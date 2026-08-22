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
            withDownloadWorkerExecutionLock {
                val runningDownloads = dao.getActiveAndPostProcessingDownloadsList()
                runningDownloads.forEach { snapshot ->
                    // Re-read while holding the same mutex used by claim plus
                    // owner publication.  A stale E1 snapshot must not kill
                    // the native process or requeue a newer E2.
                    val current = dao.getNullableDownloadById(snapshot.id)
                    if (
                        current == null ||
                            current.executionId != snapshot.executionId ||
                            current.status !in setOf(
                                DownloadRepository.Status.Active.name,
                                DownloadRepository.Status.PostProcessing.name,
                            )
                    ) {
                        return@forEach
                    }
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
                            dao.requeueActiveDownload(latest.id, latest.executionId)
                        } else {
                            dao.setStatusMultipleFromStatus(
                                listOf(latest.id),
                                latest.status,
                                DownloadRepository.Status.Queued.toString(),
                            )
                        }
                    }
                    if (current.executionId.isNotBlank()) {
                        withDownloadWorkerExecutionSideEffectLease(current.id, current.executionId) {
                            val latest = dao.getNullableDownloadById(current.id)
                            if (
                                latest?.executionId == current.executionId &&
                                latest.status in setOf(
                                    DownloadRepository.Status.Active.name,
                                    DownloadRepository.Status.PostProcessing.name,
                                )
                            ) {
                                cancelAndRequeue(latest)
                            }
                        }
                    } else {
                        cancelAndRequeue(current)
                    }
                }
            }
        }
        return Result.success()
    }
}
