package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AutomaticKeywordRuleSummary
import com.ireum.ytdl.database.models.AutomaticKeywordRuleVideoMatch
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomaticKeywordRuleDao {
    @Insert
    suspend fun insertRule(rule: AutomaticKeywordRule): Long

    @Update
    suspend fun updateRule(rule: AutomaticKeywordRule)

    @Query("SELECT * FROM automatic_keyword_rules WHERE id = :ruleId")
    suspend fun getRule(ruleId: Long): AutomaticKeywordRule?

    @Query("SELECT * FROM automatic_keyword_rules ORDER BY id DESC")
    suspend fun getAllRules(): List<AutomaticKeywordRule>

    @Query("SELECT * FROM automatic_keyword_rules WHERE enabled = 1")
    suspend fun getAllEnabledRules(): List<AutomaticKeywordRule>

    @Query(
        """
        SELECT r.*,
               GROUP_CONCAT(DISTINCT k.keyword) AS keywordsCsv,
               COUNT(DISTINCT a.historyItemId) AS matchedHistoryCount
        FROM automatic_keyword_rules r
        LEFT JOIN automatic_keyword_rule_keywords k ON k.ruleId = r.id
        LEFT JOIN history_keyword_assignments a
          ON a.sourceType = 'RULE' AND a.sourceId = r.id
        GROUP BY r.id
        ORDER BY r.id DESC
        """
    )
    fun observeRuleSummaries(): Flow<List<AutomaticKeywordRuleSummary>>

    @Query(
        "SELECT * FROM automatic_keyword_rules " +
            "WHERE enabled = 1 AND conditionType = 'PLAYLIST' AND conditionKey IN (:conditionKeys)"
    )
    suspend fun getEnabledRulesForConditionKeys(conditionKeys: List<String>): List<AutomaticKeywordRule>

    @Query(
        "SELECT * FROM automatic_keyword_rules " +
            "WHERE enabled = 1 AND conditionType = 'PLAYLIST' AND conditionKey = :conditionKey"
    )
    suspend fun getEnabledRulesForConditionKey(conditionKey: String): List<AutomaticKeywordRule>

    @Query(
        """
        SELECT DISTINCT r.* FROM automatic_keyword_rules r
        INNER JOIN automatic_keyword_rule_video_matches m ON m.ruleId = r.id
        WHERE r.enabled = 1 AND m.videoKey = :videoKey AND m.eligibleForAssignment = 1
        """
    )
    suspend fun getEnabledRulesForVideoKey(videoKey: String): List<AutomaticKeywordRule>

    @Query("DELETE FROM automatic_keyword_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query(
        "UPDATE automatic_keyword_rules SET manualSyncStatus = :status, manualSyncAt = :at, " +
            "manualSyncError = :error WHERE id = :ruleId"
    )
    suspend fun updateManualSyncStatus(ruleId: Long, status: String, at: Long, error: String)

    @Query(
        "UPDATE automatic_keyword_rules SET manualSyncStatus = :status, manualSyncAt = :at, " +
            "manualSyncError = :error WHERE id = :ruleId AND revision = :revision"
    )
    suspend fun updateManualSyncStatusIfRevision(
        ruleId: Long,
        revision: Long,
        status: String,
        at: Long,
        error: String
    ): Int

    @Query(
        "UPDATE automatic_keyword_rules SET revision = revision + 1, " +
            "pendingApplyToExisting = 1, manualSyncStatus = 'QUEUED', " +
            "manualSyncAt = :at, manualSyncError = '' " +
            "WHERE id = :ruleId AND enabled = 1"
    )
    suspend fun requestApplyExistingSync(ruleId: Long, at: Long): Int

    @Query(
        "UPDATE automatic_keyword_rules SET discoveryStatus = :status, discoveryAt = :at, " +
            "discoveryError = :error WHERE id = :ruleId"
    )
    suspend fun updateDiscoveryStatus(ruleId: Long, status: String, at: Long, error: String)

    @Query("UPDATE automatic_keyword_rules SET baselineComplete = :complete WHERE id = :ruleId")
    suspend fun updateBaselineComplete(ruleId: Long, complete: Boolean)

    @Query(
        "UPDATE automatic_keyword_rules SET baselineComplete = 1 " +
            "WHERE id = :ruleId AND revision = :revision AND conditionKey = :conditionKey " +
            "AND enabled = 1"
    )
    suspend fun completeBaselineIfCurrent(
        ruleId: Long,
        revision: Long,
        conditionKey: String
    ): Int

    @Query(
        "UPDATE automatic_keyword_rules SET baselineComplete = 1, pendingApplyToExisting = 0 " +
            "WHERE id = :ruleId AND revision = :revision AND enabled = 1"
    )
    suspend fun completeScheduledSyncIfCurrent(ruleId: Long, revision: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuleKeywords(keywords: List<AutomaticKeywordRuleKeyword>)

    @Query("DELETE FROM automatic_keyword_rule_keywords WHERE ruleId = :ruleId")
    suspend fun deleteRuleKeywords(ruleId: Long)

    @Query("SELECT * FROM automatic_keyword_rule_keywords WHERE ruleId = :ruleId ORDER BY position")
    suspend fun getRuleKeywords(ruleId: Long): List<AutomaticKeywordRuleKeyword>

    @Query("SELECT * FROM automatic_keyword_rule_keywords ORDER BY ruleId, position")
    suspend fun getAllRuleKeywords(): List<AutomaticKeywordRuleKeyword>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideoMatch(match: AutomaticKeywordRuleVideoMatch): Long

    @Query(
        """
        UPDATE automatic_keyword_rule_video_matches
        SET eligibleForAssignment = 1, videoUrl = :videoUrl
        WHERE ruleId = :ruleId AND videoKey = :videoKey
        """
    )
    suspend fun promoteVideoMatch(ruleId: Long, videoKey: String, videoUrl: String)

    @Query("SELECT * FROM automatic_keyword_rule_video_matches WHERE ruleId = :ruleId AND videoKey = :videoKey")
    suspend fun getVideoMatch(ruleId: Long, videoKey: String): AutomaticKeywordRuleVideoMatch?

    @Query("DELETE FROM automatic_keyword_rule_video_matches WHERE ruleId = :ruleId")
    suspend fun deleteVideoMatches(ruleId: Long)

    @Query("SELECT * FROM automatic_keyword_rule_video_matches ORDER BY ruleId, firstSeenAt")
    suspend fun getAllVideoMatches(): List<AutomaticKeywordRuleVideoMatch>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssignments(assignments: List<HistoryKeywordAssignment>): List<Long>

    @Query(
        "SELECT * FROM history_keyword_assignments WHERE historyItemId = :historyItemId " +
            "ORDER BY CASE sourceType WHEN 'MANUAL' THEN 0 WHEN 'RULE' THEN 1 ELSE 2 END, sourceId, position"
    )
    suspend fun getAssignmentsForHistory(historyItemId: Long): List<HistoryKeywordAssignment>

    @Query(
        "SELECT * FROM history_keyword_assignments WHERE historyItemId = :historyItemId " +
            "AND sourceType = :sourceType AND sourceId = :sourceId ORDER BY position"
    )
    suspend fun getAssignmentsForHistorySource(
        historyItemId: Long,
        sourceType: String,
        sourceId: Long
    ): List<HistoryKeywordAssignment>

    @Query(
        "SELECT DISTINCT historyItemId FROM history_keyword_assignments " +
            "WHERE sourceType = :sourceType AND sourceId = :sourceId"
    )
    suspend fun getHistoryIdsForSource(sourceType: String, sourceId: Long): List<Long>

    @Query(
        "DELETE FROM history_keyword_assignments " +
            "WHERE historyItemId = :historyItemId AND sourceType = :sourceType AND sourceId = :sourceId"
    )
    suspend fun deleteAssignmentsForHistorySource(
        historyItemId: Long,
        sourceType: String,
        sourceId: Long
    )

    @Query(
        "DELETE FROM history_keyword_assignments " +
            "WHERE sourceType = :sourceType AND sourceId = :sourceId"
    )
    suspend fun deleteAssignmentsForSource(sourceType: String, sourceId: Long)

    @Query("SELECT * FROM history_keyword_assignments WHERE historyItemId = :historyItemId")
    suspend fun getAssignmentsRaw(historyItemId: Long): List<HistoryKeywordAssignment>

    @Query("SELECT * FROM history_keyword_assignments ORDER BY historyItemId, sourceType, sourceId, position")
    suspend fun getAllAssignmentsRaw(): List<HistoryKeywordAssignment>

    @Query("DELETE FROM history_keyword_assignments WHERE historyItemId = :historyItemId")
    suspend fun deleteAssignmentsForHistory(historyItemId: Long)
}
