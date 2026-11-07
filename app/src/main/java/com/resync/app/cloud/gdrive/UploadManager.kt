package com.resync.app.cloud.gdrive

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.*
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

data class UploadProgressInfo(
    val stage: String,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val progressPercent: Float,
    val speedMBps: Float,
    val estimatedRemainingSeconds: Long,
    val speedMbps: Float = speedMBps * 8f,
    val parts: List<com.resync.app.engine.OperationPartInfo> = emptyList(),
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null
)

class UploadInterruptedException(
    val sessionUri: String,
    val bytesUploaded: Long,
    override val message: String
) : Exception(message)

class StreamingFileRequestBody(
    private val file: File,
    private val startOffset: Long,
    private val length: Long,
    private val onProgress: ((bytesSentInChunk: Long) -> Unit)? = null,
    /**
     * Optional MessageDigest updated inline as bytes are written — enables single-pass MD5
     * without a second disk read after chunk confirmation. Pass a CLONED digest before each
     * chunk so the original is unaffected if the chunk fails and needs to be retried.
     */
    private val digestToUpdate: java.security.MessageDigest? = null
) : RequestBody() {
    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            // 256 KB buffer — optimal balance between JVM heap pressure and JNI/syscall overhead.
            // Reduces read() system calls by 4× vs 64 KB, 2× vs 128 KB, while keeping
            // per-buffer heap allocation under Android's default thread-stack size.
            val buffer = ByteArray(256 * 1024)
            var remaining = length
            var totalWritten = 0L
            // Progress throttle: invoke callback at most every 200 ms OR on the last block.
            // Eliminates millions of Kotlin lambda calls and UI recompositions during 16–32 MB chunks.
            var lastProgressTime = 0L
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                // No flush inside the loop — OkHttp/Okio manages buffer coalescing automatically.
                // Flushing every 256 KB would fragment HTTP/2 frames and TLS records,
                // halving effective throughput by adding per-block round-trip latency.
                sink.write(buffer, 0, read)
                // Update MD5 inline during the upload pass — eliminates the second disk read
                // that previously re-read every chunk from storage after server confirmation.
                digestToUpdate?.update(buffer, 0, read)
                remaining -= read
                totalWritten += read
                if (onProgress != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (remaining == 0L || now - lastProgressTime >= 200L) {
                        lastProgressTime = now
                        onProgress.invoke(totalWritten)
                    }
                }
            }
            // Single terminal flush after the loop: ensures all bytes reach the TCP socket
            // before OkHttp signals end-of-request-body to the server.
            sink.flush()
        }
    }
}

class UploadManager(private val oAuthManager: OAuthManager) {

    private val gson = Gson()
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun cancel() {
        isCancelled.set(true)
    }

    companion object {
        private const val TAG = "UploadManager"
        private const val CHUNK_UNIT = 256 * 1024 // 256 KiB
        val uploadDispatcher = Dispatchers.IO.limitedParallelism(2)
    }

    /**
     * Executes resilient, high-speed, resumable Google Drive upload conforming to Drive API v3.
     */
    suspend fun executeResumableUpload(
        localFile: File,
        targetFolderId: String?,
        backupMetadata: Map<String, String>,
        existingSessionUri: String? = null,
        accountEmail: String? = null,
        jobId: String = "upload_${localFile.name}",
        onProgress: (UploadProgressInfo) -> Unit
    ): Result<DriveFileMetadata> = withContext(uploadDispatcher) {
        val context = oAuthManager.context
        val roomDb = AppRoomDatabase.getInstance(context)
        val transferDao = roomDb.cloudTransferDao()
        val executorOwner = UUID.randomUUID().toString()

        AppLogger.i(TAG, "Iniciando upload de alta velocidade: ${localFile.name} (${localFile.length()} bytes), jobId=$jobId")
        val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(context)
        dbHelper.insertLog(com.resync.app.data.model.LogLevel.INFO, "GoogleDrive", "Iniciando upload de '${localFile.name}' (${localFile.length()} bytes) para pasta ID '$targetFolderId' (jobId=$jobId)")

        // 1. Acquire single executor lock in Room (Requirement 2 item 3)
        val nowMs = System.currentTimeMillis()
        val staleThreshold = nowMs - (120_000L) // Stale if older than 2 minutes
        val lockAcquired = transferDao.acquireExecutorLock(jobId, executorOwner, nowMs, staleThreshold)
        if (lockAcquired == 0) {
            val existing = transferDao.getTransfer(jobId)
            if (existing != null && existing.executorLockOwner != null && existing.executorLockTimestamp >= staleThreshold) {
                AppLogger.w(TAG, "Outro executor já está ativo para jobId=$jobId. Ignorando execução duplicada.")
                return@withContext Result.failure(IllegalStateException("Upload já em execução por outro serviço/job."))
            }
        }

        if (!localFile.exists()) {
            val err = "Arquivo local não encontrado: ${localFile.absolutePath}"
            AppLogger.e(TAG, err, extraParams = mapOf("path" to localFile.absolutePath, "jobId" to jobId))
            onProgress(UploadProgressInfo("Falha", 0, 0, 0f, 0f, 0, isFinished = true, error = err))
            transferDao.releaseExecutorLock(jobId, executorOwner)
            return@withContext Result.failure(FileNotFoundException(err))
        }

        val totalBytes = localFile.length()
        if (totalBytes <= 0) {
            val err = "Arquivo de backup vazio não pode ser enviado."
            AppLogger.e(TAG, err, extraParams = mapOf("jobId" to jobId))
            onProgress(UploadProgressInfo("Falha", 0, 0, 0f, 0f, 0, isFinished = true, error = err))
            transferDao.releaseExecutorLock(jobId, executorOwner)
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        // Initialize or restore Room state
        var savedRecord = transferDao.getTransfer(jobId)
        if (savedRecord != null && savedRecord.localFileLastModified != 0L && savedRecord.localFileLastModified != localFile.lastModified()) {
            AppLogger.w(TAG, "O arquivo local foi modificado desde a sessão anterior. Invalidando sessão resumível e recomeçando...")
            transferDao.deleteTransfer(jobId)
            savedRecord = null
        }

        var activeSessionUri: String? = existingSessionUri ?: savedRecord?.sessionUri
        var accumulatedActiveTimeMs = savedRecord?.activeTimeMs ?: 0L
        var accumulatedIdleTimeMs = savedRecord?.idleTimeMs ?: 0L

        isPaused.set(false)
        isCancelled.set(false)

        var token = oAuthManager.getValidAccessToken(accountEmail)
        if (token == null) {
            val refreshed = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
            if (refreshed.isSuccess) {
                token = refreshed.getOrThrow()
            } else {
                val err = "Sessão expirada. Reconecte a conta Google."
                AppLogger.e(TAG, err, extraParams = mapOf("account" to (accountEmail ?: "padrão"), "jobId" to jobId))
                transferDao.updateState(jobId, CloudTransferState.FALHOU, err)
                transferDao.releaseExecutorLock(jobId, executorOwner)
                return@withContext Result.failure(GoogleDriveSessionExpiredException(err, 401))
            }
        }

        try {
            // 2. Initiate Resumable Upload session if needed
            if (activeSessionUri.isNullOrBlank()) {
                onProgress(
                    UploadProgressInfo(
                        stage = "Conectando ao Google Drive...",
                        bytesUploaded = 0,
                        totalBytes = totalBytes,
                        progressPercent = 0.01f,
                        speedMBps = 0f,
                        estimatedRemainingSeconds = 0
                    )
                )

                activeSessionUri = try {
                    initiateResumableSession(
                        fileName = localFile.name,
                        totalBytes = totalBytes,
                        targetFolderId = targetFolderId,
                        appProperties = backupMetadata,
                        token = token
                    )
                } catch (apiEx: GoogleDriveApiException) {
                    if (apiEx.statusCode == 401) {
                        token = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail).getOrThrow()
                        initiateResumableSession(
                            fileName = localFile.name,
                            totalBytes = totalBytes,
                            targetFolderId = targetFolderId,
                            appProperties = backupMetadata,
                            token = token
                        )
                    } else {
                        throw apiEx
                    }
                }

                transferDao.insertOrUpdate(
                    CloudTransferEntity(
                        jobId = jobId,
                        type = "UPLOAD",
                        title = localFile.name,
                        sessionUri = activeSessionUri,
                        serverConfirmedOffset = 0L,
                        totalBytes = totalBytes,
                        localPath = localFile.absolutePath,
                        localFileLastModified = localFile.lastModified(),
                        state = CloudTransferState.EM_ANDAMENTO,
                        accountEmail = accountEmail,
                        targetFolderId = targetFolderId,
                        executorLockOwner = executorOwner,
                        executorLockTimestamp = System.currentTimeMillis()
                    )
                )
            }

            // 3. Query Google Drive for the TRUE server acknowledged byte offset
            // Server is the SOLE SINGLE SOURCE OF TRUTH (Requirement 3)
            var startByte = queryCurrentServerByteOffset(activeSessionUri, totalBytes)
            AppLogger.i(TAG, "Offset confirmado pelo Google Drive: $startByte de $totalBytes bytes (${(startByte * 100f / totalBytes).toInt()}%)")

            var bytesUploaded = startByte
            var lastReportTimeMono = SystemClock.elapsedRealtime()
            var lastReportBytes = bytesUploaded
            var smoothedSpeedMBps = 0f
            var peakSpeedMBps = 0f
            var lastMilestonePct = (bytesUploaded * 100 / totalBytes.coerceAtLeast(1L)).toInt() / 5 * 5

            // Streaming MD5 calculation on the fly (var so clone can be committed on chunk success)
            var md5Digest = MessageDigest.getInstance("MD5")

            // If resuming, feed previously uploaded bytes to digest quickly to guarantee single-pass hash
            if (startByte > 0) {
                RandomAccessFile(localFile, "r").use { raf ->
                    val preBuf = ByteArray(256 * 1024)
                    var preReadTotal = 0L
                    while (preReadTotal < startByte) {
                        val toRead = minOf(preBuf.size.toLong(), startByte - preReadTotal).toInt()
                        val r = raf.read(preBuf, 0, toRead)
                        if (r <= 0) break
                        md5Digest.update(preBuf, 0, r)
                        preReadTotal += r
                    }
                }
            }

            var transferStartMono = SystemClock.elapsedRealtime()

            while (bytesUploaded < totalBytes) {
                if (isCancelled.get()) {
                    handleUserCancellation(activeSessionUri, jobId, transferDao, executorOwner)
                    val cancelMsg = "Upload cancelado pelo usuário."
                    onProgress(UploadProgressInfo("Cancelado", bytesUploaded, totalBytes, 0f, 0f, 0, isFinished = true, error = cancelMsg))
                    return@withContext Result.failure(Exception(cancelMsg))
                }

                if (isPaused.get()) {
                    val now = SystemClock.elapsedRealtime()
                    accumulatedActiveTimeMs += (now - transferStartMono)
                    transferDao.updateState(jobId, CloudTransferState.PAUSADO_PELO_USUARIO)
                    onProgress(UploadProgressInfo("Pausado", bytesUploaded, totalBytes, bytesUploaded.toFloat() / totalBytes, 0f, 0, isPaused = true))
                    return@withContext Result.failure(UploadInterruptedException(activeSessionUri, bytesUploaded, "Upload pausado pelo usuário."))
                }

                // Check network status reactively (zero polling suspension)
                while (!CloudNetworkMonitor.isConnected() && !isCancelled.get() && !isPaused.get()) {
                    val pauseStartMono = SystemClock.elapsedRealtime()
                    accumulatedActiveTimeMs += (pauseStartMono - transferStartMono)

                    transferDao.updateState(jobId, CloudTransferState.AGUARDANDO_REDE)
                    onProgress(
                        UploadProgressInfo(
                            stage = "Aguardando conexão... retomará automaticamente",
                            bytesUploaded = bytesUploaded,
                            totalBytes = totalBytes,
                            progressPercent = (bytesUploaded.toFloat() / totalBytes),
                            speedMBps = 0f,
                            estimatedRemainingSeconds = 0
                        )
                    )

                    CloudNetworkMonitor.awaitNetworkAvailable()
                    accumulatedIdleTimeMs += (SystemClock.elapsedRealtime() - pauseStartMono)
                    transferStartMono = SystemClock.elapsedRealtime()
                    transferDao.updateState(jobId, CloudTransferState.EM_ANDAMENTO)

                    // Re-query true server offset upon network switch (Wi-Fi <-> Cellular)
                    val recheckOffset = queryCurrentServerByteOffset(activeSessionUri, totalBytes)
                    if (recheckOffset > bytesUploaded) {
                        val gapStart = bytesUploaded
                        bytesUploaded = recheckOffset
                        RandomAccessFile(localFile, "r").use { raf ->
                            raf.seek(gapStart)
                            val mBuf = ByteArray(128 * 1024)
                            var gapRem = recheckOffset - gapStart
                            while (gapRem > 0) {
                                val r = raf.read(mBuf, 0, minOf(mBuf.size.toLong(), gapRem).toInt())
                                if (r <= 0) break
                                md5Digest.update(mBuf, 0, r)
                                gapRem -= r
                            }
                        }
                    }
                }

                // Check thermal governor and throttle if needed
                CloudThermalGovernor.throttleIfSevere()

                // Compute dynamic chunk size: 32 MB (Fast Wi-Fi), 16 MB (Balanced/Moderate thermal),
                // 4 MB (Mobile data or severe thermal) — halves HTTP 308 round-trips vs 16 MB on Wi-Fi.
                val networkType = CloudNetworkMonitor.getActiveNetworkType()
                val isMobile = networkType.contains("Móveis", ignoreCase = true) || networkType.contains("Cellular", ignoreCase = true)
                val dynamicChunkBytes = if (isMobile) {
                    4 * 1024 * 1024 // 4 MB: mobile data
                } else {
                    CloudThermalGovernor.getRecommendedChunkSize(32 * 1024 * 1024)
                }
                val remainingBytes = totalBytes - bytesUploaded
                val currentChunkSize = minOf(dynamicChunkBytes.toLong(), remainingBytes).toInt()
                val alignedChunkSize = if (bytesUploaded + currentChunkSize < totalBytes) {
                    (currentChunkSize / CHUNK_UNIT * CHUNK_UNIT).coerceAtLeast(CHUNK_UNIT)
                } else {
                    currentChunkSize
                }
                val chunkLength = alignedChunkSize.toLong()
                val endByte = bytesUploaded + chunkLength - 1

                // Clone the current MD5 state before the chunk upload.
                // On success, the clone (already updated inline by StreamingFileRequestBody)
                // becomes the new canonical digest. On failure/retry, the original is unaffected.
                val chunkDigestClone = md5Digest.clone() as MessageDigest

                val chunkResult = uploadChunkWithInfiniteNetworkRetry(
                    sessionUri = activeSessionUri,
                    file = localFile,
                    startByte = bytesUploaded,
                    length = chunkLength,
                    endByte = endByte,
                    totalBytes = totalBytes,
                    chunkDigest = chunkDigestClone,
                    onProgressUpdate = { bytesInChunk ->
                        val currentTotal = bytesUploaded + bytesInChunk
                        val nowMono = SystemClock.elapsedRealtime()
                        val timeDeltaSec = (nowMono - lastReportTimeMono) / 1000.0
                        // Throttle: emit UI update at most every 500 ms to avoid Binder IPC overhead
                        if (timeDeltaSec >= 0.5) {
                            val bytesDelta = currentTotal - lastReportBytes
                            val instantSpeed = if (timeDeltaSec > 0) ((bytesDelta / (1024.0 * 1024.0)) / timeDeltaSec).toFloat() else 0f
                            smoothedSpeedMBps = if (smoothedSpeedMBps == 0f) instantSpeed else (0.7f * smoothedSpeedMBps + 0.3f * instantSpeed)
                            if (instantSpeed > peakSpeedMBps) peakSpeedMBps = instantSpeed

                            lastReportTimeMono = nowMono
                            lastReportBytes = currentTotal

                            val remaining = totalBytes - currentTotal
                            val speedBytesSec = (smoothedSpeedMBps * 1024 * 1024).toLong()
                            val etaSec = if (speedBytesSec > 0) remaining / speedBytesSec else 0L
                            val progressPercent = (currentTotal.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)

                            val currentPctInt = (progressPercent * 100).toInt()
                            if (currentPctInt >= lastMilestonePct + 5) {
                                lastMilestonePct = (currentPctInt / 5) * 5
                                AppLogger.i(TAG, "Marco de upload $lastMilestonePct% • ${"%.2f".format(smoothedSpeedMBps)} MB/s • $currentTotal/$totalBytes bytes • ETA: ${etaSec}s")
                            }

                            onProgress(
                                UploadProgressInfo(
                                    stage = "Enviando ao Drive... ($currentPctInt%)",
                                    bytesUploaded = currentTotal,
                                    totalBytes = totalBytes,
                                    progressPercent = progressPercent,
                                    speedMBps = smoothedSpeedMBps,
                                    speedMbps = smoothedSpeedMBps * 8f,
                                    estimatedRemainingSeconds = etaSec
                                )
                            )
                        }
                    },
                    onTokenRefresh = {
                        oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail).getOrNull() ?: token
                    },
                    onRetry = { attempt, delaySec ->
                        onProgress(
                            UploadProgressInfo(
                                stage = "Reconectando bloco (tentativa $attempt em ${delaySec}s)...",
                                bytesUploaded = bytesUploaded,
                                totalBytes = totalBytes,
                                progressPercent = (bytesUploaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                                speedMBps = 0f,
                                estimatedRemainingSeconds = 0
                            )
                        )
                    }
                )

                val ackOffset = chunkResult.acknowledgedByteOffset ?: (bytesUploaded + chunkLength)
                bytesUploaded = ackOffset

                // Commit the MD5 clone on success: the clone was updated inline by StreamingFileRequestBody
                // during the upload pass — no second disk read needed (single-pass MD5 achieved).
                md5Digest = chunkDigestClone


                // Update time tracking for Room persistence
                val nowMono = SystemClock.elapsedRealtime()
                accumulatedActiveTimeMs += (nowMono - transferStartMono)
                transferStartMono = nowMono

                // Persist upload offset to Room at every chunk boundary (for resume support)
                transferDao.updateProgress(
                    jobId = jobId,
                    offset = bytesUploaded,
                    activeMs = accumulatedActiveTimeMs,
                    idleMs = accumulatedIdleTimeMs,
                    speed = smoothedSpeedMBps,
                    updatedAt = System.currentTimeMillis()
                )

                // Milestone logging at outer chunk boundary (every 5% of total)
                // Note: onProgress for intermediate updates is handled exclusively by the
                // inner onProgressUpdate callback (500 ms throttled) inside writeTo().
                // This outer block does NOT call onProgress again to avoid double-notification.
                val isFinal = chunkResult.metadata != null
                val outerPct = (bytesUploaded.toFloat() / totalBytes.toFloat() * 100).toInt()
                if (outerPct >= lastMilestonePct + 5) {
                    lastMilestonePct = (outerPct / 5) * 5
                    AppLogger.i(TAG, "Bloco confirmado: $lastMilestonePct% • ${"%.2f".format(smoothedSpeedMBps)} MB/s • ${bytesUploaded}/${totalBytes} bytes")
                }


                        // Upload completed and file metadata returned by Google Drive
                        if (isFinal) {
                            var driveMeta = chunkResult.metadata!!
                            val computedMd5 = md5Digest.digest().joinToString("") { "%02x".format(it) }

                            // Consulta obrigatória dos metadados oficiais do Drive para verificar se os bytes foram gravados de fato
                            val (verifiedSize, verifiedMd5) = fetchRemoteFileMetadata(driveMeta.id, token)
                            if (verifiedSize > 0L) {
                                driveMeta = driveMeta.copy(
                                    sizeBytes = verifiedSize,
                                    md5Checksum = verifiedMd5 ?: driveMeta.md5Checksum
                                )
                            }

                            AppLogger.i(TAG, "Upload concluído no Google Drive! FileId=${driveMeta.id}, Size=${driveMeta.sizeBytes} bytes, MD5 local=$computedMd5, MD5 Drive=${driveMeta.md5Checksum}")

                            if (driveMeta.id.isBlank()) {
                                val err = "Upload no Google Drive falhou: a API não retornou um fileId válido."
                                AppLogger.e(TAG, err, extraParams = mapOf("jobId" to jobId))
                                dbHelper.insertLog(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", err)
                                transferDao.updateState(jobId, CloudTransferState.FALHOU, err)
                                transferDao.releaseExecutorLock(jobId, executorOwner)
                                return@withContext Result.failure(IllegalStateException(err))
                            }

                            if (driveMeta.sizeBytes <= 0L || driveMeta.sizeBytes != totalBytes) {
                                val sizeErr = "Divergência de tamanho no Google Drive para ${localFile.name}: esperado $totalBytes bytes, retornado ${driveMeta.sizeBytes} bytes (0 bytes gravados ou arquivo incompleto)."
                                AppLogger.e(TAG, sizeErr, extraParams = mapOf("jobId" to jobId))
                                dbHelper.insertLog(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", sizeErr)
                                transferDao.updateState(jobId, CloudTransferState.FALHOU, sizeErr)
                                transferDao.releaseExecutorLock(jobId, executorOwner)
                                return@withContext Result.failure(IllegalStateException(sizeErr))
                            }

                        if (!driveMeta.md5Checksum.isNullOrBlank() && !computedMd5.equals(driveMeta.md5Checksum, ignoreCase = true)) {
                            val md5MismatchErr = "Divergência de integridade MD5: local=$computedMd5, remoto=${driveMeta.md5Checksum}"
                            AppLogger.e(TAG, md5MismatchErr, extraParams = mapOf("jobId" to jobId))
                            dbHelper.insertLog(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", md5MismatchErr)
                            transferDao.updateState(jobId, CloudTransferState.FALHOU, md5MismatchErr)
                            transferDao.releaseExecutorLock(jobId, executorOwner)
                            return@withContext Result.failure(IllegalStateException(md5MismatchErr))
                        }

                        dbHelper.insertLog(
                            com.resync.app.data.model.LogLevel.INFO,
                            "GoogleDrive",
                            "arquivo '${driveMeta.name}' enviado para pasta '$targetFolderId'"
                        )
                        dbHelper.insertLog(
                            com.resync.app.data.model.LogLevel.INFO,
                            "GoogleDrive",
                            "Upload concluído com sucesso: ${driveMeta.name} (Tamanho: ${driveMeta.sizeBytes} bytes, ID retornado: ${driveMeta.id})"
                        )

                        transferDao.updateState(jobId, CloudTransferState.CONCLUIDO)
                        transferDao.releaseExecutorLock(jobId, executorOwner)

                        onProgress(
                            UploadProgressInfo(
                                stage = "✓ Concluído no Google Drive",
                                bytesUploaded = totalBytes,
                                totalBytes = totalBytes,
                                progressPercent = 1.0f,
                                speedMBps = smoothedSpeedMBps,
                                estimatedRemainingSeconds = 0,
                                isFinished = true
                            )
                        )
                        return@withContext Result.success(driveMeta)
                    }
                }

            val finalError = "Upload terminou sem confirmação de metadados do Google Drive."
            AppLogger.e(TAG, finalError, extraParams = mapOf("jobId" to jobId))
            transferDao.updateState(jobId, CloudTransferState.FALHOU, finalError)
            transferDao.releaseExecutorLock(jobId, executorOwner)
            Result.failure(IllegalStateException(finalError))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Erro crítico no upload: ${e.message}", e, jobId = jobId)
            transferDao.updateState(jobId, CloudTransferState.FALHOU, e.message)
            transferDao.releaseExecutorLock(jobId, executorOwner)
            onProgress(UploadProgressInfo("Falha", 0, totalBytes, 0f, 0f, 0, isFinished = true, error = e.message))
            Result.failure(e)
        }
    }

    private suspend fun handleUserCancellation(
        sessionUri: String,
        jobId: String,
        dao: CloudTransferDao,
        executorOwner: String
    ) {
        AppLogger.w(TAG, "Usuário solicitou cancelamento do upload. Encerrando sessão no Google Drive...")
        try {
            val req = Request.Builder().url(sessionUri).delete().build()
            GDriveNetworkClient.client.newCall(req).execute().close()
        } catch (_: Throwable) {}
        dao.deleteTransfer(jobId)
        dao.releaseExecutorLock(jobId, executorOwner)
    }

    private fun initiateResumableSession(
        fileName: String,
        totalBytes: Long,
        targetFolderId: String?,
        appProperties: Map<String, String>,
        token: String
    ): String {
        val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
        val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size,md5Checksum,mimeType,parents"

        val metadata = JsonObject().apply {
            addProperty("name", fileName)
            addProperty("description", "ReSync Container Backup (.resync)")
            if (!targetFolderId.isNullOrBlank()) {
                val parentsArr = com.google.gson.JsonArray().apply { add(targetFolderId) }
                add("parents", parentsArr)
            }
            if (appProperties.isNotEmpty()) {
                val props = JsonObject()
                for ((k, v) in appProperties) {
                    props.addProperty(k, v)
                }
                add("appProperties", props)
            }
        }

        val mediaType = "application/json; charset=UTF-8".toMediaTypeOrNull()
        val requestBody = metadata.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", totalBytes.toString())
            .post(requestBody)
            .build()

        GDriveNetworkClient.client.newCall(request).execute().use { response ->
            val code = response.code
            if (code == 200 || code == 201) {
                val location = response.header("Location")
                if (!location.isNullOrBlank()) {
                    return location
                } else {
                    val err = "Google Drive não retornou Location header da sessão resumível."
                    dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", err)
                    throw IllegalStateException(err)
                }
            } else {
                val errBody = response.body?.string() ?: ""
                dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "Erro HTTP $code ao iniciar upload de '$fileName': $errBody")

                if (code == 403 || code == 507) {
                    throw InsufficientStorageException("Não há espaço suficiente no Google Drive para este backup (HTTP $code).")
                } else {
                    throw GoogleDriveApiException(code, "Falha ao iniciar upload no Google Drive (HTTP $code): $errBody")
                }
            }
        }
    }

    private data class ChunkUploadResult(
        val acknowledgedByteOffset: Long?,
        val metadata: DriveFileMetadata?
    )

    private suspend fun uploadChunkWithInfiniteNetworkRetry(
        sessionUri: String,
        file: File,
        startByte: Long,
        length: Long,
        endByte: Long,
        totalBytes: Long,
        /** Clone of md5Digest to be updated inline; committed to master on successful ack. */
        chunkDigest: java.security.MessageDigest? = null,
        onProgressUpdate: ((bytesSentInChunk: Long) -> Unit)? = null,
        onTokenRefresh: suspend () -> String,
        onRetry: (attempt: Int, delaySec: Long) -> Unit
    ): ChunkUploadResult {
        var attempt = 0
        while (!isCancelled.get() && !isPaused.get()) {
            attempt++
            try {
                // Await internet connection without busy-loop polling
                if (!CloudNetworkMonitor.isConnected()) {
                    CloudNetworkMonitor.awaitNetworkAvailable()
                }

                return uploadChunkInternal(sessionUri, file, startByte, length, endByte, totalBytes, onProgressUpdate, chunkDigest)
            } catch (e: Exception) {
                if (isCancelled.get() || isPaused.get()) throw e

                // Check if Google Drive already saved the bytes despite the timeout/drop
                try {
                    val currentServerOffset = queryCurrentServerByteOffset(sessionUri, totalBytes)
                    if (currentServerOffset > startByte) {
                        return ChunkUploadResult(acknowledgedByteOffset = currentServerOffset, metadata = null)
                    }
                } catch (_: Throwable) {}

                // Jittered Exponential backoff: 1s, 2s, 4s, 8s max
                val baseDelay = (1L shl (attempt.coerceAtMost(4) - 1)).coerceIn(1L, 8L)
                val jitter = Random.nextLong(200L, 800L)
                val totalDelayMs = baseDelay * 1000L + jitter
                onRetry(attempt, totalDelayMs / 1000L)
                delay(totalDelayMs)
            }
        }
        throw IOException("Upload cancelado ou interrompido.")
    }

    private fun uploadChunkInternal(
        sessionUri: String,
        file: File,
        startByte: Long,
        length: Long,
        endByte: Long,
        totalBytes: Long,
        onProgressUpdate: ((bytesSentInChunk: Long) -> Unit)? = null,
        /** Clone of md5Digest for single-pass MD5 — reverted on retry, committed on success. */
        chunkDigest: java.security.MessageDigest? = null
    ): ChunkUploadResult {
        val requestBody = StreamingFileRequestBody(
            file = file,
            startOffset = startByte,
            length = length,
            onProgress = onProgressUpdate,
            digestToUpdate = chunkDigest
        )
        val request = Request.Builder()
            .url(sessionUri)
            .put(requestBody)
            .addHeader("Content-Range", "bytes $startByte-$endByte/$totalBytes")
            .addHeader("Content-Length", length.toString())
            .build()

        GDriveNetworkClient.client.newCall(request).execute().use { response ->
            val code = response.code
            return when (code) {
                308 -> {
                    val rangeHeader = response.header("Range")
                    val ackOffset = if (!rangeHeader.isNullOrBlank()) {
                        val match = Regex("bytes=0-(\\d+)").find(rangeHeader)
                        match?.groupValues?.get(1)?.toLong()?.plus(1) ?: (endByte + 1)
                    } else {
                        endByte + 1
                    }
                    ChunkUploadResult(acknowledgedByteOffset = ackOffset, metadata = null)
                }
                in 200..299 -> {
                    val bodyStr = response.body?.string() ?: ""
                    val json = gson.fromJson(bodyStr, JsonObject::class.java)
                    val fileId = json?.get("id")?.asString
                        ?: throw GoogleDriveApiException(code, "Resposta do Drive sem fileId")

                    val returnedSize = json.get("size")?.asLong ?: 0L
                    val meta = DriveFileMetadata(
                        id = fileId,
                        name = json.get("name")?.asString ?: "backup.resync",
                        sizeBytes = returnedSize,
                        createdTime = System.currentTimeMillis(),
                        modifiedTime = System.currentTimeMillis(),
                        md5Checksum = json.get("md5Checksum")?.asString,
                        mimeType = json.get("mimeType")?.asString ?: "application/octet-stream"
                    )
                    ChunkUploadResult(acknowledgedByteOffset = totalBytes, metadata = meta)
                }
                404, 410 -> {
                    val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
                    dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "Sessão de upload expirou no Google Drive (HTTP $code).")
                    throw IllegalStateException("Sessão de upload expirou no Google Drive (HTTP $code). Recomeçando nova sessão...")
                }
                401 -> {
                    val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
                    dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "HTTP 401 Unauthorized - token expirado.")
                    throw IOException("HTTP 401 Unauthorized - token expirado")
                }
                403 -> {
                    val errBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
                    dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "Erro HTTP 403 no upload: $errBody")
                    if (errBody.contains("quota", ignoreCase = true) || errBody.contains("storageQuotaExceeded", ignoreCase = true)) {
                        throw InsufficientStorageException("Espaço insuficiente no Google Drive (HTTP 403).")
                    }
                    throw IOException("Acesso temporariamente restrito (HTTP 403 rate limit) - aguardando retry...")
                }
                in 500..599 -> {
                    val errBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
                    dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.WARN, "GoogleDrive", "Erro no servidor Google Drive (HTTP $code): $errBody")
                    throw IOException("Erro temporário no servidor Google Drive (HTTP $code) - aguardando retry...")
                }
                else -> {
                    val errBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
                    dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "Falha no envio do bloco (HTTP $code): $errBody")
                    throw GoogleDriveApiException(code, "Falha no envio do bloco (HTTP $code): $errBody")
                }
            }
        }
    }

    fun queryCurrentServerByteOffset(sessionUri: String, totalBytes: Long): Long {
        val requestBody = ByteArray(0).toRequestBody(null)
        val request = Request.Builder()
            .url(sessionUri)
            .header("Content-Range", "bytes */$totalBytes")
            .put(requestBody)
            .build()

        return try {
            GDriveNetworkClient.client.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 308) {
                    val rangeHeader = response.header("Range")
                    if (!rangeHeader.isNullOrBlank()) {
                        val match = Regex("bytes=0-(\\d+)").find(rangeHeader)
                        if (match != null) {
                            return match.groupValues[1].toLong() + 1
                        }
                    }
                }
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun fetchRemoteFileMetadata(fileId: String, token: String): Pair<Long, String?> {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=id,size,md5Checksum&supportsAllDrives=true"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            GDriveNetworkClient.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                    val size = json.get("size")?.asLong ?: 0L
                    val md5 = json.get("md5Checksum")?.asString
                    Pair(size, md5)
                } else {
                    Pair(0L, null)
                }
            }
        } catch (_: Exception) {
            Pair(0L, null)
        }
    }
}

