package com.resync.app.engine

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Validador de Integridade Criptográfica (SHA-256).
 * Valida containers de backup, arquivos individuais e streams em tempo real.
 * Garante que nenhum dado corrompido seja restaurado ou enviado ao Google Drive.
 */
object BackupIntegrityValidator {

    private const val TAG = "BackupIntegrityValidator"
    private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer

    /**
     * Calcula o hash SHA-256 de um arquivo de forma eficiente via streaming.
     */
    fun calculateFileSha256(file: File): String? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            FileInputStream(file).use { calculateStreamSha256(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao calcular hash SHA-256 de ${file.path}: ${e.message}")
            null
        }
    }

    /**
     * Calcula o hash SHA-256 de um InputStream sem carregar tudo em memória.
     */
    fun calculateStreamSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val hashBytes = digest.digest()
        return bytesToHex(hashBytes)
    }

    /**
     * Calcula o hash SHA-256 de um ByteArray em memória.
     */
    fun calculateBytesSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return bytesToHex(hashBytes)
    }

    /**
     * Valida se o hash SHA-256 calculado para um arquivo coincide com o esperado no manifesto.
     */
    fun verifyFileChecksum(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true // Sem hash gravado, não bloqueia
        val actualHash = calculateFileSha256(file) ?: return false
        return actualHash.equals(expectedSha256.trim(), ignoreCase = true)
    }

    /**
     * Converte array de bytes para representação hexadecimal em minúsculas.
     */
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
