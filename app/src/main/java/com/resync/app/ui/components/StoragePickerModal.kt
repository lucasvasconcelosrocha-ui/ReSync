package com.resync.app.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.resync.app.engine.CapabilityManager
import com.resync.app.engine.FileCategory
import com.resync.app.engine.StorageItem
import com.resync.app.engine.StorageRepository
import com.resync.app.engine.StorageSortOrder
import com.resync.app.engine.StorageVolumeInfo
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoragePickerModal(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val activeMode by viewModel.activeMode.collectAsState()
    val isRootActive = activeMode == CapabilityManager.ExecutionMode.ROOT

    val selectedPaths by viewModel.quickBackupSelectedPaths.collectAsState()
    val folderSizes by viewModel.quickBackupFolderSizes.collectAsState()
    val calculatingPaths by viewModel.quickBackupCalculatingPaths.collectAsState()

    // Storage Volumes Detection
    val volumes = remember { StorageRepository.getStorageVolumes(context) }
    var selectedVolume by remember {
        mutableStateOf(
            volumes.firstOrNull() ?: StorageVolumeInfo(
                id = "/storage/emulated/0",
                rootFile = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"),
                title = "Armazenamento Interno",
                isPrimary = true,
                isRemovable = false,
                totalBytes = 0L,
                freeBytes = 0L
            )
        )
    }

    var currentDir by remember(selectedVolume) { mutableStateOf(selectedVolume.rootFile) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSortOrder by remember { mutableStateOf(StorageSortOrder.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Android 11+ MANAGE_EXTERNAL_STORAGE permission
    val hasAllFilesAccess = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    // Android System Back Navigation inside Picker
    BackHandler(enabled = currentDir.absolutePath != selectedVolume.rootFile.absolutePath) {
        if (currentDir.parentFile != null && currentDir.absolutePath != selectedVolume.rootFile.absolutePath) {
            currentDir = currentDir.parentFile!!
        }
    }

    // Incremental directory items loading off the main thread
    val directoryItemsState = produceState<Pair<Boolean, List<StorageItem>>>(
        initialValue = Pair(true, emptyList()),
        key1 = currentDir.absolutePath,
        key2 = isRootActive
    ) {
        value = Pair(true, emptyList())
        val items = withContext(Dispatchers.IO) {
            StorageRepository.listDirectoryItems(currentDir, isRootActive)
        }
        value = Pair(false, items)
    }

    val isLoading = directoryItemsState.value.first
    val rawItems = directoryItemsState.value.second

    // Filter and Sort Items
    val filteredItems = remember(rawItems, searchQuery, selectedSortOrder) {
        val filtered = if (searchQuery.isBlank()) {
            rawItems
        } else {
            rawItems.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
        }
        StorageRepository.sortItems(filtered, selectedSortOrder)
    }

    val isCurrentDirRestricted = remember(currentDir) {
        StorageRepository.isRestrictedSystemDir(currentDir)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkBg
        ) {
            Scaffold(
                containerColor = DarkBg,
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = {
                                    if (currentDir.absolutePath != selectedVolume.rootFile.absolutePath && currentDir.parentFile != null) {
                                        currentDir = currentDir.parentFile!!
                                    } else {
                                        onDismiss()
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (currentDir.absolutePath != selectedVolume.rootFile.absolutePath)
                                            Icons.AutoMirrored.Filled.ArrowBack
                                        else
                                            Icons.Default.Close,
                                        contentDescription = "Fechar / Voltar",
                                        tint = TextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = "Selecionar no Armazenamento",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "Escolha pastas e arquivos para o backup rápido",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Sort Action Button
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = "Ordenar",
                                        tint = primaryAccent
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                    modifier = Modifier.background(DarkSurfaceVariant)
                                ) {
                                    StorageSortOrder.values().forEach { order ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = order.label,
                                                    color = if (selectedSortOrder == order) primaryAccent else TextPrimary,
                                                    fontWeight = if (selectedSortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 13.sp
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
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Storage Volume Switcher (if device has multiple volumes)
                        if (volumes.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                volumes.forEach { vol ->
                                    val isCurrentVol = selectedVolume.id == vol.id
                                    FilterChip(
                                        selected = isCurrentVol,
                                        onClick = {
                                            if (selectedVolume.id != vol.id) {
                                                selectedVolume = vol
                                                currentDir = vol.rootFile
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = vol.title,
                                                fontSize = 11.sp,
                                                fontWeight = if (isCurrentVol) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (vol.isPrimary) Icons.Default.Storage else Icons.Default.Storage,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = primaryAccent.copy(alpha = 0.2f),
                                            selectedLabelColor = primaryAccent,
                                            containerColor = DarkSurfaceVariant,
                                            labelColor = TextSecondary
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isCurrentVol,
                                            borderColor = DarkCardBorder,
                                            selectedBorderColor = primaryAccent
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Search Field inside current directory
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            placeholder = { Text("Buscar nesta pasta...", fontSize = 12.sp, color = TextMuted) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurfaceVariant,
                                unfocusedContainerColor = DarkSurfaceVariant,
                                focusedBorderColor = primaryAccent,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Breadcrumb Navigation
                        val relativeSegments = remember(currentDir, selectedVolume) {
                            val rel = currentDir.absolutePath.removePrefix(selectedVolume.rootFile.absolutePath).trim('/')
                            if (rel.isEmpty()) emptyList() else rel.split('/')
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedVolume.title,
                                color = if (currentDir.absolutePath == selectedVolume.rootFile.absolutePath) primaryAccent else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { currentDir = selectedVolume.rootFile }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )

                            var accumulatedPath = selectedVolume.rootFile.absolutePath
                            relativeSegments.forEachIndexed { index, segment ->
                                Text(" > ", color = TextMuted, fontSize = 12.sp)
                                accumulatedPath += "/$segment"
                                val targetDir = File(accumulatedPath)
                                val isLast = index == relativeSegments.size - 1
                                Text(
                                    text = segment,
                                    color = if (isLast) primaryAccent else TextMuted,
                                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { currentDir = targetDir }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                bottomBar = {
                    Surface(
                        color = DarkSurface,
                        border = BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            val deduplicatedPaths = remember(selectedPaths) {
                                StorageRepository.normalizeAndDeduplicatePaths(selectedPaths)
                            }
                            val isCalculating = viewModel.isQuickBackupCalculatingSizes()
                            val totalBytes = viewModel.getQuickBackupTotalEstimatedBytes()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${deduplicatedPaths.size} itens selecionados",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 13.sp
                                    )
                                    val sizeLabel = if (isCalculating) "Calculando tamanho..." else "Tamanho: ${StorageRepository.formatFileSize(totalBytes)}"
                                    Text(
                                        text = sizeLabel,
                                        color = if (isCalculating) StatusWarning else primaryAccent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (selectedPaths.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = { viewModel.clearQuickBackupSelectedPaths() },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, DarkCardBorder),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("Limpar", fontSize = 12.sp, color = StatusError, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Button(
                                        onClick = onDismiss,
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("CONFIRMAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Loading indicator for directory reading
                    if (isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = primaryAccent,
                            trackColor = DarkSurfaceVariant
                        )
                    }

                    // Permission warning on Android 11+ if missing
                    if (!hasAllFilesAccess) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, StatusWarning, RoundedCornerShape(10.dp)),
                            colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Acesso Total aos Arquivos Necessário", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Para visualizar e selecionar pastas como DCIM, Documentos e cartões SD no Android 11+, conceda a permissão especial de armazenamento.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
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
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("CONCEDER PERMISSÃO", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // Protected system directory notice (Android/data or Android/obb)
                    if (isCurrentDirRestricted) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (isRootActive) StatusSuccess else StatusWarning, RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRootActive) StatusSuccess.copy(alpha = 0.12f) else StatusWarning.copy(alpha = 0.12f)
                            )
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isRootActive) Icons.Default.Security else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isRootActive) StatusSuccess else StatusWarning,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isRootActive)
                                        "Pasta do sistema protegida — Acesso Root ativo (listagem via libsu habilitada)."
                                    else
                                        "Pasta protegida pelo Android (Android/data / obb). O acesso sem Root pode ser restrito pelo sistema.",
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    // Quick Up Directory Item if inside subdirectory
                    if (currentDir.absolutePath != selectedVolume.rootFile.absolutePath && currentDir.parentFile != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { currentDir = currentDir.parentFile!! },
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(".. (Pasta anterior)", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // Directory Items List
                    if (!isLoading && filteredItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(44.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Nenhum arquivo ou pasta nesta pasta.", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredItems, key = { it.file.absolutePath }) { item ->
                                val path = item.file.absolutePath
                                val isDirectlySelected = selectedPaths.contains(path)
                                val isParentSelected = remember(selectedPaths, path) {
                                    selectedPaths.any { parent ->
                                        parent != path && path.startsWith(parent + File.separator)
                                    }
                                }
                                val isCalculatingThis = calculatingPaths.contains(path)
                                val calculatedSize = folderSizes[path]

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            1.dp,
                                            if (isDirectlySelected) primaryAccent else if (isParentSelected) primaryAccent.copy(alpha = 0.4f) else DarkCardBorder,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            if (item.isDirectory) {
                                                currentDir = item.file
                                            } else {
                                                viewModel.toggleQuickBackupPath(path)
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDirectlySelected)
                                            primaryAccent.copy(alpha = 0.12f)
                                        else if (isParentSelected)
                                            DarkSurfaceVariant.copy(alpha = 0.7f)
                                        else
                                            DarkSurfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Selection Checkbox for both files and folders!
                                        Checkbox(
                                            checked = isDirectlySelected || isParentSelected,
                                            enabled = !isParentSelected,
                                            onCheckedChange = {
                                                viewModel.toggleQuickBackupPath(path)
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = primaryAccent,
                                                disabledCheckedColor = primaryAccent.copy(alpha = 0.5f)
                                            ),
                                            modifier = Modifier.size(28.dp)
                                        )

                                        Spacer(modifier = Modifier.width(6.dp))

                                        // Item Icon
                                        val iconBoxTint = if (item.isDirectory) AccentSunsetOrange else primaryAccent
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(iconBoxTint.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                                contentDescription = null,
                                                tint = iconBoxTint,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        // File / Folder Details
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))

                                            val detailText = when {
                                                isParentSelected -> "✓ Incluído pela pasta pai"
                                                item.isDirectory && isCalculatingThis -> "Calculando tamanho..."
                                                item.isDirectory && calculatedSize != null -> "${StorageRepository.formatFileSize(calculatedSize)} • Toque para abrir"
                                                item.isDirectory -> "Pasta • Toque para abrir"
                                                else -> StorageRepository.formatFileSize(item.sizeBytes)
                                            }

                                            Text(
                                                text = detailText,
                                                fontSize = 11.sp,
                                                color = if (isParentSelected) StatusSuccess else if (isCalculatingThis) StatusWarning else TextMuted,
                                                fontWeight = if (isCalculatingThis || isParentSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }

                                        // Folder Navigation Indicator
                                        if (item.isDirectory) {
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Abrir pasta",
                                                tint = TextMuted,
                                                modifier = Modifier.size(18.dp)
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

