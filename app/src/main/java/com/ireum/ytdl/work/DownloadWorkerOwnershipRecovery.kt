package com.ireum.ytdl.work

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementRefusal
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal data class AbandonedDownloadExecution(
    val downloadId: Long,
    val executionId: String,
    val status: String,
)

/** Serializes queue claim/publication with every stale-owner recovery scan. */
internal suspend inline fun <T> withDownloadWorkerExecutionLock(
    block: suspend () -> T,
): T = DownloadWorker.downloadWorkerMutex.withLock { block() }

/**
 * Reconciles rows left in a running state by an execution that no longer has
 * a process-local owner.  The caller supplies the execution-scoped CAS and a
 * reread so a newer attempt can win without being touched by stale recovery.
 */
internal suspend fun recoverAbandonedDownloadExecutions(
    rows: Collection<AbandonedDownloadExecution>,
    isOwnedBy: (Long, String) -> Boolean,
    requeue: suspend (Long, String) -> Int,
    readCurrent: suspend (Long) -> AbandonedDownloadExecution?,
) {
    val runningStatuses = setOf("Active", "PostProcessing")
    var firstFailure: Exception? = null
    rows.forEach { row ->
        try {
            if (isOwnedBy(row.downloadId, row.executionId)) return@forEach

            val affected = requeue(row.downloadId, row.executionId)
            if (affected > 0) return@forEach

            val current = readCurrent(row.downloadId)
            if (
                current == null ||
                current.executionId != row.executionId ||
                current.status !in runningStatuses ||
                isOwnedBy(current.downloadId, current.executionId)
            ) {
                return@forEach
            }

            error(
                "Abandoned download execution remained running after startup recovery " +
                    "id=${row.downloadId} executionId=${row.executionId}"
            )
        } catch (cancelled: CancellationException) {
            firstFailure = firstFailure.addOrSuppress(cancelled)
        } catch (failure: Exception) {
            firstFailure = firstFailure.addOrSuppress(failure)
        }
    }
    firstFailure?.let { throw it }
}

internal fun Exception?.addOrSuppress(failure: Exception): Exception =
    this?.also { if (it !== failure) it.addSuppressed(failure) } ?: failure

/**
 * Process-local liveness registry keyed by the exact Download execution token.
 * A dead E1 must not hide a recoverable row, and releasing E1 must never clear
 * a newer E2 owner for the same numeric Download ID.
 */
internal object DownloadWorkerExecutionOwners {
    private val owners: MutableMap<Long, String> = ConcurrentHashMap()

    fun claim(downloadId: Long, executionId: String) {
        if (executionId.isNotBlank()) owners[downloadId] = executionId
    }

    fun isOwnedBy(downloadId: Long, executionId: String): Boolean =
        executionId.isNotBlank() && owners[downloadId] == executionId

    fun ownerOf(downloadId: Long): String? = owners[downloadId]

    fun release(downloadId: Long, executionId: String) {
        if (executionId.isNotBlank()) owners.remove(downloadId, executionId)
    }

    /** Test-only teardown for the process-local owner registry. */
    internal fun clearForTesting() {
        owners.clear()
    }
}

/**
 * Native Download work is addressed by numeric Download ID.  Keep the exact
 * execution token in process memory so a stale attempt cannot destroy a
 * newer attempt's process after the database row has been reused.  The owner
 * is retained across same-execution retries and hard-sub work until every
 * registered native process has quiesced.
 */
internal object DownloadWorkerProcessOwners {
    private val owners: MutableMap<Long, String> = ConcurrentHashMap()

    fun claim(downloadId: Long, executionId: String): Boolean {
        if (executionId.isBlank()) return true
        val existing = owners[downloadId]
        if (existing == null && DownloadWorker.hasConflictingNativeProcess(downloadId, executionId)) {
            return false
        }
        val published = owners.putIfAbsent(downloadId, executionId)
        return published == null || published == executionId
    }

    fun isOwnedBy(downloadId: Long, executionId: String): Boolean =
        executionId.isNotBlank() && owners[downloadId] == executionId

    fun ownerOf(downloadId: Long): String? = owners[downloadId]

    /** A queued candidate cannot claim resources held by an unresolved E1. */
    fun canClaimNewExecution(downloadId: Long): Boolean = owners[downloadId] == null

    fun release(downloadId: Long, executionId: String) {
        if (executionId.isNotBlank()) owners.remove(downloadId, executionId)
    }

    /** Test-only teardown for the process-local native owner registry. */
    internal fun clearForTesting() {
        owners.clear()
    }
}

/**
 * Serializes long-lived destructive work for one Download resource.  A
 * nonblank executionId identifies the exact attempt; the blank form is used
 * only by legacy-row cleanup before a new execution token is published.
 *
 * The worker holds this lease around move/burn/temp mutation, while pause and
 * cancel paths acquire the same lease before committing a durable stop.  A
 * newer execution therefore cannot overlap resources with an older attempt,
 * without serializing the whole yt-dlp lifetime or unrelated downloads.
 */
internal object DownloadWorkerExecutionSideEffectLeases {
    // The mutex is per Download ID, not per execution token.  E1 and E2 use
    // different tokens but still share the same temporary/output resources;
    // separate token keys would allow their destructive side effects to
    // overlap during a rapid requeue.
    private val leases: MutableMap<Long, Mutex> = ConcurrentHashMap()

    suspend fun <T> withLease(
        downloadId: Long,
        executionId: String,
        block: suspend () -> T,
    ): T {
        val mutex = leases.computeIfAbsent(downloadId) { Mutex() }
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

internal suspend inline fun <T> withDownloadWorkerExecutionSideEffectLease(
    downloadId: Long,
    executionId: String,
    crossinline block: suspend () -> T,
): T = DownloadWorkerExecutionSideEffectLeases.withLease(downloadId, executionId) {
    block()
}

internal suspend fun <T> withDownloadWorkerExecutionSideEffectLeases(
    executions: Collection<Pair<Long, String>>,
    block: suspend () -> T,
): T {
    val distinctExecutions = executions
        .filter { it.second.isNotBlank() }
        // The lease is resource-scoped by Download ID.  Acquiring the same
        // mutex twice for two historical tokens of one row would deadlock and
        // would also suggest that the old token still owns the resource.
        .distinctBy { it.first }
        .sortedBy { it.first }

    suspend fun acquire(index: Int): T {
        val execution = distinctExecutions.getOrNull(index) ?: return block()
        return DownloadWorkerExecutionSideEffectLeases.withLease(
            downloadId = execution.first,
            executionId = execution.second,
        ) {
            acquire(index + 1)
        }
    }

    return acquire(0)
}

internal fun canCancelExecutionProcess(
    downloadId: Long,
    expectedExecutionId: String,
): Boolean {
    if (expectedExecutionId.isBlank()) return false
    val liveProcessOwner = DownloadWorkerProcessOwners.ownerOf(downloadId)
    if (liveProcessOwner != null && liveProcessOwner != expectedExecutionId) {
        return false
    }
    val liveExecutionOwner = DownloadWorkerExecutionOwners.ownerOf(downloadId)
    return liveExecutionOwner == null || liveExecutionOwner == expectedExecutionId
}

/**
 * Cancellation requested a native process stop, but no OS termination
 * acknowledgement was obtained.  The exact process owner must remain a
 * recovery barrier until a later lifecycle pass proves quiescence.
 */
internal class NativeProcessQuiescenceException(
    downloadId: Long,
    executionId: String,
    val originalFailure: Throwable? = null,
    @Volatile var nativeQuiescenceProven: Boolean = false,
) : IllegalStateException(
    "Native process quiescence was not proven for download $downloadId " +
        "executionId=$executionId",
    originalFailure,
)

/**
 * The production stopped-worker path uses this issue-aware primitive for both
 * local and durably persisted History replacement decisions.  The repository
 * inserts/verifies the typed refusal in the same transaction as its committed
 * History check, so a local issue cannot fall through to ordinary requeue.
 */
internal suspend fun cleanupStoppedDownloadExecution(
    repository: DownloadRepository,
    downloadId: Long,
    executionId: String,
    authoritativeIssue: DownloadIssue? = null,
    recoveryContext: Context? = null,
    dbManager: DBManager? = null,
): DownloadRepository.RunningDownloadRequeueResult {
    if (
        recoveryContext != null &&
            DownloadExecutionRecovery.pendingDispositionForExecution(
                recoveryContext,
                downloadId,
            )?.let {
                it == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL ||
                    it == DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE
            } == true
    ) {
        check(
            DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                context = recoveryContext,
                dbManager = dbManager ?: DBManager.getInstance(recoveryContext),
                downloadId = downloadId,
                executionId = executionId,
            )
        ) {
            "User-stop recovery did not converge for download $downloadId"
        }
        return DownloadRepository.RunningDownloadRequeueResult.USER_STOP_CONVERGED
    }
    val refusal = authoritativeIssue?.let { HistoryReplacementRefusal.from(it) }
    if (
        authoritativeIssue != null &&
            refusal == null &&
            authoritativeIssue.code != DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED
    ) {
        error(
            "Unsupported authoritative cleanup issue cannot enter Download requeue: " +
                authoritativeIssue.code
        )
    }
    val convergedQualityAuthorityLoss = authoritativeIssue?.code ==
        DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED
    val result = if (convergedQualityAuthorityLoss) {
        repository.convergeQualityAuthorityLoss(
            id = downloadId,
            expectedExecutionId = executionId,
        )
    } else {
        repository.requeueRunningDownload(
            id = downloadId,
            expectedExecutionId = executionId,
            authoritativeRefusal = refusal,
        )
    }
    if (authoritativeIssue != null) {
        check(
            result != DownloadRepository.RunningDownloadRequeueResult.REQUEUED &&
                (!convergedQualityAuthorityLoss ||
                    result == DownloadRepository.RunningDownloadRequeueResult.AUTHORITATIVE_ISSUE_CONVERGED ||
                    result == DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT)
        ) {
            "Authoritative History refusal was downgraded to ordinary requeue " +
                "for download $downloadId"
        }
    }
    if (result == DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT) {
        repository.completeAndDelete(
            id = downloadId,
            expectedExecutionId = executionId,
        )
    }
    return result
}

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
