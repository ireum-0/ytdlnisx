package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
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
        issueStage: String
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
          )
    """)
    suspend fun getMembershipRetryDownloadIds(sourceId: Long): List<Long>

    @Query("""
        SELECT id FROM downloads
        WHERE observeSourceId > 0
          AND (
              status='WaitingForMembership'
              OR (status='Queued' AND lastIssueCode='MEMBERSHIP_REQUIRED')
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

    @Query("""
        SELECT id FROM downloads
        WHERE observeSourceId=:sourceId
          AND status='WaitingForMembership'
          AND EXISTS(
              SELECT 1 FROM sources
              WHERE id=:sourceId AND status='ACTIVE'
          )
    """)
    suspend fun getRequeueableMembershipWaitingIds(sourceId: Long): List<Long>

    @Query("""
        UPDATE downloads
        SET status='Queued', downloadStartTime=0
        WHERE id IN (:downloadIds) AND status='WaitingForMembership'
    """)
    suspend fun requeueMembershipWaitingIds(downloadIds: List<Long>)

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
    """)
    suspend fun restoreMembershipWaitingIds(sourceId: Long, downloadIds: List<Long>)

    @Transaction
    suspend fun requeueMembershipWaiting(sourceId: Long): List<Long> {
        val waitingIds = getRequeueableMembershipWaitingIds(sourceId)
        if (waitingIds.isNotEmpty()) {
            requeueMembershipWaitingIds(waitingIds)
        }
        return waitingIds
    }

    @Transaction
    suspend fun updateAndCancelWaiting(item: ObserveSourcesItem): List<Long> {
        val waitingIds = getMembershipRetryDownloadIds(item.id)
        update(item)
        cancelMembershipRetryDownloads(item.id)
        return waitingIds
    }

    @Transaction
    suspend fun deleteAndCancelWaiting(itemId: Long): List<Long> {
        val waitingIds = getMembershipRetryDownloadIds(itemId)
        deleteRecord(itemId)
        cancelMembershipRetryDownloads(itemId)
        return waitingIds
    }

    @Transaction
    suspend fun deleteAllAndCancelWaiting(): List<Long> {
        val waitingIds = getAllMembershipRetryDownloadIds()
        deleteAllRecords()
        cancelAllMembershipRetryDownloads()
        return waitingIds
    }

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(item: ObserveSourcesItem)
}
