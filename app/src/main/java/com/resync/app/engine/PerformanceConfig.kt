package com.resync.app.engine

import android.os.Build
import android.os.PowerManager

/**
 * Global native performance configuration for backup, restore, and upload operations.
 * Always uses high-speed settings: 3 workers, 32 MB chunks, minimal thermal pauses.
 * Thermal control is fully automatic via PowerManager.OnThermalStatusChangedListener.
 * No user-facing mode selector — all optimization is internal and always active.
 */
object PerformanceConfig {

    // Legacy compatibility shim — mode is always treated as FAST internally.
    @Deprecated("Mode selection removed. Always runs at maximum safe speed.")
    @Volatile var mode: String = "FAST"

    /** Max parallel workers for backup/restore pipeline */
    val maxWorkers: Int get() = 3

    /** Upload chunk size in bytes (must be multiple of 256 KiB) — 32 MiB for high speed */
    val uploadChunkBytes: Int get() = 32 * 1024 * 1024

    /** I/O buffer size in bytes for streaming reads/writes */
    val ioBufferBytes: Int get() = 1024 * 1024

    /** Deflate compression level — 1 = fastest */
    val compressionLevel: Int = 1

    /** Minimum interval between notification/UI updates in ms (max 2x/s) */
    val notifThrottleMs: Long = 500L

    /** File extensions that should NOT be re-compressed */
    val noCompressExtensions: Set<String> = setOf(
        "apk", "zip", "gz", "gzip", "zst", "lz4", "br", "bz2", "xz",
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
        "mp4", "mp3", "m4a", "aac", "opus", "ogg", "flac", "wav",
        "avi", "mkv", "mov", "3gp", "pdf", "docx", "xlsx", "pptx"
    )

    /** Returns true if this file should skip compression */
    fun shouldSkipCompression(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in noCompressExtensions
    }

    /**
     * Automatic thermal pacing: adjusts delay based on thermal status.
     * MODERATE → brief slow-down; SEVERE → longer pause; recovers automatically.
     */
    fun thermalDelayMs(thermalStatus: Int): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
        return when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> 0L
            PowerManager.THERMAL_STATUS_MODERATE -> 100L
            PowerManager.THERMAL_STATUS_SEVERE -> 500L
            else -> 1000L
        }
    }

    /**
     * Automatic thermal worker limit: reduces parallel workers under heat.
     * MODERATE → reduce by 1; SEVERE/CRITICAL → 1 worker only.
     */
    fun thermalWorkerLimit(thermalStatus: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return maxWorkers
        return when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> maxWorkers
            PowerManager.THERMAL_STATUS_MODERATE -> (maxWorkers - 1).coerceAtLeast(1)
            else -> 1
        }
    }
}
