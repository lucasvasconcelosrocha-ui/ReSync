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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.resync.app.data.room.CloudAccount
import com.resync.app.engine.StorageRepository
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveAccountsBottomSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val multiAccounts by viewModel.multiCloudAccounts.collectAsStateWithLifecycle()
    val activeAccountEmail by viewModel.activeAccountEmail.collectAsStateWithLifecycle()
    val currentAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(currentAccent)

    var pendingConsentIntent by remember { mutableStateOf<Intent?>(null) }
    var accountPendingRemoval by remember { mutableStateOf<CloudAccount?>(null) }

    // Consent Launcher for Google OAuth Web Authorization
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val email = activeAccountEmail
            if (!email.isNullOrBlank()) {
                viewModel.addDriveAccount(
                    email = email,
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
                viewModel.addDriveAccount(
                    email = accountName,
                    onAuthNeeded = { pendingConsentIntent = it },
                    onComplete = { addRes ->
                        if (addRes.isSuccess) {
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
        scrimColor = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(primaryAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = primaryAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Contas do Google Drive",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 17.sp
                        )
                        Text(
                            text = if (multiAccounts.isEmpty()) "Nenhuma conta vinculada" else "${multiAccounts.size} de 5 contas conectadas",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (multiAccounts.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Você ainda não possui contas do Google Drive vinculadas ao ReSync.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { launchAccountPicker() },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("CONECTAR GOOGLE DRIVE", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(multiAccounts, key = { it.email }) { acc ->
                        val isActive = acc.email == activeAccountEmail

                        val totalQuota = if (acc.quotaTotalBytes > 0) acc.quotaTotalBytes else 15L * 1024 * 1024 * 1024
                        val freeBytes = maxOf(0L, totalQuota - acc.quotaUsedBytes)
                        val spaceString = if (acc.quotaTotalBytes > 0) {
                            "${StorageRepository.formatFileSize(freeBytes)} livres de ${StorageRepository.formatFileSize(totalQuota)}"
                        } else {
                            "${StorageRepository.formatFileSize(acc.quotaUsedBytes)} usados"
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = if (isActive) 1.5.dp else 1.dp,
                                    color = if (isActive) primaryAccent else DarkCardBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    if (!isActive) {
                                        viewModel.selectDriveAccount(acc.email)
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) primaryAccent.copy(alpha = 0.10f) else DarkSurfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar: foto com Coil ou inicial com cache
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(if (isActive) primaryAccent.copy(alpha = 0.25f) else DarkBg)
                                        .border(1.dp, if (isActive) primaryAccent else DarkCardBorder, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!acc.photoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(acc.photoUrl)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = acc.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = (acc.name.firstOrNull() ?: acc.email.firstOrNull() ?: 'U').uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) primaryAccent else TextPrimary,
                                            fontSize = 17.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = acc.name.ifBlank { acc.email.substringBefore("@") },
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isActive) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Conta ativa",
                                                tint = primaryAccent,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = acc.email,
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Storage,
                                            contentDescription = null,
                                            tint = if (isActive) primaryAccent else TextMuted,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = spaceString,
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                // Botão Remover conta
                                IconButton(
                                    onClick = { accountPendingRemoval = acc },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Remover conta",
                                        tint = StatusError.copy(alpha = 0.85f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (multiAccounts.size < 5) {
                    OutlinedButton(
                        onClick = { launchAccountPicker() },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Adicionar conta", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // Diálogo de confirmação de remoção de conta
    accountPendingRemoval?.let { acc ->
        AlertDialog(
            onDismissRequest = { accountPendingRemoval = null },
            title = {
                Text(
                    text = "Remover conta?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Deseja desconectar a conta ${acc.email} do ReSync? Os backups já armazenados no Google Drive permanecerão intactos.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val emailToRemove = acc.email
                        accountPendingRemoval = null
                        viewModel.removeDriveAccount(emailToRemove)
                        Toast.makeText(context, "Conta removida do ReSync.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("Remover", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountPendingRemoval = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
