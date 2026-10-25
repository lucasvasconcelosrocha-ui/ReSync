package com.resync.app.engine

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class PermissionsState(
    val isRootGranted: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val hasMediaImages: Boolean = false,
    val hasMediaVideo: Boolean = false,
    val hasMediaAudio: Boolean = false,
    val hasPostNotifications: Boolean = false,
    val hasContacts: Boolean = false,
    val hasSms: Boolean = false,
    val hasCalls: Boolean = false,
    val hasBluetoothConnect: Boolean = false,
    val hasWriteSettings: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val hasBackgroundDataExemption: Boolean = false,
    val persistedSafUrisCount: Int = 0,
    val hasUsageStats: Boolean = false,
    val hasAutostart: Boolean = false
) {
    val areAllRequiredGranted: Boolean
        get() = hasAllFilesAccess

    val areAllRuntimeGranted: Boolean
        get() = hasContacts && hasSms && hasCalls && hasPostNotifications
}

class PermissionsRepository(private val context: Context) {

    private val _permissionsState = MutableStateFlow(PermissionsState())
    val permissionsState: StateFlow<PermissionsState> = _permissionsState.asStateFlow()

    private fun checkPerm(perm: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Avaliação pura de permissões sem invocar 'su'. Rápida, síncrona e segura para chamadas frequentes.
     */
    fun evaluateSystemPermissions(currentRoot: Boolean = false): PermissionsState {
        return try {
            val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Environment.isExternalStorageManager()
                } catch (_: Throwable) {
                    false
                }
            } else {
                checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasImages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasVideo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasAudio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            }

            val hasContacts = checkPerm(Manifest.permission.READ_CONTACTS)
            val hasSms = checkPerm(Manifest.permission.READ_SMS)
            val hasCalls = checkPerm(Manifest.permission.READ_CALL_LOG)

            val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                true
            }

            val hasWrite = try {
                Settings.System.canWrite(context)
            } catch (_: Throwable) {
                false
            }

            val powerManager = try {
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            } catch (_: Throwable) {
                null
            }
            val isIgnoringBattery = try {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } catch (_: Throwable) {
                false
            }

            val safCount = try {
                context.contentResolver.persistedUriPermissions.size
            } catch (_: Throwable) {
                0
            }

            val appOps = try {
                context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            } catch (_: Throwable) {
                null
            }
            val hasUsage = try {
                if (appOps != null) {
                    @Suppress("DEPRECATION")
                    val mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                    mode == AppOpsManager.MODE_ALLOWED
                } else false
            } catch (_: Throwable) {
                false
            }

            val isXiaomi = Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                    Build.BRAND.contains("Xiaomi", ignoreCase = true) ||
                    Build.BRAND.contains("POCO", ignoreCase = true) ||
                    Build.BRAND.contains("Redmi", ignoreCase = true)

            val autostartPrefConfigured = try {
                val prefs = context.getSharedPreferences("resync_permissions_prefs", Context.MODE_PRIVATE)
                prefs.getBoolean("hyperos_autostart_configured", false)
            } catch (_: Throwable) {
                false
            }

            val miuiAutostart = if (isXiaomi && appOps != null) {
                try {
                    val checkOpMethod = appOps.javaClass.getMethod(
                        "checkOpNoThrow",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    val mode = checkOpMethod.invoke(appOps, 10008, Process.myUid(), context.packageName) as Int
                    mode == AppOpsManager.MODE_ALLOWED
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }

            val hasAutostart = miuiAutostart || (isIgnoringBattery && autostartPrefConfigured) || autostartPrefConfigured

            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val hasBgDataExemption = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && connMgr != null) {
                connMgr.restrictBackgroundStatus != android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            } else {
                true
            }

            PermissionsState(
                isRootGranted = currentRoot,
                hasAllFilesAccess = hasAllFiles,
                hasMediaImages = hasImages,
                hasMediaVideo = hasVideo,
                hasMediaAudio = hasAudio,
                hasPostNotifications = hasNotifications,
                hasContacts = hasContacts,
                hasSms = hasSms,
                hasCalls = hasCalls,
                hasBluetoothConnect = hasBluetooth,
                hasWriteSettings = hasWrite,
                isIgnoringBatteryOptimizations = isIgnoringBattery,
                hasBackgroundDataExemption = hasBgDataExemption,
                persistedSafUrisCount = safCount,
                hasUsageStats = hasUsage,
                hasAutostart = hasAutostart
            )
        } catch (_: Throwable) {
            PermissionsState(isRootGranted = currentRoot)
        }
    }

    /**
     * Define explicitamente que o Início Automático e Otimização de Bateria HyperOS foram configurados pelo usuário.
     */
    fun setHyperOsAutostartConfigured(configured: Boolean = true) {
        try {
            context.getSharedPreferences("resync_permissions_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("hyperos_autostart_configured", configured)
                .apply()
            refreshSystemPermissions()
        } catch (_: Throwable) {}
    }

    /**
     * Atualiza as permissões de sistema imediatamente (usado em callbacks de ActivityResult, onResume e polling leve).
     */
    fun refreshSystemPermissions() {
        val root = runCatching { _permissionsState.value.isRootGranted }.getOrDefault(false)
        _permissionsState.value = evaluateSystemPermissions(currentRoot = root)
    }

    /**
     * Checagem limpa e segura de root com timeout de 5 segundos.
     * Fecha shell em cache para evitar estados falsos congelados.
     */
    suspend fun checkRoot(forceNewShell: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(5000L) {
            try {
                if (forceNewShell) {
                    try {
                        Shell.getCachedShell()?.close()
                    } catch (_: Throwable) {}
                    try {
                        Shell.getShell()
                    } catch (_: Throwable) {}
                }
                CapabilityManager(context).verifyRootAccess(timeoutMs = 4000L)
            } catch (_: Throwable) {
                false
            }
        } ?: false

        updateRootGranted(result)
        result
    }

    fun updateRootGranted(isGranted: Boolean) {
        try {
            _permissionsState.value = _permissionsState.value.copy(isRootGranted = isGranted)
        } catch (_: Throwable) {}
    }

    init {
        try {
            _permissionsState.value = evaluateSystemPermissions(currentRoot = false)
        } catch (_: Throwable) {
            // Em caso de falha de qualquer API do Android, mantém PermissionsState() padrão e seguro
        }
    }

    companion object {
        @Volatile
        private var instance: PermissionsRepository? = null

        fun getInstance(context: Context): PermissionsRepository {
            return instance ?: synchronized(this) {
                instance ?: PermissionsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

