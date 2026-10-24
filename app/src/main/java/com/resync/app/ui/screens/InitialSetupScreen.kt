package com.resync.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.resync.app.R
import androidx.core.content.ContextCompat
import com.resync.app.engine.CapabilityManager
import com.resync.app.ui.components.cards.InfoRow
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun InitialSetupScreen(
    viewModel: MainViewModel,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val capabilityManager = remember { CapabilityManager(context) }
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val stats by viewModel.deviceStats.collectAsState()
    val wifiDetails by viewModel.wifiDetails.collectAsState()
    val activeNetworkDetails by viewModel.activeNetworkDetails.collectAsStateWithLifecycle()

    var currentStep by remember { mutableIntStateOf(1) } // 1: Apresentação, 2: Root, 3: Permissões, 4: Armazenamento & Rede, 5: Pronto
    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val isRequestingRoot by viewModel.isRequestingRoot.collectAsState()

    var rootManagers by remember { mutableStateOf<List<com.resync.app.engine.RootManagerApp>>(emptyList()) }
    var showRootManagerChooser by remember { mutableStateOf(false) }
    var rootFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var rootWatchDeadline by remember { mutableLongStateOf(0L) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Detect root managers on entering Step 2
    LaunchedEffect(currentStep) {
        if (currentStep == 2) {
            rootManagers = capabilityManager.getInstalledRootManagers()
        }
    }

    // Short 30s polling window after launching root manager
    LaunchedEffect(lifecycleOwner, rootWatchDeadline, permissionsState.isRootGranted) {
        if (rootWatchDeadline > System.currentTimeMillis() && !permissionsState.isRootGranted) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (System.currentTimeMillis() < rootWatchDeadline && !permissionsState.isRootGranted) {
                    viewModel.refreshRootStatus(forceNewShell = true)
                    rootManagers = capabilityManager.getInstalledRootManagers()
                    kotlinx.coroutines.delay(3000L)
                }
            }
        }
    }

    // 1-second non-root polling for pending permissions while in Step 3
    LaunchedEffect(lifecycleOwner, currentStep, permissionsState.areAllRuntimeGranted, permissionsState.hasAllFilesAccess) {
        if (currentStep == 3 && (!permissionsState.areAllRuntimeGranted || !permissionsState.hasAllFilesAccess)) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (!permissionsState.areAllRuntimeGranted || !permissionsState.hasAllFilesAccess) {
                    viewModel.refreshAllPermissions()
                    kotlinx.coroutines.delay(1000L)
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshAllPermissions()
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAllPermissions()
    }

    fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                settingsLauncher.launch(intent)
            } catch (_: Exception) {
                try {
                    settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {}
            }
        } else {
            val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissionLauncher.launch(perms)
        }
    }

    fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                settingsLauncher.launch(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    settingsLauncher.launch(intent)
                } catch (_: Exception) {}
            }
        }
    }

    fun openAutostartSettings() {
        val autostartIntents = listOf(
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
        for (intent in autostartIntents) {
            try {
                settingsLauncher.launch(intent)
                return
            } catch (_: Exception) {}
        }
    }

    fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            settingsLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                settingsLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    settingsLauncher.launch(intent)
                } catch (_: Exception) {}
            }
        }
    }


    fun openLocationPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun openBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH))
        }
    }

    fun openCommsPermissions() {
        val perms = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        )
        permissionLauncher.launch(perms)
    }

    if (showRootManagerChooser && rootManagers.size > 1) {
        AlertDialog(
            onDismissRequest = { showRootManagerChooser = false },
            title = { Text("Escolha o gerenciador de root", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rootManagers.forEach { mgr ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showRootManagerChooser = false
                                    mgr.launchIntent?.let { intent ->
                                        try {
                                            context.startActivity(intent)
                                            rootWatchDeadline = System.currentTimeMillis() + 30_000L
                                        } catch (_: Exception) {}
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(mgr.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRootManagerChooser = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Elegant Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(18.dp))
                    .padding(vertical = 20.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(id = R.drawable.logo_resync),
                        contentDescription = "ReSync Logo",
                        modifier = Modifier.size(96.dp),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "ReSync",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Backup Granular & Proteção Avançada",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = primaryAccent
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "#TEAMBRASILFELAS🇧🇷",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = StatusSuccess
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step Progress Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (s in 1..4) {
                    val isActive = s == currentStep
                    val isDone = s < currentStep
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isActive -> primaryAccent
                                    isDone -> StatusSuccess
                                    else -> DarkCardBorder
                                }
                            )
                    )
                    if (s < 4) {
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (currentStep) {
                1 -> {
                    // ETAPA 1: Apresentação
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "Bem-vindo ao ReSync",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Para utilizar todos os recursos do aplicativo, algumas permissões e acesso root são necessários.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "• O aplicativo respeita a invariância do Kotlin Flow e não fecha durante operações.\n" +
                                        "• Utiliza apenas Google Drive para armazenamento na nuvem.\n" +
                                        "• Sem dados simulados: todo status é real e auditado.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = { currentStep = 2 },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Text("CONTINUAR", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }

                2 -> {
                    // ETAPA 2: Verificar Root & Gerenciador de Root
                    val isRootOk = permissionsState.isRootGranted
                    val rootBadgeBg by animateColorAsState(
                        targetValue = if (isRootOk) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f),
                        label = "rootBadgeBg"
                    )
                    val rootBadgeText by animateColorAsState(
                        targetValue = if (isRootOk) StatusSuccess else StatusWarning,
                        label = "rootBadgeText"
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ACESSO ROOT",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(rootBadgeBg)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isRootOk) "● Root Autorizado" else "✕ Root não autorizado",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = rootBadgeText
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (isRootOk) {
                                Text(
                                    text = "✓ ReSync está autorizado no gerenciador de root. Recursos avançados como backup completo de dados de aplicativos (/data/data), chaves de Bluetooth e extração de senhas de Wi-Fi estão disponíveis.",
                                    color = StatusSuccess,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            } else {
                                Text(
                                    text = "Acesso Root (Opcional)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Abra o gerenciador do seu root (Magisk, KernelSU, APatch, etc.), autorize o ReSync e depois volte aqui para clicar em Atualizar Acesso Root.",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Indicador visual para dispositivos sem root
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = DarkBg),
                                    border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = StatusWarning,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Dispositivo Operando Sem Root",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = StatusWarning
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "O aplicativo opera em modo seguro padrão. Backups de Arquivos, Mídias, Fotos, Vídeos, SMS, Contatos, Chamadas e extração de APKs funcionam com 100% de suporte sem superusuário.",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.7f)),
                                    border = BorderStroke(1.dp, DarkCardBorder)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "Recursos exclusivos com Root:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "• Backup e restauração de dados internos de apps (/data/data)\n• Chaves de pareamento Bluetooth\n• Senhas de Wi-Fi salvas do sistema",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }

                                if (rootManagers.isEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Nenhum gerenciador de root detectado. Se você usa Magisk, KernelSU ou APatch oculto, abra-o manualmente e autorize o ReSync.",
                                        fontSize = 11.sp,
                                        color = StatusWarning,
                                        lineHeight = 15.sp
                                    )
                                }

                                rootFeedbackMessage?.let { feedback ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = feedback,
                                        fontSize = 11.sp,
                                        color = if (feedback.contains("autorizado", ignoreCase = true) && !feedback.contains("não", ignoreCase = true)) StatusSuccess else StatusWarning,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (rootManagers.isNotEmpty()) {
                                        val openButtonText = if (rootManagers.size == 1) "ABRIR ${rootManagers.first().name.uppercase()}" else "ABRIR GERENCIADOR"
                                        OutlinedButton(
                                            onClick = {
                                                if (rootManagers.size == 1) {
                                                    rootManagers.first().launchIntent?.let { intent ->
                                                        try {
                                                            context.startActivity(intent)
                                                            rootWatchDeadline = System.currentTimeMillis() + 30_000L
                                                        } catch (_: Exception) {}
                                                    }
                                                } else {
                                                    showRootManagerChooser = true
                                                }
                                            },
                                            modifier = Modifier.weight(1f).height(46.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(
                                                text = openButtonText,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryAccent,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            rootFeedbackMessage = null
                                            viewModel.requestRootPermission { granted ->
                                                rootFeedbackMessage = if (granted) "Root autorizado com sucesso!" else "Root não autorizado ou pendente."
                                            }
                                        },
                                        enabled = !isRequestingRoot,
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                                    ) {
                                        if (isRequestingRoot) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("VERIFICANDO...", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text(
                                                text = "ATUALIZAR ACESSO ROOT",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    val continueStep2Color by animateColorAsState(
                        targetValue = if (isRootOk) StatusSuccess else primaryAccent,
                        label = "continueStep2Color"
                    )

                    Button(
                        onClick = { currentStep = 3 },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = continueStep2Color)
                    ) {
                        Text(
                            text = if (isRootOk) "AVANÇAR PARA PERMISSÕES" else "CONTINUAR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }

                3 -> {
                    // ETAPA 3: Permissões do Android
                    val hasStorage = permissionsState.hasAllFilesAccess || (permissionsState.hasMediaImages && permissionsState.hasMediaVideo)
                    val hasNotifs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissionsState.hasPostNotifications else true
                    val hasAutostart = permissionsState.hasAutostart
                    val hasBattery = permissionsState.isIgnoringBatteryOptimizations
                    val hasComms = permissionsState.hasContacts && permissionsState.hasSms && permissionsState.hasCalls
                    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissionsState.hasBluetoothConnect else true

                    val allPermsOk = permissionsState.hasAllFilesAccess && permissionsState.areAllRuntimeGranted
                    val permissionsListScrollState = rememberScrollState()

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PERMISSÕES & ACESSOS",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Role para ver todos",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Centralizamos todos os acessos essenciais, otimizações e autorizações em uma lista interativa única com status em tempo real.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Lista de Permissões com Rolagem Independente
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkBg.copy(alpha = 0.5f))
                                    .border(1.dp, DarkCardBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .verticalScroll(permissionsListScrollState)
                                    .padding(8.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // 1. Arquivos e Armazenamento
                                    SetupPermissionItemRow(
                                        icon = Icons.Default.Folder,
                                        iconTint = AccentEmeraldGreen,
                                        title = "Acesso ao Armazenamento",
                                        isGranted = hasStorage,
                                        grantedLabel = "Concedido",
                                        pendingLabel = "Conceder",
                                        onClick = { openStorageSettings() }
                                    )

                                    // 2. Notificações
                                    SetupPermissionItemRow(
                                        icon = Icons.Default.Notifications,
                                        iconTint = StatusWarning,
                                        title = "Notificações do Sistema",
                                        isGranted = hasNotifs,
                                        grantedLabel = "Ativado",
                                        pendingLabel = "Ativar",
                                        onClick = { openNotificationSettings() }
                                    )

                                    // 3. Início Automático
                                    SetupPermissionItemRow(
                                        icon = Icons.Default.PowerSettingsNew,
                                        iconTint = AccentElectricCyan,
                                        title = "Início Automático (Autostart)",
                                        isGranted = hasAutostart,
                                        grantedLabel = "Configurado",
                                        pendingLabel = "Configurar",
                                        onClick = { openAutostartSettings() }
                                    )

                                    // 4. Otimização de Bateria
                                    SetupPermissionItemRow(
                                        icon = Icons.Default.BatteryChargingFull,
                                        iconTint = StatusWarning,
                                        title = "Otimização de Bateria",
                                        isGranted = hasBattery,
                                        grantedLabel = "Sem Restrições",
                                        pendingLabel = "Configurar",
                                        onClick = { openBatteryOptimizationSettings() }
                                    )

                                    // 5. Contatos, SMS e Chamadas
                                    SetupPermissionItemRow(
                                        icon = Icons.Default.Contacts,
                                        iconTint = AccentCyberPurple,
                                        title = "Contatos, SMS e Chamadas",
                                        isGranted = hasComms,
                                        grantedLabel = "Concedido",
                                        pendingLabel = "Permitir",
                                        onClick = { openCommsPermissions() }
                                    )


                                    // 7. Localização (Identificação SSID)
                                    SetupPermissionItemRow(
                                        icon = Icons.Default.Wifi,
                                        iconTint = AccentEmeraldGreen,
                                        title = "Localização (Identificação de SSID)",
                                        isGranted = hasLocation,
                                        grantedLabel = "Concedido",
                                        pendingLabel = "Permitir",
                                        onClick = { openLocationPermissions() }
                                    )

                                    // 8. Dispositivos Próximos (Bluetooth)
                                    SetupPermissionItemRow(
                                        icon = Icons.Default.Bluetooth,
                                        iconTint = primaryAccent,
                                        title = "Dispositivos Próximos (Bluetooth)",
                                        isGranted = hasBluetooth,
                                        grantedLabel = "Concedido",
                                        pendingLabel = "Permitir",
                                        onClick = { openBluetoothPermissions() }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Botão para solicitar todas as permissões principais de runtime
                            Button(
                                onClick = {
                                    val perms = mutableListOf(
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.WRITE_CONTACTS,
                                        Manifest.permission.READ_SMS,
                                        Manifest.permission.READ_CALL_LOG,
                                        Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                                        perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                        perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                                    }
                                    permissionLauncher.launch(perms.toTypedArray())
                                },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("SOLICITAR TODAS AS PERMISSÕES", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            // Acesso a todos os arquivos (Android 11+) se pendente
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !permissionsState.hasAllFilesAccess) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { openStorageSettings() },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("CONCEDER ACESSO A TODOS OS ARQUIVOS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = primaryAccent)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    val advancePermsColor by animateColorAsState(
                        targetValue = if (allPermsOk) StatusSuccess else primaryAccent,
                        label = "advancePermsColor"
                    )

                    Button(
                        onClick = { currentStep = 4 },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = advancePermsColor)
                    ) {
                        Text(
                            text = if (allPermsOk) "AVANÇAR PARA ARMAZENAMENTO" else "CONTINUAR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }

                4 -> {
                    // ETAPA 4: Armazenamento & Rede
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "ARMAZENAMENTO",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            val usedGb = "%.1f GB".format(java.util.Locale("pt", "BR"), (stats.storageTotalBytes - stats.storageFreeBytes).toDouble() / (1024 * 1024 * 1024))
                            val totalGb = "%.1f GB".format(java.util.Locale("pt", "BR"), stats.storageTotalBytes.toDouble() / (1024 * 1024 * 1024))
                            val freeGb = "%.1f GB".format(java.util.Locale("pt", "BR"), stats.storageFreeBytes.toDouble() / (1024 * 1024 * 1024))

                            InfoRow("Acesso", "✓ Disponível", StatusSuccess)
                            InfoRow("Espaço", "$usedGb / $totalGb")
                            InfoRow("Livre", freeGb, StatusSuccess)

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = DarkCardBorder)
                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "REDE & CONEXÃO",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            val isMobileActive = activeNetworkDetails.type == com.resync.app.data.model.ActiveNetworkType.MOBILE
                            val isWifiActive = activeNetworkDetails.type == com.resync.app.data.model.ActiveNetworkType.WIFI || wifiDetails.isConnected

                            if (isWifiActive) {
                                InfoRow("Tipo de Rede", "Wi-Fi", StatusSuccess)
                                val hasLocationPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val rawSsid = wifiDetails.ssid
                                val cleanSsid = if (rawSsid.contains("Permissão") || rawSsid == "<unknown ssid>") {
                                    "Wi-Fi Conectado"
                                } else {
                                    rawSsid.ifBlank { "Wi-Fi Conectado" }
                                }
                                InfoRow("SSID", cleanSsid)
                                if (!hasLocationPerm) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DarkBg.copy(alpha = 0.6f))
                                            .clickable {
                                                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ℹ Conceder localização para exibir nome da rede (SSID)",
                                            fontSize = 11.sp,
                                            color = primaryAccent,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Permitir",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                InfoRow("Velocidade", "${wifiDetails.linkSpeedMbps} Mbps")
                                InfoRow("Frequência", wifiDetails.frequencyGhz)
                                if (wifiDetails.signalDbm != 0) {
                                    InfoRow("Sinal", "${wifiDetails.signalDbm} dBm")
                                }
                            } else if (isMobileActive) {
                                val primarySim = activeNetworkDetails.mobileSims.firstOrNull()
                                InfoRow("Tipo de Rede", "Dados Móveis", primaryAccent)
                                InfoRow("Operadora", primarySim?.carrierName ?: "Rede Celular")
                                InfoRow("Tecnologia", primarySim?.networkType ?: "4G/5G")
                                if (!primarySim?.mccMnc.isNullOrBlank()) {
                                    InfoRow("MCC / MNC", primarySim!!.mccMnc)
                                }
                                if (primarySim?.signalDbm != null && primarySim.signalDbm != 0) {
                                    InfoRow("Sinal Celular", "${primarySim.signalDbm} dBm")
                                }
                                InfoRow("Roaming", if (primarySim?.isRoaming == true) "Ativo" else "Desativado")
                                val activeSimSlot = if (primarySim != null) "SIM ${primarySim.simSlotIndex + 1}" else "SIM 1"
                                InfoRow("SIM Ativo", activeSimSlot)
                            } else {
                                InfoRow("Conexão", "Desconectado", StatusWarning)
                                InfoRow("Status", "Sem conexão com a internet ativa", TextMuted)
                            }
                        }
                    }



                    // Backups na nuvem encontrados (Restauração no novo sistema / celular)
                    val cloudBackups by viewModel.remoteDriveBackups.collectAsStateWithLifecycle()
                    val driveState by viewModel.googleDriveState.collectAsStateWithLifecycle()

                    if (cloudBackups.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, AccentElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Backups encontrados na nuvem",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Identificamos backups da sua conta Google prontos para restauração neste aparelho:",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                cloudBackups.take(3).forEach { cb ->
                                    val sizeMb = "%.1f MB".format(java.util.Locale.US, cb.sizeBytes.toDouble() / (1024 * 1024))
                                    val dateStr = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(cb.modifiedTime))
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                        border = BorderStroke(1.dp, DarkCardBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(cb.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("$sizeMb • $dateStr", fontSize = 11.sp, color = TextMuted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                context.getSharedPreferences("resync_prefs", Context.MODE_PRIVATE).edit().putBoolean("setup_completed", true).apply()
                                context.getSharedPreferences("odin_prefs", Context.MODE_PRIVATE).edit().putBoolean("setup_completed", true).apply()
                                onSetupComplete()
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, TextSecondary),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Começar do zero",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Button(
                            onClick = {
                                context.getSharedPreferences("resync_prefs", Context.MODE_PRIVATE).edit().putBoolean("setup_completed", true).apply()
                                context.getSharedPreferences("odin_prefs", Context.MODE_PRIVATE).edit().putBoolean("setup_completed", true).apply()
                                onSetupComplete()
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (cloudBackups.isNotEmpty()) primaryAccent else StatusSuccess),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(if (cloudBackups.isNotEmpty()) Icons.Default.Restore else Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (cloudBackups.isNotEmpty()) "RESTAURAR" else "CONCLUIR",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPermissionItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    isGranted: Boolean,
    grantedLabel: String,
    pendingLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isGranted) StatusSuccess.copy(alpha = 0.18f) else StatusWarning.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (isGranted) "● $grantedLabel" else "○ $pendingLabel",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGranted) StatusSuccess else StatusWarning,
                softWrap = false,
                maxLines = 1
            )
        }
    }
}


