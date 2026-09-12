package com.ireum.ytdl.database

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises portable SharedPreferences capture and restore through SettingsViewModel. */
@RunWith(AndroidJUnit4::class)
class BackupPreferenceProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private val keys = setOf(
        "f7_string",
        "f7_boolean",
        "f7_int",
        "f7_long",
        "f7_float",
        "f7_string_set",
        "f7_bad_int",
        "f7_bad_boolean",
        "f7_bad_set",
        "f7_unknown",
        "app_language",
        "history_visible_child_youtuber_groups",
        "player_playback_position_f7",
        "cache_path",
        "backup_path",
    )
    private val originalValues = mutableMapOf<String, Any?>()
    private var publishedBackup: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        keys.forEach { key ->
            originalValues[key] = preferences.all[key]
        }
        preferences.edit()
            .remove("f7_string")
            .remove("f7_boolean")
            .remove("f7_int")
            .remove("f7_long")
            .remove("f7_float")
            .remove("f7_string_set")
            .remove("f7_bad_int")
            .remove("f7_bad_boolean")
            .remove("f7_bad_set")
            .remove("f7_unknown")
            .remove("app_language")
            .remove("history_visible_child_youtuber_groups")
            .remove("player_playback_position_f7")
            .remove("cache_path")
            .remove("backup_path")
            .commit()
    }

    @After
    fun tearDown() {
        publishedBackup?.let { File(it).delete() }
        val editor = preferences.edit()
        keys.forEach { key ->
            editor.remove(key)
            when (val value = originalValues[key]) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    @Test
    fun portablePreferenceTypesRoundTripThroughBackupAndRestore() = runBlocking {
        val expectedLong = 4_294_967_296L
        preferences.edit()
            .putString("f7_string", "portable")
            .putBoolean("f7_boolean", true)
            .putInt("f7_int", 42)
            .putLong("f7_long", expectedLong)
            .putFloat("f7_float", 3.25f)
            .putStringSet("f7_string_set", setOf("alpha", "beta"))
            .putString("app_language", "de")
            .putStringSet("history_visible_child_youtuber_groups", setOf("17"))
            .putLong("player_playback_position_f7", 99L)
            .commit()

        val backupResult = SettingsViewModel(context as android.app.Application)
            .backup(listOf("settings"))
        assertTrue(backupResult.isSuccess)
        publishedBackup = backupResult.getOrNull()
        val backupFile = publishedBackup?.let(::File)
        assertNotNull(backupFile)
        val root = JsonParser.parseString(backupFile!!.readText()).asJsonObject
        val items = Gson().fromJson(
            root.getAsJsonArray("settings"),
            Array<BackupSettingsItem>::class.java,
        ).toList()
        val byKey = items.associateBy { it.key }
        assertEquals("String", byKey.getValue("f7_string").type)
        assertEquals("portable", byKey.getValue("f7_string").value)
        assertEquals("Boolean", byKey.getValue("f7_boolean").type)
        assertEquals("true", byKey.getValue("f7_boolean").value)
        assertEquals("Int", byKey.getValue("f7_int").type)
        assertEquals("42", byKey.getValue("f7_int").value)
        assertEquals("Long", byKey.getValue("f7_long").type)
        assertEquals(expectedLong.toString(), byKey.getValue("f7_long").value)
        assertEquals("Float", byKey.getValue("f7_float").type)
        assertEquals("3.25", byKey.getValue("f7_float").value)
        assertEquals("StringSet", byKey.getValue("f7_string_set").type)
        assertEquals(setOf("alpha", "beta"), JsonParser.parseString(byKey.getValue("f7_string_set").value).asJsonArray.map { it.asString }.toSet())
        assertFalse(byKey.containsKey("app_language"))
        assertFalse(byKey.containsKey("history_visible_child_youtuber_groups"))
        assertFalse(byKey.containsKey("player_playback_position_f7"))

        preferences.edit()
            .remove("f7_string")
            .remove("f7_boolean")
            .remove("f7_int")
            .remove("f7_long")
            .remove("f7_float")
            .remove("f7_string_set")
            .remove("app_language")
            .remove("history_visible_child_youtuber_groups")
            .remove("player_playback_position_f7")
            .commit()
        val restored = SettingsViewModel(context as android.app.Application).restoreData(
            RestoreAppDataItem(settings = items),
            context,
        )
        assertTrue(restored)
        assertEquals("portable", preferences.getString("f7_string", null))
        assertTrue(preferences.getBoolean("f7_boolean", false))
        assertEquals(42, preferences.getInt("f7_int", 0))
        assertEquals(expectedLong, preferences.getLong("f7_long", 0L))
        assertEquals(3.25f, preferences.getFloat("f7_float", 0f), 0f)
        assertEquals(setOf("alpha", "beta"), preferences.getStringSet("f7_string_set", emptySet()))
        assertFalse(preferences.contains("app_language"))
    }

    @Test
    fun malformedPortableValuesFailWithoutSuccessfulRestore() = runBlocking {
        val malformed = listOf(
            BackupSettingsItem("f7_bad_int", "not-an-int", "Int"),
            BackupSettingsItem("f7_bad_boolean", "yes", "Boolean"),
            BackupSettingsItem("f7_bad_set", "[1]", "StringSet"),
        )
        val restored = SettingsViewModel(context as android.app.Application).restoreData(
            RestoreAppDataItem(settings = malformed),
            context,
        )
        assertFalse(restored)
        assertFalse(preferences.contains("f7_bad_int"))
        assertFalse(preferences.contains("f7_bad_boolean"))
        assertFalse(preferences.contains("f7_bad_set"))
    }

    @Test
    fun unknownPortableTypeFailsInsteadOfBeingSilentlyOmitted() = runBlocking {
        val restored = SettingsViewModel(context as android.app.Application).restoreData(
            RestoreAppDataItem(
                settings = listOf(BackupSettingsItem("f7_unknown", "value", "Parcelable")),
            ),
            context,
        )
        assertFalse(restored)
        assertFalse(preferences.contains("f7_unknown"))
    }
}
