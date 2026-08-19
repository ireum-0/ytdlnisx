package com.ireum.ytdl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticKeywordNormalizerTest {
    @Test
    fun equivalentYoutubePlaylistUrlsHaveStableIdentity() {
        assertEquals(
            "youtube:playlist:PL123",
            AutomaticKeywordNormalizer.playlistConditionKey(
                "https://www.youtube.com/playlist?list=PL123"
            )
        )
        assertEquals(
            "youtube:playlist:PL123",
            AutomaticKeywordNormalizer.playlistConditionKey(
                "https://m.youtube.com/watch?v=abc&list=PL123"
            )
        )
    }

    @Test
    fun youtubeWatchPlaylistUrlIsCanonicalizedForFetching() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PL123",
            AutomaticKeywordNormalizer.canonicalPlaylistUrl(
                "https://m.youtube.com/watch?v=abc&list=PL123&index=2"
            )
        )
    }

    @Test
    fun youtubeVideoFormsHaveStableIdentity() {
        assertEquals(
            AutomaticKeywordNormalizer.videoKey("https://youtu.be/abc"),
            AutomaticKeywordNormalizer.videoKey("https://www.youtube.com/watch?v=abc&list=PL123")
        )
    }

    @Test
    fun singleYoutubeVideoIsNotAcceptedAsPlaylistRule() {
        assertNull(
            AutomaticKeywordNormalizer.playlistConditionKey(
                "https://www.youtube.com/watch?v=abc"
            )
        )
    }

    @Test
    fun hostlessHttpUriIsNotAcceptedAsPlaylistRule() {
        assertNull(AutomaticKeywordNormalizer.playlistConditionKey("https:playlist"))
    }

    @Test
    fun keywordParsingIsCaseInsensitiveAndStable() {
        assertEquals(
            listOf("Music", "Live"),
            AutomaticKeywordNormalizer.parseKeywords(" Music, music\nLive ")
        )
    }
}
