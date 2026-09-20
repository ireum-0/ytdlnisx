package com.ireum.ytdl.ui.more.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import com.ireum.ytdl.database.RestoreMutationAdmission

/**
 * Makes AndroidX Preference framework persistence cross the same final
 * ordinary-mutation admission boundary as direct settings writers.
 *
 * The store delegates reads to the app's existing default preferences and
 * changes only the framework's write path; portability and cleanup authority
 * remain owned by the existing policy/coordinator layers.
 */
internal class RestoreAwarePreferenceDataStore(
    context: Context,
) : PreferenceDataStore() {
    companion object {
        @Volatile
        internal var beforeAdmissionForTesting: ((String) -> Unit)? = null

        @Volatile
        internal var mutationForTesting: ((String) -> Unit)? = null
    }
    private val applicationContext = context.applicationContext
    private val preferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(applicationContext)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        preferences.getBoolean(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float =
        preferences.getFloat(key, defValue)

    override fun getInt(key: String, defValue: Int): Int =
        preferences.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long =
        preferences.getLong(key, defValue)

    override fun getString(key: String, defValue: String?): String? =
        preferences.getString(key, defValue)

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        preferences.getStringSet(key, defValues)?.toSet()

    override fun putBoolean(key: String, value: Boolean) {
        mutate(key) { putBoolean(key, value) }
    }

    override fun putFloat(key: String, value: Float) {
        mutate(key) { putFloat(key, value) }
    }

    override fun putInt(key: String, value: Int) {
        mutate(key) { putInt(key, value) }
    }

    override fun putLong(key: String, value: Long) {
        mutate(key) { putLong(key, value) }
    }

    override fun putString(key: String, value: String?) {
        mutate(key) { putString(key, value) }
    }

    override fun putStringSet(key: String, values: Set<String>?) {
        mutate(key) { putStringSet(key, values) }
    }

    private fun mutate(key: String, edit: SharedPreferences.Editor.() -> Unit) {
        beforeAdmissionForTesting?.invoke(key)
        RestoreMutationAdmission.withOrdinaryMutationBlocking(applicationContext) {
            val editor = preferences.edit()
            edit(editor)
            mutationForTesting?.invoke(key)
            check(editor.commit()) {
                "AndroidX Preference persistence was not durable"
            }
        }
    }
}