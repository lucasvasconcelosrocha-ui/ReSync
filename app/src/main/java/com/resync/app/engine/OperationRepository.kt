package com.resync.app.engine

import android.content.Context
import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.CompletedItemInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OperationType {
    BACKUP,
    RESTORATION
}

enum class OperationStatus {
    IDLE,
    STARTING,
    RUNNING,
    PAUSED,
    PAUSING,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    ERROR,
    INTERRUPTED
}

data class OperationPartInfo(
    val partIndex: Int,
    val totalParts: Int,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val progressPercent: Float,
    val speedMBps: Float,
    val isCompleted: Boolean = false
)

data class Operacao(
    val id: String,
    val type: OperationType,
    val title: String,
    val stage: String,
    val currentItemName: String,
    val progressPercent: Float,
    val bytesProcessed: Long = 0L,
    val totalBytesEstimated: Long = 0L,
    val isFinished: Boolean = false,
    val isPaused: Boolean = false,
    val status: OperationStatus = OperationStatus.RUNNING,
    val error: String? = null,
    val currentItemIndex: Int = 0,
    val totalItems: Int = 0,
    val currentItemType: BackupItemType = BackupItemType.FILE,
    val completedItems: List<CompletedItemInfo> = emptyList(),
    val uploadSpeedBytesPerSec: Long = 0L,
    val downloadSpeedBytesPerSec: Long = 0L,
    val compressionSpeedBytesPerSec: Long = 0L,
    val estimatedRemainingSeconds: Long = 0L,
    val destinationOrSource: String = "",
    val speedBytesPerSec: Long = if (uploadSpeedBytesPerSec > 0L) uploadSpeedBytesPerSec else if (downloadSpeedBytesPerSec > 0L) downloadSpeedBytesPerSec else compressionSpeedBytesPerSec,
    val compressBytesPerSec: Long = compressionSpeedBytesPerSec,
    val currentPackageName: String = "",
    val containerFile: java.io.File? = null,
    val parts: List<OperationPartInfo> = emptyList(),
    val thermalMode: String = "Máximo",
    val deviceTemperature: Float = 0f,
    val localStage: String = "",
    val itemsPerSecond: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val startTimeMillis: Long = System.currentTimeMillis(),
    val totalDurationSeconds: Long = 0L,
    val formattedDuration: String? = null
)

/**
 * Singleton repository acting as the Single Source of Truth for all active background operations
 * (both Backup and Restoration).
 * Holds an immutable StateFlow<List<Operacao>> that Compose observes reactively via collectAsStateWithLifecycle().
 */
object OperationRepository {

    const val BACKUP_OPERATION_ID = "op_backup_active"
    const val RESTORE_OPERATION_ID = "op_restore_active"

    private val _operations = MutableStateFlow<List<Operacao>>(emptyList())
    val operations: StateFlow<List<Operacao>> = _operations.asStateFlow()

    private val _completedOperations = MutableStateFlow<List<Operacao>>(emptyList())
    val completedOperations: StateFlow<List<Operacao>> = _completedOperations.asStateFlow()

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun recordCompletion(op: Operacao) {
        val current = _completedOperations.value.filter { it.id != op.id }
        _completedOperations.value = (listOf(op.copy(isFinished = true)) + current).take(5)
    }

    /**
     * Publishes or updates a backup operation in the reactive list.
     * Always replaces the item with an immutable copy or appends to a new list.
     */
    fun updateBackupOperation(
        stage: String,
        currentItemName: String,
        progressPercent: Float,
        bytesProcessed: Long = 0L,
        totalBytesEstimated: Long = 0L,
        isFinished: Boolean = false,
        isPaused: Boolean = false,
        status: OperationStatus = if (isFinished) OperationStatus.COMPLETED else if (isPaused) OperationStatus.PAUSED else OperationStatus.RUNNING,
        error: String? = null,
        currentItemIndex: Int = 0,
        totalItems: Int = 0,
        currentItemType: BackupItemType = BackupItemType.FILE,
        completedItems: List<CompletedItemInfo> = emptyList(),
        uploadSpeedBytesPerSec: Long = 0L,
        downloadSpeedBytesPerSec: Long = 0L,
        compressionSpeedBytesPerSec: Long = 0L,
        estimatedRemainingSeconds: Long = 0L,
        destination: String = "",
        currentPackageName: String = "",
        containerFile: java.io.File? = null,
        parts: List<OperationPartInfo> = emptyList(),
        thermalMode: String = "Máximo",
        deviceTemperature: Float = 0f,
        localStage: String = "",
        itemsPerSecond: Float = 0f,
        totalDurationSeconds: Long = 0L,
        formattedDuration: String? = null
    ) {
        val currentList = _operations.value
        val existingIndex = currentList.indexOfFirst { it.id == BACKUP_OPERATION_ID }
        val existing = if (existingIndex >= 0) currentList[existingIndex] else null
        val startTime = existing?.startTimeMillis ?: System.currentTimeMillis()
        val durationSec = if (totalDurationSeconds > 0L) {
            totalDurationSeconds
        } else if (isFinished || status == OperationStatus.COMPLETED) {
            ((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(1L)
        } else existing?.totalDurationSeconds ?: 0L
        val fmtDuration = formattedDuration ?: if (durationSec > 0L) com.resync.app.util.formatDurationPtBr(durationSec) else existing?.formattedDuration

        val newOp = Operacao(
            id = BACKUP_OPERATION_ID,
            type = OperationType.BACKUP,
            title = "Backup do Sistema e Apps",
            stage = stage,
            currentItemName = currentItemName,
            progressPercent = progressPercent,
            bytesProcessed = bytesProcessed,
            totalBytesEstimated = totalBytesEstimated,
            isFinished = isFinished,
            isPaused = isPaused,
            status = if (error != null) OperationStatus.ERROR else status,
            error = error,
            currentItemIndex = currentItemIndex,
            totalItems = totalItems,
            currentItemType = currentItemType,
            completedItems = completedItems,
            uploadSpeedBytesPerSec = uploadSpeedBytesPerSec,
            downloadSpeedBytesPerSec = downloadSpeedBytesPerSec,
            compressionSpeedBytesPerSec = compressionSpeedBytesPerSec,
            estimatedRemainingSeconds = estimatedRemainingSeconds,
            destinationOrSource = destination,
            currentPackageName = currentPackageName,
            containerFile = containerFile,
            parts = parts,
            thermalMode = thermalMode,
            deviceTemperature = deviceTemperature,
            localStage = localStage,
            itemsPerSecond = itemsPerSecond,
            startTimeMillis = startTime,
            totalDurationSeconds = durationSec,
            formattedDuration = fmtDuration
        )

        val updatedList = if (existingIndex >= 0) {
            val pendingStatus = currentList[existingIndex].status
            // Preserve optimistic status if the operation was just set to cancelling or pausing
            val resolvedOp = if (pendingStatus == OperationStatus.CANCELLING && !isFinished) {
                newOp.copy(status = OperationStatus.CANCELLING, stage = "Cancelando...")
            } else {
                newOp
            }
            currentList.toMutableList().apply { set(existingIndex, resolvedOp) }.toList()
        } else {
            currentList + newOp
        }

        _operations.value = updatedList
        if (isFinished || status == OperationStatus.COMPLETED || status == OperationStatus.ERROR || status == OperationStatus.CANCELLED) {
            recordCompletion(if (existingIndex >= 0) updatedList[existingIndex] else newOp)
        }
    }

    /**
     * Publishes or updates a restore operation in the reactive list.
     */
    fun updateRestoreOperation(
        stage: String,
        currentItemName: String,
        progressPercent: Float,
        bytesProcessed: Long = 0L,
        totalBytesEstimated: Long = 0L,
        isFinished: Boolean = false,
        isPaused: Boolean = false,
        status: OperationStatus = if (isFinished) OperationStatus.COMPLETED else if (isPaused) OperationStatus.PAUSED else OperationStatus.RUNNING,
        error: String? = null,
        currentItemIndex: Int = 0,
        totalItems: Int = 0,
        currentItemType: BackupItemType = BackupItemType.FILE,
        completedItems: List<CompletedItemInfo> = emptyList(),
        downloadSpeedBytesPerSec: Long = 0L,
        estimatedRemainingSeconds: Long = 0L,
        source: String = "",
        currentPackageName: String = "",
        containerFile: java.io.File? = null,
        parts: List<OperationPartInfo> = emptyList(),
        thermalMode: String = "Máximo",
        deviceTemperature: Float = 0f,
        localStage: String = "",
        itemsPerSecond: Float = 0f,
        totalDurationSeconds: Long = 0L,
        formattedDuration: String? = null
    ) {
        val currentList = _operations.value
        val existingIndex = currentList.indexOfFirst { it.id == RESTORE_OPERATION_ID }
        val existing = if (existingIndex >= 0) currentList[existingIndex] else null
        val startTime = existing?.startTimeMillis ?: System.currentTimeMillis()
        val durationSec = if (totalDurationSeconds > 0L) {
            totalDurationSeconds
        } else if (isFinished || status == OperationStatus.COMPLETED) {
            ((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(1L)
        } else existing?.totalDurationSeconds ?: 0L
        val fmtDuration = formattedDuration ?: if (durationSec > 0L) com.resync.app.util.formatDurationPtBr(durationSec) else existing?.formattedDuration

        val newOp = Operacao(
            id = RESTORE_OPERATION_ID,
            type = OperationType.RESTORATION,
            title = "Restauração de Backup",
            stage = stage,
            currentItemName = currentItemName,
            progressPercent = progressPercent,
            bytesProcessed = bytesProcessed,
            totalBytesEstimated = totalBytesEstimated,
            isFinished = isFinished,
            isPaused = isPaused,
            status = if (error != null) OperationStatus.ERROR else status,
            error = error,
            currentItemIndex = currentItemIndex,
            totalItems = totalItems,
            currentItemType = currentItemType,
            completedItems = completedItems,
            downloadSpeedBytesPerSec = downloadSpeedBytesPerSec,
            estimatedRemainingSeconds = estimatedRemainingSeconds,
            destinationOrSource = source,
            currentPackageName = currentPackageName,
            containerFile = containerFile,
            parts = parts,
            thermalMode = thermalMode,
            deviceTemperature = deviceTemperature,
            localStage = localStage,
            itemsPerSecond = itemsPerSecond,
            startTimeMillis = startTime,
            totalDurationSeconds = durationSec,
            formattedDuration = fmtDuration
        )

        val updatedList = if (existingIndex >= 0) {
            val pendingStatus = currentList[existingIndex].status
            val resolvedOp = if (pendingStatus == OperationStatus.CANCELLING && !isFinished) {
                newOp.copy(status = OperationStatus.CANCELLING, stage = "Cancelando...")
            } else {
                newOp
            }
            currentList.toMutableList().apply { set(existingIndex, resolvedOp) }.toList()
        } else {
            currentList + newOp
        }

        _operations.value = updatedList
        if (isFinished || status == OperationStatus.COMPLETED || status == OperationStatus.ERROR || status == OperationStatus.CANCELLED) {
            recordCompletion(if (existingIndex >= 0) updatedList[existingIndex] else newOp)
        }
    }

    /**
     * Publishes or updates a cloud upload operation in the reactive list.
     */
    fun updateUploadOperation(
        operationId: String,
        fileName: String,
        progressPercent: Float,
        bytesProcessed: Long = 0L,
        totalBytesEstimated: Long = 0L,
        isFinished: Boolean = false,
        isPaused: Boolean = false,
        status: OperationStatus = if (isFinished) OperationStatus.COMPLETED else if (isPaused) OperationStatus.PAUSED else OperationStatus.RUNNING,
        error: String? = null,
        uploadSpeedBytesPerSec: Long = 0L,
        estimatedRemainingSeconds: Long = 0L,
        destination: String = "Google Drive",
        parts: List<OperationPartInfo> = emptyList()
    ) {
        val currentList = _operations.value
        val existingIndex = currentList.indexOfFirst { it.id == operationId }

        val newOp = Operacao(
            id = operationId,
            type = OperationType.BACKUP,
            title = "Envio para a Nuvem",
            stage = if (isFinished) "Envio Concluído" else if (error != null) "Falha no Envio" else "Enviando arquivo...",
            currentItemName = fileName,
            progressPercent = progressPercent,
            bytesProcessed = bytesProcessed,
            totalBytesEstimated = totalBytesEstimated,
            isFinished = isFinished,
            isPaused = isPaused,
            status = if (error != null) OperationStatus.ERROR else status,
            error = error,
            currentItemIndex = 1,
            totalItems = 1,
            currentItemType = BackupItemType.FILE,
            completedItems = emptyList(),
            uploadSpeedBytesPerSec = uploadSpeedBytesPerSec,
            downloadSpeedBytesPerSec = 0L,
            compressionSpeedBytesPerSec = 0L,
            estimatedRemainingSeconds = estimatedRemainingSeconds,
            destinationOrSource = destination,
            parts = parts
        )

        val updatedList = if (existingIndex >= 0) {
            val pendingStatus = currentList[existingIndex].status
            val resolvedOp = if (pendingStatus == OperationStatus.CANCELLING && !isFinished) {
                newOp.copy(status = OperationStatus.CANCELLING, stage = "Cancelando...")
            } else {
                newOp
            }
            currentList.toMutableList().apply { set(existingIndex, resolvedOp) }.toList()
        } else {
            currentList + newOp
        }

        _operations.value = updatedList
        if (isFinished || status == OperationStatus.COMPLETED || status == OperationStatus.ERROR || status == OperationStatus.CANCELLED) {
            recordCompletion(if (existingIndex >= 0) updatedList[existingIndex] else newOp)
        }
    }

    /**
     * Optimistically pauses the operation in real-time and dispatches pause to the respective service.
     */
    fun optimisticPause(operationId: String) {
        _operations.value = _operations.value.map { op ->
            if (op.id == operationId) {
                op.copy(
                    isPaused = true,
                    status = OperationStatus.PAUSED,
                    stage = if (op.stage.contains("Pausado", ignoreCase = true)) op.stage else "${op.stage} (Pausado)"
                )
            } else op
        }

        val target = _operations.value.find { it.id == operationId } ?: return
        when (target.type) {
            OperationType.BACKUP -> BackupRepository.pauseBackup()
            OperationType.RESTORATION -> RestoreRepository.pauseRestore()
        }
    }

    /**
     * Optimistically resumes the operation in real-time and dispatches resume to the respective service.
     */
    fun optimisticResume(operationId: String) {
        _operations.value = _operations.value.map { op ->
            if (op.id == operationId) {
                op.copy(
                    isPaused = false,
                    status = OperationStatus.RUNNING,
                    stage = op.stage.replace(" (Pausado)", "").replace("Pausado", "Executando")
                )
            } else op
        }

        val target = _operations.value.find { it.id == operationId } ?: return
        when (target.type) {
            OperationType.BACKUP -> BackupRepository.resumeBackup()
            OperationType.RESTORATION -> RestoreRepository.resumeRestore()
        }
    }

    /**
     * Optimistically cancels the operation, cancels coroutines/jobs, removes from list, and stops services.
     */
    fun optimisticCancel(operationId: String) {
        val target = _operations.value.find { it.id == operationId }

        // Remove immediately from active operations list (optimistic UI update)
        _operations.value = _operations.value.filterNot { it.id == operationId }

        if (operationId.startsWith("upload_")) {
            try {
                appContext?.let { ctx ->
                    androidx.work.WorkManager.getInstance(ctx).cancelAllWorkByTag(operationId)
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    nm?.cancel(com.resync.app.worker.FileUploadWorker.NOTIFICATION_ID)
                }
            } catch (_: Throwable) {}
        }

        if (target != null) {
            recordCompletion(target.copy(status = OperationStatus.CANCELLED, isFinished = true, stage = "Cancelado pelo usuário"))
            if (!operationId.startsWith("upload_")) {
                when (target.type) {
                    OperationType.BACKUP -> {
                        BackupRepository.cancelBackup(appContext)
                        BackupRepository.dismissProgress()
                    }
                    OperationType.RESTORATION -> {
                        RestoreRepository.cancelRestore()
                        RestoreRepository.dismissRestoreStatus()
                    }
                }
            }
        }
    }

    /**
     * Dismisses a completed or errored card from the list immediately.
     */
    fun dismiss(operationId: String) {
        val target = _operations.value.find { it.id == operationId }
        _operations.value = _operations.value.filterNot { it.id == operationId }

        if (target != null) {
            recordCompletion(target.copy(isFinished = true))
            when (target.type) {
                OperationType.BACKUP -> BackupRepository.dismissProgress()
                OperationType.RESTORATION -> RestoreRepository.dismissRestoreStatus()
            }
        }
    }

    /**
     * Removes an operation of a specific type (e.g. on clean reset).
     */
    fun removeOperationByType(type: OperationType) {
        val target = _operations.value.find { it.type == type }
        if (target != null) {
            recordCompletion(target.copy(isFinished = true))
        }
        _operations.value = _operations.value.filterNot { it.type == type }
    }

    fun hasActiveOperations(): Boolean {
        return _operations.value.any { !it.isFinished && it.status != OperationStatus.CANCELLED && it.status != OperationStatus.ERROR }
    }
}

