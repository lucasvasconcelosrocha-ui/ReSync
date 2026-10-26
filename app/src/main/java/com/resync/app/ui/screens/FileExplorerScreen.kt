package com.resync.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.core.content.FileProvider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import com.resync.app.data.model.BackupDestinationType
import com.resync.app.data.model.BackupFilter
import com.resync.app.data.model.CloudProviderType
import com.resync.app.engine.CapabilityManager
import com.resync.app.engine.FileCategory
import com.resync.app.engine.GlobalFileScanner
import com.resync.app.engine.ScannedItem
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.components.CloudAccountPickerModal
import com.resync.app.ui.components.CompletionAnimation
import com.resync.app.ui.components.CompletionAnimationSize
import com.resync.app.ui.components.SmoothCurrentItemText
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.util.FormatUtils
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class FileSortOrder(val label: String) {
    NAME_ASC("Nome A → Z"),
    NAME_DESC("Nome Z → A"),
    SIZE_DESC("Maior tamanho"),
    SIZE_ASC("Menor tamanho"),
    DATE_DESC("Mais recente"),
    DATE_ASC("Mais antigo"),
    TYPE("Tipo de arquivo")
}

enum class DeletionState {
    IDLE,
    CONFIRMING_DELETE,
    DELETING,
    DELETE_SUCCESS,
    DELETE_ERROR
}

data class ExplorerItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val symlinkTarget: String? = null,
    val sizeBytes: Long = 0L,
    val permissions: String = "",
    val owner: String = "",
    val group: String = "",
    val lastModified: Long = 0L,
    val isHidden: Boolean = name.startsWith("."),
    val itemCount: Int = -1
) {
    val file: File get() = File(path)
}

fun File.toExplorerItem(): ExplorerItem = ExplorerItem(
    name = name,
    path = absolutePath,
    isDirectory = isDirectory,
    sizeBytes = if (isDirectory) 0L else length(),
    permissions = if (canRead()) (if (canWrite()) "rw-" else "r--") else "---",
    owner = "user",
    group = "user",
    lastModified = lastModified(),
    isHidden = name.startsWith(".")
)

data class ExplorerClipboard(
    val isMove: Boolean,
    val sourcePath: String,
    val itemName: String,
    val isDirectory: Boolean
)

fun isReadOnlyPartition(path: String): Boolean {
    return path == "/" ||
            path == "/system" || path.startsWith("/system/") ||
            path == "/vendor" || path.startsWith("/vendor/") ||
            path == "/product" || path.startsWith("/product/") ||
            path == "/system_ext" || path.startsWith("/system_ext/") ||
            path == "/odm" || path.startsWith("/odm/") ||
            path == "/apex" || path.startsWith("/apex/")
}

fun isSystemCriticalPath(path: String): Boolean {
    return path == "/" ||
            path.startsWith("/system") ||
            path.startsWith("/vendor") ||
            path.startsWith("/product") ||
            path.startsWith("/system_ext") ||
            path.startsWith("/odm") ||
            path.startsWith("/apex") ||
            path.startsWith("/data") ||
            path.startsWith("/cache") ||
            path.startsWith("/mnt")
}

fun parseLsOutputLine(rawLine: String, parentDirPath: String): ExplorerItem? {
    val line = rawLine.trim()
    if (line.isEmpty() || line.startsWith("total ")) return null

    val tokens = line.split(Regex("""\s+"""))
    if (tokens.size < 4) return null
    val perms = tokens[0]
    if (perms.length < 9) return null

    var dateStartIdx = -1
    var dateEndIdx = -1
    var dateStr = ""
    var timeStr = ""

    val months = setOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // Scan for date tokens starting after permissions
    for (i in 1 until tokens.size - 1) {
        val token = tokens[i]
        // Check ISO format: YYYY-MM-DD
        if (token.matches(Regex("""\d{4}-\d{2}-\d{2}""")) && i + 1 < tokens.size && (tokens[i + 1].matches(Regex("""\d{1,2}:\d{2}(:\d{2})?""")) || tokens[i + 1].matches(Regex("""\d{4}""")))) {
            dateStartIdx = i
            dateEndIdx = i + 1
            dateStr = token
            timeStr = tokens[i + 1]
            break
        }
        // Check Month DD format: Oct 20
        if (months.contains(token.lowercase(Locale.ROOT)) && i + 2 < tokens.size && tokens[i + 1].matches(Regex("""\d{1,2}""")) && (tokens[i + 2].matches(Regex("""\d{1,2}:\d{2}(:\d{2})?""")) || tokens[i + 2].matches(Regex("""\d{4}""")))) {
            dateStartIdx = i
            dateEndIdx = i + 2
            dateStr = "$token ${tokens[i + 1]}"
            timeStr = tokens[i + 2]
            break
        }
    }

    val sizeBytes: Long
    val rawName: String
    var owner = "root"
    var group = "root"

    if (dateStartIdx != -1 && dateStartIdx >= 2) {
        // Size is always the token immediately preceding the date
        sizeBytes = tokens[dateStartIdx - 1].toLongOrNull() ?: 0L
        rawName = tokens.drop(dateEndIdx + 1).joinToString(" ")
        if (dateStartIdx >= 3) {
            group = tokens[dateStartIdx - 2]
            owner = if (dateStartIdx >= 4) tokens[dateStartIdx - 3] else "root"
        }
    } else {
        // Fallback: search backwards for size token if date format was unusual
        var candidateSizeIdx = -1
        for (i in tokens.size - 2 downTo 1) {
            if (tokens[i].toLongOrNull() != null) {
                candidateSizeIdx = i
                break
            }
        }
        if (candidateSizeIdx != -1) {
            sizeBytes = tokens[candidateSizeIdx].toLongOrNull() ?: 0L
            rawName = tokens.drop(candidateSizeIdx + 1).joinToString(" ")
        } else {
            sizeBytes = 0L
            rawName = tokens.last()
        }
    }

    var cleanName = rawName.trim()
    var isSymlink = perms.startsWith("l")
    var symlinkTarget: String? = null
    if (cleanName.contains(" -> ")) {
        val parts = cleanName.split(" -> ", limit = 2)
        cleanName = parts[0].trim()
        symlinkTarget = parts[1].trim()
        isSymlink = true
    }
    if (cleanName.startsWith("'") && cleanName.endsWith("'") && cleanName.length >= 2) {
        cleanName = cleanName.substring(1, cleanName.length - 1)
    }

    if (cleanName.isEmpty() || cleanName == "." || cleanName == "..") return null

    val cleanParent = if (parentDirPath.endsWith("/")) parentDirPath else "$parentDirPath/"
    val itemPath = cleanParent + cleanName
    val isDir = perms.startsWith("d") || (isSymlink && (
        try { java.io.File(itemPath).isDirectory } catch (_: Throwable) { false } ||
        (symlinkTarget == null || !symlinkTarget.contains(".") || symlinkTarget.endsWith("/"))
    ))

    val lastModified = try {
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)
        if (dateStr.contains("-")) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$dateStr $timeStr")?.time ?: 0L
        } else if (timeStr.contains(":")) {
            val cal = Calendar.getInstance()
            val parsed = SimpleDateFormat("MMM d HH:mm", Locale.US).parse("$dateStr $timeStr")
            if (parsed != null) {
                cal.time = parsed
                cal.set(Calendar.YEAR, currentYear)
                if (cal.timeInMillis > System.currentTimeMillis() + 86400000L) {
                    cal.set(Calendar.YEAR, currentYear - 1)
                }
                cal.timeInMillis
            } else 0L
        } else {
            SimpleDateFormat("MMM d yyyy", Locale.US).parse("$dateStr $timeStr")?.time ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    return ExplorerItem(
        name = cleanName,
        path = itemPath,
        isDirectory = isDir,
        isSymlink = isSymlink,
        symlinkTarget = symlinkTarget,
        sizeBytes = sizeBytes,
        permissions = perms,
        owner = owner,
        group = group,
        lastModified = lastModified,
        isHidden = cleanName.startsWith(".")
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileExplorerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onStartBackupWithFiles: (List<String>, BackupFilter?) -> Unit,
    title: String = "Arquivos"
) {
    val context = LocalContext.current
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val savedFilters by viewModel.backupFilters.collectAsState()
    val activeMode by viewModel.activeMode.collectAsState()
    val isRootActive = activeMode == CapabilityManager.ExecutionMode.ROOT
    val coroutineScope = rememberCoroutineScope()

    // Navigation state
    val rootDir = remember { Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0") }
    var currentPath by remember { mutableStateOf(rootDir.absolutePath) }
    val currentDir = File(currentPath)
    var directoryRefreshTrigger by remember { mutableStateOf(0) }
    var isPullRefreshing by remember { mutableStateOf(false) }

    // Search and Filters
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(FileCategory.ALL) }
    var selectedSortOrder by remember { mutableStateOf(FileSortOrder.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showHiddenFiles by remember { mutableStateOf(false) }
    var showLocationsMenu by remember { mutableStateOf(false) }
    var showRemountDialog by remember { mutableStateOf(false) }

    // Multi-selection state & clipboard
    val selectedPaths = remember { mutableStateListOf<String>() }
    var clipboard by remember { mutableStateOf<ExplorerClipboard?>(null) }

    // Modals & Operations
    var showFilterDialog by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf<BackupFilter?>(null) }
    var itemToRename by remember { mutableStateOf<ExplorerItem?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<ExplorerItem?>(null) }
    var isReinforcedConfirmed by remember { mutableStateOf(false) }
    var deleteState by remember { mutableStateOf(DeletionState.IDLE) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    var itemForProperties by remember { mutableStateOf<ExplorerItem?>(null) }
    var fileForInfo by remember { mutableStateOf<File?>(null) }
    var showBackupConfirmDialog by remember { mutableStateOf(false) }

    // Direct Backup states (Bottom sheet choice and Progress modal)
    var backupItemTarget by remember { mutableStateOf<File?>(null) }
    var isMultiSelectBackupTarget by remember { mutableStateOf(false) }
    var isDirectBackupActive by remember { mutableStateOf(false) }
    var pendingCloudBackupTarget by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val hasAllFilesAccess = permissionsState.hasAllFilesAccess

    val lifecycleOwner = LocalLifecycleOwner.current

    // Automatically trigger directory refresh if storage permission becomes granted while in explorer
    LaunchedEffect(hasAllFilesAccess) {
        directoryRefreshTrigger++
    }

    // 1-second non-root polling if storage permission is not yet granted
    LaunchedEffect(lifecycleOwner, hasAllFilesAccess) {
        if (!hasAllFilesAccess) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (!viewModel.permissionsState.value.hasAllFilesAccess) {
                    viewModel.refreshAllPermissions()
                    kotlinx.coroutines.delay(1000L)
                }
            }
        }
    }

    // Category scanning states
    var isCategoryScanning by remember { mutableStateOf(false) }
    var categoryItems by remember { mutableStateOf<List<ScannedItem>>(emptyList()) }
    var showInstalledApks by remember { mutableStateOf(false) }

    // Support preselected category from Storage screen
    val preselectedCategory by viewModel.preselectedFileCategory.collectAsState()
    LaunchedEffect(preselectedCategory) {
        preselectedCategory?.let {
            selectedCategory = it
            viewModel.setPreselectedFileCategory(null)
        }
    }

    // Refresh category items when category changes or trigger increments
    LaunchedEffect(selectedCategory, directoryRefreshTrigger) {
        if (selectedCategory != FileCategory.ALL) {
            isCategoryScanning = true
            categoryItems = GlobalFileScanner.scanCategory(context, selectedCategory, isRootActive)
            isCategoryScanning = false
        }
    }

    // Real-time ContentObserver for category items
    DisposableEffect(selectedCategory) {
        if (selectedCategory != FileCategory.ALL) {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    coroutineScope.launch {
                        categoryItems = GlobalFileScanner.scanCategory(context, selectedCategory, isRootActive)
                    }
                }
            }
            val uri = when (selectedCategory) {
                FileCategory.IMAGES -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                FileCategory.VIDEOS -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                FileCategory.AUDIO -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> android.provider.MediaStore.Files.getContentUri("external")
            }
            try {
                context.contentResolver.registerContentObserver(uri, true, observer)
            } catch (_: Exception) {}
            onDispose {
                try {
                    context.contentResolver.unregisterContentObserver(observer)
                } catch (_: Exception) {}
            }
        } else {
            onDispose {}
        }
    }

    // Filter and Sort category items
    val filteredCategoryItems = remember(categoryItems, searchQuery, selectedSortOrder, showInstalledApks, selectedCategory) {
        categoryItems
            .filter { item ->
                if (selectedCategory == FileCategory.APK && !showInstalledApks && item.isInstalled) {
                    false
                } else if (searchQuery.isNotBlank()) {
                    item.name.contains(searchQuery.trim(), ignoreCase = true) ||
                            (item.packageName?.contains(searchQuery.trim(), ignoreCase = true) == true)
                } else {
                    true
                }
            }
            .sortedWith { i1, i2 ->
                when (selectedSortOrder) {
                    FileSortOrder.NAME_ASC -> i1.name.compareTo(i2.name, ignoreCase = true)
                    FileSortOrder.NAME_DESC -> i2.name.compareTo(i1.name, ignoreCase = true)
                    FileSortOrder.SIZE_DESC -> i2.sizeBytes.compareTo(i1.sizeBytes)
                    FileSortOrder.SIZE_ASC -> i1.sizeBytes.compareTo(i2.sizeBytes)
                    FileSortOrder.DATE_DESC -> i2.lastModified.compareTo(i1.lastModified)
                    FileSortOrder.DATE_ASC -> i1.lastModified.compareTo(i2.lastModified)
                    FileSortOrder.TYPE -> i1.file.extension.compareTo(i2.file.extension, ignoreCase = true)
                }
            }
    }

    // Android System Back Navigation Handler
    BackHandler(enabled = selectedCategory != FileCategory.ALL || currentPath != rootDir.absolutePath) {
        if (selectedCategory != FileCategory.ALL) {
            selectedCategory = FileCategory.ALL
        } else if (currentPath != rootDir.absolutePath) {
            val parent = File(currentPath).parentFile?.absolutePath ?: "/"
            if (currentPath == "/") {
                currentPath = rootDir.absolutePath
            } else {
                currentPath = parent
            }
        }
    }

    // Query and process items in directory with root support (libsu, ls -la)
    val directoryItems by produceState<List<ExplorerItem>>(
        initialValue = emptyList(),
        key1 = currentPath,
        key2 = Pair(directoryRefreshTrigger, showHiddenFiles),
        key3 = isRootActive
    ) {
        value = withContext(Dispatchers.IO) {
            val path = currentPath
            val isSystemPath = path == "/" || path.startsWith("/system") || path.startsWith("/data") ||
                    path.startsWith("/vendor") || path.startsWith("/product") || path.startsWith("/system_ext") ||
                    path.startsWith("/odm") || path.startsWith("/apex") || path.startsWith("/cache") ||
                    path.startsWith("/mnt") || path.startsWith("/storage/emulated/0/Android/data") ||
                    path.startsWith("/storage/emulated/0/Android/obb")

            val resultList = mutableListOf<ExplorerItem>()

            // 1. FAST PATH: Leitura direta instantânea via Java File API para caminhos normais
            if (!isSystemPath) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val files = f.listFiles()
                    if (files != null) {
                        for (file in files) {
                            if (!showHiddenFiles && file.name.startsWith(".")) continue
                            val isDir = file.isDirectory
                            val count = if (isDir) (file.list()?.size ?: 0) else -1
                            resultList.add(
                                ExplorerItem(
                                    name = file.name,
                                    path = file.absolutePath,
                                    isDirectory = isDir,
                                    sizeBytes = if (isDir) 0L else file.length(),
                                    permissions = if (isDir) "drwxr-xr-x" else "-rw-r--r--",
                                    owner = "user",
                                    group = "user",
                                    lastModified = file.lastModified(),
                                    isHidden = file.name.startsWith("."),
                                    itemCount = count
                                )
                            )
                        }
                    }
                }
            }

            // 2. ELEVATED FALLBACK: Usar root em partições de sistema ou caso a leitura direta venha vazia
            if (resultList.isEmpty() && (isRootActive || isSystemPath)) {
                try {
                    val shellResult = Shell.cmd("ls -la \"$path\"").exec()
                    if (shellResult.isSuccess && shellResult.out.isNotEmpty()) {
                        for (line in shellResult.out) {
                            val parsed = parseLsOutputLine(line, path)
                            if (parsed != null) {
                                if (!showHiddenFiles && parsed.isHidden) continue
                                resultList.add(parsed)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. Fallback adicional se shell falhou
            if (resultList.isEmpty()) {
                val f = File(path)
                val files = f.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (!showHiddenFiles && file.name.startsWith(".")) continue
                        val isDir = file.isDirectory
                        val count = if (isDir) (file.list()?.size ?: 0) else -1
                        resultList.add(
                            ExplorerItem(
                                name = file.name,
                                path = file.absolutePath,
                                isDirectory = isDir,
                                sizeBytes = if (isDir) 0L else file.length(),
                                permissions = if (isDir) "drwxr-xr-x" else "-rw-r--r--",
                                owner = "user",
                                group = "user",
                                lastModified = file.lastModified(),
                                isHidden = file.name.startsWith("."),
                                itemCount = count
                            )
                        )
                    }
                }
            }

            resultList
        }
    }

    // Filter and Sort directory items
    val filteredExplorerItems: List<ExplorerItem> = remember(directoryItems, searchQuery, selectedCategory, selectedSortOrder) {
        directoryItems
            .filter { item ->
                val matchesSearch = if (searchQuery.isBlank()) true else item.name.contains(searchQuery.trim(), ignoreCase = true)
                val matchesCategory = if (selectedCategory == FileCategory.ALL || item.isDirectory) {
                    true
                } else {
                    getFileCategory(item.file) == selectedCategory
                }
                matchesSearch && matchesCategory
            }
            .sortedWith { f1, f2 ->
                if (f1.isDirectory && !f2.isDirectory) return@sortedWith -1
                if (!f1.isDirectory && f2.isDirectory) return@sortedWith 1

                when (selectedSortOrder) {
                    FileSortOrder.NAME_ASC -> f1.name.compareTo(f2.name, ignoreCase = true)
                    FileSortOrder.NAME_DESC -> f2.name.compareTo(f1.name, ignoreCase = true)
                    FileSortOrder.SIZE_DESC -> f2.sizeBytes.compareTo(f1.sizeBytes)
                    FileSortOrder.SIZE_ASC -> f1.sizeBytes.compareTo(f2.sizeBytes)
                    FileSortOrder.DATE_DESC -> f2.lastModified.compareTo(f1.lastModified)
                    FileSortOrder.DATE_ASC -> f1.lastModified.compareTo(f2.lastModified)
                    FileSortOrder.TYPE -> f1.file.extension.compareTo(f2.file.extension, ignoreCase = true)
                }
            }
    }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (selectedCategory != FileCategory.ALL) {
                                selectedCategory = FileCategory.ALL
                            } else if (currentPath != rootDir.absolutePath) {
                                val parent = File(currentPath).parentFile?.absolutePath ?: "/"
                                if (currentPath == "/") {
                                    currentPath = rootDir.absolutePath
                                } else {
                                    currentPath = parent
                                }
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar",
                                tint = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = if (isRootActive) "● Acesso Root Autorizado" else "Acesso Padrão",
                                fontSize = 11.sp,
                                color = if (isRootActive) StatusSuccess else TextMuted,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        // Search Toggle
                        IconButton(onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Pesquisar",
                                tint = if (isSearchActive) primaryAccent else TextSecondary
                            )
                        }

                        // Sort Menu
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Ordenar",
                                    tint = TextSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                FileSortOrder.values().forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.label,
                                                fontWeight = if (selectedSortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selectedSortOrder == order) primaryAccent else TextPrimary
                                            )
                                        },
                                        onClick = {
                                            selectedSortOrder = order
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Preset Filter
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filtros",
                                tint = if (activeFilter != null) primaryAccent else TextSecondary
                            )
                        }
                    }
                }

                // Search Bar
                if (isSearchActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Pesquisar arquivos na pasta...", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = primaryAccent) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Category Chips Row (Horizontal Scrollable)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FileCategory.values().forEach { cat ->
                        val isSelected = selectedCategory == cat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cat.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) primaryAccent else TextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Location selector & Breadcrumb navigation bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Clickable breadcrumb row with dropdown arrow ▾
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showLocationsMenu = true }
                                    .background(DarkSurfaceVariant.copy(alpha = 0.5f))
                                    .border(1.dp, DarkCardBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (currentPath.startsWith(rootDir.absolutePath)) Icons.Default.Folder else Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = if (currentPath.startsWith(rootDir.absolutePath)) AccentSunsetOrange else StatusSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (currentPath.startsWith(rootDir.absolutePath)) "Armazenamento Interno" else "Raiz (/)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Selecionar local",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Dropdown menu of locations and root shortcuts
                            DropdownMenu(
                                expanded = showLocationsMenu,
                                onDismissRequest = { showLocationsMenu = false },
                                modifier = Modifier
                                    .background(DarkSurfaceVariant)
                                    .widthIn(min = 260.dp)
                            ) {
                                Text(
                                    text = "LOCAIS PRINCIPAIS",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryAccent,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text("Armazenamento Interno (/storage/emulated/0)", color = TextPrimary, fontSize = 12.5.sp) },
                                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = AccentSunsetOrange) },
                                    onClick = {
                                        currentPath = rootDir.absolutePath
                                        selectedCategory = FileCategory.ALL
                                        showLocationsMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Raiz do Sistema (/)", color = TextPrimary, fontSize = 12.5.sp) },
                                    leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = StatusSuccess) },
                                    onClick = {
                                        currentPath = "/"
                                        selectedCategory = FileCategory.ALL
                                        showLocationsMenu = false
                                    }
                                )

                                HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 4.dp))

                                Text(
                                    text = "ATALHOS DO SISTEMA (ROOT)",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryAccent,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )

                                val shortcuts = listOf(
                                    "/data" to "Dados do Usuário",
                                    "/data/data" to "Dados dos Aplicativos",
                                    "/data/app" to "APKs Instalados",
                                    "/system" to "Sistema Android",
                                    "/vendor" to "Drivers do Fabricante",
                                    "/product" to "Customizações de ROM",
                                    "/system_ext" to "Extensões do Sistema",
                                    "/odm" to "Configurações ODM",
                                    "/apex" to "Módulos APEX",
                                    "/cache" to "Cache do Sistema",
                                    "/mnt" to "Pontos de Montagem",
                                    "/storage" to "Dispositivos de Armazenamento"
                                )

                                shortcuts.forEach { (path, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(path, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text(label, color = TextMuted, fontSize = 10.sp)
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            currentPath = path
                                            selectedCategory = FileCategory.ALL
                                            showLocationsMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Breadcrumb clickable segments
                        val breadcrumbSegments = remember(currentPath) {
                            val relative = if (currentPath.startsWith(rootDir.absolutePath)) {
                                currentPath.removePrefix(rootDir.absolutePath).trimStart('/')
                            } else {
                                currentPath.trimStart('/')
                            }
                            if (relative.isEmpty()) emptyList() else relative.split('/')
                        }

                        breadcrumbSegments.forEachIndexed { index, segment ->
                            Text(
                                text = " > ",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            val segmentPath = if (currentPath.startsWith(rootDir.absolutePath)) {
                                rootDir.absolutePath + "/" + breadcrumbSegments.take(index + 1).joinToString("/")
                            } else {
                                "/" + breadcrumbSegments.take(index + 1).joinToString("/")
                            }
                            Text(
                                text = segment,
                                fontSize = 12.sp,
                                fontWeight = if (index == breadcrumbSegments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == breadcrumbSegments.lastIndex) TextPrimary else primaryAccent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { currentPath = segmentPath }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Right action buttons: Hidden Files toggle & Raiz shortcut button
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { showHiddenFiles = !showHiddenFiles },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (showHiddenFiles) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (showHiddenFiles) "Ocultar arquivos ocultos" else "Mostrar arquivos ocultos",
                                tint = if (showHiddenFiles) primaryAccent else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (currentPath == "/" || !currentPath.startsWith(rootDir.absolutePath)) StatusSuccess.copy(alpha = 0.2f) else DarkSurfaceVariant,
                            border = BorderStroke(1.dp, if (currentPath == "/" || !currentPath.startsWith(rootDir.absolutePath)) StatusSuccess else DarkCardBorder),
                            modifier = Modifier.clickable {
                                if (currentPath == "/") {
                                    currentPath = rootDir.absolutePath
                                } else {
                                    currentPath = "/"
                                }
                                selectedCategory = FileCategory.ALL
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = "Raiz",
                                    tint = if (currentPath == "/" || !currentPath.startsWith(rootDir.absolutePath)) StatusSuccess else TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Raiz",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentPath == "/" || !currentPath.startsWith(rootDir.absolutePath)) StatusSuccess else TextPrimary
                                )
                            }
                        }
                    }
                }

                // Read-Only partition banner with Remount RW action
                if (isReadOnlyPartition(currentPath)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StatusWarning.copy(alpha = 0.12f))
                            .border(1.dp, StatusWarning.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Partição Somente Leitura (RO)",
                                fontSize = 11.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        TextButton(
                            onClick = { showRemountDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("Remontar RW", fontSize = 11.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Persistent Clipboard Paste Bar
                if (clipboard != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DarkSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, primaryAccent)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (clipboard!!.isMove) Icons.Default.DriveFileMove else Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "${if (clipboard!!.isMove) "Mover" else "Copiar"}: ${clipboard!!.itemName}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Destino: ${File(currentPath).name.ifEmpty { currentPath }}",
                                        fontSize = 10.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        val clip = clipboard!!
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                if (clip.isMove) {
                                                    if (isRootActive || isSystemCriticalPath(currentPath) || isSystemCriticalPath(clip.sourcePath)) {
                                                        Shell.cmd("mv \"${clip.sourcePath}\" \"$currentPath/\"").exec()
                                                    } else {
                                                        File(clip.sourcePath).renameTo(File(currentPath, clip.itemName))
                                                    }
                                                } else {
                                                    if (isRootActive || isSystemCriticalPath(currentPath) || isSystemCriticalPath(clip.sourcePath)) {
                                                        Shell.cmd("cp -r \"${clip.sourcePath}\" \"$currentPath/\"").exec()
                                                    } else {
                                                        File(clip.sourcePath).copyRecursively(File(currentPath, clip.itemName), overwrite = true)
                                                    }
                                                }
                                            }
                                            directoryRefreshTrigger++
                                            Toast.makeText(context, if (clip.isMove) "Item movido com sucesso" else "Item copiado com sucesso", Toast.LENGTH_SHORT).show()
                                            clipboard = null
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(if (clipboard!!.isMove) "Mover aqui" else "Colar aqui", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = { clipboard = null },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Barra de seleção fixa no topo (abaixo dos filtros e caminho)
                val itemsAvailableForBar = if (selectedCategory == FileCategory.ALL) filteredExplorerItems.map { it.path } else filteredCategoryItems.map { it.file.absolutePath }
                val allSelectedForBar = itemsAvailableForBar.isNotEmpty() && selectedPaths.containsAll(itemsAvailableForBar)
                val hasItemsOrSelection = itemsAvailableForBar.isNotEmpty() || selectedPaths.isNotEmpty()
                if (hasItemsOrSelection) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        color = DarkSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (selectedPaths.isNotEmpty()) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder),
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Linha 1: TriStateCheckbox + contador à esquerda; botão "X" à direita se houver seleção
                            val totalBytes = selectedPaths.sumOf { File(it).length() }
                            val countInView = itemsAvailableForBar.count { selectedPaths.contains(it) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Checkbox tri-state + contador
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .clickable(
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (allSelectedForBar) {
                                                selectedPaths.removeAll(itemsAvailableForBar.toSet())
                                            } else {
                                                val toAdd = itemsAvailableForBar.filterNot { selectedPaths.contains(it) }
                                                selectedPaths.addAll(toAdd)
                                            }
                                        }
                                ) {
                                    val triState = when {
                                        allSelectedForBar && itemsAvailableForBar.isNotEmpty() -> ToggleableState.On
                                        countInView == 0 -> ToggleableState.Off
                                        else -> ToggleableState.Indeterminate
                                    }
                                    TriStateCheckbox(
                                        state = triState,
                                        onClick = null,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = primaryAccent,
                                            checkmarkColor = Color.Black,
                                            uncheckedColor = TextSecondary
                                        ),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            selectedPaths.isEmpty() -> "Nenhum selecionado"
                                            allSelectedForBar -> "${FormatUtils.formatItemCount(selectedPaths.size)} selecionados • ${FormatUtils.formatBytes(totalBytes)}"
                                            itemsAvailableForBar.size != selectedPaths.size -> "$countInView de ${itemsAvailableForBar.size} selecionados • ${FormatUtils.formatBytes(totalBytes)}"
                                            else -> "${FormatUtils.formatItemCount(selectedPaths.size)} selecionados • ${FormatUtils.formatBytes(totalBytes)}"
                                        },
                                        color = if (selectedPaths.isNotEmpty()) TextPrimary else TextSecondary,
                                        fontWeight = if (selectedPaths.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (selectedPaths.isNotEmpty()) {
                                    IconButton(
                                        onClick = { selectedPaths.clear() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Limpar seleção",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Linha 2: dois botões lado a lado: "Marcar todos (N)" e "Desmarcar todos"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val toAdd = itemsAvailableForBar.filterNot { selectedPaths.contains(it) }
                                        selectedPaths.addAll(toAdd)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = primaryAccent.copy(alpha = 0.08f)),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = "Marcar todos (${itemsAvailableForBar.size})",
                                        fontSize = 11.5.sp,
                                        color = primaryAccent,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        selectedPaths.removeAll(itemsAvailableForBar.toSet())
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, DarkCardBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurfaceVariant),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = "Desmarcar todos",
                                        fontSize = 11.5.sp,
                                        color = TextSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }

                            // Linha 2: ações em lote (apenas quando há itens selecionados)
                            if (selectedPaths.isNotEmpty()) {
                                // Linha 2: dois botões lado a lado, com a mesma largura: "Nuvem" e "Memória interna"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val pathsToBackup = selectedPaths.toList()
                                            val backupTitle = if (pathsToBackup.size == 1) {
                                                "Backup - ${File(pathsToBackup.first()).name}"
                                            } else {
                                                "Backup - ${pathsToBackup.size} Itens"
                                            }
                                            selectedPaths.clear()
                                            pendingCloudBackupTarget = Pair(backupTitle, pathsToBackup)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, AccentElectricCyan),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CloudUpload,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                            tint = AccentElectricCyan
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Nuvem",
                                            fontSize = 11.sp,
                                            color = AccentElectricCyan,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val pathsToBackup = selectedPaths.toList()
                                            selectedPaths.clear()
                                            isDirectBackupActive = true
                                            val backupTitle = if (pathsToBackup.size == 1) {
                                                "Backup - ${File(pathsToBackup.first()).name}"
                                            } else {
                                                "Backup - ${pathsToBackup.size} Itens"
                                            }
                                            viewModel.launchBackup(
                                                title = backupTitle,
                                                selectedApps = emptyList(),
                                                selectedFiles = pathsToBackup,
                                                includeSms = false,
                                                includeCalls = false,
                                                includeContacts = false,
                                                includeDns = false,
                                                includeWallpaper = false,
                                                targetCloudProvider = null
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Save,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Memória interna",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {}
    ) { innerPadding ->
        val backupProgressState by viewModel.backupProgress.collectAsStateWithLifecycle()
        val isBackupRunning = backupProgressState != null && !backupProgressState!!.isFinished
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                directoryRefreshTrigger++
                coroutineScope.launch {
                    kotlinx.coroutines.delay(600)
                    isPullRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = if (isBackupRunning) 96.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            // Permission Notice if All Files Access is missing on Android 11+
            if (!hasAllFilesAccess) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, StatusWarning, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Acesso Completo aos Arquivos", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Para listar e fazer backup de todos os arquivos do dispositivo, conceda a permissão especial do Android.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            context.startActivity(intent)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("CONCEDER ACESSO TOTAL", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Protected system directory notice (Android/data or Android/obb)
            val isCurrentDirRestricted = StorageRepository.isRestrictedSystemDir(currentDir)
            if (isCurrentDirRestricted) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (isRootActive) StatusSuccess else StatusWarning, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isRootActive) StatusSuccess.copy(alpha = 0.12f) else StatusWarning.copy(alpha = 0.12f)
                        )
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isRootActive) Icons.Default.Security else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isRootActive) StatusSuccess else StatusWarning,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRootActive)
                                    "Pasta do sistema protegida — Acesso Root ativo (listagem via libsu habilitada)."
                                else
                                    "Pasta protegida pelo Android (Android/data / obb). O acesso sem Root pode ser restrito pelo sistema.",
                                fontSize = 12.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            if (selectedCategory != FileCategory.ALL) {
                // Category Header Summary
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${selectedCategory.label}: ${FormatUtils.formatItemCount(filteredCategoryItems.size)}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = primaryAccent
                                )
                                Text(
                                    text = "Total: ${FormatUtils.formatBytes(filteredCategoryItems.sumOf { it.sizeBytes })}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }

                            if (selectedCategory == FileCategory.APK) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Mostrar também instalados",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    Switch(
                                        checked = showInstalledApks,
                                        onCheckedChange = { showInstalledApks = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = primaryAccent,
                                            checkedTrackColor = primaryAccent.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (isCategoryScanning) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = primaryAccent, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Buscando ${selectedCategory.label.lowercase()} no armazenamento...", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }

                if (filteredCategoryItems.isEmpty() && !isCategoryScanning) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Nenhum arquivo encontrado nesta categoria", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }

                items(filteredCategoryItems, key = { it.file.absolutePath }) { item ->
                    val file = item.file
                    val isSelected = selectedPaths.contains(file.absolutePath)
                    var showItemMenu by remember { mutableStateOf(false) }

                    if (selectedCategory == FileCategory.APK) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant.copy(alpha = 0.6f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            if (isSelected) selectedPaths.remove(file.absolutePath)
                                            else selectedPaths.add(file.absolutePath)
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = primaryAccent),
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    val iconBmp = remember(item.file.absolutePath) { drawableToImageBitmap(item.apkIcon) }
                                    if (iconBmp != null) {
                                        Image(
                                            bitmap = iconBmp,
                                            contentDescription = item.name,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(StatusSuccess.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Android, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(24.dp))
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.appName ?: item.name,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                fontSize = 13.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (item.isInstalled) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = if (item.isInstalled) "Instalado" else "Não instalado",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (item.isInstalled) StatusSuccess else StatusWarning,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = item.packageName ?: file.name,
                                            fontSize = 11.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "v${item.versionName ?: "1.0"} • ${FormatUtils.formatBytes(item.sizeBytes)}",
                                                fontSize = 11.sp,
                                                color = primaryAccent,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(text = " • ", fontSize = 11.sp, color = TextMuted)
                                            Text(
                                                text = formatFileDate(item.lastModified),
                                                fontSize = 10.5.sp,
                                                color = TextMuted,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            text = file.parentFile?.name ?: "Download",
                                            fontSize = 10.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Mais opções", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                    }

                                    DropdownMenu(
                                        expanded = showItemMenu,
                                        onDismissRequest = { showItemMenu = false },
                                        modifier = Modifier.background(DarkSurfaceVariant)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Instalar", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = StatusSuccess) },
                                            onClick = {
                                                showItemMenu = false
                                                installApk(context, file)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Compartilhar", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = AccentElectricCyan) },
                                            onClick = {
                                                showItemMenu = false
                                                shareFile(context, file)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Backup na nuvem", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentElectricCyan) },
                                            onClick = {
                                                showItemMenu = false
                                                pendingCloudBackupTarget = Pair("Backup - ${file.name}", listOf(file.absolutePath))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Backup na memória interna", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, tint = primaryAccent) },
                                            onClick = {
                                                showItemMenu = false
                                                isDirectBackupActive = true
                                                viewModel.launchBackup(
                                                    title = "Backup - ${file.name}",
                                                    selectedApps = emptyList(),
                                                    selectedFiles = listOf(file.absolutePath),
                                                    includeSms = false,
                                                    includeCalls = false,
                                                    includeContacts = false,
                                                    includeDns = false,
                                                    includeWallpaper = false,
                                                    targetCloudProvider = null
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Informações", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary) },
                                            onClick = {
                                                showItemMenu = false
                                                fileForInfo = file
                                            }
                                        )
                                        HorizontalDivider(color = DarkCardBorder)
                                        DropdownMenuItem(
                                            text = { Text("Apagar", color = StatusError) },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                            onClick = {
                                                showItemMenu = false
                                                itemToDelete = file.toExplorerItem()
                                                deleteErrorMessage = null
                                                deleteState = DeletionState.CONFIRMING_DELETE
                                            }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { installApk(context, file) },
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Instalar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { shareFile(context, file) },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, AccentElectricCyan),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentElectricCyan)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Compartilhar", fontSize = 11.sp, color = AccentElectricCyan, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            itemToDelete = file.toExplorerItem()
                                            deleteErrorMessage = null
                                            deleteState = DeletionState.CONFIRMING_DELETE
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Apagar", modifier = Modifier.size(14.dp), tint = StatusError)
                                    }
                                }
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    if (isSelected) primaryAccent else DarkCardBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .combinedClickable(
                                    onClick = {
                                        backupItemTarget = file
                                        isMultiSelectBackupTarget = false
                                    },
                                    onLongClick = {
                                        backupItemTarget = file
                                        isMultiSelectBackupTarget = false
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant.copy(alpha = 0.6f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        if (isSelected) selectedPaths.remove(file.absolutePath)
                                        else selectedPaths.add(file.absolutePath)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryAccent),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                FileThumbnailOrIcon(
                                    file = file,
                                    category = item.category,
                                    isDir = false,
                                    primaryAccent = primaryAccent
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = FormatUtils.formatBytes(item.sizeBytes),
                                            fontSize = 11.sp,
                                            color = primaryAccent,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(text = " • ", fontSize = 11.sp, color = TextMuted)
                                        Text(
                                            text = "📁 ${item.parentFolderName}",
                                            fontSize = 11.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(text = " • ", fontSize = 11.sp, color = TextMuted)
                                        Text(
                                            text = formatFileDate(item.lastModified),
                                            fontSize = 11.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Box {
                                    IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Mais opções", tint = TextSecondary, modifier = Modifier.size(20.dp))
                                    }

                                    DropdownMenu(
                                        expanded = showItemMenu,
                                        onDismissRequest = { showItemMenu = false },
                                        modifier = Modifier.background(DarkSurfaceVariant)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Abrir", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = primaryAccent) },
                                            onClick = {
                                                showItemMenu = false
                                                openFile(context, file)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Compartilhar", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = AccentElectricCyan) },
                                            onClick = {
                                                showItemMenu = false
                                                shareFile(context, file)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Backup na nuvem", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentElectricCyan) },
                                            onClick = {
                                                showItemMenu = false
                                                pendingCloudBackupTarget = Pair("Backup - ${file.name}", listOf(file.absolutePath))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Backup na memória interna", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, tint = primaryAccent) },
                                            onClick = {
                                                showItemMenu = false
                                                isDirectBackupActive = true
                                                viewModel.launchBackup(
                                                    title = "Backup - ${file.name}",
                                                    selectedApps = emptyList(),
                                                    selectedFiles = listOf(file.absolutePath),
                                                    includeSms = false,
                                                    includeCalls = false,
                                                    includeContacts = false,
                                                    includeDns = false,
                                                    includeWallpaper = false,
                                                    targetCloudProvider = null
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Renomear", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = AccentCyberPurple) },
                                            onClick = {
                                                showItemMenu = false
                                                itemToRename = file.toExplorerItem()
                                                renameInput = file.name
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Informações", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary) },
                                            onClick = {
                                                showItemMenu = false
                                                fileForInfo = file
                                            }
                                        )
                                        HorizontalDivider(color = DarkCardBorder)
                                        DropdownMenuItem(
                                            text = { Text("Excluir", color = StatusError) },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                            onClick = {
                                                showItemMenu = false
                                                itemToDelete = file.toExplorerItem()
                                                deleteErrorMessage = null
                                                deleteState = DeletionState.CONFIRMING_DELETE
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Quick Up Directory Item if inside subdirectory
                if (currentPath != rootDir.absolutePath && currentPath != "/") {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    val parent = File(currentPath).parentFile?.absolutePath ?: "/"
                                    currentPath = if (currentPath == "/") rootDir.absolutePath else parent
                                },
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(".. (Pasta anterior)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Empty state
                if (filteredExplorerItems.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Nenhum arquivo encontrado nesta pasta", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }

                // Files and Folders list (with real root metadata: size, permissions rwx, owner, date)
                items(items = filteredExplorerItems, key = { it.path }) { item ->
                    val isSelected = selectedPaths.contains(item.path)
                    val isDir = item.isDirectory
                    val category = getFileCategory(item.file)

                    var showItemMenu by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                if (isSelected) primaryAccent else DarkCardBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .combinedClickable(
                                onClick = {
                                    if (isDir || (item.isSymlink && (item.symlinkTarget?.startsWith("/") == true || !item.symlinkTarget.orEmpty().contains(".")))) {
                                        currentPath = item.path
                                    } else {
                                        backupItemTarget = item.file
                                        isMultiSelectBackupTarget = false
                                    }
                                },
                                onLongClick = {
                                    showItemMenu = true
                                }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant.copy(alpha = 0.6f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Multi-selection Checkbox for both Files and Folders
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (isSelected) selectedPaths.remove(item.path)
                                    else selectedPaths.add(item.path)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = primaryAccent),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))

                            // File Thumbnail or Category Material Icon
                            FileThumbnailOrIcon(
                                file = item.file,
                                category = category,
                                isDir = isDir,
                                primaryAccent = primaryAccent
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            // File details column: Name, size, permissions, owner, date
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val sizeText = if (isDir) {
                                        if (item.sizeBytes > 0) FormatUtils.formatBytes(item.sizeBytes) else "Pasta"
                                    } else {
                                        FormatUtils.formatBytes(item.sizeBytes)
                                    }

                                    Text(
                                        text = sizeText,
                                        fontSize = 10.5.sp,
                                        color = if (isDir) AccentSunsetOrange else primaryAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    if (item.permissions.isNotBlank()) {
                                        Text("•", fontSize = 10.sp, color = TextMuted)
                                        Text(
                                            text = item.permissions,
                                            fontSize = 10.5.sp,
                                            color = StatusSuccess.copy(alpha = 0.9f),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    if (item.owner.isNotBlank()) {
                                        Text("•", fontSize = 10.sp, color = TextMuted)
                                        Text(
                                            text = "${item.owner}${if (item.group.isNotBlank() && item.group != item.owner) ":${item.group}" else ""}",
                                            fontSize = 10.5.sp,
                                            color = AccentCyberPurple,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    if (item.lastModified > 0L) {
                                        Text("•", fontSize = 10.sp, color = TextMuted)
                                        Text(
                                            text = formatFileDate(item.lastModified),
                                            fontSize = 10.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (item.isSymlink && item.symlinkTarget != null) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "🔗 → ${item.symlinkTarget}",
                                        fontSize = 10.sp,
                                        color = AccentElectricCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Context menu for individual file operations
                            Box {
                                IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Mais opções", tint = TextSecondary, modifier = Modifier.size(20.dp))
                                }

                                DropdownMenu(
                                    expanded = showItemMenu,
                                    onDismissRequest = { showItemMenu = false },
                                    modifier = Modifier.background(DarkSurfaceVariant)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Abrir", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = primaryAccent) },
                                        onClick = {
                                            showItemMenu = false
                                            if (isDir || (item.isSymlink && (item.symlinkTarget?.startsWith("/") == true || !item.symlinkTarget.orEmpty().contains(".")))) {
                                                currentPath = item.path
                                            } else {
                                                if (item.path.startsWith("/storage/emulated/0")) {
                                                    openFile(context, item.file)
                                                } else {
                                                    coroutineScope.launch {
                                                        val previewDir = File(context.cacheDir, "previews").apply { mkdirs() }
                                                        val previewFile = File(previewDir, item.name)
                                                        withContext(Dispatchers.IO) {
                                                            Shell.cmd("cp \"${item.path}\" \"${previewFile.absolutePath}\"", "chmod 644 \"${previewFile.absolutePath}\"").exec()
                                                        }
                                                        if (previewFile.exists()) {
                                                            openFile(context, previewFile)
                                                        } else {
                                                            Toast.makeText(context, "Não foi possível extrair o arquivo para visualização", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Copiar", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = primaryAccent) },
                                        onClick = {
                                            showItemMenu = false
                                            clipboard = ExplorerClipboard(
                                                isMove = false,
                                                sourcePath = item.path,
                                                itemName = item.name,
                                                isDirectory = item.isDirectory
                                            )
                                            Toast.makeText(context, "Item copiado. Navegue até o destino e toque em 'Colar aqui'", Toast.LENGTH_SHORT).show()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Mover", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = AccentSunsetOrange) },
                                        onClick = {
                                            showItemMenu = false
                                            clipboard = ExplorerClipboard(
                                                isMove = true,
                                                sourcePath = item.path,
                                                itemName = item.name,
                                                isDirectory = item.isDirectory
                                            )
                                            Toast.makeText(context, "Item selecionado para mover. Navegue até o destino e toque em 'Mover aqui'", Toast.LENGTH_SHORT).show()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Renomear", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = AccentCyberPurple) },
                                        onClick = {
                                            showItemMenu = false
                                            itemToRename = item
                                            renameInput = item.name
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Copiar caminho", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = AccentNeonBlue) },
                                        onClick = {
                                            showItemMenu = false
                                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipMgr.setPrimaryClip(ClipData.newPlainText("Caminho", item.path))
                                            Toast.makeText(context, "Caminho copiado: ${item.path}", Toast.LENGTH_SHORT).show()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Propriedades", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary) },
                                        onClick = {
                                            showItemMenu = false
                                            itemForProperties = item
                                        }
                                    )

                                    if (!isDir) {
                                        DropdownMenuItem(
                                            text = { Text("Compartilhar", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = AccentElectricCyan) },
                                            onClick = {
                                                showItemMenu = false
                                                shareFile(context, item.file)
                                            }
                                        )
                                    }

                                    DropdownMenuItem(
                                        text = { Text("Backup na nuvem (Google Drive)", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentElectricCyan) },
                                        onClick = {
                                            showItemMenu = false
                                            pendingCloudBackupTarget = Pair("Backup - ${item.name}", listOf(item.path))
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Backup na memória interna", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, tint = primaryAccent) },
                                        onClick = {
                                            showItemMenu = false
                                            isDirectBackupActive = true
                                            viewModel.launchBackup(
                                                title = "Backup - ${item.name}",
                                                selectedApps = emptyList(),
                                                selectedFiles = listOf(item.path),
                                                includeSms = false,
                                                includeCalls = false,
                                                includeContacts = false,
                                                includeDns = false,
                                                includeWallpaper = false,
                                                targetCloudProvider = null
                                            )
                                        }
                                    )

                                    HorizontalDivider(color = DarkCardBorder)

                                    DropdownMenuItem(
                                        text = { Text("Excluir", color = StatusError) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                        onClick = {
                                            showItemMenu = false
                                            itemToDelete = item
                                            isReinforcedConfirmed = false
                                            deleteErrorMessage = null
                                            deleteState = DeletionState.CONFIRMING_DELETE
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(if (selectedPaths.isNotEmpty()) 100.dp else 48.dp))
            }
        }
    }
}

    // Modal: File Properties
    if (itemForProperties != null) {
        val item = itemForProperties!!
        AlertDialog(
            onDismissRequest = { itemForProperties = null },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Propriedades", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nome: ${item.name}", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                    Text("Caminho: ${item.path}", color = TextSecondary, fontSize = 12.sp)
                    Text(
                        "Tipo: ${if (item.isDirectory) "Diretório" else if (item.isSymlink) "Link simbólico (→ ${item.symlinkTarget})" else "Arquivo (${getFileCategory(item.file).label})"}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text("Tamanho: ${FormatUtils.formatBytes(item.sizeBytes)} (${item.sizeBytes} bytes)", color = TextSecondary, fontSize = 12.sp)
                    Text("Permissões: ${item.permissions.ifEmpty { "Padrão" }}", color = StatusSuccess, fontSize = 12.sp)
                    Text("Dono / Grupo: ${item.owner.ifEmpty { "root" }}:${item.group.ifEmpty { "root" }}", color = AccentCyberPurple, fontSize = 12.sp)
                    Text("Modificado em: ${formatFileDate(item.lastModified)}", color = TextSecondary, fontSize = 12.sp)
                    Text(
                        "Status da Partição: ${if (isReadOnlyPartition(item.path)) "🔒 Somente Leitura (RO)" else "🔓 Leitura e Escrita (RW)"}",
                        color = if (isReadOnlyPartition(item.path)) StatusWarning else StatusSuccess,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipMgr.setPrimaryClip(ClipData.newPlainText("Caminho", item.path))
                        Toast.makeText(context, "Caminho copiado: ${item.path}", Toast.LENGTH_SHORT).show()
                        itemForProperties = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Copiar Caminho")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemForProperties = null }) {
                    Text("Fechar", color = TextSecondary)
                }
            }
        )
    }

    // Modal: Remount Partition RW Dialog
    if (showRemountDialog) {
        AlertDialog(
            onDismissRequest = { showRemountDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remontar como Leitura e Escrita?", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Deseja tentar remontar a partição de sistema ($currentPath) como Leitura e Escrita (RW)?",
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Isso executará comandos de Superusuário (Root) via `mount -o remount,rw`. Em sistemas modernos com EROFS ou System-as-Root sem overlay, a partição física pode permanecer somente leitura a nível de kernel.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val target = if (currentPath == "/") "/" else currentPath
                            val result = withContext(Dispatchers.IO) {
                                Shell.cmd("mount -o remount,rw /", "mount -o remount,rw \"$target\"").exec()
                            }
                            if (result.isSuccess) {
                                Toast.makeText(context, "Partição remontada como Leitura/Escrita!", Toast.LENGTH_SHORT).show()
                                directoryRefreshTrigger++
                            } else {
                                Toast.makeText(context, "Não foi possível remontar a partição como RW", Toast.LENGTH_LONG).show()
                            }
                            showRemountDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("REMONTAR RW", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemountDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Modal: Rename File
    if (itemToRename != null) {
        val item = itemToRename!!
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            containerColor = DarkSurface,
            title = { Text("Renomear", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryAccent,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank() && renameInput != item.name) {
                            coroutineScope.launch {
                                val parent = File(item.path).parent ?: "/"
                                val targetPath = if (parent.endsWith("/")) parent + renameInput.trim() else "$parent/${renameInput.trim()}"
                                val success = withContext(Dispatchers.IO) {
                                    if (isRootActive || isSystemCriticalPath(item.path)) {
                                        Shell.cmd("mv \"${item.path}\" \"$targetPath\"").exec().isSuccess
                                    } else {
                                        item.file.renameTo(File(targetPath))
                                    }
                                }
                                if (success) {
                                    directoryRefreshTrigger++
                                    Toast.makeText(context, "Renomeado com sucesso", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Falha ao renomear arquivo", Toast.LENGTH_SHORT).show()
                                }
                                itemToRename = null
                            }
                        } else {
                            itemToRename = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Modal: Delete Confirm with DeletionState machine & recursive support & system protection
    if (itemToDelete != null && deleteState != DeletionState.IDLE) {
        val item = itemToDelete!!
        val isFolder = item.isDirectory
        val isCritical = isSystemCriticalPath(item.path)

        AlertDialog(
            onDismissRequest = {
                if (deleteState != DeletionState.DELETING) {
                    itemToDelete = null
                    isReinforcedConfirmed = false
                    deleteState = DeletionState.IDLE
                }
            },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCritical) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isCritical) "Exclusão em Área Crítica do Sistema" else "Excluir ${item.name}?",
                        fontWeight = FontWeight.Bold,
                        color = StatusError
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deleteState == DeletionState.DELETING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = StatusError,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = if (isFolder) "Excluindo pasta e conteúdos..." else "Excluindo arquivo...",
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        if (isCritical) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.12f)),
                                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "AVISO DE SEGURANÇA ROOT",
                                        fontWeight = FontWeight.Bold,
                                        color = StatusError,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Você está prestes a excluir um arquivo ou diretório do sistema Android (${item.path}). Isso pode corromper a ROM, impedir a inicialização (bootloop) ou desestabilizar aplicativos.",
                                        fontSize = 11.5.sp,
                                        color = TextPrimary
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { isReinforcedConfirmed = !isReinforcedConfirmed }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isReinforcedConfirmed,
                                    onCheckedChange = { isReinforcedConfirmed = it },
                                    colors = CheckboxDefaults.colors(checkedColor = StatusError)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Estou ciente do risco e desejo excluir mesmo assim.",
                                    fontSize = 11.5.sp,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Text(
                                text = "Esta ação não pode ser desfeita.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }

                        if (deleteState == DeletionState.DELETE_ERROR && !deleteErrorMessage.isNullOrBlank()) {
                            Text(
                                text = deleteErrorMessage ?: "Não foi possível excluir o item.",
                                color = StatusError,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (deleteState != DeletionState.DELETING) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                deleteState = DeletionState.DELETING
                                val success = withContext(Dispatchers.IO) {
                                    if (isRootActive || isCritical) {
                                        try {
                                            Shell.cmd("rm -rf \"${item.path}\"").exec().isSuccess
                                        } catch (_: Exception) {
                                            false
                                        }
                                    } else {
                                        if (isFolder) item.file.deleteRecursively() else item.file.delete()
                                    }
                                }

                                if (success) {
                                    selectedPaths.remove(item.path)
                                    try {
                                        android.media.MediaScannerConnection.scanFile(
                                            context,
                                            arrayOf(item.path),
                                            null,
                                            null
                                        )
                                    } catch (_: Exception) {}
                                    directoryRefreshTrigger++
                                    deleteState = DeletionState.DELETE_SUCCESS
                                    Toast.makeText(
                                        context,
                                        if (isFolder) "Pasta excluída com sucesso" else "Arquivo excluído com sucesso",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    itemToDelete = null
                                    isReinforcedConfirmed = false
                                    deleteState = DeletionState.IDLE
                                } else {
                                    deleteState = DeletionState.DELETE_ERROR
                                    deleteErrorMessage = "Falha ao excluir o item. O arquivo pode estar em partição somente leitura."
                                    Toast.makeText(context, "Falha ao excluir o item", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isCritical || isReinforcedConfirmed,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                    ) {
                        Text(if (isCritical) "EXCLUIR PERMANENTEMENTE" else "Excluir", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (deleteState != DeletionState.DELETING) {
                    TextButton(onClick = {
                        itemToDelete = null
                        isReinforcedConfirmed = false
                        deleteState = DeletionState.IDLE
                    }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                }
            }
        )
    }

    // Modal: Backup Confirm Dialog for Selected Files
    if (showBackupConfirmDialog) {
        val totalBytes = selectedPaths.sumOf { File(it).length() }
        AlertDialog(
            onDismissRequest = { showBackupConfirmDialog = false },
            containerColor = DarkSurface,
            title = { Text("Iniciar Backup de Arquivos", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selectedPaths.size} arquivos selecionados para backup.", color = TextSecondary, fontSize = 13.sp)
                    Text("Tamanho total estimado: ${formatFileSize(totalBytes)}", fontWeight = FontWeight.SemiBold, color = primaryAccent, fontSize = 13.sp)
                    Text("Os arquivos serão empacotados com verificação de integridade no container ReSync.", color = TextMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackupConfirmDialog = false
                        onStartBackupWithFiles(selectedPaths.toList(), activeFilter)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("INICIAR BACKUP")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupConfirmDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Filter Dialog Modal (Preset filters)
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            containerColor = DarkSurface,
            title = {
                Text("Filtros de Backup", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Selecione um filtro pré-configurado:", fontSize = 13.sp, color = TextSecondary)

                    for (filter in savedFilters) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (activeFilter?.id == filter.id) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    activeFilter = filter
                                    showFilterDialog = false
                                },
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(filter.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                if (filter.includedExtensions.isNotEmpty()) {
                                    Text("Extensões: ${filter.includedExtensions.joinToString(", ")}", fontSize = 11.sp, color = TextMuted)
                                }
                                if (filter.minSizeBytes > 0) {
                                    Text("Tamanho > ${filter.minSizeBytes / (1024 * 1024)} MB", fontSize = 11.sp, color = TextMuted)
                                }
                                Text("Exclui: ${filter.excludedPatterns.joinToString(", ")}", fontSize = 11.sp, color = StatusWarning)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    activeFilter = null
                    showFilterDialog = false
                }) {
                    Text("Limpar Filtros", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFilterDialog = false }) {
                    Text("Fechar", color = TextPrimary)
                }
            }
        )
    }

    // Modal: Bottom Sheet para Backup Direto (Item Único ou Selecionados)
    if (backupItemTarget != null || isMultiSelectBackupTarget) {
        val targetFile = backupItemTarget
        val isMulti = isMultiSelectBackupTarget
        val isFolder = targetFile?.isDirectory == true
        val titleText = when {
            isMulti -> "${selectedPaths.size} itens selecionados"
            isFolder -> "Pasta: ${targetFile?.name}"
            else -> "Arquivo: ${targetFile?.name}"
        }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = {
                backupItemTarget = null
                isMultiSelectBackupTarget = false
            },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.6f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isMulti) Icons.Default.FolderZip else if (isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = primaryAccent,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subText = if (isMulti) {
                            val totalBytes = selectedPaths.sumOf { File(it).length() }
                            "Tamanho estimado: ${formatFileSize(totalBytes)}"
                        } else {
                            if (isFolder) "${targetFile?.list()?.size ?: 0} itens" else formatFileSize(targetFile?.length() ?: 0L)
                        }
                        Text(text = subText, fontSize = 12.sp, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "SELECIONE O DESTINO DO BACKUP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryAccent
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Opção a: Backup na nuvem (Google Drive)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AccentElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .clickable {
                            val pathsToBackup = if (isMulti) selectedPaths.toList() else listOf(targetFile!!.absolutePath)
                            val backupTitle = if (isMulti) {
                                if (pathsToBackup.size == 1) "Backup - ${File(pathsToBackup.first()).name}" else "Backup - ${pathsToBackup.size} Itens"
                            } else {
                                "Backup - ${targetFile!!.name}"
                            }
                            backupItemTarget = null
                            isMultiSelectBackupTarget = false
                            if (isMulti) selectedPaths.clear()
                            pendingCloudBackupTarget = Pair(backupTitle, pathsToBackup)
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentElectricCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backup na nuvem (Google Drive)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp, maxLines = 1)
                            Text("Salva na pasta 'ReSync' e sincroniza na conta Google ativa", color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Opção b: Backup na memória interna
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
                        .clickable {
                            val pathsToBackup = if (isMulti) selectedPaths.toList() else listOf(targetFile!!.absolutePath)
                            val backupTitle = if (isMulti) {
                                if (pathsToBackup.size == 1) "Backup - ${File(pathsToBackup.first()).name}" else "Backup - ${pathsToBackup.size} Itens"
                            } else {
                                "Backup - ${targetFile!!.name}"
                            }
                            backupItemTarget = null
                            isMultiSelectBackupTarget = false
                            if (isMulti) selectedPaths.clear()
                            isDirectBackupActive = true
                            viewModel.launchBackup(
                                title = backupTitle,
                                selectedApps = emptyList(),
                                selectedFiles = pathsToBackup,
                                includeSms = false,
                                includeCalls = false,
                                includeContacts = false,
                                includeDns = false,
                                includeWallpaper = false,
                                targetCloudProvider = null
                            )
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(primaryAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backup na memória interna", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("Grava o arquivo .resync em ReSyncBackups na memória interna", color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Opção c: Excluir arquivo
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, StatusError.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable {
                            val f = targetFile ?: selectedPaths.firstOrNull()?.let { File(it) }
                            backupItemTarget = null
                            isMultiSelectBackupTarget = false
                            if (f != null) {
                                itemToDelete = f.toExplorerItem()
                                deleteErrorMessage = null
                                deleteState = DeletionState.CONFIRMING_DELETE
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(StatusError.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Excluir", fontWeight = FontWeight.Bold, color = StatusError, fontSize = 14.sp)
                            Text("Apagar permanentemente do armazenamento do dispositivo", color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Modal: Seletor de Conta do Google Drive para Envio à Nuvem
    if (pendingCloudBackupTarget != null) {
        val (cloudBackupTitle, cloudPathsToBackup) = pendingCloudBackupTarget!!
        CloudAccountPickerModal(
            viewModel = viewModel,
            onConfirm = { chosenEmail ->
                pendingCloudBackupTarget = null
                isDirectBackupActive = true
                viewModel.launchBackup(
                    title = cloudBackupTitle,
                    selectedApps = emptyList(),
                    selectedFiles = cloudPathsToBackup,
                    includeSms = false,
                    includeCalls = false,
                    includeContacts = false,
                    includeDns = false,
                    includeWallpaper = false,
                    targetCloudProvider = CloudProviderType.GOOGLE_DRIVE,
                    targetCloudAccountEmail = chosenEmail,
                    destinationLabel = if (!chosenEmail.isNullOrBlank()) "Drive • $chosenEmail" else "Google Drive",
                    destinationType = BackupDestinationType.SOMENTE_DRIVE
                )
            },
            onDismiss = {
                pendingCloudBackupTarget = null
            }
        )
    }

    // Modal: Diálogo de Progresso e Resultado do Backup Direto
    val currentBackupProgress by viewModel.backupProgress.collectAsState()
    if (isDirectBackupActive && currentBackupProgress != null) {
        val bp = currentBackupProgress!!
        AlertDialog(
            onDismissRequest = {
                if (bp.isFinished) {
                    isDirectBackupActive = false
                }
            },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bp.isFinished && bp.error == null) {
                        CompletionAnimation(
                            size = CompletionAnimationSize.STANDARD,
                            themeAccent = StatusSuccess,
                            playKey = "file_exp_backup"
                        )
                    } else {
                        Icon(
                            imageVector = if (!bp.isFinished) Icons.Default.Sync else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (!bp.isFinished) primaryAccent else StatusWarning,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (!bp.isFinished) "Executando Backup" else if (bp.error == null) "Backup Concluído" else "Backup Finalizado com Avisos",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!bp.isFinished) {
                        // Progresso Ativo
                        val displayStage = when {
                            bp.stage.contains("Google Drive", ignoreCase = true) || bp.stage.contains("Enviando", ignoreCase = true) ->
                                "Enviando ao Drive... ${(bp.progressPercent * 100).toInt()}%"
                            bp.stage.contains("Gravando", ignoreCase = true) || bp.stage.contains("Compactando", ignoreCase = true) ->
                                "Compactando..."
                            bp.stage.isNotBlank() -> bp.stage
                            else -> "Processando..."
                        }

                        Text(text = displayStage, fontWeight = FontWeight.SemiBold, color = primaryAccent, fontSize = 14.sp)
                        if (bp.currentItemName.isNotBlank()) {
                            SmoothCurrentItemText(
                                text = bp.currentItemName,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 2,
                                reservedHeight = 32.dp
                            )
                        }

                        SmoothLinearProgressBar(
                            progress = bp.progressPercent,
                            color = primaryAccent,
                            trackColor = DarkSurfaceVariant,
                            height = 8.dp,
                            shapeRadius = 4.dp
                        )
                    } else {
                        // Concluído / Resultado
                        if (bp.error != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = StatusError.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = bp.error ?: "", color = StatusError, fontSize = 12.sp)
                                }
                            }
                        } else {
                            Text(
                                text = "O backup foi empacotado no container .resync e salvo com sucesso.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }

                        // Resumo por item
                        if (bp.completedItems.isNotEmpty()) {
                            Text(
                                text = "Resumo por item (${bp.completedItems.count { it.success }}/${bp.completedItems.size} ok):",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                bp.completedItems.forEach { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(DarkSurfaceVariant)
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = item.name,
                                            fontSize = 12.sp,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (item.success) "✓ OK" else "✕ Falha",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.success) StatusSuccess else StatusError
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (bp.isFinished) {
                    Button(
                        onClick = { isDirectBackupActive = false },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Text("Fechar")
                    }
                }
            },
            dismissButton = {
                if (bp.isFinished) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val details = buildString {
                                appendLine("=== Relatório de Backup ReSync ===")
                                appendLine("Estágio: ${bp.stage}")
                                appendLine("Status: ${if (bp.error == null) "Sucesso" else "Avisos/Falha"}")
                                if (!bp.error.isNullOrBlank()) appendLine("Detalhes: ${bp.error}")
                                appendLine("Itens: ${bp.completedItems.size} (${bp.completedItems.count { it.success }} ok, ${bp.completedItems.count { !it.success }} falhas)")
                                appendLine()
                                appendLine("--- Resumo por Item ---")
                                bp.completedItems.forEach { itm ->
                                    val mark = if (itm.success) "[✓]" else "[✕]"
                                    val errDetail = if (!itm.success && !itm.errorMessage.isNullOrBlank()) " (${itm.errorMessage})" else ""
                                    appendLine("$mark ${itm.name}$errDetail")
                                }
                            }
                            clipboard.setPrimaryClip(ClipData.newPlainText("ReSync Backup", details))
                            Toast.makeText(context, "Detalhes copiados para a área de transferência", Toast.LENGTH_SHORT).show()
                        },
                        border = BorderStroke(1.dp, primaryAccent)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copiar detalhes", color = primaryAccent, fontSize = 12.sp)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (bp.isPaused) viewModel.resumeBackup() else viewModel.pauseBackup()
                            }
                        ) {
                            Text(
                                text = if (bp.isPaused) "Retomar" else "Pausar",
                                color = primaryAccent
                            )
                        }
                        TextButton(
                            onClick = {
                                viewModel.cancelBackup()
                            }
                        ) {
                            Text("Cancelar", color = StatusError)
                        }
                        TextButton(onClick = { isDirectBackupActive = false }) {
                            Text("Minimizar", color = TextMuted)
                        }
                    }
                }
            }
        )
    }
}

/**
 * Thumbnail or category icon renderer with sampled bitmap decoding for images
 */
@Composable
private fun FileThumbnailOrIcon(
    file: File,
    category: FileCategory,
    isDir: Boolean,
    primaryAccent: Color
) {
    if (isDir) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentSunsetOrange.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = AccentSunsetOrange, modifier = Modifier.size(24.dp))
        }
        return
    }

    // For images, load efficient thumbnail
    if (category == FileCategory.IMAGES) {
        val thumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = file.absolutePath) {
            value = withContext(Dispatchers.IO) {
                try {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                    val sampleSize = calculateInSampleSize(boundsOpts, 96, 96)
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeFile(file.absolutePath, decodeOpts)?.asImageBitmap()
                } catch (e: Throwable) {
                    null
                }
            }
        }

        if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap!!,
                contentDescription = file.name,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            return
        }
    }

    // Material category icon fallback
    val (icon, tint) = when (category) {
        FileCategory.IMAGES -> Pair(Icons.Default.Image, StatusSuccess)
        FileCategory.VIDEOS -> Pair(Icons.Default.Movie, AccentCyberPurple)
        FileCategory.AUDIO -> Pair(Icons.Default.MusicNote, AccentElectricCyan)
        FileCategory.DOCUMENTS -> Pair(Icons.Default.Description, AccentNeonBlue)
        FileCategory.ARCHIVES -> Pair(Icons.Default.FolderZip, StatusWarning)
        FileCategory.APK -> Pair(Icons.Default.Android, StatusSuccess)
        FileCategory.BACKUPS -> Pair(Icons.Default.Shield, primaryAccent)
        else -> Pair(Icons.Default.InsertDriveFile, TextSecondary)
    }

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun getFileCategory(file: File): FileCategory {
    return StorageRepository.getFileCategory(file)
}

private fun formatFileSize(bytes: Long): String {
    return StorageRepository.formatFileSize(bytes)
}

private fun formatFileDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Data desconhecida"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
    val sdfDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return when {
        diff in 0..(24 * 60 * 60 * 1000L) -> "Hoje, ${sdfTime.format(Date(timestamp))}"
        diff in 0..(48 * 60 * 60 * 1000L) -> "Ontem, ${sdfTime.format(Date(timestamp))}"
        else -> sdfDate.format(Date(timestamp))
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun openFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Abrir com"))
    } catch (e: Exception) {
        Toast.makeText(context, "Não foi possível abrir o arquivo: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar arquivo"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao compartilhar: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun installApk(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Não foi possível iniciar o instalador: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun drawableToImageBitmap(drawable: android.graphics.drawable.Drawable?): ImageBitmap? {
    if (drawable == null) return null
    return try {
        val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        bitmap.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}


