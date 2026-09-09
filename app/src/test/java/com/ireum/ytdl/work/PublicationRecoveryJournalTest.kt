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
    fun discoveryDistinguishesHealthyEmptyFromUnavailableAndOpaqueDebt() {
        val root = Files.createTempDirectory("publication-discovery-").toFile()
        try {
            val absent = File(root, "absent")
            assertTrue(
                PublicationRecoveryJournal.discover(absent) is
                    PublicationRecoveryJournal.DiscoveryResult.Healthy,
            )

            val empty = File(root, "empty").apply { mkdirs() }
            val healthy = PublicationRecoveryJournal.discover(empty)
            assertTrue(healthy is PublicationRecoveryJournal.DiscoveryResult.Healthy)
            assertTrue(healthy.records.isEmpty())

            val notDirectory = File(root, "not-directory").apply { writeText("state") }
            assertTrue(
                PublicationRecoveryJournal.discover(notDirectory) is
                    PublicationRecoveryJournal.DiscoveryResult.Unavailable,
            )

            val opaque = File(empty, "ytdlnisx-publication-corrupt.json").apply {
                writeText("{not-json")
            }
            val opaqueResult = PublicationRecoveryJournal.discover(empty)
            assertTrue(opaqueResult is PublicationRecoveryJournal.DiscoveryResult.Opaque)
            val opaqueState = opaqueResult as PublicationRecoveryJournal.DiscoveryResult.Opaque
            assertTrue(opaqueState.opaqueFiles.contains(opaque.absolutePath))
            assertTrue(opaqueState.records.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoveryRetainsHealthyRecordsAlongsideOpaqueDebt() {
        val root = Files.createTempDirectory("publication-discovery-mixed-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "source.mp4").apply { writeText("source") }
            requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "1",
                    operationId = "op",
                    executionId = "exec",
                    attemptId = "attempt",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )
            val opaque = File(storage, "ytdlnisx-publication-opaque.json").apply {
                writeText("unknown-schema")
            }

            val result = PublicationRecoveryJournal.discover(storage)
            assertTrue(result is PublicationRecoveryJournal.DiscoveryResult.Opaque)
            val opaqueState = result as PublicationRecoveryJournal.DiscoveryResult.Opaque
            assertEquals(1, opaqueState.records.size)
            assertTrue(opaqueState.opaqueFiles.contains(opaque.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

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
    fun reservedRawDestinationIsNotPromotedWhileSourceStillExists() {
        val root = Files.createTempDirectory("publication-journal-raw-proof-").toFile()
        try {
            val source = File(root, "staging/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("output")
            }
            val destination = File(root, "published/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("possibly partial")
            }

            assertFalse(
                com.ireum.ytdl.util.FileUtil.isRecoverablePublicationComplete(
                    sourcePath = source.absolutePath,
                    destinationPath = destination.absolutePath,
                    context = null,
                )
            )
            assertTrue(source.delete())
            assertTrue(
                com.ireum.ytdl.util.FileUtil.isRecoverablePublicationComplete(
                    sourcePath = source.absolutePath,
                    destinationPath = destination.absolutePath,
                    context = null,
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reservedProviderDestinationNeverGainsAuthorityFromExistenceAlone() {
        val root = Files.createTempDirectory("publication-journal-provider-proof-").toFile()
        try {
            val source = File(root, "staging/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("output")
            }
            assertFalse(
                com.ireum.ytdl.util.FileUtil.isRecoverablePublicationComplete(
                    sourcePath = source.absolutePath,
                    destinationPath = "content://com.example.documents/document/42",
                    context = null,
                )
            )
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun providerReservationIntentFencesRetryUntilExactUriIsRecorded() {
        val root = Files.createTempDirectory("publication-journal-intent-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "staging/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("output")
            }
            val handle = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "12",
                    operationId = "operation-intent",
                    executionId = "execution-1",
                    attemptId = "attempt-1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(handle.reserveIntent(source.absolutePath))
            val intent = handle.snapshot().reservedDestinations().single()
            assertTrue(PublicationRecoveryJournal.isReservationIntent(intent))
            assertFalse(handle.reserveIntent(source.absolutePath))
            assertFalse(
                com.ireum.ytdl.util.FileUtil.isRecoverablePublicationComplete(
                    sourcePath = source.absolutePath,
                    destinationPath = intent,
                    context = null,
                )
            )

            // The provider's exact URI is the only value that may replace the
            // intent and become a publication destination.
            assertTrue(handle.reserve(source.absolutePath, "content://media/exact/12"))
            assertEquals(
                listOf("content://media/exact/12"),
                handle.snapshot().reservedDestinations(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exactDestinationCannotAuthorizeAnotherProviderCreateIntent() {
        val root = Files.createTempDirectory("publication-journal-exact-replay-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "staging/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("output")
            }
            val handle = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "16",
                    operationId = "operation-exact-replay",
                    executionId = "execution-1",
                    attemptId = "attempt-1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )
            val exact = "content://media/exact/16"
            assertTrue(handle.reserve(source.absolutePath, exact))
            assertTrue(handle.markPublished(source.absolutePath, exact))
            assertFalse(handle.reserveIntent(source.absolutePath))
            assertEquals(exact, handle.snapshot().artifacts.single().destinationPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownProviderReservationIsDurableAndCannotBeClearedOrReplayed() {
        val root = Files.createTempDirectory("publication-journal-unknown-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "staging/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("output")
            }
            val handle = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "13",
                    operationId = "operation-unknown",
                    executionId = "execution-1",
                    attemptId = "attempt-1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )

            assertTrue(handle.reserveIntent(source.absolutePath))
            assertTrue(handle.markReservationUnknown(source.absolutePath))
            val unknown = handle.snapshot().reservedDestinations().single()
            assertTrue(PublicationRecoveryJournal.isUnknownReservation(unknown))
            assertFalse(PublicationRecoveryJournal.isReservationIntent(unknown))

            // UNKNOWN means the provider may have created an opaque object;
            // clearing the fence or replaying creation could lose the only
            // duplicate-prevention authority.
            assertFalse(handle.clearReservation(source.absolutePath))
            assertFalse(handle.reserveIntent(source.absolutePath))
            assertFalse(handle.reserve(source.absolutePath, "content://media/exact/13"))
            assertFalse(handle.markPublished(source.absolutePath, "content://media/exact/13"))
            assertTrue(source.isFile)

            val recovered = PublicationRecoveryJournal.readAll(storage).single()
            val recoveredUnknown = recovered.artifacts.single().reservedDestinationPath
            assertTrue(PublicationRecoveryJournal.isUnknownReservation(recoveredUnknown))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownProviderReservationCanBeTerminalizedWithoutLosingItsFence() {
        val root = Files.createTempDirectory("publication-journal-unknown-terminal-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "staging/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("output")
            }
            val handle = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "14",
                    operationId = "operation-unknown-terminal",
                    executionId = "execution-1",
                    attemptId = "attempt-1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(handle.reserveIntent(source.absolutePath))
            assertTrue(handle.markReservationUnknown(source.absolutePath))
            assertTrue(handle.terminalizeUnknownReservation())
            assertTrue(handle.terminalizeUnknownReservation())
            assertFalse(handle.markPhase(PublicationRecoveryJournal.Phase.PARTIAL))
            assertFalse(handle.clear())

            val recovered = PublicationRecoveryJournal.readAll(storage).single()
            assertEquals(
                PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN,
                recovered.phase,
            )
            val reopened = requireNotNull(PublicationRecoveryJournal.open(storage, recovered))
            assertFalse(reopened.clear())
            assertFalse(reopened.reserveIntent(source.absolutePath))
            assertFalse(reopened.reserve(source.absolutePath, "content://media/rebound/14"))
            assertFalse(reopened.markPublished(source.absolutePath, "content://media/rebound/14"))
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pendingProviderIntentConvergesConservativelyWhenCallBoundaryIsUnknown() {
        val root = Files.createTempDirectory("publication-journal-intent-terminal-").toFile()
        val storage = File(root, "journal")
        try {
            val source = File(root, "staging/output.mp4").apply {
                parentFile?.mkdirs()
                writeText("output")
            }
            val handle = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = storage,
                    kind = PublicationRecoveryJournal.Kind.DOWNLOAD,
                    subjectId = "15",
                    operationId = "operation-intent-terminal",
                    executionId = "execution-1",
                    attemptId = "attempt-1",
                    sourceRoot = root,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(handle.reserveIntent(source.absolutePath))
            assertTrue(handle.terminalizeUnknownReservation())

            val recovered = PublicationRecoveryJournal.readAll(storage).single()
            assertEquals(
                PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN,
                recovered.phase,
            )
            assertTrue(
                PublicationRecoveryJournal.isUnknownReservation(
                    recovered.artifacts.single().reservedDestinationPath,
                )
            )
            assertFalse(handle.clearReservation(source.absolutePath))
            assertFalse(handle.reserveIntent(source.absolutePath))
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalUnknownReservationConvergesToQuarantineAndReconcileIsIdempotent() {
        val root = Files.createTempDirectory("terminal-unknown-convergence-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "14-unknown"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "14",
                    operationId = "terminal-14",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.reserveIntent(source.absolutePath))
            assertTrue(journal.markReservationUnknown(source.absolutePath))
            assertTrue(journal.terminalizeUnknownReservation())
            assertFalse(journal.clear())
            // Simulate the crash-equivalent dual-evidence window: the
            // successor carrier is durable while the journal still exists.
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "14",
                    phase = "QUARANTINED_UNKNOWN",
                )
            )

            val first = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { true },
                allowUnknownWithoutRow = true,
            )

            assertEquals(1, first.journalCount)
            assertEquals(1, first.quarantinedCount)
            assertEquals(1, first.retiredCount)
            assertTrue(source.isFile)
            assertFalse(TerminalCacheOwnership.markerFile(directory).exists())
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(directory).isFile)
            assertEquals(
                "QUARANTINED_UNKNOWN",
                TerminalCacheOwnership.listRecoveryRoots(root).single().phase,
            )
            assertTrue(PublicationRecoveryJournal.readAll(journalStorage).isEmpty())

            val second = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { true },
                allowUnknownWithoutRow = true,
            )
            assertEquals(0, second.journalCount)
            assertTrue(source.isFile)
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(directory).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownCarrierWithAbsentTerminalRowRemainsTerminalAndDiscoverable() {
        val root = Files.createTempDirectory("terminal-unknown-carrier-absent-row-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "15-unknown-carrier"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "15",
                    operationId = "terminal-15",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.reserveIntent(source.absolutePath))
            assertTrue(journal.markReservationUnknown(source.absolutePath))
            assertTrue(journal.terminalizeUnknownReservation())
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "15",
                    phase = "QUARANTINED_UNKNOWN",
                )
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, taskToken))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { false },
                allowUnknownWithoutRow = true,
            )

            assertEquals(1, result.journalCount)
            assertEquals(1, result.retiredCount)
            assertTrue(source.isFile)
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(directory).isFile)
            assertTrue(PublicationRecoveryJournal.readAll(journalStorage).isEmpty())
            assertEquals(
                "QUARANTINED_UNKNOWN",
                TerminalCacheOwnership.listRecoveryRoots(root).single().phase,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun genericCarrierCannotReplaceUnknownTerminalJournal() {
        val root = Files.createTempDirectory("terminal-unknown-generic-carrier-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "16-unknown-generic"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "16",
                    operationId = "terminal-16",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.reserveIntent(source.absolutePath))
            assertTrue(journal.markReservationUnknown(source.absolutePath))
            assertTrue(journal.terminalizeUnknownReservation())
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "16",
                    phase = "PARTIAL_PUBLICATION",
                )
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, taskToken))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { true },
                allowUnknownWithoutRow = true,
            )

            assertEquals(1, result.journalCount)
            assertEquals(0, result.retiredCount)
            assertEquals(
                PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN,
                PublicationRecoveryJournal.readAll(journalStorage).single().phase,
            )
            assertEquals(
                "PARTIAL_PUBLICATION",
                TerminalCacheOwnership.listRecoveryRoots(root).single().phase,
            )
            val retained = requireNotNull(
                PublicationRecoveryJournal.open(
                    journalStorage,
                    PublicationRecoveryJournal.readAll(journalStorage).single(),
                )
            )
            assertFalse(retained.clear())
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun genericCarrierCannotRetireUnknownJournalWhenTerminalRowIsAbsent() {
        val root = Files.createTempDirectory("terminal-unknown-generic-absent-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "17-unknown-generic"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "17",
                    operationId = "terminal-17",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.reserveIntent(source.absolutePath))
            assertTrue(journal.markReservationUnknown(source.absolutePath))
            assertTrue(journal.terminalizeUnknownReservation())
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "17",
                    phase = "PARTIAL_PUBLICATION",
                )
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, taskToken))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { false },
                allowUnknownWithoutRow = true,
            )

            assertEquals(1, result.journalCount)
            assertEquals(0, result.retiredCount)
            assertEquals(
                PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN,
                PublicationRecoveryJournal.readAll(journalStorage).single().phase,
            )
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(directory).isFile)
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun liveGenericCarrierIsUpgradedToUnknownBeforeJournalRetirement() {
        val root = Files.createTempDirectory("terminal-unknown-upgrade-carrier-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "20-unknown-upgrade"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "20",
                    operationId = "terminal-20",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.reserveIntent(source.absolutePath))
            assertTrue(journal.markReservationUnknown(source.absolutePath))
            assertTrue(journal.terminalizeUnknownReservation())
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "20",
                    phase = "PARTIAL_PUBLICATION",
                )
            )

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { false },
                allowUnknownWithoutRow = true,
            )

            assertEquals(1, result.quarantinedCount)
            assertEquals(1, result.retiredCount)
            assertTrue(PublicationRecoveryJournal.readAll(journalStorage).isEmpty())
            assertEquals(
                "QUARANTINED_UNKNOWN",
                TerminalCacheOwnership.listRecoveryRoots(root).single().phase,
            )
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedTerminalCarrierCannotReplaceUnknownJournal() {
        val root = Files.createTempDirectory("terminal-unknown-mismatched-carrier-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "18-unknown-mismatch"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "18",
                    operationId = "terminal-18",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.reserveIntent(source.absolutePath))
            assertTrue(journal.markReservationUnknown(source.absolutePath))
            assertTrue(journal.terminalizeUnknownReservation())
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "18",
                    phase = "QUARANTINED_UNKNOWN",
                )
            )
            val carrier = TerminalCacheOwnership.recoveryCarrierFile(directory)
            carrier.writeText(
                carrier.readText().replace(
                    "\"taskToken\":\"$taskToken\"",
                    "\"taskToken\":\"other-token\"",
                )
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, taskToken))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { true },
                allowUnknownWithoutRow = true,
            )

            assertEquals(1, result.journalCount)
            assertEquals(0, result.retiredCount)
            assertEquals(
                PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN,
                PublicationRecoveryJournal.readAll(journalStorage).single().phase,
            )
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedCarrierLeavesUnknownJournalAuthoritative() {
        val root = Files.createTempDirectory("terminal-unknown-malformed-carrier-").toFile()
        val journalStorage = File(root, "journal")
        try {
            val taskToken = "19-unknown-malformed"
            val directory = File(root, "TERMINAL/$taskToken").apply { mkdirs() }
            val source = File(directory, "remainder.mp4").apply { writeText("remainder") }
            TerminalCacheOwnership.ensureMarker(directory, taskToken)
            assertTrue(TerminalCacheOwnership.recordArtifacts(directory, listOf(source.absolutePath)))
            val journal = requireNotNull(
                PublicationRecoveryJournal.begin(
                    storageDirectory = journalStorage,
                    kind = PublicationRecoveryJournal.Kind.TERMINAL,
                    subjectId = "19",
                    operationId = "terminal-19",
                    executionId = taskToken,
                    attemptId = taskToken,
                    sourceRoot = directory,
                    sourceFiles = listOf(source),
                )
            )
            assertTrue(journal.reserveIntent(source.absolutePath))
            assertTrue(journal.markReservationUnknown(source.absolutePath))
            assertTrue(journal.terminalizeUnknownReservation())
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = taskToken,
                    subjectId = "19",
                    phase = "QUARANTINED_UNKNOWN",
                )
            )
            TerminalCacheOwnership.recoveryCarrierFile(directory).writeText("not-json\n")
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(directory, taskToken))

            val result = TerminalPublicationRecovery.reconcile(
                cacheRoot = root,
                journalStorage = journalStorage,
                terminalRowExists = { false },
                allowUnknownWithoutRow = true,
            )

            assertEquals(1, result.journalCount)
            assertEquals(0, result.retiredCount)
            assertEquals(
                PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN,
                PublicationRecoveryJournal.readAll(journalStorage).single().phase,
            )
            assertTrue(source.isFile)
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
    fun committedTerminalJournalRemainsAsIdempotenceTombstoneAfterStagingRootDisappears() {
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
            // Startup reconciliation may retire the staging root, but it must
            // retain the exact COMMITTED journal until a subsequent Terminal
            // admission consumes it.  Clearing it here would reopen the
            // process-death window immediately after DAO deletion.
            assertEquals(
                PublicationRecoveryJournal.Phase.COMMITTED,
                PublicationRecoveryJournal.readAll(journalStorage).single().phase,
            )
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

    @Test
    fun malformedTerminalRecoveryCarrierIsOpaqueRatherThanHealthyEmpty() {
        val root = Files.createTempDirectory("terminal-recovery-discovery-").toFile()
        try {
            val terminal = File(root, "TERMINAL/task-opaque").apply { mkdirs() }
            TerminalCacheOwnership.recoveryCarrierFile(terminal).writeText("not-json")
            val result = TerminalCacheOwnership.discoverRecoveryRoots(root)
            assertTrue(result is TerminalCacheOwnership.RecoveryDiscovery.Opaque)
            val opaque = result as TerminalCacheOwnership.RecoveryDiscovery.Opaque
            assertTrue(opaque.opaqueDirectories.contains(terminal.absolutePath))
            assertTrue(opaque.roots.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
