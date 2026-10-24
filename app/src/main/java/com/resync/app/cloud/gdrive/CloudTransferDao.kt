package com.resync.app.cloud.gdrive

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudTransferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(transfer: CloudTransferEntity)

    @Query("SELECT * FROM cloud_transfers WHERE jobId = :jobId LIMIT 1")
    suspend fun getTransfer(jobId: String): CloudTransferEntity?

    @Query("SELECT * FROM cloud_transfers WHERE state IN ('EmAndamento', 'AguardandoRede', 'PausadoPeloUsuario') ORDER BY updatedAt DESC")
    suspend fun getPendingTransfers(): List<CloudTransferEntity>

    @Query("SELECT * FROM cloud_transfers ORDER BY updatedAt DESC")
    fun getAllTransfersFlow(): Flow<List<CloudTransferEntity>>

    @Query("UPDATE cloud_transfers SET serverConfirmedOffset = :offset, activeTimeMs = :activeMs, idleTimeMs = :idleMs, currentSpeedMBps = :speed, updatedAt = :updatedAt WHERE jobId = :jobId")
    suspend fun updateProgress(jobId: String, offset: Long, activeMs: Long, idleMs: Long, speed: Float, updatedAt: Long)

    @Query("UPDATE cloud_transfers SET state = :state, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE jobId = :jobId")
    suspend fun updateState(jobId: String, state: String, errorMessage: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE cloud_transfers SET executorLockOwner = :lockOwner, executorLockTimestamp = :timestamp WHERE jobId = :jobId AND (executorLockOwner IS NULL OR executorLockOwner = :lockOwner OR executorLockTimestamp < :staleThreshold)")
    suspend fun acquireExecutorLock(jobId: String, lockOwner: String, timestamp: Long, staleThreshold: Long): Int

    @Query("UPDATE cloud_transfers SET executorLockOwner = NULL WHERE jobId = :jobId AND executorLockOwner = :lockOwner")
    suspend fun releaseExecutorLock(jobId: String, lockOwner: String)

    @Query("DELETE FROM cloud_transfers WHERE jobId = :jobId")
    suspend fun deleteTransfer(jobId: String)

    @Query("DELETE FROM cloud_transfers WHERE state = 'Concluido'")
    suspend fun clearCompleted()
}
