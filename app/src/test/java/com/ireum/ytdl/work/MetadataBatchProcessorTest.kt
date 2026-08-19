package com.ireum.ytdl.work

import com.ireum.ytdl.util.DownloadMetadataEnrichmentPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class MetadataBatchProcessorTest {
    @Test
    fun allItemsSucceed() = runBlocking {
        val executed = mutableListOf<Long>()

        val result = process(ids = listOf(1L, 2L, 3L), executed = executed)

        assertEquals(listOf(1L, 2L, 3L), executed)
        assertEquals(MetadataBatchResult(3, 3, 0, 0), result)
    }

    @Test
    fun earlyFailureDoesNotPreventLaterItems() = runBlocking {
        val executed = mutableListOf<Long>()

        val result = process(
            ids = listOf(1L, 2L, 3L),
            failures = setOf(1L),
            executed = executed,
        )

        assertEquals(listOf(1L, 2L, 3L), executed)
        assertEquals(MetadataBatchResult(3, 2, 1, 0), result)
    }

    @Test
    fun loadFailureDoesNotPreventLaterItems() = runBlocking {
        val expected = IllegalStateException("missing row")
        val loaded = mutableListOf<Long>()
        val executed = mutableListOf<Long>()
        val reported = mutableListOf<Pair<Long, Exception>>()

        val result = MetadataBatchProcessor.process(
            ids = listOf(1L, 2L, 3L),
            loadItem = { id ->
                loaded += id
                if (id == 2L) throw expected
                id
            },
            shouldProcess = { true },
            processItem = { id, _ -> executed += id },
            onItemFailure = { id, error -> reported += id to error },
        )

        assertEquals(listOf(1L, 2L, 3L), loaded)
        assertEquals(listOf(1L, 3L), executed)
        assertEquals(listOf(2L to expected), reported)
        assertEquals(MetadataBatchResult(3, 2, 1, 0), result)
    }

    @Test
    fun severalIndependentFailuresAreIsolated() = runBlocking {
        val executed = mutableListOf<Long>()
        val reported = mutableListOf<Long>()

        val result = process(
            ids = listOf(1L, 2L, 3L, 4L),
            failures = setOf(1L, 3L, 4L),
            executed = executed,
            reported = reported,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), executed)
        assertEquals(listOf(1L, 3L, 4L), reported)
        assertEquals(MetadataBatchResult(4, 1, 3, 0), result)
    }

    @Test
    fun partialSuccessCompletesWithFailureSummary() = runBlocking {
        val result = process(
            ids = listOf(1L, 2L),
            failures = setOf(2L),
            executed = mutableListOf(),
        )

        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
    }

    @Test
    fun cancellationStopsBatchAndPreservesIdentity() = runBlocking {
        val expected = CancellationException("cancelled")
        val executed = mutableListOf<Long>()

        try {
            MetadataBatchProcessor.process(
                ids = listOf(1L, 2L, 3L),
                loadItem = { it },
                shouldProcess = { true },
                processItem = { id, _ ->
                    executed += id
                    if (id == 2L) throw expected
                },
            )
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }

        assertEquals(listOf(1L, 2L), executed)
    }

    @Test
    fun completeItemsAreSkippedWithoutBeingAttempted() = runBlocking {
        val executed = mutableListOf<Long>()

        val result = MetadataBatchProcessor.process(
            ids = listOf(1L, 2L, 3L),
            loadItem = { it },
            shouldProcess = { it != 2L },
            processItem = { id, _ -> executed += id },
        )

        assertEquals(listOf(1L, 3L), executed)
        assertEquals(MetadataBatchResult(2, 2, 0, 1), result)
    }

    @Test
    fun sourceDateOnlyCandidateIsProcessedWhileIneligibleLocalItemIsSkipped() = runBlocking {
        val candidates = mapOf(
            1L to Candidate("example.com/video", mediaPublishedAt = 0L),
            2L to Candidate("content://media/external/video/2", mediaPublishedAt = 0L),
            3L to Candidate("https://example.com/old", mediaPublishedAt = -315_532_800L),
        )
        val executed = mutableListOf<Long>()

        val result = MetadataBatchProcessor.process(
            ids = candidates.keys,
            loadItem = { candidates.getValue(it) },
            shouldProcess = { candidate ->
                DownloadMetadataEnrichmentPolicy.shouldEnrich(
                    source = candidate.source,
                    title = candidate.title,
                    author = candidate.author,
                    thumbnail = candidate.thumbnail,
                    mediaPublishedAt = candidate.mediaPublishedAt,
                )
            },
            processItem = { id, _ -> executed += id },
        )

        assertEquals(listOf(1L), executed)
        assertEquals(MetadataBatchResult(1, 1, 0, 2), result)
    }

    private suspend fun process(
        ids: List<Long>,
        failures: Set<Long> = emptySet(),
        executed: MutableList<Long>,
        reported: MutableList<Long> = mutableListOf(),
    ): MetadataBatchResult {
        return MetadataBatchProcessor.process(
            ids = ids,
            loadItem = { it },
            shouldProcess = { true },
            processItem = { id, _ ->
                executed += id
                if (id in failures) throw IllegalStateException("failed $id")
            },
            onItemFailure = { id, _ -> reported += id },
        )
    }

    private data class Candidate(
        val source: String,
        val title: String = "title",
        val author: String = "author",
        val thumbnail: String = "thumbnail",
        val mediaPublishedAt: Long,
    )
}
