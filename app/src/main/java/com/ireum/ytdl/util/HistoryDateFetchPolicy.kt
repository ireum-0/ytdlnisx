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

/**
 * Typed authority carried by one History media-date lookup.  A nullable date
 * cannot distinguish a proven source absence from an extractor failure or an
 * ambiguous/unproven response, so callers must branch on this outcome before
 * persisting any NO_DATE evidence.
 */
sealed interface HistoryDateLookupOutcome {
    data class Found(val mediaPublishedAt: Long) : HistoryDateLookupOutcome

    data object AuthoritativeAbsence : HistoryDateLookupOutcome

    data class Ambiguous(val reason: String = "") : HistoryDateLookupOutcome

    data class RetryableFailure(val reason: String = "") : HistoryDateLookupOutcome

    data class FinalFailure(val reason: String = "") : HistoryDateLookupOutcome
}

/** One parsed candidate presented to the date-only authority classifier. */
data class HistoryDateLookupCandidate(
    val mediaPublishedAt: Long,
    val identity: ExtractorSourceIdentity?,
)

/**
 * Classifies date-only extractor output without trusting an empty/malformed
 * result as authoritative absence.  Exactly one clean, source-matching
 * candidate is required before either Found or AuthoritativeAbsence is
 * returned.
 */
object HistoryDateLookupOutcomePolicy {
    fun classify(
        requestedSource: String,
        candidates: List<HistoryDateLookupCandidate>,
        malformedOutput: Boolean = false,
    ): HistoryDateLookupOutcome {
        if (malformedOutput) {
            return HistoryDateLookupOutcome.FinalFailure("MALFORMED_OUTPUT")
        }
        if (candidates.size != 1) {
            return HistoryDateLookupOutcome.Ambiguous(
                if (candidates.isEmpty()) "NO_MATCHING_RESULT" else "MULTIPLE_RESULTS",
            )
        }
        val candidate = candidates.single()
        val identity = candidate.identity
            ?: return HistoryDateLookupOutcome.Ambiguous("MISSING_SOURCE_IDENTITY")
        if (!ExtractorSourceIdentityPolicy.matchesRequestedSource(requestedSource, identity)) {
            return HistoryDateLookupOutcome.Ambiguous("SOURCE_MISMATCH")
        }
        return if (MediaPublishedDate.isPresent(candidate.mediaPublishedAt)) {
            HistoryDateLookupOutcome.Found(candidate.mediaPublishedAt)
        } else {
            HistoryDateLookupOutcome.AuthoritativeAbsence
        }
    }
}

data class HistoryDateLookupResult(
    val mediaPublishedAt: Long = MediaPublishedDate.MISSING,
    val origin: HistoryDateLookupOrigin,
    val extractorLaunches: Int = 0,
    val compatibilityFallbacks: Int = 0,
    val failureReason: String = "",
    val outcome: HistoryDateLookupOutcome = legacyLookupOutcome(
        mediaPublishedAt,
        origin,
        failureReason,
    ),
)

private fun legacyLookupOutcome(
    mediaPublishedAt: Long,
    origin: HistoryDateLookupOrigin,
    failureReason: String,
): HistoryDateLookupOutcome = when {
    MediaPublishedDate.isPresent(mediaPublishedAt) ->
        HistoryDateLookupOutcome.Found(mediaPublishedAt)
    origin == HistoryDateLookupOrigin.FAILED ->
        HistoryDateLookupOutcome.RetryableFailure(failureReason.ifBlank { origin.name })
    else -> HistoryDateLookupOutcome.Ambiguous("LEGACY_UNPROVEN_RESULT")
}

object HistoryDateResolutionEngine {
    suspend fun resolve(
        localValues: Iterable<Long>,
        cachedValues: () -> Iterable<Long>,
        minimalLookup: suspend () -> Long?,
        compatibilityLookup: suspend () -> Long?,
        ensureRunning: suspend () -> Unit = {},
    ): HistoryDateLookupResult = resolveTyped(
        localValues = localValues,
        cachedValues = cachedValues,
        minimalLookup = { minimalLookup().toLookupOutcome() },
        compatibilityLookup = { compatibilityLookup().toLookupOutcome() },
        ensureRunning = ensureRunning,
    )

    /** Production typed contract used by the real extractor/worker chain. */
    suspend fun resolveTyped(
        localValues: Iterable<Long>,
        cachedValues: () -> Iterable<Long>,
        minimalLookup: suspend () -> HistoryDateLookupOutcome,
        compatibilityLookup: suspend () -> HistoryDateLookupOutcome,
        ensureRunning: suspend () -> Unit = {},
    ): HistoryDateLookupResult {
        currentCoroutineContext().ensureActive()
        ensureRunning()
        HistoryDateValuePolicy.uniqueNonConflicting(localValues)?.let {
            return resultFor(
                outcome = HistoryDateLookupOutcome.Found(it),
                origin = HistoryDateLookupOrigin.LOCAL,
            )
        }

        val cached = try {
            HistoryDateValuePolicy.uniqueNonConflicting(cachedValues())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (cached != null) {
            return resultFor(
                outcome = HistoryDateLookupOutcome.Found(cached),
                origin = HistoryDateLookupOrigin.CACHE,
            )
        }

        currentCoroutineContext().ensureActive()
        ensureRunning()
        val minimal = try {
            minimalLookup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HistoryDateLookupOutcome.RetryableFailure(error.javaClass.simpleName)
        }
        when (minimal) {
            is HistoryDateLookupOutcome.Found -> {
                if (MediaPublishedDate.isPresent(minimal.mediaPublishedAt)) {
                    return resultFor(
                        outcome = minimal,
                        origin = HistoryDateLookupOrigin.MINIMAL,
                        extractorLaunches = 1,
                    )
                }
            }
            HistoryDateLookupOutcome.AuthoritativeAbsence -> {
                return resultFor(
                    outcome = minimal,
                    origin = HistoryDateLookupOrigin.MINIMAL,
                    extractorLaunches = 1,
                )
            }
            is HistoryDateLookupOutcome.Ambiguous,
            is HistoryDateLookupOutcome.RetryableFailure,
            is HistoryDateLookupOutcome.FinalFailure -> Unit
        }

        currentCoroutineContext().ensureActive()
        ensureRunning()
        val compatibility = try {
            compatibilityLookup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HistoryDateLookupOutcome.RetryableFailure(error.javaClass.simpleName)
        }
        when (compatibility) {
            is HistoryDateLookupOutcome.Found -> {
                if (MediaPublishedDate.isPresent(compatibility.mediaPublishedAt)) {
                    return resultFor(
                        outcome = compatibility,
                        origin = HistoryDateLookupOrigin.COMPATIBILITY,
                        extractorLaunches = 2,
                        compatibilityFallbacks = 1,
                    )
                }
            }
            HistoryDateLookupOutcome.AuthoritativeAbsence -> {
                return resultFor(
                    outcome = compatibility,
                    origin = HistoryDateLookupOrigin.COMPATIBILITY,
                    extractorLaunches = 2,
                    compatibilityFallbacks = 1,
                )
            }
            is HistoryDateLookupOutcome.Ambiguous,
            is HistoryDateLookupOutcome.RetryableFailure,
            is HistoryDateLookupOutcome.FinalFailure -> Unit
        }
        val unresolved = unresolvedOutcome(minimal, compatibility)
        return resultFor(
            outcome = unresolved,
            origin = if (unresolved is HistoryDateLookupOutcome.Ambiguous) {
                HistoryDateLookupOrigin.NONE
            } else {
                HistoryDateLookupOrigin.FAILED
            },
            extractorLaunches = 2,
            compatibilityFallbacks = 1,
            failureReason = unresolved.reasonOrEmpty(),
        )
    }

    private fun resultFor(
        outcome: HistoryDateLookupOutcome,
        origin: HistoryDateLookupOrigin,
        extractorLaunches: Int = 0,
        compatibilityFallbacks: Int = 0,
        failureReason: String = outcome.reasonOrEmpty(),
    ): HistoryDateLookupResult = HistoryDateLookupResult(
        mediaPublishedAt = (outcome as? HistoryDateLookupOutcome.Found)?.mediaPublishedAt
            ?: MediaPublishedDate.MISSING,
        origin = origin,
        extractorLaunches = extractorLaunches,
        compatibilityFallbacks = compatibilityFallbacks,
        failureReason = failureReason,
        outcome = outcome,
    )

    private fun unresolvedOutcome(
        minimal: HistoryDateLookupOutcome,
        compatibility: HistoryDateLookupOutcome,
    ): HistoryDateLookupOutcome {
        val outcomes = listOf(minimal, compatibility)
        outcomes.filterIsInstance<HistoryDateLookupOutcome.RetryableFailure>()
            .firstOrNull()?.let { return it }
        outcomes.filterIsInstance<HistoryDateLookupOutcome.FinalFailure>()
            .firstOrNull()?.let { return it }
        val reasons = outcomes.mapNotNull { it.reasonOrEmpty().takeIf(String::isNotBlank) }
        return HistoryDateLookupOutcome.Ambiguous(reasons.joinToString(";").ifBlank { "UNPROVEN" })
    }
}

private fun Long?.toLookupOutcome(): HistoryDateLookupOutcome =
    if (this != null && MediaPublishedDate.isPresent(this)) {
        HistoryDateLookupOutcome.Found(this)
    } else {
        HistoryDateLookupOutcome.Ambiguous("NO_DATE_RESULT")
    }

private fun HistoryDateLookupOutcome.reasonOrEmpty(): String = when (this) {
    is HistoryDateLookupOutcome.Ambiguous -> reason
    is HistoryDateLookupOutcome.RetryableFailure -> reason
    is HistoryDateLookupOutcome.FinalFailure -> reason
    is HistoryDateLookupOutcome.Found,
    HistoryDateLookupOutcome.AuthoritativeAbsence -> ""
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
