package com.resync.app.cloud.gdrive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.resync.app.data.model.BackupManifest
import com.resync.app.data.model.BackupRecord
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.data.room.BackupIndexCacheEntity
import com.resync.app.engine.MultiPartSeekableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Manages backup index inspection, sidecar generation (.index.json),
 * Room caching, and remote Range-based Central Directory parsing for Google Drive.
 */
class BackupIndexManager(
    private val context: Context,
    private val oAuthManager: OAuthManager,
    private val driveFileManager: DriveFileManager
) {

    private val gson = Gson()
    private val roomDb = AppRoomDatabase.getInstance(context)
    private val indexDao = roomDb.backupIndexDao()
    private val dbHelper = AppDatabaseHelper.getInstance(context)

    companion object {
        private const val TAG = "RESTORE_INDEX"
        private const val MAX_RANGE_BYTES = 32L * 1024 * 1024 // teto por requisição Range em memória
        private const val MAX_ERROR_BODY = 64 * 1024
        private val SVB_MAGIC = byteArrayOf(0x53, 0x56, 0x42, 0x01) // "SVB1"
        private val PK_LOCAL_HEADER_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
        private val GZIP_MAGIC_BYTE1 = 0x1F.toByte()
        private val GZIP_MAGIC_BYTE2 = 0x8B.toByte()
    }

    // =========================================================================
    // 1. ROOM CACHE OPERATIONS
    // =========================================================================

    suspend fun getCachedManifest(backupId: String, driveFileId: String? = null, filePath: String? = null): BackupManifest? = withContext(Dispatchers.IO) {
        val cached = indexDao.getByBackupId(backupId)
            ?: (driveFileId?.let { indexDao.getByDriveFileId(it) })
            ?: (filePath?.let { indexDao.getByFilePath(it) })

        if (cached != null) {
            try {
                return@withContext decompressManifestJson(cached.manifestGzipBase64)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao decodificar manifesto em cache para backup $backupId: ${e.message}")
            }
        }
        null
    }

    suspend fun cacheManifest(
        backupId: String,
        backupTitle: String,
        filePath: String?,
        driveFileId: String?,
        sizeBytes: Long,
        manifest: BackupManifest
    ) = withContext(Dispatchers.IO) {
        try {
            val compressedBase64 = compressManifestJson(manifest)
            val entity = BackupIndexCacheEntity(
                backupId = backupId,
                backupTitle = backupTitle,
                filePath = filePath,
                driveFileId = driveFileId,
                sizeBytes = sizeBytes,
                manifestGzipBase64 = compressedBase64,
                itemCount = manifest.items.size,
                cachedAt = System.currentTimeMillis()
            )
            indexDao.insertOrUpdate(entity)
            Log.d(TAG, "Manifesto de '$backupTitle' salvo no cache Room com sucesso (${manifest.items.size} itens).")
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao salvar índice no Room: ${e.message}")
        }
    }

    // =========================================================================
    // 2. COMPRESSION & SIDECAR HANDLING
    // =========================================================================

    fun compressManifestJson(manifest: BackupManifest): String {
        val json = gson.toJson(manifest)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(json.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompressManifestJson(base64Gzip: String): BackupManifest {
        val compressedBytes = Base64.decode(base64Gzip, Base64.DEFAULT)
        val decompressedJson = GZIPInputStream(ByteArrayInputStream(compressedBytes)).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        return gson.fromJson(decompressedJson, BackupManifest::class.java)
    }

    /**
     * Reads a manifest from an index file (.index.json), which may be raw JSON or GZIP-compressed.
     */
    fun readManifestFromIndexBytes(bytes: ByteArray): BackupManifest {
        val isGzip = bytes.size >= 2 && bytes[0] == GZIP_MAGIC_BYTE1 && bytes[1] == GZIP_MAGIC_BYTE2
        val jsonString = if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return gson.fromJson(jsonString, BackupManifest::class.java)
    }

    /**
     * Writes compressed sidecar file svb_XXXX.index.json alongside the container.
     */
    suspend fun writeLocalSidecar(manifest: BackupManifest, containerFile: File): File = withContext(Dispatchers.IO) {
        val dir = containerFile.parentFile ?: context.filesDir
        val baseName = containerFile.nameWithoutExtension
        val sidecarFile = File(dir, "$baseName.index.json")

        val jsonBytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
        FileOutputStream(sidecarFile).use { fos ->
            GZIPOutputStream(fos).use { gzip ->
                gzip.write(jsonBytes)
            }
        }
        Log.i(TAG, "Sidecar local gerado: ${sidecarFile.absolutePath} (${sidecarFile.length()} bytes)")
        sidecarFile
    }

    // =========================================================================
    // 3. LOCAL STORAGE INSPECTION (NO COPY, NO UNPACK)
    // =========================================================================

    suspend fun inspectLocalBackup(containerFile: File, backupId: String? = null): BackupManifest = withContext(Dispatchers.IO) {
        // 1. Try sidecar file in the same directory
        val baseName = containerFile.nameWithoutExtension
        val dir = containerFile.parentFile
        val candidateSidecars = listOf(
            File(dir, "$baseName.index.json"),
            File(dir, "${containerFile.name}.index.json")
        )

        for (candidate in candidateSidecars) {
            if (candidate.exists() && candidate.length() > 0L) {
                try {
                    val bytes = candidate.readBytes()
                    val manifest = readManifestFromIndexBytes(bytes)
                    cacheManifest(
                        backupId = backupId ?: manifest.backupId,
                        backupTitle = manifest.title,
                        filePath = containerFile.absolutePath,
                        driveFileId = null,
                        sizeBytes = containerFile.length(),
                        manifest = manifest
                    )
                    Log.i(TAG, "Índice local lido com sucesso a partir do sidecar: ${candidate.name}")
                    return@withContext manifest
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao ler sidecar existente ${candidate.name}: ${e.message}")
                }
            }
        }

        // 2. Read container manifest directly using RandomAccessFile in Dispatchers.IO
        if (!containerFile.exists()) {
            throw IOException("Arquivo local não encontrado: ${containerFile.absolutePath}")
        }

        val manifest = inspectLocalContainerDirectly(containerFile)

        // 3. Cache to Room and create sidecar for future instant reads
        cacheManifest(
            backupId = backupId ?: manifest.backupId,
            backupTitle = manifest.title,
            filePath = containerFile.absolutePath,
            driveFileId = null,
            sizeBytes = containerFile.length(),
            manifest = manifest
        )

        try {
            writeLocalSidecar(manifest, containerFile)
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao salvar sidecar local para ${containerFile.name}: ${e.message}")
        }

        manifest
    }

    private fun inspectLocalContainerDirectly(containerFile: File): BackupManifest {
        // Inspect initial 4 bytes
        val headerBytes = ByteArray(4)
        FileInputStream(containerFile).use { it.read(headerBytes) }

        if (headerBytes.contentEquals(SVB_MAGIC)) {
            // SVB1 Container: Manifest length at bytes 4..7, JSON at bytes 8..
            FileInputStream(containerFile).use { fis ->
                fis.skip(4)
                val lenBytes = ByteArray(4)
                fis.read(lenBytes)
                val manifestLen = ByteBuffer.wrap(lenBytes).int
                if (manifestLen <= 0 || manifestLen > 10 * 1024 * 1024) {
                    throw IllegalStateException("Tamanho do manifesto SVB1 inválido: $manifestLen")
                }
                val jsonBytes = ByteArray(manifestLen)
                var totalRead = 0
                while (totalRead < manifestLen) {
                    val read = fis.read(jsonBytes, totalRead, manifestLen - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                return gson.fromJson(String(jsonBytes, Charsets.UTF_8), BackupManifest::class.java)
            }
        }

        // Standard ZIP container: read with MultiPartSeekableByteChannel and Commons Compress ZipFile
        MultiPartSeekableByteChannel(listOf(containerFile)).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                val entry = zipFile.getEntry("manifest.json")
                    ?: throw IllegalStateException("Arquivo 'manifest.json' não encontrado no container ZIP.")
                val json = zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                return gson.fromJson(json, BackupManifest::class.java)
            }
        }
    }

    // =========================================================================
    // 4. GOOGLE DRIVE INSPECTION & DIAGNOSTICS (DIAGNÓSTICO OBRIGATÓRIO)
    // =========================================================================

    /**
     * Inspects a Google Drive backup without downloading the full container.
     */
    suspend fun inspectDriveBackup(
        fileId: String,
        expectedName: String,
        expectedSizeBytes: Long,
        accountEmail: String?,
        onProgress: ((stage: String, percent: Float) -> Unit)? = null
    ): Result<BackupManifest> = withContext(Dispatchers.IO) {
        onProgress?.invoke("Verificando metadados no Google Drive...", 0.10f)

        // 1. Get and validate access token
        var token = oAuthManager.getValidAccessToken(accountEmail)
        if (token == null) {
            val refreshed = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
            if (refreshed.isSuccess) {
                token = refreshed.getOrThrow()
            } else {
                return@withContext Result.failure(GoogleDriveSessionExpiredException("Sessão do Google Drive expirada. Reconecte a conta.", 401))
            }
        }

        // 2. Metadados do arquivo e Validação do Container (Item C do diagnóstico)
        val metaRes = fetchDriveFileMetadata(fileId, token)
        if (metaRes.isFailure) {
            val ex = metaRes.exceptionOrNull()
            if (ex is GoogleDriveSessionExpiredException) {
                // Retry once with refreshed token
                val refreshed = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    val retryMeta = fetchDriveFileMetadata(fileId, token)
                    if (retryMeta.isFailure) return@withContext Result.failure(retryMeta.exceptionOrNull() ?: ex)
                } else {
                    return@withContext Result.failure(ex)
                }
            } else {
                return@withContext Result.failure(ex ?: IOException("Falha ao obter metadados do Drive."))
            }
        }

        val driveMeta = metaRes.getOrThrow()

        // Validar se é pasta ou atalho
        if (driveMeta.mimeType == "application/vnd.google-apps.folder" || driveMeta.mimeType == "application/vnd.google-apps.shortcut") {
            return@withContext Result.failure(IllegalArgumentException("O fileId '$fileId' aponta para uma pasta ou atalho, não um container de backup."))
        }

        // Comparar tamanho do arquivo no Drive com o esperado
        val remoteSize = driveMeta.sizeBytes
        if (remoteSize <= 0L) {
            return@withContext Result.failure(IllegalStateException("backup incompleto no Drive (tamanho reportado pelo Drive: 0 bytes)"))
        }

        if (expectedSizeBytes > 0L && remoteSize != expectedSizeBytes) {
            val msg = "backup incompleto no Drive (tamanho no Drive: $remoteSize bytes, esperado: $expectedSizeBytes bytes)"
            Log.w(TAG, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        onProgress?.invoke("Localizando índice complementar (.index.json)...", 0.25f)

        // 3. Tentar carregar índice sidecar svb_XXXX.index.json
        val sidecarRes = tryFetchRemoteSidecar(driveMeta, token, accountEmail)
        if (sidecarRes.isSuccess) {
            val manifest = sidecarRes.getOrThrow()
            onProgress?.invoke("Índice carregado!", 1.0f)
            cacheManifest(manifest.backupId, manifest.title, null, fileId, remoteSize, manifest)
            return@withContext Result.success(manifest)
        }

        // 4. Backups antigos sem sidecar: ler apenas o diretório central do ZIP via Range requests
        onProgress?.invoke("Lendo diretório central remoto via Range...", 0.45f)
        val centralDirRes = readRemoteManifestViaCentralDirectory(
            fileId = fileId,
            fileName = driveMeta.name,
            fileSize = remoteSize,
            token = token,
            accountEmail = accountEmail,
            onProgress = onProgress
        )

        if (centralDirRes.isSuccess) {
            val manifest = centralDirRes.getOrThrow()
            onProgress?.invoke("Índice pronto!", 1.0f)
            cacheManifest(manifest.backupId, manifest.title, null, fileId, remoteSize, manifest)

            // Criar e enviar o sidecar ao Drive em segundo plano para otimizar futuras leituras
            uploadSidecarToDriveAsync(manifest, driveMeta, accountEmail)
            return@withContext Result.success(manifest)
        }

        // Se falhou, retornar o erro real sem tentar baixar os 29 GB
        val ex = centralDirRes.exceptionOrNull()
        Log.e(TAG, "Falha na leitura do índice do Drive para ${driveMeta.name}: ${ex?.message}", ex)
        Result.failure(ex ?: IOException("Não foi possível ler o índice do backup do Google Drive."))
    }

    /**
     * Tenta localizar e baixar o sidecar svb_XXXX.index.json
     */
    private suspend fun tryFetchRemoteSidecar(
        driveMeta: DriveFileMetadata,
        token: String,
        accountEmail: String?
    ): Result<BackupManifest> {
        val baseName = driveMeta.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
        val candidateNames = listOf(
            "$baseName.index.json",
            "${driveMeta.name}.index.json"
        )

        for (candName in candidateNames) {
            val searchRes = driveFileManager.searchFileByName(candName, token)
            val indexId = searchRes.getOrNull()
            if (!indexId.isNullOrBlank()) {
                val fetchRes = downloadRemoteIndexBytes(indexId, candName, accountEmail)
                if (fetchRes.isSuccess) {
                    try {
                        val manifest = readManifestFromIndexBytes(fetchRes.getOrThrow())
                        Log.i(TAG, "Sidecar remoto '$candName' lido com sucesso.")
                        return Result.success(manifest)
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao decodificar sidecar $candName: ${e.message}")
                    }
                }
            }
        }
        return Result.failure(FileNotFoundException("Nenhum sidecar .index.json encontrado no Drive para ${driveMeta.name}"))
    }

    /**
     * Realiza download completo do arquivo de índice pequeno (.index.json) com diagnóstico.
     */
    private suspend fun downloadRemoteIndexBytes(
        indexFileId: String,
        indexName: String,
        accountEmail: String?
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var currentToken = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Não autenticado", 401))

        for (attempt in 1..maxRetries) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://www.googleapis.com/drive/v3/files/$indexFileId?alt=media&supportsAllDrives=true")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $currentToken")
                    setRequestProperty("Accept-Encoding", "identity")
                    connectTimeout = 15000
                    readTimeout = 20000
                }

                val code = conn.responseCode
                val contentType = conn.contentType ?: ""
                val contentRange = conn.getHeaderField("Content-Range") ?: "N/A"

                if (code == 401) {
                    conn.disconnect()
                    val refreshed = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
                    if (refreshed.isSuccess) {
                        currentToken = refreshed.getOrThrow()
                        continue
                    }
                    return@withContext Result.failure(GoogleDriveSessionExpiredException("Sessão expirada (HTTP 401)", 401))
                }

                if (code == 429 || code in 500..599) {
                    conn.disconnect()
                    delay(1000L * (1L shl (attempt - 1)))
                    continue
                }

                if (code != 200 && code != 206) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    logRestoreIndexDiagnostic(indexFileId, indexName, 0L, code, contentType, contentRange, ByteArray(0))
                    return@withContext Result.failure(GoogleDriveApiException(code, "HTTP $code ao baixar índice: $err"))
                }

                val bytes = conn.inputStream.use { it.readBytes() }
                logRestoreIndexDiagnostic(indexFileId, indexName, bytes.size.toLong(), code, contentType, contentRange, bytes)
                return@withContext Result.success(bytes)
            } catch (e: Exception) {
                if (attempt == maxRetries) return@withContext Result.failure(e)
                delay(1000L)
            } finally {
                conn?.disconnect()
            }
        }
        Result.failure(IOException("Falha ao baixar índice após $maxRetries tentativas."))
    }

    // =========================================================================
    // 5. PARSER REMOTO DO DIRETÓRIO CENTRAL ZIP/ZIP64 VIA RANGE (SEM BAIXAR 29 GB)
    // =========================================================================

    /**
     * Reads manifest.json from remote container by requesting only the End of Central Directory,
     * Central Directory records, and the local entry header for manifest.json.
     * Total data downloaded is typically 100-300 KB instead of 29 GB.
     */
    suspend fun readRemoteManifestViaCentralDirectory(
        fileId: String,
        fileName: String,
        fileSize: Long,
        token: String,
        accountEmail: String?,
        onProgress: ((stage: String, percent: Float) -> Unit)? = null
    ): Result<BackupManifest> = withContext(Dispatchers.IO) {
        if (fileSize < 22) {
            return@withContext Result.failure(IllegalStateException("Arquivo remoto muito pequeno ($fileSize bytes)"))
        }

        // 1. Verificar os primeiros 4 bytes do arquivo se for SVB1
        val headBytes = fetchRemoteRangeBytes(fileId, fileName, fileSize, 0L, 31L, accountEmail).getOrNull()
        if (headBytes != null && headBytes.size >= 4) {
            val magic = headBytes.copyOfRange(0, 4)
            if (magic.contentEquals(SVB_MAGIC)) {
                // Legado SVB1: ler comprimento do manifesto nos bytes 4..7
                onProgress?.invoke("Lendo manifesto SVB1...", 0.60f)
                val lenBytes = headBytes.copyOfRange(4, 8)
                val mLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
                if (mLen in 1..(10 * 1024 * 1024)) {
                    val svbManifestBytes = fetchRemoteRangeBytes(fileId, fileName, fileSize, 8L, 7L + mLen, accountEmail)
                    if (svbManifestBytes.isSuccess) {
                        val json = String(svbManifestBytes.getOrThrow(), Charsets.UTF_8)
                        val manifest = gson.fromJson(json, BackupManifest::class.java)
                        return@withContext Result.success(manifest)
                    }
                }
            }
        }

        // 2. Obter os últimos 65.557 bytes do arquivo para encontrar o EOCD (End of Central Directory)
        onProgress?.invoke("Buscando EOCD no final do container...", 0.50f)
        val tailReadSize = minOf(fileSize, 65557L).toInt()
        val tailStart = fileSize - tailReadSize

        val tailResult = fetchRemoteRangeBytes(fileId, fileName, fileSize, tailStart, fileSize - 1, accountEmail)
        if (tailResult.isFailure) {
            return@withContext Result.failure(tailResult.exceptionOrNull() ?: IOException("Falha ao ler fim do container."))
        }
        val tailBytes = tailResult.getOrThrow()

        // 3. Localizar EOCD signature 0x06054b50 (PK\x05\x06)
        var eocdOffsetInTail = -1
        for (i in (tailBytes.size - 22) downTo 0) {
            if (tailBytes[i] == 0x50.toByte() &&
                tailBytes[i + 1] == 0x4B.toByte() &&
                tailBytes[i + 2] == 0x05.toByte() &&
                tailBytes[i + 3] == 0x06.toByte()
            ) {
                eocdOffsetInTail = i
                break
            }
        }

        if (eocdOffsetInTail == -1) {
            return@withContext Result.failure(IllegalStateException("Assinatura EOCD não encontrada no container ZIP ($fileName)."))
        }

        val eocdAbsoluteOffset = tailStart + eocdOffsetInTail
        val bbEocd = ByteBuffer.wrap(tailBytes, eocdOffsetInTail, tailBytes.size - eocdOffsetInTail).order(ByteOrder.LITTLE_ENDIAN)
        bbEocd.position(12)
        val cdSize32 = bbEocd.int.toLong() and 0xFFFFFFFFL
        val cdOffset32 = bbEocd.int.toLong() and 0xFFFFFFFFL

        var cdOffset = cdOffset32
        var cdSize = cdSize32

        // 4. Verificar se é ZIP64 (necessário para arquivos > 4 GB ou ~29 GB)
        // O ZIP64 Locator fica 20 bytes antes do EOCD
        val zip64LocatorOffsetInTail = eocdOffsetInTail - 20
        if (zip64LocatorOffsetInTail >= 0) {
            val bbLoc = ByteBuffer.wrap(tailBytes, zip64LocatorOffsetInTail, 20).order(ByteOrder.LITTLE_ENDIAN)
            val locSig = bbLoc.int
            if (locSig == 0x07064b50) { // PK\x06\x07 (ZIP64 locator)
                bbLoc.position(8)
                val zip64EocdOffset = bbLoc.long
                Log.d(TAG, "ZIP64 detectado em $fileName. Offset do ZIP64 EOCD: $zip64EocdOffset")

                // Ler ZIP64 EOCD record (56 bytes)
                val zip64RecordResult = fetchRemoteRangeBytes(fileId, fileName, fileSize, zip64EocdOffset, zip64EocdOffset + 55, accountEmail)
                if (zip64RecordResult.isSuccess) {
                    val z64Bytes = zip64RecordResult.getOrThrow()
                    val bbZ64 = ByteBuffer.wrap(z64Bytes).order(ByteOrder.LITTLE_ENDIAN)
                    if (bbZ64.int == 0x06064b50) { // PK\x06\x06
                        bbZ64.position(40)
                        cdSize = bbZ64.long
                        cdOffset = bbZ64.long
                        Log.d(TAG, "ZIP64 Central Directory: offset=$cdOffset, size=$cdSize")
                    }
                }
            }
        }

        if (cdOffset <= 0L || cdSize <= 0L || (cdOffset + cdSize) > fileSize) {
            return@withContext Result.failure(IllegalStateException("Offsets do Central Directory inválidos no ZIP: offset=$cdOffset, size=$cdSize, total=$fileSize"))
        }

        // 5/6. Percorrer o Diretório Central em STREAMING (memória constante), parando ao achar manifest.json
        onProgress?.invoke("Lendo Diretório Central ($cdSize bytes)...", 0.70f)
        val cdEntryRes = findManifestInRemoteCentralDirectory(fileId, cdOffset, cdSize, accountEmail) { frac ->
            onProgress?.invoke("Localizando manifest.json no índice...", 0.70f + 0.15f * frac)
        }
        if (cdEntryRes.isFailure) {
            return@withContext Result.failure(cdEntryRes.exceptionOrNull() ?: IOException("Falha ao ler Diretório Central."))
        }
        val cdEntry = cdEntryRes.getOrThrow()
            ?: return@withContext Result.failure(IllegalStateException("'manifest.json' não encontrado no container ZIP do Drive."))

        val manifestLocalHeaderOffset = cdEntry.localHeaderOffset
        val manifestCompressedSize = cdEntry.compSize
        val manifestCompressionMethod = cdEntry.method
        if (manifestCompressedSize <= 0L || manifestCompressedSize > MAX_RANGE_BYTES) {
            return@withContext Result.failure(IllegalStateException("Tamanho inválido de manifest.json no container: $manifestCompressedSize bytes"))
        }

        // 7. Ler Local File Header de manifest.json para saber o tamanho exato dos campos variáveis
        onProgress?.invoke("Baixando manifest.json...", 0.90f)
        val localHeaderReadSize = 30 + 512L // 30 bytes fixos + até 512 bytes de nome e extra
        val localHeaderRes = fetchRemoteRangeBytes(fileId, fileName, fileSize, manifestLocalHeaderOffset, manifestLocalHeaderOffset + localHeaderReadSize, accountEmail)
        if (localHeaderRes.isFailure) {
            return@withContext Result.failure(localHeaderRes.exceptionOrNull() ?: IOException("Falha ao ler cabeçalho local de manifest.json"))
        }
        val localBytes = localHeaderRes.getOrThrow()
        val bbLoc = ByteBuffer.wrap(localBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bbLoc.int != 0x04034b50) { // PK\x03\x04
            return@withContext Result.failure(IllegalStateException("Assinatura de cabeçalho local inválida para manifest.json no container."))
        }
        bbLoc.position(26)
        val locNameLen = bbLoc.short.toInt() and 0xFFFF
        val locExtraLen = bbLoc.short.toInt() and 0xFFFF
        val dataOffset = manifestLocalHeaderOffset + 30 + locNameLen + locExtraLen

        // 8. Baixar os bytes compactados de manifest.json
        val manifestDataRes = fetchRemoteRangeBytes(fileId, fileName, fileSize, dataOffset, dataOffset + manifestCompressedSize - 1, accountEmail)
        if (manifestDataRes.isFailure) {
            return@withContext Result.failure(manifestDataRes.exceptionOrNull() ?: IOException("Falha ao baixar dados de manifest.json."))
        }
        val manifestBytes = manifestDataRes.getOrThrow()

        // 9. Descompactar se DEFLATED ou usar bytes se STORED
        val jsonString = when (manifestCompressionMethod) {
            0 -> String(manifestBytes, Charsets.UTF_8) // STORED
            8 -> { // DEFLATED
                val inflater = Inflater(true) // nowrap = true para streams ZIP raw deflate
                InflaterInputStream(ByteArrayInputStream(manifestBytes), inflater).bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }
            }
            else -> throw IllegalStateException("Método de compressão não suportado para manifest.json: $manifestCompressionMethod")
        }

        val parsedManifest = gson.fromJson(jsonString, BackupManifest::class.java)
        Log.i(TAG, "manifest.json extraído com sucesso do container remoto via Range! (${parsedManifest.items.size} itens).")
        Result.success(parsedManifest)
    }

    // =========================================================================
    // 6. REQUISIÇÕES HTTP RANGE COM RETRY, BACKOFF E DIAGNÓSTICO ESTRITO
    // =========================================================================

    /**
     * Executa requisição HTTP Range com os requisitos estritos:
     * - supportsAllDrives=true e alt=media
     * - Loga RESTORE_INDEX com código, Content-Type, Content-Range e 16 primeiros bytes em hex/ASCII
     * - Trata resposta != 200/206 ou text/html ou application/json como erro da API
     * - Em 401 renova token e faz retry
     * - Em 429/5xx faz retry com backoff exponencial
     * - Se vier 200 (Range ignorado), NÃO aceita o corpo como trecho pedido
     */
    suspend fun fetchRemoteRangeBytes(
        fileId: String,
        fileName: String,
        expectedTotalSize: Long,
        startByte: Long,
        endByte: Long,
        accountEmail: String?
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val maxAttempts = 5
        val expectedLen = endByte - startByte + 1
        if (expectedLen <= 0L || expectedLen > MAX_RANGE_BYTES) {
            return@withContext Result.failure(
                IllegalArgumentException("Range de $expectedLen bytes excede o limite seguro de $MAX_RANGE_BYTES bytes para leitura em memória.")
            )
        }
        var currentToken = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado", 401))

        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                val delayMs = 1000L * (1L shl (attempt - 2))
                Log.w(TAG, "Tentativa $attempt/$maxAttempts para Range bytes=$startByte-$endByte em $fileName após ${delayMs}ms...")
                delay(delayMs)
            }

            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $currentToken")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Range", "bytes=$startByte-$endByte")
                    connectTimeout = 15000
                    readTimeout = 25000
                }

                val code = conn.responseCode
                val contentType = conn.contentType ?: ""
                val contentRange = conn.getHeaderField("Content-Range") ?: "N/A"

                // Em 401: renovar token e tentar novamente
                if (code == 401) {
                    conn.disconnect()
                    val refreshed = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
                    if (refreshed.isSuccess) {
                        currentToken = refreshed.getOrThrow()
                        continue
                    }
                    return@withContext Result.failure(GoogleDriveSessionExpiredException("Sessão expirada no Google Drive (HTTP 401)", 401))
                }

                // Em 429 ou 5xx: retry com backoff exponencial
                if (code == 429 || code in 500..599) {
                    conn.disconnect()
                    lastException = GoogleDriveApiException(code, "Google Drive retornou HTTP $code (Rate limit / Servidor)")
                    continue
                }

                // Ler corpo da resposta ou stream de erro
                val stream: InputStream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val maxBody = if (code in 200..299) expectedLen.toInt() else MAX_ERROR_BODY
                val bodyBytes = stream.use { it.readAtMost(maxBody) }

                // Item A do diagnóstico: Registrar log RESTORE_INDEX obrigatório
                logRestoreIndexDiagnostic(fileId, fileName, expectedTotalSize, code, contentType, contentRange, bodyBytes)

                // Item B do diagnóstico: Tratar erro que não seja 200/206 ou que venha como text/html ou application/json
                val isHtmlOrJson = contentType.contains("text/html", ignoreCase = true) || contentType.contains("application/json", ignoreCase = true)
                if (code !in listOf(200, 206) || isHtmlOrJson) {
                    val errorDetail = String(bodyBytes.take(500).toByteArray(), Charsets.UTF_8).trim()
                    return@withContext Result.failure(
                        GoogleDriveApiException(code, "Erro na API do Google Drive (HTTP $code, Tipo: $contentType): $errorDetail")
                    )
                }

                // Item D do diagnóstico: Ao ler por Range, conferir se a resposta é 206 com Content-Range pedido.
                // Se vier 200 (Range ignorado), NÃO usar o corpo como o trecho pedido!
                if (code == 200) {
                    return@withContext Result.failure(
                        IllegalStateException("Google Drive ignorou o cabeçalho Range (HTTP 200 retornado em vez de 206 Partial Content). Não é seguro usar o corpo como trecho do container.")
                    )
                }

                // Validação do cabeçalho de container: SVB1 ou PK\x03\x04 APENAS se o trecho começar no byte 0
                if (startByte == 0L && bodyBytes.size >= 4) {
                    val isSvb = bodyBytes[0] == SVB_MAGIC[0] && bodyBytes[1] == SVB_MAGIC[1] && bodyBytes[2] == SVB_MAGIC[2] && bodyBytes[3] == SVB_MAGIC[3]
                    val isZip = bodyBytes[0] == PK_LOCAL_HEADER_MAGIC[0] && bodyBytes[1] == PK_LOCAL_HEADER_MAGIC[1] && bodyBytes[2] == PK_LOCAL_HEADER_MAGIC[2] && bodyBytes[3] == PK_LOCAL_HEADER_MAGIC[3]
                    if (!isSvb && !isZip) {
                        val hex16 = bodyBytes.take(16).joinToString(" ") { "%02X".format(it) }
                        val ascii16 = String(bodyBytes.take(16).toByteArray(), Charsets.US_ASCII).replace(Regex("[^\\x20-\\x7E]"), ".")
                        return@withContext Result.failure(
                            IllegalStateException("Cabeçalho de container inválido recebido do Google Drive. Esperado SVB1 ou PK\\x03\\x04. (Recebido: $hex16 | '$ascii16')")
                        )
                    }
                }

                return@withContext Result.success(bodyBytes)
            } catch (e: Exception) {
                lastException = e
            } finally {
                conn?.disconnect()
            }
        }

        Result.failure(lastException ?: IOException("Falha na requisição Range após $maxAttempts tentativas."))
    }

    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 8192))
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private data class CdEntry(
        val localHeaderOffset: Long,
        val compSize: Long,
        val uncompSize: Long,
        val method: Int
    )

    private fun java.io.DataInputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val n = skipBytes(minOf(left, Int.MAX_VALUE.toLong()).toInt())
            if (n <= 0) { readByte(); left-- } else left -= n
        }
    }

    /**
     * Lê o Diretório Central via Range em STREAMING (sem carregar tudo em memória).
     * Retorna a entrada de manifest.json ou null se não existir.
     */
    private suspend fun findManifestInRemoteCentralDirectory(
        fileId: String,
        cdOffset: Long,
        cdSize: Long,
        accountEmail: String?,
        onFraction: ((Float) -> Unit)? = null
    ): Result<CdEntry?> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado", 401))
        var conn: HttpURLConnection? = null
        try {
            var refreshed = false
            while (true) {
                val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Range", "bytes=$cdOffset-${cdOffset + cdSize - 1}")
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                val code = conn.responseCode
                if (code == 401 && !refreshed) {
                    conn.disconnect()
                    val r = oAuthManager.forceRefreshToken(accountEmail = accountEmail)
                    if (r.isFailure) return@withContext Result.failure(GoogleDriveSessionExpiredException("Sessão expirada no Google Drive (HTTP 401)", 401))
                    token = r.getOrThrow()
                    refreshed = true
                    continue
                }
                if (code != 206) {
                    return@withContext Result.failure(
                        GoogleDriveApiException(code, "Leitura do Diretório Central exige HTTP 206 (recebido $code)")
                    )
                }
                break
            }

            val input = java.io.DataInputStream(java.io.BufferedInputStream(conn!!.inputStream, 64 * 1024))
            val fixed = ByteArray(46)
            var consumed = 0L
            var lastReport = 0L

            while (consumed + 46 <= cdSize) {
                input.readFully(fixed)
                val bb = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN)
                if (bb.getInt(0) != 0x02014b50) break // fim do diretório central
                val method = bb.getShort(10).toInt() and 0xFFFF
                var compSize = bb.getInt(20).toLong() and 0xFFFFFFFFL
                var uncompSize = bb.getInt(24).toLong() and 0xFFFFFFFFL
                val nameLen = bb.getShort(28).toInt() and 0xFFFF
                val extraLen = bb.getShort(30).toInt() and 0xFFFF
                val commentLen = bb.getShort(32).toInt() and 0xFFFF
                var localOffset = bb.getInt(42).toLong() and 0xFFFFFFFFL

                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val extra = ByteArray(extraLen)
                if (extraLen > 0) input.readFully(extra)
                if (commentLen > 0) input.skipFully(commentLen.toLong())
                consumed += 46L + nameLen + extraLen + commentLen

                val entryName = String(nameBytes, Charsets.UTF_8)
                if (entryName == "manifest.json" || entryName.endsWith("/manifest.json")) {
                    if (extraLen > 0) {
                        val eb = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
                        while (eb.remaining() >= 4) {
                            val headerId = eb.short.toInt() and 0xFFFF
                            val dataSize = eb.short.toInt() and 0xFFFF
                            val next = eb.position() + dataSize
                            if (headerId == 0x0001) {
                                if (uncompSize == 0xFFFFFFFFL && eb.remaining() >= 8) uncompSize = eb.long
                                if (compSize == 0xFFFFFFFFL && eb.remaining() >= 8) compSize = eb.long
                                if (localOffset == 0xFFFFFFFFL && eb.remaining() >= 8) localOffset = eb.long
                            }
                            if (next > eb.limit()) break
                            eb.position(next)
                        }
                    }
                    return@withContext Result.success(CdEntry(localOffset, compSize, uncompSize, method))
                }

                if (consumed - lastReport >= 4L * 1024 * 1024) {
                    lastReport = consumed
                    onFraction?.invoke((consumed.toFloat() / cdSize.toFloat()).coerceIn(0f, 1f))
                }
            }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun logRestoreIndexDiagnostic(
        fileId: String,
        name: String,
        expectedSize: Long,
        httpCode: Int,
        contentType: String,
        contentRange: String,
        bytes: ByteArray
    ) {
        val bytesCount = bytes.size
        val sampleSize = minOf(bytesCount, 16)
        val hex16 = if (sampleSize > 0) bytes.take(sampleSize).joinToString(" ") { "%02X".format(it) } else "VAZIO"
        val ascii16 = if (sampleSize > 0) String(bytes.take(sampleSize).toByteArray(), Charsets.US_ASCII).replace(Regex("[^\\x20-\\x7E]"), ".") else "VAZIO"

        val diag = "[DIAGNÓSTICO] fileId=$fileId | nome='$name' | tamEsperado=$expectedSize bytes | HTTP=$httpCode | Content-Type='$contentType' | Content-Range='$contentRange' | bytesRecebidos=$bytesCount | primeiros16Bytes: [Hex: $hex16] [ASCII: '$ascii16']"
        Log.i(TAG, diag)
        dbHelper.insertLog(if (httpCode in 200..299) LogLevel.INFO else LogLevel.WARN, TAG, diag)
    }

    // =========================================================================
    // 7. UPLOAD DE SIDECAR ASSÍNCRONO EM SEGUNDO PLANO
    // =========================================================================

    private fun uploadSidecarToDriveAsync(manifest: BackupManifest, containerMeta: DriveFileMetadata, accountEmail: String?) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val baseName = containerMeta.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
                val sidecarName = "$baseName.index.json"
                val jsonString = gson.toJson(manifest)

                // Enviar sidecar
                val uploadRes = driveFileManager.uploadIndexJson(
                    name = sidecarName,
                    content = jsonString,
                    parentId = null,
                    accountEmail = accountEmail
                )
                if (uploadRes.isSuccess) {
                    Log.i(TAG, "Sidecar criado e enviado com sucesso ao Drive: $sidecarName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Aviso ao enviar sidecar ao Drive: ${e.message}")
            }
        }
    }

    private fun fetchDriveFileMetadata(fileId: String, token: String): Result<DriveFileMetadata> {
        return try {
            val fields = URLEncoder.encode("id,name,size,createdTime,modifiedTime,md5Checksum,mimeType,appProperties", "UTF-8")
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?fields=$fields&supportsAllDrives=true")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 12000
            conn.readTimeout = 12000

            val code = conn.responseCode
            if (code == 401) {
                return Result.failure(GoogleDriveSessionExpiredException("Sessão expirada", 401))
            }
            if (code !in 200..299) {
                return Result.failure(GoogleDriveApiException(code, "Falha ao obter metadados (HTTP $code)"))
            }

            val json = InputStreamReader(conn.inputStream, Charsets.UTF_8).use {
                gson.fromJson(it, JsonObject::class.java)
            }

            val meta = DriveFileMetadata(
                id = json.get("id").asString,
                name = json.get("name")?.asString ?: "",
                sizeBytes = json.get("size")?.asLong ?: 0L,
                createdTime = System.currentTimeMillis(),
                modifiedTime = System.currentTimeMillis(),
                md5Checksum = json.get("md5Checksum")?.asString,
                mimeType = json.get("mimeType")?.asString ?: "application/octet-stream"
            )
            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =========================================================================
    // 8. PRÉ-CARREGAMENTO PARALELO DE ÍNDICES
    // =========================================================================

    private val preloadSemaphore = Semaphore(3) // Concorrência máxima de 3 requisições simultâneas

    suspend fun preloadIndexes(records: List<BackupRecord>, activeEmail: String?) = withContext(Dispatchers.IO) {
        coroutineScope {
            val jobs = records.map { record ->
                async {
                    preloadSemaphore.withPermit {
                        try {
                            val cached = getCachedManifest(record.id, record.driveFileId, record.filePath)
                            if (cached != null) return@withPermit

                            val file = File(record.filePath)
                            if (file.exists() && file.length() > 0L) {
                                inspectLocalBackup(file, record.id)
                            } else if (!record.driveFileId.isNullOrBlank()) {
                                inspectDriveBackup(
                                    fileId = record.driveFileId,
                                    expectedName = record.title,
                                    expectedSizeBytes = record.sizeBytes,
                                    accountEmail = activeEmail
                                )
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Pré-carregamento ignorado para '${record.title}': ${e.message}")
                        } catch (e: OutOfMemoryError) {
                            Log.w(TAG, "Pré-carregamento abortado por falta de memória para '${record.title}'")
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
    }
}
