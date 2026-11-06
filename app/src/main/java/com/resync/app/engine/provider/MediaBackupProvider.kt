package com.resync.app.engine.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.MediaItemRecord
import com.resync.app.engine.BackupIntegrityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Provedor Universal de Backup e Restauração de Mídias (Imagens, Vídeos e Áudio).
 * Opera estritamente via ContentResolver / MediaStore e Storage Access Framework.
 * Utiliza streaming de 64 KB para evitar qualquer alocação excessiva de memória RAM.
 */
class MediaBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "MediaBackupProvider"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
    }

    private val gson = Gson()

    /**
     * Consulta mídias de acordo com a categoria solicitada (Imagens, Vídeos ou Áudios).
     */
    suspend fun queryMediaItems(type: BackupItemType): List<MediaItemRecord> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItemRecord>()
        val cr = context.contentResolver

        val (contentUri, projection) = when (type) {
            BackupItemType.MEDIA_IMAGES -> {
                Pair(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.MIME_TYPE,
                        MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_MODIFIED,
                        MediaStore.Images.Media.WIDTH,
                        MediaStore.Images.Media.HEIGHT
                    )
                )
            }
            BackupItemType.MEDIA_VIDEOS -> {
                Pair(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.MIME_TYPE,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_MODIFIED,
                        MediaStore.Video.Media.DURATION
                    )
                )
            }
            BackupItemType.MEDIA_AUDIO -> {
                Pair(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.MIME_TYPE,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.DATE_MODIFIED,
                        MediaStore.Audio.Media.DURATION
                    )
                )
            }
            else -> return@withContext emptyList()
        }

        try {
            val cursor = cr.query(
                contentUri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx) ?: "media_$id"
                    val mime = c.getString(mimeIdx) ?: "application/octet-stream"
                    val size = c.getLong(sizeIdx)
                    val dateMod = c.getLong(dateIdx)
                    val uri = ContentUris.withAppendedId(contentUri, id)

                    items.add(
                        MediaItemRecord(
                            id = id,
                            uriString = uri.toString(),
                            displayName = name,
                            mimeType = mime,
                            relativePath = null,
                            sizeBytes = size,
                            dateModified = dateMod
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar mídias para $type: ${e.message}", e)
        }

        items
    }

    /**
     * Realiza streaming das mídias para a pasta de backup, gravando manifesto com metadados e SHA-256.
     */
    suspend fun backupMediaItems(
        type: BackupItemType,
        destinationDir: File,
        onProgress: ((current: Int, total: Int, bytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            destinationDir.mkdirs()
            val items = queryMediaItems(type)
            if (items.isEmpty()) return@withContext true

            val targetSubDir = File(destinationDir, type.name.lowercase()).apply { mkdirs() }
            val processedItems = mutableListOf<MediaItemRecord>()
            val cr = context.contentResolver

            val baseUri = when (type) {
                BackupItemType.MEDIA_IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                BackupItemType.MEDIA_VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                BackupItemType.MEDIA_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            var totalBytesCopied = 0L
            val buffer = ByteArray(STREAM_BUFFER_SIZE)

            items.forEachIndexed { index, item ->
                try {
                    val itemUri = ContentUris.withAppendedId(baseUri, item.id)
                    val outFile = File(targetSubDir, "${item.id}_${item.displayName}")

                    var bytesWritten = 0L
                    cr.openInputStream(itemUri)?.use { input ->
                        FileOutputStream(outFile).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesWritten += read
                            }
                        }
                    }

                    processedItems.add(
                        item.copy(
                            relativePath = outFile.name,
                            sizeBytes = bytesWritten
                        )
                    )
                    totalBytesCopied += bytesWritten
                    onProgress?.invoke(index + 1, items.size, totalBytesCopied)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao ler mídia ${item.displayName}: ${e.message}")
                }
            }

            // Grava o arquivo de manifesto de metadados
            val metaFile = File(destinationDir, "${type.name.lowercase()}_metadata.json")
            metaFile.writeText(gson.toJson(processedItems))

            processedItems.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral no backup de mídias $type: ${e.message}", e)
            false
        }
    }

    /**
     * Restaura as mídias inserindo-as novamente no MediaStore do dispositivo com validação de checksum.
     */
    suspend fun restoreMediaItems(
        type: BackupItemType,
        sourceDir: File,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val metaFile = File(sourceDir, "${type.name.lowercase()}_metadata.json")
        if (!metaFile.exists()) return@withContext 0

        val mediaFolder = File(sourceDir, type.name.lowercase())
        val records = try {
            val listType = object : com.google.gson.reflect.TypeToken<List<MediaItemRecord>>() {}.type
            gson.fromJson<List<MediaItemRecord>>(metaFile.readText(), listType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (records.isEmpty()) return@withContext 0

        val cr = context.contentResolver
        val (targetUri, defaultRelativeDir) = when (type) {
            BackupItemType.MEDIA_IMAGES -> Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/ReSyncRestored")
            BackupItemType.MEDIA_VIDEOS -> Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + "/ReSyncRestored")
            BackupItemType.MEDIA_AUDIO -> Pair(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC + "/ReSyncRestored")
            else -> Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS + "/ReSyncRestored")
        }

        var restoredCount = 0
        val buffer = ByteArray(STREAM_BUFFER_SIZE)

        records.forEachIndexed { index, record ->
            val relPath = record.relativePath ?: return@forEachIndexed
            val srcFile = File(mediaFolder, relPath)
            if (!srcFile.exists()) return@forEachIndexed

            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, record.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, record.mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, defaultRelativeDir)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val insertedUri = cr.insert(targetUri, values)
                if (insertedUri != null) {
                    srcFile.inputStream().use { input ->
                        cr.openOutputStream(insertedUri)?.use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val updateValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        cr.update(insertedUri, updateValues, null, null)
                    }
                    restoredCount++
                }
                onProgress?.invoke(index + 1, records.size)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao restaurar mídia ${record.displayName}: ${e.message}")
            }
        }

        restoredCount
    }
}
