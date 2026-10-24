package com.resync.app.ui.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.resync.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.resync.app.data.model.ThemeAccent
import com.resync.app.engine.CapabilityManager
import com.resync.app.ui.components.cards.InfoRow
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.updater.UpdateState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToDns: () -> Unit,
    onNavigateToInitialSetup: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val capabilityManager = remember { CapabilityManager(context) }
    val currentAccent by viewModel.themeAccent.collectAsState()
    val activeMode by viewModel.activeMode.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshAllPermissions()
        }
    }

    val isAutoCheck by viewModel.isAutoUpdateCheckEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val installedVersionName = remember { viewModel.appUpdateManager.getInstalledVersionName() }
    val installedVersionCode = remember { viewModel.appUpdateManager.getInstalledVersionCode() }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Configurações",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 1. TEMA VISUAL
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tema Visual & Destaques", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (accent in ThemeAccent.values()) {
                            val color = getAccentColor(accent)
                            val isSelected = accent == currentAccent
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.setThemeAccent(accent) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. SEÇÕES RÁPIDAS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    SettingNavRow(
                        title = "Permissões do Sistema",
                        subtitle = "Controle de acessos a arquivos, contatos e chamadas",
                        icon = Icons.Default.Security,
                        onClick = onNavigateToPermissions
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SettingNavRow(
                        title = "DNS, Wi-Fi e consumo de dados",
                        subtitle = "Verificar DNS privado, redes Wi-Fi salvas e consumo de internet",
                        icon = Icons.Default.Dns,
                        onClick = onNavigateToDns
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SettingNavRow(
                        title = "Assistente de Configuração Inicial",
                        subtitle = "Executar novamente o fluxo de boas-vindas e checagem",
                        icon = Icons.Default.AutoFixHigh,
                        onClick = onNavigateToInitialSetup
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SELEÇÃO E PACOTES DO SISTEMA
            val showInternalPkgs by viewModel.showInternalSystemPackages.collectAsStateWithLifecycle()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Layers, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pacotes do Sistema", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Mostrar pacotes internos do sistema", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text("Exibe componentes sem interface, sandboxes (SDK Sandbox) e overlays de recursos na seleção", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = showInternalPkgs,
                            onCheckedChange = { viewModel.setShowInternalSystemPackages(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = primaryAccent)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. ATUALIZAÇÕES DO APLICATIVO
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Atualizações do Aplicativo", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Verificar ao abrir", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text("Consulta a última versão no GitHub ao iniciar o app", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = isAutoCheck,
                            onCheckedChange = { viewModel.toggleAutoUpdateCheck(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = primaryAccent)
                        )
                    }

                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Versão Atual: v$installedVersionName (teste de atualização)", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text("Build $installedVersionCode", fontSize = 11.sp, color = TextSecondary)
                        }
                        Button(
                            onClick = { viewModel.checkForUpdateManual() },
                            enabled = updateState !is UpdateState.Checking,
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            if (updateState is UpdateState.Checking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                            } else {
                                Text("Verificar agora", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (updateState is UpdateState.UpToDate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✓ ReSync já está na versão mais recente (v$installedVersionName).",
                            color = StatusSuccess,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (updateState is UpdateState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = (updateState as UpdateState.Error).message,
                            color = StatusError,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. INFORMAÇÕES DO APLICATIVO
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.logo_resync),
                            contentDescription = "ReSync",
                            modifier = Modifier.size(40.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("ReSync", fontWeight = FontWeight.ExtraBold, color = TextPrimary, fontSize = 16.sp)
                            Text("Versão $installedVersionName (teste de atualização) — API 35 (Android 17 Ready)", fontSize = 12.sp, color = primaryAccent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    InfoRow("Motor Criptográfico", "AES-256-GCM Hardware-Backed", StatusSuccess)
                    InfoRow("Validação de Integridade", "SHA-256 e MD5 Hash", StatusSuccess)
                    InfoRow("Nuvem Suportada", "Exclusivamente Google Drive", AccentElectricCyan)
                    InfoRow("Invariância de Fluxo", "Kotlin channelFlow Auditado", StatusSuccess)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SettingNavRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(imageVector = icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, color = TextSecondary)
            }
        }
        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
    }
}

