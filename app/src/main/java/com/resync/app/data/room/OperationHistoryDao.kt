package com.resync.app.data.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: OperationHistoryEntity)

    @Query("SELECT * FROM operation_history ORDER BY endTimestamp DESC")
    fun getAllHistoryFlow(): Flow<List<OperationHistoryEntity>>

    @Query("SELECT * FROM operation_history ORDER BY endTimestamp DESC LIMIT 50")
    suspend fun getRecentHistory(): List<OperationHistoryEntity>

    @Query("SELECT * FROM operation_history WHERE id = :id LIMIT 1")
    suspend fun getHistoryById(id: String): OperationHistoryEntity?

    @Query("DELETE FROM operation_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM operation_history")
    suspend fun clearAll()
}
