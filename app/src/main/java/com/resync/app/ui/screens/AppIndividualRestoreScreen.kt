package com.resync.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.data.model.*
import com.resync.app.engine.RestoreExecutionState
import com.resync.app.engine.RestoreRepository
import com.resync.app.ui.components.CompletionAnimation
import com.resync.app.ui.components.CompletionAnimationSize
import com.resync.app.ui.components.RealAppIcon
import com.resync.app.ui.components.SmoothLinearProgressBar
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.ui.viewmodel.ManifestInspectState
import com.resync.app.util.FormatUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIndividualRestoreScreen(
    app: AppInfo,
    points: List<AppBackupPoint>,
    initialPoint: AppBackupPoint? = null,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val manifestInspectState by viewModel.manifestInspectState.collectAsStateWithLifecycle()
    val restoreProgress by RestoreRepository.restoreProgress.collectAsStateWithLifecycle()
    val executionState by RestoreRepository.executionState.collectAsStateWithLifecycle()

    var selectedPoint by remember(points) {
        mutableStateOf(initialPoint ?: points.firstOrNull())
    }

    // Inspect manifest whenever selectedPoint changes
    LaunchedEffect(selectedPoint?.backupId, selectedPoint?.isDrive) {
        val pt = selectedPoint
        if (pt != null) {
            val rec = viewModel.createBackupRecordFromPoint(pt)
            viewModel.inspectBackup(rec, forceCloudSource = pt.isDrive)
        }
    }

    val inspectedManifest = (manifestInspectState as? ManifestInspectState.Ready)?.manifest
    val appItems = remember(inspectedManifest, app.packageName) {
        if (inspectedManifest == null) emptyList()
        else {
            inspectedManifest.items.filter {
                it.metadata["packageName"] == app.packageName ||
                it.archiveEntryPath.startsWith("apps/${app.packageName}/")
            }
        }
    }

    // Identificação dos componentes granulares do aplicativo
    val apkBaseItems = remember(appItems) {
        appItems.filter {
            it.type == BackupItemType.APP_APK ||
            it.archiveEntryPath.endsWith("base.apk") ||
            (it.archiveEntryPath.endsWith(".apk") && !it.archiveEntryPath.contains("split"))
        }
    }
    val splitItems = remember(appItems) {
        appItems.filter {
            it.type == BackupItemType.APP_SPLIT_APKS ||
            it.archiveEntryPath.contains("split")
        }
    }
    val internalDataItems = remember(appItems) {
        appItems.filter {
            (it.type == BackupItemType.APP_DATA || it.archiveEntryPath.contains("data.tar") || it.archiveEntryPath.contains("internal") || it.archiveEntryPath.contains("user_de")) &&
            !it.archiveEntryPath.contains("ext_data") && !it.archiveEntryPath.contains("obb")
        }
    }
    val externalDataItems = remember(appItems) {
        appItems.filter {
            it.archiveEntryPath.contains("ext_data") || it.archiveEntryPath.contains("external")
        }
    }
    val obbItems = remember(appItems) {
        appItems.filter {
            it.archiveEntryPath.contains("obb") || it.archiveEntryPath.contains("media")
        }
    }
    val permissionItems = remember(appItems) {
        appItems.filter {
            it.archiveEntryPath.contains("permissions") ||
            it.archiveEntryPath.endsWith("metadata.json") ||
            it.archiveEntryPath.contains("settings")
        }
    }

    // Checkboxes do aplicativo
    var checkedApkBase by remember(appItems) { mutableStateOf(true) }
    var checkedSplits by remember(appItems) { mutableStateOf(splitItems.isNotEmpty()) }
    var checkedInternalData by remember(appItems) { mutableStateOf(true) }
    var checkedExternalData by remember(appItems) { mutableStateOf(externalDataItems.isNotEmpty()) }
    var checkedObb by remember(appItems) { mutableStateOf(obbItems.isNotEmpty()) }
    var checkedPermissions by remember(appItems) { mutableStateOf(true) }

    val hasSplits = splitItems.isNotEmpty()
    val hasExternal = externalDataItems.isNotEmpty()
    val hasObb = obbItems.isNotEmpty()

    val totalSelectedSize = remember(
        checkedApkBase, checkedSplits, checkedInternalData, checkedExternalData, checkedObb, checkedPermissions,
        apkBaseItems, splitItems, internalDataItems, externalDataItems, obbItems, selectedPoint
    ) {
        var bytes = 0L
        if (checkedApkBase) bytes += apkBaseItems.sumOf { it.sizeBytes }
        if (checkedSplits && hasSplits) bytes += splitItems.sumOf { it.sizeBytes }
        if (checkedInternalData) bytes += internalDataItems.sumOf { it.sizeBytes }
        if (checkedExternalData && hasExternal) bytes += externalDataItems.sumOf { it.sizeBytes }
        if (checkedObb && hasObb) bytes += obbItems.sumOf { it.sizeBytes }
        if (bytes == 0L && selectedPoint != null) {
            bytes = selectedPoint!!.appSizeBytes
        }
        bytes
    }

    val isRestoring = executionState == RestoreExecutionState.STARTING ||
                      executionState == RestoreExecutionState.DOWNLOADING ||
                      executionState == RestoreExecutionState.VERIFYING ||
                      executionState == RestoreExecutionState.DECRYPTING ||
                      executionState == RestoreExecutionState.RESTORING
    val isFinished = executionState == RestoreExecutionState.COMPLETED

    BackHandler(enabled = true) {
        if (isRestoring) {
            Toast.makeText(context, "A restauração está em andamento", Toast.LENGTH_SHORT).show()
        } else {
            onBack()
        }
    }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isRestoring) {
                                Toast.makeText(context, "A restauração está em andamento", Toast.LENGTH_SHORT).show()
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Restaurar Aplicativo",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.appName,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)
            }
        },
        bottomBar = {
            if (!isRestoring && !isFinished) {
                Surface(
                    color = DarkSurface,
                    border = BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = BorderStroke(1.dp, DarkCardBorder),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("CANCELAR", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val pt = selectedPoint ?: return@Button
                                val rec = viewModel.createBackupRecordFromPoint(pt)
                                val chosenIds = mutableListOf<String>()
                                if (checkedApkBase) chosenIds.addAll(apkBaseItems.map { it.id })
                                if (checkedSplits) chosenIds.addAll(splitItems.map { it.id })
                                if (checkedInternalData) chosenIds.addAll(internalDataItems.map { it.id })
                                if (checkedExternalData) chosenIds.addAll(externalDataItems.map { it.id })
                                if (checkedObb) chosenIds.addAll(obbItems.map { it.id })
                                if (checkedPermissions) chosenIds.addAll(permissionItems.map { it.id })

                                if (chosenIds.isEmpty() && appItems.isNotEmpty()) {
                                    chosenIds.addAll(appItems.map { it.id })
                                } else if (chosenIds.isEmpty() && pt.itemIds.isNotEmpty()) {
                                    chosenIds.addAll(pt.itemIds)
                                }

                                val cacheFile = (manifestInspectState as? ManifestInspectState.Ready)?.fileToRestore
                                viewModel.launchRestore(
                                    record = rec,
                                    selectedItemIds = chosenIds.toSet(),
                                    downloadedCacheFile = cacheFile
                                )
                            },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(12.dp),
                            enabled = selectedPoint != null && (checkedApkBase || checkedInternalData || checkedExternalData || checkedObb)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RESTAURAR", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (isRestoring || isFinished) {
            // Tela de progresso de restauração exclusiva do aplicativo
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isFinished) {
                    CompletionAnimation(size = CompletionAnimationSize.STANDARD)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Restauração Concluída!",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${app.appName} foi restaurado com sucesso.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onFinished,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(46.dp)
                    ) {
                        Text("CONCLUIR", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }
                } else {
                    RealAppIcon(packageName = app.packageName, size = 64.dp)
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "Restaurando ${app.appName}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = restoreProgress?.stage ?: "Processando arquivos...",
                        fontSize = 13.sp,
                        color = primaryAccent
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    SmoothLinearProgressBar(
                        progress = restoreProgress?.progressPercent ?: 0.1f,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(6.dp),
                        color = primaryAccent
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = restoreProgress?.currentItemName ?: "Extraindo componentes...",
                        fontSize = 12.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp)
            ) {
                // Header com Card do Aplicativo
                item(key = "app_header") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RealAppIcon(packageName = app.packageName, size = 52.dp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = app.packageName,
                                    fontSize = 11.5.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = primaryAccent.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "${points.size} ponto(s) disponível(is)",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (totalSelectedSize > 0L) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = AccentElectricCyan.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = FormatUtils.formatBytes(totalSelectedSize),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentElectricCyan,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Seletor de Pontos de Backup (Nuvem / Dispositivo / Datas)
                item(key = "points_selector") {
                    Text(
                        text = "ORIGEM DO BACKUP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        points.forEach { pt ->
                            val isSelected = pt.backupId == selectedPoint?.backupId && pt.isDrive == selectedPoint?.isDrive
                            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(pt.timestamp))
                            val locBadge = if (pt.isDrive) "Nuvem" else "Local"
                            val locIcon = if (pt.isDrive) Icons.Default.Cloud else Icons.Default.Smartphone
                            val locColor = if (pt.isDrive) AccentElectricCyan else StatusSuccess

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) primaryAccent.copy(alpha = 0.15f) else DarkSurfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) primaryAccent else DarkCardBorder
                                ),
                                modifier = Modifier.clickable { selectedPoint = pt }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = locIcon,
                                        contentDescription = null,
                                        tint = locColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = locBadge,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = locColor
                                            )
                                            if (!pt.versionName.isNullOrBlank()) {
                                                Text(
                                                    text = " • v${pt.versionName}",
                                                    fontSize = 10.sp,
                                                    color = TextMuted
                                                )
                                            }
                                        }
                                        Text(
                                            text = dateStr,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) TextPrimary else TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Lista de Componentes Granulares do Aplicativo
                item(key = "components_header") {
                    Text(
                        text = "COMPONENTES PARA RESTAURAR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 6.dp)
                    )
                }

                if (manifestInspectState is ManifestInspectState.InspectingLocal || manifestInspectState is ManifestInspectState.DownloadingFromDrive) {
                    item(key = "loading_manifest") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryAccent, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Lendo componentes do backup...", fontSize = 13.sp, color = TextSecondary)
                            }
                        }
                    }
                } else {
                    // 1. APK Base
                    item(key = "comp_apk_base") {
                        val apkSize = apkBaseItems.sumOf { it.sizeBytes }
                        val versionDisplay = selectedPoint?.versionName?.let { "v$it" } ?: "Instalador base"
                        AppRestoreOptionCard(
                            title = "APK Base",
                            subtitle = "$versionDisplay • ${FormatUtils.formatBytes(apkSize)}",
                            checked = checkedApkBase,
                            onCheckedChange = { checkedApkBase = it },
                            icon = Icons.Default.Android,
                            accentColor = AccentEmeraldGreen
                        )
                    }

                    // 2. Split APKs
                    if (hasSplits) {
                        item(key = "comp_splits") {
                            val splitsSize = splitItems.sumOf { it.sizeBytes }
                            AppRestoreOptionCard(
                                title = "Split APKs",
                                subtitle = "${splitItems.size} arquivos de arquitetura/idioma • ${FormatUtils.formatBytes(splitsSize)}",
                                checked = checkedSplits,
                                onCheckedChange = { checkedSplits = it },
                                icon = Icons.Default.Extension,
                                accentColor = AccentNeonBlue
                            )
                        }
                    }

                    // 3. Dados Internos
                    item(key = "comp_internal_data") {
                        val intSize = internalDataItems.sumOf { it.sizeBytes }
                        val intSizeText = if (intSize > 0L) FormatUtils.formatBytes(intSize) else "Dados salvos no pacote"
                        AppRestoreOptionCard(
                            title = "Dados Internos",
                            subtitle = "/data/data/${app.packageName} • $intSizeText",
                            checked = checkedInternalData,
                            onCheckedChange = { checkedInternalData = it },
                            icon = Icons.Default.Folder,
                            accentColor = StatusWarning
                        )
                    }

                    // 4. Dados Externos
                    if (hasExternal) {
                        item(key = "comp_external_data") {
                            val extSize = externalDataItems.sumOf { it.sizeBytes }
                            AppRestoreOptionCard(
                                title = "Dados Externos",
                                subtitle = "Android/data/${app.packageName} • ${FormatUtils.formatBytes(extSize)}",
                                checked = checkedExternalData,
                                onCheckedChange = { checkedExternalData = it },
                                icon = Icons.Default.FolderShared,
                                accentColor = AccentSunsetOrange
                            )
                        }
                    }

                    // 5. OBB e Mídia
                    if (hasObb) {
                        item(key = "comp_obb") {
                            val obbSize = obbItems.sumOf { it.sizeBytes }
                            AppRestoreOptionCard(
                                title = "OBB e Mídia",
                                subtitle = "Android/obb/${app.packageName} • ${FormatUtils.formatBytes(obbSize)}",
                                checked = checkedObb,
                                onCheckedChange = { checkedObb = it },
                                icon = Icons.Default.SportsEsports,
                                accentColor = AccentElectricCyan
                            )
                        }
                    }

                    // 6. Permissões
                    item(key = "comp_permissions") {
                        AppRestoreOptionCard(
                            title = "Permissões",
                            subtitle = "Restaurar permissões de sistema e configurações concedidas",
                            checked = checkedPermissions,
                            onCheckedChange = { checkedPermissions = it },
                            icon = Icons.Default.Security,
                            accentColor = AccentCyberPurple
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRestoreOptionCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (checked) accentColor.copy(alpha = 0.4f) else DarkCardBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable { onCheckedChange(!checked) },
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = accentColor,
                    uncheckedColor = TextMuted,
                    checkmarkColor = Color.Black
                )
            )
        }
    }
}
