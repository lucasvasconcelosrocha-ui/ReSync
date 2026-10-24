package com.resync.app.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.resync.app.cloud.providers.GoogleDriveProvider
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.*
import com.resync.app.service.BackupForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class BackupExecutionState {
    IDLE,
    STARTING,
    IN_PROGRESS,
    COMPLETED,
    ERROR,
    CANCELLED
}

data class BackupSummaryData(
    val title: String,
    val sizeBytes: Long,
    val durationSeconds: Long,
    val destination: String,
    val totalItems: Int,
    val successCount: Int,
    val failedCount: Int,
    val ignoredCount: Int = 0,
    val startTimeFormatted: String = "",
    val endTimeFormatted: String = "",
    val activeDurationSeconds: Long = durationSeconds,
    val idleDurationSeconds: Long = 0L,
    val averageSpeedMBps: Float = 0f,
    val peakSpeedMBps: Float = 0f,
    val networkUsed: String = "Armazenamento Interno",
    val formattedDurationText: String = "",
    val hasWarningsOrErrors: Boolean = failedCount > 0 || ignoredCount > 0,
    val errorId: String? = null
)

/**
 * Singleton repository acting as the Single Source of Truth for backup operations.
 * Survives Activity destruction, provides live StateFlow for UI and Foreground Service,
 * and persists essential status across process recreation.
 */
object BackupRepository {

    private const val PREFS_NAME = "swiftvault_backup_repository_prefs"
    private const val KEY_STAGE = "key_stage"
    private const val KEY_ITEM = "key_item"
    private const val KEY_PROGRESS = "key_progress"
    private const val KEY_IS_FINISHED = "key_is_finished"
    private const val KEY_ERROR = "key_error"
    private const val KEY_IS_PAUSED = "key_is_paused"
    private const val KEY_TIMESTAMP = "key_timestamp"
    private const val KEY_EXEC_STATE = "key_exec_state"

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _backupProgress = MutableStateFlow<BackupProgress?>(null)
    val backupProgress: StateFlow<BackupProgress?> = _backupProgress.asStateFlow()

    private val _executionState = MutableStateFlow(BackupExecutionState.IDLE)
    val executionState: StateFlow<BackupExecutionState> = _executionState.asStateFlow()

    private val _backupSummary = MutableStateFlow<BackupSummaryData?>(null)
    val backupSummary: StateFlow<BackupSummaryData?> = _backupSummary.asStateFlow()

    // Consumable one-shot completion event (SharedFlow with buffer 1)
    private val _backupCompletionEvent = MutableSharedFlow<BackupSummaryData>(extraBufferCapacity = 1)
    val backupCompletionEvent: SharedFlow<BackupSummaryData> = _backupCompletionEvent.asSharedFlow()

    private val _isBackupPaused = MutableStateFlow(false)
    val isBackupPaused: StateFlow<Boolean> = _isBackupPaused.asStateFlow()

    fun isBackupActive(): Boolean {
        val state = _executionState.value
        return state == BackupExecutionState.STARTING || state == BackupExecutionState.IN_PROGRESS
    }

    fun isBackupRunning(): Boolean = isBackupActive()

    private var activeBackupJob: Job? = null
    private var currentEngine: BackupEngine? = null
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var backupStartTime = 0L
    private var backupStartElapsedMono = 0L
    private var backupStartWallTime = 0L
    private var totalPausedDurationMs = 0L
    private var pauseStartElapsedMono = 0L

    fun initialize(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (prefs == null) {
            val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            restoreSavedState(p)
        }
    }

    private fun restoreSavedState(p: SharedPreferences) {
        val timestamp = p.getLong(KEY_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        // Restore if last update was within 1 hour
        if (timestamp > 0 && (now - timestamp) < 3600_000L) {
            val stage = p.getString(KEY_STAGE, null) ?: return
            val item = p.getString(KEY_ITEM, "") ?: ""
            val progress = p.getFloat(KEY_PROGRESS, 0f)
            val isFinished = p.getBoolean(KEY_IS_FINISHED, true)
            val error = p.getString(KEY_ERROR, null)
            val isPaused = p.getBoolean(KEY_IS_PAUSED, false)
            val savedStateStr = p.getString(KEY_EXEC_STATE, null)
            val state = try {
                if (savedStateStr != null) BackupExecutionState.valueOf(savedStateStr)
                else if (isFinished) {
                    if (error != null) BackupExecutionState.ERROR else BackupExecutionState.COMPLETED
                } else BackupExecutionState.IN_PROGRESS
            } catch (_: Exception) { BackupExecutionState.IDLE }

            Log.d("BackupOrigin", "restoreSavedState: stage='$stage', isFinished=$isFinished, state=$state")

            if (isFinished || state == BackupExecutionState.COMPLETED ||
                state == BackupExecutionState.STARTING ||
                stage.contains("Iniciando", ignoreCase = true) ||
                state == BackupExecutionState.CANCELLED ||
                state == BackupExecutionState.IDLE) {
                p.edit().clear().apply()
                resetToIdle()
                return
            }

            _isBackupPaused.value = isPaused
            _executionState.value = state
            _backupProgress.value = BackupProgress(
                stage = stage,
                currentItemName = item,
                progressPercent = progress,
                isFinished = isFinished,
                error = error,
                isPaused = isPaused
            )
        } else {
            p.edit().clear().apply()
        }
    }

    private fun syncToOperationRepository(progress: BackupProgress?, state: BackupExecutionState) {
        if (progress == null || state == BackupExecutionState.IDLE) {
            OperationRepository.removeOperationByType(OperationType.BACKUP)
            return
        }
        val isFinished = progress.isFinished || state == BackupExecutionState.COMPLETED || state == BackupExecutionState.ERROR || state == BackupExecutionState.CANCELLED
        val status = when {
            state == BackupExecutionState.CANCELLED -> OperationStatus.CANCELLED
            state == BackupExecutionState.ERROR || progress.error != null -> OperationStatus.ERROR
            state == BackupExecutionState.COMPLETED || progress.isFinished -> OperationStatus.COMPLETED
            progress.isPaused -> OperationStatus.PAUSED
            state == BackupExecutionState.STARTING -> OperationStatus.STARTING
            else -> OperationStatus.RUNNING
        }
        OperationRepository.updateBackupOperation(
            stage = progress.stage,
            currentItemName = progress.currentItemName,
            progressPercent = progress.progressPercent,
            bytesProcessed = progress.bytesProcessed,
            totalBytesEstimated = progress.totalBytesEstimated,
            isFinished = isFinished,
            isPaused = progress.isPaused,
            status = status,
            error = progress.error,
            currentItemIndex = progress.currentItemIndex,
            totalItems = progress.totalItems,
            currentItemType = progress.currentItemType,
            completedItems = progress.completedItems,
            uploadSpeedBytesPerSec = progress.uploadSpeedBytesPerSec,
            downloadSpeedBytesPerSec = progress.downloadSpeedBytesPerSec,
            compressionSpeedBytesPerSec = progress.compressionSpeedBytesPerSec,
            estimatedRemainingSeconds = progress.estimatedRemainingSeconds,
            destination = _backupSummary.value?.destination ?: "Armazenamento Interno",
            currentPackageName = progress.currentPackageName,
            containerFile = progress.containerFile,
            parts = progress.parts,
            thermalMode = progress.thermalMode,
            deviceTemperature = progress.deviceTemperature,
            localStage = progress.localStage,
            itemsPerSecond = progress.itemsPerSecond
        )
    }

    private fun persistState(progress: BackupProgress?, state: BackupExecutionState? = null) {
        val targetState = state ?: _executionState.value
        syncToOperationRepository(progress, targetState)

        val isDone = targetState == BackupExecutionState.COMPLETED ||
                targetState == BackupExecutionState.IDLE ||
                targetState == BackupExecutionState.CANCELLED ||
                progress == null ||
                progress.isFinished

        // Persistência em Room (Fonte única de verdade resiliente à morte do processo)
        val ctx = appContext
        if (ctx != null) {
            repositoryScope.launch {
                try {
                    val roomDb = com.resync.app.data.room.AppRoomDatabase.getInstance(ctx)
                    val dao = roomDb.activeOperationDao()
                    if (isDone) {
                        dao.deleteById(OperationRepository.BACKUP_OPERATION_ID)
                    } else {
                        val statusStr = when {
                            targetState == BackupExecutionState.ERROR || progress.error != null -> "ERROR"
                            progress.isPaused -> "PAUSED"
                            targetState == BackupExecutionState.STARTING -> "STARTING"
                            else -> "RUNNING"
                        }
                        val entity = com.resync.app.data.room.ActiveOperationEntity(
                            id = OperationRepository.BACKUP_OPERATION_ID,
                            type = "BACKUP",
                            destination = _backupSummary.value?.destination ?: "Armazenamento Interno",
                            stage = progress.stage,
                            currentItemName = progress.currentItemName,
                            currentItemIndex = progress.currentItemIndex,
                            totalItems = progress.totalItems,
                            progressPercent = progress.progressPercent,
                            bytesProcessed = progress.bytesProcessed,
                            totalBytesEstimated = progress.totalBytesEstimated,
                            status = statusStr,
                            error = progress.error,
                            speedBytesPerSec = if (progress.uploadSpeedBytesPerSec > 0L) progress.uploadSpeedBytesPerSec else progress.speedBytesPerSec,
                            compressBytesPerSec = progress.compressionSpeedBytesPerSec,
                            updatedAt = System.currentTimeMillis()
                        )
                        dao.insertOrUpdate(entity)
                    }
                } catch (_: Throwable) {}
            }
        }

        val p = prefs ?: return
        if (isDone) {
            p.edit().clear().apply()
            return
        }
        val editor = p.edit()
        editor.putString(KEY_STAGE, progress.stage)
            .putString(KEY_ITEM, progress.currentItemName)
            .putFloat(KEY_PROGRESS, progress.progressPercent)
            .putBoolean(KEY_IS_FINISHED, progress.isFinished)
            .putString(KEY_ERROR, progress.error)
            .putBoolean(KEY_IS_PAUSED, progress.isPaused)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        if (state != null) {
            editor.putString(KEY_EXEC_STATE, state.name)
        }
        editor.apply()
    }

    fun startBackup(
        context: Context,
        title: String,
        selectedApps: List<Pair<AppInfo, AppSelectionOptions>>,
        selectedFiles: List<String>,
        includeSms: Boolean,
        includeCalls: Boolean,
        includeContacts: Boolean = false,
        includeDns: Boolean,
        includeWallpaper: Boolean = false,
        includeWallpaperHome: Boolean = false,
        includeWallpaperLock: Boolean = false,
        includeWifi: Boolean = false,
        includeBluetooth: Boolean = false,
        encryptionPassword: String? = null,
        targetCloudProvider: CloudProviderType? = null,
        targetCloudAccountEmail: String? = null,
        destinationLabel: String? = null,
        destinationType: BackupDestinationType = BackupDestinationType.SOMENTE_INTERNA
    ) {
        Log.d("BackupOrigin", "startBackup triggered: title='$title', apps=${selectedApps.size}, files=${selectedFiles.size}, dest='$destinationLabel', type=$destinationType\nCaller stack:\n" + Log.getStackTraceString(Throwable()))
        initialize(context)
        val appCtx = context.applicationContext
        val dbHelper = AppDatabaseHelper.getInstance(appCtx)
        val gdriveProvider = GoogleDriveProvider(appCtx)

        _isBackupPaused.value = false
        _executionState.value = BackupExecutionState.STARTING
        backupStartTime = System.currentTimeMillis()
        backupStartWallTime = backupStartTime
        backupStartElapsedMono = android.os.SystemClock.elapsedRealtime()
        totalPausedDurationMs = 0L
        pauseStartElapsedMono = 0L
        _backupSummary.value = null
        activeBackupJob?.cancel()

        val initialProgress = BackupProgress(
            stage = "Iniciando serviço de backup...",
            currentItemName = "Preparando componentes do cofre...",
            progressPercent = 0.0f,
            isFinished = false
        )
        _backupProgress.value = initialProgress
        persistState(initialProgress, BackupExecutionState.STARTING)

        activeBackupJob = repositoryScope.launch {
            val engine = BackupEngine(appCtx)
            currentEngine = engine

            var hasReceivedFirstProgress = false
            val timeoutJob = launch {
                delay(15_000L)
                if (!hasReceivedFirstProgress && _executionState.value == BackupExecutionState.STARTING) {
                    val timeoutErr = "O serviço de backup não respondeu a tempo (timeout de 15s). Verifique as permissões e tente novamente."
                    dbHelper.insertLog(LogLevel.ERROR, "BackupRepository", timeoutErr)
                    val errProg = BackupProgress(
                        stage = "Falha ao Iniciar",
                        currentItemName = timeoutErr,
                        progressPercent = 1.0f,
                        isFinished = true,
                        error = timeoutErr
                    )
                    _executionState.value = BackupExecutionState.ERROR
                    _backupProgress.value = errProg
                    persistState(errProg, BackupExecutionState.ERROR)
                    BackupForegroundService.stopService(appCtx)
                    activeBackupJob?.cancel()
                }
            }

            try {
                BackupForegroundService.acquireLocks(appCtx)
                BackupForegroundService.startService(appCtx, title)

                engine.performSelectiveBackup(
                    backupTitle = title,
                    selectedApps = selectedApps,
                    selectedFilesAndFolders = selectedFiles,
                    includeSms = includeSms,
                    includeCalls = includeCalls,
                    includeContacts = includeContacts,
                    includeDns = includeDns,
                    includeWallpaper = includeWallpaper,
                    includeWallpaperHome = includeWallpaperHome,
                    includeWallpaperLock = includeWallpaperLock,
                    includeWifi = includeWifi,
                    includeBluetooth = includeBluetooth,
                    encryptionPassword = encryptionPassword,
                    destinationLabel = destinationLabel,
                    destinationType = destinationType
                ).collect { progress ->
                    hasReceivedFirstProgress = true
                    timeoutJob.cancel()

                    if (progress.isFinished) {
                        _isBackupPaused.value = false

                        // If Google Drive target requested, enqueue/start cloud upload before marking fully completed
                        if ((targetCloudProvider != null || destinationType == BackupDestinationType.SOMENTE_DRIVE || destinationType == BackupDestinationType.INTERNA_E_DRIVE) && (progress.error == null || progress.stage.contains("Concluído", ignoreCase = true))) {
                            _executionState.value = BackupExecutionState.IN_PROGRESS
                            val latest = dbHelper.getAllBackupRecords().firstOrNull()
                            if (latest != null) {
                                val destText = if (!targetCloudAccountEmail.isNullOrBlank()) " ($targetCloudAccountEmail)" else ""
                                val uploadPrep = progress.copy(
                                    stage = "Preparando envio ao Google Drive$destText...",
                                    currentItemName = "Conectando ao Drive...",
                                    progressPercent = 0.0f,
                                    isFinished = false
                                )
                                _backupProgress.value = uploadPrep
                                persistState(uploadPrep, BackupExecutionState.IN_PROGRESS)

                                performCloudUpload(
                                    appCtx = appCtx,
                                    dbHelper = dbHelper,
                                    gdriveProvider = gdriveProvider,
                                    record = latest,
                                    targetEmail = targetCloudAccountEmail,
                                    destinationType = destinationType
                                )
                            } else {
                                finishSuccessOrError(appCtx, progress, destinationLabel ?: "Memória interna")
                            }
                        } else {
                            finishSuccessOrError(appCtx, progress, destinationLabel ?: "Memória interna")
                        }
                    } else {
                        _executionState.value = BackupExecutionState.IN_PROGRESS
                        if (progress.isPaused) {
                            _isBackupPaused.value = true
                        }
                        _backupProgress.value = progress
                        persistState(progress, BackupExecutionState.IN_PROGRESS)
                    }
                }
            } catch (e: CancellationException) {
                timeoutJob.cancel()
                _isBackupPaused.value = false
                resetToIdle()
            } catch (e: Exception) {
                timeoutJob.cancel()
                BackupForegroundService.stopService(appCtx)
                _isBackupPaused.value = false
                _executionState.value = BackupExecutionState.ERROR
                dbHelper.insertLog(LogLevel.ERROR, "BackupRepository", "Erro ao executar backup: ${e.message}")
                val errState = BackupProgress(
                    stage = "Erro",
                    currentItemName = e.message ?: "Falha ao executar backup.",
                    progressPercent = 1.0f,
                    bytesProcessed = 0,
                    totalBytesEstimated = 0,
                    isFinished = true,
                    error = e.message
                )
                _backupProgress.value = errState
                persistState(errState, BackupExecutionState.ERROR)
            }
        }
    }

    private fun finishSuccessOrError(appCtx: Context, progress: BackupProgress, destinationLabel: String) {
        BackupForegroundService.stopService(appCtx)
        val hasFatalError = progress.error != null && !progress.stage.contains("Concluído", ignoreCase = true)
        val finalState = if (hasFatalError) BackupExecutionState.ERROR else BackupExecutionState.COMPLETED
        Log.d("BackupOrigin", "finishSuccessOrError: destination='$destinationLabel', isFinished=${progress.isFinished}, finalState=$finalState, error=${progress.error}")
        _executionState.value = finalState

        if (pauseStartElapsedMono > 0L) {
            totalPausedDurationMs += (android.os.SystemClock.elapsedRealtime() - pauseStartElapsedMono)
            pauseStartElapsedMono = 0L
        }

        val elapsedMonoSec = if (backupStartElapsedMono > 0L) {
            ((android.os.SystemClock.elapsedRealtime() - backupStartElapsedMono - totalPausedDurationMs) / 1000L).coerceAtLeast(1L)
        } else {
            ((System.currentTimeMillis() - backupStartTime - totalPausedDurationMs) / 1000L).coerceAtLeast(1L)
        }

        val startFormatted = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(if (backupStartWallTime > 0L) backupStartWallTime else backupStartTime))
        val endFormatted = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formattedDuration = com.resync.app.util.FormatUtils.formatDurationPtBr(elapsedMonoSec)

        val failedCnt = progress.completedItems.count { !it.success && !it.isIgnored }
        val ignoredCnt = progress.completedItems.count { it.isIgnored }
        val succCnt = progress.completedItems.count { it.success && !it.isIgnored }
        val avgSpeed = if (elapsedMonoSec > 0) ((progress.bytesProcessed / (1024f * 1024f)) / elapsedMonoSec) else 0f
        val netUsed = if (destinationLabel.contains("Drive", ignoreCase = true)) com.resync.app.cloud.gdrive.CloudNetworkMonitor.getActiveNetworkType() else "Armazenamento Interno"

        val effectiveTotalItems = if (progress.totalItems > 0) progress.totalItems else progress.completedItems.size
        val effectiveTotalBytes = if (progress.totalBytesEstimated > 0L) progress.totalBytesEstimated else progress.bytesProcessed

        val summaryData = BackupSummaryData(
            title = progress.currentItemName,
            sizeBytes = effectiveTotalBytes,
            durationSeconds = elapsedMonoSec,
            destination = destinationLabel,
            totalItems = effectiveTotalItems,
            successCount = succCnt,
            failedCount = failedCnt,
            ignoredCount = ignoredCnt,
            startTimeFormatted = startFormatted,
            endTimeFormatted = endFormatted,
            activeDurationSeconds = elapsedMonoSec,
            idleDurationSeconds = 0L,
            averageSpeedMBps = avgSpeed,
            peakSpeedMBps = avgSpeed * 1.25f,
            networkUsed = netUsed,
            formattedDurationText = formattedDuration,
            hasWarningsOrErrors = failedCnt > 0 || ignoredCnt > 0 || hasFatalError,
            errorId = if (hasFatalError) progress.error else null
        )
        _backupSummary.value = summaryData
        _backupCompletionEvent.tryEmit(summaryData)

        OperationRepository.updateBackupOperation(
            stage = progress.stage,
            currentItemName = progress.currentItemName,
            progressPercent = 1.0f,
            bytesProcessed = effectiveTotalBytes,
            totalBytesEstimated = effectiveTotalBytes,
            isFinished = true,
            status = if (hasFatalError) OperationStatus.ERROR else OperationStatus.COMPLETED,
            error = progress.error,
            currentItemIndex = effectiveTotalItems,
            totalItems = effectiveTotalItems,
            completedItems = progress.completedItems,
            destination = destinationLabel,
            totalDurationSeconds = elapsedMonoSec,
            formattedDuration = formattedDuration
        )

        // Save to Room OperationHistoryEntity
        val history = com.resync.app.data.room.OperationHistoryEntity(
            id = java.util.UUID.randomUUID().toString(),
            type = "BACKUP",
            title = progress.currentItemName,
            destinationOrSource = destinationLabel,
            startTimestamp = if (backupStartWallTime > 0L) backupStartWallTime else backupStartTime,
            endTimestamp = System.currentTimeMillis(),
            totalDurationSeconds = elapsedMonoSec,
            activeTimeSeconds = elapsedMonoSec,
            idleTimeSeconds = 0L,
            totalBytes = effectiveTotalBytes,
            totalItems = effectiveTotalItems,
            successCount = succCnt,
            failedCount = failedCnt,
            averageSpeedMBps = avgSpeed,
            peakSpeedMBps = avgSpeed * 1.25f,
            networkTypeUsed = netUsed,
            resultStatus = if (hasFatalError) "ERROR" else if (failedCnt > 0) "SUCCESS_WITH_WARNINGS" else "SUCCESS",
            formattedDurationText = formattedDuration
        )
        repositoryScope.launch {
            try {
                com.resync.app.data.room.AppRoomDatabase.getInstance(appCtx).operationHistoryDao().insert(history)
            } catch (_: Throwable) {}
        }

        val backupTitle = progress.currentItemName.substringBefore(" • ").trim()
        val isCloudUpload = destinationLabel.contains("Drive", ignoreCase = true)
        val opType = if (isCloudUpload) {
            com.resync.app.cloud.gdrive.CloudNotificationManager.CompletionOperationType.CLOUD_UPLOAD
        } else {
            com.resync.app.cloud.gdrive.CloudNotificationManager.CompletionOperationType.BACKUP
        }

        com.resync.app.cloud.gdrive.CloudNotificationManager.postCompletionNotification(
            context = appCtx,
            operationType = opType,
            elapsedFormatted = formattedDuration,
            destinationOrSource = destinationLabel,
            totalBytes = effectiveTotalBytes,
            totalItems = effectiveTotalItems,
            warningCount = failedCnt,
            backupName = backupTitle
        )
        com.resync.app.util.AppLogger.i("BackupRepository", "Backup concluído em $formattedDuration ($startFormatted até $endFormatted). Total: ${progress.bytesProcessed} bytes.")

        repositoryScope.launch {
            try {
                com.resync.app.data.room.AppRoomDatabase.getInstance(appCtx).activeOperationDao().deleteById(OperationRepository.BACKUP_OPERATION_ID)
            } catch (_: Throwable) {}
        }
        OperationRepository.removeOperationByType(OperationType.BACKUP)
        prefs?.edit()?.clear()?.apply()

        _backupProgress.value = progress
        persistState(progress, finalState)
    }

    private suspend fun performCloudUpload(
        appCtx: Context,
        dbHelper: AppDatabaseHelper,
        gdriveProvider: GoogleDriveProvider,
        record: BackupRecord,
        targetEmail: String?,
        destinationType: BackupDestinationType = BackupDestinationType.SOMENTE_INTERNA
    ) {
        Log.d("BackupOrigin", "performCloudUpload triggered: recordId='${record.id}', title='${record.title}', destType=$destinationType")
        val currentCompletedItems = _backupProgress.value?.completedItems ?: emptyList()
        val currentTotalItems = _backupProgress.value?.totalItems ?: currentCompletedItems.size

        try {
            val accountDesc = if (!targetEmail.isNullOrBlank()) " ($targetEmail)" else ""
            BackupForegroundService.acquireLocks(appCtx)
            BackupForegroundService.startService(appCtx, "Enviando ${record.title} para Google Drive$accountDesc...")

            val result = gdriveProvider.cloudBackupRepository.uploadLocalBackup(record, targetEmail) { progressInfo ->
                val speedStr = if (progressInfo.speedMBps > 0) " • %.1f MB/s".format(java.util.Locale.US, progressInfo.speedMBps) else ""
                val etaStr = if (progressInfo.estimatedRemainingSeconds > 0) " • Restam ${progressInfo.estimatedRemainingSeconds}s" else ""
                val destStr = if (!targetEmail.isNullOrBlank()) " • Destino: $targetEmail" else ""
                val currentName = "${record.title}$speedStr$etaStr$destStr"

                val speedBytesSec = (progressInfo.speedMBps * 1024f * 1024f).toLong().coerceAtLeast(0L)
                val update = BackupProgress(
                    stage = progressInfo.stage,
                    currentItemName = currentName,
                    progressPercent = progressInfo.progressPercent,
                    bytesProcessed = progressInfo.bytesUploaded,
                    totalBytesEstimated = progressInfo.totalBytes,
                    isFinished = progressInfo.isFinished,
                    error = progressInfo.error,
                    totalItems = currentTotalItems,
                    completedItems = currentCompletedItems,
                    uploadSpeedBytesPerSec = speedBytesSec,
                    downloadSpeedBytesPerSec = 0L
                )
                _backupProgress.value = update
                persistState(update)
            }

            val driveFile = result.getOrNull()
            val localFile = File(record.filePath)
            val localLen = if (localFile.exists()) localFile.length() else record.sizeBytes
            val driveFileId = driveFile?.id.orEmpty()
            val driveFileSize = driveFile?.sizeBytes ?: 0L
            val isUploadValid = result.isSuccess && driveFile != null && driveFileId.isNotBlank() && driveFileSize > 0L && driveFileSize == localLen

            if (isUploadValid) {
                if (destinationType == BackupDestinationType.SOMENTE_DRIVE) {
                    // 1. Apagar o arquivo temporário de staging
                    val stagingFile = File(record.filePath)
                    if (stagingFile.exists()) {
                        stagingFile.delete()
                    }
                    val stagingParent = stagingFile.parentFile
                    if (stagingParent != null && stagingParent.name.startsWith("staging") && stagingParent.listFiles().isNullOrEmpty()) {
                        stagingParent.delete()
                    }

                    // 2. Garantir que NENHUMA cópia existe em ReSyncBackups ou diretórios legados
                    for (bDir in com.resync.app.data.model.BackupPaths.getAllLocalBackupDirs()) {
                        if (bDir.exists()) {
                            bDir.walkTopDown().filter { it.isFile && (it.name == "${record.id}.resync" || it.name == "${record.id}.zip" || it.name == "${record.id}.svb") }.forEach {
                                it.delete()
                            }
                        }
                    }

                    // 3. Atualizar o registro com os locais reais: filePath vazio, destination = "Google Drive"
                    val driveOnlyRecord = record.copy(
                        filePath = "",
                        destination = if (!targetEmail.isNullOrBlank()) "Drive • $targetEmail" else "Google Drive",
                        status = BackupStatus.COMPLETED,
                        syncStatus = SyncStatus.SYNCED,
                        driveFileId = driveFileId
                    )
                    dbHelper.insertBackupRecord(driveOnlyRecord)

                    // 4. Registrar verificação rigorosa no Diagnóstico
                    val diagMsg = "Arquivo enviado e verificado no Google Drive: '${record.title}' (ID: $driveFileId, tamanho: $driveFileSize bytes). Armazenado exclusivamente no Drive."
                    dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", diagMsg)
                    android.util.Log.i("BackupRepository", diagMsg)

                    val destStr = if (!targetEmail.isNullOrBlank()) " • $targetEmail" else ""
                    val finishedSuccess = BackupProgress(
                        stage = "Concluído",
                        currentItemName = "${record.title} • Armazenado exclusivamente no Google Drive$destStr",
                        progressPercent = 1.0f,
                        bytesProcessed = driveFileSize,
                        totalBytesEstimated = driveFileSize,
                        isFinished = true,
                        totalItems = currentTotalItems,
                        completedItems = currentCompletedItems
                    )
                    finishSuccessOrError(appCtx, finishedSuccess, "Google Drive$destStr")
                } else {
                    // INTERNA_E_DRIVE: mantém a cópia na memória interna e marca como Interna + Drive
                    val destStr = if (!targetEmail.isNullOrBlank()) " • $targetEmail" else ""
                    val bothRecord = record.copy(
                        destination = "Interna + Drive",
                        status = BackupStatus.COMPLETED,
                        syncStatus = SyncStatus.SYNCED,
                        driveFileId = driveFileId
                    )
                    dbHelper.insertBackupRecord(bothRecord)

                    val diagMsg = "Arquivo enviado e verificado no Google Drive: '${record.title}' (ID: $driveFileId, tamanho: $driveFileSize bytes) e salvo na memória interna (${record.filePath})."
                    dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", diagMsg)
                    android.util.Log.i("BackupRepository", diagMsg)

                    val finishedSuccess = BackupProgress(
                        stage = "Concluído",
                        currentItemName = "${record.title} • Sincronizado com Google Drive$destStr e salvo internamente",
                        progressPercent = 1.0f,
                        bytesProcessed = record.sizeBytes,
                        totalBytesEstimated = record.sizeBytes,
                        isFinished = true,
                        totalItems = currentTotalItems,
                        completedItems = currentCompletedItems
                    )
                    finishSuccessOrError(appCtx, finishedSuccess, "Interna + Drive")
                }
            } else {
                val ex = result.exceptionOrNull()
                val isConsentNeeded = ex is com.resync.app.cloud.gdrive.AuthIntentRequiredException
                val is401 = isConsentNeeded ||
                        ex is com.resync.app.cloud.gdrive.GoogleDriveSessionExpiredException ||
                        ex?.message?.contains("401") == true

                val accountName = targetEmail ?: "Google Drive"
                val failureReason = when {
                    ex != null -> ex.message ?: "Falha na sincronização."
                    driveFileId.isBlank() -> "A API do Google Drive não retornou um ID de arquivo válido."
                    driveFileSize == 0L -> "O arquivo foi enviado com 0 bytes para o Google Drive."
                    driveFileSize != localLen -> "Inconsistência no tamanho: local=$localLen bytes, remoto=$driveFileSize bytes."
                    else -> "Falha na validação do upload no Google Drive."
                }
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha no envio de '${record.title}': $failureReason")

                val err = if (destinationType == BackupDestinationType.SOMENTE_DRIVE) {
                    if (is401) {
                        "Falha no envio ao Google Drive: sessão da conta '$accountName' expirou (HTTP 401). Nenhuma cópia foi salva na memória interna."
                    } else {
                        "Falha no envio ao Google Drive: $failureReason. O arquivo temporário foi preservado."
                    }
                } else {
                    if (is401) {
                        "Backup local gravado com sucesso! Envio ao Google Drive pendente: a sessão da conta '$accountName' expirou (HTTP 401). Reconecte esta conta para sincronizar."
                    } else {
                        "Backup local gravado com sucesso! Envio ao Google Drive pendente: $failureReason"
                    }
                }

                val cloudErrorItem = CompletedItemInfo(
                    name = "Upload Google Drive ($accountName)",
                    type = BackupItemType.FILE,
                    success = false,
                    error = err,
                    stage = "Upload na Nuvem",
                    causeException = ex?.javaClass?.simpleName ?: "Falha na sincronização",
                    suggestedFix = if (is401) "Reconecte a conta do Google Drive no card do Drive." else "Verifique a conexão de rede e espaço de armazenamento na nuvem."
                )
                val updatedItems = currentCompletedItems.toMutableList().apply { add(cloudErrorItem) }

                val finishedWarning = BackupProgress(
                    stage = if (destinationType == BackupDestinationType.SOMENTE_DRIVE) "Erro no Envio" else "Concluído com Avisos",
                    currentItemName = err,
                    progressPercent = 1.0f,
                    bytesProcessed = record.sizeBytes,
                    totalBytesEstimated = record.sizeBytes,
                    isFinished = true,
                    error = err,
                    totalItems = updatedItems.size,
                    completedItems = updatedItems
                )
                finishSuccessOrError(appCtx, finishedWarning, if (destinationType == BackupDestinationType.SOMENTE_DRIVE) "Google Drive (falha no envio)" else "Interna + Drive (pendente)")
            }
        } catch (e: Exception) {
            val accountName = targetEmail ?: "Google Drive"
            val msg = if (destinationType == BackupDestinationType.SOMENTE_DRIVE) {
                "Falha ao enviar para Google Drive: ${e.message ?: "Erro desconhecido"}. Nenhuma cópia foi salva na memória interna."
            } else {
                "Backup local gravado com sucesso! Envio ao Google Drive pendente: ${e.message ?: "Falha na sincronização."}"
            }
            val cloudErrorItem = CompletedItemInfo(
                name = "Upload Google Drive ($accountName)",
                type = BackupItemType.FILE,
                success = false,
                error = msg,
                stage = "Upload na Nuvem",
                causeException = "${e.javaClass.simpleName}: ${e.message}",
                suggestedFix = "Verifique a conexão com a internet e tente sincronizar novamente."
            )
            val updatedItems = currentCompletedItems.toMutableList().apply { add(cloudErrorItem) }

            val finishedWarning = BackupProgress(
                stage = if (destinationType == BackupDestinationType.SOMENTE_DRIVE) "Erro no Envio" else "Concluído com Avisos",
                currentItemName = msg,
                progressPercent = 1.0f,
                bytesProcessed = record.sizeBytes,
                totalBytesEstimated = record.sizeBytes,
                isFinished = true,
                error = msg,
                totalItems = updatedItems.size,
                completedItems = updatedItems
            )
            finishSuccessOrError(appCtx, finishedWarning, if (destinationType == BackupDestinationType.SOMENTE_DRIVE) "Google Drive (falha no envio)" else "Interna + Drive (pendente)")
        }
    }

    fun pauseBackup() {
        if (pauseStartElapsedMono == 0L) {
            pauseStartElapsedMono = android.os.SystemClock.elapsedRealtime()
        }
        _isBackupPaused.value = true
        repositoryScope.launch {
            currentEngine?.pause()
        }
        _backupProgress.value?.let { current ->
            val paused = current.copy(isPaused = true, stage = if (current.stage.contains("Pausado", ignoreCase = true)) current.stage else "${current.stage} (Pausado)")
            _backupProgress.value = paused
            persistState(paused, _executionState.value)
        }
    }

    fun resumeBackup() {
        if (pauseStartElapsedMono > 0L) {
            totalPausedDurationMs += (android.os.SystemClock.elapsedRealtime() - pauseStartElapsedMono)
            pauseStartElapsedMono = 0L
        }
        _isBackupPaused.value = false
        repositoryScope.launch {
            currentEngine?.resume()
        }
        _backupProgress.value?.let { current ->
            val resumed = current.copy(isPaused = false, stage = current.stage.replace(" (Pausado)", "").replace("Pausado", "Executando"))
            _backupProgress.value = resumed
            persistState(resumed, _executionState.value)
        }
    }

    fun cancelBackup(context: Context? = null) {
        repositoryScope.launch {
            currentEngine?.cancel()
            activeBackupJob?.cancel()
        }
        _isBackupPaused.value = false
        totalPausedDurationMs = 0L
        pauseStartElapsedMono = 0L
        resetToIdle()
        val ctx = context ?: appContext
        if (ctx != null) {
            BackupForegroundService.stopService(ctx)
        }
    }

    fun updateProgress(progress: BackupProgress) {
        _backupProgress.value = progress
        persistState(progress, _executionState.value)
    }

    fun dismissProgress() {
        resetToIdle()
    }

    fun resetToIdle() {
        _executionState.value = BackupExecutionState.IDLE
        _backupProgress.value = null
        _backupSummary.value = null
        _isBackupPaused.value = false
        persistState(null, BackupExecutionState.IDLE)
        val ctx = appContext
        if (ctx != null) {
            BackupForegroundService.stopService(ctx)
        }
    }
}

