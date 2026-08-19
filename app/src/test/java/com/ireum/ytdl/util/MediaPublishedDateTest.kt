package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.ResultItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MediaPublishedDateTest {
    @Test
    fun resolvePrefersReleaseMetadataThenUploadMetadata() {
        assertEquals(
            1_600_000_000L,
            MediaPublishedDateParser.resolve(
                releaseTimestampSeconds = 1_600_000_000L,
                timestampSeconds = 1_700_000_000L,
                releaseDate = "20240102",
                uploadDate = "20240203"
            )
        )

        assertEquals(
            Instant.parse("2024-01-02T00:00:00Z").epochSecond,
            MediaPublishedDateParser.resolve(
                timestampSeconds = 1_700_000_000L,
                releaseDate = "20240102",
                uploadDate = "20240203"
            )
        )
    }

    @Test
    fun distinctExternalParserPathsProduceCanonicalEpochSeconds() {
        assertEquals(
            Instant.parse("2024-03-04T05:06:07Z").epochSecond,
            MediaPublishedDateParser.resolve(
                publishedAt = "2024-03-04T05:06:07Z"
            )
        )
        assertEquals(
            1_700_000_000L,
            MediaPublishedDateParser.resolve(timestampSeconds = 1_700_000_000L)
        )
        assertEquals(
            1_700_000_000L,
            MediaPublishedDateParser.fromEpochSeconds(1_700_000_000L)
        )
    }

    @Test
    fun explicitEpochUnitsHandlePositiveAndNegativeValues() {
        val modernSeconds = 1_700_000_000L
        val expected = Instant.parse("1960-01-02T00:00:00Z").epochSecond

        assertEquals(modernSeconds, MediaPublishedDateParser.fromEpochSeconds(modernSeconds))
        assertEquals(
            modernSeconds,
            MediaPublishedDateParser.fromEpochMilliseconds(modernSeconds * 1000L)
        )
        assertEquals(expected, MediaPublishedDateParser.fromEpochSeconds(expected))
        assertEquals(
            expected,
            MediaPublishedDateParser.fromEpochMilliseconds(expected * 1000L)
        )
        assertEquals(
            expected,
            MediaPublishedDateParser.resolve(releaseDate = "19600102")
        )
        assertEquals(
            expected,
            MediaPublishedDateParser.resolve(releaseTimestampSeconds = expected)
        )
    }

    @Test
    fun nearEpochMillisecondsAreNeverGuessedAsSeconds() {
        assertEquals(
            1_000_000_000L,
            MediaPublishedDateParser.resolve(timestampSeconds = 1_000_000_000L)
        )
        assertEquals(
            1_000_000L,
            MediaPublishedDateParser.fromEpochMilliseconds(1_000_000_000L)
        )
        assertEquals(-1L, MediaPublishedDateParser.fromEpochMilliseconds(-1L))
        assertEquals(-1L, MediaPublishedDateParser.fromEpochMilliseconds(-1_000L))
        assertEquals(1L, MediaPublishedDateParser.fromEpochMilliseconds(1_000L))
        assertEquals(null, MediaPublishedDateParser.fromEpochMilliseconds(1L))
    }

    @Test
    fun normalizedEpochSecondsAreNotConvertedAgain() {
        val normalized = MediaPublishedDateParser.fromEpochMilliseconds(1_000_000_000L)

        assertEquals(1_000_000L, normalized)
        assertEquals(normalized, MediaPublishedDateParser.fromEpochSeconds(normalized))
    }

    @Test
    fun resolveRejectsMissingAndInvalidDates() {
        assertEquals(0L, MediaPublishedDateParser.resolve())
        assertEquals(0L, MediaPublishedDateParser.resolve(uploadDate = "unknown"))
        assertEquals(null, MediaPublishedDateParser.fromEpochSeconds(Long.MIN_VALUE))
        assertEquals(null, MediaPublishedDateParser.fromEpochMilliseconds(Long.MIN_VALUE))
    }

    @Test
    fun epochZeroRemainsTheExplicitMissingSentinel() {
        assertEquals(
            MediaPublishedDate.MISSING,
            MediaPublishedDateParser.resolve(timestampSeconds = 0L)
        )
        assertEquals(null, MediaPublishedDateParser.fromEpochSeconds(0L))
        assertEquals(null, MediaPublishedDateParser.fromEpochMilliseconds(0L))
        assertEquals(false, MediaPublishedDate.isPresent(0L))
        assertEquals(true, MediaPublishedDate.isPresent(-1L))
        assertEquals(true, MediaPublishedDate.isPresent(1L))
    }

    @Test
    fun canonicalEpochSecondsSurvivePersistenceModelRoundTrip() {
        val canonicalSeconds = requireNotNull(
            MediaPublishedDateParser.fromEpochMilliseconds(1_000_000_000L)
        )
        val result = resultItem(canonicalSeconds)
        val persisted = historyItem(result.mediaPublishedAt)

        assertEquals(1_000_000L, persisted.mediaPublishedAt)
        assertEquals(
            persisted.mediaPublishedAt,
            MediaPublishedDateParser.fromEpochSeconds(persisted.mediaPublishedAt)
        )
    }

    @Test
    fun sourceMatchingIgnoresSurroundingWhitespaceButNotUrlChanges() {
        assertEquals(
            true,
            MediaPublishedDateSource.matches(
                "  https://example.com/watch?v=1 ",
                "https://example.com/watch?v=1"
            )
        )
        assertEquals(
            false,
            MediaPublishedDateSource.matches(
                "https://example.com/watch?v=1",
                "https://example.com/watch?v=2"
            )
        )
    }

    @Test
    fun sourceMatchingTreatsEquivalentYoutubeUrlsAsTheSameVideo() {
        assertEquals(
            true,
            MediaPublishedDateSource.matches(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ?t=5"
            )
        )
        assertEquals(
            false,
            MediaPublishedDateSource.matches(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/aaaaaaaaaaa"
            )
        )
    }

    @Test
    fun sourceMatchingAcceptsYoutubePrivacyEnhancedEmbeds() {
        assertEquals(
            true,
            MediaPublishedDateSource.matches(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"
            )
        )
    }

    @Test
    fun sourceMatchingRejectsMalformedYoutubeQueryEscapesWithoutThrowing() {
        assertEquals(
            false,
            MediaPublishedDateSource.matches(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=%"
            )
        )
    }

    @Test
    fun sourceMatchingTreatsSchemeLessAndExplicitWebUrlsAsEquivalent() {
        assertEquals(
            true,
            MediaPublishedDateSource.matches(
                "example.com/video?item=1#details",
                "https://example.com/video?item=1",
            ),
        )
        assertEquals(
            false,
            MediaPublishedDateSource.matches(
                "example.com/video?item=1",
                "https://example.com/video?item=2",
            ),
        )
    }

    @Test
    fun useDownloadDateMixesUndatedItemsIntoTimeline() {
        val sorted = MediaPublishedDateOrder.sorted(
            items = items,
            policy = MissingSourceDatePolicy.USE_DOWNLOAD_DATE,
            descending = true,
            mediaPublishedAt = Item::publishedAt,
            downloadTime = Item::downloadedAt,
            stableId = Item::id
        )

        assertEquals(listOf(3L, 2L, 1L), sorted.map(Item::id))
    }

    @Test
    fun groupedPoliciesKeepUndatedItemsAtFixedEdge() {
        val first = MediaPublishedDateOrder.sorted(
            items = items,
            policy = MissingSourceDatePolicy.GROUP_FIRST,
            descending = false,
            mediaPublishedAt = Item::publishedAt,
            downloadTime = Item::downloadedAt,
            stableId = Item::id
        )
        val last = MediaPublishedDateOrder.sorted(
            items = items,
            policy = MissingSourceDatePolicy.GROUP_LAST,
            descending = true,
            mediaPublishedAt = Item::publishedAt,
            downloadTime = Item::downloadedAt,
            stableId = Item::id
        )

        assertEquals(listOf(2L, 1L, 3L), first.map(Item::id))
        assertEquals(listOf(3L, 1L, 2L), last.map(Item::id))
    }

    @Test
    fun groupedSortingTreatsNegativeEpochAsPublished() {
        val sorted = MediaPublishedDateOrder.sorted(
            items = listOf(
                Item(id = 1L, publishedAt = -315_532_800L, downloadedAt = 900L),
                Item(id = 2L, publishedAt = 0L, downloadedAt = 800L),
                Item(id = 3L, publishedAt = 300L, downloadedAt = 700L),
            ),
            policy = MissingSourceDatePolicy.GROUP_LAST,
            descending = true,
            mediaPublishedAt = Item::publishedAt,
            downloadTime = Item::downloadedAt,
            stableId = Item::id,
        )

        assertEquals(listOf(3L, 1L, 2L), sorted.map(Item::id))
    }

    @Test
    fun sortingOrdersPreEpochNearEpochAndModernDatesInCanonicalSeconds() {
        val sorted = MediaPublishedDateOrder.sorted(
            items = listOf(
                Item(id = 1L, publishedAt = -1L, downloadedAt = 1L),
                Item(id = 2L, publishedAt = 1_000_000L, downloadedAt = 1L),
                Item(id = 3L, publishedAt = 1_700_000_000L, downloadedAt = 1L),
            ),
            policy = MissingSourceDatePolicy.GROUP_LAST,
            descending = true,
            mediaPublishedAt = Item::publishedAt,
            downloadTime = Item::downloadedAt,
            stableId = Item::id,
        )

        assertEquals(listOf(3L, 2L, 1L), sorted.map(Item::id))
    }

    @Test
    fun sqlOrderingMatchesGroupedPolicyContract() {
        assertEquals(
            "CASE WHEN mediaPublishedAt != 0 THEN 0 ELSE 1 END ASC, " +
                "CASE WHEN mediaPublishedAt != 0 THEN mediaPublishedAt ELSE time END DESC, " +
                "time DESC, id DESC",
            MediaPublishedDateOrder.sqlOrderBy(
                policy = MissingSourceDatePolicy.GROUP_LAST,
                descending = true
            )
        )
    }

    private data class Item(
        val id: Long,
        val publishedAt: Long,
        val downloadedAt: Long
    )

    private val items = listOf(
        Item(id = 1L, publishedAt = 100L, downloadedAt = 500L),
        Item(id = 2L, publishedAt = 0L, downloadedAt = 200L),
        Item(id = 3L, publishedAt = 300L, downloadedAt = 100L)
    )

    private fun resultItem(mediaPublishedAt: Long) = ResultItem(
        id = 0L,
        url = "https://example.com/video",
        title = "title",
        author = "author",
        duration = "",
        thumb = "",
        website = "example",
        playlistTitle = "",
        urls = "",
        chapters = null,
        mediaPublishedAt = mediaPublishedAt,
    )

    private fun historyItem(mediaPublishedAt: Long) = HistoryItem(
        id = 0L,
        url = "https://example.com/video",
        title = "title",
        author = "author",
        duration = "",
        thumb = "",
        type = DownloadType.video,
        time = 1L,
        downloadPath = emptyList(),
        website = "example",
        format = Format(),
        downloadId = 0L,
        mediaPublishedAt = mediaPublishedAt,
    )
}

class MediaPublishedDateBackfillTrackerTest {
    @Test
    fun noSuccessfulUpdatesDoNotRefresh() {
        val tracker = MediaPublishedDateBackfillTracker()
        var refreshes = 0

        tracker.recordUpdate(false)
        tracker.dispatchRefreshIfNeeded { refreshes += 1 }

        assertEquals(0, tracker.updatedCount)
        assertEquals(0, refreshes)
    }

    @Test
    fun oneSuccessfulUpdateRefreshesOnce() {
        val tracker = MediaPublishedDateBackfillTracker()
        var refreshes = 0

        tracker.recordUpdate(true)
        tracker.dispatchRefreshIfNeeded { refreshes += 1 }
        tracker.dispatchRefreshIfNeeded { refreshes += 1 }

        assertEquals(1, tracker.updatedCount)
        assertEquals(1, refreshes)
    }

    @Test
    fun manyAndPartialSuccessfulUpdatesStillRefreshOnce() {
        val tracker = MediaPublishedDateBackfillTracker()
        var refreshes = 0

        listOf(true, false, true, true, false).forEach(tracker::recordUpdate)
        tracker.dispatchRefreshIfNeeded { refreshes += 1 }
        tracker.dispatchRefreshIfNeeded { refreshes += 1 }

        assertEquals(3, tracker.updatedCount)
        assertEquals(1, refreshes)
    }
}
