package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.DownloadExecutionOwnershipLostException
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.util.download.DownloadIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    isCancellationRequested: (suspend () -> Boolean)? = null,
    onLinkedTransitionFailure: (suspend (Exception) -> Unit)? = null,
): HistoryReplacementPersistenceResult {
    try {
        if (isCancellationRequested?.invoke() == true) {
            throw CancellationException("Linked low-quality cancellation was already requested")
        }
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
    } catch (error: Exception) {
        // Keep the existing non-atomic Download/ledger boundary, but create a
        // live-process convergence debt so a durable Download Error cannot
        // strand a nonterminal linked child until the next app restart.
        try {
            onLinkedTransitionFailure?.invoke(error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The primary Download terminal state remains authoritative even
            // if scheduling its convergence retry also fails.
        }
    }
    return HistoryReplacementPersistenceResult.Persisted
}

/**
 * Persists an ordinary worker terminal result only while the exact worker
 * still owns the Download side-effect lease.  The short global lock protects
 * the final ownership/user-stop read; the terminal Room/ledger work then
 * runs with only the per-Download lease held, so no slow external operation
 * is placed under the global execution lock.
 *
 * A History refusal is a stronger operation fact than an ordinary Error
 * projection.  If a durable USER_STOP already won before this boundary, keep
 * that refusal carrier and stop before writing Error.  This prevents a stale
 * worker from making Cancel/Pause recovery impossible while retaining the
 * immutable refusal proof.
 */
internal suspend fun persistHistoryReplacementTerminalStateWithOwnedExecution(
    context: Context,
    dbManager: DBManager,
    downloadItem: DownloadItem,
    issue: DownloadIssue,
    preserveRefusalIssue: DownloadIssue? = null,
    persistDownload: suspend () -> Unit,
    transitionLinkedDownload: suspend (String) -> Unit,
    isCancellationRequested: (suspend () -> Boolean)? = null,
    onLinkedTransitionFailure: (suspend (Exception) -> Unit)? = null,
): HistoryReplacementPersistenceResult {
    val beforeTerminalPersistence = DownloadWorkerEffectTestHooks
        .beforeFailureTerminalPersistenceForTesting
    DownloadWorkerEffectTestHooks.beforeFailureTerminalPersistenceForTesting = null
    beforeTerminalPersistence?.invoke()

    return withDownloadWorkerExecutionSideEffectLease(
        downloadId = downloadItem.id,
        executionId = downloadItem.executionId,
    ) {
        val userStopWon = withDownloadWorkerExecutionLock {
            val current = dbManager.downloadDao.getNullableDownloadById(downloadItem.id)
            if (
                current == null ||
                    current.executionId != downloadItem.executionId ||
                    current.status !in setOf(
                        DownloadRepository.Status.Active.name,
                        DownloadRepository.Status.PostProcessing.name,
                    ) ||
                    !DownloadWorkerExecutionOwners.isOwnedBy(
                        downloadItem.id,
                        downloadItem.executionId,
                    )
            ) {
                throw DownloadExecutionOwnershipLostException(
                    downloadId = downloadItem.id,
                    expectedExecutionId = downloadItem.executionId,
                    actualExecutionId = current?.executionId,
                )
            }
            if (dbManager.lowQualityRedownloadDao.hasCancellationRequestedByDownload(downloadItem.id)) {
                throw CancellationException(
                    "Low-quality cancellation revoked terminal failure authority " +
                        "id=${downloadItem.id}",
                )
            }
            hasDurableUserStopRevokedAuthority(context, dbManager, current)
        }

        if (userStopWon) {
            val refusal = preserveRefusalIssue?.takeIf {
                HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(it.code.name)
            }
            if (refusal != null) {
                val carrierPersisted = withContext(NonCancellable) {
                    DownloadRepository(dbManager).persistHistoryReplacementRefusalCarrier(
                        id = downloadItem.id,
                        expectedExecutionId = downloadItem.executionId,
                        issueCode = refusal.code.name,
                        issueStage = refusal.stage.name,
                    )
                }
                check(carrierPersisted) {
                    "History refusal carrier could not be preserved while user stop was pending " +
                        "for download ${downloadItem.id}"
                }
            }
            throw CancellationException(
                "Durable user stop revoked terminal failure authority " +
                    "id=${downloadItem.id} executionId=${downloadItem.executionId}",
            )
        }

        val injectedFailure = DownloadWorkerEffectTestHooks
            .failureTerminalPersistenceForTesting
            ?.invoke(downloadItem.id)
        val injectedNoOp = DownloadWorkerEffectTestHooks
            .failureTerminalPersistenceNoOpForTesting
            ?.invoke(downloadItem.id) == true
        val result = when {
            injectedFailure != null -> HistoryReplacementPersistenceResult.Failed(injectedFailure)
            injectedNoOp -> HistoryReplacementPersistenceResult.Failed(
                IllegalStateException(
                    "Injected ordinary terminal affected-row no-op for download ${downloadItem.id}",
                )
            )
            else -> persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = persistDownload,
                transitionLinkedDownload = transitionLinkedDownload,
                isCancellationRequested = isCancellationRequested,
                onLinkedTransitionFailure = onLinkedTransitionFailure,
            )
        }
        val refusal = preserveRefusalIssue?.takeIf {
            HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(it.code.name)
        }
        if (refusal != null && result is HistoryReplacementPersistenceResult.Persisted) {
            val carrierPersisted = withContext(NonCancellable) {
                DownloadRepository(dbManager).persistHistoryReplacementRefusalCarrier(
                    id = downloadItem.id,
                    expectedExecutionId = downloadItem.executionId,
                    issueCode = refusal.code.name,
                    issueStage = refusal.stage.name,
                )
            }
            if (!carrierPersisted) {
                HistoryReplacementPersistenceResult.Failed(
                    IllegalStateException(
                        "History refusal carrier was not durable for download ${downloadItem.id}",
                    )
                )
            } else {
                result
            }
        } else {
            result
        }
    }
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
