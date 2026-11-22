package com.resync.app.engine

import com.resync.app.util.AppLogger
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class RootExecResult(
    val isSuccess: Boolean,
    val isRoot: Boolean,
    val uid: String?,
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>
)

object RootUtils {

    private const val TAG = "RootUtils"

    /**
     * Executa comandos em shell com garantia estrita de privilégios de Root (uid=0).
     * 1. Elimina cache não-root do libsu caso Shell.getCachedShell() esteja preso em isRoot == false.
     * 2. Tenta execução via libsu confirmando previamente "id" com "uid=0".
     * 3. Fallback via ProcessBuilder("su", "-c", ...) ou ("su", "-mm", "-c", ...) validando "uid=0".
     * 4. Registra no AppLogger com transparência diagnóstica.
     */
    suspend fun executeRootScript(commands: List<String>): RootExecResult = withContext(Dispatchers.IO) {
        if (commands.isEmpty()) {
            return@withContext RootExecResult(
                isSuccess = true,
                isRoot = true,
                uid = "uid=0",
                exitCode = 0,
                stdout = emptyList(),
                stderr = emptyList()
            )
        }

        // 1. Limpeza de cache inválido do libsu
        try {
            val cached = Shell.getCachedShell()
            if (cached != null && (!cached.isRoot || !cached.isAlive)) {
                AppLogger.w(TAG, "Descartando shell em cache inválido/não-root do libsu.")
                cached.close()
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Erro ao fechar shell em cache: ${t.message}")
        }

        // 2. Tentativa via libsu
        try {
            val shell = Shell.getShell()
            if (shell.isRoot) {
                val idCheck = Shell.cmd("id").exec()
                val idOut = idCheck.out.joinToString(" ")
                if (idCheck.isSuccess && idOut.contains("uid=0")) {
                    AppLogger.i(TAG, "Executando script via libsu Shell (confirmado $idOut)...")
                    val cmdResult = Shell.cmd(*commands.toTypedArray()).exec()
                    val success = cmdResult.isSuccess
                    AppLogger.i(
                        TAG,
                        "libsu finalizado: code=${cmdResult.code}, success=$success, linhas out=${cmdResult.out.size}, err=${cmdResult.err.size}"
                    )
                    if (cmdResult.err.isNotEmpty()) {
                        AppLogger.w(TAG, "libsu stderr: ${cmdResult.err.take(5).joinToString("; ")}")
                    }
                    return@withContext RootExecResult(
                        isSuccess = success,
                        isRoot = true,
                        uid = idOut,
                        exitCode = cmdResult.code,
                        stdout = cmdResult.out,
                        stderr = cmdResult.err
                    )
                } else {
                    AppLogger.w(TAG, "libsu shell.isRoot é true mas 'id' não retornou uid=0: $idOut")
                }
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Falha na tentativa libsu: ${t.message}")
        }

        // 3. Fallback direto via ProcessBuilder com "su -c" ou "su -mm -c"
        val suVariants = listOf(
            listOf("su", "-c"),
            listOf("su", "-mm", "-c"),
            listOf("/system/bin/su", "-c"),
            listOf("/system/xbin/su", "-c")
        )

        for (variant in suVariants) {
            try {
                // Testa validação prévia de uid=0
                val idProcess = ProcessBuilder(variant + "id").start()
                val idOutput = BufferedReader(InputStreamReader(idProcess.inputStream)).use { it.readText() }.trim()
                idProcess.waitFor()

                if (idOutput.contains("uid=0")) {
                    AppLogger.i(TAG, "Executando script via ProcessBuilder (${variant.joinToString(" ")}): confirmado $idOutput")
                    val fullScript = commands.joinToString("\n")
                    val scriptProcess = ProcessBuilder(variant + fullScript).start()

                    val stdout = mutableListOf<String>()
                    val stderr = mutableListOf<String>()

                    val outReader = BufferedReader(InputStreamReader(scriptProcess.inputStream))
                    val errReader = BufferedReader(InputStreamReader(scriptProcess.errorStream))

                    var line: String?
                    while (outReader.readLine().also { line = it } != null) {
                        line?.let { stdout.add(it) }
                    }
                    while (errReader.readLine().also { line = it } != null) {
                        line?.let { stderr.add(it) }
                    }

                    val exitCode = scriptProcess.waitFor()
                    val isSuccess = exitCode == 0

                    AppLogger.i(
                        TAG,
                        "ProcessBuilder finalizado: code=$exitCode, outLinhas=${stdout.size}, errLinhas=${stderr.size}"
                    )
                    if (stderr.isNotEmpty()) {
                        AppLogger.w(TAG, "ProcessBuilder stderr: ${stderr.take(5).joinToString("; ")}")
                    }

                    return@withContext RootExecResult(
                        isSuccess = isSuccess,
                        isRoot = true,
                        uid = idOutput,
                        exitCode = exitCode,
                        stdout = stdout,
                        stderr = stderr
                    )
                }
            } catch (_: Throwable) {
                // Tenta próxima variante de su
            }
        }

        // 4. Falha: Nenhuma execução conseguiu confirmar uid=0
        AppLogger.w(TAG, "executeRootScript FALHOU: privilégios de Root (uid=0) NÃO puderam ser confirmados!")
        RootExecResult(
            isSuccess = false,
            isRoot = false,
            uid = null,
            exitCode = -1,
            stdout = emptyList(),
            stderr = listOf("Privilégios de Root (uid=0) não autorizados ou indisponíveis")
        )
    }
}
