package com.resync.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BackupIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: BackupIndexCacheEntity)

    @Query("SELECT * FROM room_backup_indexes WHERE backupId = :backupId LIMIT 1")
    suspend fun getByBackupId(backupId: String): BackupIndexCacheEntity?

    @Query("SELECT * FROM room_backup_indexes WHERE driveFileId = :driveFileId LIMIT 1")
    suspend fun getByDriveFileId(driveFileId: String): BackupIndexCacheEntity?

    @Query("SELECT * FROM room_backup_indexes WHERE filePath = :filePath LIMIT 1")
    suspend fun getByFilePath(filePath: String): BackupIndexCacheEntity?

    @Query("SELECT * FROM room_backup_indexes WHERE backupTitle = :title LIMIT 1")
    suspend fun getByTitle(title: String): BackupIndexCacheEntity?

    @Query("DELETE FROM room_backup_indexes WHERE backupId = :backupId")
    suspend fun deleteByBackupId(backupId: String)

    @Query("DELETE FROM room_backup_indexes WHERE driveFileId = :driveFileId")
    suspend fun deleteByDriveFileId(driveFileId: String)

    @Query("SELECT * FROM room_backup_indexes")
    suspend fun getAll(): List<BackupIndexCacheEntity>

    @Query("DELETE FROM room_backup_indexes")
    suspend fun clearAll()
}
