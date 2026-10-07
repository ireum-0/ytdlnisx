package com.ireum.ytdl.work

import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class StoppedDownloadExecutionOwnerTest {
    private val id = 971L
    private lateinit var markers: java.io.File

    @Before
    fun prepareExactOwner() {
        markers = Files.createTempDirectory("stopped-owner-test").toFile()
        YtdlpNativeProcessBarrier.configureForTesting(markers)
        DownloadWorkerExecutionOwners.claim(id, "E1")
        assertTrue(DownloadWorkerProcessOwners.claim(id, "E1"))
    }

    @After
    fun retireTestObjects() {
        for (token in listOf("E1", "E2")) {
            DownloadWorkerExecutionOwners.release(id, token)
            DownloadWorkerProcessOwners.release(id, token)
        }
        markers.deleteRecursively()
    }

    private fun retire(
        row: AbandonedDownloadExecution?,
        cleanupAllowsRelease: Boolean = false,
        nativePresent: Boolean = false,
    ) = retireStoppedDownloadExecutionOwner(id, "E1", row, cleanupAllowsRelease) { candidate, token ->
        assertEquals(id, candidate)
        assertEquals("E1", token)
        nativePresent
    }

    @Test
    fun activeRetainedByChildBecomesReleasableAfterEndRequeue() {
        val active = AbandonedDownloadExecution(id, "E1", "Active")
        assertFalse(retire(active))
        assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(id, "E1"))
        val queuedByEnd = active.copy(executionId = "", status = "Queued")
        assertTrue(retire(queuedByEnd))
        assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(id, "E1"))
        assertFalse(DownloadWorkerProcessOwners.isOwnedBy(id, "E1"))
        assertEquals("Queued", queuedByEnd.status)
    }

    @Test
    fun absentRowRetiresOnlyTheSnapshotToken() {
        assertTrue(retire(null))
        assertEquals(null, DownloadWorkerExecutionOwners.ownerOf(id))
        assertEquals(null, DownloadWorkerProcessOwners.ownerOf(id))
    }

    @Test
    fun staleE1CannotRemoveOrMutateFreshE2() {
        DownloadWorkerExecutionOwners.claim(id, "E2")
        DownloadWorkerProcessOwners.release(id, "E1")
        assertTrue(DownloadWorkerProcessOwners.claim(id, "E2"))
        val e2 = AbandonedDownloadExecution(id, "E2", "Active")
        assertTrue(retire(e2))
        assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(id, "E2"))
        assertTrue(DownloadWorkerProcessOwners.isOwnedBy(id, "E2"))
        assertEquals(AbandonedDownloadExecution(id, "E2", "Active"), e2)
    }

    @Test
    fun unresolvedSameActiveOrPostProcessingTokenIsNotBlindlyReleased() {
        for (status in listOf("Active", "PostProcessing")) {
            assertFalse(retire(AbandonedDownloadExecution(id, "E1", status)))
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(id, "E1"))
            assertTrue(DownloadWorkerProcessOwners.isOwnedBy(id, "E1"))
        }
    }

    @Test
    fun sameNonrunningTokenNeedsNoRunningCleanup() {
        assertTrue(retire(AbandonedDownloadExecution(id, "E1", "Queued")))
        assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(id, "E1"))
    }

    @Test
    fun nativeRegistryKeepsSeparateProcessAuthorityAfterExecutionRetirement() {
        assertTrue(retire(AbandonedDownloadExecution(id, "", "Queued"), nativePresent = true))
        assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(id, "E1"))
        assertTrue(DownloadWorkerProcessOwners.isOwnedBy(id, "E1"))
    }

    @Test
    fun existingRecoveryAuthorityCanRetireStoppedWorkerWithoutDischargingNativeDebt() {
        val unresolved = AbandonedDownloadExecution(id, "E1", "Active")
        assertTrue(retire(unresolved, cleanupAllowsRelease = true, nativePresent = true))
        assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(id, "E1"))
        assertTrue(DownloadWorkerProcessOwners.isOwnedBy(id, "E1"))
        assertEquals("Active", unresolved.status)
    }

    @Test
    fun repeatedCatchAndFinallyRetirementIsIdempotentAndProtectsLaterE2() {
        val queued = AbandonedDownloadExecution(id, "", "Queued")
        assertTrue(retire(queued))
        assertTrue(retire(queued))
        DownloadWorkerExecutionOwners.claim(id, "E2")
        assertTrue(DownloadWorkerProcessOwners.claim(id, "E2"))
        assertTrue(retire(AbandonedDownloadExecution(id, "E2", "PostProcessing")))
        assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(id, "E2"))
        assertTrue(DownloadWorkerProcessOwners.isOwnedBy(id, "E2"))
    }
}
