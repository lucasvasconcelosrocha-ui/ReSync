package com.resync.app.ui.components

import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resync.app.ui.theme.DarkBg
import com.resync.app.ui.theme.DarkSurfaceVariant
import com.resync.app.ui.theme.TextPrimary
import com.resync.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Hook para limitar a taxa de atualização VISUAL do nome do arquivo/pasta a ~3 a 4 trocas por segundo
 * (amostragem/conflate de ~280 ms), evitando troca excessiva de texto na tela enquanto a engine continua
 * rodando em velocidade máxima em segundo plano.
 */
@Composable
fun rememberSmoothedItemName(rawName: String, sampleMs: Long = 280L): String {
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0f
        } catch (_: Throwable) { false }
    }

    if (reduceMotion) return rawName

    var smoothedName by remember { mutableStateOf(rawName) }

    LaunchedEffect(Unit) {
        snapshotFlow { rawName }
            .distinctUntilChanged()
            .conflate()
            .collectLatest { name ->
                smoothedName = name
                delay(sampleMs)
            }
    }

    return if (smoothedName.isBlank()) rawName else smoothedName
}

/**
 * Texto animado para o item atual (arquivo/pasta/pacote) com:
 * 1. Throttle visual de ~3 a 4 trocas/s
 * 2. Transição suave de crossfade + leve slide vertical (~180 ms)
 * 3. Reserva de altura fixa para 2 linhas (evita que o card ou layout pule de tamanho)
 * 4. Respeito às opções de acessibilidade de "reduzir animações" do Android.
 */
@Composable
fun SmoothCurrentItemText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    maxLines: Int = 2,
    textAlign: TextAlign = TextAlign.Start,
    reservedHeight: Dp = 38.dp
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0f
        } catch (_: Throwable) { false }
    }

    val smoothed = rememberSmoothedItemName(text)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = reservedHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        if (reduceMotion) {
            Text(
                text = smoothed,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            AnimatedContent(
                targetState = smoothed,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180, easing = LinearEasing)) +
                            slideInVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { h -> h / 3 })
                        .togetherWith(
                            fadeOut(animationSpec = tween(140, easing = LinearEasing)) +
                                    slideOutVertically(animationSpec = tween(140, easing = FastOutSlowInEasing)) { h -> -h / 3 }
                        )
                },
                label = "smooth_file_transition"
            ) { targetText ->
                Text(
                    text = targetText,
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Mostra a fase atual com transição suave (Preparando → Copiando → Verificando → Finalizando).
 */
@Composable
fun SmoothStageText(
    stage: String,
    modifier: Modifier = Modifier,
    color: Color = TextSecondary,
    fontSize: TextUnit = 11.5.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0f
        } catch (_: Throwable) { false }
    }

    val normalizedStage = when {
        stage.contains("Prepar", ignoreCase = true) -> "Preparando dados..."
        stage.contains("Copi", ignoreCase = true) || stage.contains("Transfer", ignoreCase = true) -> "Copiando arquivos..."
        stage.contains("Verific", ignoreCase = true) -> "Verificando integridade..."
        stage.contains("Compact", ignoreCase = true) -> "Compactando container..."
        stage.contains("Finaliz", ignoreCase = true) || stage.contains("Conclu", ignoreCase = true) -> "Finalizando..."
        stage.contains("Drive", ignoreCase = true) || stage.contains("Nuvem", ignoreCase = true) -> "Sincronizando com Drive..."
        stage.isNotBlank() -> stage
        else -> "Processando..."
    }

    if (reduceMotion) {
        Text(
            text = normalizedStage,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    } else {
        AnimatedContent(
            targetState = normalizedStage,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)).togetherWith(fadeOut(animationSpec = tween(160)))
            },
            label = "smooth_stage_transition",
            modifier = modifier
        ) { targetStage ->
            Text(
                text = targetStage,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Barra de progresso linear com animação contínua (tween), sem saltos bruscos, e com
 * indicador discreto de atividade (brilho/shimmer suave que percorre o progresso ativo),
 * mantendo 60 fps e respeitando acessibilidade.
 */
@Composable
fun SmoothLinearProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color,
    trackColor: Color = DarkBg,
    height: Dp = 6.dp,
    shapeRadius: Dp = 3.dp,
    showActivityShimmer: Boolean = true
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0f
        } catch (_: Throwable) { false }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) snap() else tween(350, easing = LinearOutSlowInEasing),
        label = "smooth_progress_bar"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer_bar")
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(shapeRadius))
    ) {
        val totalWidth = size.width
        val barHeight = size.height

        // 1. Trilha de fundo
        drawRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = size
        )

        // 2. Progresso preenchido
        val filledWidth = totalWidth * animatedProgress
        if (filledWidth > 0f) {
            drawRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(filledWidth, barHeight)
            )

            // 3. Indicador discreto de atividade (pulso / shimmer)
            if (showActivityShimmer && !reduceMotion && animatedProgress < 0.999f) {
                val shimmerWidth = (filledWidth * 0.45f).coerceAtLeast(30.dp.toPx())
                val startX = (filledWidth + shimmerWidth) * shimmerPhase - shimmerWidth
                val endX = startX + shimmerWidth

                val brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.28f),
                        Color.Transparent
                    ),
                    startX = startX,
                    endX = endX
                )

                drawRect(
                    brush = brush,
                    topLeft = Offset.Zero,
                    size = Size(filledWidth, barHeight)
                )
            }
        }
    }
}

/**
 * Percentual animado com contagem contínua sem saltos.
 */
@Composable
fun SmoothPercentText(
    percent: Float,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Bold
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0f
        } catch (_: Throwable) { false }
    }

    val targetPct = (percent.coerceIn(0f, 1f) * 100).toInt()
    val animatedPct by animateIntAsState(
        targetValue = targetPct,
        animationSpec = if (reduceMotion) snap() else tween(300),
        label = "smooth_pct_text"
    )

    Text(
        text = "$animatedPct%",
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier
    )
}

/**
 * Contador de lotes / itens animado suavemente (ex.: "142/2078").
 */
@Composable
fun SmoothBatchCounterText(
    currentIndex: Int,
    totalItems: Int,
    modifier: Modifier = Modifier,
    color: Color = TextSecondary,
    fontSize: TextUnit = 11.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    prefix: String = ""
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0f
        } catch (_: Throwable) { false }
    }

    val animatedIndex by animateIntAsState(
        targetValue = currentIndex.coerceAtLeast(0),
        animationSpec = if (reduceMotion) snap() else tween(280),
        label = "smooth_batch_counter"
    )

    Text(
        text = if (prefix.isNotBlank()) "$prefix $animatedIndex de $totalItems" else "$animatedIndex/$totalItems",
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier
    )
}
