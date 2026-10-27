package com.resync.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resync.app.R
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.resync.app.engine.BackupEngine
import com.resync.app.engine.ScheduleManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import android.util.Log
import java.util.Calendar

/**
 * Worker executed periodically by WorkManager for scheduled automated backups.
 */
class ScheduledBackupWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val CHANNEL_ID = "scheduled_backup_channel"
        private const val NOTIFICATION_ID = 4001
    }

    override suspend fun doWork(): Result {
        val dbHelper = AppDatabaseHelper.getInstance(appContext)
        val scheduleManager = ScheduleManager.getInstance(appContext)
        val isManual = tags.contains("MANUAL_RUN_NOW")
        val config = scheduleManager.globalConfig.value

        Log.d("BackupOrigin", "ScheduledBackupWorker triggered: isManual=$isManual, masterEnabled=${config.isMasterEnabled}")

        if (!isManual && !config.isMasterEnabled) {
            Log.d("BackupOrigin", "ScheduledBackupWorker skipped: isMasterEnabled is false")
            return Result.success()
        }

        val targetScheduleId = inputData.getString("schedule_id")
        val activeSchedules = if (!targetScheduleId.isNullOrBlank()) {
            scheduleManager.schedules.value.filter { it.id == targetScheduleId }
        } else {
            scheduleManager.schedules.value.filter { it.isEnabled }
        }
        if (activeSchedules.isEmpty()) {
            Log.d("BackupOrigin", "ScheduledBackupWorker skipped: activeSchedules is empty")
            return Result.success()
        }

        if (!isManual) {
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_WEEK)
            val matchesToday = activeSchedules.any { it.daysOfWeek.contains(today) }
            if (!matchesToday) {
                Log.d("BackupOrigin", "ScheduledBackupWorker skipped: today ($today) does not match any scheduled days")
                return Result.success()
            }
        }

        dbHelper.insertLog(LogLevel.INFO, "ScheduledBackup", "Iniciando rotina agendada de backup automático...")
        postNotification("ReSync: Backup agendado", "Executando rotina de backup em segundo plano...")

        return try {
            val installedApps = com.resync.app.engine.InstalledAppsRepository.getInstance(appContext).appsFlow.value
            val prefs = appContext.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)
            val activeEmail = prefs.getString("active_account_email", null)

            var anyError: String? = null

            for (schedule in activeSchedules) {
                scheduleManager.recordExecutionResult(schedule.id, "Executando em segundo plano...")

                val includeApps = schedule.targetItems.contains("APPS")
                val appPairs = if (includeApps) {
                    val specificSet = schedule.specificApps.toSet()
                    val appsToBackup = if (specificSet.isEmpty()) installedApps else installedApps.filter { specificSet.contains(it.packageName) }
                    val mode = schedule.appBackupMode
                    appsToBackup.map { app ->
                        Pair(
                            app,
                            com.resync.app.data.model.AppSelectionOptions(
                                includeBaseApk = schedule.includeBaseApk && (mode == "APK" || mode == "BOTH"),
                                includeSplits = schedule.includeAppSplits && (mode == "APK" || mode == "BOTH"),
                                includeData = schedule.includeAppData && (mode == "DATA" || mode == "BOTH"),
                                includeInternalData = schedule.includeAppData && (mode == "DATA" || mode == "BOTH"),
                                includeExternalData = schedule.includeExternalData && (mode == "DATA" || mode == "BOTH"),
                                includeObb = schedule.includeObb,
                                includePermissions = schedule.includeAppPermissions
                            )
                        )
                    }
                } else emptyList()

                val destType = when (schedule.destination) {
                    "Nuvem" -> com.resync.app.data.model.BackupDestinationType.SOMENTE_DRIVE
                    "Ambos" -> com.resync.app.data.model.BackupDestinationType.INTERNA_E_DRIVE
                    else -> com.resync.app.data.model.BackupDestinationType.SOMENTE_INTERNA
                }

                val cloudProvider = if (destType != com.resync.app.data.model.BackupDestinationType.SOMENTE_INTERNA) {
                    com.resync.app.data.model.CloudProviderType.GOOGLE_DRIVE
                } else null

                val destLabel = when (schedule.destination) {
                    "Nuvem" -> "Google Drive"
                    "Ambos" -> "Memória interna + Google Drive"
                    else -> "Memória interna"
                }

                com.resync.app.engine.BackupRepository.startBackup(
                    context = appContext,
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

                val finishProgress = com.resync.app.engine.BackupRepository.backupProgress.filterNotNull().first { it.isFinished }
                val dateStr = SimpleDateFormat("HH:mm - dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                if (finishProgress.error != null) {
                    anyError = finishProgress.error
                    scheduleManager.recordExecutionResult(schedule.id, "Falha: ${finishProgress.error}")
                } else {
                    scheduleManager.recordExecutionResult(schedule.id, "Sucesso ($dateStr)")
                }
            }

            if (anyError != null) {
                dbHelper.insertLog(LogLevel.ERROR, "ScheduledBackup", "Falha no backup agendado: $anyError")
                postNotification("ReSync: Falha no backup agendado", anyError)
                Result.retry()
            } else {
                val dateStr = SimpleDateFormat("HH:mm - dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                dbHelper.insertLog(LogLevel.INFO, "ScheduledBackup", "Backup agendado concluído com sucesso.")
                postNotification("ReSync: Backup agendado concluído", "Rotina de backup concluída com sucesso às $dateStr")
                Result.success()
            }
        } catch (e: Exception) {
            com.resync.app.engine.OperationRepository.removeOperationByType(com.resync.app.engine.OperationType.BACKUP)
            dbHelper.insertLog(LogLevel.ERROR, "ScheduledBackup", "Exceção no worker de backup agendado: ${e.message}")
            postNotification("ReSync: Erro no backup agendado", e.message ?: "Exceção inesperada")
            Result.failure()
        }
    }

    private fun postNotification(title: String, message: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Backups Agendados",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificações de progresso e conclusão de backups agendados"
            }
            nm.createNotificationChannel(channel)
        }

        val appLogo = com.resync.app.util.HyperFocusNotificationHelper.getAppLogoBitmap(appContext)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appLogo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
