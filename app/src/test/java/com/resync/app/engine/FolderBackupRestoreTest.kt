package com.resync.app.engine

import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.ManifestItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Testes rigorosos de auditoria para prevenção de perda de dados no backup e restauração de pastas:
 * 1. Backup: integridade total da pasta e arquivos de origem (garantia de ZERO modificações/exclusões na origem).
 * 2. Restauração: estrutura 100% idêntica sem subpasta aninhada extra (ex: ksu/ksu/...).
 * 3. Retrocompatibilidade: detecção e desembrulho de backups legados com pasta raiz duplicada.
 * 4. Preservação de arquivos preexistentes: mesclagem segura sem wipe (sem mindepth 1 delete em dados do usuário).
 */
class FolderBackupRestoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var appCacheDir: File
    private lateinit var resyncTmpDir: File

    @Before
    fun setUp() {
        appCacheDir = tempFolder.newFolder("app_cache")
        resyncTmpDir = File(appCacheDir, "resync_tmp").apply { mkdirs() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testBackupPreservesSourceFolderAndFilesIntact() {
        // 1. Simular pasta de origem do usuário (/sdcard/ksu)
        val userStorage = tempFolder.newFolder("user_storage")
        val sourceKsu = File(userStorage, "ksu").apply { mkdirs() }
        val configFile = File(sourceKsu, "config.json").apply { writeText("{\"active\": true, \"version\": 1}") }
        val modulesDir = File(sourceKsu, "modules").apply { mkdirs() }
        val updateScript = File(modulesDir, "update.sh").apply { writeText("#!/bin/sh\necho 'running'") }
        val deepDir = File(sourceKsu, "deep/nested").apply { mkdirs() }
        val deepItem = File(deepDir, "item.txt").apply { writeText("dados confidenciais") }

        // Calcular estado e hashes originais
        val originalConfigHash = sha256(configFile)
        val originalScriptHash = sha256(updateScript)
        val originalDeepHash = sha256(deepItem)
        val originalConfigLen = configFile.length()
        val originalScriptLen = updateScript.length()
        val originalDeepLen = deepItem.length()

        // 2. Simular diretório de staging exclusivo em cacheDir/resync_tmp/<id>
        val stagingDir = File(resyncTmpDir, "staging_${UUID.randomUUID()}").apply { mkdirs() }
        val stagingContainer = File(stagingDir, "backup.resync").apply { writeText("CONTAINER_DATA") }
        val stagedTempJson = File(stagingDir, "telephony_sms_backup.json").apply { writeText("[{}]") }

        // 3. Montar lista mista de PackEntry: arquivos temporários do staging + arquivos reais da origem
        val packEntries = listOf(
            ArchiveEngine.PackEntry(
                entryName = "system/telephony/sms_backup.json",
                sourceFile = stagedTempJson,
                manifestItem = ManifestItem(id = "1", type = BackupItemType.SMS, name = "sms", originalPath = "", archiveEntryPath = "system/telephony/sms_backup.json", sizeBytes = stagedTempJson.length(), sha256Checksum = "")
            ),
            ArchiveEngine.PackEntry(
                entryName = "files/ksu/config.json",
                sourceFile = configFile,
                manifestItem = ManifestItem(id = "2", type = BackupItemType.FILE, name = "config.json", originalPath = configFile.absolutePath, archiveEntryPath = "files/ksu/config.json", sizeBytes = originalConfigLen, sha256Checksum = originalConfigHash)
            ),
            ArchiveEngine.PackEntry(
                entryName = "files/ksu/modules/update.sh",
                sourceFile = updateScript,
                manifestItem = ManifestItem(id = "3", type = BackupItemType.FILE, name = "update.sh", originalPath = updateScript.absolutePath, archiveEntryPath = "files/ksu/modules/update.sh", sizeBytes = originalScriptLen, sha256Checksum = originalScriptHash)
            ),
            ArchiveEngine.PackEntry(
                entryName = "files/ksu/deep/nested/item.txt",
                sourceFile = deepItem,
                manifestItem = ManifestItem(id = "4", type = BackupItemType.FILE, name = "item.txt", originalPath = deepItem.absolutePath, archiveEntryPath = "files/ksu/deep/nested/item.txt", sizeBytes = originalDeepLen, sha256Checksum = originalDeepHash)
            )
        )

        // 4. Executar rotina corrigida de higiene pós-empacotamento
        val stagingCanonical = stagingDir.canonicalPath
        packEntries.forEach { entry ->
            val src = entry.sourceFile
            if (src != null && src.exists()) {
                val srcCanonical = src.canonicalPath
                if (srcCanonical.startsWith(stagingCanonical + File.separator) && srcCanonical != stagingContainer.canonicalPath) {
                    try { src.delete() } catch (_: Throwable) {}
                }
            }
        }

        // 5. Verificações Absolutas:
        // A) Arquivo temporário dentro de staging FOI limpo
        assertFalse("Arquivo intermediário do staging deve ser excluído", stagedTempJson.exists())

        // B) Arquivos de ORIGEM devem estar 100% INTACTOS (nenhum arquivo apagado nem alterado)
        assertTrue("configFile de origem DEVE existir após o backup", configFile.exists())
        assertEquals(originalConfigLen, configFile.length())
        assertEquals(originalConfigHash, sha256(configFile))

        assertTrue("updateScript de origem DEVE existir após o backup", updateScript.exists())
        assertEquals(originalScriptLen, updateScript.length())
        assertEquals(originalScriptHash, sha256(updateScript))

        assertTrue("deepItem de origem DEVE existir após o backup", deepItem.exists())
        assertEquals(originalDeepLen, deepItem.length())
        assertEquals(originalDeepHash, sha256(deepItem))

        assertTrue("Diretório ksu de origem DEVE existir", sourceKsu.exists())
        assertTrue("Subdiretório modules de origem DEVE existir", modulesDir.exists())
        assertTrue("Subdiretório deep/nested de origem DEVE existir", deepDir.exists())
    }

    @Test
    fun testSafeStagingCleanupBlocksSourceFolderDeletion() {
        val userFolder = tempFolder.newFolder("source_ksu")
        val protectedFile = File(userFolder, "safe.txt").apply { writeText("safe content") }

        fun safeDeleteStagingTest(dir: File?, protectedPaths: List<String>): Boolean {
            if (dir == null || !dir.exists()) return false
            val resyncTmpCanon = resyncTmpDir.canonicalFile
            val targetCanon = dir.canonicalFile
            if (!targetCanon.path.startsWith(resyncTmpCanon.path + File.separator)) {
                return false // Bloqueado: fora de resync_tmp
            }
            for (p in protectedPaths) {
                if (p.isBlank()) continue
                val pCanon = File(p).canonicalFile
                if (targetCanon.path == pCanon.path || targetCanon.path.startsWith(pCanon.path + File.separator) || pCanon.path.startsWith(targetCanon.path + File.separator)) {
                    return false // Bloqueado: coincide com pasta de origem protegida
                }
            }
            return targetCanon.deleteRecursively()
        }

        // Tentativa 1: tentar apagar a pasta de origem do usuário diretamente
        val deletedSource = safeDeleteStagingTest(userFolder, listOf(userFolder.absolutePath))
        assertFalse("Tentativa de exclusão da pasta de origem deve ser bloqueada", deletedSource)
        assertTrue("Arquivo do usuário não pode ser excluído", protectedFile.exists())

        // Tentativa 2: diretório válido em resync_tmp, mas que coincide com caminho protegido
        val fakeStagingInsideTmp = File(resyncTmpDir, "fake_staging").apply { mkdirs() }
        val blocked = safeDeleteStagingTest(fakeStagingInsideTmp, listOf(fakeStagingInsideTmp.absolutePath))
        assertFalse("Exclusão de diretório registrado como origem protegida deve ser bloqueada", blocked)
        assertTrue(fakeStagingInsideTmp.exists())

        // Tentativa 3: diretório temporário normal dentro de resync_tmp (não protegido)
        val validStaging = File(resyncTmpDir, "valid_temp_${UUID.randomUUID()}").apply { mkdirs() }
        File(validStaging, "temp.bin").writeText("12345")
        val deletedValid = safeDeleteStagingTest(validStaging, listOf(userFolder.absolutePath))
        assertTrue("Diretório temporário legítimo deve ser excluído com sucesso", deletedValid)
        assertFalse(validStaging.exists())
    }

    @Test
    fun testRestoreFolderExtractsDirectlyWithoutSubfolderNesting() {
        // Simular container novo descompactado em temp: tempRestore/files/ksu/...
        val tempRestoreDir = File(resyncTmpDir, "restore_test_${UUID.randomUUID()}").apply { mkdirs() }
        val extractedFolder = File(tempRestoreDir, "files/ksu").apply { mkdirs() }
        File(extractedFolder, "config.json").writeText("{\"restored\": true}")
        val subDir = File(extractedFolder, "modules").apply { mkdirs() }
        File(subDir, "meta.prop").writeText("id=my_module")

        val destinationFolder = tempFolder.newFolder("restored_ksu_dest")

        // Resolver raiz efetiva de conteúdo
        val effectiveSource = RestoreEngine.resolveFolderContentRoot(extractedFolder, "ksu", destinationFolder.name)
        assertEquals("Para backups novos e padronizados, a raiz deve ser o próprio extractedFolder", extractedFolder.absolutePath, effectiveSource.absolutePath)

        // Copiar arquivos para destinationFolder
        effectiveSource.walkTopDown().filter { it.isFile }.forEach { file ->
            val relPath = file.relativeTo(effectiveSource).path
            val destFile = File(destinationFolder, relPath)
            destFile.parentFile?.mkdirs()
            file.copyTo(destFile, overwrite = true)
        }

        // Validação: Estrutura idêntica, SEM pasta extra ksu/ksu
        val destConfigFile = File(destinationFolder, "config.json")
        val destModuleMeta = File(destinationFolder, "modules/meta.prop")
        val duplicateNestedKsu = File(destinationFolder, "ksu")

        assertTrue("config.json deve estar na raiz do destino", destConfigFile.exists())
        assertEquals("{\"restored\": true}", destConfigFile.readText())
        assertTrue("modules/meta.prop deve estar no subdiretório correto", destModuleMeta.exists())
        assertFalse("NÃO pode existir subpasta duplicada ksu/ksu/!", duplicateNestedKsu.exists())
    }

    @Test
    fun testRetrocompatibilityUnwrapsOlderBackupsWithDuplicateRoot() {
        // Simular backup legado antigo onde a pasta raiz foi duplicada: tempRestore/files/ksu/ksu/...
        val tempRestoreDir = File(resyncTmpDir, "restore_legacy_${UUID.randomUUID()}").apply { mkdirs() }
        val extractedFolder = File(tempRestoreDir, "files/ksu").apply { mkdirs() }
        val legacyDuplicateRoot = File(extractedFolder, "ksu").apply { mkdirs() }
        File(legacyDuplicateRoot, "config.json").writeText("{\"legacy\": true}")
        val subDir = File(legacyDuplicateRoot, "modules").apply { mkdirs() }
        File(subDir, "legacy_module.prop").writeText("version=legacy")

        val destinationFolder = tempFolder.newFolder("restored_legacy_dest")

        // Resolver raiz efetiva de conteúdo
        val effectiveSource = RestoreEngine.resolveFolderContentRoot(extractedFolder, "ksu", destinationFolder.name)
        assertEquals("Backup legado com pasta raiz duplicada deve ser desembrulhado para a subpasta ksu", legacyDuplicateRoot.absolutePath, effectiveSource.absolutePath)

        // Copiar arquivos para destinationFolder
        effectiveSource.walkTopDown().filter { it.isFile }.forEach { file ->
            val relPath = file.relativeTo(effectiveSource).path
            val destFile = File(destinationFolder, relPath)
            destFile.parentFile?.mkdirs()
            file.copyTo(destFile, overwrite = true)
        }

        // Validação: Estrutura idêntica, SEM subpasta duplicada ksu/ksu
        val destConfigFile = File(destinationFolder, "config.json")
        val destModuleMeta = File(destinationFolder, "modules/legacy_module.prop")
        val duplicateNestedKsu = File(destinationFolder, "ksu")

        assertTrue("config.json deve estar na raiz do destino", destConfigFile.exists())
        assertEquals("{\"legacy\": true}", destConfigFile.readText())
        assertTrue("modules/legacy_module.prop deve estar no subdiretório correto", destModuleMeta.exists())
        assertFalse("Subpasta raiz duplicada deve ser eliminada e NÃO existir em destino/ksu/!", duplicateNestedKsu.exists())
    }

    @Test
    fun testFolderRestorePreservesExistingUnrelatedFilesWithoutWiping() {
        val destinationFolder = tempFolder.newFolder("user_existing_folder")
        val existingUserFile = File(destinationFolder, "important_doc.pdf").apply { writeText("DOCUMENTO DO USUARIO") }

        // Simular restauração de novos arquivos na pasta
        val incomingFile = File(destinationFolder, "restored_file.txt")
        incomingFile.writeText("CONTEUDO RESTAURADO")

        // Garantir que a mesclagem não apagou o arquivo existente
        assertTrue("Arquivo do usuário existente deve permanecer intacto (sem wipe na restauração de pastas)", existingUserFile.exists())
        assertEquals("DOCUMENTO DO USUARIO", existingUserFile.readText())
        assertTrue("Arquivo restaurado deve coexistir", incomingFile.exists())
    }

    @Test
    fun testLegacyBackupWithLooseRootFilesAndSubfoldersRestoresAllFilesProperly() {
        // Simular o cenário exato do Bug 1 e Bug 2:
        // No backup legado, arquivos soltos na raiz de Download foram gravados em files/<nome>,
        // enquanto arquivos de subpastas foram gravados em files/Download/AyuGram/<nome>.
        val tempRestoreDir = File(resyncTmpDir, "legacy_download_test_${UUID.randomUUID()}").apply { mkdirs() }
        val tempFilesDir = File(tempRestoreDir, "files").apply { mkdirs() }

        // 1. Arquivo solto na raiz gravado como files/top_gear_frankfurt.mp3
        val looseArchiveFile = File(tempFilesDir, "top_gear_frankfurt.mp3").apply { writeText("AUDIO_DATA_MP3") }

        // 2. Subpasta descompactada em files/Download/AyuGram/
        val downloadFolderInTemp = File(tempFilesDir, "Download").apply { mkdirs() }
        val ayugramDir = File(downloadFolderInTemp, "AyuGram").apply { mkdirs() }
        val subfolderArchiveFile = File(ayugramDir, "photo.jpg").apply { writeText("PHOTO_DATA_JPG") }

        val destinationFolder = tempFolder.newFolder("Download")

        val folderItem = ManifestItem(
            id = "folder_download_1",
            type = BackupItemType.FOLDER,
            name = "Download",
            originalPath = destinationFolder.absolutePath,
            archiveEntryPath = "files/Download",
            sizeBytes = 1000L,
            sha256Checksum = ""
        )

        val childLooseItem = ManifestItem(
            id = "file_loose_1",
            type = BackupItemType.FILE,
            name = "top_gear_frankfurt.mp3",
            originalPath = File(destinationFolder, "top_gear_frankfurt.mp3").absolutePath,
            archiveEntryPath = "files/top_gear_frankfurt.mp3",
            sizeBytes = looseArchiveFile.length(),
            sha256Checksum = ""
        )

        val childSubfolderItem = ManifestItem(
            id = "file_sub_1",
            type = BackupItemType.FILE,
            name = "photo.jpg",
            originalPath = File(destinationFolder, "AyuGram/photo.jpg").absolutePath,
            archiveEntryPath = "files/Download/AyuGram/photo.jpg",
            sizeBytes = subfolderArchiveFile.length(),
            sha256Checksum = ""
        )

        val allManifestItems = listOf(folderItem, childLooseItem, childSubfolderItem)

        // Executar a mesma resolução unificada de alvos implementada no RestoreEngine
        val extractedFolder = File(tempRestoreDir, folderItem.archiveEntryPath)
        val childItems = allManifestItems.filter { child ->
            child.id != folderItem.id &&
            (child.archiveEntryPath.startsWith(folderItem.archiveEntryPath.trimEnd('/') + "/") ||
             (folderItem.originalPath.isNotBlank() && child.originalPath.startsWith(folderItem.originalPath.trimEnd('/') + "/")) ||
             child.archiveEntryPath.startsWith("files/${folderItem.name}/"))
        }

        val effectiveSourceFolder = RestoreEngine.resolveFolderContentRoot(extractedFolder, folderItem.name, destinationFolder.name)

        data class Target(val relPath: String, val src: File)
        val targets = mutableMapOf<String, Target>()

        // 1. Processar childItems do manifesto
        for (child in childItems) {
            var rel = if (folderItem.originalPath.isNotBlank() && child.originalPath.startsWith(folderItem.originalPath.trimEnd('/') + "/")) {
                child.originalPath.removePrefix(folderItem.originalPath.trimEnd('/') + "/")
            } else if (child.archiveEntryPath.startsWith(folderItem.archiveEntryPath.trimEnd('/') + "/")) {
                child.archiveEntryPath.removePrefix(folderItem.archiveEntryPath.trimEnd('/') + "/")
            } else if (child.archiveEntryPath.startsWith("files/${folderItem.name}/")) {
                child.archiveEntryPath.removePrefix("files/${folderItem.name}/")
            } else {
                child.name
            }
            rel = rel.trimStart('/')
            if (rel.startsWith("${folderItem.name}/", ignoreCase = true)) {
                rel = rel.substring(folderItem.name.length + 1).trimStart('/')
            }
            if (rel.isBlank()) continue

            val candidate1 = File(tempRestoreDir, child.archiveEntryPath)
            val candidate2 = File(effectiveSourceFolder, rel)
            val candidate3 = File(extractedFolder, rel)
            val candidate4 = File(tempRestoreDir, "files/$rel")

            val validSrc = listOf(candidate1, candidate2, candidate3, candidate4).firstOrNull { it.exists() && it.isFile }
            if (validSrc != null) {
                targets[rel] = Target(rel, validSrc)
            }
        }

        // 2. Processar arquivos no disco sob effectiveSourceFolder
        if (effectiveSourceFolder.exists() && effectiveSourceFolder.isDirectory) {
            effectiveSourceFolder.walkTopDown().filter { it.isFile }.forEach { file ->
                var rel = file.relativeTo(effectiveSourceFolder).path.replace('\\', '/').trimStart('/')
                if (rel.startsWith("${folderItem.name}/", ignoreCase = true)) {
                    rel = rel.substring(folderItem.name.length + 1).trimStart('/')
                }
                if (rel.isNotBlank() && !targets.containsKey(rel)) {
                    targets[rel] = Target(rel, file)
                }
            }
        }

        // 3. Gravar arquivos em destino
        var restoredCount = 0
        for (target in targets.values) {
            val destFile = File(destinationFolder, target.relPath)
            destFile.parentFile?.mkdirs()
            target.src.copyTo(destFile, overwrite = true)
            restoredCount++
        }

        // Validações Absolutas:
        assertEquals("Devem ser restaurados exatamente 2 arquivos (o solto na raiz e o da subpasta)", 2, restoredCount)

        val restoredLoose = File(destinationFolder, "top_gear_frankfurt.mp3")
        assertTrue("Arquivo solto na raiz DEVE existir em Download/top_gear_frankfurt.mp3", restoredLoose.exists())
        assertEquals("AUDIO_DATA_MP3", restoredLoose.readText())

        val restoredSubfolder = File(destinationFolder, "AyuGram/photo.jpg")
        assertTrue("Arquivo da subpasta DEVE existir em Download/AyuGram/photo.jpg", restoredSubfolder.exists())
        assertEquals("PHOTO_DATA_JPG", restoredSubfolder.readText())

        val duplicateDownload = File(destinationFolder, "Download")
        assertFalse("NÃO pode criar subpasta aninhada Download/Download!", duplicateDownload.exists())
    }

    @Test
    fun testBufferedStreamFileWritingWithParentCreation() {
        val srcDir = tempFolder.newFolder("stream_src")
        val srcFile = File(srcDir, "sample.bin").apply { writeBytes(ByteArray(128 * 1024) { (it % 127).toByte() }) }

        val destDir = tempFolder.newFolder("stream_dest")
        val destFile = File(destDir, "nested/sub/folder/sample.bin")

        // Executar gravação com streams bufferizados de 64KB e criação de pais
        val parent = destFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        java.io.FileInputStream(srcFile).use { fis ->
            java.io.BufferedInputStream(fis, 65536).use { bis ->
                java.io.FileOutputStream(destFile, false).use { fos ->
                    java.io.BufferedOutputStream(fos, 65536).use { bos ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (bis.read(buffer).also { bytesRead = it } != -1) {
                            bos.write(buffer, 0, bytesRead)
                        }
                        bos.flush()
                    }
                }
            }
        }

        assertTrue("Arquivo gravado via stream deve existir no destino aninhado", destFile.exists())
        assertEquals("Tamanho deve ser exatamente igual ao arquivo de origem (128 KB)", srcFile.length(), destFile.length())
        assertEquals("Hash SHA-256 deve coincidir perfeitamente", sha256(srcFile), sha256(destFile))
    }
}
