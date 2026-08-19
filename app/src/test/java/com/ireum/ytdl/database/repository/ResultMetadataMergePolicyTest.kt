package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.util.ExtractorSourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultMetadataMergePolicyTest {
    @Test
    fun fallbackFillsMissingFieldsWithoutOverwritingFreshValues() {
        val merged = ResultMetadataMergePolicy.merge(
            primary = item(
                title = "fresh title",
                author = "",
                thumb = "fresh-thumb",
                mediaPublishedAt = 0L,
            ),
            fallback = item(
                title = "cached title",
                author = "cached author",
                thumb = "cached-thumb",
                mediaPublishedAt = -315_532_800L,
            ),
        )

        assertEquals("fresh title", merged.title)
        assertEquals("cached author", merged.author)
        assertEquals("fresh-thumb", merged.thumb)
        assertEquals(-315_532_800L, merged.mediaPublishedAt)
    }

    @Test
    fun blankFallbackDoesNotReplaceUsablePrimaryMetadata() {
        val primary = item(
            title = "existing title",
            author = "existing author",
            thumb = "existing-thumb",
            mediaPublishedAt = 100L,
        )

        assertEquals(primary, ResultMetadataMergePolicy.merge(primary, item()))
    }

    @Test
    fun mergePreservesFreshExtractorSourceIdentity() {
        val identity = ExtractorSourceIdentity(
            originalUrl = "https://example.com/redirect",
            canonicalUrl = "https://media.example.com/video",
        )
        val primary = item(title = "fresh").also { it.sourceIdentity = identity }

        val merged = ResultMetadataMergePolicy.merge(primary, item(author = "cached"))

        assertSame(identity, merged.sourceIdentity)
    }

    @Test
    fun blankFreshUrlRequiresAndReceivesCachedFallback() {
        val fresh = item(
            url = "",
            title = "fresh title",
            author = "fresh author",
            thumb = "fresh-thumb",
            mediaPublishedAt = 100L,
        )

        assertTrue(ResultMetadataMergePolicy.needsFallback(fresh))
        assertEquals(
            "https://example.com/video",
            ResultMetadataMergePolicy.merge(fresh, item()).url,
        )
    }

    private fun item(
        url: String = "https://example.com/video",
        title: String = "",
        author: String = "",
        thumb: String = "",
        mediaPublishedAt: Long = 0L,
    ): ResultItem {
        return ResultItem(
            id = 0L,
            url = url,
            title = title,
            author = author,
            duration = "",
            thumb = thumb,
            website = "",
            playlistTitle = "",
            urls = "",
            chapters = null,
            mediaPublishedAt = mediaPublishedAt,
        )
    }
}
