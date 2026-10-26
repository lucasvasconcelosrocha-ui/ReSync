package com.resync.app.ui.screens

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.resync.app.engine.*
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduledBackupsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scheduleManager = remember { ScheduleManager.getInstance(context) }
    val globalConfig by scheduleManager.globalConfig.collectAsStateWithLifecycle()
    val schedules by scheduleManager.schedules.collectAsStateWithLifecycle()

    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)

    var showCreateEditDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<BackupScheduleItem?>(null) }
    var scheduleToDelete by remember { mutableStateOf<BackupScheduleItem?>(null) }
    var showBatteryReqDialog by remember { mutableStateOf(false) }

    fun openTimePicker(initialTime: String, onTimeSelected: (String) -> Unit) {
        val parts = initialTime.split(":")
        val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val initialMin = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                val formatted = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                onTimeSelected(formatted)
            },
            initialHour,
            initialMin,
            true
        ).show()
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Backups agendados",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                IconButton(
                    onClick = {
                        editingSchedule = null
                        showCreateEditDialog = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Novo agendamento", tint = primaryAccent)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ==========================================
            // 1) CARD "CONTROLES DE AGENDAMENTO"
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Controles de agendamento",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Interruptor geral
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { scheduleManager.setMasterEnabled(!globalConfig.isMasterEnabled) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backups agendados", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                text = "Executar rotinas em segundo plano periodicamente",
                                fontSize = 11.5.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = globalConfig.isMasterEnabled,
                            onCheckedChange = { scheduleManager.setMasterEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = primaryAccent, checkedTrackColor = primaryAccent.copy(alpha = 0.3f))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Hora de Início
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openTimePicker(globalConfig.startTime) { newTime ->
                                    scheduleManager.setStartTime(newTime)
                                    Toast.makeText(context, "Hora de início atualizada para $newTime", Toast.LENGTH_SHORT).show()
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Hora de início", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Horário preferencial de disparo", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBg)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = globalConfig.startTime,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryAccent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Requisitos de Bateria
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBatteryReqDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = AccentEmeraldGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Requisitos de bateria", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Condição mínima de carga", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBg)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = globalConfig.batteryRequirement.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Botão Executar Todos os Agendamentos
                    Button(
                        onClick = {
                            scheduleManager.runAllSchedulesNow()
                            Toast.makeText(context, "Executando agendamentos em segundo plano...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Executar todos os agendamentos", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 2) LISTA DE AGENDAMENTOS OU ESTADO VAZIO
            // ==========================================
            Text(
                text = "ROTINAS CADASTRADAS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
            )

            if (schedules.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(primaryAccent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(32.dp))
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Nenhum agendamento adicionado",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Crie uma rotina automática para salvar seus apps, mensagens e fotos periodicamente.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = {
                                editingSchedule = null
                                showCreateEditDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Novo agendamento", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            } else {
                schedules.forEach { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, if (item.isEnabled) primaryAccent.copy(alpha = 0.4f) else DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (item.isEnabled) primaryAccent.copy(alpha = 0.2f) else DarkBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Alarm,
                                            contentDescription = null,
                                            tint = if (item.isEnabled) primaryAccent else TextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = item.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Horário: ${item.time} • Destino: ${item.destination}",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                Switch(
                                    checked = item.isEnabled,
                                    onCheckedChange = { scheduleManager.toggleSchedule(item.id, it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = primaryAccent, checkedTrackColor = primaryAccent.copy(alpha = 0.3f))
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Chips de alvos incluídos usando FlowRow para não cortar
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                item.targetItems.forEach { target ->
                                    val (label, color) = when (target) {
                                        "APPS" -> Pair("Aplicativos", AccentElectricCyan)
                                        "MESSAGES" -> Pair("Mensagens", primaryAccent)
                                        "CALLS" -> Pair("Chamadas", AccentEmeraldGreen)
                                        "WIFI" -> Pair("Wi-Fi", AccentSunsetOrange)
                                        "WALLPAPER" -> Pair("Papéis de parede", AccentBrightAmber)
                                        "BLUETOOTH" -> Pair("Bluetooth", AccentNeonPurple)
                                        "FOLDERS" -> Pair("Pastas", Color(0xFF4FC3F7))
                                        else -> Pair(target, TextSecondary)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(color.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(8.dp))

                            val nextRunTimestamp = remember(item) { scheduleManager.calculateNextRun(item) }
                            val nextRunStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(nextRunTimestamp))
                            val lastRunText = if (item.lastRunTimestamp > 0L) {
                                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.lastRunTimestamp))
                                "Último: $dateStr (${item.lastRunStatus})"
                            } else {
                                item.lastRunStatus
                            }

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                        Text(
                                            text = "Próxima: $nextRunStr",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = primaryAccent
                                        )
                                        Text(
                                            text = lastRunText,
                                            fontSize = 10.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            scheduleManager.runScheduleNow(item.id)
                                            Toast.makeText(context, "Executando '${item.title}' agora...", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Executar agora", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    IconButton(
                                        onClick = {
                                            editingSchedule = item
                                            showCreateEditDialog = true
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = primaryAccent, modifier = Modifier.size(15.dp))
                                    }
                                    IconButton(
                                        onClick = { scheduleToDelete = item },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = StatusError, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Diálogo de Seleção de Requisito de Bateria
    if (showBatteryReqDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryReqDialog = false },
            title = { Text("Requisitos de bateria", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    BatteryRequirement.values().forEach { req ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scheduleManager.setBatteryRequirement(req)
                                    showBatteryReqDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = globalConfig.batteryRequirement == req,
                                onClick = {
                                    scheduleManager.setBatteryRequirement(req)
                                    showBatteryReqDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(req.label, fontSize = 13.5.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatteryReqDialog = false }) {
                    Text("Fechar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary
        )
    }

    // Diálogo Criar / Editar Agendamento
    if (showCreateEditDialog) {
        val initial = editingSchedule ?: BackupScheduleItem()
        var title by remember { mutableStateOf(initial.title) }
        var time by remember { mutableStateOf(initial.time) }
        var destination by remember { mutableStateOf(initial.destination) }
        var selectedTargets by remember { mutableStateOf(initial.targetItems.toSet()) }

        val availableTargets = listOf(
            "APPS" to "Aplicativos",
            "MESSAGES" to "Mensagens",
            "CALLS" to "Histórico de Chamadas",
            "WIFI" to "Redes Wi-Fi",
            "WALLPAPER" to "Papéis de Parede",
            "BLUETOOTH" to "Bluetooth",
            "FOLDERS" to "Pastas do Armazenamento"
        )

        AlertDialog(
            onDismissRequest = { showCreateEditDialog = false },
            title = { Text(if (editingSchedule == null) "Novo agendamento" else "Editar agendamento", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Nome da rotina") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openTimePicker(time) { newT -> time = newT }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Horário de execução:", fontSize = 13.sp, color = TextPrimary)
                        Text(time, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Destino do backup:", fontSize = 12.sp, color = TextSecondary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Dispositivo", "Nuvem", "Ambos").forEach { dest ->
                            FilterChip(
                                selected = destination == dest,
                                onClick = { destination = dest },
                                label = { Text(dest, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Itens incluídos no backup:", fontSize = 12.sp, color = TextSecondary)
                    availableTargets.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTargets = if (selectedTargets.contains(key)) selectedTargets - key else selectedTargets + key
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedTargets.contains(key),
                                onCheckedChange = { checked ->
                                    selectedTargets = if (checked) selectedTargets + key else selectedTargets - key
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(label, fontSize = 12.5.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newItem = initial.copy(
                            title = title.ifBlank { "Backup Agendado" },
                            time = time,
                            destination = destination,
                            targetItems = selectedTargets.toList()
                        )
                        scheduleManager.saveSchedule(newItem)
                        showCreateEditDialog = false
                        Toast.makeText(context, "Agendamento salvo!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Salvar", color = primaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateEditDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary
        )
    }

    // Diálogo de confirmação para exclusão
    if (scheduleToDelete != null) {
        val target = scheduleToDelete!!
        AlertDialog(
            onDismissRequest = { scheduleToDelete = null },
            title = { Text("Excluir agendamento?", fontWeight = FontWeight.Bold) },
            text = { Text("Deseja realmente apagar a rotina '${target.title}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scheduleManager.deleteSchedule(target.id)
                        scheduleToDelete = null
                        Toast.makeText(context, "Agendamento removido.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Excluir", color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { scheduleToDelete = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary
        )
    }
}
