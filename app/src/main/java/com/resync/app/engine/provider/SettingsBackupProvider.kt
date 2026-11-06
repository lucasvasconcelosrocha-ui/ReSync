package com.resync.app.engine.provider

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.model.SystemSettingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Provedor de Backup e Restauração de Configurações do Sistema.
 * Identifica e classifica as preferências públicas do Android em três categorias:
 * 1. RESTORABLE: Modificáveis via Settings.System quando WRITE_SETTINGS está concedida.
 * 2. MANUAL_ONLY: Legíveis, mas a alteração exige direcionar o usuário à tela de configurações.
 * 3. READ_ONLY_PROTECTED: Configurações protegidas de segurança (SECURE / GLOBAL restritas).
 */
class SettingsBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "SettingsBackupProvider"
    }

    private val gson = Gson()

    /**
     * Consulta e classifica as configurações públicas acessíveis do sistema.
     */
    suspend fun querySettings(): List<SystemSettingItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SystemSettingItem>()
        val cr = context.contentResolver

        // Configurações públicas comuns em Settings.System
        val systemKeys = listOf(
            Pair(Settings.System.SCREEN_OFF_TIMEOUT, "Tempo limite da tela"),
            Pair(Settings.System.SOUND_EFFECTS_ENABLED, "Sons de toque"),
            Pair(Settings.System.HAPTIC_FEEDBACK_ENABLED, "Resposta tátil"),
            Pair(Settings.System.ACCELEROMETER_ROTATION, "Rotação automática de tela"),
            Pair(Settings.System.FONT_SCALE, "Escala da fonte"),
            Pair(Settings.System.DTMF_TONE_WHEN_DIALING, "Sons do teclado de discagem")
        )

        for ((key, desc) in systemKeys) {
            try {
                val value = Settings.System.getString(cr, key) ?: ""
                list.add(
                    SystemSettingItem(
                        namespace = "system",
                        name = key,
                        value = value,
                        isRestorable = true,
                        description = desc
                    )
                )
            } catch (_: Exception) {}
        }

        // Configurações de leitura manual em Settings.Global
        val globalKeys = listOf(
            Pair("airplane_mode_on", "Modo Avião"),
            Pair("auto_time", "Data e hora automáticas"),
            Pair("auto_time_zone", "Fuso horário automático"),
            Pair("adb_enabled", "Depuração USB")
        )

        for ((key, desc) in globalKeys) {
            try {
                val value = Settings.Global.getString(cr, key) ?: ""
                list.add(
                    SystemSettingItem(
                        namespace = "global",
                        name = key,
                        value = value,
                        isRestorable = false,
                        description = desc
                    )
                )
            } catch (_: Exception) {}
        }

        list
    }

    /**
     * Exporta configurações para JSON no diretório de destino.
     */
    suspend fun backupSettingsToDir(outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val settings = querySettings()
        val jsonFile = File(outputDir, "system_settings.json")
        jsonFile.writeText(gson.toJson(settings))
        jsonFile
    }

    /**
     * Restaura configurações elegíveis quando a permissão de gravação estiver autorizada.
     */
    suspend fun restoreSettingsFromJson(jsonFile: File): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!jsonFile.exists() || !jsonFile.canRead()) return@withContext Pair(0, 0)

        val listType = object : TypeToken<List<SystemSettingItem>>() {}.type
        val items: List<SystemSettingItem> = try {
            gson.fromJson(jsonFile.readText(), listType) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler configurações: ${e.message}")
            emptyList()
        }

        val canWrite = Settings.System.canWrite(context)
        var autoRestored = 0
        var manualRequired = 0
        val cr = context.contentResolver

        for (item in items) {
            if (item.isRestorable && canWrite && item.namespace == "system") {
                try {
                    Settings.System.putString(cr, item.name, item.value)
                    autoRestored++
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao gravar ${item.name}: ${e.message}")
                    manualRequired++
                }
            } else {
                manualRequired++
            }
        }

        Pair(autoRestored, manualRequired)
    }

    /**
     * Cria Intent para abrir as configurações do sistema para ajuste manual.
     */
    fun createSystemSettingsIntent(): Intent {
        return Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
