package com.resync.app.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.cloud.gdrive.BackupIndexManager
import com.resync.app.cloud.gdrive.DriveFileMetadata
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.AppBackupStatus
import com.resync.app.data.model.BackupManifest
import com.resync.app.data.model.BackupPaths
import com.resync.app.data.model.SyncStatus
import com.resync.app.data.room.AppRoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance background detector for application backups.
 * Compares installed packages against:
 * 1. Internal storage backup directory (containers and sidecar *.index.json files)
 * 2. SQLite and Room cache of backup manifests
 * 3. Google Drive cached backup indices (without per-app network requests)
 */
class AppBackupDetector(
    private val context: Context,
    private val roomDb: AppRoomDatabase,
    private val backupIndexManager: BackupIndexManager,
    private val dbHelper: AppDatabaseHelper
) {
    companion object {
        private const val TAG = "AppBackupDetector"
        private const val PREFS_NAME = "resync_app_backup_status_cache"
        private const val KEY_STATUS_MAP_JSON = "status_map_json"
        private const val KEY_LAST_DETECTION = "last_detection_timestamp"
    }

    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, AppBackupStatus>()
    private val isDetecting = AtomicBoolean(false)
    private var lastDriveRefreshTime = 0L

    init {
        loadPersistedCache()
    }

    /**
     * Loads persisted statuses into memory cache immediately on startup (0ms latency).
     */
    fun loadPersistedCache(): Map<String, AppBackupStatus> {
        if (memoryCache.isNotEmpty()) return memoryCache

        val json = prefs.getString(KEY_STATUS_MAP_JSON, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Map<String, AppBackupStatus>>() {}.type
                val cached: Map<String, AppBackupStatus> = gson.fromJson(json, type)
                memoryCache.putAll(cached)
                Log.d(TAG, "Carregados ${cached.size} status de backup do cache persistente.")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao desserializar cache persistente de status de backup: ${e.message}")
            }
        }
        return memoryCache
    }

    fun getCachedStatuses(): Map<String, AppBackupStatus> {
        return memoryCache
    }

    /**
     * Detects backup status for all installed apps and cataloged packages across:
     * a) Memória interna (local folder, SQLite records, Room cache)
     * b) Google Drive (cached index in Room, remote drive backups sidecars)
     *
     * Never performs network requests per app.
     * Updates Drive index at most once per screen session.
     * If offline or without login, silently relies on internal memory & cached indices.
     */
    suspend fun detectBackups(
        installedPackages: List<String>,
        remoteDriveBackups: List<DriveFileMetadata> = emptyList(),
        triggerDriveRefresh: (suspend () -> Unit)? = null,
        forceRefreshDrive: Boolean = false
    ): Map<String, AppBackupStatus> = withContext(Dispatchers.IO) {
        if (!isDetecting.compareAndSet(false, true)) {
            // Already running, return current memory cache
            return@withContext memoryCache
        }

        try {
            val localLatestMap = mutableMapOf<String, Long>()
            val driveLatestMap = mutableMapOf<String, Long>()

            // 1. Scan Room Index Database (Instant cached manifests)
            try {
                val indexDao = roomDb.backupIndexDao()
                val cachedEntities = indexDao.getAll()
                val localDirs = BackupPaths.getAllLocalBackupDirs(context)

                for (entity in cachedEntities) {
                    val manifest = try {
                        backupIndexManager.decompressManifestJson(entity.manifestGzipBase64)
                    } catch (e: Exception) {
                        null
                    } ?: continue

                    val timestamp = if (manifest.createdAt > 0L) manifest.createdAt else entity.cachedAt

                    var isLocal = false
                    var isDrive = false

                    if (!entity.driveFileId.isNullOrBlank()) {
                        isDrive = true
                    }

                    if (!entity.filePath.isNullOrBlank()) {
                        val f = File(entity.filePath)
                        if (f.exists() && f.length() > 0L) {
                            isLocal = true
                        }
                    }

                    if (!isLocal) {
                        val candidateExists = localDirs.any { dir ->
                            val names = listOf(
                                "${entity.backupId}${BackupPaths.BACKUP_FILE_EXTENSION}",
                                "${entity.backupId}${BackupPaths.LEGACY_BACKUP_FILE_EXTENSION}",
                                "${entity.backupId}${BackupPaths.ZIP_FILE_EXTENSION}",
                                "${entity.backupTitle}${BackupPaths.BACKUP_FILE_EXTENSION}",
                                "${entity.backupTitle}${BackupPaths.LEGACY_BACKUP_FILE_EXTENSION}",
                                "${entity.backupTitle}${BackupPaths.ZIP_FILE_EXTENSION}"
                            )
                            names.any { name ->
                                val f = File(dir, name)
                                f.exists() && f.length() > 0L
                            }
                        }
                        if (candidateExists) {
                            isLocal = true
                        }
                    }

                    // Default to local if neither was explicitly tagged and driveId is absent
                    if (!isDrive && !isLocal) {
                        isLocal = true
                    }

                    val packages = extractPackagesFromManifest(manifest)
                    for (pkg in packages) {
                        if (isLocal) {
                            localLatestMap[pkg] = maxOf(localLatestMap[pkg] ?: 0L, timestamp)
                        }
                        if (isDrive) {
                            driveLatestMap[pkg] = maxOf(driveLatestMap[pkg] ?: 0L, timestamp)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao consultar índices do Room: ${e.message}")
            }

            // 2. Scan Local Storage Directories for sidecars (*.index.json) and containers
            try {
                val localDirs = BackupPaths.getAllLocalBackupDirs(context)
                for (dir in localDirs) {
                    if (!dir.exists() || !dir.isDirectory) continue
                    val files = dir.listFiles() ?: continue

                    // 2a. Sidecars
                    val sidecars = files.filter { it.name.endsWith(".index.json", ignoreCase = true) }
                    for (sidecar in sidecars) {
                        try {
                            val bytes = sidecar.readBytes()
                            val manifest = backupIndexManager.readManifestFromIndexBytes(bytes)
                            val timestamp = if (manifest.createdAt > 0L) manifest.createdAt else sidecar.lastModified()
                            val packages = extractPackagesFromManifest(manifest)
                            for (pkg in packages) {
                                localLatestMap[pkg] = maxOf(localLatestMap[pkg] ?: 0L, timestamp)
                            }
                        } catch (_: Exception) {}
                    }

                    // 2b. Containers without sidecars that might not be in Room yet
                    val containers = files.filter {
                        (it.name.endsWith(BackupPaths.BACKUP_FILE_EXTENSION, ignoreCase = true) ||
                         it.name.endsWith(BackupPaths.LEGACY_BACKUP_FILE_EXTENSION, ignoreCase = true) ||
                         it.name.endsWith(BackupPaths.ZIP_FILE_EXTENSION, ignoreCase = true)) &&
                        !it.name.endsWith(".index.json", ignoreCase = true)
                    }
                    for (container in containers) {
                        val baseName = container.nameWithoutExtension
                        val hasSidecar = File(dir, "$baseName.index.json").exists() || File(dir, "${container.name}.index.json").exists()
                        if (!hasSidecar) {
                            try {
                                val manifest = backupIndexManager.inspectLocalBackup(container)
                                val timestamp = if (manifest.createdAt > 0L) manifest.createdAt else container.lastModified()
                                val packages = extractPackagesFromManifest(manifest)
                                for (pkg in packages) {
                                    localLatestMap[pkg] = maxOf(localLatestMap[pkg] ?: 0L, timestamp)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao inspecionar diretórios locais: ${e.message}")
            }

            // 3. Scan SQLite BackupRecords for syncStatus / destination flags
            try {
                val records = dbHelper.getAllBackupRecords()
                for (rec in records) {
                    val isLocal = rec.filePath.isNotBlank() && File(rec.filePath).exists() && File(rec.filePath).length() > 0L
                    val isDrive = !rec.driveFileId.isNullOrBlank() ||
                            rec.syncStatus == SyncStatus.SYNCED ||
                            rec.destination.contains("Drive", ignoreCase = true)

                    // Match package from title or filePath if single app
                    val matchedPkg = installedPackages.firstOrNull { pkg ->
                        rec.title.equals(pkg, ignoreCase = true) ||
                        rec.filePath.contains(pkg, ignoreCase = true)
                    }
                    if (matchedPkg != null) {
                        if (isLocal) {
                            localLatestMap[matchedPkg] = maxOf(localLatestMap[matchedPkg] ?: 0L, rec.timestamp)
                        }
                        if (isDrive) {
                            driveLatestMap[matchedPkg] = maxOf(driveLatestMap[matchedPkg] ?: 0L, rec.timestamp)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao ler registros do SQLite: ${e.message}")
            }

            // 4. Google Drive Index check (at most once per screen opening)
            val now = System.currentTimeMillis()
            if (forceRefreshDrive && (now - lastDriveRefreshTime > 60_000L) && triggerDriveRefresh != null) {
                lastDriveRefreshTime = now
                try {
                    triggerDriveRefresh()
                } catch (e: Exception) {
                    Log.d(TAG, "Atualização do Drive ignorada silenciosamente: ${e.message}")
                }
            }

            // Check any remote drive backups in memory
            for (remote in remoteDriveBackups) {
                val pkgProp = remote.appProperties["packageName"]
                if (!pkgProp.isNullOrBlank()) {
                    driveLatestMap[pkgProp] = maxOf(driveLatestMap[pkgProp] ?: 0L, remote.modifiedTime)
                }
            }

            // 5. Combine and construct AppBackupStatus for each app
            val allPackages = (installedPackages + localLatestMap.keys + driveLatestMap.keys).distinct()
            val newMap = HashMap<String, AppBackupStatus>(allPackages.size)

            for (pkg in allPackages) {
                val status = AppBackupStatus.create(
                    packageName = pkg,
                    localTime = localLatestMap[pkg],
                    driveTime = driveLatestMap[pkg]
                )
                newMap[pkg] = status
            }

            memoryCache.clear()
            memoryCache.putAll(newMap)
            saveToPersistedCache(newMap)

            Log.d(TAG, "Detecção de backups concluída para ${newMap.size} pacotes. (${newMap.count { it.value.hasBackup }} com backup)")
            return@withContext memoryCache
        } finally {
            isDetecting.set(false)
        }
    }

    private fun extractPackagesFromManifest(manifest: BackupManifest): Set<String> {
        val packages = mutableSetOf<String>()
        for (item in manifest.items) {
            val metaPkg = item.metadata["packageName"]?.trim()
            if (!metaPkg.isNullOrBlank() && !metaPkg.endsWith(".json")) {
                packages.add(metaPkg)
                continue
            }
            val entry = item.archiveEntryPath
            if (entry.startsWith("apps/")) {
                val sub = entry.removePrefix("apps/").substringBefore("/").trim()
                if (sub.isNotBlank() && !sub.endsWith(".json") && sub != "splits" && !sub.contains("/")) {
                    packages.add(sub)
                }
            }
        }
        return packages
    }

    private fun saveToPersistedCache(map: Map<String, AppBackupStatus>) {
        try {
            val json = gson.toJson(map)
            prefs.edit()
                .putString(KEY_STATUS_MAP_JSON, json)
                .putLong(KEY_LAST_DETECTION, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar cache persistente de status de backup: ${e.message}")
        }
    }
}
