package com.resync.app.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "operation_history")
data class OperationHistoryEntity(
    @PrimaryKey
    val id: String,
    val type: String, // "BACKUP" or "RESTORATION"
    val title: String,
    val destinationOrSource: String,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val totalDurationSeconds: Long,
    val activeTimeSeconds: Long,
    val idleTimeSeconds: Long,
    val totalBytes: Long,
    val totalItems: Int,
    val successCount: Int,
    val failedCount: Int,
    val averageSpeedMBps: Float,
    val peakSpeedMBps: Float,
    val networkTypeUsed: String,
    val resultStatus: String, // "SUCCESS", "SUCCESS_WITH_WARNINGS", "ERROR", "CANCELLED"
    val stagesBreakdownJson: String? = null,
    val errorDetailsJson: String? = null,
    val formattedDurationText: String
)
