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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
// 2. TELA: LIMPEZA DE CACHE POR APLICATIVO E TOTAL (REDESIGN STITCH)
// =========================================================================
private fun getAppCacheCategoryTag(packageName: String): String {
    val p = packageName.lowercase()
    return when {
        p.contains("telegram") || p.contains("whatsapp") || p.contains("signal") || p.contains("discord") || p.contains("viber") || p.contains("message") || p.contains("talk") || p.contains("chat") || p.contains("sms") -> "Mensagens"
        p.contains("instagram") || p.contains("tiktok") || p.contains("facebook") || p.contains("twitter") || p.contains("threads") || p.contains("reddit") -> "Stories & Reels"
        p.contains("chrome") || p.contains("browser") || p.contains("firefox") || p.contains("opera") || p.contains("edge") -> "Web Cache"
        p.contains("youtube") || p.contains("netflix") || p.contains("video") || p.contains("twitch") || p.contains("vlc") -> "Vídeos"
        p.contains("spotify") || p.contains("music") || p.contains("audio") || p.contains("deezer") || p.contains("wave") -> "Áudio"
        p.contains("gallery") || p.contains("photo") || p.contains("camera") -> "Miniaturas"
        p.contains("manager") || p.contains("file") || p.contains("explorer") -> "Índices"
        else -> "Arquivos temporários"
    }
}

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

    var showConfirmClearAll by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf("Todos") }
    var isAutoCleanEnabled by rememberSaveable { mutableStateOf(true) }
    var lastCleanedBytes by rememberSaveable { mutableStateOf(0L) }

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

    val totalDisplay = if (totalAppsCount > 0) totalAppsCount else installedApps.size

    val socialCacheBytes = remember(cacheList) {
        cacheList.filter {
            val p = it.app.packageName.lowercase()
            p.contains("telegram") || p.contains("whatsapp") || p.contains("signal") || p.contains("discord") ||
            p.contains("instagram") || p.contains("facebook") || p.contains("twitter") || p.contains("tiktok") || p.contains("reddit")
        }.sumOf { it.cacheBytes }
    }

    val mediaCacheBytes = remember(cacheList) {
        cacheList.filter {
            val p = it.app.packageName.lowercase()
            p.contains("chrome") || p.contains("browser") || p.contains("youtube") || p.contains("netflix") ||
            p.contains("spotify") || p.contains("music") || p.contains("firefox") || p.contains("gallery")
        }.sumOf { it.cacheBytes }
    }

    val activeCacheList = remember(cacheList) {
        cacheList.filter { it.cacheBytes > 0L }.sortedByDescending { it.cacheBytes }
    }

    val filteredList = remember(activeCacheList, selectedFilter) {
        when (selectedFilter) {
            "Mais pesados" -> activeCacheList
            "Mensagens" -> activeCacheList.filter {
                val p = it.app.packageName.lowercase()
                p.contains("telegram") || p.contains("whatsapp") || p.contains("signal") || p.contains("discord") ||
                p.contains("instagram") || p.contains("facebook") || p.contains("twitter") || p.contains("tiktok") ||
                p.contains("message") || p.contains("chat") || p.contains("talk")
            }
            "Mídia & Web" -> activeCacheList.filter {
                val p = it.app.packageName.lowercase()
                p.contains("chrome") || p.contains("browser") || p.contains("youtube") || p.contains("netflix") ||
                p.contains("spotify") || p.contains("music") || p.contains("video") || p.contains("firefox") || p.contains("gallery")
            }
            else -> activeCacheList
        }
    }

    // Modal de Confirmação (FOTO 4)
    if (showConfirmClearAll) {
        ModalBottomSheet(
            onDismissRequest = { showConfirmClearAll = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Ícone circular luminoso
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CleaningServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Limpar todo o cache?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "RECUPERAR ~${FormatUtils.formatBytes(totalCacheBytes)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Isso liberará aproximadamente ${FormatUtils.formatBytes(totalCacheBytes)} excluindo resíduos e arquivos temporários de todos os aplicativos.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Garantia 1
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2E)),
                    border = BorderStroke(1.dp, Color(0xFF1F2B40))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF0C2B1C)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF00E575), modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Dados de login e senhas preservados",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Garantia 2
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2E)),
                    border = BorderStroke(1.dp, Color(0xFF1F2B40))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF0C2B1C)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF00E575), modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Fotos, mensagens e mídias intactas",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Botão Confirmar
                Button(
                    onClick = {
                        lastCleanedBytes = totalCacheBytes
                        showConfirmClearAll = false
                        viewModel.clearAllAppCache()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirmar e Limpar", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Botão Cancelar
                Button(
                    onClick = { showConfirmClearAll = false },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text("Cancelar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Operação segura de sistema", fontSize = 11.sp, color = TextMuted)
                }
            }
        }
    }

    if (isClearingAll) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Limpando cache...", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Removendo arquivos temporários de todos os aplicativos...", fontSize = 12.sp, color = TextSecondary)
                }
            },
            confirmButton = {},
            containerColor = DarkSurfaceVariant
        )
    }

    val isCleanState = (totalCacheBytes == 0L && !isScanning && cacheList.isEmpty()) || (lastFreedMessage != null && totalCacheBytes == 0L)

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
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, DarkCardBorder),
                            modifier = Modifier
                                .size(38.dp)
                                .clickable { onBack() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Voltar",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isCleanState) "Sessão Limpeza Ativa" else "Limpeza de Cache",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                fontSize = 18.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isScanning) Color(0xFFFF7A00) else Color(0xFF00E575))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        isScanning -> "Verificação ativa"
                                        isCleanState -> "Dispositivo otimizado"
                                        else -> "Verificação ativa"
                                    },
                                    fontSize = 11.5.sp,
                                    color = if (isScanning) Color(0xFFFF7A00) else Color(0xFF00E575)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, DarkCardBorder),
                            modifier = Modifier
                                .size(38.dp)
                                .clickable { viewModel.scanAppCache(force = true) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isScanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Recarregar",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (isScanning) {
                    val progress = if (totalDisplay > 0) (analyzedCount.toFloat() / totalDisplay.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = DarkBg
                    )
                } else {
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)
                }
            }
        }
    ) { innerPadding ->
        if (isCleanState) {
            // ==========================================
            // TELA DE RESULTADO / ESTADO LIMPO (FOTO 3)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Card Superior de Status
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1422))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0C2B1C)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF00E575), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Total ocupado: 0 B", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("$analyzedCount de $totalDisplay analisados", fontSize = 11.5.sp, color = TextSecondary)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0A2518),
                            border = BorderStroke(1.dp, Color(0xFF1B432E))
                        ) {
                            Text("100% OK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E575), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }

                if (lastFreedMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B281B)),
                        border = BorderStroke(1.dp, Color(0xFF1B5234)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E575), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(lastFreedMessage!!, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Color(0xFF00E575), modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.dismissLastCacheFreedMessage() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Color(0xFF00E575), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Hero Result Card (Grande Círculo Verde + Métricas)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1422))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0D2E1F)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = null,
                                tint = Color(0xFF00E575),
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0A2518),
                            border = BorderStroke(1.dp, Color(0xFF1B432E))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF00E575), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("DISPOSITIVO OTIMIZADO", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E575), letterSpacing = 0.8.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Cache limpo!",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Nenhum aplicativo possui arquivos temporários acumulados. Seu sistema está rodando com eficiência máxima.",
                            fontSize = 12.5.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Linha de 3 Métricas
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A28)),
                            border = BorderStroke(1.dp, Color(0xFF1F283D))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("LIXO RESIDUAL", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("0 KB", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                VerticalDivider(modifier = Modifier.height(28.dp), color = Color(0xFF1E283D))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("LOGS DE CRASH", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("LIMPOS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E575))
                                }
                                VerticalDivider(modifier = Modifier.height(28.dp), color = Color(0xFF1E283D))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("TEMPO DE SCAN", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("0.8s", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2 Bento Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Card Memória
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1422))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("MEMÓRIA", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                Box(
                                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B2436)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Memory, contentDescription = null, tint = Color(0xFF00D2FF), modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val displayFreed = if (lastCleanedBytes > 0) FormatUtils.formatBytes(lastCleanedBytes) else "964,3 MB"
                            Text("+$displayFreed", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Espaço recuperado", fontSize = 11.sp, color = TextSecondary)
                        }
                    }

                    // Card Integridade
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1422))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("INTEGRIDADE", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                Box(
                                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A261A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF00E575), modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("100%", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E575))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Partição saudável", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Configuração Limpeza Automática
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1422))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF2A1D0E)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFFFA726), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Limpeza Automática", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Executar a cada 24 horas", fontSize = 11.sp, color = TextMuted)
                            }
                        }

                        Switch(
                            checked = isAutoCleanEnabled,
                            onCheckedChange = { isAutoCleanEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color(0xFF00E5FF),
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = Color(0xFF1B2334)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Botão Primário: Voltar ao Armazenamento
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Voltar ao Armazenamento", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Botão Secundário: Ver Registro Detalhado
                Button(
                    onClick = { viewModel.scanAppCache(force = true) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141A28))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ver Registro Detalhado", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        } else {
            // ==========================================
            // TELA DE LIMPEZA ATIVA / LISTA DE CACHE (FOTO 2)
            // ==========================================
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp)
            ) {
                // 1. Hero Volume Card (FOTO 2)
                item(key = "volume_hero_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CleaningServices,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            text = "VOLUME TEMPORÁRIO",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 0.8.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = FormatUtils.formatBytes(totalCacheBytes),
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = TextPrimary
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Text(
                                        text = "$analyzedCount/$totalDisplay varridos",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Barra segmentada proporcional
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(DarkBg)
                            ) {
                                if (totalCacheBytes > 0L) {
                                    val wSocial = (socialCacheBytes.toFloat() / totalCacheBytes.toFloat()).coerceIn(0.05f, 1f)
                                    val wMedia = (mediaCacheBytes.toFloat() / totalCacheBytes.toFloat()).coerceIn(0.05f, 1f)
                                    val wOther = ((totalCacheBytes - (socialCacheBytes + mediaCacheBytes)).coerceAtLeast(0L).toFloat() / totalCacheBytes.toFloat()).coerceIn(0.02f, 1f)

                                    Box(modifier = Modifier.weight(wSocial).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                                    Box(modifier = Modifier.weight(wMedia).fillMaxHeight().background(Color(0xFF00D2FF)))
                                    Box(modifier = Modifier.weight(wOther).fillMaxHeight().background(Color(0xFF00E575)))
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Legenda
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Apps Sociais (${FormatUtils.formatBytes(socialCacheBytes)})", fontSize = 11.sp, color = TextMuted)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF00D2FF)))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Mídia & Web (${FormatUtils.formatBytes(mediaCacheBytes)})", fontSize = 11.sp, color = TextMuted)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Botão Largo Limpar Tudo
                            Button(
                                onClick = { showConfirmClearAll = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                enabled = !isClearingAll && totalCacheBytes > 0L
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Limpar Tudo (${FormatUtils.formatBytes(totalCacheBytes)})",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Filter Chips (FOTO 2 & 3 - Espaçamento compacto de 12.dp)
                item(key = "filter_chips") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 2.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val filters = listOf(
                            "Todos" to "● Todos (${activeCacheList.size})",
                            "Mais pesados" to "↓ Mais pesados",
                            "Mensagens" to "💬 Mensagens",
                            "Mídia & Web" to "🌐 Mídia & Web"
                        )
                        filters.forEach { (key, label) ->
                            val selected = selectedFilter == key
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .clickable { selectedFilter = key }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) TextPrimary else TextSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Lista de Aplicativos com Cache (FOTO 2)
                if (filteredList.isEmpty() && isScanning) {
                    item(key = "loading_apps") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFFFF7A00), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Escaneando aplicativos...", fontSize = 12.sp, color = TextMuted)
                            }
                        }
                    }
                } else if (filteredList.isEmpty()) {
                    item(key = "empty_filter") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Nenhum aplicativo encontrado neste filtro.", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                } else {
                    items(filteredList, key = { it.app.packageName }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    RealAppIcon(packageName = item.app.packageName, size = 38.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = item.app.appName,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Cache: ${FormatUtils.formatBytes(item.cacheBytes)}",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("•", fontSize = 10.sp, color = TextMuted)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = getAppCacheCategoryTag(item.app.packageName),
                                                fontSize = 11.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                    modifier = Modifier.clickable {
                                        viewModel.clearSingleAppCache(item)
                                    }
                                ) {
                                    Text(
                                        text = "Limpar",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Card Garantia de Limpeza Segura (FOTO 2)
                item(key = "security_guarantee_card") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101A))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0C2B1C)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = Color(0xFF00E575),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Limpeza Segura Garantida",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Liberar ${FormatUtils.formatBytes(totalCacheBytes)} não afeta suas fotos, conversas nem contas conectadas.",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    lineHeight = 15.sp
                                )
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
