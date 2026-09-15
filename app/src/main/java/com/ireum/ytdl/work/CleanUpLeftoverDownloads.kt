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

        /**
         * Runs after the worker has loaded its tuple but before it requests
         * the generation-owned destructive-effect admission.  It is used to
         * deterministically place a configure transition before admission;
         * production correctness is provided by the coordinator gate.
         */
        @Volatile
        internal var beforeCleanupAdmissionForTesting: (() -> Unit)? = null
    }

    override suspend fun doWork(): Result {
        val generation = inputData.getString(CleanupScheduleCoordinator.INPUT_GENERATION)
        val cadence = inputData.getString(CleanupScheduleCoordinator.INPUT_CADENCE)
        val notificationUtil = NotificationUtil(App.instance)
        val id = System.currentTimeMillis().toInt()

        val notification = notificationUtil.createDeletingLeftoverDownloadsNotification()
        if (Build.VERSION.SDK_INT >= 33) {
            setForegroundAsync(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }else{
            setForegroundAsync(ForegroundInfo(id, notification))
        }

        var cleanupFailure: Exception? = null
        var cleanupEffectConsumed = false
        var cleanupEffectRecoveryRequired = false
        val monthlyAnchorDay = inputData.getInt(
            CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY,
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
        )
        val occurrenceAt = inputData.getLong(
            CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT,
            0L,
        )
        try {
            beforeCleanupAdmissionForTesting?.invoke()
            when (
                val admission = CleanupScheduleCoordinator.withCurrentDestructiveEffect(
                    context = applicationContext,
                    generation = generation,
                    cadence = cadence,
                    monthlyAnchorDay = monthlyAnchorDay,
                    occurrenceAt = occurrenceAt,
                ) {
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
                }
            ) {
                CleanupScheduleCoordinator.DestructiveEffectResult.Stale -> {
                    return Result.success(workDataOf("cleanup_schedule_stale" to true))
                }
                CleanupScheduleCoordinator.DestructiveEffectResult.PhaseUnavailable -> {
                    throw IllegalStateException("cleanup effect phase could not be persisted")
                }
                is CleanupScheduleCoordinator.DestructiveEffectResult.Completed -> {
                    cleanupEffectConsumed = true
                }
                is CleanupScheduleCoordinator.DestructiveEffectResult.AlreadyConsumed -> {
                    cleanupEffectConsumed = true
                    cleanupEffectRecoveryRequired = admission.recoveryRequired
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (recoveryRequired: CleanupScheduleCoordinator.EffectPhaseRecoveryRequired) {
            // The cleanup body threw, but its durable reset to ELIGIBLE could
            // not be recorded.  The IN_PROGRESS marker is therefore the
            // fail-closed carrier: do not run the potentially partial effect
            // again; hand the exact occurrence to successor recovery.
            cleanupEffectConsumed = true
            cleanupEffectRecoveryRequired = true
            cleanupFailure = recoveryRequired.cause as? Exception ?: recoveryRequired
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
                monthlyAnchorDay = monthlyAnchorDay,
                completedOccurrenceAt = occurrenceAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!successorAccepted) {
            if (cleanupEffectConsumed) {
                // The destructive effect is already consumed or may have
                // started. Retrying this WorkManager request would run
                // deleteCancelled(), deleteErrored(), and temp-cache cleanup
                // a second time. The coordinator has retained exact
                // predecessor/successor ownership and a
                // replay/reconciliation owner for the unresolved handoff,
                // so complete this occurrence without asking WorkManager to
                // repeat the destructive body.
                return if (
                    CleanupScheduleCoordinator.isCurrentOccurrence(
                        context = applicationContext,
                        generation = generation,
                        cadence = cadence,
                    )
                ) {
                    Result.success(
                        workDataOf(
                            "cleanup_schedule_handoff_pending" to true,
                            "cleanup_effect_recovery_required" to cleanupEffectRecoveryRequired,
                        )
                    )
                } else {
                    Result.success(workDataOf("cleanup_schedule_stale" to true))
                }
            }
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
                    "cleanup_effect_recovery_required" to cleanupEffectRecoveryRequired,
                )
            )
        } ?: if (cleanupEffectRecoveryRequired) {
            Result.success(workDataOf("cleanup_effect_recovery_required" to true))
        } else {
            Result.success()
        }
    }

}
