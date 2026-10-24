package com.resync.app.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.resync.app.cloud.gdrive.CloudTransferDao
import com.resync.app.cloud.gdrive.CloudTransferEntity

@Database(
    entities = [
        CloudAccount::class,
        ActiveOperationEntity::class,
        BackupIndexCacheEntity::class,
        CloudTransferEntity::class,
        OperationHistoryEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppRoomDatabase : RoomDatabase() {

    abstract fun cloudAccountDao(): CloudAccountDao
    abstract fun activeOperationDao(): ActiveOperationDao
    abstract fun backupIndexDao(): BackupIndexDao
    abstract fun cloudTransferDao(): CloudTransferDao
    abstract fun operationHistoryDao(): OperationHistoryDao

    companion object {
        @Volatile
        private var instance: AppRoomDatabase? = null

        fun getInstance(context: Context): AppRoomDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppRoomDatabase::class.java,
                    "swiftvault_room.db"
                ).fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
