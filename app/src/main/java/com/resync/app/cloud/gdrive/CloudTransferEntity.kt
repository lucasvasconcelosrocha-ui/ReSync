package com.resync.app.cloud.gdrive

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * State names for persistent cloud transfers
 */
object CloudTransferState {
    const val EM_ANDAMENTO = "EmAndamento"
    const val AGUARDANDO_REDE = "AguardandoRede"
    const val PAUSADO_PELO_USUARIO = "PausadoPeloUsuario"
    const val FALHOU = "Falhou"
    const val CONCLUIDO = "Concluido"
}

@Entity(tableName = "cloud_transfers")
data class CloudTransferEntity(
    @PrimaryKey
    val jobId: String,
    val type: String, // "UPLOAD" or "DOWNLOAD"
    val title: String,
    val fileId: String? = null,
    val sessionUri: String? = null,
    val serverConfirmedOffset: Long = 0L,
    val totalBytes: Long = 0L,
    val localPath: String,
    val localFileLastModified: Long = 0L,
    val completedPartsJson: String? = null,
    val checksumSha256: String? = null,
    val expectedMd5: String? = null,
    val activeTimeMs: Long = 0L,
    val idleTimeMs: Long = 0L,
    val lastStateTimestamp: Long = System.currentTimeMillis(),
    val state: String = CloudTransferState.EM_ANDAMENTO,
    val accountEmail: String? = null,
    val targetFolderId: String? = null,
    val errorMessage: String? = null,
    val currentSpeedMBps: Float = 0f,
    val executorLockOwner: String? = null,
    val executorLockTimestamp: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)
