package com.ireum.ytdl.work

import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputAuthorityTest {

    @Test
    fun staleFilesInAReusedRootCannotBecomeCurrentAttemptSources() {
        val root = Files.createTempDirectory("terminal-output-stale-").toFile()
        try {
            val stale = File(root, "stale.mp4").apply { writeText("old attempt") }
            val authority = TerminalOutputAuthority(root)
            authority.beginAttempt()
            val current = File(root, "current.mp4").apply { writeText("current attempt") }

            assertTrue(
                authority.currentSourceFiles(
                    "Destination: '${stale.absolutePath}'\nDestination: '${current.absolutePath}'",
                ).isEmpty()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onlyAllExplicitCurrentMultiOutputsArePublished() {
        val root = Files.createTempDirectory("terminal-output-multi-").toFile()
        val destination = Files.createTempDirectory("terminal-output-destination-").toFile()
        try {
            val authority = TerminalOutputAuthority(root)
            authority.beginAttempt()
            val first = File(root, "first.mp4").apply { writeText("first") }
            val second = File(root, "second.srt").apply { writeText("second") }
            val sources = authority.currentSourceFiles(
                "Destination: '${first.absolutePath}'\nWriting subtitles to: '${second.absolutePath}'",
            )
            assertEquals(
                listOf(first.canonicalPath, second.canonicalPath),
                sources.map { it.canonicalPath },
            )

            val published = sources.map { source ->
                File(destination, source.name).apply { writeText(source.readText()) }
            }
            assertEquals(
                published.map { it.canonicalPath },
                authority.recordMoveResults(
                    movedPaths = published.map { it.absolutePath },
                    sourceFiles = sources,
                ),
            )
        } finally {
            root.deleteRecursively()
            destination.deleteRecursively()
        }
    }

    @Test
    fun anUnreportedCurrentFileCannotBeAddedByDirectoryMembership() {
        val root = Files.createTempDirectory("terminal-output-unreported-").toFile()
        try {
            val authority = TerminalOutputAuthority(root)
            authority.beginAttempt()
            File(root, "reported.mp4").apply { writeText("reported") }
            val unreported = File(root, "unreported.mp4").apply { writeText("unreported") }

            val sources = authority.currentSourceFiles(
                "Destination: '${File(root, "reported.mp4").absolutePath}'",
            )
            assertEquals(1, sources.size)
            assertTrue(sources.none { it.canonicalPath == unreported.canonicalPath })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun structuredMarkerIsRequiredForNativeFileProducingExecution() {
        val root = Files.createTempDirectory("terminal-output-structured-").toFile()
        try {
            val ownershipMarker = File(root, ".ytdlnisx-terminal-owner").apply {
                writeText("ytdlnisx-terminal-owner\nversion=1\ntaskToken=test\n")
            }
            val authority = TerminalOutputAuthority(root, ownershipMarker)
            authority.beginAttempt()
            val output = File(root, "current.mp4").apply { writeText("current") }
            val marker = File(root, ".ytdlnisx-terminal-output.txt").apply {
                writeText("${DownloadOutputProvenance.PRINT_MARKER}${output.absolutePath}\n")
            }

            assertEquals(
                listOf(output.canonicalPath),
                requireTerminalSourceFiles(authority, "", marker).map { it.canonicalPath },
            )
            assertTrue(authority.removeStructuredMarker(marker))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingStructuredMarkerCannotBeReplacedByHumanLogForStrictExecution() {
        val root = Files.createTempDirectory("terminal-output-no-marker-").toFile()
        try {
            val authority = TerminalOutputAuthority(root)
            authority.beginAttempt()
            val output = File(root, "current.mp4").apply { writeText("current") }

            try {
                requireTerminalSourceFiles(
                    authority,
                    "Destination: '${output.absolutePath}'",
                    File(root, ".ytdlnisx-terminal-output.txt"),
                )
                throw AssertionError("strict execution must reject missing structured marker")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message.orEmpty().contains("authoritative"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalFailureCleanupPreservesUnprovenChildrenAndRevokesMarker() {
        val root = Files.createTempDirectory("terminal-output-cleanup-").toFile()
        try {
            val marker = TerminalCacheOwnership.markerFile(root)
            TerminalCacheOwnership.ensureMarker(root, "task-token")
            val unproven = File(root, "unproven.bin").apply { writeText("keep") }

            assertFalse(TerminalCacheOwnership.deleteIfOwned(root, "task-token"))
            assertTrue(unproven.isFile)
            assertFalse(marker.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun partialPublicationCleanupPreservesExactRemainderAndRevokesMarker() {
        val root = Files.createTempDirectory("terminal-output-partial-").toFile()
        try {
            val token = "task-token"
            val marker = TerminalCacheOwnership.ensureMarker(root, token)
            val moved = File(root, "moved.mp4").apply { writeText("moved") }
            val remainder = File(root, "remainder.srt").apply { writeText("remaining") }
            assertTrue(TerminalCacheOwnership.recordArtifacts(root, listOf(moved.absolutePath, remainder.absolutePath)))

            // Simulate FileUtil.moveFile having published only the first
            // manifest entry before the worker's failure path runs.
            assertTrue(moved.delete())
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(root, token))

            assertTrue(remainder.isFile)
            assertTrue(TerminalCacheOwnership.artifactManifestFile(root).isFile)
            assertFalse(marker.exists())
            assertFalse(TerminalCacheOwnership.isOwned(root, token))
        } finally {
            root.deleteRecursively()
        }
    }
}
