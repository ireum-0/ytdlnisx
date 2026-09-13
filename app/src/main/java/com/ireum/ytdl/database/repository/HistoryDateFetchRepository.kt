package com.ireum.ytdl.database.repository

import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.HistoryDateFetchCounts
import com.ireum.ytdl.database.models.HistoryDateFetchItem
import com.ireum.ytdl.database.models.HistoryDateFetchItemState
import com.ireum.ytdl.database.models.HistoryDateFetchOperation
import com.ireum.ytdl.database.models.HistoryDateFetchOperationState
import com.ireum.ytdl.database.models.KnownMediaPublishedDate
import com.ireum.ytdl.util.HistoryDateFetchProgress
import com.ireum.ytdl.util.HistoryDateLookupOrigin
import com.ireum.ytdl.util.HistoryDateLookupOutcome
import com.ireum.ytdl.util.HistoryDateLookupResult
import com.ireum.ytdl.util.HistoryDateSourceCandidate
import com.ireum.ytdl.util.HistoryDateSourceGrouping
import com.ireum.ytdl.util.HistoryMediaPublishedDateCandidatePolicy
import com.ireum.ytdl.util.MediaPublishedDate
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class HistoryDateFetchRepository(private val database: DBManager) {
    private val dao = database.historyDateFetchDao

    val currentOperation: Flow<HistoryDateFetchOperation?> = dao.observeCurrentOperation()

    fun observeItems(operationId: String): Flow<List<HistoryDateFetchItem>> =
        dao.observeItems(operationId)

    fun observeCounts(operationId: String): Flow<HistoryDateFetchCounts> =
        dao.observeCounts(operationId)

    suspend fun getActiveOperation(): HistoryDateFetchOperation? = dao.getActiveOperation()

    suspend fun getNonterminalOperations(): List<HistoryDateFetchOperation> =
        dao.getNonterminalOperations()

    suspend fun getOperation(operationId: String): HistoryDateFetchOperation? =
        dao.getOperation(operationId)

    suspend fun getPendingItems(operationId: String): List<HistoryDateFetchItem> =
        dao.getPendingItems(operationId)

    suspend fun progress(operationId: String): HistoryDateFetchProgress? {
        val operation = dao.getOperation(operationId) ?: return null
        return HistoryDateFetchProgress(operation, dao.getCounts(operationId))
    }

    suspend fun createOrReconnect(now: Long = System.currentTimeMillis()): HistoryDateFetchOperation =
        database.withTransaction {
            dao.getActiveOperation()?.let { return@withTransaction it }
            val historyCandidates = HistoryMediaPublishedDateCandidatePolicy.select(
                database.historyDao.getItemsWithMissingMediaPublishedAt()
            )
            val sourceCandidates = historyCandidates.map {
                HistoryDateSourceCandidate(it.id, it.url.trim())
            }
            val groups = HistoryDateSourceGrouping.group(sourceCandidates)
            val groupKeyByHistoryId = groups.flatMap { group ->
                group.candidates.map { it.historyId to group.key }
            }.toMap()
            val operation = HistoryDateFetchOperation(
                operationId = UUID.randomUUID().toString(),
                candidateCount = sourceCandidates.size,
                uniqueSourceCount = groups.size,
                duplicateCoalesced = (sourceCandidates.size - groups.size).coerceAtLeast(0),
                createdAt = now,
                updatedAt = now,
            )
            dao.purgeOlderTerminalOperations()
            dao.insertOperation(operation)
            dao.insertItems(
                sourceCandidates.map { candidate ->
                    HistoryDateFetchItem(
                        operationId = operation.operationId,
                        historyId = candidate.historyId,
                        sourceUrlSnapshot = candidate.sourceUrl,
                        sourceGroupKey = groupKeyByHistoryId.getValue(candidate.historyId),
                        updatedAt = now,
                    )
                }
            )
            operation
        }

    suspend fun knownDates(): List<KnownMediaPublishedDate> {
        return buildList {
            addAll(database.historyDao.getKnownMediaPublishedDates())
            addAll(database.downloadDao.getKnownMediaPublishedDates())
            addAll(database.resultDao.getKnownMediaPublishedDates())
        }
    }

    suspend fun checkpointSourceGroup(
        operationId: String,
        items: List<HistoryDateFetchItem>,
        lookup: HistoryDateLookupResult,
        elapsedMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction false
        if (operation.stateValue.isTerminal || operation.cancelRequested) {
            return@withTransaction false
        }

        items.forEach { snapshot ->
            val ledger = dao.getItem(operationId, snapshot.historyId)
                ?: return@forEach
            if (ledger.stateValue != HistoryDateFetchItemState.PENDING) return@forEach
            val current = database.historyDao.getNullableItem(snapshot.historyId)
            val outcome = when {
                current == null -> ItemOutcome(HistoryDateFetchItemState.SKIPPED, REASON_HISTORY_REMOVED)
                MediaPublishedDate.isPresent(current.mediaPublishedAt) ->
                    ItemOutcome(HistoryDateFetchItemState.SKIPPED, REASON_ALREADY_UPDATED)
                current.url.trim() != ledger.sourceUrlSnapshot ->
                    ItemOutcome(HistoryDateFetchItemState.SKIPPED, REASON_SOURCE_CHANGED)
                lookup.outcome is HistoryDateLookupOutcome.Found -> {
                    val updated = database.historyDao.updateMediaPublishedAtIfMissing(
                        id = current.id,
                        normalizedUrl = ledger.sourceUrlSnapshot,
                        mediaPublishedAt = lookup.outcome.mediaPublishedAt,
                    )
                    if (updated == 1) {
                        ItemOutcome(
                            HistoryDateFetchItemState.UPDATED,
                            lookup.origin.name,
                        )
                    } else {
                        ItemOutcome(HistoryDateFetchItemState.SKIPPED, REASON_STALE_SNAPSHOT)
                    }
                }
                lookup.outcome is HistoryDateLookupOutcome.AuthoritativeAbsence ->
                    ItemOutcome(HistoryDateFetchItemState.NO_DATE, lookup.origin.name)
                lookup.outcome is HistoryDateLookupOutcome.RetryableFailure ->
                    ItemOutcome(
                        HistoryDateFetchItemState.FAILED,
                        lookup.failureReason.ifBlank { REASON_RETRYABLE_LOOKUP_FAILURE },
                    )
                lookup.outcome is HistoryDateLookupOutcome.FinalFailure ->
                    ItemOutcome(
                        HistoryDateFetchItemState.FAILED,
                        lookup.failureReason.ifBlank { REASON_FINAL_LOOKUP_FAILURE },
                    )
                else -> ItemOutcome(
                    HistoryDateFetchItemState.FAILED,
                    lookup.failureReason.ifBlank { REASON_UNPROVEN_DATE },
                )
            }
            dao.setItemOutcome(
                operationId = operationId,
                historyId = ledger.historyId,
                state = outcome.state.name,
                reason = outcome.reason,
                updatedAt = now,
            )
        }
        dao.recordSourceMetrics(
            operationId = operationId,
            localHits = if (lookup.origin == HistoryDateLookupOrigin.LOCAL) 1 else 0,
            cacheHits = if (lookup.origin == HistoryDateLookupOrigin.CACHE) 1 else 0,
            extractorLaunches = lookup.extractorLaunches,
            compatibilityFallbacks = lookup.compatibilityFallbacks,
            elapsedMs = elapsedMs,
            updatedAt = now,
        ) == 1
    }

    suspend fun requestCancellation(operationId: String): Boolean =
        dao.requestCancellation(operationId, System.currentTimeMillis()) == 1

    suspend fun finishCancellation(operationId: String): Boolean = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction false
        if (operation.stateValue.isTerminal || !operation.cancelRequested) return@withTransaction false
        val now = System.currentTimeMillis()
        dao.terminalizePending(
            operationId,
            HistoryDateFetchItemState.CANCELLED.name,
            REASON_USER_CANCELLED,
            now,
        )
        dao.finishOperation(
            operationId,
            HistoryDateFetchOperationState.CANCELLED.name,
            REASON_USER_CANCELLED,
            now,
        ) == 1
    }

    suspend fun finishCompleted(operationId: String): Boolean = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction false
        if (operation.stateValue.isTerminal || operation.cancelRequested) return@withTransaction false
        val counts = dao.getCounts(operationId)
        if (counts.pending > 0 || counts.failed > 0) return@withTransaction false
        val now = System.currentTimeMillis()
        dao.finishOperation(
            operationId,
            HistoryDateFetchOperationState.COMPLETED.name,
            "",
            now,
        ) == 1
    }

    internal suspend fun finalizeWorkerRun(
        operationId: String,
    ): HistoryDateFetchOperationState? = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction null
        if (operation.stateValue.isTerminal) return@withTransaction null
        val now = System.currentTimeMillis()
        if (operation.cancelRequested) {
            dao.terminalizePending(
                operationId,
                HistoryDateFetchItemState.CANCELLED.name,
                REASON_USER_CANCELLED,
                now,
            )
            if (
                dao.finishOperation(
                    operationId,
                    HistoryDateFetchOperationState.CANCELLED.name,
                    REASON_USER_CANCELLED,
                    now,
                ) == 1
            ) {
                HistoryDateFetchOperationState.CANCELLED
            } else {
                null
            }
        } else {
            val counts = dao.getCounts(operationId)
            if (counts.pending > 0) return@withTransaction null
            val terminalState = if (counts.failed > 0) {
                HistoryDateFetchOperationState.FAILED
            } else {
                HistoryDateFetchOperationState.COMPLETED
            }
            val terminalReason = when {
                counts.failed == counts.total && counts.failed > 0 -> REASON_ALL_CHILDREN_FAILED
                counts.failed > 0 -> REASON_PARTIAL_FAILURE
                else -> ""
            }
            if (
                dao.finishOperation(
                    operationId,
                    terminalState.name,
                    terminalReason,
                    now,
                ) == 1
            ) {
                terminalState
            } else {
                null
            }
        }
    }

    suspend fun finishFailed(
        operationId: String,
        reason: String = REASON_COORDINATOR_FAILURE,
    ): Boolean = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction false
        if (operation.stateValue.isTerminal) return@withTransaction false
        val now = System.currentTimeMillis()
        val cancelled = operation.cancelRequested
        dao.terminalizePending(
            operationId,
            if (cancelled) HistoryDateFetchItemState.CANCELLED.name else HistoryDateFetchItemState.FAILED.name,
            if (cancelled) REASON_USER_CANCELLED else reason,
            now,
        )
        dao.finishOperation(
            operationId,
            if (cancelled) HistoryDateFetchOperationState.CANCELLED.name else HistoryDateFetchOperationState.FAILED.name,
            if (cancelled) REASON_USER_CANCELLED else reason,
            now,
        ) == 1
    }

    private data class ItemOutcome(
        val state: HistoryDateFetchItemState,
        val reason: String,
    )

    companion object {
        const val REASON_USER_CANCELLED = "USER_CANCELLED"
        const val REASON_COORDINATOR_FAILURE = "COORDINATOR_FAILURE"
        const val REASON_HISTORY_REMOVED = "HISTORY_REMOVED"
        const val REASON_ALREADY_UPDATED = "ALREADY_UPDATED"
        const val REASON_SOURCE_CHANGED = "SOURCE_CHANGED"
        const val REASON_STALE_SNAPSHOT = "STALE_SNAPSHOT"
        const val REASON_UNPROVEN_DATE = "UNPROVEN_DATE"
        const val REASON_RETRYABLE_LOOKUP_FAILURE = "RETRYABLE_LOOKUP_FAILURE"
        const val REASON_FINAL_LOOKUP_FAILURE = "FINAL_LOOKUP_FAILURE"
        const val REASON_RETRY_EXHAUSTED = "RETRY_EXHAUSTED"
        const val REASON_PARTIAL_FAILURE = "PARTIAL_FAILURE"
        const val REASON_ALL_CHILDREN_FAILED = "ALL_CHILDREN_FAILED"
    }
}
