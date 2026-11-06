package com.resync.app.engine.provider

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.model.CallRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Provedor Universal de Backup e Restauração de Histórico de Chamadas.
 * Extrai chamadas via CallLog.Calls com número, data, duração e tipo.
 * Restaura com deduplicação rigorosa (número, data e duração).
 */
class CallLogBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "CallLogBackupProvider"
    }

    private val gson = Gson()

    /**
     * Consulta o histórico de chamadas acessível.
     */
    suspend fun queryCallLogs(): List<CallRecord> = withContext(Dispatchers.IO) {
        val calls = mutableListOf<CallRecord>()
        val cr = context.contentResolver

        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.CACHED_NAME
            )

            val cursor = cr.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)

                while (c.moveToNext()) {
                    val num = c.getString(numIdx) ?: ""
                    val date = c.getLong(dateIdx)
                    val dur = c.getLong(durIdx)
                    val type = c.getInt(typeIdx)
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""

                    calls.add(
                        CallRecord(
                            number = num,
                            date = date,
                            duration = dur,
                            type = type,
                            cachedName = name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar histórico de chamadas: ${e.message}", e)
        }

        calls
    }

    /**
     * Salva o histórico de chamadas em JSON no diretório de destino.
     */
    suspend fun backupCallLogsToDir(outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val calls = queryCallLogs()
        val jsonFile = File(outputDir, "call_logs.json")
        jsonFile.writeText(gson.toJson(calls))
        jsonFile
    }

    /**
     * Restaura chamadas no banco de dados com prevenção de duplicatas.
     */
    suspend fun restoreCallLogsFromJson(jsonFile: File): Int = withContext(Dispatchers.IO) {
        if (!jsonFile.exists() || !jsonFile.canRead()) return@withContext 0

        val listType = object : TypeToken<List<CallRecord>>() {}.type
        val toRestore: List<CallRecord> = try {
            gson.fromJson(jsonFile.readText(), listType) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler chamadas: ${e.message}")
            emptyList()
        }

        if (toRestore.isEmpty()) return@withContext 0

        val existing = queryCallLogs()
        val existingKeys = existing.map { "${it.number.trim()}_${it.date}_${it.duration}" }.toSet()

        val cr = context.contentResolver
        var restored = 0

        for (call in toRestore) {
            val key = "${call.number.trim()}_${call.date}_${call.duration}"
            if (existingKeys.contains(key)) continue

            try {
                val values = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, call.number)
                    put(CallLog.Calls.DATE, call.date)
                    put(CallLog.Calls.DURATION, call.duration)
                    put(CallLog.Calls.TYPE, call.type)
                    if (!call.cachedName.isNullOrBlank()) {
                        put(CallLog.Calls.CACHED_NAME, call.cachedName)
                    }
                }
                cr.insert(CallLog.Calls.CONTENT_URI, values)
                restored++
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao reinserir registro de chamada ${call.number}: ${e.message}")
            }
        }

        restored
    }
}
