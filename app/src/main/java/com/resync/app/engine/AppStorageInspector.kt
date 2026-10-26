package com.resync.app.engine

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import com.resync.app.data.model.AppInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ApkFileInfo(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val isBase: Boolean
)

data class ApkDetails(
    val files: List<ApkFileInfo>,
    val totalSizeBytes: Long,
    val fileCount: Int,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val sha256Signature: String
)

data class StoragePathBreakdown(
    val title: String,
    val path: String,
    val sizeBytes: Long, // -1L indicates path does not exist
    val exists: Boolean = true,
    val isAccessible: Boolean = true
)

data class AppStorageBreakdown(
    val internalData: StoragePathBreakdown,
    val externalData: StoragePathBreakdown,
    val obbData: StoragePathBreakdown,
    val mediaData: StoragePathBreakdown,
    val cacheData: StoragePathBreakdown,
    val totalDataBytes: Long,
    val isCalculating: Boolean = false,
    val isMeasuredWithRoot: Boolean = false
)

object AppStorageInspector {

    private const val TAG = "AppStorageInspector"
    private val memoryCache = ConcurrentHashMap<String, AppStorageBreakdown>()
    private val apkDetailsCache = ConcurrentHashMap<String, ApkDetails>()

    fun getCachedStorage(packageName: String): AppStorageBreakdown? = memoryCache[packageName]
    fun getCachedApkDetails(packageName: String): ApkDetails? = apkDetailsCache[packageName]
    fun invalidateCache(packageName: String) {
        memoryCache.remove(packageName)
        apkDetailsCache.remove(packageName)
    }

    /**
     * Inspects all APK files (base and splits), version details, SDK levels, and SHA-256 signature.
     */
    suspend fun inspectApks(context: Context, app: AppInfo): ApkDetails = withContext(Dispatchers.IO) {
        val cached = apkDetailsCache[app.packageName]
        if (cached != null) return@withContext cached

        val pm = context.packageManager
        val files = mutableListOf<ApkFileInfo>()

        // 1. Base APK
        val baseFile = File(app.sourceDir)
        if (baseFile.exists()) {
            files.add(
                ApkFileInfo(
                    fileName = baseFile.name,
                    path = baseFile.absolutePath,
                    sizeBytes = baseFile.length(),
                    isBase = true
                )
            )
        }

        // 2. Split APKs
        for (splitPath in app.splitSourceDirs) {
            val splitFile = File(splitPath)
            if (splitFile.exists()) {
                files.add(
                    ApkFileInfo(
                        fileName = splitFile.name,
                        path = splitFile.absolutePath,
                        sizeBytes = splitFile.length(),
                        isBase = false
                    )
                )
            }
        }

        // 3. Fallback inspection if splitSourceDirs was empty: check parent dir
        if (files.size <= 1 && baseFile.parentFile != null && baseFile.parentFile!!.isDirectory) {
            val siblingApks = baseFile.parentFile!!.listFiles { f ->
                f.isFile && f.extension.equals("apk", ignoreCase = true) && f.absolutePath != baseFile.absolutePath
            }
            if (siblingApks != null) {
                for (sibling in siblingApks) {
                    if (files.none { it.path == sibling.absolutePath }) {
                        files.add(
                            ApkFileInfo(
                                fileName = sibling.name,
                                path = sibling.absolutePath,
                                sizeBytes = sibling.length(),
                                isBase = false
                            )
                        )
                    }
                }
            }
        }

        val totalSize = files.sumOf { it.sizeBytes }

        // Metadata: minSdk, targetSdk, versionCode, signatures
        var minSdk = 26
        var targetSdk = 34
        var sigSha256 = "N/A"

        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = pm.getPackageInfo(app.packageName, flags)
            targetSdk = pkgInfo.applicationInfo?.targetSdkVersion ?: 34
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                pkgInfo.applicationInfo?.minSdkVersion ?: 26
            } else 21

            sigSha256 = getPackageSignatureSha256(pkgInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao obter metadados do pacote: ${e.message}")
        }

        val details = ApkDetails(
            files = files,
            totalSizeBytes = totalSize,
            fileCount = files.size,
            versionName = app.versionName,
            versionCode = app.versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            sha256Signature = sigSha256
        )

        apkDetailsCache[app.packageName] = details
        details
    }

    private fun getPackageSignatureSha256(pkgInfo: PackageInfo): String {
        return try {
            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners ?: pkgInfo.signingInfo?.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val cert = signatures[0].toByteArray()
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(cert)
                digest.joinToString(":") { String.format("%02X", it) }
            } else {
                "Não disponível"
            }
        } catch (e: Exception) {
            "Erro ao calcular"
        }
    }

    /**
     * Fast query using StorageStatsManager. Returns Pair(dataBytes - cacheBytes, cacheBytes) or null.
     * Takes < 20ms without opening root shells.
     */
    fun getQuickStorageStats(context: Context, packageName: String): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (storageStatsManager != null && storageManager != null) {
                val uuid = StorageManager.UUID_DEFAULT
                val stats = storageStatsManager.queryStatsForPackage(uuid, packageName, Process.myUserHandle())
                val totalData = (stats.dataBytes - stats.cacheBytes).coerceAtLeast(0L)
                val cache = stats.cacheBytes.coerceAtLeast(0L)
                Pair(totalData, cache)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Provides an immediate breakdown (< 20ms) from memory cache or StorageStatsManager,
     * eliminating the waiting state on opening AppDetailScreen.
     */
    fun getInitialBreakdown(context: Context, packageName: String): AppStorageBreakdown? {
        val cached = memoryCache[packageName]
        if (cached != null) return cached

        val quick = getQuickStorageStats(context, packageName) ?: return null
        val (dataBytes, cacheBytes) = quick

        val initial = AppStorageBreakdown(
            internalData = StoragePathBreakdown("Dados Internos", "/data/data/$packageName", dataBytes, exists = true),
            externalData = StoragePathBreakdown("Dados Externos", "/storage/emulated/0/Android/data/$packageName", -1L, exists = false),
            obbData = StoragePathBreakdown("Arquivos OBB", "/storage/emulated/0/Android/obb/$packageName", -1L, exists = false),
            mediaData = StoragePathBreakdown("Mídia do App", "/storage/emulated/0/Android/media/$packageName", -1L, exists = false),
            cacheData = StoragePathBreakdown("Cache", "/data/data/$packageName/cache", cacheBytes, exists = true),
            totalDataBytes = dataBytes,
            isCalculating = false,
            isMeasuredWithRoot = false
        )
        memoryCache[packageName] = initial
        return initial
    }

    /**
     * Calculates real size of internal data, external data, OBB, media and cache.
     * Uses a SINGLE consolidated root 'du -sk' command in parallel (< 100ms);
     * falls back to StorageStatsManager.
     */
    suspend fun measureAppData(context: Context, packageName: String, forceRefresh: Boolean = false): AppStorageBreakdown = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            memoryCache.remove(packageName)
        }
        val cached = memoryCache[packageName]
        if (cached != null && cached.isMeasuredWithRoot) return@withContext cached

        val internalPath = "/data/data/$packageName"
        val userDePath = "/data/user_de/0/$packageName"
        val externalPath = "/sdcard/Android/data/$packageName"
        val obbPath = "/sdcard/Android/obb/$packageName"
        val mediaPath = "/sdcard/Android/media/$packageName"
        val cacheIntPath = "/data/data/$packageName/cache"
        val codeCachePath = "/data/data/$packageName/code_cache"
        val cacheExtPath = "/sdcard/Android/data/$packageName/cache"

        val isRootActive = runCatching {
            Shell.isAppGrantedRoot() == true || Shell.getShell().isRoot
        }.getOrDefault(false)

        var internalBytes = -1L
        var internalExists = false
        var externalBytes = -1L
        var externalExists = false
        var obbBytes = -1L
        var obbExists = false
        var mediaBytes = -1L
        var mediaExists = false
        var cacheBytes = -1L
        var cacheExists = false
        var usedRoot = false

        if (isRootActive) {
            try {
                // Execute UM ÚNICO comando su -c consolidado passando todos os caminhos de uma vez
                val singleCmd = "du -sk $internalPath $userDePath $externalPath $obbPath $mediaPath $cacheIntPath $codeCachePath $cacheExtPath 2>/dev/null"
                val res = Shell.cmd(singleCmd).exec()
                val resultMap = mutableMapOf<String, Long>()

                for (line in res.out) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val parts = trimmed.split("\\s+".toRegex(), limit = 2)
                    if (parts.size >= 2) {
                        val kb = parts[0].toLongOrNull() ?: 0L
                        val p = parts[1]
                        resultMap[p] = kb * 1024L
                    }
                }

                if (resultMap.isNotEmpty()) {
                    usedRoot = true

                    // Dados internos (/data/data e /data/user_de/0)
                    val hasInt = resultMap.containsKey(internalPath)
                    val hasDe = resultMap.containsKey(userDePath)
                    internalExists = hasInt || hasDe
                    internalBytes = if (internalExists) (resultMap[internalPath] ?: 0L) + (resultMap[userDePath] ?: 0L) else -1L

                    // Dados externos
                    val extEntry = resultMap.entries.firstOrNull { it.key == externalPath || it.key.endsWith("/Android/data/$packageName") }
                    externalExists = extEntry != null
                    externalBytes = extEntry?.value ?: -1L

                    // OBB
                    val obbEntry = resultMap.entries.firstOrNull { it.key == obbPath || it.key.endsWith("/Android/obb/$packageName") }
                    obbExists = obbEntry != null
                    obbBytes = obbEntry?.value ?: -1L

                    // Mídia
                    val mediaEntry = resultMap.entries.firstOrNull { it.key == mediaPath || it.key.endsWith("/Android/media/$packageName") }
                    mediaExists = mediaEntry != null
                    mediaBytes = mediaEntry?.value ?: -1L

                    // Cache
                    val hasCInt = resultMap.containsKey(cacheIntPath) || resultMap.keys.any { it.endsWith("/data/$packageName/cache") }
                    val hasCCode = resultMap.containsKey(codeCachePath) || resultMap.keys.any { it.endsWith("/data/$packageName/code_cache") }
                    val hasCExt = resultMap.containsKey(cacheExtPath) || resultMap.keys.any { it.endsWith("/Android/data/$packageName/cache") }
                    cacheExists = hasCInt || hasCCode || hasCExt
                    val cIntBytes = resultMap.entries.firstOrNull { it.key.endsWith("/data/$packageName/cache") }?.value ?: 0L
                    val cCodeBytes = resultMap.entries.firstOrNull { it.key.endsWith("/data/$packageName/code_cache") }?.value ?: 0L
                    val cExtBytes = resultMap.entries.firstOrNull { it.key.endsWith("/Android/data/$packageName/cache") }?.value ?: 0L
                    cacheBytes = if (cacheExists) (cIntBytes + cCodeBytes + cExtBytes) else -1L
                }
            } catch (e: Exception) {
                Log.w(TAG, "Root du consolidado falhou para $packageName: ${e.message}")
                usedRoot = false
            }
        }

        // Fallback sem root com StorageStatsManager se root não mediu
        if (!usedRoot || (!internalExists && !externalExists)) {
            val stats = measureWithStorageStatsManager(context, packageName)
            if (stats != null) {
                internalBytes = stats.first
                internalExists = true
                cacheBytes = stats.second
                cacheExists = true
            } else {
                val intFile = File(internalPath)
                internalExists = intFile.exists()
                internalBytes = if (internalExists) calculateDirSizeDirectly(intFile) else -1L
            }

            val extFile = File(externalPath)
            externalExists = extFile.exists()
            externalBytes = if (externalExists) calculateDirSizeDirectly(extFile) else -1L

            val obbFile = File(obbPath)
            obbExists = obbFile.exists()
            obbBytes = if (obbExists) calculateDirSizeDirectly(obbFile) else -1L

            val mediaFile = File(mediaPath)
            mediaExists = mediaFile.exists()
            mediaBytes = if (mediaExists) calculateDirSizeDirectly(mediaFile) else -1L
        }

        val totalData = listOf(internalBytes, externalBytes, obbBytes, mediaBytes).filter { it > 0L }.sum()

        val breakdown = AppStorageBreakdown(
            internalData = StoragePathBreakdown("Dados Internos", internalPath, internalBytes, internalExists),
            externalData = StoragePathBreakdown("Dados Externos", externalPath, externalBytes, externalExists),
            obbData = StoragePathBreakdown("Arquivos OBB", obbPath, obbBytes, obbExists),
            mediaData = StoragePathBreakdown("Mídia do App", mediaPath, mediaBytes, mediaExists),
            cacheData = StoragePathBreakdown("Cache", cacheIntPath, cacheBytes, cacheExists),
            totalDataBytes = totalData,
            isCalculating = false,
            isMeasuredWithRoot = usedRoot
        )

        memoryCache[packageName] = breakdown
        breakdown
    }

    private fun measureWithStorageStatsManager(context: Context, packageName: String): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (storageStatsManager != null && storageManager != null) {
                val uuid = StorageManager.UUID_DEFAULT
                val stats = storageStatsManager.queryStatsForPackage(uuid, packageName, Process.myUserHandle())
                val totalData = (stats.dataBytes - stats.cacheBytes).coerceAtLeast(0L)
                val cache = stats.cacheBytes.coerceAtLeast(0L)
                Pair(totalData, cache)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateDirSizeDirectly(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (_: Exception) {
            0L
        }
    }
}
