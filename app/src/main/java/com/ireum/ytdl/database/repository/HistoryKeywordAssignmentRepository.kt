package com.ireum.ytdl.database.repository

import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.util.HistoryReplacementSourceIdentity
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
        return db.withTransaction {
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
        return db.withTransaction {
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
    ): HistoryReplacementAuthorization {
        require(historyId > 0L)
        return db.withTransaction {
            authorizeHistoryReplacementInTransaction(
                historyId = historyId,
                expectedSourceUrl = expectedSourceUrl,
                expectedType = expectedType,
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
    ): HistoryReplacementAuthorization = runBlocking {
        authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = expectedSourceUrl,
            expectedType = expectedType,
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
        replacementFactory: (HistoryItem) -> HistoryItem,
    ): HistoryReplacementOutcome {
        require(historyId > 0L)
        return db.withTransaction {
            when (
                val authorization = authorizeHistoryReplacementInTransaction(
                    historyId = historyId,
                    expectedSourceUrl = expectedSourceUrl,
                    expectedType = expectedType,
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
        }
    }

    fun replaceHistoryPreservingAssignmentsAuthorizedBlocking(
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
        replacementFactory: (HistoryItem) -> HistoryItem,
    ): HistoryReplacementOutcome = runBlocking {
        replaceHistoryPreservingAssignmentsAuthorized(
            historyId = historyId,
            expectedSourceUrl = expectedSourceUrl,
            expectedType = expectedType,
            replacementFactory = replacementFactory,
        )
    }

    private fun authorizeHistoryReplacementInTransaction(
        historyId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
    ): HistoryReplacementAuthorization {
        val existingHistory = db.historyDao.getNullableItem(historyId)
            ?: return HistoryReplacementAuthorization.TargetMissing
        if (
            expectedSourceUrl.isBlank() ||
            !HistoryReplacementSourceIdentity.matches(expectedSourceUrl, existingHistory.url)
        ) {
            return HistoryReplacementAuthorization.SourceMismatch
        }
        if (existingHistory.type != expectedType) {
            return HistoryReplacementAuthorization.TypeMismatch
        }
        return HistoryReplacementAuthorization.Authorized(existingHistory)
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
