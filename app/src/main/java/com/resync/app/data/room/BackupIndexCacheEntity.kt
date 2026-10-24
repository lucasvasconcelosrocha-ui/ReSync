package com.resync.app.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for caching backup index manifests in Room, allowing instant retrieval
 * on selective restore without redownloading or re-reading multi-gigabyte containers.
 */
@Entity(
    tableName = "room_backup_indexes",
    indices = [
        Index(value = ["driveFileId"]),
        Index(value = ["filePath"]),
        Index(value = ["backupTitle"])
    ]
)
data class BackupIndexCacheEntity(
    @PrimaryKey
    val backupId: String,
    val backupTitle: String,
    val filePath: String?,
    val driveFileId: String?,
    val sizeBytes: Long,
    val manifestGzipBase64: String, // Gzip-compressed manifest JSON encoded in Base64
    val itemCount: Int,
    val cachedAt: Long = System.currentTimeMillis(),
    val lastModified: Long = 0L
)
