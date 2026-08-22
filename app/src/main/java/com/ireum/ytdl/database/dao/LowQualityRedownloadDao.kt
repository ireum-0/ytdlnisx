
package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadLiveCounts
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import kotlinx.coroutines.flow.Flow

@Dao
interface LowQualityRedownloadDao {
    @Query(
        "SELECT * FROM low_quality_redownload_operations " +
            "ORDER BY CASE WHEN state = 'RUNNING' THEN 0 ELSE 1 END, createdAt DESC LIMIT 1"
    )
    fun observeCurrentOperation(): Flow<LowQualityRedownloadOperation?>

    @Query(
        "SELECT * FROM low_quality_redownload_operations " +
            "ORDER BY CASE WHEN state = 'RUNNING' THEN 0 ELSE 1 END, createdAt DESC LIMIT 1"
    )
    suspend fun getCurrentOperation(): LowQualityRedownloadOperation?

    @Query("SELECT * FROM low_quality_redownload_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getOperation(operationId: String): LowQualityRedownloadOperation?

    @Query("SELECT * FROM low_quality_redownload_operations WHERE state = 'RUNNING' LIMIT 1")
    suspend fun getActiveOperation(): LowQualityRedownloadOperation?

    @Query("SELECT * FROM low_quality_redownload_items WHERE operationId = :operationId ORDER BY historyId")
    fun observeItems(operationId: String): Flow<List<LowQualityRedownloadItem>>

    @Query("SELECT * FROM low_quality_redownload_items WHERE operationId = :operationId ORDER BY historyId")
    suspend fun getItems(operationId: String): List<LowQualityRedownloadItem>

    @Query("SELECT * FROM low_quality_redownload_items WHERE operationId = :operationId AND historyId = :historyId LIMIT 1")
    suspend fun getItem(operationId: String, historyId: Long): LowQualityRedownloadItem?

    @Query("SELECT * FROM low_quality_redownload_items WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getItemByDownloadId(downloadId: Long): LowQualityRedownloadItem?

    @Query(
        "SELECT * FROM low_quality_redownload_items WHERE downloadId IN (:downloadIds) " +
            "AND itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED')"
    )
    suspend fun getNonterminalItemsByDownloadIds(downloadIds: List<Long>): List<LowQualityRedownloadItem>

    @Query("SELECT * FROM low_quality_redownload_items WHERE operationId = :operationId AND selected = 1 ORDER BY historyId")
    suspend fun getSelectedItems(operationId: String): List<LowQualityRedownloadItem>

    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN d.status IN ('Queued','Scheduled') THEN 1 ELSE 0 END), 0) AS queued, " +
            "COALESCE(SUM(CASE WHEN d.status IN ('Active','PostProcessing') THEN 1 ELSE 0 END), 0) AS active, " +
            "COALESCE(SUM(CASE WHEN d.status = 'WaitingForMembership' THEN 1 ELSE 0 END), 0) AS waiting, " +
            "COALESCE(SUM(CASE WHEN d.status = 'Paused' THEN 1 ELSE 0 END), 0) AS paused " +
            "FROM low_quality_redownload_items l " +
            "LEFT JOIN downloads d ON d.id = l.downloadId " +
            "WHERE l.operationId = :operationId AND l.selected = 1 " +
            "AND l.itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED')"
    )
    fun observeLiveCounts(operationId: String): Flow<LowQualityRedownloadLiveCounts>

    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN d.status IN ('Queued','Scheduled') THEN 1 ELSE 0 END), 0) AS queued, " +
            "COALESCE(SUM(CASE WHEN d.status IN ('Active','PostProcessing') THEN 1 ELSE 0 END), 0) AS active, " +
            "COALESCE(SUM(CASE WHEN d.status = 'WaitingForMembership' THEN 1 ELSE 0 END), 0) AS waiting, " +
            "COALESCE(SUM(CASE WHEN d.status = 'Paused' THEN 1 ELSE 0 END), 0) AS paused " +
            "FROM low_quality_redownload_items l " +
            "LEFT JOIN downloads d ON d.id = l.downloadId " +
            "WHERE l.operationId = :operationId AND l.selected = 1 " +
            "AND l.itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED')"
    )
    suspend fun getLiveCounts(operationId: String): LowQualityRedownloadLiveCounts

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: LowQualityRedownloadOperation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: LowQualityRedownloadItem)

    @Query(
        "DELETE FROM low_quality_redownload_operations WHERE state != 'RUNNING' AND operationId NOT IN " +
            "(SELECT operationId FROM low_quality_redownload_operations WHERE state != 'RUNNING' " +
            "ORDER BY createdAt DESC LIMIT 1)"
    )
    suspend fun purgeOlderTerminalOperations()

    @Transaction
    suspend fun createOrReconnect(operation: LowQualityRedownloadOperation): LowQualityRedownloadOperation {
        getActiveOperation()?.let { return it }
        purgeOlderTerminalOperations()
        insertOperation(operation)
        return operation
    }

    @Query(
        "UPDATE low_quality_redownload_operations SET scanCursorHistoryId = :cursor, " +
            "scanProcessed = scanProcessed + 1, scanFailures = scanFailures + :failureIncrement, " +
            "updatedAt = :updatedAt WHERE operationId = :operationId AND state = 'RUNNING' AND phase = 'SCANNING'"
    )
    suspend fun advanceScanCursor(
        operationId: String,
        cursor: Long,
        failureIncrement: Int,
        updatedAt: Long
    ): Int

    @Transaction
    suspend fun checkpointScanItem(
        operationId: String,
        cursor: Long,
        candidate: LowQualityRedownloadItem?,
        failed: Boolean,
        updatedAt: Long
    ) {
        candidate?.let { upsertItem(it) }
        check(advanceScanCursor(operationId, cursor, if (failed) 1 else 0, updatedAt) == 1) {
            "Low-quality scan checkpoint no longer owns the operation"
        }
    }

    @Query(
        "UPDATE low_quality_redownload_operations SET phase = :nextPhase, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND state = 'RUNNING' AND phase = :expectedPhase"
    )
    suspend fun advancePhase(
        operationId: String,
        expectedPhase: String,
        nextPhase: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_operations SET cancelRequested = 1, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND state = 'RUNNING'"
    )
    suspend fun requestCancellation(operationId: String, updatedAt: Long): Int

    @Query(
        "UPDATE low_quality_redownload_operations SET state = :state, phase = 'FINALIZING', " +
            "terminalReason = :reason, completedAt = :completedAt, updatedAt = :completedAt " +
            "WHERE operationId = :operationId AND state = 'RUNNING'"
    )
    suspend fun finishOperation(
        operationId: String,
        state: String,
        reason: String,
        completedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET selected = :selected, " +
            "itemState = CASE WHEN :selected = 1 THEN 'PENDING' ELSE 'PROVISIONAL' END, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND historyId = :historyId " +
            "AND itemState IN ('PROVISIONAL','PENDING','NOT_SELECTED')"
    )
    suspend fun setSelected(
        operationId: String,
        historyId: Long,
        selected: Boolean,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'NOT_SELECTED', updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND selected = 0 AND itemState = 'PROVISIONAL'"
    )
    suspend fun markUnselected(operationId: String, updatedAt: Long): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = :state, reasonCode = :reason, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND historyId = :historyId AND downloadId IS NULL " +
            "AND itemState IN ('PENDING','CHECKING')"
    )
    suspend fun setItemState(
        operationId: String,
        historyId: Long,
        state: String,
        reason: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = :state, reasonCode = :reason, updatedAt = :updatedAt " +
            "WHERE downloadId = :downloadId " +
            "AND itemState IN ('PROVISIONAL','PENDING','CHECKING','QUEUED','ACTIVE','WAITING')"
    )
    suspend fun setItemStateByDownloadId(
        downloadId: Long,
        state: String,
        reason: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'CANCELLED', " +
            "reasonCode = :reason, updatedAt = :updatedAt WHERE downloadId = :downloadId " +
            "AND itemState IN ('PROVISIONAL','PENDING','CHECKING','QUEUED','ACTIVE','WAITING','CANCELLATION_REQUESTED')"
    )
    suspend fun markCancelledByDownloadId(
        downloadId: Long,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = :state, reasonCode = :reason, " +
            "updatedAt = :updatedAt WHERE downloadId = :downloadId AND itemState != 'SUCCEEDED'"
    )
    suspend fun setHistoryReplacementRefusalItemState(
        downloadId: Long,
        state: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'SUCCEEDED', " +
            "reasonCode = :reason, updatedAt = :updatedAt " +
            "WHERE downloadId = :downloadId AND operationId = :operationId " +
            "AND itemState IN ('QUEUED','ACTIVE','WAITING')"
    )
    suspend fun markHistoryReplacementCommitted(
        downloadId: Long,
        operationId: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET downloadId = :newDownloadId, updatedAt = :updatedAt " +
            "WHERE downloadId = :oldDownloadId"
    )
    suspend fun rebindDownloadId(
        oldDownloadId: Long,
        newDownloadId: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = :state, reasonCode = :reason, " +
            "updatedAt = :updatedAt WHERE downloadId = :downloadId " +
            "AND itemState = 'CANCELLATION_REQUESTED' AND reasonCode = :expectedToken"
    )
    suspend fun restoreUndoableLinkedItem(
        downloadId: Long,
        expectedToken: String,
        state: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'CANCELLED', reasonCode = :reason, " +
            "updatedAt = :updatedAt WHERE downloadId = :downloadId " +
            "AND itemState = 'CANCELLATION_REQUESTED' AND reasonCode = :expectedToken"
    )
    suspend fun commitUndoableLinkedItem(
        downloadId: Long,
        expectedToken: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'CANCELLATION_REQUESTED', " +
            "reasonCode = :token, updatedAt = :updatedAt WHERE downloadId = :downloadId " +
            "AND itemState IN ('QUEUED','ACTIVE','WAITING')"
    )
    suspend fun markPendingUserRemoval(
        downloadId: Long,
        token: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_operations SET state = :state, phase = 'FINALIZING', " +
            "terminalReason = :reason, completedAt = :completedAt, updatedAt = :completedAt " +
            "WHERE operationId = :operationId AND state = :expectedState"
    )
    suspend fun setTerminalOperationState(
        operationId: String,
        expectedState: String,
        state: String,
        reason: String,
        completedAt: Long,
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'CANCELLATION_REQUESTED', " +
            "reasonCode = :token, updatedAt = :updatedAt WHERE downloadId = :downloadId " +
            "AND itemState IN ('QUEUED','ACTIVE','WAITING')"
    )
    suspend fun markPendingUserCancellation(
        downloadId: Long,
        token: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'QUEUED', reasonCode = '', " +
            "updatedAt = :updatedAt WHERE downloadId = :downloadId " +
            "AND itemState = 'CANCELLATION_REQUESTED' AND reasonCode = :token"
    )
    suspend fun restorePendingUserCancellation(
        downloadId: Long,
        token: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'CANCELLED', reasonCode = :reason, " +
            "updatedAt = :updatedAt WHERE downloadId = :downloadId " +
            "AND itemState = 'CANCELLATION_REQUESTED' AND reasonCode = :token"
    )
    suspend fun commitPendingUserCancellation(
        downloadId: Long,
        token: String,
        reason: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET downloadId = :downloadId, itemState = 'QUEUED', " +
            "reasonCode = '', updatedAt = :updatedAt WHERE operationId = :operationId AND historyId = :historyId " +
            "AND downloadId IS NULL AND itemState = 'CHECKING'"
    )
    suspend fun linkQueuedDownload(
        operationId: String,
        historyId: Long,
        downloadId: Long,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET candidateReason = :candidateReason, mediaState = :mediaState, " +
            "actualHeight = :actualHeight, expectedHeight = :expectedHeight, sourceMaxHeight = :sourceMaxHeight, " +
            "updatedAt = :updatedAt WHERE operationId = :operationId AND historyId = :historyId"
    )
    suspend fun updateQualification(
        operationId: String,
        historyId: Long,
        candidateReason: String,
        mediaState: String,
        actualHeight: Int,
        expectedHeight: Int,
        sourceMaxHeight: Int,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = :state, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND itemState IN ('PROVISIONAL','PENDING','CHECKING')"
    )
    suspend fun markUnqueuedItems(operationId: String, state: String, updatedAt: Long): Int

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = :state, reasonCode = :reason, " +
            "updatedAt = :updatedAt WHERE operationId = :operationId " +
            "AND itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED')"
    )
    suspend fun terminalizeNonterminalItems(
        operationId: String,
        state: String,
        reason: String,
        updatedAt: Long
    ): Int

    @Query(
        "SELECT downloadId FROM low_quality_redownload_items WHERE operationId = :operationId " +
            "AND downloadId IS NOT NULL AND itemState IN ('QUEUED','ACTIVE','WAITING','CANCELLATION_REQUESTED')"
    )
    suspend fun getNonterminalDownloadIds(operationId: String): List<Long>

    @Query(
        "UPDATE low_quality_redownload_items SET itemState = 'CANCELLATION_REQUESTED', updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND downloadId IS NOT NULL " +
            "AND itemState IN ('QUEUED','ACTIVE','WAITING')"
    )
    suspend fun markLinkedCancellationRequested(operationId: String, updatedAt: Long): Int

    @Transaction
    suspend fun requestCancellationAndMarkItems(operationId: String, updatedAt: Long): List<Long> {
        requestCancellation(operationId, updatedAt)
        markUnqueuedItems(operationId, LowQualityRedownloadItemState.CANCELLED.name, updatedAt)
        markLinkedCancellationRequested(operationId, updatedAt)
        return getNonterminalDownloadIds(operationId)
    }

    @Query("SELECT COUNT(*) FROM low_quality_redownload_items WHERE operationId = :operationId")
    suspend fun countItems(operationId: String): Int

    @Query("SELECT COUNT(*) FROM low_quality_redownload_items WHERE operationId = :operationId AND selected = 1")
    suspend fun countSelected(operationId: String): Int

    @Query(
        "SELECT COUNT(*) FROM low_quality_redownload_items WHERE operationId = :operationId AND selected = 1 " +
            "AND itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED')"
    )
    suspend fun countSelectedNonterminal(operationId: String): Int
}
