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
import com.ireum.ytdl.database.models.DownloadItem
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
        var cleanupEffectIncomplete = false
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
                prepare = {
                    prepareCleanupEffectJournal()
                },
                effect = { journal ->
                    executeCleanupEffect(journal)
                },
            )
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
            // The body contains independently committing effects.  Its exact
            // journal remains IN_PROGRESS and records the unfinished suffix;
            // never publish a successor while that responsibility is still
            // incomplete.
            cleanupEffectIncomplete = true
            cleanupEffectRecoveryRequired = true
            cleanupFailure = recoveryRequired.cause as? Exception ?: recoveryRequired
        } catch (failure: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                return Result.retry()
            }
            cleanupFailure = failure
        }

        if (cleanupEffectIncomplete || !cleanupEffectConsumed) {
            return if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        "cleanup_schedule_failure" to true,
                        "cleanup_failure" to (cleanupFailure != null),
                        "cleanup_effect_recovery_required" to cleanupEffectRecoveryRequired,
                    )
                )
            }
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

    private fun prepareCleanupEffectJournal(): CleanupEffectJournal {
        val occurrenceGeneration = requireNotNull(
            inputData.getString(CleanupScheduleCoordinator.INPUT_GENERATION),
        )
        val occurrenceCadence = requireNotNull(
            inputData.getString(CleanupScheduleCoordinator.INPUT_CADENCE),
        )
        val occurrenceAnchor = inputData.getInt(
            CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY,
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
        )
        val occurrenceTime = inputData.getLong(
            CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT,
            0L,
        )

        // The override is a narrow test seam for schedule/worker tests.  The
        // real production path below always captures repository and cache
        // targets before phase admission.
        if (cleanupOverrideForTesting != null) {
            return CleanupEffectJournal(
                generation = occurrenceGeneration,
                cadence = occurrenceCadence,
                monthlyAnchorDay = occurrenceAnchor,
                occurrenceAt = occurrenceTime,
            )
        }

        val repository = DownloadRepository(DBManager.getInstance(context))
        val cancelledTargets = repository.getCancelledDownloads()
        val erroredTargets = repository.getErroredDownloads()
        val tempCleanupRequired = repository.getActiveDownloadsCount() == 0
        val tempSnapshot = if (tempCleanupRequired) {
            AppCacheManager(context).snapshotExact(AppCacheCategory.DOWNLOAD_TEMP)
                ?: throw IllegalStateException("unable to capture exact download temp cache")
        } else {
            null
        }
        return CleanupEffectJournal(
            generation = occurrenceGeneration,
            cadence = occurrenceCadence,
            monthlyAnchorDay = occurrenceAnchor,
            occurrenceAt = occurrenceTime,
            cancelledTargets = cancelledTargets,
            erroredTargets = erroredTargets,
            cancelledOperationIds = cancelledTargets.mapNotNull { it.operationId.takeIf(String::isNotBlank) },
            erroredOperationIds = erroredTargets.mapNotNull { it.operationId.takeIf(String::isNotBlank) },
            cancelledCacheCleanupRequiredIds = repository.exactCacheCleanupRequired(cancelledTargets),
            erroredCacheCleanupRequiredIds = repository.exactCacheCleanupRequired(erroredTargets),
            tempSnapshot = tempSnapshot,
            tempCleanupRequired = tempCleanupRequired,
            tempCleanupComplete = !tempCleanupRequired,
        )
    }

    private suspend fun executeCleanupEffect(journal: CleanupEffectJournal) {
        cleanupOverrideForTesting?.invoke()
        if (cleanupOverrideForTesting != null) {
            advanceJournal(journal) { current ->
                current.copy(
                    cancelledDeletionComplete = true,
                    cancelledRefreshComplete = true,
                    erroredDeletionComplete = true,
                    erroredRefreshComplete = true,
                    tempCleanupComplete = true,
                )
            }
            return
        }

        val repository = DownloadRepository(DBManager.getInstance(context))
        var current = journal
        if (!current.cancelledDeletionComplete) {
            val affectedOperationIds = repository.deleteCancelledExactTargets(
                current.cancelledTargets,
            )
            current = advanceJournal(current) {
                it.copy(
                    cancelledDeletionComplete = true,
                    cancelledOperationIds = (it.cancelledOperationIds + affectedOperationIds)
                        .filter(String::isNotBlank)
                        .distinct(),
                )
            }
        }
        current = advanceExactCacheCleanup(
            current = current,
            targets = current.cancelledTargets,
            requiredIds = current.cancelledCacheCleanupRequiredIds,
            completedIds = current.cancelledCacheCleanupCompletedIds,
            repository = repository,
            update = { journal, completed ->
                journal.copy(cancelledCacheCleanupCompletedIds = completed)
            },
        )
        if (!current.cancelledRefreshComplete) {
            LowQualityRedownloadLedger.refresh(context, current.cancelledOperationIds)
            current = advanceJournal(current) { it.copy(cancelledRefreshComplete = true) }
        }
        if (!current.erroredDeletionComplete) {
            val affectedOperationIds = repository.deleteErroredExactTargets(
                current.erroredTargets,
            )
            current = advanceJournal(current) {
                it.copy(
                    erroredDeletionComplete = true,
                    erroredOperationIds = (it.erroredOperationIds + affectedOperationIds)
                        .filter(String::isNotBlank)
                        .distinct(),
                )
            }
        }
        current = advanceExactCacheCleanup(
            current = current,
            targets = current.erroredTargets,
            requiredIds = current.erroredCacheCleanupRequiredIds,
            completedIds = current.erroredCacheCleanupCompletedIds,
            repository = repository,
            update = { journal, completed ->
                journal.copy(erroredCacheCleanupCompletedIds = completed)
            },
        )
        if (!current.erroredRefreshComplete) {
            LowQualityRedownloadLedger.refresh(context, current.erroredOperationIds)
            current = advanceJournal(current) { it.copy(erroredRefreshComplete = true) }
        }
        if (!current.tempCleanupComplete) {
            val snapshot = current.tempSnapshot
                ?: throw CleanupScheduleCoordinator.EffectPhaseRecoveryRequired(
                    IllegalStateException("cleanup temp snapshot is missing"),
                )
            val deletion = AppCacheManager(context).deleteExact(snapshot)
            if (!deletion.isComplete) {
                throw CleanupScheduleCoordinator.EffectPhaseRecoveryRequired(
                    IllegalStateException("exact cleanup temp deletion is incomplete"),
                )
            }
            advanceJournal(current) { it.copy(tempCleanupComplete = true) }
        }
    }

    private fun advanceExactCacheCleanup(
        current: CleanupEffectJournal,
        targets: List<DownloadItem>,
        requiredIds: List<Long>,
        completedIds: List<Long>,
        repository: DownloadRepository,
        update: (CleanupEffectJournal, List<Long>) -> CleanupEffectJournal,
    ): CleanupEffectJournal {
        var journal = current
        var completed = completedIds.distinct()
        val targetsById = targets.associateBy { it.id }
        requiredIds.distinct().filterNot { it in completed }.forEach { targetId ->
            val target = targetsById[targetId]
                ?: throw CleanupScheduleCoordinator.EffectPhaseRecoveryRequired(
                    IllegalStateException("cleanup cache target is missing from journal"),
                )
            if (!repository.deleteExactCacheForTarget(target)) {
                throw CleanupScheduleCoordinator.EffectPhaseRecoveryRequired(
                    IllegalStateException("exact cleanup cache deletion is incomplete"),
                )
            }
            completed = (completed + targetId).distinct()
            journal = advanceJournal(journal) { existing ->
                update(existing, completed)
            }
        }
        return journal
    }

    private fun advanceJournal(
        current: CleanupEffectJournal,
        update: (CleanupEffectJournal) -> CleanupEffectJournal,
    ): CleanupEffectJournal = CleanupScheduleCoordinator.updateEffectJournal(
        context = applicationContext,
        generation = current.generation,
        cadence = current.cadence,
        monthlyAnchorDay = current.monthlyAnchorDay,
        occurrenceAt = current.occurrenceAt,
        update = update,
    ) ?: throw CleanupScheduleCoordinator.EffectPhaseRecoveryRequired(
        IllegalStateException("cleanup effect journal transition could not be persisted"),
    )

}
