package com.resync.app.engine

import android.content.Context
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    val daysOfWeek: List<Int> = listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
    val time: String = "03:00",
    val isEnabled: Boolean = true,
    val lastRunTimestamp: Long = 0L,
    val lastRunStatus: String = "Nunca executado"
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

    fun runAllSchedulesNow() {
        val workManager = WorkManager.getInstance(context)
        val constraintsBuilder = Constraints.Builder()
        when (_globalConfig.value.batteryRequirement) {
            BatteryRequirement.CHARGING -> constraintsBuilder.setRequiresCharging(true)
            BatteryRequirement.BATTERY_ABOVE_20, BatteryRequirement.BATTERY_ABOVE_50 -> constraintsBuilder.setRequiresBatteryNotLow(true)
            BatteryRequirement.NONE -> {}
        }

        val oneTimeWork = OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
            .setConstraints(constraintsBuilder.build())
            .addTag("MANUAL_RUN_NOW")
            .build()

        workManager.enqueue(oneTimeWork)

        val now = System.currentTimeMillis()
        val current = _schedules.value.map {
            it.copy(lastRunTimestamp = now, lastRunStatus = "Executando em segundo plano...")
        }
        persistSchedules(current)
    }

    private fun syncWorkManager() {
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
