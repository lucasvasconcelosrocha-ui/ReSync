package com.resync.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

data class ScannedItem(
    val file: File,
    val name: String,
    val parentFolderName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val category: FileCategory,
    val packageName: String? = null,
    val versionName: String? = null,
    val appName: String? = null,
    val isInstalled: Boolean = false,
    val apkIcon: Drawable? = null
)

object GlobalFileScanner {

    private const val TAG = "GlobalFileScanner"

    suspend fun scanCategory(
        context: Context,
        category: FileCategory,
        isRootActive: Boolean = false
    ): List<ScannedItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedItem>()
        val seenPaths = HashSet<String>()

        try {
            when (category) {
                FileCategory.ALL -> {
                    // ALL is handled via folder navigation in FileExplorerScreen
                }

                FileCategory.IMAGES -> {
                    queryMediaStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, results, seenPaths, FileCategory.IMAGES)
                }

                FileCategory.VIDEOS -> {
                    queryMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, results, seenPaths, FileCategory.VIDEOS)
                }

                FileCategory.AUDIO -> {
                    queryMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, results, seenPaths, FileCategory.AUDIO)
                }

                FileCategory.DOCUMENTS -> {
                    val selection = "${MediaStore.Files.FileColumns.DATA} LIKE '%.pdf' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.doc' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.docx' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.xls' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.xlsx' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.ppt' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.pptx' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.txt' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.csv' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.epub' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.rtf' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.md'"
                    queryMediaStoreFiles(context, selection, results, seenPaths, FileCategory.DOCUMENTS)
                }

                FileCategory.ARCHIVES -> {
                    val selection = "${MediaStore.Files.FileColumns.DATA} LIKE '%.zip' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.rar' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.7z' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.tar' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.gz' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.bz2' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.xz'"
                    queryMediaStoreFiles(context, selection, results, seenPaths, FileCategory.ARCHIVES)
                }

                FileCategory.APK -> {
                    val selection = "${MediaStore.Files.FileColumns.DATA} LIKE '%.apk' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.apks' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.xapk' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.apkm'"
                    val apkFiles = mutableListOf<File>()
                    queryMediaStoreFilesRaw(context, selection, apkFiles, seenPaths)

                    // Also scan standard Download and Documents directories
                    val standardDirs = listOf(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        File("/storage/emulated/0/Download"),
                        File("/storage/emulated/0/Documents")
                    )
                    for (dir in standardDirs) {
                        if (dir.exists() && dir.isDirectory) {
                            scanApksInDir(dir, apkFiles, seenPaths, maxDepth = 2)
                        }
                    }

                    val pm = context.packageManager
                    for (file in apkFiles) {
                        val item = parseApkDetails(context, pm, file)
                        results.add(item)
                    }
                }

                FileCategory.BACKUPS -> {
                    val selection = "${MediaStore.Files.FileColumns.DATA} LIKE '%.resync' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.svb' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.bak' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.backup' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%.ab' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%/${com.resync.app.data.model.BackupPaths.BACKUP_DIR_NAME}/%' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%/SwiftVaultBackups/%' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%/SwiftVault/%' OR " +
                            "${MediaStore.Files.FileColumns.DATA} LIKE '%/SwiftBackup/%'"
                    queryMediaStoreFiles(context, selection, results, seenPaths, FileCategory.BACKUPS)

                    // Also check standard backup folders
                    val backupDirs = com.resync.app.data.model.BackupPaths.getAllLocalBackupDirs(context) + listOf(
                        File(Environment.getExternalStorageDirectory(), "SwiftBackup"),
                        File(context.filesDir, "backups"),
                        File(context.getExternalFilesDir(null), "backups")
                    )
                    for (bDir in backupDirs) {
                        if (bDir.exists() && bDir.isDirectory) {
                            scanDirRecursively(bDir, results, seenPaths, FileCategory.BACKUPS, maxDepth = 3)
                        }
                    }
                }

                FileCategory.OTHERS -> {
                    // Collect from media store files that don't belong to any known category
                    val baseDir = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
                    scanOthersInDir(baseDir, results, seenPaths, maxDepth = 3)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante escaneamento de categoria $category: ${e.message}", e)
        }

        // Sort descending by last modified by default
        results.sortByDescending { it.lastModified }
        results
    }

    private fun queryMediaStore(
        context: Context,
        uri: android.net.Uri,
        results: MutableList<ScannedItem>,
        seenPaths: HashSet<String>,
        category: FileCategory
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        try {
            context.contentResolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val path = if (dataIdx != -1) cursor.getString(dataIdx) else null ?: continue
                    if (!seenPaths.add(path)) continue
                    val file = File(path)
                    if (!file.exists() || file.isDirectory) continue

                    val name = if (nameIdx != -1) cursor.getString(nameIdx) ?: file.name else file.name
                    val size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else file.length()
                    val dateSec = if (dateIdx != -1) cursor.getLong(dateIdx) else file.lastModified() / 1000
                    val dateMs = if (dateSec > 1_000_000_000_000L) dateSec else dateSec * 1000L

                    val parentName = file.parentFile?.name ?: "Armazenamento"

                    results.add(
                        ScannedItem(
                            file = file,
                            name = name,
                            parentFolderName = parentName,
                            sizeBytes = if (size > 0) size else file.length(),
                            lastModified = dateMs,
                            category = category
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha na consulta MediaStore para $category: ${e.message}")
        }
    }

    private fun queryMediaStoreFiles(
        context: Context,
        selection: String,
        results: MutableList<ScannedItem>,
        seenPaths: HashSet<String>,
        category: FileCategory
    ) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        try {
            context.contentResolver.query(uri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val path = if (dataIdx != -1) cursor.getString(dataIdx) else null ?: continue
                    if (!seenPaths.add(path)) continue
                    val file = File(path)
                    if (!file.exists() || file.isDirectory) continue

                    val name = if (nameIdx != -1) cursor.getString(nameIdx) ?: file.name else file.name
                    val size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else file.length()
                    val dateSec = if (dateIdx != -1) cursor.getLong(dateIdx) else file.lastModified() / 1000
                    val dateMs = if (dateSec > 1_000_000_000_000L) dateSec else dateSec * 1000L
                    val parentName = file.parentFile?.name ?: "Armazenamento"

                    results.add(
                        ScannedItem(
                            file = file,
                            name = name,
                            parentFolderName = parentName,
                            sizeBytes = if (size > 0) size else file.length(),
                            lastModified = dateMs,
                            category = category
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha na consulta MediaStore.Files para $category: ${e.message}")
        }
    }

    private fun queryMediaStoreFilesRaw(
        context: Context,
        selection: String,
        apkFiles: MutableList<File>,
        seenPaths: HashSet<String>
    ) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)

        try {
            context.contentResolver.query(uri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    val path = if (dataIdx != -1) cursor.getString(dataIdx) else null ?: continue
                    if (seenPaths.add(path)) {
                        val f = File(path)
                        if (f.exists() && !f.isDirectory) {
                            apkFiles.add(f)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha na queryMediaStoreFilesRaw: ${e.message}")
        }
    }

    private fun scanApksInDir(dir: File, apkFiles: MutableList<File>, seenPaths: HashSet<String>, maxDepth: Int) {
        if (maxDepth < 0 || !dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) {
                if (!c.name.startsWith(".") && c.name != "Android") {
                    scanApksInDir(c, apkFiles, seenPaths, maxDepth - 1)
                }
            } else {
                val ext = c.extension.lowercase(Locale.ROOT)
                if (ext in listOf("apk", "apks", "xapk", "apkm")) {
                    if (seenPaths.add(c.absolutePath)) {
                        apkFiles.add(c)
                    }
                }
            }
        }
    }

    private fun scanDirRecursively(
        dir: File,
        results: MutableList<ScannedItem>,
        seenPaths: HashSet<String>,
        category: FileCategory,
        maxDepth: Int
    ) {
        if (maxDepth < 0 || !dir.exists()) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) {
                if (!c.name.startsWith(".") && c.name != "Android") {
                    scanDirRecursively(c, results, seenPaths, category, maxDepth - 1)
                }
            } else {
                if (seenPaths.add(c.absolutePath)) {
                    results.add(
                        ScannedItem(
                            file = c,
                            name = c.name,
                            parentFolderName = c.parentFile?.name ?: "Armazenamento",
                            sizeBytes = c.length(),
                            lastModified = c.lastModified(),
                            category = category
                        )
                    )
                }
            }
        }
    }

    private fun scanOthersInDir(
        dir: File,
        results: MutableList<ScannedItem>,
        seenPaths: HashSet<String>,
        maxDepth: Int
    ) {
        if (maxDepth < 0 || !dir.exists()) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) {
                if (!c.name.startsWith(".") && c.name != "Android") {
                    scanOthersInDir(c, results, seenPaths, maxDepth - 1)
                }
            } else {
                val cat = StorageRepository.getFileCategory(c)
                if (cat == FileCategory.OTHERS && seenPaths.add(c.absolutePath)) {
                    results.add(
                        ScannedItem(
                            file = c,
                            name = c.name,
                            parentFolderName = c.parentFile?.name ?: "Armazenamento",
                            sizeBytes = c.length(),
                            lastModified = c.lastModified(),
                            category = FileCategory.OTHERS
                        )
                    )
                }
            }
        }
    }

    private fun parseApkDetails(context: Context, pm: PackageManager, file: File): ScannedItem {
        var pkgName: String? = null
        var vName: String? = null
        var aName: String? = null
        var icon: Drawable? = null

        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext == "apk") {
            try {
                val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
                if (info != null) {
                    pkgName = info.packageName
                    vName = info.versionName
                    info.applicationInfo?.sourceDir = file.absolutePath
                    info.applicationInfo?.publicSourceDir = file.absolutePath
                    aName = info.applicationInfo?.loadLabel(pm)?.toString()
                    icon = info.applicationInfo?.loadIcon(pm)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao ler getPackageArchiveInfo para ${file.name}: ${e.message}")
            }
        } else {
            // apks, xapk, apkm
            try {
                ZipFile(file).use { zip ->
                    val manifestEntry = zip.getEntry("manifest.json")
                    if (manifestEntry != null) {
                        val json = zip.getInputStream(manifestEntry).bufferedReader().readText()
                        pkgName = Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                        vName = Regex("\"version_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                        aName = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao inspecionar zip de ${file.name}: ${e.message}")
            }
        }

        val finalPkg = pkgName ?: file.nameWithoutExtension
        val finalName = aName ?: file.nameWithoutExtension
        val finalVersion = vName ?: "1.0"

        val isInstalled = try {
            pm.getPackageInfo(finalPkg, 0)
            true
        } catch (_: Exception) {
            false
        }

        return ScannedItem(
            file = file,
            name = finalName,
            parentFolderName = file.parentFile?.name ?: "Download",
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            category = FileCategory.APK,
            packageName = finalPkg,
            versionName = finalVersion,
            appName = finalName,
            isInstalled = isInstalled,
            apkIcon = icon
        )
    }
}
