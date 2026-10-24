package com.resync.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resync.app.R
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.service.BackupForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager safety net worker.
 * Checks for unfinished backup operations in Room database and revives BackupForegroundService
 * if the process was terminated by aggressive OEM memory cleaners or system reboot.
 */
class BackupSafetyWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_TAG = "BackupSafetyWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val roomDb = AppRoomDatabase.getInstance(appContext)
            val unfinished = roomDb.activeOperationDao().getUnfinishedOperations()
                .filter { it.type == "BACKUP" && it.status != "CANCELLED" }

            android.util.Log.d("BackupOrigin", "BackupSafetyWorker checked unfinished operations: count=${unfinished.size}, isServiceRunning=${BackupForegroundService.isServiceRunning}")

            if (unfinished.isNotEmpty() && !BackupForegroundService.isServiceRunning) {
                for (op in unfinished) {
                    roomDb.activeOperationDao().insertOrUpdate(op.copy(
                        status = "INTERRUPTED",
                        stage = "Interrompido",
                        error = "A operação foi interrompida antes da conclusão (processo finalizado)."
                    ))
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

