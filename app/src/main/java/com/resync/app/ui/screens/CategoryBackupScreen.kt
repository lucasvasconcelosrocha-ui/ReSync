package com.resync.app.ui.screens

import android.Manifest
import android.app.WallpaperManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resync.app.data.model.*
import com.resync.app.engine.*
import com.resync.app.ui.components.AppSelectionListComponent
import com.resync.app.ui.components.GoogleDriveAccountsBottomSheet
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryBackupScreen(
    category: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)


    val localBackups by viewModel.backupRecords.collectAsStateWithLifecycle()
    val remoteBackups by viewModel.remoteDriveBackups.collectAsStateWithLifecycle()
    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
    val restoreProgress by viewModel.restoreProgress.collectAsStateWithLifecycle()

    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isLoadingInstalledApps by viewModel.isLoadingInstalledApps.collectAsStateWithLifecycle()
    val showInternalSystemPackages by viewModel.showInternalSystemPackages.collectAsStateWithLifecycle()

    val (categoryTitle, categoryIcon, categoryKeywords) = remember(category) {
        when (category.lowercase()) {
            "apps", "aplicativos" -> Triple("Backup de aplicativos", Icons.Default.Apps, listOf("app", "apps", "aplicativo", "aplicativos", "apk"))
            "messages", "sms" -> Triple("Backup de mensagens", Icons.Default.Message, listOf("sms", "mensagem", "mensagens", "messages"))
            "calls" -> Triple("Backup de chamadas", Icons.Default.Call, listOf("call", "chamada", "chamadas", "calls", "historico"))
            "contacts" -> Triple("Backup de contatos", Icons.Default.Contacts, listOf("contact", "contato", "contatos", "contacts", "agenda"))
            "wifi" -> Triple("Backup das redes Wi-Fi", Icons.Default.Wifi, listOf("wifi", "wi-fi", "rede", "redes"))
            "wallpaper", "wallpapers" -> Triple("Backup de papéis de parede", Icons.Default.Wallpaper, listOf("wallpaper", "wallpapers", "papel", "papeis", "papeis de parede", "papéis de parede", "fundo"))
            "bluetooth" -> Triple("Backup de dispositivos Bluetooth", Icons.Default.Bluetooth, listOf("bluetooth", "bt", "dispositivos bluetooth"))
            "dns" -> Triple("Backup de DNS Privado", Icons.Default.Dns, listOf("dns", "dns privado", "private dns"))
            else -> Triple("Backup de ${category.replaceFirstChar { it.uppercase() }}", Icons.Default.Category, listOf(category.lowercase()))
        }
    }

    val categoryCleanName = remember(category) {
        when (category.lowercase()) {
            "apps", "aplicativos" -> "Aplicativos"
            "messages", "sms" -> "Mensagens"
            "calls" -> "Chamadas"
            "contacts" -> "Contatos"
            "wifi" -> "Redes Wi-Fi"
            "wallpaper", "wallpapers" -> "Papéis de Parede"
            "bluetooth" -> "Bluetooth"
            "dns" -> "DNS Privado"
            else -> category.replaceFirstChar { it.uppercase() }
        }
    }

    // Dados reais do dispositivo
    var realSmsList by remember { mutableStateOf<List<SmsConversationItem>>(emptyList()) }
    var realCallsList by remember { mutableStateOf<List<CallLogEntry>>(emptyList()) }
    var realContactsList by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var realWifiList by remember { mutableStateOf<List<WifiSavedNetwork>>(emptyList()) }
    var realBtList by remember { mutableStateOf<List<BluetoothPairedDevice>>(emptyList()) }
    var isBluetoothEnabled by remember { mutableStateOf(true) }
    var realDnsInfo by remember { mutableStateOf<DnsCategoryInfo?>(null) }
    var realWallpapers by remember { mutableStateOf<List<WallpaperPreviewItem>>(emptyList()) }
    var isLoadingRealData by remember { mutableStateOf(true) }
    val isPermissionMissing = remember(category, isLoadingRealData) {
        when (category.lowercase()) {
            "calls" -> androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED
            "messages", "sms" -> androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            "contacts" -> androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    // Seleção de itens em "No celular agora"
    var selectedItemIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var contactSearchQuery by remember { mutableStateOf("") }
    var pageLimit by remember { mutableIntStateOf(50) }

    // Seletores de papel de parede (Ambos vêm marcados por padrão e pelo menos um deve ficar marcado)
    var isHomeWallpaperSelected by remember { mutableStateOf(true) }
    var isLockWallpaperSelected by remember { mutableStateOf(true) }

    // Estado da tela de restauração especializada
    var showRestoreSelectionScreen by remember { mutableStateOf(false) }
    var targetEntryToRestore by remember { mutableStateOf<UnifiedBackupEntry?>(null) }
    var selectedRestoreIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var isPullRefreshing by remember { mutableStateOf(false) }
    var showCreateBackupDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<UnifiedBackupEntry?>(null) }
    var permissionDeniedExplanation by remember { mutableStateOf<String?>(null) }

    val multiAccounts by viewModel.multiCloudAccounts.collectAsStateWithLifecycle()
    val activeAccountEmail by viewModel.activeAccountEmail.collectAsStateWithLifecycle()
    val activeAccount = remember(multiAccounts, activeAccountEmail) {
        multiAccounts.firstOrNull { it.email == activeAccountEmail }
            ?: multiAccounts.firstOrNull { it.isDefault }
            ?: multiAccounts.firstOrNull()
    }
    var showAccountsBottomSheet by remember { mutableStateOf(false) }

    var previewWallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewWallpaperTitle by remember { mutableStateOf("") }
    var selectedFilterChip by remember { mutableStateOf("Todos") }
    var revealedWifiPasswords by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun loadDeviceData() = withContext(Dispatchers.IO) {
        isLoadingRealData = true
        when (category.lowercase()) {
            "apps", "aplicativos" -> {
                viewModel.refreshInstalledApps(false)
                withContext(Dispatchers.Main) {
                    val apps = viewModel.installedApps.value
                    if (selectedItemIds.isEmpty() && apps.isNotEmpty()) {
                        selectedItemIds = apps.filter { !it.isSystemApp }.map { it.packageName }.toSet()
                    }
                }
            }
            "messages", "sms" -> {
                val list = DeviceCategoryInspector.getSmsConversations(context)
                withContext(Dispatchers.Main) {
                    realSmsList = list
                    selectedItemIds = list.map { it.stableId }.toSet()
                }
            }
            "calls" -> {
                val list = DeviceCategoryInspector.getCallLogs(context)
                withContext(Dispatchers.Main) {
                    realCallsList = list
                    selectedItemIds = list.map { it.stableId }.toSet()
                }
            }
            "contacts" -> {
                val list = DeviceCategoryInspector.getContacts(context)
                withContext(Dispatchers.Main) {
                    realContactsList = list
                    selectedItemIds = list.map { it.stableId }.toSet()
                }
            }
            "wifi" -> {
                val list = DeviceCategoryInspector.getSavedWifiNetworks(context)
                withContext(Dispatchers.Main) {
                    realWifiList = list
                    selectedItemIds = list.map { it.stableId }.toSet()
                }
            }
            "wallpaper", "wallpapers" -> {
                val list = DeviceCategoryInspector.getWallpapers(context)
                withContext(Dispatchers.Main) {
                    realWallpapers = list
                }
            }
            "bluetooth" -> {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val isEnabled = runCatching {
                    val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                    adapter?.isEnabled == true
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    isBluetoothEnabled = isEnabled
                }
                val list = DeviceCategoryInspector.getBluetoothDevices(context)
                withContext(Dispatchers.Main) {
                    realBtList = list
                    selectedItemIds = list.map { it.stableId }.toSet()
                }
            }
            "dns" -> {
                val dns = DeviceCategoryInspector.getDnsInfo(context)
                withContext(Dispatchers.Main) {
                    realDnsInfo = dns
                    selectedItemIds = setOf("dns_config")
                }
            }
        }
        withContext(Dispatchers.Main) {
            isLoadingRealData = false
        }
    }

    // Launcher de permissões em tempo de execução
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            permissionDeniedExplanation = "Algumas permissões foram negadas. O ReSync utilizará acesso Root como alternativa, se disponível."
        } else {
            permissionDeniedExplanation = null
        }
        coroutineScope.launch {
            loadDeviceData()
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        coroutineScope.launch { loadDeviceData() }
    }

    fun requestPermissionsForCategory() {
        val permissions = mutableListOf<String>()
        when (category.lowercase()) {
            "calls" -> {
                permissions.add(Manifest.permission.READ_CALL_LOG)
                permissions.add(Manifest.permission.READ_CONTACTS)
                permissions.add(Manifest.permission.WRITE_CALL_LOG)
            }
            "messages", "sms" -> {
                permissions.add(Manifest.permission.READ_SMS)
                permissions.add(Manifest.permission.READ_CONTACTS)
                permissions.add("android.permission.WRITE_SMS")
            }
            "contacts" -> {
                permissions.add(Manifest.permission.READ_CONTACTS)
                permissions.add(Manifest.permission.WRITE_CONTACTS)
            }
            "bluetooth" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            "wifi" -> {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                DeviceCategoryInspector.ensurePermission(context, *permissions.toTypedArray())
            }
            val missing = permissions.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    fun refreshAll() {
        viewModel.refreshBackups()
        viewModel.fetchRemoteDriveBackups()
        coroutineScope.launch {
            loadDeviceData()
        }
    }

    // Recarregar imediatamente quando um backup terminar
    LaunchedEffect(backupProgress?.isFinished, restoreProgress?.isFinished) {
        if (backupProgress?.isFinished == true || restoreProgress?.isFinished == true) {
            viewModel.refreshBackups()
            viewModel.fetchRemoteDriveBackups()
            loadDeviceData()
        }
    }

    LaunchedEffect(category) {
        requestPermissionsForCategory()
        refreshAll()
    }

    LaunchedEffect(installedApps) {
        if (category.lowercase() in listOf("apps", "aplicativos") && selectedItemIds.isEmpty() && installedApps.isNotEmpty()) {
            selectedItemIds = installedApps.filter { !it.isSystemApp }.map { it.packageName }.toSet()
        }
    }

    // ContentObserver e BroadcastReceiver para atualizações em tempo real
    DisposableEffect(category) {
        val observerHandler = Handler(Looper.getMainLooper())
        var contentObserver: ContentObserver? = null

        when (category.lowercase()) {
            "calls" -> {
                contentObserver = object : ContentObserver(observerHandler) {
                    override fun onChange(selfChange: Boolean) {
                        coroutineScope.launch { loadDeviceData() }
                    }
                }
                runCatching {
                    context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, contentObserver)
                }
            }
            "messages", "sms" -> {
                contentObserver = object : ContentObserver(observerHandler) {
                    override fun onChange(selfChange: Boolean) {
                        coroutineScope.launch { loadDeviceData() }
                    }
                }
                runCatching {
                    context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, contentObserver)
                }
            }
            "contacts" -> {
                contentObserver = object : ContentObserver(observerHandler) {
                    override fun onChange(selfChange: Boolean) {
                        coroutineScope.launch { loadDeviceData() }
                    }
                }
                runCatching {
                    context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contentObserver)
                }
            }
        }

        // BroadcastReceiver para Bluetooth e Wi-Fi
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (category.lowercase() == "bluetooth") {
                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                    isBluetoothEnabled = adapter?.isEnabled == true
                }
                coroutineScope.launch { loadDeviceData() }
            }
        }
        val filter = IntentFilter()
        if (category.lowercase() == "bluetooth") {
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            context.registerReceiver(receiver, filter)
        } else if (category.lowercase() == "wifi") {
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            contentObserver?.let {
                runCatching { context.contentResolver.unregisterContentObserver(it) }
            }
            if (category.lowercase() in listOf("bluetooth", "wifi")) {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    // Normalizador de texto para busca sem acentos
    fun normalize(str: String): String {
        return Normalizer.normalize(str, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
    }

    // Filtrar backups locais e remotos desta categoria (com tolerância a acentos e verificação de manifesto)
    val (localCategoryBackups, cloudCategoryBackups) = remember(localBackups, remoteBackups, category) {
        val validLocal = localBackups.filter {
            (it.filePath.isNotBlank() && File(it.filePath).exists() && !it.filePath.contains("/staging")) ||
                    it.destination.contains("Interna", ignoreCase = true)
        }
        val validRemote = remoteBackups.filter {
            !it.name.endsWith(".index.json") && !it.name.contains("legacy", ignoreCase = true)
        }

        fun matchesCat(textToCheck: String): Boolean {
            val normText = normalize(textToCheck)
            return categoryKeywords.any { kw -> normText.contains(normalize(kw)) }
        }

        val isAppsCategory = category.lowercase() in listOf("apps", "aplicativos")
        val localList = mutableListOf<UnifiedBackupEntry>()
        for (loc in validLocal) {
            val match = matchesCat("${loc.title} ${loc.filePath}") || when (category.lowercase()) {
                "apps", "aplicativos" -> loc.includedAppsCount > 0 || matchesCat(loc.title)
                "contacts" -> loc.includedAppsCount == 0 && loc.itemCount > 0 && matchesCat(loc.title)
                "dns" -> loc.includedDns || matchesCat(loc.title)
                else -> false
            }
            if (match) {
                localList.add(UnifiedBackupEntry(record = loc, hasLocal = true, hasDrive = false))
            }
        }

        val cloudList = mutableListOf<UnifiedBackupEntry>()
        for (rem in validRemote) {
            val remoteTitle = rem.appProperties["title"] ?: rem.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip")
            val desc = rem.appProperties["description"] ?: ""
            val isRemoteAppBackup = isAppsCategory && (rem.name.contains("app", ignoreCase = true) || remoteTitle.contains("app", ignoreCase = true))
            if (matchesCat("$remoteTitle ${rem.name} $desc") || isRemoteAppBackup) {
                val r = BackupRecord(
                    id = rem.id,
                    title = remoteTitle,
                    filePath = "",
                    sizeBytes = rem.sizeBytes,
                    checksumSha256 = rem.md5Checksum ?: "",
                    isEncrypted = rem.appProperties["isEncrypted"]?.toBooleanStrictOrNull() ?: false,
                    timestamp = rem.modifiedTime,
                    itemCount = 0,
                    status = BackupStatus.COMPLETED,
                    destination = "Google Drive",
                    syncStatus = SyncStatus.SYNCED,
                    driveFileId = rem.id
                )
                cloudList.add(UnifiedBackupEntry(record = r, hasLocal = false, hasDrive = true, remoteFileId = rem.id))
            }
        }

        Pair(localList.sortedByDescending { it.record.timestamp }, cloudList.sortedByDescending { it.record.timestamp })
    }

    // Se estiver no fluxo de restauração detalhada
    if (showRestoreSelectionScreen) {
        when (category.lowercase()) {
            "messages", "sms" -> {
                RestoreMessagesScreen(
                    conversations = realSmsList,
                    selectedIds = selectedRestoreIds,
                    onToggleSelect = { id ->
                        selectedRestoreIds = if (selectedRestoreIds.contains(id)) selectedRestoreIds - id else selectedRestoreIds + id
                    },
                    onSelectAll = { selectedRestoreIds = realSmsList.map { it.id }.toSet() },
                    onDeselectAll = { selectedRestoreIds = emptySet() },
                    onBack = { showRestoreSelectionScreen = false },
                    onConfirmRestore = {
                        showRestoreSelectionScreen = false
                        targetEntryToRestore?.let { entry ->
                            viewModel.restoreCategoryBackup(entry, category)
                            Toast.makeText(context, "Restaurando mensagens selecionadas...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    accent = primaryAccent
                )
                return
            }
            "calls" -> {
                RestoreCallsScreen(
                    callLogs = realCallsList,
                    selectedIds = selectedRestoreIds,
                    onToggleSelect = { id ->
                        selectedRestoreIds = if (selectedRestoreIds.contains(id)) selectedRestoreIds - id else selectedRestoreIds + id
                    },
                    onSelectAll = { selectedRestoreIds = realCallsList.map { it.id }.toSet() },
                    onDeselectAll = { selectedRestoreIds = emptySet() },
                    onBack = { showRestoreSelectionScreen = false },
                    onConfirmRestore = {
                        showRestoreSelectionScreen = false
                        targetEntryToRestore?.let { entry ->
                            viewModel.restoreCategoryBackup(entry, category)
                            Toast.makeText(context, "Restaurando chamadas selecionadas...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    accent = primaryAccent
                )
                return
            }
        }
    }

    val bentoBg = MaterialTheme.colorScheme.background
    val bentoCard = MaterialTheme.colorScheme.surface
    val bentoCardBorder = MaterialTheme.colorScheme.outlineVariant
    val bentoSubCard = MaterialTheme.colorScheme.surfaceVariant
    val bentoCoral = MaterialTheme.colorScheme.primary
    val bentoEmerald = StatusSuccess
    val bentoCyan = MaterialTheme.colorScheme.primary
    val bentoAmber = StatusWarning
    val bentoTextPrimary = MaterialTheme.colorScheme.onBackground
    val bentoTextSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val bentoTextMuted = TextMuted

    Scaffold(
        containerColor = bentoBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bentoBg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botão Voltar com container escuro e borda fina
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bentoCard)
                        .border(1.dp, bentoCardBorder, RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = bentoTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "EXECUÇÃO DE BACKUP",
                        style = TextStyle(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = bentoCyan,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (category.lowercase() == "bluetooth") "Backup de Bluetooth" else categoryTitle,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = bentoTextPrimary
                            ),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Pill de status do sistema/hardware
                        val (statusText, statusColor) = when (category.lowercase()) {
                            "bluetooth" -> if (isBluetoothEnabled) Pair("● ATIVO", bentoEmerald) else Pair("● INATIVO", bentoAmber)
                            "messages", "sms" -> Pair("● TELEPHONY", bentoEmerald)
                            "calls" -> Pair("● CALL_LOG", bentoEmerald)
                            "contacts" -> Pair("● ${realContactsList.size} CONTATOS", bentoEmerald)
                            "wallpaper", "wallpapers" -> Pair("● SURFACE", bentoEmerald)
                            "wifi" -> Pair("● WPA_SUPPLICANT", bentoEmerald)
                            "dns" -> Pair("● ATIVO", bentoEmerald)
                            else -> Pair("● ROOT ATIVO", bentoEmerald)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = statusColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
                            modifier = Modifier.wrapContentWidth()
                        ) {
                            Text(
                                text = statusText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = statusColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Foto de perfil circular da conta do Google Drive logada
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(bentoCard)
                        .border(1.5.dp, bentoCoral.copy(alpha = 0.8f), CircleShape)
                        .clickable { showAccountsBottomSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    val photoUrl = activeAccount?.photoUrl
                    if (!photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(photoUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Conta Google Drive",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        val initial = when {
                            !activeAccount?.name.isNullOrBlank() -> activeAccount!!.name.take(1).uppercase()
                            !activeAccount?.email.isNullOrBlank() -> activeAccount!!.email.take(1).uppercase()
                            else -> null
                        }
                        if (initial != null) {
                            Text(
                                text = initial,
                                color = bentoCoral,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Conta Google",
                                tint = bentoCoral,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = bentoBg,
                border = BorderStroke(1.dp, bentoCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    val totalSel = when (category.lowercase()) {
                        "wallpaper", "wallpapers" -> (if (isHomeWallpaperSelected) 1 else 0) + (if (isLockWallpaperSelected) 1 else 0)
                        "apps", "aplicativos" -> selectedItemIds.size
                        else -> selectedItemIds.size
                    }
                    Button(
                        onClick = { showCreateBackupDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = bentoCoral),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (totalSel > 0) "Criar Backup de $categoryCleanName ($totalSel selecionados)" else "Criar Backup de $categoryCleanName",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                coroutineScope.launch {
                    refreshAll()
                    delay(500)
                    isPullRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ==========================================
                // 1. HERO BENTO CARD SUPERIOR DA CATEGORIA
                // ==========================================
                item(key = "bento_hero_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = bentoCard),
                        border = BorderStroke(1.dp, bentoCardBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Topo do Hero: Ícone + Título Categoria + Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bentoCoral.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = categoryIcon,
                                            contentDescription = null,
                                            tint = bentoCoral,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = when (category.lowercase()) {
                                                "bluetooth" -> "BLUETOOTH & PERIFÉRICOS"
                                                "messages", "sms" -> "MENSAGENS & CONVERSAS"
                                                "calls" -> "HISTÓRICO DE CHAMADAS"
                                                "contacts" -> "AGENDA DE CONTATOS"
                                                "wallpaper", "wallpapers" -> "PAPÉIS DE PAREDE DO SISTEMA"
                                                "wifi" -> "REDES WI-FI SALVAS & CHAVES"
                                                "apps", "aplicativos" -> "APLICATIVOS DO SISTEMA & USUÁRIO"
                                                "dns" -> "CONFIGURAÇÃO DE DNS DO SISTEMA"
                                                else -> categoryTitle.uppercase()
                                            },
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = bentoTextPrimary
                                        )
                                        Text(
                                            text = when (category.lowercase()) {
                                                "bluetooth" -> "CONFIG_PATH: /data/misc/bluetooth/"
                                                "messages", "sms" -> "URI: content://sms/conversations"
                                                "calls" -> "URI: content://call_log/calls"
                                                "contacts" -> "URI: content://com.android.contacts"
                                                "wallpaper", "wallpapers" -> "PATH: /data/system/users/0/wallpaper"
                                                "wifi" -> "PATH: /data/misc/wifi/WifiConfigStore.xml"
                                                "apps", "aplicativos" -> "PATH: /data/app • /data/data"
                                                "dns" -> "PATH: Settings.Global (private_dns_mode / specifier)"
                                                else -> "PROVIDER: SYSTEM_BACKUP_SERVICE"
                                            },
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = bentoTextMuted
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Grid de Métricas em Tempo Real (3 ou 4 boxes)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                when (category.lowercase()) {
                                    "bluetooth" -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "DISPOSITIVOS", value = "${realBtList.size}", valueColor = bentoTextPrimary)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "CONECTADOS", value = "${realBtList.count { it.isConnected }}", valueColor = bentoCyan)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "ADAPTADOR", value = if (isBluetoothEnabled) "Ativo" else "Off", valueColor = if (isBluetoothEnabled) bentoEmerald else bentoAmber)
                                    }
                                    "messages", "sms" -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "CONVERSAS", value = "${realSmsList.size}", valueColor = bentoTextPrimary)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "TOTAL MSGS", value = "${realSmsList.sumOf { it.messageCount }}", valueColor = bentoCyan)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "SELECIONADAS", value = "${selectedItemIds.size}", valueColor = bentoEmerald)
                                    }
                                    "calls" -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "TOTAL", value = "${realCallsList.size}", valueColor = bentoTextPrimary)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "RECEBIDAS", value = "${realCallsList.count { it.type == CallLog.Calls.INCOMING_TYPE }}", valueColor = bentoEmerald)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "EFETUADAS", value = "${realCallsList.count { it.type == CallLog.Calls.OUTGOING_TYPE }}", valueColor = bentoCyan)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "PERDIDAS", value = "${realCallsList.count { it.type == CallLog.Calls.MISSED_TYPE }}", valueColor = bentoCoral)
                                    }
                                    "contacts" -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "TOTAL", value = "${realContactsList.size}", valueColor = bentoTextPrimary)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "COM TELEFONE", value = "${realContactsList.count { it.phone.isNotBlank() }}", valueColor = bentoCyan)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "SELECIONADOS", value = "${selectedItemIds.size}", valueColor = bentoEmerald)
                                    }
                                    "wallpaper", "wallpapers" -> {
                                        val hasHome = realWallpapers.any { it.flag == WallpaperManager.FLAG_SYSTEM }
                                        val hasLock = realWallpapers.any { it.flag == WallpaperManager.FLAG_LOCK }
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "TELA INICIAL", value = if (hasHome) "Ativo" else "Padrão", valueColor = bentoEmerald)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "BLOQUEIO", value = if (hasLock) "Ativo" else "Padrão", valueColor = bentoCyan)
                                        val selCount = (if (isHomeWallpaperSelected) 1 else 0) + (if (isLockWallpaperSelected) 1 else 0)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "SELEÇÃO", value = "$selCount / 2", valueColor = bentoCoral)
                                    }
                                    "wifi" -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "REDES SALVAS", value = "${realWifiList.size}", valueColor = bentoTextPrimary)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "COM SENHA", value = "${realWifiList.count { !it.password.isNullOrBlank() }}", valueColor = bentoCyan)
                                        val connSsid = realWifiList.find { it.isConnected }?.ssid ?: "Nenhuma"
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "CONECTADA", value = connSsid, valueColor = if (connSsid != "Nenhuma") bentoEmerald else bentoTextMuted)
                                    }
                                    "apps", "aplicativos" -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "INSTALADOS", value = "${installedApps.size}", valueColor = bentoTextPrimary)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "USUÁRIO", value = "${installedApps.count { !it.isSystemApp }}", valueColor = bentoCyan)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "SELECIONADOS", value = "${selectedItemIds.size}", valueColor = bentoEmerald)
                                    }
                                    "dns" -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "MODO ATUAL", value = when (realDnsInfo?.mode) {
                                            "hostname" -> "Privado"
                                            "opportunistic" -> "Auto"
                                            else -> "Off"
                                        }, valueColor = bentoTextPrimary)
                                        BentoMetricBox(modifier = Modifier.weight(1.3f), label = "PROVEDOR / HOST", value = realDnsInfo?.hostname?.ifBlank { "Automático" } ?: "Automático", valueColor = bentoCyan)
                                        BentoMetricBox(modifier = Modifier.weight(0.9f), label = "SERVIDORES", value = if (realDnsInfo?.isPrivateDnsActive == true) "1 Ativo" else "0", valueColor = if (realDnsInfo?.isPrivateDnsActive == true) bentoEmerald else bentoTextMuted)
                                    }
                                    else -> {
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "STATUS", value = "Operacional", valueColor = bentoEmerald)
                                        BentoMetricBox(modifier = Modifier.weight(1f), label = "BACKUPS", value = "${localCategoryBackups.size}", valueColor = bentoCyan)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Botão CTA Coral do Hero Card
                            Button(
                                onClick = { showCreateBackupDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = bentoCoral)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (category.lowercase()) {
                                        "bluetooth" -> "Fazer Backup das Conexões Bluetooth"
                                        "dns" -> "⚡ Fazer Backup Imediato do DNS"
                                        else -> "Fazer Backup de $categoryCleanName"
                                    },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Banner de permissão interativo
                if (isPermissionMissing) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = bentoAmber.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, bentoAmber.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = bentoAmber, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Permissão do sistema necessária", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = bentoTextPrimary)
                                        Text(
                                            text = when (category.lowercase()) {
                                                "calls" -> "Conceda permissão de Histórico de Chamadas para listar e salvar registros."
                                                "messages", "sms" -> "Conceda permissão de SMS para ler e exportar mensagens."
                                                "contacts" -> "Conceda permissão de Contatos para mapear a agenda."
                                                else -> "Conceda as permissões solicitadas."
                                            },
                                            fontSize = 11.sp,
                                            color = bentoTextSecondary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        requestPermissionsForCategory()
                                        coroutineScope.launch {
                                            delay(500)
                                            loadDeviceData()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = bentoAmber),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("Conceder", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                        }
                    }
                } else if (permissionDeniedExplanation != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = bentoAmber.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, bentoAmber.copy(alpha = 0.45f))
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = bentoAmber, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(permissionDeniedExplanation!!, fontSize = 11.5.sp, color = bentoTextPrimary)
                            }
                        }
                    }
                }

                // Aviso de Bluetooth desligado com botão para ligar
                if (category.lowercase() == "bluetooth" && !isBluetoothEnabled) {
                    item(key = "banner_bluetooth_disabled") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = bentoAmber.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, bentoAmber.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothDisabled,
                                        contentDescription = null,
                                        tint = bentoAmber,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Bluetooth desativado", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = bentoTextPrimary)
                                        Text("Ative para sincronizar dispositivos pareados em tempo real.", fontSize = 11.sp, color = bentoTextSecondary)
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        try {
                                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                        } catch (_: Throwable) {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                            } catch (_: Throwable) {}
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = bentoAmber),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("Ativar", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 2. CONTEÚDO PRINCIPAL / LISTAS DE DADOS
                // ==========================================
                val isAppsCategory = category.lowercase() in listOf("apps", "aplicativos")
                val isNormalListCategory = category.lowercase() in listOf("messages", "sms", "calls", "contacts", "wifi", "bluetooth")
                val isSpecialCategory = category.lowercase() in listOf("wallpaper", "wallpapers", "dns")

                if (isAppsCategory) {
                    item(key = "card_device_items_apps") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = bentoCard),
                            border = BorderStroke(1.dp, bentoCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Apps, contentDescription = null, tint = bentoCoral, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Aplicativos Instalados (${installedApps.size})",
                                            fontWeight = FontWeight.Bold,
                                            color = bentoTextPrimary,
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                AppSelectionListComponent(
                                    apps = installedApps,
                                    selectedPackages = selectedItemIds,
                                    showInternalSystemPackages = showInternalSystemPackages,
                                    onTogglePackage = { pkg ->
                                        selectedItemIds = if (selectedItemIds.contains(pkg)) selectedItemIds - pkg else selectedItemIds + pkg
                                    },
                                    onSelectAll = { pkgs -> selectedItemIds = selectedItemIds + pkgs },
                                    onDeselectAll = { pkgs -> selectedItemIds = selectedItemIds - pkgs },
                                    primaryAccent = bentoCoral,
                                    maxHeight = 360.dp,
                                    showIndividualBackupButton = true,
                                    onIndividualBackup = { app ->
                                        viewModel.launchCategoryBackup(
                                            category = "apps",
                                            destinationType = BackupDestinationType.SOMENTE_INTERNA,
                                            selectedAppPackages = listOf(app.packageName)
                                        )
                                        Toast.makeText(context, "Iniciando backup de ${app.appName}...", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                } else if (isLoadingRealData) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = bentoCard),
                            border = BorderStroke(1.dp, bentoCardBorder)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = bentoCoral, strokeWidth = 2.dp)
                            }
                        }
                    }
                } else if (isSpecialCategory) {
                    when (category.lowercase()) {
                        "wallpaper", "wallpapers" -> {
                            val homeWp = realWallpapers.find { it.flag == WallpaperManager.FLAG_SYSTEM }
                            val lockWp = realWallpapers.find { it.flag == WallpaperManager.FLAG_LOCK }

                            // Chips de Seleção Rápida
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val isBoth = isHomeWallpaperSelected && isLockWallpaperSelected
                                    val isOnlyHome = isHomeWallpaperSelected && !isLockWallpaperSelected
                                    val isOnlyLock = !isHomeWallpaperSelected && isLockWallpaperSelected

                                    BentoFilterChip(
                                        text = "Ambos",
                                        isSelected = isBoth,
                                        onClick = {
                                            isHomeWallpaperSelected = true
                                            isLockWallpaperSelected = true
                                        }
                                    )
                                    BentoFilterChip(
                                        text = "Tela Inicial",
                                        isSelected = isOnlyHome,
                                        onClick = {
                                            isHomeWallpaperSelected = true
                                            isLockWallpaperSelected = false
                                        }
                                    )
                                    BentoFilterChip(
                                        text = "Tela de Bloqueio",
                                        isSelected = isOnlyLock,
                                        onClick = {
                                            isHomeWallpaperSelected = false
                                            isLockWallpaperSelected = true
                                        }
                                    )
                                }
                            }

                            // Grid com 2 Bento Cards de Wallpaper
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Card Tela Inicial
                                    BentoWallpaperCard(
                                        modifier = Modifier.weight(1f),
                                        title = "TELA INICIAL",
                                        bitmap = homeWp?.bitmap,
                                        isSelected = isHomeWallpaperSelected,
                                        onToggle = {
                                            if (isHomeWallpaperSelected && !isLockWallpaperSelected) {
                                                Toast.makeText(context, "Pelo menos um papel de parede deve ficar selecionado.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                isHomeWallpaperSelected = !isHomeWallpaperSelected
                                            }
                                        },
                                        onPreview = {
                                            previewWallpaperBitmap = homeWp?.bitmap
                                            previewWallpaperTitle = "Papel de Parede — Tela Inicial"
                                        }
                                    )

                                    // Card Tela de Bloqueio
                                    BentoWallpaperCard(
                                        modifier = Modifier.weight(1f),
                                        title = "BLOQUEIO",
                                        bitmap = lockWp?.bitmap ?: homeWp?.bitmap,
                                        isSelected = isLockWallpaperSelected,
                                        onToggle = {
                                            if (isLockWallpaperSelected && !isHomeWallpaperSelected) {
                                                Toast.makeText(context, "Pelo menos um papel de parede deve ficar selecionado.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                isLockWallpaperSelected = !isLockWallpaperSelected
                                            }
                                        },
                                        onPreview = {
                                            previewWallpaperBitmap = lockWp?.bitmap ?: homeWp?.bitmap
                                            previewWallpaperTitle = "Papel de Parede — Tela de Bloqueio"
                                        }
                                    )
                                }
                            }
                        }

                        "dns" -> {
                            val dns = realDnsInfo
                            val isChecked = "dns_config" in selectedItemIds
                            item(key = "dns_active_config") {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .border(
                                            1.dp,
                                            if (isChecked) bentoCoral.copy(alpha = 0.5f) else bentoCardBorder,
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable {
                                            selectedItemIds = if (isChecked) emptySet() else setOf("dns_config")
                                        },
                                    colors = CardDefaults.cardColors(containerColor = bentoCard)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    selectedItemIds = if (checked) setOf("dns_config") else emptySet()
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = bentoCoral,
                                                    uncheckedColor = bentoTextMuted,
                                                    checkmarkColor = Color.White
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = if (!dns?.hostname.isNullOrBlank()) dns!!.hostname!! else "DNS Privado Automático (DoH / TLS)",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = bentoTextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = if (dns?.isPrivateDnsActive == true) bentoEmerald.copy(alpha = 0.15f) else Color(0xFF1E293B),
                                                        border = BorderStroke(1.dp, if (dns?.isPrivateDnsActive == true) bentoEmerald.copy(alpha = 0.4f) else bentoCardBorder)
                                                    ) {
                                                        Text(
                                                            text = if (dns?.isPrivateDnsActive == true) "● ATIVO" else "● PADRÃO",
                                                            fontSize = 9.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (dns?.isPrivateDnsActive == true) bentoEmerald else bentoTextMuted,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "Modo: ${dns?.mode ?: "opportunistic"} • Interface: Ativa • Servidores IPv4/IPv6",
                                                    fontSize = 11.sp,
                                                    color = bentoTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = bentoCardBorder)
                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Provedor / Hostname:",
                                                fontSize = 11.5.sp,
                                                color = bentoTextMuted
                                            )
                                            Text(
                                                text = dns?.hostname?.ifBlank { "dns.google / Cloudflare / ISP" } ?: "Padrão da Operadora / Wi-Fi",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = bentoCyan
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (isNormalListCategory) {
                    val q = normalize(contactSearchQuery)

                    // Filtragem por Chip
                    val chipFilteredList: List<Any> = when (category.lowercase()) {
                        "bluetooth" -> when (selectedFilterChip) {
                            "Conectados" -> realBtList.filter { it.isConnected }
                            "Áudio" -> realBtList.filter { it.deviceCategory in listOf("headphones", "speaker") }
                            "Outros" -> realBtList.filter { !it.isConnected && it.deviceCategory !in listOf("headphones", "speaker") }
                            else -> realBtList
                        }
                        "messages", "sms" -> when (selectedFilterChip) {
                            "Múltiplas msgs" -> realSmsList.filter { it.messageCount > 1 }
                            else -> realSmsList
                        }
                        "calls" -> when (selectedFilterChip) {
                            "Recebidas" -> realCallsList.filter { it.type == CallLog.Calls.INCOMING_TYPE }
                            "Efetuadas" -> realCallsList.filter { it.type == CallLog.Calls.OUTGOING_TYPE }
                            "Perdidas" -> realCallsList.filter { it.type == CallLog.Calls.MISSED_TYPE }
                            else -> realCallsList
                        }
                        "contacts" -> when (selectedFilterChip) {
                            "Com Telefone" -> realContactsList.filter { it.phone.isNotBlank() }
                            "Com Nome" -> realContactsList.filter { it.hasRealName }
                            else -> realContactsList
                        }
                        "wifi" -> when (selectedFilterChip) {
                            "Com Senha" -> realWifiList.filter { !it.password.isNullOrBlank() }
                            "WPA/WPA2" -> realWifiList.filter { it.keyMgmt.contains("WPA", ignoreCase = true) }
                            "Conectada" -> realWifiList.filter { it.isConnected }
                            else -> realWifiList
                        }
                        else -> emptyList()
                    }

                    // Filtragem por busca
                    @Suppress("UNCHECKED_CAST")
                    val fullList: List<Any> = if (q.isBlank()) {
                        chipFilteredList
                    } else {
                        when (category.lowercase()) {
                            "messages", "sms" -> (chipFilteredList as List<SmsConversationItem>).filter { normalize(it.address).contains(q) || normalize(it.snippet).contains(q) }
                            "calls" -> (chipFilteredList as List<CallLogEntry>).filter { normalize(it.name ?: "").contains(q) || it.number.contains(q) }
                            "contacts" -> (chipFilteredList as List<ContactItem>).filter { normalize(it.name).contains(q) || it.cleanPhone.contains(q) }
                            "wifi" -> (chipFilteredList as List<WifiSavedNetwork>).filter { normalize(it.ssid).contains(q) }
                            "bluetooth" -> (chipFilteredList as List<BluetoothPairedDevice>).filter { normalize(it.name).contains(q) || it.address.contains(q, ignoreCase = true) }
                            else -> chipFilteredList
                        }
                    }

                    val currentSelectedCount = fullList.count { item ->
                        val sid = when (item) {
                            is SmsConversationItem -> item.stableId
                            is CallLogEntry -> item.stableId
                            is ContactItem -> item.stableId
                            is WifiSavedNetwork -> item.stableId
                            is BluetoothPairedDevice -> item.stableId
                            else -> ""
                        }
                        selectedItemIds.contains(sid)
                    }

                    // Bloco de Pesquisa e Chips de Filtro
                    item(key = "filters_and_search") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Barra de Busca Bento Dark
                            BasicTextField(
                                value = contactSearchQuery,
                                onValueChange = { contactSearchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.5.sp, color = bentoTextPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(bentoCard, RoundedCornerShape(12.dp))
                                    .border(1.dp, if (contactSearchQuery.isNotEmpty()) bentoCoral else bentoCardBorder, RoundedCornerShape(12.dp)),
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
                                            tint = if (contactSearchQuery.isNotEmpty()) bentoCoral else bentoTextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            if (contactSearchQuery.isEmpty()) {
                                                Text(
                                                    text = when (category.lowercase()) {
                                                        "bluetooth" -> "Buscar dispositivos pareados..."
                                                        "messages", "sms" -> "Buscar conversas ou mensagens..."
                                                        "calls" -> "Buscar chamadas por contato ou número..."
                                                        "contacts" -> "Buscar contatos por nome ou telefone..."
                                                        "wifi" -> "Buscar rede por SSID..."
                                                        else -> "Buscar nesta categoria..."
                                                    },
                                                    fontSize = 13.sp,
                                                    color = bentoTextMuted,
                                                    maxLines = 1
                                                )
                                            }
                                            innerTextField()
                                        }
                                        if (contactSearchQuery.isNotEmpty()) {
                                            IconButton(
                                                onClick = { contactSearchQuery = "" },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Limpar",
                                                    tint = bentoTextMuted,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            )

                            // Chips de Filtro
                            val availableChips = when (category.lowercase()) {
                                "bluetooth" -> listOf("Todos", "Conectados", "Áudio", "Outros")
                                "messages", "sms" -> listOf("Todos", "Múltiplas msgs")
                                "calls" -> listOf("Todas", "Recebidas", "Efetuadas", "Perdidas")
                                "contacts" -> listOf("Todos", "Com Telefone", "Com Nome")
                                "wifi" -> listOf("Todas", "Com Senha", "WPA/WPA2", "Conectada")
                                else -> emptyList()
                            }

                            if (availableChips.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    availableChips.forEach { chipName ->
                                        BentoFilterChip(
                                            text = chipName,
                                            isSelected = (selectedFilterChip == chipName) || (selectedFilterChip == "Todos" && chipName == "Todas"),
                                            onClick = { selectedFilterChip = chipName }
                                        )
                                    }
                                }
                            }

                            // Linha de Seleção Rápida (X de Y selecionados | Marcar todos / Desmarcar todos)
                            SelectAllHeader(
                                totalCount = fullList.size,
                                selectedCount = currentSelectedCount,
                                onSelectAll = {
                                    val allIds = fullList.map { item ->
                                        when (item) {
                                            is SmsConversationItem -> item.stableId
                                            is CallLogEntry -> item.stableId
                                            is ContactItem -> item.stableId
                                            is WifiSavedNetwork -> item.stableId
                                            is BluetoothPairedDevice -> item.stableId
                                            else -> ""
                                        }
                                    }.toSet()
                                    selectedItemIds = selectedItemIds + allIds
                                },
                                onDeselectAll = {
                                    val allIds = fullList.map { item ->
                                        when (item) {
                                            is SmsConversationItem -> item.stableId
                                            is CallLogEntry -> item.stableId
                                            is ContactItem -> item.stableId
                                            is WifiSavedNetwork -> item.stableId
                                            is BluetoothPairedDevice -> item.stableId
                                            else -> ""
                                        }
                                    }.toSet()
                                    selectedItemIds = selectedItemIds - allIds
                                },
                                primaryAccent = bentoCoral
                            )
                        }
                    }

                    // Itens da Lista em Bento Cards Individuais
                    if (fullList.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = bentoCard),
                                border = BorderStroke(1.dp, bentoCardBorder)
                            ) {
                                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text("Nenhum registro encontrado.", fontSize = 12.5.sp, color = bentoTextMuted)
                                }
                            }
                        }
                    } else {
                        val visibleItems = fullList.take(pageLimit)

                        itemsIndexed(
                            items = visibleItems,
                            key = { _, item ->
                                when (item) {
                                    is SmsConversationItem -> item.stableId
                                    is CallLogEntry -> item.stableId
                                    is ContactItem -> item.stableId
                                    is WifiSavedNetwork -> item.stableId
                                    is BluetoothPairedDevice -> item.stableId
                                    else -> item.hashCode().toString()
                                }
                            }
                        ) { _, item ->
                            val itemStableId = when (item) {
                                is SmsConversationItem -> item.stableId
                                is CallLogEntry -> item.stableId
                                is ContactItem -> item.stableId
                                is WifiSavedNetwork -> item.stableId
                                is BluetoothPairedDevice -> item.stableId
                                else -> ""
                            }
                            val isChecked = selectedItemIds.contains(itemStableId)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedItemIds = if (isChecked) selectedItemIds - itemStableId else selectedItemIds + itemStableId
                                    },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = bentoCard),
                                border = BorderStroke(1.dp, if (isChecked) bentoCoral.copy(alpha = 0.45f) else bentoCardBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when (item) {
                                        is BluetoothPairedDevice -> {
                                            val btIcon = when (item.deviceCategory) {
                                                "headphones" -> Icons.Default.Headphones
                                                "watch" -> Icons.Default.Watch
                                                "speaker" -> Icons.Default.Speaker
                                                "car" -> Icons.Default.DirectionsCar
                                                "computer" -> Icons.Default.Computer
                                                "phone" -> Icons.Default.Smartphone
                                                else -> Icons.Default.Bluetooth
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(bentoSubCard),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = btIcon,
                                                    contentDescription = null,
                                                    tint = if (item.isConnected) bentoCyan else bentoTextSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = bentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.address,
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = bentoTextMuted
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = bentoSubCard,
                                                        border = BorderStroke(1.dp, bentoCardBorder)
                                                    ) {
                                                        Text(
                                                            text = item.type,
                                                            fontSize = 9.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = bentoTextMuted,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    if (item.isConnected) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = bentoCyan.copy(alpha = 0.15f),
                                                            border = BorderStroke(1.dp, bentoCyan.copy(alpha = 0.4f))
                                                        ) {
                                                            Text(
                                                                text = "● CONECTADO AGORA",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = bentoCyan,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    } else {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = bentoSubCard,
                                                            border = BorderStroke(1.dp, bentoCardBorder)
                                                        ) {
                                                            Text(
                                                                text = "PAREADO",
                                                                fontSize = 9.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = bentoTextSecondary,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        is SmsConversationItem -> {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(bentoSubCard),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Message,
                                                    contentDescription = null,
                                                    tint = bentoCyan,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = item.address,
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = bentoTextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = bentoSubCard,
                                                        border = BorderStroke(1.dp, bentoCardBorder)
                                                    ) {
                                                        Text(
                                                            text = "${item.messageCount} msgs",
                                                            fontSize = 9.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = bentoCyan,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = item.snippet,
                                                    fontSize = 11.5.sp,
                                                    color = bentoTextSecondary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        is CallLogEntry -> {
                                            val (callIcon, callColor, callTypeDesc) = when (item.type) {
                                                CallLog.Calls.INCOMING_TYPE -> Triple(Icons.AutoMirrored.Filled.CallReceived, bentoEmerald, "RECEBIDA")
                                                CallLog.Calls.OUTGOING_TYPE -> Triple(Icons.AutoMirrored.Filled.CallMade, bentoCyan, "EFETUADA")
                                                CallLog.Calls.MISSED_TYPE -> Triple(Icons.AutoMirrored.Filled.CallMissed, bentoCoral, "PERDIDA")
                                                else -> Triple(Icons.Default.Phone, bentoTextMuted, "CHAMADA")
                                            }
                                            val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(item.date))
                                            val durStr = if (item.duration > 0) "${item.duration / 60}m ${item.duration % 60}s" else callTypeDesc

                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(callColor.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(imageVector = callIcon, contentDescription = null, tint = callColor, modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name ?: item.number,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = bentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (item.name != null) item.number else callTypeDesc,
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = bentoTextMuted
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = callColor.copy(alpha = 0.12f),
                                                        border = BorderStroke(1.dp, callColor.copy(alpha = 0.35f))
                                                    ) {
                                                        Text(
                                                            text = callTypeDesc,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = callColor,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = "$dateStr • $durStr",
                                                        fontSize = 10.sp,
                                                        color = bentoTextSecondary
                                                    )
                                                }
                                            }
                                        }

                                        is ContactItem -> {
                                            val firstChar = item.name.firstOrNull { !it.isWhitespace() }
                                            val startsWithLetter = firstChar?.isLetter() == true
                                            val showInitials = item.hasRealName && startsWithLetter

                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(bentoSubCard)
                                                    .border(1.dp, bentoCardBorder, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (showInitials) {
                                                    Text(item.initials, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = bentoCoral)
                                                } else {
                                                    Icon(Icons.Default.Person, contentDescription = null, tint = bentoCoral, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (item.hasRealName) item.name else item.phone,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = bentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (item.hasRealName) item.phone else "Sem nome registrado",
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = bentoTextMuted
                                                )
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = bentoSubCard,
                                                    border = BorderStroke(1.dp, bentoCardBorder)
                                                ) {
                                                    Text(
                                                        text = if (item.hasRealName) "CONTATO REAL" else "NUMERO SALVO",
                                                        fontSize = 8.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = bentoCyan,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }

                                        is WifiSavedNetwork -> {
                                            val isRevealed = revealedWifiPasswords.contains(item.stableId)

                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(bentoSubCard),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Wifi,
                                                    contentDescription = null,
                                                    tint = if (item.isConnected) bentoEmerald else bentoCyan,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = item.ssid,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = bentoTextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (item.isConnected) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = bentoEmerald.copy(alpha = 0.15f),
                                                            border = BorderStroke(1.dp, bentoEmerald.copy(alpha = 0.4f))
                                                        ) {
                                                            Text(
                                                                text = "● CONECTADO",
                                                                fontSize = 8.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = bentoEmerald,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = bentoSubCard,
                                                    border = BorderStroke(1.dp, bentoCardBorder)
                                                ) {
                                                    Text(
                                                        text = item.keyMgmt,
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = bentoTextMuted,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                // Dark Password Container Box
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = bentoBg,
                                                    border = BorderStroke(1.dp, bentoCardBorder),
                                                    modifier = Modifier.clickable {
                                                        if (!item.password.isNullOrBlank()) {
                                                            if (isRevealed) {
                                                                clipboardManager.setText(AnnotatedString(item.password))
                                                                Toast.makeText(context, "Senha de \"${item.ssid}\" copiada!", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                revealedWifiPasswords = revealedWifiPasswords + item.stableId
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Key,
                                                            contentDescription = null,
                                                            tint = if (isRevealed) bentoCoral else bentoTextMuted,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = if (isRevealed) (item.password ?: "Sem senha") else "••••••••••••",
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = if (isRevealed) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (isRevealed) bentoCoral else bentoTextSecondary
                                                        )
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Text(
                                                            text = if (isRevealed) "Ocultar" else "Ver",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = bentoCyan,
                                                            modifier = Modifier.clickable {
                                                                revealedWifiPasswords = if (isRevealed) {
                                                                    revealedWifiPasswords - item.stableId
                                                                } else {
                                                                    revealedWifiPasswords + item.stableId
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    // Checkbox Coral à Direita
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedItemIds = if (checked) selectedItemIds + itemStableId else selectedItemIds - itemStableId
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = bentoCoral,
                                            uncheckedColor = bentoTextMuted
                                        )
                                    )
                                }
                            }
                        }

                        // Paginação sob demanda em blocos de 50
                        if (visibleItems.size < fullList.size) {
                            item(key = "load_more_pagination") {
                                OutlinedButton(
                                    onClick = { pageLimit += 50 },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, bentoCoral.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = bentoCoral, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Carregar mais 50 itens (${fullList.size - visibleItems.size} restantes)", fontSize = 11.5.sp, color = bentoCoral, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 3. HISTÓRICO DE SNAPSHOTS LOCAIS
                // ==========================================
                item(key = "section_local_snapshots") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = bentoCard),
                        border = BorderStroke(1.dp, bentoCardBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FolderZip, contentDescription = null, tint = bentoCoral, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "HISTÓRICO DE SNAPSHOTS LOCAIS (${localCategoryBackups.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = bentoTextPrimary,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            if (localCategoryBackups.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Nenhum snapshot local no dispositivo", fontSize = 12.sp, color = bentoTextMuted)
                                }
                            } else {
                                localCategoryBackups.forEach { entry ->
                                    CategoryBackupItemView(
                                        entry = entry,
                                        category = category,
                                        onRestore = {
                                            targetEntryToRestore = entry
                                            if (category.lowercase() in listOf("wallpaper", "wallpapers") && entry.record.filePath.isNotBlank()) {
                                                coroutineScope.launch {
                                                    val file = File(entry.record.filePath)
                                                    if (file.exists()) {
                                                        val (hBmp, lBmp) = DeviceCategoryInspector.extractWallpaperThumbnails(file)
                                                        val res = DeviceCategoryInspector.applyWallpapers(
                                                            context = context,
                                                            homeBitmap = hBmp,
                                                            lockBitmap = lBmp,
                                                            applyHome = isHomeWallpaperSelected,
                                                            applyLock = isLockWallpaperSelected
                                                        )
                                                        if (res) {
                                                            Toast.makeText(context, "Papéis de parede restaurados com sucesso!", Toast.LENGTH_SHORT).show()
                                                            loadDeviceData()
                                                        } else {
                                                            viewModel.restoreCategoryBackup(entry, category)
                                                        }
                                                    } else {
                                                        viewModel.restoreCategoryBackup(entry, category)
                                                    }
                                                }
                                            } else {
                                                viewModel.restoreCategoryBackup(entry, category)
                                                Toast.makeText(context, "Restaurando backup...", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onDelete = { entryToDelete = entry },
                                        onUploadCloud = {
                                            viewModel.uploadRecordToGoogleDrive(entry.record)
                                            Toast.makeText(context, "Enviando para o Google Drive...", Toast.LENGTH_SHORT).show()
                                        },
                                        accent = bentoCoral
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 4. HISTÓRICO DE SNAPSHOTS NO GOOGLE DRIVE
                // ==========================================
                item(key = "section_cloud_snapshots") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = bentoCard),
                        border = BorderStroke(1.dp, bentoCardBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudQueue, contentDescription = null, tint = bentoCyan, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "SNAPSHOTS NO GOOGLE DRIVE (${cloudCategoryBackups.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = bentoTextPrimary,
                                        fontSize = 12.5.sp
                                    )
                                }
                                if (cloudCategoryBackups.isNotEmpty()) {
                                    TextButton(
                                        onClick = { showDeleteAllDialog = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Apagar todos", fontSize = 11.sp, color = StatusError, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            if (cloudCategoryBackups.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Nenhum backup encontrado no Google Drive", fontSize = 12.sp, color = bentoTextMuted)
                                }
                            } else {
                                cloudCategoryBackups.forEach { entry ->
                                    CategoryBackupItemView(
                                        entry = entry,
                                        category = category,
                                        onRestore = {
                                            targetEntryToRestore = entry
                                            viewModel.restoreCategoryBackup(entry, category)
                                            Toast.makeText(context, "Restaurando backup da nuvem...", Toast.LENGTH_SHORT).show()
                                        },
                                        onDelete = { entryToDelete = entry },
                                        onUploadCloud = null,
                                        accent = bentoCoral
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Diálogo de Visualização de Papel de Parede em Alta Resolução
    if (previewWallpaperBitmap != null) {
        Dialog(onDismissRequest = { previewWallpaperBitmap = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = bentoCard),
                border = BorderStroke(1.dp, bentoCardBorder)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = previewWallpaperTitle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = bentoTextPrimary
                        )
                        IconButton(onClick = { previewWallpaperBitmap = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar", tint = bentoTextSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = previewWallpaperBitmap!!.asImageBitmap(),
                            contentDescription = previewWallpaperTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }

    // BottomSheet de Seleção de Contas do Google Drive
    if (showAccountsBottomSheet) {
        GoogleDriveAccountsBottomSheet(
            viewModel = viewModel,
            onDismiss = { showAccountsBottomSheet = false }
        )
    }

    // Diálogo de confirmação de exclusão individual
    if (entryToDelete != null) {
        val entry = entryToDelete!!
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            containerColor = bentoCard,
            title = { Text("Apagar backup?", fontWeight = FontWeight.Bold, color = bentoTextPrimary) },
            text = { Text("Deseja excluir permanentemente este backup de $categoryTitle?", color = bentoTextSecondary, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = entry
                        entryToDelete = null
                        val mode = if (toDel.hasLocal && toDel.hasDrive) "both" else if (toDel.hasDrive) "cloud" else "local"
                        viewModel.deleteUnifiedBackup(toDel, deleteMode = mode) { _, _ ->
                            refreshAll()
                        }
                        Toast.makeText(context, "Backup removido.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("APAGAR", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text("Cancelar", color = bentoTextSecondary)
                }
            }
        )
    }

    // Diálogo de confirmação para Apagar Todos os backups da nuvem
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            containerColor = bentoCard,
            title = { Text("Apagar todos os backups na nuvem?", fontWeight = FontWeight.Bold, color = bentoTextPrimary) },
            text = { Text("Deseja apagar todos os ${cloudCategoryBackups.size} backups remotos de $categoryTitle?", color = bentoTextSecondary, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllDialog = false
                        coroutineScope.launch {
                            for (c in cloudCategoryBackups) {
                                c.remoteFileId?.let { viewModel.deleteRemoteDriveBackup(it) }
                            }
                            Toast.makeText(context, "Backups da nuvem removidos.", Toast.LENGTH_SHORT).show()
                            refreshAll()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("APAGAR TODOS", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancelar", color = bentoTextSecondary)
                }
            }
        )
    }

    // Diálogo para criar backup (Escolha: Armazenamento interno, Google Drive ou Ambos)
    if (showCreateBackupDialog) {
        AlertDialog(
            onDismissRequest = { showCreateBackupDialog = false },
            containerColor = bentoCard,
            title = { Text("Criar backup de $categoryTitle", fontWeight = FontWeight.Bold, color = bentoTextPrimary) },
            text = {
                Column {
                    Text("Escolha o destino para o novo backup:", color = bentoTextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            showCreateBackupDialog = false
                            viewModel.launchCategoryBackup(
                                category = category,
                                destinationType = BackupDestinationType.SOMENTE_INTERNA,
                                includeHomeWallpaper = isHomeWallpaperSelected,
                                includeLockWallpaper = isLockWallpaperSelected,
                                selectedAppPackages = selectedItemIds.toList(),
                                selectedCategoryItemIds = selectedItemIds
                            )
                            Toast.makeText(context, "Iniciando backup no dispositivo...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = bentoSubCard),
                        border = BorderStroke(1.dp, bentoCoral)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = bentoCoral, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Armazenamento interno", color = bentoTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            showCreateBackupDialog = false
                            viewModel.launchCategoryBackup(
                                category = category,
                                destinationType = BackupDestinationType.SOMENTE_DRIVE,
                                includeHomeWallpaper = isHomeWallpaperSelected,
                                includeLockWallpaper = isLockWallpaperSelected,
                                selectedAppPackages = selectedItemIds.toList(),
                                selectedCategoryItemIds = selectedItemIds
                            )
                            Toast.makeText(context, "Iniciando backup no Google Drive...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = bentoSubCard),
                        border = BorderStroke(1.dp, bentoCyan)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = bentoCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Google Drive", color = bentoTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            showCreateBackupDialog = false
                            viewModel.launchCategoryBackup(
                                category = category,
                                destinationType = BackupDestinationType.INTERNA_E_DRIVE,
                                includeHomeWallpaper = isHomeWallpaperSelected,
                                includeLockWallpaper = isLockWallpaperSelected,
                                selectedAppPackages = selectedItemIds.toList(),
                                selectedCategoryItemIds = selectedItemIds
                            )
                            Toast.makeText(context, "Iniciando backup em ambos os destinos...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = bentoCoral)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ambos (Dispositivo e Nuvem)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateBackupDialog = false }) {
                    Text("Cancelar", color = bentoTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun BentoMetricBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color
) {
    Surface(
        modifier = modifier.wrapContentHeight(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF141A24),
        border = BorderStroke(1.dp, Color(0xFF1F2633))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BentoFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val borderCol = MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) primary.copy(alpha = 0.15f) else surface,
        border = BorderStroke(1.dp, if (isSelected) primary else borderCol),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) primary else TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun BentoWallpaperCard(
    modifier: Modifier = Modifier,
    title: String,
    bitmap: Bitmap?,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val borderCol = MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) primary else borderCol,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) primary else TextPrimary
                )
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = primary,
                        uncheckedColor = TextMuted
                    ),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            val resStr = if (bitmap != null) "${bitmap.width}×${bitmap.height}" else "Padrão Sistema"
            Text(
                text = "$resStr • ARGB_8888",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF64748B)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Wallpaper,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onPreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF1F2633)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Ver Completo",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )
            }
        }
    }
}

@Composable
private fun SelectAllHeader(
    totalCount: Int,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    primaryAccent: Color
) {
    val isAllSelected = totalCount > 0 && selectedCount == totalCount
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$selectedCount de $totalCount selecionados",
            fontSize = 11.5.sp,
            color = Color(0xFF94A3B8),
            fontWeight = FontWeight.Medium
        )
        TextButton(
            onClick = { if (isAllSelected) onDeselectAll() else onSelectAll() },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isAllSelected) "Desmarcar todos" else "Marcar todos",
                fontSize = 11.5.sp,
                color = primaryAccent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CategoryBackupItemView(
    entry: UnifiedBackupEntry,
    category: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onUploadCloud: (() -> Unit)?,
    accent: Color
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val dateStr = remember(entry.record.timestamp) {
        val sdf = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault())
        sdf.format(Date(entry.record.timestamp))
    }

    val contentDesc = remember(entry.record.itemCount, category) {
        when (category.lowercase()) {
            "apps", "aplicativos" -> if (entry.record.includedAppsCount > 0) "${entry.record.includedAppsCount} aplicativos" else "Aplicativos e dados"
            "messages", "sms" -> if (entry.record.itemCount > 0) "${entry.record.itemCount} conversas / mensagens" else "Conversas e mensagens SMS"
            "calls" -> if (entry.record.itemCount > 0) "${entry.record.itemCount} registros" else "Histórico de chamadas"
            "contacts" -> "Catálogo de contatos"
            "wifi" -> "Configurações de redes Wi-Fi"
            "wallpaper", "wallpapers" -> "Papéis de parede (Inicial e Bloqueio)"
            "bluetooth" -> "Dispositivos Bluetooth pareados"
            "dns" -> "Configuração de DNS Privado"
            else -> entry.record.title.ifBlank { "Backup de dados" }
        }
    }

    val wallpaperThumbs = remember(entry.record.filePath, category) {
        if (category.lowercase() in listOf("wallpaper", "wallpapers") && entry.record.filePath.isNotBlank()) {
            val f = File(entry.record.filePath)
            if (f.exists()) DeviceCategoryInspector.extractWallpaperThumbnails(f) else null
        } else null
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF141A24),
        border = BorderStroke(1.dp, Color(0xFF1F2633)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (entry.hasDrive) Icons.Default.CloudQueue else Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contentDesc,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F5F9),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(dateStr, fontSize = 10.5.sp, color = Color(0xFF94A3B8))
                        Text("•", fontSize = 10.5.sp, color = Color(0xFF64748B))
                        Text(FormatUtils.formatBytes(entry.record.sizeBytes), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF00E5FF))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0E121A),
                            border = BorderStroke(1.dp, Color(0xFF1F2633))
                        ) {
                            Text(
                                text = "tar.zst (Nível 3)",
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (entry.hasDrive) Color(0xFF00E5FF).copy(alpha = 0.12f) else Color(0xFF00E676).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, if (entry.hasDrive) Color(0xFF00E5FF).copy(alpha = 0.35f) else Color(0xFF00E676).copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = if (entry.hasDrive) "GOOGLE DRIVE" else "LOCAL",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (entry.hasDrive) Color(0xFF00E5FF) else Color(0xFF00E676),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onRestore,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restaurar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opções", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(Color(0xFF0E121A))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Restaurar", color = Color(0xFFF1F5F9)) },
                                leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null, tint = accent) },
                                onClick = {
                                    menuExpanded = false
                                    onRestore()
                                }
                            )
                            if (onUploadCloud != null && !entry.hasDrive) {
                                DropdownMenuItem(
                                    text = { Text("Backup na nuvem", color = Color(0xFFF1F5F9)) },
                                    leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF00E5FF)) },
                                    onClick = {
                                        menuExpanded = false
                                        onUploadCloud()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Apagar", color = StatusError) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            // Miniaturas de Wallpaper
            if (wallpaperThumbs != null && (wallpaperThumbs.first != null || wallpaperThumbs.second != null)) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (wallpaperThumbs.first != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("Tela inicial", fontSize = 10.sp, color = Color(0xFF64748B))
                            Spacer(modifier = Modifier.height(4.dp))
                            Image(
                                bitmap = wallpaperThumbs.first!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    if (wallpaperThumbs.second != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("Tela de bloqueio", fontSize = 10.sp, color = Color(0xFF64748B))
                            Spacer(modifier = Modifier.height(4.dp))
                            Image(
                                bitmap = wallpaperThumbs.second!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreMessagesScreen(
    conversations: List<SmsConversationItem>,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onBack: () -> Unit,
    onConfirmRestore: () -> Unit,
    accent: Color
) {
    Scaffold(
        containerColor = Color(0xFF05070A),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E121A))
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color(0xFFF1F5F9))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Restaurar Mensagens", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFFF1F5F9))
                    Text("${selectedIds.size} de ${conversations.size} selecionadas", fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
                Button(
                    onClick = onConfirmRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Text("Restaurar", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Selecione as conversas a restaurar:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    TextButton(onClick = { if (selectedIds.size == conversations.size) onDeselectAll() else onSelectAll() }) {
                        Text(if (selectedIds.size == conversations.size) "Desmarcar todos" else "Marcar todos", color = accent, fontSize = 12.sp)
                    }
                }
            }

            items(conversations, key = { it.id }) { conv ->
                val isSelected = selectedIds.contains(conv.id)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSelect(conv.id) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E121A)),
                    border = BorderStroke(1.dp, Color(0xFF1F2633))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect(conv.id) },
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conv.address, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF1F5F9))
                            Text(conv.snippet, fontSize = 11.sp, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreCallsScreen(
    callLogs: List<CallLogEntry>,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onBack: () -> Unit,
    onConfirmRestore: () -> Unit,
    accent: Color
) {
    Scaffold(
        containerColor = Color(0xFF05070A),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E121A))
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color(0xFFF1F5F9))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Restaurar Chamadas", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFFF1F5F9))
                    Text("${selectedIds.size} de ${callLogs.size} selecionadas", fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
                Button(
                    onClick = onConfirmRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Text("Restaurar", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Selecione os registros a restaurar:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    TextButton(onClick = { if (selectedIds.size == callLogs.size) onDeselectAll() else onSelectAll() }) {
                        Text(if (selectedIds.size == callLogs.size) "Desmarcar todos" else "Marcar todos", color = accent, fontSize = 12.sp)
                    }
                }
            }

            items(callLogs, key = { it.id }) { call ->
                val isSelected = selectedIds.contains(call.id)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSelect(call.id) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E121A)),
                    border = BorderStroke(1.dp, Color(0xFF1F2633))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect(call.id) },
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(call.name ?: call.number, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF1F5F9))
                            Text(call.number, fontSize = 10.5.sp, color = Color(0xFF64748B))
                        }
                    }
                }
            }
        }
    }
}
