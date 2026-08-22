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
                    if (current.executionId.isNotBlank()) {
                        DownloadWorker.cancelProcessesForExecution(
                            current.id,
                            current.executionId,
                        )
                    }
                    val hasHistoryRefusal =
                        HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                            current.lastIssueCode
                        ) || dbManager.historyReplacementBarrierDao.getByDownloadId(current.id) != null
                    if (hasHistoryRefusal) {
                        check(
                            repository.convergeHistoryReplacementRefusal(
                                id = current.id,
                                expectedExecutionId = current.executionId,
                                forceError = true,
                            ).downloadUpdated
                        ) {
                            "History refusal could not converge for download ${current.id}"
                        }
                    } else if (current.executionId.isNotBlank()) {
                        dao.requeueActiveDownload(current.id, current.executionId)
                    } else {
                        dao.setStatusMultipleFromStatus(
                            listOf(current.id),
                            current.status,
                            DownloadRepository.Status.Queued.toString(),
                        )
                    }
                }
            }
        }
        return Result.success()
    }
}
