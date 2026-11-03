package com.resync.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resync.app.data.model.ThemeAccent

fun getAccentColor(accent: ThemeAccent): Color {
    return when (accent) {
        ThemeAccent.BLUE -> AccentNeonBlue
        ThemeAccent.PURPLE -> AccentCyberPurple
        ThemeAccent.GREEN -> AccentEmeraldGreen
        ThemeAccent.CYAN -> AccentElectricCyan
        ThemeAccent.ORANGE -> AccentSunsetOrange
        ThemeAccent.RED -> AccentCrimsonRed
    }
}

val LocalThemeAccent = compositionLocalOf { ThemeAccent.RED }

/** Altura atual do OperationProgressBanner (0.dp quando não há operação ativa).
 *  Telas com Scaffold próprio devem somar esse valor ao padding inferior dos seus bottomBars. */
val LocalBannerHeight = compositionLocalOf { 0.dp }

@Composable
fun ReSyncTheme(
    accent: ThemeAccent = ThemeAccent.RED,
    content: @Composable () -> Unit
) {
    val accentColor = getAccentColor(accent)

    val colorScheme = darkColorScheme(
        primary = accentColor,
        onPrimary = Color.White,
        primaryContainer = accentColor.copy(alpha = 0.22f),
        onPrimaryContainer = Color.White,
        secondary = StatusInfo,
        onSecondary = Color.White,
        secondaryContainer = StatusInfo.copy(alpha = 0.18f),
        onSecondaryContainer = Color.White,
        tertiary = StatusWarning,
        background = DarkBg,
        onBackground = TextPrimary,
        surface = DarkSurface,
        onSurface = TextPrimary,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = TextSecondary,
        outline = BentoBorder,
        outlineVariant = BentoHairline,
        error = StatusError,
        onError = Color.White
    )

    CompositionLocalProvider(LocalThemeAccent provides accent) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = ReSyncShapes,
            typography = ReSyncTypography,
            content = content
        )
    }
}

@Composable
fun SwiftVaultTheme(
    accent: ThemeAccent = ThemeAccent.RED,
    content: @Composable () -> Unit
) = ReSyncTheme(accent, content)
