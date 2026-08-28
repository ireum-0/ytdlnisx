package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLease
import com.ireum.ytdl.work.withDownloadWorkerExecutionLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class PauseDownloadNotificationReceiver private constructor(
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

        /** Deterministic observation of the normal Resume publication point. */
        @Volatile
        internal var resumePublicationObserverForTesting: (() -> Unit)? = null
    }

    constructor() : this(null, false)

    /** Test-only constructor; the manifest continues to use the no-arg form. */
    internal constructor(database: DBManager) : this(database, true)

    override fun onReceive(c: Context, intent: Intent) {
        val result = goAsync()
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                result.finish()
                finishObserverForTesting?.invoke()
            }
        }
        val id = intent.getIntExtra("itemID", 0)
        if (id == 0) {
            finishOnce()
            return
        }
        runCatching {
            val title = intent.getStringExtra("title")
            val notificationUtil = NotificationUtil(c)
            val dbManager = databaseOverride ?: DBManager.getInstance(c)
            CoroutineScope(Dispatchers.IO).launch{
                var paused = false
                val expectedExecutionId = intent.getStringExtra("executionId").orEmpty()
                try {
                    val injectedFailure = beforeAsyncBodyForTesting
                    beforeAsyncBodyForTesting = null
                    injectedFailure?.invoke()
                    if (expectedExecutionId.isNotBlank()) {
                        withDownloadWorkerExecutionSideEffectLease(id.toLong(), expectedExecutionId) {
                            val didPause = withDownloadWorkerExecutionLock {
                                val item = dbManager.downloadDao.getDownloadById(id.toLong())
                                if (item.executionId != expectedExecutionId) {
                                    return@withDownloadWorkerExecutionLock false
                                }
                                check(
                                    DownloadExecutionRecovery.recordPending(
                                        context = c,
                                        item = item,
                                    )
                                ) {
                                    "Could not persist pause recovery responsibility for ${item.id}"
                                }
                                DownloadRepository(dbManager).setDownloadStatus(
                                    item.id,
                                    DownloadRepository.Status.Paused,
                                    expectedExecutionId,
                                )
                            }
                            paused = didPause
                            if (didPause) {
                                // The durable pause won before the native
                                // process cancellation.  The per-Download
                                // lease prevents a newer attempt from starting
                                // until this exact E1 is quiesced.
                                if (
                                    DownloadExecutionRecovery.quiesceAfterDurableStop(
                                        context = c,
                                        downloadId = id.toLong(),
                                        executionId = expectedExecutionId,
                                        dbManager = dbManager,
                                    )
                                ) {
                                    notificationUtil.cancelRunningDownloadNotification(id)
                                } else {
                                    paused = false
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    paused = false
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = c,
                        downloadId = id.toLong(),
                        dbManager = dbManager,
                        failure = cancelled,
                    )
                } catch (failure: Exception) {
                    paused = false
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = c,
                        downloadId = id.toLong(),
                        dbManager = dbManager,
                        failure = failure,
                    )
                } finally {
                    try {
                        withContext(Dispatchers.Main){
                            if (paused) {
                                resumePublicationObserverForTesting?.invoke()
                                notificationUtil.createResumeDownload(
                                    itemID = id,
                                    title = title,
                                    expectedExecutionId = expectedExecutionId,
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (publicationFailure: Exception) {
                        android.util.Log.e(
                            "PauseDownloadNotificationReceiver",
                            "Resume notification publication failed for download $id",
                            publicationFailure,
                        )
                    } finally {
                        finishOnce()
                    }
                }
            }
        }.onFailure {
            finishOnce()
        }
    }
}
