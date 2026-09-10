package com.ireum.ytdl.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProducerRecoveryTest {
    @Test
    fun preparationIsDurableBeforeProducerAndResolvesExactCompatibleGeneration() {
        val root = Files.createTempDirectory("producer-recovery").toFile()
        try {
            val staging = File(root, "staging").apply { mkdirs() }
            val output = File(staging, "video.mp4").apply { writeText("video") }
            val first = DownloadProducerRecovery.prepare(
                storageDirectory = File(root, "journal"),
                downloadId = 42L,
                operationId = "op-1",
                executionId = "e-1",
                semanticFingerprint = "fp-1",
                outputRoot = staging,
            )!!
            assertEquals(DownloadProducerRecovery.Phase.PREPARED, first.phase)
            assertTrue(DownloadProducerRecovery.markRunning(File(root, "journal"), first))
            val complete = DownloadProducerRecovery.markComplete(
                storageDirectory = File(root, "journal"),
                record = first,
                outputPaths = listOf(output.absolutePath),
                archiveDelta = "youtube video-id",
            )!!
            assertEquals(DownloadProducerRecovery.Phase.COMPLETE, complete.phase)
            assertEquals(
                DownloadProducerRecovery.Resolution.Compatible(complete),
                DownloadProducerRecovery.resolve(
                    storageDirectory = File(root, "journal"),
                    downloadId = 42L,
                    currentFingerprint = "fp-1",
                    currentExecutionId = "e-2",
                ),
            )
            assertTrue(DownloadProducerRecovery.retire(File(root, "journal"), complete))
            assertEquals(DownloadProducerRecovery.Resolution.NoPrior,
                DownloadProducerRecovery.resolve(File(root, "journal"), 42L, "fp-1", "e-2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun noOutputCompletionIsAnExactTerminalProducerFact() {
        val root = Files.createTempDirectory("producer-no-output").toFile()
        try {
            val journal = File(root, "journal")
            val record = DownloadProducerRecovery.prepare(
                storageDirectory = journal,
                downloadId = 7L,
                operationId = "op-7",
                executionId = "e-1",
                semanticFingerprint = "archive-hit",
                outputRoot = File(root, "staging"),
            )!!
            assertTrue(DownloadProducerRecovery.markRunning(journal, record))
            val complete = DownloadProducerRecovery.markComplete(journal, record, emptyList())!!
            assertEquals(DownloadProducerRecovery.Phase.NO_OUTPUT_COMPLETE, complete.phase)
            assertEquals(
                DownloadProducerRecovery.Resolution.Compatible(complete),
                DownloadProducerRecovery.resolve(journal, 7L, "archive-hit", "e-2"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun incompatibleAndAmbiguousGenerationsNeverBecomeAdoptionAuthority() {
        val root = Files.createTempDirectory("producer-ambiguous").toFile()
        try {
            val journal = File(root, "journal")
            val staging = File(root, "staging").apply { mkdirs() }
            fun complete(execution: String, fingerprint: String): DownloadProducerRecovery.Record {
                val prepared = DownloadProducerRecovery.prepare(
                    storageDirectory = journal,
                    downloadId = 9L,
                    operationId = "op-$execution",
                    executionId = execution,
                    semanticFingerprint = fingerprint,
                    outputRoot = staging,
                )!!
                check(DownloadProducerRecovery.markRunning(journal, prepared))
                return DownloadProducerRecovery.markComplete(journal, prepared, emptyList())!!
            }
            complete("e-1", "fp-1")
            complete("e-2", "fp-2")
            val latestResolution = DownloadProducerRecovery.resolve(journal, 9L, "fp-1", "e-3")
            assertTrue(latestResolution is DownloadProducerRecovery.Resolution.Incompatible)
            assertEquals(
                "e-2",
                (latestResolution as DownloadProducerRecovery.Resolution.Incompatible)
                    .record.executionId,
            )
            assertEquals(
                listOf("e-1"),
                latestResolution.obsoleteRecords.map { it.executionId },
            )
            assertTrue(
                DownloadProducerRecovery.resolve(journal, 9L, "fp-3", "e-1")
                    is DownloadProducerRecovery.Resolution.Incompatible,
            )
            assertTrue(DownloadProducerRecovery.hasPendingForTests(journal))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateLatestSequenceRemainsAmbiguousRegardlessOfEnumerationOrder() {
        val root = Files.createTempDirectory("producer-duplicate-sequence").toFile()
        try {
            val journal = File(root, "journal")
            val staging = File(root, "staging")
            fun complete(execution: String, fingerprint: String): DownloadProducerRecovery.Record {
                val prepared = DownloadProducerRecovery.prepare(
                    storageDirectory = journal,
                    downloadId = 10L,
                    operationId = "op-$execution",
                    executionId = execution,
                    semanticFingerprint = fingerprint,
                    outputRoot = staging,
                )!!
                check(DownloadProducerRecovery.markRunning(journal, prepared))
                return DownloadProducerRecovery.markComplete(journal, prepared, emptyList())!!
            }
            val first = complete("e-1", "fp-1")
            val second = complete("e-2", "fp-2")
            // Simulate legacy/partial state that assigned the same sequence
            // to two surviving records. The resolution must not fall back to
            // filesystem order or UUID ordering.
            val firstFile = journal.listFiles()!!.first { it.readText().contains(first.generationId) }
            val secondFile = journal.listFiles()!!.first { it.readText().contains(second.generationId) }
            firstFile.writeText(firstFile.readText().replace("\"sequence\":1", "\"sequence\":2"))
            secondFile.writeText(secondFile.readText().replace("\"sequence\":2", "\"sequence\":2"))
            val resolution = DownloadProducerRecovery.resolve(journal, 10L, "fp-1", "e-3")
            assertTrue(resolution is DownloadProducerRecovery.Resolution.Ambiguous)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedRecordIsOpaqueAndCannotBecomeNoPrior() {
        val root = Files.createTempDirectory("producer-opaque").toFile()
        try {
            val journal = File(root, "journal").apply { mkdirs() }
            File(journal, "ytdlnisx-producer-corrupt.json").writeText("{not-json")
            val discovery = DownloadProducerRecovery.discover(journal)
            assertTrue(discovery is DownloadProducerRecovery.DiscoveryResult.Opaque)
            assertTrue(DownloadProducerRecovery.hasPendingForTests(journal))
            assertTrue(
                DownloadProducerRecovery.resolve(journal, 1L, "fp", "e")
                    is DownloadProducerRecovery.Resolution.Opaque,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun supersededExecutionCanEstablishAReplacementGeneration() {
        val root = Files.createTempDirectory("producer-superseded").toFile()
        try {
            val journal = File(root, "journal")
            val staging = File(root, "staging")
            val first = DownloadProducerRecovery.prepare(
                storageDirectory = journal,
                downloadId = 11L,
                operationId = "op-1",
                executionId = "e-1",
                semanticFingerprint = "fp-1",
                outputRoot = staging,
            )!!
            assertTrue(DownloadProducerRecovery.markRunning(journal, first))
            assertTrue(DownloadProducerRecovery.markSuperseded(journal, first))

            val replacement = DownloadProducerRecovery.prepare(
                storageDirectory = journal,
                downloadId = 11L,
                operationId = "op-2",
                executionId = "e-1",
                semanticFingerprint = "fp-2",
                outputRoot = staging,
            )!!
            assertNotEquals(first.generationId, replacement.generationId)
            assertEquals(2L, replacement.sequence)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completeGenerationRemainsAdoptableAfterAbandonedRowRequeue() {
        val root = Files.createTempDirectory("producer-complete-retention").toFile()
        try {
            val journal = File(root, "journal")
            val staging = File(root, "staging").apply { mkdirs() }
            val output = File(staging, "video.mp4").apply { writeText("video") }
            val prepared = DownloadProducerRecovery.prepare(
                storageDirectory = journal,
                downloadId = 12L,
                operationId = "op-e1",
                executionId = "e-1",
                semanticFingerprint = "fp-compatible",
                outputRoot = staging,
            )!!
            assertTrue(DownloadProducerRecovery.markRunning(journal, prepared))
            val complete = DownloadProducerRecovery.markComplete(
                storageDirectory = journal,
                record = prepared,
                outputPaths = listOf(output.absolutePath),
            )!!

            // Model repeated startup passes after the mutable E1 row was
            // requeued.  COMPLETE is still an adoption predecessor and must
            // not become an admission fence or disposable staging debt.
            repeat(3) {
                assertEquals(
                    DownloadProducerRecovery.Resolution.Compatible(complete),
                    DownloadProducerRecovery.resolve(
                        storageDirectory = journal,
                        downloadId = 12L,
                        currentFingerprint = "fp-compatible",
                        currentExecutionId = "e-2",
                    ),
                )
                assertFalse(
                    DownloadProducerRecovery.hasBlockingForAdmissionForTests(journal, 12L),
                )
                assertTrue(output.isFile)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun weakerProducerPhasesRemainAdmissionBlocking() {
        val root = Files.createTempDirectory("producer-admission-block").toFile()
        try {
            val journal = File(root, "journal")
            val prepared = DownloadProducerRecovery.prepare(
                storageDirectory = journal,
                downloadId = 13L,
                operationId = "op-e1",
                executionId = "e-1",
                semanticFingerprint = "fp",
                outputRoot = File(root, "staging"),
            )!!
            assertTrue(DownloadProducerRecovery.hasBlockingForAdmissionForTests(journal, 13L))
            assertTrue(DownloadProducerRecovery.markRunning(journal, prepared))
            assertTrue(DownloadProducerRecovery.hasBlockingForAdmissionForTests(journal, 13L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completeFinalityIsRetainedUntilStrongerAuthorityExists() {
        repeat(3) {
            assertTrue(
                shouldRetainCompleteProducerFinality(
                    phase = DownloadProducerRecovery.Phase.COMPLETE,
                    publicationExists = false,
                    primarySuccess = false,
                ),
            )
        }
        assertFalse(
            shouldRetainCompleteProducerFinality(
                phase = DownloadProducerRecovery.Phase.COMPLETE,
                publicationExists = true,
                primarySuccess = false,
            ),
        )
        assertFalse(
            shouldRetainCompleteProducerFinality(
                phase = DownloadProducerRecovery.Phase.COMPLETE,
                publicationExists = false,
                primarySuccess = true,
            ),
        )
        assertFalse(
            shouldRetainCompleteProducerFinality(
                phase = DownloadProducerRecovery.Phase.RUNNING,
                publicationExists = false,
                primarySuccess = false,
            ),
        )
    }
}
