package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import java.util.UUID

/**
 * Scheduler ownership is deliberately narrower than the Download row's
 * semantic status.  An Active/PostProcessing row without the current
 * process-local execution owner is recovery debt: it still fences its own
 * Download/native resources, but it does not consume a sibling's scheduler
 * capacity or priority-running authority.
 */
internal data class DownloadSchedulerOwnershipSnapshot(
    val liveCapacityIds: Set<Long>,
    val liveExecutionIds: Set<Long>,
    val liveHardSubIds: Set<Long>,
    val recoveryOwnedIds: Set<Long>,
)

internal fun classifyDownloadSchedulerOwnership(
    activeOrPostProcessing: Collection<DownloadItem>,
): DownloadSchedulerOwnershipSnapshot {
    val live = activeOrPostProcessing.filter { item ->
        item.executionId.isNotBlank() &&
            DownloadWorkerExecutionOwners.isOwnedBy(item.id, item.executionId)
    }
    val liveIds = live.mapTo(linkedSetOf(), DownloadItem::id)
    return DownloadSchedulerOwnershipSnapshot(
        liveCapacityIds = live
            .asSequence()
            .filter { it.status == DownloadRepository.Status.Active.name }
            .mapTo(linkedSetOf(), DownloadItem::id),
        liveExecutionIds = liveIds,
        liveHardSubIds = live
            .asSequence()
            // Preserve the scheduler's existing hard-sub policy: only a
            // live Active row consumes hard-sub admission authority. A live
            // PostProcessing row has already released that scheduler gate.
            .filter { it.status == DownloadRepository.Status.Active.name }
            .filter(::isHardSubRedownloadForScheduler)
            .mapTo(linkedSetOf(), DownloadItem::id),
        recoveryOwnedIds = activeOrPostProcessing
            .asSequence()
            .filter { it.id !in liveIds }
            .mapTo(linkedSetOf(), DownloadItem::id),
    )
}

internal data class DownloadWorkerAdmissionResult(
    val selectedCandidates: List<DownloadItem>,
    val claimedItems: List<DownloadItem>,
    val ownership: DownloadSchedulerOwnershipSnapshot,
    val prioritySnapshot: DownloadQueuePrioritySnapshot,
    val stoppedAfterPriorities: Boolean,
)

/**
 * The production queue admission boundary.  DownloadWorker calls this for
 * every queue observation, and tests use the same boundary with the real DAO
 * claim callback.  Selection is performed under the short global lock; the
 * claim callback acquires each candidate's per-Download lease before the
 * short claim transaction, so no long native/filesystem wait is introduced
 * under the global lock.
 */
internal suspend fun admitQueuedDownloadsThroughProductionPath(
    dbManager: DBManager,
    items: List<DownloadItem>,
    priorityItemIds: List<Long>,
    currentTimeMillis: Long,
    concurrentDownloadLimit: Int,
    continueAfterPriorityItems: Boolean,
    claim: suspend (DownloadItem) -> DownloadItem?,
): DownloadWorkerAdmissionResult {
    data class Selection(
        val candidates: List<DownloadItem>,
        val ownership: DownloadSchedulerOwnershipSnapshot,
        val prioritySnapshot: DownloadQueuePrioritySnapshot,
        val stoppedAfterPriorities: Boolean,
    )

    val selection = withDownloadWorkerExecutionLock {
        val activeOrPostProcessing = dbManager.downloadDao
            .getActiveAndPostProcessingDownloadsList()
        val ownership = classifyDownloadSchedulerOwnership(activeOrPostProcessing)
        val priorityRecords = if (priorityItemIds.isEmpty()) {
            emptyList()
        } else {
            dbManager.downloadDao.getDownloadsByIdsSuspend(priorityItemIds)
        }
        val prioritySnapshot = DownloadQueuePolicy.prioritySnapshot(
            priorityItemIds = priorityItemIds,
            queueRecords = priorityRecords,
            eligibleItemIds = priorityRecords.asSequence()
                .filter { record ->
                    record.status in setOf(
                        DownloadRepository.Status.Queued.name,
                        DownloadRepository.Status.Scheduled.name,
                    ) && record.downloadStartTime <= currentTimeMillis
                }
                .mapTo(linkedSetOf(), DownloadItem::id),
            activeOrRunningIds = ownership.liveExecutionIds,
            nonterminalLinkedIds = if (priorityItemIds.isEmpty()) {
                emptySet()
            } else {
                dbManager.lowQualityRedownloadDao
                    .getNonterminalItemsByDownloadIds(priorityItemIds)
                    .mapNotNullTo(linkedSetOf()) { it.downloadId }
            },
            idOf = DownloadItem::id,
            statusOf = DownloadItem::status,
        )
        val stoppedAfterPriorities = DownloadQueuePolicy.shouldStopAfterPriorities(
            prioritySnapshot.outstandingIds,
            continueAfterPriorityItems,
        )
        val availableSlots = (
            concurrentDownloadLimit.coerceAtLeast(1) - ownership.liveCapacityIds.size
        ).coerceAtLeast(0)
        val baseEligible = if (stoppedAfterPriorities) {
            emptyList()
        } else {
            DownloadQueuePolicy.selectCandidates(
                items = if (prioritySnapshot.outstandingIds.isEmpty()) {
                    items
                } else {
                    (priorityRecords + items).distinctBy(DownloadItem::id)
                },
                runningIds = ownership.liveExecutionIds,
                prioritySnapshot = prioritySnapshot,
                availableSlots = availableSlots,
                idOf = DownloadItem::id,
            )
        }
        val hardSubEligible = if (ownership.liveHardSubIds.isNotEmpty()) {
            baseEligible.filterNot(::isHardSubRedownloadForScheduler)
        } else {
            val hardSubs = baseEligible.filter(::isHardSubRedownloadForScheduler)
            if (hardSubs.size <= 1) {
                baseEligible
            } else {
                val firstHardSubId = hardSubs.first().id
                baseEligible.filter { candidate ->
                    !isHardSubRedownloadForScheduler(candidate) || candidate.id == firstHardSubId
                }
            }
        }
        Selection(
            candidates = hardSubEligible.filterNot { candidate ->
                isDurablyCommittedHistoryReplacementForScheduler(dbManager, candidate)
            },
            ownership = ownership,
            prioritySnapshot = prioritySnapshot,
            stoppedAfterPriorities = stoppedAfterPriorities,
        )
    }

    val claimedItems = selection.candidates.mapNotNull { candidate -> claim(candidate) }
    return DownloadWorkerAdmissionResult(
        selectedCandidates = selection.candidates,
        claimedItems = claimedItems,
        ownership = selection.ownership,
        prioritySnapshot = selection.prioritySnapshot,
        stoppedAfterPriorities = selection.stoppedAfterPriorities,
    )
}

/**
 * Exact production claim used after the admission policy has selected a
 * candidate.  The worker supplies only its local bookkeeping callback; the
 * ownership/lease/CAS protocol is shared with production-wiring tests.
 */
internal suspend fun claimDownloadThroughProductionAdmission(
    context: Context,
    dbManager: DBManager,
    candidate: DownloadItem,
    concurrentDownloadLimit: Int,
    onClaimed: (DownloadItem) -> Unit = {},
): DownloadItem? = withDownloadWorkerExecutionSideEffectLease(
    downloadId = candidate.id,
    executionId = "",
) {
    // The claim boundary is also used by production-wiring callers that do
    // not first enter DownloadWorker.doWork(). Ensure the durable marker
    // namespace is configured before the fail-closed native check.
    YtdlpNativeProcessBarrier.configure(context)
    if (DownloadExecutionRecovery.pendingDownloadIds(context).contains(candidate.id)) {
        // A durable recovery/finalization carrier is stronger than a queued
        // observation.  Do not let an unrelated worker reinterpret it as a
        // fresh E2 attempt while the carrier is still present.
        return@withDownloadWorkerExecutionSideEffectLease null
    }
    if (
        !DownloadWorkerProcessOwners.canClaimNewExecution(candidate.id) ||
            DownloadWorker.hasAnyRegisteredNativeProcess(candidate.id)
    ) {
        // A prior execution still owns an unresolved native process or
        // durable marker.  Resource reuse remains fenced until recovery has
        // proved exact quiescence.
        return@withDownloadWorkerExecutionSideEffectLease null
    }
    val dao = dbManager.downloadDao
    withDownloadWorkerExecutionLock {
        // Selection may have occurred before another worker claimed a
        // sibling. Revalidate capacity and hard-sub exclusivity while this
        // candidate's lease is already held and before publishing its token.
        // This preserves the lease -> global lock -> short CAS order without
        // holding the global lock while waiting for a per-Download lease.
        // Recheck the per-Download native authority here as well: a durable
        // marker can appear after the initial precheck but before publication.
        if (
            !DownloadWorkerProcessOwners.canClaimNewExecution(candidate.id) ||
                DownloadWorker.hasAnyRegisteredNativeProcess(candidate.id)
        ) {
            return@withDownloadWorkerExecutionLock null
        }
        val currentOwnership = classifyDownloadSchedulerOwnership(
            dao.getActiveAndPostProcessingDownloadsList()
        )
        if (
            currentOwnership.liveCapacityIds.size >= concurrentDownloadLimit.coerceAtLeast(1) ||
                (
                    isHardSubRedownloadForScheduler(candidate) &&
                        currentOwnership.liveHardSubIds.isNotEmpty()
                    )
        ) {
            return@withDownloadWorkerExecutionLock null
        }
        val currentCandidate = dao.getNullableDownloadById(candidate.id)
            ?: return@withDownloadWorkerExecutionLock null
        if (isDurablyCommittedHistoryReplacementForScheduler(dbManager, currentCandidate)) {
            // Queue selection and this claim are separate observations.  The
            // History semantic commit may have won in between; a finalization
            // debt row must never become a fresh destructive execution.
            return@withDownloadWorkerExecutionLock null
        }
        val executionId = UUID.randomUUID().toString()
        val claimed = dao.claimDownloadForWorker(
            id = candidate.id,
            expectedOperationId = candidate.operationId,
            expectedRetryAttempt = candidate.retryAttempt,
            executionId = executionId,
        ) == 1
        if (!claimed) {
            null
        } else {
            DownloadWorkerExecutionOwners.claim(candidate.id, executionId)
            dao.getNullableDownloadById(candidate.id)
                ?.takeIf { it.executionId == executionId }
                ?.also(onClaimed)
        }
    }
}

internal fun isHardSubRedownloadForScheduler(item: DownloadItem): Boolean =
    item.type == DownloadType.video &&
        item.videoPreferences.embedSubs &&
        HistoryRedownloadMarker.parse(item.playlistURL) != null

internal fun isDurablyCommittedHistoryReplacementForScheduler(
    dbManager: DBManager,
    downloadItem: DownloadItem,
): Boolean {
    val marker = HistoryRedownloadMarker.parse(downloadItem.playlistURL) ?: return false
    return dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId == downloadItem.id
}
