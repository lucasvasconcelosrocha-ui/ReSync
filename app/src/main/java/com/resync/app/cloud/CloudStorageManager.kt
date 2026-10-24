package com.resync.app.cloud

import android.content.Context
import com.resync.app.cloud.providers.GoogleDriveProvider
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Cloud Storage Manager dedicated exclusively to Google Drive.
 * Manages official Google Drive OAuth 2.0, Resumable Uploads, and Single Source of Truth sync states.
 */
class CloudStorageManager(private val context: Context) {

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    val googleDriveProvider = GoogleDriveProvider(context)

    fun getProvider(type: CloudProviderType = CloudProviderType.GOOGLE_DRIVE): GoogleDriveProvider {
        return googleDriveProvider
    }

    /**
     * Run the complete 9-step connection diagnostic on Google Drive
     */
    fun runDiagnostic(type: CloudProviderType = CloudProviderType.GOOGLE_DRIVE): Flow<CloudDiagnosticResult> {
        return googleDriveProvider.runComprehensiveDiagnostic()
    }

    /**
     * Enqueue a backup file for upload to Google Drive
     */
    suspend fun enqueueUpload(backupRecord: BackupRecord, providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE): CloudSyncQueueItem = withContext(Dispatchers.IO) {
        val queueItem = CloudSyncQueueItem(
            id = "sync_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6),
            backupId = backupRecord.id,
            backupTitle = backupRecord.title,
            providerType = CloudProviderType.GOOGLE_DRIVE,
            direction = TransferDirection.UPLOAD,
            status = QueueStatus.PENDING,
            progressBytes = 0L,
            totalBytes = backupRecord.sizeBytes,
            createdAt = System.currentTimeMillis(),
            priority = 0
        )
        dbHelper.insertQueueItem(queueItem)
        dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.QUEUED)
        dbHelper.insertLog(LogLevel.INFO, "CloudSync", "Arquivo enfileirado para upload: ${backupRecord.title} -> Google Drive")
        queueItem
    }

    /**
     * Process transfer queue item with Google Drive resumable upload
     */
    suspend fun processQueueItem(item: CloudSyncQueueItem, onProgress: (bytes: Long, total: Long) -> Unit): Result<Boolean> = withContext(Dispatchers.IO) {
        val backupRecord = dbHelper.getBackupRecordById(item.backupId)

        if (backupRecord == null) {
            dbHelper.updateQueueItemStatus(item.id, QueueStatus.FAILED, 0L, "FILE_NOT_FOUND", "Registro de backup local não encontrado.")
            return@withContext Result.failure(IllegalStateException("Backup não encontrado"))
        }

        val localFile = File(backupRecord.filePath)
        if (!localFile.exists()) {
            dbHelper.updateQueueItemStatus(item.id, QueueStatus.FAILED, 0L, "FILE_MISSING", "O arquivo de backup (.resync) foi movido ou excluído do dispositivo.")
            return@withContext Result.failure(IllegalStateException("Arquivo inexistente"))
        }

        dbHelper.updateQueueItemStatus(item.id, QueueStatus.RUNNING, item.progressBytes)
        dbHelper.updateBackupSyncStatus(item.backupId, SyncStatus.UPLOADING)

        val result = googleDriveProvider.uploadFile(
            localFile = localFile,
            remotePath = "${com.resync.app.cloud.gdrive.DriveConstants.ROOT_FOLDER_NAME}/${com.resync.app.cloud.gdrive.DriveConstants.SUBFOLDER_QUICK_BACKUPS}/${localFile.name}",
            startOffsetBytes = item.progressBytes,
            onProgress = { transferred, total ->
                onProgress(transferred, total)
                if (transferred % (1024 * 1024) == 0L || transferred == total) {
                    dbHelper.writableDatabase.execSQL(
                        "UPDATE sync_queue SET progress_bytes = ? WHERE id = ?",
                        arrayOf(transferred, item.id)
                    )
                }
            }
        )

        if (result.isSuccess) {
            dbHelper.updateQueueItemStatus(item.id, QueueStatus.COMPLETED, localFile.length())
            dbHelper.updateBackupSyncStatus(item.backupId, SyncStatus.SYNCED)
            dbHelper.insertLog(LogLevel.INFO, "CloudSync", "Upload concluído para o Google Drive: ${localFile.name}")
            Result.success(true)
        } else {
            val err = result.exceptionOrNull()
            dbHelper.updateQueueItemStatus(
                item.id,
                QueueStatus.FAILED,
                item.progressBytes,
                "UPLOAD_FAILED",
                err?.message ?: "Erro durante o upload para o Google Drive."
            )
            dbHelper.updateBackupSyncStatus(item.backupId, SyncStatus.FAILED)
            dbHelper.insertLog(LogLevel.ERROR, "CloudSync", "Falha no upload do item ${item.id}: ${err?.message}")
            Result.failure(err ?: Exception("Erro de upload"))
        }
    }

    /**
     * Detect conflicts between local files and Google Drive
     */
    suspend fun detectConflicts(
        providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE,
        localRecords: List<BackupRecord>
    ): List<SyncConflict> = withContext(Dispatchers.IO) {
        val remoteFilesRes = googleDriveProvider.driveFileManager.listRemoteBackups()
        if (remoteFilesRes.isFailure) return@withContext emptyList()
        val remoteFiles = remoteFilesRes.getOrThrow()
        val conflicts = mutableListOf<SyncConflict>()

        for (local in localRecords) {
            val localFile = File(local.filePath)
            if (!localFile.exists()) continue
            val matchingRemote = remoteFiles.find { it.name == localFile.name } ?: continue

            if (local.timestamp != matchingRemote.modifiedTime && 
                (localFile.length() != matchingRemote.sizeBytes || local.checksumSha256 != (matchingRemote.md5Checksum ?: ""))) {
                conflicts.add(
                    SyncConflict(
                        fileId = matchingRemote.id,
                        fileName = localFile.name,
                        localTimestamp = local.timestamp,
                        localSize = localFile.length(),
                        remoteTimestamp = matchingRemote.modifiedTime,
                        remoteSize = matchingRemote.sizeBytes,
                        localHash = local.checksumSha256,
                        remoteHash = matchingRemote.md5Checksum ?: ""
                    )
                )
            }
        }
        conflicts
    }

    /**
     * Resolve conflict by uploading local, downloading remote, or keeping both
     */
    suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
        providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        when (resolution) {
            ConflictResolution.USE_LOCAL -> {
                val localRecord = dbHelper.getAllBackupRecords().find { File(it.filePath).name == conflict.fileName }
                if (localRecord != null) {
                    enqueueUpload(localRecord, providerType)
                    Result.success(true)
                } else {
                    Result.failure(IllegalStateException("Arquivo local não encontrado"))
                }
            }
            ConflictResolution.USE_REMOTE -> {
                val destFile = File(context.filesDir, "vault_backups/${conflict.fileName}")
                val downloadRes = googleDriveProvider.downloadFile(conflict.fileId, destFile) { _, _ -> }
                if (downloadRes.isSuccess) {
                    Result.success(true)
                } else {
                    Result.failure(downloadRes.exceptionOrNull() ?: Exception("Falha no download"))
                }
            }
            ConflictResolution.KEEP_BOTH -> {
                val nameWithoutExt = conflict.fileName.substringBeforeLast(".")
                val ext = conflict.fileName.substringAfterLast(".", "resync")
                val destFile = File(context.filesDir, "vault_backups/${nameWithoutExt}_remote.$ext")
                val downloadRes = googleDriveProvider.downloadFile(conflict.fileId, destFile) { _, _ -> }
                if (downloadRes.isSuccess) {
                    Result.success(true)
                } else {
                    Result.failure(downloadRes.exceptionOrNull() ?: Exception("Falha no download"))
                }
            }
        }
    }
}

