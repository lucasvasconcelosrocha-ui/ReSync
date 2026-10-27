package com.resync.app.engine

import android.content.Context
import android.content.SharedPreferences
import com.resync.app.cloud.providers.GoogleDriveProvider
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.CompletedItemInfo
import com.resync.app.service.RestoreForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RestoreExecutionState {
    IDLE,
    STARTING,
    DOWNLOADING,
    VERIFYING,
    DECRYPTING,
    RESTORING,
    COMPLETED,
    ERROR,
    CANCELLED
}

data class RestoreSummaryData(
    val title: String,
    val source: String,
    val durationSeconds: Long,
    val totalItems: Int,
    val successCount: Int,
    val failedCount: Int,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val startTimeFormatted: String = "",
    val endTimeFormatted: String = "",
    val activeDurationSeconds: Long = 0L,
    val idleDurationSeconds: Long = 0L,
    val averageSpeedMBps: Double = 0.0,
    val peakSpeedMBps: Double = 0.0,
    val networkUsed: String = "Local",
    val formattedDurationText: String = ""
)

/**
 * Singleton repository acting as the Single Source of Truth for restore operations.
 * Survives Activity destruction, holds live StateFlow for UI and Foreground Service,
 * and maintains completion/error state until manually dismissed by the user.
 */
object RestoreRepository {

    private const val PREFS_NAME = "swiftvault_restore_repository_prefs"
    private const val KEY_STAGE = "key_stage"
    private const val KEY_ITEM = "key_item"
    private const val KEY_PROGRESS = "key_progress"
    private const val KEY_IS_FINISHED = "key_is_finished"
    private const val KEY_ERROR = "key_error"
    private const val KEY_EXEC_STATE = "key_exec_state"
    private const val KEY_LAST_RESTORE_LOG = "key_last_restore_log"

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _restoreProgress = MutableStateFlow<RestoreProgress?>(null)
    val restoreProgress: StateFlow<RestoreProgress?> = _restoreProgress.asStateFlow()

    private val _executionState = MutableStateFlow(RestoreExecutionState.IDLE)
    val executionState: StateFlow<RestoreExecutionState> = _executionState.asStateFlow()

    private val _restoreSummary = MutableStateFlow<RestoreSummaryData?>(null)
    val restoreSummary: StateFlow<RestoreSummaryData?> = _restoreSummary.asStateFlow()

    private val _lastRestoreLog = MutableStateFlow<String?>(null)
    val lastRestoreLog: StateFlow<String?> = _lastRestoreLog.asStateFlow()
    private val restoreLogBuilder = StringBuilder()

    private var activeRestoreJob: Job? = null
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var restoreStartTime = 0L

    private var currentRestoreEngine: RestoreEngine? = null
    private var currentDriveProvider: GoogleDriveProvider? = null
    private val _isRestorePaused = MutableStateFlow(false)
    val isRestorePaused: StateFlow<Boolean> = _isRestorePaused.asStateFlow()

    fun isRestoreActive(): Boolean {
        val state = _executionState.value
        return state != RestoreExecutionState.IDLE && state != RestoreExecutionState.COMPLETED && state != RestoreExecutionState.ERROR && state != RestoreExecutionState.CANCELLED
    }

    fun isRestoreRunning(): Boolean = isRestoreActive()

    // Hold last used parameters to allow retry
    private var lastLocalFile: File? = null
    private var lastRemoteDriveFileId: String? = null
    private var lastAccountEmail: String? = null
    private var lastSelectedTypes: Set<String>? = null
    private var lastPassword: String? = null
    private var lastTempDownloadedFile: File? = null
    private var totalPausedDurationMs = 0L
    private var pauseStartElapsedMono = 0L

    fun appendRestoreLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message\n"
        restoreLogBuilder.append(line)
        _lastRestoreLog.value = restoreLogBuilder.toString()
        prefs?.edit()?.putString(KEY_LAST_RESTORE_LOG, _lastRestoreLog.value)?.apply()
    }

    fun clearRestoreLog() {
        restoreLogBuilder.clear()
        _lastRestoreLog.value = null
        prefs?.edit()?.remove(KEY_LAST_RESTORE_LOG)?.apply()
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        _lastRestoreLog.value = prefs?.getString(KEY_LAST_RESTORE_LOG, null)
        if (!_lastRestoreLog.value.isNullOrBlank()) {
            restoreLogBuilder.setLength(0)
            restoreLogBuilder.append(_lastRestoreLog.value)
        }

        // Restore saved status if present (so conclusion or error survives process restart)
        val savedExecStateName = prefs?.getString(KEY_EXEC_STATE, null)
        val savedStage = prefs?.getString(KEY_STAGE, null)
        val savedItem = prefs?.getString(KEY_ITEM, null)
        val savedProg = prefs?.getFloat(KEY_PROGRESS, 0f) ?: 0f
        val savedFinished = prefs?.getBoolean(KEY_IS_FINISHED, false) ?: false
        val savedError = prefs?.getString(KEY_ERROR, null)

        if (savedExecStateName != null && savedStage != null) {
            val state = runCatching { RestoreExecutionState.valueOf(savedExecStateName) }.getOrDefault(RestoreExecutionState.IDLE)
            _executionState.value = state
            _restoreProgress.value = RestoreProgress(
                stage = savedStage,
                currentItemName = savedItem ?: "",
                progressPercent = savedProg,
                isFinished = savedFinished,
                error = savedError
            )
        }
    }

    private var lastSelectedCallKeys: Set<String>? = null
    private var lastSelectedSmsKeys: Set<String>? = null
    private var lastSelectedContactKeys: Set<String>? = null
    private var lastSelectedWifiKeys: Set<String>? = null
    private var lastSelectedBtKeys: Set<String>? = null

    fun startRestore(
        localFile: File?,
        remoteDriveFileId: String?,
        accountEmail: String?,
        selectedItemTypes: Set<String>,
        encryptionPassword: String? = null,
        selectedCallKeys: Set<String>? = null,
        selectedSmsKeys: Set<String>? = null,
        selectedContactKeys: Set<String>? = null,
        selectedWifiKeys: Set<String>? = null,
        selectedBtKeys: Set<String>? = null
    ) {
        val context = appContext ?: return
        lastLocalFile = localFile
        lastRemoteDriveFileId = remoteDriveFileId
        lastAccountEmail = accountEmail
        lastSelectedTypes = selectedItemTypes
        lastPassword = encryptionPassword
        lastSelectedCallKeys = selectedCallKeys
        lastSelectedSmsKeys = selectedSmsKeys
        lastSelectedContactKeys = selectedContactKeys
        lastSelectedWifiKeys = selectedWifiKeys
        lastSelectedBtKeys = selectedBtKeys

        activeRestoreJob?.cancel()
        restoreStartTime = System.currentTimeMillis()
        totalPausedDurationMs = 0L
        pauseStartElapsedMono = 0L
        val monotonicStart = android.os.SystemClock.elapsedRealtime()
        val wallStartFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        clearRestoreLog()
        appendRestoreLog("Iniciando restauração: ${localFile?.name ?: "backup remoto ($remoteDriveFileId)"}")

        _executionState.value = RestoreExecutionState.STARTING
        _restoreProgress.value = RestoreProgress(
            stage = "Localizando backup...",
            currentItemName = localFile?.name ?: "Arquivo na nuvem",
            progressPercent = 0.05f
        )
        persistState()

        RestoreForegroundService.startService(context, localFile?.name ?: "Restauração de Backup")

        activeRestoreJob = repositoryScope.launch {
            var tempDownloadedFile: File? = null
            try {
                var fileToRestore: File? = localFile

                // If remote file needs downloading
                if (fileToRestore == null || !fileToRestore.exists()) {
                    if (remoteDriveFileId != null && accountEmail != null) {
                        appendRestoreLog("Baixando arquivo do Google Drive ($accountEmail)...")
                        _executionState.value = RestoreExecutionState.DOWNLOADING
                        _restoreProgress.value = RestoreProgress(
                            stage = "Baixando do Google Drive...",
                            currentItemName = "Conectando ao Drive ($accountEmail)...",
                            progressPercent = 0.1f
                        )
                        persistState()

                        val driveProvider = GoogleDriveProvider(context)
                        currentDriveProvider = driveProvider
                        val cacheDir = File(context.cacheDir, "restore_temp").apply { mkdirs() }
                        val targetLocal = File(cacheDir, "cloud_restore_${System.currentTimeMillis()}.resync")
                        tempDownloadedFile = targetLocal
                        lastTempDownloadedFile = targetLocal

                        val downloadResult = driveProvider.downloadManager.downloadBackupFile(
                            fileId = remoteDriveFileId,
                            destinationFile = targetLocal,
                            totalBytesEstimated = 0L,
                            accountEmail = accountEmail
                        ) { progressInfo ->
                            val speedBytesSec = (progressInfo.speedMBps * 1024f * 1024f).toLong().coerceAtLeast(0L)
                            val mbDown = "%.1f MB".format(java.util.Locale.US, progressInfo.bytesDownloaded.toDouble() / (1024 * 1024))
                            val mbTot = if (progressInfo.totalBytes > 0) "%.1f MB".format(java.util.Locale.US, progressInfo.totalBytes.toDouble() / (1024 * 1024)) else "?"
                            val speedStr = if (progressInfo.speedMBps > 0) " • %.1f MB/s".format(java.util.Locale.US, progressInfo.speedMBps) else ""
                            _restoreProgress.value = RestoreProgress(
                                stage = progressInfo.stage,
                                currentItemName = "$mbDown / $mbTot$speedStr",
                                progressPercent = progressInfo.progressPercent,
                                bytesProcessed = progressInfo.bytesDownloaded,
                                totalBytesEstimated = progressInfo.totalBytes,
                                downloadSpeedBytesPerSec = speedBytesSec,
                                estimatedRemainingSeconds = progressInfo.estimatedRemainingSeconds
                            )
                            persistState()
                        }

                        if (!downloadResult.isSuccess || !targetLocal.exists()) {
                            val err = "Falha no download da nuvem: ${downloadResult.exceptionOrNull()?.message ?: "Erro desconhecido"}"
                            appendRestoreLog("ERRO: $err")
                            throw IllegalStateException(err)
                        }
                        fileToRestore = targetLocal
                        appendRestoreLog("Download concluído (${targetLocal.length()} bytes)")
                    } else {
                        val err = "Arquivo de backup local não encontrado e credenciais da nuvem ausentes."
                        appendRestoreLog("ERRO: $err")
                        throw IllegalStateException(err)
                    }
                }

                _executionState.value = RestoreExecutionState.VERIFYING
                _restoreProgress.value = RestoreProgress(
                    stage = "Verificando integridade (SHA-256 / MD5)...",
                    currentItemName = fileToRestore.name,
                    progressPercent = 0.45f
                )
                persistState()
                appendRestoreLog("Verificando container: ${fileToRestore.name} (${fileToRestore.length()} bytes)")

                // Execute Restore Engine
                val engine = RestoreEngine(context)
                currentRestoreEngine = engine
                _executionState.value = RestoreExecutionState.RESTORING

                val manifest = engine.inspectBackup(fileToRestore)
                appendRestoreLog("Manifesto lido: ${manifest.items.size} itens disponíveis")

                val targetItemIds = if (selectedItemTypes.isEmpty()) {
                    manifest.items.map { it.id }.toSet()
                } else {
                    selectedItemTypes
                }
                appendRestoreLog("Iniciando restauração seletiva de ${targetItemIds.size} itens...")

                engine.performSelectiveRestore(
                    containerFile = fileToRestore,
                    manifest = manifest,
                    selectedItemIds = targetItemIds,
                    password = encryptionPassword,
                    selectedCallKeys = selectedCallKeys ?: lastSelectedCallKeys,
                    selectedSmsKeys = selectedSmsKeys ?: lastSelectedSmsKeys,
                    selectedContactKeys = selectedContactKeys ?: lastSelectedContactKeys,
                    selectedWifiKeys = selectedWifiKeys ?: lastSelectedWifiKeys,
                    selectedBtKeys = selectedBtKeys ?: lastSelectedBtKeys
                ).collect { progress ->
                    _restoreProgress.value = progress
                    if (progress.currentItemName.isNotBlank() && progress.stage != "Pausado") {
                        appendRestoreLog("[${progress.stage}] ${progress.currentItemName}")
                    }
                    persistState()
                }

                // Clean up temporary cache downloaded file if exists
                cleanupTempCache()

                if (pauseStartElapsedMono > 0L) {
                    totalPausedDurationMs += (android.os.SystemClock.elapsedRealtime() - pauseStartElapsedMono)
                    pauseStartElapsedMono = 0L
                }

                val monotonicEnd = android.os.SystemClock.elapsedRealtime()
                val monotonicDurationSeconds = ((monotonicEnd - monotonicStart - totalPausedDurationMs) / 1000L).coerceAtLeast(1L)
                val wallEndFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                val formattedDurationText = com.resync.app.util.FormatUtils.formatDurationPtBr(monotonicDurationSeconds)

                val totalItems = _restoreProgress.value?.totalItems ?: 0
                val succ = _restoreProgress.value?.completedItems?.count { it.success } ?: 0
                val failed = totalItems - succ
                val totalBytes = fileToRestore.length()
                val avgSpeedMBps = if (monotonicDurationSeconds > 0) (totalBytes.toDouble() / (1024.0 * 1024.0)) / monotonicDurationSeconds else 0.0

                val summary = RestoreSummaryData(
                    title = fileToRestore.name,
                    source = if (remoteDriveFileId != null) "Google Drive" else "Memória interna",
                    durationSeconds = monotonicDurationSeconds,
                    totalItems = totalItems,
                    successCount = succ,
                    failedCount = if (failed >= 0) failed else 0,
                    totalBytes = totalBytes,
                    startTimeFormatted = wallStartFormat,
                    endTimeFormatted = wallEndFormat,
                    activeDurationSeconds = monotonicDurationSeconds,
                    idleDurationSeconds = 0L,
                    averageSpeedMBps = avgSpeedMBps,
                    peakSpeedMBps = avgSpeedMBps * 1.2,
                    networkUsed = if (remoteDriveFileId != null) com.resync.app.cloud.gdrive.CloudNetworkMonitor.networkStatus.value.networkType else "Local",
                    formattedDurationText = formattedDurationText
                )

                _executionState.value = RestoreExecutionState.COMPLETED
                _restoreSummary.value = summary

                OperationRepository.updateRestoreOperation(
                    stage = "Concluído",
                    currentItemName = "Restauração finalizada (${fileToRestore.name})",
                    progressPercent = 1.0f,
                    bytesProcessed = totalBytes,
                    totalBytesEstimated = totalBytes,
                    isFinished = true,
                    status = OperationStatus.COMPLETED,
                    currentItemIndex = totalItems,
                    totalItems = totalItems,
                    source = summary.source,
                    totalDurationSeconds = monotonicDurationSeconds,
                    formattedDuration = formattedDurationText
                )

                com.resync.app.util.AppLogger.i("RestoreRepository", "Restauração finalizada em $formattedDurationText ($succ sucesso, $failed falhas)")
                appendRestoreLog("Restauração finalizada em $formattedDurationText ($succ sucesso, $failed falhas)")

                // Persist to Room OperationHistoryEntity
                val roomDb = com.resync.app.data.room.AppRoomDatabase.getInstance(context)
                val historyEntity = com.resync.app.data.room.OperationHistoryEntity(
                    id = "restore_${System.currentTimeMillis()}",
                    type = "RESTORATION",
                    title = fileToRestore.name,
                    destinationOrSource = summary.source,
                    startTimestamp = restoreStartTime,
                    endTimestamp = System.currentTimeMillis(),
                    totalDurationSeconds = monotonicDurationSeconds,
                    activeTimeSeconds = monotonicDurationSeconds,
                    idleTimeSeconds = 0L,
                    totalBytes = totalBytes,
                    totalItems = totalItems,
                    successCount = succ,
                    failedCount = if (failed >= 0) failed else 0,
                    averageSpeedMBps = avgSpeedMBps.toFloat(),
                    peakSpeedMBps = (avgSpeedMBps * 1.2).toFloat(),
                    networkTypeUsed = summary.networkUsed,
                    resultStatus = if (failed > 0) "SUCCESS_WITH_WARNINGS" else "SUCCESS",
                    formattedDurationText = formattedDurationText
                )
                repositoryScope.launch {
                    try {
                        roomDb.operationHistoryDao().insert(historyEntity)
                    } catch (e: Throwable) {
                        com.resync.app.util.AppLogger.e("RestoreRepository", "Erro ao salvar histórico de restauração", e)
                    }
                }

                // Post Completion notification
                com.resync.app.cloud.gdrive.CloudNotificationManager.postCompletionNotification(
                    context = context,
                    operationType = com.resync.app.cloud.gdrive.CloudNotificationManager.CompletionOperationType.RESTORE,
                    elapsedFormatted = formattedDurationText,
                    destinationOrSource = summary.source,
                    totalBytes = totalBytes,
                    totalItems = totalItems,
                    warningCount = if (failed > 0) failed else 0,
                    backupName = fileToRestore.name
                )

                _restoreProgress.value = RestoreProgress(
                    stage = "Concluído 100% - Restauração finalizada!",
                    currentItemName = "Restauração finalizada em $formattedDurationText",
                    progressPercent = 1.0f,
                    isFinished = true,
                    completedItems = _restoreProgress.value?.completedItems ?: emptyList()
                )
                persistState()

            } catch (ce: CancellationException) {
                cleanupTempCache()
                appendRestoreLog("Restauração cancelada pelo usuário")
                _executionState.value = RestoreExecutionState.CANCELLED
                _restoreProgress.value = RestoreProgress(
                    stage = "Restauração Cancelada",
                    currentItemName = "Ação interrompida pelo usuário",
                    progressPercent = 0f,
                    isFinished = true,
                    error = "Restauração cancelada pelo usuário."
                )
                persistState()
            } catch (e: Throwable) {
                cleanupTempCache()
                val errMsg = e.localizedMessage ?: e.message ?: "Erro desconhecido"
                appendRestoreLog("ERRO: $errMsg")
                _executionState.value = RestoreExecutionState.ERROR
                _restoreProgress.value = RestoreProgress(
                    stage = "Falha na Restauração",
                    currentItemName = errMsg,
                    progressPercent = 1.0f,
                    isFinished = true,
                    error = errMsg
                )
                persistState()
            } finally {
                currentRestoreEngine = null
                currentDriveProvider = null
                RestoreForegroundService.stopService(context)
            }
        }
    }

    fun pauseRestore() {
        if (pauseStartElapsedMono == 0L) {
            pauseStartElapsedMono = android.os.SystemClock.elapsedRealtime()
        }
        _isRestorePaused.value = true
        currentRestoreEngine?.pause()
        currentDriveProvider?.pauseDownload()
        _restoreProgress.value?.let { current ->
            val paused = current.copy(isPaused = true, stage = if (current.stage.contains("Pausado", ignoreCase = true)) current.stage else "${current.stage} (Pausado)")
            _restoreProgress.value = paused
            persistState()
        }
    }

    fun resumeRestore() {
        if (pauseStartElapsedMono > 0L) {
            totalPausedDurationMs += (android.os.SystemClock.elapsedRealtime() - pauseStartElapsedMono)
            pauseStartElapsedMono = 0L
        }
        _isRestorePaused.value = false
        currentRestoreEngine?.resume()
        currentDriveProvider?.resumeDownload()
        _restoreProgress.value?.let { current ->
            val resumed = current.copy(isPaused = false, stage = current.stage.replace(" (Pausado)", "").replace("Pausado", "Executando"))
            _restoreProgress.value = resumed
            persistState()
        }
    }

    fun cancelRestore() {
        currentRestoreEngine?.cancel()
        currentDriveProvider?.cancelDownload()
        activeRestoreJob?.cancel()
        cleanupTempCache()
        val context = appContext
        if (context != null) {
            RestoreForegroundService.stopService(context)
        }
        _isRestorePaused.value = false
        totalPausedDurationMs = 0L
        pauseStartElapsedMono = 0L
        _executionState.value = RestoreExecutionState.CANCELLED
        _restoreProgress.value = RestoreProgress(
            stage = "Cancelado",
            currentItemName = "Restauração interrompida",
            progressPercent = 0f,
            isFinished = true,
            error = "Cancelado pelo usuário"
        )
        persistState()
    }

    fun retryRestore() {
        val types = lastSelectedTypes ?: return
        startRestore(
            localFile = lastLocalFile,
            remoteDriveFileId = lastRemoteDriveFileId,
            accountEmail = lastAccountEmail,
            selectedItemTypes = types,
            encryptionPassword = lastPassword,
            selectedCallKeys = lastSelectedCallKeys,
            selectedSmsKeys = lastSelectedSmsKeys,
            selectedContactKeys = lastSelectedContactKeys,
            selectedWifiKeys = lastSelectedWifiKeys,
            selectedBtKeys = lastSelectedBtKeys
        )
    }

    fun dismissRestoreStatus() {
        cleanupTempCache()
        _restoreProgress.value = null
        _restoreSummary.value = null
        _isRestorePaused.value = false
        _executionState.value = RestoreExecutionState.IDLE
        OperationRepository.removeOperationByType(OperationType.RESTORATION)
        prefs?.edit()?.clear()?.apply()
        val ctx = appContext
        if (ctx != null) {
            repositoryScope.launch {
                try {
                    com.resync.app.data.room.AppRoomDatabase.getInstance(ctx)
                        .activeOperationDao().deleteById(OperationRepository.RESTORE_OPERATION_ID)
                } catch (_: Throwable) {}
            }
        }
    }

    fun cleanupTempCache() {
        runCatching {
            lastTempDownloadedFile?.let { if (it.exists()) it.delete() }
            val dir = File(appContext?.cacheDir, "restore_temp")
            if (dir.exists()) dir.deleteRecursively()
            val restoreDir = File(appContext?.cacheDir, "restore")
            if (restoreDir.exists()) restoreDir.deleteRecursively()
        }
    }

    private fun syncToOperationRepository() {
        val progress = _restoreProgress.value
        val state = _executionState.value
        if (progress == null || state == RestoreExecutionState.IDLE) {
            OperationRepository.removeOperationByType(OperationType.RESTORATION)
            return
        }
        val isFinished = progress.isFinished || state == RestoreExecutionState.COMPLETED || state == RestoreExecutionState.ERROR || state == RestoreExecutionState.CANCELLED
        val status = when {
            state == RestoreExecutionState.CANCELLED -> OperationStatus.CANCELLED
            state == RestoreExecutionState.ERROR || progress.error != null -> OperationStatus.ERROR
            state == RestoreExecutionState.COMPLETED || progress.isFinished -> OperationStatus.COMPLETED
            progress.isPaused || _isRestorePaused.value -> OperationStatus.PAUSED
            state == RestoreExecutionState.STARTING -> OperationStatus.STARTING
            else -> OperationStatus.RUNNING
        }
        OperationRepository.updateRestoreOperation(
            stage = progress.stage,
            currentItemName = progress.currentItemName,
            progressPercent = progress.progressPercent,
            bytesProcessed = progress.bytesProcessed,
            totalBytesEstimated = progress.totalBytesEstimated,
            isFinished = isFinished,
            isPaused = progress.isPaused || _isRestorePaused.value,
            status = status,
            error = progress.error,
            currentItemIndex = progress.currentItemIndex,
            totalItems = progress.totalItems,
            currentItemType = progress.currentItemType,
            completedItems = progress.completedItems,
            downloadSpeedBytesPerSec = progress.downloadSpeedBytesPerSec,
            estimatedRemainingSeconds = progress.estimatedRemainingSeconds,
            source = _restoreSummary.value?.source ?: "Google Drive / Local",
            currentPackageName = progress.currentPackageName,
            containerFile = progress.containerFile,
            parts = progress.parts,
            thermalMode = progress.thermalMode,
            deviceTemperature = progress.deviceTemperature,
            localStage = progress.localStage,
            itemsPerSecond = progress.itemsPerSecond
        )
    }

    private fun persistState() {
        syncToOperationRepository()

        val p = _restoreProgress.value ?: return

        // Persistência em Room
        val ctx = appContext
        if (ctx != null) {
            repositoryScope.launch {
                try {
                    val roomDb = com.resync.app.data.room.AppRoomDatabase.getInstance(ctx)
                    val statusStr = when {
                        _executionState.value == RestoreExecutionState.CANCELLED -> "CANCELLED"
                        _executionState.value == RestoreExecutionState.ERROR || p.error != null -> "ERROR"
                        _executionState.value == RestoreExecutionState.COMPLETED || p.isFinished -> "COMPLETED"
                        p.isPaused || _isRestorePaused.value -> "PAUSED"
                        _executionState.value == RestoreExecutionState.STARTING -> "STARTING"
                        else -> "RUNNING"
                    }
                    val entity = com.resync.app.data.room.ActiveOperationEntity(
                        id = OperationRepository.RESTORE_OPERATION_ID,
                        type = "RESTORATION",
                        destination = _restoreSummary.value?.source ?: "Google Drive / Local",
                        stage = p.stage,
                        currentItemName = p.currentItemName,
                        currentItemIndex = p.currentItemIndex,
                        totalItems = p.totalItems,
                        progressPercent = p.progressPercent,
                        bytesProcessed = p.bytesProcessed,
                        totalBytesEstimated = p.totalBytesEstimated,
                        status = statusStr,
                        error = p.error,
                        updatedAt = System.currentTimeMillis()
                    )
                    roomDb.activeOperationDao().insertOrUpdate(entity)
                } catch (_: Throwable) {}
            }
        }

        prefs?.edit()
            ?.putString(KEY_EXEC_STATE, _executionState.value.name)
            ?.putString(KEY_STAGE, p.stage)
            ?.putString(KEY_ITEM, p.currentItemName)
            ?.putFloat(KEY_PROGRESS, p.progressPercent)
            ?.putBoolean(KEY_IS_FINISHED, p.isFinished)
            ?.putString(KEY_ERROR, p.error)
            ?.apply()
    }
}

