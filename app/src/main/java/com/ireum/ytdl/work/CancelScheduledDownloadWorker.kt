package com.ireum.ytdl.work

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.yausername.youtubedl_android.YoutubeDL


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

        val runningDownloads = dao.getActiveDownloadsList()
        WorkManager.getInstance(context).cancelAllWorkByTag("download")
        runningDownloads.forEach {
            YoutubeDL.getInstance().destroyProcessById(it.id.toString())
            YoutubeDLCompat.destroyProcessById(it.id.toString())
            val hasHistoryRefusal =
                HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(it.lastIssueCode) ||
                    dbManager.historyReplacementBarrierDao.getByDownloadId(it.id) != null
            if (hasHistoryRefusal) {
                check(
                    repository.convergeHistoryReplacementRefusal(
                        id = it.id,
                        expectedExecutionId = it.executionId,
                        forceError = true,
                    ).downloadUpdated
                ) {
                    "History refusal could not converge for download ${it.id}"
                }
            } else if (it.executionId.isNotBlank()) {
                dao.requeueActiveDownload(it.id, it.executionId)
            } else {
                dao.setStatusMultipleFromStatus(
                    listOf(it.id),
                    DownloadRepository.Status.Active.toString(),
                    DownloadRepository.Status.Queued.toString(),
                )
            }
        }
        return Result.success()
    }
}
