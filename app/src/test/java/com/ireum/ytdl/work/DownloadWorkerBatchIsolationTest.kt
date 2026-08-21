package com.ireum.ytdl.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DownloadWorkerBatchIsolationTest {
    @Test
    fun terminalFailureIsRethrownAfterUnrelatedSiblingFinishes() = runBlocking {
        val statuses = ConcurrentHashMap<String, String>()
        val failure = IOException("terminal persistence failed")
        var thrown: Exception? = null

        try {
            runDownloadItemsIndependently(listOf("A", "B")) { item ->
                statuses[item] = "Active"
                if (item == "A") throw failure
                delay(25)
                statuses[item] = "Queued"
            }
        } catch (error: Exception) {
            thrown = error
        }

        assertEquals(failure, thrown)
        assertTrue(statuses.containsKey("A"))
        assertEquals("Queued", statuses["B"])
    }

    @Test
    fun cancellationRemainsCancellation() = runBlocking {
        val cancellation = CancellationException("user cancelled")
        try {
            runDownloadItemsIndependently(listOf("A", "B")) { item ->
                if (item == "A") throw cancellation
                delay(100)
            }
            throw AssertionError("cancellation was swallowed")
        } catch (error: CancellationException) {
            assertEquals(cancellation.message, error.message)
        }
    }

    @Test
    fun itemLocalPauseAndCancelCancellationDoesNotCancelSibling() = runBlocking {
        listOf("Paused", "Cancelled").forEach { localStatus ->
            val statuses = ConcurrentHashMap<String, String>()

            runDownloadItemsIndependently(
                items = listOf("A", "B"),
                isItemLocalCancellation = { item -> statuses[item] == localStatus },
            ) { item ->
                statuses[item] = "Active"
                if (item == "A") {
                    statuses[item] = localStatus
                    throw CancellationException("item locally stopped")
                }
                delay(25)
                statuses[item] = "Queued"
            }

            assertEquals(localStatus, statuses["A"])
            assertEquals("Queued", statuses["B"])
        }
    }

    @Test
    fun workerCancellationCancelsAllSiblingsAndRemainsCancellation() = runBlocking {
        val bothStarted = CompletableDeferred<Unit>()
        val workerCancellation = CancellationException("worker stopped")
        val siblingWasCancelled = AtomicBoolean(false)
        var thrown: CancellationException? = null

        try {
            runDownloadItemsIndependently(listOf("A", "B")) { item ->
                if (item == "B") {
                    bothStarted.complete(Unit)
                    try {
                        delay(Long.MAX_VALUE)
                    } catch (cancelled: CancellationException) {
                        siblingWasCancelled.set(true)
                        throw cancelled
                    }
                } else {
                    bothStarted.await()
                    throw workerCancellation
                }
            }
            throw AssertionError("worker cancellation was swallowed")
        } catch (error: CancellationException) {
            thrown = error
        }

        assertEquals(workerCancellation.message, thrown?.message)
        assertTrue(siblingWasCancelled.get())
    }

    @Test
    fun itemLocalCancellationOriginSurvivesRapidResumeOfTheSameDownload() = runBlocking {
        data class Attempt(val id: Long, val executionId: String)

        val oldAttempt = Attempt(901L, "old-attempt")
        val statuses = ConcurrentHashMap<Long, String>()
        DownloadCancellationRegistry.record(
            oldAttempt.id,
            oldAttempt.executionId,
            DownloadCancellationRegistry.Reason.PAUSED,
        )

        runDownloadItemsIndependently(
            items = listOf(oldAttempt, Attempt(902L, "sibling")),
            isItemLocalCancellation = { item ->
                DownloadCancellationRegistry.belongsTo(item.id, item.executionId)
            },
        ) { item ->
            if (item.id == oldAttempt.id) {
                // The new attempt is queued before the old child observes its
                // cancellation.  The registry still identifies the old child.
                statuses[item.id] = "Queued"
                throw CancellationException("old item-local pause")
            }
            statuses[item.id] = "Queued"
        }

        assertEquals("Queued", statuses[oldAttempt.id])
        assertEquals("Queued", statuses[902L])
    }
}
