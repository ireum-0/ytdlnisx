package com.ireum.ytdl.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMetadataEnrichmentPolicyTest {
    @Test
    fun extractorItemWithOnlyMissingSourceDateIsEligible() {
        assertTrue(candidate(source = "example.com/video", mediaPublishedAt = 0L))
    }

    @Test
    fun validNegativeSourceDateCountsAsPresent() {
        assertFalse(candidate(mediaPublishedAt = -315_532_800L))
    }

    @Test
    fun fullyCompleteItemIsSkipped() {
        assertFalse(candidate(mediaPublishedAt = 1_700_000_000L))
    }

    @Test
    fun missingCoreMetadataRemainsEligibleWhenSourceDateIsPresent() {
        assertTrue(candidate(title = "", mediaPublishedAt = -315_532_800L))
    }

    @Test
    fun localSearchAndMalformedSourcesAreIneligible() {
        assertFalse(candidate(source = "ftp://example.com/video", title = ""))
        assertFalse(candidate(source = "content://media/external/video/1", title = ""))
        assertFalse(candidate(source = "funny cat videos", title = ""))
        assertFalse(candidate(source = "version.1", title = ""))
    }

    private fun candidate(
        source: String = "https://example.com/video",
        title: String = "title",
        author: String = "author",
        thumbnail: String = "thumbnail",
        mediaPublishedAt: Long = 0L,
    ): Boolean {
        return DownloadMetadataEnrichmentPolicy.shouldEnrich(
            source = source,
            title = title,
            author = author,
            thumbnail = thumbnail,
            mediaPublishedAt = mediaPublishedAt,
        )
    }
}
