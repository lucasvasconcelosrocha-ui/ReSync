package com.resync.app.ui.screens

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.resync.app.R
import com.resync.app.util.LocaleHelper
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.widget.Toast
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

    var showLanguageBottomSheet by remember { mutableStateOf(false) }
    val currentLangTag = remember { LocaleHelper.getAppLanguage(context) }
    val currentLangSubtitle = remember(currentLangTag) {
        when (currentLangTag) {
            LocaleHelper.LANG_EN -> "English"
            LocaleHelper.LANG_PT_BR -> "Português (Brasil)"
            else -> {
                val isEn = LocaleHelper.getLocale(context).language == "en"
                if (isEn) "System default" else "Padrão do sistema"
            }
        }
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
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back), tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_title),
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
                    Text(stringResource(R.string.settings_visual_theme), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
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

            // SELETOR DE IDIOMA / LANGUAGE
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    SettingNavRow(
                        title = stringResource(R.string.settings_language_title),
                        subtitle = currentLangSubtitle,
                        icon = Icons.Default.Language,
                        onClick = { showLanguageBottomSheet = true }
                    )
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
                        title = stringResource(R.string.settings_permissions_title),
                        subtitle = stringResource(R.string.settings_permissions_desc),
                        icon = Icons.Default.Security,
                        onClick = onNavigateToPermissions
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SettingNavRow(
                        title = stringResource(R.string.settings_dns_title),
                        subtitle = stringResource(R.string.settings_dns_desc),
                        icon = Icons.Default.Dns,
                        onClick = onNavigateToDns
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SettingNavRow(
                        title = stringResource(R.string.settings_setup_wizard_title),
                        subtitle = stringResource(R.string.settings_setup_wizard_desc),
                        icon = Icons.Default.AutoFixHigh,
                        onClick = onNavigateToInitialSetup
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ATUALIZAÇÕES DO APLICATIVO
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
                        Text(stringResource(R.string.settings_app_updates_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_auto_check_title), fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text(stringResource(R.string.settings_auto_check_desc), fontSize = 11.sp, color = TextSecondary)
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
                            Text(stringResource(R.string.settings_current_version, installedVersionName), fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text(stringResource(R.string.settings_build_code, installedVersionCode), fontSize = 11.sp, color = TextSecondary)
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
                                Text(stringResource(R.string.settings_check_now), fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (updateState is UpdateState.UpToDate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_up_to_date, installedVersionName),
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
                            Text("Versão $installedVersionName — API 35 (Android 15+ Ready)", fontSize = 12.sp, color = primaryAccent, fontWeight = FontWeight.SemiBold)
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

    if (showLanguageBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showLanguageBottomSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.75f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                Surface(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(44.dp)
                        .height(4.dp),
                    shape = CircleShape,
                    color = DarkCardBorder.copy(alpha = 0.8f)
                ) {}
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_language_choose),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                val languageOptions = listOf(
                    Triple(LocaleHelper.LANG_SYSTEM, stringResource(R.string.lang_system_default), "Padrão do sistema / System default"),
                    Triple(LocaleHelper.LANG_PT_BR, stringResource(R.string.lang_portuguese), "Português (Brasil)"),
                    Triple(LocaleHelper.LANG_EN, stringResource(R.string.lang_english), "English")
                )

                languageOptions.forEach { (tag, label, desc) ->
                    val isSelected = tag == currentLangTag
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) primaryAccent else DarkCardBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                showLanguageBottomSheet = false
                                LocaleHelper.setAppLanguage(context, tag)
                                (context as? Activity)?.recreate()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    showLanguageBottomSheet = false
                                    LocaleHelper.setAppLanguage(context, tag)
                                    (context as? Activity)?.recreate()
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = primaryAccent,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = if (isSelected) primaryAccent else TextPrimary
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.5.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
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

