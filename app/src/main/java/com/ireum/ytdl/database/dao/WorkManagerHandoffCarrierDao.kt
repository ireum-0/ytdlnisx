package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier

@Dao
interface WorkManagerHandoffCarrierDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(carrier: WorkManagerHandoffCarrier): Long

    @Query("SELECT * FROM work_manager_handoff_carriers WHERE handoffId = :handoffId LIMIT 1")
    suspend fun get(handoffId: String): WorkManagerHandoffCarrier?

    @Query(
        "SELECT * FROM work_manager_handoff_carriers " +
            "WHERE state IN ('PENDING_ENQUEUE', 'ACCEPTED') " +
            "ORDER BY createdAt, handoffId"
    )
    suspend fun getOutstanding(): List<WorkManagerHandoffCarrier>

    @Query(
        "SELECT * FROM work_manager_handoff_carriers " +
            "WHERE kind = :kind AND boundary = :boundary " +
            "AND state IN ('PENDING_ENQUEUE', 'ACCEPTED') " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getOutstandingForBoundary(
        kind: String,
        boundary: String,
    ): WorkManagerHandoffCarrier?

    @Query(
        "SELECT * FROM work_manager_handoff_carriers " +
            "WHERE kind = 'OBSERVE_RETRY_DOWNLOAD' " +
            "AND sourceId = :sourceId AND confirmedUrl = :confirmedUrl " +
            "AND decision = :decision AND configFingerprint = :configFingerprint " +
            "AND state IN ('PENDING_ENQUEUE', 'ACCEPTED') " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getOutstandingObserveRetry(
        sourceId: Long,
        confirmedUrl: String,
        decision: String,
        configFingerprint: String,
    ): WorkManagerHandoffCarrier?

    @Query(
        "DELETE FROM work_manager_handoff_carriers " +
            "WHERE kind = :kind AND boundary = :boundary " +
            "AND state IN ('PENDING_ENQUEUE', 'ACCEPTED')"
    )
    suspend fun deleteOutstandingForBoundary(kind: String, boundary: String): Int

    @Query("DELETE FROM work_manager_handoff_carriers WHERE handoffId = :handoffId")
    suspend fun delete(handoffId: String): Int

    @Query(
        "DELETE FROM work_manager_handoff_carriers " +
            "WHERE handoffId = :handoffId AND requestId = :requestId " +
            "AND state = 'ACCEPTED'"
    )
    suspend fun deleteAccepted(handoffId: String, requestId: String): Int

    /** Exact request/generation acceptance; stale callbacks cannot mark a replacement. */
    @Query(
        "UPDATE work_manager_handoff_carriers SET state = 'ACCEPTED', updatedAt = :updatedAt " +
            "WHERE handoffId = :handoffId AND requestId = :requestId " +
            "AND state = 'PENDING_ENQUEUE'"
    )
    suspend fun markAccepted(
        handoffId: String,
        requestId: String,
        updatedAt: Long,
    ): Int

    /** Exact worker semantic completion; enqueue acceptance alone is insufficient. */
    @Query(
        "UPDATE work_manager_handoff_carriers SET state = 'RESOLVED', updatedAt = :updatedAt " +
            "WHERE handoffId = :handoffId AND requestId = :requestId " +
            "AND kind = 'OBSERVE_RETRY_DOWNLOAD' " +
            "AND state IN ('PENDING_ENQUEUE', 'ACCEPTED')"
    )
    suspend fun markResolved(
        handoffId: String,
        requestId: String,
        updatedAt: Long,
    ): Int

    /** A failed WorkManager generation keeps the same semantic handoff id. */
    @Query(
        "UPDATE work_manager_handoff_carriers SET requestId = :newRequestId, " +
            "attempt = :attempt, state = 'PENDING_ENQUEUE', updatedAt = :updatedAt " +
            "WHERE handoffId = :handoffId AND requestId = :oldRequestId " +
            "AND state IN ('PENDING_ENQUEUE', 'ACCEPTED')"
    )
    suspend fun advanceRetry(
        handoffId: String,
        oldRequestId: String,
        newRequestId: String,
        attempt: Int,
        updatedAt: Long,
    ): Int

    @Query(
        "DELETE FROM work_manager_handoff_carriers WHERE state = 'RESOLVED'"
    )
    suspend fun deleteResolved(): Int
}
