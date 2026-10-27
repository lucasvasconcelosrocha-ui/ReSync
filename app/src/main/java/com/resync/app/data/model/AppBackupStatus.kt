package com.resync.app.data.model

import com.resync.app.util.FormatUtils

/**
 * Model representing the detected backup status of an installed or recorded application.
 */
/**
 * Granular backup point for an application, whether from an individual backup
 * or inside a full backup package (.resync, .zip).
 */
data class AppBackupPoint(
    val backupId: String,
    val backupTitle: String,
    val timestamp: Long,
    val isLocal: Boolean,
    val isDrive: Boolean,
    val filePath: String = "",
    val driveFileId: String? = null,
    val containerSizeBytes: Long = 0L,
    val appSizeBytes: Long = 0L,
    val versionName: String? = null,
    val versionCode: Long = 0L,
    val hasApk: Boolean = true,
    val hasData: Boolean = false,
    val isFullPackage: Boolean = false,
    val itemIds: List<String> = emptyList()
)

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
                hasLocal && hasDrive -> "Local + Nuvem"
                hasLocal -> "Local"
                else -> "Nuvem"
            }

            return AppBackupStatus(
                packageName = packageName,
                hasBackup = true,
                latestTimestamp = latestTime,
                hasLocal = hasLocal,
                hasDrive = hasDrive,
                formattedText = "Backup: $dateFormatted • $location",
                badgeText = "Com backup"
            )
        }
    }
}
