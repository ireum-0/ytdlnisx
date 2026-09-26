package com.ireum.ytdl.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExecutionRecoveryTest {
    @Test
    fun durableWitnessIsPublishedBeforeNativeStartAndSurvivesReload() {
        val root = Files.createTempDirectory("terminal-execution-witness-").toFile()
        try {
            assertTrue(
                TerminalExecutionRecovery.begin(
                    storageDirectory = root,
                    subjectId = 41L,
                    executionToken = "41-e1",
                    processId = "terminal:41",
                ),
            )
            assertEquals(
                TerminalExecutionRecovery.Phase.ADMITTED,
                TerminalExecutionRecovery.read(root, 41L)?.phase,
            )
            assertTrue(
                TerminalExecutionRecovery.markNativeStarted(root, 41L, "41-e1"),
            )
            assertEquals(
                TerminalExecutionRecovery.Phase.NATIVE_STARTED,
                TerminalExecutionRecovery.read(root, 41L)?.phase,
            )
            assertEquals(
                "41-e1",
                TerminalExecutionRecovery.readAll(root).single().executionToken,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fixedTokenTransitionsAreMonotonicAndStaleTokenCannotMutateGeneration() {
        val root = Files.createTempDirectory("terminal-execution-monotonic-").toFile()
        var effectLease: TerminalExecutionRecovery.EffectLease? = null
        try {
            assertTrue(TerminalExecutionRecovery.begin(root, 42L, "42-e1", "terminal:42"))
            assertFalse(TerminalExecutionRecovery.markNativeStarted(root, 42L, "42-e2"))
            assertTrue(TerminalExecutionRecovery.markNativeStarted(root, 42L, "42-e1"))
            assertTrue(TerminalExecutionRecovery.bindNativeGeneration(root, 42L, "42-e1", "g1"))
            assertFalse(TerminalExecutionRecovery.bindNativeGeneration(root, 42L, "42-e1", "g2"))
            assertTrue(TerminalExecutionRecovery.markNativeFinished(root, 42L, "42-e1"))
            effectLease = requireNotNull(
                TerminalExecutionRecovery.beginPostNativeEffects(root, 42L, "42-e1"),
            )
            assertTrue(TerminalExecutionRecovery.markCommitting(root, 42L, "42-e1"))
            assertTrue(TerminalExecutionRecovery.markCommitted(root, 42L, "42-e1"))
            effectLease?.close()
            assertEquals(
                TerminalExecutionRecovery.Phase.COMMITTED,
                TerminalExecutionRecovery.read(root, 42L)?.phase,
            )
            assertFalse(TerminalExecutionRecovery.begin(root, 42L, "42-e2", "terminal:42"))
        } finally {
            effectLease?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun preEffectSchemaRecordRemainsReadableAfterAddingPostNativePhases() {
        val root = Files.createTempDirectory("terminal-execution-legacy-phase-").toFile()
        try {
            File(root, "ytdlnisx-terminal-execution-51.json").writeText(
                """{"version":1,"subjectId":51,"executionToken":"51-e1","processId":"terminal:51","phase":"NATIVE_FINISHED","outcome":null,"nativeGenerationToken":null}""",
            )
            val record = TerminalExecutionRecovery.read(root, 51L)
            assertNotNull(record)
            assertEquals(1, record?.version)
            assertEquals(TerminalExecutionRecovery.Phase.NATIVE_FINISHED, record?.phase)
            assertTrue(record!!.nativeQuiescent)
            assertTrue(record.postNativeEffectsQuiescent)
            assertTrue(TerminalExecutionRecovery.markTerminalFailure(root, 51L, "51-e1"))
            assertEquals(2, TerminalExecutionRecovery.read(root, 51L)?.version)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationBeforePublicationWinsWithoutDeclaringEffectQuiescenceEarly() {
        val root = Files.createTempDirectory("terminal-effect-cancel-").toFile()
        var lease: TerminalExecutionRecovery.EffectLease? = null
        try {
            assertTrue(TerminalExecutionRecovery.begin(root, 48L, "48-e1", "terminal:48"))
            assertTrue(TerminalExecutionRecovery.markNativeStarted(root, 48L, "48-e1"))
            assertTrue(TerminalExecutionRecovery.markNativeFinished(root, 48L, "48-e1"))
            lease = requireNotNull(
                TerminalExecutionRecovery.beginPostNativeEffects(root, 48L, "48-e1"),
            )
            assertTrue(TerminalExecutionRecovery.read(root, 48L)!!.nativeQuiescent)
            assertFalse(TerminalExecutionRecovery.read(root, 48L)!!.postNativeEffectsQuiescent)
            assertTrue(
                TerminalExecutionRecovery.requestPostNativeOutcome(
                    root,
                    48L,
                    "48-e1",
                    TerminalExecutionRecovery.Outcome.STOPPED,
                ),
            )
            val effectLease = requireNotNull(lease)
            assertFalse(TerminalExecutionRecovery.markPublicationStarted(root, 48L, "48-e1", effectLease))
            assertFalse(TerminalExecutionRecovery.markCommitting(root, 48L, "48-e1"))
            assertEquals(
                TerminalExecutionRecovery.Phase.POST_NATIVE_EFFECTS,
                TerminalExecutionRecovery.read(root, 48L)?.phase,
            )
            assertEquals(
                TerminalExecutionRecovery.Outcome.STOPPED,
                TerminalExecutionRecovery.read(root, 48L)?.outcome,
            )

            assertTrue(
                TerminalExecutionRecovery.completePostNativeEffects(
                    root,
                    48L,
                    "48-e1",
                    effectLease,
                    TerminalExecutionRecovery.Outcome.FAILURE,
                ),
            )
            assertEquals(
                TerminalExecutionRecovery.Phase.TERMINAL_STOPPED,
                TerminalExecutionRecovery.read(root, 48L)?.phase,
            )
            lease?.close()
        } finally {
            lease?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationDuringPublicationRetainsOwnerAndCommitWinsOnlyAfterItsBoundary() {
        val root = Files.createTempDirectory("terminal-publication-cancel-").toFile()
        var effectLease: TerminalExecutionRecovery.EffectLease? = null
        var committedLease: TerminalExecutionRecovery.EffectLease? = null
        try {
            assertTrue(TerminalExecutionRecovery.begin(root, 49L, "49-e1", "terminal:49"))
            assertTrue(TerminalExecutionRecovery.markNativeStarted(root, 49L, "49-e1"))
            assertTrue(TerminalExecutionRecovery.markNativeFinished(root, 49L, "49-e1"))
            effectLease = requireNotNull(
                TerminalExecutionRecovery.beginPostNativeEffects(root, 49L, "49-e1"),
            )
            val lease = requireNotNull(effectLease)
            assertTrue(TerminalExecutionRecovery.markPublicationStarted(root, 49L, "49-e1", lease))
            assertTrue(
                TerminalExecutionRecovery.requestPostNativeOutcome(
                    root,
                    49L,
                    "49-e1",
                    TerminalExecutionRecovery.Outcome.STOPPED,
                ),
            )
            assertFalse(TerminalExecutionRecovery.markCommitting(root, 49L, "49-e1"))
            assertTrue(TerminalExecutionRecovery.read(root, 49L)!!.nativeQuiescent)
            assertFalse(TerminalExecutionRecovery.read(root, 49L)!!.postNativeEffectsQuiescent)
            assertTrue(
                TerminalExecutionRecovery.completePostNativeEffects(
                    root,
                    49L,
                    "49-e1",
                    lease,
                    TerminalExecutionRecovery.Outcome.FAILURE,
                ),
            )
            assertEquals(
                TerminalExecutionRecovery.Phase.TERMINAL_STOPPED,
                TerminalExecutionRecovery.read(root, 49L)?.phase,
            )
            lease.close()
            effectLease = null

            assertTrue(TerminalExecutionRecovery.begin(root, 50L, "50-e1", "terminal:50"))
            assertTrue(TerminalExecutionRecovery.markNativeStarted(root, 50L, "50-e1"))
            assertTrue(TerminalExecutionRecovery.markNativeFinished(root, 50L, "50-e1"))
            committedLease = requireNotNull(
                TerminalExecutionRecovery.beginPostNativeEffects(root, 50L, "50-e1"),
            )
            assertTrue(TerminalExecutionRecovery.markCommitting(root, 50L, "50-e1"))
            assertFalse(
                TerminalExecutionRecovery.requestPostNativeOutcome(
                    root,
                    50L,
                    "50-e1",
                    TerminalExecutionRecovery.Outcome.STOPPED,
                ),
            )
            assertTrue(TerminalExecutionRecovery.markCommitted(root, 50L, "50-e1"))
            assertEquals(
                TerminalExecutionRecovery.Phase.COMMITTED,
                TerminalExecutionRecovery.read(root, 50L)?.phase,
            )
            committedLease?.close()
        } finally {
            effectLease?.close()
            committedLease?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun preparedGenerationBindsAdmissionBeforeNativeStart() {
        val root = Files.createTempDirectory("terminal-execution-prepared-").toFile()
        try {
            assertTrue(TerminalExecutionRecovery.begin(root, 46L, "46-e1", "terminal:46"))
            assertEquals(
                TerminalExecutionRecovery.Phase.ADMITTED,
                TerminalExecutionRecovery.read(root, 46L)?.phase,
            )
            assertTrue(
                TerminalExecutionRecovery.bindNativeGeneration(
                    root,
                    46L,
                    "46-e1",
                    "generation-1",
                ),
            )
            val record = TerminalExecutionRecovery.read(root, 46L)
            assertEquals(TerminalExecutionRecovery.Phase.NATIVE_STARTED, record?.phase)
            assertEquals("generation-1", record?.nativeGenerationToken)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unresolvedNativeStopRetainsDurablePendingOwnerUntilPositiveProof() {
        val root = Files.createTempDirectory("terminal-execution-stop-").toFile()
        try {
            assertTrue(TerminalExecutionRecovery.begin(root, 43L, "43-e1", "terminal:43"))
            assertTrue(TerminalExecutionRecovery.markNativeStarted(root, 43L, "43-e1"))
            assertTrue(
                TerminalExecutionRecovery.markQuiescencePending(
                    root,
                    43L,
                    "43-e1",
                    TerminalExecutionRecovery.Outcome.STOPPED,
                ),
            )
            assertEquals(
                TerminalExecutionRecovery.Phase.NATIVE_QUIESCENCE_PENDING,
                TerminalExecutionRecovery.read(root, 43L)?.phase,
            )
            assertFalse(TerminalExecutionRecovery.markTerminalFailure(root, 43L, "43-e1"))
            assertNotNull(TerminalExecutionRecovery.read(root, 43L))
            assertTrue(
                TerminalExecutionRecovery.markTerminalFailure(
                    root,
                    43L,
                    "43-e1",
                    TerminalExecutionRecovery.Outcome.STOPPED,
                    quiescenceProven = true,
                ),
            )
            assertTrue(TerminalExecutionRecovery.read(root, 43L)?.terminal == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistenceFailureLeavesPreviousDurableOwnerAndCannotAdmitReplacement() {
        val root = Files.createTempDirectory("terminal-execution-persist-").toFile()
        try {
            assertTrue(TerminalExecutionRecovery.begin(root, 44L, "44-e1", "terminal:44"))
            TerminalExecutionRecovery.persistenceFailureForTesting = true
            assertFalse(TerminalExecutionRecovery.markNativeStarted(root, 44L, "44-e1"))
            assertEquals(
                TerminalExecutionRecovery.Phase.ADMITTED,
                TerminalExecutionRecovery.read(root, 44L)?.phase,
            )
            assertFalse(TerminalExecutionRecovery.begin(root, 44L, "44-e2", "terminal:44"))
        } finally {
            TerminalExecutionRecovery.persistenceFailureForTesting = false
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyGenericFailureGetsItsOwnTerminalTombstone() {
        val root = Files.createTempDirectory("terminal-execution-legacy-").toFile()
        try {
            assertTrue(
                TerminalExecutionRecovery.recordLegacyTerminalFailure(
                    storageDirectory = root,
                    subjectId = 45L,
                    executionToken = "45-e1",
                    processId = "terminal:45",
                ),
            )
            assertEquals(
                TerminalExecutionRecovery.Phase.TERMINAL_FAILURE,
                TerminalExecutionRecovery.read(root, 45L)?.phase,
            )
            assertTrue(
                TerminalExecutionRecovery.recordLegacyTerminalFailure(
                    storageDirectory = root,
                    subjectId = 45L,
                    executionToken = "45-e1",
                    processId = "terminal:45",
                ),
            )
            assertFalse(
                TerminalExecutionRecovery.recordLegacyTerminalFailure(
                    storageDirectory = root,
                    subjectId = 45L,
                    executionToken = "45-e2",
                    processId = "terminal:45",
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun executionWitnessDiscoveryDoesNotTreatOpaqueNamespaceAsHealthyEmpty() {
        val notDirectory = Files.createTempFile("terminal-execution-not-directory-", ".json").toFile()
        val root = Files.createTempDirectory("terminal-execution-opaque-").toFile()
        try {
            assertTrue(
                TerminalExecutionRecovery.discover(notDirectory) is
                    TerminalExecutionRecovery.DiscoveryResult.Unavailable,
            )
            val malformed = File(root, "ytdlnisx-terminal-execution-47.json")
            malformed.writeText("{\"version\":999}")
            val discovery = TerminalExecutionRecovery.discover(root)
            val opaque = discovery as? TerminalExecutionRecovery.DiscoveryResult.Opaque
            assertNotNull(opaque)
            assertEquals(1, opaque!!.opaqueFiles.size)
            assertTrue(opaque.records.isEmpty())
        } finally {
            notDirectory.delete()
            root.deleteRecursively()
        }
    }
}
