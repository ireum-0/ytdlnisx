package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ireum.ytdl.database.models.HistoryDateFetchCounts
import com.ireum.ytdl.database.models.HistoryDateFetchItem
import com.ireum.ytdl.database.models.HistoryDateFetchOperation
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDateFetchDao {
    @Query("SELECT * FROM history_date_fetch_operations ORDER BY createdAt DESC LIMIT 1")
    fun observeCurrentOperation(): Flow<HistoryDateFetchOperation?>

    @Query("SELECT * FROM history_date_fetch_operations WHERE state = 'RUNNING' ORDER BY createdAt LIMIT 1")
    suspend fun getActiveOperation(): HistoryDateFetchOperation?

    @Query("SELECT * FROM history_date_fetch_operations WHERE state = 'RUNNING' ORDER BY createdAt")
    suspend fun getNonterminalOperations(): List<HistoryDateFetchOperation>

    @Query("SELECT * FROM history_date_fetch_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getOperation(operationId: String): HistoryDateFetchOperation?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: HistoryDateFetchOperation)

    @Query(
        "DELETE FROM history_date_fetch_operations WHERE state != 'RUNNING' AND operationId NOT IN " +
            "(SELECT operationId FROM history_date_fetch_operations WHERE state != 'RUNNING' " +
            "ORDER BY createdAt DESC LIMIT 1)"
    )
    suspend fun purgeOlderTerminalOperations()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<HistoryDateFetchItem>)

    @Query("SELECT * FROM history_date_fetch_items WHERE operationId = :operationId ORDER BY historyId")
    fun observeItems(operationId: String): Flow<List<HistoryDateFetchItem>>

    @Query("SELECT * FROM history_date_fetch_items WHERE operationId = :operationId ORDER BY historyId")
    suspend fun getItems(operationId: String): List<HistoryDateFetchItem>

    @Query(
        "SELECT * FROM history_date_fetch_items WHERE operationId = :operationId " +
            "AND itemState = 'PENDING' ORDER BY historyId"
    )
    suspend fun getPendingItems(operationId: String): List<HistoryDateFetchItem>

    @Query(
        "SELECT * FROM history_date_fetch_items WHERE operationId = :operationId " +
            "AND historyId = :historyId LIMIT 1"
    )
    suspend fun getItem(operationId: String, historyId: Long): HistoryDateFetchItem?

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN itemState = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending, " +
            "COALESCE(SUM(CASE WHEN itemState = 'UPDATED' THEN 1 ELSE 0 END), 0) AS updated, " +
            "COALESCE(SUM(CASE WHEN itemState = 'NO_DATE' THEN 1 ELSE 0 END), 0) AS noDate, " +
            "COALESCE(SUM(CASE WHEN itemState = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed, " +
            "COALESCE(SUM(CASE WHEN itemState = 'SKIPPED' THEN 1 ELSE 0 END), 0) AS skipped, " +
            "COALESCE(SUM(CASE WHEN itemState = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled " +
            "FROM history_date_fetch_items WHERE operationId = :operationId"
    )
    fun observeCounts(operationId: String): Flow<HistoryDateFetchCounts>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN itemState = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending, " +
            "COALESCE(SUM(CASE WHEN itemState = 'UPDATED' THEN 1 ELSE 0 END), 0) AS updated, " +
            "COALESCE(SUM(CASE WHEN itemState = 'NO_DATE' THEN 1 ELSE 0 END), 0) AS noDate, " +
            "COALESCE(SUM(CASE WHEN itemState = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed, " +
            "COALESCE(SUM(CASE WHEN itemState = 'SKIPPED' THEN 1 ELSE 0 END), 0) AS skipped, " +
            "COALESCE(SUM(CASE WHEN itemState = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled " +
            "FROM history_date_fetch_items WHERE operationId = :operationId"
    )
    suspend fun getCounts(operationId: String): HistoryDateFetchCounts

    @Query(
        "UPDATE history_date_fetch_items SET itemState = :state, reasonCode = :reason, " +
            "updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND historyId = :historyId AND itemState = 'PENDING'"
    )
    suspend fun setItemOutcome(
        operationId: String,
        historyId: Long,
        state: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE history_date_fetch_operations SET processedSourceCount = processedSourceCount + 1, " +
            "localHits = localHits + :localHits, cacheHits = cacheHits + :cacheHits, " +
            "extractorLaunches = extractorLaunches + :extractorLaunches, " +
            "compatibilityFallbacks = compatibilityFallbacks + :compatibilityFallbacks, " +
            "elapsedMs = elapsedMs + :elapsedMs, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND state = 'RUNNING'"
    )
    suspend fun recordSourceMetrics(
        operationId: String,
        localHits: Int,
        cacheHits: Int,
        extractorLaunches: Int,
        compatibilityFallbacks: Int,
        elapsedMs: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE history_date_fetch_operations SET cancelRequested = 1, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND state = 'RUNNING'"
    )
    suspend fun requestCancellation(operationId: String, updatedAt: Long): Int

    @Query(
        "UPDATE history_date_fetch_items SET itemState = :state, reasonCode = :reason, " +
            "updatedAt = :updatedAt WHERE operationId = :operationId AND itemState = 'PENDING'"
    )
    suspend fun terminalizePending(
        operationId: String,
        state: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE history_date_fetch_operations SET state = :state, terminalReason = :reason, " +
            "completedAt = :completedAt, updatedAt = :completedAt " +
            "WHERE operationId = :operationId AND state = 'RUNNING'"
    )
    suspend fun finishOperation(
        operationId: String,
        state: String,
        reason: String,
        completedAt: Long,
    ): Int
}
