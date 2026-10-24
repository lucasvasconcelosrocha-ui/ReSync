package com.resync.app.updater

import java.io.File

/**
 * Metadata received from GitHub release version.json
 */
data class AppReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val apkName: String,
    val apkSize: Long,
    val sha256: String,
    val releaseNotes: String,
    val apkUrl: String
)

/**
 * UI State for the in-app self-updater
 */
sealed class UpdateState {
    object Idle : UpdateState()
    data class Checking(val isManual: Boolean) : UpdateState()
    data class Available(val releaseInfo: AppReleaseInfo) : UpdateState()
    data class UpToDate(val currentVersionName: String) : UpdateState()
    data class Downloading(
        val releaseInfo: AppReleaseInfo,
        val progress: Float,
        val speedMbPerSec: Double,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class Verifying(val releaseInfo: AppReleaseInfo, val step: String) : UpdateState()
    data class ReadyToInstall(
        val releaseInfo: AppReleaseInfo,
        val apkFile: File,
        val isRootAvailable: Boolean
    ) : UpdateState()
    data class Installing(val releaseInfo: AppReleaseInfo, val isRoot: Boolean) : UpdateState()
    data class BlockedByOperation(val reason: String) : UpdateState()
    data class Error(val message: String, val technicalCode: String? = null) : UpdateState()
}

