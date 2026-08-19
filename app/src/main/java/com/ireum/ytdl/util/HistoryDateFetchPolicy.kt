package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.HistoryDateFetchCounts
import com.ireum.ytdl.database.models.HistoryDateFetchOperation
import com.ireum.ytdl.database.models.HistoryDateFetchOperationState
import com.ireum.ytdl.database.models.KnownMediaPublishedDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

data class HistoryDateSourceCandidate(
    val historyId: Long,
    val sourceUrl: String,
)

data class HistoryDateSourceGroup(
    val key: String,
    val candidates: List<HistoryDateSourceCandidate>,
) {
    val representativeSource: String
        get() = candidates.first().sourceUrl
}

object HistoryDateSourceGrouping {
    fun group(candidates: Iterable<HistoryDateSourceCandidate>): List<HistoryDateSourceGroup> {
        val groups = mutableListOf<MutableList<HistoryDateSourceCandidate>>()
        candidates.sortedBy(HistoryDateSourceCandidate::historyId).forEach { candidate ->
            val matching = groups.withIndex().filter { (_, group) ->
                group.all { existing ->
                    MediaPublishedDateSource.matches(existing.sourceUrl, candidate.sourceUrl)
                }
            }
            if (matching.size == 1) {
                matching.single().value += candidate
            } else {
                groups += mutableListOf(candidate)
            }
        }
        return groups.map { group ->
            HistoryDateSourceGroup(
                key = "source-${group.minOf(HistoryDateSourceCandidate::historyId)}",
                candidates = group.toList(),
            )
        }
    }
}

object HistoryDateValuePolicy {
    fun uniqueNonConflicting(values: Iterable<Long>): Long? {
        val distinct = values.filter(MediaPublishedDate::isPresent).distinct().take(2)
        return distinct.singleOrNull()
    }

    fun localDate(
        requestedSource: String,
        projections: Iterable<KnownMediaPublishedDate>,
    ): Long? = uniqueNonConflicting(
        projections.asSequence()
            .filter { MediaPublishedDateSource.matches(requestedSource, it.url) }
            .map(KnownMediaPublishedDate::mediaPublishedAt)
            .asIterable()
    )
}

internal class KnownMediaPublishedDateIndex(
    projections: Iterable<KnownMediaPublishedDate>,
    private val keyResolver: (String) -> List<MediaPublishedDateSourceKey> =
        MediaPublishedDateSource::matchingKeys,
) {
    private data class IndexedProjection(
        val order: Int,
        val projection: KnownMediaPublishedDate,
    )

    private val projectionsByKey = mutableMapOf<MediaPublishedDateSourceKey, MutableList<IndexedProjection>>()

    init {
        projections.forEachIndexed { order, projection ->
            val indexed = IndexedProjection(order, projection)
            keyResolver(projection.url).distinct().forEach { key ->
                projectionsByKey.getOrPut(key) { mutableListOf() } += indexed
            }
        }
    }

    fun valuesFor(requestedSource: String): List<Long> {
        val matchesByOrder = linkedMapOf<Int, IndexedProjection>()
        keyResolver(requestedSource).distinct().forEach { key ->
            projectionsByKey[key].orEmpty().forEach { indexed ->
                if (indexed.order !in matchesByOrder) {
                    matchesByOrder[indexed.order] = indexed
                }
            }
        }
        return matchesByOrder.values
            .sortedBy(IndexedProjection::order)
            .map { it.projection.mediaPublishedAt }
    }
}

enum class HistoryDateLookupOrigin {
    LOCAL,
    CACHE,
    MINIMAL,
    COMPATIBILITY,
    NONE,
    FAILED,
}

data class HistoryDateLookupResult(
    val mediaPublishedAt: Long = MediaPublishedDate.MISSING,
    val origin: HistoryDateLookupOrigin,
    val extractorLaunches: Int = 0,
    val compatibilityFallbacks: Int = 0,
    val failureReason: String = "",
)

object HistoryDateResolutionEngine {
    suspend fun resolve(
        localValues: Iterable<Long>,
        cachedValues: () -> Iterable<Long>,
        minimalLookup: suspend () -> Long?,
        compatibilityLookup: suspend () -> Long?,
        ensureRunning: suspend () -> Unit = {},
    ): HistoryDateLookupResult {
        currentCoroutineContext().ensureActive()
        ensureRunning()
        HistoryDateValuePolicy.uniqueNonConflicting(localValues)?.let {
            return HistoryDateLookupResult(it, HistoryDateLookupOrigin.LOCAL)
        }

        val cached = try {
            HistoryDateValuePolicy.uniqueNonConflicting(cachedValues())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (cached != null) {
            return HistoryDateLookupResult(cached, HistoryDateLookupOrigin.CACHE)
        }

        currentCoroutineContext().ensureActive()
        ensureRunning()
        val minimal = try {
            minimalLookup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }?.takeIf(MediaPublishedDate::isPresent)
        if (minimal != null) {
            return HistoryDateLookupResult(
                mediaPublishedAt = minimal,
                origin = HistoryDateLookupOrigin.MINIMAL,
                extractorLaunches = 1,
            )
        }

        currentCoroutineContext().ensureActive()
        ensureRunning()
        return try {
            val compatibility = compatibilityLookup()?.takeIf(MediaPublishedDate::isPresent)
            if (compatibility == null) {
                HistoryDateLookupResult(
                    origin = HistoryDateLookupOrigin.NONE,
                    extractorLaunches = 2,
                    compatibilityFallbacks = 1,
                )
            } else {
                HistoryDateLookupResult(
                    mediaPublishedAt = compatibility,
                    origin = HistoryDateLookupOrigin.COMPATIBILITY,
                    extractorLaunches = 2,
                    compatibilityFallbacks = 1,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HistoryDateLookupResult(
                origin = HistoryDateLookupOrigin.FAILED,
                extractorLaunches = 2,
                compatibilityFallbacks = 1,
                failureReason = error.javaClass.simpleName,
            )
        }
    }
}

data class HistoryDateBatchResult(
    val mediaPublishedAt: Long,
    val identity: ExtractorSourceIdentity,
)

data class HistoryDateBatchMapping(
    val acceptedDatesByGroupKey: Map<String, Long>,
    val fallbackGroupKeys: Set<String>,
)

object HistoryDateBatchResultMapper {
    fun map(
        groups: List<HistoryDateSourceGroup>,
        results: List<HistoryDateBatchResult>,
    ): HistoryDateBatchMapping {
        val uniquelyMapped = groups.associate { it.key to mutableListOf<Long>() }
        val ambiguousGroups = mutableSetOf<String>()

        results.forEach { result ->
            if (!MediaPublishedDate.isPresent(result.mediaPublishedAt)) return@forEach
            val matches = groups.filter { group ->
                group.candidates.all { candidate ->
                    ExtractorSourceIdentityPolicy.matchesRequestedSource(
                        candidate.sourceUrl,
                        result.identity,
                    )
                }
            }
            if (matches.size == 1) {
                uniquelyMapped.getValue(matches.single().key) += result.mediaPublishedAt
            } else {
                ambiguousGroups += matches.map(HistoryDateSourceGroup::key)
            }
        }

        val accepted = mutableMapOf<String, Long>()
        val fallback = mutableSetOf<String>()
        groups.forEach { group ->
            val values = uniquelyMapped.getValue(group.key)
            val date = HistoryDateValuePolicy.uniqueNonConflicting(values)
            if (
                date != null &&
                values.size == 1 &&
                group.key !in ambiguousGroups
            ) {
                accepted[group.key] = date
            } else {
                fallback += group.key
            }
        }
        return HistoryDateBatchMapping(accepted, fallback)
    }
}

object HistoryDateBatchSizePolicy {
    /** Chooses the smallest measured size within 10% of the best throughput. */
    fun select(throughputBySize: Map<Int, Double>): Int {
        val supported = throughputBySize.filterKeys { it in setOf(1, 2, 4, 8) }
        if (supported.isEmpty()) return 1
        val best = supported.values.maxOrNull() ?: return 1
        val threshold = best * 0.9
        return supported.filterValues { it + abs(it) * 1e-12 >= threshold }
            .keys.minOrNull() ?: 1
    }
}

data class HistoryDateFetchProgress(
    val operation: HistoryDateFetchOperation,
    val counts: HistoryDateFetchCounts,
) {
    val isTerminal: Boolean
        get() = operation.stateValue.isTerminal
}

object HistoryDateFetchNotificationPolicy {
    fun restoreAtStartup(operation: HistoryDateFetchOperation): Boolean =
        operation.stateValue == HistoryDateFetchOperationState.RUNNING

    fun emitTerminal(transitionedToTerminal: Boolean): Boolean = transitionedToTerminal
}
