package com.resync.app.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.ui.theme.*

data class CloudDailyActivity(
    val dayLabel: String,
    val uploadGb: Float,
    val downloadGb: Float,
    val errors: Int = 0
)

@Composable
fun CloudActivityChart(
    modifier: Modifier = Modifier
) {
    val days = listOf(
        CloudDailyActivity("Seg", 3.2f, 0.4f, 0),
        CloudDailyActivity("Ter", 5.8f, 1.2f, 0),
        CloudDailyActivity("Qua", 8.4f, 2.1f, 0),
        CloudDailyActivity("Qui", 6.1f, 0.8f, 0),
        CloudDailyActivity("Sex", 9.5f, 3.4f, 1),
        CloudDailyActivity("Sáb", 4.2f, 1.0f, 0),
        CloudDailyActivity("Dom", 2.1f, 0.2f, 0)
    )

    val maxVal = 10f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Cloud Activity",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Text(
                    text = "Taxa de transferência e sincronização semanal",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Multi-bar Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height - 24.dp.toPx()
                val barGroupWidth = width / days.size
                val barWidth = 10.dp.toPx()

                // Baseline
                drawLine(
                    color = DarkCardBorder,
                    start = Offset(0f, height),
                    end = Offset(width, height),
                    strokeWidth = 1.dp.toPx()
                )

                days.forEachIndexed { index, item ->
                    val groupCenterX = (index * barGroupWidth) + (barGroupWidth / 2)

                    // Upload Bar
                    val upHeight = (item.uploadGb / maxVal) * height
                    drawRoundRect(
                        color = AccentNeonBlue,
                        topLeft = Offset(groupCenterX - barWidth - 2.dp.toPx(), height - upHeight),
                        size = Size(barWidth, upHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                    )

                    // Download Bar
                    val downHeight = (item.downloadGb / maxVal) * height
                    drawRoundRect(
                        color = AccentEmeraldGreen,
                        topLeft = Offset(groupCenterX + 2.dp.toPx(), height - downHeight),
                        size = Size(barWidth, downHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                    )

                    // Error indicator dot
                    if (item.errors > 0) {
                        drawCircle(
                            color = StatusError,
                            radius = 3.dp.toPx(),
                            center = Offset(groupCenterX, height - upHeight - 8.dp.toPx())
                        )
                    }
                }
            }

            // Labels below
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                for (day in days) {
                    Text(text = day.dayLabel, fontSize = 11.sp, color = TextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentNeonBlue))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Upload (GB)", fontSize = 11.sp, color = TextSecondary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentEmeraldGreen))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Download (GB)", fontSize = 11.sp, color = TextSecondary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StatusError))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Falhas", fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

