package com.ireum.ytdl.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
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
    rows.forEach { row ->
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
    }
}

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
}

/**
 * The native yt-dlp process is addressed by numeric Download ID.  Keep its
 * exact execution token in process memory so a stale attempt cannot destroy a
 * newer attempt's process after the database row has been reused.
 */
internal object DownloadWorkerProcessOwners {
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
}

/**
 * Serializes long-lived destructive work for one exact Download execution.
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
        require(executionId.isNotBlank()) { "Execution lease requires an execution token" }
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
