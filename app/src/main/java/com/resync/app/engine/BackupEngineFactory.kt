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
     * Deteção passiva, silenciosa e segura da disponibilidade de privilégios de Superusuário.
     * NÃO dispara prompt de superusuário na tela.
     */
    suspend fun isRootAvailable(context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        cachedHasRoot?.let { return@withContext it }

        val hasRoot = try {
            Shell.isAppGrantedRoot() == true || (try { Shell.getCachedShell()?.isRoot == true } catch (_: Throwable) { false })
        } catch (e: Throwable) {
            Log.w(TAG, "Falha na verificação passiva de Root: ${e.message}")
            false
        }

        cachedHasRoot = hasRoot
        hasRoot
    }

    /**
     * Verificação interativa explícita (chamada apenas quando o usuário solicita ativação de Root).
     */
    suspend fun verifyRootInteractive(context: Context): Boolean = withContext(Dispatchers.IO) {
        val hasRoot = try {
            CapabilityManager(context).requestRootAccess()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha na solicitação explícita de Root: ${e.message}")
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
        val rootActive = forceRootMode ?: isRootAvailable(context)
        return if (rootActive) {
            Log.i(TAG, "Dispositivo possui privilégios de Superusuário ativos. Utilizando RootBackupEngine.")
            RootBackupEngine(context.applicationContext)
        } else {
            Log.i(TAG, "Dispositivo operando sem Root. Fazendo fallback para NormalBackupEngine.")
            NormalBackupEngine(context.applicationContext)
        }
    }
}
