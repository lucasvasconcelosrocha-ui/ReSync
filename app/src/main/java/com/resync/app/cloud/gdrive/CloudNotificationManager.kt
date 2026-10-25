package com.resync.app.cloud.gdrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.resync.app.MainActivity
import com.resync.app.R
import com.resync.app.service.BackupForegroundService
import com.resync.app.util.AppLogger
import com.resync.app.util.HyperFocusNotificationHelper
import java.util.Locale

data class NotificationTransferPayload(
    val isUpload: Boolean,
    val progressPercent: Float,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val currentSpeedMBps: Float,
    val averageSpeedMBps: Float,
    val remainingSeconds: Long,
    val currentItemName: String,
    val currentItemIndex: Int,
    val totalItems: Int,
    val stage: String,
    val networkType: String,
    val isPaused: Boolean,
    val isWaitingNetwork: Boolean,
    val isError: Boolean,
    val errorMessage: String? = null
)

object CloudNotificationManager {

    private const val TAG = "CloudNotificationManager"
    const val CHANNEL_ID = HyperFocusNotificationHelper.CHANNEL_OPERATIONS_ID
    const val CHANNEL_COMPLETION_ID = HyperFocusNotificationHelper.CHANNEL_COMPLETION_ID
    const val NOTIFICATION_ID = 3003
    const val COMPLETION_NOTIFICATION_ID = 3004

    private var lastUpdateTimeMs = 0L
    private const val MIN_UPDATE_INTERVAL_MS = 1000L // 1 update per second max

    fun ensureChannel(context: Context) {
        HyperFocusNotificationHelper.ensureNotificationChannels(context)
    }

    fun buildTransferNotification(
        context: Context,
        payload: NotificationTransferPayload,
        pauseActionIntent: Intent? = null,
        cancelActionIntent: Intent? = null
    ): Notification {
        ensureChannel(context)

        val pct = (payload.progressPercent * 100).toInt().coerceIn(0, 100)
        val arrow = if (payload.isUpload) "↑" else "↓"
        val opName = if (payload.isUpload) "Drive" else "Restaurando"

        val speedStr = String.format(Locale.US, "%.1f MB/s", payload.currentSpeedMBps).replace('.', ',')
        val avgSpeedStr = String.format(Locale.US, "%.1f MB/s", payload.averageSpeedMBps).replace('.', ',')

        val notifTitle = when {
            payload.isWaitingNetwork -> "Aguardando conexão... ($pct%)"
            payload.isPaused -> "$opName $arrow Pausado ($pct%)"
            payload.isError -> "Falha na transferência"
            else -> "$opName $arrow ($pct%)"
        }

        val bytesProcessedStr = formatCompactBytes(payload.bytesProcessed)
        val totalBytesStr = if (payload.totalBytes > 0) formatCompactBytes(payload.totalBytes) else "?"
        val sizeDestStr = "$bytesProcessedStr de $totalBytesStr • Google Drive"

        val batchStr = if (payload.totalItems > 1) {
            "Lote: ${payload.currentItemIndex}/${payload.totalItems}"
        } else {
            "Lote: 1/1"
        }

        val etaShort = if (payload.remainingSeconds <= 0L) "Calculando…" else com.resync.app.util.FormatUtils.formatRemainingTimeOnly(payload.remainingSeconds)
        val etaSuffix = "⏱ $etaShort"
        val fullBatchLine = "$batchStr • Taxa: $pct% • $etaSuffix"
        val batchRateStr = when {
            payload.isWaitingNetwork -> "$batchStr • Sem rede"
            payload.isPaused -> "$batchStr • Pausado"
            fullBatchLine.length <= 38 -> fullBatchLine
            else -> "$batchStr • $etaSuffix"
        }
        val tempStr = com.resync.app.util.DeviceThermalHelper.getFormattedHighestTemp(context)
        val speedText = if (tempStr.isNotBlank()) "Velocidade: $speedStr • 🌡 $tempStr" else "Velocidade: $speedStr"

        val statusText = when {
            payload.isWaitingNetwork -> "Status: Aguardando rede para continuar..."
            payload.isPaused -> "Status: Transferência pausada pelo usuário"
            payload.isError -> "Status: ${payload.errorMessage ?: "Erro desconhecido"}"
            payload.stage.isNotBlank() -> {
                val item = if (payload.currentItemName.isNotBlank() && payload.currentItemName != payload.stage) " • ${payload.currentItemName}" else ""
                "Status: ${payload.stage}$item"
            }
            else -> "Status: Processando transferência..."
        }

        val subTextHeader = when {
            payload.isWaitingNetwork -> "Sem rede"
            payload.isPaused -> "Pausado"
            payload.stage.isNotBlank() -> payload.stage
            else -> "Processando itens..."
        }

        val etaStr = if (payload.remainingSeconds > 0) formatCompactEta(payload.remainingSeconds) else ""
        val extraInfo = if (etaStr.isNotBlank()) "Tempo restante: $etaStr • Rede: ${payload.networkType}" else "Rede: ${payload.networkType}"

        // Pending Intents
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_ACTIVE_OPERATIONS", true)
        }
        val pendingOpen = PendingIntent.getActivity(
            context, 100, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val actions = mutableListOf<NotificationCompat.Action>()
        if (pauseActionIntent != null) {
            val pPause = PendingIntent.getService(
                context, 101, pauseActionIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            actions.add(
                NotificationCompat.Action.Builder(
                    if (payload.isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (payload.isPaused) "Retomar" else "Pausar",
                    pPause
                ).build()
            )
        }

        if (cancelActionIntent != null) {
            val pCancel = PendingIntent.getService(
                context, 102, cancelActionIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            actions.add(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancelar",
                    pCancel
                ).build()
            )
        }

        val islandOpName = if (payload.isUpload) "Drive" else "Restaurar"
        val shortDest = if (payload.isUpload) "Drive" else "Local"

        return HyperFocusNotificationHelper.buildSafeNotification(
            context = context,
            channelId = CHANNEL_ID,
            title = notifTitle,
            line2SizeDest = sizeDestStr,
            line3BatchRate = batchRateStr,
            line4SpeedOrDetails = speedText,
            progressPercent = pct,
            isOngoing = true,
            isOnlyAlertOnce = true,
            autoCancel = false,
            contentIntent = pendingOpen,
            actions = actions,
            operationName = islandOpName,
            destinationText = shortDest,
            isFinished = false,
            subText = subTextHeader
        )
    }

    fun updateProgressNotificationThrottled(
        context: Context,
        payload: NotificationTransferPayload,
        pauseActionIntent: Intent? = null,
        cancelActionIntent: Intent? = null,
        forceImmediate: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!forceImmediate && now - lastUpdateTimeMs < MIN_UPDATE_INTERVAL_MS) {
            return
        }
        lastUpdateTimeMs = now
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            val notif = buildTransferNotification(context, payload, pauseActionIntent, cancelActionIntent)
            HyperFocusNotificationHelper.logNotificationStatus(context, CHANNEL_ID, "CloudNotificationManager.updateProgressNotificationThrottled: BEFORE notify")
            nm.notify(NOTIFICATION_ID, notif)
            android.util.Log.d("ReSyncNotif", "CloudNotificationManager: transfer notify executado com sucesso (id=$NOTIFICATION_ID, pct=${payload.progressPercent})")
        } catch (t: Throwable) {
            android.util.Log.e("ReSyncNotif", "CloudNotificationManager: Falha ao postar notificação de transferência: ${t.message}", t)
        }
    }

    enum class CompletionOperationType {
        BACKUP,
        RESTORE,
        CLOUD_UPLOAD,
        CLOUD_DOWNLOAD
    }

    fun postCompletionNotification(
        context: Context,
        operationType: CompletionOperationType = CompletionOperationType.BACKUP,
        elapsedFormatted: String, // e.g. "14 min 51 s"
        destinationOrSource: String = "Memória interna",
        totalBytes: Long = 0L,
        totalItems: Int = 0,
        warningCount: Int = 0,
        failureCount: Int = 0,
        backupName: String = "",
        legacyTitle: String = ""
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID) // 3003
        nm.cancel(1001) // BackupForegroundService.NOTIFICATION_ID
        nm.cancel(2002) // RestoreForegroundService.NOTIFICATION_ID

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_ACTIVE_OPERATIONS", true)
        }
        val pendingOpen = PendingIntent.getActivity(
            context, 103, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Linha 1 (Título): Concluído em 14 min 51 s
        val cleanElapsed = elapsedFormatted.replace("Concluído em ", "").trim()
        val line1Title = if (cleanElapsed.isNotBlank()) "Concluído em $cleanElapsed" else "Concluído com sucesso"

        // Linha 2 (Status curto): Backup concluído com sucesso / Concluído com 2 avisos
        val line2Status = when {
            failureCount > 0 -> "Concluído com $failureCount ${if (failureCount == 1) "falha" else "falhas"}"
            warningCount > 0 -> "Concluído com $warningCount ${if (warningCount == 1) "aviso" else "avisos"}"
            operationType == CompletionOperationType.RESTORE -> "Restauração concluída com sucesso"
            operationType == CompletionOperationType.CLOUD_UPLOAD -> "Envio concluído com sucesso"
            operationType == CompletionOperationType.CLOUD_DOWNLOAD -> "Download concluído com sucesso"
            else -> "Backup concluído com sucesso"
        }

        // Linha 3 (Destino/Origem curto e direto): Destino: Memória interna (ou Google Drive / Ambos)
        val isRestoreOrDownload = operationType == CompletionOperationType.RESTORE || operationType == CompletionOperationType.CLOUD_DOWNLOAD
        val destPrefix = if (isRestoreOrDownload) "Origem:" else "Destino:"
        val cleanDest = when {
            destinationOrSource.contains("Interna", ignoreCase = true) && destinationOrSource.contains("Drive", ignoreCase = true) -> "Ambos (Interna e Drive)"
            destinationOrSource.contains("Drive", ignoreCase = true) || destinationOrSource.contains("Google", ignoreCase = true) -> "Google Drive"
            destinationOrSource.contains("Interna", ignoreCase = true) || destinationOrSource.contains("Local", ignoreCase = true) -> "Memória interna"
            destinationOrSource.isNotBlank() -> destinationOrSource
                .replace("Armazenado exclusivamente no ", "")
                .replace("Armazenado exclusivamente em ", "")
                .replace("Armazenado na ", "")
                .replace("Armazenado no ", "")
                .trim()
            else -> "Memória interna"
        }
        val line3Dest = "$destPrefix $cleanDest"

        // Linha 4 (Tamanho e Itens): Tamanho: 15,3 GB • Itens: 1969
        val sizeStr = if (totalBytes > 0) formatCompactBytes(totalBytes) else ""
        val line4SizeItems = when {
            sizeStr.isNotBlank() && totalItems > 0 -> "Tamanho: $sizeStr • Itens: $totalItems"
            sizeStr.isNotBlank() -> "Tamanho: $sizeStr"
            totalItems > 0 -> "Itens: $totalItems"
            else -> "Processamento finalizado"
        }

        // Layout colapsado (tela de bloqueio): Exatamente 4 linhas curtas, sem nome longo de arquivo
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_completion_collapsed).apply {
            setTextViewText(R.id.notif_comp_title, line1Title)
            setTextViewText(R.id.notif_comp_status, line2Status)
            setTextViewText(R.id.notif_comp_destination, line3Dest)
            setTextViewText(R.id.notif_comp_size_items, line4SizeItems)
        }

        // Layout expandido: Mostra as 4 linhas + nome do arquivo e detalhes
        val expandedView = RemoteViews(context.packageName, R.layout.notification_completion_expanded).apply {
            setTextViewText(R.id.notif_comp_title, line1Title)
            setTextViewText(R.id.notif_comp_status, line2Status)
            setTextViewText(R.id.notif_comp_destination, line3Dest)
            setTextViewText(R.id.notif_comp_size_items, line4SizeItems)

            val displayFileName = backupName.ifBlank { legacyTitle }.trim()
            if (displayFileName.isNotBlank()) {
                setViewVisibility(R.id.notif_comp_filename, android.view.View.VISIBLE)
                setTextViewText(R.id.notif_comp_filename, "Arquivo: $displayFileName")
            } else {
                setViewVisibility(R.id.notif_comp_filename, android.view.View.GONE)
            }

            if (warningCount > 0 || failureCount > 0) {
                setViewVisibility(R.id.notif_comp_details, android.view.View.VISIBLE)
                val detailStr = if (failureCount > 0) "$failureCount itens com falha. Toque para ver no app." else "$warningCount avisos registrados."
                setTextViewText(R.id.notif_comp_details, detailStr)
            } else {
                setViewVisibility(R.id.notif_comp_details, android.view.View.GONE)
            }
        }

        val appLogo = HyperFocusNotificationHelper.getAdaptiveIconColorBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETION_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appLogo)
            .setContentTitle(line1Title)
            .setContentText(line2Status)
            .setSubText(cleanDest)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)

        // Versão pública para a tela de bloqueio com exatamente as 4 linhas
        val publicNotif = NotificationCompat.Builder(context, CHANNEL_COMPLETION_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appLogo)
            .setContentTitle(line1Title)
            .setContentText(line2Status)
            .setSubText(cleanDest)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView)
            .build()
        builder.setPublicVersion(publicNotif)

        try {
            nm.notify(COMPLETION_NOTIFICATION_ID, builder.build())
            android.util.Log.d("ReSyncNotif", "CloudNotificationManager: completion notify executado com sucesso (id=$COMPLETION_NOTIFICATION_ID, title='$line1Title')")
        } catch (t: Throwable) {
            android.util.Log.e("ReSyncNotif", "CloudNotificationManager: Falha ao postar notificação de conclusão: ${t.message}", t)
        }
    }

    // Sobrecarga de compatibilidade para chamadas legadas
    fun postCompletionNotification(context: Context, title: String, elapsedFormatted: String) {
        postCompletionNotification(
            context = context,
            operationType = CompletionOperationType.BACKUP,
            elapsedFormatted = elapsedFormatted,
            legacyTitle = title
        )
    }

    fun cancelProgressNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID)
    }

    private fun formatCompactBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0).replace('.', ',')
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0).replace('.', ',')
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatCompactEta(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h} h ${m} min"
            m > 0 -> "${m} min"
            else -> "${s} s"
        }
    }
}
