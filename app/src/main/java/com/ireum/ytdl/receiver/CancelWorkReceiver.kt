package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.work.FormatBatchCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class CancelWorkReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.hasExtra(FormatBatchCancellation.REQUEST_ID)) {
            val request = runCatching { UUID.fromString(intent.getStringExtra(FormatBatchCancellation.REQUEST_ID)) }.getOrNull()
                ?: return // Invalid exact authority never falls back to a class-wide tag.
            FormatBatchCancellation.observe(request)
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { FormatBatchCancellation.cancel(c.applicationContext, DBManager.getInstance(c), request) }
                catch (failure: Exception) { android.util.Log.e("FormatCancellation", "Exact request revocation failed", failure) }
                finally { pending.finish() }
            }
            return
        }
        val id = intent.getStringExtra("workTag")
        // Old format actions have no exact request authority over newer format batches.
        if (id == "updateFormats" || id == "com.ireum.ytdl.work.UpdateMultipleDownloadsFormatsWorker") return
        if (!id.isNullOrBlank()){
            WorkManager.getInstance(c).cancelAllWorkByTag(id)
        }
    }
}
