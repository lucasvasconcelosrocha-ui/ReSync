package com.resync.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.FileContent
import com.google.api.services.drive.model.File as DriveFile
import com.resync.app.R
import com.resync.app.cloud.gdrive.DriveRepository
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.resync.app.data.model.QueueStatus
import com.resync.app.data.model.SyncStatus
import com.resync.app.util.formatBytes
import com.resync.app.util.formatEta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class FileUploadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = com.resync.app.util.HyperFocusNotificationHelper.CHANNEL_OPERATIONS_ID
        const val NOTIFICATION_ID = 2001

        const val KEY_ACCOUNT_EMAIL = "target_account_email"
        const val KEY_FILE_PATH = "local_file_path"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FOLDER_ID = "dest_folder_id"
        const val KEY_QUEUE_ID = "queue_id"
        const val KEY_BACKUP_ID = "backup_id"
        const val KEY_MIME_TYPE = "mime_type"
    }

    private val dbHelper = AppDatabaseHelper.getInstance(appContext)
    private val driveRepo = DriveRepository(appContext)
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val isCancelled = AtomicBoolean(false)
        var lastNotifUpdateMs = 0L
        var queueId = inputData.getString(KEY_QUEUE_ID)
        var backupId = inputData.getString(KEY_BACKUP_ID)
        var fileName = inputData.getString(KEY_FILE_NAME) ?: "arquivo"

        var wakeLock: PowerManager.WakeLock? = null
        var wifiLock: WifiManager.WifiLock? = null

        try {
            try {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReSync:FileUploadWorkerWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
                val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ReSync:FileUploadWorkerWifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReSync:FileUploadWorkerWifiLock")
                }?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) {}

            try {
                val targetEmail = inputData.getString(KEY_ACCOUNT_EMAIL)
            if (targetEmail.isNullOrBlank()) {
                val err = "Conta destino ausente no lote de upload."
                dbHelper.insertLog(LogLevel.ERROR, "UploadWorker", err)
                if (queueId != null) {
                    try { dbHelper.updateQueueItemStatus(queueId, QueueStatus.FAILED, 0L, "NO_ACCOUNT", err) } catch (_: Throwable) {}
                }
                return@withContext Result.failure(workDataOf("error" to err))
            }

            val filePath = inputData.getString(KEY_FILE_PATH)
            if (filePath.isNullOrBlank()) {
                val err = "Caminho do arquivo local não informado."
                dbHelper.insertLog(LogLevel.ERROR, "UploadWorker", err)
                if (queueId != null) {
                    try { dbHelper.updateQueueItemStatus(queueId, QueueStatus.FAILED, 0L, "NO_FILE_PATH", err) } catch (_: Throwable) {}
                }
                return@withContext Result.failure(workDataOf("error" to err))
            }

            val localFile = File(filePath)
            if (!localFile.exists() || localFile.length() == 0L) {
                val err = "Arquivo local não encontrado ou vazio: $filePath"
                dbHelper.insertLog(LogLevel.ERROR, "UploadWorker", err)
                if (queueId != null) {
                    try { dbHelper.updateQueueItemStatus(queueId, QueueStatus.FAILED, 0L, "FILE_NOT_FOUND", err) } catch (_: Throwable) {}
                }
                return@withContext Result.failure(workDataOf("error" to err))
            }

            if (fileName == "arquivo") {
                fileName = localFile.name
            }
            val customFolderId = inputData.getString(KEY_FOLDER_ID)
            val isContainer = fileName.endsWith(".resync", ignoreCase = true) || fileName.endsWith(".svb", ignoreCase = true) || fileName.endsWith(".zip", ignoreCase = true)
            val mimeType = if (isContainer) "application/octet-stream" else (inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream")

            // Promoção a Foreground com BigTextStyle (título curto + 2 linhas de detalhes)
            try {
                createNotificationChannel()
                setForeground(
                    createForegroundInfo(
                        title = "Enviando ao Google Drive • 0%",
                        progress = 0,
                        detailLine1 = "Iniciando envio de $fileName",
                        detailLine2 = formatBytes(localFile.length())
                    )
                )
            } catch (t: Throwable) {
                dbHelper.insertLog(LogLevel.WARN, "UploadWorker", "Executando upload sem notificação de primeiro plano: ${t.message}")
            }

            val drive = driveRepo.getDriveClientForAccount(targetEmail)
            val parentFolderId = if (!customFolderId.isNullOrBlank()) {
                customFolderId
            } else {
                val struct = driveRepo.getOrCreateSwiftVaultStructure(targetEmail)
                if (fileName.endsWith(".resync", ignoreCase = true) || fileName.endsWith(".svb", ignoreCase = true) || fileName.contains("Odin", ignoreCase = true)) {
                    struct.quickBackupsFolderId
                } else {
                    struct.filesFolderId
                }
            }

            dbHelper.insertLog(
                LogLevel.INFO,
                "GoogleDrive",
                "Iniciando envio de '$fileName' (${localFile.length()} bytes) para a pasta ID '$parentFolderId' (Conta: $targetEmail)"
            )

            // Verificar se o arquivo já existe na pasta destino para reutilizar/atualizar em vez de duplicar
            val existingFile = driveRepo.findFileInFolder(targetEmail, fileName, parentFolderId)
            val mediaContent = FileContent(mimeType, localFile)

            val uploadedDriveFile = if (existingFile != null && !existingFile.id.isNullOrBlank()) {
                // Atualizar / sobrescrever arquivo existente
                val updateMetadata = DriveFile()
                val updateRequest = drive.files().update(existingFile.id, updateMetadata, mediaContent)
                updateRequest.fields = "id, name, size, md5Checksum"
                val totalFileBytes = localFile.length()
                var lastProgressBytes = 0L
                var lastProgressTime = System.currentTimeMillis()
                var uploadSpeedBytesPerSec = 0L

                val progressCallback: (MediaHttpUploader) -> Unit = { u ->
                    if (isCancelled.get() || isStopped) {
                        isCancelled.set(true)
                        throw CancellationException("Upload cancelado pelo usuário")
                    }
                    val progressPercent = (u.progress * 100).toInt()
                    val progressBytes = u.numBytesUploaded
                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastProgressTime
                    if (timeDiff >= 1000L) {
                        val bytesDiff = (progressBytes - lastProgressBytes).coerceAtLeast(0L)
                        uploadSpeedBytesPerSec = (bytesDiff * 1000L) / timeDiff
                        lastProgressBytes = progressBytes
                        lastProgressTime = now
                    }

                    val remainingBytes = (totalFileBytes - progressBytes).coerceAtLeast(0L)
                    val remainingSec = if (uploadSpeedBytesPerSec > 0L) remainingBytes / uploadSpeedBytesPerSec else 0L
                    val speedMBps = uploadSpeedBytesPerSec.toFloat() / (1024f * 1024f)
                    val partCount = if (totalFileBytes < 32L * 1024 * 1024) 1 else (totalFileBytes / (16L * 1024 * 1024)).toInt().coerceIn(1, 5)
                    val partSize = (totalFileBytes / partCount).coerceAtLeast(1L)
                    val currentPartIdx = (progressBytes / partSize).toInt().coerceIn(0, partCount - 1)

                    if (now - lastNotifUpdateMs >= com.resync.app.engine.PerformanceConfig.notifThrottleMs) {
                        lastNotifUpdateMs = now
                        val speedStr = if (speedMBps >= 1f) String.format(Locale.US, "%.1f MB/s", speedMBps) else String.format(Locale.US, "%.0f KB/s", uploadSpeedBytesPerSec / 1024f)
                        val doneStr = formatBytes(progressBytes)
                        val totalStr = formatBytes(totalFileBytes)
                        val etaStr = if (remainingSec > 0) formatEta(remainingSec) else "Calculando..."
                        try {
                            setForegroundAsync(
                                createForegroundInfo(
                                    title = "Enviando ao Google Drive • $progressPercent%",
                                    progress = progressPercent,
                                    detailLine1 = "$doneStr de $totalStr • $speedStr",
                                    detailLine2 = "Tempo restante: $etaStr • $fileName"
                                )
                            )
                        } catch (_: Throwable) {}
                    }

                    if (queueId != null) {
                        try {
                            dbHelper.writableDatabase.execSQL(
                                "UPDATE sync_queue SET progress_bytes = ?, status = 'RUNNING' WHERE id = ?",
                                arrayOf(progressBytes, queueId)
                            )
                        } catch (_: Throwable) {}
                        val partsList = (0 until partCount).map { i ->
                            val pStart = i * partSize
                            val pEnd = if (i == partCount - 1) totalFileBytes else (i + 1) * partSize
                            val pTotal = pEnd - pStart
                            val pDone = when {
                                progressBytes >= pEnd -> pTotal
                                progressBytes <= pStart -> 0L
                                else -> progressBytes - pStart
                            }
                            val pPercent = if (pTotal > 0) (pDone.toFloat() / pTotal.toFloat()).coerceIn(0f, 1f) else 1f
                            val pSpeed = if (i == currentPartIdx) speedMBps else 0f
                            com.resync.app.engine.OperationPartInfo(
                                partIndex = i + 1,
                                totalParts = partCount,
                                bytesProcessed = pDone,
                                totalBytes = pTotal,
                                progressPercent = pPercent,
                                speedMBps = pSpeed,
                                isCompleted = pDone >= pTotal
                            )
                        }

                        com.resync.app.engine.OperationRepository.updateUploadOperation(
                            operationId = "upload_$queueId",
                            fileName = fileName,
                            progressPercent = (progressBytes.toFloat() / totalFileBytes.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                            bytesProcessed = progressBytes,
                            totalBytesEstimated = totalFileBytes,
                            isFinished = false,
                            status = com.resync.app.engine.OperationStatus.RUNNING,
                            uploadSpeedBytesPerSec = uploadSpeedBytesPerSec,
                            estimatedRemainingSeconds = remainingSec,
                            destination = "Google Drive ($targetEmail)",
                            parts = partsList
                        )
                    }
                }

                updateRequest.mediaHttpUploader.apply {
                    isDirectUploadEnabled = false
                    chunkSize = com.resync.app.engine.PerformanceConfig.uploadChunkBytes
                    setProgressListener { u -> progressCallback(u) }
                }
                try {
                    updateRequest.execute()
                } catch (t: Throwable) {
                    val is401 = (t is com.google.api.client.googleapis.json.GoogleJsonResponseException && t.statusCode == 401) ||
                            t.message?.contains("401") == true
                    if (is401) {
                        driveRepo.invalidateAccountToken(targetEmail)
                        val retryDrive = driveRepo.getDriveClientForAccount(targetEmail)
                        val retryUpdate = retryDrive.files().update(existingFile.id, updateMetadata, mediaContent)
                        retryUpdate.fields = "id, name, size, md5Checksum"
                        retryUpdate.mediaHttpUploader.apply {
                            isDirectUploadEnabled = false
                            chunkSize = com.resync.app.engine.PerformanceConfig.uploadChunkBytes
                            setProgressListener { u -> progressCallback(u) }
                        }
                        retryUpdate.execute()
                    } else {
                        throw t
                    }
                }
            } else {
                // Criar novo arquivo
                val fileMetadata = DriveFile().apply {
                    name = fileName
                    parents = listOf(parentFolderId)
                }
                val insertRequest = drive.files().create(fileMetadata, mediaContent)
                insertRequest.fields = "id, name, size, md5Checksum"
                val uploader = insertRequest.mediaHttpUploader
                uploader.isDirectUploadEnabled = false
                uploader.chunkSize = com.resync.app.engine.PerformanceConfig.uploadChunkBytes

                val totalFileBytes = localFile.length()
                var lastProgressBytes = 0L
                var lastProgressTime = System.currentTimeMillis()
                var uploadSpeedBytesPerSec = 0L

                val progressCallback: (MediaHttpUploader) -> Unit = { u ->
                    if (isCancelled.get() || isStopped) {
                        isCancelled.set(true)
                        throw CancellationException("Upload cancelado pelo usuário")
                    }
                    val progressPercent = (u.progress * 100).toInt()
                    val progressBytes = u.numBytesUploaded
                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastProgressTime
                    if (timeDiff >= 1000L) {
                        val bytesDiff = (progressBytes - lastProgressBytes).coerceAtLeast(0L)
                        uploadSpeedBytesPerSec = (bytesDiff * 1000L) / timeDiff
                        lastProgressBytes = progressBytes
                        lastProgressTime = now
                    }

                    val remainingBytes = (totalFileBytes - progressBytes).coerceAtLeast(0L)
                    val remainingSec = if (uploadSpeedBytesPerSec > 0L) remainingBytes / uploadSpeedBytesPerSec else 0L
                    val speedMBps = uploadSpeedBytesPerSec.toFloat() / (1024f * 1024f)
                    val partCount = if (totalFileBytes < 32L * 1024 * 1024) 1 else (totalFileBytes / (16L * 1024 * 1024)).toInt().coerceIn(1, 5)
                    val partSize = (totalFileBytes / partCount).coerceAtLeast(1L)
                    val currentPartIdx = (progressBytes / partSize).toInt().coerceIn(0, partCount - 1)

                    if (now - lastNotifUpdateMs >= com.resync.app.engine.PerformanceConfig.notifThrottleMs) {
                        lastNotifUpdateMs = now
                        val speedStr = if (speedMBps >= 1f) String.format(Locale.US, "%.1f MB/s", speedMBps) else String.format(Locale.US, "%.0f KB/s", uploadSpeedBytesPerSec / 1024f)
                        val doneStr = formatBytes(progressBytes)
                        val totalStr = formatBytes(totalFileBytes)
                        val etaStr = if (remainingSec > 0) formatEta(remainingSec) else "Calculando..."
                        try {
                            setForegroundAsync(
                                createForegroundInfo(
                                    title = "Enviando ao Google Drive • $progressPercent%",
                                    progress = progressPercent,
                                    detailLine1 = "$doneStr de $totalStr • $speedStr",
                                    detailLine2 = "Tempo restante: $etaStr • $fileName"
                                )
                            )
                        } catch (_: Throwable) {}
                    }

                    if (queueId != null) {
                        try {
                            dbHelper.writableDatabase.execSQL(
                                "UPDATE sync_queue SET progress_bytes = ?, status = 'RUNNING' WHERE id = ?",
                                arrayOf(progressBytes, queueId)
                            )
                        } catch (_: Throwable) {}
                        val partsList = (0 until partCount).map { i ->
                            val pStart = i * partSize
                            val pEnd = if (i == partCount - 1) totalFileBytes else (i + 1) * partSize
                            val pTotal = pEnd - pStart
                            val pDone = when {
                                progressBytes >= pEnd -> pTotal
                                progressBytes <= pStart -> 0L
                                else -> progressBytes - pStart
                            }
                            val pPercent = if (pTotal > 0) (pDone.toFloat() / pTotal.toFloat()).coerceIn(0f, 1f) else 1f
                            val pSpeed = if (i == currentPartIdx) speedMBps else 0f
                            com.resync.app.engine.OperationPartInfo(
                                partIndex = i + 1,
                                totalParts = partCount,
                                bytesProcessed = pDone,
                                totalBytes = pTotal,
                                progressPercent = pPercent,
                                speedMBps = pSpeed,
                                isCompleted = pDone >= pTotal
                            )
                        }

                        com.resync.app.engine.OperationRepository.updateUploadOperation(
                            operationId = "upload_$queueId",
                            fileName = fileName,
                            progressPercent = (progressBytes.toFloat() / totalFileBytes.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                            bytesProcessed = progressBytes,
                            totalBytesEstimated = totalFileBytes,
                            isFinished = false,
                            status = com.resync.app.engine.OperationStatus.RUNNING,
                            uploadSpeedBytesPerSec = uploadSpeedBytesPerSec,
                            estimatedRemainingSeconds = remainingSec,
                            destination = "Google Drive ($targetEmail)",
                            parts = partsList
                        )
                    }
                }

                uploader.setProgressListener { u -> progressCallback(u) }

                try {
                    insertRequest.execute()
                } catch (t: Throwable) {
                    val is401 = (t is com.google.api.client.googleapis.json.GoogleJsonResponseException && t.statusCode == 401) ||
                            t.message?.contains("401") == true
                    if (is401) {
                        driveRepo.invalidateAccountToken(targetEmail)
                        val retryDrive = driveRepo.getDriveClientForAccount(targetEmail)
                        val retryInsert = retryDrive.files().create(fileMetadata, mediaContent)
                        retryInsert.fields = "id, name, size, md5Checksum"
                        retryInsert.mediaHttpUploader.apply {
                            isDirectUploadEnabled = false
                            chunkSize = com.resync.app.engine.PerformanceConfig.uploadChunkBytes
                            setProgressListener { u -> progressCallback(u) }
                        }
                        retryInsert.execute()
                    } else {
                        throw t
                    }
                }
            }

            // Validar ID e tamanho retornado pelo Google Drive
            val returnedId = uploadedDriveFile.id.orEmpty()
            val returnedSize = uploadedDriveFile.size?.toLong() ?: 0L
            if (returnedId.isBlank()) {
                val err = "Upload no Google Drive falhou: a API não retornou um ID de arquivo válido para $fileName."
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", err)
                throw IllegalStateException(err)
            }
            if (returnedSize <= 0L || returnedSize != localFile.length()) {
                val sizeMismatchErr = "Divergência de tamanho no Google Drive para $fileName: esperado ${localFile.length()} bytes, retornado $returnedSize bytes (0 bytes ou incompleto)."
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", sizeMismatchErr)
                throw IllegalStateException(sizeMismatchErr)
            }
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Arquivo enviado com sucesso ao Google Drive: $fileName (Tamanho: $returnedSize bytes, ID retornado: $returnedId)")

            // Se for container de backup, gerar e enviar índice complementar (svb_XXXX.index.json)
            if (isContainer) {
                try {
                    val baseName = localFile.nameWithoutExtension
                    val localSidecar = File(localFile.parentFile, "$baseName.index.json")
                    val indexJsonStr = if (localSidecar.exists() && localSidecar.length() > 0L) {
                        val bytes = localSidecar.readBytes()
                        val isGzip = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
                        if (isGzip) {
                            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } else {
                            String(bytes, Charsets.UTF_8)
                        }
                    } else {
                        val manifest = com.resync.app.engine.ArchiveEngine.readManifest(localFile)
                        com.google.gson.Gson().toJson(manifest)
                    }

                    val indexFileName = "$baseName.index.json"
                    val tempIndexFile = File(appContext.cacheDir, indexFileName)
                    tempIndexFile.writeText(indexJsonStr, Charsets.UTF_8)

                    try {
                        val existingIndexFile = driveRepo.findFileInFolder(targetEmail, indexFileName, parentFolderId)
                        val indexMedia = FileContent("application/json", tempIndexFile)
                        if (existingIndexFile != null && !existingIndexFile.id.isNullOrBlank()) {
                            val updateMeta = DriveFile()
                            drive.files().update(existingIndexFile.id, updateMeta, indexMedia).execute()
                        } else {
                            val indexMeta = DriveFile().apply {
                                name = indexFileName
                                parents = listOf(parentFolderId)
                                appProperties = mapOf(
                                    "indexFor" to uploadedDriveFile.id,
                                    "containerName" to fileName
                                )
                            }
                            drive.files().create(indexMeta, indexMedia).execute()
                        }
                        dbHelper.insertLog(LogLevel.INFO, "UploadWorker", "Índice complementar sincronizado: $indexFileName")
                    } finally {
                        tempIndexFile.delete()
                    }
                } catch (idxEx: Exception) {
                    dbHelper.insertLog(LogLevel.WARN, "UploadWorker", "Aviso ao gerar índice complementar: ${idxEx.message}")
                }
            }

            if (queueId != null) {
                dbHelper.updateQueueItemStatus(queueId, QueueStatus.COMPLETED, localFile.length())
                com.resync.app.engine.OperationRepository.updateUploadOperation(
                    operationId = "upload_$queueId",
                    fileName = fileName,
                    progressPercent = 1f,
                    bytesProcessed = localFile.length(),
                    totalBytesEstimated = localFile.length(),
                    isFinished = true,
                    status = com.resync.app.engine.OperationStatus.COMPLETED,
                    destination = "Google Drive ($targetEmail)"
                )
            }
            if (backupId != null) {
                dbHelper.updateBackupSyncStatus(backupId, SyncStatus.SYNCED, uploadedDriveFile.id)
            }

            dbHelper.insertLog(LogLevel.INFO, "UploadWorker", "Upload concluído: $fileName -> Google Drive ($targetEmail)")
            try { notificationManager.cancel(NOTIFICATION_ID) } catch (_: Throwable) {}

            Result.success(workDataOf("uploaded_id" to uploadedDriveFile.id))
        } catch (ce: CancellationException) {
            dbHelper.insertLog(LogLevel.WARN, "UploadWorker", "Upload cancelado pelo usuário: $fileName")
            if (queueId != null) {
                try {
                    dbHelper.updateQueueItemStatus(queueId, QueueStatus.FAILED, 0L, "CANCELLED", "Upload cancelado pelo usuário")
                } catch (_: Throwable) {}

                com.resync.app.engine.OperationRepository.updateUploadOperation(
                    operationId = "upload_$queueId",
                    fileName = fileName,
                    progressPercent = 0f,
                    isFinished = true,
                    status = com.resync.app.engine.OperationStatus.CANCELLED,
                    error = "Upload cancelado pelo usuário",
                    destination = "Google Drive"
                )
            }
            if (backupId != null) {
                try {
                    dbHelper.updateBackupSyncStatus(backupId, SyncStatus.FAILED)
                } catch (_: Throwable) {}
            }
            Result.failure(workDataOf("error" to "Upload cancelado pelo usuário"))
        } catch (t: Throwable) {
            if (t.cause is CancellationException || isCancelled.get() || isStopped) {
                dbHelper.insertLog(LogLevel.WARN, "UploadWorker", "Upload cancelado pelo usuário: $fileName")
                if (queueId != null) {
                    try {
                        dbHelper.updateQueueItemStatus(queueId, QueueStatus.FAILED, 0L, "CANCELLED", "Upload cancelado pelo usuário")
                    } catch (_: Throwable) {}

                    com.resync.app.engine.OperationRepository.updateUploadOperation(
                        operationId = "upload_$queueId",
                        fileName = fileName,
                        progressPercent = 0f,
                        isFinished = true,
                        status = com.resync.app.engine.OperationStatus.CANCELLED,
                        error = "Upload cancelado pelo usuário",
                        destination = "Google Drive"
                    )
                }
                if (backupId != null) {
                    try {
                        dbHelper.updateBackupSyncStatus(backupId, SyncStatus.FAILED)
                    } catch (_: Throwable) {}
                }
                Result.failure(workDataOf("error" to "Upload cancelado pelo usuário"))
            } else {
                val isNetworkIssue = t is java.io.IOException || t is java.net.SocketTimeoutException
                val errMsg = "Falha no upload de $fileName: ${t.message}"
                dbHelper.insertLog(LogLevel.ERROR, "UploadWorker", errMsg)
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", errMsg)

                if (queueId != null) {
                    try {
                        dbHelper.updateQueueItemStatus(
                            queueId,
                            if (isNetworkIssue) QueueStatus.RETRYING else QueueStatus.FAILED,
                            0L,
                            "ERROR",
                            t.message ?: "Erro desconhecido"
                        )
                    } catch (_: Throwable) {}

                    com.resync.app.engine.OperationRepository.updateUploadOperation(
                        operationId = "upload_$queueId",
                        fileName = fileName,
                        progressPercent = 0f,
                        isFinished = true,
                        status = com.resync.app.engine.OperationStatus.ERROR,
                        error = t.message ?: "Falha no upload",
                        destination = "Google Drive"
                    )
                }
                if (backupId != null) {
                    try {
                        dbHelper.updateBackupSyncStatus(backupId, SyncStatus.FAILED)
                    } catch (_: Throwable) {}
                }

                if (isNetworkIssue && runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf("error" to (t.message ?: "Erro desconhecido")))
                }
            }
        }
    } finally {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }
}

    private fun createForegroundInfo(
        title: String,
        progress: Int,
        detailLine1: String,
        detailLine2: String
    ): ForegroundInfo {
        val cancelPendingIntent = androidx.work.WorkManager.getInstance(appContext).createCancelPendingIntent(id)
        val bigLines = listOfNotNull(
            detailLine1.takeIf { it.isNotBlank() },
            detailLine2.takeIf { it.isNotBlank() }
        )
        val bigText = bigLines.joinToString("\n")

        val appLogo = com.resync.app.util.HyperFocusNotificationHelper.getAppLogoBitmap(appContext)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (bigLines.isNotEmpty()) bigLines.first() else "Enviando arquivo...")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appLogo)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPendingIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        com.resync.app.util.HyperFocusNotificationHelper.ensureNotificationChannels(appContext)
    }
}

