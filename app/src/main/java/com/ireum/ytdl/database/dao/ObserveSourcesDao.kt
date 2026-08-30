package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.util.LowQualityRedownloadCompletionPolicy
import kotlinx.coroutines.flow.Flow

@Dao
interface ObserveSourcesDao {
    @Query("SELECT * FROM sources WHERE observationPurpose = 'USER' ORDER BY id DESC")
    fun getAllSources() : List<ObserveSourcesItem>

    @Query("SELECT * FROM sources ORDER BY id DESC")
    fun getAllSourcesIncludingManaged(): List<ObserveSourcesItem>

    @Query("SELECT * FROM sources WHERE observationPurpose = 'USER' AND url = :url LIMIT 1")
    fun getByURL(url: String) : ObserveSourcesItem

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    fun getByID(id: Long) : ObserveSourcesItem

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    fun getByIDOrNull(id: Long): ObserveSourcesItem?

    @Query("SELECT * FROM sources WHERE observationPurpose = 'USER' ORDER BY id DESC")
    fun getAllSourcesFlow() : Flow<List<ObserveSourcesItem>>

    @Query("SELECT * FROM sources WHERE observationPurpose = 'KEYWORD_DISCOVERY' AND managedConditionKey = :conditionKey LIMIT 1")
    fun getManagedKeywordSource(conditionKey: String): ObserveSourcesItem?

    @Query("SELECT EXISTS(SELECT * FROM sources WHERE observationPurpose = 'USER' AND url=:url LIMIT 1)")
    fun checkIfExistsWithSameURL(url: String) : Boolean

    @Query("""
        UPDATE downloads
        SET status = 'WaitingForMembership',
            lastIssueCode = :issueCode,
            lastIssueStage = :issueStage
        WHERE id = :downloadId
          AND observeSourceId = :sourceId
          AND status = :expectedStatus
          AND (:expectedExecutionId = '' OR executionId = :expectedExecutionId)
          AND (lastIssueCode IS NULL OR lastIssueCode != 'HISTORY_TARGET_DELETED')
          AND EXISTS(
              SELECT 1 FROM sources
              WHERE id = :sourceId AND status = 'ACTIVE'
          )
    """)
    suspend fun parkDownloadForMembership(
        downloadId: Long,
        sourceId: Long,
        expectedStatus: String,
        issueCode: String,
        issueStage: String,
        expectedExecutionId: String = "",
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ObserveSourcesItem) : Long

    @Query("DELETE FROM sources WHERE observationPurpose = 'USER'")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM sources WHERE id=:itemId")
    suspend fun deleteRecord(itemId: Long)

    @Query("""
        SELECT id FROM downloads
        WHERE observeSourceId=:sourceId
          AND (
              status='WaitingForMembership'
              OR (status='Queued' AND lastIssueCode='MEMBERSHIP_REQUIRED')
              OR (
                  status='Cancelled'
                  AND lastIssueCode='MEMBERSHIP_REQUIRED'
                  AND EXISTS(
                      SELECT 1 FROM low_quality_redownload_items pendingCancellation
                      WHERE pendingCancellation.downloadId=downloads.id
                        AND pendingCancellation.itemState='CANCELLATION_REQUESTED'
                        AND pendingCancellation.reasonCode LIKE 'PENDING_USER_CANCELLATION:v1:%'
                  )
              )
          )
    """)
    suspend fun getMembershipRetryDownloadIds(sourceId: Long): List<Long>

    @Query("""
        SELECT id FROM downloads
        WHERE observeSourceId > 0
          AND (
              status='WaitingForMembership'
              OR (status='Queued' AND lastIssueCode='MEMBERSHIP_REQUIRED')
              OR (
                  status='Cancelled'
                  AND lastIssueCode='MEMBERSHIP_REQUIRED'
                  AND EXISTS(
                      SELECT 1 FROM low_quality_redownload_items pendingCancellation
                      WHERE pendingCancellation.downloadId=downloads.id
                        AND pendingCancellation.itemState='CANCELLATION_REQUESTED'
                        AND pendingCancellation.reasonCode LIKE 'PENDING_USER_CANCELLATION:v1:%'
                  )
              )
          )
    """)
    suspend fun getAllMembershipRetryDownloadIds(): List<Long>

    @Query("""
        UPDATE downloads SET status='Cancelled'
        WHERE observeSourceId=:sourceId
          AND (
              status='WaitingForMembership'
              OR (status='Queued' AND lastIssueCode='MEMBERSHIP_REQUIRED')
          )
    """)
    suspend fun cancelMembershipRetryDownloads(sourceId: Long)

    @Query("""
        UPDATE downloads SET status='Cancelled'
        WHERE observeSourceId > 0
          AND (
              status='WaitingForMembership'
              OR (status='Queued' AND lastIssueCode='MEMBERSHIP_REQUIRED')
          )
    """)
    suspend fun cancelAllMembershipRetryDownloads()

    /**
     * Source revocation is selected before the source row is changed or
     * deleted.  Revalidate each selected Download here so a concurrent
     * production claim that won first is not retroactively cancelled.
     */
    @Query("""
        UPDATE downloads SET status='Cancelled'
        WHERE id IN (:downloadIds)
          AND (
              status='WaitingForMembership'
              OR (status='Queued' AND lastIssueCode='MEMBERSHIP_REQUIRED')
          )
    """)
    suspend fun cancelMembershipRetryDownloadsByIds(downloadIds: List<Long>): Int

    @Query("""
        SELECT DISTINCT operationId
        FROM low_quality_redownload_items
        WHERE downloadId IN (:downloadIds)
    """)
    suspend fun getMembershipRevocationOperationIds(downloadIds: List<Long>): List<String>

    @Query("""
        SELECT * FROM low_quality_redownload_operations
        WHERE operationId=:operationId
        LIMIT 1
    """)
    suspend fun getMembershipRevocationOperation(operationId: String): LowQualityRedownloadOperation?

    @Query("""
        SELECT * FROM low_quality_redownload_items
        WHERE operationId=:operationId
    """)
    suspend fun getMembershipRevocationItems(operationId: String): List<LowQualityRedownloadItem>

    /**
     * A source stop/delete is an authoritative cancellation for membership
     * retry rows.  Only rows that are still Cancelled are allowed to drive the
     * linked-child transition; this is the source-vs-claim first-wins fence.
     */
    @Query("""
        UPDATE low_quality_redownload_items
        SET itemState='CANCELLED', reasonCode='USER_CANCELLED', updatedAt=:updatedAt
        WHERE downloadId IN (:downloadIds)
          AND itemState IN ('PROVISIONAL','PENDING','CHECKING','QUEUED','ACTIVE','WAITING','CANCELLATION_REQUESTED')
          AND EXISTS(
              SELECT 1 FROM downloads revokedDownload
              WHERE revokedDownload.id=low_quality_redownload_items.downloadId
                AND revokedDownload.status='Cancelled'
          )
    """)
    suspend fun cancelMembershipLinkedItems(
        downloadIds: List<Long>,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE low_quality_redownload_operations
        SET state=:state,
            phase='FINALIZING',
            terminalReason='USER_CANCELLED',
            completedAt=:completedAt,
            updatedAt=:completedAt
        WHERE operationId=:operationId
          AND state='RUNNING'
    """)
    suspend fun finishMembershipRevokedOperation(
        operationId: String,
        state: String,
        completedAt: Long,
    ): Int

    @Query("""
        SELECT id FROM downloads
        WHERE observeSourceId=:sourceId
          AND status='WaitingForMembership'
          AND lastIssueCode='MEMBERSHIP_REQUIRED'
          AND EXISTS(
              SELECT 1 FROM sources
              WHERE id=:sourceId AND status='ACTIVE'
          )
          AND NOT EXISTS(
              SELECT 1 FROM history_replacement_barriers barrier
              WHERE barrier.downloadId=downloads.id
          )
          AND NOT (
              COALESCE(playlistURL, '') LIKE 'history-redownload:%'
              AND EXISTS(
                  SELECT 1 FROM history committedHistory
                  WHERE committedHistory.downloadId=downloads.id
              )
          )
          AND NOT EXISTS(
              SELECT 1
              FROM low_quality_redownload_items linked
              LEFT JOIN low_quality_redownload_operations operation
                ON operation.operationId=linked.operationId
              WHERE linked.downloadId=downloads.id
                AND (
                    linked.itemState NOT IN ('WAITING','QUEUED')
                    OR COALESCE(linked.reasonCode, '') != ''
                    OR operation.operationId IS NULL
                    OR operation.state != 'RUNNING'
                    OR operation.cancelRequested = 1
                )
          )
    """)
    suspend fun getRequeueableMembershipWaitingIds(sourceId: Long): List<Long>

    @Query("""
        UPDATE downloads
        SET status='Queued', downloadStartTime=0
        WHERE id IN (:downloadIds)
          AND status='WaitingForMembership'
          AND lastIssueCode='MEMBERSHIP_REQUIRED'
          AND EXISTS(
              SELECT 1 FROM sources source
              WHERE source.id=downloads.observeSourceId
                AND source.status='ACTIVE'
          )
          AND NOT EXISTS(
              SELECT 1 FROM history_replacement_barriers barrier
              WHERE barrier.downloadId=downloads.id
          )
          AND NOT (
              COALESCE(playlistURL, '') LIKE 'history-redownload:%'
              AND EXISTS(
                  SELECT 1 FROM history committedHistory
                  WHERE committedHistory.downloadId=downloads.id
              )
          )
    """)
    suspend fun requeueMembershipWaitingIds(downloadIds: List<Long>): Int

    /**
     * Membership retry changes the linked low-quality child in the same Room
     * transaction as the Download status transition.  A queued Download with
     * a still-WAITING child is not a coherent claim candidate.
     */
    @Query("""
        UPDATE low_quality_redownload_items
        SET itemState='QUEUED', reasonCode='', updatedAt=:updatedAt
        WHERE downloadId IN (:downloadIds)
          AND itemState='WAITING'
          AND COALESCE(reasonCode, '')=''
          AND EXISTS(
              SELECT 1 FROM downloads retryDownload
              WHERE retryDownload.id=low_quality_redownload_items.downloadId
                AND retryDownload.observeSourceId=:sourceId
                AND retryDownload.status='Queued'
                AND retryDownload.lastIssueCode='MEMBERSHIP_REQUIRED'
          )
          AND EXISTS(
              SELECT 1 FROM low_quality_redownload_operations operation
              WHERE operation.operationId=low_quality_redownload_items.operationId
                AND operation.state='RUNNING'
                AND operation.cancelRequested=0
          )
    """)
    suspend fun requeueMembershipWaitingChildren(
        sourceId: Long,
        downloadIds: List<Long>,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE downloads
        SET status='WaitingForMembership', downloadStartTime=0
        WHERE id IN (:downloadIds)
          AND observeSourceId=:sourceId
          AND status='Queued'
          AND lastIssueCode='MEMBERSHIP_REQUIRED'
          AND EXISTS(
              SELECT 1 FROM sources
              WHERE id=:sourceId AND status='ACTIVE'
          )
          AND NOT EXISTS(
              SELECT 1 FROM history_replacement_barriers barrier
              WHERE barrier.downloadId=downloads.id
          )
          AND NOT (
              COALESCE(playlistURL, '') LIKE 'history-redownload:%'
              AND EXISTS(
                  SELECT 1 FROM history committedHistory
                  WHERE committedHistory.downloadId=downloads.id
              )
          )
          AND NOT EXISTS(
              SELECT 1
              FROM low_quality_redownload_items linked
              LEFT JOIN low_quality_redownload_operations operation
                ON operation.operationId=linked.operationId
              WHERE linked.downloadId=downloads.id
                AND (
                    linked.itemState != 'QUEUED'
                    OR COALESCE(linked.reasonCode, '') != ''
                    OR operation.operationId IS NULL
                    OR operation.state != 'RUNNING'
                    OR operation.cancelRequested = 1
                )
          )
    """)
    suspend fun restoreMembershipWaitingIds(sourceId: Long, downloadIds: List<Long>): Int

    @Query("""
        UPDATE low_quality_redownload_items
        SET itemState='WAITING', reasonCode='', updatedAt=:updatedAt
        WHERE downloadId IN (:downloadIds)
          AND itemState='QUEUED'
          AND COALESCE(reasonCode, '')=''
          AND EXISTS(
              SELECT 1 FROM downloads waitingDownload
              WHERE waitingDownload.id=low_quality_redownload_items.downloadId
                AND waitingDownload.observeSourceId=:sourceId
                AND waitingDownload.status='WaitingForMembership'
                AND waitingDownload.lastIssueCode='MEMBERSHIP_REQUIRED'
          )
          AND EXISTS(
              SELECT 1 FROM low_quality_redownload_operations operation
              WHERE operation.operationId=low_quality_redownload_items.operationId
                AND operation.state='RUNNING'
                AND operation.cancelRequested=0
          )
    """)
    suspend fun restoreMembershipWaitingChildren(
        sourceId: Long,
        downloadIds: List<Long>,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun requeueMembershipWaiting(sourceId: Long): List<Long> {
        val waitingIds = getRequeueableMembershipWaitingIds(sourceId)
        if (waitingIds.isEmpty()) return emptyList()
        check(requeueMembershipWaitingIds(waitingIds) == waitingIds.size) {
            "Membership retry lost its exact Download transition"
        }
        requeueMembershipWaitingChildren(
            sourceId = sourceId,
            downloadIds = waitingIds,
            updatedAt = System.currentTimeMillis(),
        )
        return waitingIds
    }

    /**
     * Compensation for a retry-start failure restores both sides of the
     * waiting authority.  Every mutation is guarded by the queued membership
     * state and an uncancelled running operation, so a stronger concurrent
     * decision is never reopened.
     */
    @Transaction
    suspend fun restoreMembershipWaiting(
        sourceId: Long,
        downloadIds: List<Long>,
    ): Int {
        val restored = restoreMembershipWaitingIds(sourceId, downloadIds)
        if (restored > 0) {
            restoreMembershipWaitingChildren(
                sourceId = sourceId,
                downloadIds = downloadIds,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return restored
    }

    @Transaction
    suspend fun updateAndCancelWaiting(item: ObserveSourcesItem): List<Long> {
        val waitingIds = getMembershipRetryDownloadIds(item.id)
        update(item)
        convergeMembershipRevocation(waitingIds)
        return waitingIds
    }

    @Transaction
    suspend fun deleteAndCancelWaiting(itemId: Long): List<Long> {
        val waitingIds = getMembershipRetryDownloadIds(itemId)
        deleteRecord(itemId)
        convergeMembershipRevocation(waitingIds)
        return waitingIds
    }

    @Transaction
    suspend fun deleteAllAndCancelWaiting(): List<Long> {
        val waitingIds = getAllMembershipRetryDownloadIds()
        deleteAllRecords()
        convergeMembershipRevocation(waitingIds)
        return waitingIds
    }

    /**
     * Keeps source revocation and the linked low-quality terminal transition
     * in one Room transaction.  If the transaction is interrupted, SQLite
     * rolls back both the source/download mutation and this convergence, so
     * process death cannot strand a cancelled membership child without an
     * owner.
     */
    private suspend fun convergeMembershipRevocation(downloadIds: List<Long>) {
        if (downloadIds.isEmpty()) return
        cancelMembershipRetryDownloadsByIds(downloadIds)
        cancelMembershipLinkedItems(
            downloadIds = downloadIds,
            updatedAt = System.currentTimeMillis(),
        )
        getMembershipRevocationOperationIds(downloadIds)
            .distinct()
            .forEach { operationId ->
                val operation = getMembershipRevocationOperation(operationId)
                    ?: return@forEach
                if (operation.stateValue.isTerminal) return@forEach
                val terminalState = LowQualityRedownloadCompletionPolicy.terminalState(
                    operation,
                    getMembershipRevocationItems(operationId),
                ) ?: return@forEach
                finishMembershipRevokedOperation(
                    operationId = operationId,
                    state = terminalState.name,
                    completedAt = System.currentTimeMillis(),
                )
            }
    }

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(item: ObserveSourcesItem)
}
