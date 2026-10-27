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

    /**
     * Locates a matching local backup container file (.resync, .svb, .zip) on the device
     * based on backup ID, file title, drive file ID, or expected size.
     * Recursively inspects all known backup directories and nested device/label subfolders.
     */
    fun findLocalMirrorFile(
        backupId: String? = null,
        title: String? = null,
        context: Context? = null,
        expectedSizeBytes: Long = 0L,
        driveFileId: String? = null
    ): File? {
        val allDirs = getAllLocalBackupDirs(context)
        val candidateFiles = mutableListOf<File>()
        for (dir in allDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().maxDepth(4).forEach { f ->
                if (f.isFile && f.length() > 0L && isBackupContainerFile(f)) {
                    candidateFiles.add(f)
                }
            }
        }
        if (candidateFiles.isEmpty()) return null

        // 1. Direct match by title or name
        if (!title.isNullOrBlank()) {
            val cleanTitle = title.trim()
            val baseTitle = cleanTitle.removeSuffix(BACKUP_FILE_EXTENSION)
                .removeSuffix(LEGACY_BACKUP_FILE_EXTENSION)
                .removeSuffix(ZIP_FILE_EXTENSION)

            // Direct file name match
            candidateFiles.firstOrNull {
                it.name.equals(cleanTitle, ignoreCase = true) ||
                it.nameWithoutExtension.equals(baseTitle, ignoreCase = true)
            }?.let { return it }

            // Parent directory matches title/label (e.g. 2026-10-26_20-53-52_Xiaomi_24129RT7CC)
            candidateFiles.firstOrNull {
                it.parentFile?.name.equals(cleanTitle, ignoreCase = true) ||
                it.parentFile?.name.equals(baseTitle, ignoreCase = true)
            }?.let { return it }

            // Numeric/timestamp substring match (e.g. 1793058017154 or 2026-10-26)
            val timestampPattern = Regex("""\d{10,14}|\d{4}-\d{2}-\d{2}""")
            val titleTimestamp = timestampPattern.find(cleanTitle)?.value
            if (!titleTimestamp.isNullOrBlank()) {
                candidateFiles.firstOrNull {
                    it.name.contains(titleTimestamp) || (it.parentFile?.name?.contains(titleTimestamp) == true)
                }?.let { return it }
            }
        }

        // 2. Match by backupId
        if (!backupId.isNullOrBlank()) {
            candidateFiles.firstOrNull {
                it.name.contains(backupId, ignoreCase = true) || (it.parentFile?.name?.contains(backupId, ignoreCase = true) == true)
            }?.let { return it }
        }

        // 3. Match by size (within 2MB tolerance)
        if (expectedSizeBytes > 1024 * 1024) {
            candidateFiles.firstOrNull {
                Math.abs(it.length() - expectedSizeBytes) < 2 * 1024 * 1024
            }?.let { return it }
        }

        // 4. Fallback: Largest container file on device (> 50MB), newest first
        val largeContainers = candidateFiles.filter { it.length() > 50 * 1024 * 1024 }
        if (largeContainers.isNotEmpty()) {
            return largeContainers.maxByOrNull { it.lastModified() }
        }

        // 5. Absolute fallback: newest container file
        return candidateFiles.maxByOrNull { it.lastModified() }
    }
}
