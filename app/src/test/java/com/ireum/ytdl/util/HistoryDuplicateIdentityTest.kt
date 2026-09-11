package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDuplicateIdentityTest {
    private val youtubeWatch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val youtubeShort = "https://youtu.be/dQw4w9WgXcQ?t=5"

    @Test
    fun equivalentYoutubeFormsShareIdentityForCompatibleType() {
        assertTrue(
            HistoryDuplicateIdentity.matches(
                DownloadType.video,
                youtubeWatch,
                DownloadType.video,
                youtubeShort,
            )
        )
    }

    @Test
    fun differentTypesNeverShareDestructiveIdentity() {
        assertFalse(
            HistoryDuplicateIdentity.matches(
                DownloadType.video,
                youtubeWatch,
                DownloadType.audio,
                youtubeShort,
            )
        )
    }

    @Test
    fun genericQueryAndSchemeRemainIdentifying() {
        assertFalse(
            HistoryDuplicateIdentity.matches(
                DownloadType.video,
                "https://example.com/video?asset=one",
                DownloadType.video,
                "https://example.com/video?asset=two",
            )
        )
        assertFalse(
            HistoryDuplicateIdentity.matches(
                DownloadType.video,
                "http://example.com/video",
                DownloadType.video,
                "https://example.com/video",
            )
        )
    }

    @Test
    fun genericCanonicalWebIdentityCanGroupWithoutTitle() {
        assertTrue(
            HistoryDuplicateIdentity.matches(
                DownloadType.video,
                "HTTPS://Example.com./video",
                DownloadType.video,
                "https://example.com/video",
            )
        )
    }

    @Test
    fun unsupportedOrUnprovableSourcesFailClosed() {
        listOf(
            "",
            "same title, not a source",
            "yt-dlp https://example.com/video",
            "/storage/emulated/0/video.mp4",
            "content://media/video",
            "https://youtube.com/watch?v=not-a-video-id",
        ).forEach { source ->
            assertTrue("$source must be rejected", HistoryDuplicateIdentity.key(DownloadType.video, source) == null)
        }
    }

    @Test
    fun providerIdentityDoesNotCollapseWithGenericOrOtherProvider() {
        assertFalse(
            HistoryDuplicateIdentity.matches(
                DownloadType.video,
                youtubeWatch,
                DownloadType.video,
                "https://piped.video/watch?v=dQw4w9WgXcQ",
            )
        )
        assertNotNull(
            HistoryDuplicateIdentity.key(
                DownloadType.video,
                "https://piped.video/watch?v=dQw4w9WgXcQ",
            )
        )
    }
}
