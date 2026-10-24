package com.resync.app.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

data class StorageVolumeInfo(
    val id: String,
    val rootFile: File,
    val title: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long
)

data class StorageItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val isRestrictedSystemDir: Boolean = false,
    val extension: String = ""
)

enum class StorageSortOrder(val label: String) {
    NAME_ASC("Nome (A → Z)"),
    NAME_DESC("Nome (Z → A)"),
    SIZE_DESC("Maior tamanho"),
    SIZE_ASC("Menor tamanho"),
    DATE_DESC("Mais recente"),
    DATE_ASC("Mais antigo")
}

/**
 * Shared storage management and discovery engine.
 * Provides unified, asynchronous storage volume detection, directory traversal,
 * protected directory warnings, background size calculation, and parent/child path deduplication.
 */
object StorageRepository {

    private const val TAG = "StorageRepository"

    /**
     * Detects all mounted storage volumes (Internal shared storage, MicroSD cards, USB OTG).
     */
    fun getStorageVolumes(context: Context): List<StorageVolumeInfo> {
        val volumeList = mutableListOf<StorageVolumeInfo>()
        val seenPaths = mutableSetOf<String>()

        // 1. StorageManager API
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (storageManager != null) {
            try {
                val volumes: List<StorageVolume> = storageManager.storageVolumes
                for (vol in volumes) {
                    val file: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vol.directory
                    } else {
                        try {
                            val getPathMethod = vol.javaClass.getMethod("getPath")
                            val path = getPathMethod.invoke(vol) as? String
                            if (path != null) File(path) else null
                        } catch (e: Exception) {
                            Log.w(TAG, "Falha ao invocar getPath via reflexão na StorageVolume: ${e.message}")
                            null
                        }
                    }

                    if (file != null && file.exists()) {
                        val path = file.canonicalPath
                        if (seenPaths.add(path)) {
                            val isPrimary = vol.isPrimary
                            val isRemovable = vol.isRemovable
                            val desc = try {
                                vol.getDescription(context)
                            } catch (e: Exception) {
                                if (isPrimary) "Armazenamento Interno" else "Cartão SD / USB"
                            }
                            volumeList.add(
                                StorageVolumeInfo(
                                    id = path,
                                    rootFile = file,
                                    title = if (isPrimary) "Armazenamento Interno" else (desc ?: "Armazenamento Secundário"),
                                    isPrimary = isPrimary,
                                    isRemovable = isRemovable,
                                    totalBytes = file.totalSpace,
                                    freeBytes = file.freeSpace
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao consultar storageVolumes: ${e.message}", e)
            }
        }

        // 2. Primary fallback if not yet included
        val primaryDir = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        try {
            val primaryPath = primaryDir.canonicalPath
            if (seenPaths.add(primaryPath)) {
                volumeList.add(
                    0,
                    StorageVolumeInfo(
                        id = primaryPath,
                        rootFile = primaryDir,
                        title = "Armazenamento Interno",
                        isPrimary = true,
                        isRemovable = false,
                        totalBytes = primaryDir.totalSpace,
                        freeBytes = primaryDir.freeSpace
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter armazenamento primário: ${e.message}", e)
        }

        // 3. Fallback: Secondary external files directories from ContextCompat
        try {
            val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            for (extDir in externalDirs) {
                if (extDir != null) {
                    val abs = extDir.absolutePath
                    val androidIdx = abs.indexOf("/Android/")
                    if (androidIdx > 0) {
                        val rootPath = abs.substring(0, androidIdx)
                        val rootFile = File(rootPath)
                        if (rootFile.exists() && seenPaths.add(rootFile.canonicalPath)) {
                            volumeList.add(
                                StorageVolumeInfo(
                                    id = rootFile.canonicalPath,
                                    rootFile = rootFile,
                                    title = "Armazenamento Secundário (${rootFile.name})",
                                    isPrimary = false,
                                    isRemovable = true,
                                    totalBytes = rootFile.totalSpace,
                                    freeBytes = rootFile.freeSpace
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar externalFilesDirs: ${e.message}", e)
        }

        // 4. Fallback: Direct /storage inspection for mounted mount points
        try {
            val storageRoot = File("/storage")
            if (storageRoot.exists() && storageRoot.isDirectory) {
                val entries = storageRoot.listFiles()
                if (entries != null) {
                    for (entry in entries) {
                        val name = entry.name
                        if (name != "emulated" && name != "self" && entry.isDirectory) {
                            val canonical = entry.canonicalPath
                            if (seenPaths.add(canonical)) {
                                volumeList.add(
                                    StorageVolumeInfo(
                                        id = canonical,
                                        rootFile = entry,
                                        title = "Armazenamento Externo ($name)",
                                        isPrimary = false,
                                        isRemovable = true,
                                        totalBytes = entry.totalSpace,
                                        freeBytes = entry.freeSpace
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inspecionar /storage: ${e.message}", e)
        }

        return volumeList
    }

    /**
     * Identifies if the path targets Android Scoped Storage protected folders (Android/data or Android/obb).
     */
    fun isRestrictedSystemDir(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val normalized = file.absolutePath.replace("\\", "/")
        return normalized.endsWith("/Android/data") || normalized.contains("/Android/data/") ||
                normalized.endsWith("/Android/obb") || normalized.contains("/Android/obb/")
    }

    /**
     * Lists directory items on IO dispatcher with Root (libsu) fallback if direct access is restricted.
     */
    suspend fun listDirectoryItems(
        directory: File,
        isRootActive: Boolean
    ): List<StorageItem> = withContext(Dispatchers.IO) {
        if (!directory.exists()) return@withContext emptyList()

        val directFiles = directory.listFiles()
        if (directFiles != null && directFiles.isNotEmpty()) {
            return@withContext directFiles.map { f ->
                StorageItem(
                    file = f,
                    name = f.name,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified(),
                    isRestrictedSystemDir = isRestrictedSystemDir(f),
                    extension = f.extension
                )
            }
        }

        // Root fallback via Shell if direct access returned null or empty
        if (isRootActive) {
            try {
                val escapedPath = directory.absolutePath.replace("\"", "\\\"")
                val output = Shell.cmd("ls -1p \"$escapedPath\"").exec().out
                val results = mutableListOf<StorageItem>()
                for (entry in output) {
                    val isDir = entry.endsWith("/")
                    val cleanName = if (isDir) entry.dropLast(1) else entry
                    if (cleanName.isBlank()) continue
                    val childFile = File(directory, cleanName)
                    results.add(
                        StorageItem(
                            file = childFile,
                            name = cleanName,
                            isDirectory = isDir,
                            sizeBytes = if (isDir) 0L else childFile.length(),
                            lastModified = childFile.lastModified(),
                            isRestrictedSystemDir = isRestrictedSystemDir(childFile),
                            extension = childFile.extension
                        )
                    )
                }
                return@withContext results
            } catch (e: Exception) {
                Log.e(TAG, "Erro no fallback root ao listar ${directory.absolutePath}: ${e.message}", e)
            }
        }

        emptyList()
    }

    /**
     * Sorts directory items: directories first (unless strictly sorting by size), followed by files.
     */
    fun sortItems(items: List<StorageItem>, sortOrder: StorageSortOrder): List<StorageItem> {
        return items.sortedWith { o1, o2 ->
            if (sortOrder != StorageSortOrder.SIZE_DESC && sortOrder != StorageSortOrder.SIZE_ASC) {
                if (o1.isDirectory && !o2.isDirectory) return@sortedWith -1
                if (!o1.isDirectory && o2.isDirectory) return@sortedWith 1
            }
            when (sortOrder) {
                StorageSortOrder.NAME_ASC -> o1.name.compareTo(o2.name, ignoreCase = true)
                StorageSortOrder.NAME_DESC -> o2.name.compareTo(o1.name, ignoreCase = true)
                StorageSortOrder.SIZE_DESC -> {
                    if (o1.isDirectory && !o2.isDirectory) -1
                    else if (!o1.isDirectory && o2.isDirectory) 1
                    else o2.sizeBytes.compareTo(o1.sizeBytes)
                }
                StorageSortOrder.SIZE_ASC -> {
                    if (o1.isDirectory && !o2.isDirectory) -1
                    else if (!o1.isDirectory && o2.isDirectory) 1
                    else o1.sizeBytes.compareTo(o2.sizeBytes)
                }
                StorageSortOrder.DATE_DESC -> o2.lastModified.compareTo(o1.lastModified)
                StorageSortOrder.DATE_ASC -> o1.lastModified.compareTo(o2.lastModified)
            }
        }
    }

    private val folderSizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun getCachedFolderSize(path: String): Long? = folderSizeCache[path]

    fun setCachedFolderSize(path: String, size: Long) {
        folderSizeCache[path] = size
    }

    /**
     * Calculates folder recursive size on IO dispatcher without blocking the UI thread.
     * Uses in-memory cache and direct Root du -sb / du -sk.
     */
    suspend fun calculateFolderSize(folder: File, isRootActive: Boolean): Long = withContext(Dispatchers.IO) {
        if (!folder.exists()) return@withContext 0L
        if (!folder.isDirectory) return@withContext folder.length()

        val path = folder.absolutePath
        folderSizeCache[path]?.let { return@withContext it }

        // Fast path with Root: direct single du command on folder with 1.5s timeout
        if (isRootActive) {
            try {
                val escaped = path.replace("\"", "\\\"")
                val out = withTimeoutOrNull(1500L) {
                    Shell.cmd("du -sb \"$escaped\" 2>/dev/null || du -sk \"$escaped\" 2>/dev/null").exec().out
                }
                val firstLine = out?.firstOrNull()?.trim()
                if (!firstLine.isNullOrBlank()) {
                    val tokens = firstLine.split(Regex("\\s+"))
                    val rawSize = tokens.firstOrNull()?.toLongOrNull()
                    if (rawSize != null) {
                        val totalBytes = if (firstLine.contains(escaped) && rawSize > 0) {
                            rawSize
                        } else {
                            rawSize * 1024L
                        }
                        folderSizeCache[path] = totalBytes
                        return@withContext totalBytes
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha na leitura direta via du em $path: ${e.message}")
            }
        }

        var totalBytes = 0L
        var inspectedItems = 0
        val startTime = System.currentTimeMillis()
        val stack = ArrayDeque<File>()
        stack.push(folder)

        while (!stack.isEmpty()) {
            if (inspectedItems > 3000 || System.currentTimeMillis() - startTime > 1000L) {
                break
            }
            val current = stack.pop()
            val children = current.listFiles()
            if (children != null) {
                for (child in children) {
                    inspectedItems++
                    if (child.isDirectory) {
                        stack.push(child)
                    } else {
                        totalBytes += child.length()
                    }
                }
            }
        }
        folderSizeCache[path] = totalBytes
        totalBytes
    }

    /**
     * Resolves real file size in bytes, leveraging Root shell (stat -c %s / wc -c)
     * if unprivileged Java File.length() returns 0 on protected system paths.
     */
    suspend fun getFileSize(file: File, isRootActive: Boolean): Long = withContext(Dispatchers.IO) {
        val len = try { file.length() } catch (_: Throwable) { 0L }
        if (len > 0L) return@withContext len

        if (isRootActive) {
            try {
                val escaped = file.absolutePath.replace("\"", "\\\"")
                val out = withTimeoutOrNull(1000L) {
                    Shell.cmd("stat -c %s \"$escaped\" 2>/dev/null || wc -c < \"$escaped\" 2>/dev/null").exec().out
                }
                val raw = out?.firstOrNull()?.trim()
                val parsed = raw?.toLongOrNull()
                if (parsed != null && parsed >= 0L) {
                    return@withContext parsed
                }
            } catch (_: Throwable) {}
        }
        0L
    }

    /**
     * Deduplicates a collection of selected paths.
     * If both a parent directory and its children are selected, drops the child to prevent archiving duplicate files.
     */
    fun normalizeAndDeduplicatePaths(paths: Collection<String>): List<String> {
        if (paths.isEmpty()) return emptyList()

        val canonicalPaths = paths.map { raw ->
            try {
                File(raw).canonicalPath
            } catch (e: Exception) {
                File(raw).absolutePath
            }
        }.distinct().sorted()

        val result = mutableListOf<String>()
        for (path in canonicalPaths) {
            val isChildOfExisting = result.any { parent ->
                path == parent || path.startsWith(parent + File.separator)
            }
            if (!isChildOfExisting) {
                result.add(path)
            }
        }
        return result
    }

    /**
     * Resolves an Android Storage Access Framework (SAF) Uri to a filesystem path when possible.
     */
    fun resolveUriToPath(context: Context, uri: Uri): String? {
        try {
            if (uri.scheme == "file") {
                return uri.path
            }
            val isTree = try { android.provider.DocumentsContract.isTreeUri(uri) } catch (e: Exception) { false }
            val docId = try {
                if (isTree) {
                    android.provider.DocumentsContract.getTreeDocumentId(uri)
                } else {
                    android.provider.DocumentsContract.getDocumentId(uri)
                }
            } catch (e: Exception) {
                null
            }

            if (!docId.isNullOrBlank()) {
                if (docId.startsWith("primary:", ignoreCase = true)) {
                    val relPath = docId.substringAfter(":", "")
                    val file = File(Environment.getExternalStorageDirectory(), relPath)
                    return file.absolutePath
                } else if (docId.contains(":")) {
                    val parts = docId.split(":", limit = 2)
                    val volumeId = parts[0]
                    val relPath = parts[1]
                    val candidate = File("/storage/$volumeId/$relPath")
                    if (candidate.exists()) {
                        return candidate.absolutePath
                    }
                }
            }

            val path = uri.path
            if (!path.isNullOrBlank() && (path.startsWith("/storage") || path.startsWith("/sdcard") || path.startsWith("/data"))) {
                return path
            }
        } catch (e: Exception) {
            Log.w("StorageRepository", "Erro ao resolver URI: ${e.message}")
        }
        return uri.path
    }

    /**
     * Converts raw bytes into human-readable formatted string (B, KB, MB, GB, TB).
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val ptBr = Locale("pt", "BR")
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val group = digitGroups.coerceIn(0, units.size - 1)
        if (group == 0) {
            return String.format(ptBr, "%,d B", bytes)
        }
        val value = bytes / Math.pow(1024.0, group.toDouble())
        return String.format(ptBr, "%.1f %s", value, units[group])
    }

    /**
     * Formats timestamp into standard localized date/time representation.
     */
    fun formatFileDate(timestamp: Long): String {
        return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(java.util.Date(timestamp))
    }

    /**
     * Categorizes a file by its extension.
     */
    fun getFileCategory(file: File): FileCategory {
        if (file.isDirectory) return FileCategory.ALL
        return when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg" -> FileCategory.IMAGES
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp" -> FileCategory.VIDEOS
            "mp3", "m4a", "aac", "ogg", "wav", "flac", "opus", "wma" -> FileCategory.AUDIO
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "md" -> FileCategory.DOCUMENTS
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> FileCategory.ARCHIVES
            "apk", "apks", "xapk", "apkm" -> FileCategory.APK
            "resync", "svb", "bak", "backup", "ab" -> FileCategory.BACKUPS
            else -> FileCategory.OTHERS
        }
    }
}

enum class FileCategory(val label: String) {
    ALL("Todos"),
    IMAGES("Imagens"),
    VIDEOS("Vídeos"),
    AUDIO("Áudios"),
    DOCUMENTS("Documentos"),
    ARCHIVES("Compactados"),
    APK("APK"),
    BACKUPS("Backups"),
    OTHERS("Outros")
}


