package com.ireum.ytdl.work

import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadWorkerNativeProcessQuiescenceTest {
    @Test
    fun delayedNativeTerminationKeepsE2BehindTheProductionLease() = runBlocking {
        val downloadId = 1201L
        val e1 = "E1"
        val e2 = "E2"
        val processId = YtdlpProcessIdentity.download(downloadId, e1)
        val process = ControlledProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, e1)
        DownloadWorkerProcessOwners.claim(downloadId, e1)
        YoutubeDLCompat.registerProcessForTesting(processId, process)

        try {
            val e1Cancellation = async(Dispatchers.IO) {
                withDownloadWorkerExecutionSideEffectLease(downloadId, e1) {
                    assertTrue(DownloadWorker.cancelProcessesForExecution(downloadId, e1))
                }
            }
            process.destroyRequested.await()

            val e2Claimed = CompletableDeferred<Unit>()
            val e2 = async(Dispatchers.IO) {
                withDownloadWorkerExecutionSideEffectLease(downloadId, e2) {
                    e2Claimed.complete(Unit)
                    DownloadWorkerProcessOwners.claim(downloadId, e2)
                }
            }

            yield()
            assertFalse(e2Claimed.isCompleted)
            assertFalse(e1Cancellation.isCompleted)

            process.acknowledgeTermination()
            e1Cancellation.await()
            e2.await()
            assertTrue(e2Claimed.isCompleted)
            assertTrue(DownloadWorkerProcessOwners.isOwnedBy(downloadId, e2))
        } finally {
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, e1)
            DownloadWorkerProcessOwners.release(downloadId, e2)
            DownloadWorkerExecutionOwners.release(downloadId, e1)
            DownloadWorkerExecutionOwners.release(downloadId, e2)
        }
    }

    @Test
    fun unprovenTerminationRetainsTheExactE1ProcessBarrier() = runBlocking {
        val downloadId = 1202L
        val e1 = "E1"
        val processId = YtdlpProcessIdentity.download(downloadId, e1)
        val process = UnquiescentProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, e1)
        DownloadWorkerProcessOwners.claim(downloadId, e1)
        YoutubeDLCompat.registerProcessForTesting(processId, process)

        try {
            var failedClosed = false
            try {
                withDownloadWorkerExecutionSideEffectLease(downloadId, e1) {
                    DownloadWorker.cancelProcessesForExecution(downloadId, e1)
                }
            } catch (_: NativeProcessQuiescenceException) {
                failedClosed = true
            }

            assertTrue(failedClosed)
            assertTrue(DownloadWorkerProcessOwners.isOwnedBy(downloadId, e1))
            // A newer attempt cannot address or replace the unresolved native
            // owner through the production cancellation gate.
            assertFalse(DownloadWorker.cancelProcessesForExecution(downloadId, "E2"))
            assertTrue(DownloadWorkerProcessOwners.isOwnedBy(downloadId, e1))
        } finally {
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, e1)
            DownloadWorkerExecutionOwners.release(downloadId, e1)
        }
    }

    @Test
    fun staleE1CannotDestroyOrClearAnE2ProcessRegistryEntry() = runBlocking {
        val downloadId = 1203L
        val e1 = "E1"
        val e2 = "E2"
        val processId = YtdlpProcessIdentity.download(downloadId, e2)
        val process = ControlledProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, e2)
        DownloadWorkerProcessOwners.claim(downloadId, e2)
        YoutubeDLCompat.registerProcessForTesting(processId, process)

        try {
            withDownloadWorkerExecutionSideEffectLease(downloadId, e1) {
                assertFalse(DownloadWorker.cancelProcessesForExecution(downloadId, e1))
            }
            assertFalse(process.destroyRequested.isCompleted)
            assertTrue(DownloadWorkerProcessOwners.isOwnedBy(downloadId, e2))
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, e2))
        } finally {
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, e2)
            DownloadWorkerExecutionOwners.release(downloadId, e2)
        }
    }

    @Test
    fun oneUnquiescentSiblingDoesNotPreventAnotherSiblingFromConverging() = runBlocking {
        val failingId = 1204L
        val healthyId = 1205L
        val failingProcessId = YtdlpProcessIdentity.download(failingId, "E1")
        val healthyProcessId = YtdlpProcessIdentity.download(healthyId, "E1")
        val failingProcess = UnquiescentProcess()
        val healthyProcess = ControlledProcess().also { it.acknowledgeTermination() }
        DownloadWorkerProcessOwners.claim(failingId, "E1")
        DownloadWorkerProcessOwners.claim(healthyId, "E1")
        YoutubeDLCompat.registerProcessForTesting(failingProcessId, failingProcess)
        YoutubeDLCompat.registerProcessForTesting(healthyProcessId, healthyProcess)

        try {
            var failingCaught = false
            try {
                withDownloadWorkerExecutionSideEffectLease(failingId, "E1") {
                    DownloadWorker.cancelProcessesForExecution(failingId, "E1")
                }
            } catch (_: NativeProcessQuiescenceException) {
                failingCaught = true
            }

            withDownloadWorkerExecutionSideEffectLease(healthyId, "E1") {
                assertTrue(DownloadWorker.cancelProcessesForExecution(healthyId, "E1"))
            }

            assertTrue(failingCaught)
            assertTrue(DownloadWorkerProcessOwners.isOwnedBy(failingId, "E1"))
            assertFalse(DownloadWorkerProcessOwners.isOwnedBy(healthyId, "E1"))
        } finally {
            YoutubeDLCompat.clearProcessForTesting(failingProcessId)
            YoutubeDLCompat.clearProcessForTesting(healthyProcessId)
            DownloadWorkerProcessOwners.release(failingId, "E1")
            DownloadWorkerProcessOwners.release(healthyId, "E1")
        }
    }

    private open class ControlledProcess(
        private val acknowledgeOnForce: Boolean = true,
    ) : Process() {
        private val terminated = CountDownLatch(1)
        private var alive = true
        val destroyRequested = CompletableDeferred<Unit>()

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            terminated.await()
            return 143
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            terminated.await(timeout, unit)

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 143
        }

        override fun destroy() {
            destroyRequested.complete(Unit)
        }

        override fun destroyForcibly(): Process {
            if (acknowledgeOnForce) acknowledgeTermination()
            return this
        }

        override fun isAlive(): Boolean = alive

        fun acknowledgeTermination() {
            alive = false
            terminated.countDown()
        }
    }

    private class UnquiescentProcess : ControlledProcess(acknowledgeOnForce = false) {
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
    }
}
