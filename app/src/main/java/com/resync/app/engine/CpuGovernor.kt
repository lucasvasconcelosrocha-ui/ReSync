package com.resync.app.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.resync.app.BuildConfig
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.Locale

/**
 * CpuGovernor monitors app process CPU consumption, battery temperature, and native Android Thermal Status.
 *
 * 1. CPU Throttling:
 *    Every ~1 second, measures Process.getElapsedCpuTime() against wall-clock time.
 *    If the process CPU usage ratio exceeds maxCpuUsageFraction (default 18%),
 *    it introduces a proportional cooperative sleep/delay to keep average CPU within limits.
 *
 * 2. Thermal Protection:
 *    - Uses PowerManager.addThermalStatusListener on Android 10+ (API 29+).
 *    - Falls back to battery temperature via ACTION_BATTERY_CHANGED.
 *    - If status is MODERATE or higher (or battery > 40.0 °C):
 *      reduces to 1 thread and inserts short cooling pauses (50-100ms) between items.
 *    - If status is SEVERE or higher (or battery > 43.0 °C):
 *      pauses backup automatically ("Pausado para esfriar"), notifying via callback.
 *      Resumes automatically only when temp drops below 38.0 °C (or status returns to <= MODERATE).
 *
 * 3. CPU profiling & Telemetry:
 *    - In debug mode: logs every 10 seconds.
 *    - In release mode: spaced to once per 60 seconds (or on stage changes).
 */
class CpuGovernor(
    private val context: Context,
    private val maxCpuUsageFraction: Double = 0.18 // 18% CPU limit
) {

    companion object {
        private const val TAG = "CpuGovernor"

        // Thermal status observable globally for UI
        private val _currentDeviceTemperature = MutableStateFlow(32.0f)
        val currentDeviceTemperature: StateFlow<Float> = _currentDeviceTemperature.asStateFlow()

        private val _isThermalThrottled = MutableStateFlow(false)
        val isThermalThrottled: StateFlow<Boolean> = _isThermalThrottled.asStateFlow()

        private val _currentCpuUsagePct = MutableStateFlow(0)
        val currentCpuUsagePct: StateFlow<Int> = _currentCpuUsagePct.asStateFlow()

        private val _currentThermalStatusInt = MutableStateFlow(0)
        val currentThermalStatusInt: StateFlow<Int> = _currentThermalStatusInt.asStateFlow()

        private val _activeThreadsCount = MutableStateFlow(1)
        val activeThreadsCount: StateFlow<Int> = _activeThreadsCount.asStateFlow()

        /**
         * Formats telemetry log message using Kotlin string template and Locale.ROOT.
         * Safe against UnknownFormatConversionException caused by unescaped '%' characters.
         */
        fun formatTelemetryLog(
            avgPct: Int,
            nowCpuMs: Long,
            speedMBps: Double,
            currentTemp: Float,
            stageName: String
        ): String {
            return try {
                val speedFormatted = "%.1f".format(Locale.ROOT, speedMBps)
                val tempFormatted = "%.1f".format(Locale.ROOT, currentTemp)
                "[Telemetria 10s] CPU: $avgPct% (${nowCpuMs}ms) | Vel: $speedFormatted MB/s | Temp: $tempFormatted °C | Etapa: '$stageName'"
            } catch (_: Throwable) {
                "[Telemetria 10s] CPU: $avgPct% (${nowCpuMs}ms) | Temp: $currentTemp C | Etapa: '$stageName'"
            }
        }
    }

    private val dbHelper by lazy { AppDatabaseHelper.getInstance(context) }
    private val thermalPrefs by lazy { com.resync.app.data.preferences.ThermalPreferences(context) }
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    // Thermal listener reference
    private var thermalListener: Any? = null
    @Volatile
    private var currentThermalStatus: Int = 0 // 0 = THERMAL_STATUS_NONE

    init {
        registerThermalStatusListener()
    }

    private fun registerThermalStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    currentThermalStatus = status
                    val isHot = status >= PowerManager.THERMAL_STATUS_MODERATE
                    _isThermalThrottled.value = isHot
                }
                powerManager?.addThermalStatusListener(context.mainExecutor, listener)
                thermalListener = listener
                currentThermalStatus = powerManager?.currentThermalStatus ?: 0
            } catch (t: Throwable) {
                Log.w(TAG, "Não foi possível registrar OnThermalStatusChangedListener: ${t.message}")
            }
        }
    }

    fun unregisterThermalStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                powerManager?.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
            } catch (_: Throwable) {}
            thermalListener = null
        }
    }

    // Profiling states
    private var stageName: String = "Init"
    private var stageStartWallMs: Long = SystemClock.elapsedRealtime()
    private var stageStartCpuMs: Long = Process.getElapsedCpuTime()

    // Governor throttling tracking
    private var lastCpuCheckWallMs: Long = SystemClock.elapsedRealtime()
    private var lastCpuTimeMs: Long = Process.getElapsedCpuTime()

    // Thermal state
    @Volatile
    var isThermalPaused: Boolean = false
        private set

    private var peakObservedTemp: Float = 0f

    /**
     * Reads current battery temperature in Celsius (°C).
     */
    fun getCurrentBatteryTemperature(): Float {
        val temp = try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bIntent = context.registerReceiver(null, ifilter)
            val tempTenths = bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 250
            tempTenths / 10.0f
        } catch (_: Exception) {
            25.0f
        }
        _currentDeviceTemperature.value = temp
        return temp
    }

    /**
     * Returns true if thermal condition requires reducing concurrency to 1 thread
     * and adding pacing pauses (status >= MODERATE or temp >= 40.0 °C).
     */
    fun isModeratelyHot(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            return true
        }
        val temp = getCurrentBatteryTemperature()
        return temp >= thermalPrefs.warnTempThreshold
    }

    /**
     * Returns true if thermal condition requires pausing the backup completely
     * (status >= SEVERE or temp >= 43.0 °C).
     */
    fun isSeverelyHot(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return true
        }
        val temp = getCurrentBatteryTemperature()
        return temp >= thermalPrefs.highTempThreshold
    }

    /**
     * Allows manual resume button to bypass thermal pause for 10 minutes.
     */
    fun overrideThermalProtection() {
        thermalPrefs.setOverrideForMinutes(10)
        isThermalPaused = false
    }

    /**
     * Calculates optimal concurrent network connections dynamically:
     * Normal: 5 connections
     * LIGHT or temp >= 39 °C: 3 connections
     * MODERATE or temp >= 42 °C: 2 connections
     * SEVERE or temp >= 45 °C: 1 connection
     * CRITICAL or temp >= 48 °C: 0 (paused for cooling)
     */
    fun getOptimalConnectionCount(): Int {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: currentThermalStatus
        } else currentThermalStatus
        val temp = getCurrentBatteryTemperature()

        return when {
            status >= PowerManager.THERMAL_STATUS_SEVERE || temp >= 45.0f -> 2
            status >= PowerManager.THERMAL_STATUS_MODERATE || temp >= 42.0f -> 4
            else -> 8
        }
    }

    /**
     * Micro-pauses between chunks to let hardware dissipate heat without aborting the backup:
     * - Normal: 0ms
     * - Moderado: 50ms
     * - Severo/Crítico: 250ms (modo sobrevivência térmica)
     */
    fun getThermalCooldownDelayMs(): Long {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: currentThermalStatus
        } else currentThermalStatus
        val temp = getCurrentBatteryTemperature()

        return when {
            status >= PowerManager.THERMAL_STATUS_SEVERE || temp >= 45.0f -> 250L
            status >= PowerManager.THERMAL_STATUS_MODERATE || temp >= 42.0f -> 50L
            else -> 0L
        }
    }

    /**
     * Calculates optimal compression threads dynamically:
     * - Cores - 2 as base cap (never starve OS/system UI, min 2)
     * - LIGHT or temp >= 39 °C: 2 threads
     * - MODERATE or temp >= 42 °C: 1 thread
     * - SEVERE or temp >= 45 °C: 1 thread (STORED / no compression)
     * - Measures process CPU usage and automatically decrements threads if exceeding threshold
     */
    fun getOptimalThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        val baseMax = (available - 2).coerceIn(2, 8)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: currentThermalStatus
        } else currentThermalStatus
        val temp = getCurrentBatteryTemperature()

        var threads = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE || temp >= 45.0f -> 1
            status >= PowerManager.THERMAL_STATUS_MODERATE || temp >= 42.0f -> 1
            status >= PowerManager.THERMAL_STATUS_LIGHT || temp >= 39.0f -> 2
            else -> baseMax
        }

        // Auto-regulate against excessive process CPU usage
        if (_currentCpuUsagePct.value > 80 && threads > 1) {
            threads = (threads - 1).coerceAtLeast(1)
        }
        _activeThreadsCount.value = threads
        return threads
    }

    /**
     * Discrete thermal mode label for UI: "Máximo", "Equilibrado" or "Frio".
     */
    fun getThermalMode(): String {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: currentThermalStatus
        } else currentThermalStatus
        val temp = getCurrentBatteryTemperature()
        return when {
            status >= PowerManager.THERMAL_STATUS_MODERATE || temp >= 42.0f -> "Frio"
            status >= PowerManager.THERMAL_STATUS_LIGHT || temp >= 39.0f -> "Equilibrado"
            else -> "Máximo"
        }
    }

    /**
     * Under SEVERE thermal condition, bypass compression entirely (STORED) to save CPU.
     */
    fun isNoCompressionPreferred(): Boolean {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: currentThermalStatus
        } else currentThermalStatus
        val temp = getCurrentBatteryTemperature()
        return status >= PowerManager.THERMAL_STATUS_SEVERE || temp >= 45.0f
    }

    /**
     * Cooperative checkpoint: measures process CPU without artificial delays.
     * Only pauses execution if status reaches CRITICAL (or battery >= 48 °C),
     * resuming automatically when temperature cools down below 39 °C.
     */
    suspend fun throttleIfNeeded(
        onThermalWait: (suspend (tempCelsius: Float) -> Unit)? = null,
        onThermalResume: (suspend () -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ) {
        if (isCancelled()) return

        val currentTemp = getCurrentBatteryTemperature()
        _currentDeviceTemperature.value = currentTemp
        if (currentTemp > peakObservedTemp) {
            peakObservedTemp = currentTemp
        }

        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: currentThermalStatus
        } else currentThermalStatus

        // Monitor process CPU usage without artificial delays
        val nowWallMs = SystemClock.elapsedRealtime()
        val wallDeltaMs = nowWallMs - lastCpuCheckWallMs
        if (wallDeltaMs >= 1000L) {
            val nowCpuMs = Process.getElapsedCpuTime()
            val cpuDeltaMs = (nowCpuMs - lastCpuTimeMs).coerceAtLeast(0L)
            val pct = if (wallDeltaMs > 0) ((cpuDeltaMs.toDouble() / wallDeltaMs.toDouble()) * 100).toInt() else 0
            _currentCpuUsagePct.value = pct
            lastCpuCheckWallMs = nowWallMs
            lastCpuTimeMs = nowCpuMs
        }

        // Only pause in CRITICAL status or extreme overheating (temp >= 48 °C)
        val isCritical = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && status >= PowerManager.THERMAL_STATUS_CRITICAL) || currentTemp >= 48.0f
        if (isCritical) {
            isThermalPaused = true
            val warnMsg = "Proteção Térmica: Temperatura em %.1f °C (Status CRITICAL). Pausando temporariamente para resfriamento.".format(Locale.ROOT, currentTemp)
            Log.w(TAG, warnMsg)
            try { dbHelper.insertLog(LogLevel.WARN, TAG, warnMsg) } catch (_: Throwable) {}
            onThermalWait?.invoke(currentTemp)

            while (isCritical && !isCancelled()) {
                delay(2000L)
                val checkTemp = getCurrentBatteryTemperature()
                val checkStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager?.currentThermalStatus ?: 0 else 0
                if (checkTemp < 39.0f && checkStatus < PowerManager.THERMAL_STATUS_MODERATE) {
                    break
                }
            }
            isThermalPaused = false
            onThermalResume?.invoke()
        } else {
            isThermalPaused = false
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Start profiling a new stage and logs the summary of the previous stage.
     */
    fun startStage(newStageName: String) {
        val nowWallMs = SystemClock.elapsedRealtime()
        val nowCpuMs = Process.getElapsedCpuTime()

        val prevWallMs = nowWallMs - stageStartWallMs
        val prevCpuMs = nowCpuMs - stageStartCpuMs
        val cpuUsagePct = if (prevWallMs > 0) ((prevCpuMs.toDouble() / prevWallMs.toDouble()) * 100).toInt() else 0

        val logMsg = "Tempo de CPU da etapa '$stageName': CPU: ${prevCpuMs}ms | Duração real: ${prevWallMs}ms | CPU médio: $cpuUsagePct%"
        Log.i(TAG, logMsg)
        scope.launch {
            try {
                dbHelper.insertLog(LogLevel.INFO, TAG, logMsg)
            } catch (_: Throwable) {}
        }

        stageName = newStageName
        stageStartWallMs = nowWallMs
        stageStartCpuMs = nowCpuMs
        lastCpuCheckWallMs = nowWallMs
        lastCpuTimeMs = nowCpuMs
    }

    private var periodicJob: kotlinx.coroutines.Job? = null

    /**
     * Starts logging process CPU consumption, transfer speed and battery temperature.
     * In release mode (!BuildConfig.DEBUG), intervals are spaced to once every 60 seconds.
     */
    fun startPeriodicCpuLogging(
        intervalMs: Long = 10000L,
        currentSpeedBytesSec: (() -> Long)? = null
    ) {
        periodicJob?.cancel()
        val effectiveInterval = if (BuildConfig.DEBUG) intervalMs else 60000L

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Exceção capturada no CoroutineExceptionHandler de telemetria de CPU: ${throwable.message}", throwable)
        }

        periodicJob = scope.launch(exceptionHandler) {
            try {
                supervisorScope {
                    var lastPeriodicWallMs = SystemClock.elapsedRealtime()
                    var lastPeriodicCpuMs = Process.getElapsedCpuTime()
                    while (isActive) {
                        try {
                            delay(effectiveInterval)
                            val nowWall = SystemClock.elapsedRealtime()
                            val nowCpu = Process.getElapsedCpuTime()
                            val wallDelta = nowWall - lastPeriodicWallMs
                            val cpuDelta = nowCpu - lastPeriodicCpuMs
                            val avgPct = if (wallDelta > 0) ((cpuDelta.toDouble() / wallDelta.toDouble()) * 100).toInt() else 0

                            val currentTemp = getCurrentBatteryTemperature()
                            val speedBytes = currentSpeedBytesSec?.invoke() ?: 0L
                            val speedMBps = speedBytes.toDouble() / (1024.0 * 1024.0)

                            _currentCpuUsagePct.value = avgPct
                            _currentDeviceTemperature.value = currentTemp
                            _currentThermalStatusInt.value = currentThermalStatus
                            _activeThreadsCount.value = Thread.activeCount()

                            val msg = formatTelemetryLog(
                                avgPct = avgPct,
                                nowCpuMs = nowCpu,
                                speedMBps = speedMBps,
                                currentTemp = currentTemp,
                                stageName = stageName
                            )
                            Log.i(TAG, msg)
                            try {
                                dbHelper.insertLog(LogLevel.INFO, TAG, msg)
                            } catch (_: Throwable) {}
                            lastPeriodicWallMs = nowWall
                            lastPeriodicCpuMs = nowCpu
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) throw t
                            Log.e(TAG, "Erro não fatal no laço de telemetria de CPU: ${t.message}", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Erro crítico amortecido em startPeriodicCpuLogging: ${t.message}", t)
            }
        }
    }

    /**
     * Stops periodic CPU logging and unregisters thermal listeners.
     */
    fun stopPeriodicCpuLogging() {
        periodicJob?.cancel()
        periodicJob = null
        unregisterThermalStatusListener()
    }

    /**
     * Concludes final profiling and records total CPU metrics.
     */
    fun endProfiling() {
        stopPeriodicCpuLogging()
        startStage("Finalizado")
    }
}

