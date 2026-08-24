package com.ireum.ytdl.database.repository

import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.HistoryReplacementSourceIdentity
import com.ireum.ytdl.util.LowQualityRedownloadCompletionPolicy
import com.ireum.ytdl.util.LowQualityReplacementAuthority
import com.ireum.ytdl.util.storage.HistoryReferenceMutationCoordinator
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

enum class HistoryReplacementResult {
    UPDATED,
    TARGET_MISSING,
    SOURCE_MISMATCH,
    TYPE_MISMATCH,
}

sealed interface HistoryReplacementAuthorization {
    data class Authorized(val target: HistoryItem) : HistoryReplacementAuthorization
    data object TargetMissing : HistoryReplacementAuthorization
    data object SourceMismatch : HistoryReplacementAuthorization
    data object TypeMismatch : HistoryReplacementAuthorization
}

/**
 * Raised when a DownloadWorker reaches a privileged History boundary after a
 * newer execution has claimed the Download row.  The exception is deliberately
 * distinct from an authorization refusal: the stale attempt must stop without
 * turning the newer attempt's row, ledger item, or History target terminal.
 */
class HistoryReplacementExecutionOwnershipLostException(
    val downloadId: Long,
    val expectedExecutionId: String,
    val actualExecutionId: String?,
    val expectedOperationId: String = "",
    val actualOperationId: String? = null,
) : IllegalStateException(
    "History replacement execution ownership lost for download $downloadId"
)

class HistoryReplacementQualityAuthorityLostException(
    val downloadId: Long,
    val cancellationOrigin: Boolean = false,
) : IllegalStateException(
    "Low-quality History replacement authority is missing or terminal for download $downloadId"
)

/**
 * The History refusal was authoritative, but its first durable carrier could
 * not be inserted or verified.  Callers must retain [authorization] and
 * [issue] while surfacing [persistenceFailure]; the database must never claim
 * a barrier that Room did not actually persist.
 */
class HistoryReplacementRefusalPersistenceException(
    val authorization: HistoryReplacementAuthorization,
    val issue: DownloadIssue,
    val persistenceFailure: Exception,
) : IllegalStateException(
    "History replacement refusal could not be durably recorded",
    persistenceFailure,
)

/**
 * Keeps the first History refusal typed while its durable carrier is being
 * inserted and verified.  The caller must not turn a failure from either
 * operation into a generic History write issue.
 */
internal suspend fun persistHistoryReplacementRefusalOrThrow(
    authorization: HistoryReplacementAuthorization,
    issue: DownloadIssue,
    persist: suspend () -> Unit,
    verify: suspend () -> HistoryReplacementAuthorization,
): HistoryReplacementAuthorization {
    try {
        persist()
        return verify()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (persistenceFailure: Exception) {
        throw HistoryReplacementRefusalPersistenceException(
            authorization = authorization,
            issue = issue,
            persistenceFailure = persistenceFailure,
        )
    }
}

sealed interface HistoryReplacementOutcome {
    data class Updated(val previousTarget: HistoryItem) : HistoryReplacementOutcome
    data object TargetMissing : HistoryReplacementOutcome
    data object SourceMismatch : HistoryReplacementOutcome
    data object TypeMismatch : HistoryReplacementOutcome
}

/**
 * The only application-level writer for HistoryItem.keywords.
 *
 * Assignment rows are authoritative. HistoryItem.keywords is updated in the same
 * transaction as a materialized compatibility projection for existing readers.
 */
class HistoryKeywordAssignmentRepository(private val db: DBManager) {
    private companion object {
        const val HISTORY_REPLACEMENT_COMMITTED_REASON = "HISTORY_REPLACEMENT_COMMITTED"
    }

    private val dao = db.automaticKeywordRuleDao

    suspend fun initializeManualAssignments(historyItemId: Long, keywords: String) {
        replaceManualKeywords(historyItemId, AutomaticKeywordNormalizer.parseKeywords(keywords))
    }

    suspend fun replaceManualKeywords(historyItemId: Long, keywords: Collection<String>) {
        replaceSourceKeywords(
            historyItemId,
            HistoryKeywordAssignmentSources.MANUAL,
            HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID,
            keywords
        )
    }

    suspend fun addManualKeywords(historyItemId: Long, keywords: Collection<String>) {
        db.withTransaction {
            val existing = dao.getAssignmentsForHistorySource(
                historyItemId,
                HistoryKeywordAssignmentSources.MANUAL,
                HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID
            ).map { it.keyword }
            replaceSourceKeywordsInTransaction(
                historyItemId,
                HistoryKeywordAssignmentSources.MANUAL,
                HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID,
                existing + keywords
            )
        }
    }

    suspend fun removeEditableKeywords(historyItemId: Long, normalizedKeywords: Set<String>) {
        if (normalizedKeywords.isEmpty()) return
        db.withTransaction {
            dao.getAssignmentsForHistory(historyItemId)
                .filter { it.sourceType.isUserEditableSource() }
                .groupBy { it.sourceType to it.sourceId }
                .forEach { (source, assignments) ->
                    replaceSourceKeywordsInTransaction(
                        historyItemId,
                        source.first,
                        source.second,
                        assignments
                            .filterNot { it.normalizedKeyword in normalizedKeywords }
                            .map { it.keyword }
                    )
                }
        }
    }

    /**
     * Adapts the legacy editor, which displays the materialized union, without
     * converting already-automatic keywords into manual assignments.
     */
    suspend fun updateManualFromMaterializedEditor(
        historyItemId: Long,
        requestedKeywords: Collection<String>
    ): Int {
        return db.withTransaction {
            // A stale UI target must not create assignment state after the
            // History row has been deleted.  Keep the existence check inside
            // the same transaction as the assignment/materialization writes.
            if (db.historyDao.getNullableItem(historyItemId) == null) {
                return@withTransaction 0
            }
            val requested = requestedKeywords
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter(String::isNotBlank)
                .distinctBy(AutomaticKeywordNormalizer::normalizeKeyword)
            val requestedByKey = requested.associateBy(AutomaticKeywordNormalizer::normalizeKeyword)
            val all = dao.getAssignmentsForHistory(historyItemId)
            val currentUnionKeys = all.mapTo(hashSetOf()) { it.normalizedKeyword }
            val existingManualKeys = all.asSequence()
                .filter {
                    it.sourceType == HistoryKeywordAssignmentSources.MANUAL &&
                        it.sourceId == HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID
                }
                .map { it.normalizedKeyword }
                .toSet()
            val nextManual = requested.filter { keyword ->
                val key = AutomaticKeywordNormalizer.normalizeKeyword(keyword)
                key in existingManualKeys || key !in currentUnionKeys
            }
            replaceSourceKeywordsInTransaction(
                historyItemId,
                HistoryKeywordAssignmentSources.MANUAL,
                HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID,
                nextManual
            )
            all.asSequence()
                .filter {
                    it.sourceType == HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE
                }
                .groupBy { it.sourceId }
                .forEach { (sourceId, assignments) ->
                    replaceSourceKeywordsInTransaction(
                        historyItemId,
                        HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE,
                        sourceId,
                        assignments
                            .filter { it.normalizedKeyword in requestedByKey }
                            .map { it.keyword }
                    )
                }
            all.asSequence()
                .filter { it.sourceType == HistoryKeywordAssignmentSources.RULE }
                .map { it.normalizedKeyword }
                .distinct()
                .count { it !in requestedByKey }
        }
    }

    suspend fun removeRuleAssignmentsForHistory(historyItemId: Long) {
        db.withTransaction {
            dao.getAssignmentsForHistory(historyItemId)
                .asSequence()
                .filter { it.sourceType == HistoryKeywordAssignmentSources.RULE }
                .map { it.sourceId }
                .distinct()
                .forEach { ruleId ->
                    dao.deleteAssignmentsForHistorySource(
                        historyItemId,
                        HistoryKeywordAssignmentSources.RULE,
                        ruleId
                    )
                }
            materializeInTransaction(historyItemId)
        }
    }

    suspend fun replaceSourceKeywords(
        historyItemId: Long,
        sourceType: String,
        sourceId: Long,
        keywords: Collection<String>
    ) {
        requireSourceIdentity(sourceType, sourceId)
        db.withTransaction {
            replaceSourceKeywordsInTransaction(historyItemId, sourceType, sourceId, keywords)
        }
    }

    suspend fun removeSourceAssignments(sourceType: String, sourceId: Long) {
        requireSourceIdentity(sourceType, sourceId)
        db.withTransaction {
            val historyIds = dao.getHistoryIdsForSource(sourceType, sourceId)
            dao.deleteAssignmentsForSource(sourceType, sourceId)
            historyIds.forEach { materializeInTransaction(it) }
        }
    }

    suspend fun replaceRuleAssignmentsForExistingHistories(
        ruleId: Long,
        keywords: Collection<String>
    ) {
        db.withTransaction {
            dao.getHistoryIdsForSource(HistoryKeywordAssignmentSources.RULE, ruleId).forEach {
                replaceSourceKeywordsInTransaction(
                    it,
                    HistoryKeywordAssignmentSources.RULE,
                    ruleId,
                    keywords
                )
            }
        }
    }

    suspend fun deleteRuleAndAssignments(ruleId: Long) {
        db.withTransaction {
            val historyIds = dao.getHistoryIdsForSource(
                HistoryKeywordAssignmentSources.RULE,
                ruleId
            )
            dao.deleteAssignmentsForSource(HistoryKeywordAssignmentSources.RULE, ruleId)
            dao.deleteRule(ruleId)
            historyIds.forEach { materializeInTransaction(it) }
        }
    }

    suspend fun mergeHistoryAssignments(fromHistoryItemId: Long, toHistoryItemId: Long) {
        if (fromHistoryItemId == toHistoryItemId) return
        db.withTransaction {
            val copied = dao.getAssignmentsRaw(fromHistoryItemId).map {
                it.copy(historyItemId = toHistoryItemId)
            }
            if (copied.isNotEmpty()) dao.insertAssignments(copied)
            materializeInTransaction(toHistoryItemId)
        }
    }

    suspend fun insertHistory(item: HistoryItem): Long {
        val manualKeywords = AutomaticKeywordNormalizer.parseKeywords(item.keywords)
        val videoKey = AutomaticKeywordNormalizer.videoKey(item.url)
        return HistoryReferenceMutationCoordinator.withLock {
            db.withTransaction {
            val id = db.historyDao.insertAndGetIdRaw(item.copy(keywords = ""))
            replaceSourceKeywordsInTransaction(
                id,
                HistoryKeywordAssignmentSources.MANUAL,
                HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID,
                manualKeywords
            )
            if (videoKey.isNotBlank()) {
                db.automaticKeywordRuleDao.getEnabledRulesForVideoKey(videoKey).forEach { rule ->
                    replaceSourceKeywordsInTransaction(
                        id,
                        HistoryKeywordAssignmentSources.RULE,
                        rule.id,
                        db.automaticKeywordRuleDao.getRuleKeywords(rule.id).map { it.keyword }
                    )
                }
            }
            id
            }
        }
    }

    suspend fun snapshotAssignments(historyItemId: Long): List<HistoryKeywordAssignment> =
        dao.getAssignmentsRaw(historyItemId)

    /**
     * Restores a deleted History row together with its authoritative assignment rows.
     * This is intentionally separate from insertHistory(), whose incoming keywords
     * represent a new manual/import source.
     */
    suspend fun restoreHistory(
        item: HistoryItem,
        assignmentSnapshot: List<HistoryKeywordAssignment>
    ): Long {
        require(item.id > 0)
        return HistoryReferenceMutationCoordinator.withLock {
            db.withTransaction {
            db.historyDao.insertRaw(item.copy(keywords = ""))
            val existingRuleIds = assignmentSnapshot.asSequence()
                .filter { it.sourceType == HistoryKeywordAssignmentSources.RULE }
                .map { it.sourceId }
                .distinct()
                .toList()
                .mapNotNull { ruleId -> dao.getRule(ruleId)?.let { ruleId } }
                .toSet()
            val restorableAssignments = assignmentSnapshot.filter {
                it.historyItemId == item.id &&
                    (
                        it.sourceType != HistoryKeywordAssignmentSources.RULE ||
                            it.sourceId in existingRuleIds
                        )
            }
            if (restorableAssignments.isNotEmpty()) {
                dao.insertAssignments(restorableAssignments)
            }
            materializeInTransaction(item.id)
            item.id
            }
        }
    }

    suspend fun restoreAssignments(
        historyItemId: Long,
        assignmentSnapshot: List<HistoryKeywordAssignment>,
        preserveExistingRuleAssignments: Boolean = false
    ) {
        db.withTransaction {
            val existingRuleAssignments = if (preserveExistingRuleAssignments) {
                dao.getAssignmentsRaw(historyItemId).filter {
                    it.sourceType == HistoryKeywordAssignmentSources.RULE
                }
            } else {
                emptyList()
            }
            dao.deleteAssignmentsForHistory(historyItemId)
            val restorable = assignmentSnapshot
                .filter { it.historyItemId == historyItemId }
                .onEach { requireSourceIdentity(it.sourceType, it.sourceId) }
            val merged = restorable + existingRuleAssignments
            if (merged.isNotEmpty()) dao.insertAssignments(merged)
            materializeInTransaction(historyItemId)
        }
    }

    /**
     * Compatibility wrapper for callers that already have a complete replacement row.
     * The target URL and type are still checked inside the replacement transaction; an
     * ID by itself is never sufficient authority.
     */
    suspend fun replaceHistoryPreservingAssignments(item: HistoryItem): HistoryReplacementResult {
        return when (
            replaceHistoryPreservingAssignmentsAuthorized(
                historyId = item.id,
                expectedSourceUrl = item.url,
                expectedType = item.type
            ) { existing -> item.copy(id = existing.id) }
        ) {
            is HistoryReplacementOutcome.Updated -> HistoryReplacementResult.UPDATED
            HistoryReplacementOutcome.TargetMissing -> HistoryReplacementResult.TARGET_MISSING
            HistoryReplacementOutcome.SourceMismatch -> HistoryReplacementResult.SOURCE_MISMATCH
            HistoryReplacementOutcome.TypeMismatch -> HistoryReplacementResult.TYPE_MISMATCH
        }
    }

    /**
     * Validates a replacement target against the current row snapshot.  Callers must
     * use the returned snapshot for any target-derived read or cleanup decision.
     */
    suspend fun authorizeHistoryReplacement(
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementDownloadId: Long = 0L,
        replacementOperationId: String = "",
        expectedExecutionId: String = "",
    ): HistoryReplacementAuthorization {
        require(historyId > 0L)
        return db.withTransaction {
            authorizeHistoryReplacementInTransaction(
                historyId = historyId,
                expectedSourceUrl = expectedSourceUrl,
                expectedType = expectedType,
                replacementDownloadId = replacementDownloadId,
                replacementOperationId = replacementOperationId,
                expectedExecutionId = expectedExecutionId,
            )
        }
    }

    /**
     * Synchronous adapter for already-IO-bound worker code.  Keeping the suspend
     * transaction out of DownloadWorker's large coroutine state machine avoids
     * introducing another large continuation there.
     */
    fun authorizeHistoryReplacementBlocking(
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementDownloadId: Long = 0L,
        replacementOperationId: String = "",
        expectedExecutionId: String = "",
    ): HistoryReplacementAuthorization = runBlocking {
        authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = expectedSourceUrl,
            expectedType = expectedType,
            replacementDownloadId = replacementDownloadId,
            replacementOperationId = replacementOperationId,
            expectedExecutionId = expectedExecutionId,
        )
    }

    /**
     * Validates and replaces a target in one Room transaction.  The factory runs from
     * the validated current snapshot so concurrent changes cannot be overwritten with
     * a stale worker-side copy.  Updated returns exactly the snapshot that was replaced.
     */
    suspend fun replaceHistoryPreservingAssignmentsAuthorized(
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementDownloadId: Long = 0L,
        replacementOperationId: String = "",
        expectedExecutionId: String = "",
        replacementFactory: (HistoryItem) -> HistoryItem,
    ): HistoryReplacementOutcome {
        require(historyId > 0L)
        return HistoryReferenceMutationCoordinator.withLock {
            db.withTransaction {
            val outcome = when (
                val authorization = authorizeHistoryReplacementInTransaction(
                    historyId = historyId,
                    expectedSourceUrl = expectedSourceUrl,
                    expectedType = expectedType,
                    replacementDownloadId = replacementDownloadId,
                    replacementOperationId = replacementOperationId,
                    expectedExecutionId = expectedExecutionId,
                )
            ) {
                is HistoryReplacementAuthorization.Authorized -> {
                    val existingHistory = authorization.target
                    val replacement = replacementFactory(existingHistory).copy(
                        id = existingHistory.id,
                        keywords = ""
                    )
                    if (
                        replacement.url.isBlank() ||
                        !HistoryReplacementSourceIdentity.matches(expectedSourceUrl, replacement.url)
                    ) {
                        HistoryReplacementOutcome.SourceMismatch
                    } else if (replacement.type != expectedType) {
                        HistoryReplacementOutcome.TypeMismatch
                    } else {
                        val existingAssignments = dao.getAssignmentsRaw(existingHistory.id)
                        val legacyManual = if (existingAssignments.isEmpty()) {
                            AutomaticKeywordNormalizer.parseKeywords(existingHistory.keywords)
                        } else {
                            emptyList()
                        }
                        if (db.historyDao.updateRaw(replacement) != 1) {
                            HistoryReplacementOutcome.TargetMissing
                        } else {
                            markQualityReplacementCommittedInTransaction(
                                replacementDownloadId = replacementDownloadId,
                            )
                            if (legacyManual.isNotEmpty()) {
                                replaceSourceKeywordsInTransaction(
                                    existingHistory.id,
                                    HistoryKeywordAssignmentSources.MANUAL,
                                    HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID,
                                    legacyManual
                                )
                            } else {
                                materializeInTransaction(existingHistory.id)
                            }
                            HistoryReplacementOutcome.Updated(existingHistory)
                        }
                    }
                }
                HistoryReplacementAuthorization.TargetMissing ->
                    HistoryReplacementOutcome.TargetMissing
                HistoryReplacementAuthorization.SourceMismatch ->
                    HistoryReplacementOutcome.SourceMismatch
                HistoryReplacementAuthorization.TypeMismatch ->
                    HistoryReplacementOutcome.TypeMismatch
            }
            durableRefusalOutcome(
                outcome = outcome,
                historyId = historyId,
                expectedSourceUrl = expectedSourceUrl,
                expectedType = expectedType,
                replacementDownloadId = replacementDownloadId,
                replacementOperationId = replacementOperationId,
            )
            }
        }
    }

    /**
     * History replacement and its linked low-quality success fact are one
     * semantic commit.  The Download row is intentionally finalized later by
     * the owner, but a process death after this transaction cannot turn a
     * committed History replacement back into a cancelled/failed child.
     */
    private suspend fun markQualityReplacementCommittedInTransaction(
        replacementDownloadId: Long,
    ) {
        val marker = HistoryRedownloadMarker.parse(
            db.downloadDao.getNullableDownloadById(replacementDownloadId)?.playlistURL
        )
            ?.takeIf { it.isQualityReplacement }
            ?: return
        val ledgerDao = db.lowQualityRedownloadDao
        val ledgerItem = ledgerDao.getItemByDownloadId(replacementDownloadId)
            ?: error("Quality replacement lost its linked low-quality item before History commit")
        val operation = ledgerDao.getOperation(ledgerItem.operationId)
            ?: error("Quality replacement lost its low-quality operation before History commit")
        check(
            ledgerItem.historyId == marker.historyId &&
                !ledgerItem.stateValue.isTerminal &&
                !operation.stateValue.isTerminal &&
                !operation.cancelRequested
        ) {
            "Quality replacement authority changed before History commit"
        }
        check(
            ledgerDao.markHistoryReplacementCommitted(
                downloadId = replacementDownloadId,
                operationId = ledgerItem.operationId,
                reason = HISTORY_REPLACEMENT_COMMITTED_REASON,
                updatedAt = System.currentTimeMillis(),
            ) == 1
        ) {
            "Quality replacement lost linked-child ownership at History commit"
        }
        LowQualityRedownloadCompletionPolicy.terminalState(
            operation,
            ledgerDao.getItems(ledgerItem.operationId),
        )?.let { finalState ->
            check(
                ledgerDao.finishOperation(
                    operationId = ledgerItem.operationId,
                    state = finalState.name,
                    reason = HISTORY_REPLACEMENT_COMMITTED_REASON,
                    completedAt = System.currentTimeMillis(),
                ) == 1
            ) { "Quality replacement lost low-quality operation ownership at History commit" }
        }
    }

    fun replaceHistoryPreservingAssignmentsAuthorizedBlocking(
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementDownloadId: Long = 0L,
        replacementOperationId: String = "",
        expectedExecutionId: String = "",
        replacementFactory: (HistoryItem) -> HistoryItem,
    ): HistoryReplacementOutcome = runBlocking {
        replaceHistoryPreservingAssignmentsAuthorized(
            historyId = historyId,
            expectedSourceUrl = expectedSourceUrl,
            expectedType = expectedType,
            replacementFactory = replacementFactory,
            replacementDownloadId = replacementDownloadId,
            replacementOperationId = replacementOperationId,
            expectedExecutionId = expectedExecutionId,
        )
    }

    private suspend fun authorizeHistoryReplacementInTransaction(
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementDownloadId: Long,
        replacementOperationId: String,
        expectedExecutionId: String,
    ): HistoryReplacementAuthorization {
        val replacementDownload = assertReplacementExecutionOwned(
            replacementDownloadId = replacementDownloadId,
            expectedOperationId = replacementOperationId,
            expectedExecutionId = expectedExecutionId,
        )
        if (replacementDownloadId > 0L) {
            db.historyReplacementBarrierDao.getByDownloadId(replacementDownloadId)?.let { barrier ->
                return authorizationForPersistedRefusal(barrier.issueCode)
            }
            // The Download diagnostic projection is the crash-safe fallback
            // for the narrow window in which the first barrier insert or its
            // read-back failed.  It is consulted before the live History row
            // so a process restart cannot reinterpret the same privileged
            // marker as a fresh authorized replacement.
            replacementDownload
                ?.lastIssueCode
                ?.takeIf { it.isNotBlank() }
                ?.let { issueCode ->
                    HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(issueCode)
                        ?.let { issue ->
                            return authorizationForPersistedRefusal(issue.code.name)
                        }
                }
            val marker = HistoryRedownloadMarker.parse(
                replacementDownload?.playlistURL
            )
            if (marker?.isQualityReplacement == true) {
                val ledgerItem = db.lowQualityRedownloadDao
                    .getItemByDownloadId(replacementDownloadId)
                val operation = ledgerItem?.let {
                    db.lowQualityRedownloadDao.getOperation(it.operationId)
                }
                val cancellationOrigin = operation?.cancelRequested == true ||
                    ledgerItem?.stateValue == com.ireum.ytdl.database.models.LowQualityRedownloadItemState.CANCELLATION_REQUESTED
                if (
                    !LowQualityReplacementAuthority.isCoherent(
                        marker = marker,
                        item = ledgerItem,
                        operation = operation,
                        expectedDownloadId = replacementDownloadId,
                        expectedSourceUrl = expectedSourceUrl,
                        expectedType = expectedType,
                    )
                ) {
                    throw HistoryReplacementQualityAuthorityLostException(
                        downloadId = replacementDownloadId,
                        cancellationOrigin = cancellationOrigin,
                    )
                }
            }
        }
        val existingHistory = db.historyDao.getNullableItem(historyId)
            ?: return durableRefusalAuthorization(
                authorization = HistoryReplacementAuthorization.TargetMissing,
                historyId = historyId,
                expectedSourceUrl = expectedSourceUrl,
                expectedType = expectedType,
                replacementDownloadId = replacementDownloadId,
                replacementOperationId = replacementOperationId,
            )
        if (
            expectedSourceUrl.isBlank() ||
            !HistoryReplacementSourceIdentity.matches(expectedSourceUrl, existingHistory.url)
        ) {
            return durableRefusalAuthorization(
                authorization = HistoryReplacementAuthorization.SourceMismatch,
                historyId = historyId,
                expectedSourceUrl = expectedSourceUrl,
                expectedType = expectedType,
                replacementDownloadId = replacementDownloadId,
                replacementOperationId = replacementOperationId,
            )
        }
        if (existingHistory.type != expectedType) {
            return durableRefusalAuthorization(
                authorization = HistoryReplacementAuthorization.TypeMismatch,
                historyId = historyId,
                expectedSourceUrl = expectedSourceUrl,
                expectedType = expectedType,
                replacementDownloadId = replacementDownloadId,
                replacementOperationId = replacementOperationId,
            )
        }
        return HistoryReplacementAuthorization.Authorized(existingHistory)
    }

    private suspend fun assertReplacementExecutionOwned(
        replacementDownloadId: Long,
        expectedOperationId: String,
        expectedExecutionId: String,
    ): com.ireum.ytdl.database.models.DownloadItem? {
        if (replacementDownloadId <= 0L) return null
        val current = db.downloadDao.getNullableDownloadById(replacementDownloadId)
        val executionOwned = expectedExecutionId.isBlank() || (
            current != null &&
                current.executionId == expectedExecutionId &&
                current.status in setOf("Active", "PostProcessing")
            )
        val downloadLineageOwned = expectedOperationId.isNotBlank() &&
            current?.operationId == expectedOperationId
        if (
            current == null ||
            !executionOwned ||
            !downloadLineageOwned
        ) {
            throw HistoryReplacementExecutionOwnershipLostException(
                downloadId = replacementDownloadId,
                expectedExecutionId = expectedExecutionId,
                actualExecutionId = current?.executionId,
                expectedOperationId = expectedOperationId,
                actualOperationId = current?.operationId,
            )
        }
        return current
    }

    private fun authorizationForPersistedRefusal(
        issueCode: String,
    ): HistoryReplacementAuthorization = when (issueCode) {
        DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name ->
            HistoryReplacementAuthorization.SourceMismatch
        DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH.name ->
            HistoryReplacementAuthorization.TypeMismatch
        DownloadIssueCode.HISTORY_TARGET_DELETED.name ->
            HistoryReplacementAuthorization.TargetMissing
        else -> error("Invalid History replacement barrier issue $issueCode")
    }

    private suspend fun durableRefusalAuthorization(
        authorization: HistoryReplacementAuthorization,
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementDownloadId: Long,
        replacementOperationId: String,
    ): HistoryReplacementAuthorization {
        if (replacementDownloadId <= 0L) return authorization
        val issue = when (authorization) {
            HistoryReplacementAuthorization.TargetMissing ->
                HistoryReplacementDiagnostic.targetDeletedIssue()
            HistoryReplacementAuthorization.SourceMismatch ->
                HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
            HistoryReplacementAuthorization.TypeMismatch ->
                HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE)
            is HistoryReplacementAuthorization.Authorized ->
                error("Authorized History result is not a refusal")
        }
        val barrierDao = db.historyReplacementBarrierDao
        return persistHistoryReplacementRefusalOrThrow(
            authorization = authorization,
            issue = issue,
            persist = {
                barrierDao.insertIfAbsent(
                    HistoryReplacementBarrier(
                        downloadId = replacementDownloadId,
                        operationId = replacementOperationId,
                        historyId = historyId,
                        expectedSourceUrl = expectedSourceUrl,
                        expectedType = expectedType.name,
                        issueCode = issue.code.name,
                        issueStage = issue.stage.name,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            },
            verify = {
                val persisted = barrierDao.getByDownloadId(replacementDownloadId)
                    ?: error("History replacement refusal barrier was not durably recorded")
                authorizationForPersistedRefusal(persisted.issueCode)
            },
        )
    }

    private suspend fun durableRefusalOutcome(
        outcome: HistoryReplacementOutcome,
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementDownloadId: Long,
        replacementOperationId: String,
    ): HistoryReplacementOutcome {
        if (replacementDownloadId <= 0L) return outcome
        val authorization = when (outcome) {
            HistoryReplacementOutcome.SourceMismatch ->
                HistoryReplacementAuthorization.SourceMismatch
            HistoryReplacementOutcome.TypeMismatch ->
                HistoryReplacementAuthorization.TypeMismatch
            HistoryReplacementOutcome.TargetMissing ->
                HistoryReplacementAuthorization.TargetMissing
            is HistoryReplacementOutcome.Updated -> return outcome
        }
        return when (
            durableRefusalAuthorization(
                authorization = authorization,
                historyId = historyId,
                expectedSourceUrl = expectedSourceUrl,
                expectedType = expectedType,
                replacementDownloadId = replacementDownloadId,
                replacementOperationId = replacementOperationId,
            )
        ) {
            HistoryReplacementAuthorization.SourceMismatch -> HistoryReplacementOutcome.SourceMismatch
            HistoryReplacementAuthorization.TypeMismatch -> HistoryReplacementOutcome.TypeMismatch
            HistoryReplacementAuthorization.TargetMissing -> HistoryReplacementOutcome.TargetMissing
            is HistoryReplacementAuthorization.Authorized -> error("Invalid History replacement refusal barrier")
        }
    }

    suspend fun materialize(historyItemId: Long) {
        db.withTransaction { materializeInTransaction(historyItemId) }
    }

    private suspend fun replaceSourceKeywordsInTransaction(
        historyItemId: Long,
        sourceType: String,
        sourceId: Long,
        keywords: Collection<String>
    ) {
        requireSourceIdentity(sourceType, sourceId)
        dao.deleteAssignmentsForHistorySource(historyItemId, sourceType, sourceId)
        val seen = linkedSetOf<String>()
        val now = System.currentTimeMillis()
        val assignments = keywords.mapIndexedNotNull { index, raw ->
            val display = raw.trim().replace(Regex("\\s+"), " ")
            val normalized = AutomaticKeywordNormalizer.normalizeKeyword(display)
            if (normalized.isBlank() || !seen.add(normalized)) null else HistoryKeywordAssignment(
                historyItemId = historyItemId,
                normalizedKeyword = normalized,
                keyword = display,
                sourceType = sourceType,
                sourceId = sourceId,
                position = index,
                createdAt = now
            )
        }
        if (assignments.isNotEmpty()) dao.insertAssignments(assignments)
        materializeInTransaction(historyItemId)
    }

    private suspend fun materializeInTransaction(historyItemId: Long) {
        db.historyDao.updateKeywordsMaterialized(
            historyItemId,
            KeywordAssignmentMaterializer.materialize(dao.getAssignmentsForHistory(historyItemId))
        )
    }

    private fun requireSourceIdentity(sourceType: String, sourceId: Long) {
        require(sourceType.isNotBlank()) { "Keyword assignment source type must not be blank" }
        require(sourceId >= 0L) { "Keyword assignment source ID must be non-negative" }
        if (sourceType == HistoryKeywordAssignmentSources.MANUAL) {
            require(sourceId == HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID)
        } else {
            require(sourceId > 0L) { "Non-manual keyword assignment sources require a persistent ID" }
        }
    }

    private fun String.isUserEditableSource(): Boolean =
        this == HistoryKeywordAssignmentSources.MANUAL ||
            this == HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE
}
