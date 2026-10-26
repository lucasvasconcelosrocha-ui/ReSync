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
import com.resync.app.data.model.*
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
// 2. TELA: LIMPEZA DE CACHE POR APLICATIVO E TOTAL (Fonte Única de Verdade)
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageCacheScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()

    val cacheList by viewModel.appCacheList.collectAsStateWithLifecycle()
    val isScanning by viewModel.isCacheScanning.collectAsStateWithLifecycle()
    val analyzedCount by viewModel.cacheAnalyzedCount.collectAsStateWithLifecycle()
    val totalAppsCount by viewModel.cacheTotalAppsCount.collectAsStateWithLifecycle()
    val isClearingAll by viewModel.isClearingCache.collectAsStateWithLifecycle()
    val lastFreedMessage by viewModel.lastCacheFreedMessage.collectAsStateWithLifecycle()
    val totalCacheBytes by viewModel.totalAppCacheBytes.collectAsStateWithLifecycle()

    var showConfirmClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.scanAppCache(force = false)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!isScanning && !isClearingAll) {
                    viewModel.scanAppCache(force = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
                    Row(
                        modifier = Modifier.weight(1f),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Limpeza de Cache",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            // Linha 1: Total ocupado: X GB
                            Text(
                                text = "Total ocupado: ${FormatUtils.formatBytes(totalCacheBytes)}",
                                fontSize = 11.5.sp,
                                color = primaryAccent,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Linha 2: X de Y analisados
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val totalDisplay = if (totalAppsCount > 0) totalAppsCount else installedApps.size
                                Text(
                                    text = "$analyzedCount de $totalDisplay analisados",
                                    fontSize = 10.5.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isScanning) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.5.dp,
                                        color = primaryAccent
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Botões à direita
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!isScanning && analyzedCount < installedApps.size && installedApps.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.scanAppCache(force = true) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Recarregar", fontSize = 11.sp, color = primaryAccent, maxLines = 1)
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

                if (isScanning && (totalAppsCount > 0 || installedApps.isNotEmpty())) {
                    val totalDisplay = if (totalAppsCount > 0) totalAppsCount else installedApps.size
                    val progress = (analyzedCount.toFloat() / totalDisplay.toFloat()).coerceIn(0f, 1f)
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
                            viewModel.clearAllAppCache()
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
                        IconButton(onClick = { viewModel.dismissLastCacheFreedMessage() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar", tint = StatusSuccess, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (isScanning && cacheList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = primaryAccent)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Calculando cache dos aplicativos...", fontSize = 12.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(4.dp))
                        val totalDisplay = if (totalAppsCount > 0) totalAppsCount else installedApps.size
                        Text("$analyzedCount de $totalDisplay analisados", fontSize = 11.sp, color = primaryAccent)
                    }
                }
            } else if (cacheList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusSuccess,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Cache limpo!",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Nenhum aplicativo possui arquivos temporários acumulados.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
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
                                    }
                                }
                                if (item.cacheBytes > 0) {
                                    OutlinedButton(
                                        onClick = { viewModel.clearSingleAppCache(item) },
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
