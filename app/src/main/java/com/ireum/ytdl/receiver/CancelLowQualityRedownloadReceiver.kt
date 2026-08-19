package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ireum.ytdl.work.LowQualityRedownloadManager

class CancelLowQualityRedownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty()
        if (operationId.isBlank()) return
        val pendingResult = goAsync()
        LowQualityRedownloadManager.get(context).cancel(operationId) {
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.ireum.ytdl.action.CANCEL_LOW_QUALITY_REDOWNLOAD"
        const val EXTRA_OPERATION_ID = "operation_id"
    }
}
