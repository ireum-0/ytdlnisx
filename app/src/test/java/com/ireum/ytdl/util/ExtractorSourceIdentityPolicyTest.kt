package com.ireum.ytdl.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorSourceIdentityPolicyTest {
    @Test
    fun exactRequestedUrlIsAccepted() {
        assertMatches(
            requested = "https://example.com/video/1",
            identity = identity(canonical = "https://example.com/video/1"),
        )
    }

    @Test
    fun schemeLessAndHttpRequestsMatchExplicitCanonicalForms() {
        assertMatches(
            requested = "example.com/video/1",
            identity = identity(canonical = "https://example.com/video/1"),
        )
        assertMatches(
            requested = "http://example.com/video/1",
            identity = identity(canonical = "https://example.com/video/1"),
        )
    }

    @Test
    fun youtubeAlternateFormsRequireTheSameVideoId() {
        assertMatches(
            requested = "https://youtu.be/dQw4w9WgXcQ",
            identity = identity(
                canonical = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                stableId = "dQw4w9WgXcQ",
                extractor = "Youtube",
            ),
        )
        assertDoesNotMatch(
            requested = "https://youtu.be/dQw4w9WgXcQ",
            identity = identity(
                canonical = "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                stableId = "aaaaaaaaaaa",
                extractor = "Youtube",
            ),
        )
    }

    @Test
    fun knownProviderRedirectCanUseStableMediaIdentity() {
        assertMatches(
            requested = "https://player.vimeo.com/video/123456",
            identity = identity(
                canonical = "https://vimeo.com/123456",
                stableId = "123456",
                extractor = "Vimeo",
            ),
        )
        assertDoesNotMatch(
            requested = "https://player.vimeo.com/video/123456",
            identity = identity(
                canonical = "https://vimeo.com/654321",
                stableId = "654321",
                extractor = "Vimeo",
            ),
        )
    }

    @Test
    fun extractorOriginalUrlValidatesGenericCanonicalRedirect() {
        val requested = "https://redirect.example/go/abc?utm_source=history"

        assertMatches(
            requested = requested,
            identity = identity(
                original = requested,
                canonical = "https://media.example/watch/abc",
                stableId = "abc",
                extractor = "Generic",
            ),
        )
    }

    @Test
    fun canonicalizationMayRemoveKnownTrackingParametersButNotMediaParameters() {
        assertMatches(
            requested = "https://example.com/watch/abc?utm_source=share&fbclid=tracking",
            identity = identity(canonical = "https://example.com/watch/abc"),
        )
        assertDoesNotMatch(
            requested = "https://example.com/watch?video=abc&utm_source=share",
            identity = identity(canonical = "https://example.com/watch?video=def"),
        )
    }

    @Test
    fun requestedSourceAloneIsNeverAcceptedAsExtractorEvidence() {
        assertDoesNotMatch(
            requested = "https://example.com/video/1",
            identity = ExtractorSourceIdentity(
                requestedSource = "https://example.com/video/1"
            ),
        )
    }

    @Test
    fun unrelatedSearchAndDifferentProviderItemsAreRejected() {
        assertDoesNotMatch(
            requested = "funny cat videos",
            identity = identity(canonical = "https://example.com/video/1"),
        )
        assertDoesNotMatch(
            requested = "https://example.com/video/1",
            identity = identity(canonical = "https://example.com/video/2"),
        )
    }

    @Test
    fun playlistRequestIsNotEquivalentToAnIndividualPlaylistItem() {
        val requested = "https://www.youtube.com/playlist?list=PL1234567890"
        assertDoesNotMatch(
            requested = requested,
            identity = identity(
                original = requested,
                canonical = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890",
                stableId = "dQw4w9WgXcQ",
                extractor = "Youtube",
                resultType = "video",
            ),
        )
        assertMatches(
            requested = requested,
            identity = identity(
                original = requested,
                canonical = requested,
                stableId = "PL1234567890",
                extractor = "YoutubeTab",
                resultType = "playlist",
            ),
        )
    }

    @Test
    fun genericPlaylistProvenanceDoesNotValidateAnIndividualItem() {
        val playlist = "https://media.example/collection/7"
        assertDoesNotMatch(
            requested = playlist,
            identity = ExtractorSourceIdentity(
                originalUrl = playlist,
                canonicalUrl = "https://media.example/video/42",
                resultType = "video",
                playlistUrl = playlist,
            ),
        )
    }

    @Test
    fun cachedInfoValidationUsesOriginalAndCanonicalProvenance() {
        assertMatches(
            requested = "https://short.example/item/7",
            identity = identity(
                original = "https://short.example/item/7",
                canonical = "https://canonical.example/media/7",
            ),
        )
        assertDoesNotMatch(
            requested = "https://short.example/item/8",
            identity = identity(
                original = "https://short.example/item/7",
                canonical = "https://canonical.example/media/7",
            ),
        )
    }

    @Test
    fun manualLocalAddAndBackfillValidationAcceptCanonicalRedirectProvenance() {
        val identity = identity(
            original = "https://example.com/redirect/42",
            canonical = "https://cdn.example.com/watch/42",
            stableId = "42",
            extractor = "Generic",
        )

        assertMatches("https://example.com/redirect/42", identity)
        assertMatches("example.com/redirect/42", identity)
    }

    private fun identity(
        original: String = "",
        canonical: String = "",
        stableId: String = "",
        extractor: String = "",
        resultType: String = "video",
    ) = ExtractorSourceIdentity(
        originalUrl = original,
        canonicalUrl = canonical,
        stableMediaId = stableId,
        extractor = extractor,
        resultType = resultType,
    )

    private fun assertMatches(requested: String, identity: ExtractorSourceIdentity) {
        assertTrue(ExtractorSourceIdentityPolicy.matchesRequestedSource(requested, identity))
    }

    private fun assertDoesNotMatch(requested: String, identity: ExtractorSourceIdentity) {
        assertFalse(ExtractorSourceIdentityPolicy.matchesRequestedSource(requested, identity))
    }
}
