package com.resync.app.cloud.gdrive

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enterprise-grade OAuth 2.0 Manager for official Google Drive integration.
 * Complies with Google OAuth 2.0 RFC 6749 and Android security standards.
 * Never requests or stores the user's Google password.
 * Always requests a fresh access token on-demand using GoogleAuthUtil without saving tokens to Room or DataStore.
 */
class OAuthManager(val context: Context) {

    private val prefs = context.getSharedPreferences("rodin_gdrive_auth", Context.MODE_PRIVATE)

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        val REQUIRED_SCOPES = listOf(DRIVE_FILE_SCOPE)

        private const val PREF_ACCOUNT_EMAIL = "account_email"
        private const val PREF_ACCOUNT_NAME = "account_name"
        private const val TAG = "OAuthManager"
    }

    init {
        // Strip out any previously stored tokens in SharedPreferences to guarantee tokens are never persisted
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("enc_access_token")
            .remove("enc_refresh_token")
            .remove("expiry_time")
            .apply()
    }

    /**
     * Clears corrupted credentials and marks session as expired to prompt clean re-login.
     */
    fun clearCorruptedCredentials() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("enc_access_token")
            .remove("enc_refresh_token")
            .remove("expiry_time")
            .putBoolean("session_expired", true)
            .apply()
    }

    /**
     * Create official Android account chooser intent for Google accounts
     */
    fun createChooseAccountIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
        } else {
            @Suppress("DEPRECATION")
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
        }
    }

    /**
     * Obtains a fresh access token directly from Google Play Services on-demand
     * without saving it into Room, DataStore or SharedPreferences.
     */
    suspend fun getFreshAccessToken(accountEmail: String? = null): String = withContext(Dispatchers.IO) {
        val email = accountEmail ?: getConnectedAccountEmail()
            ?: throw IllegalStateException("Nenhuma conta Google conectada.")

        val account = Account(email.trim(), "com.google")
        val scope = "oauth2:$DRIVE_FILE_SCOPE"

        try {
            val token = GoogleAuthUtil.getToken(context, account, scope)
            if (token.isNullOrBlank()) {
                throw IllegalStateException("GoogleAuthUtil retornou token vazio para $email.")
            }
            markSessionExpired(false)
            token
        } catch (e: UserRecoverableAuthException) {
            markSessionExpired(true)
            throw AuthIntentRequiredException(e.intent)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                throw GoogleCloudOAuthConfigurationException()
            }
            throw e
        }
    }

    /**
     * Authenticate and obtain OAuth 2.0 token on-demand using GoogleAuthUtil
     */
    suspend fun authenticateWithAccount(account: Account, activity: android.app.Activity? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            prefs.edit()
                .putString(PREF_ACCOUNT_EMAIL, account.name)
                .putString(PREF_ACCOUNT_NAME, account.name)
                .putBoolean("session_expired", false)
                .apply()

            val scope = "oauth2:$DRIVE_FILE_SCOPE"
            val token = GoogleAuthUtil.getToken(context, account, scope)
            Result.success(token)
        } catch (e: UserRecoverableAuthException) {
            markSessionExpired(true)
            Result.failure(AuthIntentRequiredException(e.intent))
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val causeMsg = e.cause?.message ?: ""
            if (msg.contains("UnregisteredOnApiConsole", ignoreCase = true) || causeMsg.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                Result.failure(
                    GoogleCloudOAuthConfigurationException(
                        "Cliente OAuth Android não registrado no Google Cloud Console (UnregisteredOnApiConsole).\nPacote: com.resync.app\nSHA-1: 58:09:B3:BB:3E:F4:E1:0A:32:8B:EC:3F:E5:A2:C9:60:44:B8:F8:F9"
                    )
                )
            } else if (msg.contains("access_denied", ignoreCase = true) || causeMsg.contains("access_denied", ignoreCase = true) || msg.contains("403")) {
                Result.failure(
                    GoogleDriveApiException(
                        403,
                        "Acesso negado pelo Google (Erro 403: access_denied). Verifique se o projeto OAuth no Google Cloud está configurado com o escopo drive.file e o SHA-1 da build atual."
                    )
                )
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Authenticate directly by Google Account email name
     */
    suspend fun authenticateWithAccountName(email: String, activity: android.app.Activity? = null): Result<String> {
        val account = Account(email.trim(), "com.google")
        return authenticateWithAccount(account, activity)
    }

    /**
     * Invalidate active cached token using GoogleAuthUtil.clearToken
     */
    fun invalidateToken(token: String) {
        if (token.isBlank()) return
        try {
            GoogleAuthUtil.clearToken(context, token)
            Log.i(TAG, "Token invalidado via GoogleAuthUtil.clearToken com sucesso.")
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao limpar token via GoogleAuthUtil: ${e.message}")
        }
        try {
            val accountManager = AccountManager.get(context)
            accountManager.invalidateAuthToken("com.google", token)
        } catch (_: Exception) {}
    }

    fun invalidateActiveToken() {
        clearCorruptedCredentials()
    }

    private val refreshMutex = Mutex()

    /**
     * Get valid access token on-demand without saving to Room/DataStore/Prefs
     */
    suspend fun getValidAccessToken(accountEmail: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            getFreshAccessToken(accountEmail)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao obter token sob demanda: ${e.message}")
            null
        }
    }

    /**
     * Force token refresh: invalidates previous token in GoogleAuthUtil and requests fresh token
     */
    suspend fun forceRefreshToken(currentToken: String? = null, accountEmail: String? = null): Result<String> = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            if (!currentToken.isNullOrBlank()) {
                invalidateToken(currentToken)
            }
            try {
                val token = getFreshAccessToken(accountEmail)
                Result.success(token)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Validate active token against Google's tokeninfo API
     */
    suspend fun validateActiveToken(): Result<Boolean> = withContext(Dispatchers.IO) {
        var token = getValidAccessToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Nenhum token configurado."))
        }

        try {
            val url = URL("https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=$token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                markSessionExpired(false)
                Result.success(true)
            } else if (responseCode == 401) {
                // Invalidate token and retry once
                invalidateToken(token)
                val refreshed = forceRefreshToken(currentToken = token)
                if (refreshed.isSuccess) {
                    val newToken = refreshed.getOrThrow()
                    val retryUrl = URL("https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=$newToken")
                    val retryConn = retryUrl.openConnection() as HttpURLConnection
                    retryConn.requestMethod = "GET"
                    retryConn.connectTimeout = 10000
                    retryConn.readTimeout = 10000
                    if (retryConn.responseCode == 200) {
                        markSessionExpired(false)
                        return@withContext Result.success(true)
                    }
                }
                markSessionExpired(true)
                Result.failure(GoogleDriveSessionExpiredException("Sessão do Google Drive expirada (HTTP 401). Reconecte sua conta.", 401))
            } else {
                Result.failure(GoogleDriveSessionExpiredException("Erro na validação do token (HTTP $responseCode)", responseCode))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshAccessToken(): Result<String> = forceRefreshToken()

    /**
     * Disconnect account and revoke token with Google
     */
    suspend fun disconnect(): Result<Boolean> = withContext(Dispatchers.IO) {
        val email = getConnectedAccountEmail()
        if (!email.isNullOrBlank()) {
            try {
                val token = getValidAccessToken(email)
                if (!token.isNullOrBlank()) {
                    val url = URL("https://oauth2.googleapis.com/revoke?token=$token")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8000
                    conn.responseCode
                    invalidateToken(token)
                }
            } catch (_: Exception) {}
        }
        clearCredentials()
        Result.success(true)
    }

    fun getConnectedAccountEmail(): String? = prefs.getString(PREF_ACCOUNT_EMAIL, null)

    fun setSelectedAccountEmail(email: String) {
        val cleanEmail = email.trim()
        if (cleanEmail.isNotBlank()) {
            val previousEmail = prefs.getString(PREF_ACCOUNT_EMAIL, null)
            val editor = prefs.edit()
                .putString(PREF_ACCOUNT_EMAIL, cleanEmail)
                .putString(PREF_ACCOUNT_NAME, cleanEmail)
            if (!previousEmail.equals(cleanEmail, ignoreCase = true)) {
                editor.remove("cached_rodin_folder_id")
                editor.putBoolean("session_expired", false)
            }
            editor.apply()
        }
    }

    fun isConnected(): Boolean {
        val email = prefs.getString(PREF_ACCOUNT_EMAIL, null)
        return !email.isNullOrBlank() && !isSessionExpired()
    }

    fun isSessionExpired(): Boolean = prefs.getBoolean("session_expired", false)

    fun markSessionExpired(expired: Boolean) {
        prefs.edit().putBoolean("session_expired", expired).apply()
    }

    fun getCachedRootFolderId(): String? = prefs.getString("cached_rodin_folder_id", null)

    fun setCachedRootFolderId(folderId: String?) {
        if (folderId != null) {
            prefs.edit().putString("cached_rodin_folder_id", folderId).apply()
        } else {
            prefs.edit().remove("cached_rodin_folder_id").apply()
        }
    }

    private fun clearCredentials() {
        prefs.edit().clear().apply()
    }
}

class AuthIntentRequiredException(val intent: Intent? = null) : Exception("Interação do usuário necessária para autenticação Google.")
class TokenExpiredOrRevokedException(msg: String) : Exception(msg)
class GoogleCloudOAuthConfigurationException(
    override val message: String = "Cliente OAuth não registrado no Google Cloud Console (UnregisteredOnApiConsole). Certifique-se de que o Client ID Android foi criado com o pacote 'com.resync.app' e a chave SHA-1 correspondente."
) : Exception(message)
class GoogleDriveSessionExpiredException(
    override val message: String = "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.",
    val statusCode: Int = 401
) : Exception(message)

