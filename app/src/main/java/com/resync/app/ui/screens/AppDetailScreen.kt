package com.resync.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.data.model.*
import com.resync.app.engine.ApkDetails
import com.resync.app.engine.AppStorageBreakdown
import com.resync.app.engine.AppStorageInspector
import com.resync.app.engine.IconCacheManager
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    app: AppInfo,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSelectFiles: (AppInfo) -> Unit = {},
    onViewBackup: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val iconCache = remember { IconCacheManager.getInstance(context) }
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)

    val localBackups by viewModel.backupRecords.collectAsStateWithLifecycle()
    val remoteBackups by viewModel.remoteDriveBackups.collectAsStateWithLifecycle()
    val restoreProgress by viewModel.restoreProgress.collectAsStateWithLifecycle()
    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()

    val appIconState = produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        value = iconCache.getAppIcon(app.packageName)
    }

    // Filtro em tempo real de backups deste aplicativo no dispositivo
    val deviceBackupsForApp = remember(localBackups, app.packageName, app.appName) {
        localBackups.filter {
            (it.filePath.isNotBlank() && File(it.filePath).exists()) &&
            (it.title.contains(app.appName, ignoreCase = true) ||
             it.title.contains(app.packageName, ignoreCase = true) ||
             it.filePath.contains(app.packageName, ignoreCase = true))
        }.sortedByDescending { it.timestamp }
    }

    // Filtro em tempo real de backups deste aplicativo na nuvem
    val cloudBackupsForApp = remember(remoteBackups, app.packageName, app.appName) {
        remoteBackups.filter {
            !it.name.endsWith(".index.json") &&
            (it.name.contains(app.packageName, ignoreCase = true) ||
             it.name.contains(app.appName, ignoreCase = true))
        }.sortedByDescending { it.modifiedTime }
    }

    // Estados e Preferências do App
    var isFavorite by remember(app.packageName) { mutableStateOf(viewModel.isAppFavorite(app.packageName)) }
    var isBlacklisted by remember(app.packageName) { mutableStateOf(viewModel.isAppBlacklisted(app.packageName)) }
    var isIgnoringBattery by remember(app.packageName) { mutableStateOf(viewModel.isAppIgnoringBatteryOptimizations(app.packageName)) }
    var isDisabled by remember(app.packageName) { mutableStateOf(viewModel.isAppDisabled(app.packageName)) }
    var appTags by remember(app.packageName) { mutableStateOf(viewModel.getAppTags(app.packageName)) }

    // Diálogos e Menus
    var showBackupDropdown by remember { mutableStateOf(false) }
    var showOptionsMenuSheet by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showTagsDialog by remember { mutableStateOf(false) }
    var showBlacklistHelpDialog by remember { mutableStateOf(false) }
    var showApksBottomSheet by remember { mutableStateOf(false) }
    var showDataBottomSheet by remember { mutableStateOf(false) }
    var backupToDelete by remember { mutableStateOf<Pair<UnifiedBackupEntry, String>?>(null) }

    // Opções de inclusão no backup
    var backupIncludeApk by remember { mutableStateOf(true) }
    var backupIncludeInternalData by remember { mutableStateOf(true) }
    var backupIncludeExternalData by remember { mutableStateOf(true) }
    var backupIncludeObb by remember { mutableStateOf(true) }

    // Estado de medição real de APKs e Dados
    var apkDetails by remember(app.packageName) { mutableStateOf(AppStorageInspector.getCachedApkDetails(app.packageName)) }
    var storageBreakdown by remember(app.packageName) { mutableStateOf(AppStorageInspector.getCachedStorage(app.packageName)) }
    var isMeasuringData by remember(app.packageName) { mutableStateOf(storageBreakdown == null) }

    LaunchedEffect(app.packageName) {
        launch(Dispatchers.IO) {
            val apks = AppStorageInspector.inspectApks(context, app)
            withContext(Dispatchers.Main) {
                apkDetails = apks
            }
        }
        launch(Dispatchers.IO) {
            isMeasuringData = true
            val data = AppStorageInspector.measureAppData(context, app.packageName)
            withContext(Dispatchers.Main) {
                storageBreakdown = data
                isMeasuringData = false
            }
        }
    }

    val installDateFormatted = remember(app.packageName) {
        try {
            val pi = context.packageManager.getPackageInfo(app.packageName, 0)
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(pi.firstInstallTime))
        } catch (_: Exception) {
            "—"
        }
    }

    val lastUpdateDateFormatted = remember(app.lastUpdateTime) {
        if (app.lastUpdateTime > 0L) {
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(app.lastUpdateTime))
        } else "—"
    }

    val daysSinceUpdate = remember(app.lastUpdateTime) {
        val diff = System.currentTimeMillis() - app.lastUpdateTime
        val d = (diff / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
        if (d == 0L) "hoje" else "há $d dia${if (d == 1L) "" else "s"}"
    }

    fun shareAppApk() {
        coroutineScope.launch {
            try {
                Toast.makeText(context, "Preparando APK para compartilhamento...", Toast.LENGTH_SHORT).show()
                val sharedDir = File(context.cacheDir, "shared_apks").apply { mkdirs() }
                val targetApk = File(sharedDir, "${app.appName.replace(" ", "_")}_v${app.versionName}.apk")

                withContext(Dispatchers.IO) {
                    val srcFile = File(app.sourceDir)
                    if (srcFile.exists()) {
                        FileInputStream(srcFile).use { input ->
                            FileOutputStream(targetApk).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                if (targetApk.exists() && targetApk.length() > 0) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetApk)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Compartilhar APK de ${app.appName}").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } else {
                    Toast.makeText(context, "Não foi possível extrair o arquivo APK.", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(context, "Falha ao compartilhar APK: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copiado para a área de transferência", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Voltar",
                        tint = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Backup e restauração",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
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
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // ==========================================
            // CARD DO APP (ESTILO SWIFT BACKUP COMPLETO)
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Linha Superior: Ícone real, nome, pacote e Menu ⋮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Ícone real
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkBg)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (appIconState.value != null) {
                                Image(
                                    bitmap = appIconState.value!!,
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(52.dp)
                                )
                            } else {
                                CircularProgressIndicator(color = primaryAccent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.appName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            // Pacote COMPLETO com clique para copiar
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.clickable {
                                    copyToClipboard("Nome do pacote", app.packageName)
                                }
                            )

                            Spacer(modifier = Modifier.height(3.dp))

                            Text(
                                text = "v${app.versionName} (${app.versionCode})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryAccent
                            )
                        }

                        // Menu ⋮ no topo do card
                        IconButton(
                            onClick = { showOptionsMenuSheet = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Opções do aplicativo",
                                tint = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Metadados adicionais: Instalação e Atualização
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Instalado em", fontSize = 11.sp, color = TextMuted)
                            Text(installDateFormatted, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Última atualização", fontSize = 11.sp, color = TextMuted)
                            Text("$lastUpdateDateFormatted ($daysSinceUpdate)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Linha com Botão Desinstalar e Tamanho real do APK
                    val totalApkBytes = apkDetails?.totalSizeBytes ?: (File(app.sourceDir).length() + app.splitSourceDirs.sumOf { File(it).length() })
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Tamanho do APK: ${formatStorageSize(totalApkBytes)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Botão Desinstalar
                        Button(
                            onClick = { showUninstallDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusError.copy(alpha = 0.16f),
                                contentColor = StatusError
                            ),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Desinstalar", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Chips Interativos: "APKs (tamanho e arqs)" e "Dados (tamanho real)"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // CHIP APKS (Toca para abrir Bottom Sheet)
                        val apkFileCount = apkDetails?.fileCount ?: (1 + app.splitSourceDirs.size)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showApksBottomSheet = true },
                            color = DarkBg,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Android, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "APKs (${formatStorageSize(totalApkBytes)})",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$apkFileCount arquivo${if (apkFileCount > 1) "s" else ""}",
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }

                        // CHIP DADOS (Toca para abrir Bottom Sheet)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showDataBottomSheet = true },
                            color = DarkBg,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, AccentSunsetOrange.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Storage, contentDescription = null, tint = AccentSunsetOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    if (isMeasuringData && storageBreakdown == null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                color = AccentSunsetOrange,
                                                modifier = Modifier.size(10.dp),
                                                strokeWidth = 1.5.dp
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Calculando...",
                                                fontSize = 11.sp,
                                                color = AccentSunsetOrange,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    } else {
                                        val totalData = storageBreakdown?.totalDataBytes ?: 0L
                                        Text(
                                            text = "Dados (${formatStorageSize(totalData)})",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val cacheVal = storageBreakdown?.cacheData?.sizeBytes ?: 0L
                                        Text(
                                            text = "Cache: ${formatStorageSize(cacheVal)}",
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (appTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            appTags.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(primaryAccent.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text("#$tag", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // BOTÃO "+ BACKUP" COM MENU SUSPENSO
            // ==========================================
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showBackupDropdown = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BACKUP",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Black)
                }

                DropdownMenu(
                    expanded = showBackupDropdown,
                    onDismissRequest = { showBackupDropdown = false },
                    modifier = Modifier
                        .background(DarkSurface)
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Backup para o dispositivo", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryAccent) },
                        onClick = {
                            showBackupDropdown = false
                            viewModel.launchBackup(
                                title = "Backup de ${app.appName}",
                                selectedApps = listOf(
                                    Pair(
                                        app,
                                        AppSelectionOptions(
                                            includeBaseApk = backupIncludeApk,
                                            includeSplits = backupIncludeApk && app.splitSourceDirs.isNotEmpty(),
                                            includeData = backupIncludeInternalData || backupIncludeExternalData,
                                            relatedSettings = true
                                        )
                                    )
                                ),
                                selectedFiles = emptyList(),
                                includeSms = false,
                                includeCalls = false,
                                includeDns = false,
                                includeWallpaper = false,
                                destinationLabel = "Memória interna",
                                destinationType = BackupDestinationType.SOMENTE_INTERNA
                            )
                            Toast.makeText(context, "Iniciando backup no dispositivo...", Toast.LENGTH_SHORT).show()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Backup na Nuvem (Google Drive)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentElectricCyan) },
                        onClick = {
                            showBackupDropdown = false
                            viewModel.launchBackup(
                                title = "Backup de ${app.appName}",
                                selectedApps = listOf(
                                    Pair(
                                        app,
                                        AppSelectionOptions(
                                            includeBaseApk = backupIncludeApk,
                                            includeSplits = backupIncludeApk && app.splitSourceDirs.isNotEmpty(),
                                            includeData = backupIncludeInternalData || backupIncludeExternalData,
                                            relatedSettings = true
                                        )
                                    )
                                ),
                                selectedFiles = emptyList(),
                                includeSms = false,
                                includeCalls = false,
                                includeDns = false,
                                includeWallpaper = false,
                                targetCloudProvider = CloudProviderType.GOOGLE_DRIVE,
                                destinationLabel = "Google Drive",
                                destinationType = BackupDestinationType.SOMENTE_DRIVE
                            )
                            Toast.makeText(context, "Iniciando backup no Google Drive...", Toast.LENGTH_SHORT).show()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Backup para o dispositivo e nuvem", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null, tint = AccentEmeraldGreen) },
                        onClick = {
                            showBackupDropdown = false
                            viewModel.launchBackup(
                                title = "Backup de ${app.appName}",
                                selectedApps = listOf(
                                    Pair(
                                        app,
                                        AppSelectionOptions(
                                            includeBaseApk = backupIncludeApk,
                                            includeSplits = backupIncludeApk && app.splitSourceDirs.isNotEmpty(),
                                            includeData = backupIncludeInternalData || backupIncludeExternalData,
                                            relatedSettings = true
                                        )
                                    )
                                ),
                                selectedFiles = emptyList(),
                                includeSms = false,
                                includeCalls = false,
                                includeDns = false,
                                includeWallpaper = false,
                                targetCloudProvider = CloudProviderType.GOOGLE_DRIVE,
                                destinationLabel = "Interna + Drive",
                                destinationType = BackupDestinationType.INTERNA_E_DRIVE
                            )
                            Toast.makeText(context, "Iniciando backup no dispositivo e nuvem...", Toast.LENGTH_SHORT).show()
                        }
                    )

                    HorizontalDivider(color = DarkCardBorder)

                    DropdownMenuItem(
                        text = { Text("Compartilhar APK", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = AccentSunsetOrange) },
                        onClick = {
                            showBackupDropdown = false
                            shareAppApk()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // CARD "BACKUPS NO DISPOSITIVO"
            // ==========================================
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Backups no dispositivo",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )

                        Text(
                            text = "${deviceBackupsForApp.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryAccent
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (deviceBackupsForApp.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Não há backup no dispositivo",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    } else {
                        deviceBackupsForApp.forEachIndexed { index, record ->
                            if (index > 0) {
                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                            }

                            val formattedDate = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(record.timestamp))
                            var showItemMenu by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(primaryAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = formattedDate,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatStorageSize(record.sizeBytes),
                                            fontSize = 11.5.sp,
                                            color = TextSecondary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(primaryAccent.copy(alpha = 0.15f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("APK + Dados", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                                        }
                                    }
                                }

                                Box {
                                    IconButton(onClick = { showItemMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                                    }

                                    DropdownMenu(
                                        expanded = showItemMenu,
                                        onDismissRequest = { showItemMenu = false },
                                        modifier = Modifier.background(DarkSurface)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Restaurar", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null, tint = StatusSuccess) },
                                            onClick = {
                                                showItemMenu = false
                                                val entry = UnifiedBackupEntry(record = record, hasLocal = true, hasDrive = false)
                                                viewModel.restoreSingleAppBackup(entry, app.packageName)
                                                Toast.makeText(context, "Iniciando restauração de ${app.appName}...", Toast.LENGTH_SHORT).show()
                                            }
                                        )

                                        DropdownMenuItem(
                                            text = { Text("Backup na nuvem", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AccentElectricCyan) },
                                            onClick = {
                                                showItemMenu = false
                                                viewModel.uploadRecordToGoogleDrive(record)
                                                Toast.makeText(context, "Enviando backup para o Google Drive...", Toast.LENGTH_SHORT).show()
                                            }
                                        )

                                        DropdownMenuItem(
                                            text = { Text("Apagar", color = StatusError, fontWeight = FontWeight.Bold) },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                            onClick = {
                                                showItemMenu = false
                                                val entry = UnifiedBackupEntry(record = record, hasLocal = true, hasDrive = false)
                                                backupToDelete = Pair(entry, "local")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // CARD "BACKUPS NA NUVEM"
            // ==========================================
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Backups na nuvem",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )

                        Text(
                            text = "${cloudBackupsForApp.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentElectricCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (cloudBackupsForApp.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Não há backup na nuvem",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    } else {
                        cloudBackupsForApp.forEachIndexed { index, remote ->
                            if (index > 0) {
                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                            }

                            val formattedDate = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(remote.modifiedTime))
                            var showCloudItemMenu by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(AccentElectricCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CloudQueue, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(18.dp))
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = formattedDate,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatStorageSize(remote.sizeBytes),
                                            fontSize = 11.5.sp,
                                            color = TextSecondary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AccentElectricCyan.copy(alpha = 0.15f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("Nuvem", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AccentElectricCyan)
                                        }
                                    }
                                }

                                Box {
                                    IconButton(onClick = { showCloudItemMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                                    }

                                    DropdownMenu(
                                        expanded = showCloudItemMenu,
                                        onDismissRequest = { showCloudItemMenu = false },
                                        modifier = Modifier.background(DarkSurface)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Restaurar", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null, tint = StatusSuccess) },
                                            onClick = {
                                                showCloudItemMenu = false
                                                val dummyRecord = BackupRecord(
                                                    id = remote.id,
                                                    title = remote.name,
                                                    filePath = "",
                                                    sizeBytes = remote.sizeBytes,
                                                    checksumSha256 = remote.md5Checksum ?: "",
                                                    isEncrypted = false,
                                                    timestamp = remote.modifiedTime,
                                                    itemCount = 0,
                                                    status = BackupStatus.COMPLETED,
                                                    destination = "Google Drive",
                                                    driveFileId = remote.id
                                                )
                                                val entry = UnifiedBackupEntry(record = dummyRecord, hasLocal = false, hasDrive = true, remoteFileId = remote.id)
                                                viewModel.restoreSingleAppBackup(entry, app.packageName)
                                                Toast.makeText(context, "Baixando e restaurando ${app.appName}...", Toast.LENGTH_SHORT).show()
                                            }
                                        )

                                        DropdownMenuItem(
                                            text = { Text("Apagar da nuvem", color = StatusError, fontWeight = FontWeight.Bold) },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                            onClick = {
                                                showCloudItemMenu = false
                                                val dummyRecord = BackupRecord(
                                                    id = remote.id,
                                                    title = remote.name,
                                                    filePath = "",
                                                    sizeBytes = remote.sizeBytes,
                                                    checksumSha256 = "",
                                                    isEncrypted = false,
                                                    timestamp = remote.modifiedTime,
                                                    itemCount = 0,
                                                    status = BackupStatus.COMPLETED,
                                                    driveFileId = remote.id
                                                )
                                                val entry = UnifiedBackupEntry(record = dummyRecord, hasLocal = false, hasDrive = true, remoteFileId = remote.id)
                                                backupToDelete = Pair(entry, "cloud")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    // ==========================================
    // BOTTOM SHEET "APKs"
    // ==========================================
    if (showApksBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showApksBottomSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Arquivos do APK",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "${apkDetails?.fileCount ?: 1} arquivo(s) • Total: ${formatStorageSize(apkDetails?.totalSizeBytes ?: 0L)}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Button(
                        onClick = {
                            showApksBottomSheet = false
                            shareAppApk()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compartilhar", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Informações técnicas do pacote
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Versão", fontSize = 11.5.sp, color = TextSecondary)
                            Text("v${apkDetails?.versionName ?: app.versionName} (${apkDetails?.versionCode ?: app.versionCode})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SDK Alvo / Mínimo", fontSize = 11.5.sp, color = TextSecondary)
                            Text("Android ${apkDetails?.targetSdk ?: 34} (Min: ${apkDetails?.minSdk ?: 26})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Column {
                            Text("Assinatura SHA-256", fontSize = 11.5.sp, color = TextSecondary)
                            Text(
                                text = apkDetails?.sha256Signature ?: "Calculando...",
                                fontSize = 10.sp,
                                color = primaryAccent,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable {
                                        apkDetails?.sha256Signature?.let { copyToClipboard("SHA-256", it) }
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "LISTA DE ARQUIVOS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(8.dp))

                val apkFiles = apkDetails?.files ?: listOf(
                    com.resync.app.engine.ApkFileInfo(
                        fileName = "base.apk",
                        path = app.sourceDir,
                        sizeBytes = File(app.sourceDir).length(),
                        isBase = true
                    )
                )

                apkFiles.forEach { fileInfo ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = DarkSurfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (fileInfo.isBase) Icons.Default.FolderZip else Icons.Default.Extension,
                                contentDescription = null,
                                tint = if (fileInfo.isBase) primaryAccent else AccentElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = fileInfo.fileName,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    if (fileInfo.isBase) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(primaryAccent.copy(alpha = 0.2f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("Base", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                                        }
                                    }
                                }
                                Text(
                                    text = fileInfo.path,
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = formatStorageSize(fileInfo.sizeBytes),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // BOTTOM SHEET "DADOS"
    // ==========================================
    if (showDataBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDataBottomSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Detalhamento de Dados",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Total real: ${formatStorageSize(storageBreakdown?.totalDataBytes ?: 0L)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentSunsetOrange
                        )
                    }

                    if (storageBreakdown?.isMeasuredWithRoot == true) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(StatusSuccess.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Medição Root ✓", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusSuccess)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Detalhamento por partição
                val items = listOf(
                    storageBreakdown?.internalData ?: com.resync.app.engine.StoragePathBreakdown("Dados Internos", "/data/data/${app.packageName}", 0L),
                    storageBreakdown?.externalData ?: com.resync.app.engine.StoragePathBreakdown("Dados Externos", "/storage/emulated/0/Android/data/${app.packageName}", 0L),
                    storageBreakdown?.obbData ?: com.resync.app.engine.StoragePathBreakdown("Arquivos OBB", "/storage/emulated/0/Android/obb/${app.packageName}", 0L),
                    storageBreakdown?.mediaData ?: com.resync.app.engine.StoragePathBreakdown("Mídia", "/storage/emulated/0/Android/media/${app.packageName}", 0L),
                    storageBreakdown?.cacheData ?: com.resync.app.engine.StoragePathBreakdown("Cache", "/data/data/${app.packageName}/cache", 0L)
                )

                items.forEach { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = DarkSurfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = item.title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(
                                    text = item.path,
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = formatStorageSize(item.sizeBytes),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (item.sizeBytes > 0) TextPrimary else TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "O QUE ENTRA NO BACKUP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Toggles para escolher o que entra no backup
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("APK (base e splits)", fontSize = 13.sp, color = TextPrimary)
                            Switch(
                                checked = backupIncludeApk,
                                onCheckedChange = { backupIncludeApk = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = primaryAccent, checkedTrackColor = primaryAccent.copy(alpha = 0.3f))
                            )
                        }
                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Dados Internos (/data/data)", fontSize = 13.sp, color = TextPrimary)
                            Switch(
                                checked = backupIncludeInternalData,
                                onCheckedChange = { backupIncludeInternalData = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = primaryAccent, checkedTrackColor = primaryAccent.copy(alpha = 0.3f))
                            )
                        }
                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Dados Externos (Android/data)", fontSize = 13.sp, color = TextPrimary)
                            Switch(
                                checked = backupIncludeExternalData,
                                onCheckedChange = { backupIncludeExternalData = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = primaryAccent, checkedTrackColor = primaryAccent.copy(alpha = 0.3f))
                            )
                        }
                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Arquivos OBB (Android/obb)", fontSize = 13.sp, color = TextPrimary)
                            Switch(
                                checked = backupIncludeObb,
                                onCheckedChange = { backupIncludeObb = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = primaryAccent, checkedTrackColor = primaryAccent.copy(alpha = 0.3f))
                            )
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // MENU ⋮ (BOTTOM SHEET COMPLETO ESTILO SWIFT BACKUP)
    // ==========================================
    if (showOptionsMenuSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showOptionsMenuSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
            ) {
                // Título
                Text(
                    text = "Ações do Aplicativo",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // CHIPS ROLÁVEIS NA HORIZONTAL
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        title = "Informações",
                        icon = Icons.Default.Info,
                        color = primaryAccent,
                        onClick = {
                            showOptionsMenuSheet = false
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${app.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    )

                    ActionChip(
                        title = "Compartilhar",
                        icon = Icons.Default.Share,
                        color = AccentElectricCyan,
                        onClick = {
                            showOptionsMenuSheet = false
                            shareAppApk()
                        }
                    )

                    ActionChip(
                        title = "Limpar dados",
                        icon = Icons.Default.DeleteSweep,
                        color = StatusError,
                        onClick = {
                            showOptionsMenuSheet = false
                            showClearDataDialog = true
                        }
                    )

                    ActionChip(
                        title = "Play Store",
                        icon = Icons.Default.Shop,
                        color = AccentEmeraldGreen,
                        onClick = {
                            showOptionsMenuSheet = false
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.packageName}")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                    )

                    ActionChip(
                        title = if (isDisabled) "Habilitar" else "Desabilitar",
                        icon = if (isDisabled) Icons.Default.CheckCircle else Icons.Default.Block,
                        color = if (isDisabled) StatusSuccess else StatusWarning,
                        onClick = {
                            showOptionsMenuSheet = false
                            viewModel.setAppEnabled(app.packageName, isDisabled) { ok, err ->
                                if (ok) {
                                    isDisabled = !isDisabled
                                    Toast.makeText(context, if (isDisabled) "App desabilitado com sucesso!" else "App habilitado!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, err ?: "Falha ao alterar estado do app", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )

                    ActionChip(
                        title = "Forçar parada",
                        icon = Icons.Default.PowerSettingsNew,
                        color = StatusError,
                        onClick = {
                            showOptionsMenuSheet = false
                            viewModel.forceStopApp(app.packageName) { ok, err ->
                                if (ok) {
                                    Toast.makeText(context, "Parada forçada executada com sucesso!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, err ?: "Falha ao forçar parada", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = DarkCardBorder)
                Spacer(modifier = Modifier.height(8.dp))

                // 1. Favoritos
                OptionRow(
                    title = "Favoritos",
                    subtitle = if (isFavorite) "Marcado como favorito" else "Não favoritado",
                    icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    iconColor = if (isFavorite) StatusWarning else TextMuted,
                    onClick = {
                        isFavorite = !isFavorite
                        viewModel.setAppFavorite(app.packageName, isFavorite)
                        Toast.makeText(context, if (isFavorite) "Adicionado aos favoritos" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
                    }
                )

                // 2. Definir etiquetas de aplicativos
                OptionRow(
                    title = "Definir etiquetas de aplicativos",
                    subtitle = if (appTags.isEmpty()) "Nenhuma etiqueta definida" else appTags.joinToString(", "),
                    icon = Icons.Default.Label,
                    iconColor = primaryAccent,
                    onClick = {
                        showOptionsMenuSheet = false
                        showTagsDialog = true
                    }
                )

                // 3. Adicionar à lista negra
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isBlacklisted = !isBlacklisted
                            viewModel.setAppBlacklisted(app.packageName, isBlacklisted)
                            Toast.makeText(context, if (isBlacklisted) "Adicionado à lista negra" else "Removido da lista negra", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isBlacklisted) Icons.Default.Security else Icons.Outlined.Security,
                        contentDescription = null,
                        tint = if (isBlacklisted) StatusError else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Adicionar à lista negra", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { showBlacklistHelpDialog = true },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Default.HelpOutline, contentDescription = "Ajuda", tint = primaryAccent, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = if (isBlacklisted) "Ignorado em backups automáticos" else "Incluído normalmente",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = isBlacklisted,
                        onCheckedChange = {
                            isBlacklisted = it
                            viewModel.setAppBlacklisted(app.packageName, it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = StatusError, checkedTrackColor = StatusError.copy(alpha = 0.3f))
                    )
                }

                // 4. Otimização da bateria (switch)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newTarget = !isIgnoringBattery
                            viewModel.setAppBatteryOptimization(app.packageName, newTarget) { ok ->
                                if (ok) {
                                    isIgnoringBattery = newTarget
                                } else {
                                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = if (isIgnoringBattery) StatusSuccess else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Otimização da bateria", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(
                            text = if (isIgnoringBattery) "Sem restrições em segundo plano" else "Otimizado pelo sistema",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = isIgnoringBattery,
                        onCheckedChange = { target ->
                            viewModel.setAppBatteryOptimization(app.packageName, target) { ok ->
                                if (ok) {
                                    isIgnoringBattery = target
                                } else {
                                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryAccent, checkedTrackColor = primaryAccent.copy(alpha = 0.3f))
                    )
                }

                // 5. Adicionar à tela inicial
                OptionRow(
                    title = "Adicionar à tela inicial",
                    subtitle = "Criar atalho rápido na tela inicial do dispositivo",
                    icon = Icons.Default.AddHome,
                    iconColor = primaryAccent,
                    onClick = {
                        showOptionsMenuSheet = false
                        val pm = context.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                        if (launchIntent != null && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                            val shortcut = ShortcutInfoCompat.Builder(context, "shortcut_${app.packageName}")
                                .setShortLabel(app.appName)
                                .setLongLabel(app.appName)
                                .setIntent(launchIntent)
                                .setIcon(IconCompat.createWithResource(context, com.resync.app.R.mipmap.ic_launcher))
                                .build()
                            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                            Toast.makeText(context, "Solicitação de atalho enviada!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Criação de atalhos não suportada por este iniciador.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                // 6. Configurações
                OptionRow(
                    title = "Configurações",
                    subtitle = "Abrir detalhes de notificações e permissões do sistema",
                    icon = Icons.Default.Settings,
                    iconColor = TextSecondary,
                    onClick = {
                        showOptionsMenuSheet = false
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${app.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }
    }

    // Diálogo de Desinstalar
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = { Text("Desinstalar ${app.appName}?", fontWeight = FontWeight.Bold) },
            text = { Text("Deseja realmente remover o aplicativo do dispositivo?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUninstallDialog = false
                        viewModel.uninstallApp(app.packageName) { ok, reason ->
                            if (ok) {
                                Toast.makeText(context, "${app.appName} desinstalado com sucesso!", Toast.LENGTH_SHORT).show()
                                onBack()
                            } else if (reason == "REQUER_INTENT") {
                                val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, reason ?: "Falha ao desinstalar", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Desinstalar", color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    // Diálogo de Limpar Dados
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Limpar dados de ${app.appName}?", fontWeight = FontWeight.Bold) },
            text = { Text("Todos os arquivos, contas, bancos de dados e preferências salvos deste aplicativo serão apagados permanentemente.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDataDialog = false
                        viewModel.clearAppData(app.packageName) { ok, err ->
                            if (ok) {
                                Toast.makeText(context, "Dados limpos com sucesso!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, err ?: "Falha ao limpar dados", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Limpar dados", color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    // Diálogo de Definir Etiquetas
    if (showTagsDialog) {
        var tagsInput by remember { mutableStateOf(appTags.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { showTagsDialog = false },
            title = { Text("Etiquetas de ${app.appName}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Digite as etiquetas separadas por vírgula (ex: Trabalho, Bancos, Jogos):", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tagsInput,
                        onValueChange = { tagsInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        placeholder = { Text("Ex: Essencial, Social", color = TextMuted) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = tagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        appTags = parsed
                        viewModel.setAppTags(app.packageName, parsed)
                        showTagsDialog = false
                        Toast.makeText(context, "Etiquetas salvas!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Salvar", color = primaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTagsDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    // Diálogo de Ajuda da Lista Negra
    if (showBlacklistHelpDialog) {
        AlertDialog(
            onDismissRequest = { showBlacklistHelpDialog = false },
            title = { Text("Sobre a Lista Negra", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Aplicativos adicionados à lista negra não serão incluídos em backups programados ou rotinas de backup em lote.\n\nIsso evita o consumo excessivo de armazenamento interno ou quota do Google Drive com aplicativos temporários ou de grandes dimensões que você não deseja salvar.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { showBlacklistHelpDialog = false }) {
                    Text("Entendi", color = primaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    // Diálogo de confirmação para excluir backup
    if (backupToDelete != null) {
        val (entry, mode) = backupToDelete!!
        AlertDialog(
            onDismissRequest = { backupToDelete = null },
            title = { Text("Apagar backup?", fontWeight = FontWeight.Bold) },
            text = { Text("Deseja realmente excluir este arquivo de backup permanente de ${app.appName}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = backupToDelete
                        backupToDelete = null
                        if (target != null) {
                            viewModel.deleteUnifiedBackup(target.first, deleteMode = target.second) { _, _ -> }
                            Toast.makeText(context, "Backup excluído.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Apagar", color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { backupToDelete = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

@Composable
private fun ActionChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = DarkSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(text = subtitle, fontSize = 11.5.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes.toDouble() / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale("pt", "BR"), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale("pt", "BR"), "%.1f MB", mb)
        else -> String.format(Locale("pt", "BR"), "%.1f KB", kb)
    }
}
