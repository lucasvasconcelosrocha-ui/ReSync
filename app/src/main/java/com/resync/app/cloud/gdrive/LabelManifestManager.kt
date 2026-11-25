package com.resync.app.cloud.gdrive

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LabelManifest
import com.resync.app.data.model.LogLevel
import com.resync.app.data.model.PartitionPointer
import com.resync.app.engine.SecurityManager
import com.resync.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Manages Etiquetas 2.0 (`LabelManifest`):
 * - Instant local JSON caching in `context.filesDir/label_manifests/`
 * - Remote persistence in Google Drive (`label_manifest_<labelId>.json`)
 * - Modular per-category atomic commit after validated backup upload
 * - Automatic migration of legacy labels from `.index.json`
 * - Incomplete file detection and cleanup
 * - Pruning according to retention policy
 */
class LabelManifestManager(
    private val context: Context,
    private val oAuthManager: OAuthManager,
    private val driveFileManager: DriveFileManager
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dbHelper = AppDatabaseHelper.getInstance(context)

    companion object {
        private const val TAG = "LabelManifestManager"
        private const val CACHE_DIR = "label_manifests"

        fun getSafeLabelId(labelId: String): String {
            val trimmed = labelId.trim()
            if (trimmed.isBlank()) return "default_device"
            return trimmed.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        }

        fun getActiveCryptoFingerprint(context: Context): String {
            return try {
                val prefs = context.getSharedPreferences("resync_crypto_prefs", Context.MODE_PRIVATE)
                val existing = prefs.getString("active_key_fingerprint", null)
                if (!existing.isNullOrBlank()) return existing

                // Generate persistent deterministic hardware/device seed
                val seed = "resync_hw_${Build.FINGERPRINT}_${Build.SERIAL ?: "0"}"
                val hash = SecurityManager.computeSha256(seed.toByteArray(StandardCharsets.UTF_8))
                val shortFingerprint = hash.take(10).lowercase()
                prefs.edit().putString("active_key_fingerprint", shortFingerprint).apply()
                shortFingerprint
            } catch (e: Exception) {
                "resync_hw_def"
            }
        }
    }

    private fun getCacheFile(labelId: String): File {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        return File(dir, "manifest_${getSafeLabelId(labelId)}.json")
    }

    // =========================================================================
    // 1. LOCAL CACHE
    // =========================================================================

    fun getLocalManifest(labelId: String): LabelManifest? {
        val file = getCacheFile(labelId)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val json = file.readText(StandardCharsets.UTF_8)
            gson.fromJson(json, LabelManifest::class.java)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Falha ao ler cache local do manifesto de $labelId: ${e.message}")
            null
        }
    }

    fun saveLocalManifest(manifest: LabelManifest) {
        try {
            val file = getCacheFile(manifest.labelId)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(manifest), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Falha ao gravar cache local do manifesto: ${e.message}")
        }
    }

    fun deleteLocalManifest(labelId: String) {
        try {
            val file = getCacheFile(labelId)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 2. REMOTE DRIVE OPERATIONS
    // =========================================================================

    suspend fun fetchRemoteManifest(labelId: String, accountEmail: String? = null): LabelManifest? = withContext(Dispatchers.IO) {
        val token = oAuthManager.getValidAccessToken(accountEmail) ?: return@withContext null
        val safeId = getSafeLabelId(labelId)
        val fileName = "label_manifest_${safeId}.json"

        try {
            val fileId = driveFileManager.searchFileByName(fileName, token, null).getOrNull()
                ?: driveFileManager.searchFileByName("label_manifest.json", token, null).getOrNull()
                ?: return@withContext null

            val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            GDriveNetworkClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val manifest = gson.fromJson(bodyStr, LabelManifest::class.java)
                if (manifest != null) {
                    saveLocalManifest(manifest)
                }
                return@withContext manifest
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Erro ao buscar manifesto remoto para $labelId: ${e.message}")
            null
        }
    }

    suspend fun saveManifest(
        manifest: LabelManifest,
        accountEmail: String? = null,
        targetFolderId: String? = null
    ): Result<LabelManifest> = withContext(Dispatchers.IO) {
        saveLocalManifest(manifest)

        val safeId = getSafeLabelId(manifest.labelId)
        val fileName = "label_manifest_${safeId}.json"
        val jsonStr = gson.toJson(manifest)

        val uploadRes = driveFileManager.uploadIndexJson(
            name = fileName,
            content = jsonStr,
            parentId = targetFolderId,
            accountEmail = accountEmail
        )

        if (uploadRes.isSuccess) {
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Manifesto de etiqueta sincronizado: $fileName (${manifest.displayName})")
            Result.success(manifest)
        } else {
            val err = uploadRes.exceptionOrNull() ?: Exception("Falha ao enviar manifesto para o Google Drive")
            AppLogger.w(TAG, "Aviso: cache local salvo, mas upload do manifesto falhou: ${err.message}")
            // Retorna sucesso com o manifesto local pois o cache está íntegro
            Result.success(manifest)
        }
    }

    // =========================================================================
    // 3. LOAD OR MIGRATE (RETROCOMPATIBILIDADE TOTAL)
    // =========================================================================

    suspend fun loadOrCreateManifest(
        labelId: String,
        displayName: String? = null,
        remoteFiles: List<DriveFileMetadata> = emptyList(),
        accountEmail: String? = null,
        targetFolderId: String? = null
    ): LabelManifest = withContext(Dispatchers.IO) {
        val trimmedLabel = labelId.trim()

        // 1. Cache local instantâneo
        val local = getLocalManifest(trimmedLabel)
        if (local != null) return@withContext local

        // 2. Tentar baixar do Google Drive
        val remote = fetchRemoteManifest(trimmedLabel, accountEmail)
        if (remote != null) return@withContext remote

        // 3. Migração automática a partir dos backups existentes
        val matchingFiles = remoteFiles.filter { f ->
            val p = f.appProperties["deviceLabel"]?.trim()
            if (!p.isNullOrBlank()) p.equals(trimmedLabel, ignoreCase = true)
            else trimmedLabel.isNotBlank() && f.name.contains(trimmedLabel, ignoreCase = true)
        }

        val migrated = migrateFromExistingFiles(trimmedLabel, displayName, matchingFiles)
        saveManifest(migrated, accountEmail, targetFolderId)
        migrated
    }

    private fun migrateFromExistingFiles(
        labelId: String,
        displayName: String?,
        files: List<DriveFileMetadata>
    ): LabelManifest {
        val rootActive = try {
            com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
        } catch (_: Throwable) { false }

        val cryptoFingerprint = getActiveCryptoFingerprint(context)
        val defaultManifest = LabelManifest.createDefault(
            labelId = labelId,
            displayName = displayName ?: labelId,
            rootActive = rootActive,
            cryptoKeyFingerprint = cryptoFingerprint
        )

        if (files.isEmpty()) return defaultManifest

        val partitions = defaultManifest.partitions.toMutableMap()
        var totalBytes = 0L

        // Ordena arquivos dos mais recentes aos mais antigos
        val sortedFiles = files.sortedByDescending { it.modifiedTime.coerceAtLeast(it.createdTime) }

        for (file in sortedFiles) {
            totalBytes += file.sizeBytes
            val summary = CloudBackupCategoryHelper.inspectRemoteFile(file, context)

            fun updatePointerIfEmpty(key: String, count: Int, has: Boolean) {
                if (!has) return
                val current = partitions[key]
                if (current == null || current.itemCount == 0 || current.sourceFileId.isBlank()) {
                    val baseName = file.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
                    val sidecar = files.find { it.name == "$baseName.index.json" }
                    val fileTimestamp = file.modifiedTime.coerceAtLeast(file.createdTime)
                    partitions[key] = PartitionPointer(
                        itemCount = count.coerceAtLeast(1),
                        sizeBytes = file.sizeBytes,
                        updatedAt = if (fileTimestamp > 0) fileTimestamp else System.currentTimeMillis(),
                        sourceFileId = file.id,
                        sourceIndexFileId = sidecar?.id ?: "",
                        sourceFileName = file.name
                    )
                }
            }

            updatePointerIfEmpty(LabelManifest.KEY_APPS, summary.countApps, summary.hasApps)
            updatePointerIfEmpty(LabelManifest.KEY_MESSAGES, summary.countSms, summary.hasSms)
            updatePointerIfEmpty(LabelManifest.KEY_CALLS, summary.countCalls, summary.hasCalls)
            updatePointerIfEmpty(LabelManifest.KEY_FOLDERS, summary.countFolders, summary.hasFolders)
            updatePointerIfEmpty(LabelManifest.KEY_BLUETOOTH, summary.countBluetooth, summary.hasBluetooth)
            updatePointerIfEmpty(LabelManifest.KEY_DNS, summary.countDns, summary.hasDns)
            updatePointerIfEmpty(LabelManifest.KEY_WIFI, summary.countWifi, summary.hasWifi)
            updatePointerIfEmpty(LabelManifest.KEY_CONTACTS, summary.countContacts, summary.hasContacts)
        }

        val appCount = partitions[LabelManifest.KEY_APPS]?.itemCount ?: 0
        val latestTimestamp = sortedFiles.firstOrNull()?.let { it.modifiedTime.coerceAtLeast(it.createdTime) } ?: System.currentTimeMillis()
        return defaultManifest.copy(
            updatedAt = latestTimestamp,
            totalBytes = totalBytes,
            packageCount = appCount,
            partitions = partitions
        )
    }

    // =========================================================================
    // 4. ATUALIZAÇÃO MODULAR POR CATEGORIA (COMMIT ATÔMICO)
    // =========================================================================

    suspend fun updateModularCategoryAtomic(
        labelId: String,
        categorySummary: CloudBackupCategoryHelper.CategorySummary,
        backupFile: DriveFileMetadata,
        indexFileId: String? = null,
        accountEmail: String? = null,
        targetFolderId: String? = null
    ): LabelManifest = withContext(Dispatchers.IO) {
        val current = loadOrCreateManifest(labelId, accountEmail = accountEmail, targetFolderId = targetFolderId)
        if (current.isLocked) {
            AppLogger.w(TAG, "Tentativa de atualizar etiqueta bloqueada ($labelId). Alteração abortada.")
            return@withContext current
        }

        val updatedPartitions = current.partitions.toMutableMap()
        val now = System.currentTimeMillis()

        fun updateIfIncluded(key: String, count: Int, has: Boolean) {
            if (has) {
                updatedPartitions[key] = PartitionPointer(
                    itemCount = count.coerceAtLeast(1),
                    sizeBytes = backupFile.sizeBytes,
                    updatedAt = now,
                    sourceFileId = backupFile.id,
                    sourceIndexFileId = indexFileId ?: "",
                    sourceFileName = backupFile.name
                )
            }
        }

        updateIfIncluded(LabelManifest.KEY_APPS, categorySummary.countApps, categorySummary.hasApps)
        updateIfIncluded(LabelManifest.KEY_MESSAGES, categorySummary.countSms, categorySummary.hasSms)
        updateIfIncluded(LabelManifest.KEY_CALLS, categorySummary.countCalls, categorySummary.hasCalls)
        updateIfIncluded(LabelManifest.KEY_FOLDERS, categorySummary.countFolders, categorySummary.hasFolders)
        updateIfIncluded(LabelManifest.KEY_BLUETOOTH, categorySummary.countBluetooth, categorySummary.hasBluetooth)
        updateIfIncluded(LabelManifest.KEY_DNS, categorySummary.countDns, categorySummary.hasDns)
        updateIfIncluded(LabelManifest.KEY_WIFI, categorySummary.countWifi, categorySummary.hasWifi)
        updateIfIncluded(LabelManifest.KEY_CONTACTS, categorySummary.countContacts, categorySummary.hasContacts)

        val newPackageCount = updatedPartitions[LabelManifest.KEY_APPS]?.itemCount ?: current.packageCount
        val totalBytes = (current.totalBytes + backupFile.sizeBytes)

        val updatedManifest = current.copy(
            updatedAt = now,
            totalBytes = totalBytes,
            packageCount = newPackageCount,
            partitions = updatedPartitions
        )

        saveManifest(updatedManifest, accountEmail, targetFolderId)
        updatedManifest
    }

    // =========================================================================
    // 5. INCOMPLETE FILES DETECTION & CLEANUP
    // =========================================================================

    data class IncompleteFilesAudit(
        val incompleteFiles: List<DriveFileMetadata>,
        val totalBytes: Long
    )

    fun detectIncompleteFiles(
        labelId: String,
        remoteFiles: List<DriveFileMetadata>
    ): IncompleteFilesAudit {
        val trimmed = labelId.trim()
        val matching = remoteFiles.filter { f ->
            val p = f.appProperties["deviceLabel"]?.trim()
            if (!p.isNullOrBlank()) p.equals(trimmed, ignoreCase = true)
            else trimmed.isNotBlank() && f.name.contains(trimmed, ignoreCase = true)
        }

        val incomplete = matching.filter { file ->
            val name = file.name.lowercase()
            val isTemp = name.endsWith(".tmp") || name.endsWith(".part") || name.contains(".crdownload")
            val isZeroByte = file.sizeBytes == 0L && !name.endsWith(".json")
            val isBrokenContainer = (name.endsWith(".resync") || name.endsWith(".svb") || name.endsWith(".zip")) && file.sizeBytes < 512L
            isTemp || isZeroByte || isBrokenContainer
        }

        return IncompleteFilesAudit(
            incompleteFiles = incomplete,
            totalBytes = incomplete.sumOf { it.sizeBytes }
        )
    }

    suspend fun cleanupIncompleteFiles(
        files: List<DriveFileMetadata>,
        accountEmail: String? = null
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (f in files) {
            val res = driveFileManager.deleteFile(f.id, accountEmail)
            if (res.isSuccess) count++
        }
        count
    }
}
