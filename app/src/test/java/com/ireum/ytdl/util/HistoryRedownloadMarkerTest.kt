package com.ireum.ytdl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRedownloadMarkerTest {
    @Test
    fun regularMarkerRemainsBackwardCompatible() {
        val encoded = HistoryRedownloadMarker.regular(42L)

        assertEquals("history-redownload:42", encoded)
        assertEquals(HistoryRedownloadMarker(42L), HistoryRedownloadMarker.parse(encoded))
    }

    @Test
    fun qualityMarkerPersistsReplacementTargetAcrossWorkerSerialization() {
        val encoded = HistoryRedownloadMarker.quality(42L, 1080)

        assertEquals("history-redownload:42:quality:1080", encoded)
        assertEquals(
            HistoryRedownloadMarker(42L, expectedMinimumHeight = 1080),
            HistoryRedownloadMarker.parse(encoded)
        )
    }

    @Test
    fun malformedMarkersAreRejected() {
        assertNull(HistoryRedownloadMarker.parse(null))
        assertNull(HistoryRedownloadMarker.parse("history-redownload:0"))
        assertNull(HistoryRedownloadMarker.parse("history-redownload:42:quality:0"))
        assertNull(HistoryRedownloadMarker.parse("history-redownload:42:other:1080"))
    }

    @Test
    fun regularAndQualityMarkersForTheSameHistoryItemCannotBeQueuedTogether() {
        val seen = mutableSetOf<Long>()
        assertFalse(
            HistoryRedownloadQueuePolicy.isDuplicate(
                HistoryRedownloadMarker.regular(42L),
                emptyList(),
                seen
            )
        )
        assertTrue(
            HistoryRedownloadQueuePolicy.isDuplicate(
                HistoryRedownloadMarker.quality(42L, 1080),
                emptyList(),
                seen
            )
        )
        assertTrue(
            HistoryRedownloadQueuePolicy.isDuplicate(
                HistoryRedownloadMarker.quality(7L, 720),
                listOf(HistoryRedownloadMarker.regular(7L)),
                mutableSetOf()
            )
        )
    }

    @Test
    fun explicitHistoryReplacementBypassesTheDownloadArchive() {
        assertTrue(HistoryRedownloadQueuePolicy.shouldUseDownloadArchive(null))
        assertTrue(HistoryRedownloadQueuePolicy.shouldUseDownloadArchive("playlist-source"))
        assertFalse(
            HistoryRedownloadQueuePolicy.shouldUseDownloadArchive(
                HistoryRedownloadMarker.quality(42L, 1080)
            )
        )
    }

    @Test
    fun replacementFilePolicyNeverDeletesAnOriginalOnFailure() {
        val originals = listOf(" old-video.mp4 ", "old-thumb.jpg")
        val candidates = listOf("old-video.mp4", "new-video.mkv", "new-video.mkv")

        assertEquals(
            listOf("new-video.mkv"),
            HistoryReplacementFilePolicy.rejectedPathsToDelete(originals, candidates)
        )
        assertEquals(
            listOf("old-video.mp4", "old-thumb.jpg"),
            HistoryReplacementFilePolicy.originalPathsToDelete(originals, listOf("new-video.mkv"))
        )
        assertEquals(
            emptyList<String>(),
            HistoryReplacementFilePolicy.originalPathsToDelete(originals, emptyList())
        )
    }
}
