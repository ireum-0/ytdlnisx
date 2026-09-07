package com.ireum.ytdl.util.storage

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CacheImportPlannerTest {
    @Test
    fun onlyMarkerBackedArtifactsEnterMigrationManifest() {
        val root = Files.createTempDirectory("cache-import-manifest-").toFile()
        try {
            File(root, "unrelated.bin").writeText("keep")
            val unowned = File(root, "77").apply { mkdirs() }
            File(unowned, "legacy.bin").writeText("keep")

            val item = item()
            val owned = File(root, item.id.toString()).apply { mkdirs() }
            val ownedFile = File(owned, "video.mp4").apply { writeText("owned") }
            DownloadCacheOwnership.ensureMarker(root, item)

            val manifest = CacheImportPlanner.collect(root)

            assertEquals(listOf(ownedFile.canonicalPath), manifest.map { it.source.canonicalPath })
            assertFalse(manifest.any { it.source.name == "unrelated.bin" })
            assertFalse(manifest.any { it.source.name == "legacy.bin" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalMarkerBackedArtifactsAreSelectedButMarkerIsNotPublished() {
        val root = Files.createTempDirectory("cache-import-terminal-").toFile()
        try {
            val terminal = File(root, "TERMINAL/task-1").apply { mkdirs() }
            val output = File(terminal, "output.mp4").apply { writeText("owned") }
            TerminalCacheOwnership.ensureMarker(terminal, "task-1")

            val manifest = CacheImportPlanner.collect(root)

            assertEquals(listOf(output.canonicalPath), manifest.map { it.source.canonicalPath })
            assertTrue(manifest.none { it.source.name == ".ytdlnisx-terminal-owner" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun collisionSafeDestinationNeverReplacesExistingFile() {
        val root = Files.createTempDirectory("cache-import-destination-").toFile()
        try {
            val existing = File(root, "42/video.mp4").apply {
                parentFile?.mkdirs()
                writeText("existing")
            }

            val destination = CacheImportPlanner.collisionSafeDestination(root, "42/video.mp4")

            assertFalse(destination.absolutePath.equals(existing.absolutePath, ignoreCase = true))
            assertEquals("video (1).mp4", destination.name)
            assertEquals("existing", existing.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun item() = DownloadItem(
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
        operationId = "operation-current",
        executionId = "execution-current",
    )
}
