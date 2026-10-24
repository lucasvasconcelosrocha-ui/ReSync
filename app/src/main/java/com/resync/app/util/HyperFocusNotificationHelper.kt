package com.resync.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.resync.app.R

/**
 * Utilitário para gerenciamento de notificações e canais do ReSync.
 * Mantém o layout decorado padrão com o visual de 4 linhas fixas e barra de progresso,
 * sem inclusão de extras miui.focus / ilha dinâmica ou styles experimentais.
 */
object HyperFocusNotificationHelper {

    const val TAG = "ReSyncNotif"

    const val CHANNEL_OPERATIONS_ID = "resync_ops_v5"
    const val CHANNEL_COMPLETION_ID = "resync_completion_v5"

    /**
     * Cria e atualiza os canais de notificação unificados do app, deletando canais obsoletos
     * para contornar bloqueios ou reduções silenciosas de importância salvas pelo Android.
     */
    fun ensureNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val obsoleteChannels = listOf(
                "resync_ops_v4",
                "resync_completion_v4",
                "resync_ops_v3",
                "resync_completion_v3",
                "backup_lockscreen_channel",
                "resync_backup_channel",
                "resync_backup_channel_v2",
                "resync_cloud_transfer_channel",
                "resync_cloud_transfer_channel_v2",
                "resync_restore_channel",
                "resync_restore_channel_v2",
                "resync_completion_channel",
                "resync_completion_channel_v2",
                "swiftvault_backup_channel",
                "swiftvault_restore_channel",
                "resync_upload_channel"
            )
            for (ch in obsoleteChannels) {
                try {
                    nm.deleteNotificationChannel(ch)
                } catch (_: Throwable) {}
            }

            // Canal para operações em andamento (Backup, Restauração, Nuvem)
            val opsChannel = NotificationChannel(
                CHANNEL_OPERATIONS_ID,
                "Operações em Andamento",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso em tempo real de backups, restaurações e transferências para a nuvem."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(opsChannel)

            // Canal para avisos de conclusão de operações
            val compChannel = NotificationChannel(
                CHANNEL_COMPLETION_ID,
                "Conclusão de Operações",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificações de finalização de backup, restauração e envio para a nuvem."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            nm.createNotificationChannel(compChannel)
        }
    }

    /**
     * Consulta no Xiaomi HyperOS / MIUI se o aplicativo possui permissão ativa para
     * status público.
     */
    fun isHyperOsFocusAllowed(context: Context): Boolean {
        return try {
            val uri = Uri.parse("content://miui.statusbar.notification.public")
            val extras = Bundle().apply { putString("package", context.packageName) }
            val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras)
            bundle?.getBoolean("canShowFocus", false) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Emite logs para diagnóstico de status de notificação.
     */
    fun logNotificationStatus(context: Context, channelId: String, callerTag: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val areEnabled = nm?.areNotificationsEnabled() ?: false
            val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm?.getNotificationChannel(channelId)
            } else null
            val importance = channel?.importance ?: -1
            Log.d(
                TAG,
                "[$callerTag] areNotificationsEnabled=$areEnabled, channelId=$channelId, channelImportance=$importance"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "[$callerTag] Erro ao consultar status da notificação: ${t.message}")
        }
    }

    @Volatile
    private var cachedAppLogoBitmap: Bitmap? = null

    /**
     * Retorna o bitmap oficial do ReSync limitado a ~192x192 px de forma segura.
     */
    fun getAppLogoBitmap(context: Context): Bitmap? {
        val cached = cachedAppLogoBitmap
        if (cached != null && !cached.isRecycled) {
            return cached
        }
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeResource(context.resources, R.drawable.ic_resync_notification_logo, options)
                ?: BitmapFactory.decodeResource(context.resources, R.drawable.ic_resync_island, options)
                ?: BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher, options)

            if (bmp != null && (bmp.width > 192 || bmp.height > 192)) {
                bmp = Bitmap.createScaledBitmap(bmp, 192, 192, true)
            }
            cachedAppLogoBitmap = bmp
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "getAppLogoBitmap: falha ao carregar logo: ${t.message}")
            null
        }
    }

    fun getIslandLogoBitmap(context: Context): Bitmap? = getAppLogoBitmap(context)

    fun formatShortIslandTime(seconds: Long): String {
        if (seconds <= 0L) return "~"
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "%d:%02d:%02d".format(hours, mins, secs)
            else -> "%02d:%02d".format(mins, secs)
        }
    }

    fun formatShortDestination(destination: String): String {
        val d = destination.trim().lowercase()
        return when {
            d.contains("ambos") || d.contains("both") -> "Ambos"
            d.contains("drive") || d.contains("cloud") || d.contains("nuvem") -> "Drive"
            else -> "Local"
        }
    }

    /**
     * Construtor original e estável de notificação: utiliza DecoratedCustomViewStyle com layout
     * de 4 linhas exatas e barra de progresso (R.layout.notification_transfer_collapsed).
     */
    fun buildSafeNotification(
        context: Context,
        channelId: String,
        title: String,
        line2SizeDest: String,
        line3BatchRate: String = "",
        line4SpeedOrDetails: String = "",
        progressPercent: Int = -1,
        isOngoing: Boolean = true,
        isOnlyAlertOnce: Boolean = true,
        autoCancel: Boolean = false,
        contentIntent: PendingIntent? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
        operationName: String = "Backup",
        destinationText: String = "Local",
        isFinished: Boolean = false,
        subText: String = ""
    ): Notification {
        ensureNotificationChannels(context)

        return try {
            val collapsedView = RemoteViews(context.packageName, R.layout.notification_transfer_collapsed).apply {
                setTextViewText(R.id.notif_title, title.ifBlank { "Realizando Backup" })
                setTextViewText(R.id.notif_size_dest, line2SizeDest.ifBlank { "0 B • Local" })
                setTextViewText(R.id.notif_batch_rate, line3BatchRate.ifBlank { "Lote: 0/0" })
                setTextViewText(R.id.notif_speed, line4SpeedOrDetails.ifBlank { "Velocidade: 0,0 MB/s" })
            }

            val effectiveSubText = subText.ifBlank { "Processando itens..." }

            val builder = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(line2SizeDest)
                .setSubText(effectiveSubText)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(isOngoing)
                .setAutoCancel(autoCancel)
                .setOnlyAlertOnce(isOnlyAlertOnce)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(if (isOngoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsedView)
                .setCustomBigContentView(collapsedView)

            if (contentIntent != null) {
                builder.setContentIntent(contentIntent)
            }

            for (action in actions) {
                builder.addAction(action)
            }

            if (progressPercent in 0..100) {
                builder.setProgress(100, progressPercent, false)
            } else if (isOngoing) {
                builder.setProgress(100, 0, true)
            }

            // Lockscreen Public Notification (matches the same 4-line collapsed view)
            val publicNotification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(line2SizeDest)
                .setSubText(effectiveSubText)
                .setOnlyAlertOnce(isOnlyAlertOnce)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsedView)
                .apply {
                    if (progressPercent in 0..100) setProgress(100, progressPercent, false)
                    else if (isOngoing) setProgress(100, 0, true)
                }
                .build()
            builder.setPublicVersion(publicNotification)

            builder.build()
        } catch (t: Throwable) {
            Log.e(TAG, "buildSafeNotification fallback: ${t.message}", t)
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.ifBlank { "ReSync" })
                .setContentText(line2SizeDest.ifBlank { "Operação em andamento" })
                .setOngoing(isOngoing)
                .setOnlyAlertOnce(true)
                .apply {
                    if (contentIntent != null) setContentIntent(contentIntent)
                }
                .build()
        }
    }
}
