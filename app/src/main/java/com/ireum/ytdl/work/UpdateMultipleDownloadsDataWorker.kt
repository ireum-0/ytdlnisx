package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.App
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.DownloadMetadataEnrichmentPolicy
import com.ireum.ytdl.util.NotificationUtil
import kotlinx.coroutines.CancellationException


class UpdateMultipleDownloadsDataWorker(private val context: Context,workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val workNotif = NotificationUtil(App.instance).createDataUpdateNotification()

        return ForegroundInfo(
            2000000000,
            workNotif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }


    override suspend fun doWork(): Result {
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val resDao = dbManager.resultDao
        val commandTemplateDao = dbManager.commandTemplateDao
        val resultRepo = ResultRepository(resDao,commandTemplateDao, context)
        val ids = inputData.getLongArray("ids")?.toMutableList() ?: return Result.failure()

        if (!setForegroundSafely()) return Result.retry()
        try {
            val batchResult = MetadataBatchProcessor.process(
                ids = ids,
                loadItem = { id ->
                    if (isStopped) {
                        throw CancellationException("Metadata update worker stopped")
                    }
                    dao.getDownloadById(id)
                },
                shouldProcess = DownloadMetadataEnrichmentPolicy::shouldEnrich,
                processItem = { id, item ->
                    resultRepo.updateDownloadItem(item)?.let { updatedItem ->
                        val currentItem = dao.getNullableDownloadById(id)
                        if (currentItem != null) {
                            updatedItem.status = currentItem.status
                            updatedItem.executionId = currentItem.executionId
                            updatedItem.lastIssueCode = currentItem.lastIssueCode
                            updatedItem.lastIssueStage = currentItem.lastIssueStage
                            dbManager.historyReplacementBarrierDao
                                .getByDownloadId(id)
                                ?.let { barrier ->
                                    updatedItem.lastIssueCode = barrier.issueCode
                                    updatedItem.lastIssueStage = barrier.issueStage
                                }
                            if (currentItem.executionId.isNotBlank()) {
                                dao.updateIfExecutionOwned(updatedItem, currentItem.executionId)
                            } else {
                                dao.updateWithoutUpsert(updatedItem)
                            }
                        }
                    }
                },
                onItemFailure = { id, error ->
                    Log.w(
                        TAG,
                        "Metadata update failed for item id=$id type=${error.javaClass.simpleName}",
                    )
                },
            )
            if (batchResult.failed > 0) {
                Log.w(
                    TAG,
                    "Metadata batch completed with failures attempted=${batchResult.attempted} " +
                        "completed=${batchResult.completed} failed=${batchResult.failed}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return Result.failure()
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "UpdateDownloadsData"
    }
}
