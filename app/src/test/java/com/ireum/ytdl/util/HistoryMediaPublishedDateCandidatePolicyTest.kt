package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMediaPublishedDateCandidatePolicyTest {
    @Test
    fun explicitAndSchemeLessExtractorSourcesWithZeroDateAreEligible() {
        assertTrue(isEligible("https://example.com/video"))
        assertTrue(isEligible("youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(isEligible("example.com/video"))
    }

    @Test
    fun onlyZeroIsMissingAndNegativeDateIsNotEligible() {
        assertTrue(isEligible("https://example.com/video", mediaPublishedAt = 0L))
        assertFalse(isEligible("https://example.com/video", mediaPublishedAt = -315_532_800L))
    }

    @Test
    fun localMalformedSearchAndUnsupportedTypesAreIneligible() {
        assertFalse(isEligible("ftp://example.com/video"))
        assertFalse(isEligible("content://media/external/video/1"))
        assertFalse(isEligible("C:/Movies/video.mp4"))
        assertFalse(isEligible("example..com/video"))
        assertFalse(isEligible("funny cat videos"))
        assertFalse(isEligible("version.1"))
        assertFalse(
            HistoryMediaPublishedDateCandidatePolicy.isEligible(
                item("https://example.com/video", type = DownloadType.command)
            )
        )
    }

    @Test
    fun mixedRowsSelectOnlyActuallyEligibleMissingDates() {
        val rows = listOf(
            item("https://example.com/explicit", id = 1L),
            item("youtube.com/watch?v=dQw4w9WgXcQ", id = 2L),
            item("example.com/generic", id = 3L),
            item("content://media/external/video/4", id = 4L),
            item("funny cat videos", id = 5L),
            item("https://example.com/dated", id = 6L, mediaPublishedAt = -1L),
        )

        assertEquals(
            listOf(1L, 2L, 3L),
            HistoryMediaPublishedDateCandidatePolicy.select(rows).map(HistoryItem::id),
        )
    }

    @Test
    fun emptySelectionMeansAllEligibleRowsAlreadyHaveDates() {
        val rows = listOf(
            item("https://example.com/dated", mediaPublishedAt = 10L),
            item("content://media/external/video/1"),
            item("not a url"),
        )

        assertTrue(HistoryMediaPublishedDateCandidatePolicy.select(rows).isEmpty())
    }

    @Test
    fun broadDaoPredicateIsASupersetOfTheAuthoritativePolicy() {
        val rows = listOf(
            item("https://example.com/explicit", id = 1L),
            item("example.com/scheme-less", id = 2L),
            item("content://media/external/video/3", id = 3L),
            item("https://example.com/dated", id = 4L, mediaPublishedAt = -1L),
            item("https://example.com/command", id = 5L, type = DownloadType.command),
        )
        val broadDaoRows = rows.filter { row ->
            row.mediaPublishedAt == MediaPublishedDate.MISSING &&
                row.type in setOf(DownloadType.video, DownloadType.audio)
        }

        assertEquals(
            HistoryMediaPublishedDateCandidatePolicy.select(rows),
            HistoryMediaPublishedDateCandidatePolicy.select(broadDaoRows),
        )
    }

    private fun isEligible(url: String, mediaPublishedAt: Long = 0L): Boolean {
        return HistoryMediaPublishedDateCandidatePolicy.isEligible(
            item(url, mediaPublishedAt = mediaPublishedAt)
        )
    }

    private fun item(
        url: String,
        id: Long = 1L,
        type: DownloadType = DownloadType.video,
        mediaPublishedAt: Long = 0L,
    ) = HistoryItem(
        id = id,
        url = url,
        title = "title",
        author = "author",
        duration = "",
        thumb = "",
        type = type,
        time = 1L,
        downloadPath = emptyList(),
        website = "example",
        format = Format(),
        downloadId = 0L,
        mediaPublishedAt = mediaPublishedAt,
    )
}
