package com.ireum.ytdl.work

import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import kotlinx.coroutines.CancellationException

/**
 * Requeues worker-owned rows without discarding an already authoritative
 * History replacement mismatch.  The mismatch write is deliberately kept
 * separate from the linked-ledger transition; this only protects the queue
 * ownership fallback when the terminal Download write was unavailable.
 */
internal suspend fun requeueOwnedDownloadRowsPreservingIssues(
    downloadIds: Collection<Long>,
    authoritativeIssues: Map<Long, DownloadIssue>,
    requeueWithIssue: suspend (List<Long>, DownloadIssue) -> Unit,
    requeueOrdinary: suspend (List<Long>) -> Unit,
) {
    val distinctIds = downloadIds.distinct()
    val issueGroups = linkedMapOf<
        Pair<DownloadIssueCode, DownloadIssueStage>,
        Pair<DownloadIssue, MutableList<Long>>
    >()
    val ordinaryIds = mutableListOf<Long>()

    distinctIds.forEach { id ->
        val issue = authoritativeIssues[id]
        if (issue == null) {
            ordinaryIds += id
        } else {
            val key = issue.code to issue.stage
            val group = issueGroups[key]
            if (group == null) {
                issueGroups[key] = issue to mutableListOf(id)
            } else {
                group.second += id
            }
        }
    }

    issueGroups.values.forEach { (issue, ids) ->
        requeueWithIssue(ids, issue)
    }
    if (ordinaryIds.isNotEmpty()) {
        requeueOrdinary(ordinaryIds)
    }
}

/**
 * Keeps an exceptional worker exit observable even when its ownership cleanup
 * also fails.  Cleanup errors are diagnostic details on the original failure;
 * they must not turn an unsuccessful operation into handled success or into a
 * cancellation.
 */
internal suspend fun rethrowAfterOwnedDownloadCleanup(
    failure: Exception,
    cleanup: suspend () -> Unit,
): Nothing {
    try {
        cleanup()
    } catch (cancelled: CancellationException) {
        failure.addSuppressed(cancelled)
    } catch (cleanupFailure: Exception) {
        failure.addSuppressed(cleanupFailure)
    }
    throw failure
}
