package com.ireum.ytdl.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
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
}
