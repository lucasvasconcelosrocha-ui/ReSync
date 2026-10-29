package com.resync.app.util

import java.io.File

/**
 * Utilitários de segurança cibernética: blindagem de comandos root contra Command Injection
 * e prevenção de ataques de Path Traversal (Zip Slip).
 */
object ShellSecurityUtils {
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

    /**
     * Envolve caminhos ou parâmetros em aspas simples seguras,
     * substituindo qualquer aspa simples interna por '\''.
     * Impede 100% ataques de Command Injection via nomes de pastas ou arquivos maliciosos.
     */
    fun shellEscape(path: String): String {
        return "'${path.replace("'", "'\\''")}'"
    }

    /**
     * Valida nomes de pacotes com regex estrito ^[a-zA-Z0-9._]+$
     * Lança IllegalArgumentException se o nome contiver caracteres proibidos.
     */
    fun validatePackageName(packageName: String): String {
        require(isValidPackageName(packageName)) {
            "Nome de pacote inválido ou potencialmente malicioso: $packageName"
        }
        return packageName
    }

    fun isValidPackageName(packageName: String): Boolean {
        return PACKAGE_NAME_REGEX.matches(packageName)
    }

    /**
     * Proteção contra Path Traversal (Zip Slip):
     * Verifica obrigatoriamente se destFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)
     * antes de escrever qualquer byte no disco.
     */
    fun assertSafePath(targetDir: File, destFile: File): File {
        val destCanonical = destFile.canonicalPath
        val targetCanonical = targetDir.canonicalPath
        if (!destCanonical.startsWith(targetCanonical + File.separator) && destCanonical != targetCanonical) {
            throw SecurityException("Tentativa de Path Traversal bloqueada: ${destFile.name} fora do diretório de destino $targetCanonical")
        }
        return destFile
    }

    fun isSafePath(targetDir: File, destFile: File): Boolean {
        return try {
            val destCanonical = destFile.canonicalPath
            val targetCanonical = targetDir.canonicalPath
            destCanonical.startsWith(targetCanonical + File.separator) || destCanonical == targetCanonical
        } catch (_: Throwable) {
            false
        }
    }
}

fun shellEscape(path: String): String = ShellSecurityUtils.shellEscape(path)
fun validatePackageName(pkg: String): String = ShellSecurityUtils.validatePackageName(pkg)
