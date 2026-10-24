package com.resync.app.data.model

import android.content.Context
import android.os.Environment
import com.resync.app.data.database.AppDatabaseHelper
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Centralized repository for all backup paths, folder names and migration routines.
 * Ensures consistent usage of the "ReSync" and "ReSyncBackups" branding while
 * seamlessly preserving full backward compatibility with legacy "SwiftVault" backups.
 */
object BackupPaths {

    /**
     * Primary backup folder name on internal storage.
     */
    const val BACKUP_DIR_NAME = "ReSyncBackups"

    /**
     * Legacy backup folder names on internal storage, recognized for migration and reading.
     */
    const val LEGACY_BACKUP_DIR_NAME = "SwiftVaultBackups"
    const val LEGACY_BACKUP_DIR_NAME_ALT = "SwiftVault"

    /**
     * Primary root folder name in Google Drive.
     */
    const val DRIVE_ROOT_FOLDER_NAME = "ReSync"

    /**
     * Legacy root folder names in Google Drive, recognized for migration and reading.
     */
    val LEGACY_DRIVE_FOLDER_NAMES = listOf("ReSyncBackups", "SwiftVault", "SwiftVault Backup", "Rodin_Backup")

    /**
     * Official file extension and prefixes for ReSync backup containers.
     */
    const val BACKUP_FILE_EXTENSION = ".resync"
    const val LEGACY_BACKUP_FILE_EXTENSION = ".svb"
    const val ZIP_FILE_EXTENSION = ".zip"

    const val BACKUP_FILE_PREFIX = "resync_"
    const val LEGACY_BACKUP_FILE_PREFIX = "svb_"

    /**
     * Returns the primary local backup directory: /storage/emulated/0/ReSyncBackups.
     */
    fun getDefaultLocalBackupDir(): File {
        val extStorage = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        return File(extStorage, BACKUP_DIR_NAME)
    }

    /**
     * Returns all potential local backup directories (new and legacy) for reading existing backups.
     */
    fun getAllLocalBackupDirs(context: Context? = null): List<File> {
        val baseExt = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        val dirs = mutableListOf(
            File(baseExt, BACKUP_DIR_NAME),
            File(baseExt, LEGACY_BACKUP_DIR_NAME),
            File(baseExt, LEGACY_BACKUP_DIR_NAME_ALT)
        )
        if (context != null) {
            context.getExternalFilesDir(null)?.let {
                dirs.add(File(it, BACKUP_DIR_NAME))
                dirs.add(File(it, LEGACY_BACKUP_DIR_NAME))
            }
            dirs.add(File(context.filesDir, BACKUP_DIR_NAME))
            dirs.add(File(context.filesDir, LEGACY_BACKUP_DIR_NAME))
        }
        return dirs.distinctBy { it.absolutePath }
    }

    /**
     * Checks if a given file or directory represents a backup archive or backup container directory.
     */
    fun isBackupDirectory(file: File): Boolean {
        val name = file.name
        return name.equals(BACKUP_DIR_NAME, ignoreCase = true) ||
                name.equals(LEGACY_BACKUP_DIR_NAME, ignoreCase = true) ||
                name.equals(LEGACY_BACKUP_DIR_NAME_ALT, ignoreCase = true)
    }

    /**
     * Checks if a file is a valid backup container by name extension (.resync, .svb or .zip).
     */
    fun isBackupContainerFile(file: File): Boolean {
        val name = file.name
        return name.endsWith(BACKUP_FILE_EXTENSION, ignoreCase = true) ||
                name.endsWith(LEGACY_BACKUP_FILE_EXTENSION, ignoreCase = true) ||
                name.endsWith(ZIP_FILE_EXTENSION, ignoreCase = true)
    }

    /**
     * Migrates local backup folder from legacy name (SwiftVaultBackups / SwiftVault) to ReSyncBackups.
     * Renames the folder atomically using root (mv) or File.renameTo if root is not active,
     * updates SQLite database records, and guarantees no backup files are lost.
     * Never triggers an automatic backup or sync.
     */
    suspend fun migrateLocalBackupFolders(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseExt = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            val targetDir = File(baseExt, BACKUP_DIR_NAME)
            val legacyDir = File(baseExt, LEGACY_BACKUP_DIR_NAME)
            val legacyAltDir = File(baseExt, LEGACY_BACKUP_DIR_NAME_ALT)

            val dirToMigrate = when {
                legacyDir.exists() && legacyDir.isDirectory -> legacyDir
                legacyAltDir.exists() && legacyAltDir.isDirectory -> legacyAltDir
                else -> null
            }

            var migrated = false

            if (dirToMigrate != null && dirToMigrate.exists()) {
                if (!targetDir.exists()) {
                    // Try direct rename first
                    val renamed = dirToMigrate.renameTo(targetDir)
                    if (!renamed) {
                        // Fallback to root mv
                        val cmd = Shell.cmd("mv \"${dirToMigrate.absolutePath}\" \"${targetDir.absolutePath}\"").exec()
                        migrated = cmd.isSuccess && targetDir.exists()
                    } else {
                        migrated = true
                    }
                } else {
                    // Both directories exist: move contents of legacy into new directory without overwriting
                    val legacyFiles = dirToMigrate.listFiles() ?: emptyArray()
                    for (file in legacyFiles) {
                        val destFile = File(targetDir, file.name)
                        if (!destFile.exists()) {
                            val moved = file.renameTo(destFile)
                            if (!moved) {
                                Shell.cmd("mv \"${file.absolutePath}\" \"${destFile.absolutePath}\"").exec()
                            }
                        }
                    }
                    // Remove legacy folder if now empty
                    if (dirToMigrate.listFiles().isNullOrEmpty()) {
                        dirToMigrate.delete()
                    }
                    migrated = true
                }
            }

            // Always update database records to reference the new path if any old paths remain
            val db = AppDatabaseHelper.getInstance(context)
            try {
                val dbWritable = db.writableDatabase
                dbWritable.execSQL("""
                    UPDATE backup_records 
                    SET file_path = REPLACE(file_path, '$LEGACY_BACKUP_DIR_NAME', '$BACKUP_DIR_NAME') 
                    WHERE file_path LIKE '%$LEGACY_BACKUP_DIR_NAME%'
                """.trimIndent())
                dbWritable.execSQL("""
                    UPDATE backup_records 
                    SET file_path = REPLACE(file_path, '$LEGACY_BACKUP_DIR_NAME_ALT', '$BACKUP_DIR_NAME') 
                    WHERE file_path LIKE '%$LEGACY_BACKUP_DIR_NAME_ALT%'
                """.trimIndent())
            } catch (_: Throwable) {}

            migrated
        } catch (_: Throwable) {
            false
        }
    }
}
