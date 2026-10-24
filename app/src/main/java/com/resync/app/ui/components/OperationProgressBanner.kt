package com.resync.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.CompletedItemInfo
import com.resync.app.engine.IconCacheManager
import com.resync.app.engine.OperationPartInfo
import com.resync.app.engine.OperationStatus
import com.resync.app.engine.OperationType
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.screens.LocalStagesProgressView
import com.resync.app.ui.screens.OperationPartsProgressView
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.FormatUtils
import com.resync.app.ui.components.SmoothCurrentItemText
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.ui.components.SmoothPercentText
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Integrated bottom banner for active operations (single or multi-active).
 * Placed inside the bottom bar column above action buttons / navigation bar.
 */
@Composable
fun ActiveOperationsBottomBanner(
    viewModel: MainViewModel,
    primaryAccent: Color,
    onNavigateToActive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeOperations by viewModel.activeOperations.collectAsStateWithLifecycle()
    val runningOperations = activeOperations.filter { !it.isFinished }
    val displayOp = runningOperations.firstOrNull()

    if (displayOp == null) return

    val isMultiActive = runningOperations.size > 1

    Box(modifier = modifier.fillMaxWidth()) {
        if (isMultiActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, primaryAccent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .clickable { onNavigateToActive() },
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(primaryAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = primaryAccent,
                                strokeWidth = 2.dp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${runningOperations.size} operações em andamento",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Toque para ver todas em andamento",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "Ver todas",
                        tint = primaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            val isPausedState = displayOp.isPaused || displayOp.status == OperationStatus.PAUSED
            val isBackup = displayOp.type == OperationType.BACKUP
            val failedCount = displayOp.completedItems.count { !it.success && !it.isIgnored }
            val ignoredCount = displayOp.completedItems.count { it.isIgnored }
            val bannerTitle = if (displayOp.isFinished) {
                if (isBackup) {
                    when {
                        displayOp.error != null || failedCount > 0 -> "Finalizado com falhas"
                        ignoredCount > 0 -> "Finalizado com avisos"
                        else -> "Backup finalizado"
                    }
                } else {
                    when {
                        displayOp.error != null || failedCount > 0 -> "Finalizado com falhas"
                        ignoredCount > 0 -> "Finalizado com avisos"
                        else -> "Restauração finalizada"
                    }
                }
            } else {
                displayOp.stage.ifBlank { if (isBackup) "Backup em andamento" else "Restauração em andamento" }
            }

            OperationProgressBanner(
                title = bannerTitle,
                subtitle = displayOp.currentItemName,
                progress = displayOp.progressPercent,
                isFinished = displayOp.isFinished,
                error = displayOp.error,
                primaryAccent = primaryAccent,
                currentItemIndex = displayOp.currentItemIndex,
                totalItems = displayOp.totalItems,
                currentItemType = displayOp.currentItemType,
                completedItems = displayOp.completedItems,
                isPaused = isPausedState,
                uploadSpeedBytesPerSec = displayOp.uploadSpeedBytesPerSec,
                downloadSpeedBytesPerSec = displayOp.downloadSpeedBytesPerSec,
                compressionSpeedBytesPerSec = displayOp.compressionSpeedBytesPerSec,
                estimatedRemainingSeconds = displayOp.estimatedRemainingSeconds,
                bytesProcessed = displayOp.bytesProcessed,
                totalBytesEstimated = displayOp.totalBytesEstimated,
                currentPackageName = displayOp.currentPackageName,
                containerFile = displayOp.containerFile,
                parts = displayOp.parts,
                thermalMode = displayOp.thermalMode,
                deviceTemperature = displayOp.deviceTemperature,
                localStage = displayOp.localStage,
                itemsPerSecond = displayOp.itemsPerSecond,
                onClick = onNavigateToActive,
                onPause = { viewModel.pauseOperation(displayOp.id) },
                onResume = { viewModel.resumeOperation(displayOp.id) },
                onCancel = { viewModel.cancelOperation(displayOp.id) },
                onDismiss = { viewModel.dismissOperation(displayOp.id) }
            )
        }
    }
}

/**
 * Compact floating/integrated progress banner.
 * Shows title in 1 line + 1 or 2 small detail lines with ellipsis, without cutting texts.
 */
@Composable
fun OperationProgressBanner(
    title: String,
    subtitle: String,
    progress: Float,
    isFinished: Boolean,
    error: String?,
    primaryAccent: Color,
    currentItemIndex: Int = 0,
    totalItems: Int = 0,
    currentItemType: BackupItemType = BackupItemType.FILE,
    completedItems: List<CompletedItemInfo> = emptyList(),
    manualActionNotice: String? = null,
    isPaused: Boolean = false,
    uploadSpeedBytesPerSec: Long = 0L,
    downloadSpeedBytesPerSec: Long = 0L,
    compressionSpeedBytesPerSec: Long = 0L,
    estimatedRemainingSeconds: Long = 0L,
    bytesProcessed: Long = 0L,
    totalBytesEstimated: Long = 0L,
    currentPackageName: String = "",
    containerFile: File? = null,
    parts: List<OperationPartInfo> = emptyList(),
    thermalMode: String = "",
    deviceTemperature: Float = 0f,
    localStage: String = "",
    itemsPerSecond: Float = 0f,
    onClick: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val effectiveSpeed = if (uploadSpeedBytesPerSec > 0) uploadSpeedBytesPerSec else if (downloadSpeedBytesPerSec > 0) downloadSpeedBytesPerSec else compressionSpeedBytesPerSec

    val context = LocalContext.current
    val iconCache = remember { IconCacheManager.getInstance(context) }
    var currentAppIcon by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(title, subtitle, currentPackageName, containerFile) {
        val identifier = currentPackageName.ifBlank { subtitle.ifBlank { title } }
        currentAppIcon = iconCache.getItemIcon(identifier, currentPackageName, currentItemType, containerFile)
    }

    val hasFailures = error != null || completedItems.any { !it.success && !it.isIgnored }
    val hasIgnored = completedItems.any { it.isIgnored }

    LaunchedEffect(isFinished, hasFailures) {
        if (isFinished && !hasFailures) {
            delay(4000L)
            onDismiss()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                when {
                    isFinished && hasFailures -> StatusError
                    isFinished && hasIgnored -> StatusWarning
                    error != null -> StatusError
                    else -> primaryAccent.copy(alpha = 0.6f)
                },
                RoundedCornerShape(14.dp)
            )
            .clickable {
                if (onClick != null) {
                    onClick()
                } else {
                    isExpanded = !isExpanded
                }
            },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // LINHA 1: Ícone + Título (1 linha) + Porcentagem + Ações Rápidas (Pausar/Cancelar/Chevron)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ícone ou indicador de progresso
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                hasFailures -> StatusError.copy(alpha = 0.15f)
                                isFinished && hasIgnored -> StatusWarning.copy(alpha = 0.15f)
                                else -> primaryAccent.copy(alpha = 0.15f)
                            }
                        )
                        .clickable { onClick?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isFinished) {
                        if (hasFailures) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = StatusError,
                                modifier = Modifier.size(18.dp)
                            )
                        } else if (hasIgnored) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusWarning,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            CompletionAnimation(
                                size = CompletionAnimationSize.COMPACT,
                                themeAccent = StatusSuccess,
                                playKey = "banner_${title}_${subtitle}"
                            )
                        }
                    } else if (isPaused) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = null,
                            tint = primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    } else if (currentAppIcon != null) {
                        Image(
                            bitmap = currentAppIcon!!,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(18.dp),
                            color = primaryAccent,
                            strokeWidth = 2.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Título em 1 linha com porcentagem
                val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
                val headerTitle = when {
                    isPaused -> "Pausado • $pct%"
                    isFinished -> when {
                        hasFailures -> "Finalizado com falhas"
                        hasIgnored -> "Finalizado com avisos"
                        else -> "$title • 100%"
                    }
                    title.isNotBlank() -> "$title • $pct%"
                    else -> "Processando • $pct%"
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = headerTitle,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isFinished && hasFailures -> StatusError
                            isFinished && hasIgnored -> StatusWarning
                            error != null -> StatusError
                            else -> TextPrimary
                        },
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (thermalMode.isNotBlank() && !isFinished) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val thermalColor = when (thermalMode) {
                            "Frio" -> Color(0xFF64B5F6)
                            "Equilibrado" -> StatusWarning
                            else -> StatusSuccess
                        }
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(thermalColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Botões de ação: Pausar/Retomar, Cancelar, Chevron de expansão
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isFinished) {
                        if (onPause != null && onResume != null) {
                            IconButton(
                                onClick = { if (isPaused) onResume() else onPause() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Retomar" else "Pausar",
                                    tint = primaryAccent,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        if (onCancel != null) {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancelar",
                                    tint = StatusError,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    } else {
                        if (error != null && onRetry != null) {
                            TextButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Tentar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Fechar",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Chevron para expandir/recolher detalhes
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Recolher" else "Detalhes",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // LINHA 2: Item Atual / Contagem de itens (ex.: "126 de 162 itens • WhatsApp")
            val itemDetail = buildString {
                if (totalItems > 0 && currentItemIndex > 0) {
                    append("$currentItemIndex de $totalItems itens")
                    if (subtitle.isNotBlank() && subtitle != title) {
                        append(" • $subtitle")
                    }
                } else if (subtitle.isNotBlank()) {
                    append(subtitle)
                } else if (localStage.isNotBlank()) {
                    append(localStage)
                } else {
                    append("Em andamento...")
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            SmoothCurrentItemText(
                text = itemDetail,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Normal,
                color = TextSecondary,
                maxLines = 1,
                reservedHeight = 20.dp
            )

            // LINHA 3: Bytes enviados/total, velocidade em MB/s e tempo restante
            val speedMb = effectiveSpeed.toDouble() / (1024.0 * 1024.0)
            val speedMbps = speedMb * 8.0

            val metricsDetail = buildString {
                if (totalBytesEstimated > 0L && !isFinished) {
                    append(FormatUtils.formatBytes(bytesProcessed))
                    append(" de ")
                    append(FormatUtils.formatBytes(totalBytesEstimated))
                }
                if (!isFinished && effectiveSpeed > 0L) {
                    if (isNotEmpty()) append(" • ")
                    if (speedMb >= 0.1) {
                        append(String.format(Locale.US, "%.1f MB/s", speedMb))
                    } else {
                        append("${effectiveSpeed / 1024} KB/s")
                    }
                }
                if (!isFinished) {
                    if (isNotEmpty()) append(" • ")
                    append(com.resync.app.util.FormatUtils.formatShortEta(estimatedRemainingSeconds))
                }
            }

            if (metricsDetail.isNotBlank() && !isFinished) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = metricsDetail,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Barra fina de progresso (3dp) com shimmer suave de atividade
            if (!isFinished) {
                Spacer(modifier = Modifier.height(5.dp))
                SmoothLinearProgressBar(
                    progress = progress,
                    color = primaryAccent,
                    trackColor = DarkCardBorder.copy(alpha = 0.5f),
                    height = 3.dp,
                    shapeRadius = 1.5.dp
                )
            }

            // DETALHES EXPANDIDOS (Aparecem ao tocar ou clicar no chevron)
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(6.dp))

                    if (parts.isNotEmpty()) {
                        OperationPartsProgressView(parts = parts, primaryAccent = primaryAccent)
                    } else if (localStage.isNotBlank()) {
                        LocalStagesProgressView(
                            currentStage = localStage,
                            progressPercent = progress,
                            speedMBps = speedMb.toFloat(),
                            itemsPerSecond = itemsPerSecond,
                            primaryAccent = primaryAccent
                        )
                    }

                    if (manualActionNotice != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = manualActionNotice,
                                fontSize = 11.sp,
                                color = StatusWarning,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }

                    if (completedItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val successCount = completedItems.count { it.success && !it.isIgnored }
                        val ignoredCount = completedItems.count { it.isIgnored }
                        val failCount = completedItems.count { !it.success && !it.isIgnored }
                        val countsText = buildString {
                            append("$successCount ok")
                            if (ignoredCount > 0) append(", $ignoredCount ignorados")
                            if (failCount > 0) append(", $failCount falhas")
                        }
                        Text(
                            text = "Itens Concluídos ($countsText):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            completedItems.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val (itemIcon, itemTint) = when {
                                        !item.success && !item.isIgnored -> Icons.Default.Error to StatusError
                                        item.isIgnored -> Icons.Default.Warning to StatusWarning
                                        else -> Icons.Default.CheckCircle to StatusSuccess
                                    }
                                    Icon(
                                        imageVector = itemIcon,
                                        contentDescription = null,
                                        tint = itemTint,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.name,
                                        fontSize = 11.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (item.error != null) {
                                        Text(
                                            text = item.error,
                                            fontSize = 10.sp,
                                            color = StatusError,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else if (item.isIgnored && !item.statusText.isNullOrBlank()) {
                                        Text(
                                            text = item.statusText.orEmpty(),
                                            fontSize = 10.sp,
                                            color = StatusWarning,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
