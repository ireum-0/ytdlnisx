package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.ObserveSourceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ObserveRetryDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, 0L)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val decision = intent.action.orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        if (sourceId <= 0L || url.isBlank() || decision !in VALID_ACTIONS) return

        if (notificationId != 0) {
            NotificationUtil(context).cancelDownloadNotification(notificationId)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbManager = DBManager.getInstance(context)
                val source = dbManager.observeSourcesDao.getByID(sourceId)

                if (
                    !source.retryMissingDownloads ||
                    source.status == ObserveSourcesRepository.SourceStatus.STOPPED
                ) {
                    return@launch
                }

                if (source.retryPromptedLinks.contains(url)) return@launch

                // Ignore is final immediately. Download is recorded by the worker only
                // after the target has actually been inserted into the download queue.
                if (decision == ACTION_IGNORE) {
                    source.retryPromptedLinks.add(url)
                    if (!source.ignoredLinks.contains(url)) {
                        source.ignoredLinks.add(url)
                    }
                    dbManager.observeSourcesDao.update(source)
                }

                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                val networkType = if (preferences.getBoolean("metered_networks", true)) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.UNMETERED
                }
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build()
                val input = Data.Builder()
                    .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId)
                    .putString(ObserveSourceWorker.INPUT_CONFIRMED_URL, url)
                    .putString(ObserveSourceWorker.INPUT_CONFIRMATION_DECISION, decision)
                    .build()
                val request = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
                    .addTag("observeSources")
                    .addTag("observation_$sourceId")
                    .addTag(sourceId.toString())
                    .setConstraints(constraints)
                    .setInputData(input)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    "OBSERVE$sourceId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (error: Exception) {
                Log.e("ObserveRetryDecision", "Failed to apply retry decision", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.ireum.ytdl.action.OBSERVE_RETRY_DOWNLOAD"
        const val ACTION_IGNORE = "com.ireum.ytdl.action.OBSERVE_RETRY_IGNORE"
        const val EXTRA_SOURCE_ID = "sourceId"
        const val EXTRA_URL = "url"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        private val VALID_ACTIONS = setOf(ACTION_DOWNLOAD, ACTION_IGNORE)
    }
}
