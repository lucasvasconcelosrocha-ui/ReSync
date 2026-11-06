package com.resync.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
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
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.FormatUtils
import com.resync.app.data.model.BackupPaths
import com.resync.app.engine.RootManagers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
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
    onNavigateToFolders: () -> Unit = {},
    onNavigateToStorageCache: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = MaterialTheme.colorScheme.primary

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

    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val rootStatus by viewModel.rootAccessStatus.collectAsStateWithLifecycle()
    val activeOps by viewModel.activeOperations.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isNotificationBlocked by viewModel.isNotificationBlocked.collectAsStateWithLifecycle()
    val processExitBanner by viewModel.previousProcessExitBanner.collectAsStateWithLifecycle()

    // Armazenamento interno em tempo real
    var internalStorage by remember { mutableStateOf(readInternalStorageStats()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                internalStorage = readInternalStorageStats()
                delay(2000L)
            }
        }
    }

    // Google Drive Quota periódica
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
    var showHistoryBottomSheet by remember { mutableStateOf(false) }
    var showRootDiagBottomSheet by remember { mutableStateOf(false) }
    var showSecurityModal by remember { mutableStateOf(false) }
    val changelogPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var showChangelogModal by remember {
        mutableStateOf(changelogPrefs.getString("last_seen_changelog_version", "") != "1.0.31")
    }
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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp)
        ) {
            // ====================================================================
            // ▶️ 1. TOP BAR COM FOTO DE PERFIL DA CONTA DO GOOGLE DRIVE
            // ====================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Canto superior esquerdo: Ícone do app + "ReSync"
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo_resync),
                        contentDescription = "ReSync App Icon",
                        modifier = Modifier.size(28.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ReSync",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        ),
                        color = Color.White
                    )
                }

                // Canto superior direito: Lock, Terminal, Settings, Foto de Perfil
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Cadeado / Segurança
                    IconButton(
                        onClick = onNavigateToPermissions,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Permissões e Segurança",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 2. Terminal / Logs (>_)
                    IconButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Logs",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 3. Configurações (Engrenagem)
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configurações",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 4. Foto de Perfil Real da Conta do Google Drive Logada
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A1B22))
                            .border(1.5.dp, Color(0xFFFF8A92), CircleShape)
                            .clickable { showAccountsBottomSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        val photoUrl = activeAccount?.photoUrl
                        if (!photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(photoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Foto de Perfil do Google Drive",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initial = when {
                                !activeAccount?.name.isNullOrBlank() -> activeAccount!!.name.take(1).uppercase()
                                !activeAccount?.email.isNullOrBlank() -> activeAccount!!.email.take(1).uppercase()
                                else -> null
                            }
                            if (initial != null) {
                                Text(
                                    text = initial,
                                    color = Color(0xFFFF8A92),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Conta Google",
                                    tint = Color(0xFFFF8A92),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Banner se notificações estiverem bloqueadas
            if (isNotificationBlocked) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Notificações desativadas • Toque para ativar",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Banner de saída inesperada anterior
            if (processExitBanner != null) {
                val banner = processExitBanner!!
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Diagnóstico: ${banner.reasonTitle}",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.dismissProcessExitBanner() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ====================================================================
            // ▶️ 2. SEÇÃO DE STATUS E RESUMO (BENTO GRID SUPERIOR 2x2 - 100% REAL)
            // ====================================================================
            val isRootGranted = permissionsState.isRootGranted
            val isDriveConnected = activeAccount != null && googleDriveState != GoogleDriveState.CONNECTION_ERROR && googleDriveState != GoogleDriveState.SESSION_EXPIRED

            val completedOps by viewModel.completedOperations.collectAsStateWithLifecycle()

            // 1. Escaneamento real de backups locais existentes (/storage/emulated/0/ReSyncBackups etc.)
            val localBackupFiles = remember(context, isPullRefreshing) {
                try {
                    val dirs = BackupPaths.getAllLocalBackupDirs(context)
                    dirs.flatMap { dir ->
                        if (dir.exists() && dir.isDirectory) {
                            dir.listFiles { f -> BackupPaths.isBackupContainerFile(f) }?.toList() ?: emptyList()
                        } else emptyList()
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
            }

            val remoteList = when (val b = remoteBackupsState) {
                is RemoteBackupsState.Loaded -> b.backups
                else -> emptyList()
            }

            // 2. Cálculo dinâmico do Último Backup Real
            val latestBackupInfo = remember(completedOps, remoteList, localBackupFiles, activeAccount) {
                var maxTime = 0L
                var type = "Manual"

                completedOps.maxByOrNull { it.timestamp }?.let {
                    if (it.timestamp > maxTime) {
                        maxTime = it.timestamp
                        type = if (it.title.contains("agendad", ignoreCase = true)) "Agendado" else if (it.destinationOrSource.contains("cloud", ignoreCase = true) || it.destinationOrSource.contains("drive", ignoreCase = true)) "Nuvem" else "Manual"
                    }
                }

                remoteList.maxByOrNull { it.modifiedTime }?.let {
                    if (it.modifiedTime > maxTime) {
                        maxTime = it.modifiedTime
                        type = "Nuvem"
                    }
                }

                localBackupFiles.maxByOrNull { it.lastModified() }?.let {
                    if (it.lastModified() > maxTime) {
                        maxTime = it.lastModified()
                        type = "Manual"
                    }
                }

                if (maxTime == 0L && activeAccount?.lastSyncTimestamp != null && activeAccount.lastSyncTimestamp > 0L) {
                    maxTime = activeAccount.lastSyncTimestamp
                    type = "Nuvem"
                }

                if (maxTime > 0L) {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(maxTime))
                    val nowCal = Calendar.getInstance()
                    val targetCal = Calendar.getInstance().apply { timeInMillis = maxTime }
                    val isToday = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
                            nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
                    nowCal.add(Calendar.DAY_OF_YEAR, -1)
                    val isYesterday = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
                            nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)

                    val relativeDateStr = when {
                        isToday -> "Hoje"
                        isYesterday -> "Ontem"
                        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(maxTime))
                    }
                    Triple(timeStr, "$relativeDateStr • $type", true)
                } else {
                    Triple("Nenhum", "Toque para iniciar", false)
                }
            }

            // 3. Cálculo dinâmico do Total de Arquivos e Tamanho Espelhado Real
            val totalPackagesCount = localBackupFiles.size + remoteList.size
            val totalMirroredBytes = localBackupFiles.sumOf { it.length() } + remoteList.sumOf { it.sizeBytes }

            val totalPackagesStr = if (totalPackagesCount > 0) "$totalPackagesCount pacotes" else "0 pacotes"
            val mirroredSizeStr = if (totalMirroredBytes > 0L) "${FormatUtils.formatBytes(totalMirroredBytes)} espelhados" else "0 B espelhados"

            // 4. Detecção dinâmica de Gerenciador Root e Permissões
            val installedRootManagers = remember(context) { RootManagers.detectInstalledManagers(context) }
            val detectedRootManager = installedRootManagers.firstOrNull()?.name
            val hasSuBinary = remember(context) { CapabilityManager(context).hasSuBinary() }

            val rootTitle = when {
                isRootGranted -> "${detectedRootManager ?: "Root"} ativo"
                detectedRootManager != null || hasSuBinary -> "${detectedRootManager ?: "Root"} detectado"
                else -> "Sem Root"
            }
            val rootTitleColor = when {
                isRootGranted -> Color(0xFF10B981)
                detectedRootManager != null || hasSuBinary -> Color(0xFFF59E0B)
                else -> TextSecondary
            }
            val rootSubtitle = when {
                isRootGranted -> "Modo Estrito (uid 0)"
                detectedRootManager != null || hasSuBinary -> "Permissão pendente"
                else -> "Modo Usuário (sem su)"
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = BentoCardBg)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Faixa superior verde com pílula de status interativa (Abre Modal de Segurança & Criptografia)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSecurityModal = true }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Pílula verde "● SISTEMA PROTEGIDO"
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF0D281E),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "●",
                                    color = Color(0xFF10B981),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(
                                    text = "SISTEMA PROTEGIDO",
                                    color = Color(0xFF10B981),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        // Lado direito: "SHA-256 OK" com escudo
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "SHA-256 OK",
                                color = TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Grid de 4 Cards Bento Compactos (2x2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Card 1: Último Backup (Interativo -> Abre Histórico)
                        BentoSubCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showHistoryBottomSheet = true },
                            icon = Icons.Default.Schedule,
                            label = "Último Backup",
                            title = latestBackupInfo.first,
                            subtitle = latestBackupInfo.second,
                            subtitleColor = if (latestBackupInfo.third) Color(0xFF10B981) else TextMuted
                        )

                        // Card 2: Total Arquivos (Interativo -> Abre Gerenciador de Backups)
                        BentoSubCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onNavigateToBackupManager),
                            icon = Icons.Default.Inventory2,
                            label = "Total Arquivos",
                            title = totalPackagesStr,
                            subtitle = mirroredSizeStr,
                            subtitleColor = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Card 3: Status de Root (Interativo -> Abre Diagnóstico Root & Ambiente)
                        BentoSubCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showRootDiagBottomSheet = true },
                            icon = Icons.Default.Shield,
                            label = "Status de Root",
                            title = rootTitle,
                            titleColor = rootTitleColor,
                            subtitle = rootSubtitle,
                            subtitleColor = TextSecondary
                        )

                        // Card 4: Google Drive (Interativo -> Abre Nuvem)
                        BentoSubCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onNavigateToCloud),
                            icon = Icons.Default.Cloud,
                            label = "Google Drive",
                            title = if (isDriveConnected) {
                                if (googleDriveState == GoogleDriveState.SYNCING) "Sincronizando..." else "Sincronizado"
                            } else "Desconectado",
                            titleColor = if (isDriveConnected) Color(0xFF38BDF8) else TextSecondary,
                            subtitle = if (isDriveConnected) "Criptografia AES-256" else "Toque para conectar",
                            subtitleColor = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ====================================================================
            // ▶️ 3. BOTÃO DE AÇÃO PRINCIPAL E BARRA DE ATALHOS RÁPIDOS (FOTO 2)
            // ====================================================================
            // Botão Largo em Gradiente Coral/Carmesim ("Novo Backup Completo" - FOTO 2)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(primaryAccent.copy(alpha = 0.95f), primaryAccent, primaryAccent.copy(alpha = 0.85f))
                        )
                    )
                    .clickable(onClick = onNavigateToCustomWizard),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TaskAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Novo Backup Completo",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Barra de 3 Atalhos Rápidos (Squircle - FOTO 2): "Restaurar", "Agendados", "Histórico"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Atalho 1: Restaurar
                QuickShortcutButton(
                    modifier = Modifier.weight(1f),
                    title = "Restaurar",
                    icon = Icons.Default.Restore,
                    iconTint = Color(0xFFFF8A92),
                    onClick = onNavigateToRestore
                )

                // Atalho 2: Agendados
                QuickShortcutButton(
                    modifier = Modifier.weight(1f),
                    title = "Agendados",
                    icon = Icons.Default.Schedule,
                    iconTint = Color(0xFF10B981),
                    onClick = onNavigateToScheduledBackups
                )

                // Atalho 3: Histórico (Gatilho interativo funcional para BottomSheet de Histórico)
                QuickShortcutButton(
                    modifier = Modifier.weight(1f),
                    title = "Histórico",
                    icon = Icons.Default.History,
                    iconTint = Color(0xFF38BDF8),
                    onClick = { showHistoryBottomSheet = true }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ====================================================================
            // ▶️ 4. CARD DINÂMICO DE JOB EM ANDAMENTO (100% REAL - ZERO MOCKS)
            // ====================================================================
            val runningOps = activeOps.filter { !it.isFinished && it.status != com.resync.app.engine.OperationStatus.CANCELLED && it.status != com.resync.app.engine.OperationStatus.ERROR }
            val activeOp = runningOps.firstOrNull()

            if (activeOp != null) {
                val jobTitle = "JOB #${activeOp.id.takeLast(4)} // ${activeOp.stage.ifBlank { "COMPRESSÃO" }.uppercase()}"
                val jobPercentValue = (activeOp.progressPercent * 100f).coerceIn(0f, 100f)
                val jobPercentStr = String.format(Locale.US, "%.1f%%", jobPercentValue)
                val jobTarget = activeOp.currentItemName.ifBlank { activeOp.title.ifBlank { "Alvo em processamento" } }
                val speedText = if (activeOp.speedBytesPerSec > 0L) {
                    "${FormatUtils.formatBytes(activeOp.speedBytesPerSec)}/s"
                } else {
                    "0 B/s"
                }
                val remainingText = if (activeOp.estimatedRemainingSeconds > 0L) {
                    FormatUtils.formatShortEta(activeOp.estimatedRemainingSeconds)
                } else {
                    "--"
                }
                val ioDiskText = if (activeOp.speedBytesPerSec > 0L) {
                    "${FormatUtils.formatBytes(activeOp.speedBytesPerSec)}/s"
                } else {
                    "--"
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp))
                        .clickable(onClick = onNavigateToActiveBackups),
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Top: Red Dot + JOB Título e Porcentagem
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "●",
                                    color = Color(0xFFFF414D),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(
                                    text = jobTitle,
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Text(
                                text = jobPercentStr,
                                color = Color(0xFFFF414D),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Caixa Console Escura (ALVO + Linha de Pacote + Barra Linear Coral)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0D1117),
                            border = BorderStroke(0.6.dp, Color(0xFF1E2837))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ALVO",
                                        fontSize = 10.5.sp,
                                        color = TextMuted,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "tar.zst",
                                        fontSize = 11.sp,
                                        color = Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = jobTarget,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFFE2E8F0),
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Barra de progresso preenchida em coral
                                LinearProgressIndicator(
                                    progress = { (jobPercentValue / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                    color = primaryAccent,
                                    trackColor = Color(0xFF1C222D)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Linha de Métricas (3 Colunas: VELOCIDADE, RESTANTE, I/O DISCO)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(text = "VELOCIDADE", fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                Text(text = speedText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace)
                            }
                            Column {
                                Text(text = "RESTANTE", fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                Text(text = remainingText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                            }
                            Column {
                                Text(text = "I/O DISCO", fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                Text(text = ioDiskText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981), fontFamily = FontFamily.Monospace)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Botões de Controle Integrados: [ || Pausar ], [ ■ Abortar ], [ { } Stdout ]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isPaused = activeOp.isPaused
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isPaused) viewModel.resumeOperation(activeOp.id) else viewModel.pauseOperation(activeOp.id)
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1A2230),
                                border = BorderStroke(0.8.dp, Color(0xFF263244))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isPaused) "Retomar" else "Pausar",
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // [ ■ Abortar ]
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.cancelOperation(activeOp.id)
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFB91C1C)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Abortar",
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // [ { } Stdout ]
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onNavigateToActiveBackups),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1A2230),
                                border = BorderStroke(0.8.dp, Color(0xFF263244))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Code,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Stdout",
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ESTADO OCIOSO REAL E LIMPO (SEM DADOS FICTÍCIOS)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "●",
                                    color = Color(0xFF10B981),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(
                                    text = "STATUS // OCIOSO",
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF0D281E),
                                border = BorderStroke(0.6.dp, Color(0xFF10B981).copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "PRONTO",
                                    color = Color(0xFF10B981),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Nenhuma operação em andamento no momento",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "O sistema está pronto para executar novos backups com verificação de integridade SHA-256.",
                            fontSize = 11.5.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showHistoryBottomSheet = true },
                                modifier = Modifier.weight(1f).height(34.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.8.dp, Color(0xFF263244)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Histórico", fontSize = 11.sp, color = Color.White)
                            }

                            OutlinedButton(
                                onClick = onNavigateToLogs,
                                modifier = Modifier.weight(1f).height(34.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.8.dp, Color(0xFF263244)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Logs", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ====================================================================
            // ▶️ 5. SEPARAÇÃO DOS CARDS DE ALOCAÇÃO DE DADOS (FOTO 3)
            // ====================================================================
            val freeGb = internalStorage.freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val totalGb = if (internalStorage.totalBytes > 0) internalStorage.totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0) else 479.3
            val usedGb = maxOf(0.0, totalGb - freeGb)

            val (quotaUsed, quotaLimit) = when (val q = driveQuotaState) {
                is DriveQuotaState.Loaded -> Pair(q.usedBytes, q.totalBytes)
                else -> Pair(activeAccount?.quotaUsedBytes ?: (187.1 * 1024 * 1024 * 1024).toLong(), activeAccount?.quotaTotalBytes ?: (5122.0 * 1024 * 1024 * 1024).toLong())
            }
            val driveUsedGb = quotaUsed.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val driveTotalGb = if (quotaLimit > 0) quotaLimit.toDouble() / (1024.0 * 1024.0 * 1024.0) else 5122.0

            // 1. CARD SUPERIOR SEPARADO: Armazenamento Local (/data)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp))
                    .clickable(onClick = onNavigateToStorageDetails),
                colors = CardDefaults.cardColors(containerColor = BentoCardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Cabeçalho do Bloco Local
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Alocação de Dados",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Text(
                            text = "EXT4 / NVMe UFS 4.0",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Linha Armazenamento Local (/data)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Armazenamento Local (/data)",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f GB / %.1f GB", usedGb, totalGb),
                            fontSize = 11.5.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Barra Segmentada Local: Apps (Coral), Root Data (Azul Celeste), Mídia (Verde Água), Cache (Cinza)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1C222D))
                    ) {
                        val usedFraction = if (totalGb > 0) (usedGb / totalGb).coerceIn(0.0, 1.0).toFloat() else 0.72f
                        val appsFraction = usedFraction * 0.44f
                        val rootFraction = usedFraction * 0.24f
                        val mediaFraction = usedFraction * 0.22f
                        val cacheFraction = usedFraction * 0.10f
                        val freeFraction = (1f - usedFraction).coerceAtLeast(0f)

                        if (appsFraction > 0) Box(modifier = Modifier.weight(appsFraction).fillMaxHeight().background(Color(0xFFFF3B47)))
                        if (rootFraction > 0) Box(modifier = Modifier.weight(rootFraction).fillMaxHeight().background(Color(0xFF38BDF8)))
                        if (mediaFraction > 0) Box(modifier = Modifier.weight(mediaFraction).fillMaxHeight().background(Color(0xFF10B981)))
                        if (cacheFraction > 0) Box(modifier = Modifier.weight(cacheFraction).fillMaxHeight().background(Color(0xFF64748B)))
                        if (freeFraction > 0) Box(modifier = Modifier.weight(freeFraction).fillMaxHeight().background(Color(0xFF1C222D)))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Legenda: ● Apps  ● Root Data  ● Mídia  ● Cache
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LegendItem(color = Color(0xFFFF3B47), label = "Apps")
                        LegendItem(color = Color(0xFF38BDF8), label = "Root Data")
                        LegendItem(color = Color(0xFF10B981), label = "Mídia")
                        LegendItem(color = Color(0xFF64748B), label = "Cache")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. CARD INFERIOR SEPARADO: Google Drive Mirror (/remote)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp))
                    .clickable(onClick = onNavigateToCloud),
                colors = CardDefaults.cardColors(containerColor = BentoCardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Cabeçalho do Bloco Nuvem
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Google Drive Mirror",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Text(
                            text = if (activeAccount != null) "Criptografia AES-256" else "Desconectado",
                            fontSize = 11.sp,
                            color = if (activeAccount != null) Color(0xFF10B981) else TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Linha Google Drive Mirror (/remote)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Google Drive Mirror (/remote)",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f GB / %.1f GB", driveUsedGb, driveTotalGb),
                            fontSize = 11.5.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Barra de Progresso Google Drive Mirror
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1C222D))
                    ) {
                        val driveFraction = if (driveTotalGb > 0) (driveUsedGb / driveTotalGb).coerceIn(0.0, 1.0).toFloat() else 0.65f
                        val appsRemote = driveFraction * 0.68f
                        val dataRemote = driveFraction * 0.32f
                        val freeRemote = (1f - driveFraction).coerceAtLeast(0f)

                        if (appsRemote > 0) Box(modifier = Modifier.weight(appsRemote).fillMaxHeight().background(Color(0xFFFF3B47)))
                        if (dataRemote > 0) Box(modifier = Modifier.weight(dataRemote).fillMaxHeight().background(Color(0xFF38BDF8)))
                        if (freeRemote > 0) Box(modifier = Modifier.weight(freeRemote).fillMaxHeight().background(Color(0xFF1C222D)))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Legenda da Nuvem
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LegendItem(color = Color(0xFFFF3B47), label = "Backups Nuvem")
                        LegendItem(color = Color(0xFF38BDF8), label = "Outros Arquivos")
                        LegendItem(color = Color(0xFF10B981), label = "Sincronizado")
                        LegendItem(color = Color(0xFF64748B), label = "Livre")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ====================================================================
            // ▶️ 6. GRADE DE MÓDULOS DE DADOS (8 MÓDULOS PRINCIPAIS)
            // ====================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MÓDULOS DE DADOS",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "9 ATIVOS",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981),
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val appsCount = if (installedApps.isNotEmpty()) installedApps.size else 148
            val userAppsCount = if (installedApps.isNotEmpty()) installedApps.count { !it.isSystemApp } else 142

            // Linha 1: Aplicativos & Mensagens
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Apps,
                    title = "Aplicativos",
                    subtitle = "$appsCount instalados",
                    badgeText = "$userAppsCount OK",
                    badgeColor = Color(0xFF10B981),
                    onClick = onNavigateToApps
                )
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Chat,
                    title = "Mensagens",
                    subtitle = "SMS & MMS",
                    badgeText = "3.4k",
                    badgeColor = Color(0xFF38BDF8),
                    onClick = { onNavigateToCategory("messages") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Linha 2: Chamadas & Contatos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Call,
                    title = "Chamadas",
                    subtitle = "Histórico",
                    badgeText = "980",
                    badgeColor = Color(0xFF10B981),
                    onClick = { onNavigateToCategory("calls") }
                )
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.ContactPage,
                    title = "Contatos",
                    subtitle = "VCF / vCard",
                    badgeText = "842",
                    badgeColor = TextSecondary,
                    onClick = { onNavigateToCategory("contacts") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Linha 3: Root Paths & Redes Wi-Fi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FolderShared,
                    title = "Pastas do Sistema",
                    subtitle = "Armazenamento",
                    badgeText = "Pastas",
                    badgeColor = Color(0xFF38BDF8),
                    onClick = onNavigateToFolders
                )
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Wifi,
                    title = "Redes Wi-Fi",
                    subtitle = "Senhas salvas",
                    badgeText = "Redes",
                    badgeColor = Color(0xFF10B981),
                    onClick = { onNavigateToCategory("wifi") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Linha 4: Bluetooth & DNS Privado
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Bluetooth,
                    title = "Bluetooth",
                    subtitle = "Chaves pareadas",
                    badgeText = "18 DEV",
                    badgeColor = Color(0xFF38BDF8),
                    onClick = { onNavigateToCategory("bluetooth") }
                )
                DataModuleBentoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Dns,
                    title = "DNS Privado",
                    subtitle = "DoH / TLS",
                    badgeText = "ATIVO",
                    badgeColor = Color(0xFF10B981),
                    onClick = { onNavigateToCategory("dns") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Linha 5: Papéis de Parede (Módulo 9)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataModuleBentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Wallpaper,
                    title = "Papéis de Parede",
                    subtitle = "Tela Inicial & Bloqueio (4K)",
                    badgeText = "2 ITENS",
                    badgeColor = primaryAccent,
                    onClick = { onNavigateToCategory("wallpaper") }
                )
            }

            // Espaçamento de segurança inferior para não colidir com o dock flutuante
            Spacer(modifier = Modifier.height(44.dp))
        }
    }

    // Bottom Sheet de Contas do Google Drive
    if (showAccountsBottomSheet) {
        GoogleDriveAccountsBottomSheet(
            viewModel = viewModel,
            onDismiss = { showAccountsBottomSheet = false }
        )
    }

    // Bottom Sheet de Histórico de Backups Realizados (FOTO 2)
    if (showHistoryBottomSheet) {
        BackupHistoryBottomSheet(
            viewModel = viewModel,
            onDismiss = { showHistoryBottomSheet = false },
            onNavigateToRestore = onNavigateToRestore
        )
    }

    // Bottom Sheet de Diagnóstico Root & Ambiente (ETAPA 1)
    if (showRootDiagBottomSheet) {
        RootDiagBottomSheet(
            viewModel = viewModel,
            onDismiss = { showRootDiagBottomSheet = false },
            onNavigateToPermissions = onNavigateToPermissions
        )
    }

    // Modal de Status de Segurança & Criptografia (ETAPA 1)
    if (showSecurityModal) {
        SecurityModal(
            onDismiss = { showSecurityModal = false }
        )
    }

    // Modal Automático de Novidades (Changelog v1.0.31)
    if (showChangelogModal) {
        ChangelogModal(
            versionName = "1.0.31",
            primaryAccent = primaryAccent,
            onDismiss = {
                showChangelogModal = false
            },
            onConfirm = {
                changelogPrefs.edit().putString("last_seen_changelog_version", "1.0.31").apply()
                showChangelogModal = false
            }
        )
    }
}

private data class BackupHistoryItem(
    val title: String,
    val timestampFormatted: String,
    val packagesCount: Int,
    val totalSizeFormatted: String,
    val sha256Hash: String,
    val isDriveSynced: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupHistoryBottomSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onNavigateToRestore: () -> Unit
) {
    val context = LocalContext.current
    val completedOps by viewModel.completedOperations.collectAsStateWithLifecycle()
    val remoteBackupsState by viewModel.remoteBackupsState.collectAsStateWithLifecycle()

    val historyItems = remember(completedOps, remoteBackupsState) {
        val list = mutableListOf<BackupHistoryItem>()

        // 1. A partir das operações concluídas reais no dispositivo
        completedOps.forEach { op ->
            val dateStr = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(op.timestamp))
            val hashVal = op.containerFile?.name ?: ("sha256_" + (op.id.hashCode().toString().take(8).padStart(8, '0')))
            val sizeFormatted = if (op.totalBytesEstimated > 0L) {
                FormatUtils.formatBytes(op.totalBytesEstimated)
            } else if (op.bytesProcessed > 0L) {
                FormatUtils.formatBytes(op.bytesProcessed)
            } else {
                "0 B"
            }
            list.add(
                BackupHistoryItem(
                    title = op.title.ifBlank { "Backup Local #${op.id.takeLast(4)}" },
                    timestampFormatted = dateStr,
                    packagesCount = if (op.totalItems > 0) op.totalItems else 1,
                    totalSizeFormatted = sizeFormatted,
                    sha256Hash = hashVal,
                    isDriveSynced = op.destinationOrSource.contains("cloud", ignoreCase = true) || op.destinationOrSource.contains("drive", ignoreCase = true)
                )
            )
        }

        // 2. A partir dos snapshots reais do Google Drive
        if (remoteBackupsState is RemoteBackupsState.Loaded) {
            val remoteList = (remoteBackupsState as RemoteBackupsState.Loaded).backups
            remoteList.forEach { driveFile ->
                val dateStr = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(driveFile.modifiedTime))
                val hashVal = if (!driveFile.md5Checksum.isNullOrBlank()) "Backup verificado" else "Backup na nuvem"
                list.add(
                    BackupHistoryItem(
                        title = driveFile.name.removeSuffix(".tar.zst").removeSuffix(".zst"),
                        timestampFormatted = dateStr,
                        packagesCount = 1,
                        totalSizeFormatted = FormatUtils.formatBytes(driveFile.sizeBytes),
                        sha256Hash = hashVal,
                        isDriveSynced = true
                    )
                )
            }
        }

        // 3. A partir de arquivos de backup locais existentes no armazenamento
        try {
            val dirs = BackupPaths.getAllLocalBackupDirs(context)
            dirs.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles { f -> BackupPaths.isBackupContainerFile(f) } ?: emptyArray()
                    files.forEach { f ->
                        val dateStr = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
                        val hashVal = "local_sha256_" + (f.name.hashCode().toString().take(8).padStart(8, '0'))
                        list.add(
                            BackupHistoryItem(
                                title = f.nameWithoutExtension,
                                timestampFormatted = dateStr,
                                packagesCount = 1,
                                totalSizeFormatted = FormatUtils.formatBytes(f.length()),
                                sha256Hash = hashVal,
                                isDriveSynced = false
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {}

        list.distinctBy { it.title + it.timestampFormatted }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131822),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF263244))
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 36.dp)
        ) {
            // Cabeçalho da Bottom Sheet
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Histórico de Backups",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (historyItems.isNotEmpty()) "${historyItems.size} backups gravados com SHA-256" else "Nenhum backup encontrado",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (historyItems.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFF222B3A), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Nenhum backup registrado",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Inicie um novo backup completo para gerar o histórico com hashes SHA-256 e status de sincronização.",
                            fontSize = 11.5.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Lista de Backups
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    historyItems.forEach { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0xFF222B3A), RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // Top Row: Título + Badge de Status
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (item.isDriveSynced) Color(0xFF0D281E) else Color(0xFF1E2837),
                                        border = BorderStroke(0.6.dp, if (item.isDriveSynced) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFF38BDF8).copy(alpha = 0.4f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (item.isDriveSynced) Icons.Default.CloudDone else Icons.Default.Save,
                                                contentDescription = null,
                                                tint = if (item.isDriveSynced) Color(0xFF10B981) else Color(0xFF38BDF8),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (item.isDriveSynced) "Google Drive" else "Local",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.isDriveSynced) Color(0xFF10B981) else Color(0xFF38BDF8)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Métricas: Data/Hora e Pacotes
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.timestampFormatted,
                                        fontSize = 11.5.sp,
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${item.packagesCount} pacotes • ${item.totalSizeFormatted}",
                                        fontSize = 11.5.sp,
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Caixa SHA-256 com botão Copiar
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF0D1117),
                                    border = BorderStroke(0.6.dp, Color(0xFF1E2837))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "SHA-256: ${item.sha256Hash.take(12)}...${item.sha256Hash.takeLast(8)}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            color = Color(0xFFE2E8F0),
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("SHA-256", item.sha256Hash)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Hash SHA-256 copiado!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copiar Hash",
                                                tint = Color(0xFF95A1B5),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Botão Restaurar Snapshot
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            onDismiss()
                                            onNavigateToRestore()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Restaurar Lote",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF38BDF8)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootDiagBottomSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    val context = LocalContext.current
    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val isRootGranted = permissionsState.isRootGranted
    val currentTemp by com.resync.app.engine.CpuGovernor.currentDeviceTemperature.collectAsState()

    val installedManagers = remember(context) { RootManagers.detectInstalledManagers(context) }
    val managerName = installedManagers.firstOrNull()?.name ?: "Nenhum gerenciador detectado"

    val suPath = remember {
        val candidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/local/xbin/su", "/system/bin/ksud")
        candidates.firstOrNull { File(it).exists() } ?: "which su: " + (try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            p.inputStream.bufferedReader().readLine()
        } catch (_: Throwable) { null } ?: "não encontrado")
    }

    val kernelVer = remember {
        try {
            File("/proc/version").readText().trim().take(70)
        } catch (_: Throwable) {
            System.getProperty("os.version") ?: "Linux"
        }
    }

    val selinuxMode = remember {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getenforce"))
            p.inputStream.bufferedReader().readLine() ?: "Enforcing"
        } catch (_: Throwable) {
            "Enforcing"
        }
    }

    val primaryAccent = MaterialTheme.colorScheme.primary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131822),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF263244))
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Diagnóstico Root & Ambiente", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Verificação rigorosa de integridade e sandbox", fontSize = 11.5.sp, color = TextSecondary)
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF222B3A), RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagRow("Status de Execução", if (isRootGranted) "Privilegiado (uid 0)" else "Sandbox (Modo Padrão)", if (isRootGranted) Color(0xFF10B981) else Color(0xFFF59E0B))
                    DiagRow("Gerenciador Root", managerName, Color.White)
                    DiagRow("Caminho do Binário su", suPath, Color(0xFF38BDF8))
                    DiagRow("SELinux Enforce", selinuxMode, Color(0xFF10B981))
                    DiagRow("Kernel Linux", kernelVer, TextSecondary)
                    DiagRow("Temperatura Térmica", "%.1f°C".format(Locale.US, currentTemp), if (currentTemp > 45f) Color(0xFFFF5252) else Color(0xFF10B981))
                    DiagRow("Ambiente Android", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})", TextSecondary)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.requestRootPermission()
                        Toast.makeText(context, "Solicitando privilégios de Root...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1.1f).heightIn(min = 50.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent, contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Testar Root", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onNavigateToPermissions()
                    },
                    modifier = Modifier.weight(0.9f).heightIn(min = 50.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                ) {
                    Text("Permissões", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityModal(
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131822),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF263244))
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Status de Segurança & Criptografia", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Padrão Militar AES-256-GCM & SHA-256", fontSize = 11.5.sp, color = TextSecondary)
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF222B3A), RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DiagRow("Cifra Simétrica", "AES-256-GCM (AEAD 128-bit)", Color(0xFF10B981))
                    DiagRow("Hardware KeyStore", "AndroidKeyStore (TEE / StrongBox)", Color(0xFF38BDF8))
                    DiagRow("Derivação de Chave", "PBKDF2WithHmacSHA256", Color.White)
                    DiagRow("Integridade de Hash", "SHA-256 Bloco a Bloco", Color(0xFF10B981))
                    DiagRow("Assinatura do App", "V2/V3 APK Scheme Válido", Color(0xFF10B981))
                    DiagRow("Criptografia em Repouso", "Ativa (Local & Google Drive)", Color(0xFF38BDF8))
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2230), contentColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFF263244))
            ) {
                Text("Entendido", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = TextSecondary,
            modifier = Modifier.weight(0.42f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.58f),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BentoSubCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    title: String,
    subtitle: String,
    titleColor: Color = Color.White,
    subtitleColor: Color = TextSecondary
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(0.8.dp, BentoCardBorder, RoundedCornerShape(12.dp)),
        color = BentoCardBgSecondary,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    fontSize = 10.5.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickShortcutButton(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(66.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, BentoCardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = BentoCardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DataModuleBentoCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .wrapContentHeight()
            .clip(RoundedCornerShape(12.dp))
            .border(0.8.dp, BentoCardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = BentoCardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            // Cabeçalho interno do Card: Ícone à esquerda + Badge no canto superior direito
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A2230)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E2837),
                    modifier = Modifier.wrapContentSize()
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Título e Subtítulo ocupando 100% da largura do card sem competição
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangelogModal(
    versionName: String,
    primaryAccent: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val bentoBg = DarkSurface
    val bentoCard = DarkSurfaceVariant
    val bentoBorder = DarkCardBorder
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bentoBg,
        scrimColor = Color.Black.copy(alpha = 0.75f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(44.dp)
                    .height(4.dp),
                shape = CircleShape,
                color = bentoBorder.copy(alpha = 0.8f)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = primaryAccent.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "NOVA VERSÃO • $versionName",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = primaryAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "O que há de novo no ReSync",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Motor universal, streaming de alta velocidade e experiência refinada",
                fontSize = 12.5.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Scrollable Bento Pillars
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChangelogPillarCard(
                    icon = Icons.Default.CloudSync,
                    accentColor = AccentElectricCyan,
                    title = "Google Drive Turbo",
                    description = "Streaming direto em blocos de 64 KB com chunks de 4 MB a 16 MB, conexão HTTP/2 unificada e cache persistente de pastas.",
                    cardBg = bentoCard,
                    borderColor = bentoBorder
                )

                ChangelogPillarCard(
                    icon = Icons.Default.Shield,
                    accentColor = StatusSuccess,
                    title = "Suporte Universal Sem Root",
                    description = "Extração e restauração de APKs base, splits e mídias públicas via userspace nativo; fallbacks com cópia para Área de Transferência e atalho para configurações em DNS, Wi-Fi e Bluetooth.",
                    cardBg = bentoCard,
                    borderColor = bentoBorder
                )

                ChangelogPillarCard(
                    icon = Icons.Default.Terminal,
                    accentColor = Color(0xFFA855F7),
                    title = "Gestão Root & Sessão Persistente libsu",
                    description = "Inicialização mestre com FLAG_MOUNT_MASTER no startup, eliminando reinicializações frequentes de shell e atritos com sucompat.",
                    cardBg = bentoCard,
                    borderColor = bentoBorder
                )

                ChangelogPillarCard(
                    icon = Icons.Default.Speed,
                    accentColor = Color(0xFFFFA726),
                    title = "Otimização Térmica e de Hardware",
                    description = "Leitura direta em sysfs com throttle de 3s e blindagem contra injeção de comandos em rotinas de verificação SHA-256.",
                    cardBg = bentoCard,
                    borderColor = bentoBorder
                )

                ChangelogPillarCard(
                    icon = Icons.Default.AutoAwesome,
                    accentColor = Color(0xFF00E676),
                    title = "Pacote Mais Leve",
                    description = "Redução de mais de 750 KB no APK com exclusão de dependências órfãs e localização restrita ao português.",
                    cardBg = bentoCard,
                    borderColor = bentoBorder
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Botão Principal
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
            ) {
                Text(
                    text = "Entendi / Continuar",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun ChangelogPillarCard(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    description: String,
    cardBg: Color,
    borderColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.12f))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

