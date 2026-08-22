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

private const val HISTORY_TARGET_DELETED_ISSUE = "HISTORY_TARGET_DELETED"

private const val LOW_QUALITY_REPLACEMENT_RUNNABLE_PREDICATE =
    "NOT (COALESCE(playlistURL, '') LIKE 'history-redownload:%:quality:%' " +
        "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items q " +
        "WHERE q.downloadId = downloads.id)) " +
        "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
        "LEFT JOIN low_quality_redownload_operations lqo " +
        "ON lqo.operationId = lqi.operationId " +
        "WHERE lqi.downloadId = downloads.id " +
        "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
        "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1))"

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
        "UPDATE downloads SET status='Active', executionId=:executionId " +
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
    )
    suspend fun claimDownloadForWorker(
        id: Long,
        expectedOperationId: String,
        expectedRetryAttempt: Int,
        executionId: String,
    ): Int

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
        if (
            item.id > 0L &&
                (
                    getHistoryReplacementBarrier(item.id) != null ||
                        !preservesTargetDeletedIssue(getNullableDownloadById(item.id), item)
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
            "AND NOT EXISTS (SELECT 1 FROM low_quality_redownload_items lqi " +
            "LEFT JOIN low_quality_redownload_operations lqo " +
            "ON lqo.operationId = lqi.operationId " +
            "WHERE lqi.downloadId = d.id " +
            "AND (lqi.itemState IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED','NOT_SELECTED','CANCELLATION_REQUESTED') " +
            "OR lqo.operationId IS NULL OR lqo.state != 'RUNNING' OR lqo.cancelRequested = 1))"
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
    suspend fun setStatusMultipleFromStatus(ids: List<Long>, currentStatus: String, newStatus: String)

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
            "WHERE b.downloadId=downloads.id)"
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
                getHistoryReplacementBarrier(item.id) != null
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
