package com.resync.app.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Result data class for an individual app installation attempt.
 */
data class RestoreInstallResult(
    val success: Boolean,
    val statusCode: Int,
    val errorMessage: String? = null
)

/**
 * BroadcastReceiver for handling PackageInstaller session callbacks during non-root app restorations.
 * Receives STATUS_PENDING_USER_ACTION, prompts the user for install confirmation,
 * and notifies active suspension hooks on completion (SUCCESS or FAILURE).
 */
class RestoreResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTORE_RESULT = "com.resync.app.ACTION_RESTORE_RESULT"
        private const val TAG = "RestoreResultReceiver"

        private val activeSessions = ConcurrentHashMap<Int, CompletableDeferred<RestoreInstallResult>>()

        fun registerSession(sessionId: Int, deferred: CompletableDeferred<RestoreInstallResult>) {
            activeSessions[sessionId] = deferred
        }

        fun unregisterSession(sessionId: Int) {
            activeSessions.remove(sessionId)
        }

        fun createPendingIntent(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(context, RestoreResultReceiver::class.java).apply {
                action = ACTION_RESTORE_RESULT
                setPackage(context.packageName)
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, sessionId, intent, flags)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        Log.i(TAG, "Callback recebido: sessionId=$sessionId, status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context?.startActivity(confirmIntent)
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION recebido sem EXTRA_INTENT para sessão $sessionId")
                    activeSessions.remove(sessionId)?.complete(
                        RestoreInstallResult(false, status, "Sistema solicitou confirmação mas não forneceu a tela de instalação.")
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Instalação da sessão $sessionId concluída com sucesso.")
                activeSessions.remove(sessionId)?.complete(
                    RestoreInstallResult(true, status, null)
                )
            }

            else -> {
                val failureDescription = formatStatusError(status, message)
                Log.e(TAG, "Instalação da sessão $sessionId falhou: $failureDescription")
                activeSessions.remove(sessionId)?.complete(
                    RestoreInstallResult(false, status, failureDescription)
                )
            }
        }
    }

    private fun formatStatusError(status: Int, rawMessage: String?): String {
        val detail = rawMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalação cancelada pelo usuário$detail."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Instalação bloqueada pela política do dispositivo$detail."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflito com app existente (assinatura ou versão incompatível)$detail."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Pacote incompatível com a arquitetura ou versão do Android$detail."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Arquivo APK corrompido ou inválido$detail."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Espaço de armazenamento insuficiente no dispositivo$detail."
            else -> "Falha na instalação [código $status]$detail."
        }
    }
}

