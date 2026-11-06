package com.resync.app.engine.provider

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.resync.app.engine.BackupIntegrityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Provedor Universal de Backup e Restauração de Documentos e Pastas.
 * Trabalha com pastas locais autorizadas e árvores SAF (Storage Access Framework).
 */
class DocumentBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "DocumentBackupProvider"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
    }

    /**
     * Realiza o backup de diretórios locais autorizados para o diretório de destino.
     */
    suspend fun backupLocalFolders(
        folderPaths: List<String>,
        outputDir: File,
        onProgress: ((copiedFiles: Int, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()
            val targets = if (folderPaths.isNotEmpty()) {
                folderPaths.map { File(it) }
            } else {
                listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                )
            }

            var copiedCount = 0
            var totalBytes = 0L
            val buffer = ByteArray(STREAM_BUFFER_SIZE)

            for (target in targets) {
                if (!target.exists() || !target.canRead()) continue

                val targetOutputDir = File(outputDir, target.name).apply { mkdirs() }

                target.walkTopDown().forEach { file ->
                    if (file.isFile && file.canRead()) {
                        val relPath = file.relativeTo(target).path
                        val destFile = File(targetOutputDir, relPath)
                        destFile.parentFile?.mkdirs()

                        file.inputStream().use { input ->
                            FileOutputStream(destFile).use { output ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    totalBytes += read
                                }
                            }
                        }
                        copiedCount++
                        onProgress?.invoke(copiedCount, totalBytes)
                    }
                }
            }
            copiedCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro no backup de pastas locais: ${e.message}", e)
            false
        }
    }

    /**
     * Realiza o backup de árvores persistidas via SAF (ACTION_OPEN_DOCUMENT_TREE).
     */
    suspend fun backupSafTrees(
        safTreeUris: List<Uri>,
        outputDir: File,
        onProgress: ((copiedFiles: Int, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()
            val cr = context.contentResolver
            var copiedCount = 0
            var totalBytes = 0L
            val buffer = ByteArray(STREAM_BUFFER_SIZE)

            for (treeUri in safTreeUris) {
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: continue
                val rootName = rootDoc.name ?: "saf_folder_${System.currentTimeMillis()}"
                val targetDir = File(outputDir, rootName).apply { mkdirs() }

                suspend fun walkSafDoc(doc: DocumentFile, currentDir: File) {
                    if (doc.isDirectory) {
                        val subDir = File(currentDir, doc.name ?: "sub").apply { mkdirs() }
                        for (child in doc.listFiles()) {
                            walkSafDoc(child, subDir)
                        }
                    } else if (doc.isFile) {
                        val outFile = File(currentDir, doc.name ?: "file_${System.currentTimeMillis()}")
                        cr.openInputStream(doc.uri)?.use { input ->
                            FileOutputStream(outFile).use { output ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    totalBytes += read
                                }
                            }
                        }
                        copiedCount++
                        onProgress?.invoke(copiedCount, totalBytes)
                    }
                }

                walkSafDoc(rootDoc, targetDir)
            }
            copiedCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro no backup via SAF: ${e.message}", e)
            false
        }
    }

    /**
     * Restaura os documentos para a pasta pública Documents/ReSyncRestored.
     */
    suspend fun restoreDocuments(
        backupSourceDir: File,
        onProgress: ((restoredFiles: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        if (!backupSourceDir.exists()) return@withContext 0

        val targetBase = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "ReSyncRestored"
        ).apply { mkdirs() }

        var count = 0
        val buffer = ByteArray(STREAM_BUFFER_SIZE)

        backupSourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relPath = file.relativeTo(backupSourceDir).path
                val destFile = File(targetBase, relPath)
                destFile.parentFile?.mkdirs()

                file.inputStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                    }
                }
                count++
                onProgress?.invoke(count)
            }
        }
        count
    }
}
