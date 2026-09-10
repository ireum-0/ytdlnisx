package com.ireum.ytdl.util.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadArchiveIdentityTest {
    @Test
    fun validYouTubeArchiveIdentityMatchesSupportedSourceForms() {
        val lines = listOf("youtube dQw4w9WgXcQ", "youtube OTHERID000")
        assertTrue(
            DownloadArchiveIdentity.matchesSource(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                lines,
            ),
        )
        assertTrue(
            DownloadArchiveIdentity.matchesSource(
                "https://youtu.be/dQw4w9WgXcQ",
                lines,
            ),
        )
    }

    @Test
    fun extractorIdentityAndExactIdAreRequired() {
        val lines = listOf("vimeo dQw4w9WgXcQ", "youtube dQw4w9WgXcQ-extra")
        assertFalse(
            DownloadArchiveIdentity.matchesSource(
                "https://youtu.be/dQw4w9WgXcQ",
                lines,
            ),
        )
        assertFalse(
            DownloadArchiveIdentity.matchesSource(
                "https://youtu.be/9bZkp7q19f0",
                listOf("youtube dQw4w9WgXcQ"),
            ),
        )
    }

    @Test
    fun malformedLinesAndUrlSubstringsNeverBecomeAuthority() {
        assertFalse(
            DownloadArchiveIdentity.matchesSource(
                "https://example.test/watch?id=dQw4w9WgXcQ",
                listOf("youtube dQw4w9WgXcQ"),
            ),
        )
        assertFalse(
            DownloadArchiveIdentity.matchesSource(
                "https://youtu.be/dQw4w9WgXcQ",
                listOf("youtube", "youtube dQw4w9WgXcQ extra", "bad dQw4w9WgXcQ"),
            ),
        )
    }
}
