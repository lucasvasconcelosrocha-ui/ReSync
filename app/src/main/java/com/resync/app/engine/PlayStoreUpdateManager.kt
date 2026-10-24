package com.resync.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlayStorePendingUpdate(
    val packageName: String,
    val appName: String,
    val installedVersionName: String,
    val installedVersionCode: Long,
    val targetVersionName: String,
    val targetVersionCode: Long,
    val updateSizeBytes: Long = 0L,
    val appIcon: Drawable? = null
)

sealed class PlayStoreUpdatesResult {
    object Idle : PlayStoreUpdatesResult()
    object Loading : PlayStoreUpdatesResult()
    data class Success(val updates: List<PlayStorePendingUpdate>) : PlayStoreUpdatesResult()
    data class Error(val message: String, val isRootMissing: Boolean = false) : PlayStoreUpdatesResult()
}

/**
 * Reads pending app updates directly from the official Google Play Store databases
 * located in /data/data/com.android.vending/databases/ using root access (libsu),
 * filtering strictly to applications whose installation source is com.android.vending.
 */
class PlayStoreUpdateManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _updatesState = MutableStateFlow<PlayStoreUpdatesResult>(PlayStoreUpdatesResult.Idle)
    val updatesState: StateFlow<PlayStoreUpdatesResult> = _updatesState.asStateFlow()

    private var isReceiverRegistered = false

    companion object {
        private const val TAG = "PlayStoreUpdateManager"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        @Volatile
        private var instance: PlayStoreUpdateManager? = null

        fun getInstance(context: Context): PlayStoreUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: PlayStoreUpdateManager(context.applicationContext).also {
                    instance = it
                    it.init()
                }
            }
        }

        fun isInstalledViaPlayStore(context: Context, packageName: String): Boolean {
            return try {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val info = pm.getInstallSourceInfo(packageName)
                    info.installingPackageName == PLAY_STORE_PACKAGE ||
                    info.initiatingPackageName == PLAY_STORE_PACKAGE
                } else {
                    @Suppress("DEPRECATION")
                    val installer = pm.getInstallerPackageName(packageName)
                    installer == PLAY_STORE_PACKAGE
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun init() {
        registerPackageChangeReceiver()
    }

    private fun registerPackageChangeReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    refreshUpdates()
                }
            }
            context.registerReceiver(receiver, filter)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register package change receiver: ${e.message}")
        }
    }

    fun refreshUpdates() {
        scope.launch {
            _updatesState.value = PlayStoreUpdatesResult.Loading
            val result = queryPlayStoreDatabases()
            _updatesState.value = result
        }
    }

    private suspend fun queryPlayStoreDatabases(): PlayStoreUpdatesResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        val hasRoot = try {
            Shell.isAppGrantedRoot() == true
        } catch (_: Exception) {
            false
        }

        if (!hasRoot) {
            return@withContext PlayStoreUpdatesResult.Error(
                message = "O acesso root é necessário para ler os bancos de dados locais da Google Play Store (/data/data/com.android.vending/databases/) e listar atualizações pendentes em tempo real.",
                isRootMissing = true
            )
        }

        val cacheDir = File(context.cacheDir, "playstore_inspect")
        cacheDir.mkdirs()

        try {
            // Copy Play Store local state database to temp cache directory to query without lock conflicts
            val copyCmd = "cp -f /data/data/com.android.vending/databases/localappstate.db* \"${cacheDir.absolutePath}/\" 2>/dev/null && chmod -R 777 \"${cacheDir.absolutePath}\""
            Shell.cmd(copyCmd).exec()

            val dbFile = File(cacheDir, "localappstate.db")
            if (!dbFile.exists() || dbFile.length() == 0L) {
                return@withContext PlayStoreUpdatesResult.Error(
                    message = "O banco de dados local da Play Store (localappstate.db) não foi localizado ou ainda não foi inicializado pelo sistema.",
                    isRootMissing = false
                )
            }

            val updatesFound = mutableListOf<PlayStorePendingUpdate>()
            var db: SQLiteDatabase? = null

            try {
                db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

                // Inspect tables
                val tables = mutableListOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        tables.add(cursor.getString(0))
                    }
                }

                // Check auto_update or appstate tables
                val targetTable = when {
                    tables.contains("auto_update") -> "auto_update"
                    tables.contains("appstate") -> "appstate"
                    tables.contains("packages") -> "packages"
                    else -> tables.firstOrNull { it.contains("update", ignoreCase = true) || it.contains("app", ignoreCase = true) }
                }

                if (targetTable != null) {
                    val columns = mutableListOf<String>()
                    db.rawQuery("PRAGMA table_info($targetTable)", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val colName = cursor.getString(1)
                            columns.add(colName)
                        }
                    }

                    val pkgCol = columns.firstOrNull { it.equals("package_name", ignoreCase = true) || it.equals("pkg_name", ignoreCase = true) || it.equals("packageName", ignoreCase = true) }
                    val targetVersionCol = columns.firstOrNull {
                        it.equals("desired_version", ignoreCase = true) ||
                        it.equals("desired_version_code", ignoreCase = true) ||
                        it.equals("target_version_code", ignoreCase = true) ||
                        it.equals("version_code", ignoreCase = true)
                    }
                    val targetVersionNameCol = columns.firstOrNull {
                        it.equals("desired_version_name", ignoreCase = true) ||
                        it.equals("target_version_name", ignoreCase = true) ||
                        it.equals("version_name", ignoreCase = true)
                    }
                    val sizeCol = columns.firstOrNull {
                        it.contains("size", ignoreCase = true) || it.contains("bytes", ignoreCase = true)
                    }

                    if (pkgCol != null) {
                        val query = "SELECT * FROM $targetTable"
                        db.rawQuery(query, null).use { cursor ->
                            val pkgIdx = cursor.getColumnIndex(pkgCol)
                            val vCodeIdx = targetVersionCol?.let { cursor.getColumnIndex(it) } ?: -1
                            val vNameIdx = targetVersionNameCol?.let { cursor.getColumnIndex(it) } ?: -1
                            val sizeIdx = sizeCol?.let { cursor.getColumnIndex(it) } ?: -1

                            while (cursor.moveToNext()) {
                                val pkgName = cursor.getString(pkgIdx) ?: continue
                                if (pkgName.isBlank() || pkgName == context.packageName) continue

                                // Verify installed and installer is Play Store
                                if (!isInstalledViaPlayStore(context, pkgName)) continue

                                val pkgInfo = try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        pm.getPackageInfo(pkgName, 0)
                                    }
                                } catch (_: Exception) {
                                    null
                                } ?: continue

                                val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    pkgInfo.longVersionCode
                                } else {
                                    @Suppress("DEPRECATION")
                                    pkgInfo.versionCode.toLong()
                                }

                                val targetCode = if (vCodeIdx >= 0) cursor.getLong(vCodeIdx) else 0L
                                val targetName = if (vNameIdx >= 0) cursor.getString(vNameIdx) ?: "" else ""
                                val updateSize = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L

                                // Only include if target version code is strictly greater than current
                                if (targetCode > currentCode) {
                                    val appName = try {
                                        pkgInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgName
                                    } catch (_: Exception) {
                                        pkgName
                                    }
                                    val icon = try {
                                        pkgInfo.applicationInfo?.loadIcon(pm)
                                    } catch (_: Exception) {
                                        null
                                    }

                                    updatesFound.add(
                                        PlayStorePendingUpdate(
                                            packageName = pkgName,
                                            appName = appName,
                                            installedVersionName = pkgInfo.versionName ?: "v$currentCode",
                                            installedVersionCode = currentCode,
                                            targetVersionName = if (targetName.isNotBlank()) targetName else "v$targetCode",
                                            targetVersionCode = targetCode,
                                            updateSizeBytes = updateSize,
                                            appIcon = icon
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                try {
                    db?.close()
                } catch (_: Exception) {}
                try {
                    dbFile.delete()
                    File(cacheDir, "localappstate.db-wal").delete()
                    File(cacheDir, "localappstate.db-shm").delete()
                } catch (_: Exception) {}
            }

            return@withContext PlayStoreUpdatesResult.Success(updatesFound)
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting Play Store database: ${e.message}", e)
            return@withContext PlayStoreUpdatesResult.Error(
                message = "Falha ao processar banco de dados da Play Store: ${e.message ?: "Erro desconhecido"}",
                isRootMissing = false
            )
        }
    }
}
