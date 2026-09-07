package com.ireum.ytdl.util.storage

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadCacheOwnershipTest {
    @Test
    fun numericDirectoryWithoutMatchingOperationMarkerIsPreserved() {
        val root = Files.createTempDirectory("download-cache-unowned-").toFile()
        try {
            val item = item(operationId = "operation-current", executionId = "execution-current")
            val directory = File(root, item.id.toString()).apply { mkdirs() }
            val sentinel = File(directory, "unrelated.bin").apply { writeText("keep") }

            assertFalse(DownloadCacheOwnership.deleteIfOwned(root, item))
            assertTrue(sentinel.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun matchingOperationMarkerAllowsOnlyThatExactNumericRootToBeDeleted() {
        val root = Files.createTempDirectory("download-cache-owned-").toFile()
        try {
            val item = item(operationId = "operation-current", executionId = "execution-current")
            val directory = File(root, item.id.toString()).apply { mkdirs() }
            val sentinel = File(directory, "current.bin").apply { writeText("owned") }
            DownloadCacheOwnership.ensureMarker(root, item)
            assertTrue(DownloadCacheOwnership.recordArtifacts(root, item, listOf(sentinel.absolutePath)))

            assertTrue(DownloadCacheOwnership.deleteIfOwned(root, item))
            assertFalse(sentinel.exists())
            assertFalse(DownloadCacheOwnership.markerFile(root, item.id).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun matchingMarkerDoesNotDeleteUnprovenSibling() {
        val root = Files.createTempDirectory("download-cache-unproven-").toFile()
        try {
            val item = item(operationId = "operation-current", executionId = "execution-current")
            val directory = File(root, item.id.toString()).apply { mkdirs() }
            val owned = File(directory, "owned.bin").apply { writeText("owned") }
            val unrelated = File(directory, "unrelated.bin").apply { writeText("keep") }
            DownloadCacheOwnership.ensureMarker(root, item)
            assertTrue(DownloadCacheOwnership.recordArtifacts(root, item, listOf(owned.absolutePath)))

            assertFalse(DownloadCacheOwnership.deleteIfOwned(root, item))
            assertFalse(owned.exists())
            assertTrue(unrelated.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun markerFromAnotherOperationCannotAuthorizeStaleGenerationDeletion() {
        val root = Files.createTempDirectory("download-cache-stale-").toFile()
        try {
            val old = item(operationId = "operation-old", executionId = "execution-old")
            val current = item(operationId = "operation-current", executionId = "execution-current")
            File(root, old.id.toString()).mkdirs()
            File(root, old.id.toString()).resolve("stale.bin").writeText("stale")
            DownloadCacheOwnership.ensureMarker(root, old)

            assertFalse(DownloadCacheOwnership.deleteIfOwned(root, current))
            assertTrue(File(root, old.id.toString()).resolve("stale.bin").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun item(operationId: String, executionId: String) = DownloadItem(
        id = 42L,
        url = "https://example.com/video",
        title = "video",
        author = "author",
        thumb = "",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(format_id = "best"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "/downloads",
        website = "example.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = "Queued",
        downloadStartTime = 0L,
        logID = null,
        operationId = operationId,
        executionId = executionId,
    )
}
