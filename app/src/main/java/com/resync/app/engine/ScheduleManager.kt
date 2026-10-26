package com.resync.app.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.receiver.ScheduledBackupAlarmReceiver
import com.resync.app.worker.ScheduledBackupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class BatteryRequirement(val label: String) {
    NONE("Nenhum"),
    CHARGING("Carregando"),
    BATTERY_ABOVE_20("Bateria acima de 20%"),
    BATTERY_ABOVE_50("Bateria acima de 50%")
}

data class BackupScheduleItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Agendamento Padrão",
    val targetItems: List<String> = listOf("MESSAGES", "CALLS", "WIFI"),
    val destination: String = "Dispositivo", // "Dispositivo", "Nuvem", "Ambos"
    val daysOfWeek: List<Int> = listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY),
    val time: String = "03:00",
    val isEnabled: Boolean = true,
    val lastRunTimestamp: Long = 0L,
    val lastRunStatus: String = "Nunca executado",
    val specificApps: List<String> = emptyList(),
    val appBackupMode: String = "BOTH", // "APK", "DATA", "BOTH"
    val selectedFolders: List<String> = emptyList()
)

data class ScheduleGlobalConfig(
    val isMasterEnabled: Boolean = false,
    val startTime: String = "03:00",
    val batteryRequirement: BatteryRequirement = BatteryRequirement.CHARGING
)

class ScheduleManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("resync_scheduled_backups", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _globalConfig = MutableStateFlow(loadGlobalConfig())
    val globalConfig: StateFlow<ScheduleGlobalConfig> = _globalConfig.asStateFlow()

    private val _schedules = MutableStateFlow(loadSchedules())
    val schedules: StateFlow<List<BackupScheduleItem>> = _schedules.asStateFlow()

    init {
        syncWorkManager()
    }

    companion object {
        private const val KEY_CONFIG = "schedule_config_json"
        private const val KEY_SCHEDULES = "schedules_list_json"
        private const val WORK_TAG_SCHEDULED = "resync_scheduled_backup_work"

        @Volatile
        private var instance: ScheduleManager? = null

        fun getInstance(context: Context): ScheduleManager {
            return instance ?: synchronized(this) {
                instance ?: ScheduleManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun loadGlobalConfig(): ScheduleGlobalConfig {
        val json = prefs.getString(KEY_CONFIG, null) ?: return ScheduleGlobalConfig()
        return try {
            gson.fromJson(json, ScheduleGlobalConfig::class.java) ?: ScheduleGlobalConfig()
        } catch (_: Exception) {
            ScheduleGlobalConfig()
        }
    }

    private fun loadSchedules(): List<BackupScheduleItem> {
        val json = prefs.getString(KEY_SCHEDULES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<BackupScheduleItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistConfig(config: ScheduleGlobalConfig) {
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
        _globalConfig.value = config
        syncWorkManager()
    }

    private fun persistSchedules(list: List<BackupScheduleItem>) {
        prefs.edit().putString(KEY_SCHEDULES, gson.toJson(list)).apply()
        _schedules.value = list
        syncWorkManager()
    }

    fun setMasterEnabled(enabled: Boolean) {
        val newConfig = _globalConfig.value.copy(isMasterEnabled = enabled)
        persistConfig(newConfig)
    }

    fun setStartTime(time: String) {
        val newConfig = _globalConfig.value.copy(startTime = time)
        persistConfig(newConfig)
    }

    fun setBatteryRequirement(req: BatteryRequirement) {
        val newConfig = _globalConfig.value.copy(batteryRequirement = req)
        persistConfig(newConfig)
    }

    fun saveSchedule(item: BackupScheduleItem) {
        val current = _schedules.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
        } else {
            current.add(0, item)
        }
        persistSchedules(current)
    }

    fun deleteSchedule(id: String) {
        val current = _schedules.value.filter { it.id != id }
        persistSchedules(current)
    }

    fun toggleSchedule(id: String, enabled: Boolean) {
        val current = _schedules.value.map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
        persistSchedules(current)
    }

    fun recordExecutionResult(id: String, status: String) {
        val now = System.currentTimeMillis()
        val current = _schedules.value.map {
            if (it.id == id) it.copy(lastRunTimestamp = now, lastRunStatus = status) else it
        }
        persistSchedules(current)
    }

    fun rescheduleAll() {
        syncWorkManager()
    }

    fun calculateNextRun(item: BackupScheduleItem): Long {
        val parts = item.time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val now = Calendar.getInstance()
        val candidate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var daysChecked = 0
        while (daysChecked < 8) {
            val dayOfWeek = candidate.get(Calendar.DAY_OF_WEEK)
            if (candidate.timeInMillis > now.timeInMillis && (item.daysOfWeek.isEmpty() || item.daysOfWeek.contains(dayOfWeek))) {
                return candidate.timeInMillis
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
            daysChecked++
        }
        return candidate.timeInMillis
    }

    fun runScheduleNow(scheduleId: String) {
        val workManager = WorkManager.getInstance(context)
        val inputData = Data.Builder()
            .putString("schedule_id", scheduleId)
            .build()

        val oneTimeWork = OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
            .setInputData(inputData)
            .addTag("MANUAL_RUN_NOW")
            .build()

        workManager.enqueue(oneTimeWork)

        val now = System.currentTimeMillis()
        val current = _schedules.value.map {
            if (it.id == scheduleId) it.copy(lastRunTimestamp = now, lastRunStatus = "Executando...") else it
        }
        persistSchedules(current)
    }

    fun runAllSchedulesNow() {
        val workManager = WorkManager.getInstance(context)
        val oneTimeWork = OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
            .addTag("MANUAL_RUN_NOW")
            .build()

        workManager.enqueue(oneTimeWork)

        val now = System.currentTimeMillis()
        val current = _schedules.value.map {
            it.copy(lastRunTimestamp = now, lastRunStatus = "Executando...")
        }
        persistSchedules(current)
    }

    private fun scheduleExactAlarm() {
        val config = _globalConfig.value
        val enabledSchedules = _schedules.value.filter { it.isEnabled }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, ScheduledBackupAlarmReceiver::class.java).apply {
            action = "com.resync.app.ACTION_SCHEDULED_BACKUP"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!config.isMasterEnabled || enabledSchedules.isEmpty()) {
            alarmManager.cancel(pendingIntent)
            return
        }

        val nextTriggerMs = enabledSchedules.minOfOrNull { calculateNextRun(it) } ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
            }
        } catch (_: Exception) {
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
            } catch (_: Exception) {}
        }
    }

    private fun syncWorkManager() {
        scheduleExactAlarm()

        val workManager = WorkManager.getInstance(context)
        val config = _globalConfig.value
        val hasActiveSchedules = _schedules.value.any { it.isEnabled }

        if (!config.isMasterEnabled || !hasActiveSchedules) {
            workManager.cancelAllWorkByTag(WORK_TAG_SCHEDULED)
            return
        }

        val constraintsBuilder = Constraints.Builder()
        when (config.batteryRequirement) {
            BatteryRequirement.CHARGING -> constraintsBuilder.setRequiresCharging(true)
            BatteryRequirement.BATTERY_ABOVE_20, BatteryRequirement.BATTERY_ABOVE_50 -> constraintsBuilder.setRequiresBatteryNotLow(true)
            BatteryRequirement.NONE -> {}
        }

        val periodicWork = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
            24, TimeUnit.HOURS,
            1, TimeUnit.HOURS
        )
            .setConstraints(constraintsBuilder.build())
            .addTag(WORK_TAG_SCHEDULED)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_TAG_SCHEDULED,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }
}
