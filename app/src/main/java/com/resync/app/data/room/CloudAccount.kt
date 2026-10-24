package com.resync.app.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "room_cloud_accounts")
data class CloudAccount(
    @PrimaryKey
    val email: String,
    val name: String,
    val photoUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false,
    val quotaUsedBytes: Long = 0L,
    val quotaTotalBytes: Long = 0L,
    val lastSyncTimestamp: Long = 0L
)

