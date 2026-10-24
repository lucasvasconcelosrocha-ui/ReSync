package com.resync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.resync.app.MainActivity
import com.resync.app.R
import com.resync.app.engine.RestoreProgress
import com.resync.app.engine.RestoreRepository
import com.resync.app.util.HyperFocusNotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground Service guaranteeing resilient execution of restore operations
 * under Android background execution limitations.
 * Observes RestoreRepository and provides progress and cancel actions in the notification.
 */
class RestoreForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = HyperFocusNotificationHelper.CHANNEL_OPERATIONS_ID
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val EXTRA_TITLE = "EXTRA_TITLE"

        var isServiceRunning: Boolean = false
            private set

        fun startService(context: Context, title: String) {
            try {
                isServiceRunning = true
                val intent = Intent(context, RestoreForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TITLE, title)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {}
        }

        fun stopService(context: Context) {
            try {
                isServiceRunning = false
                val intent = Intent(context, RestoreForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationManager: NotificationManager? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        notificationManager = getSystemService(NotificationManager::class.java)

        // Observe RestoreRepository progress
        serviceScope.launch {
            RestoreRepository.restoreProgress.collectLatest { progress ->
                if (progress != null) {
                    updateNotificationFromProgress(progress)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        val currentProgress = RestoreRepository.restoreProgress.value
        val pct = currentProgress?.let { (it.progressPercent * 100).toInt() } ?: 0
        val stage = currentProgress?.stage ?: "Desconhecido"
        val errorMsg = currentProgress?.error
        val isDone = currentProgress?.isFinished ?: true
        val reason = if (isDone) {
            if (errorMsg != null) "Término com erro: $errorMsg" else "Término normal com sucesso"
        } else {
            "Interrupção prematura da restauração em $pct% (estágio: '$stage'). Motivo provável: parada pelo sistema ou pressão de memória."
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = com.resync.app.data.database.AppDatabaseHelper.getInstance(this@RestoreForegroundService)
                val level = if (!isDone || errorMsg != null) com.resync.app.data.model.LogLevel.WARN else com.resync.app.data.model.LogLevel.INFO
                db.insertLog(level, "RestoreService", "Término do serviço registrado: $pct% | Motivo: $reason")
            } catch (_: Throwable) {}
        }
        releaseLocks()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        serviceScope.launch(Dispatchers.IO) {
            try {
                com.resync.app.data.database.AppDatabaseHelper.getInstance(this@RestoreForegroundService)
                    .insertLog(com.resync.app.data.model.LogLevel.INFO, "RestoreService", "onTaskRemoved: Aplicativo deslizado dos recentes. Restauração em primeiro plano mantida ativa.")
            } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        serviceScope.launch(Dispatchers.IO) {
            try {
                com.resync.app.data.database.AppDatabaseHelper.getInstance(this@RestoreForegroundService)
                    .insertLog(com.resync.app.data.model.LogLevel.INFO, "RestoreService", "onStartCommand iniciado (action=$action, startId=$startId)")
            } catch (_: Throwable) {}
        }

        when (action) {
            ACTION_START -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Restauração de Backup"
                val notification = buildInitialNotification(title)
                try {
                    HyperFocusNotificationHelper.logNotificationStatus(this, CHANNEL_ID, "RestoreForegroundService: BEFORE startForeground")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    HyperFocusNotificationHelper.logNotificationStatus(this, CHANNEL_ID, "RestoreForegroundService: AFTER startForeground")
                    android.util.Log.d("ReSyncNotif", "RestoreForegroundService startForeground chamado com sucesso no canal $CHANNEL_ID")
                } catch (t: Throwable) {
                    android.util.Log.e("ReSyncNotif", "Erro no startForeground da restauração: ${t.message}", t)
                }
                acquireLocks()
                return START_STICKY
            }
            ACTION_PAUSE -> {
                RestoreRepository.pauseRestore()
                return START_STICKY
            }
            ACTION_RESUME -> {
                RestoreRepository.resumeRestore()
                return START_STICKY
            }
            ACTION_CANCEL -> {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        com.resync.app.data.database.AppDatabaseHelper.getInstance(this@RestoreForegroundService)
                            .insertLog(com.resync.app.data.model.LogLevel.WARN, "RestoreService", "Restauração cancelada pelo usuário via notificação.")
                    } catch (_: Throwable) {}
                }
                RestoreRepository.cancelRestore()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Android 15+ (API 35): Tratamento de timeout para foregroundServiceType="dataSync".
     * Salva o progresso no Room e reinicia o serviço para estender a execução de restaurações grandes.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = com.resync.app.data.database.AppDatabaseHelper.getInstance(this@RestoreForegroundService)
                db.insertLog(
                    com.resync.app.data.model.LogLevel.WARN,
                    "RestoreService",
                    "onTimeout acionado pelo Android 15+ (startId=$startId, type=$fgsType). Salvando estado e renovando serviço."
                )
                val currentProgress = RestoreRepository.restoreProgress.value
                if (currentProgress != null && !currentProgress.isFinished) {
                    val roomDb = com.resync.app.data.room.AppRoomDatabase.getInstance(this@RestoreForegroundService)
                    val dao = roomDb.activeOperationDao()
                    val existing = dao.getOperationById(com.resync.app.engine.OperationRepository.RESTORE_OPERATION_ID)
                    if (existing != null) {
                        dao.insertOrUpdate(existing.copy(updatedAt = System.currentTimeMillis()))
                    }
                    startService(this@RestoreForegroundService, "Restauração de Backup em andamento")
                }
            } catch (_: Throwable) {}
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ReSync:RestoreWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ReSync:RestoreWifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReSync:RestoreWifiLock")
                }?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}

        serviceScope.launch(Dispatchers.IO) {
            try {
                com.topjohnwu.superuser.Shell.cmd(
                    "dumpsys deviceidle whitelist +com.resync.app",
                    "cmd appops set com.resync.app RUN_ANY_IN_BACKGROUND allow"
                ).exec()
            } catch (_: Throwable) {}
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun createNotificationChannel() {
        HyperFocusNotificationHelper.ensureNotificationChannels(this)
    }

    private var lastRestoreNotifTime = 0L

    private fun isScreenOn(): Boolean {
        return try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isInteractive ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun buildInitialNotification(title: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_ACTIVE_OPERATIONS", true)
        }
        val pOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, RestoreForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pCancel = PendingIntent.getService(
            this, 3, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actions = listOf(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar",
                pCancel
            ).build()
        )

        val tempStr = com.resync.app.util.DeviceThermalHelper.getFormattedHighestTemp(this)
        val initialSpeedStr = if (tempStr.isNotBlank()) "Velocidade: 0,0 MB/s • 🌡 $tempStr" else "Velocidade: 0,0 MB/s"
        val sizeDest = "0 B • Local"
        val batchRate = "Lote: 0/0 • Taxa: 0%"

        return HyperFocusNotificationHelper.buildSafeNotification(
            context = this,
            channelId = CHANNEL_ID,
            title = "Restaurando Backup (0%)",
            line2SizeDest = sizeDest,
            line3BatchRate = batchRate,
            line4SpeedOrDetails = initialSpeedStr,
            progressPercent = 0,
            isOngoing = true,
            isOnlyAlertOnce = true,
            autoCancel = false,
            contentIntent = pOpen,
            actions = actions,
            operationName = "Restaurar",
            destinationText = "Local",
            isFinished = false,
            subText = "Iniciando..."
        )
    }

    private fun updateNotificationFromProgress(progress: RestoreProgress) {
        val now = System.currentTimeMillis()
        val minInterval = 1000L // Atualizar no máximo 1 vez por segundo
        if ((now - lastRestoreNotifTime < minInterval) && !progress.isFinished) {
            return
        }
        lastRestoreNotifTime = now

        val isPaused = progress.isPaused || progress.stage.contains("Pausado", ignoreCase = true) || progress.stage.contains("esfriar", ignoreCase = true)
        if (isPaused) {
            releaseLocks()
        } else if (!progress.isFinished) {
            acquireLocks()
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


        val pct = (progress.progressPercent * 100).toInt().coerceIn(0, 100)
        val isDone = progress.isFinished

        val isCloud = progress.stage.contains("Drive", ignoreCase = true) ||
                progress.stage.contains("Nuvem", ignoreCase = true) ||
                progress.currentItemName.contains("Drive", ignoreCase = true)

        val speedMBps = if (progress.downloadSpeedBytesPerSec > 0L) (progress.downloadSpeedBytesPerSec / (1024f * 1024f)) else 0f
        val payload = com.resync.app.cloud.gdrive.NotificationTransferPayload(
            isUpload = false,
            progressPercent = progress.progressPercent,
            bytesProcessed = progress.bytesProcessed,
            totalBytes = progress.totalBytesEstimated,
            currentSpeedMBps = speedMBps,
            averageSpeedMBps = speedMBps,
            remainingSeconds = progress.estimatedRemainingSeconds,
            currentItemName = progress.currentItemName,
            currentItemIndex = progress.currentItemIndex,
            totalItems = progress.totalItems,
            stage = progress.stage,
            networkType = com.resync.app.cloud.gdrive.CloudNetworkMonitor.getActiveNetworkType(),
            isPaused = isPaused,
            isWaitingNetwork = !com.resync.app.cloud.gdrive.CloudNetworkMonitor.isConnected(),
            isError = progress.error != null,
            errorMessage = progress.error
        )

        val pCancel = Intent(this, RestoreForegroundService::class.java).apply { action = ACTION_CANCEL }
        val pPause = Intent(this, RestoreForegroundService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }

        if (!isDone) {
            val notif = com.resync.app.cloud.gdrive.CloudNotificationManager.buildTransferNotification(
                context = this,
                payload = payload,
                pauseActionIntent = pPause,
                cancelActionIntent = pCancel
            )
            try {
                HyperFocusNotificationHelper.logNotificationStatus(this, CHANNEL_ID, "RestoreForegroundService: BEFORE notify (pct=${payload.progressPercent})")
                notificationManager?.notify(NOTIFICATION_ID, notif)
                android.util.Log.d("ReSyncNotif", "RestoreForegroundService: notify postado com sucesso (id=$NOTIFICATION_ID, pct=${payload.progressPercent})")
            } catch (t: Throwable) {
                android.util.Log.e("ReSyncNotif", "Erro ao notificar progresso da restauração: ${t.message}", t)
            }
        } else {
            notificationManager?.cancel(NOTIFICATION_ID)
            com.resync.app.cloud.gdrive.CloudNotificationManager.cancelProgressNotification(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0).replace('.', ',')
            bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0).replace('.', ',')
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        val mbPerSec = bytesPerSec.toDouble() / (1024.0 * 1024.0)
        return if (mbPerSec >= 0.1) {
            String.format(java.util.Locale.US, "%.1f MB/s", mbPerSec).replace('.', ',')
        } else {
            val kbPerSec = bytesPerSec.toDouble() / 1024.0
            String.format(java.util.Locale.US, "%.0f KB/s", kbPerSec)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

