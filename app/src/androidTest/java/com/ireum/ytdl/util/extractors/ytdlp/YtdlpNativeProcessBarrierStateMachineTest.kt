package com.ireum.ytdl.util.extractors.ytdlp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class YtdlpNativeProcessBarrierStateMachineTest {
    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val processes = mutableListOf<Process>()
    private val markers = mutableListOf<File>()

    @After
    fun cleanup() {
        YtdlpNativeProcessBarrier.markerWriteFailureForTesting = false
        YtdlpNativeProcessBarrier.markerDeleteFailureForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        YtdlpNativeProcessBarrier.markerEnumerationFailureForTesting = false
        processes.forEach { process ->
            if (isAliveCompat(process)) process.destroy()
            awaitExitCompat(process)
        }
        markers.forEach { it.delete() }
    }

    @Test
    fun preLaunchCrashClearsOnlyAfterExactGenerationAbsenceProof() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val processId = downloadProcessId()
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = processId,
            state = "STARTING",
            generationToken = "pre-launch-${UUID.randomUUID()}",
        )
        markers += marker

        assertTrue(YtdlpNativeProcessBarrier.recover(marker))
        assertFalse(marker.exists())
    }

    @Test
    fun supervisorCreatedBeforeIdentityPublicationIsRecoveredByToken() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val token = "supervisor-${UUID.randomUUID()}"
        val process = startTaggedProcess(token)
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = downloadProcessId(),
            state = "LAUNCHING_CHILD",
            generationToken = token,
        )
        markers += marker

        assertTrue(YtdlpNativeProcessBarrier.recover(marker))
        assertFalse(isAliveCompat(process))
        assertFalse(marker.exists())
    }

    @Test
    fun exactRunningGenerationIsTerminatedWithoutNumericGroupAuthority() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val token = "old-generation-${UUID.randomUUID()}"
        val process = startTaggedProcess(token)
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = downloadProcessId(),
            state = "RUNNING",
            generationToken = token,
            pgid = findTaggedPid(token),
            pgidStartTime = null,
        )
        markers += marker

        assertTrue(YtdlpNativeProcessBarrier.recover(marker))
        assertFalse(isAliveCompat(process))
        assertFalse(marker.exists())
    }

    @Test
    fun directNativeRoleMarkerUsesTheSameExactGenerationRecoveryCarrier() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val token = "direct-role-${UUID.randomUUID()}"
        val processId = "download:918003:E1:direct:ffmpeg:${UUID.randomUUID()}"
        val process = startTaggedProcess(token)
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = processId,
            state = "RUNNING",
            generationToken = token,
        )
        markers += marker

        assertTrue(YtdlpNativeProcessBarrier.recoverDownloadExecution(918003L, "E1"))
        assertFalse(isAliveCompat(process))
        assertFalse(marker.exists())
    }

    @Test
    fun recoveryDoesNotReportSuccessAfterOnlyReadableMarkerSubsetConverges() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = 918004L
        val executionId = "E1"
        val readable = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = "download:$downloadId:$executionId",
            state = "RUNNING",
            generationToken = "readable-${UUID.randomUUID()}",
        )
        val opaque = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = "download:$downloadId:$executionId:direct:ffmpeg",
            state = "RUNNING",
            generationToken = "opaque-${UUID.randomUUID()}",
        )
        markers += readable
        markers += opaque

        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = opaque.absolutePath
        assertFalse(
            YtdlpNativeProcessBarrier.recoverDownloadExecution(downloadId, executionId)
        )
        assertFalse(readable.exists())
        assertTrue(opaque.exists())

        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        assertTrue(
            YtdlpNativeProcessBarrier.recoverDownloadExecution(downloadId, executionId)
        )
        assertFalse(readable.exists())
        assertFalse(opaque.exists())
    }

    @Test
    fun downloadRecoveryTreatsQuiescentDeleteFailureAsRetryableCleanupDebt() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = 918005L
        val executionId = "E1"
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = "download:$downloadId:$executionId",
            state = "RUNNING",
            generationToken = "cleanup-retry-${UUID.randomUUID()}",
        )
        markers += marker

        YtdlpNativeProcessBarrier.markerDeleteFailureForTesting = true
        assertTrue(
            YtdlpNativeProcessBarrier.recoverDownloadExecution(downloadId, executionId)
        )
        assertTrue(marker.exists())
        assertTrue(YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId, executionId))
        assertTrue(YtdlpNativeProcessBarrier.isQuiescent(marker))

        YtdlpNativeProcessBarrier.markerDeleteFailureForTesting = false
        assertTrue(
            YtdlpNativeProcessBarrier.recoverDownloadExecution(downloadId, executionId)
        )
        assertFalse(marker.exists())
    }

    @Test
    fun recycledNumericLocatorWithDifferentGenerationIsNeverSignalled() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val siblingToken = "sibling-${UUID.randomUUID()}"
        val sibling = startTaggedProcess(siblingToken)
        val recycledLocator = findTaggedPid(siblingToken)
        val oldMarker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = downloadProcessId(),
            state = "RUNNING",
            generationToken = "gone-old-generation-${UUID.randomUUID()}",
            // This is the unrelated sibling's live numeric locator. Recovery
            // must ignore it because its generation token is different.
            pgid = recycledLocator,
        )
        markers += oldMarker

        assertTrue(YtdlpNativeProcessBarrier.recover(oldMarker))
        assertTrue(isAliveCompat(sibling))
        assertFalse(oldMarker.exists())
    }

    @Test
    fun quiescenceProofSurvivesMarkerDeleteFailureAsCleanupDebt() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = downloadProcessId(),
            state = "QUIESCENT",
            generationToken = "cleanup-debt-${UUID.randomUUID()}",
        )
        markers += marker

        YtdlpNativeProcessBarrier.markerDeleteFailureForTesting = true
        val result = YtdlpNativeProcessBarrier.recoverDetailed(marker)
        assertEquals(
            YtdlpNativeProcessBarrier.QuiescenceState.PROVEN_QUIESCENT_CLEANUP_PENDING,
            result.state,
        )
        assertTrue(result.isProvenQuiescent)
        assertTrue(marker.exists())
        assertTrue(YtdlpNativeProcessBarrier.recover(marker))
        YtdlpNativeProcessBarrier.markerDeleteFailureForTesting = false
        assertTrue(YtdlpNativeProcessBarrier.recover(marker))
        assertFalse(marker.exists())
    }

    @Test
    fun quiescencePublicationFailureLeavesLiveStateFailClosed() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = downloadProcessId(),
            state = "RUNNING",
            generationToken = "publication-debt-${UUID.randomUUID()}",
        )
        markers += marker

        YtdlpNativeProcessBarrier.markerWriteFailureForTesting = true
        val result = YtdlpNativeProcessBarrier.recoverDetailed(marker)
        assertEquals(
            YtdlpNativeProcessBarrier.QuiescenceState.UNRESOLVED,
            result.state,
        )
        assertTrue(marker.exists())
        YtdlpNativeProcessBarrier.markerWriteFailureForTesting = false
        assertTrue(YtdlpNativeProcessBarrier.recover(marker))
        assertFalse(marker.exists())
    }

    @Test
    fun unreadableOrMalformedMarkerIsUnknownNotAbsent() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val processId = downloadProcessId()
        val marker = YtdlpNativeProcessBarrier.markerFor(processId)
        marker.parentFile!!.mkdirs()
        marker.writeText("RUNNING\nnot-a-process-id\n")
        markers += marker

        assertTrue(
            YtdlpNativeProcessBarrier.observeGeneration(processId) is
                YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN,
        )
    }

    @Test
    fun markerIdentityReadFailureIsNotPersistedAsAbsence() {
        YtdlpNativeProcessBarrier.configure(appContext)
        val processId = downloadProcessId()
        val token = "read-failure-${UUID.randomUUID()}"
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = processId,
            state = "RUNNING",
            generationToken = token,
        )
        markers += marker

        YtdlpNativeProcessBarrier.markerReadFailureForTesting = true
        assertTrue(
            YtdlpNativeProcessBarrier.observeGeneration(processId) is
                YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN,
        )
        YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
        assertTrue(
            YtdlpNativeProcessBarrier.observeGeneration(processId) is
                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION,
        )
    }

    private fun downloadProcessId(): String =
        "download:${System.nanoTime()}:${UUID.randomUUID()}"

    private fun startTaggedProcess(token: String): Process {
        val process = ProcessBuilder(
            "/system/bin/sh",
            "-c",
            "exec sleep 60",
        ).apply {
            environment()[YtdlpNativeProcessBarrier.NATIVE_GENERATION_ENVIRONMENT] = token
        }.start()
        processes += process
        waitForTaggedPid(token)
        return process
    }

    private fun waitForTaggedPid(token: String): Long {
        repeat(40) {
            findTaggedPid(token)?.let { return it }
            Thread.sleep(25L)
        }
        throw AssertionError("tagged process was not visible in /proc")
    }

    private fun findTaggedPid(token: String): Long? = File("/proc")
        .listFiles()
        .orEmpty()
        .mapNotNull { entry ->
            val pid = entry.name.toLongOrNull() ?: return@mapNotNull null
            val environment = runCatching {
                entry.resolve("environ").readBytes().toString(Charsets.ISO_8859_1)
            }.getOrNull() ?: return@mapNotNull null
            val expected = "${YtdlpNativeProcessBarrier.NATIVE_GENERATION_ENVIRONMENT}=$token"
            pid.takeIf { environment.split('\u0000').contains(expected) }
        }
        .firstOrNull()

    private fun isAliveCompat(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun awaitExitCompat(process: Process) {
        repeat(80) {
            if (!isAliveCompat(process)) return
            Thread.sleep(25L)
        }
    }
}
