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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.data.model.*
import com.resync.app.engine.*
import com.resync.app.ui.components.AppSelectionListComponent
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
            "dns" -> Triple("Backup de configuração DNS", Icons.Default.Dns, listOf("dns", "dns privado", "private dns"))
            else -> Triple("Backup de ${category.replaceFirstChar { it.uppercase() }}", Icons.Default.Category, listOf(category.lowercase()))
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

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(categoryIcon, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = categoryTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Dados do dispositivo e nuvem",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateBackupDialog = true },
                containerColor = primaryAccent,
                contentColor = Color.Black,
                shape = CircleShape
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Criar backup", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Banner de permissão interativo
                if (isPermissionMissing) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Permissão necessária", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = TextPrimary)
                                        Text(
                                            text = when (category.lowercase()) {
                                                "calls" -> "Conceda permissão de Histórico de Chamadas e Contatos para visualizar e salvar suas ligações."
                                                "messages", "sms" -> "Conceda permissão de SMS para visualizar e salvar suas mensagens."
                                                "contacts" -> "Conceda permissão de Contatos para visualizar sua agenda."
                                                else -> "Conceda as permissões necessárias."
                                            },
                                            fontSize = 11.sp,
                                            color = TextSecondary
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
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
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
                            colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(permissionDeniedExplanation!!, fontSize = 11.5.sp, color = TextPrimary)
                            }
                        }
                    }
                }

                // ==========================================
                // DADOS NO CELULAR (COMPLETO COM PAGINAÇÃO E STICKY HEADER)
                // ==========================================
                val isAppsCategory = category.lowercase() in listOf("apps", "aplicativos")
                val isNormalListCategory = category.lowercase() in listOf("messages", "sms", "calls", "contacts", "wifi", "bluetooth")
                val isSpecialCategory = category.lowercase() in listOf("wallpaper", "wallpapers", "dns")

                if (isAppsCategory) {
                    item(key = "card_device_items_apps") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Apps, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "No celular agora (${installedApps.size})",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 14.sp
                                        )
                                    }

                                    Button(
                                        onClick = { showCreateBackupDialog = true },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("Backup", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                                    primaryAccent = primaryAccent,
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
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = primaryAccent, strokeWidth = 2.dp)
                            }
                        }
                    }
                } else if (isSpecialCategory) {
                    // Categoria Especial: Papéis de Parede ou DNS
                    item {
                        val count = if (category.lowercase() in listOf("wallpaper", "wallpapers")) {
                            (if (isHomeWallpaperSelected) 1 else 0) + (if (isLockWallpaperSelected) 1 else 0)
                        } else if (realDnsInfo?.isPrivateDnsActive == true) 1 else 0

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "No celular agora ($count)",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 14.sp
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (category.lowercase() in listOf("wallpaper", "wallpapers")) {
                                            OutlinedButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        isLoadingRealData = true
                                                        realWallpapers = DeviceCategoryInspector.getWallpapers(context)
                                                        isLoadingRealData = false
                                                        Toast.makeText(context, "Papéis de parede atualizados!", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, DarkCardBorder),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp), tint = primaryAccent)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Atualizar", fontSize = 11.sp, color = primaryAccent)
                                            }
                                        }

                                        Button(
                                            onClick = { showCreateBackupDialog = true },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("Backup", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                when (category.lowercase()) {
                                    "dns" -> {
                                        val dns = realDnsInfo
                                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Modo DNS Privado", fontSize = 13.sp, color = TextSecondary)
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (dns?.isPrivateDnsActive == true) AccentEmeraldGreen.copy(alpha = 0.2f) else DarkBg,
                                                    border = BorderStroke(1.dp, if (dns?.isPrivateDnsActive == true) AccentEmeraldGreen else DarkCardBorder)
                                                ) {
                                                    Text(
                                                        text = when (dns?.mode) {
                                                            "hostname" -> "Personalizado"
                                                            "opportunistic" -> "Automático"
                                                            else -> "Desativado"
                                                        },
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (dns?.isPrivateDnsActive == true) AccentEmeraldGreen else TextMuted,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                            if (!dns?.hostname.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("Provedor configurado", fontSize = 12.sp, color = TextMuted)
                                                    Text(dns!!.hostname!!, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                }
                                            }
                                        }
                                    }

                                    "wallpaper", "wallpapers" -> {
                                        val homeWp = realWallpapers.find { it.flag == WallpaperManager.FLAG_SYSTEM }
                                        val lockWp = realWallpapers.find { it.flag == WallpaperManager.FLAG_LOCK }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            InteractiveWallpaperSelector(
                                                modifier = Modifier.weight(1f),
                                                title = "Tela inicial",
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
                                                primaryAccent = primaryAccent
                                            )

                                            InteractiveWallpaperSelector(
                                                modifier = Modifier.weight(1f),
                                                title = "Tela de bloqueio",
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
                                                primaryAccent = primaryAccent
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (isNormalListCategory) {
                    val q = normalize(contactSearchQuery)
                    val fullList = when (category.lowercase()) {
                        "messages", "sms" -> if (q.isBlank()) realSmsList else realSmsList.filter { normalize(it.address).contains(q) || normalize(it.snippet).contains(q) }
                        "calls" -> if (q.isBlank()) realCallsList else realCallsList.filter { normalize(it.name ?: "").contains(q) || it.number.contains(q) }
                        "contacts" -> if (q.isBlank()) realContactsList else realContactsList.filter { normalize(it.name).contains(q) || it.cleanPhone.contains(q) }
                        "wifi" -> if (q.isBlank()) realWifiList else realWifiList.filter { normalize(it.ssid).contains(q) }
                        "bluetooth" -> if (q.isBlank()) realBtList else realBtList.filter { normalize(it.name).contains(q) || it.address.contains(q, ignoreCase = true) }
                        else -> emptyList()
                    }

                    val totalOriginalCount = when (category.lowercase()) {
                        "messages", "sms" -> realSmsList.size
                        "calls" -> realCallsList.size
                        "contacts" -> realContactsList.size
                        "wifi" -> realWifiList.size
                        "bluetooth" -> realBtList.size
                        else -> 0
                    }

                    // Contador selecionados baseado na lista filtrada atual
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

                    // Aviso quando o Bluetooth estiver desligado com botão para ativar
                    if (category.lowercase() == "bluetooth" && !isBluetoothEnabled) {
                        item(key = "banner_bluetooth_disabled") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f)),
                                border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BluetoothDisabled,
                                            contentDescription = null,
                                            tint = StatusWarning,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Bluetooth desativado",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "Ative para sincronizar dispositivos em tempo real.",
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
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
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text(
                                            text = "Ativar Bluetooth",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Card: No celular agora (N) com rolagem independente de 340dp
                    item(key = "card_device_items_$category") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "No celular agora ($totalOriginalCount)",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 14.sp
                                        )
                                    }

                                    Button(
                                        onClick = { showCreateBackupDialog = true },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("Backup", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Campo de busca em tempo real (48 dp, 14 sp centralizado verticalmente)
                                BasicTextField(
                                    value = contactSearchQuery,
                                    onValueChange = { contactSearchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .background(DarkBg, RoundedCornerShape(10.dp))
                                        .border(1.dp, if (contactSearchQuery.isNotEmpty()) primaryAccent else DarkCardBorder, RoundedCornerShape(10.dp)),
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
                                                tint = primaryAccent,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                                if (contactSearchQuery.isEmpty()) {
                                                    Text(
                                                        text = "Pesquisar nesta categoria...",
                                                        fontSize = 14.sp,
                                                        color = TextMuted,
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
                                                        tint = TextMuted,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(12.dp))

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
                                    primaryAccent = primaryAccent
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                                if (fullList.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                        Text("Nenhum registro encontrado.", fontSize = 12.sp, color = TextMuted)
                                    }
                                } else {
                                    val visibleItems = fullList.take(pageLimit)
                                    // Área com heightIn(max = 340 dp) que encolhe quando há poucos itens e rolagem INDEPENDENTE
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 340.dp)
                                    ) {
                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 340.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
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
                                            ) { idx, item ->
                                                if (idx > 0) {
                                                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 4.dp))
                                                }

                                                val itemStableId = when (item) {
                                                    is SmsConversationItem -> item.stableId
                                                    is CallLogEntry -> item.stableId
                                                    is ContactItem -> item.stableId
                                                    is WifiSavedNetwork -> item.stableId
                                                    is BluetoothPairedDevice -> item.stableId
                                                    else -> ""
                                                }
                                                val isChecked = selectedItemIds.contains(itemStableId)

                                                // Item compacto (~54-56 dp) sem card com borda individual
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            selectedItemIds = if (isChecked) selectedItemIds - itemStableId else selectedItemIds + itemStableId
                                                        }
                                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isChecked,
                                                        onCheckedChange = { checked ->
                                                            selectedItemIds = if (checked) selectedItemIds + itemStableId else selectedItemIds - itemStableId
                                                        },
                                                        colors = CheckboxDefaults.colors(checkedColor = primaryAccent),
                                                        modifier = Modifier.size(32.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))

                                                    when (item) {
                                                        is SmsConversationItem -> {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(item.address, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                Text(item.snippet, fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            }
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            val msgText = if (item.messageCount == 1) "1 msg" else "${item.messageCount} msgs"
                                                            Text(msgText, fontSize = 10.5.sp, color = TextSecondary)
                                                        }

                                                        is CallLogEntry -> {
                                                            val (callIcon, callColor, callTypeDesc) = when (item.type) {
                                                                CallLog.Calls.INCOMING_TYPE -> Triple(Icons.AutoMirrored.Filled.CallReceived, AccentEmeraldGreen, "Recebida")
                                                                CallLog.Calls.OUTGOING_TYPE -> Triple(Icons.AutoMirrored.Filled.CallMade, AccentElectricCyan, "Efetuada")
                                                                CallLog.Calls.MISSED_TYPE -> Triple(Icons.AutoMirrored.Filled.CallMissed, StatusError, "Perdida")
                                                                5, 6 -> Triple(Icons.Default.CallEnd, AccentSunsetOrange, "Rejeitada")
                                                                else -> Triple(Icons.Default.Phone, TextMuted, "Chamada")
                                                            }
                                                            val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(item.date))
                                                            val durStr = if (item.duration > 0) "${item.duration / 60}m ${item.duration % 60}s" else callTypeDesc

                                                            Icon(callIcon, contentDescription = null, tint = callColor, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(item.name ?: item.number, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                Text(if (item.name != null) item.number else callTypeDesc, fontSize = 10.5.sp, color = TextMuted)
                                                            }
                                                            Column(horizontalAlignment = Alignment.End) {
                                                                Text(dateStr, fontSize = 10.5.sp, color = TextSecondary)
                                                                Text(durStr, fontSize = 9.5.sp, color = callColor)
                                                            }
                                                        }

                                                        is ContactItem -> {
                                                            val firstChar = item.name.firstOrNull { !it.isWhitespace() }
                                                            val startsWithLetter = firstChar?.isLetter() == true
                                                            val showInitials = item.hasRealName && startsWithLetter

                                                            Box(
                                                                modifier = Modifier.size(34.dp).clip(CircleShape).background(primaryAccent.copy(alpha = 0.18f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                if (showInitials) {
                                                                    Text(item.initials, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                                                                } else {
                                                                    Icon(Icons.Default.Person, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                                                                }
                                                            }
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = if (item.hasRealName) item.name else item.phone,
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = TextPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = if (item.hasRealName) item.phone else "Sem nome",
                                                                    fontSize = 10.5.sp,
                                                                    color = TextMuted,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }

                                                        is WifiSavedNetwork -> {
                                                            var isPasswordRevealed by remember(item.ssid) { mutableStateOf(false) }

                                                            Icon(Icons.Default.Wifi, contentDescription = null, tint = if (item.isConnected) AccentEmeraldGreen else TextSecondary, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(item.ssid, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(item.keyMgmt, fontSize = 10.sp, color = TextMuted)
                                                                    if (!item.password.isNullOrBlank()) {
                                                                        Text(" • ", fontSize = 10.sp, color = TextMuted)
                                                                        Text(
                                                                            text = if (isPasswordRevealed) item.password else "••••••••",
                                                                            fontSize = 10.sp,
                                                                            color = if (isPasswordRevealed) primaryAccent else TextSecondary,
                                                                            fontWeight = if (isPasswordRevealed) FontWeight.Bold else FontWeight.Normal
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            if (!item.password.isNullOrBlank()) {
                                                                IconButton(
                                                                    onClick = {
                                                                        isPasswordRevealed = !isPasswordRevealed
                                                                        if (isPasswordRevealed) {
                                                                            clipboardManager.setText(AnnotatedString(item.password))
                                                                            Toast.makeText(context, "Senha de \"${item.ssid}\" copiada!", Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    },
                                                                    modifier = Modifier.size(32.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = if (isPasswordRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                                        contentDescription = "Revelar senha",
                                                                        tint = if (isPasswordRevealed) primaryAccent else TextMuted,
                                                                        modifier = Modifier.size(16.dp)
                                                                    )
                                                                }
                                                            }
                                                            if (item.isConnected) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = AccentEmeraldGreen.copy(alpha = 0.2f),
                                                                    border = BorderStroke(1.dp, AccentEmeraldGreen.copy(alpha = 0.5f))
                                                                ) {
                                                                    Text("Conectado", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentEmeraldGreen, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                                }
                                                            }
                                                        }

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
                                                            Icon(
                                                                imageVector = btIcon,
                                                                contentDescription = null,
                                                                tint = if (item.isConnected) AccentElectricCyan else primaryAccent,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(10.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = item.name,
                                                                    fontSize = 13.sp,
                                                                    color = TextPrimary,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = "${item.type} • ${item.address}",
                                                                    fontSize = 10.sp,
                                                                    color = TextMuted
                                                                )
                                                            }
                                                            if (item.isConnected) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = AccentElectricCyan.copy(alpha = 0.2f),
                                                                    border = BorderStroke(1.dp, AccentElectricCyan.copy(alpha = 0.5f))
                                                                ) {
                                                                    Text(
                                                                        text = "Conectado agora",
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = AccentElectricCyan,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            } else {
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = DarkBg,
                                                                    border = BorderStroke(1.dp, DarkCardBorder)
                                                                ) {
                                                                    Text(
                                                                        text = "Pareado",
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Medium,
                                                                        color = TextSecondary,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            // Paginação sob demanda em blocos de 50
                                            if (visibleItems.size < fullList.size) {
                                                item(key = "load_more_pagination") {
                                                    OutlinedButton(
                                                        onClick = { pageLimit += 50 },
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                        shape = RoundedCornerShape(10.dp),
                                                        border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f))
                                                    ) {
                                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Carregar mais 50 itens (${fullList.size - visibleItems.size} restantes)", fontSize = 11.5.sp, color = primaryAccent, fontWeight = FontWeight.SemiBold)
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

                // ==========================================
                // CARD: BACKUPS NO DISPOSITIVO (N)
                // ==========================================
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        border = BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Backups no dispositivo (${localCategoryBackups.size})",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            if (localCategoryBackups.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Nenhum backup local no dispositivo", fontSize = 12.sp, color = TextMuted)
                                }
                            } else {
                                localCategoryBackups.forEach { entry ->
                                    CategoryBackupItemView(
                                        entry = entry,
                                        category = category,
                                        onRestore = {
                                            targetEntryToRestore = entry
                                            if (category.lowercase() in listOf("wallpaper", "wallpapers") && entry.record.filePath.isNotBlank()) {
                                                // Restauração de papéis de parede aplicando no WallpaperManager
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
                                        accent = primaryAccent
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // CARD: BACKUPS NA NUVEM (N)
                // ==========================================
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        border = BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudQueue, contentDescription = null, tint = AccentNeonBlue, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Backups na nuvem (${cloudCategoryBackups.size})",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 14.sp
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

                            Spacer(modifier = Modifier.height(10.dp))
                            if (cloudCategoryBackups.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Nenhum backup encontrado no Google Drive", fontSize = 12.sp, color = TextMuted)
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
                                        accent = primaryAccent
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

    // Diálogo de confirmação de exclusão individual
    if (entryToDelete != null) {
        val entry = entryToDelete!!
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            containerColor = DarkSurface,
            title = { Text("Apagar backup?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("Deseja excluir permanentemente este backup de $categoryTitle?", color = TextSecondary, fontSize = 13.sp) },
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
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Diálogo de confirmação para Apagar Todos os backups da nuvem
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            containerColor = DarkSurface,
            title = { Text("Apagar todos os backups na nuvem?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("Deseja apagar todos os ${cloudCategoryBackups.size} backups remotos de $categoryTitle?", color = TextSecondary, fontSize = 13.sp) },
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
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Diálogo para criar backup (Escolha: Armazenamento interno, Google Drive ou Ambos)
    if (showCreateBackupDialog) {
        AlertDialog(
            onDismissRequest = { showCreateBackupDialog = false },
            containerColor = DarkSurface,
            title = { Text("Criar backup de $categoryTitle", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text("Escolha o destino para o novo backup:", color = TextSecondary, fontSize = 13.sp)
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
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        border = BorderStroke(1.dp, primaryAccent)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Armazenamento interno", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        border = BorderStroke(1.dp, AccentNeonBlue)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentNeonBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Google Drive", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ambos (Dispositivo e Nuvem)", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateBackupDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
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
            color = TextSecondary,
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
private fun InteractiveWallpaperSelector(
    modifier: Modifier = Modifier,
    title: String,
    bitmap: Bitmap?,
    isSelected: Boolean,
    onToggle: () -> Unit,
    primaryAccent: Color
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) primaryAccent else DarkCardBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) primaryAccent.copy(alpha = 0.08f) else DarkBg)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) primaryAccent else TextPrimary
                )
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) primaryAccent else Color.Transparent)
                        .border(1.dp, if (isSelected) primaryAccent else TextMuted, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Wallpaper, contentDescription = null, tint = TextMuted, modifier = Modifier.size(32.dp))
                }
            }
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

    // Miniaturas para papéis de parede locais
    val wallpaperThumbs = remember(entry.record.filePath, category) {
        if (category.lowercase() in listOf("wallpaper", "wallpapers") && entry.record.filePath.isNotBlank()) {
            val f = File(entry.record.filePath)
            if (f.exists()) DeviceCategoryInspector.extractWallpaperThumbnails(f) else null
        } else null
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkBg,
        border = BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
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
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dateStr, fontSize = 11.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("•", fontSize = 11.sp, color = TextMuted)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(FormatUtils.formatBytes(entry.record.sizeBytes), fontSize = 11.sp, color = TextMuted)
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Opções", tint = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(DarkSurfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Restaurar", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null, tint = accent) },
                            onClick = {
                                menuExpanded = false
                                onRestore()
                            }
                        )
                        if (onUploadCloud != null && !entry.hasDrive) {
                            DropdownMenuItem(
                                text = { Text("Backup na nuvem", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentNeonBlue) },
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

            // Miniaturas de Wallpaper
            if (wallpaperThumbs != null && (wallpaperThumbs.first != null || wallpaperThumbs.second != null)) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (wallpaperThumbs.first != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("Tela inicial", fontSize = 10.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(4.dp))
                            Image(
                                bitmap = wallpaperThumbs.first!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                    }
                    if (wallpaperThumbs.second != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("Tela de bloqueio", fontSize = 10.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(4.dp))
                            Image(
                                bitmap = wallpaperThumbs.second!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
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
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(DarkSurface).statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Restaurar Mensagens", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Text("${selectedIds.size} de ${conversations.size} selecionadas", fontSize = 11.sp, color = TextSecondary)
                }
                Button(
                    onClick = onConfirmRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Text("Restaurar", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Selecione as conversas a restaurar:", fontSize = 12.sp, color = TextSecondary)
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
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect(conv.id) },
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conv.address, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(conv.snippet, fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(DarkSurface).statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Restaurar Chamadas", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Text("${selectedIds.size} de ${callLogs.size} selecionadas", fontSize = 11.sp, color = TextSecondary)
                }
                Button(
                    onClick = onConfirmRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Text("Restaurar", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Selecione os registros a restaurar:", fontSize = 12.sp, color = TextSecondary)
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
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect(call.id) },
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(call.name ?: call.number, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(call.number, fontSize = 10.5.sp, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}
