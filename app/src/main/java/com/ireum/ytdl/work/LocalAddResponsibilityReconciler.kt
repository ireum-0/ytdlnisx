package com.ireum.ytdl.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.RestoreTransactionCoordinator.RestoreReconciliationAuthority
import com.ireum.ytdl.util.LocalAddStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Owns the exact LocalAdd session responsibility that can be cancelled by
 * F11 History quiescence.  It never discovers work by enumerating raw entry
 * keys: only explicit live owner markers or the exact pre-quiescence
 * WorkManager session identities are eligible.
 */
internal object LocalAddResponsibilityReconciler {
    private const val ACCEPTANCE_TIMEOUT_MS = 5_000L

    suspend fun reconcileForRestore(
        context: Context,
        sessionId: String,
        authority: RestoreReconciliationAuthority,
    ): Boolean = withContext(Dispatchers.IO) {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        reconcile(context.applicationContext, sessionId, authority)
    }

    suspend fun reconcileStartup(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (RestoreGate.isRestoreInProgress(appContext)) return@withContext
        LocalAddStorage.loadLiveWorkOwners(appContext).forEach { owner ->
            reconcile(appContext, owner.sessionId, null)
        }
    }

    /**
     * Captures an active WorkManager identity into the durable owner marker
     * before F11 requests cancellation.  A user-retired session is never
     * reactivated by this path.
     */
    suspend fun trackQuiescedSession(
        context: Context,
        sessionId: String,
        requestId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext false
        RestoreMutationAdmission.withRestoreMutation {
            LocalAddStorage.trackActiveOwner(context.applicationContext, sessionId, requestId)
        }
    }

    private suspend fun reconcile(
        context: Context,
        sessionId: String,
        authority: RestoreReconciliationAuthority?,
    ): Boolean {
        if (authority != null) {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        } else if (RestoreGate.isRestoreInProgress(context)) {
            return false
        }

        var owner = LocalAddStorage.loadWorkOwner(context, sessionId)
        if (owner?.state == LocalAddStorage.OWNER_RETIRED) return true
        if (owner == null) {
            return true
        }
        if (LocalAddStorage.loadEntries(context, sessionId).isEmpty()) {
            mutateOwner(context, authority) {
                LocalAddStorage.completeSession(context, sessionId)
            }
            return true
        }

        val workManager = WorkManager.getInstance(context)
        val existing = owner.requestId
            .takeIf { it.isNotBlank() }
            ?.let { requestId ->
                runCatching {
                    workManager.getWorkInfoById(UUID.fromString(requestId))
                        .get(ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.getOrElse { error ->
                    throw IllegalStateException(
                        "LocalAdd owner query failed for session $sessionId",
                        error,
                    )
                }
            }

        if (existing != null &&
            !existing.state.isFinished &&
            existing.id.toString() == owner.requestId
        ) {
            mutateOwner(context, authority) {
                LocalAddStorage.markOwnerAccepted(context, sessionId, existing.id.toString())
            }
            return true
        }

        val request = OneTimeWorkRequestBuilder<LocalAddWorker>()
            .setInputData(
                androidx.work.workDataOf(LocalAddWorker.KEY_SESSION_ID to sessionId)
            )
            .addTag(LocalAddWorker.TAG)
            .build()

        val prepared = mutateOwner(context, authority) {
            LocalAddStorage.markOwnerPending(context, sessionId, request.id.toString())
        }
        if (!prepared) return true

        if (authority != null) {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        } else {
            check(!RestoreGate.isRestoreInProgress(context)) {
                "Restore transaction became active before LocalAdd enqueue"
            }
        }

        val operation = workManager.enqueueUniqueWork(
            owner.uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            request,
        )
        try {
            operation.result.get(ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "LocalAdd WorkManager enqueue was not accepted for session $sessionId",
                error,
            )
        }

        val accepted = workManager.getWorkInfoById(request.id)
            .get(ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        check(
            accepted != null &&
                !accepted.state.isFinished &&
                accepted.id.toString() == request.id.toString()
        ) {
            "LocalAdd accepted owner is not observable for session $sessionId"
        }

        if (authority != null) {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        }
        check(
            mutateOwner(context, authority) {
                LocalAddStorage.markOwnerAccepted(context, sessionId, request.id.toString())
            }
        ) {
            "LocalAdd owner was retired or replaced before acceptance"
        }
        return true
    }

    private suspend fun <T> mutateOwner(
        context: Context,
        authority: RestoreReconciliationAuthority?,
        block: () -> T,
    ): T = if (authority == null) {
        RestoreMutationAdmission.withOrdinaryMutation(context) { block() }
    } else {
        RestoreMutationAdmission.withRestoreMutation {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
            block()
        }
    }
}