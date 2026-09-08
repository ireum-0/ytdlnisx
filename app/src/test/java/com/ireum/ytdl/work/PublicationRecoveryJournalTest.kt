package com.ireum.ytdl.work

import com.ireum.ytdl.util.storage.CacheImportPlanner
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PublicationRecoveryJournalTest {
    @Test
    fun exactSourceDestinationPairsSurviveRestartUntilExplicitRetirement() {
        val root = Files.createTempDirectory("publication-journal-").toFile()
        val storage = File(root, "journal")
        try {
            val first = File(root, "first.mp4").apply { writeText("first") }
            val second = File(root, "second.srt").apply { writeText("second") }
            val handle = PublicationRecoveryJournal.begin(
                storageDirectory = storage,
                kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                subjectId = "42",
                operationId = "operation-1",
                executionId = "execution-1",
                attemptId = "attempt-1",
                sourceRoot = root,
                sourceFiles = listOf(first, second),
            )
            assertTrue(handle != null)
            assertTrue(handle!!.markPublished(first.absolutePath, "content://media/first"))

            val recovered = PublicationRecoveryJournal.readAll(storage).single()
            assertEquals(PublicationRecoveryJournal.Phase.PARTIAL, recovered.phase)
            assertEquals(listOf("content://media/first"), recovered.publishedDestinations())
            assertEquals(listOf(second.canonicalPath), recovered.remainingSources())
            assertEquals(
                listOf(recovered),
                PublicationRecoveryJournal.readAll(storage),
            )
            assertFalse(handle.clear())
            assertTrue(PublicationRecoveryJournal.readAll(storage).isNotEmpty())

            assertTrue(handle.markPublished(second.absolutePath, "/published/second.srt"))
            assertEquals(PublicationRecoveryJournal.Phase.COMPLETE, handle.snapshot().phase)
            assertTrue(handle.clear())
            assertTrue(PublicationRecoveryJournal.readAll(storage).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reservedDestinationSurvivesCrashWindowBeforePublishedCallback() {
        val root = Files.createTempDirectory("publication-journal-reserved-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "output.mp4").apply { writeText("output") }
            val destination = File(root, "published/output.mp4")
            val handle = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "9",
                    operationId = "operation-reserved",
                    executionId = "execution-1",
                    attemptId = "attempt-1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(handle.reserve(source.absolutePath, destination.absolutePath))

            val recovered = PublicationRecoveryJournal.readAll(storage).single()
            assertEquals(
                listOf(destination.canonicalPath),
                recovered.reservedDestinations(),
            )
            assertEquals(listOf(source.canonicalPath), recovered.remainingSources())

            destination.parentFile?.mkdirs()
            destination.writeText("published")
            assertTrue(handle.markPublished(source.absolutePath, destination.absolutePath))
            assertEquals(
                listOf(destination.canonicalPath),
                handle.snapshot().publishedDestinations(),
            )
            assertTrue(handle.snapshot().remainingSources().isEmpty())
            assertTrue(handle.clear())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absentReservationCanBeReboundForAProviderOrCollisionSafeRetry() {
        val root = Files.createTempDirectory("publication-journal-rebind-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "output.mp4").apply { writeText("output") }
            val firstDestination = File(root, "published/first.mp4")
            val secondDestination = File(root, "published/second.mp4")
            val handle = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "10",
                    operationId = "operation-rebind",
                    executionId = "execution-1",
                    attemptId = "attempt-1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(handle.reserve(source.absolutePath, firstDestination.absolutePath))
            assertTrue(handle.clearReservation(source.absolutePath))
            assertTrue(handle.reserve(source.absolutePath, secondDestination.absolutePath))
            assertTrue(handle.markPublished(source.absolutePath, secondDestination.absolutePath))
            assertEquals(
                listOf(secondDestination.canonicalPath),
                handle.snapshot().publishedDestinations(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleExecutionCannotMatchAnotherDownloadOperation() {
        val root = Files.createTempDirectory("publication-journal-identity-").toFile()
        try {
            val source = File(root, "output.mp4").apply { writeText("output") }
            assertTrue(
                PublicationRecoveryJournal.begin(
                    storageDirectory = File(root, "journal"),
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "7",
                    operationId = "operation-e1",
                    executionId = "execution-e1",
                    attemptId = "attempt-e1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                ) != null,
            )
            val records = PublicationRecoveryJournal.readAll(File(root, "journal"))
            assertTrue(
                records.none {
                    it.subjectId == "7" && it.operationId == "operation-e2"
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun markerRevokedTerminalRemainderIsExplicitlyDiscoverableButNotLiveImport() {
        val root = Files.createTempDirectory("terminal-recovery-").toFile()
        try {
            val directory = File(root, "TERMINAL/task-1").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, "task-1")
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory,
                    "task-1",
                    listOf("content://media/published"),
                )
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, "task-1"))

            assertTrue(CacheImportPlanner.collect(root).isEmpty())
            val recovery = CacheImportPlanner.collectRecovery(root)
            assertEquals(1, recovery.size)
            assertEquals(listOf(source.canonicalPath), recovery.single().remainingSourcePaths)
            assertEquals(
                listOf("content://media/published"),
                recovery.single().publishedDestinationPaths,
            )
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalRecoveryCarrierCannotBeReusedByAnotherTaskToken() {
        val root = Files.createTempDirectory("terminal-recovery-reuse-").toFile()
        try {
            val directory = File(root, "TERMINAL/task-1").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, "task-1")
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            assertTrue(TerminalCacheOwnership.recordRecoveryCarrier(directory, "task-1"))
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, "task-1"))

            try {
                TerminalCacheOwnership.ensureMarker(directory, "task-2")
                fail("a marker-revoked recovery root must not be reused")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("recovery carrier"))
            }
            assertTrue(source.isFile)
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(directory).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun startupReconcilesJournalBoundTerminalRootIntoRecoveryOnlyCarrier() {
        val root = Files.createTempDirectory("terminal-journal-startup-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "task-startup"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "7",
                    operationId = "terminal-7",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.markPhase(PublicationRecoveryJournal.Phase.PUBLISHING))

            val result = TerminalPublicationRecovery.reconcile(root, journalStorage)

            assertEquals(1, result.journalCount)
            assertEquals(1, result.quarantinedCount)
            assertFalse(TerminalCacheOwnership.markerFile(directory).exists())
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(directory).isFile)
            assertTrue(source.isFile)
            assertTrue(CacheImportPlanner.collect(root).isEmpty())
            assertEquals(1, CacheImportPlanner.collectRecovery(root).size)
            assertTrue(PublicationRecoveryJournal.readAll(journalStorage).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedTerminalFailureRetiresOnlyExactQuarantineRemainder() {
        val root = Files.createTempDirectory("terminal-recovery-consumer-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "12-recovery"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val remainder = File(directory, "remainder.mp4").apply { writeText("remainder") }
            val unknown = File(directory, "unknown.bin").apply { writeText("preserve") }
            val published = File(root, "published/remainder.mp4").apply {
                parentFile?.mkdirs()
                writeText("published")
            }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(remainder.absolutePath)))
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "12",
                    publishedDestinationPaths = listOf(published.absolutePath),
                    phase = "QUARANTINED_FAILURE",
                )
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, taskToken))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { false },
            )

            assertEquals(1, result.retiredCount)
            assertFalse(remainder.exists())
            assertTrue(unknown.isFile)
            assertTrue(published.isFile)
            assertFalse(TerminalCacheOwnership.recoveryCarrierFile(directory).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun liveTerminalRowPreservesQuarantineForLaterRecovery() {
        val root = Files.createTempDirectory("terminal-recovery-live-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "13-recovery"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val remainder = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(remainder.absolutePath)))
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "13",
                    phase = "QUARANTINED_FAILURE",
                )
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, taskToken))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { true },
            )

            assertEquals(0, result.retiredCount)
            assertTrue(remainder.isFile)
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(directory).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun activeTerminalRowKeepsLiveJournalAndMarkerForWorkerOwnership() {
        val root = Files.createTempDirectory("terminal-recovery-active-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "14-active"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val output = File(directory, "output.mp4").apply { writeText("output") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(output.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "14",
                    operationId = "terminal-14",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(output),
                )
            )
            assertTrue(journal.markPhase(PublicationRecoveryJournal.Phase.PUBLISHING))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { true },
            )

            assertEquals(0, result.quarantinedCount)
            assertTrue(TerminalCacheOwnership.markerFile(directory).isFile)
            assertTrue(PublicationRecoveryJournal.readAll(journalStorage).isNotEmpty())
            assertTrue(output.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun committedTerminalJournalIsRetiredAfterStagingRootDisappears() {
        val root = Files.createTempDirectory("terminal-committed-journal-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "task-committed"
            val staging = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(staging, "video.mp4").apply { writeText("video") }
            val destination = File(root, "published/video.mp4").apply {
                parentFile?.mkdirs()
                writeText("published")
            }
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "11",
                    operationId = "terminal-11",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = staging,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.markPublished(source.absolutePath, destination.absolutePath))
            assertTrue(journal.markPhase(PublicationRecoveryJournal.Phase.COMMITTED))
            staging.deleteRecursively()

            val result = TerminalPublicationRecovery.reconcile(root, journalStorage)

            assertEquals(1, result.journalCount)
            assertEquals(1, result.retiredCount)
            assertTrue(destination.isFile)
            assertTrue(PublicationRecoveryJournal.readAll(journalStorage).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedTerminalRecoveryCarrierKeepsLiveMarkerForFailClosedRetry() {
        val root = Files.createTempDirectory("terminal-journal-malformed-carrier-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "task-malformed"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            TerminalCacheOwnership.recoveryCarrierFile(directory).writeText("not-json\n")
            requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "8",
                    operationId = "terminal-8",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )

            val result = TerminalPublicationRecovery.reconcile(root, journalStorage)

            assertEquals(1, result.journalCount)
            assertEquals(0, result.quarantinedCount)
            assertTrue(TerminalCacheOwnership.markerFile(directory).isFile)
            assertEquals("not-json\n", TerminalCacheOwnership.recoveryCarrierFile(directory).readText())
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
