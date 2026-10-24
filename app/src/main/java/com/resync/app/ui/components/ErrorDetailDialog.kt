package com.resync.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.resync.app.ui.theme.*
import com.resync.app.util.ErrorDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ErrorDetailDialog(
    errorDetail: ErrorDetail,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val formattedTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.getDefault()).format(Date(errorDetail.timestamp))

    val fullLogText = buildString {
        appendLine("=== DETALHES DO ERRO - RESYNC ===")
        appendLine("Data/Hora: $formattedTime")
        appendLine("Módulo: ${errorDetail.module}")
        appendLine("Operação: ${errorDetail.operation}")
        appendLine("Exceção: ${errorDetail.exceptionType ?: "N/A"}")
        appendLine("Mensagem: ${errorDetail.message}")
        if (errorDetail.rootCauseMessage != null) appendLine("Causa Raiz: ${errorDetail.rootCauseMessage}")
        if (errorDetail.httpCode != null) appendLine("Código HTTP: ${errorDetail.httpCode}")
        if (errorDetail.httpUrl != null) appendLine("URL: ${errorDetail.httpUrl}")
        if (!errorDetail.httpResponseBody.isNullOrBlank()) appendLine("Resposta HTTP:\n${errorDetail.httpResponseBody}")
        if (errorDetail.networkState != null) appendLine("Rede: ${errorDetail.networkState}")
        if (errorDetail.thermalState != null) appendLine("Térmico: ${errorDetail.thermalState}")
        if (errorDetail.extraParams.isNotEmpty()) {
            appendLine("Parâmetros:")
            errorDetail.extraParams.forEach { (k, v) -> appendLine("  - $k: $v") }
        }
        if (!errorDetail.deviceContext.isNullOrBlank()) {
            appendLine("Contexto do Dispositivo:")
            appendLine(errorDetail.deviceContext)
        }
        if (!errorDetail.stackTrace.isNullOrBlank()) {
            appendLine("Stacktrace Completo:")
            appendLine(errorDetail.stackTrace)
        }
        appendLine("=================================")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, StatusError.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Detalhes Técnicos da Falha",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "${errorDetail.module} • $formattedTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkBg)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = fullLogText,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("ReSync Error Details", fullLogText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Log copiado para a área de transferência", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copiar Log", fontSize = 13.sp)
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Fechar", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
