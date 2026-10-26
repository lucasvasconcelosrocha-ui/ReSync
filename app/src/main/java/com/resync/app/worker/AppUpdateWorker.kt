package com.resync.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.resync.app.MainActivity
import com.resync.app.R
import com.resync.app.updater.AppReleaseInfo
import com.resync.app.updater.AppUpdateManager
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker that periodically checks for new app releases on GitHub
 * and shows a notification on a dedicated channel when a new version is available.
 */
class AppUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "resync_app_update_check"
        const val CHANNEL_ID = "resync_app_updates_channel"
        const val NOTIFICATION_ID = 9001
        private const val TAG = "AppUpdateWorker"

        fun enqueuePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<AppUpdateWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Periodic AppUpdateWorker enqueued (every 6 hours).")
        }

        fun showUpdateNotification(context: Context, releaseInfo: AppReleaseInfo) {
            ensureNotificationChannel(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    Log.d(TAG, "Sem permissão POST_NOTIFICATIONS para exibir notificação de atualização.")
                    return
                }
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_APP_UPDATE", true)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Nova versão disponível (v${releaseInfo.versionName})")
                .setContentText("Uma nova versão do ReSync está disponível para download e atualização.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Nova versão v${releaseInfo.versionName} (build ${releaseInfo.versionCode}) disponível. Toque para abrir a tela de atualização e instalar o APK."
                    )
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
                Log.i(TAG, "Notificação de atualização exibida com sucesso para v${releaseInfo.versionName}.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao exibir notificação de atualização: ${e.message}")
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (manager != null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Atualizações do Aplicativo",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Notificações informando novas versões e atualizações disponíveis do ReSync"
                        setShowBadge(true)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }
    }

    override suspend fun doWork(): Result {
        val appUpdateManager = AppUpdateManager(appContext)

        if (!appUpdateManager.isAutoCheckEnabled()) {
            Log.d(TAG, "Checagem automática de atualização está desativada nas preferências.")
            return Result.success()
        }

        Log.d(TAG, "Executando verificação periódica de atualização em background via WorkManager...")
        val checkResult = appUpdateManager.checkForUpdate(forceManual = true)

        checkResult.onSuccess { releaseInfo ->
            if (releaseInfo != null) {
                Log.i(TAG, "Nova versão detectada via worker: ${releaseInfo.versionName}. Exibindo notificação...")
                showUpdateNotification(appContext, releaseInfo)
            } else {
                Log.d(TAG, "Worker: aplicativo já está na versão mais recente.")
            }
        }.onFailure { e ->
            Log.w(TAG, "Worker: falha na verificação de atualização: ${e.message}")
        }

        return Result.success()
    }
}
