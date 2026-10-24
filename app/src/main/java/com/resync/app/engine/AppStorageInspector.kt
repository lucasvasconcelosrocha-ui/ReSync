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
    val sizeBytes: Long,
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
     * Calculates real size of internal data, external data, OBB, media and cache.
     * Uses Root (libsu) du command first; falls back to StorageStatsManager with PACKAGE_USAGE_STATS.
     */
    suspend fun measureAppData(context: Context, packageName: String): AppStorageBreakdown = withContext(Dispatchers.IO) {
        val cached = memoryCache[packageName]
        if (cached != null && !cached.isCalculating) return@withContext cached

        val internalPath = "/data/data/$packageName"
        val userDePath = "/data/user_de/0/$packageName"
        val externalPath = "/storage/emulated/0/Android/data/$packageName"
        val obbPath = "/storage/emulated/0/Android/obb/$packageName"
        val mediaPath = "/storage/emulated/0/Android/media/$packageName"

        val isRootActive = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)

        var internalBytes = 0L
        var externalBytes = 0L
        var obbBytes = 0L
        var mediaBytes = 0L
        var cacheBytes = 0L
        var usedRoot = false

        if (isRootActive) {
            try {
                // Execute du -sk via libsu shell on each directory
                internalBytes = measurePathWithRoot(internalPath) + measurePathWithRoot(userDePath)
                externalBytes = measurePathWithRoot(externalPath)
                obbBytes = measurePathWithRoot(obbPath)
                mediaBytes = measurePathWithRoot(mediaPath)
                cacheBytes = measurePathWithRoot("$internalPath/cache") + measurePathWithRoot("$internalPath/code_cache") + measurePathWithRoot("$externalPath/cache")
                usedRoot = true
            } catch (e: Exception) {
                Log.w(TAG, "Root du falhou para $packageName: ${e.message}")
                usedRoot = false
            }
        }

        // Se o root não foi usado ou retornou tudo zerado (e há StorageStatsManager disponível)
        if (!usedRoot || (internalBytes == 0L && externalBytes == 0L)) {
            val stats = measureWithStorageStatsManager(context, packageName)
            if (stats != null) {
                if (internalBytes == 0L) {
                    internalBytes = stats.first
                }
                if (cacheBytes == 0L) {
                    cacheBytes = stats.second
                }
            }
            // Direct readable file check for external data/obb/media if permitted
            if (externalBytes == 0L) {
                externalBytes = calculateDirSizeDirectly(File(externalPath))
            }
            if (obbBytes == 0L) {
                obbBytes = calculateDirSizeDirectly(File(obbPath))
            }
            if (mediaBytes == 0L) {
                mediaBytes = calculateDirSizeDirectly(File(mediaPath))
            }
        }

        val totalData = internalBytes + externalBytes + obbBytes + mediaBytes

        val breakdown = AppStorageBreakdown(
            internalData = StoragePathBreakdown("Dados Internos", internalPath, internalBytes),
            externalData = StoragePathBreakdown("Dados Externos", externalPath, externalBytes),
            obbData = StoragePathBreakdown("Arquivos OBB", obbPath, obbBytes),
            mediaData = StoragePathBreakdown("Mídia do App", mediaPath, mediaBytes),
            cacheData = StoragePathBreakdown("Cache", "$internalPath/cache", cacheBytes),
            totalDataBytes = totalData,
            isCalculating = false,
            isMeasuredWithRoot = usedRoot
        )

        memoryCache[packageName] = breakdown
        breakdown
    }

    private fun measurePathWithRoot(path: String): Long {
        return try {
            val escaped = path.replace("\"", "\\\"")
            val res = Shell.cmd("du -sk \"$escaped\" 2>/dev/null").exec()
            if (res.isSuccess && res.out.isNotEmpty()) {
                val line = res.out.firstOrNull()?.trim() ?: ""
                val kbStr = line.split("\\s+".toRegex()).firstOrNull()
                val kb = kbStr?.toLongOrNull() ?: 0L
                kb * 1024L
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun measureWithStorageStatsManager(context: Context, packageName: String): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (storageStatsManager != null && storageManager != null) {
                val uuid = StorageManager.UUID_DEFAULT
                val stats = storageStatsManager.queryStatsForPackage(uuid, packageName, Process.myUserHandle())
                Pair(stats.dataBytes, stats.cacheBytes)
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
