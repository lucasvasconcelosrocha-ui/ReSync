package com.resync.app.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.resync.app.engine.CapabilityManager
import com.resync.app.engine.PermissionsState
import com.resync.app.engine.RootManagerApp
import com.resync.app.engine.RootManagers
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

enum class PermissionStatusType(val label: String, val isGood: Boolean) {
    PERMITTED("Ativado", true),
    AVAILABLE("Disponível", true),
    AUTHORIZED("Autorizado", true),
    REQUIRED("Pendente", false),
    DENIED("Negado", false),
    OPTIONAL("Pendente", false),
    NOT_AVAILABLE("Indisponível", false)
}

data class UnifiedPermissionItem(
    val title: String,
    val description: String,
    val status: PermissionStatusType,
    val icon: ImageVector,
    val isPrimary: Boolean = false,
    val permissions: List<String> = emptyList(),
    val actionLabel: String = "Configurar",
    val onAction: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val capabilityManager = remember { CapabilityManager(context) }
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val permissionsState by viewModel.permissionsState.collectAsStateWithLifecycle()
    val isNotificationBlocked by viewModel.isNotificationBlocked.collectAsStateWithLifecycle()
    val isRequestingRoot by viewModel.isRequestingRoot.collectAsStateWithLifecycle()

    var isFutureRootGuideExpanded by remember { mutableStateOf(false) }
    var rootFeedbackMessage by remember { mutableStateOf<String?>(null) }

    val detectedRootManagers: List<RootManagerApp> = remember(capabilityManager) {
        capabilityManager.getInstalledRootManagers()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAllPermissions()
        viewModel.refreshRootStatus()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshAllPermissions()
            while (true) {
                delay(1200L)
                viewModel.refreshAllPermissions()
            }
        }
    }

    // Permission launcher for standard runtime permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshAllPermissions()
    }

    // Launchers for system settings (Autostart and Usage stats)
    val autostartLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.setHyperOsAutostartConfigured(true)
        viewModel.refreshAllPermissions()
    }

    // Helper functions for system intents
    fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(fallback)
                } catch (_: Exception) {}
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
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
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
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
                autostartLauncher.launch(intent)
                return
            } catch (_: Exception) {}
        }
    }

    fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    fun openBackgroundDataSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun openHyperOsBatteryNoRestrictions() {
        val miuiIntents = listOf(
            Intent().setComponent(android.content.ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")).apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
            },
            Intent().setComponent(android.content.ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")),
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
        for (intent in miuiIntents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {}
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    // Consolidated interactive list of permissions and system accesses
    val unifiedPermissions = remember(permissionsState) {
        val list = mutableListOf<UnifiedPermissionItem>()

        // 1. Acesso ao Armazenamento
        val storageOk = permissionsState.hasAllFilesAccess
        list.add(
            UnifiedPermissionItem(
                title = "Acesso ao Armazenamento",
                description = "Leitura e gravação integral de containers de backup (.resync) no armazenamento compartilhado e raiz.",
                status = if (storageOk) PermissionStatusType.PERMITTED else PermissionStatusType.REQUIRED,
                icon = Icons.Default.Folder,
                isPrimary = true,
                actionLabel = if (storageOk) "Concedido" else "Conceder",
                onAction = { openStorageSettings() }
            )
        )

        // 2. Notificações
        val notifOk = !isNotificationBlocked && if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsState.hasPostNotifications
        } else true
        list.add(
            UnifiedPermissionItem(
                title = "Notificações",
                description = "Progresso em tempo real no painel de notificações e alertas de término de backup/sincronização.",
                status = if (notifOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                icon = Icons.Default.Notifications,
                isPrimary = true,
                actionLabel = if (notifOk) "Ativado" else "Ativar",
                onAction = { openNotificationSettings() }
            )
        )

        // 3. Início Automático (Permissões de inicialização)
        val autostartOk = permissionsState.hasAutostart
        list.add(
            UnifiedPermissionItem(
                title = "Início Automático",
                description = "Permite inicialização em segundo plano no HyperOS, MIUI, Samsung e outros para rotinas agendadas.",
                status = if (autostartOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                icon = Icons.Default.PowerSettingsNew,
                isPrimary = true,
                actionLabel = if (autostartOk) "Configurado" else "Configurar",
                onAction = { openAutostartSettings() }
            )
        )

        // 4. Otimização de Bateria / Restrições em segundo plano
        val batteryOk = permissionsState.isIgnoringBatteryOptimizations
        list.add(
            UnifiedPermissionItem(
                title = "Otimização de Bateria",
                description = "Ajuste para 'Sem restrições', impedindo o sistema de interromper transferências e backups longos.",
                status = if (batteryOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                icon = Icons.Default.BatteryChargingFull,
                isPrimary = true,
                actionLabel = if (batteryOk) "Sem Restrições" else "Configurar",
                onAction = { openBatteryOptimizationSettings() }
            )
        )

        // 5. Economia de Dados em Segundo Plano
        val bgDataOk = permissionsState.hasBackgroundDataExemption
        list.add(
            UnifiedPermissionItem(
                title = "Economia de Dados em 2º Plano",
                description = "Isenção da restrição de dados móveis em segundo plano, evitando que transferências caiam com a tela desligada.",
                status = if (bgDataOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                icon = Icons.Default.SignalCellularAlt,
                isPrimary = false,
                actionLabel = if (bgDataOk) "Liberado" else "Configurar",
                onAction = { openBackgroundDataSettings() }
            )
        )

        // 6. HyperOS / MIUI: Sem restrições
        val isXiaomi = Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                Build.BRAND.contains("Xiaomi", ignoreCase = true) ||
                Build.BRAND.contains("POCO", ignoreCase = true) ||
                Build.BRAND.contains("Redmi", ignoreCase = true)
        if (isXiaomi) {
            list.add(
                UnifiedPermissionItem(
                    title = "HyperOS / MIUI: Sem Restrições",
                    description = "Defina a economia de bateria do app como 'Sem restrições' para prevenir que o sistema mate o Foreground Service.",
                    status = if (batteryOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                    icon = Icons.Default.SettingsApplications,
                    isPrimary = true,
                    actionLabel = "Configurar",
                    onAction = { openHyperOsBatteryNoRestrictions() }
                )
            )
        }

        // 5. Contatos, SMS e Chamadas
        val commsOk = permissionsState.hasContacts && permissionsState.hasSms && permissionsState.hasCalls
        val commsPerms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG
        )
        list.add(
            UnifiedPermissionItem(
                title = "Contatos, SMS e Chamadas",
                description = "Backup seguro da agenda telefônica, histórico de ligações e mensagens de texto SMS.",
                status = if (commsOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                icon = Icons.Default.Contacts,
                permissions = commsPerms,
                actionLabel = if (commsOk) "Concedido" else "Permitir",
                onAction = {
                    permissionLauncher.launch(commsPerms.toTypedArray())
                }
            )
        )


        // 7. Bluetooth (Dispositivos Pareados)
        val btOk = permissionsState.hasBluetoothConnect
        val btPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        list.add(
            UnifiedPermissionItem(
                title = "Bluetooth",
                description = "Consulta de dispositivos pareados para preservação de perfis de conexão.",
                status = if (btOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                icon = Icons.Default.Bluetooth,
                permissions = btPerms,
                actionLabel = if (btOk) "Permitido" else "Permitir",
                onAction = {
                    if (btPerms.isNotEmpty()) {
                        permissionLauncher.launch(btPerms.toTypedArray())
                    }
                }
            )
        )

        // 8. Acesso a Dados de Uso
        val usageOk = permissionsState.hasUsageStats
        list.add(
            UnifiedPermissionItem(
                title = "Acesso a Dados de Uso",
                description = "Necessário para cálculo de cache de aplicativos sem acesso root e diagnóstico de armazenamento.",
                status = if (usageOk) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL,
                icon = Icons.Default.Storage,
                actionLabel = if (usageOk) "Concedido" else "Conceder",
                onAction = { openUsageAccessSettings() }
            )
        )

        list
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
                        text = "Permissões e Acessos",
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Central de Acessos e Privilégios",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Todas as permissões e ajustes do sistema concentrados em uma única lista interativa em tempo real.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // SEÇÃO DESTACADA: ACESSO ROOT
            // ==========================================
            val isRootGranted = permissionsState.isRootGranted

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        if (isRootGranted) StatusSuccess.copy(alpha = 0.5f) else StatusWarning.copy(alpha = 0.5f),
                        RoundedCornerShape(16.dp)
                    ),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isRootGranted) StatusSuccess.copy(alpha = 0.15f) else StatusWarning.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isRootGranted) Icons.Default.AdminPanelSettings else Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = if (isRootGranted) StatusSuccess else StatusWarning,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Acesso Root (Superusuário)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isRootGranted) "Privilégios ativos" else "Modo Padrão (Sem Root)",
                                    fontSize = 11.sp,
                                    color = if (isRootGranted) StatusSuccess else StatusWarning,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isRootGranted) StatusSuccess.copy(alpha = 0.15f) else StatusWarning.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, if (isRootGranted) StatusSuccess.copy(alpha = 0.4f) else StatusWarning.copy(alpha = 0.4f)),
                            modifier = Modifier.wrapContentSize()
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isRootGranted) "● Root Autorizado" else "○ Sem Root",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRootGranted) StatusSuccess else StatusWarning,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isRootGranted) {
                        Text(
                            text = "✓ Privilégios de superusuário concedidos. O ReSync possui acesso irrestrito para backup de dados internos de aplicativos (/data/data), senhas de Wi-Fi salvas e extração protegida.",
                            fontSize = 12.sp,
                            color = StatusSuccess,
                            lineHeight = 16.sp
                        )
                    } else {
                        // Instrução clara solicitada pelo usuário
                        Text(
                            text = "Abra o gerenciador do seu root (Magisk, KernelSU, APatch, etc.), autorize o ReSync e depois volte aqui para clicar em Atualizar Acesso Root.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Elemento visual interativo indicando que o app está rodando sem privilégios de root
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DarkBg),
                            border = BorderStroke(1.dp, DarkCardBorder),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = AccentElectricCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Dispositivo Operando Sem Root",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = AccentElectricCyan
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "O aplicativo opera em modo seguro padrão. O backup de Mídias, Fotos, Vídeos, Documentos, SMS, Chamadas, Contatos e extração de APKs instalados funcionam com 100% de estabilidade sem root.",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    lineHeight = 15.sp
                                )
                            }
                        }

                        // Atalho interativo para passo a passo futuro
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkBg,
                            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isFutureRootGuideExpanded = !isFutureRootGuideExpanded }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Como habilitar ou autorizar Root no futuro?",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = primaryAccent
                                    )
                                }
                                val arrowRotation by animateFloatAsState(
                                    targetValue = if (isFutureRootGuideExpanded) 180f else 0f,
                                    label = "rootGuideArrow"
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer(rotationZ = arrowRotation)
                                )
                            }
                        }

                        AnimatedVisibility(visible = isFutureRootGuideExpanded) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkBg),
                                border = BorderStroke(1.dp, DarkCardBorder),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Passo a passo para autorização:", fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = TextPrimary)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("1. Abra seu gerenciador de root (Magisk, KernelSU ou APatch).", fontSize = 11.sp, color = TextSecondary)
                                    Text("2. Acesse a aba 'Superusuário' ou 'Módulos/Acessos' e autorize o app 'ReSync'.", fontSize = 11.sp, color = TextSecondary)
                                    Text("3. Retorne aqui e clique no botão 'Atualizar Acesso Root' abaixo.", fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }

                    rootFeedbackMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = msg,
                            fontSize = 11.sp,
                            color = if (msg.contains("sucesso", ignoreCase = true) || msg.contains("autorizado", ignoreCase = true) && !msg.contains("não", ignoreCase = true)) StatusSuccess else StatusWarning,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (detectedRootManagers.isNotEmpty()) {
                            val manager = detectedRootManagers.first()
                            OutlinedButton(
                                onClick = {
                                    manager.launchIntent?.let { intent ->
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, primaryAccent)
                            ) {
                                Text(
                                    text = "Abrir ${manager.name}",
                                    fontSize = 11.sp,
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
                                    rootFeedbackMessage = if (granted) "Acesso Root autorizado com sucesso!" else "Acesso Root ainda não autorizado. Conceda no gerenciador e tente novamente."
                                }
                            },
                            enabled = !isRequestingRoot,
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                        ) {
                            if (isRequestingRoot) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verificando...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Atualizar Acesso Root", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // LISTA INTERATIVA ÚNICA DE PERMISSÕES
            // ==========================================
            Text(
                text = "PERMISSÕES E SERVIÇOS DO SISTEMA",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in unifiedPermissions) {
                    val isGood = item.status.isGood
                    val badgeColor = if (isGood) StatusSuccess else StatusWarning
                    val badgeText = if (isGood) "✓ Concedido" else "✕ Pendente"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (item.isPrimary && !isGood) StatusWarning.copy(alpha = 0.5f) else DarkCardBorder, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            // Linha 1: ícone de 32dp + título + badge "Concedido"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(if (isGood) StatusSuccess.copy(alpha = 0.12f) else primaryAccent.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            tint = if (isGood) StatusSuccess else primaryAccent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = badgeColor.copy(alpha = 0.14f),
                                ) {
                                    Text(
                                        text = badgeText,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            // Linha 2: descrição em até 2 linhas, 12sp, alinhada com o título
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.description,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 42.dp)
                            )

                            // Linha 3: "Revisar Ajustes" como TextButton pequeno à direita, sem espaço extra
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isGood) {
                                    @OptIn(ExperimentalMaterial3Api::class)
                                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                        TextButton(
                                            onClick = item.onAction,
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("Revisar Ajustes", fontSize = 11.5.sp, color = TextMuted)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = item.onAction,
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(item.actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}
