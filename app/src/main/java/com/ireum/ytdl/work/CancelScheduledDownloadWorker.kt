package com.ireum.ytdl.work

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext


class CancelScheduledDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    @SuppressLint("RestrictedApi")
    override suspend fun doWork(): Result {
        if (isStopped) return Result.success()
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val repository = DownloadRepository(dbManager)

        WorkManager.getInstance(context).cancelAllWorkByTag("download")
        withContext(Dispatchers.IO + NonCancellable) {
            // Snapshot under the global claim/publication lock, then release
            // it before waiting for a per-download side-effect lease.  Holding
            // the locks in the opposite order creates the same AB/BA cycle as
            // worker post-processing and cancellation paths.
            val runningDownloads = withDownloadWorkerExecutionLock {
                dao.getActiveAndPostProcessingDownloadsList()
            }
            var firstFailure: Exception? = null
            fun retainRecoveryResponsibility(downloadId: Long) {
                runCatching {
                    DownloadExecutionRecovery.scheduleRecovery(context, downloadId)
                }.onFailure { schedulingFailure ->
                    firstFailure = firstFailure.addOrSuppress(
                        schedulingFailure as? Exception
                            ?: IllegalStateException(
                                "Recovery owner scheduling failed for download $downloadId",
                                schedulingFailure,
                            )
                    )
                }
            }
            runningDownloads.forEach { snapshot ->
                try {
                    suspend fun cancelAndRequeue(latest: com.ireum.ytdl.database.models.DownloadItem) {
                        if (
                            DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                                context = context,
                                dbManager = dbManager,
                                downloadId = latest.id,
                                executionId = latest.executionId,
                            )
                        ) {
                            return
                        }
                        check(
                            DownloadWorker.cancelProcessesForExecution(
                                latest.id,
                                latest.executionId,
                            )
                        ) {
                            "Native process did not quiesce while stopping ${latest.id}"
                        }
                        when (repository.requeueRunningDownload(latest.id, latest.executionId)) {
                            DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                            DownloadRepository.RunningDownloadRequeueResult.USER_STOP_CONVERGED,
                            DownloadRepository.RunningDownloadRequeueResult.REFUSAL_CONVERGED,
                            DownloadRepository.RunningDownloadRequeueResult.AUTHORITATIVE_ISSUE_CONVERGED,
                            DownloadRepository.RunningDownloadRequeueResult.NOT_RUNNING -> Unit
                            DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT -> {
                                // The History row is already authoritative.  Finish
                                // the debt; never return this row to the runnable queue.
                                repository.completeAndDelete(
                                    id = latest.id,
                                    expectedExecutionId = latest.executionId,
                                )
                            }
                            DownloadRepository.RunningDownloadRequeueResult.OWNERSHIP_LOST -> {
                                error("Download execution ownership was lost while stopping ${latest.id}")
                            }
                        }
                    }

                    if (snapshot.executionId.isNotBlank()) {
                        withDownloadWorkerExecutionSideEffectLease(snapshot.id, snapshot.executionId) {
                            // This short check is the only part that needs the
                            // global lock.  Native cancellation and the DB CAS
                            // happen while the exact per-download lease is held.
                            val current = withDownloadWorkerExecutionLock {
                                dao.getNullableDownloadById(snapshot.id)?.takeIf {
                                    it.executionId == snapshot.executionId &&
                                        it.status in setOf(
                                            DownloadRepository.Status.Active.name,
                                            DownloadRepository.Status.PostProcessing.name,
                                        )
                                }
                            }
                            if (current != null) cancelAndRequeue(current)
                        }
                    } else {
                        val current = withDownloadWorkerExecutionLock {
                            dao.getNullableDownloadById(snapshot.id)?.takeIf {
                                it.executionId.isBlank() &&
                                    it.status in setOf(
                                        DownloadRepository.Status.Active.name,
                                        DownloadRepository.Status.PostProcessing.name,
                                    )
                            }
                        }
                        if (current != null) {
                            withDownloadWorkerExecutionSideEffectLease(snapshot.id, "") {
                                cancelAndRequeue(current)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    firstFailure = firstFailure.addOrSuppress(cancelled)
                    retainRecoveryResponsibility(snapshot.id)
                } catch (failure: Exception) {
                    firstFailure = firstFailure.addOrSuppress(failure)
                    // A's durable row/marker remains its own recovery carrier;
                    // continue to B/C instead of aborting the snapshot pass.
                    retainRecoveryResponsibility(snapshot.id)
                }
            }
            firstFailure?.let { throw it }
        }
        return Result.success()
    }
}
