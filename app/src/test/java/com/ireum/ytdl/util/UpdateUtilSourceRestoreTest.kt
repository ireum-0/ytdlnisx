package com.ireum.ytdl.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateUtilSourceRestoreTest {
    @Test
    fun sameEffectiveSourceRetainsDestinationGeneration() {
        assertEquals(41L, UpdateUtil.restoredSourceGeneration("nightly", 41L, "nightly"))
    }

    @Test
    fun changedSourceAdvancesDestinationGeneration() {
        assertEquals(42L, UpdateUtil.restoredSourceGeneration("stable", 41L, "nightly"))
    }

    @Test
    fun absentDefaultSourceKeepsZeroGeneration() {
        assertEquals(0L, UpdateUtil.restoredSourceGeneration("stable", 0L, "stable"))
    }

    @Test
    fun changedSourceResetToDefaultStillAdvancesGeneration() {
        assertEquals(42L, UpdateUtil.restoredSourceGeneration("nightly", 41L, "stable"))
    }

    @Test
    fun sameSourceAtMaximumGenerationDoesNotAllocateAgain() {
        assertEquals(Long.MAX_VALUE, UpdateUtil.restoredSourceGeneration("stable", Long.MAX_VALUE, "stable"))
    }

    @Test(expected = IllegalStateException::class)
    fun changedSourceAtMaximumGenerationFailsClosed() {
        UpdateUtil.restoredSourceGeneration("stable", Long.MAX_VALUE, "nightly")
    }

    @Test(expected = IllegalStateException::class)
    fun invalidDestinationGenerationFailsClosed() {
        UpdateUtil.restoredSourceGeneration("stable", -1L, "nightly")
    }
}
