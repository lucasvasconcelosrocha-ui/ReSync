package com.resync.app.engine

import android.os.SystemClock
import android.util.Log
import com.resync.app.util.FormatUtils
import java.util.Locale

/**
 * Calculador de estimativa de tempo restante (ETA) em tempo real e velocidade média.
 *
 * Requisitos estritos:
 * - Base do cálculo: progresso REAL e tempo REAL decorrido.
 *   progresso p = média ponderada entre (bytes processados ÷ bytes totais) e (itens/lotes processados ÷ itens/lotes totais);
 *   tempo restante = tempo decorrido × (1 − p) ÷ p.
 * - Total de bytes e de lotes calculado UMA vez no início e mantido estritamente fixo.
 * - Suavização com Média Móvel Exponencial (EMA com janela de ~30 s), mantendo a tendência desde o início.
 * - Fases finais (verificação, compactação, finalização, envio ao Drive) mostram "Finalizando…".
 * - Countdown local: o contador DIMINUI a cada segundo entre os recálculos e recalcula a cada ~3 s.
 * - "Calculando…" só até ~5% ou 15 s. Se pausado, "—".
 * - Formato: "~45 s", "~3 min", "~1 h 10 min".
 * - Log de validação: a cada 5 s, Log.d("ETA") com tempo decorrido, p, bytes, lotes e ETA.
 *   Ao terminar, registra "ETA previsto no início vs tempo real total".
 */
class BackupProgressEstimator(
    val totalBytes: Long,
    val totalItems: Int,
    private val windowSeconds: Double = 30.0
) {
    private val startTimeMs = SystemClock.elapsedRealtime()
    private var lastRecalculationTimeMs = 0L
    private var lastCountdownTickMs = startTimeMs
    private var lastValidationLogMs = 0L

    private var emaRemainingSeconds: Double = -1.0
    private var cachedEstimatedRemainingSec: Long = FormatUtils.ESTIMATE_CALCULATING

    private var lastBytesProcessed: Long = 0L
    private var lastItemsProcessed: Int = 0
    private var lastProgressFraction: Double = 0.0

    private var initialPredictedEtaSec: Long = 0L
    private var isFinalizingPhase: Boolean = false
    private var isOperationPaused: Boolean = false

    private var rollingSpeedBytesSec: Long = 0L
    private var lastSpeedCalcTimeMs = startTimeMs
    private var lastSpeedCalcBytes = 0L

    @Synchronized
    fun setPaused(paused: Boolean) {
        isOperationPaused = paused
    }

    @Synchronized
    fun update(
        bytesProcessed: Long,
        itemsProcessed: Int,
        isFinalPhase: Boolean = false
    ): Long {
        val now = SystemClock.elapsedRealtime()
        lastBytesProcessed = bytesProcessed
        lastItemsProcessed = itemsProcessed
        if (isFinalPhase) {
            isFinalizingPhase = true
        }

        // Medição de velocidade média suavizada em bytes/segundo para o indicador da UI
        val speedDt = (now - lastSpeedCalcTimeMs) / 1000.0
        if (speedDt >= 0.5) {
            val dBytes = (bytesProcessed - lastSpeedCalcBytes).coerceAtLeast(0L)
            val instSpeed = (dBytes / speedDt).toLong()
            rollingSpeedBytesSec = if (rollingSpeedBytesSec <= 0L) instSpeed else ((rollingSpeedBytesSec * 0.7) + (instSpeed * 0.3)).toLong()
            lastSpeedCalcBytes = bytesProcessed
            lastSpeedCalcTimeMs = now
        }

        if (isOperationPaused) {
            cachedEstimatedRemainingSec = FormatUtils.ESTIMATE_ZERO_SPEED
            return cachedEstimatedRemainingSec
        }

        if (isFinalizingPhase) {
            cachedEstimatedRemainingSec = FormatUtils.ESTIMATE_FINALIZING
            return cachedEstimatedRemainingSec
        }

        val elapsedSec = ((now - startTimeMs) / 1000.0).coerceAtLeast(0.001)

        // Progresso p = média ponderada entre (bytes processados ÷ bytes totais) e (itens/lotes processados ÷ itens/lotes totais)
        val pBytes = if (totalBytes > 0) (bytesProcessed.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) else 0.0
        val pItems = if (totalItems > 0) (itemsProcessed.toDouble() / totalItems.toDouble()).coerceIn(0.0, 1.0) else 0.0
        val p = if (totalBytes > 0 && totalItems > 0) {
            (0.60 * pBytes + 0.40 * pItems).coerceIn(0.0, 1.0)
        } else if (totalBytes > 0) {
            pBytes
        } else {
            pItems
        }
        lastProgressFraction = p

        // "Calculando…" só até ~5% ou 15 s
        if (elapsedSec < 15.0 || p < 0.05) {
            cachedEstimatedRemainingSec = FormatUtils.ESTIMATE_CALCULATING
            return cachedEstimatedRemainingSec
        }

        // Fases finais do backup (> 96% de conclusão real)
        if (p >= 0.96) {
            isFinalizingPhase = true
            cachedEstimatedRemainingSec = FormatUtils.ESTIMATE_FINALIZING
            return cachedEstimatedRemainingSec
        }

        // Recalcular a cada ~3 s
        if (now - lastRecalculationTimeMs >= 3000L || lastRecalculationTimeMs == 0L) {
            // tempo restante = tempo decorrido × (1 − p) ÷ p
            val rawRemainingSec = (elapsedSec * (1.0 - p) / p).coerceAtLeast(1.0)

            // Suavizar com média móvel exponencial (janela ~30 s) mantendo a tendência desde o início
            if (emaRemainingSeconds <= 0.0) {
                emaRemainingSeconds = rawRemainingSec
                initialPredictedEtaSec = rawRemainingSec.toLong()
            } else {
                val dt = if (lastRecalculationTimeMs > 0L) (now - lastRecalculationTimeMs) / 1000.0 else 3.0
                val alpha = (dt / windowSeconds).coerceIn(0.05, 0.40)
                emaRemainingSeconds = (alpha * rawRemainingSec) + ((1.0 - alpha) * emaRemainingSeconds)
            }

            lastRecalculationTimeMs = now
            lastCountdownTickMs = now
            cachedEstimatedRemainingSec = emaRemainingSeconds.toLong().coerceAtLeast(1L)

            if (initialPredictedEtaSec == 0L && cachedEstimatedRemainingSec > 0L) {
                initialPredictedEtaSec = cachedEstimatedRemainingSec
            }
        } else {
            // Countdown local entre os recálculos: diminui a cada segundo decorrido
            val dtTick = ((now - lastCountdownTickMs) / 1000L)
            if (dtTick >= 1L && cachedEstimatedRemainingSec > 1L) {
                cachedEstimatedRemainingSec = (cachedEstimatedRemainingSec - dtTick).coerceAtLeast(1L)
                lastCountdownTickMs = now
            }
        }

        logValidationIfNeeded(now, elapsedSec, p, bytesProcessed, itemsProcessed)

        return cachedEstimatedRemainingSec
    }

    /**
     * Chamado pelo ticker de 1 segundo para diminuir o contador a cada segundo entre os recálculos.
     */
    @Synchronized
    fun tickCountdown(): Long {
        if (isOperationPaused) {
            cachedEstimatedRemainingSec = FormatUtils.ESTIMATE_ZERO_SPEED
            return cachedEstimatedRemainingSec
        }
        if (isFinalizingPhase) {
            cachedEstimatedRemainingSec = FormatUtils.ESTIMATE_FINALIZING
            return cachedEstimatedRemainingSec
        }

        val now = SystemClock.elapsedRealtime()
        val elapsedSec = ((now - startTimeMs) / 1000.0).coerceAtLeast(0.001)

        // Se ainda está calculando nos primeiros 15s ou < 5%, mantém calculando
        if (elapsedSec < 15.0 || lastProgressFraction < 0.05) {
            cachedEstimatedRemainingSec = FormatUtils.ESTIMATE_CALCULATING
            return cachedEstimatedRemainingSec
        }

        val dtTick = ((now - lastCountdownTickMs) / 1000L)
        if (dtTick >= 1L && cachedEstimatedRemainingSec > 1L) {
            cachedEstimatedRemainingSec = (cachedEstimatedRemainingSec - dtTick).coerceAtLeast(1L)
            lastCountdownTickMs = now
        }

        logValidationIfNeeded(now, elapsedSec, lastProgressFraction, lastBytesProcessed, lastItemsProcessed)

        return cachedEstimatedRemainingSec
    }

    private fun logValidationIfNeeded(now: Long, elapsedSec: Double, p: Double, bytes: Long, items: Int) {
        if (now - lastValidationLogMs >= 5000L) {
            lastValidationLogMs = now
            val etaStr = FormatUtils.formatRemainingTimeOnly(cachedEstimatedRemainingSec)
            Log.d(
                "ETA",
                "tempo decorrido: %ds | p: %.3f (bytes: %d/%d, lotes: %d/%d) | ETA: ~%ds (%s)"
                    .format(Locale.US, elapsedSec.toInt(), p, bytes, totalBytes, items, totalItems, cachedEstimatedRemainingSec, etaStr)
            )
        }
    }

    @Synchronized
    fun logCompletion() {
        val totalRealElapsedSec = ((SystemClock.elapsedRealtime() - startTimeMs) / 1000L).coerceAtLeast(1L)
        val initialEtaStr = if (initialPredictedEtaSec > 0L) "${initialPredictedEtaSec}s (${FormatUtils.formatRemainingTimeOnly(initialPredictedEtaSec)})" else "N/A"
        val totalRealStr = "${totalRealElapsedSec}s (${FormatUtils.formatRemainingTimeOnly(totalRealElapsedSec)})"
        Log.d("ETA", "Conclusão do Backup: ETA previsto no início ($initialEtaStr) vs tempo real total ($totalRealStr)")
    }

    @Synchronized
    fun getEffectiveSpeedBytes(): Long {
        return rollingSpeedBytesSec
    }
}
