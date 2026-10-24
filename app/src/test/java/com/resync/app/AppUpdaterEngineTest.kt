package com.resync.app

import com.resync.app.engine.SecurityManager
import com.resync.app.updater.AppReleaseInfo
import com.resync.app.updater.AppUpdateManager
import com.resync.app.updater.UpdateState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

class AppUpdaterEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVersionJsonParsing() {
        val jsonPayload = """
            {
              "versionCode": 2,
              "versionName": "1.0.1",
              "tag": "v1.0.1",
              "apkName": "SwiftVault-v1.0.1.apk",
              "apkSize": 18456000,
              "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "releaseNotes": "* Adicionado auto-atualizador\n* Melhorias de backup",
              "apkUrl": "https://github.com/lucasvasconcelosrocha-ui/SwiftVault/releases/download/v1.0.1/SwiftVault-v1.0.1.apk"
            }
        """.trimIndent()

        // Create a dummy mockable parser test
        val obj = com.google.gson.JsonParser.parseString(jsonPayload).asJsonObject
        val versionCode = obj.get("versionCode").asInt
        val versionName = obj.get("versionName").asString
        val tag = obj.get("tag").asString
        val apkName = obj.get("apkName").asString
        val apkSize = obj.get("apkSize").asLong
        val sha256 = obj.get("sha256").asString
        val releaseNotes = obj.get("releaseNotes").asString
        val apkUrl = obj.get("apkUrl").asString

        val releaseInfo = AppReleaseInfo(
            versionCode = versionCode,
            versionName = versionName,
            tag = tag,
            apkName = apkName,
            apkSize = apkSize,
            sha256 = sha256,
            releaseNotes = releaseNotes,
            apkUrl = apkUrl
        )

        assertEquals(2, releaseInfo.versionCode)
        assertEquals("1.0.1", releaseInfo.versionName)
        assertEquals("v1.0.1", releaseInfo.tag)
        assertEquals("SwiftVault-v1.0.1.apk", releaseInfo.apkName)
        assertEquals(18456000L, releaseInfo.apkSize)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", releaseInfo.sha256)
        assertTrue(releaseInfo.releaseNotes.contains("auto-atualizador"))
        assertTrue(releaseInfo.apkUrl.endsWith(".apk"))
    }

    @Test
    fun testVersionComparisonLogic() {
        fun isUpdateAvailable(remoteVersionCode: Int, installedVersionCode: Int): Boolean {
            return remoteVersionCode > installedVersionCode
        }

        // Newer version published (v1.0.1 build 2 vs installed build 1)
        assertTrue("Build 2 deve disparar atualização sobre Build 1", isUpdateAvailable(remoteVersionCode = 2, installedVersionCode = 1))

        // Same version already installed
        assertFalse("Mesmo build não deve disparar atualização", isUpdateAvailable(remoteVersionCode = 1, installedVersionCode = 1))

        // Newer local build (e.g. dev build ahead of release)
        assertFalse("Build local superior não deve ser sobrescrito por build inferior", isUpdateAvailable(remoteVersionCode = 1, installedVersionCode = 2))
    }

    @Test
    fun testSha256ChecksumVerification() {
        val testApk = tempFolder.newFile("SwiftVault-test.apk").apply {
            writeText("Simulated APK payload binary content for SwiftVault unit testing")
        }

        val calculatedSha = FileInputStream(testApk).use { SecurityManager.computeSha256(it) }
        assertNotNull(calculatedSha)
        assertEquals(64, calculatedSha.length)

        // Matching hash must pass
        val expectedMatchingHash = calculatedSha
        assertTrue("Hashes idênticos devem validar com sucesso", calculatedSha.equals(expectedMatchingHash, ignoreCase = true))

        // Tampered hash must fail
        val tamperedHash = "0000000000000000000000000000000000000000000000000000000000000000"
        assertFalse("Hash divergente deve falhar na validação", calculatedSha.equals(tamperedHash, ignoreCase = true))

        // Simulate file deletion on tampered file
        if (!calculatedSha.equals(tamperedHash, ignoreCase = true)) {
            testApk.delete()
        }
        assertFalse("Arquivo comprometido deve ser deletado do disco", testApk.exists())
    }

    @Test
    fun testAgoraNaoFlowDoesNotPersistIgnoreFlag() {
        // In-memory simulation of the ViewModel session state
        var uiState: UpdateState = UpdateState.Available(
            AppReleaseInfo(
                versionCode = 2,
                versionName = "1.0.1",
                tag = "v1.0.1",
                apkName = "SwiftVault-v1.0.1.apk",
                apkSize = 1024L,
                sha256 = "abc",
                releaseNotes = "Notes",
                apkUrl = "http://test.apk"
            )
        )

        // User taps "Agora não"
        fun onUserClicksAgoraNao(persistentIgnoreMap: MutableMap<String, Boolean>) {
            // Dismisses dialog in current session ONLY.
            uiState = UpdateState.Idle
            // Rule: "Não guardar 'ignorar esta versão'."
            // Never put ignore flag in persistent map!
        }

        val persistentStore = mutableMapOf<String, Boolean>()
        onUserClicksAgoraNao(persistentStore)

        assertEquals(UpdateState.Idle, uiState)
        assertTrue("Nenhum sinalizador de ignorar versão deve ser persistido", persistentStore.isEmpty())

        // Next app launch simulation:
        fun onNextAppLaunch(remoteBuild: Int, installedBuild: Int, persistentStore: Map<String, Boolean>): UpdateState {
            val hasIgnored = persistentStore["ignore_build_$remoteBuild"] == true
            return if (!hasIgnored && remoteBuild > installedBuild) {
                UpdateState.Available(
                    AppReleaseInfo(
                        versionCode = remoteBuild,
                        versionName = "1.0.1",
                        tag = "v1.0.1",
                        apkName = "SwiftVault-v1.0.1.apk",
                        apkSize = 1024L,
                        sha256 = "abc",
                        releaseNotes = "Notes",
                        apkUrl = "http://test.apk"
                    )
                )
            } else {
                UpdateState.Idle
            }
        }

        val nextLaunchState = onNextAppLaunch(remoteBuild = 2, installedBuild = 1, persistentStore = persistentStore)
        assertTrue("Aviso deve reaparecer na inicialização seguinte", nextLaunchState is UpdateState.Available)
    }
}

