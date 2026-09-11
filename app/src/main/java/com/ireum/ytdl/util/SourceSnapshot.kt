package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.ResultItem

/**
 * The result of extracting a source list, including whether the extractor
 * proved that the returned membership is complete.
 *
 * A list of items alone is not sufficient for source synchronization: an
 * extractor may have returned only the items it could parse.  Consumers that
 * make absence/destructive decisions must require [Authority.AUTHORITATIVE].
 */
data class SourceSnapshot(
    val items: List<ResultItem>,
    val authority: Authority,
    val cause: Throwable? = null,
    val diagnostic: String = "",
) {
    enum class Authority {
        AUTHORITATIVE,
        PARTIAL,
        FAILED,
    }

    init {
        require(authority != Authority.AUTHORITATIVE || cause == null) {
            "An authoritative source snapshot cannot carry a failure cause"
        }
    }

    val permitsDestructiveAbsenceReconciliation: Boolean
        get() = authority == Authority.AUTHORITATIVE

    companion object {
        fun authoritative(items: List<ResultItem>): SourceSnapshot = SourceSnapshot(
            items = items.toList(),
            authority = Authority.AUTHORITATIVE,
        )

        fun partial(
            items: List<ResultItem>,
            diagnostic: String = "",
            cause: Throwable? = null,
        ): SourceSnapshot = SourceSnapshot(
            items = items.toList(),
            authority = Authority.PARTIAL,
            cause = cause,
            diagnostic = diagnostic,
        )

        fun failed(
            cause: Throwable? = null,
            diagnostic: String = "",
        ): SourceSnapshot = SourceSnapshot(
            items = emptyList(),
            authority = Authority.FAILED,
            cause = cause,
            diagnostic = diagnostic,
        )
    }
}

/**
 * Centralizes producer-side authority decisions so an extractor cannot
 * accidentally turn a dropped item or an error-tolerant response into a
 * complete source snapshot.
 */
object SourceSnapshotAuthority {
    /**
     * A fallback may repair a failed primary extractor, but it must never
     * upgrade a primary partial result by silently discarding its uncertainty.
     */
    fun preserveOrFallback(
        primary: SourceSnapshot,
        fallback: () -> SourceSnapshot,
    ): SourceSnapshot = when (primary.authority) {
        SourceSnapshot.Authority.AUTHORITATIVE,
        SourceSnapshot.Authority.PARTIAL -> primary
        SourceSnapshot.Authority.FAILED -> fallback()
    }

    fun fromNewPipe(
        items: List<ResultItem>,
        conversionDropped: Boolean = false,
        continuationIncomplete: Boolean = false,
        extractionFailure: Throwable? = null,
        diagnostic: String = "",
    ): SourceSnapshot {
        if (extractionFailure != null) {
            return if (items.isEmpty() && !conversionDropped && !continuationIncomplete) {
                SourceSnapshot.failed(extractionFailure, diagnostic)
            } else {
                SourceSnapshot.partial(items, diagnostic, extractionFailure)
            }
        }
        return if (conversionDropped || continuationIncomplete) {
            SourceSnapshot.partial(items, diagnostic)
        } else {
            SourceSnapshot.authoritative(items)
        }
    }

    fun fromYtdlp(
        items: List<ResultItem>,
        singleItem: Boolean,
        executionFailure: Throwable? = null,
        parseDropped: Boolean = false,
        ignoredChildErrors: Boolean = false,
        diagnostic: String = "",
    ): SourceSnapshot {
        if (executionFailure != null) {
            return if (items.isEmpty()) {
                SourceSnapshot.failed(executionFailure, diagnostic)
            } else {
                SourceSnapshot.partial(items, diagnostic, executionFailure)
            }
        }
        return if (parseDropped || ignoredChildErrors || !singleItem) {
            SourceSnapshot.partial(items, diagnostic)
        } else {
            SourceSnapshot.authoritative(items)
        }
    }
}
