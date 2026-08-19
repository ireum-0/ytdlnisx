package com.ireum.ytdl.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MetadataEnrichmentResolverTest {
    @Test
    fun completeFreshResultSkipsUsableCache() = runBlocking {
        var cacheLookups = 0

        val result = MetadataEnrichmentResolver.resolveFreshFirst(
            loadFresh = { Metadata(title = "fresh", author = "author", date = 100L) },
            loadCached = {
                cacheLookups += 1
                Metadata(title = "cached", author = "cached", date = 50L)
            },
            isUsable = Metadata::isUsable,
            needsFallback = Metadata::needsFallback,
            merge = ::merge,
        )

        assertEquals(Metadata(title = "fresh", author = "author", date = 100L), result)
        assertEquals(0, cacheLookups)
    }

    @Test
    fun freshFailureFallsBackToUsableCache() = runBlocking {
        val result = MetadataEnrichmentResolver.resolveFreshFirst(
            loadFresh = { throw IllegalStateException("source failed") },
            loadCached = { Metadata(title = "cached", author = "author", date = 50L) },
            isUsable = Metadata::isUsable,
            needsFallback = Metadata::needsFallback,
            merge = ::merge,
        )

        assertEquals(Metadata(title = "cached", author = "author", date = 50L), result)
    }

    @Test
    fun missingCacheKeepsSuccessfulFreshResult() = runBlocking {
        val fresh = Metadata(title = "fresh", author = "", date = 0L)

        val result = MetadataEnrichmentResolver.resolveFreshFirst(
            loadFresh = { fresh },
            loadCached = { null },
            isUsable = Metadata::isUsable,
            needsFallback = Metadata::needsFallback,
            merge = ::merge,
        )

        assertSame(fresh, result)
    }

    @Test
    fun missingCacheRethrowsFreshFailureWithIdentity() = runBlocking {
        val expected = IllegalStateException("source failed")

        try {
            MetadataEnrichmentResolver.resolveFreshFirst(
                loadFresh = { throw expected },
                loadCached = { null },
                isUsable = Metadata::isUsable,
                needsFallback = Metadata::needsFallback,
                merge = ::merge,
            )
            fail("Expected source failure")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun malformedCacheIsNotConvertedToAMiss() = runBlocking {
        val expected = IllegalArgumentException("malformed cache")

        try {
            MetadataEnrichmentResolver.resolveFreshFirst(
                loadFresh = { Metadata(title = "fresh", author = "", date = 0L) },
                loadCached = { throw expected },
                isUsable = Metadata::isUsable,
                needsFallback = Metadata::needsFallback,
                merge = ::merge,
            )
            fail("Expected cache failure")
        } catch (actual: IllegalArgumentException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun freshCancellationPropagatesWithoutConsultingCache() = runBlocking {
        val expected = CancellationException("cancelled")
        var cacheLookups = 0

        try {
            MetadataEnrichmentResolver.resolveFreshFirst(
                loadFresh = { throw expected },
                loadCached = {
                    cacheLookups += 1
                    Metadata(title = "cached", author = "author", date = 50L)
                },
                isUsable = Metadata::isUsable,
                needsFallback = Metadata::needsFallback,
                merge = ::merge,
            )
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        assertEquals(0, cacheLookups)
    }

    @Test
    fun incompleteFreshMetadataUsesCacheDateWithoutReplacingFreshTitle() = runBlocking {
        val result = MetadataEnrichmentResolver.resolveFreshFirst(
            loadFresh = { Metadata(title = "fresh title", author = "author", date = 0L) },
            loadCached = { Metadata(title = "cached title", author = "author", date = -315_532_800L) },
            isUsable = Metadata::isUsable,
            needsFallback = Metadata::needsFallback,
            merge = ::merge,
        )

        assertEquals(
            Metadata(title = "fresh title", author = "author", date = -315_532_800L),
            result,
        )
    }

    @Test
    fun completeCacheHitSkipsFreshLookup() = runBlocking {
        val fields = mutableSetOf<String>()
        var freshLookups = 0

        val changed = MetadataEnrichmentResolver.enrichCacheFirst(
            loadCached = { setOf("title", "author", "date") },
            loadFresh = {
                freshLookups += 1
                setOf("fresh")
            },
            applyMetadata = { fields.addAll(it) },
            isComplete = { fields.containsAll(setOf("title", "author", "date")) },
        )

        assertTrue(changed)
        assertEquals(0, freshLookups)
    }

    @Test
    fun cacheMissUsesOneFreshLookup() = runBlocking {
        val fields = mutableSetOf<String>()
        var freshLookups = 0

        val changed = MetadataEnrichmentResolver.enrichCacheFirst(
            loadCached = { null },
            loadFresh = {
                freshLookups += 1
                setOf("title", "author")
            },
            applyMetadata = { fields.addAll(it) },
            isComplete = { "title" in fields && "author" in fields },
        )

        assertTrue(changed)
        assertEquals(1, freshLookups)
        assertEquals(setOf("title", "author"), fields)
    }

    @Test
    fun insufficientCacheIsMergedWithFreshMetadata() = runBlocking {
        val fields = mutableSetOf<String>()

        MetadataEnrichmentResolver.enrichCacheFirst(
            loadCached = { setOf("title") },
            loadFresh = { setOf("author", "date") },
            applyMetadata = { fields.addAll(it) },
            isComplete = { fields.containsAll(setOf("title", "author", "date")) },
        )

        assertEquals(setOf("title", "author", "date"), fields)
    }

    @Test
    fun missingCacheAndSourceRemainUnchanged() = runBlocking {
        val changed = MetadataEnrichmentResolver.enrichCacheFirst<String>(
            loadCached = { null },
            loadFresh = { null },
            applyMetadata = { true },
            isComplete = { false },
        )

        assertFalse(changed)
    }

    @Test
    fun freshLookupFailureIsNotConvertedToCacheMiss() = runBlocking {
        val expected = IllegalStateException("source failed")

        try {
            MetadataEnrichmentResolver.enrichCacheFirst<String>(
                loadCached = { null },
                loadFresh = { throw expected },
                applyMetadata = { true },
                isComplete = { false },
            )
            fail("Expected source failure")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun cancellationPropagatesWithIdentity() = runBlocking {
        val expected = CancellationException("cancelled")

        try {
            MetadataEnrichmentResolver.enrichCacheFirst<String>(
                loadCached = { null },
                loadFresh = { throw expected },
                applyMetadata = { true },
                isComplete = { false },
            )
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun cacheFirstKeepsAppliedCacheWhenOptionalFreshLookupFails() = runBlocking {
        val fields = mutableSetOf<String>()

        val changed = MetadataEnrichmentResolver.enrichCacheFirst(
            loadCached = { setOf("title") },
            loadFresh = { throw IllegalStateException("source failed") },
            applyMetadata = { fields.addAll(it) },
            isComplete = { fields.containsAll(setOf("title", "author")) },
        )

        assertTrue(changed)
        assertEquals(setOf("title"), fields)
    }

    private data class Metadata(
        val title: String,
        val author: String,
        val date: Long,
    ) {
        fun isUsable(): Boolean = title.isNotBlank() || author.isNotBlank() || date != 0L
        fun needsFallback(): Boolean = title.isBlank() || author.isBlank() || date == 0L
    }

    private fun merge(primary: Metadata, fallback: Metadata): Metadata {
        return Metadata(
            title = primary.title.ifBlank { fallback.title },
            author = primary.author.ifBlank { fallback.author },
            date = primary.date.takeIf { it != 0L } ?: fallback.date,
        )
    }
}
