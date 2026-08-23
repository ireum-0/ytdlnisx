package com.ireum.ytdl.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LowQualityRedownloadManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = DBManager.getInstance(appContext)
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
                throw cancelled
            } catch (error: Exception) {
                // The phase-one transaction may have committed even though a
                // later publication/phase-two step failed.  Schedule a
                // durable-state-driven retry; the convergence owner itself
                // exits if the revocation did not commit.
                LowQualityRedownloadLedger.scheduleCancellationConvergence(
                    appContext,
                    operationId,
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

    private suspend fun completeCancellation(operationId: String) {
        try {
            workManager.cancelAllWorkByTag(LowQualityRedownloadWorker.operationTag(operationId))
            val result = repository.completePersistedCancellationWithPublications(operationId)
            DownloadCancellationRegistry.publish(result.publications)
            result.publications.forEach { publication ->
                withDownloadWorkerExecutionSideEffectLease(
                    publication.downloadId,
                    publication.executionId,
                ) {
                    DownloadWorker.cancelProcessesForExecution(
                        publication.downloadId,
                        publication.executionId,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LowQualityRedownloadLedger.scheduleCancellationConvergence(
                appContext,
                operationId,
            )
            throw error
        } finally {
            repository.progress(operationId)?.let { notification.update(it) }
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
        workManager.enqueueUniqueWork(
            LowQualityRedownloadWorker.uniqueWorkName(operationId),
            policy,
            request
        )
    }

    companion object {
        @Volatile
        private var instance: LowQualityRedownloadManager? = null

        fun get(context: Context): LowQualityRedownloadManager =
            instance ?: synchronized(this) {
                instance ?: LowQualityRedownloadManager(context).also { instance = it }
            }
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
     * Phase-one cancellation is durable even when native cancellation or the
     * phase-two Room transaction fails.  Keep retrying the exact operation in
     * this process; startup recovery invokes the same protocol after process
     * death.
     */
    fun scheduleCancellationConvergence(context: Context, operationId: String) {
        if (operationId.isBlank()) return
        val appContext = context.applicationContext
        cancellationJobs.computeIfAbsent(operationId) {
            convergenceScope.launch {
                var retryDelayMs = 100L
                try {
                    while (true) {
                        val repository = LowQualityRedownloadRepository(
                            DBManager.getInstance(appContext)
                        )
                        val operation = try {
                            repository.getOperation(operationId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w(
                                "LowQualityRedownload",
                                "Could not inspect cancellation convergence operation=$operationId",
                                error,
                            )
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                            continue
                        } ?: return@launch
                        if (operation.stateValue.isTerminal || !operation.cancelRequested) {
                            return@launch
                        }
                        try {
                            val result = repository.completePersistedCancellationWithPublications(
                                operationId
                            )
                            DownloadCancellationRegistry.publish(result.publications)
                            result.publications.forEach { publication ->
                                withDownloadWorkerExecutionSideEffectLease(
                                    publication.downloadId,
                                    publication.executionId,
                                ) {
                                    DownloadWorker.cancelProcessesForExecution(
                                        publication.downloadId,
                                        publication.executionId,
                                    )
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
                        val latest = try {
                            repository.getOperation(operationId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w(
                                "LowQualityRedownload",
                                "Could not re-read cancellation convergence operation=$operationId",
                                error,
                            )
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                            continue
                        }
                        if (latest == null || latest.stateValue.isTerminal) return@launch
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
                    }
                } finally {
                    cancellationJobs.remove(operationId)
                }
            }
        }
    }

    suspend fun refresh(context: Context, operationIds: Collection<String>) {
        if (operationIds.isEmpty()) return
        val appContext = context.applicationContext
        val repository = LowQualityRedownloadRepository(DBManager.getInstance(appContext))
        val notification = LowQualityRedownloadNotification(appContext)
        operationIds.distinct().forEach { operationId ->
            repository.progress(operationId)?.let(notification::update)
        }
    }
}
