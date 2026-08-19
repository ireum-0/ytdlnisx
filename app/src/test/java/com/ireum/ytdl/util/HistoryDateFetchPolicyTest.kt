package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.HistoryDateFetchOperation
import com.ireum.ytdl.database.models.HistoryDateFetchOperationState
import com.ireum.ytdl.database.models.KnownMediaPublishedDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDateFetchPolicyTest {
    @Test
    fun groupingCoalescesCanonicalAndSchemeLessSources() {
        val groups = HistoryDateSourceGrouping.group(
            listOf(
                HistoryDateSourceCandidate(1, "youtube.com/watch?v=dQw4w9WgXcQ"),
                HistoryDateSourceCandidate(2, "https://youtu.be/dQw4w9WgXcQ"),
                HistoryDateSourceCandidate(3, "https://example.com/other"),
            )
        )

        assertEquals(2, groups.size)
        assertEquals(listOf(1L, 2L), groups.first().candidates.map { it.historyId })
    }

    @Test
    fun localDateRequiresOneNonConflictingNonzeroValueAndKeepsNegativeDates() {
        val source = "https://example.com/video"
        assertEquals(
            -315_532_800L,
            HistoryDateValuePolicy.localDate(
                source,
                listOf(
                    KnownMediaPublishedDate("example.com/video", -315_532_800L),
                    KnownMediaPublishedDate(source, -315_532_800L),
                    KnownMediaPublishedDate(source, 0L),
                ),
            ),
        )
        assertNull(
            HistoryDateValuePolicy.localDate(
                source,
                listOf(
                    KnownMediaPublishedDate(source, 100L),
                    KnownMediaPublishedDate(source, 200L),
                ),
            ),
        )
    }

    @Test
    fun indexedKnownDatesMatchLegacyPolicyAcrossEverySourceIdentity() {
        val exact = KnownMediaPublishedDate(" opaque-source ", 10L)
        val schemeNormalized = KnownMediaPublishedDate("example.com/video?item=1", 20L)
        val stableIdentity = KnownMediaPublishedDate(
            "https://tracked.example/video?item=2&utm_source=history",
            30L,
        )
        val youtube = KnownMediaPublishedDate(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            40L,
        )
        val projections = listOf(exact, schemeNormalized, stableIdentity, youtube)
        val index = KnownMediaPublishedDateIndex(projections)
        val cases = listOf(
            "opaque-source" to listOf(10L),
            "https://example.com/video?item=1" to listOf(20L),
            "https://tracked.example/video?item=2" to listOf(30L),
            "https://youtu.be/dQw4w9WgXcQ?t=5" to listOf(40L),
        )

        cases.forEach { (source, expectedValues) ->
            val indexedValues = index.valuesFor(source)
            assertEquals(expectedValues, indexedValues)
            assertEquals(
                HistoryDateValuePolicy.localDate(source, projections),
                HistoryDateValuePolicy.uniqueNonConflicting(indexedValues),
            )
        }
    }

    @Test
    fun indexedConflictsRemainUnresolvedAndDuplicateDatesRemainAuthoritative() {
        val source = "https://example.com/video?item=1"
        val conflicting = listOf(
            KnownMediaPublishedDate("$source&utm_source=history", 100L),
            KnownMediaPublishedDate("$source&fbclid=tracking", 200L),
        )
        val duplicate = conflicting.map { it.copy(mediaPublishedAt = 100L) }

        assertNull(
            HistoryDateValuePolicy.uniqueNonConflicting(
                KnownMediaPublishedDateIndex(conflicting).valuesFor(source),
            ),
        )
        assertEquals(
            100L,
            HistoryDateValuePolicy.uniqueNonConflicting(
                KnownMediaPublishedDateIndex(duplicate).valuesFor(source),
            ),
        )
    }

    @Test
    fun indexDoesNotMatchValuesAcrossKeyNamespaces() {
        val index = KnownMediaPublishedDateIndex(
            listOf(KnownMediaPublishedDate("web://example.com/", 10L)),
        )

        assertEquals(emptyList<Long>(), index.valuesFor("https://example.com"))
    }

    @Test
    fun indexNormalizesEachKnownRecordOnceDuringConstruction() {
        val resolutions = mutableMapOf<String, Int>()
        val resolver: (String) -> List<MediaPublishedDateSourceKey> = { value ->
            resolutions[value] = resolutions.getOrDefault(value, 0) + 1
            listOf(
                MediaPublishedDateSourceKey(
                    MediaPublishedDateSourceKeyKind.EXACT,
                    value.substringBefore('#'),
                ),
            )
        }
        val firstRecord = "source-a#stored"
        val secondRecord = "source-b#stored"
        val index = KnownMediaPublishedDateIndex(
            listOf(
                KnownMediaPublishedDate(firstRecord, 10L),
                KnownMediaPublishedDate(secondRecord, 20L),
            ),
            resolver,
        )

        assertEquals(listOf(10L), index.valuesFor("source-a#group"))
        assertEquals(listOf(20L), index.valuesFor("source-b#group"))
        assertEquals(1, resolutions[firstRecord])
        assertEquals(1, resolutions[secondRecord])
    }

    @Test
    fun resolutionUsesLocalThenCacheBeforeLaunchingExtractor() = runBlocking {
        var minimalCalls = 0
        val local = HistoryDateResolutionEngine.resolve(
            localValues = listOf(123L),
            cachedValues = { listOf(456L) },
            minimalLookup = { minimalCalls += 1; 789L },
            compatibilityLookup = { 999L },
        )
        assertEquals(HistoryDateLookupOrigin.LOCAL, local.origin)
        assertEquals(0, minimalCalls)

        val cached = HistoryDateResolutionEngine.resolve(
            localValues = emptyList(),
            cachedValues = { listOf(456L, 456L) },
            minimalLookup = { minimalCalls += 1; 789L },
            compatibilityLookup = { 999L },
        )
        assertEquals(HistoryDateLookupOrigin.CACHE, cached.origin)
        assertEquals(0, minimalCalls)
    }

    @Test
    fun conflictingCacheFallsThroughMinimalThenCompatibility() = runBlocking {
        var compatibilityCalls = 0
        val minimal = HistoryDateResolutionEngine.resolve(
            localValues = emptyList(),
            cachedValues = { listOf(100L, 200L) },
            minimalLookup = { 300L },
            compatibilityLookup = { compatibilityCalls += 1; 400L },
        )
        assertEquals(HistoryDateLookupOrigin.MINIMAL, minimal.origin)
        assertEquals(1, minimal.extractorLaunches)
        assertEquals(0, compatibilityCalls)

        val compatibility = HistoryDateResolutionEngine.resolve(
            localValues = emptyList(),
            cachedValues = { emptyList() },
            minimalLookup = { null },
            compatibilityLookup = { compatibilityCalls += 1; 400L },
        )
        assertEquals(HistoryDateLookupOrigin.COMPATIBILITY, compatibility.origin)
        assertEquals(2, compatibility.extractorLaunches)
        assertEquals(1, compatibility.compatibilityFallbacks)
        assertEquals(1, compatibilityCalls)
    }

    @Test
    fun cancellationPropagatesWithoutCompatibilityFallback() = runBlocking {
        var compatibilityCalled = false
        var propagated = false
        try {
            HistoryDateResolutionEngine.resolve(
                localValues = emptyList(),
                cachedValues = { emptyList() },
                minimalLookup = { throw CancellationException("stop") },
                compatibilityLookup = { compatibilityCalled = true; 1L },
            )
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertFalse(compatibilityCalled)
    }

    @Test
    fun persistedCancellationIsCheckedBeforeCompatibilityFallback() = runBlocking {
        var checks = 0
        var compatibilityCalled = false
        var propagated = false
        try {
            HistoryDateResolutionEngine.resolve(
                localValues = emptyList(),
                cachedValues = { emptyList() },
                minimalLookup = { throw IllegalStateException("extractor stopped") },
                compatibilityLookup = { compatibilityCalled = true; 1L },
                ensureRunning = {
                    checks += 1
                    if (checks >= 3) throw CancellationException("persisted cancellation")
                },
            )
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertFalse(compatibilityCalled)
    }

    @Test
    fun batchMappingUsesProvenanceAndFallsBackForMissingAmbiguousOrMultipleResults() {
        val first = HistoryDateSourceGroup(
            "first",
            listOf(HistoryDateSourceCandidate(1, "https://example.com/a")),
        )
        val second = HistoryDateSourceGroup(
            "second",
            listOf(HistoryDateSourceCandidate(2, "https://example.net/b")),
        )
        val mapped = HistoryDateBatchResultMapper.map(
            listOf(first, second),
            listOf(
                HistoryDateBatchResult(
                    10L,
                    ExtractorSourceIdentity(canonicalUrl = "https://example.com/a"),
                ),
            ),
        )
        assertEquals(mapOf("first" to 10L), mapped.acceptedDatesByGroupKey)
        assertEquals(setOf("second"), mapped.fallbackGroupKeys)

        val ambiguous = HistoryDateBatchResultMapper.map(
            listOf(first, second),
            listOf(
                HistoryDateBatchResult(
                    10L,
                    ExtractorSourceIdentity(
                        canonicalUrl = "https://example.com/a",
                        originalUrl = "https://example.net/b",
                    ),
                ),
            ),
        )
        assertTrue(ambiguous.acceptedDatesByGroupKey.isEmpty())
        assertEquals(setOf("first", "second"), ambiguous.fallbackGroupKeys)

        val multiple = HistoryDateBatchResultMapper.map(
            listOf(first),
            listOf(
                HistoryDateBatchResult(10L, ExtractorSourceIdentity(canonicalUrl = first.representativeSource)),
                HistoryDateBatchResult(10L, ExtractorSourceIdentity(originalUrl = first.representativeSource)),
            ),
        )
        assertTrue(multiple.acceptedDatesByGroupKey.isEmpty())
        assertEquals(setOf("first"), multiple.fallbackGroupKeys)
    }

    @Test
    fun batchSizeSelectsSmallestWithinTenPercentOfBest() {
        assertEquals(2, HistoryDateBatchSizePolicy.select(mapOf(1 to 5.0, 2 to 9.1, 4 to 10.0, 8 to 9.8)))
        assertEquals(1, HistoryDateBatchSizePolicy.select(emptyMap()))
    }

    @Test
    fun startupRestoresOnlyNonterminalAndTerminalEmissionRequiresTransition() {
        assertTrue(
            HistoryDateFetchNotificationPolicy.restoreAtStartup(
                HistoryDateFetchOperation("active")
            )
        )
        assertFalse(
            HistoryDateFetchNotificationPolicy.restoreAtStartup(
                HistoryDateFetchOperation(
                    "done",
                    state = HistoryDateFetchOperationState.COMPLETED.name,
                )
            )
        )
        assertTrue(HistoryDateFetchNotificationPolicy.emitTerminal(true))
        assertFalse(HistoryDateFetchNotificationPolicy.emitTerminal(false))
    }
}
