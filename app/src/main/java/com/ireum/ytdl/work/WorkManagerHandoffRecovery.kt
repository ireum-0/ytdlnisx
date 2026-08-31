package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.receiver.ObserveRetryDecisionReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Exact durable handoff/retry owner for one-shot WorkManager producers.
 *
 * Calling enqueueUniqueWork is not acceptance.  Every attempt uses the exact
 * persisted WorkRequest UUID and observes Operation.result.  A failed attempt
 * replaces only that exact pending row with a new request UUID while retaining
 * the same semantic generation.  Process death reconstructs the rows from
 * Room and uses WorkInfo/request identity before retrying.
 */
internal object WorkManagerHandoffRecovery {
    internal enum class OutcomeKind {
        ACCEPTED,
        RETRYING,
        SUPERSEDED,
        FAILED,
    }

    internal data class EnqueueOutcome(
        val kind: OutcomeKind,
        val failure: Throwable? = null,
    ) {
        val accepted: Boolean
            get() = kind == OutcomeKind.ACCEPTED

        val superseded: Boolean
            get() = kind == OutcomeKind.SUPERSEDED
    }

    private const val START_WORK_NAME = "scheduled_download_start"
    private const val END_WORK_NAME = "scheduled_download_end"
    private const val RETRY_INITIAL_BACKOFF_MS = 1_000L
    private const val RETRY_MAX_BACKOFF_MS = 60_000L

    private val convergenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attemptJobs = java.util.concurrent.ConcurrentHashMap<String, Deferred<EnqueueOutcome>>()
    private val retryJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val boundaryLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val latestGenerationByBoundary = java.util.concurrent.ConcurrentHashMap<String, String>()

    /*
     * Production always uses the real Room database and WorkManager.  These
     * narrow seams let production-wiring tests drive the same coordinator
     * with a real in-memory Room database and a deterministic Operation.
     */
    @Volatile
    internal var databaseForTesting: DBManager? = null

    @Volatile
    internal var workManagerForTesting: WorkManager? = null

    @Volatile
    internal var enqueueOverrideForTesting:
        ((String, ExistingWorkPolicy, OneTimeWorkRequest) -> Operation)? = null

    @Volatile
    internal var workInfoOverrideForTesting: ((String) -> WorkInfo?)? = null

    @Volatile
    internal var cancelUniqueWorkOverrideForTesting: ((String) -> Unit)? = null

    const val EXTRA_HANDOFF_ID = "workManagerHandoffId"

    fun prepareHardSub(context: Context): String {
        val handoffId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = WorkManagerHandoffCarrier.HARD_SUB_SCAN,
            generationId = handoffId,
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = HardSubScanWorker.UNIQUE_WORK_NAME,
            boundary = "",
            createdAt = now,
            updatedAt = now,
        )
        replaceOutstandingAndInsert(context, carrier)
        return handoffId
    }

    fun prepareSchedulerBoundary(
        context: Context,
        boundary: String,
        notBeforeAt: Long,
    ): String {
        val kind = when (boundary) {
            WorkManagerHandoffCarrier.START_BOUNDARY -> WorkManagerHandoffCarrier.SCHEDULE_START
            WorkManagerHandoffCarrier.END_BOUNDARY -> WorkManagerHandoffCarrier.SCHEDULE_END
            else -> error("Unknown scheduler boundary: $boundary")
        }
        val handoffId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = kind,
            generationId = handoffId,
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = when (kind) {
                WorkManagerHandoffCarrier.SCHEDULE_START -> START_WORK_NAME
                else -> END_WORK_NAME
            },
            boundary = boundary,
            notBeforeAt = notBeforeAt,
            createdAt = now,
            updatedAt = now,
        )
        replaceOutstandingAndInsert(context, carrier)
        return handoffId
    }

    /**
     * The notification action is deterministic for one source/config/url so a
     * duplicate PendingIntent cannot create two semantic Download decisions.
     */
    fun prepareObserveRetryDownload(
        context: Context,
        sourceId: Long,
        confirmedUrl: String,
        configFingerprint: String,
    ): String {
        val canonicalIdentity = "$sourceId|$confirmedUrl|$configFingerprint|" +
            ObserveRetryDecisionReceiver.ACTION_DOWNLOAD
        val handoffId = UUID.nameUUIDFromBytes(canonicalIdentity.toByteArray(StandardCharsets.UTF_8)).toString()
        val dao = database(context).workManagerHandoffCarrierDao
        val existing = blocking {
            dao.getOutstandingObserveRetry(
                sourceId = sourceId,
                confirmedUrl = confirmedUrl,
                decision = ObserveRetryDecisionReceiver.ACTION_DOWNLOAD,
                configFingerprint = configFingerprint,
            )
        }
        if (existing != null) return existing.handoffId

        val now = System.currentTimeMillis()
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD,
            generationId = handoffId,
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = "OBSERVE$sourceId",
            sourceId = sourceId,
            confirmedUrl = confirmedUrl,
            decision = ObserveRetryDecisionReceiver.ACTION_DOWNLOAD,
            configFingerprint = configFingerprint,
            createdAt = now,
            updatedAt = now,
        )
        blocking { dao.insert(carrier) }
        return blocking { dao.get(handoffId) }?.handoffId ?: handoffId
    }

    fun prepareLegacySchedulerBoundary(context: Context, boundary: String): String =
        prepareSchedulerBoundary(context, boundary, System.currentTimeMillis())

    fun cancelScheduledHandoffs(context: Context) {
        cancelBoundary(
            context,
            WorkManagerHandoffCarrier.SCHEDULE_START,
            WorkManagerHandoffCarrier.START_BOUNDARY,
        )
        cancelBoundary(
            context,
            WorkManagerHandoffCarrier.SCHEDULE_END,
            WorkManagerHandoffCarrier.END_BOUNDARY,
        )
        runCatching {
            cancelUniqueWork(context, START_WORK_NAME)
            cancelUniqueWork(context, END_WORK_NAME)
        }
    }

    fun enqueueAndObserve(
        context: Context,
        handoffId: String,
        completion: (EnqueueOutcome) -> Unit,
    ) {
        convergenceScope.launch {
            val outcome = enqueueAndAwait(context, handoffId).await()
            withContext(Dispatchers.Main.immediate) {
                completion(outcome)
            }
        }
    }

    fun ensureConvergence(context: Context, handoffId: String) {
        convergenceScope.launch {
            enqueueAndAwait(context, handoffId).await()
        }
    }

    internal fun clearForTesting() {
        attemptJobs.values.toList().forEach { it.cancel() }
        retryJobs.values.toList().forEach { it.cancel() }
        attemptJobs.clear()
        retryJobs.clear()
        latestGenerationByBoundary.clear()
        boundaryLocks.clear()
        databaseForTesting = null
        workManagerForTesting = null
        enqueueOverrideForTesting = null
        workInfoOverrideForTesting = null
        cancelUniqueWorkOverrideForTesting = null
    }

    /** A receiver can keep goAsync alive until this exact Operation completes. */
    suspend fun enqueueAndAwait(
        context: Context,
        handoffId: String,
    ): Deferred<EnqueueOutcome> {
        attemptJobs[handoffId]?.let { return it }
        val candidate = convergenceScope.async(start = CoroutineStart.LAZY) {
            performAttempt(context.applicationContext, handoffId)
        }
        val existing = attemptJobs.putIfAbsent(handoffId, candidate)
        if (existing != null) {
            candidate.cancel()
            return existing
        }
        candidate.invokeOnCompletion { attemptJobs.remove(handoffId, candidate) }
        candidate.start()
        return candidate
    }

    /** Startup path; it does not require runtime/native readiness. */
    suspend fun reconcile(context: Context) {
        val appContext = context.applicationContext
        val dao = database(appContext).workManagerHandoffCarrierDao
        dao.deleteResolved()
        dao.getOutstanding().forEach { carrier ->
            reconcileCarrier(appContext, carrier)
        }
    }

    suspend fun markObserveRetryResolved(
        context: Context,
        handoffId: String,
        requestId: String,
    ): Boolean {
        val dao = database(context).workManagerHandoffCarrierDao
        val changed = dao.markResolved(handoffId, requestId, System.currentTimeMillis())
        if (changed == 0) {
            val current = dao.get(handoffId)
            if (current == null || current.state == WorkManagerHandoffCarrier.RESOLVED) return true
            return false
        }
        dao.delete(handoffId)
        retryJobs.remove(handoffId)?.cancel()
        return true
    }

    fun observeConfigFingerprint(source: ObserveSourcesItem): String {
        val material = listOf(
            source.id,
            source.name,
            source.url,
            source.downloadItemTemplate.toString(),
            source.everyNr,
            source.everyCategory,
            source.everyTime,
            source.weeklyConfig,
            source.monthlyConfig,
            source.startsTime,
            source.endsDate,
            source.endsAfterCount,
            source.getOnlyNewUploads,
            source.retryMissingDownloads,
            source.syncWithSource,
            source.excludeShorts,
            source.autoAddKeyword,
            source.observationPurpose,
            source.managedConditionKey,
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private suspend fun reconcileCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ) {
        if (!isCurrentGeneration(carrier)) return
        val workInfo = workInfo(context, carrier.requestId)
        if (carrier.state == WorkManagerHandoffCarrier.ACCEPTED) {
            when {
                carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD &&
                    workInfo?.state == WorkInfo.State.SUCCEEDED -> {
                    markObserveRetryResolved(context, carrier.handoffId, carrier.requestId)
                }
                carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD &&
                    workInfo?.state in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED) -> {
                    retryAfterFailure(context, carrier, null)
                }
                carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD -> {
                    database(context).workManagerHandoffCarrierDao.deleteAccepted(
                        carrier.handoffId,
                        carrier.requestId,
                    )
                }
            }
            return
        }

        when (workInfo?.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.SUCCEEDED -> {
                val accepted = database(context).workManagerHandoffCarrierDao.markAccepted(
                    carrier.handoffId,
                    carrier.requestId,
                    System.currentTimeMillis(),
                )
                if (accepted > 0 && carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                    database(context).workManagerHandoffCarrierDao.deleteAccepted(
                        carrier.handoffId,
                        carrier.requestId,
                    )
                } else if (
                    accepted > 0 &&
                    carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD &&
                    workInfo.state == WorkInfo.State.SUCCEEDED
                ) {
                    markObserveRetryResolved(context, carrier.handoffId, carrier.requestId)
                }
            }
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED -> retryAfterFailure(context, carrier, null)
            null -> scheduleRetry(context, carrier.handoffId)
        }
    }

    private suspend fun performAttempt(
        context: Context,
        handoffId: String,
    ): EnqueueOutcome {
        val dao = database(context).workManagerHandoffCarrierDao
        val carrier = dao.get(handoffId) ?: return EnqueueOutcome(OutcomeKind.SUPERSEDED)
        if (!isCurrentGeneration(carrier)) {
            return EnqueueOutcome(OutcomeKind.SUPERSEDED)
        }
        if (carrier.state != WorkManagerHandoffCarrier.PENDING_ENQUEUE) {
            return EnqueueOutcome(
                if (carrier.state == WorkManagerHandoffCarrier.ACCEPTED) {
                    OutcomeKind.ACCEPTED
                } else {
                    OutcomeKind.SUPERSEDED
                }
            )
        }
        val remainingDelay = carrier.notBeforeAt - System.currentTimeMillis()
        if (remainingDelay > 0L) {
            scheduleRetry(context, handoffId)
            return EnqueueOutcome(OutcomeKind.RETRYING)
        }

        val request = try {
            buildRequest(context, carrier)
        } catch (failure: Throwable) {
            return retryAfterFailure(context, carrier, failure)
        }

        return try {
            val operation = synchronized(boundaryLock(carrier)) {
                if (!isCurrentGeneration(carrier)) {
                    null
                } else {
                    enqueueUniqueWork(
                        context = context,
                        uniqueWorkName = carrier.uniqueWorkName,
                        request = request,
                    )
                }
            } ?: return EnqueueOutcome(OutcomeKind.SUPERSEDED)
            val failure = awaitOperation(operation)
            if (failure != null) {
                retryAfterFailure(context, carrier, failure)
            } else {
                if (!isCurrentGeneration(carrier)) {
                    return EnqueueOutcome(OutcomeKind.SUPERSEDED)
                }
                val accepted = dao.markAccepted(
                    carrier.handoffId,
                    carrier.requestId,
                    System.currentTimeMillis(),
                )
                if (accepted == 0) {
                    val current = dao.get(carrier.handoffId)
                    if (current == null || current.requestId != carrier.requestId || !isCurrentGeneration(carrier)) {
                        EnqueueOutcome(OutcomeKind.SUPERSEDED)
                    } else {
                        EnqueueOutcome(OutcomeKind.ACCEPTED)
                    }
                } else {
                    if (carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                        dao.deleteAccepted(carrier.handoffId, carrier.requestId)
                    }
                    retryJobs.remove(carrier.handoffId)?.cancel()
                    if (!isCurrentGeneration(carrier)) {
                        EnqueueOutcome(OutcomeKind.SUPERSEDED)
                    } else {
                        EnqueueOutcome(OutcomeKind.ACCEPTED)
                    }
                }
            }
        } catch (failure: Throwable) {
            retryAfterFailure(context, carrier, failure)
        }
    }

    private suspend fun retryAfterFailure(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
        failure: Throwable?,
    ): EnqueueOutcome {
        val dao = database(context).workManagerHandoffCarrierDao
        if (!isCurrentGeneration(carrier)) {
            return EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
        }
        val existingWork = workInfo(context, carrier.requestId)
        if (existingWork != null && existingWork.state !in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            val accepted = dao.markAccepted(
                carrier.handoffId,
                carrier.requestId,
                System.currentTimeMillis(),
            )
            if (accepted > 0 && carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                dao.deleteAccepted(carrier.handoffId, carrier.requestId)
            }
            if (accepted > 0) {
                return if (isCurrentGeneration(carrier)) {
                    EnqueueOutcome(OutcomeKind.ACCEPTED, failure)
                } else {
                    EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
                }
            }
            val current = dao.get(carrier.handoffId)
            return if (current == null || current.requestId != carrier.requestId || !isCurrentGeneration(carrier)) {
                // A newer REPLACE generation owns the exact semantic request.
                // An old Operation/WorkInfo callback cannot report acceptance
                // for that successor.
                EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
            } else {
                EnqueueOutcome(OutcomeKind.ACCEPTED, failure)
            }
        }

        val current = dao.get(carrier.handoffId)
            ?: return EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
        if (current.requestId != carrier.requestId || !isCurrentGeneration(carrier)) {
            return EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
        }
        val newRequestId = UUID.randomUUID().toString()
        val advanced = dao.advanceRetry(
            handoffId = carrier.handoffId,
            oldRequestId = carrier.requestId,
            newRequestId = newRequestId,
            attempt = carrier.attempt + 1,
            updatedAt = System.currentTimeMillis(),
        )
        if (advanced == 0) {
            val latest = dao.get(carrier.handoffId)
            return if (latest == null || latest.requestId != carrier.requestId || !isCurrentGeneration(carrier)) {
                EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
            } else {
                EnqueueOutcome(OutcomeKind.RETRYING, failure)
            }
        }
        scheduleRetry(context, carrier.handoffId)
        return EnqueueOutcome(OutcomeKind.RETRYING, failure)
    }

    private fun scheduleRetry(context: Context, handoffId: String) {
        val retryJob = convergenceScope.launch {
            var backoff = RETRY_INITIAL_BACKOFF_MS
            var firstAttempt = true
            while (isActive) {
                val carrier = withContext(Dispatchers.IO) {
                    database(context).workManagerHandoffCarrierDao.get(handoffId)
                } ?: return@launch
                if (carrier.state != WorkManagerHandoffCarrier.PENDING_ENQUEUE) return@launch

                val waitForSchedule = carrier.notBeforeAt - System.currentTimeMillis()
                if (waitForSchedule > 0L) {
                    delay(waitForSchedule)
                } else if (firstAttempt) {
                    // The current attempt may be the caller that installed
                    // this retry job.  Give its Deferred a chance to leave
                    // attemptJobs before asking for the next exact attempt;
                    // this is scheduling backoff, not an authority barrier.
                    delay(RETRY_INITIAL_BACKOFF_MS)
                }
                firstAttempt = false

                val outcome = enqueueAndAwait(context, handoffId).await()
                if (outcome.accepted || outcome.superseded) return@launch
                delay(backoff)
                backoff = (backoff * 2L).coerceAtMost(RETRY_MAX_BACKOFF_MS)
            }
        }
        val existing = retryJobs.putIfAbsent(handoffId, retryJob)
        if (existing != null) {
            retryJob.cancel()
        } else {
            retryJob.invokeOnCompletion { retryJobs.remove(handoffId, retryJob) }
        }
    }

    private fun replaceOutstandingAndInsert(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ) {
        synchronized(boundaryLock(carrier.kind, carrier.boundary)) {
            val dao = database(context).workManagerHandoffCarrierDao
            val oldCarrier = blocking {
                dao.getOutstandingForBoundary(carrier.kind, carrier.boundary)
            }
            oldCarrier?.let {
                retryJobs.remove(it.handoffId)?.cancel()
                attemptJobs.remove(it.handoffId)?.cancel()
            }
            val inserted = blocking {
                database(context).withTransaction {
                    dao.deleteOutstandingForBoundary(carrier.kind, carrier.boundary)
                    dao.insert(carrier)
                }
            }
            check(inserted != -1L) {
                "WorkManager handoff carrier already exists: ${carrier.handoffId}"
            }
            latestGenerationByBoundary[boundaryKey(carrier.kind, carrier.boundary)] = carrier.handoffId
        }
    }

    private fun cancelBoundary(
        context: Context,
        kind: String,
        boundary: String,
    ) {
        synchronized(boundaryLock(kind, boundary)) {
            val dao = database(context).workManagerHandoffCarrierDao
            val carrier = blocking { dao.getOutstandingForBoundary(kind, boundary) }
            carrier?.let {
                retryJobs.remove(it.handoffId)?.cancel()
                attemptJobs.remove(it.handoffId)?.cancel()
            }
            blocking { dao.deleteOutstandingForBoundary(kind, boundary) }
            // A tombstone prevents an already-running old attempt from
            // treating a concurrent cancellation as a process-death reset.
            latestGenerationByBoundary[boundaryKey(kind, boundary)] = CANCELLED_GENERATION
        }
    }

    private fun <T> blocking(block: suspend () -> T): T =
        runBlocking(Dispatchers.IO + NonCancellable) { block() }

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

    private suspend fun workInfo(context: Context, requestId: String): WorkInfo? {
        val uuid = runCatching { UUID.fromString(requestId) }.getOrNull() ?: return null
        return try {
            workInfoOverrideForTesting?.invoke(requestId)
                ?: workManager(context).getWorkInfoById(uuid).get()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        }
    }

    private fun buildRequest(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): OneTimeWorkRequest {
        val requestId = UUID.fromString(carrier.requestId)
        val initialDelay = (carrier.notBeforeAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val input = Data.Builder()
            .putString(INPUT_HANDOFF_ID, carrier.handoffId)
            .putString(INPUT_REQUEST_ID, carrier.requestId)
            .build()

        return when (carrier.kind) {
            WorkManagerHandoffCarrier.HARD_SUB_SCAN -> OneTimeWorkRequestBuilder<HardSubScanWorker>()
                .setId(requestId)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(HardSubScanWorker.TAG)
                .setInputData(input)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManagerHandoffCarrier.SCHEDULE_START -> {
                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                val constraints = Constraints.Builder().apply {
                    if (!preferences.getBoolean("metered_networks", true)) {
                        setRequiredNetworkType(NetworkType.UNMETERED)
                    }
                }.build()
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setId(requestId)
                    .setConstraints(constraints)
                    .addTag("scheduledDownload")
                    .addTag("download")
                    .setInputData(input)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()
            }

            WorkManagerHandoffCarrier.SCHEDULE_END -> OneTimeWorkRequestBuilder<CancelScheduledDownloadWorker>()
                .setId(requestId)
                .addTag("cancelScheduledDownload")
                .setInputData(input)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD -> {
                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                val networkType = if (preferences.getBoolean("metered_networks", true)) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.UNMETERED
                }
                val observeInput = Data.Builder()
                    .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, carrier.sourceId)
                    .putString(ObserveSourceWorker.INPUT_CONFIRMED_URL, carrier.confirmedUrl)
                    .putString(ObserveSourceWorker.INPUT_CONFIRMATION_DECISION, carrier.decision)
                    .putString(ObserveSourceWorker.INPUT_HANDOFF_ID, carrier.handoffId)
                    .putString(ObserveSourceWorker.INPUT_HANDOFF_REQUEST_ID, carrier.requestId)
                    .putString(ObserveSourceWorker.INPUT_CONFIG_FINGERPRINT, carrier.configFingerprint)
                    .build()
                OneTimeWorkRequestBuilder<ObserveSourceWorker>()
                    .setId(requestId)
                    .addTag("observeSources")
                    .addTag("observation_${carrier.sourceId}")
                    .addTag(carrier.sourceId.toString())
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(networkType).build()
                    )
                    .setInputData(observeInput)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()
            }

            else -> error("Unknown WorkManager handoff kind ${carrier.kind}")
        }
    }

    private const val INPUT_HANDOFF_ID = "handoffId"
    private const val INPUT_REQUEST_ID = "handoffRequestId"
    private const val CANCELLED_GENERATION = "__CANCELLED__"

    private fun database(context: Context): DBManager =
        databaseForTesting ?: DBManager.getInstance(context)

    private fun workManager(context: Context): WorkManager =
        workManagerForTesting ?: WorkManager.getInstance(context)

    private fun enqueueUniqueWork(
        context: Context,
        uniqueWorkName: String,
        request: OneTimeWorkRequest,
    ): Operation = enqueueOverrideForTesting?.invoke(
        uniqueWorkName,
        ExistingWorkPolicy.REPLACE,
        request,
    ) ?: workManager(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.REPLACE,
        request,
    )

    private fun cancelUniqueWork(context: Context, uniqueWorkName: String) {
        cancelUniqueWorkOverrideForTesting?.invoke(uniqueWorkName)
            ?: workManager(context).cancelUniqueWork(uniqueWorkName)
    }

    private fun boundaryKey(kind: String, boundary: String): String = "$kind\u0000$boundary"

    private fun boundaryLock(carrier: WorkManagerHandoffCarrier): Any =
        boundaryLock(carrier.kind, carrier.boundary)

    private fun boundaryLock(kind: String, boundary: String): Any =
        boundaryLocks.getOrPut(boundaryKey(kind, boundary)) { Any() }

    private fun isCurrentGeneration(carrier: WorkManagerHandoffCarrier): Boolean =
        latestGenerationByBoundary[boundaryKey(carrier.kind, carrier.boundary)]
            ?.let { it == carrier.handoffId }
            ?: true
}
