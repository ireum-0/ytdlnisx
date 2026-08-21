package com.ireum.ytdl.work

import com.ireum.ytdl.util.download.DownloadIssue
import kotlinx.coroutines.CancellationException

internal sealed interface HistoryReplacementPersistenceResult {
    data object Persisted : HistoryReplacementPersistenceResult

    data class Failed(
        val error: Exception,
    ) : HistoryReplacementPersistenceResult
}

/**
 * Attempts the authoritative queue write and, only after it succeeds, the
 * linked low-quality transition.  The two writes intentionally remain
 * non-atomic; a ledger failure is a follow-up diagnostic and must not erase a
 * durable Download mismatch.
 */
internal suspend fun persistHistoryReplacementTerminalState(
    issue: DownloadIssue,
    persistDownload: suspend () -> Unit,
    transitionLinkedDownload: suspend (String) -> Unit,
): HistoryReplacementPersistenceResult {
    try {
        persistDownload()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return HistoryReplacementPersistenceResult.Failed(error)
    }

    try {
        transitionLinkedDownload(issue.code.name)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Keep the existing non-atomic Download/ledger boundary.
    }
    return HistoryReplacementPersistenceResult.Persisted
}

internal fun authoritativeDownloadIssue(
    establishedHistoryIssue: DownloadIssue?,
    fallbackIssue: DownloadIssue,
): DownloadIssue = establishedHistoryIssue ?: fallbackIssue

internal fun unrecoverableHistoryReplacementPersistenceFailure(
    establishedHistoryIssue: DownloadIssue?,
    result: HistoryReplacementPersistenceResult?,
): Exception? = if (establishedHistoryIssue != null) {
    (result as? HistoryReplacementPersistenceResult.Failed)?.error
} else {
    null
}
