package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ireum.ytdl.database.models.HistoryReplacementBarrier

@Dao
interface HistoryReplacementBarrierDao {
    /**
     * The first refusal wins.  A later attempt cannot replace a SourceMismatch
     * with TypeMismatch (or with an ordinary failure).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(barrier: HistoryReplacementBarrier): Long

    @Query("SELECT * FROM history_replacement_barriers WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getByDownloadId(downloadId: Long): HistoryReplacementBarrier?

    @Query("SELECT * FROM history_replacement_barriers WHERE downloadId = :downloadId LIMIT 1")
    fun getByDownloadIdBlocking(downloadId: Long): HistoryReplacementBarrier?

    @Query("SELECT * FROM history_replacement_barriers WHERE downloadId IN (:downloadIds)")
    suspend fun getByDownloadIds(downloadIds: List<Long>): List<HistoryReplacementBarrier>

    @Query("SELECT downloadId FROM history_replacement_barriers WHERE downloadId IN (:downloadIds)")
    suspend fun getDownloadIds(downloadIds: List<Long>): List<Long>

    @Query("DELETE FROM history_replacement_barriers WHERE downloadId IN (:downloadIds)")
    suspend fun deleteForDownloadIds(downloadIds: List<Long>)
}
