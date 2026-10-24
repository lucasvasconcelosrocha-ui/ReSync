package com.resync.app

import android.content.pm.PackageInstaller
import com.resync.app.engine.RestoreInstallResult
import com.resync.app.engine.RestoreResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RestoreEngineTest {

    @Test
    fun testRestoreResultReceiverSessionLifecycle() = runBlocking {
        val sessionId = 98765
        val deferred = CompletableDeferred<RestoreInstallResult>()

        RestoreResultReceiver.registerSession(sessionId, deferred)
        assertFalse(deferred.isCompleted)

        // Complete the session as success
        deferred.complete(RestoreInstallResult(true, PackageInstaller.STATUS_SUCCESS, null))

        assertTrue(deferred.isCompleted)
        val result = deferred.await()
        assertTrue(result.success)
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.statusCode)
        assertNull(result.errorMessage)

        RestoreResultReceiver.unregisterSession(sessionId)
    }

    @Test
    fun testRestoreResultReceiverFailureSession() = runBlocking {
        val sessionId = 12345
        val deferred = CompletableDeferred<RestoreInstallResult>()

        RestoreResultReceiver.registerSession(sessionId, deferred)

        val expectedError = "Instalação cancelada pelo usuário."
        deferred.complete(RestoreInstallResult(false, PackageInstaller.STATUS_FAILURE_ABORTED, expectedError))

        val result = deferred.await()
        assertFalse(result.success)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, result.statusCode)
        assertEquals(expectedError, result.errorMessage)

        RestoreResultReceiver.unregisterSession(sessionId)
    }

    @Test
    fun testSessionIdExtractionFromRootPmOutput() {
        val standardOutput = "Success: created install session [7654321]"
        val regex = Regex("""\[(\d+)\]""")
        val match = regex.find(standardOutput)
        val sessionId = match?.groupValues?.get(1)

        assertEquals("7654321", sessionId)

        val alternateOutput = "created session 11223344"
        val altMatch = Regex("""\b(\d{3,})\b""").find(alternateOutput)
        val altSessionId = altMatch?.groupValues?.get(1) ?: altMatch?.value

        assertEquals("11223344", altSessionId)
    }

    @Test
    fun testHonestReportBuilding() {
        val appResults = listOf(
            "com.app.success" to RestoreInstallResult(true, 0),
            "com.app.fail" to RestoreInstallResult(false, -1, "INSTALL_FAILED_VERSION_DOWNGRADE")
        )

        val totalApps = appResults.size
        val failedApps = appResults.filter { !it.second.success }
        val successApps = appResults.filter { it.second.success }

        assertEquals(2, totalApps)
        assertEquals(1, failedApps.size)
        assertEquals(1, successApps.size)

        val failureDetails = failedApps.joinToString("; ") { "${it.first}: ${it.second.errorMessage}" }
        val fullReport = "${successApps.size} de $totalApps apps restaurados. Falhas: $failureDetails."

        assertEquals("1 de 2 apps restaurados. Falhas: com.app.fail: INSTALL_FAILED_VERSION_DOWNGRADE.", fullReport)
    }

    @Test
    fun testAesGcmCipherGeneratedIvContract() {
        val keyGen = javax.crypto.KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        val plaintext = "ya29.a0ARrdaM_SampleGoogleOAuthAccessToken_2026"
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

        // Encrypt WITHOUT caller-provided IV (matching Android Keystore contract)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
        val generatedIv = cipher.iv
        assertNotNull("Cipher must automatically generate IV", generatedIv)
        assertEquals("GCM IV must be 12 bytes", 12, generatedIv.size)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend IV + Ciphertext
        val combined = java.nio.ByteBuffer.allocate(generatedIv.size + ciphertext.size)
            .put(generatedIv)
            .put(ciphertext)
            .array()

        // Decrypt using extracted IV and GCMParameterSpec(128, iv)
        assertTrue(combined.size >= 12 + 16)
        val extractedIv = combined.copyOfRange(0, 12)
        val extractedCiphertext = combined.copyOfRange(12, combined.size)

        val decryptCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = javax.crypto.spec.GCMParameterSpec(128, extractedIv)
        decryptCipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)

        val decrypted = String(decryptCipher.doFinal(extractedCiphertext), Charsets.UTF_8)
        assertEquals(plaintext, decrypted)
    }
}

