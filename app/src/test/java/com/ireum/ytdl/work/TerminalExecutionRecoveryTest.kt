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
        try {
            assertTrue(TerminalExecutionRecovery.begin(root, 42L, "42-e1", "terminal:42"))
            assertFalse(TerminalExecutionRecovery.markNativeStarted(root, 42L, "42-e2"))
            assertTrue(TerminalExecutionRecovery.markNativeStarted(root, 42L, "42-e1"))
            assertTrue(TerminalExecutionRecovery.bindNativeGeneration(root, 42L, "42-e1", "g1"))
            assertFalse(TerminalExecutionRecovery.bindNativeGeneration(root, 42L, "42-e1", "g2"))
            assertTrue(TerminalExecutionRecovery.markNativeFinished(root, 42L, "42-e1"))
            assertTrue(TerminalExecutionRecovery.markCommitting(root, 42L, "42-e1"))
            assertTrue(TerminalExecutionRecovery.markCommitted(root, 42L, "42-e1"))
            assertEquals(
                TerminalExecutionRecovery.Phase.COMMITTED,
                TerminalExecutionRecovery.read(root, 42L)?.phase,
            )
            assertFalse(TerminalExecutionRecovery.begin(root, 42L, "42-e2", "terminal:42"))
        } finally {
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
