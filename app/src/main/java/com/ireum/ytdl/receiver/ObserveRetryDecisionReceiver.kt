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
import androidx.work.Operation
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.ObserveSourceWorker
import com.ireum.ytdl.work.WorkManagerHandoffRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ObserveRetryDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, 0L)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val decision = intent.action.orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val notificationFingerprint = intent.getStringExtra(EXTRA_CONFIG_FINGERPRINT).orEmpty()

        if (sourceId <= 0L || url.isBlank() || decision !in VALID_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val dbManager = DBManager.getInstance(appContext)
                val source = dbManager.observeSourcesDao.getByID(sourceId)

                if (
                    !source.retryMissingDownloads ||
                    source.status == ObserveSourcesRepository.SourceStatus.STOPPED
                ) {
                    return@launch
                }

                val canonicalUrl = com.ireum.ytdl.util.LinkUtil.canonicalYoutubeVideoUrlOrSelf(url)
                val currentFingerprint = WorkManagerHandoffRecovery.observeConfigFingerprint(source)
                if (
                    notificationFingerprint.isNotBlank() &&
                    notificationFingerprint != currentFingerprint
                ) {
                    Log.i(TAG, "Ignoring stale Observe Retry notification sourceId=$sourceId")
                    return@launch
                }

                if (source.retryPromptedLinks.any { existing ->
                        com.ireum.ytdl.util.LinkUtil.canonicalYoutubeVideoUrlOrSelf(existing) == canonicalUrl
                    }) return@launch

                if (decision == ACTION_DOWNLOAD) {
                    // Persist the exact user command before removing its only
                    // notification carrier.  The row owns retries until the
                    // exact WorkManager Operation is accepted and the worker
                    // records queue insertion/refusal.
                    val handoffId = WorkManagerHandoffRecovery.prepareObserveRetryDownload(
                        context = appContext,
                        sourceId = sourceId,
                        confirmedUrl = canonicalUrl,
                        configFingerprint = currentFingerprint,
                    )
                    if (notificationId != 0) {
                        NotificationUtil(appContext).cancelDownloadNotification(notificationId)
                    }
                    val outcome = WorkManagerHandoffRecovery
                        .enqueueAndAwait(appContext, handoffId)
                        .await()
                    if (!outcome.accepted && !outcome.superseded) {
                        Log.w(TAG, "Observe Retry Download remains recoverable", outcome.failure)
                    }
                    return@launch
                }

                if (notificationId != 0) {
                    NotificationUtil(appContext).cancelDownloadNotification(notificationId)
                }

                // Ignore is final immediately. Download is recorded by the worker only
                // after the target has actually been inserted into the download queue.
                if (decision == ACTION_IGNORE) {
                    if (!source.retryPromptedLinks.contains(canonicalUrl)) {
                        source.retryPromptedLinks.add(canonicalUrl)
                    }
                    if (!source.ignoredLinks.contains(canonicalUrl)) {
                        source.ignoredLinks.add(canonicalUrl)
                    }
                    dbManager.observeSourcesDao.update(source)
                }

                // The Ignore decision is already durable above; the existing
                // observation enqueue remains independent of the Download
                // command carrier.
                val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
                val networkType = if (preferences.getBoolean("metered_networks", true)) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.UNMETERED
                }
                val request = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
                    .addTag("observeSources")
                    .addTag("observation_$sourceId")
                    .addTag(sourceId.toString())
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(networkType).build()
                    )
                    .setInputData(
                        Data.Builder()
                            .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId)
                            .putString(ObserveSourceWorker.INPUT_CONFIRMED_URL, canonicalUrl)
                            .putString(ObserveSourceWorker.INPUT_CONFIRMATION_DECISION, decision)
                            .build()
                    )
                    .build()
                val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                    "OBSERVE$sourceId",
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                awaitOperation(operation)?.let { failure ->
                    // Ignore was already durably recorded.  Keep this receiver
                    // alive through the exact scheduler handoff and report the
                    // failure instead of silently discarding Operation.result.
                    Log.w(TAG, "Observe Retry Ignore follow-up remains recoverable", failure)
                }
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
        const val EXTRA_CONFIG_FINGERPRINT = "configFingerprint"

        private const val TAG = "ObserveRetryDecision"

        private val VALID_ACTIONS = setOf(ACTION_DOWNLOAD, ACTION_IGNORE)
    }

    private suspend fun awaitOperation(operation: Operation): Throwable? =
        suspendCancellableCoroutine { continuation ->
            operation.result.addListener(
                {
                    val failure = try {
                        operation.result.get()
                        null
                    } catch (error: Throwable) {
                        error
                    }
                    if (continuation.isActive) continuation.resume(failure)
                },
                Runnable::run,
            )
        }
}
