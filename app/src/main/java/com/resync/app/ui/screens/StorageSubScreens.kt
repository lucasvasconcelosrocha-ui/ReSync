package com.resync.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.data.model.AppInfo
import com.resync.app.engine.AppStorageInspector
import com.resync.app.ui.components.RealAppIcon
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.FormatUtils
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.os.Process
import android.os.storage.StorageManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

// =========================================================================
// 1. TELA: APLICATIVOS ORDENADOS POR TAMANHO
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAppsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToAppDetail: (AppInfo) -> Unit
) {
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()

    val sortedApps = remember(installedApps) {
        installedApps.sortedByDescending { it.sizeBytes }
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
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Aplicativos",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "${sortedApps.size} apps ordenados por tamanho",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(sortedApps, key = { it.packageName }) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp))
                        .clickable { onNavigateToAppDetail(app) },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RealAppIcon(
                            packageName = app.packageName,
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.appName,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = app.packageName,
                                fontSize = 10.5.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = FormatUtils.formatBytes(app.sizeBytes),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryAccent
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// 2. TELA: LIMPEZA DE CACHE POR APLICATIVO E TOTAL
// =========================================================================
data class AppCacheItem(
    val app: AppInfo,
    var cacheBytes: Long,
    var isClearing: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageCacheScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()

    var cacheList by remember { mutableStateOf<List<AppCacheItem>>(emptyList()) }
    var failedApps by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var isCalculating by remember { mutableStateOf(false) }
    var analyzedCount by remember { mutableIntStateOf(0) }
    var totalAppsCount by remember { mutableIntStateOf(0) }
    var showConfirmClearAll by remember { mutableStateOf(false) }
    var isClearingAll by remember { mutableStateOf(false) }
    var hasUsagePermission by remember { mutableStateOf(true) }
    var lastFreedMessage by remember { mutableStateOf<String?>(null) }

    fun checkUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun queryAppCacheBytes(
        packageName: String,
        storageStatsManager: StorageStatsManager?,
        usageGranted: Boolean,
        hasRoot: Boolean
    ): Long = withContext(Dispatchers.IO) {
        var cBytes = 0L
        if (storageStatsManager != null && usageGranted) {
            try {
                val stats = storageStatsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    packageName,
                    Process.myUserHandle()
                )
                cBytes = stats.cacheBytes
            } catch (_: Exception) {}
        }
        if (cBytes <= 0L && hasRoot) {
            try {
                val cmd = "du -sk /data/data/$packageName/cache /data/data/$packageName/code_cache /data/user_de/0/$packageName/cache /storage/emulated/0/Android/data/$packageName/cache 2>/dev/null"
                val res = Shell.cmd(cmd).exec()
                for (line in res.out) {
                    val kb = line.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
                    cBytes += kb * 1024L
                }
            } catch (_: Exception) {}
        }
        cBytes.coerceAtLeast(0L)
    }

    suspend fun loadCacheData(silent: Boolean = false) = withContext(Dispatchers.IO) {
        if (isCalculating || isClearingAll) return@withContext
        isCalculating = true
        val hasRoot = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
        val usageGranted = checkUsageStatsPermission()
        withContext(Dispatchers.Main) {
            hasUsagePermission = usageGranted || hasRoot
            if (!silent && cacheList.isEmpty()) isLoading = true
        }

        val storageStatsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
        } else null

        val apps = installedApps
        withContext(Dispatchers.Main) {
            totalAppsCount = apps.size
            analyzedCount = 0
        }

        val tempItems = ConcurrentLinkedQueue<AppCacheItem>()
        val semaphore = Semaphore(8)

        coroutineScope {
            apps.map { app ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        var cBytes = 0L
                        withTimeoutOrNull(2500L) {
                            cBytes = queryAppCacheBytes(app.packageName, storageStatsManager, usageGranted, hasRoot)
                        }

                        if (cBytes > 0L) {
                            tempItems.add(AppCacheItem(app, cBytes))
                        }
                        withContext(Dispatchers.Main) {
                            analyzedCount++
                            if (!silent) {
                                cacheList = tempItems.toList().sortedByDescending { it.cacheBytes }
                                isLoading = false
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        withContext(Dispatchers.Main) {
            cacheList = tempItems.toList().sortedByDescending { it.cacheBytes }
            isCalculating = false
            isLoading = false
        }
    }

    LaunchedEffect(installedApps) {
        if (installedApps.isNotEmpty()) {
            loadCacheData()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasRoot = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
                val granted = checkUsageStatsPermission()
                val newHasUsage = granted || hasRoot
                hasUsagePermission = newHasUsage
                if (!isCalculating && !isClearingAll) {
                    coroutineScope.launch { loadCacheData(silent = cacheList.isNotEmpty()) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(5000L)
                if (!isCalculating && !isClearingAll) {
                    loadCacheData(silent = true)
                }
            }
        }
    }

    fun clearAppCache(item: AppCacheItem) {
        coroutineScope.launch {
            val pkg = item.app.packageName
            val initialBytes = item.cacheBytes

            // 1. Atualização imediata no State (remove da lista na hora)
            cacheList = cacheList.filter { it.app.packageName != pkg }

            val hasRoot = withContext(Dispatchers.IO) {
                runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
            }

            if (hasRoot) {
                withContext(Dispatchers.IO) {
                    Shell.cmd(
                        "rm -rf /data/data/$pkg/cache/*",
                        "rm -rf /data/data/$pkg/code_cache/*",
                        "rm -rf /data/user_de/0/$pkg/cache/*",
                        "rm -rf /storage/emulated/0/Android/data/$pkg/cache/*"
                    ).exec()
                }
            } else {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", pkg, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, "Abra Armazenamento e toque em Limpar Cache", Toast.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    Toast.makeText(context, "Não foi possível abrir as configurações", Toast.LENGTH_SHORT).show()
                }
            }

            val storageStatsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            } else null
            val usageGranted = checkUsageStatsPermission()

            // 2. Reconsulta real 1 (logo após limpar)
            var currentBytes = queryAppCacheBytes(pkg, storageStatsManager, usageGranted, hasRoot)

            // 2. Reconsulta real 2 (após ~1.5s)
            delay(1500L)
            currentBytes = queryAppCacheBytes(pkg, storageStatsManager, usageGranted, hasRoot)

            withContext(Dispatchers.Main) {
                val currentList = cacheList.filter { it.app.packageName != pkg }.toMutableList()
                if (currentBytes > 0L) {
                    currentList.add(AppCacheItem(item.app, currentBytes))
                    cacheList = currentList.sortedByDescending { it.cacheBytes }
                    val freed = initialBytes - currentBytes
                    if (freed > 0L) {
                        Toast.makeText(context, "${FormatUtils.formatBytes(freed)} liberados de ${item.app.appName}!", Toast.LENGTH_SHORT).show()
                    } else {
                        failedApps = failedApps + (pkg to true)
                        Toast.makeText(context, "Não foi possível limpar o cache de ${item.app.appName}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    cacheList = currentList.sortedByDescending { it.cacheBytes }
                    failedApps = failedApps - pkg
                    Toast.makeText(context, "Cache de ${item.app.appName} limpo com sucesso!", Toast.LENGTH_SHORT).show()
                }
                viewModel.loadData()
            }
        }
    }

    fun clearAllCache() {
        coroutineScope.launch {
            isClearingAll = true
            val itemsToClean = cacheList.toList()
            val totalBefore = itemsToClean.sumOf { it.cacheBytes }

            // 1. Atualização imediata no State
            cacheList = emptyList()

            val hasRoot = withContext(Dispatchers.IO) {
                runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
            }

            if (hasRoot) {
                withContext(Dispatchers.IO) {
                    Shell.cmd("pm trim-caches 999999999999").exec()
                    for (item in itemsToClean) {
                        val pkg = item.app.packageName
                        Shell.cmd(
                            "rm -rf /data/data/$pkg/cache/*",
                            "rm -rf /data/data/$pkg/code_cache/*",
                            "rm -rf /data/user_de/0/$pkg/cache/*",
                            "rm -rf /storage/emulated/0/Android/data/$pkg/cache/*"
                        ).exec()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Limpeza em lote de outros apps requer acesso Root", Toast.LENGTH_LONG).show()
                }
            }

            val storageStatsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            } else null
            val usageGranted = checkUsageStatsPermission()

            // 2. Reconsulta real 1 (logo após limpar)
            withContext(Dispatchers.IO) {
                for (item in itemsToClean) {
                    queryAppCacheBytes(item.app.packageName, storageStatsManager, usageGranted, hasRoot)
                }
            }

            // 2. Reconsulta real 2 (após ~1.5s)
            delay(1500L)
            val remainingItems = mutableListOf<AppCacheItem>()
            val newFailedMap = mutableMapOf<String, Boolean>()
            withContext(Dispatchers.IO) {
                for (item in itemsToClean) {
                    val b = queryAppCacheBytes(item.app.packageName, storageStatsManager, usageGranted, hasRoot)
                    if (b > 0L) {
                        remainingItems.add(AppCacheItem(item.app, b))
                        if (b >= item.cacheBytes) {
                            newFailedMap[item.app.packageName] = true
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                cacheList = remainingItems.sortedByDescending { it.cacheBytes }
                failedApps = newFailedMap
                isClearingAll = false

                // 3. Diferença entre o total antes e o total depois da releitura
                val totalAfter = cacheList.sumOf { it.cacheBytes }
                val actuallyFreed = (totalBefore - totalAfter).coerceAtLeast(0L)

                if (actuallyFreed > 0L) {
                    val freedStr = FormatUtils.formatBytes(actuallyFreed)
                    lastFreedMessage = "$freedStr liberados com sucesso!"
                    Toast.makeText(context, "$freedStr liberados com sucesso!", Toast.LENGTH_LONG).show()
                } else {
                    if (!hasRoot) {
                        lastFreedMessage = "Não foi possível limpar o cache automaticamente (requer Root)."
                        Toast.makeText(context, "Não foi possível limpar o cache (requer Root)", Toast.LENGTH_LONG).show()
                    } else {
                        lastFreedMessage = "Nenhum cache adicional pôde ser liberado."
                        Toast.makeText(context, "Nenhum cache pôde ser liberado", Toast.LENGTH_SHORT).show()
                    }
                }
                viewModel.loadData()
            }
        }
    }

    val totalCacheBytes = remember(cacheList) { cacheList.sumOf { it.cacheBytes } }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        // Linha 1: seta de voltar e título na mesma linha
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Voltar",
                                    tint = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Limpeza de Cache",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // Linha 2: "Total ocupado: 2,55 GB • 232 de 454 analisados"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 40.dp)
                        ) {
                            Text(
                                text = "Total ocupado: ${FormatUtils.formatBytes(totalCacheBytes)} • $analyzedCount de ${totalAppsCount.coerceAtLeast(installedApps.size)} analisados",
                                fontSize = 11.sp,
                                color = primaryAccent,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2
                            )
                            if (isCalculating) {
                                Spacer(modifier = Modifier.width(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = primaryAccent
                                )
                            }
                        }
                    }

                    // Botões à direita centralizados verticalmente
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!isCalculating && analyzedCount < installedApps.size && installedApps.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    coroutineScope.launch { loadCacheData() }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Tentar de novo", fontSize = 11.sp, color = primaryAccent, maxLines = 1)
                            }
                        }

                        if (totalCacheBytes > 0L) {
                            Button(
                                onClick = { showConfirmClearAll = true },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.height(40.dp),
                                enabled = !isClearingAll
                            ) {
                                if (isClearingAll) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Black)
                                } else {
                                    Text("Limpar tudo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                if (isCalculating && totalAppsCount > 0) {
                    val progress = (analyzedCount.toFloat() / totalAppsCount.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = primaryAccent,
                        trackColor = DarkBg
                    )
                } else {
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)
                }
            }
        }
    ) { innerPadding ->
        if (showConfirmClearAll) {
            AlertDialog(
                onDismissRequest = { showConfirmClearAll = false },
                title = { Text("Limpar todo o cache?", fontWeight = FontWeight.Bold) },
                text = {
                    Text("Isso liberará aproximadamente ${FormatUtils.formatBytes(totalCacheBytes)} excluindo arquivos temporários de todos os aplicativos. Nenhum dado pessoal ou conta será perdida.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmClearAll = false
                            clearAllCache()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusWarning)
                    ) {
                        Text("Confirmar e Limpar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmClearAll = false }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurfaceVariant
            )
        }

        if (isClearingAll) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Limpando cache...", fontWeight = FontWeight.Bold) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        CircularProgressIndicator(color = StatusWarning)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Removendo arquivos temporários de todos os aplicativos...", fontSize = 12.sp, color = TextSecondary)
                    }
                },
                confirmButton = {},
                containerColor = DarkSurfaceVariant
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Aviso de Permissão de Acesso de Uso caso falte e não haja Root
            if (!hasUsagePermission) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Acesso de uso necessário", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            Text("Para calcular o cache de cada app sem root, conceda permissão de acesso de uso.", fontSize = 11.sp, color = TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Abra as Configurações de Acesso de Uso", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (_: Exception) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Abra as Configurações de Acesso de Uso", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Conceder", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Banner de resultado final ("X GB liberados")
            if (lastFreedMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusSuccess.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(lastFreedMessage!!, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = StatusSuccess)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { lastFreedMessage = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar", tint = StatusSuccess, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (isLoading && cacheList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = primaryAccent)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Calculando cache dos aplicativos...", fontSize = 12.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("$analyzedCount de ${totalAppsCount.coerceAtLeast(installedApps.size)} analisados", fontSize = 11.sp, color = primaryAccent)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(cacheList, key = { it.app.packageName }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RealAppIcon(packageName = item.app.packageName, size = 38.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.app.appName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Cache: ${FormatUtils.formatBytes(item.cacheBytes)}",
                                            fontSize = 11.sp,
                                            color = if (item.cacheBytes > 0) StatusWarning else TextMuted
                                        )
                                        if (failedApps[item.app.packageName] == true) {
                                            Text(
                                                text = "• não foi possível limpar",
                                                fontSize = 11.sp,
                                                color = StatusError,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                                if (item.cacheBytes > 0) {
                                    OutlinedButton(
                                        onClick = { clearAppCache(item) },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, DarkCardBorder),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Limpar", fontSize = 11.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("Limpo", fontSize = 11.sp, color = StatusSuccess, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 3. TELA: DETALHES DO SISTEMA E PARTIÇÕES
// =========================================================================
data class SystemPartitionItem(
    val name: String,
    val path: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSystemScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)

    val partitions = remember {
        val list = mutableListOf<SystemPartitionItem>()
        fun addPart(name: String, path: String) {
            val f = File(path)
            if (f.exists()) {
                try {
                    val stat = StatFs(path)
                    val bSize = stat.blockSizeLong
                    val total = stat.blockCountLong * bSize
                    val free = stat.availableBlocksLong * bSize
                    val used = (total - free).coerceAtLeast(0L)
                    list.add(SystemPartitionItem(name, path, total, used, free))
                } catch (_: Throwable) {}
            }
        }
        addPart("Sistema (/system)", "/system")
        addPart("Fabricante (/vendor)", "/vendor")
        addPart("Produto (/product)", "/product")
        addPart("Extensão do Sistema (/system_ext)", "/system_ext")
        addPart("ODM (/odm)", "/odm")
        list
    }

    val androidRelease = remember { Build.VERSION.RELEASE }
    val sdkInt = remember { Build.VERSION.SDK_INT }
    val securityPatch = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "N/A"
    }
    val model = remember { "${Build.MANUFACTURER} ${Build.MODEL}" }
    val buildId = remember { Build.DISPLAY }

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
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Sistema Android",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Versão e partições do dispositivo",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card: Informações do Android
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "INFORMAÇÕES DO SO",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryAccent,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SystemInfoRow("Versão do Android", "Android $androidRelease (API $sdkInt)")
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SystemInfoRow("Dispositivo", model)
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SystemInfoRow("Patch de segurança", securityPatch)
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SystemInfoRow("Compilação", buildId)
                }
            }

            // Card: Partições do Sistema
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "PARTIÇÕES DO SISTEMA (SOMENTE LEITURA)",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (partitions.isEmpty()) {
                        Text("Nenhuma partição encontrada ou acesso restrito.", fontSize = 12.sp, color = TextMuted)
                    } else {
                        partitions.forEachIndexed { index, part ->
                            if (index > 0) {
                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 10.dp))
                            }
                            val pct = if (part.totalBytes > 0) ((part.usedBytes.toDouble() / part.totalBytes.toDouble()) * 100.0).toFloat() else 0f
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = part.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text(text = part.path, fontSize = 10.5.sp, color = TextMuted)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${FormatUtils.formatBytes(part.freeBytes)} livres",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentEmeraldGreen
                                        )
                                        Text(
                                            text = "de ${FormatUtils.formatBytes(part.totalBytes)}",
                                            fontSize = 10.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = primaryAccent,
                                    trackColor = DarkBg
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = TextSecondary)
        Text(text = value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}
