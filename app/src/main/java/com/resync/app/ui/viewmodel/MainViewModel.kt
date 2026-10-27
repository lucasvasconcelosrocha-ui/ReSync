package com.resync.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resync.app.cloud.CloudStorageManager
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.*
import com.resync.app.engine.*
import com.resync.app.service.BackupForegroundService
import com.resync.app.updater.*
import com.topjohnwu.superuser.Shell
import android.util.Log
import android.app.usage.StorageStatsManager
import android.os.storage.StorageManager
import android.os.Process
import com.resync.app.util.FormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import com.resync.app.cloud.gdrive.DriveAuthResult
import com.resync.app.cloud.gdrive.DriveConstants
import com.resync.app.cloud.gdrive.DriveRepository
import com.resync.app.worker.FileUploadWorker
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.data.room.CloudAccount as RoomCloudAccount
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

enum class GoogleDriveState {
    NOT_CONNECTED,   // ⚪ Não conectado
    CONNECTING,      // 🔵 Conectando...
    CONNECTED,       // 🟢 Conectado
    SYNCING,         // 🔵 Sincronizando...
    SESSION_EXPIRED, // 🟠 Reconectar
    CONNECTION_ERROR // 🔴 Erro
}

sealed class DriveQuotaState {
    object Idle : DriveQuotaState()
    object Loading : DriveQuotaState()
    data class Loaded(
        val totalBytes: Long,
        val usedBytes: Long,
        val availableBytes: Long,
        val userEmail: String?,
        val displayName: String?
    ) : DriveQuotaState()
    data class Error(val message: String, val technicalCode: String? = null) : DriveQuotaState()
}

sealed class RemoteBackupsState {
    object Idle : RemoteBackupsState()
    object Loading : RemoteBackupsState()
    data class Loaded(val backups: List<com.resync.app.cloud.gdrive.DriveFileMetadata>) : RemoteBackupsState()
    data class Error(val message: String, val technicalCode: String? = null) : RemoteBackupsState()
}

sealed class ManifestInspectState {
    object Idle : ManifestInspectState()
    object InspectingLocal : ManifestInspectState()
    data class DownloadingFromDrive(
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L,
        val progressPercent: Float = 0f,
        val stage: String = "Lendo índice do Google Drive..."
    ) : ManifestInspectState()
    data class Ready(val manifest: BackupManifest, val fileToRestore: File?) : ManifestInspectState()
    data class Error(val message: String, val canRetry: Boolean = true) : ManifestInspectState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val iconCache = IconCacheManager.getInstance(context)
    private val dnsManager = DnsManager(context)
    private val capabilityManager = CapabilityManager(context)
    private val backupEngine = BackupEngine(context)
    private val restoreEngine = RestoreEngine(context)
    val backupIndexManager by lazy {
        val gdriveProvider = com.resync.app.cloud.providers.GoogleDriveProvider(context)
        gdriveProvider.cloudBackupRepository.backupIndexManager
    }
    private val installedAppsRepo = InstalledAppsRepository.getInstance(context)
    val cloudManager = CloudStorageManager(context)

    private val prefs = context.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)

    // Current Theme Accent
    private val _themeAccent = MutableStateFlow(
        ThemeAccent.values().getOrNull(prefs.getInt("theme_accent", 0)) ?: ThemeAccent.BLUE
    )
    val themeAccent: StateFlow<ThemeAccent> = _themeAccent.asStateFlow()

    // Shared Cache Bytes & List for Storage Cache, Storage Details and Dashboard broom (Fonte Única de Verdade)
    val totalAppCacheBytes: StateFlow<Long> = AppCacheRepository.totalAppCacheBytes
    val appCacheList: StateFlow<List<AppCacheItem>> = AppCacheRepository.appCacheList
    val isCacheScanning: StateFlow<Boolean> = AppCacheRepository.isCacheScanning
    val cacheAnalyzedCount: StateFlow<Int> = AppCacheRepository.cacheAnalyzedCount
    val cacheTotalAppsCount: StateFlow<Int> = AppCacheRepository.cacheTotalAppsCount
    val isClearingCache: StateFlow<Boolean> = AppCacheRepository.isClearingCache
    val lastCacheFreedMessage: StateFlow<String?> = AppCacheRepository.lastCacheFreedMessage

    fun dismissLastCacheFreedMessage() {
        AppCacheRepository.dismissLastCacheFreedMessage()
    }

    /**
     * Scans installed applications cache using real root directory measurement in batch.
     */
    fun scanAppCache(force: Boolean = false) {
        val apps = if (installedApps.value.isNotEmpty()) installedApps.value else {
            refreshInstalledApps(force = false)
            installedApps.value
        }
        AppCacheRepository.scanAppCache(context, apps, force)
    }

    /**
     * Clears single application cache via root and updates StateFlow immediately.
     */
    fun clearSingleAppCache(item: AppCacheItem) {
        AppCacheRepository.clearSingleAppCache(item)
    }

    /**
     * Clears all apps cache via root in batched commands and updates StateFlow immediately.
     */
    fun clearAllAppCache() {
        AppCacheRepository.clearAllAppCache()
    }

    // Custom Device Label & Multi-Label Manager
    private fun loadSavedLabels(): List<String> {
        val defaultId = com.resync.app.util.FormatUtils.getDeviceIdentifier(context)
        val currentCustom = prefs.getString("custom_device_label", null) ?: defaultId
        val savedSet = prefs.getStringSet("saved_device_labels_set", null) ?: setOf(defaultId)
        return (savedSet + defaultId + currentCustom).filter { it.isNotBlank() }.distinct().sorted()
    }

    private val _savedDeviceLabels = MutableStateFlow(loadSavedLabels())
    val savedDeviceLabels: StateFlow<List<String>> = _savedDeviceLabels.asStateFlow()

    private val _customDeviceLabel = MutableStateFlow(
        prefs.getString("custom_device_label", null) ?: com.resync.app.util.FormatUtils.getDeviceIdentifier(context)
    )
    val customDeviceLabel: StateFlow<String> = _customDeviceLabel.asStateFlow()

    private val _selectedViewLabel = MutableStateFlow(
        prefs.getString("selected_view_label", null) ?: _customDeviceLabel.value
    )
    val selectedViewLabel: StateFlow<String> = _selectedViewLabel.asStateFlow()

    fun setSelectedViewLabel(label: String) {
        val trimmed = label.trim()
        if (trimmed.isNotBlank()) {
            _selectedViewLabel.value = trimmed
            prefs.edit().putString("selected_view_label", trimmed).apply()
        }
    }

    fun setCustomDeviceLabel(label: String) {
        val trimmed = label.trim()
        if (trimmed.isNotBlank()) {
            prefs.edit().putString("custom_device_label", trimmed).apply()
            _customDeviceLabel.value = trimmed
            _selectedViewLabel.value = trimmed
            prefs.edit().putString("selected_view_label", trimmed).apply()
            val updated = (_savedDeviceLabels.value + trimmed).distinct().sorted()
            _savedDeviceLabels.value = updated
            prefs.edit().putStringSet("saved_device_labels_set", updated.toSet()).apply()
        }
    }

    fun createDeviceLabel(newLabel: String, setAsActiveForNewBackups: Boolean) {
        val trimmed = newLabel.trim()
        if (trimmed.isNotBlank()) {
            val updated = (_savedDeviceLabels.value + trimmed).distinct().sorted()
            _savedDeviceLabels.value = updated
            prefs.edit().putStringSet("saved_device_labels_set", updated.toSet()).apply()
            _selectedViewLabel.value = trimmed
            prefs.edit().putString("selected_view_label", trimmed).apply()
            if (setAsActiveForNewBackups) {
                _customDeviceLabel.value = trimmed
                prefs.edit().putString("custom_device_label", trimmed).apply()
            }
        }
    }

    fun renameDeviceLabel(oldLabel: String, newLabel: String, onFinished: (Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldTrimmed = oldLabel.trim()
            val newTrimmed = newLabel.trim()
            if (newTrimmed.isBlank() || oldTrimmed == newTrimmed) return@launch

            val updatedSet = _savedDeviceLabels.value.map { if (it.equals(oldTrimmed, ignoreCase = true)) newTrimmed else it }.distinct().sorted()
            _savedDeviceLabels.value = updatedSet
            prefs.edit().putStringSet("saved_device_labels_set", updatedSet.toSet()).apply()

            if (_customDeviceLabel.value.equals(oldTrimmed, ignoreCase = true)) {
                _customDeviceLabel.value = newTrimmed
                prefs.edit().putString("custom_device_label", newTrimmed).apply()
            }
            if (_selectedViewLabel.value.equals(oldTrimmed, ignoreCase = true)) {
                _selectedViewLabel.value = newTrimmed
                prefs.edit().putString("selected_view_label", newTrimmed).apply()
            }

            val activeEmail = _activeAccountEmail.value
            val currentRemote = _remoteDriveBackups.value
            val targets = currentRemote.filter { file ->
                val prop = file.appProperties["deviceLabel"]?.trim()
                prop?.equals(oldTrimmed, ignoreCase = true) == true ||
                        (prop.isNullOrBlank() && file.name.contains(oldTrimmed, ignoreCase = true))
            }

            val updatedRemote = currentRemote.map { file ->
                if (targets.any { it.id == file.id }) {
                    val newProps = file.appProperties.toMutableMap().apply { put("deviceLabel", newTrimmed) }
                    file.copy(appProperties = newProps)
                } else file
            }
            _remoteDriveBackups.value = updatedRemote
            _remoteBackupsState.value = RemoteBackupsState.Loaded(updatedRemote)

            var updatedCount = 0
            for (file in targets) {
                val patchRes = gdriveProvider.cloudBackupRepository.updateRemoteBackupProperties(
                    fileId = file.id,
                    properties = mapOf("deviceLabel" to newTrimmed),
                    accountEmail = activeEmail
                )
                if (patchRes.isSuccess) updatedCount++
            }

            refreshActiveAccountData(activeEmail, isManual = true)
            withContext(Dispatchers.Main) {
                onFinished(updatedCount)
            }
        }
    }

    fun deleteDeviceLabel(label: String, onFinished: (Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = label.trim()
            deleteRemoteBackupsForLabel(trimmed) { deletedCount ->
                val remaining = _savedDeviceLabels.value.filterNot { it.equals(trimmed, ignoreCase = true) }
                val defaultLabel = com.resync.app.util.FormatUtils.getDeviceIdentifier(context)
                val fallback = remaining.firstOrNull() ?: defaultLabel
                val finalLabels = (remaining + fallback).distinct().sorted()
                _savedDeviceLabels.value = finalLabels
                prefs.edit().putStringSet("saved_device_labels_set", finalLabels.toSet()).apply()

                if (_selectedViewLabel.value.equals(trimmed, ignoreCase = true)) {
                    _selectedViewLabel.value = fallback
                    prefs.edit().putString("selected_view_label", fallback).apply()
                }
                if (_customDeviceLabel.value.equals(trimmed, ignoreCase = true)) {
                    _customDeviceLabel.value = fallback
                    prefs.edit().putString("custom_device_label", fallback).apply()
                }
                onFinished(deletedCount)
            }
        }
    }

    fun createUnifiedEntryFromDriveFile(rem: com.resync.app.cloud.gdrive.DriveFileMetadata): UnifiedBackupEntry {
        val remoteTitle = rem.appProperties["title"] ?: rem.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
        val r = BackupRecord(
            id = rem.id,
            title = remoteTitle,
            filePath = "",
            sizeBytes = rem.sizeBytes,
            checksumSha256 = rem.md5Checksum ?: "",
            isEncrypted = rem.appProperties["isEncrypted"]?.toBooleanStrictOrNull() ?: false,
            timestamp = rem.modifiedTime,
            itemCount = rem.appProperties["count_apps"]?.toIntOrNull() ?: 0,
            status = BackupStatus.COMPLETED,
            destination = "Google Drive",
            syncStatus = SyncStatus.SYNCED,
            driveFileId = rem.id
        )
        return UnifiedBackupEntry(record = r, hasLocal = false, hasDrive = true, remoteFileId = rem.id)
    }

    // Military Grade AES-256-GCM Security & Encryption
    private val _cryptoStrategy = MutableStateFlow(prefs.getString("crypto_strategy", "ACCOUNT") ?: "ACCOUNT")
    val cryptoStrategy: StateFlow<String> = _cryptoStrategy.asStateFlow()

    fun setCryptoStrategy(strategy: String) {
        prefs.edit().putString("crypto_strategy", strategy).apply()
        _cryptoStrategy.value = strategy
    }

    fun saveAdvancedPassword(password: String) {
        val encrypted = SecurityManager.encryptHardwareString(password)
        prefs.edit().putString("crypto_adv_pass", encrypted).apply()
    }

    fun getAdvancedPassword(): String? {
        val enc = prefs.getString("crypto_adv_pass", null) ?: return null
        return runCatching { SecurityManager.decryptHardwareString(enc) }.getOrNull()
    }

    fun testPassphrase(password: String): Boolean {
        return SecurityManager.testPassphrase(password)
    }

    // Performance Mode — removed from UI; optimization is now native and automatic.
    // Kept as no-op stub for binary compatibility during transition.
    @Deprecated("Mode selection removed. Performance is always native/high-speed.")
    val performanceMode: StateFlow<String> = MutableStateFlow("FAST").asStateFlow()

    @Deprecated("Mode selection removed. Performance is always native/high-speed.")
    fun setPerformanceMode(mode: String) {
        // No-op: PerformanceConfig always runs at maximum safe speed with auto thermal control.
    }

    // Apps list (Backed by InstalledAppsRepository with instant cache and batch loading)
    val installedApps: StateFlow<List<AppInfo>> = installedAppsRepo.appsFlow
    val isLoadingInstalledApps: StateFlow<Boolean> = installedAppsRepo.isLoadingFlow

    private val roomDb by lazy { AppRoomDatabase.getInstance(context) }
    val appBackupDetector by lazy {
        AppBackupDetector(context, roomDb, backupIndexManager, dbHelper)
    }

    private val _appBackupStatusMap = MutableStateFlow<Map<String, AppBackupStatus>>(emptyMap())
    val appBackupStatusMap: StateFlow<Map<String, AppBackupStatus>> = _appBackupStatusMap.asStateFlow()

    private val _appBackupPointsMap = MutableStateFlow<Map<String, List<AppBackupPoint>>>(emptyMap())
    val appBackupPointsMap: StateFlow<Map<String, List<AppBackupPoint>>> = _appBackupPointsMap.asStateFlow()

    fun getBackupPointsForApp(packageName: String): List<AppBackupPoint> {
        return _appBackupPointsMap.value[packageName]
            ?: appBackupDetector.getBackupPointsForApp(packageName)
    }

    fun createBackupRecordFromPoint(point: AppBackupPoint): BackupRecord {
        return BackupRecord(
            id = point.backupId,
            title = point.backupTitle,
            filePath = point.filePath,
            sizeBytes = if (point.containerSizeBytes > 0L) point.containerSizeBytes else point.appSizeBytes,
            checksumSha256 = "",
            isEncrypted = false,
            timestamp = point.timestamp,
            itemCount = point.itemIds.size.coerceAtLeast(1),
            status = BackupStatus.COMPLETED,
            includedAppsCount = 1,
            destination = if (point.isLocal && point.isDrive) "Local + Nuvem" else if (point.isLocal) "Local" else "Drive",
            syncStatus = if (point.isDrive) SyncStatus.SYNCED else SyncStatus.LOCAL_ONLY,
            driveFileId = point.driveFileId
        )
    }

    fun triggerAppBackupDetection(forceRefreshDrive: Boolean = false) {
        viewModelScope.launch {
            val pkgs = installedApps.value.map { it.packageName }
            val remotes = _remoteDriveBackups.value
            val result = appBackupDetector.detectBackups(
                installedPackages = pkgs,
                remoteDriveBackups = remotes,
                triggerDriveRefresh = {
                    val email = _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
                    if (!email.isNullOrBlank()) {
                        refreshActiveAccountData(targetEmail = email, isManual = true)
                    }
                },
                forceRefreshDrive = forceRefreshDrive
            )
            _appBackupStatusMap.value = result
            _appBackupPointsMap.value = appBackupDetector.getAllBackupPoints()
        }
    }

    fun refreshInstalledApps(force: Boolean = true) {
        installedAppsRepo.refreshApps(force)
        triggerAppBackupDetection(forceRefreshDrive = true)
    }

    // Toggle showing internal system packages (SDK Sandbox, AdServices, Overlays)
    private val _showInternalSystemPackages = MutableStateFlow(
        prefs.getBoolean("show_internal_system_packages", false)
    )
    val showInternalSystemPackages: StateFlow<Boolean> = _showInternalSystemPackages.asStateFlow()

    fun setShowInternalSystemPackages(show: Boolean) {
        _showInternalSystemPackages.value = show
        prefs.edit().putBoolean("show_internal_system_packages", show).apply()
    }

    // Toggle for including app cache in data backups
    private val _includeAppCache = MutableStateFlow(
        prefs.getBoolean("backup_include_cache", false)
    )
    val includeAppCache: StateFlow<Boolean> = _includeAppCache.asStateFlow()

    fun setIncludeAppCache(include: Boolean) {
        _includeAppCache.value = include
        prefs.edit().putBoolean("backup_include_cache", include).apply()
    }

    private val _selectedAppForDetail = MutableStateFlow<AppInfo?>(null)
    val selectedAppForDetail: StateFlow<AppInfo?> = _selectedAppForDetail.asStateFlow()

    // Google Play Store pending updates inspector
    private val playStoreUpdateManager = com.resync.app.engine.PlayStoreUpdateManager.getInstance(context)
    val playStoreUpdatesState: StateFlow<com.resync.app.engine.PlayStoreUpdatesResult> = playStoreUpdateManager.updatesState

    fun refreshPlayStoreUpdates() {
        playStoreUpdateManager.refreshUpdates()
    }

    // Backups history
    private val _backupRecords = MutableStateFlow<List<BackupRecord>>(emptyList())
    val backupRecords: StateFlow<List<BackupRecord>> = _backupRecords.asStateFlow()

    // Preselected File Category for FileExplorer
    private val _preselectedFileCategory = MutableStateFlow<com.resync.app.engine.FileCategory?>(null)
    val preselectedFileCategory: StateFlow<com.resync.app.engine.FileCategory?> = _preselectedFileCategory.asStateFlow()

    fun setPreselectedFileCategory(category: com.resync.app.engine.FileCategory?) {
        _preselectedFileCategory.value = category
    }

    // Cloud Accounts & Queue
    private val _cloudAccounts = MutableStateFlow<List<CloudAccount>>(emptyList())
    val cloudAccounts: StateFlow<List<CloudAccount>> = _cloudAccounts.asStateFlow()

    private val _driveQuota = MutableStateFlow<com.resync.app.cloud.gdrive.DriveStorageQuota?>(null)
    val driveQuota: StateFlow<com.resync.app.cloud.gdrive.DriveStorageQuota?> = _driveQuota.asStateFlow()

    private val _syncQueue = MutableStateFlow<List<CloudSyncQueueItem>>(emptyList())
    val syncQueue: StateFlow<List<CloudSyncQueueItem>> = _syncQueue.asStateFlow()

    private val _lastCrashLog = MutableStateFlow<String?>(null)
    val lastCrashLog: StateFlow<String?> = _lastCrashLog.asStateFlow()

    private val _historicalProcessExitReasons = MutableStateFlow<List<ProcessExitInfoModel>>(emptyList())
    val historicalProcessExitReasons: StateFlow<List<ProcessExitInfoModel>> = _historicalProcessExitReasons.asStateFlow()

    private val _previousProcessExitBanner = MutableStateFlow<ProcessExitInfoModel?>(null)
    val previousProcessExitBanner: StateFlow<ProcessExitInfoModel?> = _previousProcessExitBanner.asStateFlow()

    fun dismissProcessExitBanner() {
        val banner = _previousProcessExitBanner.value
        if (banner != null) {
            prefs.edit().putLong("last_dismissed_exit_timestamp", banner.timestamp).apply()
        }
        _previousProcessExitBanner.value = null
    }

    private val _isNotificationBlocked = MutableStateFlow(false)
    val isNotificationBlocked: StateFlow<Boolean> = _isNotificationBlocked.asStateFlow()

    // Google Drive direct provider access
    val gdriveProvider = cloudManager.googleDriveProvider

    private val _remoteDriveBackups = MutableStateFlow<List<com.resync.app.cloud.gdrive.DriveFileMetadata>>(emptyList())
    val remoteDriveBackups: StateFlow<List<com.resync.app.cloud.gdrive.DriveFileMetadata>> = _remoteDriveBackups.asStateFlow()

    private val networkDetailsManager = NetworkDetailsManager(context)
    val bluetoothDetailsManager = com.resync.app.engine.BluetoothDetailsManager(context)
    val deviceHardwareManager = com.resync.app.engine.DeviceHardwareManager(context)

    private val _wifiDetails = MutableStateFlow(
        WifiConnectionDetails(isConnected = false, ssid = "Carregando...", bssid = "N/A", wifiPasswordStatus = "Carregando...")
    )
    val wifiDetails: StateFlow<WifiConnectionDetails> = _wifiDetails.asStateFlow()

    private val _activeNetworkDetails = MutableStateFlow(
        ActiveNetworkDetails(type = ActiveNetworkType.NONE, isConnected = false)
    )
    val activeNetworkDetails: StateFlow<ActiveNetworkDetails> = _activeNetworkDetails.asStateFlow()

    private val _dataUsageStats = MutableStateFlow(NetworkDataUsageStats())
    val dataUsageStats: StateFlow<NetworkDataUsageStats> = _dataUsageStats.asStateFlow()

    private val _bluetoothDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val bluetoothDevices: StateFlow<List<BluetoothDeviceInfo>> = _bluetoothDevices.asStateFlow()

    private val _bluetoothHistory = MutableStateFlow<List<BluetoothHistoryEvent>>(emptyList())
    val bluetoothHistory: StateFlow<List<BluetoothHistoryEvent>> = _bluetoothHistory.asStateFlow()

    private val _deviceHardwareSpec = MutableStateFlow(DeviceHardwareSpecification())
    val deviceHardwareSpec: StateFlow<DeviceHardwareSpecification> = _deviceHardwareSpec.asStateFlow()

    private val _syncSettings = MutableStateFlow(com.resync.app.cloud.gdrive.AutoSyncSettings())
    val syncSettings: StateFlow<com.resync.app.cloud.gdrive.AutoSyncSettings> = _syncSettings.asStateFlow()

    // Diagnostic Runner State
    private val _diagnosticResults = MutableStateFlow<List<CloudDiagnosticResult>>(emptyList())
    val diagnosticResults: StateFlow<List<CloudDiagnosticResult>> = _diagnosticResults.asStateFlow()

    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()

    // Backup Job & Pause Control
    val isBackupPaused: StateFlow<Boolean> = BackupRepository.isBackupPaused

    // Backup Progress & State Flows (Single source of truth via BackupRepository)
    val backupProgress: StateFlow<BackupProgress?> = BackupRepository.backupProgress
    val backupExecutionState: StateFlow<com.resync.app.engine.BackupExecutionState> = BackupRepository.executionState
    val backupSummary: StateFlow<com.resync.app.engine.BackupSummaryData?> = BackupRepository.backupSummary
    val backupCompletionEvent: kotlinx.coroutines.flow.SharedFlow<com.resync.app.engine.BackupSummaryData> = BackupRepository.backupCompletionEvent

    // Restore Progress Flow (Delegated to RestoreRepository)
    val restoreProgress: StateFlow<RestoreProgress?> = RestoreRepository.restoreProgress
    val restoreExecutionState: StateFlow<RestoreExecutionState> = RestoreRepository.executionState
    val restoreSummary: StateFlow<RestoreSummaryData?> = RestoreRepository.restoreSummary

    // Unified Background Operations (Single Source of Truth via OperationRepository)
    val activeOperations: StateFlow<List<com.resync.app.engine.Operacao>> = com.resync.app.engine.OperationRepository.operations
    val completedOperations: StateFlow<List<com.resync.app.engine.Operacao>> = com.resync.app.engine.OperationRepository.completedOperations

    fun pauseOperation(operationId: String) {
        com.resync.app.engine.OperationRepository.optimisticPause(operationId)
    }

    fun resumeOperation(operationId: String) {
        com.resync.app.engine.OperationRepository.optimisticResume(operationId)
    }

    fun cancelOperation(operationId: String) {
        com.resync.app.engine.OperationRepository.optimisticCancel(operationId)
        // Also cancel WorkManager job if it's an upload
        if (operationId.startsWith("upload_")) {
            try {
                val wm = androidx.work.WorkManager.getInstance(context)
                wm.cancelAllWorkByTag(operationId)
            } catch (_: Throwable) {}
        }
    }

    fun dismissOperation(operationId: String) {
        com.resync.app.engine.OperationRepository.dismiss(operationId)
    }

    fun dismissProgress() {
        com.resync.app.engine.OperationRepository.dismiss(com.resync.app.engine.OperationRepository.BACKUP_OPERATION_ID)
        com.resync.app.engine.OperationRepository.dismiss(com.resync.app.engine.OperationRepository.RESTORE_OPERATION_ID)
        BackupRepository.dismissProgress()
        RestoreRepository.dismissRestoreStatus()
    }

    fun cancelRestore() {
        com.resync.app.engine.OperationRepository.optimisticCancel(com.resync.app.engine.OperationRepository.RESTORE_OPERATION_ID)
        RestoreRepository.cancelRestore()
    }

    fun retryRestore() {
        RestoreRepository.retryRestore()
    }

    private val _manifestInspectState = MutableStateFlow<ManifestInspectState>(ManifestInspectState.Idle)
    val manifestInspectState: StateFlow<ManifestInspectState> = _manifestInspectState.asStateFlow()

    fun resetManifestInspectState() {
        _manifestInspectState.value = ManifestInspectState.Idle
    }

    // Device Stats
    private val _deviceStats = MutableStateFlow(DeviceStats())
    val deviceStats: StateFlow<DeviceStats> = _deviceStats.asStateFlow()

    // Active DNS Config
    private val _dnsConfig = MutableStateFlow(DnsConfig(isActive = false, mode = "Automático", hostname = null, dnsServers = emptyList()))
    val dnsConfig: StateFlow<DnsConfig> = _dnsConfig.asStateFlow()

    // System Logs
    private val _systemLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val systemLogs: StateFlow<List<LogEntry>> = _systemLogs.asStateFlow()

    // Filters
    private val _backupFilters = MutableStateFlow<List<BackupFilter>>(emptyList())
    val backupFilters: StateFlow<List<BackupFilter>> = _backupFilters.asStateFlow()

    // Conflicts
    private val _detectedConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val detectedConflicts: StateFlow<List<SyncConflict>> = _detectedConflicts.asStateFlow()

    // Google Drive Enhanced States
    private val _googleDriveState = MutableStateFlow(
        if (gdriveProvider.oAuthManager.isConnected()) {
            if (gdriveProvider.oAuthManager.isSessionExpired()) GoogleDriveState.SESSION_EXPIRED else GoogleDriveState.CONNECTED
        } else GoogleDriveState.NOT_CONNECTED
    )
    val googleDriveState: StateFlow<GoogleDriveState> = _googleDriveState.asStateFlow()

    private val _driveQuotaState = MutableStateFlow<DriveQuotaState>(DriveQuotaState.Idle)
    val driveQuotaState: StateFlow<DriveQuotaState> = _driveQuotaState.asStateFlow()

    private val _remoteBackupsState = MutableStateFlow<RemoteBackupsState>(RemoteBackupsState.Idle)
    val remoteBackupsState: StateFlow<RemoteBackupsState> = _remoteBackupsState.asStateFlow()

    // Unified Permissions Repository
    val permissionsRepository = com.resync.app.engine.PermissionsRepository.getInstance(context)
    val permissionsState: StateFlow<com.resync.app.engine.PermissionsState> = permissionsRepository.permissionsState

    // Root granular capability state
    private val _rootAccessStatus = MutableStateFlow(CapabilityManager.RootStatus.UNAUTHORIZED)
    val rootAccessStatus: StateFlow<CapabilityManager.RootStatus> = _rootAccessStatus.asStateFlow()

    private val _isRequestingRoot = MutableStateFlow(false)
    val isRequestingRoot: StateFlow<Boolean> = _isRequestingRoot.asStateFlow()

    // In-app updater
    val appUpdateManager = AppUpdateManager(context)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _isAutoUpdateCheckEnabled = MutableStateFlow(appUpdateManager.isAutoCheckEnabled())
    val isAutoUpdateCheckEnabled: StateFlow<Boolean> = _isAutoUpdateCheckEnabled.asStateFlow()

    // Capability state
    private val _activeMode = MutableStateFlow(CapabilityManager.ExecutionMode.STANDARD)
    val activeMode: StateFlow<CapabilityManager.ExecutionMode> = _activeMode.asStateFlow()

    // Quick Backup granular storage item selection (retained across configuration changes / navigation)
    private val _quickBackupSelectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val quickBackupSelectedPaths: StateFlow<Set<String>> = _quickBackupSelectedPaths.asStateFlow()

    private val _quickBackupFolderSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val quickBackupFolderSizes: StateFlow<Map<String, Long>> = _quickBackupFolderSizes.asStateFlow()

    private val _quickBackupCalculatingPaths = MutableStateFlow<Set<String>>(emptySet())
    val quickBackupCalculatingPaths: StateFlow<Set<String>> = _quickBackupCalculatingPaths.asStateFlow()

    private val folderSizeCalcJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    // Multi-Account Google Drive (Room + DriveRepository)
    // Multi-Account Google Drive (Room + DriveRepository)
    val driveRepository = DriveRepository(context)

    data class AccountLiveCache(
        val email: String,
        val quotaState: DriveQuotaState,
        val backupsState: RemoteBackupsState,
        val googleDriveState: GoogleDriveState,
        val reauthIntent: Intent? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val accountCache = java.util.concurrent.ConcurrentHashMap<String, AccountLiveCache>()
    private val refreshJobsByEmail = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val lastAutoRefreshByEmail = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val refreshExecutionCounter = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val accountPreferences = com.resync.app.data.preferences.AccountPreferences(context)

    private val _multiCloudAccounts = MutableStateFlow<List<RoomCloudAccount>>(emptyList())
    val multiCloudAccounts: StateFlow<List<RoomCloudAccount>> = _multiCloudAccounts.asStateFlow()

    // Single source of truth for the active account
    private val _activeAccountEmail = MutableStateFlow<String?>(prefs.getString("active_account_email", null))
    val activeAccountEmail: StateFlow<String?> = _activeAccountEmail.asStateFlow()

    // Backward compatibility alias for existing code
    val selectedDriveAccountEmail: StateFlow<String?> = _activeAccountEmail.asStateFlow()

    private val _activeAccountReauthIntent = MutableStateFlow<Intent?>(null)
    val activeAccountReauthIntent: StateFlow<Intent?> = _activeAccountReauthIntent.asStateFlow()

    private val _isRefreshingActiveAccount = MutableStateFlow(false)
    val isRefreshingActiveAccount: StateFlow<Boolean> = _isRefreshingActiveAccount.asStateFlow()

    // Google Drive Multi-Account Auth State
    private val _isAuthorizingAccount = MutableStateFlow(false)
    val isAuthorizingAccount: StateFlow<Boolean> = _isAuthorizingAccount.asStateFlow()

    private val _driveAuthErrorMessage = MutableStateFlow<String?>(null)
    val driveAuthErrorMessage: StateFlow<String?> = _driveAuthErrorMessage.asStateFlow()

    private val _cloudResetTrigger = MutableStateFlow(0)
    val cloudResetTrigger: StateFlow<Int> = _cloudResetTrigger.asStateFlow()

    fun resetCloudInternalState() {
        _cloudResetTrigger.value += 1
        clearDriveAuthError()
    }

    fun clearDriveAuthError() {
        _driveAuthErrorMessage.value = null
    }

    private fun applyCachedOrRoomAccountData(email: String) {
        val cached = accountCache[email]
        if (cached != null) {
            _driveQuotaState.value = cached.quotaState
            _remoteBackupsState.value = cached.backupsState
            _googleDriveState.value = cached.googleDriveState
            _activeAccountReauthIntent.value = cached.reauthIntent
            if (cached.backupsState is RemoteBackupsState.Loaded) {
                _remoteDriveBackups.value = cached.backupsState.backups
            }
            return
        }

        val roomAcc = _multiCloudAccounts.value.find { it.email == email }
        if (roomAcc != null) {
            _googleDriveState.value = GoogleDriveState.CONNECTED
            val total = if (roomAcc.quotaTotalBytes > 0) roomAcc.quotaTotalBytes else 15L * 1024 * 1024 * 1024
            val used = roomAcc.quotaUsedBytes
            val available = maxOf(0L, total - used)
            _driveQuotaState.value = DriveQuotaState.Loaded(
                totalBytes = total,
                usedBytes = used,
                availableBytes = available,
                userEmail = roomAcc.email,
                displayName = roomAcc.name
            )
            _remoteBackupsState.value = RemoteBackupsState.Loading
            _activeAccountReauthIntent.value = null
        }
    }

    init {
        _appBackupStatusMap.value = appBackupDetector.loadPersistedCache()
        checkCrashLogOnStartup()
        loadData()
        observeDatabaseChanges()
        checkForUpdateOnStartup()
        refreshNotificationStatus()

        viewModelScope.launch {
            installedApps.collectLatest { apps ->
                if (apps.isNotEmpty()) {
                    triggerAppBackupDetection(forceRefreshDrive = false)
                }
            }
        }

        viewModelScope.launch {
            driveRepository.allAccounts
                .distinctUntilChanged { old, new ->
                    old.size == new.size && old.map { it.email to it.isDefault } == new.map { it.email to it.isDefault }
                }
                .collect { list ->
                    _multiCloudAccounts.value = list
                    val savedEmail = _activeAccountEmail.value ?: prefs.getString("active_account_email", null)
                    val validEmail = if (savedEmail != null && list.any { it.email == savedEmail }) {
                        savedEmail
                    } else {
                        list.find { it.isDefault }?.email ?: list.firstOrNull()?.email
                    }

                    val emailChanged = validEmail != _activeAccountEmail.value
                    if (emailChanged) {
                        _activeAccountEmail.value = validEmail
                        prefs.edit().putString("active_account_email", validEmail).apply()
                    }

                    if (validEmail != null) {
                        applyCachedOrRoomAccountData(validEmail)
                        if (emailChanged || _driveQuotaState.value !is DriveQuotaState.Loaded) {
                            refreshActiveAccountData(validEmail, isManual = false)
                        }
                    } else {
                        _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
                        _driveQuotaState.value = DriveQuotaState.Idle
                        _remoteBackupsState.value = RemoteBackupsState.Idle
                        _activeAccountReauthIntent.value = null
                    }
                }
        }
        viewModelScope.launch {
            try {
                driveRepository.revalidateAllAccounts()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Falha ao revalidar contas Google Drive: ${e.message}")
            }
        }

        viewModelScope.launch {
            accountPreferences.activeAccountEmailFlow.collect { dsEmail ->
                if (!dsEmail.isNullOrBlank() && dsEmail != _activeAccountEmail.value) {
                    val list = _multiCloudAccounts.value
                    if (list.any { it.email == dsEmail }) {
                        selectDriveAccount(dsEmail)
                    }
                }
            }
        }

        viewModelScope.launch {
            BackupRepository.backupProgress.collect { progress ->
                if (progress?.isFinished == true) {
                    loadBackupRecords()
                    _deviceStats.value = generateDeviceStats()
                    _activeAccountEmail.value?.let { email ->
                        refreshActiveAccountData(email, isManual = false)
                    }
                }
            }
        }
    }

    fun setThemeAccent(accent: ThemeAccent) {
        _themeAccent.value = accent
        prefs.edit().putInt("theme_accent", accent.ordinal).apply()
    }

    fun selectAppForDetail(app: AppInfo) {
        _selectedAppForDetail.value = app
    }

    fun clearSelectedApp() {
        _selectedAppForDetail.value = null
    }

    private fun observeDatabaseChanges() {
        viewModelScope.launch {
            dbHelper.dbChanges.collect { table ->
                when (table) {
                    "backup_records" -> loadBackupRecords()
                    "cloud_accounts" -> loadCloudAccounts()
                    "sync_queue" -> loadSyncQueue()
                    "system_logs" -> loadLogs()
                    "backup_filters" -> loadFilters()
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshRootStatus()
            refreshDns()
            loadInstalledApps()
            loadBackupRecords()
            loadCloudAccounts()
            loadSyncQueue()
            loadLogs()
            loadFilters()
            runCatching { _deviceStats.value = generateDeviceStats() }
            runCatching { _syncSettings.value = gdriveProvider.syncManager.getSettings() }
            runCatching { _wifiDetails.value = networkDetailsManager.getWifiDetails() }
            runCatching { _activeNetworkDetails.value = networkDetailsManager.getActiveNetworkDetails() }
            runCatching { _dataUsageStats.value = networkDetailsManager.getDataUsageStats() }
            runCatching { _deviceHardwareSpec.value = deviceHardwareManager.getDeviceSpecification() }
            runCatching { _bluetoothDevices.value = bluetoothDetailsManager.getBluetoothDevices() }
            runCatching { _bluetoothHistory.value = bluetoothDetailsManager.getDeviceHistory("") }
            if (gdriveProvider.oAuthManager.isConnected()) {
                refreshDriveAccountInfo()
                fetchRemoteDriveBackups()
            } else {
                _googleDriveState.value = if (gdriveProvider.oAuthManager.isSessionExpired()) {
                    GoogleDriveState.SESSION_EXPIRED
                } else {
                    GoogleDriveState.NOT_CONNECTED
                }
                _driveQuotaState.value = DriveQuotaState.Idle
                _remoteBackupsState.value = RemoteBackupsState.Idle
            }
        }
    }

    fun refreshDashboard() = loadData()

    fun refreshBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            loadBackupRecords()
        }
    }

    fun refreshCloudData() {
        viewModelScope.launch(Dispatchers.IO) {
            loadCloudAccounts()
            loadSyncQueue()
            refreshDriveAccountInfo()
            fetchRemoteDriveBackups()
        }
    }

    fun refreshAllPermissions() {
        permissionsRepository.refreshSystemPermissions()
        refreshNotificationStatus()
    }

    fun refreshNotificationStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val areEnabled = nm?.areNotificationsEnabled() ?: false
            val channelImportance = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val ch = nm?.getNotificationChannel(com.resync.app.util.HyperFocusNotificationHelper.CHANNEL_OPERATIONS_ID)
                ch?.importance ?: android.app.NotificationManager.IMPORTANCE_DEFAULT
            } else {
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            }
            val isChannelBlocked = (channelImportance == android.app.NotificationManager.IMPORTANCE_NONE)
            val blocked = !areEnabled || isChannelBlocked
            _isNotificationBlocked.value = blocked

            val canShowFocus = com.resync.app.util.HyperFocusNotificationHelper.isHyperOsFocusAllowed(context)
            android.util.Log.d(
                "ReSyncNotif",
                "MainViewModel.refreshNotificationStatus: areEnabled=$areEnabled, channelImportance=$channelImportance, isChannelBlocked=$isChannelBlocked, blocked=$blocked, canShowFocus=$canShowFocus"
            )
        }
    }

    fun setHyperOsAutostartConfigured(configured: Boolean = true) {
        permissionsRepository.setHyperOsAutostartConfigured(configured)
    }

    fun refreshRootStatus(forceNewShell: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val isRoot = permissionsRepository.checkRoot(forceNewShell = forceNewShell)
            val status = if (isRoot) CapabilityManager.RootStatus.AUTHORIZED else {
                val hasBinary = capabilityManager.hasSuBinary()
                val hasManager = capabilityManager.getInstalledRootManagers().isNotEmpty()
                if (hasBinary || hasManager) CapabilityManager.RootStatus.PERMISSION_NEEDED else CapabilityManager.RootStatus.UNAUTHORIZED
            }
            _rootAccessStatus.value = status
            _activeMode.value = if (isRoot) {
                CapabilityManager.ExecutionMode.ROOT
            } else if (capabilityManager.isShizukuRunning()) {
                CapabilityManager.ExecutionMode.SHIZUKU
            } else {
                CapabilityManager.ExecutionMode.STANDARD
            }
        }
    }

    fun requestRootPermission(onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRequestingRoot.value = true
            try {
                val granted = capabilityManager.requestRootAccess()
                permissionsRepository.updateRootGranted(granted)
                refreshRootStatus(forceNewShell = false)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(granted)
                }
            } finally {
                _isRequestingRoot.value = false
            }
        }
    }

    fun refreshDns() {
        _dnsConfig.value = dnsManager.getDnsConfiguration()
    }

    fun copyDnsHost() {
        _dnsConfig.value.hostname?.let { dnsManager.copyHostToClipboard(it) }
    }

    fun getOpenDnsSettingsIntent() = dnsManager.createOpenDnsSettingsIntent()

    private suspend fun loadInstalledApps() = withContext(Dispatchers.IO) {
        installedAppsRepo.refreshApps()
    }

    private suspend fun scanInternalStorageContainers(): List<BackupRecord> {
        val discovered = mutableListOf<BackupRecord>()
        val searchDirs = (com.resync.app.data.model.BackupPaths.getAllLocalBackupDirs(context) + listOf(
            File(android.os.Environment.getExternalStorageDirectory(), "Download")
        )).filter { it.exists() && it.canRead() }

        val seenPaths = mutableSetOf<String>()

        for (dir in searchDirs) {
            try {
                dir.walkTopDown().maxDepth(3).filter { file ->
                    file.isFile && (file.name.endsWith(".resync", ignoreCase = true) || file.name.endsWith(".svb", ignoreCase = true) || file.name.endsWith(".zip", ignoreCase = true)) &&
                            file.length() > 64 && !file.absolutePath.contains("/staging") && !file.absolutePath.contains("/cache/")
                }.forEach { file ->
                    if (seenPaths.add(file.absolutePath)) {
                        try {
                            val manifest = restoreEngine.inspectBackup(file)
                            val recordId = manifest.backupId.ifBlank { file.nameWithoutExtension }
                            val rec = BackupRecord(
                                id = recordId,
                                title = manifest.title.ifBlank { file.nameWithoutExtension },
                                filePath = file.absolutePath,
                                sizeBytes = file.length(),
                                checksumSha256 = manifest.sha256PayloadHash,
                                isEncrypted = manifest.isEncrypted,
                                timestamp = if (manifest.createdAt > 0) manifest.createdAt else file.lastModified(),
                                itemCount = manifest.items.size,
                                status = BackupStatus.COMPLETED,
                                includedAppsCount = manifest.items.count { it.type == BackupItemType.APP_APK || it.type == BackupItemType.APP_DATA },
                                includedFilesCount = manifest.items.count { it.type == BackupItemType.FILE },
                                includedDns = manifest.items.any { it.type == BackupItemType.DNS_CONFIG },
                                destination = "Memória interna",
                                syncStatus = SyncStatus.LOCAL_ONLY
                            )
                            discovered.add(rec)
                            dbHelper.insertBackupRecord(rec)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        return discovered
    }

    private suspend fun loadBackupRecords() = withContext(Dispatchers.IO) {
        // 1. Escaneia containers físicos na memória interna para auto-descoberta
        scanInternalStorageContainers()

        // 2. Carrega todos os registros cadastrados
        val records = dbHelper.getAllBackupRecords()
        val validRecords = mutableListOf<BackupRecord>()
        val seenIds = mutableSetOf<String>()

        for (rec in records) {
            val file = File(rec.filePath)
            val isCloudBackup = !rec.driveFileId.isNullOrBlank() ||
                    rec.syncStatus == SyncStatus.SYNCED ||
                    rec.destination.contains("Drive", ignoreCase = true)
            if (file.exists() || isCloudBackup) {
                if (seenIds.add(rec.id)) {
                    validRecords.add(rec)
                }
            } else {
                dbHelper.deleteBackupRecord(rec.id)
                dbHelper.insertLog(LogLevel.WARN, "Reconcile", "Registro de backup ${rec.id} (${rec.title}) removido pois o arquivo físico não existe localmente e não está na nuvem.")
            }
        }

        // 3. Mescla com backups remotos já consultados do Google Drive
        val cloudBackups = _remoteDriveBackups.value
        for (remote in cloudBackups) {
            if (seenIds.add(remote.id)) {
                val appProps = remote.appProperties
                val title = appProps["title"]?.takeIf { it.isNotBlank() } ?: remote.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
                val isEncrypted = appProps["isEncrypted"]?.toBooleanStrictOrNull() ?: false
                val rec = BackupRecord(
                    id = remote.id,
                    title = title,
                    filePath = remote.name,
                    sizeBytes = remote.sizeBytes,
                    checksumSha256 = remote.md5Checksum ?: "",
                    isEncrypted = isEncrypted,
                    timestamp = remote.modifiedTime,
                    itemCount = 1,
                    status = BackupStatus.COMPLETED,
                    destination = "Google Drive",
                    syncStatus = SyncStatus.SYNCED,
                    driveFileId = remote.id
                )
                validRecords.add(rec)
                dbHelper.insertBackupRecord(rec)
            }
        }

        _backupRecords.value = validRecords
        preloadBackupIndexes(validRecords)
        triggerAppBackupDetection(forceRefreshDrive = false)
    }

    private suspend fun loadCloudAccounts() = withContext(Dispatchers.IO) {
        _cloudAccounts.value = dbHelper.getAllCloudAccounts()
    }

    private suspend fun loadSyncQueue() = withContext(Dispatchers.IO) {
        _syncQueue.value = dbHelper.getAllQueueItems()
    }

    private suspend fun loadLogs() = withContext(Dispatchers.IO) {
        _systemLogs.value = dbHelper.getRecentLogs()
    }

    private suspend fun loadFilters() = withContext(Dispatchers.IO) {
        _backupFilters.value = dbHelper.getAllFilters()
    }

    // Diagnostic Runner
    fun runCloudDiagnostic(providerType: CloudProviderType) {
        viewModelScope.launch {
            _isDiagnosing.value = true
            _diagnosticResults.value = emptyList()

            cloudManager.runDiagnostic(providerType).collect { result ->
                _diagnosticResults.value = _diagnosticResults.value + result
            }
            _isDiagnosing.value = false
        }
    }

    // Queue Actions
    fun pauseQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.updateQueueItemStatus(id, QueueStatus.PAUSED)
        }
    }

    fun resumeQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.updateQueueItemStatus(id, QueueStatus.PENDING)
            val item = _syncQueue.value.find { it.id == id }
            if (item != null) {
                cloudManager.processQueueItem(item) { _, _ -> }
            }
        }
    }

    fun prioritizeQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.prioritizeQueueItem(id)
        }
    }

    fun removeQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.removeQueueItem(id)
        }
    }

    fun cancelQueueItem(id: String) {
        viewModelScope.launch {
            try {
                androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(id)
            } catch (_: Throwable) {}
            dbHelper.updateQueueItemStatus(
                id = id,
                status = QueueStatus.FAILED,
                errorCode = "CANCELLED",
                errorMessage = "Cancelado pelo usuário."
            )
            loadSyncQueue()
        }
    }

    fun clearSyncQueue(accountEmail: String? = null) {
        viewModelScope.launch {
            val targetEmail = accountEmail ?: _activeAccountEmail.value
            dbHelper.clearQueue(targetEmail)
            loadSyncQueue()
        }
    }

    fun clearCompletedSyncQueue(accountEmail: String? = null) {
        viewModelScope.launch {
            val targetEmail = accountEmail ?: _activeAccountEmail.value
            dbHelper.clearCompletedQueue(targetEmail)
            loadSyncQueue()
        }
    }

    fun retryQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.updateQueueItemStatus(id, QueueStatus.PENDING, errorCode = null, errorMessage = null)
            val item = _syncQueue.value.find { it.id == id }
            if (item != null) {
                val email = item.accountEmail ?: _activeAccountEmail.value
                val tempFile = File(context.cacheDir, item.backupTitle)
                val localFile = if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile
                } else {
                    val record = dbHelper.getBackupRecordById(item.backupId)
                    if (record != null && File(record.filePath).exists()) File(record.filePath) else null
                }

                if (email != null && localFile != null && localFile.exists() && localFile.length() > 0) {
                    val workData = androidx.work.workDataOf(
                        FileUploadWorker.KEY_ACCOUNT_EMAIL to email,
                        FileUploadWorker.KEY_FILE_PATH to localFile.absolutePath,
                        FileUploadWorker.KEY_FILE_NAME to item.backupTitle,
                        FileUploadWorker.KEY_QUEUE_ID to item.id,
                        FileUploadWorker.KEY_BACKUP_ID to item.backupId
                    )
                    val uploadRequestBuilder = androidx.work.OneTimeWorkRequestBuilder<FileUploadWorker>()
                        .addTag("upload_${item.id}")
                        .addTag("FileUploadWorker")
                        .setInputData(workData)
                        .setBackoffCriteria(
                            androidx.work.BackoffPolicy.EXPONENTIAL,
                            15,
                            java.util.concurrent.TimeUnit.SECONDS
                        )
                    try {
                        uploadRequestBuilder.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    } catch (_: Throwable) {}

                    androidx.work.WorkManager.getInstance(context).enqueue(uploadRequestBuilder.build())
                } else {
                    cloudManager.processQueueItem(item) { _, _ -> }
                }
            }
        }
    }

    fun checkCrashLogOnStartup() {
        val crashFile = File(context.filesDir, "crash.log")
        if (crashFile.exists()) {
            try {
                val content = crashFile.readText()
                if (content.isNotBlank()) {
                    _lastCrashLog.value = content
                }
            } catch (_: Exception) {}
        }

        // Consultar ActivityManager.getHistoricalProcessExitReasons
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val am = context.getSystemService(android.app.ActivityManager::class.java)
                    val exitList = am?.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                    if (!exitList.isNullOrEmpty()) {
                        val parsed = exitList.map { exitInfo ->
                            val isAnr = exitInfo.reason == android.app.ApplicationExitInfo.REASON_ANR
                            val isLmk = exitInfo.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
                            val isCrash = exitInfo.reason == android.app.ApplicationExitInfo.REASON_CRASH || exitInfo.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                            val isSignaled = exitInfo.reason == android.app.ApplicationExitInfo.REASON_SIGNALED
                            val isResource = exitInfo.reason == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE

                            val (title, isAbnormal) = when (exitInfo.reason) {
                                android.app.ApplicationExitInfo.REASON_ANR -> "ANR (Sem Resposta)" to true
                                android.app.ApplicationExitInfo.REASON_CRASH -> "Crash (Exceção não tratada)" to true
                                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crash Nativo (C/C++)" to true
                                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Falta de Memória RAM (LMK)" to false
                                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Uso de Recursos (Sistema)" to false
                                android.app.ApplicationExitInfo.REASON_SIGNALED -> "Terminado pelo Kernel (Sinal/Reboot)" to false
                                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "Encerramento Normal (Exit Self)" to false
                                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "Fechado pelo Usuário" to false
                                else -> "Outro Motivo (${exitInfo.reason})" to false
                            }

                            var traceStr: String? = null
                            if (isAnr) {
                                try {
                                    exitInfo.traceInputStream?.use { stream ->
                                        traceStr = stream.bufferedReader().use { it.readText() }.take(4000)
                                    }
                                } catch (_: Throwable) {}
                            }

                            ProcessExitInfoModel(
                                pid = exitInfo.pid,
                                timestamp = exitInfo.timestamp,
                                reasonCode = exitInfo.reason,
                                reasonTitle = title,
                                description = exitInfo.description ?: "",
                                importance = exitInfo.importance,
                                pssKb = exitInfo.pss,
                                rssKb = exitInfo.rss,
                                isAbnormalExit = isAbnormal,
                                anrTrace = traceStr
                            )
                        }

                        _historicalProcessExitReasons.value = parsed

                        // Identificar se a saída imediatamente anterior foi anormal para alertar o usuário
                        val mostRecentAbnormal = parsed.firstOrNull { it.isAbnormalExit }
                        val lastDismissedTimestamp = prefs.getLong("last_dismissed_exit_timestamp", 0L)
                        if (mostRecentAbnormal != null && mostRecentAbnormal.timestamp > lastDismissedTimestamp) {
                            _previousProcessExitBanner.value = mostRecentAbnormal
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MainViewModel", "Erro ao carregar historical process exit reasons: ${t.message}")
                }
            }
        }
    }

    fun dismissCrashLog() {
        _lastCrashLog.value = null
        try {
            val crashFile = File(context.filesDir, "crash.log")
            if (crashFile.exists()) crashFile.delete()
        } catch (_: Exception) {}
    }

    fun refreshNetworkAndWifi() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val wifi = networkDetailsManager.getWifiDetails()
                val active = networkDetailsManager.getActiveNetworkDetails()
                val usage = networkDetailsManager.getDataUsageStats()
                val dns = dnsManager.getDnsConfiguration()
                _wifiDetails.value = wifi
                _activeNetworkDetails.value = active
                _dataUsageStats.value = usage
                _dnsConfig.value = dns
            }
        }
    }

    private var trafficMonitorJob: kotlinx.coroutines.Job? = null

    fun startRealtimeTrafficAndHardwareMonitoring() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(2000)
                runCatching {
                    _activeNetworkDetails.value = networkDetailsManager.getActiveNetworkDetails()
                    _wifiDetails.value = networkDetailsManager.getWifiDetails()

                    // Atualizar dinâmicos de hardware (RAM, Bateria)
                    val mem = deviceHardwareManager.getMemoryInfo()
                    val bat = deviceHardwareManager.getBatteryInfo()
                    val currentSpec = _deviceHardwareSpec.value
                    _deviceHardwareSpec.value = currentSpec.copy(
                        ramUsedBytes = mem.usedBytes,
                        ramAvailableBytes = mem.availableBytes,
                        batteryPercent = bat.percent,
                        batteryHealth = bat.health,
                        batteryTemperatureC = bat.tempC,
                        batteryVoltageMv = bat.voltageMv,
                        batteryStatus = bat.status,
                        batteryCycles = bat.cycles
                    )
                }
            }
        }
    }

    fun stopRealtimeMonitoring() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
    }

    fun refreshBluetoothData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _bluetoothDevices.value = bluetoothDetailsManager.getBluetoothDevices()
                _bluetoothHistory.value = bluetoothDetailsManager.getDeviceHistory("")
            }
        }
    }

    fun refreshDeviceHardwareSpec() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _deviceHardwareSpec.value = deviceHardwareManager.getDeviceSpecification()
            }
        }
    }

    fun verifyDriveBackupsStatus() {
        viewModelScope.launch {
            if (gdriveProvider.oAuthManager.isConnected()) {
                val updated = gdriveProvider.cloudBackupRepository.verifyRemoteStatusForLocalBackups()
                _backupRecords.value = updated
            }
        }
    }

    // Backup Orchestration
    fun launchBackup(
        title: String,
        selectedApps: List<Pair<AppInfo, AppSelectionOptions>>,
        selectedFiles: List<String>,
        includeSms: Boolean,
        includeCalls: Boolean,
        includeContacts: Boolean = false,
        includeDns: Boolean,
        includeWallpaper: Boolean = false,
        includeWallpaperHome: Boolean = false,
        includeWallpaperLock: Boolean = false,
        includeWifi: Boolean = false,
        includeBluetooth: Boolean = false,
        encryptionPassword: String? = null,
        targetCloudProvider: CloudProviderType? = null,
        targetCloudAccountEmail: String? = null,
        destinationLabel: String? = null,
        destinationType: BackupDestinationType = BackupDestinationType.SOMENTE_INTERNA,
        selectedCategoryItemIds: Set<String>? = null
    ) {
        Log.d("BackupOrigin", "MainViewModel.launchBackup called by user: title='$title', apps=${selectedApps.size}, files=${selectedFiles.size}, destinationType=$destinationType, destLabel='$destinationLabel'")
        val emailToSync = targetCloudAccountEmail ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
        BackupRepository.startBackup(
            context = context,
            title = title,
            selectedApps = selectedApps,
            selectedFiles = selectedFiles,
            includeSms = includeSms,
            includeCalls = includeCalls,
            includeContacts = includeContacts,
            includeDns = includeDns,
            includeWallpaper = includeWallpaper,
            includeWallpaperHome = includeWallpaperHome,
            includeWallpaperLock = includeWallpaperLock,
            includeWifi = includeWifi,
            includeBluetooth = includeBluetooth,
            encryptionPassword = encryptionPassword,
            targetCloudProvider = targetCloudProvider,
            targetCloudAccountEmail = emailToSync,
            destinationLabel = destinationLabel,
            destinationType = destinationType,
            selectedCategoryItemIds = selectedCategoryItemIds
        )
    }

    fun pauseBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BackupRepository.pauseBackup()
                pauseCloudUpload()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Erro ao pausar backup: ${e.message}")
            }
        }
    }

    fun resumeBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BackupRepository.resumeBackup()
                resumeCloudUpload()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Erro ao retomar backup: ${e.message}")
            }
        }
    }

    fun cancelBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BackupRepository.cancelBackup(context)
                cancelCloudUpload()
                try {
                    androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("FileUploadWorker")
                    androidx.work.WorkManager.getInstance(context).cancelAllWork()
                } catch (_: Throwable) {}
            } catch (e: Exception) {
                Log.e("MainViewModel", "Erro ao cancelar backup: ${e.message}")
            }
        }
    }

    fun getContactsCount(): Int = backupEngine.getContactsCount()

    // Quick Backup Selection Management
    fun setQuickBackupSelectedPaths(paths: Set<String>) {
        _quickBackupSelectedPaths.value = paths
        reconcileFolderSizeCalculations(paths)
    }

    fun addQuickBackupSelectedPaths(paths: Collection<String>) {
        val newSet = _quickBackupSelectedPaths.value + paths
        _quickBackupSelectedPaths.value = newSet
        reconcileFolderSizeCalculations(newSet)
    }

    fun removeQuickBackupSelectedPath(path: String) {
        val newSet = _quickBackupSelectedPaths.value - path
        _quickBackupSelectedPaths.value = newSet
        folderSizeCalcJobs[path]?.cancel()
        folderSizeCalcJobs.remove(path)
        _quickBackupCalculatingPaths.value = _quickBackupCalculatingPaths.value - path
    }

    fun removeQuickBackupSelectedPaths(paths: Collection<String>) {
        val toRemove = paths.toSet()
        val newSet = _quickBackupSelectedPaths.value - toRemove
        _quickBackupSelectedPaths.value = newSet
        paths.forEach { path ->
            folderSizeCalcJobs[path]?.cancel()
            folderSizeCalcJobs.remove(path)
        }
        _quickBackupCalculatingPaths.value = _quickBackupCalculatingPaths.value - toRemove
    }

    fun toggleQuickBackupPath(path: String) {
        if (_quickBackupSelectedPaths.value.contains(path)) {
            removeQuickBackupSelectedPath(path)
        } else {
            addQuickBackupSelectedPaths(listOf(path))
        }
    }

    fun clearQuickBackupSelectedPaths() {
        _quickBackupSelectedPaths.value = emptySet()
        folderSizeCalcJobs.values.forEach { it.cancel() }
        folderSizeCalcJobs.clear()
        _quickBackupCalculatingPaths.value = emptySet()
    }

    private val _quickBackupRootAvailablePaths = MutableStateFlow<List<String>>(emptyList())
    val quickBackupRootAvailablePaths: StateFlow<List<String>> = _quickBackupRootAvailablePaths.asStateFlow()

    fun loadQuickBackupAvailablePaths() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootDir = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            val files = if (rootDir.exists() && rootDir.canRead()) {
                rootDir.listFiles()?.filter { !it.name.startsWith(".") }?.map { it.absolutePath }?.sorted() ?: emptyList()
            } else {
                emptyList()
            }
            if (files.isNotEmpty()) {
                _quickBackupRootAvailablePaths.value = files
            }
        }
    }

    fun toggleAllQuickBackupRootPaths(availableFromUi: Collection<String> = emptyList()) {
        val candidates = if (availableFromUi.isNotEmpty()) {
            availableFromUi.toList()
        } else if (_quickBackupRootAvailablePaths.value.isNotEmpty()) {
            _quickBackupRootAvailablePaths.value
        } else {
            val rootDir = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            rootDir.listFiles()?.filter { !it.name.startsWith(".") }?.map { it.absolutePath }?.sorted() ?: emptyList()
        }

        if (candidates.isEmpty()) return

        _quickBackupRootAvailablePaths.value = candidates
        val cur = _quickBackupSelectedPaths.value
        val allSelected = candidates.all { cur.contains(it) }

        if (allSelected) {
            removeQuickBackupSelectedPaths(candidates)
        } else {
            addQuickBackupSelectedPaths(candidates)
        }
    }

    private fun reconcileFolderSizeCalculations(paths: Set<String>) {
        val isRoot = activeMode.value == CapabilityManager.ExecutionMode.ROOT
        for (path in paths) {
            val file = File(path)
            if (file.isDirectory && !_quickBackupFolderSizes.value.containsKey(path) && !folderSizeCalcJobs.containsKey(path)) {
                _quickBackupCalculatingPaths.value = _quickBackupCalculatingPaths.value + path
                val job = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val size = StorageRepository.calculateFolderSize(file, isRoot)
                        _quickBackupFolderSizes.value = _quickBackupFolderSizes.value + (path to size)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e("MainViewModel", "Erro ao calcular tamanho da pasta $path: ${e.message}", e)
                        }
                    } finally {
                        _quickBackupCalculatingPaths.value = _quickBackupCalculatingPaths.value - path
                        folderSizeCalcJobs.remove(path)
                    }
                }
                folderSizeCalcJobs[path] = job
            }
        }
    }

    fun getQuickBackupTotalEstimatedBytes(): Long {
        val deduplicated = StorageRepository.normalizeAndDeduplicatePaths(_quickBackupSelectedPaths.value)
        return deduplicated.sumOf { path ->
            val file = File(path)
            if (file.isDirectory) {
                _quickBackupFolderSizes.value[path] ?: 0L
            } else {
                file.length()
            }
        }
    }

    fun isQuickBackupCalculatingSizes(): Boolean {
        val deduplicated = StorageRepository.normalizeAndDeduplicatePaths(_quickBackupSelectedPaths.value)
        val calculating = _quickBackupCalculatingPaths.value
        return deduplicated.any { calculating.contains(it) }
    }

    // Restore Orchestration
    // Restore Orchestration
    suspend fun inspectBackupManifest(record: BackupRecord): BackupManifest? {
        val file = File(record.filePath)
        if (file.exists() && file.length() > 0) {
            return try {
                restoreEngine.inspectBackup(file)
            } catch (e: Exception) {
                null
            }
        }
        val cacheFile = File(context.cacheDir, "restore/${record.id}.resync")
        val altCacheFile = if (cacheFile.exists()) cacheFile else File(context.cacheDir, "restore/${record.id}.svb")
        val finalCacheFile = if (altCacheFile.exists()) altCacheFile else File(context.cacheDir, "restore/${record.id}.zip")
        if (finalCacheFile.exists() && finalCacheFile.length() > 0) {
            return try {
                restoreEngine.inspectBackup(finalCacheFile)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    fun preloadBackupIndexes(records: List<BackupRecord>, targetEmail: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val email = targetEmail ?: _activeAccountEmail.value
            backupIndexManager.preloadIndexes(records, email)
        }
    }

    fun inspectBackup(record: BackupRecord, forceCloudSource: Boolean = false) {
        viewModelScope.launch {
            Log.d("RestoreInspection", "[INSPECT] Verificando backup ${record.id} ('${record.title}')...")
            dbHelper.insertLog(LogLevel.INFO, "Restore", "Iniciando inspeção do backup '${record.title}' (${record.id})")

            var localFile = File(record.filePath)
            if (!localFile.exists() || localFile.length() == 0L) {
                val matched = _backupRecords.value.firstOrNull {
                    (!it.driveFileId.isNullOrBlank() && it.driveFileId == record.driveFileId) ||
                    (it.title.isNotBlank() && (it.title == record.title || it.filePath.endsWith(record.title) || it.filePath.endsWith("${record.title}.resync")))
                }
                if (matched != null && File(matched.filePath).exists()) {
                    localFile = File(matched.filePath)
                } else {
                    val found = com.resync.app.data.model.BackupPaths.findLocalMirrorFile(
                        backupId = record.id,
                        title = record.title,
                        context = context,
                        expectedSizeBytes = record.sizeBytes,
                        driveFileId = record.driveFileId
                    )
                    if (found != null) {
                        localFile = found
                    }
                }
            }

            // Se encontrarmos o container ou espelho no aparelho, sempre priorizar a leitura local instantânea!
            val hasValidLocalFile = localFile.exists() && localFile.length() > 0 && !localFile.absolutePath.contains("/staging")

            // 1. Tentar ler do arquivo lateral .index.json local em < 20ms se disponível
            if (hasValidLocalFile) {
                val sidecar = File(localFile.parentFile, "${localFile.nameWithoutExtension}.index.json")
                if (sidecar.exists() && sidecar.length() > 0L) {
                    try {
                        val sidecarBytes = sidecar.readBytes()
                        val isGzip = sidecarBytes.size >= 2 && sidecarBytes[0] == 0x1F.toByte() && sidecarBytes[1] == 0x8B.toByte()
                        val jsonStr = if (isGzip) {
                            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(sidecarBytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } else {
                            String(sidecarBytes, Charsets.UTF_8)
                        }
                        val sidecarManifest = com.google.gson.Gson().fromJson(jsonStr, BackupManifest::class.java)
                        if (sidecarManifest != null && sidecarManifest.items.isNotEmpty()) {
                            Log.d("RestoreInspection", "[INSPECT] Manifesto lido instantaneamente do arquivo lateral .index.json (${sidecarManifest.items.size} itens).")
                            _manifestInspectState.value = ManifestInspectState.Ready(sidecarManifest, localFile)
                            return@launch
                        }
                    } catch (_: Throwable) {}
                }
            }

            // 2. Tentar recuperar instantaneamente do cache no Room
            val cachedManifest = backupIndexManager.getCachedManifest(record.id, record.driveFileId, record.filePath)
            if (cachedManifest != null) {
                Log.d("RestoreInspection", "[INSPECT] Manifesto lido instantaneamente do cache Room (${cachedManifest.items.size} itens).")
                _manifestInspectState.value = ManifestInspectState.Ready(cachedManifest, if (localFile.exists() && localFile.length() > 0L) localFile else null)
                return@launch
            }

            // 2. Leitura local se disponível (sem cópia e sem descompactar container)
            if (hasValidLocalFile) {
                _manifestInspectState.value = ManifestInspectState.InspectingLocal
                Log.d("RestoreInspection", "[INSPECT] Arquivo local encontrado: ${localFile.absolutePath} (${localFile.length()} bytes)")
                dbHelper.insertLog(LogLevel.INFO, "Restore", "Arquivo local encontrado para '${record.title}': ${localFile.name}")
                try {
                    val manifest = backupIndexManager.inspectLocalBackup(localFile, record.id)
                    Log.d("RestoreInspection", "[INSPECT] Manifesto lido com sucesso (${manifest.items.size} itens).")
                    dbHelper.insertLog(LogLevel.INFO, "Restore", "Manifesto lido com sucesso: ${manifest.items.size} itens encontrados.")
                    _manifestInspectState.value = ManifestInspectState.Ready(manifest, localFile)
                } catch (e: Exception) {
                    val errMsg = "Não foi possível ler o arquivo local: ${e.localizedMessage ?: e.message}"
                    Log.e("RestoreInspection", "[INSPECT] $errMsg", e)
                    dbHelper.insertLog(LogLevel.ERROR, "Restore", errMsg)
                    _manifestInspectState.value = ManifestInspectState.Error(errMsg, canRetry = false)
                }
                return@launch
            }

            // 3. Inspeção no Google Drive
            val isCloudBackup = !record.driveFileId.isNullOrBlank() ||
                    record.syncStatus == SyncStatus.SYNCED ||
                    record.destination.contains("Drive", ignoreCase = true)

            if (!isCloudBackup) {
                val errMsg = "Arquivo de backup não encontrado no aparelho e não está disponível na nuvem."
                Log.e("RestoreInspection", "[INSPECT] $errMsg")
                dbHelper.insertLog(LogLevel.ERROR, "Restore", errMsg)
                _manifestInspectState.value = ManifestInspectState.Error(errMsg, canRetry = false)
                return@launch
            }

            // Verificar conectividade de rede
            if (!_activeNetworkDetails.value.isConnected) {
                val errMsg = "Sem conexão com a internet para baixar o backup do Google Drive."
                Log.e("RestoreInspection", "[INSPECT] $errMsg")
                dbHelper.insertLog(LogLevel.ERROR, "Restore", errMsg)
                _manifestInspectState.value = ManifestInspectState.Error(errMsg, canRetry = true)
                return@launch
            }

            // Identificar conta Google
            val emailRegex = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}".toRegex()
            val accountFromDest = emailRegex.find(record.destination)?.value
            val targetEmail = accountFromDest ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email

            // Obter driveFileId
            var fileId = record.driveFileId
            if (fileId.isNullOrBlank()) {
                val fileName = File(record.filePath).name
                Log.d("RestoreInspection", "[INSPECT] driveFileId ausente no registro. Buscando por nome '$fileName' no Drive ($targetEmail)...")
                try {
                    if (targetEmail != null) {
                        val client = driveRepository.getDriveClientForAccount(targetEmail)
                        val q = "name = '$fileName' and trashed = false"
                        val found = client.files().list().setQ(q).setFields("files(id, name, size)").execute().files?.firstOrNull()
                        if (found != null) {
                            fileId = found.id
                            Log.d("RestoreInspection", "[INSPECT] Arquivo localizado no Drive via busca por nome: id=$fileId")
                            dbHelper.updateBackupSyncStatus(record.id, SyncStatus.SYNCED, fileId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RestoreInspection", "[INSPECT] Falha na busca por nome no Drive: ${e.message}")
                }
            }

            if (fileId.isNullOrBlank()) {
                val errMsg = "Arquivo não encontrado no Google Drive. Ele pode ter sido excluído ou a conta não está conectada."
                Log.e("RestoreInspection", "[INSPECT] $errMsg")
                dbHelper.insertLog(LogLevel.ERROR, "Restore", errMsg)
                _manifestInspectState.value = ManifestInspectState.Error(errMsg, canRetry = true)
                return@launch
            }

            // Iniciar leitura remota sem baixar o container de 29 GB
            _manifestInspectState.value = ManifestInspectState.DownloadingFromDrive(
                progressPercent = 0.10f,
                stage = "Verificando metadados no Google Drive..."
            )

            val inspectRes = backupIndexManager.inspectDriveBackup(
                fileId = fileId,
                expectedName = if (record.title.endsWith(".resync") || record.title.endsWith(".svb") || record.title.endsWith(".zip")) record.title else "${record.title}.resync",
                expectedSizeBytes = record.sizeBytes,
                accountEmail = targetEmail
            ) { stage, pct ->
                _manifestInspectState.value = ManifestInspectState.DownloadingFromDrive(
                    progressPercent = pct,
                    stage = stage
                )
            }

            if (inspectRes.isSuccess) {
                val manifest = inspectRes.getOrThrow()
                Log.d("RestoreInspection", "[INSPECT] Índice lido com sucesso da nuvem (${manifest.items.size} itens).")
                dbHelper.insertLog(LogLevel.INFO, "Restore", "Índice do backup lido com sucesso (${manifest.items.size} itens disponíveis para restauração).")
                _manifestInspectState.value = ManifestInspectState.Ready(manifest, if (localFile.exists() && localFile.length() > 0L) localFile else null)
            } else {
                val dlEx = inspectRes.exceptionOrNull()
                val fullCauses = mutableListOf<String>()
                var curr: Throwable? = dlEx
                while (curr != null) {
                    fullCauses.add("${curr.javaClass.simpleName}: ${curr.message}")
                    curr = curr.cause
                }
                val causeChain = fullCauses.joinToString(" -> ")
                val detailedLog = "Falha na leitura do índice do Drive (Esperado: ${record.sizeBytes} bytes). Causas: $causeChain"
                Log.e("RestoreInspection", detailedLog, dlEx)
                dbHelper.insertLog(LogLevel.ERROR, "Restore", detailedLog)

                val errMsg = when {
                    dlEx is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException || dlEx?.message?.contains("401") == true ->
                        "Sessão do Google Drive expirada. Reconecte sua conta em Sincronização em Nuvem."
                    dlEx?.message?.contains("incompleto", ignoreCase = true) == true ->
                        dlEx.message ?: "backup incompleto no Drive"
                    dlEx?.message?.contains("404") == true || dlEx?.message?.contains("not found", ignoreCase = true) == true ->
                        "Arquivo não encontrado no Google Drive. Ele pode ter sido excluído da nuvem."
                    !_activeNetworkDetails.value.isConnected ->
                        "Conexão com a internet perdida durante a leitura do índice."
                    else -> "Falha ao carregar índice do backup: ${dlEx?.localizedMessage ?: dlEx?.message ?: "Erro desconhecido"}"
                }
                _manifestInspectState.value = ManifestInspectState.Error(errMsg, canRetry = true)
            }
        }
    }

    suspend fun fetchRemoteEntryBytes(record: BackupRecord, entryPath: String, password: String? = null): ByteArray? {
        val fileId = record.driveFileId ?: (if (File(record.filePath).exists()) null else record.id) ?: return null
        val emailRegex = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}".toRegex()
        val accountFromDest = emailRegex.find(record.destination)?.value
        val primaryEmail = accountFromDest ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
        
        val firstResult = backupIndexManager.fetchRemoteEntryBytes(
            fileId = fileId,
            fileName = record.title,
            fileSize = record.sizeBytes,
            entryPath = entryPath,
            accountEmail = primaryEmail,
            decryptionPassword = password
        )
        if (firstResult != null) return firstResult

        // Se falhar na conta primária, tenta nas outras contas Google conectadas
        val candidates = _multiCloudAccounts.value.map { it.email }.filter { it != primaryEmail }
        for (altEmail in candidates) {
            val altResult = backupIndexManager.fetchRemoteEntryBytes(
                fileId = fileId,
                fileName = record.title,
                fileSize = record.sizeBytes,
                entryPath = entryPath,
                accountEmail = altEmail,
                decryptionPassword = password
            )
            if (altResult != null) return altResult
        }
        return null
    }

    fun launchRestore(
        record: BackupRecord,
        selectedItemIds: Set<String>,
        password: String? = null,
        downloadedCacheFile: File? = null,
        selectedCallKeys: Set<String>? = null,
        selectedSmsKeys: Set<String>? = null,
        selectedContactKeys: Set<String>? = null
    ) {
        val file = downloadedCacheFile?.takeIf { it.exists() } ?: File(record.filePath)
        val isLocal = file.exists()
        val remoteId = record.driveFileId ?: (if (!isLocal) record.id else null)

        val emailRegex = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}".toRegex()
        val accountFromDest = emailRegex.find(record.destination)?.value
        val email = accountFromDest ?: _activeAccountEmail.value

        Log.d("RestoreInspection", "[RESTORE] Iniciando restauração: file=${file.absolutePath} (isLocal=$isLocal), remoteId=$remoteId, items=${selectedItemIds.size}, account=$email")
        viewModelScope.launch {
            dbHelper.insertLog(LogLevel.INFO, "Restore", "Iniciando restauração de '${record.title}' (${selectedItemIds.size} itens selecionados)")
        }

        RestoreRepository.startRestore(
            localFile = if (isLocal) file else null,
            remoteDriveFileId = if (!isLocal) remoteId else null,
            accountEmail = email,
            selectedItemTypes = selectedItemIds,
            encryptionPassword = password,
            selectedCallKeys = selectedCallKeys,
            selectedSmsKeys = selectedSmsKeys,
            selectedContactKeys = selectedContactKeys
        )
    }


    fun checkSyncConflicts(providerType: CloudProviderType) {
        viewModelScope.launch {
            val conflicts = cloudManager.detectConflicts(providerType, _backupRecords.value)
            _detectedConflicts.value = conflicts
        }
    }

    fun resolveConflict(conflict: SyncConflict, resolution: ConflictResolution, providerType: CloudProviderType) {
        viewModelScope.launch {
            cloudManager.resolveConflict(conflict, resolution, providerType)
            _detectedConflicts.value = _detectedConflicts.value.filter { it.fileId != conflict.fileId }
        }
    }

    // Google Drive Multi-Account Active Operations
    fun fetchRemoteDriveBackups() {
        refreshActiveAccountData(isManual = true)
    }

    fun refreshDriveAccountInfo() {
        refreshActiveAccountData(isManual = true)
    }

    fun refreshAllGoogleDriveData(onComplete: (() -> Unit)? = null) {
        refreshActiveAccountData(isManual = true, onComplete = onComplete)
    }

    fun refreshActiveAccountData(
        targetEmail: String? = null,
        isManual: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val email = targetEmail ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
        if (email.isNullOrBlank()) {
            if (gdriveProvider.oAuthManager.isConnected()) {
                refreshLegacyGoogleDriveData(onComplete)
            } else {
                _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
                _driveQuotaState.value = DriveQuotaState.Idle
                _remoteBackupsState.value = RemoteBackupsState.Idle
                _activeAccountReauthIntent.value = null
                onComplete?.invoke()
            }
            return
        }

        // 1. One refresh per account at a time
        val existingJob = refreshJobsByEmail[email]
        if (existingJob != null && existingJob.isActive) {
            Log.d("DriveSync", "Refresh para $email ignorado: já em andamento.")
            onComplete?.invoke()
            return
        }

        // 2. Minimum 30s interval between automatic refreshes (manual ignores)
        val now = System.currentTimeMillis()
        if (!isManual) {
            val lastRefresh = lastAutoRefreshByEmail[email] ?: 0L
            val elapsed = now - lastRefresh
            if (elapsed < 30_000L) {
                Log.d("DriveSync", "Auto-refresh para $email ignorado: cooldown ativo (${elapsed / 1000}s / 30s).")
                onComplete?.invoke()
                return
            }
        }
        lastAutoRefreshByEmail[email] = now

        // 3. Counter and log to verify refresh stops looping
        val count = refreshExecutionCounter.computeIfAbsent(email) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        Log.d("DriveSync", "Refresh executado para $email (execução #$count, manual=$isManual)")

        val job = viewModelScope.launch(Dispatchers.IO) {
            _isRefreshingActiveAccount.value = true
            try {
                // 1. Quota & User Info
                when (val quotaRes = driveRepository.fetchAccountInfo(email)) {
                    is DriveAuthResult.Success -> {
                        val acc = quotaRes.account
                        val total = if (acc.quotaTotalBytes > 0) acc.quotaTotalBytes else 15L * 1024 * 1024 * 1024
                        val used = acc.quotaUsedBytes
                        val available = maxOf(0L, total - used)
                        val loadedQuota = DriveQuotaState.Loaded(
                            totalBytes = total,
                            usedBytes = used,
                            availableBytes = available,
                            userEmail = acc.email,
                            displayName = acc.name
                        )
                        _driveQuotaState.value = loadedQuota
                        _googleDriveState.value = GoogleDriveState.CONNECTED
                        _activeAccountReauthIntent.value = null
                    }
                    is DriveAuthResult.NeedsUserRecoverableAuth -> {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        _driveQuotaState.value = DriveQuotaState.Error("A sessão requer autorização da conta.", "REAUTH_NEEDED")
                        _activeAccountReauthIntent.value = quotaRes.recoveryIntent
                    }
                    is DriveAuthResult.Error -> {
                        val isOffline = quotaRes.errorMessage.contains("UnknownHost", ignoreCase = true) ||
                                quotaRes.errorMessage.contains("ConnectException", ignoreCase = true) ||
                                quotaRes.errorMessage.contains("network", ignoreCase = true) ||
                                quotaRes.errorMessage.contains("rede", ignoreCase = true) ||
                                quotaRes.errorMessage.contains("internet", ignoreCase = true)
                        val errDisplay = if (isOffline) "Sem conexão com a internet. Verifique sua conexão e tente novamente." else quotaRes.errorMessage
                        _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                        if (_driveQuotaState.value !is DriveQuotaState.Loaded) {
                            _driveQuotaState.value = DriveQuotaState.Error(errDisplay, "ERROR")
                        }
                    }
                }

                // 2. Remote Backups
                try {
                    val driveFiles = driveRepository.listBackups(email)
                    val mappedBackups = driveFiles.map { f ->
                        com.resync.app.cloud.gdrive.DriveFileMetadata(
                            id = f.id ?: "",
                            name = f.name ?: "",
                            sizeBytes = f.getSize() ?: 0L,
                            createdTime = f.createdTime?.value ?: System.currentTimeMillis(),
                            modifiedTime = f.modifiedTime?.value ?: System.currentTimeMillis(),
                            md5Checksum = f.md5Checksum,
                            mimeType = f.mimeType ?: "application/octet-stream",
                            appProperties = f.appProperties ?: emptyMap()
                        )
                    }
                    val loadedBackups = RemoteBackupsState.Loaded(mappedBackups)
                    _remoteBackupsState.value = loadedBackups
                    _remoteDriveBackups.value = mappedBackups
                    val remoteDummyRecords = mappedBackups.map { rem ->
                        BackupRecord(
                            id = rem.id,
                            title = rem.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip"),
                            filePath = "",
                            sizeBytes = rem.sizeBytes,
                            checksumSha256 = rem.md5Checksum ?: "",
                            isEncrypted = false,
                            timestamp = rem.modifiedTime,
                            itemCount = 0,
                            status = BackupStatus.COMPLETED,
                            destination = "Google Drive",
                            syncStatus = SyncStatus.SYNCED,
                            driveFileId = rem.id
                        )
                    }
                    preloadBackupIndexes(remoteDummyRecords, email)
                } catch (t: Throwable) {
                    Log.w("MainViewModel", "Erro ao listar backups para $email: ${t.message}")
                    if (_remoteBackupsState.value !is RemoteBackupsState.Loaded) {
                        val isOffline = t.message?.contains("UnknownHost", ignoreCase = true) == true ||
                                t.message?.contains("ConnectException", ignoreCase = true) == true
                        val errMsg = if (isOffline) "Sem conexão com a internet." else (t.message ?: "Não foi possível consultar os backups.")
                        _remoteBackupsState.value = RemoteBackupsState.Error(errMsg)
                    }
                }

                // 3. Update Cache for this account
                accountCache[email] = AccountLiveCache(
                    email = email,
                    quotaState = _driveQuotaState.value,
                    backupsState = _remoteBackupsState.value,
                    googleDriveState = _googleDriveState.value,
                    reauthIntent = _activeAccountReauthIntent.value
                )
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Erro ao atualizar dados da conta $email: ${t.message}", t)
            } finally {
                _isRefreshingActiveAccount.value = false
                refreshJobsByEmail.remove(email)
                onComplete?.invoke()
            }
        }
        refreshJobsByEmail[email] = job
    }

    private fun refreshLegacyGoogleDriveData(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (!gdriveProvider.oAuthManager.isConnected()) {
                _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
                _driveQuotaState.value = DriveQuotaState.Idle
                _remoteBackupsState.value = RemoteBackupsState.Idle
                onComplete?.invoke()
                return@launch
            }
            try {
                if (gdriveProvider.oAuthManager.isSessionExpired()) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                }
                val qRes = gdriveProvider.driveFileManager.getStorageQuota()
                if (qRes.isSuccess) {
                    val q = qRes.getOrThrow()
                    _driveQuotaState.value = DriveQuotaState.Loaded(q.limitBytes, q.usageBytes, q.availableBytes, q.userEmail, q.displayName)
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                }
                val bRes = gdriveProvider.cloudBackupRepository.fetchRemoteBackups()
                if (bRes.isSuccess) {
                    val b = bRes.getOrThrow()
                    _remoteDriveBackups.value = b
                    _remoteBackupsState.value = RemoteBackupsState.Loaded(b)
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun connectGoogleAccount(email: String, activity: android.app.Activity? = null, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            _googleDriveState.value = GoogleDriveState.CONNECTING
            val authRes = gdriveProvider.oAuthManager.authenticateWithAccountName(email, activity)
            if (authRes.isSuccess) {
                // Pre-flight check: Verify real token and permissions with Drive API (about.get)
                val quotaRes = gdriveProvider.driveFileManager.getStorageQuota()
                if (quotaRes.isSuccess) {
                    val quota = quotaRes.getOrThrow()
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                    _driveQuota.value = quota
                    _driveQuotaState.value = DriveQuotaState.Loaded(
                        totalBytes = quota.limitBytes,
                        usedBytes = quota.usageBytes,
                        availableBytes = quota.availableBytes,
                        userEmail = quota.userEmail ?: email,
                        displayName = quota.displayName
                    )
                    dbHelper.updateCloudAccount(
                        CloudAccount(
                            providerType = CloudProviderType.GOOGLE_DRIVE,
                            accountName = quota.userEmail ?: email,
                            isConnected = true,
                            quotaUsedBytes = quota.usageBytes,
                            quotaTotalBytes = quota.limitBytes,
                            lastSyncTimestamp = System.currentTimeMillis()
                        )
                    )
                    loadCloudAccounts()
                    fetchRemoteDriveBackups()
                    onResult(authRes)
                } else {
                    val ex = quotaRes.exceptionOrNull()
                    val is401 = ex is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
                    if (is401) {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        _driveQuotaState.value = DriveQuotaState.Error("Acesso à Google Drive API não autorizado (HTTP 401). Reconecte sua conta.", "HTTP 401")
                    } else {
                        _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                        _driveQuotaState.value = DriveQuotaState.Error(ex?.message ?: "Falha ao validar chamada da Google Drive API")
                    }
                    onResult(Result.failure(ex ?: IllegalStateException("Falha ao validar chamada da Google Drive API")))
                }
            } else {
                val ex = authRes.exceptionOrNull()
                if (ex is com.resync.app.cloud.gdrive.AuthIntentRequiredException) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    _activeAccountReauthIntent.value = ex.intent
                    _driveQuotaState.value = DriveQuotaState.Error("A sessão requer autorização da conta.", "REAUTH_NEEDED")
                } else if (ex is com.resync.app.cloud.gdrive.GoogleCloudOAuthConfigurationException) {
                    _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                    _driveQuotaState.value = DriveQuotaState.Error(
                        message = ex.message ?: "Cliente OAuth não registrado no Google Cloud Console",
                        technicalCode = "UnregisteredOnApiConsole"
                    )
                } else if (ex is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    _driveQuotaState.value = DriveQuotaState.Error(ex.message ?: "Sessão expirada", "HTTP 401")
                } else {
                    _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                    _driveQuotaState.value = DriveQuotaState.Error(ex?.message ?: "Erro de conexão")
                }
                onResult(authRes)
            }
        }
    }

    fun refreshGoogleAuth(onResult: ((Result<String>) -> Unit)? = null) {
        viewModelScope.launch {
            _googleDriveState.value = GoogleDriveState.CONNECTING
            val authRes = gdriveProvider.oAuthManager.refreshAccessToken()
            if (authRes.isSuccess) {
                val quotaRes = gdriveProvider.driveFileManager.getStorageQuota()
                if (quotaRes.isSuccess) {
                    val quota = quotaRes.getOrThrow()
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                    _driveQuota.value = quota
                    _driveQuotaState.value = DriveQuotaState.Loaded(
                        totalBytes = quota.limitBytes,
                        usedBytes = quota.usageBytes,
                        availableBytes = quota.availableBytes,
                        userEmail = quota.userEmail,
                        displayName = quota.displayName
                    )
                    loadCloudAccounts()
                    fetchRemoteDriveBackups()
                    onResult?.invoke(authRes)
                } else {
                    val ex = quotaRes.exceptionOrNull()
                    if (ex is com.resync.app.cloud.gdrive.AuthIntentRequiredException) {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        _activeAccountReauthIntent.value = ex.intent
                    } else {
                        val is401 = ex is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
                        if (is401) {
                            _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        } else {
                            _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                        }
                    }
                    onResult?.invoke(Result.failure(ex ?: IllegalStateException("Validação da API falhou após renovação de token.")))
                }
            } else {
                val ex = authRes.exceptionOrNull()
                if (ex is com.resync.app.cloud.gdrive.AuthIntentRequiredException) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    _activeAccountReauthIntent.value = ex.intent
                } else if (ex is com.resync.app.cloud.gdrive.GoogleCloudOAuthConfigurationException) {
                    _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                    _driveQuotaState.value = DriveQuotaState.Error(
                        message = ex.message ?: "Cliente OAuth não registrado no Google Cloud Console",
                        technicalCode = "UnregisteredOnApiConsole"
                    )
                } else {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                }
                onResult?.invoke(authRes)
            }
        }
    }

    fun deleteBackupRecord(record: BackupRecord, deleteFromCloud: Boolean = false, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val file = File(record.filePath)
                var fileDeleted = true
                if (file.exists()) {
                    fileDeleted = file.delete()
                    if (fileDeleted) {
                        // Se a subpasta específica do backup contiver apenas este arquivo ou ficar vazia, remove a subpasta
                        val parentDir = file.parentFile
                        if (parentDir != null && parentDir.name != com.resync.app.data.model.BackupPaths.BACKUP_DIR_NAME && parentDir.name != "SwiftVaultBackups" && parentDir.listFiles().isNullOrEmpty()) {
                            parentDir.delete()
                        }
                    }
                }

                if (!fileDeleted) {
                    onResult(false, "Não foi possível excluir o arquivo de backup do aparelho. Verifique as permissões.")
                    return@launch
                }

                // Excluir do Google Drive caso solicitado e exista id na nuvem
                if (deleteFromCloud && !record.driveFileId.isNullOrBlank()) {
                    try {
                        gdriveProvider.cloudBackupRepository.deleteRemoteBackup(record.driveFileId)
                        dbHelper.insertLog(LogLevel.INFO, "BackupDelete", "Backup ${record.title} também excluído da nuvem.")
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Falha ao excluir backup da nuvem: ${e.message}")
                    }
                }

                dbHelper.deleteBackupRecord(record.id)
                loadBackupRecords()
                _deviceStats.value = generateDeviceStats()
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Erro ao excluir backup.")
            }
        }
    }

    fun pauseCloudUpload() {
        gdriveProvider.cloudBackupRepository.pauseUpload()
    }

    fun resumeCloudUpload() {
        gdriveProvider.cloudBackupRepository.resumeUpload()
    }

    fun cancelCloudUpload() {
        gdriveProvider.cloudBackupRepository.cancelUpload()
    }

    fun uploadRecordToGoogleDrive(record: BackupRecord, targetAccountEmail: String? = null) {
        val targetEmail = targetAccountEmail ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
        Log.d("BackupOrigin", "uploadRecordToGoogleDrive invoked: recordId='${record.id}', title='${record.title}', targetEmail='$targetEmail'")
        viewModelScope.launch {
            val currentCompletedItems = BackupRepository.backupProgress.value?.completedItems ?: emptyList()
            val currentTotalItems = BackupRepository.backupProgress.value?.totalItems ?: currentCompletedItems.size
            try {
                _googleDriveState.value = GoogleDriveState.SYNCING
                val accountDesc = if (!targetEmail.isNullOrBlank()) " ($targetEmail)" else ""
                val opTitle = "Enviando ${record.title} para Google Drive$accountDesc..."
                BackupRepository.setCloudUploadActive(opTitle)
                BackupForegroundService.startService(context, opTitle)
                val result = gdriveProvider.cloudBackupRepository.uploadLocalBackup(record, targetEmail) { progressInfo ->
                    val speedStr = if (progressInfo.speedMBps > 0) " • %.1f MB/s".format(java.util.Locale.US, progressInfo.speedMBps) else ""
                    val etaStr = if (progressInfo.estimatedRemainingSeconds > 0) " • Restam ${progressInfo.estimatedRemainingSeconds}s" else ""
                    val destStr = if (!targetEmail.isNullOrBlank()) " • Destino: $targetEmail" else ""
                    val currentName = "${record.title}$speedStr$etaStr$destStr"

                    BackupRepository.updateProgress(
                        BackupProgress(
                            stage = progressInfo.stage,
                            currentItemName = currentName,
                            progressPercent = progressInfo.progressPercent,
                            bytesProcessed = progressInfo.bytesUploaded,
                            totalBytesEstimated = progressInfo.totalBytes,
                            isFinished = progressInfo.isFinished,
                            error = progressInfo.error,
                            totalItems = currentTotalItems,
                            completedItems = currentCompletedItems
                        )
                    )
                }

                if (result.isSuccess) {
                    val destStr = if (!targetEmail.isNullOrBlank()) " • Enviado para $targetEmail" else ""
                    BackupRepository.updateProgress(
                        BackupProgress(
                            stage = "Concluído",
                            currentItemName = "${record.title} • Sincronizado com Google Drive$destStr",
                            progressPercent = 1.0f,
                            bytesProcessed = record.sizeBytes,
                            totalBytesEstimated = record.sizeBytes,
                            isFinished = true,
                            totalItems = currentTotalItems,
                            completedItems = currentCompletedItems
                        )
                    )
                }

                if (result.isFailure) {
                    val ex = result.exceptionOrNull()
                    val isConsentNeeded = ex is com.resync.app.cloud.gdrive.AuthIntentRequiredException
                    val is401 = isConsentNeeded ||
                            ex is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException ||
                            ex?.message?.contains("401") == true

                    if (targetEmail == null || targetEmail == _activeAccountEmail.value) {
                        if (isConsentNeeded) {
                            _activeAccountReauthIntent.value = (ex as com.resync.app.cloud.gdrive.AuthIntentRequiredException).intent
                            _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        } else if (is401) {
                            _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        }
                    }

                    val accountName = targetEmail ?: "Google Drive"
                    val err = if (is401) {
                        "Backup local gravado com sucesso! Envio ao Google Drive pendente: a sessão da conta '$accountName' expirou (HTTP 401). Reconecte esta conta para sincronizar."
                    } else {
                        "Backup local gravado com sucesso! Envio ao Google Drive pendente: ${ex?.message ?: "Falha na sincronização."}"
                    }

                    BackupRepository.updateProgress(
                        BackupProgress(
                            stage = "Concluído com Avisos",
                            currentItemName = err,
                            progressPercent = 1.0f,
                            bytesProcessed = record.sizeBytes,
                            totalBytesEstimated = record.sizeBytes,
                            isFinished = true,
                            error = err,
                            totalItems = currentTotalItems,
                            completedItems = currentCompletedItems
                        )
                    )
                }
            } catch (e: Exception) {
                val isConsentNeeded = e is com.resync.app.cloud.gdrive.AuthIntentRequiredException
                val is401 = isConsentNeeded ||
                        e is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException ||
                        e.message?.contains("401") == true
                if (targetEmail == null || targetEmail == _activeAccountEmail.value) {
                    if (isConsentNeeded) {
                        _activeAccountReauthIntent.value = (e as com.resync.app.cloud.gdrive.AuthIntentRequiredException).intent
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    } else if (is401) {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    }
                }
                val accountName = targetEmail ?: "Google Drive"
                val msg = if (is401) {
                    "Backup local gravado com sucesso! Envio ao Google Drive pendente: a sessão da conta '$accountName' expirou (HTTP 401). Reconecte esta conta para sincronizar."
                } else {
                    "Backup local gravado com sucesso! Envio ao Google Drive pendente: ${e.message ?: "Erro no upload."}"
                }
                BackupRepository.updateProgress(
                    BackupProgress(
                        stage = "Concluído com Avisos",
                        currentItemName = msg,
                        progressPercent = 1.0f,
                        bytesProcessed = record.sizeBytes,
                        totalBytesEstimated = record.sizeBytes,
                        isFinished = true,
                        error = msg,
                        totalItems = currentTotalItems,
                        completedItems = currentCompletedItems
                    )
                )
            } finally {
                BackupForegroundService.stopService(context)
                loadBackupRecords()
                fetchRemoteDriveBackups()
                refreshDriveAccountInfo()
                if (gdriveProvider.oAuthManager.isSessionExpired()) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                } else if (_googleDriveState.value == GoogleDriveState.SYNCING) {
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                }
            }
        }
    }

    fun downloadAndRestoreFromGoogleDrive(remoteFile: com.resync.app.cloud.gdrive.DriveFileMetadata, password: String?) {
        RestoreRepository.startRestore(
            localFile = null,
            remoteDriveFileId = remoteFile.id,
            accountEmail = _activeAccountEmail.value,
            selectedItemTypes = emptySet(),
            encryptionPassword = password
        )
    }

    fun deleteRemoteDriveBackup(fileId: String) {
        viewModelScope.launch {
            val res = gdriveProvider.cloudBackupRepository.deleteRemoteBackup(fileId)
            if (res.isSuccess) {
                fetchRemoteDriveBackups()
                refreshDriveAccountInfo()
            }
        }
    }

    fun deleteRemoteBackupsForLabel(deviceLabel: String, onFinished: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentLabelTrimmed = deviceLabel.trim()
            val targets = _remoteDriveBackups.value.filter {
                !it.name.endsWith(".index.json") && (
                    it.appProperties["deviceLabel"]?.trim()?.equals(currentLabelTrimmed, ignoreCase = true) == true ||
                    (currentLabelTrimmed.isNotBlank() && it.name.contains(currentLabelTrimmed, ignoreCase = true))
                )
            }
            // Immediately remove from in-memory state to reflect zeroed counters in UI
            val remaining = _remoteDriveBackups.value.filterNot { targets.any { t -> t.id == it.id } }
            _remoteDriveBackups.value = remaining
            _remoteBackupsState.value = RemoteBackupsState.Loaded(remaining)

            var count = 0
            val activeEmail = _activeAccountEmail.value
            for (item in targets) {
                val res = gdriveProvider.cloudBackupRepository.deleteRemoteBackup(item.id, activeEmail)
                if (res.isSuccess) count++
                val baseName = item.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
                val sidecar = _remoteDriveBackups.value.find { it.name == "$baseName.index.json" }
                if (sidecar != null) {
                    gdriveProvider.cloudBackupRepository.deleteRemoteBackup(sidecar.id, activeEmail)
                }
            }
            refreshActiveAccountData(activeEmail, isManual = true)
            withContext(Dispatchers.Main) {
                onFinished(count)
            }
        }
    }

    fun executeScheduleRoutine(schedule: BackupScheduleItem, onStarted: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val appCtx = getApplication<Application>().applicationContext
            val allApps = installedApps.value
            
            val includeApps = schedule.targetItems.contains("APPS")
            val appPairs = if (includeApps) {
                val specificSet = schedule.specificApps.toSet()
                val appsToBackup = if (specificSet.isEmpty()) allApps else allApps.filter { specificSet.contains(it.packageName) }
                val mode = schedule.appBackupMode
                appsToBackup.map { app ->
                    Pair(
                        app,
                        com.resync.app.data.model.AppSelectionOptions(
                            includeBaseApk = schedule.includeBaseApk && (mode == "APK" || mode == "BOTH"),
                            includeSplits = schedule.includeAppSplits && (mode == "APK" || mode == "BOTH"),
                            includeData = schedule.includeAppData && (mode == "DATA" || mode == "BOTH"),
                            includeInternalData = schedule.includeAppData && (mode == "DATA" || mode == "BOTH"),
                            includeExternalData = schedule.includeExternalData && (mode == "DATA" || mode == "BOTH"),
                            includeObb = schedule.includeObb,
                            includePermissions = schedule.includeAppPermissions
                        )
                    )
                }
            } else emptyList()

            val destType = when (schedule.destination) {
                "Nuvem" -> BackupDestinationType.SOMENTE_DRIVE
                "Ambos" -> BackupDestinationType.INTERNA_E_DRIVE
                else -> BackupDestinationType.SOMENTE_INTERNA
            }

            val cloudProvider = if (destType != BackupDestinationType.SOMENTE_INTERNA) {
                CloudProviderType.GOOGLE_DRIVE
            } else null

            val destLabel = when (schedule.destination) {
                "Nuvem" -> "Google Drive"
                "Ambos" -> "Memória interna + Google Drive"
                else -> "Memória interna"
            }

            withContext(Dispatchers.Main) {
                onStarted?.invoke()
            }

            ScheduleManager.getInstance(appCtx).recordExecutionResult(schedule.id, "Executando...")

            BackupRepository.startBackup(
                context = appCtx,
                title = schedule.title,
                selectedApps = appPairs,
                selectedFiles = schedule.selectedFolders,
                includeSms = schedule.targetItems.contains("MESSAGES"),
                includeCalls = schedule.targetItems.contains("CALLS"),
                includeContacts = schedule.targetItems.contains("CONTACTS"),
                includeDns = schedule.targetItems.contains("DNS"),
                includeWallpaper = schedule.targetItems.contains("WALLPAPER"),
                includeWallpaperHome = schedule.targetItems.contains("WALLPAPER"),
                includeWallpaperLock = schedule.targetItems.contains("WALLPAPER"),
                includeWifi = schedule.targetItems.contains("WIFI"),
                includeBluetooth = schedule.targetItems.contains("BLUETOOTH"),
                targetCloudProvider = cloudProvider,
                targetCloudAccountEmail = _activeAccountEmail.value,
                destinationLabel = destLabel,
                destinationType = destType
            )

            try {
                val finishProgress = BackupRepository.backupProgress.filterNotNull().first { it.isFinished }
                val dateStr = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                if (finishProgress.error != null) {
                    ScheduleManager.getInstance(appCtx).recordExecutionResult(schedule.id, "Falha: ${finishProgress.error}")
                } else {
                    ScheduleManager.getInstance(appCtx).recordExecutionResult(schedule.id, "Sucesso ($dateStr)")
                }
            } catch (t: Throwable) {
                Log.w("MainViewModel", "Erro aguardando conclusão do agendamento: ${t.message}")
            }
        }
    }

    fun executeAllScheduleRoutines(routines: List<BackupScheduleItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val enabled = routines.filter { it.isEnabled }
            for (routine in enabled) {
                executeScheduleRoutine(routine)
                try {
                    BackupRepository.backupProgress.filterNotNull().first { it.isFinished }
                } catch (_: Throwable) {}
                delay(2000L)
            }
        }
    }

    fun deleteUnifiedBackup(
        entry: UnifiedBackupEntry,
        deleteMode: String, // "local", "cloud", "both"
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val record = entry.record
                val driveFileId = entry.remoteFileId ?: record.driveFileId

                // 1. Excluir do Google Drive se solicitado ou se o item for puramente em nuvem
                val shouldDeleteDrive = (deleteMode == "cloud" || deleteMode == "both" || !entry.hasLocal) && entry.hasDrive
                if (shouldDeleteDrive && !driveFileId.isNullOrBlank()) {
                    val activeEmail = _activeAccountEmail.value
                    val driveRes = gdriveProvider.cloudBackupRepository.deleteRemoteBackup(driveFileId, activeEmail)
                    if (driveRes.isFailure) {
                        val ex = driveRes.exceptionOrNull()
                        val msg = when (ex) {
                            is com.resync.app.cloud.gdrive.GoogleDriveApiException -> "Erro no Google Drive (HTTP ${ex.statusCode}): ${ex.message}"
                            is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException -> "Sessão expirada no Google Drive (HTTP ${ex.statusCode}). Reconecte sua conta."
                            else -> ex?.message ?: "Falha ao excluir arquivo do Google Drive."
                        }
                        onResult(false, msg)
                        return@launch
                    }
                }

                // 2. Excluir do aparelho se solicitado ou se o item for puramente local
                val shouldDeleteLocal = (deleteMode == "local" || deleteMode == "both" || !entry.hasDrive) && entry.hasLocal
                if (shouldDeleteLocal && record.filePath.isNotBlank()) {
                    val file = java.io.File(record.filePath)
                    if (file.exists()) {
                        file.delete()
                        val parentDir = file.parentFile
                        if (parentDir != null && parentDir.name != com.resync.app.data.model.BackupPaths.BACKUP_DIR_NAME && parentDir.name != "SwiftVaultBackups" && parentDir.listFiles().isNullOrEmpty()) {
                            parentDir.delete()
                        }
                    }
                }

                // 3. Atualizar ou excluir do banco de dados Room / SQLite
                if (deleteMode == "both" || (!entry.hasLocal && shouldDeleteDrive) || (!entry.hasDrive && shouldDeleteLocal)) {
                    dbHelper.deleteBackupRecord(record.id)
                    if (!driveFileId.isNullOrBlank()) {
                        dbHelper.deleteBackupRecordByDriveFileId(driveFileId)
                    }
                } else if (deleteMode == "cloud") {
                    val updatedRecord = record.copy(driveFileId = null, syncStatus = SyncStatus.LOCAL_ONLY, destination = "Dispositivo")
                    dbHelper.insertBackupRecord(updatedRecord)
                } else if (deleteMode == "local") {
                    if (!driveFileId.isNullOrBlank()) {
                        val updatedRecord = record.copy(filePath = "", destination = "Google Drive")
                        dbHelper.insertBackupRecord(updatedRecord)
                    } else {
                        dbHelper.deleteBackupRecord(record.id)
                    }
                }

                // 4. Atualizar estados em memória imediatamente
                loadBackupRecords()
                fetchRemoteDriveBackups()
                refreshDriveAccountInfo()
                _deviceStats.value = generateDeviceStats()

                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Erro ao excluir backup.")
            }
        }
    }

    fun updateAutoSyncSettings(settings: com.resync.app.cloud.gdrive.AutoSyncSettings) {
        _syncSettings.value = settings
        gdriveProvider.syncManager.updateSettings(settings)
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            gdriveProvider.oAuthManager.disconnect()
            dbHelper.updateCloudAccount(
                com.resync.app.data.model.CloudAccount(
                    providerType = com.resync.app.data.model.CloudProviderType.GOOGLE_DRIVE,
                    accountName = "Google Drive (Não configurado)",
                    isConnected = false,
                    quotaUsedBytes = 0L,
                    quotaTotalBytes = 0L,
                    lastSyncTimestamp = 0L
                )
            )
            _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
            _driveQuotaState.value = DriveQuotaState.Idle
            _remoteBackupsState.value = RemoteBackupsState.Idle
            _driveQuota.value = null
            _remoteDriveBackups.value = emptyList()
            loadCloudAccounts()
        }
    }

    private fun generateDeviceStats(): DeviceStats {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalStorage = totalBlocks * blockSize
        val freeStorage = availableBlocks * blockSize
        val usedStorage = totalStorage - freeStorage

        val backupDir = backupEngine.getOrCreateDefaultVaultDir()
        val backupsSize = backupDir.listFiles()?.sumOf { it.length() } ?: 0L

        // Estimates based on standard device distribution
        val appsSize = (usedStorage * 0.40).toLong()
        val photosSize = (usedStorage * 0.25).toLong()
        val videosSize = (usedStorage * 0.15).toLong()
        val docsSize = (usedStorage * 0.05).toLong()
        val otherSize = (usedStorage - (appsSize + photosSize + videosSize + docsSize + backupsSize)).coerceAtLeast(0L)

        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.getMemoryInfo(memoryInfo)

        return DeviceStats(
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (Baklava / API ${Build.VERSION.SDK_INT})",
            apiLevel = Build.VERSION.SDK_INT,
            ramTotalBytes = memoryInfo.totalMem,
            ramAvailableBytes = memoryInfo.availMem,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "2026-10-01",
            storageTotalBytes = totalStorage,
            storageUsedBytes = usedStorage,
            storageFreeBytes = freeStorage,
            backupStorageUsedBytes = backupsSize,
            appsStorageBytes = appsSize,
            photosBytes = photosSize,
            videosBytes = videosSize,
            documentsBytes = docsSize,
            othersBytes = otherSize
        )
    }

    fun isCriticalOperationActive(): Boolean {
        val backupActive = BackupRepository.backupProgress.value?.let { !it.isFinished } ?: false
        val restoreActive = RestoreRepository.restoreProgress.value?.let { !it.isFinished } ?: false
        val syncActive = _syncQueue.value.any { it.status == QueueStatus.RUNNING }
        return backupActive || restoreActive || syncActive
    }

    fun toggleAutoUpdateCheck(enabled: Boolean) {
        appUpdateManager.setAutoCheckEnabled(enabled)
        _isAutoUpdateCheckEnabled.value = enabled
    }

    private var dismissedVersionForSession: Int? = null

    fun checkForUpdateOnStartup() {
        checkForUpdateOnResume()
    }

    fun checkForUpdateOnResume() {
        if (!appUpdateManager.isAutoCheckEnabled()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = appUpdateManager.checkForUpdate(forceManual = false)
            result.onSuccess { releaseInfo ->
                if (releaseInfo != null) {
                    if (dismissedVersionForSession != releaseInfo.versionCode) {
                        _updateState.value = UpdateState.Available(releaseInfo)
                        com.resync.app.worker.AppUpdateWorker.showUpdateNotification(context, releaseInfo)
                    }
                }
            }.onFailure { e ->
                Log.d("MainViewModel", "Checagem automática de versão em background ignorada em silêncio: ${e.message}")
            }
        }
    }

    fun checkForUpdateManual() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking(isManual = true)
            val result = appUpdateManager.checkForUpdate(forceManual = true)
            result.onSuccess { releaseInfo ->
                if (releaseInfo != null) {
                    _updateState.value = UpdateState.Available(releaseInfo)
                } else {
                    _updateState.value = UpdateState.UpToDate(appUpdateManager.getInstalledVersionName())
                }
            }.onFailure { e ->
                _updateState.value = UpdateState.Error(
                    message = "Não foi possível verificar atualizações no momento.",
                    technicalCode = e.message
                )
            }
        }
    }

    /**
     * "Depois": Dismisses the dialog for this session.
     * Does NOT persist to SharedPreferences, so on the next app restart
     * the dialog will show again if the update is still pending.
     */
    fun dismissUpdatePrompt(versionCode: Int? = null) {
        if (versionCode != null) {
            dismissedVersionForSession = versionCode
        }
        _updateState.value = UpdateState.Idle
    }

    fun startUpdateDownload(releaseInfo: AppReleaseInfo) {
        if (isCriticalOperationActive()) {
            _updateState.value = UpdateState.BlockedByOperation(
                "Existe uma operação de backup, restauração ou sincronização em andamento. Aguarde antes de atualizar."
            )
            return
        }

        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(releaseInfo, 0f, 0.0, 0L, releaseInfo.apkSize)

            val downloadResult = appUpdateManager.downloadUpdate(releaseInfo) { progress, speedMb, downloaded, total ->
                _updateState.value = UpdateState.Downloading(releaseInfo, progress, speedMb, downloaded, total)
            }

            downloadResult.onSuccess { apkFile ->
                _updateState.value = UpdateState.Verifying(releaseInfo, "Verificando integridade e assinatura do APK...")
                val verifyResult = appUpdateManager.verifyDownloadedApk(apkFile, releaseInfo.sha256)
                verifyResult.onSuccess {
                    val rootAvailable = runCatching {
                        Shell.isAppGrantedRoot() == true || capabilityManager.isRootGranted()
                    }.getOrDefault(false)

                    if (rootAvailable) {
                        installDownloadedUpdate(apkFile, preferRoot = true)
                    } else {
                        _updateState.value = UpdateState.ReadyToInstall(releaseInfo, apkFile, isRootAvailable = false)
                    }
                }.onFailure { err ->
                    _updateState.value = UpdateState.Error(
                        message = err.message ?: "Falha na verificação de integridade do pacote.",
                        technicalCode = "SECURITY_VERIFICATION_FAILED"
                    )
                }
            }.onFailure { err ->
                _updateState.value = UpdateState.Error(
                    message = "Falha no download da atualização: ${err.message}",
                    technicalCode = "DOWNLOAD_FAILED"
                )
            }
        }
    }

    fun installDownloadedUpdate(apkFile: File, preferRoot: Boolean) {
        if (isCriticalOperationActive()) {
            _updateState.value = UpdateState.BlockedByOperation(
                "Existe uma operação de backup, restauração ou sincronização em andamento. Aguarde antes de atualizar."
            )
            return
        }

        viewModelScope.launch {
            val currentState = _updateState.value
            val releaseInfo = when (currentState) {
                is UpdateState.ReadyToInstall -> currentState.releaseInfo
                is UpdateState.Verifying -> currentState.releaseInfo
                is UpdateState.Downloading -> currentState.releaseInfo
                is UpdateState.Available -> currentState.releaseInfo
                else -> appUpdateManager.getCachedReleaseInfo()
            }
            if (releaseInfo != null) {
                _updateState.value = UpdateState.Installing(releaseInfo, isRoot = preferRoot)
            }

            val installResult = appUpdateManager.installApk(apkFile, preferRoot)
            installResult.onFailure { err ->
                if (err is UnknownSourcesPermissionRequiredException) {
                    try {
                        context.startActivity(err.intent)
                    } catch (_: Exception) {}
                    _updateState.value = UpdateState.Error(
                        message = "Permissão necessária: autorize o ReSync a instalar aplicativos nas configurações do Android.",
                        technicalCode = "PERMISSION_INSTALL_PACKAGES_REQUIRED"
                    )
                } else {
                    _updateState.value = UpdateState.Error(
                        message = "Falha na instalação: ${err.message}",
                        technicalCode = "INSTALLATION_FAILED"
                    )
                }
            }
        }
    }

    // ==========================================
    // MULTI-ACCOUNT GOOGLE DRIVE OPERATIONS
    // ==========================================
    fun selectDriveAccount(email: String?) {
        _activeAccountEmail.value = email
        prefs.edit().putString("active_account_email", email).apply()
        viewModelScope.launch {
            try {
                accountPreferences.setActiveAccountEmail(email)
            } catch (_: Throwable) {}
        }
        if (email != null) {
            gdriveProvider.oAuthManager.setSelectedAccountEmail(email)
            setDefaultDriveAccount(email)
            applyCachedOrRoomAccountData(email)
            refreshActiveAccountData(email, isManual = true)
        } else {
            _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
            _driveQuotaState.value = DriveQuotaState.Idle
            _remoteBackupsState.value = RemoteBackupsState.Idle
            _activeAccountReauthIntent.value = null
        }
    }

    fun addDriveAccount(
        email: String,
        onAuthNeeded: (Intent) -> Unit,
        onComplete: (Result<RoomCloudAccount>) -> Unit
    ) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) {
            _driveAuthErrorMessage.value = "O e-mail da conta Google não pode ser vazio."
            onComplete(Result.failure(IllegalArgumentException("O e-mail da conta Google não pode ser vazio.")))
            return
        }

        viewModelScope.launch {
            _isAuthorizingAccount.value = true
            _driveAuthErrorMessage.value = null
            try {
                when (val res = driveRepository.addAccount(cleanEmail)) {
                    is DriveAuthResult.Success -> {
                        _isAuthorizingAccount.value = false
                        selectDriveAccount(res.account.email)
                        onComplete(Result.success(res.account))
                    }
                    is DriveAuthResult.NeedsUserRecoverableAuth -> {
                        _isAuthorizingAccount.value = false
                        try {
                            onAuthNeeded(res.recoveryIntent)
                        } catch (t: Throwable) {
                            val msg = "Não foi possível abrir a tela de autorização do Google: ${t.message}"
                            _driveAuthErrorMessage.value = msg
                            onComplete(Result.failure(Exception(msg, t)))
                        }
                    }
                    is DriveAuthResult.Error -> {
                        _isAuthorizingAccount.value = false
                        _driveAuthErrorMessage.value = res.errorMessage
                        onComplete(Result.failure(Exception(res.errorMessage, res.cause)))
                    }
                }
            } catch (t: Throwable) {
                _isAuthorizingAccount.value = false
                val msg = "Falha ao adicionar conta: ${t.message ?: "Erro desconhecido"}"
                _driveAuthErrorMessage.value = msg
                onComplete(Result.failure(Exception(msg, t)))
            }
        }
    }

    fun removeDriveAccount(email: String) {
        viewModelScope.launch {
            try {
                accountCache.remove(email)
                driveRepository.removeAccount(email)
                if (_activeAccountEmail.value == email) {
                    val remaining = _multiCloudAccounts.value.filter { it.email != email }
                    val next = remaining.find { it.isDefault }?.email ?: remaining.firstOrNull()?.email
                    selectDriveAccount(next)
                }
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Erro ao remover conta Google: ${t.message}")
            }
        }
    }

    fun setDefaultDriveAccount(email: String) {
        viewModelScope.launch {
            try {
                driveRepository.setDefaultAccount(email)
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Erro ao definir conta padrão: ${t.message}")
            }
        }
    }

    fun refreshDriveAccounts() {
        viewModelScope.launch {
            try {
                driveRepository.revalidateAllAccounts()
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Erro ao revalidar contas: ${t.message}")
            }
        }
    }

    // ==========================================
    // UPLOAD, QUEUE & CLOUD SYNC OPERATIONS
    // ==========================================
    fun enqueueFileUpload(
        localFilePath: String,
        fileName: String,
        targetEmail: String,
        destFolderId: String? = null,
        backupId: String? = null,
        mimeType: String = "application/octet-stream"
    ) {
        viewModelScope.launch {
            val queueItem = CloudSyncQueueItem(
                id = "queue_" + System.currentTimeMillis() + "_" + (1000..9999).random(),
                backupId = backupId ?: ("file_" + System.currentTimeMillis()),
                backupTitle = fileName,
                providerType = CloudProviderType.GOOGLE_DRIVE,
                direction = TransferDirection.UPLOAD,
                status = QueueStatus.PENDING,
                progressBytes = 0L,
                totalBytes = File(localFilePath).length(),
                createdAt = System.currentTimeMillis(),
                priority = 0,
                accountEmail = targetEmail
            )
            dbHelper.insertQueueItem(queueItem)
            loadSyncQueue()

            val workData = androidx.work.workDataOf(
                FileUploadWorker.KEY_ACCOUNT_EMAIL to targetEmail,
                FileUploadWorker.KEY_FILE_PATH to localFilePath,
                FileUploadWorker.KEY_FILE_NAME to fileName,
                FileUploadWorker.KEY_FOLDER_ID to destFolderId,
                FileUploadWorker.KEY_QUEUE_ID to queueItem.id,
                FileUploadWorker.KEY_BACKUP_ID to backupId,
                FileUploadWorker.KEY_MIME_TYPE to mimeType
            )

            val uploadRequestBuilder = androidx.work.OneTimeWorkRequestBuilder<FileUploadWorker>()
                .addTag("upload_${queueItem.id}")
                .addTag("FileUploadWorker")
                .setInputData(workData)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    15,
                    java.util.concurrent.TimeUnit.SECONDS
                )

            try {
                uploadRequestBuilder.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } catch (_: Throwable) {}

            androidx.work.WorkManager.getInstance(context).enqueue(uploadRequestBuilder.build())

            // Publish initial operation to OperationRepository so floating banner and active operations immediately show it
            com.resync.app.engine.OperationRepository.updateUploadOperation(
                operationId = "upload_${queueItem.id}",
                fileName = fileName,
                progressPercent = 0f,
                bytesProcessed = 0L,
                totalBytesEstimated = queueItem.totalBytes,
                isFinished = false,
                status = com.resync.app.engine.OperationStatus.STARTING,
                destination = "Google Drive ($targetEmail)"
            )

            dbHelper.insertLog(LogLevel.INFO, "UploadQueue", "Arquivo $fileName enfileirado para upload via WorkManager ($targetEmail)")
        }
    }

    fun uploadMultipleFiles(
        uris: List<Uri>,
        targetFolder: String = DriveConstants.ROOT_FOLDER_NAME,
        accountEmail: String? = null
    ) {
        val email = accountEmail ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email ?: return
        viewModelScope.launch {
            val structure = driveRepository.getOrCreateSwiftVaultStructure(email)
            val filesFolderId = structure.filesFolderId
            for (uri in uris) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Throwable) {}
                val tempFile = copyUriToTemp(uri)
                if (tempFile != null) {
                    enqueueFileUpload(
                        localFilePath = tempFile.absolutePath,
                        fileName = tempFile.name,
                        targetEmail = email,
                        destFolderId = filesFolderId
                    )
                }
            }
        }
    }

    fun uploadFolderTree(
        treeUri: Uri,
        targetFolder: String = DriveConstants.ROOT_FOLDER_NAME,
        accountEmail: String? = null
    ) {
        val email = accountEmail ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email ?: return
        viewModelScope.launch {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (_: Throwable) {}
            val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            if (rootDoc == null) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Não foi possível acessar a pasta selecionada: $treeUri")
                return@launch
            }
            val rootName = rootDoc.name ?: "Pasta_Enviada"

            // 1. Resolver estrutura base da nuvem (Backups Rápidos, Pastas, Arquivos)
            val parentFoldersId = try {
                val structure = driveRepository.getOrCreateSwiftVaultStructure(email)
                structure.foldersFolderId
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao resolver estrutura base de pastas no Google Drive: ${e.message}")
                return@launch
            }

            if (parentFoldersId.isBlank()) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ID da pasta 'Pastas' inválido. Envio da árvore abortado.")
                return@launch
            }

            // 2. Resolver a pasta raiz do par no Drive
            val targetFolderId = try {
                driveRepository.getOrCreateSubfolder(email, rootName, parentFoldersId)
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao resolver pasta raiz '$rootName' no Drive: ${e.message}")
                return@launch
            }

            if (targetFolderId.isBlank()) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ID inválido retornado para pasta raiz '$rootName'. Envio abortado.")
                return@launch
            }

            // 3. Resolver e confirmar o folderId de TODA a árvore de diretórios ANTES de enviar arquivos
            val dirFolderMap = mutableMapOf<String, String>()
            dirFolderMap[rootDoc.uri.toString()] = targetFolderId

            val allDirsResolved = resolveAllSubfoldersBeforeUpload(rootDoc, email, targetFolderId, dirFolderMap)
            if (!allDirsResolved) {
                dbHelper.insertLog(
                    LogLevel.ERROR,
                    "GoogleDrive",
                    "A resolução de pastas da árvore falhou. Nenhum arquivo foi enviado para impedir pastas vazias ou arquivos na raiz."
                )
                return@launch
            }

            // 4. Com a hierarquia 100% resolvida e confirmada no Google Drive, enfileirar os arquivos
            enqueueFilesForResolvedTree(rootDoc, email, dirFolderMap)
        }
    }

    private suspend fun resolveAllSubfoldersBeforeUpload(
        currentDir: androidx.documentfile.provider.DocumentFile,
        targetEmail: String,
        currentDriveFolderId: String,
        dirFolderMap: MutableMap<String, String>
    ): Boolean {
        for (child in currentDir.listFiles()) {
            if (child.isDirectory) {
                val dirName = child.name ?: "Subpasta"
                val subDriveId = try {
                    driveRepository.getOrCreateSubfolder(targetEmail, dirName, currentDriveFolderId)
                } catch (e: Exception) {
                    dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao resolver subpasta '$dirName' (Pai: $currentDriveFolderId): ${e.message}")
                    return false
                }
                if (subDriveId.isBlank()) {
                    dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ID retornado em branco para subpasta '$dirName'.")
                    return false
                }
                dirFolderMap[child.uri.toString()] = subDriveId
                val subOk = resolveAllSubfoldersBeforeUpload(child, targetEmail, subDriveId, dirFolderMap)
                if (!subOk) return false
            }
        }
        return true
    }

    private suspend fun enqueueFilesForResolvedTree(
        currentDir: androidx.documentfile.provider.DocumentFile,
        targetEmail: String,
        dirFolderMap: Map<String, String>
    ) {
        val destFolderId = dirFolderMap[currentDir.uri.toString()]
        if (destFolderId.isNullOrBlank()) {
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Destino da pasta ${currentDir.name} não localizado. Arquivos não enviados para evitar queda na raiz.")
            return
        }

        for (child in currentDir.listFiles()) {
            if (child.isDirectory) {
                enqueueFilesForResolvedTree(child, targetEmail, dirFolderMap)
            } else if (child.isFile) {
                val tempFile = copyUriToTemp(child.uri, child.name)
                if (tempFile != null) {
                    enqueueFileUpload(
                        localFilePath = tempFile.absolutePath,
                        fileName = child.name ?: tempFile.name,
                        targetEmail = targetEmail,
                        destFolderId = destFolderId,
                        mimeType = child.type ?: "application/octet-stream"
                    )
                } else {
                    dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao preparar cópia local do arquivo '${child.name}' para pasta '$destFolderId'")
                }
            }
        }
    }

    private suspend fun copyUriToTemp(uri: Uri, preferredName: String? = null): File? = withContext(Dispatchers.IO) {
        try {
            val fileName = preferredName ?: getFileNameFromUri(uri) ?: ("upload_" + System.currentTimeMillis())
            val tempFile = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.ERROR, "UploadManager", "Erro ao preparar arquivo temporário: ${e.message}")
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = it.getString(nameIdx)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }

    fun uploadBackupsToDrive(
        records: List<BackupRecord>,
        targetFolder: String = DriveConstants.ROOT_FOLDER_NAME,
        accountEmail: String? = null
    ) {
        val email = accountEmail ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email ?: return
        viewModelScope.launch {
            val structure = driveRepository.getOrCreateSwiftVaultStructure(email)
            val quickBackupsFolderId = structure.quickBackupsFolderId
            for (record in records) {
                val f = File(record.filePath)
                if (!f.exists() || f.length() == 0L) {
                    dbHelper.insertLog(LogLevel.ERROR, "UploadManager", "Arquivo do backup não encontrado: ${record.filePath}")
                    continue
                }
                // Validação de cabeçalho (.svb ou .zip válido)
                val isValidContainer = try {
                    java.io.FileInputStream(f).use { fis ->
                        val magic = ByteArray(4)
                        val r = fis.read(magic)
                        r >= 4 && (
                            magic.contentEquals(com.resync.app.engine.ArchiveEngine.SVB_MAGIC) ||
                            (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte())
                        )
                    }
                } catch (_: Throwable) {
                    false
                }

                if (!isValidContainer) {
                    dbHelper.insertLog(LogLevel.WARN, "UploadManager", "Arquivo ${f.name} possui cabeçalho inválido ou corrompido.")
                }

                enqueueFileUpload(
                    localFilePath = record.filePath,
                    fileName = f.name,
                    targetEmail = email,
                    destFolderId = quickBackupsFolderId,
                    backupId = record.id
                )
            }
        }
    }

    fun downloadAndRestoreDriveBackup(driveFileId: String, fileName: String, targetEmail: String, onDownloaded: (File) -> Unit) {
        viewModelScope.launch {
            val backupsDir = com.resync.app.data.model.BackupPaths.getDefaultLocalBackupDir()
            backupsDir.mkdirs()
            val destFile = File(backupsDir, fileName)
            try {
                driveRepository.downloadFile(targetEmail, driveFileId, destFile)
                dbHelper.insertLog(LogLevel.INFO, "DriveDownload", "Backup $fileName baixado com sucesso.")
                loadBackupRecords()
                onDownloaded(destFile)
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.ERROR, "DriveDownload", "Erro ao baixar backup: ${e.message}")
            }
        }
    }

    fun syncFolderWithDrive(folderFile: File, targetEmail: String) {
        viewModelScope.launch {
            if (!folderFile.exists() || !folderFile.isDirectory) return@launch
            val structure = try {
                driveRepository.getOrCreateSwiftVaultStructure(targetEmail)
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao resolver estrutura para $targetEmail: ${e.message}")
                return@launch
            }
            val targetFolderId = try {
                driveRepository.getOrCreateSubfolder(targetEmail, folderFile.name, structure.foldersFolderId)
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao resolver pasta '${folderFile.name}': ${e.message}")
                return@launch
            }

            if (targetFolderId.isBlank()) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ID em branco para pasta '${folderFile.name}'.")
                return@launch
            }

            val dirFolderMap = mutableMapOf<String, String>()
            dirFolderMap[folderFile.absolutePath] = targetFolderId

            val allDirsResolved = resolveAllLocalSubfoldersBeforeUpload(folderFile, targetEmail, targetFolderId, dirFolderMap)
            if (!allDirsResolved) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "A resolução de subpastas locais falhou para ${folderFile.name}. Sincronização abortada.")
                return@launch
            }

            enqueueLocalFilesForResolvedTree(folderFile, targetEmail, dirFolderMap)
        }
    }

    private suspend fun resolveAllLocalSubfoldersBeforeUpload(
        currentDir: File,
        targetEmail: String,
        currentDriveFolderId: String,
        dirFolderMap: MutableMap<String, String>
    ): Boolean {
        val children = currentDir.listFiles() ?: return true
        for (child in children) {
            if (child.isDirectory) {
                val subDriveId = try {
                    driveRepository.getOrCreateSubfolder(targetEmail, child.name, currentDriveFolderId)
                } catch (e: Exception) {
                    dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao resolver subpasta '${child.name}': ${e.message}")
                    return false
                }
                if (subDriveId.isBlank()) return false
                dirFolderMap[child.absolutePath] = subDriveId
                if (!resolveAllLocalSubfoldersBeforeUpload(child, targetEmail, subDriveId, dirFolderMap)) return false
            }
        }
        return true
    }

    private fun enqueueLocalFilesForResolvedTree(
        currentDir: File,
        targetEmail: String,
        dirFolderMap: Map<String, String>
    ) {
        val destFolderId = dirFolderMap[currentDir.absolutePath] ?: return
        val children = currentDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                enqueueLocalFilesForResolvedTree(child, targetEmail, dirFolderMap)
            } else if (child.isFile) {
                enqueueFileUpload(
                    localFilePath = child.absolutePath,
                    fileName = child.name,
                    targetEmail = targetEmail,
                    destFolderId = destFolderId
                )
            }
        }
    }

    // ==========================================
    // QUICK BACKUP SAVED OPTIONS & UNIFICATION
    // ==========================================
    fun getSavedQuickBackupOptions(): AppSelectionOptions {
        return AppSelectionOptions(
            includeBaseApk = prefs.getBoolean("quick_opt_base_apk", true),
            includeSplits = prefs.getBoolean("quick_opt_splits", true),
            includeData = prefs.getBoolean("quick_opt_data", true),
            includeInternalData = prefs.getBoolean("quick_opt_internal_data", true),
            includeExternalData = prefs.getBoolean("quick_opt_external_data", false),
            includeObb = prefs.getBoolean("quick_opt_obb", false),
            includeUserDe = prefs.getBoolean("quick_opt_user_de", true),
            includeCache = prefs.getBoolean("backup_include_cache", false)
        )
    }

    fun saveQuickBackupOptions(options: AppSelectionOptions) {
        prefs.edit()
            .putBoolean("quick_opt_base_apk", options.includeBaseApk)
            .putBoolean("quick_opt_splits", options.includeSplits)
            .putBoolean("quick_opt_data", options.includeData)
            .putBoolean("quick_opt_internal_data", options.includeInternalData)
            .putBoolean("quick_opt_external_data", options.includeExternalData)
            .putBoolean("quick_opt_obb", options.includeObb)
            .putBoolean("quick_opt_user_de", options.includeUserDe)
            .putBoolean("backup_include_cache", options.includeCache)
            .apply()
    }

    fun launchQuickBackupForApps(packages: List<String>) {
        val options = getSavedQuickBackupOptions()
        val selected = installedApps.value
            .filter { packages.contains(it.packageName) }
            .map { it to options }

        if (selected.isEmpty()) return

        val title = if (selected.size == 1) "Backup Rápido - ${selected.first().first.appName}"
                    else "Backup Rápido - ${selected.size} Apps"

        launchBackup(
            title = title,
            selectedApps = selected,
            selectedFiles = emptyList(),
            includeSms = false,
            includeCalls = false,
            includeContacts = false,
            includeDns = false,
            includeWallpaper = false
        )
    }

    fun launchCustomAppBackup(
        packages: List<String>,
        options: AppSelectionOptions,
        destination: String = "Local",
        driveAccountEmail: String? = null,
        compression: String = "GZIP"
    ) {
        saveQuickBackupOptions(options)
        if (!driveAccountEmail.isNullOrBlank()) {
            selectDriveAccount(driveAccountEmail)
        }

        val selected = installedApps.value
            .filter { packages.contains(it.packageName) }
            .map { it to options }

        if (selected.isEmpty()) return

        val title = if (selected.size == 1) "Backup - ${selected.first().first.appName}"
                    else "Backup - ${selected.size} Apps"

        val targetCloud = if (destination == "Google Drive" || destination == "Ambos") CloudProviderType.GOOGLE_DRIVE else null

        launchBackup(
            title = title,
            selectedApps = selected,
            selectedFiles = emptyList(),
            includeSms = false,
            includeCalls = false,
            includeContacts = false,
            includeDns = false,
            includeWallpaper = false,
            targetCloudProvider = targetCloud
        )
    }

    fun importBackupFromUri(uri: android.net.Uri, onResult: (Result<BackupRecord>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destDir = backupEngine.getOrCreateDefaultVaultDir()
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
                } ?: "imported_backup_${System.currentTimeMillis()}.resync"

                val localTarget = File(destDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    localTarget.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalArgumentException("Não foi possível ler o arquivo selecionado.")

                val manifest = restoreEngine.inspectBackup(localTarget)
                val recordId = manifest.backupId.ifBlank { localTarget.nameWithoutExtension }
                val rec = BackupRecord(
                    id = recordId,
                    title = manifest.title.ifBlank { localTarget.nameWithoutExtension },
                    filePath = localTarget.absolutePath,
                    sizeBytes = localTarget.length(),
                    checksumSha256 = manifest.sha256PayloadHash,
                    isEncrypted = manifest.isEncrypted,
                    timestamp = if (manifest.createdAt > 0) manifest.createdAt else localTarget.lastModified(),
                    itemCount = manifest.items.size,
                    status = BackupStatus.COMPLETED,
                    includedAppsCount = manifest.items.count { it.type == BackupItemType.APP_APK || it.type == BackupItemType.APP_DATA },
                    includedFilesCount = manifest.items.count { it.type == BackupItemType.FILE },
                    includedDns = manifest.items.any { it.type == BackupItemType.DNS_CONFIG },
                    destination = "Memória interna",
                    syncStatus = SyncStatus.LOCAL_ONLY
                )
                dbHelper.insertBackupRecord(rec)
                loadBackupRecords()
                withContext(Dispatchers.Main) {
                    onResult(Result.success(rec))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    // ==========================================
    // SWIFT BACKUP STYLE RESTORATION SUITE
    // ==========================================
    private val _restoreAppItems = MutableStateFlow<List<RestoreAppItem>>(emptyList())
    val restoreAppItems: StateFlow<List<RestoreAppItem>> = _restoreAppItems.asStateFlow()

    private val _restoreAppSelectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val restoreAppSelectedPackages: StateFlow<Set<String>> = _restoreAppSelectedPackages.asStateFlow()

    fun toggleAppPackageSelected(packageName: String, selected: Boolean? = null) {
        _restoreAppSelectedPackages.update { current ->
            val isNowSelected = selected ?: !current.contains(packageName)
            if (isNowSelected) current + packageName else current - packageName
        }
    }

    fun selectAllAppPackages(packageNames: Collection<String>, selectAll: Boolean) {
        _restoreAppSelectedPackages.update { current ->
            if (selectAll) current + packageNames else current - packageNames.toSet()
        }
    }

    fun setAppPackageSelection(selectedMap: Map<String, Boolean>) {
        _restoreAppSelectedPackages.update { current ->
            val set = current.toMutableSet()
            selectedMap.forEach { (pkg, sel) ->
                if (sel) set.add(pkg) else set.remove(pkg)
            }
            set
        }
    }

    private val _restoreSystemItems = MutableStateFlow<List<RestoreSystemItem>>(emptyList())
    val restoreSystemItems: StateFlow<List<RestoreSystemItem>> = _restoreSystemItems.asStateFlow()

    private val _legacyBackups = MutableStateFlow<List<RestoreSystemItem>>(emptyList())
    val legacyBackups: StateFlow<List<RestoreSystemItem>> = _legacyBackups.asStateFlow()

    private val _isRestoreListLoading = MutableStateFlow(false)
    val isRestoreListLoading: StateFlow<Boolean> = _isRestoreListLoading.asStateFlow()

    init {
        // Observe restore progress to update individual item restore status in real-time
        viewModelScope.launch {
            RestoreRepository.restoreProgress.collectLatest { progress ->
                if (progress != null) {
                    val currentItem = progress.currentItemName
                    val isDone = progress.isFinished
                    val hasError = progress.error != null

                    _restoreAppItems.update { list ->
                        list.map { app ->
                            if (app.status == ItemRestoreStatus.RESTORING) {
                                if (isDone && !hasError) {
                                    app.copy(status = ItemRestoreStatus.RESTORED, isInstalled = true)
                                } else if (hasError) {
                                    app.copy(status = ItemRestoreStatus.ERROR, errorMessage = progress.error)
                                } else {
                                    app
                                }
                            } else if (currentItem.contains(app.packageName, ignoreCase = true) || currentItem.contains(app.appName, ignoreCase = true)) {
                                if (hasError) {
                                    app.copy(status = ItemRestoreStatus.ERROR, errorMessage = progress.error)
                                } else {
                                    app.copy(status = ItemRestoreStatus.RESTORING)
                                }
                            } else {
                                app
                            }
                        }
                    }

                    _restoreSystemItems.update { list ->
                        list.map { item ->
                            if (item.status == ItemRestoreStatus.RESTORING) {
                                if (isDone && !hasError) {
                                    item.copy(status = ItemRestoreStatus.RESTORED)
                                } else if (hasError) {
                                    item.copy(status = ItemRestoreStatus.ERROR, errorMessage = progress.error)
                                } else {
                                    item
                                }
                            } else {
                                item
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun findAllLocalContainers(): List<File> = withContext(Dispatchers.IO) {
        val searchDirs = (com.resync.app.data.model.BackupPaths.getAllLocalBackupDirs(context) + listOf(
            File(android.os.Environment.getExternalStorageDirectory(), "Download")
        )).filter { it.exists() && it.canRead() }

        val discovered = mutableListOf<File>()
        val seenPaths = mutableSetOf<String>()

        // 1. Arquivos registrados no banco de dados local
        try {
            val dbRecords = dbHelper.getAllBackupRecords()
            for (rec in dbRecords) {
                if (rec.filePath.isNotBlank()) {
                    val f = File(rec.filePath)
                    if (f.exists() && f.isFile && f.length() > 64) {
                        val path = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                        if (seenPaths.add(path)) {
                            discovered.add(f)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("RestoreScanner", "Erro ao carregar registros do banco: ${e.message}")
        }

        // 2. Varredura recursiva nos diretórios padrão e subpastas (profundidade até 4)
        for (dir in searchDirs) {
            try {
                dir.walkTopDown().maxDepth(4).filter { file ->
                    file.isFile && (file.name.endsWith(".resync", ignoreCase = true) || file.name.endsWith(".svb", ignoreCase = true) || file.name.endsWith(".zip", ignoreCase = true)) &&
                            file.length() > 64 && !file.absolutePath.contains("/staging") && !file.absolutePath.contains("/cache/")
                }.forEach { file ->
                    val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                    if (seenPaths.add(path)) {
                        discovered.add(file)
                    }
                }
            } catch (e: Exception) {
                Log.w("RestoreScanner", "Falha ao varrer diretório ${dir.absolutePath}: ${e.message}")
            }
        }

        val fileNames = discovered.map { it.name }.joinToString(", ")
        dbHelper.insertLog(LogLevel.INFO, "RestoreScanner", "Varredura local: ${discovered.size} containers encontrados em ${searchDirs.size} diretórios: [$fileNames]")
        Log.d("RestoreScanner", "Varredura encontrou ${discovered.size} containers: $fileNames")
        discovered.sortedByDescending { it.lastModified() }
    }

    fun loadRestoreAppItems(source: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRestoreListLoading.value = true
            try {
                val appItemsMap = mutableMapOf<String, MutableList<RestoreAppVersion>>()
                val appInfoMap = mutableMapOf<String, Triple<String, Long, Boolean>>() // appName, installedVersionCode, isSystemApp
                val appTotalSizeMap = mutableMapOf<String, Long>()

                val pm = context.packageManager
                val installedPackages = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstalledPackages(0)
                    }
                }.getOrDefault(emptyList())

                val installedPkgMap = installedPackages.associateBy { it.packageName }

                if (source.equals("device", ignoreCase = true) || source.equals("both", ignoreCase = true)) {
                    val localFiles = findAllLocalContainers()
                    var successContainers = 0

                    for (file in localFiles) {
                        try {
                            val manifest = restoreEngine.inspectBackup(file)
                            val backupTime = if (manifest.createdAt > 0) manifest.createdAt else file.lastModified()
                            val backupTitle = manifest.title.ifBlank { file.nameWithoutExtension }

                            // Group items that belong to apps
                            val appEntries = manifest.items.filter {
                                it.type == BackupItemType.APP_APK ||
                                it.type == BackupItemType.APP_SPLIT_APKS ||
                                it.type == BackupItemType.APP_DATA ||
                                (it.archiveEntryPath.startsWith("apps/") && !it.archiveEntryPath.endsWith("metadata.json"))
                            }

                            val grouped = appEntries.groupBy { item ->
                                item.metadata["packageName"]?.takeIf { it.isNotBlank() }
                                    ?: if (item.archiveEntryPath.startsWith("apps/")) {
                                        item.archiveEntryPath.removePrefix("apps/").substringBefore("/")
                                    } else ""
                            }.filter { it.key.isNotBlank() }

                            for ((pkg, items) in grouped) {
                                val metaItem = manifest.items.find { it.archiveEntryPath == "apps/$pkg/metadata.json" }
                                val vName = metaItem?.metadata?.get("versionName") ?: items.firstOrNull()?.metadata?.get("versionName") ?: "1.0"
                                val vCode = metaItem?.metadata?.get("versionCode")?.toLongOrNull() ?: items.firstOrNull()?.metadata?.get("versionCode")?.toLongOrNull() ?: 1L
                                val appName = metaItem?.metadata?.get("appName")
                                    ?: runCatching { installedPkgMap[pkg]?.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()
                                    ?: items.firstOrNull()?.name?.substringBefore(" (")
                                    ?: pkg
                                val totalBytes = items.sumOf { it.sizeBytes }
                                val itemIds = items.map { it.id }

                                val baseApkItem = items.find { it.type == BackupItemType.APP_APK || it.archiveEntryPath.endsWith("base.apk", ignoreCase = true) || it.archiveEntryPath.endsWith(".apk", ignoreCase = true) }
                                val baseApkPath = baseApkItem?.archiveEntryPath

                                val version = RestoreAppVersion(
                                    versionName = vName,
                                    versionCode = vCode,
                                    backupTimestamp = backupTime,
                                    containerFile = file,
                                    remoteDriveFileId = null,
                                    backupTitle = backupTitle,
                                    totalSizeBytes = totalBytes,
                                    manifestItemIds = itemIds,
                                    baseApkEntryPath = baseApkPath
                                )

                                val list = appItemsMap.getOrPut(pkg) { mutableListOf() }
                                list.add(version)

                                if (!appInfoMap.containsKey(pkg)) {
                                    val installedInfo = installedPkgMap[pkg]
                                    val instCode = if (installedInfo != null) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedInfo.longVersionCode else @Suppress("DEPRECATION") installedInfo.versionCode.toLong()
                                    } else 0L
                                    val isSys = installedInfo?.applicationInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
                                    appInfoMap[pkg] = Triple(appName, instCode, isSys)
                                }
                                appTotalSizeMap[pkg] = (appTotalSizeMap[pkg] ?: 0L) + totalBytes
                            }
                            successContainers++
                            dbHelper.insertLog(LogLevel.INFO, "RestoreScanner", "Container ${file.name} lido com sucesso: ${grouped.size} apps identificados.")
                        } catch (e: Exception) {
                            dbHelper.insertLog(LogLevel.WARN, "RestoreScanner", "Falha ao inspecionar container local ${file.name}: ${e.message}")
                            Log.w("MainViewModel", "Erro ao ler manifest de ${file.name}: ${e.message}")
                        }
                    }
                    dbHelper.insertLog(LogLevel.INFO, "RestoreScanner", "Varredura local finalizada: $successContainers/${localFiles.size} containers lidos com sucesso. Total de ${appItemsMap.size} apps catalogados.")
                }

                if (source.equals("cloud", ignoreCase = true) || source.equals("both", ignoreCase = true)) {
                    val activeEmail = _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
                    val remoteFiles = _remoteDriveBackups.value.ifEmpty {
                        if (activeEmail != null) {
                            gdriveProvider.cloudBackupRepository.fetchRemoteBackups().getOrDefault(emptyList())
                        } else emptyList()
                    }

                    for (driveMeta in remoteFiles) {
                        val backupTime = driveMeta.modifiedTime
                        val backupTitle = driveMeta.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")

                        val dbRec = dbHelper.getAllBackupRecords().find { it.title == backupTitle || it.title == driveMeta.name || it.driveFileId == driveMeta.id }
                        val localFile = if (dbRec != null && dbRec.filePath.isNotBlank()) File(dbRec.filePath).takeIf { it.exists() } else null

                        if (localFile != null) {
                            try {
                                val manifest = restoreEngine.inspectBackup(localFile)
                                val appEntries = manifest.items.filter {
                                    it.type == BackupItemType.APP_APK ||
                                    it.type == BackupItemType.APP_SPLIT_APKS ||
                                    it.type == BackupItemType.APP_DATA ||
                                    (it.archiveEntryPath.startsWith("apps/") && !it.archiveEntryPath.endsWith("metadata.json"))
                                }
                                val grouped = appEntries.groupBy { item ->
                                    item.metadata["packageName"]?.takeIf { it.isNotBlank() }
                                        ?: if (item.archiveEntryPath.startsWith("apps/")) {
                                            item.archiveEntryPath.removePrefix("apps/").substringBefore("/")
                                        } else ""
                                }.filter { it.key.isNotBlank() }

                                for ((pkg, items) in grouped) {
                                    val metaItem = manifest.items.find { it.archiveEntryPath == "apps/$pkg/metadata.json" }
                                    val vName = metaItem?.metadata?.get("versionName") ?: "1.0"
                                    val vCode = metaItem?.metadata?.get("versionCode")?.toLongOrNull() ?: 1L
                                    val appName = metaItem?.metadata?.get("appName") ?: pkg
                                    val totalBytes = items.sumOf { it.sizeBytes }

                                    val baseApkItem = items.find { it.type == BackupItemType.APP_APK || it.archiveEntryPath.endsWith("base.apk", ignoreCase = true) || it.archiveEntryPath.endsWith(".apk", ignoreCase = true) }
                                    val baseApkPath = baseApkItem?.archiveEntryPath

                                    val version = RestoreAppVersion(
                                        versionName = vName,
                                        versionCode = vCode,
                                        backupTimestamp = backupTime,
                                        containerFile = null,
                                        remoteDriveFileId = driveMeta.id,
                                        backupTitle = backupTitle,
                                        totalSizeBytes = totalBytes,
                                        manifestItemIds = items.map { it.id },
                                        baseApkEntryPath = baseApkPath
                                    )
                                    val list = appItemsMap.getOrPut(pkg) { mutableListOf() }
                                    list.add(version)

                                    if (!appInfoMap.containsKey(pkg)) {
                                        val installedInfo = installedPkgMap[pkg]
                                        val instCode = if (installedInfo != null) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedInfo.longVersionCode else @Suppress("DEPRECATION") installedInfo.versionCode.toLong()
                                        } else 0L
                                        val isSys = installedInfo?.applicationInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
                                        appInfoMap[pkg] = Triple(appName, instCode, isSys)
                                    }
                                }
                            } catch (_: Exception) {}
                        } else {
                            // Backup na nuvem sem cópia local: extrai metadados do appProperties do Drive se disponível
                            val titleProp = driveMeta.appProperties["title"] ?: backupTitle
                            val pkgFromProp = driveMeta.appProperties["packageName"]
                            if (!pkgFromProp.isNullOrBlank()) {
                                val vName = driveMeta.appProperties["versionName"] ?: "1.0"
                                val vCode = driveMeta.appProperties["versionCode"]?.toLongOrNull() ?: 1L
                                val appName = driveMeta.appProperties["appName"] ?: pkgFromProp
                                val version = RestoreAppVersion(
                                    versionName = vName,
                                    versionCode = vCode,
                                    backupTimestamp = backupTime,
                                    containerFile = null,
                                    remoteDriveFileId = driveMeta.id,
                                    backupTitle = titleProp,
                                    totalSizeBytes = driveMeta.sizeBytes,
                                    manifestItemIds = emptyList(),
                                    baseApkEntryPath = null
                                )
                                appItemsMap.getOrPut(pkgFromProp) { mutableListOf() }.add(version)
                                if (!appInfoMap.containsKey(pkgFromProp)) {
                                    val installedInfo = installedPkgMap[pkgFromProp]
                                    val instCode = if (installedInfo != null) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedInfo.longVersionCode else @Suppress("DEPRECATION") installedInfo.versionCode.toLong()
                                    } else 0L
                                    val isSys = installedInfo?.applicationInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
                                    appInfoMap[pkgFromProp] = Triple(appName, instCode, isSys)
                                }
                            }
                        }
                    }
                }

                // Transform grouped apps to RestoreAppItem
                val resultList = appItemsMap.map { (pkg, versions) ->
                    val sortedVersions = versions.sortedByDescending { it.backupTimestamp }
                    val latest = sortedVersions.first()
                    val info = appInfoMap[pkg] ?: Triple(pkg, 0L, false)
                    val appName = info.first
                    val instCode = info.second
                    val isSystem = info.third
                    val isInstalled = installedPkgMap.containsKey(pkg)

                    // Get cached or live app icon (se instalado: PackageManager; se não instalado: extrai do APK no backup)
                    var icon = iconCache.getAppIcon(pkg)
                    if (icon == null) {
                        val containerFile = latest.containerFile ?: versions.firstNotNullOfOrNull { it.containerFile }
                        val apkEntry = latest.baseApkEntryPath ?: versions.firstNotNullOfOrNull { it.baseApkEntryPath }
                        if (containerFile != null && apkEntry != null) {
                            icon = iconCache.getAppIconFromContainer(containerFile, apkEntry, pkg)
                        }
                    }

                    RestoreAppItem(
                        packageName = pkg,
                        appName = appName,
                        versionName = latest.versionName,
                        versionCode = latest.versionCode,
                        backupTimestamp = latest.backupTimestamp,
                        containerFile = latest.containerFile,
                        remoteDriveFileId = latest.remoteDriveFileId,
                        driveAccountEmail = _activeAccountEmail.value,
                        backupTitle = latest.backupTitle,
                        totalSizeBytes = latest.totalSizeBytes,
                        manifestItemIds = latest.manifestItemIds,
                        iconBitmap = icon,
                        status = if (isInstalled) ItemRestoreStatus.ALREADY_INSTALLED else ItemRestoreStatus.NOT_RESTORED,
                        isInstalled = isInstalled,
                        installedVersionCode = instCode,
                        isSystemApp = isSystem,
                        allVersions = sortedVersions,
                        baseApkEntryPath = latest.baseApkEntryPath
                    )
                }.sortedBy { it.appName.lowercase() }

                _restoreAppItems.value = resultList
                dbHelper.insertLog(LogLevel.INFO, "RestoreScanner", "Total de aplicativos prontos para exibição: ${resultList.size}")
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.ERROR, "RestoreScanner", "Erro ao carregar lista de apps para restauração: ${e.message}")
                Log.e("MainViewModel", "Erro ao carregar lista de apps para restauração: ${e.message}", e)
            } finally {
                _isRestoreListLoading.value = false
            }
        }
    }

    fun loadRestoreSystemItems(targetType: BackupItemType?, source: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRestoreListLoading.value = true
            try {
                val systemItems = mutableListOf<RestoreSystemItem>()
                val legacyItems = mutableListOf<RestoreSystemItem>()
                val allowedTypes = if (targetType != null) {
                    if (targetType == BackupItemType.FILE) {
                        setOf(BackupItemType.FILE, BackupItemType.FOLDER)
                    } else if (targetType == BackupItemType.WALLPAPER || targetType == BackupItemType.WALLPAPER_LOCK) {
                        setOf(BackupItemType.WALLPAPER, BackupItemType.WALLPAPER_LOCK)
                    } else {
                        setOf(targetType)
                    }
                } else {
                    setOf(
                        BackupItemType.SMS,
                        BackupItemType.CALL_LOG,
                        BackupItemType.WIFI_CONFIG,
                        BackupItemType.BLUETOOTH,
                        BackupItemType.DNS_CONFIG,
                        BackupItemType.WALLPAPER,
                        BackupItemType.WALLPAPER_LOCK,
                        BackupItemType.FILE,
                        BackupItemType.FOLDER
                    )
                }

                fun isInternalForbiddenFile(path: String): Boolean {
                    val pLower = path.lowercase()
                    val fileName = path.substringAfterLast('/')
                    return pLower.endsWith(".dex") ||
                            pLower.endsWith(".class") ||
                            pLower.endsWith(".nomedia") ||
                            pLower.endsWith("manifest.json") ||
                            pLower.contains("$") ||
                            fileName.startsWith("staging_")
                }

                fun formatRealSystemTitleAndDescription(item: ManifestItem, resolvedType: BackupItemType): Pair<String, String> {
                    return when (resolvedType) {
                        BackupItemType.SMS -> {
                            val count = item.metadata["count"] ?: item.name.substringAfter("(").substringBefore(")").takeIf { it.isNotBlank() }
                            val displayTitle = if (count != null) "Mensagens SMS ($count)" else "Mensagens SMS"
                            val displayDesc = if (count != null) "$count mensagem(ns) arquivada(s)" else "Mensagens SMS arquivadas"
                            Pair(displayTitle, displayDesc)
                        }
                        BackupItemType.CALL_LOG -> {
                            val count = item.metadata["count"] ?: item.name.substringAfter("(").substringBefore(")").takeIf { it.isNotBlank() }
                            val displayTitle = if (count != null) "Registro de Chamadas ($count)" else "Registro de Chamadas"
                            val displayDesc = if (count != null) "$count registro(s) de ligação" else "Histórico de chamadas arquivado"
                            Pair(displayTitle, displayDesc)
                        }
                        BackupItemType.WIFI_CONFIG -> {
                            val ssid = item.metadata["ssid"]?.takeIf { it.isNotBlank() }
                                ?: item.name.substringAfter("(").substringBefore(")").takeIf { it.isNotBlank() && !it.endsWith(".xml") && !it.endsWith(".conf") }
                                ?: "Redes Wi-Fi salvas"
                            Pair(ssid, "Ponto de acesso Wi-Fi salvo")
                        }
                        BackupItemType.BLUETOOTH -> {
                            val devName = item.metadata["deviceName"]?.takeIf { it.isNotBlank() }
                                ?: item.name.substringAfter("(").substringBefore(")").takeIf { it.isNotBlank() }
                                ?: item.name
                            val totalBonded = item.metadata["totalBonded"]
                            val desc = if (totalBonded != null) "$totalBonded dispositivo(s) pareado(s)" else "Dispositivo Bluetooth pareado"
                            Pair(devName, desc)
                        }
                        BackupItemType.DNS_CONFIG -> {
                            val host = item.metadata["hostname"]?.takeIf { it.isNotBlank() }
                                ?: item.metadata["mode"]?.takeIf { it.isNotBlank() }
                                ?: "DNS Privado"
                            Pair(host, "Configuração de DNS Privado / DoH")
                        }
                        BackupItemType.WALLPAPER -> {
                            Pair("Tela Inicial", "Papel de parede da tela de início")
                        }
                        BackupItemType.WALLPAPER_LOCK -> {
                            Pair("Tela de Bloqueio", "Papel de parede da tela de bloqueio")
                        }
                        BackupItemType.FOLDER -> {
                            val originalPath = item.originalPath.ifBlank { item.archiveEntryPath.removePrefix("files/") }
                            val count = item.metadata["fileCount"]?.let { " • $it arquivos" } ?: ""
                            Pair(originalPath, "Pasta arquivada$count")
                        }
                        BackupItemType.FILE -> {
                            val originalPath = item.originalPath.ifBlank { item.archiveEntryPath.removePrefix("files/") }
                            Pair(originalPath, "Arquivo arquivado")
                        }
                        else -> Pair(item.name, item.name)
                    }
                }

                if (source.equals("device", ignoreCase = true) || source.equals("both", ignoreCase = true)) {
                    val localFiles = findAllLocalContainers()
                    var successContainers = 0

                    for (file in localFiles) {
                        try {
                            val manifest = try {
                                restoreEngine.inspectBackup(file)
                            } catch (e: Exception) {
                                null
                            }

                            val bTime = if (manifest != null && manifest.createdAt > 0) manifest.createdAt else file.lastModified()
                            val bTitle = manifest?.title?.ifBlank { file.nameWithoutExtension } ?: file.nameWithoutExtension

                            if (manifest == null) {
                                // Container antigo sem manifest.json - adiciona à lista de antigos, NÃO como configuração
                                legacyItems.add(
                                    RestoreSystemItem(
                                        id = "${file.name}_legacy",
                                        type = BackupItemType.FILE,
                                        title = file.name,
                                        description = "Backup antigo sem manifesto",
                                        backupTimestamp = bTime,
                                        containerFile = file,
                                        remoteDriveFileId = null,
                                        backupTitle = bTitle,
                                        sizeBytes = file.length(),
                                        manifestItemId = "",
                                        isAvailable = false,
                                        status = ItemRestoreStatus.ERROR,
                                        errorMessage = "Backup antigo sem manifesto",
                                        isLegacyBackup = true
                                    )
                                )
                                continue
                            }

                            // Filtrar SOMENTE pelo tipo e ocultar arquivos internos
                            val matching = manifest.items.filter { item ->
                                allowedTypes.contains(item.type) && !isInternalForbiddenFile(item.archiveEntryPath)
                            }

                            for (item in matching) {
                                val (displayTitle, displayDesc) = formatRealSystemTitleAndDescription(item, item.type)
                                systemItems.add(
                                    RestoreSystemItem(
                                        id = "${file.name}_${item.id}",
                                        type = item.type,
                                        title = displayTitle,
                                        description = displayDesc,
                                        backupTimestamp = bTime,
                                        containerFile = file,
                                        remoteDriveFileId = null,
                                        backupTitle = bTitle,
                                        sizeBytes = item.sizeBytes,
                                        manifestItemId = item.id,
                                        status = ItemRestoreStatus.NOT_RESTORED,
                                        isLegacyBackup = false
                                    )
                                )
                            }
                            successContainers++
                            dbHelper.insertLog(LogLevel.INFO, "RestoreScanner", "Container ${file.name}: ${matching.size} itens de sistema encontrados para filtro $targetType.")
                        } catch (e: Exception) {
                            dbHelper.insertLog(LogLevel.WARN, "RestoreScanner", "Erro ao inspecionar container local ${file.name} para itens de sistema: ${e.message}")
                        }
                    }
                    dbHelper.insertLog(LogLevel.INFO, "RestoreScanner", "Varredura de itens de sistema finalizada: ${systemItems.size} itens encontrados em $successContainers containers.")
                }

                if (source.equals("cloud", ignoreCase = true) || source.equals("both", ignoreCase = true)) {
                    val activeEmail = _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
                    val remoteFiles = _remoteDriveBackups.value.ifEmpty {
                        if (activeEmail != null) {
                            gdriveProvider.cloudBackupRepository.fetchRemoteBackups().getOrDefault(emptyList())
                        } else emptyList()
                    }

                    for (driveMeta in remoteFiles) {
                        val backupTitle = driveMeta.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
                        val dbRec = dbHelper.getAllBackupRecords().find { it.title == backupTitle || it.title == driveMeta.name || it.driveFileId == driveMeta.id }
                        val localFile = if (dbRec != null && dbRec.filePath.isNotBlank()) File(dbRec.filePath).takeIf { it.exists() } else null

                        try {
                            val manifest = if (localFile != null) {
                                runCatching { restoreEngine.inspectBackup(localFile) }.getOrNull()
                            } else {
                                // Tentar índice complementar primeiro, fallback para Range request
                                gdriveProvider.cloudBackupRepository.fetchCompanionIndexOrManifest(driveMeta.id, driveMeta.name, activeEmail).getOrNull()
                            }

                            if (manifest == null) {
                                legacyItems.add(
                                    RestoreSystemItem(
                                        id = "${driveMeta.id}_legacy",
                                        type = BackupItemType.FILE,
                                        title = driveMeta.name,
                                        description = "Backup antigo na nuvem sem manifesto",
                                        backupTimestamp = driveMeta.modifiedTime,
                                        containerFile = null,
                                        remoteDriveFileId = driveMeta.id,
                                        driveAccountEmail = activeEmail,
                                        backupTitle = backupTitle,
                                        sizeBytes = driveMeta.sizeBytes,
                                        manifestItemId = "",
                                        isAvailable = false,
                                        status = ItemRestoreStatus.ERROR,
                                        errorMessage = "Backup antigo sem manifesto",
                                        isLegacyBackup = true
                                    )
                                )
                                continue
                            }

                            val matching = manifest.items.filter { item ->
                                allowedTypes.contains(item.type) && !isInternalForbiddenFile(item.archiveEntryPath)
                            }

                            for (item in matching) {
                                val (displayTitle, displayDesc) = formatRealSystemTitleAndDescription(item, item.type)
                                systemItems.add(
                                    RestoreSystemItem(
                                        id = "${driveMeta.id}_${item.id}",
                                        type = item.type,
                                        title = displayTitle,
                                        description = "$displayDesc (Nuvem)",
                                        backupTimestamp = driveMeta.modifiedTime,
                                        containerFile = null,
                                        remoteDriveFileId = driveMeta.id,
                                        driveAccountEmail = activeEmail,
                                        backupTitle = backupTitle,
                                        sizeBytes = item.sizeBytes,
                                        manifestItemId = item.id,
                                        status = ItemRestoreStatus.NOT_RESTORED,
                                        isLegacyBackup = false
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                _restoreSystemItems.value = systemItems.sortedByDescending { it.backupTimestamp }
                _legacyBackups.value = legacyItems.sortedByDescending { it.backupTimestamp }
                dbHelper.insertLog(LogLevel.INFO, "RestoreScanner", "Total de itens de sistema prontos: ${systemItems.size} | Backups legados: ${legacyItems.size}")
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.ERROR, "RestoreScanner", "Erro ao carregar itens de sistema: ${e.message}")
                Log.e("MainViewModel", "Erro ao carregar itens do sistema para restauração: ${e.message}", e)
            } finally {
                _isRestoreListLoading.value = false
            }
        }
    }

    fun deleteLegacyBackup(item: RestoreSystemItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (item.containerFile != null && item.containerFile.exists()) {
                    item.containerFile.delete()
                    dbHelper.insertLog(LogLevel.INFO, "Restore", "Backup legado local excluído: ${item.containerFile.name}")
                }
                if (!item.remoteDriveFileId.isNullOrBlank()) {
                    val email = item.driveAccountEmail ?: _activeAccountEmail.value
                    gdriveProvider.cloudBackupRepository.deleteRemoteBackup(item.remoteDriveFileId)
                    dbHelper.insertLog(LogLevel.INFO, "Restore", "Backup legado na nuvem excluído: ${item.title}")
                }
                _legacyBackups.update { list -> list.filter { it.id != item.id } }
                loadBackupRecords()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Erro ao excluir backup legado: ${e.message}", e)
                dbHelper.insertLog(LogLevel.ERROR, "Restore", "Erro ao excluir backup legado: ${e.message}")
            }
        }
    }

    fun executeAppRestore(
        selectedApps: List<RestoreAppItem>,
        options: RestoreAppOptions,
        onFinished: (() -> Unit)? = null
    ) {
        val appCount = selectedApps.size
        Log.d("RestoreEngine", "executeAppRestore acionado: $appCount apps selecionados | Opções: $options")

        if (selectedApps.isEmpty()) {
            Log.w("RestoreEngine", "executeAppRestore abortado: lista de aplicativos selecionados está vazia.")
            viewModelScope.launch {
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Restauração de apps cancelada: nenhum app selecionado.")
            }
            return
        }

        viewModelScope.launch {
            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Iniciando restauração de $appCount apps selecionados. Opções: $options")
            // Group selected items by container file or remote drive ID
            val byContainer = selectedApps.groupBy { it.containerFile?.absolutePath ?: it.remoteDriveFileId ?: "" }

            for ((containerKey, group) in byContainer) {
                val first = group.first()
                val container = first.containerFile
                val remoteId = first.remoteDriveFileId
                val email = first.driveAccountEmail ?: _activeAccountEmail.value

                Log.d("RestoreEngine", "Preparando container: '$containerKey' para ${group.size} apps: ${group.map { it.packageName }}")

                // Filter manifest item IDs based on selected options: APKs, Data, External Data
                val targetManifestItemIds = mutableSetOf<String>()
                if (container != null && container.exists()) {
                    try {
                        val manifest = restoreEngine.inspectBackup(container)
                        for (app in group) {
                            val appEntries = manifest.items.filter {
                                (it.metadata["packageName"] == app.packageName) ||
                                (it.archiveEntryPath.startsWith("apps/${app.packageName}/"))
                            }

                            for (item in appEntries) {
                                val isApk = item.type == BackupItemType.APP_APK ||
                                            item.type == BackupItemType.APP_SPLIT_APKS ||
                                            item.archiveEntryPath.endsWith(".apk")
                                val isInternalData = item.archiveEntryPath.endsWith("data.tar") ||
                                                     item.archiveEntryPath.endsWith("data.tar.gz") ||
                                                     item.archiveEntryPath.endsWith("user_de.tar") ||
                                                     item.archiveEntryPath.endsWith("user_de.tar.gz") ||
                                                     item.metadata["dataType"] == "internal_data"
                                val isExtData = item.archiveEntryPath.endsWith("ext_data.tar") ||
                                                item.archiveEntryPath.endsWith("ext_data.tar.gz") ||
                                                item.archiveEntryPath.endsWith("obb.tar") ||
                                                item.archiveEntryPath.endsWith("obb.tar.gz") ||
                                                item.metadata["dataType"] == "external_data" ||
                                                item.metadata["dataType"] == "obb"

                                if (isApk && options.restoreApkAndSplits) {
                                    targetManifestItemIds.add(item.id)
                                } else if (isInternalData && options.restoreData) {
                                    targetManifestItemIds.add(item.id)
                                } else if (isExtData && options.restoreExternalData) {
                                    targetManifestItemIds.add(item.id)
                                } else if (!isApk && !isInternalData && !isExtData) {
                                    targetManifestItemIds.add(item.id)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("RestoreEngine", "Falha ao inspecionar manifesto para filtrar opções: ${e.message}")
                    }
                }

                // Fallback: se nenhum item filtrado especificamente, usa os IDs armazenados no app
                val finalItemIds = if (targetManifestItemIds.isNotEmpty()) {
                    targetManifestItemIds
                } else {
                    group.flatMap { it.manifestItemIds }.toSet()
                }

                Log.d("RestoreEngine", "Enviando ${finalItemIds.size} itens de manifesto para restauração no RestoreRepository")
                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Iniciando RestoreRepository para ${group.size} apps (${finalItemIds.size} itens no container $containerKey)")

                // Mark items as restoring in StateFlow
                _restoreAppItems.update { list ->
                    list.map { app ->
                        if (group.any { it.packageName == app.packageName }) {
                            app.copy(status = ItemRestoreStatus.RESTORING)
                        } else app
                    }
                }

                RestoreRepository.startRestore(
                    localFile = container,
                    remoteDriveFileId = remoteId,
                    accountEmail = email,
                    selectedItemTypes = finalItemIds,
                    encryptionPassword = null
                )
            }
            onFinished?.invoke()
        }
    }

    fun executeSystemRestore(
        selectedItems: List<RestoreSystemItem>,
        onFinished: (() -> Unit)? = null
    ) {
        if (selectedItems.isEmpty()) return
        viewModelScope.launch {
            val byContainer = selectedItems.groupBy { it.containerFile?.absolutePath ?: it.remoteDriveFileId ?: "" }

            for ((_, group) in byContainer) {
                val first = group.first()
                val container = first.containerFile
                val remoteId = first.remoteDriveFileId
                val email = first.driveAccountEmail ?: _activeAccountEmail.value
                val itemIds = group.map { it.manifestItemId }.toSet()

                _restoreSystemItems.update { list ->
                    list.map { item ->
                        if (group.any { it.id == item.id }) {
                            item.copy(status = ItemRestoreStatus.RESTORING)
                        } else item
                    }
                }

                RestoreRepository.startRestore(
                    localFile = container,
                    remoteDriveFileId = remoteId,
                    accountEmail = email,
                    selectedItemTypes = itemIds,
                    encryptionPassword = null
                )
            }
            onFinished?.invoke()
        }
    }

    // ==========================================
    // OPERAÇÕES DE APP INDIVIDUAL (ROOT & SISTEMA)
    // ==========================================

    fun uninstallApp(packageName: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            if (capabilityManager.isRootGranted()) {
                val res = withContext(Dispatchers.IO) {
                    Shell.cmd("pm uninstall $packageName").exec()
                }
                if (res.isSuccess) {
                    refreshInstalledApps(force = true)
                    onResult(true, null)
                } else {
                    onResult(false, res.err.joinToString("\n").ifBlank { "Erro ao desinstalar via root" })
                }
            } else {
                onResult(false, "REQUER_INTENT")
            }
        }
    }

    fun clearAppData(packageName: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            if (capabilityManager.isRootGranted()) {
                val res = withContext(Dispatchers.IO) {
                    Shell.cmd("pm clear $packageName").exec()
                }
                if (res.isSuccess) {
                    onResult(true, null)
                } else {
                    onResult(false, res.err.joinToString("\n").ifBlank { "Falha ao limpar dados do app" })
                }
            } else {
                onResult(false, "Sem permissão root para limpar dados diretamente.")
            }
        }
    }

    fun isAppDisabled(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            !ai.enabled
        } catch (_: Exception) {
            false
        }
    }

    fun setAppEnabled(packageName: String, enable: Boolean, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            if (capabilityManager.isRootGranted()) {
                val cmdStr = if (enable) "pm enable $packageName" else "pm disable-user --user 0 $packageName"
                val res = withContext(Dispatchers.IO) {
                    Shell.cmd(cmdStr).exec()
                }
                if (res.isSuccess) {
                    onResult(true, null)
                } else {
                    onResult(false, res.err.joinToString("\n").ifBlank { "Falha ao alterar estado do app" })
                }
            } else {
                onResult(false, "Requer root para desabilitar/habilitar pacotes.")
            }
        }
    }

    fun forceStopApp(packageName: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val success = ProcessControlManager.safeForceStop(context, packageName, 1f, "AppDetailScreen")
            if (success) {
                onResult(true, null)
            } else {
                onResult(false, "Não foi possível forçar a parada do aplicativo ou pacote protegido.")
            }
        }
    }

    fun isAppIgnoringBatteryOptimizations(packageName: String): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) ?: false
    }

    fun setAppBatteryOptimization(packageName: String, ignore: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (capabilityManager.isRootGranted()) {
                val op = if (ignore) "+$packageName" else "-$packageName"
                val res = withContext(Dispatchers.IO) {
                    Shell.cmd("dumpsys deviceidle whitelist $op").exec()
                }
                onResult(res.isSuccess)
            } else {
                onResult(false)
            }
        }
    }

    fun isAppFavorite(packageName: String): Boolean {
        return prefs.getBoolean("fav_$packageName", false)
    }

    fun setAppFavorite(packageName: String, fav: Boolean) {
        prefs.edit().putBoolean("fav_$packageName", fav).apply()
    }

    fun isAppBlacklisted(packageName: String): Boolean {
        return prefs.getBoolean("blacklist_$packageName", false)
    }

    fun setAppBlacklisted(packageName: String, blacklisted: Boolean) {
        prefs.edit().putBoolean("blacklist_$packageName", blacklisted).apply()
    }

    fun getAppTags(packageName: String): List<String> {
        val str = prefs.getString("tags_$packageName", "") ?: ""
        return if (str.isBlank()) emptyList() else str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setAppTags(packageName: String, tags: List<String>) {
        val str = tags.filter { it.isNotBlank() }.joinToString(",")
        prefs.edit().putString("tags_$packageName", str).apply()
    }

    // ==========================================
    // OPERAÇÕES DE CATEGORIAS (MENSAGENS, CHAMADAS, ETC)
    // ==========================================

    private data class CategoryConfig(
        val title: String,
        val sms: Boolean,
        val calls: Boolean,
        val contacts: Boolean = false,
        val wifi: Boolean = false,
        val wall: Boolean = false,
        val wallHome: Boolean = false,
        val wallLock: Boolean = false,
        val bt: Boolean = false,
        val dns: Boolean = false
    )

    fun launchCategoryBackup(
        category: String,
        isCloud: Boolean,
        email: String? = null
    ) {
        val destType = if (isCloud) BackupDestinationType.SOMENTE_DRIVE else BackupDestinationType.SOMENTE_INTERNA
        launchCategoryBackup(
            category = category,
            destinationType = destType,
            email = email,
            includeHomeWallpaper = true,
            includeLockWallpaper = true
        )
    }

    fun launchCategoryBackup(
        category: String,
        destinationType: BackupDestinationType,
        email: String? = null,
        includeHomeWallpaper: Boolean = true,
        includeLockWallpaper: Boolean = true,
        selectedAppPackages: List<String> = emptyList(),
        selectedCategoryItemIds: Set<String>? = null
    ) {
        val isCloud = destinationType == BackupDestinationType.SOMENTE_DRIVE || destinationType == BackupDestinationType.INTERNA_E_DRIVE
        val targetEmail = email ?: _activeAccountEmail.value ?: _multiCloudAccounts.value.firstOrNull()?.email
        val isAppsCat = category.lowercase() in listOf("apps", "aplicativos")
        val appsToBackup: List<Pair<AppInfo, AppSelectionOptions>> = if (isAppsCat) {
            val appInfoMap = installedApps.value.associateBy { it.packageName }
            val pkgs = if (selectedAppPackages.isNotEmpty()) {
                selectedAppPackages
            } else if (selectedCategoryItemIds != null && selectedCategoryItemIds.isNotEmpty()) {
                selectedCategoryItemIds.toList()
            } else {
                installedApps.value.filter { !it.isSystemApp }.map { it.packageName }
            }
            pkgs.mapNotNull { pkg ->
                appInfoMap[pkg]?.let { appInfo ->
                    Pair(appInfo, AppSelectionOptions())
                }
            }
        } else {
            emptyList()
        }

        val cfg = when (category.lowercase()) {
            "apps", "aplicativos" -> CategoryConfig(if (appsToBackup.size == 1) "Backup de Aplicativo" else "Backup de Aplicativos", sms = false, calls = false, contacts = false, wifi = false, wall = false, bt = false, dns = false)
            "messages", "sms" -> CategoryConfig("Backup de Mensagens", sms = true, calls = false, contacts = false, wifi = false, wall = false, bt = false, dns = false)
            "calls" -> CategoryConfig("Backup de Registro de Chamadas", sms = false, calls = true, contacts = false, wifi = false, wall = false, bt = false, dns = false)
            "contacts" -> CategoryConfig("Backup de Contatos", sms = false, calls = false, contacts = true, wifi = false, wall = false, bt = false, dns = false)
            "wifi" -> CategoryConfig("Backup de Redes Wi-Fi", sms = false, calls = false, contacts = false, wifi = true, wall = false, bt = false, dns = false)
            "wallpaper", "wallpapers" -> CategoryConfig("Backup de Papéis de Parede", sms = false, calls = false, contacts = false, wifi = false, wall = true, wallHome = includeHomeWallpaper, wallLock = includeLockWallpaper, bt = false, dns = false)
            "bluetooth" -> CategoryConfig("Backup de Dispositivos Bluetooth", sms = false, calls = false, contacts = false, wifi = false, wall = false, bt = true, dns = false)
            "dns" -> CategoryConfig("Backup de Configuração DNS", sms = false, calls = false, contacts = false, wifi = false, wall = false, bt = false, dns = true)
            else -> CategoryConfig("Backup de Sistema ($category)", false, false, false, false, false, false, false, false, false)
        }

        val destLabel = when (destinationType) {
            BackupDestinationType.SOMENTE_DRIVE -> if (!targetEmail.isNullOrBlank()) "Drive • $targetEmail" else "Google Drive"
            BackupDestinationType.INTERNA_E_DRIVE -> "Interna + Drive"
            else -> "Memória interna"
        }

        launchBackup(
            title = cfg.title,
            selectedApps = appsToBackup,
            selectedFiles = emptyList(),
            includeSms = cfg.sms,
            includeCalls = cfg.calls,
            includeContacts = cfg.contacts,
            includeWifi = cfg.wifi,
            includeWallpaper = cfg.wall,
            includeWallpaperHome = if (cfg.wall) cfg.wallHome else false,
            includeWallpaperLock = if (cfg.wall) cfg.wallLock else false,
            includeBluetooth = cfg.bt,
            includeDns = cfg.dns,
            targetCloudProvider = if (isCloud) CloudProviderType.GOOGLE_DRIVE else null,
            targetCloudAccountEmail = if (isCloud) targetEmail else null,
            destinationLabel = destLabel,
            destinationType = destinationType,
            selectedCategoryItemIds = selectedCategoryItemIds
        )
    }

    fun restoreCategoryBackup(
        entry: UnifiedBackupEntry,
        category: String
    ) {
        viewModelScope.launch {
            val containerFile = if (entry.hasLocal && entry.record.filePath.isNotBlank()) File(entry.record.filePath).takeIf { it.exists() } else null
            val remoteId = entry.remoteFileId ?: entry.record.driveFileId
            val email = _activeAccountEmail.value

            val itemIds = mutableSetOf<String>()
            if (containerFile != null && containerFile.exists()) {
                try {
                    val manifest = restoreEngine.inspectBackup(containerFile)
                    for (item in manifest.items) {
                        val matches = when (category.lowercase()) {
                            "apps", "aplicativos" -> item.type in listOf(BackupItemType.APP_APK, BackupItemType.APP_SPLIT_APKS, BackupItemType.APP_DATA) || item.archiveEntryPath.startsWith("apps/")
                            "messages", "sms" -> item.type == BackupItemType.SMS || item.archiveEntryPath.contains("sms")
                            "calls" -> item.type == BackupItemType.CALL_LOG || item.archiveEntryPath.contains("call")
                            "contacts" -> item.type == BackupItemType.CONTACTS || item.archiveEntryPath.contains("contact")
                            "wifi" -> item.type == BackupItemType.WIFI_CONFIG || item.archiveEntryPath.contains("wifi")
                            "wallpaper", "wallpapers" -> item.type == BackupItemType.WALLPAPER || item.type == BackupItemType.WALLPAPER_LOCK || item.archiveEntryPath.contains("wallpaper")
                            "bluetooth" -> item.type == BackupItemType.BLUETOOTH || item.archiveEntryPath.contains("bluetooth")
                            "dns" -> item.type == BackupItemType.DNS_CONFIG || item.archiveEntryPath.contains("dns")
                            else -> false
                        }
                        if (matches) {
                            itemIds.add(item.id)
                        }
                    }
                } catch (_: Exception) {}
            }

            RestoreRepository.startRestore(
                localFile = containerFile,
                remoteDriveFileId = remoteId,
                accountEmail = email,
                selectedItemTypes = itemIds,
                encryptionPassword = null
            )
        }
    }

    fun restoreSingleAppBackup(entry: UnifiedBackupEntry, packageName: String) {
        viewModelScope.launch {
            val containerFile = if (entry.hasLocal && entry.record.filePath.isNotBlank()) File(entry.record.filePath).takeIf { it.exists() } else null
            val remoteId = entry.remoteFileId ?: entry.record.driveFileId
            val email = _activeAccountEmail.value

            val itemIds = mutableSetOf<String>()
            if (containerFile != null && containerFile.exists()) {
                try {
                    val manifest = restoreEngine.inspectBackup(containerFile)
                    for (item in manifest.items) {
                        if (item.metadata["packageName"] == packageName || item.archiveEntryPath.startsWith("apps/$packageName/")) {
                            itemIds.add(item.id)
                        }
                    }
                } catch (_: Exception) {}
            }

            RestoreRepository.startRestore(
                localFile = containerFile,
                remoteDriveFileId = remoteId,
                accountEmail = email,
                selectedItemTypes = itemIds,
                encryptionPassword = null
            )
        }
    }
}



