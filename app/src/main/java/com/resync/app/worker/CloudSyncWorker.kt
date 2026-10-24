package com.resync.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resync.app.cloud.CloudStorageManager
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.resync.app.data.model.QueueStatus

/**
 * Worker that runs in background to drain pending cloud upload/download queue.
 */
class CloudSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dbHelper = AppDatabaseHelper.getInstance(appContext)
        val cloudManager = CloudStorageManager(appContext)

        val pendingItems = dbHelper.getAllQueueItems().filter {
            it.status == QueueStatus.PENDING || it.status == QueueStatus.RETRYING
        }

        if (pendingItems.isEmpty()) {
            return Result.success()
        }

        dbHelper.insertLog(LogLevel.INFO, "CloudSyncWorker", "Iniciando processamento de ${pendingItems.size} itens na fila de sincronização.")

        var hasFailures = false
        for (item in pendingItems) {
            val res = cloudManager.processQueueItem(item) { _, _ -> }
            if (res.isFailure) {
                hasFailures = true
            }
        }

        return if (hasFailures) Result.retry() else Result.success()
    }
}

