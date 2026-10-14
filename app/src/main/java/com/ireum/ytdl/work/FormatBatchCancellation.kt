package com.ireum.ytdl.work

import android.content.Context
import androidx.concurrent.futures.await
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.FormatRequestCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Request cancellation is separate from durable semantic format responsibility. */
internal object FormatBatchCancellation {
    const val REQUEST_ID = "formatRequestId"
    private val unpersistedDecisions = ConcurrentHashMap.newKeySet<String>()
    fun observe(requestId: UUID) { unpersistedDecisions.add(requestId.toString()) }
    fun revoked(requestId: String): Boolean = requestId in unpersistedDecisions

    private suspend fun revoke(context: Context, db: DBManager, requestId: UUID): String? {
        val handoff = FormatAuthority.transaction(context, db) {
            val candidates = db.workManagerHandoffCarrierDao.getCurrentFormatRequests(requestId.toString())
            check(candidates.size <= 1) { "Ambiguous format request authority" }
            val current = candidates.singleOrNull() ?: return@transaction null
            db.formatAuthorityDao.retainCancellation(FormatRequestCancellation(
                requestId.toString(), current.handoffId, current.generationId))
            val retained = checkNotNull(db.formatAuthorityDao.getCancellation(requestId.toString()))
            check(retained.handoffId == current.handoffId && retained.generationId == current.generationId)
            check(db.workManagerHandoffCarrierDao.revokeFormatRequest(current.handoffId, current.generationId,
                requestId.toString(), UUID.randomUUID().toString(), System.currentTimeMillis()) == 1)
            check(db.workManagerHandoffCarrierDao.get(current.handoffId)?.requestId != requestId.toString())
            current.handoffId
        }
        // Remove a transient decision only after the exact outgoing request is no longer current.
        unpersistedDecisions.remove(requestId.toString())
        return handoff
    }

    suspend fun cancel(context: Context, db: DBManager, requestId: UUID) = withContext(NonCancellable) {
        observe(requestId)
        var handoff: String? = null
        var submitted = false
        try {
            handoff = revoke(context, db, requestId)
            submitted = true
            WorkManager.getInstance(context).cancelWorkById(requestId).result.await()
        } catch (failure: Throwable) {
            // Do not reinterpret a failed durable revocation as permission for the old request.
            try { if (!submitted) WorkManager.getInstance(context).cancelWorkById(requestId).result.await() }
            catch (native: Throwable) { failure.addSuppressed(native) }
            throw failure
        } finally {
            handoff?.let { WorkManagerHandoffRecovery.ensureConvergence(context, it) }
        }
    }

    suspend fun reconcile(context: Context, db: DBManager) {
        unpersistedDecisions.toList().forEach { id ->
            revoke(context, db, UUID.fromString(id))?.let { WorkManagerHandoffRecovery.ensureConvergence(context, it) }
        }
        val pending = FormatAuthority.transaction(context, db) { db.formatAuthorityDao.pendingCancellations() }
        val manager = WorkManager.getInstance(context)
        for (record in pending) {
            val request = UUID.fromString(record.requestId)
            manager.cancelWorkById(request).result.await()
            val info = manager.getWorkInfoById(request).await()
            if (info?.state?.isFinished == true) FormatAuthority.transaction(context, db) {
                db.formatAuthorityDao.confirmCancellation(record.requestId, record.handoffId, record.generationId)
            }
            // Missing WorkInfo retains the exact tombstone against delayed enqueue acceptance.
        }
    }
}
