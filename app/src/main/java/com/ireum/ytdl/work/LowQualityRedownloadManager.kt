package com.ireum.ytdl.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.util.LowQualityRedownloadNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LowQualityRedownloadManager private constructor(
    context: Context,
    databaseOverride: DBManager? = null,
    private val enqueueOverride:
        ((String, ExistingWorkPolicy, OneTimeWorkRequest) -> Operation)? = null,
) {
    private val appContext = context.applicationContext
    private val database = databaseOverride ?: DBManager.getInstance(appContext)
    private val repository = LowQualityRedownloadRepository(database)
    private val workManager = WorkManager.getInstance(appContext)
    // Selection toggles and confirm/cancel commands must retain call order even though
    // their owner outlives any Fragment view.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val notification = LowQualityRedownloadNotification(appContext)

    fun startOrReconnect() {
        scope.launch {
            val operation = repository.createOrReconnect()
            dispatchRecovery(operation)
        }
    }

    fun setSelected(operationId: String, historyId: Long, selected: Boolean) {
        scope.launch { repository.setSelected(operationId, historyId, selected) }
    }

    fun confirm(operationId: String) {
        scope.launch {
            confirmAndEnqueueLowQualityRedownload(
                operationId = operationId,
                transition = repository::confirmSelection,
                enqueueSuccessor = { confirmedId, policy ->
                    enqueue(confirmedId, networkRequired = true, policy = policy)
                }
            )
        }
    }

    fun cancel(operationId: String, onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                repository.requestCancellation(operationId)
                completeCancellation(operationId)
            } catch (cancelled: CancellationException) {
                // requestCancellation() may already have committed the
                // durable revocation before coroutine cancellation interrupts
                // phase two.  Keep an exact-state convergence owner alive so
                // cancellation cannot strand that operation or leave a stale
                // enqueue retry responsible for it.
                LowQualityRedownloadLedger.scheduleCancellationConvergence(
                    appContext,
                    operationId,
                    database,
                )
                throw cancelled
            } catch (error: Exception) {
                // The phase-one transaction may have committed even though a
                // later publication/phase-two step failed.  Schedule a
                // durable-state-driven retry; the convergence owner itself
                // exits if the revocation did not commit.
                LowQualityRedownloadLedger.scheduleCancellationConvergence(
                    appContext,
                    operationId,
                    database,
                )
                android.util.Log.e(
                    "LowQualityRedownload",
                    "Low-quality cancellation completion deferred operation=$operationId",
                    error,
                )
            } finally {
                onComplete?.invoke()
            }
        }
    }

    suspend fun reconcile() {
        val operation = repository.getActiveOperation() ?: return
        dispatchRecovery(operation)
    }

    /**
     * Owns only the durable phase-one cancellation debt.  This deliberately
     * does not open the normal scan/preparation/download reconciliation gate;
     * cancellation convergence must remain discoverable after optional
     * runtime initialization fails.
     */
    suspend fun reconcileCancellationDebt(
        dbManager: DBManager = database,
    ): Boolean {
        val recoveryRepository = if (dbManager === database) {
            repository
        } else {
            LowQualityRedownloadRepository(dbManager)
        }
        val abandonedUndoDebts = recoveryRepository.reconcileAbandonedUndoDebts()
        val operation = recoveryRepository.getActiveOperation()
            ?.takeIf { it.cancelRequested }
        if (operation == null) return abandonedUndoDebts.isNotEmpty()
        completeCancellation(
            operationId = operation.operationId,
            dbManager = dbManager,
            recoveryRepository = recoveryRepository,
        )
        return true
    }

    private suspend fun dispatchRecovery(operation: LowQualityRedownloadOperation) {
        dispatchLowQualityRedownloadRecovery(
            operation = operation,
            completeCancellation = ::completeCancellation,
            enqueuePhase = ::enqueue,
            reconcileDownloads = ::reconcileOperation,
            refreshNotification = { operationId ->
                repository.progress(operationId)?.let { notification.update(it) }
            }
        )
    }

    private suspend fun completeCancellation(
        operationId: String,
        dbManager: DBManager = database,
        recoveryRepository: LowQualityRedownloadRepository = repository,
    ) {
        try {
            LowQualityRedownloadLedger.cancelEnqueueConvergence(operationId)
            workManager.cancelAllWorkByTag(LowQualityRedownloadWorker.operationTag(operationId))
            val result = recoveryRepository.completePersistedCancellationWithPublications(
                operationId = operationId,
                context = appContext,
            )
            DownloadCancellationRegistry.publish(result.publications)
            result.publications.forEach { publication ->
                withDownloadWorkerExecutionSideEffectLease(
                    publication.downloadId,
                    publication.executionId,
                ) {
                    check(
                        DownloadExecutionRecovery.markUserStopSemanticCommitted(
                            context = appContext,
                            downloadId = publication.downloadId,
                            executionId = publication.executionId,
                            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                        )
                    ) {
                        "Cancellation semantic carrier was not acknowledged for " +
                            publication.downloadId
                    }
                    check(
                        DownloadExecutionRecovery.quiesceAfterDurableStop(
                            context = appContext,
                            downloadId = publication.downloadId,
                            executionId = publication.executionId,
                            dbManager = dbManager,
                        )
                    ) {
                        "Native cancellation was not acknowledged for ${publication.downloadId}"
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LowQualityRedownloadLedger.scheduleCancellationConvergence(
                appContext,
                operationId,
                dbManager,
            )
            throw error
        } finally {
            recoveryRepository.progress(operationId)?.let { notification.update(it) }
        }
    }

    private suspend fun reconcileOperation(operationId: String) {
        val downloads = repository.reconcileLinkedDownloads(operationId)
        val operation = repository.getOperation(operationId)
        if (
            operation?.stateValue == LowQualityRedownloadOperationState.RUNNING &&
            !operation.cancelRequested &&
            downloads.any { it.status == DownloadRepository.Status.Queued.name }
        ) {
            DownloadRepository(database).startDownloadWorker(emptyList(), appContext)
        }
        repository.progress(operationId)?.let { notification.update(it) }
    }

    private fun enqueue(
        operationId: String,
        networkRequired: Boolean,
        policy: ExistingWorkPolicy = LowQualityRedownloadEnqueuePolicy.RECOVERY.workPolicy
    ) {
        val constraints = Constraints.Builder().apply {
            if (networkRequired) setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()
        val request = OneTimeWorkRequestBuilder<LowQualityRedownloadWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(LowQualityRedownloadWorker.KEY_OPERATION_ID, operationId).build())
            .addTag(LowQualityRedownloadWorker.GLOBAL_TAG)
            .addTag(LowQualityRedownloadWorker.operationTag(operationId))
            .build()
        enqueueAttempt(
            operationId = operationId,
            networkRequired = networkRequired,
            policy = policy,
            request = request,
        ) { failure ->
            if (failure != null) {
                scheduleEnqueueRetry(operationId, networkRequired)
            }
        }
    }

    /** Observe Operation.result; invoking enqueue is not acceptance. */
    private fun enqueueAttempt(
        operationId: String,
        networkRequired: Boolean,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
        completion: (Throwable?) -> Unit,
    ) {
        try {
            val operation = enqueueOverride?.invoke(
                LowQualityRedownloadWorker.uniqueWorkName(operationId),
                policy,
                request,
            ) ?: workManager.enqueueUniqueWork(
                LowQualityRedownloadWorker.uniqueWorkName(operationId),
                policy,
                request,
            )
            operation.result.addListener(
                {
                    completion(
                        runCatching { operation.result.get() }.exceptionOrNull()
                    )
                },
                Runnable::run,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            completion(failure)
        }
    }

    private fun scheduleEnqueueRetry(
        operationId: String,
        networkRequired: Boolean,
    ) {
        LowQualityRedownloadLedger.scheduleEnqueueConvergence(
            dbManager = database,
            operationId = operationId,
            networkRequired = networkRequired,
            enqueueAttempt = { retryOperationId, retryNetworkRequired, retryPolicy, completion ->
                val constraints = Constraints.Builder().apply {
                    if (retryNetworkRequired) {
                        setRequiredNetworkType(NetworkType.CONNECTED)
                    }
                }.build()
                val retryRequest = OneTimeWorkRequestBuilder<LowQualityRedownloadWorker>()
                    .setConstraints(constraints)
                    .setInputData(
                        Data.Builder()
                            .putString(
                                LowQualityRedownloadWorker.KEY_OPERATION_ID,
                                retryOperationId,
                            )
                            .build()
                    )
                    .addTag(LowQualityRedownloadWorker.GLOBAL_TAG)
                    .addTag(LowQualityRedownloadWorker.operationTag(retryOperationId))
                    .build()
                enqueueAttempt(
                    operationId = retryOperationId,
                    networkRequired = retryNetworkRequired,
                    policy = retryPolicy,
                    request = retryRequest,
                    completion = completion,
                )
            },
        )
    }

    companion object {
        @Volatile
        private var instance: LowQualityRedownloadManager? = null

        fun get(context: Context): LowQualityRedownloadManager =
            instance ?: synchronized(this) {
                instance ?: LowQualityRedownloadManager(context).also { instance = it }
            }

        /** Uses the production manager path with only the WorkManager result injected. */
        internal fun createForTesting(
            context: Context,
            database: DBManager,
            enqueue: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Operation,
        ): LowQualityRedownloadManager = LowQualityRedownloadManager(
            context = context,
            databaseOverride = database,
            enqueueOverride = enqueue,
        )
    }
}

internal enum class LowQualityRedownloadEnqueuePolicy(
    val workPolicy: ExistingWorkPolicy
) {
    RECOVERY(ExistingWorkPolicy.KEEP),
    CONFIRMATION(ExistingWorkPolicy.APPEND_OR_REPLACE)
}

internal suspend fun confirmAndEnqueueLowQualityRedownload(
    operationId: String,
    transition: suspend (String) -> Boolean,
    enqueueSuccessor: (String, ExistingWorkPolicy) -> Unit
): Boolean {
    if (!transition(operationId)) return false
    enqueueSuccessor(
        operationId,
        LowQualityRedownloadEnqueuePolicy.CONFIRMATION.workPolicy
    )
    return true
}

internal suspend fun dispatchLowQualityRedownloadRecovery(
    operation: LowQualityRedownloadOperation,
    completeCancellation: suspend (String) -> Unit,
    enqueuePhase: (String, Boolean) -> Unit,
    reconcileDownloads: suspend (String) -> Unit,
    refreshNotification: suspend (String) -> Unit
) {
    val operationId = operation.operationId
    if (operation.cancelRequested) {
        completeCancellation(operationId)
        return
    }
    if (operation.stateValue.isTerminal) {
        refreshNotification(operationId)
        return
    }
    when (operation.phaseValue) {
        LowQualityRedownloadPhase.SCANNING -> enqueuePhase(operationId, false)
        LowQualityRedownloadPhase.PREPARING,
        LowQualityRedownloadPhase.QUEUEING -> enqueuePhase(operationId, true)
        LowQualityRedownloadPhase.DOWNLOADING,
        LowQualityRedownloadPhase.FINALIZING -> reconcileDownloads(operationId)
        LowQualityRedownloadPhase.AWAITING_SELECTION -> refreshNotification(operationId)
    }
}

object LowQualityRedownloadLedger {
    private val convergenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val abandonedUndoJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val enqueueJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Test seam for the first post-commit notification/ledger refresh step. */
    @Volatile
    internal var refreshFailureForTesting: (() -> Exception?)? = null

    suspend fun transition(
        context: Context,
        downloadId: Long,
        state: LowQualityRedownloadItemState,
        reason: String = "",
        expectedExecutionId: String = "",
    ) {
        val repository = LowQualityRedownloadRepository(DBManager.getInstance(context))
        val operationId = repository.markDownloadState(
            downloadId = downloadId,
            state = state,
            reason = reason,
            expectedExecutionId = expectedExecutionId,
        ) ?: return
        refresh(context, setOf(operationId))
    }

    /**
     * A Download terminal write is authoritative even when the independent
     * ledger write fails.  Reconcile that durable Download state in the live
     * process so the linked child/parent cannot remain nonterminal forever.
     */
    fun scheduleConvergence(context: Context, downloadId: Long) {
        val appContext = context.applicationContext
        convergenceScope.launch {
            var retryDelayMs = 100L
            while (true) {
                val repository = LowQualityRedownloadRepository(DBManager.getInstance(appContext))
                val linked = try {
                    repository.hasLinkedDownload(downloadId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w(
                        "LowQualityRedownload",
                        "Could not inspect terminal convergence debt download=$downloadId",
                        error,
                    )
                    null
                }
                if (linked == false) {
                    // The linked row was removed; there is no remaining debt
                    // for this Download to converge.
                    return@launch
                }
                val operationId = try {
                    repository.reconcileDownload(downloadId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w(
                        "LowQualityRedownload",
                        "Terminal convergence retry failed download=$downloadId",
                        error,
                    )
                    null
                }
                if (operationId == null) {
                    // Preserve the debt on a transient read/transaction
                    // failure; the next iteration retries it.
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                    continue
                }
                val child = try {
                    repository.getItems(operationId)
                        .firstOrNull { it.downloadId == downloadId }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w(
                        "LowQualityRedownload",
                        "Could not inspect terminal convergence child download=$downloadId",
                        error,
                    )
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                    continue
                }
                if (child == null || child.stateValue.isTerminal) {
                    refresh(appContext, setOf(operationId))
                    return@launch
                }
                // The durable Download Error/refusal is the convergence debt:
                // keep deriving the linked terminal state until the Room write
                // succeeds.  A bounded best-effort loop could leave a live
                // process with a nonterminal child forever after a transient
                // ledger failure.
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
            }
        }
    }

    /**
     * Installs a token-scoped successor owner before a lifecycle owner drops
     * its live Undo authority.  This owner is deliberately independent from
     * runtime/native readiness and retries the exact durable carrier until it
     * is resolved or a stronger authority consumes it.
     */
    fun scheduleAbandonedUndoConvergence(
        dbManager: DBManager,
        token: String,
    ) {
        if (
            !token.startsWith(DownloadRepository.PENDING_REMOVAL_TOKEN_PREFIX) &&
                !DownloadRepository.isValidPendingCancellationToken(token)
        ) {
            return
        }
        abandonedUndoJobs.computeIfAbsent(token) {
            convergenceScope.launch {
                val ownerJob = coroutineContext[Job]
                var retryDelayMs = 100L
                try {
                    while (true) {
                        val resolved = try {
                            LowQualityRedownloadRepository(dbManager)
                                .reconcileAbandonedUndoDebt(token)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w(
                                "LowQualityRedownload",
                                "Abandoned Undo convergence retry failed token=$token",
                                error,
                            )
                            false
                        }
                        if (resolved) return@launch
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                    }
                } finally {
                    if (ownerJob != null) abandonedUndoJobs.remove(token, ownerJob)
                }
            }
        }
    }

    internal fun isAbandonedUndoConvergenceActiveForTesting(token: String): Boolean =
        abandonedUndoJobs[token]?.isActive == true

    internal fun cancelAbandonedUndoConvergenceForTesting(token: String) {
        abandonedUndoJobs.remove(token)?.cancel()
    }

    internal fun cancelAllAbandonedUndoConvergenceJobsForTesting() {
        abandonedUndoJobs.values.forEach { it.cancel() }
        abandonedUndoJobs.clear()
    }

    /**
     * Owns the exact durable low-quality phase until WorkManager's Operation
     * has accepted the unique work.  It is intentionally independent of the
     * manager's limitedParallelism(1) command lane, so cancellation can still
     * establish cancelRequested while this carrier retries.
     */
    fun scheduleEnqueueConvergence(
        dbManager: DBManager,
        operationId: String,
        networkRequired: Boolean,
        enqueueAttempt: (
            String,
            Boolean,
            ExistingWorkPolicy,
            (Throwable?) -> Unit,
        ) -> Unit,
    ) {
        if (operationId.isBlank()) return
        enqueueJobs.computeIfAbsent(operationId) {
            convergenceScope.launch {
                val ownerJob = coroutineContext[Job]
                var retryDelayMs = 100L
                try {
                    while (true) {
                        val repository = LowQualityRedownloadRepository(dbManager)
                        val operation = try {
                            repository.getOperation(operationId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w(
                                "LowQualityRedownload",
                                "Could not inspect enqueue carrier operation=$operationId",
                                error,
                            )
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                            continue
                        }
                        if (operation == null || operation.stateValue.isTerminal || operation.cancelRequested) {
                            return@launch
                        }
                        val phaseMatches = if (networkRequired) {
                            operation.phaseValue in setOf(
                                LowQualityRedownloadPhase.PREPARING,
                                LowQualityRedownloadPhase.QUEUEING,
                            )
                        } else {
                            operation.phaseValue == LowQualityRedownloadPhase.SCANNING
                        }
                        if (!phaseMatches) return@launch

                        val result = CompletableDeferred<Throwable?>()
                        try {
                            // KEEP is used by every retry.  The unique name is
                            // the exact operation carrier and prevents a late
                            // retry from creating a second worker generation.
                            enqueueAttempt(
                                operationId,
                                networkRequired,
                                ExistingWorkPolicy.KEEP,
                            ) { failure -> result.complete(failure) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            result.complete(error)
                        }
                        val failure = result.await()
                        if (failure == null) return@launch
                        android.util.Log.w(
                            "LowQualityRedownload",
                            "Low-quality enqueue acceptance retry operation=$operationId",
                            failure,
                        )
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                    }
                } finally {
                    if (ownerJob != null) enqueueJobs.remove(operationId, ownerJob)
                }
            }
        }
    }

    internal fun isEnqueueConvergenceActiveForTesting(operationId: String): Boolean =
        enqueueJobs[operationId]?.isActive == true

    internal fun cancelEnqueueConvergence(operationId: String) {
        enqueueJobs.remove(operationId)?.cancel()
    }

    internal fun cancelAllEnqueueConvergenceJobsForTesting() {
        enqueueJobs.values.forEach { it.cancel() }
        enqueueJobs.clear()
    }

    /**
     * Phase-one cancellation is durable even when native cancellation or the
     * phase-two Room transaction fails.  Keep retrying the exact operation in
     * this process; startup recovery invokes the same protocol after process
     * death.
     */
    fun scheduleCancellationConvergence(
        context: Context,
        operationId: String,
        dbManager: DBManager = DBManager.getInstance(context.applicationContext),
    ) {
        if (operationId.isBlank()) return
        val appContext = context.applicationContext
        cancellationJobs.computeIfAbsent(operationId) {
            convergenceScope.launch {
                val ownerJob = coroutineContext[Job]
                var retryDelayMs = 100L
                try {
                    while (true) {
                        try {
                            val repository = LowQualityRedownloadRepository(dbManager)
                            val operation = repository.getOperation(operationId)
                                ?: return@launch
                            // This read is part of the cancellation-debt
                            // decision. Keep it inside the owner boundary so a
                            // transient Room failure cannot tear down the
                            // only same-process recovery responsibility.
                            val linkedDownloadIds = repository.getItems(operationId)
                                .mapNotNull { it.downloadId }
                                .distinct()
                            if (operation.stateValue.isTerminal) {
                                // Phase two may already have committed the
                                // terminal operation before native quiescence
                                // was acknowledged. Terminal state is not
                                // permission to abandon that independent
                                // native-debt carrier.
                                linkedDownloadIds.forEach { downloadId ->
                                    DownloadExecutionRecovery.scheduleRecovery(
                                        appContext,
                                        downloadId,
                                        dbManager,
                                    )
                                }
                                try {
                                    DownloadExecutionRecovery.reconcile(appContext, dbManager)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    // A failed recovery pass leaves the
                                    // durable carrier in place. Retry the whole
                                    // convergence iteration rather than using
                                    // a partial debt observation to terminate.
                                    android.util.Log.w(
                                        "LowQualityRedownload",
                                        "Terminal native recovery pass failed operation=$operationId",
                                        error,
                                    )
                                    delay(retryDelayMs)
                                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                                    continue
                                }
                                val nativeDebtRemains = linkedDownloadIds.any { downloadId ->
                                    DownloadExecutionRecovery.pendingDownloadIds(appContext)
                                        .contains(downloadId) ||
                                        DownloadWorker.hasAnyRegisteredNativeProcess(downloadId)
                                }
                                if (!nativeDebtRemains) return@launch
                                delay(retryDelayMs)
                                retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                                continue
                            }
                            if (!operation.cancelRequested) {
                                return@launch
                            }
                            cancelEnqueueConvergence(operationId)
                            try {
                                val result = repository.completePersistedCancellationWithPublications(
                                    operationId = operationId,
                                    context = appContext,
                                )
                                DownloadCancellationRegistry.publish(result.publications)
                                result.publications.forEach { publication ->
                                    withDownloadWorkerExecutionSideEffectLease(
                                        publication.downloadId,
                                        publication.executionId,
                                    ) {
                                        check(
                                            DownloadExecutionRecovery.markUserStopSemanticCommitted(
                                                context = appContext,
                                                downloadId = publication.downloadId,
                                                executionId = publication.executionId,
                                                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                            )
                                        ) {
                                            "Cancellation semantic carrier was not acknowledged for " +
                                                publication.downloadId
                                        }
                                        check(
                                            DownloadExecutionRecovery.quiesceAfterDurableStop(
                                                context = appContext,
                                                downloadId = publication.downloadId,
                                                executionId = publication.executionId,
                                                dbManager = dbManager,
                                            )
                                        ) {
                                            "Native cancellation was not acknowledged for " +
                                                publication.downloadId
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                android.util.Log.w(
                                    "LowQualityRedownload",
                                    "Low-quality cancellation convergence retry failed operation=$operationId",
                                    error,
                                )
                            }
                            if (repository.getOperation(operationId) == null) return@launch
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            // Every ordinary Room/native observation in this
                            // iteration is retryable cancellation debt. In
                            // particular, getItems() must not escape to the
                            // finally block while cancelRequested remains
                            // durable.
                            android.util.Log.w(
                                "LowQualityRedownload",
                                "Low-quality cancellation convergence iteration failed operation=$operationId",
                                error,
                            )
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                        }
                    }
                } finally {
                    if (ownerJob != null) cancellationJobs.remove(operationId, ownerJob)
                }
            }
        }
    }

    internal fun isCancellationConvergenceActiveForTesting(operationId: String): Boolean =
        cancellationJobs[operationId]?.isActive == true

    internal fun cancelCancellationConvergenceForTesting(operationId: String) {
        cancellationJobs.remove(operationId)?.cancel()
    }

    internal fun cancelAllCancellationConvergenceJobsForTesting() {
        cancellationJobs.values.forEach { it.cancel() }
        cancellationJobs.clear()
    }

    suspend fun refresh(context: Context, operationIds: Collection<String>) {
        refreshFailureForTesting?.invoke()?.let { throw it }
        if (operationIds.isEmpty()) return
        val appContext = context.applicationContext
        val repository = LowQualityRedownloadRepository(DBManager.getInstance(appContext))
        val notification = LowQualityRedownloadNotification(appContext)
        operationIds.distinct().forEach { operationId ->
            repository.progress(operationId)?.let(notification::update)
        }
    }
}
