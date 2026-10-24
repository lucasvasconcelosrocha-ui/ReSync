package com.resync.app.data.preferences

import android.content.Context

enum class AppBackupDestinationType {
    INTERNAL,
    DRIVE,
    BOTH
}

/**
 * Persists granular backup destination and Drive target account per application package name.
 */
class AppBackupPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_backup_dest_prefs", Context.MODE_PRIVATE)

    fun getDestination(packageName: String): AppBackupDestinationType {
        val raw = prefs.getString("dest_$packageName", AppBackupDestinationType.INTERNAL.name)
        return try {
            AppBackupDestinationType.valueOf(raw ?: AppBackupDestinationType.INTERNAL.name)
        } catch (_: Exception) {
            AppBackupDestinationType.INTERNAL
        }
    }

    fun setDestination(packageName: String, dest: AppBackupDestinationType) {
        prefs.edit().putString("dest_$packageName", dest.name).apply()
    }

    fun getDriveAccount(packageName: String): String? {
        return prefs.getString("account_$packageName", null)
    }

    fun setDriveAccount(packageName: String, email: String?) {
        if (email.isNullOrBlank()) {
            prefs.edit().remove("account_$packageName").apply()
        } else {
            prefs.edit().putString("account_$packageName", email).apply()
        }
    }
}

