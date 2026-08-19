package com.ireum.ytdl.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class LowQualityBatchProcessorTest {
    @Test
    fun oneFailureDoesNotSkipLaterItemsAndProgressIsOperationScoped() = runBlocking {
        val attempted = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = LowQualityBatchProcessor.process(
            items = listOf(1, 2, 3),
            processItem = { item ->
                attempted += item
                if (item == 2) error("unavailable source")
                "candidate-$item"
            },
            onProgress = { completed, total -> progress += completed to total }
        )

        assertEquals(listOf(1, 2, 3), attempted)
        assertEquals(listOf("candidate-1", "candidate-3"), result.completed)
        assertEquals(1, result.failureCount)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun nullResultsRepresentIndependentIneligibleItems() = runBlocking {
        val result = LowQualityBatchProcessor.process(
            items = listOf(1, 2, 3),
            processItem = { item -> item.takeIf { it == 2 } }
        )

        assertEquals(listOf(2), result.completed)
        assertEquals(0, result.failureCount)
    }

    @Test
    fun cancellationStopsImmediatelyAndPreservesIdentity() = runBlocking {
        val expected = CancellationException("cancel")
        val attempted = mutableListOf<Int>()
        try {
            LowQualityBatchProcessor.process(
                items = listOf(1, 2, 3),
                processItem = { item ->
                    attempted += item
                    if (item == 2) throw expected
                    item
                }
            )
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }

        assertEquals(listOf(1, 2), attempted)
    }
}
