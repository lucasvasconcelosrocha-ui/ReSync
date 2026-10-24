package com.resync.app.cloud.gdrive

import com.resync.app.engine.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class IntegrityVerificationReport(
    val isVerified: Boolean,
    val failureReason: String? = null,
    val localSizeBytes: Long,
    val remoteSizeBytes: Long,
    val localMd5: String,
    val remoteMd5: String?,
    val manifestValidated: Boolean
)

class IntegrityManager {

    /**
     * Compute MD5 checksum of local file to cross-verify with Google Drive md5Checksum property
     */
    fun computeMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Complete 4-tier integrity verification between local .svb backup and Google Drive metadata
     */
    suspend fun verifyUploadIntegrity(
        localFile: File,
        driveMetadata: DriveFileMetadata,
        precomputedMd5: String? = null
    ): IntegrityVerificationReport = withContext(Dispatchers.IO) {
        val localSize = localFile.length()
        val remoteSize = driveMetadata.sizeBytes

        // 1. Size Verification
        if (localSize != remoteSize) {
            return@withContext IntegrityVerificationReport(
                isVerified = false,
                failureReason = "Inconsistência de tamanho: local possui $localSize bytes mas a nuvem registrou $remoteSize bytes.",
                localSizeBytes = localSize,
                remoteSizeBytes = remoteSize,
                localMd5 = "",
                remoteMd5 = driveMetadata.md5Checksum,
                manifestValidated = false
            )
        }

        // 2. MD5 Checksum Verification (Single pass: uses precomputed streaming hash if available)
        val localMd5 = if (!precomputedMd5.isNullOrBlank()) precomputedMd5 else computeMd5(localFile)
        val remoteMd5 = driveMetadata.md5Checksum

        if (remoteMd5 != null && !localMd5.equals(remoteMd5, ignoreCase = true)) {
            return@withContext IntegrityVerificationReport(
                isVerified = false,
                failureReason = "Falha de integridade MD5: Hash local ($localMd5) difere do hash da nuvem ($remoteMd5).",
                localSizeBytes = localSize,
                remoteSizeBytes = remoteSize,
                localMd5 = localMd5,
                remoteMd5 = remoteMd5,
                manifestValidated = false
            )
        }

        // 3. Manifest Validity Check
        val manifestOk = try {
            ArchiveEngine.readManifest(localFile)
            true
        } catch (e: Exception) {
            false
        }

        if (!manifestOk) {
            return@withContext IntegrityVerificationReport(
                isVerified = false,
                failureReason = "Container .svb local apresenta cabeçalho ou manifesto corrompido.",
                localSizeBytes = localSize,
                remoteSizeBytes = remoteSize,
                localMd5 = localMd5,
                remoteMd5 = remoteMd5,
                manifestValidated = false
            )
        }

        IntegrityVerificationReport(
            isVerified = true,
            failureReason = null,
            localSizeBytes = localSize,
            remoteSizeBytes = remoteSize,
            localMd5 = localMd5,
            remoteMd5 = remoteMd5,
            manifestValidated = true
        )
    }
}

