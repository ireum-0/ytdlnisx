package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleEngine
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleScheduler
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.SourceSnapshot
import kotlinx.coroutines.CancellationException

class AutomaticKeywordRuleSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    companion object {
        const val INPUT_RULE_ID = "ruleId"
        const val INPUT_REVISION = "ruleRevision"
        const val INPUT_MODE = "mode"
        const val INPUT_HANDOFF_ID = "handoffId"
        const val INPUT_REQUEST_ID = "requestId"
        const val INPUT_GENERATION_ID = "generationId"
        const val INPUT_BOUNDARY = "boundary"
        private const val MAX_ATTEMPTS = 3
    }

    private data class WorkerAuthority(
        val ruleId: Long,
        val revision: Long,
        val mode: AutomaticKeywordRuleScheduler.Mode,
        val handoffId: String,
        val requestId: String,
        val generationId: String,
        val boundary: String,
    )

    private data class Completion(
        val current: Boolean,
        val result: AutomaticKeywordRuleEngine.ApplyResult? = null,
        val shouldRetry: Boolean = false,
    )

    override suspend fun doWork(): Result {
        if (RestoreGate.isRestoreInProgress(applicationContext)) return Result.retry()
        val authority = readAuthority() ?: return Result.success()
        val db = AutomaticKeywordRuleSyncWorkerTestHooks.dbManagerForTesting
            ?: DBManager.getInstance(applicationContext)
        val dao = db.automaticKeywordRuleDao

        val initialRule = try {
            withCurrentOwner(authority, db) { it }
        } catch (blocked: IllegalStateException) {
            if (RestoreGate.isRestoreInProgress(applicationContext)) return Result.retry()
            throw blocked
        } ?: return Result.success()
        val started = try {
            withCurrentOwner(authority, db) {
                dao.updateManualSyncStatusIfRevision(
                    authority.ruleId,
                    authority.revision,
                    AutomaticKeywordSyncStatus.RUNNING,
                    System.currentTimeMillis(),
                    AutomaticKeywordSyncError.NONE,
                ) == 1
            }
        } catch (blocked: IllegalStateException) {
            if (RestoreGate.isRestoreInProgress(applicationContext)) return Result.retry()
            throw blocked
        } ?: return Result.success()
        if (!started) return Result.success()

        try {
            val notification = NotificationUtil(applicationContext).createObserveSourcesNotification(
                initialRule.playlistName,
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
            val recorded = recordTerminalError(authority, db, AutomaticKeywordSyncError.UNKNOWN)
            return if (recorded) Result.failure() else Result.success()
        }

        return try {
            val resultRepository = ResultRepository(
                db.resultDao,
                db.commandTemplateDao,
                applicationContext
            )
            val snapshot = AutomaticKeywordRuleSyncWorkerTestHooks.sourceSnapshotForTesting
                ?.invoke(initialRule)
                ?: resultRepository.getSourceSnapshotFromSource(
                    initialRule.conditionValue,
                    resetResults = false,
                    addToResults = false,
                    singleItem = false
                )
            AutomaticKeywordRuleSyncWorkerTestHooks.afterFetchForTesting?.invoke(db, initialRule)
            when (snapshot.authority) {
                SourceSnapshot.Authority.FAILED -> {
                    throw snapshot.cause ?: IllegalStateException(
                        snapshot.diagnostic.ifBlank { "Source extraction failed" }
                    )
                }
                SourceSnapshot.Authority.PARTIAL -> {
                    val retry = runAttemptCount + 1 < MAX_ATTEMPTS
                    val recorded = withCurrentOwner(authority, db) {
                        db.withTransaction {
                            if (!isCurrentOwner(authority, applicationContext)) {
                                false
                            } else {
                                val changed = dao.updateManualSyncStatusIfRevision(
                                    authority.ruleId,
                                    authority.revision,
                                    AutomaticKeywordSyncStatus.PARTIAL,
                                    System.currentTimeMillis(),
                                    AutomaticKeywordSyncError.EXTRACTION,
                                )
                                if (changed == 1 && !retry) {
                                    db.workManagerHandoffCarrierDao.deleteExact(
                                        authority.handoffId,
                                        authority.requestId,
                                    )
                                }
                                changed == 1
                            }
                        }
                    } ?: false
                    if (!recorded) return Result.success()
                    return if (retry) Result.retry() else Result.success()
                }
                SourceSnapshot.Authority.AUTHORITATIVE -> Unit
            }

            val retry = runAttemptCount + 1 < MAX_ATTEMPTS
            val completion = withCurrentOwner(authority, db) { currentRule ->
                db.withTransaction {
                    if (!isCurrentOwner(authority, applicationContext)) {
                        Completion(current = false)
                    } else {
                        val engine = AutomaticKeywordRuleEngine(db)
                        val result = when (authority.mode) {
                            AutomaticKeywordRuleScheduler.Mode.APPLY_EXISTING ->
                                engine.applyFullSync(authority.ruleId, snapshot.items)
                            AutomaticKeywordRuleScheduler.Mode.BASELINE_ONLY -> if (
                                currentRule.baselineComplete
                            ) {
                                engine.recordDiscovery(currentRule.conditionKey, snapshot.items)
                            } else {
                                engine.recordBaseline(authority.ruleId, snapshot.items)
                            }
                        }
                        val terminalStatus = if (result.failed == 0) {
                            AutomaticKeywordSyncStatus.SUCCESS
                        } else {
                            AutomaticKeywordSyncStatus.PARTIAL
                        }
                        val updated = dao.updateManualSyncStatusIfRevision(
                            authority.ruleId,
                            authority.revision,
                            terminalStatus,
                            System.currentTimeMillis(),
                            if (result.failed == 0) {
                                AutomaticKeywordSyncError.NONE
                            } else {
                                AutomaticKeywordSyncError.DATABASE_PARTIAL
                            },
                        )
                        val shouldRetry = result.failed > 0 && retry
                        if (updated == 1 && !shouldRetry) {
                            db.workManagerHandoffCarrierDao.deleteExact(
                                authority.handoffId,
                                authority.requestId,
                            )
                        }
                        if (updated != 1) {
                            Completion(current = false)
                        } else {
                            Completion(current = true, result = result, shouldRetry = shouldRetry)
                        }
                    }
                }
            } ?: return Result.success()

            if (!completion.current) return Result.success()
            val syncResult = checkNotNull(completion.result)
            if (completion.shouldRetry) {
                Result.retry()
            } else {
                Result.success(
                    Data.Builder()
                        .putInt("matched", syncResult.matched)
                        .putInt("failed", syncResult.failed)
                        .build()
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (RestoreGate.isRestoreInProgress(applicationContext)) return Result.retry()
            val errorCode = classify(error)
            val retry = runAttemptCount + 1 < MAX_ATTEMPTS &&
                errorCode in setOf(
                    AutomaticKeywordSyncError.NETWORK,
                    AutomaticKeywordSyncError.EXTRACTION,
                    AutomaticKeywordSyncError.UNKNOWN,
                )
            val recorded = recordTerminalError(authority, db, errorCode, resolve = !retry)
            if (!recorded) Result.success() else if (retry) Result.retry() else Result.success()
        }
    }

    private fun readAuthority(): WorkerAuthority? {
        val ruleId = inputData.getLong(INPUT_RULE_ID, 0L)
        val revision = inputData.getLong(INPUT_REVISION, 0L)
        val mode = inputData.getString(INPUT_MODE)
            ?.let { runCatching { AutomaticKeywordRuleScheduler.Mode.valueOf(it) }.getOrNull() }
            ?: return null
        val handoffId = inputData.getString(INPUT_HANDOFF_ID).orEmpty()
        val requestId = inputData.getString(INPUT_REQUEST_ID).orEmpty()
        val generationId = inputData.getString(INPUT_GENERATION_ID).orEmpty()
        val boundary = inputData.getString(INPUT_BOUNDARY).orEmpty()
        if (ruleId <= 0L || revision <= 0L || handoffId.isBlank() || requestId.isBlank() ||
            generationId.isBlank() || boundary != ruleId.toString() || requestId != id.toString()
        ) return null
        return WorkerAuthority(ruleId, revision, mode, handoffId, requestId, generationId, boundary)
    }

    private suspend fun <T : Any> withCurrentOwner(
        authority: WorkerAuthority,
        db: DBManager,
        block: suspend (AutomaticKeywordRule) -> T,
    ): T? = RestoreMutationAdmission.withOrdinaryMutation(applicationContext) {
        if (!isCurrentOwner(authority, applicationContext)) return@withOrdinaryMutation null
        val rule = db.automaticKeywordRuleDao.getRule(authority.ruleId)
            ?.takeIf { it.enabled && it.revision == authority.revision }
            ?: return@withOrdinaryMutation null
        block(rule)
    }

    private suspend fun isCurrentOwner(
        authority: WorkerAuthority,
        context: Context,
    ): Boolean = WorkManagerHandoffRecovery.isCurrentAutomaticKeywordSyncRequest(
        context = context,
        ruleId = authority.ruleId,
        revision = authority.revision,
        mode = authority.mode.name,
        handoffId = authority.handoffId,
        requestId = authority.requestId,
        generationId = authority.generationId,
        boundary = authority.boundary,
        workRequestId = id.toString(),
    )

    private suspend fun recordTerminalError(
        authority: WorkerAuthority,
        db: DBManager,
        errorCode: String,
        resolve: Boolean = true,
    ): Boolean {
        if (RestoreGate.isRestoreInProgress(applicationContext)) return false
        return withCurrentOwner(authority, db) {
            db.withTransaction {
                if (!isCurrentOwner(authority, applicationContext)) {
                    false
                } else {
                    val updated = db.automaticKeywordRuleDao.updateManualSyncStatusIfRevision(
                        authority.ruleId,
                        authority.revision,
                        AutomaticKeywordSyncStatus.FAILED,
                        System.currentTimeMillis(),
                        errorCode,
                    )
                    if (updated == 1 && resolve) {
                        db.workManagerHandoffCarrierDao.deleteExact(
                            authority.handoffId,
                            authority.requestId,
                        )
                    }
                    updated == 1
                }
            }
        } ?: false
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

/**
 * Test-only seams for the real automatic-keyword sync worker.  Hooks replace
 * only external persistence/extraction inputs; authority checks, rule
 * revision validation, engine selection, and status writes remain production
 * code executed against the supplied Room database.
 */
internal object AutomaticKeywordRuleSyncWorkerTestHooks {
    @Volatile
    internal var dbManagerForTesting: DBManager? = null

    @Volatile
    internal var sourceSnapshotForTesting:
        (suspend (AutomaticKeywordRule) -> SourceSnapshot)? = null

    /** Runs after extraction and before the worker revalidates its owner. */
    @Volatile
    internal var afterFetchForTesting:
        (suspend (DBManager, AutomaticKeywordRule) -> Unit)? = null

    internal fun clearForTesting() {
        dbManagerForTesting = null
        sourceSnapshotForTesting = null
        afterFetchForTesting = null
    }
}
