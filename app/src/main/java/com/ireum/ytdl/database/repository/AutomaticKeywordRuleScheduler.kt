package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.RestoreTransactionCoordinator.RestoreReconciliationAuthority
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.work.WorkManagerHandoffRecovery
import kotlinx.coroutines.withTimeout

object AutomaticKeywordRuleScheduler {
    enum class Mode { APPLY_EXISTING, BASELINE_ONLY }

    fun workName(ruleId: Long) = "AUTOMATIC_KEYWORD_RULE_SYNC_$ruleId"

    /** Stages exact revision authority before starting asynchronous publication. */
    suspend fun enqueue(
        context: Context,
        ruleId: Long,
        mode: Mode,
    ): Boolean {
        val appContext = context.applicationContext
        val db = DBManager.getInstance(appContext)
        val change = RestoreMutationAdmission.withOrdinaryMutation(appContext) {
            db.withTransaction {
                val rule = db.automaticKeywordRuleDao.getRule(ruleId)
                if (rule?.enabled != true) {
                    WorkManagerHandoffRecovery.supersedeAutomaticKeywordSyncWithinTransaction(
                        db,
                        ruleId,
                    )
                } else {
                    val current = db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                        WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC,
                        ruleId.toString(),
                    )
                    if (current?.sourceId == ruleId &&
                        current.sourceConfigurationGeneration == rule.revision &&
                        current.decision == mode.name
                    ) {
                        WorkManagerHandoffRecovery.AutomaticKeywordOwnerChange(
                            handoffId = current.handoffId,
                        )
                    } else {
                        db.automaticKeywordRuleDao.updateManualSyncStatusIfRevision(
                            ruleId,
                            rule.revision,
                            AutomaticKeywordSyncStatus.QUEUED,
                            System.currentTimeMillis(),
                            AutomaticKeywordSyncError.NONE,
                        )
                        WorkManagerHandoffRecovery.stageAutomaticKeywordSyncWithinTransaction(
                            db,
                            ruleId,
                            rule.revision,
                            mode.name,
                        )
                    }
                }
            }
        }
        WorkManagerHandoffRecovery.dispatchAutomaticKeywordOwnerChange(appContext, change)
        return change.handoffId != null
    }

    /** Restore-owned publication uses the same durable carrier and awaits acceptance. */
    internal suspend fun enqueueForRestore(
        context: Context,
        ruleId: Long,
        mode: Mode,
        authority: RestoreReconciliationAuthority,
    ): Boolean {
        val appContext = context.applicationContext
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(appContext, authority)
        val db = DBManager.getInstance(appContext)
        val change = RestoreMutationAdmission.withRestoreMutation {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(appContext, authority)
            db.withTransaction {
                val rule = db.automaticKeywordRuleDao.getRule(ruleId)
                    ?: error("Automatic keyword rule disappeared during Restore reconciliation")
                check(rule.enabled) { "Disabled automatic keyword rule cannot be scheduled during Restore" }
                val current = db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC,
                    ruleId.toString(),
                )
                if (current?.sourceId == ruleId &&
                    current.sourceConfigurationGeneration == rule.revision &&
                    current.decision == mode.name
                ) {
                    WorkManagerHandoffRecovery.AutomaticKeywordOwnerChange(
                        handoffId = current.handoffId,
                    )
                } else {
                    check(
                        db.automaticKeywordRuleDao.updateManualSyncStatusIfRevision(
                            ruleId,
                            rule.revision,
                            AutomaticKeywordSyncStatus.QUEUED,
                            System.currentTimeMillis(),
                            AutomaticKeywordSyncError.NONE,
                        ) == 1
                    ) { "Automatic keyword rule changed during Restore scheduling" }
                    WorkManagerHandoffRecovery.stageAutomaticKeywordSyncWithinTransaction(
                        db,
                        ruleId,
                        rule.revision,
                        mode.name,
                    )
                }
            }
        }
        // Restore quiescence has already cancelled the keyword tag. Do not run
        // ordinary cancellation while Restore owns admission; unique REPLACE
        // plus the exact durable owner fences any pre-Restore request.
        val handoffId = checkNotNull(change.handoffId)
        val outcome = withTimeout(5_000L) {
            WorkManagerHandoffRecovery.enqueueAndAwaitForRestore(
                appContext,
                handoffId,
                authority,
            ).await()
        }
        check(outcome.accepted) {
            "Automatic keyword scheduling was not accepted: ${outcome.kind}"
        }
        return true
    }

    /** Durable carrier supersession precedes exact-request cancellation. */
    suspend fun cancel(context: Context, ruleId: Long) {
        val appContext = context.applicationContext
        val db = DBManager.getInstance(appContext)
        val change = RestoreMutationAdmission.withOrdinaryMutation(appContext) {
            db.withTransaction {
                WorkManagerHandoffRecovery.supersedeAutomaticKeywordSyncWithinTransaction(
                    db,
                    ruleId,
                )
            }
        }
        WorkManagerHandoffRecovery.dispatchAutomaticKeywordOwnerChange(appContext, change)
    }
}
