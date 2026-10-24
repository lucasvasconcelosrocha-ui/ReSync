package com.resync.app.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes.toDouble() / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        val tb = gb / 1024.0
        return when {
            tb >= 1.0 -> String.format(Locale("pt", "BR"), "%.2f TB", tb)
            gb >= 1.0 -> String.format(Locale("pt", "BR"), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale("pt", "BR"), "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale("pt", "BR"), "%.1f KB", kb)
            else -> String.format(Locale("pt", "BR"), "%,d B", bytes)
        }
    }

    fun formatEta(remainingSeconds: Long): String {
        if (remainingSeconds <= 0L) return "0s"
        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        return when {
            h > 0 -> String.format(Locale("pt", "BR"), "%dh %02dm", h, m)
            m > 0 -> String.format(Locale("pt", "BR"), "%dm %02ds", m, s)
            else -> String.format(Locale("pt", "BR"), "%ds", s)
        }
    }

    fun formatItemCount(count: Int): String {
        return if (count == 1) "1 item" else "$count itens"
    }

    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0L) return "Nenhum"
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
        return sdf.format(java.util.Date(timestamp))
    }

    fun getDeviceIdentifier(context: Context): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val model = Build.MODEL
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.take(8)?.uppercase() ?: "UNKNOWN"
        return "$manufacturer $model ($androidId)".trim()
    }

    fun formatDurationPtBr(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d h %02d min %02d s", h, m, sec)
            m > 0 -> String.format(Locale.US, "%d min %02d s", m, sec)
            else -> "${sec} s"
        }
    }

    const val ESTIMATE_CALCULATING = -1L
    const val ESTIMATE_ZERO_SPEED = -2L
    const val ESTIMATE_FINALIZING = -3L

    /**
     * Formato curto de estimativa de tempo restante:
     * - "Calculando…" nos primeiros segundos ou quando remainingSeconds <= 0 / -1L
     * - "—" quando velocidade ~0 (-2L)
     * - "Finalizando…" nas etapas finais (-3L)
     * - "~45 s"
     * - "~3 min"
     * - "~1 h 10 min"
     */
    fun formatRemainingTimeOnly(remainingSeconds: Long): String {
        return when (remainingSeconds) {
            ESTIMATE_CALCULATING -> "Calculando…"
            ESTIMATE_ZERO_SPEED -> "—"
            ESTIMATE_FINALIZING -> "Finalizando…"
            else -> {
                if (remainingSeconds <= 0L) return "Calculando…"
                val h = remainingSeconds / 3600
                val m = (remainingSeconds % 3600) / 60
                val s = remainingSeconds % 60
                when {
                    h > 0 -> if (m > 0) "~$h h $m min" else "~$h h"
                    m > 0 -> "~$m min"
                    else -> "~${s.coerceAtLeast(1)} s"
                }
            }
        }
    }

    /**
     * Formato curto com prefixo Resta:
     * - "Calculando…" nos primeiros segundos ou quando remainingSeconds <= 0
     * - "—"
     * - "Finalizando…"
     * - "Resta ~45 s"
     * - "Resta ~3 min"
     * - "Resta ~1 h 10 min"
     */
    fun formatShortEta(remainingSeconds: Long): String {
        val time = formatRemainingTimeOnly(remainingSeconds)
        return when (time) {
            "Calculando…", "—", "Finalizando…" -> time
            else -> "Resta $time"
        }
    }
}

fun formatBytes(bytes: Long): String = FormatUtils.formatBytes(bytes)
fun formatEta(remainingSeconds: Long): String = FormatUtils.formatEta(remainingSeconds)
fun formatShortEta(remainingSeconds: Long): String = FormatUtils.formatShortEta(remainingSeconds)
fun formatRemainingTimeOnly(remainingSeconds: Long): String = FormatUtils.formatRemainingTimeOnly(remainingSeconds)
fun formatDurationPtBr(totalSeconds: Long): String = FormatUtils.formatDurationPtBr(totalSeconds)

