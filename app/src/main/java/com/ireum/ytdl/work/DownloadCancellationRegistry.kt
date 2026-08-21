package com.ireum.ytdl.work

import java.util.concurrent.ConcurrentHashMap

/**
 * Stable origin for a per-download stop request.
 *
 * The Download row is intentionally not consulted here: a rapid Resume can
 * change Paused back to Queued before the old child observes its cancellation.
 * The execution token keeps that old cancellation attached to the old attempt.
 */
internal object DownloadCancellationRegistry {
    enum class Reason {
        PAUSED,
        CANCELLED,
    }

    private val requests = ConcurrentHashMap<String, Reason>()

    fun record(downloadId: Long, executionId: String, reason: Reason) {
        if (executionId.isNotBlank()) {
            requests[key(downloadId, executionId)] = reason
        }
    }

    fun belongsTo(downloadId: Long, executionId: String): Boolean =
        executionId.isNotBlank() && requests.containsKey(key(downloadId, executionId))

    private fun key(downloadId: Long, executionId: String): String =
        "$downloadId\u0000$executionId"
}
