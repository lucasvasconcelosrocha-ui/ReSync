package com.resync.app.cloud

import com.resync.app.data.model.CloudAccount
import com.resync.app.data.model.CloudDiagnosticResult
import com.resync.app.data.model.CloudProviderType
import com.resync.app.data.model.CloudRemoteFile
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Interface defining contract for cloud storage adapters.
 * Each provider (Google Drive, OneDrive, S3, WebDAV, etc.) implements an isolated adapter.
 */
interface CloudStorageProvider {
    val providerType: CloudProviderType

    suspend fun connect(account: CloudAccount): Result<Boolean>
    suspend fun disconnect(): Result<Boolean>
    suspend fun getAccountInfo(): Result<CloudAccount>
    suspend fun listRemoteFiles(remoteDir: String = com.resync.app.data.model.BackupPaths.DRIVE_ROOT_FOLDER_NAME): Result<List<CloudRemoteFile>>

    /**
     * Upload with byte-level progress reporting and resumable chunk tracking
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        startOffsetBytes: Long = 0L,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit
    ): Result<String>

    /**
     * Download with byte-level progress reporting
     */
    suspend fun downloadFile(
        remotePath: String,
        destinationFile: File,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit
    ): Result<Unit>

    suspend fun deleteFile(remotePath: String): Result<Boolean>

    /**
     * Run the 9 individual diagnostic tests
     */
    fun runComprehensiveDiagnostic(): Flow<CloudDiagnosticResult>

    fun supportsResumable(): Boolean = true
}

