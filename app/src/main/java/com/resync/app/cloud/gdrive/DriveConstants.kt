package com.resync.app.cloud.gdrive

import com.resync.app.data.model.BackupPaths

object DriveConstants {
    /**
     * Official single root folder name in Google Drive.
     */
    const val ROOT_FOLDER_NAME = "ReSync"

    /**
     * Legacy root folder names used in previous versions, preserved for migration/discovery.
     */
    const val LEGACY_ROOT_FOLDER_RESYNC_BACKUPS = "ReSyncBackups"
    const val LEGACY_ROOT_FOLDER_RESYNC = "ReSync"
    const val LEGACY_ROOT_FOLDER_NAME = "SwiftVault"
    const val LEGACY_ROOT_FOLDER_NAME_ALT = "SwiftVault Backup"
    const val LEGACY_ROOT_FOLDER_NAME_OLD = "Rodin_Backup"

    /**
     * Official organized subfolders inside ReSync root folder.
     */
    const val SUBFOLDER_QUICK_BACKUPS = "Backups Rápidos"
    const val SUBFOLDER_FOLDERS = "Pastas"
    const val SUBFOLDER_FILES = "Arquivos"

    /**
     * List of recognized root folder names for locating existing backups.
     */
    val ALL_ROOT_FOLDER_NAMES = listOf(
        ROOT_FOLDER_NAME,
        LEGACY_ROOT_FOLDER_RESYNC_BACKUPS,
        LEGACY_ROOT_FOLDER_NAME,
        LEGACY_ROOT_FOLDER_NAME_ALT,
        LEGACY_ROOT_FOLDER_NAME_OLD
    )
}

