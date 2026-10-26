package com.resync.app.engine

import android.content.Context
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

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

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
    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_MILITARY_ITERATIONS): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        val tmp = factory.generateSecret(spec)
        val encoded = tmp.encoded
        val key = SecretKeySpec(encoded, "AES")
        zeroByteArray(encoded)
        return key
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

    val RESY_MAGIC = byteArrayOf(0x52, 0x45, 0x53, 0x59) // "RESY"
    val SVBE_MAGIC = byteArrayOf(0x53, 0x56, 0x42, 0x45) // "SVBE"
    val ZIP_PK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
    const val RESY_VERSION_V2: Byte = 0x02
    const val STRATEGY_ACCOUNT: Byte = 0x01
    const val STRATEGY_ADVANCED: Byte = 0x02
    const val PBKDF2_MILITARY_ITERATIONS = 600_000
    const val DEFAULT_CHUNK_SIZE = 64 * 1024

    fun zeroByteArray(array: ByteArray?) {
        if (array != null) java.util.Arrays.fill(array, 0.toByte())
    }

    fun zeroCharArray(array: CharArray?) {
        if (array != null) java.util.Arrays.fill(array, '\u0000')
    }

    fun deriveAccountKey(accountEmail: String, salt: ByteArray): SecretKey {
        val seed = "$accountEmail:ReSync_Master_Account_Salt".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(seed)
        digest.update(salt)
        val hash = digest.digest()
        val key = SecretKeySpec(hash, "AES")
        zeroByteArray(hash)
        return key
    }

    /**
     * Encrypt an input stream into an output stream using RESY AES-256-GCM streaming block format.
     * Header format:
     * [RESY_MAGIC: 4 bytes] + [Version: 1 byte (0x02)] + [Strategy: 1 byte] + [Iterations: 4 bytes] + [Salt: 16 bytes] + [ChunkSize: 4 bytes]
     * Chunks: [CiphertextLen: 4 bytes] + [IV: 12 bytes] + [Ciphertext + Tag: N bytes]
     * Terminator: [0: 4 bytes]
     */
    fun encryptStreamChunked(
        inputStream: InputStream,
        outputStream: OutputStream,
        password: String? = null,
        accountEmail: String? = null,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: ((bytesRead: Long) -> Unit)? = null
    ) {
        val salt = generateRandomBytes(SALT_LENGTH_BYTES)
        val isAdvanced = !password.isNullOrEmpty()
        val strategyByte = if (isAdvanced) STRATEGY_ADVANCED else STRATEGY_ACCOUNT
        val iterations = if (isAdvanced) PBKDF2_MILITARY_ITERATIONS else 1

        val secretKey = if (isAdvanced) {
            val passChars = password!!.toCharArray()
            val k = deriveKeyFromPassword(passChars, salt, iterations)
            zeroCharArray(passChars)
            k
        } else {
            val resolvedEmail = if (!accountEmail.isNullOrBlank()) {
                accountEmail.trim().lowercase()
            } else {
                try {
                    appContext?.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)
                        ?.getString("active_account_email", null)?.trim()?.lowercase()
                } catch (_: Throwable) { null }
            }
            val finalEmail = if (!resolvedEmail.isNullOrBlank()) resolvedEmail else "default_user@resync.local"
            deriveAccountKey(finalEmail, salt)
        }

        // Write RESY header
        outputStream.write(RESY_MAGIC)
        outputStream.write(byteArrayOf(RESY_VERSION_V2, strategyByte))
        outputStream.write(java.nio.ByteBuffer.allocate(4).putInt(iterations).array())
        outputStream.write(salt)
        outputStream.write(java.nio.ByteBuffer.allocate(4).putInt(chunkSize).array())

        val buffer = ByteArray(chunkSize)
        var totalBytesRead = 0L

        try {
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

                // Write chunk header: [CiphertextLen: 4 bytes] + [IV: 12 bytes] + [Ciphertext: N bytes]
                outputStream.write(java.nio.ByteBuffer.allocate(4).putInt(ciphertext.size).array())
                outputStream.write(iv)
                outputStream.write(ciphertext)

                onProgress?.invoke(totalBytesRead)
            }

            // Write end-of-stream indicator (0 length chunk)
            outputStream.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
            outputStream.flush()
        } finally {
            zeroByteArray(buffer)
        }
    }

    /**
     * Decrypt a chunked stream into the output stream.
     * Supports RESY, legacy SVBE, and unencrypted ZIP containers.
     */
    fun decryptStreamChunked(
        inputStream: InputStream,
        outputStream: OutputStream,
        password: String? = null,
        accountEmail: String? = null,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ) {
        val magic = ByteArray(4)
        var magicRead = 0
        while (magicRead < 4) {
            val r = inputStream.read(magic, magicRead, 4 - magicRead)
            if (r == -1) break
            magicRead += r
        }

        if (magicRead < 4) {
            throw IllegalArgumentException("Arquivo de backup corrompido ou incompleto.")
        }

        val isResy = magic.contentEquals(RESY_MAGIC)
        val isSvbe = magic.contentEquals(SVBE_MAGIC)

        if (!isResy && !isSvbe) {
            // Check if standard uncompressed/zip local file header (PK\x03\x04)
            if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) {
                outputStream.write(magic)
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (inputStream.read(buf).also { read = it } != -1) {
                    outputStream.write(buf, 0, read)
                }
                outputStream.flush()
                return
            }
            throw IllegalArgumentException("Formato de criptografia não reconhecido ou arquivo adulterado.")
        }

        var secretKey: SecretKey? = null
        val chunkSize: Int
        val candidateKeys = mutableListOf<SecretKey>()

        if (isResy) {
            val versionByte = inputStream.read()
            val strategyByte = inputStream.read()
            if (versionByte == -1 || strategyByte == -1) {
                throw IllegalArgumentException("Cabeçalho RESY incompleto.")
            }

            val iterBuf = ByteArray(4)
            val rIter = inputStream.read(iterBuf)
            if (rIter < 4) throw IllegalArgumentException("Arquivo de backup corrompido ou adulterado.")
            val iterations = java.nio.ByteBuffer.wrap(iterBuf).int.coerceAtLeast(1)

            val salt = ByteArray(SALT_LENGTH_BYTES)
            val saltRead = inputStream.read(salt)
            if (saltRead != SALT_LENGTH_BYTES) {
                throw IllegalArgumentException("Arquivo de backup corrompido ou adulterado.")
            }

            val chunkSizeBuf = ByteArray(4)
            val rChunk = inputStream.read(chunkSizeBuf)
            if (rChunk < 4) throw IllegalArgumentException("Arquivo de backup corrompido ou adulterado.")
            chunkSize = java.nio.ByteBuffer.wrap(chunkSizeBuf).int.coerceIn(1024, 1024 * 1024)

            if (strategyByte == STRATEGY_ADVANCED.toInt()) {
                val resolvedPass = if (!password.isNullOrEmpty()) {
                    password
                } else {
                    try {
                        val prefs = appContext?.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)
                        val enc = prefs?.getString("crypto_adv_pass", null)
                        enc?.let { runCatching { decryptHardwareString(it) }.getOrNull() }
                    } catch (_: Throwable) { null }
                }
                if (resolvedPass.isNullOrEmpty()) {
                    throw IllegalArgumentException("Senha incorreta: Este backup foi criptografado com senha personalizada.")
                }
                val passChars = resolvedPass.toCharArray()
                secretKey = deriveKeyFromPassword(passChars, salt, iterations)
                zeroCharArray(passChars)
            } else {
                // STRATEGY_ACCOUNT: build prioritized candidate keys
                val explicitEmail = accountEmail?.trim()?.lowercase()
                val prefsEmail = try {
                    appContext?.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)
                        ?.getString("active_account_email", null)?.trim()?.lowercase()
                } catch (_: Throwable) { null }

                if (!explicitEmail.isNullOrBlank()) {
                    candidateKeys.add(deriveAccountKey(explicitEmail, salt))
                }
                if (!prefsEmail.isNullOrBlank() && prefsEmail != explicitEmail) {
                    candidateKeys.add(deriveAccountKey(prefsEmail, salt))
                }
                candidateKeys.add(deriveAccountKey("default_user@resync.local", salt))
                try {
                    candidateKeys.add(getOrCreateHardwareKey())
                } catch (_: Throwable) {}
            }
        } else {
            // Legacy SVBE
            val salt = ByteArray(SALT_LENGTH_BYTES)
            val saltRead = inputStream.read(salt)
            if (saltRead != SALT_LENGTH_BYTES) {
                throw IllegalArgumentException("Arquivo de backup corrompido ou adulterado.")
            }

            val chunkSizeBuf = ByteArray(4)
            inputStream.read(chunkSizeBuf)
            chunkSize = java.nio.ByteBuffer.wrap(chunkSizeBuf).int

            if (password.isNullOrEmpty()) {
                throw IllegalArgumentException("Senha incorreta: Senha necessária para descriptografar.")
            }
            val passChars = password.toCharArray()
            secretKey = deriveKeyFromPassword(passChars, salt, 100_000)
            zeroCharArray(passChars)
        }

        val lenBuf = ByteArray(4)
        var totalWritten = 0L
        var isFirstChunk = true

        while (true) {
            var lenRead = 0
            while (lenRead < 4) {
                val r = inputStream.read(lenBuf, lenRead, 4 - lenRead)
                if (r == -1) break
                lenRead += r
            }
            if (lenRead < 4) break
            val cipherLen = java.nio.ByteBuffer.wrap(lenBuf).int
            if (cipherLen == 0) break // EOF indicator

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            var ivReadTotal = 0
            while (ivReadTotal < GCM_IV_LENGTH_BYTES) {
                val r = inputStream.read(iv, ivReadTotal, GCM_IV_LENGTH_BYTES - ivReadTotal)
                if (r == -1) throw IllegalArgumentException("Arquivo de backup corrompido ou adulterado.")
                ivReadTotal += r
            }

            val cipherBytes = ByteArray(cipherLen)
            var cipherReadTotal = 0
            while (cipherReadTotal < cipherLen) {
                val r = inputStream.read(cipherBytes, cipherReadTotal, cipherLen - cipherReadTotal)
                if (r == -1) throw IllegalArgumentException("Arquivo de backup corrompido ou adulterado.")
                cipherReadTotal += r
            }

            // Authenticate key on first chunk if using candidate keys
            if (isFirstChunk && secretKey == null) {
                var workingKey: SecretKey? = null
                var decryptedBytes: ByteArray? = null
                for (cand in candidateKeys) {
                    try {
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                        cipher.init(Cipher.DECRYPT_MODE, cand, spec)
                        val res = cipher.doFinal(cipherBytes)
                        workingKey = cand
                        decryptedBytes = res
                        break
                    } catch (_: Exception) {
                        // Candidate failed GCM tag verification, try next
                    }
                }
                if (workingKey == null || decryptedBytes == null) {
                    throw IllegalArgumentException("Falha na autenticação ou arquivo corrompido: chave incompatível.")
                }
                secretKey = workingKey
                isFirstChunk = false
                try {
                    outputStream.write(decryptedBytes)
                    totalWritten += decryptedBytes.size
                    onProgress?.invoke(totalWritten)
                } finally {
                    zeroByteArray(decryptedBytes)
                }
                continue
            }

            isFirstChunk = false
            val activeKey = secretKey ?: throw IllegalStateException("Chave de descriptografia indisponível.")

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, activeKey, spec)

            val plaintext = try {
                cipher.doFinal(cipherBytes)
            } catch (e: Exception) {
                throw IllegalArgumentException("Senha incorreta ou arquivo corrompido / adulterado.", e)
            }

            try {
                outputStream.write(plaintext)
                totalWritten += plaintext.size
                onProgress?.invoke(totalWritten)
            } finally {
                zeroByteArray(plaintext)
            }
        }

        outputStream.flush()
    }

    /**
     * Checks if an input stream starts with RESY or SVBE magic header without consuming it if it can be reset.
     */
    fun isChunkedEncryptedStream(bis: java.io.BufferedInputStream): Boolean {
        bis.mark(4)
        val magic = ByteArray(4)
        val r = bis.read(magic)
        bis.reset()
        return r == 4 && (magic.contentEquals(RESY_MAGIC) || magic.contentEquals(SVBE_MAGIC))
    }

    /**
     * Tests a passphrase by encrypting and decrypting a sample payload.
     */
    fun testPassphrase(passphrase: String): Boolean {
        return try {
            val testData = "RESY_CRYPTO_VERIFICATION_PAYLOAD".toByteArray(Charsets.UTF_8)
            val bos = java.io.ByteArrayOutputStream()
            encryptStreamChunked(java.io.ByteArrayInputStream(testData), bos, password = passphrase)
            val encryptedBytes = bos.toByteArray()
            val decryptedBos = java.io.ByteArrayOutputStream()
            decryptStreamChunked(java.io.ByteArrayInputStream(encryptedBytes), decryptedBos, password = passphrase)
            val result = decryptedBos.toString("UTF-8")
            result == "RESY_CRYPTO_VERIFICATION_PAYLOAD"
        } catch (_: Exception) {
            false
        }
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


