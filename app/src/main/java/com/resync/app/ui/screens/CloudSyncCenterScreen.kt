package com.resync.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.shadow
import com.resync.app.util.FormatUtils
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.cloud.gdrive.AutoSyncSettings
import com.resync.app.cloud.gdrive.CloudBackupCategoryHelper
import com.resync.app.cloud.gdrive.DriveConstants
import com.resync.app.cloud.gdrive.DriveFileMetadata
import com.resync.app.data.model.*
import com.resync.app.data.model.RestoreSource
import com.resync.app.data.room.CloudAccount
import com.resync.app.ui.components.UserAvatar
import com.resync.app.ui.components.CompletionAnimation
import com.resync.app.ui.components.CompletionAnimationSize
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.ui.components.cards.InfoRow
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.resync.app.engine.RestoreRepository
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
fun CloudSyncCenterScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onViewLogs: () -> Unit,
    onNavigateToRestore: ((RestoreSource) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lastRestoreLog by RestoreRepository.lastRestoreLog.collectAsState()
    val cloudAccounts by viewModel.cloudAccounts.collectAsState()
    val syncQueue by viewModel.syncQueue.collectAsState()
    val diagnosticResults by viewModel.diagnosticResults.collectAsState()
    val isDiagnosing by viewModel.isDiagnosing.collectAsState()
    val conflicts by viewModel.detectedConflicts.collectAsState()
    val remoteBackups by viewModel.remoteDriveBackups.collectAsState()
    val syncSettings by viewModel.syncSettings.collectAsState()

    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var selectedProviderType by remember { mutableStateOf(CloudProviderType.GOOGLE_DRIVE) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Visão Geral, 1: Fila, 2: Diagnóstico

    var inputEmail by remember { mutableStateOf("") }
    var isPullRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val activeAccountEmail by viewModel.activeAccountEmail.collectAsState()
    val isRefreshingActiveAccount by viewModel.isRefreshingActiveAccount.collectAsState()
    val activeAccountReauthIntent by viewModel.activeAccountReauthIntent.collectAsState()
    val multiAccounts by viewModel.multiCloudAccounts.collectAsState()
    val selectedDriveAccountEmail = activeAccountEmail
    val isGoogleConnected = multiAccounts.isNotEmpty() && activeAccountEmail != null
    val connectedEmail = activeAccountEmail
    val activeRoomAccount = multiAccounts.find { it.email == activeAccountEmail }
    val googleDriveState by viewModel.googleDriveState.collectAsState()
    val driveQuotaState by viewModel.driveQuotaState.collectAsState()
    val remoteBackupsState by viewModel.remoteBackupsState.collectAsState()

    val carouselListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    val lastCrashLog by viewModel.lastCrashLog.collectAsState()
    val currentTemp by com.resync.app.engine.CpuGovernor.currentDeviceTemperature.collectAsState()
    val isThermalThrottled by com.resync.app.engine.CpuGovernor.isThermalThrottled.collectAsState()
    val currentCpuUsage by com.resync.app.engine.CpuGovernor.currentCpuUsagePct.collectAsState()
    val currentThermalStatus by com.resync.app.engine.CpuGovernor.currentThermalStatusInt.collectAsState()
    val activeThreads by com.resync.app.engine.CpuGovernor.activeThreadsCount.collectAsState()
    val backupProgress by viewModel.backupProgress.collectAsState()

    var isFabExpanded by remember { mutableStateOf(false) }
    var showBackupSelectionDialog by remember { mutableStateOf(false) }
    var accountToRemove by remember { mutableStateOf<CloudAccount?>(null) }
    var remoteBackupToDelete by remember { mutableStateOf<DriveFileMetadata?>(null) }
    var isDeletingLabelBackups by remember { mutableStateOf(false) }

    val customDeviceLabel by viewModel.customDeviceLabel.collectAsState()
    val selectedViewLabel by viewModel.selectedViewLabel.collectAsState()
    val savedDeviceLabels by viewModel.savedDeviceLabels.collectAsState()
    val fullDeviceLabel = selectedViewLabel.ifBlank { customDeviceLabel }
    val effectiveViewLabel: String = fullDeviceLabel.trim()

    val remoteList: List<DriveFileMetadata> = (remoteBackupsState as? RemoteBackupsState.Loaded)?.backups ?: remoteBackups
    val labelBackups: List<DriveFileMetadata> = remember(remoteList, effectiveViewLabel) {
        if (effectiveViewLabel.isBlank()) {
            remoteList
        } else {
            remoteList.filter { file ->
                val prop = file.appProperties["deviceLabel"]?.trim()
                if (!prop.isNullOrBlank()) {
                    prop.equals(effectiveViewLabel, ignoreCase = true)
                } else {
                    file.name.contains(effectiveViewLabel, ignoreCase = true)
                }
            }
        }
    }

    var showLabelManagerModal by remember { mutableStateOf(false) }
    var showCreateLabelDialog by remember { mutableStateOf(false) }
    var showRenameLabelDialog by remember { mutableStateOf(false) }
    var showDeleteSpecificLabelDialog by remember { mutableStateOf(false) }
    var labelToRename by remember { mutableStateOf("") }
    var labelToDelete by remember { mutableStateOf("") }
    var selectedCategoryModal by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    val openFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}
            }
            viewModel.uploadMultipleFiles(uris, DriveConstants.ROOT_FOLDER_NAME, selectedDriveAccountEmail)
            coroutineScope.launch {
                val res = snackbarHostState.showSnackbar(
                    message = "${uris.size} arquivo(s) adicionados à fila de envio!",
                    actionLabel = "Ver fila",
                    duration = SnackbarDuration.Short
                )
                if (res == SnackbarResult.ActionPerformed) {
                    selectedTab = 1
                }
            }
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Throwable) {}
            viewModel.uploadFolderTree(uri, DriveConstants.ROOT_FOLDER_NAME, selectedDriveAccountEmail)
            coroutineScope.launch {
                val res = snackbarHostState.showSnackbar(
                    message = "Pasta adicionada à fila de envio!",
                    actionLabel = "Ver fila",
                    duration = SnackbarDuration.Short
                )
                if (res == SnackbarResult.ActionPerformed) {
                    selectedTab = 1
                }
            }
        }
    }

    val isAuthorizingAccount by viewModel.isAuthorizingAccount.collectAsState()
    val driveAuthErrorMessage by viewModel.driveAuthErrorMessage.collectAsState()
    val cloudResetTrigger by viewModel.cloudResetTrigger.collectAsState()

    LaunchedEffect(cloudResetTrigger) {
        if (cloudResetTrigger > 0) {
            isFabExpanded = false
            showBackupSelectionDialog = false
            accountToRemove = null
            selectedTab = 0
            viewModel.clearDriveAuthError()
        }
    }

    val hasInternalBack = isFabExpanded || showBackupSelectionDialog || accountToRemove != null || selectedTab != 0 ||
            showLabelManagerModal || selectedCategoryModal != null || showCreateLabelDialog || showRenameLabelDialog || showDeleteSpecificLabelDialog
    BackHandler(enabled = hasInternalBack) {
        when {
            showCreateLabelDialog -> showCreateLabelDialog = false
            showRenameLabelDialog -> showRenameLabelDialog = false
            showDeleteSpecificLabelDialog -> showDeleteSpecificLabelDialog = false
            selectedCategoryModal != null -> selectedCategoryModal = null
            showLabelManagerModal -> showLabelManagerModal = false
            isFabExpanded -> isFabExpanded = false
            showBackupSelectionDialog -> showBackupSelectionDialog = false
            accountToRemove != null -> accountToRemove = null
            selectedTab != 0 -> selectedTab = 0
        }
    }

    var pendingConsentIntent by remember { mutableStateOf<Intent?>(null) }

    // Activity result launcher for Google Consent Intent
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val emailToUse = inputEmail.ifBlank { selectedDriveAccountEmail ?: "" }
            if (emailToUse.isNotBlank()) {
                viewModel.addDriveAccount(
                    email = emailToUse,
                    onAuthNeeded = { intent ->
                        pendingConsentIntent = intent
                    },
                    onComplete = { addRes ->
                        if (addRes.isSuccess) {
                            Toast.makeText(context, "Google Drive conectado e validado com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        } else {
            Toast.makeText(context, "Autorização OAuth cancelada ou não concluída.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pendingConsentIntent) {
        pendingConsentIntent?.let { intent ->
            pendingConsentIntent = null
            try {
                consentLauncher.launch(intent)
            } catch (t: Throwable) {
                Toast.makeText(context, "Erro ao abrir autorização: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Activity result launcher for Google Account chooser
    val accountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                inputEmail = accountName
                viewModel.addDriveAccount(
                    email = accountName,
                    onAuthNeeded = { intent ->
                        pendingConsentIntent = intent
                    },
                    onComplete = { addRes ->
                        if (addRes.isSuccess) {
                            Toast.makeText(context, "Conta Google Drive conectada com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else {
                Toast.makeText(context, "Nenhuma conta Google foi selecionada.", Toast.LENGTH_SHORT).show()
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(context, "Seleção de conta cancelada.", Toast.LENGTH_SHORT).show()
        }
    }

    val launchAccountPicker = {
        try {
            val intent = viewModel.driveRepository.newChooseAccountIntent()
            accountChooserLauncher.launch(intent)
        } catch (e: Throwable) {
            try {
                val fallbackIntent = viewModel.gdriveProvider.oAuthManager.createChooseAccountIntent()
                accountChooserLauncher.launch(fallbackIntent)
            } catch (e2: Throwable) {
                Toast.makeText(context, "Erro ao abrir seletor de contas: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(activeAccountEmail) {
        if (activeAccountEmail != null) {
            viewModel.refreshActiveAccountData(activeAccountEmail, isManual = false)
            while (isActive) {
                delay(60_000L)
                viewModel.refreshActiveAccountData(activeAccountEmail, isManual = false)
            }
        }
    }

    var hasInitialScrolledToActive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(multiAccounts) {
        if (!hasInitialScrolledToActive && multiAccounts.isNotEmpty() && activeAccountEmail != null) {
            val selectedIdx = multiAccounts.indexOfFirst { it.email == activeAccountEmail }
            if (selectedIdx >= 0) {
                val visibleItems = carouselListState.layoutInfo.visibleItemsInfo
                val isVisible = visibleItems.any { it.index == selectedIdx }
                if (!isVisible && visibleItems.isNotEmpty()) {
                    carouselListState.scrollToItem(selectedIdx)
                }
            }
            hasInitialScrolledToActive = true
        }
    }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Google Drive",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Floating Subtabs styled identically to FloatingBottomNavBar
                val activeQSize = syncQueue.count { it.accountEmail == null || it.accountEmail == activeAccountEmail }
                val subTabs = remember(activeQSize) {
                    listOf(
                        "Visão Geral",
                        "Fila ($activeQSize)",
                        "Diagnóstico"
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = Color.Black.copy(alpha = 0.5f),
                            spotColor = primaryAccent.copy(alpha = 0.25f)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        subTabs.forEachIndexed { index, tabTitle ->
                            val isSelected = selectedTab == index

                            val itemContentColor by animateColorAsState(
                                targetValue = if (isSelected) primaryAccent else TextMuted,
                                animationSpec = tween(durationMillis = 200),
                                label = "subTabContent_$index"
                            )

                            val indicatorBg by animateColorAsState(
                                targetValue = if (isSelected) primaryAccent.copy(alpha = 0.16f) else Color.Transparent,
                                animationSpec = tween(durationMillis = 200),
                                label = "subTabInd_$index"
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(indicatorBg)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true, color = primaryAccent),
                                        onClick = { selectedTab = index }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tabTitle,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = itemContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, end = 16.dp)
            ) {
                AnimatedVisibility(
                    visible = isFabExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                openFilesLauncher.launch(arrayOf("*/*"))
                            },
                            containerColor = DarkSurfaceVariant,
                            contentColor = primaryAccent,
                            elevation = FloatingActionButtonDefaults.elevation(4.dp),
                            icon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
                            text = { Text("Enviar Arquivos", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )

                        ExtendedFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                openFolderLauncher.launch(null)
                            },
                            containerColor = DarkSurfaceVariant,
                            contentColor = primaryAccent,
                            elevation = FloatingActionButtonDefaults.elevation(4.dp),
                            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            text = { Text("Enviar Pasta", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )

                        ExtendedFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                showBackupSelectionDialog = true
                            },
                            containerColor = DarkSurfaceVariant,
                            contentColor = primaryAccent,
                            elevation = FloatingActionButtonDefaults.elevation(4.dp),
                            icon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                            text = { Text("Enviar Backup Existente", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = primaryAccent,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Default.Close else Icons.Default.CloudUpload,
                        contentDescription = "Ações do Google Drive"
                    )
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                coroutineScope.launch {
                    viewModel.refreshCloudData()
                    delay(600)
                    isPullRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (isFabExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isFabExpanded = false }
                )
            }

            when (selectedTab) {
                0 -> {
                    // TAB 0: Visão Geral e Conexão Gratuita
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 120.dp)
                    ) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // OAuth Configuration / Connection Error Card
                        if (googleDriveState == GoogleDriveState.CONNECTION_ERROR) {
                            val errorInfo = driveQuotaState as? DriveQuotaState.Error
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, StatusError.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
                                colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.12f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Erro de Configuração / Autenticação", fontWeight = FontWeight.Bold, color = StatusError, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = errorInfo?.message ?: "Falha ao autenticar com o Google Drive.",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    if (!errorInfo?.technicalCode.isNullOrBlank()) {
                                        Text(
                                            text = "Código técnico: ${errorInfo?.technicalCode}",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = { launchAccountPicker() },
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("TENTAR CONECTAR NOVAMENTE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Session Expired Warning Card
                        if (googleDriveState == GoogleDriveState.SESSION_EXPIRED) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, AccentSunsetOrange.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
                                colors = CardDefaults.cardColors(containerColor = AccentSunsetOrange.copy(alpha = 0.12f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = AccentSunsetOrange, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sessão Expirada", fontWeight = FontWeight.Bold, color = AccentSunsetOrange, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Código técnico: HTTP 401",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = { launchAccountPicker() },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentSunsetOrange),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("RECONECTAR GOOGLE DRIVE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // MULTI-ACCOUNT HORIZONTAL PAGER (UP TO 5 ACCOUNTS)
                        val totalPagerPages = if (multiAccounts.isEmpty()) 1 else multiAccounts.size + (if (multiAccounts.size < 5) 1 else 0)
                        val pagerState = rememberPagerState(pageCount = { totalPagerPages })

                        LaunchedEffect(activeAccountEmail, multiAccounts) {
                            val activeIdx = multiAccounts.indexOfFirst { it.email == activeAccountEmail }
                            if (activeIdx >= 0 && pagerState.currentPage != activeIdx && !pagerState.isScrollInProgress) {
                                pagerState.scrollToPage(activeIdx)
                            }
                        }

                        var maxAccountCardHeightPx by remember { mutableIntStateOf(0) }
                        val density = LocalDensity.current
                        val dynamicCardHeightDp = remember(maxAccountCardHeightPx) {
                            if (maxAccountCardHeightPx > 0) with(density) { maxAccountCardHeightPx.toDp() } else null
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxWidth(),
                            pageSize = PageSize.Fill,
                            pageSpacing = 12.dp
                        ) { pageIndex ->
                            if (pageIndex < multiAccounts.size) {
                                val account = multiAccounts[pageIndex]
                                val isActive = account.email == activeAccountEmail

                                val (driveBadgeText, driveBadgeColor) = when {
                                    isActive && isRefreshingActiveAccount -> Pair("● Atualizando...", AccentElectricCyan)
                                    isActive && (activeAccountReauthIntent != null || googleDriveState == GoogleDriveState.SESSION_EXPIRED) -> Pair("● Reautorizar", AccentSunsetOrange)
                                    isActive && googleDriveState == GoogleDriveState.CONNECTION_ERROR -> Pair("● Erro", StatusError)
                                    isActive && googleDriveState == GoogleDriveState.SYNCING -> Pair("● Sincronizando...", AccentNeonBlue)
                                    else -> Pair("● Conectado", StatusSuccess)
                                }

                                val totalBytes = if (account.quotaTotalBytes > 0) account.quotaTotalBytes else 15L * 1024 * 1024 * 1024
                                val usedBytes = account.quotaUsedBytes
                                val availBytes = maxOf(0L, totalBytes - usedBytes)
                                val rawPercent = (usedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
                                val pctUsed = (rawPercent * 100).toInt()

                                val remoteList = (remoteBackupsState as? RemoteBackupsState.Loaded)?.backups ?: remoteBackups
                                val backupsCountStr = if (isActive) {
                                    when (val b = remoteBackupsState) {
                                        is RemoteBackupsState.Loaded -> "${b.backups.size}"
                                        is RemoteBackupsState.Loading -> "Consultando..."
                                        else -> if (isRefreshingActiveAccount) "Consultando..." else "0"
                                    }
                                } else {
                                    "Consultar"
                                }

                                val activeQueueCount = syncQueue.count { it.accountEmail == null || it.accountEmail == account.email }
                                val activeErrorsCount = syncQueue.count { (it.accountEmail == null || it.accountEmail == account.email) && it.status == QueueStatus.FAILED }

                                val lastSyncStr = when {
                                    account.lastSyncTimestamp > 0 -> FormatUtils.formatDate(account.lastSyncTimestamp)
                                    isActive && remoteList.isNotEmpty() -> FormatUtils.formatDate(remoteList.maxOf { it.modifiedTime })
                                    else -> "Nenhum backup"
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (dynamicCardHeightDp != null) Modifier.height(dynamicCardHeightDp) else Modifier.wrapContentHeight())
                                        .onGloballyPositioned { coords ->
                                            if (coords.size.height > maxAccountCardHeightPx) {
                                                maxAccountCardHeightPx = coords.size.height
                                            }
                                        }
                                        .clip(RoundedCornerShape(16.dp))
                                        .border(
                                            if (isActive) 1.5.dp else 1.dp,
                                            if (isActive) primaryAccent else DarkCardBorder,
                                            RoundedCornerShape(16.dp)
                                        ),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (dynamicCardHeightDp != null) Modifier.fillMaxHeight() else Modifier.wrapContentHeight())
                                            .padding(14.dp)
                                    ) {
                                        // Header row 1: Photo, Name, Email (weight 1f, full text)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            UserAvatar(
                                                photoUrl = account.photoUrl,
                                                displayName = account.name.ifBlank { account.email },
                                                email = account.email,
                                                size = 42.dp
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = account.name.ifBlank { account.email.substringBefore("@") },
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = account.email,
                                                    fontSize = 11.5.sp,
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Header row 2: "Ativa" / "Usar esta conta" on left, "Desconectar" on right (height 36dp pill)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isActive) {
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = primaryAccent.copy(alpha = 0.15f),
                                                    border = BorderStroke(1.dp, primaryAccent),
                                                    modifier = Modifier.height(36.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 14.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(7.dp)
                                                                .clip(CircleShape)
                                                                .background(primaryAccent)
                                                        )
                                                        Text(
                                                            text = "Ativa",
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = primaryAccent,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        viewModel.selectDriveAccount(account.email)
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    border = BorderStroke(1.dp, primaryAccent),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryAccent),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(36.dp)
                                                ) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = primaryAccent)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Usar esta conta", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = primaryAccent, maxLines = 1)
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = { accountToRemove = account },
                                                shape = RoundedCornerShape(50),
                                                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f)),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                                modifier = Modifier.height(36.dp)
                                            ) {
                                                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = StatusError)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Desconectar", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = StatusError, maxLines = 1)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = DarkCardBorder)
                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Storage usage
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${FormatUtils.formatBytes(availBytes)} livres de ${FormatUtils.formatBytes(totalBytes)}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "$pctUsed% usado",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (pctUsed > 85) AccentSunsetOrange else primaryAccent
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { rawPercent },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = if (pctUsed > 85) AccentSunsetOrange else primaryAccent,
                                            trackColor = DarkBg
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = DarkCardBorder)
                                        Spacer(modifier = Modifier.height(8.dp))

                                        CompactCloudInfoRow("Status", driveBadgeText, driveBadgeColor)
                                        CompactCloudInfoRow("Espaço utilizado", FormatUtils.formatBytes(usedBytes))
                                        CompactCloudInfoRow("Espaço disponível", FormatUtils.formatBytes(availBytes), StatusSuccess)
                                        CompactCloudInfoRow("Espaço total", FormatUtils.formatBytes(totalBytes))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(32.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onNavigateToRestore?.invoke(RestoreSource.DRIVE) }
                                                .padding(horizontal = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Backups no Drive", fontSize = 12.sp, color = TextSecondary)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(Icons.Default.ChevronRight, contentDescription = "Ver backups no Drive", tint = primaryAccent, modifier = Modifier.size(16.dp))
                                            }
                                            Text(backupsCountStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                                        }

                                        CompactCloudInfoRow("Fila pendente", "$activeQueueCount")
                                        CompactCloudInfoRow("Erros", "$activeErrorsCount", if (activeErrorsCount == 0) StatusSuccess else StatusError)
                                        CompactCloudInfoRow("Última sincronização", lastSyncStr)

                                        if (isActive && (googleDriveState == GoogleDriveState.CONNECTION_ERROR || driveQuotaState is DriveQuotaState.Error)) {
                                            val errText = (driveQuotaState as? DriveQuotaState.Error)?.message
                                                ?: (remoteBackupsState as? RemoteBackupsState.Error)?.message
                                                ?: "Falha de conexão com os servidores do Google Drive."
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = StatusError.copy(alpha = 0.15f),
                                                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.4f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Erro de Comunicação", fontWeight = FontWeight.Bold, color = StatusError, fontSize = 11.5.sp)
                                                    }
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(errText, color = TextSecondary, fontSize = 10.5.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Last page: "Adicionar conta" Card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (dynamicCardHeightDp != null) Modifier.height(dynamicCardHeightDp) else Modifier.heightIn(min = 390.dp))
                                        .clip(RoundedCornerShape(16.dp))
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
                                        .clickable { launchAccountPicker() },
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 20.dp, vertical = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .background(primaryAccent.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(28.dp))
                                        }
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Text(
                                            text = "Adicionar conta",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "${multiAccounts.size}/5 contas conectadas",
                                            fontSize = 12.sp,
                                            color = TextMuted,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(18.dp))
                                        Button(
                                            onClick = { launchAccountPicker() },
                                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                        ) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (multiAccounts.isEmpty()) "CONECTAR GOOGLE DRIVE" else "CONECTAR OUTRA CONTA",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Indicator Dots
                        if (totalPagerPages > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(totalPagerPages) { pageIdx ->
                                    val isCurrent = pagerState.currentPage == pageIdx
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .size(if (isCurrent) 8.dp else 6.dp)
                                            .clip(CircleShape)
                                            .background(if (isCurrent) primaryAccent else TextMuted.copy(alpha = 0.4f))
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Cartão "Etiqueta do backup na nuvem"
                        val categorySummaries = remember(labelBackups) {
                            labelBackups.map { it to CloudBackupCategoryHelper.inspectRemoteFile(it) }
                        }

                        val remoteAppsCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasApps) s.countApps.coerceAtLeast(1) else 0 }
                        }
                        val remoteSmsCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasSms) s.countSms.coerceAtLeast(1) else 0 }
                        }
                        val remoteCallsCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasCalls) s.countCalls.coerceAtLeast(1) else 0 }
                        }
                        val remoteFoldersCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasFolders) s.countFolders.coerceAtLeast(1) else 0 }
                        }
                        val remoteBluetoothCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasBluetooth) s.countBluetooth.coerceAtLeast(1) else 0 }
                        }
                        val remoteDnsCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasDns) s.countDns.coerceAtLeast(1) else 0 }
                        }
                        val remoteWifiCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasWifi) s.countWifi.coerceAtLeast(1) else 0 }
                        }
                        val remoteContactsCount = remember(categorySummaries) {
                            categorySummaries.sumOf { (_, s) -> if (s.hasContacts) s.countContacts.coerceAtLeast(1) else 0 }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Label, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Etiqueta do backup na nuvem",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TextPrimary
                                        )
                                    }
                                    TextButton(
                                        onClick = { showLabelManagerModal = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Gerenciar", fontSize = 12.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "A etiqueta identifica seu aparelho no Google Drive para agrupar e gerenciar backups. Toque para alternar ou criar etiquetas.",
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    lineHeight = 16.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = DarkBg,
                                    border = BorderStroke(1.dp, if (effectiveViewLabel == customDeviceLabel) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showLabelManagerModal = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Smartphone, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Etiqueta em exibição", fontSize = 10.5.sp, color = TextMuted)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                if (effectiveViewLabel == customDeviceLabel) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = primaryAccent.copy(alpha = 0.15f)
                                                    ) {
                                                        Text("Ativa neste dispositivo", fontSize = 9.sp, color = primaryAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                                    }
                                                } else {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = StatusWarning.copy(alpha = 0.15f)
                                                    ) {
                                                        Text("Visualizando outro aparelho", fontSize = 9.sp, color = StatusWarning, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(effectiveViewLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                        Icon(Icons.Default.SwapHoriz, contentDescription = "Alternar", tint = primaryAccent, modifier = Modifier.size(20.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Backups nesta etiqueta (${labelBackups.size} arq.):",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Toque para ver/restaurar",
                                        fontSize = 10.sp,
                                        color = primaryAccent
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                // 4 colunas x 2 linhas (Clicáveis)
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Linha 1: Aplicativos, Mensagens, Chamadas, Pastas
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteAppsCount > 0) primaryAccent.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "Aplicativos" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteAppsCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = primaryAccent)
                                                Text("Aplicativos", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteSmsCount > 0) AccentElectricCyan.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "Mensagens" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteSmsCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AccentElectricCyan)
                                                Text("Mensagens", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteCallsCount > 0) AccentEmeraldGreen.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "Chamadas" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteCallsCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AccentEmeraldGreen)
                                                Text("Chamadas", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteFoldersCount > 0) AccentNeonPurple.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "Pastas" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteFoldersCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AccentNeonPurple)
                                                Text("Pastas", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                    }

                                    // Linha 2: Bluetooth, DNS, Wi-Fi, Contatos
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteBluetoothCount > 0) AccentNeonBlue.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "Bluetooth" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteBluetoothCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AccentNeonBlue)
                                                Text("Bluetooth", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteDnsCount > 0) AccentCyberPurple.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "DNS" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteDnsCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AccentCyberPurple)
                                                Text("DNS", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteWifiCount > 0) StatusWarning.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "Wi-Fi" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteWifiCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = StatusWarning)
                                                Text("Wi-Fi", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(0.5.dp, if (remoteContactsCount > 0) AccentSunsetOrange.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(10.dp))
                                                .clickable { selectedCategoryModal = "Contatos" },
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$remoteContactsCount", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AccentSunsetOrange)
                                                Text("Contatos", fontSize = 9.5.sp, color = TextSecondary, maxLines = 1)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkBg.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Toque em qualquer categoria para listar os backups e restaurar diretamente.",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                OutlinedButton(
                                    onClick = {
                                        labelToDelete = effectiveViewLabel
                                        showDeleteSpecificLabelDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                                    enabled = !isDeletingLabelBackups
                                ) {
                                    if (isDeletingLabelBackups) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = StatusError, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Excluindo...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp), tint = StatusError)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Excluir etiqueta e backups associados", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StatusError)
                                    }
                                }
                            }
                        }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Action Buttons for the active account
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        val targetEmail = activeAccountEmail ?: multiAccounts.firstOrNull()?.email
                                        if (targetEmail.isNullOrBlank()) {
                                            Toast.makeText(context, "Nenhuma conta Google Drive conectada.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Sincronizando conta ativa...", Toast.LENGTH_SHORT).show()
                                            viewModel.refreshActiveAccountData(targetEmail, isManual = true) {
                                                val qErr = (viewModel.driveQuotaState.value as? DriveQuotaState.Error)?.message
                                                val bErr = (viewModel.remoteBackupsState.value as? RemoteBackupsState.Error)?.message
                                                val err = qErr ?: bErr
                                                if (err != null) {
                                                    Toast.makeText(context, "Erro na sincronização: $err", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "Sincronização concluída com sucesso!", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    enabled = !isRefreshingActiveAccount
                                ) {
                                    if (isRefreshingActiveAccount) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("SINCRONIZANDO...", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.Black, maxLines = 1)
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("SINCRONIZAR", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        val targetEmail = activeAccountEmail ?: multiAccounts.firstOrNull()?.email
                                        if (targetEmail.isNullOrBlank()) {
                                            Toast.makeText(context, "Nenhuma conta Google Drive conectada.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Atualizando dados da conta ativa...", Toast.LENGTH_SHORT).show()
                                            viewModel.refreshActiveAccountData(targetEmail, isManual = true) {
                                                val qErr = (viewModel.driveQuotaState.value as? DriveQuotaState.Error)?.message
                                                val bErr = (viewModel.remoteBackupsState.value as? RemoteBackupsState.Error)?.message
                                                val err = qErr ?: bErr
                                                if (err != null) {
                                                    Toast.makeText(context, "Erro na atualização: $err", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "Dados atualizados com sucesso!", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, primaryAccent),
                                    enabled = !isRefreshingActiveAccount
                                ) {
                                    if (isRefreshingActiveAccount) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = primaryAccent, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("ATUALIZANDO...", color = primaryAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("ATUALIZAR", color = primaryAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = {
                                    selectedTab = 2
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = "Diagnóstico", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("DIAGNÓSTICO DA NUVEM", color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = onViewLogs,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Icon(Icons.Default.Article, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("VER LOGS", color = TextSecondary, fontSize = 12.sp)
                            }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Sincronização Automática Card (Section 35.8)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = primaryAccent)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sincronização Automática (Gratuita)", fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                Text("Execução segura via WorkManager em segundo plano", fontSize = 11.sp, color = TextSecondary)

                                Spacer(modifier = Modifier.height(12.dp))

                                SyncSettingCheckbox(
                                    label = "Sincronização automática ativa",
                                    checked = syncSettings.isAutoSyncEnabled,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(isAutoSyncEnabled = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Somente Wi-Fi (Economizar dados móveis)",
                                    checked = syncSettings.wifiOnly,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(wifiOnly = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Somente durante carregamento da bateria",
                                    checked = syncSettings.requiresCharging,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(requiresCharging = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Sincronizar diariamente",
                                    checked = syncSettings.isDaily,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(isDaily = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Sincronizar imediatamente após backup",
                                    checked = syncSettings.syncImmediatelyAfterBackup,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(syncImmediatelyAfterBackup = it)) },
                                    accent = primaryAccent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(140.dp))
                    }
                }

                1 -> {
                    // TAB 1: Sync Queue
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Fila de Transferência Ativa", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Uploads resumíveis com priorização de itens", fontSize = 12.sp, color = TextSecondary)
                                }
                                if (syncQueue.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val hasFinishedItems = syncQueue.any { it.status == QueueStatus.COMPLETED || it.status == QueueStatus.FAILED }
                                        if (hasFinishedItems) {
                                            TextButton(
                                                onClick = { viewModel.clearCompletedSyncQueue(activeAccountEmail) }
                                            ) {
                                                Icon(Icons.Default.DoneAll, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("Concluídos", color = primaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        TextButton(
                                            onClick = { viewModel.clearSyncQueue(activeAccountEmail) }
                                        ) {
                                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = StatusError, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Limpar", color = StatusError, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (activeAccountEmail != null && syncQueue.isNotEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = primaryAccent.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.FilterList, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Filtrando fila pela conta ativa: $activeAccountEmail",
                                            fontSize = 11.sp,
                                            color = primaryAccent,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        val queueItems = if (activeAccountEmail != null) {
                            syncQueue.filter { it.accountEmail == null || it.accountEmail == activeAccountEmail }
                        } else {
                            syncQueue
                        }
                        if (queueItems.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.DoneAll, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(40.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Fila vazia. Todos os backups estão sincronizados!", color = TextSecondary)
                                    }
                                }
                            }
                        } else {
                            items(queueItems, key = { it.id }) { item ->
                                SyncQueueCard(
                                    item = item,
                                    primaryAccent = primaryAccent,
                                    onPause = { viewModel.pauseQueueItem(item.id) },
                                    onResume = { viewModel.resumeQueueItem(item.id) },
                                    onPrioritize = { viewModel.prioritizeQueueItem(item.id) },
                                    onRetry = { viewModel.retryQueueItem(item.id) },
                                    onCancel = { viewModel.cancelQueueItem(item.id) },
                                    onRemove = { viewModel.removeQueueItem(item.id) }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(96.dp))
                        }
                    }
                }

                2 -> {
                    // TAB 2: Diagnóstico da Nuvem (9 Testes Individuais)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))



                        // CARD DO LOG DA ÚLTIMA RESTAURAÇÃO
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Restore, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Log da Última Restauração", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    }
                                    if (!lastRestoreLog.isNullOrBlank()) {
                                        TextButton(onClick = {
                                            clipboardManager.setText(AnnotatedString(lastRestoreLog!!))
                                            Toast.makeText(context, "Log de restauração copiado!", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Text("Copiar", color = primaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                if (lastRestoreLog.isNullOrBlank()) {
                                    Text("Nenhum log de restauração registrado recentemente.", fontSize = 12.sp, color = TextMuted)
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DarkBg)
                                            .padding(10.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = lastRestoreLog!!,
                                            fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { viewModel.runCloudDiagnostic(CloudProviderType.GOOGLE_DRIVE) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            enabled = !isDiagnosing
                        ) {
                            if (isDiagnosing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Executando Testes...")
                            } else {
                                Icon(Icons.Default.Speed, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Diagnosticar Conexão", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (diagnosticResults.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                border = BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Nenhum teste de diagnóstico executado",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Toque no botão 'Diagnosticar Conexão' acima para executar em tempo real a validação da sua conta e do Google Drive.",
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            for (res in diagnosticResults) {
                                DiagnosticResultCard(res)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        // Seção Último Erro do Sistema / Crash Log
                        if (!lastCrashLog.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, StatusError.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.10f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.BugReport, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Último Erro do Sistema / Crash Log", fontWeight = FontWeight.Bold, color = StatusError, fontSize = 13.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        color = DarkBg,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 140.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(6.dp)
                                    ) {
                                        Text(
                                            text = lastCrashLog ?: "",
                                            fontSize = 10.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = StatusError
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { viewModel.dismissCrashLog() }) {
                                            Text("Limpar Registro", color = TextSecondary, fontSize = 11.sp)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("ReSync Crash Log", lastCrashLog))
                                                Toast.makeText(context, "Log copiado com sucesso!", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copiar Log", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(96.dp))
                    }
                }
            }
        }
    }

    if (!lastCrashLog.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCrashLog() },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("O app fechou da última vez", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        "Ocorreu uma falha inesperada na sessão anterior. O log técnico foi salvo e pode ser copiado para diagnóstico:",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = DarkBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(
                            text = lastCrashLog ?: "",
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = StatusError
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ReSync Crash Log", lastCrashLog))
                        Toast.makeText(context, "Log copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copiar Log", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCrashLog() }) {
                    Text("Fechar", color = TextSecondary, fontSize = 12.sp)
                }
            }
        )
    }

    if (accountToRemove != null) {
        val acc = accountToRemove!!
        AlertDialog(
            onDismissRequest = { accountToRemove = null },
            containerColor = DarkSurface,
            title = { Text("Remover Conta", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text("Deseja desconectar a conta ${acc.email}? Seus arquivos no Google Drive permanecerão intactos.", color = TextSecondary, fontSize = 13.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        val email = acc.email
                        accountToRemove = null
                        viewModel.removeDriveAccount(email)
                        Toast.makeText(context, "Conta removida.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("REMOVER", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToRemove = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    if (showDeleteSpecificLabelDialog && labelToDelete.isNotBlank()) {
        val targetLabel = labelToDelete.trim()
        val targetBackups = remember(remoteList, targetLabel) {
            remoteList.filter { file ->
                val prop = file.appProperties["deviceLabel"]?.trim()
                if (!prop.isNullOrBlank()) {
                    prop.equals(targetLabel, ignoreCase = true)
                } else {
                    file.name.contains(targetLabel, ignoreCase = true)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isDeletingLabelBackups) showDeleteSpecificLabelDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = StatusError, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Excluir etiqueta e backups?", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        "Deseja realmente excluir a etiqueta \"$targetLabel\" e todos os ${targetBackups.size} backup(s) associados a ela no Google Drive?",
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = DarkBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Total de arquivos no Google Drive: ${targetBackups.size}", fontSize = 11.sp, color = TextMuted)
                            val totalBytes = targetBackups.sumOf { it.sizeBytes }
                            Text("Espaço a ser liberado: ${formatStorageSize(totalBytes)}", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Essa ação apagará os arquivos correspondentes na nuvem permanentemente. Os backups locais no aparelho não serão afetados.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeletingLabelBackups = true
                        viewModel.deleteDeviceLabel(targetLabel) { deletedCount ->
                            isDeletingLabelBackups = false
                            showDeleteSpecificLabelDialog = false
                            labelToDelete = ""
                            Toast.makeText(context, "$deletedCount backup(s) excluídos da nuvem com sucesso.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    enabled = !isDeletingLabelBackups
                ) {
                    if (isDeletingLabelBackups) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Excluindo...", color = Color.White)
                    } else {
                        Text("EXCLUIR PERMANENTEMENTE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteSpecificLabelDialog = false },
                    enabled = !isDeletingLabelBackups
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    if (showCreateLabelDialog) {
        var newLabelInput by remember { mutableStateOf("") }
        var setAsActive by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showCreateLabelDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Criar Nova Etiqueta", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text("Digite o nome da nova etiqueta para organizar seus backups:", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newLabelInput,
                        onValueChange = { newLabelInput = it },
                        placeholder = { Text("Ex: POCO X7 Pro - Diário, Backup Format", fontSize = 12.sp, color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setAsActive = !setAsActive },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = setAsActive,
                            onCheckedChange = { setAsActive = it },
                            colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Definir como etiqueta ativa", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Novos backups usarão esta etiqueta", fontSize = 10.sp, color = TextMuted)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newLabelInput.trim()
                        if (trimmed.isNotBlank()) {
                            viewModel.createDeviceLabel(trimmed, setAsActive)
                            showCreateLabelDialog = false
                            Toast.makeText(context, "Etiqueta \"$trimmed\" criada com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    enabled = newLabelInput.isNotBlank()
                ) {
                    Text("Criar", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateLabelDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    if (showRenameLabelDialog && labelToRename.isNotBlank()) {
        var renamedInput by remember { mutableStateOf(labelToRename) }
        var isRenaming by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isRenaming) showRenameLabelDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Renomear Etiqueta", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text("Digite o novo nome para a etiqueta \"$labelToRename\":", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = renamedInput,
                        onValueChange = { renamedInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Isso atualizará os metadados dos arquivos existentes no Google Drive associados a esta etiqueta.", fontSize = 11.sp, color = TextMuted)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renamedInput.trim()
                        if (trimmed.isNotBlank() && trimmed != labelToRename) {
                            isRenaming = true
                            viewModel.renameDeviceLabel(labelToRename, trimmed) { count ->
                                isRenaming = false
                                showRenameLabelDialog = false
                                Toast.makeText(context, "$count backup(s) atualizados com a nova etiqueta.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    enabled = renamedInput.isNotBlank() && !isRenaming
                ) {
                    if (isRenaming) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Atualizando...", color = Color.Black)
                    } else {
                        Text("Salvar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameLabelDialog = false }, enabled = !isRenaming) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    if (showLabelManagerModal) {
        val allDiscoveredLabels = remember(remoteList, savedDeviceLabels, customDeviceLabel) {
            val list = mutableListOf<String>()
            val cLabel = customDeviceLabel.trim()
            if (cLabel.isNotBlank()) list.add(cLabel)
            savedDeviceLabels.forEach { s ->
                val tr = s.trim()
                if (tr.isNotBlank() && !list.contains(tr)) list.add(tr)
            }
            remoteList.forEach { f ->
                val p = f.appProperties["deviceLabel"]?.trim()
                if (!p.isNullOrBlank() && !list.contains(p)) list.add(p)
            }
            list
        }

        AlertDialog(
            onDismissRequest = { showLabelManagerModal = false },
            containerColor = DarkSurface,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Label, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gerenciar Etiquetas", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                    }
                    IconButton(
                        onClick = { showCreateLabelDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Criar", tint = primaryAccent)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Selecione uma etiqueta para ver seus backups ou use o botão '+' para criar uma nova.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (allDiscoveredLabels.isEmpty()) {
                        Text("Nenhuma etiqueta encontrada.", fontSize = 12.sp, color = TextMuted)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(allDiscoveredLabels, key = { it }) { labelItem ->
                                val isSelected = labelItem.equals(effectiveViewLabel, ignoreCase = true)
                                val isActiveDevice = labelItem.equals(customDeviceLabel, ignoreCase = true)
                                val count = remoteList.count { f ->
                                    val prop = f.appProperties["deviceLabel"]?.trim()
                                    if (!prop.isNullOrBlank()) prop.equals(labelItem, ignoreCase = true)
                                    else f.name.contains(labelItem, ignoreCase = true)
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkBg,
                                    border = BorderStroke(1.dp, if (isSelected) primaryAccent else DarkCardBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.setSelectedViewLabel(labelItem)
                                            showLabelManagerModal = false
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) primaryAccent else TextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(labelItem, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                if (isActiveDevice) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(shape = RoundedCornerShape(4.dp), color = primaryAccent.copy(alpha = 0.2f)) {
                                                        Text("Ativa", fontSize = 9.sp, color = primaryAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                    }
                                                }
                                            }
                                            Text("$count backup(s) no Google Drive", fontSize = 11.sp, color = TextSecondary)
                                        }

                                        // Botões de ação rápida
                                        IconButton(
                                            onClick = {
                                                labelToRename = labelItem
                                                showRenameLabelDialog = true
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Renomear", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                labelToDelete = labelItem
                                                showDeleteSpecificLabelDialog = true
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = StatusError, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showCreateLabelDialog = true },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, primaryAccent)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Criar nova etiqueta", color = primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLabelManagerModal = false }) {
                    Text("Concluir", color = primaryAccent, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (selectedCategoryModal != null) {
        val catName = selectedCategoryModal!!
        val matchingBackups = remember(labelBackups, catName) {
            labelBackups.filter { file ->
                val s = CloudBackupCategoryHelper.inspectRemoteFile(file)
                s.containsCategory(catName)
            }
        }

        AlertDialog(
            onDismissRequest = { selectedCategoryModal = null },
            containerColor = DarkSurface,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (catName.lowercase()) {
                            "aplicativos" -> Icons.Default.Apps
                            "mensagens" -> Icons.Default.Sms
                            "chamadas" -> Icons.Default.Call
                            "pastas" -> Icons.Default.Folder
                            "bluetooth" -> Icons.Default.Bluetooth
                            "dns" -> Icons.Default.Dns
                            "wi-fi" -> Icons.Default.Wifi
                            "contatos" -> Icons.Default.Contacts
                            else -> Icons.Default.Inventory2
                        }
                        Icon(icon, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(catName, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                    }
                    IconButton(onClick = { selectedCategoryModal = null }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextMuted)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Backups contendo \"$catName\" sob a etiqueta \"$effectiveViewLabel\":",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (matchingBackups.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkBg,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Nenhum backup encontrado contendo $catName nesta etiqueta.",
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(matchingBackups, key = { it.id }) { file ->
                                val summary = remember(file) { CloudBackupCategoryHelper.inspectRemoteFile(file) }
                                val count = when (catName.lowercase()) {
                                    "aplicativos" -> summary.countApps
                                    "mensagens" -> summary.countSms
                                    "chamadas" -> summary.countCalls
                                    "pastas" -> summary.countFolders
                                    "bluetooth" -> summary.countBluetooth
                                    "dns" -> summary.countDns
                                    "wi-fi" -> summary.countWifi
                                    "contatos" -> summary.countContacts
                                    else -> 1
                                }
                                val isFullPkg = file.name.endsWith(".resync", ignoreCase = true) || file.name.endsWith(".svb", ignoreCase = true)
                                val displayTitle = file.appProperties["title"] ?: file.name

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = DarkBg,
                                    border = BorderStroke(1.dp, DarkCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = displayTitle,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isFullPkg) primaryAccent.copy(alpha = 0.15f) else TextSecondary.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = if (isFullPkg) "Pacote Completo" else "Avulso",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isFullPkg) primaryAccent else TextSecondary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${formatStorageSize(file.sizeBytes)} • ${if (count > 0) "$count item(ns)" else "Incluso"}",
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
                                            Button(
                                                onClick = {
                                                    val unifiedEntry = viewModel.createUnifiedEntryFromDriveFile(file)
                                                    Toast.makeText(context, "Iniciando restauração de $catName...", Toast.LENGTH_SHORT).show()
                                                    viewModel.restoreCategoryBackup(unifiedEntry, catName)
                                                    selectedCategoryModal = null
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Icon(Icons.Default.Restore, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Restaurar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCategoryModal = null }) {
                    Text("Fechar", color = TextSecondary)
                }
            }
        )
    }

    if (showBackupSelectionDialog) {
        val localBackups by viewModel.backupRecords.collectAsState()
        val selectedBackupIds = remember { mutableStateListOf<String>() }

        AlertDialog(
            onDismissRequest = { showBackupSelectionDialog = false },
            containerColor = DarkSurface,
            title = {
                Text("Enviar Backups Existentes", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    Text(
                        text = "Selecione os backups locais para enviar ao Google Drive (${selectedDriveAccountEmail ?: "Conta Ativa"}):",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (localBackups.isEmpty()) {
                        Text("Nenhum backup local encontrado.", color = TextMuted, fontSize = 13.sp)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(localBackups, key = { it.id }) { b ->
                                val isChecked = selectedBackupIds.contains(b.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceVariant)
                                        .clickable {
                                            if (isChecked) selectedBackupIds.remove(b.id)
                                            else selectedBackupIds.add(b.id)
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = {
                                            if (it) selectedBackupIds.add(b.id)
                                            else selectedBackupIds.remove(b.id)
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(b.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                        Text("%.2f MB • ${b.destination}".format(java.util.Locale.US, b.sizeBytes.toDouble() / (1024 * 1024)), fontSize = 11.sp, color = TextMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toUpload = localBackups.filter { selectedBackupIds.contains(it.id) }
                        showBackupSelectionDialog = false
                        if (toUpload.isNotEmpty()) {
                            viewModel.uploadBackupsToDrive(toUpload, DriveConstants.ROOT_FOLDER_NAME, selectedDriveAccountEmail)
                            coroutineScope.launch {
                                val res = snackbarHostState.showSnackbar(
                                    message = "${toUpload.size} backup(s) adicionados à fila de envio!",
                                    actionLabel = "Ver fila",
                                    duration = SnackbarDuration.Short
                                )
                                if (res == SnackbarResult.ActionPerformed) {
                                    selectedTab = 1
                                }
                            }
                        }
                    },
                    enabled = selectedBackupIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Enviar Selecionados", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupSelectionDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Loading Dialog during account authorization
    if (isAuthorizingAccount) {
        AlertDialog(
            onDismissRequest = { /* Não dispensa durante autenticação em andamento */ },
            containerColor = DarkSurface,
            title = {
                Text("Conectando ao Google Drive", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = primaryAccent)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Validando credenciais e consultando cota de armazenamento...",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Error Dialog with Retry Button
    if (!driveAuthErrorMessage.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { viewModel.clearDriveAuthError() },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Falha na Conta Google", fontWeight = FontWeight.Bold, color = StatusError)
                }
            },
            text = {
                Column {
                    Text(
                        text = driveAuthErrorMessage ?: "Ocorreu um erro ao conectar ao Google Drive.",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Verifique sua conexão à internet e conceda as permissões necessárias na tela de consentimento.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val retryEmail = inputEmail
                        viewModel.clearDriveAuthError()
                        if (retryEmail.isNotBlank()) {
                            viewModel.addDriveAccount(
                                email = retryEmail,
                                onAuthNeeded = { intent ->
                                    pendingConsentIntent = intent
                                },
                                onComplete = { addRes ->
                                    if (addRes.isSuccess) {
                                        Toast.makeText(context, "Google Drive conectado com sucesso!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        } else {
                            launchAccountPicker()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Tentar novamente", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearDriveAuthError() }) {
                    Text("Fechar", color = TextSecondary)
                }
            }
        )
    }

    if (remoteBackupToDelete != null) {
        val target = remoteBackupToDelete!!
        AlertDialog(
            onDismissRequest = { remoteBackupToDelete = null },
            title = { Text("Excluir Backup da Nuvem", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    text = "Tem certeza que deseja excluir '${target.name}' do Google Drive?",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRemoteDriveBackup(target.id)
                        remoteBackupToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("Excluir", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { remoteBackupToDelete = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}


@Composable
private fun SyncSettingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = accent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontSize = 13.sp, color = TextPrimary)
    }
}

@Composable
fun SyncQueueCard(
    item: CloudSyncQueueItem,
    primaryAccent: Color,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPrioritize: () -> Unit,
    onRetry: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.status == QueueStatus.COMPLETED) {
                        CompletionAnimation(
                            size = CompletionAnimationSize.COMPACT,
                            themeAccent = StatusSuccess,
                            playKey = item.id
                        )
                    } else {
                        val statusIcon = when (item.status) {
                            QueueStatus.RUNNING -> Icons.Default.CloudUpload
                            QueueStatus.PAUSED -> Icons.Default.PauseCircle
                            QueueStatus.FAILED -> Icons.Default.Error
                            else -> Icons.Default.Schedule
                        }
                        val statusTint = when (item.status) {
                            QueueStatus.RUNNING -> primaryAccent
                            QueueStatus.PAUSED -> StatusWarning
                            QueueStatus.FAILED -> StatusError
                            else -> TextSecondary
                        }
                        Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(item.backupTitle, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (item.status) {
                                QueueStatus.COMPLETED -> StatusSuccess.copy(alpha = 0.15f)
                                QueueStatus.RUNNING -> primaryAccent.copy(alpha = 0.15f)
                                QueueStatus.PAUSED -> StatusWarning.copy(alpha = 0.15f)
                                QueueStatus.FAILED -> StatusError.copy(alpha = 0.15f)
                                else -> DarkCardBorder
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.status.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (item.status) {
                            QueueStatus.COMPLETED -> StatusSuccess
                            QueueStatus.RUNNING -> primaryAccent
                            QueueStatus.PAUSED -> StatusWarning
                            QueueStatus.FAILED -> StatusError
                            else -> TextSecondary
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val progress = if (item.totalBytes > 0) (item.progressBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f
            SmoothLinearProgressBar(
                progress = progress,
                color = primaryAccent,
                trackColor = DarkCardBorder,
                height = 4.dp,
                shapeRadius = 2.dp
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%.1f MB / %.1f MB".format(
                        java.util.Locale.US,
                        item.progressBytes.toDouble() / (1024 * 1024),
                        item.totalBytes.toDouble() / (1024 * 1024)
                    ),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Text(
                    text = "Prioridade: ${item.priority}",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            if (!item.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                val errPrefix = if (!item.errorCode.isNullOrBlank()) "[${item.errorCode}] " else ""
                Text(
                    text = "$errPrefix${item.errorMessage}",
                    fontSize = 11.sp,
                    color = StatusError
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (item.status == QueueStatus.RUNNING) {
                    TextButton(onClick = onPause) {
                        Text("Pausar", fontSize = 11.sp, color = StatusWarning)
                    }
                } else if (item.status == QueueStatus.PAUSED) {
                    TextButton(onClick = onResume) {
                        Text("Continuar", fontSize = 11.sp, color = primaryAccent)
                    }
                }

                if (item.status == QueueStatus.RUNNING || item.status == QueueStatus.PENDING) {
                    TextButton(onClick = { onCancel?.invoke() }) {
                        Text("Cancelar", fontSize = 11.sp, color = StatusError)
                    }
                }

                if (item.status == QueueStatus.FAILED) {
                    TextButton(onClick = onRetry) {
                        Text("Tentar Novamente", fontSize = 11.sp, color = primaryAccent)
                    }
                }

                TextButton(onClick = onPrioritize) {
                    Text("Priorizar", fontSize = 11.sp, color = TextSecondary)
                }

                TextButton(onClick = onRemove) {
                    Text("Remover", fontSize = 11.sp, color = StatusError)
                }
            }
        }
    }
}

@Composable
fun DiagnosticResultCard(res: CloudDiagnosticResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (res.status == DiagnosticStepStatus.FAILED) StatusError.copy(alpha = 0.4f) else DarkCardBorder,
                RoundedCornerShape(10.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Linha superior: Ícone de status, Nome do passo (com weight) e Latência
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (res.status) {
                    DiagnosticStepStatus.SUCCESS -> Icons.Default.CheckCircle
                    DiagnosticStepStatus.FAILED -> Icons.Default.Cancel
                    DiagnosticStepStatus.RUNNING -> Icons.Default.Sync
                    else -> Icons.Default.Schedule
                }
                val tint = when (res.status) {
                    DiagnosticStepStatus.SUCCESS -> StatusSuccess
                    DiagnosticStepStatus.FAILED -> StatusError
                    DiagnosticStepStatus.RUNNING -> AccentElectricCyan
                    else -> TextSecondary
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = res.stepName,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${res.latencyMs} ms",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Descrição/Mensagem de erro em largura total com quebra natural de linha (sem compressão lateral)
            if (!res.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = res.errorMessage,
                    color = StatusError,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 26.dp),
                    softWrap = true
                )
            }
            if (!res.possibleCause.isNullOrBlank() && res.status == DiagnosticStepStatus.FAILED) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Causa: ${res.possibleCause}",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 26.dp),
                    softWrap = true
                )
            }
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes.toDouble() / 1024
    val mb = kb / 1024
    val gb = mb / 1024
    val tb = gb / 1024
    return when {
        tb >= 1.0 -> "%.2f TB".format(java.util.Locale.US, tb)
        gb >= 1.0 -> "%.2f GB".format(java.util.Locale.US, gb)
        mb >= 1.0 -> "%.1f MB".format(java.util.Locale.US, mb)
        else -> "%.1f KB".format(java.util.Locale.US, kb)
    }
}

private fun formatSpeedDisplay(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0,0 MB/s"
    val mbPerSec = bytesPerSec.toDouble() / (1024.0 * 1024.0)
    return if (mbPerSec >= 0.1) {
        String.format(java.util.Locale.US, "%.1f MB/s", mbPerSec).replace('.', ',')
    } else {
        val kbPerSec = bytesPerSec.toDouble() / 1024.0
        String.format(java.util.Locale.US, "%.0f KB/s", kbPerSec)
    }
}

@Composable
private fun CompactCloudInfoRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

