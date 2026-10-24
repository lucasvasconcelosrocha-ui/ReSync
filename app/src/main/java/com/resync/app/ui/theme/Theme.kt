package com.resync.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resync.app.data.model.ThemeAccent

// Deep Dark Palette
val DarkBg = Color(0xFF0A0D14)
val DarkSurface = Color(0xFF121824)
val DarkSurfaceVariant = Color(0xFF1A2232)
val DarkCardBorder = Color(0x28FFFFFF)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

// Standard Card & Action Button Tokens (Padrão Unificado "Status do Root")
val StandardCardBg = DarkSurfaceVariant.copy(alpha = 0.7f)
val StandardCardBorder = DarkCardBorder
val StandardCardShape = RoundedCornerShape(14.dp)

// Status Colors
val StatusSuccess = Color(0xFF10B981)
val StatusInfo = Color(0xFF38BDF8)
val StatusWarning = Color(0xFFF59E0B)
val StatusError = Color(0xFFEF4444)
val StatusAdvanced = Color(0xFFA855F7)

// Accent Colors
val AccentNeonBlue = Color(0xFF2979FF)
val AccentCyberPurple = Color(0xFFA855F7)
val AccentNeonPurple = Color(0xFFA855F7)
val AccentEmeraldGreen = Color(0xFF10B981)
val AccentElectricCyan = Color(0xFF06B6D4)
val AccentSunsetOrange = Color(0xFFF97316)
val AccentBrightAmber = Color(0xFFF59E0B)
val AccentCrimsonRed = Color(0xFFEF4444)

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

val LocalThemeAccent = compositionLocalOf { ThemeAccent.BLUE }

/** Altura atual do OperationProgressBanner (0.dp quando não há operação ativa).
 *  Telas com Scaffold próprio devem somar esse valor ao padding inferior dos seus bottomBars. */
val LocalBannerHeight = compositionLocalOf { 0.dp }

@Composable
fun ReSyncTheme(
    accent: ThemeAccent = ThemeAccent.BLUE,
    content: @Composable () -> Unit
) {
    val accentColor = getAccentColor(accent)

    val colorScheme = darkColorScheme(
        primary = accentColor,
        onPrimary = Color.White,
        primaryContainer = accentColor.copy(alpha = 0.25f),
        onPrimaryContainer = Color.White,
        secondary = StatusAdvanced,
        onSecondary = Color.White,
        background = DarkBg,
        onBackground = TextPrimary,
        surface = DarkSurface,
        onSurface = TextPrimary,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = TextSecondary,
        error = StatusError,
        onError = Color.White
    )

    CompositionLocalProvider(LocalThemeAccent provides accent) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

@Composable
fun SwiftVaultTheme(
    accent: ThemeAccent = ThemeAccent.BLUE,
    content: @Composable () -> Unit
) = ReSyncTheme(accent, content)


