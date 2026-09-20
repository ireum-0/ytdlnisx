package com.ireum.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import com.ireum.ytdl.database.RestoreMutationAdmission

object PendingDuplicateDownloadStore {
    private const val PREF_PENDING_DUPLICATE_DOWNLOADS = "pending_duplicate_download_history_pairs"
    private val lock = Any()

    fun snapshot(sharedPreferences: SharedPreferences): Set<String> {
        return synchronized(lock) {
            sharedPreferences
                .getStringSet(PREF_PENDING_DUPLICATE_DOWNLOADS, emptySet())
                .orEmpty()
                .toSet()
        }
    }

    fun add(
        context: Context,
        sharedPreferences: SharedPreferences,
        newHistoryId: Long,
        existingHistoryId: Long
    ) {
        if (newHistoryId <= 0L || existingHistoryId <= 0L || newHistoryId == existingHistoryId) return
        RestoreMutationAdmission.withOrdinaryMutationBlocking(context.applicationContext) {
            update(sharedPreferences) { pending ->
                pending.add("$newHistoryId:$existingHistoryId")
            }
        }
    }

    fun remove(context: Context, sharedPreferences: SharedPreferences, key: String) {
        if (key.isBlank()) return
        RestoreMutationAdmission.withOrdinaryMutationBlocking(context.applicationContext) {
            update(sharedPreferences) { pending ->
                pending.remove(key)
            }
        }
    }

    private fun update(
        sharedPreferences: SharedPreferences,
        mutate: (MutableSet<String>) -> Unit
    ) {
        synchronized(lock) {
            val pending = sharedPreferences
                .getStringSet(PREF_PENDING_DUPLICATE_DOWNLOADS, emptySet())
                .orEmpty()
                .toMutableSet()
            mutate(pending)
            check(sharedPreferences.edit()
                .putStringSet(PREF_PENDING_DUPLICATE_DOWNLOADS, pending)
                .commit()) {
                "Pending duplicate download state was not durable"
            }
        }
    }
}