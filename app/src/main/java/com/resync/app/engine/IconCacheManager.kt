package com.resync.app.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.resync.app.data.model.BackupItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Manages extraction, caching, and serialization of real application icons.
 * Full support for AdaptiveIconDrawable, VectorDrawable, and standard Drawables.
 * Prevents redundant GC allocations and UI lag in LazyLists.
 * Supports packaging icons into backups (PNG 96x96 or 128x128) and reading from container index.
 */
class IconCacheManager private constructor(private val context: Context) {

    // 40MB memory cache for ImageBitmaps
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)

    private val memoryCache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }

    companion object {
        @Volatile
        private var instance: IconCacheManager? = null

        fun getInstance(context: Context): IconCacheManager {
            return instance ?: synchronized(this) {
                instance ?: IconCacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Retrieve real app icon from cache or PackageManager.
     */
    suspend fun getAppIcon(packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext null
        memoryCache.get(packageName)?.let { return@withContext it }

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)

            val bitmap = drawableToBitmap(drawable)
            val imageBitmap = bitmap.asImageBitmap()

            memoryCache.put(packageName, imageBitmap)
            return@withContext imageBitmap
        } catch (_: Exception) {
            null
        }
    }

    fun putAppIcon(packageName: String, imageBitmap: ImageBitmap) {
        if (packageName.isNotBlank()) {
            memoryCache.put(packageName, imageBitmap)
        }
    }

    /**
     * Extracts application icon as a PNG byte array (96x96 to 128x128) for backup storage.
     */
    suspend fun extractAppIconPngBytes(packageName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)
            val bitmap = drawableToBitmap(drawable, targetSize = 128)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Loads icon from raw PNG bytes, caches it, and returns ImageBitmap.
     */
    fun loadAppIconFromPngBytes(packageName: String, bytes: ByteArray): ImageBitmap? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val imageBitmap = bitmap.asImageBitmap()
            if (packageName.isNotBlank()) {
                memoryCache.put(packageName, imageBitmap)
            }
            imageBitmap
        } catch (_: Throwable) {
            null
        }
    }

    data class AppIconResult(
        val bitmap: ImageBitmap?,
        val isInstalled: Boolean
    )

    fun isPackageInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getAppIconFromApk(apkFile: File, packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (packageName.isNotBlank()) {
            memoryCache.get(packageName)?.let { return@withContext it }
        }
        if (!apkFile.exists() || apkFile.length() == 0L) return@withContext null
        try {
            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
                ?: pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?: return@withContext null
            val appInfo = pi.applicationInfo ?: return@withContext null
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
            
            var drawable: Drawable? = null
            try {
                drawable = appInfo.loadIcon(pm)
            } catch (_: Throwable) {}

            if (drawable == null || drawable == pm.defaultActivityIcon) {
                try {
                    val res = pm.getResourcesForApplication(appInfo)
                    if (appInfo.icon != 0) {
                        drawable = res.getDrawable(appInfo.icon, null)
                    }
                } catch (_: Throwable) {}
            }

            if (drawable == null || drawable == pm.defaultActivityIcon) {
                try {
                    drawable = pm.getApplicationIcon(appInfo)
                } catch (_: Throwable) {}
            }

            val bitmap = drawable?.let { drawableToBitmap(it) } ?: return@withContext null
            val imageBitmap = bitmap.asImageBitmap()
            if (packageName.isNotBlank()) {
                memoryCache.put(packageName, imageBitmap)
            }
            imageBitmap
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Reads the application icon directly from the container (.svb / .zip).
     * Priority:
     * 1. Check if "apps/<packageName>/icon.png" is packaged directly.
     * 2. Fallback to extracting from the base.apk entry.
     */
    suspend fun getAppIconFromContainer(
        containerFile: File,
        entryPath: String,
        packageName: String,
        password: String? = null,
        accountEmail: String? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (packageName.isNotBlank()) {
            memoryCache.get(packageName)?.let { return@withContext it }
        }
        if (!containerFile.exists()) return@withContext null

        // 1. Try reading encrypted/stored apps/<packageName>/icon.png via ArchiveEngine
        val directBytes = runCatching {
            ArchiveEngine.readEntryBytes(
                containerFile = containerFile,
                entryPath = "apps/$packageName/icon.png",
                password = password,
                accountEmail = accountEmail
            )
        }.getOrNull()

        if (directBytes != null && directBytes.isNotEmpty()) {
            val bmp = loadAppIconFromPngBytes(packageName, directBytes)
            if (bmp != null) return@withContext bmp
        }

        // 2. Try extracting APK to read icon
        var tempApk: File? = null
        try {
            val apkBytes = runCatching {
                ArchiveEngine.readEntryBytes(
                    containerFile = containerFile,
                    entryPath = entryPath,
                    password = password,
                    accountEmail = accountEmail
                )
            }.getOrNull()

            if (apkBytes != null && apkBytes.isNotEmpty()) {
                tempApk = File.createTempFile("icon_${packageName}_", ".apk", context.cacheDir)
                tempApk!!.writeBytes(apkBytes)
                val icon = getAppIconFromApk(tempApk!!, packageName)
                if (icon != null) return@withContext icon
            }
        } catch (_: Throwable) {
        } finally {
            try { tempApk?.delete() } catch (_: Throwable) {}
        }
        null
    }

    suspend fun getAppIconWithStatus(
        packageName: String,
        containerFile: File? = null,
        remoteIconFetcher: (suspend (String) -> ByteArray?)? = null,
        password: String? = null,
        accountEmail: String? = null
    ): AppIconResult = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext AppIconResult(null, false)
        val installed = isPackageInstalled(packageName)
        if (installed) {
            val bmp = getAppIcon(packageName)
            return@withContext AppIconResult(bmp, true)
        }

        // App not installed on device:
        val uninstalledCacheKey = "uninstalled_icon_$packageName"
        memoryCache.get(uninstalledCacheKey)?.let { return@withContext AppIconResult(it, false) }
        memoryCache.get(packageName)?.let { return@withContext AppIconResult(it, false) }

        // 1. Try reading from containerFile if available
        if (containerFile != null && containerFile.exists()) {
            val containerBmp = getAppIconFromContainer(
                containerFile = containerFile,
                entryPath = "apps/$packageName/base.apk",
                packageName = packageName,
                password = password,
                accountEmail = accountEmail
            )
            if (containerBmp != null) {
                memoryCache.put(uninstalledCacheKey, containerBmp)
                return@withContext AppIconResult(containerBmp, false)
            }
        }

        // 2. Try remoteIconFetcher if available (e.g. Google Drive range request for icon.png)
        if (remoteIconFetcher != null) {
            val remoteBytes = runCatching {
                remoteIconFetcher("apps/$packageName/icon.png")
            }.getOrNull()
            if (remoteBytes != null && remoteBytes.isNotEmpty()) {
                val remoteBmp = loadAppIconFromPngBytes(packageName, remoteBytes)
                if (remoteBmp != null) {
                    memoryCache.put(uninstalledCacheKey, remoteBmp)
                    return@withContext AppIconResult(remoteBmp, false)
                }
            }
        }

        // App is not installed and icon could not be extracted
        AppIconResult(null, false)
    }

    /**
     * Converts any Android Drawable (including AdaptiveIconDrawable and VectorDrawable)
     * cleanly into a hardware-independent ARGB_8888 Bitmap.
     */
    fun drawableToBitmap(drawable: Drawable, targetSize: Int = 128): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val bmp = drawable.bitmap
            if (bmp.width == targetSize && bmp.height == targetSize) {
                return bmp
            }
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceIn(96, 192) else targetSize
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceIn(96, 192) else targetSize

        val finalWidth = if (targetSize > 0) targetSize else width
        val finalHeight = if (targetSize > 0) targetSize else height

        val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }

    // Mapping of lowercase app label -> package name
    private val appLabelToPackageMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var isAppMapInitialized = false

    private fun ensureAppMap() {
        if (isAppMapInitialized) return
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            for (app in apps) {
                val label = app.loadLabel(pm).toString().trim().lowercase()
                appLabelToPackageMap[label] = app.packageName
                appLabelToPackageMap[app.packageName.lowercase()] = app.packageName
            }
            isAppMapInitialized = true
        } catch (_: Throwable) {}
    }

    /**
     * Finds the real app icon by either package name or app name (e.g. "FusionSolar (Base APK)").
     */
    suspend fun getAppIconByNameOrPackage(identifier: String, containerFile: File? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        if (identifier.isBlank()) return@withContext null

        // 1. If it looks like a clean package name, try directly
        var directBmp = getAppIcon(identifier)
        if (directBmp != null) return@withContext directBmp

        // 2. Clean up suffixes like " (Base APK)", " (Splits)", " (Dados)", " (Split APKs)", " (App Data)"
        val clean = identifier
            .replace(Regex("(?i)\\s*\\((Base APK|Splits|Split APKs|Dados|App Data|Cache|v[0-9.]+)\\)"), "")
            .trim()

        // If it extracted a package name inside parenthesis, e.g. "FusionSolar (com.huawei.smartpvms)"
        val pkgInParens = Regex("\\((com\\.[a-zA-Z0-9_.]+)\\)").find(identifier)?.groupValues?.get(1)
        if (!pkgInParens.isNullOrBlank()) {
            directBmp = getAppIcon(pkgInParens)
            if (directBmp != null) return@withContext directBmp
        }

        // Try clean as package
        directBmp = getAppIcon(clean)
        if (directBmp != null) return@withContext directBmp

        // 3. Lookup in installed apps by app label
        ensureAppMap()
        val mappedPkg = appLabelToPackageMap[clean.lowercase()]
        if (!mappedPkg.isNullOrBlank()) {
            directBmp = getAppIcon(mappedPkg)
            if (directBmp != null) return@withContext directBmp
        }

        // Partial match on label
        val partialPkg = appLabelToPackageMap.entries.firstOrNull { 
            it.key.contains(clean.lowercase()) || clean.lowercase().contains(it.key) 
        }?.value
        if (!partialPkg.isNullOrBlank()) {
            directBmp = getAppIcon(partialPkg)
            if (directBmp != null) return@withContext directBmp
        }

        // 4. Try from container if available (during restore or verification)
        if (containerFile != null && containerFile.exists()) {
            val candidatePkg = mappedPkg ?: partialPkg ?: clean
            val containerBmp = getAppIconFromContainer(containerFile, "apps/$candidatePkg/base.apk", candidatePkg)
            if (containerBmp != null) return@withContext containerBmp
        }

        null
    }

    /**
     * Extracts a real thumbnail for an image or video file.
     * Supports local files, MediaStore, and reading from backup containers (.svb / .zip).
     */
    suspend fun getFileThumbnail(pathOrName: String, containerFile: File? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        if (pathOrName.isBlank()) return@withContext null
        val cacheKey = "thumb:${containerFile?.name ?: ""}:$pathOrName"
        memoryCache.get(cacheKey)?.let { return@withContext it }

        try {
            val lower = pathOrName.lowercase()
            val isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif") || lower.endsWith(".heic")
            val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                    lower.endsWith(".3gp") || lower.endsWith(".mov") || lower.endsWith(".avi")

            if (!isImage && !isVideo) return@withContext null

            var bitmap: Bitmap? = null
            val file = File(pathOrName)

            if (file.exists() && file.canRead()) {
                if (isImage) {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                    val sampleSize = calculateInSampleSize(boundsOpts, 128, 128)
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                } else if (isVideo) {
                    bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            ThumbnailUtils.createVideoThumbnail(file, Size(128, 128), null)
                        } catch (_: Throwable) {
                            @Suppress("DEPRECATION")
                            ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                    }
                }
            } else if (containerFile != null && containerFile.exists() && isImage) {
                // Read image entry from backup container (.svb / .zip)
                try {
                    val files = ArchiveEngine.resolveArchiveFiles(containerFile)
                    val channel = MultiPartSeekableByteChannel(files)
                    channel.use { ch ->
                        org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(ch).get().use { zipFile ->
                            val cleanName = pathOrName.replace('\\', '/').trimStart('/')
                            val entry = zipFile.getEntry(cleanName)
                                ?: zipFile.getEntry("files/$cleanName")
                                ?: zipFile.entries.asSequence().firstOrNull { it.name.endsWith(File(pathOrName).name, ignoreCase = true) }
                            if (entry != null && !entry.isDirectory && entry.size in 1..25_000_000L) {
                                zipFile.getInputStream(entry).use { input ->
                                    val bytes = input.readBytes()
                                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                                    val sampleSize = calculateInSampleSize(boundsOpts, 128, 128)
                                    val decodeOpts = BitmapFactory.Options().apply {
                                        inSampleSize = sampleSize
                                        inPreferredConfig = Bitmap.Config.RGB_565
                                    }
                                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            } else {
                // If only file name is provided (e.g. IMG_20150729_192523024_HDR.jpg), query MediaStore
                val fileName = File(pathOrName).name
                val cr = context.contentResolver
                if (isImage) {
                    val projection = arrayOf(MediaStore.Images.Media._ID)
                    val cursor = cr.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.Images.Media.DISPLAY_NAME} = ? OR ${MediaStore.Images.Media.TITLE} = ?",
                        arrayOf(fileName, fileName.substringBeforeLast(".")),
                        null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                try { cr.loadThumbnail(uri, Size(128, 128), null) } catch (_: Throwable) { null }
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                            }
                        }
                    }
                } else if (isVideo) {
                    val projection = arrayOf(MediaStore.Video.Media._ID)
                    val cursor = cr.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.Video.Media.DISPLAY_NAME} = ? OR ${MediaStore.Video.Media.TITLE} = ?",
                        arrayOf(fileName, fileName.substringBeforeLast(".")),
                        null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                            val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                try { cr.loadThumbnail(uri, Size(128, 128), null) } catch (_: Throwable) { null }
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                            }
                        }
                    }
                }
            }

            val finalBitmap = bitmap
            if (finalBitmap != null) {
                val imageBmp = finalBitmap.asImageBitmap()
                memoryCache.put(cacheKey, imageBmp)
                return@withContext imageBmp
            }
        } catch (_: Throwable) {}

        null
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Unified resolver: gets app icon, file thumbnail, or null for fallback.
     */
    suspend fun getItemIcon(
        name: String,
        packageName: String = "",
        type: BackupItemType = BackupItemType.FILE,
        containerFile: File? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val isApps = type == BackupItemType.APP_APK ||
                type == BackupItemType.APP_SPLIT_APKS ||
                type == BackupItemType.APP_DATA ||
                name.endsWith(".apk", ignoreCase = true) ||
                name.contains("(Base APK)", ignoreCase = true) ||
                name.contains("(Splits)", ignoreCase = true)

        if (isApps) {
            val pkgToTry = packageName.ifBlank { name }
            getAppIconByNameOrPackage(pkgToTry, containerFile)
        } else {
            getFileThumbnail(name, containerFile)
        }
    }

    /**
     * Clear icon cache
     */
    fun clearCache() {
        memoryCache.evictAll()
    }
}

