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
    val website: String
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

object LocalAddStorage {
    private const val KEY_OPEN_SESSION = "local_add_open_session"
    private const val KEY_PENDING_PREFIX = "local_add_pending_"
    private const val KEY_ENTRIES_PREFIX = "local_add_entries_"
    private const val KEY_PROGRESS_DONE = "local_add_progress_done"
    private const val KEY_PROGRESS_TOTAL = "local_add_progress_total"
    private const val KEY_PROGRESS_TIME = "local_add_progress_time"
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
        prefs(context).edit()
            .putString(KEY_ENTRIES_PREFIX + sessionId, json)
            .apply()
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
