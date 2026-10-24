package com.resync.app.cloud.gdrive

import android.content.Context
import androidx.work.*
import com.resync.app.worker.CloudSyncWorker
import java.util.concurrent.TimeUnit

data class AutoSyncSettings(
    val isAutoSyncEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val requiresCharging: Boolean = true,
    val isDaily: Boolean = true, // Daily vs Weekly
    val syncImmediatelyAfterBackup: Boolean = false
)

class SyncManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("rodin_sync_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTO_SYNC = "auto_sync_enabled"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_DAILY = "daily_periodicity"
        private const val KEY_SYNC_IMMEDIATELY = "sync_immediately"
        const val WORK_TAG_AUTO_SYNC = "rodin_auto_cloud_sync"
    }

    fun getSettings(): AutoSyncSettings {
        return AutoSyncSettings(
            isAutoSyncEnabled = prefs.getBoolean(KEY_AUTO_SYNC, true),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
            requiresCharging = prefs.getBoolean(KEY_CHARGING_ONLY, true),
            isDaily = prefs.getBoolean(KEY_DAILY, true),
            syncImmediatelyAfterBackup = prefs.getBoolean(KEY_SYNC_IMMEDIATELY, false)
        )
    }

    fun updateSettings(settings: AutoSyncSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_SYNC, settings.isAutoSyncEnabled)
            .putBoolean(KEY_WIFI_ONLY, settings.wifiOnly)
            .putBoolean(KEY_CHARGING_ONLY, settings.requiresCharging)
            .putBoolean(KEY_DAILY, settings.isDaily)
            .putBoolean(KEY_SYNC_IMMEDIATELY, settings.syncImmediatelyAfterBackup)
            .apply()

        applyWorkManagerSchedule(settings)
    }

    /**
     * Schedules or cancels background synchronization based on user constraints
     */
    private fun applyWorkManagerSchedule(settings: AutoSyncSettings) {
        val workManager = WorkManager.getInstance(context)

        if (!settings.isAutoSyncEnabled) {
            workManager.cancelUniqueWork(WORK_TAG_AUTO_SYNC)
            return
        }

        val constraintsBuilder = Constraints.Builder()

        if (settings.wifiOnly) {
            constraintsBuilder.setRequiredNetworkType(NetworkType.UNMETERED)
        } else {
            constraintsBuilder.setRequiredNetworkType(NetworkType.CONNECTED)
        }

        if (settings.requiresCharging) {
            constraintsBuilder.setRequiresCharging(true)
        }

        val repeatIntervalHours = if (settings.isDaily) 24L else (24L * 7L)

        val workRequest = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            repeatIntervalHours, TimeUnit.HOURS
        )
            .setConstraints(constraintsBuilder.build())
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_TAG_AUTO_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
}

