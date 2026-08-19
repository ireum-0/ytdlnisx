package com.ireum.ytdl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlInputTest {
    @Test
    fun explicitHttpAndHttpsUrlsAreSupported() {
        assertTrue(WebUrlInput.isSupportedWebAddress("https://example.com/video"))
        assertTrue(WebUrlInput.isSupportedWebAddress("http://example.com/video"))
        assertTrue(LinkUtil.isExtractorInput("https://example.com/video"))
    }

    @Test
    fun ftpIsUnsupportedForExtractionButRetainsComparisonIdentity() {
        val input = "ftp://example.com:21/video#section"

        assertFalse(WebUrlInput.isSupportedWebAddress(input))
        assertFalse(LinkUtil.isExtractorInput(input))
        assertEquals(null, WebUrlInput.resolveExtractorInput(input))
        assertEquals(
            WebUrlInput.InputRoute.UnsupportedExplicitScheme("ftp"),
            WebUrlInput.routeInput(input),
        )
        assertEquals("ftp://example.com/video", WebUrlInput.sourceKey(input))
    }

    @Test
    fun routingDistinguishesExtractorSearchAndUnsupportedExplicitInputs() {
        val extractor = WebUrlInput.routeInput("example.com/video")

        assertTrue(extractor is WebUrlInput.InputRoute.Extractor)
        assertEquals(
            "https://example.com/video",
            (extractor as WebUrlInput.InputRoute.Extractor).input.dispatchValue,
        )
        assertEquals(WebUrlInput.InputRoute.SearchQuery, WebUrlInput.routeInput("funny cat videos"))
        assertEquals(WebUrlInput.InputRoute.SearchQuery, WebUrlInput.routeInput("site:youtube cats"))
        assertEquals(
            WebUrlInput.InputRoute.UnsupportedExplicitScheme("content"),
            WebUrlInput.routeInput("content://media/external/video/1"),
        )
    }

    @Test
    fun validSchemeLessDomainsAreSupported() {
        assertTrue(WebUrlInput.isSchemeLessWebAddress("example.com/video"))
        assertTrue(WebUrlInput.isSchemeLessWebAddress("www.example.com/video"))
        assertTrue(LinkUtil.isExtractorInput("example.com/video"))
        assertEquals(
            "https://example.com/video",
            WebUrlInput.resolveExtractorInput("example.com/video")?.dispatchValue,
        )
        assertEquals(
            "https://www.example.com/video",
            WebUrlInput.resolveExtractorInput("www.example.com/video")?.dispatchValue,
        )
        assertEquals(
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            WebUrlInput.resolveExtractorInput(
                "youtube.com/watch?v=dQw4w9WgXcQ"
            )?.dispatchValue,
        )
    }

    @Test
    fun queryAndFragmentDoNotInvalidateSchemeLessDomain() {
        val input = "example.com:8443/video?quality=best&lang=en#comments"

        assertEquals(
            "https://$input",
            WebUrlInput.resolveExtractorInput(input)?.dispatchValue,
        )
    }

    @Test
    fun ipAndExplicitLocalhostInputsFollowExistingWebRules() {
        assertTrue(WebUrlInput.isSchemeLessWebAddress("192.168.1.20:8080/video"))
        assertEquals(
            "https://192.168.1.20:8080/video",
            WebUrlInput.resolveExtractorInput("192.168.1.20:8080/video")?.dispatchValue,
        )
        assertTrue(WebUrlInput.isSupportedWebAddress("http://localhost:8080/video"))
        assertEquals(
            "http://localhost:8080/video",
            WebUrlInput.resolveExtractorInput("http://localhost:8080/video")?.dispatchValue,
        )
        assertFalse(WebUrlInput.isSchemeLessWebAddress("localhost/video"))
    }

    @Test
    fun searchesAndInvalidHostsAreRejected() {
        assertFalse(WebUrlInput.isSchemeLessWebAddress("funny cat videos"))
        assertFalse(WebUrlInput.isSchemeLessWebAddress("version.1"))
        assertFalse(WebUrlInput.isSchemeLessWebAddress("example..com/video"))
        assertFalse(WebUrlInput.isSchemeLessWebAddress("bad_host.example/video"))
        assertFalse(WebUrlInput.isSupportedWebAddress("unknown://example.com/video"))
        assertEquals(null, WebUrlInput.resolveExtractorInput("funny cat videos"))
        assertEquals(null, WebUrlInput.resolveExtractorInput("version.1"))
    }

    @Test
    fun sourceComparisonNormalizesSchemeWithoutRewritingInput() {
        val original = "example.com/video?item=1#section"

        assertEquals(
            WebUrlInput.sourceKey("https://example.com/video?item=1"),
            WebUrlInput.sourceKey(original),
        )
        assertEquals(original, "example.com/video?item=1#section")
        assertEquals(
            original,
            WebUrlInput.resolveExtractorInput(original)?.originalValue,
        )
    }

    @Test
    fun explicitUrlsAreDispatchedWithoutRewritingOrDoubleSchemes() {
        assertEquals(
            "http://example.com/video",
            WebUrlInput.resolveExtractorInput("http://example.com/video")?.dispatchValue,
        )
        assertEquals(
            "https://example.com/video",
            WebUrlInput.resolveExtractorInput("https://example.com/video")?.dispatchValue,
        )
        assertEquals(null, WebUrlInput.resolveExtractorInput("https://https://example.com/video"))
    }

    @Test
    fun normalizedDispatchAndExplicitResultRemainSourceEquivalent() {
        val resolved = WebUrlInput.resolveExtractorInput(
            "example.com:8443/video?item=1#local"
        )!!

        assertEquals(
            resolved.sourceKey,
            WebUrlInput.sourceKey("https://example.com:8443/video?item=1#remote"),
        )
        assertTrue(
            MediaPublishedDateSource.matches(
                resolved.originalValue,
                "https://example.com:8443/video?item=1#remote",
            )
        )
    }
}
