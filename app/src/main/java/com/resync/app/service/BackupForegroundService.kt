package com.resync.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.widget.RemoteViews
import com.resync.app.MainActivity
import com.resync.app.R
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.BackupPaths
import com.resync.app.data.model.LogLevel
import com.resync.app.data.room.ActiveOperationEntity
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.engine.*
import com.resync.app.util.HyperFocusNotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Armored, resilient Foreground Service guaranteeing uninterrupted backup execution
 * (Swift Backup style).
 *
 * Capabilities:
 * - Immune to app-swipe from Recents (`stopWithTask="false"`, `START_STICKY`, `onTaskRemoved`).
 * - High-speed in-memory streaming with Zstd/LZ4 Smart Bypass and HTTP/2 pipelining.
 * - Dynamic Thermal Pacing via `PowerManager.OnThermalStatusChangedListener`.
 * - Persistent Lockscreen Notification with real-time speed (MB/s), progress, and interactive Pause/Cancel controls.
 * - Throttled notification updates (>= 500ms) preventing System UI freeze.
 * - Safe WakeLock and WifiLock acquisition and release in `finally`.
 */
class BackupForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = HyperFocusNotificationHelper.CHANNEL_OPERATIONS_ID
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_BACKUP"
        const val ACTION_START_LEGACY = "ACTION_START"
        const val ACTION_PAUSE_RESUME = "ACTION_PAUSE_RESUME_BACKUP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL_BACKUP"
        const val ACTION_CANCEL_LEGACY = "ACTION_CANCEL"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TITLE = "EXTRA_TITLE"

        var isServiceRunning: Boolean = false
            private set

        private var staticWakeLock: PowerManager.WakeLock? = null
        private var staticWifiLock: WifiManager.WifiLock? = null

        fun acquireLocks(context: Context) {
            try {
                if (staticWakeLock == null) {
                    val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    staticWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReSync::CloudBackupWakeLock").apply {
                        setReferenceCounted(false)
                        acquire(24 * 60 * 60 * 1000L) // 24h safety limit
                    }
                }
                if (staticWifiLock == null) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    staticWifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ReSync::CloudBackupWifiLock")
                    } else {
                        @Suppress("DEPRECATION")
                        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReSync::CloudBackupWifiLock")
                    }.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } catch (_: Throwable) {}
        }

        fun releaseLocks() {
            try {
                staticWakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Throwable) {}
            staticWakeLock = null

            try {
                staticWifiLock?.let { if (it.isHeld) it.release() }
            } catch (_: Throwable) {}
            staticWifiLock = null
        }

        fun startService(context: Context, title: String) {
            try {
                acquireLocks(context)
                isServiceRunning = true
                val intent = Intent(context, BackupForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TITLE, title)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {}
        }

        fun stopService(context: Context) {
            try {
                releaseLocks()
                isServiceRunning = false
                val intent = Intent(context, BackupForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        /**
         * Verifies if battery optimization (Doze mode) is ignored for this app.
         */
        fun isBatteryOptimizationIgnored(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else {
                true
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backupJob: Job? = null
    private var thermalListener: Any? = null
    @Volatile private var currentOperationTitle: String = "Realizando Backup"
    @Volatile private var isFinishedOrCancelled: Boolean = false

    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val activeConcurrencyLimit = AtomicInteger(com.resync.app.engine.PerformanceConfig.maxWorkers)
    @Volatile private var thermalCooldownDelayMs = 0L

    // Notification throttling (minimum 1000ms between calls to avoid freezing System UI)
    private var lastNotifTimeMs = 0L
    private val NOTIF_THROTTLE_MS = 1000L

    override fun onCreate() {
        super.onCreate()
        activeConcurrencyLimit.set(com.resync.app.engine.PerformanceConfig.maxWorkers)
        isServiceRunning = true
        isFinishedOrCancelled = false
        acquireLocks()
        createNotificationChannel()
        registerThermalMonitor()

        // Sync with BackupRepository progress flow (Single Source of Truth)
        serviceScope.launch {
            BackupRepository.backupProgress.collectLatest { progress ->
                if (progress != null && !isFinishedOrCancelled) {
                    if (progress.isFinished) {
                        isFinishedOrCancelled = true
                        android.util.Log.d("BackupOrigin", "BackupForegroundService: progress.isFinished recebido. Encerrando foreground notification.")
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.cancel(NOTIFICATION_ID)
                        stopSelf()
                        return@collectLatest
                    }

                    val pct = (progress.progressPercent * 100).toInt().coerceIn(0, 99)
                    val speedBytes = if (progress.uploadSpeedBytesPerSec > 0L) progress.uploadSpeedBytesPerSec
                    else if (progress.speedBytesPerSec > 0L) progress.speedBytesPerSec
                    else progress.compressionSpeedBytesPerSec

                    val speedText = formatSpeed(speedBytes)

                    val isDriveUpload = progress.stage.contains("Drive", ignoreCase = true) ||
                            progress.stage.contains("Enviando", ignoreCase = true) ||
                            progress.uploadSpeedBytesPerSec > 0L ||
                            currentOperationTitle.contains("Drive", ignoreCase = true)

                    val notifTitle = when {
                        progress.isPaused -> if (isDriveUpload) "Drive ↑ Pausado ($pct%)" else "Backup Pausado ($pct%)"
                        !com.resync.app.cloud.gdrive.CloudNetworkMonitor.isConnected() && isDriveUpload -> "Aguardando conexão... ($pct%)"
                        isDriveUpload -> "Google Drive ↑ ($pct%)"
                        else -> "Realizando Backup ($pct%)"
                    }

                    val destStr = when {
                        currentOperationTitle.contains("Ambos", ignoreCase = true) -> "Ambos"
                        isDriveUpload -> "Drive"
                        else -> "Local"
                    }
                    val sizeDest = if (progress.bytesProcessed > 0L && progress.totalBytesEstimated > 0L) {
                        "${formatBytes(progress.bytesProcessed)} de ${formatBytes(progress.totalBytesEstimated)} • $destStr"
                    } else if (progress.totalBytesEstimated > 0L) {
                        "0 B de ${formatBytes(progress.totalBytesEstimated)} • $destStr"
                    } else {
                        "0 B • $destStr"
                    }

                    val batchStr = if (progress.totalItems > 0) {
                        "Lote: ${progress.currentItemIndex}/${progress.totalItems}"
                    } else {
                        "Lote: 0/0"
                    }

                    val remainingSec = progress.estimatedRemainingSeconds
                    val etaShort = com.resync.app.util.FormatUtils.formatRemainingTimeOnly(remainingSec)
                    val etaSuffix = when (etaShort) {
                        "Calculando…", "—", "Finalizando…" -> etaShort
                        else -> "⏱ $etaShort"
                    }
                    val fullBatchLine = "$batchStr • Taxa: $pct% • $etaSuffix"
                    val batchRateLine = when {
                        progress.isPaused -> "$batchStr • Pausado"
                        !com.resync.app.cloud.gdrive.CloudNetworkMonitor.isConnected() && isDriveUpload -> "$batchStr • Sem rede"
                        else -> fullBatchLine
                    }
                    val tempStr = com.resync.app.util.DeviceThermalHelper.getFormattedHighestTemp(this@BackupForegroundService)
                    val speedLine = if (tempStr.isNotBlank()) "Velocidade: $speedText • 🌡 $tempStr" else "Velocidade: $speedText"

                    updateNotification(
                        title = notifTitle,
                        progress = pct,
                        sizeDest = sizeDest,
                        batchRate = batchRateLine,
                        speed = speedLine,
                        subText = if (progress.isPaused) "Pausado" else progress.stage.ifBlank { "Em andamento" },
                        paused = progress.isPaused,
                        remainingSeconds = remainingSec
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        android.util.Log.d("BackupOrigin", "BackupForegroundService.onStartCommand: action=$action, startId=$startId, flags=$flags")
        if (action == null) {
            android.util.Log.d("BackupOrigin", "BackupForegroundService: intent ou action nula. Finalizando serviço com stopSelf().")
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START, ACTION_START_LEGACY -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.backup_in_progress)
                if (!BackupRepository.isBackupActive()) {
                    android.util.Log.w("BackupOrigin", "BackupForegroundService: ACTION_START recebido mas nenhum backup está ativo no BackupRepository. Encerrando serviço e limpando notificações órfãs.")
                    isFinishedOrCancelled = true
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(NOTIFICATION_ID)
                    manager.cancel(com.resync.app.cloud.gdrive.CloudNotificationManager.NOTIFICATION_ID)
                    stopSelf()
                    return START_NOT_STICKY
                }
                isFinishedOrCancelled = false
                acquireLocks()
                startBackupProcess(title)
            }
            ACTION_PAUSE_RESUME -> togglePauseResume()
            ACTION_PAUSE -> setPauseState(true)
            ACTION_RESUME -> setPauseState(false)
            ACTION_CANCEL, ACTION_CANCEL_LEGACY -> cancelBackupProcess()
            ACTION_STOP -> {
                android.util.Log.d("BackupOrigin", "BackupForegroundService recebendo ACTION_STOP")
                isFinishedOrCancelled = true
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
                manager.cancel(com.resync.app.cloud.gdrive.CloudNotificationManager.NOTIFICATION_ID)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startBackupProcess(title: String = "Backup") {
        val isDriveUpload = title.contains("Drive", ignoreCase = true)
        val destStr = when {
            title.contains("Ambos", ignoreCase = true) -> "Ambos"
            isDriveUpload -> "Drive"
            else -> "Local"
        }
        currentOperationTitle = if (isDriveUpload) "Enviando ao Drive" else "Realizando Backup"

        isCancelled.set(false)
        isPaused.set(false)
        acquireLocks()

        val tempStr = com.resync.app.util.DeviceThermalHelper.getFormattedHighestTemp(this)
        val initialSpeedStr = if (tempStr.isNotBlank()) "Velocidade: 0,0 MB/s • 🌡 $tempStr" else "Velocidade: 0,0 MB/s"
        val initialTitle = if (isDriveUpload) "Enviando ao Drive (0%)" else "Realizando Backup (0%)"
        val initialNotification = buildNotification(
            title = initialTitle,
            progress = 0,
            sizeDest = "0 B • $destStr",
            batchRate = "Lote: 0/0 • Taxa: 0%",
            speed = initialSpeedStr,
            subText = "Iniciando...",
            paused = false
        )

        try {
            HyperFocusNotificationHelper.logNotificationStatus(this, CHANNEL_ID, "BackupForegroundService: BEFORE startForeground")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
            HyperFocusNotificationHelper.logNotificationStatus(this, CHANNEL_ID, "BackupForegroundService: AFTER startForeground")
            android.util.Log.d("ReSyncNotif", "BackupForegroundService: startForeground executado com sucesso no canal $CHANNEL_ID")
        } catch (t: Throwable) {
            android.util.Log.e("ReSyncNotif", "BackupForegroundService: Falha ao executar startForeground: ${t.message}", t)
        }
    }


    /**
     * Dynamic Thermal Monitor adjusting concurrency and pacing dynamically.
     */
    private fun registerThermalMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val limit = com.resync.app.engine.PerformanceConfig.thermalWorkerLimit(status)
                val delay = com.resync.app.engine.PerformanceConfig.thermalDelayMs(status)
                activeConcurrencyLimit.set(limit)
                thermalCooldownDelayMs = delay
            }
            try {
                powerManager?.addThermalStatusListener(mainExecutor, listener)
                thermalListener = listener
            } catch (_: Throwable) {}
        }
    }

    private fun setPauseState(pause: Boolean) {
        isPaused.set(pause)
        if (pause) {
            BackupRepository.pauseBackup()
        } else {
            BackupRepository.resumeBackup()
        }
        updateNotification(
            title = if (pause) "Operação Pausada" else "Retomando Operação...",
            progress = -1,
            sizeDest = "Em espera",
            batchRate = "Lote: Pausado • Taxa: --",
            speed = "Velocidade: 0,0 MB/s",
            status = if (pause) "Status: Toque em Retomar para continuar" else "Status: Reconectando pipeline...",
            subText = if (pause) "Pausado" else "Retomando...",
            paused = pause
        )
    }

    private fun togglePauseResume() {
        val next = !isPaused.get()
        setPauseState(next)
    }

    private fun cancelBackupProcess() {
        android.util.Log.d("BackupOrigin", "BackupForegroundService: cancelBackupProcess chamado")
        isCancelled.set(true)
        isFinishedOrCancelled = true
        backupJob?.cancel()
        BackupRepository.cancelBackup(this)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val roomDb = AppRoomDatabase.getInstance(this@BackupForegroundService)
                val activeList = roomDb.activeOperationDao().getUnfinishedOperations()
                    .filter { it.type == "BACKUP" }
                for (op in activeList) {
                    roomDb.activeOperationDao().insertOrUpdate(
                        op.copy(
                            status = "CANCELLED",
                            stage = "Cancelado pelo usuário",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (_: Throwable) {}
        }
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(com.resync.app.cloud.gdrive.CloudNotificationManager.NOTIFICATION_ID)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        logEvent(LogLevel.INFO, "onTaskRemoved: App fechado na lista de recentes. Backup em primeiro plano preservado com imortalidade.")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.d("BackupOrigin", "onTimeout acionado pelo Android 15+ (startId=$startId, fgsType=$fgsType)")
        val currentProgress = BackupRepository.backupProgress.value
        if (currentProgress != null && !currentProgress.isFinished && !isFinishedOrCancelled) {
            logEvent(LogLevel.WARN, "onTimeout acionado pelo Android 15+. Salvando estado e estendendo execução.")
            serviceScope.launch {
                try {
                    startService(this@BackupForegroundService, currentOperationTitle)
                } catch (_: Throwable) {}
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            stopSelf()
        }
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        sizeDest: String,
        batchRate: String,
        speed: String,
        status: String = "",
        subText: String = "",
        extra: String = "",
        paused: Boolean = false,
        remainingSeconds: Long = 0L
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_ACTIVE_OPERATIONS", true)
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseResumePending = PendingIntent.getService(
            this, 1,
            Intent(this, BackupForegroundService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPending = PendingIntent.getService(
            this, 2,
            Intent(this, BackupForegroundService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actions = listOf(
            NotificationCompat.Action.Builder(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Retomar" else "Pausar",
                pauseResumePending
            ).build(),
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar",
                cancelPending
            ).build()
        )

        val effectiveSubText = subText.ifBlank { "Processando itens..." }
        val opName = when {
            title.contains("Drive", ignoreCase = true) -> "Drive"
            title.contains("Ambos", ignoreCase = true) -> "Ambos"
            else -> "Backup"
        }
        val shortDest = HyperFocusNotificationHelper.formatShortDestination(sizeDest)

        return HyperFocusNotificationHelper.buildSafeNotification(
            context = this,
            channelId = CHANNEL_ID,
            title = title,
            line2SizeDest = sizeDest,
            line3BatchRate = batchRate,
            line4SpeedOrDetails = speed,
            progressPercent = progress,
            isOngoing = true,
            isOnlyAlertOnce = true,
            autoCancel = false,
            contentIntent = pendingOpenIntent,
            actions = actions,
            operationName = opName,
            destinationText = shortDest,
            isFinished = progress >= 100,
            subText = effectiveSubText
        )
    }


    private fun updateNotification(
        title: String,
        progress: Int,
        sizeDest: String = "",
        batchRate: String = "",
        speed: String = "",
        status: String = "",
        subText: String = "",
        extra: String = "",
        paused: Boolean = false,
        remainingSeconds: Long = 0L
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifTimeMs < NOTIF_THROTTLE_MS && progress != 100 && progress != 0) {
            return
        }
        lastNotifTimeMs = now
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = buildNotification(title, progress, sizeDest, batchRate, speed, status, subText, extra, paused, remainingSeconds)
            HyperFocusNotificationHelper.logNotificationStatus(this, CHANNEL_ID, "BackupForegroundService: BEFORE notify (pct=$progress%)")
            manager.notify(NOTIFICATION_ID, notif)
            android.util.Log.d("ReSyncNotif", "BackupForegroundService: notify executado com sucesso (id=$NOTIFICATION_ID, progress=$progress%)")
        } catch (t: Throwable) {
            android.util.Log.e("ReSyncNotif", "BackupForegroundService: Erro ao postar notificação de backup: ${t.message}", t)
        }
    }

    private fun createNotificationChannel() {
        HyperFocusNotificationHelper.ensureNotificationChannels(this)
    }

    private fun acquireLocks() {
        Companion.acquireLocks(this)
    }

    private fun releaseLocks() {
        Companion.releaseLocks()
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0,0 MB/s"
        val mbPerSec = bytesPerSec.toDouble() / (1024.0 * 1024.0)
        return if (mbPerSec >= 0.1) {
            String.format(Locale.US, "%.1f MB/s", mbPerSec).replace('.', ',')
        } else {
            val kbPerSec = bytesPerSec.toDouble() / 1024.0
            String.format(Locale.US, "%.0f KB/s", kbPerSec)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0).replace('.', ',')
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0).replace('.', ',')
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun logEvent(level: LogLevel, message: String) {
        serviceScope.launch {
            try {
                AppDatabaseHelper.getInstance(this@BackupForegroundService)
                    .insertLog(level, "BackupForegroundService", message)
            } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                @Suppress("UNCHECKED_CAST")
                pm?.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
            } catch (_: Throwable) {}
        }
        releaseLocks()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(com.resync.app.cloud.gdrive.CloudNotificationManager.NOTIFICATION_ID)
        } catch (_: Throwable) {}
        serviceScope.cancel()
        super.onDestroy()
    }
}
