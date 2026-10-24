package com.resync.app.ui.components.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.ui.theme.*

enum class AppStatusCategory(val displayName: String, val color: Color) {
    WITH_BACKUP("Com backup", StatusSuccess),
    WITHOUT_BACKUP("Sem backup", TextMuted),
    OUTDATED("Desatualizado", StatusWarning),
    ERROR("Erro", StatusError)
}

@Composable
fun AppStatusDistributionChart(
    withBackupCount: Int,
    withoutBackupCount: Int,
    outdatedCount: Int,
    errorCount: Int,
    modifier: Modifier = Modifier,
    onCategoryClick: (AppStatusCategory) -> Unit = {}
) {
    val total = (withBackupCount + withoutBackupCount + outdatedCount + errorCount).coerceAtLeast(1)

    val withBackupWeight = (withBackupCount.toFloat() / total).coerceAtLeast(0.02f)
    val withoutBackupWeight = (withoutBackupCount.toFloat() / total).coerceAtLeast(0.02f)
    val outdatedWeight = (outdatedCount.toFloat() / total).coerceAtLeast(0.02f)
    val errorWeight = (errorCount.toFloat() / total).coerceAtLeast(0.02f)

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
            Text(
                text = "Status dos Aplicativos",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
            Text(
                text = "$total apps instalados",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Segmented Horizontal Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
        ) {
            if (withBackupCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(withBackupWeight)
                        .fillMaxHeight()
                        .background(StatusSuccess)
                        .clickable { onCategoryClick(AppStatusCategory.WITH_BACKUP) }
                )
            }
            if (outdatedCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(outdatedWeight)
                        .fillMaxHeight()
                        .background(StatusWarning)
                        .clickable { onCategoryClick(AppStatusCategory.OUTDATED) }
                )
            }
            if (withoutBackupCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(withoutBackupWeight)
                        .fillMaxHeight()
                        .background(TextMuted)
                        .clickable { onCategoryClick(AppStatusCategory.WITHOUT_BACKUP) }
                )
            }
            if (errorCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(errorWeight)
                        .fillMaxHeight()
                        .background(StatusError)
                        .clickable { onCategoryClick(AppStatusCategory.ERROR) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Interactive Category Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CategoryStatItem(
                category = AppStatusCategory.WITH_BACKUP,
                count = withBackupCount,
                onClick = { onCategoryClick(AppStatusCategory.WITH_BACKUP) }
            )
            CategoryStatItem(
                category = AppStatusCategory.OUTDATED,
                count = outdatedCount,
                onClick = { onCategoryClick(AppStatusCategory.OUTDATED) }
            )
            CategoryStatItem(
                category = AppStatusCategory.WITHOUT_BACKUP,
                count = withoutBackupCount,
                onClick = { onCategoryClick(AppStatusCategory.WITHOUT_BACKUP) }
            )
            CategoryStatItem(
                category = AppStatusCategory.ERROR,
                count = errorCount,
                onClick = { onCategoryClick(AppStatusCategory.ERROR) }
            )
        }
    }
}

@Composable
private fun CategoryStatItem(
    category: AppStatusCategory,
    count: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(category.color))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = count.toString(), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
        }
        Text(text = category.displayName, fontSize = 11.sp, color = TextSecondary)
    }
}

