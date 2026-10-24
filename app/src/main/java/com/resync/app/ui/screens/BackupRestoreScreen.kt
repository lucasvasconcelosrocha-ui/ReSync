package com.resync.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.R
import com.resync.app.cloud.gdrive.DriveFileMetadata
import com.resync.app.data.model.*
import com.resync.app.ui.components.cards.BackupItemCard
import com.resync.app.ui.components.cards.LegacyBackupCard
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    viewModel: MainViewModel,
    initialFilter: BackupOriginFilter = BackupOriginFilter.ALL,
    autoOpenSingle: Boolean = false,
    onBack: () -> Unit,
    onNavigateToActive: () -> Unit = {}
) {
    val backups by viewModel.backupRecords.collectAsStateWithLifecycle()
    val remoteBackups by viewModel.remoteDriveBackups.collectAsStateWithLifecycle()
    val legacySystemItems by viewModel.legacyBackups.collectAsStateWithLifecycle()
    val activeAccountEmail by viewModel.activeAccountEmail.collectAsStateWithLifecycle()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeFilter by remember { mutableStateOf(initialFilter) }
    var selectedBackupForSelection by remember { mutableStateOf<Pair<BackupRecord, RestoreSource>?>(null) }
    var backupToDelete by remember { mutableStateOf<UnifiedBackupEntry?>(null) }
    var legacyToDelete by remember { mutableStateOf<RestoreSystemItem?>(null) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    var hasAutoOpened by remember { mutableStateOf(false) }

    var importMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            viewModel.importBackupFromUri(uri) { res ->
                isImporting = false
                if (res.isSuccess) {
                    val imported = res.getOrThrow()
                    importMessage = "Backup '${imported.title}' importado com sucesso!"
                    selectedBackupForSelection = Pair(imported, RestoreSource.LOCAL)
                } else {
                    importMessage = "Falha ao importar backup: ${res.exceptionOrNull()?.message}"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshBackups()
        viewModel.fetchRemoteDriveBackups()
    }

    // Se uma seleção de restauração estiver ativa, exibe a tela de seleção unificada
    if (selectedBackupForSelection != null) {
        val (b, src) = selectedBackupForSelection!!
        RestoreSelectionScreen(
            backup = b,
            source = src,
            viewModel = viewModel,
            onBack = {
                if (autoOpenSingle && hasAutoOpened) {
                    onBack()
                } else {
                    selectedBackupForSelection = null
                }
            },
            onFinished = {
                selectedBackupForSelection = null
                viewModel.refreshBackups()
                viewModel.fetchRemoteDriveBackups()
                if (autoOpenSingle && hasAutoOpened) {
                    onBack()
                }
            },
            onNavigateToActive = onNavigateToActive
        )
        return
    }

    // Mesclar backups locais e remotos em lista única
    val unifiedEntries = remember(backups, remoteBackups) {
        val validLocal = backups.filter {
            it.filePath.isNotBlank() && File(it.filePath).exists() && !it.filePath.contains("/staging")
        }
        val validRemote = remoteBackups.filter {
            !it.name.endsWith(".index.json") && !it.name.contains("legacy", ignoreCase = true)
        }

        val entries = mutableListOf<UnifiedBackupEntry>()
        val matchedRemoteIds = mutableSetOf<String>()

        for (local in validLocal) {
            val remoteMatch = validRemote.find { remote ->
                (local.driveFileId != null && remote.id == local.driveFileId) ||
                local.title.equals(remote.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip"), ignoreCase = true) ||
                (local.checksumSha256.isNotBlank() && remote.md5Checksum.equals(local.checksumSha256, ignoreCase = true))
            }
            if (remoteMatch != null) {
                matchedRemoteIds.add(remoteMatch.id)
                entries.add(
                    UnifiedBackupEntry(
                        record = local.copy(
                            destination = "Ambos",
                            driveFileId = local.driveFileId ?: remoteMatch.id
                        ),
                        hasLocal = true,
                        hasDrive = true,
                        remoteFileId = remoteMatch.id
                    )
                )
            } else {
                entries.add(
                    UnifiedBackupEntry(
                        record = local.copy(destination = "Dispositivo"),
                        hasLocal = true,
                        hasDrive = false
                    )
                )
            }
        }

        for (remote in validRemote) {
            if (!matchedRemoteIds.contains(remote.id)) {
                val driveRecord = BackupRecord(
                    id = remote.id,
                    title = remote.name.removeSuffix(".resync").removeSuffix(".svb").removeSuffix(".zip"),
                    filePath = "",
                    sizeBytes = remote.sizeBytes,
                    checksumSha256 = remote.md5Checksum ?: "",
                    isEncrypted = false,
                    timestamp = remote.modifiedTime,
                    itemCount = 0,
                    status = BackupStatus.COMPLETED,
                    destination = "Google Drive",
                    syncStatus = SyncStatus.SYNCED,
                    driveFileId = remote.id
                )
                entries.add(
                    UnifiedBackupEntry(
                        record = driveRecord,
                        hasLocal = false,
                        hasDrive = true,
                        remoteFileId = remote.id
                    )
                )
            }
        }

        entries.sortedByDescending { it.record.timestamp }
    }

    LaunchedEffect(unifiedEntries, autoOpenSingle) {
        if (autoOpenSingle && !hasAutoOpened && unifiedEntries.size == 1) {
            hasAutoOpened = true
            val single = unifiedEntries.first()
            val source = if (single.hasLocal) RestoreSource.LOCAL else RestoreSource.DRIVE
            selectedBackupForSelection = Pair(single.record, source)
        }
    }

    val filteredEntries = remember(unifiedEntries, activeFilter) {
        when (activeFilter) {
            BackupOriginFilter.ALL -> unifiedEntries
            BackupOriginFilter.LOCAL -> unifiedEntries.filter { it.hasLocal }
            BackupOriginFilter.DRIVE -> unifiedEntries.filter { it.hasDrive }
        }
    }

    val totalCount = unifiedEntries.size
    val localCount = unifiedEntries.count { it.hasLocal }
    val driveCount = unifiedEntries.count { it.hasDrive }

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
                            text = "Backups",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = primaryAccent.copy(alpha = 0.2f),
                                contentColor = primaryAccent
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            enabled = !isImporting,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = "Importar", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "IMPORTAR",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        IconButton(onClick = {
                            viewModel.refreshBackups()
                            viewModel.fetchRemoteDriveBackups()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recarregar", tint = TextSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkBg,
                        titleContentColor = TextPrimary
                    )
                )

                // Filtro por origem: [ Todos ] [ Dispositivo ] [ Google Drive ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = activeFilter == BackupOriginFilter.ALL,
                        onClick = { activeFilter = BackupOriginFilter.ALL },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Inventory2,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                "Todos ($totalCount)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        modifier = Modifier.height(38.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = primaryAccent.copy(alpha = 0.2f),
                            selectedLabelColor = primaryAccent,
                            selectedLeadingIconColor = primaryAccent
                        )
                    )

                    FilterChip(
                        selected = activeFilter == BackupOriginFilter.LOCAL,
                        onClick = { activeFilter = BackupOriginFilter.LOCAL },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                "Dispositivo ($localCount)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        modifier = Modifier.height(38.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentSunsetOrange.copy(alpha = 0.2f),
                            selectedLabelColor = AccentSunsetOrange,
                            selectedLeadingIconColor = AccentSunsetOrange
                        )
                    )

                    FilterChip(
                        selected = activeFilter == BackupOriginFilter.DRIVE,
                        onClick = { activeFilter = BackupOriginFilter.DRIVE },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                "Google Drive ($driveCount)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        modifier = Modifier.height(38.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = AccentElectricCyan,
                            selectedLeadingIconColor = AccentElectricCyan
                        )
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                coroutineScope.launch {
                    viewModel.refreshBackups()
                    viewModel.fetchRemoteDriveBackups()
                    delay(500)
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
            ) {
                if (!importMessage.isNullOrBlank()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            border = BorderStroke(1.dp, if (importMessage!!.startsWith("Falha")) StatusError else StatusSuccess)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(importMessage!!, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                                IconButton(onClick = { importMessage = null }) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Backups disponíveis (${filteredEntries.size}):",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

                if (filteredEntries.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.Inventory2, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = when (activeFilter) {
                                        BackupOriginFilter.ALL -> "Nenhum backup encontrado no dispositivo ou Google Drive."
                                        BackupOriginFilter.LOCAL -> "Nenhum backup encontrado no armazenamento interno."
                                        BackupOriginFilter.DRIVE -> "Nenhum backup encontrado no Google Drive."
                                    },
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Puxe para atualizar ou crie um novo backup.", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                    }
                } else {
                    items(filteredEntries, key = { it.record.id.ifBlank { it.record.filePath } }) { entry ->
                        BackupItemCard(
                            record = entry.record,
                            primaryAccent = primaryAccent,
                            hasLocal = entry.hasLocal,
                            hasDrive = entry.hasDrive,
                            onClick = {
                                val preferredSource = if (entry.hasLocal) RestoreSource.LOCAL else RestoreSource.DRIVE
                                selectedBackupForSelection = Pair(entry.record, preferredSource)
                            },
                            onDelete = { backupToDelete = entry },
                            onRestore = {
                                val preferredSource = if (entry.hasLocal) RestoreSource.LOCAL else RestoreSource.DRIVE
                                selectedBackupForSelection = Pair(entry.record, preferredSource)
                            }
                        )
                    }
                }

                // Backups legados (sem manifesto granular)
                val relevantLegacy = legacySystemItems.filter {
                    when (activeFilter) {
                        BackupOriginFilter.ALL -> true
                        BackupOriginFilter.LOCAL -> it.remoteDriveFileId == null
                        BackupOriginFilter.DRIVE -> it.remoteDriveFileId != null
                    }
                }

                if (relevantLegacy.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Antigos (${relevantLegacy.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = StatusWarning
                            )
                        }
                        Text(
                            text = "Backups antigos sem manifesto granular. Refaça o backup para habilitar restauração seletiva.",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    items(relevantLegacy, key = { it.id }) { legacy ->
                        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(legacy.backupTimestamp))
                        LegacyBackupCard(
                            title = legacy.title,
                            description = legacy.description.ifBlank { "Backup antigo sem manifesto" },
                            dateFormatted = dateStr,
                            sizeBytes = legacy.sizeBytes,
                            location = if (legacy.remoteDriveFileId != null) "Google Drive" else "Memória interna",
                            onDelete = { legacyToDelete = legacy }
                        )
                    }
                }
            }
        }
    }

    // Diálogo de confirmação de exclusão de backup unificado
    if (backupToDelete != null) {
        val entry = backupToDelete!!
        val b = entry.record
        var deleteMode by remember { mutableStateOf("both") } // "local", "cloud", "both"
        var isDeleting by remember { mutableStateOf(false) }
        var deleteError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) backupToDelete = null
            },
            containerColor = DarkSurface,
            title = {
                Text("Excluir Backup", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tem certeza que deseja excluir '${b.title}'?", color = TextSecondary, fontSize = 13.sp)

                    if (entry.hasLocal && entry.hasDrive) {
                        Text("Este backup está presente no dispositivo e no Google Drive. Escolha de onde excluir:", color = TextMuted, fontSize = 12.sp)

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !isDeleting) { deleteMode = "both" }) {
                            RadioButton(selected = deleteMode == "both", onClick = { deleteMode = "both" }, enabled = !isDeleting)
                            Text("Aparelho e Google Drive", fontSize = 12.sp, color = TextPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !isDeleting) { deleteMode = "local" }) {
                            RadioButton(selected = deleteMode == "local", onClick = { deleteMode = "local" }, enabled = !isDeleting)
                            Text("Apenas do aparelho", fontSize = 12.sp, color = TextPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !isDeleting) { deleteMode = "cloud" }) {
                            RadioButton(selected = deleteMode == "cloud", onClick = { deleteMode = "cloud" }, enabled = !isDeleting)
                            Text("Apenas do Google Drive", fontSize = 12.sp, color = TextPrimary)
                        }
                    } else if (entry.hasDrive) {
                        Text("O arquivo será removido permanentemente da sua conta do Google Drive.", color = TextMuted, fontSize = 11.sp)
                    } else {
                        Text("O arquivo será apagado permanentemente do armazenamento interno.", color = TextMuted, fontSize = 11.sp)
                    }

                    if (deleteError != null) {
                        Surface(
                            color = StatusError.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = deleteError!!,
                                color = StatusError,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        deleteError = null
                        val toDelete = entry
                        viewModel.deleteUnifiedBackup(toDelete, deleteMode) { success, errMsg ->
                            isDeleting = false
                            if (success) {
                                backupToDelete = null
                                Toast.makeText(context, "Backup excluído com sucesso!", Toast.LENGTH_SHORT).show()
                            } else {
                                deleteError = errMsg ?: "Falha ao excluir o backup."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    enabled = !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Excluindo...", fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Text("Excluir", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { backupToDelete = null },
                    enabled = !isDeleting
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Diálogo de confirmação de exclusão de backup legado
    if (legacyToDelete != null) {
        val l = legacyToDelete!!
        AlertDialog(
            onDismissRequest = { legacyToDelete = null },
            containerColor = DarkSurface,
            title = {
                Text("Apagar Backup Antigo", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Text("Deseja apagar o backup antigo '${l.title}'?", color = TextSecondary, fontSize = 13.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = l
                        legacyToDelete = null
                        viewModel.deleteLegacyBackup(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("Apagar", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { legacyToDelete = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }
}
