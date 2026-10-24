package com.resync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.updater.UpdateState
import java.io.File
import java.util.Locale

@Composable
fun AppUpdateDialog(
    viewModel: MainViewModel,
    primaryAccent: Color
) {
    val updateState by viewModel.updateState.collectAsState()

    if (updateState == UpdateState.Idle || updateState is UpdateState.UpToDate) {
        return
    }

    var preferSilentRoot by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = {
            if (updateState !is UpdateState.Downloading && updateState !is UpdateState.Installing) {
                viewModel.dismissUpdatePrompt()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = updateState !is UpdateState.Downloading && updateState !is UpdateState.Installing,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, primaryAccent.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .fillMaxWidth()
            ) {
                when (val state = updateState) {
                    UpdateState.Idle, is UpdateState.UpToDate -> Unit

                    is UpdateState.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = primaryAccent,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Consultando atualizações no GitHub...",
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                        }
                    }

                    is UpdateState.Available -> {
                        val release = state.releaseInfo
                        val sizeMb = release.apkSize.toDouble() / (1024.0 * 1024.0)
                        val formattedSize = if (sizeMb > 0) String.format(Locale.US, "%.1f MB", sizeMb) else "Tamanho não especificado"

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = primaryAccent,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Nova atualização disponível",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "${release.tag} (Build ${release.versionCode}) • $formattedSize",
                                    color = primaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Notas da Versão:",
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = release.releaseNotes.ifBlank { "Melhorias de desempenho e estabilidade." },
                                color = TextPrimary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Opção de instalação Root se disponível
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = preferSilentRoot,
                                onCheckedChange = { preferSilentRoot = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = primaryAccent,
                                    uncheckedColor = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Instalar silenciosamente (Root)",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (preferSilentRoot)
                                        "Usa 'pm install -r' e reinicia o app automaticamente."
                                    else
                                        "O Android exibirá a confirmação do instalador padrão.",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.dismissUpdatePrompt() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Agora não", fontSize = 13.sp)
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = { viewModel.startUpdateDownload(release) },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Atualizar agora", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    is UpdateState.Downloading -> {
                        val release = state.releaseInfo
                        val percent = (state.progress * 100).toInt()
                        val downloadedMb = state.downloadedBytes.toDouble() / (1024.0 * 1024.0)
                        val totalMb = state.totalBytes.toDouble() / (1024.0 * 1024.0)

                        Text(
                            text = "Baixando atualização...",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${release.apkName} • ${release.tag}",
                            color = primaryAccent,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = primaryAccent,
                            trackColor = DarkSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$percent% • ${String.format(Locale.US, "%.2f MB/s", state.speedMbPerSec)}",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.1f", downloadedMb)} / ${String.format(Locale.US, "%.1f MB", totalMb)}",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Retomada automática ativa em caso de instabilidade na conexão.",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }

                    is UpdateState.Verifying -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = primaryAccent,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Conferência de Segurança",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = state.step,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    is UpdateState.ReadyToInstall -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusSuccess,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Download verificado com sucesso",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "SHA-256 e assinatura validados.",
                                    color = StatusSuccess,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (state.isRootAvailable && preferSilentRoot)
                                "O ReSync será atualizado silenciosamente via Root (pm install -r) e reiniciado automaticamente."
                            else
                                "O Android exibirá a confirmação do instalador de pacotes para concluir a atualização.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.dismissUpdatePrompt() },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Mais tarde", fontSize = 13.sp, color = TextSecondary)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Button(
                                onClick = { viewModel.installDownloadedUpdate(state.apkFile, preferSilentRoot) },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Instalar agora", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    is UpdateState.Installing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = primaryAccent,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Instalando atualização...",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (state.isRoot) "Executando via Root silencioso (pm install -r)..." else "Aguardando instalador do sistema...",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    is UpdateState.BlockedByOperation -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusWarning,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Atualização temporariamente bloqueada",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = state.reason,
                                    color = StatusWarning,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { viewModel.dismissUpdatePrompt() },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Entendido", color = TextPrimary, fontSize = 13.sp)
                        }
                    }

                    is UpdateState.Error -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = StatusError,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Aviso de Atualização",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = state.message,
                                    color = StatusError,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (!state.technicalCode.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Detalhes: ${state.technicalCode}",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { viewModel.dismissUpdatePrompt() },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Fechar", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

