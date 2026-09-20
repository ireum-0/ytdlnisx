package com.ireum.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class LocalAddMatchDto(
    val url: String,
    val title: String,
    val author: String,
    val duration: String,
    val thumb: String,
    val website: String,
    val mediaPublishedAt: Long = 0
)

data class LocalAddCandidateDto(
    val uri: String,
    val treeUri: String?,
    val title: String,
    val ext: String,
    val size: Long,
    val durationSeconds: Int,
    val match: LocalAddMatchDto? = null
)

data class LocalAddEntryDto(
    val uri: String,
    val treeUri: String?
)

data class LocalAddWorkOwner(
    val sessionId: String,
    val requestId: String,
    val uniqueWorkName: String,
    val state: String,
)

object LocalAddStorage {
    private const val KEY_OPEN_SESSION = "local_add_open_session"
    private const val KEY_PENDING_PREFIX = "local_add_pending_"
    private const val KEY_ENTRIES_PREFIX = "local_add_entries_"
    private const val KEY_OWNER_PREFIX = "local_add_owner_"
    private const val KEY_PROGRESS_DONE = "local_add_progress_done"
    private const val KEY_PROGRESS_TOTAL = "local_add_progress_total"
    private const val KEY_PROGRESS_TIME = "local_add_progress_time"

    const val OWNER_PENDING_ENQUEUE = "PENDING_ENQUEUE"
    const val OWNER_ACCEPTED = "ACCEPTED"
    const val OWNER_RETIRED = "RETIRED"

    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun savePending(context: Context, sessionId: String, candidates: List<LocalAddCandidateDto>) {
        val json = gson.toJson(candidates)
        prefs(context).edit()
            .putString(KEY_PENDING_PREFIX + sessionId, json)
            .apply()
    }

    fun loadPending(context: Context, sessionId: String): List<LocalAddCandidateDto> {
        val json = prefs(context).getString(KEY_PENDING_PREFIX + sessionId, null) ?: return emptyList()
        val type = object : TypeToken<List<LocalAddCandidateDto>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun clearPending(context: Context, sessionId: String) {
        prefs(context).edit()
            .remove(KEY_PENDING_PREFIX + sessionId)
            .apply()
    }

    fun saveEntries(context: Context, sessionId: String, entries: List<LocalAddEntryDto>) {
        val json = gson.toJson(entries)
        check(
            prefs(context).edit()
                .putString(KEY_ENTRIES_PREFIX + sessionId, json)
                .commit()
        ) { "LocalAdd entries could not be durably persisted" }
    }

    /**
     * Publishes the exact session payload and its first WorkManager owner
     * together. A process death after this commit leaves a discoverable
     * session even if enqueue has not returned yet.
     */
    fun beginSession(
        context: Context,
        sessionId: String,
        requestId: String,
        entries: List<LocalAddEntryDto>,
    ) {
        require(sessionId.isNotBlank())
        require(requestId.isNotBlank())
        val owner = LocalAddWorkOwner(
            sessionId = sessionId,
            requestId = requestId,
            uniqueWorkName = uniqueWorkName(sessionId),
            state = OWNER_PENDING_ENQUEUE,
        )
        check(
            prefs(context).edit()
                .putString(KEY_ENTRIES_PREFIX + sessionId, gson.toJson(entries))
                .putString(KEY_OWNER_PREFIX + sessionId, gson.toJson(owner))
                .commit()
        ) { "LocalAdd session could not be durably published" }
    }

    fun loadEntries(context: Context, sessionId: String): List<LocalAddEntryDto> {
        val json = prefs(context).getString(KEY_ENTRIES_PREFIX + sessionId, null) ?: return emptyList()
        val type = object : TypeToken<List<LocalAddEntryDto>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun clearEntries(context: Context, sessionId: String) {
        prefs(context).edit()
            .remove(KEY_ENTRIES_PREFIX + sessionId)
            .apply()
    }

    fun uniqueWorkName(sessionId: String): String = "local_add_session_$sessionId"

    fun loadWorkOwner(context: Context, sessionId: String): LocalAddWorkOwner? {
        val json = prefs(context).getString(KEY_OWNER_PREFIX + sessionId, null) ?: return null
        val owner = gson.fromJson(json, LocalAddWorkOwner::class.java)
            ?: error("LocalAdd owner is null for $sessionId")
        require(owner.sessionId == sessionId) { "LocalAdd owner session mismatch" }
        require(owner.state in setOf(OWNER_PENDING_ENQUEUE, OWNER_ACCEPTED, OWNER_RETIRED)) {
            "Unknown LocalAdd owner state: ${owner.state}"
        }
        return owner
    }

    /** Enumerates only explicit owner markers, never historical entry payload keys. */
    fun loadLiveWorkOwners(context: Context): List<LocalAddWorkOwner> =
        prefs(context).all.keys
            .filter { it.startsWith(KEY_OWNER_PREFIX) }
            .mapNotNull { key -> loadWorkOwner(context, key.removePrefix(KEY_OWNER_PREFIX)) }
            .filter { it.state != OWNER_RETIRED }
            .sortedBy { it.sessionId }

    /** Returns false when an explicit user retirement already owns this session. */
    fun trackActiveOwner(
        context: Context,
        sessionId: String,
        requestId: String,
    ): Boolean {
        if (loadEntries(context, sessionId).isEmpty()) return false
        val existing = loadWorkOwner(context, sessionId)
        if (existing?.state == OWNER_RETIRED) return false
        val owner = (existing ?: LocalAddWorkOwner(
            sessionId = sessionId,
            requestId = requestId,
            uniqueWorkName = uniqueWorkName(sessionId),
            state = OWNER_ACCEPTED,
        )).copy(requestId = requestId, state = OWNER_ACCEPTED)
        check(
            prefs(context).edit()
                .putString(KEY_OWNER_PREFIX + sessionId, gson.toJson(owner))
                .commit()
        ) { "LocalAdd owner could not be tracked" }
        return true
    }

    fun markOwnerPending(
        context: Context,
        sessionId: String,
        requestId: String,
    ): Boolean {
        val existing = loadWorkOwner(context, sessionId) ?: return false
        if (existing.state == OWNER_RETIRED) return false
        val updated = existing.copy(requestId = requestId, state = OWNER_PENDING_ENQUEUE)
        check(
            prefs(context).edit()
                .putString(KEY_OWNER_PREFIX + sessionId, gson.toJson(updated))
                .commit()
        ) { "LocalAdd owner pending state could not be persisted" }
        return true
    }

    fun markOwnerAccepted(
        context: Context,
        sessionId: String,
        requestId: String,
    ): Boolean {
        val existing = loadWorkOwner(context, sessionId) ?: return false
        if (existing.state == OWNER_RETIRED || existing.requestId != requestId) return false
        val updated = existing.copy(state = OWNER_ACCEPTED)
        check(
            prefs(context).edit()
                .putString(KEY_OWNER_PREFIX + sessionId, gson.toJson(updated))
                .commit()
        ) { "LocalAdd owner accepted state could not be persisted" }
        return true
    }

    fun retireSession(context: Context, sessionId: String) {
        val existing = loadWorkOwner(context, sessionId)
        val retired = (existing ?: LocalAddWorkOwner(
            sessionId = sessionId,
            requestId = "",
            uniqueWorkName = uniqueWorkName(sessionId),
            state = OWNER_RETIRED,
        )).copy(state = OWNER_RETIRED)
        val editor = prefs(context).edit()
            .putString(KEY_OWNER_PREFIX + sessionId, gson.toJson(retired))
            .remove(KEY_ENTRIES_PREFIX + sessionId)
            .remove(KEY_PENDING_PREFIX + sessionId)
        if (prefs(context).getString(KEY_OPEN_SESSION, null) == sessionId) {
            editor.remove(KEY_OPEN_SESSION)
        }
        check(editor.commit()) { "LocalAdd retirement could not be durably persisted" }
    }

    fun completeSession(context: Context, sessionId: String) {
        retireSession(context, sessionId)
    }

    fun setOpenSession(context: Context, sessionId: String?) {
        prefs(context).edit().putString(KEY_OPEN_SESSION, sessionId).apply()
    }

    fun consumeOpenSession(context: Context): String? {
        val id = prefs(context).getString(KEY_OPEN_SESSION, null)
        if (id != null) {
            prefs(context).edit().remove(KEY_OPEN_SESSION).apply()
        }
        return id
    }

    fun setProgressSnapshot(context: Context, done: Int, total: Int) {
        prefs(context).edit()
            .putInt(KEY_PROGRESS_DONE, done)
            .putInt(KEY_PROGRESS_TOTAL, total)
            .putLong(KEY_PROGRESS_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getProgressSnapshot(context: Context): Triple<Int, Int, Long>? {
        val total = prefs(context).getInt(KEY_PROGRESS_TOTAL, 0)
        if (total <= 0) return null
        val done = prefs(context).getInt(KEY_PROGRESS_DONE, 0)
        val time = prefs(context).getLong(KEY_PROGRESS_TIME, 0L)
        return Triple(done, total, time)
    }

    fun clearProgressSnapshot(context: Context) {
        prefs(context).edit()
            .remove(KEY_PROGRESS_DONE)
            .remove(KEY_PROGRESS_TOTAL)
            .remove(KEY_PROGRESS_TIME)
            .apply()
    }
}
