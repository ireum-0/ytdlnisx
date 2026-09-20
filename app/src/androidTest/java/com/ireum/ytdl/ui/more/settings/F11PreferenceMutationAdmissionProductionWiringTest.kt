package com.ireum.ytdl.ui.more.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreOutcome
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production-wiring proof for AndroidX Preference final-mutation admission. */
@RunWith(AndroidJUnit4::class)
class F11PreferenceMutationAdmissionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private var originalValues: Map<String, Any?> = emptyMap()

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalValues = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        clearHooks()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        clearHooks()
        runCatching { RestoreTransactionCoordinator.recover(context) }
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        val editor = preferences.edit().clear()
        originalValues.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun switchPreferenceOrdinaryWriterWinsBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins(
            restoreValue = BackupSettingsItem("use_alarm_for_scheduling", "false", "Boolean"),
            write = { _, switch, _, _ -> switch.isChecked = true },
            expectedOrdinary = true,
        )
    }

    @Test
    fun listPreferenceOrdinaryWriterWinsBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins(
            restoreValue = BackupSettingsItem("preferred_download_type", "audio", "String"),
            write = { _, _, list, _ -> list.value = "video" },
            expectedOrdinary = "video",
        )
    }

    @Test
    fun editTextPreferenceOrdinaryWriterWinsBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins(
            restoreValue = BackupSettingsItem("proxy", "restore-proxy", "String"),
            write = { _, _, _, edit -> edit.text = "ordinary-proxy" },
            expectedOrdinary = "ordinary-proxy",
        )
    }

    @Test
    fun switchPreferenceResetWinsBeforeFrameworkMutation(): Unit = runBlocking {
        resetWins(
            key = "use_alarm_for_scheduling",
            initial = false,
            restoreValue = BackupSettingsItem("use_alarm_for_scheduling", "true", "Boolean"),
            write = { _, switch, _, _ -> switch.isChecked = true },
            expectedBefore = false,
        )
    }

    @Test
    fun listPreferenceResetWinsBeforeFrameworkMutation(): Unit = runBlocking {
        resetWins(
            key = "preferred_download_type",
            initial = "video",
            restoreValue = BackupSettingsItem("preferred_download_type", "audio", "String"),
            write = { _, _, list, _ -> list.value = "audio" },
            expectedBefore = "video",
        )
    }

    @Test
    fun editTextPreferenceResetWinsBeforeFrameworkMutation(): Unit = runBlocking {
        resetWins(
            key = "proxy",
            initial = "before-reset",
            restoreValue = BackupSettingsItem("proxy", "restored", "String"),
            write = { _, _, _, edit -> edit.text = "ordinary" },
            expectedBefore = "before-reset",
        )
    }

    private suspend fun ordinaryWriterWins(
        restoreValue: BackupSettingsItem,
        write: (Context, SwitchPreferenceCompat, ListPreference, EditTextPreference) -> Unit,
        expectedOrdinary: Any,
    ) {
        val (switch, list, edit) = preferencesFor()
        val scope = CoroutineScope(currentCoroutineContext())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val events = AtomicInteger(0)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                assertTrue(events.compareAndSet(0, 1))
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            assertEquals(2, events.get())
            events.compareAndSet(2, 3)
        }

        val writer = scope.async(Dispatchers.IO) {
            write(context, switch, list, edit)
            assertTrue(events.compareAndSet(1, 2))
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = scope.async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(
                context,
                plan(restoreValue),
            )
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        assertEquals(expectedOrdinary, preferences.all[restoreValue.key])
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertEquals(3, events.get())
        assertEquals(restoreValue.value, preferences.all[restoreValue.key].toString())
    }

    private suspend fun resetWins(
        key: String,
        initial: Any,
        restoreValue: BackupSettingsItem,
        write: (Context, SwitchPreferenceCompat, ListPreference, EditTextPreference) -> Unit,
        expectedBefore: Any,
    ) {
        putValue(key, initial)
        val (switch, list, edit) = preferencesFor()
        val publicationEntered = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val ordinaryEntered = CountDownLatch(1)
        val releaseOrdinary = CountDownLatch(1)
        val first = AtomicBoolean(true)
        lateinit var writer: Deferred<Result<Unit>>
        val writerScope = CoroutineScope(currentCoroutineContext())
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                ordinaryEntered.countDown()
                check(releaseOrdinary.await(20, TimeUnit.SECONDS))
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            publicationEntered.countDown()
            writer = writerScope.async(Dispatchers.IO) {
                writerStarted.countDown()
                runCatching { write(context, switch, list, edit) }
            }
        }

        val reset = writerScope.async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(
                context,
                plan(restoreValue),
            )
        }
        assertTrue(publicationEntered.await(20, TimeUnit.SECONDS))
        assertTrue(writerStarted.await(20, TimeUnit.SECONDS))
        assertTrue(ordinaryEntered.await(20, TimeUnit.SECONDS))
        assertTrue(RestoreGate.isRestoreInProgress(context))
        releaseOrdinary.countDown()
        val writerFailure = writer.await().exceptionOrNull()
        assertTrue(writerFailure is IllegalStateException)
        assertEquals(expectedBefore.toString(), preferences.all[key].toString())
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertEquals(restoreValue.value, preferences.all[key].toString())
    }

    private fun preferencesFor(): Triple<SwitchPreferenceCompat, ListPreference, EditTextPreference> {
        val manager = PreferenceManager(context)
        manager.preferenceDataStore = RestoreAwarePreferenceDataStore(context)
        val screen = manager.createPreferenceScreen(context)
        val switch = SwitchPreferenceCompat(context).apply { key = "use_alarm_for_scheduling" }
        val list = ListPreference(context).apply {
            key = "preferred_download_type"
            entries = arrayOf("Video", "Audio")
            entryValues = arrayOf("video", "audio")
        }
        val edit = EditTextPreference(context).apply { key = "proxy" }
        screen.addPreference(switch)
        screen.addPreference(list)
        screen.addPreference(edit)
        check(manager.setPreferences(screen))
        return Triple(switch, list, edit)
    }

    private fun putValue(key: String, value: Any) {
        val editor = preferences.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            else -> error("unsupported test preference value")
        }
        check(editor.commit())
    }

    private fun plan(setting: BackupSettingsItem): RestorePlan = BackupRestoreParser.fromTyped(
        RestoreAppDataItem(settings = listOf(setting)),
    )

    private fun clearHooks() {
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = null
        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = null
        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = null
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = null
    }
}