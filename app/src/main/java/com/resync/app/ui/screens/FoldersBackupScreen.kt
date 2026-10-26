package com.resync.app.ui.screens

import android.net.Uri
import android.os.Environment
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel
import com.resync.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ManagedFolderItem(
    val id: String,
    val name: String,
    val path: String,
    val totalSizeBytes: Long,
    val fileCount: Int,
    val isAutoSync: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersBackupScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val themeAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val primaryAccent = getAccentColor(themeAccent)

    var managedFolders by remember {
        val initialList = mutableListOf<ManagedFolderItem>()
        // Sugestões padrão comuns
        val dcim = File(Environment.getExternalStorageDirectory(), "DCIM")
        if (dcim.exists()) {
            val count = dcim.listFiles()?.size ?: 0
            val size = dcim.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            initialList.add(ManagedFolderItem("dcim", "DCIM (Fotos)", dcim.absolutePath, size, count))
        }
        val docs = File(Environment.getExternalStorageDirectory(), "Documents")
        if (docs.exists()) {
            val count = docs.listFiles()?.size ?: 0
            val size = docs.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            initialList.add(ManagedFolderItem("docs", "Documentos", docs.absolutePath, size, count))
        }
        val downloads = File(Environment.getExternalStorageDirectory(), "Download")
        if (downloads.exists()) {
            val count = downloads.listFiles()?.size ?: 0
            val size = downloads.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            initialList.add(ManagedFolderItem("downloads", "Downloads", downloads.absolutePath, size, count))
        }
        mutableStateOf(initialList.toList())
    }

    var isScanning by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val path = uri.path ?: uri.toString()
                val folderName = uri.lastPathSegment?.split(":")?.lastOrNull() ?: "Nova Pasta"
                val newItem = ManagedFolderItem(
                    id = uri.toString(),
                    name = folderName,
                    path = path,
                    totalSizeBytes = 0L,
                    fileCount = 0
                )
                withContext(Dispatchers.Main) {
                    managedFolders = managedFolders + newItem
                    Toast.makeText(context, "Pasta adicionada: $folderName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Backup de Pastas",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "Adicionar pasta",
                            tint = primaryAccent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StandardCardBg),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StandardCardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderSpecial,
                            contentDescription = null,
                            tint = primaryAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sincronização de Pastas",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Gerencie pastas locais para backup de arquivos ou sincronização direta com a nuvem.",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PASTAS CONFIGURADAS (${managedFolders.size})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 0.5.sp
                    )

                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = primaryAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Adicionar pasta",
                            fontSize = 12.sp,
                            color = primaryAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (managedFolders.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FolderOff,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Nenhuma pasta configurada ainda",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            } else {
                items(managedFolders, key = { it.id }) { folder ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StandardCardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StandardCardBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(primaryAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = primaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.name,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = folder.path,
                                        fontSize = 10.5.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        managedFolders = managedFolders.filter { it.id != folder.id }
                                        Toast.makeText(context, "Pasta removida da lista", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remover",
                                        tint = TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.4f), thickness = 0.8.dp)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (folder.totalSizeBytes > 0L) FormatUtils.formatBytes(folder.totalSizeBytes) else "Diretório local",
                                    fontSize = 11.5.sp,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium
                                )

                                Button(
                                    onClick = {
                                        Toast.makeText(context, "Sincronização de ${folder.name} iniciada!", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Fazer backup",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
