package com.resync.app.data.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.google.gson.annotations.SerializedName

/**
 * Types of granular backup items supported by SwiftVault
 */
enum class BackupItemType {
    @SerializedName("app_apk")
    APP_APK,

    @SerializedName("app_split_apks")
    APP_SPLIT_APKS,

    @SerializedName("app_data")
    APP_DATA,

    @SerializedName("folder")
    FOLDER,

    @SerializedName("file")
    FILE,

    @SerializedName("sms")
    SMS,

    @SerializedName("call_log")
    CALL_LOG,

    @SerializedName("dns_config")
    DNS_CONFIG,

    @SerializedName("wifi_config")
    WIFI_CONFIG,

    @SerializedName("contacts")
    CONTACTS,

    @SerializedName("wallpaper")
    WALLPAPER,

    @SerializedName("wallpaper_lock")
    WALLPAPER_LOCK,

    @SerializedName("bluetooth")
    BLUETOOTH
}

/**
 * Model representing a paired or connected Bluetooth device
 */
data class BluetoothDeviceRecord(
    val name: String,
    val address: String,
    val deviceClass: String,
    val bondState: String,
    val isConnected: Boolean = false,
    val connectedProfiles: List<String> = emptyList()
)

/**
 * Metadata package structure for Bluetooth backup
 */
data class BluetoothBackupData(
    val backupTimestamp: Long,
    val totalBonded: Int,
    val totalConnected: Int,
    val configFilePresent: Boolean,
    val configStatusMessage: String,
    val bondedDevices: List<BluetoothDeviceRecord> = emptyList(),
    val connectedDevices: List<BluetoothDeviceRecord> = emptyList()
)

/**
 * Contact record model for backup and restore
 */
data class ContactRecord(
    val id: String,
    val displayName: String,
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList()
)

/**
 * App information retrieved from PackageManager
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val sourceDir: String,
    val splitSourceDirs: List<String> = emptyList(),
    val dataDir: String,
    val isSystemApp: Boolean,
    val sizeBytes: Long,
    val lastInstallTime: Long,
    val lastUpdateTime: Long,
    val lastBackupDate: String? = null,
    val hasBackup: Boolean = false,
    val isOutdatedBackup: Boolean = false,
    val backupSize: Long = 0L,
    val architecture: String = "arm64-v8a",
    val isInternalSystemPackage: Boolean = false,
    @Transient var iconBitmap: ImageBitmap? = null
)

/**
 * Granular options when backing up an individual app
 */
data class AppSelectionOptions(
    val includeBaseApk: Boolean = true,
    val includeSplits: Boolean = true,
    val includeData: Boolean = true,
    val includeInternalData: Boolean = true,
    val includeExternalData: Boolean = false,
    val includeObb: Boolean = false,
    val includeUserDe: Boolean = true,
    val includePermissions: Boolean = true,
    val includeCache: Boolean = false,
    val selectedFiles: List<String> = emptyList(),
    val relatedSettings: Boolean = false
)

/**
 * Metadata JSON structure for app backup
 */
data class AppBackupMetadata(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val backupTimestamp: Long,
    val files: List<AppBackupFileEntry>
)

data class AppBackupFileEntry(
    val fileName: String,
    val entryPath: String,
    val sizeBytes: Long,
    val sha256Checksum: String,
    val fileType: String
)

/**
 * Hierarchical tree node for File & Folder Explorer
 */
data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val childrenCount: Int = 0,
    val extension: String = "",
    val isSelected: Boolean = false,
    val isPartiallySelected: Boolean = false,
    val children: List<FileNode> = emptyList()
)

/**
 * Reusable backup filter criteria
 */
data class BackupFilter(
    val id: String,
    val name: String,
    val includedExtensions: List<String> = emptyList(), // e.g. .jpg, .png, .mp4, .pdf
    val minSizeBytes: Long = 0L, // e.g. > 100MB
    val maxAgeDays: Int = 0, // e.g. 30 days
    val excludedPatterns: List<String> = listOf("*.tmp", "*.cache"),
    val isIncremental: Boolean = false
)

/**
 * DNS & Network configuration snapshot
 */
data class DnsConfig(
    val mode: String, // "off", "opportunistic", "hostname"
    val isActive: Boolean,
    val hostname: String?,
    val dnsServers: List<String> = emptyList(),
    val interfaceName: String? = null,
    val isVpnActive: Boolean = false,
    val rawMode: String = mode,
    val perNetworkDns: Map<String, String> = emptyMap(),
    val capturedAt: Long = System.currentTimeMillis()
)

/**
 * SMS record model for backup and restore
 */
data class SmsRecord(
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Int
)

/**
 * Call log record model for backup and restore
 */
data class CallRecord(
    val number: String,
    val date: Long,
    val duration: Long,
    val type: Int,
    val cachedName: String? = null,
    val cachedNumberType: Int = 0
)

/**
 * Wallpaper & system preferences backup model
 */
data class SystemSettingsConfig(
    val wallpaperBase64: String? = null,
    val screenTimeout: Int = 30000,
    val fontScale: Float = 1.0f,
    val autoRotate: Boolean = true,
    val hapticFeedback: Boolean = true,
    val capturedAt: Long = System.currentTimeMillis()
)

/**
 * Specification for multi-part archives (.zip.001, .zip.002...)
 */
data class BackupPartInfo(
    val partIndex: Int,
    val fileName: String,
    val sizeBytes: Long,
    val sha256Checksum: String,
    val startByte: Long = 0L,
    val endByte: Long = 0L,
    val cloudAccountId: String? = null
)

/**
 * Item specification inside the SwiftVault Container Manifest
 */
data class ManifestItem(
    val id: String,
    val type: BackupItemType,
    val name: String,
    val originalPath: String,
    val archiveEntryPath: String,
    val sizeBytes: Long,
    val sha256Checksum: String,
    val isEncrypted: Boolean = false,
    val compressionMethod: String = "DEFLATE", // "STORED" or "DEFLATE"
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Manifest header written inside SwiftVault containers (.zip and legacy .svb)
 */
data class BackupManifest(
    val formatIdentifier: String = "SwiftVault",
    val manifestVersion: Int = 2,
    val vaultVersion: String = "2.0.0",
    val backupId: String,
    val title: String,
    val deviceName: String = "",
    val androidSdk: Int = 0,
    val androidRelease: String = "",
    val rootManager: String? = null,
    val createdAt: Long,
    val isEncrypted: Boolean,
    val encryptionAlgorithm: String = "AES-256-GCM-CHUNKED",
    val compressionAlgorithm: String = "ZIP-MIXED",
    val totalSizeBytes: Long,
    val items: List<ManifestItem>,
    val sha256PayloadHash: String = "",
    val parts: List<BackupPartInfo> = emptyList()
)

/**
 * Stored Backup history record
 */
data class BackupRecord(
    val id: String,
    val title: String,
    val filePath: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val isEncrypted: Boolean,
    val timestamp: Long,
    val itemCount: Int,
    val status: BackupStatus,
    val includedAppsCount: Int = 0,
    val includedFilesCount: Int = 0,
    val includedDns: Boolean = false,
    val destination: String = "Local",
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val driveFileId: String? = null
)

enum class BackupStatus {
    COMPLETED,
    IN_PROGRESS,
    FAILED,
    CORRUPTED,
    VERIFYING
}

/**
 * Single Source of Truth for Cloud Synchronization Status
 */
enum class SyncStatus(val displayName: String) {
    LOCAL_ONLY("Não sincronizado"),
    QUEUED("Na fila"),
    UPLOADING("Enviando..."),
    UPLOADED("Enviado"),
    VERIFYING("Verificando integridade"),
    SYNCED("Sincronizado"),
    DOWNLOADING("Baixando..."),
    FAILED("Falha na sincronização"),
    PAUSED("Pausado"),
    CONFLICT("Conflito")
}

/**
 * Destination selection for backup operation
 */
enum class BackupDestinationType(val displayName: String) {
    SOMENTE_INTERNA("Memória interna"),
    SOMENTE_DRIVE("Google Drive"),
    INTERNA_E_DRIVE("Interna + Drive")
}

enum class BackupOriginFilter(val label: String) {
    ALL("Todos"),
    LOCAL("Dispositivo"),
    DRIVE("Google Drive")
}

data class UnifiedBackupEntry(
    val record: BackupRecord,
    val hasLocal: Boolean,
    val hasDrive: Boolean,
    val remoteFileId: String? = null
)

/**
 * Exclusively Supported Cloud Storage Provider
 */
enum class CloudProviderType(val displayName: String) {
    GOOGLE_DRIVE("Google Drive")
}

/**
 * Legitimate saved Wi-Fi network information (retrieved via authorized root from WifiConfigStore)
 */
data class SavedWifiNetwork(
    val ssid: String,
    val password: String?,
    val securityType: String = "WPA2/WPA3",
    val lastConnected: String = "Salva no dispositivo"
)

/**
 * Legitimate Wi-Fi connection metadata
 */
data class WifiConnectionDetails(
    val isConnected: Boolean = false,
    val ssid: String = "Desconectado",
    val bssid: String = "Indisponível",
    val linkSpeedMbps: Int = 0,
    val frequencyGhz: String = "N/A",
    val channel: Int = 0,
    val standard: String = "Wi-Fi",
    val signalDbm: Int = 0,
    val signalLevel: Int = 0, // 0-4
    val ipAddress: String = "N/A",
    val gateway: String = "N/A",
    val dnsServers: List<String> = emptyList(),
    val privateDnsServerName: String? = null,
    val wifiPasswordStatus: String = "Indisponível pelo Android\nO Android não permite que este aplicativo acesse a senha desta rede sem autorização root.",
    val savedNetworks: List<SavedWifiNetwork> = emptyList()
)

enum class ActiveNetworkType(val displayName: String) {
    NONE("Sem conexão"),
    WIFI("Wi-Fi"),
    MOBILE("Dados Móveis"),
    ETHERNET("Ethernet"),
    VPN("VPN")
}

data class MobileSimCardInfo(
    val simSlotIndex: Int, // 0 = SIM 1, 1 = SIM 2
    val carrierName: String,
    val mccMnc: String,
    val networkType: String, // "4G LTE", "5G NSA", "5G SA", "3G", etc.
    val signalDbm: Int,
    val signalLevel: Int, // 0-4
    val isRoaming: Boolean,
    val isDataEnabled: Boolean,
    val ipAddress: String,
    val bandOrArfcn: String? = null
)

data class ActiveNetworkDetails(
    val type: ActiveNetworkType,
    val isConnected: Boolean,
    val wifi: WifiConnectionDetails? = null,
    val mobileSims: List<MobileSimCardInfo> = emptyList(),
    val primaryMobileSimIndex: Int = 0,
    val ethernetIp: String? = null,
    val vpnName: String? = null,
    val currentDownloadSpeedKbps: Double = 0.0,
    val currentUploadSpeedKbps: Double = 0.0,
    val sessionDownloadedBytes: Long = 0L,
    val sessionUploadedBytes: Long = 0L,
    val requiresPhonePermission: Boolean = false,
    val requiresLocationPermission: Boolean = false
)

data class BluetoothDeviceInfo(
    val name: String,
    val alias: String?,
    val address: String, // MAC
    val isConnected: Boolean,
    val isBonded: Boolean,
    val deviceType: String, // Clássico, BLE, Dual
    val majorClass: String, // Fone de Ouvido, Relógio, Carro, etc.
    val bondState: String,
    val batteryLevel: Int? = null, // % ou null
    val supportedProfiles: List<String> = emptyList(),
    val connectedProfiles: List<String> = emptyList(),
    val uuids: List<String> = emptyList(),
    val audioCodec: String? = null,
    val firstSeenTimestamp: Long = 0L,
    val lastConnectedTimestamp: Long = 0L,
    val totalConnectionCount: Int = 0,
    val totalConnectedDurationSec: Long = 0L
)

data class BluetoothHistoryEvent(
    val id: Long = 0L,
    val address: String,
    val deviceName: String,
    val event: String, // "CONECTADO", "DESCONECTADO", "PAREADO", "DESPAREADO"
    val timestamp: Long = System.currentTimeMillis(),
    val details: String? = null
)

data class DeviceCpuCoreInfo(
    val coreIndex: Int,
    val curFreqKhz: Long,
    val minFreqKhz: Long,
    val maxFreqKhz: Long,
    val isOnline: Boolean
)

data class DeviceHardwareSpecification(
    // Identificação
    val model: String = "Detectando...",
    val marketName: String = "Detectando...",
    val manufacturer: String = "Detectando...",
    val brand: String = "Detectando...",
    val deviceCode: String = "",
    val board: String = "",
    val hardware: String = "",
    val buildNumber: String = "",
    val fingerprint: String = "",

    // Sistema
    val androidVersion: String = "",
    val apiLevel: Int = 0,
    val securityPatch: String = "",
    val romVersion: String = "",
    val buildDate: String = "",
    val kernelVersion: String = "",
    val basebandVersion: String = "",
    val bootloaderStatus: String = "",
    val rootStatus: String = "",
    val trebleSupport: Boolean = false,
    val abPartitions: Boolean = false,
    val playServicesVersion: String = "",
    val webViewVersion: String = "",

    // Processador
    val socModel: String = "",
    val socManufacturer: String = "",
    val totalCores: Int = 0,
    val abis: List<String> = emptyList(),
    val cores: List<DeviceCpuCoreInfo> = emptyList(),
    val governor: String = "",
    val gpuRenderer: String = "",
    val vulkanVersion: String = "",

    // Memória
    val ramTotalBytes: Long = 0L,
    val ramUsedBytes: Long = 0L,
    val ramAvailableBytes: Long = 0L,
    val zramTotalBytes: Long = 0L,
    val zramUsedBytes: Long = 0L,
    val internalStorageTotalBytes: Long = 0L,
    val internalStorageUsedBytes: Long = 0L,
    val internalStorageFreeBytes: Long = 0L,
    val storageType: String = "",
    val sdCardStatus: String = "",

    // Tela
    val resolution: String = "",
    val densityDpi: Int = 0,
    val approximateInches: Double = 0.0,
    val currentRefreshRate: Float = 60f,
    val supportedRefreshRates: List<Float> = emptyList(),
    val hdrCapabilities: String = "",

    // Bateria
    val batteryPercent: Int = 0,
    val batteryHealth: String = "",
    val batteryTemperatureC: Float = 0f,
    val batteryVoltageMv: Int = 0,
    val batteryTechnology: String = "",
    val batteryStatus: String = "",
    val batteryCycles: Int? = null,

    // Hardware extra
    val camerasSummary: String = "",
    val sensorsCount: Int = 0,
    val hasNfc: Boolean = false,
    val hasBiometrics: Boolean = false,
    val wifiVersions: String = "",
    val bluetoothVersion: String = ""
)

/**
 * Network data usage breakdown
 */
data class DataUsagePeriod(
    val downloadBytes: Long,
    val uploadBytes: Long,
    val totalBytes: Long,
    val wifiBytes: Long,
    val mobileBytes: Long
)

data class NetworkDataUsageStats(
    val today: DataUsagePeriod = DataUsagePeriod(0L, 0L, 0L, 0L, 0L),
    val thisWeek: DataUsagePeriod = DataUsagePeriod(0L, 0L, 0L, 0L, 0L),
    val thisMonth: DataUsagePeriod = DataUsagePeriod(0L, 0L, 0L, 0L, 0L),
    val weeklyHistory: List<Pair<String, Long>> = emptyList(),
    val limitationNotice: String? = null
)

/**
 * Cloud Account information and connection status
 */
data class CloudAccount(
    val providerType: CloudProviderType,
    val accountName: String,
    val isConnected: Boolean,
    val quotaUsedBytes: Long = 0L,
    val quotaTotalBytes: Long = 0L,
    val lastSyncTimestamp: Long = 0L,
    val filesCount: Int = 0,
    val errorsCount: Int = 0,
    val serverHost: String? = null,
    val serverPort: Int = 0,
    val username: String? = null,
    val authTokenOrSecret: String? = null
)

/**
 * Remote cloud file representation
 */
data class CloudRemoteFile(
    val id: String,
    val name: String,
    val remotePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val md5OrSha: String? = null
)

/**
 * Cloud Transfer Queue item
 */
data class CloudSyncQueueItem(
    val id: String,
    val backupId: String,
    val backupTitle: String,
    val providerType: CloudProviderType,
    val direction: TransferDirection,
    val status: QueueStatus,
    val progressBytes: Long,
    val totalBytes: Long,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val priority: Int = 0, // Higher = processed first
    val accountEmail: String? = null
)

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

enum class QueueStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    RETRYING
}

/**
 * Result of individual cloud diagnostic test
 */
data class CloudDiagnosticResult(
    val stepName: String,
    val status: DiagnosticStepStatus,
    val latencyMs: Long = 0L,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val possibleCause: String? = null,
    val recommendedSolution: String? = null
)

enum class DiagnosticStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}

/**
 * Conflict detection for bi-directional synchronization
 */
data class SyncConflict(
    val fileId: String,
    val fileName: String,
    val localTimestamp: Long,
    val localSize: Long,
    val remoteTimestamp: Long,
    val remoteSize: Long,
    val localHash: String,
    val remoteHash: String
)

enum class ConflictResolution {
    USE_LOCAL,
    USE_REMOTE,
    KEEP_BOTH
}

/**
 * Device diagnostic and storage breakdown stats
 */
data class DeviceStats(
    val model: String = "",
    val androidVersion: String = "",
    val apiLevel: Int = 0,
    val ramTotalBytes: Long = 0L,
    val ramAvailableBytes: Long = 0L,
    val securityPatch: String = "",
    val storageTotalBytes: Long = 0L,
    val storageUsedBytes: Long = 0L,
    val storageFreeBytes: Long = 0L,
    val backupStorageUsedBytes: Long = 0L,
    val appsStorageBytes: Long = 0L,
    val photosBytes: Long = 0L,
    val videosBytes: Long = 0L,
    val documentsBytes: Long = 0L,
    val othersBytes: Long = 0L
)

/**
 * Structured system log entry
 */
data class LogEntry(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val details: String? = null
)

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}

/**
 * Accent colors for visual theme personalization
 */
enum class ThemeAccent(val displayName: String, val hexCode: Long) {
    BLUE("Azul Neon", 0xFF2979FF),
    PURPLE("Roxo Cyber", 0xFFA855F7),
    GREEN("Verde Esmeralda", 0xFF10B981),
    CYAN("Ciano Elétrico", 0xFF06B6D4),
    ORANGE("Laranja Sunset", 0xFFF97316),
    RED("Vermelho Carmim", 0xFFEF4444)
}

/**
 * Step in the 8-Step Backup Wizard
 */
enum class WizardStep(val stepNumber: Int, val title: String) {
    APPS(1, "Aplicativos"),
    DATA(2, "Dados"),
    FILES(3, "Arquivos"),
    SYSTEM(4, "Sistema"),
    DNS(5, "DNS"),
    STORAGE(6, "Armazenamento"),
    SECURITY(7, "Segurança"),
    SUMMARY(8, "Resumo")
}

/**
 * Real-time operational item completion tracking for progress UI
 */
data class CompletedItemInfo(
    val id: String = "",
    val name: String,
    val type: BackupItemType = BackupItemType.FILE,
    val success: Boolean = true,
    val error: String? = null,
    val stage: String = "",
    val causeException: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val suggestedFix: String? = null,
    val packageName: String = "",
    val iconPath: String? = null,
    val detailCount: String? = null,
    val verified: Boolean = false,
    val statusText: String? = null,
    val isIgnored: Boolean = false
) {
    val errorMessage: String? get() = error
}

/**
 * Throttles rapid progress updates to at most 2 updates per second (minIntervalMs = 500ms)
 * to minimize CPU usage, avoid flooding the Main / UI thread and prevent Android ANRs/watchdog kills.
 */
class ProgressRateLimiter(private val minIntervalMs: Long = 500L) {
    private var lastEmitTime = 0L

    fun shouldEmit(force: Boolean = false): Boolean {
        if (force) {
            lastEmitTime = System.currentTimeMillis()
            return true
        }
        val now = System.currentTimeMillis()
        if (now - lastEmitTime >= minIntervalMs) {
            lastEmitTime = now
            return true
        }
        return false
    }

    suspend inline fun emitThrottled(force: Boolean = false, crossinline action: suspend () -> Unit): Boolean {
        if (shouldEmit(force)) {
            action()
            return true
        }
        return false
    }

    fun reset() {
        lastEmitTime = 0L
    }
}

/**
 * Options for restoring applications (Swift Backup style)
 */
data class RestoreAppOptions(
    val restoreApkAndSplits: Boolean = true,
    val restoreData: Boolean = true,
    val restoreExternalData: Boolean = true,
    val restorePermissions: Boolean = true,
    val installPolicy: InstallPolicy = InstallPolicy.ALWAYS // ALWAYS, ONLY_IF_NOT_INSTALLED, REPLACE_IF_VERSION_DIFF, KEEP_NEWER
)

enum class InstallPolicy {
    ALWAYS,
    ONLY_IF_NOT_INSTALLED,
    REPLACE_IF_VERSION_DIFF,
    KEEP_NEWER
}

enum class ItemRestoreStatus {
    NOT_RESTORED,
    RESTORING,
    RESTORED,
    ALREADY_INSTALLED,
    ERROR
}

/**
 * An app available for restoration found across backups
 */
data class RestoreAppItem(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val backupTimestamp: Long,
    val containerFile: java.io.File? = null,
    val remoteDriveFileId: String? = null,
    val driveAccountEmail: String? = null,
    val backupTitle: String = "",
    val totalSizeBytes: Long = 0L,
    val manifestItemIds: List<String> = emptyList(),
    val iconBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    val status: ItemRestoreStatus = ItemRestoreStatus.NOT_RESTORED,
    val errorMessage: String? = null,
    val isInstalled: Boolean = false,
    val installedVersionCode: Long = 0L,
    val isSystemApp: Boolean = false,
    val allVersions: List<RestoreAppVersion> = emptyList(),
    val baseApkEntryPath: String? = null,
    val isLegacyBackup: Boolean = false
)

data class RestoreAppVersion(
    val versionName: String,
    val versionCode: Long,
    val backupTimestamp: Long,
    val containerFile: java.io.File? = null,
    val remoteDriveFileId: String? = null,
    val backupTitle: String = "",
    val totalSizeBytes: Long = 0L,
    val manifestItemIds: List<String> = emptyList(),
    val baseApkEntryPath: String? = null
)

/**
 * A system item available for restoration
 */
data class RestoreSystemItem(
    val id: String,
    val type: BackupItemType,
    val title: String,
    val description: String,
    val backupTimestamp: Long,
    val containerFile: java.io.File? = null,
    val remoteDriveFileId: String? = null,
    val driveAccountEmail: String? = null,
    val backupTitle: String = "",
    val sizeBytes: Long = 0L,
    val manifestItemId: String = "",
    val isAvailable: Boolean = true,
    val status: ItemRestoreStatus = ItemRestoreStatus.NOT_RESTORED,
    val errorMessage: String? = null,
    val isLegacyBackup: Boolean = false
)

/**
 * Historical process exit report to diagnose why previous process died (e.g. LMK, ANR, Crash)
 */
data class ProcessExitInfoModel(
    val pid: Int,
    val timestamp: Long,
    val reasonCode: Int,
    val reasonTitle: String,
    val description: String,
    val importance: Int,
    val pssKb: Long = 0L,
    val rssKb: Long = 0L,
    val isAbnormalExit: Boolean = false,
    val anrTrace: String? = null
)

