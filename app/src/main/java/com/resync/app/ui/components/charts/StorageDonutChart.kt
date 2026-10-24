package com.resync.app.ui.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.data.model.DeviceStats
import com.resync.app.ui.theme.*
import kotlin.math.atan2

data class StorageSegment(
    val name: String,
    val bytes: Long,
    val color: Color
)

@Composable
fun StorageDonutChart(
    stats: DeviceStats,
    modifier: Modifier = Modifier,
    onSegmentClick: (StorageSegment) -> Unit = {}
) {
    val segments = remember(stats) {
        listOf(
            StorageSegment("Aplicativos", stats.appsStorageBytes, AccentNeonBlue),
            StorageSegment("Backups", stats.backupStorageUsedBytes, AccentCyberPurple),
            StorageSegment("Fotos", stats.photosBytes, AccentEmeraldGreen),
            StorageSegment("Vídeos", stats.videosBytes, AccentSunsetOrange),
            StorageSegment("Documentos", stats.documentsBytes, AccentElectricCyan),
            StorageSegment("Outros", stats.othersBytes, TextMuted)
        ).filter { it.bytes > 0 }
    }

    val totalUsed = remember(segments) { segments.sumOf { it.bytes }.coerceAtLeast(1L) }
    val totalCapacity = stats.storageTotalBytes.coerceAtLeast(totalUsed)
    val usedPercentage = ((totalUsed.toDouble() / totalCapacity.toDouble()) * 100).toInt()

    var selectedSegment by remember { mutableStateOf<StorageSegment?>(null) }
    var animationPlayed by remember { mutableStateOf(false) }

    val animProgress by animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = tween(durationMillis = 1000),
        label = "donut_anim"
    )

    LaunchedEffect(Unit) {
        animationPlayed = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Armazenamento do Dispositivo",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(190.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val angle = (Math.toDegrees(
                            atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())
                        ) + 360 + 90) % 360

                        var currentAngle = 0.0
                        for (seg in segments) {
                            val sweep = (seg.bytes.toDouble() / totalUsed.toDouble()) * 360.0
                            if (angle >= currentAngle && angle <= currentAngle + sweep) {
                                selectedSegment = seg
                                onSegmentClick(seg)
                                break
                            }
                            currentAngle += sweep
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.size(160.dp)) {
                val strokeWidth = 24.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                val arcSize = Size(radius * 2, radius * 2)

                // Background track
                drawArc(
                    color = DarkBg,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth)
                )

                var startAngle = -90f
                for (seg in segments) {
                    val sweep = ((seg.bytes.toFloat() / totalUsed.toFloat()) * 360f) * animProgress
                    val isSelected = selectedSegment == seg
                    drawArc(
                        color = if (isSelected) seg.color.copy(alpha = 1f) else seg.color.copy(alpha = 0.85f),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(
                            width = if (isSelected) strokeWidth * 1.25f else strokeWidth,
                            cap = StrokeCap.Butt
                        )
                    )
                    startAngle += sweep
                }
            }

            // Center details
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$usedPercentage%",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    text = "${totalUsed / (1024 * 1024 * 1024)} GB / ${totalCapacity / (1024 * 1024 * 1024)} GB",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Selected segment banner or Legend
        if (selectedSegment != null) {
            val seg = selectedSegment!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(seg.color.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(seg.color))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = seg.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                }
                Text(
                    text = "%.2f GB".format(java.util.Locale.US, seg.bytes.toDouble() / (1024 * 1024 * 1024)),
                    color = seg.color,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Legend Flow/Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (seg in segments.take(4)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { selectedSegment = seg }
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(seg.color))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = seg.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

