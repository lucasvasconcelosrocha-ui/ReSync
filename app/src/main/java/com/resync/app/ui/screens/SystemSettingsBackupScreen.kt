package com.resync.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel

data class SettingBackupItem(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val status: String,
    val permissionRequired: String,
    val lastBackup: String
)

@Composable
fun SystemSettingsBackupScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val items = listOf(
        SettingBackupItem(
            id = "wallpaper",
            name = "Papel de Parede",
            description = "Extrai e preserva o bitmap de fundo de tela atual do sistema.",
            icon = Icons.Default.Wallpaper,
            status = "Disponível",
            permissionRequired = "READ_WALLPAPER_INTERNAL",
            lastBackup = "Hoje, 03:42"
        ),
        SettingBackupItem(
            id = "dns",
            name = "DNS Privado & Rede",
            description = "Modo de DNS criptografado DoT/DoH e servidores configurados.",
            icon = Icons.Default.Dns,
            status = "Ativo",
            permissionRequired = "Padrão (Sem privilégios especiais)",
            lastBackup = "Hoje, 03:42"
        ),
        SettingBackupItem(
            id = "network_pref",
            name = "Configurações de Rede Legítimas",
            description = "Preferências exportáveis de roaming, dados móveis e limites.",
            icon = Icons.Default.Wifi,
            status = "Exportável",
            permissionRequired = "ACCESS_NETWORK_STATE",
            lastBackup = "Ontem, 18:20"
        ),
        SettingBackupItem(
            id = "app_prefs",
            name = "Preferências do Aplicativo",
            description = "Temas visuais, agendamentos e regras de backup do ReSync.",
            icon = Icons.Default.Tune,
            status = "Pronto",
            permissionRequired = "Nenhuma",
            lastBackup = "Hoje, 03:42"
        ),
        SettingBackupItem(
            id = "sound_display",
            name = "Ajustes de Tela e Som",
            description = "Tempo limite de suspensão de tela, rotação automática e escala de fonte.",
            icon = Icons.Default.BrightnessMedium,
            status = "Disponível",
            permissionRequired = "Settings.System",
            lastBackup = "Pendente"
        )
    )

    val selectedItems = remember { mutableStateMapOf<String, Boolean>().apply {
        put("wallpaper", true)
        put("dns", true)
        put("network_pref", false)
        put("app_prefs", true)
        put("sound_display", false)
    } }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Backup de Configurações",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 12.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = StandardCardBg,
                    border = BorderStroke(1.dp, StandardCardBorder),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedItems.values.count { it }} itens selecionados",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Button(
                            onClick = {
                                viewModel.launchBackup(
                                    title = "Backup de Configurações do Sistema",
                                    selectedApps = emptyList(),
                                    selectedFiles = emptyList(),
                                    includeSms = false,
                                    includeCalls = false,
                                    includeDns = selectedItems["dns"] == true,
                                    includeWallpaper = selectedItems["wallpaper"] == true
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fazer Backup", fontWeight = FontWeight.Bold)
                        }
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Escolha granularmente quais definições do sistema deseja proteger:",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            for (item in items) {
                val isChecked = selectedItems[item.id] == true

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, if (isChecked) primaryAccent else DarkCardBorder, RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { selectedItems[item.id] = it },
                            colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.name,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 15.sp
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(StatusSuccess.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = item.status,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusSuccess
                                    )
                                }
                            }

                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Permissão: ${item.permissionRequired}",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = "Último: ${item.lastBackup}",
                                    fontSize = 11.sp,
                                    color = primaryAccent
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

