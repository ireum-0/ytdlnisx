package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ireum.ytdl.database.models.FormatAuthorityClock
import com.ireum.ytdl.database.models.FormatForegroundClaim
import com.ireum.ytdl.database.models.FormatLegacyDebt
import com.ireum.ytdl.database.models.FormatRecoveryIssue
import com.ireum.ytdl.database.models.FormatRequestCancellation
import com.ireum.ytdl.database.models.FormatRowAuthority
import kotlinx.coroutines.flow.Flow

@Dao
interface FormatAuthorityDao {
    @Insert suspend fun allocate(value: FormatAuthorityClock = FormatAuthorityClock()): Long
    @Query("DELETE FROM format_authority_clock WHERE id < :current") suspend fun trimClock(current: Long)
    @Query("SELECT * FROM format_row_authorities WHERE kind=:kind AND target=:target")
    suspend fun get(kind: String, target: String): FormatRowAuthority?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: FormatRowAuthority)
    @Query("SELECT * FROM format_row_authorities WHERE owner=:owner")
    suspend fun getOwned(owner: String): List<FormatRowAuthority>
    @Insert suspend fun insertForeground(value: FormatForegroundClaim)
    @Query("SELECT * FROM format_foreground_claims") suspend fun foregroundClaims(): List<FormatForegroundClaim>
    @Query("DELETE FROM format_foreground_claims WHERE id=:id AND session=:session")
    suspend fun deleteForeground(id: String, session: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun retainLegacy(value: FormatLegacyDebt): Long
    @Query("SELECT * FROM format_legacy_debts WHERE requestId=:requestId")
    suspend fun getLegacy(requestId: String): FormatLegacyDebt?
    @Query("SELECT * FROM format_legacy_debts ORDER BY requestId")
    suspend fun legacyDebts(): List<FormatLegacyDebt>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecoveryIssue(value: FormatRecoveryIssue): Long
    @Query("SELECT * FROM format_recovery_issues WHERE requestId=:requestId AND handoffId=:handoffId AND generationId=:generationId AND origin=:origin")
    suspend fun recoveryIssueForOrigin(requestId: String, handoffId: String, generationId: String, origin: String): FormatRecoveryIssue?
    @Query("SELECT * FROM format_recovery_issues WHERE issueId=:issueId")
    suspend fun recoveryIssue(issueId: String): FormatRecoveryIssue?
    @Query("SELECT * FROM format_recovery_issues ORDER BY issueId")
    suspend fun recoveryIssues(): List<FormatRecoveryIssue>
    @Query("SELECT * FROM format_recovery_issues WHERE state='OPEN' ORDER BY issueId")
    fun observeOpenRecoveryIssues(): Flow<List<FormatRecoveryIssue>>
    @Query("UPDATE format_recovery_issues SET state='ACKNOWLEDGED', acknowledgedAt=:now WHERE issueId=:issueId AND requestId=:requestId AND handoffId=:handoffId AND generationId=:generationId AND origin=:origin AND state='OPEN'")
    suspend fun acknowledgeRecoveryIssue(issueId: String, requestId: String, handoffId: String, generationId: String, origin: String, now: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun retainCancellation(value: FormatRequestCancellation): Long
    @Query("SELECT * FROM format_request_cancellations WHERE requestId=:requestId")
    suspend fun getCancellation(requestId: String): FormatRequestCancellation?
    @Query("SELECT * FROM format_request_cancellations WHERE state='PENDING'")
    suspend fun pendingCancellations(): List<FormatRequestCancellation>
    @Query("UPDATE format_request_cancellations SET state='TERMINAL_CONFIRMED' WHERE requestId=:requestId AND handoffId=:handoffId AND generationId=:generationId")
    suspend fun confirmCancellation(requestId: String, handoffId: String, generationId: String): Int
}
