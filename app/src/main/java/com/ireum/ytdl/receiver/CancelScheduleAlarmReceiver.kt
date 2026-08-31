package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.work.WorkManagerHandoffRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CancelScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        val context = ctx?.applicationContext ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val handoffId = intent?.getStringExtra(WorkManagerHandoffRecovery.EXTRA_HANDOFF_ID)
                    ?: WorkManagerHandoffRecovery.prepareLegacySchedulerBoundary(
                        context,
                        WorkManagerHandoffCarrier.END_BOUNDARY,
                    )
                val outcome = WorkManagerHandoffRecovery
                    .enqueueAndAwait(context, handoffId)
                    .await()
                if (!outcome.accepted && !outcome.superseded) {
                    Log.w(TAG, "Scheduled-download end handoff remains recoverable", outcome.failure)
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Scheduled-download end handoff failed", failure)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CancelScheduleAlarmReceiver"
    }
}
