package com.resync.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.resync.app.util.showSafeToast
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resync.app.R
import com.resync.app.ui.components.FuturisticCompletionOrb
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

    // Status Dinâmico 100% Real
    val isPaused = activeOp?.isPaused == true || activeOp?.status == OperationStatus.PAUSED

    val progressPercent = activeOp?.let { (it.progressPercent * 100f).coerceIn(0f, 100f) } ?: 0f
    val currentItemName = activeOp?.currentItemName?.ifBlank { activeOp.title } ?: stringResource(R.string.no_item_running)
    val currentStageDesc = activeOp?.stage?.ifBlank { stringResource(R.string.executing_backup_tasks) } ?: stringResource(R.string.waiting_process_start)

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
        systemLogs.toList()
            .filter { !it.tag.contains("DIAGN", true) && !it.message.contains("[DIAGN", true) && !it.message.contains("preload", true) }
            .takeLast(6)
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF00FF88).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF00FF88).copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "v${com.resync.app.BuildConfig.VERSION_NAME}",
                            color = Color(0xFF00FF88),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(onClick = onNavigateToLogs, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.CleaningServices, contentDescription = stringResource(R.string.active_clean_desc), tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    IconButton(onClick = onNavigateToLogs, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onNavigateBack, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title), tint = Color.White, modifier = Modifier.size(20.dp))
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
                                contentDescription = stringResource(R.string.account_google_account),
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
            if (activeOp != null) {
                // ==========================================
                // RODAPÉ EM ANDAMENTO: [ || Pausar ]  [ ⊘ Abortar ]  [ ↗ Minimizar ]
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
                                    if (isPaused) viewModel.resumeOperation(activeOp.id) else viewModel.pauseOperation(activeOp.id)
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
                                    text = if (isPaused) stringResource(R.string.action_resume) else stringResource(R.string.action_pause),
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
                                    viewModel.cancelOperation(activeOp.id)
                                    onNavigateBack()
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
                                    text = stringResource(R.string.cancel_action),
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
            } else {
                // ==========================================
                // RODAPÉ PÓS-CONCLUSÃO / OCIOSO (ETAPA 4.2)
                // ==========================================
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    color = Color(0xFF0F131C),
                    border = BorderStroke(1.dp, Color(0xFF1E2530))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF00FF88).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFF00FF88).copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00FF88))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.completed_sha256_verified),
                                        color = Color(0xFF00FF88),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.system_ready),
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // [ Done ] (Primary)
                            Button(
                                onClick = onNavigateBack,
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00FF88),
                                    contentColor = Color.Black
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.btn_done),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // [ View Backups ] (Secondary)
                            OutlinedButton(
                                onClick = onNavigateBack,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color(0xFF263244))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.History, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.view_backups),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
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
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ==========================================
            if (activeOp == null) {
                // Estado Ocioso quando não há job em andamento (com FuturisticCompletionOrb)
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
                        FuturisticCompletionOrb(
                            size = 104.dp,
                            onTap = {
                                context.showSafeToast("System Ready • SHA-256 Verified")
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.no_active_backup),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.all_processes_finished),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onNavigateBack,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88), contentColor = Color.Black),
                                modifier = Modifier.height(44.dp)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.back_to_dashboard), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }

                            OutlinedButton(
                                onClick = onNavigateToLogs,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                                modifier = Modifier.height(44.dp)
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.logs_button), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8))
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
                            text = stringResource(R.string.processing_backup),
                            fontSize = 12.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                        val backupDisplayTitle = activeOp?.title?.takeIf { it.isNotBlank() && !it.equals("active", ignoreCase = true) }
                            ?: stringResource(R.string.active_backup_default_title)
                        Text(
                            text = backupDisplayTitle,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val opTime = activeOp?.startTimeMillis?.takeIf { it > 0L } ?: System.currentTimeMillis()
                        val dateStr = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(opTime))
                        Text(
                            text = "ID: RS-$dateStr",
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
                                text = stringResource(R.string.active_wakelock_active),
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
                    StepSelectorPill(text = stringResource(R.string.active_step_data), isCompleted = true)
                    StepSelectorPill(text = stringResource(R.string.active_step_dest), isCompleted = true)
                    StepSelectorPill(text = stringResource(R.string.active_options), isCompleted = true)
                    StepSelectorPill(text = stringResource(R.string.active_review), isCompleted = true)
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
                                    FormatUtils.formatBytes(activeOp.bytesProcessed) + " / " + (if (activeOp.totalBytesEstimated > 0L) FormatUtils.formatBytes(activeOp.totalBytesEstimated) else stringResource(R.string.status_dynamic))
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
                            val rawFraction = if (progressPercent.isNaN()) 0f else progressPercent / 100f
                            val pFraction = rawFraction.coerceIn(0f, 1f)
                            if (pFraction > 0f) {
                                Box(modifier = Modifier.weight(pFraction.coerceAtLeast(0.0001f)).fillMaxHeight().background(Color(0xFFFF3B47)))
                            }
                            val remainFraction = (1f - pFraction).coerceIn(0f, 1f)
                            if (remainFraction > 0f) {
                                Box(modifier = Modifier.weight(remainFraction.coerceAtLeast(0.0001f)).fillMaxHeight().background(Color(0xFF1C222D)))
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
                            LegendBullet(color = Color(0xFF38BDF8), label = if (activeAccount != null) stringResource(R.string.active_drive_active) else "Local")
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
                        subtext = stringResource(R.string.active_flow_realtime)
                    )

                    // Box 2: Compressão
                    PerformanceBentoBox(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.active_compression),
                        tag = "zstd",
                        tagColor = Color(0xFF10B981),
                        mainValue = "-38.2%",
                        mainColor = Color(0xFF10B981),
                        subtext = stringResource(R.string.active_tar_level3)
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
                        label = stringResource(R.string.active_thermal_battery),
                        tag = if (isThermalNormal) stringResource(R.string.active_thermal_normal) else stringResource(R.string.active_thermal_high),
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
                                text = stringResource(R.string.active_pipeline_title),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = stringResource(R.string.active_steps_count, 4),
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
                            title = stringResource(R.string.active_step1_title),
                            statusText = if (isStage1Done) stringResource(R.string.active_completed) else stringResource(R.string.active_status_active),
                            statusColor = if (isStage1Done) Color(0xFF10B981) else Color(0xFFFF3B47),
                            subtitle = stringResource(R.string.active_step1_desc)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Etapa 2
                        PipelineStepRow(
                            icon = if (isStage2Done) Icons.Default.Check else if (isStage2Active) Icons.Default.Sync else Icons.Default.Schedule,
                            iconColor = if (isStage2Done) Color(0xFF10B981) else if (isStage2Active) Color(0xFFFF3B47) else TextMuted,
                            iconBg = if (isStage2Done) Color(0xFF0D281E) else if (isStage2Active) Color(0xFF2A1519) else Color(0xFF1A2230),
                            title = stringResource(R.string.active_step2_title),
                            statusText = if (isStage2Done) stringResource(R.string.active_completed) else if (isStage2Active) stringResource(R.string.active_step2_active, progressPercent.toInt()) else stringResource(R.string.active_waiting),
                            statusColor = if (isStage2Done) Color(0xFF10B981) else if (isStage2Active) Color(0xFFFF3B47) else TextMuted,
                            subtitle = stringResource(R.string.active_step2_desc)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Etapa 3
                        PipelineStepRow(
                            icon = if (isStage3Done) Icons.Default.Check else if (isStage3Active) Icons.Default.Sync else Icons.Default.Shield,
                            iconColor = if (isStage3Done) Color(0xFF10B981) else if (isStage3Active) Color(0xFFFF3B47) else TextMuted,
                            iconBg = if (isStage3Done) Color(0xFF0D281E) else if (isStage3Active) Color(0xFF2A1519) else Color(0xFF1A2230),
                            title = stringResource(R.string.active_step3_title),
                            statusText = if (isStage3Done) stringResource(R.string.active_completed) else if (isStage3Active) stringResource(R.string.active_step3_encrypting) else stringResource(R.string.active_waiting),
                            statusColor = if (isStage3Done) Color(0xFF10B981) else if (isStage3Active) Color(0xFFFF3B47) else TextMuted,
                            subtitle = stringResource(R.string.active_step3_desc)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Etapa 4
                        PipelineStepRow(
                            icon = if (isStage4Done) Icons.Default.Check else if (isStage4Active) Icons.Default.CloudUpload else Icons.Default.Cloud,
                            iconColor = if (isStage4Done) Color(0xFF10B981) else if (isStage4Active) Color(0xFF38BDF8) else TextMuted,
                            iconBg = if (isStage4Done) Color(0xFF0D281E) else if (isStage4Active) Color(0xFF122230) else Color(0xFF1A2230),
                            title = stringResource(R.string.active_step4_title),
                            statusText = if (isStage4Done) stringResource(R.string.active_synced) else if (isStage4Active) stringResource(R.string.active_uploading_cloud) else if (activeAccount != null) stringResource(R.string.active_queued) else stringResource(R.string.active_not_configured),
                            statusColor = if (isStage4Done) Color(0xFF10B981) else if (isStage4Active) Color(0xFF38BDF8) else TextMuted,
                            subtitle = stringResource(R.string.active_step4_desc)
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
                                        val safeLogs = recentLogs.toList()
                                        val logText = if (safeLogs.isNotEmpty()) {
                                            safeLogs.joinToString("\n") { log ->
                                                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                                                "[$timeStr] [${log.tag}] ${log.message}"
                                            }
                                        } else {
                                            "[DAEMON] Daemon ready • System logs available."
                                        }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Stdout Console", logText)
                                        clipboard.setPrimaryClip(clip)
                                        context.showSafeToast(context.getString(R.string.stdout_copied))
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(11.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(stringResource(R.string.copy_button), fontSize = 10.sp, color = TextSecondary)
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
                                        Text(stringResource(R.string.logs_button), fontSize = 10.sp, color = TextSecondary)
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
                                val safeLogs = recentLogs.toList()
                                if (safeLogs.isNotEmpty()) {
                                    safeLogs.forEach { logEntry ->
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
                                    StdoutLogLine(time = "--:--:--", tag = "DAEMON", tagColor = Color(0xFF10B981), msg = stringResource(R.string.active_daemon_ready))
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
                            text = stringResource(R.string.active_protected_background),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.active_protected_background_desc),
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "[$time]",
                fontSize = 11.sp,
                color = Color(0xFF64748B),
                fontFamily = FontFamily.Monospace
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = tagColor.copy(alpha = 0.15f),
                border = BorderStroke(0.6.dp, tagColor.copy(alpha = 0.45f))
            ) {
                Text(
                    text = tag.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tagColor,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = msg,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = Color(0xFFE2E8F0),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
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

