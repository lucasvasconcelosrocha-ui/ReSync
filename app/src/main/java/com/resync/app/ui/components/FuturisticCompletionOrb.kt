package com.resync.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Componente Jetpack Compose moderno, holográfico e futurista para finalização de operações (Backup/Restore).
 * Padrão Bento UI OLED (#05070A) com anéis neon concêntricos, ondas de pulso radar, checkmark vetorial com desenho progressivo
 * e interatividade ao toque (haptic feedback + nova onda de pulso holográfica).
 */
@Composable
fun FuturisticCompletionOrb(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    primaryColor: Color = Color(0xFF00FF88),
    secondaryColor: Color = Color(0xFF00E5FF),
    onTap: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Rotação contínua dos anéis concêntricos
    val infiniteTransition = rememberInfiniteTransition(label = "orbital_rings")
    val ringAngle1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_angle_1"
    )
    val ringAngle2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_angle_2"
    )

    // Pulso contínuo suave do núcleo
    val coreGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_glow_alpha"
    )

    // Desenho progressivo do Checkmark ao montar
    var isDrawn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isDrawn = true
    }
    val checkProgress by animateFloatAsState(
        targetValue = if (isDrawn) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "check_draw_progress"
    )

    // Onda de pulso radar expansiva sob demanda ao tocar
    val tapPulseAnim = remember { Animatable(0f) }

    fun triggerTapEffect() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onTap?.invoke()
        coroutineScope.launch {
            tapPulseAnim.snapTo(0f)
            tapPulseAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                triggerTapEffect()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
            val radius = size.toPx() / 2f

            // 1. Brilho Radial OLED de Fundo (Glow)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = coreGlowAlpha * 0.4f),
                        secondaryColor.copy(alpha = coreGlowAlpha * 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 0.95f
                ),
                radius = radius * 0.95f,
                center = center
            )

            // 2. Onda Holográfica Interativa de Toque (Radar Pulse)
            if (tapPulseAnim.value > 0f) {
                val pulseRadius = radius * 0.5f + (radius * 0.5f * tapPulseAnim.value)
                val pulseAlpha = (1f - tapPulseAnim.value).coerceIn(0f, 1f)
                drawCircle(
                    color = secondaryColor.copy(alpha = pulseAlpha * 0.6f),
                    radius = pulseRadius,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx() * (1f - tapPulseAnim.value * 0.5f))
                )
            }

            // 3. Anel Orbital Externo Neon (sentido horário)
            val outerRingRadius = radius * 0.88f
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        primaryColor.copy(alpha = 0.1f),
                        primaryColor,
                        secondaryColor,
                        Color.Transparent
                    ),
                    center = center
                ),
                startAngle = ringAngle1,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(center.x - outerRingRadius, center.y - outerRingRadius),
                size = Size(outerRingRadius * 2, outerRingRadius * 2),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            )

            // 4. Anel Orbital Interno Neon (sentido anti-horário)
            val innerRingRadius = radius * 0.74f
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        secondaryColor,
                        primaryColor.copy(alpha = 0.2f),
                        secondaryColor.copy(alpha = 0.8f),
                        Color.Transparent
                    ),
                    center = center
                ),
                startAngle = ringAngle2,
                sweepAngle = 200f,
                useCenter = false,
                topLeft = Offset(center.x - innerRingRadius, center.y - innerRingRadius),
                size = Size(innerRingRadius * 2, innerRingRadius * 2),
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )

            // 5. Círculo Central Neon Translúcido
            val coreRadius = radius * 0.56f
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.22f),
                        secondaryColor.copy(alpha = 0.12f)
                    ),
                    start = Offset(center.x - coreRadius, center.y - coreRadius),
                    end = Offset(center.x + coreRadius, center.y + coreRadius)
                ),
                radius = coreRadius,
                center = center
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.5f),
                radius = coreRadius,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )

            // 6. Checkmark Vetorial com Desenho Progressivo (PathMeasure)
            if (checkProgress > 0.01f) {
                val fullPath = Path().apply {
                    val startX = center.x - (coreRadius * 0.42f)
                    val startY = center.y + (coreRadius * 0.02f)
                    val midX = center.x - (coreRadius * 0.08f)
                    val midY = center.y + (coreRadius * 0.38f)
                    val endX = center.x + (coreRadius * 0.46f)
                    val endY = center.y - (coreRadius * 0.34f)

                    moveTo(startX, startY)
                    lineTo(midX, midY)
                    lineTo(endX, endY)
                }

                val pathMeasure = PathMeasure().apply {
                    setPath(fullPath, false)
                }
                val length = pathMeasure.length
                val drawPath = Path()
                pathMeasure.getSegment(0f, length * checkProgress.coerceIn(0f, 1f), drawPath, true)

                // Brilho do Checkmark
                drawPath(
                    path = drawPath,
                    color = primaryColor.copy(alpha = 0.35f),
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // Linha Principal do Checkmark
                drawPath(
                    path = drawPath,
                    color = Color.White,
                    style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}
