package com.resync.app.data.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.model.*
import com.resync.app.util.AppLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Robust, native SQLite database manager for SwiftVault.
 * Ensures zero compiler-plugin friction, fast startup, and bulletproof transactional integrity.
 */
class AppDatabaseHelper(private val context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    private val gson = Gson()
    private val _dbChanges = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val dbChanges: SharedFlow<String> = _dbChanges.asSharedFlow()

    companion object {
        private const val DATABASE_NAME = "swiftvault.db"
        private const val DATABASE_VERSION = 1

        @Volatile
        private var instance: AppDatabaseHelper? = null

        fun getInstance(context: Context): AppDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AppDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Table: backup_records
        db.execSQL("""
            CREATE TABLE backup_records (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                file_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                checksum_sha256 TEXT NOT NULL,
                is_encrypted INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                item_count INTEGER NOT NULL,
                status TEXT NOT NULL,
                apps_count INTEGER NOT NULL DEFAULT 0,
                files_count INTEGER NOT NULL DEFAULT 0,
                has_dns INTEGER NOT NULL DEFAULT 0,
                destination TEXT NOT NULL DEFAULT 'Local',
                sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                drive_file_id TEXT
            )
        """.trimIndent())

        // Table: cloud_accounts
        db.execSQL("""
            CREATE TABLE cloud_accounts (
                provider_type TEXT PRIMARY KEY,
                account_name TEXT NOT NULL,
                is_connected INTEGER NOT NULL,
                quota_used INTEGER NOT NULL DEFAULT 0,
                quota_total INTEGER NOT NULL DEFAULT 0,
                last_sync INTEGER NOT NULL DEFAULT 0,
                files_count INTEGER NOT NULL DEFAULT 0,
                errors_count INTEGER NOT NULL DEFAULT 0,
                server_host TEXT,
                server_port INTEGER DEFAULT 0,
                username TEXT,
                auth_token TEXT
            )
        """.trimIndent())

        // Table: sync_queue
        db.execSQL("""
            CREATE TABLE sync_queue (
                id TEXT PRIMARY KEY,
                backup_id TEXT NOT NULL,
                backup_title TEXT NOT NULL,
                provider_type TEXT NOT NULL,
                direction TEXT NOT NULL,
                status TEXT NOT NULL,
                progress_bytes INTEGER NOT NULL DEFAULT 0,
                total_bytes INTEGER NOT NULL DEFAULT 0,
                error_code TEXT,
                error_message TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                account_email TEXT
            )
        """.trimIndent())

        // Table: backup_filters
        db.execSQL("""
            CREATE TABLE backup_filters (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                included_extensions TEXT NOT NULL,
                min_size_bytes INTEGER NOT NULL,
                max_age_days INTEGER NOT NULL,
                excluded_patterns TEXT NOT NULL
            )
        """.trimIndent())

        // Table: system_logs
        db.execSQL("""
            CREATE TABLE system_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                level TEXT NOT NULL,
                tag TEXT NOT NULL,
                message TEXT NOT NULL,
                details TEXT
            )
        """.trimIndent())

        // Table: bluetooth_history
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bluetooth_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mac_address TEXT NOT NULL,
                device_name TEXT NOT NULL,
                event_type TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                details TEXT
            )
        """.trimIndent())

        // Table: bluetooth_devices (persistidos para consulta offline/BT desligado)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bluetooth_devices (
                mac_address TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                device_type TEXT,
                major_class TEXT,
                bond_state TEXT,
                last_seen INTEGER NOT NULL
            )
        """.trimIndent())

        // Table: bluetooth_alias
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bluetooth_alias (
                mac_address TEXT PRIMARY KEY,
                alias TEXT NOT NULL
            )
        """.trimIndent())

        // Seed initial default filters
        seedDefaultFilters(db)
        seedCloudProviders(db)
    }

    private fun seedDefaultFilters(db: SQLiteDatabase) {
        val defaultFilters = listOf(
            BackupFilter(
                id = "filter_media",
                name = "Fotos e Vídeos",
                includedExtensions = listOf(".jpg", ".jpeg", ".png", ".mp4", ".mov", ".heic"),
                minSizeBytes = 0L,
                maxAgeDays = 0,
                excludedPatterns = listOf("*.tmp", "*.thumb", "cache/*")
            ),
            BackupFilter(
                id = "filter_docs",
                name = "Documentos Importantes",
                includedExtensions = listOf(".pdf", ".docx", ".xlsx", ".pptx", ".txt"),
                minSizeBytes = 0L,
                maxAgeDays = 0,
                excludedPatterns = listOf("*.tmp")
            ),
            BackupFilter(
                id = "filter_heavy",
                name = "Arquivos Maiores que 100 MB",
                includedExtensions = emptyList(),
                minSizeBytes = 100 * 1024 * 1024L,
                maxAgeDays = 0,
                excludedPatterns = listOf("*.tmp", "*.cache")
            ),
            BackupFilter(
                id = "filter_recent",
                name = "Arquivos Recentes (Últimos 30 dias)",
                includedExtensions = emptyList(),
                minSizeBytes = 0L,
                maxAgeDays = 30,
                excludedPatterns = listOf("*.tmp", "*.cache")
            )
        )

        for (filter in defaultFilters) {
            val cv = ContentValues().apply {
                put("id", filter.id)
                put("name", filter.name)
                put("included_extensions", gson.toJson(filter.includedExtensions))
                put("min_size_bytes", filter.minSizeBytes)
                put("max_age_days", filter.maxAgeDays)
                put("excluded_patterns", gson.toJson(filter.excludedPatterns))
            }
            db.insert("backup_filters", null, cv)
        }
    }

    private fun seedCloudProviders(db: SQLiteDatabase) {
        for (provider in CloudProviderType.values()) {
            val cv = ContentValues().apply {
                put("provider_type", provider.name)
                put("account_name", "${provider.displayName} (Não configurado)")
                put("is_connected", 0)
                put("quota_used", 0)
                put("quota_total", 0)
                put("last_sync", 0)
                put("files_count", 0)
                put("errors_count", 0)
            }
            db.insert("cloud_accounts", null, cv)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            db.execSQL("ALTER TABLE sync_queue ADD COLUMN account_email TEXT")
        } catch (_: Exception) {}
        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bluetooth_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mac_address TEXT NOT NULL,
                    device_name TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    details TEXT
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bluetooth_alias (
                    mac_address TEXT PRIMARY KEY,
                    alias TEXT NOT NULL
                )
            """.trimIndent())
        } catch (_: Exception) {}
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            db.execSQL("ALTER TABLE backup_records ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE backup_records ADD COLUMN drive_file_id TEXT")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE sync_queue ADD COLUMN account_email TEXT")
        } catch (_: Exception) {}
        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bluetooth_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mac_address TEXT NOT NULL,
                    device_name TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    details TEXT
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bluetooth_alias (
                    mac_address TEXT PRIMARY KEY,
                    alias TEXT NOT NULL
                )
            """.trimIndent())
        } catch (_: Exception) {}
    }

    // ==========================================
    // BACKUP RECORDS DAO
    // ==========================================
    suspend fun insertBackupRecord(record: BackupRecord) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", record.id)
            put("title", record.title)
            put("file_path", record.filePath)
            put("size_bytes", record.sizeBytes)
            put("checksum_sha256", record.checksumSha256)
            put("is_encrypted", if (record.isEncrypted) 1 else 0)
            put("timestamp", record.timestamp)
            put("item_count", record.itemCount)
            put("status", record.status.name)
            put("apps_count", record.includedAppsCount)
            put("files_count", record.includedFilesCount)
            put("has_dns", if (record.includedDns) 1 else 0)
            put("destination", record.destination)
            put("sync_status", record.syncStatus.name)
            put("drive_file_id", record.driveFileId)
        }
        writableDatabase.insertWithOnConflict("backup_records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _dbChanges.tryEmit("backup_records")
    }

    suspend fun updateBackupStatus(id: String, status: BackupStatus) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("status", status.name)
        }
        writableDatabase.update("backup_records", cv, "id = ?", arrayOf(id))
        _dbChanges.tryEmit("backup_records")
    }

    suspend fun updateBackupSyncStatus(id: String, syncStatus: SyncStatus, driveFileId: String? = null) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("sync_status", syncStatus.name)
            if (driveFileId != null) {
                put("drive_file_id", driveFileId)
            }
        }
        writableDatabase.update("backup_records", cv, "id = ?", arrayOf(id))
        _dbChanges.tryEmit("backup_records")
    }

    suspend fun getAllBackupRecords(): List<BackupRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<BackupRecord>()
        val cursor = readableDatabase.query(
            "backup_records", null, null, null, null, null, "timestamp DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToBackupRecord(it))
            }
        }
        list
    }

    suspend fun getBackupRecordById(id: String): BackupRecord? = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.query(
            "backup_records", null, "id = ?", arrayOf(id), null, null, null
        )
        cursor.use {
            if (it.moveToNext()) cursorToBackupRecord(it) else null
        }
    }

    suspend fun getBackupRecordByDriveFileId(driveFileId: String): BackupRecord? = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.query(
            "backup_records", null, "drive_file_id = ?", arrayOf(driveFileId), null, null, null
        )
        cursor.use {
            if (it.moveToNext()) cursorToBackupRecord(it) else null
        }
    }

    suspend fun deleteBackupRecord(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("backup_records", "id = ? OR drive_file_id = ?", arrayOf(id, id))
        _dbChanges.tryEmit("backup_records")
    }

    suspend fun deleteBackupRecordByDriveFileId(driveFileId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("backup_records", "drive_file_id = ?", arrayOf(driveFileId))
        _dbChanges.tryEmit("backup_records")
    }

    private fun cursorToBackupRecord(cursor: Cursor): BackupRecord {
        val syncStatusCol = cursor.getColumnIndex("sync_status")
        val syncStatus = if (syncStatusCol >= 0 && !cursor.isNull(syncStatusCol)) {
            try { SyncStatus.valueOf(cursor.getString(syncStatusCol)) } catch (_: Exception) { SyncStatus.LOCAL_ONLY }
        } else SyncStatus.LOCAL_ONLY

        val driveFileIdCol = cursor.getColumnIndex("drive_file_id")
        val driveFileId = if (driveFileIdCol >= 0 && !cursor.isNull(driveFileIdCol)) cursor.getString(driveFileIdCol) else null

        return BackupRecord(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            filePath = cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
            checksumSha256 = cursor.getString(cursor.getColumnIndexOrThrow("checksum_sha256")),
            isEncrypted = cursor.getInt(cursor.getColumnIndexOrThrow("is_encrypted")) == 1,
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            itemCount = cursor.getInt(cursor.getColumnIndexOrThrow("item_count")),
            status = BackupStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            includedAppsCount = cursor.getInt(cursor.getColumnIndexOrThrow("apps_count")),
            includedFilesCount = cursor.getInt(cursor.getColumnIndexOrThrow("files_count")),
            includedDns = cursor.getInt(cursor.getColumnIndexOrThrow("has_dns")) == 1,
            destination = cursor.getString(cursor.getColumnIndexOrThrow("destination")),
            syncStatus = syncStatus,
            driveFileId = driveFileId
        )
    }

    // ==========================================
    // CLOUD ACCOUNTS DAO
    // ==========================================
    suspend fun getAllCloudAccounts(): List<CloudAccount> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CloudAccount>()
        val cursor = readableDatabase.query("cloud_accounts", null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                val providerType = CloudProviderType.valueOf(it.getString(it.getColumnIndexOrThrow("provider_type")))
                list.add(
                    CloudAccount(
                        providerType = providerType,
                        accountName = it.getString(it.getColumnIndexOrThrow("account_name")),
                        isConnected = it.getInt(it.getColumnIndexOrThrow("is_connected")) == 1,
                        quotaUsedBytes = it.getLong(it.getColumnIndexOrThrow("quota_used")),
                        quotaTotalBytes = it.getLong(it.getColumnIndexOrThrow("quota_total")),
                        lastSyncTimestamp = it.getLong(it.getColumnIndexOrThrow("last_sync")),
                        filesCount = it.getInt(it.getColumnIndexOrThrow("files_count")),
                        errorsCount = it.getInt(it.getColumnIndexOrThrow("errors_count")),
                        serverHost = it.getString(it.getColumnIndexOrThrow("server_host")),
                        serverPort = it.getInt(it.getColumnIndexOrThrow("server_port")),
                        username = it.getString(it.getColumnIndexOrThrow("username")),
                        authTokenOrSecret = it.getString(it.getColumnIndexOrThrow("auth_token"))
                    )
                )
            }
        }
        list
    }

    suspend fun updateCloudAccount(account: CloudAccount) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("account_name", account.accountName)
            put("is_connected", if (account.isConnected) 1 else 0)
            put("quota_used", account.quotaUsedBytes)
            put("quota_total", account.quotaTotalBytes)
            put("last_sync", account.lastSyncTimestamp)
            put("files_count", account.filesCount)
            put("errors_count", account.errorsCount)
            put("server_host", account.serverHost)
            put("server_port", account.serverPort)
            put("username", account.username)
            put("auth_token", account.authTokenOrSecret)
        }
        writableDatabase.update("cloud_accounts", cv, "provider_type = ?", arrayOf(account.providerType.name))
        _dbChanges.tryEmit("cloud_accounts")
    }

    suspend fun updateCloudAccountQuota(providerName: String, usedBytes: Long, totalBytes: Long) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("quota_used", usedBytes)
            put("quota_total", totalBytes)
            put("last_sync", System.currentTimeMillis())
        }
        writableDatabase.update("cloud_accounts", cv, "provider_type = ?", arrayOf(providerName))
        _dbChanges.tryEmit("cloud_accounts")
    }

    // ==========================================
    // SYNC QUEUE DAO
    // ==========================================
    suspend fun getAllQueueItems(): List<CloudSyncQueueItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CloudSyncQueueItem>()
        val cursor = readableDatabase.query(
            "sync_queue", null, null, null, null, null, "priority DESC, created_at ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToQueueItem(it))
            }
        }
        list
    }

    suspend fun insertQueueItem(item: CloudSyncQueueItem) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", item.id)
            put("backup_id", item.backupId)
            put("backup_title", item.backupTitle)
            put("provider_type", item.providerType.name)
            put("direction", item.direction.name)
            put("status", item.status.name)
            put("progress_bytes", item.progressBytes)
            put("total_bytes", item.totalBytes)
            put("error_code", item.errorCode)
            put("error_message", item.errorMessage)
            put("retry_count", item.retryCount)
            put("created_at", item.createdAt)
            put("priority", item.priority)
            if (item.accountEmail != null) put("account_email", item.accountEmail)
        }
        writableDatabase.insertWithOnConflict("sync_queue", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _dbChanges.tryEmit("sync_queue")
    }

    suspend fun updateQueueItemStatus(
        id: String,
        status: QueueStatus,
        progress: Long = 0L,
        errorCode: String? = null,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("status", status.name)
            if (progress > 0) put("progress_bytes", progress)
            if (errorCode != null) put("error_code", errorCode)
            if (errorMessage != null) put("error_message", errorMessage)
        }
        writableDatabase.update("sync_queue", cv, "id = ?", arrayOf(id))
        _dbChanges.tryEmit("sync_queue")
    }

    suspend fun prioritizeQueueItem(id: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("priority", 100)
        }
        writableDatabase.update("sync_queue", cv, "id = ?", arrayOf(id))
        _dbChanges.tryEmit("sync_queue")
    }

    suspend fun removeQueueItem(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("sync_queue", "id = ?", arrayOf(id))
        _dbChanges.tryEmit("sync_queue")
    }

    suspend fun clearQueue(accountEmail: String? = null) = withContext(Dispatchers.IO) {
        if (accountEmail != null) {
            writableDatabase.delete("sync_queue", "account_email = ? OR account_email IS NULL", arrayOf(accountEmail))
        } else {
            writableDatabase.delete("sync_queue", null, null)
        }
        _dbChanges.tryEmit("sync_queue")
    }

    suspend fun clearCompletedQueue(accountEmail: String? = null) = withContext(Dispatchers.IO) {
        if (accountEmail != null) {
            writableDatabase.delete("sync_queue", "(status = 'COMPLETED' OR status = 'FAILED') AND (account_email = ? OR account_email IS NULL)", arrayOf(accountEmail))
        } else {
            writableDatabase.delete("sync_queue", "status = 'COMPLETED' OR status = 'FAILED'", null)
        }
        _dbChanges.tryEmit("sync_queue")
    }

    suspend fun resetRunningQueueItems(asError: Boolean = false, errorMessage: String? = null) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            if (asError) {
                put("status", QueueStatus.FAILED.name)
                put("error_code", "INTERRUPTED")
                put("error_message", errorMessage ?: "Interrompido por reinicialização abrupta do app.")
            } else {
                put("status", QueueStatus.PENDING.name)
            }
        }
        writableDatabase.update("sync_queue", cv, "status = 'RUNNING'", null)
        _dbChanges.tryEmit("sync_queue")
    }

    private fun cursorToQueueItem(cursor: Cursor): CloudSyncQueueItem {
        return CloudSyncQueueItem(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            backupId = cursor.getString(cursor.getColumnIndexOrThrow("backup_id")),
            backupTitle = cursor.getString(cursor.getColumnIndexOrThrow("backup_title")),
            providerType = CloudProviderType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("provider_type"))),
            direction = TransferDirection.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("direction"))),
            status = QueueStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            progressBytes = cursor.getLong(cursor.getColumnIndexOrThrow("progress_bytes")),
            totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow("total_bytes")),
            errorCode = cursor.getString(cursor.getColumnIndexOrThrow("error_code")),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
            accountEmail = cursor.getColumnIndex("account_email").let { if (it >= 0 && !cursor.isNull(it)) cursor.getString(it) else null }
        )
    }

    // ==========================================
    // BACKUP FILTERS DAO
    // ==========================================
    suspend fun getAllFilters(): List<BackupFilter> = withContext(Dispatchers.IO) {
        val list = mutableListOf<BackupFilter>()
        val cursor = readableDatabase.query("backup_filters", null, null, null, null, null, "name ASC")
        val stringListType = object : TypeToken<List<String>>() {}.type
        cursor.use {
            while (it.moveToNext()) {
                val extensionsJson = it.getString(it.getColumnIndexOrThrow("included_extensions"))
                val exclusionsJson = it.getString(it.getColumnIndexOrThrow("excluded_patterns"))
                list.add(
                    BackupFilter(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        includedExtensions = gson.fromJson(extensionsJson, stringListType) ?: emptyList(),
                        minSizeBytes = it.getLong(it.getColumnIndexOrThrow("min_size_bytes")),
                        maxAgeDays = it.getInt(it.getColumnIndexOrThrow("max_age_days")),
                        excludedPatterns = gson.fromJson(exclusionsJson, stringListType) ?: emptyList()
                    )
                )
            }
        }
        list
    }

    suspend fun insertFilter(filter: BackupFilter) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", filter.id)
            put("name", filter.name)
            put("included_extensions", gson.toJson(filter.includedExtensions))
            put("min_size_bytes", filter.minSizeBytes)
            put("max_age_days", filter.maxAgeDays)
            put("excluded_patterns", gson.toJson(filter.excludedPatterns))
        }
        writableDatabase.insertWithOnConflict("backup_filters", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _dbChanges.tryEmit("backup_filters")
    }

    suspend fun deleteFilter(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("backup_filters", "id = ?", arrayOf(id))
        _dbChanges.tryEmit("backup_filters")
    }

    // ==========================================
    // SYSTEM LOGS DAO
    // ==========================================
    suspend fun insertLog(level: LogLevel, tag: String, message: String, details: String? = null) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sanitizedMsg = AppLogger.maskSensitive(message)
        val sanitizedDetails = details?.let { AppLogger.maskSensitive(it) }
        val cv = ContentValues().apply {
            put("timestamp", now)
            put("level", level.name)
            put("tag", tag)
            put("message", sanitizedMsg)
            put("details", sanitizedDetails)
        }
        writableDatabase.insert("system_logs", null, cv)
        _dbChanges.tryEmit("system_logs")

        // Persistência em arquivo físico em ReSyncBackups/logs
        try {
            val logDirs = listOf(
                File(com.resync.app.data.model.BackupPaths.getDefaultLocalBackupDir(), "logs"),
                File(context.getExternalFilesDir(null), "logs"),
                File(context.filesDir, "logs")
            )
            val logDir = logDirs.firstOrNull { it.exists() || it.mkdirs() }
            if (logDir != null) {
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
                val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(now))
                val logFile = File(logDir, "resync_$dateStr.log")
                val detailStr = if (sanitizedDetails != null) " | Detalhes: $sanitizedDetails" else ""
                val line = "[$timeStr] [${level.name}] [$tag] $sanitizedMsg$detailStr\n"
                logFile.appendText(line)
            }
        } catch (_: Throwable) {}
    }

    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun insertLogAsync(level: LogLevel, tag: String, message: String, details: String? = null) {
        dbScope.launch {
            insertLog(level, tag, message, details)
        }
    }

    suspend fun getRecentLogs(limit: Int = 100): List<LogEntry> = withContext(Dispatchers.IO) {
        val list = mutableListOf<LogEntry>()
        val cursor = readableDatabase.query(
            "system_logs", null, null, null, null, null, "timestamp DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    LogEntry(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        level = LogLevel.valueOf(it.getString(it.getColumnIndexOrThrow("level"))),
                        tag = it.getString(it.getColumnIndexOrThrow("tag")),
                        message = it.getString(it.getColumnIndexOrThrow("message")),
                        details = it.getString(it.getColumnIndexOrThrow("details"))
                    )
                )
            }
        }
        list
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        writableDatabase.delete("system_logs", null, null)
        _dbChanges.tryEmit("system_logs")
    }

    // ==========================================
    // BLUETOOTH HISTORY & ALIAS DAO
    // ==========================================
    suspend fun insertBluetoothEvent(
        macAddress: String,
        deviceName: String,
        eventType: String,
        details: String? = null
    ) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("mac_address", macAddress)
            put("device_name", deviceName)
            put("event_type", eventType)
            put("timestamp", System.currentTimeMillis())
            put("details", details)
        }
        writableDatabase.insert("bluetooth_history", null, cv)
        _dbChanges.tryEmit("bluetooth_history")
    }

    suspend fun getBluetoothHistory(macAddress: String? = null, limit: Int = 200): List<BluetoothHistoryEvent> = withContext(Dispatchers.IO) {
        val list = mutableListOf<BluetoothHistoryEvent>()
        val selection = if (macAddress != null) "mac_address = ?" else null
        val selectionArgs = if (macAddress != null) arrayOf(macAddress) else null

        val cursor = readableDatabase.query(
            "bluetooth_history",
            null,
            selection,
            selectionArgs,
            null,
            null,
            "timestamp DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    BluetoothHistoryEvent(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        address = it.getString(it.getColumnIndexOrThrow("mac_address")),
                        deviceName = it.getString(it.getColumnIndexOrThrow("device_name")),
                        event = it.getString(it.getColumnIndexOrThrow("event_type")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        details = it.getString(it.getColumnIndexOrThrow("details"))
                    )
                )
            }
        }
        list
    }

    suspend fun deleteDeviceBluetoothHistory(macAddress: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("bluetooth_history", "mac_address = ?", arrayOf(macAddress))
        _dbChanges.tryEmit("bluetooth_history")
    }

    suspend fun clearAllBluetoothHistory() = withContext(Dispatchers.IO) {
        writableDatabase.delete("bluetooth_history", null, null)
        _dbChanges.tryEmit("bluetooth_history")
    }

    suspend fun setBluetoothAlias(macAddress: String, alias: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("mac_address", macAddress)
            put("alias", alias)
        }
        writableDatabase.insertWithOnConflict(
            "bluetooth_alias",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        _dbChanges.tryEmit("bluetooth_alias")
    }

    suspend fun getAllBluetoothAliases(): Map<String, String> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, String>()
        val cursor = readableDatabase.query("bluetooth_alias", null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                val mac = it.getString(it.getColumnIndexOrThrow("mac_address"))
                val alias = it.getString(it.getColumnIndexOrThrow("alias"))
                map[mac] = alias
            }
        }
        map
    }

    suspend fun upsertBluetoothDevice(
        macAddress: String,
        name: String,
        deviceType: String?,
        majorClass: String?,
        bondState: String?
    ) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("mac_address", macAddress)
            put("device_name", name)
            put("device_type", deviceType)
            put("major_class", majorClass)
            put("bond_state", bondState)
            put("last_seen", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("bluetooth_devices", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _dbChanges.tryEmit("bluetooth_devices")
    }

    suspend fun getAllPersistedBluetoothDevices(): List<BluetoothDeviceInfo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<BluetoothDeviceInfo>()
        val aliases = getAllBluetoothAliases()
        val allHistory = getBluetoothHistory(null, limit = 1000)

        val cursor = readableDatabase.query("bluetooth_devices", null, null, null, null, null, "last_seen DESC")
        cursor.use {
            while (it.moveToNext()) {
                val mac = it.getString(it.getColumnIndexOrThrow("mac_address"))
                val name = it.getString(it.getColumnIndexOrThrow("device_name"))
                val devType = it.getString(it.getColumnIndexOrThrow("device_type")) ?: "Desconhecido"
                val majorClass = it.getString(it.getColumnIndexOrThrow("major_class")) ?: "Genérico"
                val bondState = it.getString(it.getColumnIndexOrThrow("bond_state")) ?: "Pareado"

                val deviceEvents = allHistory.filter { ev -> ev.address.equals(mac, ignoreCase = true) }
                val firstSeen = deviceEvents.minOfOrNull { ev -> ev.timestamp } ?: 0L
                val lastConn = deviceEvents.firstOrNull { ev -> ev.event == "CONECTADO" }?.timestamp ?: 0L
                val connCount = deviceEvents.count { ev -> ev.event == "CONECTADO" }

                list.add(
                    BluetoothDeviceInfo(
                        name = name,
                        alias = aliases[mac],
                        address = mac,
                        isConnected = false,
                        isBonded = true,
                        deviceType = devType,
                        majorClass = majorClass,
                        bondState = bondState,
                        batteryLevel = null,
                        supportedProfiles = emptyList(),
                        connectedProfiles = emptyList(),
                        uuids = emptyList(),
                        audioCodec = null,
                        firstSeenTimestamp = firstSeen,
                        lastConnectedTimestamp = lastConn,
                        totalConnectionCount = connCount,
                        totalConnectedDurationSec = 0L
                    )
                )
            }
        }
        list
    }
}

