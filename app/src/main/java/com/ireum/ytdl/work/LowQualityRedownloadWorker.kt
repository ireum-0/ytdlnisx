package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryRedownloadItemFactory
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.HistoryReplacementSourceIdentity
import com.ireum.ytdl.util.HistoryVideoQualityProbe
import com.ireum.ytdl.util.LowQualityAssessment
import com.ireum.ytdl.util.LowQualityAssessmentInput
import com.ireum.ytdl.util.LowQualityRedownloadNotification
import com.ireum.ytdl.util.MediaPublishedDateSource
import com.ireum.ytdl.util.VideoQualityPolicy
import com.ireum.ytdl.util.WebUrlInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

class LowQualityRedownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    private val database by lazy { DBManager.getInstance(context) }
    private val repository by lazy { LowQualityRedownloadRepository(database) }
    private val notification by lazy { LowQualityRedownloadNotification(context) }

    override suspend fun doWork(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID).orEmpty()
        if (operationId.isBlank()) return Result.failure()
        val operation = repository.getOperation(operationId) ?: return Result.failure()
        if (operation.stateValue.isTerminal) {
            return Result.success()
        }

        return try {
            setForeground(foregroundInfo(operationId))
            when (operation.phaseValue) {
                LowQualityRedownloadPhase.SCANNING -> scan(operationId)
                LowQualityRedownloadPhase.PREPARING -> prepare(operationId)
                LowQualityRedownloadPhase.QUEUEING -> queuePersistedDownloads(operationId)
                LowQualityRedownloadPhase.DOWNLOADING,
                LowQualityRedownloadPhase.FINALIZING -> {
                    repository.reconcileLinkedDownloads(operationId)
                    repository.progress(operationId)?.let { notification.update(it) }
                }
                LowQualityRedownloadPhase.AWAITING_SELECTION ->
                    repository.progress(operationId)?.let { notification.update(it) }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            val latest = repository.getOperation(operationId)
            if (latest?.cancelRequested == true) {
                repository.finalizeIfReady(operationId)
                // Phase one is already durable. Keep a live convergence
                // owner for phase two even when this coordinator attempt is
                // the thing that received cancellation.
                LowQualityRedownloadLedger.scheduleCancellationConvergence(
                    context,
                    operationId,
                )
                repository.progress(operationId)?.let { notification.update(it) }
                Result.success()
            } else {
                throw cancelled
            }
        } catch (error: Exception) {
            Log.e(TAG, "Coordinator failed operation=$operationId attempt=$runAttemptCount", error)
            if (runAttemptCount < MAX_COORDINATOR_ATTEMPTS - 1) {
                Result.retry()
            } else {
                val result = repository.failCoordinatorWithPublications(
                    operationId = operationId,
                    context = context,
                )
                DownloadCancellationRegistry.publish(result.publications)
                result.publications.forEach { publication ->
                    withDownloadWorkerExecutionSideEffectLease(
                        publication.downloadId,
                        publication.executionId,
                    ) {
                        check(
                            DownloadWorker.cancelProcessesForExecution(
                                publication.downloadId,
                                publication.executionId,
                            )
                        ) {
                            "Native cancellation was not acknowledged for ${publication.downloadId}"
                        }
                        check(
                            DownloadExecutionRecovery.markNativeQuiescent(
                                context = context,
                                downloadId = publication.downloadId,
                                executionId = publication.executionId,
                            )
                        ) {
                            "Native cancellation recovery carrier was not acknowledged for " +
                                publication.downloadId
                        }
                    }
                }
                repository.progress(operationId)?.let { notification.update(it) }
                Result.failure()
            }
        }
    }

    private suspend fun scan(operationId: String) {
        var operation = repository.getOperation(operationId) ?: return
        while (operation.scanCursorHistoryId < operation.scanUpperBoundHistoryId) {
            ensureRunning(operationId)
            val page = database.historyDao.getVideoQualityScanPage(
                cursor = operation.scanCursorHistoryId,
                upperBound = operation.scanUpperBoundHistoryId,
                limit = SCAN_PAGE_SIZE
            )
            if (page.isEmpty()) break
            page.forEach { historyItem ->
                ensureRunning(operationId)
                var failed = false
                val candidate = try {
                    inspectCandidate(operationId, historyItem)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    Log.w(
                        TAG,
                        "Local quality probe failed historyId=${historyItem.id} type=${error.javaClass.simpleName}"
                    )
                    null
                }
                repository.checkpointScan(operationId, historyItem.id, candidate, failed)
                repository.progress(operationId)?.let { notification.update(it, force = false) }
            }
            operation = repository.getOperation(operationId) ?: return
        }

        if (database.lowQualityRedownloadDao.countItems(operationId) == 0) {
            repository.finishNoCandidates(operationId)
        } else {
            repository.advancePhase(
                operationId,
                LowQualityRedownloadPhase.SCANNING,
                LowQualityRedownloadPhase.AWAITING_SELECTION
            )
        }
        repository.progress(operationId)?.let { notification.update(it) }
    }

    private fun inspectCandidate(
        operationId: String,
        historyItem: HistoryItem
    ): LowQualityRedownloadItem? {
        val extractorEligible = isExtractorEligible(historyItem)
        if (!extractorEligible || historyItem.hardSubDone) return null
        val marker = HistoryRedownloadMarker.regular(historyItem.id)
        if (database.downloadDao.countPendingByPlaylistMarker(marker) > 0) return null
        val activeDuplicate = database.downloadDao.getPendingObservationDownloadsList().any { pending ->
            isBlockingLowQualityReplacementDuplicate(
                status = pending.status,
                isVideo = pending.type == DownloadType.video,
                sameSource = MediaPublishedDateSource.matches(pending.url, historyItem.url)
            )
        }
        if (activeDuplicate) return null
        val requestedHeight = VideoQualityPolicy.requestedHeight(historyItem.format) ?: return null
        val media = HistoryVideoQualityProbe.probe(context, historyItem.downloadPath)
        if (!VideoQualityPolicy.isPreliminaryCandidate(requestedHeight, media)) return null
        val assessment = VideoQualityPolicy.assess(
            LowQualityAssessmentInput(
                isVideo = historyItem.type == DownloadType.video,
                extractorSourceEligible = true,
                activeDuplicate = false,
                incognitoEnabled = false,
                hardSubDone = false,
                requestedHeight = requestedHeight,
                sourceMaxHeight = requestedHeight,
                media = media
            )
        ) as? LowQualityAssessment.Candidate ?: return null
        return LowQualityRedownloadItem(
            operationId = operationId,
            historyId = historyItem.id,
            intendedSourceUrl = historyItem.url,
            intendedType = historyItem.type.name,
            candidateReason = assessment.reason.name,
            mediaState = media.state.name,
            actualHeight = assessment.actualHeight,
            requestedHeight = requestedHeight,
            expectedHeight = assessment.expectedHeight,
            sourceMaxHeight = assessment.sourceMaxHeight,
            selected = false,
            itemState = LowQualityRedownloadItemState.PROVISIONAL.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun prepare(operationId: String) {
        val resultRepository = ResultRepository(
            database.resultDao,
            database.commandTemplateDao,
            context
        )
        val factory = HistoryRedownloadItemFactory(context, database)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val selected = database.lowQualityRedownloadDao.getSelectedItems(operationId)

        selected.forEach { ledgerItem ->
            ensureRunning(operationId)
            if (ledgerItem.downloadId != null || ledgerItem.stateValue.isTerminal) return@forEach
            repository.setItemState(
                operationId,
                ledgerItem.historyId,
                LowQualityRedownloadItemState.CHECKING
            )
            try {
                val historyItem = database.historyDao.getNullableItem(ledgerItem.historyId)
                if (historyItem == null) {
                    skip(operationId, ledgerItem.historyId, "HISTORY_MISSING")
                    return@forEach
                }
                if (ledgerItem.intendedSourceUrl.isBlank()) {
                    fail(operationId, ledgerItem.historyId, "SELECTION_IDENTITY_MISSING")
                    return@forEach
                }
                if (ledgerItem.intendedType.isBlank()) {
                    fail(operationId, ledgerItem.historyId, "SELECTION_TYPE_MISSING")
                    return@forEach
                }
                if (ledgerItem.intendedType != historyItem.type.name) {
                    skip(operationId, ledgerItem.historyId, "SELECTION_TYPE_CHANGED")
                    return@forEach
                }
                if (!HistoryReplacementSourceIdentity.matches(
                        ledgerItem.intendedSourceUrl,
                        historyItem.url
                    )
                ) {
                    skip(operationId, ledgerItem.historyId, "SELECTION_SOURCE_CHANGED")
                    return@forEach
                }
                if (!isExtractorEligible(historyItem) || historyItem.hardSubDone) {
                    skip(operationId, ledgerItem.historyId, "SOURCE_INELIGIBLE")
                    return@forEach
                }
                VideoQualityPolicy.replacementPersistenceSkipReason(
                    preferences.getBoolean("incognito", false)
                )?.let { reason ->
                    skip(
                        operationId,
                        ledgerItem.historyId,
                        reason.name
                    )
                    return@forEach
                }
                if (database.downloadDao.countPendingByPlaylistMarker(
                        HistoryRedownloadMarker.regular(historyItem.id)
                    ) > 0
                ) {
                    skip(operationId, ledgerItem.historyId, "DUPLICATE")
                    return@forEach
                }
                val activeDuplicate = database.downloadDao.getPendingObservationDownloadsList().any { pending ->
                    isBlockingLowQualityReplacementDuplicate(
                        status = pending.status,
                        isVideo = pending.type == DownloadType.video,
                        sameSource = MediaPublishedDateSource.matches(pending.url, historyItem.url)
                    )
                }
                if (activeDuplicate) {
                    skip(operationId, ledgerItem.historyId, "DUPLICATE")
                    return@forEach
                }
                val currentMedia = HistoryVideoQualityProbe.probe(context, historyItem.downloadPath)
                val sourceResult = resultRepository.getSingleMetadataFromUrl(historyItem.url)
                if (sourceResult == null) {
                    fail(operationId, ledgerItem.historyId, "METADATA_EMPTY")
                    return@forEach
                }
                val sourceMaximum = VideoQualityPolicy.maxSourceHeight(sourceResult.formats)
                val assessment = VideoQualityPolicy.assess(
                    LowQualityAssessmentInput(
                        isVideo = historyItem.type == DownloadType.video,
                        extractorSourceEligible = true,
                        activeDuplicate = false,
                        incognitoEnabled = preferences.getBoolean("incognito", false),
                        hardSubDone = historyItem.hardSubDone,
                        requestedHeight = ledgerItem.requestedHeight,
                        sourceMaxHeight = sourceMaximum,
                        media = currentMedia
                    )
                )
                val verified = assessment as? LowQualityAssessment.Candidate
                if (verified == null) {
                    val reason = (assessment as? LowQualityAssessment.Skipped)?.reason?.name
                        ?: "NO_LONGER_ELIGIBLE"
                    skip(operationId, ledgerItem.historyId, reason)
                    return@forEach
                }
                database.lowQualityRedownloadDao.updateQualification(
                    operationId,
                    ledgerItem.historyId,
                    verified.reason.name,
                    currentMedia.state.name,
                    verified.actualHeight,
                    verified.expectedHeight,
                    verified.sourceMaxHeight,
                    System.currentTimeMillis()
                )
                val download = factory.createQualityReplacement(
                    historyItem,
                    verified.expectedHeight,
                    sourceResult.formats
                ).apply {
                    this.operationId = UUID.randomUUID().toString()
                }
                // This is the authoritative persistence-boundary snapshot. It is intentionally
                // not retroactive once the Download row has been committed.
                VideoQualityPolicy.replacementPersistenceSkipReason(
                    preferences.getBoolean("incognito", false)
                )?.let { reason ->
                    skip(
                        operationId,
                        ledgerItem.historyId,
                        reason.name
                    )
                    return@forEach
                }
                repository.linkDownloadAtomically(operationId, ledgerItem.historyId, download)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "Qualification failed historyId=${ledgerItem.historyId} type=${error.javaClass.simpleName}"
                )
                fail(operationId, ledgerItem.historyId, "METADATA_LOOKUP_FAILED")
            } finally {
                repository.progress(operationId)?.let { notification.update(it) }
            }
        }

        if (
            repository.advancePhase(
                operationId,
                LowQualityRedownloadPhase.PREPARING,
                LowQualityRedownloadPhase.QUEUEING
            )
        ) {
            queuePersistedDownloads(operationId)
        }
    }

    private suspend fun queuePersistedDownloads(operationId: String) {
        ensureRunning(operationId)
        // A stopped coordinator can resume after the download row and ledger link committed but
        // before the normal scheduler was triggered. Rebuild that scheduler input from Room so
        // restart recovery neither loses nor duplicates the child download.
        val persistedQueued = repository.reconcileLinkedDownloads(operationId)
            .filter { it.status == DownloadRepository.Status.Queued.name }
        val operation = repository.getOperation(operationId)
        if (
            operation?.stateValue == LowQualityRedownloadOperationState.RUNNING &&
            !operation.cancelRequested &&
            persistedQueued.isNotEmpty()
        ) {
            startNormalQueue(persistedQueued.distinctBy(DownloadItem::id))
        }
        repository.advancePhase(
            operationId,
            LowQualityRedownloadPhase.QUEUEING,
            LowQualityRedownloadPhase.DOWNLOADING
        )
        repository.finalizeIfReady(operationId)
        repository.progress(operationId)?.let { notification.update(it) }
    }

    private suspend fun startNormalQueue(queued: List<DownloadItem>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val scheduler = AlarmScheduler(context)
        when (
            lowQualityQueueStartDecision(
                useScheduler = preferences.getBoolean("use_scheduler", false),
                isDuringScheduledTime = scheduler.isDuringTheScheduledTime(),
                canScheduleExactAlarm = scheduler.canSchedule()
            )
        ) {
            LowQualityQueueStartDecision.SCHEDULE -> scheduler.schedule()
            LowQualityQueueStartDecision.START_NOW_DISABLE_SCHEDULER -> {
                // Match the normal queue's permission fallback. apply() changes the in-memory
                // value synchronously, so the DownloadWorker cannot immediately self-stop.
                preferences.edit().putBoolean("use_scheduler", false).apply()
                DownloadRepository(database).startDownloadWorker(queued, context)
            }
            LowQualityQueueStartDecision.START_NOW ->
                DownloadRepository(database).startDownloadWorker(queued, context)
        }
    }

    private suspend fun ensureRunning(operationId: String) {
        currentCoroutineContext().ensureActive()
        if (isStopped) throw CancellationException("Low-quality coordinator stopped")
        val operation = repository.getOperation(operationId)
            ?: throw CancellationException("Low-quality operation was removed")
        if (operation.cancelRequested || operation.stateValue.isTerminal) {
            throw CancellationException("Low-quality operation cancelled or finished")
        }
    }

    private fun isExtractorEligible(item: HistoryItem): Boolean =
        item.type == DownloadType.video &&
            WebUrlInput.resolveExtractorInput(item.url) != null &&
            item.localTreeUri.isBlank() &&
            item.localTreePath.isBlank() &&
            !item.format.format_id.equals("local", ignoreCase = true) &&
            !item.format.format_id.startsWith("local+", ignoreCase = true)

    private suspend fun skip(operationId: String, historyId: Long, reason: String) {
        repository.setItemState(operationId, historyId, LowQualityRedownloadItemState.SKIPPED, reason)
    }

    private suspend fun fail(operationId: String, historyId: Long, reason: String) {
        repository.setItemState(operationId, historyId, LowQualityRedownloadItemState.FAILED, reason)
    }

    private suspend fun foregroundInfo(operationId: String): ForegroundInfo {
        val progress = repository.progress(operationId)
            ?: throw IllegalStateException("Missing low-quality progress")
        val built = notification.build(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                LowQualityRedownloadNotification.NOTIFICATION_ID,
                built,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(LowQualityRedownloadNotification.NOTIFICATION_ID, built)
        }
    }

    companion object {
        const val KEY_OPERATION_ID = "operation_id"
        const val GLOBAL_TAG = "low_quality_redownload"
        const val UNIQUE_WORK_PREFIX = "low_quality_redownload_"
        const val OPERATION_TAG_PREFIX = "low_quality_redownload_operation_"
        private const val TAG = "LowQualityRedownload"
        private const val SCAN_PAGE_SIZE = 25
        private const val MAX_COORDINATOR_ATTEMPTS = 3

        fun operationTag(operationId: String) = "$OPERATION_TAG_PREFIX$operationId"
        fun uniqueWorkName(operationId: String) = "$UNIQUE_WORK_PREFIX$operationId"
    }
}

internal enum class LowQualityQueueStartDecision {
    SCHEDULE,
    START_NOW,
    START_NOW_DISABLE_SCHEDULER
}

internal fun lowQualityQueueStartDecision(
    useScheduler: Boolean,
    isDuringScheduledTime: Boolean,
    canScheduleExactAlarm: Boolean
): LowQualityQueueStartDecision = when {
    !useScheduler || isDuringScheduledTime -> LowQualityQueueStartDecision.START_NOW
    canScheduleExactAlarm -> LowQualityQueueStartDecision.SCHEDULE
    else -> LowQualityQueueStartDecision.START_NOW_DISABLE_SCHEDULER
}

private val LOW_QUALITY_REPLACEMENT_PENDING_STATUSES = setOf(
    DownloadRepository.Status.Active.name,
    DownloadRepository.Status.PostProcessing.name,
    DownloadRepository.Status.Queued.name,
    DownloadRepository.Status.WaitingForMembership.name,
    DownloadRepository.Status.Scheduled.name,
    DownloadRepository.Status.Paused.name,
    DownloadRepository.Status.Processing.name,
)

internal fun isBlockingLowQualityReplacementDuplicate(
    status: String,
    isVideo: Boolean,
    sameSource: Boolean,
): Boolean = isVideo && sameSource && status in LOW_QUALITY_REPLACEMENT_PENDING_STATUSES
