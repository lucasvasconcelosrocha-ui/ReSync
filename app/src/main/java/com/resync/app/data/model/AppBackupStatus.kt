package com.resync.app.data.model

import com.resync.app.util.FormatUtils

/**
 * Model representing the detected backup status of an installed or recorded application.
 */
data class AppBackupStatus(
    val packageName: String,
    val hasBackup: Boolean = false,
    val latestTimestamp: Long = 0L,
    val hasLocal: Boolean = false,
    val hasDrive: Boolean = false,
    val formattedText: String = "Sem backup",
    val badgeText: String = "Sem backup"
) {
    companion object {
        fun none(packageName: String): AppBackupStatus {
            return AppBackupStatus(
                packageName = packageName,
                hasBackup = false,
                latestTimestamp = 0L,
                hasLocal = false,
                hasDrive = false,
                formattedText = "Sem backup",
                badgeText = "Sem backup"
            )
        }

        fun create(packageName: String, localTime: Long?, driveTime: Long?): AppBackupStatus {
            val hasLocal = localTime != null && localTime > 0L
            val hasDrive = driveTime != null && driveTime > 0L

            if (!hasLocal && !hasDrive) {
                return none(packageName)
            }

            val latestTime = maxOf(localTime ?: 0L, driveTime ?: 0L)
            val dateFormatted = FormatUtils.formatDate(latestTime)
            val location = when {
                hasLocal && hasDrive -> "Memória interna + Drive"
                hasLocal -> "Memória interna"
                else -> "Drive"
            }

            return AppBackupStatus(
                packageName = packageName,
                hasBackup = true,
                latestTimestamp = latestTime,
                hasLocal = hasLocal,
                hasDrive = hasDrive,
                formattedText = "Backup: $dateFormatted · $location",
                badgeText = "Com backup"
            )
        }
    }
}
