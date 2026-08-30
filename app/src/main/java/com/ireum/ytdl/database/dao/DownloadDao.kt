package com.ireum.ytdl.database.dao

import android.util.Log
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.KnownMediaPublishedDate
import com.ireum.ytdl.database.models.DownloadItemConfigureMultiple
import com.ireum.ytdl.database.models.DownloadItemSimple
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.database.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

private const val HISTORY_TARGET_DELETED_ISSUE = "HISTORY_TARGET_DELETED"

/**
 * A membership retry is the one nonterminal issue that is intentionally
 * claimable.  It remains typed by every part of the durable authority:
 * Queued Download + MEMBERSHIP_REQUIRED + the same Queued child + a running,
 * uncancelled operation + an active observe source.  This is deliberately
 * expressed inside the debt row predicate so another unresolved child cannot
 * be hidden by a valid membership child.
 */
private const val LOW_QUALITY_MEMBERSHIP_RETRY_AUTHORITY =
    "downloads.status = 'Queued' " +
        "AND downloads.lastIssueCode = 'MEMBERSHIP_REQUIRED' " +
        "AND debt.itemState = 'QUEUED' " +
        "AND COALESCE(debt.reasonCode, '') = '' " +
        "AND EXISTS (SELECT 1 FROM low_quality_redownload_operations membershipOperation " +
        "WHERE membershipOperation.operationId = debt.operationId " +
        "AND membershipOperation.state = 'RUNNING' " +
        "AND membershipOperation.cancelRequested = 0) " +
        "AND EXISTS (SELECT 1 FROM sources membershipSource " +
        "WHERE membershipSource.id = downloads.observeSourceId " +
        "AND membershipSource.status = 'ACTIVE') " +
        "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers membershipBarrier " +
        "WHERE membershipBarrier.downloadId = downloads.id) " +
        "AND NOT (COALESCE(downloads.playlistURL, '') LIKE 'history-redownload:%' " +
        "AND EXISTS (SELECT 1 FROM history committedMembershipHistory " +
        "WHERE committedMembershipHistory.downloadId = downloads.id))"

private const val LOW_QUALITY_TERMINAL_DEBT_GUARD =
    "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items debt " +
        "WHERE debt.downloadId = downloads.id " +
        "AND debt.itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED') " +
        "AND COALESCE(downloads.lastIssueCode, '') != '' " +
        "AND NOT ($LOW_QUALITY_MEMBERSHIP_RETRY_AUTHORITY))"

/** Same typed state, used to consume the transient issue at successful claim. */
private const val LOW_QUALITY_MEMBERSHIP_RETRY_CLEAR_PREDICATE =
    "downloads.status = 'Queued' " +
        "AND downloads.lastIssueCode = 'MEMBERSHIP_REQUIRED' " +
        "AND EXISTS (SELECT 1 FROM sources membershipSource " +
        "WHERE membershipSource.id = downloads.observeSourceId " +
        "AND membershipSource.status = 'ACTIVE')"

private const val COMMITTED_HISTORY_REPLACEMENT_GUARD =
    "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%' " +
        "AND EXISTS (SELECT 1 FROM history committedHistory " +
        "WHERE committedHistory.downloadId = downloads.id)) "

private const val LOW_QUALITY_REPLACEMENT_RUNNABLE_PREDICATE =
    "NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
        "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
        "WHERE q.downloadId = downloads.id)) " +
        // A replacement whose History row already points at this Download is
        // past the semantic commit boundary.  It must be finalized, never
        // admitted as a new destructive attempt.
        COMMITTED_HISTORY_REPLACEMENT_GUARD +
        "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
        "LEFT JOIN low_quality_redownload_operations lqo " +
        "ON lqo.operationId = lqi.operationId " +
        "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
        // A durable Download issue plus a nonterminal linked child is an
        // unresolved terminal-convergence fact.  It is not safe to turn that
        // row back into runnable work before the child has consumed the fact.
        LOW_QUALITY_TERMINAL_DEBT_GUARD

private const val LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD =
    "AND $LOW_QUALITY_REPLACEMENT_RUNNABLE_PREDICATE"

private fun preservesTargetDeletedIssue(
    current: DownloadItem?,
    incoming: DownloadItem,
): Boolean = current?.lastIssueCode != HISTORY_TARGET_DELETED_ISSUE ||
    (
        incoming.lastIssueCode == HISTORY_TARGET_DELETED_ISSUE &&
            incoming.lastIssueStage == current.lastIssueStage
        )

private suspend fun DownloadDao.preservesTerminalConvergenceDebt(
    current: DownloadItem?,
    incoming: DownloadItem,
): Boolean {
    if (current == null || current.lastIssueCode.isNullOrBlank()) return true
    if (!hasLowQualityTerminalConvergenceDebt(current.id)) return true
    return current.status == incoming.status &&
        current.lastIssueCode == incoming.lastIssueCode &&
        current.lastIssueStage == incoming.lastIssueStage
}

@Dao
interface DownloadDao {

    @Query("SELECT url, mediaPublishedAt FROM downloads WHERE mediaPublishedAt != 0")
    suspend fun getKnownMediaPublishedDates(): List<KnownMediaPublishedDate>

    @Query("SELECT * FROM downloads ORDER BY status")
    fun getAllDownloads() : PagingSource<Int, DownloadItem>

    @Query("SELECT * FROM downloads")
    suspend fun getAllDownloadsList(): List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE status='Active'")
    fun getActiveDownloads() : Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE status in('Active','PostProcessing','Paused') ORDER BY orderPosition, id, CASE WHEN status='Active' THEN 0 WHEN status='PostProcessing' THEN 1 ELSE 2 END")
    fun getActiveAndPausedDownloads() : Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE status='Paused'")
    fun getPausedDownloads() : Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE status='Paused'")
    fun getPausedDownloadsList() : List<DownloadItem>

    @Query(
        "SELECT d.* FROM downloads d " +
            "INNER JOIN history h ON h.downloadId = d.id " +
            "WHERE COALESCE(d.playlistURL, '') LIKE 'history-redownload:%'"
    )
    suspend fun getCommittedHistoryReplacementDownloads(): List<DownloadItem>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM downloads WHERE status = 'Processing'")
    fun getProcessingDownloads() : Flow<List<DownloadItemConfigureMultiple>>

    @Query("SELECT COUNT(*) FROM downloads WHERE status in (:statuses)")
    fun getDownloadsCountFlow(statuses: List<String>) : Flow<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE status in (:status)")
    fun getDownloadsCountByStatusFlow(status : List<String>) : Flow<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE status in (:statuses)")
    fun getDownloadsCountByStatus(statuses : List<String>) : Int


    @Query("""
        SELECT DISTINCT type from downloads where status = 'Processing'
    """)
    fun getProcessingDownloadTypes() : List<String>


    @Query("SELECT DISTINCT type from downloads where status = 'Processing' and id in (:ids)")
    fun getProcessingDownloadTypesByIDs(ids: List<Long>) : List<String>

    @Query("""
        SELECT DISTINCT container from downloads where status = 'Processing'
        """)
    fun getProcessingDownloadContainers() : List<String>

    @Query("""SELECT DISTINCT container from downloads where id in (:ids)""")
    fun getDownloadContainersByIDs(ids: List<Long>) : List<String>


    @Query(
        "UPDATE downloads SET status = 'Processing', executionId='' WHERE id IN (:ids) " +
            "AND status NOT IN ('Active','PostProcessing') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun updateItemsToProcessing(ids: List<Long>): Int


    @Query("SELECT * FROM downloads WHERE status = 'Processing' ORDER BY orderPosition, id LIMIT 1")
    fun getFirstProcessingDownload() : DownloadItem


    @Query("SELECT * FROM downloads WHERE status = 'Processing' ORDER BY orderPosition, id")
    fun getProcessingDownloadsList() : List<DownloadItem>

    @Query("UPDATE downloads set downloadPath=:path WHERE status ='Processing'")
    suspend fun updateProcessingDownloadPath(path: String)

    @Query("UPDATE downloads set downloadPath=:path WHERE id in (:ids)")
    suspend fun updateDownloadPathByIDs(ids: List<Long>, path: String)

    @Query("UPDATE downloads set container=:cont WHERE status ='Processing'")
    suspend fun updateProcessingContainer(cont: String)

    @Query("UPDATE downloads set container=:cont WHERE id in (:ids)")
    suspend fun updateContainerByIds(ids: List<Long>, cont: String)

    @Query("SELECT * FROM downloads WHERE status='Active'")
    fun getActiveDownloadsList() : List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE status in('Active','PostProcessing')")
    fun getActiveAndPostProcessingDownloadsList() : List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE url=:url AND status='Processing'")
    fun getProcessingDownloadsByUrl(url: String) : List<DownloadItem>

    @Query("DELETE from downloads where status = 'Processing' AND url=:url")
    suspend fun deleteProcessingByUrl(url: String)

    @Query("SELECT * FROM downloads WHERE status in('Active','PostProcessing','Queued','WaitingForMembership','Scheduled')")
    fun getActiveAndQueuedDownloadsList() : List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE status in('Active','PostProcessing','Queued','WaitingForMembership','Scheduled','Paused','Processing')")
    fun getPendingObservationDownloadsList() : List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE observeSourceId=:sourceId AND status='WaitingForMembership' ORDER BY orderPosition, id")
    fun getMembershipWaitingDownloads(sourceId: Long): List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE status='WaitingForMembership' ORDER BY orderPosition, id")
    fun getMembershipWaitingDownloads(): List<DownloadItem>

    @Query("SELECT COUNT(*) FROM downloads WHERE (playlistURL = :marker OR playlistURL LIKE :marker || ':%') AND status IN ('Processing','Queued','WaitingForMembership','Active','PostProcessing','Paused','Scheduled')")
    fun countPendingByPlaylistMarker(marker: String): Int

    @Query(
        "UPDATE downloads SET status='Queued', downloadStartTime = -1, executionId='' " +
            "WHERE status IN ('Paused') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun resetPausedToQueued()

    @Query("SELECT id FROM downloads WHERE status in('Active','PostProcessing','Queued', 'Paused')")
    fun getActiveAndQueuedDownloadIDs() : List<Long>

    @Query("SELECT * FROM downloads WHERE status in('Active','PostProcessing','Queued')")
    fun getActiveAndQueuedDownloads() : Flow<List<DownloadItem>>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT * FROM downloads WHERE status in ('Queued','WaitingForMembership') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
            "ORDER BY orderPosition, id"
    )
    fun getQueuedDownloads() : PagingSource<Int, DownloadItemSimple>

    @Query("SELECT format FROM downloads WHERE status IN ('Queued','WaitingForMembership')")
    fun getSelectedFormatFromQueued() : List<Format>

    @Query("""
        SELECT * FROM downloads 
        WHERE status in ('Queued', 'Scheduled') AND downloadStartTime <= :currentTime
        AND (lastIssueCode IS NULL OR lastIssueCode NOT IN
            ('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED'))
        AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id)
        AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%'
            AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q
                WHERE q.downloadId = downloads.id))
        AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi
            LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId
            WHERE lqi.downloadId = downloads.id
            AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED')
                OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1))
        ORDER BY downloadStartTime, orderPosition, id
        LIMIT 10
    """)
    fun getQueuedScheduledDownloadsUntil(currentTime: Long) : Flow<List<DownloadItem>>

    @Query("""
        SELECT * FROM downloads 
        WHERE status in ('Queued', 'Scheduled') AND downloadStartTime <= :currentTime
        AND (lastIssueCode IS NULL OR lastIssueCode NOT IN
            ('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED'))
        AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id)
        AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%'
            AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q
                WHERE q.downloadId = downloads.id))
        AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi
            LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId
            WHERE lqi.downloadId = downloads.id
            AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED')
                OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1))
        ORDER BY 
            CASE
                WHEN id in (:priorityItems) THEN 0
                ELSE 1
            END,
            downloadStartTime, orderPosition, id
        LIMIT 10
    """)
    fun getQueuedScheduledDownloadsUntilWithPriority(currentTime: Long, priorityItems: List<Long>) : Flow<List<DownloadItem>>

    @Query(
        "SELECT * FROM downloads WHERE status in ('Queued','WaitingForMembership') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
            "ORDER BY orderPosition, id"
    )
    fun getQueuedDownloadsList() : List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE status in ('Queued','WaitingForMembership') ORDER BY orderPosition, id")
    suspend fun getQueuedDownloadsForBackupList(): List<DownloadItem>

    @Query(
        "SELECT * FROM downloads WHERE status='Scheduled' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
            "ORDER BY downloadStartTime, orderPosition, id"
    )
    fun getScheduledDownloadsList() : List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE status='Scheduled' ORDER BY downloadStartTime, orderPosition, id")
    suspend fun getScheduledDownloadsForBackupList(): List<DownloadItem>

    @Query(
        "SELECT id FROM downloads WHERE status='Queued' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
            "ORDER BY orderPosition, id"
    )
    fun getQueuedDownloadsListIDs() : List<Long>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM downloads WHERE status='Cancelled' ORDER BY id DESC")
    fun getCancelledDownloads() : PagingSource<Int, DownloadItemSimple>

    @Query("SELECT * FROM downloads WHERE status='Cancelled' ORDER BY id DESC")
    fun getCancelledDownloadsList() : List<DownloadItem>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM downloads WHERE status='Error' ORDER BY id DESC")
    fun getErroredDownloads() : PagingSource<Int, DownloadItemSimple>

    @Query("SELECT * FROM downloads WHERE status='Error' ORDER BY id DESC")
    fun getErroredDownloadsList() : List<DownloadItem>


    @Query(
        "SELECT id from downloads WHERE status='Scheduled' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
            "ORDER BY downloadStartTime, orderPosition, id DESC"
    )
    fun getScheduledDownloadIDs(): List<Long>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM downloads WHERE status='Saved' ORDER BY id DESC")
    fun getSavedDownloads() : PagingSource<Int, DownloadItemSimple>

    @Query("SELECT * FROM downloads WHERE status='Saved' ORDER BY id DESC")
    fun getSavedDownloadsList() : List<DownloadItem>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT * FROM downloads WHERE status='Scheduled' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
            "ORDER BY downloadStartTime, orderPosition, id DESC"
    )
    fun getScheduledDownloads() : PagingSource<Int, DownloadItemSimple>

    @Query("SELECT * FROM downloads WHERE id=:id LIMIT 1")
    fun getDownloadById(id: Long) : DownloadItem

    @Query("SELECT * FROM downloads WHERE id=:id LIMIT 1")
    fun getNullableDownloadById(id: Long) : DownloadItem?

    @Query("SELECT * FROM history_replacement_barriers WHERE downloadId=:id LIMIT 1")
    suspend fun getHistoryReplacementBarrier(id: Long): HistoryReplacementBarrier?

    @Query("SELECT * FROM history_replacement_barriers WHERE downloadId=:id LIMIT 1")
    fun getHistoryReplacementBarrierBlocking(id: Long): HistoryReplacementBarrier?

    @Query("SELECT * FROM downloads WHERE id IN (:ids)")
    fun getDownloadsByIds(ids: List<Long>) : List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE id IN (:ids)")
    suspend fun getDownloadsByIdsSuspend(ids: List<Long>): List<DownloadItem>

    /**
     * Claims one exact queue snapshot.  A stale worker gets zero rows and must
     * not run the item or write its stale full-row snapshot back to Room.
     */
    @Query(
        "UPDATE downloads SET status='Active', executionId=:executionId, " +
            "lastIssueCode=CASE WHEN ($LOW_QUALITY_MEMBERSHIP_RETRY_CLEAR_PREDICATE) " +
            "THEN '' ELSE lastIssueCode END, " +
            "lastIssueStage=CASE WHEN ($LOW_QUALITY_MEMBERSHIP_RETRY_CLEAR_PREDICATE) " +
            "THEN '' ELSE lastIssueStage END " +
            "WHERE id=:id AND status IN ('Queued','Scheduled') " +
            "AND operationId=:expectedOperationId AND retryAttempt=:expectedRetryAttempt " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = downloads.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1))"
            + COMMITTED_HISTORY_REPLACEMENT_GUARD
            + LOW_QUALITY_TERMINAL_DEBT_GUARD
    )
    suspend fun claimDownloadForWorker(
        id: Long,
        expectedOperationId: String,
        expectedRetryAttempt: Int,
        executionId: String,
    ): Int

    /**
     * Claims and materializes one exact queue snapshot as one Room
     * transaction.  A successful claim without an exact readable row is a
     * failed handoff, not a nullable success: throwing here makes Room roll
     * the CAS back before process-local ownership can be published.
     */
    @Transaction
    suspend fun claimDownloadForWorkerAndRead(
        id: Long,
        expectedOperationId: String,
        expectedRetryAttempt: Int,
        executionId: String,
    ): DownloadItem? {
        check(!DownloadClaimTestHooks.consumeClaimWriteFailureForTesting()) {
            "Injected Download claim write failure"
        }
        if (
            claimDownloadForWorker(
                id = id,
                expectedOperationId = expectedOperationId,
                expectedRetryAttempt = expectedRetryAttempt,
                executionId = executionId,
            ) != 1
        ) {
            return null
        }
        check(!DownloadClaimTestHooks.consumeMaterializationReadFailureForTesting()) {
            "Injected Download claim materialization read failure"
        }
        return getNullableDownloadById(id)
            ?.takeIf { it.executionId == executionId }
            ?: error(
                "Claimed Download row could not be materialized for " +
                    "executionId=$executionId id=$id"
            )
    }

    @Query("SELECT * FROM downloads WHERE id IN (:ids)")
    fun getDownloadsByIdsFlow(ids: List<Long>) : Flow<List<DownloadItem>>

    @Query("SELECT id FROM downloads ORDER BY id DESC LIMIT 1")
    fun getLastDownloadId(): Long

    @Query("SELECT status FROM downloads WHERE id=:id")
    fun checkStatus(id: Long) : DownloadRepository.Status?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRaw(item: DownloadItem) : Long

    @Transaction
    suspend fun insert(item: DownloadItem): Long {
        if (
            item.id > 0L &&
                getHistoryReplacementBarrier(item.id) != null
        ) {
            return item.id
        }
        if (item.id <= 0L) {
            item.orderPosition = 0L
        } else if (item.orderPosition <= 0L) {
            item.orderPosition = item.id
        }
        val id = insertRaw(item)
        if (item.orderPosition <= 0L) {
            item.orderPosition = id
            updateOrderPosition(id, id)
        }
        return id
    }

    @Transaction
    suspend fun insertAll(list: List<DownloadItem>): List<Long> = list.map { insert(it) }

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("DELETE FROM downloads WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status='Cancelled'")
    suspend fun deleteCancelled()

    @Query("DELETE FROM downloads WHERE status='Error'")
    suspend fun deleteErrored()

    @Query("DELETE FROM downloads WHERE status in ('Queued','WaitingForMembership')")
    suspend fun deleteQueued()

    @Query("DELETE FROM downloads WHERE status='Saved'")
    suspend fun deleteSaved()

    @Query("DELETE FROM downloads WHERE status='Processing'")
    suspend fun deleteProcessing()

    @Query("DELETE FROM downloads WHERE status='Duplicate'")
    suspend fun deleteWithDuplicateStatus()

    @Query("DELETE FROM downloads WHERE status='Scheduled'")
    suspend fun deleteScheduled()

    @Query("DELETE FROM downloads WHERE id in (:list)")
    suspend fun deleteAllWithIDs(list: List<Long>)

    @Query("UPDATE downloads SET status='Cancelled' WHERE status in('Queued','WaitingForMembership','Active','PostProcessing', 'Scheduled', 'Paused')")
    suspend fun cancelActiveQueued()

    @Query(
        "UPDATE downloads SET status='Cancelled' WHERE id=:id " +
            "AND status IN ('Queued','WaitingForMembership','Active','PostProcessing','Scheduled','Paused')"
    )
    suspend fun cancelByUser(id: Long): Int

    @Query(
        "UPDATE downloads SET status=:status, executionId='' WHERE id=:id AND status='Cancelled' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode != 'HISTORY_TARGET_DELETED') " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            "AND (:status NOT IN ('Queued','Scheduled','Active','Processing','WaitingForMembership') OR " +
            "(" + LOW_QUALITY_REPLACEMENT_RUNNABLE_PREDICATE + "))"
    )
    suspend fun restoreCancelledStatus(id: Long, status: String): Int

    @Query(
        "UPDATE downloads SET status=:status, " +
            "executionId=CASE WHEN :status='Queued' THEN '' ELSE executionId END " +
            "WHERE id=:id AND status='Cancelled' " +
            "AND :status IN ('Queued','WaitingForMembership') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode != 'HISTORY_TARGET_DELETED') " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b " +
            "WHERE b.downloadId = downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%' " +
            "AND EXISTS (SELECT 1 FROM history committedHistory " +
            "WHERE committedHistory.downloadId = downloads.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items debt " +
            "WHERE debt.downloadId = downloads.id " +
            "AND debt.itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED') " +
            "AND NOT (debt.operationId = :operationId " +
            "AND debt.itemState = 'CANCELLATION_REQUESTED' " +
            "AND debt.reasonCode = :token " +
            "AND ((:status = 'Queued' " +
            "AND COALESCE(downloads.lastIssueCode, '') IN ('', 'MEMBERSHIP_REQUIRED')) " +
            "OR (:status = 'WaitingForMembership' " +
            "AND downloads.lastIssueCode = 'MEMBERSHIP_REQUIRED')))) " +
            "AND (:status != 'WaitingForMembership' OR EXISTS (" +
            "SELECT 1 FROM sources membershipSource " +
            "WHERE membershipSource.id = downloads.observeSourceId " +
            "AND membershipSource.status = 'ACTIVE')) " +
            "AND EXISTS (SELECT 1 FROM low_quality_redownload_items pending " +
            "JOIN low_quality_redownload_operations operation " +
            "ON operation.operationId = pending.operationId " +
            "WHERE pending.downloadId = downloads.id " +
            "AND pending.operationId = :operationId " +
            "AND pending.itemState = 'CANCELLATION_REQUESTED' " +
            "AND pending.reasonCode = :token " +
            "AND operation.state = 'RUNNING' " +
            "AND operation.cancelRequested = 0)"
    )
    suspend fun restoreCancelledStatusForPendingCancellation(
        id: Long,
        status: String,
        operationId: String,
        token: String,
    ): Int

    @Query(
        "UPDATE downloads SET status='Paused' WHERE id=:id AND executionId=:executionId " +
            "AND status IN ('Active','PostProcessing','Queued','Scheduled','WaitingForMembership')"
    )
    suspend fun pauseIfExecutionOwned(id: Long, executionId: String): Int

    @Query(
        "UPDATE downloads SET status='Queued', downloadStartTime=0, executionId='' " +
            "WHERE id=:id AND status='Paused' AND executionId=:expectedExecutionId " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun resumePausedIfExecutionOwned(id: Long, expectedExecutionId: String): Int

    @Query(
        "UPDATE downloads SET status='Cancelled' WHERE id=:id AND executionId=:executionId " +
            "AND status IN ('Queued','WaitingForMembership','Active','PostProcessing','Scheduled','Paused')"
    )
    suspend fun cancelIfExecutionOwned(id: Long, executionId: String): Int

    @Query("DELETE FROM downloads WHERE status='Processing' AND id=:id")
    suspend fun deleteSingleProcessing(id: Long)

    @Upsert
    suspend fun updateRaw(item: DownloadItem): Long

    /**
     * A stale full-row snapshot must not erase an immutable History mismatch
     * barrier.  Owner-only worker writes use updateRaw after validating the
     * execution token below.
     */
    @Transaction
    suspend fun update(item: DownloadItem): Long {
        val current = item.id.takeIf { it > 0L }?.let(::getNullableDownloadById)
        if (
            item.id > 0L &&
            (
                getHistoryReplacementBarrier(item.id) != null ||
                        !preservesTargetDeletedIssue(current, item) ||
                        !preservesTerminalConvergenceDebt(current, item)
                    )
        ) {
            return item.id
        }
        return updateRaw(item)
    }

    /** A full-row write is allowed only while this exact worker owns the attempt. */
    @Transaction
    suspend fun updateIfExecutionOwned(item: DownloadItem, expectedExecutionId: String): Boolean {
        val current = getNullableDownloadById(item.id)
        if (current?.executionId != expectedExecutionId) return false
        if (!preservesTargetDeletedIssue(current, item)) return false
        if (!preservesTerminalConvergenceDebt(current, item)) return false
        val barrier = getHistoryReplacementBarrier(item.id)
        if (
            barrier != null &&
                (
                    item.lastIssueCode != barrier.issueCode ||
                        item.lastIssueStage != barrier.issueStage
                    )
        ) {
            return false
        }
        updateRaw(item)
        return true
    }

    /** Terminal/phase writes must not turn a user pause/cancel back into Active/Error. */
    @Transaction
    suspend fun updateIfExecutionOwnedAndRunning(
        item: DownloadItem,
        expectedExecutionId: String,
    ): Boolean {
        val current = getNullableDownloadById(item.id)
        if (
            current?.executionId != expectedExecutionId ||
            current.status !in setOf("Active", "PostProcessing")
        ) {
            return false
        }
        if (hasLowQualityCancellationRequested(item.id)) return false
        if (!preservesTargetDeletedIssue(current, item)) return false
        if (!preservesTerminalConvergenceDebt(current, item)) return false
        val barrier = getHistoryReplacementBarrier(item.id)
        if (
            barrier != null &&
                (
                    item.lastIssueCode != barrier.issueCode ||
                        item.lastIssueStage != barrier.issueStage
                    )
        ) {
            return false
        }
        updateRaw(item)
        return true
    }

    /**
     * Atomically applies a runnable transition from the exact source snapshot
     * the caller evaluated.  A second retry/reconfigure action must observe
     * zero rows after the first action has queued or claimed the item, rather
     * than replaying a stale full-row snapshot over the newer execution.
     */
    @Transaction
    suspend fun updateForQueueIfSnapshot(
        item: DownloadItem,
        expectedStatus: String,
        expectedExecutionId: String,
        expectedOperationId: String,
        expectedRetryAttempt: Int,
        expectedIssueCode: String,
        expectedIssueStage: String,
    ): Boolean {
        val current = getNullableDownloadById(item.id)
        if (
            current == null ||
                current.status != expectedStatus ||
                expectedStatus in setOf("Active", "PostProcessing") ||
                current.executionId != expectedExecutionId ||
                current.operationId != expectedOperationId ||
                current.retryAttempt != expectedRetryAttempt ||
                current.lastIssueCode != expectedIssueCode ||
                current.lastIssueStage != expectedIssueStage ||
                getHistoryReplacementBarrier(item.id) != null ||
                !isRunnableLowQualityReplacement(item.id)
        ) {
            return false
        }
        updateRaw(
            item.copy(executionId = "")
        )
        return true
    }

    @Query(
        "SELECT COUNT(*) = 1 FROM downloads AS d " +
            "WHERE d.id=:id " +
            "AND NOT (COALESCE(d.playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
            "WHERE q.downloadId = d.id)) " +
            "AND NOT (COALESCE(d.playlistURL, '') LIKE 'history-redownload:%' " +
            "AND EXISTS (SELECT 1 FROM history committedHistory " +
            "WHERE committedHistory.downloadId = d.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo " +
            "ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = d.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1)) " +
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items debt " +
            "WHERE debt.downloadId = d.id " +
            "AND debt.itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED') " +
            "AND COALESCE(d.lastIssueCode, '') != '')"
    )
    suspend fun isRunnableLowQualityReplacement(id: Long): Boolean

    @Query(
        "SELECT EXISTS (" +
            "SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo " +
            "ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = :id " +
            "AND (lqi.itemState = 'CANCELLATION_REQUESTED' OR lqo.cancelRequested = 1)" +
            ")"
    )
    suspend fun hasLowQualityCancellationRequested(id: Long): Boolean

    @Query(
        "SELECT EXISTS (" +
            "SELECT 1 FROM low_quality_redownload_items debt " +
            "WHERE debt.downloadId = :id " +
            "AND debt.itemState NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED') " +
            "AND EXISTS (SELECT 1 FROM downloads d " +
                "WHERE d.id = debt.downloadId AND COALESCE(d.lastIssueCode, '') != '')" +
            ")"
    )
    suspend fun hasLowQualityTerminalConvergenceDebt(id: Long): Boolean

    @Transaction
    suspend fun updateAll(list: List<DownloadItem>) : List<DownloadItem> {
        val toReturn = mutableListOf<DownloadItem>()
        list.forEach {
            if (it.id > 0) {
                if (preservesTargetDeletedIssue(getNullableDownloadById(it.id), it)) {
                    update(it)
                }
            }else{
                it.id = insert(it)
            }
            toReturn.add(it)
        }

        return toReturn
    }

    @Query("UPDATE downloads set status=:status where id=:id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE downloads set status=:status where id IN (:ids)")
    suspend fun setStatusMultiple(ids: List<Long>, status: String)

    @Query(
        "UPDATE downloads SET status=:nextStatus, " +
            "downloadStartTime=CASE WHEN :nextStatus='Error' THEN 0 ELSE downloadStartTime END, " +
            "executionId=CASE WHEN :nextStatus='Error' THEN '' ELSE executionId END, " +
            "lastIssueCode=:issueCode, lastIssueStage=:issueStage " +
            "WHERE id=:id AND status=:expectedStatus " +
            "AND (:expectedExecutionId='' OR executionId=:expectedExecutionId) " +
            "AND (lastIssueCode IS NULL OR lastIssueCode='' OR lastIssueCode=:issueCode OR EXISTS (" +
            "SELECT 1 FROM history_replacement_barriers b0 WHERE b0.downloadId=downloads.id " +
            "AND b0.issueCode=:issueCode AND b0.issueStage=:issueStage)) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b " +
            "WHERE b.downloadId=downloads.id " +
            "AND (b.issueCode != :issueCode OR b.issueStage != :issueStage))"
    )
    suspend fun convergeHistoryReplacementRefusal(
        id: Long,
        expectedStatus: String,
        expectedExecutionId: String,
        nextStatus: String,
        issueCode: String,
        issueStage: String,
    ): Int

    @Query(
        "UPDATE downloads SET status='Queued', downloadStartTime=0, executionId='' " +
            "WHERE id IN (:ids) AND status IN ('Active','PostProcessing') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun requeueActiveDownloads(ids: List<Long>): Int

    @Query(
        "UPDATE downloads SET status = 'Cancelled' WHERE id IN (:ids) " +
            "AND status IN ('Queued','Scheduled','Paused','WaitingForMembership','Active','PostProcessing')"
    )
    suspend fun cancelLinkedDownloads(ids: List<Long>): Int

    @Query(
        "UPDATE downloads set status=:newStatus, " +
            "executionId=CASE WHEN :newStatus='Queued' THEN '' ELSE executionId END " +
            "where id IN (:ids) AND status=:currentStatus " +
            "AND (:newStatus NOT IN ('Queued','Scheduled','Active','Processing','WaitingForMembership') OR " +
            "(" + LOW_QUALITY_REPLACEMENT_RUNNABLE_PREDICATE + "))"
    )
    suspend fun setStatusMultipleFromStatus(ids: List<Long>, currentStatus: String, newStatus: String): Int

    @Query(
        "UPDATE downloads SET lastIssueCode=:issueCode, lastIssueStage=:issueStage " +
            "WHERE id=:id AND executionId=:executionId " +
            "AND (lastIssueCode IS NULL OR lastIssueCode='' OR lastIssueCode=:issueCode) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b " +
            "WHERE b.downloadId=downloads.id " +
            "AND (b.issueCode != :issueCode OR b.issueStage != :issueStage))"
    )
    suspend fun persistMismatchIssueForExecution(
        id: Long,
        executionId: String,
        issueCode: String,
        issueStage: String,
    ): Int

    @Query(
        "UPDATE downloads SET lastIssueCode=:issueCode, lastIssueStage=:issueStage " +
            "WHERE id=:id AND executionId=:executionId " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b " +
            "WHERE b.downloadId=downloads.id " +
            "AND (b.issueCode != :issueCode OR b.issueStage != :issueStage))"
    )
    suspend fun persistAuthoritativeIssueForExecution(
        id: Long,
        executionId: String,
        issueCode: String,
        issueStage: String,
    ): Int

    @Query(
        "UPDATE downloads SET status='Queued', downloadStartTime=0, executionId='', " +
            "lastIssueCode=:issueCode, lastIssueStage=:issueStage " +
            "WHERE id=:id AND executionId=:executionId AND status IN ('Active','PostProcessing') " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b " +
            "WHERE b.downloadId=downloads.id " +
            "AND (b.issueCode != :issueCode OR b.issueStage != :issueStage))"
    )
    suspend fun requeueActiveDownloadWithIssue(
        id: Long,
        executionId: String,
        issueCode: String,
        issueStage: String,
    ): Int

    @Query(
        "UPDATE downloads SET status='Queued', downloadStartTime=0, executionId='' " +
            "WHERE id=:id AND executionId=:executionId AND status IN ('Active','PostProcessing') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode != 'HISTORY_TARGET_DELETED') " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b " +
            "WHERE b.downloadId=downloads.id) " +
            "AND NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%' " +
            "AND EXISTS (SELECT 1 FROM history committedHistory " +
            "WHERE committedHistory.downloadId = downloads.id))"
    )
    suspend fun requeueActiveDownload(id: Long, executionId: String): Int

    @Query(
        "UPDATE downloads SET status='Queued', downloadStartTime=0, executionId='', " +
            "lastIssueCode=:issueCode, lastIssueStage=:issueStage " +
            "WHERE id IN (:ids) AND status IN ('Active','PostProcessing') " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b " +
            "WHERE b.downloadId=downloads.id " +
            "AND (b.issueCode != :issueCode OR b.issueStage != :issueStage))"
    )
    suspend fun requeueActiveDownloadsWithIssue(
        ids: List<Long>,
        issueCode: String,
        issueStage: String,
    ): Int

    @Update
    suspend fun updateWithoutUpsertRaw(item: DownloadItem)

    @Transaction
    suspend fun updateWithoutUpsert(item: DownloadItem) {
        if (
            item.id > 0L &&
                (
                    getHistoryReplacementBarrier(item.id) != null ||
                        !preservesTerminalConvergenceDebt(
                            getNullableDownloadById(item.id),
                            item,
                        )
                    )
        ) {
            return
        }
        updateWithoutUpsertRaw(item)
    }

    @Query("UPDATE downloads SET logID=null")
    fun removeAllLogID()

    @Query("UPDATE downloads SET logID=null WHERE logID=:logID")
    fun removeLogID(logID: Long)

    @Upsert
    fun updateMultipleRaw(items: List<DownloadItem>)

    @Transaction
    fun updateMultiple(items: List<DownloadItem>) {
        items.filter { item ->
            item.id <= 0L || getHistoryReplacementBarrierBlocking(item.id) == null
        }.takeIf { it.isNotEmpty() }?.let { updateMultipleRaw(it) }
    }

    @Query("SELECT * FROM downloads ORDER BY id DESC LIMIT 1")
    fun getLatest() : DownloadItem

    @Query(
        "UPDATE downloads SET status='Queued', executionId='' WHERE status='Processing' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun queueAllProcessing()


    @Query("SELECT * FROM downloads WHERE url=:url AND (status='Error' OR status='Cancelled') LIMIT 1")
    fun checkIfErrorOrCancelled(url: String) : DownloadItem

    @Query("SELECT * FROM downloads WHERE url=:url AND format=:format AND (status='Error' OR status='Cancelled') LIMIT 1")
    fun getUnfinishedByURLAndFormat(url: String, format: String) : DownloadItem


    @Query(
        "SELECT COUNT(*) = 0 FROM downloads " +
            "WHERE id IN (:items) AND (" +
            "(:inverted = 'false' AND downloadStartTime <= :currentStartTime) OR " +
            "(:inverted = 'true' AND downloadStartTime >= :currentStartTime)" +
            ")"
    )
    fun checkAllQueuedItemsAreScheduledAfterNow(items: List<Long>, inverted: String, currentStartTime: Long) : Boolean


    @Query("Select id from downloads where id not in (:list) and status in (:status)")
    fun getDownloadIDsNotPresentInList(list: List<Long>, status: List<String>) : List<Long>

    @Query("Select url from downloads where status in (:status)")
    fun getURLsByStatus(status: List<String>) : List<String>

    @Query("Select id from downloads where status in (:status)")
    fun getIDsByStatus(status: List<String>) : List<Long>

    @Query("Select url from downloads where id in (:ids)")
    fun getURLsByID(ids: List<Long>) : List<String>

    @Query(
        "UPDATE downloads SET downloadStartTime=0, status='Queued', executionId='' WHERE id IN (:list) " +
            "AND status IN ('Queued','Scheduled') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun resetScheduleTimeForItems(list: List<Long>): Int

    @Query(
        "UPDATE downloads SET downloadStartTime=0, status='Queued', executionId='' WHERE status = 'Scheduled' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun resetScheduleTimeForAllScheduledItems(): Int

    @Query(
        "UPDATE downloads SET downloadStartTime=:startTime, " +
            "status=CASE WHEN :startTime > 0 THEN 'Scheduled' ELSE 'Queued' END, executionId='' " +
            "WHERE id=:id AND status IN ('Queued','Scheduled') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun rescheduleQueuedOrScheduled(id: Long, startTime: Long): Int

    @Query(
        "UPDATE downloads SET status='Queued', downloadStartTime = 0, executionId='' " +
            "WHERE id IN (:list) AND status NOT IN ('Active','PostProcessing') " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id) " +
            LOW_QUALITY_REPLACEMENT_RUNNABLE_GUARD
    )
    suspend fun reQueueDownloadItems(list: List<Long>): Int

    @Query(
        "UPDATE downloads SET status='Saved' WHERE status='Processing' " +
            "AND (lastIssueCode IS NULL OR lastIssueCode NOT IN " +
            "('HISTORY_REPLACEMENT_SOURCE_MISMATCH', 'HISTORY_REPLACEMENT_TYPE_MISMATCH', 'HISTORY_TARGET_DELETED')) " +
            "AND NOT EXISTS (SELECT 1 FROM history_replacement_barriers b WHERE b.downloadId = downloads.id)"
    )
    suspend fun updateProcessingtoSavedStatus()

    @Transaction
    suspend fun putAtTopOfTheQueue(existingIDs: List<Long>) {
        val current = getQueuedDownloadsListIDs()
        val selected = current.filter(existingIDs.toSet()::contains)
        rewriteQueuedOrder(selected + current.filterNot(selected.toSet()::contains))
    }

    @Transaction
    suspend fun putAtBottomOfTheQueue(existingIDs: List<Long>) {
        val current = getQueuedDownloadsListIDs()
        val selected = current.filter(existingIDs.toSet()::contains)
        rewriteQueuedOrder(current.filterNot(selected.toSet()::contains) + selected)
    }

    @Transaction
    suspend fun putAtPosition(currentId: Long, targetId: Long) {
        val ordered = getQueuedDownloadsListIDs().toMutableList()
        val currentIndex = ordered.indexOf(currentId)
        val targetIndex = ordered.indexOf(targetId)
        if (currentIndex < 0 || targetIndex < 0 || currentIndex == targetIndex) return
        ordered.removeAt(currentIndex)
        ordered.add(targetIndex, currentId)
        rewriteQueuedOrder(ordered)
    }

    private suspend fun rewriteQueuedOrder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> updateQueuedOrderPosition(id, index + 1L) }
    }

    @Transaction
    suspend fun reverseProcessingDownloads() {
        val items = getProcessingDownloadsList()
        val positions = items.map(DownloadItem::orderPosition)
        items.reversed().forEachIndexed { index, item ->
            updateOrderPosition(item.id, positions[index])
            updateDownloadRowNumber(item.id, items.size - index)
        }
    }

    @Query("UPDATE downloads SET orderPosition=:position WHERE id=:id")
    suspend fun updateOrderPosition(id: Long, position: Long)

    @Query("UPDATE downloads SET orderPosition=:position WHERE id=:id AND status='Queued'")
    suspend fun updateQueuedOrderPosition(id: Long, position: Long): Int

    @Query("Update downloads set rowNumber=:newNr where id=:id")
    suspend fun updateDownloadRowNumber(id: Long, newNr: Int)

    @Query("SELECT id from downloads WHERE id > :item1 AND id < :item2 AND status in (:statuses) ORDER BY id DESC")
    fun getIDsBetweenTwoItems(item1: Long, item2: Long, statuses: List<String>) : List<Long>

    @Query(
        "SELECT id FROM downloads WHERE status='Queued' AND orderPosition > " +
            "MIN((SELECT orderPosition FROM downloads WHERE id=:item1), " +
            "(SELECT orderPosition FROM downloads WHERE id=:item2)) AND orderPosition < " +
            "MAX((SELECT orderPosition FROM downloads WHERE id=:item1), " +
            "(SELECT orderPosition FROM downloads WHERE id=:item2)) ORDER BY orderPosition, id"
    )
    fun getQueuedIDsBetweenTwoItems(item1: Long, item2: Long): List<Long>

    @Query("SELECT id from downloads WHERE id > :item1 AND id < :item2 AND status in('Scheduled') ORDER BY downloadStartTime, id")
    fun getScheduledIDsBetweenTwoItems(item1: Long, item2: Long) : List<Long>


    @Query("UPDATE downloads set incognito=:incognito WHERE status='Processing'")
    suspend fun updateProcessingIncognito(incognito: Boolean)

    @Query("UPDATE downloads set incognito=:incognito WHERE id in (:ids)")
    suspend fun updateIncognitoByIDs(incognito: Boolean, ids: List<Long>)

    @Query("SELECT COUNT(id) FROM downloads WHERE status='Processing' AND incognito='1'")
    fun getProcessingAsIncognitoCount(): Int

    @Query("SELECT COUNT(id) FROM downloads WHERE status='Processing' AND incognito='1' and id in (:ids)")
    fun getProcessingAsIncognitoCountByIDs(ids: List<Long>): Int
}

/** Narrow fault controls for deterministic production-path claim tests. */
internal object DownloadClaimTestHooks {
    private val failNextClaimWrite = AtomicBoolean(false)
    private val failNextMaterializationRead = AtomicBoolean(false)

    @Volatile
    internal var afterClaimMaterializationBeforeOwnerPublicationForTesting:
        ((DownloadItem) -> Unit)? = null

    @Volatile
    internal var afterExecutionOwnerPublicationForTesting: ((DownloadItem) -> Unit)? = null

    internal fun failNextClaimWriteForTesting() {
        failNextClaimWrite.set(true)
    }

    internal fun failNextMaterializationReadForTesting() {
        failNextMaterializationRead.set(true)
    }

    internal fun consumeClaimWriteFailureForTesting(): Boolean =
        failNextClaimWrite.compareAndSet(true, false)

    internal fun consumeMaterializationReadFailureForTesting(): Boolean =
        failNextMaterializationRead.compareAndSet(true, false)

    internal fun resetForTesting() {
        failNextClaimWrite.set(false)
        failNextMaterializationRead.set(false)
        afterClaimMaterializationBeforeOwnerPublicationForTesting = null
        afterExecutionOwnerPublicationForTesting = null
    }
}
