package com.resync.app.cloud.gdrive

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.resync.app.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native, automatic thermal governor dedicated exclusively to cloud operations.
 * Never modifies local backup or compression settings.
 */
object CloudThermalGovernor {

    private const val TAG = "CloudThermalGovernor"

    private val _thermalStatus = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val thermalStatus: StateFlow<Int> = _thermalStatus.asStateFlow()

    private val cloudParallelWorkers = AtomicInteger(3)
    private var isListenerRegistered = false

    fun initialize(context: Context) {
        if (isListenerRegistered) return
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                pm.addThermalStatusListener(context.mainExecutor) { status ->
                    _thermalStatus.value = status
                    when (status) {
                        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> {
                            cloudParallelWorkers.set(3)
                            AppLogger.i(TAG, "Status térmico normalizado (${status}). Nuvem em velocidade máxima.")
                        }
                        PowerManager.THERMAL_STATUS_MODERATE -> {
                            cloudParallelWorkers.set(1)
                            AppLogger.w(TAG, "Status térmico MODERADO detectado. Reduzindo paralelismo e tamanho de chunks da nuvem.")
                        }
                        PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> {
                            cloudParallelWorkers.set(1)
                            AppLogger.w(TAG, "Status térmico SEVERO detectado (${status}). Nuvem entrando em resfriamento temporário.")
                        }
                    }
                }
                isListenerRegistered = true
                _thermalStatus.value = pm.currentThermalStatus
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Falha ao registrar ThermalStatusListener: ${e.message}")
            }
        }
    }

    fun getRecommendedChunkSize(baseChunkBytes: Int): Int {
        return when (_thermalStatus.value) {
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> 4 * 1024 * 1024 // 4 MiB
            PowerManager.THERMAL_STATUS_MODERATE -> 8 * 1024 * 1024 // 8 MiB
            else -> baseChunkBytes // 16 to 32 MiB
        }
    }

    fun getParallelDownloadConnections(maxConnections: Int): Int {
        return when (_thermalStatus.value) {
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> 1
            PowerManager.THERMAL_STATUS_MODERATE -> 2
            else -> maxConnections
        }
    }

    /**
     * Pauses coroutine briefly if the device is running critically hot.
     */
    suspend fun throttleIfSevere() {
        val status = _thermalStatus.value
        if (status == PowerManager.THERMAL_STATUS_SEVERE) {
            delay(3000L)
        } else if (status >= PowerManager.THERMAL_STATUS_CRITICAL) {
            delay(6000L)
        }
    }

    fun isThermalRestricted(): Boolean {
        return _thermalStatus.value >= PowerManager.THERMAL_STATUS_MODERATE
    }
}
