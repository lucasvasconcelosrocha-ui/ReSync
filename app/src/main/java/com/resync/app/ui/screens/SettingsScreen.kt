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

            // 3. SEGURANÇA E CRIPTOGRAFIA (NÍVEL MILITAR)
            val cryptoStrategy by viewModel.cryptoStrategy.collectAsStateWithLifecycle()
            var enteredPassword by remember { mutableStateOf(viewModel.getAdvancedPassword() ?: "") }
            var isPasswordVisible by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.EnhancedEncryption, contentDescription = null, tint = AccentEmeraldGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Segurança e Criptografia", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentEmeraldGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("AES-256-GCM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentEmeraldGreen)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Todos os dados são autenticados e criptografados em streaming AEAD antes de saírem do dispositivo.",
                        fontSize = 11.5.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Estratégia: Conta
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (cryptoStrategy == "ACCOUNT") primaryAccent.copy(alpha = 0.1f) else DarkBg,
                        border = BorderStroke(1.dp, if (cryptoStrategy == "ACCOUNT") primaryAccent else DarkCardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setCryptoStrategy("ACCOUNT") }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = cryptoStrategy == "ACCOUNT",
                                onClick = { viewModel.setCryptoStrategy("ACCOUNT") },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Conta do Google (Padrão seguro)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Chaves vinculadas ao hardware Android KeyStore e sua conta Google. Restauração automática perfeita.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Estratégia: Avançado (Senha)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (cryptoStrategy == "ADVANCED") primaryAccent.copy(alpha = 0.1f) else DarkBg,
                        border = BorderStroke(1.dp, if (cryptoStrategy == "ADVANCED") primaryAccent else DarkCardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setCryptoStrategy("ADVANCED") }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = cryptoStrategy == "ADVANCED",
                                onClick = { viewModel.setCryptoStrategy("ADVANCED") },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Avançado (Senha Personalizada)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("PBKDF2-HMAC-SHA256 (600.000 iterações). Proteção militar máxima contra qualquer acesso não autorizado.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    if (cryptoStrategy == "ADVANCED") {
                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = enteredPassword,
                            onValueChange = { enteredPassword = it },
                            label = { Text("Senha mestre do backup", fontSize = 12.sp) },
                            singleLine = true,
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = TextMuted
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryAccent,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Medidor de força da senha
                        val strengthText = when {
                            enteredPassword.isEmpty() -> "Digite uma senha"
                            enteredPassword.length < 8 -> "Fraca (mínimo 8 caracteres)"
                            enteredPassword.length < 12 -> "Média"
                            enteredPassword.any { !it.isLetterOrDigit() } -> "Muito Forte (Militar)"
                            else -> "Forte"
                        }
                        val strengthColor = when {
                            enteredPassword.isEmpty() -> TextMuted
                            enteredPassword.length < 8 -> StatusError
                            enteredPassword.length < 12 -> AccentBrightAmber
                            else -> AccentEmeraldGreen
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Força: $strengthText",
                                fontSize = 11.sp,
                                color = strengthColor,
                                fontWeight = FontWeight.Medium
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (enteredPassword.isBlank()) {
                                            Toast.makeText(context, "Digite uma senha primeiro.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val valid = viewModel.testPassphrase(enteredPassword)
                                            if (valid) {
                                                Toast.makeText(context, "Descriptografia testada e validada com sucesso!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Falha na validação do teste criptográfico.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, DarkCardBorder),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Testar", fontSize = 11.sp, color = TextPrimary)
                                }

                                Button(
                                    onClick = {
                                        if (enteredPassword.length < 6) {
                                            Toast.makeText(context, "A senha deve ter no mínimo 6 caracteres.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.saveAdvancedPassword(enteredPassword)
                                            Toast.makeText(context, "Senha salva de forma segura no Keystore!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Salvar Senha", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Aviso obrigatório sobre perda de senha
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StatusError.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "AVISO OBRIGATÓRIO: O ReSync utiliza criptografia com prova de integridade AES-GCM. Não há recuperação de senha. Se esquecê-la, seus dados serão permanentemente irrecuperáveis.",
                                    fontSize = 10.5.sp,
                                    color = StatusError,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
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

            // DADOS DE APLICATIVOS (CACHE)
            val includeAppCache by viewModel.includeAppCache.collectAsStateWithLifecycle()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.FolderZip, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Dados de Aplicativos", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Incluir cache dos aplicativos", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text("Por padrão, pastas cache/, code_cache/ e no_backup/ são excluídas do backup para economizar espaço e evitar temporários", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = includeAppCache,
                            onCheckedChange = { viewModel.setIncludeAppCache(it) },
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
                            Text("Versão Atual: v$installedVersionName", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
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

