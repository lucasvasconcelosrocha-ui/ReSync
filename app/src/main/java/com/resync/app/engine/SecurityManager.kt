package com.resync.app.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enterprise-grade security manager for SwiftVault.
 * Implements AES-256-GCM encryption with Android Keystore or PBKDF2-derived password keys.
 * Performs streaming SHA-256 integrity calculation.
 */
object SecurityManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "SwiftVault_Master_Key"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val SALT_LENGTH_BYTES = 16
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    private val secureRandom = SecureRandom()

    /**
     * Compute SHA-256 hash of an InputStream in a streaming fashion with 1 MB buffer.
     */
    fun computeSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024) // 1 MB buffer
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute MD5 hash of a local File for Google Drive md5Checksum verification.
     */
    fun computeFileMd5(file: java.io.File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute SHA-256 of byte array
     */
    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute SHA-256 hash of a local File.
     * Uses native command-line sha256sum when file is large (> 20 MB) to keep CPU off the Java VM process,
     * with fallback to 1 MB buffered Java streaming.
     */
    fun computeFileSha256(file: java.io.File): String {
        if (!file.exists() || file.length() == 0L) return ""
        if (file.length() > 20L * 1024L * 1024L) {
            try {
                val cmd = com.topjohnwu.superuser.Shell.cmd("nice -n 19 ionice -c 3 sha256sum \"${file.absolutePath}\" 2>/dev/null || sha256sum \"${file.absolutePath}\"").exec()
                if (cmd.isSuccess && cmd.out.isNotEmpty()) {
                    val hash = cmd.out.first().trim().split("\\s+".toRegex()).firstOrNull()
                    if (!hash.isNullOrBlank() && hash.length == 64) {
                        return hash.lowercase()
                    }
                }
            } catch (_: Throwable) {}
        }
        return java.io.FileInputStream(file).use { computeSha256(it) }
    }

    /**
     * Get or create hardware-backed Master Key in Android KeyStore
     */
    fun getOrCreateHardwareKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_LENGTH_BITS)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Derive AES-256 SecretKey from user password using PBKDF2WithHmacSHA256
     */
    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Generate cryptographically secure random bytes
     */
    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Wrap an output stream with AES-256-GCM encryption.
     * Header format: [Salt: 16 bytes] + [IV: 12 bytes] + [Encrypted Stream + GCM Tag]
     */
    fun createEncryptingOutputStream(
        outputStream: OutputStream,
        password: String? = null
    ): Pair<OutputStream, ByteArray> {
        val salt = generateRandomBytes(SALT_LENGTH_BYTES)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)

        val iv = if (!password.isNullOrEmpty()) {
            val key = deriveKeyFromPassword(password.toCharArray(), salt)
            val generatedIv = generateRandomBytes(GCM_IV_LENGTH_BYTES)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, generatedIv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            generatedIv
        } else {
            val key = getOrCreateHardwareKey()
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv ?: throw IllegalStateException("Cipher do Android KeyStore não gerou o IV obrigatório.")
        }

        // Write salt and IV to beginning of stream
        outputStream.write(salt)
        outputStream.write(iv)

        val cipherStream = CipherOutputStream(outputStream, cipher)
        return Pair(cipherStream, iv)
    }

    /**
     * Legacy SVB1 container stream decryptor (AES-256-GCM stream wrapper).
     */
    fun createDecryptingInputStream(
        inputStream: InputStream,
        password: String? = null
    ): InputStream {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)

        val saltRead = inputStream.read(salt)
        val ivRead = inputStream.read(iv)

        if (saltRead != SALT_LENGTH_BYTES || ivRead != GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Arquivo corrompido: cabeçalho criptográfico incompleto.")
        }

        val secretKey = if (!password.isNullOrEmpty()) {
            deriveKeyFromPassword(password.toCharArray(), salt)
        } else {
            getOrCreateHardwareKey()
        }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return CipherInputStream(inputStream, cipher)
    }

    val SVBE_MAGIC = byteArrayOf(0x53, 0x56, 0x42, 0x45) // "SVBE"
    const val DEFAULT_CHUNK_SIZE = 64 * 1024

    /**
     * Encrypt an input stream into an output stream in 64 KiB chunks using AES-256-GCM.
     * Header format: [SVBE: 4 bytes] + [Salt: 16 bytes] + [ChunkSize: 4 bytes]
     * Chunks: [CiphertextLen: 4 bytes] + [IV: 12 bytes] + [Ciphertext + Tag: N bytes]
     * Terminator: [0: 4 bytes]
     */
    fun encryptStreamChunked(
        inputStream: InputStream,
        outputStream: OutputStream,
        password: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: ((bytesRead: Long) -> Unit)? = null
    ) {
        val salt = generateRandomBytes(SALT_LENGTH_BYTES)
        val secretKey = deriveKeyFromPassword(password.toCharArray(), salt)

        // Write header
        outputStream.write(SVBE_MAGIC)
        outputStream.write(salt)
        outputStream.write(java.nio.ByteBuffer.allocate(4).putInt(chunkSize).array())

        val buffer = ByteArray(chunkSize)
        var totalBytesRead = 0L

        while (true) {
            var bytesReadThisChunk = 0
            while (bytesReadThisChunk < chunkSize) {
                val r = inputStream.read(buffer, bytesReadThisChunk, chunkSize - bytesReadThisChunk)
                if (r == -1) break
                bytesReadThisChunk += r
            }

            if (bytesReadThisChunk == 0) break

            totalBytesRead += bytesReadThisChunk
            val iv = generateRandomBytes(GCM_IV_LENGTH_BYTES)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            val ciphertext = cipher.doFinal(buffer, 0, bytesReadThisChunk)

            // Write chunk header
            outputStream.write(java.nio.ByteBuffer.allocate(4).putInt(ciphertext.size).array())
            outputStream.write(iv)
            outputStream.write(ciphertext)

            onProgress?.invoke(totalBytesRead)
        }

        // Write end-of-stream indicator (0 length chunk)
        outputStream.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        outputStream.flush()
    }

    /**
     * Decrypt a chunked SVBE stream into the output stream using AES-256-GCM.
     */
    fun decryptStreamChunked(
        inputStream: InputStream,
        outputStream: OutputStream,
        password: String,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ) {
        val magic = ByteArray(4)
        val readMagic = inputStream.read(magic)
        if (readMagic != 4 || !magic.contentEquals(SVBE_MAGIC)) {
            throw IllegalArgumentException("Cabeçalho de criptografia por entrada (SVBE) inválido ou ausente.")
        }

        val salt = ByteArray(SALT_LENGTH_BYTES)
        val readSalt = inputStream.read(salt)
        if (readSalt != SALT_LENGTH_BYTES) {
            throw IllegalArgumentException("Salt da entrada criptografada corrompido.")
        }

        val secretKey = deriveKeyFromPassword(password.toCharArray(), salt)

        val chunkSizeBuf = ByteArray(4)
        inputStream.read(chunkSizeBuf)
        val chunkSize = java.nio.ByteBuffer.wrap(chunkSizeBuf).int

        val lenBuf = ByteArray(4)
        var totalWritten = 0L

        while (true) {
            val lenRead = inputStream.read(lenBuf)
            if (lenRead < 4) break
            val cipherLen = java.nio.ByteBuffer.wrap(lenBuf).int
            if (cipherLen == 0) break // EOF indicator

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            var ivReadTotal = 0
            while (ivReadTotal < GCM_IV_LENGTH_BYTES) {
                val r = inputStream.read(iv, ivReadTotal, GCM_IV_LENGTH_BYTES - ivReadTotal)
                if (r == -1) throw IllegalArgumentException("Fim de fluxo prematuro ao ler IV do chunk.")
                ivReadTotal += r
            }

            val cipherBytes = ByteArray(cipherLen)
            var cipherReadTotal = 0
            while (cipherReadTotal < cipherLen) {
                val r = inputStream.read(cipherBytes, cipherReadTotal, cipherLen - cipherReadTotal)
                if (r == -1) throw IllegalArgumentException("Fim de fluxo prematuro ao ler dados do chunk cifrado.")
                cipherReadTotal += r
            }

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plaintext = try {
                cipher.doFinal(cipherBytes)
            } catch (e: Exception) {
                throw IllegalArgumentException("Senha incorreta ou integridade do bloco violada: ${e.message}", e)
            }

            outputStream.write(plaintext)
            totalWritten += plaintext.size
            onProgress?.invoke(totalWritten)
        }

        outputStream.flush()
    }

    /**
     * Checks if an input stream starts with SVBE magic header without consuming it if it can be reset.
     */
    fun isChunkedEncryptedStream(bis: java.io.BufferedInputStream): Boolean {
        bis.mark(4)
        val magic = ByteArray(4)
        val r = bis.read(magic)
        bis.reset()
        return r == 4 && magic.contentEquals(SVBE_MAGIC)
    }

    /**
     * Encrypt arbitrary string using hardware-backed Android KeyStore AES-256-GCM.
     * With Android KeyStore keys, passing a caller-provided IV is prohibited when randomized
     * encryption is required. The cipher generates the IV on init(ENCRYPT_MODE, key) and we read cipher.iv.
     */
    fun encryptHardwareString(plaintext: String): String {
        val key = getOrCreateHardwareKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IllegalStateException("Cipher do Android KeyStore não gerou o IV obrigatório.")
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = java.nio.ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypt string using hardware-backed Android KeyStore AES-256-GCM.
     * Extracts the saved 12-byte IV and initializes the cipher with GCMParameterSpec(128, iv).
     */
    fun decryptHardwareString(base64Ciphertext: String): String {
        val combined = try {
            android.util.Base64.decode(base64Ciphertext, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            throw IllegalArgumentException("Formato Base64 inválido para token cifrado.", e)
        }
        val minRequiredSize = GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8) // 12 + 16 = 28 bytes
        if (combined.size < minRequiredSize) {
            throw IllegalArgumentException("Texto cifrado inválido ou incompleto para hardware Keystore (tamanho: ${combined.size} bytes).")
        }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val key = getOrCreateHardwareKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
}


