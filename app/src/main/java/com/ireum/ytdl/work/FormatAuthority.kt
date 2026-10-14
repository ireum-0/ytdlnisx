package com.ireum.ytdl.work

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.FormatAuthoritySchema
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.FormatForegroundClaim
import com.ireum.ytdl.database.models.FormatRowAuthority
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class FormatMemberAuthority(
    val id: Long, val url: String, val type: String,
    val incarnation: Long, val revision: Long, val configuration: String,
    val policy: FormatRequestPolicy, val selected: Boolean,
    val state: String = "PENDING",
    val fetchRequest: String = "",
    val cache: FormatCacheAuthority? = null,
)
internal data class FormatResultAuthority(val id: Long, val revision: Long, val incarnation: Long)
internal data class FormatCacheAuthority(
    val url: String, val revision: Long, val owner: String, val rows: List<FormatResultAuthority>,
)
internal data class FormatFetchAuthority(
    val actor: String, val session: Long, val members: List<FormatMemberAuthority>,
    val cache: FormatCacheAuthority, val writeDownloads: Boolean,
)
internal data class FormatBatchIdentity(val handoffId: String, val generationId: String, val requestId: String)

/** All authority changes and effects are short transactions; callers fetch outside this object. */
internal object FormatAuthority {
    private val gson = Gson()
    private val sessions = ConcurrentHashMap<Any, Long>()
    private fun databaseKey(context: Context, db: DBManager): Any = db.openHelper.databaseName
        ?.let { context.getDatabasePath(it).canonicalPath } ?: db

    suspend fun <T> transaction(context: Context, db: DBManager, block: suspend (Long) -> T): T =
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            val key = databaseKey(context, db)
            val result = db.withTransaction {
                val existing = sessions[key]
                val session = if (existing == null) {
                    FormatAuthoritySchema.install(db.openHelper.writableDatabase)
                    val serial = mint(db)
                    db.formatAuthorityDao.put(FormatRowAuthority("SESSION", "process", serial, serial))
                    // New process authority, not an inference from a pending row shape.
                    db.formatAuthorityDao.foregroundClaims().filter { it.session != serial }.forEach {
                        releaseInTransaction(db, it.id, it.session)
                    }
                    serial
                } else {
                    check(db.formatAuthorityDao.get("SESSION", "process")?.revision == existing) {
                        "Format database session superseded"
                    }
                    existing
                }
                session to block(session)
            }
            sessions.putIfAbsent(key, result.first)
            result.second
        }

    suspend fun mint(db: DBManager): Long = db.formatAuthorityDao.allocate().also {
        check(it > 0) { "Format authority serial exhausted" }
        db.formatAuthorityDao.trimClock(it)
    }

    /** Reserve outside an admission transaction so failed admission cleanup cannot alias a later actor. */
    suspend fun reserveActor(context: Context, db: DBManager, prefix: String): String =
        transaction(context, db) { "$prefix:${mint(db)}" }

    fun configuration(item: DownloadItem): String {
        val ownedInputs = listOf(item.url, item.type, item.format, item.allFormats,
            item.container, item.audioPreferences, item.videoPreferences,
            item.extraCommands, item.downloadSections)
        return MessageDigest.getInstance("SHA-256").digest(gson.toJson(ownedInputs).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun encode(members: List<FormatMemberAuthority>): String = gson.toJson(members)
    fun decode(payload: String): List<FormatMemberAuthority> =
        gson.fromJson(payload, object : TypeToken<List<FormatMemberAuthority>>() {}.type)
            ?: emptyList()
    fun proven(member: FormatMemberAuthority): Boolean = member.id > 0 && member.incarnation > 0 &&
        member.revision > 0 && member.url.isNotBlank() && member.configuration.isNotBlank()

    suspend fun current(db: DBManager, identity: FormatBatchIdentity): Boolean {
        val c = db.workManagerHandoffCarrierDao.get(identity.handoffId) ?: return false
        return c.kind == WorkManagerHandoffCarrier.FORMAT_BATCH && c.generationId == identity.generationId &&
            c.requestId == identity.requestId && c.state in setOf(WorkManagerHandoffCarrier.PENDING_ENQUEUE, WorkManagerHandoffCarrier.ACCEPTED)
    }

    suspend fun claimMember(db: DBManager, item: DownloadItem, actor: String, background: Boolean): FormatRowAuthority {
        val old = checkNotNull(db.formatAuthorityDao.get("DOWNLOAD", item.id.toString()))
        check(old.state == "CURRENT")
        val value = old.copy(revision = mint(db), owner = actor,
            lineage = if (background) actor else old.lineage, completedLineage = "")
        db.formatAuthorityDao.put(value)
        return value
    }

    suspend fun cache(db: DBManager, url: String, actor: String, allRows: Boolean): FormatCacheAuthority {
        val old = db.formatAuthorityDao.get("RESULT_URL", url)
        val serial = mint(db)
        db.formatAuthorityDao.put(FormatRowAuthority("RESULT_URL", url, serial, old?.incarnation ?: serial, owner = actor))
        val results = if (allRows) db.resultDao.getAllByURL(url) else listOfNotNull(db.resultDao.getResultByURL(url))
        return FormatCacheAuthority(url, serial, actor, results.map { result ->
            val stamp = checkNotNull(db.formatAuthorityDao.get("RESULT_ROW", result.id.toString()))
            FormatResultAuthority(result.id, stamp.revision, stamp.incarnation)
        })
    }

    private suspend fun validCache(db: DBManager, cache: FormatCacheAuthority): Boolean {
        val url = db.formatAuthorityDao.get("RESULT_URL", cache.url)
        if (url?.revision != cache.revision || url.owner != cache.owner) return false
        for (row in cache.rows) {
            val item = db.resultDao.getResultByID(row.id) ?: return false
            val stamp = db.formatAuthorityDao.get("RESULT_ROW", row.id.toString()) ?: return false
            if (item.url != cache.url || stamp.state != "CURRENT" || stamp.revision != row.revision ||
                stamp.incarnation != row.incarnation) return false
        }
        return true
    }

    private suspend fun validMember(db: DBManager, member: FormatMemberAuthority, actor: String, statuses: List<String>): DownloadItem? {
        if (!proven(member)) return null
        val item = db.downloadDao.getNullableDownloadById(member.id) ?: return null
        val stamp = db.formatAuthorityDao.get("DOWNLOAD", member.id.toString()) ?: return null
        if (stamp.state != "CURRENT" || stamp.owner != actor || stamp.incarnation != member.incarnation ||
            stamp.revision != member.revision || item.url != member.url || item.type.toString() != member.type ||
            configuration(item) != member.configuration || db.downloadDao.formatEligible(item.id, statuses) != 1) return null
        return item
    }

    /** No partial Download/cache refresh is committed, including when the second field update throws. */
    suspend fun publish(
        context: Context, db: DBManager, lease: FormatFetchAuthority, formats: List<Format>,
        identity: FormatBatchIdentity? = null,
    ): Boolean = transaction(context, db) { session ->
        if (lease.session != session || formats.isEmpty()) return@transaction false
        if (identity != null) {
            if (FormatBatchCancellation.revoked(identity.requestId) || lease.actor != identity.generationId || !current(db, identity)) return@transaction false
            val stored = db.formatBatchPayloadDao.get(identity.handoffId) ?: return@transaction false
            val member = decode(stored.authority).singleOrNull { it.id == lease.members.single().id } ?: return@transaction false
            if (member.state != "PENDING" || member.fetchRequest != identity.requestId || member.cache != lease.cache ||
                member != lease.members.single()) return@transaction false
        } else {
            val stored = db.formatAuthorityDao.foregroundClaims().singleOrNull { it.id == lease.actor && it.session == session }
                ?: return@transaction false
            val captures: List<FormatFetchAuthority> = gson.fromJson(stored.payload,
                object : TypeToken<List<FormatFetchAuthority>>() {}.type)
            if (lease !in captures) return@transaction false
        }
        val allowed = if (identity == null) listOf("Processing", "Saved") else listOf("Saved")
        val items = lease.members.map { validMember(db, it, lease.actor, allowed) ?: return@transaction false }
        if (identity != null && lease.members.any { db.formatAuthorityDao.get("DOWNLOAD", it.id.toString())?.lineage != identity.generationId }) return@transaction false
        if (!validCache(db, lease.cache)) return@transaction false
        if (lease.writeDownloads) {
            items.zip(lease.members).forEach { (item, member) ->
                check(db.downloadDao.publishOwnedFormats(item.id, item.url, item.status,
                    member.policy.choose(context, formats, item.type), formats) == 1)
                // SQL revokes the old token. This authoritative effect settles only its own lineage.
                val stamp = checkNotNull(db.formatAuthorityDao.get("DOWNLOAD", item.id.toString()))
                db.formatAuthorityDao.put(stamp.copy(completedLineage = stamp.lineage))
            }
        }
        lease.cache.rows.forEach { row ->
            check(db.resultDao.publishOwnedFormats(row.id, lease.cache.url, formats) == 1)
        }
        if (identity != null) {
            val payload = checkNotNull(db.formatBatchPayloadDao.get(identity.handoffId))
            val members = decode(payload.authority).map { member ->
                if (member.id != lease.members.single().id) member else {
                    val row = checkNotNull(db.downloadDao.getNullableDownloadById(member.id))
                    val stamp = checkNotNull(db.formatAuthorityDao.get("DOWNLOAD", member.id.toString()))
                    member.copy(state = "COMPLETE", revision = stamp.revision, configuration = configuration(row))
                }
            }
            check(db.formatBatchPayloadDao.updateAuthority(identity.handoffId, encode(members)) == 1)
        }
        true
    }

    suspend fun captureForeground(
        context: Context, db: DBManager, items: List<DownloadItem>, policies: Map<Long, FormatRequestPolicy>, writeDownloads: Boolean,
    ): List<FormatFetchAuthority> {
        val actor = reserveActor(context, db, "foreground65")
        try {
            return transaction(context, db) { session ->
                val members = items.distinctBy { it.id }.mapNotNull { supplied ->
                    val current = db.downloadDao.getNullableDownloadById(supplied.id) ?: return@mapNotNull null
                    if (current.url != supplied.url || current.type != supplied.type ||
                        db.downloadDao.formatEligible(current.id, listOf("Processing", "Saved")) != 1) return@mapNotNull null
                    val policy = policies[current.id] ?: return@mapNotNull null
                    val stamp = claimMember(db, current, actor, false)
                    FormatMemberAuthority(current.id, current.url, current.type.toString(), stamp.incarnation,
                        stamp.revision, configuration(current), policy, true)
                }
                val leases = members.groupBy { it.url }.map { (url, group) ->
                    FormatFetchAuthority(actor, session, group, cache(db, url, actor, true), writeDownloads)
                }
                if (leases.isNotEmpty()) db.formatAuthorityDao.insertForeground(FormatForegroundClaim(actor, session, gson.toJson(leases)))
                leases
            }
        } catch (failure: Throwable) {
            // Handles a lost caller return after commit using the reserved exact actor.
            try { releaseForeground(context, db, actor) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private suspend fun releaseInTransaction(db: DBManager, actor: String, session: Long) {
        db.formatAuthorityDao.getOwned(actor).forEach { value ->
            db.formatAuthorityDao.put(value.copy(revision = mint(db), owner = ""))
        }
        db.formatAuthorityDao.deleteForeground(actor, session)
    }
    suspend fun releaseForeground(context: Context, db: DBManager, actor: String) = withContext(NonCancellable) {
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            db.withTransaction {
                val claim = db.formatAuthorityDao.foregroundClaims().singleOrNull { it.id == actor }
                if (claim != null) releaseInTransaction(db, actor, claim.session)
            }
        }
    }

    suspend fun retainLegacy(context: Context, db: DBManager, requestId: String, ids: List<Long>, reason: String) =
        FormatRecoveryIssues.retainLegacy(context, db, requestId, ids, reason)
}
