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
            DownloadCacheOwnership.ensureMarker(root, item)
            val sentinel = File(directory, "current.bin").apply { writeText("owned") }
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
            DownloadCacheOwnership.ensureMarker(root, item)
            val owned = File(directory, "owned.bin").apply { writeText("owned") }
            val unrelated = File(directory, "unrelated.bin").apply { writeText("keep") }
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
            DownloadCacheOwnership.ensureMarker(root, old)
            File(root, old.id.toString()).resolve("stale.bin").writeText("stale")

            assertFalse(DownloadCacheOwnership.deleteIfOwned(root, current))
            assertTrue(File(root, old.id.toString()).resolve("stale.bin").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun markerFromAnotherExecutionCannotAuthorizeStaleGenerationDeletion() {
        val root = Files.createTempDirectory("download-cache-stale-execution-").toFile()
        try {
            val old = item(operationId = "operation-current", executionId = "execution-old")
            val current = item(operationId = "operation-current", executionId = "execution-current")
            val directory = File(root, old.id.toString()).apply { mkdirs() }
            DownloadCacheOwnership.ensureMarker(root, old)
            val stale = directory.resolve("stale.bin").apply { writeText("stale") }

            assertFalse(DownloadCacheOwnership.isOwned(root, current))
            assertFalse(DownloadCacheOwnership.deleteIfOwned(root, current))
            assertTrue(stale.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun prepareAttemptCannotAuthenticatePreExistingNumericContents() {
        val root = Files.createTempDirectory("download-cache-bootstrap-").toFile()
        try {
            val item = item(operationId = "operation-current", executionId = "execution-current")
            val directory = File(root, item.id.toString()).apply { mkdirs() }
            val stale = directory.resolve("stale.bin").apply { writeText("preserve") }
            val staleManifest = DownloadCacheOwnership.artifactManifestFile(root, item.id).apply {
                writeText("stale.bin\n")
            }

            assertFalse(runCatching { DownloadCacheOwnership.prepareAttempt(root, item) }.isSuccess)
            assertTrue(stale.isFile)
            assertTrue(staleManifest.isFile)
            assertFalse(DownloadCacheOwnership.markerFile(root, item.id).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun prepareAttemptRejectsUnboundManifestEvenWhenMarkerMatchesCurrentExecution() {
        val root = Files.createTempDirectory("download-cache-unbound-manifest-").toFile()
        try {
            val item = item(operationId = "operation-current", executionId = "execution-current")
            val directory = File(root, item.id.toString()).apply { mkdirs() }
            DownloadCacheOwnership.ensureMarker(root, item)
            val stale = directory.resolve("stale.bin").apply { writeText("preserve") }
            DownloadCacheOwnership.artifactManifestFile(root, item.id).writeText("stale.bin\n")

            assertFalse(runCatching { DownloadCacheOwnership.prepareAttempt(root, item) }.isSuccess)
            assertTrue(stale.isFile)
            assertTrue(DownloadCacheOwnership.artifactManifestFile(root, item.id).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retiringManifestRequiresCurrentOwnershipMarker() {
        val root = Files.createTempDirectory("download-cache-retire-").toFile()
        try {
            val item = item(operationId = "operation-current", executionId = "execution-current")
            val directory = File(root, item.id.toString()).apply { mkdirs() }
            File(directory, "output.bin").writeText("output")

            assertFalse(DownloadCacheOwnership.removeArtifactManifest(root, item))
            assertTrue(File(directory, "output.bin").isFile)
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
