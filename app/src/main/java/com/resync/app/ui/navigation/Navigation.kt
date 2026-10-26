package com.resync.app.ui.navigation

sealed class Screen(val route: String) {
    object InitialSetup : Screen("initial_setup")
    object Dashboard : Screen("dashboard")
    object AppsList : Screen("apps_list")
    object AppDetail : Screen("app_detail")
    object FileExplorer : Screen("file_explorer")
    object DnsSettings : Screen("dns_settings")
    object SystemSettings : Screen("system_settings")
    object Account : Screen("account")
    object CustomBackupWizard : Screen("custom_backup_wizard")
    object CloudSync : Screen("cloud_sync")
    object StorageDetails : Screen("storage_details")
    object ScheduledBackups : Screen("scheduled_backups")
    object Restore : Screen("restore?source={source}&autoSingle={autoSingle}") {
        fun createRoute(source: String = "all", autoSingle: Boolean = false) =
            "restore?source=$source&autoSingle=$autoSingle"
    }
    object RestoreSelection : Screen("restore_selection?source={source}&backupId={backupId}&driveFileId={driveFileId}") {
        fun createRoute(source: String, backupId: String, driveFileId: String = "") =
            "restore_selection?source=$source&backupId=$backupId&driveFileId=$driveFileId"
    }
    object Permissions : Screen("permissions")
    object Logs : Screen("logs")
    object ActiveBackups : Screen("active_backups?fromStarted={fromStarted}") {
        fun createRoute(fromStarted: Boolean = false) = "active_backups?fromStarted=$fromStarted"
    }
    object BackupManager : Screen("backup_manager?source={source}&autoSingle={autoSingle}") {
        fun createRoute(source: String = "all", autoSingle: Boolean = false) =
            "backup_manager?source=$source&autoSingle=$autoSingle"
    }
    object CategoryBackup : Screen("category_backup/{category}") {
        fun createRoute(category: String) = "category_backup/$category"
    }
    object FoldersBackup : Screen("folders_backup")
    object StorageCache : Screen("storage_cache")
    object StorageSystem : Screen("storage_system")
}
