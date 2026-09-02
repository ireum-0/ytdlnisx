package com.ireum.ytdl.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadOutputProvenanceTest {

    @Test
    fun ambientDirectoryFilesAndPreExistingNamesNeverBecomeAuthoritative() {
        val tempDirectory = Files.createTempDirectory("output-provenance-temp-").toFile()
        val directDirectory = Files.createTempDirectory("output-provenance-direct-").toFile()
        try {
            val staleTemp = write(tempDirectory, "stale.mp4")
            val preExistingExpected = write(directDirectory, "expected.mp4")
            val unrelatedRecent = write(directDirectory, "unrelated.mp4")
            val unrelatedSameName = write(directDirectory, "same-name.mp4")
            val provenance = DownloadOutputProvenance(tempDirectory, directDirectory)

            provenance.beginAttempt()

            assertTrue(provenance.currentAttemptPaths().isEmpty())
            assertFalse(provenance.isAuthoritative(staleTemp.absolutePath))
            assertFalse(provenance.isAuthoritative(unrelatedRecent.absolutePath))
            assertFalse(provenance.isAuthoritative(unrelatedSameName.absolutePath))
            assertTrue(
                provenance.acceptYtdlpOutput("Destination: '${preExistingExpected.absolutePath}'").isEmpty()
            )
            assertTrue(provenance.currentAttemptPaths().isEmpty())
        } finally {
            tempDirectory.deleteRecursively()
            directDirectory.deleteRecursively()
        }
    }

    @Test
    fun staleTempAttemptIsRejectedUntilTheOwnedRootIsReset() {
        val tempDirectory = Files.createTempDirectory("output-provenance-retry-").toFile()
        try {
            write(tempDirectory, "same-name.mp4")
            val provenance = DownloadOutputProvenance(tempDirectory)
            provenance.beginAttempt()

            val attemptTwoArtifact = File(tempDirectory, "same-name.mp4")
            attemptTwoArtifact.writeText("attempt-two-without-reset")
            assertTrue(
                provenance.acceptYtdlpOutput("Destination: '${attemptTwoArtifact.absolutePath}'").isEmpty()
            )

            tempDirectory.deleteRecursively()
            assertTrue(tempDirectory.mkdirs())
            provenance.beginAttempt()
            val cleanAttemptArtifact = write(tempDirectory, "same-name.mp4")
            assertEquals(
                listOf(cleanAttemptArtifact.canonicalPath),
                provenance.acceptYtdlpOutput("Destination: '${cleanAttemptArtifact.absolutePath}'")
            )
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun exactCurrentReportedMediaSubtitlesAndSidecarsArePreserved() {
        val tempDirectory = Files.createTempDirectory("output-provenance-reported-").toFile()
        try {
            val provenance = DownloadOutputProvenance(tempDirectory)
            provenance.beginAttempt()
            val media = write(tempDirectory, "video.mp4")
            val subtitle = write(tempDirectory, "video.en.srt")
            val sidecar = write(tempDirectory, "video.info.json")
            val output = listOf(
                "[download] Destination: '${media.absolutePath}'",
                "[info] Writing video subtitles to: '${subtitle.absolutePath}'",
                "[info] Writing metadata to: '${sidecar.absolutePath}'",
                "${DownloadOutputProvenance.PRINT_MARKER}'${media.absolutePath}'",
            ).joinToString("\n")

            assertEquals(
                listOf(media, subtitle, sidecar).map { it.canonicalPath },
                provenance.acceptYtdlpOutput(output)
            )
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun directOutputAcceptsOnlyAnExactNewReportedPath() {
        val directDirectory = Files.createTempDirectory("output-provenance-direct-new-").toFile()
        val unusedTempDirectory = Files.createTempDirectory("output-provenance-unused-").toFile()
        try {
            val preExisting = write(directDirectory, "same.mp4")
            val provenance = DownloadOutputProvenance(
                tempDirectory = unusedTempDirectory,
                directDirectory = directDirectory,
            )
            provenance.beginAttempt()
            val current = write(directDirectory, "same-new.mp4")

            assertTrue(
                provenance.acceptYtdlpOutput("Destination: '${preExisting.absolutePath}'").isEmpty()
            )
            assertEquals(
                listOf(current.canonicalPath),
                provenance.acceptYtdlpOutput("Destination: '${current.absolutePath}'")
            )
        } finally {
            directDirectory.deleteRecursively()
            unusedTempDirectory.deleteRecursively()
        }
    }

    @Test
    fun exactMoveResultsAndDerivedOutputsCarryLineage() {
        val tempDirectory = Files.createTempDirectory("output-provenance-lineage-").toFile()
        val destinationDirectory = Files.createTempDirectory("output-provenance-destination-").toFile()
        try {
            val provenance = DownloadOutputProvenance(tempDirectory)
            provenance.beginAttempt()
            val source = write(tempDirectory, "source.mp4")
            assertTrue(
                provenance.acceptYtdlpOutput("Destination: '${source.absolutePath}'").isNotEmpty()
            )

            val moved = write(destinationDirectory, "source (1).mp4")
            assertEquals(
                listOf(moved.canonicalPath),
                provenance.recordMoveResults(
                    paths = listOf(moved.absolutePath),
                    sourcePaths = listOf(source.absolutePath),
                )
            )
            assertTrue(provenance.isAuthoritative(moved.absolutePath))

            val derivative = write(destinationDirectory, "source.burnin.mp4")
            assertEquals(
                derivative.canonicalPath,
                provenance.recordDerivedOutput(derivative.absolutePath, listOf(moved.absolutePath))
            )
            val unrelated = write(destinationDirectory, "unrelated.burnin.mp4")
            assertNull(
                provenance.recordDerivedOutput(unrelated.absolutePath, listOf(unrelated.absolutePath))
            )
            assertNull(
                provenance.recordDerivedOutput(
                    outputPath = unrelated.absolutePath,
                    inputPaths = listOf(moved.absolutePath, unrelated.absolutePath),
                )
            )
            assertFalse(provenance.isAuthoritative(unrelated.absolutePath))

            val foreignMove = write(destinationDirectory, "foreign.mp4")
            val foreignRoot = Files.createTempDirectory("output-provenance-foreign-root-").toFile()
            try {
                assertTrue(
                    provenance.recordMoveResults(
                        listOf(foreignMove.absolutePath),
                        sourcePaths = listOf(foreignRoot.absolutePath),
                    ).isEmpty()
                )
                assertFalse(provenance.isAuthoritative(foreignMove.absolutePath))
            } finally {
                foreignRoot.deleteRecursively()
            }
        } finally {
            tempDirectory.deleteRecursively()
            destinationDirectory.deleteRecursively()
        }
    }

    @Test
    fun partialMoveOrTransformOnlyCarriesExactReportedEffects() {
        val tempDirectory = Files.createTempDirectory("output-provenance-partial-").toFile()
        val destinationDirectory = Files.createTempDirectory("output-provenance-partial-destination-").toFile()
        try {
            val provenance = DownloadOutputProvenance(tempDirectory)
            provenance.beginAttempt()
            val sourceOne = write(tempDirectory, "one.mp4")
            val sourceTwo = write(tempDirectory, "two.mp4")
            provenance.acceptYtdlpOutput(
                "Destination: '${sourceOne.absolutePath}'\nDestination: '${sourceTwo.absolutePath}'"
            )

            val movedOne = write(destinationDirectory, "one (1).mp4")
            val ambientDestination = write(destinationDirectory, "two (1).mp4")
            val returned = provenance.recordMoveResults(
                paths = listOf(movedOne.absolutePath),
                sourcePaths = listOf(sourceOne.absolutePath, sourceTwo.absolutePath),
            )

            assertEquals(listOf(movedOne.canonicalPath), returned)
            assertTrue(provenance.isAuthoritative(movedOne.absolutePath))
            assertTrue(provenance.isAuthoritative(sourceTwo.absolutePath))
            assertFalse(provenance.isAuthoritative(ambientDestination.absolutePath))

            val semanticCandidates = listOf(movedOne.absolutePath, ambientDestination.absolutePath)
                .filter(provenance::isAuthoritative)
            assertEquals(listOf(movedOne.canonicalPath), semanticCandidates)
        } finally {
            tempDirectory.deleteRecursively()
            destinationDirectory.deleteRecursively()
        }
    }

    @Test
    fun unprovenPathsCannotBecomePublishedOrDestructiveCandidates() {
        val tempDirectory = Files.createTempDirectory("output-provenance-consumer-").toFile()
        val destinationDirectory = Files.createTempDirectory("output-provenance-consumer-destination-").toFile()
        try {
            val provenance = DownloadOutputProvenance(tempDirectory)
            provenance.beginAttempt()
            val source = write(tempDirectory, "source.mp4")
            provenance.acceptYtdlpOutput("Destination: '${source.absolutePath}'")
            val published = write(destinationDirectory, "source.mp4")
            provenance.recordMoveResults(
                paths = listOf(published.absolutePath),
                sourcePaths = listOf(source.absolutePath),
            )
            val unrelated = write(destinationDirectory, "unrelated.mp4")

            val finalPaths = listOf(published.absolutePath, unrelated.absolutePath)
                .filter(provenance::isAuthoritative)
            val historyReplacementPaths = finalPaths
                .filter(provenance::isAuthoritative)
            val oldMediaDeletionCandidates = finalPaths
                .filter(provenance::isAuthoritative)

            assertEquals(listOf(published.canonicalPath), finalPaths)
            assertEquals(finalPaths, historyReplacementPaths)
            assertEquals(finalPaths, oldMediaDeletionCandidates)
            assertFalse(provenance.isAuthoritative(unrelated.absolutePath))
        } finally {
            tempDirectory.deleteRecursively()
            destinationDirectory.deleteRecursively()
        }
    }

    @Test
    fun invalidOrUnreportedPathsDoNotEnterTheAttemptSet() {
        val tempDirectory = Files.createTempDirectory("output-provenance-invalid-").toFile()
        try {
            val provenance = DownloadOutputProvenance(tempDirectory)
            provenance.beginAttempt()
            val outside = Files.createTempFile("output-provenance-outside-", ".mp4").toFile()
            try {
                val output = listOf(
                    "normal yt-dlp diagnostic without a path",
                    "Destination: '${File(tempDirectory, "missing.mp4").absolutePath}'",
                    "${DownloadOutputProvenance.PRINT_MARKER}'${outside.absolutePath}'",
                    "relative-output.mp4",
                ).joinToString("\n")
                assertTrue(provenance.acceptYtdlpOutput(output).isEmpty())
                assertTrue(provenance.currentAttemptPaths().isEmpty())
            } finally {
                outside.delete()
            }
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun unprovenTemporaryArtifactsAreRetainedForCleanupPolicyButNeverPromoted() {
        val tempDirectory = Files.createTempDirectory("output-provenance-retain-").toFile()
        try {
            val provenance = DownloadOutputProvenance(tempDirectory)
            provenance.beginAttempt()
            val reported = write(tempDirectory, "reported.mp4")
            val ambient = write(tempDirectory, "ambient.mp4")
            provenance.acceptYtdlpOutput("Destination: '${reported.absolutePath}'")

            assertTrue(provenance.hasUnprovenTemporaryArtifacts())
            assertFalse(provenance.isAuthoritative(ambient.absolutePath))

            ambient.delete()
            assertFalse(provenance.hasUnprovenTemporaryArtifacts())
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun parserKeepsAllOutputBearingRecordsButIgnoresAmbientDiagnostics() {
        val paths = DownloadOutputProvenance.parseYtdlpOutputPaths(
            """
            [download] Destination: '/data/user/0/com.ireum.ytdl/cache/7/video.mp4'
            [info] Merging formats into "/data/user/0/com.ireum.ytdl/cache/7/video.mkv"
            [info] Writing thumbnail to: '/data/user/0/com.ireum.ytdl/cache/7/video.jpg'
            [debug] recent unrelated file /sdcard/Download/other.mp4
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "/data/user/0/com.ireum.ytdl/cache/7/video.mp4",
                "/data/user/0/com.ireum.ytdl/cache/7/video.mkv",
                "/data/user/0/com.ireum.ytdl/cache/7/video.jpg",
            ),
            paths
        )
    }

    private fun write(parent: File, name: String): File {
        val file = File(parent, name)
        file.parentFile?.mkdirs()
        file.writeText("artifact")
        return file
    }
}
