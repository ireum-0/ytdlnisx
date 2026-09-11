package com.ireum.ytdl.util.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPositionPersistenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun positionsForOneHistoryIdAreCommittedInSubmissionOrder() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val committed = mutableListOf<Long>()
        val writer = OrderedPlaybackPositionWriter<Long>(scope) { _, position ->
            if (position == 10_000L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            committed += position
        }

        val first = writer.submit(7L, 10_000L)
        firstStarted.await()
        val second = writer.submit(7L, 20_000L)

        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        joinAll(first, second)

        assertEquals(listOf(10_000L, 20_000L), committed)
    }

    @Test
    fun completionZeroIsTheLaterValueForTheSameHistoryId() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var persisted = Long.MIN_VALUE
        val writer = OrderedPlaybackPositionWriter<Long>(scope) { _, position ->
            if (position == 15_000L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            persisted = position
        }

        val earlier = writer.submit(9L, 15_000L)
        firstStarted.await()
        val completion = writer.submit(9L, 0L)
        releaseFirst.complete(Unit)
        joinAll(earlier, completion)

        assertEquals(0L, persisted)
    }

    @Test
    fun rapidSequenceConvergesToTheLatestPosition() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<Long>()
        val writer = OrderedPlaybackPositionWriter<Long>(scope) { _, position ->
            if (position == 1_000L) release.await()
            committed += position
        }

        val jobs = listOf(1L, 2L, 3L, 4L).map { seconds ->
            writer.submit(11L, seconds * 1_000L)
        }
        release.complete(Unit)
        jobs.joinAll()

        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), committed)
    }

    @Test
    fun acceptedWriteOutlivesCallerLifecycleScope() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val release = CompletableDeferred<Unit>()
            var persisted = 0L
            val writer = OrderedPlaybackPositionWriter<Long>(ownerScope) { _, position ->
                release.await()
                persisted = position
            }

            val accepted = writer.submit(12L, 42_000L)
            callerScope.cancel()
            release.complete(Unit)
            accepted.join()

            assertEquals(42_000L, persisted)
        } finally {
            ownerScope.cancel()
            callerScope.cancel()
        }
    }

    @Test
    fun recreationUsesTheSameOrderedOwner() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val committed = mutableListOf<Long>()
        val writer = OrderedPlaybackPositionWriter<Long>(scope) { _, position ->
            if (position == 5_000L) releaseFirst.await()
            committed += position
        }

        val beforeRecreation = writer.submit(13L, 5_000L)
        val afterRecreation = writer.submit(13L, 6_000L)
        releaseFirst.complete(Unit)
        joinAll(beforeRecreation, afterRecreation)

        assertEquals(listOf(5_000L, 6_000L), committed)
    }

    @Test
    fun differentHistoryIdsDoNotShareAnOrderingTail() = runBlocking {
        val releaseA = CompletableDeferred<Unit>()
        val bCommitted = CompletableDeferred<Unit>()
        val writer = OrderedPlaybackPositionWriter<Long>(scope) { id, _ ->
            if (id == 21L) releaseA.await()
            if (id == 22L) bCommitted.complete(Unit)
        }

        val a = writer.submit(21L, 1_000L)
        val b = writer.submit(22L, 2_000L)
        bCommitted.await()
        assertFalse(a.isCompleted)
        releaseA.complete(Unit)
        joinAll(a, b)
        assertTrue(b.isCompleted)
    }

    @Test
    fun failedWriteDoesNotPreventLaterAcceptedPosition() = runBlocking {
        val committed = mutableListOf<Long>()
        val writer = OrderedPlaybackPositionWriter<Long>(scope) { _, position ->
            if (position == 1_000L) error("simulated missing/deleted row")
            committed += position
        }

        val first = writer.submit(31L, 1_000L)
        val second = writer.submit(31L, 2_000L)
        joinAll(first, second)

        assertEquals(listOf(2_000L), committed)
    }

    @Test
    fun zeroPositionRemainsAValidImmediateQueueValue() {
        val state = PlaybackQueueState<TestItem>(
            idOf = TestItem::id,
            pathsOf = TestItem::paths,
            playbackPositionOf = TestItem::positionMs
        )
        val item = TestItem(41L, listOf("/media.mp4"), 9_000L)
        state.replaceItems(listOf(item), state.prepare(listOf(item), mediaKeyForPath = { it }))

        state.recordPlaybackPosition(item.id, 0L)

        assertEquals(0L, state.playbackPosition(item.id))
    }

    private data class TestItem(
        val id: Long,
        val paths: List<String>,
        val positionMs: Long
    )
}
