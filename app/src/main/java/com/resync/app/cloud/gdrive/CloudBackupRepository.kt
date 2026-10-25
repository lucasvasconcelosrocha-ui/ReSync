package com.resync.app.cloud.gdrive

import android.content.Context
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.BackupRecord
import com.resync.app.data.model.BackupStatus
import com.resync.app.data.model.CloudProviderType
import com.resync.app.data.model.LogLevel
import com.resync.app.data.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudBackupRepository(
    private val context: Context,
    val oAuthManager: OAuthManager,
    val driveFileManager: DriveFileManager,
    val uploadManager: UploadManager,
    val downloadManager: DownloadManager,
    val integrityManager: IntegrityManager
) {
    private val dbHelper = AppDatabaseHelper.getInstance(context)
    val backupIndexManager = BackupIndexManager(context, oAuthManager, driveFileManager)

    /**
     * Fetch list of all existing backups stored on user's Google Drive
     */
    suspend fun fetchRemoteBackups(): Result<List<DriveFileMetadata>> = withContext(Dispatchers.IO) {
        driveFileManager.listRemoteBackups()
    }

    suspend fun fetchRemoteManifest(fileId: String, accountEmail: String? = null): Result<com.resync.app.data.model.BackupManifest> = withContext(Dispatchers.IO) {
        backupIndexManager.inspectDriveBackup(fileId, fileId, 0L, accountEmail)
    }


    fun pauseUpload() = uploadManager.pause()
    fun resumeUpload() = uploadManager.resume()
    fun cancelUpload() = uploadManager.cancel()

    fun pauseDownload() = downloadManager.pause()
    fun resumeDownload() = downloadManager.resume()
    fun cancelDownload() = downloadManager.cancel()

    /**
     * Upload local .svb backup to Google Drive with Resumable Upload and rigorous integrity check
     */
    suspend fun uploadLocalBackup(
        backupRecord: BackupRecord,
        accountEmail: String? = null,
        onProgress: (UploadProgressInfo) -> Unit
    ): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
        val localFile = File(backupRecord.filePath)
        if (!localFile.exists()) {
            val err = "Arquivo local não encontrado: ${backupRecord.filePath}"
            onProgress(UploadProgressInfo("Falha", 0, 0, 0f, 0f, 0, isFinished = true, error = err))
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        // 1. Check storage quota
        onProgress(UploadProgressInfo("Preparando backup...", 0, localFile.length(), 0.02f, 0f, 0))
        val quotaResult = driveFileManager.getStorageQuota(accountEmail)
        if (quotaResult.isSuccess) {
            val quota = quotaResult.getOrThrow()
            if (quota.isStorageFull || (quota.limitBytes - quota.usageBytes) < localFile.length()) {
                val err = InsufficientStorageException("Não há espaço suficiente no Google Drive para concluir este backup (${(localFile.length() / (1024 * 1024))} MB necessários).")
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", err.message ?: "")
                onProgress(UploadProgressInfo("Falha", 0, localFile.length(), 0f, 0f, 0, isFinished = true, error = err.message))
                return@withContext Result.failure(err)
            }
        }

        // 2. Ensure folder hierarchy in Google Drive
        onProgress(UploadProgressInfo("Conectando ao Google Drive...", 0, localFile.length(), 0.05f, 0f, 0))
        val hierarchyResult = driveFileManager.ensureBackupHierarchy(accountEmail)
        if (hierarchyResult.isFailure) {
            val ex = hierarchyResult.exceptionOrNull()
            val isConsentNeeded = ex is AuthIntentRequiredException
            val is401 = isConsentNeeded || ex is GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
            val friendlyError = if (isConsentNeeded) {
                "Autorização da conta Google necessária para continuar o envio ao Google Drive."
            } else if (is401) {
                val accountDesc = if (!accountEmail.isNullOrBlank()) " da conta $accountEmail" else ""
                "A sessão do Google Drive$accountDesc expirou. É necessário reconectar sua conta para continuar.\nCódigo técnico: HTTP 401"
            } else {
                ex?.message ?: "Falha ao acessar pasta no Google Drive."
            }
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", friendlyError)
            onProgress(UploadProgressInfo("Falha", 0, localFile.length(), 0f, 0f, 0, isFinished = true, error = friendlyError))
            return@withContext Result.failure(ex ?: Exception(friendlyError))
        }
        val hierarchy = hierarchyResult.getOrNull()
        val targetFolderId = hierarchy?.backupsFolderId ?: hierarchy?.rootFolderId

        // 3. Resumable Upload
        val appProps = mapOf(
            "backupId" to backupRecord.id,
            "title" to backupRecord.title,
            "version" to "1.0",
            "isEncrypted" to backupRecord.isEncrypted.toString(),
            "sha256" to backupRecord.checksumSha256
        )

        dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.UPLOADING)

        val uploadResult = uploadManager.executeResumableUpload(
            localFile = localFile,
            targetFolderId = targetFolderId,
            backupMetadata = appProps,
            accountEmail = accountEmail,
            onProgress = onProgress
        )

        if (uploadResult.isFailure) {
            val ex = uploadResult.exceptionOrNull()
            dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.FAILED)
            onProgress(
                UploadProgressInfo(
                    stage = "Falha no envio",
                    bytesUploaded = 0,
                    totalBytes = localFile.length(),
                    progressPercent = 0f,
                    speedMBps = 0f,
                    estimatedRemainingSeconds = 0,
                    isFinished = true,
                    error = ex?.message ?: "Falha na transferência para o Google Drive."
                )
            )
            return@withContext uploadResult
        }

        val driveFile = uploadResult.getOrThrow()
        if (driveFile.id.isBlank()) {
            val missingIdErr = "Google Drive não retornou um fileId válido para o arquivo enviado."
            dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.FAILED)
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", missingIdErr)
            onProgress(UploadProgressInfo("Falha", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = true, error = missingIdErr))
            return@withContext Result.failure(IllegalStateException(missingIdErr))
        }

        // 4. Rigorous Post-Upload Verification
        dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.VERIFYING)
        onProgress(UploadProgressInfo("Verificando arquivo no Google Drive...", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = false))

        // Confirm size directly with Drive API metadata
        val remoteMetaRes = driveFileManager.getFileMetadata(driveFile.id, accountEmail)
        val verifiedFile = remoteMetaRes.getOrNull() ?: driveFile
        val verifiedSize = verifiedFile.sizeBytes

        if (verifiedSize <= 0L || verifiedSize != localFile.length()) {
            val sizeMismatchMsg = "Inconsistência no tamanho do arquivo no Google Drive: esperado ${localFile.length()} bytes, retornado $verifiedSize bytes (0 bytes ou incompleto)."
            dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.FAILED)
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", sizeMismatchMsg)
            onProgress(UploadProgressInfo("Falha", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = true, error = sizeMismatchMsg))
            return@withContext Result.failure(IllegalStateException(sizeMismatchMsg))
        }

        dbHelper.insertLog(
            LogLevel.INFO,
            "GoogleDrive",
            "Arquivo verificado no Google Drive: '${verifiedFile.name}' (Tamanho confirmado: $verifiedSize bytes, ID retornado: '${verifiedFile.id}')"
        )
        dbHelper.insertLog(
            LogLevel.INFO,
            "GoogleDrive",
            "arquivo '${verifiedFile.name}' enviado para pasta '$targetFolderId'"
        )

        // Integrity Validation (Checksum & MD5) using streaming single-pass validation
        val integrity = integrityManager.verifyUploadIntegrity(localFile, driveFile, driveFile.md5Checksum)
        if (!integrity.isVerified) {
            dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.FAILED)
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha na verificação de integridade pós-upload: ${integrity.failureReason}")
            onProgress(UploadProgressInfo("Falha", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = true, error = integrity.failureReason))
            return@withContext Result.failure(IllegalStateException(integrity.failureReason ?: "Falha na verificação de integridade."))
        }

        // 4.1 Enviar índice complementar (svb_XXXX.index.json) para consulta rápida sem baixar o container
        try {
            val baseName = localFile.nameWithoutExtension
            val localSidecar = File(localFile.parentFile, "$baseName.index.json")
            val manifest = if (localSidecar.exists() && localSidecar.length() > 0L) {
                backupIndexManager.readManifestFromIndexBytes(localSidecar.readBytes())
            } else {
                com.resync.app.engine.ArchiveEngine.readManifest(localFile)
            }
            val indexJson = com.google.gson.Gson().toJson(manifest)
            val indexName = "$baseName.index.json"
            driveFileManager.uploadIndexJson(indexName, indexJson, targetFolderId, accountEmail)
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Índice complementar sincronizado: $indexName")
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "GoogleDrive", "Aviso ao salvar índice complementar: ${e.message}")
        }

        // 5. Update local record with SINGLE SOURCE OF TRUTH: SYNCED
        val updatedRecord = backupRecord.copy(
            destination = "Google Drive",
            status = BackupStatus.COMPLETED,
            syncStatus = SyncStatus.SYNCED,
            driveFileId = driveFile.id
        )
        dbHelper.insertBackupRecord(updatedRecord)
        dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Backup verificado com sucesso no Google Drive: ${driveFile.name}")

        // Refresh remote storage quota in database
        val quotaAfter = driveFileManager.getStorageQuota().getOrNull()
        if (quotaAfter != null) {
            dbHelper.updateCloudAccountQuota(
                CloudProviderType.GOOGLE_DRIVE.name,
                quotaAfter.usageBytes,
                quotaAfter.limitBytes
            )
        }

        onProgress(UploadProgressInfo("✓ Backup sincronizado", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = true))
        Result.success(driveFile)
    }

    /**
     * Check real state of backups against Google Drive and update Single Source of Truth
     */
    suspend fun verifyRemoteStatusForLocalBackups(): List<BackupRecord> = withContext(Dispatchers.IO) {
        val remoteListRes = driveFileManager.listRemoteBackups()
        val allLocal = dbHelper.getAllBackupRecords()
        if (remoteListRes.isSuccess) {
            val remoteFiles = remoteListRes.getOrThrow()
            val remoteIds = remoteFiles.map { it.id }.toSet()
            val remoteNames = remoteFiles.map { it.name }.toSet()

            for (local in allLocal) {
                val fileName = File(local.filePath).name
                val isPresentInDrive = (local.driveFileId != null && remoteIds.contains(local.driveFileId)) ||
                        remoteNames.contains(fileName)

                if (local.syncStatus == SyncStatus.SYNCED && !isPresentInDrive) {
                    // File was deleted from Google Drive! Revert to LOCAL_ONLY
                    dbHelper.updateBackupSyncStatus(local.id, SyncStatus.LOCAL_ONLY, null)
                    dbHelper.insertLog(LogLevel.WARN, "GoogleDrive", "Backup ${local.title} não encontrado no Google Drive. Marcado como Não Sincronizado.")
                } else if (local.syncStatus != SyncStatus.SYNCED && isPresentInDrive) {
                    // File was found on Google Drive!
                    val matching = remoteFiles.find { it.id == local.driveFileId || it.name == fileName }
                    dbHelper.updateBackupSyncStatus(local.id, SyncStatus.SYNCED, matching?.id)
                }
            }
        }
        dbHelper.getAllBackupRecords()
    }

    /**
     * Download backup from Google Drive and prepare for restoration
     */
    suspend fun downloadRemoteBackup(
        remoteFile: DriveFileMetadata,
        onProgress: (DownloadProgressInfo) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "gdrive_downloads")
        tempDir.mkdirs()
        val destinationFile = File(tempDir, remoteFile.name)

        downloadManager.downloadBackupFile(
            fileId = remoteFile.id,
            destinationFile = destinationFile,
            totalBytesEstimated = remoteFile.sizeBytes,
            onProgress = onProgress
        )
    }

    /**
     * Delete remote backup from Google Drive
     */
    suspend fun deleteRemoteBackup(fileId: String, accountEmail: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        driveFileManager.deleteFile(fileId, accountEmail)
    }

    /**
     * Inspeciona índice do backup no Drive: primeiro sidecar .index.json, depois diretório central via Range.
     * Nunca faz download do container de 29 GB.
     */
    suspend fun fetchCompanionIndexOrManifest(
        containerFileId: String,
        containerFileName: String,
        accountEmail: String? = null,
        expectedSizeBytes: Long = 0L,
        onProgress: ((stage: String, percent: Float) -> Unit)? = null
    ): Result<com.resync.app.data.model.BackupManifest> = withContext(Dispatchers.IO) {
        backupIndexManager.inspectDriveBackup(
            fileId = containerFileId,
            expectedName = containerFileName,
            expectedSizeBytes = expectedSizeBytes,
            accountEmail = accountEmail,
            onProgress = onProgress
        )
    }
}

