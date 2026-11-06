package com.resync.app.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.Locale

object DeviceThermalHelper {

    @Volatile
    private var lastTempC: Float = 0.0f
    @Volatile
    private var lastReadTimeMs: Long = 0L
    private const val CACHE_EXPIRY_MS = 3000L

    @Volatile
    private var cachedValidTempFiles: List<File>? = null
    @Volatile
    private var lastCpuTempC: Float = 0.0f
    @Volatile
    private var lastCpuReadTimeMs: Long = 0L
    private const val CPU_THROTTLE_MS = 3000L

    /**
     * Retorna a maior temperatura entre bateria e CPU.
     * Utiliza cache em memória de 3 segundos para evitar qualquer impacto na CPU.
     */
    fun getHighestTemperature(context: Context): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReadTimeMs < CACHE_EXPIRY_MS && lastTempC > 0f) {
            return lastTempC
        }

        val batteryTemp = getBatteryTemperature(context)
        val cpuTemp = getCpuTemperature()
        val highest = if (batteryTemp > 0f && cpuTemp > 0f) {
            maxOf(batteryTemp, cpuTemp)
        } else if (batteryTemp > 0f) {
            batteryTemp
        } else {
            cpuTemp
        }

        lastTempC = highest
        lastReadTimeMs = now
        return highest
    }

    /**
     * Obtém a temperatura da bateria via sticky intent do BatteryManager.
     * Custo zero de processamento e totalmente suportado pelo Android.
     */
    fun getBatteryTemperature(context: Context): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            if (raw > 0) raw / 10.0f else 0.0f
        } catch (_: Throwable) {
            0.0f
        }
    }

    /**
     * Obtém a temperatura da CPU através das zonas térmicas do Linux (/sys/class/thermal/thermal_zoneX/temp).
     * Implementa cache em memória dos arquivos válidos e throttle mínimo de 3000ms.
     * Seleciona a maior leitura válida (entre 15°C e 115°C).
     */
    fun getCpuTemperature(): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCpuReadTimeMs < CPU_THROTTLE_MS && lastCpuTempC > 0f) {
            return lastCpuTempC
        }

        return try {
            var files = cachedValidTempFiles
            if (files == null) {
                val thermalDir = File("/sys/class/thermal")
                if (!thermalDir.exists() || !thermalDir.isDirectory) {
                    cachedValidTempFiles = emptyList()
                    return 0.0f
                }
                val validList = mutableListOf<File>()
                val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: emptyArray()
                for (zone in zones) {
                    val tempFile = File(zone, "temp")
                    if (tempFile.canRead()) {
                        validList.add(tempFile)
                    }
                }
                cachedValidTempFiles = validList
                files = validList
            }

            if (files.isEmpty()) return 0.0f

            var maxCpu = 0.0f
            for (tempFile in files) {
                try {
                    val content = tempFile.readText().trim()
                    val rawVal = content.toLongOrNull() ?: continue
                    val degC = if (rawVal > 1000L) rawVal / 1000.0f else rawVal.toFloat()
                    if (degC in 15.0f..115.0f && degC > maxCpu) {
                        maxCpu = degC
                    }
                } catch (_: Throwable) {}
            }
            lastCpuTempC = maxCpu
            lastCpuReadTimeMs = now
            maxCpu
        } catch (_: Throwable) {
            0.0f
        }
    }

    /**
     * Retorna a temperatura formatada com 1 casa decimal e vírgula, ex.: "38,5°C".
     * Se não for possível obter um valor válido, retorna string vazia.
     */
    fun getFormattedHighestTemp(context: Context): String {
        val temp = getHighestTemperature(context)
        if (temp <= 0f) return ""
        return String.format(Locale.US, "%.1f°C", temp).replace('.', ',')
    }
}
