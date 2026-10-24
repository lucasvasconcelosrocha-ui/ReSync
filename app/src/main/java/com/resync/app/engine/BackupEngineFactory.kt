package com.resync.app.engine

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fábrica e detector automático de ambiente de privilégios (Root vs Sem Root).
 * Inspiração arquitetural: Swift Backup.
 *
 * Realiza a deteção silenciosa e segura da disponibilidade de privilégios de Superusuário
 * sem travar o thread principal e sem disparar erros críticos caso o binário não esteja disponível.
 */
object BackupEngineFactory {

    private const val TAG = "BackupEngineFactory"

    @Volatile
    private var cachedHasRoot: Boolean? = null

    /**
     * Deteção silenciosa e segura da disponibilidade de privilégios de Superusuário.
     * Verifica se o binário su está disponível e se a shell atual possui privilégios de root (uid=0).
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        cachedHasRoot?.let { return@withContext it }

        val hasRoot = try {
            Shell.isAppGrantedRoot() == true || (try { Shell.getShell().isRoot } catch (_: Throwable) { false })
        } catch (e: Throwable) {
            Log.w(TAG, "Falha na verificação silenciosa de Root: ${e.message}")
            false
        }

        cachedHasRoot = hasRoot
        hasRoot
    }

    /**
     * Força a reavaliação de privilégios (útil se o utilizador acabou de conceder permissão no Magisk/KernelSU).
     */
    fun invalidateCache() {
        cachedHasRoot = null
    }

    /**
     * Instancia e retorna a implementação apropriada de [IBackupEngine].
     *
     * @param context Contexto da aplicação.
     * @param forceRootMode Permite forçar um modo específico (útil para testes unitários ou auditoria).
     */
    suspend fun createEngine(
        context: Context,
        forceRootMode: Boolean? = null
    ): IBackupEngine {
        val rootActive = forceRootMode ?: isRootAvailable()
        return if (rootActive) {
            Log.i(TAG, "Dispositivo possui privilégios de Superusuário ativos. Utilizando RootBackupEngine.")
            RootBackupEngine(context.applicationContext)
        } else {
            Log.i(TAG, "Dispositivo operando sem Root. Fazendo fallback para NormalBackupEngine.")
            NormalBackupEngine(context.applicationContext)
        }
    }
}
