package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.TerminalExecutionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Cancellation capability for TerminalItem, never for a DownloadItem. */
class CancelTerminalNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val terminalId = intent.getLongExtra(EXTRA_TERMINAL_ID, 0L)
        if (terminalId <= 0L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cancellationRequested = runCatching {
                    WorkManager.getInstance(context)
                        .cancelUniqueWork(terminalId.toString())
                        .result
                        .get()
                }.isSuccess
                if (!cancellationRequested || !TerminalExecutionRegistry.cancel(context, terminalId)) {
                    // Native quiescence or cancellation is unresolved. Keep
                    // the exact row/witness; startup recovery owns the next
                    // attempt and no new native work can be admitted.
                    NotificationUtil(context).cancelTerminalDownloadNotification(terminalId.toInt())
                    return@launch
                }
                NotificationUtil(context).cancelTerminalDownloadNotification(terminalId.toInt())
                val dao = DBManager.getInstance(context).terminalDao
                dao.delete(terminalId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TERMINAL_ID = "terminalID"
    }
}
