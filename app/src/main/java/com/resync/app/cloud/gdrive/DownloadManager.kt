package com.resync.app.cloud.gdrive

import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.engine.OperationPartInfo
import com.resync.app.util.AppLogger
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadProgressInfo(
    val stage: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Float,
    val speedMBps: Float,
    val speedMbps: Float = speedMBps * 8f,
    val estimatedRemainingSeconds: Long,
    val parts: List<OperationPartInfo> = emptyList(),
    val isFinished: Boolean = false,
    val error: String? = null
)

data class PartRangeState(
    val partIndex: Int,
    val startByte: Long,
    val endByte: Long,
    var bytesDownloaded: Long = 0L,
    var isCompleted: Boolean = false,
    var partMd5: String? = null,
    var currentSpeedMBps: Float = 0f
) {
    val totalBytes: Long get() = (endByte - startByte + 1).coerceAtLeast(0L)
    val progressPercent: Float get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 1f
}

class DownloadManager(private val oAuthManager: OAuthManager) {

    private val gson = Gson()
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    @Volatile
    private var pauseDeferred: CompletableDeferred<Unit>? = null

    companion object {
        private const val TAG = "DownloadManager"
        private const val BUFFER_SIZE = 1024 * 1024 // 1 MB buffer
    }

    fun pause() {
        isPaused.set(true)
        if (pauseDeferred == null || pauseDeferred?.isCompleted == true) {
            pauseDeferred = CompletableDeferred()
        }
    }

    fun resume() {
        isPaused.set(false)
        pauseDeferred?.complete(Unit)
        pauseDeferred = null
    }

    fun cancel() {
        isCancelled.set(true)
        isPaused.set(false)
        pauseDeferred?.complete(Unit)
        pauseDeferred = null
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Downloads .resync / .svb container with pre-allocated .part file, multi-range parallel connections,
     * Room persistence, zero-polling network pause/resume, and verified atomic rename.
     */
    suspend fun downloadBackupFile(
        fileId: String,
        destinationFile: File,
        totalBytesEstimated: Long,
        expectedMd5: String? = null,
        accountEmail: String? = null,
        jobId: String = "download_${destinationFile.name}",
        onProgress: (DownloadProgressInfo) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val context = oAuthManager.context
        val roomDb = AppRoomDatabase.getInstance(context)
        val transferDao = roomDb.cloudTransferDao()
        val executorOwner = UUID.randomUUID().toString()

        AppLogger.i(TAG, "Iniciando download resiliente do Google Drive: fileId=$fileId, destino=${destinationFile.name}, jobId=$jobId")

        // 1. Single executor lock in Room
        val nowMs = System.currentTimeMillis()
        val staleThreshold = nowMs - (120_000L)
        val lockAcquired = transferDao.acquireExecutorLock(jobId, executorOwner, nowMs, staleThreshold)
        if (lockAcquired == 0) {
            val existing = transferDao.getTransfer(jobId)
            if (existing != null && existing.executorLockOwner != null && existing.executorLockTimestamp >= staleThreshold) {
                AppLogger.w(TAG, "Outro executor já está ativo para download jobId=$jobId. Ignorando.")
                return@withContext Result.failure(IllegalStateException("Download já em execução por outro serviço/job."))
            }
        }

        destinationFile.parentFile?.mkdirs()
        val partFile = File(destinationFile.parentFile, "${destinationFile.name}.part")

        if (isCancelled.get()) {
            partFile.delete()
            transferDao.deleteTransfer(jobId)
            transferDao.releaseExecutorLock(jobId, executorOwner)
            return@withContext Result.failure(CancellationException("Download cancelado pelo usuário."))
        }

        var token = oAuthManager.getValidAccessToken(accountEmail)
        if (token == null) {
            val refreshed = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
            if (refreshed.isSuccess) {
                token = refreshed.getOrThrow()
            } else {
                transferDao.updateState(jobId, CloudTransferState.FALHOU, "Sessão expirada.")
                transferDao.releaseExecutorLock(jobId, executorOwner)
                return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado no Google Drive.", 401))
            }
        }

        // 2. Fetch size and MD5 from Drive metadata if not provided
        var remoteSize = totalBytesEstimated
        var remoteMd5 = expectedMd5
        if (remoteSize <= 0 || remoteMd5.isNullOrBlank()) {
            val (metaSize, metaMd5) = fetchRemoteFileMetadata(fileId, token)
            if (metaSize > 0) remoteSize = metaSize
            if (!metaMd5.isNullOrBlank()) remoteMd5 = metaMd5
        }

        val totalBytes = remoteSize
        if (totalBytes <= 0) {
            transferDao.releaseExecutorLock(jobId, executorOwner)
            return@withContext Result.failure(IllegalStateException("Tamanho do arquivo inválido retornado pelo Google Drive."))
        }

        // Reaproveitamento inteligente: se o arquivo de destino já existe e é válido, não redownload
        if (destinationFile.exists() && destinationFile.length() == totalBytes) {
            val header = ByteArray(4)
            val headerRead = FileInputStream(destinationFile).use { it.read(header) }
            val isHeaderValid = headerRead >= 4 && (
                (header[0] == 0x53.toByte() && header[1] == 0x56.toByte() && header[2] == 0x42.toByte() && header[3] == 0x01.toByte()) ||
                (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte())
            )
            if (isHeaderValid) {
                AppLogger.i(TAG, "Container já presente no destino com tamanho e cabeçalho corretos: ${destinationFile.name} ($totalBytes bytes). Reutilizando sem download!")
                transferDao.updateState(jobId, CloudTransferState.CONCLUIDO)
                transferDao.releaseExecutorLock(jobId, executorOwner)
                onProgress(
                    DownloadProgressInfo(
                        stage = "Arquivo em cache já disponível!",
                        bytesDownloaded = totalBytes,
                        totalBytes = totalBytes,
                        progressPercent = 1.0f,
                        speedMBps = 0f,
                        estimatedRemainingSeconds = 0L,
                        isFinished = true
                    )
                )
                return@withContext Result.success(destinationFile)
            }
        }

        // 3. Pre-allocation of the destination .part file (Requirement 3)
        if (!partFile.exists() || partFile.length() != totalBytes) {
            RandomAccessFile(partFile, "rw").use { it.setLength(totalBytes) }
        }

        // 4. Number of parallel range parts (up to 4, dynamically adapted by thermal status)
        val maxConnections = if (totalBytes < 32L * 1024 * 1024) 1 else 3
        val parallelConnections = CloudThermalGovernor.getParallelDownloadConnections(maxConnections)

        // 5. Restore or initialize parts state from Room
        var savedRecord = transferDao.getTransfer(jobId)
        val parts: List<PartRangeState> = if (savedRecord?.completedPartsJson != null) {
            try {
                val listType = object : TypeToken<List<PartRangeState>>() {}.type
                gson.fromJson(savedRecord.completedPartsJson, listType)
            } catch (_: Exception) {
                createDefaultPartRanges(totalBytes, parallelConnections)
            }
        } else {
            createDefaultPartRanges(totalBytes, parallelConnections)
        }

        // Save initial state to Room
        transferDao.insertOrUpdate(
            CloudTransferEntity(
                jobId = jobId,
                type = "DOWNLOAD",
                title = destinationFile.name,
                fileId = fileId,
                totalBytes = totalBytes,
                localPath = partFile.absolutePath,
                completedPartsJson = gson.toJson(parts),
                expectedMd5 = remoteMd5,
                state = CloudTransferState.EM_ANDAMENTO,
                accountEmail = accountEmail,
                executorLockOwner = executorOwner,
                executorLockTimestamp = System.currentTimeMillis()
            )
        )

        var lastEmitTimeMono = 0L
        var accumulatedActiveTimeMs = savedRecord?.activeTimeMs ?: 0L
        var accumulatedIdleTimeMs = savedRecord?.idleTimeMs ?: 0L
        var downloadStartMono = SystemClock.elapsedRealtime()

        fun reportProgress(isFinished: Boolean = false, stageName: String? = null) {
            val nowMono = SystemClock.elapsedRealtime()
            val totalDownloaded = parts.sumOf { it.bytesDownloaded }
            val totalSpeedMBps = parts.sumOf { it.currentSpeedMBps.toDouble() }.toFloat()
            val remainingBytes = (totalBytes - totalDownloaded).coerceAtLeast(0L)
            val etaSeconds = if (totalSpeedMBps > 0) (remainingBytes / (totalSpeedMBps * 1024 * 1024)).toLong() else 0L
            val percent = if (totalBytes > 0) (totalDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0.5f

            val operationParts = parts.map { p ->
                OperationPartInfo(
                    partIndex = p.partIndex,
                    totalParts = parts.size,
                    bytesProcessed = p.bytesDownloaded,
                    totalBytes = p.totalBytes,
                    progressPercent = p.progressPercent,
                    speedMBps = p.currentSpeedMBps,
                    isCompleted = p.isCompleted
                )
            }

            val defaultStage = if (parts.size > 1) "Baixando em ${parts.size} conexões simultâneas..." else "Baixando do Drive..."
            onProgress(
                DownloadProgressInfo(
                    stage = stageName ?: defaultStage,
                    bytesDownloaded = totalDownloaded,
                    totalBytes = totalBytes,
                    progressPercent = percent,
                    speedMBps = totalSpeedMBps,
                    speedMbps = totalSpeedMBps * 8f,
                    estimatedRemainingSeconds = etaSeconds,
                    parts = operationParts,
                    isFinished = isFinished
                )
            )
        }

        reportProgress()

        // 6. Execute Parallel Downloads with per-part streaming and auto-resume
        val downloadResult: Result<Unit> = try {
            coroutineScope {
                val jobs = parts.map { part ->
                    async(Dispatchers.IO) {
                        downloadPartRangeWithRetry(
                            fileId = fileId,
                            part = part,
                            partFile = partFile,
                            accountEmail = accountEmail,
                            onChunkWritten = { read ->
                                val nowMono = SystemClock.elapsedRealtime()
                                if (nowMono - lastEmitTimeMono >= 500L) { // Throttled to ~2x/s
                                    lastEmitTimeMono = nowMono
                                    accumulatedActiveTimeMs += (nowMono - downloadStartMono)
                                    downloadStartMono = nowMono

                                    launch(Dispatchers.IO) {
                                        transferDao.updateProgress(
                                            jobId = jobId,
                                            offset = parts.sumOf { it.bytesDownloaded },
                                            activeMs = accumulatedActiveTimeMs,
                                            idleMs = accumulatedIdleTimeMs,
                                            speed = parts.sumOf { it.currentSpeedMBps.toDouble() }.toFloat(),
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    }
                                    reportProgress()
                                }
                            }
                        )
                    }
                }

                val results = jobs.awaitAll()
                val failure = results.firstOrNull { it.isFailure }
                if (failure != null) {
                    Result.failure(failure.exceptionOrNull() ?: IOException("Falha no download de uma das partes."))
                } else {
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (downloadResult.isFailure) {
            val ex = downloadResult.exceptionOrNull()
            if (isCancelled.get()) {
                partFile.delete()
                transferDao.deleteTransfer(jobId)
                transferDao.releaseExecutorLock(jobId, executorOwner)
                return@withContext Result.failure(CancellationException("Download cancelado pelo usuário."))
            }

            transferDao.updateState(jobId, CloudTransferState.FALHOU, ex?.message)
            transferDao.releaseExecutorLock(jobId, executorOwner)
            return@withContext Result.failure(ex ?: IOException("Falha no download."))
        }

        // 7. Verify Integrity of pre-allocated file before atomic rename (Requirement 3)
        // 7.1 Confirm length
        val finalLength = partFile.length()
        if (finalLength != totalBytes) {
            partFile.delete()
            val lenErr = "Divergência de tamanho: esperado $totalBytes bytes, baixado $finalLength bytes."
            transferDao.updateState(jobId, CloudTransferState.FALHOU, lenErr)
            transferDao.releaseExecutorLock(jobId, executorOwner)
            return@withContext Result.failure(IllegalStateException(lenErr))
        }

        // 7.2 Header Magic check (SVB1 or PK\x03\x04)
        reportProgress(stageName = "Verificando cabeçalho do container...")
        val headerBytes = ByteArray(16)
        val readHeader = FileInputStream(partFile).use { it.read(headerBytes) }
        val isSvbMagic = readHeader >= 4 && (
            (headerBytes[0] == 0x53.toByte() && headerBytes[1] == 0x56.toByte() && headerBytes[2] == 0x42.toByte() && headerBytes[3] == 0x01.toByte()) ||
            (headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() && headerBytes[2] == 0x03.toByte() && headerBytes[3] == 0x04.toByte())
        )
        if (!isSvbMagic) {
            val hex16 = headerBytes.take(readHeader.coerceAtLeast(0)).joinToString(" ") { "%02X".format(it) }
            val ascii16 = String(headerBytes.take(readHeader.coerceAtLeast(0)).toByteArray(), Charsets.US_ASCII).replace(Regex("[^\\x20-\\x7E]"), ".")
            val headerErr = "Cabeçalho de container inválido recebido do Google Drive. Esperado SVB1 ou PK\\x03\\x04. (Recebido: $hex16 | '$ascii16', tamanho: $finalLength vs esperado: $totalBytes)"
            AppLogger.e(TAG, headerErr, extraParams = mapOf(
                "jobId" to jobId,
                "tamanhoRecebido" to finalLength.toString(),
                "tamanhoEsperado" to totalBytes.toString(),
                "hex16" to hex16,
                "ascii16" to ascii16
            ))
            partFile.delete()
            transferDao.updateState(jobId, CloudTransferState.FALHOU, headerErr)
            transferDao.releaseExecutorLock(jobId, executorOwner)
            return@withContext Result.failure(IllegalStateException(headerErr))
        }

        // 7.3 MD5 checksum verification against Drive metadata
        if (!remoteMd5.isNullOrBlank()) {
            reportProgress(stageName = "Verificando integridade MD5...")
            val digest = MessageDigest.getInstance("MD5")
            val buf = ByteArray(1024 * 1024)
            FileInputStream(partFile).use { fis ->
                var r: Int
                while (fis.read(buf).also { r = it } != -1) {
                    digest.update(buf, 0, r)
                }
            }
            val computedMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!computedMd5.equals(remoteMd5, ignoreCase = true)) {
                partFile.delete()
                val md5Err = "Falha de integridade: MD5 baixado ($computedMd5) diverge da nuvem ($remoteMd5)."
                AppLogger.e(TAG, md5Err, extraParams = mapOf("jobId" to jobId))
                transferDao.updateState(jobId, CloudTransferState.FALHOU, md5Err)
                transferDao.releaseExecutorLock(jobId, executorOwner)
                return@withContext Result.failure(IllegalStateException(md5Err))
            }
        }

        // 8. Atomic Rename from .part to destination (Requirement 3: never overwrite before verification)
        destinationFile.delete()
        val renamed = partFile.renameTo(destinationFile)
        if (!renamed) {
            // Fallback: move via stream if atomic rename fails across mount points
            partFile.copyTo(destinationFile, overwrite = true)
            partFile.delete()
        }

        transferDao.updateState(jobId, CloudTransferState.CONCLUIDO)
        transferDao.releaseExecutorLock(jobId, executorOwner)

        reportProgress(isFinished = true, stageName = "Download concluído com sucesso!")
        AppLogger.i(TAG, "Download concluído com sucesso: ${destinationFile.name} (${destinationFile.length()} bytes)")
        Result.success(destinationFile)
    }

    private fun createDefaultPartRanges(totalBytes: Long, partCount: Int): List<PartRangeState> {
        val partSize = totalBytes / partCount
        return (0 until partCount).map { i ->
            val start = i * partSize
            val end = if (i == partCount - 1) totalBytes - 1 else (i + 1) * partSize - 1
            PartRangeState(
                partIndex = i + 1,
                startByte = start,
                endByte = end,
                bytesDownloaded = 0L,
                isCompleted = false
            )
        }
    }

    private suspend fun downloadPartRangeWithRetry(
        fileId: String,
        part: PartRangeState,
        partFile: File,
        accountEmail: String?,
        onChunkWritten: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (part.isCompleted && part.bytesDownloaded >= part.totalBytes) {
            return@withContext Result.success(Unit)
        }

        var attempt = 0
        var lastEx: Exception? = null

        while (!isCancelled.get()) {
            attempt++
            if (part.isCompleted) return@withContext Result.success(Unit)

            // Reactive network suspension (Requirement 3)
            while (!CloudNetworkMonitor.isConnected() && !isCancelled.get()) {
                CloudNetworkMonitor.awaitNetworkAvailable()
            }

            var token = oAuthManager.getValidAccessToken(accountEmail)
            if (token == null) {
                token = oAuthManager.forceRefreshToken(accountEmail = accountEmail).getOrNull()
                if (token == null) {
                    return@withContext Result.failure(GoogleDriveSessionExpiredException("Sessão expirada no Drive.", 401))
                }
            }

            val rangeStart = part.startByte + part.bytesDownloaded
            if (rangeStart > part.endByte) {
                part.isCompleted = true
                return@withContext Result.success(Unit)
            }

            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true")
                .header("Authorization", "Bearer $token")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$rangeStart-${part.endByte}")
                .build()

            val partStartMono = SystemClock.elapsedRealtime()
            val startBytes = part.bytesDownloaded

            try {
                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val contentType = response.header("Content-Type") ?: "desconhecido"

                    if (code == 401) {
                        oAuthManager.forceRefreshToken(accountEmail = accountEmail)
                        throw IOException("HTTP 401 Unauthorized - token renovado")
                    }

                    if (code != 206) {
                        val errBytes = response.body?.bytes() ?: ByteArray(0)
                        val hex16 = errBytes.take(16).joinToString(" ") { "%02X".format(it) }
                        val errDetail = "HTTP $code inesperado ao baixar bloco (esperado 206). Content-Type=$contentType, tamanho recebido=${errBytes.size}, hex16=[$hex16]"
                        AppLogger.e(TAG, errDetail)
                        throw GoogleDriveApiException(code, errDetail)
                    }

                    val body = response.body ?: throw IOException("Corpo da resposta vazio para parte ${part.partIndex}")
                    val inputStream = body.byteStream()

                    RandomAccessFile(partFile, "rw").use { raf ->
                        raf.seek(rangeStart)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int

                        while (inputStream.read(buffer).also { read = it } != -1) {
                            while (isPaused.get() && !isCancelled.get()) {
                                pauseDeferred?.await()
                            }

                            if (isCancelled.get()) {
                                return@withContext Result.failure(CancellationException("Download cancelado."))
                            }

                            raf.write(buffer, 0, read)
                            part.bytesDownloaded += read

                            val elapsed = ((SystemClock.elapsedRealtime() - partStartMono) / 1000.0).coerceAtLeast(0.1)
                            val speed = ((part.bytesDownloaded - startBytes) / (1024.0 * 1024.0)) / elapsed
                            part.currentSpeedMBps = speed.toFloat()

                            onChunkWritten(read)
                        }
                    }

                    if (part.bytesDownloaded >= part.totalBytes) {
                        part.isCompleted = true
                        return@withContext Result.success(Unit)
                    }
                }
            } catch (e: Exception) {
                lastEx = e
                if (isCancelled.get()) return@withContext Result.failure(e)
                val backoffMs = (1L shl (attempt.coerceAtMost(4) - 1)) * 1000L
                delay(backoffMs)
            }
        }
        Result.failure(lastEx ?: IOException("Download cancelado."))
    }

    private suspend fun fetchRemoteFileMetadata(fileId: String, token: String): Pair<Long, String?> = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=id,size,md5Checksum"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
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

    suspend fun fetchRemoteFileSize(fileId: String, token: String): Long {
        return fetchRemoteFileMetadata(fileId, token).first
    }

    /**
     * Download simples direto via files.get com alt=media, sem cabeçalho Range.
     * Utilizado para download de teste de diagnóstico e arquivos sem validação de container.
     */
    suspend fun downloadSimpleFile(
        fileId: String,
        destinationFile: File,
        accountEmail: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken(accountEmail)
        if (token == null) {
            val refreshed = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
            if (refreshed.isSuccess) {
                token = refreshed.getOrThrow()
            } else {
                return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado no Google Drive.", 401))
            }
        }

        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        destinationFile.parentFile?.mkdirs()
        val tempDest = File(destinationFile.parentFile, "${destinationFile.name}.tmp")
        tempDest.delete()

        try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type") ?: "desconhecido"
                val body = response.body

                if (!response.isSuccessful || body == null) {
                    val errorBytes = body?.bytes() ?: ByteArray(0)
                    val hex16 = errorBytes.take(16).joinToString(" ") { "%02X".format(it) }
                    val errDetail = "Falha no download direto: HTTP $code, Content-Type=$contentType, bytes=${errorBytes.size}, hex16=[$hex16]"
                    AppLogger.e(TAG, errDetail)
                    return@withContext Result.failure(GoogleDriveApiException(code, errDetail))
                }

                FileOutputStream(tempDest).use { fos ->
                    body.byteStream().copyTo(fos)
                }

                destinationFile.delete()
                if (!tempDest.renameTo(destinationFile)) {
                    tempDest.copyTo(destinationFile, overwrite = true)
                    tempDest.delete()
                }

                AppLogger.i(TAG, "Download direto simples concluído: ${destinationFile.name} (${destinationFile.length()} bytes, HTTP $code, Type=$contentType)")
                Result.success(destinationFile)
            }
        } catch (e: Exception) {
            tempDest.delete()
            AppLogger.e(TAG, "Erro no download simples do arquivo $fileId: ${e.message}", e)
            Result.failure(e)
        }
    }
}

