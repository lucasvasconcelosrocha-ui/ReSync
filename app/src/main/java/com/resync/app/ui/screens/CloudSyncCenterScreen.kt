package com.resync.app.ui.screens

import com.resync.app.util.showSafeToast
import androidx.documentfile.provider.DocumentFile

import com.resync.app.R
import androidx.compose.ui.res.stringResource


import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.cloud.gdrive.AutoSyncSettings
import com.resync.app.cloud.gdrive.CloudBackupCategoryHelper
import com.resync.app.cloud.gdrive.LabelManifestManager
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

@OptIn(ExperimentalMaterial3Api::class)
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

    val activeLabelManifest by viewModel.activeLabelManifest.collectAsState()
    val allDiscoveredManifests by viewModel.allDiscoveredManifests.collectAsState()
    val currentDeviceFingerprint = remember { LabelManifestManager.getActiveCryptoFingerprint(context) }

    val cloudLabelComponentSummary by viewModel.cloudLabelComponentSummary.collectAsState()
    val remoteCategorySummaries by viewModel.remoteCategorySummaries.collectAsState()

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
    val labelIsLocked = activeLabelManifest?.isLocked == true
    val labelDisplayName = activeLabelManifest?.displayName?.ifBlank { effectiveViewLabel } ?: effectiveViewLabel
    val labelBackupsBytes = remember(labelBackups) { labelBackups.sumOf { it.sizeBytes } }

    LaunchedEffect(effectiveViewLabel, activeAccountEmail, remoteList) {
        viewModel.loadCloudLabelComponentSummary(effectiveViewLabel, activeAccountEmail)
    }

    val overviewScrollState = rememberScrollState()
    var isFabVisible by remember { mutableStateOf(true) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(overviewScrollState.value) {
        val currentOffset = overviewScrollState.value
        val diff = currentOffset - previousScrollOffset
        if (currentOffset <= 20) {
            isFabVisible = true
        } else if (diff > 12) {
            isFabVisible = false
        } else if (diff < -12) {
            isFabVisible = true
        }
        previousScrollOffset = currentOffset
    }

    val shouldShowFab = (isFabVisible || isFabExpanded) && selectedTab == 0 && (activeAccountEmail != null || multiAccounts.isNotEmpty())

    var showLabelManagerModal by remember { mutableStateOf(false) }
    var showCreateLabelDialog by remember { mutableStateOf(false) }
    var showRenameLabelDialog by remember { mutableStateOf(false) }
    var showDeleteSpecificLabelDialog by remember { mutableStateOf(false) }
    var labelToRename by remember { mutableStateOf("") }
    var labelToDelete by remember { mutableStateOf("") }
    var showRenameDisplayNameDialog by remember { mutableStateOf(false) }
    var renameDisplayNameInput by remember { mutableStateOf("") }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showIncompleteFilesDialog by remember { mutableStateOf(false) }
    var incompleteAuditResult by remember { mutableStateOf<LabelManifestManager.IncompleteFilesAudit?>(null) }
    var isCleaningIncomplete by remember { mutableStateOf(false) }
    var showDeleteLabelScopeDialog by remember { mutableStateOf(false) }
    var showEmptyCategoryDialog by remember { mutableStateOf<String?>(null) }
    var showCryptoWarningDialog by remember { mutableStateOf<String?>(null) }
    var newLabelRetentionChoice by remember { mutableIntStateOf(2) }
    var selectedCategoryModal by remember { mutableStateOf<String?>(null) }
    var selectiveRestoreBackup by remember { mutableStateOf<BackupRecord?>(null) }
    var selectiveRestoreCategory by remember { mutableStateOf<String?>(null) }

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
                    message = context.getString(R.string.cloud_files_added_queue, uris.size),
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

            val docFile = DocumentFile.fromTreeUri(context, uri)
            val filesList = docFile?.listFiles()
            if (filesList == null || filesList.isEmpty()) {
                context.showSafeToast("A pasta selecionada está vazia.")
            } else {
                viewModel.uploadFolderTree(uri, DriveConstants.ROOT_FOLDER_NAME, selectedDriveAccountEmail)
                coroutineScope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.cloud_folder_added_queue),
                        actionLabel = "Ver fila",
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        selectedTab = 1
                    }
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

    if (selectiveRestoreBackup != null && selectiveRestoreCategory != null) {
        val b = selectiveRestoreBackup!!
        val cat = selectiveRestoreCategory!!
        BackHandler {
            selectiveRestoreBackup = null
            selectiveRestoreCategory = null
        }
        val resolvedLocal = if (File(b.filePath).exists()) File(b.filePath) else com.resync.app.data.model.BackupPaths.findLocalMirrorFile(b.id, b.title, context, b.sizeBytes, b.driveFileId)
        val effectiveBackup = if (resolvedLocal != null && (b.filePath.isBlank() || !File(b.filePath).exists())) {
            b.copy(filePath = resolvedLocal.absolutePath, syncStatus = SyncStatus.SYNCED)
        } else b

        RestoreSelectionScreen(
            backup = effectiveBackup,
            source = if (resolvedLocal != null && resolvedLocal.exists()) RestoreSource.LOCAL else (if (!effectiveBackup.driveFileId.isNullOrBlank()) RestoreSource.DRIVE else RestoreSource.LOCAL),
            viewModel = viewModel,
            initialCategoryId = cat,
            exclusiveCategoryMode = true,
            onBack = {
                selectiveRestoreBackup = null
                selectiveRestoreCategory = null
            },
            onFinished = {
                selectiveRestoreBackup = null
                selectiveRestoreCategory = null
            }
        )
        return
    }

    val onOpenCategoryRestore: (String) -> Unit = { catName ->
        val matching = labelBackups.filter { file ->
            val s = remoteCategorySummaries[file.id] ?: CloudBackupCategoryHelper.inspectRemoteFile(file, context)
            s.containsCategory(catName)
        }.ifEmpty {
            if (labelBackups.isNotEmpty()) listOf(labelBackups.first()) else emptyList()
        }
        if (matching.isEmpty()) {
            context.showSafeToast(context.getString(R.string.cloud_no_backup_tag, catName))
        } else if (matching.size == 1) {
            val file = matching.first()
            val localFile = resolveLocalMirrorFile(context, viewModel.backupRecords.value, file.name, file.id, file.sizeBytes)
            val rec = BackupRecord(
                id = file.id,
                title = file.name,
                filePath = localFile?.absolutePath ?: "",
                sizeBytes = file.sizeBytes,
                checksumSha256 = file.md5Checksum ?: "",
                isEncrypted = file.name.endsWith(".svb") || file.name.endsWith(".resync"),
                timestamp = file.modifiedTime ?: file.createdTime ?: System.currentTimeMillis(),
                itemCount = 1,
                status = BackupStatus.COMPLETED,
                destination = "Google Drive",
                syncStatus = if (localFile != null && localFile.exists()) SyncStatus.SYNCED else SyncStatus.UPLOADED,
                driveFileId = file.id
            )
            selectiveRestoreBackup = rec
            selectiveRestoreCategory = catName
        } else {
            selectedCategoryModal = catName
        }
    }

    val hasInternalBack = isFabExpanded || showBackupSelectionDialog || accountToRemove != null || selectedTab != 0 ||
            showLabelManagerModal || selectedCategoryModal != null || showCreateLabelDialog || showRenameLabelDialog ||
            showDeleteSpecificLabelDialog || showRenameDisplayNameDialog || showRetentionDialog || showIncompleteFilesDialog ||
            showDeleteLabelScopeDialog || showEmptyCategoryDialog != null || showCryptoWarningDialog != null
    BackHandler(enabled = hasInternalBack) {
        when {
            showRenameDisplayNameDialog -> showRenameDisplayNameDialog = false
            showRetentionDialog -> showRetentionDialog = false
            showIncompleteFilesDialog -> showIncompleteFilesDialog = false
            showDeleteLabelScopeDialog -> showDeleteLabelScopeDialog = false
            showEmptyCategoryDialog != null -> showEmptyCategoryDialog = null
            showCryptoWarningDialog != null -> showCryptoWarningDialog = null
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
                            context.showSafeToast(context.getString(R.string.cloud_gdrive_connected_valid))
                        }
                    }
                )
            }
        } else {
            context.showSafeToast(context.getString(R.string.cloud_oauth_cancelled))
        }
    }

    LaunchedEffect(pendingConsentIntent) {
        pendingConsentIntent?.let { intent ->
            pendingConsentIntent = null
            try {
                consentLauncher.launch(intent)
            } catch (t: Throwable) {
                context.showSafeToast(context.getString(R.string.gdrive_auth_err, t.message ?: ""), Toast.LENGTH_LONG)
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
                            context.showSafeToast(context.getString(R.string.cloud_account_connected_success))
                        }
                    }
                )
            } else {
                context.showSafeToast("Nenhuma conta Google foi selecionada.")
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            context.showSafeToast(context.getString(R.string.cloud_account_selection_cancelled))
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
                context.showSafeToast(context.getString(R.string.gdrive_account_picker_err, e2.message ?: ""), Toast.LENGTH_LONG)
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.cloud_resync_cloud_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = activeAccountEmail ?: "Google Drive",
                                fontSize = 11.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Badge AES-256-GCM (FOTO 7)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0D281E),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF10B981)))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "AES-256-GCM",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Avatar do Usuário do Google Drive
                        UserAvatar(
                            photoUrl = activeRoomAccount?.photoUrl,
                            displayName = activeRoomAccount?.name?.ifBlank { activeRoomAccount.email },
                            email = activeRoomAccount?.email,
                            size = 32.dp,
                            onClick = { launchAccountPicker() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Floating Subtabs styled identically to FloatingBottomNavBar
                val activeQSize = syncQueue.count { it.accountEmail == null || it.accountEmail == activeAccountEmail }
                val subTabs = remember(activeQSize) {
                    listOf(
                        context.getString(R.string.cloud_tab_overview),
                        "Fila ($activeQSize)",
                        context.getString(R.string.cloud_tab_diagnostics)
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                    .padding(horizontal = 16.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        // TAB 0: Visão Geral e Conexão Gratuita
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(overviewScrollState)
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
                                        Text(stringResource(R.string.cloud_auth_error_title), fontWeight = FontWeight.Bold, color = StatusError, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = errorInfo?.message ?: stringResource(R.string.cloud_auth_failed_desc),
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    if (!errorInfo?.technicalCode.isNullOrBlank()) {
                                        Text(
                                            text = stringResource(R.string.cloud_tech_code, errorInfo?.technicalCode ?: ""),
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
                                        Text(stringResource(R.string.cloud_session_expired_title), fontWeight = FontWeight.Bold, color = AccentSunsetOrange, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.cloud_session_expired_desc),
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.cloud_tech_code_http401),
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
                                    isActive && googleDriveState == GoogleDriveState.CONNECTION_ERROR -> Pair(stringResource(R.string.cloud_status_error), StatusError)
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
                                    else -> stringResource(R.string.cloud_no_backup_simple)
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
                                        CompactCloudInfoRow(stringResource(R.string.cloud_used_space), FormatUtils.formatBytes(usedBytes))
                                        CompactCloudInfoRow(stringResource(R.string.cloud_available_space), FormatUtils.formatBytes(availBytes), StatusSuccess)
                                        CompactCloudInfoRow(stringResource(R.string.cloud_total_space), FormatUtils.formatBytes(totalBytes))

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
                                        CompactCloudInfoRow(stringResource(R.string.cloud_last_sync), lastSyncStr)

                                        if (isActive && (googleDriveState == GoogleDriveState.CONNECTION_ERROR || driveQuotaState is DriveQuotaState.Error)) {
                                            val errText = (driveQuotaState as? DriveQuotaState.Error)?.message
                                                ?: (remoteBackupsState as? RemoteBackupsState.Error)?.message
                                                ?: stringResource(R.string.cloud_conn_fail_servers)
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
                                                        Text(stringResource(R.string.cloud_comm_error), fontWeight = FontWeight.Bold, color = StatusError, fontSize = 11.5.sp)
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
                        val categorySummaries = remember(labelBackups, remoteCategorySummaries) {
                            labelBackups.map { it to (remoteCategorySummaries[it.id] ?: CloudBackupCategoryHelper.inspectRemoteFile(it, context)) }
                        }

                        val remoteAppsCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.appsCount else categorySummaries.sumOf { (_, s) -> if (s.hasApps) s.countApps.coerceAtLeast(1) else 0 }
                        val remoteSmsCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.smsCount else categorySummaries.sumOf { (_, s) -> if (s.hasSms) s.countSms.coerceAtLeast(1) else 0 }
                        val remoteCallsCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.callsCount else categorySummaries.sumOf { (_, s) -> if (s.hasCalls) s.countCalls.coerceAtLeast(1) else 0 }
                        val remoteFoldersCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.foldersCount else categorySummaries.sumOf { (_, s) -> if (s.hasFolders) s.countFolders.coerceAtLeast(1) else 0 }
                        val remoteBluetoothCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.bluetoothCount else categorySummaries.sumOf { (_, s) -> if (s.hasBluetooth) s.countBluetooth.coerceAtLeast(1) else 0 }
                        val remoteDnsCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.dnsCount else categorySummaries.sumOf { (_, s) -> if (s.hasDns) s.countDns.coerceAtLeast(1) else 0 }
                        val remoteWifiCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.wifiCount else categorySummaries.sumOf { (_, s) -> if (s.hasWifi) s.countWifi.coerceAtLeast(1) else 0 }
                        val remoteContactsCount = if (cloudLabelComponentSummary.totalBackupsCount > 0) cloudLabelComponentSummary.contactsCount else categorySummaries.sumOf { (_, s) -> if (s.hasContacts) s.countContacts.coerceAtLeast(1) else 0 }

                        val labelBackupsBytes = remember(labelBackups) {
                            labelBackups.sumOf { it.sizeBytes }
                        }
                        val isRootActive = remember {
                            try {
                                com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
                            } catch (_: Exception) {
                                false
                            }
                        }

                        val labelDeviceModel = activeLabelManifest?.deviceModel ?: Build.MODEL
                        val labelAndroidVer = activeLabelManifest?.androidVersion ?: Build.VERSION.RELEASE
                        val labelIsRoot = activeLabelManifest?.rootActive ?: isRootActive

                        // Card Bento "Etiqueta Ativa" (Etiquetas 2.0 Interativa)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(16.dp)) {
                                // 1) Topo do Card com nome, modelo e selos de status
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(primaryAccent.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Smartphone,
                                            contentDescription = null,
                                            tint = primaryAccent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = labelDisplayName,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$labelDeviceModel • Android $labelAndroidVer • Root ${if (labelIsRoot) "ativo" else "inativo"}",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Selos de Status: Verde (Ativa Destino) | Âmbar (Protegida) | Azul (Em visualização)
                                    if (labelIsLocked) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF2D1E05),
                                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                                            modifier = Modifier.wrapContentSize()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(11.dp))
                                                Text(
                                                    text = stringResource(R.string.label_status_protected_readonly),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFF59E0B),
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    } else if (effectiveViewLabel == customDeviceLabel) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF0D281E),
                                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                                            modifier = Modifier.wrapContentSize()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF10B981)))
                                                Text(
                                                    text = stringResource(R.string.label_status_active_target),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF10B981),
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF0F1E36),
                                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                                            modifier = Modifier.wrapContentSize()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                                                Text(
                                                    text = stringResource(R.string.label_status_viewing),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF38BDF8),
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Ações rápidas da etiqueta: Renomear (lápis) | Proteger (cadeado) | Limite de cópias (ajustes)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkBg,
                                        border = BorderStroke(1.dp, DarkCardBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 1. Renomear (ícone lápis)
                                            IconButton(
                                                onClick = {
                                                    renameDisplayNameInput = labelDisplayName
                                                    showRenameDisplayNameDialog = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.label_action_rename), tint = primaryAccent, modifier = Modifier.size(16.dp))
                                            }

                                            // 2. Proteger / Desproteger (ícone cadeado)
                                            IconButton(
                                                onClick = {
                                                    viewModel.toggleLabelLock(effectiveViewLabel) { newLock ->
                                                        context.showSafeToast(if (newLock) "Etiqueta protegida (somente leitura)" else "Proteção desativada com sucesso")
                                                    }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    if (labelIsLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                                    contentDescription = if (labelIsLocked) stringResource(R.string.label_action_unprotect) else stringResource(R.string.label_action_protect),
                                                    tint = if (labelIsLocked) Color(0xFFF59E0B) else TextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            // 3. Limite de cópias / retenção (ícone ajustes)
                                            IconButton(
                                                onClick = { showRetentionDialog = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.label_action_retention), tint = primaryAccent, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // 2) Meio do Card: Armazenamento na Nuvem
                                val totalAccountQuota = remember(activeAccountEmail, multiAccounts) {
                                    val acc = multiAccounts.firstOrNull { it.email == activeAccountEmail }
                                    if (acc != null && acc.quotaTotalBytes > 0) acc.quotaTotalBytes else 15L * 1024 * 1024 * 1024
                                }
                                val storageFraction = (labelBackupsBytes.toFloat() / totalAccountQuota.toFloat()).coerceIn(0.01f, 1f)

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.cloud_storage_title),
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${FormatUtils.formatBytes(labelBackupsBytes)} (${labelBackups.size} arq.)",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { storageFraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = primaryAccent,
                                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // 3) Base do Card: Linha com Trocar/Criar Etiqueta, Verificar e Excluir
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.loadAllDiscoveredManifests(savedDeviceLabels, remoteList)
                                            showLabelManagerModal = true
                                        },
                                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = primaryAccent
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.label_switch_or_create), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = primaryAccent, maxLines = 1, softWrap = false)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            incompleteAuditResult = viewModel.detectIncompleteLabelFiles(effectiveViewLabel)
                                            showIncompleteFilesDialog = true
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(15.dp), tint = primaryAccent)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Verificar", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = primaryAccent)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            if (labelIsLocked) {
                                                context.showSafeToast(context.getString(R.string.label_delete_locked_error))
                                            } else {
                                                labelToDelete = effectiveViewLabel
                                                showDeleteLabelScopeDialog = true
                                            }
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, if (labelIsLocked) TextMuted.copy(alpha = 0.3f) else StatusError.copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (labelIsLocked) TextMuted else StatusError),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (labelIsLocked) TextMuted else StatusError)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Bento Grid "Componentes da Etiqueta" (Partições) - Etiquetas 2.0 Interativas
                        val manifestPartitions = activeLabelManifest?.partitions ?: emptyMap()
                        data class CategoryCardItem(
                            val key: String,
                            val name: String,
                            val unit: String,
                            val icon: androidx.compose.ui.graphics.vector.ImageVector,
                            val color: Color,
                            val count: Int,
                            val updatedAt: Long
                        )
                        val categoryItems = remember(manifestPartitions, remoteAppsCount, remoteSmsCount, remoteCallsCount, remoteFoldersCount, remoteBluetoothCount, remoteDnsCount, remoteWifiCount, remoteContactsCount) {
                            listOf(
                                CategoryCardItem("apps", context.getString(R.string.category_apps), "apps", Icons.Default.Apps, AccentElectricCyan, manifestPartitions["apps"]?.itemCount ?: remoteAppsCount, manifestPartitions["apps"]?.updatedAt ?: 0L),
                                CategoryCardItem("messages", context.getString(R.string.category_messages), "SMS", Icons.Default.Chat, AccentElectricCyan, manifestPartitions["messages"]?.itemCount ?: remoteSmsCount, manifestPartitions["messages"]?.updatedAt ?: 0L),
                                CategoryCardItem("calls", context.getString(R.string.category_calls), "chamadas", Icons.Default.Call, AccentEmeraldGreen, manifestPartitions["calls"]?.itemCount ?: remoteCallsCount, manifestPartitions["calls"]?.updatedAt ?: 0L),
                                CategoryCardItem("folders", context.getString(R.string.category_folders), "pastas", Icons.Default.Folder, AccentNeonPurple, manifestPartitions["folders"]?.itemCount ?: remoteFoldersCount, manifestPartitions["folders"]?.updatedAt ?: 0L),
                                CategoryCardItem("bluetooth", "Bluetooth", "dispositivos", Icons.Default.Bluetooth, AccentNeonBlue, manifestPartitions["bluetooth"]?.itemCount ?: remoteBluetoothCount, manifestPartitions["bluetooth"]?.updatedAt ?: 0L),
                                CategoryCardItem("dns", "DNS", "registros", Icons.Default.Dns, AccentCyberPurple, manifestPartitions["dns"]?.itemCount ?: remoteDnsCount, manifestPartitions["dns"]?.updatedAt ?: 0L),
                                CategoryCardItem("wifi", "Wi-Fi", "redes", Icons.Default.Wifi, StatusWarning, manifestPartitions["wifi"]?.itemCount ?: remoteWifiCount, manifestPartitions["wifi"]?.updatedAt ?: 0L),
                                CategoryCardItem("contacts", context.getString(R.string.category_contacts), "contatos", Icons.Default.Contacts, AccentSunsetOrange, manifestPartitions["contacts"]?.itemCount ?: remoteContactsCount, manifestPartitions["contacts"]?.updatedAt ?: 0L)
                            )
                        }
                        val activePartitionsCount = categoryItems.count { it.count > 0 }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Componentes da Etiqueta",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.cloud_tap_inspect_restore),
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = primaryAccent.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = stringResource(R.string.cloud_active_partitions_count, activePartitionsCount),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 2 Columns Bento Grid
                            val chunkedPartitions = categoryItems.chunked(2)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                chunkedPartitions.forEach { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        pair.forEach { item ->
                                            Card(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        if (item.count > 0) {
                                                            val manifestKeyFp = activeLabelManifest?.cryptoKeyFingerprint
                                                            if (!manifestKeyFp.isNullOrBlank() && manifestKeyFp != currentDeviceFingerprint) {
                                                                showCryptoWarningDialog = item.name
                                                            } else {
                                                                onOpenCategoryRestore(item.name)
                                                            }
                                                        } else {
                                                            showEmptyCategoryDialog = item.name
                                                        }
                                                    },
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(item.color.copy(alpha = 0.12f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(item.icon, contentDescription = null, tint = item.color, modifier = Modifier.size(18.dp))
                                                    }
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = item.name,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(1.dp))
                                                        Text(
                                                            text = "${item.count} ${item.unit}",
                                                            fontSize = 10.5.sp,
                                                            color = if (item.count == 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else item.color,
                                                            maxLines = 1,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                        if (item.updatedAt > 0L) {
                                                            val formattedDate = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(item.updatedAt))
                                                            Text(
                                                                text = formattedDate,
                                                                fontSize = 9.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                    Icon(
                                                        Icons.Default.ChevronRight,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                        if (pair.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            TextButton(
                                onClick = {
                                    if (labelIsLocked) {
                                        context.showSafeToast(context.getString(R.string.label_delete_locked_error))
                                    } else {
                                        labelToDelete = effectiveViewLabel
                                        showDeleteLabelScopeDialog = true
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                enabled = !isDeletingLabelBackups
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusError.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Excluir etiqueta \"$effectiveViewLabel\" e backups",
                                    fontSize = 11.sp,
                                    color = StatusError.copy(alpha = 0.8f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action Buttons for the active account (FOTO 7)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val targetEmail = activeAccountEmail ?: multiAccounts.firstOrNull()?.email
                                    if (targetEmail.isNullOrBlank()) {
                                        context.showSafeToast("Nenhuma conta Google Drive conectada.")
                                    } else {
                                        context.showSafeToast("Sincronizando conta ativa...")
                                        viewModel.refreshActiveAccountData(targetEmail, isManual = true) {
                                            val qErr = (viewModel.driveQuotaState.value as? DriveQuotaState.Error)?.message
                                            val bErr = (viewModel.remoteBackupsState.value as? RemoteBackupsState.Error)?.message
                                            val err = qErr ?: bErr
                                            if (err != null) {
                                                context.showSafeToast(context.getString(R.string.cloud_sync_err, err), Toast.LENGTH_LONG)
                                            } else {
                                                context.showSafeToast(context.getString(R.string.cloud_sync_success_toast))
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                enabled = !isRefreshingActiveAccount
                            ) {
                                if (isRefreshingActiveAccount) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.dashboard_syncing), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.Black, maxLines = 1, softWrap = false)
                                } else {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.cloud_sync_now), fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val targetEmail = activeAccountEmail ?: multiAccounts.firstOrNull()?.email
                                    if (targetEmail.isNullOrBlank()) {
                                        context.showSafeToast("Nenhuma conta Google Drive conectada.")
                                    } else {
                                        context.showSafeToast("Verificando integridade dos backups...")
                                        viewModel.refreshActiveAccountData(targetEmail, isManual = true) {
                                            val qErr = (viewModel.driveQuotaState.value as? DriveQuotaState.Error)?.message
                                            val bErr = (viewModel.remoteBackupsState.value as? RemoteBackupsState.Error)?.message
                                            val err = qErr ?: bErr
                                            if (err != null) {
                                                context.showSafeToast(context.getString(R.string.cloud_verify_err, err), Toast.LENGTH_LONG)
                                            } else {
                                                context.showSafeToast(context.getString(R.string.cloud_integrity_verified_toast))
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = primaryAccent
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                enabled = !isRefreshingActiveAccount
                            ) {
                                if (isRefreshingActiveAccount) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = primaryAccent, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Verificando...", color = primaryAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
                                } else {
                                    Icon(Icons.Default.Verified, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Verificar", color = primaryAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
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
                                Icon(Icons.Default.BugReport, contentDescription = context.getString(R.string.cloud_tab_diagnostics), tint = TextSecondary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(context.getString(R.string.cloud_tab_diagnostics), color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
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
                                Text("Ver registros", color = TextSecondary, fontSize = 12.sp)
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
                                    Text(stringResource(R.string.cloud_auto_sync_title), fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                Text(stringResource(R.string.cloud_auto_sync_desc), fontSize = 11.sp, color = TextSecondary)

                                Spacer(modifier = Modifier.height(12.dp))

                                SyncSettingCheckbox(
                                    label = stringResource(R.string.cloud_auto_sync_active),
                                    checked = syncSettings.isAutoSyncEnabled,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(isAutoSyncEnabled = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = stringResource(R.string.cloud_wifi_only),
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
                                    label = stringResource(R.string.cloud_sync_immediate),
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
                    val queueItems = remember(syncQueue, activeAccountEmail) {
                        if (activeAccountEmail != null) {
                            syncQueue.filter { it.accountEmail == null || it.accountEmail == activeAccountEmail }
                        } else {
                            syncQueue
                        }
                    }
                    val completedCount = queueItems.count { it.status == QueueStatus.COMPLETED }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.cloud_transfer_queue_title), fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text(stringResource(R.string.cloud_transfer_queue_desc), fontSize = 12.sp, color = TextSecondary)
                                }
                                if (queueItems.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = { viewModel.clearSyncQueue(activeAccountEmail) }
                                        ) {
                                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = StatusError, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Limpar tudo", color = StatusError, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Fast top header when there are completed items
                        if (completedCount > 0) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = StatusSuccess.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.DoneAll, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "$completedCount item(ns) concluído(s)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = StatusSuccess
                                            )
                                        }
                                        TextButton(
                                            onClick = { viewModel.clearCompletedSyncQueue(activeAccountEmail) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(stringResource(R.string.queue_clear_completed), color = StatusSuccess, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        if (activeAccountEmail != null && syncQueue.isNotEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = primaryAccent.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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
                                        Text(stringResource(R.string.cloud_queue_empty), color = TextSecondary)
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
                                        Text(stringResource(R.string.cloud_last_restore_log), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    }
                                    if (!lastRestoreLog.isNullOrBlank()) {
                                        TextButton(onClick = {
                                            clipboardManager.setText(AnnotatedString(lastRestoreLog!!))
                                            context.showSafeToast(context.getString(R.string.cloud_restore_log_copied))
                                        }) {
                                            Text("Copiar", color = primaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                if (lastRestoreLog.isNullOrBlank()) {
                                    Text(stringResource(R.string.cloud_no_recent_restore_log), fontSize = 12.sp, color = TextMuted)
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
                                Text(stringResource(R.string.cloud_diagnose_connection), fontWeight = FontWeight.Bold)
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
                                        text = stringResource(R.string.cloud_no_diag_tests_run),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.cloud_tap_diagnose_button),
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
                                        Text(stringResource(R.string.cloud_last_sys_err_crash), fontWeight = FontWeight.Bold, color = StatusError, fontSize = 13.sp)
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
                                                context.showSafeToast(context.getString(R.string.cloud_log_copied_success))
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

            if (isFabExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isFabExpanded = false }
                )
            }

            AnimatedVisibility(
                visible = shouldShowFab,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End
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
                                    val targetEmail = activeAccountEmail ?: multiAccounts.firstOrNull()?.email
                                    if (targetEmail.isNullOrBlank()) {
                                        context.showSafeToast("Nenhuma conta Google Drive conectada.")
                                    } else {
                                        openFilesLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                modifier = Modifier.border(1.dp, Color(0xFF263244), RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Color(0xFF161F2E),
                                contentColor = primaryAccent,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                                icon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
                                text = { Text(stringResource(R.string.cloud_send_files), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            )

                            ExtendedFloatingActionButton(
                                onClick = {
                                    isFabExpanded = false
                                    val targetEmail = activeAccountEmail ?: multiAccounts.firstOrNull()?.email
                                    if (targetEmail.isNullOrBlank()) {
                                        context.showSafeToast("Nenhuma conta Google Drive conectada.")
                                    } else {
                                        openFolderLauncher.launch(null)
                                    }
                                },
                                modifier = Modifier.border(1.dp, Color(0xFF263244), RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Color(0xFF161F2E),
                                contentColor = primaryAccent,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                                icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                text = { Text("Enviar Pasta", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            )

                            ExtendedFloatingActionButton(
                                onClick = {
                                    isFabExpanded = false
                                    val targetEmail = activeAccountEmail ?: multiAccounts.firstOrNull()?.email
                                    if (targetEmail.isNullOrBlank()) {
                                        context.showSafeToast("Nenhuma conta Google Drive conectada.")
                                    } else {
                                        showBackupSelectionDialog = true
                                    }
                                },
                                modifier = Modifier.border(1.dp, Color(0xFF263244), RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Color(0xFF161F2E),
                                contentColor = primaryAccent,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
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
                            contentDescription = stringResource(R.string.cloud_gdrive_actions)
                        )
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
                    Text(stringResource(R.string.cloud_app_closed_last_time), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.cloud_unexpected_failure_notice),
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
                        context.showSafeToast(context.getString(R.string.cloud_log_copied_clipboard))
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
                Text(stringResource(R.string.cloud_disconnect_account_prompt, acc.email), color = TextSecondary, fontSize = 13.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        val email = acc.email
                        accountToRemove = null
                        viewModel.removeDriveAccount(email)
                        context.showSafeToast("Conta removida.")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("REMOVER", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToRemove = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
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
                        stringResource(R.string.cloud_delete_tag_prompt, targetLabel, targetBackups.size),
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
                            Text(stringResource(R.string.cloud_total_files_drive, targetBackups.size), fontSize = 11.sp, color = TextMuted)
                            val totalBytes = targetBackups.sumOf { it.sizeBytes }
                            Text(stringResource(R.string.cloud_space_to_free, formatStorageSize(totalBytes)), fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.cloud_delete_action_warning),
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
                            context.showSafeToast(context.getString(R.string.cloud_deleted_backups_toast, deletedCount))
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
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
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
                        placeholder = { Text(stringResource(R.string.cloud_tag_example_hint), fontSize = 12.sp, color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Limite de retenção de backups:", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "Ilimitado", 1 to "1 cópia", 2 to "2 cópias", 3 to "3 cópias").forEach { (ret, label) ->
                            FilterChip(
                                selected = newLabelRetentionChoice == ret,
                                onClick = { newLabelRetentionChoice = ret },
                                label = { Text(label, fontSize = 10.5.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = primaryAccent
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
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
                            Text(stringResource(R.string.cloud_new_backups_tag_notice), fontSize = 10.sp, color = TextMuted)
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
                            viewModel.setLabelRetentionCount(trimmed, newLabelRetentionChoice)
                            showCreateLabelDialog = false
                            context.showSafeToast(context.getString(R.string.cloud_tag_created_success, trimmed))
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
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
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
                    Text(stringResource(R.string.cloud_metadata_update_notice), fontSize = 11.sp, color = TextMuted)
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
                                context.showSafeToast("$count backup(s) atualizados com a nova etiqueta.")
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
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    if (showLabelManagerModal) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        val manifestsMap = remember(allDiscoveredManifests) {
            allDiscoveredManifests.associateBy { m: LabelManifest -> m.labelId.lowercase() }
        }
        val allDiscoveredLabels = remember(remoteList, savedDeviceLabels, customDeviceLabel, allDiscoveredManifests) {
            val list = mutableListOf<String>()
            val cLabel = customDeviceLabel.trim()
            if (cLabel.isNotBlank()) list.add(cLabel)
            savedDeviceLabels.forEach { s ->
                val tr = s.trim()
                if (tr.isNotBlank() && !list.any { it.equals(tr, ignoreCase = true) }) list.add(tr)
            }
            remoteList.forEach { f ->
                val p = f.appProperties["deviceLabel"]?.trim()
                if (!p.isNullOrBlank() && !list.any { it.equals(p, ignoreCase = true) }) list.add(p)
            }
            allDiscoveredManifests.forEach { m ->
                if (!list.any { it.equals(m.labelId, ignoreCase = true) }) list.add(m.labelId)
            }
            list
        }

        val isDeviceUnlinked = customDeviceLabel.isBlank() || customDeviceLabel == Build.MODEL
        val matchingCloudManifest = allDiscoveredManifests.firstOrNull {
            it.deviceModel.equals(Build.MODEL, ignoreCase = true) && !it.labelId.equals(customDeviceLabel, ignoreCase = true)
        }

        ModalBottomSheet(
            onDismissRequest = { showLabelManagerModal = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = DarkCardBorder) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Label, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.label_manager_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                    }
                    IconButton(
                        onClick = { showLabelManagerModal = false },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Post-format recognition banner
                if (isDeviceUnlinked && matchingCloudManifest != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = primaryAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Aparelho reconhecido na nuvem",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = primaryAccent
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Encontramos a etiqueta '${matchingCloudManifest.displayName}' deste mesmo modelo (${matchingCloudManifest.deviceModel}) no Google Drive.",
                                fontSize = 11.5.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.setCustomDeviceLabel(matchingCloudManifest.labelId)
                                        viewModel.setSelectedViewLabel(matchingCloudManifest.labelId)
                                        showLabelManagerModal = false
                                        context.showSafeToast("Aparelho vinculado com sucesso à etiqueta: ${matchingCloudManifest.displayName}")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Vincular a esta etiqueta", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                                OutlinedButton(
                                    onClick = { showCreateLabelDialog = true },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Criar nova etiqueta", fontSize = 11.sp, color = TextPrimary)
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.cloud_select_tag_prompt),
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
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(allDiscoveredLabels, key = { it }) { labelItem ->
                            val manifest = manifestsMap[labelItem.lowercase()]
                            val isSelectedView = labelItem.equals(effectiveViewLabel, ignoreCase = true)
                            val isActiveTarget = labelItem.equals(customDeviceLabel, ignoreCase = true)
                            val isLocked = manifest?.isLocked ?: false
                            val displayName = manifest?.displayName?.ifBlank { labelItem } ?: labelItem
                            val deviceModel = manifest?.deviceModel ?: "Dispositivo"
                            val androidVer = manifest?.androidVersion ?: ""
                            val rootStr = if (manifest?.rootActive == true) "Root ativo" else "Sem root"
                            val subInfo = if (androidVer.isNotBlank()) "$deviceModel • Android $androidVer • $rootStr" else deviceModel
                            val fileCount = manifest?.packageCount ?: remoteList.count { f ->
                                val prop = f.appProperties["deviceLabel"]?.trim()
                                if (!prop.isNullOrBlank()) prop.equals(labelItem, ignoreCase = true) else f.name.contains(labelItem, ignoreCase = true)
                            }
                            val bytesTotal = manifest?.totalBytes ?: remoteList.filter { f ->
                                val prop = f.appProperties["deviceLabel"]?.trim()
                                if (!prop.isNullOrBlank()) prop.equals(labelItem, ignoreCase = true) else f.name.contains(labelItem, ignoreCase = true)
                            }.sumOf { it.sizeBytes }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelectedView) primaryAccent.copy(alpha = 0.08f) else DarkBg,
                                border = BorderStroke(1.dp, if (isSelectedView) primaryAccent.copy(alpha = 0.6f) else DarkCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                if (manifest?.displayName.isNullOrBlank().not() && manifest?.displayName != labelItem) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("($labelItem)", fontSize = 10.sp, color = TextMuted)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(subInfo, fontSize = 11.sp, color = TextSecondary)
                                            Text("${FormatUtils.formatBytes(bytesTotal)} • $fileCount arquivo(s)", fontSize = 10.5.sp, color = TextMuted)
                                        }

                                        // Badges
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (isLocked) {
                                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFF59E0B).copy(alpha = 0.15f)) {
                                                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(10.dp))
                                                        Spacer(modifier = Modifier.width(3.dp))
                                                        Text("Protegida", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                                    }
                                                }
                                            }
                                            if (isActiveTarget) {
                                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF10B981).copy(alpha = 0.15f)) {
                                                    Text("Ativa", fontSize = 9.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                }
                                            } else if (isSelectedView) {
                                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF38BDF8).copy(alpha = 0.15f)) {
                                                    Text("Visualizando", fontSize = 9.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.setSelectedViewLabel(labelItem)
                                                showLabelManagerModal = false
                                            },
                                            modifier = Modifier.height(32.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                        ) {
                                            Text("Visualizar", fontSize = 11.sp, color = primaryAccent)
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Button(
                                            onClick = {
                                                if (isLocked) {
                                                    context.showSafeToast("Esta etiqueta está protegida (somente leitura).")
                                                } else {
                                                    viewModel.setCustomDeviceLabel(labelItem)
                                                    viewModel.setSelectedViewLabel(labelItem)
                                                    showLabelManagerModal = false
                                                    context.showSafeToast("Etiqueta '$displayName' definida como ativa.")
                                                }
                                            },
                                            enabled = !isLocked && !isActiveTarget,
                                            modifier = Modifier.height(32.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (isActiveTarget) "Etiqueta Ativa" else "Definir como ativa", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isActiveTarget || isLocked) TextMuted else Color.Black)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        showLabelManagerModal = false
                        showCreateLabelDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.label_new_button), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showRenameDisplayNameDialog) {
        var inputName by remember { mutableStateOf(renameDisplayNameInput.ifBlank { effectiveViewLabel }) }
        AlertDialog(
            onDismissRequest = { showRenameDisplayNameDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.label_action_rename), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text("Digite o nome amigável para a etiqueta \"$effectiveViewLabel\":", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("O nome amigável é salvo diretamente no manifesto sem mover seus backups no Google Drive.", fontSize = 10.5.sp, color = TextMuted)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = inputName.trim()
                        if (trimmed.isNotBlank()) {
                            viewModel.renameLabelDisplayName(effectiveViewLabel, trimmed) { ok ->
                                context.showSafeToast(if (ok) "Nome amigável atualizado com sucesso" else "Falha ao atualizar nome")
                            }
                            showRenameDisplayNameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    enabled = inputName.isNotBlank()
                ) {
                    Text("Salvar", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDisplayNameDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    if (showRetentionDialog) {
        var selectedRetention by remember { mutableIntStateOf(activeLabelManifest?.retentionCount ?: 2) }
        val manifestFingerprint = activeLabelManifest?.cryptoKeyFingerprint.orEmpty()
        val isCompatibleKey = manifestFingerprint.isBlank() || manifestFingerprint == currentDeviceFingerprint

        AlertDialog(
            onDismissRequest = { showRetentionDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.label_action_retention), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Selecione o limite de cópias automáticas mantidas para a etiqueta \"$effectiveViewLabel\":",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    listOf(
                        0 to "Ilimitado (manter todas as cópias)",
                        1 to "1 cópia (substituir sempre pela mais recente)",
                        2 to "2 cópias (recomendado)",
                        3 to "3 cópias"
                    ).forEach { (ret, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRetention = ret }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedRetention == ret,
                                onClick = { selectedRetention = ret },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, fontSize = 12.sp, color = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCompatibleKey) StatusSuccess.copy(alpha = 0.12f) else StatusWarning.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, if (isCompatibleKey) StatusSuccess.copy(alpha = 0.3f) else StatusWarning.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isCompatibleKey) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isCompatibleKey) StatusSuccess else StatusWarning,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isCompatibleKey) "Chave de criptografia compatível" else "Chave de criptografia diferente",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCompatibleKey) StatusSuccess else StatusWarning
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Hash ativo: $currentDeviceFingerprint ${if (manifestFingerprint.isNotBlank()) "• Manifesto: $manifestFingerprint" else ""}",
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setLabelRetentionCount(effectiveViewLabel, selectedRetention) { ok ->
                            context.showSafeToast(if (ok) "Limite de retenção salvo com sucesso" else "Falha ao salvar limite de retenção")
                        }
                        showRetentionDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Salvar", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetentionDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    if (showIncompleteFilesDialog) {
        val audit = incompleteAuditResult
        val count = audit?.incompleteFiles?.size ?: 0
        val bytes = audit?.totalBytes ?: 0L

        AlertDialog(
            onDismissRequest = { if (!isCleaningIncomplete) showIncompleteFilesDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verificação de Integridade", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    if (count == 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StatusSuccess.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Nenhum arquivo incompleto detectado. Todos os backups desta etiqueta estão consistentes.", fontSize = 12.sp, color = TextPrimary)
                            }
                        }
                    } else {
                        Text(
                            text = "Foram encontrados $count arquivo(s) incompleto(s) de envios interrompidos, ocupando ${FormatUtils.formatBytes(bytes)} no Google Drive:",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(audit?.incompleteFiles ?: emptyList()) { f ->
                                Surface(shape = RoundedCornerShape(6.dp), color = DarkBg, modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(f.name, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(FormatUtils.formatBytes(f.sizeBytes), fontSize = 10.sp, color = TextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (count > 0) {
                    Button(
                        onClick = {
                            isCleaningIncomplete = true
                            viewModel.cleanupIncompleteFilesForLabel(effectiveViewLabel) { cleaned ->
                                isCleaningIncomplete = false
                                showIncompleteFilesDialog = false
                                context.showSafeToast(context.getString(R.string.label_incomplete_cleanup_success, cleaned))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                        enabled = !isCleaningIncomplete
                    ) {
                        if (isCleaningIncomplete) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Limpando...", color = Color.Black)
                        } else {
                            Text(stringResource(R.string.label_clean_incomplete_button), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    TextButton(onClick = { showIncompleteFilesDialog = false }) {
                        Text("Fechar", color = primaryAccent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (count > 0) {
                    TextButton(onClick = { showIncompleteFilesDialog = false }, enabled = !isCleaningIncomplete) {
                        Text(stringResource(R.string.action_cancel), color = TextSecondary)
                    }
                }
            }
        )
    }

    if (showDeleteLabelScopeDialog && labelToDelete.isNotBlank()) {
        var chosenScope by remember { mutableStateOf(LabelDeleteScope.CLOUD_ONLY) }
        var isExecutingDelete by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isExecutingDelete) showDeleteLabelScopeDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.label_delete_scope_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Escolha o escopo de exclusão para a etiqueta \"$labelToDelete\". Espaço a ser liberado na nuvem: ${FormatUtils.formatBytes(labelBackupsBytes)}.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    listOf(
                        Triple(LabelDeleteScope.CLOUD_ONLY, stringResource(R.string.label_delete_scope_cloud_only), "Remove todos os arquivos e o manifesto do Google Drive. Mantém dados locais."),
                        Triple(LabelDeleteScope.DEVICE_ONLY, stringResource(R.string.label_delete_scope_device_only), "Desvincula a etiqueta local e remove seu registro deste aparelho. Mantém backups na nuvem."),
                        Triple(LabelDeleteScope.BOTH, stringResource(R.string.label_delete_scope_both), "Exclusão completa: apaga da nuvem e desvincula do dispositivo.")
                    ).forEach { (scope, title, desc) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (chosenScope == scope) StatusError.copy(alpha = 0.12f) else DarkBg,
                            border = BorderStroke(1.dp, if (chosenScope == scope) StatusError else DarkCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosenScope = scope }
                                .padding(vertical = 4.dp)
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = chosenScope == scope,
                                    onClick = { chosenScope = scope },
                                    colors = RadioButtonDefaults.colors(selectedColor = StatusError)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text(desc, fontSize = 10.5.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isExecutingDelete = true
                        viewModel.deleteLabelWithScope(labelToDelete, chosenScope) { deletedCount ->
                            isExecutingDelete = false
                            showDeleteLabelScopeDialog = false
                            context.showSafeToast("Exclusão concluída: $deletedCount item(ns) processados.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    enabled = !isExecutingDelete
                ) {
                    if (isExecutingDelete) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Excluindo...", color = Color.White)
                    } else {
                        Text("Confirmar Exclusão", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteLabelScopeDialog = false }, enabled = !isExecutingDelete) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    if (showEmptyCategoryDialog != null) {
        val cat = showEmptyCategoryDialog!!
        AlertDialog(
            onDismissRequest = { showEmptyCategoryDialog = null },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(cat, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.cloud_empty_category_prompt, cat),
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmptyCategoryDialog = null
                        if (labelIsLocked) {
                            context.showSafeToast(context.getString(R.string.label_locked_backup_error))
                        } else {
                            showBackupSelectionDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text(stringResource(R.string.cloud_backup_category_now), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyCategoryDialog = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    if (showCryptoWarningDialog != null) {
        val cat = showCryptoWarningDialog!!
        AlertDialog(
            onDismissRequest = { showCryptoWarningDialog = null },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.label_crypto_warning_title), fontWeight = FontWeight.Bold, color = StatusWarning, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.label_crypto_warning_desc),
                    fontSize = 12.5.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val c = cat
                        showCryptoWarningDialog = null
                        onOpenCategoryRestore(c)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusWarning)
                ) {
                    Text(stringResource(R.string.label_crypto_warning_proceed), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCryptoWarningDialog = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    if (selectedCategoryModal != null) {
        val catName = selectedCategoryModal!!
        val matchingBackups = remember(labelBackups, catName, remoteCategorySummaries) {
            val filtered = labelBackups.filter { file ->
                val s = remoteCategorySummaries[file.id] ?: CloudBackupCategoryHelper.inspectRemoteFile(file, context)
                s.containsCategory(catName)
            }
            if (filtered.isEmpty() && labelBackups.isNotEmpty()) {
                labelBackups
            } else {
                filtered
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
                            "apps" -> Icons.Default.Apps
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
                                    stringResource(R.string.cloud_no_backup_cat_tag, catName),
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
                                val summary = remember(file) { CloudBackupCategoryHelper.inspectRemoteFile(file, context) }
                                val count = when (catName.lowercase()) {
                                    "apps" -> summary.countApps
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
                                                    val localFile = resolveLocalMirrorFile(context, viewModel.backupRecords.value, file.name, file.id, file.sizeBytes)
                                                    val rec = BackupRecord(
                                                        id = file.id,
                                                        title = file.name,
                                                        filePath = localFile?.absolutePath ?: "",
                                                        sizeBytes = file.sizeBytes,
                                                        checksumSha256 = file.md5Checksum ?: "",
                                                        isEncrypted = file.name.endsWith(".svb") || file.name.endsWith(".resync"),
                                                        timestamp = file.modifiedTime ?: file.createdTime ?: System.currentTimeMillis(),
                                                        itemCount = 1,
                                                        status = BackupStatus.COMPLETED,
                                                        destination = "Google Drive",
                                                        syncStatus = if (localFile != null && localFile.exists()) SyncStatus.SYNCED else SyncStatus.UPLOADED,
                                                        driveFileId = file.id
                                                    )
                                                    selectedCategoryModal = null
                                                    selectiveRestoreBackup = rec
                                                    selectiveRestoreCategory = catName
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Icon(Icons.Default.Restore, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(stringResource(R.string.action_restore), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
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
        val allLocalBackups by viewModel.backupRecords.collectAsState()
        val localBackups = remember(allLocalBackups) {
            allLocalBackups.filter { File(it.filePath).exists() && File(it.filePath).length() > 0L }
        }
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
                        Text(stringResource(R.string.cloud_no_local_backup_found), color = TextMuted, fontSize = 13.sp)
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
                                    message = context.getString(R.string.cloud_added_backups_toast, toUpload.size),
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
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
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
                        text = stringResource(R.string.cloud_validating_creds),
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
                    Text(stringResource(R.string.cloud_google_account_failure), fontWeight = FontWeight.Bold, color = StatusError)
                }
            },
            text = {
                Column {
                    Text(
                        text = driveAuthErrorMessage ?: stringResource(R.string.cloud_conn_error_gdrive),
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.cloud_check_net_perm),
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
                                        context.showSafeToast(context.getString(R.string.gdrive_connected_success))
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
            title = { Text(stringResource(R.string.cloud_delete_cloud_backup_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
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
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
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
    val statusText = when (item.status) {
        QueueStatus.COMPLETED -> stringResource(R.string.queue_status_completed)
        QueueStatus.RUNNING -> stringResource(R.string.queue_status_running)
        QueueStatus.PAUSED -> stringResource(R.string.queue_status_paused)
        QueueStatus.FAILED -> stringResource(R.string.queue_status_failed)
        else -> stringResource(R.string.queue_status_pending)
    }
    val statusColor = when (item.status) {
        QueueStatus.COMPLETED -> StatusSuccess
        QueueStatus.RUNNING -> primaryAccent
        QueueStatus.PAUSED -> StatusWarning
        QueueStatus.FAILED -> StatusError
        else -> TextSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Status Icon + Title + Discrete Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.status == QueueStatus.COMPLETED) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                } else {
                    val statusIcon = when (item.status) {
                        QueueStatus.RUNNING -> Icons.Default.CloudUpload
                        QueueStatus.PAUSED -> Icons.Default.PauseCircle
                        QueueStatus.FAILED -> Icons.Default.Error
                        else -> Icons.Default.Schedule
                    }
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.backupTitle,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Row 2: Contextual thin progress bar
            val progress = if (item.totalBytes > 0) (item.progressBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else if (item.status == QueueStatus.COMPLETED) 1f else 0f
            SmoothLinearProgressBar(
                progress = progress,
                color = statusColor,
                trackColor = DarkCardBorder,
                height = 4.dp,
                shapeRadius = 2.dp
            )

            if (!item.errorMessage.isNullOrBlank()) {
                val errPrefix = if (!item.errorCode.isNullOrBlank()) "[${item.errorCode}] " else ""
                Text(
                    text = "$errPrefix${item.errorMessage}",
                    fontSize = 10.5.sp,
                    color = StatusError,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Row 3: Transferred size + Priority + Compact Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "%.1f MB / %.1f MB".format(
                        java.util.Locale.US,
                        item.progressBytes.toDouble() / (1024 * 1024),
                        item.totalBytes.toDouble() / (1024 * 1024)
                    ) + if (item.status != QueueStatus.COMPLETED) " • P${item.priority}" else "",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.status == QueueStatus.RUNNING) {
                        TextButton(
                            onClick = onPause,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Pausar", fontSize = 11.sp, color = StatusWarning)
                        }
                    } else if (item.status == QueueStatus.PAUSED) {
                        TextButton(
                            onClick = onResume,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Continuar", fontSize = 11.sp, color = primaryAccent)
                        }
                    }

                    if (item.status == QueueStatus.RUNNING || item.status == QueueStatus.PENDING) {
                        TextButton(
                            onClick = { onCancel?.invoke() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(stringResource(R.string.action_cancel), fontSize = 11.sp, color = StatusError)
                        }
                    }

                    if (item.status == QueueStatus.FAILED) {
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Tentar novamente", fontSize = 11.sp, color = primaryAccent)
                        }
                    }

                    // O botão Priorizar deve sumir após a conclusão do item
                    if (item.status != QueueStatus.COMPLETED) {
                        TextButton(
                            onClick = onPrioritize,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Priorizar", fontSize = 11.sp, color = TextSecondary)
                        }
                    }

                    TextButton(
                        onClick = onRemove,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Remover", fontSize = 11.sp, color = StatusError)
                    }
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

private fun resolveLocalMirrorFile(
    context: Context,
    localRecords: List<BackupRecord>,
    fileName: String,
    driveFileId: String?,
    fileSizeBytes: Long = 0L
): File? {
    // 1. Procurar nos registros do Room do ViewModel
    localRecords.firstOrNull {
        (!driveFileId.isNullOrBlank() && it.driveFileId == driveFileId) ||
        (it.title.isNotBlank() && (it.title == fileName || it.filePath.endsWith(fileName) || it.filePath.endsWith("${fileName}.resync")))
    }?.let { rec ->
        val f = File(rec.filePath)
        if (f.exists() && f.length() > 0L) return f
    }

    // 2. Busca profunda e robusta nos diretórios e subpastas de backup locais
    return com.resync.app.data.model.BackupPaths.findLocalMirrorFile(
        backupId = driveFileId,
        title = fileName,
        context = context,
        expectedSizeBytes = fileSizeBytes,
        driveFileId = driveFileId
    )
}

