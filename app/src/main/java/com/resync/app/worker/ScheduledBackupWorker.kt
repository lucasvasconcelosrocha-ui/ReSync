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
import kotlinx.coroutines.flow.last
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
            val backupEngine = BackupEngine(appContext)

            val includeApps = activeSchedules.any { it.targetItems.contains("APPS") }
            val includeContacts = activeSchedules.any { it.targetItems.contains("CONTACTS") }
            val includeSms = activeSchedules.any { it.targetItems.contains("MESSAGES") }
            val includeCalls = activeSchedules.any { it.targetItems.contains("CALLS") }
            val includeWifi = activeSchedules.any { it.targetItems.contains("WIFI") }
            val includeWallpaper = activeSchedules.any { it.targetItems.contains("WALLPAPER") }
            val includeBluetooth = activeSchedules.any { it.targetItems.contains("BLUETOOTH") }
            val includeDns = activeSchedules.any { it.targetItems.contains("DNS") }

            val installedApps = com.resync.app.engine.InstalledAppsRepository.getInstance(appContext).appsFlow.value
            val specificPkgList = activeSchedules.flatMap { it.specificApps }.toSet()
            val appPairs = if (includeApps) {
                installedApps.filter { app ->
                    specificPkgList.isEmpty() || specificPkgList.contains(app.packageName)
                }.take(if (specificPkgList.isEmpty()) 20 else specificPkgList.size).map { app ->
                    val scheduleWithApp = activeSchedules.find { it.specificApps.contains(app.packageName) }
                    val mode = scheduleWithApp?.appBackupMode ?: "BOTH"
                    val opt = com.resync.app.data.model.AppSelectionOptions(
                        includeBaseApk = mode == "APK" || mode == "BOTH",
                        includeSplits = mode == "APK" || mode == "BOTH",
                        includeData = mode == "DATA" || mode == "BOTH",
                        includeInternalData = mode == "DATA" || mode == "BOTH",
                        includeExternalData = mode == "DATA" || mode == "BOTH"
                    )
                    Pair(app, opt)
                }
            } else emptyList()

            val backupTitle = if (activeSchedules.size == 1) activeSchedules.first().title else "Backup Agendado"
            val dateStr = SimpleDateFormat("HH:mm - dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            var lastProgress: com.resync.app.engine.BackupProgress? = null
            backupEngine.performSelectiveBackup(
                backupTitle = backupTitle,
                selectedApps = appPairs,
                includeSms = includeSms,
                includeCalls = includeCalls,
                includeContacts = includeContacts,
                includeDns = includeDns,
                includeWifi = includeWifi,
                includeWallpaper = includeWallpaper,
                includeBluetooth = includeBluetooth
            ).collect { step ->
                lastProgress = step
                com.resync.app.engine.OperationRepository.updateBackupOperation(
                    stage = step.stage,
                    currentItemName = step.currentItemName,
                    progressPercent = step.progressPercent,
                    bytesProcessed = step.bytesProcessed,
                    totalBytesEstimated = step.totalBytesEstimated,
                    isFinished = step.isFinished,
                    error = step.error,
                    currentItemIndex = step.currentItemIndex,
                    totalItems = step.totalItems
                )
            }

            val progress = lastProgress ?: com.resync.app.engine.BackupProgress(
                stage = "Concluído",
                currentItemName = backupTitle,
                progressPercent = 1.0f,
                isFinished = true
            )

            // Remove da lista de operações ativas
            com.resync.app.engine.OperationRepository.removeOperationByType(com.resync.app.engine.OperationType.BACKUP)

            if (progress.error != null) {
                dbHelper.insertLog(LogLevel.ERROR, "ScheduledBackup", "Falha no backup agendado: ${progress.error}")
                postNotification("ReSync: Falha no backup agendado", progress.error ?: "Erro desconhecido")
                activeSchedules.forEach {
                    scheduleManager.recordExecutionResult(it.id, "Erro: ${progress.error}")
                }
                Result.retry()
            } else {
                dbHelper.insertLog(LogLevel.INFO, "ScheduledBackup", "Backup agendado concluído com sucesso.")
                postNotification("ReSync: Backup agendado concluído", "Rotina de backup concluída com sucesso às $dateStr")
                activeSchedules.forEach {
                    scheduleManager.recordExecutionResult(it.id, "Sucesso ($dateStr)")
                }
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
