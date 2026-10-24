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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.net.HttpURLConnection
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

class UploadManager(private val oAuthManager: OAuthManager) {

    private val gson = Gson()
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    // Reusable buffer pool for reading local file to avoid GC churn in hot loop
    private val bufferPool = ThreadLocal.withInitial { ByteArray(1024 * 1024) } // 1 MB buffer

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

            // Streaming MD5 calculation on the fly
            val md5Digest = MessageDigest.getInstance("MD5")

            FileInputStream(localFile).use { fis ->
                val channel = fis.channel

                // If resuming, feed previously uploaded bytes to digest quickly to guarantee single-pass hash
                if (startByte > 0) {
                    val preBuf = bufferPool.get()
                    var preReadTotal = 0L
                    while (preReadTotal < startByte) {
                        val toRead = minOf(preBuf.size.toLong(), startByte - preReadTotal).toInt()
                        val r = fis.read(preBuf, 0, toRead)
                        if (r <= 0) break
                        md5Digest.update(preBuf, 0, r)
                        preReadTotal += r
                    }
                }

                channel.position(startByte)
                var bis = BufferedInputStream(fis, 512 * 1024)

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

                    // Check network status reactively (Requirement 3: zero polling suspension)
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
                            bytesUploaded = recheckOffset
                            channel.position(bytesUploaded)
                            bis = BufferedInputStream(fis, 512 * 1024)
                        }
                    }

                    // Check thermal governor and throttle if needed (Requirement 5)
                    CloudThermalGovernor.throttleIfSevere()

                    // Compute dynamic chunk size: 16 to 32 MiB, reduced on mobile or thermal throttle
                    val networkType = CloudNetworkMonitor.getActiveNetworkType()
                    val baseChunk = if (networkType.contains("Móveis", ignoreCase = true)) 8 * 1024 * 1024 else 24 * 1024 * 1024
                    val targetChunkBytes = CloudThermalGovernor.getRecommendedChunkSize(baseChunk)
                    val remainingBytes = totalBytes - bytesUploaded
                    val currentChunkSize = minOf(targetChunkBytes.toLong(), remainingBytes).toInt()
                    val alignedChunkSize = ((currentChunkSize + CHUNK_UNIT - 1) / CHUNK_UNIT * CHUNK_UNIT)
                        .coerceAtMost(currentChunkSize)
                        .coerceAtLeast(CHUNK_UNIT)

                    val chunkBuffer = ByteArray(currentChunkSize)
                    var readSoFar = 0
                    while (readSoFar < currentChunkSize) {
                        val r = bis.read(chunkBuffer, readSoFar, currentChunkSize - readSoFar)
                        if (r <= 0) break
                        readSoFar += r
                    }

                    if (readSoFar <= 0) break

                    // Update streaming MD5 digest for this chunk
                    md5Digest.update(chunkBuffer, 0, readSoFar)

                    val endByte = bytesUploaded + readSoFar - 1

                    // Send chunk with jittered exponential backoff and persistent retries
                    val chunkResult = uploadChunkWithInfiniteNetworkRetry(
                        sessionUri = activeSessionUri,
                        chunkData = chunkBuffer,
                        length = readSoFar,
                        startByte = bytesUploaded,
                        endByte = endByte,
                        totalBytes = totalBytes,
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

                    val ackOffset = chunkResult.acknowledgedByteOffset ?: (bytesUploaded + readSoFar)
                    val prevBytes = bytesUploaded
                    bytesUploaded = ackOffset

                    val nowMono = SystemClock.elapsedRealtime()
                    accumulatedActiveTimeMs += (nowMono - transferStartMono)
                    transferStartMono = nowMono

                    // Reposition channel if server requested another offset
                    if (bytesUploaded != prevBytes + readSoFar && bytesUploaded < totalBytes) {
                        channel.position(bytesUploaded)
                        bis = BufferedInputStream(fis, 512 * 1024)
                    }

                    // Update Room progress at every chunk
                    transferDao.updateProgress(
                        jobId = jobId,
                        offset = bytesUploaded,
                        activeMs = accumulatedActiveTimeMs,
                        idleMs = accumulatedIdleTimeMs,
                        speed = smoothedSpeedMBps,
                        updatedAt = System.currentTimeMillis()
                    )

                    // Calculate speed and report throttled metrics (max ~2x/s)
                    val timeDeltaSec = (nowMono - lastReportTimeMono) / 1000.0
                    val isFinal = chunkResult.metadata != null
                    if (timeDeltaSec >= 0.5 || isFinal) {
                        val bytesDelta = bytesUploaded - lastReportBytes
                        val instantSpeed = if (timeDeltaSec > 0) ((bytesDelta / (1024.0 * 1024.0)) / timeDeltaSec).toFloat() else 0f
                        smoothedSpeedMBps = if (smoothedSpeedMBps == 0f) instantSpeed else (0.7f * smoothedSpeedMBps + 0.3f * instantSpeed)
                        if (instantSpeed > peakSpeedMBps) peakSpeedMBps = instantSpeed

                        lastReportTimeMono = nowMono
                        lastReportBytes = bytesUploaded

                        val remaining = totalBytes - bytesUploaded
                        val speedBytesSec = (smoothedSpeedMBps * 1024 * 1024).toLong()
                        val etaSec = if (speedBytesSec > 0) remaining / speedBytesSec else 0L
                        val progressPercent = (bytesUploaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)

                        // Milestone logging every 5%
                        val currentPctInt = (progressPercent * 100).toInt()
                        if (currentPctInt >= lastMilestonePct + 5) {
                            lastMilestonePct = (currentPctInt / 5) * 5
                            AppLogger.i(TAG, "Marco de upload $lastMilestonePct% • $smoothedSpeedMBps MB/s • ${bytesUploaded}/${totalBytes} bytes • ETA: ${etaSec}s")
                        }

                        onProgress(
                            UploadProgressInfo(
                                stage = "Enviando ao Drive... ($currentPctInt%)",
                                bytesUploaded = bytesUploaded,
                                totalBytes = totalBytes,
                                progressPercent = progressPercent,
                                speedMBps = smoothedSpeedMBps,
                                speedMbps = smoothedSpeedMBps * 8f,
                                estimatedRemainingSeconds = etaSec
                            )
                        )
                    }

                    // Upload completed and file metadata returned by Google Drive
                    if (isFinal) {
                        val driveMeta = chunkResult.metadata!!
                        val computedMd5 = md5Digest.digest().joinToString("") { "%02x".format(it) }

                        AppLogger.i(TAG, "Upload concluído no Google Drive! FileId=${driveMeta.id}, MD5 local=$computedMd5, MD5 Drive=${driveMeta.md5Checksum}")

                        if (driveMeta.id.isBlank()) {
                            val err = "Upload no Google Drive falhou: a API não retornou um fileId válido."
                            AppLogger.e(TAG, err, extraParams = mapOf("jobId" to jobId))
                            dbHelper.insertLog(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", err)
                            transferDao.updateState(jobId, CloudTransferState.FALHOU, err)
                            transferDao.releaseExecutorLock(jobId, executorOwner)
                            return@withContext Result.failure(IllegalStateException(err))
                        }

                        if (driveMeta.sizeBytes <= 0L || driveMeta.sizeBytes != totalBytes) {
                            val sizeErr = "Divergência de tamanho no Google Drive para ${localFile.name}: esperado $totalBytes bytes, retornado ${driveMeta.sizeBytes} bytes (0 bytes ou incompleto)."
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
        val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size,md5Checksum,mimeType,parents")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Upload-Content-Length", totalBytes.toString())
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 60000
            conn.readTimeout = 120000

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

            OutputStreamWriter(conn.outputStream).use { it.write(metadata.toString()) }

            val code = conn.responseCode
            if (code == 200 || code == 201) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    return location
                } else {
                    val err = "Google Drive não retornou Location header da sessão resumível."
                    dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", err)
                    throw IllegalStateException(err)
                }
            } else {
                val errBody = try {
                    BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
                } catch (_: Exception) { "" }

                dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "Erro HTTP $code ao iniciar upload de '$fileName': $errBody")

                if (code == 403 || code == 507) {
                    throw InsufficientStorageException("Não há espaço suficiente no Google Drive para este backup (HTTP $code).")
                } else {
                    throw GoogleDriveApiException(code, "Falha ao iniciar upload no Google Drive (HTTP $code): $errBody")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private data class ChunkUploadResult(
        val acknowledgedByteOffset: Long?,
        val metadata: DriveFileMetadata?
    )

    private suspend fun uploadChunkWithInfiniteNetworkRetry(
        sessionUri: String,
        chunkData: ByteArray,
        length: Int,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
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

                return uploadChunkInternal(sessionUri, chunkData, length, startByte, endByte, totalBytes)
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
        chunkData: ByteArray,
        length: Int,
        startByte: Long,
        endByte: Long,
        totalBytes: Long
    ): ChunkUploadResult {
        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val requestBody = chunkData.toRequestBody(mediaType, 0, length)
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

                    val returnedSize = json.get("size")?.asLong ?: totalBytes
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
        val url = URL(sessionUri)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.instanceFollowRedirects = false
            conn.setFixedLengthStreamingMode(0)
            conn.setRequestProperty("Content-Range", "bytes */$totalBytes")
            conn.connectTimeout = 60000
            conn.readTimeout = 120000

            val code = conn.responseCode
            if (code == 308) {
                val rangeHeader = conn.getHeaderField("Range")
                if (!rangeHeader.isNullOrBlank()) {
                    val match = Regex("bytes=0-(\\d+)").find(rangeHeader)
                    if (match != null) {
                        return match.groupValues[1].toLong() + 1
                    }
                }
            }
            return 0L
        } catch (_: Exception) {
            return 0L
        } finally {
            conn.disconnect()
        }
    }
}
