package com.resync.app.cloud.gdrive

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.resync.app.data.room.AppRoomDatabase
import com.resync.app.data.room.CloudAccount
import com.resync.app.data.room.MaxCloudAccountsExceededException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

sealed class DriveAuthResult {
    data class Success(val account: CloudAccount) : DriveAuthResult()
    data class NeedsUserRecoverableAuth(val email: String, val recoveryIntent: Intent) : DriveAuthResult()
    data class Error(val email: String, val errorMessage: String, val cause: Throwable? = null) : DriveAuthResult()
}

/**
 * Repository providing Google Drive REST v3 operations per account.
 * Multi-account enabled: up to 5 accounts, using pure OkHttp3 HTTP/2 and REST v3.
 * Eliminates legacy google-api-client / Apache HTTP bloat and buffers.
 */
class DriveRepository(private val context: Context) {

    companion object {
        private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

        fun escapeDriveQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        fun parseIso8601ToMillis(isoString: String?): Long {
            if (isoString.isNullOrBlank()) return System.currentTimeMillis()
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.time.Instant.parse(isoString).toEpochMilli()
                } else {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                    sdf.parse(isoString)?.time ?: System.currentTimeMillis()
                }
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
        }
    }

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val roomDb = AppRoomDatabase.getInstance(context)
    private val dao = roomDb.cloudAccountDao()
    private val gson = Gson()

    private val folderMutexes = ConcurrentHashMap<String, Mutex>()
    private val folderIdCache = ConcurrentHashMap<String, String>()

    private fun getFolderMutex(pathKey: String): Mutex {
        return folderMutexes.computeIfAbsent(pathKey) { Mutex() }
    }

    val allAccounts: Flow<List<CloudAccount>> = dao.getAllAccounts()

    /**
     * Create the official system account chooser intent configured specifically
     * for Google accounts.
     */
    fun newChooseAccountIntent(): Intent {
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
     * Verify whether Google Play Services is available and updated.
     */
    fun checkGooglePlayServices(): Result<Unit> {
        return try {
            val availability = GoogleApiAvailability.getInstance()
            val code = availability.isGooglePlayServicesAvailable(context)
            if (code == ConnectionResult.SUCCESS) {
                Result.success(Unit)
            } else {
                val msg = availability.getErrorString(code)
                Result.failure(IllegalStateException("Google Play Services indisponível ou desatualizado ($msg)."))
            }
        } catch (_: Throwable) {
            Result.success(Unit)
        }
    }

    /**
     * Verify whether the specified account exists on the device.
     */
    fun isAccountOnDevice(email: String): Boolean {
        return try {
            val am = AccountManager.get(context)
            val accounts = am.getAccountsByType("com.google")
            accounts.any { it.name.equals(email.trim(), ignoreCase = true) }
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Obtains an OAuth2 access token for Google Drive via Google Play Services on-demand.
     */
    suspend fun getAccessToken(email: String): String = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        val account = Account(cleanEmail, "com.google")
        GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
    }

    /**
     * Invalidates cached token using GoogleAuthUtil.clearToken.
     */
    fun invalidateAccountToken(email: String) {
        val cleanEmail = email.trim()
        try {
            val account = Account(cleanEmail, "com.google")
            val token = GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
            if (!token.isNullOrBlank()) {
                GoogleAuthUtil.clearToken(context, token)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Inspects quota and user profile on Google Drive for a given account via REST v3:
     * GET https://www.googleapis.com/drive/v3/about?fields=storageQuota,user
     */
    suspend fun fetchAccountInfo(email: String): DriveAuthResult = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) {
            return@withContext DriveAuthResult.Error(email, "E-mail inválido ou em branco.")
        }

        // 1. Check account on device
        if (!isAccountOnDevice(cleanEmail)) {
            return@withContext DriveAuthResult.Error(
                cleanEmail,
                "A conta '$cleanEmail' não está cadastrada neste aparelho. Adicione a conta nas Configurações do Android primeiro."
            )
        }

        // 2. Check Google Play Services
        val playCheck = checkGooglePlayServices()
        if (playCheck.isFailure) {
            return@withContext DriveAuthResult.Error(
                cleanEmail,
                playCheck.exceptionOrNull()?.message ?: "Google Play Services não disponível."
            )
        }

        // 3. Check account limit in Room DB
        try {
            val existing = dao.getAccountByEmail(cleanEmail)
            if (existing == null && dao.getAccountCount() >= 5) {
                return@withContext DriveAuthResult.Error(
                    cleanEmail,
                    "Limite máximo de 5 contas atingido. Remova uma conta antes de adicionar outra."
                )
            }
        } catch (t: Throwable) {
            return@withContext DriveAuthResult.Error(cleanEmail, "Erro ao acessar banco de dados local: ${t.message}", t)
        }

        // 4. Connect to Google Drive on Dispatchers.IO via OkHttp REST v3
        try {
            var token = try {
                getAccessToken(cleanEmail)
            } catch (authEx: UserRecoverableAuthException) {
                val intent = authEx.intent
                if (intent != null) {
                    return@withContext DriveAuthResult.NeedsUserRecoverableAuth(cleanEmail, intent)
                } else {
                    return@withContext DriveAuthResult.Error(cleanEmail, "Autorização necessária para $cleanEmail.", authEx)
                }
            }

            val client = GDriveNetworkClient.client
            val aboutUrl = "https://www.googleapis.com/drive/v3/about?fields=storageQuota,user"

            fun executeAbout(authToken: String): String {
                val request = Request.Builder()
                    .url(aboutUrl)
                    .header("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code == 401) {
                        throw IllegalStateException("HTTP_401")
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP_${response.code}: $body")
                    }
                    return body
                }
            }

            val bodyStr = try {
                executeAbout(token)
            } catch (e: Exception) {
                if (e.message?.contains("HTTP_401") == true) {
                    invalidateAccountToken(cleanEmail)
                    token = getAccessToken(cleanEmail)
                    executeAbout(token)
                } else {
                    throw e
                }
            }

            val rootObj = gson.fromJson(bodyStr, JsonObject::class.java)
            val userObj = rootObj.getAsJsonObject("user")
            val quotaObj = rootObj.getAsJsonObject("storageQuota")

            val displayName = userObj?.get("displayName")?.asString ?: cleanEmail.substringBefore("@")
            val photoUrl = userObj?.get("photoLink")?.asString
            val quotaLimit = quotaObj?.get("limit")?.asLong ?: (15L * 1024 * 1024 * 1024)
            val quotaUsed = quotaObj?.get("usage")?.asLong ?: 0L

            val existing = dao.getAccountByEmail(cleanEmail)
            val isDefault = existing?.isDefault ?: (dao.getAccountCount() == 0)

            val hasDataChanged = existing == null ||
                    existing.name != displayName ||
                    existing.photoUrl != photoUrl ||
                    existing.quotaUsedBytes != quotaUsed ||
                    existing.quotaTotalBytes != quotaLimit ||
                    existing.isDefault != isDefault

            val account = CloudAccount(
                email = cleanEmail,
                name = displayName,
                photoUrl = photoUrl,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                isDefault = isDefault,
                quotaUsedBytes = quotaUsed,
                quotaTotalBytes = quotaLimit,
                lastSyncTimestamp = existing?.lastSyncTimestamp ?: System.currentTimeMillis()
            )

            if (hasDataChanged) {
                dao.insertValidated(account)
            }
            DriveAuthResult.Success(account)
        } catch (t: Throwable) {
            val recoverableIntent = if (t is UserRecoverableAuthException) t.intent else (t.cause as? UserRecoverableAuthException)?.intent
            if (recoverableIntent != null) {
                return@withContext DriveAuthResult.NeedsUserRecoverableAuth(cleanEmail, recoverableIntent)
            }

            val msg = t.message ?: ""
            val fullErr = "${t.javaClass.name}: $msg ${t.cause?.message ?: ""}"

            if (msg.contains("access_denied", ignoreCase = true) ||
                msg.contains("accessDenied", ignoreCase = true) ||
                msg.contains("403", ignoreCase = true) ||
                fullErr.contains("access_denied", ignoreCase = true)
            ) {
                return@withContext DriveAuthResult.Error(
                    cleanEmail,
                    "Acesso negado (403: access_denied). A conta Google não concedeu autorização para o app criar e gerenciar arquivos de backup.",
                    t
                )
            }

            if (msg.contains("UnregisteredOnApiConsole", ignoreCase = true) ||
                fullErr.contains("UnregisteredOnApiConsole", ignoreCase = true) ||
                msg.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                msg.contains("10: ", ignoreCase = true)
            ) {
                return@withContext DriveAuthResult.Error(
                    cleanEmail,
                    "Configuração OAuth pendente no Google Cloud Console. Verifique se o pacote 'com.resync.app' e o SHA-1 do keystore estão cadastrados no console Google Cloud com a API Google Drive habilitada.",
                    t
                )
            }

            if (t is MaxCloudAccountsExceededException) {
                return@withContext DriveAuthResult.Error(cleanEmail, t.message ?: "Limite de contas atingido.", t)
            }

            DriveAuthResult.Error(
                cleanEmail,
                "Falha ao autorizar conta no Google Drive: ${t.message ?: "Erro desconhecido"}",
                t
            )
        }
    }

    suspend fun addAccount(email: String): DriveAuthResult {
        return fetchAccountInfo(email)
    }

    suspend fun removeAccount(email: String) = withContext(Dispatchers.IO) {
        try {
            dao.deleteByEmail(email)
            val remaining = dao.getAllAccountsList()
            if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
                dao.setAccountAsDefault(remaining.first().email)
            }
        } catch (_: Throwable) {}
    }

    suspend fun setDefaultAccount(email: String) = withContext(Dispatchers.IO) {
        try {
            dao.setAccountAsDefault(email)
        } catch (_: Throwable) {}
    }

    suspend fun getDefaultAccount(): CloudAccount? = withContext(Dispatchers.IO) {
        try {
            dao.getDefaultAccount() ?: dao.getAllAccountsList().firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getAllAccountsList(): List<CloudAccount> = withContext(Dispatchers.IO) {
        try {
            dao.getAllAccountsList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun revalidateAllAccounts(): List<DriveAuthResult> = withContext(Dispatchers.IO) {
        val accounts = try {
            dao.getAllAccountsList()
        } catch (_: Throwable) {
            emptyList()
        }
        val results = mutableListOf<DriveAuthResult>()
        for (acc in accounts) {
            try {
                val res = fetchAccountInfo(acc.email)
                results.add(res)
            } catch (t: Throwable) {
                results.add(DriveAuthResult.Error(acc.email, t.message ?: "Erro ao revalidar", t))
            }
        }
        results
    }

    private fun parseDriveFileMetadata(obj: JsonObject): DriveFileMetadata {
        val id = obj.get("id")?.asString.orEmpty()
        val name = obj.get("name")?.asString.orEmpty()
        val size = obj.get("size")?.asLong ?: 0L
        val createdIso = obj.get("createdTime")?.asString
        val modifiedIso = obj.get("modifiedTime")?.asString
        val md5 = obj.get("md5Checksum")?.asString
        val mime = obj.get("mimeType")?.asString ?: "application/octet-stream"

        val appProps = mutableMapOf<String, String>()
        if (obj.has("appProperties") && !obj.get("appProperties").isJsonNull) {
            val propsObj = obj.getAsJsonObject("appProperties")
            for (key in propsObj.keySet()) {
                appProps[key] = propsObj.get(key)?.asString.orEmpty()
            }
        }

        return DriveFileMetadata(
            id = id,
            name = name,
            sizeBytes = size,
            createdTime = parseIso8601ToMillis(createdIso),
            modifiedTime = parseIso8601ToMillis(modifiedIso),
            md5Checksum = md5,
            mimeType = mime,
            appProperties = appProps
        )
    }

    private fun executeGetJson(url: String, email: String): JsonObject {
        var token = runCatching {
            val account = Account(email.trim(), "com.google")
            GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
        }.getOrThrow()

        val client = GDriveNetworkClient.client

        fun send(authToken: String): String {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) throw IllegalStateException("HTTP_401")
                if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}: $body")
                return body
            }
        }

        val bodyStr = try {
            send(token)
        } catch (e: Exception) {
            if (e.message?.contains("HTTP_401") == true) {
                invalidateAccountToken(email)
                val account = Account(email.trim(), "com.google")
                token = GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
                send(token)
            } else {
                throw e
            }
        }

        return gson.fromJson(bodyStr, JsonObject::class.java)
    }

    private fun executeCreateFolder(email: String, folderName: String, parentId: String?): String {
        var token = runCatching {
            val account = Account(email.trim(), "com.google")
            GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
        }.getOrThrow()

        val client = GDriveNetworkClient.client
        val createObj = JsonObject().apply {
            addProperty("name", folderName)
            addProperty("mimeType", "application/vnd.google-apps.folder")
            if (!parentId.isNullOrBlank()) {
                val parentsArr = JsonArray().apply { add(parentId) }
                add("parents", parentsArr)
            }
        }
        val reqBody = createObj.toString().toRequestBody(JSON_MEDIA_TYPE)

        fun send(authToken: String): String {
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer $authToken")
                .post(reqBody)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) throw IllegalStateException("HTTP_401")
                if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}: $body")
                return body
            }
        }

        val bodyStr = try {
            send(token)
        } catch (e: Exception) {
            if (e.message?.contains("HTTP_401") == true) {
                invalidateAccountToken(email)
                val account = Account(email.trim(), "com.google")
                token = GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
                send(token)
            } else {
                throw e
            }
        }

        val resJson = gson.fromJson(bodyStr, JsonObject::class.java)
        return resJson.get("id").asString
    }

    /**
     * Gets or creates the root folder in Google Drive for the given account.
     */
    suspend fun getOrCreateRootFolder(email: String, folderName: String = DriveConstants.ROOT_FOLDER_NAME): String = withContext(Dispatchers.IO) {
        for (candidateName in DriveConstants.ALL_ROOT_FOLDER_NAMES) {
            val safeCandidate = escapeDriveQuery(candidateName)
            val query = "name = '$safeCandidate' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode("files(id, name)", "UTF-8")}"

            try {
                val json = executeGetJson(url, email)
                val filesArr = json.getAsJsonArray("files")
                if (filesArr != null && filesArr.size() > 0) {
                    val folderId = filesArr[0].asJsonObject.get("id").asString
                    dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Pasta raiz '$candidateName' localizada no Google Drive para $email (ID: $folderId)")
                    return@withContext folderId
                }
            } catch (t: Throwable) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao buscar pasta '$candidateName' para $email: ${t.message}")
                throw t
            }
        }

        // Se não existir, cria a pasta oficial "ReSync"
        val createdId = try {
            executeCreateFolder(email, folderName, null)
        } catch (t: Throwable) {
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao criar pasta raiz '$folderName' para $email: ${t.message}")
            throw t
        }

        dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Pasta raiz '$folderName' criada com sucesso no Google Drive para $email (ID: $createdId)")
        createdId
    }

    data class ReSyncDriveHierarchy(
        val rootFolderId: String,
        val quickBackupsFolderId: String,
        val foldersFolderId: String,
        val filesFolderId: String
    )

    suspend fun getOrCreateReSyncStructure(email: String): ReSyncDriveHierarchy = withContext(Dispatchers.IO) {
        val rootId = getOrCreateRootFolder(email, DriveConstants.ROOT_FOLDER_NAME)
        val quickBackupsId = getOrCreateSubfolder(email, DriveConstants.SUBFOLDER_QUICK_BACKUPS, rootId)
        val foldersId = getOrCreateSubfolder(email, DriveConstants.SUBFOLDER_FOLDERS, rootId)
        val filesId = getOrCreateSubfolder(email, DriveConstants.SUBFOLDER_FILES, rootId)
        ReSyncDriveHierarchy(
            rootFolderId = rootId,
            quickBackupsFolderId = quickBackupsId,
            foldersFolderId = foldersId,
            filesFolderId = filesId
        )
    }

    suspend fun getOrCreateSwiftVaultStructure(email: String): SwiftVaultDriveHierarchy = getOrCreateReSyncStructure(email)

    suspend fun getOrCreateSubfolder(email: String, subfolderName: String, parentId: String): String = withContext(Dispatchers.IO) {
        val pathKey = "$email::$parentId::$subfolderName"

        folderIdCache[pathKey]?.let { cachedId ->
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$subfolderName' resolvida (id: $cachedId, pai: $parentId)")
            return@withContext cachedId
        }

        val mutex = getFolderMutex(pathKey)
        mutex.withLock {
            folderIdCache[pathKey]?.let { cachedId ->
                dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$subfolderName' resolvida (id: $cachedId, pai: $parentId)")
                return@withContext cachedId
            }

            val safeName = escapeDriveQuery(subfolderName)
            val query = "name = '$safeName' and '$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode("files(id, name)", "UTF-8")}"

            var existingId: String? = null
            var lastError: Throwable? = null

            for (attempt in 0..3) {
                try {
                    val json = executeGetJson(url, email)
                    val filesArr = json.getAsJsonArray("files")
                    if (filesArr != null && filesArr.size() > 0) {
                        existingId = filesArr[0].asJsonObject.get("id").asString
                    }
                    lastError = null
                    break
                } catch (t: Throwable) {
                    lastError = t
                    val msg = t.message ?: ""
                    val isRetriable = msg.contains("429") || msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") || t is java.io.IOException
                    if (isRetriable && attempt < 3) {
                        val delayMs = (1000L * (1 shl attempt)) + Random.nextLong(100L, 500L)
                        dbHelper.insertLog(LogLevel.WARN, "GoogleDrive", "Tentativa ${attempt + 1}/3 ao buscar subpasta '$subfolderName' falhou (${t.message}). Aguardando ${delayMs}ms com backoff...")
                        delay(delayMs)
                    } else {
                        break
                    }
                }
            }

            if (lastError != null) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao buscar subpasta '$subfolderName' para $email: ${lastError?.message}")
                throw lastError!!
            }

            if (existingId != null) {
                folderIdCache[pathKey] = existingId
                dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$subfolderName' resolvida (id: $existingId, pai: $parentId)")
                return@withContext existingId
            }

            val createdId = try {
                executeCreateFolder(email, subfolderName, parentId)
            } catch (t: Throwable) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao criar subpasta '$subfolderName' para $email: ${t.message}")
                throw t
            }

            folderIdCache[pathKey] = createdId
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$subfolderName' resolvida (id: $createdId, pai: $parentId)")
            createdId
        }
    }

    suspend fun findFileInFolder(email: String, fileName: String, parentId: String): DriveFileMetadata? = withContext(Dispatchers.IO) {
        val safeName = escapeDriveQuery(fileName)
        val query = "name = '$safeName' and '$parentId' in parents and mimeType != 'application/vnd.google-apps.folder' and trashed = false"
        val fields = "files(id, name, size, modifiedTime, createdTime, md5Checksum, mimeType, appProperties)"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode(fields, "UTF-8")}"

        try {
            val json = executeGetJson(url, email)
            val filesArr = json.getAsJsonArray("files")
            if (filesArr != null && filesArr.size() > 0) {
                parseDriveFileMetadata(filesArr[0].asJsonObject)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun findFileByName(email: String, fileName: String): DriveFileMetadata? = withContext(Dispatchers.IO) {
        val safeName = escapeDriveQuery(fileName)
        val query = "name = '$safeName' and trashed = false"
        val fields = "files(id, name, size, modifiedTime, createdTime, md5Checksum, mimeType, appProperties)"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode(fields, "UTF-8")}"

        try {
            val json = executeGetJson(url, email)
            val filesArr = json.getAsJsonArray("files")
            if (filesArr != null && filesArr.size() > 0) {
                parseDriveFileMetadata(filesArr[0].asJsonObject)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun listBackups(email: String): List<DriveFileMetadata> = withContext(Dispatchers.IO) {
        val allFiles = mutableListOf<DriveFileMetadata>()
        val seenFileIds = mutableSetOf<String>()

        val foldersToScan = mutableSetOf<String>()
        for (name in DriveConstants.ALL_ROOT_FOLDER_NAMES) {
            val safeName = escapeDriveQuery(name)
            val query = "name = '$safeName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode("files(id, name)", "UTF-8")}"
            try {
                val json = executeGetJson(url, email)
                val filesArr = json.getAsJsonArray("files")
                filesArr?.forEach { element ->
                    val id = element.asJsonObject.get("id")?.asString
                    if (!id.isNullOrBlank()) {
                        foldersToScan.add(id)
                    }
                }
            } catch (_: Throwable) {}
        }

        if (foldersToScan.isEmpty()) {
            val struct = getOrCreateSwiftVaultStructure(email)
            foldersToScan.add(struct.rootFolderId)
            foldersToScan.add(struct.quickBackupsFolderId)
        } else {
            val subfolderQuery = foldersToScan.joinToString(" or ") { "'$it' in parents" } + " and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(subfolderQuery, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode("files(id, name)", "UTF-8")}"
            try {
                val subJson = executeGetJson(url, email)
                val subArr = subJson.getAsJsonArray("files")
                subArr?.forEach { element ->
                    val id = element.asJsonObject.get("id")?.asString
                    if (!id.isNullOrBlank()) {
                        foldersToScan.add(id)
                    }
                }
            } catch (_: Throwable) {}
        }

        for (folderId in foldersToScan) {
            val query = "'$folderId' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
            val fields = "files(id, name, size, modifiedTime, createdTime, md5Checksum, mimeType, appProperties)"
            val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode(fields, "UTF-8")}"

            val json = try {
                executeGetJson(url, email)
            } catch (_: Throwable) {
                continue
            }

            val filesArr = json.getAsJsonArray("files") ?: continue
            for (element in filesArr) {
                val fileMeta = parseDriveFileMetadata(element.asJsonObject)
                val name = fileMeta.name
                val isBackupFile = name.endsWith(".resync", ignoreCase = true) ||
                        name.endsWith(".svb", ignoreCase = true) ||
                        name.endsWith(".zip", ignoreCase = true) ||
                        name.contains("Odin", ignoreCase = true) ||
                        name.contains("SwiftVault", ignoreCase = true) ||
                        name.contains("ReSync", ignoreCase = true) ||
                        fileMeta.appProperties.containsKey("backupId")
                if (isBackupFile && seenFileIds.add(fileMeta.id)) {
                    allFiles.add(fileMeta)
                }
            }
        }

        allFiles
    }

    /**
     * Downloads a file from Google Drive to local destination using direct OkHttp3 streaming (256 KB buffer).
     */
    suspend fun downloadFile(email: String, fileId: String, destFile: File) = withContext(Dispatchers.IO) {
        var token = runCatching {
            val account = Account(email.trim(), "com.google")
            GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
        }.getOrThrow()

        val client = GDriveNetworkClient.client
        val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

        fun send(authToken: String): Boolean {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer $authToken")
                .header("Accept-Encoding", "identity")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw IllegalStateException("HTTP_401")
                if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
                val body = response.body ?: throw IllegalStateException("Empty response body")
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { fos ->
                    val buffer = ByteArray(256 * 1024)
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                    fos.flush()
                }
                return true
            }
        }

        try {
            send(token)
        } catch (e: Exception) {
            if (e.message?.contains("HTTP_401") == true) {
                invalidateAccountToken(email)
                val account = Account(email.trim(), "com.google")
                token = GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
                send(token)
            } else {
                throw e
            }
        }
    }

    /**
     * Baixa o conteúdo do índice complementar (.index.json) se existir no Drive.
     */
    suspend fun fetchCompanionIndexContent(email: String, indexFileName: String): String? = withContext(Dispatchers.IO) {
        val safeName = escapeDriveQuery(indexFileName)
        val query = "name = '$safeName' and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=${URLEncoder.encode("files(id)", "UTF-8")}"

        try {
            val json = executeGetJson(url, email)
            val filesArr = json.getAsJsonArray("files")
            val fileId = filesArr?.firstOrNull()?.asJsonObject?.get("id")?.asString ?: return@withContext null

            var token = runCatching {
                val account = Account(email.trim(), "com.google")
                GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
            }.getOrThrow()

            val client = GDriveNetworkClient.client
            val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

            fun send(authToken: String): String {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("Authorization", "Bearer $authToken")
                    .header("Accept-Encoding", "identity")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) throw IllegalStateException("HTTP_401")
                    if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
                    return response.body?.string().orEmpty()
                }
            }

            try {
                send(token)
            } catch (e: Exception) {
                if (e.message?.contains("HTTP_401") == true) {
                    invalidateAccountToken(email)
                    val account = Account(email.trim(), "com.google")
                    token = GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
                    send(token)
                } else {
                    throw e
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

typealias SwiftVaultDriveHierarchy = DriveRepository.ReSyncDriveHierarchy
