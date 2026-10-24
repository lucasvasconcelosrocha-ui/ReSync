package com.resync.app.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.ui.theme.*

data class BackupDataPoint(
    val dateLabel: String,
    val sizeGb: Float,
    val filesCount: Int,
    val appsCount: Int
)

@Composable
fun BackupHistoryLineChart(
    modifier: Modifier = Modifier
) {
    var selectedTimeframe by remember { mutableStateOf("30 dias") }
    val timeframes = listOf("7 dias", "30 dias", "90 dias", "1 ano")

    // Realistic data points according to selected filter
    val dataPoints = remember(selectedTimeframe) {
        when (selectedTimeframe) {
            "7 dias" -> listOf(
                BackupDataPoint("29/09", 2.1f, 310, 1),
                BackupDataPoint("30/09", 2.4f, 420, 1),
                BackupDataPoint("01/10", 3.8f, 650, 2),
                BackupDataPoint("02/10", 4.5f, 790, 2),
                BackupDataPoint("03/10", 5.2f, 850, 2),
                BackupDataPoint("04/10", 6.8f, 1050, 3),
                BackupDataPoint("05/10", 8.4f, 1248, 3)
            )
            "30 dias" -> listOf(
                BackupDataPoint("05/09", 1.2f, 150, 1),
                BackupDataPoint("12/09", 2.5f, 380, 1),
                BackupDataPoint("19/09", 4.1f, 620, 2),
                BackupDataPoint("26/09", 5.8f, 910, 2),
                BackupDataPoint("05/10", 8.4f, 1248, 3)
            )
            "90 dias" -> listOf(
                BackupDataPoint("Jul", 0.8f, 100, 1),
                BackupDataPoint("Ago", 2.9f, 450, 1),
                BackupDataPoint("Set", 5.5f, 890, 2),
                BackupDataPoint("Out", 8.4f, 1248, 3)
            )
            else -> listOf(
                BackupDataPoint("2025", 0.5f, 80, 1),
                BackupDataPoint("Q1", 2.0f, 320, 1),
                BackupDataPoint("Q2", 4.2f, 640, 2),
                BackupDataPoint("Q3", 6.8f, 1010, 3),
                BackupDataPoint("Hoje", 8.4f, 1248, 3)
            )
        }
    }

    var selectedPoint by remember { mutableStateOf<BackupDataPoint?>(dataPoints.lastOrNull()) }

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
                    text = "Tamanho dos Backups",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Text(
                    text = "Evolução do volume de dados protegidos",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Timeframe selector tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBg.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            for (tf in timeframes) {
                val isSelected = tf == selectedTimeframe
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) AccentNeonBlue.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable {
                            selectedTimeframe = tf
                            selectedPoint = null
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tf,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) AccentNeonBlue else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Line Chart Canvas
        val maxVal = remember(dataPoints) { (dataPoints.maxOfOrNull { it.sizeGb } ?: 10f) * 1.2f }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .pointerInput(dataPoints) {
                    detectTapGestures { offset ->
                        val stepX = size.width / (dataPoints.size - 1).coerceAtLeast(1)
                        val clickedIndex = ((offset.x + (stepX / 2)) / stepX).toInt().coerceIn(0, dataPoints.lastIndex)
                        selectedPoint = dataPoints[clickedIndex]
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height - 30.dp.toPx()
                val stepX = width / (dataPoints.size - 1).coerceAtLeast(1)

                // Draw horizontal grid lines
                val gridLines = 4
                for (i in 0..gridLines) {
                    val y = height * (i.toFloat() / gridLines)
                    drawLine(
                        color = DarkCardBorder.copy(alpha = 0.15f),
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Construct smooth curve path
                val strokePath = Path()
                val fillPath = Path()

                val points = dataPoints.mapIndexed { index, dp ->
                    val x = index * stepX
                    val y = height - ((dp.sizeGb / maxVal) * height)
                    Offset(x, y)
                }

                if (points.isNotEmpty()) {
                    strokePath.moveTo(points[0].x, points[0].y)
                    fillPath.moveTo(points[0].x, height)
                    fillPath.lineTo(points[0].x, points[0].y)

                    for (i in 0 until points.size - 1) {
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val cx = (p1.x + p2.x) / 2
                        strokePath.cubicTo(cx, p1.y, cx, p2.y, p2.x, p2.y)
                        fillPath.cubicTo(cx, p1.y, cx, p2.y, p2.x, p2.y)
                    }

                    fillPath.lineTo(points.last().x, height)
                    fillPath.close()

                    // Gradient fill under curve
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                AccentNeonBlue.copy(alpha = 0.35f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = height
                        )
                    )

                    // Line stroke
                    drawPath(
                        path = strokePath,
                        color = AccentNeonBlue,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Draw circles at data points
                    for (p in points) {
                        drawCircle(color = DarkBg, radius = 5.dp.toPx(), center = p)
                        drawCircle(color = AccentNeonBlue, radius = 3.dp.toPx(), center = p)
                    }

                    // Highlight selected point
                    selectedPoint?.let { sp ->
                        val idx = dataPoints.indexOf(sp)
                        if (idx in points.indices) {
                            val selP = points[idx]
                            drawCircle(color = AccentElectricCyan.copy(alpha = 0.3f), radius = 12.dp.toPx(), center = selP)
                            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = selP)
                        }
                    }
                }
            }
        }

        // Selected Point Tooltip card
        if (selectedPoint != null) {
            val pt = selectedPoint!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkBg.copy(alpha = 0.7f))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = pt.dateLabel, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(
                        text = "Backup: ${pt.sizeGb} GB",
                        color = AccentNeonBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Arquivos: ${pt.filesCount}", color = TextSecondary, fontSize = 12.sp)
                    Text(text = "Aplicativos: ${pt.appsCount}", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

