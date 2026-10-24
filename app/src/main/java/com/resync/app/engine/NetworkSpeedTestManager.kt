package com.resync.app.engine

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

enum class SpeedTestStage(val displayName: String) {
    IDLE("Aguardando"),
    PING("Medindo Latência (Ping)..."),
    DOWNLOAD("Medindo Download..."),
    UPLOAD("Medindo Upload..."),
    COMPLETED("Teste Concluído"),
    ERROR("Falha no Teste"),
    CANCELLED("Cancelado")
}

data class SpeedTestResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val networkType: String,
    val pingMs: Long,
    val jitterMs: Long,
    val downloadSpeedMbps: Double,
    val uploadSpeedMbps: Double,
    val formattedDate: String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
)

data class SpeedTestState(
    val stage: SpeedTestStage = SpeedTestStage.IDLE,
    val pingMs: Long = 0L,
    val jitterMs: Long = 0L,
    val currentDownloadMbps: Double = 0.0,
    val currentUploadMbps: Double = 0.0,
    val finalDownloadMbps: Double = 0.0,
    val finalUploadMbps: Double = 0.0,
    val progressPercent: Float = 0f,
    val errorMessage: String? = null,
    val isRunning: Boolean = false
)

class NetworkSpeedTestManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("swiftvault_speed_test", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _testState = MutableStateFlow(SpeedTestState())
    val testState: StateFlow<SpeedTestState> = _testState.asStateFlow()

    private var activeJob: Job? = null
    private val isCancelled = AtomicBoolean(false)

    companion object {
        @Volatile
        private var INSTANCE: NetworkSpeedTestManager? = null

        fun getInstance(context: Context): NetworkSpeedTestManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkSpeedTestManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun startSpeedTest(networkTypeName: String) {
        if (_testState.value.isRunning) return

        isCancelled.set(false)
        activeJob?.cancel()

        activeJob = CoroutineScope(Dispatchers.IO).launch {
            _testState.value = SpeedTestState(stage = SpeedTestStage.PING, isRunning = true, progressPercent = 0.05f)

            try {
                // 1. PING & JITTER TEST (4 probes)
                val pingSamples = mutableListOf<Long>()
                val pingHosts = listOf(
                    Pair("1.1.1.1", 443),
                    Pair("8.8.8.8", 53),
                    Pair("1.0.0.1", 443),
                    Pair("8.8.4.4", 53)
                )

                for ((host, port) in pingHosts) {
                    if (isCancelled.get()) break
                    val start = System.currentTimeMillis()
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), 1200)
                            val duration = System.currentTimeMillis() - start
                            pingSamples.add(duration)
                        }
                    } catch (_: Exception) {
                        // Fallback HTTP probe
                        val httpStart = System.currentTimeMillis()
                        try {
                            val conn = (URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection).apply {
                                connectTimeout = 1500
                                readTimeout = 1500
                                requestMethod = "HEAD"
                            }
                            conn.responseCode
                            conn.disconnect()
                            pingSamples.add(System.currentTimeMillis() - httpStart)
                        } catch (_: Exception) {}
                    }
                    delay(80)
                }

                if (isCancelled.get()) {
                    _testState.value = SpeedTestState(stage = SpeedTestStage.CANCELLED, isRunning = false)
                    return@launch
                }

                val avgPing = if (pingSamples.isNotEmpty()) pingSamples.average().toLong().coerceAtLeast(1L) else 25L
                val jitter = if (pingSamples.size > 1) {
                    val diffs = pingSamples.zipWithNext { a, b -> abs(a - b) }
                    diffs.average().toLong()
                } else 2L

                _testState.value = _testState.value.copy(
                    stage = SpeedTestStage.DOWNLOAD,
                    pingMs = avgPing,
                    jitterMs = jitter,
                    progressPercent = 0.20f
                )

                // 2. DOWNLOAD TEST (~6 to 8 seconds multi-threaded)
                val downloadUrls = listOf(
                    "https://speed.cloudflare.com/__down?bytes=15000000",
                    "https://speed.cloudflare.com/__down?bytes=15000000"
                )

                val bytesDownloaded = AtomicLong(0L)
                val downloadStartTime = System.currentTimeMillis()
                val downloadDurationMs = 7000L // 7 seconds duration
                val downloadStreamsJob = Job()

                val downloadScope = CoroutineScope(Dispatchers.IO + downloadStreamsJob)

                // Progress reporter loop
                val downloadReporter = launch {
                    while (isActive && !isCancelled.get()) {
                        val elapsed = System.currentTimeMillis() - downloadStartTime
                        if (elapsed > 0) {
                            val bytes = bytesDownloaded.get()
                            val mbps = (bytes * 8.0) / (elapsed * 1000.0) // bits / ms = kbps / 1000 = Mbps
                            val prog = 0.20f + ((elapsed.toFloat() / downloadDurationMs.toFloat()).coerceIn(0f, 1f) * 0.40f) // 20% to 60%
                            _testState.value = _testState.value.copy(
                                currentDownloadMbps = String.format(Locale.US, "%.1f", mbps).toDoubleOrNull() ?: mbps,
                                progressPercent = prog
                            )
                        }
                        delay(180)
                    }
                }

                // Launch workers
                downloadUrls.forEach { urlStr ->
                    downloadScope.launch {
                        try {
                            val url = URL(urlStr)
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 4000
                                readTimeout = 4000
                                requestMethod = "GET"
                            }
                            conn.inputStream.use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (System.currentTimeMillis() - downloadStartTime < downloadDurationMs && !isCancelled.get()) {
                                    read = input.read(buffer)
                                    if (read == -1) break
                                    bytesDownloaded.addAndGet(read.toLong())
                                }
                            }
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }
                }

                // Wait for duration or completion
                while (System.currentTimeMillis() - downloadStartTime < downloadDurationMs && !isCancelled.get()) {
                    delay(100)
                }

                downloadStreamsJob.cancel()
                downloadReporter.cancel()

                if (isCancelled.get()) {
                    _testState.value = SpeedTestState(stage = SpeedTestStage.CANCELLED, isRunning = false)
                    return@launch
                }

                val totalDlElapsed = (System.currentTimeMillis() - downloadStartTime).coerceAtLeast(1000L)
                val finalDlMbps = (bytesDownloaded.get() * 8.0) / (totalDlElapsed * 1000.0)
                val roundedDlMbps = (String.format(Locale.US, "%.1f", finalDlMbps).toDoubleOrNull() ?: finalDlMbps).coerceAtLeast(0.1)

                _testState.value = _testState.value.copy(
                    stage = SpeedTestStage.UPLOAD,
                    finalDownloadMbps = roundedDlMbps,
                    currentDownloadMbps = roundedDlMbps,
                    progressPercent = 0.60f
                )

                // 3. UPLOAD TEST (~5 seconds)
                val uploadStartTime = System.currentTimeMillis()
                val uploadDurationMs = 5000L
                val bytesUploaded = AtomicLong(0L)
                val uploadStreamsJob = Job()
                val uploadScope = CoroutineScope(Dispatchers.IO + uploadStreamsJob)

                val uploadReporter = launch {
                    while (isActive && !isCancelled.get()) {
                        val elapsed = System.currentTimeMillis() - uploadStartTime
                        if (elapsed > 0) {
                            val bytes = bytesUploaded.get()
                            val mbps = (bytes * 8.0) / (elapsed * 1000.0)
                            val prog = 0.60f + ((elapsed.toFloat() / uploadDurationMs.toFloat()).coerceIn(0f, 1f) * 0.38f) // 60% to 98%
                            _testState.value = _testState.value.copy(
                                currentUploadMbps = String.format(Locale.US, "%.1f", mbps).toDoubleOrNull() ?: mbps,
                                progressPercent = prog
                            )
                        }
                        delay(180)
                    }
                }

                uploadScope.launch {
                    val dummyPayload = ByteArray(32 * 1024) { 0x41 }
                    try {
                        val conn = (URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                            requestMethod = "POST"
                            doOutput = true
                            setChunkedStreamingMode(32 * 1024)
                        }
                        conn.outputStream.use { out ->
                            while (System.currentTimeMillis() - uploadStartTime < uploadDurationMs && !isCancelled.get()) {
                                out.write(dummyPayload)
                                bytesUploaded.addAndGet(dummyPayload.size.toLong())
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {}
                }

                while (System.currentTimeMillis() - uploadStartTime < uploadDurationMs && !isCancelled.get()) {
                    delay(100)
                }

                uploadStreamsJob.cancel()
                uploadReporter.cancel()

                if (isCancelled.get()) {
                    _testState.value = SpeedTestState(stage = SpeedTestStage.CANCELLED, isRunning = false)
                    return@launch
                }

                val totalUlElapsed = (System.currentTimeMillis() - uploadStartTime).coerceAtLeast(1000L)
                val finalUlMbps = (bytesUploaded.get() * 8.0) / (totalUlElapsed * 1000.0)
                val roundedUlMbps = (String.format(Locale.US, "%.1f", finalUlMbps).toDoubleOrNull() ?: finalUlMbps).coerceAtLeast(0.1)

                // 4. FINALIZE & SAVE
                val completedState = SpeedTestState(
                    stage = SpeedTestStage.COMPLETED,
                    pingMs = avgPing,
                    jitterMs = jitter,
                    finalDownloadMbps = roundedDlMbps,
                    finalUploadMbps = roundedUlMbps,
                    currentDownloadMbps = roundedDlMbps,
                    currentUploadMbps = roundedUlMbps,
                    progressPercent = 1.0f,
                    isRunning = false
                )
                _testState.value = completedState

                val result = SpeedTestResult(
                    networkType = networkTypeName,
                    pingMs = avgPing,
                    jitterMs = jitter,
                    downloadSpeedMbps = roundedDlMbps,
                    uploadSpeedMbps = roundedUlMbps
                )
                saveResult(result)

            } catch (e: Exception) {
                if (isCancelled.get()) {
                    _testState.value = SpeedTestState(stage = SpeedTestStage.CANCELLED, isRunning = false)
                } else {
                    _testState.value = SpeedTestState(
                        stage = SpeedTestStage.ERROR,
                        errorMessage = e.message ?: "Erro ao testar velocidade",
                        isRunning = false
                    )
                }
            }
        }
    }

    fun cancelSpeedTest() {
        isCancelled.set(true)
        activeJob?.cancel()
        _testState.value = SpeedTestState(stage = SpeedTestStage.CANCELLED, isRunning = false)
    }

    fun resetState() {
        _testState.value = SpeedTestState()
    }

    fun getHistory(): List<SpeedTestResult> {
        val json = prefs.getString("speed_history", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SpeedTestResult>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveResult(result: SpeedTestResult) {
        val history = getHistory().toMutableList()
        history.add(0, result)
        val trimmed = history.take(15) // Keep last 15 tests
        prefs.edit().putString("speed_history", gson.toJson(trimmed)).apply()
    }
}

