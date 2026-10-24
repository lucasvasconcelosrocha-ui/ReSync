package com.resync.app

import com.resync.app.engine.FileCategory
import com.resync.app.engine.StorageItem
import com.resync.app.engine.StorageRepository
import com.resync.app.engine.StorageSortOrder
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StorageRepositoryTest {

    @Test
    fun testNormalizeAndDeduplicatePaths_removesChildrenWhenParentSelected() {
        val paths = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/DCIM/Camera",
            "/storage/emulated/0/DCIM/Camera/IMG_001.jpg",
            "/storage/emulated/0/Documents/report.pdf",
            "/storage/emulated/0/Pictures"
        )

        val deduplicated = StorageRepository.normalizeAndDeduplicatePaths(paths)

        assertEquals(3, deduplicated.size)
        // Should keep DCIM, Documents/report.pdf, and Pictures, but DROP DCIM/Camera and DCIM/Camera/IMG_001.jpg
        assertTrue(deduplicated.contains(File("/storage/emulated/0/DCIM").canonicalPath))
        assertTrue(deduplicated.contains(File("/storage/emulated/0/Documents/report.pdf").canonicalPath))
        assertTrue(deduplicated.contains(File("/storage/emulated/0/Pictures").canonicalPath))
        assertFalse(deduplicated.contains(File("/storage/emulated/0/DCIM/Camera").canonicalPath))
        assertFalse(deduplicated.contains(File("/storage/emulated/0/DCIM/Camera/IMG_001.jpg").canonicalPath))
    }

    @Test
    fun testNormalizeAndDeduplicatePaths_preservesDisjointDirectories() {
        val paths = listOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Music",
            "/storage/emulated/0/Movies"
        )

        val deduplicated = StorageRepository.normalizeAndDeduplicatePaths(paths)
        assertEquals(3, deduplicated.size)
    }

    @Test
    fun testNormalizeAndDeduplicatePaths_handlesEmptyAndDuplicates() {
        assertTrue(StorageRepository.normalizeAndDeduplicatePaths(emptyList()).isEmpty())

        val duplicates = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/DCIM"
        )
        val deduplicated = StorageRepository.normalizeAndDeduplicatePaths(duplicates)
        assertEquals(1, deduplicated.size)
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", StorageRepository.formatFileSize(0))
        assertEquals("0 B", StorageRepository.formatFileSize(-10))
        assertEquals("512 B", StorageRepository.formatFileSize(512))
        assertEquals("1,0 KB", StorageRepository.formatFileSize(1024))
        assertEquals("1,5 KB", StorageRepository.formatFileSize(1536))
        assertEquals("10,0 MB", StorageRepository.formatFileSize(10 * 1024 * 1024))
        assertEquals("2,5 GB", StorageRepository.formatFileSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testGetFileCategory() {
        assertEquals(FileCategory.IMAGES, StorageRepository.getFileCategory(File("photo.JPG")))
        assertEquals(FileCategory.IMAGES, StorageRepository.getFileCategory(File("test.png")))
        assertEquals(FileCategory.VIDEOS, StorageRepository.getFileCategory(File("video.mp4")))
        assertEquals(FileCategory.AUDIO, StorageRepository.getFileCategory(File("song.mp3")))
        assertEquals(FileCategory.DOCUMENTS, StorageRepository.getFileCategory(File("doc.pdf")))
        assertEquals(FileCategory.ARCHIVES, StorageRepository.getFileCategory(File("archive.zip")))
        assertEquals(FileCategory.APK, StorageRepository.getFileCategory(File("base.apk")))
        assertEquals(FileCategory.BACKUPS, StorageRepository.getFileCategory(File("vault.resync")))
        assertEquals(FileCategory.BACKUPS, StorageRepository.getFileCategory(File("vault.svb")))
        assertEquals(FileCategory.OTHERS, StorageRepository.getFileCategory(File("unknown.xyz")))
    }

    @Test
    fun testSortItems_foldersFirstByName() {
        val f1 = StorageItem(File("/storage/emulated/0/fileB.txt"), "fileB.txt", false, 500L, 1000L)
        val f2 = StorageItem(File("/storage/emulated/0/fileA.txt"), "fileA.txt", false, 1500L, 2000L)
        val dir1 = StorageItem(File("/storage/emulated/0/dirZ"), "dirZ", true, 0L, 500L)
        val dir2 = StorageItem(File("/storage/emulated/0/dirA"), "dirA", true, 0L, 1500L)

        val items = listOf(f1, dir1, f2, dir2)

        val sortedByNameAsc = StorageRepository.sortItems(items, StorageSortOrder.NAME_ASC)

        // Folders first (dirA, dirZ), then files (fileA.txt, fileB.txt)
        assertEquals("dirA", sortedByNameAsc[0].name)
        assertEquals("dirZ", sortedByNameAsc[1].name)
        assertEquals("fileA.txt", sortedByNameAsc[2].name)
        assertEquals("fileB.txt", sortedByNameAsc[3].name)
    }

    @Test
    fun testSortItems_bySizeDesc() {
        val f1 = StorageItem(File("/storage/emulated/0/small.txt"), "small.txt", false, 100L, 1000L)
        val f2 = StorageItem(File("/storage/emulated/0/large.txt"), "large.txt", false, 5000L, 2000L)
        val dir = StorageItem(File("/storage/emulated/0/folder"), "folder", true, 0L, 500L)

        val items = listOf(f1, f2, dir)
        val sortedBySize = StorageRepository.sortItems(items, StorageSortOrder.SIZE_DESC)

        // Directories first, then larger file, then smaller file
        assertEquals("folder", sortedBySize[0].name)
        assertEquals("large.txt", sortedBySize[1].name)
        assertEquals("small.txt", sortedBySize[2].name)
    }

    @Test
    fun testParseLsOutputLine_variousFormatsAndRealSizes() {
        val path = "/data/adb/modules/test_module"

        // 1. Standard Android Toybox format
        val item1 = com.resync.app.ui.screens.parseLsOutputLine(
            "-rw-r--r-- 1 root root 25841020 2026-10-20 11:11 MiuiHome.apk",
            path
        )
        assertNotNull(item1)
        assertEquals("MiuiHome.apk", item1!!.name)
        assertEquals(25841020L, item1.sizeBytes)
        assertFalse(item1.isDirectory)

        // 2. Traditional GNU/Unix format with Month name (Oct 20)
        val item2 = com.resync.app.ui.screens.parseLsOutputLine(
            "-rw-r--r-- 1 root root 25841020 Oct 20 11:11 MiuiHome.apk",
            path
        )
        assertNotNull(item2)
        assertEquals("MiuiHome.apk", item2!!.name)
        assertEquals(25841020L, item2.sizeBytes)
        assertFalse(item2.isDirectory)

        // 3. Line with SELinux security context
        val item3 = com.resync.app.ui.screens.parseLsOutputLine(
            "-rw-r--r-- 1 root root u:object_r:system_file:s0 25841020 2026-10-20 11:11 MiuiHome.apk",
            path
        )
        assertNotNull(item3)
        assertEquals("MiuiHome.apk", item3!!.name)
        assertEquals(25841020L, item3.sizeBytes)
        assertFalse(item3.isDirectory)

        // 4. Numeric UID and GID (ls -lan)
        val item4 = com.resync.app.ui.screens.parseLsOutputLine(
            "-rw-r--r-- 1 0 0 25841020 2026-10-20 11:11 MiuiHome.apk",
            path
        )
        assertNotNull(item4)
        assertEquals("MiuiHome.apk", item4!!.name)
        assertEquals(25841020L, item4.sizeBytes)
        assertFalse(item4.isDirectory)

        // 5. Directory entry
        val item5 = com.resync.app.ui.screens.parseLsOutputLine(
            "drwxr-xr-x 2 root root 4096 2026-10-20 11:11 system",
            path
        )
        assertNotNull(item5)
        assertEquals("system", item5!!.name)
        assertTrue(item5.isDirectory)

        // 6. File with spaces
        val item6 = com.resync.app.ui.screens.parseLsOutputLine(
            "-rw-r--r-- 1 root root 25841020 2026-10-20 11:11 Miui Home Launcher.apk",
            path
        )
        assertNotNull(item6)
        assertEquals("Miui Home Launcher.apk", item6!!.name)
        assertEquals(25841020L, item6.sizeBytes)
        assertFalse(item6.isDirectory)

        // 7. Symlink entry
        val item7 = com.resync.app.ui.screens.parseLsOutputLine(
            "lrwxrwxrwx 1 root root 23 Oct 20 11:11 MiuiHome.apk -> /system/priv-app/MiuiHome.apk",
            path
        )
        assertNotNull(item7)
        assertEquals("MiuiHome.apk", item7!!.name)
        assertTrue(item7.isSymlink)
        assertEquals("/system/priv-app/MiuiHome.apk", item7.symlinkTarget)
    }
}

