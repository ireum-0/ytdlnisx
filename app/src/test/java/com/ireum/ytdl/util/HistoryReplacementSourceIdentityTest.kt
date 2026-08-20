package com.ireum.ytdl.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReplacementSourceIdentityTest {
    @Test
    fun genericHttpAndHttpsEndpointsDoNotMatch() {
        assertFalse(
            HistoryReplacementSourceIdentity.matches(
                "http://example.com/video",
                "https://example.com/video",
            )
        )
        assertFalse(
            HistoryReplacementSourceIdentity.matches(
                "https://example.com/video",
                "http://example.com/video",
            )
        )
    }

    @Test
    fun portsAreNormalizedOnlyForTheirOwnScheme() {
        assertFalse(
            HistoryReplacementSourceIdentity.matches(
                "https://example.com/video",
                "http://example.com:443/video",
            )
        )
        assertFalse(
            HistoryReplacementSourceIdentity.matches(
                "http://example.com/video",
                "https://example.com:80/video",
            )
        )
        assertTrue(
            HistoryReplacementSourceIdentity.matches(
                "http://example.com:80/video",
                "http://example.com/video",
            )
        )
        assertTrue(
            HistoryReplacementSourceIdentity.matches(
                "https://example.com:443/video",
                "https://example.com/video",
            )
        )
    }

    @Test
    fun nonDefaultPortsRemainIdentifying() {
        assertTrue(
            HistoryReplacementSourceIdentity.matches(
                "https://example.com:8443/video",
                "https://example.com:8443/video",
            )
        )
        assertFalse(
            HistoryReplacementSourceIdentity.matches(
                "https://example.com:8443/video",
                "https://example.com:9443/video",
            )
        )
    }

    @Test
    fun schemeLessInputUsesItsHttpsDispatchIdentity() {
        assertTrue(
            HistoryReplacementSourceIdentity.matches(
                "example.com/video",
                "https://example.com/video",
            )
        )
    }

    @Test
    fun canonicalWebDetailsRemainNonIdentifying() {
        assertTrue(
            HistoryReplacementSourceIdentity.matches(
                "HTTPS://Example.com./video?utm_source=feed#one",
                "https://example.com/video#two",
            )
        )
    }

    @Test
    fun equivalentYoutubeFormsRetainStableProviderIdentity() {
        assertTrue(
            HistoryReplacementSourceIdentity.matches(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ?t=5",
            )
        )
    }

    @Test
    fun blankSourceRemainsFailClosed() {
        assertFalse(
            HistoryReplacementSourceIdentity.matches(
                "",
                "https://example.com/video",
            )
        )
    }
}
