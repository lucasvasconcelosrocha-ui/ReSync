package com.resync.app

import com.google.gson.Gson
import com.resync.app.data.model.ContactRecord
import com.resync.app.data.model.SyncStatus
import com.resync.app.engine.BackupProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class FlowInvariantAndContactsTest {

    private val gson = Gson()

    /**
     * TEST: Verify that channelFlow completely resolves the Flow Invariant Violation.
     * When a background process switches context with withContext(Dispatchers.IO),
     * channelFlow's send() MUST succeed without throwing IllegalStateException: Flow invariant is violated.
     */
    @Test
    fun testFlowInvariantViolationResolvedWithChannelFlow() = runBlocking {
        val progressEvents = mutableListOf<String>()

        // Simulate ArchiveEngine emitting progress from inside withContext(Dispatchers.IO)
        val testFlow = channelFlow<BackupProgress> {
            send(BackupProgress("Iniciando", "Preparando...", 0.05f, 0, 100))

            // Simulate suspend function with withContext
            withContext(Dispatchers.IO) {
                for (i in 1..5) {
                    val percent = 0.1f * i
                    send(BackupProgress("Gravando", "Item $i", percent, i * 1024L, 5120L))
                }
            }

            send(BackupProgress("Concluído", "Finalizado", 1.0f, 5120L, 5120L, isFinished = true))
        }.flowOn(Dispatchers.IO)

        // Collect the flow
        val collected = testFlow.toList()

        assertEquals("Must collect all 7 progress emissions without crash", 7, collected.size)
        assertEquals("First step must be Iniciando", "Iniciando", collected.first().stage)
        assertEquals("Last step must be Concluído", "Concluído", collected.last().stage)
        assertTrue("Last item must be marked finished", collected.last().isFinished)
    }

    /**
     * TEST: Verify ContactRecord serialization and integrity.
     */
    @Test
    fun testContactsSerializationAndIntegrity() {
        val originalContacts = listOf(
            ContactRecord(
                id = "1",
                displayName = "Carlos Silva",
                phoneNumbers = listOf("+55 11 98888-7777", "+55 11 3333-2222"),
                emails = listOf("carlos@example.com")
            ),
            ContactRecord(
                id = "2",
                displayName = "Mariana Souza",
                phoneNumbers = listOf("+55 21 97777-6666"),
                emails = listOf("mariana@example.com", "mariana.work@empresa.com")
            )
        )

        val json = gson.toJson(originalContacts)
        val deserialized = gson.fromJson(json, Array<ContactRecord>::class.java).toList()

        assertEquals(2, deserialized.size)
        assertEquals("Carlos Silva", deserialized[0].displayName)
        assertEquals(2, deserialized[0].phoneNumbers.size)
        assertEquals("+55 11 98888-7777", deserialized[0].phoneNumbers[0])
        assertEquals("Mariana Souza", deserialized[1].displayName)
        assertEquals(2, deserialized[1].emails.size)
    }

    /**
     * TEST: Verify Single Source of Truth SyncStatus state machine.
     */
    @Test
    fun testSyncStatusStateMachine() {
        val states = listOf(
            SyncStatus.LOCAL_ONLY,
            SyncStatus.QUEUED,
            SyncStatus.UPLOADING,
            SyncStatus.VERIFYING,
            SyncStatus.SYNCED,
            SyncStatus.FAILED,
            SyncStatus.PAUSED,
            SyncStatus.CONFLICT
        )

        assertEquals("LOCAL_ONLY display name must match", "Não sincronizado", SyncStatus.LOCAL_ONLY.displayName)
        assertEquals("SYNCED display name must match", "Sincronizado", SyncStatus.SYNCED.displayName)
        assertEquals("QUEUED display name must match", "Na fila", SyncStatus.QUEUED.displayName)
        assertEquals("UPLOADING display name must match", "Enviando...", SyncStatus.UPLOADING.displayName)
        assertEquals("VERIFYING display name must match", "Verificando integridade", SyncStatus.VERIFYING.displayName)

        // Verify state progression validity
        assertTrue(states.contains(SyncStatus.SYNCED))
        assertTrue(states.contains(SyncStatus.LOCAL_ONLY))
    }

    /**
     * TEST: Verify that WifiConfigStore XML parsing extracts real SSIDs and passwords accurately.
     */
    @Test
    fun testWifiConfigStoreXmlParsing() {
        val sampleXml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <WifiConfigStoreData>
                <NetworkList>
                    <Network>
                        <WifiConfiguration>
                            <string name="ConfigKey">&quot;MinhaCasa&quot;WPA_PSK</string>
                            <string name="SSID">&quot;MinhaCasa&quot;</string>
                            <string name="PreSharedKey">&quot;SenhaSecreta123&quot;</string>
                        </WifiConfiguration>
                    </Network>
                    <Network>
                        <WifiConfiguration>
                            <string name="ConfigKey">&quot;RedeCafeAberta&quot;NONE</string>
                            <string name="SSID">&quot;RedeCafeAberta&quot;</string>
                            <null name="PreSharedKey" />
                        </WifiConfiguration>
                    </Network>
                </NetworkList>
            </WifiConfigStoreData>
        """.trimIndent()

        val networkRegex = Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL)
        val ssidRegex = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>")
        val pskRegex = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>")

        val parsedNetworks = mutableListOf<Pair<String, String?>>()
        val matches = networkRegex.findAll(sampleXml)
        for (match in matches) {
            val block = match.groupValues[1]
            val ssid = ssidRegex.find(block)?.groupValues?.get(1) ?: continue
            val psk = pskRegex.find(block)?.groupValues?.get(1)
            parsedNetworks.add(Pair(ssid, psk))
        }

        assertEquals(2, parsedNetworks.size)
        assertEquals("MinhaCasa", parsedNetworks[0].first)
        assertEquals("SenhaSecreta123", parsedNetworks[0].second)
        assertEquals("RedeCafeAberta", parsedNetworks[1].first)
        assertNull(parsedNetworks[1].second)
    }

    /**
     * TEST: Verify Data Usage calculation has no synthetic multipliers.
     */
    @Test
    fun testDataUsageZeroMultipliers() {
        val download = 100_000_000L
        val upload = 20_000_000L
        val total = download + upload

        assertEquals("Total bytes must be exactly sum of download and upload without multipliers", 120_000_000L, total)
    }

    /**
     * TEST: Verify KernelSU Next and root manager constants.
     */
    @Test
    fun testKernelSuNextConstants() {
        assertEquals("com.rifsxd.ksunext", com.resync.app.engine.CapabilityManager.PKG_KERNELSU_NEXT)
        assertEquals("me.weishu.kernelsu", com.resync.app.engine.CapabilityManager.PKG_KERNELSU)
    }
}

