package com.resync.app.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.cloud.gdrive.BackupIndexManager
import com.resync.app.cloud.gdrive.DriveFileMetadata
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.AppBackupPoint
import com.resync.app.data.model.AppBackupStatus
import com.resync.app.data.model.AppInfo
import com.resync.app.data.model.BackupItemType
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
        private const val KEY_UNINSTALLED_APPS_JSON = "uninstalled_apps_json"
        private const val KEY_LAST_DETECTION = "last_detection_timestamp"
    }

    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, AppBackupStatus>()
    private val uninstalledAppsMap = ConcurrentHashMap<String, AppInfo>()
    private val appBackupPoints = ConcurrentHashMap<String, MutableList<AppBackupPoint>>()
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

        val uninstalledJson = prefs.getString(KEY_UNINSTALLED_APPS_JSON, null)
        if (!uninstalledJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<AppInfo>>() {}.type
                val cachedList: List<AppInfo> = gson.fromJson(uninstalledJson, type)
                uninstalledAppsMap.clear()
                cachedList.forEach { uninstalledAppsMap[it.packageName] = it }
                Log.d(TAG, "Carregados ${cachedList.size} apps desinstalados com backup do cache persistente.")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao desserializar cache de apps desinstalados: ${e.message}")
            }
        }

        return memoryCache
    }

    fun getUninstalledAppsWithBackup(): List<AppInfo> {
        return uninstalledAppsMap.values.toList().sortedBy { it.appName.lowercase() }
    }

    fun getCachedStatuses(): Map<String, AppBackupStatus> {
        return memoryCache
    }

    fun getBackupPointsForApp(packageName: String): List<AppBackupPoint> {
        return appBackupPoints[packageName]?.distinctBy { (it.driveFileId ?: "") + it.filePath + it.timestamp }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    fun getAllBackupPoints(): Map<String, List<AppBackupPoint>> {
        return appBackupPoints.mapValues { (_, list) ->
            list.distinctBy { (it.driveFileId ?: "") + it.filePath + it.timestamp }.sortedByDescending { it.timestamp }
        }
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
            val newPointsMap = mutableMapOf<String, MutableList<AppBackupPoint>>()

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

                    // Extract detailed app backup points from manifest
                    val points = extractAppPointsFromManifest(
                        manifest = manifest,
                        isLocal = isLocal,
                        isDrive = isDrive,
                        filePath = entity.filePath ?: "",
                        driveFileId = entity.driveFileId,
                        containerSizeBytes = entity.sizeBytes
                    )
                    for ((pkg, pt) in points) {
                        newPointsMap.getOrPut(pkg) { mutableListOf() }.add(pt)
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
                            val points = extractAppPointsFromManifest(
                                manifest = manifest,
                                isLocal = true,
                                isDrive = false,
                                filePath = sidecar.absolutePath.removeSuffix(".index.json"),
                                driveFileId = null,
                                containerSizeBytes = sidecar.length()
                            )
                            for ((pkg, pt) in points) {
                                newPointsMap.getOrPut(pkg) { mutableListOf() }.add(pt)
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
                                val points = extractAppPointsFromManifest(
                                    manifest = manifest,
                                    isLocal = true,
                                    isDrive = false,
                                    filePath = container.absolutePath,
                                    driveFileId = null,
                                    containerSizeBytes = container.length()
                                )
                                for ((pkg, pt) in points) {
                                    newPointsMap.getOrPut(pkg) { mutableListOf() }.add(pt)
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

                    // Match package from title or filePath if single app, or if title/file looks like a package name
                    val matchedPkg = installedPackages.firstOrNull { pkg ->
                        rec.title.equals(pkg, ignoreCase = true) ||
                        rec.filePath.contains(pkg, ignoreCase = true)
                    } ?: run {
                        val cleanTitle = rec.title.trim()
                        if (cleanTitle.contains(".") && !cleanTitle.contains(" ") && cleanTitle.length > 3) {
                            cleanTitle
                        } else {
                            val candidate = rec.filePath.substringAfterLast("/").substringBeforeLast(".")
                            if (candidate.contains(".") && !candidate.contains(" ") && candidate.length > 3) candidate else null
                        }
                    }
                    if (matchedPkg != null) {
                        if (isLocal) {
                            localLatestMap[matchedPkg] = maxOf(localLatestMap[matchedPkg] ?: 0L, rec.timestamp)
                        }
                        if (isDrive) {
                            driveLatestMap[matchedPkg] = maxOf(driveLatestMap[matchedPkg] ?: 0L, rec.timestamp)
                        }
                        val point = AppBackupPoint(
                            backupId = rec.id,
                            backupTitle = rec.title,
                            timestamp = rec.timestamp,
                            isLocal = isLocal,
                            isDrive = isDrive,
                            filePath = rec.filePath,
                            driveFileId = rec.driveFileId,
                            containerSizeBytes = rec.sizeBytes,
                            appSizeBytes = rec.sizeBytes,
                            versionName = null,
                            hasApk = true,
                            hasData = true,
                            isFullPackage = false,
                            itemIds = emptyList(),
                            appName = rec.title
                        )
                        newPointsMap.getOrPut(matchedPkg) { mutableListOf() }.add(point)
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
                    val point = AppBackupPoint(
                        backupId = remote.id,
                        backupTitle = remote.name,
                        timestamp = remote.modifiedTime,
                        isLocal = false,
                        isDrive = true,
                        filePath = "",
                        driveFileId = remote.id,
                        containerSizeBytes = remote.sizeBytes,
                        appSizeBytes = remote.sizeBytes,
                        versionName = remote.appProperties["versionName"],
                        hasApk = true,
                        hasData = true,
                        isFullPackage = false,
                        itemIds = emptyList()
                    )
                    newPointsMap.getOrPut(pkgProp) { mutableListOf() }.add(point)
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
            appBackupPoints.clear()
            for ((pkg, list) in newPointsMap) {
                appBackupPoints[pkg] = list.distinctBy { (it.driveFileId ?: "") + it.filePath + it.timestamp }
                    .sortedByDescending { it.timestamp }
                    .toMutableList()
            }

            // 5b. Identify uninstalled packages that have available backups
            val uninstalledPkgs = (localLatestMap.keys + driveLatestMap.keys).filter { pkg ->
                !installedPackages.contains(pkg)
            }.distinct()

            val uninstalledList = mutableListOf<AppInfo>()
            val pm = context.packageManager
            for (pkg in uninstalledPkgs) {
                val isReallyInstalled = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0)
                    }
                    true
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    false
                } catch (_: Exception) {
                    false
                }

                if (!isReallyInstalled) {
                    val points = newPointsMap[pkg] ?: emptyList()
                    val latestPt = points.maxByOrNull { it.timestamp }
                    val appName = latestPt?.appName?.takeIf { it.isNotBlank() }
                        ?: pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                    val versionName = latestPt?.versionName ?: "1.0"
                    val versionCode = latestPt?.versionCode ?: 0L
                    val sizeBytes = latestPt?.appSizeBytes ?: 0L
                    val timestamp = latestPt?.timestamp ?: System.currentTimeMillis()
                    val dateFormatted = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

                    uninstalledList.add(
                        AppInfo(
                            packageName = pkg,
                            appName = appName,
                            versionName = versionName,
                            versionCode = versionCode,
                            minSdkVersion = 26,
                            targetSdkVersion = 34,
                            sourceDir = "",
                            dataDir = "",
                            isSystemApp = false,
                            sizeBytes = sizeBytes,
                            lastInstallTime = 0L,
                            lastUpdateTime = 0L,
                            lastBackupDate = dateFormatted,
                            hasBackup = true,
                            isOutdatedBackup = false,
                            backupSize = sizeBytes,
                            isInstalled = false
                        )
                    )
                }
            }

            uninstalledAppsMap.clear()
            uninstalledList.forEach { uninstalledAppsMap[it.packageName] = it }

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

    private fun extractAppPointsFromManifest(
        manifest: BackupManifest,
        isLocal: Boolean,
        isDrive: Boolean,
        filePath: String,
        driveFileId: String?,
        containerSizeBytes: Long
    ): Map<String, AppBackupPoint> {
        val result = mutableMapOf<String, AppBackupPoint>()
        val appItems = manifest.items.filter {
            it.type in listOf(BackupItemType.APP_APK, BackupItemType.APP_SPLIT_APKS, BackupItemType.APP_DATA) ||
            it.archiveEntryPath.startsWith("apps/")
        }
        if (appItems.isEmpty()) return result

        val isFullPackage = manifest.items.any {
            it.type !in listOf(BackupItemType.APP_APK, BackupItemType.APP_SPLIT_APKS, BackupItemType.APP_DATA) &&
            !it.archiveEntryPath.startsWith("apps/") &&
            !it.archiveEntryPath.endsWith("metadata.json")
        } || appItems.mapNotNull { it.metadata["packageName"] ?: it.archiveEntryPath.removePrefix("apps/").substringBefore("/").takeIf { s -> s.isNotBlank() } }.distinct().size > 1

        val byPkg = appItems.groupBy {
            it.metadata["packageName"] ?: it.archiveEntryPath.removePrefix("apps/").substringBefore("/").takeIf { s -> s.isNotBlank() } ?: "unknown"
        }.filterKeys { it != "unknown" && !it.endsWith(".json") && it != "splits" && !it.contains("/") }

        for ((pkg, items) in byPkg) {
            val hasApk = items.any {
                it.type == BackupItemType.APP_APK ||
                it.type == BackupItemType.APP_SPLIT_APKS ||
                it.archiveEntryPath.endsWith(".apk")
            }
            val hasData = items.any {
                it.type == BackupItemType.APP_DATA ||
                it.archiveEntryPath.endsWith(".tar") ||
                it.archiveEntryPath.endsWith(".tar.gz") ||
                it.metadata["dataType"] == "internal_data"
            }
            val appSizeBytes = items.sumOf { it.sizeBytes }
            val metaVer = items.firstNotNullOfOrNull { it.metadata["versionName"] }
                ?: manifest.items.find { it.archiveEntryPath == "apps/$pkg/metadata.json" }?.metadata?.get("versionName")
            val metaCode = items.firstNotNullOfOrNull { it.metadata["versionCode"] }?.toLongOrNull() ?: 0L

            val metaAppName = items.firstNotNullOfOrNull { it.metadata["appName"] }
                ?: manifest.items.find { it.archiveEntryPath == "apps/$pkg/metadata.json" }?.metadata?.get("appName")
                ?: items.firstOrNull { it.type == BackupItemType.APP_APK }?.name?.removeSuffix(" (Base APK)")
                ?: pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }

            val point = AppBackupPoint(
                backupId = manifest.backupId,
                backupTitle = manifest.title.ifBlank { "Backup" },
                timestamp = if (manifest.createdAt > 0L) manifest.createdAt else System.currentTimeMillis(),
                isLocal = isLocal,
                isDrive = isDrive,
                filePath = filePath,
                driveFileId = driveFileId,
                containerSizeBytes = if (containerSizeBytes > 0L) containerSizeBytes else manifest.totalSizeBytes,
                appSizeBytes = appSizeBytes,
                versionName = metaVer,
                versionCode = metaCode,
                hasApk = hasApk,
                hasData = hasData,
                isFullPackage = isFullPackage,
                itemIds = items.map { it.id },
                appName = metaAppName
            )
            result[pkg] = point
        }
        return result
    }

    private fun saveToPersistedCache(map: Map<String, AppBackupStatus>) {
        try {
            val json = gson.toJson(map)
            val uninstalledJson = gson.toJson(uninstalledAppsMap.values.toList())
            prefs.edit()
                .putString(KEY_STATUS_MAP_JSON, json)
                .putString(KEY_UNINSTALLED_APPS_JSON, uninstalledJson)
                .putLong(KEY_LAST_DETECTION, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar cache persistente de status de backup: ${e.message}")
        }
    }
}
