package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ireum.ytdl.database.models.DownloadPrimarySuccessAuthority

@Dao
interface DownloadPrimarySuccessAuthorityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(authority: DownloadPrimarySuccessAuthority): Long

    @Query(
        "SELECT * FROM download_primary_success_authorities " +
            "WHERE downloadId = :downloadId AND executionId = :executionId LIMIT 1",
    )
    suspend fun getByExecution(downloadId: Long, executionId: String): DownloadPrimarySuccessAuthority?

    @Query(
        "SELECT * FROM download_primary_success_authorities " +
            "WHERE downloadId = :downloadId AND executionId = :executionId LIMIT 1",
    )
    fun getByExecutionBlocking(downloadId: Long, executionId: String): DownloadPrimarySuccessAuthority?

    @Query(
        "SELECT * FROM download_primary_success_authorities " +
            "WHERE downloadId = :downloadId ORDER BY createdAt DESC",
    )
    suspend fun getByDownload(downloadId: Long): List<DownloadPrimarySuccessAuthority>

    @Query(
        "SELECT * FROM download_primary_success_authorities " +
            "WHERE downloadId = :downloadId ORDER BY createdAt DESC",
    )
    fun getByDownloadBlocking(downloadId: Long): List<DownloadPrimarySuccessAuthority>

    @Query(
        "SELECT * FROM download_primary_success_authorities " +
            "WHERE phase IN ('PRIMARY_COMMITTED', 'FINALIZATION_PENDING')",
    )
    suspend fun getPendingFinalization(): List<DownloadPrimarySuccessAuthority>

    @Query(
        "SELECT * FROM download_primary_success_authorities " +
            "WHERE phase IN ('PRIMARY_COMMITTED', 'FINALIZATION_PENDING')",
    )
    fun getPendingFinalizationBlocking(): List<DownloadPrimarySuccessAuthority>

    @Query(
        "UPDATE download_primary_success_authorities SET phase = :phase " +
            "WHERE authorityKey = :authorityKey AND phase != 'FINALIZED'",
    )
    suspend fun markPhase(authorityKey: String, phase: String): Int

    @Query("DELETE FROM download_primary_success_authorities WHERE authorityKey = :authorityKey")
    suspend fun delete(authorityKey: String): Int
}
