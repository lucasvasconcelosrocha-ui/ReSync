package com.resync.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.data.model.BackupItemType
import com.resync.app.util.FormatUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.R
import com.resync.app.engine.OperationStatus
import com.resync.app.engine.OperationType
import com.resync.app.engine.Operacao
import com.resync.app.engine.OperationPartInfo
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.ui.components.CompletionAnimation
import com.resync.app.ui.components.CompletionAnimationSize
import com.resync.app.ui.components.SmoothBatchCounterText
import com.resync.app.ui.components.SmoothCurrentItemText
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.ui.components.SmoothPercentText
import com.resync.app.ui.components.SmoothStageText
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveBackupsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
    fromJustStarted: Boolean = false
) {
    val operations by viewModel.activeOperations.collectAsStateWithLifecycle()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val activeCount = operations.count { !it.isFinished && it.status != OperationStatus.CANCELLED && it.status != OperationStatus.ERROR }
    var showMenu by remember { mutableStateOf(false) }

    val currentTemp by com.resync.app.engine.CpuGovernor.currentDeviceTemperature.collectAsState()
    val isThrottled by com.resync.app.engine.CpuGovernor.isThermalThrottled.collectAsState()

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(top = 10.dp, bottom = 6.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = StandardCardBg,
                    border = BorderStroke(1.dp, StandardCardBorder),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.active_backups_title),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (activeCount == 1) "1 operação ativa" else "$activeCount operações ativas",
                                    fontSize = 11.5.sp,
                                    color = if (activeCount > 0) primaryAccent else TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "•  %.1f °C".format(Locale.US, currentTemp),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isThrottled || currentTemp >= 40f) StatusWarning else TextSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimizar", tint = primaryAccent)
                        }
                        IconButton(
                            onClick = onNavigateToLogs,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = "Logs", tint = TextPrimary)
                        }
                        Box {
                            IconButton(
                                onClick = { showMenu = !showMenu },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextPrimary)
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ver Registros de Logs") },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToLogs()
                                    }
                                )
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (fromJustStarted) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = primaryAccent.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, primaryAccent.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Backup iniciado com sucesso",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Acompanhe o progresso em tempo real ou minimize para segundo plano.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
            val distinctOps = operations.distinctBy { it.id }
            if (distinctOps.isNotEmpty()) {
                distinctOps.forEach { op ->
                    key(op.id) {
                        if (!op.isFinished) {
                            ActiveOperationCard(
                                op = op,
                                primaryAccent = primaryAccent,
                                onPause = { viewModel.pauseOperation(op.id) },
                                onResume = { viewModel.resumeOperation(op.id) },
                                onCancel = { viewModel.cancelOperation(op.id) },
                                onMinimize = onNavigateBack
                            )
                        } else {
                            FinishedOperationCard(
                                op = op,
                                primaryAccent = primaryAccent,
                                onDismiss = { viewModel.dismissOperation(op.id) }
                            )
                        }
                    }
                }
            } else {
                // Estado Vazio: Nenhuma operação em execução no momento
                Spacer(modifier = Modifier.height(60.dp))
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PendingActions,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(42.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.active_backups_none),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.active_backups_empty_desc),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    lineHeight = 18.sp,
                    maxLines = 3
                )
            }
        }
    }
}

private fun getOperationCategoryLabel(type: OperationType, itemType: BackupItemType): String {
    val prefix = if (type == OperationType.BACKUP) "Backup" else "Restauração"
    val category = when (itemType) {
        BackupItemType.APP_APK, BackupItemType.APP_DATA, BackupItemType.APP_SPLIT_APKS -> "Aplicativos"
        BackupItemType.FILE, BackupItemType.FOLDER -> "Arquivos e Pastas"
        BackupItemType.SMS -> "Mensagens (SMS)"
        BackupItemType.CALL_LOG -> "Registro de Chamadas"
        BackupItemType.CONTACTS -> "Contatos"
        BackupItemType.WIFI_CONFIG -> "Redes Wi-Fi"
        BackupItemType.BLUETOOTH -> "Bluetooth"
        BackupItemType.WALLPAPER, BackupItemType.WALLPAPER_LOCK -> "Papel de Parede"
        BackupItemType.DNS_CONFIG -> "Configurações DNS"
    }
    return "$prefix • $category"
}

@Composable
private fun ActiveOperationCard(
    op: Operacao,
    primaryAccent: Color,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onMinimize: () -> Unit
) {
    val isBackup = op.type == OperationType.BACKUP
    val isPausedState = op.isPaused || op.status == OperationStatus.PAUSED || op.stage.contains("esfriar", ignoreCase = true)

    val statusText = when {
        op.stage.contains("esfriar", ignoreCase = true) -> "Pausado para esfriar"
        op.status == OperationStatus.CANCELLING -> "Cancelando..."
        op.status == OperationStatus.PAUSING -> "Pausando..."
        isPausedState -> "Pausado"
        op.status == OperationStatus.STARTING -> "Iniciando..."
        else -> "Em execução"
    }

    val statusColor = when {
        op.status == OperationStatus.CANCELLING -> StatusError
        isPausedState || op.status == OperationStatus.PAUSING -> StatusWarning
        else -> StatusSuccess
    }

    // 1) Tipo da operação (ex.: "Backup • Aplicativos")
    val categoryLabel = getOperationCategoryLabel(op.type, op.currentItemType)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StandardCardShape)
            .border(1.dp, StandardCardBorder, StandardCardShape),
        colors = CardDefaults.cardColors(containerColor = StandardCardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // CABEÇALHO DO CARD: Linha 1 = Tipo da Operação (Largura completa)
            //                    Linha 2 = Selos de Status e Modo Térmico
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isPausedState) StatusWarning else StatusSuccess)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = categoryLabel.uppercase(Locale.ROOT),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = primaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (op.thermalMode.isNotBlank()) {
                        val thermalColor = when (op.thermalMode) {
                            "Frio" -> Color(0xFF64B5F6)
                            "Equilibrado" -> StatusWarning
                            else -> StatusSuccess
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DarkSurfaceVariant
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(thermalColor)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (op.deviceTemperature > 0f) "${op.thermalMode} · ${String.format(Locale.US, "%.0f", op.deviceTemperature)}°C" else op.thermalMode,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = statusText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

            // 2) ITEM ATUAL (Ícone + Nome) + 3) "ITEM X DE Y"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.resync.app.ui.components.ItemThumbnailIcon(
                    name = op.currentItemName,
                    packageName = op.currentPackageName,
                    type = op.currentItemType,
                    containerFile = op.containerFile,
                    size = 42.dp,
                    shapeRadius = 10.dp,
                    tintFallback = primaryAccent
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    SmoothCurrentItemText(
                        text = op.currentItemName.ifBlank { if (isBackup) "Empacotando dados..." else "Restaurando..." },
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 2,
                        reservedHeight = 36.dp
                    )
                    if (op.stage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        SmoothStageText(
                            stage = op.stage,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                }

                if (op.totalItems > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Item ${op.currentItemIndex.coerceAtLeast(1)} de ${op.totalItems}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 4) VOLUME (ex.: 11,7 MB / 233,8 MB) + 5) PORCENTAGEM + BARRA DE PROGRESSO
            Column(modifier = Modifier.fillMaxWidth()) {
                val processedFormatted = StorageRepository.formatFileSize(op.bytesProcessed)
                val totalFormatted = StorageRepository.formatFileSize(op.totalBytesEstimated.coerceAtLeast(op.bytesProcessed))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$processedFormatted / $totalFormatted",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    SmoothPercentText(
                        percent = op.progressPercent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryAccent
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                SmoothLinearProgressBar(
                    progress = op.progressPercent,
                    color = primaryAccent,
                    trackColor = DarkSurfaceVariant,
                    height = 7.dp,
                    shapeRadius = 4.dp
                )
            }

            // 6) VELOCIDADE DE UPLOAD/DOWNLOAD + 7) TEMPO DECORRIDO / ESTIMADO
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Velocidade
                    val effectiveSpeed = if (op.uploadSpeedBytesPerSec > 0L) op.uploadSpeedBytesPerSec
                        else if (op.downloadSpeedBytesPerSec > 0L) op.downloadSpeedBytesPerSec
                        else if (op.compressionSpeedBytesPerSec > 0L) op.compressionSpeedBytesPerSec
                        else op.speedBytesPerSec
                    val speedMb = effectiveSpeed.toDouble() / (1024.0 * 1024.0)
                    val speedText = when {
                        op.uploadSpeedBytesPerSec > 0L -> {
                            val s = if (speedMb >= 0.1) String.format(Locale.US, "%.1f MB/s", speedMb).replace('.', ',') else "${effectiveSpeed / 1024} KB/s"
                            "Upload: $s"
                        }
                        op.downloadSpeedBytesPerSec > 0L -> {
                            val s = if (speedMb >= 0.1) String.format(Locale.US, "%.1f MB/s", speedMb).replace('.', ',') else "${effectiveSpeed / 1024} KB/s"
                            "Download: $s"
                        }
                        effectiveSpeed > 0L -> {
                            val s = if (speedMb >= 0.1) String.format(Locale.US, "%.1f MB/s", speedMb).replace('.', ',') else "${effectiveSpeed / 1024} KB/s"
                            "Velocidade: $s"
                        }
                        else -> "Velocidade: Calculando…"
                    }

                    // Tempo
                    val elapsedSec = if (op.startTimeMillis > 0L) {
                        ((System.currentTimeMillis() - op.startTimeMillis) / 1000L).coerceAtLeast(0L)
                    } else 0L
                    val elapsedStr = FormatUtils.formatRemainingTimeOnly(elapsedSec)
                    val etaStr = if (op.estimatedRemainingSeconds > 0L) {
                        FormatUtils.formatRemainingTimeOnly(op.estimatedRemainingSeconds)
                    } else "Calculando…"

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = speedText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Decorrido: $elapsedStr · Resta: $etaStr",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Partes ou estágios se houver
            if (op.parts.isNotEmpty()) {
                OperationPartsProgressView(parts = op.parts, primaryAccent = primaryAccent)
            } else if (op.type == OperationType.BACKUP && !op.destinationOrSource.contains("Drive", ignoreCase = true) && op.localStage.isNotBlank()) {
                LocalStagesProgressView(
                    currentStage = op.localStage,
                    progressPercent = op.progressPercent,
                    speedMBps = (op.compressionSpeedBytesPerSec.toDouble() / (1024.0 * 1024.0)).toFloat(),
                    itemsPerSecond = op.itemsPerSecond,
                    primaryAccent = primaryAccent
                )
            }

            // 8) BOTÕES PAUSAR / CANCELAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { if (isPausedState) onResume() else onPause() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = StandardCardShape,
                    border = BorderStroke(1.dp, StandardCardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = StandardCardBg,
                        contentColor = primaryAccent
                    )
                ) {
                    Icon(
                        imageVector = if (isPausedState) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        tint = primaryAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPausedState) stringResource(R.string.action_resume) else stringResource(R.string.action_pause),
                        color = primaryAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = StandardCardShape,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }

            // 9) BOTÃO MINIMIZAR / EXECUTAR EM SEGUNDO PLANO
            Button(
                onClick = onMinimize,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EXECUTAR EM SEGUNDO PLANO",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun FinishedOperationCard(
    op: Operacao,
    primaryAccent: Color,
    onDismiss: () -> Unit
) {
    val isBackup = op.type == OperationType.BACKUP
    val failedItems = op.completedItems.filter { !it.success && !it.isIgnored }
    val ignoredItems = op.completedItems.filter { it.isIgnored }
    val hasRealFailures = failedItems.isNotEmpty() || (op.error != null && op.status == OperationStatus.ERROR)
    val hasWarnings = ignoredItems.isNotEmpty() || (op.error != null && op.stage.contains("aviso", ignoreCase = true))

    val isCancelled = op.status == OperationStatus.CANCELLED
    val isInterrupted = op.status == OperationStatus.INTERRUPTED
    val isSuccess = !hasRealFailures && !hasWarnings && !isCancelled && !isInterrupted && op.status == OperationStatus.COMPLETED

    val statusColor = if (isCancelled || isInterrupted) {
        StatusWarning
    } else if (hasRealFailures) {
        StatusError
    } else if (hasWarnings) {
        StatusWarning
    } else {
        StatusSuccess
    }

    val statusText = if (isCancelled) {
        if (isBackup) "Backup cancelado" else "Restauração cancelada"
    } else if (isInterrupted) {
        if (isBackup) "Backup Interrompido" else "Restauração Interrompida"
    } else if (hasRealFailures) {
        if (isBackup) "Finalizado com falhas" else "Finalizado com falhas"
    } else if (hasWarnings) {
        if (isBackup) "Finalizado com avisos" else "Finalizado com avisos"
    } else {
        if (isBackup) "Backup finalizado" else "Restauração finalizada"
    }

    val statusIcon = if (isCancelled) {
        Icons.Default.Cancel
    } else if (isInterrupted) {
        Icons.Default.Warning
    } else if (hasRealFailures) {
        Icons.Default.Error
    } else if (hasWarnings) {
        Icons.Default.WarningAmber
    } else {
        Icons.Default.CheckCircle
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(28.dp))
                if (isSuccess) {
                    CompletionAnimation(
                        size = CompletionAnimationSize.STANDARD,
                        themeAccent = statusColor,
                        playKey = op.id
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(48.dp))
                    }
                }
                // Botão X no topo direito
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Textos com alinhamento uniforme à esquerda (evita que Duração fique centralizada e os outros à esquerda)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = statusText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = statusColor,
                    textAlign = TextAlign.Start
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = op.currentItemName.ifBlank { op.error ?: "Operação concluída com êxito." },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )
            }

            if (ignoredItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = DarkCardBorder)
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Itens ignorados (${ignoredItems.size}):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = StatusWarning,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ignoredItems.forEach { item ->
                        Surface(
                            color = StatusWarning.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StatusWarning.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.name.ifBlank { item.id },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                val statusTextToShow = item.statusText ?: "Ignorado (sem dados)"
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = statusTextToShow,
                                    fontSize = 11.sp,
                                    color = StatusWarning,
                                    modifier = Modifier.padding(start = 22.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (failedItems.isNotEmpty() || (hasRealFailures && op.error != null)) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = DarkCardBorder)
                Spacer(modifier = Modifier.height(10.dp))

                if (failedItems.isNotEmpty()) {
                    Text(
                        text = "Itens com falha (${failedItems.size}):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = StatusError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        failedItems.forEach { item ->
                            Surface(
                                color = StatusError.copy(alpha = 0.08f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StatusError.copy(alpha = 0.25f)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.name.ifBlank { item.id },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!item.error.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.error,
                                            fontSize = 11.sp,
                                            color = StatusError,
                                            modifier = Modifier.padding(start = 22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!isSuccess && op.error != null && failedItems.isEmpty()) {
                    Surface(
                        color = StatusError.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusError.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = op.error,
                            fontSize = 11.sp,
                            color = StatusError,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            val durationText = op.formattedDuration ?: if (op.totalDurationSeconds > 0L) {
                com.resync.app.util.FormatUtils.formatDurationPtBr(op.totalDurationSeconds)
            } else if (op.startTimeMillis > 0L) {
                val sec = ((System.currentTimeMillis() - op.startTimeMillis) / 1000L).coerceAtLeast(1L)
                com.resync.app.util.FormatUtils.formatDurationPtBr(sec)
            } else null

            if (!durationText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tempo total: $durationText",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isInterrupted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = StandardCardShape,
                        border = BorderStroke(1.dp, StandardCardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = StandardCardBg,
                            contentColor = TextSecondary
                        )
                    ) {
                        Text("Descartar", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            if (isBackup) {
                                com.resync.app.engine.BackupRepository.resumeBackup()
                            } else {
                                com.resync.app.engine.RestoreRepository.resumeRestore()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                        shape = StandardCardShape,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retomar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    shape = StandardCardShape,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("Fechar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun OperationPartsProgressView(
    parts: List<OperationPartInfo>,
    primaryAccent: Color
) {
    if (parts.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.7f))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Partes (${parts.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
            val totalSpeed = parts.sumOf { it.speedMBps.toDouble() }.toFloat()
            if (totalSpeed > 0f) {
                Text(
                    text = String.format(Locale.US, "%.1f MB/s (%.0f Mbps)", totalSpeed, totalSpeed * 8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        parts.forEach { part ->
            val partPct = (part.progressPercent * 100).toInt().coerceIn(0, 100)
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Parte ${part.partIndex}/${part.totalParts}",
                        fontSize = 10.sp,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (part.isCompleted) "100% • Concluído" else "$partPct% • ${String.format(Locale.US, "%.1f MB/s", part.speedMBps)}",
                        fontSize = 10.sp,
                        color = if (part.isCompleted) StatusSuccess else TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { part.progressPercent.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (part.isCompleted) StatusSuccess else primaryAccent,
                    trackColor = DarkBg
                )
            }
        }
    }
}

@Composable
fun LocalStagesProgressView(
    currentStage: String,
    progressPercent: Float,
    speedMBps: Float,
    itemsPerSecond: Float,
    primaryAccent: Color
) {
    val stages = listOf("Preparando", "Compactando", "Gravando")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.7f))
            .padding(10.dp)
    ) {
        Text(
            text = "Etapas da Operação Local",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        stages.forEach { stageName ->
            val isCurrent = currentStage.contains(stageName, ignoreCase = true)
            val isPast = when {
                currentStage.contains("Gravando", ignoreCase = true) -> stageName != "Gravando"
                currentStage.contains("Compactando", ignoreCase = true) -> stageName == "Preparando"
                else -> false
            }
            val stageProgress = if (isPast) 1f else if (isCurrent) progressPercent else 0f
            val stageColor = if (isPast) StatusSuccess else if (isCurrent) primaryAccent else TextMuted.copy(alpha = 0.4f)

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stageName,
                        fontSize = 10.sp,
                        color = if (isCurrent || isPast) TextPrimary else TextMuted,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                    )
                    if (isCurrent) {
                        val metrics = buildString {
                            if (speedMBps > 0f) append(String.format(Locale.US, "%.1f MB/s", speedMBps))
                            if (itemsPerSecond > 0f) {
                                if (isNotEmpty()) append(" • ")
                                append(String.format(Locale.US, "%.0f arq/s", itemsPerSecond))
                            }
                        }
                        Text(
                            text = if (metrics.isNotEmpty()) metrics else "${(stageProgress * 100).toInt()}%",
                            fontSize = 10.sp,
                            color = primaryAccent,
                            fontWeight = FontWeight.Medium
                        )
                    } else if (isPast) {
                        Text(
                            text = "Concluído",
                            fontSize = 10.sp,
                            color = StatusSuccess,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { stageProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = stageColor,
                    trackColor = DarkBg
                )
            }
        }
    }
}

