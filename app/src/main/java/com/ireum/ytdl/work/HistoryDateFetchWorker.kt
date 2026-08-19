package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.HistoryDateFetchRepository
import com.ireum.ytdl.util.HistoryDateFetchNotification
import com.ireum.ytdl.util.HistoryDateFetchNotificationPolicy
import com.ireum.ytdl.util.HistoryDateLookupOrigin
import com.ireum.ytdl.util.HistoryDateResolutionEngine
import com.ireum.ytdl.util.KnownMediaPublishedDateIndex
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class HistoryDateFetchWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val database by lazy { DBManager.getInstance(context) }
    private val repository by lazy { HistoryDateFetchRepository(database) }
    private val notification by lazy { HistoryDateFetchNotification(context) }

    override suspend fun doWork(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID).orEmpty()
        if (operationId.isBlank()) return Result.failure()
        val operation = repository.getOperation(operationId) ?: return Result.failure()
        if (operation.stateValue.isTerminal) return Result.success()

        return try {
            setForeground(foregroundInfo(operationId))
            fetchPendingDates(operationId)
            val transitioned = repository.finalizeWorkerRun(operationId) != null
            if (HistoryDateFetchNotificationPolicy.emitTerminal(transitioned)) {
                repository.progress(operationId)?.let {
                    logTerminalMetrics(it)
                    notification.notifyTerminal(it)
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            val latest = repository.getOperation(operationId)
            if (latest?.cancelRequested == true) {
                val transitioned = repository.finishCancellation(operationId)
                if (HistoryDateFetchNotificationPolicy.emitTerminal(transitioned)) {
                    repository.progress(operationId)?.let(notification::notifyTerminal)
                }
                Result.success()
            } else {
                throw cancelled
            }
        } catch (error: Exception) {
            Log.e(TAG, "Date-fetch coordinator failed attempt=$runAttemptCount", error)
            if (runAttemptCount < MAX_COORDINATOR_ATTEMPTS - 1) {
                Result.retry()
            } else {
                val transitioned = repository.finishFailed(operationId)
                if (HistoryDateFetchNotificationPolicy.emitTerminal(transitioned)) {
                    repository.progress(operationId)?.let(notification::notifyTerminal)
                }
                Result.failure()
            }
        } finally {
            stopExtractor(operationId)
        }
    }

    private suspend fun fetchPendingDates(operationId: String) {
        val pending = repository.getPendingItems(operationId)
        if (pending.isEmpty()) return
        val knownDates = repository.knownDates()
        val knownDateIndex = KnownMediaPublishedDateIndex(knownDates)
        val ytdlp = YTDLPUtil(context, database.commandTemplateDao)
        val groups = pending.groupBy { it.sourceGroupKey.ifBlank { "source-${it.historyId}" } }
            .toSortedMap(compareBy { key ->
                key.substringAfterLast('-').toLongOrNull() ?: Long.MAX_VALUE
            })

        groups.values.forEachIndexed { index, groupItems ->
            ensureRunning(operationId)
            val source = groupItems.first().sourceUrlSnapshot
            val localValues = knownDateIndex.valuesFor(source)
            val started = SystemClock.elapsedRealtime()
            val lookup = HistoryDateResolutionEngine.resolve(
                localValues = localValues,
                cachedValues = {
                    ytdlp.getCachedInfoJsonResultsOrThrow(source).map { it.mediaPublishedAt }
                },
                minimalLookup = {
                    ytdlp.getDateOnlyMetadata(source, processId(operationId))?.mediaPublishedAt
                },
                compatibilityLookup = {
                    ytdlp.getCompatibilityDateMetadata(
                        source,
                        processId(operationId),
                    )?.mediaPublishedAt
                },
                ensureRunning = { ensureRunning(operationId) },
            )
            ensureRunning(operationId)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (!repository.checkpointSourceGroup(operationId, groupItems, lookup, elapsed)) {
                ensureRunning(operationId)
            }
            Log.i(
                TAG,
                "source=${index + 1}/${groups.size} items=${groupItems.size} " +
                    "origin=${lookup.origin} launches=${lookup.extractorLaunches} " +
                    "fallbacks=${lookup.compatibilityFallbacks} elapsedMs=$elapsed",
            )
            repository.progress(operationId)?.let(notification::updateActive)
        }
    }

    private suspend fun ensureRunning(operationId: String) {
        currentCoroutineContext().ensureActive()
        if (isStopped) throw CancellationException("Date-fetch coordinator stopped")
        val operation = repository.getOperation(operationId)
            ?: throw CancellationException("Date-fetch operation was removed")
        if (operation.cancelRequested || operation.stateValue.isTerminal) {
            throw CancellationException("Date-fetch operation cancelled or finished")
        }
    }

    private suspend fun foregroundInfo(operationId: String): ForegroundInfo {
        val progress = repository.progress(operationId)
            ?: throw IllegalStateException("Missing date-fetch progress")
        val built = notification.buildActive(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                HistoryDateFetchNotification.NOTIFICATION_ID,
                built,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(HistoryDateFetchNotification.NOTIFICATION_ID, built)
        }
    }

    private fun logTerminalMetrics(progress: com.ireum.ytdl.util.HistoryDateFetchProgress) {
        val operation = progress.operation
        val minutes = operation.elapsedMs.toDouble() / 60_000.0
        val sourcesPerMinute = if (minutes > 0.0) operation.processedSourceCount / minutes else 0.0
        val itemsPerMinute = if (minutes > 0.0) progress.counts.processed / minutes else 0.0
        Log.i(
            TAG,
            "terminal state=${operation.state} items=${operation.candidateCount} " +
                "sources=${operation.uniqueSourceCount} localHits=${operation.localHits} " +
                "cacheHits=${operation.cacheHits} coalesced=${operation.duplicateCoalesced} " +
                "launches=${operation.extractorLaunches} fallbacks=${operation.compatibilityFallbacks} " +
                "elapsedMs=${operation.elapsedMs} sourcesPerMinute=${"%.2f".format(sourcesPerMinute)} " +
                "itemsPerMinute=${"%.2f".format(itemsPerMinute)}",
        )
    }

    private fun stopExtractor(operationId: String) {
        val id = processId(operationId)
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        runCatching { YoutubeDLCompat.destroyProcessById(id) }
    }

    companion object {
        const val KEY_OPERATION_ID = "operation_id"
        const val GLOBAL_TAG = "history_date_fetch"
        private const val UNIQUE_WORK_PREFIX = "history_date_fetch_"
        private const val OPERATION_TAG_PREFIX = "history_date_fetch_operation_"
        private const val PROCESS_PREFIX = "history_date_fetch_process_"
        private const val TAG = "HistoryDateFetch"
        private const val MAX_COORDINATOR_ATTEMPTS = 3

        fun uniqueWorkName(operationId: String) = "$UNIQUE_WORK_PREFIX$operationId"
        fun operationTag(operationId: String) = "$OPERATION_TAG_PREFIX$operationId"
        fun processId(operationId: String) = "$PROCESS_PREFIX$operationId"
    }
}
