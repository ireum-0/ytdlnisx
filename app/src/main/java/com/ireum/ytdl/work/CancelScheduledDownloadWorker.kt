package com.ireum.ytdl.work

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
            runningDownloads.forEach { snapshot ->
                suspend fun cancelAndRequeue(latest: com.ireum.ytdl.database.models.DownloadItem) {
                    if (latest.executionId.isNotBlank()) {
                        DownloadWorker.cancelProcessesForExecution(
                            latest.id,
                            latest.executionId,
                        )
                    }
                    val hasHistoryRefusal =
                        HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                            latest.lastIssueCode
                        ) || dbManager.historyReplacementBarrierDao.getByDownloadId(latest.id) != null
                    if (hasHistoryRefusal) {
                        check(
                            repository.convergeHistoryReplacementRefusal(
                                id = latest.id,
                                expectedExecutionId = latest.executionId,
                                forceError = true,
                            ).downloadUpdated
                        ) {
                            "History refusal could not converge for download ${latest.id}"
                        }
                    } else if (latest.executionId.isNotBlank()) {
                        check(dao.requeueActiveDownload(latest.id, latest.executionId) == 1) {
                            "Download execution ownership was lost while stopping ${latest.id}"
                        }
                    } else {
                        dao.setStatusMultipleFromStatus(
                            listOf(latest.id),
                            latest.status,
                            DownloadRepository.Status.Queued.toString(),
                        )
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
                    if (current != null) cancelAndRequeue(current)
                }
            }
        }
        return Result.success()
    }
}
