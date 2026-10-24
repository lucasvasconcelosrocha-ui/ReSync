package com.resync.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.ui.navigation.Screen
import com.resync.app.ui.theme.DarkCardBorder
import com.resync.app.ui.theme.DarkSurfaceVariant
import com.resync.app.ui.theme.TextMuted

private data class NavItemData(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun FloatingBottomNavBar(
    currentRoute: String?,
    primaryAccent: Color,
    onNavigateToDashboard: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToCloud: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember(currentRoute, primaryAccent) {
        listOf(
            NavItemData(
                route = Screen.Dashboard.route,
                title = "Início",
                icon = Icons.Default.Home,
                onClick = onNavigateToDashboard
            ),
            NavItemData(
                route = Screen.AppsList.route,
                title = "Aplicativos",
                icon = Icons.Default.Apps,
                onClick = onNavigateToApps
            ),
            NavItemData(
                route = Screen.FileExplorer.route,
                title = "Arquivos",
                icon = Icons.Default.Folder,
                onClick = onNavigateToFiles
            ),
            NavItemData(
                route = Screen.CloudSync.route,
                title = "Nuvem",
                icon = Icons.Default.CloudQueue,
                onClick = onNavigateToCloud
            ),
            NavItemData(
                route = Screen.Account.route,
                title = "Conta",
                icon = Icons.Default.Person,
                onClick = onNavigateToSettings
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp)
            .padding(bottom = 8.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = primaryAccent.copy(alpha = 0.25f)
                ),
            shape = RoundedCornerShape(24.dp),
            color = DarkSurfaceVariant,
            border = BorderStroke(1.dp, DarkCardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route

                    val itemContentColor by animateColorAsState(
                        targetValue = if (isSelected) primaryAccent else TextMuted,
                        animationSpec = tween(durationMillis = 200),
                        label = "tabContent_${item.route}"
                    )

                    val indicatorBg by animateColorAsState(
                        targetValue = if (isSelected) primaryAccent.copy(alpha = 0.16f) else Color.Transparent,
                        animationSpec = tween(durationMillis = 200),
                        label = "tabInd_${item.route}"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(18.dp))
                            .background(indicatorBg)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, color = primaryAccent),
                                onClick = item.onClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = itemContentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = itemContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
