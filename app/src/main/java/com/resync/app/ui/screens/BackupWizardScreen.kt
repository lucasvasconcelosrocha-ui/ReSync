package com.resync.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import android.widget.Toast
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import android.os.FileObserver
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import com.resync.app.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import com.resync.app.data.model.*
import com.resync.app.data.preferences.QuickBackupPreferences
import com.resync.app.data.preferences.QuickBackupSelection
import android.provider.MediaStore
import com.resync.app.engine.CapabilityManager
import com.resync.app.engine.DeviceCategoryInspector
import com.resync.app.engine.StorageItem
import com.resync.app.engine.StorageRepository
import com.resync.app.util.FormatUtils
import com.resync.app.ui.components.AppSelectionListComponent
import com.resync.app.ui.components.CloudAccountPickerModal
import com.resync.app.ui.components.RealAppIcon
import com.resync.app.ui.components.StoragePickerModal
import com.resync.app.ui.components.StorageExplorerListComponent
import com.resync.app.ui.components.cards.InfoRow
import com.resync.app.ui.components.CompletionAnimation
import com.resync.app.ui.components.CompletionAnimationSize
import com.resync.app.ui.components.SmoothBatchCounterText
import com.resync.app.ui.components.SmoothCurrentItemText
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.ui.components.SmoothPercentText
import com.resync.app.ui.components.SmoothStageText
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AssistantStep(val stepNumber: Int, val title: String) {
    SELECTION(1, "O que você deseja fazer backup?"),
    DESTINATION(2, "Destino do Backup"),
    SECURITY(3, "Segurança e Compactação"),
    SUMMARY(4, "Resumo e Execução")
}

data class WizardMediaFolder(
    val name: String,
    val path: String,
    val icon: ImageVector,
    val itemCount: Int,
    val sizeBytes: Long,
    val isDirectory: Boolean = true,
    val isCalculating: Boolean = false,
    val typeLabel: String = "",
    val isRestricted: Boolean = false
)

data class FolderCalculationResult(
    val itemCount: Int,
    val sizeBytes: Long,
    val lastModified: Long
)

internal object StorageFolderSizeCache {
    val cache = java.util.concurrent.ConcurrentHashMap<String, FolderCalculationResult>()
}

private fun getFileOrFolderIcon(name: String, isDir: Boolean): ImageVector {
    if (isDir) {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower in listOf("dcim", "pictures", "screenshots") -> Icons.Default.PhotoLibrary
            lower in listOf("download", "downloads") -> Icons.Default.Download
            lower in listOf("documents", "documentos") -> Icons.Default.Description
            lower in listOf("movies", "video", "videos") -> Icons.Default.VideoLibrary
            lower in listOf("music", "musica", "músicas") -> Icons.Default.MusicNote
            lower in listOf("podcasts", "audiobooks") -> Icons.Default.Podcasts
            lower in listOf("notifications", "ringtones", "alarms", "recordings") -> Icons.Default.Notifications
            lower in listOf("android") -> Icons.Default.Android
            lower.contains("backup") || lower.contains("resync") || lower.contains("swift") -> Icons.Default.CloudSync
            else -> Icons.Default.Folder
        }
    } else {
        val lower = name.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")
        return when (ext) {
            "apk", "apks", "xapk" -> Icons.Default.Android
            "jpg", "jpeg", "png", "webp", "gif", "heic", "svg" -> Icons.Default.Image
            "mp4", "mkv", "avi", "webm", "mov", "3gp" -> Icons.Default.Movie
            "mp3", "m4a", "ogg", "wav", "flac", "opus" -> Icons.Default.AudioFile
            "zip", "tar", "gz", "7z", "rar", "bz2" -> Icons.Default.Archive
            "pdf", "doc", "docx", "txt", "json", "xml", "log" -> Icons.Default.Description
            "img", "bin", "iso" -> Icons.Default.Storage
            else -> Icons.Default.InsertDriveFile
        }
    }
}

@Composable
fun BackupWizardScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onStartBackup: () -> Unit = {},
    onNavigateToActive: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val preferencesManager = remember { QuickBackupPreferences(context) }
    val savedPreferences by preferencesManager.selectionFlow.collectAsState(initial = QuickBackupSelection())

    val apps by viewModel.installedApps.collectAsState()
    val showInternalSystemPackages by viewModel.showInternalSystemPackages.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var currentStep by remember { mutableStateOf(AssistantStep.SELECTION) }

    // ==========================================
    // SELECTION STATES (INITIALIZED WITH DEFAULTS)
    // ==========================================
    var includeApps by remember { mutableStateOf(true) }
    var includeBaseApk by remember { mutableStateOf(true) }
    var includeAppSplits by remember { mutableStateOf(true) }
    var includeAppData by remember { mutableStateOf(true) } // MARCADO POR PADRÃO
    var includeExternalData by remember { mutableStateOf(false) }
    var includeObb by remember { mutableStateOf(false) }
    var includeAppPermissions by remember { mutableStateOf(true) }

    var includeWifiNetwork by remember { mutableStateOf(true) }
    var includeDns by remember { mutableStateOf(true) }
    var includeSms by remember { mutableStateOf(true) }
    var includeCalls by remember { mutableStateOf(false) }
    var includeContacts by remember { mutableStateOf(true) }
    var includeBluetooth by remember { mutableStateOf(false) }
    var includeWallpaperHome by remember { mutableStateOf(true) }
    var includeWallpaperLock by remember { mutableStateOf(true) }

    var includeCustomFilesAndFolders by remember { mutableStateOf(true) }
    var includePhotos by remember { mutableStateOf(false) }
    var includeVideos by remember { mutableStateOf(false) }
    var includeDocuments by remember { mutableStateOf(false) }
    var includeMusic by remember { mutableStateOf(false) }
    var includeDownloads by remember { mutableStateOf(false) }
    var includeScreenshots by remember { mutableStateOf(false) }

    // Menus expandidos independentes com rememberSaveable
    var expandedWizardGroups by rememberSaveable { mutableStateOf(setOf("apps_selection")) }
    var storageSearchQuery by rememberSaveable { mutableStateOf("") }
    var storageSortBySize by rememberSaveable { mutableStateOf(false) }
    var storageScanTrigger by remember { mutableIntStateOf(0) }
    val appDataListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val systemListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val storageFilesListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        )
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else true
                if (granted != hasAllFilesAccess) {
                    hasAllFilesAccess = granted
                }
                storageScanTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Apps Selection
    val selectedAppPackages = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        if (apps.isEmpty()) {
            viewModel.refreshInstalledApps(force = false)
        }
    }

    LaunchedEffect(apps) {
        if (selectedAppPackages.isEmpty() && apps.isNotEmpty()) {
            val userApps = apps.filter { !it.isSystemApp }.take(5).map { it.packageName }
            selectedAppPackages.addAll(userApps)
        }
    }

    // Dados reais para "Dados dos aplicativos"
    var appDataFoldersCount by remember { mutableIntStateOf(0) }
    var apkBaseCount by remember { mutableIntStateOf(0) }
    var apkBaseTotalBytes by remember { mutableLongStateOf(0L) }
    var splitsCount by remember { mutableIntStateOf(0) }
    var splitsTotalBytes by remember { mutableLongStateOf(0L) }
    var internalDataCount by remember { mutableIntStateOf(0) }
    var internalDataTotalBytes by remember { mutableLongStateOf(0L) }
    var obbCount by remember { mutableIntStateOf(0) }
    var obbTotalBytes by remember { mutableLongStateOf(0L) }
    var permissionsCount by remember { mutableIntStateOf(0) }

    // Dados reais para "Sistema e Comunicação"
    var systemWifiCount by remember { mutableIntStateOf(0) }
    var systemDnsText by remember { mutableStateOf("Configurado") }
    var systemSmsCount by remember { mutableIntStateOf(0) }
    var systemCallsCount by remember { mutableIntStateOf(0) }
    var systemContactsCount by remember { mutableIntStateOf(0) }
    var systemBtCount by remember { mutableIntStateOf(0) }

    // Dados reais para "Arquivos e Mídia"
    var wizardMediaFolders by remember { mutableStateOf<List<WizardMediaFolder>>(emptyList()) }

    // Reativo: cálculo das pastas reais de aplicativos e seus componentes
    LaunchedEffect(selectedAppPackages.toList(), apps) {
        withContext(Dispatchers.IO) {
            val selectedSet = selectedAppPackages.toSet()
            val chosenApps = apps.filter { selectedSet.contains(it.packageName) }

            var totalRealFolders = 0
            var baseApks = 0
            var baseApkBytes = 0L
            var splits = 0
            var splitsBytes = 0L
            var intData = 0
            var intDataBytes = 0L
            var obb = 0
            var obbBytes = 0L
            var perms = 0

            val extStorage = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            val androidDataDir = File(extStorage, "Android/data")
            val androidObbDir = File(extStorage, "Android/obb")
            val androidMediaDir = File(extStorage, "Android/media")

            for (app in chosenApps) {
                val pkg = app.packageName
                val dData = File("/data/data/$pkg")
                if (dData.exists() || app.sizeBytes > 0) {
                    totalRealFolders++
                    intData++
                    intDataBytes += (app.sizeBytes * 0.45).toLong().coerceAtLeast(4096L)
                }

                val userDe = File("/data/user_de/0/$pkg")
                if (userDe.exists()) {
                    totalRealFolders++
                }

                val aData = File(androidDataDir, pkg)
                if (aData.exists()) {
                    totalRealFolders++
                }

                val aObb = File(androidObbDir, pkg)
                if (aObb.exists()) {
                    totalRealFolders++
                    obb++
                    obbBytes += (aObb.listFiles()?.sumOf { it.length() } ?: 0L).coerceAtLeast(1024L)
                }

                val aMedia = File(androidMediaDir, pkg)
                if (aMedia.exists()) {
                    totalRealFolders++
                }

                if (app.sourceDir.isNotBlank()) {
                    val baseFile = File(app.sourceDir)
                    if (baseFile.exists()) {
                        baseApks++
                        baseApkBytes += baseFile.length()
                        val splitFiles = baseFile.parentFile?.listFiles { f -> f.isFile && f.name.startsWith("split_") && f.name.endsWith(".apk") }
                        if (!splitFiles.isNullOrEmpty()) {
                            splits += splitFiles.size
                            splitsBytes += splitFiles.sumOf { it.length() }
                        }
                    } else {
                        baseApks++
                        baseApkBytes += (app.sizeBytes * 0.55).toLong().coerceAtLeast(5 * 1024 * 1024L)
                    }
                } else {
                    baseApks++
                    baseApkBytes += (app.sizeBytes * 0.55).toLong().coerceAtLeast(5 * 1024 * 1024L)
                }

                perms++
            }

            appDataFoldersCount = totalRealFolders
            apkBaseCount = baseApks
            apkBaseTotalBytes = baseApkBytes
            splitsCount = splits
            splitsTotalBytes = splitsBytes
            internalDataCount = intData
            internalDataTotalBytes = intDataBytes
            obbCount = obb
            obbTotalBytes = obbBytes
            permissionsCount = perms
        }
    }

    // Carregamento de quantidades reais do Sistema
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { systemWifiCount = DeviceCategoryInspector.getSavedWifiNetworks(context).size }
            runCatching {
                val dns = DeviceCategoryInspector.getDnsInfo(context)
                systemDnsText = if (dns.mode.isNotBlank()) dns.mode else "Automático"
            }
            runCatching { systemSmsCount = DeviceCategoryInspector.getSmsConversations(context).size }
            runCatching { systemCallsCount = DeviceCategoryInspector.getCallLogs(context).size }
            runCatching { systemContactsCount = DeviceCategoryInspector.getContacts(context).size }
            runCatching { systemBtCount = DeviceCategoryInspector.getBluetoothDevices(context).size }
        }
    }

    // Carregamento de conteúdo real de /storage/emulated/0
    LaunchedEffect(storageScanTrigger, hasAllFilesAccess) {
        withContext(Dispatchers.IO) {
            val rootStorage = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            val itemsMap = LinkedHashMap<String, File>()

            val directFiles = rootStorage.listFiles()
            if (directFiles != null) {
                for (f in directFiles) {
                    if (f.name != "." && f.name != "..") {
                        itemsMap[f.absolutePath] = f
                    }
                }
            }

            if (itemsMap.isEmpty() && com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                try {
                    val res = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                        com.topjohnwu.superuser.Shell.cmd("ls -1a /storage/emulated/0").exec()
                    }
                    if (res != null && res.isSuccess) {
                        for (name in res.out) {
                            val trimmed = name.trim()
                            if (trimmed.isNotBlank() && trimmed != "." && trimmed != "..") {
                                val f = File(rootStorage, trimmed)
                                if (!itemsMap.containsKey(f.absolutePath)) {
                                    itemsMap[f.absolutePath] = f
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            val initialList = mutableListOf<WizardMediaFolder>()

            for ((path, file) in itemsMap) {
                val isDir = try { file.isDirectory } catch (_: Throwable) { false }
                val name = file.name
                val icon = getFileOrFolderIcon(name, isDir)
                val isRestricted = isDir && name.equals("Android", ignoreCase = true) &&
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                        com.topjohnwu.superuser.Shell.isAppGrantedRoot() != true

                if (isDir) {
                    val quickCount = try { file.list()?.size ?: 0 } catch (_: Throwable) { 0 }
                    val cached = StorageFolderSizeCache.cache[path]
                    val size = cached?.sizeBytes ?: 0L
                    initialList.add(
                        WizardMediaFolder(
                            name = name,
                            path = path,
                            icon = icon,
                            itemCount = cached?.itemCount ?: quickCount,
                            sizeBytes = size,
                            isDirectory = true,
                            isCalculating = false,
                            typeLabel = if (quickCount == 1) "1 item" else "$quickCount itens",
                            isRestricted = isRestricted
                        )
                    )
                } else {
                    val len = try { file.length() } catch (_: Throwable) { 0L }
                    val ext = file.extension.uppercase(Locale.ROOT).ifBlank { "ARQUIVO" }
                    initialList.add(
                        WizardMediaFolder(
                            name = name,
                            path = path,
                            icon = icon,
                            itemCount = 1,
                            sizeBytes = len,
                            isDirectory = false,
                            isCalculating = false,
                            typeLabel = ext,
                            isRestricted = false
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                wizardMediaFolders = initialList
            }
        }
    }

    // FileObserver para atualizações em tempo real de /storage/emulated/0
    DisposableEffect(Unit) {
        var observer: FileObserver? = null
        try {
            val rootPath = "/storage/emulated/0"
            val events = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM
            observer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                object : FileObserver(File(rootPath), events) {
                    override fun onEvent(event: Int, path: String?) {
                        storageScanTrigger++
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(rootPath, events) {
                    override fun onEvent(event: Int, path: String?) {
                        storageScanTrigger++
                    }
                }
            }
            observer.startWatching()
        } catch (_: Throwable) {}

        onDispose {
            try { observer?.stopWatching() } catch (_: Throwable) {}
        }
    }

    // Storage browser state for Category 3
    var wizardStorageDir by remember { mutableStateOf(File("/storage/emulated/0")) }
    var wizardStorageSearch by remember { mutableStateOf("") }
    var wizardExplorerPath by rememberSaveable { mutableStateOf("/storage/emulated/0") }
    var wizardShowHiddenFiles by rememberSaveable { mutableStateOf(false) }
    var wizardCurrentViewItems by remember { mutableStateOf<List<ExplorerItem>>(emptyList()) }
    val activeMode by viewModel.activeMode.collectAsState()
    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val isRootActive = activeMode == CapabilityManager.ExecutionMode.ROOT || permissionsState.isRootGranted || com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true

    // Non-root App Data Warning & Confirmation Dialog
    var showNonRootConfirmationDialog by remember { mutableStateOf(false) }
    var pendingNonRootConfirmationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Modals & Choosers
    var showStoragePickerModal by remember { mutableStateOf(false) }
    var showFolderSourceChoiceDialog by remember { mutableStateOf(false) }
    var showAppPickerModal by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshAllPermissions()
        if (!isGranted) {
            Toast.makeText(context, "Permissão necessária para ler os dados deste item no backup.", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkAndRequestPermission(perm: String, itemLabel: String, onProceed: () -> Unit) {
        val status = ContextCompat.checkSelfPermission(context, perm)
        if (status == PackageManager.PERMISSION_GRANTED) {
            onProceed()
        } else {
            Toast.makeText(context, "Conceda permissão para incluir $itemLabel no backup.", Toast.LENGTH_SHORT).show()
            permissionLauncher.launch(perm)
            onProceed()
        }
    }

    // Restore from DataStore on initial load
    var isPreferencesInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(savedPreferences) {
        if (!isPreferencesInitialized) {
            isPreferencesInitialized = true
            includeApps = savedPreferences.includeApps
            includeBaseApk = savedPreferences.includeBaseApk
            includeAppSplits = savedPreferences.includeAppSplits
            includeAppData = savedPreferences.includeAppData
            includeExternalData = savedPreferences.includeExternalData
            includeObb = savedPreferences.includeObb
            includeAppPermissions = savedPreferences.includeAppPermissions

            includeWifiNetwork = savedPreferences.includeWifiNetwork
            includeDns = savedPreferences.includeDns
            includeSms = savedPreferences.includeSms
            includeCalls = savedPreferences.includeCalls
            includeContacts = savedPreferences.includeContacts
            includeBluetooth = savedPreferences.includeBluetooth
            includeWallpaperHome = savedPreferences.includeWallpaperHome
            includeWallpaperLock = savedPreferences.includeWallpaperLock

            includeCustomFilesAndFolders = savedPreferences.includeCustomFilesAndFolders
            includePhotos = savedPreferences.includePhotos
            includeVideos = savedPreferences.includeVideos
            includeDocuments = savedPreferences.includeDocuments
            includeMusic = savedPreferences.includeMusic
            includeDownloads = savedPreferences.includeDownloads
            includeScreenshots = savedPreferences.includeScreenshots
        }
    }

    // Persist to DataStore
    fun persistCurrentSelection() {
        coroutineScope.launch {
            preferencesManager.saveSelection(
                QuickBackupSelection(
                    includeApps = includeApps,
                    includeBaseApk = includeBaseApk,
                    includeAppSplits = includeAppSplits,
                    includeAppData = includeAppData,
                    includeExternalData = includeExternalData,
                    includeObb = includeObb,
                    includeAppPermissions = includeAppPermissions,

                    includeWifiNetwork = includeWifiNetwork,
                    includeDns = includeDns,
                    includeSms = includeSms,
                    includeCalls = includeCalls,
                    includeContacts = includeContacts,
                    includeBluetooth = includeBluetooth,
                    includeWallpaper = includeWallpaperHome || includeWallpaperLock,
                    includeWallpaperHome = includeWallpaperHome,
                    includeWallpaperLock = includeWallpaperLock,

                    includeCustomFilesAndFolders = includeCustomFilesAndFolders,
                    includePhotos = includePhotos,
                    includeVideos = includeVideos,
                    includeDocuments = includeDocuments,
                    includeMusic = includeMusic,
                    includeDownloads = includeDownloads,
                    includeScreenshots = includeScreenshots
                )
            )
        }
    }

    // SAF Launchers
    val openTreeFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("BackupWizard", "takePersistableUriPermission failed: ${e.message}")
            }
            val resolvedPath = StorageRepository.resolveUriToPath(context, uri)
            if (!resolvedPath.isNullOrBlank()) {
                viewModel.addQuickBackupSelectedPaths(listOf(resolvedPath))
                Toast.makeText(context, "Pasta adicionada: ${File(resolvedPath).name.ifBlank { resolvedPath }}", Toast.LENGTH_SHORT).show()
            }
            viewModel.refreshAllPermissions()
        }
    }

    val openFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            val paths = mutableListOf<String>()
            for (uri in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w("BackupWizard", "takePersistableUriPermission failed: ${e.message}")
                }
                val path = StorageRepository.resolveUriToPath(context, uri)
                if (!path.isNullOrBlank()) paths.add(path)
            }
            if (paths.isNotEmpty()) {
                viewModel.addQuickBackupSelectedPaths(paths)
                Toast.makeText(context, "${paths.size} item(ns) adicionado(s)!", Toast.LENGTH_SHORT).show()
            }
            viewModel.refreshAllPermissions()
        }
    }

    val quickBackupPaths by viewModel.quickBackupSelectedPaths.collectAsState()
    val availableRootPaths by viewModel.quickBackupRootAvailablePaths.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.loadQuickBackupAvailablePaths()
    }
    val quickBackupFolderSizes by viewModel.quickBackupFolderSizes.collectAsState()
    val isCalculatingStorageSize = viewModel.isQuickBackupCalculatingSizes()
    val quickBackupTotalBytes = viewModel.getQuickBackupTotalEstimatedBytes()

    // Runtime Permission Request State
    data class PermissionRequest(
        val permission: String,
        val title: String,
        val explanation: String,
        val onGranted: () -> Unit
    )
    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshAllPermissions()
        val req = pendingPermissionRequest
        if (isGranted) {
            req?.onGranted?.invoke()
            Toast.makeText(context, "Permissão concedida para ${req?.title ?: "o item"}.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissão necessária para incluir ${req?.title ?: "o item"} no backup.", Toast.LENGTH_LONG).show()
        }
        pendingPermissionRequest = null
    }

    val requestWithExplanation: (String, String, String, () -> Unit) -> Unit = { permission, title, explanation, onGranted ->
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingPermissionRequest = PermissionRequest(permission, title, explanation, onGranted)
        }
    }

    // Destination, Cloud & Execution State
    val multiAccounts by viewModel.multiCloudAccounts.collectAsStateWithLifecycle()
    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
    val backupExecutionState by viewModel.backupExecutionState.collectAsStateWithLifecycle()
    val backupSummary by viewModel.backupSummary.collectAsStateWithLifecycle()
    val isBackupPaused by viewModel.isBackupPaused.collectAsStateWithLifecycle()
    val selectedDriveEmailFromVm by viewModel.selectedDriveAccountEmail.collectAsStateWithLifecycle()

    var storageDestination by remember { mutableStateOf("Armazenamento Local") } // "Armazenamento Local", "Google Drive", "Ambos (Local + Google Drive)"
    var selectedDriveAccount by remember { mutableStateOf<String?>(null) }
    var pendingCloudBackupLauncher by remember { mutableStateOf<((String) -> Unit)?>(null) }

    LaunchedEffect(multiAccounts, selectedDriveEmailFromVm) {
        if (selectedDriveAccount == null) {
            selectedDriveAccount = selectedDriveEmailFromVm ?: multiAccounts.firstOrNull { it.isDefault }?.email ?: multiAccounts.firstOrNull()?.email
        }
    }

    var securityMode by remember { mutableStateOf("Sem criptografia") } // "Sem criptografia", "Criptografia com senha"
    var passwordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    var compressionMode by remember { mutableStateOf("Padrão (ZIP-MIXED)") } // "Padrão (ZIP-MIXED)", "Rápida (STORED)"

    // ==========================================
    // PARENT TRISTATE CALCULATIONS
    // ==========================================
    val appsSelectionParentState = when {
        apps.isNotEmpty() && selectedAppPackages.size == apps.size -> ToggleableState.On
        selectedAppPackages.isEmpty() -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    val appDataChildren = listOf(includeBaseApk, includeAppSplits, includeAppData, includeObb, includeAppPermissions)
    val appDataParentState = when {
        appDataChildren.all { it } -> ToggleableState.On
        appDataChildren.none { it } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    val systemChildren = listOf(includeWifiNetwork, includeDns, includeSms, includeCalls, includeContacts, includeBluetooth, includeWallpaperHome, includeWallpaperLock)
    val systemParentState = when {
        systemChildren.all { it } -> ToggleableState.On
        systemChildren.none { it } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    val selectedStorageCount = quickBackupPaths.size
    val currentViewPaths = remember(wizardCurrentViewItems) { wizardCurrentViewItems.map { it.path }.toSet() }
    val effectiveAvailablePaths = remember(wizardCurrentViewItems, availableRootPaths) {
        if (wizardCurrentViewItems.isNotEmpty()) {
            wizardCurrentViewItems.map { it.path }
        } else if (availableRootPaths.isNotEmpty()) {
            availableRootPaths
        } else {
            val rootDir = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            rootDir.listFiles()?.filter { !it.name.startsWith(".") }?.map { it.absolutePath }?.sorted() ?: emptyList()
        }
    }
    val filesParentState = when {
        quickBackupPaths.isEmpty() -> ToggleableState.Off
        effectiveAvailablePaths.isNotEmpty() && effectiveAvailablePaths.all { quickBackupPaths.contains(it) } -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

    // Total Items Selection Count & Selected Items Count
    val totalCount = 1 + appDataChildren.size + systemChildren.size + (if (quickBackupPaths.isNotEmpty()) 1 else 0)
    val selectedCount = (if (selectedAppPackages.isNotEmpty()) 1 else 0) +
            appDataChildren.count { it } +
            systemChildren.count { it } +
            (if (quickBackupPaths.isNotEmpty()) 1 else 0)

    // Dynamic Real-Time Size Estimation
    val estimatedBytes = remember(
        selectedAppPackages.size, includeBaseApk, includeAppSplits, includeAppData, includeObb,
        quickBackupPaths,
        apkBaseTotalBytes, splitsTotalBytes, internalDataTotalBytes, obbTotalBytes,
        includeSms, includeCalls, includeContacts, includeDns, includeWifiNetwork,
        includeWallpaperHome, includeWallpaperLock
    ) {
        var bytes = 0L
        if (includeBaseApk) bytes += apkBaseTotalBytes
        if (includeAppSplits) bytes += splitsTotalBytes
        if (includeAppData) bytes += internalDataTotalBytes
        if (includeObb) bytes += obbTotalBytes

        for (p in quickBackupPaths) {
            val cachedSize = StorageRepository.getCachedFolderSize(p)
            if (cachedSize != null) {
                bytes += cachedSize
            } else {
                val f = File(p)
                if (f.isFile) {
                    bytes += f.length()
                } else {
                    bytes += 10_000_000L
                }
            }
        }

        if (includeSms) bytes += (systemSmsCount * 1024L).coerceAtLeast(512L)
        if (includeCalls) bytes += (systemCallsCount * 256L).coerceAtLeast(512L)
        if (includeContacts) bytes += (systemContactsCount * 512L).coerceAtLeast(1024L)
        if (includeDns) bytes += 4096L
        if (includeWifiNetwork) bytes += (systemWifiCount * 1024L).coerceAtLeast(2048L)
        if (includeWallpaperHome) bytes += 4 * 1024 * 1024L
        if (includeWallpaperLock) bytes += 4 * 1024 * 1024L

        bytes
    }

    val estimatedSizeDisplay = remember(estimatedBytes) {
        FormatUtils.formatBytes(estimatedBytes)
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = {
                        if (currentStep.stepNumber > 1) {
                            currentStep = AssistantStep.values()[currentStep.stepNumber - 2]
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Backup Rápido (${currentStep.stepNumber}/${AssistantStep.values().size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isRootActive) StatusSuccess else StatusWarning)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isRootActive) "Modo: Root (Superusuário)" else "Modo: Sem root",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isRootActive) StatusSuccess else StatusWarning
                            )
                        }
                    }

                    TextButton(onClick = onBack) {
                        Text("Fechar", color = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Progress Indicator
                val topProgress = currentStep.stepNumber.toFloat() / AssistantStep.values().size.toFloat()

                LinearProgressIndicator(
                    progress = { topProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = primaryAccent,
                    trackColor = DarkSurfaceVariant
                )
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                com.resync.app.ui.components.ActiveOperationsBottomBanner(
                    viewModel = viewModel,
                    primaryAccent = primaryAccent,
                    onNavigateToActive = onNavigateToActive,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = StandardCardBg,
                        border = BorderStroke(1.dp, StandardCardBorder),
                        shadowElevation = 8.dp
                    ) {
                        val hasBack = currentStep.stepNumber > 1
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasBack) {
                                OutlinedButton(
                                    onClick = {
                                        currentStep = AssistantStep.values()[currentStep.stepNumber - 2]
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = StandardCardShape,
                                    border = BorderStroke(1.dp, StandardCardBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = StandardCardBg,
                                        contentColor = TextPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "VOLTAR",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            if (currentStep != AssistantStep.SUMMARY) {
                                val canContinue = if (currentStep == AssistantStep.SELECTION) selectedCount > 0 else true
                                Button(
                                    onClick = {
                                        currentStep = AssistantStep.values()[currentStep.stepNumber]
                                    },
                                    enabled = canContinue,
                                    modifier = (if (hasBack) Modifier.weight(1f) else Modifier.fillMaxWidth()).height(48.dp),
                                    shape = StandardCardShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryAccent,
                                        contentColor = Color.White,
                                        disabledContainerColor = primaryAccent.copy(alpha = 0.25f),
                                        disabledContentColor = TextMuted
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = "CONTINUAR",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (canContinue) Color.White else TextMuted
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (securityMode == "Criptografia com senha" && passwordInput.isBlank()) {
                                            Toast.makeText(context, "Digite uma senha para a criptografia ou selecione Sem Criptografia.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val selectedAppsList = if (includeApps && appDataChildren.any { it }) {
                                                apps.filter { selectedAppPackages.contains(it.packageName) }
                                                    .map {
                                                        Pair(
                                                            it,
                                                            AppSelectionOptions(
                                                                includeBaseApk = includeBaseApk,
                                                                includeSplits = includeAppSplits,
                                                                includeData = includeAppData,
                                                                includeInternalData = includeAppData,
                                                                includeExternalData = includeExternalData,
                                                                includeObb = includeObb,
                                                                includePermissions = includeAppPermissions
                                                            )
                                                        )
                                                    }
                                            } else emptyList()

                                            val filesList = mutableListOf<String>()
                                            if (includeCustomFilesAndFolders) {
                                                filesList.addAll(quickBackupPaths)
                                            }

                                            val deduplicatedFiles = StorageRepository.normalizeAndDeduplicatePaths(filesList)
                                            val backupDestType = when (storageDestination) {
                                                "Google Drive" -> BackupDestinationType.SOMENTE_DRIVE
                                                "Ambos (Local + Google Drive)" -> BackupDestinationType.INTERNA_E_DRIVE
                                                else -> BackupDestinationType.SOMENTE_INTERNA
                                            }
                                            val targetProvider = if (backupDestType != BackupDestinationType.SOMENTE_INTERNA) CloudProviderType.GOOGLE_DRIVE else null

                                            val startBackupJob = { accountEmail: String? ->
                                                viewModel.launchBackup(
                                                    title = "Backup Rápido ReSync",
                                                    selectedApps = selectedAppsList,
                                                    selectedFiles = deduplicatedFiles,
                                                    includeSms = includeSms,
                                                    includeCalls = includeCalls,
                                                    includeContacts = includeContacts,
                                                    includeDns = includeDns,
                                                    includeWallpaper = includeWallpaperHome || includeWallpaperLock,
                                                    includeWallpaperHome = includeWallpaperHome,
                                                    includeWallpaperLock = includeWallpaperLock,
                                                    includeWifi = includeWifiNetwork,
                                                    includeBluetooth = includeBluetooth,
                                                    encryptionPassword = if (securityMode != "Sem criptografia") passwordInput.ifEmpty { "ReSyncSecuredKey" } else null,
                                                    targetCloudProvider = targetProvider,
                                                    targetCloudAccountEmail = accountEmail,
                                                    destinationType = backupDestType
                                                )
                                                onStartBackup()
                                            }

                                            val proceedWithLaunch = {
                                                if (targetProvider != null) {
                                                    pendingCloudBackupLauncher = { chosenEmail ->
                                                        selectedDriveAccount = chosenEmail
                                                        startBackupJob(chosenEmail)
                                                    }
                                                } else {
                                                    startBackupJob(null)
                                                }
                                            }

                                            if (!isRootActive && includeAppData) {
                                                pendingNonRootConfirmationAction = proceedWithLaunch
                                                showNonRootConfirmationDialog = true
                                            } else {
                                                proceedWithLaunch()
                                            }
                                        }
                                    },
                                    modifier = (if (hasBack) Modifier.weight(1f) else Modifier.fillMaxWidth()).height(48.dp),
                                    shape = StandardCardShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryAccent,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "INICIAR BACKUP",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = 16.dp)
        ) {
            when (currentStep) {
                AssistantStep.SELECTION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Header Titles
                        Column {
                            Text(
                                text = "O que você deseja fazer backup?",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Selecione as categorias e componentes que serão incluídos no seu arquivo de cofre.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }

                        // Top Counter & Estimative Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Checklist, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "$selectedCount de $totalCount itens selecionados",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Estimativa: ${estimatedSizeDisplay.replace('.', ',')}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedCount > 0) StatusSuccess else TextMuted
                                )
                            }
                        }

                        // Quick Select / Clear Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    includeApps = true
                                    includeBaseApk = true
                                    includeAppSplits = true
                                    includeAppData = true
                                    includeExternalData = true
                                    includeObb = true
                                    includeAppPermissions = true

                                    includeWifiNetwork = true
                                    includeDns = true
                                    includeSms = true
                                    includeCalls = true
                                    includeContacts = true
                                    includeBluetooth = true
                                    includeWallpaperHome = true
                                    includeWallpaperLock = true

                                    includeCustomFilesAndFolders = true
                                    viewModel.addQuickBackupSelectedPaths(wizardMediaFolders.map { it.path })
                                    persistCurrentSelection()
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = StandardCardShape,
                                border = BorderStroke(1.dp, StandardCardBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = StandardCardBg,
                                    contentColor = primaryAccent
                                )
                            ) {
                                Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SELECIONAR TUDO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                            }

                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    includeApps = false
                                    includeBaseApk = false
                                    includeAppSplits = false
                                    includeAppData = false
                                    includeExternalData = false
                                    includeObb = false
                                    includeAppPermissions = false

                                    includeWifiNetwork = false
                                    includeDns = false
                                    includeSms = false
                                    includeCalls = false
                                    includeContacts = false
                                    includeBluetooth = false
                                    includeWallpaperHome = false
                                    includeWallpaperLock = false

                                    includeCustomFilesAndFolders = false
                                    viewModel.clearQuickBackupSelectedPaths()
                                    persistCurrentSelection()
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = StandardCardShape,
                                border = BorderStroke(1.dp, StandardCardBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = StandardCardBg,
                                    contentColor = StatusError
                                )
                            ) {
                                Icon(Icons.Default.Deselect, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusError)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("LIMPAR SELEÇÃO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusError)
                            }
                        }

                        // ==========================================
                        // GRUPO 1: APLICATIVOS SELECIONADOS
                        // ==========================================
                        CategorySelectionCard(
                            icon = Icons.Default.Apps,
                            iconTint = primaryAccent,
                            title = "Aplicativos selecionados",
                            subtitle = "${selectedAppPackages.size} de ${apps.size} selecionados • Escolha quais aplicativos farão parte do backup",
                            parentState = appsSelectionParentState,
                            onParentToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val newState = appsSelectionParentState != ToggleableState.On
                                if (newState) {
                                    selectedAppPackages.clear()
                                    selectedAppPackages.addAll(apps.map { it.packageName })
                                } else {
                                    selectedAppPackages.clear()
                                }
                                includeApps = selectedAppPackages.isNotEmpty()
                                persistCurrentSelection()
                            },
                            isExpanded = expandedWizardGroups.contains("apps_selection"),
                            onToggleExpand = {
                                expandedWizardGroups = if (expandedWizardGroups.contains("apps_selection")) {
                                    expandedWizardGroups - "apps_selection"
                                } else {
                                    expandedWizardGroups + "apps_selection"
                                }
                            }
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AppSelectionListComponent(
                                    apps = apps,
                                    selectedPackages = selectedAppPackages.toSet(),
                                    onTogglePackage = { pkg ->
                                        if (selectedAppPackages.contains(pkg)) {
                                            selectedAppPackages.remove(pkg)
                                        } else {
                                            selectedAppPackages.add(pkg)
                                        }
                                        includeApps = selectedAppPackages.isNotEmpty()
                                        persistCurrentSelection()
                                    },
                                    onSelectAll = { pkgs ->
                                        selectedAppPackages.addAll(pkgs)
                                        includeApps = true
                                        persistCurrentSelection()
                                    },
                                    onDeselectAll = { pkgs ->
                                        selectedAppPackages.removeAll(pkgs)
                                        includeApps = selectedAppPackages.isNotEmpty()
                                        persistCurrentSelection()
                                    },
                                    showInternalSystemPackages = showInternalSystemPackages,
                                    primaryAccent = primaryAccent
                                )
                            }
                        }

                        // ==========================================
                        // GRUPO 2: DADOS DOS APLICATIVOS
                        // ==========================================
                        CategorySelectionCard(
                            icon = Icons.Default.FolderShared,
                            iconTint = AccentElectricCyan,
                            title = "Dados dos aplicativos",
                            subtitle = "$appDataFoldersCount ${if (appDataFoldersCount == 1) "pasta" else "pastas"} • APK base, Splits, Dados internos, OBB e Permissões",
                            parentState = appDataParentState,
                            onParentToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val newState = appDataParentState != ToggleableState.On
                                includeBaseApk = newState
                                includeAppSplits = newState
                                includeAppData = newState
                                includeObb = newState
                                includeAppPermissions = newState
                                includeApps = newState && selectedAppPackages.isNotEmpty()
                                persistCurrentSelection()
                            },
                            isExpanded = expandedWizardGroups.contains("app_data"),
                            onToggleExpand = {
                                expandedWizardGroups = if (expandedWizardGroups.contains("app_data")) {
                                    expandedWizardGroups - "app_data"
                                } else {
                                    expandedWizardGroups + "app_data"
                                }
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                            ) {
                                LazyColumn(
                                    state = appDataListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Android,
                                            label = "APK base",
                                            description = "Binário do aplicativo instalável • $apkBaseCount apps (${FormatUtils.formatBytes(apkBaseTotalBytes)})",
                                            checked = includeBaseApk,
                                            onCheckedChange = {
                                                includeBaseApk = it
                                                includeApps = it || includeAppSplits || includeAppData || includeObb || includeAppPermissions
                                                persistCurrentSelection()
                                            },
                                            accent = AccentElectricCyan,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.PieChart,
                                            label = "Splits",
                                            description = "Módulos de densidade e ABI • $splitsCount módulos (${FormatUtils.formatBytes(splitsTotalBytes)})",
                                            checked = includeAppSplits,
                                            onCheckedChange = {
                                                includeAppSplits = it
                                                includeApps = includeBaseApk || it || includeAppData || includeObb || includeAppPermissions
                                                persistCurrentSelection()
                                            },
                                            accent = AccentElectricCyan,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Storage,
                                            label = if (isRootActive) "Dados internos" else "Dados internos (Requer Root)",
                                            description = if (isRootActive) {
                                                "Bancos de dados e preferências (/data/data) • $internalDataCount pastas (${FormatUtils.formatBytes(internalDataTotalBytes)})"
                                            } else {
                                                "Requer Root. Em modo sem root, apenas APKs serão copiados • $internalDataCount pastas"
                                            },
                                            checked = includeAppData,
                                            onCheckedChange = {
                                                includeAppData = it
                                                includeApps = includeBaseApk || includeAppSplits || it || includeObb || includeAppPermissions
                                                persistCurrentSelection()
                                            },
                                            accent = AccentElectricCyan,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.SportsEsports,
                                            label = "OBB",
                                            description = "Pacotes de dados e mídias pesadas (Android/obb) • $obbCount pastas (${FormatUtils.formatBytes(obbTotalBytes)})",
                                            checked = includeObb,
                                            onCheckedChange = {
                                                includeObb = it
                                                includeApps = includeBaseApk || includeAppSplits || includeAppData || it || includeAppPermissions
                                                persistCurrentSelection()
                                            },
                                            accent = AccentElectricCyan,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Security,
                                            label = "Permissões",
                                            description = "Privilégios concedidos pelo usuário • $permissionsCount apps",
                                            checked = includeAppPermissions,
                                            onCheckedChange = {
                                                includeAppPermissions = it
                                                includeApps = includeBaseApk || includeAppSplits || includeAppData || includeObb || it
                                                persistCurrentSelection()
                                            },
                                            accent = AccentElectricCyan,
                                            haptic = haptic
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // GRUPO 3: SISTEMA E COMUNICAÇÃO
                        // ==========================================
                        CategorySelectionCard(
                            icon = Icons.Default.SettingsCell,
                            iconTint = AccentNeonPurple,
                            title = "Sistema e Comunicação",
                            subtitle = "Wi-Fi, DNS, SMS, Chamadas, Contatos, Bluetooth e Papel de parede",
                            parentState = systemParentState,
                            onParentToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val newState = systemParentState != ToggleableState.On
                                includeWifiNetwork = newState
                                includeDns = newState
                                includeSms = newState
                                includeCalls = newState
                                includeContacts = newState
                                includeBluetooth = newState
                                includeWallpaperHome = newState
                                includeWallpaperLock = newState
                                persistCurrentSelection()
                            },
                            isExpanded = expandedWizardGroups.contains("system"),
                            onToggleExpand = {
                                expandedWizardGroups = if (expandedWizardGroups.contains("system")) {
                                    expandedWizardGroups - "system"
                                } else {
                                    expandedWizardGroups + "system"
                                }
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                            ) {
                                LazyColumn(
                                    state = systemListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Wifi,
                                            label = "Wi-Fi",
                                            description = "$systemWifiCount redes salvas no sistema",
                                            checked = includeWifiNetwork,
                                            onCheckedChange = {
                                                includeWifiNetwork = it
                                                persistCurrentSelection()
                                            },
                                            accent = AccentNeonPurple,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Dns,
                                            label = "DNS",
                                            description = "Servidores e DNS Privado (Modo: $systemDnsText)",
                                            checked = includeDns,
                                            onCheckedChange = {
                                                includeDns = it
                                                persistCurrentSelection()
                                            },
                                            accent = AccentNeonPurple,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Sms,
                                            label = "SMS",
                                            description = "$systemSmsCount ${if (systemSmsCount == 1) "conversa" else "conversas"} registradas",
                                            checked = includeSms,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    requestWithExplanation(
                                                        android.Manifest.permission.READ_SMS,
                                                        "SMS e MMS",
                                                        "O ReSync precisa da permissão de leitura de SMS para exportar as conversas."
                                                    ) {
                                                        includeSms = true
                                                        persistCurrentSelection()
                                                    }
                                                } else {
                                                    includeSms = false
                                                    persistCurrentSelection()
                                                }
                                            },
                                            accent = AccentNeonPurple,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Call,
                                            label = "Chamadas",
                                            description = "$systemCallsCount ${if (systemCallsCount == 1) "ligação registrada" else "ligações registradas"}",
                                            checked = includeCalls,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    requestWithExplanation(
                                                        android.Manifest.permission.READ_CALL_LOG,
                                                        "Histórico de Chamadas",
                                                        "O ReSync precisa da permissão de leitura de chamadas para exportar o registro de ligações."
                                                    ) {
                                                        includeCalls = true
                                                        persistCurrentSelection()
                                                    }
                                                } else {
                                                    includeCalls = false
                                                    persistCurrentSelection()
                                                }
                                            },
                                            accent = AccentNeonPurple,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Contacts,
                                            label = "Contatos",
                                            description = "$systemContactsCount ${if (systemContactsCount == 1) "contato" else "contatos"} na agenda",
                                            checked = includeContacts,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    requestWithExplanation(
                                                        android.Manifest.permission.READ_CONTACTS,
                                                        "Contatos",
                                                        "O ReSync precisa da permissão de leitura de contatos para gerar o arquivo vCard."
                                                    ) {
                                                        includeContacts = true
                                                        persistCurrentSelection()
                                                    }
                                                } else {
                                                    includeContacts = false
                                                    persistCurrentSelection()
                                                }
                                            },
                                            accent = AccentNeonPurple,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Bluetooth,
                                            label = "Bluetooth",
                                            description = "$systemBtCount ${if (systemBtCount == 1) "dispositivo pareado" else "dispositivos pareados"}",
                                            checked = includeBluetooth,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                                        requestWithExplanation(
                                                            android.Manifest.permission.BLUETOOTH_CONNECT,
                                                            "Bluetooth",
                                                            "Permissão necessária para listar dispositivos Bluetooth pareados."
                                                        ) {
                                                            includeBluetooth = true
                                                            persistCurrentSelection()
                                                        }
                                                    } else {
                                                        includeBluetooth = true
                                                        persistCurrentSelection()
                                                    }
                                                } else {
                                                    includeBluetooth = false
                                                    persistCurrentSelection()
                                                }
                                            },
                                            accent = AccentNeonPurple,
                                            haptic = haptic
                                        )
                                    }

                                    item {
                                        SubOptionItem(
                                            icon = Icons.Default.Wallpaper,
                                            label = "Papel de parede",
                                            description = "Papel de parede da tela inicial e da tela de bloqueio",
                                            checked = includeWallpaperHome || includeWallpaperLock,
                                            onCheckedChange = {
                                                includeWallpaperHome = it
                                                includeWallpaperLock = it
                                                persistCurrentSelection()
                                            },
                                            accent = AccentNeonPurple,
                                            haptic = haptic
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // GRUPO 4: ARQUIVOS E MÍDIA
                        // ==========================================
                        CategorySelectionCard(
                            icon = Icons.Default.FolderCopy,
                            iconTint = AccentSunsetOrange,
                            title = "Arquivos e Mídia",
                            subtitle = when {
                                quickBackupPaths.isEmpty() -> "Nenhum item selecionado"
                                else -> "${quickBackupPaths.size} ${if (quickBackupPaths.size == 1) "item selecionado" else "itens selecionados"}"
                            },
                            parentState = filesParentState,
                            onParentToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (filesParentState == ToggleableState.On) {
                                    viewModel.removeQuickBackupSelectedPaths(effectiveAvailablePaths)
                                    includeCustomFilesAndFolders = quickBackupPaths.isNotEmpty()
                                } else {
                                    includeCustomFilesAndFolders = true
                                    viewModel.toggleAllQuickBackupRootPaths(effectiveAvailablePaths)
                                }
                                persistCurrentSelection()
                            },
                            isExpanded = expandedWizardGroups.contains("files"),
                            onToggleExpand = {
                                expandedWizardGroups = if (expandedWizardGroups.contains("files")) {
                                    expandedWizardGroups - "files"
                                } else {
                                    expandedWizardGroups + "files"
                                }
                            }
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Banner discreto se MANAGE_EXTERNAL_STORAGE não foi concedido
                                if (!hasAllFilesAccess && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(1.dp, StatusWarning.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                                        colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Security, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Acesso a todos os arquivos necessário",
                                                    color = StatusWarning,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Text(
                                                text = "Para incluir todas as pastas e arquivos de /storage/emulated/0 no backup, conceda permissão de acesso a todos os arquivos.",
                                                color = TextSecondary,
                                                fontSize = 11.sp
                                            )
                                            Button(
                                                onClick = {
                                                    try {
                                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                            data = android.net.Uri.parse("package:${context.packageName}")
                                                        }
                                                        context.startActivity(intent)
                                                    } catch (_: Exception) {
                                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                        context.startActivity(intent)
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text("CONCEDER PERMISSÃO", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                                            }
                                        }
                                    }
                                }

                                // Campo de pesquisa com altura de 48 dp e texto de 14 sp centralizado
                                BasicTextField(
                                    value = storageSearchQuery,
                                    onValueChange = { storageSearchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 14.sp
                                    ),
                                    cursorBrush = SolidColor(AccentSunsetOrange),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(DarkSurfaceVariant)
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp)),
                                    decorationBox = { innerTextField ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                                if (storageSearchQuery.isEmpty()) {
                                                    Text(
                                                        text = "Pesquisar pasta ou arquivo...",
                                                        color = TextMuted,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                                innerTextField()
                                            }
                                            if (storageSearchQuery.isNotEmpty()) {
                                                IconButton(
                                                    onClick = { storageSearchQuery = "" },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Limpar busca",
                                                        tint = TextMuted,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )

                                // Reusable root storage explorer component with location bar, breadcrumbs, independent scroll
                                StorageExplorerListComponent(
                                    currentPath = wizardExplorerPath,
                                    onPathChange = { wizardExplorerPath = it },
                                    showHiddenFiles = wizardShowHiddenFiles,
                                    onToggleShowHiddenFiles = { wizardShowHiddenFiles = !wizardShowHiddenFiles },
                                    searchQuery = storageSearchQuery,
                                    selectedPaths = quickBackupPaths,
                                    onTogglePath = { path ->
                                        viewModel.toggleQuickBackupPath(path)
                                        includeCustomFilesAndFolders = true
                                        persistCurrentSelection()
                                    },
                                    onSelectAllInCurrentView = { paths ->
                                        viewModel.addQuickBackupSelectedPaths(paths)
                                        includeCustomFilesAndFolders = true
                                        persistCurrentSelection()
                                    },
                                    onDeselectAllInCurrentView = { paths ->
                                        viewModel.removeQuickBackupSelectedPaths(paths)
                                        persistCurrentSelection()
                                    },
                                    isRootActive = isRootActive,
                                    primaryAccent = AccentSunsetOrange,
                                    listHeight = 320.dp,
                                    showControlsRow = true,
                                    showBreadcrumbRow = true,
                                    enableSystemWarningDialog = true,
                                    onCurrentItemsChanged = { wizardCurrentViewItems = it }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(110.dp))
                    }
                }

                AssistantStep.DESTINATION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Destino do Backup",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Escolha onde o container .resync será gravado ou sincronizado.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Card 1: Armazenamento Local
                        SelectionOptionCard(
                            icon = Icons.Default.PhoneAndroid,
                            iconTint = AccentSunsetOrange,
                            title = "Armazenamento Local",
                            subtitle = "Salvar o cofre .resync na memória interna do aparelho (/sdcard/ReSyncBackups)",
                            selected = storageDestination == "Armazenamento Local",
                            onSelect = { storageDestination = "Armazenamento Local" },
                            badge = "Offline",
                            haptic = haptic
                        )

                        // Card 2: Google Drive
                        SelectionOptionCard(
                            icon = Icons.Default.CloudDone,
                            iconTint = AccentElectricCyan,
                            title = "Google Drive",
                            subtitle = "Sincronização na nuvem com integridade MD5 e SHA-256",
                            selected = storageDestination == "Google Drive",
                            onSelect = { storageDestination = "Google Drive" },
                            badge = "Nuvem",
                            haptic = haptic
                        )

                        // Card 3: Ambos (Local + Google Drive)
                        SelectionOptionCard(
                            icon = Icons.Default.Sync,
                            iconTint = AccentEmeraldGreen,
                            title = "Ambos (Local + Google Drive)",
                            subtitle = "Salva uma cópia na memória interna e sincroniza no Google Drive",
                            selected = storageDestination == "Ambos (Local + Google Drive)",
                            onSelect = { storageDestination = "Ambos (Local + Google Drive)" },
                            badge = "Recomendado",
                            haptic = haptic
                        )

                        // Google Drive Account Selector (when Drive or Both is chosen)
                        AnimatedVisibility(
                            visible = storageDestination != "Armazenamento Local",
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, AccentElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(AccentElectricCyan.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Conta do Google Drive",
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = "Selecione a conta para onde o backup será enviado",
                                                color = TextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    if (multiAccounts.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            multiAccounts.forEach { acc ->
                                                val isAccSelected = selectedDriveAccount == acc.email || (selectedDriveAccount == null && acc.isDefault)
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isAccSelected) AccentElectricCyan.copy(alpha = 0.12f) else DarkSurface,
                                                    border = BorderStroke(1.dp, if (isAccSelected) AccentElectricCyan else DarkCardBorder),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            selectedDriveAccount = acc.email
                                                        }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(28.dp)
                                                                    .clip(CircleShape)
                                                                    .background(if (isAccSelected) AccentElectricCyan else TextMuted),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(
                                                                    text = acc.email.take(1).uppercase(),
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color.Black,
                                                                    fontSize = 13.sp
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(10.dp))
                                                            Column {
                                                                Text(
                                                                    text = acc.email,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = TextPrimary,
                                                                    fontSize = 13.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                if (acc.isDefault) {
                                                                    Text(
                                                                        text = "Conta padrão",
                                                                        color = StatusSuccess,
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Medium
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        RadioButton(
                                                            selected = isAccSelected,
                                                            onClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                selectedDriveAccount = acc.email
                                                            },
                                                            colors = RadioButtonDefaults.colors(selectedColor = AccentElectricCyan)
                                                        )
                                                    }
                                                }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(
                                                    onClick = { pendingCloudBackupLauncher = { chosen -> selectedDriveAccount = chosen } }
                                                ) {
                                                    Icon(Icons.Default.ManageAccounts, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Gerenciar contas", color = AccentElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = StatusWarning.copy(alpha = 0.1f),
                                            border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Text(
                                                        text = "Nenhuma conta Google conectada. Conecte sua conta para fazer backup na nuvem.",
                                                        fontSize = 12.sp,
                                                        color = TextPrimary,
                                                        lineHeight = 16.sp
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Button(
                                                    onClick = { pendingCloudBackupLauncher = { chosen -> selectedDriveAccount = chosen } },
                                                    colors = ButtonDefaults.buttonColors(containerColor = AccentElectricCyan),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("CONECTAR GOOGLE DRIVE", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                AssistantStep.SECURITY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Segurança e Compactação",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Configure a proteção por senha e o algoritmo de compressão do arquivo.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "CRIPTOGRAFIA DO COFRE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyberPurple
                        )

                        // Card 1: Sem Criptografia
                        SelectionOptionCard(
                            icon = Icons.Default.LockOpen,
                            iconTint = TextSecondary,
                            title = "Sem Criptografia",
                            subtitle = "Container padrão aberto. Restauração rápida em qualquer aparelho.",
                            selected = securityMode == "Sem criptografia",
                            onSelect = { securityMode = "Sem criptografia" },
                            haptic = haptic
                        )

                        // Card 2: Criptografia com senha
                        SelectionOptionCard(
                            icon = Icons.Default.EnhancedEncryption,
                            iconTint = AccentCyberPurple,
                            title = "Criptografia com Senha (AES-256)",
                            subtitle = "Proteção militar de alta segurança com cifra AES-256-GCM",
                            selected = securityMode == "Criptografia com senha",
                            onSelect = { securityMode = "Criptografia com senha" },
                            badge = "Alta Segurança",
                            haptic = haptic
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = { Text("Digite a Senha do Cofre") },
                                    placeholder = { Text("Mínimo de 6 caracteres recomendado") },
                                    singleLine = true,
                                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                            Icon(
                                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (isPasswordVisible) "Ocultar senha" else "Mostrar senha",
                                                tint = AccentCyberPurple
                                            )
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentCyberPurple,
                                        unfocusedBorderColor = DarkCardBorder,
                                        focusedLabelColor = AccentCyberPurple
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Atenção: Guarde esta senha. Sem ela, os dados não poderão ser restaurados.",
                                    fontSize = 11.sp,
                                    color = StatusWarning,
                                    lineHeight = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "ALGORITMO DE COMPACTAÇÃO",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentElectricCyan
                        )

                        // Card 1: Inteligente (ZIP-MIXED)
                        SelectionOptionCard(
                            icon = Icons.Default.FolderZip,
                            iconTint = AccentElectricCyan,
                            title = "Inteligente (ZIP-MIXED)",
                            subtitle = "Equilíbrio recomendado: comprime scripts e dados; preserva APKs e mídias sem recompressão para máxima velocidade",
                            selected = compressionMode == "Padrão (ZIP-MIXED)",
                            onSelect = { compressionMode = "Padrão (ZIP-MIXED)" },
                            badge = "Recomendado",
                            haptic = haptic
                        )

                        // Card 2: Rápida (STORED)
                        SelectionOptionCard(
                            icon = Icons.Default.Speed,
                            iconTint = AccentSunsetOrange,
                            title = "Gravação Direta (STORED)",
                            subtitle = "Armazenamento direto sem compactação para menor uso de CPU",
                            selected = compressionMode == "Rápida (STORED)",
                            onSelect = { compressionMode = "Rápida (STORED)" },
                            badge = "Mais Rápido",
                            haptic = haptic
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                AssistantStep.SUMMARY -> {
                        // SUMMARY VIEW (BEFORE BACKUP START)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Resumo do Backup",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Revise tudo o que será gravado no cofre antes de iniciar.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "CONTEÚDO SELECIONADO",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryAccent
                            )

                            // Apps Summary Card
                            if (includeApps && appDataChildren.any { it }) {
                                val appChips = mutableListOf<String>()
                                if (includeBaseApk) appChips.add("APK")
                                if (includeAppSplits) appChips.add("Splits")
                                if (includeAppData) appChips.add("Dados")
                                if (includeExternalData) appChips.add("Ext")
                                if (includeObb) appChips.add("OBB")
                                if (includeAppPermissions) appChips.add("Permissões")

                                SummaryCategoryCard(
                                    icon = Icons.Default.Android,
                                    iconTint = AccentCyberPurple,
                                    title = "${selectedAppPackages.size} Aplicativo(s) Selecionado(s)",
                                    subtitle = "Pacotes instalados e estruturas de dados do usuário",
                                    chips = appChips
                                )
                            }

                            // System & Communication Summary Card
                            if (systemChildren.any { it }) {
                                val sysChips = mutableListOf<String>()
                                if (includeWifiNetwork) sysChips.add("Wi-Fi")
                                if (includeDns) sysChips.add("DNS")
                                if (includeSms) sysChips.add("SMS")
                                if (includeCalls) sysChips.add("Chamadas")
                                if (includeContacts) sysChips.add("Contatos")
                                if (includeBluetooth) sysChips.add("Bluetooth")
                                if (includeWallpaperHome) sysChips.add("Papel Inicial")
                                if (includeWallpaperLock) sysChips.add("Papel Bloqueio")

                                SummaryCategoryCard(
                                    icon = Icons.Default.SettingsCell,
                                    iconTint = AccentElectricCyan,
                                    title = "Sistema e Comunicação",
                                    subtitle = "Ajustes de conectividade, mensagens e registros telefônicos",
                                    chips = sysChips
                                )
                            }

                            // Files & Media Summary Card
                            if (quickBackupPaths.isNotEmpty()) {
                                val fileChips = mutableListOf<String>()
                                val selectedPathsList = quickBackupPaths.toList()
                                selectedPathsList.take(4).forEach {
                                    fileChips.add(File(it).name.ifEmpty { it })
                                }
                                if (selectedPathsList.size > 4) {
                                    fileChips.add("+${selectedPathsList.size - 4} itens")
                                }
                                val customItemCount = StorageRepository.normalizeAndDeduplicatePaths(quickBackupPaths).size

                                SummaryCategoryCard(
                                    icon = Icons.Default.Folder,
                                    iconTint = AccentSunsetOrange,
                                    title = "Arquivos e Mídias",
                                    subtitle = "$customItemCount item(ns) selecionado(s) em todos os locais",
                                    chips = fileChips
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "PARÂMETROS DE SAÍDA",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryAccent
                            )

                            // Destination Summary Card
                            val destIcon = when (storageDestination) {
                                "Google Drive" -> Icons.Default.CloudDone
                                "Ambos (Local + Google Drive)" -> Icons.Default.Sync
                                else -> Icons.Default.PhoneAndroid
                            }
                            val destTint = when (storageDestination) {
                                "Google Drive" -> AccentElectricCyan
                                "Ambos (Local + Google Drive)" -> AccentEmeraldGreen
                                else -> AccentSunsetOrange
                            }
                            val destSubtitle = if (storageDestination != "Armazenamento Local") {
                                "Conta: ${selectedDriveAccount ?: "Conta padrão conectada"}"
                            } else {
                                "Armazenamento interno: /sdcard/ReSyncBackups"
                            }

                            SummaryCategoryCard(
                                icon = destIcon,
                                iconTint = destTint,
                                title = storageDestination,
                                subtitle = destSubtitle
                            )

                            // Security & Compression Summary Card
                            SummaryCategoryCard(
                                icon = if (securityMode != "Sem criptografia") Icons.Default.EnhancedEncryption else Icons.Default.LockOpen,
                                iconTint = if (securityMode != "Sem criptografia") AccentCyberPurple else TextSecondary,
                                title = if (securityMode != "Sem criptografia") "Criptografia AES-256 Ativada" else "Sem Criptografia",
                                subtitle = "Compressão: $compressionMode"
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Highlight Total Estimated Size Card
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StatusSuccess.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(StatusSuccess.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Checklist, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 12.dp)
                                        ) {
                                            Text(
                                                text = "Tamanho Total Estimado",
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = "$selectedCount itens selecionados para gravação",
                                                color = TextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Text(
                                        text = estimatedSizeDisplay,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusSuccess,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                }
            }
        }
    }

    // Choice Dialog for choosing folders or files
    if (showFolderSourceChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showFolderSourceChoiceDialog = false },
            containerColor = DarkSurface,
            title = {
                Text("Adicionar Pastas ou Arquivos", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Selecione o método de navegação para escolher itens:",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )

                    // Option 1: Root Internal Browser
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, primaryAccent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .clickable {
                                showFolderSourceChoiceDialog = false
                                showStoragePickerModal = true
                            },
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(primaryAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Navegador Root Interno (Recomendado)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Text("Acesso com root a pastas restritas como /sdcard/Android/data e navegação por breadcrumb", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    // Option 2: System Folder Selector
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                showFolderSourceChoiceDialog = false
                                openTreeFolderLauncher.launch(null)
                            },
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AccentSunsetOrange.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AccentSunsetOrange, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Seletor de Pastas do Sistema (SAF)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Text("Abrir seletor oficial de documentos do Android", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    // Option 3: System Files Selector
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                showFolderSourceChoiceDialog = false
                                openFilesLauncher.launch(arrayOf("*/*"))
                            },
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AccentElectricCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Seletor de Arquivos do Sistema", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Text("Escolher múltiplos arquivos individuais", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFolderSourceChoiceDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Modal to pick granular apps with search & real icons
    if (showAppPickerModal) {
        val initialAppSelection = remember { selectedAppPackages.toList() }
        val cancelAndDismissAppSelection = {
            selectedAppPackages.clear()
            selectedAppPackages.addAll(initialAppSelection)
            showAppPickerModal = false
        }

        AlertDialog(
            onDismissRequest = cancelAndDismissAppSelection,
            containerColor = DarkSurface,
            title = {
                Text(
                    text = stringResource(R.string.select_apps_title),
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    val savedFilter by preferencesManager.appTypeFilterFlow.collectAsState(initial = "INSTALLED")
                    var selectedTypeFilter by remember { mutableStateOf("INSTALLED") }
                    var hasRestoredFilter by remember { mutableStateOf(false) }
                    LaunchedEffect(savedFilter) {
                        if (!hasRestoredFilter) {
                            selectedTypeFilter = savedFilter
                            hasRestoredFilter = true
                        }
                    }

                    var isFilterDropdownExpanded by remember { mutableStateOf(false) }
                    var hasWarnedAboutSystemApps by rememberSaveable { mutableStateOf(false) }

                    val visibleApps = remember(apps, showInternalSystemPackages) {
                        if (showInternalSystemPackages) apps else apps.filter { !it.isInternalSystemPackage && !com.resync.app.engine.PackageIgnorePolicy.isInternalSystemPackage(it.packageName) }
                    }

                    val installedAppsCount = remember(visibleApps) { visibleApps.count { !it.isSystemApp } }
                    val systemAppsCount = remember(visibleApps) { visibleApps.count { it.isSystemApp } }
                    val allAppsCount = remember(visibleApps) { visibleApps.size }

                    // Filtro em background (Dispatchers.Default) com debounce de 200 ms
                    var pickerApps by remember { mutableStateOf<List<com.resync.app.data.model.AppInfo>>(emptyList()) }
                    LaunchedEffect(visibleApps, appSearchQuery, selectedTypeFilter) {
                        if (appSearchQuery.isNotBlank()) {
                            kotlinx.coroutines.delay(200)
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            val filtered = visibleApps.filter { app ->
                                val matchesType = when (selectedTypeFilter) {
                                    "INSTALLED" -> !app.isSystemApp
                                    "SYSTEM" -> app.isSystemApp
                                    else -> true
                                }
                                val matchesSearch = if (appSearchQuery.isBlank()) true else {
                                    app.appName.contains(appSearchQuery, ignoreCase = true) ||
                                            app.packageName.contains(appSearchQuery, ignoreCase = true)
                                }
                                matchesType && matchesSearch
                            }
                            pickerApps = filtered
                        }
                    }

                    // Linha 1: Campo de pesquisa ocupando a largura toda, singleLine, ~48dp
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_apps_placeholder),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Linha 2: Dropdown do filtro ocupando a largura toda, ~42dp, sem cortar o texto
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentFilterLabel = when (selectedTypeFilter) {
                            "SYSTEM" -> stringResource(R.string.filter_system, systemAppsCount)
                            "ALL" -> stringResource(R.string.filter_all, allAppsCount)
                            else -> stringResource(R.string.filter_installed, installedAppsCount)
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clickable { isFilterDropdownExpanded = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = currentFilterLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isFilterDropdownExpanded,
                            onDismissRequest = { isFilterDropdownExpanded = false },
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .background(DarkSurfaceVariant)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_installed, installedAppsCount), color = TextPrimary, fontSize = 12.sp, maxLines = 1) },
                                onClick = {
                                    selectedTypeFilter = "INSTALLED"
                                    isFilterDropdownExpanded = false
                                    coroutineScope.launch { preferencesManager.saveAppTypeFilter("INSTALLED") }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_system, systemAppsCount), color = TextPrimary, fontSize = 12.sp, maxLines = 1) },
                                onClick = {
                                    selectedTypeFilter = "SYSTEM"
                                    isFilterDropdownExpanded = false
                                    coroutineScope.launch { preferencesManager.saveAppTypeFilter("SYSTEM") }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_all, allAppsCount), color = TextPrimary, fontSize = 12.sp, maxLines = 1) },
                                onClick = {
                                    selectedTypeFilter = "ALL"
                                    isFilterDropdownExpanded = false
                                    coroutineScope.launch { preferencesManager.saveAppTypeFilter("ALL") }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Linha 3: "Marcar todos" e "Desmarcar todos" lado a lado, weight 1f, altura ~40dp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = primaryAccent.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable {
                                    val eligiblePkgs = pickerApps
                                        .filterNot { it.isInternalSystemPackage || com.resync.app.engine.PackageIgnorePolicy.isInternalSystemPackage(it.packageName) }
                                        .map { it.packageName }
                                        .toSet()
                                    val toAdd = eligiblePkgs.filterNot { selectedAppPackages.contains(it) }
                                    if (toAdd.isNotEmpty()) {
                                        selectedAppPackages.addAll(toAdd)
                                        if (!hasWarnedAboutSystemApps && pickerApps.any { it.isSystemApp && toAdd.contains(it.packageName) }) {
                                            hasWarnedAboutSystemApps = true
                                            Toast.makeText(context, context.getString(R.string.system_app_warning), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.select_all),
                                    color = primaryAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, DarkCardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable {
                                    val visiblePkgs = pickerApps.map { it.packageName }.toSet()
                                    selectedAppPackages.removeAll(visiblePkgs)
                                }
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.deselect_all),
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(pickerApps, key = { it.packageName }) { app ->
                            val isChecked = selectedAppPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isChecked) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (isChecked) {
                                            selectedAppPackages.remove(app.packageName)
                                        } else {
                                            selectedAppPackages.add(app.packageName)
                                            if (app.isSystemApp && !hasWarnedAboutSystemApps) {
                                                hasWarnedAboutSystemApps = true
                                                Toast.makeText(context, context.getString(R.string.system_app_warning), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (isChecked) {
                                            selectedAppPackages.remove(app.packageName)
                                        } else {
                                            selectedAppPackages.add(app.packageName)
                                            if (app.isSystemApp && !hasWarnedAboutSystemApps) {
                                                hasWarnedAboutSystemApps = true
                                                Toast.makeText(context, context.getString(R.string.system_app_warning), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                RealAppIcon(
                                    packageName = app.packageName,
                                    size = 34.dp,
                                    shapeRadius = 8.dp
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(app.appName, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                                        if (app.isSystemApp) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = StatusWarning.copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "Sistema",
                                                    color = StatusWarning,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = app.packageName,
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            softWrap = true,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        val sizeText = if (app.sizeBytes > 0) {
                                            android.text.format.Formatter.formatFileSize(context, app.sizeBytes)
                                        } else {
                                            "0 B"
                                        }
                                        Text(
                                            text = sizeText,
                                            color = TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = cancelAndDismissAppSelection,
                    border = BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Voltar", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAppPickerModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.finish_selection, selectedAppPackages.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        )
    }

    // Non-root confirmation dialog for App Data
    if (showNonRootConfirmationDialog) {
        AlertDialog(
            onDismissRequest = {
                showNonRootConfirmationDialog = false
                pendingNonRootConfirmationAction = null
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(28.dp)) },
            title = {
                Text(
                    text = "Modo Sem Root Detectado",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "O backup de dados internos de aplicativos (/data/data) exige privilégios de Root.\n\nComo o dispositivo está em Modo Sem Root, o ReSync fará o backup apenas dos APKs dos aplicativos selecionados, sem gerar falhas.\n\nDeseja continuar com o backup dos APKs?",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNonRootConfirmationDialog = false
                        includeAppData = false
                        persistCurrentSelection()
                        val action = pendingNonRootConfirmationAction
                        pendingNonRootConfirmationAction = null
                        action?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Continuar (Apenas APKs)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNonRootConfirmationDialog = false
                    pendingNonRootConfirmationAction = null
                }) {
                    Text("Cancelar", color = TextMuted, fontSize = 12.sp)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Modal to pick custom files and folders with full storage navigation
    if (showStoragePickerModal) {
        StoragePickerModal(
            viewModel = viewModel,
            onDismiss = { showStoragePickerModal = false }
        )
    }

    if (pendingCloudBackupLauncher != null) {
        CloudAccountPickerModal(
            viewModel = viewModel,
            onConfirm = { chosenEmail ->
                val launcher = pendingCloudBackupLauncher
                pendingCloudBackupLauncher = null
                launcher?.invoke(chosenEmail)
            },
            onDismiss = {
                pendingCloudBackupLauncher = null
            }
        )
    }

    if (pendingPermissionRequest != null) {
        val req = pendingPermissionRequest!!
        AlertDialog(
            onDismissRequest = { pendingPermissionRequest = null },
            containerColor = DarkSurface,
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = AccentElectricCyan) },
            title = {
                Text(
                    text = "Permissão necessária: ${req.title}",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = req.explanation,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = req.permission
                        runtimePermissionLauncher.launch(p)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentElectricCyan)
                ) {
                    Text("Conceder Permissão", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermissionRequest = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }
}

// ==========================================
// REDESIGNED CATEGORY CARD WITH RICH AESTHETICS
// ==========================================
@Composable
private fun CategorySelectionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    parentState: ToggleableState,
    onParentToggle: () -> Unit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (isExpanded) iconTint.copy(alpha = 0.35f) else DarkCardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    // Left Circular Icon
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 3,
                            softWrap = true
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (actionText != null && onActionClick != null) {
                        TextButton(
                            onClick = onActionClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(actionText, fontSize = 12.sp, color = iconTint, fontWeight = FontWeight.Bold)
                        }
                    }

                    val haptic = LocalHapticFeedback.current
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(
                                role = Role.Checkbox,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onParentToggle()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        TriStateCheckbox(
                            state = parentState,
                            onClick = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = iconTint,
                                checkmarkColor = Color.White
                            )
                        )
                    }

                    IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Recolher" else "Expandir",
                            tint = TextSecondary
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 6.dp))
                    content()
                }
            }
        }
    }
}

// ==========================================
// SUB-OPTION ITEM WITH ICON, DESCRIPTION & HAPTICS
// ==========================================
@Composable
private fun SubOptionItem(
    icon: ImageVector,
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    badge: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    haptic: HapticFeedback
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Icon
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (checked) accent.copy(alpha = 0.15f) else DarkBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) accent else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (checked) TextPrimary else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DarkSurface)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(text = badge, fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (description != null) {
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 2,
                        softWrap = true
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (actionText != null && onActionClick != null) {
                TextButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(actionText, fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold)
                }
            }

            Checkbox(
                checked = checked,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(it)
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = accent,
                    checkmarkColor = Color.White
                )
            )
        }
    }
}

// ==========================================
// SELECTION OPTION CARD (UNIFORM DESIGN FOR STEPS 2, 3, 4)
// ==========================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionOptionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    badge: String? = null,
    haptic: HapticFeedback,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                1.dp,
                if (selected) iconTint.copy(alpha = 0.5f) else DarkCardBorder,
                RoundedCornerShape(16.dp)
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelect()
            },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) iconTint.copy(alpha = 0.08f) else DarkSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (badge != null) {
                                Surface(
                                    color = iconTint.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = badge,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = iconTint,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                RadioButton(
                    selected = selected,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect()
                    },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = iconTint,
                        unselectedColor = TextMuted
                    )
                )
            }

            if (content != null && selected) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 8.dp))
                content()
            }
        }
    }
}

// ==========================================
// SUMMARY CATEGORY CARD
// ==========================================
@Composable
private fun SummaryCategoryCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    sizeEstimate: String? = null,
    chips: List<String> = emptyList()
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, iconTint.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
                if (sizeEstimate != null) {
                    Text(
                        text = sizeEstimate,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = StatusSuccess
                    )
                }
            }

            if (chips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    chips.take(4).forEach { chip ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = iconTint.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, iconTint.copy(alpha = 0.25f))
                        ) {
                            Text(
                                text = chip,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = iconTint,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (chips.size > 4) {
                        Text(
                            text = "+${chips.size - 4}",
                            fontSize = 10.sp,
                            color = TextMuted,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}

private fun getBackupItemIcon(type: BackupItemType): ImageVector {
    return when (type) {
        BackupItemType.APP_APK, BackupItemType.APP_SPLIT_APKS, BackupItemType.APP_DATA -> Icons.Default.Android
        BackupItemType.WIFI_CONFIG -> Icons.Default.Wifi
        BackupItemType.DNS_CONFIG -> Icons.Default.Dns
        BackupItemType.SMS -> Icons.Default.Sms
        BackupItemType.CALL_LOG -> Icons.Default.Call
        BackupItemType.CONTACTS -> Icons.Default.Person
        BackupItemType.WALLPAPER -> Icons.Default.Wallpaper
        BackupItemType.WALLPAPER_LOCK -> Icons.Default.Lock
        BackupItemType.BLUETOOTH -> Icons.Default.Bluetooth
        BackupItemType.FILE -> Icons.Default.Description
        else -> Icons.Default.Folder
    }
}

private fun getBackupItemColor(type: BackupItemType): Color {
    return when (type) {
        BackupItemType.APP_APK, BackupItemType.APP_SPLIT_APKS, BackupItemType.APP_DATA -> AccentCyberPurple
        BackupItemType.WIFI_CONFIG, BackupItemType.DNS_CONFIG, BackupItemType.SMS, BackupItemType.CALL_LOG, BackupItemType.CONTACTS, BackupItemType.BLUETOOTH -> AccentElectricCyan
        BackupItemType.FILE, BackupItemType.WALLPAPER, BackupItemType.WALLPAPER_LOCK -> AccentSunsetOrange
        else -> AccentNeonBlue
    }
}

