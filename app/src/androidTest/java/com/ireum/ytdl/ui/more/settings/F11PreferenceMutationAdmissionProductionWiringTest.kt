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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production-wiring proof for AndroidX Preference final-mutation admission. */
@RunWith(AndroidJUnit4::class)
class F11PreferenceMutationAdmissionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private var originalValues: Map<String, Any?> = emptyMap()
    private var setupReady = false

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        setupReady = false
        clearHooks()
        awaitStartupDefaultsReady()
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        val recoveryOutcome = RestoreTransactionCoordinator.recover(context)
        assertTrue(
                "Preference admission setup requires completed Restore recovery; " +
                "outcome=$recoveryOutcome, spl=${preferences.getInt(STARTUP_DEFAULTS_MARKER, 0)}, " +
                "restoreGate=${restoreGateState()}, active=${activeRestoreDiagnostic()}",
            recoveryOutcome is RestoreOutcome.Completed,
        )
        val restoreActive = runCatching { RestoreGate.isRestoreInProgress(context) }
            .getOrElse { failure ->
                throw AssertionError(
                    "Could not establish RestoreGate state after setup recovery; " +
                        "outcome=$recoveryOutcome, spl=${preferences.getInt(STARTUP_DEFAULTS_MARKER, 0)}, " +
                        "active=${activeRestoreDiagnostic()}",
                    failure,
                )
            }
        assertFalse(
            "Preference admission setup cannot begin while Restore remains active; " +
                "outcome=$recoveryOutcome, spl=${preferences.getInt(STARTUP_DEFAULTS_MARKER, 0)}, " +
                "active=${activeRestoreDiagnostic()}",
            restoreActive,
        )
        originalValues = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        RestoreOperationStore.root(context).deleteRecursively()
        setupReady = true
    }

    @After
    fun tearDown(): Unit = runBlocking {
        clearHooks()
        if (!setupReady) return@runBlocking
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
        setupReady = false
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
        val events = AtomicInteger(0)
        RestoreAwarePreferenceDataStore.mutationForTesting = { changedKey ->
            if (changedKey == restoreValue.key) {
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
        val timeline = Collections.synchronizedList(mutableListOf<String>())
        val writerStarted = AtomicBoolean(false)
        val frameworkBeforeAdmissionEntered = AtomicBoolean(false)
        val frameworkWriteReleased = AtomicBoolean(false)
        val frameworkWriterCompleted = AtomicBoolean(false)
        val frameworkWriterException = AtomicReference<String?>(null)

        fun timelineText(): String = synchronized(timeline) {
            timeline.joinToString(separator = "\n")
        }

        fun recordPhase(phase: String, outcome: String? = null) {
            val entry = buildString {
                append("phase=").append(phase)
                append(" key=").append(key)
                append(" value=").append(preferences.all[key] ?: "<absent>")
                append(" spl=").append(preferences.getInt(STARTUP_DEFAULTS_MARKER, 0))
                append(" restoreGate=").append(restoreGateState())
                append(" writerStarted=").append(writerStarted.get())
                append(" beforeAdmissionEntered=").append(frameworkBeforeAdmissionEntered.get())
                append(" frameworkWriteReleased=").append(frameworkWriteReleased.get())
                append(" frameworkWriterCompleted=").append(frameworkWriterCompleted.get())
                append(" frameworkWriterException=")
                    .append(if (frameworkWriterCompleted.get()) {
                        frameworkWriterException.get() ?: "<none>"
                    } else {
                        "<not-completed>"
                    })
                if (outcome != null) append(" outcome=").append(outcome)
            }
            synchronized(timeline) { timeline.add(entry) }
        }

        fun assertValueAt(phase: String, expected: Any) {
            recordPhase(phase)
            assertEquals(
                "$phase precondition failed; key=$key, current=${preferences.all[key] ?: "<absent>"}, " +
                    "spl=${preferences.getInt(STARTUP_DEFAULTS_MARKER, 0)}, " +
                    "restoreGate=${restoreGateState()}, timeline:\n${timelineText()}",
                expected,
                preferences.all[key],
            )
        }

        putValue(key, initial)
        assertValueAt("A after initial raw commit", initial)

        val (switch, list, edit) = preferencesFor()
        assertValueAt("B after Preference hierarchy attachment", initial)

        val publicationEntered = CountDownLatch(1)
        val writerStartedLatch = CountDownLatch(1)
        val frameworkAttemptEntered = CountDownLatch(1)
        val releaseFrameworkAttempt = CountDownLatch(1)
        val releaseBeforeQuiescence = CountDownLatch(1)
        var writer: Deferred<Result<Unit>>? = null
        var reset: Deferred<RestoreOutcome>? = null
        val writerScope = CoroutineScope(currentCoroutineContext())
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) recordPhase("preference listener observed key mutation")
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)

        RestoreAwarePreferenceDataStore.beforeAdmissionForTesting = { changedKey ->
            if (changedKey == key) {
                frameworkBeforeAdmissionEntered.set(true)
                recordPhase("D framework write reached beforeAdmissionForTesting")
                frameworkAttemptEntered.countDown()
                check(releaseFrameworkAttempt.await(20, TimeUnit.SECONDS)) {
                    "Timed out before framework write release; timeline:\n${timelineText()}"
                }
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            recordPhase("C Restore publication authority acquired")
            writer = writerScope.async(Dispatchers.IO) {
                writerStarted.set(true)
                writerStartedLatch.countDown()
                val result = runCatching { write(context, switch, list, edit) }
                frameworkWriterException.set(result.exceptionOrNull()?.javaClass?.name)
                frameworkWriterCompleted.set(true)
                recordPhase("F framework writer returned or failed")
                result
            }
        }
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            publicationEntered.countDown()
            check(writerStartedLatch.await(20, TimeUnit.SECONDS)) {
                "Timed out waiting for framework writer start; timeline:\n${timelineText()}"
            }
            check(frameworkAttemptEntered.await(20, TimeUnit.SECONDS)) {
                "Timed out waiting for before-admission boundary; timeline:\n${timelineText()}"
            }
            check(releaseBeforeQuiescence.await(20, TimeUnit.SECONDS)) {
                "Timed out waiting for pre-quiescence release; timeline:\n${timelineText()}"
            }
        }

        try {
            val resetJob = writerScope.async(Dispatchers.IO) {
                RestoreTransactionCoordinator.begin(
                    context,
                    plan(restoreValue),
                )
            }
            reset = resetJob
            assertTrue(
                "Timed out waiting for Restore publication; timeline:\n${timelineText()}",
                publicationEntered.await(20, TimeUnit.SECONDS),
            )
            assertTrue(
                "Timed out waiting for framework writer start; timeline:\n${timelineText()}",
                writerStartedLatch.await(20, TimeUnit.SECONDS),
            )
            assertTrue(
                "Timed out waiting for framework before-admission boundary; timeline:\n${timelineText()}",
                frameworkAttemptEntered.await(20, TimeUnit.SECONDS),
            )
            recordPhase("Restore is active before releasing framework write")
            assertTrue(
                "Restore must own the preference graph before writer release; timeline:\n${timelineText()}",
                RestoreGate.isRestoreInProgress(context),
            )
            recordPhase("E immediately before framework write release")
            frameworkWriteReleased.set(true)
            releaseFrameworkAttempt.countDown()

            val writerJob = checkNotNull(writer) {
                "Restore publication callback did not create the framework writer; timeline:\n${timelineText()}"
            }
            val writerFailure = writerJob.await().exceptionOrNull()
            recordPhase("F framework writer result observed")
            assertTrue(
                "Framework write must be rejected with IllegalStateException; " +
                    "failure=${writerFailure?.javaClass?.name}, timeline:\n${timelineText()}",
                writerFailure is IllegalStateException,
            )

            recordPhase("G immediately before pre-quiescence expected-value assertion")
            assertEquals(
                "Pre-quiescence durable value changed; expected initial=$expectedBefore, " +
                    "actual=${preferences.all[key] ?: "<absent>"}, " +
                    "spl=${preferences.getInt(STARTUP_DEFAULTS_MARKER, 0)}, " +
                    "restoreGate=${restoreGateState()}, timeline:\n${timelineText()}",
                expectedBefore.toString(),
                preferences.all[key].toString(),
            )

            releaseBeforeQuiescence.countDown()
            val outcome = resetJob.await()
            recordPhase("H after Restore completion result", outcome.toString())
            assertTrue(
                "Restore did not complete; outcome=$outcome, timeline:\n${timelineText()}",
                outcome is RestoreOutcome.Completed,
            )
            assertEquals(
                "Restore target was not durable after completion; timeline:\n${timelineText()}",
                restoreValue.value,
                preferences.all[key].toString(),
            )
        } finally {
            releaseFrameworkAttempt.countDown()
            releaseBeforeQuiescence.countDown()
            writer?.let { runCatching { it.await() } }
            reset?.let { runCatching { it.await() } }
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
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

    private fun awaitStartupDefaultsReady() {
        if (preferences.getInt(STARTUP_DEFAULTS_MARKER, 0) == STARTUP_DEFAULTS_COMPLETE) return

        val markerChanged = CountDownLatch(1)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPreferences, key ->
            if (key == STARTUP_DEFAULTS_MARKER &&
                changedPreferences.getInt(STARTUP_DEFAULTS_MARKER, 0) == STARTUP_DEFAULTS_COMPLETE
            ) {
                markerChanged.countDown()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            if (preferences.getInt(STARTUP_DEFAULTS_MARKER, 0) != STARTUP_DEFAULTS_COMPLETE) {
                check(markerChanged.await(20, TimeUnit.SECONDS)) {
                    "Timed out waiting for production startup defaults; " +
                        "spl=${preferences.getInt(STARTUP_DEFAULTS_MARKER, 0)}, " +
                        "restoreGate=${restoreGateState()}"
                }
            }
            check(preferences.getInt(STARTUP_DEFAULTS_MARKER, 0) == STARTUP_DEFAULTS_COMPLETE) {
                "Production startup-default marker changed unexpectedly; " +
                    "spl=${preferences.getInt(STARTUP_DEFAULTS_MARKER, 0)}, " +
                    "restoreGate=${restoreGateState()}"
            }
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun restoreGateState(): String = runCatching {
        RestoreGate.isRestoreInProgress(context).toString()
    }.getOrElse { "unavailable:${it.javaClass.name}:${it.message}" }

    private fun activeRestoreDiagnostic(): String = runCatching {
        RestoreOperationStore.load(context)?.let { record ->
            "${record.journal.operationId}:${record.journal.phase}:lastError=${record.journal.lastError}"
        } ?: "<none>"
    }.getOrElse { "unavailable:${it.javaClass.name}:${it.message}" }

    private fun plan(setting: BackupSettingsItem): RestorePlan = BackupRestoreParser.fromTyped(
        RestoreAppDataItem(settings = listOf(setting)),
    )

    private fun clearHooks() {
        RestoreAwarePreferenceDataStore.beforeAdmissionForTesting = null
        RestoreAwarePreferenceDataStore.mutationForTesting = null
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = null
        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = null
        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = null
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = null
    }

    private companion object {
        const val STARTUP_DEFAULTS_MARKER = "spl"
        const val STARTUP_DEFAULTS_COMPLETE = 1
    }
}
