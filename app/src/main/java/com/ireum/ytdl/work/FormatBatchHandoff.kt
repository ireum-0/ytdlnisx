package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.FormatBatchPayload
import com.ireum.ytdl.database.models.FormatRowAuthority
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/** Membership capture includes a database-owned incarnation and authority, not endpoint equality. */
internal data class CapturedFormatDownload(
    val id: Long, val url: String, val type: String, val executionId: String,
    val operationId: String, val retryAttempt: Int,
    val incarnation: Long = 0, val revision: Long = 0, val configuration: String = "",
) {
    companion object {
        fun from(item: DownloadItem, authority: FormatRowAuthority? = null) = CapturedFormatDownload(
            item.id, item.url, item.type.toString(), item.executionId, item.operationId, item.retryAttempt,
            authority?.incarnation ?: 0, authority?.revision ?: 0, FormatAuthority.configuration(item),
        )
    }
}

internal object FormatBatchHandoff {
    data class StagedBatch(
        val handoffId: String?, val capturedIds: List<Long>, val selectedIds: List<Long>,
        val otherIds: List<Long>, val refusedOrStaleIds: List<Long>,
    )
    sealed interface FetchStart {
        data class Ready(val lease: FormatFetchAuthority) : FetchStart
        data object Settled : FetchStart
        data object Recapture : FetchStart
        data object Waiting : FetchStart
        data object StaleRequest : FetchStart
    }

    @Volatile internal var afterCaptureForTesting: (suspend (List<CapturedFormatDownload>) -> Unit)? = null
    @Volatile internal var afterTransitionsForTesting: ((List<Long>) -> Unit)? = null
    @Volatile internal var convergenceForTesting: ((String) -> Unit)? = null

    suspend fun capture(context: Context, database: DBManager, items: List<DownloadItem>): List<CapturedFormatDownload> =
        FormatAuthority.transaction(context, database) {
            items.distinctBy { it.id }.map { snapshot ->
                val current = database.downloadDao.getNullableDownloadById(snapshot.id)
                val compatible = current != null && current.status == snapshot.status &&
                    current.executionId == snapshot.executionId && current.operationId == snapshot.operationId &&
                    current.retryAttempt == snapshot.retryAttempt &&
                    FormatAuthority.configuration(current) == FormatAuthority.configuration(snapshot)
                CapturedFormatDownload.from(snapshot, if (compatible)
                    database.formatAuthorityDao.get("DOWNLOAD", snapshot.id.toString()) else null)
            }
        }

    suspend fun stage(
        context: Context, database: DBManager, capture: List<CapturedFormatDownload>, selection: List<Long>?,
    ): StagedBatch = withContext(Dispatchers.IO) {
        val captured = capture.distinctBy { it.id }.filter { it.id > 0 }
        val handoffId = UUID.randomUUID().toString()
        val generation = FormatAuthority.reserveActor(context, database, "batch65")
        try {
            FormatAuthority.transaction(context, database) {
                val transitioned = captured.filter { item ->
                    val row = database.downloadDao.getNullableDownloadById(item.id)
                    val stamp = database.formatAuthorityDao.get("DOWNLOAD", item.id.toString())
                    item.revision > 0 && item.incarnation > 0 && stamp?.revision == item.revision &&
                        stamp.incarnation == item.incarnation && row != null &&
                        FormatAuthority.configuration(row) == item.configuration &&
                        database.downloadDao.saveCapturedProcessing(item.id, item.url, item.type,
                            item.executionId, item.operationId, item.retryAttempt) == 1
                }.map { it.id }
                afterTransitionsForTesting?.invoke(captured.map { it.id })
                val membership = FormatBatchMembership.partition(captured.map { it.id }, transitioned, selection)
                if (transitioned.isNotEmpty()) {
                    val members = transitioned.map { id ->
                        val row = database.downloadDao.getDownloadById(id)
                        val stamp = FormatAuthority.claimMember(database, row, generation, true)
                        FormatMemberAuthority(id, row.url, row.type.toString(), stamp.incarnation, stamp.revision,
                            FormatAuthority.configuration(row), FormatRequestPolicy.capture(context, database, row.type, row.url),
                            id in membership.selected)
                    }
                    insert(context, database, handoffId, generation, captured.map { it.id }, members)
                }
                StagedBatch(handoffId.takeIf { transitioned.isNotEmpty() }, captured.map { it.id },
                    membership.selected, membership.other, membership.refusedOrStale)
            }
        } finally { converge(context, handoffId) }
    }

    private suspend fun insert(
        context: Context, db: DBManager, handoffId: String, generation: String, captured: List<Long>, members: List<FormatMemberAuthority>,
    ) {
        val now = System.currentTimeMillis()
        check(db.workManagerHandoffCarrierDao.insert(WorkManagerHandoffCarrier(
            handoffId = handoffId, kind = WorkManagerHandoffCarrier.FORMAT_BATCH,
            generationId = generation, requestId = UUID.randomUUID().toString(),
            uniqueWorkName = "format_batch_$handoffId", boundary = handoffId, createdAt = now, updatedAt = now,
        )) != -1L)
        db.formatBatchPayloadDao.insert(FormatBatchPayload(handoffId, encode(captured),
            encode(members.filter { it.selected }.map { it.id }), encode(members.filterNot { it.selected }.map { it.id }),
            UUID.randomUUID().hashCode().let { if (it == 0) 1 else it }, FormatAuthority.encode(members)))
    }
    private fun converge(context: Context, handoffId: String) = convergenceForTesting?.let { it(handoffId) }
        ?: WorkManagerHandoffRecovery.ensureConvergence(context, handoffId)

    suspend fun currentPayload(context: Context, database: DBManager, handoffId: String, generationId: String, requestId: String): FormatBatchPayload? =
        FormatAuthority.transaction(context, database) {
            if (!FormatAuthority.current(database, FormatBatchIdentity(handoffId, generationId, requestId))) null
            else database.formatBatchPayloadDao.get(handoffId)
        }

    fun members(payload: FormatBatchPayload): List<FormatMemberAuthority> {
        val values = FormatAuthority.decode(payload.authority)
        check(values.isNotEmpty() && values.all(FormatAuthority::proven)) { "Unproven format member authority" }
        check(values.map { it.id }.distinct().size == values.size)
        check(values.filter { it.selected }.map { it.id }.toSet() == decode(payload.selectedIds).toSet())
        check(values.filterNot { it.selected }.map { it.id }.toSet() == decode(payload.otherIds).toSet())
        check(values.all { it.id in decode(payload.capturedIds) })
        return values
    }

    private suspend fun settle(db: DBManager, payload: FormatBatchPayload, member: FormatMemberAuthority, state: String, current: DownloadItem? = null) {
        val stamp = db.formatAuthorityDao.get("DOWNLOAD", member.id.toString())
        val updated = member.copy(state = state,
            revision = if (current != null) checkNotNull(stamp).revision else member.revision,
            configuration = current?.let(FormatAuthority::configuration) ?: member.configuration)
        check(db.formatBatchPayloadDao.updateAuthority(payload.handoffId,
            FormatAuthority.encode(members(payload).map { if (it.id == member.id) updated else it })) == 1)
    }

    suspend fun prepareFetch(context: Context, db: DBManager, identity: FormatBatchIdentity, memberId: Long): FetchStart =
        FormatAuthority.transaction(context, db) { session ->
            if (!FormatAuthority.current(db, identity)) return@transaction FetchStart.StaleRequest
            val payload = checkNotNull(db.formatBatchPayloadDao.get(identity.handoffId)) { "Missing format payload" }
            val member = members(payload).single { it.id == memberId }
            if (member.state != "PENDING") return@transaction FetchStart.Settled
            val row = db.downloadDao.getNullableDownloadById(memberId)
            val stamp = db.formatAuthorityDao.get("DOWNLOAD", memberId.toString())
            if (row == null || stamp?.incarnation != member.incarnation || stamp.lineage != identity.generationId) {
                settle(db, payload, member, "SUPERSEDED")
                return@transaction FetchStart.Settled
            }
            if (stamp.completedLineage == identity.generationId && db.downloadDao.formatEligible(row.id, listOf("Saved")) == 1) {
                settle(db, payload, member, "COMPLETE_EXTERNAL", row)
                return@transaction FetchStart.Settled
            }
            if (stamp.owner != identity.generationId || stamp.revision != member.revision ||
                FormatAuthority.configuration(row) != member.configuration) {
                return@transaction if (stamp.owner.isBlank() && db.downloadDao.formatEligible(row.id, listOf("Saved")) == 1)
                    FetchStart.Recapture else FetchStart.Waiting
            }
            if (db.downloadDao.formatEligible(row.id, listOf("Saved")) != 1) return@transaction FetchStart.Waiting
            if (!member.selected) {
                settle(db, payload, member, "COMPLETE_OTHER", row)
                return@transaction FetchStart.Settled
            }
            val cacheActor = "${identity.generationId}:${identity.requestId}:${FormatAuthority.mint(db)}"
            val cache = FormatAuthority.cache(db, row.url, cacheActor, false)
            val current = member.copy(fetchRequest = identity.requestId, cache = cache)
            check(db.formatBatchPayloadDao.updateAuthority(payload.handoffId,
                FormatAuthority.encode(members(payload).map { if (it.id == memberId) current else it })) == 1)
            FetchStart.Ready(FormatFetchAuthority(identity.generationId, session, listOf(current), cache, true))
        }

    /** Transfers only still-owed lineage; the old response is deliberately not an argument. */
    suspend fun recapture(context: Context, db: DBManager, identity: FormatBatchIdentity, memberId: Long): Boolean {
        val generation = FormatAuthority.reserveActor(context, db, "batch65")
        val handoff = UUID.randomUUID().toString()
        try {
            return FormatAuthority.transaction(context, db) {
                if (!FormatAuthority.current(db, identity)) return@transaction false
                val payload = checkNotNull(db.formatBatchPayloadDao.get(identity.handoffId))
                val member = members(payload).single { it.id == memberId }
                val row = db.downloadDao.getNullableDownloadById(memberId) ?: return@transaction false
                val old = db.formatAuthorityDao.get("DOWNLOAD", memberId.toString()) ?: return@transaction false
                if (member.state != "PENDING" || old.incarnation != member.incarnation ||
                    old.lineage != identity.generationId || old.owner.isNotBlank() ||
                    db.downloadDao.formatEligible(memberId, listOf("Saved")) != 1) return@transaction false
                val stamp = FormatAuthority.claimMember(db, row, generation, true)
                val successor = FormatMemberAuthority(row.id, row.url, row.type.toString(), stamp.incarnation,
                    stamp.revision, FormatAuthority.configuration(row), FormatRequestPolicy.capture(context, db, row.type, row.url), member.selected)
                insert(context, db, handoff, generation, listOf(row.id), listOf(successor))
                settle(db, payload, member, "TRANSFERRED")
                true
            }
        } finally { converge(context, handoff) }
    }

    suspend fun notificationMembers(context: Context, db: DBManager, identity: FormatBatchIdentity): List<Long>? =
        FormatAuthority.transaction(context, db) {
            if (!FormatAuthority.current(db, identity)) return@transaction null
            val payload = checkNotNull(db.formatBatchPayloadDao.get(identity.handoffId))
            val all = members(payload)
            if (all.any { it.state == "PENDING" }) return@transaction null
            all.filter { it.state in setOf("COMPLETE", "COMPLETE_EXTERNAL", "COMPLETE_OTHER") }.mapNotNull { member ->
                val row = db.downloadDao.getNullableDownloadById(member.id) ?: return@mapNotNull null
                val stamp = db.formatAuthorityDao.get("DOWNLOAD", member.id.toString()) ?: return@mapNotNull null
                member.id.takeIf { stamp.incarnation == member.incarnation && stamp.revision == member.revision &&
                    stamp.lineage == identity.generationId && row.url == member.url &&
                    FormatAuthority.configuration(row) == member.configuration &&
                    db.downloadDao.formatEligible(member.id, listOf("Saved")) == 1 }
            }
        }

    suspend fun complete(context: Context, database: DBManager, handoffId: String, generationId: String, requestId: String): Boolean =
        FormatAuthority.transaction(context, database) {
            val identity = FormatBatchIdentity(handoffId, generationId, requestId)
            if (!FormatAuthority.current(database, identity)) return@transaction false
            val payload = database.formatBatchPayloadDao.get(handoffId) ?: return@transaction false
            if (members(payload).any { it.state == "PENDING" }) return@transaction false
            database.workManagerHandoffCarrierDao.markFormatResolved(handoffId, generationId, requestId, System.currentTimeMillis()) == 1
        }
    fun encode(ids: List<Long>): String = JSONArray(ids).toString()
    fun decode(ids: String): List<Long> = JSONArray(ids).let { a -> List(a.length()) { a.getLong(it) } }
}
