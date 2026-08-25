package com.ireum.ytdl.util.extractors.ytdlp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
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
