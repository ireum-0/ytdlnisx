package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.DownloadPrimarySuccessAuthority
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Shared exact-generation primary-success authority policy. */
internal object DownloadPrimarySuccessAuthorityRepository {
    const val PRIMARY_COMMITTED = "PRIMARY_COMMITTED"
    const val FINALIZATION_PENDING = "FINALIZATION_PENDING"
    const val FINALIZED = "FINALIZED"

    fun key(downloadId: Long, executionId: String): String {
        require(executionId.isNotBlank()) { "Primary success requires an exact execution id" }
        return "$downloadId/$executionId"
    }

    fun fingerprint(command: String): String = MessageDigest.getInstance("SHA-256")
        .digest(command.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    suspend fun recordNoHistory(
        dbManager: DBManager,
        downloadId: Long,
        operationId: String,
        executionId: String,
        semanticFingerprint: String,
        archiveDelta: String,
    ): Boolean {
        val authorityKey = key(downloadId, executionId)
        val existing = forExecution(dbManager, downloadId, executionId)
        if (existing != null) return existing.noHistory && existing.phase != FINALIZED
        return dbManager.downloadPrimarySuccessAuthorityDao.insertIfAbsent(
            DownloadPrimarySuccessAuthority(
                authorityKey = authorityKey,
                downloadId = downloadId,
                operationId = operationId,
                executionId = executionId,
                historyId = 0L,
                noHistory = true,
                semanticFingerprint = semanticFingerprint,
                archiveDelta = archiveDelta,
                phase = PRIMARY_COMMITTED,
                createdAt = System.currentTimeMillis(),
            ),
        ) > 0L
    }

    suspend fun forExecution(
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): DownloadPrimarySuccessAuthority? = dbManager.downloadPrimarySuccessAuthorityDao
        .getByExecution(downloadId, executionId)

    suspend fun isCommitted(
        dbManager: DBManager,
        item: DownloadItem,
    ): Boolean = item.executionId.isNotBlank() &&
        forExecution(dbManager, item.id, item.executionId)?.let { authority ->
            authority.phase != FINALIZED
        } == true

    fun isCommittedBlocking(
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        if (executionId.isBlank()) return false
        val authority = dbManager.downloadPrimarySuccessAuthorityDao
            .getByExecutionBlocking(downloadId, executionId)
        return authority != null && authority.phase != FINALIZED
    }

    fun hasCommittedForDownloadBlocking(
        dbManager: DBManager,
        downloadId: Long,
    ): Boolean = dbManager.downloadPrimarySuccessAuthorityDao
        .getByDownloadBlocking(downloadId)
        .any { it.phase != FINALIZED }

    suspend fun markFinalizationPending(
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val authority = forExecution(dbManager, downloadId, executionId) ?: return false
        return dbManager.downloadPrimarySuccessAuthorityDao
            .markPhase(authority.authorityKey, FINALIZATION_PENDING) == 1 ||
            forExecution(dbManager, downloadId, executionId)?.phase == FINALIZATION_PENDING
    }

    suspend fun retire(
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val authority = forExecution(dbManager, downloadId, executionId) ?: return true
        // Do not mark the row FINALIZED before deletion is acknowledged.  A
        // failed retirement must remain discoverable and must continue to
        // fence producer replay on the next recovery pass.
        return dbManager.downloadPrimarySuccessAuthorityDao.delete(authority.authorityKey) == 1 ||
            forExecution(dbManager, downloadId, executionId) == null
    }
}
