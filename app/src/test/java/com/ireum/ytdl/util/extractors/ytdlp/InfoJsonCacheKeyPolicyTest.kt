package com.ireum.ytdl.util.extractors.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoJsonCacheKeyPolicyTest {
    @Test
    fun schemeLessAndHttpsInputsShareAuthoritativeKey() {
        val schemeLess = InfoJsonCacheKeyPolicy.resolve("example.com/video/1")
        val https = InfoJsonCacheKeyPolicy.resolve("https://example.com/video/1")

        assertEquals(https.authoritativeIdentity, schemeLess.authoritativeIdentity)
        assertEquals(https.authoritativePrefix, schemeLess.authoritativePrefix)
        assertEquals("https://example.com/video/1", schemeLess.dispatchSource)
    }

    @Test
    fun explicitHttpAndHttpsIdentitiesRemainUnchangedAndDistinct() {
        val http = InfoJsonCacheKeyPolicy.resolve("http://example.com/video/1")
        val https = InfoJsonCacheKeyPolicy.resolve("https://example.com/video/1")

        assertEquals("http://example.com/video/1", http.authoritativeIdentity)
        assertEquals("https://example.com/video/1", https.authoritativeIdentity)
        assertNotEquals(http.authoritativePrefix, https.authoritativePrefix)
        assertNull(InfoJsonCacheKeyPolicy.legacySchemeLessPrefix("http://example.com/video/1", http))
        assertNull(InfoJsonCacheKeyPolicy.legacySchemeLessPrefix("https://example.com/video/1", https))
    }

    @Test
    fun youtubeVideoIdentityRemainsTheVideoId() {
        val short = InfoJsonCacheKeyPolicy.resolve("https://youtu.be/dQw4w9WgXcQ")
        val watch = InfoJsonCacheKeyPolicy.resolve(
            "youtube.com/watch?v=dQw4w9WgXcQ"
        )

        assertEquals("dQw4w9WgXcQ", short.authoritativeIdentity)
        assertEquals(short.authoritativePrefix, watch.authoritativePrefix)
        assertNull(
            InfoJsonCacheKeyPolicy.legacySchemeLessPrefix(
                "youtube.com/watch?v=dQw4w9WgXcQ",
                watch,
            )
        )
    }

    @Test
    fun legacyRawPrefixIsReadOnlyAndWritesUseAuthoritativePrefix() {
        val key = InfoJsonCacheKeyPolicy.resolve("example.com/video/legacy")
        val legacyPrefix = InfoJsonCacheKeyPolicy.legacySchemeLessPrefix(
            "example.com/video/legacy",
            key,
        )!!
        val fileName = key.writeFileName(1234L)

        assertEquals(
            "${InfoJsonCacheKeyPolicy.hash("example.com/video/legacy")}-",
            legacyPrefix
        )
        assertTrue(key.authoritativePrefix.matches(Regex("^[0-9a-f]{1,8}-$")))
        assertTrue(fileName.startsWith(key.authoritativePrefix))
        assertTrue(fileName.endsWith("1234video.info.json"))
        assertTrue(!fileName.startsWith(legacyPrefix))
    }
}
