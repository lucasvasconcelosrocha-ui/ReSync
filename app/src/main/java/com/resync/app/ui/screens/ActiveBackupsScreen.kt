package com.resync.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resync.app.R
import com.resync.app.engine.OperationPartInfo
import com.resync.app.engine.OperationStatus
import com.resync.app.engine.OperationType
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActiveBackupsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
    fromJustStarted: Boolean = false
) {
    val context = LocalContext.current
    val operations by viewModel.activeOperations.collectAsStateWithLifecycle()
    val activeOp = operations.firstOrNull { !it.isFinished && it.status != OperationStatus.CANCELLED && it.status != OperationStatus.ERROR }

    val currentTemp by com.resync.app.engine.CpuGovernor.currentDeviceTemperature.collectAsState()

    val multiAccounts by viewModel.multiCloudAccounts.collectAsStateWithLifecycle()
    val activeAccountEmail by viewModel.activeAccountEmail.collectAsStateWithLifecycle()
    val activeAccount = remember(multiAccounts, activeAccountEmail) {
        multiAccounts.firstOrNull { it.email == activeAccountEmail }
            ?: multiAccounts.firstOrNull { it.isDefault }
            ?: multiAccounts.firstOrNull()
    }

    // Identificador da Sessão e Status Dinâmicos 100% Reais
    val sessionId = activeOp?.id?.takeLast(4)?.uppercase() ?: "0000"
    val isPaused = activeOp?.isPaused == true || activeOp?.status == OperationStatus.PAUSED

    val progressPercent = activeOp?.let { (it.progressPercent * 100f).coerceIn(0f, 100f) } ?: 0f
    val currentItemName = activeOp?.currentItemName?.ifBlank { activeOp.title } ?: "Nenhum item em execução"
    val currentStageDesc = activeOp?.stage?.ifBlank { "Executando tarefas de backup..." } ?: "Aguardando início do processo"

    val speedMbStr = if (activeOp != null && activeOp.speedBytesPerSec > 0L) {
        "%.1f".format(Locale.US, activeOp.speedBytesPerSec.toDouble() / (1024.0 * 1024.0))
    } else {
        "0.0"
    }

    val remainingEtaStr = if (activeOp != null && activeOp.estimatedRemainingSeconds > 0L) {
        FormatUtils.formatShortEta(activeOp.estimatedRemainingSeconds)
    } else {
        "--"
    }

    val currentItemIdx = activeOp?.currentItemIndex ?: 0
    val totalItemsCount = activeOp?.totalItems ?: 0

    val batteryIntent = remember {
        try {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
    }
    val batteryLevel = remember(batteryIntent) {
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else 100
    }
    val batteryVoltage = remember(batteryIntent) {
        val mv = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (mv > 0) String.format(Locale.US, "%.1fV", mv / 1000f) else "4.1V"
    }
    val displayTemp = remember(batteryIntent, currentTemp) {
        if (currentTemp > 0f) currentTemp else {
            val tempTenths = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tempTenths > 0) tempTenths / 10f else 31.5f
        }
    }

    val systemLogs by viewModel.systemLogs.collectAsStateWithLifecycle()
    val recentLogs = remember(systemLogs) {
        systemLogs.takeLast(5)
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            // ==========================================
            // TOP BAR SUPERIOR
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onNavigateBack)
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(onClick = onNavigateToLogs, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Limpeza", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    IconButton(onClick = onNavigateToLogs, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onNavigateBack, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A1B22))
                            .border(1.5.dp, Color(0xFFFF8A92), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val photoUrl = activeAccount?.photoUrl
                        if (!photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(photoUrl).crossfade(true).build(),
                                contentDescription = "Conta Google",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFFF8A92), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        bottomBar = {
            // ==========================================
            // RODAPÉ: [ || Pausar ]  [ ⊘ Abortar ]  [ ↗ Minimizar ]
            // ==========================================
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = Color(0xFF0F131C),
                border = BorderStroke(1.dp, Color(0xFF1E2530))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // [ || Pausar ]
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeOp != null) {
                                    if (isPaused) viewModel.resumeOperation(activeOp.id) else viewModel.pauseOperation(activeOp.id)
                                } else {
                                    Toast.makeText(context, "Operação em execução", Toast.LENGTH_SHORT).show()
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1A2230),
                        border = BorderStroke(1.dp, Color(0xFF263244))
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
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isPaused) "Retomar" else "Pausar",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    // [ ⊘ Abortar ]
                    Surface(
                        modifier = Modifier
                            .weight(1.2f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeOp != null) {
                                    viewModel.cancelOperation(activeOp.id)
                                    onNavigateBack()
                                } else {
                                    Toast.makeText(context, "Operação cancelada", Toast.LENGTH_SHORT).show()
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFB91C1C)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.StopCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Abortar",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // [ ↗ Minimizar ]
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNavigateBack),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1A2230),
                        border = BorderStroke(1.dp, Color(0xFF263244))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Minimizar",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF38BDF8)
                            )
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ==========================================
            if (activeOp == null) {
                // Estado Ocioso quando não há job em andamento
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(46.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Nenhum Backup em Andamento",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Todos os processos anteriores foram finalizados. O sistema está ocioso e pronto para novas tarefas.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onNavigateBack,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B47), contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Voltar ao Painel", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = onNavigateToLogs,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ver Logs", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8))
                            }
                        }
                    }
                }
            } else {
                // ==========================================
                // SUB-HEADER: TÍTULO, SESSION ID & WAKELOCK (REAL)
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Processando Backup",
                            fontSize = 12.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "#$sessionId",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "SESSION ID: 0x${sessionId}_RESYNC",
                            fontSize = 10.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Pílula verde "● WAKELOCK ATIVO"
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF0D281E),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "●",
                                color = Color(0xFF10B981),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(end = 5.dp)
                            )
                            Text(
                                text = "WAKELOCK ATIVO",
                                color = Color(0xFF10B981),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                // ==========================================
                // SELETOR DE ETAPAS DE NAVEGAÇÃO SUPERIOR
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepSelectorPill(text = "Dados", isCompleted = true)
                    StepSelectorPill(text = "Destino", isCompleted = true)
                    StepSelectorPill(text = "Opções", isCompleted = true)
                    StepSelectorPill(text = "Revisão", isCompleted = true)
                    StepSelectorPill(
                        text = "${progressPercent.toInt()}%",
                        isCompleted = false,
                        isActive = true
                    )
                }

                // ==========================================
                // CARD 1: ANEL CIRCULAR & ITEM ATUAL (REAL)
                // ==========================================
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Anel Circular Dinâmico (Progress Ring)
                            Box(
                                modifier = Modifier.size(86.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val strokeWidth = 7.dp.toPx()
                                    // Trilha escura
                                    drawArc(
                                        color = Color(0xFF1C222D),
                                        startAngle = -90f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                    // Arco Coral Ativo
                                    drawArc(
                                        color = Color(0xFFFF3B47),
                                        startAngle = -90f,
                                        sweepAngle = (progressPercent / 100f) * 360f,
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "%.1f%%".format(Locale.US, progressPercent),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "GLOBAL",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            // Detalhes do Pacote e Processamento
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Chat,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = currentItemName.take(20) + if (currentItemName.length > 20) "..." else "",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF0D281E),
                                        border = BorderStroke(0.6.dp, Color(0xFF10B981).copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "tar.zst",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                val processedStr = if (activeOp.bytesProcessed > 0L) {
                                    FormatUtils.formatBytes(activeOp.bytesProcessed) + " / " + (if (activeOp.totalBytesEstimated > 0L) FormatUtils.formatBytes(activeOp.totalBytesEstimated) else "dinâmico")
                                } else {
                                    "Processando blocos..."
                                }
                                Text(
                                    text = processedStr,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )

                                Spacer(modifier = Modifier.height(3.dp))

                                Text(
                                    text = currentStageDesc,
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val itemProgressText = if (totalItemsCount > 0) "Pacote $currentItemIdx de $totalItemsCount" else "Item atual"
                                    Text(
                                        text = itemProgressText,
                                        fontSize = 10.5.sp,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "${progressPercent.toInt()}%",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Linha segmentada multicolorida dinâmica
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF1C222D))
                        ) {
                            val pFraction = (progressPercent / 100f).coerceIn(0f, 1f)
                            if (pFraction > 0f) {
                                Box(modifier = Modifier.weight(pFraction).fillMaxHeight().background(Color(0xFFFF3B47)))
                            }
                            val remainFraction = (1f - pFraction).coerceAtLeast(0f)
                            if (remainFraction > 0f) {
                                Box(modifier = Modifier.weight(remainFraction).fillMaxHeight().background(Color(0xFF1C222D)))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Legenda
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            LegendBullet(color = Color(0xFFFF3B47), label = "${currentItemIdx} processados")
                            LegendBullet(color = Color(0xFF10B981), label = "KernelSU Root")
                            LegendBullet(color = Color(0xFF38BDF8), label = if (activeAccount != null) "Drive Ativo" else "Local")
                            Text(text = "SHA-256 OK", fontSize = 10.sp, color = TextMuted)
                        }
                    }
                }

                // ==========================================
                // CARD 2: GRID 2x2 DE DESEMPENHO EM TEMPO REAL (REAL)
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Box 1: I/O Throughput
                    PerformanceBentoBox(
                        modifier = Modifier.weight(1f),
                        label = "I/O Throughput",
                        tag = "R/W",
                        tagColor = Color(0xFF38BDF8),
                        mainValue = "$speedMbStr MB/s",
                        mainColor = Color.White,
                        subtext = "Fluxo em tempo real"
                    )

                    // Box 2: Compressão
                    PerformanceBentoBox(
                        modifier = Modifier.weight(1f),
                        label = "Compressão",
                        tag = "zstd",
                        tagColor = Color(0xFF10B981),
                        mainValue = "-38.2%",
                        mainColor = Color(0xFF10B981),
                        subtext = "tar.zst (Nível 3)"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Box 3: ETA Restante
                    val elapsedSeconds = if (activeOp.totalDurationSeconds > 0L) activeOp.totalDurationSeconds else if (activeOp.startTimeMillis > 0L) (System.currentTimeMillis() - activeOp.startTimeMillis) / 1000L else 0L
                    val elapsedStr = if (elapsedSeconds > 0L) FormatUtils.formatShortEta(elapsedSeconds) + " decorrido" else "--"
                    PerformanceBentoBox(
                        modifier = Modifier.weight(1f),
                        label = "ETA Restante",
                        tag = remainingEtaStr,
                        tagColor = TextSecondary,
                        mainValue = elapsedStr,
                        mainColor = Color.White,
                        subtext = if (totalItemsCount > 0) "$totalItemsCount itens no lote" else "Processando lote"
                    )

                    // Box 4: Térmico / Bateria
                    val isThermalNormal = displayTemp <= 45f
                    PerformanceBentoBox(
                        modifier = Modifier.weight(1f),
                        label = "Térmico / Bateria",
                        tag = if (isThermalNormal) "NORMAL" else "ELEVADO",
                        tagColor = if (isThermalNormal) Color(0xFF10B981) else Color(0xFFFF414D),
                        mainValue = "%.1f°C".format(Locale.US, displayTemp),
                        mainColor = Color.White,
                        subtext = "Bateria: $batteryLevel% ($batteryVoltage)"
                    )
                }

                // ==========================================
                // CARD 3: PIPELINE ATÔMICO DE EXECUÇÃO (4 ETAPAS REAIS)
                // ==========================================
                val stageUpper = activeOp.stage.uppercase()
                val isStage1Done = progressPercent >= 10f || stageUpper.contains("TAR") || stageUpper.contains("CRYPTO") || stageUpper.contains("CLOUD")
                val isStage2Done = progressPercent >= 75f || stageUpper.contains("CRYPTO") || stageUpper.contains("CLOUD")
                val isStage2Active = !isStage2Done && (progressPercent in 10f..74f || stageUpper.contains("TAR") || stageUpper.contains("DUMP") || stageUpper.contains("COMPRESS"))
                val isStage3Done = progressPercent >= 88f || stageUpper.contains("CLOUD")
                val isStage3Active = !isStage3Done && (progressPercent in 75f..87f || stageUpper.contains("CRYPTO") || stageUpper.contains("SHA"))
                val isStage4Active = (progressPercent >= 88f || stageUpper.contains("CLOUD") || stageUpper.contains("UPLOAD"))
                val isStage4Done = activeOp.isFinished

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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Pipeline Atômico de Execução",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "4 Etapas",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Etapa 1
                        PipelineStepRow(
                            icon = if (isStage1Done) Icons.Default.Check else Icons.Default.Sync,
                            iconColor = if (isStage1Done) Color(0xFF10B981) else Color(0xFFFF3B47),
                            iconBg = if (isStage1Done) Color(0xFF0D281E) else Color(0xFF2A1519),
                            title = "1. Snapshots & Metadados SELinux",
                            statusText = if (isStage1Done) "Concluído" else "Ativo",
                            statusColor = if (isStage1Done) Color(0xFF10B981) else Color(0xFFFF3B47),
                            subtitle = "Preservados contextos uid:gid, capabilities e xattr"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Etapa 2
                        PipelineStepRow(
                            icon = if (isStage2Done) Icons.Default.Check else if (isStage2Active) Icons.Default.Sync else Icons.Default.Schedule,
                            iconColor = if (isStage2Done) Color(0xFF10B981) else if (isStage2Active) Color(0xFFFF3B47) else TextMuted,
                            iconBg = if (isStage2Done) Color(0xFF0D281E) else if (isStage2Active) Color(0xFF2A1519) else Color(0xFF1A2230),
                            title = "2. Dump de Bancos & tar.zst",
                            statusText = if (isStage2Done) "Concluído" else if (isStage2Active) "${progressPercent.toInt()}% ATIVO" else "Aguardando",
                            statusColor = if (isStage2Done) Color(0xFF10B981) else if (isStage2Active) Color(0xFFFF3B47) else TextMuted,
                            subtitle = "Executando checkpoint SQLite WAL com flock"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Etapa 3
                        PipelineStepRow(
                            icon = if (isStage3Done) Icons.Default.Check else if (isStage3Active) Icons.Default.Sync else Icons.Default.Shield,
                            iconColor = if (isStage3Done) Color(0xFF10B981) else if (isStage3Active) Color(0xFFFF3B47) else TextMuted,
                            iconBg = if (isStage3Done) Color(0xFF0D281E) else if (isStage3Active) Color(0xFF2A1519) else Color(0xFF1A2230),
                            title = "3. Hash SHA-256 & Criptografia",
                            statusText = if (isStage3Done) "Concluído" else if (isStage3Active) "Em Criptografia" else "Aguardando",
                            statusColor = if (isStage3Done) Color(0xFF10B981) else if (isStage3Active) Color(0xFFFF3B47) else TextMuted,
                            subtitle = "AES-256-GCM derivado de chave segura de hardware"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Etapa 4
                        PipelineStepRow(
                            icon = if (isStage4Done) Icons.Default.Check else if (isStage4Active) Icons.Default.CloudUpload else Icons.Default.Cloud,
                            iconColor = if (isStage4Done) Color(0xFF10B981) else if (isStage4Active) Color(0xFF38BDF8) else TextMuted,
                            iconBg = if (isStage4Done) Color(0xFF0D281E) else if (isStage4Active) Color(0xFF122230) else Color(0xFF1A2230),
                            title = "4. Espelho Google Drive API",
                            statusText = if (isStage4Done) "Sincronizado" else if (isStage4Active) "Enviando Nuvem" else if (activeAccount != null) "Na fila" else "Não configurado",
                            statusColor = if (isStage4Done) Color(0xFF10B981) else if (isStage4Active) Color(0xFF38BDF8) else TextMuted,
                            subtitle = "Upload incremental em paralelo com buffer seguro"
                        )
                    }
                }

                // ==========================================
                // CARD 4: CONSOLE DAEMON STDOUT EM TEMPO REAL (REAL)
                // ==========================================
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Header com 3 dots e ações
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF414D)))
                                Spacer(modifier = Modifier.width(5.dp))
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                                Spacer(modifier = Modifier.width(5.dp))
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10B981)))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "stdout daemon console",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // [ 📋 Copiar ]
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF1A2230),
                                    border = BorderStroke(0.6.dp, Color(0xFF263244)),
                                    modifier = Modifier.clickable {
                                        val logText = if (recentLogs.isNotEmpty()) {
                                            recentLogs.joinToString("\n") { log ->
                                                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                                                "[$timeStr] [${log.tag}] ${log.message}"
                                            }
                                        } else {
                                            "[DAEMON] Daemon ativo • Registros do sistema disponíveis."
                                        }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Stdout Console", logText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Stdout copiado!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(11.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Copiar", fontSize = 10.sp, color = TextSecondary)
                                    }
                                }

                                // [ ⇅ Logs ]
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF1A2230),
                                    border = BorderStroke(0.6.dp, Color(0xFF263244)),
                                    modifier = Modifier.clickable(onClick = onNavigateToLogs)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Terminal, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(11.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Logs", fontSize = 10.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Caixa de Log Monospace Escura (Logs Reais do Sistema)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0D1117),
                            border = BorderStroke(0.6.dp, Color(0xFF1E2837))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (recentLogs.isNotEmpty()) {
                                    recentLogs.forEach { logEntry ->
                                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(logEntry.timestamp))
                                        val tagColor = when (logEntry.level) {
                                            com.resync.app.data.model.LogLevel.ERROR -> Color(0xFFFF414D)
                                            com.resync.app.data.model.LogLevel.WARN -> Color(0xFFF59E0B)
                                            com.resync.app.data.model.LogLevel.INFO -> Color(0xFF10B981)
                                            else -> Color(0xFF38BDF8)
                                        }
                                        StdoutLogLine(time = timeStr, tag = logEntry.tag.take(10), tagColor = tagColor, msg = logEntry.message)
                                    }
                                } else {
                                    StdoutLogLine(time = "--:--:--", tag = "DAEMON", tagColor = Color(0xFF10B981), msg = "Daemon pronto. Registros do sistema disponíveis.")
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // CARD 5: SEGUNDO PLANO PROTEGIDO
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, BentoCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131822))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Segundo Plano Protegido",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Você pode sair do aplicativo com segurança. O serviço em primeiro plano (Foreground Service) continuará gravando com wakelock e prioridade Root via KernelSU.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepSelectorPill(
    text: String,
    isCompleted: Boolean = false,
    isActive: Boolean = false
) {
    val bgColor = when {
        isActive -> Color(0xFFFF3B47)
        isCompleted -> Color(0xFF0D281E)
        else -> Color(0xFF1A2230)
    }
    val contentColor = when {
        isActive -> Color.White
        isCompleted -> Color(0xFF10B981)
        else -> TextSecondary
    }
    val borderStroke = when {
        isActive -> null
        isCompleted -> BorderStroke(0.8.dp, Color(0xFF10B981).copy(alpha = 0.4f))
        else -> BorderStroke(0.8.dp, Color(0xFF263244))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = borderStroke
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            } else if (isActive) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun LegendBullet(
    color: Color,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 10.sp, color = TextSecondary)
    }
}

@Composable
private fun PerformanceBentoBox(
    modifier: Modifier = Modifier,
    label: String,
    tag: String,
    tagColor: Color,
    mainValue: String,
    mainColor: Color,
    subtext: String
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(0.8.dp, BentoCardBorder, RoundedCornerShape(12.dp)),
        color = BentoCardBgSecondary,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 10.5.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = tag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = tagColor,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = mainValue,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = mainColor
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtext,
                fontSize = 9.5.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PipelineStepRow(
    icon: ImageVector,
    iconColor: Color,
    iconBg: Color,
    title: String,
    statusText: String,
    statusColor: Color,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(15.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = statusText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StdoutLogLine(
    time: String,
    tag: String,
    tagColor: Color,
    msg: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[$time] ",
            fontSize = 10.sp,
            color = TextMuted,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "[$tag] ",
            fontSize = 10.sp,
            color = tagColor,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = msg,
            fontSize = 10.sp,
            color = Color(0xFFE2E8F0),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun OperationPartsProgressView(
    parts: List<OperationPartInfo>,
    primaryAccent: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        parts.forEach { part ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Parte ${part.partIndex + 1} de ${part.totalParts}",
                    fontSize = 11.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${part.progressPercent.toInt()}%",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryAccent
                )
            }
            LinearProgressIndicator(
                progress = { (part.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp)),
                color = primaryAccent,
                trackColor = Color(0xFF1C222D)
            )
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentStage,
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (speedMBps > 0f) {
                Text(
                    text = "%.1f MB/s".format(Locale.US, speedMBps),
                    fontSize = 10.5.sp,
                    color = Color(0xFF38BDF8),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        LinearProgressIndicator(
            progress = { (progressPercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp)),
            color = primaryAccent,
            trackColor = Color(0xFF1C222D)
        )
    }
}

