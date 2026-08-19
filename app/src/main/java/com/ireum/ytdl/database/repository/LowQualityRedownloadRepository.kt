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
        reason: String = ""
    ): String? = database.withTransaction {
        val item = dao.getItemByDownloadId(downloadId) ?: return@withTransaction null
        val operation = dao.getOperation(item.operationId) ?: return@withTransaction null
        if (operation.stateValue.isTerminal) return@withTransaction null
        val now = System.currentTimeMillis()
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

    internal suspend fun completePersistedCancellation(operationId: String): List<Long> =
        database.withTransaction {
            val operation = dao.getOperation(operationId) ?: return@withTransaction emptyList()
            if (operation.stateValue.isTerminal || !operation.cancelRequested) {
                return@withTransaction emptyList()
            }
            val now = System.currentTimeMillis()
            val linkedDownloadIds = dao.getNonterminalDownloadIds(operationId)
            if (linkedDownloadIds.isNotEmpty()) {
                database.downloadDao.cancelLinkedDownloads(linkedDownloadIds)
            }
            dao.terminalizeNonterminalItems(
                operationId,
                LowQualityRedownloadItemState.CANCELLED.name,
                REASON_USER_CANCELLED,
                now
            )
            check(
                dao.finishOperation(
                    operationId,
                    LowQualityRedownloadOperationState.CANCELLED.name,
                    REASON_USER_CANCELLED,
                    now
                ) == 1
            ) { "Low-quality cancellation lost operation ownership" }
            linkedDownloadIds
        }

    suspend fun failCoordinator(
        operationId: String,
        reason: String = REASON_COORDINATOR_FAILURE
    ): List<Long> = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction emptyList()
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
            database.downloadDao.cancelLinkedDownloads(linkedDownloadIds)
        }
        dao.terminalizeNonterminalItems(
            operationId,
            itemState.name,
            terminalReason,
            now
        )
        check(
            dao.finishOperation(
                operationId,
                terminalState.name,
                terminalReason,
                now
            ) == 1
        ) { "Low-quality coordinator failure lost operation ownership" }
        linkedDownloadIds
    }

    suspend fun markLinkedCancelled(operationId: String, downloadIds: List<Long>) {
        downloadIds.forEach { id ->
            dao.setItemStateByDownloadId(
                id,
                LowQualityRedownloadItemState.CANCELLED.name,
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
            val terminalState = LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
                currentState = item.stateValue,
                downloadStatus = download?.status,
            )
            if (terminalState != null) {
                val reason = when {
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
                dao.setItemStateByDownloadId(
                    id,
                    terminalState.name,
                    reason,
                    now
                )
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
