package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AutomaticKeywordRuleSummary
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
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
            AutomaticKeywordSyncStatus.RUNNING
        )
        val nextBaselineComplete =
            if (conditionChanged) false else oldRule?.baselineComplete ?: false
        val needsInitialSync = oldRule == null || conditionChanged || !nextBaselineComplete
        val pendingApplyToExisting = input.applyToExistingVideos ||
            (syncWasActive && oldRule?.pendingApplyToExisting == true)
        val ruleId = db.withTransaction {
            if (conditionChanged) {
                assignments.removeSourceAssignments(
                    HistoryKeywordAssignmentSources.RULE,
                    requireNotNull(oldRule).id
                )
            }
            val next = AutomaticKeywordRule(
                id = oldRule?.id ?: 0,
                conditionValue = conditionValue,
                conditionKey = conditionKey,
                playlistName = input.playlistName.trim().ifBlank { conditionValue },
                enabled = input.enabled,
                revision = (oldRule?.revision ?: 0) + 1,
                baselineComplete = nextBaselineComplete,
                pendingApplyToExisting = pendingApplyToExisting,
                manualSyncStatus = if (syncWasActive) {
                    AutomaticKeywordSyncStatus.NEVER
                } else {
                    oldRule?.manualSyncStatus ?: AutomaticKeywordSyncStatus.NEVER
                },
                manualSyncAt = oldRule?.manualSyncAt ?: 0,
                manualSyncError = oldRule?.manualSyncError ?: AutomaticKeywordSyncError.NONE,
                discoveryStatus = oldRule?.discoveryStatus ?: AutomaticKeywordSyncStatus.NEVER,
                discoveryAt = oldRule?.discoveryAt ?: 0,
                discoveryError = oldRule?.discoveryError ?: AutomaticKeywordSyncError.NONE
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
                    position = position
                )
            })
            assignments.replaceRuleAssignmentsForExistingHistories(id, parsedKeywords)
            id
        }

        AutomaticKeywordObservationCoverage(context, db).reconcile()
        val shouldScheduleInitialSync =
            needsInitialSync || pendingApplyToExisting || syncWasActive
        if (input.enabled && shouldScheduleInitialSync) {
            dao.updateManualSyncStatus(
                ruleId,
                AutomaticKeywordSyncStatus.QUEUED,
                System.currentTimeMillis(),
                AutomaticKeywordSyncError.NONE
            )
            AutomaticKeywordRuleScheduler.enqueue(
                context,
                ruleId,
                if (pendingApplyToExisting) {
                    AutomaticKeywordRuleScheduler.Mode.APPLY_EXISTING
                } else {
                    AutomaticKeywordRuleScheduler.Mode.BASELINE_ONLY
                }
            )
        } else if (!input.enabled && oldRule?.enabled == true) {
            AutomaticKeywordRuleScheduler.cancel(context, ruleId)
        }
        return ruleId
    }

    suspend fun setEnabled(ruleId: Long, enabled: Boolean) {
        val rule = dao.getRule(ruleId) ?: return
        val needsSync = enabled && (!rule.baselineComplete || rule.pendingApplyToExisting)
        dao.updateRule(
            rule.copy(
                enabled = enabled,
                revision = rule.revision + 1,
                manualSyncStatus = when {
                    needsSync -> AutomaticKeywordSyncStatus.QUEUED
                    !enabled && rule.manualSyncStatus in setOf(
                        AutomaticKeywordSyncStatus.QUEUED,
                        AutomaticKeywordSyncStatus.RUNNING
                    ) -> AutomaticKeywordSyncStatus.NEVER
                    else -> rule.manualSyncStatus
                },
                manualSyncAt = if (needsSync) System.currentTimeMillis() else rule.manualSyncAt,
                manualSyncError = if (needsSync) {
                    AutomaticKeywordSyncError.NONE
                } else {
                    rule.manualSyncError
                }
            )
        )
        if (!enabled) {
            AutomaticKeywordRuleScheduler.cancel(context, ruleId)
        } else if (needsSync) {
            AutomaticKeywordRuleScheduler.enqueue(
                context,
                ruleId,
                if (rule.pendingApplyToExisting) {
                    AutomaticKeywordRuleScheduler.Mode.APPLY_EXISTING
                } else {
                    AutomaticKeywordRuleScheduler.Mode.BASELINE_ONLY
                }
            )
        }
        AutomaticKeywordObservationCoverage(context, db).reconcile()
    }

    suspend fun delete(ruleId: Long) {
        assignments.deleteRuleAndAssignments(ruleId)
        AutomaticKeywordRuleScheduler.cancel(context, ruleId)
        AutomaticKeywordObservationCoverage(context, db).reconcile()
    }

    suspend fun syncNow(ruleId: Long): Boolean {
        if (dao.requestApplyExistingSync(ruleId, System.currentTimeMillis()) == 0) return false
        AutomaticKeywordRuleScheduler.enqueue(
            context,
            ruleId,
            AutomaticKeywordRuleScheduler.Mode.APPLY_EXISTING
        )
        return true
    }
}
