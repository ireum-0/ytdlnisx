package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AutomaticKeywordRuleSummary
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.work.WorkManagerHandoffRecovery
import kotlinx.coroutines.flow.Flow

data class AutomaticKeywordRuleInput(
    val id: Long = 0,
    val playlistUrl: String,
    val playlistName: String,
    val keywords: List<String>,
    val enabled: Boolean,
    val applyToExistingVideos: Boolean
)

class AutomaticKeywordRuleRepository(
    private val context: Context,
    private val db: DBManager = DBManager.getInstance(context)
) {
    private val dao = db.automaticKeywordRuleDao
    private val assignments = HistoryKeywordAssignmentRepository(db)
    val summaries: Flow<List<AutomaticKeywordRuleSummary>> = dao.observeRuleSummaries()

    suspend fun getRule(ruleId: Long): AutomaticKeywordRule? = dao.getRule(ruleId)
    suspend fun getKeywords(ruleId: Long): List<String> =
        dao.getRuleKeywords(ruleId).map { it.keyword }

    suspend fun save(input: AutomaticKeywordRuleInput): Long {
        val (ruleId, ownerChange) = RestoreMutationAdmission.withOrdinaryMutation(context) {
            val conditionValue = requireNotNull(
                AutomaticKeywordNormalizer.canonicalPlaylistUrl(input.playlistUrl)
            ) { "Invalid playlist URL" }
            val conditionKey = requireNotNull(
                AutomaticKeywordNormalizer.playlistConditionKey(conditionValue)
            ) { "Invalid playlist URL" }
            val parsedKeywords = input.keywords
                .flatMap(AutomaticKeywordNormalizer::parseKeywords)
                .distinctBy(AutomaticKeywordNormalizer::normalizeKeyword)
            require(parsedKeywords.isNotEmpty()) { "At least one keyword is required" }

            val oldRule = input.id.takeIf { it > 0 }?.let { dao.getRule(it) }
            val conditionChanged = oldRule != null && oldRule.conditionKey != conditionKey
            val syncWasActive = oldRule?.manualSyncStatus in setOf(
                AutomaticKeywordSyncStatus.QUEUED,
                AutomaticKeywordSyncStatus.RUNNING,
            )
            val nextBaselineComplete =
                if (conditionChanged) false else oldRule?.baselineComplete ?: false
            val needsInitialSync = oldRule == null || conditionChanged || !nextBaselineComplete
            val pendingApplyToExisting = input.applyToExistingVideos ||
                (syncWasActive && oldRule?.pendingApplyToExisting == true)
            val shouldSchedule = input.enabled &&
                (needsInitialSync || pendingApplyToExisting || syncWasActive)

            val transactionResult = db.withTransaction {
                if (conditionChanged) {
                    assignments.removeSourceAssignments(
                        HistoryKeywordAssignmentSources.RULE,
                        requireNotNull(oldRule).id,
                    )
                }
                val scheduledAt = System.currentTimeMillis()
                val next = AutomaticKeywordRule(
                    id = oldRule?.id ?: 0,
                    conditionValue = conditionValue,
                    conditionKey = conditionKey,
                    playlistName = input.playlistName.trim().ifBlank { conditionValue },
                    enabled = input.enabled,
                    revision = (oldRule?.revision ?: 0) + 1,
                    baselineComplete = nextBaselineComplete,
                    pendingApplyToExisting = pendingApplyToExisting,
                    manualSyncStatus = when {
                        shouldSchedule -> AutomaticKeywordSyncStatus.QUEUED
                        syncWasActive -> AutomaticKeywordSyncStatus.NEVER
                        else -> oldRule?.manualSyncStatus ?: AutomaticKeywordSyncStatus.NEVER
                    },
                    manualSyncAt = if (shouldSchedule) scheduledAt else oldRule?.manualSyncAt ?: 0,
                    manualSyncError = if (shouldSchedule) {
                        AutomaticKeywordSyncError.NONE
                    } else {
                        oldRule?.manualSyncError ?: AutomaticKeywordSyncError.NONE
                    },
                    discoveryStatus = oldRule?.discoveryStatus ?: AutomaticKeywordSyncStatus.NEVER,
                    discoveryAt = oldRule?.discoveryAt ?: 0,
                    discoveryError = oldRule?.discoveryError ?: AutomaticKeywordSyncError.NONE,
                )
                val id = if (oldRule == null) dao.insertRule(next) else {
                    dao.updateRule(next)
                    next.id
                }
                if (conditionChanged) dao.deleteVideoMatches(id)
                dao.deleteRuleKeywords(id)
                dao.insertRuleKeywords(parsedKeywords.mapIndexed { position, keyword ->
                    AutomaticKeywordRuleKeyword(
                        ruleId = id,
                        normalizedKeyword = AutomaticKeywordNormalizer.normalizeKeyword(keyword),
                        keyword = keyword,
                        position = position,
                    )
                })
                assignments.replaceRuleAssignmentsForExistingHistories(id, parsedKeywords)

                val ownerChange = if (shouldSchedule) {
                    WorkManagerHandoffRecovery.stageAutomaticKeywordSyncWithinTransaction(
                        db,
                        id,
                        next.revision,
                        if (pendingApplyToExisting) "APPLY_EXISTING" else "BASELINE_ONLY",
                    )
                } else {
                    WorkManagerHandoffRecovery.supersedeAutomaticKeywordSyncWithinTransaction(db, id)
                }
                id to ownerChange
            }
            AutomaticKeywordObservationCoverage(context, db).reconcile()
            transactionResult
        }
        WorkManagerHandoffRecovery.dispatchAutomaticKeywordOwnerChange(context, ownerChange)
        return ruleId
    }

    suspend fun setEnabled(ruleId: Long, enabled: Boolean) {
        val ownerChange = RestoreMutationAdmission.withOrdinaryMutation(context) {
            val change = db.withTransaction {
                val rule = dao.getRule(ruleId) ?: return@withTransaction null
                val syncWasActive = rule.manualSyncStatus in setOf(
                    AutomaticKeywordSyncStatus.QUEUED,
                    AutomaticKeywordSyncStatus.RUNNING,
                )
                val needsSync = enabled && (
                    syncWasActive || !rule.baselineComplete || rule.pendingApplyToExisting
                )
                val next = rule.copy(
                    enabled = enabled,
                    revision = rule.revision + 1,
                    manualSyncStatus = when {
                        needsSync -> AutomaticKeywordSyncStatus.QUEUED
                        !enabled && rule.manualSyncStatus in setOf(
                            AutomaticKeywordSyncStatus.QUEUED,
                            AutomaticKeywordSyncStatus.RUNNING,
                        ) -> AutomaticKeywordSyncStatus.NEVER
                        else -> rule.manualSyncStatus
                    },
                    manualSyncAt = if (needsSync) System.currentTimeMillis() else rule.manualSyncAt,
                    manualSyncError = if (needsSync) {
                        AutomaticKeywordSyncError.NONE
                    } else {
                        rule.manualSyncError
                    },
                )
                dao.updateRule(next)
                if (needsSync) {
                    WorkManagerHandoffRecovery.stageAutomaticKeywordSyncWithinTransaction(
                        db,
                        ruleId,
                        next.revision,
                        if (rule.pendingApplyToExisting) "APPLY_EXISTING" else "BASELINE_ONLY",
                    )
                } else {
                    WorkManagerHandoffRecovery.supersedeAutomaticKeywordSyncWithinTransaction(
                        db,
                        ruleId,
                    )
                }
            }
            AutomaticKeywordObservationCoverage(context, db).reconcile()
            change
        }
        ownerChange?.let {
            WorkManagerHandoffRecovery.dispatchAutomaticKeywordOwnerChange(context, it)
        }
    }

    suspend fun delete(ruleId: Long) {
        val ownerChange = RestoreMutationAdmission.withOrdinaryMutation(context) {
            // The durable row absence immediately invalidates a worker even if
            // the following carrier tombstone must be recovered after a crash.
            assignments.deleteRuleAndAssignments(ruleId)
            db.withTransaction {
                WorkManagerHandoffRecovery.supersedeAutomaticKeywordSyncWithinTransaction(
                    db,
                    ruleId,
                )
            }
        }
        WorkManagerHandoffRecovery.dispatchAutomaticKeywordOwnerChange(context, ownerChange)
        AutomaticKeywordObservationCoverage(context, db).reconcile()
    }

    suspend fun syncNow(ruleId: Long): Boolean {
        val result = RestoreMutationAdmission.withOrdinaryMutation(context) {
            db.withTransaction {
                if (dao.requestApplyExistingSync(ruleId, System.currentTimeMillis()) == 0) {
                    return@withTransaction null
                }
                val rule = checkNotNull(dao.getRule(ruleId)) {
                    "Automatic keyword rule disappeared while staging sync"
                }
                WorkManagerHandoffRecovery.stageAutomaticKeywordSyncWithinTransaction(
                    db,
                    ruleId,
                    rule.revision,
                    "APPLY_EXISTING",
                )
            }
        } ?: return false
        WorkManagerHandoffRecovery.dispatchAutomaticKeywordOwnerChange(context, result)
        return true
    }
}
