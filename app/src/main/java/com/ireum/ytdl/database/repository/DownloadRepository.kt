package com.ireum.ytdl.database.repository

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.text.format.DateFormat
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.App
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.DownloadDao
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.DownloadItemConfigureMultiple
import com.ireum.ytdl.database.models.DownloadItemSimple
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.util.Extensions.toListString
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.LowQualityRedownloadCompletionPolicy
import com.ireum.ytdl.util.LowQualityRedownloadLinkedDownloadPolicy
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.work.AlarmScheduler
import com.ireum.ytdl.work.DownloadCancellationRegistry
import com.ireum.ytdl.work.DownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap


class DownloadExecutionOwnershipLostException(
    val downloadId: Long,
    val expectedExecutionId: String,
    val actualExecutionId: String?,
) : IllegalStateException(
    "Download execution ownership lost for download $downloadId"
)

class DownloadRepository(private val database: DBManager) {
    private val downloadDao: DownloadDao = database.downloadDao
    val allDownloads : Pager<Int, DownloadItem> = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 1),
        pagingSourceFactory = {downloadDao.getAllDownloads()}
    )
    val activeDownloads : Flow<List<DownloadItem>> = downloadDao.getActiveDownloads().distinctUntilChanged()
    val activePausedDownloads : Flow<List<DownloadItem>> = downloadDao.getActiveAndPausedDownloads().distinctUntilChanged()
    val pausedDownloads : Flow<List<DownloadItem>> = downloadDao.getPausedDownloads().distinctUntilChanged()
    val processingDownloads : Flow<List<DownloadItemConfigureMultiple>> = downloadDao.getProcessingDownloads().distinctUntilChanged()
    val queuedDownloads : Pager<Int, DownloadItemSimple> = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 1),
        pagingSourceFactory = {downloadDao.getQueuedDownloads()}
    )
    val cancelledDownloads : Pager<Int, DownloadItemSimple> = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 1),
        pagingSourceFactory = {downloadDao.getCancelledDownloads()}
    )
    val erroredDownloads : Pager<Int, DownloadItemSimple> = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 1),
        pagingSourceFactory = {downloadDao.getErroredDownloads()}
    )
    val savedDownloads : Pager<Int, DownloadItemSimple> = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 1),
        pagingSourceFactory = {downloadDao.getSavedDownloads()}
    )
    val scheduledDownloads: Pager<Int, DownloadItemSimple> = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 1),
        pagingSourceFactory = {downloadDao.getScheduledDownloads()}
    )

    val activeDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Active, Status.PostProcessing).toListString())
    val activePausedDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Active, Status.PostProcessing, Status.Paused).toListString())
    val queuedDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(
        listOf(Status.Queued, Status.WaitingForMembership).toListString()
    )
    val runnableQueuedDownloadsCount : Flow<Int> =
        downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Queued).toListString())
    val pausedDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Paused).toListString())
    val cancelledDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Cancelled).toListString())
    val erroredDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Error).toListString())
    val savedDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Saved).toListString())
    val scheduledDownloadsCount : Flow<Int> = downloadDao.getDownloadsCountByStatusFlow(listOf(Status.Scheduled).toListString())

    enum class Status {
        Active, PostProcessing, Paused, Queued, WaitingForMembership, Error, Cancelled, Saved, Processing, Scheduled, Duplicate
    }

    data class UndoableCancellation(
        val pendingToken: String? = null,
        val affectedOperationIds: Set<String> = emptySet()
    )

    data class DownloadUndoToken(val value: String)

    data class DownloadUndoHandle(
        val token: DownloadUndoToken,
        val item: DownloadItem,
        val affectedOperationIds: Set<String> = emptySet(),
    )

    data class DownloadRemovalSnapshot(
        val download: DownloadItem,
        val barrier: HistoryReplacementBarrier? = null,
        val linkedItem: LowQualityRedownloadItem? = null,
        val pendingUndoToken: String? = null,
    )

    private data class DownloadRemovalResult(
        val download: DownloadItem,
        val affectedOperationIds: Set<String>,
        val snapshot: DownloadRemovalSnapshot?,
    )

    private data class PersistedHistoryRefusal(
        val issueCode: String,
        val issueStage: String,
    )

    private val pendingRemovalSnapshots = ConcurrentHashMap<String, DownloadRemovalSnapshot>()

    data class PendingCancellationResolution(
        val restoredStatus: Status? = null,
        val affectedOperationIds: Set<String> = emptySet()
    )

    data class SavedDownloadResult(
        val downloadId: Long,
        val affectedOperationIds: Set<String> = emptySet()
    )

    data class HistoryReplacementConvergenceResult(
        val downloadUpdated: Boolean,
        val affectedOperationIds: Set<String> = emptySet(),
    )

    suspend fun insert(item: DownloadItem) : Long {
        return downloadDao.insert(item)
    }

    suspend fun insertRestoredDownload(
        item: DownloadItem,
        barrier: HistoryReplacementBarrier?,
    ): Long = database.withTransaction {
        val restoredItem = barrier?.let {
            item.copy(
                status = Status.Error.name,
                lastIssueCode = it.issueCode,
                lastIssueStage = it.issueStage,
            )
        } ?: item
        val restoredId = downloadDao.insert(restoredItem.copy(id = 0L, executionId = ""))
        barrier?.let { source ->
            val restoredBarrier = source.copy(downloadId = restoredId)
            database.historyReplacementBarrierDao.insertIfAbsent(restoredBarrier)
            check(
                database.historyReplacementBarrierDao.getByDownloadId(restoredId) == restoredBarrier
            ) {
                "History replacement refusal was not restored for download $restoredId"
            }
        }
        restoredId
    }

    suspend fun insertAll(items: List<DownloadItem>) : List<Long> {
        return downloadDao.insertAll(items)
    }

    suspend fun deleteAll(): Set<String> =
        deleteKnownUserRemoval(downloadDao.getAllDownloadsList())

    suspend fun delete(id: Long): Set<String> = removeDownload(id)

    /**
     * Deletes a Download and returns only after the matching Undo snapshot has
     * been registered.  UI Undo actions must retain this token instead of
     * replaying an old DownloadItem by numeric id.
     */
    suspend fun deleteForUndo(id: Long): DownloadUndoHandle? {
        val token = DownloadUndoToken("$PENDING_REMOVAL_TOKEN_PREFIX${UUID.randomUUID()}")
        registerLivePendingRemovalToken(token.value)
        try {
            val result = removeDownloadWithSnapshot(id, token.value)
            val snapshot = result?.snapshot
            if (snapshot == null) {
                unregisterLivePendingRemovalToken(token.value)
                return null
            }
            pendingRemovalSnapshots[token.value] = snapshot
            return DownloadUndoHandle(
                token = token,
                item = snapshot.download,
                affectedOperationIds = result.affectedOperationIds,
            )
        } catch (error: Exception) {
            unregisterLivePendingRemovalToken(token.value)
            throw error
        }
    }

    private suspend fun removeDownload(id: Long): Set<String> {
        val result = removeDownloadTransaction(id, captureUndo = false) ?: return emptySet()
        deleteCache(listOf(result.download))
        return result.affectedOperationIds
    }

    private suspend fun removeDownloadWithSnapshot(
        id: Long,
        pendingUndoToken: String,
    ): DownloadRemovalResult? {
        val result = removeDownloadTransaction(
            id = id,
            captureUndo = true,
            pendingUndoToken = pendingUndoToken,
        ) ?: return null
        deleteCache(listOf(result.download))
        return result
    }

    private suspend fun removeDownloadTransaction(
        id: Long,
        captureUndo: Boolean,
        pendingUndoToken: String? = null,
    ): DownloadRemovalResult? = database.withTransaction {
        val item = downloadDao.getNullableDownloadById(id) ?: return@withTransaction null
        val barrier = database.historyReplacementBarrierDao.getByDownloadId(id)
        val linkedItem = database.lowQualityRedownloadDao.getItemByDownloadId(id)
        val ledgerDao = database.lowQualityRedownloadDao
        val pendingLinkedItem = if (
            captureUndo &&
                pendingUndoToken != null &&
                linkedItem != null &&
                !linkedItem.stateValue.isTerminal &&
                linkedItem.stateValue in setOf(
                    LowQualityRedownloadItemState.QUEUED,
                    LowQualityRedownloadItemState.ACTIVE,
                    LowQualityRedownloadItemState.WAITING,
                ) &&
                ledgerDao.markPendingUserRemoval(
                    downloadId = id,
                    token = pendingUndoToken,
                    updatedAt = System.currentTimeMillis(),
                ) == 1
        ) {
            pendingUndoToken
        } else {
            null
        }
        val affectedOperationIds = if (pendingLinkedItem != null) {
            setOf(linkedItem!!.operationId)
        } else {
            terminalizeLinkedChildren(
                downloadIds = listOf(id),
                reason = REASON_USER_REMOVED,
                now = System.currentTimeMillis(),
            )
        }
        val snapshot = if (captureUndo) {
            DownloadRemovalSnapshot(
                download = item,
                barrier = barrier,
                linkedItem = linkedItem,
                pendingUndoToken = pendingLinkedItem,
            )
        } else {
            null
        }
        database.historyReplacementBarrierDao.deleteForDownloadIds(listOf(id))
        downloadDao.delete(id)
        DownloadRemovalResult(item, affectedOperationIds, snapshot)
    }

    private fun deleteCache(items: List<DownloadItem>) {
        val cacheDir = FileUtil.getCachePath(App.instance)
        items.forEach {
           runCatching { File(cacheDir, it.id.toString()).deleteRecursively() }
        }
    }

    suspend fun update(item: DownloadItem) : Long {
        return if (item.id <= 0L) downloadDao.insert(item) else downloadDao.update(item)
    }

    suspend fun updateAll(list: List<DownloadItem>) : List<DownloadItem> {
        return downloadDao.updateAll(list)
    }

    suspend fun updateWithoutUpsert(item: DownloadItem){
        kotlin.runCatching { downloadDao.updateWithoutUpsert(item) }
    }

    suspend fun restoreUndo(token: DownloadUndoToken): Long? {
        val snapshot = pendingRemovalSnapshots.remove(token.value) ?: return null
        return try {
            restoreRemovalSnapshot(snapshot).also {
                unregisterLivePendingRemovalToken(token.value)
            }
        } catch (error: Exception) {
            pendingRemovalSnapshots[token.value] = snapshot
            throw error
        }
    }

    /** Commits a pending Undo removal when its Snackbar is dismissed. */
    suspend fun commitUndo(token: DownloadUndoToken): Set<String> {
        val snapshot = pendingRemovalSnapshots.remove(token.value) ?: return emptySet()
        return try {
            commitRemovalSnapshot(snapshot).also {
                unregisterLivePendingRemovalToken(token.value)
            }
        } catch (error: Exception) {
            pendingRemovalSnapshots[token.value] = snapshot
            throw error
        }
    }

    private suspend fun commitRemovalSnapshot(
        snapshot: DownloadRemovalSnapshot,
    ): Set<String> = database.withTransaction {
        val linkedSnapshot = snapshot.linkedItem ?: return@withTransaction emptySet()
        val pendingToken = snapshot.pendingUndoToken ?: return@withTransaction emptySet()
        val ledgerDao = database.lowQualityRedownloadDao
        if (
            ledgerDao.commitUndoableLinkedItem(
                downloadId = snapshot.download.id,
                expectedToken = pendingToken,
                reason = REASON_USER_REMOVED,
                updatedAt = System.currentTimeMillis(),
            ) != 1
        ) {
            return@withTransaction emptySet()
        }
        val operation = ledgerDao.getOperation(linkedSnapshot.operationId)
        if (operation != null && !operation.stateValue.isTerminal) {
            LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                ledgerDao.getItems(linkedSnapshot.operationId),
            )?.let { finalState ->
                check(
                    ledgerDao.finishOperation(
                        linkedSnapshot.operationId,
                        finalState.name,
                        REASON_USER_REMOVED,
                        System.currentTimeMillis(),
                    ) == 1
                ) { "Undoable low-quality removal lost operation ownership" }
            }
        }
        setOf(linkedSnapshot.operationId)
    }

    private suspend fun restoreRemovalSnapshot(
        snapshot: DownloadRemovalSnapshot,
    ): Long = database.withTransaction {
        if (snapshot.barrier == null) {
            val existing = downloadDao.getNullableDownloadById(snapshot.download.id)
            val restoredItem = snapshot.download.copy(
                id = if (existing == null) snapshot.download.id else 0L,
                status = if (
                    snapshot.download.status in setOf(Status.Active.name, Status.PostProcessing.name)
                ) {
                    Status.Queued.name
                } else {
                    snapshot.download.status
                },
                executionId = "",
            )
            val restoredId = downloadDao.insert(restoredItem)
            snapshot.linkedItem?.let { linkedSnapshot ->
                val ledgerDao = database.lowQualityRedownloadDao
                ledgerDao.rebindDownloadId(
                    oldDownloadId = snapshot.download.id,
                    newDownloadId = restoredId,
                    updatedAt = System.currentTimeMillis(),
                )
                val operation = ledgerDao.getOperation(linkedSnapshot.operationId)
                if (snapshot.pendingUndoToken != null &&
                    (operation == null || operation.stateValue.isTerminal)
                ) {
                    // The parent finished independently while Undo was open.
                    // Restore the Download only; close the pending child without
                    // replaying or reopening the stale parent snapshot.
                    val committed = ledgerDao.commitUndoableLinkedItem(
                        downloadId = restoredId,
                        expectedToken = snapshot.pendingUndoToken,
                        reason = REASON_USER_REMOVED,
                        updatedAt = System.currentTimeMillis(),
                    )
                    if (committed != 1) {
                        check(
                            ledgerDao.getItemByDownloadId(restoredId)
                                ?.stateValue
                                ?.isTerminal == true
                        ) {
                            "Undoable low-quality child lost terminal parent ownership $restoredId"
                        }
                    }
                } else if (
                    operation != null &&
                        !operation.stateValue.isTerminal &&
                        snapshot.pendingUndoToken != null
                ) {
                    val restoredState = when (linkedSnapshot.stateValue) {
                        LowQualityRedownloadItemState.ACTIVE,
                        LowQualityRedownloadItemState.CANCELLATION_REQUESTED ->
                            LowQualityRedownloadItemState.QUEUED
                        else -> linkedSnapshot.stateValue
                    }
                    val restored = ledgerDao.restoreUndoableLinkedItem(
                        downloadId = restoredId,
                        expectedToken = snapshot.pendingUndoToken,
                        state = restoredState.name,
                        reason = linkedSnapshot.reasonCode,
                        updatedAt = System.currentTimeMillis(),
                    )
                    if (restored == 1) {
                        val finalState = LowQualityRedownloadCompletionPolicy.terminalState(
                            operation,
                            ledgerDao.getItems(linkedSnapshot.operationId),
                        )
                        if (finalState != null && finalState.name != operation.state) {
                            ledgerDao.finishOperation(
                                linkedSnapshot.operationId,
                                finalState.name,
                                "",
                                System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
            return@withTransaction restoredId
        }

        val restoredItem = snapshot.download.copy(
            id = 0L,
            status = Status.Error.name,
            lastIssueCode = snapshot.barrier.issueCode,
            lastIssueStage = snapshot.barrier.issueStage,
            executionId = "",
        )
        val restoredId = downloadDao.insert(restoredItem)
        val restoredBarrier = snapshot.barrier.copy(downloadId = restoredId)
        database.historyReplacementBarrierDao.insertIfAbsent(restoredBarrier)
        check(
            database.historyReplacementBarrierDao.getByDownloadId(restoredId) == restoredBarrier
        ) {
            "History replacement refusal was not restored for download $restoredId"
        }
        snapshot.linkedItem?.let {
            database.lowQualityRedownloadDao.rebindDownloadId(
                oldDownloadId = snapshot.download.id,
                newDownloadId = restoredId,
                updatedAt = System.currentTimeMillis(),
            )
        }
        convergeLinkedHistoryRefusalLocked(
            restoredId,
            PersistedHistoryRefusal(restoredBarrier.issueCode, restoredBarrier.issueStage),
        )
        // Do not replay stale child/parent snapshots: sibling progress,
        // cancellation, and a terminal parent may have changed while Undo was
        // available.
        restoredId
    }

    private suspend fun projectHistoryRefusalsForBackup(
        items: List<DownloadItem>,
    ): List<DownloadItem> = database.withTransaction {
        if (items.isEmpty()) return@withTransaction emptyList()
        val barriers = database.historyReplacementBarrierDao
            .getByDownloadIds(items.map(DownloadItem::id))
            .associateBy(HistoryReplacementBarrier::downloadId)
        items.map { item ->
            val barrier = barriers[item.id] ?: return@map item.copy()
            check(
                HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                    barrier.issueCode
                )
            ) {
                "Invalid History replacement refusal barrier for backup ${item.id}"
            }
            item.copy(
                lastIssueCode = barrier.issueCode,
                lastIssueStage = barrier.issueStage,
            )
        }
    }

    suspend fun getQueuedDownloadsForBackup(): List<DownloadItem> =
        projectHistoryRefusalsForBackup(downloadDao.getQueuedDownloadsForBackupList())

    suspend fun getScheduledDownloadsForBackup(): List<DownloadItem> =
        projectHistoryRefusalsForBackup(downloadDao.getScheduledDownloadsForBackupList())

    suspend fun getCancelledDownloadsForBackup(): List<DownloadItem> =
        projectHistoryRefusalsForBackup(getCancelledDownloads())

    suspend fun getErroredDownloadsForBackup(): List<DownloadItem> =
        projectHistoryRefusalsForBackup(getErroredDownloads())

    suspend fun getSavedDownloadsForBackup(): List<DownloadItem> =
        projectHistoryRefusalsForBackup(getSavedDownloads())

    private suspend fun persistedHistoryRefusalLocked(
        downloadId: Long,
    ): PersistedHistoryRefusal? {
        val barrier = database.historyReplacementBarrierDao.getByDownloadId(downloadId)
        val current = downloadDao.getNullableDownloadById(downloadId)
        val issueCode = barrier?.issueCode ?: current?.lastIssueCode.orEmpty()
        val issue = HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(issueCode)
            ?: return null
        return PersistedHistoryRefusal(issue.code.name, barrier?.issueStage ?: current?.lastIssueStage.orEmpty())
    }

    private fun historyRefusalTerminalState(
        refusal: PersistedHistoryRefusal,
        operation: LowQualityRedownloadOperation,
        items: List<LowQualityRedownloadItem>,
    ): LowQualityRedownloadOperationState? {
        val selected = items.filter(LowQualityRedownloadItem::selected)
        if (selected.any { !it.stateValue.isTerminal }) return null
        val hasFailed = selected.any { it.stateValue == LowQualityRedownloadItemState.FAILED }
        val hasCancelled = selected.any { it.stateValue == LowQualityRedownloadItemState.CANCELLED }
        val hasSuccessfulOrSkipped = selected.any {
            it.stateValue == LowQualityRedownloadItemState.SUCCEEDED ||
                it.stateValue == LowQualityRedownloadItemState.SKIPPED
        }
        val targetDeleted = refusal.issueCode == "HISTORY_TARGET_DELETED"
        return if (targetDeleted) {
            when {
                hasFailed && hasSuccessfulOrSkipped -> LowQualityRedownloadOperationState.PARTIAL_FAILURE
                hasFailed -> LowQualityRedownloadOperationState.FAILED
                hasCancelled && hasSuccessfulOrSkipped -> LowQualityRedownloadOperationState.PARTIAL_FAILURE
                hasCancelled -> LowQualityRedownloadOperationState.PARTIAL_FAILURE
                else -> LowQualityRedownloadOperationState.COMPLETED
            }
        } else {
            when {
                hasFailed && hasSuccessfulOrSkipped -> LowQualityRedownloadOperationState.PARTIAL_FAILURE
                hasFailed -> LowQualityRedownloadOperationState.FAILED
                hasCancelled && hasSuccessfulOrSkipped -> LowQualityRedownloadOperationState.PARTIAL_FAILURE
                hasCancelled -> LowQualityRedownloadOperationState.FAILED
                else -> LowQualityRedownloadOperationState.FAILED
            }
        }
    }

    private suspend fun convergeLinkedHistoryRefusalLocked(
        downloadId: Long,
        refusal: PersistedHistoryRefusal,
    ): Set<String> {
        val disposition = HistoryReplacementDiagnostic.refusalLedgerDisposition(refusal.issueCode)
            ?: return emptySet()
        val ledgerDao = database.lowQualityRedownloadDao
        val ledgerItem = ledgerDao.getItemByDownloadId(downloadId) ?: return emptySet()
        check(ledgerItem.stateValue != LowQualityRedownloadItemState.SUCCEEDED) {
            "History refusal conflicts with a successful low-quality child $downloadId"
        }
        check(
            ledgerDao.setHistoryReplacementRefusalItemState(
                downloadId = downloadId,
                state = disposition.itemState.name,
                reason = disposition.reasonCode,
                updatedAt = System.currentTimeMillis(),
            ) == 1
        ) {
            "History refusal could not converge low-quality child $downloadId"
        }
        val operation = ledgerDao.getOperation(ledgerItem.operationId)
            ?: error("Missing low-quality operation for linked download $downloadId")
        val finalState = historyRefusalTerminalState(
            refusal = refusal,
            operation = operation,
            items = ledgerDao.getItems(ledgerItem.operationId),
        ) ?: return setOf(ledgerItem.operationId)
        if (operation.stateValue.isTerminal) return setOf(ledgerItem.operationId)
        if (operation.state != finalState.name) {
            check(
                ledgerDao.setTerminalOperationState(
                    operationId = ledgerItem.operationId,
                    expectedState = operation.state,
                    state = finalState.name,
                    reason = disposition.reasonCode,
                    completedAt = System.currentTimeMillis(),
                ) == 1
            ) {
                "History refusal could not converge low-quality operation ${ledgerItem.operationId}"
            }
        }
        return setOf(ledgerItem.operationId)
    }

    /**
     * Moves an abandoned or user-reclassified refused replacement into a
     * non-runnable diagnostic state without discarding its exact refusal.
     * Paused/Cancelled are retained only for an explicit user stop; all other
     * ordinary statuses converge to Error.
     */
    private suspend fun convergeHistoryReplacementRefusalLocked(
        id: Long,
        expectedExecutionId: String = "",
        forceError: Boolean = true,
    ): HistoryReplacementConvergenceResult {
        val current = downloadDao.getNullableDownloadById(id)
            ?: return HistoryReplacementConvergenceResult(downloadUpdated = false)
        val refusal = persistedHistoryRefusalLocked(id)
            ?: return HistoryReplacementConvergenceResult(downloadUpdated = false)
        val nextStatus = if (
            !forceError && current.status in setOf(Status.Paused.name, Status.Cancelled.name)
        ) {
            current.status
        } else {
            Status.Error.name
        }
        if (
            downloadDao.convergeHistoryReplacementRefusal(
                id = id,
                expectedStatus = current.status,
                expectedExecutionId = expectedExecutionId,
                nextStatus = nextStatus,
                issueCode = refusal.issueCode,
                issueStage = refusal.issueStage,
            ) != 1
        ) {
            return HistoryReplacementConvergenceResult(downloadUpdated = false)
        }
        return HistoryReplacementConvergenceResult(
            downloadUpdated = true,
            affectedOperationIds = convergeLinkedHistoryRefusalLocked(id, refusal),
        )
    }

    suspend fun convergeHistoryReplacementRefusal(
        id: Long,
        expectedExecutionId: String = "",
        forceError: Boolean = true,
    ): HistoryReplacementConvergenceResult = database.withTransaction {
        convergeHistoryReplacementRefusalInCurrentTransaction(id, expectedExecutionId, forceError)
    }

    /** Must be called while the caller already owns the Room transaction. */
    internal suspend fun convergeHistoryReplacementRefusalInCurrentTransaction(
        id: Long,
        expectedExecutionId: String = "",
        forceError: Boolean = true,
    ): HistoryReplacementConvergenceResult =
        convergeHistoryReplacementRefusalLocked(id, expectedExecutionId, forceError)


    suspend fun setDownloadStatus(
        id: Long,
        status: Status,
        expectedExecutionId: String? = null,
    ) {
        database.withTransaction {
            val current = downloadDao.getNullableDownloadById(id)
                ?: return@withTransaction
            val expected = expectedExecutionId?.takeIf { it.isNotBlank() }
            if (expected != null && current.executionId != expected) {
                return@withTransaction
            }
            if (
                status in setOf(Status.Paused, Status.Cancelled) &&
                    current.executionId.isNotBlank()
            ) {
                val changed = if (status == Status.Paused) {
                    downloadDao.pauseIfExecutionOwned(id, current.executionId)
                } else {
                    downloadDao.cancelIfExecutionOwned(id, current.executionId)
                }
                if (changed == 1) {
                    DownloadCancellationRegistry.record(
                        id,
                        current.executionId,
                        if (status == Status.Paused) {
                            DownloadCancellationRegistry.Reason.PAUSED
                        } else {
                            DownloadCancellationRegistry.Reason.CANCELLED
                        },
                    )
                    if (persistedHistoryRefusalLocked(id) != null) {
                        convergeHistoryReplacementRefusalLocked(
                            id = id,
                            expectedExecutionId = current.executionId,
                            forceError = false,
                        )
                    }
                }
            } else {
                downloadDao.setStatus(id, status.toString())
                if (
                    status in setOf(Status.Paused, Status.Cancelled) &&
                        persistedHistoryRefusalLocked(id) != null
                ) {
                    convergeHistoryReplacementRefusalLocked(
                        id = id,
                        expectedExecutionId = current.executionId,
                        forceError = false,
                    )
                }
            }
        }
    }

    suspend fun setDownloadStatusMultiple(ids: List<Long>, status: Status) {
        if (status != Status.Paused && status != Status.Cancelled) {
            downloadDao.setStatusMultiple(ids, status.toString())
            return
        }
        database.withTransaction {
            ids.distinct().forEach { id ->
                val current = downloadDao.getNullableDownloadById(id) ?: return@forEach
                if (current.executionId.isBlank()) {
                    downloadDao.setStatus(id, status.toString())
                    if (persistedHistoryRefusalLocked(id) != null) {
                        convergeHistoryReplacementRefusalLocked(
                            id = id,
                            expectedExecutionId = current.executionId,
                            forceError = false,
                        )
                    }
                } else {
                    val changed = if (status == Status.Paused) {
                        downloadDao.pauseIfExecutionOwned(id, current.executionId)
                    } else {
                        downloadDao.cancelIfExecutionOwned(id, current.executionId)
                    }
                    if (changed != 1) return@forEach
                    DownloadCancellationRegistry.record(
                        id,
                        current.executionId,
                        if (status == Status.Paused) {
                            DownloadCancellationRegistry.Reason.PAUSED
                        } else {
                            DownloadCancellationRegistry.Reason.CANCELLED
                        },
                    )
                    if (persistedHistoryRefusalLocked(id) != null) {
                        convergeHistoryReplacementRefusalLocked(
                            id = id,
                            expectedExecutionId = current.executionId,
                            forceError = false,
                        )
                    }
                }
            }
        }
    }

    suspend fun saveForLater(item: DownloadItem): SavedDownloadResult = database.withTransaction {
        if (item.id > 0L && persistedHistoryRefusalLocked(item.id) != null) {
            return@withTransaction SavedDownloadResult(
                downloadId = item.id,
                affectedOperationIds = convergeHistoryReplacementRefusalLocked(
                    id = item.id,
                    expectedExecutionId = downloadDao.getNullableDownloadById(item.id)
                        ?.executionId.orEmpty(),
                    forceError = true,
                ).affectedOperationIds,
            )
        }
        item.status = Status.Saved.name
        val upsertResult = if (item.id <= 0L) downloadDao.insert(item) else downloadDao.update(item)
        val downloadId = item.id.takeIf { it > 0L } ?: upsertResult
        SavedDownloadResult(
            downloadId = downloadId,
            affectedOperationIds = markLinkedDownloadSaved(downloadId, System.currentTimeMillis())
        )
    }

    suspend fun moveToSaved(id: Long): Set<String> = database.withTransaction {
        val item = downloadDao.getNullableDownloadById(id) ?: return@withTransaction emptySet()
        if (persistedHistoryRefusalLocked(id) != null) {
            return@withTransaction convergeHistoryReplacementRefusalLocked(
                id = id,
                expectedExecutionId = item.executionId,
                forceError = true,
            ).affectedOperationIds
        }
        downloadDao.setStatus(item.id, Status.Saved.name)
        markLinkedDownloadSaved(item.id, System.currentTimeMillis())
    }

    private suspend fun markLinkedDownloadSaved(downloadId: Long, now: Long): Set<String> {
        val ledgerDao = database.lowQualityRedownloadDao
        val ledgerItem = ledgerDao.getItemByDownloadId(downloadId) ?: return emptySet()
        val reconciledState = LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
            currentState = ledgerItem.stateValue,
            downloadStatus = Status.Saved.name,
        )
        if (reconciledState != LowQualityRedownloadItemState.SKIPPED) return emptySet()
        if (
            ledgerDao.setItemStateByDownloadId(
                downloadId,
                reconciledState.name,
                REASON_SAVED_FOR_LATER,
                now
            ) != 1
        ) {
            return emptySet()
        }
        val operation = ledgerDao.getOperation(ledgerItem.operationId)
        if (operation != null && !operation.stateValue.isTerminal) {
            LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                ledgerDao.getItems(ledgerItem.operationId)
            )?.let { finalState ->
                ledgerDao.finishOperation(ledgerItem.operationId, finalState.name, "", now)
            }
        }
        return setOf(ledgerItem.operationId)
    }

    fun getItemByID(id: Long) : DownloadItem {
        return downloadDao.getDownloadById(id)
    }

    fun getAllItemsByIDs(ids : List<Long>) : List<DownloadItem>{
        return downloadDao.getDownloadsByIds(ids)
    }

    fun getActiveDownloads() : List<DownloadItem> {
        return downloadDao.getActiveDownloadsList()
    }

    fun getProcessingDownloadsByUrl(url: String) : List<DownloadItem> {
        return downloadDao.getProcessingDownloadsByUrl(url)
    }

    suspend fun deleteProcessingByUrl(url: String) {
        downloadDao.deleteProcessingByUrl(url)
    }

    fun getAllProcessingDownloads() : List<DownloadItem> {
        return downloadDao.getProcessingDownloadsList()
    }

    suspend fun reverseProcessingDownloads() {
        downloadDao.reverseProcessingDownloads()
    }

    fun getActiveAndQueuedDownloads() : List<DownloadItem> {
        return downloadDao.getActiveAndQueuedDownloadsList()
    }

    fun getPendingObservationDownloads() : List<DownloadItem> {
        return downloadDao.getPendingObservationDownloadsList()
    }

    fun getMembershipWaitingDownloads(sourceId: Long): List<DownloadItem> {
        return downloadDao.getMembershipWaitingDownloads(sourceId)
    }

    fun getMembershipWaitingDownloads(): List<DownloadItem> {
        return downloadDao.getMembershipWaitingDownloads()
    }

    fun getActiveAndQueuedDownloadIDs() : List<Long> {
        return downloadDao.getActiveAndQueuedDownloadIDs()
    }

    fun getQueuedDownloads() : List<DownloadItem> {
        return downloadDao.getQueuedDownloadsList()
    }

    fun getScheduledDownloads() : List<DownloadItem> {
        return downloadDao.getScheduledDownloadsList()
    }

    fun getCancelledDownloads() : List<DownloadItem> {
        return downloadDao.getCancelledDownloadsList()
    }

    fun getErroredDownloads() : List<DownloadItem> {
        return downloadDao.getErroredDownloadsList()
    }

    fun getSavedDownloads() : List<DownloadItem> {
        return downloadDao.getSavedDownloadsList()
    }

    fun getScheduledDownloadIDs() : List<Long> {
        return downloadDao.getScheduledDownloadIDs()
    }

    suspend fun deleteCancelled(): Set<String> =
        deleteKnownUserRemoval(getCancelledDownloads())

    fun getActiveDownloadsCount() : Int {
        return downloadDao.getDownloadsCountByStatus(listOf(Status.Active, Status.PostProcessing).toListString())
    }

    suspend fun deleteScheduled(): Set<String> =
        deleteKnownUserRemoval(getScheduledDownloads())

    suspend fun deleteErrored(){
        val errored = getErroredDownloads()
        database.withTransaction {
            database.historyReplacementBarrierDao.deleteForDownloadIds(
                errored.map(DownloadItem::id)
            )
            downloadDao.deleteErrored()
        }
        deleteCache(errored)
    }

    suspend fun deleteQueued(): Set<String> =
        deleteKnownUserRemoval(getQueuedDownloads())

    suspend fun deleteSaved(){
        val saved = getSavedDownloads()
        database.withTransaction {
            database.historyReplacementBarrierDao.deleteForDownloadIds(
                saved.map(DownloadItem::id)
            )
            downloadDao.deleteSaved()
        }
    }

    suspend fun deleteProcessing(){
        val processing = getAllProcessingDownloads()
        database.withTransaction {
            database.historyReplacementBarrierDao.deleteForDownloadIds(
                processing.map(DownloadItem::id)
            )
            downloadDao.deleteProcessing()
        }
    }

    suspend fun deleteWithDuplicateStatus() {
        downloadDao.deleteWithDuplicateStatus()
    }

    suspend fun deleteAllWithIDs(ids: List<Long>): Set<String> =
        deleteKnownUserRemoval(downloadDao.getDownloadsByIdsSuspend(ids.distinct()))

    suspend fun cancelByUser(
        id: Long,
        expectedExecutionId: String? = null,
    ): Set<String> = database.withTransaction {
        val item = downloadDao.getNullableDownloadById(id) ?: return@withTransaction emptySet()
        val expected = expectedExecutionId?.takeIf { it.isNotBlank() }
        if (expected != null && item.executionId != expected) {
            return@withTransaction emptySet()
        }
        val changed = if (item.executionId.isBlank()) {
            downloadDao.cancelByUser(item.id) == 1
        } else {
            downloadDao.cancelIfExecutionOwned(item.id, item.executionId) == 1
        }
        if (changed) {
            DownloadCancellationRegistry.record(
                item.id,
                item.executionId,
                DownloadCancellationRegistry.Reason.CANCELLED,
            )
        }
        if (!changed && item.status != Status.Cancelled.name) {
            return@withTransaction emptySet()
        }
        if (persistedHistoryRefusalLocked(item.id) != null) {
            return@withTransaction convergeHistoryReplacementRefusalLocked(
                id = item.id,
                expectedExecutionId = downloadDao.getNullableDownloadById(item.id)?.executionId.orEmpty(),
                forceError = false,
            ).affectedOperationIds
        }
        terminalizeLinkedChildren(
            downloadIds = listOf(item.id),
            reason = REASON_USER_CANCELLED,
            now = System.currentTimeMillis()
        )
    }

    suspend fun completeAndDelete(
        id: Long,
        successReason: String = "",
        expectedExecutionId: String = "",
    ): Set<String> = database.withTransaction {
        assertTerminalExecutionOwned(id, expectedExecutionId)
        val ledgerDao = database.lowQualityRedownloadDao
        val ledgerItem = ledgerDao.getItemByDownloadId(id)
        val currentDownload = downloadDao.getNullableDownloadById(id)
            ?: error("Missing download for History replacement completion")
        val qualityMarker = HistoryRedownloadMarker.parse(currentDownload.playlistURL)
        if (qualityMarker?.isQualityReplacement == true) {
            val operation = ledgerItem?.let { ledgerDao.getOperation(it.operationId) }
            check(
                ledgerItem != null &&
                    operation != null &&
                    !ledgerItem.stateValue.isTerminal &&
                    !operation.stateValue.isTerminal
            ) {
                "Orphaned quality replacement cannot complete History successfully"
            }
        }
        check(persistedHistoryRefusalLocked(id) == null) {
            "History refusal cannot be completed as a successful replacement"
        }
        val changedOperationIds = linkedSetOf<String>()
        if (ledgerItem != null) {
            val operation = ledgerDao.getOperation(ledgerItem.operationId)
                ?: error("Missing low-quality operation for linked download")
            check(!ledgerItem.stateValue.isTerminal) {
                "Terminal low-quality child cannot authorize History success"
            }
            check(!operation.stateValue.isTerminal) {
                "Terminal low-quality operation still owns a nonterminal child"
            }
            check(
                ledgerDao.setItemStateByDownloadId(
                    id,
                    LowQualityRedownloadItemState.SUCCEEDED.name,
                    successReason,
                    System.currentTimeMillis()
                ) == 1
            ) {
                "Completed download lost ledger ownership"
            }
            val finalState = LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                ledgerDao.getItems(ledgerItem.operationId)
            )
            if (finalState != null) {
                ledgerDao.finishOperation(
                    ledgerItem.operationId,
                    finalState.name,
                    "",
                    System.currentTimeMillis()
                )
            }
            changedOperationIds += ledgerItem.operationId
        }
        database.historyReplacementBarrierDao.deleteForDownloadIds(listOf(id))
        downloadDao.delete(id)
        changedOperationIds
    }

    suspend fun completeHistoryTargetDeletedAndDelete(
        id: Long,
        expectedExecutionId: String = "",
    ): Set<String> =
        database.withTransaction {
            assertTerminalExecutionOwned(id, expectedExecutionId)
            val ledgerDao = database.lowQualityRedownloadDao
            val ledgerItem = ledgerDao.getItemByDownloadId(id)
            val refusal = persistedHistoryRefusalLocked(id)
            check(
                refusal == null || refusal.issueCode == DownloadIssueCode.HISTORY_TARGET_DELETED.name
            ) {
                "Mismatch refusal cannot be completed as target-deleted"
            }
            val changedOperationIds = linkedSetOf<String>()
            if (ledgerItem != null) {
                val operation = ledgerDao.getOperation(ledgerItem.operationId)
                    ?: error("Missing low-quality operation for linked download")
                if (ledgerItem.stateValue.isTerminal || operation.stateValue.isTerminal) {
                    check(ledgerItem.stateValue == LowQualityRedownloadItemState.SKIPPED) {
                        "Terminal low-quality child cannot be reclassified as target-deleted"
                    }
                } else {
                    val now = System.currentTimeMillis()
                    check(
                        ledgerDao.setItemStateByDownloadId(
                            id,
                            LowQualityRedownloadItemState.SKIPPED.name,
                            REASON_HISTORY_TARGET_DELETED,
                            now
                        ) == 1
                    ) {
                        "History-target deletion lost ledger ownership"
                    }
                    val finalState = LowQualityRedownloadCompletionPolicy.terminalState(
                        operation,
                        ledgerDao.getItems(ledgerItem.operationId)
                    )
                    if (finalState != null) {
                        ledgerDao.finishOperation(
                            ledgerItem.operationId,
                            finalState.name,
                            "",
                            now
                        )
                    }
                }
                changedOperationIds += ledgerItem.operationId
            }
            database.historyReplacementBarrierDao.deleteForDownloadIds(listOf(id))
            downloadDao.delete(id)
            changedOperationIds
        }

    private fun assertTerminalExecutionOwned(
        id: Long,
        expectedExecutionId: String,
    ) {
        if (expectedExecutionId.isBlank()) return
        val current = downloadDao.getNullableDownloadById(id)
        if (
            current == null ||
            current.executionId != expectedExecutionId ||
            current.status !in setOf(Status.Active.name, Status.PostProcessing.name)
        ) {
            throw DownloadExecutionOwnershipLostException(
                downloadId = id,
                expectedExecutionId = expectedExecutionId,
                actualExecutionId = current?.executionId,
            )
        }
    }

    suspend fun beginUndoableCancellation(
        id: Long,
        expectedExecutionId: String? = null,
    ): UndoableCancellation =
        database.withTransaction {
            val item = downloadDao.getNullableDownloadById(id)
                ?: return@withTransaction UndoableCancellation()
            val expected = expectedExecutionId?.takeIf { it.isNotBlank() }
            if (expected != null && item.executionId != expected) {
                return@withTransaction UndoableCancellation()
            }
            val refusal = persistedHistoryRefusalLocked(id)
            if (refusal != null) {
                val changed = if (item.executionId.isBlank()) {
                    downloadDao.cancelByUser(id) == 1
                } else {
                    downloadDao.cancelIfExecutionOwned(id, item.executionId) == 1
                }
                if (changed) {
                    DownloadCancellationRegistry.record(
                        item.id,
                        item.executionId,
                        DownloadCancellationRegistry.Reason.CANCELLED,
                    )
                }
                if (!changed && item.status != Status.Cancelled.name) {
                    return@withTransaction UndoableCancellation()
                }
                return@withTransaction UndoableCancellation(
                    affectedOperationIds = convergeHistoryReplacementRefusalLocked(
                        id = id,
                        expectedExecutionId = downloadDao.getNullableDownloadById(id)
                            ?.executionId.orEmpty(),
                        forceError = false,
                    ).affectedOperationIds
                )
            }
            val ledgerDao = database.lowQualityRedownloadDao
            val ledgerItem = ledgerDao.getItemByDownloadId(id)
            val operation = ledgerItem?.let { ledgerDao.getOperation(it.operationId) }
            val canRemainPending =
                item.status in setOf(Status.Queued.name, Status.WaitingForMembership.name) &&
                    ledgerItem != null &&
                    !ledgerItem.stateValue.isTerminal &&
                    operation != null &&
                    !operation.stateValue.isTerminal &&
                    !operation.cancelRequested

            if (canRemainPending) {
                val token = "$PENDING_CANCELLATION_TOKEN_PREFIX${UUID.randomUUID()}"
                check(downloadDao.cancelByUser(id) == 1) {
                    "Undoable cancellation lost download ownership"
                }
                check(
                    ledgerDao.markPendingUserCancellation(id, token, System.currentTimeMillis()) == 1
                ) {
                    "Undoable cancellation lost ledger ownership"
                }
                return@withTransaction UndoableCancellation(pendingToken = token)
            }

            val changed = if (item.executionId.isBlank()) {
                downloadDao.cancelByUser(id) == 1
            } else {
                downloadDao.cancelIfExecutionOwned(id, item.executionId) == 1
            }
            if (changed) {
                DownloadCancellationRegistry.record(
                    item.id,
                    item.executionId,
                    DownloadCancellationRegistry.Reason.CANCELLED,
                )
            }
            if (!changed && item.status != Status.Cancelled.name) {
                return@withTransaction UndoableCancellation()
            }
            UndoableCancellation(
                affectedOperationIds = terminalizeLinkedChildren(
                    downloadIds = listOf(id),
                    reason = REASON_USER_CANCELLED,
                    now = System.currentTimeMillis()
                )
            )
        }

    suspend fun undoPendingCancellation(
        id: Long,
        token: String,
        originalStatus: Status
    ): PendingCancellationResolution = database.withTransaction {
        val ledgerDao = database.lowQualityRedownloadDao
        val ledgerItem = ledgerDao.getItemByDownloadId(id)
            ?: return@withTransaction PendingCancellationResolution()
        if (
            ledgerItem.stateValue != LowQualityRedownloadItemState.CANCELLATION_REQUESTED ||
            ledgerItem.reasonCode != token
        ) {
            return@withTransaction PendingCancellationResolution()
        }
        val operation = ledgerDao.getOperation(ledgerItem.operationId)
        val download = downloadDao.getNullableDownloadById(id)
        if (download != null && persistedHistoryRefusalLocked(id) != null) {
            return@withTransaction PendingCancellationResolution(
                affectedOperationIds = convergeHistoryReplacementRefusalLocked(
                    id = id,
                    expectedExecutionId = download.executionId,
                    forceError = false,
                ).affectedOperationIds
            )
        }
        val canRestore =
            operation != null &&
                !operation.stateValue.isTerminal &&
                !operation.cancelRequested &&
                download?.status == Status.Cancelled.name &&
                originalStatus in setOf(Status.Queued, Status.WaitingForMembership)

        if (canRestore) {
            val restored = when (originalStatus) {
                Status.Queued -> downloadDao.restoreCancelledStatus(id, Status.Queued.name)
                Status.WaitingForMembership -> database.observeSourcesDao.parkDownloadForMembership(
                    downloadId = id,
                    sourceId = download!!.observeSourceId,
                    expectedStatus = Status.Cancelled.name,
                    issueCode = download!!.lastIssueCode,
                    issueStage = download!!.lastIssueStage
                )
                else -> 0
            }
            if (restored == 1) {
                check(
                    ledgerDao.restorePendingUserCancellation(
                        id,
                        token,
                        System.currentTimeMillis()
                    ) == 1
                ) {
                    "Undoable cancellation lost restore ownership"
                }
                return@withTransaction PendingCancellationResolution(
                    restoredStatus = originalStatus,
                    affectedOperationIds = setOf(ledgerItem.operationId)
                )
            }
        }

        PendingCancellationResolution(
            affectedOperationIds = commitPendingCancellationLocked(id, token)
        )
    }

    suspend fun commitPendingCancellation(id: Long, token: String): Set<String> =
        database.withTransaction { commitPendingCancellationLocked(id, token) }

    private suspend fun commitPendingCancellationLocked(id: Long, token: String): Set<String> {
        val ledgerDao = database.lowQualityRedownloadDao
        val ledgerItem = ledgerDao.getItemByDownloadId(id) ?: return emptySet()
        if (
            ledgerItem.stateValue != LowQualityRedownloadItemState.CANCELLATION_REQUESTED ||
            ledgerItem.reasonCode != token
        ) {
            return emptySet()
        }
        val download = downloadDao.getNullableDownloadById(id)
        if (download != null && persistedHistoryRefusalLocked(id) != null) {
            return convergeHistoryReplacementRefusalLocked(
                id = id,
                expectedExecutionId = download.executionId,
                forceError = false,
            ).affectedOperationIds
        }
        if (download != null && download.status != Status.Cancelled.name) {
            val changed = if (download.executionId.isBlank()) {
                downloadDao.cancelByUser(id)
            } else {
                downloadDao.cancelIfExecutionOwned(id, download.executionId).also { affected ->
                    if (affected == 1) {
                        DownloadCancellationRegistry.record(
                            download.id,
                            download.executionId,
                            DownloadCancellationRegistry.Reason.CANCELLED,
                        )
                    }
                }
            }
            if (changed != 1) return emptySet()
        }
        if (
            ledgerDao.commitPendingUserCancellation(
                id,
                token,
                REASON_USER_CANCELLED,
                System.currentTimeMillis()
            ) != 1
        ) {
            return emptySet()
        }
        val operation = ledgerDao.getOperation(ledgerItem.operationId)
        if (operation != null && !operation.stateValue.isTerminal) {
            val finalState = LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                ledgerDao.getItems(ledgerItem.operationId)
            )
            if (finalState != null) {
                ledgerDao.finishOperation(
                    ledgerItem.operationId,
                    finalState.name,
                    "",
                    System.currentTimeMillis()
                )
            }
        }
        return setOf(ledgerItem.operationId)
    }

    private suspend fun deleteKnownUserRemoval(items: List<DownloadItem>): Set<String> {
        if (items.isEmpty()) return emptySet()
        val ids = items.map(DownloadItem::id).distinct()
        val operationIds = database.withTransaction {
            val now = System.currentTimeMillis()
            val affected = terminalizeLinkedChildren(ids, REASON_USER_REMOVED, now)
            database.historyReplacementBarrierDao.deleteForDownloadIds(ids)
            downloadDao.deleteAllWithIDs(ids)
            affected
        }
        deleteCache(items)
        return operationIds
    }

    private suspend fun terminalizeLinkedChildren(
        downloadIds: List<Long>,
        reason: String,
        now: Long
    ): Set<String> {
        val ledgerDao = database.lowQualityRedownloadDao
        val changedOperationIds = linkedSetOf<String>()
        ledgerDao.getNonterminalItemsByDownloadIds(downloadIds).forEach { item ->
            val downloadId = item.downloadId ?: return@forEach
            if (
                ledgerDao.setItemStateByDownloadId(
                    downloadId,
                    LowQualityRedownloadItemState.CANCELLED.name,
                    reason,
                    now
                ) == 1
            ) {
                changedOperationIds += item.operationId
            }
        }
        val notificationOperationIds = linkedSetOf<String>()
        changedOperationIds.forEach { operationId ->
            val operation = ledgerDao.getOperation(operationId) ?: return@forEach
            if (operation.stateValue.isTerminal) return@forEach
            val finalState = LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                ledgerDao.getItems(operationId)
            )
            if (finalState != null) {
                ledgerDao.finishOperation(operationId, finalState.name, "", now)
            }
            notificationOperationIds += operationId
        }
        return notificationOperationIds
    }

    suspend fun cancelActiveQueued(){
        database.withTransaction {
            downloadDao.getActiveAndQueuedDownloadsList().forEach { item ->
                val changed = if (item.executionId.isBlank()) {
                    downloadDao.cancelByUser(item.id) == 1
                } else {
                    downloadDao.cancelIfExecutionOwned(item.id, item.executionId) == 1
                }
                if (changed) {
                    DownloadCancellationRegistry.record(
                        item.id,
                        item.executionId,
                        DownloadCancellationRegistry.Reason.CANCELLED,
                    )
                }
                if (persistedHistoryRefusalLocked(item.id) != null) {
                    convergeHistoryReplacementRefusalLocked(
                        id = item.id,
                        expectedExecutionId = item.executionId,
                        forceError = false,
                    )
                }
            }
        }
    }

    fun removeLogID(logID: Long){
        downloadDao.removeLogID(logID)
    }

    fun removeAllLogID(){
        downloadDao.removeAllLogID()
    }

    @SuppressLint("RestrictedApi")
    suspend fun startDownloadWorker(queuedItems: List<DownloadItem>, context: Context, continueAfterPriorityItems: Boolean = true) : Result<String> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val allowMeteredNetworks = sharedPreferences.getBoolean("metered_networks", true)
        val workManager = WorkManager.getInstance(context)

        val useAlarmForScheduling = sharedPreferences.getBoolean("use_alarm_for_scheduling", false)
        val currentTime = System.currentTimeMillis()
        val scheduledItems = withContext(Dispatchers.IO) {
            getScheduledDownloads()
        }
        val scheduleCandidates = (queuedItems + scheduledItems)
            .distinctBy { it.id }
            .filter { it.downloadStartTime > 0L }
        val futureScheduleGroups = scheduleCandidates
            .filter { it.downloadStartTime - currentTime > 60_000L }
            .groupBy { it.downloadStartTime }
        val immediateItems = (queuedItems + scheduleCandidates)
            .distinctBy { it.id }
            .filter { it.downloadStartTime == 0L || it.downloadStartTime - currentTime <= 60_000L }
        val immediateRequestItems = if (continueAfterPriorityItems) {
            immediateItems
        } else {
            queuedItems
                .distinctBy { it.id }
                .filter { it.downloadStartTime == 0L || it.downloadStartTime - currentTime <= 60_000L }
        }

        val workConstraints = Constraints.Builder()
        if (!allowMeteredNetworks) workConstraints.setRequiredNetworkType(NetworkType.UNMETERED)

        fun buildRequest(
            items: List<DownloadItem>,
            delay: Long,
            continueAfterPriorityIds: Boolean
        ) =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag("download")
                .setConstraints(workConstraints.build())
                .setInitialDelay(delay.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .putLongArray("priority_item_ids", items.take(20).map { it.id }.toLongArray())
                        .putBoolean("continue_after_priority_ids", continueAfterPriorityIds)
                        .build()
                )
                .build()

        if (futureScheduleGroups.isNotEmpty() && useAlarmForScheduling) {
            AlarmScheduler(context).scheduleAt(futureScheduleGroups.keys.min())
        } else {
            futureScheduleGroups.forEach { (startTime, itemsAtStart) ->
                val request = buildRequest(
                    items = itemsAtStart,
                    delay = startTime - currentTime,
                    continueAfterPriorityIds = true
                )
                workManager.enqueueUniqueWork(
                    "$SCHEDULED_DOWNLOAD_WORK_NAME-$startTime",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }

        if (queuedItems.isEmpty() || immediateRequestItems.isNotEmpty()) {
            val request = buildRequest(
                items = immediateRequestItems,
                delay = 0L,
                continueAfterPriorityIds = continueAfterPriorityItems
            )
            // Keep each trigger independent. A unique KEEP request can be dropped while
            // the previous worker is shutting down after observing an empty queue.
            workManager.enqueueUniqueWork(
                "$DOWNLOAD_WORK_NAME-${request.id}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }


        val message = StringBuilder()

        val isCurrentNetworkMetered = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        if (!allowMeteredNetworks && isCurrentNetworkMetered){
            message.appendLine(context.getString(R.string.metered_network_download_start_info))
        }

        if (queuedItems.isNotEmpty()) {
            val first = queuedItems.first()
            if (first.downloadStartTime > 0L) {
                val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMMMyyyy - HHmm"), Locale.getDefault()).format(queuedItems.first().downloadStartTime)
                message.appendLine(context.getString(R.string.download_rescheduled_to) + " " + date)
            }
        }

        return Result.success(message.toString())
    }

    companion object {
        const val REASON_USER_CANCELLED = "USER_CANCELLED"
        const val REASON_USER_REMOVED = "USER_REMOVED_QUEUE_ITEM"
        const val REASON_SAVED_FOR_LATER = "SAVED_FOR_LATER"
        const val REASON_HISTORY_TARGET_DELETED = "HISTORY_TARGET_DELETED"
        const val PENDING_CANCELLATION_TOKEN_PREFIX = "PENDING_USER_CANCELLATION:"
        const val PENDING_REMOVAL_TOKEN_PREFIX = "PENDING_USER_REMOVAL:"
        private val livePendingRemovalTokens = ConcurrentHashMap.newKeySet<String>()

        internal fun isLivePendingRemovalToken(token: String): Boolean =
            livePendingRemovalTokens.contains(token)

        private fun registerLivePendingRemovalToken(token: String) {
            livePendingRemovalTokens += token
        }

        private fun unregisterLivePendingRemovalToken(token: String) {
            livePendingRemovalTokens -= token
        }

        internal fun clearLivePendingRemovalTokensForTest() {
            livePendingRemovalTokens.clear()
        }
        private const val DOWNLOAD_WORK_NAME = "download"
        private const val SCHEDULED_DOWNLOAD_WORK_NAME = "scheduledDownload"
    }

}
