package com.resync.app.ui.components

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Storage
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
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel

/**
 * Reusable modal bottom sheet to select Google Drive target account for backup uploads.
 * Displays connected accounts, free quota, active account preselection, and account add flow.
 * The selection is scoped strictly to the current upload operation and does NOT mutate global active account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountPickerModal(
    viewModel: MainViewModel,
    showSimpleConfirmOnly: Boolean = true,
    onConfirm: (selectedEmail: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val multiAccounts by viewModel.multiCloudAccounts.collectAsStateWithLifecycle()
    val activeAccountEmail by viewModel.activeAccountEmail.collectAsStateWithLifecycle()

    var selectedEmail by remember(multiAccounts, activeAccountEmail) {
        val initial = activeAccountEmail?.takeIf { active -> multiAccounts.any { it.email == active } }
            ?: multiAccounts.firstOrNull { it.isDefault }?.email
            ?: multiAccounts.firstOrNull()?.email
            ?: ""
        mutableStateOf(initial)
    }

    var pendingConsentIntent by remember { mutableStateOf<Intent?>(null) }

    // Consent Activity Launcher for Google OAuth Web Authorization
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (selectedEmail.isNotBlank()) {
                viewModel.addDriveAccount(
                    email = selectedEmail,
                    onAuthNeeded = { pendingConsentIntent = it },
                    onComplete = { addRes ->
                        if (addRes.isSuccess) {
                            Toast.makeText(context, "Google Drive conectado com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        } else {
            Toast.makeText(context, "Autorização não concluída.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pendingConsentIntent) {
        pendingConsentIntent?.let { intent ->
            pendingConsentIntent = null
            try {
                consentLauncher.launch(intent)
            } catch (t: Throwable) {
                Toast.makeText(context, "Erro ao abrir autorização: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Google Account Chooser Activity Launcher
    val accountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                selectedEmail = accountName
                viewModel.addDriveAccount(
                    email = accountName,
                    onAuthNeeded = { pendingConsentIntent = it },
                    onComplete = { addRes ->
                        if (addRes.isSuccess) {
                            selectedEmail = accountName
                            Toast.makeText(context, "Conta $accountName conectada com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    val launchAccountPicker = {
        try {
            val intent = viewModel.driveRepository.newChooseAccountIntent()
            accountChooserLauncher.launch(intent)
        } catch (_: Throwable) {
            try {
                val fallbackIntent = viewModel.gdriveProvider.oAuthManager.createChooseAccountIntent()
                accountChooserLauncher.launch(fallbackIntent)
            } catch (e2: Throwable) {
                Toast.makeText(context, "Erro ao abrir seletor de contas: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentElectricCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = AccentElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (multiAccounts.isEmpty()) {
                            "Conectar Google Drive"
                        } else if (showSimpleConfirmOnly) {
                            "Confirmar Envio"
                        } else {
                            "Enviar para qual conta?"
                        },
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 17.sp
                    )
                    Text(
                        text = if (multiAccounts.isEmpty()) {
                            "Nenhuma conta conectada para backup na nuvem"
                        } else if (showSimpleConfirmOnly || multiAccounts.size == 1) {
                            "Confirme o envio do backup para o Google Drive"
                        } else {
                            "Selecione o destino deste backup no Google Drive"
                        },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            val activeOrFirstAccount = multiAccounts.firstOrNull { it.email == activeAccountEmail }
                ?: multiAccounts.firstOrNull { it.isDefault }
                ?: multiAccounts.firstOrNull()

            when {
                // CASE 1: No accounts connected
                multiAccounts.isEmpty() -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Você ainda não possui nenhuma conta do Google Drive vinculada ao ReSync.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = { launchAccountPicker() },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentElectricCyan),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("CONECTAR GOOGLE DRIVE", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, DarkCardBorder),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("CANCELAR", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                }

                // CASE 2: Modo simplificado (Backup Rápido) OU apenas 1 conta conectada
                showSimpleConfirmOnly || multiAccounts.size == 1 -> {
                    val targetAcc = activeOrFirstAccount ?: multiAccounts.first()
                    val totalQuota = if (targetAcc.quotaTotalBytes > 0) targetAcc.quotaTotalBytes else 15L * 1024 * 1024 * 1024
                    val freeBytes = maxOf(0L, totalQuota - targetAcc.quotaUsedBytes)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, AccentElectricCyan, RoundedCornerShape(14.dp)),
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
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(AccentElectricCyan.copy(alpha = 0.2f))
                                    .border(1.dp, AccentElectricCyan, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (targetAcc.name.firstOrNull() ?: targetAcc.email.firstOrNull() ?: 'U').uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    color = AccentElectricCyan,
                                    fontSize = 16.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Conta ativa: ${targetAcc.email}",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${StorageRepository.formatFileSize(freeBytes)} livres de ${StorageRepository.formatFileSize(totalQuota)}",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    if (!showSimpleConfirmOnly) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { launchAccountPicker() }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Adicionar outra conta", color = AccentElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DarkCardBorder),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("CANCELAR", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = { onConfirm(targetAcc.email) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentElectricCyan),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("ENVIAR", fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }

                // CASE 3: Multiple accounts connected
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(multiAccounts, key = { it.email }) { acc ->
                            val isSelected = selectedEmail == acc.email
                            val isGloballyActive = acc.email == activeAccountEmail
                            val totalQuota = if (acc.quotaTotalBytes > 0) acc.quotaTotalBytes else 15L * 1024 * 1024 * 1024
                            val freeBytes = maxOf(0L, totalQuota - acc.quotaUsedBytes)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        if (isSelected) 1.5.dp else 1.dp,
                                        if (isSelected) AccentElectricCyan else DarkCardBorder,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { selectedEmail = acc.email },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) DarkSurfaceVariant else DarkSurfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) AccentElectricCyan.copy(alpha = 0.25f) else DarkBg)
                                            .border(1.dp, if (isSelected) AccentElectricCyan else DarkCardBorder, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = (acc.name.firstOrNull() ?: acc.email.firstOrNull() ?: 'U').uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) AccentElectricCyan else TextPrimary,
                                            fontSize = 14.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = acc.name.ifBlank { acc.email.substringBefore("@") },
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isGloballyActive) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = AccentElectricCyan.copy(alpha = 0.15f),
                                                    border = BorderStroke(0.5.dp, AccentElectricCyan.copy(alpha = 0.4f))
                                                ) {
                                                    Text(
                                                        text = "Ativa",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = AccentElectricCyan,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = acc.email,
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${StorageRepository.formatFileSize(freeBytes)} livres de ${StorageRepository.formatFileSize(totalQuota)}",
                                            color = TextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }

                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedEmail = acc.email },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = AccentElectricCyan,
                                            unselectedColor = TextMuted
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (multiAccounts.size < 5) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { launchAccountPicker() }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AccentElectricCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Adicionar conta", color = AccentElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DarkCardBorder),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("CANCELAR", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                if (selectedEmail.isNotBlank()) {
                                    onConfirm(selectedEmail)
                                }
                            },
                            enabled = selectedEmail.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentElectricCyan,
                                disabledContainerColor = AccentElectricCyan.copy(alpha = 0.3f),
                                disabledContentColor = TextMuted
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("ENVIAR", fontWeight = FontWeight.Bold, color = if (selectedEmail.isNotBlank()) Color.Black else TextMuted)
                        }
                    }
                }
            }
        }
    }
}

