package com.resync.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.resync.app.R
import com.resync.app.data.model.*
import com.resync.app.engine.CapabilityManager
import com.resync.app.engine.IconCacheManager
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import android.widget.Toast
import com.resync.app.cloud.gdrive.DriveConstants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsListScreen(
    viewModel: MainViewModel,
    onAppClick: (AppInfo) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val apps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingInstalledApps.collectAsState()
    val backupRecords by viewModel.backupRecords.collectAsState()
    val multiAccounts by viewModel.multiCloudAccounts.collectAsState()
    val selectedDriveAccountEmail by viewModel.selectedDriveAccountEmail.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val activeMode by viewModel.activeMode.collectAsState()
    val isRootActive = activeMode == CapabilityManager.ExecutionMode.ROOT || com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
    val backupStatusMap by viewModel.appBackupStatusMap.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Todos") } // "Todos", "Usuário", "Sistema", "Com backup", "Sem backup", "Desatualizados"
    var sortBy by remember { mutableStateOf("Nome A → Z") }
    var showSortMenu by remember { mutableStateOf(false) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedPackages = remember { mutableStateListOf<String>() }

    var showCustomizeBottomSheet by remember { mutableStateOf(false) }
    var showNonRootConfirmDialog by remember { mutableStateOf(false) }
    var pendingCustomBackupLaunch by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var backupToRestore by remember { mutableStateOf<BackupRecord?>(null) }
    var selectedBackupForRestoreScreen by remember { mutableStateOf<BackupRecord?>(null) }
    var appForIndividualRestore by remember { mutableStateOf<AppInfo?>(null) }
    var individualRestorePoint by remember { mutableStateOf<AppBackupPoint?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupRecord?>(null) }

    val playStoreUpdatesResult by viewModel.playStoreUpdatesState.collectAsState()
    val playStoreFilteredUpdates = remember(playStoreUpdatesResult, searchQuery) {
        val result = playStoreUpdatesResult
        if (result is com.resync.app.engine.PlayStoreUpdatesResult.Success) {
            if (searchQuery.isBlank()) result.updates
            else result.updates.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        } else {
            emptyList()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.triggerAppBackupDetection(forceRefreshDrive = true)
    }

    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) {
            viewModel.triggerAppBackupDetection(forceRefreshDrive = false)
        }
    }

    LaunchedEffect(selectedFilter) {
        if (selectedFilter == "Desatualizados") {
            viewModel.refreshPlayStoreUpdates()
        }
    }

    // Sort and filter pipeline
    val processedApps = remember(apps, searchQuery, selectedFilter, sortBy, backupStatusMap) {
        val filtered = apps.filter { app ->
            val matchesSearch = app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            val status = backupStatusMap[app.packageName]
            val hasBackup = status?.hasBackup ?: app.hasBackup
            val matchesFilter = when (selectedFilter) {
                "Usuário" -> !app.isSystemApp
                "Sistema" -> app.isSystemApp
                "Com backup" -> hasBackup
                "Sem backup" -> !hasBackup
                "Desatualizados" -> app.isOutdatedBackup
                else -> true
            }
            matchesSearch && matchesFilter
        }

        when (sortBy) {
            "Nome A → Z" -> filtered.sortedBy { it.appName.lowercase() }
            "Nome Z → A" -> filtered.sortedByDescending { it.appName.lowercase() }
            "Tamanho" -> filtered.sortedByDescending { it.sizeBytes }
            "Data do último backup" -> filtered.sortedByDescending { backupStatusMap[it.packageName]?.latestTimestamp ?: 0L }
            "Data de instalação" -> filtered.sortedByDescending { it.lastInstallTime }
            "Com backup" -> filtered.sortedByDescending { backupStatusMap[it.packageName]?.hasBackup ?: it.hasBackup }
            "Sem backup" -> filtered.sortedBy { backupStatusMap[it.packageName]?.hasBackup ?: it.hasBackup }
            else -> filtered
        }
    }

    val userApps = remember(processedApps) { processedApps.filter { !it.isSystemApp } }
    val systemApps = remember(processedApps) { processedApps.filter { it.isSystemApp } }

    val totalSelectedSize = remember(selectedPackages, apps) {
        apps.filter { selectedPackages.contains(it.packageName) }.sumOf { it.sizeBytes }
    }

    if (appForIndividualRestore != null) {
        val app = appForIndividualRestore!!
        val appPoints = viewModel.getBackupPointsForApp(app.packageName)
        val initialPt = individualRestorePoint ?: appPoints.maxByOrNull { it.timestamp }
        BackHandler {
            appForIndividualRestore = null
            individualRestorePoint = null
        }
        AppIndividualRestoreScreen(
            app = app,
            points = appPoints,
            initialPoint = initialPt,
            viewModel = viewModel,
            onBack = {
                appForIndividualRestore = null
                individualRestorePoint = null
            },
            onFinished = {
                appForIndividualRestore = null
                individualRestorePoint = null
            }
        )
        return
    }

    if (selectedBackupForRestoreScreen != null) {
        val b = selectedBackupForRestoreScreen!!
        val isCloud = !b.driveFileId.isNullOrBlank()
        BackHandler { selectedBackupForRestoreScreen = null }
        RestoreSelectionScreen(
            backup = b,
            source = if (isCloud) RestoreSource.DRIVE else RestoreSource.LOCAL,
            viewModel = viewModel,
            onBack = { selectedBackupForRestoreScreen = null },
            onFinished = { selectedBackupForRestoreScreen = null }
        )
        return
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
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Aplicativos",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val modeLabel = when (activeMode) {
                                CapabilityManager.ExecutionMode.ROOT -> "Root autorizado"
                                CapabilityManager.ExecutionMode.SHIZUKU -> "Shizuku ativo"
                                CapabilityManager.ExecutionMode.STANDARD -> "Acesso padrão"
                            }
                            Text(
                                text = modeLabel,
                                fontSize = 11.sp,
                                color = if (activeMode != CapabilityManager.ExecutionMode.STANDARD) StatusSuccess else TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Sort Button with dropdown
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Ordenar", tint = TextSecondary)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                val sortOptions = listOf(
                                    "Nome A → Z",
                                    "Nome Z → A",
                                    "Tamanho",
                                    "Data de instalação",
                                    "Com backup",
                                    "Sem backup"
                                )
                                sortOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = option,
                                                color = if (sortBy == option) primaryAccent else TextPrimary,
                                                fontWeight = if (sortBy == option) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            sortBy = option
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = {
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) selectedPackages.clear()
                        }) {
                            Icon(
                                imageVector = if (isMultiSelectMode) Icons.Default.ChecklistRtl else Icons.Default.Checklist,
                                contentDescription = "Seleção Múltipla",
                                tint = if (isMultiSelectMode) primaryAccent else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Pesquisar aplicativo ou pacote...", color = TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = TextMuted)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedBorderColor = primaryAccent,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Chips Row (Scrollable horizontally to prevent compression and vertical character wrapping)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("Todos", "Usuário", "Sistema", "Com backup", "Sem backup", "Desatualizados")
                    for (f in filters) {
                        val isSelected = f == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedFilter = f }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = f,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) primaryAccent else TextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Batch Selection Control Row
                if (isMultiSelectMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    val currentVisiblePackages = processedApps.map { it.packageName }
                                    selectedPackages.clear()
                                    selectedPackages.addAll(currentVisiblePackages)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("[Selecionar todos]", fontSize = 12.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                            }

                            TextButton(
                                onClick = { selectedPackages.clear() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("[Desmarcar todos]", fontSize = 12.sp, color = TextMuted)
                            }
                        }

                        Text(
                            text = "${selectedPackages.size}/${processedApps.size} selecionados",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (isMultiSelectMode && selectedPackages.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "${selectedPackages.size} selecionados",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Estimado: ${formatAppSize(totalSelectedSize)}",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    showCustomizeBottomSheet = true
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryAccent),
                                border = BorderStroke(1.dp, primaryAccent),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Personalizar", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    viewModel.launchQuickBackupForApps(selectedPackages.toList())
                                    isMultiSelectMode = false
                                    selectedPackages.clear()
                                    Toast.makeText(context, "Iniciando Backup Rápido...", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Backup Rápido", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isLoadingApps && apps.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = primaryAccent, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Carregando aplicativos...", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            } else if (isLoadingApps) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = primaryAccent, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Atualizando lista de aplicativos...", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }

            if (selectedFilter == "Desatualizados") {
                when (val result = playStoreUpdatesResult) {
                    is com.resync.app.engine.PlayStoreUpdatesResult.Loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = primaryAccent, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Verificando atualizações locais na Play Store...", color = TextSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    is com.resync.app.engine.PlayStoreUpdatesResult.Error -> {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, StatusWarning.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Verificação de Atualizações", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = result.message,
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        lineHeight = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = context.packageManager.getLaunchIntentForPackage("com.android.vending")
                                                    ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store"))
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Abrir Play Store", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    is com.resync.app.engine.PlayStoreUpdatesResult.Success -> {
                        val filteredUpdates = playStoreFilteredUpdates

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${filteredUpdates.size} atualizações disponíveis",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = context.packageManager.getLaunchIntentForPackage("com.android.vending")
                                                ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store"))
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    border = BorderStroke(1.dp, primaryAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = primaryAccent)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Abrir Play Store", fontSize = 11.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (filteredUpdates.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Todos os aplicativos estão atualizados",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 14.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Nenhuma atualização pendente detectada na Play Store.",
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            items(filteredUpdates, key = { "upd_${it.packageName}" }) { upd ->
                                PlayStoreUpdateCard(
                                    update = upd,
                                    primaryAccent = primaryAccent,
                                    onUpdateClick = {
                                        try {
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("market://details?id=${upd.packageName}")
                                            ).apply {
                                                setPackage("com.android.vending")
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            val webIntent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://play.google.com/store/apps/details?id=${upd.packageName}")
                                            )
                                            context.startActivity(webIntent)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    else -> {}
                }
            } else if (selectedFilter == "Todos" && searchQuery.isBlank()) {
                // Section 1: User Apps
                if (userApps.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "APLICATIVOS INSTALADOS",
                            count = userApps.size,
                            accentColor = primaryAccent
                        )
                    }
                    items(userApps, key = { "user_${it.packageName}" }) { app ->
                        val appBackups = remember(backupRecords, app.packageName, app.appName) {
                            backupRecords.filter {
                                it.title.contains(app.appName, ignoreCase = true) ||
                                it.title.contains(app.packageName, ignoreCase = true) ||
                                it.filePath.contains(app.packageName, ignoreCase = true)
                            }
                        }
                        AppListItemCard(
                            app = app,
                            backupStatus = backupStatusMap[app.packageName] ?: AppBackupStatus.none(app.packageName),
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = selectedPackages.contains(app.packageName),
                            accentColor = primaryAccent,
                            appBackups = appBackups,
                            onToggleSelect = {
                                if (selectedPackages.contains(app.packageName)) {
                                    selectedPackages.remove(app.packageName)
                                } else {
                                    selectedPackages.add(app.packageName)
                                }
                            },
                            onClick = {
                                if (isMultiSelectMode) {
                                    if (selectedPackages.contains(app.packageName)) {
                                        selectedPackages.remove(app.packageName)
                                    } else {
                                        selectedPackages.add(app.packageName)
                                    }
                                } else {
                                    viewModel.selectAppForDetail(app)
                                    onAppClick(app)
                                }
                            },
                            onLongClick = {
                                isMultiSelectMode = true
                                if (!selectedPackages.contains(app.packageName)) {
                                    selectedPackages.add(app.packageName)
                                }
                            },
                            onRestoreBackup = { b ->
                                val pt = AppBackupPoint(
                                    backupId = b.id,
                                    backupTitle = b.title,
                                    timestamp = b.timestamp,
                                    isLocal = !b.filePath.isNullOrBlank(),
                                    isDrive = !b.driveFileId.isNullOrBlank(),
                                    filePath = b.filePath,
                                    driveFileId = b.driveFileId,
                                    containerSizeBytes = b.sizeBytes,
                                    appSizeBytes = b.sizeBytes,
                                    isFullPackage = b.includedAppsCount > 1
                                )
                                appForIndividualRestore = app
                                individualRestorePoint = pt
                            },
                            onDeleteBackup = { backupToDelete = it },
                            onUploadBackupToDrive = {
                                viewModel.uploadBackupsToDrive(listOf(it), DriveConstants.ROOT_FOLDER_NAME, selectedDriveAccountEmail)
                                Toast.makeText(context, "Enviando backup para o Drive...", Toast.LENGTH_SHORT).show()
                            },
                            onRestoreAppClick = {
                                val points = viewModel.getBackupPointsForApp(app.packageName)
                                if (points.isNotEmpty()) {
                                    appForIndividualRestore = app
                                    individualRestorePoint = points.maxByOrNull { it.timestamp }
                                } else {
                                    val record = appBackups.maxByOrNull { it.timestamp }
                                    if (record != null) {
                                        val pt = AppBackupPoint(
                                            backupId = record.id,
                                            backupTitle = record.title,
                                            timestamp = record.timestamp,
                                            isLocal = !record.filePath.isNullOrBlank(),
                                            isDrive = !record.driveFileId.isNullOrBlank(),
                                            filePath = record.filePath,
                                            driveFileId = record.driveFileId,
                                            containerSizeBytes = record.sizeBytes,
                                            appSizeBytes = record.sizeBytes,
                                            isFullPackage = record.includedAppsCount > 1
                                        )
                                        appForIndividualRestore = app
                                        individualRestorePoint = pt
                                    } else {
                                        Toast.makeText(context, "Nenhum backup encontrado para este app", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }

                // Section 2: System Apps
                if (systemApps.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        SectionHeader(
                            title = "APLICATIVOS DO SISTEMA",
                            count = systemApps.size,
                            accentColor = TextSecondary
                        )
                    }
                    items(systemApps, key = { "sys_${it.packageName}" }) { app ->
                        val appBackups = remember(backupRecords, app.packageName, app.appName) {
                            backupRecords.filter {
                                it.title.contains(app.appName, ignoreCase = true) ||
                                it.title.contains(app.packageName, ignoreCase = true) ||
                                it.filePath.contains(app.packageName, ignoreCase = true)
                            }
                        }
                        AppListItemCard(
                            app = app,
                            backupStatus = backupStatusMap[app.packageName] ?: AppBackupStatus.none(app.packageName),
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = selectedPackages.contains(app.packageName),
                            accentColor = primaryAccent,
                            appBackups = appBackups,
                            onToggleSelect = {
                                if (selectedPackages.contains(app.packageName)) {
                                    selectedPackages.remove(app.packageName)
                                } else {
                                    selectedPackages.add(app.packageName)
                                }
                            },
                            onClick = {
                                if (isMultiSelectMode) {
                                    if (selectedPackages.contains(app.packageName)) {
                                        selectedPackages.remove(app.packageName)
                                    } else {
                                        selectedPackages.add(app.packageName)
                                    }
                                } else {
                                    viewModel.selectAppForDetail(app)
                                    onAppClick(app)
                                }
                            },
                            onLongClick = {
                                isMultiSelectMode = true
                                if (!selectedPackages.contains(app.packageName)) {
                                    selectedPackages.add(app.packageName)
                                }
                            },
                            onRestoreBackup = { b ->
                                val pt = AppBackupPoint(
                                    backupId = b.id,
                                    backupTitle = b.title,
                                    timestamp = b.timestamp,
                                    isLocal = !b.filePath.isNullOrBlank(),
                                    isDrive = !b.driveFileId.isNullOrBlank(),
                                    filePath = b.filePath,
                                    driveFileId = b.driveFileId,
                                    containerSizeBytes = b.sizeBytes,
                                    appSizeBytes = b.sizeBytes,
                                    isFullPackage = b.includedAppsCount > 1
                                )
                                appForIndividualRestore = app
                                individualRestorePoint = pt
                            },
                            onDeleteBackup = { backupToDelete = it },
                            onUploadBackupToDrive = {
                                viewModel.uploadBackupsToDrive(listOf(it), DriveConstants.ROOT_FOLDER_NAME, selectedDriveAccountEmail)
                                Toast.makeText(context, "Enviando backup para o Drive...", Toast.LENGTH_SHORT).show()
                            },
                            onRestoreAppClick = {
                                val points = viewModel.getBackupPointsForApp(app.packageName)
                                if (points.isNotEmpty()) {
                                    appForIndividualRestore = app
                                    individualRestorePoint = points.maxByOrNull { it.timestamp }
                                } else {
                                    val record = appBackups.maxByOrNull { it.timestamp }
                                    if (record != null) {
                                        val pt = AppBackupPoint(
                                            backupId = record.id,
                                            backupTitle = record.title,
                                            timestamp = record.timestamp,
                                            isLocal = !record.filePath.isNullOrBlank(),
                                            isDrive = !record.driveFileId.isNullOrBlank(),
                                            filePath = record.filePath,
                                            driveFileId = record.driveFileId,
                                            containerSizeBytes = record.sizeBytes,
                                            appSizeBytes = record.sizeBytes,
                                            isFullPackage = record.includedAppsCount > 1
                                        )
                                        appForIndividualRestore = app
                                        individualRestorePoint = pt
                                    } else {
                                        Toast.makeText(context, "Nenhum backup encontrado para este app", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            } else {
                // Filtered or searched view
                item {
                    Text(
                        text = "${processedApps.size} aplicativos encontrados",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(processedApps, key = { it.packageName }) { app ->
                    val appBackups = remember(backupRecords, app.packageName, app.appName) {
                        backupRecords.filter {
                            it.title.contains(app.appName, ignoreCase = true) ||
                            it.title.contains(app.packageName, ignoreCase = true) ||
                            it.filePath.contains(app.packageName, ignoreCase = true)
                        }
                    }
                    AppListItemCard(
                        app = app,
                        backupStatus = backupStatusMap[app.packageName] ?: AppBackupStatus.none(app.packageName),
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = selectedPackages.contains(app.packageName),
                        accentColor = primaryAccent,
                        appBackups = appBackups,
                        onToggleSelect = {
                            if (selectedPackages.contains(app.packageName)) {
                                selectedPackages.remove(app.packageName)
                            } else {
                                selectedPackages.add(app.packageName)
                            }
                        },
                        onClick = {
                            if (isMultiSelectMode) {
                                if (selectedPackages.contains(app.packageName)) {
                                    selectedPackages.remove(app.packageName)
                                } else {
                                    selectedPackages.add(app.packageName)
                                }
                            } else {
                                viewModel.selectAppForDetail(app)
                                onAppClick(app)
                            }
                        },
                        onLongClick = {
                            isMultiSelectMode = true
                            if (!selectedPackages.contains(app.packageName)) {
                                selectedPackages.add(app.packageName)
                            }
                        },
                        onRestoreBackup = { b ->
                            val pt = AppBackupPoint(
                                backupId = b.id,
                                backupTitle = b.title,
                                timestamp = b.timestamp,
                                isLocal = !b.filePath.isNullOrBlank(),
                                isDrive = !b.driveFileId.isNullOrBlank(),
                                filePath = b.filePath,
                                driveFileId = b.driveFileId,
                                containerSizeBytes = b.sizeBytes,
                                appSizeBytes = b.sizeBytes,
                                isFullPackage = b.includedAppsCount > 1
                            )
                            appForIndividualRestore = app
                            individualRestorePoint = pt
                        },
                        onDeleteBackup = { backupToDelete = it },
                        onUploadBackupToDrive = {
                            viewModel.uploadBackupsToDrive(listOf(it), DriveConstants.ROOT_FOLDER_NAME, selectedDriveAccountEmail)
                            Toast.makeText(context, "Enviando backup para o Drive...", Toast.LENGTH_SHORT).show()
                        },
                        onRestoreAppClick = {
                            val points = viewModel.getBackupPointsForApp(app.packageName)
                            if (points.isNotEmpty()) {
                                appForIndividualRestore = app
                                individualRestorePoint = points.maxByOrNull { it.timestamp }
                            } else {
                                val record = appBackups.maxByOrNull { it.timestamp }
                                if (record != null) {
                                    val pt = AppBackupPoint(
                                        backupId = record.id,
                                        backupTitle = record.title,
                                        timestamp = record.timestamp,
                                        isLocal = !record.filePath.isNullOrBlank(),
                                        isDrive = !record.driveFileId.isNullOrBlank(),
                                        filePath = record.filePath,
                                        driveFileId = record.driveFileId,
                                        containerSizeBytes = record.sizeBytes,
                                        appSizeBytes = record.sizeBytes,
                                        isFullPackage = record.includedAppsCount > 1
                                    )
                                    appForIndividualRestore = app
                                    individualRestorePoint = pt
                                } else {
                                    Toast.makeText(context, "Nenhum backup encontrado para este app", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    if (showCustomizeBottomSheet) {
        var includeBaseApk by remember { mutableStateOf(true) }
        var includeSplits by remember { mutableStateOf(true) }
        var includeInternalData by remember { mutableStateOf(true) }
        var includeExternalData by remember { mutableStateOf(false) }
        var includeObb by remember { mutableStateOf(false) }
        var selectedDestination by remember { mutableStateOf("Local") }
        var selectedDriveAccount by remember { mutableStateOf(selectedDriveAccountEmail ?: multiAccounts.firstOrNull()?.email ?: "") }
        var selectedCompression by remember { mutableStateOf("GZIP") }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showCustomizeBottomSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Personalizar Backup (${selectedPackages.size} Apps)",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    text = "Configure opções avançadas de arquivamento, dados e nuvem:",
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(14.dp))
                Text("DADOS A INCLUIR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { includeBaseApk = !includeBaseApk }) {
                    Checkbox(checked = includeBaseApk, onCheckedChange = { includeBaseApk = it }, colors = CheckboxDefaults.colors(checkedColor = primaryAccent))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("APK Base e Splits", fontSize = 13.sp, color = TextPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { includeInternalData = !includeInternalData }) {
                    Checkbox(checked = includeInternalData, onCheckedChange = { includeInternalData = it }, colors = CheckboxDefaults.colors(checkedColor = primaryAccent))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRootActive) "Dados Internos (/data/data)" else "Dados Internos (/data/data) • Requer Root", fontSize = 13.sp, color = if (isRootActive) TextPrimary else TextMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { includeExternalData = !includeExternalData }) {
                    Checkbox(checked = includeExternalData, onCheckedChange = { includeExternalData = it }, colors = CheckboxDefaults.colors(checkedColor = primaryAccent))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dados Externos (/sdcard/Android/data)", fontSize = 13.sp, color = TextPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { includeObb = !includeObb }) {
                    Checkbox(checked = includeObb, onCheckedChange = { includeObb = it }, colors = CheckboxDefaults.colors(checkedColor = primaryAccent))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Arquivos OBB (/sdcard/Android/obb)", fontSize = 13.sp, color = TextPrimary)
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("DESTINO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Local", "Google Drive", "Ambos").forEach { dest ->
                        val isSel = selectedDestination == dest
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSel) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedDestination = dest }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(dest, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) primaryAccent else TextPrimary)
                        }
                    }
                }

                if (selectedDestination != "Local" && multiAccounts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("CONTA DO GOOGLE DRIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                    Spacer(modifier = Modifier.height(6.dp))
                    multiAccounts.forEach { acc ->
                        val isAccSel = selectedDriveAccount == acc.email
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isAccSel) primaryAccent.copy(alpha = 0.15f) else DarkSurfaceVariant)
                                .border(1.dp, if (isAccSel) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedDriveAccount = acc.email }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isAccSel,
                                onClick = { selectedDriveAccount = acc.email },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(acc.email, fontSize = 12.sp, color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("COMPRESSÃO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("GZIP" to "GZIP (Recomendado)", "STORED" to "STORE (Rápido)").forEach { (key, label) ->
                        val isCompSel = selectedCompression == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCompSel) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isCompSel) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedCompression = key }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, fontSize = 11.sp, fontWeight = if (isCompSel) FontWeight.Bold else FontWeight.Normal, color = if (isCompSel) primaryAccent else TextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        val launchAction = { skipInternalData: Boolean ->
                            val opts = AppSelectionOptions(
                                includeBaseApk = includeBaseApk,
                                includeSplits = includeSplits,
                                includeData = (!skipInternalData && includeInternalData) || includeExternalData || includeObb,
                                includeInternalData = if (skipInternalData) false else includeInternalData,
                                includeExternalData = includeExternalData,
                                includeObb = includeObb,
                                includeUserDe = if (skipInternalData) false else includeInternalData
                            )
                            viewModel.launchCustomAppBackup(
                                packages = selectedPackages.toList(),
                                options = opts,
                                destination = selectedDestination,
                                driveAccountEmail = if (selectedDestination != "Local") selectedDriveAccount else null,
                                compression = selectedCompression
                            )
                            showCustomizeBottomSheet = false
                            isMultiSelectMode = false
                            selectedPackages.clear()
                            Toast.makeText(context, "Iniciando Backup Personalizado...", Toast.LENGTH_SHORT).show()
                        }

                        if (!isRootActive && includeInternalData) {
                            pendingCustomBackupLaunch = launchAction
                            showNonRootConfirmDialog = true
                        } else {
                            launchAction(false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("INICIAR BACKUP", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showNonRootConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showNonRootConfirmDialog = false
                pendingCustomBackupLaunch = null
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(28.dp)) },
            title = { Text("Modo Sem Root Detectado", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    text = "O backup de dados internos de aplicativos (/data/data) exige privilégios de Root.\n\nComo o dispositivo está em Modo Sem Root, o ReSync fará o backup apenas dos APKs dos aplicativos selecionados, sem registrar erros de dados.\n\nDeseja prosseguir com o backup dos APKs?",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNonRootConfirmDialog = false
                        val act = pendingCustomBackupLaunch
                        pendingCustomBackupLaunch = null
                        act?.invoke(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Continuar (Apenas APKs)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNonRootConfirmDialog = false
                    pendingCustomBackupLaunch = null
                }) {
                    Text("Cancelar", color = TextMuted, fontSize = 12.sp)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (backupToRestore != null) {
        val b = backupToRestore!!
        AlertDialog(
            onDismissRequest = { backupToRestore = null },
            containerColor = DarkSurface,
            title = { Text("Restaurar Backup?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text("Deseja restaurar o backup de '${b.title}'? O aplicativo e seus dados salvos serão reinstalados e recuperados.", color = TextSecondary, fontSize = 13.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        backupToRestore = null
                        viewModel.launchRestore(b, emptySet())
                        Toast.makeText(context, "Iniciando restauração...", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("RESTAURAR", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { backupToRestore = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    if (backupToDelete != null) {
        val b = backupToDelete!!
        val hasCloudCopy = !b.driveFileId.isNullOrBlank() || b.syncStatus == SyncStatus.SYNCED
        AlertDialog(
            onDismissRequest = { backupToDelete = null },
            containerColor = DarkSurface,
            title = { Text(stringResource(R.string.delete_backup_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(stringResource(R.string.delete_backup_confirm_message), color = TextSecondary, fontSize = 13.sp)
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasCloudCopy) {
                        OutlinedButton(
                            onClick = {
                                val toDelete = b
                                backupToDelete = null
                                viewModel.deleteBackupRecord(toDelete, deleteFromCloud = false) { success, _ ->
                                    if (success) Toast.makeText(context, "Backup excluído do aparelho.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            border = BorderStroke(1.dp, StatusError)
                        ) {
                            Text(stringResource(R.string.delete_local_only), color = StatusError, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val toDelete = b
                                backupToDelete = null
                                viewModel.deleteBackupRecord(toDelete, deleteFromCloud = true) { success, _ ->
                                    if (success) Toast.makeText(context, "Backup excluído do aparelho e da nuvem.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                        ) {
                            Text(stringResource(R.string.delete_local_and_cloud), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                val toDelete = b
                                backupToDelete = null
                                viewModel.deleteBackupRecord(toDelete, deleteFromCloud = false) { success, _ ->
                                    if (success) Toast.makeText(context, "Backup excluído.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                        ) {
                            Text(stringResource(R.string.delete_action), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { backupToDelete = null }) {
                    Text(stringResource(R.string.cancel_action), color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count apps",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppListItemCard(
    app: AppInfo,
    backupStatus: AppBackupStatus,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    accentColor: Color,
    appBackups: List<BackupRecord>,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestoreBackup: (BackupRecord) -> Unit,
    onDeleteBackup: (BackupRecord) -> Unit,
    onUploadBackupToDrive: (BackupRecord) -> Unit,
    onRestoreAppClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val iconCache = remember { IconCacheManager.getInstance(context) }
    var isHistoryExpanded by remember { mutableStateOf(false) }

    val appIconState = produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        value = iconCache.getAppIcon(app.packageName)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (isSelected) accentColor else DarkCardBorder,
                RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = accentColor,
                            uncheckedColor = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Real Icon Display
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (appIconState.value != null) {
                        Image(
                            bitmap = appIconState.value!!,
                            contentDescription = app.appName,
                            modifier = Modifier.size(44.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = accentColor
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatAppSize(app.sizeBytes),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Text(
                        text = "${app.packageName} • v${app.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (app.isSystemApp) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(DarkSurface)
                                    .border(1.dp, DarkCardBorder, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "SISTEMA",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasBackup = backupStatus.hasBackup
                        val backupTextColor = if (hasBackup) StatusSuccess else TextMuted

                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasBackup) {
                                val locIcon = when {
                                    backupStatus.hasLocal && backupStatus.hasDrive -> Icons.Default.CloudDone
                                    backupStatus.hasDrive -> Icons.Default.Cloud
                                    else -> Icons.Default.Smartphone
                                }
                                Icon(
                                    imageVector = locIcon,
                                    contentDescription = null,
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = if (hasBackup) backupStatus.formattedText else "Sem backup",
                                fontSize = 11.sp,
                                color = backupTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        val statusBadgeText = if (hasBackup) "Com backup" else "Sem backup"
                        val statusBadgeColor = if (hasBackup) StatusSuccess else TextMuted

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusBadgeColor.copy(alpha = 0.15f))
                                .then(
                                    if (hasBackup) {
                                        Modifier.clickable { onRestoreAppClick() }
                                    } else Modifier
                                )
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (hasBackup) {
                                    Icon(
                                        Icons.Default.Restore,
                                        contentDescription = "Restaurar",
                                        tint = statusBadgeColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                }
                                Text(
                                    text = statusBadgeText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusBadgeColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Per-app previous backups section
            if (appBackups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(DarkBg.copy(alpha = 0.6f))
                        .clickable { isHistoryExpanded = !isHistoryExpanded }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isHistoryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${appBackups.size} backup(s) anterior(es)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor
                        )
                    }
                    Text(
                        text = if (isHistoryExpanded) "Ocultar" else "Ver histórico",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }

                if (isHistoryExpanded) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        appBackups.forEach { b ->
                            val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(b.timestamp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = DarkBg),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(dateStr, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text("%.2f MB".format(java.util.Locale.US, b.sizeBytes.toDouble() / (1024 * 1024)), fontSize = 11.sp, color = TextSecondary)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { onDeleteBackup(b) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Excluir", fontSize = 10.sp, color = StatusError)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = { onUploadBackupToDrive(b) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = accentColor, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Drive", fontSize = 10.sp, color = accentColor)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Button(
                                            onClick = { onRestoreBackup(b) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                                        ) {
                                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Restaurar", fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

private fun formatAppSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1024) {
        "%.1f GB".format(java.util.Locale("pt", "BR"), mb / 1024)
    } else {
        "%.1f MB".format(java.util.Locale("pt", "BR"), mb)
    }
}

@Composable
private fun PlayStoreUpdateCard(
    update: com.resync.app.engine.PlayStorePendingUpdate,
    primaryAccent: Color,
    onUpdateClick: () -> Unit
) {
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
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val context = LocalContext.current
            val pm = context.packageManager
            val iconBitmap = remember(update.packageName) {
                try {
                    pm.getApplicationIcon(update.packageName).toBitmap().asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = update.appName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(primaryAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Android, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = update.appName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = update.packageName,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${update.installedVersionName} → ${update.targetVersionName}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryAccent
                    )
                    if (update.updateSizeBytes > 0L) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "• ${com.resync.app.engine.StorageRepository.formatFileSize(update.updateSizeBytes)}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onUpdateClick,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Atualizar", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

