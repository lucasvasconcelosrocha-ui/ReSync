package com.resync.app.ui.components

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.screens.ExplorerItem
import com.resync.app.ui.screens.isReadOnlyPartition
import com.resync.app.ui.screens.isSystemCriticalPath
import com.resync.app.ui.screens.parseLsOutputLine
import com.resync.app.ui.screens.toExplorerItem
import com.resync.app.ui.theme.*
import com.resync.app.util.FormatUtils
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory directory listing cache to make directory navigation instant.
 */
object StorageDirectoryCache {
    private val cache = ConcurrentHashMap<String, List<ExplorerItem>>()

    fun get(path: String, showHidden: Boolean): List<ExplorerItem>? {
        return cache["$path::$showHidden"]
    }

    fun put(path: String, showHidden: Boolean, items: List<ExplorerItem>) {
        cache["$path::$showHidden"] = items
    }

    fun clear() {
        cache.clear()
    }
}

/**
 * Checks whether the given path belongs to an Android system partition or protected area.
 */
fun isSystemPartition(path: String): Boolean {
    val p = if (path.startsWith("/")) path else "/$path"
    if (p == "/" || !p.startsWith("/storage/emulated")) return true
    if (p.startsWith("/storage/emulated/0/Android/data") || p.startsWith("/storage/emulated/0/Android/obb")) return true
    return false
}

/**
 * Reusable, high-performance root-enabled storage explorer component.
 * Used identically in FileExplorerScreen and Quick Backup (Arquivos e Mídia)
 * without duplicating any location bar or file listing code.
 */
@Composable
fun StorageExplorerListComponent(
    currentPath: String,
    onPathChange: (String) -> Unit,
    showHiddenFiles: Boolean,
    onToggleShowHiddenFiles: () -> Unit,
    searchQuery: String = "",
    selectedPaths: Set<String>,
    onTogglePath: (String) -> Unit,
    onSelectAllInCurrentView: (List<String>) -> Unit,
    onDeselectAllInCurrentView: (List<String>) -> Unit,
    isRootActive: Boolean,
    primaryAccent: Color,
    modifier: Modifier = Modifier,
    listHeight: Dp = 320.dp,
    showControlsRow: Boolean = true,
    showBreadcrumbRow: Boolean = true,
    enableSystemWarningDialog: Boolean = true,
    onCurrentItemsChanged: ((List<ExplorerItem>) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val internalRoot = remember { Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0") }

    var showLocationsMenu by remember { mutableStateOf(false) }
    var systemFolderToConfirm by remember { mutableStateOf<String?>(null) }

    // Map of path to calculated size (bytes)
    val folderSizes = remember { mutableStateMapOf<String, Long>() }
    // Map of paths currently being calculated in background
    val calculatingPaths = remember { mutableStateMapOf<String, Boolean>() }

    val cachedInitial = remember(currentPath, showHiddenFiles) {
        StorageDirectoryCache.get(currentPath, showHiddenFiles)
    }
    var isLoadingDirectory by remember(currentPath, showHiddenFiles) {
        mutableStateOf(cachedInitial == null)
    }

    // Query and process items in directory with root support (libsu, su -c, ls -la / ls -1pa)
    val directoryItems by produceState<List<ExplorerItem>>(
        initialValue = cachedInitial ?: emptyList(),
        key1 = currentPath,
        key2 = showHiddenFiles,
        key3 = isRootActive
    ) {
        val cached = StorageDirectoryCache.get(currentPath, showHiddenFiles)
        if (cached != null) {
            value = cached
            isLoadingDirectory = false
        } else {
            isLoadingDirectory = true
        }

        value = withContext(Dispatchers.IO) {
            val path = currentPath
            val isSystemPath = isSystemPartition(path)
            val resultList = mutableListOf<ExplorerItem>()

            // 1. FAST PATH: Leitura direta instantânea via Java File API para caminhos normais
            if (!isSystemPath) {
                val dir = File(path)
                if (dir.exists() && dir.canRead()) {
                    val files = dir.listFiles()
                    if (files != null) {
                        for (file in files) {
                            val isHidden = file.name.startsWith(".")
                            if (!showHiddenFiles && isHidden) continue
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
                                    isHidden = isHidden,
                                    itemCount = count
                                )
                            )
                        }
                    }
                }
            }

            // 2. ROOT / ELEVATED FALLBACK: Usar root em partições de sistema ou caso a leitura direta venha vazia
            if (resultList.isEmpty() && (isRootActive || isSystemPath)) {
                // 1. Tentar execução prioritária com ls -la para obter metadados e tamanhos reais
                try {
                    val shellResult = withTimeoutOrNull(2500L) {
                        Shell.cmd("ls -la \"$path\"").exec()
                    }
                    if (shellResult != null && shellResult.isSuccess && shellResult.out.isNotEmpty()) {
                        for (line in shellResult.out) {
                            val parsed = parseLsOutputLine(line, path)
                            if (parsed != null) {
                                if (!showHiddenFiles && parsed.isHidden) continue
                                resultList.add(parsed)
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // 2. Se falhar ou vier vazio em partição do sistema, tentar su direto via Runtime com ls -la
                if (resultList.isEmpty() && isSystemPath) {
                    try {
                        val lines = withTimeoutOrNull(2500L) {
                            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -la \"$path\""))
                            val res = proc.inputStream.bufferedReader().readLines()
                            proc.waitFor()
                            res
                        }
                        if (lines != null && lines.isNotEmpty()) {
                            for (line in lines) {
                                val parsed = parseLsOutputLine(line, path)
                                if (parsed != null) {
                                    if (!showHiddenFiles && parsed.isHidden) continue
                                    resultList.add(parsed)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                // 3. Fallback adicional via ls -lan (com UID numérico, contornando parsing de usuários desconhecidos)
                if (resultList.isEmpty() && isSystemPath) {
                    try {
                        val shellResult = withTimeoutOrNull(2500L) {
                            Shell.cmd("ls -lan \"$path\"").exec()
                        }
                        if (shellResult != null && shellResult.isSuccess && shellResult.out.isNotEmpty()) {
                            for (line in shellResult.out) {
                                val parsed = parseLsOutputLine(line, path)
                                if (parsed != null) {
                                    if (!showHiddenFiles && parsed.isHidden) continue
                                    resultList.add(parsed)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                // 4. Último recurso se ls -la falhou: ls -1pa
                if (resultList.isEmpty() && isSystemPath) {
                    try {
                        val shellResult = withTimeoutOrNull(2000L) {
                            Shell.cmd("ls -1pa \"$path\"").exec()
                        }
                        if (shellResult != null && shellResult.isSuccess && shellResult.out.isNotEmpty()) {
                            for (raw in shellResult.out) {
                                val trimmed = raw.trim()
                                if (trimmed.isEmpty() || trimmed == "." || trimmed == ".." || trimmed == "./" || trimmed == "../") continue
                                val isDir = trimmed.endsWith("/")
                                val name = if (isDir) trimmed.dropLast(1) else trimmed
                                val isHidden = name.startsWith(".")
                                if (!showHiddenFiles && isHidden) continue
                                val cleanParent = if (path == "/") "/" else "${path.trimEnd('/')}/"
                                val itemPath = if (cleanParent == "/") "/$name" else "$cleanParent$name"
                                resultList.add(
                                    ExplorerItem(
                                        name = name,
                                        path = itemPath,
                                        isDirectory = isDir,
                                        sizeBytes = 0L,
                                        permissions = if (isDir) "drwxr-xr-x" else "-rw-r--r--",
                                        owner = "root",
                                        group = "root",
                                        lastModified = 0L,
                                        isHidden = isHidden,
                                        itemCount = -1
                                    )
                                )
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }

            // Fallback padrão se não for área de sistema ou se resultList ainda estiver vazia
            if (resultList.isEmpty()) {
                val dir = File(path)
                val files = dir.listFiles()
                if (files != null) {
                    for (file in files) {
                        val isHidden = file.name.startsWith(".")
                        if (!showHiddenFiles && isHidden) continue
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
                                isHidden = isHidden,
                                itemCount = count
                            )
                        )
                    }
                }
            }

            val sorted = resultList.sortedWith(
                compareBy<ExplorerItem> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
            StorageDirectoryCache.put(path, showHiddenFiles, sorted)
            sorted
        }
        isLoadingDirectory = false
    }

    // Launch background folder size calculations with sequential execution and timeout to avoid shell saturation
    LaunchedEffect(directoryItems, isRootActive) {
        withContext(Dispatchers.IO) {
            // First load all cached sizes instantly
            for (item in directoryItems) {
                if (item.isDirectory) {
                    val cached = StorageRepository.getCachedFolderSize(item.path)
                    if (cached != null) {
                        folderSizes[item.path] = cached
                    }
                }
            }

            // Sequentially measure uncached folders with a strict 1-second timeout per folder
            for (item in directoryItems.take(20)) {
                if (item.isDirectory && !folderSizes.containsKey(item.path) && !calculatingPaths.containsKey(item.path)) {
                    calculatingPaths[item.path] = true
                    try {
                        withTimeoutOrNull(1000L) {
                            val size = StorageRepository.calculateFolderSize(File(item.path), isRootActive)
                            if (size > 0L) {
                                folderSizes[item.path] = size
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        calculatingPaths.remove(item.path)
                    }
                } else if (!item.isDirectory && item.sizeBytes == 0L && !folderSizes.containsKey(item.path) && isRootActive && isSystemPartition(item.path)) {
                    try {
                        val realSize = StorageRepository.getFileSize(File(item.path), isRootActive)
                        if (realSize > 0L) {
                            folderSizes[item.path] = realSize
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // Filter items according to search query
    val filteredItems = remember(directoryItems, searchQuery) {
        if (searchQuery.isBlank()) {
            directoryItems
        } else {
            directoryItems.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

    LaunchedEffect(filteredItems) {
        onCurrentItemsChanged?.invoke(filteredItems)
    }

    // Confirmation dialog when selecting a system partition/folder
    if (systemFolderToConfirm != null) {
        val targetPath = systemFolderToConfirm!!
        AlertDialog(
            onDismissRequest = { systemFolderToConfirm = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pasta do Sistema", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "A pasta \"$targetPath\" é uma partição ou área protegida do sistema.",
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "O backup pode ser grande e demorado, e exigirá autorização Root para preservar permissões e metadados SELinux.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onTogglePath(targetPath)
                        systemFolderToConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("INCLUIR NO BACKUP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { systemFolderToConfirm = null }) {
                    Text("CANCELAR", color = TextSecondary, fontSize = 12.sp)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showControlsRow) {
            // Row of Controls: Storage dropdown, Hidden eye icon, Raiz button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Location Selector Dropdown
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, DarkCardBorder),
                        modifier = Modifier.clickable { showLocationsMenu = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentPath.startsWith(internalRoot.absolutePath)) Icons.Default.PhoneAndroid else Icons.Default.Terminal,
                                contentDescription = null,
                                tint = if (currentPath.startsWith(internalRoot.absolutePath)) AccentSunsetOrange else StatusSuccess,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (currentPath.startsWith(internalRoot.absolutePath)) "Armazenamento Interno" else "Raiz (/)",
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
                    }

                    // Dropdown menu of locations and root shortcuts
                    DropdownMenu(
                        expanded = showLocationsMenu,
                        onDismissRequest = { showLocationsMenu = false },
                        modifier = Modifier
                            .background(DarkSurfaceVariant)
                            .widthIn(min = 270.dp)
                    ) {
                        Text(
                            text = "LOCAIS PRINCIPAIS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryAccent,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Armazenamento Interno (/storage/emulated/0)", color = TextPrimary, fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = AccentSunsetOrange) },
                            onClick = {
                                onPathChange(internalRoot.absolutePath)
                                showLocationsMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Raiz do Sistema (/)", color = TextPrimary, fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = StatusSuccess) },
                            onClick = {
                                onPathChange("/")
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
                                    onPathChange(path)
                                    showLocationsMenu = false
                                }
                            )
                        }
                    }
                }

                // Eye Icon & Raiz Button
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleShowHiddenFiles,
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
                        color = if (currentPath == "/" || !currentPath.startsWith(internalRoot.absolutePath)) StatusSuccess.copy(alpha = 0.2f) else DarkSurfaceVariant,
                        border = BorderStroke(1.dp, if (currentPath == "/" || !currentPath.startsWith(internalRoot.absolutePath)) StatusSuccess else DarkCardBorder),
                        modifier = Modifier.clickable {
                            if (currentPath == "/") {
                                onPathChange(internalRoot.absolutePath)
                            } else {
                                onPathChange("/")
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Raiz",
                                tint = if (currentPath == "/" || !currentPath.startsWith(internalRoot.absolutePath)) StatusSuccess else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Raiz",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (currentPath == "/" || !currentPath.startsWith(internalRoot.absolutePath)) StatusSuccess else TextPrimary
                            )
                        }
                    }
                }
            }
        }

        if (showBreadcrumbRow) {
            // Breadcrumbs Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button if not at root
                if (currentPath != "/" && currentPath != internalRoot.absolutePath) {
                    IconButton(
                        onClick = {
                            val parent = File(currentPath).parentFile?.absolutePath ?: "/"
                            onPathChange(parent)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Subir nível",
                            tint = primaryAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Base root badge
                val isInternal = currentPath.startsWith(internalRoot.absolutePath)
                Text(
                    text = if (isInternal) "Armazenamento Interno" else "/",
                    fontSize = 11.5.sp,
                    color = if (currentPath == internalRoot.absolutePath || currentPath == "/") TextPrimary else primaryAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onPathChange(if (isInternal) internalRoot.absolutePath else "/")
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )

                val breadcrumbSegments = remember(currentPath) {
                    val relative = if (currentPath.startsWith(internalRoot.absolutePath)) {
                        currentPath.removePrefix(internalRoot.absolutePath).trimStart('/')
                    } else {
                        currentPath.trimStart('/')
                    }
                    if (relative.isEmpty()) emptyList() else relative.split('/')
                }

                breadcrumbSegments.forEachIndexed { index, segment ->
                    Text(
                        text = " > ",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    val segmentPath = if (currentPath.startsWith(internalRoot.absolutePath)) {
                        internalRoot.absolutePath + "/" + breadcrumbSegments.take(index + 1).joinToString("/")
                    } else {
                        "/" + breadcrumbSegments.take(index + 1).joinToString("/")
                    }
                    Text(
                        text = segment,
                        fontSize = 11.5.sp,
                        fontWeight = if (index == breadcrumbSegments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == breadcrumbSegments.lastIndex) TextPrimary else primaryAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onPathChange(segmentPath) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Quick select / deselect buttons for current folder view
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelectAllInCurrentView(filteredItems.map { it.path })
                },
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = primaryAccent.copy(alpha = 0.08f)),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    text = "Marcar todos (${filteredItems.size})",
                    fontSize = 11.5.sp,
                    color = primaryAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeselectAllInCurrentView(filteredItems.map { it.path })
                },
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, DarkCardBorder),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurfaceVariant),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    text = "Desmarcar todos",
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Fixed height LazyColumn with independent scrolling
        if (isLoadingDirectory) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = primaryAccent
                    )
                    Text(
                        text = "Carregando diretório...",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isNotEmpty()) "Nenhum arquivo ou pasta encontrado" else "Pasta vazia ou protegida",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredItems, key = { it.path }) { item ->
                    val isSelected = selectedPaths.contains(item.path)
                    val isCalculating = calculatingPaths.containsKey(item.path)
                    val folderBytes = folderSizes[item.path]
                    val isSystem = isSystemPartition(item.path)

                    val actualFileSize = if (!item.isDirectory) (folderSizes[item.path] ?: item.sizeBytes) else 0L
                    val sizeText = when {
                        !item.isDirectory -> FormatUtils.formatBytes(actualFileSize)
                        folderBytes != null -> FormatUtils.formatBytes(folderBytes)
                        item.itemCount >= 0 -> if (item.itemCount == 1) "1 item" else "${item.itemCount} itens"
                        isCalculating -> "Calculando..."
                        else -> "Pasta"
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                if (isSelected) primaryAccent.copy(alpha = 0.6f) else DarkCardBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                if (item.isDirectory) {
                                    onPathChange(item.path)
                                } else {
                                    onTogglePath(item.path)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) primaryAccent.copy(alpha = 0.08f) else DarkSurfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Checkbox + Icon + Name & Info
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (!isSelected && isSystem && enableSystemWarningDialog) {
                                            systemFolderToConfirm = item.path
                                        } else {
                                            onTogglePath(item.path)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = primaryAccent,
                                        uncheckedColor = TextSecondary,
                                        checkmarkColor = Color.Black
                                    ),
                                    modifier = Modifier.size(28.dp)
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                Icon(
                                    imageVector = when {
                                        item.isDirectory -> Icons.Default.Folder
                                        item.name.endsWith(".apk", ignoreCase = true) -> Icons.Default.Android
                                        item.name.endsWith(".zip", ignoreCase = true) || item.name.endsWith(".resync", ignoreCase = true) || item.name.endsWith(".svb", ignoreCase = true) -> Icons.Default.Archive
                                        else -> Icons.Default.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = if (item.isDirectory) AccentSunsetOrange else primaryAccent,
                                    modifier = Modifier.size(20.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        if (isSystem) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = StatusWarning.copy(alpha = 0.18f),
                                                border = BorderStroke(0.5.dp, StatusWarning.copy(alpha = 0.5f))
                                            ) {
                                                Text(
                                                    text = "Sistema",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = StatusWarning,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = if (item.isDirectory) "Pasta" else "Arquivo",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                        Text(text = "•", fontSize = 11.sp, color = TextMuted)
                                        Text(
                                            text = sizeText,
                                            fontSize = 11.sp,
                                            color = if (isCalculating && folderBytes == null && item.itemCount < 0) AccentSunsetOrange else TextSecondary,
                                            fontWeight = if (isCalculating && folderBytes == null && item.itemCount < 0) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            // Right: chevron arrow if folder to navigate
                            if (item.isDirectory) {
                                IconButton(
                                    onClick = { onPathChange(item.path) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Abrir pasta",
                                        tint = TextSecondary,
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
