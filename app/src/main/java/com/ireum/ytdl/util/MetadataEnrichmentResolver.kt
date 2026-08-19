package com.ireum.ytdl.util

import kotlinx.coroutines.CancellationException

/**
 * Applies cached metadata first and consults the source at most once when the
 * cached values do not satisfy the caller's requirements.
 *
 * Cache lookup and parsing failures intentionally propagate. An ordinary
 * optional fresh lookup failure is tolerated only when cached metadata was
 * actually applied; cancellation always propagates.
 */
object MetadataEnrichmentResolver {
    suspend fun <T> enrichCacheFirst(
        loadCached: suspend () -> T?,
        loadFresh: suspend () -> T?,
        applyMetadata: (T) -> Boolean,
        isComplete: () -> Boolean,
    ): Boolean {
        var changed = false
        var cachedMetadataApplied = false
        loadCached()?.let { cached ->
            cachedMetadataApplied = applyMetadata(cached)
            changed = cachedMetadataApplied || changed
        }
        if (!isComplete()) {
            try {
                loadFresh()?.let { fresh ->
                    changed = applyMetadata(fresh) || changed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!cachedMetadataApplied) throw error
            }
        }
        return changed
    }

    /**
     * Resolves fresh metadata first. Cache is consulted only when the fresh
     * result is incomplete or an ordinary fresh lookup fails.
     *
     * Cache failures always propagate. A fresh failure is rethrown unchanged
     * when no usable cached result exists, and cancellation never reaches the
     * cache lookup.
     */
    suspend fun <T> resolveFreshFirst(
        loadFresh: suspend () -> T?,
        loadCached: suspend () -> T?,
        isUsable: (T) -> Boolean,
        needsFallback: (T) -> Boolean,
        merge: (primary: T, fallback: T) -> T,
    ): T? {
        var freshFailure: Exception? = null
        val fresh = try {
            loadFresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            freshFailure = error
            null
        }

        if (fresh != null && !needsFallback(fresh)) return fresh

        val cached = loadCached()
        if (fresh != null) {
            return cached?.let { merge(fresh, it) } ?: fresh
        }
        if (cached != null && isUsable(cached)) return cached

        freshFailure?.let { throw it }
        return null
    }
}
