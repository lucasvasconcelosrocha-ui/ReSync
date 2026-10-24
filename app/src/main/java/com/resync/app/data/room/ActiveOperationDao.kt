package com.resync.app.data.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveOperationDao {

    @Query("SELECT * FROM room_active_operations ORDER BY updatedAt DESC")
    fun getAllOperations(): Flow<List<ActiveOperationEntity>>

    @Query("SELECT * FROM room_active_operations ORDER BY updatedAt DESC")
    suspend fun getAllOperationsList(): List<ActiveOperationEntity>

    @Query("SELECT * FROM room_active_operations WHERE id = :id LIMIT 1")
    suspend fun getOperationById(id: String): ActiveOperationEntity?

    @Query("SELECT * FROM room_active_operations WHERE status NOT IN ('COMPLETED', 'CANCELLED')")
    suspend fun getUnfinishedOperations(): List<ActiveOperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(operation: ActiveOperationEntity)

    @Query("DELETE FROM room_active_operations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM room_active_operations WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("DELETE FROM room_active_operations")
    suspend fun clearAll()

    @Query("UPDATE room_active_operations SET status = 'INTERRUPTED' WHERE status = 'RUNNING'")
    suspend fun markRunningAsInterrupted()
}

