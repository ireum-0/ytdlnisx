package com.ireum.ytdl.util.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackQueueStateTest {
    private val state = PlaybackQueueState<TestItem>(
        idOf = TestItem::id,
        pathsOf = TestItem::paths,
        playbackPositionOf = TestItem::positionMs
    )

    @Test
    fun replacementUpdatesOrderAndEveryDerivedLookupTogether() {
        val items = listOf(
            TestItem(10, listOf("/a.mp4"), 1_000),
            TestItem(20, listOf("/b.mp4"), 2_000)
        )
        val prepared = state.prepare(items, mediaKeyForPath = { "media:$it" })

        state.replaceItems(items, prepared)

        assertEquals(listOf(10L, 20L), state.items.map(TestItem::id))
        assertEquals(1, state.indexOf(20))
        assertEquals("/a.mp4", state.playablePath(10))
        assertEquals("media:/b.mp4", state.mediaKey(20))
        assertEquals(20L, state.idForMediaKey("media:/b.mp4"))
        assertEquals(2_000L, state.playbackPosition(20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun preparedDataCannotBeAppliedToAnotherOrder() {
        val first = TestItem(1, listOf("/1"), 0)
        val second = TestItem(2, listOf("/2"), 0)
        val prepared = state.prepare(listOf(first, second), mediaKeyForPath = { it })

        state.replaceItems(listOf(second, first), prepared)
    }

    @Test
    fun shuffleKeepsCurrentFirstAndBaseOrderUnchanged() {
        val items = (1L..5L).map { TestItem(it, listOf("/$it"), 0) }
        state.setBaseItems(items)
        state.replaceItems(items, state.prepare(items, mediaKeyForPath = { it }))
        state.setShuffled(true)

        val shuffled = state.shuffledOrderKeepingCurrent(3L, Random(7))

        assertEquals(3L, shuffled.first().id)
        assertEquals(items.map(TestItem::id).toSet(), shuffled.map(TestItem::id).toSet())
        assertEquals(items, state.baseItems)
        assertTrue(state.isShuffled)
    }

    @Test
    fun updateAndOffsetQueriesUseTheOwnedQueue() {
        val items = listOf(
            TestItem(1, listOf("/1"), 0),
            TestItem(2, listOf("/2"), 0),
            TestItem(3, listOf("/3"), 0)
        )
        state.setBaseItems(items)
        state.replaceItems(items, state.prepare(items, mediaKeyForPath = { it }))

        state.updateItem(items[1].copy(paths = listOf("/updated")))
        state.recordPlaybackPosition(2, 8_000)

        assertEquals("/updated", state.items[1].paths.single())
        assertEquals("/updated", state.baseItems[1].paths.single())
        assertEquals(3L, state.itemAtOffset(2, 1)?.id)
        assertNull(state.itemAtOffset(3, 1))
        assertEquals(8_000L, state.playbackPosition(2))
        assertTrue(state.containsId(1))
        assertFalse(state.containsId(99))
    }

    private data class TestItem(
        val id: Long,
        val paths: List<String>,
        val positionMs: Long
    )
}
