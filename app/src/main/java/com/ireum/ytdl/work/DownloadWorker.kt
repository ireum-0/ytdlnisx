package com.ireum.ytdl.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.ireum.ytdl.App
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.DownloadDao
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LogItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementAuthorization
import com.ireum.ytdl.database.repository.HistoryReplacementCleanupAction
import com.ireum.ytdl.database.repository.HistoryReplacementCleanupResult
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementExecutionOwnershipLostException
import com.ireum.ytdl.database.repository.HistoryReplacementQualityAuthorityLostException
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementRefusalPersistenceException
import com.ireum.ytdl.database.repository.HistoryReplacementOutcome
import com.ireum.ytdl.database.repository.HistoryReplacementOutcomePolicy
import com.ireum.ytdl.database.repository.HistoryReplacementTerminalAction
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.database.repository.LogRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.database.repository.DownloadExecutionOwnershipLostException
import com.ireum.ytdl.util.Extensions.getIDFromYoutubeURL
import com.ireum.ytdl.util.Extensions.getMediaDuration
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import com.ireum.ytdl.util.Extensions.toStringDuration
import com.ireum.ytdl.util.Extensions.toDurationSeconds
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.DownloadQualityDecision
import com.ireum.ytdl.util.DownloadQualityFallbackPolicy
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.HistoryReplacementFilePolicy
import com.ireum.ytdl.util.HistoryVideoQualityProbe
import com.ireum.ytdl.util.MediaPublishedDate
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.PendingDuplicateDownloadStore
import com.ireum.ytdl.util.process.ProcessQuiescence
import com.ireum.ytdl.util.SensitiveTextRedactor
import com.ireum.ytdl.util.SubtitleFileValidator
import com.ireum.ytdl.util.SubtitleFormatConverter
import com.ireum.ytdl.util.SubtitleSelection
import com.ireum.ytdl.util.StagedVideoQualityValidationPolicy
import com.ireum.ytdl.util.VideoMediaQuality
import com.ireum.ytdl.util.VideoFileQualityState
import com.ireum.ytdl.util.VideoQualityPolicy
import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueClassifier
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueSeverity
import com.ireum.ytdl.util.download.DownloadIssueSource
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.download.DownloadIssueStageTracker
import com.ireum.ytdl.util.download.DownloadIssueText
import com.ireum.ytdl.util.download.DownloadOutcome
import com.ireum.ytdl.util.download.composeCompletionOutcome
import com.ireum.ytdl.util.download.DownloadRetryMetadata
import com.ireum.ytdl.util.download.DownloadRetryPolicy
import com.ireum.ytdl.util.download.DownloadRetryStrategy
import com.ireum.ytdl.util.download.DownloadSuggestedAction
import com.ireum.ytdl.util.download.MembershipAccessDetector
import com.ireum.ytdl.util.download.MembershipAccessPolicy
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeMediaAccessPolicy
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeMediaAccessProfile
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeMediaAttemptSet
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeMediaFailureKind
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeQualityRouteInput
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeQualityRouteOutcome
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpRetryLog
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.util.storage.AndroidHistoryFileDeletionGateway
import com.ireum.ytdl.util.storage.HistoryDeletionRecord
import com.ireum.ytdl.util.storage.HistoryDeletionSummary
import com.ireum.ytdl.util.storage.HistoryFileDeletionEngine
import com.ireum.ytdl.util.storage.HistoryReferenceMutationCoordinator
import com.ireum.ytdl.util.storage.HistoryFileDeletionGateway
import com.ireum.ytdl.util.storage.referencesSameFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Regex


/**
 * Production startup boundary: per-Download recovery debt is retained by
 * DownloadExecutionRecovery, while a healthy queue may still be observed.
 * Only a genuinely global reconciliation failure escapes before admission.
 */
internal data class DownloadQueueAdmission(
    val recovery: DownloadExecutionRecovery.ReconcileResult,
    val queuedItems: Flow<List<DownloadItem>>,
)

internal suspend fun observeQueuedDownloadsAfterRecovery(
    context: Context,
    dbManager: DBManager,
    priorityItemIds: List<Long>,
    currentTimeMillis: Long,
): DownloadQueueAdmission {
    val recovery = DownloadExecutionRecovery.reconcile(context, dbManager)
    if (recovery.deferredDownloadIds.isNotEmpty()) {
        Log.w(
            "DownloadWorker",
            "Queue admission continues with per-Download recovery debt ids=" +
                recovery.deferredDownloadIds,
        )
    }
    val queuedItems = when (DownloadQueuePolicy.observationMode(priorityItemIds)) {
        DownloadQueueObservationMode.STANDARD ->
            dbManager.downloadDao.getQueuedScheduledDownloadsUntil(currentTimeMillis)
        DownloadQueueObservationMode.PRIORITY ->
            dbManager.downloadDao.getQueuedScheduledDownloadsUntilWithPriority(
                currentTimeMillis,
                priorityItemIds,
            )
    }
    return DownloadQueueAdmission(recovery, queuedItems)
}


class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    private val workerDownloadIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val workerCleanupDownloadIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val workerAuthoritativeIssues: MutableMap<Long, DownloadIssue> = ConcurrentHashMap()
    private val workerExecutionIds: MutableMap<Long, String> = ConcurrentHashMap()

    private data class CleanupSnapshot(
        val activeIds: List<Long>,
        val executionIds: Map<Long, String>,
        val authoritativeIssues: Map<Long, DownloadIssue>,
        val workerExecutionIds: Map<Long, String>,
        val committedHistoryReplacementIds: Set<Long>,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val workNotif = NotificationUtil(App.instance).createDefaultWorkerNotification()

        return ForegroundInfo(
            1000000000,
            workNotif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun cleanupStoppedWorker(
        includeStaleRows: Boolean = true,
        propagateRequeueFailure: Boolean = false,
    ) = withContext(Dispatchers.IO + NonCancellable) {
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val snapshot = withDownloadWorkerExecutionLock {
            val workerOwnedIds = (workerCleanupDownloadIds + workerDownloadIds).distinct()
            val staleItems = if (includeStaleRows) {
                dao.getActiveAndPostProcessingDownloadsList()
                    .filter { item ->
                        !DownloadWorkerExecutionOwners.isOwnedBy(
                            item.id,
                            item.executionId,
                        )
                    }
            } else {
                emptyList()
            }
            val staleDbIds = staleItems.map { it.id }
            val activeIds = (workerOwnedIds + staleDbIds).distinct()
            if (activeIds.isEmpty()) {
                return@withDownloadWorkerExecutionLock CleanupSnapshot(
                    activeIds = emptyList(),
                    executionIds = emptyMap(),
                    authoritativeIssues = emptyMap(),
                    workerExecutionIds = emptyMap(),
                    committedHistoryReplacementIds = emptySet(),
                )
            }

            val committedHistoryReplacementIds = dao.getDownloadsByIds(activeIds)
                .filter { item ->
                    val marker = HistoryRedownloadMarker.parse(item.playlistURL)
                    marker != null &&
                        dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId == item.id
                }
                .mapTo(linkedSetOf()) { it.id }

            val executionIds = workerExecutionIds.toMutableMap().apply {
                staleItems.forEach { item ->
                    if (item.id !in this) put(item.id, item.executionId)
                }
            }
            val authoritativeIssues = workerAuthoritativeIssues.toMutableMap().apply {
                staleItems.forEach { item ->
                    HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(item.lastIssueCode)
                        ?.let { put(item.id, it) }
                }
                dbManager.historyReplacementBarrierDao.getByDownloadIds(activeIds).forEach { barrier ->
                    HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(barrier.issueCode)
                        ?.let { put(barrier.downloadId, it) }
                }
            }

            CleanupSnapshot(
                activeIds = activeIds,
                executionIds = executionIds,
                authoritativeIssues = authoritativeIssues,
                workerExecutionIds = workerExecutionIds.toMap(),
                committedHistoryReplacementIds = committedHistoryReplacementIds,
            )
        }
        if (snapshot.activeIds.isEmpty()) return@withContext

        val releasedIds = linkedSetOf<Long>()
        val recoveryEligibleIds = linkedSetOf<Long>()
        val nativeQuiescenceBlockedIds = linkedSetOf<Long>()
        val recoveryPublicationFailedIds = linkedSetOf<Long>()
        var firstCleanupFailure: Exception? = null

        /**
         * The side-effect lease is acquired before the worker mutex, matching
         * withOwnedExecutionLease().  The current row is re-read while both
         * guards are held, so a stale cleanup snapshot cannot kill or mutate a
         * newer execution that claimed the same Download ID.
         */
        suspend fun withCleanupOwnership(
            downloadId: Long,
            requireRunning: Boolean,
            block: suspend (currentExecutionId: String) -> Unit,
        ): Boolean {
            val expectedExecutionId = snapshot.executionIds[downloadId].orEmpty()
            if (expectedExecutionId.isBlank()) {
                return withDownloadWorkerExecutionSideEffectLease(
                    downloadId = downloadId,
                    executionId = expectedExecutionId,
                ) {
                    val owned = withDownloadWorkerExecutionLock {
                        val current = dao.getNullableDownloadById(downloadId)
                        if (
                            current == null ||
                                current.executionId.isNotBlank() ||
                                (requireRunning && current.status !in setOf(
                                    DownloadRepository.Status.Active.name,
                                    DownloadRepository.Status.PostProcessing.name,
                                ))
                        ) {
                            false
                        } else {
                            true
                        }
                    }
                    if (!owned) return@withDownloadWorkerExecutionSideEffectLease false
                    block("")
                    true
                }
            }

            return withDownloadWorkerExecutionSideEffectLease(
                downloadId = downloadId,
                executionId = expectedExecutionId,
            ) {
                val owned = withDownloadWorkerExecutionLock {
                    val current = dao.getNullableDownloadById(downloadId)
                    if (
                        current == null ||
                            current.executionId != expectedExecutionId ||
                            (requireRunning && current.status !in setOf(
                                DownloadRepository.Status.Active.name,
                                DownloadRepository.Status.PostProcessing.name,
                            ))
                    ) {
                        false
                    } else {
                        true
                    }
                }
                if (!owned) return@withDownloadWorkerExecutionSideEffectLease false
                // Keep only the per-Download lease while native cancellation,
                // notification cleanup, and the execution-scoped DB CAS run.
                // The global claim/publication lock must not be held across
                // those side effects.
                block(expectedExecutionId)
                true
            }
        }

        snapshot.activeIds.forEach { downloadId ->
            try {
                val cleaned = withCleanupOwnership(
                    downloadId = downloadId,
                    requireRunning = false,
                ) { expectedExecutionId ->
                    // Publish the exceptional-exit carrier only after the
                    // exact row has been revalidated under this Download's
                    // lease.  A stale E1 snapshot can therefore never write
                    // an E1 journal over a newer E2 execution.
                    val current = dao.getNullableDownloadById(downloadId)
                        ?.takeIf { it.executionId == expectedExecutionId }
                        ?: error(
                            "Download execution changed before recovery publication for $downloadId"
                        )
                    val recoveryRecorded = DownloadExecutionRecovery.recordPending(
                        context = context,
                        item = current,
                        authoritativeIssue = snapshot.authoritativeIssues[downloadId],
                    )
                    if (!recoveryRecorded) {
                        recoveryPublicationFailedIds += downloadId
                        firstCleanupFailure = firstCleanupFailure.addOrSuppress(
                            IllegalStateException(
                                "Could not persist recovery responsibility for download $downloadId"
                            )
                        )
                    }
                    check(
                        cancelProcessesForExecution(downloadId, expectedExecutionId)
                    ) {
                        "Native process owner changed while cleaning download $downloadId"
                    }
                    if (recoveryRecorded) {
                        check(
                            DownloadExecutionRecovery.markNativeQuiescent(
                                context = context,
                                downloadId = downloadId,
                                executionId = expectedExecutionId,
                                exactGenerationProof = true,
                            )
                        ) {
                            "Native quiescence recovery carrier was not durable for download $downloadId"
                        }
                    }
                    runCatching {
                        NotificationUtil(context).cancelRunningDownloadNotification(downloadId.toInt())
                    }
                }
                if (!cleaned) {
                    Log.i(TAG, "Skipping stale process cleanup for newer execution id=$downloadId")
                }
            } catch (cancelled: CancellationException) {
                firstCleanupFailure = firstCleanupFailure.addOrSuppress(cancelled)
                nativeQuiescenceBlockedIds += downloadId
                recoveryEligibleIds += downloadId
            } catch (failure: Exception) {
                firstCleanupFailure = firstCleanupFailure.addOrSuppress(failure)
                nativeQuiescenceBlockedIds += downloadId
                recoveryEligibleIds += downloadId
            }
        }

        val refusalRepository = DownloadRepository(dbManager)
        snapshot.activeIds.forEach { downloadId ->
            if (downloadId in nativeQuiescenceBlockedIds) return@forEach
            try {
                val issue = snapshot.authoritativeIssues[downloadId]
                var released = false
                val owned = withCleanupOwnership(
                    downloadId = downloadId,
                    requireRunning = true,
                ) { executionId ->
                    val affected = when (
                        cleanupStoppedDownloadExecution(
                            repository = refusalRepository,
                            downloadId = downloadId,
                            executionId = executionId,
                            authoritativeIssue = issue,
                        )
                    ) {
                        DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                        DownloadRepository.RunningDownloadRequeueResult.REFUSAL_CONVERGED,
                        DownloadRepository.RunningDownloadRequeueResult.AUTHORITATIVE_ISSUE_CONVERGED -> 1
                        DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT -> 1
                        DownloadRepository.RunningDownloadRequeueResult.OWNERSHIP_LOST,
                        DownloadRepository.RunningDownloadRequeueResult.NOT_RUNNING -> 0
                    }

                    if (affected == 0) {
                        val current = dao.getNullableDownloadById(downloadId)
                        check(
                            current?.executionId != executionId ||
                                current.status !in setOf(
                                    DownloadRepository.Status.Active.name,
                                    DownloadRepository.Status.PostProcessing.name,
                                )
                        ) {
                            "Owned download $downloadId remained running after failed cleanup"
                        }
                    }
                    released = affected == 1 ||
                        (dao.getNullableDownloadById(downloadId)?.status !in setOf(
                            DownloadRepository.Status.Active.name,
                            DownloadRepository.Status.PostProcessing.name,
                        ))
                }
                if (owned && released) releasedIds += downloadId
            } catch (cancelled: CancellationException) {
                firstCleanupFailure = firstCleanupFailure.addOrSuppress(cancelled)
                // The durable row remains the recovery carrier.  Release only
                // this exact process owner so the next worker can reconcile it;
                // a newer execution token is never removed by this cleanup.
                recoveryEligibleIds += downloadId
            } catch (failure: Exception) {
                firstCleanupFailure = firstCleanupFailure.addOrSuppress(failure)
                recoveryEligibleIds += downloadId
            }
        }

        withDownloadWorkerExecutionLock {
            snapshot.workerExecutionIds.forEach { (downloadId, executionId) ->
                // Release only this exact dead attempt.  A newer worker may
                // have claimed the same Download ID while cleanup was waiting.
                if (
                    workerExecutionIds[downloadId] == executionId &&
                    (
                        downloadId in releasedIds ||
                            downloadId in recoveryEligibleIds ||
                            downloadId in recoveryPublicationFailedIds
                    )
                ) {
                    workerExecutionIds.remove(downloadId, executionId)
                    workerDownloadIds.remove(downloadId)
                    workerCleanupDownloadIds.remove(downloadId)
                    DownloadWorkerExecutionOwners.release(downloadId, executionId)
                    if (!hasNativeProcessRegistryEntry(downloadId, executionId)) {
                        DownloadWorkerProcessOwners.release(downloadId, executionId)
                    }
                    workerAuthoritativeIssues.remove(downloadId)
                }
            }
            releasedIds.forEach { downloadId ->
                val current = dao.getNullableDownloadById(downloadId)
                if (
                    current == null ||
                        current.status !in setOf(
                            DownloadRepository.Status.Active.name,
                            DownloadRepository.Status.PostProcessing.name,
                        )
                ) {
                    runningYTDLInstances.remove(downloadId)
                }
            }
        }

        // The DB row is the durable fallback when journal publication failed;
        // an unresolved native registry gets a live retry owner as well.  The
        // owner is item-local and does not hold the global claim mutex while
        // waiting for a later termination acknowledgement.
        (recoveryEligibleIds + recoveryPublicationFailedIds).forEach { downloadId ->
            DownloadExecutionRecovery.scheduleRecovery(context, downloadId)
        }

        // A History replacement is an irreversible semantic commit.  If the
        // exact-token finalization above could not delete its Download row,
        // releasing the dead owner is necessary for recovery, but it is not
        // sufficient: no unrelated queue activity should be required to make
        // the same-process finalization attempt happen.  The committed
        // History row is the durable carrier; enqueue a fresh worker that
        // will run the startup finalizer.  Do this after releasing the global
        // claim lock so WorkManager/Room work cannot participate in the
        // worker-lock ordering.
        val committedFinalizationDebt = withDownloadWorkerExecutionLock {
            snapshot.committedHistoryReplacementIds.filter { downloadId ->
                val item = dao.getNullableDownloadById(downloadId) ?: return@filter false
                val marker = HistoryRedownloadMarker.parse(item.playlistURL) ?: return@filter false
                dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId == downloadId
            }
        }
        if (committedFinalizationDebt.isNotEmpty()) {
            Log.i(
                TAG,
                "Committed History finalization debt remains recoverable ids=$committedFinalizationDebt"
            )
        }

        try {
            DownloadExecutionRecovery.reconcile(context, dbManager)
        } catch (failure: Exception) {
            firstCleanupFailure = firstCleanupFailure.addOrSuppress(failure)
            Log.e(TAG, "Download cleanup recovery pass failed", failure)
        }

        if (firstCleanupFailure == null) {
            Log.i(TAG, "Stopped worker cleanup completed for ${snapshot.activeIds.size} active download(s)")
        } else {
            Log.w(
                TAG,
                "Failed to fully clean stopped active downloads ids=${snapshot.activeIds}; " +
                    "durable recovery remains responsible for unresolved rows",
                firstCleanupFailure
            )
            if (propagateRequeueFailure) throw firstCleanupFailure
        }
    }

    private suspend fun persistDownloadMetadata(
        resultRepo: ResultRepository,
        dao: DownloadDao,
        downloadItem: DownloadItem
    ) {
        val updatedItem = try {
            resultRepo.updateDownloadItem(
                downloadItem,
                lookupOrder = ResultRepository.DownloadMetadataLookupOrder.CACHE_FIRST,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(
                TAG,
                "Download metadata enrichment failed id=${downloadItem.id} type=${error.javaClass.simpleName}"
            )
            null
        }
        updatedItem?.let { enrichedItem ->
            val current = dao.getNullableDownloadById(enrichedItem.id)
            if (
                current?.let {
                    it.status == DownloadRepository.Status.Active.name &&
                        it.executionId == downloadItem.executionId
                } == true
            ) {
                enrichedItem.executionId = current.executionId
                enrichedItem.lastIssueCode = current.lastIssueCode
                enrichedItem.lastIssueStage = current.lastIssueStage
                DBManager.getInstance(context).historyReplacementBarrierDao
                    .getByDownloadId(enrichedItem.id)
                    ?.let { barrier ->
                        enrichedItem.lastIssueCode = barrier.issueCode
                        enrichedItem.lastIssueStage = barrier.issueStage
                    }
                dao.updateIfExecutionOwned(enrichedItem, current.executionId)
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            doWorkSerialized()
        } catch (cancelled: CancellationException) {
            if (isStopped || currentCoroutineContext()[Job]?.isActive != true) {
                try {
                    cleanupStoppedWorker(includeStaleRows = false)
                } catch (cleanupFailure: CancellationException) {
                    cancelled.addSuppressed(cleanupFailure)
                } catch (cleanupFailure: Exception) {
                    cancelled.addSuppressed(cleanupFailure)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            rethrowAfterOwnedDownloadCleanup(failure) {
                cleanupStoppedWorker(
                    includeStaleRows = false,
                    propagateRequeueFailure = true,
                )
            }
        } finally {
            if (isStopped) {
                cleanupStoppedWorker()
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("RestrictedApi", "SuspiciousIndentation")
    private suspend fun doWorkSerialized(): Result {
        if (isStopped) return Result.Failure()

        // Configure the durable descendant barrier before any claim, retry,
        // or cleanup path can address an exact yt-dlp process. WorkManager
        // may start this worker before the application startup pass runs.
        YtdlpNativeProcessBarrier.configure(context)

        if (!setForegroundSafely()) return Result.retry()

        val notificationUtil = NotificationUtil(App.instance)
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val historyDao = dbManager.historyDao
        val observeSourcesDao = dbManager.observeSourcesDao
        val commandTemplateDao = dbManager.commandTemplateDao
        val historyKeywordAssignments = HistoryKeywordAssignmentRepository(dbManager)
        val logRepo = LogRepository(dbManager.logDao)
        val resultRepo = ResultRepository(dbManager.resultDao, commandTemplateDao, context)
        val ytdlpUtil = YTDLPUtil(context, commandTemplateDao)
        val handler = Handler(Looper.getMainLooper())
        val alarmScheduler = AlarmScheduler(context)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val time = System.currentTimeMillis() + 6000
        val requestedPriorityItemIDs =
            (inputData.getLongArray("priority_item_ids") ?: longArrayOf()).toList()
        var priorityItemIDs = requestedPriorityItemIDs
        val continueAfterPriorityIds = inputData.getBoolean("continue_after_priority_ids", true)
        val queueAdmission = observeQueuedDownloadsAfterRecovery(
            context = context,
            dbManager = dbManager,
            priorityItemIds = requestedPriorityItemIDs,
            currentTimeMillis = time,
        )
        val queuedItems = queueAdmission.queuedItems

        // this is needed for observe sources call, so it wont create result items
        // [removed]
        //val createResultItem = inputData.getBoolean("createResultItem", true)

        val confTmp = Configuration(context.resources.configuration)
        val locale = if (Build.VERSION.SDK_INT < 33) {
            sharedPreferences.getString("app_language", "")!!.ifEmpty { Locale.getDefault().language }
        }else{
            Locale.getDefault().language
        }.run {
            split("-")
        }.run {
            if (this.size == 1) Locale(this[0]) else Locale(this[0], this[1])
        }
        confTmp.setLocale(locale)
        val metrics = DisplayMetrics()
        val resources = Resources(context.assets, metrics, confTmp)

        val openQueueIntent = Intent(context, MainActivity::class.java)
        openQueueIntent.setAction(Intent.ACTION_VIEW)
        openQueueIntent.putExtra("destination", "Queue")
        val openDownloadQueue = PendingIntent.getActivity(
            context,
            1000000000,
            openQueueIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        resetHardSubProgress()

        queuedItems.collect { items ->
            if (this@DownloadWorker.isStopped) return@collect

            // Recovery responsibility is a liveness carrier, not a scheduler
            // capacity owner.  Recompute it for this observation; a Download
            // that later requeues and is claimed as E2 must not remain
            // excluded merely because it was deferred during startup.
            val recoveryResponsibility = DownloadExecutionRecovery
                .hasRecoveryResponsibility(context, dbManager)
            val admission = admitQueuedDownloadsThroughProductionPath(
                dbManager = dbManager,
                items = items,
                priorityItemIds = priorityItemIDs,
                currentTimeMillis = time,
                concurrentDownloadLimit = sharedPreferences
                    .getInt("concurrent_downloads", 1)
                    .coerceAtLeast(1),
                continueAfterPriorityItems = continueAfterPriorityIds,
                claim = { candidate ->
                    claimDownloadThroughProductionAdmission(
                        context = context,
                        dbManager = dbManager,
                        candidate = candidate,
                        concurrentDownloadLimit = sharedPreferences
                            .getInt("concurrent_downloads", 1)
                            .coerceAtLeast(1),
                    ) { claimedItem ->
                        workerExecutionIds[claimedItem.id] = claimedItem.executionId
                        workerDownloadIds.add(claimedItem.id)
                        workerCleanupDownloadIds.add(claimedItem.id)
                    }
                },
            )
            priorityItemIDs = admission.prioritySnapshot.outstandingIds
            withDownloadWorkerExecutionLock {
                runningYTDLInstances.clear()
                runningYTDLInstances.addAll(admission.ownership.liveCapacityIds)
            }
            val hasOutstandingWorkerExecution =
                workerDownloadIds.isNotEmpty() ||
                    workerCleanupDownloadIds.isNotEmpty() ||
                    admission.ownership.liveExecutionIds.isNotEmpty()
            val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
            if (
                items.isEmpty() &&
                admission.ownership.liveCapacityIds.isEmpty() &&
                admission.prioritySnapshot.outstandingIds.isEmpty() &&
                !hasOutstandingWorkerExecution &&
                !recoveryResponsibility
            ) {
                WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collect
            }

            if (useScheduler){
                if (
                    items.none { it.downloadStartTime > 0L } &&
                    admission.ownership.liveCapacityIds.isEmpty() &&
                    admission.prioritySnapshot.outstandingIds.isEmpty() &&
                    !hasOutstandingWorkerExecution &&
                    !recoveryResponsibility &&
                    !alarmScheduler.isDuringTheScheduledTime()
                ) {
                    WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                    return@collect
                }
            }

            if (admission.stoppedAfterPriorities && !recoveryResponsibility) {
                WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collect
            }

            val eligibleDownloads = admission.claimedItems

            // Claims publish the execution token while the exact per-Download
            // side-effect lease is held.  Cleanup/pause/cancel therefore cannot
            // pass its ownership check and then address the resource before a
            // newly claimed execution is visible in the process-local owner
            // registry.

            runDownloadItemsIndependently(
                items = eligibleDownloads,
                isItemLocalCancellation = { item ->
                    !this@DownloadWorker.isStopped &&
                        (
                            DownloadCancellationRegistry.belongsTo(item.id, item.executionId) ||
                                runCatching {
                                    dbManager.lowQualityRedownloadDao
                                        .hasCancellationRequestedByDownload(item.id)
                                }.getOrDefault(false)
                        )
                },
            ) launch@{ downloadItem ->
                    workerDownloadIds.add(downloadItem.id)
                    val rawTempFileDir = File(FileUtil.getCachePath(context), downloadItem.id.toString())
                    var ytdlpExecutionState: YtdlpExecutionState? = null
                    var hardSubPostProcessLockHeld = false
                    var currentIssueStage = DownloadIssueStage.PREFLIGHT
                    var createdOutputPaths: List<String> = emptyList()
                    var preserveQueueRecord = false
                    val durableReplacementBarrier = withContext(Dispatchers.IO + NonCancellable) {
                        dbManager.historyReplacementBarrierDao.getByDownloadId(downloadItem.id)
                    }
                    val persistedHistoryRefusal = durableReplacementBarrier
                        ?.let { HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(it.issueCode) }
                        ?: HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(downloadItem.lastIssueCode)
                    var historyReplacementFailureIssue: DownloadIssue? =
                        persistedHistoryRefusal?.takeUnless {
                            it.code == DownloadIssueCode.HISTORY_TARGET_DELETED
                        }
                    var historyReplacementAuthoritativeIssue: DownloadIssue? = persistedHistoryRefusal
                    historyReplacementFailureIssue?.let { issue ->
                        workerAuthoritativeIssues[downloadItem.id] = issue
                    }
                    persistedHistoryRefusal
                        ?.takeIf { it.code == DownloadIssueCode.HISTORY_TARGET_DELETED }
                        ?.let { issue ->
                            workerAuthoritativeIssues[downloadItem.id] = issue
                        }
                    var historyReplacementTerminalAction: HistoryReplacementTerminalAction? = when {
                        persistedHistoryRefusal?.code == DownloadIssueCode.HISTORY_TARGET_DELETED ->
                            HistoryReplacementTerminalAction.TARGET_DELETED
                        historyReplacementFailureIssue != null ->
                            HistoryReplacementTerminalAction.PRESERVE_FAILED
                        else -> null
                    }
                    fun establishHistoryReplacementFailure(issue: DownloadIssue) {
                        historyReplacementFailureIssue = issue
                        historyReplacementAuthoritativeIssue = issue
                        workerAuthoritativeIssues[downloadItem.id] = issue
                    }
                    fun establishHistoryTargetDeleted() {
                        if (historyReplacementFailureIssue == null) {
                            val issue = historyReplacementRefusalIssue(
                                HistoryReplacementAuthorization.TargetMissing
                            )
                            historyReplacementAuthoritativeIssue = issue
                            workerAuthoritativeIssues[downloadItem.id] = issue
                        }
                        historyReplacementTerminalAction =
                            HistoryReplacementOutcomePolicy.mergeTerminalAction(
                                historyReplacementTerminalAction,
                                historyReplacementRefusalTerminalAction(
                                    HistoryReplacementAuthorization.TargetMissing
                                )
                            )
                    }
                    fun adoptHistoryReplacementAuthorization(
                        authorization: HistoryReplacementAuthorization,
                    ) {
                        when (authorization) {
                            is HistoryReplacementAuthorization.Authorized -> Unit
                            HistoryReplacementAuthorization.TargetMissing ->
                                establishHistoryTargetDeleted()
                            HistoryReplacementAuthorization.SourceMismatch ->
                                establishHistoryReplacementFailure(
                                    historyReplacementRefusalIssue(authorization)
                                )
                            HistoryReplacementAuthorization.TypeMismatch ->
                                establishHistoryReplacementFailure(
                                    historyReplacementRefusalIssue(authorization)
                                )
                        }
                    }
                    fun establishQualityAuthorityLoss(): DownloadIssue {
                        val issue = HistoryReplacementDiagnostic.qualityAuthorityLostIssue()
                        historyReplacementTerminalAction =
                            HistoryReplacementOutcomePolicy.mergeTerminalAction(
                                historyReplacementTerminalAction,
                                HistoryReplacementTerminalAction.PRESERVE_FAILED,
                            )
                        establishHistoryReplacementFailure(issue)
                        return issue
                    }
                    suspend fun refreshDurableHistoryReplacementBarrier() {
                        val barrier = withContext(Dispatchers.IO + NonCancellable) {
                            dbManager.historyReplacementBarrierDao.getByDownloadId(downloadItem.id)
                        }
                        barrier?.let {
                            HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(it.issueCode)
                                ?.let { issue ->
                                    if (issue.code == DownloadIssueCode.HISTORY_TARGET_DELETED) {
                                        establishHistoryTargetDeleted()
                                    } else {
                                        establishHistoryReplacementFailure(issue)
                                    }
                                }
                        }
                    }
                    var downloadOutcome: DownloadOutcome? = null
                    fun recordCreatedOutputs(paths: List<String>) {
                        createdOutputPaths = (createdOutputPaths + paths)
                            .distinct()
                            .filter { path ->
                                val file = File(path)
                                file.exists() && file.isFile
                            }
                    }
                    fun shouldStopForUserRequest(): Boolean {
                        val latest = runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()
                        val lostExecutionOwnership = downloadItem.executionId.isNotBlank() &&
                            (latest == null || latest.executionId != downloadItem.executionId)
                        val lowQualityCancellationRequested = runCatching {
                            dbManager.lowQualityRedownloadDao
                                .hasCancellationRequestedByDownload(downloadItem.id)
                        }.getOrDefault(false)
                        return this@DownloadWorker.isStopped ||
                            lostExecutionOwnership ||
                            lowQualityCancellationRequested ||
                            latest?.status in setOf(
                                DownloadRepository.Status.Paused.name,
                                DownloadRepository.Status.Cancelled.name,
                            )
                    }
                    val notificationTitle = SensitiveTextRedactor.redactOutput(
                        downloadItem.title.ifEmpty { downloadItem.url }
                    )
                    var historyReplacementCommitted = withContext(Dispatchers.IO + NonCancellable) {
                        isDurablyCommittedHistoryReplacement(dbManager, downloadItem)
                    }
                    try {
                    if (historyReplacementFailureIssue != null) {
                        preserveQueueRecord = true
                        throw IllegalStateException(
                            "History replacement mismatch cannot be retried"
                        )
                    }
                    if (historyReplacementCommitted) {
                        val affectedOperations = DownloadRepository(dbManager)
                            .completeAndDelete(
                                id = downloadItem.id,
                                expectedExecutionId = downloadItem.executionId,
                            )
                        runCatching {
                            LowQualityRedownloadLedger.refresh(context, affectedOperations)
                        }
                        runCatching {
                            withContext(Dispatchers.Main) {
                                notificationUtil.cancelRunningDownloadNotification(downloadItem.id.toInt())
                                notificationUtil.createDownloadFinished(
                                    downloadItem.id,
                                    notificationTitle,
                                    downloadItem.type,
                                    null,
                                    resources,
                                )
                            }
                        }.onFailure { notificationError ->
                            Log.w(
                                TAG,
                                "Committed History replacement notification failed id=${downloadItem.id}",
                                notificationError,
                            )
                        }
                        downloadOutcome = DownloadOutcome.completed(createdFileCount = 0)
                        return@launch
                    }
                    if (isHardSubRedownload(downloadItem)) {
                        registerHardSubTarget(downloadItem.id)
                        updateHardSubWorkerNotificationSafely(notificationUtil)
                    }
                    withOwnedExecutionSideEffect(downloadItem) {
                        val notification = notificationUtil.createDownloadServiceNotification(
                            openDownloadQueue,
                            notificationTitle,
                        )
                        notificationUtil.notify(
                            NotificationUtil.downloadRunningNotificationId(downloadItem.id.toInt()),
                            notification,
                        )
                    }

                    val writtenPath = downloadItem.format.format_note.contains("-P ")
                    var shouldBurnHardSub = downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs
                    val qualityReplacement = HistoryRedownloadMarker.parse(downloadItem.playlistURL)
                        ?.isQualityReplacement == true
                    val requiresVerifiedQualityStaging = VideoQualityPolicy.requiresVerifiedStaging(
                        isVideo = downloadItem.type == DownloadType.video,
                        format = downloadItem.format,
                        sourceFormats = downloadItem.allFormats,
                        isQualityReplacement = qualityReplacement
                    ) || (
                        downloadItem.type == DownloadType.video &&
                            downloadItem.url.isYoutubeURL() &&
                            YoutubeMediaAccessPolicy.containsRawFormatOverride(downloadItem.extraCommands)
                        )
                    val noCache = writtenPath || (
                        !requiresVerifiedQualityStaging &&
                            !sharedPreferences.getBoolean("cache_downloads", true) &&
                            FileUtil.canWriteToDestination(downloadItem.downloadPath, context)
                        )

                    val downloadLocation = downloadItem.downloadPath
                    val keepCache = sharedPreferences.getBoolean("keep_cache", false)
                    val noKeepSubs = sharedPreferences.getBoolean("no_keep_subs", false)
                    val ytdlpInput = YtdlpPhaseInput(
                        downloadItem = downloadItem,
                        rawTempDirectory = rawTempFileDir,
                        notificationTitle = notificationTitle,
                        loggingEnabled = sharedPreferences.getBoolean("log_downloads", false) &&
                            !downloadItem.incognito,
                    )
                    val ytdlpServices = YtdlpPhaseServices(
                        ytdlpUtil = ytdlpUtil,
                        notificationUtil = notificationUtil,
                        logRepository = logRepo,
                        downloadDao = dao,
                        resources = resources,
                    )
                    val ytdlpPreparation = prepareYtdlpPhase(ytdlpInput, ytdlpServices)
                    val eventBus = EventBus.getDefault()
                    val downloadStartedAt = System.currentTimeMillis()
                    try {
                    val ytdlpOutcome = executeYtdlpPhase(
                        input = ytdlpInput,
                        services = ytdlpServices,
                        eventBus = eventBus,
                        preparation = ytdlpPreparation,
                        startedAt = downloadStartedAt,
                    )
                    ytdlpExecutionState = ytdlpOutcome.state
                    currentIssueStage = ytdlpOutcome.state.issueStage
                    val ytdlpPhase = when (ytdlpOutcome) {
                        is YtdlpPhaseOutcome.Completed -> ytdlpOutcome
                        is YtdlpPhaseOutcome.Failed -> throw ytdlpOutcome.error
                        is YtdlpPhaseOutcome.Cancelled -> throw ytdlpOutcome.error
                    }
                    val tempFileDir = ytdlpPhase.state.validatedTempDirectory ?: rawTempFileDir

                    persistDownloadMetadata(resultRepo, dao, downloadItem)
                    //val wasQuickDownloaded = resultDao.getCountInt() == 0
                    var finalPaths = mutableListOf<String>()
                    var hardSubBurned = false

                        val hardSubSkipReason = if (shouldBurnHardSub) {
                            resolveHardSubSkipReason(ytdlpPhase.result.response.out)
                        } else {
                            null
                        }
                        if (hardSubSkipReason != null) {
                            shouldBurnHardSub = false
                            Log.w(TAG, "HardSub skipped id=${downloadItem.id} reason=$hardSubSkipReason")
                            eventBus.post(WorkerProgress(100, hardSubSkipReason, downloadItem.id, downloadItem.logID))
                        }
                        if (shouldBurnHardSub && sharedPreferences.getBoolean("parallel_hardsub_postprocessing", false)) {
                            downloadItem.status = DownloadRepository.Status.PostProcessing.toString()
                            check(
                                dao.updateIfExecutionOwnedAndRunning(
                                    downloadItem,
                                    downloadItem.executionId,
                                )
                            ) {
                                "Post-processing ownership lost for download ${downloadItem.id}"
                            }
                            if (DownloadWorkerExecutionOwners.isOwnedBy(downloadItem.id, downloadItem.executionId)) {
                                runningYTDLInstances.remove(downloadItem.id)
                            }
                            val postProcessingMessage =
                                resources.getString(R.string.post_processing_hard_subtitles)
                            withOwnedExecutionSideEffect(downloadItem) {
                                eventBus.post(
                                    WorkerProgress(
                                        100,
                                        postProcessingMessage,
                                        downloadItem.id,
                                        downloadItem.logID,
                                    )
                                )
                                notificationUtil.updateDownloadNotification(
                                    downloadItem.id.toInt(),
                                    postProcessingMessage,
                                    100,
                                    0,
                                    notificationTitle,
                                    NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
                                    getHardSubStatusText(resources),
                                    downloadItem.executionId,
                                )
                            }
                            Log.i(TAG, "HardSub post-processing slot released id=${downloadItem.id}")
                            DownloadRepository(dbManager).startDownloadWorker(emptyList(), context)
                            hardSubPostProcessMutex.lock()
                            hardSubPostProcessLockHeld = true
                        }
                        if (shouldStopForUserRequest()) return@launch

                        var deferBurnUntilPostMove = false
                        val forceDeferBurn = shouldBurnHardSub && shouldForceHardSubFailpoint("force_hardsub_defer")
                        val forceMoveUnresolved = shouldBurnHardSub && shouldForceHardSubFailpoint("force_hardsub_move_unresolved")
                        if (shouldBurnHardSub) {
                            Log.i(
                                TAG,
                                "HardSub session id=${downloadItem.id} noCache=$noCache keepCache=$keepCache downloadLocation=${FileUtil.formatPath(downloadLocation)} tempDir=${tempFileDir.absolutePath} forceDeferBurn=$forceDeferBurn forceMoveUnresolved=$forceMoveUnresolved"
                            )
                        }
                        if (!noCache && shouldBurnHardSub) {
                            var preMoveBurnPaths = extractPathsFromYtdlpOutput(ytdlpPhase.result.response.out).toMutableList()
                            logPathCandidates("HardSub pre-move parsed", downloadItem.id, preMoveBurnPaths)
                            if (preMoveBurnPaths.isEmpty()) {
                                preMoveBurnPaths = recoverPathsFromDirectory(
                                    tempFileDir.absolutePath,
                                    ytdlpPhase.state.startedAt
                                ).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub pre-move temp fallback used id=${downloadItem.id} recovered=${preMoveBurnPaths.size} dir=${tempFileDir.absolutePath}"
                                )
                            }
                            val preMoveHasMedia = preMoveBurnPaths.any { path ->
                                val ext = File(path).extension.lowercase(Locale.US)
                                ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                            }
                            if (!preMoveHasMedia) {
                                val recoveredMedia = resolvePreviousHistoryMediaPaths(
                                    downloadItem = downloadItem,
                                    historyKeywordAssignments = historyKeywordAssignments
                                )
                                if (recoveredMedia.isNotEmpty()) {
                                    preMoveBurnPaths.addAll(recoveredMedia)
                                    Log.w(
                                        TAG,
                                        "HardSub pre-move history-media fallback used id=${downloadItem.id} recovered=${recoveredMedia.size}"
                                    )
                                }
                            }
                            Log.i(TAG, "HardSub pre-move remap input=${preMoveBurnPaths.size}")
                            preMoveBurnPaths = remapPathsForBurnIn(
                                preMoveBurnPaths,
                                tempFileDir.absolutePath,
                                tempFileDir.absolutePath
                            ).toMutableList()
                            Log.i(TAG, "HardSub pre-move remap output=${preMoveBurnPaths.size}")
                            val beforeTempOnlyFilter = preMoveBurnPaths.size
                            preMoveBurnPaths = preMoveBurnPaths
                                .filter { candidate ->
                                    isPathInsideDirectory(candidate, tempFileDir)
                                }
                                .toMutableList()
                            if (beforeTempOnlyFilter != preMoveBurnPaths.size) {
                                Log.w(
                                    TAG,
                                    "HardSub pre-move filtered non-temp paths removed=${beforeTempOnlyFilter - preMoveBurnPaths.size}"
                                )
                            }
                            if (preMoveBurnPaths.isEmpty()) {
                                throw IOException("HardSub aborted: no temporary media files found for burn-in")
                            }
                            var hasMediaForPreMove = preMoveBurnPaths.any { path ->
                                val ext = File(path).extension.lowercase(Locale.US)
                                ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                            }
                            if (!hasMediaForPreMove) {
                                val recoveredFromTemp = recoverPathsFromDirectory(
                                    tempFileDir.absolutePath,
                                    ytdlpPhase.state.startedAt
                                )
                                if (recoveredFromTemp.isNotEmpty()) {
                                    preMoveBurnPaths.addAll(recoveredFromTemp)
                                    preMoveBurnPaths = remapPathsForBurnIn(
                                        preMoveBurnPaths,
                                        tempFileDir.absolutePath,
                                        tempFileDir.absolutePath
                                    ).filter { candidate ->
                                        isPathInsideDirectory(candidate, tempFileDir)
                                    }.distinct().toMutableList()
                                    hasMediaForPreMove = preMoveBurnPaths.any { path ->
                                        val ext = File(path).extension.lowercase(Locale.US)
                                        ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                                    }
                                    Log.w(
                                        TAG,
                                        "HardSub pre-move temp rescan used id=${downloadItem.id} recovered=${recoveredFromTemp.size} mediaPresent=$hasMediaForPreMove"
                                    )
                                }
                                if (!hasMediaForPreMove) {
                                    val recoveredAllFromTemp = recoverAllPathsFromDirectory(tempFileDir.absolutePath)
                                    if (recoveredAllFromTemp.isNotEmpty()) {
                                        preMoveBurnPaths.addAll(recoveredAllFromTemp)
                                        preMoveBurnPaths = remapPathsForBurnIn(
                                            preMoveBurnPaths,
                                            tempFileDir.absolutePath,
                                            tempFileDir.absolutePath
                                        ).filter { candidate ->
                                            isPathInsideDirectory(candidate, tempFileDir)
                                        }.distinct().toMutableList()
                                        hasMediaForPreMove = preMoveBurnPaths.any { path ->
                                            val ext = File(path).extension.lowercase(Locale.US)
                                            ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                                        }
                                        Log.w(
                                            TAG,
                                            "HardSub pre-move temp fullscan used id=${downloadItem.id} recovered=${recoveredAllFromTemp.size} mediaPresent=$hasMediaForPreMove"
                                        )
                                    }
                                }
                            }
                            if (hasMediaForPreMove) {
                                if (forceDeferBurn) {
                                    deferBurnUntilPostMove = true
                                    Log.w(TAG, "HardSub pre-move forced defer id=${downloadItem.id} marker=force_hardsub_defer")
                                } else {
                                    if (shouldStopForUserRequest()) return@launch
                                    Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${preMoveBurnPaths.size} mode=pre-move")
                                    eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                    currentIssueStage = DownloadIssueStage.HARD_SUB
                                    val burned = withOwnedExecutionLease(downloadItem) {
                                        burnSubtitlesInPlace(
                                            preMoveBurnPaths,
                                            noKeepSubs,
                                            downloadItem.id,
                                            downloadItem.executionId,
                                            downloadItem.logID,
                                            downloadItem.videoPreferences.subsLanguages,
                                        )
                                    }
                                    hardSubBurned = hardSubBurned || burned
                                    if (burned) {
                                        eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                        Log.i(TAG, "HardSub completed id=${downloadItem.id} mode=pre-move")
                                    } else {
                                        Log.w(TAG, "HardSub pre-move produced no burned media id=${downloadItem.id}")
                                    }
                                }
                            } else {
                                deferBurnUntilPostMove = true
                                Log.w(
                                    TAG,
                                    "HardSub pre-move deferred id=${downloadItem.id} reason=no-media-in-temp tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                                )
                            }
                        }

                        if (shouldBurnHardSub && !noCache && deferBurnUntilPostMove) {
                            var latePreMoveBurnPaths = recoverAllPathsFromDirectory(tempFileDir.absolutePath).toMutableList()
                            if (latePreMoveBurnPaths.isNotEmpty()) {
                                latePreMoveBurnPaths = remapPathsForBurnIn(
                                    latePreMoveBurnPaths,
                                    tempFileDir.absolutePath,
                                    tempFileDir.absolutePath
                                ).filter { candidate ->
                                    isPathInsideDirectory(candidate, tempFileDir)
                                }.distinct().toMutableList()
                            }
                            val lateHasMedia = latePreMoveBurnPaths.any { path ->
                                val ext = File(path).extension.lowercase(Locale.US)
                                ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                            }
                            if (lateHasMedia) {
                                if (shouldStopForUserRequest()) return@launch
                                Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${latePreMoveBurnPaths.size} mode=pre-move-late")
                                eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                currentIssueStage = DownloadIssueStage.HARD_SUB
                                val burned = withOwnedExecutionLease(downloadItem) {
                                    burnSubtitlesInPlace(
                                        latePreMoveBurnPaths,
                                        noKeepSubs,
                                        downloadItem.id,
                                        downloadItem.executionId,
                                        downloadItem.logID,
                                        downloadItem.videoPreferences.subsLanguages,
                                    )
                                }
                                hardSubBurned = hardSubBurned || burned
                                if (burned) {
                                    deferBurnUntilPostMove = false
                                    eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                    Log.i(TAG, "HardSub completed id=${downloadItem.id} mode=pre-move-late")
                                } else {
                                    Log.w(TAG, "HardSub pre-move-late produced no burned media id=${downloadItem.id}")
                                }
                            } else {
                                Log.w(TAG, "HardSub pre-move-late skipped id=${downloadItem.id} reason=no-media-in-temp")
                            }
                        }

                            if (noCache){
                            eventBus.post(WorkerProgress(100, "Scanning Files", downloadItem.id, downloadItem.logID))
                            finalPaths = extractPathsFromYtdlpOutput(ytdlpPhase.result.response.out).toMutableList()
                            logPathCandidates("HardSub no-cache parsed", downloadItem.id, finalPaths)

                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(
                                    downloadLocation,
                                    ytdlpPhase.state.startedAt
                                ).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub path recovery used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                )
                            }

                            finalPaths.sortBy { File(it).lastModified() }
                            finalPaths = finalPaths.distinct().toMutableList()
                            Log.i(
                                TAG,
                                "HardSub no-cache output paths id=${downloadItem.id} count=${finalPaths.size} sample=${
                                    finalPaths.joinToString(limit = 3)
                                }"
                            )
                            FileUtil.scanMedia(finalPaths, context)
                        }else{
                            //move file from internal to set download directory
                            currentIssueStage = DownloadIssueStage.MOVE
                            eventBus.post(WorkerProgress(100, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                            Log.i(
                                TAG,
                                "HardSub move start id=${downloadItem.id} from=${tempFileDir.absolutePath} to=${FileUtil.formatPath(downloadLocation)} tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                            )
                            val expectedMovedNames = runCatching {
                                tempFileDir.walkTopDown()
                                    .filter { it.isFile && it.length() > 0L }
                                    .map { it.name }
                                    .toSet()
                            }.getOrDefault(emptySet())
                            if (expectedMovedNames.isNotEmpty()) {
                                Log.i(
                                    TAG,
                                    "HardSub move expected names id=${downloadItem.id} count=${expectedMovedNames.size} sample=${expectedMovedNames.joinToString(limit = 5)}"
                                )
                            }
                            try {
                                finalPaths = withOwnedExecutionLease(downloadItem) {
                                    withContext(Dispatchers.IO){
                                        FileUtil.moveFile(tempFileDir.absoluteFile,context, downloadLocation, keepCache){ p ->
                                            eventBus.post(WorkerProgress(p, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                        }
                                    }
                                }.filter { !it.matches("\\.(description)|(txt)\$".toRegex()) }.toMutableList()
                                if (forceMoveUnresolved) {
                                    Log.w(TAG, "HardSub move forcing unresolved outputs id=${downloadItem.id} marker=force_hardsub_move_unresolved")
                                    finalPaths.clear()
                                }
                                logPathCandidates("HardSub move returned", downloadItem.id, finalPaths)

                                if (finalPaths.isNotEmpty()){
                                    eventBus.post(WorkerProgress(100, "Moved file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                    Log.i(
                                        TAG,
                                        "HardSub move done id=${downloadItem.id} count=${finalPaths.size} sample=${
                                            finalPaths.joinToString(limit = 3)
                                        } destSnapshot=${describeDirectorySnapshot(File(downloadLocation))}"
                                    )
                                } else {
                                    if (expectedMovedNames.isNotEmpty()) {
                                        val recoveredByName = recoverPathsByFileNames(downloadLocation, expectedMovedNames.toList())
                                            .filter { File(it).exists() && File(it).isFile }
                                            .toMutableList()
                                        if (recoveredByName.isNotEmpty()) {
                                            finalPaths = recoveredByName
                                            Log.w(
                                                TAG,
                                                "HardSub move name-hint fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                            )
                                        }
                                    }
                                    val recoveredAfterMove = recoverPathsFromDirectory(
                                        downloadLocation,
                                        ytdlpPhase.state.startedAt
                                    )
                                        .filter { File(it).exists() && File(it).isFile }
                                        .toMutableList()
                                    if (finalPaths.isEmpty() && recoveredAfterMove.isNotEmpty()) {
                                        finalPaths = recoveredAfterMove
                                        Log.w(
                                            TAG,
                                            "HardSub move output fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                        )
                                    }
                                    Log.w(
                                        TAG,
                                        "HardSub move done id=${downloadItem.id} but no output paths detected destSnapshot=${describeDirectorySnapshot(File(downloadLocation))} tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                                    )
                                    if (shouldBurnHardSub && !noCache && finalPaths.isEmpty()) {
                                        throw IOException("HardSub move completed but output paths are unresolved after burn-in")
                                    }
                                }
                            }catch (e: Exception){
                                if (e is CancellationException) throw e
                                e.printStackTrace()
                                Log.e(TAG, "HardSub move failed id=${downloadItem.id}", e)
                                val recoveredAfterFailure = buildList {
                                    if (expectedMovedNames.isNotEmpty()) {
                                        addAll(recoverPathsByFileNames(downloadLocation, expectedMovedNames.toList()))
                                    }
                                    addAll(recoverPathsFromDirectory(downloadLocation, ytdlpPhase.state.startedAt))
                                }
                                    .distinct()
                                    .filter { File(it).exists() && File(it).isFile }
                                    .toMutableList()

                                if (recoveredAfterFailure.isNotEmpty()) {
                                    finalPaths = recoveredAfterFailure
                                    Log.w(
                                        TAG,
                                        "HardSub move failure recovery used id=${downloadItem.id} recovered=${finalPaths.size}"
                                    )
                                } else {
                                    val recoveredTempPaths = recoverPathsFromDirectory(
                                        tempFileDir.absolutePath,
                                        ytdlpPhase.state.startedAt
                                    )
                                        .filter { File(it).exists() && File(it).isFile }
                                    if (recoveredTempPaths.isNotEmpty()) {
                                        Log.w(
                                            TAG,
                                            "HardSub move failure left temp outputs id=${downloadItem.id} recovered=${recoveredTempPaths.size}; retrying move"
                                        )
                                        finalPaths = retryMoveFromTempDirectory(
                                            tempFileDir = tempFileDir,
                                            downloadLocation = downloadLocation,
                                            keepCache = keepCache,
                                            downloadItem = downloadItem,
                                            downloadLogId = downloadItem.logID,
                                            eventBus = eventBus
                                        ).toMutableList()
                                    }
                                }

                                if (shouldBurnHardSub && finalPaths.isEmpty()) {
                                    throw IOException(
                                        "HardSub move failed: output files are missing after burn-in",
                                        e
                                    )
                                }
                                Log.w(
                                    TAG,
                                    "HardSub move failure state id=${downloadItem.id} destSnapshot=${describeDirectorySnapshot(File(downloadLocation))} tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                                )
                                if (e.message?.isNotBlank() == true) {
                                    handler.postDelayed({
                                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                    }, 1000)
                                }

                            }
                        }

                        validateMovedQualityReplacement(
                            downloadItem = downloadItem,
                            finalPaths = finalPaths,
                            historyDao = historyDao,
                            historyKeywordAssignments = historyKeywordAssignments
                        )
                        recordCreatedOutputs(finalPaths)

                        if (shouldBurnHardSub && !noCache && deferBurnUntilPostMove) {
                            var postMoveBurnPaths = finalPaths.toMutableList()
                            if (postMoveBurnPaths.isEmpty()) {
                                val fromOutput = extractPathsFromYtdlpOutput(ytdlpPhase.result.response.out)
                                if (fromOutput.isNotEmpty()) {
                                    postMoveBurnPaths = remapPathsForBurnIn(
                                        fromOutput,
                                        downloadLocation,
                                        tempFileDir.absolutePath
                                    ).toMutableList()
                                    if (postMoveBurnPaths.isNotEmpty()) {
                                        Log.w(
                                            TAG,
                                            "HardSub post-move output-remap used id=${downloadItem.id} recovered=${postMoveBurnPaths.size}"
                                        )
                                    }
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                postMoveBurnPaths = recoverPathsFromDirectory(
                                    downloadLocation,
                                    ytdlpPhase.state.startedAt
                                ).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub post-move fallback used id=${downloadItem.id} recovered=${postMoveBurnPaths.size} dir=$downloadLocation"
                                )
                            }
                            postMoveBurnPaths = remapPathsForBurnIn(
                                postMoveBurnPaths,
                                downloadLocation,
                                tempFileDir.absolutePath
                            ).toMutableList()
                            if (postMoveBurnPaths.isEmpty()) {
                                val directRecovered = recoverPathsFromDirectory(
                                    downloadLocation,
                                    ytdlpPhase.state.startedAt
                                )
                                    .filter { File(it).exists() && File(it).isFile }
                                    .toMutableList()
                                if (directRecovered.isNotEmpty()) {
                                    postMoveBurnPaths = directRecovered
                                    Log.w(
                                        TAG,
                                        "HardSub post-move direct recovery used id=${downloadItem.id} recovered=${postMoveBurnPaths.size} dir=$downloadLocation"
                                    )
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                val recoveredHistoryMedia = resolvePreviousHistoryMediaPaths(
                                    downloadItem = downloadItem,
                                    historyKeywordAssignments = historyKeywordAssignments
                                )
                                    .filter { path ->
                                        val file = File(path)
                                        file.exists() && file.isFile
                                    }
                                if (recoveredHistoryMedia.isNotEmpty()) {
                                    postMoveBurnPaths = recoveredHistoryMedia.toMutableList()
                                    Log.w(
                                        TAG,
                                        "HardSub post-move history-media fallback used id=${downloadItem.id} recovered=${postMoveBurnPaths.size}"
                                    )
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                val nameHints = extractPathsFromYtdlpOutput(ytdlpPhase.result.response.out)
                                    .map { File(it).name }
                                    .filter { it.isNotBlank() }
                                if (nameHints.isNotEmpty()) {
                                    val nameRecovered = recoverPathsByFileNames(downloadLocation, nameHints)
                                    if (nameRecovered.isNotEmpty()) {
                                        postMoveBurnPaths = nameRecovered.toMutableList()
                                        Log.w(
                                            TAG,
                                            "HardSub post-move name-hint recovery used id=${downloadItem.id} recovered=${postMoveBurnPaths.size}"
                                        )
                                    }
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                if (isHardSubRedownload(downloadItem)) {
                                    throw IOException("HardSub aborted: no files found after move for deferred burn-in")
                                }
                                Log.w(
                                    TAG,
                                    "HardSub post-move skipped id=${downloadItem.id} reason=no-files-found-after-move destSnapshot=${describeDirectorySnapshot(File(downloadLocation))}"
                                )
                            } else {
                                if (shouldStopForUserRequest()) return@launch
                                Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${postMoveBurnPaths.size} mode=post-move")
                                eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                currentIssueStage = DownloadIssueStage.HARD_SUB
                                val burned = withOwnedExecutionLease(downloadItem) {
                                    burnSubtitlesInPlace(
                                        postMoveBurnPaths,
                                        noKeepSubs,
                                        downloadItem.id,
                                        downloadItem.executionId,
                                        downloadItem.logID,
                                        downloadItem.videoPreferences.subsLanguages,
                                    )
                                }
                                hardSubBurned = hardSubBurned || burned
                                if (!burned && isHardSubRedownload(downloadItem)) {
                                    throw IOException("HardSub aborted: no media was burned in post-move stage")
                                }
                                if (burned) {
                                    eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                    Log.i(TAG, "HardSub completed id=${downloadItem.id} mode=post-move")
                                } else {
                                    Log.w(TAG, "HardSub post-move produced no burned media id=${downloadItem.id}")
                                }
                            }
                        }


                        if (shouldBurnHardSub && noCache) {
                            if (finalPaths.isEmpty()) {
                                finalPaths = extractPathsFromYtdlpOutput(ytdlpPhase.result.response.out).toMutableList()
                                if (finalPaths.isNotEmpty()) {
                                    Log.w(
                                        TAG,
                                        "HardSub pre-burn output-parse fallback used id=${downloadItem.id} recovered=${finalPaths.size}"
                                    )
                                }
                            }
                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(
                                    downloadLocation,
                                    ytdlpPhase.state.startedAt
                                ).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub pre-burn fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                )
                            }
                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(
                                    tempFileDir.absolutePath,
                                    ytdlpPhase.state.startedAt
                                ).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub pre-burn temp fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=${tempFileDir.absolutePath}"
                                )
                            }
                            if (finalPaths.isEmpty()) {
                                throw IOException("HardSub aborted: no output files detected for burn-in")
                            }
                            Log.i(TAG, "HardSub pre-remap paths=${finalPaths.size}")
                            finalPaths = remapPathsForBurnIn(finalPaths, downloadLocation, tempFileDir.absolutePath).toMutableList()
                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(
                                    tempFileDir.absolutePath,
                                    ytdlpPhase.state.startedAt
                                ).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub remap-empty temp fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=${tempFileDir.absolutePath}"
                                )
                            }
                            Log.i(TAG, "HardSub post-remap paths=${finalPaths.size}")
                            if (shouldStopForUserRequest()) return@launch
                            Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${finalPaths.size}")
                            eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                            currentIssueStage = DownloadIssueStage.HARD_SUB
                            val burned = withOwnedExecutionLease(downloadItem) {
                                burnSubtitlesInPlace(
                                    finalPaths,
                                    noKeepSubs,
                                    downloadItem.id,
                                    downloadItem.executionId,
                                    downloadItem.logID,
                                    downloadItem.videoPreferences.subsLanguages,
                                )
                            }
                            hardSubBurned = hardSubBurned || burned
                            if (!burned && isHardSubRedownload(downloadItem)) {
                                throw IOException("HardSub aborted: no media was burned")
                            }
                            if (burned) {
                                eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                Log.i(TAG, "HardSub completed id=${downloadItem.id}")
                            } else {
                                Log.w(TAG, "HardSub produced no burned media id=${downloadItem.id}")
                            }
                        }

                        if (
                            downloadItem.type == DownloadType.video &&
                            !downloadItem.videoPreferences.embedSubs &&
                            (
                                commandHasYtdlpOption(ytdlpPhase.state.effectiveCommand, "--write-subs") ||
                                    commandHasYtdlpOption(ytdlpPhase.state.effectiveCommand, "--write-auto-subs")
                            )
                        ) {
                            validateSavedSubtitleSidecars(
                                downloadItem,
                                finalPaths,
                                downloadLocation,
                                ytdlpPhase.state.startedAt
                            )
                        }

                        val nonMediaExtensions = mutableSetOf<String>().apply {
                            addAll(context.getStringArray(R.array.thumbnail_containers_values).map { it.lowercase(Locale.US) })
                            addAll(context.getStringArray(R.array.sub_formats_values).filter { it.isNotBlank() }.map { it.lowercase(Locale.US) })
                            // Hard-sub flow can download rich subtitle sidecars not present in sub_formats_values.
                            addAll(listOf("srv3", "json3", "json", "ttml"))
                            add("description")
                            add("txt")
                        }
                        finalPaths = finalPaths.filter { path ->
                            val file = File(path)
                            file.exists() &&
                                file.isFile &&
                                file.extension.lowercase(Locale.US) !in nonMediaExtensions
                        }.toMutableList()
                        if (!noCache) {
                            val beforeTempFilter = finalPaths.size
                            finalPaths = finalPaths.filterNot { path ->
                                isPathInsideDirectory(path, tempFileDir)
                            }.toMutableList()
                            if (beforeTempFilter != finalPaths.size) {
                                Log.w(
                                    TAG,
                                    "HardSub filtered temp output paths id=${downloadItem.id} removed=${beforeTempFilter - finalPaths.size}"
                                )
                            }
                            if (finalPaths.isEmpty()) {
                                val strandedTempMedia = recoverPathsFromDirectory(
                                    tempFileDir.absolutePath,
                                    ytdlpPhase.state.startedAt
                                )
                                    .filter { candidate ->
                                        val file = File(candidate)
                                        file.exists() &&
                                            file.isFile &&
                                            file.extension.lowercase(Locale.US) !in nonMediaExtensions
                                    }
                                if (strandedTempMedia.isNotEmpty()) {
                                    val moveFailureDetails = FileUtil.consumeLastMoveFailureDetails()
                                    throw IOException(
                                        "Downloaded files remain in temporary storage after move failure" +
                                            (moveFailureDetails?.let { ": $it" } ?: "")
                                    )
                                }
                            }
                        }
                        finalPaths = prioritizePrimaryMediaPath(finalPaths, downloadItem.type)
                        if (shouldStopForUserRequest()) return@launch
                        if (finalPaths.isNotEmpty()) {
                            val summary = finalPaths.joinToString(limit = 5) { path ->
                                val file = File(path)
                                "${file.name}(size=${file.length()},mtime=${file.lastModified()})"
                            }
                            Log.i(TAG, "HardSub final paths id=${downloadItem.id} count=${finalPaths.size} sample=$summary")
                        }
                        ytdlpPhase.state.requests.forEach { requestToCleanup ->
                            runCatching { FileUtil.deleteConfigFiles(requestToCleanup) }
                                .onFailure { cleanupError ->
                                    Log.w(TAG, "Config cleanup failed id=${downloadItem.id}", cleanupError)
                                }
                        }
                        recordCreatedOutputs(finalPaths)
                        val completionIssues = mutableListOf<DownloadIssue>()
                        ytdlpPhase.result.qualityWarning?.let { mismatch ->
                            completionIssues += DownloadIssue.create(
                                stage = DownloadIssueStage.DOWNLOAD,
                                code = DownloadIssueCode.FORMAT_UNAVAILABLE,
                                severity = DownloadIssueSeverity.WARNING,
                                suggestedActions = setOf(
                                    DownloadSuggestedAction.VIEW_LOG,
                                    DownloadSuggestedAction.RECONFIGURE,
                                ),
                                details = "Requested ${mismatch.expectedHeight}p; downloaded " +
                                    "${mismatch.actualHeight}p after bounded fallback",
                                source = DownloadIssueSource.EXPLICIT_STATE,
                            )
                        }

                        //put download in history
                        currentIssueStage = DownloadIssueStage.HISTORY
                        if (shouldStopForUserRequest()) return@launch
                        try {
                            if (!downloadItem.incognito) {
                                if (ytdlpPhase.state.activeRequest.hasOption("--download-archive") && finalPaths.isEmpty()) {
                                    handler.postDelayed({
                                        Toast.makeText(context, resources.getString(R.string.download_already_exists), Toast.LENGTH_LONG).show()
                                    }, 100)
                                } else if (finalPaths.isNotEmpty()) {
                                    val unixTime = System.currentTimeMillis() / 1000
                                    finalPaths.first().apply {
                                        val file = File(this)
                                        var duration = downloadItem.duration
                                        val d = file.getMediaDuration(context)
                                        if (d > 0) duration = d.toStringDuration(Locale.US)

                                        downloadItem.format.filesize = file.length()
                                        downloadItem.format.container = file.extension
                                        downloadItem.duration = duration
                                    }

                                    val replacedHistoryId = HistoryRedownloadMarker
                                        .parse(downloadItem.playlistURL)
                                        ?.historyId ?: 0L
                                    val isHistoryRedownload = replacedHistoryId > 0L

                                    val completedHardSub = hardSubBurned
                                    val observeKeyword = if (downloadItem.observeSourceId > 0L) {
                                        runCatching { observeSourcesDao.getByID(downloadItem.observeSourceId).autoAddKeyword.trim() }.getOrDefault("")
                                    } else {
                                        ""
                                    }
                                    val existingDuplicateHistoryItem = if (!isHistoryRedownload) {
                                        findExistingHistoryForDownloadedItem(downloadItem, historyDao)
                                    } else {
                                        null
                                    }

                                    val preferredThumbPath = pickLocalThumbnailPath(finalPaths) ?: downloadItem.thumb
                                    val historyItem = HistoryItem(
                                        id = replacedHistoryId,
                                        url = downloadItem.url,
                                        title = downloadItem.title,
                                        author = downloadItem.author,
                                        artist = "",
                                        duration = downloadItem.duration,
                                        durationSeconds = downloadItem.duration.toDurationSeconds(),
                                        thumb = preferredThumbPath,
                                        type = downloadItem.type,
                                        time = unixTime,
                                        lastWatched = 0,
                                        downloadPath = finalPaths,
                                        website = downloadItem.website,
                                        format = downloadItem.format,
                                        filesize = downloadItem.format.filesize,
                                        downloadId = downloadItem.id,
                                        command = ytdlpPhase.state.initialCommand,
                                        playbackPositionMs = 0,
                                        localTreeUri = "",
                                        localTreePath = "",
                                        keywords = "",
                                        customThumb = "",
                                        hardSubScanRemoved = completedHardSub,
                                        hardSubDone = completedHardSub,
                                        mediaPublishedAt = downloadItem.mediaPublishedAt.takeIf(MediaPublishedDate::isPresent)
                                            ?: 0L
                                    )
                                    val replacementOutcome = if (replacedHistoryId > 0L) {
                                        historyKeywordAssignments
                                            .replaceHistoryPreservingAssignmentsAuthorizedBlocking(
                                                historyId = replacedHistoryId,
                                                expectedSourceUrl = downloadItem.url,
                                                expectedType = downloadItem.type,
                                                replacementDownloadId = downloadItem.id,
                                                replacementOperationId = downloadItem.operationId,
                                                expectedExecutionId = downloadItem.executionId,
                                            ) { previous ->
                                                historyItem.copy(
                                                    id = previous.id,
                                                    artist = previous.artist,
                                                    lastWatched = previous.lastWatched,
                                                    playbackPositionMs = if (completedHardSub) {
                                                        0L
                                                    } else {
                                                        previous.playbackPositionMs
                                                    },
                                                    localTreeUri = "",
                                                    localTreePath = "",
                                                    keywords = previous.keywords,
                                                    customThumb = previous.customThumb,
                                                    hardSubScanRemoved = if (completedHardSub) {
                                                        true
                                                    } else {
                                                        previous.hardSubScanRemoved
                                                    },
                                                    hardSubDone = if (completedHardSub) {
                                                        true
                                                    } else {
                                                        previous.hardSubDone
                                                    },
                                                    mediaPublishedAt = downloadItem.mediaPublishedAt
                                                        .takeIf(MediaPublishedDate::isPresent)
                                                        ?: previous.mediaPublishedAt
                                                )
                                            }
                                    } else {
                                        null
                                    }
                                    if (replacementOutcome is HistoryReplacementOutcome.Updated) {
                                        // The replacement transaction also records
                                        // the linked quality-child success fact.
                                        // Cancellation and ancillary failures after
                                        // this point cannot undo the primary commit.
                                        historyReplacementCommitted = true
                                    }
                                    if (!historyReplacementCommitted && shouldStopForUserRequest()) return@launch
                                    val persistedHistoryId = if (replacedHistoryId > 0L) {
                                        val replacement = replacementOutcome!!
                                        val replacementTerminalAction =
                                            HistoryReplacementOutcomePolicy.terminalAction(replacement)
                                        historyReplacementTerminalAction =
                                            HistoryReplacementOutcomePolicy.mergeTerminalAction(
                                                historyReplacementTerminalAction,
                                                replacementTerminalAction
                                            )
                                        when (replacementTerminalAction) {
                                            HistoryReplacementTerminalAction.COMPLETE ->
                                                (replacement as HistoryReplacementOutcome.Updated)
                                                    .previousTarget.id
                                            HistoryReplacementTerminalAction.TARGET_DELETED -> {
                                                val targetDeletedIssue =
                                                    HistoryReplacementDiagnostic.targetDeletedIssue()
                                                establishHistoryTargetDeleted()
                                                completionIssues += targetDeletedIssue
                                                null
                                            }
                                            HistoryReplacementTerminalAction.PRESERVE_FAILED -> {
                                                throw HistoryReplacementNotAuthorizedException(
                                                    mismatch = HistoryReplacementDiagnostic.mismatchKind(replacement)
                                                        ?: error("Missing mismatch kind for unauthorized replacement")
                                                )
                                            }
                                        }
                                    } else {
                                        historyKeywordAssignments.insertHistory(historyItem)
                                    }
                                    persistedHistoryId?.let { historyId ->
                                        com.ireum.ytdl.database.repository.AutomaticKeywordRuleEngine(dbManager)
                                            .applyToHistory(
                                                historyId,
                                                downloadItem.url,
                                                downloadItem.observeSourceId,
                                                observeKeyword
                                            )
                                    }
                                    if (replacedHistoryId > 0L && persistedHistoryId != null) {
                                        if (!historyReplacementCommitted && shouldStopForUserRequest()) return@launch
                                        deleteReplacedHistoryMedia(
                                            previousHistoryItem =
                                                (replacementOutcome as? HistoryReplacementOutcome.Updated)
                                                    ?.previousTarget,
                                            finalPaths = finalPaths,
                                            downloadItem = downloadItem,
                                        )
                                    } else if (
                                        replacedHistoryId == 0L &&
                                        existingDuplicateHistoryItem != null &&
                                        persistedHistoryId != null
                                    ) {
                                        PendingDuplicateDownloadStore.add(
                                            sharedPreferences,
                                            newHistoryId = persistedHistoryId,
                                            existingHistoryId = existingDuplicateHistoryItem.id
                                        )
                                        Log.i(
                                            TAG,
                                            "Duplicate download needs user choice newHistoryId=$persistedHistoryId existingHistoryId=${existingDuplicateHistoryItem.id} url=${downloadItem.url}"
                                        )
                                    }
                                }
                            }
                        } catch (historyError: Exception) {
                            if (historyError is CancellationException) throw historyError
                            val committedHistoryReplacement = historyReplacementCommitted ||
                                withContext(Dispatchers.IO + NonCancellable) {
                                    isDurablyCommittedHistoryReplacement(dbManager, downloadItem)
                                }
                            if (committedHistoryReplacement) {
                                historyReplacementCommitted = true
                                completionIssues += DownloadIssue.create(
                                    stage = DownloadIssueStage.HISTORY,
                                    code = DownloadIssueCode.HISTORY_POST_COMMIT_WARNING,
                                    severity = DownloadIssueSeverity.WARNING,
                                    suggestedActions = setOf(DownloadSuggestedAction.VIEW_LOG),
                                    details = historyError.message.orEmpty(),
                                    source = DownloadIssueSource.TYPED_EXCEPTION,
                                )
                                Log.w(
                                    TAG,
                                    "History ancillary work failed after replacement commit id=${downloadItem.id}",
                                    historyError,
                                )
                            } else {
                                if (historyError is HistoryReplacementExecutionOwnershipLostException) {
                                    Log.w(
                                        TAG,
                                        "Stale History replacement attempt stopped id=${downloadItem.id}",
                                        historyError,
                                    )
                                    return@launch
                                }
                                if (historyError is DownloadExecutionOwnershipLostException) {
                                    Log.w(
                                        TAG,
                                        "Stale History cleanup attempt stopped id=${downloadItem.id}",
                                        historyError,
                                    )
                                    return@launch
                                }
                                preserveQueueRecord = true
                                downloadItem.status = DownloadRepository.Status.Error.toString()
                                val historyIssue = if (
                                    historyError is HistoryReplacementNotAuthorizedException
                                ) {
                                historyReplacementTerminalAction =
                                    HistoryReplacementOutcomePolicy.mergeTerminalAction(
                                        historyReplacementTerminalAction,
                                        HistoryReplacementTerminalAction.PRESERVE_FAILED
                                    )
                                HistoryReplacementDiagnostic.issue(historyError.mismatch).also { issue ->
                                    establishHistoryReplacementFailure(issue)
                                }
                            } else if (
                                historyError is HistoryReplacementAuthorizationRefusalException
                            ) {
                                adoptHistoryReplacementAuthorization(historyError.authorization)
                                historyReplacementAuthoritativeIssue
                                    ?: error("History replacement refusal did not establish an issue")
                            } else if (
                                historyError is HistoryReplacementRefusalPersistenceException
                            ) {
                                adoptHistoryReplacementAuthorization(historyError.authorization)
                                historyError.issue
                            } else if (
                                historyError is HistoryReplacementQualityAuthorityLostException
                            ) {
                                if (historyError.cancellationOrigin) {
                                    downloadOutcome =
                                        HistoryReplacementDiagnostic.qualityAuthorityLossOutcome(
                                            cancellationOrigin = true,
                                        )
                                    return@launch
                                }
                                establishQualityAuthorityLoss()
                                } else {
                                    historyReplacementAuthoritativeIssue ?: DownloadIssue.create(
                                        stage = DownloadIssueStage.HISTORY,
                                        code = DownloadIssueCode.HISTORY_WRITE_FAILED,
                                        severity = DownloadIssueSeverity.WARNING,
                                        suggestedActions = setOf(
                                            DownloadSuggestedAction.VIEW_LOG,
                                            DownloadSuggestedAction.COPY_SUMMARY
                                        ),
                                        details = historyError.message.orEmpty(),
                                        source = DownloadIssueSource.TYPED_EXCEPTION
                                    )
                                }
                                downloadItem.lastIssueCode = historyIssue.code.name
                                downloadItem.lastIssueStage = DownloadIssueStage.HISTORY.name
                                val persistenceResult = persistHistoryReplacementTerminalState(
                                    issue = historyIssue,
                                    persistDownload = {
                                        check(
                                            dao.updateIfExecutionOwnedAndRunning(
                                                downloadItem,
                                                downloadItem.executionId,
                                            )
                                        ) {
                                            "History mismatch ownership lost for download ${downloadItem.id}"
                                        }
                                    },
                                    transitionLinkedDownload = { reason ->
                                        LowQualityRedownloadLedger.transition(
                                            context,
                                            downloadItem.id,
                                            com.ireum.ytdl.database.models.LowQualityRedownloadItemState.FAILED,
                                            reason = reason,
                                            expectedExecutionId = downloadItem.executionId,
                                        )
                                    },
                                    isCancellationRequested = {
                                        LowQualityRedownloadRepository(dbManager)
                                            .isCancellationRequestedForDownload(downloadItem.id)
                                    },
                                    onLinkedTransitionFailure = {
                                        LowQualityRedownloadLedger.scheduleConvergence(
                                            context,
                                            downloadItem.id,
                                        )
                                    },
                                )
                                if (
                                    HistoryReplacementDiagnostic
                                        .isPersistedHistoryReplacementRefusal(historyIssue.code.name) &&
                                        persistenceResult is HistoryReplacementPersistenceResult.Persisted
                                ) {
                                    // The first barrier insert/read-back may
                                    // have failed before this catch.  The
                                    // Download diagnostic is portable, but it
                                    // must be followed by the immutable barrier
                                    // before the worker is allowed to finish.
                                    check(
                                        withContext(Dispatchers.IO + NonCancellable) {
                                            DownloadRepository(dbManager)
                                                .persistHistoryReplacementRefusalCarrier(
                                                    id = downloadItem.id,
                                                    expectedExecutionId = downloadItem.executionId,
                                                    issueCode = historyIssue.code.name,
                                                    issueStage = historyIssue.stage.name,
                                                )
                                        }
                                    ) {
                                        "History refusal carrier could not be restored for download ${downloadItem.id}"
                                    }
                                }
                                val unrecoverableMismatch =
                                    unrecoverableHistoryReplacementPersistenceFailure(
                                        historyReplacementAuthoritativeIssue,
                                        persistenceResult,
                                    )
                                if (unrecoverableMismatch != null) {
                                    throw unrecoverableMismatch
                                }
                                if (persistenceResult is HistoryReplacementPersistenceResult.Failed) {
                                    Log.e(
                                        TAG,
                                        "Failed to mark history recovery record id=${downloadItem.id}",
                                        persistenceResult.error
                                    )
                                }
                                completionIssues += historyIssue
                                Log.e(TAG, "History update failed after file creation id=${downloadItem.id}", historyError)
                            }
                        }

                        if (isHardSubRedownload(downloadItem)) {
                            if (shouldStopForUserRequest()) return@launch
                            markHardSubProcessed(downloadItem.id)
                            updateHardSubWorkerNotificationSafely(notificationUtil)
                        }
                        currentIssueStage = DownloadIssueStage.NOTIFICATION
                        val notificationIssue = historyReplacementFailureIssue
                            ?: completionIssues.firstOrNull()
                        val notificationMessage = notificationIssue?.let { issue ->
                            DownloadIssueText.formatted(resources, issue)
                        }
                        try {
                            withContext(Dispatchers.Main) {
                                notificationUtil.cancelRunningDownloadNotification(downloadItem.id.toInt())
                                if (historyReplacementFailureIssue == null) {
                                    notificationUtil.createDownloadFinished(
                                        downloadItem.id,
                                        notificationTitle,
                                        downloadItem.type,
                                        if (finalPaths.isEmpty()) null else finalPaths,
                                        resources,
                                        notificationMessage
                                    )
                                } else {
                                    notificationUtil.createDownloadErrored(
                                        downloadItem.id,
                                        notificationTitle,
                                        notificationMessage.orEmpty(),
                                        downloadItem.logID,
                                        resources,
                                        retryable = false,
                                        allowReconfigure = false
                                    )
                                }
                            }
                        } catch (notificationError: Exception) {
                            if (notificationError is CancellationException) throw notificationError
                            completionIssues += DownloadIssue.create(
                                stage = DownloadIssueStage.NOTIFICATION,
                                code = DownloadIssueCode.NOTIFICATION_FAILED,
                                severity = DownloadIssueSeverity.WARNING,
                                suggestedActions = setOf(DownloadSuggestedAction.VIEW_LOG),
                                details = notificationError.message.orEmpty(),
                                source = DownloadIssueSource.TYPED_EXCEPTION
                            )
                            Log.e(TAG, "Finished notification failed after file creation id=${downloadItem.id}", notificationError)
                        }
                        val terminalOutcome = composeCompletionOutcome(
                            createdFileCount = createdOutputPaths.size,
                            issues = completionIssues,
                            forceFailure = historyReplacementFailureIssue != null,
                        )
                        downloadOutcome = terminalOutcome
                        val outcomeSummary = terminalOutcome.issues
                            .joinToString(separator = "\n") { issue ->
                                DownloadIssueText.formatted(resources, issue)
                            }
                            .takeIf { it.isNotBlank() }
                        outcomeSummary?.let { summary ->
                            eventBus.post(
                                WorkerProgress(100, summary, downloadItem.id, downloadItem.logID)
                            )
                        }

//                        if (wasQuickDownloaded && createResultItem){
//                            runCatching {
//                                eventBus.post(WorkerProgress(100, "Creating Result Items", downloadItem.id))
//                                runBlocking {
//                                    infoUtil.getFromYTDL(downloadItem.url).forEach { res ->
//                                        if (res != null) {
//                                            resultDao.insert(res)
//                                        }
//                                    }
//                                }
//                            }
//                        }

                        if (!preserveQueueRecord) {
                            if (!historyReplacementCommitted && shouldStopForUserRequest()) return@launch
                            val downloadRepository = DownloadRepository(dbManager)
                            val affectedOperations = if (
                                historyReplacementTerminalAction ==
                                    HistoryReplacementTerminalAction.TARGET_DELETED
                            ) {
                                downloadRepository.completeHistoryTargetDeletedAndDelete(
                                    id = downloadItem.id,
                                    expectedExecutionId = downloadItem.executionId,
                                )
                            } else {
                                downloadRepository.completeAndDelete(
                                    id = downloadItem.id,
                                    expectedExecutionId = downloadItem.executionId,
                                )
                            }
                            runCatching {
                                LowQualityRedownloadLedger.refresh(context, affectedOperations)
                            }
                        }

                        if (ytdlpPhase.state.logging.enabled){
                            val structuredOutcomeLog = outcomeSummary?.let {
                                "\nStructured outcome: ${terminalOutcome.status}\n$it\n"
                            }.orEmpty()
                            logRepo.update(
                                ytdlpPhase.state.logging.initialDetails +
                                    ytdlpPhase.state.logging.retryDetails +
                                    SensitiveTextRedactor.redactOutput(ytdlpPhase.result.response.out) +
                                    structuredOutcomeLog,
                                ytdlpPhase.state.logging.logId ?: 0L,
                                true
                            )
                        }

                    } catch (it: Exception) {
                        val failedYtdlpState = ytdlpExecutionState
                        if (downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs) {
                            Log.e(TAG, "HardSub failed id=${downloadItem.id} type=${it.javaClass.simpleName}")
                        }
                        failedYtdlpState?.requests.orEmpty().forEach { requestToCleanup ->
                            runCatching { FileUtil.deleteConfigFiles(requestToCleanup) }
                                .onFailure { cleanupError ->
                                    Log.w(TAG, "Failure cleanup failed id=${downloadItem.id}", cleanupError)
                                }
                        }
                        if (it is CancellationException) {
                            downloadOutcome = DownloadOutcome.canceled()
                            throw it
                        }
                        if (it is NativeProcessQuiescenceException) {
                            // Root yt-dlp success is not a Download success
                            // while the exact native generation remains
                            // unresolved. Leave the row/marker as durable
                            // recovery debt and let doWork() run the native
                            // cleanup protocol; no output, History,
                            // notification, outcome, or row deletion may be
                            // published from this branch.
                            throw it
                        }
                        if (
                            it is HistoryReplacementAuthorizationRefusalException ||
                            it is HistoryReplacementRefusalPersistenceException ||
                            it is HistoryReplacementQualityAuthorityLostException
                        ) {
                            // Preserve the typed History decision for the outer
                            // terminal state machine; do not classify it as the
                            // later hard-sub/yt-dlp exception.
                            throw it
                        }
                        if (
                            it is HistoryReplacementExecutionOwnershipLostException ||
                            it is DownloadExecutionOwnershipLostException
                        ) {
                            Log.w(
                                TAG,
                                "Stale Download execution stopped id=${downloadItem.id}",
                                it,
                            )
                            return@launch
                        }
                        cancelDownloadNotificationSafely(notificationUtil, downloadItem)
                        val latestStatus = runCatching { dao.checkStatus(downloadItem.id) }.getOrNull()
                        val itemLocallyCancelled =
                            DownloadCancellationRegistry.belongsTo(
                                downloadItem.id,
                                downloadItem.executionId,
                            )
                        val lowQualityCancellationRequested = try {
                            LowQualityRedownloadRepository(dbManager)
                                .isCancellationRequestedForDownload(downloadItem.id)
                        } catch (_: Exception) {
                            false
                        }
                        if (
                            this@DownloadWorker.isStopped ||
                            it is YoutubeDL.CanceledException ||
                            itemLocallyCancelled ||
                            lowQualityCancellationRequested ||
                            latestStatus == DownloadRepository.Status.Paused ||
                            latestStatus == DownloadRepository.Status.Cancelled
                        ) {
                            downloadOutcome = DownloadOutcome.canceled()
                            return@launch
                        }
                        val destinationWritable = if (
                            currentIssueStage == DownloadIssueStage.PREFLIGHT ||
                            currentIssueStage == DownloadIssueStage.MOVE
                        ) {
                            runCatching {
                                FileUtil.canWriteToDestination(downloadItem.downloadPath, context)
                            }.getOrNull()
                        } else {
                            null
                        }
                        val classifiedIssues = DownloadIssueClassifier.classify(
                            DownloadIssueClassifier.Input(
                                stage = currentIssueStage,
                                exceptionClassName = it.javaClass.name,
                                message = it.message.orEmpty(),
                                output = failedYtdlpState?.logging?.recentOutput.orEmpty().joinToString("\n"),
                                destinationWritable = destinationWritable
                            )
                        )
                        var primaryIssue = classifiedIssues.first()
                        var qualityCleanupAction: HistoryReplacementCleanupAction? = null
                        var qualityCleanupCompleted = true
                        val qualityValidationException = it as? QualityReplacementValidationException
                        qualityValidationException?.candidatePaths?.let(::recordCreatedOutputs)
                        val carriedQualityCleanupResult = qualityValidationException?.cleanupResult

                        val failedQualityMarker = HistoryRedownloadMarker.parse(downloadItem.playlistURL)
                            ?.takeIf { marker -> marker.isQualityReplacement }
                        if (failedQualityMarker != null) {
                            if (historyReplacementFailureIssue == null) {
                                val cleanupResult = carriedQualityCleanupResult
                                    ?: deleteRejectedQualityReplacementOutputs(
                                        historyId = failedQualityMarker.historyId,
                                        candidatePaths = createdOutputPaths,
                                        historyDao = historyDao,
                                        historyKeywordAssignments = historyKeywordAssignments,
                                        downloadItem = downloadItem,
                                    )
                                qualityCleanupCompleted = cleanupResult.cleanupCompleted
                                val cleanupAuthorization = cleanupResult.authorization
                                val cleanupAction = HistoryReplacementOutcomePolicy.cleanupAction(
                                    cleanupAuthorization
                                )
                                qualityCleanupAction = cleanupAction
                                historyReplacementTerminalAction =
                                    HistoryReplacementOutcomePolicy.mergeTerminalAction(
                                        historyReplacementTerminalAction,
                                        HistoryReplacementOutcomePolicy.terminalAction(cleanupAction)
                                    )
                                when (cleanupAuthorization) {
                                    is HistoryReplacementAuthorization.Authorized ->
                                        if (cleanupResult.cleanupCompleted) {
                                            createdOutputPaths = emptyList()
                                    }
                                    HistoryReplacementAuthorization.TargetMissing -> {
                                        establishHistoryTargetDeleted()
                                        primaryIssue = HistoryReplacementDiagnostic.targetDeletedIssue()
                                    }
                                    HistoryReplacementAuthorization.SourceMismatch,
                                    HistoryReplacementAuthorization.TypeMismatch -> {
                                        val mismatchKind = HistoryReplacementDiagnostic.mismatchKind(
                                            cleanupAuthorization
                                        ) ?: error("Missing mismatch kind for refused quality cleanup")
                                        val mismatchIssue = HistoryReplacementDiagnostic.issue(mismatchKind)
                                        establishHistoryReplacementFailure(mismatchIssue)
                                        primaryIssue = mismatchIssue
                                    }
                                }
                            }
                            runCatching {
                                withOwnedExecutionSideEffect(downloadItem) {
                                    resetYtdlpTempDirectoryUnsafe(
                                        rawTempDirectory = rawTempFileDir,
                                        downloadId = downloadItem.id,
                                        beforeRetry = true,
                                    ).delete()
                                }
                            }.onFailure { cleanupError ->
                                Log.w(
                                    TAG,
                                    "Failed to clean rejected quality replacement cache id=${downloadItem.id}",
                                    cleanupError
                                )
                            }
                        }

                        createdOutputPaths = createdOutputPaths.filter { path ->
                            val file = File(path)
                            file.exists() && file.isFile
                        }
                        primaryIssue = historyReplacementFailureIssue ?: primaryIssue
                        val failureIssues = historyReplacementFailureIssue?.let { issue ->
                            listOf(issue) + classifiedIssues
                        } ?: if (
                            historyReplacementTerminalAction ==
                                HistoryReplacementTerminalAction.TARGET_DELETED
                        ) {
                            listOf(primaryIssue) + classifiedIssues
                        } else {
                            classifiedIssues
                        }
                        val structuredFailureSummary = failureIssues.joinToString("\n") { issue ->
                            DownloadIssueText.formatted(resources, issue)
                        }
                        val targetDeleted = historyReplacementTerminalAction ==
                            HistoryReplacementTerminalAction.TARGET_DELETED
                        if (targetDeleted || HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                                hasCreatedOutputs = createdOutputPaths.isNotEmpty(),
                                cleanupAction = qualityCleanupAction,
                                authoritativeAction = historyReplacementTerminalAction,
                                cleanupCompleted = qualityCleanupCompleted,
                            )
                        ) {
                            val warningIssue = if (targetDeleted) {
                                HistoryReplacementDiagnostic.targetDeletedIssue()
                            } else {
                                DownloadIssue.create(
                                    stage = primaryIssue.stage,
                                    code = primaryIssue.code,
                                    severity = DownloadIssueSeverity.WARNING,
                                    suggestedActions = setOf(
                                        DownloadSuggestedAction.VIEW_LOG,
                                        DownloadSuggestedAction.COPY_SUMMARY
                                    ),
                                    details = primaryIssue.redactedDetails,
                                    source = primaryIssue.source
                                )
                            }
                            val partialOutcome = DownloadOutcome.completed(
                                createdFileCount = createdOutputPaths.size,
                                issues = listOf(warningIssue)
                            )
                            downloadOutcome = partialOutcome
                            val warningSummary = DownloadIssueText.formatted(resources, warningIssue)
                            if (failedYtdlpState?.logging?.enabled == true) {
                                logRepo.update(
                                    "\nStructured outcome: ${partialOutcome.status}\n$warningSummary\n",
                                    failedYtdlpState.logging.logId ?: 0L
                                )
                            }
                            if (!preserveQueueRecord) {
                                try {
                                    if (shouldStopForUserRequest()) return@launch
                                    val affectedOperations = DownloadRepository(dbManager)
                                        .let { repository ->
                                            if (targetDeleted) {
                                                repository.completeHistoryTargetDeletedAndDelete(
                                                    id = downloadItem.id,
                                                    expectedExecutionId = downloadItem.executionId,
                                                )
                                            } else {
                                                repository.completeAndDelete(
                                                    id = downloadItem.id,
                                                    successReason = "SUCCESS_WITH_WARNINGS",
                                                    expectedExecutionId = downloadItem.executionId,
                                                )
                                            }
                                        }
                                    runCatching {
                                        LowQualityRedownloadLedger.refresh(
                                            context,
                                            affectedOperations
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (deleteError: Exception) {
                                    preserveQueueRecord = true
                                    downloadItem.status = DownloadRepository.Status.Error.toString()
                                    downloadItem.lastIssueCode = primaryIssue.code.name
                                    downloadItem.lastIssueStage = primaryIssue.stage.name
                                    try {
                                        check(
                                            dao.updateIfExecutionOwnedAndRunning(
                                                downloadItem,
                                                downloadItem.executionId,
                                            )
                                        ) {
                                            "Download ownership lost while persisting failure ${downloadItem.id}"
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (updateError: Exception) {
                                        Log.e(
                                            TAG,
                                            "Failed to preserve queue record id=${downloadItem.id}",
                                            updateError
                                        )
                                    }
                                    Log.e(
                                        TAG,
                                        "Failed to delete completed queue record id=${downloadItem.id}",
                                        deleteError
                                    )
                                }
                            }
                            try {
                                withContext(Dispatchers.Main) {
                                    notificationUtil.createDownloadFinished(
                                        downloadItem.id,
                                        notificationTitle,
                                        downloadItem.type,
                                        createdOutputPaths,
                                        resources,
                                        warningSummary
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (notificationError: Exception) {
                                Log.w(
                                    TAG,
                                    "Failed to report partial download completion id=${downloadItem.id}",
                                    notificationError
                                )
                            }
                            eventBus.post(
                                WorkerProgress(100, warningSummary, downloadItem.id, downloadItem.logID)
                            )
                            return@launch
                        }
                        val failedOutcome = DownloadOutcome.failed(primaryIssue)
                        downloadOutcome = failedOutcome
                        if (it.message?.contains("JSONDecodeError") == true) {
                            runCatching {
                                withOwnedExecutionSideEffect(downloadItem) {
                                    deleteLoadedAppInfoJson(
                                        failedYtdlpState?.effectiveCommand.orEmpty()
                                    )
                                }
                            }
                        }

                        val failureDiagnostics = buildFailureDiagnostics(
                            error = it,
                            item = downloadItem,
                            requestCommand = failedYtdlpState?.effectiveCommand.orEmpty(),
                            tempDir = failedYtdlpState?.validatedTempDirectory ?: rawTempFileDir,
                            recentOutput = failedYtdlpState?.logging?.recentOutput.orEmpty()
                        ) + "\nStructured failure:\n$structuredFailureSummary\n"
                        if (failedYtdlpState?.logging?.enabled == true){
                            runCatching {
                                withOwnedExecutionSideEffect(downloadItem) {
                                    logRepo.update(
                                        failureDiagnostics,
                                        failedYtdlpState.logging.logId ?: 0L,
                                    )
                                }
                            }
                        }


                        failedYtdlpState?.validatedTempDirectory?.let { failedTempDirectory ->
                            runCatching {
                                withOwnedExecutionSideEffect(downloadItem) {
                                    failedTempDirectory.deleteRecursively()
                                }
                            }
                                .onFailure { cleanupError ->
                                    Log.w(
                                        TAG,
                                        "Failed to clean failed download cache id=${downloadItem.id}",
                                        cleanupError
                                    )
                                }
                        }

                        Log.e(
                            TAG,
                            "${context.getString(R.string.failed_download)} id=${downloadItem.id} type=${it.javaClass.simpleName}",
                            it
                        )
                        cancelDownloadNotificationSafely(notificationUtil, downloadItem)

                        val membershipDecision = MembershipAccessPolicy.decide(
                            observeSourceId = downloadItem.observeSourceId,
                            previousIssueCode = downloadItem.lastIssueCode
                        )
                        if (
                            primaryIssue.code == DownloadIssueCode.MEMBERSHIP_REQUIRED &&
                            membershipDecision.waitForAutomaticRetry
                        ) {
                            val parked = observeSourcesDao.parkDownloadForMembership(
                                downloadId = downloadItem.id,
                                sourceId = downloadItem.observeSourceId,
                                expectedStatus = DownloadRepository.Status.Active.toString(),
                                issueCode = primaryIssue.code.name,
                                issueStage = primaryIssue.stage.name,
                                expectedExecutionId = downloadItem.executionId,
                            ) > 0
                            if (parked) {
                                downloadOutcome = DownloadOutcome(
                                    status = com.ireum.ytdl.util.download.DownloadOutcomeStatus.WAITING_FOR_ACCESS,
                                    issues = listOf(primaryIssue)
                                )
                                downloadItem.status =
                                    DownloadRepository.Status.WaitingForMembership.toString()
                                downloadItem.lastIssueCode = primaryIssue.code.name
                                downloadItem.lastIssueStage = primaryIssue.stage.name
                                if (membershipDecision.showFirstWaitingNotification) {
                                    notificationUtil.createMembershipWaiting(
                                        downloadItem.id,
                                        notificationTitle,
                                        resources
                                    )
                                }
                                eventBus.post(
                                    WorkerProgress(
                                        100,
                                        resources.getString(R.string.membership_waiting_auto),
                                        downloadItem.id,
                                        downloadItem.logID
                                    )
                                )
                                return@launch
                            }
                        }

                        downloadItem.status = DownloadRepository.Status.Error.toString()
                        downloadItem.lastIssueCode = primaryIssue.code.name
                        downloadItem.lastIssueStage = primaryIssue.stage.name
                        val terminalPersistence = persistHistoryReplacementTerminalState(
                            issue = primaryIssue,
                            persistDownload = {
                                check(
                                    dao.updateIfExecutionOwnedAndRunning(
                                        downloadItem,
                                        downloadItem.executionId,
                                    )
                                ) {
                                    "Download ownership lost while persisting failure ${downloadItem.id}"
                                }
                            },
                            transitionLinkedDownload = { reason ->
                                LowQualityRedownloadLedger.transition(
                                    context,
                                    downloadItem.id,
                                    com.ireum.ytdl.database.models.LowQualityRedownloadItemState.FAILED,
                                    reason = reason,
                                    expectedExecutionId = downloadItem.executionId,
                                )
                            },
                            isCancellationRequested = {
                                LowQualityRedownloadRepository(dbManager)
                                    .isCancellationRequestedForDownload(downloadItem.id)
                            },
                            onLinkedTransitionFailure = {
                                LowQualityRedownloadLedger.scheduleConvergence(
                                    context,
                                    downloadItem.id,
                                )
                            },
                        )
                        if (terminalPersistence is HistoryReplacementPersistenceResult.Failed) {
                            throw terminalPersistence.error
                        }
                        if (
                            runCatching {
                                LowQualityRedownloadRepository(dbManager)
                                    .isCancellationRequestedForDownload(downloadItem.id)
                            }.getOrDefault(false)
                        ) {
                            // Phase-one low-quality cancellation may commit
                            // while the terminal Download write/ledger
                            // handoff is completing.  Cancellation remains the
                            // terminal semantic; do not publish an Error
                            // notification for the racing failure.
                            downloadOutcome = DownloadOutcome.canceled()
                            return@launch
                        }
                        if (isHardSubRedownload(downloadItem)) {
                            if (shouldStopForUserRequest()) return@launch
                            markHardSubProcessed(downloadItem.id)
                            updateHardSubWorkerNotificationSafely(notificationUtil)
                        }

                        val retryMetadata = DownloadRetryMetadata(
                            operationId = downloadItem.operationId,
                            attempt = downloadItem.retryAttempt,
                            strategy = runCatching {
                                DownloadRetryStrategy.valueOf(downloadItem.retryStrategy)
                            }.getOrDefault(DownloadRetryStrategy.ORIGINAL)
                        )

                        notificationUtil.createDownloadErrored(
                            downloadItem.id,
                            notificationTitle,
                            structuredFailureSummary,
                            downloadItem.logID,
                            resources,
                            retryable = DownloadRetryPolicy.canOffer(
                                retryMetadata,
                                DownloadRetryStrategy.SAME_SETTINGS,
                                primaryIssue.retryable
                            ),
                            allowReconfigure =
                                DownloadSuggestedAction.RECONFIGURE in primaryIssue.suggestedActions &&
                                    DownloadRetryPolicy.canOffer(
                                        retryMetadata,
                                        DownloadRetryStrategy.RECONFIGURED
                                    ),
                            retryCapabilityOperationId = downloadItem.operationId,
                            retryCapabilityAttempt = downloadItem.retryAttempt,
                        )

                        eventBus.post(
                            WorkerProgress(
                                100,
                                structuredFailureSummary,
                                downloadItem.id,
                                downloadItem.logID
                            )
                        )
                    }
                    } catch (unexpected: Exception) {
                        if (unexpected is CancellationException) throw unexpected
                        if (unexpected is NativeProcessQuiescenceException) {
                            // A completed root/supervisor process is not a
                            // completed Download while its exact descendant
                            // generation remains unresolved.  Keep this out
                            // of the generic terminal-error branch so it
                            // cannot publish an outcome, notification, row
                            // deletion, or handled WorkManager success.  The
                            // worker-level cleanup path records and retries
                            // the exact native recovery responsibility.
                            throw unexpected
                        }
                        if (
                            unexpected is HistoryReplacementExecutionOwnershipLostException ||
                            unexpected is DownloadExecutionOwnershipLostException
                        ) {
                            Log.w(
                                TAG,
                                "Stale Download attempt stopped before terminal handling id=${downloadItem.id}",
                                unexpected,
                            )
                            return@launch
                        }
                        val committedHistoryReplacement = historyReplacementCommitted ||
                            withContext(Dispatchers.IO + NonCancellable) {
                                isDurablyCommittedHistoryReplacement(dbManager, downloadItem)
                            }
                        if (committedHistoryReplacement) {
                            historyReplacementCommitted = true
                            val current = withContext(Dispatchers.IO + NonCancellable) {
                                dao.getNullableDownloadById(downloadItem.id)
                            }
                            if (current != null) {
                                try {
                                    val affectedOperations = DownloadRepository(dbManager)
                                        .completeAndDelete(
                                            id = downloadItem.id,
                                            expectedExecutionId = downloadItem.executionId,
                                        )
                                    runCatching {
                                        LowQualityRedownloadLedger.refresh(context, affectedOperations)
                                    }
                                } catch (finalizationError: Exception) {
                                    if (finalizationError is CancellationException) throw finalizationError
                                    Log.e(
                                        TAG,
                                        "Committed History replacement could not finalize download id=${downloadItem.id}",
                                        finalizationError,
                                    )
                                    throw unexpected
                                }
                            }
                            downloadOutcome = DownloadOutcome.completed(
                                createdFileCount = 0,
                                issues = listOf(
                                    DownloadIssue.create(
                                        stage = DownloadIssueStage.HISTORY,
                                        code = DownloadIssueCode.HISTORY_POST_COMMIT_WARNING,
                                        severity = DownloadIssueSeverity.WARNING,
                                        suggestedActions = setOf(DownloadSuggestedAction.VIEW_LOG),
                                        details = unexpected.message.orEmpty(),
                                        source = DownloadIssueSource.TYPED_EXCEPTION,
                                    )
                                ),
                            )
                            Log.w(
                                TAG,
                                "Ancillary Download work failed after committed History replacement id=${downloadItem.id}",
                                unexpected,
                            )
                            return@launch
                        }
                        when (unexpected) {
                            is HistoryReplacementAuthorizationRefusalException ->
                                adoptHistoryReplacementAuthorization(unexpected.authorization)
                            is HistoryReplacementRefusalPersistenceException ->
                                adoptHistoryReplacementAuthorization(unexpected.authorization)
                            is HistoryReplacementQualityAuthorityLostException -> {
                                if (unexpected.cancellationOrigin) {
                                    downloadOutcome =
                                        HistoryReplacementDiagnostic.qualityAuthorityLossOutcome(
                                            cancellationOrigin = true,
                                        )
                                    return@launch
                                }
                                establishQualityAuthorityLoss()
                            }
                            else -> Unit
                        }
                        refreshDurableHistoryReplacementBarrier()
                        val targetDeleted = historyReplacementTerminalAction ==
                            HistoryReplacementTerminalAction.TARGET_DELETED
                        val fallbackIssue = DownloadIssue.create(
                            stage = currentIssueStage,
                            code = DownloadIssueCode.UNKNOWN,
                            details = unexpected.message.orEmpty(),
                            source = DownloadIssueSource.TYPED_EXCEPTION
                        )
                        val issue = authoritativeDownloadIssue(
                            establishedHistoryIssue = historyReplacementAuthoritativeIssue,
                            fallbackIssue = fallbackIssue,
                        )
                        downloadOutcome = if (targetDeleted) {
                            DownloadOutcome.completed(
                                createdFileCount = 0,
                                issues = listOf(issue),
                            )
                        } else {
                            DownloadOutcome.failed(issue)
                        }
                        withContext(Dispatchers.IO + NonCancellable) {
                            var recoveryResult: HistoryReplacementPersistenceResult? = null
                            try {
                                val latest = dao.getNullableDownloadById(downloadItem.id)
                                val isMismatch = historyReplacementFailureIssue != null
                                val refusalIssue = historyReplacementAuthoritativeIssue
                                    ?.takeIf {
                                        HistoryReplacementDiagnostic
                                            .isPersistedHistoryReplacementRefusal(it.code.name)
                                    }

                                suspend fun ensureRefusalCarrier(issue: DownloadIssue): Boolean {
                                    val current = dao.getNullableDownloadById(downloadItem.id)
                                        ?: return true
                                    if (
                                        downloadItem.executionId.isNotBlank() &&
                                            current.executionId != downloadItem.executionId
                                    ) {
                                        return false
                                    }
                                    val carrierCreated = DownloadRepository(dbManager)
                                        .persistHistoryReplacementRefusalCarrier(
                                            id = downloadItem.id,
                                            expectedExecutionId = downloadItem.executionId,
                                            issueCode = issue.code.name,
                                            issueStage = issue.stage.name,
                                        )
                                    if (!carrierCreated) return false

                                    // Keep the portable Download diagnostic
                                    // projection aligned as well.  The barrier
                                    // remains authoritative if this projection
                                    // is raced by a user status transition.
                                    runCatching {
                                        if (
                                            dao.persistAuthoritativeIssueForExecution(
                                                id = downloadItem.id,
                                                executionId = downloadItem.executionId,
                                                issueCode = issue.code.name,
                                                issueStage = issue.stage.name,
                                            ) == 0
                                        ) {
                                            Log.w(
                                                TAG,
                                                "History refusal diagnostic projection was not updated id=${downloadItem.id}",
                                            )
                                        }
                                    }.onFailure { projectionFailure ->
                                        Log.w(
                                            TAG,
                                            "History refusal diagnostic projection failed id=${downloadItem.id}",
                                            projectionFailure,
                                        )
                                    }
                                    val verified = dao.getNullableDownloadById(downloadItem.id)
                                    val verifiedBarrier = dbManager.historyReplacementBarrierDao
                                        .getByDownloadId(downloadItem.id)
                                    return verifiedBarrier?.issueCode == issue.code.name &&
                                        verifiedBarrier.issueStage == issue.stage.name ||
                                        verified?.lastIssueCode == issue.code.name &&
                                            verified.lastIssueStage == issue.stage.name
                                }

                                if (latest == null) {
                                    // The row is already gone; there is no
                                    // remaining Download marker that could be
                                    // re-authorized by this worker.
                                    recoveryResult = HistoryReplacementPersistenceResult.Persisted
                                } else if (refusalIssue != null) {
                                    val carrierPersisted = ensureRefusalCarrier(refusalIssue)
                                    if (!carrierPersisted) {
                                        recoveryResult = HistoryReplacementPersistenceResult.Failed(
                                            IllegalStateException(
                                                "Authoritative History refusal carrier was not durable for " +
                                                    "download ${downloadItem.id}"
                                            )
                                        )
                                    } else if (
                                        targetDeleted &&
                                        latest.status in setOf(
                                            DownloadRepository.Status.Active.name,
                                            DownloadRepository.Status.PostProcessing.name,
                                        ) &&
                                        latest.executionId == downloadItem.executionId
                                    ) {
                                        val affectedOperations = DownloadRepository(dbManager)
                                            .completeHistoryTargetDeletedAndDelete(
                                                id = downloadItem.id,
                                                expectedExecutionId = downloadItem.executionId,
                                            )
                                        runCatching {
                                            LowQualityRedownloadLedger.refresh(context, affectedOperations)
                                        }
                                        recoveryResult = HistoryReplacementPersistenceResult.Persisted
                                    } else if (
                                        latest.status in setOf(
                                            DownloadRepository.Status.Active.name,
                                            DownloadRepository.Status.PostProcessing.name,
                                        )
                                    ) {
                                    downloadItem.status = DownloadRepository.Status.Error.toString()
                                    downloadItem.lastIssueCode = issue.code.name
                                    downloadItem.lastIssueStage = issue.stage.name
                                    recoveryResult = persistHistoryReplacementTerminalState(
                                        issue = issue,
                                        persistDownload = {
                                            check(
                                                dao.updateIfExecutionOwnedAndRunning(
                                                    downloadItem,
                                                    downloadItem.executionId,
                                                )
                                            ) {
                                                "History mismatch ownership lost during recovery ${downloadItem.id}"
                                            }
                                        },
                                        transitionLinkedDownload = { reason ->
                                            LowQualityRedownloadLedger.transition(
                                                context,
                                                downloadItem.id,
                                                com.ireum.ytdl.database.models.LowQualityRedownloadItemState.FAILED,
                                                reason = reason,
                                                expectedExecutionId = downloadItem.executionId,
                                            )
                                        },
                                        isCancellationRequested = {
                                            LowQualityRedownloadRepository(dbManager)
                                                .isCancellationRequestedForDownload(downloadItem.id)
                                        },
                                        onLinkedTransitionFailure = {
                                            LowQualityRedownloadLedger.scheduleConvergence(
                                                context,
                                                downloadItem.id,
                                            )
                                        },
                                    )
                                    } else {
                                        // Pause/Cancel is a user-state
                                        // dimension, not permission to erase a
                                        // refusal observed by the privileged
                                        // operation.  The carrier was already
                                        // verified above; converge only the
                                        // linked child without forcing the
                                        // user's Download status back to Error.
                                        try {
                                            LowQualityRedownloadLedger.transition(
                                                context,
                                                downloadItem.id,
                                                if (targetDeleted) {
                                                    com.ireum.ytdl.database.models.LowQualityRedownloadItemState.SKIPPED
                                                } else {
                                                    com.ireum.ytdl.database.models.LowQualityRedownloadItemState.FAILED
                                                },
                                                reason = if (targetDeleted) {
                                                    DownloadRepository.REASON_HISTORY_TARGET_DELETED
                                                } else {
                                                    issue.code.name
                                                },
                                                expectedExecutionId = downloadItem.executionId,
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            LowQualityRedownloadLedger.scheduleConvergence(
                                                context,
                                                downloadItem.id,
                                            )
                                        }
                                        recoveryResult = HistoryReplacementPersistenceResult.Persisted
                                    }
                                } else if (isMismatch) {
                                    val durableBarrier = dbManager.historyReplacementBarrierDao
                                        .getByDownloadId(downloadItem.id)
                                    val fieldsAlreadyPersisted = latest?.let {
                                        it.lastIssueCode == issue.code.name &&
                                            it.lastIssueStage == issue.stage.name
                                    } == true
                                    val barrierAlreadyPersisted = durableBarrier?.let {
                                        it.issueCode == issue.code.name &&
                                            it.issueStage == issue.stage.name
                                    } == true
                                    val fieldsPersistedByRecovery = if (
                                        !fieldsAlreadyPersisted &&
                                        !barrierAlreadyPersisted &&
                                        latest?.executionId == downloadItem.executionId
                                    ) {
                                        dao.persistMismatchIssueForExecution(
                                            id = downloadItem.id,
                                            executionId = downloadItem.executionId,
                                            issueCode = issue.code.name,
                                            issueStage = issue.stage.name,
                                        ) == 1
                                    } else {
                                        false
                                    }
                                    val carrierPersisted = fieldsAlreadyPersisted ||
                                        barrierAlreadyPersisted ||
                                        fieldsPersistedByRecovery
                                    if (carrierPersisted) {
                                        try {
                                            LowQualityRedownloadLedger.transition(
                                                context,
                                                downloadItem.id,
                                                com.ireum.ytdl.database.models.LowQualityRedownloadItemState.FAILED,
                                                reason = issue.code.name,
                                                expectedExecutionId = downloadItem.executionId,
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            // Keep the deliberate Download/barrier
                                            // and ledger boundary, then derive
                                            // the child terminal state again.
                                            LowQualityRedownloadLedger.scheduleConvergence(
                                                context,
                                                downloadItem.id,
                                            )
                                        }
                                        recoveryResult = HistoryReplacementPersistenceResult.Persisted
                                    } else {
                                        recoveryResult = HistoryReplacementPersistenceResult.Failed(
                                            IllegalStateException(
                                                "Authoritative History mismatch carrier was not durable for " +
                                                    "download ${downloadItem.id}"
                                            )
                                        )
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (recoveryError: Exception) {
                                recoveryResult = HistoryReplacementPersistenceResult.Failed(recoveryError)
                            }
                            val recoveryFailure = recoveryResult as? HistoryReplacementPersistenceResult.Failed
                            if (recoveryFailure != null) {
                                Log.e(
                                    TAG,
                                    "Failed to recover unexpected download error id=${downloadItem.id}",
                                    recoveryFailure.error
                                )
                            }
                            try {
                                notificationUtil.cancelRunningDownloadNotification(downloadItem.id.toInt())
                                notificationUtil.createDownloadErrored(
                                    downloadItem.id,
                                    SensitiveTextRedactor.redactOutput(
                                        downloadItem.title.ifBlank { downloadItem.url }
                                    ),
                                    DownloadIssueText.formatted(resources, issue),
                                    downloadItem.logID,
                                    resources,
                                    retryable = false,
                                    allowReconfigure = historyReplacementAuthoritativeIssue == null,
                                    retryCapabilityOperationId = downloadItem.operationId,
                                    retryCapabilityAttempt = downloadItem.retryAttempt,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (notificationError: Exception) {
                                Log.w(
                                    TAG,
                                    "Failed to report unexpected download error id=${downloadItem.id}",
                                    notificationError
                                )
                            }
                            unrecoverableHistoryReplacementPersistenceFailure(
                                establishedHistoryIssue = historyReplacementAuthoritativeIssue,
                                result = recoveryResult,
                            )?.let { unrecoverableMismatch ->
                                throw unrecoverableMismatch
                            }
                            // An ordinary terminal write/recovery failure is
                            // still an unhandled worker failure.  Do not let
                            // the child finish successfully while its row is
                            // potentially Active/PostProcessing and no longer
                            // owned by a live execution.
                            (recoveryResult as? HistoryReplacementPersistenceResult.Failed)
                                ?.let { recoveryFailure ->
                                    unexpected.addSuppressed(recoveryFailure.error)
                                    throw unexpected
                                }
                            if (
                                runCatching {
                                    LowQualityRedownloadRepository(dbManager)
                                        .isCancellationRequestedForDownload(downloadItem.id)
                                }.getOrDefault(false)
                            ) {
                                downloadOutcome = DownloadOutcome.canceled()
                                return@withContext
                            }
                        }
                        Log.e(TAG, "Unexpected download failure id=${downloadItem.id}", unexpected)
                    } finally {
                        downloadOutcome?.let { outcome ->
                            Log.i(
                                TAG,
                                "Download outcome id=${downloadItem.id} status=${outcome.status} issues=${outcome.issues.map { it.code }}"
                            )
                        }
                        if (hardSubPostProcessLockHeld) {
                            hardSubPostProcessMutex.unlock()
                        }
                        // The child owns the token it was claimed with.  Looking
                        // it up from the mutable per-worker map would let a
                        // rapid Pause -> Resume claim replace the old token and
                        // allow the stale child to clean up the new attempt.
                        val expectedExecutionId = downloadItem.executionId
                            .takeIf { it.isNotBlank() }
                            ?: workerExecutionIds[downloadItem.id]
                        val workerEntryMatches = expectedExecutionId.isNullOrBlank() ||
                            workerExecutionIds[downloadItem.id] == expectedExecutionId
                        val latestStatus = withContext(Dispatchers.IO + NonCancellable) {
                            runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()
                        }
                        val stillOwnsAttempt = expectedExecutionId.isNullOrBlank() ||
                            latestStatus?.executionId == expectedExecutionId
                        if (stillOwnsAttempt) {
                            if (workerEntryMatches) {
                                workerDownloadIds.remove(downloadItem.id)
                            }
                            if (
                                latestStatus == null ||
                                    latestStatus.status !in setOf(
                                        DownloadRepository.Status.Active.name,
                                        DownloadRepository.Status.PostProcessing.name,
                                    )
                            ) {
                                expectedExecutionId?.let {
                                    DownloadWorkerExecutionOwners.release(downloadItem.id, it)
                                    if (
                                        !hasNativeProcessRegistryEntry(
                                            downloadItem.id,
                                            it,
                                        )
                                    ) {
                                        DownloadWorkerProcessOwners.release(downloadItem.id, it)
                                    }
                                }
                                if (workerEntryMatches) {
                                    workerCleanupDownloadIds.remove(downloadItem.id)
                                    workerAuthoritativeIssues.remove(downloadItem.id)
                                    if (expectedExecutionId != null) {
                                        workerExecutionIds.remove(downloadItem.id, expectedExecutionId)
                                    } else {
                                        workerExecutionIds.remove(downloadItem.id)
                                    }
                                }
                            }
                        } else {
                            // The row now belongs to another attempt.  Drop only
                            // this child's process-local bookkeeping; never touch
                            // the newer owner's row or token.
                            if (workerEntryMatches) {
                                workerDownloadIds.remove(downloadItem.id)
                                workerCleanupDownloadIds.remove(downloadItem.id)
                                workerAuthoritativeIssues.remove(downloadItem.id)
                            }
                            expectedExecutionId?.let {
                                DownloadWorkerExecutionOwners.release(downloadItem.id, it)
                                if (
                                    !hasNativeProcessRegistryEntry(
                                        downloadItem.id,
                                        it,
                                    )
                                ) {
                                    DownloadWorkerProcessOwners.release(downloadItem.id, it)
                                }
                                if (workerEntryMatches) {
                                    workerExecutionIds.remove(downloadItem.id, it)
                                }
                            }
                        }
                    }
            }
        }

        return Result.success()
    }

    private data class YtdlpPhaseInput(
        val downloadItem: DownloadItem,
        val rawTempDirectory: File,
        val notificationTitle: String,
        val loggingEnabled: Boolean,
    )

    private data class YtdlpPhaseServices(
        val ytdlpUtil: YTDLPUtil,
        val notificationUtil: NotificationUtil,
        val logRepository: LogRepository,
        val downloadDao: DownloadDao,
        val resources: Resources,
    )

    private data class YtdlpPhasePreparation(
        val initialAttempt: YtdlpAttempt,
        val registeredRequests: List<YoutubeDLRequest>,
        val logging: YtdlpLoggingSnapshot,
    )

    private sealed interface YtdlpPhaseOutcome {
        val state: YtdlpExecutionState

        data class Completed(
            val result: YtdlpExecutionResult,
            override val state: YtdlpExecutionState,
        ) : YtdlpPhaseOutcome

        data class Failed(
            val error: Exception,
            override val state: YtdlpExecutionState,
        ) : YtdlpPhaseOutcome

        data class Cancelled(
            val error: CancellationException,
            override val state: YtdlpExecutionState,
        ) : YtdlpPhaseOutcome
    }

    private data class YtdlpExecutionResult(
        val response: YoutubeDLResponse,
        val qualityWarning: VideoQualityMismatch? = null,
    )

    private data class YtdlpAttemptsResult(
        val response: YoutubeDLResponse,
        val qualityWarning: VideoQualityMismatch? = null,
    )

    private data class VideoQualityMismatch(
        val expectedHeight: Int,
        val actualHeight: Int,
    )

    private class YtdlpQualityRejectedException(message: String) : IOException(message)

    private class QualityReplacementValidationException(
        val cleanupResult: HistoryReplacementCleanupResult,
        val candidatePaths: List<String>,
        message: String,
    ) : IOException(message)

    private class HistoryReplacementNotAuthorizedException(
        val mismatch: HistoryReplacementMismatchKind
    ) : IOException(HistoryReplacementDiagnostic.details(mismatch))

    private sealed interface CompletedYtdlpQualityOutcome {
        data class Accept(val result: YtdlpAttemptsResult) : CompletedYtdlpQualityOutcome
        data class Retry(val profile: YoutubeMediaAccessProfile) : CompletedYtdlpQualityOutcome
        data class Reject(val message: String) : CompletedYtdlpQualityOutcome
    }

    private data class YtdlpExecutionState(
        val requests: List<YoutubeDLRequest>,
        val validatedTempDirectory: File?,
        val initialCommand: String,
        val effectiveCommand: String,
        val startedAt: Long,
        val issueStage: DownloadIssueStage,
        val logging: YtdlpLoggingSnapshot,
    ) {
        val activeRequest: YoutubeDLRequest
            get() = requests.last()
    }

    private data class YtdlpLoggingSnapshot(
        val enabled: Boolean,
        val logId: Long?,
        val initialDetails: String,
        val retryDetails: String,
        val recentOutput: List<String>,
    )

    private data class YtdlpAttempt(
        val request: YoutubeDLRequest,
        val command: String,
        val diagnostics: String,
        val mediaAccessProfile: YoutubeMediaAccessProfile,
        val qualityGuardApplied: Boolean,
        val qualityTargetHeight: Int?,
    )

    private sealed interface YtdlpRetryPlan {
        object NoRetry : YtdlpRetryPlan

        sealed interface Attempt : YtdlpRetryPlan {
            val notice: String
            val mediaAccessProfile: YoutubeMediaAccessProfile
            val useCachedInfoJson: Boolean
            val applyQualityGuard: Boolean
        }

        data class CachedInfo(
            override val notice: String,
            override val mediaAccessProfile: YoutubeMediaAccessProfile,
        ) : Attempt
        {
            override val useCachedInfoJson = false
            override val applyQualityGuard = true
        }

        data class Route(
            override val notice: String,
            override val mediaAccessProfile: YoutubeMediaAccessProfile,
            override val applyQualityGuard: Boolean = true,
        ) : Attempt {
            override val useCachedInfoJson = false
        }
    }

    private class YtdlpPhaseRuntimeState(
        preparation: YtdlpPhasePreparation,
        startedAt: Long,
    ) {
        private val requestRegistry = preparation.registeredRequests.toMutableList()
        val recentOutput = ArrayDeque<String>().apply {
            addAll(preparation.logging.recentOutput)
        }
        val currentAttemptOutput = ArrayDeque<String>()
        var validatedTempDirectory: File? = null
        val initialCommand = preparation.initialAttempt.command
        var effectiveCommand = preparation.initialAttempt.command
        val startedAt = startedAt
        var issueStage = DownloadIssueStage.PREFLIGHT
        val loggingEnabled = preparation.logging.enabled
        val logId = preparation.logging.logId
        val initialLogDetails = preparation.logging.initialDetails
        var retryLogDetails = preparation.logging.retryDetails
        var lastNotificationUpdateAt = 0L
        var lastNotificationProgress = -1
        var currentAttemptTransferStarted = false
        var completedMediaTransfers = 0

        fun beginAttempt() {
            currentAttemptTransferStarted = false
            currentAttemptOutput.clear()
        }

        fun registerRequest(request: YoutubeDLRequest) {
            requestRegistry += request
        }

        fun snapshot() = YtdlpExecutionState(
            requests = requestRegistry.toList(),
            validatedTempDirectory = validatedTempDirectory,
            initialCommand = initialCommand,
            effectiveCommand = effectiveCommand,
            startedAt = startedAt,
            issueStage = issueStage,
            logging = YtdlpLoggingSnapshot(
                enabled = loggingEnabled,
                logId = logId,
                initialDetails = initialLogDetails,
                retryDetails = retryLogDetails,
                recentOutput = recentOutput.toList(),
            ),
        )
    }

    private suspend fun executeYtdlpPhase(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
        eventBus: EventBus,
        preparation: YtdlpPhasePreparation,
        startedAt: Long,
    ): YtdlpPhaseOutcome {
        val runtime = YtdlpPhaseRuntimeState(preparation, startedAt)
        return try {
            runtime.validatedTempDirectory = resetYtdlpTempDirectory(
                downloadItem = input.downloadItem,
                rawTempDirectory = input.rawTempDirectory,
                beforeRetry = false,
            )
            val progressCallback = createYtdlpProgressCallback(input, services, eventBus, runtime)
            val attemptsResult = executeYtdlpAttempts(
                input = input,
                services = services,
                eventBus = eventBus,
                runtime = runtime,
                initialAttempt = preparation.initialAttempt,
                progressCallback = progressCallback,
            )
            YtdlpPhaseOutcome.Completed(
                YtdlpExecutionResult(
                    response = attemptsResult.response,
                    qualityWarning = attemptsResult.qualityWarning,
                ),
                runtime.snapshot()
            )
        } catch (error: CancellationException) {
            YtdlpPhaseOutcome.Cancelled(error, runtime.snapshot())
        } catch (error: Exception) {
            YtdlpPhaseOutcome.Failed(error, runtime.snapshot())
        }
    }

    private suspend fun prepareYtdlpPhase(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
    ): YtdlpPhasePreparation {
        val downloadItem = input.downloadItem
        ensureExecutionOwnedBeforeAttempt(downloadItem)
        val requestOwner = YtdlpPreparationRequestOwner<YoutubeDLRequest>(
            cleanupRequest = FileUtil::deleteConfigFiles,
        )
        return try {
            val (initialProfile, request) = withOwnedExecutionSideEffect(downloadItem) {
                val profile = services.ytdlpUtil.resolveInitialYoutubeMediaAccessProfile(downloadItem)
                val builtRequest = services.ytdlpUtil.buildYoutubeDLRequest(downloadItem, profile)
                requestOwner.register(builtRequest)
                profile to builtRequest
            }
            val initialAttempt = run {
                downloadItem.status = DownloadRepository.Status.Active.toString()
                if (downloadItem.operationId.isBlank()) {
                    downloadItem.operationId = "download-${downloadItem.id}"
                }
                val command = services.ytdlpUtil.parseYTDLRequestString(request)
                val rawFormatOverride = YoutubeMediaAccessPolicy.containsRawFormatOverride(
                    downloadItem.extraCommands,
                )
                YtdlpAttempt(
                    request = request,
                    command = command,
                    diagnostics = services.ytdlpUtil.buildRequestDiagnostics(
                        downloadItem,
                        request,
                        command,
                        initialProfile,
                    ),
                    mediaAccessProfile = initialProfile,
                    qualityGuardApplied = !rawFormatOverride && commandQualityTarget(command) != null,
                    qualityTargetHeight = commandQualityTarget(command).takeUnless { rawFormatOverride },
                )
            }
            val initialLogDetails = SensitiveTextRedactor.redactOutput(
                "Downloading:\n" +
                    "Title: ${downloadItem.title}\n" +
                    "URL: ${downloadItem.url}\n" +
                    "Type: ${downloadItem.type}\n" +
                    "Command:\n${SensitiveTextRedactor.redactCommand(initialAttempt.command)} \n" +
                    "${initialAttempt.diagnostics}\n"
            )
            val logItem = LogItem(
                0,
                SensitiveTextRedactor.redactOutput(downloadItem.title.ifBlank { downloadItem.url }),
                initialLogDetails,
                downloadItem.format,
                downloadItem.type,
                System.currentTimeMillis(),
            )
            ensureExecutionOwnedBeforeAttempt(downloadItem)
            val logId = if (input.loggingEnabled) {
                withOwnedExecutionSideEffect(downloadItem) {
                    services.logRepository.insert(logItem).also { insertedLogId ->
                        logItem.id = insertedLogId
                        downloadItem.logID = insertedLogId
                    }
                }
            } else {
                downloadItem.logID = null
                null
            }
            check(
                services.downloadDao.updateIfExecutionOwnedAndRunning(
                    downloadItem,
                    downloadItem.executionId,
                )
            ) {
                "Download ownership lost before yt-dlp execution ${downloadItem.id}"
            }
            YtdlpPhasePreparation(
                initialAttempt = initialAttempt,
                registeredRequests = requestOwner.snapshot(),
                logging = YtdlpLoggingSnapshot(
                    enabled = input.loggingEnabled,
                    logId = logId,
                    initialDetails = initialLogDetails,
                    retryDetails = "",
                    recentOutput = emptyList(),
                ),
            )
        } catch (error: Throwable) {
            cleanupYtdlpPreparationAndRethrow(requestOwner, error)
        }
    }

    private fun buildYtdlpAttempt(
        downloadItem: DownloadItem,
        ytdlpUtil: YTDLPUtil,
        retryPlan: YtdlpRetryPlan.Attempt,
        onRequestBuilt: (YoutubeDLRequest) -> Unit,
        onCommandBuilt: (String) -> Unit,
    ): YtdlpAttempt {
        val request = ytdlpUtil.buildYoutubeDLRequest(
            downloadItem = downloadItem,
            mediaAccessProfile = retryPlan.mediaAccessProfile,
            useCachedInfoJson = retryPlan.useCachedInfoJson,
            applyQualityGuard = retryPlan.applyQualityGuard,
        )
        onRequestBuilt(request)
        val command = ytdlpUtil.parseYTDLRequestString(request)
        val rawFormatOverride = YoutubeMediaAccessPolicy.containsRawFormatOverride(
            downloadItem.extraCommands,
        )
        onCommandBuilt(command)
        return YtdlpAttempt(
            request = request,
            command = command,
            diagnostics = ytdlpUtil.buildRequestDiagnostics(
                downloadItem,
                request,
                command,
                retryPlan.mediaAccessProfile,
            ),
            mediaAccessProfile = retryPlan.mediaAccessProfile,
            qualityGuardApplied = retryPlan.applyQualityGuard &&
                !rawFormatOverride && commandQualityTarget(command) != null,
            qualityTargetHeight = commandQualityTarget(command).takeUnless { rawFormatOverride },
        )
    }

    private fun createYtdlpProgressCallback(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
        eventBus: EventBus,
        runtime: YtdlpPhaseRuntimeState,
    ): (Float, Long, String) -> Unit {
        val item = input.downloadItem
        val notificationTitle = input.notificationTitle
        return progressCallback@{ progress, _, line ->
            fun runOwnedSideEffect(block: suspend () -> Unit): Boolean = runCatching {
                runBlocking(Dispatchers.IO) {
                    withOwnedExecutionSideEffect(item, block)
                }
            }.isSuccess

            val stillOwnsAttempt = runCatching {
                runBlocking(Dispatchers.IO) {
                    ensureExecutionOwnedBeforeAttempt(item)
                }
                true
            }.getOrDefault(false)
            if (!stillOwnsAttempt) return@progressCallback
            runtime.issueStage = DownloadIssueStageTracker.update(runtime.issueStage, line)
            val normalizedDownloadLine = line.trimStart()
            if (
                normalizedDownloadLine.startsWith("[download]") &&
                (
                    normalizedDownloadLine.contains("Destination:", ignoreCase = true) ||
                        normalizedDownloadLine.contains("%") ||
                        normalizedDownloadLine.contains("Resuming download", ignoreCase = true)
                    )
            ) {
                runtime.currentAttemptTransferStarted = true
            }
            val redactedLine = SensitiveTextRedactor.redactOutput(line)
            if (item.type == DownloadType.video && item.videoPreferences.embedSubs) {
                val lowerLine = line.lowercase(Locale.US)
                if (
                    lowerLine.contains("downloading subtitles") ||
                    lowerLine.contains("writing video subtitles to:") ||
                    lowerLine.contains("subtitle") ||
                    lowerLine.contains("subtitlesconvertor")
                ) {
                    if (!runOwnedSideEffect {
                            Log.i(TAG, "HardSub sub log id=${item.id}: $redactedLine")
                        }) {
                        return@progressCallback
                    }
                }
            }
            if (!runOwnedSideEffect {
                    eventBus.post(WorkerProgress(progress.toInt(), redactedLine, item.id, item.logID))
                }) {
                return@progressCallback
            }
            val now = System.currentTimeMillis()
            val intProgress = progress.toInt()
            val progressAdvancedEnough = runtime.lastNotificationProgress < 0 ||
                (intProgress - runtime.lastNotificationProgress) >= 2
            if (
                now - runtime.lastNotificationUpdateAt >= 800L ||
                progressAdvancedEnough ||
                intProgress >= 100
            ) {
                val totalHardSubs = hardSubTargetIds.size
                val hardSubStatus = if (totalHardSubs <= 0) {
                    null
                } else {
                    services.resources.getString(
                        R.string.hard_sub_progress,
                        hardSubProcessedIds.size.coerceAtMost(totalHardSubs),
                        totalHardSubs,
                    )
                }
                if (!runOwnedSideEffect {
                        services.notificationUtil.updateDownloadNotification(
                            item.id.toInt(),
                            redactedLine,
                            intProgress,
                            0,
                            notificationTitle,
                            NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
                            hardSubStatus,
                            item.executionId,
                        )
                    }) {
                    return@progressCallback
                }
                runtime.lastNotificationUpdateAt = now
                runtime.lastNotificationProgress = intProgress
            }
            if (!runOwnedSideEffect {
                    if (runtime.loggingEnabled) {
                        services.logRepository.update(redactedLine, runtime.logId ?: 0L)
                    }
                    runtime.recentOutput.addLast(redactedLine)
                    runtime.currentAttemptOutput.addLast(redactedLine)
                    while (runtime.recentOutput.size > FAILURE_YTDLP_TAIL_LINES) {
                        runtime.recentOutput.removeFirst()
                    }
                    while (runtime.currentAttemptOutput.size > FAILURE_YTDLP_TAIL_LINES) {
                        runtime.currentAttemptOutput.removeFirst()
                    }
                }) {
                return@progressCallback
            }
        }
    }

    private suspend fun executeYtdlpAttempt(
        input: YtdlpPhaseInput,
        runtime: YtdlpPhaseRuntimeState,
        attempt: YtdlpAttempt,
        progressCallback: (Float, Long, String) -> Unit,
    ): YoutubeDLResponse {
        ensureExecutionOwnedBeforeAttempt(input.downloadItem)
        runtime.issueStage = DownloadIssueStage.EXTRACT
        val processId = YtdlpProcessIdentity.download(
            input.downloadItem.id,
            input.downloadItem.executionId,
        )
        return coroutineScope {
            val processRegistered = CompletableDeferred<Unit>()
            val execution = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    YoutubeDLCompat.executeWithQuiescence(
                        applicationContext,
                        attempt.request,
                        processId,
                        true,
                        progressCallback,
                        onProcessRegistered = { processRegistered.complete(Unit) },
                    )
                } catch (failure: Throwable) {
                    processRegistered.completeExceptionally(failure)
                    throw failure
                }
            }
            var completedResponse: YoutubeDLResponse? = null
            var pendingFailure: Throwable? = null
            var nativeRecoveryFailure: NativeProcessQuiescenceException? = null
            try {
                // The global worker mutex only protects the exact ownership
                // read/claim.  The per-download lease covers the final
                // revalidation through native process registration, so a
                // durable Pause/Cancel that wins before this gate prevents the
                // stale execution from starting, while a start that wins the
                // gate is quiesced by the same lease before cancellation can
                // commit.
                withDownloadWorkerExecutionSideEffectLease(
                    downloadId = input.downloadItem.id,
                    executionId = input.downloadItem.executionId,
                ) {
                    withDownloadWorkerExecutionLock {
                        assertExecutionOwnedBeforeAttemptLocked(input.downloadItem)
                        check(
                            !hasConflictingNativeProcess(
                                input.downloadItem.id,
                                input.downloadItem.executionId,
                            )
                        ) {
                            "Native process for another execution remains registered for download " +
                                input.downloadItem.id
                        }
                        check(
                            DownloadWorkerProcessOwners.claim(
                                input.downloadItem.id,
                                input.downloadItem.executionId,
                            )
                        ) {
                            "Native process owner changed before starting download " +
                                input.downloadItem.id
                        }
                    }
                    // The side-effect lease keeps Pause/Cancel and a newer
                    // execution from taking this Download's resources while
                    // cache/temp preparation and native start run.  The
                    // global claim mutex is deliberately not held here.
                    prepareProcessForExecution(
                        input.downloadItem.id,
                        input.downloadItem.executionId,
                    )
                    execution.start()
                    try {
                        processRegistered.await()
                    } catch (failure: YoutubeDLCompat.NativeExecutionFailure) {
                        if (failure.finalization.isProvenQuiescent) {
                            throw failure.originalFailure
                        }
                        throw failure
                    }
                }
                val executionResult = try {
                    execution.await()
                } catch (failure: YoutubeDLCompat.NativeExecutionFailure) {
                    if (failure.finalization.isProvenQuiescent) {
                        throw failure.originalFailure
                    }
                    throw failure
                }
                if (!executionResult.nativeQuiescent) {
                    completedResponse = executionResult.response
                    nativeRecoveryFailure = NativeProcessQuiescenceException(
                        downloadId = input.downloadItem.id,
                        executionId = input.downloadItem.executionId,
                        originalFailure = executionResult.response.exitCode
                            .takeIf { it > 0 }
                            ?.let {
                                YoutubeDLException(
                                    "yt-dlp exited with code $it while descendants remained unresolved",
                                )
                            },
                    )
                } else {
                    completedResponse = executionResult.response
                }
            } catch (failure: YoutubeDLCompat.NativeExecutionFailure) {
                nativeRecoveryFailure = NativeProcessQuiescenceException(
                    downloadId = input.downloadItem.id,
                    executionId = input.downloadItem.executionId,
                    originalFailure = failure.originalFailure,
                )
            } catch (failure: NativeProcessQuiescenceException) {
                nativeRecoveryFailure = failure
            } catch (failure: Throwable) {
                pendingFailure = failure
            } finally {
                withContext(Dispatchers.IO + NonCancellable) {
                    withDownloadWorkerExecutionSideEffectLease(
                        downloadId = input.downloadItem.id,
                        executionId = input.downloadItem.executionId,
                    ) {
                        val shouldCancel = withDownloadWorkerExecutionLock {
                            !execution.isCompleted ||
                                YoutubeDLCompat.hasProcessById(processId)
                        }
                        if (shouldCancel) {
                            val quiesced = runCatching {
                                cancelYtdlpProcess(
                                    input.downloadItem.id,
                                    input.downloadItem.executionId,
                                )
                            }.getOrDefault(false)
                            if (quiesced) {
                                execution.cancel()
                                nativeRecoveryFailure?.let { failure ->
                                    if (
                                        failure.originalFailure != null ||
                                            completedResponse != null
                                    ) {
                                        failure.nativeQuiescenceProven = true
                                    }
                                }
                            } else if (nativeRecoveryFailure == null) {
                                nativeRecoveryFailure = NativeProcessQuiescenceException(
                                    downloadId = input.downloadItem.id,
                                    executionId = input.downloadItem.executionId,
                                    originalFailure = pendingFailure,
                                )
                                pendingFailure = null
                            }
                        }
                        // Keep the exact native owner through same-execution
                        // retries and the later hard-sub phase.  Releasing it
                        // here would let a queued E2 claim the Download while
                        // an execution-scoped post-processing process can
                        // still be registered.  The worker-level finalizer
                        // releases it only after the whole execution has
                        // converged and no native process remains.
                    }
                }
            }
            nativeRecoveryFailure?.let { failure ->
                if (failure.nativeQuiescenceProven) {
                    failure.originalFailure?.let { throw it }
                    return@coroutineScope requireNotNull(completedResponse)
                }
                throw failure
            }
            pendingFailure?.let { throw it }
            requireNotNull(completedResponse)
        }
    }

    private suspend fun executeYtdlpAttempts(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
        eventBus: EventBus,
        runtime: YtdlpPhaseRuntimeState,
        initialAttempt: YtdlpAttempt,
        progressCallback: (Float, Long, String) -> Unit,
    ): YtdlpAttemptsResult {
        val routeAttempts = YoutubeMediaAttemptSet()
        routeAttempts.markAttempted(initialAttempt.mediaAccessProfile)
        var currentAttempt = initialAttempt
        val expectedHeight = StagedVideoQualityValidationPolicy.targetHeight(
            attemptTargetHeight = initialAttempt.qualityTargetHeight,
            configuredFallbackHeight = expectedVideoHeight(input.downloadItem),
            hasRawFormatOverride = YoutubeMediaAccessPolicy.containsRawFormatOverride(
                input.downloadItem.extraCommands,
            ),
        )
        val qualityMarker = HistoryRedownloadMarker.parse(input.downloadItem.playlistURL)

        while (true) {
            runtime.beginAttempt()
            try {
                val completedResponse = executeYtdlpAttempt(
                    input,
                    runtime,
                    currentAttempt,
                    progressCallback
                )
                if (runtime.currentAttemptTransferStarted) {
                    runtime.completedMediaTransfers += 1
                    check(routeAttempts.recordCompletedMediaTransfer()) {
                        "Completed media transfer budget exceeded"
                    }
                }

                when (val qualityOutcome = resolveCompletedYtdlpQuality(
                    input = input,
                    services = services,
                    runtime = runtime,
                    currentAttempt = currentAttempt,
                    routeAttempts = routeAttempts,
                    completedResponse = completedResponse,
                    expectedHeight = expectedHeight,
                    isVerifiedReplacement = qualityMarker?.isQualityReplacement == true,
                )) {
                    is CompletedYtdlpQualityOutcome.Accept -> return qualityOutcome.result
                    is CompletedYtdlpQualityOutcome.Reject -> {
                        resetYtdlpTempDirectory(
                            downloadItem = input.downloadItem,
                            rawTempDirectory = input.rawTempDirectory,
                            beforeRetry = true,
                        )
                        throw YtdlpQualityRejectedException(qualityOutcome.message)
                    }
                    is CompletedYtdlpQualityOutcome.Retry -> {
                        if (!routeAttempts.markAttempted(qualityOutcome.profile)) {
                            error("Media access route was already attempted")
                        }
                        val retryError = IOException(
                            "Completed media was below the verified quality target"
                        )
                        currentAttempt = prepareYtdlpRetry(
                            input = input,
                            services = services,
                            eventBus = eventBus,
                            runtime = runtime,
                            previousAttempt = currentAttempt,
                            plan = YtdlpRetryPlan.Route(
                                notice = if (qualityOutcome.profile.isPublic) {
                                    services.resources.getString(R.string.retry_clean_public_before_transfer)
                                } else {
                                    services.resources.getString(R.string.retry_quality_authenticated_selected)
                                },
                                mediaAccessProfile = qualityOutcome.profile,
                            ),
                            previousError = retryError,
                            errorLabel = "Completed quality validation",
                        )
                        continue
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (attemptError: Exception) {
                if (attemptError is YtdlpQualityRejectedException) throw attemptError
                if (attemptError is NativeProcessQuiescenceException) {
                    // The attempt finally has already run the exact native
                    // convergence barrier.  Preserve an attached extraction
                    // failure for the normal retry policy only after that
                    // barrier succeeds; bare native debt remains recovery-owned.
                    if (attemptError.nativeQuiescenceProven) {
                        attemptError.originalFailure?.let { throw it }
                    }
                    throw attemptError
                }
                if (attemptError is DownloadExecutionOwnershipLostException) {
                    // A stale attempt must not turn an E2-owned Active row
                    // into a retry plan.  Stop before probing, rebuilding a
                    // request, touching shared cache, or publishing another
                    // notification.
                    throw attemptError
                }
                val latestStatus = runCatching {
                    services.downloadDao.checkStatus(input.downloadItem.id)
                }.getOrNull()
                if (
                    isStopped ||
                    latestStatus == DownloadRepository.Status.Paused ||
                    latestStatus == DownloadRepository.Status.Cancelled
                ) {
                    throw CancellationException("Download was cancelled before media route transition")
                }
                val failureText = buildYtdlpRetryProbeText(
                    attemptError,
                    runtime.currentAttemptOutput.toList(),
                )
                val failureKind = YoutubeMediaAccessPolicy.classifyFailure(failureText)
                val cachedInfoRetry = shouldRetryWithoutCachedInfoJson(
                    failureText,
                    currentAttempt.command,
                ) && routeAttempts.markCachedInfoRetried(currentAttempt.mediaAccessProfile)

                var retryPlan: YtdlpRetryPlan.Attempt? = when {
                    cachedInfoRetry -> YtdlpRetryPlan.CachedInfo(
                        notice = services.resources.getString(R.string.retry_cached_info),
                        mediaAccessProfile = currentAttempt.mediaAccessProfile,
                    )
                    currentAttempt.mediaAccessProfile == YoutubeMediaAccessProfile.USER_PINNED -> null
                    failureKind == YoutubeMediaFailureKind.ACCOUNT_RESTRICTED &&
                        currentAttempt.mediaAccessProfile.isPublic &&
                        services.ytdlpUtil.hasYoutubeAuthenticationConfiguration() -> {
                        routeAttempts.authenticatedIfUntried()?.also(routeAttempts::markAttempted)?.let { profile ->
                            YtdlpRetryPlan.Route(
                                notice = services.resources.getString(R.string.retry_public_account_authenticated),
                                mediaAccessProfile = profile,
                            )
                        }
                    }
                    (failureKind == YoutubeMediaFailureKind.QUALITY_UNAVAILABLE ||
                        failureKind == YoutubeMediaFailureKind.GENERIC_FORBIDDEN) -> {
                        routeAttempts.nextCleanPublicAfter(currentAttempt.mediaAccessProfile)
                            ?.also(routeAttempts::markAttempted)
                            ?.let { profile ->
                                YtdlpRetryPlan.Route(
                                    notice = services.resources.getString(R.string.retry_clean_public_before_transfer),
                                    mediaAccessProfile = profile,
                                )
                            }
                    }
                    else -> null
                }

                if (retryPlan == null && YoutubeMediaAccessPolicy.shouldRunSelectionProbe(
                        profile = currentAttempt.mediaAccessProfile,
                        failureKind = failureKind,
                        transferStarted = runtime.currentAttemptTransferStarted,
                        qualityGuardApplied = currentAttempt.qualityGuardApplied,
                        targetHeight = expectedHeight,
                    )
                ) {
                    val guardedTargetHeight = expectedHeight
                        ?: error("Selection probe requires a quality target")
                    val publicHeight = runSelectionProbe(
                        input = input,
                        services = services,
                        runtime = runtime,
                        profile = currentAttempt.mediaAccessProfile,
                        routeAttempts = routeAttempts,
                    )
                    val authenticatedHeight = if (
                        services.ytdlpUtil.hasYoutubeAuthenticationConfiguration() &&
                        !routeAttempts.wasAttempted(YoutubeMediaAccessProfile.AUTHENTICATED)
                    ) {
                        runSelectionProbe(
                            input = input,
                            services = services,
                            runtime = runtime,
                            profile = YoutubeMediaAccessProfile.AUTHENTICATED,
                            routeAttempts = routeAttempts,
                        )
                    } else {
                        null
                    }

                    retryPlan = when {
                        authenticatedHeight != null && authenticatedHeight >= guardedTargetHeight -> {
                            routeAttempts.markAttempted(YoutubeMediaAccessProfile.AUTHENTICATED)
                            YtdlpRetryPlan.Route(
                                notice = services.resources.getString(R.string.retry_quality_authenticated_selected),
                                mediaAccessProfile = YoutubeMediaAccessProfile.AUTHENTICATED,
                            )
                        }
                        qualityMarker?.isQualityReplacement == true -> null
                        publicHeight != null && publicHeight > 0 -> {
                            YtdlpRetryPlan.Route(
                                notice = services.resources.getString(
                                    R.string.retry_quality_degraded_selected,
                                    publicHeight,
                                ),
                                mediaAccessProfile = currentAttempt.mediaAccessProfile,
                                applyQualityGuard = false,
                            )
                        }
                        else -> null
                    }
                }

                if (retryPlan == null) throw attemptError
                currentAttempt = prepareYtdlpRetry(
                    input = input,
                    services = services,
                    eventBus = eventBus,
                    runtime = runtime,
                    previousAttempt = currentAttempt,
                    plan = retryPlan,
                    previousError = attemptError,
                    errorLabel = "Previous selection error",
                )
            }
        }
    }

    private suspend fun prepareYtdlpRetry(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
        eventBus: EventBus,
        runtime: YtdlpPhaseRuntimeState,
        previousAttempt: YtdlpAttempt,
        plan: YtdlpRetryPlan.Attempt,
        previousError: Exception,
        errorLabel: String,
    ): YtdlpAttempt {
        ensureExecutionOwnedBeforeAttempt(input.downloadItem)
        if (plan is YtdlpRetryPlan.CachedInfo) {
            withOwnedExecutionSideEffect(input.downloadItem) {
                deleteLoadedAppInfoJson(previousAttempt.command)
            }
        }
        withOwnedExecutionSideEffect(input.downloadItem) {
            announceYtdlpRetry(input, services, eventBus, plan.notice, previousError)
        }
        resetYtdlpTempDirectory(
            downloadItem = input.downloadItem,
            rawTempDirectory = input.rawTempDirectory,
            beforeRetry = true,
        )
        return withOwnedExecutionSideEffect(input.downloadItem) {
            buildYtdlpAttempt(
                downloadItem = input.downloadItem,
                ytdlpUtil = services.ytdlpUtil,
                retryPlan = plan,
                onRequestBuilt = runtime::registerRequest,
                onCommandBuilt = { command -> runtime.effectiveCommand = command },
            )
        }.also { attempt ->
            withOwnedExecutionSideEffect(input.downloadItem) {
                appendYtdlpRetryLog(
                    services = services,
                    runtime = runtime,
                    attempt = attempt,
                    notice = plan.notice,
                    errorLabel = errorLabel,
                    error = previousError,
                )
            }
        }
    }

    private suspend fun resolveCompletedYtdlpQuality(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
        runtime: YtdlpPhaseRuntimeState,
        currentAttempt: YtdlpAttempt,
        routeAttempts: YoutubeMediaAttemptSet,
        completedResponse: YoutubeDLResponse,
        expectedHeight: Int?,
        isVerifiedReplacement: Boolean,
    ): CompletedYtdlpQualityOutcome {
        ensureExecutionOwnedBeforeAttempt(input.downloadItem)
        val stagedQuality = probeStagedVideoQuality(input)
            ?: return CompletedYtdlpQualityOutcome.Accept(YtdlpAttemptsResult(completedResponse))
        val isYoutubeVideo = input.downloadItem.type == DownloadType.video &&
            input.downloadItem.url.isYoutubeURL()
        val routeInput = YoutubeQualityRouteInput(
            completedProfile = currentAttempt.mediaAccessProfile,
            attempts = routeAttempts.snapshot(),
            expectedHeight = expectedHeight,
            actualHeight = stagedQuality.resolutionHeight,
            isYoutubeVideo = isYoutubeVideo,
            isVerifiedReplacement = isVerifiedReplacement,
            canBuildCleanPublicRequest = services.ytdlpUtil.canBuildCleanPublicRequest(input.downloadItem),
            hasAuthenticationConfiguration = services.ytdlpUtil.hasYoutubeAuthenticationConfiguration(),
            accountRestrictionEvidence = YoutubeMediaAccessPolicy.classifyFailure(
                completedResponse.out.orEmpty()
            ) == YoutubeMediaFailureKind.ACCOUNT_RESTRICTED
        )
        var route = if (isYoutubeVideo) {
            YoutubeMediaAccessPolicy.qualityRoute(routeInput)
        } else {
            when (DownloadQualityFallbackPolicy.decide(
                expectedHeight = expectedHeight,
                actualHeight = stagedQuality.resolutionHeight,
                isYoutubeVideo = false,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = isVerifiedReplacement,
            )) {
                DownloadQualityDecision.ACCEPT -> YoutubeQualityRouteOutcome.Accept
                DownloadQualityDecision.RETRY_PUBLIC ->
                    YoutubeQualityRouteOutcome.Retry(YoutubeMediaAccessProfile.PUBLIC_DEFAULT)
                DownloadQualityDecision.ACCEPT_WITH_DEGRADED_WARNING ->
                    YoutubeQualityRouteOutcome.AcceptDegraded
                DownloadQualityDecision.REJECT_REPLACEMENT ->
                    YoutubeQualityRouteOutcome.RejectReplacement
                DownloadQualityDecision.REJECT_INVALID_OUTPUT ->
                    YoutubeQualityRouteOutcome.RejectInvalid
            }
        }
        if (route is YoutubeQualityRouteOutcome.Probe) {
            val probeHeight = runSelectionProbe(
                input = input,
                services = services,
                runtime = runtime,
                profile = route.profile,
                routeAttempts = routeAttempts,
            )
            route = YoutubeMediaAccessPolicy.qualityRoute(
                routeInput.copy(
                    attempts = routeAttempts.snapshot(),
                    authenticatedProbeHeight = probeHeight
                )
            )
        }
        return when (route) {
            YoutubeQualityRouteOutcome.Accept ->
                CompletedYtdlpQualityOutcome.Accept(YtdlpAttemptsResult(completedResponse))
            YoutubeQualityRouteOutcome.AcceptDegraded ->
                CompletedYtdlpQualityOutcome.Accept(
                    YtdlpAttemptsResult(
                        response = completedResponse,
                        qualityWarning = VideoQualityMismatch(
                            expectedHeight = expectedHeight ?: 0,
                            actualHeight = stagedQuality.resolutionHeight,
                        )
                    )
                )
            YoutubeQualityRouteOutcome.RejectReplacement,
            YoutubeQualityRouteOutcome.RejectInvalid -> CompletedYtdlpQualityOutcome.Reject(
                "Requested format is not available: downloaded video quality did not pass validation " +
                    "(expected=${expectedHeight ?: 0}p, actual=${stagedQuality.resolutionHeight}p)"
            )
            is YoutubeQualityRouteOutcome.Retry -> CompletedYtdlpQualityOutcome.Retry(route.profile)
            is YoutubeQualityRouteOutcome.Probe -> error("Selection probe outcome was not resolved")
        }
    }

    private suspend fun runSelectionProbe(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
        runtime: YtdlpPhaseRuntimeState,
        profile: YoutubeMediaAccessProfile,
        routeAttempts: YoutubeMediaAttemptSet,
    ): Int? {
        ensureExecutionOwnedBeforeAttempt(input.downloadItem)
        if (!routeAttempts.markProbed(profile)) return null
        val request = withOwnedExecutionSideEffect(input.downloadItem) {
            services.ytdlpUtil.buildYoutubeDLRequest(
                downloadItem = input.downloadItem,
                mediaAccessProfile = profile,
                useCachedInfoJson = false,
                applyQualityGuard = false,
                selectionOnly = true,
            ).apply {
                addOption("--simulate")
                addOption("--skip-download")
                addOption("--check-formats")
                addOption("--no-write-info-json")
                addOption("--no-write-thumbnail")
                addOption("--no-write-subs")
                addOption("--no-write-auto-subs")
                addOption("--print", "ytdlnisx-selection-height:%(height|0)s")
            }
        }
        runtime.registerRequest(request)
        val command = services.ytdlpUtil.parseYTDLRequestString(request)
        val probeAttempt = YtdlpAttempt(
            request = request,
            command = command,
            diagnostics = services.ytdlpUtil.buildRequestDiagnostics(
                input.downloadItem,
                request,
                command,
                profile,
            ),
            mediaAccessProfile = profile,
            qualityGuardApplied = false,
            qualityTargetHeight = null,
        )
        val response = try {
            runtime.beginAttempt()
            executeYtdlpAttempt(input, runtime, probeAttempt) { _, _, line ->
                val redacted = SensitiveTextRedactor.redactOutput(line)
                runtime.recentOutput.addLast(redacted)
                runtime.currentAttemptOutput.addLast(redacted)
                while (runtime.recentOutput.size > FAILURE_YTDLP_TAIL_LINES) {
                    runtime.recentOutput.removeFirst()
                }
                while (runtime.currentAttemptOutput.size > FAILURE_YTDLP_TAIL_LINES) {
                    runtime.currentAttemptOutput.removeFirst()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ownershipLost: DownloadExecutionOwnershipLostException) {
            throw ownershipLost
        } catch (_: Exception) {
            null
        }
        return response?.out
            ?.lineSequence()
            ?.mapNotNull { line ->
                line.substringAfter("ytdlnisx-selection-height:", "")
                    .trim()
                    .toIntOrNull()
            }
            ?.maxOrNull()
    }

    private fun expectedVideoHeight(item: DownloadItem): Int? {
        if (item.type != DownloadType.video) return null
        return HistoryRedownloadMarker.parse(item.playlistURL)?.expectedMinimumHeight
            ?: VideoQualityPolicy.expectedDownloadHeight(item.format, item.allFormats)
    }

    private fun commandQualityTarget(command: String): Int? {
        return Regex("""height>=([0-9]{2,5})\]\[height<=([0-9]{2,5})""")
            .findAll(command)
            .mapNotNull { match ->
                val minimum = match.groupValues.getOrNull(1)?.toIntOrNull()
                val maximum = match.groupValues.getOrNull(2)?.toIntOrNull()
                minimum.takeIf { it != null && it == maximum }
            }
            .minOrNull()
    }

    private fun probeStagedVideoQuality(input: YtdlpPhaseInput): VideoMediaQuality? {
        if (input.downloadItem.type != DownloadType.video) {
            return null
        }
        val paths = input.rawTempDirectory.walkTopDown()
            .filter { it.isFile && it.length() > 0L }
            .sortedByDescending { it.length() }
            .map { it.absolutePath }
            .toList()
        if (paths.isEmpty()) return null
        return HistoryVideoQualityProbe.probe(context, paths)
    }

    private fun commandHasYoutubeAuthentication(command: String): Boolean {
        return commandHasYtdlpOption(command, "--cookies") ||
            command.contains("po_token=") ||
            command.contains("data_sync_id=") ||
            command.contains("visitor_data=")
    }

    private suspend fun <T> withOwnedExecutionSideEffect(
        downloadItem: DownloadItem,
        sideEffect: suspend () -> T,
    ): T = withDownloadWorkerExecutionSideEffectLease(
        downloadId = downloadItem.id,
        executionId = downloadItem.executionId,
    ) {
        withDownloadWorkerExecutionLock {
            assertExecutionOwnedBeforeAttemptLocked(downloadItem)
        }
        sideEffect()
    }

    private suspend fun <T> withOwnedExecutionLease(
        downloadItem: DownloadItem,
        sideEffect: suspend () -> T,
    ): T = withDownloadWorkerExecutionSideEffectLease(
        downloadId = downloadItem.id,
        executionId = downloadItem.executionId,
    ) {
        withDownloadWorkerExecutionLock {
            assertExecutionOwnedBeforeAttemptLocked(downloadItem)
        }
        sideEffect()
    }

    private suspend fun assertExecutionOwnedBeforeAttemptLocked(downloadItem: DownloadItem) {
        if (
            DBManager.getInstance(context).lowQualityRedownloadDao
                .hasCancellationRequestedByDownload(downloadItem.id)
        ) {
            throw CancellationException(
                "Low-quality cancellation was committed before download side effect " +
                    "id=${downloadItem.id}"
            )
        }
        val current = DBManager.getInstance(context).downloadDao
            .getNullableDownloadById(downloadItem.id)
        if (
            current == null ||
                current.executionId != downloadItem.executionId ||
                current.status !in setOf(
                    DownloadRepository.Status.Active.name,
                    DownloadRepository.Status.PostProcessing.name,
                ) ||
                !DownloadWorkerExecutionOwners.isOwnedBy(
                    downloadItem.id,
                    downloadItem.executionId,
                )
        ) {
            throw DownloadExecutionOwnershipLostException(
                downloadId = downloadItem.id,
                expectedExecutionId = downloadItem.executionId,
                actualExecutionId = current?.executionId,
            )
        }
    }

    private suspend fun ensureExecutionOwnedBeforeAttempt(downloadItem: DownloadItem) {
        withDownloadWorkerExecutionLock {
            val current = DBManager.getInstance(context).downloadDao
                .getNullableDownloadById(downloadItem.id)
            if (
                this@DownloadWorker.isStopped ||
                    DownloadCancellationRegistry.belongsTo(
                        downloadItem.id,
                        downloadItem.executionId,
                    ) ||
                    current?.status in setOf(
                        DownloadRepository.Status.Paused.name,
                        DownloadRepository.Status.Cancelled.name,
                    ) ||
                    DBManager.getInstance(context).lowQualityRedownloadDao
                        .hasCancellationRequestedByDownload(downloadItem.id)
            ) {
                throw CancellationException(
                    "Download execution was cancelled before attempt side effects " +
                        "id=${downloadItem.id}"
                )
            }
            if (
                current == null ||
                    current.executionId != downloadItem.executionId ||
                    current.status !in setOf(
                        DownloadRepository.Status.Active.name,
                        DownloadRepository.Status.PostProcessing.name,
                    ) ||
                    !DownloadWorkerExecutionOwners.isOwnedBy(
                        downloadItem.id,
                        downloadItem.executionId,
                    )
            ) {
                throw DownloadExecutionOwnershipLostException(
                    downloadId = downloadItem.id,
                    expectedExecutionId = downloadItem.executionId,
                    actualExecutionId = current?.executionId,
                )
            }
        }
    }

    private suspend fun resetYtdlpTempDirectory(
        downloadItem: DownloadItem,
        rawTempDirectory: File,
        beforeRetry: Boolean,
    ): File = withOwnedExecutionSideEffect(downloadItem) {
        resetYtdlpTempDirectoryUnsafe(
            rawTempDirectory = rawTempDirectory,
            downloadId = downloadItem.id,
            beforeRetry = beforeRetry,
        )
    }

    private fun resetYtdlpTempDirectoryUnsafe(
        rawTempDirectory: File,
        downloadId: Long,
        beforeRetry: Boolean,
    ): File {
        val cacheRoot = File(FileUtil.getCachePath(context)).canonicalFile
        val tempDirectory = rawTempDirectory.canonicalFile
        if (tempDirectory.parentFile != cacheRoot || tempDirectory.name != downloadId.toString()) {
            throw IOException("Unsafe temporary download directory: ${tempDirectory.absolutePath}")
        }
        val cleanFailure = if (beforeRetry) {
            "Failed to clean temporary download directory before retry"
        } else {
            "Failed to clean temporary download directory"
        }
        val createFailure = if (beforeRetry) {
            "Failed to recreate temporary download directory before retry"
        } else {
            "Failed to create temporary download directory"
        }
        if (tempDirectory.exists() && !tempDirectory.deleteRecursively()) {
            throw IOException("$cleanFailure: ${tempDirectory.absolutePath}")
        }
        if (!tempDirectory.mkdirs() && !tempDirectory.isDirectory) {
            throw IOException("$createFailure: ${tempDirectory.absolutePath}")
        }
        return tempDirectory
    }

    private suspend fun appendYtdlpRetryLog(
        services: YtdlpPhaseServices,
        runtime: YtdlpPhaseRuntimeState,
        attempt: YtdlpAttempt,
        notice: String,
        errorLabel: String,
        error: Exception,
    ) {
        val retryDetails = YtdlpRetryLog.format(
            notice = notice,
            errorLabel = errorLabel,
            errorMessage = error.message.orEmpty(),
            command = attempt.command,
            diagnostics = attempt.diagnostics,
        )
        runtime.retryLogDetails = YtdlpRetryLog.append(runtime.retryLogDetails, retryDetails)
        if (runtime.loggingEnabled) {
            services.logRepository.update(retryDetails, runtime.logId ?: 0L)
        }
    }

    private fun announceYtdlpRetry(
        input: YtdlpPhaseInput,
        services: YtdlpPhaseServices,
        eventBus: EventBus,
        notice: String,
        error: Exception,
    ) {
        val item = input.downloadItem
        Log.w(TAG, "$notice id=${item.id}", error)
        eventBus.post(WorkerProgress(0, notice, item.id, item.logID))
        services.notificationUtil.updateDownloadNotification(
            item.id.toInt(),
            notice,
            0,
            0,
            input.notificationTitle,
            NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
            getHardSubStatusText(services.resources),
            item.executionId,
        )
    }



    companion object {
        val runningYTDLInstances: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        const val TAG = "DownloadWorker"
        internal val downloadWorkerMutex = Mutex()
        private val hardSubH264Containers = setOf(
            "mp4", "m4v", "mov", "mkv", "avi", "flv",
            "ts", "m2ts", "mts", "m2t", "3gp", "3g2"
        )

        private val hardSubTargetIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val hardSubProcessedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val hardSubDisabledFfmpegSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val hardSubFilterSupportCache: MutableMap<String, Set<String>> = ConcurrentHashMap()
        private val hardSubPostProcessMutex = Mutex()
        /**
         * Every hard-sub/post-processing Process is retained here until its
         * own OS termination has been acknowledged.  The execution token is
         * part of the key; a stale cleanup can therefore address only its
         * exact process set and cannot clear a newer execution's registry.
         */
        private data class NativePostProcessingHandle(
            val process: Process,
            val prepared: YtdlpNativeProcessBarrier.PreparedProcess?,
        )

        private val runningNativePostProcessingProcesses:
            MutableMap<FfmpegProcessKey, MutableSet<NativePostProcessingHandle>> =
            ConcurrentHashMap()
        private val activeNativeProcessKey = ThreadLocal<FfmpegProcessKey?>()
        private val loadInfoJsonOptionRegex = Regex("""--load-info-json\s+(?:"([^"]+)"|'([^']+)'|(\S+))""")
        private const val FAILURE_YTDLP_TAIL_LINES = 160
        private const val FAILURE_FILE_LIST_LIMIT = 80
        private const val FAILURE_STACK_TRACE_LIMIT = 6000

        private fun cancelYtdlpProcess(downloadId: Long, executionId: String): Boolean {
            val processId = YtdlpProcessIdentity.download(downloadId, executionId)
            // DownloadWorker uses YoutubeDLCompat.execute for the exact
            // execution-scoped process.  Its registry retains the Process
            // entry until waitFor/termination acknowledgement, so the return
            // value is a real quiescence proof rather than a destroy request.
            return YoutubeDLCompat.destroyProcessById(processId)
        }

        /**
         * True when any exact native process registry still identifies this
         * Download execution as a writer.  The execution-owner map is only
         * one of the barriers: it is deliberately not treated as a complete
         * liveness proof because a Process can outlive a coroutine owner.
         */
        internal fun hasRegisteredNativeProcess(
            downloadId: Long,
            expectedExecutionId: String,
        ): Boolean {
            if (expectedExecutionId.isBlank()) {
                // A legacy row has no execution token with which to claim a
                // native writer.  Any visible same-Download carrier is
                // therefore a fail-closed recovery barrier, never proof of
                // absence.
                return hasAnyRegisteredNativeProcess(downloadId)
            }
            val processId = YtdlpProcessIdentity.download(downloadId, expectedExecutionId)
            return DownloadWorkerProcessOwners.isOwnedBy(downloadId, expectedExecutionId) ||
                YoutubeDLCompat.hasProcessById(processId) ||
                YtdlpNativeProcessBarrier.hasUnresolvedDownloadExecution(
                    downloadId,
                    expectedExecutionId,
                ) ||
                runningNativePostProcessingProcesses[
                    FfmpegProcessKey(downloadId, expectedExecutionId)
                ]?.isNotEmpty() == true
        }

        /** Registry-only form used when deciding whether the owner token can
         * be released after the complete worker execution has converged. */
        internal fun hasNativeProcessRegistryEntry(
            downloadId: Long,
            expectedExecutionId: String,
        ): Boolean {
            if (expectedExecutionId.isBlank()) {
                return YoutubeDLCompat.hasAnyDownloadProcess(downloadId) ||
                    YtdlpNativeProcessBarrier.hasUnresolvedDownloadExecution(downloadId) ||
                    runningNativePostProcessingProcesses.keys.any { key ->
                        key.downloadItemId == downloadId &&
                            runningNativePostProcessingProcesses[key]?.isNotEmpty() == true
                    }
            }
            val processId = YtdlpProcessIdentity.download(downloadId, expectedExecutionId)
            return YoutubeDLCompat.hasProcessById(processId) ||
                YtdlpNativeProcessBarrier.hasUnresolvedDownloadExecution(
                    downloadId,
                    expectedExecutionId,
                ) ||
                runningNativePostProcessingProcesses[
                    FfmpegProcessKey(downloadId, expectedExecutionId)
                ]?.isNotEmpty() == true
        }

        /**
         * Used before publishing a new execution token.  A row is not
         * reusable while a process for any older execution is still visible
         * in any same-process registry, even if its worker owner disappeared.
         */
        internal fun hasAnyRegisteredNativeProcess(downloadId: Long): Boolean {
            return DownloadWorkerProcessOwners.ownerOf(downloadId) != null ||
                YoutubeDLCompat.hasAnyDownloadProcess(downloadId) ||
                runningNativePostProcessingProcesses.keys.any { key ->
                    key.downloadItemId == downloadId &&
                        runningNativePostProcessingProcesses[key]?.isNotEmpty() == true
                }
        }

        /**
         * A recovery snapshot must not mutate E1 when another exact native
         * execution is still registered for the same numeric Download ID.
         */
        internal fun hasConflictingNativeProcess(
            downloadId: Long,
            expectedExecutionId: String,
        ): Boolean {
            if (expectedExecutionId.isBlank()) {
                return hasAnyRegisteredNativeProcess(downloadId)
            }
            val processOwner = DownloadWorkerProcessOwners.ownerOf(downloadId)
            if (processOwner != null && processOwner != expectedExecutionId) return true

            val expectedProcessId = YtdlpProcessIdentity.download(downloadId, expectedExecutionId)
            if (YoutubeDLCompat.hasOtherDownloadProcess(downloadId, expectedProcessId)) return true

            return runningNativePostProcessingProcesses.keys.any { key ->
                key.downloadItemId == downloadId &&
                    key.executionId != expectedExecutionId &&
                    runningNativePostProcessingProcesses[key]?.isNotEmpty() == true
            }
        }

        private fun registerNativePostProcessingProcess(
            key: FfmpegProcessKey,
            process: Process,
            prepared: YtdlpNativeProcessBarrier.PreparedProcess? = null,
        ) {
            runningNativePostProcessingProcesses
                .computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
                .add(NativePostProcessingHandle(process, prepared))
        }

        private fun removeNativePostProcessingProcess(
            key: FfmpegProcessKey,
            process: Process,
        ) {
            val processSet = runningNativePostProcessingProcesses[key] ?: return
            processSet.removeIf { it.process === process }
            if (processSet.isEmpty()) {
                runningNativePostProcessingProcesses.remove(key, processSet)
            }
        }

        private fun nativeProcessKeyForCurrentThread(): FfmpegProcessKey? =
            activeNativeProcessKey.get()

        /**
         * A few hard-sub probes and merge helpers do not carry the progress
         * object through their call graph.  Keep their process registration
         * execution-scoped for the duration of the production burn-in call.
         */
        private fun <T> withNativeProcessScope(
            key: FfmpegProcessKey?,
            block: () -> T,
        ): T {
            val previous = activeNativeProcessKey.get()
            if (key == null) return block()
            activeNativeProcessKey.set(key)
            return try {
                block()
            } finally {
                if (previous == null) {
                    activeNativeProcessKey.remove()
                } else {
                    activeNativeProcessKey.set(previous)
                }
            }
        }

        internal fun registerPostProcessingProcessForTesting(
            downloadId: Long,
            executionId: String,
            process: Process,
        ) {
            registerNativePostProcessingProcess(
                FfmpegProcessKey(downloadId, executionId),
                process,
            )
        }

        internal fun clearPostProcessingProcessForTesting(
            downloadId: Long,
            executionId: String,
            process: Process,
        ) {
            removeNativePostProcessingProcess(
                FfmpegProcessKey(downloadId, executionId),
                process,
            )
        }

        /**
         * Called only while the caller holds the exact per-Download side-effect
         * lease and has re-read the matching execution row.  A process owner
         * for a different execution is never addressed by numeric Download ID.
         */
        internal fun cancelProcessesForExecution(
            downloadId: Long,
            expectedExecutionId: String,
        ): Boolean {
            if (expectedExecutionId.isBlank()) {
                // Legacy rows have no execution identity with which an
                // unknown native process can be safely addressed.  Treat a
                // visible process registry entry as a fail-closed barrier;
                // never cancel by numeric Download ID alone.
                return !hasAnyRegisteredNativeProcess(downloadId)
            }
            if (!canCancelExecutionProcess(downloadId, expectedExecutionId)) return false
            val ytdlpQuiesced = cancelYtdlpProcess(downloadId, expectedExecutionId)
            val postProcessingQuiesced = cancelPostProcessingById(
                downloadId,
                expectedExecutionId,
            )
            if (!ytdlpQuiesced || !postProcessingQuiesced) {
                throw NativeProcessQuiescenceException(downloadId, expectedExecutionId)
            }
            check(
                YtdlpNativeProcessBarrier.recoverDownloadExecution(
                    downloadId = downloadId,
                    executionId = expectedExecutionId,
                )
            ) {
                "Durable native marker set remained unresolved while cancelling download $downloadId"
            }
            // A newer execution can reuse the resource only after every
            // process registered by this exact execution has terminated.
            DownloadWorkerProcessOwners.release(downloadId, expectedExecutionId)
            return true
        }

        /**
         * Starts a new attempt while holding the exact current Download
         * ownership gate.  Only the exact current execution's prior process
         * may be quiesced for a retry; a process belonging to another token
         * is a fail-closed reuse barrier.
         */
        internal fun prepareProcessForExecution(
            downloadId: Long,
            expectedExecutionId: String,
        ) {
            if (expectedExecutionId.isBlank()) return
            check(!hasConflictingNativeProcess(downloadId, expectedExecutionId)) {
                "Native process for another execution remains registered before retry for download " +
                    downloadId
            }
            check(cancelYtdlpProcess(downloadId, expectedExecutionId)) {
                "Native yt-dlp process did not quiesce before retry for download $downloadId"
            }
            check(cancelPostProcessingById(downloadId, expectedExecutionId)) {
                "Native post-processing did not quiesce before retry for download $downloadId"
            }
        }

        fun cancelPostProcessingById(downloadId: Long, expectedExecutionId: String): Boolean {
            val key = FfmpegProcessKey(downloadId, expectedExecutionId)
            var quiesced = true
            runningNativePostProcessingProcesses[key]?.toList().orEmpty().forEach { handle ->
                val localProcessQuiesced = ProcessQuiescence.requestTermination(handle.process)
                val durableQuiesced = handle.prepared?.let {
                    YtdlpNativeProcessBarrier.recoverDetailed(
                        it.marker,
                        it.generationToken,
                    )?.isProvenQuiescent
                } ?: true
                if (localProcessQuiesced && durableQuiesced != false) {
                    removeNativePostProcessingProcess(key, handle.process)
                } else {
                    quiesced = false
                }
            }
            if (quiesced && !YtdlpNativeProcessBarrier.recoverDownloadExecution(
                    downloadId,
                    expectedExecutionId,
                )
            ) {
                quiesced = false
            }
            return quiesced
        }
    }

    private fun commandHasYtdlpOption(commandString: String, option: String): Boolean {
        return Regex("""(?:^|\s)${Regex.escape(option)}(?:\s|$)""").containsMatchIn(commandString)
    }

    private fun buildFailureDiagnostics(
        error: Exception,
        item: DownloadItem,
        requestCommand: String,
        tempDir: File,
        recentOutput: List<String>
    ): String {
        val stackTrace = ByteArrayOutputStream().use { out ->
            PrintStream(out).use { ps -> error.printStackTrace(ps) }
            redactFailureDiagnosticValue(out.toString(Charsets.UTF_8.name())).take(FAILURE_STACK_TRACE_LIMIT)
        }
        val outputTail = recentOutput
            .takeLast(FAILURE_YTDLP_TAIL_LINES)
            .joinToString("\n")
            .let { redactFailureDiagnosticValue(it) }
            .ifBlank { "<none>" }
        val errorMessage = redactFailureDiagnosticValue(error.message.orEmpty()).takeLast(4000)

        return buildString {
            appendLine()
            appendLine("Failure Diagnostics:")
            appendLine("exceptionType: ${error.javaClass.name}")
            appendLine("exceptionMessage: $errorMessage")
            appendLine("downloadId: ${item.id}")
            appendLine("type: ${item.type}")
            appendLine("formatId: ${item.format.format_id}")
            appendLine("formatContainer: ${item.format.container}")
            appendLine("formatVcodec: ${item.format.vcodec}")
            appendLine("formatAcodec: ${item.format.acodec}")
            appendLine("selectedContainer: ${item.container}")
            appendLine("videoRecode: ${item.videoPreferences.recodeVideo}")
            appendLine("videoCompatibilityMode: ${item.videoPreferences.compatibilityMode}")
            appendLine("videoEmbedSubs: ${item.videoPreferences.embedSubs}")
            appendLine("videoWriteSubs: ${item.videoPreferences.writeSubs}")
            appendLine("videoWriteAutoSubs: ${item.videoPreferences.writeAutoSubs}")
            appendLine("hasLoadInfoJson: ${commandHasYtdlpOption(requestCommand, "--load-info-json")}")
            appendLine("mergeOutputFormat: ${requestCommand.firstYtdlpOptionValue("--merge-output-format") ?: "<none>"}")
            appendLine("formatSelector: ${requestCommand.firstYtdlpOptionValue("-f") ?: "<none>"}")
            appendLine("sortSelector: ${requestCommand.firstYtdlpOptionValue("-S") ?: "<none>"}")
            appendLine("tempDir: ${tempDir.absolutePath}")
            appendLine(buildTempDirectoryDiagnostics(tempDir))
            appendLine("ytDlpRecentOutputLast${FAILURE_YTDLP_TAIL_LINES}:")
            appendLine(outputTail)
            appendLine("stackTrace:")
            appendLine(stackTrace)
        }
    }

    private fun redactFailureDiagnosticValue(value: String): String {
        return SensitiveTextRedactor.redactOutput(value)
            .replace(Regex("""po_token=[^;"\s]+"""), "po_token=<redacted>")
            .replace(Regex("""pot=[^&;"\s]+"""), "pot=<redacted>")
            .replace(Regex("""visitor_data=[^;"\s]+"""), "visitor_data=<redacted>")
            .replace(Regex("""data_sync_id=[^;"\s]+"""), "data_sync_id=<redacted>")
            .replace(Regex("""(?i)(authorization|cookie):\s*[^\r\n]+"""), "$1: <redacted>")
            .replace(Regex("""https?://[^\s"'<>]*googlevideo\.com/[^\s"'<>]+"""), "<googlevideo-url-redacted>")
    }

    private fun buildTempDirectoryDiagnostics(tempDir: File): String {
        if (!tempDir.exists()) return "tempFiles: <temp-dir-missing>"
        if (!tempDir.isDirectory) return "tempFiles: <not-a-directory size=${tempDir.length()}>"

        val files = runCatching {
            tempDir.walkTopDown()
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .take(FAILURE_FILE_LIST_LIMIT)
                .toList()
        }.getOrElse {
            return "tempFiles: <scan-failed ${it.javaClass.simpleName}:${it.message.orEmpty().take(200)}>"
        }

        if (files.isEmpty()) return "tempFiles: <empty>"
        return buildString {
            appendLine("tempFiles:")
            files.forEach { file ->
                val relativePath = runCatching { file.relativeTo(tempDir).path }.getOrDefault(file.name)
                appendLine(" - ${relativePath} size=${file.length()} modified=${file.lastModified()} ext=${file.extension.ifBlank { "<none>" }}")
            }
            val totalFiles = runCatching { tempDir.walkTopDown().count { it.isFile } }.getOrDefault(files.size)
            if (totalFiles > files.size) {
                appendLine(" - <${totalFiles - files.size} more files omitted>")
            }
        }.trimEnd()
    }

    private fun String.firstYtdlpOptionValue(option: String): String? {
        val pattern = Regex("""(?:^|\s)${Regex.escape(option)}\s+(?:"([^"]+)"|'([^']+)'|(\S+))""")
        return pattern.find(this)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
    }

    private fun isHardSubRedownload(item: DownloadItem): Boolean {
        return item.type == com.ireum.ytdl.database.enums.DownloadType.video &&
            item.videoPreferences.embedSubs &&
            HistoryRedownloadMarker.parse(item.playlistURL) != null
    }

    private fun isDurablyCommittedHistoryReplacement(
        dbManager: DBManager,
        downloadItem: DownloadItem,
    ): Boolean {
        val marker = HistoryRedownloadMarker.parse(downloadItem.playlistURL) ?: return false
        return dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId == downloadItem.id
    }

    private fun buildYtdlpRetryProbeText(error: Exception, recentOutput: List<String>): String {
        return buildString {
            appendLine(error.message.orEmpty())
            recentOutput.takeLast(FAILURE_YTDLP_TAIL_LINES).forEach(::appendLine)
        }
    }

    private fun shouldRetryWithoutCachedInfoJson(retryProbeText: String, commandString: String): Boolean {
        if (!commandString.contains("--load-info-json")) return false
        return retryProbeText.contains("HTTP Error 403", ignoreCase = true) ||
            (
                retryProbeText.contains("Forbidden", ignoreCase = true) &&
                    retryProbeText.contains("unable to download video data", ignoreCase = true)
            )
    }

    private fun shouldRetryYoutube403WithoutAuthentication(
        retryProbeText: String,
        commandString: String,
        item: DownloadItem
    ): Boolean {
        if (
            item.type != DownloadType.video ||
            !item.url.isYoutubeURL() ||
            item.url.getIDFromYoutubeURL() == null
        ) return false
        val hasAuthentication = commandHasYtdlpOption(commandString, "--cookies") ||
            commandString.contains("po_token=") ||
            commandString.contains("data_sync_id=") ||
            commandString.contains("visitor_data=")
        if (!hasAuthentication) return false
        return retryProbeText.contains("HTTP Error 403", ignoreCase = true) ||
            retryProbeText.contains("403: Forbidden", ignoreCase = true) ||
            (
                retryProbeText.contains("Forbidden", ignoreCase = true) &&
                    retryProbeText.contains("unable to download video data", ignoreCase = true)
            )
    }

    private fun deleteLoadedAppInfoJson(commandString: String) {
        val infoJsonRoot = runCatching {
            File(FileUtil.getCachePath(context), "infojsons").canonicalFile
        }.getOrNull() ?: return

        loadInfoJsonOptionRegex.findAll(commandString)
            .mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            }
            .forEach { path ->
                runCatching {
                    val file = File(path).canonicalFile
                    if (
                        file.parentFile == infoJsonRoot &&
                        file.name.endsWith(".info.json") &&
                        file.exists()
                    ) {
                        if (file.delete()) {
                            Log.i(TAG, "Deleted stale cached info JSON: ${file.name}")
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to delete stale cached info JSON from retry command", it)
                }
            }
    }

    private fun resolveHardSubSkipReason(output: String): String? {
        val normalized = output.lowercase(Locale.US)
        return when {
            normalized.contains("there are no subtitles for the requested languages") -> "Subtitle burn-in skipped: requested subtitles are unavailable"
            normalized.contains("no subtitles for the requested languages") -> "Subtitle burn-in skipped: requested subtitles are unavailable"
            else -> null
        }
    }

    private fun pickLocalThumbnailPath(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        val thumbExts = setOf("jpg", "jpeg", "png", "webp", "avif")
        return paths.firstOrNull { path ->
            val file = File(path)
            file.exists() && thumbExts.contains(file.extension.lowercase(Locale.US))
        }
    }

    private fun resetHardSubProgress() {
        hardSubTargetIds.clear()
        hardSubProcessedIds.clear()
        hardSubDisabledFfmpegSources.clear()
        hardSubFilterSupportCache.clear()
    }

    private fun registerHardSubTarget(downloadId: Long) {
        hardSubTargetIds.add(downloadId)
    }

    private fun markHardSubProcessed(downloadId: Long) {
        if (hardSubTargetIds.contains(downloadId)) {
            hardSubProcessedIds.add(downloadId)
        }
    }

    private fun getHardSubStatusText(resources: Resources): String? {
        val total = hardSubTargetIds.size
        if (total <= 0) return null
        val done = hardSubProcessedIds.size.coerceAtMost(total)
        return resources.getString(R.string.hard_sub_progress, done, total)
    }

    private fun updateHardSubWorkerNotification(notificationUtil: NotificationUtil) {
        val status = getHardSubStatusText(context.resources)
        if (status.isNullOrBlank()) {
            notificationUtil.notify(1000000000, notificationUtil.createDefaultWorkerNotification())
        } else {
            notificationUtil.notify(1000000000, notificationUtil.createHardSubWorkerNotification(status))
        }
    }

    private fun updateHardSubWorkerNotificationSafely(notificationUtil: NotificationUtil) {
        try {
            updateHardSubWorkerNotification(notificationUtil)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notificationError: Exception) {
            Log.w(TAG, "Failed to update hard-sub worker notification", notificationError)
        }
    }

    private suspend fun cancelDownloadNotificationSafely(
        notificationUtil: NotificationUtil,
        downloadItem: DownloadItem,
    ) {
        try {
            withOwnedExecutionSideEffect(downloadItem) {
                notificationUtil.cancelRunningDownloadNotification(downloadItem.id.toInt())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notificationError: Exception) {
            Log.w(
                TAG,
                "Failed to cancel download notification id=${downloadItem.id}",
                notificationError
            )
        }
    }

    private fun validateSubtitleFilesForUse(
        subtitleFiles: List<File>,
        subtitleRequest: SubtitleSelection.Request,
        downloadItemId: Long?,
        downloadLogId: Long?,
        fatal: Boolean = true
    ): List<File> {
        if (subtitleFiles.isEmpty()) {
            val message = "Subtitle validation found no subtitle files for ${subtitleRequest.subLanguages}"
            if (fatal) {
                val error = "Subtitle validation failed: no subtitle files found for ${subtitleRequest.subLanguages}"
                Log.e(TAG, "HardSub $error")
                EventBus.getDefault().post(WorkerProgress(100, error, downloadItemId ?: -1L, downloadLogId))
                throw IOException(error)
            }
            Log.w(TAG, message)
            EventBus.getDefault().post(WorkerProgress(100, message, downloadItemId ?: -1L, downloadLogId))
            return emptyList()
        }

        val validated = subtitleFiles.filter { file ->
            val result = SubtitleFileValidator.validate(file, subtitleRequest.liveChatOnly)
            val status = if (result.valid) "ok" else "failed"
            val message = "Subtitle validation $status file=${file.name} size=${file.length()} cues=${result.cueCount} reason=${result.reason} selected=${subtitleRequest.subLanguages} liveChat=${subtitleRequest.liveChatOnly}"
            if (result.valid) {
                Log.i(TAG, message)
            } else {
                Log.w(TAG, "$message zeroByte=${file.length() == 0L}")
            }
            result.valid
        }

        if (validated.isEmpty()) {
            val failed = subtitleFiles.joinToString { "${it.name}:${it.length()}B" }
            if (fatal) {
                val error = "Subtitle validation failed: no usable subtitle body for ${subtitleRequest.subLanguages}; files=$failed"
                Log.e(TAG, "HardSub $error")
                EventBus.getDefault().post(WorkerProgress(100, error, downloadItemId ?: -1L, downloadLogId))
                throw IOException(error)
            }
            val warning = "Subtitle validation found no usable subtitle body for ${subtitleRequest.subLanguages}; files=$failed"
            Log.w(TAG, warning)
            EventBus.getDefault().post(WorkerProgress(100, warning, downloadItemId ?: -1L, downloadLogId))
            return emptyList()
        }

        return validated
    }

    private fun validateSavedSubtitleSidecars(
        downloadItem: DownloadItem,
        finalPaths: List<String>,
        downloadLocation: String,
        downloadStartedAt: Long
    ) {
        val subtitleRequest = SubtitleSelection.normalize(downloadItem.videoPreferences.subsLanguages)
        val subtitleExts = setOf("srv3", "json3", "json", "ttml", "ass", "vtt", "srt")
        val fromPaths = finalPaths
            .map { File(it) }
            .filter { it.exists() && it.isFile && it.extension.lowercase(Locale.US) in subtitleExts }
        val fromDirectory = runCatching {
            File(downloadLocation)
                .walkTopDown()
                .filter { it.isFile && it.extension.lowercase(Locale.US) in subtitleExts }
                .filter { it.lastModified() >= downloadStartedAt }
                .toList()
        }.getOrDefault(emptyList())

        val selected = (fromPaths + fromDirectory)
            .distinctBy { it.absolutePath }
            .filter { SubtitleSelection.isSelectedSubtitleFile(it, subtitleRequest) }

        Log.i(
            TAG,
            "Subtitle sidecar scan id=${downloadItem.id} selected=${subtitleRequest.subLanguages} liveChat=${subtitleRequest.liveChatOnly} candidates=${selected.size}"
        )
        validateSubtitleFilesForUse(
            selected,
            subtitleRequest,
            downloadItem.id,
            downloadItem.logID,
            fatal = false
        )
    }

    private fun burnSubtitlesInPlace(
        paths: List<String>,
        removeSubsAfterBurnIn: Boolean,
        downloadItemId: Long? = null,
        downloadExecutionId: String? = null,
        downloadLogId: Long? = null,
        selectedSubtitleLanguages: String = ""
    ): Boolean {
        val processKey = if (downloadItemId != null && !downloadExecutionId.isNullOrBlank()) {
            FfmpegProcessKey(downloadItemId, downloadExecutionId)
        } else {
            null
        }
        return withNativeProcessScope(processKey) {
            burnSubtitlesInPlaceScoped(
                paths = paths,
                removeSubsAfterBurnIn = removeSubsAfterBurnIn,
                downloadItemId = downloadItemId,
                downloadExecutionId = downloadExecutionId,
                downloadLogId = downloadLogId,
                selectedSubtitleLanguages = selectedSubtitleLanguages,
            )
        }
    }

    private fun burnSubtitlesInPlaceScoped(
        paths: List<String>,
        removeSubsAfterBurnIn: Boolean,
        downloadItemId: Long? = null,
        downloadExecutionId: String? = null,
        downloadLogId: Long? = null,
        selectedSubtitleLanguages: String = ""
    ): Boolean {
        val ffmpegRuntime = resolveFfmpegRuntime()
        val supportedFilters = probeSubtitleFilters(ffmpegRuntime)
        val dedicatedSrv3ConverterPath = resolveSrv3ConverterPath()
        val subtitleExts = listOf("srv3", "json3", "json", "ttml", "ass", "vtt", "srt")
        val subtitleRequest = SubtitleSelection.normalize(selectedSubtitleLanguages)
        val existingFiles = paths
            .map { File(it) }
            .filter { it.exists() && it.isFile }
        val siblingFiles = existingFiles
            .mapNotNull { it.parentFile }
            .distinctBy { it.absolutePath }
            .flatMap { it.listFiles().orEmpty().asList() }
        val subtitleFiles = (existingFiles + siblingFiles)
            .distinctBy { it.absolutePath }
            .filter { file -> subtitleExts.any { ext -> file.extension.equals(ext, ignoreCase = true) } }
            .filter { file -> SubtitleSelection.isSelectedSubtitleFile(file, subtitleRequest) }
            .let { validateSubtitleFilesForUse(it, subtitleRequest, downloadItemId, downloadLogId) }
        if (!removeSubsAfterBurnIn) {
            convertSubtitleFilesToSrt(subtitleFiles, ffmpegRuntime, dedicatedSrv3ConverterPath)
        }
        val canonicalSubtitle = createCanonicalHardSubSubtitle(subtitleFiles, subtitleExts)
        val subtitleCandidates = canonicalSubtitle?.let { listOf(it.file) } ?: subtitleFiles
        var mediaFiles = existingFiles
            .filterNot { file -> subtitleExts.any { ext -> file.extension.equals(ext, ignoreCase = true) } }
        if (mediaFiles.isEmpty() && subtitleCandidates.isNotEmpty()) {
            mediaFiles = findSiblingMediaForSubtitles(subtitleCandidates, subtitleExts)
            if (mediaFiles.isNotEmpty()) {
                Log.w(TAG, "HardSub media fallback from subtitle siblings used recovered=${mediaFiles.size}")
            }
        }
        mediaFiles = mergeSeparatedVideoAudioIfNeeded(mediaFiles, ffmpegRuntime)

        Log.i(
            TAG,
            "HardSub burn scan mediaFiles=${mediaFiles.size} converter=${if (dedicatedSrv3ConverterPath != null) "yttml" else "ffmpeg-only"}"
        )
        Log.i(
            TAG,
            "HardSub ffmpeg runtime exec=${ffmpegRuntime.executablePath} linker=${ffmpegRuntime.linkerPath ?: "<direct>"} source=${ffmpegRuntime.source} libs=${ffmpegRuntime.libraryPath ?: "<default>"}"
        )
        if (supportedFilters.isNotEmpty()) {
            Log.i(TAG, "HardSub ffmpeg subtitle filters=${supportedFilters.joinToString(",")}")
        } else {
            Log.w(TAG, "HardSub ffmpeg subtitle filter probe unavailable; using runtime fallback attempts")
        }
        if (mediaFiles.isEmpty()) {
            Log.w(TAG, "HardSub burn input paths=${paths.joinToString(limit = 5)}")
            Log.w(TAG, "HardSub no media files found for burn-in after path resolution")
            throw IOException("HardSub aborted: no media files found for burn-in")
        }
        var burnedMediaCount = 0
        val consumedSubtitles = linkedSetOf<String>()
        var unwritableOutputDir: String? = null
        mediaFiles.forEach { media ->
            if (!hasVideoStream(media, ffmpegRuntime)) {
                Log.w(TAG, "HardSub skip media=${media.name} reason=no-video-stream")
                return@forEach
            }
            val mediaHadAudioBeforeBurn = hasAudioStream(media, ffmpegRuntime)
            val mediaParent = media.parentFile
            if (mediaParent == null || !canCreateSiblingOutput(mediaParent)) {
                if (unwritableOutputDir == null) {
                    unwritableOutputDir = mediaParent?.absolutePath ?: "unknown"
                }
                Log.w(
                    TAG,
                    "HardSub skip media=${media.name} reason=output-directory-not-writable dir=${mediaParent?.absolutePath ?: "unknown"}"
                )
                return@forEach
            }
            val subtitle = prepareSubtitleForBurnIn(media, subtitleExts, subtitleCandidates, ffmpegRuntime, dedicatedSrv3ConverterPath, subtitleRequest)
            if (subtitle == null) {
                Log.w(TAG, "HardSub skip media=${media.name} reason=no-matching-subtitle")
                return@forEach
            }
            consumedSubtitles.add(subtitle.file.absolutePath)
            val progressTarget = if (downloadItemId != null && !downloadExecutionId.isNullOrBlank()) {
                FfmpegProgressTarget(
                    downloadItemId,
                    requireNotNull(downloadExecutionId),
                    downloadLogId,
                    media.name,
                )
            } else {
                null
            }
            val output = File(media.parentFile, "${media.nameWithoutExtension}.burnin.${media.extension}")
            val filterCandidates = buildFilterCandidatesForMedia(subtitle.isAss, supportedFilters)
            if (filterCandidates.isEmpty()) {
                throw IOException(
                    "ffmpeg runtime missing required subtitle filter (available=${supportedFilters.joinToString(",").ifBlank { "none" }})"
                )
            }
            var ffmpegResult: FfmpegExecResult? = null
            var usedFilter: String? = null
            for (filter in filterCandidates) {
                val filterArgs = buildSubtitleFilterArgs(filter, subtitle.file.absolutePath)
                var result: FfmpegExecResult = FfmpegExecResult(1, "ffmpeg burn-in returned no result")
                for (subtitleArg in filterArgs) {
                    Log.i(
                        TAG,
                        "HardSub burn file media=${media.name} subtitle=${subtitle.file.name} filter=$filter temp=${subtitle.isTemporary}"
                    )
                    result = executeFfmpegWithAutoPatch(
                        ffmpegRuntime,
                        listOf(
                            "-y",
                            "-i",
                            media.absolutePath,
                            "-vf",
                            subtitleArg
                        ) + hardSubVideoEncodeArgs(media) + listOf(
                            "-c:a",
                            "copy",
                            output.absolutePath
                        ),
                        progressTarget
                    )
                    if (result.exitCode != 0 && media.extension.equals("webm", ignoreCase = true) && shouldTryWebmFallback(result.output)) {
                        val skipLibvpx = result.output.contains("ABI version mismatch", ignoreCase = true)
                        val encoderFallbacks = if (skipLibvpx) {
                            listOf("vp9", "vp8")
                        } else {
                            listOf("libvpx-vp9", "libvpx", "vp9", "vp8")
                        }
                        for (encoder in encoderFallbacks) {
                            Log.w(TAG, "HardSub webm encoder fallback media=${media.name} encoder=$encoder")
                            result = executeFfmpegWithAutoPatch(
                                ffmpegRuntime,
                                listOf(
                                    "-y",
                                    "-i",
                                    media.absolutePath,
                                    "-vf",
                                    subtitleArg,
                                    "-c:v",
                                    encoder,
                                    "-c:a",
                                    "copy",
                                    output.absolutePath
                                ),
                                progressTarget
                            )
                            if (result.exitCode == 0) break
                        }
                        if (result.exitCode != 0) {
                            // Some Android ffmpeg builds can burn subtitles but cannot encode WebM video.
                            // Fallback to Matroska muxer with broadly available encoders.
                            val mkvOutput = File(media.parentFile, "${media.nameWithoutExtension}.burnin.mkv")
                            val mkvFallbacks = listOf(
                                listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "copy"),
                                listOf("-c:v", "mpeg4", "-q:v", "2", "-c:a", "copy")
                            )
                            for (fallback in mkvFallbacks) {
                                val encoderLabel = fallback.joinToString(" ")
                                Log.w(TAG, "HardSub webm container fallback media=${media.name} args=$encoderLabel")
                                result = executeFfmpegWithAutoPatch(
                                    ffmpegRuntime,
                                    listOf(
                                        "-y",
                                        "-i",
                                        media.absolutePath,
                                        "-vf",
                                        subtitleArg
                                    ) + fallback + listOf(
                                        "-f",
                                        "matroska",
                                        mkvOutput.absolutePath
                                    ),
                                    progressTarget
                                )
                                if (result.exitCode == 0) {
                                    if (output.exists()) output.delete()
                                    if (mkvOutput.exists()) {
                                        mkvOutput.renameTo(output)
                                    }
                                    break
                                }
                                if (mkvOutput.exists()) mkvOutput.delete()
                            }
                        }
                    }
                    // If this runtime does not include the requested filter, stop retrying this filter variant immediately.
                    if (result.output.contains("No such filter: '$filter'", ignoreCase = true)) {
                        break
                    }
                    if (result.exitCode == 0) break
                }
                if (result.exitCode == 0) {
                    ffmpegResult = result
                    usedFilter = filter
                    break
                }

                // Fallback when ffmpeg build does not include the ass filter.
                if (filter == "ass" && result.output.contains("No such filter: 'ass'", ignoreCase = true)) {
                    ffmpegResult = result
                    Log.w(TAG, "HardSub ass filter unavailable, fallback to subtitles filter media=${media.name}")
                    continue
                }
                if (filter == "subtitles" && result.output.contains("No such filter: 'subtitles'", ignoreCase = true)) {
                    ffmpegResult = result
                    Log.w(TAG, "HardSub subtitles filter unavailable media=${media.name}")
                    continue
                }

                ffmpegResult = result
                break
            }

            val finalResult = ffmpegResult ?: FfmpegExecResult(1, "ffmpeg burn-in returned no result")
            if (finalResult.exitCode != 0) {
                Log.e(TAG, "HardSub ffmpeg burn failed code=${finalResult.exitCode} media=${media.name}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("ffmpeg burn-in failed (code=${finalResult.exitCode}): ${finalResult.output.takeLast(1200)}")
            }
            if (!output.exists()) {
                Log.e(TAG, "HardSub ffmpeg output missing media=${media.name}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("ffmpeg burn-in failed: output was not created")
            }
            if (mediaHadAudioBeforeBurn && !hasAudioStream(output, ffmpegRuntime)) {
                Log.e(TAG, "HardSub ffmpeg output missing-audio media=${media.name}")
                if (output.exists()) output.delete()
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("ffmpeg burn-in failed: output lost audio stream")
            }

            Log.i(
                TAG,
                "HardSub replace start media=${media.absolutePath} exists=${media.exists()} size=${media.length()} mtime=${media.lastModified()} output=${output.absolutePath} outSize=${output.length()}"
            )

            if (media.exists() && !media.delete()) {
                Log.e(TAG, "HardSub replace delete failed media=${media.absolutePath}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("failed to replace original media file after burn-in")
            }
            if (!output.renameTo(media)) {
                Log.e(TAG, "HardSub replace rename failed from=${output.absolutePath} to=${media.absolutePath}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("failed to rename burn-in output file")
            }
            // Keep filesystem mtime aligned with hard-sub completion time so
            // re-downloaded/re-encoded files are distinguishable from originals.
            runCatching { media.setLastModified(System.currentTimeMillis()) }
            Log.i(
                TAG,
                "HardSub replace done media=${media.absolutePath} exists=${media.exists()} size=${media.length()} mtime=${media.lastModified()}"
            )

            if (removeSubsAfterBurnIn) {
                deleteSubtitleSidecars(media, subtitleExts)
            }
            if (subtitle.isTemporary) {
                subtitle.file.delete()
            }
            burnedMediaCount += 1
            Log.i(TAG, "HardSub burn success media=${media.name} filter=${usedFilter ?: "unknown"}")
        }
        if (burnedMediaCount == 0) {
            if (canonicalSubtitle?.isTemporary == true) canonicalSubtitle.file.delete()
            if (!unwritableOutputDir.isNullOrBlank()) {
                throw IOException("HardSub aborted: output directory is not writable for ffmpeg ($unwritableOutputDir)")
            }
            Log.w(TAG, "HardSub skipped: no media was burned")
            return false
        }
        if (removeSubsAfterBurnIn) {
            // Remove downloaded subtitle sidecars that participated in this burn-in run.
            val removableSubtitlePaths = linkedSetOf<String>().apply {
                addAll(subtitleFiles.map { it.absolutePath })
                addAll(consumedSubtitles)
            }
            removableSubtitlePaths.forEach { subtitlePath ->
                runCatching {
                    val subtitleFile = File(subtitlePath)
                    if (subtitleFile.exists() && subtitleFile.isFile) {
                        subtitleFile.delete()
                    }
                }
            }
        }
        if (canonicalSubtitle?.isTemporary == true) canonicalSubtitle.file.delete()
        return true
    }

    private fun hardSubVideoEncodeArgs(media: File): List<String> {
        if (media.extension.lowercase(Locale.ROOT) !in hardSubH264Containers) {
            return emptyList()
        }
        return listOf(
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p"
        )
    }

    private fun hasVideoStream(media: File, ffmpegRuntime: FfmpegRuntime): Boolean {
        val probe = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf("-hide_banner", "-i", media.absolutePath)
        )
        // ffmpeg -i returns non-zero without output target, so parse stream info from output.
        return Regex("""(?m)^\s*Stream #\d+:\d+.*:\s*Video:\s*""").containsMatchIn(probe.output)
    }

    private fun hasAudioStream(media: File, ffmpegRuntime: FfmpegRuntime): Boolean {
        val probe = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf("-hide_banner", "-i", media.absolutePath)
        )
        // ffmpeg -i returns non-zero without output target, so parse stream info from output.
        return Regex("""(?m)^\s*Stream #\d+:\d+.*:\s*Audio:\s*""").containsMatchIn(probe.output)
    }

    private fun mergeSeparatedVideoAudioIfNeeded(mediaFiles: List<File>, ffmpegRuntime: FfmpegRuntime): List<File> {
        if (mediaFiles.size < 2) {
            val single = mediaFiles.firstOrNull() ?: return mediaFiles
            val likelySplitVideo = normalizeMuxStem(single.nameWithoutExtension) != single.nameWithoutExtension
            if (likelySplitVideo && hasVideoStream(single, ffmpegRuntime) && !hasAudioStream(single, ffmpegRuntime)) {
                throw IOException("HardSub AV merge required but companion audio file is missing for video=${single.name}")
            }
            return mediaFiles
        }

        val videoCandidates = mediaFiles.filter { hasVideoStream(it, ffmpegRuntime) }
        if (videoCandidates.isEmpty()) return mediaFiles
        val audioOnlyCandidates = mediaFiles.filter { !hasVideoStream(it, ffmpegRuntime) && hasAudioStream(it, ffmpegRuntime) }

        val primaryVideo = videoCandidates.maxByOrNull { it.length() } ?: return mediaFiles
        if (hasAudioStream(primaryVideo, ffmpegRuntime)) return mediaFiles
        if (audioOnlyCandidates.isEmpty()) {
            throw IOException(
                "HardSub AV merge required but no audio-only file found for video=${primaryVideo.name}"
            )
        }

        val normalizedVideoStem = normalizeMuxStem(primaryVideo.nameWithoutExtension)
        val matchedAudioCandidates = audioOnlyCandidates
            .filter { normalizeMuxStem(it.nameWithoutExtension) == normalizedVideoStem }
        if (matchedAudioCandidates.isEmpty()) {
            throw IOException(
                "HardSub AV merge required but matching audio file not found for video=${primaryVideo.name}"
            )
        }
        val primaryAudio = matchedAudioCandidates.maxByOrNull { it.length() }
            ?: throw IOException("HardSub AV merge failed selecting audio candidate for video=${primaryVideo.name}")

        val mergedVideo = if (canCreateSiblingOutput(primaryVideo.parentFile ?: return mediaFiles)) {
            mergeVideoAudioPairInDirectory(primaryVideo, primaryAudio, ffmpegRuntime)
        } else {
            Log.w(
                TAG,
                "HardSub AV merge staging fallback video=${primaryVideo.name} audio=${primaryAudio.name} dir=${primaryVideo.parentFile?.absolutePath ?: "unknown"}"
            )
            mergeVideoAudioPairViaWritableStage(primaryVideo, primaryAudio, ffmpegRuntime)
        } ?: throw IOException(
            "HardSub AV merge failed video=${primaryVideo.name} audio=${primaryAudio.name}"
        )

        val removed = setOf(primaryVideo.absolutePath, primaryAudio.absolutePath)
        return mediaFiles
            .asSequence()
            .filter { it.absolutePath !in removed }
            .filter { it.exists() && it.isFile }
            .plus(sequenceOf(mergedVideo))
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun mergeVideoAudioPairInDirectory(primaryVideo: File, primaryAudio: File, ffmpegRuntime: FfmpegRuntime): File? {
        val parent = primaryVideo.parentFile ?: return null
        val mergedTemp = File(parent, "${primaryVideo.nameWithoutExtension}.muxed.${primaryVideo.extension}")
        val mergeResult = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf(
                "-y",
                "-i", primaryVideo.absolutePath,
                "-i", primaryAudio.absolutePath,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c", "copy",
                mergedTemp.absolutePath
            )
        )
        if (mergeResult.exitCode != 0 || !mergedTemp.exists() || mergedTemp.length() == 0L) {
            if (mergedTemp.exists()) mergedTemp.delete()
            Log.w(
                TAG,
                "HardSub AV merge skipped video=${primaryVideo.name} audio=${primaryAudio.name} code=${mergeResult.exitCode} dir=${parent.absolutePath}"
            )
            return null
        }

        val replaceOk = runCatching {
            if (primaryVideo.exists() && !primaryVideo.delete()) return@runCatching false
            if (!mergedTemp.renameTo(primaryVideo)) return@runCatching false
            primaryAudio.delete()
            true
        }.getOrDefault(false)
        if (!replaceOk) {
            if (mergedTemp.exists()) mergedTemp.delete()
            Log.w(TAG, "HardSub AV merge replace failed video=${primaryVideo.name} audio=${primaryAudio.name}")
            return null
        }

        Log.i(
            TAG,
            "HardSub AV merged video=${primaryVideo.name} audio=${primaryAudio.name} size=${primaryVideo.length()}"
        )
        return primaryVideo
    }

    private fun mergeVideoAudioPairViaWritableStage(primaryVideo: File, primaryAudio: File, ffmpegRuntime: FfmpegRuntime): File? {
        val sourceParent = primaryVideo.parentFile ?: return null
        val stageRoot = File(
            FileUtil.getCachePath(context),
            "hardsub_mux_stage/${System.currentTimeMillis()}_${primaryVideo.nameWithoutExtension.hashCode().toString().replace('-', 'n')}"
        )
        if (!stageRoot.exists() && !stageRoot.mkdirs()) {
            Log.w(TAG, "HardSub AV merge staging create failed dir=${stageRoot.absolutePath}")
            return null
        }
        return try {
            val stagedVideo = File(stageRoot, primaryVideo.name)
            val stagedAudio = File(stageRoot, primaryAudio.name)
            runCatching { primaryVideo.copyTo(stagedVideo, overwrite = true) }.getOrElse { error ->
                Log.w(TAG, "HardSub AV merge staging copy failed file=${primaryVideo.name} reason=${error.message}")
                return null
            }
            runCatching { primaryAudio.copyTo(stagedAudio, overwrite = true) }.getOrElse { error ->
                Log.w(TAG, "HardSub AV merge staging copy failed file=${primaryAudio.name} reason=${error.message}")
                return null
            }

            val mergedInStage = mergeVideoAudioPairInDirectory(stagedVideo, stagedAudio, ffmpegRuntime) ?: return null
            val publishDir = File(stageRoot, "publish")
            if (!publishDir.exists() && !publishDir.mkdirs()) {
                Log.w(TAG, "HardSub AV merge staging publish dir create failed dir=${publishDir.absolutePath}")
                return null
            }
            val stagedPublish = File(publishDir, primaryVideo.name)
            mergedInStage.copyTo(stagedPublish, overwrite = true)

            val movedBack = runBlocking {
                runCatching {
                    FileUtil.moveFile(
                        publishDir,
                        context,
                        sourceParent.absolutePath,
                        keepCache = false
                    ) { _ -> }
                }.getOrElse { error ->
                    Log.w(TAG, "HardSub AV merge staging move-back failed dir=${sourceParent.absolutePath} reason=${error.message}")
                    emptyList()
                }
            }
            val movedPath = movedBack
                .firstOrNull { File(it).name == primaryVideo.name }
                ?: movedBack.firstOrNull { moved ->
                    val movedFile = File(moved)
                    movedFile.extension.equals(primaryVideo.extension, ignoreCase = true) &&
                        movedFile.nameWithoutExtension.startsWith(primaryVideo.nameWithoutExtension)
                }
                ?: return null

            runCatching { primaryAudio.delete() }
            runCatching { primaryVideo.delete() }
            val mergedFile = File(movedPath)
            if (!mergedFile.exists() || !mergedFile.isFile) return null
            Log.i(
                TAG,
                "HardSub AV merged via staging video=${primaryVideo.name} audio=${primaryAudio.name} output=${mergedFile.absolutePath}"
            )
            mergedFile
        } finally {
            runCatching { stageRoot.deleteRecursively() }
        }
    }

    private fun normalizeMuxStem(name: String): String {
        // Match yt-dlp split stream suffixes like ".f248", ".f248-sr", ".f140-drc", etc.
        return name.replace(Regex("""\.f\d+(?:-[A-Za-z0-9_]+)*$""", RegexOption.IGNORE_CASE), "")
    }

    private fun prepareSubtitleForBurnIn(
        media: File,
        subtitleExts: List<String>,
        providedSubtitles: List<File>,
        ffmpegRuntime: FfmpegRuntime,
        dedicatedSrv3ConverterPath: String?,
        subtitleRequest: SubtitleSelection.Request
    ): BurnInSubtitle? {
        val candidates = findSubtitleCandidatesForMedia(media, subtitleExts, providedSubtitles, subtitleRequest)
        if (candidates.isEmpty()) return null

        candidates.forEach { selectedSubtitle ->
            if (selectedSubtitle.extension.equals("ass", ignoreCase = true)) {
                return BurnInSubtitle(selectedSubtitle, isAss = true, isTemporary = false)
            }

            val convertedAss = convertSubtitleToAss(selectedSubtitle, ffmpegRuntime, dedicatedSrv3ConverterPath)
            if (convertedAss != null) {
                return BurnInSubtitle(convertedAss, isAss = true, isTemporary = true)
            }

            if (setOf("srv3", "json3", "ttml").contains(selectedSubtitle.extension.lowercase(Locale.US))) {
                Log.w(
                    TAG,
                    "HardSub rich subtitle convert failed source=${selectedSubtitle.name} trying-next-candidate"
                )
                return@forEach
            }

            return BurnInSubtitle(selectedSubtitle, isAss = false, isTemporary = false)
        }

        return null
    }

    private fun convertSubtitleToAss(subtitle: File, ffmpegRuntime: FfmpegRuntime, dedicatedSrv3ConverterPath: String?): File? {
        val richSubtitleExts = setOf("srv3", "json3", "ttml")
        val ext = subtitle.extension.lowercase(Locale.US)
        if (ext in setOf("json", "json3")) {
            SubtitleFormatConverter.convertJson3ToAss(subtitle, createHardSubTempAssFile())?.let {
                Log.i(TAG, "HardSub json3 subtitle converted to ass source=${subtitle.name} output=${it.name}")
                return it
            }
        }
        if (
            dedicatedSrv3ConverterPath != null &&
            richSubtitleExts.contains(ext)
        ) {
            convertSrv3ToAssWithDedicatedConverter(subtitle, dedicatedSrv3ConverterPath)?.let { return it }
            createNormalizedRichSubtitleForConverter(subtitle)?.let { normalized ->
                try {
                    convertSrv3ToAssWithDedicatedConverter(normalized, dedicatedSrv3ConverterPath)?.let { converted ->
                        Log.i(
                            TAG,
                            "HardSub dedicated rich subtitle conversion succeeded after normalization source=${subtitle.name}"
                        )
                        return converted
                    }
                } finally {
                    runCatching { normalized.delete() }
                }
            }
        }

        val output = createHardSubTempAssFile()
        val result = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf(
                "-y",
                "-i",
                subtitle.absolutePath,
                output.absolutePath
            )
        )
        val exitCode = result.exitCode
        if (exitCode != 0 || !output.exists() || output.length() == 0L) {
            if (output.exists()) output.delete()
            return null
        }
        return output
    }

    private data class FfmpegRuntime(
        val executablePath: String,
        val linkerPath: String?,
        val libraryPath: String?,
        val preloadLibraryPath: String?,
        val source: String
    )

    private data class FfmpegExecResult(
        val exitCode: Int,
        val output: String
    )

    private data class FfmpegProgressTarget(
        val downloadItemId: Long,
        val executionId: String,
        val logItemId: Long?,
        val mediaName: String
    )

    private data class FfmpegProcessKey(
        val downloadItemId: Long,
        val executionId: String,
    )

    private fun resolveFfmpegRuntime(excludedSources: Set<String> = emptySet()): FfmpegRuntime {
        runCatching { App.instance.ensureRuntimeToolsInstalled() }
            .onFailure { Log.w(TAG, "Failed to ensure bundled runtime tools before ffmpeg runtime resolution", it) }
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val extractedLibDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val executableCandidates = listOf(
            File(nativeLibDir, "libffmpeg.so") to "wrapper-native-libffmpeg"
        )

        val selected = executableCandidates.firstOrNull { (file, source) ->
            if (source in excludedSources || source in hardSubDisabledFfmpegSources) return@firstOrNull false
            isUsableFfmpegExecutable(file)
        } ?: executableCandidates.first()
        val executable = selected.first
        val source = selected.second

        val libraryDirs = mutableListOf<String>()
        if (extractedLibDir.exists() && extractedLibDir.isDirectory) {
            libraryDirs.add(extractedLibDir.absolutePath)
        }
        val preloadLibraryPath: String? = null
        val linkerPath: String? = null
        return FfmpegRuntime(
            executablePath = executable.absolutePath,
            linkerPath = linkerPath,
            libraryPath = libraryDirs.distinct().joinToString(":").ifBlank { null },
            preloadLibraryPath = preloadLibraryPath,
            source = source
        )
    }

    private fun resolveSystemLinkerPath(): String? {
        val preferred = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "/system/bin/linker64" else "/system/bin/linker"
        val preferredFile = File(preferred)
        if (preferredFile.exists() && preferredFile.isFile) return preferredFile.absolutePath
        return listOf("/system/bin/linker64", "/system/bin/linker")
            .map(::File)
            .firstOrNull { it.exists() && it.isFile }
            ?.absolutePath
    }

    private fun systemLibrarySearchDirs(): List<String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val candidates = mutableListOf<String>()
        if (abi.contains("64")) {
            listOf(
                "/apex/com.android.runtime/lib64",
                "/system/lib64",
                "/vendor/lib64"
            )
        } else {
            listOf(
                "/apex/com.android.runtime/lib",
                "/system/lib",
                "/vendor/lib"
            )
        }.let { candidates.addAll(it) }
        // Optional: reuse Termux-provided runtime libs when available.
        candidates.addAll(
            listOf(
                "/data/data/com.termux/files/usr/lib",
                "/data/user/0/com.termux/files/usr/lib"
            )
        )
        return candidates.filter { path ->
            val dir = File(path)
            dir.exists() && dir.isDirectory
        }
    }

    private fun buildFfmpegProcess(runtime: FfmpegRuntime, args: List<String>): ProcessBuilder {
        val command = mutableListOf<String>()
        runtime.linkerPath?.let { command.add(it) }
        command.add(runtime.executablePath)
        command.addAll(args)
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        configureFfmpegEnvironment(builder)
        runtime.libraryPath?.let { libs ->
            val env = builder.environment()
            val current = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] = if (current.isBlank()) libs else "$libs:$current"
            runtime.preloadLibraryPath?.let { preload ->
                val currentPreload = env["LD_PRELOAD"].orEmpty()
                env["LD_PRELOAD"] = if (currentPreload.isBlank()) preload else "$preload:$currentPreload"
            }
        }
        return builder
    }

    private fun configureFfmpegEnvironment(builder: ProcessBuilder) {
        val env = builder.environment()
        val fontconfigDir = File(context.cacheDir, "fontconfig")
        val fontconfigCacheDir = File(fontconfigDir, "cache")
        val fontsConfig = File(fontconfigDir, "fonts.conf")
        runCatching {
            fontconfigCacheDir.mkdirs()
            if (!fontsConfig.exists()) {
                fontsConfig.writeText(
                    """
                    <?xml version="1.0"?>
                    <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                    <fontconfig>
                        <dir>/system/fonts</dir>
                        <cachedir>${fontconfigCacheDir.absolutePath}</cachedir>
                    </fontconfig>
                    """.trimIndent(),
                    Charsets.UTF_8
                )
            }
            env["FONTCONFIG_FILE"] = fontsConfig.absolutePath
            env["FONTCONFIG_PATH"] = fontconfigDir.absolutePath
            env["XDG_CACHE_HOME"] = context.cacheDir.absolutePath
            env["HOME"] = context.filesDir.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "HardSub fontconfig environment setup failed reason=${error.message}")
        }
    }

    private fun startDurableNativeProcess(
        key: FfmpegProcessKey,
        role: String,
        builder: ProcessBuilder,
    ): NativePostProcessingHandle {
        val processId = YtdlpProcessIdentity.directDownload(
            downloadId = key.downloadItemId,
            executionId = key.executionId,
            role = role,
        )
        val prepared = try {
            YtdlpNativeProcessBarrier.prepare(context, processId)
        } catch (failure: Exception) {
            // An unresolved exact role marker is a reuse barrier.  Do not
            // reinterpret prepare failure as an ordinary ffmpeg failure and
            // enter an alternate executable/fallback attempt.
            throw NativeProcessQuiescenceException(
                key.downloadItemId,
                key.executionId,
                failure,
            )
        }
        builder.environment()[YtdlpNativeProcessBarrier.NATIVE_GENERATION_ENVIRONMENT] =
            prepared.generationToken
        builder.environment()["YTDLNISX_PROCESS_ID"] = processId
        val process = try {
            builder.start()
        } catch (failure: Exception) {
            val finalization = YtdlpNativeProcessBarrier.recoverDetailed(
                prepared.marker,
                prepared.generationToken,
            )
            if (!finalization.isProvenQuiescent) {
                throw NativeProcessQuiescenceException(
                    key.downloadItemId,
                    key.executionId,
                )
            }
            throw failure
        }

        if (!YtdlpNativeProcessBarrier.publishDirectProcessRunning(prepared)) {
            val finalization = YtdlpNativeProcessBarrier.recoverDetailed(
                prepared.marker,
                prepared.generationToken,
            )
            if (!finalization.isProvenQuiescent) {
                ProcessQuiescence.requestTermination(process)
                throw NativeProcessQuiescenceException(
                    key.downloadItemId,
                    key.executionId,
                )
            }
        }
        return NativePostProcessingHandle(process, prepared)
    }

    private fun executeFfmpegWithAutoPatch(
        runtime: FfmpegRuntime,
        args: List<String>,
        progressTarget: FfmpegProgressTarget? = null
    ): FfmpegExecResult {
        var activeRuntime = runtime
        if (activeRuntime.source in hardSubDisabledFfmpegSources) {
            activeRuntime = resolveFfmpegRuntime(setOf(activeRuntime.source))
        }
        var last = runFfmpeg(activeRuntime, args, progressTarget)
        if (last.exitCode == 0) return last

        if (last.output.contains("Permission denied", ignoreCase = true)) {
            hardSubDisabledFfmpegSources.add(activeRuntime.source)
            val fallback = resolveFfmpegRuntime(setOf(activeRuntime.source))
            if (fallback.executablePath != activeRuntime.executablePath) {
                Log.w(
                    TAG,
                    "HardSub ffmpeg permission denied source=${activeRuntime.source}; fallback source=${fallback.source}"
                )
                activeRuntime = fallback
                last = runFfmpeg(activeRuntime, args, progressTarget)
                if (last.exitCode == 0) return last
            }
        }

        if (isLikelyInvalidFfmpegBinaryOutput(last.output)) {
            hardSubDisabledFfmpegSources.add(activeRuntime.source)
            val fallback = resolveFfmpegRuntime(setOf(activeRuntime.source))
            if (fallback.executablePath != activeRuntime.executablePath) {
                Log.w(
                    TAG,
                    "HardSub ffmpeg invalid runtime output source=${activeRuntime.source}; fallback source=${fallback.source}"
                )
                activeRuntime = fallback
                last = runFfmpeg(activeRuntime, args, progressTarget)
                if (last.exitCode == 0) return last
            }
        }

        val patchedLibs = mutableSetOf<String>()
        repeat(20) {
            val missingLib = extractMissingRuntimeLibraryName(last.output).orEmpty()
            if (missingLib.isBlank()) return last
            if (!patchedLibs.add(missingLib)) return last
            if (!patchMissingRuntimeLibrary(missingLib)) return last

            Log.w(TAG, "HardSub ffmpeg retry after runtime patch lib=$missingLib")
            last = runFfmpeg(activeRuntime, args, progressTarget)
            if (last.exitCode == 0) return last
        }

        return last
    }

    private fun extractMissingRuntimeLibraryName(output: String): String? {
        Regex("""library "([^"]+)" not found""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex("""cannot find "([^"]+)" from verneed""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex(""""([^"]+)" is too small to be an ELF executable""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                return try {
                    File(raw).name.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }

        return null
    }

    private fun isUsableFfmpegExecutable(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (!file.canExecute()) return false
        if (!file.name.endsWith(".so")) return true
        return hasElfHeader(file)
    }

    private fun hasElfHeader(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 &&
                    header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun isLikelyInvalidFfmpegBinaryOutput(output: String): Boolean {
        val lowered = output.lowercase(Locale.US)
        return lowered.contains("exec format error") ||
            lowered.contains("syntax error: unexpected") ||
            Regex("""(?m)\[\d+]:\s*PK""").containsMatchIn(output)
    }

    private fun needsExplicitWebmEncoder(output: String): Boolean {
        val lowered = output.lowercase(Locale.US)
        return lowered.contains("automatic encoder selection failed") ||
            lowered.contains("error selecting an encoder for stream")
    }

    private fun shouldTryWebmFallback(output: String): Boolean {
        if (needsExplicitWebmEncoder(output)) return true
        val lowered = output.lowercase(Locale.US)
        return lowered.contains("abi version mismatch") ||
            lowered.contains("failed to initialize encoder") ||
            lowered.contains("error while opening encoder") ||
            lowered.contains("nothing was written into output file")
    }

    private fun buildFilterCandidatesForMedia(subtitleIsAss: Boolean, supportedFilters: Set<String>): List<String> {
        if (supportedFilters.isEmpty()) {
            return if (subtitleIsAss) listOf("ass", "subtitles") else listOf("subtitles", "ass")
        }
        val candidates = mutableListOf<String>()
        if (subtitleIsAss && supportedFilters.contains("ass")) {
            candidates.add("ass")
        }
        if (supportedFilters.contains("subtitles")) {
            candidates.add("subtitles")
        }
        if (subtitleIsAss && !candidates.contains("ass") && supportedFilters.contains("ass")) {
            candidates.add("ass")
        }
        return candidates.distinct()
    }

    private fun probeSubtitleFilters(runtime: FfmpegRuntime): Set<String> {
        val cacheKey = "${runtime.source}|${runtime.executablePath}"
        hardSubFilterSupportCache[cacheKey]?.let { return it }

        val probe = executeFfmpegWithAutoPatch(
            runtime,
            listOf("-hide_banner", "-filters")
        )
        if (probe.exitCode != 0) {
            hardSubFilterSupportCache[cacheKey] = emptySet()
            return emptySet()
        }
        val output = probe.output
        val support = mutableSetOf<String>()
        if (Regex("""(?m)^\s*[.A-Z]{3}\s+ass\s""").containsMatchIn(output)) {
            support.add("ass")
        }
        if (Regex("""(?m)^\s*[.A-Z]{3}\s+subtitles\s""").containsMatchIn(output)) {
            support.add("subtitles")
        }
        hardSubFilterSupportCache[cacheKey] = support
        return support
    }

    private fun runFfmpeg(
        runtime: FfmpegRuntime,
        args: List<String>,
        progressTarget: FfmpegProgressTarget? = null
    ): FfmpegExecResult {
        return runCatching {
            val processKey = progressTarget?.let {
                if (it.executionId.isBlank()) {
                    throw NativeProcessQuiescenceException(it.downloadItemId, it.executionId)
                }
                FfmpegProcessKey(it.downloadItemId, it.executionId)
            } ?: nativeProcessKeyForCurrentThread()
                ?: error("Direct FFmpeg started without an exact Download execution owner")
            val handle = startDurableNativeProcess(
                key = processKey,
                role = "ffmpeg",
                builder = buildFfmpegProcess(runtime, args),
            )
            val process = handle.process
            val prepared = requireNotNull(handle.prepared)
            registerNativePostProcessingProcess(processKey, process, prepared)
            val outputBuilder = StringBuilder()
            val startedAt = System.currentTimeMillis()
            var lastLiveLogAt = 0L
            var detectedDurationSec: Double? = null
            var lastProgressPercent = -1
            var lastProgressPostAt = 0L
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    outputBuilder.append(line).append('\n')
                    val now = System.currentTimeMillis()
                    if (shouldLogFfmpegLiveLine(line, now, lastLiveLogAt)) {
                        Log.i(TAG, "HardSub ffmpeg live source=${runtime.source} line=$line")
                        lastLiveLogAt = now
                    }
                    if (progressTarget != null) {
                        parseDurationSecondsFromFfmpegLine(line)?.let { parsed ->
                            if (parsed > 0.0) detectedDurationSec = parsed
                        }
                        val currentSec = parseCurrentSecondsFromFfmpegLine(line)
                        val totalSec = detectedDurationSec
                        if (currentSec != null && totalSec != null && totalSec > 0.0) {
                            val percent = ((currentSec / totalSec) * 100.0).toInt().coerceIn(1, 99)
                            if (percent > lastProgressPercent && (now - lastProgressPostAt) >= 700L) {
                                EventBus.getDefault().post(
                                    WorkerProgress(
                                        percent,
                                        "Burning subtitles $percent%",
                                        progressTarget.downloadItemId,
                                        progressTarget.logItemId
                                    )
                                )
                                lastProgressPercent = percent
                                lastProgressPostAt = now
                            }
                        }
                    }
                }
            }
            try {
                val exitCode = process.waitFor()
                val elapsed = System.currentTimeMillis() - startedAt
                Log.i(TAG, "HardSub ffmpeg end source=${runtime.source} code=$exitCode elapsedMs=$elapsed")
                FfmpegExecResult(exitCode = exitCode, output = outputBuilder.toString())
            } finally {
                // A normal waitFor() completion is already quiescent.  The
                // explicit call also covers coroutine/reader interruption;
                // an unproven process stays in the exact registry for later
                // recovery instead of becoming an orphan writer.
                val processQuiesced = ProcessQuiescence.requestTermination(process)
                val durableQuiesced = YtdlpNativeProcessBarrier.recoverDetailed(
                    prepared.marker,
                    prepared.generationToken,
                ).isProvenQuiescent
                if (processQuiesced && durableQuiesced) {
                    processKey?.let { key ->
                        removeNativePostProcessingProcess(key, process)
                    }
                } else {
                    throw NativeProcessQuiescenceException(
                        processKey.downloadItemId,
                        processKey.executionId,
                    )
                }
            }
        }.getOrElse { error ->
            if (error is NativeProcessQuiescenceException) throw error
            Log.e(TAG, "HardSub ffmpeg process start failed source=${runtime.source}", error)
            FfmpegExecResult(exitCode = 1, output = error.message ?: error.toString())
        }
    }

    private fun parseDurationSecondsFromFfmpegLine(line: String): Double? {
        val match = Regex("""Duration:\s*([0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?)""").find(line) ?: return null
        return parseClockToSeconds(match.groupValues[1])
    }

    private fun parseCurrentSecondsFromFfmpegLine(line: String): Double? {
        val match = Regex("""time=([0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?)""").find(line) ?: return null
        return parseClockToSeconds(match.groupValues[1])
    }

    private fun parseClockToSeconds(value: String): Double? {
        val parts = value.split(":")
        if (parts.size != 3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = parts[2].toDoubleOrNull() ?: return null
        return hour * 3600.0 + minute * 60.0 + second
    }

    private fun shouldLogFfmpegLiveLine(line: String, now: Long, lastLoggedAt: Long): Boolean {
        if (line.isBlank()) return false
        val lowered = line.lowercase(Locale.US)
        val important = lowered.contains("frame=") ||
            lowered.contains("time=") ||
            lowered.contains("speed=") ||
            lowered.contains("stream mapping") ||
            lowered.contains("error") ||
            lowered.contains("failed") ||
            lowered.contains("conversion failed") ||
            lowered.contains("press [q]")
        if (!important) return false
        if (lastLoggedAt == 0L) return true
        return (now - lastLoggedAt) >= 1200L
    }

    private fun patchMissingRuntimeLibrary(missingLibName: String): Boolean {
        val ffmpegLibDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        if (!ffmpegLibDir.exists() || !ffmpegLibDir.isDirectory) return false

        val target = File(ffmpegLibDir, missingLibName)
        if (target.exists()) {
            if (hasElfHeader(target)) return true
            val normalized = resolveAliasLibraryTarget(target)
            if (normalized != null) {
                return runCatching {
                    normalized.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setReadable(true, true)
                    Log.i(TAG, "HardSub ffmpeg runtime normalized alias lib=${target.name} source=${normalized.name}")
                    true
                }.getOrDefault(false)
            }
        }

        val baseName = missingLibName.substringBefore(".so", missingLibName) + ".so"
        val inPayload = selectBestLibraryCandidate(
            ffmpegLibDir.listFiles().orEmpty().filter { it.isFile },
            missingLibName,
            baseName
        )
        if (inPayload != null) {
            return runCatching {
                inPayload.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setReadable(true, true)
                Log.i(TAG, "HardSub ffmpeg runtime patched lib=${target.name} source=${inPayload.absolutePath}")
                true
            }.getOrDefault(false)
        }

        val searchDirs = buildList {
            addAll(systemLibrarySearchDirs())
            add(context.applicationInfo.nativeLibraryDir)
        }.distinct()

        val source = findLibrarySource(searchDirs, missingLibName, baseName)
            ?: run {
                Log.w(TAG, "HardSub ffmpeg runtime patch source not found for $missingLibName")
                return false
            }

        return runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, true)
            Log.i(TAG, "HardSub ffmpeg runtime patched lib=${target.name} source=${source.absolutePath}")
            true
        }.getOrElse {
            Log.e(TAG, "HardSub ffmpeg runtime patch failed lib=$missingLibName", it)
            false
        }
    }

    private fun selectBestLibraryCandidate(candidates: List<File>, missingLibName: String, baseName: String): File? {
        val elfCandidates = candidates.filter { hasElfHeader(it) }
        if (elfCandidates.isEmpty()) return null

        elfCandidates.firstOrNull { it.name == missingLibName }?.let { return it }
        elfCandidates.firstOrNull { it.name.startsWith("$missingLibName.") }?.let { return it }

        val major = Regex("""\.so\.(\d+)$""")
            .find(missingLibName)
            ?.groupValues
            ?.getOrNull(1)
        if (major != null) {
            elfCandidates.firstOrNull { it.name.matches(Regex("^${Regex.escape(baseName)}\\.${Regex.escape(major)}(\\..+)?$")) }
                ?.let { return it }
        }

        return elfCandidates.firstOrNull { it.name.startsWith("$baseName.") }
            ?: elfCandidates.firstOrNull { it.name == baseName }
    }

    private fun resolveAliasLibraryTarget(aliasFile: File): File? {
        if (!aliasFile.exists() || !aliasFile.isFile) return null
        if (hasElfHeader(aliasFile)) return aliasFile
        return runCatching {
            val targetName = aliasFile.readBytes()
                .toString(Charsets.UTF_8)
                .replace("\u0000", "")
                .trim()
            if (!targetName.matches(Regex("^[A-Za-z0-9._+\\-]+$"))) return null
            val target = File(aliasFile.parentFile, targetName)
            if (!target.exists() || !target.isFile) return null
            if (!hasElfHeader(target)) return null
            target
        }.getOrNull()
    }

    private fun findLibrarySource(searchDirs: List<String>, missingLibName: String, baseName: String): File? {
        val direct = searchDirs
            .asSequence()
            .map(::File)
            .filter { it.exists() && it.isDirectory }
            .mapNotNull { dir ->
                sequenceOf(
                    File(dir, missingLibName),
                    File(dir, baseName)
                ).firstOrNull { it.exists() && it.isFile }
                    ?: dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.startsWith(baseName) }
            }
            .firstOrNull()
        if (direct != null) return direct

        val recursiveRoots = listOf("/apex", "/system", "/vendor")
            .map(::File)
            .filter { it.exists() && it.isDirectory }
        recursiveRoots.forEach { root ->
            root.walkTopDown()
                .onEnter { dir ->
                    // Limit traversal depth to keep patch lookup fast on-device.
                    val depth = dir.absolutePath.count { it == '/' } - root.absolutePath.count { it == '/' }
                    depth <= 6
                }
                .firstOrNull { file ->
                    file.isFile && (file.name == missingLibName || file.name == baseName || file.name.startsWith(baseName))
                }?.let { return it }
        }
        return null
    }

    private fun convertSrv3ToAssWithDedicatedConverter(subtitle: File, converterPath: String): File? {
        val output = createHardSubTempAssFile()
        return runDedicatedSubtitleConverter(
            input = subtitle,
            output = output,
            converterPath = converterPath,
            format = "ass",
            startFailLogPrefix = "Dedicated srv3->ass converter start failed",
            failLogPrefix = "Dedicated srv3->ass converter failed"
        )
    }

    private fun convertSubtitleToSrtWithDedicatedConverter(subtitle: File, converterPath: String): File? {
        val parent = subtitle.parentFile ?: return null
        val output = File(parent, "${subtitle.nameWithoutExtension}.srt")
        if (output.exists() && output.length() > 0L) return output
        runDedicatedSubtitleConverter(
            input = subtitle,
            output = output,
            converterPath = converterPath,
            format = "srt",
            startFailLogPrefix = "Dedicated subtitle->srt converter start failed",
            failLogPrefix = "Dedicated subtitle->srt converter failed"
        )?.let { return it }

        val ext = subtitle.extension.lowercase(Locale.US)
        if (ext !in setOf("srv3", "json3", "ttml")) return null

        createNormalizedRichSubtitleForConverter(subtitle)?.let { normalized ->
            try {
                runDedicatedSubtitleConverter(
                    input = normalized,
                    output = output,
                    converterPath = converterPath,
                    format = "srt",
                    startFailLogPrefix = "Dedicated subtitle->srt converter (normalized) start failed",
                    failLogPrefix = "Dedicated subtitle->srt converter (normalized) failed"
                )?.let {
                    Log.i(
                        TAG,
                        "HardSub dedicated subtitle->srt conversion succeeded after normalization source=${subtitle.name}"
                    )
                    return it
                }
            } finally {
                runCatching { normalized.delete() }
            }
        }
        return null
    }

    private fun runDedicatedSubtitleConverter(
        input: File,
        output: File,
        converterPath: String,
        format: String,
        startFailLogPrefix: String,
        failLogPrefix: String
    ): File? {
        val processKey = nativeProcessKeyForCurrentThread()
            ?: error("Dedicated subtitle converter started without an exact Download execution owner")
        val handle = runCatching {
            startDurableNativeProcess(
                key = processKey,
                role = "subtitle-converter",
                builder = ProcessBuilder(
                    converterPath,
                    "parse",
                    input.absolutePath,
                    "--format",
                    format,
                    "--save",
                    "file",
                    "--output",
                    output.absolutePath
                ).redirectErrorStream(true),
            )
        }.getOrElse { error ->
            if (error is NativeProcessQuiescenceException) throw error
            Log.w(
                TAG,
                "$startFailLogPrefix path=$converterPath source=${input.name} reason=${error.message}"
            )
            return null
        }
        val process = handle.process
        val prepared = requireNotNull(handle.prepared)
        registerNativePostProcessingProcess(processKey, process, prepared)
        return try {
            val converterOutput = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0 || !output.exists() || output.length() == 0L) {
                if (output.exists()) output.delete()
                Log.w(TAG, "$failLogPrefix (code=$exitCode): ${converterOutput.takeLast(800)}")
                return null
            }
            output
        } finally {
            // Keep a converter process registered if interruption prevented a
            // positive termination acknowledgement.
            val processQuiesced = ProcessQuiescence.requestTermination(process)
            val durableQuiesced = YtdlpNativeProcessBarrier.recoverDetailed(
                prepared.marker,
                prepared.generationToken,
            ).isProvenQuiescent
            if (processQuiesced && durableQuiesced) {
                removeNativePostProcessingProcess(processKey, process)
            } else {
                throw NativeProcessQuiescenceException(
                    processKey.downloadItemId,
                    processKey.executionId,
                )
            }
        }
    }

    private fun createNormalizedRichSubtitleForConverter(subtitle: File): File? {
        val ext = subtitle.extension.lowercase(Locale.US)
        if (ext !in setOf("srv3", "json3", "ttml")) return null
        val parent = subtitle.parentFile ?: return null
        val raw = runCatching { subtitle.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        if (raw.isBlank()) return null

        var normalized = raw
        // Remove empty rich-sub nodes that can cause strict parsers to fail on missing value fields.
        normalized = normalized.replace(Regex("""<s(\s+[^>]*)?/\s*>"""), "")
        normalized = normalized.replace(Regex("""<s(\s+[^>]*)?>\s*</s>"""), "")
        normalized = normalized.replace(Regex("""<p(\s+[^>]*)?>\s*</p>"""), "")

        if (normalized == raw) return null
        val normalizedFile = File(parent, "__hardsub_normalized.$ext")
        return runCatching {
            normalizedFile.writeText(normalized, Charsets.UTF_8)
            Log.i(TAG, "HardSub rich subtitle normalized source=${subtitle.name} output=${normalizedFile.name}")
            normalizedFile
        }.getOrNull()
    }

    private fun createHardSubTempAssFile(): File {
        val dir = File(context.cacheDir, "hardsub")
        val parent = if (dir.mkdirs() || dir.isDirectory) dir else context.cacheDir
        return File(parent, "ytdlnisx_hardsub_${java.util.UUID.randomUUID()}.ass")
    }

    private fun convertSubtitleFilesToSrt(
        subtitleFiles: List<File>,
        ffmpegRuntime: FfmpegRuntime,
        dedicatedSrv3ConverterPath: String?
    ) {
        subtitleFiles.forEach { subtitle ->
            val ext = subtitle.extension.lowercase(Locale.US)
            if (ext == "srt") return@forEach
            val target = File(subtitle.parentFile ?: return@forEach, "${subtitle.nameWithoutExtension}.srt")
            if (target.exists() && target.length() > 0L) return@forEach

            val converted = if (ext in setOf("json", "json3")) {
                SubtitleFormatConverter.convertJson3ToSrt(subtitle)
            } else if (dedicatedSrv3ConverterPath != null && ext in setOf("srv3", "json3", "ttml")) {
                convertSubtitleToSrtWithDedicatedConverter(subtitle, dedicatedSrv3ConverterPath)
            } else {
                val result = executeFfmpegWithAutoPatch(
                    ffmpegRuntime,
                    listOf(
                        "-y",
                        "-i",
                        subtitle.absolutePath,
                        target.absolutePath
                    )
                )
                if (result.exitCode != 0 || !target.exists() || target.length() == 0L) {
                    if (target.exists()) target.delete()
                    null
                } else {
                    target
                }
            }

            if (converted != null) {
                Log.i(TAG, "HardSub subtitle sidecar converted to srt source=${subtitle.name} output=${converted.name}")
            } else {
                Log.w(TAG, "HardSub subtitle sidecar srt conversion failed source=${subtitle.name}")
            }
        }
    }

    private fun resolveSrv3ConverterPath(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val configuredPath = prefs.getString("hard_sub_srv3_converter_path", "").orEmpty().trim()
        if (configuredPath.isNotEmpty()) {
            val configuredFile = File(configuredPath)
            if (configuredFile.exists() && configuredFile.canExecute()) return configuredFile.absolutePath
        }

        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeCandidates = listOf(
            File(nativeLibDir, "libyttml_exec.so"),
            File(nativeLibDir, "libyttml.so")
        )
        nativeCandidates.firstOrNull { it.exists() && it.isFile }?.let { candidate ->
            Log.i(TAG, "HardSub using native yttml converter path=${candidate.absolutePath}")
            return candidate.absolutePath
        }

        val bundledCandidate = File(context.filesDir, "bin/yttml")
        if (bundledCandidate.exists() && bundledCandidate.canExecute()) {
            // filesDir can be mounted noexec on some devices/ROMs; avoid hard failure path.
            val binaryDir = File(context.filesDir, "bin")
            if (isPathInsideDirectory(bundledCandidate.absolutePath, binaryDir)) {
                Log.w(TAG, "HardSub yttml bundled path may be noexec; skip dedicated converter path=${bundledCandidate.absolutePath}")
            } else {
                return bundledCandidate.absolutePath
            }
        }

        return null
    }

    private fun findSubtitleCandidatesForMedia(
        media: File,
        subtitleExts: List<String>,
        providedSubtitles: List<File>,
        subtitleRequest: SubtitleSelection.Request
    ): List<File> {
        val parent = media.parentFile ?: return emptyList()
        val prefix = "${media.nameWithoutExtension}."
        val files = parent.listFiles().orEmpty()
        val allCandidates = (files.asList() + providedSubtitles)
            .asSequence()
            .filter { it.isFile }
            .filter { SubtitleSelection.isSelectedSubtitleFile(it, subtitleRequest) }
            .distinctBy { it.absolutePath }
            .toList()

        val orderedByExt = mutableListOf<File>()
        subtitleExts.forEach { ext ->
            allCandidates
                .asSequence()
                .filter { candidate ->
                    candidate.name.startsWith(prefix) &&
                        candidate.extension.equals(ext, ignoreCase = true)
                }
                .sortedByDescending { candidate -> candidate.lastModified() }
                .forEach { candidate ->
                    if (orderedByExt.none { it.absolutePath == candidate.absolutePath }) {
                        orderedByExt.add(candidate)
                    }
                }
        }
        if (orderedByExt.isNotEmpty()) return orderedByExt

        val sameDirSubtitles = allCandidates.filter { candidate ->
            candidate.parentFile?.absolutePath == parent.absolutePath &&
                subtitleExts.any { ext -> candidate.extension.equals(ext, ignoreCase = true) }
        }
        if (sameDirSubtitles.size == 1) {
            Log.w(TAG, "HardSub subtitle fallback media=${media.name} matched=${sameDirSubtitles.first().name} reason=single-subtitle-in-dir")
            return listOf(sameDirSubtitles.first())
        }
        if (providedSubtitles.size == 1) {
            val only = providedSubtitles.first()
            Log.w(TAG, "HardSub subtitle fallback media=${media.name} matched=${only.name} reason=single-provided-subtitle")
            return listOf(only)
        }
        return emptyList()
    }

    private fun findSiblingMediaForSubtitles(subtitleFiles: List<File>, subtitleExts: List<String>): List<File> {
        val recovered = linkedSetOf<File>()
        subtitleFiles.forEach { subtitle ->
            val parent = subtitle.parentFile ?: return@forEach
            val subtitleStem = subtitle.nameWithoutExtension
            val stemWithoutLang = subtitleStem.substringBeforeLast('.', subtitleStem)
            parent.listFiles().orEmpty().forEach { candidate ->
                if (!candidate.isFile) return@forEach
                val isSubtitle = subtitleExts.any { ext -> candidate.extension.equals(ext, ignoreCase = true) }
                if (isSubtitle) return@forEach
                val mediaStem = candidate.nameWithoutExtension
                if (mediaStem == subtitleStem || mediaStem == stemWithoutLang) {
                    recovered.add(candidate)
                }
            }
        }
        return recovered.toList()
    }

    private fun resolvePreviousHistoryMediaPaths(
        downloadItem: DownloadItem,
        historyKeywordAssignments: HistoryKeywordAssignmentRepository,
    ): List<String> {
        val historyId = HistoryRedownloadMarker.parse(downloadItem.playlistURL)?.historyId
            ?: return emptyList()
        val previous = when (
            val authorization = historyKeywordAssignments.authorizeHistoryReplacementBlocking(
                historyId = historyId,
                expectedSourceUrl = downloadItem.url,
                expectedType = downloadItem.type,
                replacementDownloadId = downloadItem.id,
                replacementOperationId = downloadItem.operationId,
                expectedExecutionId = downloadItem.executionId,
            )
        ) {
            is HistoryReplacementAuthorization.Authorized ->
                authorization.target
            HistoryReplacementAuthorization.TargetMissing,
            HistoryReplacementAuthorization.SourceMismatch,
            HistoryReplacementAuthorization.TypeMismatch ->
                throw HistoryReplacementAuthorizationRefusalException(authorization)
        }
        return previous.downloadPath
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { path ->
                val file = File(path)
                file.exists() && file.isFile
            }
            .toList()
    }

    private suspend fun deleteReplacedHistoryMedia(
        previousHistoryItem: HistoryItem?,
        finalPaths: List<String>,
        downloadItem: DownloadItem,
    ) {
        if (previousHistoryItem == null) return

        val stalePaths = HistoryReplacementFilePolicy.originalPathsToDelete(
            previousPaths = previousHistoryItem.downloadPath,
            replacementPaths = finalPaths
        )
        if (stalePaths.isEmpty()) return

        withOwnedExecutionLease(downloadItem) {
            deleteValidatedReplacementPaths(
                historyId = previousHistoryItem.id,
                paths = stalePaths,
                historyDao = DBManager.getInstance(context).historyDao,
                logLabel = "History replacement cleanup",
                trustedHistoryItem = previousHistoryItem,
                expectedDownloadId = downloadItem.id,
                expectedExecutionId = downloadItem.executionId,
            )
        }
    }

    private suspend fun validateMovedQualityReplacement(
        downloadItem: DownloadItem,
        finalPaths: List<String>,
        historyDao: com.ireum.ytdl.database.dao.HistoryDao,
        historyKeywordAssignments: HistoryKeywordAssignmentRepository,
    ) {
        val marker = HistoryRedownloadMarker.parse(downloadItem.playlistURL)
            ?.takeIf { it.isQualityReplacement }
            ?: return
        val expectedHeight = marker.expectedMinimumHeight ?: return
        val quality = withOwnedExecutionLease(downloadItem) {
            HistoryVideoQualityProbe.probe(context, finalPaths)
        }
        if (
            quality.state == VideoFileQualityState.READY &&
            quality.resolutionHeight >= expectedHeight
        ) {
            return
        }

        val cleanupResult = deleteRejectedQualityReplacementOutputs(
            historyId = marker.historyId,
            candidatePaths = finalPaths,
            historyDao = historyDao,
            historyKeywordAssignments = historyKeywordAssignments,
            downloadItem = downloadItem,
        )
        throw QualityReplacementValidationException(
            cleanupResult = cleanupResult,
            candidatePaths = finalPaths,
            message = "Quality replacement was not committed because the moved output failed validation " +
                "(expected=${expectedHeight}p, actual=${quality.resolutionHeight}p, state=${quality.state})"
        )
    }

    private suspend fun deleteRejectedQualityReplacementOutputs(
        historyId: Long,
        candidatePaths: List<String>,
        historyDao: com.ireum.ytdl.database.dao.HistoryDao,
        historyKeywordAssignments: HistoryKeywordAssignmentRepository,
        downloadItem: DownloadItem,
    ): HistoryReplacementCleanupResult = withOwnedExecutionLease(downloadItem) {
        val authorization = historyKeywordAssignments.authorizeHistoryReplacementBlocking(
            historyId = historyId,
            expectedSourceUrl = downloadItem.url,
            expectedType = downloadItem.type,
            replacementDownloadId = downloadItem.id,
            replacementOperationId = downloadItem.operationId,
            expectedExecutionId = downloadItem.executionId,
        )
        val previous = when (authorization) {
            is HistoryReplacementAuthorization.Authorized -> authorization.target
            else -> return@withOwnedExecutionLease HistoryReplacementCleanupResult.Completed(authorization)
        }
        try {
            val rejectedPaths = HistoryReplacementFilePolicy.rejectedPathsToDelete(
                previousPaths = previous.downloadPath,
                candidatePaths = candidatePaths
            )
            val deletionSummary = deleteValidatedReplacementPaths(
                historyId = historyId,
                paths = rejectedPaths,
                historyDao = historyDao,
                logLabel = "Rejected quality replacement cleanup",
                trustedHistoryItem = previous,
                expectedDownloadId = downloadItem.id,
                expectedExecutionId = downloadItem.executionId,
            )
            HistoryReplacementOutcomePolicy.cleanupResult(
                authorization = authorization,
                deletionSummary = deletionSummary,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ownershipLost: DownloadExecutionOwnershipLostException) {
            throw ownershipLost
        } catch (cleanupError: Exception) {
            Log.w(
                TAG,
                "Rejected quality replacement cleanup failed id=${downloadItem.id}",
                cleanupError
            )
            HistoryReplacementCleanupResult.Failed(authorization, cleanupError)
        }
    }

    private fun deleteValidatedReplacementPaths(
        historyId: Long,
        paths: List<String>,
        historyDao: com.ireum.ytdl.database.dao.HistoryDao,
        logLabel: String,
        trustedHistoryItem: HistoryItem? = null,
        expectedDownloadId: Long = 0L,
        expectedExecutionId: String = "",
    ): HistoryDeletionSummary = HistoryReferenceMutationCoordinator.withLockBlocking {
        if (paths.isEmpty()) {
            return@withLockBlocking HistoryDeletionSummary(
                recordsRequested = 0,
                recordsRemoved = 0,
                removableRecordIds = emptySet(),
                outcomes = emptyList(),
            )
        }
        if (expectedDownloadId > 0L && expectedExecutionId.isNotBlank()) {
            val current = DBManager.getInstance(context).downloadDao
                .getNullableDownloadById(expectedDownloadId)
            if (
                current?.executionId != expectedExecutionId ||
                    current.status !in setOf(
                        DownloadRepository.Status.Active.name,
                        DownloadRepository.Status.PostProcessing.name,
                    )
            ) {
                throw DownloadExecutionOwnershipLostException(
                    downloadId = expectedDownloadId,
                    expectedExecutionId = expectedExecutionId,
                    actualExecutionId = current?.executionId,
                )
            }
        }
        val gateway = AndroidHistoryFileDeletionGateway(context)
        val engine = HistoryFileDeletionEngine(gateway)
        val trustedDocumentTargets: Set<String> = trustedHistoryItem
            ?.let { item -> trustedResolvedTreeTargets(item, paths, gateway) }
            ?: emptySet()
        val retainedTargets = historyDao.getDeletionReferenceRecords()
            .asSequence()
            .flatMap { record -> record.downloadPath.asSequence() }
        val validation = engine.excludeTargetsReferencedBy(
            validation = engine.validate(
                listOf(
                    HistoryDeletionRecord(
                        id = historyId,
                        storedTargets = (paths + trustedDocumentTargets).distinct(),
                        trustedDocumentTargets = trustedDocumentTargets
                    )
                )
            ),
            retainedStoredTargets = retainedTargets
        )
        val result = engine.execute(validation)
        Log.i(
            TAG,
            "$logLabel deleted=${result.filesDeleted} absent=${result.filesAlreadyAbsent} " +
                "skipped=${result.filesSkipped} permission=${result.filesPermissionDenied} " +
                "failed=${result.filesFailed}"
        )
        result
    }

    private fun trustedResolvedTreeTargets(
        item: HistoryItem,
        storedTargets: List<String>,
        gateway: HistoryFileDeletionGateway
    ): Set<String> {
        val resolvedTreeTarget = if (
            item.localTreeUri.isNotBlank() && item.localTreePath.isNotBlank()
        ) {
            FileUtil.resolveTreeDocumentUri(item.localTreeUri, item.localTreePath)?.toString()
        } else {
            null
        }
        return listOfNotNull(resolvedTreeTarget)
            .filterTo(linkedSetOf()) { treeTarget ->
                storedTargets.any { storedTarget ->
                    gateway.referencesSameFile(treeTarget, storedTarget)
                }
            }
    }

    private fun findExistingHistoryForDownloadedItem(
        downloadItem: DownloadItem,
        historyDao: com.ireum.ytdl.database.dao.HistoryDao
    ): HistoryItem? {
        return equivalentDownloadUrls(downloadItem.url)
            .flatMap { url -> historyDao.getItemsByUrl(url) }
            .distinctBy { it.id }
            .firstOrNull { item ->
                item.type == downloadItem.type &&
                    item.downloadPath.any { path -> FileUtil.exists(path) }
            }
    }

    private fun canonicalDownloadUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.isYoutubeURL()) return trimmed
        val id = trimmed.getIDFromYoutubeURL() ?: return trimmed
        return "https://youtu.be/$id"
    }

    private fun equivalentDownloadUrls(url: String): List<String> {
        val canonical = canonicalDownloadUrl(url)
        if (!canonical.startsWith("https://youtu.be/")) return listOf(url)
        val id = canonical.removePrefix("https://youtu.be/")
        return listOf(
            canonical,
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id"
        ).distinct()
    }

    private suspend fun retryMoveFromTempDirectory(
        tempFileDir: File,
        downloadLocation: String,
        keepCache: Boolean,
        downloadItem: DownloadItem,
        downloadLogId: Long?,
        eventBus: EventBus
    ): List<String> {
        return try {
            val recovered = withOwnedExecutionLease(downloadItem) {
                withContext(Dispatchers.IO) {
                    FileUtil.moveFile(tempFileDir, context, downloadLocation, keepCache) { progress ->
                        eventBus.post(
                            WorkerProgress(
                                progress,
                                "Retrying move to ${FileUtil.formatPath(downloadLocation)}",
                                downloadItem.id,
                                downloadLogId
                            )
                        )
                    }
                }
            }.filter { !it.matches("\\.(description)|(txt)\$".toRegex()) }
            if (recovered.isNotEmpty()) {
                Log.w(
                    TAG,
                    "HardSub temp move retry succeeded id=${downloadItem.id} recovered=${recovered.size}"
                )
            }
            recovered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "HardSub temp move retry failed id=${downloadItem.id}", error)
            emptyList()
        }
    }

    private data class BurnInSubtitle(
        val file: File,
        val isAss: Boolean,
        val isTemporary: Boolean
    )

    private data class CanonicalSubtitle(
        val file: File,
        val isTemporary: Boolean
    )

    private fun createCanonicalHardSubSubtitle(subtitleFiles: List<File>, subtitleExts: List<String>): CanonicalSubtitle? {
        if (subtitleFiles.isEmpty()) return null
        val priority = subtitleExts.withIndex().associate { it.value.lowercase(Locale.US) to it.index }
        val selected = subtitleFiles.sortedWith(
            compareBy<File> { file -> priority[file.extension.lowercase(Locale.US)] ?: Int.MAX_VALUE }
                .thenByDescending { file -> file.lastModified() }
        ).first()
        val parent = selected.parentFile ?: return CanonicalSubtitle(selected, isTemporary = false)
        val canonical = File(parent, "__hardsub_input.${selected.extension.lowercase(Locale.US)}")
        if (selected.absolutePath == canonical.absolutePath) {
            return CanonicalSubtitle(selected, isTemporary = false)
        }
        return runCatching {
            selected.copyTo(canonical, overwrite = true)
            Log.i(TAG, "HardSub subtitle canonicalized from=${selected.name} to=${canonical.name}")
            CanonicalSubtitle(canonical, isTemporary = true)
        }.getOrElse {
            Log.w(TAG, "HardSub subtitle canonicalize failed source=${selected.name} reason=${it.message}")
            CanonicalSubtitle(selected, isTemporary = false)
        }
    }

    private fun deleteSubtitleSidecars(media: File, subtitleExts: List<String>) {
        val parent = media.parentFile ?: return
        val prefix = "${media.nameWithoutExtension}."
        parent.listFiles().orEmpty().forEach { file ->
            val isSubtitle = subtitleExts.any { ext -> file.extension.equals(ext, ignoreCase = true) }
            if (file.isFile && file.name.startsWith(prefix) && isSubtitle) {
                file.delete()
            }
        }
    }

    private fun extractPathsFromYtdlpOutput(output: String): List<String> {
        val lines = output.lines()
        val paths = mutableListOf<String>()

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.contains("Destination: ") -> {
                    val path = trimmed.substringAfter("Destination: ").trim().trim('"', '\'')
                    if (path.startsWith("/")) paths.add(path)
                }
                trimmed.contains("Merging formats into ") -> {
                    val path = trimmed.substringAfter("Merging formats into ").trim().trim('"', '\'')
                    if (path.startsWith("/")) paths.add(path)
                }
                trimmed.startsWith("'/") && trimmed.endsWith("'") -> {
                    paths.add(trimmed.trim('\''))
                }
            }
        }
        return paths
    }

    private fun recoverPathsFromDirectory(downloadLocation: String, startedAtMillis: Long): List<String> {
        val dir = File(downloadLocation)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val minTime = startedAtMillis - 120_000L
        val rootDepth = dir.absolutePath.count { it == File.separatorChar }
        val scopedFiles = dir.walkTopDown()
            .onEnter { subDir ->
                val depth = subDir.absolutePath.count { it == File.separatorChar } - rootDepth
                depth <= 4
            }
            .asSequence()
            .filter { it.isFile }
            .toList()

        val recent = scopedFiles
            .asSequence()
            .filter { it.lastModified() >= minTime }
            .map { it.absolutePath }
            .sortedBy { File(it).lastModified() }
            .toList()
        return recent
    }

    private fun recoverAllPathsFromDirectory(downloadLocation: String): List<String> {
        val dir = File(downloadLocation)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val rootDepth = dir.absolutePath.count { it == File.separatorChar }
        return dir.walkTopDown()
            .onEnter { subDir ->
                val depth = subDir.absolutePath.count { it == File.separatorChar } - rootDepth
                depth <= 4
            }
            .asSequence()
            .filter { it.isFile }
            .map { it.absolutePath }
            .sortedBy { File(it).lastModified() }
            .toList()
    }

    private fun recoverPathsByFileNames(downloadLocation: String, fileNames: List<String>): List<String> {
        val dir = File(downloadLocation)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        if (fileNames.isEmpty()) return emptyList()
        val wanted = fileNames.toSet()
        val rootDepth = dir.absolutePath.count { it == File.separatorChar }
        return dir.walkTopDown()
            .onEnter { subDir ->
                val depth = subDir.absolutePath.count { it == File.separatorChar } - rootDepth
                depth <= 5
            }
            .asSequence()
            .filter { it.isFile && wanted.contains(it.name) }
            .map { it.absolutePath }
            .distinct()
            .toList()
    }

    private fun logPathCandidates(label: String, downloadId: Long, paths: List<String>) {
        if (paths.isEmpty()) {
            Log.i(TAG, "$label id=$downloadId count=0")
            return
        }
        val sample = paths.joinToString(limit = 5) { candidate ->
            val file = File(candidate)
            val exists = file.exists()
            val size = if (exists && file.isFile) file.length() else -1L
            "${file.name}[exists=$exists,size=$size]"
        }
        Log.i(TAG, "$label id=$downloadId count=${paths.size} sample=$sample")
    }

    private fun describeDirectorySnapshot(directory: File?): String {
        if (directory == null) return "<null>"
        if (!directory.exists()) return "${directory.absolutePath}[missing]"
        if (!directory.isDirectory) return "${directory.absolutePath}[not-directory]"

        val entries = runCatching {
            directory.walkTopDown()
                .maxDepth(2)
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .take(8)
                .map { file ->
                    val relative = runCatching {
                        file.absolutePath.removePrefix(directory.absolutePath).trimStart(File.separatorChar)
                    }.getOrDefault(file.name)
                    "$relative(size=${file.length()},mtime=${file.lastModified()})"
                }
                .toList()
        }.getOrDefault(emptyList())

        return "${directory.absolutePath}[files=${entries.size}${if (entries.isNotEmpty()) ",sample=${entries.joinToString()}" else ""}]"
    }

    private fun shouldForceHardSubFailpoint(markerName: String): Boolean {
        val marker = File(FileUtil.getCachePath(context), "debug/$markerName")
        val enabled = marker.exists()
        if (enabled) {
            Log.w(TAG, "HardSub failpoint enabled marker=${marker.absolutePath}")
        }
        return enabled
    }

    private fun remapPathsForBurnIn(paths: List<String>, downloadLocation: String, tempLocation: String): List<String> {
        val downloadDir = File(downloadLocation)
        val tempDir = File(tempLocation)
        return paths
            .asSequence()
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotBlank() }
            .mapNotNull { raw ->
                val direct = File(raw)
                if (direct.exists() && direct.isFile) return@mapNotNull direct.absolutePath

                val fileName = direct.name
                if (fileName.isBlank()) return@mapNotNull null

                val fromDownload = File(downloadDir, fileName)
                if (fromDownload.exists() && fromDownload.isFile) return@mapNotNull fromDownload.absolutePath

                val fromTemp = File(tempDir, fileName)
                if (fromTemp.exists() && fromTemp.isFile) return@mapNotNull fromTemp.absolutePath

                null
            }
            .distinct()
            .toList()
    }

    private fun isPathInsideDirectory(path: String, directory: File): Boolean {
        return runCatching {
            val normalizedPath = File(path).canonicalFile.toPath().normalize()
            val normalizedDirectory = directory.canonicalFile.toPath().normalize()
            normalizedPath.startsWith(normalizedDirectory)
        }.getOrDefault(false)
    }

    private fun prioritizePrimaryMediaPath(paths: List<String>, downloadType: DownloadType): MutableList<String> {
        val normalized = paths
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        if (normalized.size <= 1) return normalized

        val primary = selectPrimaryMediaPath(normalized, downloadType) ?: return normalized
        if (normalized.firstOrNull() == primary) return normalized

        val reordered = mutableListOf(primary)
        reordered.addAll(normalized.filterNot { it == primary })
        return reordered
    }

    private fun selectPrimaryMediaPath(paths: List<String>, downloadType: DownloadType): String? {
        val files = paths
            .map { File(it) }
            .filter { it.exists() && it.isFile }
        if (files.isEmpty()) return null

        if (downloadType == DownloadType.video) {
            files.firstOrNull { fileHasVideoTrack(it) }?.let { return it.absolutePath }
            files.maxByOrNull { it.length() }?.let { return it.absolutePath }
        }
        return files.first().absolutePath
    }

    private fun fileHasVideoTrack(file: File): Boolean {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
            val hasVideo = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                ?.lowercase(Locale.US)
                ?.let { it == "yes" || it == "1" || it == "true" } ?: false
            if (hasVideo) return@runCatching true
            val width = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            width > 0 && height > 0
        }.getOrDefault(false).also {
            runCatching { retriever?.release() }
        }
    }

    private fun fileHasAudioTrack(file: File): Boolean {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.lowercase(Locale.US)
                ?.let { it == "yes" || it == "1" || it == "true" } ?: false
        }.getOrDefault(false).also {
            runCatching { retriever?.release() }
        }
    }

    private fun canCreateSiblingOutput(directory: File): Boolean {
        if (!directory.exists() || !directory.isDirectory) return false
        return runCatching {
            val probe = File.createTempFile(".hardsub_probe_", ".tmp", directory)
            probe.delete()
            true
        }.getOrDefault(false)
    }

    private fun escapeForFfmpegFilterArg(path: String): String {
        return path
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace(",", "\\,")
            .replace(";", "\\;")
    }

    private fun buildSubtitleFilterArgs(filterName: String, path: String): List<String> {
        val escaped = escapeForFfmpegFilterArg(path)
        return if (filterName.equals("ass", ignoreCase = true)) {
            // Different ffmpeg versions parse ass filter arguments differently.
            listOf(
                "ass='$escaped'",
                "ass=$escaped",
                "ass=filename='$escaped'"
            )
        } else {
            listOf(
                "$filterName=filename='$escaped'",
                "$filterName='$escaped'"
            )
        }
    }

    class WorkerProgress(
        val progress: Int,
        val output: String,
        val downloadItemID: Long,
        val logItemID: Long?
    )

}



