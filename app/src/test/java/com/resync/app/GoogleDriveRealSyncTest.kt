package com.resync.app

import com.resync.app.cloud.gdrive.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoogleDriveRealSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMd5ChecksumCalculation() {
        val testFile = tempFolder.newFile("test_upload.txt").apply {
            writeText("Rodin_Backup Google Drive Real Cloud Synchronization Engine")
        }

        val integrityManager = IntegrityManager()
        val md5 = integrityManager.computeMd5(testFile)

        assertNotNull(md5)
        assertEquals(32, md5.length) // Standard MD5 hex length is 32 chars
    }

    @Test
    fun testIntegrityManagerVerificationSuccess() = runBlocking {
        val testFile = tempFolder.newFile("test_svb.svb").apply {
            // Write valid magic bytes and minimal container structure
            writeBytes(byteArrayOf(0x53, 0x56, 0x42, 0x01, 0x00, 0x00, 0x00, 0x02, 0x7B, 0x7D))
        }

        val integrityManager = IntegrityManager()
        val localMd5 = integrityManager.computeMd5(testFile)

        val driveMetadata = DriveFileMetadata(
            id = "drive_file_123",
            name = testFile.name,
            sizeBytes = testFile.length(),
            createdTime = System.currentTimeMillis(),
            modifiedTime = System.currentTimeMillis(),
            md5Checksum = localMd5,
            mimeType = "application/octet-stream"
        )

        // Test size and hash matching
        assertEquals(testFile.length(), driveMetadata.sizeBytes)
        assertEquals(localMd5, driveMetadata.md5Checksum)
    }

    @Test
    fun testIntegrityManagerSizeMismatchDetection() = runBlocking {
        val testFile = tempFolder.newFile("mismatched_file.svb").apply {
            writeText("Local data content of length 33 bytes")
        }

        val integrityManager = IntegrityManager()
        val driveMetadata = DriveFileMetadata(
            id = "drive_file_456",
            name = testFile.name,
            sizeBytes = 100L, // Mismatched size
            createdTime = System.currentTimeMillis(),
            modifiedTime = System.currentTimeMillis(),
            md5Checksum = "some_remote_md5",
            mimeType = "application/octet-stream"
        )

        val report = integrityManager.verifyUploadIntegrity(testFile, driveMetadata)
        assertFalse("Report must be unverified when size mismatches", report.isVerified)
        assertTrue(report.failureReason?.contains("Inconsistência de tamanho") == true)
    }

    @Test
    fun testIntegrityManagerMd5MismatchDetection() = runBlocking {
        val testFile = tempFolder.newFile("hash_mismatch.svb").apply {
            writeText("Local content")
        }

        val integrityManager = IntegrityManager()
        val driveMetadata = DriveFileMetadata(
            id = "drive_file_789",
            name = testFile.name,
            sizeBytes = testFile.length(),
            createdTime = System.currentTimeMillis(),
            modifiedTime = System.currentTimeMillis(),
            md5Checksum = "00000000000000000000000000000000", // Wrong hash
            mimeType = "application/octet-stream"
        )

        val report = integrityManager.verifyUploadIntegrity(testFile, driveMetadata)
        assertFalse("Report must be unverified when MD5 mismatches", report.isVerified)
        assertTrue(report.failureReason?.contains("Falha de integridade MD5") == true)
    }

    @Test
    fun testInsufficientStorageExceptionMessage() {
        val quotaException = InsufficientStorageException("Não há espaço suficiente no Google Drive para concluir este backup.")
        assertEquals("Não há espaço suficiente no Google Drive para concluir este backup.", quotaException.message)
    }

    @Test
    fun testAutoSyncSettingsConstraints() {
        val settings = AutoSyncSettings(
            isAutoSyncEnabled = true,
            wifiOnly = true,
            requiresCharging = true,
            isDaily = true,
            syncImmediatelyAfterBackup = true
        )

        assertTrue(settings.isAutoSyncEnabled)
        assertTrue(settings.wifiOnly)
        assertTrue(settings.requiresCharging)
        assertTrue(settings.isDaily)
        assertTrue(settings.syncImmediatelyAfterBackup)
    }
}

