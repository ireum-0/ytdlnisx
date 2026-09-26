package com.ireum.ytdl.work

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.BackoffPolicy
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
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.receiver.ObserveRetryDecisionReceiver
import com.ireum.ytdl.util.Extensions.calculateNextTimeForObserving
import com.ireum.ytdl.util.terminal.TerminalCommandMetadata
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

    internal data class ObserveRunCommit(
        val committed: Boolean,
        val recurrenceHandoffId: String? = null,
        val revokedMembershipDownloadIds: List<Long> = emptyList(),
        val currentOwnerHandoffId: String? = null,
        val supersededHandoffId: String? = null,
    )

    internal data class AutomaticKeywordOwnerChange(
        val handoffId: String? = null,
        val supersededHandoffIds: List<String> = emptyList(),
    )

    private const val TAG = "WorkManagerHandoffRecovery"
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

    @Volatile
    internal var cancelWorkByIdOperationOverrideForTesting: ((String) -> Operation)? = null

    /**
     * Stages a revision-bound automatic-keyword owner inside the caller's
     * Room transaction. Rule publication and enqueue debt therefore share a
     * durable commit boundary.
     */
    internal suspend fun stageAutomaticKeywordSyncWithinTransaction(
        db: DBManager,
        ruleId: Long,
        revision: Long,
        mode: String,
    ): AutomaticKeywordOwnerChange {
        require(ruleId > 0L && revision > 0L)
        require(mode == "APPLY_EXISTING" || mode == "BASELINE_ONLY")
        val boundary = ruleId.toString()
        val dao = db.workManagerHandoffCarrierDao
        val previous = dao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC,
            boundary,
        )
        val now = System.currentTimeMillis()
        previous?.let {
            dao.markSuperseded(it.handoffId, it.requestId, now)
        }
        val handoffId = UUID.randomUUID().toString()
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC,
            generationId = UUID.randomUUID().toString(),
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = "AUTOMATIC_KEYWORD_RULE_SYNC_$ruleId",
            sourceId = ruleId,
            decision = mode,
            sourceConfigurationGeneration = revision,
            boundary = boundary,
            createdAt = now,
            updatedAt = now,
        )
        check(dao.insert(carrier) != -1L) {
            "Automatic keyword handoff already exists: $handoffId"
        }
        return AutomaticKeywordOwnerChange(
            handoffId = handoffId,
            supersededHandoffIds = listOfNotNull(previous?.handoffId),
        )
    }

    /** Marks the current per-rule request stale in the caller's Room transaction. */
    internal suspend fun supersedeAutomaticKeywordSyncWithinTransaction(
        db: DBManager,
        ruleId: Long,
    ): AutomaticKeywordOwnerChange {
        val boundary = ruleId.toString()
        val previous = db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC,
            boundary,
        ) ?: return AutomaticKeywordOwnerChange()
        db.workManagerHandoffCarrierDao.markSuperseded(
            previous.handoffId,
            previous.requestId,
            System.currentTimeMillis(),
        )
        return AutomaticKeywordOwnerChange(
            supersededHandoffIds = listOf(previous.handoffId),
        )
    }

    /** Starts asynchronous publication only after the owner transaction commits. */
    internal fun dispatchAutomaticKeywordOwnerChange(
        context: Context,
        change: AutomaticKeywordOwnerChange,
    ) {
        change.supersededHandoffIds.forEach { handoffId ->
            retryJobs.remove(handoffId)?.cancel()
            attemptJobs.remove(handoffId)?.cancel()
            convergenceScope.launch(Dispatchers.IO) {
                val carrier = database(context).workManagerHandoffCarrierDao.get(handoffId)
                if (carrier?.state == WorkManagerHandoffCarrier.SUPERSEDED) {
                    reconcileSuperseded(context.applicationContext, carrier)
                }
            }
        }
        change.handoffId?.let { handoffId ->
            convergenceScope.launch {
                enqueueAndAwaitInternal(context.applicationContext, handoffId, null).await()
            }
        }
    }

    internal fun terminalCommandFingerprint(command: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(command.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun terminalWorkName(terminalId: Long): String = terminalId.toString()

    /** Stages the exact Terminal dispatch owner in the caller's Room transaction. */
    internal suspend fun stageTerminalDispatchWithinTransaction(
        db: DBManager,
        terminalId: Long,
        command: String,
    ): String {
        require(terminalId > 0L)
        val carrierDao = db.workManagerHandoffCarrierDao
        val boundary = terminalId.toString()
        val previous = carrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
            boundary,
        )
        previous?.let {
            carrierDao.markSuperseded(it.handoffId, it.requestId, System.currentTimeMillis())
        }
        val handoffId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
            generationId = handoffId,
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = terminalWorkName(terminalId),
            sourceId = terminalId,
            // The generic carrier has no command column; confirmedUrl is its
            // existing opaque payload slot, while the fingerprint remains the
            // semantic identity checked at every boundary.
            confirmedUrl = command,
            decision = TERMINAL_DISPATCH_DECISION,
            configFingerprint = terminalCommandFingerprint(command),
            sourceConfigurationGeneration = 1L,
            boundary = boundary,
            createdAt = now,
            updatedAt = now,
        )
        check(carrierDao.insert(carrier) != -1L) {
            "Terminal dispatch handoff already exists: $handoffId"
        }
        return handoffId
    }

    internal fun dispatchTerminalDispatch(context: Context, terminalId: Long) {
        convergenceScope.launch {
            val handoffId = database(context).workManagerHandoffCarrierDao
                .getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                    terminalId.toString(),
                )?.handoffId ?: return@launch
            enqueueAndAwait(context.applicationContext, handoffId).await()
        }
    }

    internal data class TerminalDispatchCancellationResult(
        val dispatchSuperseded: Boolean,
        val workManagerCancellationAcknowledged: Boolean,
    )

    private data class TerminalDispatchSupersession(
        val established: Boolean,
        val requestId: String?,
    )

    /** Supersedes first, then cancels only the exact old request identity. */
    internal suspend fun cancelTerminalDispatch(
        context: Context,
        terminalId: Long,
    ): TerminalDispatchCancellationResult {
        val supersession = runCatching {
            RestoreMutationAdmission.withOrdinaryMutation(context) {
                database(context).withTransaction {
                    val dao = database(context).workManagerHandoffCarrierDao
                    val carrier = dao.getOutstandingForBoundary(
                        WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                        terminalId.toString(),
                    )
                    if (carrier == null) {
                        TerminalDispatchSupersession(established = true, requestId = null)
                    } else {
                        val changed = dao.markSuperseded(
                            carrier.handoffId,
                            carrier.requestId,
                            System.currentTimeMillis(),
                        )
                        TerminalDispatchSupersession(
                            established = changed == 1,
                            requestId = carrier.requestId.takeIf { changed == 1 },
                        )
                    }
                }
            }
        }.getOrNull() ?: return TerminalDispatchCancellationResult(
            dispatchSuperseded = false,
            workManagerCancellationAcknowledged = false,
        )

        if (!supersession.established) {
            return TerminalDispatchCancellationResult(
                dispatchSuperseded = false,
                workManagerCancellationAcknowledged = false,
            )
        }

        val workManagerCancellationAcknowledged = runCatching {
            if (supersession.requestId != null) {
                cancelWorkByIdAndAwait(context, supersession.requestId)
            } else {
                cancelUniqueWorkAndAwait(context, terminalWorkName(terminalId))
            }
        }.isSuccess
        return TerminalDispatchCancellationResult(
            dispatchSuperseded = true,
            workManagerCancellationAcknowledged = workManagerCancellationAcknowledged,
        )
    }

    /** Resolves only the exact worker that also converged the Terminal row. */
    internal suspend fun resolveTerminalDispatch(
        context: Context,
        terminalId: Long,
        handoffId: String,
        requestId: String,
        generationId: String,
        boundary: String,
        commandFingerprint: String,
    ): Boolean {
        if (
            terminalId <= 0L || handoffId.isBlank() || requestId.isBlank() ||
            generationId.isBlank() || boundary.isBlank() || commandFingerprint.isBlank()
        ) return false
        val execution = TerminalExecutionRecovery.read(context, terminalId)
        if (execution != null && !execution.terminal) return false
        return RestoreMutationAdmission.withOrdinaryMutation(context) {
            val db = database(context)
            db.withTransaction {
                if (db.terminalDao.getTerminalById(terminalId) != null) return@withTransaction false
                val dao = db.workManagerHandoffCarrierDao
                val carrier = dao.get(handoffId) ?: return@withTransaction false
                val current = dao.getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                    boundary,
                )
                if (
                    carrier.kind != WorkManagerHandoffCarrier.TERMINAL_DISPATCH ||
                    carrier.sourceId != terminalId ||
                    carrier.requestId != requestId ||
                    carrier.generationId != generationId ||
                    carrier.boundary != boundary ||
                    carrier.uniqueWorkName != terminalWorkName(terminalId) ||
                    carrier.configFingerprint != terminalCommandFingerprint(carrier.confirmedUrl) ||
                    carrier.configFingerprint != commandFingerprint ||
                    current?.handoffId != handoffId ||
                    current.requestId != requestId
                ) {
                    return@withTransaction false
                }
                dao.markTerminalResolved(handoffId, requestId, System.currentTimeMillis()) == 1
            }
        }
    }

    internal suspend fun isCurrentTerminalDispatchRequest(
        context: Context,
        terminalId: Long,
        command: String,
        handoffId: String,
        requestId: String,
        generationId: String,
        boundary: String,
        commandFingerprint: String,
        workRequestId: String,
    ): Boolean {
        if (
            RestoreGate.isRestoreInProgress(context) ||
            terminalId <= 0L || command.isBlank() || handoffId.isBlank() || requestId.isBlank() ||
            generationId.isBlank() || boundary != terminalId.toString() ||
            commandFingerprint.isBlank() || requestId != workRequestId
        ) return false
        // Final execution gate.  A command with no durable destination authority
        // is refused here even when every identity field matches, because
        // matching identities over an incomplete record prove nothing about the
        // original provider.  The planner would otherwise resolve the current
        // preference and publish this old Terminal to a newer archive.
        if (!hasDurableTerminalOutputAuthority(command)) return false
        val db = database(context)
        val carrier = db.workManagerHandoffCarrierDao.get(handoffId) ?: return false
        if (
            carrier.kind != WorkManagerHandoffCarrier.TERMINAL_DISPATCH ||
            carrier.sourceId != terminalId ||
            carrier.requestId != requestId ||
            carrier.generationId != generationId ||
            carrier.boundary != boundary ||
            carrier.uniqueWorkName != terminalWorkName(terminalId) ||
            carrier.decision != TERMINAL_DISPATCH_DECISION ||
            carrier.confirmedUrl != command ||
            commandFingerprint != terminalCommandFingerprint(command) ||
            carrier.configFingerprint != commandFingerprint ||
            carrier.state !in setOf(
                WorkManagerHandoffCarrier.PENDING_ENQUEUE,
                WorkManagerHandoffCarrier.ACCEPTED,
            )
        ) return false
        val current = db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
            boundary,
        )
        if (current?.handoffId != handoffId || current.requestId != requestId) return false
        val terminal = db.terminalDao.getTerminalById(terminalId) ?: return false
        return terminal.command == command
    }

    /**
     * Worker-boundary validation for the exact rule revision, semantic mode,
     * carrier generation, WorkRequest UUID, and current durable owner.
     */
    internal suspend fun isCurrentAutomaticKeywordSyncRequest(
        context: Context,
        ruleId: Long,
        revision: Long,
        mode: String,
        handoffId: String,
        requestId: String,
        generationId: String,
        boundary: String,
        workRequestId: String,
    ): Boolean {
        if (RestoreGate.isRestoreInProgress(context) ||
            requestId.isBlank() || generationId.isBlank() || handoffId.isBlank() ||
            requestId != workRequestId || boundary != ruleId.toString() ||
            mode !in setOf("APPLY_EXISTING", "BASELINE_ONLY")
        ) return false
        val dao = database(context).workManagerHandoffCarrierDao
        val carrier = dao.get(handoffId) ?: return false
        if (carrier.kind != WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC ||
            carrier.sourceId != ruleId || carrier.sourceConfigurationGeneration != revision ||
            carrier.decision != mode || carrier.requestId != requestId ||
            carrier.generationId != generationId || carrier.boundary != boundary ||
            carrier.uniqueWorkName != "AUTOMATIC_KEYWORD_RULE_SYNC_$ruleId" ||
            carrier.state !in setOf(
                WorkManagerHandoffCarrier.PENDING_ENQUEUE,
                WorkManagerHandoffCarrier.ACCEPTED,
            )
        ) return false
        val current = dao.getOutstandingForBoundary(carrier.kind, boundary)
        if (current?.handoffId != handoffId || current.requestId != requestId) return false
        val rule = database(context).automaticKeywordRuleDao.getRule(ruleId) ?: return false
        return rule.enabled &&
            rule.revision == revision &&
            mode == expectedAutomaticKeywordMode(rule.pendingApplyToExisting)
    }

    /**
     * Commits worker-owned runtime state and its ordinary recurring successor
     * debt atomically. The caller holds ordinary mutation admission; no
     * WorkManager operation is awaited while this transaction/authority is held.
     */
    internal suspend fun commitObserveRunAndStageRecurrence(
        context: Context,
        item: ObserveSourcesItem,
        revoke: Boolean,
        recurrenceHandoffId: String,
        recurrenceRequestId: String,
    ): ObserveRunCommit {
        val appContext = context.applicationContext
        val hasHandoffId = recurrenceHandoffId.isNotBlank()
        val hasRequestId = recurrenceRequestId.isNotBlank()
        if (hasHandoffId != hasRequestId || RestoreGate.isRestoreInProgress(appContext)) {
            return ObserveRunCommit(committed = false)
        }

        val db = database(appContext)
        val carrierDao = db.workManagerHandoffCarrierDao
        val boundary = item.id.toString()
        val result = db.withTransaction {
            if (RestoreGate.isRestoreInProgress(appContext)) {
                return@withTransaction ObserveRunCommit(committed = false)
            }
            val currentSource = db.observeSourcesDao.getByIDOrNull(item.id)
                ?: return@withTransaction ObserveRunCommit(committed = false)
            if (currentSource.status != ObserveSourcesRepository.SourceStatus.ACTIVE ||
                currentSource.configurationGeneration != item.configurationGeneration
            ) {
                return@withTransaction ObserveRunCommit(committed = false)
            }

            val outstanding = carrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.OBSERVE_RECURRENCE,
                boundary,
            )
            if (hasHandoffId &&
                (outstanding?.handoffId != recurrenceHandoffId ||
                    outstanding.requestId != recurrenceRequestId ||
                    outstanding.sourceId != item.id ||
                    outstanding.sourceConfigurationGeneration != item.configurationGeneration)
            ) {
                return@withTransaction ObserveRunCommit(committed = false)
            }

            if (revoke) {
                val revokedIds = db.observeSourcesDao.finishAndRevokeAndCancelWaitingIfGeneration(item)
                    ?: return@withTransaction ObserveRunCommit(committed = false)
                carrierDao.deleteOutstandingForBoundary(
                    WorkManagerHandoffCarrier.OBSERVE_RECURRENCE,
                    boundary,
                )
                return@withTransaction ObserveRunCommit(
                    committed = true,
                    revokedMembershipDownloadIds = revokedIds,
                    supersededHandoffId = outstanding?.handoffId,
                )
            }

            val updated = db.observeSourcesDao.updateRuntimeIfGeneration(
                id = item.id,
                expectedGeneration = item.configurationGeneration,
                runCount = item.runCount,
                ignoredLinks = item.ignoredLinks,
                alreadyProcessedLinks = item.alreadyProcessedLinks,
                runHistory = item.runHistory,
                runInProgress = item.runInProgress,
                currentRunStatus = item.currentRunStatus,
                retryPromptedLinks = item.retryPromptedLinks,
                observedLinks = item.observedLinks,
            )
            if (updated != 1) return@withTransaction ObserveRunCommit(committed = false)

            val sourceAfterRuntime = db.observeSourcesDao.getByIDOrNull(item.id)
                ?.takeIf {
                    it.status == ObserveSourcesRepository.SourceStatus.ACTIVE &&
                        it.configurationGeneration == item.configurationGeneration
                }
                ?: return@withTransaction ObserveRunCommit(committed = false)
            val ownerAfterRuntime = carrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.OBSERVE_RECURRENCE,
                boundary,
            )

            if (!hasHandoffId && ownerAfterRuntime != null &&
                ownerAfterRuntime.sourceId == item.id &&
                ownerAfterRuntime.sourceConfigurationGeneration == item.configurationGeneration
            ) {
                return@withTransaction ObserveRunCommit(
                    committed = true,
                    currentOwnerHandoffId = ownerAfterRuntime.handoffId,
                )
            }

            var supersededHandoffId: String? = null
            if (ownerAfterRuntime != null) {
                supersededHandoffId = ownerAfterRuntime.handoffId
                if (hasHandoffId) {
                    carrierDao.deleteExact(ownerAfterRuntime.handoffId, ownerAfterRuntime.requestId)
                } else {
                    carrierDao.markSuperseded(
                        ownerAfterRuntime.handoffId,
                        ownerAfterRuntime.requestId,
                        System.currentTimeMillis(),
                    )
                }
            }

            val handoffId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val carrier = WorkManagerHandoffCarrier(
                handoffId = handoffId,
                kind = WorkManagerHandoffCarrier.OBSERVE_RECURRENCE,
                generationId = handoffId,
                requestId = UUID.randomUUID().toString(),
                uniqueWorkName = "OBSERVE${item.id}",
                sourceId = item.id,
                sourceConfigurationGeneration = item.configurationGeneration,
                boundary = boundary,
                notBeforeAt = sourceAfterRuntime.calculateNextTimeForObserving(),
                createdAt = now,
                updatedAt = now,
            )
            check(carrierDao.insert(carrier) != -1L) {
                "Observe recurrence carrier already exists: $handoffId"
            }
            ObserveRunCommit(
                committed = true,
                recurrenceHandoffId = handoffId,
                supersededHandoffId = supersededHandoffId,
            )
        }

        if (result.committed) {
            val key = boundaryKey(WorkManagerHandoffCarrier.OBSERVE_RECURRENCE, boundary)
            when {
                result.recurrenceHandoffId != null ->
                    latestGenerationByBoundary[key] = result.recurrenceHandoffId
                result.currentOwnerHandoffId != null ->
                    latestGenerationByBoundary[key] = result.currentOwnerHandoffId
                revoke -> latestGenerationByBoundary[key] = CANCELLED_GENERATION
            }
            result.supersededHandoffId?.let { oldId ->
                retryJobs.remove(oldId)?.cancel()
                attemptJobs.remove(oldId)?.cancel()
            }
        }
        return result
    }

    internal suspend fun isCurrentObserveRecurrenceRequest(
        context: Context,
        handoffId: String,
        requestId: String,
        sourceId: Long,
        sourceConfigurationGeneration: Long,
    ): Boolean {
        if (handoffId.isBlank() || requestId.isBlank() || RestoreGate.isRestoreInProgress(context)) {
            return false
        }
        val carrier = database(context).workManagerHandoffCarrierDao.get(handoffId)
            ?: return false
        return carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE &&
            carrier.requestId == requestId &&
            carrier.sourceId == sourceId &&
            carrier.sourceConfigurationGeneration == sourceConfigurationGeneration &&
            carrier.state in setOf(
                WorkManagerHandoffCarrier.PENDING_ENQUEUE,
                WorkManagerHandoffCarrier.ACCEPTED,
            ) &&
            isCurrentObserveRecurrenceAuthority(context, carrier) &&
            isDurablyCurrentRetainedCarrier(context, carrier) &&
            isCurrentGeneration(carrier)
    }

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
        sourceConfigurationGeneration: Long,
        confirmedUrl: String,
        configFingerprint: String,
    ): String {
        check(!RestoreGate.isRestoreInProgress(context)) {
            "Restore transaction is active"
        }
        val canonicalIdentity = "$sourceId|$sourceConfigurationGeneration|$confirmedUrl|$configFingerprint|" +
            ObserveRetryDecisionReceiver.ACTION_DOWNLOAD
        val handoffId = UUID.nameUUIDFromBytes(canonicalIdentity.toByteArray(StandardCharsets.UTF_8)).toString()
        return RestoreMutationAdmission.withOrdinaryMutationBlocking(context) {
            val dao = database(context).workManagerHandoffCarrierDao
            val source = blocking { database(context).observeSourcesDao.getByIDOrNull(sourceId) }
            check(
                source != null &&
                    source.status == ObserveSourcesRepository.SourceStatus.ACTIVE &&
                    source.configurationGeneration == sourceConfigurationGeneration &&
                    observeConfigFingerprint(source) == configFingerprint
            ) { "Observe Retry notification belongs to a stale source configuration" }
            check(!RestoreGate.isRestoreInProgress(context)) {
                "Restore transaction is active"
            }
            val existing = blocking {
                dao.getOutstandingObserveRetry(
                    sourceId = sourceId,
                    confirmedUrl = confirmedUrl,
                    decision = ObserveRetryDecisionReceiver.ACTION_DOWNLOAD,
                    configFingerprint = configFingerprint,
                    sourceConfigurationGeneration = sourceConfigurationGeneration,
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
                sourceConfigurationGeneration = sourceConfigurationGeneration,
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
        cancelWorkByIdOperationOverrideForTesting = null
    }

    internal enum class SchedulerWorkRequestDisposition {
        CURRENT,
        PENDING,
        STALE,
    }

    /**
     * Validates the exact durable scheduler owner at the worker boundary.
     * The process-local generation map is deliberately not consulted here.
     */
    internal suspend fun schedulerWorkRequestDisposition(
        context: Context,
        handoffId: String,
        requestId: String,
        generationId: String,
        boundary: String,
        kind: String,
        workRequestId: String,
    ): SchedulerWorkRequestDisposition {
        val carrier = database(context).workManagerHandoffCarrierDao.get(handoffId)
            ?: return SchedulerWorkRequestDisposition.STALE
        if (
            carrier.kind != kind ||
            carrier.requestId != requestId ||
            (generationId.isNotBlank() && carrier.generationId != generationId) ||
            (boundary.isNotBlank() && carrier.boundary != boundary) ||
            requestId != workRequestId
        ) {
            return SchedulerWorkRequestDisposition.STALE
        }
        val current = database(context).workManagerHandoffCarrierDao
            .getOutstandingForBoundary(kind, boundary.ifBlank { carrier.boundary })
        if (current?.handoffId != handoffId || current.requestId != requestId) {
            return SchedulerWorkRequestDisposition.STALE
        }
        return when (carrier.state) {
            WorkManagerHandoffCarrier.ACCEPTED -> SchedulerWorkRequestDisposition.CURRENT
            WorkManagerHandoffCarrier.PENDING_ENQUEUE -> SchedulerWorkRequestDisposition.PENDING
            else -> SchedulerWorkRequestDisposition.STALE
        }
    }

    /**
     * Retires only the exact accepted scheduler carrier consumed by a worker.
     * A newer generation or a Restore-owned carrier is never deleted here.
     */
    internal suspend fun retireSchedulerWorkRequest(
        context: Context,
        handoffId: String,
        requestId: String,
    ) {
        if (RestoreGate.isRestoreInProgress(context)) return
        runCatching {
            RestoreMutationAdmission.withOrdinaryMutation(context) {
                database(context).workManagerHandoffCarrierDao.deleteAccepted(
                    handoffId,
                    requestId,
                )
            }
        }
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
        reconcileAutomaticKeywordRules(appContext)
        reconcileTerminalDispatches(appContext)
        dao.getSuperseded().forEach { carrier ->
            reconcileSuperseded(appContext, carrier)
        }
        dao.getOutstanding().forEach { carrier ->
            reconcileCarrier(appContext, carrier)
        }
    }

    /** Reconstructs the durable owner for legacy or interrupted queued syncs. */
    private suspend fun reconcileAutomaticKeywordRules(context: Context) {
        val db = database(context)
        val changes = RestoreMutationAdmission.withOrdinaryMutation(context) {
            db.withTransaction {
                buildList {
                    db.automaticKeywordRuleDao.getAllRules().forEach { rule ->
                        val currentOwner = db.workManagerHandoffCarrierDao
                            .getOutstandingForBoundary(
                                WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC,
                                rule.id.toString(),
                            )
                        if (!rule.enabled) {
                            val superseded = supersedeAutomaticKeywordSyncWithinTransaction(db, rule.id)
                            if (rule.manualSyncStatus in setOf(
                                    AutomaticKeywordSyncStatus.QUEUED,
                                    AutomaticKeywordSyncStatus.RUNNING,
                                )
                            ) {
                                db.automaticKeywordRuleDao.updateManualSyncStatusIfRevision(
                                    rule.id,
                                    rule.revision,
                                    AutomaticKeywordSyncStatus.NEVER,
                                    System.currentTimeMillis(),
                                    AutomaticKeywordSyncError.NONE,
                                )
                            }
                            if (superseded.supersededHandoffIds.isNotEmpty()) add(superseded)
                            return@forEach
                        }
                        if (rule.manualSyncStatus !in setOf(
                                AutomaticKeywordSyncStatus.QUEUED,
                                AutomaticKeywordSyncStatus.RUNNING,
                            )
                        ) return@forEach

                        val expectedMode = expectedAutomaticKeywordMode(rule.pendingApplyToExisting)
                        val ownerMatches = currentOwner?.let {
                            it.kind == WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC &&
                                it.sourceId == rule.id &&
                                it.sourceConfigurationGeneration == rule.revision &&
                                it.boundary == rule.id.toString() &&
                                it.uniqueWorkName == "AUTOMATIC_KEYWORD_RULE_SYNC_${rule.id}" &&
                                it.decision == expectedMode
                        } == true
                        if (ownerMatches) return@forEach

                        if (rule.manualSyncStatus != AutomaticKeywordSyncStatus.QUEUED) {
                            check(
                                db.automaticKeywordRuleDao.updateManualSyncStatusIfRevision(
                                    rule.id,
                                    rule.revision,
                                    AutomaticKeywordSyncStatus.QUEUED,
                                    System.currentTimeMillis(),
                                    AutomaticKeywordSyncError.NONE,
                                ) == 1
                            ) { "Automatic keyword sync status changed during startup recovery" }
                        }
                        val mode = expectedMode
                        add(
                            stageAutomaticKeywordSyncWithinTransaction(
                                db,
                                rule.id,
                                rule.revision,
                                mode,
                            ),
                        )
                    }
                }
            }
        }
        changes.forEach { dispatchAutomaticKeywordOwnerChange(context, it) }
    }
    /**
     * Reconstructs one durable owner for every ordinary Terminal row.  A
     * superseded tombstone is treated as a cancellation/recovery barrier; it
     * must not turn an unresolved cancellation back into a fresh command.
     */
    private suspend fun reconcileTerminalDispatches(context: Context) {
        val db = database(context)
        val handoffIds = RestoreMutationAdmission.withOrdinaryMutation(context) {
            db.withTransaction {
                val supersededBoundaries = db.workManagerHandoffCarrierDao
                    .getSuperseded()
                    .asSequence()
                    .filter { it.kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH }
                    .map { it.boundary }
                    .toSet()
                buildList {
                    db.terminalDao.getActiveTerminalDownloads().forEach { terminal ->
                        if (TerminalExecutionRecovery.hasRecordFile(context, terminal.id)) {
                            return@forEach
                        }
                        val boundary = terminal.id.toString()
                        val current = db.workManagerHandoffCarrierDao
                            .getOutstandingForBoundary(
                                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                                boundary,
                            )
                        // A persisted command with no durable destination
                        // authority is never given a runnable carrier, and any
                        // outstanding owner is revoked instead. The row and its
                        // command/log are preserved: this is a non-runnable
                        // disposition, not a deletion.
                        if (!hasDurableTerminalOutputAuthority(terminal.command)) {
                            current?.let {
                                db.workManagerHandoffCarrierDao.markSuperseded(
                                    it.handoffId,
                                    it.requestId,
                                    System.currentTimeMillis(),
                                )
                            }
                            return@forEach
                        }
                        val ownerMatches = current?.let {
                            it.sourceId == terminal.id &&
                                it.requestId.isNotBlank() &&
                                it.generationId.isNotBlank() &&
                                it.uniqueWorkName == terminalWorkName(terminal.id) &&
                                it.boundary == boundary &&
                                it.decision == TERMINAL_DISPATCH_DECISION &&
                                it.sourceConfigurationGeneration == 1L &&
                                it.confirmedUrl == terminal.command &&
                                it.configFingerprint == terminalCommandFingerprint(terminal.command)
                        } == true
                        if (ownerMatches) {
                            add(checkNotNull(current).handoffId)
                        } else if (boundary !in supersededBoundaries) {
                            current?.let {
                                db.workManagerHandoffCarrierDao.markSuperseded(
                                    it.handoffId,
                                    it.requestId,
                                    System.currentTimeMillis(),
                                )
                            }
                            add(
                                stageTerminalDispatchWithinTransaction(
                                    db,
                                    terminal.id,
                                    terminal.command,
                                ),
                            )
                        }
                    }
                }
            }
        }
        handoffIds.forEach { handoffId -> dispatchTerminalHandoff(context, handoffId) }
    }

    private fun dispatchTerminalHandoff(context: Context, handoffId: String) {
        convergenceScope.launch {
            enqueueAndAwait(context.applicationContext, handoffId).await()
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

    private fun retainsCarrierUntilWorkerTerminal(kind: String): Boolean =
        kind == WorkManagerHandoffCarrier.SCHEDULE_START ||
            kind == WorkManagerHandoffCarrier.SCHEDULE_END ||
            kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE ||
            kind == WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC ||
            kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH
    private suspend fun reconcileSuperseded(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ) {
        if (RestoreGate.isRestoreInProgress(context)) return
        val existing = workInfo(context, carrier.requestId)
        if (existing != null && !existing.state.isFinished) {
            runCatching { cancelWorkByIdAndAwait(context, carrier.requestId) }
        }
        val afterCancellation = workInfo(context, carrier.requestId)
        if (afterCancellation == null && retainsSupersededTombstone(carrier.kind)) {
            // Keep the exact request tombstone until WorkManager proves that
            // the request reached a terminal state.  A null WorkInfo is not
            // proof that an in-flight enqueue cannot accept late.
            return
        }
        if (afterCancellation == null || afterCancellation.state.isFinished) {
            RestoreMutationAdmission.withOrdinaryMutation(context) {
                database(context).workManagerHandoffCarrierDao.deleteExact(
                    carrier.handoffId,
                    carrier.requestId,
                )
            }
        }
    }

    private fun retainsSupersededTombstone(kind: String): Boolean =
        kind == WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC ||
            kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH
    private suspend fun reconcileCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ) {
        if (RestoreGate.isRestoreInProgress(context)) return
        if (retireStaleObserveCarrier(context, carrier)) return
        if (!isCurrentGeneration(carrier)) return
        if (!isCurrentObserveExecutionAuthority(context, carrier) ||
            (carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE &&
                !isDurablyCurrentRetainedCarrier(context, carrier))
        ) {
            retireStaleObserveCarrier(context, carrier)
            return
        }
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
                carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE &&
                    workInfo?.state?.isFinished == true -> {
                    retryAfterFailure(context, carrier, null)
                }
                carrier.kind == WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC &&
                    workInfo?.state == WorkInfo.State.SUCCEEDED -> {
                    withCarrierMutation(context, null) {
                        database(context).workManagerHandoffCarrierDao.deleteExact(
                            carrier.handoffId,
                            carrier.requestId,
                        )
                    }
                }
                carrier.kind == WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC &&
                    workInfo?.state in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED) -> {
                    retryAfterFailure(context, carrier, null)
                }
                carrier.kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH &&
                    workInfo?.state in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED) -> {
                    retryAfterFailure(context, carrier, null)
                }
                carrier.kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH &&
                    workInfo?.state == WorkInfo.State.SUCCEEDED -> Unit
                retainsCarrierUntilWorkerTerminal(carrier.kind) && workInfo == null -> {
                    // Operation.result acceptance is durable authority; a transiently
                    // absent WorkInfo must not advance the request identity and
                    // orphan a late-accepted owner.
                    Unit
                }
                retainsCarrierUntilWorkerTerminal(carrier.kind) &&
                    workInfo != null && !workInfo.state.isFinished -> Unit
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
        if (carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE &&
            workInfo?.state == WorkInfo.State.SUCCEEDED
        ) {
            retryAfterFailure(context, carrier, null)
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
                    if (changed > 0 && !retainsCarrierUntilWorkerTerminal(carrier.kind) && carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                        dao.deleteAccepted(
                            carrier.handoffId,
                            carrier.requestId,
                        )
                    }
                    changed
                }
                if (
                    accepted > 0 &&
                    workInfo.state == WorkInfo.State.SUCCEEDED
                ) {
                    when (carrier.kind) {
                        WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD ->
                            markObserveRetryResolved(context, carrier.handoffId, carrier.requestId)
                        WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC ->
                            withCarrierMutation(context, null) {
                                database(context).workManagerHandoffCarrierDao.deleteExact(
                                    carrier.handoffId,
                                    carrier.requestId,
                                )
                            }
                    }
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

    private suspend fun isDurablyCurrentRetainedCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean {
        if (!retainsCarrierUntilWorkerTerminal(carrier.kind)) return true
        val current = database(context).workManagerHandoffCarrierDao
            .getOutstandingForBoundary(carrier.kind, carrier.boundary)
        return current?.handoffId == carrier.handoffId && current.requestId == carrier.requestId
    }

    /** Observe retry carriers have source-owned authority that must survive process death. */
    private suspend fun isCurrentObserveRetryAuthority(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) return true
        val source = database(context).observeSourcesDao.getByIDOrNull(carrier.sourceId) ?: return false
        return source.status == ObserveSourcesRepository.SourceStatus.ACTIVE &&
            source.configurationGeneration == carrier.sourceConfigurationGeneration &&
            observeConfigFingerprint(source) == carrier.configFingerprint
    }

    private suspend fun isCurrentObserveRecurrenceAuthority(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RECURRENCE) return true
        val source = database(context).observeSourcesDao.getByIDOrNull(carrier.sourceId) ?: return false
        return carrier.boundary == carrier.sourceId.toString() &&
            carrier.uniqueWorkName == "OBSERVE${carrier.sourceId}" &&
            source.status == ObserveSourcesRepository.SourceStatus.ACTIVE &&
            source.configurationGeneration == carrier.sourceConfigurationGeneration
    }

    private suspend fun isCurrentAutomaticKeywordAuthority(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC) return true
        val rule = database(context).automaticKeywordRuleDao.getRule(carrier.sourceId) ?: return false
        val expectedMode = expectedAutomaticKeywordMode(rule.pendingApplyToExisting)
        return carrier.boundary == carrier.sourceId.toString() &&
            carrier.uniqueWorkName == "AUTOMATIC_KEYWORD_RULE_SYNC_${carrier.sourceId}" &&
            carrier.generationId.isNotBlank() &&
            carrier.decision == expectedMode &&
            rule.enabled && rule.revision == carrier.sourceConfigurationGeneration
    }

    private fun expectedAutomaticKeywordMode(pendingApplyToExisting: Boolean): String =
        if (pendingApplyToExisting) "APPLY_EXISTING" else "BASELINE_ONLY"

    private suspend fun isCurrentTerminalDispatchAuthority(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.TERMINAL_DISPATCH) return true
        val terminal = database(context).terminalDao.getTerminalById(carrier.sourceId)
            ?: return false
        return carrier.requestId.isNotBlank() &&
            carrier.boundary == carrier.sourceId.toString() &&
            carrier.uniqueWorkName == terminalWorkName(carrier.sourceId) &&
            carrier.generationId.isNotBlank() &&
            carrier.decision == TERMINAL_DISPATCH_DECISION &&
            carrier.sourceConfigurationGeneration == 1L &&
            carrier.confirmedUrl == terminal.command &&
            carrier.configFingerprint == terminalCommandFingerprint(terminal.command) &&
            hasDurableTerminalOutputAuthority(terminal.command)
    }

    /**
     * Whether a durably stored Terminal command proves the output authority it
     * would execute under.
     *
     * A pre-materializer command that carries neither explicit provider
     * metadata nor an authored native destination has no durable record of the
     * configured provider it was created under, and the original provider is
     * unrecoverable from the row and carrier. Such a command is internally
     * consistent and still incomplete, so it must never become runnable: the
     * current preference is not evidence of its original authority.
     */
    private fun hasDurableTerminalOutputAuthority(command: String): Boolean =
        when (TerminalCommandMetadata.classifyDurable(command)) {
            TerminalCommandMetadata.DurableAuthority.CurrentFormat -> true
            TerminalCommandMetadata.DurableAuthority.SelfBound -> true
            TerminalCommandMetadata.DurableAuthority.Ambiguous -> {
                Log.w(
                    TAG,
                    "Refusing Terminal dispatch for a persisted command with no durable " +
                        "destination authority; it will not inherit the current preference",
                )
                false
            }
        }

    private suspend fun isCurrentObserveExecutionAuthority(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean = when (carrier.kind) {
        WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD ->
            isCurrentObserveRetryAuthority(context, carrier)
        WorkManagerHandoffCarrier.OBSERVE_RECURRENCE ->
            isCurrentObserveRecurrenceAuthority(context, carrier)
        WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC ->
            isCurrentAutomaticKeywordAuthority(context, carrier)
        WorkManagerHandoffCarrier.TERMINAL_DISPATCH ->
            isCurrentTerminalDispatchAuthority(context, carrier)
        else -> true
    }

    /** Retires a stale exact request without ever cancelling a newer unique-work owner. */
    private suspend fun retireStaleObserveRetryCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
        authority: RestoreReconciliationAuthority? = null,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) return false
        val stale = withCarrierMutation(context, authority) {
            if (isCurrentObserveRetryAuthority(context, carrier)) {
                false
            } else {
                database(context).workManagerHandoffCarrierDao.deleteExact(
                    carrier.handoffId,
                    carrier.requestId,
                )
                true
            }
        }
        if (stale) revokeStaleRequest(context, carrier.requestId)
        return stale
    }

    private suspend fun retireStaleObserveRecurrenceCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RECURRENCE) return false
        val stale = withCarrierMutation(context, null) {
            if (isCurrentObserveRecurrenceAuthority(context, carrier) &&
                isDurablyCurrentRetainedCarrier(context, carrier)
            ) {
                false
            } else {
                database(context).workManagerHandoffCarrierDao.markSuperseded(
                    carrier.handoffId,
                    carrier.requestId,
                    System.currentTimeMillis(),
                )
                true
            }
        }
        if (stale) reconcileSuperseded(context, carrier)
        return stale
    }

    private suspend fun retireStaleTerminalDispatchCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.TERMINAL_DISPATCH) return false
        val execution = TerminalExecutionRecovery.read(context, carrier.sourceId)
        if (execution != null && !execution.terminal) return false
        val stale = withCarrierMutation(context, null) {
            if (isCurrentTerminalDispatchAuthority(context, carrier) &&
                isDurablyCurrentRetainedCarrier(context, carrier)
            ) {
                false
            } else {
                database(context).workManagerHandoffCarrierDao.markSuperseded(
                    carrier.handoffId,
                    carrier.requestId,
                    System.currentTimeMillis(),
                )
                true
            }
        }
        if (stale) reconcileSuperseded(context, carrier)
        return stale
    }

    private suspend fun retireStaleObserveCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
        authority: RestoreReconciliationAuthority? = null,
    ): Boolean = retireStaleAutomaticKeywordCarrier(context, carrier, authority) ||
        retireStaleTerminalDispatchCarrier(context, carrier) ||
        retireStaleObserveRetryCarrier(context, carrier, authority) ||
        retireStaleObserveRecurrenceCarrier(context, carrier)

    private suspend fun retireStaleAutomaticKeywordCarrier(
        context: Context,
        carrier: WorkManagerHandoffCarrier,
        authority: RestoreReconciliationAuthority? = null,
    ): Boolean {
        if (carrier.kind != WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC) return false
        val stale = withCarrierMutation(context, authority) {
            if (isCurrentAutomaticKeywordAuthority(context, carrier) &&
                isDurablyCurrentRetainedCarrier(context, carrier)
            ) {
                false
            } else {
                database(context).workManagerHandoffCarrierDao.markSuperseded(
                    carrier.handoffId,
                    carrier.requestId,
                    System.currentTimeMillis(),
                )
                true
            }
        }
        if (stale) reconcileSuperseded(context, carrier)
        return stale
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
        if (retireStaleObserveCarrier(context, carrier, authority)) {
            return EnqueueOutcome(OutcomeKind.SUPERSEDED)
        }
        if (!isCurrentGeneration(carrier) ||
            !isCurrentObserveExecutionAuthority(context, carrier) ||
            (retainsCarrierUntilWorkerTerminal(carrier.kind) &&
                !isDurablyCurrentRetainedCarrier(context, carrier))
        ) {
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
        if (carrier.kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH) {
            // Once TerminalExecutionRecovery has a witness, this subject is
            // already owned by the admitted/recovery path. Do not publish a
            // second request merely because WorkInfo is absent.
            if (TerminalExecutionRecovery.hasRecordFile(context, carrier.sourceId)) {
                return EnqueueOutcome(OutcomeKind.ACCEPTED)
            }
            val existingWork = workInfo(context, carrier.requestId)
            if (existingWork != null) {
                if (existingWork.state == WorkInfo.State.SUCCEEDED ||
                    !existingWork.state.isFinished
                ) {
                    val marked = withCarrierMutation(context, authority) {
                        val dao = database(context).workManagerHandoffCarrierDao
                        val changed = dao.markAccepted(
                            carrier.handoffId,
                            carrier.requestId,
                            System.currentTimeMillis(),
                        )
                        if (changed == 0) {
                            val current = dao.get(carrier.handoffId)
                            if (current?.requestId == carrier.requestId &&
                                current.state == WorkManagerHandoffCarrier.ACCEPTED &&
                                isCurrentGeneration(carrier) &&
                                isCurrentObserveExecutionAuthority(context, carrier) &&
                                isDurablyCurrentRetainedCarrier(context, carrier)
                            ) 1 else 0
                        } else {
                            changed
                        }
                    }
                    return if (marked > 0) {
                        EnqueueOutcome(OutcomeKind.ACCEPTED)
                    } else {
                        EnqueueOutcome(OutcomeKind.SUPERSEDED)
                    }
                }
                return retryAfterFailure(context, carrier, null, authority)
            }
        }
        val remainingDelay = carrier.notBeforeAt - System.currentTimeMillis()
        if (remainingDelay > 0L && authority == null &&
            carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RECURRENCE
        ) {
            scheduleRetry(context, handoffId, authority)
            return EnqueueOutcome(OutcomeKind.RETRYING)
        }

        val request = try {
            buildRequest(context, carrier)
        } catch (failure: Throwable) {
            return retryAfterFailure(context, carrier, failure, authority)
        }

        return try {
            val operation = if (authority == null) {
                // Enter admission only for the enqueue publication call. Awaiting
                // Operation.result while holding the process-global mutex would
                // block a newer REPLACE generation from superseding this attempt.
                RestoreMutationAdmission.withOrdinaryMutation(context) {
                    if (!schedulerAuthorityAvailable(context, null) ||
                        !isCurrentGeneration(carrier) ||
                        !isCurrentObserveExecutionAuthority(context, carrier) ||
                        !isDurablyCurrentRetainedCarrier(context, carrier)
                    ) {
                        null
                    } else {
                        enqueueUniqueWork(
                            context = context,
                            uniqueWorkName = carrier.uniqueWorkName,
                            request = request,
                        )
                    }
                }
            } else {
                RestoreMutationAdmission.withRestoreMutation {
                    RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
                    if (!schedulerAuthorityAvailable(context, authority) ||
                        !isCurrentGeneration(carrier) ||
                        !isCurrentObserveExecutionAuthority(context, carrier) ||
                        (retainsCarrierUntilWorkerTerminal(carrier.kind) &&
                            !isDurablyCurrentRetainedCarrier(context, carrier))
                    ) {
                        null
                    } else {
                        enqueueUniqueWork(
                            context = context,
                            uniqueWorkName = carrier.uniqueWorkName,
                            request = request,
                        )
                    }
                }
            }
            val result = if (operation == null) {
                null
            } else {
                val failure = awaitOperation(operation)
                if (failure != null) {
                    retryAfterFailure(context, carrier, failure, authority)
                } else {
                    finalizeAccepted(context, carrier, authority)
                }
            }
            if (result == null && schedulerAuthorityAvailable(context, authority) &&
                retireStaleObserveCarrier(context, carrier, authority)
            ) {
                return EnqueueOutcome(OutcomeKind.SUPERSEDED)
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
    ): EnqueueOutcome {
        val schedulerCarrier = retainsCarrierUntilWorkerTerminal(carrier.kind)
        if (
            schedulerCarrier && authority == null &&
            (
                RestoreGate.isRestoreInProgress(context) ||
                    !isCurrentGeneration(carrier) ||
                    !isCurrentObserveExecutionAuthority(context, carrier) ||
                    !isDurablyCurrentRetainedCarrier(context, carrier)
                )
        ) {
            revokeStaleRequest(context, carrier.requestId)
            return EnqueueOutcome(OutcomeKind.SUPERSEDED)
        }

        val outcome = try {
            withCarrierMutation(context, authority) {
                val dao = database(context).workManagerHandoffCarrierDao
                if (!isCurrentGeneration(carrier)) {
                    return@withCarrierMutation null
                }
                if (!isCurrentObserveExecutionAuthority(context, carrier)) {
                    if (carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                        dao.deleteExact(carrier.handoffId, carrier.requestId)
                    } else if (retainsCarrierUntilWorkerTerminal(carrier.kind)) {
                        dao.markSuperseded(
                            carrier.handoffId,
                            carrier.requestId,
                            System.currentTimeMillis(),
                        )
                    }
                    return@withCarrierMutation null
                }
                if (schedulerCarrier && !isDurablyCurrentRetainedCarrier(context, carrier)) {
                    return@withCarrierMutation null
                }
                val accepted = dao.markAccepted(
                    carrier.handoffId,
                    carrier.requestId,
                    System.currentTimeMillis(),
                )
                if (accepted == 0) {
                    val current = dao.get(carrier.handoffId)
                    if (
                        current == null ||
                        current.requestId != carrier.requestId ||
                        !isCurrentGeneration(carrier) ||
                        !isCurrentObserveExecutionAuthority(context, carrier) ||
                        (schedulerCarrier &&
                            !isDurablyCurrentRetainedCarrier(context, carrier))
                    ) {
                        null
                    } else {
                        EnqueueOutcome(OutcomeKind.ACCEPTED)
                    }
                } else {
                    if (
                        !retainsCarrierUntilWorkerTerminal(carrier.kind) &&
                        carrier.kind != WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD &&
                        authority == null
                    ) {
                        dao.deleteAccepted(carrier.handoffId, carrier.requestId)
                    }
                    retryJobs.remove(carrier.handoffId)?.cancel()
                    if (!isCurrentGeneration(carrier)) {
                        null
                    } else {
                        EnqueueOutcome(OutcomeKind.ACCEPTED)
                    }
                }
            }
        } catch (blocked: IllegalStateException) {
            if (
                schedulerCarrier &&
                authority == null &&
                blocked.message == "Restore transaction is active"
            ) {
                revokeStaleRequest(context, carrier.requestId)
                return EnqueueOutcome(OutcomeKind.SUPERSEDED)
            }
            throw blocked
        }
        if (outcome == null) {
            if (schedulerCarrier && authority == null ||
                carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD
            ) {
                revokeStaleRequest(context, carrier.requestId)
            }
            return EnqueueOutcome(OutcomeKind.SUPERSEDED)
        }
        return outcome
    }

    private suspend fun revokeStaleRequest(context: Context, requestId: String) {
        runCatching { cancelWorkByIdAndAwait(context, requestId) }
            .onFailure { failure ->
                Log.w(TAG, "Could not revoke stale scheduler request $requestId", failure)
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
        if (retireStaleObserveCarrier(context, carrier, authority)) {
            return EnqueueOutcome(OutcomeKind.SUPERSEDED, failure)
        }
        if (
            carrier.kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH &&
            TerminalExecutionRecovery.hasRecordFile(context, carrier.sourceId)
        ) {
            return EnqueueOutcome(OutcomeKind.ACCEPTED, failure)
        }

        val existingWork = workInfo(context, carrier.requestId)
        if (existingWork != null &&
            existingWork.state !in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED) &&
            !(carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE &&
                existingWork.state.isFinished)
        ) {
            val outcome = try {
                withCarrierMutation(context, authority) {
                    val dao = database(context).workManagerHandoffCarrierDao
                    if (!isCurrentObserveExecutionAuthority(context, carrier)) {
                        if (carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                            dao.deleteExact(carrier.handoffId, carrier.requestId)
                        } else if (retainsCarrierUntilWorkerTerminal(carrier.kind)) {
                            dao.markSuperseded(
                                carrier.handoffId,
                                carrier.requestId,
                                System.currentTimeMillis(),
                            )
                        }
                        return@withCarrierMutation EnqueueOutcome(
                            OutcomeKind.SUPERSEDED,
                            failure,
                        )
                    }
                    if (
                        !isCurrentGeneration(carrier) ||
                        (retainsCarrierUntilWorkerTerminal(carrier.kind) &&
                            !isDurablyCurrentRetainedCarrier(context, carrier))
                    ) {
                        return@withCarrierMutation EnqueueOutcome(
                            OutcomeKind.SUPERSEDED,
                            failure,
                        )
                    }
                    val accepted = dao.markAccepted(
                        carrier.handoffId,
                        carrier.requestId,
                        System.currentTimeMillis(),
                    )
                    if (accepted > 0 &&
                        !retainsCarrierUntilWorkerTerminal(carrier.kind) &&
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
            if (outcome.superseded) retireStaleObserveCarrier(context, carrier, authority)
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
                if (!isCurrentObserveExecutionAuthority(context, carrier)) {
                    if (carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RETRY_DOWNLOAD) {
                        dao.deleteExact(carrier.handoffId, carrier.requestId)
                    } else if (retainsCarrierUntilWorkerTerminal(carrier.kind)) {
                        dao.markSuperseded(
                            carrier.handoffId,
                            carrier.requestId,
                            System.currentTimeMillis(),
                        )
                    }
                    return@withCarrierMutation EnqueueOutcome(
                        OutcomeKind.SUPERSEDED,
                        failure,
                    )
                }
                if (current.requestId != carrier.requestId ||
                    !isCurrentGeneration(carrier) ||
                    (retainsCarrierUntilWorkerTerminal(carrier.kind) &&
                        !isDurablyCurrentRetainedCarrier(context, carrier))
                ) {
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
        if (outcome.superseded) retireStaleObserveCarrier(context, carrier, authority)
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

                val waitForSchedule = if (
                    carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE
                ) {
                    0L
                } else {
                    carrier.notBeforeAt - System.currentTimeMillis()
                }
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
                if (retainsCarrierUntilWorkerTerminal(it.kind)) {
                    blocking {
                        dao.markSuperseded(
                            it.handoffId,
                            it.requestId,
                            System.currentTimeMillis(),
                        )
                    }
                }
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
                    if (!retainsCarrierUntilWorkerTerminal(carrier.kind)) {
                        dao.deleteOutstandingForBoundary(carrier.kind, carrier.boundary)
                    }
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
                if (retainsCarrierUntilWorkerTerminal(it.kind)) {
                    blocking {
                        dao.markSuperseded(
                            it.handoffId,
                            it.requestId,
                            System.currentTimeMillis(),
                        )
                    }
                }
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
                val db = database(context)
                db.withTransaction {
                    val dao = db.workManagerHandoffCarrierDao
                    dao.deleteOutstandingForBoundary(kind, boundary)
                    dao.deleteSupersededForBoundary(kind, boundary)
                }
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
            .putString(INPUT_GENERATION_ID, carrier.generationId)
            .putString(INPUT_BOUNDARY, carrier.boundary)
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
                    .putLong(
                        ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION,
                        carrier.sourceConfigurationGeneration,
                    )
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
                    .addTag(
                        ObserveSourceWorker.configurationGenerationTag(carrier.sourceConfigurationGeneration),
                    )
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(networkType).build()
                    )
                    .setInputData(observeInput)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()
            }

            WorkManagerHandoffCarrier.OBSERVE_RECURRENCE -> {
                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                val networkType = if (preferences.getBoolean("metered_networks", true)) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.UNMETERED
                }
                val observeInput = Data.Builder()
                    .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, carrier.sourceId)
                    .putLong(
                        ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION,
                        carrier.sourceConfigurationGeneration,
                    )
                    .putString(ObserveSourceWorker.INPUT_RECURRENCE_HANDOFF_ID, carrier.handoffId)
                    .putString(ObserveSourceWorker.INPUT_RECURRENCE_REQUEST_ID, carrier.requestId)
                    .build()
                OneTimeWorkRequestBuilder<ObserveSourceWorker>()
                    .setId(requestId)
                    .addTag("observeSources")
                    .addTag("observation_${carrier.sourceId}")
                    .addTag(carrier.sourceId.toString())
                    .addTag(
                        ObserveSourceWorker.configurationGenerationTag(
                            carrier.sourceConfigurationGeneration,
                        ),
                    )
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(networkType).build(),
                    )
                    .setInputData(observeInput)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()
            }

            WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC -> {
                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                val networkType = if (preferences.getBoolean("metered_networks", true)) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.UNMETERED
                }
                val keywordInput = Data.Builder()
                    .putLong(AutomaticKeywordRuleSyncWorker.INPUT_RULE_ID, carrier.sourceId)
                    .putLong(
                        AutomaticKeywordRuleSyncWorker.INPUT_REVISION,
                        carrier.sourceConfigurationGeneration,
                    )
                    .putString(AutomaticKeywordRuleSyncWorker.INPUT_MODE, carrier.decision)
                    .putString(AutomaticKeywordRuleSyncWorker.INPUT_HANDOFF_ID, carrier.handoffId)
                    .putString(AutomaticKeywordRuleSyncWorker.INPUT_REQUEST_ID, carrier.requestId)
                    .putString(AutomaticKeywordRuleSyncWorker.INPUT_GENERATION_ID, carrier.generationId)
                    .putString(AutomaticKeywordRuleSyncWorker.INPUT_BOUNDARY, carrier.boundary)
                    .build()
                OneTimeWorkRequestBuilder<AutomaticKeywordRuleSyncWorker>()
                    .setId(requestId)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag("automaticKeywordRules")
                    .addTag("automaticKeywordRule_${carrier.sourceId}")
                    .setInputData(keywordInput)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()
            }

            WorkManagerHandoffCarrier.TERMINAL_DISPATCH -> {
                val terminalInput = Data.Builder()
                    .putInt(TerminalDownloadWorker.INPUT_ID, carrier.sourceId.toInt())
                    .putString(TerminalDownloadWorker.INPUT_COMMAND, carrier.confirmedUrl)
                    .putString(TerminalDownloadWorker.INPUT_HANDOFF_ID, carrier.handoffId)
                    .putString(TerminalDownloadWorker.INPUT_REQUEST_ID, carrier.requestId)
                    .putString(TerminalDownloadWorker.INPUT_GENERATION_ID, carrier.generationId)
                    .putString(TerminalDownloadWorker.INPUT_BOUNDARY, carrier.boundary)
                    .putString(TerminalDownloadWorker.INPUT_COMMAND_FINGERPRINT, carrier.configFingerprint)
                    .build()
                OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
                    .setId(requestId)
                    .addTag("terminal")
                    .addTag(carrier.sourceId.toString())
                    .setInputData(terminalInput)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()
            }

            else -> error("Unknown WorkManager handoff kind ${carrier.kind}")
        }
    }

    private const val INPUT_HANDOFF_ID = "handoffId"
    private const val INPUT_REQUEST_ID = "handoffRequestId"
    internal const val INPUT_GENERATION_ID = "handoffGenerationId"
    internal const val INPUT_BOUNDARY = "handoffBoundary"
    private const val TERMINAL_DISPATCH_DECISION = "EXECUTE"
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

    private fun cancelWorkByIdAndAwait(context: Context, requestId: String) {
        val uuid = UUID.fromString(requestId)
        val operation = cancelWorkByIdOperationOverrideForTesting?.invoke(requestId)
            ?: workManager(context).cancelWorkById(uuid)
        operation.result.get(5_000L, TimeUnit.MILLISECONDS)
    }
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

    private fun isCurrentGeneration(carrier: WorkManagerHandoffCarrier): Boolean {
        // Recurrence authority is the exact durable owner plus source
        // generation. A process-local map can lag a committed transaction if
        // its worker is cancelled before this process refreshes the map.
        if (carrier.kind == WorkManagerHandoffCarrier.OBSERVE_RECURRENCE ||
            carrier.kind == WorkManagerHandoffCarrier.AUTOMATIC_KEYWORD_SYNC ||
            carrier.kind == WorkManagerHandoffCarrier.TERMINAL_DISPATCH
        ) return true
        return latestGenerationByBoundary[boundaryKey(carrier.kind, carrier.boundary)]
            ?.let { it == carrier.handoffId }
            ?: true
    }
}
