package com.resync.app.ui.screens

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

data class StorageCategory(
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
    var categories by remember { mutableStateOf<List<StorageCategory>>(emptyList()) }

    var expandedCategories by rememberSaveable { mutableStateOf(setOf<String>()) }
    var appsFilter by rememberSaveable { mutableStateOf("USER") } // "USER", "SYSTEM", "ALL"
    var categoryFilesMap by remember { mutableStateOf<Map<String, List<StorageCategoryFile>>>(emptyMap()) }
    var isLoadingCategoryFiles by remember { mutableStateOf(false) }
    var visibleCountMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var fileToDelete by remember { mutableStateOf<StorageCategoryFile?>(null) }
    var fileForBackup by remember { mutableStateOf<StorageCategoryFile?>(null) }
    var showBackupChoiceDialog by remember { mutableStateOf(false) }

    suspend fun queryCategoryFiles(catName: String): List<StorageCategoryFile> = withContext(Dispatchers.IO) {
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
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext() && results.size < 120) {
                        val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                        if (path != null && seenPaths.add(path)) {
                            val file = File(path)
                            val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else file.length()
                            val name = if (nameCol >= 0) cursor.getString(nameCol) ?: file.name else file.name
                            val date = if (dateCol >= 0) cursor.getLong(dateCol) * 1000L else file.lastModified()
                            val parent = file.parentFile?.name ?: file.parent ?: ""
                            if (file.exists() && size > 0L) {
                                results.add(StorageCategoryFile(file, name, path, parent, size, date))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        when (catName.lowercase()) {
            "imagens" -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                queryUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj)
            }
            "vídeos" -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                queryUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj)
            }
            "áudio" -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                queryUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj)
            }
            "documentos" -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                val sel = "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%'"
                queryUri(MediaStore.Files.getContentUri("external"), proj, sel)
            }
            "outros" -> {
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                val sel = "${MediaStore.MediaColumns.SIZE} > 1048576"
                queryUri(MediaStore.Files.getContentUri("external"), proj, sel)
            }
        }

        // Fallback para escaneamento direto / root caso resultados estejam vazios
        if (results.isEmpty()) {
            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                val exts = when (catName.lowercase()) {
                    "imagens" -> listOf("jpg", "jpeg", "png", "webp", "gif", "heic")
                    "vídeos" -> listOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
                    "áudio" -> listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
                    "documentos" -> listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub")
                    else -> emptyList()
                }
                if (exts.isNotEmpty()) {
                    val found = mutableListOf<File>()
                    fun scan(dir: File, depth: Int = 0) {
                        if (depth > 4 || found.size >= 50) return
                        dir.listFiles()?.forEach { f ->
                            if (f.isDirectory && !f.name.startsWith(".") && f.name != "Android") {
                                scan(f, depth + 1)
                            } else if (f.isFile && f.extension.lowercase() in exts && f.length() > 0L) {
                                found.add(f)
                            }
                        }
                    }
                    scan(extDir)
                    found.forEach { f ->
                        if (seenPaths.add(f.absolutePath)) {
                            results.add(StorageCategoryFile(f, f.name, f.absolutePath, f.parentFile?.name ?: "", f.length(), f.lastModified()))
                        }
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

        // 2. Medir MediaStore (Imagens, Vídeos, Áudio, Documentos)
        var imagesBytes = 0L
        var videosBytes = 0L
        var audioBytes = 0L
        var docsBytes = 0L

        fun queryMediaStoreSize(uri: android.net.Uri, mimePrefix: String? = null): Long {
            var sum = 0L
            try {
                val projection = arrayOf(MediaStore.MediaColumns.SIZE)
                val selection = if (mimePrefix != null) "${MediaStore.MediaColumns.MIME_TYPE} LIKE '$mimePrefix%'" else null
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

        imagesBytes = queryMediaStoreSize(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        videosBytes = queryMediaStoreSize(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        audioBytes = queryMediaStoreSize(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        docsBytes = queryMediaStoreSize(MediaStore.Files.getContentUri("external"), "application/")

        // 3. Aplicativos (soma real dos apps instalados)
        val appsSum = installedApps.sumOf { it.sizeBytes }

        // 4. Cache real lida do State compartilhado com a tela de limpeza (Fonte Única de Verdade)
        val cacheSum = totalAppCacheBytes

        // 5. Sistema operacional (/system)
        val systemStat = try { StatFs(Environment.getRootDirectory().path) } catch (_: Exception) { null }
        val systemTotal = systemStat?.let { it.blockCountLong * it.blockSizeLong } ?: (used * 0.15).toLong()

        // 6. Outros
        val accounted = appsSum + imagesBytes + videosBytes + audioBytes + docsBytes + cacheSum
        val othersBytes = (used - accounted).coerceAtLeast(0L)

        categories = listOf(
            StorageCategory("Aplicativos", appsSum, AccentElectricCyan, Icons.Default.Apps),
            StorageCategory("Imagens", imagesBytes, AccentEmeraldGreen, Icons.Default.Image),
            StorageCategory("Vídeos", videosBytes, AccentSunsetOrange, Icons.Default.VideoLibrary),
            StorageCategory("Áudio", audioBytes, AccentNeonPurple, Icons.Default.MusicNote),
            StorageCategory("Documentos", docsBytes, AccentBrightAmber, Icons.Default.Description),
            StorageCategory("Cache", cacheSum, StatusWarning, Icons.Default.CleaningServices),
            StorageCategory("Sistema", systemTotal, TextMuted, Icons.Default.Memory),
            StorageCategory("Outros", othersBytes, DarkCardBorder, Icons.Default.FolderOpen)
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
                                contentDescription = "Voltar",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Armazenamento",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Memória interna do sistema",
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
                                text = if (usedPercent < 90f) "Saudável" else "Atenção",
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

        val appsSum = remember(installedApps) { installedApps.sumOf { it.sizeBytes } }
        val imagesCat = categories.find { it.name.equals("Imagens", ignoreCase = true) }?.sizeBytes ?: 0L
        val videosCat = categories.find { it.name.equals("Vídeos", ignoreCase = true) }?.sizeBytes ?: 0L
        val audioCat = categories.find { it.name.equals("Áudio", ignoreCase = true) }?.sizeBytes ?: 0L
        val docsCat = categories.find { it.name.equals("Documentos", ignoreCase = true) }?.sizeBytes ?: 0L
        val systemCat = categories.find { it.name.equals("Sistema", ignoreCase = true) }?.sizeBytes ?: 0L
        val othersCat = categories.find { it.name.equals("Outros", ignoreCase = true) }?.sizeBytes ?: 0L
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
                                text = "ESPAÇO USADO",
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
                    text = "DIVISÃO POR CATEGORIA",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = TextSecondary
                )
                Text(
                    text = if (showCategoryDetailsSection) "Ocultar" else "Ver detalhes",
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
                                expandedCategories = if ("Aplicativos" in expandedCategories) emptySet() else setOf("Aplicativos")
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
                                    Text("Maior uso", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00D2FF), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Aplicativos", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(appsSum), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", appsPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.85f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF00D2FF)))
                        }
                    }

                    // Card 2: Imagens
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                val catName = "Imagens"
                                expandedCategories = if (catName in expandedCategories) emptySet() else setOf(catName)
                                if (catName in expandedCategories && categoryFilesMap[catName] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(catName)
                                        categoryFilesMap = categoryFilesMap + (catName to files)
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
                                    Text("Fotos", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E575), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Imagens", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(imagesCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", imagesPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.6f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF00E575)))
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
                                val catName = "Vídeos"
                                expandedCategories = if (catName in expandedCategories) emptySet() else setOf(catName)
                                if (catName in expandedCategories && categoryFilesMap[catName] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(catName)
                                        categoryFilesMap = categoryFilesMap + (catName to files)
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
                                    Text("Vídeos HD", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF818CF8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Vídeos", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(videosCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", videosPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.45f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF818CF8)))
                        }
                    }

                    // Card 4: Áudio
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                val catName = "Áudio"
                                expandedCategories = if (catName in expandedCategories) emptySet() else setOf(catName)
                                if (catName in expandedCategories && categoryFilesMap[catName] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(catName)
                                        categoryFilesMap = categoryFilesMap + (catName to files)
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
                                    Text("Músicas", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Áudio", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(audioCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", audioPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.35f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFF59E0B)))
                        }
                    }
                }

                // Linha 3: Documentos e Cache
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
                                val catName = "Documentos"
                                expandedCategories = if (catName in expandedCategories) emptySet() else setOf(catName)
                                if (catName in expandedCategories && categoryFilesMap[catName] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(catName)
                                        categoryFilesMap = categoryFilesMap + (catName to files)
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
                            Text("Documentos", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(docsCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", docsPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.35f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFB300)))
                        }
                    }

                    // Card 6: Cache
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable { onNavigateToStorageCache() },
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
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                ) {
                                    Text("Temporário", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Cache", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(totalAppCacheBytes), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", cachePercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.25f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }

                // Linha 4: Sistema e Outros
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Card 7: Sistema
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
                                    Text("Kernel/Base", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Sistema", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(systemCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", systemPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.2f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF64748B)))
                        }
                    }

                    // Card 8: Outros
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable {
                                val catName = "Outros"
                                expandedCategories = if (catName in expandedCategories) emptySet() else setOf(catName)
                                if (catName in expandedCategories && categoryFilesMap[catName] == null) {
                                    coroutineScope.launch {
                                        isLoadingCategoryFiles = true
                                        val files = queryCategoryFiles(catName)
                                        categoryFilesMap = categoryFilesMap + (catName to files)
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
                                    Text("Arquivos", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Outros", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(FormatUtils.formatBytes(othersCat), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(String.format(Locale("pt", "BR"), "%.1f%%", othersPercent), fontSize = 11.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.18f).height(3.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF94A3B8)))
                        }
                    }
                }
            }

            // Seção Expansível Detalhada de Arquivos / Categoria Selecionada (Substitui lista duplicada)
            AnimatedVisibility(visible = expandedCategories.isNotEmpty()) {
                val selectedCatName = expandedCategories.firstOrNull() ?: ""
                val catObj = categories.find { it.name.equals(selectedCatName, ignoreCase = true) }
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
                                        imageVector = catObj?.icon ?: Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = catObj?.color ?: MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Arquivos em $selectedCatName",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                IconButton(
                                    onClick = { expandedCategories = emptySet() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                                if (selectedCatName.lowercase() == "aplicativos") {
                                    val filteredSortedApps = remember(installedApps, appsFilter) {
                                        when (appsFilter) {
                                            "USER" -> installedApps.filter { !it.isSystemApp }
                                            "SYSTEM" -> installedApps.filter { it.isSystemApp }
                                            else -> installedApps
                                        }.sortedByDescending { it.sizeBytes }
                                    }
                                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(filteredSortedApps.take(15), key = { it.packageName }) { app ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().clickable { onNavigateToAppDetail(app) }.padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RealAppIcon(packageName = app.packageName, size = 32.dp)
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = app.appName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(text = app.packageName, fontSize = 9.5.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(text = FormatUtils.formatBytes(app.sizeBytes), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = catObj?.color ?: MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                } else {
                                    val files = categoryFilesMap[selectedCatName]
                                    if (files.isNullOrEmpty()) {
                                        Text("Nenhum arquivo encontrado.", fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(vertical = 8.dp))
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            items(files.take(15), key = { it.path }) { fItem ->
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
                        text = "TOP 5 MAIORES APLICATIVOS",
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
                            Text("Nenhum aplicativo analisado ainda.", fontSize = 12.sp, color = TextMuted)
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
                        text = "Aplicações com Maior Cache",
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
                            Text("Nenhum aplicativo com cache pendente.", fontSize = 12.sp, color = TextMuted)
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
                                text = if (totalAppCacheBytes > 0) "Libere até ${FormatUtils.formatBytes(totalAppCacheBytes)}" else "Sistema Otimizado",
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
                text = { Text("Esta ação não pode ser desfeita. O arquivo será removido permanentemente da memória do dispositivo.") },
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
                                        categories = categories.map { c ->
                                            if (c.name == matchingCat) c.copy(sizeBytes = maxOf(0L, c.sizeBytes - freed)) else c
                                        }
                                    }
                                    usedStorageBytes = maxOf(0L, usedStorageBytes - freed)
                                    freeStorageBytes = minOf(totalStorageBytes, freeStorageBytes + freed)
                                    Toast.makeText(context, "${target.name} excluído!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Falha ao excluir o arquivo", Toast.LENGTH_SHORT).show()
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
                        Text("Cancelar", color = TextSecondary)
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
                text = { Text("Escolha o destino para armazenar a cópia de segurança deste arquivo:") },
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
                                Toast.makeText(context, "Backup na memória interna iniciado!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup na memória interna", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                                Toast.makeText(context, "Backup na nuvem iniciado!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentElectricCyan)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup na nuvem (Google Drive)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showBackupChoiceDialog = false
                        fileForBackup = null
                    }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurfaceVariant
            )
        }
    }
}
