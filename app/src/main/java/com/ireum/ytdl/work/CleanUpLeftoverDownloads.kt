package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.App
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.storage.AppCacheCategory
import com.ireum.ytdl.util.storage.AppCacheManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import java.util.Calendar


class CleanUpLeftoverDownloads(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    companion object {
        const val MAX_ATTEMPTS = 3

        /** Null-default seam for deterministic cleanup retry tests. */
        @Volatile
        internal var cleanupOverrideForTesting: (suspend () -> Unit)? = null
    }

    override suspend fun doWork(): Result {
        val notificationUtil = NotificationUtil(App.instance)
        val id = System.currentTimeMillis().toInt()

        val notification = notificationUtil.createDeletingLeftoverDownloadsNotification()
        if (Build.VERSION.SDK_INT >= 33) {
            setForegroundAsync(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }else{
            setForegroundAsync(ForegroundInfo(id, notification))
        }

        try {
            val override = cleanupOverrideForTesting
            if (override != null) {
                override()
            } else {
                val dbManager = DBManager.getInstance(context)
                val downloadRepo = DownloadRepository(dbManager)
                LowQualityRedownloadLedger.refresh(context, downloadRepo.deleteCancelled())
                LowQualityRedownloadLedger.refresh(context, downloadRepo.deleteErrored())

                val activeDownloadCount = downloadRepo.getActiveDownloadsCount()
                if (activeDownloadCount == 0){
                    AppCacheManager(context).delete(setOf(AppCacheCategory.DOWNLOAD_TEMP))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }

        try {
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = applicationContext,
                generation = inputData.getString(CleanupScheduleCoordinator.INPUT_GENERATION),
                cadence = inputData.getString(CleanupScheduleCoordinator.INPUT_CADENCE),
                monthlyAnchorDay = inputData.getInt(
                    CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY,
                    Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
                ),
                completedOccurrenceAt = inputData.getLong(
                    CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT,
                    0L,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }

        return Result.success()
    }

}
