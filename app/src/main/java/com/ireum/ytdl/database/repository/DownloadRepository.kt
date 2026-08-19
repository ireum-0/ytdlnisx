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
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.util.Extensions.toListString
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.LowQualityRedownloadCompletionPolicy
import com.ireum.ytdl.util.LowQualityRedownloadLinkedDownloadPolicy
import com.ireum.ytdl.work.AlarmScheduler
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

    data class PendingCancellationResolution(
        val restoredStatus: Status? = null,
        val affectedOperationIds: Set<String> = emptySet()
    )

    data class SavedDownloadResult(
        val downloadId: Long,
        val affectedOperationIds: Set<String> = emptySet()
    )

    suspend fun insert(item: DownloadItem) : Long {
        return downloadDao.insert(item)
    }

    suspend fun insertAll(items: List<DownloadItem>) : List<Long> {
        return downloadDao.insertAll(items)
    }

    suspend fun deleteAll(): Set<String> =
        deleteKnownUserRemoval(downloadDao.getAllDownloadsList())

    suspend fun delete(id: Long): Set<String> {
        val item = downloadDao.getNullableDownloadById(id) ?: return emptySet()
        return deleteKnownUserRemoval(listOf(item))
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


    suspend fun setDownloadStatus(id: Long, status: Status){
        downloadDao.setStatus(id, status.toString())
    }

    suspend fun setDownloadStatusMultiple(ids: List<Long>, status: Status) {
        downloadDao.setStatusMultiple(ids, status.toString())
    }

    suspend fun saveForLater(item: DownloadItem): SavedDownloadResult = database.withTransaction {
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
        downloadDao.deleteErrored()
        deleteCache(errored)
    }

    suspend fun deleteQueued(): Set<String> =
        deleteKnownUserRemoval(getQueuedDownloads())

    suspend fun deleteSaved(){
        downloadDao.deleteSaved()
    }

    suspend fun deleteProcessing(){
        downloadDao.deleteProcessing()
    }

    suspend fun deleteWithDuplicateStatus() {
        downloadDao.deleteWithDuplicateStatus()
    }

    suspend fun deleteAllWithIDs(ids: List<Long>): Set<String> =
        deleteKnownUserRemoval(downloadDao.getDownloadsByIdsSuspend(ids.distinct()))

    suspend fun cancelByUser(id: Long): Set<String> = database.withTransaction {
        val item = downloadDao.getNullableDownloadById(id) ?: return@withTransaction emptySet()
        val changed = downloadDao.cancelByUser(item.id) == 1
        if (!changed && item.status != Status.Cancelled.name) {
            return@withTransaction emptySet()
        }
        terminalizeLinkedChildren(
            downloadIds = listOf(item.id),
            reason = REASON_USER_CANCELLED,
            now = System.currentTimeMillis()
        )
    }

    suspend fun completeAndDelete(
        id: Long,
        successReason: String = ""
    ): Set<String> = database.withTransaction {
        val ledgerDao = database.lowQualityRedownloadDao
        val ledgerItem = ledgerDao.getItemByDownloadId(id)
        val changedOperationIds = linkedSetOf<String>()
        if (ledgerItem != null && !ledgerItem.stateValue.isTerminal) {
            val operation = ledgerDao.getOperation(ledgerItem.operationId)
                ?: error("Missing low-quality operation for linked download")
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
        downloadDao.delete(id)
        changedOperationIds
    }

    suspend fun completeHistoryTargetDeletedAndDelete(id: Long): Set<String> =
        database.withTransaction {
            val ledgerDao = database.lowQualityRedownloadDao
            val ledgerItem = ledgerDao.getItemByDownloadId(id)
            val changedOperationIds = linkedSetOf<String>()
            if (ledgerItem != null && !ledgerItem.stateValue.isTerminal) {
                val operation = ledgerDao.getOperation(ledgerItem.operationId)
                    ?: error("Missing low-quality operation for linked download")
                check(!operation.stateValue.isTerminal) {
                    "Terminal low-quality operation still owns a nonterminal child"
                }
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
                changedOperationIds += ledgerItem.operationId
            }
            downloadDao.delete(id)
            changedOperationIds
        }

    suspend fun beginUndoableCancellation(id: Long): UndoableCancellation =
        database.withTransaction {
            val item = downloadDao.getNullableDownloadById(id)
                ?: return@withTransaction UndoableCancellation()
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

            val changed = downloadDao.cancelByUser(id) == 1
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
        if (download != null && download.status != Status.Cancelled.name) {
            if (downloadDao.cancelByUser(id) != 1) return emptySet()
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
        downloadDao.cancelActiveQueued()
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
        private const val DOWNLOAD_WORK_NAME = "download"
        private const val SCHEDULED_DOWNLOAD_WORK_NAME = "scheduledDownload"
    }

}
