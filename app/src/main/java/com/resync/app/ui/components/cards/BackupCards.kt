package com.resync.app.ui.components.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.data.model.BackupRecord
import com.resync.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BackupItemCard(
    record: BackupRecord,
    isSelected: Boolean = false,
    primaryAccent: Color,
    hasLocal: Boolean = false,
    hasDrive: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit
) {
    val dateFormatted = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    val isDriveRecord = record.destination.contains("Drive", ignoreCase = true) || !record.driveFileId.isNullOrBlank()
    val isBothLocations = (hasLocal && hasDrive) || record.destination.contains("Ambos", ignoreCase = true)
    val showLocalBadge = hasLocal || isBothLocations || (!hasDrive && !isDriveRecord)
    val showDriveBadge = hasDrive || isBothLocations || (!hasLocal && isDriveRecord)
    val sizeMb = record.sizeBytes.toDouble() / (1024 * 1024)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (record.isEncrypted) primaryAccent else AccentSunsetOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = record.title,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(StatusSuccess.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "✓ Integridade OK",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = StatusSuccess,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Criado em: $dateFormatted",
                    fontSize = 11.sp,
                    color = TextMuted,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                val (originBadgeText, originBadgeBg, originBadgeColor) = when {
                    isBothLocations -> Triple("☁️ Ambos", primaryAccent.copy(alpha = 0.15f), primaryAccent)
                    hasDrive || isDriveRecord -> Triple("☁️ Google Drive", AccentElectricCyan.copy(alpha = 0.15f), AccentElectricCyan)
                    else -> Triple("📱 Dispositivo", AccentSunsetOrange.copy(alpha = 0.15f), AccentSunsetOrange)
                }

                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(originBadgeBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = originBadgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = originBadgeColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            val itemsSubtitle = if (record.itemCount > 0) " • ${record.itemCount} itens arquivados" else ""
            Text(
                text = "Tamanho: %.2f MB$itemsSubtitle".format(Locale.US, sizeMb),
                fontSize = 12.sp,
                color = primaryAccent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusError)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("EXCLUIR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StatusError)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = onRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("RESTAURAR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LegacyBackupCard(
    title: String,
    description: String,
    dateFormatted: String,
    sizeBytes: Long,
    location: String,
    onDelete: () -> Unit
) {
    val sizeMb = sizeBytes.toDouble() / (1024 * 1024)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StatusWarning.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = StatusWarning,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusWarning,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$dateFormatted • %.1f MB • $location".format(Locale.US, sizeMb),
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = StatusError)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Apagar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusError)
            }
        }
    }
}
