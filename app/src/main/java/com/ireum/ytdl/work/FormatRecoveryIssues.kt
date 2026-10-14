package com.ireum.ytdl.work

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.models.FormatLegacyDebt
import com.ireum.ytdl.database.models.FormatRecoveryIssue
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Durable format-only user decisions. No issue or WorkInfo observation grants member/cache authority. */
internal object FormatRecoveryIssues {
    private const val LEGACY = "RETAINED_LEGACY_DEBT_MEMBER_ORIGIN_UNPROVABLE"
    private const val CARRIER = "EXACT_CARRIER_MEMBER_ORIGIN_UNPROVABLE"

    enum class CarrierDisposition { PROCEED, USER_ACTION_REQUIRED, RETIRED }
    data class CandidateObservation(val requestIds: List<String>, val truncated: Boolean)

    fun observeOpen(db: DBManager): Flow<List<FormatRecoveryIssue>> =
        db.formatAuthorityDao.observeOpenRecoveryIssues()

    private suspend fun ensure(
        db: DBManager, requestId: String, origin: String, reason: String, payload: String,
        handoffId: String = "", generationId: String = "",
    ): FormatRecoveryIssue {
        val dao = db.formatAuthorityDao
        dao.recoveryIssueForOrigin(requestId, handoffId, generationId, origin)?.let { return it }
        dao.insertRecoveryIssue(FormatRecoveryIssue(
            issueId = UUID.randomUUID().toString(), requestId = requestId,
            origin = origin, reason = reason, payload = payload,
            handoffId = handoffId, generationId = generationId,
        ))
        return checkNotNull(dao.recoveryIssueForOrigin(requestId, handoffId, generationId, origin))
    }

    suspend fun retainLegacy(
        context: Context, db: DBManager, requestId: String, ids: List<Long>, reason: String,
    ): FormatRecoveryIssue = RestoreMutationAdmission.withOrdinaryMutation(context) {
        db.withTransaction {
            val dao = db.formatAuthorityDao
            dao.retainLegacy(FormatLegacyDebt(requestId, FormatBatchHandoff.encode(ids), reason))
            val retained = checkNotNull(dao.getLegacy(requestId))
            ensure(db, retained.requestId, LEGACY, retained.reason, retained.payload)
        }
    }

    /** A standalone query, deliberately not joined to carriers or surviving Download IDs. */
    suspend fun discoverRetained(context: Context, db: DBManager): List<FormatRecoveryIssue> =
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            db.withTransaction {
                discoverRetainedWithinTransaction(db)
            }
        }

    /** Caller owns ordinary or current Restore admission and the Room transaction. */
    internal suspend fun discoverRetainedWithinTransaction(db: DBManager): List<FormatRecoveryIssue> {
        db.formatAuthorityDao.legacyDebts().forEach { debt ->
            ensure(db, debt.requestId, LEGACY, debt.reason, debt.payload)
        }
        val issues = db.formatAuthorityDao.recoveryIssues()
        issues.filter { it.state == FormatRecoveryIssue.ACKNOWLEDGED }.forEach { issue ->
            finishAcknowledgedWithinTransaction(db, issue)
        }
        return issues.filter { it.state == FormatRecoveryIssue.OPEN }
    }

    /** Public observations only. Neither tags nor lack of a carrier prove an old numeric payload. */
    suspend fun observeCandidates(context: Context): CandidateObservation = withContext(Dispatchers.IO) {
        val query = WorkQuery.Builder.fromTags(listOf(UpdateMultipleDownloadsFormatsWorker::class.java.name))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
            .build()
        val found = WorkManager.getInstance(context).getWorkInfos(query).get(5, TimeUnit.SECONDS)
        CandidateObservation(found.take(64).map { it.id.toString() }, found.size > 64)
    }

    private data class Unproven(val reason: String, val payload: String)
    private suspend fun unproven(db: DBManager, handoffId: String): Unproven? {
        val payload = db.formatBatchPayloadDao.get(handoffId)
            ?: return Unproven("MISSING_FORMAT_PAYLOAD", "PAYLOAD_ABSENT")
        try {
            FormatBatchHandoff.members(payload)
            return null
        } catch (_: Exception) {
            // Parsing is synchronous. Preserve the first actual record, not invented member stamps.
            return Unproven("UNPROVEN_FORMAT_PAYLOAD", JSONArray()
                .put(payload.handoffId).put(payload.capturedIds).put(payload.selectedIds)
                .put(payload.otherIds).put(payload.notificationId).put(payload.authority).toString())
        }
    }

    /** Caller owns Restore admission and a Room transaction. No network or WorkManager wait here. */
    suspend fun carrierDispositionWithinTransaction(
        db: DBManager, snapshot: WorkManagerHandoffCarrier,
    ): CarrierDisposition {
        if (snapshot.kind != WorkManagerHandoffCarrier.FORMAT_BATCH) return CarrierDisposition.PROCEED
        val current = db.workManagerHandoffCarrierDao.get(snapshot.handoffId)
        if (current?.kind != WorkManagerHandoffCarrier.FORMAT_BATCH ||
            current.generationId != snapshot.generationId || current.requestId != snapshot.requestId ||
            current.state !in setOf(WorkManagerHandoffCarrier.PENDING_ENQUEUE, WorkManagerHandoffCarrier.ACCEPTED)
        ) return CarrierDisposition.RETIRED
        val evidence = unproven(db, current.handoffId) ?: return CarrierDisposition.PROCEED
        val issue = ensure(db, current.requestId, CARRIER, evidence.reason, evidence.payload,
            current.handoffId, current.generationId)
        if (issue.state == FormatRecoveryIssue.ACKNOWLEDGED) {
            check(db.workManagerHandoffCarrierDao.markFormatResolved(current.handoffId,
                current.generationId, current.requestId, System.currentTimeMillis()) == 1)
            return CarrierDisposition.RETIRED
        }
        check(issue.state == FormatRecoveryIssue.OPEN) { "Unknown format issue state" }
        return CarrierDisposition.USER_ACTION_REQUIRED
    }

    suspend fun carrierDisposition(
        context: Context, db: DBManager, snapshot: WorkManagerHandoffCarrier,
    ): CarrierDisposition = RestoreMutationAdmission.withOrdinaryMutation(context) {
        db.withTransaction { carrierDispositionWithinTransaction(db, snapshot) }
    }

    private suspend fun finishAcknowledgedWithinTransaction(db: DBManager, issue: FormatRecoveryIssue) {
        if (issue.origin != CARRIER || issue.handoffId.isEmpty() || issue.generationId.isEmpty()) return
        val carrier = db.workManagerHandoffCarrierDao.get(issue.handoffId) ?: return
        if (carrier.kind == WorkManagerHandoffCarrier.FORMAT_BATCH &&
            carrier.generationId == issue.generationId && carrier.requestId == issue.requestId &&
            carrier.state in setOf(WorkManagerHandoffCarrier.PENDING_ENQUEUE, WorkManagerHandoffCarrier.ACCEPTED)
        ) carrierDispositionWithinTransaction(db, carrier)
    }

    /** The reachable UI calls this exact CAS. Dismissal never selects Downloads or cancels a request. */
    suspend fun acknowledge(context: Context, db: DBManager, expected: FormatRecoveryIssue): Boolean =
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            db.withTransaction {
                val dao = db.formatAuthorityDao
                val current = dao.recoveryIssue(expected.issueId) ?: return@withTransaction false
                if (current != expected || current.state != FormatRecoveryIssue.OPEN) return@withTransaction false
                val changed = dao.acknowledgeRecoveryIssue(current.issueId, current.requestId,
                    current.handoffId, current.generationId, current.origin, System.currentTimeMillis())
                if (changed != 1) return@withTransaction false
                finishAcknowledgedWithinTransaction(db, checkNotNull(dao.recoveryIssue(current.issueId)))
                true
            }
        }
}
