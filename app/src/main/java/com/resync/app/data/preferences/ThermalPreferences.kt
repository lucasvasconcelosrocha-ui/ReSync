package com.resync.app.data.preferences

import android.content.Context

/**
 * Manages thermal protection thresholds and override settings.
 * Default:
 * - Reduce threads / insert short pauses at 40.0 °C (or THERMAL_STATUS_MODERATE).
 * - Pause at 43.0 °C (or THERMAL_STATUS_SEVERE).
 * - Resume when cooled down below 38.0 °C (or <= THERMAL_STATUS_MODERATE).
 * Optimization is fully automatic and native.
 */
class ThermalPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("swiftvault_thermal_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_WARN_TEMP = 40.0f
        const val DEFAULT_HIGH_TEMP = 43.0f
        const val DEFAULT_COOL_TEMP = 38.0f
        private const val KEY_WARN_TEMP = "thermal_warn_temp"
        private const val KEY_HIGH_TEMP = "thermal_high_temp"
        private const val KEY_COOL_TEMP = "thermal_cool_temp"
        private const val KEY_OVERRIDE_UNTIL = "thermal_override_until"
    }

    var warnTempThreshold: Float
        get() = prefs.getFloat(KEY_WARN_TEMP, DEFAULT_WARN_TEMP)
        set(value) = prefs.edit().putFloat(KEY_WARN_TEMP, value).apply()

    var highTempThreshold: Float
        get() = prefs.getFloat(KEY_HIGH_TEMP, DEFAULT_HIGH_TEMP)
        set(value) = prefs.edit().putFloat(KEY_HIGH_TEMP, value).apply()

    var coolTempThreshold: Float
        get() = prefs.getFloat(KEY_COOL_TEMP, DEFAULT_COOL_TEMP)
        set(value) = prefs.edit().putFloat(KEY_COOL_TEMP, value).apply()

    fun setOverrideForMinutes(minutes: Int = 10) {
        val until = System.currentTimeMillis() + (minutes * 60 * 1000L)
        prefs.edit().putLong(KEY_OVERRIDE_UNTIL, until).apply()
    }

    fun isOverrideActive(): Boolean {
        val until = prefs.getLong(KEY_OVERRIDE_UNTIL, 0L)
        return System.currentTimeMillis() < until
    }

    fun clearOverride() {
        prefs.edit().remove(KEY_OVERRIDE_UNTIL).apply()
    }
}

