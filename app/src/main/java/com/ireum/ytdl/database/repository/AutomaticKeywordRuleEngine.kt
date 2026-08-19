package com.ireum.ytdl.database.repository

import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleVideoMatch
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import kotlinx.coroutines.CancellationException

class AutomaticKeywordRuleEngine(private val db: DBManager) {
    private val dao = db.automaticKeywordRuleDao
    private val assignmentRepository = HistoryKeywordAssignmentRepository(db)

    data class RuleApplyResult(
        val ruleId: Long,
        val revision: Long,
        val matched: Int,
        val failed: Int
    )

    data class ApplyResult(
        val matched: Int,
        val failed: Int,
        val ruleResults: List<RuleApplyResult> = emptyList()
    )

    suspend fun recordDiscovery(
        videosByConditionKey: Map<String, List<ResultItem>>
    ): ApplyResult {
        val keys = videosByConditionKey.keys.filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return ApplyResult(0, 0)
        val rules = dao.getEnabledRulesForConditionKeys(keys)
        val historyByVideoKey by lazy(::buildHistoryIndex)
        var matched = 0
        var failed = 0
        val ruleResults = mutableListOf<RuleApplyResult>()
        rules.forEach { ruleSnapshot ->
            val baselineWasComplete = ruleSnapshot.baselineComplete
            var baselineCanComplete = true
            var ruleMatched = 0
            var ruleFailed = 0
            val ruleVideos = videosByConditionKey[ruleSnapshot.conditionKey]
                .orEmpty()
                .filter { AutomaticKeywordNormalizer.videoKey(it.url).isNotBlank() }
                .distinctBy { AutomaticKeywordNormalizer.videoKey(it.url) }
            for (video in ruleVideos) {
                try {
                    val processed = db.withTransaction {
                        val currentRule = dao.getRule(ruleSnapshot.id)
                        if (!currentRule.matchesSnapshot(ruleSnapshot)) {
                            return@withTransaction false
                        }
                        val videoKey = AutomaticKeywordNormalizer.videoKey(video.url)
                        val existing = dao.getVideoMatch(ruleSnapshot.id, videoKey)
                        val eligible =
                            existing?.eligibleForAssignment ?: baselineWasComplete
                        dao.insertVideoMatch(
                            AutomaticKeywordRuleVideoMatch(
                                ruleId = ruleSnapshot.id,
                                videoKey = videoKey,
                                videoUrl = video.url,
                                eligibleForAssignment = eligible,
                                firstSeenAt = System.currentTimeMillis()
                            )
                        )
                        if (eligible) {
                            dao.promoteVideoMatch(ruleSnapshot.id, videoKey, video.url)
                            applyRuleToLocalHistory(
                                ruleSnapshot.id,
                                ruleSnapshot.revision,
                                video.url,
                                historyByVideoKey
                            )
                        }
                        true
                    }
                    if (!processed) {
                        baselineCanComplete = false
                        break
                    }
                    matched++
                    ruleMatched++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed++
                    ruleFailed++
                    baselineCanComplete = false
                }
            }
            if (!baselineWasComplete && baselineCanComplete) {
                dao.completeBaselineIfCurrent(
                    ruleSnapshot.id,
                    ruleSnapshot.revision,
                    ruleSnapshot.conditionKey
                )
            }
            ruleResults += RuleApplyResult(
                ruleId = ruleSnapshot.id,
                revision = ruleSnapshot.revision,
                matched = ruleMatched,
                failed = ruleFailed
            )
        }
        return ApplyResult(matched, failed, ruleResults)
    }

    suspend fun recordDiscovery(conditionKey: String, videos: List<ResultItem>): ApplyResult =
        recordDiscovery(mapOf(conditionKey to videos))

    suspend fun applyFullSync(ruleId: Long, videos: List<ResultItem>): ApplyResult {
        val rule = dao.getRule(ruleId) ?: return ApplyResult(0, 0)
        if (!rule.enabled) return ApplyResult(0, 0)
        // Deliberately retain prior matches and their assignments when they are absent from
        // this response. Playlist extraction can be incomplete or temporarily unavailable,
        // and V1 rules keep already-applied keywords when membership later changes.
        var matched = 0
        var failed = 0
        var syncCanComplete = true
        val historyByVideoKey = buildHistoryIndex()
        for (video in videos.filter { AutomaticKeywordNormalizer.videoKey(it.url).isNotBlank() }) {
            try {
                val processed = db.withTransaction {
                    if (!dao.getRule(ruleId).matchesSnapshot(rule)) {
                        return@withTransaction false
                    }
                    val key = AutomaticKeywordNormalizer.videoKey(video.url)
                    dao.insertVideoMatch(
                        AutomaticKeywordRuleVideoMatch(
                            ruleId,
                            key,
                            video.url,
                            eligibleForAssignment = true,
                            firstSeenAt = System.currentTimeMillis()
                        )
                    )
                    dao.promoteVideoMatch(rule.id, key, video.url)
                    applyRuleToLocalHistory(
                        rule.id,
                        rule.revision,
                        video.url,
                        historyByVideoKey
                    )
                    true
                }
                if (!processed) {
                    syncCanComplete = false
                    break
                }
                matched++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
                syncCanComplete = false
            }
        }
        if (syncCanComplete) {
            dao.completeScheduledSyncIfCurrent(rule.id, rule.revision)
        }
        return ApplyResult(matched, failed)
    }

    suspend fun recordBaseline(ruleId: Long, videos: List<ResultItem>): ApplyResult {
        val rule = dao.getRule(ruleId) ?: return ApplyResult(0, 0)
        if (!rule.enabled) return ApplyResult(0, 0)
        var matched = 0
        var failed = 0
        var baselineCanComplete = true
        for (video in videos.filter { AutomaticKeywordNormalizer.videoKey(it.url).isNotBlank() }) {
            try {
                val processed = db.withTransaction {
                    if (!dao.getRule(ruleId).matchesSnapshot(rule)) {
                        return@withTransaction false
                    }
                    dao.insertVideoMatch(
                        AutomaticKeywordRuleVideoMatch(
                            rule.id,
                            AutomaticKeywordNormalizer.videoKey(video.url),
                            video.url,
                            eligibleForAssignment = false,
                            firstSeenAt = System.currentTimeMillis()
                        )
                    )
                    true
                }
                if (!processed) {
                    baselineCanComplete = false
                    break
                }
                matched++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
                baselineCanComplete = false
            }
        }
        if (baselineCanComplete) {
            dao.completeScheduledSyncIfCurrent(rule.id, rule.revision)
        }
        return ApplyResult(matched, failed)
    }

    suspend fun applyToHistory(
        historyItemId: Long,
        videoUrl: String,
        observeSourceId: Long = 0,
        observeSourceKeyword: String = ""
    ) {
        if (observeSourceId > 0 && observeSourceKeyword.isNotBlank()) {
            assignmentRepository.replaceSourceKeywords(
                historyItemId,
                HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE,
                observeSourceId,
                AutomaticKeywordNormalizer.parseKeywords(observeSourceKeyword)
            )
        }
        val videoKey = AutomaticKeywordNormalizer.videoKey(videoUrl)
        if (videoKey.isBlank()) return
        val rules = dao.getEnabledRulesForVideoKey(videoKey)
        rules.forEach { applyRuleToHistory(it.id, it.revision, historyItemId) }
    }

    suspend fun reconcileHistoryUrlChange(
        historyItemId: Long,
        previousUrl: String,
        currentUrl: String
    ) {
        val previousVideoKey = AutomaticKeywordNormalizer.videoKey(previousUrl)
        val currentVideoKey = AutomaticKeywordNormalizer.videoKey(currentUrl)
        if (previousVideoKey == currentVideoKey) {
            return
        }
        db.withTransaction {
            assignmentRepository.removeRuleAssignmentsForHistory(historyItemId)
            if (currentVideoKey.isNotBlank()) {
                dao.getEnabledRulesForVideoKey(currentVideoKey).forEach { rule ->
                    applyRuleToHistory(rule.id, rule.revision, historyItemId)
                }
            }
        }
    }

    private suspend fun applyRuleToLocalHistory(
        ruleId: Long,
        ruleRevision: Long,
        videoUrl: String,
        historyByVideoKey: Map<String, List<HistoryItem>>
    ) {
        historyByVideoKey[AutomaticKeywordNormalizer.videoKey(videoUrl)].orEmpty().forEach {
            applyRuleToHistory(ruleId, ruleRevision, it.id)
        }
    }

    private fun buildHistoryIndex(): Map<String, List<HistoryItem>> =
        db.historyDao.getAll()
            .mapNotNull { item ->
                AutomaticKeywordNormalizer.videoKey(item.url)
                    .takeIf(String::isNotBlank)
                    ?.let { it to item }
            }
            .groupBy({ it.first }, { it.second })

    private suspend fun applyRuleToHistory(
        ruleId: Long,
        ruleRevision: Long,
        historyItemId: Long
    ) {
        db.withTransaction {
            val currentRule = dao.getRule(ruleId)
            if (currentRule == null ||
                !currentRule.enabled ||
                currentRule.revision != ruleRevision
            ) {
                return@withTransaction
            }
            val keywords = dao.getRuleKeywords(ruleId).map { it.keyword }
            assignmentRepository.replaceSourceKeywords(
                historyItemId,
                HistoryKeywordAssignmentSources.RULE,
                ruleId,
                keywords
            )
        }
    }

    private fun AutomaticKeywordRule?.matchesSnapshot(snapshot: AutomaticKeywordRule): Boolean =
        this != null &&
            enabled &&
            revision == snapshot.revision &&
            conditionKey == snapshot.conditionKey
}
