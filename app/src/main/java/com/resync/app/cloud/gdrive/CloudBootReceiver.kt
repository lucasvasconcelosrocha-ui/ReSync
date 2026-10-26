package com.resync.app.cloud.gdrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.service.BackupForegroundService
import com.resync.app.service.RestoreForegroundService
import com.resync.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Resumes unfinished cloud transfers after device reboot or app update.
 */
class CloudBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CloudBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        AppLogger.i(TAG, "Dispositivo reiniciado ou pacote atualizado (action=$action). Verificando transferências pendentes e reativando agendamentos...")
        runCatching {
            com.resync.app.engine.ScheduleManager.getInstance(context).rescheduleAll()
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppRoomDatabase.getInstance(context)
                val pendingList = db.cloudTransferDao().getPendingTransfers()

                if (pendingList.isNotEmpty()) {
                    AppLogger.i(TAG, "Encontradas ${pendingList.size} transferências na nuvem pendentes de retomada.")
                    for (transfer in pendingList) {
                        AppLogger.i(TAG, "Retomando transferência: jobId=${transfer.jobId}, tipo=${transfer.type}, offset=${transfer.serverConfirmedOffset}/${transfer.totalBytes}")

                        // Release any stale locks held from previous boot
                        db.cloudTransferDao().releaseExecutorLock(transfer.jobId, transfer.executorLockOwner ?: "")

                        if (transfer.type == "UPLOAD") {
                            android.util.Log.d("BackupOrigin", "CloudBootReceiver: resuming UPLOAD transfer ${transfer.title}")
                            BackupForegroundService.startService(context, "Retomando ${transfer.title}...")
                        } else {
                            android.util.Log.d("BackupOrigin", "CloudBootReceiver: resuming DOWNLOAD transfer ${transfer.title}")
                            RestoreForegroundService.startService(context, "Retomando ${transfer.title}...")
                        }
                    }
                } else {
                    AppLogger.i(TAG, "Nenhuma transferência na nuvem pendente após reinício.")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Falha ao verificar transferências pendentes após reinício: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
