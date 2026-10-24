package com.resync.app

import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.BackupManifest
import com.resync.app.data.model.ManifestItem
import com.resync.app.engine.ArchiveEngine
import com.resync.app.engine.SecurityManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class BackupAndSecurityEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSha256ChecksumCalculation() {
        val testData = "SwiftVault_Enterprise_Integrity_Check_2026".toByteArray(Charsets.UTF_8)
        val hash1 = SecurityManager.computeSha256(testData)
        val hash2 = SecurityManager.computeSha256(ByteArrayInputStream(testData))

        assertEquals("Hashes should match", hash1, hash2)
        assertEquals(64, hash1.length) // SHA-256 hex length is 64 characters
    }

    @Test
    fun testPasswordKeyDerivationAndEncryption() {
        val password = "StrongPassword@2026".toCharArray()
        val salt = SecurityManager.generateRandomBytes(16)

        val key1 = SecurityManager.deriveKeyFromPassword(password, salt)
        val key2 = SecurityManager.deriveKeyFromPassword(password, salt)

        assertArrayEquals("Derived keys with same salt and password must match", key1.encoded, key2.encoded)

        // Test encryption and decryption stream
        val originalText = "Sensitive SMS and App Data content to be encrypted with AES-256-GCM"
        val outputStream = ByteArrayOutputStream()

        val (encryptStream, _) = SecurityManager.createEncryptingOutputStream(outputStream, "StrongPassword@2026")
        encryptStream.write(originalText.toByteArray(Charsets.UTF_8))
        encryptStream.close()

        val encryptedBytes = outputStream.toByteArray()
        assertTrue("Encrypted bytes should be longer than plaintext due to salt, IV and tag", encryptedBytes.size > originalText.length)

        // Decrypt
        val inputStream = ByteArrayInputStream(encryptedBytes)
        val decryptStream = SecurityManager.createDecryptingInputStream(inputStream, "StrongPassword@2026")
        val decryptedText = decryptStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testSvbArchiveCreationAndManifestRead() = runBlocking {
        val archiveFile = tempFolder.newFile("test_backup.svb")
        val sampleFile = tempFolder.newFile("sample.txt").apply { writeText("Hello SwiftVault") }

        val item = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.FILE,
            name = "sample.txt",
            originalPath = sampleFile.absolutePath,
            archiveEntryPath = "files/sample.txt",
            sizeBytes = sampleFile.length(),
            sha256Checksum = SecurityManager.computeSha256(sampleFile.readBytes())
        )

        val manifest = BackupManifest(
            backupId = "test_svb_01",
            title = "Teste de Backup Unitário",
            createdAt = System.currentTimeMillis(),
            isEncrypted = false,
            totalSizeBytes = sampleFile.length(),
            items = listOf(item),
            sha256PayloadHash = ""
        )

        val entries = listOf(
            ArchiveEngine.PackEntry("files/sample.txt", sourceFile = sampleFile, manifestItem = item)
        )

        val writtenSize = ArchiveEngine.createSvbContainer(
            outputFile = archiveFile,
            manifest = manifest,
            entries = entries
        ) { _, _ -> }

        assertTrue(writtenSize > 0)
        assertTrue(archiveFile.exists())

        // Read manifest back
        val readManifest = ArchiveEngine.readManifest(archiveFile)
        assertEquals("test_svb_01", readManifest.backupId)
        assertEquals("Teste de Backup Unitário", readManifest.title)
        assertEquals(1, readManifest.items.size)
        assertEquals("sample.txt", readManifest.items[0].name)
    }

    @Test
    fun testResyncArchiveCreationAndManifestRead() = runBlocking {
        val archiveFile = tempFolder.newFile("test_backup.resync")
        val sampleFile = tempFolder.newFile("sample_resync.txt").apply { writeText("Hello ReSync") }

        val item = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.FILE,
            name = "sample_resync.txt",
            originalPath = sampleFile.absolutePath,
            archiveEntryPath = "files/sample_resync.txt",
            sizeBytes = sampleFile.length(),
            sha256Checksum = SecurityManager.computeSha256(sampleFile.readBytes())
        )

        val manifest = BackupManifest(
            backupId = "resync_123456789",
            title = "Teste de Backup ReSync Unitário",
            createdAt = System.currentTimeMillis(),
            isEncrypted = false,
            totalSizeBytes = sampleFile.length(),
            items = listOf(item),
            sha256PayloadHash = ""
        )

        val entries = listOf(
            ArchiveEngine.PackEntry("files/sample_resync.txt", sourceFile = sampleFile, manifestItem = item)
        )

        val writtenSize = ArchiveEngine.createZipContainer(
            outputFile = archiveFile,
            manifest = manifest,
            entries = entries,
            onProgress = { _, _ -> }
        )

        assertTrue(writtenSize > 0)
        assertTrue(archiveFile.exists())

        // Read manifest back
        val readManifest = ArchiveEngine.readManifest(archiveFile)
        assertEquals("resync_123456789", readManifest.backupId)
        assertEquals("Teste de Backup ReSync Unitário", readManifest.title)
        assertEquals(1, readManifest.items.size)
        assertEquals("sample_resync.txt", readManifest.items[0].name)
    }

    @Test
    fun testCorruptedArchiveDetection() {
        val corruptedFile = tempFolder.newFile("corrupted.svb").apply {
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03)) // Invalid magic header
        }

        try {
            runBlocking { ArchiveEngine.readManifest(corruptedFile) }
            fail("Expected IllegalArgumentException for corrupted magic header")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("inválido") == true || e.message?.contains("não reconhecido") == true)
        }
    }

    @Test
    fun testNewZipContainerCreationAndUnpack() = runBlocking {
        val zipFile = tempFolder.newFile("backup_test.zip")
        val sampleFile = tempFolder.newFile("test_data.txt").apply { writeText("SwiftVault New Zip Architecture 2026") }

        val item = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.FILE,
            name = "test_data.txt",
            originalPath = sampleFile.absolutePath,
            archiveEntryPath = "files/test_data.txt",
            sizeBytes = sampleFile.length(),
            sha256Checksum = SecurityManager.computeSha256(sampleFile.readBytes()),
            compressionMethod = "DEFLATE"
        )

        val manifest = BackupManifest(
            formatIdentifier = "SwiftVault",
            manifestVersion = 2,
            vaultVersion = "2.0.0",
            backupId = "zip_test_01",
            title = "Teste de Contêiner Zip",
            createdAt = System.currentTimeMillis(),
            isEncrypted = false,
            totalSizeBytes = sampleFile.length(),
            items = listOf(item)
        )

        val entries = listOf(
            ArchiveEngine.PackEntry("files/test_data.txt", sourceFile = sampleFile, manifestItem = item)
        )

        val writtenSize = ArchiveEngine.createZipContainer(
            outputFile = zipFile,
            manifest = manifest,
            entries = entries,
            onProgress = { _, _ -> }
        )

        assertTrue(writtenSize > 0)
        assertTrue(zipFile.exists())

        // 1. Read manifest back
        val readManifest = ArchiveEngine.readManifest(zipFile)
        assertEquals("SwiftVault", readManifest.formatIdentifier)
        assertEquals(2, readManifest.manifestVersion)
        assertEquals("zip_test_01", readManifest.backupId)
        assertEquals(1, readManifest.items.size)

        // 2. Unpack container
        val unpackDir = tempFolder.newFolder("unpacked_zip")
        ArchiveEngine.unpackContainer(
            containerFile = zipFile,
            targetOutputDir = unpackDir,
            onProgress = { _, _ -> }
        )

        val restoredFile = File(unpackDir, "files/test_data.txt")
        assertTrue(restoredFile.exists())
        assertEquals("SwiftVault New Zip Architecture 2026", restoredFile.readText())
    }

    @Test
    fun testPerEntryChunkedEncryptionAndDecryption() = runBlocking {
        val zipFile = tempFolder.newFile("encrypted_backup.zip")
        val secretContent = "Confidential App Data and Authentication Token to be encrypted chunked"
        val sampleFile = tempFolder.newFile("secret.txt").apply { writeText(secretContent) }

        val item = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.APP_DATA,
            name = "dados.tar",
            originalPath = sampleFile.absolutePath,
            archiveEntryPath = "apps/com.example/dados.tar",
            sizeBytes = sampleFile.length(),
            sha256Checksum = SecurityManager.computeSha256(sampleFile.readBytes()),
            isEncrypted = true,
            compressionMethod = "STORED"
        )

        val manifest = BackupManifest(
            formatIdentifier = "SwiftVault",
            manifestVersion = 2,
            vaultVersion = "2.0.0",
            backupId = "enc_test_01",
            title = "Teste Criptografado por Entrada",
            createdAt = System.currentTimeMillis(),
            isEncrypted = true,
            totalSizeBytes = sampleFile.length(),
            items = listOf(item)
        )

        val entries = listOf(
            ArchiveEngine.PackEntry("apps/com.example/dados.tar", sourceFile = sampleFile, manifestItem = item, isStoreOnly = true)
        )

        ArchiveEngine.createZipContainer(
            outputFile = zipFile,
            manifest = manifest,
            entries = entries,
            encryptionPassword = "PasswordSecure@2026",
            onProgress = { _, _ -> }
        )

        // Manifest is readable without decrypting payload entries
        val readManifest = ArchiveEngine.readManifest(zipFile)
        assertTrue(readManifest.isEncrypted)
        assertEquals(1, readManifest.items.size)

        // Unpack with CORRECT password
        val unpackDirSuccess = tempFolder.newFolder("unpacked_success")
        ArchiveEngine.unpackContainer(
            containerFile = zipFile,
            targetOutputDir = unpackDirSuccess,
            decryptionPassword = "PasswordSecure@2026",
            onProgress = { _, _ -> }
        )

        val restoredFile = File(unpackDirSuccess, "apps/com.example/dados.tar")
        assertTrue(restoredFile.exists())
        assertEquals(secretContent, restoredFile.readText())

        // Unpack with WRONG password must fail with clear error
        val unpackDirFail = tempFolder.newFolder("unpacked_fail")
        try {
            ArchiveEngine.unpackContainer(
                containerFile = zipFile,
                targetOutputDir = unpackDirFail,
                decryptionPassword = "WrongPassword",
                onProgress = { _, _ -> }
            )
            fail("Expected exception for invalid decryption password")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Senha") == true || e.message?.contains("integridade") == true)
        }
    }

    @Test
    fun testMultiPartZipSplittingAndReading() = runBlocking {
        val baseZipFile = File(tempFolder.root, "split_backup.zip")
        val largeContent = "ChunkData_".repeat(200) // ~2000 bytes
        val sampleFile = tempFolder.newFile("payload.txt").apply { writeText(largeContent) }

        val item = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.FILE,
            name = "payload.txt",
            originalPath = sampleFile.absolutePath,
            archiveEntryPath = "files/payload.txt",
            sizeBytes = sampleFile.length(),
            sha256Checksum = SecurityManager.computeSha256(sampleFile.readBytes()),
            compressionMethod = "STORED"
        )

        val manifest = BackupManifest(
            formatIdentifier = "SwiftVault",
            manifestVersion = 2,
            vaultVersion = "2.0.0",
            backupId = "split_01",
            title = "Teste de Split Multi-part",
            createdAt = System.currentTimeMillis(),
            isEncrypted = false,
            totalSizeBytes = sampleFile.length(),
            items = listOf(item)
        )

        val entries = listOf(
            ArchiveEngine.PackEntry("files/payload.txt", sourceFile = sampleFile, manifestItem = item, isStoreOnly = true)
        )

        // Force splitting with small chunk size (512 bytes)
        val writtenTotal = ArchiveEngine.createZipContainer(
            outputFile = baseZipFile,
            manifest = manifest,
            entries = entries,
            splitSizeBytes = 512L,
            onProgress = { _, _ -> }
        )

        assertTrue(writtenTotal > 0)

        val part1 = File(tempFolder.root, "split_backup.zip.001")
        val part2 = File(tempFolder.root, "split_backup.zip.002")
        assertTrue("Primeira parte .zip.001 deve existir", part1.exists())
        assertTrue("Segunda parte .zip.002 deve existir", part2.exists())

        // Read manifest directly from split parts through MultiPartSeekableByteChannel
        val readManifest = ArchiveEngine.readManifest(part1)
        assertEquals("SwiftVault", readManifest.formatIdentifier)
        assertEquals("split_01", readManifest.backupId)

        // Unpack from split parts without joining on disk
        val unpackDir = tempFolder.newFolder("unpacked_split")
        ArchiveEngine.unpackContainer(
            containerFile = part1,
            targetOutputDir = unpackDir,
            onProgress = { _, _ -> }
        )

        val restoredFile = File(unpackDir, "files/payload.txt")
        assertTrue(restoredFile.exists())
        assertEquals(largeContent, restoredFile.readText())
    }
}

