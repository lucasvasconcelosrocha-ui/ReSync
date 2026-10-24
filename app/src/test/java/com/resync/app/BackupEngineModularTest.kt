package com.resync.app

import android.content.Context
import android.content.ContextWrapper
import com.resync.app.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupEngineModularTest {

    private val stubContext: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): java.io.File? = java.io.File("/tmp")
        override fun getPackageManager(): android.content.pm.PackageManager? = null
    }

    @Test
    fun testFactoryCreatesRootBackupEngineWhenRootRequested() = runBlocking {
        val engine = BackupEngineFactory.createEngine(stubContext, forceRootMode = true)
        assertTrue("O motor criado deve ser RootBackupEngine", engine is RootBackupEngine)
        assertTrue("isRootEnabled deve ser true para RootBackupEngine", engine.isRootEnabled)
    }

    @Test
    fun testFactoryCreatesNormalBackupEngineWhenRootDenied() = runBlocking {
        val engine = BackupEngineFactory.createEngine(stubContext, forceRootMode = false)
        assertTrue("O motor criado deve ser NormalBackupEngine", engine is NormalBackupEngine)
        assertFalse("isRootEnabled deve ser false para NormalBackupEngine", engine.isRootEnabled)
    }

    @Test
    fun testNormalBackupEngineSafelyBlocksAppDataBackup() = runBlocking {
        val normalEngine = NormalBackupEngine(stubContext, throwOnRestrictedOperations = false)
        val success = normalEngine.backupAppData(listOf("com.example.app"))
        assertFalse("NormalBackupEngine deve retornar false para backup de /data/data sem lançar crash", success)
    }

    @Test
    fun testNormalBackupEngineSafelyBlocksAppDataRestore() = runBlocking {
        val normalEngine = NormalBackupEngine(stubContext, throwOnRestrictedOperations = false)
        val success = normalEngine.restoreAppData(listOf("com.example.app"))
        assertFalse("NormalBackupEngine deve retornar false para restauro de /data/data sem lançar crash", success)
    }

    @Test
    fun testNormalBackupEngineThrowsControlledExceptionWhenConfigured() = runBlocking {
        val strictNormalEngine = NormalBackupEngine(stubContext, throwOnRestrictedOperations = true)
        try {
            strictNormalEngine.backupAppData(listOf("com.example.app"))
            fail("Deveria lançar UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message?.contains("Root") == true)
        }
    }
}
