package com.resync.app.cloud.gdrive

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

data class DriveStorageQuota(
    val limitBytes: Long,
    val usageBytes: Long,
    val usageInDriveBytes: Long,
    val isStorageFull: Boolean,
    val userEmail: String?,
    val displayName: String? = null
) {
    val availableBytes: Long
        get() = maxOf(0L, limitBytes - usageBytes)
}

data class DriveFileMetadata(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val createdTime: Long,
    val modifiedTime: Long,
    val md5Checksum: String?,
    val mimeType: String,
    val appProperties: Map<String, String> = emptyMap()
)

data class BackupFolderHierarchy(
    val rootFolderId: String,
    val backupsFolderId: String,
    val appsFolderId: String,
    val filesFolderId: String,
    val systemFolderId: String,
    val dnsFolderId: String,
    val smsFolderId: String,
    val callsFolderId: String,
    val metadataFolderId: String
)

class InsufficientStorageException(msg: String) : Exception(msg)
class GoogleDriveApiException(val statusCode: Int, msg: String) : Exception(msg)

class DriveFileManager(private val oAuthManager: OAuthManager) {

    private val gson = Gson()

    companion object {
        private const val API_BASE = "https://www.googleapis.com/drive/v3"

        /**
         * Escapes single quotes and backslashes for Google Drive API v3 query terms (e.g. name = '...').
         */
        fun escapeDriveQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }

    private val folderMutexes = ConcurrentHashMap<String, Mutex>()
    private val folderIdCache = ConcurrentHashMap<String, String>()

    private fun getFolderMutex(pathKey: String): Mutex {
        return folderMutexes.computeIfAbsent(pathKey) { Mutex() }
    }

    /**
     * Inspect Google Drive personal storage quota
     */
    suspend fun getStorageQuota(accountEmail: String? = null): Result<DriveStorageQuota> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado no Google Drive.", 401))

        try {
            var quotaRes = fetchStorageQuotaInternal(token)
            if (quotaRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                oAuthManager.invalidateToken(token)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    quotaRes = fetchStorageQuotaInternal(token)
                    if (quotaRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                        oAuthManager.markSessionExpired(true)
                    }
                } else {
                    val ex = refreshed.exceptionOrNull()
                    if (ex is AuthIntentRequiredException) return@withContext Result.failure(ex)
                    oAuthManager.markSessionExpired(true)
                    return@withContext Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
                }
            }
            quotaRes
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchStorageQuotaInternal(token: String): Result<DriveStorageQuota> {
        val url = URL("$API_BASE/about?fields=storageQuota,user")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val code = conn.responseCode
        if (code == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val json = gson.fromJson(reader, JsonObject::class.java)

            val quotaObj = json.getAsJsonObject("storageQuota")
            val limit = quotaObj?.get("limit")?.asLong ?: (15L * 1024 * 1024 * 1024)
            val usage = quotaObj?.get("usage")?.asLong ?: 0L
            val usageInDrive = quotaObj?.get("usageInDrive")?.asLong ?: 0L

            val userObj = json.getAsJsonObject("user")
            val email = userObj?.get("emailAddress")?.asString
            val displayName = userObj?.get("displayName")?.asString

            val isFull = usage >= limit
            return Result.success(
                DriveStorageQuota(
                    limitBytes = limit,
                    usageBytes = usage,
                    usageInDriveBytes = usageInDrive,
                    isStorageFull = isFull,
                    userEmail = email,
                    displayName = displayName
                )
            )
        } else if (code == 401) {
            return Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
        } else {
            return handleApiError(conn, code)
        }
    }

    /**
     * Get or create the official single ReSync folder in Google Drive.
     * Reuses existing folder if already created, preventing duplicate folders.
     */
    suspend fun getOrCreateRootBackupFolder(accountEmail: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
        var token = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(
                GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401)
            )

        // 1. Check cached folder ID (only for default active account)
        val cachedId = if (accountEmail == null || accountEmail == oAuthManager.getConnectedAccountEmail()) oAuthManager.getCachedRootFolderId() else null
        if (!cachedId.isNullOrBlank()) {
            val valid = checkFolderValid(cachedId, token)
            if (valid.isSuccess && valid.getOrNull() == true) {
                dbHelper.insertLog(com.resync.app.data.model.LogLevel.INFO, "GoogleDrive", "Pasta raiz reutilizada do cache: 'ReSync' (ID: $cachedId)")
                return@withContext Result.success(cachedId)
            } else if (valid.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                oAuthManager.invalidateToken(token)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    val retryValid = checkFolderValid(cachedId, token)
                    if (retryValid.isSuccess && retryValid.getOrNull() == true) {
                        dbHelper.insertLog(com.resync.app.data.model.LogLevel.INFO, "GoogleDrive", "Pasta raiz reutilizada do cache: 'ReSync' (ID: $cachedId)")
                        return@withContext Result.success(cachedId)
                    }
                } else {
                    val ex = refreshed.exceptionOrNull()
                    if (ex is AuthIntentRequiredException) return@withContext Result.failure(ex)
                    oAuthManager.markSessionExpired(true)
                    return@withContext Result.failure(refreshed.exceptionOrNull() ?: GoogleDriveSessionExpiredException())
                }
            }
        }

        // 2. Search for existing "ReSync" or recognized legacy folders
        var resyncId: String? = null
        for (folderCandidate in DriveConstants.ALL_ROOT_FOLDER_NAMES) {
            val searchRes = searchFolderWithBackoff(folderCandidate, token, accountEmail = accountEmail)
            if (searchRes.isFailure) {
                val ex = searchRes.exceptionOrNull()
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao buscar pasta raiz '$folderCandidate': ${ex?.message}")
                return@withContext Result.failure(ex ?: Exception("Falha ao buscar pasta raiz '$folderCandidate'."))
            }
            val candidateId = searchRes.getOrNull()
            if (!candidateId.isNullOrBlank()) {
                resyncId = candidateId
                dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$folderCandidate' resolvida (id: $resyncId, pai: root)")
                break
            }
        }

        if (!resyncId.isNullOrBlank()) {
            if (accountEmail == null || accountEmail == oAuthManager.getConnectedAccountEmail()) {
                oAuthManager.setCachedRootFolderId(resyncId)
            }
            return@withContext Result.success(resyncId)
        }

        // 3. Create official "ReSync" folder if not found
        var createResult = createFolder(DriveConstants.ROOT_FOLDER_NAME, null, token)
        if (createResult.exceptionOrNull() is GoogleDriveSessionExpiredException) {
            oAuthManager.invalidateToken(token)
            val refreshed = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail)
            if (refreshed.isSuccess) {
                token = refreshed.getOrThrow()
                createResult = createFolder(DriveConstants.ROOT_FOLDER_NAME, null, token)
                if (createResult.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                    oAuthManager.markSessionExpired(true)
                }
            } else {
                val ex = refreshed.exceptionOrNull()
                if (ex is AuthIntentRequiredException) return@withContext Result.failure(ex)
                oAuthManager.markSessionExpired(true)
                return@withContext Result.failure(refreshed.exceptionOrNull() ?: GoogleDriveSessionExpiredException())
            }
        }

        val createdId = createResult.getOrNull()
        if (!createdId.isNullOrBlank()) {
            if (accountEmail == null || accountEmail == oAuthManager.getConnectedAccountEmail()) {
                oAuthManager.setCachedRootFolderId(createdId)
            }
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '${DriveConstants.ROOT_FOLDER_NAME}' resolvida (id: $createdId, pai: root)")
            return@withContext Result.success(createdId)
        } else {
            val err = createResult.exceptionOrNull() ?: Exception("Falha ao criar pasta '${DriveConstants.ROOT_FOLDER_NAME}' no Google Drive.")
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao criar pasta raiz '${DriveConstants.ROOT_FOLDER_NAME}': ${err.message}")
            return@withContext Result.failure(err)
        }
    }

    /**
     * Ensure the official ReSync folder structure exists in Google Drive
     * ReSync
     *  ├── Backups Rápidos
     *  ├── Pastas
     *  └── Arquivos
     */
    suspend fun ensureBackupHierarchy(accountEmail: String? = null): Result<BackupFolderHierarchy> = withContext(Dispatchers.IO) {
        val dbHelper = AppDatabaseHelper.getInstance(oAuthManager.context)
        val rootResult = getOrCreateRootBackupFolder(accountEmail)
        if (rootResult.isFailure) {
            val err = rootResult.exceptionOrNull() ?: Exception("Falha ao acessar pasta no Google Drive.")
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ensureBackupHierarchy falhou na raiz: ${err.message}")
            return@withContext Result.failure(err)
        }
        val rootId = rootResult.getOrThrow()

        val quickBackupsResult = getOrCreateSubfolder(DriveConstants.SUBFOLDER_QUICK_BACKUPS, rootId, accountEmail)
        if (quickBackupsResult.isFailure) {
            val err = quickBackupsResult.exceptionOrNull() ?: Exception("Falha ao resolver subpasta '${DriveConstants.SUBFOLDER_QUICK_BACKUPS}'.")
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ensureBackupHierarchy falhou em '${DriveConstants.SUBFOLDER_QUICK_BACKUPS}': ${err.message}")
            return@withContext Result.failure(err)
        }
        val quickBackupsId = quickBackupsResult.getOrThrow()

        val foldersResult = getOrCreateSubfolder(DriveConstants.SUBFOLDER_FOLDERS, rootId, accountEmail)
        if (foldersResult.isFailure) {
            val err = foldersResult.exceptionOrNull() ?: Exception("Falha ao resolver subpasta '${DriveConstants.SUBFOLDER_FOLDERS}'.")
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ensureBackupHierarchy falhou em '${DriveConstants.SUBFOLDER_FOLDERS}': ${err.message}")
            return@withContext Result.failure(err)
        }
        val foldersId = foldersResult.getOrThrow()

        val filesResult = getOrCreateSubfolder(DriveConstants.SUBFOLDER_FILES, rootId, accountEmail)
        if (filesResult.isFailure) {
            val err = filesResult.exceptionOrNull() ?: Exception("Falha ao resolver subpasta '${DriveConstants.SUBFOLDER_FILES}'.")
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "ensureBackupHierarchy falhou em '${DriveConstants.SUBFOLDER_FILES}': ${err.message}")
            return@withContext Result.failure(err)
        }
        val filesId = filesResult.getOrThrow()

        Result.success(
            BackupFolderHierarchy(
                rootFolderId = rootId,
                backupsFolderId = quickBackupsId,
                appsFolderId = quickBackupsId,
                filesFolderId = filesId,
                systemFolderId = quickBackupsId,
                dnsFolderId = quickBackupsId,
                smsFolderId = quickBackupsId,
                callsFolderId = quickBackupsId,
                metadataFolderId = quickBackupsId
            )
        )
    }

    /**
     * List all .svb backups in Google Drive
     */
    suspend fun listRemoteBackups(folderId: String? = null): Result<List<DriveFileMetadata>> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken()
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado.", 401))

        var actualFolderId = folderId
        if (actualFolderId == null) {
            actualFolderId = getOrCreateRootBackupFolder().getOrNull()
        }

        try {
            var listRes = fetchRemoteBackupsInternal(actualFolderId, token)
            if (listRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                oAuthManager.invalidateToken(token)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = token)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    listRes = fetchRemoteBackupsInternal(actualFolderId, token)
                    if (listRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                        oAuthManager.markSessionExpired(true)
                    }
                } else {
                    val ex = refreshed.exceptionOrNull()
                    if (ex is AuthIntentRequiredException) return@withContext Result.failure(ex)
                    oAuthManager.markSessionExpired(true)
                    return@withContext Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
                }
            }
            listRes
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchRemoteBackupsInternal(folderId: String?, token: String): Result<List<DriveFileMetadata>> {
        // Under drive.file scope, the app sees files it created.
        // We query non-trashed non-folder files that match backup extensions (.resync, .svb or .zip)
        val queryBuilder = StringBuilder("trashed = false and mimeType != 'application/vnd.google-apps.folder'")
        if (folderId != null) {
            queryBuilder.append(" and ('$folderId' in parents or name contains '.resync' or name contains '.svb' or name contains '.zip')")
        } else {
            queryBuilder.append(" and (name contains '.resync' or name contains '.svb' or name contains '.zip')")
        }

        val encodedQuery = URLEncoder.encode(queryBuilder.toString(), "UTF-8")
        val fields = URLEncoder.encode("files(id,name,size,createdTime,modifiedTime,md5Checksum,mimeType,appProperties)", "UTF-8")
        val url = URL("$API_BASE/files?q=$encodedQuery&fields=$fields&pageSize=100")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 12000
        conn.readTimeout = 12000

        val code = conn.responseCode
        if (code == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val json = gson.fromJson(reader, JsonObject::class.java)
            val filesArray = json.getAsJsonArray("files")

            val list = mutableListOf<DriveFileMetadata>()
            if (filesArray != null) {
                for (element in filesArray) {
                    val obj = element.asJsonObject
                    val name = obj.get("name")?.asString ?: ""
                    // Filter ReSync, SwiftVault and legacy Odin backup containers (.resync, .svb or .zip)
                    if (!name.endsWith(".resync", ignoreCase = true) && !name.endsWith(".svb", ignoreCase = true) && !name.endsWith(".zip", ignoreCase = true) && !name.contains("Odin", ignoreCase = true) && !name.contains("ReSync", ignoreCase = true) && !name.contains("SwiftVault", ignoreCase = true)) {
                        continue
                    }
                    val id = obj.get("id").asString
                    val size = obj.get("size")?.asLong ?: 0L
                    val md5 = obj.get("md5Checksum")?.asString
                    val mime = obj.get("mimeType")?.asString ?: "application/octet-stream"

                    val appProps = mutableMapOf<String, String>()
                    if (obj.has("appProperties") && !obj.get("appProperties").isJsonNull) {
                        val propsObj = obj.getAsJsonObject("appProperties")
                        for (key in propsObj.keySet()) {
                            appProps[key] = propsObj.get(key).asString
                        }
                    }

                    list.add(
                        DriveFileMetadata(
                            id = id,
                            name = name,
                            sizeBytes = size,
                            createdTime = System.currentTimeMillis(),
                            modifiedTime = System.currentTimeMillis(),
                            md5Checksum = md5,
                            mimeType = mime,
                            appProperties = appProps
                        )
                    )
                }
            }
            return Result.success(list)
        } else if (code == 401) {
            return Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
        } else {
            return handleApiError(conn, code)
        }
    }

    /**
     * Delete file from Google Drive
     */
    suspend fun deleteFile(fileId: String, accountEmail: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado no Google Drive.", 401))

        try {
            var res = deleteFileInternal(fileId, token)
            val initialEx = res.exceptionOrNull()
            if (initialEx is GoogleDriveSessionExpiredException || (initialEx is GoogleDriveApiException && (initialEx.statusCode == 401 || initialEx.statusCode == 403))) {
                oAuthManager.invalidateToken(token)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    res = deleteFileInternal(fileId, token)
                    if (res.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                        oAuthManager.markSessionExpired(true)
                    }
                } else {
                    val ex = refreshed.exceptionOrNull()
                    if (ex is AuthIntentRequiredException) return@withContext Result.failure(ex)
                    oAuthManager.markSessionExpired(true)
                    return@withContext Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
                }
            }
            res
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun deleteFileInternal(fileId: String, token: String): Result<Boolean> {
        val url = URL("$API_BASE/files/$fileId")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            return if (code == 204 || code == 200 || code == 404) {
                Result.success(true)
            } else if (code == 401 || code == 403) {
                Result.failure(GoogleDriveSessionExpiredException("Acesso expirado ou não autorizado no Google Drive (HTTP $code).", code))
            } else {
                handleApiError(conn, code)
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Updates appProperties for an existing file in Google Drive via PATCH request.
     */
    suspend fun updateFileAppProperties(fileId: String, properties: Map<String, String>, accountEmail: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado no Google Drive.", 401))

        try {
            var res = updateFileAppPropertiesInternal(fileId, properties, token)
            val initialEx = res.exceptionOrNull()
            if (initialEx is GoogleDriveSessionExpiredException || (initialEx is GoogleDriveApiException && (initialEx.statusCode == 401 || initialEx.statusCode == 403))) {
                oAuthManager.invalidateToken(token)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    res = updateFileAppPropertiesInternal(fileId, properties, token)
                }
            }
            res
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun updateFileAppPropertiesInternal(fileId: String, properties: Map<String, String>, token: String): Result<Boolean> {
        val url = URL("$API_BASE/files/$fileId")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val jsonBody = JsonObject().apply {
                val propsObj = JsonObject()
                for ((k, v) in properties) {
                    propsObj.addProperty(k, v)
                }
                add("appProperties", propsObj)
            }
            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            return if (code in 200..299) {
                Result.success(true)
            } else if (code == 401 || code == 403) {
                Result.failure(GoogleDriveSessionExpiredException("Acesso expirado ou não autorizado no Google Drive (HTTP $code).", code))
            } else {
                handleApiError(conn, code)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun checkFolderValid(folderId: String, token: String): Result<Boolean> {
        return try {
            val url = URL("$API_BASE/files/$folderId?fields=id,name,trashed,mimeType")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = gson.fromJson(reader, JsonObject::class.java)
                val trashed = json.get("trashed")?.asBoolean ?: false
                val mime = json.get("mimeType")?.asString ?: ""
                Result.success(!trashed && mime == "application/vnd.google-apps.folder")
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("Sessão expirada (HTTP 401)", 401))
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun searchFolderSingleAttempt(name: String, token: String, parentId: String? = null): Result<String?> {
        val dbHelper = AppDatabaseHelper.getInstance(oAuthManager.context)
        return try {
            val safeName = escapeDriveQuery(name)
            val query = if (parentId != null) {
                "name = '$safeName' and '$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            } else {
                "name = '$safeName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            }
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = URL("$API_BASE/files?q=$encodedQuery&fields=files(id,name)")
            val conn = searchUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = gson.fromJson(reader, JsonObject::class.java)
                val filesArray = json.getAsJsonArray("files")
                if (filesArray != null && filesArray.size() > 0) {
                    Result.success(filesArray.get(0).asJsonObject.get("id").asString)
                } else {
                    Result.success(null)
                }
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("Sessão expirada (HTTP 401)", 401))
            } else if (code in listOf(403, 429, 500, 502, 503, 504)) {
                val errBody = try {
                    BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
                } catch (_: Exception) { "" }
                dbHelper.insertLogAsync(LogLevel.ERROR, "GoogleDrive", "Erro transitório na busca da pasta '$name' (HTTP $code): $errBody")
                Result.failure(GoogleDriveApiException(code, "HTTP $code ao buscar pasta '$name': $errBody"))
            } else {
                handleApiError(conn, code)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchFolderWithBackoff(
        name: String,
        initialToken: String,
        parentId: String? = null,
        maxRetries: Int = 3,
        accountEmail: String? = null
    ): Result<String?> = withContext(Dispatchers.IO) {
        val dbHelper = AppDatabaseHelper.getInstance(oAuthManager.context)
        var currentToken = initialToken
        var lastError: Throwable? = null

        for (attempt in 0..maxRetries) {
            val res = searchFolderSingleAttempt(name, currentToken, parentId)
            if (res.isSuccess) {
                return@withContext res
            }

            val ex = res.exceptionOrNull()
            lastError = ex

            val is401 = ex is GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
            if (is401) {
                oAuthManager.invalidateToken(currentToken)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = currentToken, accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    currentToken = refreshed.getOrThrow()
                    continue
                } else {
                    return@withContext Result.failure(refreshed.exceptionOrNull() ?: ex ?: GoogleDriveSessionExpiredException())
                }
            }

            val isRetriable = (ex is GoogleDriveApiException && ex.statusCode in listOf(403, 429, 500, 502, 503, 504)) ||
                    ex is java.io.IOException ||
                    ex is java.net.SocketTimeoutException

            if (isRetriable && attempt < maxRetries) {
                val delayMs = (1000L * (1 shl attempt)) + Random.nextLong(100L, 500L)
                dbHelper.insertLogAsync(
                    LogLevel.WARN,
                    "GoogleDrive",
                    "Tentativa ${attempt + 1}/$maxRetries ao buscar pasta '$name' falhou (${ex?.message}). Aguardando ${delayMs}ms com backoff exponencial..."
                )
                delay(delayMs)
            } else {
                break
            }
        }

        val finalErr = lastError ?: Exception("Falha desconhecida ao buscar pasta '$name'")
        dbHelper.insertLogAsync(LogLevel.ERROR, "GoogleDrive", "searchFolder esgotou retries para '$name': ${finalErr.message}")
        Result.failure(finalErr)
    }

    suspend fun searchFolder(name: String, token: String, parentId: String? = null): Result<String?> {
        return searchFolderWithBackoff(name, token, parentId)
    }

    suspend fun getOrCreateSubfolder(name: String, parentId: String, accountEmail: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val dbHelper = AppDatabaseHelper.getInstance(oAuthManager.context)
        val pathKey = "${accountEmail.orEmpty()}::$parentId::$name"

        // 1. Fast cache check
        folderIdCache[pathKey]?.let { cachedId ->
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$name' resolvida (id: $cachedId, pai: $parentId)")
            return@withContext Result.success(cachedId)
        }

        val mutex = getFolderMutex(pathKey)
        mutex.withLock {
            // 2. Double-checked locking inside mutex
            folderIdCache[pathKey]?.let { cachedId ->
                dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$name' resolvida (id: $cachedId, pai: $parentId)")
                return@withContext Result.success(cachedId)
            }

            var token = oAuthManager.getValidAccessToken(accountEmail)
                ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou.", 401))

            // 3. Search folder with backoff before attempting to create
            var searchRes = searchFolderWithBackoff(name, token, parentId, accountEmail = accountEmail)
            if (searchRes.isFailure) {
                val err = searchRes.exceptionOrNull() ?: Exception("Falha ao buscar pasta '$name' no Google Drive.")
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha ao resolver pasta '$name': ${err.message}")
                return@withContext Result.failure(err)
            }

            val existingId = searchRes.getOrNull()
            if (!existingId.isNullOrBlank()) {
                folderIdCache[pathKey] = existingId
                dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$name' resolvida (id: $existingId, pai: $parentId)")
                return@withContext Result.success(existingId)
            }

            // 4. Folder strictly does not exist, create it
            var createRes = createFolder(name, parentId, token)
            if (createRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                oAuthManager.invalidateToken(token)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    createRes = createFolder(name, parentId, token)
                    if (createRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                        oAuthManager.markSessionExpired(true)
                    }
                } else {
                    val ex = refreshed.exceptionOrNull() ?: GoogleDriveSessionExpiredException()
                    dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro de autenticação ao criar pasta '$name': ${ex.message}")
                    return@withContext Result.failure(ex)
                }
            }

            val createdId = createRes.getOrNull()
            if (!createdId.isNullOrBlank()) {
                folderIdCache[pathKey] = createdId
                dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$name' resolvida (id: $createdId, pai: $parentId)")
                Result.success(createdId)
            } else {
                val err = createRes.exceptionOrNull() ?: Exception("Falha ao criar pasta '$name' no Google Drive.")
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao criar subpasta '$name' (Pai: $parentId): ${err.message}")
                Result.failure(err)
            }
        }
    }

    private fun createFolder(name: String, parentId: String?, token: String): Result<String> {
        val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
        return try {
            val createUrl = URL("$API_BASE/files")
            val createConn = createUrl.openConnection() as HttpURLConnection
            createConn.requestMethod = "POST"
            createConn.setRequestProperty("Authorization", "Bearer $token")
            createConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            createConn.connectTimeout = 12000
            createConn.readTimeout = 12000
            createConn.doOutput = true

            val body = JsonObject().apply {
                addProperty("name", name)
                addProperty("mimeType", "application/vnd.google-apps.folder")
                if (parentId != null) {
                    val parentsArr = com.google.gson.JsonArray().apply { add(parentId) }
                    add("parents", parentsArr)
                }
            }

            OutputStreamWriter(createConn.outputStream).use { it.write(body.toString()) }

            val code = createConn.responseCode
            if (code == 200 || code == 201) {
                val reader = BufferedReader(InputStreamReader(createConn.inputStream))
                val json = gson.fromJson(reader, JsonObject::class.java)
                val folderId = json.get("id").asString
                Result.success(folderId)
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
            } else {
                handleApiError(createConn, code)
            }
        } catch (e: Exception) {
            dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "Exceção ao criar pasta '$name': ${e.message}")
            Result.failure(e)
        }
    }

    private fun <T> handleApiError(conn: HttpURLConnection, code: Int): Result<T> {
        val errorBody = try {
            BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        val dbHelper = com.resync.app.data.database.AppDatabaseHelper.getInstance(oAuthManager.context)
        dbHelper.insertLogAsync(com.resync.app.data.model.LogLevel.ERROR, "GoogleDrive", "Erro na API do Google Drive (HTTP $code): $errorBody")

        if (code == 401) {
            return Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
        }

        if (code == 403 || code == 507) {
            if (errorBody.contains("storageQuotaExceeded", ignoreCase = true) || errorBody.contains("quota", ignoreCase = true)) {
                return Result.failure(InsufficientStorageException("Não há espaço suficiente no Google Drive para concluir este backup."))
            }
            if (errorBody.contains("insufficientFilePermissions", ignoreCase = true)) {
                return Result.failure(GoogleDriveApiException(code, "A conta Google não concedeu permissão de escrita."))
            }
        }

        return Result.failure(GoogleDriveApiException(code, "Erro na API do Google Drive (HTTP $code): $errorBody"))
    }

    /**
     * Query real metadata directly from Google Drive API to strictly verify existence, size, and MD5.
     */
    suspend fun getFileMetadata(fileId: String, accountEmail: String? = null): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado no Google Drive.", 401))

        try {
            var res = fetchFileMetadataInternal(fileId, token)
            if (res.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                oAuthManager.invalidateToken(token)
                val refreshed = oAuthManager.forceRefreshToken(currentToken = token, accountEmail = accountEmail)
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    res = fetchFileMetadataInternal(fileId, token)
                }
            }
            res
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchFileMetadataInternal(fileId: String, token: String): Result<DriveFileMetadata> {
        val fields = URLEncoder.encode("id,name,size,createdTime,modifiedTime,md5Checksum,mimeType,appProperties,parents,trashed", "UTF-8")
        val url = URL("$API_BASE/files/$fileId?fields=$fields")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 12000
        conn.readTimeout = 12000

        val code = conn.responseCode
        if (code in 200..299) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val obj = gson.fromJson(reader, JsonObject::class.java)
            val id = obj.get("id")?.asString ?: fileId
            val name = obj.get("name")?.asString ?: ""
            val size = obj.get("size")?.asLong ?: 0L
            val md5 = obj.get("md5Checksum")?.asString
            val mime = obj.get("mimeType")?.asString ?: "application/octet-stream"
            val trashed = obj.get("trashed")?.asBoolean ?: false
            if (trashed) {
                return Result.failure(IllegalStateException("Arquivo $id está na lixeira do Google Drive."))
            }
            val appProps = mutableMapOf<String, String>()
            if (obj.has("appProperties") && !obj.get("appProperties").isJsonNull) {
                val propsObj = obj.getAsJsonObject("appProperties")
                for (k in propsObj.keySet()) {
                    appProps[k] = propsObj.get(k).asString
                }
            }
            return Result.success(
                DriveFileMetadata(
                    id = id,
                    name = name,
                    sizeBytes = size,
                    createdTime = System.currentTimeMillis(),
                    modifiedTime = System.currentTimeMillis(),
                    md5Checksum = md5,
                    mimeType = mime,
                    appProperties = appProps
                )
            )
        } else if (code == 401) {
            return Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou.", 401))
        } else {
            return handleApiError(conn, code)
        }
    }

    suspend fun uploadIndexJson(
        name: String,
        content: String,
        parentId: String?,
        accountEmail: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = oAuthManager.getValidAccessToken(accountEmail)
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou.", 401))

        try {
            val existingId = searchFileByName(name, token, parentId).getOrNull()
            if (!existingId.isNullOrBlank()) {
                val url = URL("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(content) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Result.success(existingId)
                } else {
                    Result.failure(GoogleDriveApiException(code, "Falha ao atualizar índice: HTTP $code"))
                }
            } else {
                val boundary = "-------SwiftVaultBoundary" + System.currentTimeMillis()
                val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val metaObj = JsonObject().apply {
                    addProperty("name", name)
                    addProperty("mimeType", "application/json")
                    if (parentId != null) {
                        val arr = com.google.gson.JsonArray().apply { add(parentId) }
                        add("parents", arr)
                    }
                }

                val os = conn.outputStream
                val writer = OutputStreamWriter(os, Charsets.UTF_8)
                writer.write("--$boundary\r\n")
                writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                writer.write(metaObj.toString())
                writer.write("\r\n--$boundary\r\n")
                writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                writer.write(content)
                writer.write("\r\n--$boundary--\r\n")
                writer.flush()

                val code = conn.responseCode
                if (code in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val json = gson.fromJson(reader, JsonObject::class.java)
                    Result.success(json.get("id").asString)
                } else {
                    Result.failure(GoogleDriveApiException(code, "Falha ao criar índice: HTTP $code"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun searchFileByName(name: String, token: String, parentId: String? = null): Result<String?> {
        return try {
            val safeName = escapeDriveQuery(name)
            val query = if (parentId != null) {
                "name = '$safeName' and '$parentId' in parents and trashed = false"
            } else {
                "name = '$safeName' and trashed = false"
            }
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = URL("$API_BASE/files?q=$encodedQuery&fields=files(id,name)")
            val conn = searchUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = gson.fromJson(reader, JsonObject::class.java)
                val filesArray = json.getAsJsonArray("files")
                if (filesArray != null && filesArray.size() > 0) {
                    Result.success(filesArray.get(0).asJsonObject.get("id").asString)
                } else {
                    Result.success(null)
                }
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("Sessão expirada (HTTP 401)", 401))
            } else {
                handleApiError(conn, code)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

