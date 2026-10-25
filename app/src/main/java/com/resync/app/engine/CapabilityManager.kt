package com.resync.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Honest capability manager that assesses device execution environments:
 * Standard Non-Root, Shizuku ADB privileged mode, and Authorized Root mode via Root Manager.
 * Strictly adheres to Android security rules without forging status.
 */
class CapabilityManager(private val context: Context) {

    enum class ExecutionMode {
        STANDARD,
        SHIZUKU,
        ROOT
    }

    enum class RootStatus {
        AUTHORIZED,        // Root ✓ Autorizado
        PERMISSION_NEEDED, // Root ⚠ Permissão necessária
        UNAUTHORIZED,      // Root ✕ Não autorizado
        NOT_INSTALLED      // Binário su / gerenciador não detectado
    }

    companion object {
        const val PKG_KERNELSU_NEXT = "com.rifsxd.ksunext"
        const val PKG_KERNELSU = "me.weishu.kernelsu"
        const val PKG_APATCH = "me.bmax.apatch"
        const val PKG_MAGISK = "com.topjohnwu.magisk"
    }

    /**
     * Identifies the primary root manager installed on device using RootManagers
     */
    fun getInstalledRootManagerPackage(): String? {
        val managers = RootManagers.detectInstalledManagers(context)
        return managers.firstOrNull()?.packageName
    }

    fun getInstalledRootManagers(): List<RootManagerApp> {
        return RootManagers.detectInstalledManagers(context)
    }

    fun isKernelSuNextInstalled(): Boolean {
        return getInstalledRootManagers().any { it.packageName == PKG_KERNELSU_NEXT }
    }

    fun getRootManagerLaunchIntent(): Intent? {
        val managers = getInstalledRootManagers()
        return managers.firstOrNull()?.launchIntent
    }

    /**
     * Check if su binary exists in any common system or vendor path
     */
    fun hasSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/apex/com.android.runtime/bin/su",
            "/system/bin/ksud"
        )
        for (path in paths) {
            try {
                if (java.io.File(path).exists()) return true
            } catch (_: Exception) {}
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if device has Superuser binary and if app was explicitly granted root access.
     */
    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        verifyRootAccess()
    }

    /**
     * Validação rigorosa e segura de privilégios de Root.
     * Testa Shell.isAppGrantedRoot(), comando direto "su -c id" (uid=0),
     * mount master "su -mm -c id" (KernelSU / Magisk) e Shell.cmd("id").
     */
    suspend fun verifyRootAccess(timeoutMs: Long = 4000L): Boolean = withContext(Dispatchers.IO) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            try {
                // 1. Verifica flag do libsu
                if (Shell.isAppGrantedRoot() == true) return@withTimeoutOrNull true

                // 2. Testa comando direto via Runtime "su -c id" (uid=0)
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    val line = p.inputStream.bufferedReader().readLine()
                    p.waitFor()
                    if (p.exitValue() == 0 && line != null && line.contains("uid=0")) {
                        return@withTimeoutOrNull true
                    }
                } catch (_: Throwable) {}

                // 3. Testa comando direto com mount master "su -mm -c id" (KernelSU / Magisk)
                try {
                    val pMm = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "id"))
                    val lineMm = pMm.inputStream.bufferedReader().readLine()
                    pMm.waitFor()
                    if (pMm.exitValue() == 0 && lineMm != null && lineMm.contains("uid=0")) {
                        return@withTimeoutOrNull true
                    }
                } catch (_: Throwable) {}

                // 4. Testa Shell libsu executando id
                try {
                    val res = Shell.cmd("id").exec()
                    if (res.isSuccess && res.out.any { it.contains("uid=0") }) {
                        return@withTimeoutOrNull true
                    }
                } catch (_: Throwable) {}

                false
            } catch (_: Throwable) {
                false
            }
        } ?: false
    }

    /**
     * Consulta o status do SELinux do dispositivo (Enforcing, Permissive ou Disabled).
     */
    fun getSelinuxStatus(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "getenforce 2>/dev/null || cat /sys/fs/selinux/enforce 2>/dev/null"))
            val out = p.inputStream.bufferedReader().readLine()?.trim() ?: ""
            p.waitFor()
            when (out) {
                "1", "Enforcing" -> "Enforcing"
                "0", "Permissive" -> "Permissive"
                "" -> "Desconhecido"
                else -> out
            }
        } catch (_: Throwable) {
            "Desconhecido"
        }
    }

    /**
     * Reliable root check verifying actual privileged execution
     */
    suspend fun checkRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        try {
            if (verifyRootAccess()) {
                return@withContext RootStatus.AUTHORIZED
            }

            val hasBinary = hasSuBinary()
            val hasManager = getInstalledRootManagers().isNotEmpty()

            if (hasBinary || hasManager) {
                return@withContext RootStatus.PERMISSION_NEEDED
            }

            return@withContext RootStatus.UNAUTHORIZED
        } catch (e: Exception) {
            RootStatus.UNAUTHORIZED
        }
    }

    suspend fun checkRootStatus(): RootStatus = checkRootAccess()

    /**
     * Request root access explicitly:
     * Closes any cached shell so a non-root cached shell is discarded,
     * calls Shell.getShell() in Dispatchers.IO to trigger the superuser prompt,
     * and evaluates Shell.isAppGrantedRoot().
     */
    suspend fun requestRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Fechar o shell em cache para não reutilizar um shell sem root
            try {
                Shell.getCachedShell()?.close()
            } catch (_: Throwable) {}

            // Criar um novo shell síncrono no background
            Shell.getShell()

            val granted = Shell.isAppGrantedRoot() == true
            if (granted) {
                return@withContext true
            }

            // Testar comando real id
            val res = Shell.cmd("id").exec()
            if (res.isSuccess && res.out.any { it.contains("uid=0") }) {
                return@withContext true
            }

            // Fallback direto via Runtime
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            p.exitValue() == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if Shizuku manager is installed on device
     */
    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Check if Shizuku service is running and accessible
     */
    fun isShizukuRunning(): Boolean {
        if (!isShizukuInstalled()) return false
        return try {
            val shizukuClass = Class.forName("moe.shizuku.api.ShizukuService")
            val getVersionMethod = shizukuClass.getMethod("getVersion")
            val version = getVersionMethod.invoke(null) as? Int ?: 0
            version > 0
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Check if All Files Access (MANAGE_EXTERNAL_STORAGE) is granted on Android 11+ / 17
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Determine current active execution mode
     */
    suspend fun getActiveMode(): ExecutionMode {
        return when {
            isRootGranted() -> ExecutionMode.ROOT
            isShizukuRunning() -> ExecutionMode.SHIZUKU
            else -> ExecutionMode.STANDARD
        }
    }

    /**
     * Returns an informative, honest message explaining limitations or requirements.
     */
    fun getRestrictionNotice(action: String, requiredMode: ExecutionMode): String {
        return when (requiredMode) {
            ExecutionMode.ROOT -> "Esta função requer autorização explícita no gerenciador de root para acessar $action."
            ExecutionMode.SHIZUKU -> "Esta função requer Shizuku ativo para realizar $action."
            ExecutionMode.STANDARD -> "Indisponível devido às restrições de segurança do Android."
        }
    }
}

