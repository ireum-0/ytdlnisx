package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
        val generation = inputData.getString(CleanupScheduleCoordinator.INPUT_GENERATION)
        val cadence = inputData.getString(CleanupScheduleCoordinator.INPUT_CADENCE)
        // WorkManager cancellation is asynchronous. A request from a
        // superseded/disabled generation must prove current authority before
        // it can enter any destructive cleanup effect.
        if (!CleanupScheduleCoordinator.isCurrentOccurrence(
                context = applicationContext,
                generation = generation,
                cadence = cadence,
            )
        ) {
            return Result.success(workDataOf("cleanup_schedule_stale" to true))
        }

        val notificationUtil = NotificationUtil(App.instance)
        val id = System.currentTimeMillis().toInt()

        val notification = notificationUtil.createDeletingLeftoverDownloadsNotification()
        if (Build.VERSION.SDK_INT >= 33) {
            setForegroundAsync(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }else{
            setForegroundAsync(ForegroundInfo(id, notification))
        }

        var cleanupFailure: Exception? = null
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
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                return Result.retry()
            }
            cleanupFailure = failure
        }

        val successorAccepted = try {
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = applicationContext,
                generation = generation,
                cadence = cadence,
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
        } catch (_: Exception) {
            false
        }
        if (!successorAccepted) {
            return if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        "cleanup_schedule_failure" to true,
                        "cleanup_failure" to (cleanupFailure != null),
                    )
                )
            }
        }

        /*
         * A scheduled occurrence is the owner of the recurring chain.  Once
         * its successor has been accepted, returning FAILURE would make
         * WorkManager fail the appended chain as well.  Keep the occurrence's
         * cleanup failure explicit in output data while completing the chain
         * successfully so the cadence remains alive.
         */
        return cleanupFailure?.let { failure ->
            Result.success(
                workDataOf(
                    "cleanup_failure" to true,
                    "cleanup_failure_message" to (failure.message ?: failure.javaClass.simpleName),
                )
            )
        } ?: Result.success()
    }

}
