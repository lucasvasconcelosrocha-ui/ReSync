package com.resync.app.ui.screens

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.data.model.AppInfo
import com.resync.app.engine.BackupScheduleItem
import com.resync.app.engine.BatteryRequirement
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.components.AppSelectionListComponent
import com.resync.app.ui.theme.*
import com.resync.app.util.FormatUtils
import java.io.File
import java.util.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleRoutineWizardScreen(
    initialSchedule: BackupScheduleItem?,
    installedApps: List<AppInfo>,
    primaryAccent: Color,
    onSave: (BackupScheduleItem) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val initial = initialSchedule ?: BackupScheduleItem()
    var routineTitle by remember { mutableStateOf(initial.title) }
    var routineTime by remember { mutableStateOf(initial.time) }
    var routineDestination by remember { mutableStateOf(initial.destination) }
    var routineBatteryReq by remember { mutableStateOf(initial.batteryRequirement ?: BatteryRequirement.CHARGING) }

    val dayOptions = remember {
        listOf(
            Calendar.SUNDAY to "Dom",
            Calendar.MONDAY to "Seg",
            Calendar.TUESDAY to "Ter",
            Calendar.WEDNESDAY to "Qua",
            Calendar.THURSDAY to "Qui",
            Calendar.FRIDAY to "Sex",
            Calendar.SATURDAY to "Sáb"
        )
    }

    var selectedDays by remember {
        mutableStateOf(
            if (initial.daysOfWeek.isNotEmpty()) initial.daysOfWeek.toSet()
            else dayOptions.map { it.first }.toSet()
        )
    }

    // App Selection States
    val selectedAppPackages = remember {
        mutableStateListOf<String>().apply {
            if (initial.specificApps.isNotEmpty()) {
                addAll(initial.specificApps)
            } else if (initial.targetItems.contains("APPS")) {
                addAll(installedApps.map { it.packageName })
            }
        }
    }
    var includeApps by remember {
        mutableStateOf(initial.targetItems.contains("APPS") || selectedAppPackages.isNotEmpty())
    }

    // App Data Components
    var includeBaseApk by remember { mutableStateOf(initial.includeBaseApk) }
    var includeAppSplits by remember { mutableStateOf(initial.includeAppSplits) }
    var includeAppData by remember { mutableStateOf(initial.includeAppData) }
    var includeExternalData by remember { mutableStateOf(initial.includeExternalData) }
    var includeObb by remember { mutableStateOf(initial.includeObb) }
    var includeAppPermissions by remember { mutableStateOf(initial.includeAppPermissions) }

    // System Components
    var includeWifi by remember { mutableStateOf(initial.targetItems.contains("WIFI")) }
    var includeDns by remember { mutableStateOf(initial.targetItems.contains("DNS")) }
    var includeSms by remember { mutableStateOf(initial.targetItems.contains("MESSAGES")) }
    var includeCalls by remember { mutableStateOf(initial.targetItems.contains("CALLS")) }
    var includeContacts by remember { mutableStateOf(initial.targetItems.contains("CONTACTS")) }
    var includeBluetooth by remember { mutableStateOf(initial.targetItems.contains("BLUETOOTH")) }
    var includeWallpaper by remember { mutableStateOf(initial.targetItems.contains("WALLPAPER")) }

    // Media Folders
    val defaultStandardFolders = remember {
        listOf(
            "/storage/emulated/0/Download" to "Downloads",
            "/storage/emulated/0/Documents" to "Documentos",
            "/storage/emulated/0/DCIM" to "Câmera (DCIM)",
            "/storage/emulated/0/Pictures" to "Imagens e Fotos",
            "/storage/emulated/0/Music" to "Músicas",
            "/storage/emulated/0/Movies" to "Filmes e Vídeos",
            "/storage/emulated/0/Recordings" to "Gravações",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media" to "Mídia WhatsApp"
        )
    }
    val selectedFolders = remember {
        mutableStateListOf<String>().apply {
            addAll(initial.selectedFolders)
        }
    }
    var includeFolders by remember {
        mutableStateOf(initial.targetItems.contains("FOLDERS") || selectedFolders.isNotEmpty())
    }

    // Expanded Section States
    var expandedGroups by remember {
        mutableStateOf(setOf<String>("routine_config"))
    }

    fun toggleGroup(group: String) {
        expandedGroups = if (expandedGroups.contains(group)) expandedGroups - group else expandedGroups + group
    }

    fun openTimePicker() {
        val parts = routineTime.split(":")
        val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val initialMin = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                routineTime = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
            },
            initialHour,
            initialMin,
            true
        ).show()
    }

    // Dynamic Real-time Calculations
    val appDataChildren = listOf(includeBaseApk, includeAppSplits, includeAppData, includeExternalData, includeObb, includeAppPermissions)
    val systemChildren = listOf(includeWifi, includeDns, includeSms, includeCalls, includeContacts, includeBluetooth, includeWallpaper)

    val appsParentState = when {
        !includeApps || selectedAppPackages.isEmpty() -> ToggleableState.Off
        selectedAppPackages.size == installedApps.size && installedApps.isNotEmpty() -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

    val appDataParentState = when {
        appDataChildren.all { it } -> ToggleableState.On
        appDataChildren.none { it } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    val systemParentState = when {
        systemChildren.all { it } -> ToggleableState.On
        systemChildren.none { it } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    val foldersParentState = when {
        !includeFolders || selectedFolders.isEmpty() -> ToggleableState.Off
        selectedFolders.size >= defaultStandardFolders.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

    val totalCount = 1 + appDataChildren.size + systemChildren.size + 1
    val selectedCount = (if (includeApps && selectedAppPackages.isNotEmpty()) 1 else 0) +
            appDataChildren.count { it } +
            systemChildren.count { it } +
            (if (includeFolders && selectedFolders.isNotEmpty()) 1 else 0)

    val estimatedBytes = remember(
        selectedAppPackages.size, includeApps, includeBaseApk, includeAppSplits, includeAppData, includeExternalData, includeObb,
        selectedFolders.size, includeFolders,
        includeWifi, includeDns, includeSms, includeCalls, includeContacts, includeBluetooth, includeWallpaper
    ) {
        var bytes = 0L
        val appCount = if (includeApps) selectedAppPackages.size else 0
        if (appCount > 0) {
            if (includeBaseApk) bytes += appCount * 32_000_000L
            if (includeAppSplits) bytes += appCount * 12_000_000L
            if (includeAppData) bytes += appCount * 24_000_000L
            if (includeExternalData) bytes += appCount * 18_000_000L
            if (includeObb) bytes += appCount * 45_000_000L
        }

        if (includeFolders) {
            for (p in selectedFolders) {
                val cached = StorageRepository.getCachedFolderSize(p)
                if (cached != null) {
                    bytes += cached
                } else {
                    val f = File(p)
                    if (f.exists()) {
                        if (f.isFile) bytes += f.length() else bytes += 12_000_000L
                    }
                }
            }
        }

        if (includeSms) bytes += 2_000_000L
        if (includeCalls) bytes += 500_000L
        if (includeContacts) bytes += 1_000_000L
        if (includeDns) bytes += 4_096L
        if (includeWifi) bytes += 10_000L
        if (includeBluetooth) bytes += 10_000L
        if (includeWallpaper) bytes += 8_000_000L

        bytes
    }

    val estimatedSizeDisplay = remember(estimatedBytes) {
        FormatUtils.formatBytes(estimatedBytes)
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
                    IconButton(onClick = onCancel) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (initialSchedule == null) "Nova rotina de agendamento" else "Editar rotina",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                TextButton(
                    onClick = {
                        val targets = buildList {
                            if (includeApps && selectedAppPackages.isNotEmpty()) add("APPS")
                            if (includeSms) add("MESSAGES")
                            if (includeCalls) add("CALLS")
                            if (includeContacts) add("CONTACTS")
                            if (includeWifi) add("WIFI")
                            if (includeDns) add("DNS")
                            if (includeBluetooth) add("BLUETOOTH")
                            if (includeWallpaper) add("WALLPAPER")
                            if (includeFolders && selectedFolders.isNotEmpty()) add("FOLDERS")
                        }
                        val appMode = when {
                            includeBaseApk && includeAppData -> "BOTH"
                            includeBaseApk -> "APK"
                            includeAppData -> "DATA"
                            else -> "BOTH"
                        }
                        val updated = initial.copy(
                            title = routineTitle.ifBlank { "Backup Agendado" },
                            time = routineTime,
                            destination = routineDestination,
                            daysOfWeek = if (selectedDays.isEmpty()) dayOptions.map { it.first } else selectedDays.toList(),
                            targetItems = targets,
                            specificApps = if (includeApps) selectedAppPackages.toList() else emptyList(),
                            appBackupMode = appMode,
                            selectedFolders = if (includeFolders) selectedFolders.toList() else emptyList(),
                            includeBaseApk = includeBaseApk,
                            includeAppSplits = includeAppSplits,
                            includeAppData = includeAppData,
                            includeExternalData = includeExternalData,
                            includeObb = includeObb,
                            includeAppPermissions = includeAppPermissions,
                            batteryRequirement = routineBatteryReq
                        )
                        onSave(updated)
                    }
                ) {
                    Text("SALVAR", color = primaryAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        },
        bottomBar = {
            Surface(
                color = DarkSurface,
                border = BorderStroke(1.dp, DarkCardBorder.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = {
                            val targets = buildList {
                                if (includeApps && selectedAppPackages.isNotEmpty()) add("APPS")
                                if (includeSms) add("MESSAGES")
                                if (includeCalls) add("CALLS")
                                if (includeContacts) add("CONTACTS")
                                if (includeWifi) add("WIFI")
                                if (includeDns) add("DNS")
                                if (includeBluetooth) add("BLUETOOTH")
                                if (includeWallpaper) add("WALLPAPER")
                                if (includeFolders && selectedFolders.isNotEmpty()) add("FOLDERS")
                            }
                            val appMode = when {
                                includeBaseApk && includeAppData -> "BOTH"
                                includeBaseApk -> "APK"
                                includeAppData -> "DATA"
                                else -> "BOTH"
                            }
                            val updated = initial.copy(
                                title = routineTitle.ifBlank { "Backup Agendado" },
                                time = routineTime,
                                destination = routineDestination,
                                daysOfWeek = if (selectedDays.isEmpty()) dayOptions.map { it.first } else selectedDays.toList(),
                                targetItems = targets,
                                specificApps = if (includeApps) selectedAppPackages.toList() else emptyList(),
                                appBackupMode = appMode,
                                selectedFolders = if (includeFolders) selectedFolders.toList() else emptyList(),
                                includeBaseApk = includeBaseApk,
                                includeAppSplits = includeAppSplits,
                                includeAppData = includeAppData,
                                includeExternalData = includeExternalData,
                                includeObb = includeObb,
                                includeAppPermissions = includeAppPermissions,
                                batteryRequirement = routineBatteryReq
                            )
                            onSave(updated)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SALVAR ROTINA", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Description
            Column {
                Text(
                    text = "Configuração detalhada da rotina",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Personalize o conteúdo, alvos e o agendamento exato para disparo em segundo plano.",
                    color = TextSecondary,
                    fontSize = 12.5.sp
                )
            }

            // Top Real-time Counter & Estimation Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Checklist, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$selectedCount de $totalCount itens selecionados",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Estimativa: ${estimatedSizeDisplay.replace('.', ',')}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedCount > 0) StatusSuccess else TextMuted
                    )
                }
            }

            // Quick Select / Clear Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        includeApps = true
                        selectedAppPackages.clear()
                        selectedAppPackages.addAll(installedApps.map { it.packageName })
                        includeBaseApk = true
                        includeAppSplits = true
                        includeAppData = true
                        includeExternalData = true
                        includeObb = true
                        includeAppPermissions = true

                        includeWifi = true
                        includeDns = true
                        includeSms = true
                        includeCalls = true
                        includeContacts = true
                        includeBluetooth = true
                        includeWallpaper = true

                        includeFolders = true
                        selectedFolders.clear()
                        selectedFolders.addAll(defaultStandardFolders.map { it.first })
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DarkSurfaceVariant,
                        contentColor = primaryAccent
                    )
                ) {
                    Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SELECIONAR TUDO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                }

                OutlinedButton(
                    onClick = {
                        includeApps = false
                        selectedAppPackages.clear()
                        includeBaseApk = false
                        includeAppSplits = false
                        includeAppData = false
                        includeExternalData = false
                        includeObb = false
                        includeAppPermissions = false

                        includeWifi = false
                        includeDns = false
                        includeSms = false
                        includeCalls = false
                        includeContacts = false
                        includeBluetooth = false
                        includeWallpaper = false

                        includeFolders = false
                        selectedFolders.clear()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DarkSurfaceVariant,
                        contentColor = StatusError
                    )
                ) {
                    Icon(Icons.Default.Deselect, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusError)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("LIMPAR SELEÇÃO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusError)
                }
            }

            // ==========================================
            // SEÇÃO 1: APLICATIVOS SELECIONADOS
            // ==========================================
            RoutineCategoryCard(
                icon = Icons.Default.Apps,
                iconTint = primaryAccent,
                title = "Aplicativos selecionados",
                subtitle = "${selectedAppPackages.size} de ${installedApps.size} selecionados • Escolha os apps do backup",
                parentState = appsParentState,
                onParentToggle = {
                    val selectAll = appsParentState != ToggleableState.On
                    includeApps = selectAll
                    selectedAppPackages.clear()
                    if (selectAll) {
                        selectedAppPackages.addAll(installedApps.map { it.packageName })
                    }
                },
                isExpanded = expandedGroups.contains("apps"),
                onToggleExpand = { toggleGroup("apps") }
            ) {
                AppSelectionListComponent(
                    apps = installedApps,
                    selectedPackages = selectedAppPackages.toSet(),
                    onTogglePackage = { pkg ->
                        if (selectedAppPackages.contains(pkg)) {
                            selectedAppPackages.remove(pkg)
                        } else {
                            selectedAppPackages.add(pkg)
                        }
                        includeApps = selectedAppPackages.isNotEmpty()
                    },
                    onSelectAll = { pkgs ->
                        selectedAppPackages.addAll(pkgs)
                        includeApps = true
                    },
                    onDeselectAll = { pkgs ->
                        selectedAppPackages.removeAll(pkgs)
                        includeApps = selectedAppPackages.isNotEmpty()
                    },
                    primaryAccent = primaryAccent,
                    maxHeight = 300.dp
                )
            }

            // ==========================================
            // SEÇÃO 2: DADOS DOS APLICATIVOS
            // ==========================================
            RoutineCategoryCard(
                icon = Icons.Default.FolderShared,
                iconTint = AccentElectricCyan,
                title = "Dados dos aplicativos",
                subtitle = "APK base, Splits, Dados internos, OBB e Permissões",
                parentState = appDataParentState,
                onParentToggle = {
                    val newState = appDataParentState != ToggleableState.On
                    includeBaseApk = newState
                    includeAppSplits = newState
                    includeAppData = newState
                    includeExternalData = newState
                    includeObb = newState
                    includeAppPermissions = newState
                },
                isExpanded = expandedGroups.contains("app_data"),
                onToggleExpand = { toggleGroup("app_data") }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoutineSubOptionItem(
                        icon = Icons.Default.Android,
                        label = "APK base",
                        description = "Binário do aplicativo instalável (.apk)",
                        checked = includeBaseApk,
                        onCheckedChange = { includeBaseApk = it },
                        accent = AccentElectricCyan
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.PieChart,
                        label = "Splits",
                        description = "Módulos de densidade e arquitetura ABI",
                        checked = includeAppSplits,
                        onCheckedChange = { includeAppSplits = it },
                        accent = AccentElectricCyan
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Storage,
                        label = "Dados internos",
                        description = "Bancos de dados e preferências (/data/data)",
                        checked = includeAppData,
                        onCheckedChange = { includeAppData = it },
                        accent = AccentElectricCyan
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.FolderOpen,
                        label = "Dados externos",
                        description = "Arquivos armazenados em /Android/data",
                        checked = includeExternalData,
                        onCheckedChange = { includeExternalData = it },
                        accent = AccentElectricCyan
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.SportsEsports,
                        label = "OBB",
                        description = "Pacotes de expansão pesados (/Android/obb)",
                        checked = includeObb,
                        onCheckedChange = { includeObb = it },
                        accent = AccentElectricCyan
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Security,
                        label = "Permissões",
                        description = "Privilégios e permissões concedidas pelo usuário",
                        checked = includeAppPermissions,
                        onCheckedChange = { includeAppPermissions = it },
                        accent = AccentElectricCyan
                    )
                }
            }

            // ==========================================
            // SEÇÃO 3: SISTEMA E COMUNICAÇÃO
            // ==========================================
            RoutineCategoryCard(
                icon = Icons.Default.SettingsCell,
                iconTint = AccentNeonPurple,
                title = "Sistema e Comunicação",
                subtitle = "Wi-Fi, DNS, SMS, Chamadas, Contatos, Bluetooth e Papel de parede",
                parentState = systemParentState,
                onParentToggle = {
                    val newState = systemParentState != ToggleableState.On
                    includeWifi = newState
                    includeDns = newState
                    includeSms = newState
                    includeCalls = newState
                    includeContacts = newState
                    includeBluetooth = newState
                    includeWallpaper = newState
                },
                isExpanded = expandedGroups.contains("system"),
                onToggleExpand = { toggleGroup("system") }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoutineSubOptionItem(
                        icon = Icons.Default.Wifi,
                        label = "Wi-Fi",
                        description = "Redes Wi-Fi salvas com senhas",
                        checked = includeWifi,
                        onCheckedChange = { includeWifi = it },
                        accent = AccentNeonPurple
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Dns,
                        label = "DNS",
                        description = "Configurações e servidores de DNS Privado",
                        checked = includeDns,
                        onCheckedChange = { includeDns = it },
                        accent = AccentNeonPurple
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Sms,
                        label = "SMS / Mensagens",
                        description = "Histórico de mensagens e conversas",
                        checked = includeSms,
                        onCheckedChange = { includeSms = it },
                        accent = AccentNeonPurple
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Call,
                        label = "Histórico de Chamadas",
                        description = "Registro de ligações efetuadas, recebidas e perdidas",
                        checked = includeCalls,
                        onCheckedChange = { includeCalls = it },
                        accent = AccentNeonPurple
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Contacts,
                        label = "Contatos",
                        description = "Lista e agenda telefônica de contatos",
                        checked = includeContacts,
                        onCheckedChange = { includeContacts = it },
                        accent = AccentNeonPurple
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Bluetooth,
                        label = "Bluetooth",
                        description = "Dispositivos emparelhados e configurações",
                        checked = includeBluetooth,
                        onCheckedChange = { includeBluetooth = it },
                        accent = AccentNeonPurple
                    )
                    RoutineSubOptionItem(
                        icon = Icons.Default.Wallpaper,
                        label = "Papel de parede",
                        description = "Imagens da tela inicial e da tela de bloqueio",
                        checked = includeWallpaper,
                        onCheckedChange = { includeWallpaper = it },
                        accent = AccentNeonPurple
                    )
                }
            }

            // ==========================================
            // SEÇÃO 4: ARQUIVOS E MÍDIA
            // ==========================================
            RoutineCategoryCard(
                icon = Icons.Default.FolderCopy,
                iconTint = AccentSunsetOrange,
                title = "Arquivos e Mídia",
                subtitle = "${selectedFolders.size} ${if (selectedFolders.size == 1) "pasta selecionada" else "pastas selecionadas"} da memória",
                parentState = foldersParentState,
                onParentToggle = {
                    val selectAll = foldersParentState != ToggleableState.On
                    includeFolders = selectAll
                    selectedFolders.clear()
                    if (selectAll) {
                        selectedFolders.addAll(defaultStandardFolders.map { it.first })
                    }
                },
                isExpanded = expandedGroups.contains("files"),
                onToggleExpand = { toggleGroup("files") }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Pastas do Armazenamento Interno:",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )

                    defaultStandardFolders.forEach { (path, label) ->
                        val isChecked = selectedFolders.contains(path)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isChecked) selectedFolders.remove(path) else selectedFolders.add(path)
                                    includeFolders = selectedFolders.isNotEmpty()
                                }
                                .padding(vertical = 5.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedFolders.add(path) else selectedFolders.remove(path)
                                    includeFolders = selectedFolders.isNotEmpty()
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentSunsetOrange)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(path, fontSize = 10.5.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SEÇÃO 5: CONFIGURAÇÕES DA ROTINA
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, primaryAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(primaryAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Configurações do Agendamento",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Defina nome, horário, frequência e destino",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Nome da rotina
                    OutlinedTextField(
                        value = routineTitle,
                        onValueChange = { routineTitle = it },
                        label = { Text("Nome da rotina") },
                        placeholder = { Text("Ex: Backup Noturno") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            focusedLabelColor = primaryAccent
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Horário do agendamento
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openTimePicker() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Horário exato", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Hora preferencial de disparo", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBg)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = routineTime,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryAccent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Dias da semana com botões rápidos e sem cortar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dias da semana:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Todos",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryAccent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(primaryAccent.copy(alpha = 0.12f))
                                    .clickable { selectedDays = dayOptions.map { it.first }.toSet() }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                            Text(
                                text = "Seg-Sex",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DarkBg)
                                    .clickable {
                                        selectedDays = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 7 botões de dias com peso uniforme (weight 1f) para NUNCA cortar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dayOptions.forEach { (dayInt, dayLabel) ->
                            val isSelected = selectedDays.contains(dayInt)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) primaryAccent else DarkBg)
                                    .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedDays = if (isSelected) {
                                            if (selectedDays.size > 1) selectedDays - dayInt else selectedDays
                                        } else {
                                            selectedDays + dayInt
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else TextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Requisitos de bateria
                    Text("Requisitos de bateria:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BatteryRequirement.values().forEach { req ->
                            FilterChip(
                                selected = routineBatteryReq == req,
                                onClick = { routineBatteryReq = req },
                                label = { Text(req.label, fontSize = 11.5.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryAccent,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Destino do backup
                    Text("Destino do cofre:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Dispositivo", "Nuvem", "Ambos").forEach { dest ->
                            FilterChip(
                                selected = routineDestination == dest,
                                onClick = { routineDestination = dest },
                                label = { Text(dest, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryAccent,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoutineCategoryCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    parentState: ToggleableState,
    onParentToggle: () -> Unit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (isExpanded) iconTint.copy(alpha = 0.35f) else DarkCardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 2,
                            softWrap = true
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(
                                role = Role.Checkbox,
                                onClick = onParentToggle
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        TriStateCheckbox(
                            state = parentState,
                            onClick = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = iconTint,
                                checkmarkColor = Color.White
                            )
                        )
                    }

                    IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Recolher" else "Expandir",
                            tint = TextSecondary
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 6.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun RoutineSubOptionItem(
    icon: ImageVector,
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (checked) accent.copy(alpha = 0.15f) else DarkBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) accent else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (checked) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description != null) {
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 2,
                        softWrap = true
                    )
                }
            }
        }

        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = accent,
                checkmarkColor = Color.White
            )
        )
    }
}
