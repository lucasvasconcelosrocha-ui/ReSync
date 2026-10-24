package com.resync.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.R
import com.resync.app.data.model.ActiveNetworkType
import com.resync.app.data.model.BluetoothDeviceInfo
import com.resync.app.data.model.BluetoothHistoryEvent
import com.resync.app.engine.DnsManager
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.components.cards.InfoRow
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun DnsSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val dnsConfig by viewModel.dnsConfig.collectAsStateWithLifecycle()
    val wifiDetails by viewModel.wifiDetails.collectAsStateWithLifecycle()
    val activeNetwork by viewModel.activeNetworkDetails.collectAsStateWithLifecycle()
    val bluetoothDevices by viewModel.bluetoothDevices.collectAsStateWithLifecycle()
    val bluetoothHistory by viewModel.bluetoothHistory.collectAsStateWithLifecycle()
    val deviceSpec by viewModel.deviceHardwareSpec.collectAsStateWithLifecycle()

    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    // Abas: 0: DNS Privado, 1: Rede, 2: Bluetooth, 3: Dispositivo
    var selectedTab by remember { mutableIntStateOf(0) }
    var copiedNotice by remember { mutableStateOf(false) }

    // Bluetooth UI states
    var btSearchQuery by remember { mutableStateOf("") }
    var selectedBtDeviceForDetails by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportDialogTitle by remember { mutableStateOf("") }
    var exportDialogContent by remember { mutableStateOf("") }
    var editingAliasDevice by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }
    var aliasInputText by remember { mutableStateOf("") }

    // Start/Stop monitoring based on screen lifecycle and selected tab
    DisposableEffect(Unit) {
        viewModel.refreshNetworkAndWifi()
        viewModel.refreshBluetoothData()
        viewModel.refreshDeviceHardwareSpec()
        viewModel.startRealtimeTrafficAndHardwareMonitoring()
        onDispose {
            viewModel.stopRealtimeMonitoring()
        }
    }

    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> viewModel.refreshNetworkAndWifi()
            2 -> viewModel.refreshBluetoothData()
            3 -> viewModel.refreshDeviceHardwareSpec()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshNetworkAndWifi()
        viewModel.refreshBluetoothData()
    }

    val formattedDnsSummary = remember(dnsConfig) {
        DnsManager(context).formatDnsBackupSummary(dnsConfig)
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
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DNS, Wi-Fi e consumo de dados",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 2,
                        softWrap = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(24.dp),
                                ambientColor = Color.Black.copy(alpha = 0.5f),
                                spotColor = primaryAccent.copy(alpha = 0.25f)
                            ),
                        shape = RoundedCornerShape(24.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tabs = listOf(
                                Triple(0, "DNS", Icons.Default.Dns),
                                Triple(1, "Rede", Icons.Default.Wifi),
                                Triple(2, "Bluetooth", Icons.Default.Bluetooth),
                                Triple(3, "Dispositivo", Icons.Default.PhoneAndroid)
                            )
                            tabs.forEach { (index, title, icon) ->
                                val isSelected = selectedTab == index

                                val itemContentColor by animateColorAsState(
                                    targetValue = if (isSelected) primaryAccent else TextMuted,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "dnsTabContent_$index"
                                )

                                val indicatorBg by animateColorAsState(
                                    targetValue = if (isSelected) primaryAccent.copy(alpha = 0.16f) else Color.Transparent,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "dnsTabInd_$index"
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(indicatorBg)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(bounded = true, color = primaryAccent),
                                            onClick = { selectedTab = index }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = title,
                                            tint = itemContentColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = title,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = itemContentColor,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val backupProgressState by viewModel.backupProgress.collectAsStateWithLifecycle()
        val isBackupRunning = backupProgressState != null && !backupProgressState!!.isFinished

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (isBackupRunning) 96.dp else 16.dp)
        ) {
            when (selectedTab) {
                0 -> {
                    // TAB 0: DNS PRIVADO
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Dns, contentDescription = null, tint = AccentSunsetOrange)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Status do DNS Privado", fontWeight = FontWeight.Bold, color = TextPrimary)
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (dnsConfig.isActive) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (dnsConfig.isActive) "● Ativo" else "○ Inativo",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (dnsConfig.isActive) StatusSuccess else StatusWarning
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            InfoRow("Modo Detectado", dnsConfig.mode)
                            InfoRow("Hostname do Provedor", dnsConfig.hostname ?: "Automático / Nenhum")
                            InfoRow("Servidores Ativos", if (dnsConfig.dnsServers.isNotEmpty()) dnsConfig.dnsServers.joinToString(", ") else "DNS Padrão do Roteador")
                            InfoRow("Interface Ativa", dnsConfig.interfaceName ?: "wlan0 / rede móvel")
                            InfoRow("Status VPN", if (dnsConfig.isVpnActive) "Ativa" else "Inativa")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Visualização da exportação
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Visualização da Exportação", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkBg)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = formattedDnsSummary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = AccentElectricCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.launchBackup(
                                title = "Backup de Configuração DNS",
                                selectedApps = emptyList(),
                                selectedFiles = emptyList(),
                                includeSms = false,
                                includeCalls = false,
                                includeDns = true,
                                includeWallpaper = false
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fazer Backup do DNS", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                viewModel.copyDnsHost()
                                copiedNotice = true
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (copiedNotice) "Copiado!" else "Copiar Host", color = TextPrimary, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(viewModel.getOpenDnsSettingsIntent())
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Configurações", color = primaryAccent, fontSize = 12.sp)
                        }
                    }
                }

                1 -> {
                    // TAB 1: REDE (WI-FI E DADOS MÓVEIS)

                    // Se permissões estiverem ausentes, mostrar cartão explicativo
                    if (activeNetwork.requiresLocationPermission || activeNetwork.requiresPhonePermission) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, StatusWarning.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.1f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Permissões Necessárias", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Para inspecionar o nome exato da rede Wi-Fi (SSID), canal, sinal e as especificações da operadora/SIM, conceda as permissões de localização e telefone.",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    lineHeight = 15.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.READ_PHONE_STATE
                                            )
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(38.dp)
                                ) {
                                    Text(stringResource(R.string.grant_permission), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Card Wi-Fi
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, if (activeNetwork.type == ActiveNetworkType.WIFI) AccentEmeraldGreen.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = AccentEmeraldGreen, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Rede Wi-Fi", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                                }

                                Surface(
                                    color = if (wifiDetails.isConnected) StatusSuccess.copy(alpha = 0.18f) else DarkSurface,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (wifiDetails.isConnected) "● Conectado" else "○ Desconectado",
                                        color = if (wifiDetails.isConnected) StatusSuccess else TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            if (wifiDetails.isConnected) {
                                InfoRow("SSID", wifiDetails.ssid)
                                InfoRow("BSSID (MAC)", wifiDetails.bssid)
                                InfoRow("Padrão", wifiDetails.standard)
                                InfoRow("Banda & Canal", "${wifiDetails.frequencyGhz} (Canal ${if (wifiDetails.channel > 0) wifiDetails.channel else "Automático"})")
                                InfoRow("Velocidade do Link", "${wifiDetails.linkSpeedMbps} Mbps")
                                InfoRow("Sinal", "${wifiDetails.signalDbm} dBm (Nível ${wifiDetails.signalLevel}/4)")
                                InfoRow("Endereço IP", wifiDetails.ipAddress)
                                InfoRow("Gateway", wifiDetails.gateway)
                                InfoRow("Servidores DNS", if (wifiDetails.dnsServers.isNotEmpty()) wifiDetails.dnsServers.joinToString(", ") else "Indisponível")
                            } else {
                                InfoRow("Status", "Desconectado do Wi-Fi", TextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Card Dados Móveis
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, if (activeNetwork.type == ActiveNetworkType.MOBILE) primaryAccent.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Dados Móveis / Celular", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                                }

                                Surface(
                                    color = if (activeNetwork.type == ActiveNetworkType.MOBILE) primaryAccent.copy(alpha = 0.18f) else DarkSurface,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (activeNetwork.type == ActiveNetworkType.MOBILE) "● Conectado" else "○ Em Espera",
                                        color = if (activeNetwork.type == ActiveNetworkType.MOBILE) primaryAccent else TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (activeNetwork.mobileSims.isNotEmpty()) {
                                activeNetwork.mobileSims.forEachIndexed { idx, sim ->
                                    if (idx > 0) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.4f))
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                    val isPrimary = idx == activeNetwork.primaryMobileSimIndex
                                    val simTitle = if (isPrimary && activeNetwork.type == ActiveNetworkType.MOBILE) {
                                        "CHIP ${sim.simSlotIndex + 1} (Dados ativos) - ${sim.carrierName}"
                                    } else {
                                        "CHIP ${sim.simSlotIndex + 1} - ${sim.carrierName}"
                                    }
                                    Text(simTitle, fontWeight = FontWeight.Bold, color = if (isPrimary) primaryAccent else TextSecondary, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    InfoRow("Operadora", sim.carrierName)
                                    InfoRow("MCC / MNC", sim.mccMnc)
                                    InfoRow("Tecnologia de Rede", sim.networkType)
                                    InfoRow("Intensidade do Sinal", "${sim.signalDbm} dBm (Nível ${sim.signalLevel}/4)")
                                    InfoRow("Roaming", if (sim.isRoaming) "Ativado (Fora da área)" else "Desativado (Rede de origem)")
                                    InfoRow("Dados Móveis", if (sim.isDataEnabled) "Habilitado" else "Desabilitado")
                                    InfoRow("IP da Conexão Celular", sim.ipAddress)
                                }
                            } else {
                                InfoRow("Status", "Nenhum chip ou conexão celular detectada", TextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Redes Salvas via Root
                    if (wifiDetails.savedNetworks.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("REDES WI-FI CONHECIDAS (${wifiDetails.savedNetworks.size})", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                wifiDetails.savedNetworks.take(6).forEach { saved ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(saved.ssid, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 12.sp)
                                        if (saved.password != null) {
                                            Text("Senha: ${saved.password}", color = StatusSuccess, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text(saved.securityType, color = TextMuted, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 2: BLUETOOTH
                    val isBtEnabled = viewModel.bluetoothDetailsManager.isBluetoothEnabled()
                    val hasBtPerm = viewModel.bluetoothDetailsManager.hasBluetoothPermission()

                    if (!hasBtPerm) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, StatusWarning.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.1f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Permissão de Bluetooth Necessária", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "O Android 12+ exige a permissão BLUETOOTH_CONNECT para identificar dispositivos conectados, nível de bateria e histórico.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.grant_permission), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    } else if (!isBtEnabled) {
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
                                    .padding(horizontal = 20.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(DarkSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.BluetoothDisabled,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = stringResource(R.string.bluetooth_disabled),
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Ative o Bluetooth para localizar e gerenciar dispositivos pareados e conectados.",
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Button(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                        } catch (_: Exception) {}
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(44.dp).padding(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.turn_on_bluetooth), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Ações de exportar histórico e busca
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = btSearchQuery,
                            onValueChange = { btSearchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_bluetooth_placeholder), fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )

                        IconButton(
                            onClick = {
                                exportDialogTitle = "Exportação de Histórico Bluetooth"
                                exportDialogContent = viewModel.bluetoothDetailsManager.formatHistoryExport(bluetoothDevices, bluetoothHistory)
                                showExportDialog = true
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = primaryAccent)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val filteredBt = bluetoothDevices.filter {
                        if (btSearchQuery.isBlank()) true
                        else it.name.contains(btSearchQuery, ignoreCase = true) || (it.alias?.contains(btSearchQuery, ignoreCase = true) == true) || it.address.contains(btSearchQuery, ignoreCase = true)
                    }

                    val connectedBt = filteredBt.filter { it.isConnected }
                    val pairedBt = filteredBt.filter { !it.isConnected }

                    // Seção Conectados agora
                    Text(
                        text = "${stringResource(R.string.connected_now)} (${connectedBt.size})",
                        fontWeight = FontWeight.Bold,
                        color = StatusSuccess,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (connectedBt.isNotEmpty()) {
                        connectedBt.forEach { dev ->
                            BluetoothDeviceCard(
                                dev = dev,
                                primaryAccent = primaryAccent,
                                onClick = { selectedBtDeviceForDetails = dev }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    } else {
                        Text("Nenhum dispositivo conectado no momento.", fontSize = 12.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Seção Salvos / Pareados
                    Text(
                        text = "${stringResource(R.string.saved_paired)} (${pairedBt.size})",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (pairedBt.isNotEmpty()) {
                        pairedBt.forEach { dev ->
                            BluetoothDeviceCard(
                                dev = dev,
                                primaryAccent = primaryAccent,
                                onClick = { selectedBtDeviceForDetails = dev }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    } else {
                        Text("Nenhum outro dispositivo salvo encontrado.", fontSize = 12.sp, color = TextMuted)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "O histórico exato de conexões começa a partir da ativação do monitoramento no app.",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }

                3 -> {
                    // TAB 3: DISPOSITIVO (INFORMAÇÕES COMPLETAS DO SISTEMA)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "ESPECIFICAÇÕES DO APARELHO",
                            fontWeight = FontWeight.ExtraBold,
                            color = primaryAccent,
                            fontSize = 13.sp,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val specText = buildString {
                                        appendLine("=== Relatório Completo do Dispositivo - ReSync ===")
                                        appendLine("Modelo: ${deviceSpec.marketName} (${deviceSpec.deviceCode})")
                                        appendLine("Fabricante: ${deviceSpec.manufacturer} | Placa: ${deviceSpec.board}")
                                        appendLine("Android: ${deviceSpec.androidVersion} (API ${deviceSpec.apiLevel})")
                                        appendLine("ROM: ${deviceSpec.romVersion}")
                                        appendLine("Kernel: ${deviceSpec.kernelVersion}")
                                        appendLine("SoC: ${deviceSpec.socModel} (${deviceSpec.totalCores} núcleos)")
                                        appendLine("GPU: ${deviceSpec.gpuRenderer}")
                                        appendLine("RAM: ${StorageRepository.formatFileSize(deviceSpec.ramUsedBytes)} / ${StorageRepository.formatFileSize(deviceSpec.ramTotalBytes)}")
                                        appendLine("Armazenamento: ${StorageRepository.formatFileSize(deviceSpec.internalStorageUsedBytes)} / ${StorageRepository.formatFileSize(deviceSpec.internalStorageTotalBytes)}")
                                        appendLine("Tela: ${deviceSpec.resolution} • ${deviceSpec.densityDpi} DPI (${deviceSpec.currentRefreshRate} Hz)")
                                        appendLine("Bateria: ${deviceSpec.batteryPercent}% (${deviceSpec.batteryStatus}, ${deviceSpec.batteryTemperatureC}°C)")
                                        appendLine("Câmeras: ${deviceSpec.camerasSummary}")
                                    }
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Device Specs", specText))
                                    Toast.makeText(context, context.getString(R.string.device_info_copied), Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = primaryAccent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.copy_all), fontSize = 11.sp, color = primaryAccent, fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            Button(
                                onClick = {
                                    exportDialogTitle = "Especificações do Dispositivo"
                                    exportDialogContent = buildString {
                                        appendLine("=== Relatório Técnico Completo - ReSync ===")
                                        appendLine("--- IDENTIFICAÇÃO ---")
                                        appendLine("Modelo: ${deviceSpec.model}")
                                        appendLine("Nome Comercial: ${deviceSpec.marketName}")
                                        appendLine("Fabricante: ${deviceSpec.manufacturer}")
                                        appendLine("Marca: ${deviceSpec.brand}")
                                        appendLine("Codinome: ${deviceSpec.deviceCode}")
                                        appendLine("Placa (Board): ${deviceSpec.board}")
                                        appendLine("Hardware: ${deviceSpec.hardware}")
                                        appendLine("Build: ${deviceSpec.buildNumber}")
                                        appendLine("Fingerprint: ${deviceSpec.fingerprint}")
                                        appendLine()
                                        appendLine("--- SISTEMA ---")
                                        appendLine("Versão Android: ${deviceSpec.androidVersion} (API ${deviceSpec.apiLevel})")
                                        appendLine("Patch de Segurança: ${deviceSpec.securityPatch}")
                                        appendLine("ROM: ${deviceSpec.romVersion}")
                                        appendLine("Data do Build: ${deviceSpec.buildDate}")
                                        appendLine("Kernel: ${deviceSpec.kernelVersion}")
                                        appendLine("Baseband: ${deviceSpec.basebandVersion}")
                                        appendLine("Bootloader: ${deviceSpec.bootloaderStatus}")
                                        appendLine("Root: ${deviceSpec.rootStatus}")
                                        appendLine("Treble: ${if (deviceSpec.trebleSupport) "Sim" else "Não"}")
                                        appendLine("Partições A/B: ${if (deviceSpec.abPartitions) "Sim" else "Não"}")
                                        appendLine("Google Play Services: ${deviceSpec.playServicesVersion}")
                                        appendLine("WebView: ${deviceSpec.webViewVersion}")
                                        appendLine()
                                        appendLine("--- PROCESSADOR & GPU ---")
                                        appendLine("SoC: ${deviceSpec.socModel} (${deviceSpec.socManufacturer})")
                                        appendLine("Núcleos: ${deviceSpec.totalCores}")
                                        appendLine("Arquiteturas (ABIs): ${deviceSpec.abis.joinToString(", ")}")
                                        appendLine("Governor: ${deviceSpec.governor}")
                                        appendLine("GPU Renderer: ${deviceSpec.gpuRenderer}")
                                        appendLine("Vulkan: ${deviceSpec.vulkanVersion}")
                                        appendLine()
                                        appendLine("--- MEMÓRIA & ARMAZENAMENTO ---")
                                        appendLine("RAM Total: ${StorageRepository.formatFileSize(deviceSpec.ramTotalBytes)}")
                                        appendLine("RAM Usada: ${StorageRepository.formatFileSize(deviceSpec.ramUsedBytes)}")
                                        appendLine("RAM Livre: ${StorageRepository.formatFileSize(deviceSpec.ramAvailableBytes)}")
                                        appendLine("zRAM / Swap: ${StorageRepository.formatFileSize(deviceSpec.zramTotalBytes)}")
                                        appendLine("Armazenamento Interno: ${StorageRepository.formatFileSize(deviceSpec.internalStorageUsedBytes)} / ${StorageRepository.formatFileSize(deviceSpec.internalStorageTotalBytes)}")
                                        appendLine("Tipo de Armazenamento: ${deviceSpec.storageType}")
                                        appendLine("Cartão SD: ${deviceSpec.sdCardStatus}")
                                        appendLine()
                                        appendLine("--- TELA ---")
                                        appendLine("Resolução: ${deviceSpec.resolution}")
                                        appendLine("Densidade: ${deviceSpec.densityDpi} DPI")
                                        appendLine("Tamanho Estimado: ${deviceSpec.approximateInches}\"")
                                        appendLine("Taxa de Atualização: ${deviceSpec.currentRefreshRate} Hz (Suportadas: ${deviceSpec.supportedRefreshRates.joinToString(" Hz, ")} Hz)")
                                        appendLine("HDR: ${deviceSpec.hdrCapabilities}")
                                        appendLine()
                                        appendLine("--- BATERIA ---")
                                        appendLine("Nível: ${deviceSpec.batteryPercent}% (${deviceSpec.batteryStatus})")
                                        appendLine("Saúde: ${deviceSpec.batteryHealth} | Temperatura: ${deviceSpec.batteryTemperatureC}°C")
                                        appendLine("Tensão: ${deviceSpec.batteryVoltageMv} mV | Tecnologia: ${deviceSpec.batteryTechnology}")
                                        if (deviceSpec.batteryCycles != null) appendLine("Ciclos: ${deviceSpec.batteryCycles}")
                                        appendLine()
                                        appendLine("--- RECURSOS EXTRAS ---")
                                        appendLine("Câmeras: ${deviceSpec.camerasSummary}")
                                        appendLine("Sensores detectados: ${deviceSpec.sensorsCount}")
                                        appendLine("NFC: ${if (deviceSpec.hasNfc) "Presente" else "Ausente"}")
                                        appendLine("Biometria: ${if (deviceSpec.hasBiometrics) "Suportada" else "Não disponível"}")
                                        appendLine("Conectividade Wi-Fi: ${deviceSpec.wifiVersions}")
                                        appendLine("Conectividade Bluetooth: ${deviceSpec.bluetoothVersion}")
                                    }
                                    showExportDialog = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.export_text), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Card 1: Identificação
                    DeviceGroupCard(title = "IDENTIFICAÇÃO", icon = Icons.Default.PhoneAndroid, iconTint = primaryAccent) {
                        InfoRow("Modelo", deviceSpec.model)
                        InfoRow("Nome Comercial", deviceSpec.marketName)
                        InfoRow("Fabricante / Marca", "${deviceSpec.manufacturer} • ${deviceSpec.brand}")
                        InfoRow("Codinome (Device)", deviceSpec.deviceCode)
                        InfoRow("Placa (Board)", deviceSpec.board)
                        InfoRow("Hardware", deviceSpec.hardware)
                        InfoRow("Número da Versão (Build)", deviceSpec.buildNumber)
                        InfoRow("Fingerprint", deviceSpec.fingerprint)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Card 2: Sistema
                    DeviceGroupCard(title = "SISTEMA", icon = Icons.Default.SettingsSuggest, iconTint = AccentEmeraldGreen) {
                        InfoRow("Versão do Android", "${deviceSpec.androidVersion} (API ${deviceSpec.apiLevel})")
                        InfoRow("Patch de Segurança", deviceSpec.securityPatch)
                        InfoRow("Versão da ROM", deviceSpec.romVersion)
                        InfoRow("Data do Build", deviceSpec.buildDate)
                        InfoRow("Kernel", deviceSpec.kernelVersion)
                        InfoRow("Baseband", deviceSpec.basebandVersion)
                        InfoRow("Bootloader", deviceSpec.bootloaderStatus)
                        InfoRow("Acesso Root", deviceSpec.rootStatus)
                        InfoRow("Suporte ao Treble", if (deviceSpec.trebleSupport) "Sim" else "Não")
                        InfoRow("Partições A/B", if (deviceSpec.abPartitions) "Sim" else "Não")
                        InfoRow("Google Play Services", deviceSpec.playServicesVersion)
                        InfoRow("Android System WebView", deviceSpec.webViewVersion)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Card 3: Processador
                    DeviceGroupCard(title = "PROCESSADOR & GPU", icon = Icons.Default.Memory, iconTint = AccentSunsetOrange) {
                        InfoRow("SoC (Chipset)", "${deviceSpec.socModel} (${deviceSpec.socManufacturer})")
                        InfoRow("Total de Núcleos", "${deviceSpec.totalCores} núcleos")
                        InfoRow("Arquitetura", deviceSpec.abis.joinToString(", "))
                        InfoRow("Governor", deviceSpec.governor)
                        InfoRow("GPU / Renderizador", deviceSpec.gpuRenderer)
                        InfoRow("Vulkan", deviceSpec.vulkanVersion)
                        if (deviceSpec.cores.isNotEmpty()) {
                            val activeCores = deviceSpec.cores.count { it.isOnline }
                            InfoRow("Núcleos Ativos", "$activeCores / ${deviceSpec.cores.size}")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Card 4: Memória
                    DeviceGroupCard(title = "MEMÓRIA & ARMAZENAMENTO", icon = Icons.Default.Storage, iconTint = AccentElectricCyan) {
                        InfoRow("RAM Total", StorageRepository.formatFileSize(deviceSpec.ramTotalBytes))
                        InfoRow("RAM em Uso", StorageRepository.formatFileSize(deviceSpec.ramUsedBytes), primaryAccent)
                        InfoRow("RAM Disponível", StorageRepository.formatFileSize(deviceSpec.ramAvailableBytes), StatusSuccess)
                        if (deviceSpec.zramTotalBytes > 0) {
                            InfoRow("zRAM (Swap)", StorageRepository.formatFileSize(deviceSpec.zramTotalBytes))
                        }
                        InfoRow("Armazenamento Interno", "${StorageRepository.formatFileSize(deviceSpec.internalStorageUsedBytes)} / ${StorageRepository.formatFileSize(deviceSpec.internalStorageTotalBytes)}")
                        InfoRow("Espaço Livre", StorageRepository.formatFileSize(deviceSpec.internalStorageFreeBytes), StatusSuccess)
                        InfoRow("Tecnologia de Armazenamento", deviceSpec.storageType)
                        InfoRow("Cartão SD Externo", deviceSpec.sdCardStatus)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Card 5: Tela
                    DeviceGroupCard(title = "TELA", icon = Icons.Default.Screenshot, iconTint = AccentCyberPurple) {
                        InfoRow("Resolução", deviceSpec.resolution)
                        InfoRow("Densidade", "${deviceSpec.densityDpi} DPI")
                        InfoRow("Tamanho Aproximado", "${deviceSpec.approximateInches} polegadas")
                        InfoRow("Taxa de Atualização Atual", "%.0f Hz".format(java.util.Locale.US, deviceSpec.currentRefreshRate))
                        InfoRow("Taxas Suportadas", deviceSpec.supportedRefreshRates.map { "%.0f Hz".format(java.util.Locale.US, it) }.joinToString(", "))
                        InfoRow("Recursos HDR", deviceSpec.hdrCapabilities)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Card 6: Bateria
                    DeviceGroupCard(title = "BATERIA", icon = Icons.Default.BatteryChargingFull, iconTint = StatusSuccess) {
                        InfoRow("Nível Atual", "${deviceSpec.batteryPercent}% (${deviceSpec.batteryStatus})", StatusSuccess)
                        InfoRow("Saúde", deviceSpec.batteryHealth)
                        InfoRow("Temperatura", "%.1f °C".format(java.util.Locale.US, deviceSpec.batteryTemperatureC))
                        InfoRow("Tensão", "${deviceSpec.batteryVoltageMv} mV")
                        InfoRow("Tecnologia", deviceSpec.batteryTechnology)
                        if (deviceSpec.batteryCycles != null) {
                            InfoRow("Ciclos de Carga", "${deviceSpec.batteryCycles}")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Card 7: Recursos Extras
                    DeviceGroupCard(title = "HARDWARE EXTRA", icon = Icons.Default.Widgets, iconTint = TextSecondary) {
                        InfoRow("Câmeras", deviceSpec.camerasSummary)
                        InfoRow("Sensores Integrados", "${deviceSpec.sensorsCount} sensores disponíveis")
                        InfoRow("NFC", if (deviceSpec.hasNfc) "Presente" else "Não disponível")
                        InfoRow("Biometria", if (deviceSpec.hasBiometrics) "Disponível (Digital / Facial)" else "Não configurado")
                        InfoRow("Protocolos Wi-Fi", deviceSpec.wifiVersions)
                        InfoRow("Versão Bluetooth", deviceSpec.bluetoothVersion)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    // Modal de Detalhes do Dispositivo Bluetooth
    if (selectedBtDeviceForDetails != null) {
        val dev = selectedBtDeviceForDetails!!
        AlertDialog(
            onDismissRequest = { selectedBtDeviceForDetails = null },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = primaryAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(dev.alias ?: dev.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InfoRow("Nome de Fábrica", dev.name.ifBlank { stringResource(R.string.unavailable) })
                    InfoRow("Apelido Local", dev.alias ?: "Nenhum apelido definido")
                    InfoRow("Endereço MAC", dev.address)
                    InfoRow("Tipo de Bluetooth", dev.deviceType)
                    InfoRow("Classe do Dispositivo", dev.majorClass)
                    val isBtActive = viewModel.bluetoothDetailsManager.isBluetoothEnabled()
                    val connStatus = if (dev.isConnected) "● Conectado agora" else (if (!isBtActive) stringResource(R.string.bluetooth_disabled_status) else "○ Desconectado")
                    InfoRow("Conexão Atual", connStatus, if (dev.isConnected) StatusSuccess else TextMuted)
                    InfoRow("Nível de Bateria", if (dev.batteryLevel != null) "${dev.batteryLevel}%" else stringResource(R.string.unavailable), if (dev.batteryLevel != null) StatusSuccess else TextSecondary)
                    if (dev.isConnected && dev.connectedProfiles.isNotEmpty()) {
                        InfoRow("Perfis Conectados", dev.connectedProfiles.joinToString(", "))
                    }
                    if (!dev.audioCodec.isNullOrBlank()) {
                        InfoRow("Codec de Áudio", dev.audioCodec)
                    }
                    InfoRow("Perfis Suportados", dev.supportedProfiles.ifEmpty { listOf(stringResource(R.string.unavailable)) }.joinToString(", "))
                    if (dev.uuids.isNotEmpty()) {
                        InfoRow("UUIDs", dev.uuids.take(2).joinToString("\n"))
                    } else {
                        InfoRow("UUIDs", stringResource(R.string.unavailable))
                    }
                    InfoRow("Total de Conexões", if (dev.totalConnectionCount > 0) "${dev.totalConnectionCount}" else "0")
                    InfoRow("Tempo Total Conectado", if (dev.totalConnectedDurationSec > 0) "${dev.totalConnectedDurationSec / 60} minutos" else "0 min")
                    if (dev.firstSeenTimestamp > 0) {
                        val dt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(dev.firstSeenTimestamp))
                        InfoRow("Primeiro Registro", dt)
                    }
                    if (dev.lastConnectedTimestamp > 0) {
                        val dtLast = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(dev.lastConnectedTimestamp))
                        InfoRow("Última Conexão", dtLast)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = DarkCardBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("AÇÕES DO DISPOSITIVO", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = primaryAccent)

                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("MAC Address", dev.address))
                            Toast.makeText(context, "Endereço MAC copiado!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copiar Endereço MAC", fontSize = 12.sp, color = TextPrimary)
                    }

                    Button(
                        onClick = {
                            editingAliasDevice = dev
                            aliasInputText = dev.alias ?: dev.name
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Renomear Apelido no App", fontSize = 12.sp, color = TextPrimary)
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                viewModel.bluetoothDetailsManager.deleteDeviceHistory(dev.address)
                                viewModel.refreshBluetoothData()
                                selectedBtDeviceForDetails = null
                                Toast.makeText(context, "Histórico do dispositivo apagado.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusError)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apagar Histórico deste Dispositivo", fontSize = 12.sp, color = StatusError)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedBtDeviceForDetails = null }) {
                    Text("Fechar", color = primaryAccent, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Modal de Edição de Apelido Local
    val devToEdit = editingAliasDevice
    if (devToEdit != null) {
        val dev = devToEdit
        AlertDialog(
            onDismissRequest = { editingAliasDevice = null },
            containerColor = DarkSurface,
            title = { Text("Renomear Dispositivo", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text("Defina um apelido local amigável para este dispositivo:", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = aliasInputText,
                        onValueChange = { aliasInputText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.bluetoothDetailsManager.saveDeviceAlias(dev.address, aliasInputText.trim())
                            viewModel.refreshBluetoothData()
                            editingAliasDevice = null
                            selectedBtDeviceForDetails = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Salvar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingAliasDevice = null }) {
                    Text("Cancelar", color = TextMuted)
                }
            }
        )
    }

    // Modal de Exportação de Texto Formatado
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = DarkSurface,
            title = { Text(exportDialogTitle, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBg)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = exportDialogContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = AccentElectricCyan
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Export Text", exportDialogContent))
                        Toast.makeText(context, "Texto copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
                        showExportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copiar Texto", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Fechar", color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun BluetoothDeviceCard(
    dev: BluetoothDeviceInfo,
    primaryAccent: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceVariant,
        border = BorderStroke(1.dp, if (dev.isConnected) StatusSuccess.copy(alpha = 0.4f) else DarkCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (dev.isConnected) StatusSuccess.copy(alpha = 0.15f) else primaryAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        dev.majorClass.contains("Áudio", ignoreCase = true) || dev.majorClass.contains("Fone", ignoreCase = true) -> Icons.Default.Headphones
                        dev.majorClass.contains("Relógio", ignoreCase = true) -> Icons.Default.Watch
                        dev.majorClass.contains("Computador", ignoreCase = true) -> Icons.Default.Laptop
                        dev.majorClass.contains("Teclado", ignoreCase = true) -> Icons.Default.Keyboard
                        else -> Icons.Default.Bluetooth
                    },
                    contentDescription = null,
                    tint = if (dev.isConnected) StatusSuccess else primaryAccent,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dev.alias ?: dev.name,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (dev.isConnected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = StatusSuccess.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Conectado", color = StatusSuccess, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${dev.majorClass} • ${dev.address}",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (dev.batteryLevel != null) {
                    Text("Bateria: ${dev.batteryLevel}%", fontSize = 10.sp, color = StatusSuccess, fontWeight = FontWeight.SemiBold)
                }
            }

            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DeviceGroupCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

