package com.resync.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.resync.app.data.model.LogEntry
import com.resync.app.data.model.LogLevel
import com.resync.app.ui.components.ErrorDetailDialog
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.AppLogger
import com.resync.app.util.ErrorDetail
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val systemLogs by viewModel.systemLogs.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var searchQuery by remember { mutableStateOf("") }
    var selectedLevelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var selectedErrorDetail by remember { mutableStateOf<ErrorDetail?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Filter logs based on search text and level
    val filteredLogs = remember(systemLogs, selectedLevelFilter, searchQuery) {
        systemLogs.filter { log ->
            val matchesLevel = selectedLevelFilter == null || log.level == selectedLevelFilter
            val matchesQuery = searchQuery.isBlank() ||
                    log.message.contains(searchQuery, ignoreCase = true) ||
                    log.tag.contains(searchQuery, ignoreCase = true) ||
                    (log.details?.contains(searchQuery, ignoreCase = true) == true)
            matchesLevel && matchesQuery
        }
    }

    fun copyLogsToClipboard() {
        val textToCopy = if (filteredLogs.isNotEmpty()) {
            filteredLogs.joinToString("\n") { log ->
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
                "[$dateStr] [${log.level}] [${log.tag}] ${log.message}${if (!log.details.isNullOrBlank()) "\nDetalhes: ${log.details}" else ""}"
            }
        } else {
            AppLogger.getCombinedLogContent()
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ReSync Logs", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Logs copiados para a área de transferência", Toast.LENGTH_SHORT).show()
    }

    fun exportAndShareLogs() {
        try {
            val content = AppLogger.getCombinedLogContent()
            val exportDir = File(context.cacheDir, "logs_export").apply { mkdirs() }
            val exportFile = File(exportDir, "resync_system_logs_${System.currentTimeMillis()}.txt")
            exportFile.writeText(content)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ReSync - Logs do Sistema")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Exportar Logs"))
        } catch (e: Exception) {
            Toast.makeText(context, "Falha ao exportar logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Logs do Sistema",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { copyLogsToClipboard() }) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copiar Logs", tint = TextSecondary)
                        }
                        IconButton(onClick = { exportAndShareLogs() }) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Compartilhar", tint = TextSecondary)
                        }
                        IconButton(onClick = { showClearConfirmation = true }) {
                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Limpar", tint = StatusError)
                        }
                        IconButton(onClick = { viewModel.loadData() }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Atualizar", tint = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    placeholder = { Text("Buscar em mensagens, módulos, erros...", fontSize = 13.sp, color = TextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar busca", tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryAccent,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Level filter buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val levels = listOf(null, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG)
                    for (lvl in levels) {
                        val isSelected = lvl == selectedLevelFilter
                        val label = lvl?.name ?: "Todos"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedLevelFilter = lvl }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) primaryAccent else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredLogs.size} registros encontrados",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Text(
                        text = "Toque em um erro para ver detalhes",
                        style = MaterialTheme.typography.bodySmall,
                        color = primaryAccent,
                        fontSize = 11.sp
                    )
                }
            }

            items(filteredLogs, key = { it.id }) { log ->
                val dateFormatted = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
                val levelColor = when (log.level) {
                    LogLevel.INFO -> StatusInfo
                    LogLevel.WARN -> StatusWarning
                    LogLevel.ERROR -> StatusError
                    LogLevel.DEBUG -> TextMuted
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            1.dp,
                            if (log.level == LogLevel.ERROR) StatusError.copy(alpha = 0.4f) else DarkCardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            if (log.level == LogLevel.ERROR || !log.details.isNullOrBlank()) {
                                selectedErrorDetail = ErrorDetail(
                                    errorId = "err_${log.id}",
                                    timestamp = log.timestamp,
                                    module = log.tag,
                                    operation = "Operação de Sistema",
                                    message = log.message,
                                    rootCauseMessage = null,
                                    exceptionType = null,
                                    stackTrace = log.details
                                )
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(levelColor.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = log.level.name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = levelColor
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = log.tag,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }

                            Text(
                                text = dateFormatted,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = log.message,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )

                        if (!log.details.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = log.details.take(120) + if (log.details.length > 120) "..." else "",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        selectedErrorDetail = ErrorDetail(
                                            errorId = "err_${log.id}",
                                            timestamp = log.timestamp,
                                            module = log.tag,
                                            operation = "Visualização de Falha",
                                            message = log.message,
                                            rootCauseMessage = null,
                                            exceptionType = null,
                                            stackTrace = log.details
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Ver detalhes", fontSize = 11.sp, color = primaryAccent)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    // Error Detail Dialog
    val currentDetail = selectedErrorDetail
    if (currentDetail != null) {
        ErrorDetailDialog(
            errorDetail = currentDetail,
            onDismiss = { selectedErrorDetail = null }
        )
    }

    // Confirmation dialog for clearing logs
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Limpar Logs", color = TextPrimary) },
            text = { Text("Deseja apagar todos os registros de log do sistema e do arquivo de diagnóstico?", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        AppLogger.clearLogs()
                        viewModel.loadData()
                        showClearConfirmation = false
                        Toast.makeText(context, "Logs apagados com sucesso", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("Limpar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
