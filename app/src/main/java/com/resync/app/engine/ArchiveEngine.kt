package com.resync.app.engine

import com.google.gson.Gson
import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.BackupManifest
import com.resync.app.data.model.BackupPartInfo
import com.resync.app.data.model.ManifestItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handles creation and unpacking of SwiftVault archives (.zip containers with Zip64 and legacy .svb files).
 * Features:
 * - Direct streaming with Zip64 and STORED entries (no recompression of pre-compressed data and tar archives).
 * - Chunked AES-256-GCM per-entry encryption keeping valid ZIP container structure.
 * - Multi-part file splitting (.zip.001, .zip.002) with continuous byte-level SeekableByteChannel.
 * - Seamless backward-compatible read and extraction of legacy SVB1 containers.
 */
object ArchiveEngine {

    val SVB_MAGIC = byteArrayOf(0x53, 0x56, 0x42, 0x01) // "SVB1"
    val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
    private val gson = Gson()
    // Buffer fixo de 1 MB para compactação/descompactação em streaming eficiente com baixo consumo de memória
    private const val BUFFER_SIZE = 1024 * 1024

    data class PackEntry(
        val entryName: String,
        val sourceFile: File? = null,
        val rawData: ByteArray? = null,
        val manifestItem: ManifestItem,
        val isStoreOnly: Boolean = false
    )

    /**
     * Creates a standard Zip container with Zip64 support, per-entry encryption,
     * and optional byte-level splitting (.zip.001, .zip.002, etc.).
     */
    suspend fun createZipContainer(
        outputFile: File,
        manifest: BackupManifest,
        entries: List<PackEntry>,
        encryptionPassword: String? = null,
        accountEmail: String? = null,
        splitSizeBytes: Long = 0L,
        onProgress: (suspend (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit)? = null,
        onItemProgress: (suspend (bytesWritten: Long, totalEstimatedBytes: Long, currentItemName: String, itemIndex: Int, totalItems: Int, itemType: BackupItemType) -> Unit)? = null,
        checkPauseAndCancel: (suspend () -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {}

        outputFile.parentFile?.mkdirs()
        val targetZipFile = if (splitSizeBytes > 0L) File(outputFile.parentFile, "${outputFile.name}.tmp") else outputFile
        if (targetZipFile.exists()) targetZipFile.delete()

        val totalBytesToPack = entries.sumOf { it.sourceFile?.length() ?: it.rawData?.size?.toLong() ?: 0L }
        var totalBytesWritten = 0L

        // 1. Stream Zip entries using Apache Commons Compress
        ZipArchiveOutputStream(targetZipFile).use { zos ->
            // Enable Zip64 automatically when required
            zos.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded)

            val totalEntriesCount = entries.size
            // Reutilizar o buffer de 1 MB para todas as entradas e evitar alocação excessiva no heap/GC
            val sharedBuffer = ByteArray(BUFFER_SIZE)

            for ((index, entry) in entries.withIndex()) {
                checkPauseAndCancel?.invoke()
                val itemName = entry.manifestItem.name
                val itemType = entry.manifestItem.type
                val itemIndex = index + 1
                onItemProgress?.invoke(totalBytesWritten, totalBytesToPack, itemName, itemIndex, totalEntriesCount, itemType)

                val zipEntry = ZipArchiveEntry(entry.entryName)
                zipEntry.time = System.currentTimeMillis()

                val isPreCompressed = HighSpeedStreamingEngine.shouldBypassCompression(entry.entryName)

                val isBypassedFromEncryption = entry.manifestItem.type == BackupItemType.APP_APK ||
                        entry.manifestItem.type == BackupItemType.WALLPAPER ||
                        entry.entryName.endsWith(".apk") ||
                        entry.entryName.startsWith("wallpapers/")
                val shouldEncrypt = !isBypassedFromEncryption || !encryptionPassword.isNullOrEmpty()

                val shouldStore = entry.isStoreOnly ||
                        entry.manifestItem.compressionMethod == "STORED" ||
                        isPreCompressed ||
                        shouldEncrypt

                if (shouldStore) {
                    zipEntry.method = ZipEntry.STORED
                } else {
                    zipEntry.method = ZipEntry.DEFLATED
                    // Nível 1 (BEST_SPEED) minimiza consumo de CPU durante compressão; nunca usar nível máximo
                    zos.setLevel(Deflater.BEST_SPEED)
                }

                zos.putArchiveEntry(zipEntry)

                val entryInputStream: InputStream = if (entry.rawData != null) {
                    ByteArrayInputStream(entry.rawData)
                } else if (entry.sourceFile != null && entry.sourceFile.exists()) {
                    FileInputStream(entry.sourceFile)
                } else {
                    ByteArrayInputStream(ByteArray(0))
                }

                entryInputStream.use { input ->
                    var lastProgressReportMs = 0L
                    if (shouldEncrypt) {
                        SecurityManager.encryptStreamChunked(input, zos, encryptionPassword, accountEmail) { written ->
                            totalBytesWritten += written
                            val now = System.currentTimeMillis()
                            if (now - lastProgressReportMs >= 250L || totalBytesWritten >= totalBytesToPack) {
                                lastProgressReportMs = now
                                checkPauseAndCancel?.let { kotlinx.coroutines.runBlocking { it.invoke() } }
                                onProgress?.let { kotlinx.coroutines.runBlocking { it.invoke(totalBytesWritten, totalBytesToPack) } }
                                onItemProgress?.let { kotlinx.coroutines.runBlocking { it.invoke(totalBytesWritten, totalBytesToPack, itemName, itemIndex, totalEntriesCount, itemType) } }
                            }
                        }
                    } else {
                        var read: Int
                        while (input.read(sharedBuffer).also { read = it } != -1) {
                            checkPauseAndCancel?.invoke()
                            zos.write(sharedBuffer, 0, read)
                            totalBytesWritten += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgressReportMs >= 250L || totalBytesWritten >= totalBytesToPack) {
                                lastProgressReportMs = now
                                onProgress?.invoke(totalBytesWritten, totalBytesToPack)
                                onItemProgress?.invoke(totalBytesWritten, totalBytesToPack, itemName, itemIndex, totalEntriesCount, itemType)
                            }
                        }
                    }
                }

                zos.closeArchiveEntry()
            }

            // 2. Write manifest.json at root of zip
            val manifestJson = gson.toJson(manifest)
            val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
            val manifestEntry = ZipArchiveEntry("manifest.json")
            manifestEntry.method = ZipEntry.DEFLATED
            zos.putArchiveEntry(manifestEntry)
            zos.write(manifestBytes)
            zos.closeArchiveEntry()

            zos.finish()
        }

        // 3. Handle splitting into .zip.001, .zip.002 if requested
        if (splitSizeBytes > 0L && targetZipFile.length() > splitSizeBytes) {
            val partInfos = splitFileIntoParts(targetZipFile, outputFile, splitSizeBytes)
            targetZipFile.delete()

            // Update parts in manifest if necessary by reading parts
            return@withContext partInfos.sumOf { it.sizeBytes }
        } else {
            if (targetZipFile != outputFile) {
                if (outputFile.exists()) outputFile.delete()
                if (!targetZipFile.renameTo(outputFile)) {
                    targetZipFile.copyTo(outputFile, overwrite = true)
                    targetZipFile.delete()
                }
            }
            return@withContext outputFile.length()
        }
    }

    private fun splitFileIntoParts(
        sourceFile: File,
        baseOutputFile: File,
        partSizeBytes: Long
    ): List<BackupPartInfo> {
        val parts = mutableListOf<BackupPartInfo>()
        val baseName = if (baseOutputFile.name.endsWith(".zip", ignoreCase = true)) {
            baseOutputFile.name
        } else {
            "${baseOutputFile.name}.zip"
        }

        val totalLength = sourceFile.length()
        var currentOffset = 0L
        var partIndex = 1
        val buffer = ByteArray(1024 * 1024) // 1MB buffer

        FileInputStream(sourceFile).use { fis ->
            while (currentOffset < totalLength) {
                val partFileName = String.format(java.util.Locale.US, "%s.%03d", baseName, partIndex)
                val partFile = File(baseOutputFile.parentFile, partFileName)
                val bytesToReadThisPart = minOf(partSizeBytes, totalLength - currentOffset)
                var bytesWrittenThisPart = 0L

                val digest = java.security.MessageDigest.getInstance("SHA-256")
                FileOutputStream(partFile).use { fos ->
                    while (bytesWrittenThisPart < bytesToReadThisPart) {
                        val toRead = minOf(buffer.size.toLong(), bytesToReadThisPart - bytesWrittenThisPart).toInt()
                        val r = fis.read(buffer, 0, toRead)
                        if (r == -1) break
                        fos.write(buffer, 0, r)
                        digest.update(buffer, 0, r)
                        bytesWrittenThisPart += r
                    }
                }

                val partSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                parts.add(
                    BackupPartInfo(
                        partIndex = partIndex,
                        fileName = partFileName,
                        sizeBytes = partFile.length(),
                        sha256Checksum = partSha256,
                        startByte = currentOffset,
                        endByte = currentOffset + bytesWrittenThisPart - 1
                    )
                )

                currentOffset += bytesWrittenThisPart
                partIndex++
            }
        }
        return parts
    }

    /**
     * Resolves all part files for a given archive file if split (.zip.001, etc.) or returns single file.
     */
    fun resolveArchiveFiles(primaryFile: File): List<File> {
        val name = primaryFile.name
        val parent = primaryFile.parentFile ?: return listOf(primaryFile)

        if (name.matches(Regex(".*\\.(zip|resync|svb)\\.\\d{3}$", RegexOption.IGNORE_CASE))) {
            val baseName = name.substringBeforeLast(".") + "."
            val parts = parent.listFiles { f -> f.name.startsWith(baseName, ignoreCase = true) }
            if (!parts.isNullOrEmpty()) {
                return parts.sortedBy { it.name }
            }
        }

        // Check if there are split parts for this archive
        val potentialParts = parent.listFiles { f -> f.name.startsWith("${name}.", ignoreCase = true) && f.name.matches(Regex(".*\\.(zip|resync|svb)\\.\\d{3}$", RegexOption.IGNORE_CASE)) }
        if (!potentialParts.isNullOrEmpty()) {
            return potentialParts.sortedBy { it.name }
        }

        return listOf(primaryFile)
    }

    /**
     * Reads BackupManifest from either a modern .zip archive (single or split) or legacy .svb container.
     */
    suspend fun readManifest(containerFile: File): BackupManifest = withContext(Dispatchers.IO) {
        val files = resolveArchiveFiles(containerFile)
        val firstFile = files.first()

        if (!firstFile.exists()) {
            throw FileNotFoundException("Arquivo de container não encontrado: ${firstFile.absolutePath}")
        }

        // Inspect first 32 bytes for precise diagnostics and format detection
        val headerBytes = ByteArray(32)
        val bytesRead = try {
            FileInputStream(firstFile).use { fis -> fis.read(headerBytes) }
        } catch (e: Exception) {
            throw IllegalArgumentException("Falha ao ler cabeçalho do arquivo: ${e.message}", e)
        }

        if (bytesRead < 4) {
            throw IllegalArgumentException("Arquivo vazio ou corrompido (apenas $bytesRead bytes lidos).")
        }

        val magic = headerBytes.copyOfRange(0, 4)
        val isSvbMagic = magic.contentEquals(SVB_MAGIC)
        val isZipHeader = (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) // "PK"

        // 1. Try reading as SVB1 if magic matches
        if (isSvbMagic) {
            try {
                return@withContext readLegacySvbManifest(firstFile)
            } catch (legacyEx: Exception) {
                // If legacy SVB manifest parser failed, check if it's an SVB header followed by a ZIP stream
                val svbOffset = try {
                    FileInputStream(firstFile).use { fis ->
                        fis.skip(4) // skip SVB1
                        val lenBytes = ByteArray(4)
                        fis.read(lenBytes)
                        val mLen = ByteBuffer.wrap(lenBytes).int
                        if (mLen in 1..(10 * 1024 * 1024)) (8L + mLen) else 0L
                    }
                } catch (_: Exception) { 0L }

                if (svbOffset > 0L) {
                    try {
                        return@withContext readZipManifest(files, svbOffset)
                    } catch (_: Exception) {}
                }

                // Fallback to standard ZIP at offset 0
                try {
                    return@withContext readZipManifest(files, 0L)
                } catch (_: Exception) {
                    throw legacyEx
                }
            }
        }

        // 2. Try reading as ZIP if PK header matches
        if (isZipHeader) {
            try {
                return@withContext readZipManifest(files)
            } catch (e: Exception) {
                // If ZIP fails, try SVB1 fallback
                try {
                    return@withContext readLegacySvbManifest(firstFile)
                } catch (_: Exception) {
                    throw e
                }
            }
        }

        // 3. Neither SVB1 nor PK recognized: prepare comprehensive diagnostic dump
        val hexDump = headerBytes.take(bytesRead.coerceAtLeast(0)).joinToString(" ") { "%02X".format(it) }
        val asciiStr = String(headerBytes, 0, bytesRead.coerceAtLeast(0), Charsets.US_ASCII).replace(Regex("[^\\x20-\\x7E]"), ".")

        if (asciiStr.startsWith("{") || asciiStr.contains("{")) {
            throw IllegalArgumentException("Arquivo inválido: o Google Drive retornou uma resposta JSON em vez do arquivo binário (Header: $hexDump | '$asciiStr'). Verifique a autenticação ou permissões.")
        } else if (asciiStr.startsWith("<") || asciiStr.contains("html", ignoreCase = true)) {
            throw IllegalArgumentException("Arquivo inválido: o Google Drive retornou uma página HTML em vez do arquivo (Header: $hexDump).")
        } else {
            throw IllegalArgumentException("Arquivo inválido: cabeçalho ReSync (.resync, .svb ou .zip) não reconhecido. (Header: $hexDump | '$asciiStr', tamanho=${firstFile.length()} bytes)")
        }
    }

    private fun readZipManifest(files: List<File>, startOffset: Long = 0L): BackupManifest {
        MultiPartSeekableByteChannel(files, startOffset).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                val manifestEntry = zipFile.getEntry("manifest.json")
                    ?: throw IllegalArgumentException("Arquivo inválido: 'manifest.json' não encontrado no container.")

                val jsonContent = zipFile.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                return gson.fromJson(jsonContent, BackupManifest::class.java)
            }
        }
    }

    private fun readLegacySvbManifest(containerFile: File): BackupManifest {
        FileInputStream(containerFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                val magic = ByteArray(4)
                val readMagic = bis.read(magic)
                if (readMagic != 4 || !magic.contentEquals(SVB_MAGIC)) {
                    val hexDump = magic.joinToString(" ") { "%02X".format(it) }
                    throw IllegalArgumentException("Arquivo inválido: cabeçalho SwiftVault (.svb) não reconhecido (Magic: $hexDump).")
                }

                val lenBytes = ByteArray(4)
                bis.read(lenBytes)
                val manifestLen = ByteBuffer.wrap(lenBytes).int

                if (manifestLen <= 0 || manifestLen > 10 * 1024 * 1024) {
                    throw IllegalArgumentException("Manifest corrompido ou de tamanho inválido ($manifestLen bytes).")
                }

                val manifestBytes = ByteArray(manifestLen)
                var totalRead = 0
                while (totalRead < manifestLen) {
                    val r = bis.read(manifestBytes, totalRead, manifestLen - totalRead)
                    if (r == -1) break
                    totalRead += r
                }

                val manifestJson = String(manifestBytes, Charsets.UTF_8)
                return gson.fromJson(manifestJson, BackupManifest::class.java)
            }
        }
    }

    /**
     * Lê diretamente os bytes de uma entrada específica de um container ZIP,
     * realizando descriptografia automática caso a entrada esteja criptografada.
     */
    fun readEntryBytes(
        containerFile: File,
        entryPath: String,
        password: String? = null,
        accountEmail: String? = null
    ): ByteArray? {
        return try {
            val files = resolveArchiveFiles(containerFile)
            val firstFile = files.firstOrNull() ?: return null
            if (!firstFile.exists() || firstFile.length() < 4L) return null
            val magic = ByteArray(4)
            FileInputStream(firstFile).use { it.read(magic) }
            if (magic.contentEquals(SVB_MAGIC)) return null
            val raw = MultiPartSeekableByteChannel(files).use { channel ->
                ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                    val entry = zipFile.getEntry(entryPath) ?: return@use null
                    zipFile.getInputStream(entry).use { it.readBytes() }
                }
            } ?: return null

            if (raw.size >= 4 && (
                (raw[0] == SecurityManager.RESY_MAGIC[0] && raw[1] == SecurityManager.RESY_MAGIC[1] && raw[2] == SecurityManager.RESY_MAGIC[2] && raw[3] == SecurityManager.RESY_MAGIC[3]) ||
                (raw[0] == SecurityManager.SVBE_MAGIC[0] && raw[1] == SecurityManager.SVBE_MAGIC[1] && raw[2] == SecurityManager.SVBE_MAGIC[2] && raw[3] == SecurityManager.SVBE_MAGIC[3])
            )) {
                val baos = java.io.ByteArrayOutputStream()
                SecurityManager.decryptStreamChunked(java.io.ByteArrayInputStream(raw), baos, password, accountEmail)
                baos.toByteArray()
            } else {
                raw
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Unpacks selected entries from modern .zip or legacy .svb container.
     */
    suspend fun unpackContainer(
        containerFile: File,
        targetOutputDir: File,
        manifest: BackupManifest? = null,
        selectedArchivePaths: Set<String>? = null,
        decryptionPassword: String? = null,
        accountEmail: String? = null,
        onProgress: (suspend (bytesRead: Long, totalEstimatedBytes: Long) -> Unit)? = null,
        onItemProgress: (suspend (bytesRead: Long, totalEstimatedBytes: Long, currentItemName: String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        targetOutputDir.mkdirs()
        val files = resolveArchiveFiles(containerFile)
        val firstFile = files.first()

        val magic = ByteArray(4)
        FileInputStream(firstFile).use { it.read(magic) }

        if (magic.contentEquals(SVB_MAGIC)) {
            // Legacy SVB1 Unpack
            unpackLegacySvb(firstFile, targetOutputDir, manifest, selectedArchivePaths, decryptionPassword) { readBytes, total ->
                onProgress?.invoke(readBytes, total)
            }
            return@withContext
        }

        // Modern Zip Container Unpack
        val activeManifest = manifest ?: readManifest(containerFile)
        val totalSize = activeManifest.totalSizeBytes
        var bytesRestored = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        MultiPartSeekableByteChannel(files).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "manifest.json" || entry.isDirectory) continue

                    val shouldExtract = selectedArchivePaths == null || selectedArchivePaths.any { path ->
                        entry.name == path ||
                        entry.name.startsWith(path.trimEnd('/') + "/") ||
                        path.startsWith(entry.name.trimEnd('/') + "/")
                    }
                    if (shouldExtract) {
                        val outFile = File(targetOutputDir, entry.name)
                        // Anti-Path-Traversal check
                        val destCanonical = targetOutputDir.canonicalPath
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(destCanonical + File.separator) && outCanonical != destCanonical) {
                            throw SecurityException("Tentativa de Path Traversal bloqueada: ${entry.name}")
                        }
                        onItemProgress?.invoke(bytesRestored, totalSize, entry.name)
                        outFile.parentFile?.mkdirs()

                        zipFile.getInputStream(entry).use { rawIn ->
                            BufferedInputStream(rawIn, BUFFER_SIZE).use { bis ->
                                FileOutputStream(outFile).use { fos ->
                                    if (SecurityManager.isChunkedEncryptedStream(bis)) {
                                        SecurityManager.decryptStreamChunked(bis, fos, decryptionPassword, accountEmail) { written ->
                                            bytesRestored += written
                                            kotlinx.coroutines.runBlocking {
                                                onProgress?.invoke(bytesRestored, totalSize)
                                                onItemProgress?.invoke(bytesRestored, totalSize, entry.name)
                                            }
                                        }
                                    } else {
                                        var read: Int
                                        while (bis.read(buffer).also { read = it } != -1) {
                                            fos.write(buffer, 0, read)
                                            bytesRestored += read
                                            onProgress?.invoke(bytesRestored, totalSize)
                                            onItemProgress?.invoke(bytesRestored, totalSize, entry.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun unpackLegacySvb(
        containerFile: File,
        targetOutputDir: File,
        manifest: BackupManifest?,
        selectedArchivePaths: Set<String>?,
        decryptionPassword: String?,
        onProgress: suspend (bytesRead: Long, totalEstimatedBytes: Long) -> Unit
    ) {
        FileInputStream(containerFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                // Skip Magic & Manifest
                val magic = ByteArray(4)
                bis.read(magic)
                val lenBytes = ByteArray(4)
                bis.read(lenBytes)
                val manifestLen = ByteBuffer.wrap(lenBytes).int
                bis.skip(manifestLen.toLong())

                val activeManifest = manifest ?: readLegacySvbManifest(containerFile)

                val payloadStream: InputStream = if (activeManifest.isEncrypted) {
                    SecurityManager.createDecryptingInputStream(bis, decryptionPassword)
                } else {
                    bis
                }

                val totalSize = activeManifest.totalSizeBytes
                var bytesRestored = 0L
                val buffer = ByteArray(BUFFER_SIZE)

                ZipInputStream(BufferedInputStream(payloadStream, BUFFER_SIZE)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (selectedArchivePaths == null || selectedArchivePaths.contains(entryName)) {
                            val outFile = File(targetOutputDir, entryName)
                            val destCanonical = targetOutputDir.canonicalPath
                            val outCanonical = outFile.canonicalPath
                            if (!outCanonical.startsWith(destCanonical + File.separator) && outCanonical != destCanonical) {
                                throw SecurityException("Tentativa de Path Traversal bloqueada: $entryName")
                            }

                            outFile.parentFile?.mkdirs()

                            FileOutputStream(outFile).use { fos ->
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                    bytesRestored += read
                                    onProgress(bytesRestored, totalSize)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }

    /**
     * Legacy SVB1 container creator preserved for backward-compatibility unit testing.
     */
    suspend fun createSvbContainer(
        outputFile: File,
        manifest: BackupManifest,
        entries: List<PackEntry>,
        encryptionPassword: String? = null,
        onProgress: suspend (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val totalBytesToPack = entries.sumOf { it.sourceFile?.length() ?: it.rawData?.size?.toLong() ?: 0L }
        var totalBytesWritten = 0L

        FileOutputStream(outputFile).use { fos ->
            BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                bos.write(SVB_MAGIC)

                val manifestJson = gson.toJson(manifest)
                val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
                val lengthBuffer = ByteBuffer.allocate(4).putInt(manifestBytes.size).array()
                bos.write(lengthBuffer)
                bos.write(manifestBytes)
                bos.flush()

                val targetStream: OutputStream = if (manifest.isEncrypted) {
                    val (cipherStream, _) = SecurityManager.createEncryptingOutputStream(bos, encryptionPassword)
                    cipherStream
                } else {
                    bos
                }

                ZipOutputStream(BufferedOutputStream(targetStream, BUFFER_SIZE)).use { zos ->
                    zos.setLevel(1)
                    val buffer = ByteArray(BUFFER_SIZE)

                    for (entry in entries) {
                        val zipEntry = ZipEntry(entry.entryName)
                        zipEntry.time = System.currentTimeMillis()
                        zos.putNextEntry(zipEntry)

                        if (entry.rawData != null) {
                            zos.write(entry.rawData)
                            totalBytesWritten += entry.rawData.size
                            onProgress(totalBytesWritten, totalBytesToPack)
                        } else if (entry.sourceFile != null && entry.sourceFile.exists()) {
                            if (entry.sourceFile.isFile) {
                                FileInputStream(entry.sourceFile).use { fis ->
                                    var read: Int
                                    while (fis.read(buffer).also { read = it } != -1) {
                                        zos.write(buffer, 0, read)
                                        totalBytesWritten += read
                                        onProgress(totalBytesWritten, totalBytesToPack)
                                    }
                                }
                            }
                        }
                        zos.closeEntry()
                    }
                    zos.finish()
                    zos.flush()
                }
            }
        }

        return@withContext outputFile.length()
    }
}

