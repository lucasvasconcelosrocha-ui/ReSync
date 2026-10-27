package com.resync.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.data.model.*
import com.resync.app.engine.ArchiveEngine
import com.resync.app.engine.RestoreEngine
import com.resync.app.engine.RestoreExecutionState
import com.resync.app.engine.RestoreRepository
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.components.RealAppIcon
import com.resync.app.ui.components.CompletionAnimation
import com.resync.app.ui.components.CompletionAnimationSize
import com.resync.app.ui.components.SmoothCurrentItemText
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.ui.components.SmoothPercentText
import com.resync.app.ui.components.SmoothStageText
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.ui.viewmodel.ManifestInspectState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class RestoreCategoryGroup(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color,
    val itemCount: Int,
    val sizeBytes: Long,
    val itemIds: List<String>,
    val isEnabled: Boolean = true
)

data class AppRestorePackageGroup(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val sizeBytes: Long,
    val apkItemIds: List<String>,
    val dataItemIds: List<String>,
    val allItemIds: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreSelectionScreen(
    backup: BackupRecord,
    source: RestoreSource,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onNavigateToActive: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val manifestInspectState by viewModel.manifestInspectState.collectAsStateWithLifecycle()
    val restoreProgress by RestoreRepository.restoreProgress.collectAsStateWithLifecycle()
    val executionState by RestoreRepository.executionState.collectAsStateWithLifecycle()
    val isRestorePaused by RestoreRepository.isRestorePaused.collectAsStateWithLifecycle()
    val lastRestoreLog by RestoreRepository.lastRestoreLog.collectAsStateWithLifecycle()
    val restoreSummary by RestoreRepository.restoreSummary.collectAsStateWithLifecycle()

    val selectedCategoryIds = remember { mutableStateMapOf<String, Boolean>() }
    val selectedItemIds = remember { mutableStateMapOf<String, Boolean>() }
    val selectedCallKeys = remember { mutableStateMapOf<String, Boolean>() }
    val selectedSmsKeys = remember { mutableStateMapOf<String, Boolean>() }
    val selectedContactKeys = remember { mutableStateMapOf<String, Boolean>() }
    val selectedWifiKeys = remember { mutableStateMapOf<String, Boolean>() }
    val selectedBtKeys = remember { mutableStateMapOf<String, Boolean>() }
    var hasInitializedSelection by remember { mutableStateOf(false) }

    var activeCategoryDetail by remember { mutableStateOf<RestoreCategoryGroup?>(null) }
    var detailSearchQuery by remember { mutableStateOf("") }
    var showLogDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (activeCategoryDetail != null) {
            activeCategoryDetail = null
            detailSearchQuery = ""
        } else {
            onBack()
        }
    }

    // Start inspecting manifest (lightweight index download for Drive, local manifest read for Local)
    LaunchedEffect(backup.id, source) {
        val forceCloud = source == RestoreSource.DRIVE
        viewModel.inspectBackup(backup, forceCloudSource = forceCloud)
    }

    val inspectedManifest = (manifestInspectState as? ManifestInspectState.Ready)?.manifest

    val dateFormatted = remember(backup.timestamp) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(backup.timestamp))
    }

    // Build category groups from manifest
    val categoryGroups = remember(inspectedManifest) {
        if (inspectedManifest == null) emptyList()
        else {
            val validItems = inspectedManifest.items.filter { !it.archiveEntryPath.endsWith("metadata.json") }
            val list = mutableListOf<RestoreCategoryGroup>()

            // 1. Aplicativos
            val appItems = validItems.filter {
                it.type == BackupItemType.APP_APK ||
                it.type == BackupItemType.APP_SPLIT_APKS ||
                it.type == BackupItemType.APP_DATA ||
                it.archiveEntryPath.startsWith("apps/")
            }
            if (appItems.isNotEmpty()) {
                val pkgCount = appItems.mapNotNull { it.metadata["packageName"] ?: it.archiveEntryPath.removePrefix("apps/").substringBefore("/").takeIf { s -> s.isNotBlank() } }.distinct().size.coerceAtLeast(1)
                list.add(
                    RestoreCategoryGroup(
                        id = "APPS",
                        title = "Aplicativos",
                        description = "$pkgCount app(s) em backup",
                        icon = Icons.Default.Apps,
                        accentColor = AccentNeonBlue,
                        itemCount = pkgCount,
                        sizeBytes = appItems.sumOf { it.sizeBytes },
                        itemIds = appItems.map { it.id }
                    )
                )
            }

            // 2. SMS (Mensagens)
            val smsItems = validItems.filter { it.type == BackupItemType.SMS || it.archiveEntryPath.startsWith("sms/") }
            if (smsItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "SMS",
                        title = "Mensagens (SMS)",
                        description = "Histórico de mensagens de texto",
                        icon = Icons.Default.Chat,
                        accentColor = AccentCyberPurple,
                        itemCount = smsItems.size,
                        sizeBytes = smsItems.sumOf { it.sizeBytes },
                        itemIds = smsItems.map { it.id }
                    )
                )
            }

            // 3. Registro de chamadas (Histórico de chamadas)
            val callItems = validItems.filter {
                it.type == BackupItemType.CALL_LOG ||
                it.archiveEntryPath.startsWith("calls/") ||
                it.archiveEntryPath.contains("calls_backup")
            }
            if (callItems.isNotEmpty()) {
                val callCount = callItems.sumOf { it.metadata["count"]?.toIntOrNull() ?: 1 }
                list.add(
                    RestoreCategoryGroup(
                        id = "CALL_LOG",
                        title = "Histórico de chamadas",
                        description = "$callCount chamada(s) registrada(s)",
                        icon = Icons.Default.Phone,
                        accentColor = AccentEmeraldGreen,
                        itemCount = callCount,
                        sizeBytes = callItems.sumOf { it.sizeBytes },
                        itemIds = callItems.map { it.id },
                        isEnabled = true
                    )
                )
            } else {
                list.add(
                    RestoreCategoryGroup(
                        id = "CALL_LOG",
                        title = "Histórico de chamadas",
                        description = "Sem histórico de chamadas neste backup",
                        icon = Icons.Default.Phone,
                        accentColor = TextMuted,
                        itemCount = 0,
                        sizeBytes = 0L,
                        itemIds = emptyList(),
                        isEnabled = false
                    )
                )
            }

            // 4. Wi-Fi
            val wifiItems = validItems.filter { it.type == BackupItemType.WIFI_CONFIG || it.archiveEntryPath.startsWith("wifi/") }
            if (wifiItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "WIFI",
                        title = "Wi-Fi",
                        description = "Redes sem fio e credenciais salvas",
                        icon = Icons.Default.Wifi,
                        accentColor = AccentElectricCyan,
                        itemCount = wifiItems.size,
                        sizeBytes = wifiItems.sumOf { it.sizeBytes },
                        itemIds = wifiItems.map { it.id }
                    )
                )
            }

            // 5. Bluetooth
            val btItems = validItems.filter { it.type == BackupItemType.BLUETOOTH || it.archiveEntryPath.startsWith("bluetooth/") }
            if (btItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "BLUETOOTH",
                        title = "Bluetooth",
                        description = "Dispositivos pareados",
                        icon = Icons.Default.Bluetooth,
                        accentColor = AccentSunsetOrange,
                        itemCount = btItems.size,
                        sizeBytes = btItems.sumOf { it.sizeBytes },
                        itemIds = btItems.map { it.id }
                    )
                )
            }

            // 6. Pastas
            val folderItems = validItems.filter { it.type == BackupItemType.FOLDER || it.archiveEntryPath.startsWith("folders/") }
            if (folderItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "FOLDERS",
                        title = "Pastas",
                        description = "${folderItems.size} diretório(s) arquivado(s)",
                        icon = Icons.Default.FolderZip,
                        accentColor = AccentSunsetOrange,
                        itemCount = folderItems.size,
                        sizeBytes = folderItems.sumOf { it.sizeBytes },
                        itemIds = folderItems.map { it.id }
                    )
                )
            }

            // 7. Arquivos
            val fileItems = validItems.filter { it.type == BackupItemType.FILE || it.archiveEntryPath.startsWith("files/") }
            if (fileItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "FILES",
                        title = "Arquivos",
                        description = "${fileItems.size} arquivo(s) e documento(s)",
                        icon = Icons.Default.Description,
                        accentColor = AccentElectricCyan,
                        itemCount = fileItems.size,
                        sizeBytes = fileItems.sumOf { it.sizeBytes },
                        itemIds = fileItems.map { it.id }
                    )
                )
            }

            // 8. DNS
            val dnsItems = validItems.filter { it.type == BackupItemType.DNS_CONFIG || it.archiveEntryPath.startsWith("dns/") }
            if (dnsItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "DNS",
                        title = "DNS",
                        description = "Perfis e servidor de DNS Privado",
                        icon = Icons.Default.Dns,
                        accentColor = primaryAccent,
                        itemCount = dnsItems.size,
                        sizeBytes = dnsItems.sumOf { it.sizeBytes },
                        itemIds = dnsItems.map { it.id }
                    )
                )
            }

            // 9. Papéis de parede
            val wpItems = validItems.filter { it.type == BackupItemType.WALLPAPER || it.type == BackupItemType.WALLPAPER_LOCK || it.archiveEntryPath.startsWith("wallpaper/") }
            if (wpItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "WALLPAPER",
                        title = "Papéis de parede",
                        description = "Tela de início e tela de bloqueio",
                        icon = Icons.Default.Wallpaper,
                        accentColor = AccentElectricCyan,
                        itemCount = wpItems.size,
                        sizeBytes = wpItems.sumOf { it.sizeBytes },
                        itemIds = wpItems.map { it.id }
                    )
                )
            }

            // 10. Contatos
            val contactItems = validItems.filter { it.type == BackupItemType.CONTACTS || it.archiveEntryPath.startsWith("contacts/") }
            if (contactItems.isNotEmpty()) {
                list.add(
                    RestoreCategoryGroup(
                        id = "CONTACTS",
                        title = "Contatos",
                        description = "Catálogo de contatos",
                        icon = Icons.Default.Person,
                        accentColor = AccentEmeraldGreen,
                        itemCount = contactItems.size,
                        sizeBytes = contactItems.sumOf { it.sizeBytes },
                        itemIds = contactItems.map { it.id }
                    )
                )
            }

            list
        }
    }

    // Initialize auto-selection
    LaunchedEffect(categoryGroups) {
        if (!hasInitializedSelection && categoryGroups.isNotEmpty()) {
            categoryGroups.filter { it.isEnabled }.forEach { group ->
                selectedCategoryIds[group.id] = true
                group.itemIds.forEach { itemId ->
                    selectedItemIds[itemId] = true
                }
            }
            hasInitializedSelection = true
        }
    }

    // Calculate real total selected units (distinct apps + other items)
    val totalSelectedUnits = remember(selectedItemIds.toMap(), categoryGroups, inspectedManifest) {
        var count = 0
        if (inspectedManifest != null) {
            val validItems = inspectedManifest.items.filter { !it.archiveEntryPath.endsWith("metadata.json") }
            val appItems = validItems.filter {
                it.type == BackupItemType.APP_APK || it.type == BackupItemType.APP_SPLIT_APKS || it.type == BackupItemType.APP_DATA || it.archiveEntryPath.startsWith("apps/")
            }
            val appPkgs = appItems.groupBy { it.metadata["packageName"] ?: it.archiveEntryPath.removePrefix("apps/").substringBefore("/") }
            val selectedPkgs = appPkgs.count { (_, items) -> items.any { selectedItemIds[it.id] == true } }
            count += selectedPkgs

            val nonAppItems = validItems.filterNot {
                it.type == BackupItemType.APP_APK || it.type == BackupItemType.APP_SPLIT_APKS || it.type == BackupItemType.APP_DATA || it.archiveEntryPath.startsWith("apps/")
            }
            count += nonAppItems.count { selectedItemIds[it.id] == true }
        }
        count
    }

    val isRestoring = executionState != RestoreExecutionState.IDLE && executionState != RestoreExecutionState.COMPLETED && executionState != RestoreExecutionState.ERROR && executionState != RestoreExecutionState.CANCELLED
    val isFinished = executionState == RestoreExecutionState.COMPLETED || executionState == RestoreExecutionState.ERROR || (restoreProgress?.isFinished == true)

    // Evita que o banner de progresso do MainActivity cubra os botões desta tela
    val bannerBottomPadding = LocalBannerHeight.current

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        containerColor = DarkBg,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(DarkBg)) {
                TopAppBar(
                    title = {
                        Text(
                            text = when {
                                isFinished -> "Resultado da Restauração"
                                isRestoring -> "Restaurando Dados"
                                activeCategoryDetail != null -> activeCategoryDetail!!.title
                                else -> "Restauração Seletiva"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (activeCategoryDetail != null) {
                                activeCategoryDetail = null
                                detailSearchQuery = ""
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkBg,
                        titleContentColor = TextPrimary
                    )
                )

                // Subtitle Header
                if (!isRestoring && !isFinished) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (activeCategoryDetail != null) {
                            val curGroup = activeCategoryDetail!!
                            val countSelected = when (curGroup.id) {
                                "APPS" -> {
                                    val appItems = inspectedManifest?.items?.filter { it.id in curGroup.itemIds } ?: emptyList()
                                    val pkgs = appItems.mapNotNull { it.metadata["packageName"] ?: it.archiveEntryPath.removePrefix("apps/").substringBefore("/").takeIf { s -> s.isNotBlank() } }.distinct()
                                    pkgs.count { pkg ->
                                        appItems.filter { (it.metadata["packageName"] ?: it.archiveEntryPath.removePrefix("apps/").substringBefore("/")) == pkg }
                                            .any { selectedItemIds[it.id] == true }
                                    }
                                }
                                "CALL_LOG" -> selectedCallKeys.values.count { it }
                                "SMS" -> selectedSmsKeys.values.count { it }
                                "CONTACTS" -> selectedContactKeys.values.count { it }
                                "WIFI" -> selectedWifiKeys.values.count { it }
                                "BLUETOOTH" -> selectedBtKeys.values.count { it }
                                else -> curGroup.itemIds.count { selectedItemIds[it] == true }
                            }
                            val totalInCat = when (curGroup.id) {
                                "APPS" -> curGroup.itemCount
                                "CALL_LOG" -> selectedCallKeys.size.takeIf { it > 0 } ?: curGroup.itemCount
                                "SMS" -> selectedSmsKeys.size.takeIf { it > 0 } ?: curGroup.itemCount
                                "CONTACTS" -> selectedContactKeys.size.takeIf { it > 0 } ?: curGroup.itemCount
                                "WIFI" -> selectedWifiKeys.size.takeIf { it > 0 } ?: curGroup.itemCount
                                "BLUETOOTH" -> selectedBtKeys.size.takeIf { it > 0 } ?: curGroup.itemCount
                                else -> curGroup.itemIds.size
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "$countSelected de $totalInCat selecionados",
                                    fontSize = 12.sp,
                                    color = primaryAccent,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "• ${source.tag}",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "$totalSelectedUnits selecionado(s)",
                                    fontSize = 12.sp,
                                    color = primaryAccent,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "• ${source.tag}",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Banner de Ações Rápidas: Marcar todos e Desmarcar todos
                    if (categoryGroups.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = StandardCardShape,
                                color = StandardCardBg,
                                border = BorderStroke(1.dp, StandardCardBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clickable {
                                        if (activeCategoryDetail != null) {
                                            when (activeCategoryDetail!!.id) {
                                                "CALL_LOG" -> selectedCallKeys.keys.forEach { selectedCallKeys[it] = true }
                                                "SMS" -> selectedSmsKeys.keys.forEach { selectedSmsKeys[it] = true }
                                                "CONTACTS" -> selectedContactKeys.keys.forEach { selectedContactKeys[it] = true }
                                                "WIFI" -> selectedWifiKeys.keys.forEach { selectedWifiKeys[it] = true }
                                                "BLUETOOTH" -> selectedBtKeys.keys.forEach { selectedBtKeys[it] = true }
                                            }
                                            activeCategoryDetail!!.itemIds.forEach { itemId ->
                                                selectedItemIds[itemId] = true
                                            }
                                            selectedCategoryIds[activeCategoryDetail!!.id] = true
                                        } else {
                                            categoryGroups.filter { it.isEnabled }.forEach { group ->
                                                selectedCategoryIds[group.id] = true
                                                group.itemIds.forEach { itemId ->
                                                    selectedItemIds[itemId] = true
                                                }
                                            }
                                        }
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Marcar todos", color = primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Surface(
                                shape = StandardCardShape,
                                color = StandardCardBg,
                                border = BorderStroke(1.dp, StandardCardBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clickable {
                                        if (activeCategoryDetail != null) {
                                            when (activeCategoryDetail!!.id) {
                                                "CALL_LOG" -> selectedCallKeys.keys.forEach { selectedCallKeys[it] = false }
                                                "SMS" -> selectedSmsKeys.keys.forEach { selectedSmsKeys[it] = false }
                                                "CONTACTS" -> selectedContactKeys.keys.forEach { selectedContactKeys[it] = false }
                                                "WIFI" -> selectedWifiKeys.keys.forEach { selectedWifiKeys[it] = false }
                                                "BLUETOOTH" -> selectedBtKeys.keys.forEach { selectedBtKeys[it] = false }
                                            }
                                            activeCategoryDetail!!.itemIds.forEach { itemId ->
                                                selectedItemIds[itemId] = false
                                            }
                                            selectedCategoryIds[activeCategoryDetail!!.id] = false
                                        } else {
                                            categoryGroups.forEach { group ->
                                                selectedCategoryIds[group.id] = false
                                                group.itemIds.forEach { itemId ->
                                                    selectedItemIds[itemId] = false
                                                }
                                            }
                                        }
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Desmarcar todos", color = StatusError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                if (!isRestoring && !isFinished) {
                    com.resync.app.ui.components.ActiveOperationsBottomBanner(
                        viewModel = viewModel,
                        primaryAccent = primaryAccent,
                        onNavigateToActive = onNavigateToActive,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                if (isFinished || !isRestoring) {
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
                if (isFinished) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showLogDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            border = BorderStroke(1.dp, StandardCardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = StandardCardBg,
                                contentColor = primaryAccent
                            ),
                            shape = StandardCardShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.TextSnippet, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("VER LOG", fontWeight = FontWeight.Bold, color = primaryAccent, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = {
                                RestoreRepository.dismissRestoreStatus()
                                onFinished()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = StandardCardShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "FEITO",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = Color.Black,
                                letterSpacing = 1.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else if (!isRestoring) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (activeCategoryDetail != null) {
                                    activeCategoryDetail = null
                                    detailSearchQuery = ""
                                } else {
                                    onBack()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = StandardCardBg,
                                contentColor = TextPrimary
                            ),
                            border = BorderStroke(1.dp, StandardCardBorder),
                            shape = StandardCardShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("VOLTAR", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = {
                                val allTargetItemIds = selectedItemIds.filter { it.value }.keys
                                if (allTargetItemIds.isNotEmpty()) {
                                    val cacheFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore
                                    val chosenCallKeys = if (selectedCallKeys.isNotEmpty()) {
                                        selectedCallKeys.filter { it.value }.keys
                                    } else null
                                    val chosenSmsKeys = if (selectedSmsKeys.isNotEmpty()) {
                                        selectedSmsKeys.filter { it.value }.keys
                                    } else null
                                    val chosenContactKeys = if (selectedContactKeys.isNotEmpty()) {
                                        selectedContactKeys.filter { it.value }.keys
                                    } else null
                                    viewModel.launchRestore(
                                        record = backup,
                                        selectedItemIds = allTargetItemIds,
                                        downloadedCacheFile = cacheFile,
                                        selectedCallKeys = chosenCallKeys,
                                        selectedSmsKeys = chosenSmsKeys,
                                        selectedContactKeys = chosenContactKeys
                                    )
                                }
                            },
                            enabled = totalSelectedUnits > 0,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryAccent,
                                contentColor = Color.White,
                                disabledContainerColor = primaryAccent.copy(alpha = 0.25f),
                                disabledContentColor = TextMuted
                            ),
                            shape = StandardCardShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, tint = if (totalSelectedUnits > 0) Color.White else TextMuted, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (totalSelectedUnits > 0) "RESTAURAR ($totalSelectedUnits)" else "RESTAURAR",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
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
}
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = 16.dp)
        ) {
            when {
                // TELA 1: ESTADO FINALIZADO (RESULTADO DA RESTAURAÇÃO)
                isFinished -> {
                    val completedItems = restoreProgress?.completedItems ?: emptyList()
                    val hasError = executionState == RestoreExecutionState.ERROR || restoreProgress?.error != null || completedItems.any { !it.success }
                    val statusColor = if (!hasError) StatusSuccess else StatusError
                    val statusIcon = if (!hasError) Icons.Default.CheckCircle else Icons.Default.Error

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (!hasError) {
                                        CompletionAnimation(
                                            size = CompletionAnimationSize.STANDARD,
                                            themeAccent = statusColor,
                                            playKey = "restore_done",
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(CircleShape)
                                                .background(statusColor.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(32.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (!hasError) "Restauração Finalizada" else "Restauração com Falhas ou Parcial",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        color = statusColor
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = restoreProgress?.stage ?: (if (!hasError) "Todos os itens selecionados foram verificados no sistema com êxito." else "Ocorreram falhas durante a restauração ou verificação."),
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (restoreSummary != null) {
                            item {
                                val summary = restoreSummary!!
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Arquivo", color = TextSecondary, fontSize = 12.sp)
                                            Text(summary.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Origem", color = TextSecondary, fontSize = 12.sp)
                                            Text(summary.source, color = TextPrimary, fontSize = 12.sp)
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Itens processados", color = TextSecondary, fontSize = 12.sp)
                                            Text("${summary.totalItems} itens (${summary.successCount} ok${if (summary.failedCount > 0) ", ${summary.failedCount} falhas" else ""})", color = TextPrimary, fontSize = 12.sp)
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Tempo decorrido", color = TextSecondary, fontSize = 12.sp)
                                            val durationStr = if (summary.formattedDurationText.isNotBlank()) {
                                                summary.formattedDurationText
                                            } else {
                                                com.resync.app.util.FormatUtils.formatDurationPtBr(summary.durationSeconds.coerceAtLeast(1))
                                            }
                                            Text(durationStr, color = primaryAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        if (summary.averageSpeedMBps > 0.05) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Velocidade média", color = TextSecondary, fontSize = 12.sp)
                                                Text("%.1f MB/s".format(java.util.Locale.US, summary.averageSpeedMBps), color = TextPrimary, fontSize = 12.sp)
                                            }
                                        }
                                        if (summary.networkUsed.isNotBlank() && summary.networkUsed != "Local") {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Rede utilizada", color = TextSecondary, fontSize = 12.sp)
                                                Text(summary.networkUsed, color = TextPrimary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Botões de Tentar Novamente e Ver Detalhes
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasError) {
                                    Button(
                                        onClick = { RestoreRepository.retryRestore() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Tentar novamente", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = { showLogDialog = true },
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, primaryAccent),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Article, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Ver detalhes/log", color = primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Itens Processados (${completedItems.size}):",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                        }

                        if (completedItems.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Text(
                                        text = "Restauração concluída.",
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        } else {
                            items(completedItems, key = { it.id.ifBlank { it.name } }) { item ->
                                val isSuccess = item.success
                                val isVerified = item.verified
                                val itemColor = if (isSuccess) StatusSuccess else StatusError

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
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
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(itemColor.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.Close,
                                                    contentDescription = null,
                                                    tint = itemColor,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = item.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextPrimary,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (!item.detailCount.isNullOrBlank()) {
                                                    Text(
                                                        text = item.detailCount!!,
                                                        color = if (isSuccess) primaryAccent else StatusError,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                                if (!isSuccess && !item.error.isNullOrBlank()) {
                                                    Text(
                                                        text = "Motivo: ${item.error}",
                                                        color = StatusError,
                                                        fontSize = 11.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = itemColor.copy(alpha = 0.15f),
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = if (isVerified) "Verificado" else (item.statusText ?: if (isSuccess) "Restaurado" else "Falhou"),
                                                color = itemColor,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // TELA 2: RESTAURAÇÃO EM ANDAMENTO (PROCESSO ATIVO)
                isRestoring -> {
                    val p = restoreProgress
                    val pct = ((p?.progressPercent ?: 0f) * 100).toInt().coerceIn(0, 100)
                    val speedMb = (p?.downloadSpeedBytesPerSec ?: 0L).toDouble() / (1024 * 1024)
                    val speedStr = if (speedMb >= 0.1) String.format(Locale.US, "%.1f MB/s", speedMb) else "${(p?.downloadSpeedBytesPerSec ?: 0L) / 1024} KB/s"

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, primaryAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val animCirclePct by animateFloatAsState(
                                        targetValue = p?.progressPercent?.coerceIn(0f, 1f) ?: 0f,
                                        animationSpec = tween(350, easing = LinearOutSlowInEasing),
                                        label = "restore_circle_pct"
                                    )
                                    CircularProgressIndicator(
                                        progress = { animCirclePct },
                                        color = primaryAccent,
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        SmoothStageText(
                                            stage = p?.stage ?: "Restaurando...",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        SmoothCurrentItemText(
                                            text = p?.currentItemName ?: backup.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = TextSecondary,
                                            maxLines = 2,
                                            reservedHeight = 34.dp
                                        )
                                    }
                                    SmoothPercentText(
                                        percent = p?.progressPercent ?: 0f,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = primaryAccent,
                                        fontSize = 15.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                SmoothLinearProgressBar(
                                    progress = p?.progressPercent ?: 0f,
                                    color = primaryAccent,
                                    trackColor = DarkSurface,
                                    height = 6.dp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Origem: ${source.tag}",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    if (p?.downloadSpeedBytesPerSec != null && p.downloadSpeedBytesPerSec > 0L) {
                                        Text(
                                            text = "↓ $speedStr",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (isRestorePaused) RestoreRepository.resumeRestore()
                                            else RestoreRepository.pauseRestore()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, primaryAccent)
                                    ) {
                                        Icon(
                                            imageVector = if (isRestorePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = primaryAccent
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isRestorePaused) "Retomar" else "Pausar", color = primaryAccent, fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = { RestoreRepository.cancelRestore() },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Cancelar", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // TELA 3: SELEÇÃO DRILL-DOWN OU LISTA DE CATEGORIAS
                else -> {
                    when (val state = manifestInspectState) {
                        is ManifestInspectState.InspectingLocal,
                        is ManifestInspectState.DownloadingFromDrive -> {
                            val dlState = state as? ManifestInspectState.DownloadingFromDrive
                            val progressPct = dlState?.progressPercent ?: 0f
                            val stageMsg = dlState?.stage ?: if (source == RestoreSource.DRIVE) "Baixando índice do Google Drive..." else "Lendo manifesto do container..."
                            val percentText = if (progressPct > 0f) " ${(progressPct * 100).toInt()}%" else ""

                            Box(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.widthIn(max = 340.dp)
                                ) {
                                    if (progressPct > 0f) {
                                        CircularProgressIndicator(
                                            progress = { progressPct },
                                            color = primaryAccent,
                                            modifier = Modifier.size(44.dp)
                                        )
                                    } else {
                                        CircularProgressIndicator(color = primaryAccent, modifier = Modifier.size(44.dp))
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "$stageMsg$percentText",
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (source == RestoreSource.DRIVE) "Lendo apenas o índice leve sem baixar o container completo" else "Acessando armazenamento interno diretamente",
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }

                        is ManifestInspectState.Error -> {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.12f)),
                                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Falha ao carregar índice", fontWeight = FontWeight.Bold, color = StatusError, fontSize = 14.sp)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(state.message, color = TextPrimary, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = { viewModel.inspectBackup(backup, forceCloudSource = source == RestoreSource.DRIVE) },
                                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Tentar novamente", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        is ManifestInspectState.Ready,
                        ManifestInspectState.Idle -> {
                            val curGroup = activeCategoryDetail
                            if (curGroup != null && inspectedManifest != null) {
                                // MODO DRILL-DOWN: ITENS INTERNOS DA CATEGORIA SELECIONADA
                                val groupValidItems = inspectedManifest.items.filter { it.id in curGroup.itemIds }

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Campo de busca dentro da categoria
                                    OutlinedTextField(
                                        value = detailSearchQuery,
                                        onValueChange = { detailSearchQuery = it },
                                        placeholder = { Text("Buscar itens em ${curGroup.title}...", fontSize = 12.sp, color = TextMuted) },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextSecondary) },
                                        trailingIcon = {
                                            if (detailSearchQuery.isNotBlank()) {
                                                IconButton(onClick = { detailSearchQuery = "" }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Limpar", modifier = Modifier.size(16.dp), tint = TextSecondary)
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = primaryAccent,
                                            unfocusedBorderColor = DarkCardBorder,
                                            focusedContainerColor = DarkSurfaceVariant,
                                            unfocusedContainerColor = DarkSurfaceVariant
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    if (curGroup.id == "APPS") {
                                        // Agrupamento por App Package
                                        val appPkgs = remember(groupValidItems) {
                                            val map = mutableMapOf<String, MutableList<ManifestItem>>()
                                            groupValidItems.forEach { item ->
                                                val pkg = item.metadata["packageName"] ?: item.archiveEntryPath.removePrefix("apps/").substringBefore("/").takeIf { it.isNotBlank() } ?: "unknown"
                                                map.getOrPut(pkg) { mutableListOf() }.add(item)
                                            }
                                            map.map { (pkg, items) ->
                                                val appName = items.firstNotNullOfOrNull { it.metadata["appName"] } ?: pkg
                                                val metaItem = items.find { it.archiveEntryPath.endsWith("metadata.json") }
                                                val versionName = metaItem?.metadata?.get("versionName")
                                                    ?: items.firstNotNullOfOrNull { it.metadata["versionName"] ?: it.metadata["version"] }
                                                    ?: "1.0"
                                                val size = items.sumOf { it.sizeBytes }
                                                val apkItems = items.filter { it.type == BackupItemType.APP_APK || it.type == BackupItemType.APP_SPLIT_APKS || it.archiveEntryPath.endsWith(".apk") }
                                                val dataItems = items.filter { it.type == BackupItemType.APP_DATA || it.archiveEntryPath.endsWith(".tar") || it.archiveEntryPath.endsWith(".tar.gz") }
                                                AppRestorePackageGroup(
                                                    packageName = pkg,
                                                    appName = appName,
                                                    versionName = versionName,
                                                    sizeBytes = size,
                                                    apkItemIds = apkItems.map { it.id },
                                                    dataItemIds = dataItems.map { it.id },
                                                    allItemIds = items.map { it.id }
                                                )
                                            }
                                        }

                                        val filteredApps = remember(appPkgs, detailSearchQuery) {
                                            if (detailSearchQuery.isBlank()) appPkgs
                                            else appPkgs.filter {
                                                it.appName.contains(detailSearchQuery.trim(), ignoreCase = true) ||
                                                it.packageName.contains(detailSearchQuery.trim(), ignoreCase = true)
                                            }
                                        }

                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                                        ) {
                                            items(filteredApps, key = { it.packageName }) { appGroup ->
                                                val hasApk = appGroup.apkItemIds.isNotEmpty()
                                                val hasData = appGroup.dataItemIds.isNotEmpty()
                                                val isApkSelected = hasApk && appGroup.apkItemIds.any { selectedItemIds[it] == true }
                                                val isDataSelected = hasData && appGroup.dataItemIds.any { selectedItemIds[it] == true }
                                                val isChecked = isApkSelected || isDataSelected
                                                val isAmbos = isApkSelected && isDataSelected
                                                val isSoApk = isApkSelected && !isDataSelected
                                                val isSoDados = !isApkSelected && isDataSelected
                                                val sizeStr = StorageRepository.formatFileSize(appGroup.sizeBytes)

                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .border(1.dp, if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(12.dp)),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                                                    )
                                                ) {
                                                    Column(modifier = Modifier.padding(12.dp)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { target ->
                                                                    appGroup.allItemIds.forEach { selectedItemIds[it] = target }
                                                                },
                                                                colors = CheckboxDefaults.colors(
                                                                    checkedColor = primaryAccent,
                                                                    uncheckedColor = TextMuted
                                                                )
                                                            )

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            RealAppIcon(
                                                                packageName = appGroup.packageName,
                                                                size = 40.dp,
                                                                shapeRadius = 10.dp
                                                            )

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = appGroup.appName,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = TextPrimary,
                                                                        fontSize = 13.sp,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis,
                                                                        modifier = Modifier.weight(1f, fill = false)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(6.dp))
                                                                    Text(
                                                                        text = sizeStr,
                                                                        fontSize = 11.sp,
                                                                        color = TextMuted
                                                                    )
                                                                }
                                                                Text(
                                                                    text = "${appGroup.packageName} • v${appGroup.versionName}",
                                                                    fontSize = 11.sp,
                                                                    color = TextSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }

                                                        // Granular Selectors: Ambos, Só APK, Só Dados
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(start = 44.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            if (hasApk && hasData) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = if (isAmbos) primaryAccent.copy(alpha = 0.25f) else DarkSurface,
                                                                    border = BorderStroke(1.dp, if (isAmbos) primaryAccent else DarkCardBorder),
                                                                    modifier = Modifier.clickable {
                                                                        appGroup.apkItemIds.forEach { selectedItemIds[it] = true }
                                                                        appGroup.dataItemIds.forEach { selectedItemIds[it] = true }
                                                                    }
                                                                ) {
                                                                    Text(
                                                                        "Ambos",
                                                                        fontSize = 10.5.sp,
                                                                        fontWeight = if (isAmbos) FontWeight.Bold else FontWeight.Normal,
                                                                        color = if (isAmbos) primaryAccent else TextSecondary,
                                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                                    )
                                                                }
                                                            }

                                                            if (hasApk) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = if (isSoApk) primaryAccent.copy(alpha = 0.25f) else DarkSurface,
                                                                    border = BorderStroke(1.dp, if (isSoApk) primaryAccent else DarkCardBorder),
                                                                    modifier = Modifier.clickable {
                                                                        appGroup.apkItemIds.forEach { selectedItemIds[it] = true }
                                                                        appGroup.dataItemIds.forEach { selectedItemIds[it] = false }
                                                                    }
                                                                ) {
                                                                    Text(
                                                                        "Só APK",
                                                                        fontSize = 10.5.sp,
                                                                        fontWeight = if (isSoApk) FontWeight.Bold else FontWeight.Normal,
                                                                        color = if (isSoApk) primaryAccent else TextSecondary,
                                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                                    )
                                                                }
                                                            }

                                                            if (hasData) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = if (isSoDados) primaryAccent.copy(alpha = 0.25f) else DarkSurface,
                                                                    border = BorderStroke(1.dp, if (isSoDados) primaryAccent else DarkCardBorder),
                                                                    modifier = Modifier.clickable {
                                                                        appGroup.apkItemIds.forEach { selectedItemIds[it] = false }
                                                                        appGroup.dataItemIds.forEach { selectedItemIds[it] = true }
                                                                    }
                                                                ) {
                                                                    Text(
                                                                        "Só Dados",
                                                                        fontSize = 10.5.sp,
                                                                        fontWeight = if (isSoDados) FontWeight.Bold else FontWeight.Normal,
                                                                        color = if (isSoDados) primaryAccent else TextSecondary,
                                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (curGroup.id == "SMS") {
                                        // Histórico detalhado de SMS
                                        val containerFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore ?: File(backup.filePath)
                                        val smsRecords = remember(containerFile, groupValidItems) {
                                            val smsItem = groupValidItems.firstOrNull { it.type == BackupItemType.SMS && (it.archiveEntryPath.endsWith("sms_backup.json") || it.archiveEntryPath.endsWith(".json")) }
                                                ?: groupValidItems.firstOrNull { it.type == BackupItemType.SMS }
                                            if (containerFile.exists() && smsItem != null) {
                                                runCatching {
                                                    val bytes = ArchiveEngine.readEntryBytes(containerFile, smsItem.archiveEntryPath)
                                                    if (bytes != null) {
                                                        com.google.gson.Gson().fromJson(String(bytes, Charsets.UTF_8), Array<SmsRecord>::class.java).toList()
                                                    } else emptyList()
                                                }.getOrDefault(emptyList())
                                            } else emptyList()
                                        }

                                        LaunchedEffect(smsRecords) {
                                            if (smsRecords.isNotEmpty() && selectedSmsKeys.isEmpty()) {
                                                smsRecords.forEach { sms ->
                                                    selectedSmsKeys["${sms.date}_${sms.address}"] = true
                                                }
                                            }
                                        }

                                        val filteredSms = remember(smsRecords, detailSearchQuery) {
                                            if (detailSearchQuery.isBlank()) smsRecords
                                            else smsRecords.filter {
                                                it.address.contains(detailSearchQuery.trim(), ignoreCase = true) ||
                                                it.body.contains(detailSearchQuery.trim(), ignoreCase = true)
                                            }
                                        }

                                        if (filteredSms.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (smsRecords.isEmpty()) "Nenhuma mensagem SMS encontrada no container." else "Nenhuma mensagem encontrada para a busca.",
                                                    color = TextMuted,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                                            ) {
                                                items(filteredSms, key = { "${it.date}_${it.address}" }) { sms ->
                                                    val smsKey = "${sms.date}_${sms.address}"
                                                    val isChecked = selectedSmsKeys[smsKey] != false
                                                    val isReceived = sms.type == 1
                                                    val typeLabel = if (isReceived) "Recebida" else "Enviada"
                                                    val typeColor = if (isReceived) AccentNeonBlue else AccentEmeraldGreen
                                                    val dateStr = remember(sms.date) {
                                                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(sms.date))
                                                    }

                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .border(1.dp, if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                            .clickable {
                                                                val next = !isChecked
                                                                selectedSmsKeys[smsKey] = next
                                                                val hasAny = selectedSmsKeys.values.any { it }
                                                                curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                selectedCategoryIds[curGroup.id] = hasAny
                                                            },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                                                        )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { next ->
                                                                    selectedSmsKeys[smsKey] = next
                                                                    val hasAny = selectedSmsKeys.values.any { it }
                                                                    curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                    selectedCategoryIds[curGroup.id] = hasAny
                                                                },
                                                                colors = CheckboxDefaults.colors(
                                                                    checkedColor = primaryAccent,
                                                                    uncheckedColor = TextMuted
                                                                )
                                                            )

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(typeColor.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(Icons.Default.Chat, contentDescription = null, tint = typeColor, modifier = Modifier.size(18.dp))
                                                            }

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = sms.address,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = TextPrimary,
                                                                        fontSize = 13.sp,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                    Text(
                                                                        text = dateStr,
                                                                        fontSize = 10.sp,
                                                                        color = TextMuted
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.height(2.dp))
                                                                Text(
                                                                    text = sms.body,
                                                                    fontSize = 11.5.sp,
                                                                    color = TextSecondary,
                                                                    maxLines = 2,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Spacer(modifier = Modifier.height(2.dp))
                                                                Text(
                                                                    text = typeLabel,
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = typeColor
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (curGroup.id == "CALL_LOG") {
                                        // Histórico detalhado de chamadas
                                        val containerFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore ?: File(backup.filePath)
                                        val callRecords = remember(containerFile, groupValidItems) {
                                            val callItem = groupValidItems.firstOrNull()
                                            if (containerFile.exists() && callItem != null) {
                                                runCatching {
                                                    val bytes = ArchiveEngine.readEntryBytes(containerFile, callItem.archiveEntryPath)
                                                    if (bytes != null) {
                                                        com.google.gson.Gson().fromJson(String(bytes, Charsets.UTF_8), Array<CallRecord>::class.java).toList()
                                                    } else emptyList()
                                                }.getOrDefault(emptyList())
                                            } else emptyList()
                                        }

                                        LaunchedEffect(callRecords) {
                                            if (callRecords.isNotEmpty() && selectedCallKeys.isEmpty()) {
                                                callRecords.forEach { call ->
                                                    selectedCallKeys["${call.date}_${call.number}"] = true
                                                }
                                            }
                                        }

                                        val filteredCalls = remember(callRecords, detailSearchQuery) {
                                            if (detailSearchQuery.isBlank()) callRecords
                                            else callRecords.filter {
                                                it.number.contains(detailSearchQuery.trim(), ignoreCase = true) ||
                                                (it.cachedName?.contains(detailSearchQuery.trim(), ignoreCase = true) == true)
                                            }
                                        }

                                        if (filteredCalls.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (callRecords.isEmpty()) "Nenhum registro de chamada encontrado no container." else "Nenhuma chamada encontrada para a busca.",
                                                    color = TextMuted,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                                            ) {
                                                items(filteredCalls, key = { "${it.date}_${it.number}" }) { call ->
                                                    val callKey = "${call.date}_${call.number}"
                                                    val isChecked = selectedCallKeys[callKey] != false
                                                    val callTypeIcon = when (call.type) {
                                                        1 -> Icons.Default.CallReceived
                                                        2 -> Icons.Default.CallMade
                                                        3 -> Icons.Default.CallMissed
                                                        5 -> Icons.Default.PhoneDisabled
                                                        else -> Icons.Default.Call
                                                    }
                                                    val callTypeColor = when (call.type) {
                                                        1 -> AccentEmeraldGreen
                                                        2 -> AccentNeonBlue
                                                        3, 5 -> StatusError
                                                        else -> AccentElectricCyan
                                                    }
                                                    val callTypeLabel = when (call.type) {
                                                        1 -> "Recebida"
                                                        2 -> "Efetuada"
                                                        3 -> "Perdida"
                                                        5 -> "Rejeitada"
                                                        else -> "Chamada"
                                                    }
                                                    val dateFormattedCall = remember(call.date) {
                                                        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(call.date))
                                                    }
                                                    val durationText = if (call.duration > 0) "${call.duration}s" else "0s"

                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .border(1.dp, if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                            .clickable {
                                                                val next = !isChecked
                                                                selectedCallKeys[callKey] = next
                                                                val hasAny = selectedCallKeys.values.any { it }
                                                                curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                selectedCategoryIds[curGroup.id] = hasAny
                                                            },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                                                        )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { next ->
                                                                    selectedCallKeys[callKey] = next
                                                                    val hasAny = selectedCallKeys.values.any { it }
                                                                    curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                    selectedCategoryIds[curGroup.id] = hasAny
                                                                },
                                                                colors = CheckboxDefaults.colors(
                                                                    checkedColor = primaryAccent,
                                                                    uncheckedColor = TextMuted
                                                                )
                                                            )

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(callTypeColor.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(callTypeIcon, contentDescription = null, tint = callTypeColor, modifier = Modifier.size(18.dp))
                                                            }

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = call.cachedName ?: call.number,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = TextPrimary,
                                                                    fontSize = 13.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                if (call.cachedName != null) {
                                                                    Text(
                                                                        text = call.number,
                                                                        fontSize = 11.sp,
                                                                        color = TextSecondary,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                                Text(
                                                                    text = "$callTypeLabel ($durationText) • $dateFormattedCall",
                                                                    fontSize = 10.sp,
                                                                    color = TextMuted
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (curGroup.id == "CONTACTS") {
                                        // Catálogo de Contatos
                                        val containerFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore ?: File(backup.filePath)
                                        val contactRecords = remember(containerFile, groupValidItems) {
                                            val contactItem = groupValidItems.firstOrNull { it.type == BackupItemType.CONTACTS || it.archiveEntryPath.contains("contact") }
                                            if (containerFile.exists() && contactItem != null) {
                                                runCatching {
                                                    val bytes = ArchiveEngine.readEntryBytes(containerFile, contactItem.archiveEntryPath)
                                                    if (bytes != null) {
                                                        val str = String(bytes, Charsets.UTF_8)
                                                        if (str.contains("BEGIN:VCARD", ignoreCase = true)) {
                                                            RestoreEngine.parseVcardContacts(str)
                                                        } else {
                                                            com.google.gson.Gson().fromJson(str, Array<ContactRecord>::class.java).toList()
                                                        }
                                                    } else emptyList()
                                                }.getOrDefault(emptyList())
                                            } else emptyList()
                                        }

                                        LaunchedEffect(contactRecords) {
                                            if (contactRecords.isNotEmpty() && selectedContactKeys.isEmpty()) {
                                                contactRecords.forEach { c ->
                                                    val key = c.id.ifBlank { "${c.displayName}_${c.phoneNumbers.firstOrNull() ?: ""}" }
                                                    selectedContactKeys[key] = true
                                                }
                                            }
                                        }

                                        val filteredContacts = remember(contactRecords, detailSearchQuery) {
                                            if (detailSearchQuery.isBlank()) contactRecords
                                            else contactRecords.filter {
                                                it.displayName.contains(detailSearchQuery.trim(), ignoreCase = true) ||
                                                it.phoneNumbers.any { p -> p.contains(detailSearchQuery.trim()) } ||
                                                it.emails.any { e -> e.contains(detailSearchQuery.trim(), ignoreCase = true) }
                                            }
                                        }

                                        if (filteredContacts.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (contactRecords.isEmpty()) "Nenhum contato encontrado no container." else "Nenhum contato encontrado para a busca.",
                                                    color = TextMuted,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                                            ) {
                                                items(filteredContacts, key = { it.id.ifBlank { "${it.displayName}_${it.phoneNumbers.firstOrNull() ?: ""}" } }) { contact ->
                                                    val contactKey = contact.id.ifBlank { "${contact.displayName}_${contact.phoneNumbers.firstOrNull() ?: ""}" }
                                                    val isChecked = selectedContactKeys[contactKey] != false
                                                    val initial = contact.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .border(1.dp, if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                            .clickable {
                                                                val next = !isChecked
                                                                selectedContactKeys[contactKey] = next
                                                                val hasAny = selectedContactKeys.values.any { it }
                                                                curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                selectedCategoryIds[curGroup.id] = hasAny
                                                            },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                                                        )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { next ->
                                                                    selectedContactKeys[contactKey] = next
                                                                    val hasAny = selectedContactKeys.values.any { it }
                                                                    curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                    selectedCategoryIds[curGroup.id] = hasAny
                                                                },
                                                                colors = CheckboxDefaults.colors(
                                                                    checkedColor = primaryAccent,
                                                                    uncheckedColor = TextMuted
                                                                )
                                                            )

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(CircleShape)
                                                                    .background(AccentEmeraldGreen.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(initial, fontWeight = FontWeight.Bold, color = AccentEmeraldGreen, fontSize = 14.sp)
                                                            }

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = contact.displayName,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = TextPrimary,
                                                                    fontSize = 13.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                if (contact.phoneNumbers.isNotEmpty()) {
                                                                    Text(
                                                                        text = contact.phoneNumbers.joinToString(", "),
                                                                        fontSize = 11.sp,
                                                                        color = TextSecondary,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                                if (contact.emails.isNotEmpty()) {
                                                                    Text(
                                                                        text = contact.emails.joinToString(", "),
                                                                        fontSize = 10.sp,
                                                                        color = TextMuted,
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
                                    } else if (curGroup.id == "WIFI") {
                                        // Redes Wi-Fi salvas
                                        val containerFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore ?: File(backup.filePath)
                                        val wifiNetworks = remember(containerFile, groupValidItems) {
                                            val wifiItem = groupValidItems.firstOrNull { it.type == BackupItemType.WIFI_CONFIG || it.archiveEntryPath.contains("wifi") }
                                            if (containerFile.exists() && wifiItem != null) {
                                                runCatching {
                                                    val bytes = ArchiveEngine.readEntryBytes(containerFile, wifiItem.archiveEntryPath)
                                                    if (bytes != null) {
                                                        val content = String(bytes, Charsets.UTF_8)
                                                        val list = mutableListOf<SavedWifiNetwork>()
                                                        val networkRegex = Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL)
                                                        val ssidRegex = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>")
                                                        val pskRegex = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>")
                                                        val configKeyRegex = Regex("<string name=\"ConfigKey\">&quot;(.*?)&quot;</string>")

                                                        val matches = networkRegex.findAll(content)
                                                        for (match in matches) {
                                                            val block = match.groupValues[1]
                                                            val ssid = ssidRegex.find(block)?.groupValues?.get(1) ?: continue
                                                            val psk = pskRegex.find(block)?.groupValues?.get(1)
                                                            val configKey = configKeyRegex.find(block)?.groupValues?.get(1) ?: ""
                                                            val secType = when {
                                                                configKey.contains("WPA_PSK") || configKey.contains("WPA2") -> "WPA2-PSK"
                                                                configKey.contains("SAE") || configKey.contains("WPA3") -> "WPA3-SAE"
                                                                configKey.contains("NONE") || psk == null -> "Rede Aberta"
                                                                else -> "WPA/WPA2"
                                                            }
                                                            list.add(
                                                                SavedWifiNetwork(
                                                                    ssid = ssid.replace("&quot;", "\""),
                                                                    password = psk?.replace("&quot;", "\""),
                                                                    securityType = secType,
                                                                    lastConnected = "Salva no backup"
                                                                )
                                                            )
                                                        }
                                                        if (list.isEmpty()) {
                                                            Regex("""SSID[=:"]+([^"\r\n]+)""").findAll(content).forEach { m ->
                                                                val s = m.groupValues[1].trim()
                                                                if (s.isNotBlank() && list.none { it.ssid == s }) {
                                                                    list.add(SavedWifiNetwork(s, null, "Wi-Fi Seguro", "Salva no backup"))
                                                                }
                                                            }
                                                        }
                                                        list
                                                    } else emptyList()
                                                }.getOrDefault(emptyList())
                                            } else emptyList()
                                        }

                                        LaunchedEffect(wifiNetworks) {
                                            if (wifiNetworks.isNotEmpty() && selectedWifiKeys.isEmpty()) {
                                                wifiNetworks.forEach { net ->
                                                    selectedWifiKeys[net.ssid] = true
                                                }
                                            }
                                        }

                                        val filteredWifi = remember(wifiNetworks, detailSearchQuery) {
                                            if (detailSearchQuery.isBlank()) wifiNetworks
                                            else wifiNetworks.filter {
                                                it.ssid.contains(detailSearchQuery.trim(), ignoreCase = true) ||
                                                it.securityType.contains(detailSearchQuery.trim(), ignoreCase = true)
                                            }
                                        }

                                        if (filteredWifi.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (wifiNetworks.isEmpty()) "Nenhuma rede Wi-Fi encontrada no container." else "Nenhuma rede Wi-Fi encontrada para a busca.",
                                                    color = TextMuted,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                                            ) {
                                                items(filteredWifi, key = { it.ssid }) { net ->
                                                    val isChecked = selectedWifiKeys[net.ssid] != false

                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .border(1.dp, if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                            .clickable {
                                                                val next = !isChecked
                                                                selectedWifiKeys[net.ssid] = next
                                                                val hasAny = selectedWifiKeys.values.any { it }
                                                                curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                selectedCategoryIds[curGroup.id] = hasAny
                                                            },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                                                        )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { next ->
                                                                    selectedWifiKeys[net.ssid] = next
                                                                    val hasAny = selectedWifiKeys.values.any { it }
                                                                    curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                    selectedCategoryIds[curGroup.id] = hasAny
                                                                },
                                                                colors = CheckboxDefaults.colors(
                                                                    checkedColor = primaryAccent,
                                                                    uncheckedColor = TextMuted
                                                                )
                                                            )

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(AccentElectricCyan.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(Icons.Default.Wifi, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(18.dp))
                                                            }

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = net.ssid,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = TextPrimary,
                                                                    fontSize = 13.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = "${net.securityType} • ${if (!net.password.isNullOrBlank()) "Senha salva" else "Rede aberta"}",
                                                                    fontSize = 11.sp,
                                                                    color = TextSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (curGroup.id == "BLUETOOTH") {
                                        // Dispositivos Bluetooth pareados
                                        val containerFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore ?: File(backup.filePath)
                                        val btDevices = remember(containerFile, groupValidItems) {
                                            val btItem = groupValidItems.firstOrNull { it.type == BackupItemType.BLUETOOTH && it.archiveEntryPath.endsWith(".json") }
                                                ?: groupValidItems.firstOrNull { it.type == BackupItemType.BLUETOOTH }
                                            if (containerFile.exists() && btItem != null) {
                                                runCatching {
                                                    val bytes = ArchiveEngine.readEntryBytes(containerFile, btItem.archiveEntryPath)
                                                    if (bytes != null) {
                                                        val str = String(bytes, Charsets.UTF_8)
                                                        val data = com.google.gson.Gson().fromJson(str, BluetoothBackupData::class.java)
                                                        data.bondedDevices.ifEmpty { data.connectedDevices }
                                                    } else emptyList()
                                                }.getOrDefault(emptyList())
                                            } else emptyList()
                                        }

                                        LaunchedEffect(btDevices) {
                                            if (btDevices.isNotEmpty() && selectedBtKeys.isEmpty()) {
                                                btDevices.forEach { dev ->
                                                    selectedBtKeys[dev.address] = true
                                                }
                                            }
                                        }

                                        val filteredBt = remember(btDevices, detailSearchQuery) {
                                            if (detailSearchQuery.isBlank()) btDevices
                                            else btDevices.filter {
                                                it.name.contains(detailSearchQuery.trim(), ignoreCase = true) ||
                                                it.address.contains(detailSearchQuery.trim(), ignoreCase = true)
                                            }
                                        }

                                        if (filteredBt.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (btDevices.isEmpty()) "Nenhum dispositivo Bluetooth encontrado no container." else "Nenhum dispositivo encontrado para a busca.",
                                                    color = TextMuted,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                                            ) {
                                                items(filteredBt, key = { it.address }) { dev ->
                                                    val isChecked = selectedBtKeys[dev.address] != false

                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .border(1.dp, if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                            .clickable {
                                                                val next = !isChecked
                                                                selectedBtKeys[dev.address] = next
                                                                val hasAny = selectedBtKeys.values.any { it }
                                                                curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                selectedCategoryIds[curGroup.id] = hasAny
                                                            },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                                                        )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { next ->
                                                                    selectedBtKeys[dev.address] = next
                                                                    val hasAny = selectedBtKeys.values.any { it }
                                                                    curGroup.itemIds.forEach { selectedItemIds[it] = hasAny }
                                                                    selectedCategoryIds[curGroup.id] = hasAny
                                                                },
                                                                colors = CheckboxDefaults.colors(
                                                                    checkedColor = primaryAccent,
                                                                    uncheckedColor = TextMuted
                                                                )
                                                            )

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(AccentSunsetOrange.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = AccentSunsetOrange, modifier = Modifier.size(18.dp))
                                                            }

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = dev.name.ifBlank { "Dispositivo Bluetooth" },
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = TextPrimary,
                                                                    fontSize = 13.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = "${dev.address} • ${dev.deviceClass.ifBlank { "Pareado" }}",
                                                                    fontSize = 11.sp,
                                                                    color = TextSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (curGroup.id == "DNS") {
                                        // Configuração de DNS Privado
                                        val containerFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore ?: File(backup.filePath)
                                        val dnsConfig = remember(containerFile, groupValidItems) {
                                            val dnsItem = groupValidItems.firstOrNull { it.type == BackupItemType.DNS_CONFIG || it.archiveEntryPath.contains("dns") }
                                            if (containerFile.exists() && dnsItem != null) {
                                                runCatching {
                                                    val bytes = ArchiveEngine.readEntryBytes(containerFile, dnsItem.archiveEntryPath)
                                                    if (bytes != null) {
                                                        com.google.gson.Gson().fromJson(String(bytes, Charsets.UTF_8), DnsConfig::class.java)
                                                    } else null
                                                }.getOrNull()
                                            } else null
                                        }

                                        val isDnsSelected = curGroup.itemIds.any { selectedItemIds[it] == true }

                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(top = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(1.dp, if (isDnsSelected) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(12.dp)),
                                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                            ) {
                                                Column(modifier = Modifier.padding(16.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(40.dp)
                                                                    .clip(RoundedCornerShape(10.dp))
                                                                    .background(primaryAccent.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(Icons.Default.Dns, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                                                            }
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Column {
                                                                Text("Configuração de DNS Privado", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                                                Text(
                                                                    text = "Modo: ${dnsConfig?.mode ?: "Automático"}",
                                                                    fontSize = 12.sp,
                                                                    color = TextSecondary
                                                                )
                                                            }
                                                        }

                                                        Switch(
                                                            checked = isDnsSelected,
                                                            onCheckedChange = { next ->
                                                                curGroup.itemIds.forEach { selectedItemIds[it] = next }
                                                                selectedCategoryIds[curGroup.id] = next
                                                            },
                                                            colors = SwitchDefaults.colors(
                                                                checkedThumbColor = primaryAccent,
                                                                checkedTrackColor = primaryAccent.copy(alpha = 0.3f)
                                                            )
                                                        )
                                                    }

                                                    if (!dnsConfig?.hostname.isNullOrBlank()) {
                                                        Spacer(modifier = Modifier.height(12.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = DarkSurface,
                                                            border = BorderStroke(1.dp, DarkCardBorder),
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(10.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Column {
                                                                    Text("Servidor de DNS:", fontSize = 10.sp, color = TextMuted)
                                                                    Text(dnsConfig!!.hostname!!, fontWeight = FontWeight.Bold, color = primaryAccent, fontSize = 13.sp)
                                                                }
                                                                IconButton(onClick = {
                                                                    clipboardManager.setText(AnnotatedString(dnsConfig.hostname!!))
                                                                    Toast.makeText(context, "Hostname copiado!", Toast.LENGTH_SHORT).show()
                                                                }) {
                                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = primaryAccent, modifier = Modifier.size(18.dp))
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Outras categorias (Arquivos, Pastas, Papéis de parede)
                                        val filteredItems = remember(groupValidItems, detailSearchQuery) {
                                            if (detailSearchQuery.isBlank()) groupValidItems
                                            else groupValidItems.filter {
                                                it.name.contains(detailSearchQuery.trim(), ignoreCase = true) ||
                                                it.archiveEntryPath.contains(detailSearchQuery.trim(), ignoreCase = true)
                                            }
                                        }

                                        if (filteredItems.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "Nenhum item encontrado nesta categoria.",
                                                    color = TextMuted,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                                            ) {
                                                items(filteredItems, key = { it.id }) { item ->
                                                    val isChecked = selectedItemIds[item.id] == true
                                                    val sizeStr = StorageRepository.formatFileSize(item.sizeBytes)

                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .border(1.dp, if (isChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                            .clickable { selectedItemIds[item.id] = !isChecked },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isChecked) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                                                        )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { selectedItemIds[item.id] = it },
                                                                colors = CheckboxDefaults.colors(
                                                                    checkedColor = primaryAccent,
                                                                    uncheckedColor = TextMuted
                                                                )
                                                            )

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(curGroup.accentColor.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(curGroup.icon, contentDescription = null, tint = curGroup.accentColor, modifier = Modifier.size(20.dp))
                                                            }

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = item.name,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = TextPrimary,
                                                                    fontSize = 13.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = item.archiveEntryPath,
                                                                    fontSize = 10.sp,
                                                                    color = TextSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = sizeStr,
                                                                    fontSize = 10.sp,
                                                                    color = TextMuted
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // MODO CATEGORIAS PRINCIPAIS
                                if (categoryGroups.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Nenhum item encontrado no backup.", color = TextMuted, fontSize = 13.sp)
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                                    ) {
                                        item {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(backup.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                                        Text("$dateFormatted • Toque em qualquer categoria para escolher itens específicos", fontSize = 11.sp, color = TextSecondary)
                                                    }
                                                }
                                            }
                                        }

                                        items(categoryGroups, key = { it.id }) { group ->
                                            val isAnyChecked = group.isEnabled && group.itemIds.any { selectedItemIds[it] == true }
                                            val isAllChecked = group.isEnabled && group.itemIds.isNotEmpty() && group.itemIds.all { selectedItemIds[it] == true }
                                            val sizeFormatted = StorageRepository.formatFileSize(group.sizeBytes)

                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .border(
                                                        1.dp,
                                                        if (isAnyChecked) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder.copy(alpha = if (group.isEnabled) 1f else 0.4f),
                                                        RoundedCornerShape(14.dp)
                                                    )
                                                    .then(
                                                        if (group.isEnabled) {
                                                            Modifier.clickable {
                                                                // Abrir drill-down ao tocar na categoria
                                                                activeCategoryDetail = group
                                                                detailSearchQuery = ""
                                                            }
                                                        } else Modifier
                                                    ),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isAnyChecked) primaryAccent.copy(alpha = 0.06f) else DarkSurfaceVariant.copy(alpha = if (group.isEnabled) 1f else 0.45f)
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isAllChecked,
                                                        enabled = group.isEnabled,
                                                        onCheckedChange = { target ->
                                                            if (group.isEnabled) {
                                                                selectedCategoryIds[group.id] = target
                                                                group.itemIds.forEach { itemId ->
                                                                    selectedItemIds[itemId] = target
                                                                }
                                                            }
                                                        },
                                                        colors = CheckboxDefaults.colors(
                                                            checkedColor = primaryAccent,
                                                            uncheckedColor = TextMuted,
                                                            disabledCheckedColor = TextMuted.copy(alpha = 0.25f),
                                                            disabledUncheckedColor = TextMuted.copy(alpha = 0.2f)
                                                        )
                                                    )

                                                    Spacer(modifier = Modifier.width(6.dp))

                                                    Box(
                                                        modifier = Modifier
                                                            .size(40.dp)
                                                            .clip(CircleShape)
                                                            .background((if (group.isEnabled) group.accentColor else TextMuted).copy(alpha = 0.15f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = group.icon,
                                                            contentDescription = null,
                                                            tint = if (group.isEnabled) group.accentColor else TextMuted,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.width(12.dp))

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = group.title,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (group.isEnabled) TextPrimary else TextSecondary,
                                                            fontSize = 14.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = group.description,
                                                            fontSize = 11.sp,
                                                            color = if (group.isEnabled) TextSecondary else TextMuted,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (group.isEnabled) {
                                                            Text(
                                                                text = "$dateFormatted • $sizeFormatted",
                                                                fontSize = 10.sp,
                                                                color = TextMuted
                                                            )
                                                        } else {
                                                            Text(
                                                                text = "Indisponível neste backup",
                                                                fontSize = 10.sp,
                                                                color = TextMuted.copy(alpha = 0.7f)
                                                            )
                                                        }
                                                    }

                                                    if (group.isEnabled) {
                                                        Icon(
                                                            imageVector = Icons.Default.ChevronRight,
                                                            contentDescription = "Ver itens",
                                                            tint = TextSecondary,
                                                            modifier = Modifier.size(22.dp)
                                                        )
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
            }
        }
    }

    // Modal com Log Detalhado da Restauração
    if (showLogDialog) {
        Dialog(onDismissRequest = { showLogDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, DarkCardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Log Detalhado da Restauração",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 15.sp
                        )
                        IconButton(onClick = { showLogDialog = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val logText = lastRestoreLog ?: "Nenhum log registrado ainda."
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBg)
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = logText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            clipboardManager.setText(AnnotatedString(logText))
                            Toast.makeText(context, "Log copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copiar Log", color = primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { showLogDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Fechar", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
