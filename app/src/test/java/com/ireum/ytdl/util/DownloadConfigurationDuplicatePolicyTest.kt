package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConfigurationDuplicatePolicyTest {
    @Test
    fun orderPositionDoesNotAffectMatching() {
        val requested = downloadItem()

        assertTrue(
            DownloadConfigurationDuplicatePolicy.matches(
                requested,
                requested.copy(orderPosition = 999L),
            )
        )
    }

    @Test
    fun mediaPublishedAtDoesNotAffectMatching() {
        val requested = downloadItem()

        assertTrue(
            DownloadConfigurationDuplicatePolicy.matches(
                requested,
                requested.copy(mediaPublishedAt = 1_700_000_000L),
            )
        )
    }

    @Test
    fun runtimeBookkeepingAndDiscoveredMetadataDoNotAffectMatching() {
        val requested = downloadItem()
        val runtimeVariant = requested.copy(
            id = 55L,
            thumb = "https://example.com/new-thumb.jpg",
            duration = "9:59",
            website = "changed.example",
            downloadSize = "900 MiB",
            status = "Active",
            downloadStartTime = 123_456L,
            logID = 91L,
            availableSubtitles = listOf("ko", "en"),
            operationId = "operation-2",
            retryAttempt = 4,
            retryStrategy = "SAFE_FALLBACK",
            lastIssueCode = "NETWORK",
            lastIssueStage = "DOWNLOAD",
            mediaPublishedAt = 1_700_000_000L,
            orderPosition = 999L,
        )

        assertTrue(DownloadConfigurationDuplicatePolicy.matches(requested, runtimeVariant))
    }

    @Test
    fun requestChangingFieldsDoNotMatch() {
        val requested = downloadItem()
        val variants = listOf(
            requested.copy(format = requested.format.copy(format_id = "720p")),
            requested.copy(
                videoPreferences = requested.videoPreferences.copy(removeAudio = true),
            ),
            requested.copy(downloadPath = "/different-destination"),
            requested.copy(extraCommands = "--write-description"),
            requested.copy(customFileNameTemplate = "%(id)s"),
        )

        variants.forEach { variant ->
            assertFalse(DownloadConfigurationDuplicatePolicy.matches(requested, variant))
        }
    }

    @Test
    fun sharedFinderAppliesThePolicyToBothDuplicateCandidateLists() {
        val requested = downloadItem()
        val equivalent = requested.copy(
            id = 88L,
            status = "Active",
            mediaPublishedAt = 1_700_000_000L,
            orderPosition = 12L,
        )

        val viewModelCandidates = mutableListOf(equivalent)
        val observationCandidates = mutableListOf(equivalent.copy(logID = 42L))

        assertEquals(
            equivalent,
            DownloadConfigurationDuplicatePolicy.findMatch(viewModelCandidates, requested),
        )
        assertEquals(
            observationCandidates.single(),
            DownloadConfigurationDuplicatePolicy.findMatch(observationCandidates, requested),
        )
    }

    private fun downloadItem() = DownloadItem(
        id = 0L,
        url = "https://example.com/video",
        title = "Title override",
        author = "Author override",
        thumb = "https://example.com/thumb.jpg",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(format_id = "best", container = "mp4", vcodec = "avc1"),
        container = "mp4",
        downloadSections = "*00:00-01:00",
        allFormats = mutableListOf(Format(format_id = "source", container = "webm")),
        downloadPath = "/downloads",
        website = "example.com",
        downloadSize = "100 MiB",
        playlistTitle = "Playlist override",
        audioPreferences = AudioPreferences(bitrate = "192"),
        videoPreferences = VideoPreferences(embedSubs = true),
        extraCommands = "--embed-metadata",
        customFileNameTemplate = "%(title)s",
        SaveThumb = true,
        status = "Queued",
        downloadStartTime = 0L,
        logID = null,
        playlistURL = "https://example.com/playlist",
        playlistIndex = 3,
        incognito = false,
        availableSubtitles = listOf("en"),
        rowNumber = 3,
        observeSourceId = 7L,
        operationId = "operation-1",
        retryAttempt = 0,
        retryStrategy = "ORIGINAL",
        lastIssueCode = "",
        lastIssueStage = "",
        mediaPublishedAt = 0L,
        orderPosition = 1L,
    )
}
