package com.resync.app.ui.screens

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
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

    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingInstalledApps.collectAsStateWithLifecycle()

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

    // Top 10 aplicativos
    val topApps = remember(installedApps) {
        installedApps.sortedByDescending { it.sizeBytes }.take(10)
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

        // 4. Cache estimada/real
        val cacheSum = (used * 0.04).toLong().coerceAtLeast(100L * 1024L * 1024L)

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
    }

    LaunchedEffect(installedApps) {
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
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Armazenamento do celular",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                IconButton(
                    onClick = {
                        isRefreshing = true
                        coroutineScope.launch {
                            loadStorageMetrics()
                            isRefreshing = false
                        }
                    }
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryAccent, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = primaryAccent)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ==========================================
            // 1) GRÁFICO CIRCULAR / DONUT ANIMADO
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            drawArc(
                                color = DarkBg,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = primaryAccent,
                                startAngle = -90f,
                                sweepAngle = animatedProgress * 360f,
                                useCenter = false,
                                style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = String.format(Locale("pt", "BR"), "%.1f%%", usedPercent),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Usado",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Usado", fontSize = 11.5.sp, color = TextMuted)
                            Text(FormatUtils.formatBytes(usedStorageBytes), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                        }
                        VerticalDivider(modifier = Modifier.height(30.dp), color = DarkCardBorder)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Livre", fontSize = 11.5.sp, color = TextMuted)
                            Text(FormatUtils.formatBytes(freeStorageBytes), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentEmeraldGreen)
                        }
                        VerticalDivider(modifier = Modifier.height(30.dp), color = DarkCardBorder)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total", fontSize = 11.5.sp, color = TextMuted)
                            Text(FormatUtils.formatBytes(totalStorageBytes), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // 2) BARRA DE CATEGORIAS COLORIDA CONTÍNUA
            // ==========================================
            Text(
                text = "DIVISÃO POR CATEGORIA",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Barra contínua colorida
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkBg)
                    ) {
                        if (usedStorageBytes > 0L) {
                            categories.forEach { cat ->
                                if (cat.sizeBytes > 0L) {
                                    val weight = (cat.sizeBytes.toFloat() / usedStorageBytes.toFloat()).coerceIn(0.01f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .weight(weight)
                                            .fillMaxHeight()
                                            .background(cat.color)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Lista detalhada de cada categoria
                    categories.forEachIndexed { index, cat ->
                        if (index > 0) {
                            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
                        }

                        val isExpandableCategory = cat.name.lowercase() in listOf("aplicativos", "imagens", "vídeos", "áudio", "documentos", "outros")
                        val isExpanded = isExpandableCategory && expandedCategories.contains(cat.name)

                        val arrowRotation by animateFloatAsState(
                            targetValue = if (isExpanded) 90f else 0f,
                            animationSpec = tween(durationMillis = 250),
                            label = "chevron_rot_${cat.name}"
                        )

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (isExpandableCategory) {
                                            if (isExpanded) {
                                                expandedCategories = expandedCategories - cat.name
                                            } else {
                                                expandedCategories = expandedCategories + cat.name
                                                if (cat.name.lowercase() != "aplicativos") {
                                                    if (visibleCountMap[cat.name] == null) {
                                                        visibleCountMap = visibleCountMap + (cat.name to 10)
                                                    }
                                                    if (categoryFilesMap[cat.name] == null) {
                                                        coroutineScope.launch {
                                                            isLoadingCategoryFiles = true
                                                            val files = queryCategoryFiles(cat.name)
                                                            categoryFilesMap = categoryFilesMap + (cat.name to files)
                                                            isLoadingCategoryFiles = false
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            when (cat.name.lowercase()) {
                                                "cache" -> onNavigateToStorageCache()
                                                "sistema" -> onNavigateToStorageSystem()
                                                else -> onNavigateToFiles()
                                            }
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(cat.color)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(imageVector = cat.icon, contentDescription = null, tint = cat.color, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = cat.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val sizeDisplay = if (cat.name.equals("aplicativos", ignoreCase = true) && cat.sizeBytes == 0L) {
                                        "Calculando..."
                                    } else {
                                        FormatUtils.formatBytes(cat.sizeBytes)
                                    }
                                    Text(
                                        text = sizeDisplay,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = if (isExpanded) primaryAccent else TextMuted,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .graphicsLayer(rotationZ = arrowRotation)
                                    )
                                }
                            }

                            // Conteúdo Expandido no próprio lugar
                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
                                ) {
                                    if (cat.name.lowercase() == "aplicativos") {
                                        // Chips de filtro no topo da lista: Usuário, Sistema e Todos
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val filters = listOf(
                                                Triple("USER", "Usuário", installedApps.count { !it.isSystemApp }),
                                                Triple("SYSTEM", "Sistema", installedApps.count { it.isSystemApp }),
                                                Triple("ALL", "Todos", installedApps.size)
                                            )
                                            filters.forEach { (key, label, count) ->
                                                val selected = appsFilter == key
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (selected) cat.color.copy(alpha = 0.18f) else DarkBg,
                                                    border = BorderStroke(1.dp, if (selected) cat.color else DarkCardBorder),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(34.dp)
                                                        .clickable { appsFilter = key }
                                                ) {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = "$label ($count)",
                                                            fontSize = 11.sp,
                                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (selected) cat.color else TextSecondary,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        val filteredSortedApps = remember(installedApps, appsFilter) {
                                            val list = when (appsFilter) {
                                                "USER" -> installedApps.filter { !it.isSystemApp }
                                                "SYSTEM" -> installedApps.filter { it.isSystemApp }
                                                else -> installedApps
                                            }
                                            list.sortedByDescending { it.sizeBytes }
                                        }

                                        val appsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 320.dp)
                                        ) {
                                            if (isLoadingApps && filteredSortedApps.isEmpty()) {
                                                Box(
                                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = cat.color, strokeWidth = 2.dp)
                                                }
                                            } else if (filteredSortedApps.isEmpty()) {
                                                Box(
                                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text("Nenhum aplicativo encontrado.", fontSize = 11.5.sp, color = TextMuted)
                                                }
                                            } else {
                                                LazyColumn(
                                                    state = appsListState,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 320.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    items(filteredSortedApps, key = { it.packageName }) { app ->
                                                        Card(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                                                                .clickable { onNavigateToAppDetail(app) },
                                                            colors = CardDefaults.cardColors(containerColor = DarkSurface)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                RealAppIcon(
                                                                    packageName = app.packageName,
                                                                    size = 36.dp
                                                                )
                                                                Spacer(modifier = Modifier.width(10.dp))
                                                                Column(modifier = Modifier.weight(1f)) {
                                                                    Text(
                                                                        text = app.appName,
                                                                        fontSize = 12.5.sp,
                                                                        fontWeight = FontWeight.SemiBold,
                                                                        color = TextPrimary,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                    Text(
                                                                        text = app.packageName,
                                                                        fontSize = 10.sp,
                                                                        color = TextMuted,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                val sizeStr = if (app.sizeBytes == 0L && isLoadingApps) {
                                                                    "Calculando..."
                                                                } else {
                                                                    FormatUtils.formatBytes(app.sizeBytes)
                                                                }
                                                                Text(
                                                                    text = sizeStr,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = cat.color
                                                                )
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Icon(
                                                                    imageVector = Icons.Default.ChevronRight,
                                                                    contentDescription = null,
                                                                    tint = TextMuted,
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { expandedCategories = expandedCategories - cat.name },
                                            modifier = Modifier.fillMaxWidth().height(32.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, DarkCardBorder),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Recolher", fontSize = 11.sp, color = TextSecondary)
                                        }
                                    } else {
                                        val files = categoryFilesMap[cat.name]
                                    if (isLoadingCategoryFiles && files == null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 20.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = cat.color, strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text("Localizando arquivos...", fontSize = 11.5.sp, color = TextMuted)
                                            }
                                        }
                                    } else if (files.isNullOrEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Nenhum arquivo encontrado nesta categoria.", fontSize = 11.5.sp, color = TextMuted)
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 320.dp)
                                        ) {
                                            val categoryListState = rememberSaveable(cat.name, saver = LazyListState.Saver) { LazyListState() }
                                            LazyColumn(
                                                state = categoryListState,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 320.dp)
                                            ) {
                                                itemsIndexed(files, key = { _, fileItem -> fileItem.path }) { fIdx, fileItem ->
                                                    if (fIdx > 0) {
                                                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 2.dp))
                                                    }
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(36.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(cat.color.copy(alpha = 0.12f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(imageVector = cat.icon, contentDescription = null, tint = cat.color, modifier = Modifier.size(18.dp))
                                                        }
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = fileItem.name,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = TextPrimary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = FormatUtils.formatBytes(fileItem.sizeBytes),
                                                                    fontSize = 11.sp,
                                                                    color = cat.color,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                Text("•", fontSize = 9.sp, color = TextMuted)
                                                                Text(
                                                                    text = fileItem.parentFolder,
                                                                    fontSize = 10.5.sp,
                                                                    color = TextSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                Text("•", fontSize = 9.sp, color = TextMuted)
                                                                Text(
                                                                    text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(fileItem.lastModified)),
                                                                    fontSize = 10.sp,
                                                                    color = TextMuted
                                                                )
                                                            }
                                                        }
                                                        Box {
                                                            var menuOpen by remember { mutableStateOf(false) }
                                                            IconButton(
                                                                onClick = { menuOpen = true },
                                                                modifier = Modifier.size(32.dp)
                                                            ) {
                                                                Icon(Icons.Default.MoreVert, contentDescription = "Opções", tint = TextMuted, modifier = Modifier.size(16.dp))
                                                            }
                                                            DropdownMenu(
                                                                expanded = menuOpen,
                                                                onDismissRequest = { menuOpen = false },
                                                                modifier = Modifier.background(DarkSurfaceVariant)
                                                            ) {
                                                                DropdownMenuItem(
                                                                    text = { Text("Compartilhar", fontSize = 12.sp, color = TextPrimary) },
                                                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp)) },
                                                                    onClick = {
                                                                        menuOpen = false
                                                                        try {
                                                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                                                type = "*/*"
                                                                                val uri = try {
                                                                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fileItem.file)
                                                                                } catch (_: Exception) {
                                                                                    Uri.fromFile(fileItem.file)
                                                                                }
                                                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                            }
                                                                            context.startActivity(Intent.createChooser(shareIntent, "Compartilhar ${fileItem.name}"))
                                                                        } catch (_: Exception) {
                                                                            Toast.makeText(context, "Não foi possível compartilhar o arquivo", Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    }
                                                                )
                                                                DropdownMenuItem(
                                                                    text = { Text("Backup", fontSize = 12.sp, color = TextPrimary) },
                                                                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp)) },
                                                                    onClick = {
                                                                        menuOpen = false
                                                                        fileForBackup = fileItem
                                                                        showBackupChoiceDialog = true
                                                                    }
                                                                )
                                                                DropdownMenuItem(
                                                                    text = { Text("Excluir", fontSize = 12.sp, color = StatusError) },
                                                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp)) },
                                                                    onClick = {
                                                                        menuOpen = false
                                                                        fileToDelete = fileItem
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { expandedCategories = expandedCategories - cat.name },
                                            modifier = Modifier.fillMaxWidth().height(32.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, DarkCardBorder),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Recolher", fontSize = 11.sp, color = TextSecondary)
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // 3) TOP 10 MAIORES APLICATIVOS (MANTIDO)
            // ==========================================
            Text(
                text = "TOP 10 MAIORES APLICATIVOS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (isLoadingApps && topApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = primaryAccent, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Calculando maiores aplicativos...", fontSize = 12.sp, color = TextMuted)
                            }
                        }
                    } else if (topApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Nenhum aplicativo listado ainda.", fontSize = 12.sp, color = TextMuted)
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(onClick = { viewModel.refreshInstalledApps(force = true) }) {
                                    Text("Atualizar Lista", color = primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        for ((index, app) in topApps.withIndex()) {
                            if (index > 0) {
                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp))
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToAppDetail(app) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (index < 3) primaryAccent.copy(alpha = 0.18f) else DarkBg,
                                    border = BorderStroke(1.dp, if (index < 3) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder),
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (index < 3) primaryAccent else TextMuted
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                RealAppIcon(
                                    packageName = app.packageName,
                                    size = 36.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = app.appName,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (app.isSystemApp) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = DarkBg,
                                                border = BorderStroke(0.5.dp, DarkCardBorder)
                                            ) {
                                                Text(
                                                    text = "Sistema",
                                                    fontSize = 9.sp,
                                                    color = TextMuted,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = app.packageName,
                                        fontSize = 10.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = FormatUtils.formatBytes(app.sizeBytes),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (index < 3) primaryAccent else TextPrimary
                                )
                            }
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
