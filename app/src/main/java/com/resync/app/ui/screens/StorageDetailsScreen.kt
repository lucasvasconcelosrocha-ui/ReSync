package com.resync.app.ui.screens

import com.resync.app.R
import com.resync.app.util.showSafeToast
import androidx.compose.ui.res.stringResource


import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.graphicsLayer
import com.resync.app.data.model.AppInfo
import com.resync.app.data.model.CloudProviderType
import com.resync.app.engine.FileCategory
import com.resync.app.ui.components.RealAppIcon
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StorageCategory {
    APPS,
    IMAGES,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
    SYSTEM,
    OTHER
}

data class StorageCategoryInfo(
    val category: StorageCategory,
    val name: String,
    val sizeBytes: Long,
    val color: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

data class StorageCategoryFile(
    val file: File,
    val name: String,
    val path: String,
    val parentFolder: String,
    val sizeBytes: Long,
    val lastModified: Long
)

fun resolveStorageCategory(name: String): StorageCategory {
    val n = name.trim().lowercase(Locale.ROOT)
        .replace("á", "a")
        .replace("à", "a")
        .replace("ã", "a")
        .replace("é", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("õ", "o")
        .replace("ú", "u")
    return when {
        n in listOf("apps", "aplicativos", "applications") -> StorageCategory.APPS
        n in listOf("imagens", "images", "imagem", "image", "fotos", "photos", "foto", "photo") -> StorageCategory.IMAGES
        n in listOf("videos", "video") -> StorageCategory.VIDEOS
        n in listOf("audio", "audios", "musicas", "music", "som") -> StorageCategory.AUDIO
        n in listOf("documentos", "documents", "documento", "document", "docs", "doc") -> StorageCategory.DOCUMENTS
        n in listOf("sistema", "system") -> StorageCategory.SYSTEM
        else -> StorageCategory.OTHER
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDetailsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToAppDetail: (AppInfo) -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
    onNavigateToFilesCategory: (FileCategory) -> Unit = {},
    onNavigateToStorageCache: () -> Unit = {},
    onNavigateToStorageSystem: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)

    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val isRootActive = permissionsState.isRootGranted || com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true

    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingInstalledApps.collectAsStateWithLifecycle()
    val totalAppCacheBytes by viewModel.totalAppCacheBytes.collectAsStateWithLifecycle()
    val appCacheList by viewModel.appCacheList.collectAsStateWithLifecycle()
    val analyzedCount by viewModel.cacheAnalyzedCount.collectAsStateWithLifecycle()
    val totalAppsCount by viewModel.cacheTotalAppsCount.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }

    // Totais de armazenamento interno (/data e /storage/emulated/0)
    var totalStorageBytes by remember { mutableStateOf(0L) }
    var freeStorageBytes by remember { mutableStateOf(0L) }
    var usedStorageBytes by remember { mutableStateOf(0L) }

    // Categorias
    var categorySizes by remember { mutableStateOf<Map<StorageCategory, Long>>(emptyMap()) }
    var expandedCategory by rememberSaveable { mutableStateOf<StorageCategory?>(null) }
    var appsFilter by rememberSaveable { mutableStateOf("USER") } // "USER", "SYSTEM", "ALL"
    var categoryFilesMap by remember { mutableStateOf<Map<StorageCategory, List<StorageCategoryFile>>>(emptyMap()) }
    var isLoadingCategoryFiles by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<StorageCategoryFile?>(null) }
    var fileForBackup by remember { mutableStateOf<StorageCategoryFile?>(null) }
    var showBackupChoiceDialog by remember { mutableStateOf(false) }

    val categories = remember(categorySizes, context) {
        listOf(
            StorageCategoryInfo(StorageCategory.APPS, context.getString(R.string.category_apps), categorySizes[StorageCategory.APPS] ?: 0L, AccentElectricCyan, Icons.Default.Apps),
            StorageCategoryInfo(StorageCategory.IMAGES, context.getString(R.string.storage_images_total), categorySizes[StorageCategory.IMAGES] ?: 0L, AccentEmeraldGreen, Icons.Default.PhotoLibrary),
            StorageCategoryInfo(StorageCategory.VIDEOS, context.getString(R.string.category_videos), categorySizes[StorageCategory.VIDEOS] ?: 0L, AccentSunsetOrange, Icons.Default.VideoLibrary),
            StorageCategoryInfo(StorageCategory.AUDIO, context.getString(R.string.category_audio), categorySizes[StorageCategory.AUDIO] ?: 0L, AccentNeonPurple, Icons.Default.MusicNote),
            StorageCategoryInfo(StorageCategory.DOCUMENTS, context.getString(R.string.storage_documents_total), categorySizes[StorageCategory.DOCUMENTS] ?: 0L, AccentBrightAmber, Icons.Default.Description),
            StorageCategoryInfo(StorageCategory.SYSTEM, context.getString(R.string.device_category_system), categorySizes[StorageCategory.SYSTEM] ?: 0L, TextMuted, Icons.Default.Memory),
            StorageCategoryInfo(StorageCategory.OTHER, context.getString(R.string.storage_other_total), categorySizes[StorageCategory.OTHER] ?: 0L, Color(0xFF94A3B8), Icons.Default.FolderOpen)
        )
    }

    suspend fun queryCategoryFiles(cat: StorageCategory): List<StorageCategoryFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StorageCategoryFile>()
        val seenPaths = mutableSetOf<String>()

        fun queryUri(uri: Uri, projection: Array<String>, selection: String? = null, args: Array<String>? = null) {
            try {
                context.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    args,
                    "${MediaStore.MediaColumns.SIZE} DESC"
                )?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val relCol = cursor.getColumnIndex("relative_path")
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext() && results.size < 150) {
                        val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                        var path = if (dataCol >= 0) cursor.getString(dataCol) else null
                        if (path.isNullOrBlank() && relCol >= 0 && name.isNotBlank()) {
                            val rel = cursor.getString(relCol) ?: ""
                            path = "/storage/emulated/0/${rel.trim('/')}/$name"
                        }
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val date = if (dateCol >= 0) cursor.getLong(dateCol) * 1000L else System.currentTimeMillis()

                        if (!path.isNullOrBlank() && size > 0L && seenPaths.add(path)) {
                            val file = File(path)
                            val parent = try { file.parentFile?.name ?: file.parent ?: "" } catch (_: Exception) { "" }
                            results.add(StorageCategoryFile(file, if (name.isNotBlank()) name else file.name, path, parent, size, date))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        when (cat) {
            StorageCategory.IMAGES -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                queryUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj)
            }
            StorageCategory.VIDEOS -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                queryUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj)
            }
            StorageCategory.AUDIO -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                queryUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj)
            }
            StorageCategory.DOCUMENTS -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                val sel = "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%'"
                queryUri(MediaStore.Files.getContentUri("external"), proj, sel)
            }
            StorageCategory.OTHER -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                val sel = "${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'image/%' AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'video/%' AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'audio/%' AND ${MediaStore.MediaColumns.SIZE} > 524288"
                queryUri(MediaStore.Files.getContentUri("external"), proj, sel)
            }
            else -> {}
        }

        // Fallback para escaneamento direto / root caso resultados estejam vazios ou parciais
        if (results.size < 10) {
            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                val exts = when (cat) {
                    StorageCategory.IMAGES -> listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng")
                    StorageCategory.VIDEOS -> listOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "m4v")
                    StorageCategory.AUDIO -> listOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "wma", "amr")
                    StorageCategory.DOCUMENTS -> listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub")
                    StorageCategory.OTHER -> listOf("zip", "rar", "7z", "tar", "gz", "apk", "bin", "iso", "img", "obb", "dat", "bak")
                    else -> emptyList()
                }
                if (exts.isNotEmpty()) {
                    val candidateDirs = when (cat) {
                        StorageCategory.IMAGES -> listOf(File(extDir, "DCIM"), File(extDir, "Pictures"), File(extDir, "Download"), File(extDir, "WhatsApp/Media/WhatsApp Images"), File(extDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"))
                        StorageCategory.VIDEOS -> listOf(File(extDir, "DCIM"), File(extDir, "Movies"), File(extDir, "Download"), File(extDir, "WhatsApp/Media/WhatsApp Video"), File(extDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"))
                        StorageCategory.AUDIO -> listOf(File(extDir, "Music"), File(extDir, "Download"), File(extDir, "Recordings"), File(extDir, "Podcasts"), File(extDir, "Audio"), File(extDir, "WhatsApp/Media/WhatsApp Audio"), File(extDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"))
                        StorageCategory.DOCUMENTS -> listOf(File(extDir, "Documents"), File(extDir, "Download"), File(extDir, "WhatsApp/Media/WhatsApp Documents"), File(extDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"))
                        StorageCategory.OTHER -> listOf(File(extDir, "Download"), File(extDir, "Android/obb"), extDir)
                        else -> emptyList()
                    }

                    fun scanDir(dir: File, depth: Int = 0) {
                        if (depth > 5 || results.size >= 150 || !dir.exists() || !dir.canRead()) return
                        dir.listFiles()?.forEach { f ->
                            if (f.isDirectory && !f.name.startsWith(".")) {
                                scanDir(f, depth + 1)
                            } else if (f.isFile && f.extension.lowercase(Locale.ROOT) in exts && f.length() > 0L) {
                                if (seenPaths.add(f.absolutePath)) {
                                    results.add(StorageCategoryFile(f, f.name, f.absolutePath, f.parentFile?.name ?: "", f.length(), f.lastModified()))
                                }
                            }
                        }
                    }

                    for (cDir in candidateDirs) {
                        if (cDir.exists()) scanDir(cDir)
                        if (results.size >= 50) break
                    }

                    if (results.isEmpty()) {
                        scanDir(extDir)
                    }
                }
            }
        }

        results.sortedByDescending { it.sizeBytes }
    }

    // Top 5 e 10 aplicativos
    val topApps = remember(installedApps) {
        installedApps.sortedByDescending { it.sizeBytes }.take(10)
    }
    val top5Apps = remember(installedApps) {
        installedApps.sortedByDescending { it.sizeBytes }.take(5)
    }
    val top5TotalBytes = remember(top5Apps) {
        top5Apps.sumOf { it.sizeBytes }
    }
    val appsWithLargestCache = remember(appCacheList) {
        appCacheList.filter { it.cacheBytes > 0 }.sortedByDescending { it.cacheBytes }.take(4)
    }

    suspend fun loadStorageMetrics() = withContext(Dispatchers.IO) {
        // 1. StatFs em /data
        val dataDir = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val total = totalBlocks * blockSize
        val free = availableBlocks * blockSize
        val used = (total - free).coerceAtLeast(0L)

        totalStorageBytes = total
        freeStorageBytes = free
        usedStorageBytes = used

        // 2. Medir MediaStore com Fallback em /storage/emulated/0
        fun queryMediaStoreSize(uri: Uri, selection: String? = null): Long {
            var sum = 0L
            try {
                val projection = arrayOf(MediaStore.MediaColumns.SIZE)
                context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        if (sizeCol >= 0) {
                            sum += cursor.getLong(sizeCol).coerceAtLeast(0L)
                        }
                    }
                }
            } catch (_: Exception) {}
            return sum
        }

        fun sumFilesQuick(dirs: List<File>, extensions: Set<String>, maxDepth: Int = 3): Long {
            var sum = 0L
            val seen = HashSet<String>()
            fun walk(d: File, depth: Int) {
                if (depth > maxDepth || !d.exists() || !d.canRead()) return
                val list = d.listFiles() ?: return
                for (f in list) {
                    if (f.isDirectory && !f.name.startsWith(".")) {
                        walk(f, depth + 1)
                    } else if (f.isFile && f.extension.lowercase(Locale.ROOT) in extensions) {
                        if (seen.add(f.absolutePath)) {
                            sum += f.length().coerceAtLeast(0L)
                        }
                    }
                }
            }
            for (dir in dirs) walk(dir, 0)
            return sum
        }

        val extDir = Environment.getExternalStorageDirectory()

        var imagesBytes = queryMediaStoreSize(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (imagesBytes == 0L && extDir != null && extDir.exists()) {
            imagesBytes = sumFilesQuick(
                listOf(File(extDir, "DCIM"), File(extDir, "Pictures"), File(extDir, "Download")),
                setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng")
            )
        }

        var videosBytes = queryMediaStoreSize(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        if (videosBytes == 0L && extDir != null && extDir.exists()) {
            videosBytes = sumFilesQuick(
                listOf(File(extDir, "DCIM"), File(extDir, "Movies"), File(extDir, "Download")),
                setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "m4v")
            )
        }

        var audioBytes = queryMediaStoreSize(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        if (audioBytes == 0L && extDir != null && extDir.exists()) {
            audioBytes = sumFilesQuick(
                listOf(File(extDir, "Music"), File(extDir, "Download"), File(extDir, "Recordings"), File(extDir, "Podcasts"), File(extDir, "Audio")),
                setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "wma", "amr")
            )
        }

        var docsBytes = queryMediaStoreSize(
            MediaStore.Files.getContentUri("external"),
            "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%'"
        )
        if (docsBytes == 0L && extDir != null && extDir.exists()) {
            docsBytes = sumFilesQuick(
                listOf(File(extDir, "Documents"), File(extDir, "Download")),
                setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub")
            )
        }

        // 3. Aplicativos (soma real dos apps instalados)
        val appsSum = installedApps.sumOf { it.sizeBytes }

        // 4. Cache real lida do State compartilhado com a tela de limpeza (Fonte Única de Verdade)
        val cacheSum = totalAppCacheBytes

        // 5. Sistema operacional (/system)
        val systemStat = try { StatFs(Environment.getRootDirectory().path) } catch (_: Exception) { null }
        val systemTotal = systemStat?.let { it.blockCountLong * it.blockSizeLong } ?: (used * 0.15).toLong()

        // 6. Outros
        val accounted = appsSum + imagesBytes + videosBytes + audioBytes + docsBytes + systemTotal
        var othersBytes = (used - accounted).coerceAtLeast(0L)
        if (othersBytes == 0L && extDir != null && extDir.exists()) {
            othersBytes = sumFilesQuick(
                listOf(File(extDir, "Download"), File(extDir, "Android/obb")),
                setOf("zip", "rar", "7z", "tar", "gz", "apk", "bin", "iso", "img", "obb", "dat", "bak")
            )
        }

        // Se a lista sob demanda já carregou arquivos maiores, sincronize em tempo real
        categoryFilesMap[StorageCategory.IMAGES]?.sumOf { it.sizeBytes }?.let { if (it > imagesBytes) imagesBytes = it }
        categoryFilesMap[StorageCategory.VIDEOS]?.sumOf { it.sizeBytes }?.let { if (it > videosBytes) videosBytes = it }
        categoryFilesMap[StorageCategory.AUDIO]?.sumOf { it.sizeBytes }?.let { if (it > audioBytes) audioBytes = it }
        categoryFilesMap[StorageCategory.DOCUMENTS]?.sumOf { it.sizeBytes }?.let { if (it > docsBytes) docsBytes = it }
        categoryFilesMap[StorageCategory.OTHER]?.sumOf { it.sizeBytes }?.let { if (it > othersBytes) othersBytes = it }

        categorySizes = mapOf(
            StorageCategory.APPS to appsSum,
            StorageCategory.IMAGES to imagesBytes,
            StorageCategory.VIDEOS to videosBytes,
            StorageCategory.AUDIO to audioBytes,
            StorageCategory.DOCUMENTS to docsBytes,
            StorageCategory.SYSTEM to systemTotal,
            StorageCategory.OTHER to othersBytes
        )
    }

    LaunchedEffect(Unit) {
        if (installedApps.isEmpty()) {
            viewModel.refreshInstalledApps(force = false)
        }
        viewModel.scanAppCache(force = false)
    }

    LaunchedEffect(installedApps, totalAppCacheBytes) {
        loadStorageMetrics()
    }

    val usedPercent = remember(usedStorageBytes, totalStorageBytes) {
        if (totalStorageBytes > 0) ((usedStorageBytes.toDouble() / totalStorageBytes.toDouble()) * 100.0).toFloat() else 0f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (usedPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "storageProgress"
    )

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, DarkCardBorder),
                        modifier = Modifier
                            .size(38.dp)
                            .clickable { onBack() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.btn_back),
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.dash_storage_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            fontSize = 18.sp
                        )
                        Text(
                            text = stringResource(R.string.storage_system_memory),
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Status pill "Saudável"
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (usedPercent < 90f) Color(0xFF0A2518) else Color(0xFF2A1515),
                        border = BorderStroke(1.dp, if (usedPercent < 90f) Color(0xFF1B432E) else Color(0xFF4A2020))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (usedPercent < 90f) Color(0xFF00E575) else StatusError)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (usedPercent < 90f) stringResource(R.string.storage_healthy) else stringResource(R.string.storage_warning),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (usedPercent < 90f) Color(0xFF00E575) else StatusError
                            )
                        }
                    }

                    // Botão Atualizar
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, DarkCardBorder),
                        modifier = Modifier
                            .size(38.dp)
                            .clickable {
                                isRefreshing = true
                                coroutineScope.launch {
                                    loadStorageMetrics()
                                    viewModel.scanAppCache(force = true)
                                    isRefreshing = false
                                }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFFFFA726), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Atualizar",
                                    tint = Color(0xFFFFA726),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        var showCategoryDetailsSection by rememberSaveable { mutableStateOf(false) }

        val appsSum = categorySizes[StorageCategory.APPS] ?: installedApps.sumOf { it.sizeBytes }
        val imagesCat = categorySizes[StorageCategory.IMAGES] ?: 0L
        val videosCat = categorySizes[StorageCategory.VIDEOS] ?: 0L
        val audioCat = categorySizes[StorageCategory.AUDIO] ?: 0L
        val docsCat = categorySizes[StorageCategory.DOCUMENTS] ?: 0L
        val systemCat = categorySizes[StorageCategory.SYSTEM] ?: 0L
        val othersCat = categorySizes[StorageCategory.OTHER] ?: 0L
        val mediaSum = imagesCat + videosCat

        val appsPercent = if (totalStorageBytes > 0) ((appsSum.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f
        val imagesPercent = if (totalStorageBytes > 0) ((imagesCat.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f
        val videosPercent = if (totalStorageBytes > 0) ((videosCat.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f
        val audioPercent = if (totalStorageBytes > 0) ((audioCat.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f
        val docsPercent = if (totalStorageBytes > 0) ((docsCat.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f
        val cachePercent = if (totalStorageBytes > 0) ((totalAppCacheBytes.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f
        val systemPercent = if (totalStorageBytes > 0) ((systemCat.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f
        val othersPercent = if (totalStorageBytes > 0) ((othersCat.toFloat() / totalStorageBytes.toFloat()) * 100f) else 0f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ==========================================
            // 1) GRÁFICO CIRCULAR / DONUT ANIMADO (FOTO 1)
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(175.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                            // Trilho de fundo
                            drawArc(
                                color = Color(0xFF1B2436),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round)
                            )
                            // Arco animado de progresso (Laranja brilhante)
                            drawArc(
                                color = Color(0xFFFF7A00),
                                startAngle = -90f,
                                sweepAngle = animatedProgress * 360f,
                                useCenter = false,
                                style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = String.format(Locale("pt", "BR"), "%.1f%%", usedPercent),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.storage_used_space_header),
                                fontSize = 10.5.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Barra segmentada multicolorida
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1B2436))
                    ) {
                        if (usedStorageBytes > 0L) {
                            val wApps = (appsSum.toFloat() / usedStorageBytes.toFloat()).coerceIn(0.02f, 1f)
                            val wMedia = (mediaSum.toFloat() / usedStorageBytes.toFloat()).coerceIn(0.02f, 1f)
                            val wDocs = (docsCat.toFloat() / usedStorageBytes.toFloat()).coerceIn(0.01f, 1f)
                            val wSys = (systemCat.toFloat() / usedStorageBytes.toFloat()).coerceIn(0.01f, 1f)

                            Box(modifier = Modifier.weight(wApps).fillMaxHeight().background(Color(0xFF00D2FF)))
                            Box(modifier = Modifier.weight(wMedia).fillMaxHeight().background(Color(0xFF00E575)))
                            Box(modifier = Modifier.weight(wDocs).fillMaxHeight().background(Color(0xFFFFB300)))
                            Box(modifier = Modifier.weight(wSys).fillMaxHeight().background(Color(0xFF64748B)))
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("USADO", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(FormatUtils.formatBytes(usedStorageBytes), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA726))
                        }
                        VerticalDivider(modifier = Modifier.height(32.dp), color = Color(0xFF1E283D))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("LIVRE", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(FormatUtils.formatBytes(freeStorageBytes), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E575))
                        }
                        VerticalDivider(modifier = Modifier.height(32.dp), color = Color(0xFF1E283D))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TOTAL", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(FormatUtils.formatBytes(totalStorageBytes), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // 2) CARD RÁPIDO: LIMPEZA DE CACHE (FOTO 1)
            // ==========================================
            val totalDisplay = if (totalAppsCount > 0) totalAppsCount else installedApps.size
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Limpeza de Cache",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = FormatUtils.formatBytes(totalAppCacheBytes),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$analyzedCount de $totalDisplay apps analisados",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = onNavigateToStorageCache,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = "Limpar tudo",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 3) DIVISÃO POR CATEGORIA (BENTO GRID 2x2 - FOTO 1)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.storage_breakdown_header),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = TextSecondary
                )
                Text(
                    text = if (showCategoryDetailsSection) stringResource(R.string.storage_hide_details) else stringResource(R.string.view_details),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00E5FF),
                    modifier = Modifier.clickable {
                        showCategoryDetailsSection = !showCategoryDetailsSection
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bento Grid 4x2 com as 8 categorias unificadas (FOTO 1)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Linha 1: Aplicativos e Imagens
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Card 1: Aplicativos
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                expandedCategory = if (expandedCategory == StorageCategory.APPS) null else StorageCategory.APPS
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF00D2FF).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFF00D2FF), modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF00D2FF).copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Color(0xFF00D2FF).copy(alpha = 0.35f))
                                ) {
                                    Text(stringResource(R.string.storage_top_usage), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00D2FF), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.category_apps), fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(appsSum), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale.getDefault(), "%.1f%%", appsPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val appsBarWidth = if (appsSum <= 0L || totalStorageBytes <= 0L) 0f else (appsPercent / 100f).coerceIn(0.04f, 1f)
                            if (appsBarWidth > 0f) {
                                Box(modifier = Modifier.fillMaxWidth(appsBarWidth).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF00D2FF)))
                            }
                        }
                    }

                    // Card 2: Imagens
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                val target = StorageCategory.IMAGES
                                expandedCategory = if (expandedCategory == target) null else target
                                if (expandedCategory == target && categoryFilesMap[target] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(target)
                                        categoryFilesMap = categoryFilesMap + (target to files)
                                        val filesSum = files.sumOf { it.sizeBytes }
                                        val currentSize = categorySizes[target] ?: 0L
                                        if (filesSum > currentSize || currentSize == 0L) {
                                            categorySizes = categorySizes + (target to filesSum)
                                        }
                                        isLoadingCategoryFiles = false
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF00E575).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFF00E575), modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF00E575).copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Color(0xFF00E575).copy(alpha = 0.35f))
                                ) {
                                    Text(stringResource(R.string.storage_photos), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E575), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.storage_images_total), fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(imagesCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale.getDefault(), "%.1f%%", imagesPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val imagesBarWidth = if (imagesCat <= 0L || totalStorageBytes <= 0L) 0f else (imagesPercent / 100f).coerceIn(0.04f, 1f)
                            if (imagesBarWidth > 0f) {
                                Box(modifier = Modifier.fillMaxWidth(imagesBarWidth).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF00E575)))
                            }
                        }
                    }
                }

                // Linha 2: Vídeos e Áudio
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Card 3: Vídeos
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                val target = StorageCategory.VIDEOS
                                expandedCategory = if (expandedCategory == target) null else target
                                if (expandedCategory == target && categoryFilesMap[target] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(target)
                                        categoryFilesMap = categoryFilesMap + (target to files)
                                        val filesSum = files.sumOf { it.sizeBytes }
                                        val currentSize = categorySizes[target] ?: 0L
                                        if (filesSum > currentSize || currentSize == 0L) {
                                            categorySizes = categorySizes + (target to filesSum)
                                        }
                                        isLoadingCategoryFiles = false
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF818CF8).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF818CF8).copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Color(0xFF818CF8).copy(alpha = 0.35f))
                                ) {
                                    Text(stringResource(R.string.storage_hd_videos), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF818CF8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.category_videos), fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(videosCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale.getDefault(), "%.1f%%", videosPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val videosBarWidth = if (videosCat <= 0L || totalStorageBytes <= 0L) 0f else (videosPercent / 100f).coerceIn(0.04f, 1f)
                            if (videosBarWidth > 0f) {
                                Box(modifier = Modifier.fillMaxWidth(videosBarWidth).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF818CF8)))
                            }
                        }
                    }

                    // Card 4: Áudio
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                val target = StorageCategory.AUDIO
                                expandedCategory = if (expandedCategory == target) null else target
                                if (expandedCategory == target && categoryFilesMap[target] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(target)
                                        categoryFilesMap = categoryFilesMap + (target to files)
                                        val filesSum = files.sumOf { it.sizeBytes }
                                        val currentSize = categorySizes[target] ?: 0L
                                        if (filesSum > currentSize || currentSize == 0L) {
                                            categorySizes = categorySizes + (target to filesSum)
                                        }
                                        isLoadingCategoryFiles = false
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFF59E0B).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Color(0xFFF59E0B).copy(alpha = 0.35f))
                                ) {
                                    Text(stringResource(R.string.storage_music), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.category_audio), fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(audioCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale.getDefault(), "%.1f%%", audioPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val audioBarWidth = if (audioCat <= 0L || totalStorageBytes <= 0L) 0f else (audioPercent / 100f).coerceIn(0.04f, 1f)
                            if (audioBarWidth > 0f) {
                                Box(modifier = Modifier.fillMaxWidth(audioBarWidth).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFF59E0B)))
                            }
                        }
                    }
                }

                // Linha 3: Documentos e Sistema
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Card 5: Documentos
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                val target = StorageCategory.DOCUMENTS
                                expandedCategory = if (expandedCategory == target) null else target
                                if (expandedCategory == target && categoryFilesMap[target] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(target)
                                        categoryFilesMap = categoryFilesMap + (target to files)
                                        val filesSum = files.sumOf { it.sizeBytes }
                                        val currentSize = categorySizes[target] ?: 0L
                                        if (filesSum > currentSize || currentSize == 0L) {
                                            categorySizes = categorySizes + (target to filesSum)
                                        }
                                        isLoadingCategoryFiles = false
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFFB300).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFFFB300).copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Color(0xFFFFB300).copy(alpha = 0.35f))
                                ) {
                                    Text("PDF/Docs", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.storage_documents_total), fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(docsCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale.getDefault(), "%.1f%%", docsPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val docsBarWidth = if (docsCat <= 0L || totalStorageBytes <= 0L) 0f else (docsPercent / 100f).coerceIn(0.04f, 1f)
                            if (docsBarWidth > 0f) {
                                Box(modifier = Modifier.fillMaxWidth(docsBarWidth).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFB300)))
                            }
                        }
                    }

                    // Card 6: Sistema
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable { onNavigateToStorageSystem() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF64748B).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Memory, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF64748B).copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Color(0xFF64748B).copy(alpha = 0.35f))
                                ) {
                                    Text(stringResource(R.string.storage_system_files), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.device_category_system), fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(systemCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale.getDefault(), "%.1f%%", systemPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val systemBarWidth = if (systemCat <= 0L || totalStorageBytes <= 0L) 0f else (systemPercent / 100f).coerceIn(0.04f, 1f)
                            if (systemBarWidth > 0f) {
                                Box(modifier = Modifier.fillMaxWidth(systemBarWidth).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF64748B)))
                            }
                        }
                    }
                }

                // Linha 4: Outros (Largura Total - Clicável)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .clickable {
                            val target = StorageCategory.OTHER
                            expandedCategory = if (expandedCategory == target) null else target
                            if (expandedCategory == target && categoryFilesMap[target] == null) {
                                coroutineScope.launch {
                                    isLoadingCategoryFiles = true
                                    val files = queryCategoryFiles(target)
                                    categoryFilesMap = categoryFilesMap + (target to files)
                                    val filesSum = files.sumOf { it.sizeBytes }
                                    val currentSize = categorySizes[target] ?: 0L
                                    if (filesSum > currentSize || currentSize == 0L) {
                                        categorySizes = categorySizes + (target to filesSum)
                                    }
                                    isLoadingCategoryFiles = false
                                }
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF94A3B8).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF94A3B8).copy(alpha = 0.12f),
                                border = BorderStroke(0.5.dp, Color(0xFF94A3B8).copy(alpha = 0.35f))
                            ) {
                                Text(stringResource(R.string.storage_files_label), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.storage_other_total), fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(FormatUtils.formatBytes(othersCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(String.format(Locale.getDefault(), "%.1f%%", othersPercent), fontSize = 11.sp, color = TextMuted)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val othersBarWidth = if (othersCat <= 0L || totalStorageBytes <= 0L) 0f else (othersPercent / 100f).coerceIn(0.04f, 1f)
                        if (othersBarWidth > 0f) {
                            Box(modifier = Modifier.fillMaxWidth(othersBarWidth).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF94A3B8)))
                        }
                    }
                }
            }

            // Seção Expansível Detalhada de Arquivos / Categoria Selecionada (Substitui lista duplicada)
            AnimatedVisibility(visible = expandedCategory != null) {
                val selectedCat = expandedCategory ?: StorageCategory.APPS
                val catObj = categories.find { it.category == selectedCat }
                val isAppsCategory = selectedCat == StorageCategory.APPS
                Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = catObj?.icon ?: (if (isAppsCategory) Icons.Default.Apps else Icons.Default.Folder),
                                        contentDescription = null,
                                        tint = catObj?.color ?: (if (isAppsCategory) AccentElectricCyan else MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isAppsCategory) {
                                            stringResource(R.string.storage_installed_apps_title)
                                        } else {
                                            val displayName = catObj?.name ?: ""
                                            stringResource(R.string.storage_files_in_cat, displayName)
                                        },
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                IconButton(
                                    onClick = { expandedCategory = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                                if (isAppsCategory) {
                                    val sortedApps = remember(installedApps) {
                                        installedApps.sortedByDescending { it.sizeBytes }
                                    }
                                    val cacheByPkg = remember(appCacheList) {
                                        appCacheList.associate { it.app.packageName to it.cacheBytes }
                                    }
                                    if (sortedApps.isEmpty()) {
                                        Text(
                                            text = if (isLoadingApps) "Carregando..." else stringResource(R.string.storage_no_files_found),
                                            fontSize = 11.sp,
                                            color = TextMuted,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            items(sortedApps, key = { it.packageName }) { app ->
                                                val cacheBytes = cacheByPkg[app.packageName] ?: 0L
                                                val subtitleText = if (cacheBytes > 0L) {
                                                    "${app.packageName} • ${FormatUtils.formatBytes(cacheBytes)} cache"
                                                } else {
                                                    app.packageName
                                                }
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onNavigateToAppDetail(app) }
                                                        .padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    RealAppIcon(packageName = app.packageName, size = 32.dp)
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = app.appName,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = TextPrimary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = subtitleText,
                                                            fontSize = 9.5.sp,
                                                            color = TextMuted,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = FormatUtils.formatBytes(app.sizeBytes),
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = catObj?.color ?: AccentElectricCyan
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val files = categoryFilesMap[selectedCat]
                                    if (isLoadingCategoryFiles && files == null) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = catObj?.color ?: MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else if (files.isNullOrEmpty()) {
                                        Text(stringResource(R.string.storage_no_files_found), fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(vertical = 8.dp))
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            items(files.take(50), key = { it.path }) { fItem ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(imageVector = catObj?.icon ?: Icons.Default.Folder, contentDescription = null, tint = catObj?.color ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(text = fItem.name, fontSize = 11.5.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(text = fItem.parentFolder, fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                                    }
                                                    Text(text = FormatUtils.formatBytes(fItem.sizeBytes), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = catObj?.color ?: MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // 4) TOP 5 MAIORES APLICATIVOS (FOTO 1)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.storage_top_5_apps),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Ordenado pelo volume em disco",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "Total: ${FormatUtils.formatBytes(top5TotalBytes)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (top5Apps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.storage_no_apps_analyzed), fontSize = 12.sp, color = TextMuted)
                        }
                    } else {
                        top5Apps.forEachIndexed { index, app ->
                            if (index > 0) {
                                HorizontalDivider(color = Color(0xFF1E283D).copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))
                            }

                            val rankColor = when (index) {
                                0, 1, 2 -> Color(0xFFFFA726)
                                3 -> Color(0xFF00D2FF)
                                else -> Color(0xFFFF3B47)
                            }

                            val appCache = appCacheList.find { it.app.packageName == app.packageName }?.cacheBytes ?: 0L

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToAppDetail(app) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = rankColor,
                                    modifier = Modifier.width(18.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                RealAppIcon(
                                    packageName = app.packageName,
                                    size = 38.dp
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = app.appName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (appCache > 0L) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF16233B),
                                                border = BorderStroke(0.5.dp, Color(0xFF23395F))
                                            ) {
                                                Text(
                                                    text = "${FormatUtils.formatBytes(appCache)} cache",
                                                    fontSize = 9.sp,
                                                    color = Color(0xFF60A5FA),
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = app.packageName,
                                        fontSize = 10.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = FormatUtils.formatBytes(app.sizeBytes),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = rankColor
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(42.dp)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(1.5.dp))
                                            .background(rankColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // 5) APLICAÇÕES COM MAIOR CACHE (FOTO 1)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF7A00))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.storage_top_cache_apps),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Text(
                    text = "Limpeza individual",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1422))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (appsWithLargestCache.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.storage_no_pending_cache), fontSize = 12.sp, color = TextMuted)
                        }
                    } else {
                        appsWithLargestCache.forEachIndexed { index, item ->
                            if (index > 0) {
                                HorizontalDivider(color = Color(0xFF1E283D).copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    RealAppIcon(packageName = item.app.packageName, size = 36.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = item.app.appName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Cache: ${FormatUtils.formatBytes(item.cacheBytes)}",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFFFA726)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                if (!isRootActive) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF192336),
                                        border = BorderStroke(1.dp, Color(0xFF2B3A54)),
                                        modifier = Modifier.clickable {
                                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", item.app.packageName, null)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        }
                                    ) {
                                        Text(
                                            text = "Config",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF192336),
                                    border = BorderStroke(1.dp, Color(0xFF2B3A54)),
                                    modifier = Modifier.clickable {
                                        viewModel.clearSingleAppCache(item)
                                    }
                                ) {
                                    Text(
                                        text = "Limpar",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFD1D5DB),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // 6) BANNER: OTIMIZADOR (FOTO 1)
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF183B5E), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1525))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF08263E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "OTIMIZADOR",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (totalAppCacheBytes > 0) stringResource(R.string.storage_free_up_to, FormatUtils.formatBytes(totalAppCacheBytes)) else stringResource(R.string.storage_system_optimized),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }

                    Button(
                        onClick = onNavigateToStorageCache,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Otimizar Agora", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        if (fileToDelete != null) {
            val target = fileToDelete!!
            AlertDialog(
                onDismissRequest = { fileToDelete = null },
                title = { Text("Excluir ${target.name}?", fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.storage_delete_permanent_warning)) },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val freed = target.sizeBytes
                                val deleted = withContext(Dispatchers.IO) {
                                    var ok = target.file.delete()
                                    if (!ok) {
                                        runCatching {
                                            com.topjohnwu.superuser.Shell.cmd("rm -f '${target.file.absolutePath}'").exec().isSuccess
                                        }.getOrDefault(false)
                                    } else true
                                }
                                if (deleted) {
                                    withContext(Dispatchers.IO) {
                                        MediaScannerConnection.scanFile(context, arrayOf(target.file.absolutePath), null, null)
                                    }
                                    // Atualizar na hora a lista, o tamanho da categoria, o gráfico e os valores Usado, Livre e Total
                                    val matchingCat = categoryFilesMap.entries.firstOrNull { entry -> entry.value.any { it.path == target.path } }?.key
                                    categoryFilesMap = categoryFilesMap.mapValues { (_, list) ->
                                        list.filter { it.path != target.path }
                                    }
                                    if (matchingCat != null) {
                                        val currentBytes = categorySizes[matchingCat] ?: 0L
                                        categorySizes = categorySizes + (matchingCat to maxOf(0L, currentBytes - freed))
                                    }
                                    usedStorageBytes = maxOf(0L, usedStorageBytes - freed)
                                    freeStorageBytes = minOf(totalStorageBytes, freeStorageBytes + freed)
                                    context.showSafeToast(context.getString(R.string.storage_file_deleted_toast, target.name), Toast.LENGTH_SHORT)
                                } else {
                                    context.showSafeToast(context.getString(R.string.storage_file_delete_err_toast), Toast.LENGTH_SHORT)
                                }
                                fileToDelete = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                    ) {
                        Text("Excluir", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileToDelete = null }) {
                        Text(stringResource(R.string.action_cancel), color = TextSecondary)
                    }
                },
                containerColor = DarkSurfaceVariant
            )
        }

        if (showBackupChoiceDialog && fileForBackup != null) {
            val bTarget = fileForBackup!!
            AlertDialog(
                onDismissRequest = {
                    showBackupChoiceDialog = false
                    fileForBackup = null
                },
                title = { Text("Backup de ${bTarget.name}", fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.storage_choose_dest_file)) },
                confirmButton = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val path = bTarget.path
                                showBackupChoiceDialog = false
                                fileForBackup = null
                                viewModel.launchBackup(
                                    title = "Backup - ${bTarget.name}",
                                    selectedApps = emptyList(),
                                    selectedFiles = listOf(path),
                                    includeSms = false,
                                    includeCalls = false,
                                    includeContacts = false,
                                    includeDns = false,
                                    includeWallpaper = false,
                                    targetCloudProvider = null
                                )
                                context.showSafeToast(context.getString(R.string.storage_internal_backup_started), Toast.LENGTH_SHORT)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.explorer_internal_storage_backup), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                val path = bTarget.path
                                showBackupChoiceDialog = false
                                fileForBackup = null
                                viewModel.launchBackup(
                                    title = "Backup - ${bTarget.name}",
                                    selectedApps = emptyList(),
                                    selectedFiles = listOf(path),
                                    includeSms = false,
                                    includeCalls = false,
                                    includeContacts = false,
                                    includeDns = false,
                                    includeWallpaper = false,
                                    targetCloudProvider = CloudProviderType.GOOGLE_DRIVE
                                )
                                context.showSafeToast(context.getString(R.string.storage_cloud_backup_started), Toast.LENGTH_SHORT)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentElectricCyan)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.explorer_cloud_backup_gdrive), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showBackupChoiceDialog = false
                        fileForBackup = null
                    }) {
                        Text(stringResource(R.string.action_cancel), color = TextSecondary)
                    }
                },
                containerColor = DarkSurfaceVariant
            )
        }
    }
}
