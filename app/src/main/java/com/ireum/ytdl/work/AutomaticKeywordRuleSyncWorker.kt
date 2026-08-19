package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleEngine
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.NotificationUtil
import kotlinx.coroutines.CancellationException

class AutomaticKeywordRuleSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    companion object {
        const val INPUT_RULE_ID = "ruleId"
        const val INPUT_MODE = "mode"
        private const val MAX_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        val ruleId = inputData.getLong(INPUT_RULE_ID, 0)
        if (ruleId <= 0) return Result.failure()
        val db = DBManager.getInstance(applicationContext)
        val dao = db.automaticKeywordRuleDao
        val rule = dao.getRule(ruleId) ?: return Result.success()
        if (!rule.enabled) return Result.success()
        val notification = NotificationUtil(applicationContext).createObserveSourcesNotification(
            rule.playlistName,
            applicationContext.getString(R.string.automatic_keyword_status_running)
        )
        val foreground = if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                System.currentTimeMillis().toInt(),
                notification,
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(System.currentTimeMillis().toInt(), notification)
        }
        setForeground(foreground)
        val started = dao.updateManualSyncStatusIfRevision(
            ruleId,
            rule.revision,
            AutomaticKeywordSyncStatus.RUNNING,
            System.currentTimeMillis(),
            AutomaticKeywordSyncError.NONE
        )
        if (started == 0) return Result.success()
        return try {
            val videos = ResultRepository(
                db.resultDao,
                db.commandTemplateDao,
                applicationContext
            ).getResultsFromSource(
                rule.conditionValue,
                resetResults = false,
                addToResults = false,
                singleItem = false
            )
            val currentRule = dao.getRule(ruleId)
            if (currentRule == null || !currentRule.enabled || currentRule.revision != rule.revision) {
                return Result.success()
            }
            val engine = AutomaticKeywordRuleEngine(db)
            val result = when {
                currentRule.pendingApplyToExisting ->
                    engine.applyFullSync(ruleId, videos)
                !currentRule.baselineComplete ->
                    engine.recordBaseline(ruleId, videos)
                else ->
                    engine.recordDiscovery(currentRule.conditionKey, videos)
            }
            dao.updateManualSyncStatusIfRevision(
                ruleId,
                rule.revision,
                if (result.failed == 0) AutomaticKeywordSyncStatus.SUCCESS else AutomaticKeywordSyncStatus.PARTIAL,
                System.currentTimeMillis(),
                if (result.failed == 0) AutomaticKeywordSyncError.NONE else AutomaticKeywordSyncError.DATABASE_PARTIAL
            )
            if (result.failed > 0 && runAttemptCount + 1 < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.success(
                    androidx.work.Data.Builder()
                        .putInt("matched", result.matched)
                        .putInt("failed", result.failed)
                        .build()
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val errorCode = classify(error)
            dao.updateManualSyncStatusIfRevision(
                ruleId,
                rule.revision,
                AutomaticKeywordSyncStatus.FAILED,
                System.currentTimeMillis(),
                errorCode
            )
            if (runAttemptCount + 1 < MAX_ATTEMPTS &&
                errorCode in setOf(
                    AutomaticKeywordSyncError.NETWORK,
                    AutomaticKeywordSyncError.EXTRACTION,
                    AutomaticKeywordSyncError.UNKNOWN
                )
            ) {
                Result.retry()
            } else {
                // Authentication/private/unavailable states need user or server changes.
                Result.success()
            }
        }
    }

    private fun classify(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "login" in message || "sign in" in message || "authentication" in message ->
                AutomaticKeywordSyncError.AUTH_REQUIRED
            "private" in message -> AutomaticKeywordSyncError.PRIVATE_PLAYLIST
            "unavailable" in message || "not available" in message ->
                AutomaticKeywordSyncError.UNAVAILABLE
            "network" in message || "timeout" in message || "connection" in message ->
                AutomaticKeywordSyncError.NETWORK
            "extract" in message || "yt-dlp" in message -> AutomaticKeywordSyncError.EXTRACTION
            else -> AutomaticKeywordSyncError.UNKNOWN
        }
    }
}
