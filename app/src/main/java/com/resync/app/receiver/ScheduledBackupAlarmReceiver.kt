package com.resync.app.receiver

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.BackupDestinationType
import com.resync.app.data.model.CloudProviderType
import com.resync.app.data.model.LogLevel
import com.resync.app.engine.BackupRepository
import com.resync.app.engine.BatteryRequirement
import com.resync.app.engine.InstalledAppsRepository
import com.resync.app.engine.ScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Exact Alarm Receiver for automated backups on Android and HyperOS/Xiaomi.
 * Wakes up device at the scheduled time, checks battery constraints, and triggers Foreground Service backup.
 */
class ScheduledBackupAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledAlarmReceiver"
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val scheduleManager = ScheduleManager.getInstance(context)
        val config = scheduleManager.globalConfig.value

        Log.d(TAG, "Alarme exato de backup disparado (masterEnabled=${config.isMasterEnabled})")

        if (!config.isMasterEnabled) {
            Log.d(TAG, "Agendamento desativado no interruptor geral. Ignorando.")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReSync:ScheduledBackupWakeLock")?.apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            val dbHelper = AppDatabaseHelper.getInstance(context)
            try {
                // 1. Validar restrições de bateria
                val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, batteryFilter)
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (scale > 0) (level * 100f / scale) else 100f

                val reqMet = when (config.batteryRequirement) {
                    BatteryRequirement.CHARGING -> isCharging
                    BatteryRequirement.BATTERY_ABOVE_20 -> batteryPct >= 20f
                    BatteryRequirement.BATTERY_ABOVE_50 -> batteryPct >= 50f
                    BatteryRequirement.NONE -> true
                }

                if (!reqMet) {
                    val msg = "Alarme de backup agendado ignorado por restrição de bateria: Requer ${config.batteryRequirement.label} (Atual: ${batteryPct.toInt()}%, carregando=$isCharging)"
                    Log.w(TAG, msg)
                    dbHelper.insertLog(LogLevel.WARN, "ScheduledBackup", msg)
                    scheduleManager.rescheduleAll()
                    return@launch
                }

                // 2. Localizar rotinas ativas para hoje
                val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                val activeSchedules = scheduleManager.schedules.value.filter {
                    it.isEnabled && (it.daysOfWeek.isEmpty() || it.daysOfWeek.contains(today))
                }

                if (activeSchedules.isEmpty()) {
                    Log.d(TAG, "Nenhuma rotina ativa para hoje ($today). Reagendando próximo disparo.")
                    scheduleManager.rescheduleAll()
                    return@launch
                }

                val installedApps = InstalledAppsRepository.getInstance(context).appsFlow.value
                val prefs = context.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)
                val activeEmail = prefs.getString("active_account_email", null)

                for (schedule in activeSchedules) {
                    scheduleManager.recordExecutionResult(schedule.id, "Executando via alarme exato...")
                    dbHelper.insertLog(LogLevel.INFO, "ScheduledBackup", "Executando rotina agendada: '${schedule.title}'")

                    val includeApps = schedule.targetItems.contains("APPS")
                    val appPairs = if (includeApps) {
                        val specificSet = schedule.specificApps.toSet()
                        val appsToBackup = if (specificSet.isEmpty()) installedApps else installedApps.filter { specificSet.contains(it.packageName) }
                        val mode = schedule.appBackupMode
                        appsToBackup.map { app ->
                            Pair(
                                app,
                                com.resync.app.data.model.AppSelectionOptions(
                                    includeBaseApk = mode == "APK" || mode == "BOTH",
                                    includeSplits = mode == "APK" || mode == "BOTH",
                                    includeData = mode == "DATA" || mode == "BOTH",
                                    includeInternalData = mode == "DATA" || mode == "BOTH",
                                    includeExternalData = mode == "DATA" || mode == "BOTH"
                                )
                            )
                        }
                    } else emptyList()

                    val destType = when (schedule.destination) {
                        "Nuvem" -> BackupDestinationType.SOMENTE_DRIVE
                        "Ambos" -> BackupDestinationType.INTERNA_E_DRIVE
                        else -> BackupDestinationType.SOMENTE_INTERNA
                    }

                    val cloudProvider = if (destType != BackupDestinationType.SOMENTE_INTERNA) {
                        CloudProviderType.GOOGLE_DRIVE
                    } else null

                    val destLabel = when (schedule.destination) {
                        "Nuvem" -> "Google Drive"
                        "Ambos" -> "Memória interna + Google Drive"
                        else -> "Memória interna"
                    }

                    BackupRepository.startBackup(
                        context = context.applicationContext,
                        title = schedule.title,
                        selectedApps = appPairs,
                        selectedFiles = schedule.selectedFolders,
                        includeSms = schedule.targetItems.contains("MESSAGES"),
                        includeCalls = schedule.targetItems.contains("CALLS"),
                        includeContacts = schedule.targetItems.contains("CONTACTS"),
                        includeDns = schedule.targetItems.contains("DNS"),
                        includeWallpaper = schedule.targetItems.contains("WALLPAPER"),
                        includeWallpaperHome = schedule.targetItems.contains("WALLPAPER"),
                        includeWallpaperLock = schedule.targetItems.contains("WALLPAPER"),
                        includeWifi = schedule.targetItems.contains("WIFI"),
                        includeBluetooth = schedule.targetItems.contains("BLUETOOTH"),
                        targetCloudProvider = cloudProvider,
                        targetCloudAccountEmail = activeEmail,
                        destinationLabel = destLabel,
                        destinationType = destType
                    )

                    // Aguardar conclusão da rotina antes de partir para a próxima
                    val finishProgress = BackupRepository.backupProgress.filterNotNull().first { it.isFinished }
                    val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
                    if (finishProgress.error != null) {
                        scheduleManager.recordExecutionResult(schedule.id, "Falha: ${finishProgress.error}")
                        dbHelper.insertLog(LogLevel.ERROR, "ScheduledBackup", "Falha na rotina '${schedule.title}': ${finishProgress.error}")
                    } else {
                        scheduleManager.recordExecutionResult(schedule.id, "Sucesso ($dateStr)")
                        dbHelper.insertLog(LogLevel.INFO, "ScheduledBackup", "Rotina '${schedule.title}' concluída com sucesso.")
                    }
                }

                // Reagendar próximo disparo exato
                scheduleManager.rescheduleAll()
            } catch (e: Exception) {
                Log.e(TAG, "Erro durante execução do alarme de backup: ${e.message}", e)
                dbHelper.insertLog(LogLevel.ERROR, "ScheduledBackup", "Exceção no alarme de backup: ${e.message}")
            } finally {
                try {
                    wakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }
}
