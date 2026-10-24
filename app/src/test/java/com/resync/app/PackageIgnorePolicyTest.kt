package com.resync.app

import com.resync.app.data.model.CompletedItemInfo
import com.resync.app.engine.PackageIgnorePolicy
import org.junit.Assert.*
import org.junit.Test

class PackageIgnorePolicyTest {

    @Test
    fun testSdkSandboxIsIdentifiedAsInternalAndAlwaysIgnoredForData() {
        val pkg = "com.android.sdksandbox"
        assertTrue("SDK Sandbox deve ser reconhecido como pacote interno", PackageIgnorePolicy.isInternalSystemPackage(pkg))
        assertTrue("SDK Sandbox nunca deve ter backup de dados", PackageIgnorePolicy.isAlwaysIgnoredForData(pkg))
        assertTrue("SDK Sandbox vazio deve ser ignorado", PackageIgnorePolicy.shouldIgnoreIfEmptyData(pkg, isSystemApp = true))
    }

    @Test
    fun testAdServicesIsIdentifiedAsInternalAndAlwaysIgnoredForData() {
        val pkg1 = "com.google.android.adservices.api"
        val pkg2 = "com.android.adservices.api"
        assertTrue("Adservices deve ser interno", PackageIgnorePolicy.isInternalSystemPackage(pkg1))
        assertTrue("Adservices deve ser interno", PackageIgnorePolicy.isInternalSystemPackage(pkg2))
        assertTrue("Adservices não deve ter backup de dados", PackageIgnorePolicy.isAlwaysIgnoredForData(pkg1))
    }

    @Test
    fun testOverlaysAndRroAreIdentifiedAsInternalAndIgnored() {
        val rro = "android.auto_generated_rro_vendor__"
        val overlayName = "com.android.theme.icon.roundedrect.overlay"
        val overlayWithoutName = "com.android.internal.systemui.navbar.gestural"
        assertTrue("RRO auto-gerado deve ser reconhecido como overlay", PackageIgnorePolicy.isOverlayPackage(rro))
        assertTrue("Overlay por nome deve ser interno", PackageIgnorePolicy.isInternalSystemPackage(overlayName))
        assertTrue("Overlay por flag isOverlay deve ser interno", PackageIgnorePolicy.isInternalSystemPackage(overlayWithoutName, isOverlay = true))
        assertTrue("RRO não deve ter backup de dados", PackageIgnorePolicy.isAlwaysIgnoredForData(rro))
    }

    @Test
    fun testUserAppWithEmptyDataIsNotIgnored() {
        val userApp = "com.whatsapp"
        assertFalse("App de usuário não é interno", PackageIgnorePolicy.isInternalSystemPackage(userApp))
        assertFalse("App de usuário não é ignorado para dados por padrão", PackageIgnorePolicy.isAlwaysIgnoredForData(userApp))
        assertFalse("App de usuário sem dados DEVE ser tratado como falha (não ignorado)",
            PackageIgnorePolicy.shouldIgnoreIfEmptyData(userApp, isSystemApp = false))
    }

    @Test
    fun testGenericSystemAppWithEmptyDataIsIgnored() {
        val systemApp = "com.android.systemui"
        assertTrue("App de sistema sem dados deve ser marcado como ignorado",
            PackageIgnorePolicy.shouldIgnoreIfEmptyData(systemApp, isSystemApp = true))
    }

    @Test
    fun testProviderPackageEmptyDataIsIgnoredEvenIfFlagMismatch() {
        val provider = "com.android.providers.telephony"
        assertTrue("Provider do sistema vazio deve ser ignorado",
            PackageIgnorePolicy.shouldIgnoreIfEmptyData(provider, isSystemApp = false))
    }

    @Test
    fun testThreeStateSummaryClassification() {
        val items = listOf(
            CompletedItemInfo(name = "WhatsApp", success = true, isIgnored = false),
            CompletedItemInfo(name = "SDK Sandbox", success = true, isIgnored = true, statusText = "Ignorado (sem dados)"),
            CompletedItemInfo(name = "AdServices", success = true, isIgnored = true, statusText = "Ignorado (sem dados)"),
            CompletedItemInfo(name = "Broken App", success = false, isIgnored = false, error = "Timeout")
        )

        val successCount = items.count { it.success && !it.isIgnored }
        val ignoredCount = items.count { it.isIgnored }
        val failedCount = items.count { !it.success && !it.isIgnored }

        assertEquals(1, successCount)
        assertEquals(2, ignoredCount)
        assertEquals(1, failedCount)

        // Com falhas reais
        val titleWithFailures = when {
            failedCount > 0 -> "Finalizado com falhas"
            ignoredCount > 0 -> "Finalizado com avisos"
            else -> "Backup finalizado"
        }
        assertEquals("Finalizado com falhas", titleWithFailures)

        // Apenas com ignorados (sem falhas reais)
        val itemsWithoutFailures = items.filter { it.error == null }
        val failCnt2 = itemsWithoutFailures.count { !it.success && !it.isIgnored }
        val ignCnt2 = itemsWithoutFailures.count { it.isIgnored }
        val titleWithWarnings = when {
            failCnt2 > 0 -> "Finalizado com falhas"
            ignCnt2 > 0 -> "Finalizado com avisos"
            else -> "Backup finalizado"
        }
        assertEquals("Finalizado com avisos", titleWithWarnings)

        // Tudo 100% sucesso
        val perfectItems = listOf(
            CompletedItemInfo(name = "App1", success = true),
            CompletedItemInfo(name = "App2", success = true)
        )
        val failCnt3 = perfectItems.count { !it.success && !it.isIgnored }
        val ignCnt3 = perfectItems.count { it.isIgnored }
        val titlePerfect = when {
            failCnt3 > 0 -> "Finalizado com falhas"
            ignCnt3 > 0 -> "Finalizado com avisos"
            else -> "Backup finalizado"
        }
        assertEquals("Backup finalizado", titlePerfect)
    }
}
