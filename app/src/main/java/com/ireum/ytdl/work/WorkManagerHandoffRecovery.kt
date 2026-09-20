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
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.RestoreTransactionCoordinator.RestoreReconciliationAuthority
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    @Volatile
    internal var cancelUniqueWorkOperationOverrideForTesting: ((String) -> Operation)? = null

    const val EXTRA_HANDOFF_ID = "workManagerHandoffId"

    fun prepareHardSub(context: Context): String {
        check(!RestoreGate.isRestoreInProgress(context)) {
            "Restore transaction is active"
        }
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
    ): String = RestoreMutationAdmission.withOrdinaryMutationBlocking(context) {
        prepareSchedulerBoundaryWithinOrdinaryMutation(context, boundary, notBeforeAt)
    }

    internal fun prepareSchedulerBoundaryWithinOrdinaryMutation(
        context: Context,
        boundary: String,
        notBeforeAt: Long,
    ): String {
        check(!RestoreGate.isRestoreInProgress(context)) {
            "Restore transaction is active"
        }
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
        replaceOutstandingAndInsertLocked(context, carrier, null)
        return handoffId
    }
    internal suspend fun prepareSchedulerBoundaryForRestore(
        context: Context,
        boundary: String,
        notBeforeAt: Long,
        authority: RestoreReconciliationAuthority,
    ): String = RestoreMutationAdmission.withRestoreMutation {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        val kind = when (boundary) {
            WorkManagerHandoffCarrier.START_BOUNDARY -> WorkManagerHandoffCarrier.SCHEDULE_START
            WorkManagerHandoffCarrier.END_BOUNDARY -> WorkManagerHandoffCarrier.SCHEDULE_END
            else -> error("Unknown scheduler boundary: $boundary")
        }
        val identity = "restore-scheduler|${authority.operationId}|$boundary"
        val handoffId = UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8)).toString()
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
        replaceOutstandingAndInsert(context, carrier, authority)
        handoffId
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
        check(!RestoreGate.isRestoreInProgress(context)) {
            "Restore transaction is active"
        }
        val canonicalIdentity = "$sourceId|$confirmedUrl|$configFingerprint|" +
            ObserveRetryDecisionReceiver.ACTION_DOWNLOAD
        val handoffId = UUID.nameUUIDFromBytes(canonicalIdentity.toByteArray(StandardCharsets.UTF_8)).toString()
        return RestoreMutationAdmission.withOrdinaryMutationBlocking(context) {
            val dao = database(context).workManagerHandoffCarrierDao
            check(!RestoreGate.isRestoreInProgress(context)) {
                "Restore transaction is active"
            }
            val existing = blocking {
                dao.getOutstandingObserveRetry(
                    sourceId = sourceId,
                    confirmedUrl = confirmedUrl,
                    decision = ObserveRetryDecisionReceiver.ACTION_DOWNLOAD,
                    configFingerprint = configFingerprint,
                )
            }
            if (existing != null) {
                return@withOrdinaryMutationBlocking existing.handoffId
            }

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
            blocking {
                check(!RestoreGate.isRestoreInProgress(context)) {
                    "Restore transaction is active"
                }
                dao.insert(carrier)
            }
            blocking { dao.get(handoffId) }?.handoffId ?: handoffId
        }
    }
    fun prepareLegacySchedulerBoundary(context: Context, boundary: String): String =
        prepareSchedulerBoundary(context, boundary, System.currentTimeMillis())

    fun cancelScheduledHandoffs(context: Context) {
        RestoreMutationAdmission.tryOrdinaryMutationBlocking(context) {
            cancelScheduledHandoffsWithinOrdinaryMutation(context)
        }
    }

    /**
     * Cancels the complete scheduler authority effect while the caller owns
     * ordinary mutation admission. The carrier tombstone is installed before
     * any later producer can enter, and WorkManager cancellation is awaited
     * before the durable carrier rows are retired.
     */
    internal fun cancelScheduledHandoffsWithinOrdinaryMutation(context: Context) {
        check(!RestoreGate.isRestoreInProgress(context)) {
            "Restore transaction is active"
        }
        markBoundaryCancelledWithinOrdinaryMutation(
            context,
            WorkManagerHandoffCarrier.SCHEDULE_START,
            WorkManagerHandoffCarrier.START_BOUNDARY,
        )
        markBoundaryCancelledWithinOrdinaryMutation(
            context,
            WorkManagerHandoffCarrier.SCHEDULE_END,
            WorkManagerHandoffCarrier.END_BOUNDARY,
        )
        cancelUniqueWorkAndAwait(context, START_WORK_NAME)
        cancelUniqueWorkAndAwait(context, END_WORK_NAME)
        deleteBoundaryAfterExternalCancellation(
            context,
            WorkManagerHandoffCarrier.SCHEDULE_START,
            WorkManagerHandoffCarrier.START_BOUNDARY,
        )
        deleteBoundaryAfterExternalCancellation(
            context,
            WorkManagerHandoffCarrier.SCHEDULE_END,
            WorkManagerHandoffCarrier.END_BOUNDARY,
        )
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

    internal fun ensureConvergenceForRestore(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority,
    ) {
        convergenceScope.launch {
            enqueueAndAwaitForRestore(context, handoffId, authority).await()
        }
    }

    /**
     * A delayed WorkManager fallback accepted during Restore remains the
     * durable successor until ordinary startup reconciliation can retire its
     * carrier after the Restore pointer is released.
     */
    internal suspend fun restoreSuccessorAlreadyAccepted(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority,
    ): Boolean {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        val carrier = database(context).workManagerHandoffCarrierDao.get(handoffId)
            ?: return false
        if (carrier.state != WorkManagerHandoffCarrier.ACCEPTED) return false
        val current = workInfo(context, carrier.requestId) ?: return false
        return current.id.toString() == carrier.requestId && !current.state.isFinished
    }

    /**
     * A failed Restore fallback advances the carrier generation. On replay,
     * retry that durable fallback before attempting exact-alarm publication
     * again; the process-local retry job is never the owner.
     */
    internal suspend fun restoreSuccessorNeedsFallbackRetry(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority,
    ): Boolean {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        val carrier = database(context).workManagerHandoffCarrierDao.get(handoffId)
            ?: return false
        return carrier.state == WorkManagerHandoffCarrier.PENDING_ENQUEUE && carrier.attempt > 0
    }

    /**
     * Restore-owned callers must not retire the Restore pointer while an
     * exact-alarm failure still has only an in-memory retry. This suspend
     * boundary returns only after WorkManager Operation.result has accepted
     * the delayed successor and the carrier has been durably acknowledged.
     */
    internal suspend fun ensureConvergenceForRestoreAndAwait(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority,
    ): EnqueueOutcome {
        val outcome = enqueueAndAwaitForRestore(context, handoffId, authority).await()
        check(outcome.accepted) {
            "Restore scheduler successor was not accepted: ${outcome.kind}"
        }
        return outcome
    }

    internal fun clearForTesting() {
        val jobs = (attemptJobs.values.map { it as Job } + retryJobs.values.toList()).distinct()
        jobs.forEach { it.cancel() }
        runBlocking {
            withTimeoutOrNull(5_000L) {
                jobs.joinAll()
            }
        }
        attemptJobs.clear()
        retryJobs.clear()
        latestGenerationByBoundary.clear()
        boundaryLocks.clear()
        databaseForTesting = null
        workManagerForTesting = null
        enqueueOverrideForTesting = null
        workInfoOverrideForTesting = null
        cancelUniqueWorkOverrideForTesting = null
        cancelUniqueWorkOperationOverrideForTesting = null
    }

    /** A receiver can keep goAsync alive until this exact Operation completes. */
    suspend fun enqueueAndAwait(
        context: Context,
        handoffId: String,
    ): Deferred<EnqueueOutcome> = enqueueAndAwaitInternal(context, handoffId, null)

    internal suspend fun enqueueAndAwaitForRestore(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority,
    ): Deferred<EnqueueOutcome> {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        return enqueueAndAwaitInternal(context, handoffId, authority)
    }

    private suspend fun enqueueAndAwaitInternal(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority?,
    ): Deferred<EnqueueOutcome> {
        attemptJobs[handoffId]?.let { return it }
        val candidate = convergenceScope.async(start = CoroutineStart.LAZY) {
            performAttempt(context.applicationContext, handoffId, authority)
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
        if (RestoreGate.isRestoreInProgress(appContext)) return
        val dao = database(appContext).workManagerHandoffCarrierDao
        RestoreMutationAdmission.withOrdinaryMutation(appContext) {
            dao.deleteResolved()
        }
        dao.getOutstanding().forEach { carrier ->
            reconcileCarrier(appContext, carrier)
        }
    }

    suspend fun markObserveRetryResolved(
        context: Context,
        handoffId: String,
        requestId: String,
    ): Boolean {
        if (RestoreGate.isRestoreInProgress(context)) return false
        return RestoreMutationAdmission.withOrdinaryMutation(context) {
            val dao = database(context).workManagerHandoffCarrierDao
            val changed = dao.markResolved(handoffId, requestId, System.currentTimeMillis())
            if (changed == 0) {
                val current = dao.get(handoffId)
                if (current == null || current.state == WorkManagerHandoffCarrier.RESOLVED) {
                    return@withOrdinaryMutation true
                }
                return@withOrdinaryMutation false
            }
            dao.delete(handoffId)
            retryJobs.remove(handoffId)?.cancel()
            true
        }
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
        if (RestoreGate.isRestoreInProgress(context)) return
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
                    withCarrierMutation(context, null) {
                        database(context).workManagerHandoffCarrierDao.deleteAccepted(
                            carrier.handoffId,
                            carrier.requestId,
                        )
                    }
                }
            }
            return
        }

        when (workInfo?.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.SUCCEEDED -> {
                val accepted = withCarrierMutation(context, null) {
                    val dao = database(context).workManagerHandoffCarrierDao
                    val changed = dao.markAccepted(
                        carrier.handoffId,
                        carrier.requestId,
                        System.currentTimeMillis(),
                    )
                    if (changed > 0 && carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                        dao.deleteAccepted(
                            carrier.handoffId,
                            carrier.requestId,
                        )
                    }
                    changed
                }
                if (
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

    /**
     * Protects only the final Room/carrier mutation. WorkManager enqueue and
     * Operation.result observation remain outside this boundary.
     */
    private suspend fun <T> withCarrierMutation(
        context: Context,
        authority: RestoreReconciliationAuthority?,
        block: suspend () -> T,
    ): T = if (authority == null) {
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            block()
        }
    } else {
        RestoreMutationAdmission.withRestoreMutation {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
            block()
        }
    }

    private fun schedulerAuthorityAvailable(
        context: Context,
        authority: RestoreReconciliationAuthority?,
    ): Boolean {
        if (authority == null) return !RestoreGate.isRestoreInProgress(context)
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        return true
    }

    private suspend fun performAttempt(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority?,
    ): EnqueueOutcome {
        val dao = database(context).workManagerHandoffCarrierDao
        val carrier = dao.get(handoffId) ?: return EnqueueOutcome(OutcomeKind.SUPERSEDED)
        if (!schedulerAuthorityAvailable(context, authority)) {
            scheduleRetry(context, handoffId, authority)
            return EnqueueOutcome(OutcomeKind.RETRYING)
        }
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
        if (remainingDelay > 0L && authority == null) {
            scheduleRetry(context, handoffId, authority)
            return EnqueueOutcome(OutcomeKind.RETRYING)
        }

        val request = try {
            buildRequest(context, carrier)
        } catch (failure: Throwable) {
            return retryAfterFailure(context, carrier, failure, authority)
        }

        return try {
            // WorkManager enqueue acceptance is itself part of the ordinary
            // scheduler authority transfer. Keep the short enqueue/result/
            // carrier-finalization boundary under the same admission so
            // Restore cannot publish between an accepted external owner and
            // its durable acknowledgement.
            val result = if (authority == null) {
                RestoreMutationAdmission.withOrdinaryMutation(context) {
                    if (!schedulerAuthorityAvailable(context, null) || !isCurrentGeneration(carrier)) {
                        null
                    } else {
                        val operation = enqueueUniqueWork(
                            context = context,
                            uniqueWorkName = carrier.uniqueWorkName,
                            request = request,
                        )
                        val failure = awaitOperation(operation)
                        if (failure != null) {
                            retryAfterFailure(context, carrier, failure, null)
                        } else {
                            finalizeAccepted(context, carrier, null)
                        }
                    }
                }
            } else {
                RestoreMutationAdmission.withRestoreMutation {
                    RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
                    if (!schedulerAuthorityAvailable(context, authority) || !isCurrentGeneration(carrier)) {
                        null
                    } else {
                        val operation = enqueueUniqueWork(
                            context = context,
                            uniqueWorkName = carrier.uniqueWorkName,
                            request = request,
                        )
                        val failure = awaitOperation(operation)
                        if (failure != null) {
                            retryAfterFailure(context, carrier, failure, authority)
                        } else {
                            finalizeAccepted(context, carrier, authority)
                        }
                    }
                }
            }
            result ?: if (!schedulerAuthorityAvailable(context, authority)) {
                scheduleRetry(context, handoffId, authority)
                EnqueueOutcome(OutcomeKind.RETRYING)
            } else {
                EnqueueOutcome(OutcomeKind.SUPERSEDED)
            }
        } catch (failure: Throwable) {
            retryAfterFailure(context, carrier, failure, authority)
        }
    }

    private suspend fun finalizeAccepted(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
        authority: RestoreReconciliationAuthority?,
    ): EnqueueOutcome = withCarrierMutation(context, authority) {
        if (!isCurrentGeneration(carrier)) {
            return@withCarrierMutation EnqueueOutcome(OutcomeKind.SUPERSEDED)
        }
        val dao = database(context).workManagerHandoffCarrierDao
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
            if (carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD && authority == null) {
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

    private suspend fun retryAfterFailure(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
        failure: Throwable?,
        authority: RestoreReconciliationAuthority? = null,
    ): EnqueueOutcome {
        if (!schedulerAuthorityAvailable(context, authority)) {
            scheduleRetry(context, carrier.handoffId, authority)
            return EnqueueOutcome(OutcomeKind.RETRYING, failure)
        }

        val existingWork = workInfo(context, carrier.requestId)
        if (existingWork != null &&
            existingWork.state !in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)
        ) {
            val outcome = try {
                withCarrierMutation(context, authority) {
                    if (!isCurrentGeneration(carrier)) {
                        return@withCarrierMutation EnqueueOutcome(
                            OutcomeKind.SUPERSEDED,
                            failure,
                        )
                    }
                    val dao = database(context).workManagerHandoffCarrierDao
                    val accepted = dao.markAccepted(
                        carrier.handoffId,
                        carrier.requestId,
                        System.currentTimeMillis(),
                    )
                    if (accepted > 0 &&
                        carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD &&
                        authority == null
                    ) {
                        dao.deleteAccepted(carrier.handoffId, carrier.requestId)
                    }
                    if (accepted > 0) {
                        if (isCurrentGeneration(carrier)) {
                            EnqueueOutcome(OutcomeKind.ACCEPTED, failure)
                        } else {
                            EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
                        }
                    } else {
                        val current = dao.get(carrier.handoffId)
                        if (
                            current == null ||
                            current.requestId != carrier.requestId ||
                            !isCurrentGeneration(carrier)
                        ) {
                            // A newer REPLACE generation owns the exact
                            // semantic request.
                            EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
                        } else {
                            EnqueueOutcome(OutcomeKind.ACCEPTED, failure)
                        }
                    }
                }
            } catch (blocked: IllegalStateException) {
                if (authority == null && blocked.message == "Restore transaction is active") {
                    scheduleRetry(context, carrier.handoffId, authority)
                    return EnqueueOutcome(OutcomeKind.RETRYING, failure)
                }
                throw blocked
            }
            return outcome
        }

        val outcome = try {
            withCarrierMutation(context, authority) {
                val dao = database(context).workManagerHandoffCarrierDao
                val current = dao.get(carrier.handoffId)
                    ?: return@withCarrierMutation EnqueueOutcome(
                        OutcomeKind.SUPERSEDED,
                        failure,
                    )
                if (current.requestId != carrier.requestId || !isCurrentGeneration(carrier)) {
                    return@withCarrierMutation EnqueueOutcome(
                        OutcomeKind.SUPERSEDED,
                        failure,
                    )
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
                    if (
                        latest == null ||
                        latest.requestId != carrier.requestId ||
                        !isCurrentGeneration(carrier)
                    ) {
                        EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
                    } else {
                        EnqueueOutcome(OutcomeKind.RETRYING, failure)
                    }
                } else {
                    EnqueueOutcome(OutcomeKind.RETRYING, failure)
                }
            }
        } catch (blocked: IllegalStateException) {
            if (authority == null && blocked.message == "Restore transaction is active") {
                scheduleRetry(context, carrier.handoffId, authority)
                return EnqueueOutcome(OutcomeKind.RETRYING, failure)
            }
            throw blocked
        }
        if (outcome.kind == OutcomeKind.RETRYING) {
            scheduleRetry(context, carrier.handoffId, authority)
        }
        return outcome
    }
    private fun scheduleRetry(
        context: Context,
        handoffId: String,
        authority: RestoreReconciliationAuthority? = null,
    ) {
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

                val outcome = enqueueAndAwaitInternal(context, handoffId, authority).await()
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
        restoreAuthority: RestoreReconciliationAuthority? = null,
    ) {
        if (restoreAuthority == null) {
            RestoreMutationAdmission.withOrdinaryMutationBlocking(context) {
                replaceOutstandingAndInsertLocked(context, carrier, null)
            }
        } else {
            replaceOutstandingAndInsertLocked(context, carrier, restoreAuthority)
        }
    }

    private fun replaceOutstandingAndInsertLocked(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
        restoreAuthority: RestoreReconciliationAuthority? = null,
    ) {
        if (restoreAuthority == null) {
            check(!RestoreGate.isRestoreInProgress(context)) {
                "Restore transaction is active"
            }
        } else {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, restoreAuthority)
        }
        synchronized(boundaryLock(carrier.kind, carrier.boundary)) {
            if (restoreAuthority == null) {
                check(!RestoreGate.isRestoreInProgress(context)) {
                    "Restore transaction is active"
                }
            } else {
                RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, restoreAuthority)
            }
            val dao = database(context).workManagerHandoffCarrierDao
            val oldCarrier = blocking {
                dao.getOutstandingForBoundary(carrier.kind, carrier.boundary)
            }
            if (oldCarrier?.handoffId == carrier.handoffId) {
                latestGenerationByBoundary[boundaryKey(carrier.kind, carrier.boundary)] = carrier.handoffId
                return
            }
            oldCarrier?.let {
                retryJobs.remove(it.handoffId)?.cancel()
                attemptJobs.remove(it.handoffId)?.cancel()
            }
            val inserted = blocking {
                if (restoreAuthority == null) {
                    check(!RestoreGate.isRestoreInProgress(context)) {
                        "Restore transaction is active"
                    }
                } else {
                    RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, restoreAuthority)
                }
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
    private fun markBoundaryCancelledWithinOrdinaryMutation(
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
            // The tombstone is visible to any already-created attempt before
            // the external WorkManager cancellation is requested.
            latestGenerationByBoundary[boundaryKey(kind, boundary)] = CANCELLED_GENERATION
        }
    }

    private fun deleteBoundaryAfterExternalCancellation(
        context: Context,
        kind: String,
        boundary: String,
    ) {
        synchronized(boundaryLock(kind, boundary)) {
            blocking {
                database(context).workManagerHandoffCarrierDao
                    .deleteOutstandingForBoundary(kind, boundary)
            }
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

    private fun cancelUniqueWorkAndAwait(context: Context, uniqueWorkName: String) {
        val operation = cancelUniqueWorkOperationOverrideForTesting?.invoke(uniqueWorkName)
        if (operation != null) {
            operation.result.get(5_000L, TimeUnit.MILLISECONDS)
            return
        }
        val override = cancelUniqueWorkOverrideForTesting
        if (override != null) {
            override(uniqueWorkName)
            return
        }
        workManager(context).cancelUniqueWork(uniqueWorkName)
            .result
            .get(5_000L, TimeUnit.MILLISECONDS)
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
