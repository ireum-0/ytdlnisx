package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.work.YtdlpProcessIdentity
import com.yausername.youtubedl_android.YoutubeDL
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
                val processId = YtdlpProcessIdentity.terminal(terminalId)
                YoutubeDL.getInstance().destroyProcessById(processId)
                YoutubeDLCompat.destroyProcessById(processId)
                WorkManager.getInstance(context).cancelUniqueWork(terminalId.toString())
                NotificationUtil(context).cancelTerminalDownloadNotification(terminalId.toInt())
                DBManager.getInstance(context).terminalDao.delete(terminalId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TERMINAL_ID = "terminalID"
    }
}
