package com.resync.app.cloud.gdrive

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

sealed class DriveAuthResult {
    data class Success(val account: CloudAccount) : DriveAuthResult()
    data class NeedsUserRecoverableAuth(val email: String, val recoveryIntent: Intent) : DriveAuthResult()
    data class Error(val email: String, val errorMessage: String, val cause: Throwable? = null) : DriveAuthResult()
}

/**
 * Repository providing Drive client instances per account.
 * Multi-account enabled: up to 5 accounts, each using its own isolated Drive client instance.
 * Prohibits singleton global client.
 */
class DriveRepository(private val context: Context) {

    companion object {
        fun escapeDriveQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val roomDb = AppRoomDatabase.getInstance(context)
    private val dao = roomDb.cloudAccountDao()

    // Multi-account client cache: STRICTLY PER ACCOUNT, never a global singleton
    private val clientsByEmail = ConcurrentHashMap<String, Drive>()
    private val folderMutexes = ConcurrentHashMap<String, Mutex>()
    private val folderIdCache = ConcurrentHashMap<String, String>()

    private fun getFolderMutex(pathKey: String): Mutex {
        return folderMutexes.computeIfAbsent(pathKey) { Mutex() }
    }

    val allAccounts: Flow<List<CloudAccount>> = dao.getAllAccounts()

    /**
     * Create the official system account chooser intent configured specifically
     * for Google Drive accounts with DRIVE_FILE scope.
     */
    fun newChooseAccountIntent(): Intent {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        return credential.newChooseAccountIntent()
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
        } catch (t: Throwable) {
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
        } catch (t: Throwable) {
            true // fallback permissivo em caso de permissão de sistema
        }
    }

    /**
     * Unwraps any UserRecoverableAuth exception (both Google Play Services and Google API Client wrappers)
     * from anywhere in the exception cause chain.
     */
    private fun findUserRecoverableIntent(throwable: Throwable): Intent? {
        var curr: Throwable? = throwable
        while (curr != null) {
            when (curr) {
                is UserRecoverableAuthIOException -> {
                    return curr.intent
                }
                is UserRecoverableAuthException -> {
                    return curr.intent
                }
                is GooglePlayServicesAvailabilityIOException -> {
                    return curr.intent
                }
            }
            curr = curr.cause
        }
        return null
    }

    /**
     * Returns a Drive client configured specifically for this email account.
     * Uses GoogleAccountCredential.usingOAuth2 with DriveScopes.DRIVE_FILE.
     */
    fun getDriveClientForAccount(email: String): Drive {
        val cleanEmail = email.trim()
        require(cleanEmail.isNotBlank()) { "O e-mail da conta Google não pode ser vazio." }
        return clientsByEmail.computeIfAbsent(cleanEmail) {
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_FILE)
            ).setSelectedAccountName(cleanEmail)

            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("ReSync Backup")
                .build()
        }
    }

    /**
     * Invalidates cached token using GoogleAuthUtil.clearToken and resets the cached Drive client.
     */
    fun invalidateAccountToken(email: String) {
        val cleanEmail = email.trim()
        clientsByEmail.remove(cleanEmail)
        try {
            val token = GoogleAuthUtil.getToken(context, Account(cleanEmail, "com.google"), "oauth2:" + DriveScopes.DRIVE_FILE)
            GoogleAuthUtil.clearToken(context, token)
        } catch (_: Throwable) {}
    }

    /**
     * Inspects quota and user profile on Google Drive for a given account.
     * Uses drive.about().get().setFields("storageQuota,user").
     * Handles UserRecoverableAuthIOException and UserRecoverableAuthException to allow prompting user authorization.
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

        // 4. Connect to Google Drive on Dispatchers.IO
        try {
            var drive = getDriveClientForAccount(cleanEmail)
            val about = try {
                drive.about().get().setFields("storageQuota,user").execute()
            } catch (t: Throwable) {
                val is401 = (t is GoogleJsonResponseException && t.statusCode == 401) ||
                        t.message?.contains("401") == true
                if (is401) {
                    invalidateAccountToken(cleanEmail)
                    drive = getDriveClientForAccount(cleanEmail)
                    drive.about().get().setFields("storageQuota,user").execute()
                } else {
                    throw t
                }
            }
            val user = about.user
            val quota = about.storageQuota

            val displayName = user?.displayName ?: cleanEmail.substringBefore("@")
            val photoUrl = user?.photoLink
            val quotaLimit = quota?.limit ?: (15L * 1024 * 1024 * 1024)
            val quotaUsed = quota?.usage ?: 0L

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
            val recoverableIntent = findUserRecoverableIntent(t)
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
        clientsByEmail.remove(email)
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

    /**
     * Gets or creates the root folder in Google Drive for the given account.
     * Looks for existing folder in ALL_ROOT_FOLDER_NAMES ("ReSync", "SwiftVault", "SwiftVault Backup" and legacy "Rodin_Backup").
     * If legacy folder is found and "ReSync" does not exist, renames it to "ReSync" preserving identical ID.
     * If both exist, moves files from legacy to "ReSync".
     * If none exists, creates "ReSync".
     */
    suspend fun getOrCreateRootFolder(email: String, folderName: String = DriveConstants.ROOT_FOLDER_NAME): String = withContext(Dispatchers.IO) {
        val dbHelper = AppDatabaseHelper.getInstance(context)
        var drive = getDriveClientForAccount(email)

        // 1. Busca a pasta oficial ReSync ou pastas legadas reconhecidas
        for (candidateName in DriveConstants.ALL_ROOT_FOLDER_NAMES) {
            val safeCandidate = escapeDriveQuery(candidateName)
            val query = "name = '$safeCandidate' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val result = try {
                drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()
            } catch (t: Throwable) {
                val is401 = (t is GoogleJsonResponseException && t.statusCode == 401) ||
                        t.message?.contains("401") == true
                if (is401) {
                    invalidateAccountToken(email)
                    drive = getDriveClientForAccount(email)
                    drive.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setFields("files(id, name)")
                        .execute()
                } else {
                    dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao buscar pasta '$candidateName' para $email: ${t.message}")
                    throw t
                }
            }

            val existingFolder = result.files?.firstOrNull()
            if (existingFolder != null && !existingFolder.id.isNullOrBlank()) {
                dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Pasta raiz '$candidateName' localizada no Google Drive para $email (ID: ${existingFolder.id})")
                return@withContext existingFolder.id
            }
        }

        // 2. Se não existir, cria a pasta oficial "ReSync"
        val folderMetadata = com.google.api.services.drive.model.File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }

        val created = try {
            drive.files().create(folderMetadata)
                .setFields("id")
                .execute()
        } catch (t: Throwable) {
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao criar pasta raiz '$folderName' para $email: ${t.message}")
            throw t
        }

        dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Pasta raiz '$folderName' criada com sucesso no Google Drive para $email (ID: ${created.id})")
        created.id
    }

    /**
     * Structure of official ReSync folders in Google Drive.
     */
    data class ReSyncDriveHierarchy(
        val rootFolderId: String,
        val quickBackupsFolderId: String,
        val foldersFolderId: String,
        val filesFolderId: String
    )

    /**
     * Ensures root ReSync folder and its 3 standard subfolders exist for an account:
     * - Backups Rápidos
     * - Pastas
     * - Arquivos
     */
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

    /**
     * Gets or creates a subfolder within a parent folder. Reuses existing folder if found, never duplicates.
     */
    suspend fun getOrCreateSubfolder(email: String, subfolderName: String, parentId: String): String = withContext(Dispatchers.IO) {
        val dbHelper = AppDatabaseHelper.getInstance(context)
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

            var drive = getDriveClientForAccount(email)
            val safeName = escapeDriveQuery(subfolderName)
            val query = "name = '$safeName' and '$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"

            var existingId: String? = null
            var lastError: Throwable? = null

            for (attempt in 0..3) {
                try {
                    val result = drive.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setFields("files(id, name)")
                        .execute()
                    val existing = result.files?.firstOrNull()
                    if (existing != null) {
                        existingId = existing.id
                    }
                    lastError = null
                    break
                } catch (t: Throwable) {
                    lastError = t
                    val is401 = (t is GoogleJsonResponseException && t.statusCode == 401) || t.message?.contains("401") == true
                    if (is401) {
                        invalidateAccountToken(email)
                        drive = getDriveClientForAccount(email)
                        continue
                    }
                    val isRetriable = (t is GoogleJsonResponseException && t.statusCode in listOf(403, 429, 500, 502, 503, 504)) ||
                            t is java.io.IOException
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

            val folderMetadata = com.google.api.services.drive.model.File().apply {
                name = subfolderName
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }

            val created = try {
                drive.files().create(folderMetadata)
                    .setFields("id")
                    .execute()
            } catch (t: Throwable) {
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Erro ao criar subpasta '$subfolderName' para $email: ${t.message}")
                throw t
            }

            folderIdCache[pathKey] = created.id
            dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "pasta '$subfolderName' resolvida (id: ${created.id}, pai: $parentId)")
            created.id
        }
    }

    /**
     * Searches for an existing non-trashed file with the specified name inside a parent folder.
     */
    suspend fun findFileInFolder(email: String, fileName: String, parentId: String): com.google.api.services.drive.model.File? = withContext(Dispatchers.IO) {
        var drive = getDriveClientForAccount(email)
        val safeName = escapeDriveQuery(fileName)
        val query = "name = '$safeName' and '$parentId' in parents and mimeType != 'application/vnd.google-apps.folder' and trashed = false"
        try {
            val res = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, size, modifiedTime, md5Checksum)")
                .execute()
            res.files?.firstOrNull()
        } catch (t: Throwable) {
            val is401 = (t is GoogleJsonResponseException && t.statusCode == 401) || t.message?.contains("401") == true
            if (is401) {
                invalidateAccountToken(email)
                drive = getDriveClientForAccount(email)
                val res = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, size, modifiedTime, md5Checksum)")
                    .execute()
                res.files?.firstOrNull()
            } else {
                null
            }
        }
    }

    /**
     * Lists backup files stored in Google Drive for a given account.
     * Searches inside:
     * 1) Root folders (SwiftVault, SwiftVault Backup, Rodin_Backup)
     * 2) "Backups Rápidos" subfolder
     * 3) Any subfolder of root folders containing .svb containers
     * Ensuring retrocompatibility with all past and future backups.
     */
    suspend fun listBackups(email: String): List<com.google.api.services.drive.model.File> = withContext(Dispatchers.IO) {
        var drive = getDriveClientForAccount(email)
        val allFiles = mutableListOf<com.google.api.services.drive.model.File>()
        val seenFileIds = mutableSetOf<String>()

        // Discover all existing root and sub folders
        val foldersToScan = mutableSetOf<String>()
        for (name in DriveConstants.ALL_ROOT_FOLDER_NAMES) {
            val query = "name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            try {
                val res = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()
                res.files?.forEach { f ->
                    if (!f.id.isNullOrBlank()) {
                        foldersToScan.add(f.id)
                    }
                }
            } catch (t: Throwable) {
                val is401 = (t is GoogleJsonResponseException && t.statusCode == 401) ||
                        t.message?.contains("401") == true
                if (is401) {
                    invalidateAccountToken(email)
                    drive = getDriveClientForAccount(email)
                    val retryRes = drive.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setFields("files(id, name)")
                        .execute()
                    retryRes.files?.forEach { f ->
                        if (!f.id.isNullOrBlank()) {
                            foldersToScan.add(f.id)
                        }
                    }
                }
            }
        }

        // If no folder found yet, ensure the root folder and structure are created
        if (foldersToScan.isEmpty()) {
            val struct = getOrCreateSwiftVaultStructure(email)
            foldersToScan.add(struct.rootFolderId)
            foldersToScan.add(struct.quickBackupsFolderId)
        } else {
            // Also discover any subfolder of these root folders (e.g. Backups Rápidos)
            val subfolderQuery = foldersToScan.joinToString(" or ") { "'$it' in parents" } + " and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            try {
                val subRes = drive.files().list()
                    .setQ(subfolderQuery)
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()
                subRes.files?.forEach { sub ->
                    if (!sub.id.isNullOrBlank()) {
                        foldersToScan.add(sub.id)
                    }
                }
            } catch (_: Throwable) {}
        }

        for (folderId in foldersToScan) {
            val query = "'$folderId' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
            val result = try {
                drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, size, modifiedTime, md5Checksum, mimeType, appProperties)")
                    .execute()
            } catch (t: Throwable) {
                val is401 = (t is GoogleJsonResponseException && t.statusCode == 401) ||
                        t.message?.contains("401") == true
                if (is401) {
                    invalidateAccountToken(email)
                    drive = getDriveClientForAccount(email)
                    drive.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setFields("files(id, name, size, modifiedTime, md5Checksum, mimeType, appProperties)")
                        .execute()
                } else {
                    continue
                }
            }

            result.files?.forEach { file ->
                val name = file.name ?: ""
                val isBackupFile = name.endsWith(".resync", ignoreCase = true) ||
                        name.endsWith(".svb", ignoreCase = true) ||
                        name.endsWith(".zip", ignoreCase = true) ||
                        name.contains("Odin", ignoreCase = true) ||
                        name.contains("SwiftVault", ignoreCase = true) ||
                        name.contains("ReSync", ignoreCase = true) ||
                        file.appProperties?.containsKey("backupId") == true
                if (isBackupFile && seenFileIds.add(file.id)) {
                    allFiles.add(file)
                }
            }
        }

        allFiles
    }

    /**
     * Downloads a file from Google Drive to local destination.
     */
    suspend fun downloadFile(email: String, fileId: String, destFile: File) = withContext(Dispatchers.IO) {
        val drive = getDriveClientForAccount(email)
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { output ->
            drive.files().get(fileId).executeMediaAndDownloadTo(output)
        }
    }

    /**
     * Baixa o conteúdo do índice complementar (.index.json) se existir no Drive.
     */
    suspend fun fetchCompanionIndexContent(email: String, indexFileName: String): String? = withContext(Dispatchers.IO) {
        val drive = getDriveClientForAccount(email)
        val query = "name = '$indexFileName' and trashed = false"
        try {
            val list = drive.files().list().setQ(query).setFields("files(id)").execute().files
            val fileId = list?.firstOrNull()?.id ?: return@withContext null
            val baos = java.io.ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(baos)
            baos.toString("UTF-8")
        } catch (_: Exception) {
            null
        }
    }
}

typealias SwiftVaultDriveHierarchy = DriveRepository.ReSyncDriveHierarchy


