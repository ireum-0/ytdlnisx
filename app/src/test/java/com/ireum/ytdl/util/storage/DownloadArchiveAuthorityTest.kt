package com.ireum.ytdl.util.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadArchiveAuthorityTest {
    @Test
    fun promotionIsIdempotentAndOnlyAddsPrivateEntries() {
        val root = Files.createTempDirectory("archive-authority").toFile()
        val previousSync = DownloadArchiveAuthority.syncForTesting
        DownloadArchiveAuthority.syncForTesting = { }
        try {
            val global = File(root, "global.txt").apply { writeText("youtube A\n") }
            val private = File(root, "private.txt").apply { writeText("youtube A\nyoutube B\n") }
            val generation = DownloadArchiveAuthority.Generation(7L, "e1", private, global)

            assertTrue(DownloadArchiveAuthority.promote(generation))
            assertEquals(listOf("youtube A", "youtube B"), global.readLines())
            assertFalse(private.exists())
            assertTrue(DownloadArchiveAuthority.promote(generation))
            assertEquals(listOf("youtube A", "youtube B"), global.readLines())
        } finally {
            DownloadArchiveAuthority.syncForTesting = previousSync
            root.deleteRecursively()
        }
    }

    @Test
    fun privateArchiveDoesNotMutateGlobalBeforePromotion() {
        val root = Files.createTempDirectory("archive-authority").toFile()
        try {
            val global = File(root, "global.txt").apply { writeText("youtube A\n") }
            val private = File(root, "private.txt").apply { writeText("youtube A\nyoutube B\n") }
            assertEquals(listOf("youtube A"), DownloadArchiveAuthority.readLines(global))
            assertEquals(listOf("youtube A", "youtube B"), DownloadArchiveAuthority.readLines(private))
            assertEquals("youtube A\n", global.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
