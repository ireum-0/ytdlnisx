package com.ireum.ytdl.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.HistoryDateFetchRepository
import com.ireum.ytdl.util.HistoryDateFetchNotification
import com.ireum.ytdl.util.HistoryDateFetchNotificationPolicy
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HistoryDateFetchManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = HistoryDateFetchRepository(DBManager.getInstance(appContext))
    private val workManager = WorkManager.getInstance(appContext)
    private val notification = HistoryDateFetchNotification(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun startOrReconnect() {
        scope.launch {
            val operation = repository.createOrReconnect()
            if (operation.candidateCount == 0) {
                val transitioned = repository.finishCompleted(operation.operationId)
                if (HistoryDateFetchNotificationPolicy.emitTerminal(transitioned)) {
                    repository.progress(operation.operationId)?.let(notification::notifyTerminal)
                }
                return@launch
            }
            repository.progress(operation.operationId)?.let(notification::updateActive)
            enqueue(operation.operationId)
        }
    }

    fun cancel(operationId: String, onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                if (!repository.requestCancellation(operationId)) return@launch
                workManager.cancelUniqueWork(HistoryDateFetchWorker.uniqueWorkName(operationId))
                stopExtractor(operationId)
                val transitioned = repository.finishCancellation(operationId)
                if (HistoryDateFetchNotificationPolicy.emitTerminal(transitioned)) {
                    repository.progress(operationId)?.let(notification::notifyTerminal)
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    suspend fun reconcile() {
        repository.getNonterminalOperations()
            .filter(HistoryDateFetchNotificationPolicy::restoreAtStartup)
            .forEach { operation ->
                repository.progress(operation.operationId)?.let(notification::updateActive)
                enqueue(operation.operationId)
            }
    }

    private fun enqueue(operationId: String) {
        val request = OneTimeWorkRequestBuilder<HistoryDateFetchWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(
                Data.Builder().putString(HistoryDateFetchWorker.KEY_OPERATION_ID, operationId).build()
            )
            .addTag(HistoryDateFetchWorker.GLOBAL_TAG)
            .addTag(HistoryDateFetchWorker.operationTag(operationId))
            .build()
        workManager.enqueueUniqueWork(
            HistoryDateFetchWorker.uniqueWorkName(operationId),
            HistoryDateFetchEnqueuePolicy.workPolicy,
            request,
        )
    }

    private fun stopExtractor(operationId: String) {
        val processId = HistoryDateFetchWorker.processId(operationId)
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        runCatching { YoutubeDLCompat.destroyProcessById(processId) }
    }

    companion object {
        @Volatile
        private var instance: HistoryDateFetchManager? = null

        fun get(context: Context): HistoryDateFetchManager =
            instance ?: synchronized(this) {
                instance ?: HistoryDateFetchManager(context).also { instance = it }
            }
    }
}

internal object HistoryDateFetchEnqueuePolicy {
    val workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
}
