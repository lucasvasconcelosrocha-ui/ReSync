package com.resync.app.ui.screens

import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ripple
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resync.app.R
import com.resync.app.ui.viewmodel.DriveQuotaState
import com.resync.app.ui.viewmodel.GoogleDriveState
import com.resync.app.ui.viewmodel.RemoteBackupsState
import com.resync.app.engine.CapabilityManager
import com.resync.app.ui.components.GoogleDriveAccountsBottomSheet
import com.resync.app.ui.components.cards.InfoRow
import com.resync.app.ui.components.cards.InteractiveGlassCard
import com.resync.app.ui.components.cards.DashboardActionButton
import com.resync.app.ui.components.SmoothCurrentItemText
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.ui.components.SmoothPercentText
import com.resync.app.ui.components.UserAvatar
import com.resync.app.ui.components.CompletionAnimation
import com.resync.app.ui.components.CompletionAnimationSize
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.resync.app.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.*

data class InternalStorageState(
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val usedPercent: Float = 0f
)

private fun readInternalStorageStats(): InternalStorageState {
    return try {
        val path = Environment.getDataDirectory().path
        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val totalBytes = stat.blockCountLong * blockSize
        val freeBytes = stat.availableBlocksLong * blockSize
        val usedBytes = maxOf(0L, totalBytes - freeBytes)
        val percent = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes) * 100.0).toFloat() else 0f
        InternalStorageState(
            freeBytes = freeBytes,
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            usedPercent = percent
        )
    } catch (_: Exception) {
        InternalStorageState()
    }
}

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToApps: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToCloud: () -> Unit,
    onNavigateToDns: () -> Unit,
    onNavigateToCustomWizard: () -> Unit,
    onNavigateToRestore: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToActiveBackups: () -> Unit = {},
    onNavigateToBackupManager: () -> Unit = {},
    onNavigateToCategory: (String) -> Unit = {},
    onNavigateToStorageDetails: () -> Unit = {},
    onNavigateToScheduledBackups: () -> Unit = {},
    onNavigateToFolders: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)

    val multiAccounts by viewModel.multiCloudAccounts.collectAsStateWithLifecycle()
    val activeAccountEmail by viewModel.activeAccountEmail.collectAsStateWithLifecycle()
    val activeAccount = remember(multiAccounts, activeAccountEmail) {
        multiAccounts.firstOrNull { it.email == activeAccountEmail }
            ?: multiAccounts.firstOrNull { it.isDefault }
            ?: multiAccounts.firstOrNull()
    }

    val googleDriveState by viewModel.googleDriveState.collectAsStateWithLifecycle()
    val driveQuotaState by viewModel.driveQuotaState.collectAsStateWithLifecycle()
    val remoteBackupsState by viewModel.remoteBackupsState.collectAsStateWithLifecycle()
    val isRefreshingActiveAccount by viewModel.isRefreshingActiveAccount.collectAsStateWithLifecycle()

    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val rootStatus by viewModel.rootAccessStatus.collectAsStateWithLifecycle()
    val processExitBanner by viewModel.previousProcessExitBanner.collectAsStateWithLifecycle()
    val isNotificationBlocked by viewModel.isNotificationBlocked.collectAsStateWithLifecycle()
    val activeOps by viewModel.activeOperations.collectAsStateWithLifecycle()
    val completedOps by viewModel.completedOperations.collectAsStateWithLifecycle()

    // 1. Armazenamento interno em tempo real (atualizado a cada 2s enquanto a tela estiver visível)
    var internalStorage by remember { mutableStateOf(readInternalStorageStats()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                internalStorage = readInternalStorageStats()
                delay(2000L)
            }
        }
    }

    // 2. Google Drive Quota periódica (atualizada a cada 30s enquanto a tela estiver visível e ao entrar)
    LaunchedEffect(lifecycleOwner, activeAccountEmail) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val email = activeAccountEmail
            if (!email.isNullOrBlank()) {
                viewModel.refreshActiveAccountData(email, isManual = false)
            }
            while (currentCoroutineContext().isActive) {
                delay(30_000L)
                val currentEmail = activeAccountEmail
                if (!currentEmail.isNullOrBlank()) {
                    viewModel.refreshActiveAccountData(currentEmail, isManual = false)
                }
            }
        }
    }

    var showAccountsBottomSheet by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    var isPullRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = isPullRefreshing,
        onRefresh = {
            isPullRefreshing = true
            coroutineScope.launch {
                internalStorage = readInternalStorageStats()
                viewModel.refreshDashboard()
                delay(600)
                isPullRefreshing = false
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
        ) {
            // ==========================================
            // 1) TOPO (Estilo Swift Backup)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Esquerda: FOTO DE PERFIL REAL da conta ativa do Google Drive (~44dp)
                UserAvatar(
                    photoUrl = activeAccount?.photoUrl,
                    displayName = activeAccount?.name,
                    email = activeAccount?.email,
                    size = 44.dp,
                    borderColor = primaryAccent,
                    borderWidth = 1.5.dp,
                    accentColor = primaryAccent,
                    onClick = { showAccountsBottomSheet = true }
                )

                // Centro: Logo + Nome "ReSync"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo_resync),
                        contentDescription = "ReSync",
                        modifier = Modifier.size(34.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ReSync",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextPrimary
                    )
                }

                // Direita: Ícone de Terminal / Logs
                IconButton(
                    onClick = onNavigateToLogs,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Logs",
                        tint = primaryAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Banner discreto se as notificações ou o canal estiverem desativados
            if (isNotificationBlocked) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            try {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            } catch (_: Throwable) {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Throwable) {}
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notificações desativadas",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Ative para acompanhar o progresso de backups na barra e na ilha de status.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = "Ativar",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = StatusWarning,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // Banner opcional de diagnóstico do processo anterior
            if (processExitBanner != null) {
                val banner = processExitBanner!!
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(banner.timestamp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Diagnóstico do Processo Anterior",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                            }
                            IconButton(
                                onClick = { viewModel.dismissProcessExitBanner() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "O app encerrou anteriormente por: ${banner.reasonTitle}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusWarning
                        )
                        Text(
                            text = "Data: $dateStr • PID: ${banner.pid}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = onNavigateToLogs,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Ver detalhes nos logs do sistema", fontSize = 12.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ==========================================
            // 2) SEÇÃO VISÃO GERAL (Swift Backup Style)
            // ==========================================
            Text(
                text = "VISÃO GERAL",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
            )

            // a) CÁLCULOS DE ARMAZENAMENTO E DRIVE
            val freeGb = internalStorage.freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val totalGb = internalStorage.totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val internalProgress = (internalStorage.usedPercent / 100f).coerceIn(0f, 1f)
            val internalUsedText = String.format(Locale("pt", "BR"), "%.1f%% usado", internalStorage.usedPercent)
            val internalStorageText = String.format(Locale("pt", "BR"), "%.2f GB livres de %.2f GB", freeGb, totalGb)

            // b) GOOGLE DRIVE (Conta ativa, photoUrl/initial, quota e métricas)
            val isDriveOffline = googleDriveState == GoogleDriveState.CONNECTION_ERROR
            val isDriveSessionExpired = googleDriveState == GoogleDriveState.SESSION_EXPIRED

            val (quotaUsed, quotaLimit) = when (val q = driveQuotaState) {
                is DriveQuotaState.Loaded -> Pair(q.usedBytes, q.totalBytes)
                else -> Pair(activeAccount?.quotaUsedBytes ?: 0L, activeAccount?.quotaTotalBytes ?: 0L)
            }

            val driveProgress = if (quotaLimit > 0) (quotaUsed.toFloat() / quotaLimit.toFloat()).coerceIn(0f, 1f) else 0f
            val driveUsedPercent = if (quotaLimit > 0) (quotaUsed.toDouble() / quotaLimit.toDouble() * 100.0) else 0.0

            val backupsCountText = when (val b = remoteBackupsState) {
                is RemoteBackupsState.Loaded -> "${b.backups.size}"
                is RemoteBackupsState.Loading -> "..."
                else -> if (activeAccount != null) "..." else "—"
            }

            val lastSyncFormatted = when {
                remoteBackupsState is RemoteBackupsState.Loaded && (remoteBackupsState as RemoteBackupsState.Loaded).backups.isNotEmpty() -> {
                    val maxTime = (remoteBackupsState as RemoteBackupsState.Loaded).backups.maxOf { it.modifiedTime }
                    SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault()).format(Date(maxTime))
                }
                activeAccount?.lastSyncTimestamp != null && activeAccount.lastSyncTimestamp > 0L -> {
                    SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault()).format(Date(activeAccount.lastSyncTimestamp))
                }
                googleDriveState == GoogleDriveState.CONNECTED -> "Nenhum backup"
                else -> "—"
            }

            // CARD UNIFICADO: GOOGLE DRIVE + ARMAZENAMENTO INTERNO
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(StandardCardShape)
                    .border(1.dp, StandardCardBorder, StandardCardShape),
                colors = CardDefaults.cardColors(containerColor = StandardCardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // PARTE 1: GOOGLE DRIVE
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToCloud)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                UserAvatar(
                                    photoUrl = activeAccount?.photoUrl,
                                    displayName = activeAccount?.name,
                                    email = activeAccount?.email,
                                    size = 26.dp,
                                    borderColor = AccentElectricCyan,
                                    borderWidth = 1.dp,
                                    accentColor = AccentElectricCyan
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Column {
                                    Text(
                                        text = "Google Drive",
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = activeAccount?.email ?: "Nenhuma conta ativa",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isRefreshingActiveAccount) {
                                    Text(
                                        text = "Atualizando…",
                                        fontSize = 11.sp,
                                        color = AccentElectricCyan,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else if (isDriveOffline) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(StatusWarning.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Sem conexão",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusWarning
                                        )
                                    }
                                } else if (isDriveSessionExpired) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AccentSunsetOrange.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Reconectar",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentSunsetOrange
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (activeAccount != null) {
                            val quotaText = if (quotaLimit > 0) {
                                val freeQuota = maxOf(0L, quotaLimit - quotaUsed)
                                "${formatStorageMetric(freeQuota)} livres de ${formatStorageMetric(quotaLimit)}"
                            } else {
                                "${formatStorageMetric(quotaUsed)} usados"
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = quotaText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = String.format(Locale("pt", "BR"), "%.1f%% usado", driveUsedPercent),
                                    fontSize = 11.5.sp,
                                    color = TextSecondary
                                )
                            }

                            if (quotaLimit > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { driveProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = AccentElectricCyan,
                                    trackColor = DarkBg
                                )
                            }
                        } else {
                            Text(
                                text = "Toque para vincular sua conta do Google Drive",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Backups em nuvem: ", fontSize = 11.sp, color = TextSecondary)
                                Text(backupsCountText, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Última sincronização: ", fontSize = 11.sp, color = TextSecondary)
                                Text(lastSyncFormatted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // PARTE 2: ARMAZENAMENTO INTERNO
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToStorageDetails)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(primaryAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhoneAndroid,
                                        contentDescription = null,
                                        tint = primaryAccent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Armazenamento interno",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }

                            Text(
                                text = internalUsedText,
                                fontSize = 11.5.sp,
                                color = TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = internalStorageText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { internalProgress },
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

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // SEÇÃO CATEGORIAS (Subida para logo abaixo do card unificado)
            // ==========================================
            Text(
                text = "CATEGORIAS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
            )

            // Linha 1 de chips: Aplicativos, Mensagens, Chamadas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CategoryChip(
                    title = "Aplicativos",
                    icon = Icons.Default.Apps,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToCategory("apps") }
                )
                CategoryChip(
                    title = "Mensagens",
                    icon = Icons.Default.Message,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToCategory("messages") }
                )
                CategoryChip(
                    title = "Chamadas",
                    icon = Icons.Default.Call,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToCategory("calls") }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Linha 2 de chips: Contatos, Pastas, Wi-Fi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CategoryChip(
                    title = "Contatos",
                    icon = Icons.Default.Contacts,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToCategory("contacts") }
                )
                CategoryChip(
                    title = "Pastas",
                    icon = Icons.Default.Folder,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToFolders
                )
                CategoryChip(
                    title = "Wi-Fi",
                    icon = Icons.Default.Wifi,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToCategory("wifi") }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Linha 3 de chips: Bluetooth, Papéis de parede, DNS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CategoryChip(
                    title = "Bluetooth",
                    icon = Icons.Default.Bluetooth,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToCategory("bluetooth") }
                )
                CategoryChip(
                    title = "Papéis de parede",
                    icon = Icons.Default.Wallpaper,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(1.35f),
                    onClick = { onNavigateToCategory("wallpaper") }
                )
                CategoryChip(
                    title = "DNS",
                    icon = Icons.Default.Dns,
                    primaryAccent = primaryAccent,
                    modifier = Modifier.weight(0.75f),
                    onClick = { onNavigateToCategory("dns") }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // c) STATUS DO ROOT (Estilo Swift Backup)
            val isRootGranted = permissionsState.isRootGranted
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(StandardCardShape)
                    .border(1.dp, StandardCardBorder, StandardCardShape)
                    .clickable {
                        if (rootStatus == CapabilityManager.RootStatus.PERMISSION_NEEDED) {
                            viewModel.requestRootPermission()
                        } else {
                            onNavigateToPermissions()
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = StandardCardBg)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "STATUS DO ROOT",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 0.5.sp
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRootGranted) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Root Concedido",
                                tint = StatusSuccess,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Concedido",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusSuccess
                            )
                        } else {
                            val isPending = rootStatus == CapabilityManager.RootStatus.PERMISSION_NEEDED
                            Icon(
                                imageVector = if (isPending) Icons.Default.Warning else Icons.Default.Cancel,
                                contentDescription = "Root Negado",
                                tint = if (isPending) StatusWarning else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isPending) "Permissão pendente" else "Negado",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPending) StatusWarning else TextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // 3) RESTANTE DO INÍCIO: BOTÕES PADRONIZADOS
            // ==========================================

            // AÇÃO 1: [ NOVO BACKUP ]
            DashboardActionButton(
                text = "NOVO BACKUP",
                onClick = onNavigateToCustomWizard
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_novo_backup),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // AÇÃO 2: [ BACKUPS AGENDADOS ]
            DashboardActionButton(
                text = "BACKUPS AGENDADOS",
                onClick = onNavigateToScheduledBackups
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = primaryAccent,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // AÇÃO 3: [ BACKUPS / RESTAURAR ]
            DashboardActionButton(
                text = stringResource(R.string.action_backups_restore),
                onClick = onNavigateToRestore
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    tint = AccentEmeraldGreen,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // CARD: OPERAÇÕES EM ANDAMENTO
            val runningOps = activeOps.filter { !it.isFinished && it.status != com.resync.app.engine.OperationStatus.CANCELLED && it.status != com.resync.app.engine.OperationStatus.ERROR }
            val hasActiveOps = runningOps.isNotEmpty()
            InteractiveGlassCard(
                title = stringResource(R.string.active_backups_title),
                icon = Icons.Default.PendingActions,
                iconTint = if (hasActiveOps) primaryAccent else TextMuted,
                badgeText = if (hasActiveOps) "${runningOps.size} ATIV${if (runningOps.size == 1) "A" else "AS"}" else "0 ATIVAS",
                badgeColor = if (hasActiveOps) primaryAccent else DarkCardBorder,
                onClick = onNavigateToActiveBackups
            ) {
                if (hasActiveOps) {
                    runningOps.forEachIndexed { idx, op ->
                        if (idx > 0) {
                            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 10.dp))
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    val typeTag = if (op.type == com.resync.app.engine.OperationType.BACKUP) "Backup" else "Restauração"
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = primaryAccent.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = typeTag,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = op.title.ifBlank { typeTag },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                SmoothPercentText(
                                    percent = op.progressPercent,
                                    color = primaryAccent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            SmoothCurrentItemText(
                                text = op.currentItemName.ifBlank { op.stage },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = TextSecondary,
                                maxLines = 2,
                                reservedHeight = 30.dp
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            SmoothLinearProgressBar(
                                progress = op.progressPercent,
                                color = primaryAccent,
                                trackColor = DarkBg,
                                height = 6.dp
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val speedStr = if (op.speedBytesPerSec > 0L) "${FormatUtils.formatBytes(op.speedBytesPerSec)}/s" else null
                                val remStr = if (!op.isFinished) FormatUtils.formatShortEta(op.estimatedRemainingSeconds) else null

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (speedStr != null) {
                                        Text(text = speedStr, fontSize = 10.5.sp, color = TextMuted)
                                        if (remStr != null) {
                                            Text(text = " • ", fontSize = 10.sp, color = TextMuted)
                                        }
                                    }
                                    if (remStr != null) {
                                        Text(text = remStr, fontSize = 10.5.sp, color = TextMuted)
                                    }
                                    if (speedStr == null && remStr == null) {
                                        Text(text = op.stage, fontSize = 10.5.sp, color = TextMuted)
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            if (op.isPaused) viewModel.resumeOperation(op.id) else viewModel.pauseOperation(op.id)
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(
                                            text = if (op.isPaused) "Retomar" else "Pausar",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = primaryAccent
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.cancelOperation(op.id) },
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(
                                            text = "Cancelar",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = StatusError
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Nenhuma operação em execução no momento",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Últimas 5 operações concluídas (sucesso, falha ou cancelada)
                if (completedOps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ÚLTIMAS CONCLUÍDAS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    completedOps.take(5).forEachIndexed { cIdx, cop ->
                        if (cIdx > 0) {
                            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 4.dp))
                        }
                        val isSuccess = cop.status == com.resync.app.engine.OperationStatus.COMPLETED
                        val isCancelled = cop.status == com.resync.app.engine.OperationStatus.CANCELLED
                        val statusColor = if (isSuccess) StatusSuccess else if (isCancelled) StatusWarning else StatusError
                        val statusText = if (isSuccess) "Concluído" else if (isCancelled) "Cancelado" else "Falha"
                        val timeStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(cop.timestamp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                if (isSuccess) {
                                    CompletionAnimation(
                                        size = CompletionAnimationSize.COMPACT,
                                        themeAccent = statusColor,
                                        playKey = cop.id
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isCancelled) Icons.Default.Cancel else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = statusColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = cop.title.ifBlank { if (cop.type == com.resync.app.engine.OperationType.BACKUP) "Backup" else "Restauração" },
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$statusText • $timeStr",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Espaço seguro inferior para a lista não ficar coberta pela barra flutuante
            Spacer(modifier = Modifier.height(36.dp))
        }
    }

    // Bottom Sheet de Contas do Google Drive
    if (showAccountsBottomSheet) {
        GoogleDriveAccountsBottomSheet(
            viewModel = viewModel,
            onDismiss = { showAccountsBottomSheet = false }
        )
    }
}

@Composable
private fun CategoryChip(
    title: String,
    icon: ImageVector,
    primaryAccent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = DarkSurfaceVariant.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = primaryAccent,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(2.5.dp))
            Text(
                text = title,
                fontSize = if (title.length > 12) 8.5.sp else 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

private fun formatStorageMetric(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes.toDouble() / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    val tb = gb / 1024.0
    return when {
        tb >= 1.0 -> String.format(Locale("pt", "BR"), "%.2f TB", tb)
        gb >= 1.0 -> String.format(Locale("pt", "BR"), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale("pt", "BR"), "%.1f MB", mb)
        else -> String.format(Locale("pt", "BR"), "%.1f KB", kb)
    }
}
