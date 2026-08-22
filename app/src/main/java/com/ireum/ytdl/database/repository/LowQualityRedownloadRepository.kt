package com.ireum.ytdl.database.repository

import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadLiveCounts
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.util.LowQualityRedownloadProgress
import com.ireum.ytdl.util.LowQualityRedownloadCompletionPolicy
import com.ireum.ytdl.util.LowQualityRedownloadLinkedDownloadPolicy
import com.ireum.ytdl.util.HistoryReplacementSourceIdentity
import com.ireum.ytdl.work.DownloadCancellationRegistry
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLeases
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class LowQualityRedownloadRepository(private val database: DBManager) {
    private val dao = database.lowQualityRedownloadDao

    val currentOperation: Flow<LowQualityRedownloadOperation?> = dao.observeCurrentOperation()

    fun observeItems(operationId: String): Flow<List<LowQualityRedownloadItem>> =
        dao.observeItems(operationId)

    fun observeLiveCounts(operationId: String): Flow<LowQualityRedownloadLiveCounts> =
        dao.observeLiveCounts(operationId)

    suspend fun getCurrentOperation(): LowQualityRedownloadOperation? = dao.getCurrentOperation()

    suspend fun getActiveOperation(): LowQualityRedownloadOperation? = dao.getActiveOperation()

    suspend fun getOperation(operationId: String): LowQualityRedownloadOperation? =
        dao.getOperation(operationId)

    suspend fun getItems(operationId: String): List<LowQualityRedownloadItem> =
        dao.getItems(operationId)

    suspend fun createOrReconnect(now: Long = System.currentTimeMillis()): LowQualityRedownloadOperation =
        database.withTransaction {
            dao.getActiveOperation()?.let { return@withTransaction it }
            val upperBound = database.historyDao.getVideoQualityScanUpperBound()
            val operation = LowQualityRedownloadOperation(
                operationId = UUID.randomUUID().toString(),
                scanUpperBoundHistoryId = upperBound,
                scanTotal = database.historyDao.getVideoQualityScanCount(upperBound),
                createdAt = now,
                updatedAt = now
            )
            dao.createOrReconnect(operation)
        }

    suspend fun setSelected(operationId: String, historyId: Long, selected: Boolean) {
        dao.setSelected(operationId, historyId, selected, System.currentTimeMillis())
    }

    suspend fun confirmSelection(operationId: String): Boolean = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction false
        if (
            operation.stateValue.isTerminal ||
            operation.phaseValue != LowQualityRedownloadPhase.AWAITING_SELECTION ||
            dao.countSelected(operationId) == 0
        ) {
            return@withTransaction false
        }
        // Candidate identity is captured during the scan and must be present
        // before the user-confirmed operation can enter PREPARING.  Legacy
        // rows without it remain fail-closed instead of rebinding by ID.
        if (dao.getSelectedItems(operationId).any {
                it.intendedSourceUrl.isBlank() || it.intendedType.isBlank()
            }
        ) {
            return@withTransaction false
        }
        val now = System.currentTimeMillis()
        dao.markUnselected(operationId, now)
        dao.advancePhase(
            operationId,
            LowQualityRedownloadPhase.AWAITING_SELECTION.name,
            LowQualityRedownloadPhase.PREPARING.name,
            now
        ) == 1
    }

    suspend fun checkpointScan(
        operationId: String,
        historyId: Long,
        candidate: LowQualityRedownloadItem?,
        failed: Boolean
    ) {
        dao.checkpointScanItem(
            operationId = operationId,
            cursor = historyId,
            candidate = candidate,
            failed = failed,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun advancePhase(
        operationId: String,
        expected: LowQualityRedownloadPhase,
        next: LowQualityRedownloadPhase
    ): Boolean = dao.advancePhase(
        operationId,
        expected.name,
        next.name,
        System.currentTimeMillis()
    ) == 1

    suspend fun setItemState(
        operationId: String,
        historyId: Long,
        state: LowQualityRedownloadItemState,
        reason: String = ""
    ) {
        dao.setItemState(operationId, historyId, state.name, reason, System.currentTimeMillis())
    }

    suspend fun linkDownloadAtomically(
        operationId: String,
        historyId: Long,
        downloadItem: DownloadItem
    ): Long? = database.withTransaction {
        val current = dao.getItem(operationId, historyId)
            ?: error("Missing low-quality ledger item")
        current.downloadId?.let { return@withTransaction it }
        check(current.stateValue == LowQualityRedownloadItemState.CHECKING) {
            "Low-quality item is not ready for queue linkage"
        }
        if (current.intendedSourceUrl.isNotBlank() || current.intendedType.isNotBlank()) {
            val history = database.historyDao.getNullableItem(historyId)
            when {
                history == null -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.SKIPPED.name,
                        "HISTORY_MISSING",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                current.intendedSourceUrl.isBlank() -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.FAILED.name,
                        "SELECTION_IDENTITY_MISSING",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                current.intendedType.isBlank() -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.FAILED.name,
                        "SELECTION_TYPE_MISSING",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                current.intendedType != history.type.name -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.SKIPPED.name,
                        "SELECTION_TYPE_CHANGED",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                !HistoryReplacementSourceIdentity.matches(
                    current.intendedSourceUrl,
                    history.url
                ) -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.SKIPPED.name,
                        "SELECTION_SOURCE_CHANGED",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                !HistoryReplacementSourceIdentity.matches(
                    current.intendedSourceUrl,
                    downloadItem.url
                ) || downloadItem.type.name != current.intendedType -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.FAILED.name,
                        "SELECTION_DOWNLOAD_IDENTITY_MISMATCH",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
            }
        }
        if (
            database.downloadDao.countPendingByPlaylistMarker(
                com.ireum.ytdl.util.HistoryRedownloadMarker.regular(historyId)
            ) > 0
        ) {
            dao.setItemState(
                operationId,
                historyId,
                LowQualityRedownloadItemState.SKIPPED.name,
                "DUPLICATE",
                System.currentTimeMillis()
            )
            return@withTransaction null
        }
        val id = database.downloadDao.insert(downloadItem)
        check(
            dao.linkQueuedDownload(operationId, historyId, id, System.currentTimeMillis()) == 1
        ) { "Low-quality queue linkage lost ownership" }
        id
    }

    suspend fun markDownloadState(
        downloadId: Long,
        state: LowQualityRedownloadItemState,
        reason: String = "",
        expectedExecutionId: String = "",
    ): String? = database.withTransaction {
        if (expectedExecutionId.isNotBlank()) {
            val download = database.downloadDao.getNullableDownloadById(downloadId)
            if (download?.executionId != expectedExecutionId) return@withTransaction null
        }
        val item = dao.getItemByDownloadId(downloadId) ?: return@withTransaction null
        val operation = dao.getOperation(item.operationId) ?: return@withTransaction null
        if (operation.stateValue.isTerminal) return@withTransaction null
        val now = System.currentTimeMillis()
        if (
            operation.cancelRequested ||
            item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED
        ) {
            dao.markCancelledByDownloadId(
                downloadId = downloadId,
                reason = REASON_USER_CANCELLED,
                updatedAt = now,
            )
            finalizeIfReadyLocked(item.operationId, now)
            return@withTransaction item.operationId
        }
        if (state == LowQualityRedownloadItemState.CANCELLATION_REQUESTED) {
            return@withTransaction null
        }
        if (dao.setItemStateByDownloadId(downloadId, state.name, reason, now) != 1) {
            return@withTransaction null
        }
        finalizeIfReadyLocked(item.operationId, now)
        item.operationId
    }

    suspend fun finishNoCandidates(operationId: String) {
        val operation = dao.getOperation(operationId) ?: return
        val allInspectionsFailed =
            operation.scanProcessed > 0 && operation.scanFailures >= operation.scanProcessed
        val state = if (allInspectionsFailed) {
            LowQualityRedownloadOperationState.FAILED
        } else {
            LowQualityRedownloadOperationState.COMPLETED
        }
        val reason = when {
            allInspectionsFailed -> REASON_SCAN_FAILED
            operation.scanFailures > 0 -> REASON_NO_CANDIDATES_WITH_FAILURES
            else -> REASON_NO_CANDIDATES
        }
        dao.finishOperation(
            operationId,
            state.name,
            reason,
            System.currentTimeMillis()
        )
    }

    suspend fun requestCancellation(operationId: String): List<Long> =
        dao.requestCancellationAndMarkItems(operationId, System.currentTimeMillis())

    suspend fun isCancellationRequestedForDownload(downloadId: Long): Boolean =
        database.withTransaction {
            val item = dao.getItemByDownloadId(downloadId) ?: return@withTransaction false
            val operation = dao.getOperation(item.operationId)
            operation?.cancelRequested == true ||
                item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED
        }

    internal data class CancellationCommitResult(
        val downloadIds: List<Long>,
        val publications: List<DownloadCancellationRegistry.Publication>,
    )

    internal suspend fun completePersistedCancellation(operationId: String): List<Long> {
        val result = completePersistedCancellationWithPublications(operationId)
        DownloadCancellationRegistry.publish(result.publications)
        return result.downloadIds
    }

    internal suspend fun completePersistedCancellationWithPublications(
        operationId: String,
    ): CancellationCommitResult {
        val candidateIds = dao.getNonterminalDownloadIds(operationId)
        val expectedExecutionIds = candidateIds.mapNotNull { id ->
            database.downloadDao.getNullableDownloadById(id)
                ?.executionId
                ?.takeIf { it.isNotBlank() }
                ?.let { id to it }
        }.toMap()
        return withDownloadWorkerExecutionSideEffectLeases(
            expectedExecutionIds.map { it.key to it.value },
        ) {
            val publications = mutableListOf<DownloadCancellationRegistry.Publication>()
            val linkedDownloadIds = database.withTransaction {
                val operation = dao.getOperation(operationId)
                    ?: return@withTransaction emptyList()
                if (operation.stateValue.isTerminal || !operation.cancelRequested) {
                    return@withTransaction emptyList()
                }
                val now = System.currentTimeMillis()
                val linkedDownloadIds = dao.getNonterminalDownloadIds(operationId)
                if (linkedDownloadIds.isNotEmpty()) {
                    cancelLinkedDownloadsAndCollectOwnership(
                        linkedDownloadIds,
                        publications,
                        expectedExecutionIds,
                    )
                }
                dao.terminalizeNonterminalItems(
                    operationId,
                    LowQualityRedownloadItemState.CANCELLED.name,
                    REASON_USER_CANCELLED,
                    now
                )
                val downloadRepository = DownloadRepository(database)
                linkedDownloadIds.forEach { downloadId ->
                    downloadRepository.convergeHistoryReplacementRefusalInCurrentTransaction(
                        id = downloadId,
                        forceError = true,
                    )
                }
                if (dao.getOperation(operationId)?.stateValue == LowQualityRedownloadOperationState.RUNNING) {
                    check(
                        dao.finishOperation(
                            operationId,
                            LowQualityRedownloadOperationState.CANCELLED.name,
                            REASON_USER_CANCELLED,
                            now
                        ) == 1
                    ) { "Low-quality cancellation lost operation ownership" }
                }
                linkedDownloadIds
            }
            CancellationCommitResult(linkedDownloadIds, publications)
        }
    }

    suspend fun failCoordinator(
        operationId: String,
        reason: String = REASON_COORDINATOR_FAILURE
    ): List<Long> {
        val result = failCoordinatorWithPublications(operationId, reason)
        DownloadCancellationRegistry.publish(result.publications)
        return result.downloadIds
    }

    internal suspend fun failCoordinatorWithPublications(
        operationId: String,
        reason: String = REASON_COORDINATOR_FAILURE,
    ): CancellationCommitResult {
        val candidateIds = dao.getNonterminalDownloadIds(operationId)
        val expectedExecutionIds = candidateIds.mapNotNull { id ->
            database.downloadDao.getNullableDownloadById(id)
                ?.executionId
                ?.takeIf { it.isNotBlank() }
                ?.let { id to it }
        }.toMap()
        return withDownloadWorkerExecutionSideEffectLeases(
            expectedExecutionIds.map { it.key to it.value },
        ) {
            val publications = mutableListOf<DownloadCancellationRegistry.Publication>()
            val linkedDownloadIds = database.withTransaction {
                val operation = dao.getOperation(operationId)
                    ?: return@withTransaction emptyList()
                if (operation.stateValue.isTerminal) return@withTransaction emptyList()
                val now = System.currentTimeMillis()
                val cancelledByUser = operation.cancelRequested
                val itemState = if (cancelledByUser) {
                    LowQualityRedownloadItemState.CANCELLED
                } else {
                    LowQualityRedownloadItemState.FAILED
                }
                val terminalState = if (cancelledByUser) {
                    LowQualityRedownloadOperationState.CANCELLED
                } else {
                    LowQualityRedownloadOperationState.FAILED
                }
                val terminalReason = if (cancelledByUser) REASON_USER_CANCELLED else reason
                val linkedDownloadIds = dao.getNonterminalDownloadIds(operationId)
                if (linkedDownloadIds.isNotEmpty()) {
                    cancelLinkedDownloadsAndCollectOwnership(
                        linkedDownloadIds,
                        publications,
                        expectedExecutionIds,
                    )
                }
                dao.terminalizeNonterminalItems(
                    operationId,
                    itemState.name,
                    terminalReason,
                    now
                )
                val downloadRepository = DownloadRepository(database)
                linkedDownloadIds.forEach { downloadId ->
                    downloadRepository.convergeHistoryReplacementRefusalInCurrentTransaction(
                        id = downloadId,
                        forceError = true,
                    )
                }
                if (dao.getOperation(operationId)?.stateValue == LowQualityRedownloadOperationState.RUNNING) {
                    check(
                        dao.finishOperation(
                            operationId,
                            terminalState.name,
                            terminalReason,
                            now
                        ) == 1
                    ) { "Low-quality coordinator failure lost operation ownership" }
                }
                linkedDownloadIds
            }
            CancellationCommitResult(linkedDownloadIds, publications)
        }
    }

    /**
     * Bounded low-quality cancellation is item-local to each active child.  The
     * execution token is captured from the same Room transaction that changes
     * the row to Cancelled, before callers destroy the native process.
     */
    private suspend fun cancelLinkedDownloadsAndCollectOwnership(
        ids: List<Long>,
        publications: MutableList<DownloadCancellationRegistry.Publication>,
        expectedExecutionIds: Map<Long, String> = emptyMap(),
    ) {
        ids.forEach { id ->
            val item = database.downloadDao.getNullableDownloadById(id) ?: return@forEach
            val expectedExecutionId = expectedExecutionIds[id]
            if (
                item.executionId.isNotBlank() &&
                expectedExecutionId != item.executionId
            ) {
                return@forEach
            }
            val changed = if (item.executionId.isBlank()) {
                database.downloadDao.cancelByUser(item.id)
            } else {
                database.downloadDao.cancelIfExecutionOwned(item.id, item.executionId)
            }
            if (
                changed == 1 &&
                item.executionId.isNotBlank() &&
                item.status in setOf(
                    DownloadRepository.Status.Active.name,
                    DownloadRepository.Status.PostProcessing.name,
                )
            ) {
                publications += DownloadCancellationRegistry.Publication(
                    downloadId = item.id,
                    executionId = item.executionId,
                    reason = DownloadCancellationRegistry.Reason.CANCELLED,
                )
            }
        }
    }

    suspend fun markLinkedCancelled(operationId: String, downloadIds: List<Long>) {
        downloadIds.forEach { id ->
            dao.markCancelledByDownloadId(
                id,
                REASON_USER_CANCELLED,
                System.currentTimeMillis()
            )
        }
        finalizeIfReady(operationId)
    }

    suspend fun reconcileLinkedDownloads(operationId: String): List<DownloadItem> =
        database.withTransaction {
        val items = dao.getItems(operationId).filter { it.downloadId != null && !it.stateValue.isTerminal }
        if (items.isEmpty()) {
            finalizeIfReadyLocked(operationId, System.currentTimeMillis())
            return@withTransaction emptyList()
        }
        val ids = items.mapNotNull(LowQualityRedownloadItem::downloadId)
        val downloads = database.downloadDao.getDownloadsByIdsSuspend(ids)
        val byId = downloads.associateBy(DownloadItem::id)
        val now = System.currentTimeMillis()
        items.forEach { item ->
            val id = item.downloadId ?: return@forEach
            val download = byId[id]
            if (
                download == null &&
                    item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED &&
                    item.reasonCode.startsWith(DownloadRepository.PENDING_REMOVAL_TOKEN_PREFIX)
            ) {
                if (DownloadRepository.isLivePendingRemovalToken(item.reasonCode)) {
                    // A live Snackbar Undo token is still authoritative.  A
                    // routine reconcile must not consume it before the exact
                    // Undo action or explicit commit gets to run.
                    return@forEach
                }
                // A process death or coordinator reconciliation commits an
                // unresolved delete-for-Undo token rather than inventing a
                // generic failure for the missing, intentionally hidden row.
                dao.markCancelledByDownloadId(
                    id,
                    DownloadRepository.REASON_USER_REMOVED,
                    now,
                )
                return@forEach
            }
            val operation = dao.getOperation(item.operationId)
            val terminalState = if (operation?.cancelRequested == true) {
                LowQualityRedownloadItemState.CANCELLED
            } else {
                LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
                    currentState = item.stateValue,
                    downloadStatus = download?.status,
                )
            }
            if (terminalState != null) {
                val reason = when {
                    operation?.cancelRequested == true -> REASON_USER_CANCELLED
                    download == null -> REASON_MISSING_DOWNLOAD
                    terminalState == LowQualityRedownloadItemState.SKIPPED ->
                        DownloadRepository.REASON_SAVED_FOR_LATER
                    terminalState == LowQualityRedownloadItemState.CANCELLED &&
                        item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED &&
                        item.reasonCode.startsWith(
                            DownloadRepository.PENDING_CANCELLATION_TOKEN_PREFIX
                        ) -> REASON_USER_CANCELLED
                    else -> ""
                }
                val changed = if (terminalState == LowQualityRedownloadItemState.CANCELLED) {
                    dao.markCancelledByDownloadId(id, reason, now)
                } else {
                    dao.setItemStateByDownloadId(id, terminalState.name, reason, now)
                }
                if (changed == 0 && item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED) {
                    dao.markCancelledByDownloadId(id, REASON_USER_CANCELLED, now)
                }
            }
        }
        finalizeIfReadyLocked(operationId, now)
        downloads
    }

    suspend fun progress(operationId: String): LowQualityRedownloadProgress? {
        val operation = dao.getOperation(operationId) ?: return null
        return LowQualityRedownloadProgress.from(
            operation,
            dao.getItems(operationId),
            dao.getLiveCounts(operationId)
        )
    }

    suspend fun finalizeIfReady(operationId: String): LowQualityRedownloadOperationState? =
        database.withTransaction {
            finalizeIfReadyLocked(operationId, System.currentTimeMillis())
        }

    /**
     * Re-derives one linked child from durable Download/operation state.  This
     * is the same convergence rule used by startup reconciliation, exposed for
     * the live-process recovery debt created when the Download terminal write
     * commits but the independent ledger write fails.
     */
    suspend fun reconcileDownload(downloadId: Long): String? = database.withTransaction {
        val item = dao.getItemByDownloadId(downloadId) ?: return@withTransaction null
        val operation = dao.getOperation(item.operationId) ?: return@withTransaction null
        val download = database.downloadDao.getNullableDownloadById(downloadId)
        val now = System.currentTimeMillis()
        if (operation.cancelRequested || item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED) {
            dao.markCancelledByDownloadId(downloadId, REASON_USER_CANCELLED, now)
        } else if (operation.stateValue.isTerminal && item.stateValue.isTerminal) {
            return@withTransaction item.operationId
        } else {
            LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
                currentState = item.stateValue,
                downloadStatus = download?.status,
            )?.let { state ->
                if (state == LowQualityRedownloadItemState.CANCELLED) {
                    dao.markCancelledByDownloadId(downloadId, REASON_USER_CANCELLED, now)
                } else {
                    dao.setItemStateByDownloadId(downloadId, state.name, "", now)
                }
            }
        }
        finalizeIfReadyLocked(item.operationId, now)
        item.operationId
    }

    private suspend fun finalizeIfReadyLocked(
        operationId: String,
        now: Long
    ): LowQualityRedownloadOperationState? {
        val operation = dao.getOperation(operationId) ?: return null
        val items = dao.getItems(operationId)
        val finalState = LowQualityRedownloadCompletionPolicy.terminalState(operation, items)
            ?: return null
        if (operation.stateValue.isTerminal) return finalState
        dao.finishOperation(operationId, finalState.name, "", now)
        return finalState
    }

    companion object {
        const val REASON_NO_CANDIDATES = "NO_CANDIDATES"
        const val REASON_NO_CANDIDATES_WITH_FAILURES = "NO_CANDIDATES_WITH_FAILURES"
        const val REASON_SCAN_FAILED = "SCAN_FAILED"
        const val REASON_COORDINATOR_FAILURE = "COORDINATOR_FAILURE"
        const val REASON_USER_CANCELLED = "USER_CANCELLED"
        const val REASON_MISSING_DOWNLOAD = "MISSING_DOWNLOAD_RECORD"
    }
}
