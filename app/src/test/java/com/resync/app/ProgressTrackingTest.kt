package com.resync.app

import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.CompletedItemInfo
import com.resync.app.data.model.ProgressRateLimiter
import com.resync.app.engine.BackupProgress
import com.resync.app.engine.RestoreProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProgressTrackingTest {

    @Test
    fun testProgressRateLimiterThrottling() = runBlocking {
        val limiter = ProgressRateLimiter(minIntervalMs = 50L)
        var callCount = 0

        // 1st call should execute immediately
        val firstCalled = limiter.emitThrottled { callCount++ }
        assertTrue("First emission must succeed immediately", firstCalled)
        assertEquals(1, callCount)

        // 2nd call right away without force should be throttled
        val secondCalled = limiter.emitThrottled(force = false) { callCount++ }
        assertFalse("Immediate subsequent emission without force must be throttled", secondCalled)
        assertEquals(1, callCount)

        // Forced call should always execute regardless of elapsed time
        val forcedCalled = limiter.emitThrottled(force = true) { callCount++ }
        assertTrue("Forced emission must bypass rate limit", forcedCalled)
        assertEquals(2, callCount)

        // After waiting past interval, emission should succeed again
        Thread.sleep(60L)
        val afterDelayCalled = limiter.emitThrottled { callCount++ }
        assertTrue("Emission after interval expires must succeed", afterDelayCalled)
        assertEquals(3, callCount)
    }

    @Test
    fun testBackupProgressModelWithDetailedItems() {
        val completedList = listOf(
            CompletedItemInfo(id = "item1", name = "Photos.zip", type = BackupItemType.FILE, success = true),
            CompletedItemInfo(id = "item2", name = "Contacts.vcf", type = BackupItemType.CONTACTS, success = false, error = "Corrupted VCF")
        )

        val progress = BackupProgress(
            stage = "Empacotando",
            currentItemName = "Photos.zip",
            progressPercent = 0.5f,
            currentItemIndex = 1,
            totalItems = 2,
            currentItemType = BackupItemType.FILE,
            completedItems = completedList
        )

        assertEquals("Empacotando", progress.stage)
        assertEquals("Photos.zip", progress.currentItemName)
        assertEquals(0.5f, progress.progressPercent, 0.001f)
        assertEquals(1, progress.currentItemIndex)
        assertEquals(2, progress.totalItems)
        assertEquals(BackupItemType.FILE, progress.currentItemType)
        assertEquals(2, progress.completedItems.size)
        assertTrue(progress.completedItems[0].success)
        assertFalse(progress.completedItems[1].success)
        assertEquals("Corrupted VCF", progress.completedItems[1].error)
    }

    @Test
    fun testRestoreProgressModelWithDetailedItems() {
        val completedList = listOf(
            CompletedItemInfo(id = "com.test.app", name = "TestApp", type = BackupItemType.APP_APK, success = true)
        )

        val progress = RestoreProgress(
            stage = "Instalando App",
            currentItemName = "TestApp (com.test.app)",
            progressPercent = 0.8f,
            currentItemIndex = 1,
            totalItems = 1,
            currentItemType = BackupItemType.APP_APK,
            completedItems = completedList
        )

        assertEquals("Instalando App", progress.stage)
        assertEquals(1, progress.currentItemIndex)
        assertEquals(1, progress.totalItems)
        assertEquals(BackupItemType.APP_APK, progress.currentItemType)
        assertEquals(1, progress.completedItems.size)
        assertEquals("TestApp", progress.completedItems[0].name)
    }
}

