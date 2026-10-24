package com.resync.app.engine

import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.model.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High performance repository for installed applications.
 * - Instant cache retrieval on app launch (0 ms wait)
 * - 1st pass: Fast emission of package name + title in batches of 30 apps
 * - 2nd pass: Background batch calculation of sizes via StorageStatsManager without blocking UI
 * - Cache invalidation via PackageChangeReceiver on app install/uninstall/update
 */
class InstalledAppsRepository private constructor(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("installed_apps_cache", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    val appsFlow: StateFlow<List<AppInfo>> = _appsFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    companion object {
        private const val KEY_APPS_JSON = "cached_apps_json"
        private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"

        @Volatile
        private var instance: InstalledAppsRepository? = null

        fun getInstance(context: Context): InstalledAppsRepository {
            return instance ?: synchronized(this) {
                instance ?: InstalledAppsRepository(context.applicationContext).also {
                    instance = it
                    it.init()
                }
            }
        }
    }

    private fun init() {
        // Load disk cache immediately into memory
        loadFromCache()
        registerPackageChangeReceiver()
        if (_appsFlow.value.isEmpty()) {
            refreshApps(force = false)
        }
    }

    private fun loadFromCache() {
        val json = prefs.getString(KEY_APPS_JSON, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<AppInfo>>() {}.type
                val cached: List<AppInfo> = gson.fromJson(json, type)
                if (cached.isNotEmpty()) {
                    _appsFlow.value = cached
                    Log.d("InstalledAppsRepo", "Loaded ${cached.size} apps immediately from persistent cache.")
                }
            } catch (e: Exception) {
                Log.w("InstalledAppsRepo", "Failed to deserialize cached apps: ${e.message}")
            }
        }
    }

    private fun saveToCache(apps: List<AppInfo>) {
        try {
            val json = gson.toJson(apps)
            prefs.edit()
                .putString(KEY_APPS_JSON, json)
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w("InstalledAppsRepo", "Failed to persist apps cache: ${e.message}")
        }
    }

    /**
     * Triggers asynchronous scanning in two phases:
     * Phase 1: Fast metadata extraction + instant APK size computation in batches of 30 items
     * Phase 2: Background StorageStatsManager size calculation (adding data/cache) without blocking UI
     */
    fun refreshApps(force: Boolean = false) {
        scope.launch {
            if (_isLoadingFlow.value && !force) return@launch
            _isLoadingFlow.value = true

            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val pm = context.packageManager

                // Comprehensive package retrieval with fallbacks
                val packages = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstalledPackages(PackageManager.GET_META_DATA)
                    }
                } catch (e: Exception) {
                    emptyList()
                }.ifEmpty {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getInstalledPackages(0)
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val currentList = mutableListOf<AppInfo>()
                val batchSize = 30
                var counter = 0

                val isRoot = com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true

                if (packages.isNotEmpty()) {
                    // Phase 1: Quick collection from PackageInfo
                    for (pkg in packages) {
                        val pkgName = pkg.packageName
                        // Avoid backing up the app itself to prevent self-referencing loops
                        if (pkgName.equals(context.packageName, ignoreCase = true)) continue

                        val appInfo = pkg.applicationInfo ?: runCatching { pm.getApplicationInfo(pkgName, 0) }.getOrNull()
                        val isSystem = if (appInfo != null) {
                            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        } else false

                        val appName = try {
                            if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkgName
                        } catch (_: Exception) {
                            pkgName
                        }

                        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pkg.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            pkg.versionCode.toLong()
                        }

                        val srcDir = appInfo?.sourceDir ?: ""
                        val splitDirs = appInfo?.splitSourceDirs?.toList() ?: emptyList()

                        // Calculate APK size immediately in Phase 1 so sizes are NEVER 0 B!
                        val baseApk = File(srcDir)
                        val baseSize = if (baseApk.exists()) baseApk.length() else 0L
                        val splitsSize = splitDirs.sumOf { File(it).length() }
                        var initialSize = baseSize + splitsSize

                        if (initialSize == 0L && isRoot && srcDir.isNotBlank()) {
                            runCatching {
                                val out = com.topjohnwu.superuser.Shell.cmd("stat -c %s '$srcDir' 2>/dev/null").exec().out
                                val sz = out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                                if (sz > 0L) initialSize = sz
                            }
                        }

                        val isOverlay = try {
                            pkg.javaClass.getField("overlayTarget").get(pkg) != null
                        } catch (_: Throwable) {
                            false
                        } || PackageIgnorePolicy.isOverlayPackage(pkgName)
                        val isInternal = isSystem && (isOverlay || PackageIgnorePolicy.isInternalSystemPackage(pkgName, isOverlay))

                        val item = AppInfo(
                            packageName = pkgName,
                            appName = appName,
                            versionName = pkg.versionName ?: "1.0",
                            versionCode = vCode,
                            minSdkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && appInfo != null) appInfo.minSdkVersion else 21,
                            targetSdkVersion = appInfo?.targetSdkVersion ?: 30,
                            sourceDir = srcDir,
                            splitSourceDirs = splitDirs,
                            dataDir = appInfo?.dataDir ?: "",
                            isSystemApp = isSystem,
                            sizeBytes = initialSize,
                            lastInstallTime = pkg.firstInstallTime,
                            lastUpdateTime = pkg.lastUpdateTime,
                            lastBackupDate = null,
                            hasBackup = false,
                            isOutdatedBackup = false,
                            backupSize = 0L,
                            architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                            isInternalSystemPackage = isInternal
                        )
                        currentList.add(item)
                        counter++

                        // Emit in batches of 30 to display immediately in UI
                        if (counter % batchSize == 0) {
                            val sortedSnapshot = currentList.sortedWith(
                                compareBy<AppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() }
                            )
                            _appsFlow.value = sortedSnapshot
                        }
                    }
                } else {
                    // Fallback to getInstalledApplications if getInstalledPackages was restricted
                    val appInfos = try {
                        pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    } catch (_: Exception) {
                        try {
                            pm.getInstalledApplications(0)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    for (appInfo in appInfos) {
                        val pkgName = appInfo.packageName
                        if (pkgName.equals(context.packageName, ignoreCase = true)) continue

                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val appName = try {
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (_: Exception) {
                            pkgName
                        }

                        val srcDir = appInfo.sourceDir ?: ""
                        val splitDirs = appInfo.splitSourceDirs?.toList() ?: emptyList()
                        val baseApk = File(srcDir)
                        val baseSize = if (baseApk.exists()) baseApk.length() else 0L
                        val splitsSize = splitDirs.sumOf { File(it).length() }
                        val initialSize = baseSize + splitsSize

                        val isInternal = isSystem && PackageIgnorePolicy.isInternalSystemPackage(pkgName)

                        val item = AppInfo(
                            packageName = pkgName,
                            appName = appName,
                            versionName = "1.0",
                            versionCode = 1L,
                            minSdkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 21,
                            targetSdkVersion = appInfo.targetSdkVersion,
                            sourceDir = srcDir,
                            splitSourceDirs = splitDirs,
                            dataDir = appInfo.dataDir ?: "",
                            isSystemApp = isSystem,
                            sizeBytes = initialSize,
                            lastInstallTime = System.currentTimeMillis(),
                            lastUpdateTime = System.currentTimeMillis(),
                            lastBackupDate = null,
                            hasBackup = false,
                            isOutdatedBackup = false,
                            backupSize = 0L,
                            architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                            isInternalSystemPackage = isInternal
                        )
                        currentList.add(item)
                        counter++
                    }
                }

                // Final emission of Phase 1
                val phase1Final = currentList.sortedWith(
                    compareBy<AppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() }
                )
                _appsFlow.value = phase1Final

                // Phase 2: Refine sizes with StorageStatsManager in background
                val storageStatsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
                } else null

                if (isRoot) {
                    runCatching {
                        com.topjohnwu.superuser.Shell.cmd("cmd appops set ${context.packageName} GET_USAGE_STATS allow").exec()
                    }
                }

                val updatedList = mutableListOf<AppInfo>()
                for ((idx, app) in phase1Final.withIndex()) {
                    var calculatedSize = app.sizeBytes

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && storageStatsManager != null) {
                        try {
                            val userHandle = Process.myUserHandle()
                            val uuid = StorageManager.UUID_DEFAULT
                            val stats = storageStatsManager.queryStatsForPackage(uuid, app.packageName, userHandle)
                            val totalStats = stats.appBytes + stats.dataBytes + stats.cacheBytes
                            if (totalStats > 0L) {
                                calculatedSize = totalStats
                            }
                        } catch (_: Exception) {
                            // If stats query fails, retain initial APK size
                        }
                    }

                    if (calculatedSize == 0L && isRoot && app.sourceDir.isNotBlank()) {
                        runCatching {
                            val out = com.topjohnwu.superuser.Shell.cmd("stat -c %s '${app.sourceDir}' 2>/dev/null").exec().out
                            val sz = out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                            if (sz > 0L) calculatedSize = sz
                        }
                    }

                    updatedList.add(app.copy(sizeBytes = calculatedSize))

                    // Progressively emit every 30 apps in phase 2
                    if ((idx + 1) % batchSize == 0 || idx == phase1Final.size - 1) {
                        val partialUpdated = updatedList + phase1Final.drop(updatedList.size)
                        _appsFlow.value = partialUpdated
                    }
                }

                _appsFlow.value = updatedList
                saveToCache(updatedList)
                Log.d("InstalledAppsRepo", "Phase 2 size calculation completed for ${updatedList.size} apps.")
            } catch (e: Exception) {
                Log.e("InstalledAppsRepo", "Error refreshing apps list: ${e.message}", e)
            } finally {
                _isLoadingFlow.value = false
            }
        }
    }

    private fun registerPackageChangeReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    Log.d("InstalledAppsRepo", "Package changed event received: ${intent?.action}. Invalidating cache.")
                    refreshApps(force = true)
                }
            }, filter)
        } catch (e: Exception) {
            Log.w("InstalledAppsRepo", "Could not register package change broadcast receiver: ${e.message}")
        }
    }
}

