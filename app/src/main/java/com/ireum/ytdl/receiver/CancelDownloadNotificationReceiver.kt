package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.LowQualityRedownloadLedger
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLease
import com.ireum.ytdl.work.withDownloadWorkerExecutionLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CancelDownloadNotificationReceiver private constructor(
    private val databaseOverride: DBManager?,
    @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
) : BroadcastReceiver() {
    companion object {
        /** Deterministic seam for a failure raised inside the launched body. */
        @Volatile
        internal var beforeAsyncBodyForTesting: (() -> Unit)? = null

        /** Deterministic PendingResult completion observation for wiring tests. */
        @Volatile
        internal var finishObserverForTesting: (() -> Unit)? = null
    }

    constructor() : this(null, false)

    /** Test-only constructor; the manifest continues to use the no-arg form. */
    internal constructor(database: DBManager) : this(database, true)

    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra("itemID", 0)
        if (id > 0) {
            val result = goAsync()
            val finished = AtomicBoolean(false)
            fun finishOnce() {
                if (finished.compareAndSet(false, true)) {
                    result.finish()
                    finishObserverForTesting?.invoke()
                }
            }
            runCatching {
                val notificationUtil = NotificationUtil(c)
                val dbManager = databaseOverride ?: DBManager.getInstance(c)
                CoroutineScope(Dispatchers.IO).launch{
                    val expectedExecutionId = intent.getStringExtra("executionId").orEmpty()
                    try {
                        val injectedFailure = beforeAsyncBodyForTesting
                        beforeAsyncBodyForTesting = null
                        injectedFailure?.invoke()
                        if (expectedExecutionId.isBlank()) return@launch
                        withDownloadWorkerExecutionSideEffectLease(id.toLong(), expectedExecutionId) {
                            val affectedOperationIds = withDownloadWorkerExecutionLock {
                                val item = dbManager.downloadDao.getNullableDownloadById(id.toLong())
                                if (item?.executionId != expectedExecutionId) {
                                    return@withDownloadWorkerExecutionLock null
                                }
                                check(
                                    DownloadExecutionRecovery.recordPending(
                                        context = c,
                                        item = item,
                                        disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                        phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                                    )
                                ) {
                                    "Could not persist cancellation recovery responsibility for ${item.id}"
                                }
                                val affected = DownloadRepository(dbManager).cancelByUser(
                                    id.toLong(),
                                    expectedExecutionId,
                                )
                                val committed = dbManager.downloadDao
                                    .getNullableDownloadById(id.toLong())
                                    ?.let {
                                        it.status == DownloadRepository.Status.Cancelled.name &&
                                            it.executionId == expectedExecutionId
                                    } == true
                                check(committed) {
                                    "Cancellation semantic state was not committed for $id"
                                }
                                affected
                            }
                            if (affectedOperationIds != null) {
                                check(
                                    DownloadExecutionRecovery.markUserStopSemanticCommitted(
                                        context = c,
                                        downloadId = id.toLong(),
                                        executionId = expectedExecutionId,
                                        disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                    )
                                ) {
                                    "Cancellation semantic carrier was not acknowledged for $id"
                                }
                                // Durable cancellation committed before any
                                // process-local/native side effect.  The
                                // per-Download lease prevents a newer attempt
                                // from starting while this exact process is
                                // being stopped.
                                if (
                                    DownloadExecutionRecovery.quiesceAfterDurableStop(
                                        context = c,
                                        downloadId = id.toLong(),
                                        executionId = expectedExecutionId,
                                        dbManager = dbManager,
                                    )
                                ) {
                                    LowQualityRedownloadLedger.refresh(c, affectedOperationIds)
                                    notificationUtil.cancelRunningDownloadNotification(id)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        DownloadExecutionRecovery.retainRecoveryResponsibility(
                            context = c,
                            downloadId = id.toLong(),
                            dbManager = dbManager,
                            failure = cancelled,
                        )
                    } catch (failure: Exception) {
                        DownloadExecutionRecovery.retainRecoveryResponsibility(
                            context = c,
                            downloadId = id.toLong(),
                            dbManager = dbManager,
                            failure = failure,
                        )
                    } finally {
                        finishOnce()
                    }
                }
            }.onFailure {
                finishOnce()
            }

        }
    }
}
