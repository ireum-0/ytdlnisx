package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.util.Log
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.NotificationUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class UpdateMultipleDownloadsFormatsWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    companion object {
        /** Returns a fetch seam only for an explicitly matched handoff/member; other owners remain real. */
        @Volatile internal var fetchForTesting: ((FormatBatchIdentity, Long) -> (suspend () -> List<Format>)?)? = null
    }

    override suspend fun doWork(): Result {
        if (RestoreGate.isRestoreInProgress(applicationContext)) return Result.retry()
        val database = WorkManagerHandoffRecovery.databaseForTesting ?: DBManager.getInstance(context)
        val handoff = inputData.getString(WorkManagerHandoffRecovery.INPUT_HANDOFF_ID)
        if (handoff == null) {
            // IDs cannot distinguish old rows from replacements. Retain, never fabricate credentials.
            return try {
                FormatAuthority.retainLegacy(context, database, id.toString(),
                    inputData.getLongArray("ids")?.toList().orEmpty(), "LEGACY_IDS_REQUIRE_CURRENT_PRODUCER")
                Result.failure()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.e("FormatBatchFailure", "Legacy debt persistence failed", failure)
                Result.retry()
            }
        }
        val identity = FormatBatchIdentity(handoff,
            inputData.getString(WorkManagerHandoffRecovery.INPUT_GENERATION_ID).orEmpty(),
            inputData.getString(WorkManagerHandoffRecovery.INPUT_REQUEST_ID).orEmpty())
        if (identity.requestId != id.toString()) return Result.failure()
        val notifications = NotificationUtil(context)
        var notificationId: Int? = null
        try {
            val payload = FormatBatchHandoff.currentPayload(context, database, handoff, identity.generationId, identity.requestId)
            if (payload == null) {
                return unprovenResult(database, identity)
            }
            val members = try { FormatBatchHandoff.members(payload) } catch (failure: Exception) {
                Log.e("FormatBatchFailure", "Unproven member payload: $handoff", failure)
                return unprovenResult(database, identity)
            }
            check(payload.notificationId != 0)
            notificationId = payload.notificationId
            val notification = notifications.createFormatsUpdateNotification()
            setForeground(if (Build.VERSION.SDK_INT >= 33)
                ForegroundInfo(payload.notificationId, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(payload.notificationId, notification))
            val repository = ResultRepository(database.resultDao, database.commandTemplateDao, context)
            var completed = members.count { it.selected && it.state in setOf("COMPLETE", "COMPLETE_EXTERNAL") }
            for (member in members) {
                currentCoroutineContext().ensureActive()
                if (isStopped || FormatBatchCancellation.revoked(identity.requestId)) return Result.retry()
                when (val start = FormatBatchHandoff.prepareFetch(context, database, identity, member.id)) {
                    FormatBatchHandoff.FetchStart.StaleRequest -> return Result.success()
                    FormatBatchHandoff.FetchStart.Waiting -> return Result.retry()
                    FormatBatchHandoff.FetchStart.Recapture -> {
                        if (!FormatBatchHandoff.recapture(context, database, identity, member.id)) return Result.retry()
                    }
                    FormatBatchHandoff.FetchStart.Settled -> Unit
                    is FormatBatchHandoff.FetchStart.Ready -> {
                        val policy = start.lease.members.single().policy
                        val formats = fetchForTesting?.invoke(identity, member.id)?.invoke()
                            ?: repository.getFormats(member.url, policy.source, policy.allowFallback)
                        currentCoroutineContext().ensureActive()
                        check(formats.isNotEmpty()) { "Empty formats for member ${member.id}" }
                        if (isStopped || !FormatAuthority.publish(context, database, start.lease, formats, identity)) return Result.retry()
                        completed++
                        notifications.updateFormatUpdateNotification(payload.notificationId, id, completed,
                            members.count { it.selected })
                    }
                }
            }
            return RestoreMutationAdmission.withOrdinaryMutation(context) {
                currentCoroutineContext().ensureActive()
                if (isStopped || FormatBatchCancellation.revoked(identity.requestId)) return@withOrdinaryMutation Result.retry()
                val ids = FormatBatchHandoff.notificationMembers(context, database, identity) ?: return@withOrdinaryMutation Result.retry()
                // Completion notification is owed before carrier retirement; failure retains retry debt.
                if (ids.isNotEmpty()) notifications.showFormatsUpdatedNotification(ids)
                if (FormatBatchHandoff.complete(context, database, handoff, identity.generationId, identity.requestId))
                    Result.success() else Result.retry()
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.e("FormatBatchFailure", "Format member effects incomplete: $handoff", failure)
            return Result.retry()
        } finally {
            notificationId?.let { notifications.cancelDownloadNotification(it) }
        }
    }

    private suspend fun unprovenResult(database: DBManager, identity: FormatBatchIdentity): Result {
        val carrier = database.workManagerHandoffCarrierDao.get(identity.handoffId)
        if (carrier == null || carrier.generationId != identity.generationId || carrier.requestId != identity.requestId)
            return Result.success()
        return when (FormatRecoveryIssues.carrierDisposition(context, database, carrier)) {
            FormatRecoveryIssues.CarrierDisposition.USER_ACTION_REQUIRED -> Result.failure()
            FormatRecoveryIssues.CarrierDisposition.RETIRED -> Result.success()
            FormatRecoveryIssues.CarrierDisposition.PROCEED -> Result.retry()
        }
    }
}
