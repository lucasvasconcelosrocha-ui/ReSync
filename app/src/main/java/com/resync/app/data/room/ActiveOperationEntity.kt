package com.resync.app.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "room_active_operations")
data class ActiveOperationEntity(
    @PrimaryKey
    val id: String,
    val type: String, // "BACKUP" or "RESTORATION"
    val destination: String,
    val stage: String,
    val currentItemName: String,
    val currentItemIndex: Int,
    val totalItems: Int,
    val progressPercent: Float,
    val bytesProcessed: Long,
    val totalBytesEstimated: Long,
    val status: String, // RUNNING, PAUSED, COMPLETED, ERROR, INTERRUPTED, CANCELLED
    val error: String? = null,
    val uploadSessionUri: String? = null,
    val speedBytesPerSec: Long = 0L,
    val compressBytesPerSec: Long = 0L,
    val backupParamsJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

