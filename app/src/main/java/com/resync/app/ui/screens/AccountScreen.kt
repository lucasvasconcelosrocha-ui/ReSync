package com.resync.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.resync.app.R
import com.resync.app.util.LocaleHelper
import com.resync.app.util.findActivity
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.BuildConfig
import com.resync.app.ui.components.GoogleDriveAccountsBottomSheet
import com.resync.app.ui.components.UserAvatar
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.DriveQuotaState
import com.resync.app.ui.viewmodel.MainViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val context = LocalContext.current
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)

    val multiAccounts by viewModel.multiCloudAccounts.collectAsStateWithLifecycle()
    val activeAccountEmail by viewModel.activeAccountEmail.collectAsStateWithLifecycle()
    val activeAccount = remember(multiAccounts, activeAccountEmail) {
        multiAccounts.firstOrNull { it.email == activeAccountEmail }
            ?: multiAccounts.firstOrNull { it.isDefault }
            ?: multiAccounts.firstOrNull()
    }

    val deviceStats by viewModel.deviceStats.collectAsStateWithLifecycle()
    val driveQuota by viewModel.driveQuota.collectAsStateWithLifecycle()
    val driveQuotaState by viewModel.driveQuotaState.collectAsStateWithLifecycle()
    val isRefreshingActiveAccount by viewModel.isRefreshingActiveAccount.collectAsStateWithLifecycle()

    LaunchedEffect(activeAccount?.email) {
        activeAccount?.email?.let { email ->
            if (driveQuotaState !is DriveQuotaState.Loaded) {
                viewModel.refreshActiveAccountData(email, isManual = false)
            }
        }
    }

    val deviceModel = remember {
        com.resync.app.util.FormatUtils.getDeviceIdentifier(context)
    }

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showAccountsBottomSheet by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showHelpCenterDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Conta",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ==========================================
            // 1) CARD DE CONTA DO GOOGLE DRIVE
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            photoUrl = activeAccount?.photoUrl,
                            displayName = activeAccount?.name,
                            email = activeAccount?.email,
                            size = 56.dp,
                            borderColor = primaryAccent,
                            borderWidth = 1.5.dp,
                            accentColor = primaryAccent,
                            onClick = { showAccountsBottomSheet = true }
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeAccount?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_google),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = activeAccount?.email ?: stringResource(R.string.account_no_linked),
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(primaryAccent.copy(alpha = 0.15f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(11.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(stringResource(R.string.account_active), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = primaryAccent)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Dispositivo & Ação (sem colisão)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.account_device), fontSize = 11.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(deviceModel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        if (activeAccount != null) {
                            OutlinedButton(
                                onClick = { showSignOutDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null, tint = StatusError, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.account_sign_out), fontSize = 12.sp, color = StatusError, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { showAccountsBottomSheet = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.account_link_account), fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Informações discretas de armazenamento
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    val (driveTotal, driveUsed) = when {
                        driveQuotaState is DriveQuotaState.Loaded -> {
                            val loaded = driveQuotaState as DriveQuotaState.Loaded
                            Pair(loaded.totalBytes, loaded.usedBytes)
                        }
                        activeAccount != null && activeAccount.quotaTotalBytes > 0L -> {
                            Pair(activeAccount.quotaTotalBytes, activeAccount.quotaUsedBytes)
                        }
                        driveQuota != null && (driveQuota?.limitBytes ?: 0L) > 0L -> {
                            Pair(driveQuota!!.limitBytes, driveQuota!!.usageBytes)
                        }
                        else -> Pair(0L, 0L)
                    }
                    val driveFree = maxOf(0L, driveTotal - driveUsed)
                    val driveProgress = if (driveTotal > 0L) (driveUsed.toFloat() / driveTotal.toFloat()).coerceIn(0f, 1f) else 0f

                    val internalTotal = deviceStats.storageTotalBytes
                    val internalUsed = deviceStats.storageUsedBytes
                    val internalFree = deviceStats.storageFreeBytes
                    val internalProgress = if (internalTotal > 0L) (internalUsed.toFloat() / internalTotal.toFloat()).coerceIn(0f, 1f) else 0f

                    val isQuotaError = driveQuotaState is DriveQuotaState.Error

                    // Linha Google Drive
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = activeAccount != null && (driveTotal == 0L || isQuotaError)) {
                                activeAccount?.email?.let { email ->
                                    viewModel.refreshActiveAccountData(email, isManual = true)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Cloud, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Google Drive", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                            }
                            val driveText = when {
                                driveTotal > 0L -> {
                                    "${com.resync.app.util.FormatUtils.formatBytes(driveFree)} livres de ${com.resync.app.util.FormatUtils.formatBytes(driveTotal)}"
                                }
                                activeAccount != null && isRefreshingActiveAccount -> stringResource(R.string.account_querying)
                                activeAccount != null && isQuotaError -> stringResource(R.string.account_tap_retry)
                                activeAccount != null -> stringResource(R.string.account_tap_retry)
                                else -> stringResource(R.string.account_not_connected)
                            }
                            Text(
                                text = driveText,
                                fontSize = 11.sp,
                                color = if (isQuotaError && driveTotal == 0L) StatusError else TextMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { driveProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = primaryAccent,
                            trackColor = DarkSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Linha Armazenamento Interno
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storage, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.account_internal_storage), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                            }
                            val internalText = if (internalTotal > 0L) {
                                stringResource(R.string.account_internal_free_total, com.resync.app.util.FormatUtils.formatBytes(internalFree), com.resync.app.util.FormatUtils.formatBytes(internalTotal))
                            } else {
                                "Consultando..."
                            }
                            Text(internalText, fontSize = 11.sp, color = TextMuted)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { internalProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = AccentElectricCyan,
                            trackColor = DarkSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 2) SEÇÃO DE CONFIGURAÇÕES EM LISTA DE CARDS
            // ==========================================
            Text(
                text = stringResource(R.string.account_section_settings_app),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column {
                    AccountOptionRow(
                        title = stringResource(R.string.dashboard_settings),
                        subtitle = stringResource(R.string.account_settings_desc),
                        icon = Icons.Default.Settings,
                        iconColor = primaryAccent,
                        onClick = onNavigateToSettings
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                    AccountOptionRow(
                        title = stringResource(R.string.account_logger_title),
                        subtitle = stringResource(R.string.account_logger_desc),
                        icon = Icons.Default.Terminal,
                        iconColor = AccentElectricCyan,
                        onClick = onNavigateToLogs
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                    AccountOptionRow(
                        title = stringResource(R.string.account_language),
                        subtitle = LocaleHelper.getLanguageDisplayName(context),
                        icon = Icons.Default.Language,
                        iconColor = AccentEmeraldGreen,
                        onClick = { showLanguageDialog = true }
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                    AccountOptionRow(
                        title = stringResource(R.string.account_help_title),
                        subtitle = stringResource(R.string.account_help_desc),
                        icon = Icons.Default.HelpOutline,
                        iconColor = AccentBrightAmber,
                        onClick = { showHelpCenterDialog = true }
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                    AccountOptionRow(
                        title = stringResource(R.string.account_contact_title),
                        subtitle = stringResource(R.string.account_contact_desc),
                        icon = Icons.Default.Email,
                        iconColor = AccentSunsetOrange,
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:suporte@resync.app?subject=ReSync%20Suporte")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }.onFailure {
                                Toast.makeText(context, context.getString(R.string.account_no_email_app), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                    AccountOptionRow(
                        title = stringResource(R.string.account_rate_title),
                        subtitle = stringResource(R.string.account_rate_desc),
                        icon = Icons.Default.StarRate,
                        iconColor = StatusWarning,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.resync.app")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }.onFailure {
                                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.resync.app")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(webIntent) }
                            }
                        }
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                    AccountOptionRow(
                        title = "Compartilhar o ReSync",
                        subtitle = "Recomende o aplicativo para amigos",
                        icon = Icons.Default.Share,
                        iconColor = AccentNeonPurple,
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "Conheça o ReSync, o melhor aplicativo de backup e restauração para Android: https://github.com/resync-app")
                                type = "text/plain"
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Compartilhar ReSync").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(shareIntent)
                        }
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                    AccountOptionRow(
                        title = "Sobre",
                        subtitle = stringResource(R.string.account_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        icon = Icons.Default.Info,
                        iconColor = TextSecondary,
                        onClick = { showAboutDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    // Modal de Contas do Google Drive
    if (showAccountsBottomSheet) {
        GoogleDriveAccountsBottomSheet(
            viewModel = viewModel,
            onDismiss = { showAccountsBottomSheet = false }
        )
    }

    // Diálogo Sair da Conta
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sair da conta?", fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.account_disconnect_confirm, activeAccount?.email ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val email = activeAccount?.email
                        if (!email.isNullOrBlank()) {
                            viewModel.removeDriveAccount(email)
                        }
                        showSignOutDialog = false
                        Toast.makeText(context, context.getString(R.string.account_disconnected_success), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.account_sign_out), color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary
        )
    }

    // Diálogo de Idioma
    if (showLanguageDialog) {
        val currentTag = LocaleHelper.getAppLanguage(context)
        val langOptions = listOf(
            Triple(LocaleHelper.LANG_SYSTEM, stringResource(R.string.lang_system_default), "System default"),
            Triple(LocaleHelper.LANG_PT_BR, stringResource(R.string.lang_portuguese), "Portuguese"),
            Triple(LocaleHelper.LANG_EN, stringResource(R.string.lang_english), "English")
        )
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language_choose), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    langOptions.forEach { (tag, label, _) ->
                        val isCurrent = tag == currentTag
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showLanguageDialog = false
                                    LocaleHelper.setAppLanguage(context, tag)
                                    context.findActivity()?.recreate()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isCurrent,
                                onClick = {
                                    showLanguageDialog = false
                                    LocaleHelper.setAppLanguage(context, tag)
                                    context.findActivity()?.recreate()
                                }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(label, fontSize = 13.5.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.btn_close), color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary
        )
    }

    // Diálogo Central de Ajuda
    if (showHelpCenterDialog) {
        AlertDialog(
            onDismissRequest = { showHelpCenterDialog = false },
            title = { Text("Central de Ajuda", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Como funciona o ReSync?", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = primaryAccent)
                    Text(stringResource(R.string.account_resync_description), fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.account_gdrive_sync_title), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = primaryAccent)
                    Text(stringResource(R.string.account_gdrive_sync_desc), fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Requisitos de Root", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = primaryAccent)
                    Text(stringResource(R.string.account_root_access_desc), fontSize = 12.sp, color = TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpCenterDialog = false }) {
                    Text("Entendi", color = primaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary
        )
    }

    // Diálogo Sobre
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Sobre o ReSync", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("ReSync", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = primaryAccent)
                    Text(stringResource(R.string.account_complete_solution), fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.account_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), fontSize = 12.5.sp, color = TextPrimary)
                    Text("API do Android: ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})", fontSize = 12.5.sp, color = TextPrimary)
                    Text("Arquitetura: ARM64-v8a", fontSize = 12.5.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Desenvolvido com Jetpack Compose e Material 3.", fontSize = 11.5.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "#TEAMBRASILFELAS\uD83C\uDDE7\uD83C\uDDF7",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryAccent,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showAboutDialog = false },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text("Fechar", color = primaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary
        )
    }
}

@Composable
private fun AccountOptionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(text = subtitle, fontSize = 11.5.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
    }
}
