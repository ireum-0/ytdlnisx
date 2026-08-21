package com.ireum.ytdl.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
}
