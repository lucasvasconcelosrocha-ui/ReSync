package com.resync.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.resync.app.cloud.gdrive.CloudThermalGovernor
import com.resync.app.ui.theme.StatusSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*
import kotlin.random.Random

/**
 * Dimensão do componente único de animação de conclusão.
 * O tamanho padrão único para todas as telas e cards de conclusão é STANDARD (96 dp).
 */
enum class CompletionAnimationSize(val dp: androidx.compose.ui.unit.Dp) {
    STANDARD(96.dp),  // Tamanho padrão único (96 dp) para todas as telas e cards de conclusão
    LARGE(96.dp),     // Compatibilidade mapeada para o tamanho padrão único (96 dp)
    COMPACT(24.dp)    // Para pequenos indicadores inline em listas densas
}

/**
 * Estado leve de partícula alocada estaticamente para evitar Garbage Collection a 60 fps.
 */
private class ParticleState {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var radius = 4f
    var alpha = 0f
    var color = Color(0xFF10B981)
    var active = false

    fun init(originX: Float, originY: Float, angleRad: Float, speed: Float, particleColor: Color, size: Float) {
        x = originX
        y = originY
        vx = cos(angleRad) * speed
        vy = sin(angleRad) * speed
        color = particleColor
        radius = size
        alpha = 1f
        active = true
    }

    fun update(friction: Float, decay: Float) {
        if (!active) return
        x += vx
        y += vy
        vx *= friction
        vy *= friction
        alpha = (alpha - decay).coerceAtLeast(0f)

        if (alpha <= 0.01f) {
            active = false
            alpha = 0f
        }
    }
}

/**
 * Componente Único e Padrão de Animação de Finalização (CompletionAnimation / SuccessAnimation).
 *
 * Características e Sequência:
 * 1) Anel desenhando-se (sweep 0 -> 360°)
 * 2) Check traçado com spring/bounce e pulso em ondas (ripple shockwave)
 * 3) Confete curto nas cores do app (azul, ciano, verde esmeralda)
 * 4) Vibração háptica de confirmação no instante do check
 * 5) Entrada em cascata opcional dos textos/conteúdo associado
 * 6) Toca uma única vez por conclusão (salvo via rememberSaveable; não repete em recomposição/rotação)
 * 7) Tocar no check repete a animação sob demanda
 * 8) Respeita a configuração de acessibilidade "reduzir animações" do sistema operacional e mantém 60 fps
 */
@Composable
fun CompletionAnimation(
    modifier: Modifier = Modifier,
    size: CompletionAnimationSize = CompletionAnimationSize.STANDARD,
    themeAccent: Color = StatusSuccess,
    playKey: Any? = Unit,
    onAnimationDone: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    // Respeita acessibilidade do sistema: reduz animações se ANIMATOR_DURATION_SCALE for 0
    val animatorScale = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        } catch (_: Throwable) { 1.0f }
    }
    val reduceMotion = animatorScale == 0f
    val isThermalThrottled = remember { CloudThermalGovernor.isThermalRestricted() }

    // Toca uma única vez por conclusão (não repete ao recompor ou rotacionar a tela)
    var hasPlayedAnimation by rememberSaveable(playKey) { mutableStateOf(false) }

    val ringSweepAnim = remember { Animatable(if (reduceMotion || hasPlayedAnimation) 1f else 0f) }
    val circleScaleAnim = remember { Animatable(if (reduceMotion || hasPlayedAnimation) 1f else 0f) }
    val checkmarkAnim = remember { Animatable(if (reduceMotion || hasPlayedAnimation) 1f else 0f) }
    val rippleAnim = remember { Animatable(if (reduceMotion || hasPlayedAnimation) 1f else 0f) }
    val glowPulse = remember { Animatable(1f) }

    val contentAlpha = remember { Animatable(if (reduceMotion || hasPlayedAnimation) 1f else 0f) }
    val contentOffsetY = remember { Animatable(if (reduceMotion || hasPlayedAnimation) 0f else 14f) }

    val isStandardOrLarge = size != CompletionAnimationSize.COMPACT
    val particleCount = if (isStandardOrLarge) 40 else 16
    val particles = remember { Array(particleCount) { ParticleState() } }
    var particlesTick by remember { mutableLongStateOf(0L) }

    val appConfettiColors = remember {
        listOf(
            Color(0xFF3B82F6), // Azul
            Color(0xFF06B6D4), // Ciano
            Color(0xFF10B981), // Verde
            Color(0xFF34D399), // Verde Esmeralda claro
            Color(0xFF60A5FA)  // Azul céu
        )
    }

    var centerOffsetPx by remember { mutableStateOf(Offset.Zero) }

    fun triggerHapticConfirm() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Throwable) {}
    }

    fun spawnConfetti(origin: Offset) {
        if (reduceMotion || isThermalThrottled || origin == Offset.Zero) return
        val rnd = Random(System.currentTimeMillis())
        val actualCount = particles.size
        val speedMult = if (isStandardOrLarge) 1.0f else 0.5f
        val sizeMult = if (isStandardOrLarge) 1.0f else 0.55f

        for (i in 0 until actualCount) {
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            val speed = (3.5f + rnd.nextFloat() * 6.5f) * speedMult
            val pSize = (3.0f + rnd.nextFloat() * 3.5f) * sizeMult
            val col = appConfettiColors[rnd.nextInt(appConfettiColors.size)]
            particles[i].init(origin.x, origin.y, angle, speed, col, pSize)
        }
        particlesTick++
    }

    fun playFullAnimation() {
        coroutineScope.launch {
            if (reduceMotion) {
                ringSweepAnim.snapTo(1f)
                circleScaleAnim.snapTo(1f)
                checkmarkAnim.snapTo(1f)
                rippleAnim.snapTo(1f)
                contentAlpha.snapTo(1f)
                contentOffsetY.snapTo(0f)
                onAnimationDone?.invoke()
                return@launch
            }

            ringSweepAnim.snapTo(0f)
            circleScaleAnim.snapTo(0.2f)
            checkmarkAnim.snapTo(0f)
            rippleAnim.snapTo(0f)
            contentAlpha.snapTo(0f)
            contentOffsetY.snapTo(14f)

            // 1. Anel desenhando
            ringSweepAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 320, easing = LinearOutSlowInEasing)
            )

            // 2. Escala em bounce e ripple
            launch {
                circleScaleAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                )
            }
            launch {
                rippleAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
                )
            }

            // 3. Traçado do check com vibração háptica
            triggerHapticConfirm()
            launch {
                checkmarkAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
                )
            }

            // 4. Confete curto nas cores do app
            if (centerOffsetPx != Offset.Zero) {
                spawnConfetti(centerOffsetPx)
            }

            // 5. Entrada em cascata do conteúdo de texto
            if (content != null) {
                launch {
                    delay(120L)
                    launch { contentAlpha.animateTo(1f, tween(350, easing = FastOutSlowInEasing)) }
                    launch { contentOffsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
                }
            }

            // 6. Pulso de brilho suave
            repeat(2) {
                glowPulse.animateTo(1.12f, tween(240, easing = FastOutSlowInEasing))
                glowPulse.animateTo(1.0f, tween(300, easing = FastOutSlowInEasing))
            }

            onAnimationDone?.invoke()
        }
    }

    LaunchedEffect(centerOffsetPx) {
        if (centerOffsetPx != Offset.Zero && !hasPlayedAnimation) {
            hasPlayedAnimation = true
            playFullAnimation()
        }
    }

    // Loop de partículas a 60 fps (~16 ms por frame)
    LaunchedEffect(particlesTick, particles.any { it.active }) {
        if (particles.any { it.active }) {
            delay(16L)
            for (p in particles) {
                p.update(friction = 0.93f, decay = 0.018f)
            }
            particlesTick++
        }
    }

    val boxSize = size.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Tocar no check repete a animação
                            triggerHapticConfirm()
                            playFullAnimation()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                if (centerOffsetPx != center) {
                    centerOffsetPx = center
                }

                val minDim = min(this.size.width, this.size.height)
                val baseRadius = minDim * 0.32f
                val ringStrokePx = (minDim * 0.040f).coerceAtLeast(1.8.dp.toPx())
                val checkStrokePx = (minDim * 0.046f).coerceAtLeast(2.0.dp.toPx())
                val rippleMaxExtra = minDim * 0.26f
                val checkScale = minDim / 96.dp.toPx()

                // 1. Pulso em ondas (Ripple Shockwave)
                if (rippleAnim.value > 0f && rippleAnim.value < 1f) {
                    val rippleRadius = baseRadius + (rippleAnim.value * rippleMaxExtra)
                    val rippleAlpha = ((1f - rippleAnim.value) * 0.40f).coerceIn(0f, 1f)
                    drawCircle(
                        color = themeAccent.copy(alpha = rippleAlpha),
                        radius = rippleRadius,
                        center = center,
                        style = Stroke(width = if (isStandardOrLarge) 2.5.dp.toPx() else 1.2.dp.toPx())
                    )
                }

                // 2. Pulso de brilho suave
                if (circleScaleAnim.value > 0.4f && isStandardOrLarge) {
                    val glowAlpha = (circleScaleAnim.value * 0.20f * (glowPulse.value - 0.95f)).coerceIn(0f, 0.35f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(themeAccent.copy(alpha = glowAlpha), Color.Transparent),
                            center = center,
                            radius = baseRadius * 1.4f * glowPulse.value
                        ),
                        center = center,
                        radius = baseRadius * 1.4f * glowPulse.value
                    )
                }

                // 3. Fundo circular central
                val currentRadius = baseRadius * circleScaleAnim.value
                if (currentRadius > 0f) {
                    drawCircle(
                        color = themeAccent.copy(alpha = 0.16f),
                        radius = currentRadius,
                        center = center
                    )
                }

                // 4. Anel verde desenhando (sweep 0 -> 360°)
                if (ringSweepAnim.value > 0f) {
                    val ringDiameter = baseRadius * 2f
                    val ringTopLeft = Offset(center.x - baseRadius, center.y - baseRadius)
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                themeAccent,
                                Color(0xFF34D399),
                                Color(0xFF06B6D4),
                                themeAccent
                            ),
                            center = center
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f * ringSweepAnim.value.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = ringTopLeft,
                        size = Size(ringDiameter, ringDiameter),
                        style = Stroke(width = ringStrokePx, cap = StrokeCap.Round)
                    )
                }

                // 5. Check traçado com spring/bounce
                if (checkmarkAnim.value > 0.01f && currentRadius > 4f) {
                    val scale = checkScale
                    val checkPath = Path().apply {
                        val startX = center.x - (14.dp.toPx() * scale)
                        val startY = center.y + (1.dp.toPx() * scale)
                        val midX = center.x - (3.dp.toPx() * scale)
                        val midY = center.y + (11.dp.toPx() * scale)
                        val endX = center.x + (15.dp.toPx() * scale)
                        val endY = center.y - (10.dp.toPx() * scale)

                        moveTo(startX, startY)
                        lineTo(midX, midY)
                        lineTo(endX, endY)
                    }

                    val measure = PathMeasure()
                    measure.setPath(checkPath, false)
                    val totalLength = measure.length
                    val trimmedPath = Path()
                    measure.getSegment(0f, totalLength * checkmarkAnim.value.coerceIn(0f, 1f), trimmedPath, true)

                    drawPath(
                        path = trimmedPath,
                        color = themeAccent,
                        style = Stroke(
                            width = checkStrokePx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 6. Confete nas cores do app
                if (!reduceMotion && !isThermalThrottled) {
                    for (p in particles) {
                        if (p.active && p.alpha > 0.01f) {
                            drawCircle(
                                color = p.color.copy(alpha = p.alpha),
                                radius = p.radius * p.alpha,
                                center = Offset(p.x, p.y)
                            )
                        }
                    }
                }
            }
        }

        // 7. Entrada em cascata dos textos (opcional)
        if (content != null) {
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = contentAlpha.value
                    translationY = contentOffsetY.value.dp.toPx()
                }
            ) {
                content()
            }
        }
    }
}

/**
 * Compatibilidade legada para telas que invocavam CompletionAnimationView diretamente.
 */
@Composable
fun CompletionAnimationView(
    modifier: Modifier = Modifier,
    size: CompletionAnimationSize = CompletionAnimationSize.LARGE,
    themeAccent: Color = StatusSuccess,
    playKey: Any? = Unit,
    onAnimationDone: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    CompletionAnimation(
        modifier = modifier,
        size = size,
        themeAccent = themeAccent,
        playKey = playKey,
        onAnimationDone = onAnimationDone,
        content = content
    )
}

/**
 * Contador numérico animado para estatísticas nas telas de conclusão.
 */
@Composable
fun AnimatedCountUpText(
    targetValue: Long,
    formatter: (Long) -> String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val animVal = remember { Animatable(0f) }

    LaunchedEffect(targetValue) {
        animVal.snapTo(0f)
        animVal.animateTo(
            targetValue = targetValue.toFloat(),
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
        )
    }

    Text(
        text = formatter(animVal.value.toLong()),
        style = style,
        color = color,
        modifier = modifier
    )
}
