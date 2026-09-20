package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.RestoreTransactionCoordinator.RestoreReconciliationAuthority
import com.ireum.ytdl.work.AutomaticKeywordRuleSyncWorker
import java.util.concurrent.TimeUnit

object AutomaticKeywordRuleScheduler {
    enum class Mode { APPLY_EXISTING, BASELINE_ONLY }

    fun workName(ruleId: Long) = "AUTOMATIC_KEYWORD_RULE_SYNC_$ruleId"

    fun enqueue(
        context: Context,
        ruleId: Long,
        mode: Mode,
    ): Operation? {
        if (RestoreGate.isRestoreInProgress(context)) return null
        return enqueueInternal(context, ruleId, mode)
    }

    internal fun enqueueForRestore(
        context: Context,
        ruleId: Long,
        mode: Mode,
        authority: RestoreReconciliationAuthority,
    ): Operation? {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        return enqueueInternal(context, ruleId, mode)
    }

    private fun enqueueInternal(
        context: Context,
        ruleId: Long,
        mode: Mode,
    ): Operation {
        val allowMeteredNetworks = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("metered_networks", true)
        val networkType =
            if (allowMeteredNetworks) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<AutomaticKeywordRuleSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putLong(AutomaticKeywordRuleSyncWorker.INPUT_RULE_ID, ruleId)
                    .putString(AutomaticKeywordRuleSyncWorker.INPUT_MODE, mode.name)
                    .build(),
            )
            .addTag("automaticKeywordRules")
            .addTag("automaticKeywordRule_$ruleId")
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(
            workName(ruleId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, ruleId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(ruleId))
    }
}