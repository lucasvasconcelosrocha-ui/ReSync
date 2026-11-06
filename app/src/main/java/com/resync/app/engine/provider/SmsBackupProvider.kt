package com.resync.app.engine.provider

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.model.SmsRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Provedor Universal de Backup e Restauração de Mensagens SMS.
 * Extrai mensagens autorizadas e exporta para JSON estruturado.
 * Na restauração, realiza deduplicação rigorosa (remetente/destinatário, timestamp e corpo da mensagem).
 */
class SmsBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "SmsBackupProvider"
    }

    private val gson = Gson()

    /**
     * Consulta todas as mensagens SMS acessíveis.
     */
    suspend fun querySms(): List<SmsRecord> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<SmsRecord>()
        val cr = context.contentResolver

        try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
                Telephony.Sms.BODY
            )

            val cursor = cr.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC")
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val readIdx = c.getColumnIndex(Telephony.Sms.READ)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)

                while (c.moveToNext()) {
                    val addr = c.getString(addrIdx) ?: ""
                    val date = c.getLong(dateIdx)
                    val read = if (readIdx >= 0) c.getInt(readIdx) else 1
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else 1
                    val body = c.getString(bodyIdx) ?: ""

                    messages.add(
                        SmsRecord(
                            address = addr,
                            body = body,
                            date = date,
                            type = type,
                            read = read
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar mensagens SMS: ${e.message}", e)
        }

        messages
    }

    /**
     * Exporta as mensagens para arquivo JSON dentro do diretório de destino.
     */
    suspend fun backupSmsToDir(outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val messages = querySms()
        val jsonFile = File(outputDir, "sms_messages.json")
        jsonFile.writeText(gson.toJson(messages))
        jsonFile
    }

    /**
     * Restaura mensagens a partir de arquivo JSON com deduplicação.
     */
    suspend fun restoreSmsFromJson(jsonFile: File): Int = withContext(Dispatchers.IO) {
        if (!jsonFile.exists() || !jsonFile.canRead()) return@withContext 0

        val listType = object : TypeToken<List<SmsRecord>>() {}.type
        val toRestore: List<SmsRecord> = try {
            gson.fromJson(jsonFile.readText(), listType) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear mensagens SMS: ${e.message}")
            emptyList()
        }

        if (toRestore.isEmpty()) return@withContext 0

        val existing = querySms()
        val existingKeys = existing.map { "${it.address.trim()}_${it.date}_${it.body.trim().hashCode()}" }.toSet()

        val cr = context.contentResolver
        var restored = 0

        for (sms in toRestore) {
            val key = "${sms.address.trim()}_${sms.date}_${sms.body.trim().hashCode()}"
            if (existingKeys.contains(key)) continue

            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sms.address)
                    put(Telephony.Sms.DATE, sms.date)
                    put(Telephony.Sms.READ, sms.read)
                    put(Telephony.Sms.TYPE, sms.type)
                    put(Telephony.Sms.BODY, sms.body)
                }
                cr.insert(Telephony.Sms.CONTENT_URI, values)
                restored++
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao reinserir SMS de ${sms.address}: ${e.message}")
            }
        }

        restored
    }
}
