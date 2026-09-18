package com.ireum.ytdl.work

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import com.google.gson.Gson
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.ui.more.settings.CleanupSchedulePreferenceController
import com.ireum.ytdl.ui.more.settings.DownloadSettingsFragment
import com.ireum.ytdl.ui.more.settings.FolderSettingsFragment
import com.ireum.ytdl.ui.more.settings.SettingsActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.storage.DownloadCacheOwnership
import androidx.navigation.fragment.NavHostFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Calendar
import java.util.UUID
import java.io.File

/**
 * Drives the real coordinator and CleanUpLeftoverDownloads WorkManager path.
 * Cleanup itself is replaced only at the narrow side-effect boundary so the
 * schedule/generation/retry decisions remain production code.
 */
@RunWith(AndroidJUnit4::class)
class CleanupScheduleCoordinatorProductionWiringTest {
    private companion object {
        // WorkManager clamps retry backoff to this minimum on Android.
        const val WORK_MANAGER_MIN_BACKOFF_MILLIS = 10_000L
        const val WORK_MANAGER_QUERY_TIMEOUT_MILLIS = 1_000L
        const val WORK_POLL_INTERVAL_MILLIS = 50L
        const val WORKER_WAIT_TIMEOUT_MILLIS = 90_000L
        const val SETTINGS_UI_READY_TIMEOUT_MILLIS = 20_000L
    }

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var legacyPreferences: android.content.SharedPreferences
    private lateinit var database: DBManager
    private val createdDownloadIds = Collections.synchronizedList(mutableListOf<Long>())
    private val controlledOperations = Collections.synchronizedList(mutableListOf<ControlledOperation>())

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        database = DBManager.getInstance(context)
        preferences = CleanupScheduleCoordinator.criticalPreferencesForTesting(context)
        legacyPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        disableCleanupAuthorityBeforeWorkDrain()
        DownloadCacheOwnership.clearRootBindingsForTesting(context)
        clearSchedulePreferences()
    }

    @After
    fun tearDown() = runBlocking {
        disableCleanupAuthorityBeforeWorkDrain()
        DownloadCacheOwnership.clearRootBindingsForTesting(context)
        if (createdDownloadIds.isNotEmpty()) {
            DownloadRepository(database).deleteAllWithIDs(createdDownloadIds.toList())
            createdDownloadIds.clear()
        }
        clearSchedulePreferences()
        controlledOperations.clear()
    }

    @Test
    fun cadenceChangesAndRepeatedReconciliationLeaveOneStableLogicalRequest() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        awaitUnfinishedCount(1)
        assertTrue(
            unfinishedCurrentWork().single().tags.contains(
                cadenceTag(CleanupSchedulePolicy.DAILY),
            )
        )

        CleanupScheduleCoordinator.reconcile(context)
        assertEquals(1, unfinishedCurrentWork().size)

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY)
        awaitUnfinishedCount(1)
        assertTrue(
            unfinishedCurrentWork().single().tags.contains(
                cadenceTag(CleanupSchedulePolicy.WEEKLY),
            )
        )

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.MONTHLY)
        CleanupScheduleCoordinator.reconcile(context)
        awaitUnfinishedCount(1)
        val finalWork = unfinishedCurrentWork()
        assertEquals(1, finalWork.size)
        assertTrue(finalWork.single().tags.contains(cadenceTag(CleanupSchedulePolicy.MONTHLY)))
    }

    @Test
    fun startupReconciliationRepairsMissingChainWithoutDuplicatingIt() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        assertEquals(1, unfinishedCurrentWork().size)

        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertTrue(unfinishedCurrentWork().isEmpty())

        CleanupScheduleCoordinator.reconcile(context)
        CleanupScheduleCoordinator.reconcile(context)
        val repaired = unfinishedCurrentWork()
        assertEquals(1, repaired.size)
        assertTrue(repaired.single().tags.contains(cadenceTag(CleanupSchedulePolicy.DAILY)))
    }

    @Test
    fun legacyCriticalStateMigratesWithoutReplacingExactOccurrenceState() = runBlocking {
        val generation = "legacy-generation"
        val anchorDay = 31
        val occurrenceAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)
        assertTrue(
            legacyPreferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .putString("cleanup_leftover_downloads_generation", generation)
                .putInt("cleanup_leftover_downloads_anchor_day", anchorDay)
                .putString("cleanup_leftover_downloads_pending_generation", generation)
                .putString("cleanup_leftover_downloads_pending_cadence", CleanupSchedulePolicy.DAILY)
                .putInt("cleanup_leftover_downloads_pending_anchor_day", anchorDay)
                .putLong("cleanup_leftover_downloads_pending_occurrence_at", occurrenceAt)
                .putString("cleanup_leftover_downloads_pending_effect_phase", "in_progress")
                .putString("cleanup_leftover_downloads_effect_journal", "legacy-exact-journal")
                .commit(),
        )

        CleanupScheduleCoordinator.reconcile(context)

        assertEquals(
            generation,
            preferences.getString("cleanup_leftover_downloads_generation", null),
        )
        assertEquals(
            occurrenceAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )
        assertEquals(
            "legacy-exact-journal",
            preferences.getString("cleanup_leftover_downloads_effect_journal", null),
        )
        assertEquals(1, preferences.getInt("cleanup_leftover_downloads_critical_store_version", -1))
    }

    @Test
    fun failedCriticalStoreMigrationKeepsLegacyStateForRestartRecovery() = runBlocking {
        val generation = "legacy-generation-for-restart"
        assertTrue(
            legacyPreferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .putString("cleanup_leftover_downloads_generation", generation)
                .putInt("cleanup_leftover_downloads_anchor_day", 12)
                .commit(),
        )
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        CleanupScheduleCoordinator.reconcile(context)

        // The failure seam deliberately models Android's commit(false)
        // behavior: the rejected target is visible in this process even
        // though it is not confirmed durable.  Restart must consult the
        // independent durable image rather than treating this value as
        // authority.
        assertEquals(
            1,
            preferences.getInt("cleanup_leftover_downloads_critical_store_version", -1),
        )
        assertEquals(
            generation,
            legacyPreferences.getString("cleanup_leftover_downloads_generation", null),
        )
        assertTrue(
            legacyPreferences.edit()
                .putString("schedule_start", "08:00")
                .commit(),
        )

        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)
        assertNull(preferences.getString("cleanup_leftover_downloads_critical_store_version", null))
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = false
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        CleanupScheduleCoordinator.reconcile(context)

        assertEquals(
            generation,
            preferences.getString("cleanup_leftover_downloads_generation", null),
        )
        assertEquals(1, preferences.getInt("cleanup_leftover_downloads_critical_store_version", -1))
    }

    @Test
    fun failedCriticalMigrationThenMergeRestoreCannotImportCleanupAuthority() = runBlocking {
        val generation = "destination-generation-merge"
        val occurrenceAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)
        val originalPreferences = snapshotPreferences(legacyPreferences)
        try {
            assertTrue(
                legacyPreferences.edit()
                    .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                    .putString("cleanup_leftover_downloads_generation", generation)
                    .putInt("cleanup_leftover_downloads_anchor_day", 17)
                    .putString("cleanup_leftover_downloads_pending_generation", generation)
                    .putString("cleanup_leftover_downloads_pending_cadence", CleanupSchedulePolicy.DAILY)
                    .putInt("cleanup_leftover_downloads_pending_anchor_day", 17)
                    .putLong("cleanup_leftover_downloads_pending_occurrence_at", occurrenceAt)
                    .putString("cleanup_leftover_downloads_pending_effect_phase", "eligible")
                    .putString("cleanup_leftover_downloads_effect_journal", "destination-journal")
                    .commit(),
            )
            CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
            CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
            CleanupScheduleCoordinator.reconcile(context)

            assertEquals(
                1,
                preferences.getInt("cleanup_leftover_downloads_critical_store_version", -1),
            )
            assertTrue(
                SettingsViewModel(context as android.app.Application).restoreData(
                    RestoreAppDataItem(
                        settings = listOf(
                            BackupSettingsItem(
                                "cleanup_leftover_downloads",
                                CleanupSchedulePolicy.WEEKLY,
                                "String",
                            ),
                            BackupSettingsItem(
                                "cleanup_leftover_downloads_generation",
                                "imported-generation",
                                "String",
                            ),
                            BackupSettingsItem("f10_restore_merge", "ok", "String"),
                        ),
                    ),
                    context,
                ),
            )
            assertEquals(generation, legacyPreferences.getString("cleanup_leftover_downloads_generation", null))
            assertEquals(CleanupSchedulePolicy.DAILY, legacyPreferences.getString("cleanup_leftover_downloads", null))
            assertEquals("ok", legacyPreferences.getString("f10_restore_merge", null))

            // The rejected dedicated-store image is process-visible only;
            // restart must restore the last confirmed image before retrying
            // migration from the untouched destination legacy namespace.
            CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)
            CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = false
            CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
            CleanupScheduleCoordinator.reconcile(context)

            assertEquals(generation, preferences.getString("cleanup_leftover_downloads_generation", null))
            assertEquals(
                occurrenceAt,
                preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
            )
            assertEquals("destination-journal", preferences.getString("cleanup_leftover_downloads_effect_journal", null))
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                CleanupScheduleCoordinator.currentCadenceForSettings(context),
            )
        } finally {
            restorePreferences(legacyPreferences, originalPreferences)
        }
    }

    @Test
    fun failedCriticalMigrationThenResetRestoreCannotClearCleanupAuthority() = runBlocking {
        val generation = "destination-generation-reset"
        val occurrenceAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)
        val originalPreferences = snapshotPreferences(legacyPreferences)
        try {
            assertTrue(
                legacyPreferences.edit()
                    .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                    .putString("cleanup_leftover_downloads_generation", generation)
                    .putInt("cleanup_leftover_downloads_anchor_day", 19)
                    .putString("cleanup_leftover_downloads_pending_generation", generation)
                    .putString("cleanup_leftover_downloads_pending_cadence", CleanupSchedulePolicy.DAILY)
                    .putInt("cleanup_leftover_downloads_pending_anchor_day", 19)
                    .putLong("cleanup_leftover_downloads_pending_occurrence_at", occurrenceAt)
                    .putString("cleanup_leftover_downloads_pending_effect_phase", "eligible")
                    .putString("cleanup_leftover_downloads_effect_journal", "destination-reset-journal")
                    .commit(),
            )
            CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
            CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
            CleanupScheduleCoordinator.reconcile(context)

            assertFalse(
                SettingsViewModel(context as android.app.Application).restoreData(
                    RestoreAppDataItem(
                        settings = listOf(
                            BackupSettingsItem(
                                "cleanup_leftover_downloads",
                                CleanupSchedulePolicy.WEEKLY,
                                "String",
                            ),
                            BackupSettingsItem(
                                "cleanup_leftover_downloads_generation",
                                "imported-reset-generation",
                                "String",
                            ),
                            BackupSettingsItem("f10_restore_reset", "must-not-apply", "String"),
                        ),
                    ),
                    context,
                    resetData = true,
                ),
            )
            assertEquals(generation, legacyPreferences.getString("cleanup_leftover_downloads_generation", null))
            assertEquals(CleanupSchedulePolicy.DAILY, legacyPreferences.getString("cleanup_leftover_downloads", null))
            assertFalse(legacyPreferences.contains("f10_restore_reset"))

            CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)
            CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = false
            CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
            CleanupScheduleCoordinator.reconcile(context)

            assertEquals(generation, preferences.getString("cleanup_leftover_downloads_generation", null))
            assertEquals(
                occurrenceAt,
                preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
            )
            assertEquals(
                "destination-reset-journal",
                preferences.getString("cleanup_leftover_downloads_effect_journal", null),
            )
        } finally {
            restorePreferences(legacyPreferences, originalPreferences)
        }
    }

    @Test
    fun startupMissingGenerationCommitFailureDoesNotPublishNewAuthorityOrDebt() = runBlocking {
        seedInitializedScheduleWithMissingGeneration()
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        CleanupScheduleCoordinator.reconcile(context)

        assertEquals(CleanupSchedulePolicy.DAILY, preferences.getString("cleanup_leftover_downloads", null))
        assertNull(preferences.getString("cleanup_leftover_downloads_generation", null))
        assertNull(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
        )
        assertTrue(unfinishedCurrentWork().isEmpty())
    }

    @Test
    fun startupMissingGenerationCommitFailureRetainsCurrentProcessBootstrapOwner() = runBlocking {
        seedInitializedScheduleWithMissingGeneration()
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        CleanupScheduleCoordinator.reconcile(context)

        assertNull(preferences.getString("cleanup_leftover_downloads_generation", null))
        assertNull(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
        )
        assertTrue(unfinishedCurrentWork().isEmpty())

        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                val generation = preferences.getString(
                    "cleanup_leftover_downloads_generation",
                    null,
                )
                generation != null && (
                    preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                        == generation ||
                        preferences.getString("cleanup_leftover_downloads_active_generation", null)
                            == generation
                    )
            }
        )
        assertTrue(awaitUnfinishedCount(1))
    }

    @Test
    fun memoryVisibleBootstrapFailureIsFencedUntilSameProcessRecovery() = runBlocking {
        seedInitializedScheduleWithMissingGeneration()
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        CleanupScheduleCoordinator.reconcile(context)

        // The editor mutation is visible through Android's in-process map,
        // but the coordinator must not treat it as durable authority.
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_generation", null) != null
        )
        assertEquals(
            CleanupSchedulePolicy.DAILY,
            CleanupScheduleCoordinator.currentCadenceForSettings(context),
        )
        assertTrue(unfinishedCurrentWork().isEmpty())

        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                val generation = preferences.getString(
                    "cleanup_leftover_downloads_generation",
                    null,
                )
                generation != null && (
                    preferences.getString("cleanup_leftover_downloads_pending_generation", null) ==
                        generation ||
                        preferences.getString("cleanup_leftover_downloads_active_generation", null) ==
                        generation
                    )
            }
        )
        assertTrue(awaitUnfinishedCount(1))
    }

    @Test
    fun memoryOnlyCriticalMutationRestoresLastDurableStateAtProcessRestart() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val durableGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )

        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        assertFalse(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        assertEquals(
            CleanupSchedulePolicy.WEEKLY,
            preferences.getString("cleanup_leftover_downloads", null),
        )
        assertEquals(
            CleanupSchedulePolicy.DAILY,
            CleanupScheduleCoordinator.currentCadenceForSettings(context),
        )

        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)

        assertEquals(
            CleanupSchedulePolicy.DAILY,
            preferences.getString("cleanup_leftover_downloads", null),
        )
        assertEquals(
            durableGeneration,
            preferences.getString("cleanup_leftover_downloads_generation", null),
        )
    }

    @Test
    fun rejectedCriticalAuthorityCannotBePersistedByUnrelatedDefaultPreferenceWrite() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val durableGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null),
        )

        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        assertFalse(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        assertEquals(
            CleanupSchedulePolicy.WEEKLY,
            preferences.getString("cleanup_leftover_downloads", null),
        )

        // This write targets the legacy/default settings container.  It must
        // not be able to flush rejected cleanup authority because the
        // coordinator's critical namespace is now a separate store.
        assertTrue(
            legacyPreferences.edit()
                .putString("schedule_start", "23:00")
                .commit(),
        )
        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)

        assertEquals(CleanupSchedulePolicy.DAILY, preferences.getString("cleanup_leftover_downloads", null))
        assertEquals(
            durableGeneration,
            preferences.getString("cleanup_leftover_downloads_generation", null),
        )
        assertEquals("23:00", legacyPreferences.getString("schedule_start", null))
    }

    @Test
    fun rejectedAuthorityCannotBeCollateralPersistedByLaterSuccessfulEffectJournalWrite() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val durableGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        val journal = CleanupEffectJournal(
            generation = durableGeneration,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            occurrenceAt = occurrenceAt,
            tempCleanupRequired = false,
        )

        // Model a failed DAILY -> WEEKLY commit that is visible in this
        // process but is not durable across restart.
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        assertFalse(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        assertEquals(CleanupSchedulePolicy.WEEKLY, preferences.getString("cleanup_leftover_downloads", null))
        assertTrue(legacyPreferences.edit().putString("schedule_start", "22:00").commit())

        // A later legitimate DAILY effect-journal write must rebuild the
        // critical namespace from the confirmed DAILY snapshot, not from the
        // contaminated in-process WEEKLY map.
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        assertTrue(CleanupScheduleCoordinator.seedEffectJournalForTesting(context, journal))
        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)

        assertEquals(CleanupSchedulePolicy.DAILY, preferences.getString("cleanup_leftover_downloads", null))
        assertEquals(
            durableGeneration,
            preferences.getString("cleanup_leftover_downloads_generation", null),
        )
        assertEquals(
            occurrenceAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )
    }

    @Test
    fun rejectedEffectJournalCannotBeCollateralPersistedByLaterAuthorityWrite() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val oldGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        val journal = CleanupEffectJournal(
            generation = oldGeneration,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            occurrenceAt = occurrenceAt,
            tempCleanupRequired = false,
        )

        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = { false }
        assertFalse(CleanupScheduleCoordinator.seedEffectJournalForTesting(context, journal))
        assertTrue(preferences.getString("cleanup_leftover_downloads_effect_journal", null) != null)
        assertTrue(legacyPreferences.edit().putString("schedule_end", "06:00").commit())

        // Supersession is a successful critical authority write. It must not
        // carry the rejected in-progress journal into the new generation.
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = null
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)

        assertEquals(CleanupSchedulePolicy.WEEKLY, preferences.getString("cleanup_leftover_downloads", null))
        assertNull(preferences.getString("cleanup_leftover_downloads_effect_journal", null))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_generation", null) != oldGeneration
        )
    }

    @Test
    fun rejectedEffectProgressCannotBeCollateralPersistedByLaterSuccessorWrite() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ ->
            throw IllegalStateException("scheduler unavailable")
        }
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        val completeJournal = CleanupEffectJournal(
            generation = generation,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            occurrenceAt = occurrenceAt,
            cancelledDeletionComplete = true,
            cancelledRefreshComplete = true,
            erroredDeletionComplete = true,
            erroredRefreshComplete = true,
            tempCleanupRequired = false,
        )
        assertTrue(CleanupScheduleCoordinator.seedEffectJournalForTesting(context, completeJournal))

        // The rejected progress write is visible in this process but remains
        // outside the confirmed durable image.
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = { false }
        assertNull(
            CleanupScheduleCoordinator.updateEffectJournal(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                occurrenceAt = occurrenceAt,
            ) { it.copy(cancelledRefreshComplete = false) }
        )
        assertTrue(preferences.getString("cleanup_leftover_downloads_effect_journal", null) != null)
        assertTrue(legacyPreferences.edit().putString("download_archive_path", "/archive").commit())

        // Successor publication is a later critical authority write. It must
        // be based on the confirmed complete journal, not the rejected raw
        // progress mutation.
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = null
        val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = occurrenceAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        CleanupScheduleCoordinator.scheduleSuccessor(
            context = context,
            generation = generation,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            completedOccurrenceAt = occurrenceAt,
        )
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()
        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)

        assertEquals(
            expectedSuccessorAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )
        assertNull(preferences.getString("cleanup_leftover_downloads_effect_journal", null))
    }

    @Test
    fun rejectedSuccessorDebtCannotBeCollateralPersistedByLaterEffectWrite() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        val completeJournal = CleanupEffectJournal(
            generation = generation,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            occurrenceAt = occurrenceAt,
            cancelledDeletionComplete = true,
            cancelledRefreshComplete = true,
            erroredDeletionComplete = true,
            erroredRefreshComplete = true,
            tempCleanupRequired = false,
        )
        assertTrue(CleanupScheduleCoordinator.seedEffectJournalForTesting(context, completeJournal))

        // Persisting the exact successor fails after making the rejected
        // successor visible in this process.
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                completedOccurrenceAt = occurrenceAt,
            )
        )
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()

        assertTrue(legacyPreferences.edit().putString("schedule_start", "21:00").commit())

        // A successful effect transition for the still-confirmed predecessor
        // must not carry that rejected successor into the durable image.
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        assertTrue(CleanupScheduleCoordinator.seedEffectJournalForTesting(context, completeJournal))
        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)

        assertEquals(
            occurrenceAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )
        assertNull(preferences.getString("cleanup_leftover_downloads_active_occurrence_at", null))
        assertTrue(preferences.getString("cleanup_leftover_downloads_effect_journal", null) != null)
    }

    @Test
    fun bootstrapReplayIsFencedByDisableAndSupersession() = runBlocking {
        seedInitializedScheduleWithMissingGeneration()
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        CleanupScheduleCoordinator.reconcile(context)

        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        assertTrue(CleanupScheduleCoordinator.configure(context, null))
        assertEquals("", preferences.getString("cleanup_leftover_downloads", null))
        assertNull(preferences.getString("cleanup_leftover_downloads_pending_generation", null))
        assertNull(preferences.getString("cleanup_leftover_downloads_active_generation", null))

        preferences.edit().clear().commit()
        seedInitializedScheduleWithMissingGeneration()
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        CleanupScheduleCoordinator.reconcile(context)
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        assertEquals(
            CleanupSchedulePolicy.WEEKLY,
            preferences.getString("cleanup_leftover_downloads", null),
        )
        assertEquals(1, unfinishedCurrentWork().size)
        assertTrue(
            unfinishedCurrentWork().single().tags.contains(
                cadenceTag(CleanupSchedulePolicy.WEEKLY),
            )
        )
    }

    @Test
    fun startupMissingGenerationCommitsDebtBeforeEnqueueAttempt() = runBlocking {
        seedInitializedScheduleWithMissingGeneration()
        val operation = ControlledOperation().also { controlledOperations += it }
        var enqueueSawMatchingDebt = false
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { editor ->
            editor.commit()
        }
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, request ->
            val generation = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_GENERATION,
            )
            val cadence = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_CADENCE,
            )
            val anchorDay = request.workSpec.input.getInt(
                CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY,
                -1,
            )
            val occurrenceAt = request.workSpec.input.getLong(
                CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT,
                -1L,
            )
            enqueueSawMatchingDebt = generation == preferences.getString(
                "cleanup_leftover_downloads_pending_generation",
                null,
            ) && cadence == preferences.getString(
                "cleanup_leftover_downloads_pending_cadence",
                null,
            ) && anchorDay == preferences.getInt(
                "cleanup_leftover_downloads_pending_anchor_day",
                -1,
            ) && occurrenceAt == preferences.getLong(
                "cleanup_leftover_downloads_pending_occurrence_at",
                -1L,
            )
            operation
        }

        CleanupScheduleCoordinator.reconcile(context)

        assertTrue(enqueueSawMatchingDebt)
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
    }

    @Test
    fun startupMissingGenerationEnqueueFailureReplaysInProcess() = runBlocking {
        seedInitializedScheduleWithMissingGeneration()
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                throw IllegalStateException("startup enqueue failure")
            }
            workManager.enqueueUniqueWork(name, policy, request)
        }

        CleanupScheduleCoordinator.reconcile(context)

        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertTrue(enqueueCalls.get() >= 2)
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun failedAcceptedOccurrencePromotionRetainsDebtUntilDurablePromotion() = runBlocking {
        val operation = ControlledOperation().also { controlledOperations += it }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            workManager.enqueueUniqueWork(name, policy, request)
            operation
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        operation.succeed()

        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == generation
            }
        )
        assertNull(preferences.getString("cleanup_leftover_downloads_active_generation", null))

        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null &&
                    preferences.getString("cleanup_leftover_downloads_active_generation", null) == generation
            }
        )
    }

    @Test
    fun failedPredecessorClearAndSuccessorWriteAdvanceOnlyExactSuccessor() = runBlocking {
        val operation = ControlledOperation().also { controlledOperations += it }
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            workManager.enqueueUniqueWork(name, policy, request)
            operation
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = predecessorAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis

        val authorityCommitAttempts = AtomicInteger(0)
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = {
            authorityCommitAttempts.incrementAndGet()
            false
        }
        operation.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == generation &&
                    authorityCommitAttempts.get() > 0
            }
        )
        val failedPromotionAttempts = authorityCommitAttempts.get()
        // Stop the promotion-failure replay owner while the test establishes
        // the exact completed predecessor through the coordinator's effect
        // phase writer.  The durable pending tuple remains the recovery
        // carrier; the owner is recreated by the deliberate successor write
        // failure below.
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()
        val effectPhaseCommitAttempts = AtomicInteger(0)
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = { editor ->
            effectPhaseCommitAttempts.incrementAndGet()
            editor.commit()
        }
        val completeJournal = CleanupEffectJournal(
            generation = generation,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            occurrenceAt = predecessorAt,
            cancelledDeletionComplete = true,
            cancelledRefreshComplete = true,
            erroredDeletionComplete = true,
            erroredRefreshComplete = true,
            tempCleanupRequired = false,
        )
        assertTrue(
            CleanupScheduleCoordinator.seedEffectJournalForTesting(
                context = context,
                journal = completeJournal,
                phase = "consumed",
            )
        )
        assertEquals(1, effectPhaseCommitAttempts.get())
        assertEquals(
            "consumed",
            preferences.getString("cleanup_leftover_downloads_pending_effect_phase", null),
        )
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = null
        val successorAttemptBaseline = authorityCommitAttempts.get()
        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                completedOccurrenceAt = predecessorAt,
            )
        )
        assertEquals(
            "the successor publication must reach a new authority commit after predecessor completion",
            successorAttemptBaseline + 1,
            authorityCommitAttempts.get(),
        )
        assertTrue(authorityCommitAttempts.get() > failedPromotionAttempts)
        assertEquals(
            predecessorAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )

        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        CleanupScheduleCoordinator.enqueueOverrideForTesting = null
        // Model the predecessor no longer being discoverable.  The replay
        // owner must advance the exact successor rather than selecting the
        // stale predecessor tuple merely because its clear commit failed.
        val predecessorTag = occurrenceTag(generation, predecessorAt)
        val successorTag = occurrenceTag(generation, expectedSuccessorAt)
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = {
            // Keep real unique-work discovery enabled and hide only the old
            // predecessor.  The successor created by recovery, and any
            // unrelated current work, must remain observable to the
            // coordinator.
            workManager.getWorkInfosForUniqueWork(
                CleanupScheduleCoordinator.WORK_NAME,
            ).get(WORK_MANAGER_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .filterNot { info -> info.tags.contains(predecessorTag) }
        }

        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) == -1L &&
                    preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) == expectedSuccessorAt
            }
        )
        fun isUnfinished(info: WorkInfo): Boolean =
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        val current = awaitWork(timeoutMs = 10_000L) { infos ->
            val unfinishedSuccessors = infos.filter { info ->
                isUnfinished(info) && info.tags.contains(successorTag)
            }
            val unfinishedPredecessors = infos.filter { info ->
                isUnfinished(info) && info.tags.contains(predecessorTag)
            }
            unfinishedSuccessors.size == 1 && unfinishedPredecessors.isEmpty()
        }
        val unfinishedSuccessors = current.filter { info ->
            isUnfinished(info) && info.tags.contains(successorTag)
        }
        assertEquals(1, unfinishedSuccessors.size)
        assertTrue(
            unfinishedSuccessors.single().state == WorkInfo.State.ENQUEUED ||
                unfinishedSuccessors.single().state == WorkInfo.State.RUNNING ||
                unfinishedSuccessors.single().state == WorkInfo.State.BLOCKED,
        )
        assertTrue(
            current.none { info ->
                isUnfinished(info) && info.tags.contains(predecessorTag)
            },
        )
    }

    @Test
    fun terminalWorkerRetainsExactSuccessorAcrossReplayOwnerLoss() = runBlocking {
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(1)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = {
            admissionEntered.countDown()
            check(releaseAdmission.await(10, TimeUnit.SECONDS)) {
                "cleanup admission did not release"
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = predecessorAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_active_generation", null) == generation
            }
        )

        assertTrue(admissionEntered.await(10, TimeUnit.SECONDS))
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        releaseAdmission.countDown()
        val terminal = awaitWork(timeoutMs = 30_000L) { infos ->
            infos.any { info ->
                info.state == WorkInfo.State.SUCCEEDED &&
                    info.tags.contains(occurrenceTag(generation, predecessorAt)) &&
                    info.outputData.getBoolean("cleanup_schedule_handoff_pending", false)
            }
        }
        assertTrue(terminal.any { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(1, cleanupRuns.get())
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()

        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        CleanupScheduleCoordinator.reconcile(context)
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) == expectedSuccessorAt
            }
        )
        val current = unfinishedCurrentWork()
        assertEquals(1, current.size)
        assertTrue(current.single().tags.contains(occurrenceTag(generation, expectedSuccessorAt)))
        assertTrue(current.single().tags.none { it.contains("_$predecessorAt") })
    }

    @Test
    fun successorDiscoveryFailurePersistsExactDebtBeforeQueryAndRecovers() = runBlocking {
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        assertEquals(1, unfinishedCurrentWork().size)
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = predecessorAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        markCurrentEffectConsumed(predecessorAt)
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = {
            error("scheduler discovery unavailable")
        }

        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                completedOccurrenceAt = predecessorAt,
            )
        )
        assertEquals(
            generation,
            preferences.getString("cleanup_leftover_downloads_pending_generation", null),
        )
        assertEquals(
            expectedSuccessorAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )

        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = null
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun successorDebtPersistenceFailureUsesLiveFallbackUntilItCanPersist() = runBlocking {
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        val expectedSuccessorAt = expectedSuccessorOccurrenceAt(
            predecessorOccurrenceAt = predecessorAt,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        )

        fun slotPrefix(slot: String): String = "cleanup_leftover_downloads_$slot"

        fun slotIsOccupied(slot: String): Boolean {
            val prefix = slotPrefix(slot)
            return preferences.getString("${prefix}_generation", null) != null ||
                preferences.getString("${prefix}_cadence", null) != null ||
                preferences.getInt("${prefix}_anchor_day", -1) != -1 ||
                preferences.getLong("${prefix}_occurrence_at", -1L) != -1L
        }

        fun slotMatches(slot: String, occurrenceAt: Long): Boolean {
            val prefix = slotPrefix(slot)
            return preferences.getString("${prefix}_generation", null) == generation &&
                preferences.getString("${prefix}_cadence", null) == CleanupSchedulePolicy.DAILY &&
                preferences.getInt("${prefix}_anchor_day", -1) == anchorDay &&
                preferences.getLong("${prefix}_occurrence_at", -1L) == occurrenceAt
        }

        fun exactD1Slot(requireConsumed: Boolean = false): String? {
            val pendingOccupied = slotIsOccupied("pending")
            val activeOccupied = slotIsOccupied("active")
            if (pendingOccupied && activeOccupied) {
                throw AssertionError(
                    "conflicting pending/active D1 ownership: ${durableSchedulingState()}",
                )
            }
            val slot = when {
                slotMatches("pending", predecessorAt) -> "pending"
                slotMatches("active", predecessorAt) -> "active"
                else -> null
            }
            if (requireConsumed && slot != null) {
                assertEquals(
                    "consumed",
                    preferences.getString("${slotPrefix(slot)}_effect_phase", null),
                )
            }
            return slot
        }

        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                exactD1Slot() != null
            },
        )
        val d1SlotBeforeFailure = exactD1Slot() ?: error(
            "missing exact D1 carrier before successor persistence failure: " +
                durableSchedulingState(),
        )
        val predecessorTag = occurrenceTag(generation, predecessorAt)
        val predecessorWork = awaitWork(timeoutMs = 5_000L) { infos ->
            infos.count { info ->
                (info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED) &&
                    info.tags.contains(predecessorTag)
            } == 1
        }.first { info ->
            (info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED) &&
                info.tags.contains(predecessorTag)
        }
        val d1PrefixBeforeFailure = slotPrefix(d1SlotBeforeFailure)
        assertEquals(
            generation,
            preferences.getString("${d1PrefixBeforeFailure}_generation", null),
        )
        assertEquals(
            predecessorAt,
            preferences.getLong("${d1PrefixBeforeFailure}_occurrence_at", -1L),
        )
        assertTrue(
            preferences.edit()
                .putString("${d1PrefixBeforeFailure}_effect_phase", "consumed")
                .commit(),
        )
        assertEquals(
            "consumed",
            preferences.getString("${d1PrefixBeforeFailure}_effect_phase", null),
        )
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                completedOccurrenceAt = predecessorAt,
            )
        )
        assertFalse(slotMatches("pending", expectedSuccessorAt))
        assertFalse(slotMatches("active", expectedSuccessorAt))
        val d1SlotAfterFailure = exactD1Slot(requireConsumed = true) ?: error(
            "exact D1 responsibility disappeared after failed D2 persistence: " +
                durableSchedulingState(),
        )
        assertEquals(
            generation,
            preferences.getString("${slotPrefix(d1SlotAfterFailure)}_generation", null),
        )
        assertEquals(
            CleanupSchedulePolicy.DAILY,
            preferences.getString("${slotPrefix(d1SlotAfterFailure)}_cadence", null),
        )
        assertEquals(
            anchorDay,
            preferences.getInt("${slotPrefix(d1SlotAfterFailure)}_anchor_day", -1),
        )
        assertEquals(
            predecessorAt,
            preferences.getLong("${slotPrefix(d1SlotAfterFailure)}_occurrence_at", -1L),
        )

        workManager.cancelWorkById(predecessorWork.id).result.get(20, TimeUnit.SECONDS)
        val predecessorTerminal = awaitWorkById(predecessorWork.id) { info ->
            info.state == WorkInfo.State.CANCELLED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.SUCCEEDED
        }
        assertEquals(WorkInfo.State.CANCELLED, predecessorTerminal.state)
        awaitWork(timeoutMs = 10_000L) { infos ->
            infos.none { info ->
                (info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED) &&
                info.tags.contains(predecessorTag)
            }
        }
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        val successorWork = awaitExactSuccessor(
            generation = generation,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            predecessorOccurrenceAt = predecessorAt,
            timeoutMs = 10_000L,
        )
        assertTrue(successorWork.tags.contains(occurrenceTag(generation, expectedSuccessorAt)))
        assertFalse(successorWork.tags.contains(predecessorTag))

        val finalPendingD2 = slotMatches("pending", expectedSuccessorAt)
        val finalActiveD2 = slotMatches("active", expectedSuccessorAt)
        assertTrue(finalPendingD2.xor(finalActiveD2))
        assertFalse(slotMatches("pending", predecessorAt))
        assertFalse(slotMatches("active", predecessorAt))
        val liveCurrent = queryUniqueWorkInfos().filter { info ->
            (info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED) &&
                info.tags.contains(generationTag(generation)) &&
                info.tags.contains(cadenceTag(CleanupSchedulePolicy.DAILY))
        }
        assertEquals(1, liveCurrent.size)
        assertTrue(liveCurrent.single().tags.contains(occurrenceTag(generation, expectedSuccessorAt)))
        assertTrue(liveCurrent.none { info -> info.tags.contains(predecessorTag) })
    }

    @Test
    fun startupRecoveryDebtFailureRetainsCurrentProcessOwnership() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertTrue(awaitPreference(timeoutMs = 2_000L) { unfinishedCurrentWork().isEmpty() })
        preferences.edit()
            .remove("cleanup_leftover_downloads_pending_generation")
            .remove("cleanup_leftover_downloads_pending_cadence")
            .remove("cleanup_leftover_downloads_pending_anchor_day")
            .remove("cleanup_leftover_downloads_pending_occurrence_at")
            .remove("cleanup_leftover_downloads_pending_effect_phase")
            .remove("cleanup_leftover_downloads_active_generation")
            .remove("cleanup_leftover_downloads_active_cadence")
            .remove("cleanup_leftover_downloads_active_anchor_day")
            .remove("cleanup_leftover_downloads_active_occurrence_at")
            .remove("cleanup_leftover_downloads_active_effect_phase")
            .commit()

        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        CleanupScheduleCoordinator.reconcile(context)

        assertNull(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
        )
        assertTrue(unfinishedCurrentWork().isEmpty())

        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            enqueueCalls.incrementAndGet()
            workManager.enqueueUniqueWork(name, policy, request)
        }
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                enqueueCalls.get() >= 1 && unfinishedCurrentWork().size == 1
            }
        )
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun restartReconstructsReplayOwnerForUnmatchedSuccessorDebt() = runBlocking {
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val predecessor = unfinishedCurrentWork().single()
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        val successorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = predecessorAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        preferences.edit()
            .remove("cleanup_leftover_downloads_active_generation")
            .remove("cleanup_leftover_downloads_active_cadence")
            .remove("cleanup_leftover_downloads_active_anchor_day")
            .remove("cleanup_leftover_downloads_active_occurrence_at")
            .putString("cleanup_leftover_downloads_pending_generation", generation)
            .putString("cleanup_leftover_downloads_pending_cadence", CleanupSchedulePolicy.DAILY)
            .putInt("cleanup_leftover_downloads_pending_anchor_day", anchorDay)
            .putLong("cleanup_leftover_downloads_pending_occurrence_at", successorAt)
            .commit()
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()

        CleanupScheduleCoordinator.reconcile(context)
        assertEquals(
            successorAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )
        workManager.cancelWorkById(predecessor.id).result.get(20, TimeUnit.SECONDS)

        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        val repaired = unfinishedCurrentWork()
        assertEquals(1, repaired.size)
        assertTrue(repaired.single().tags.contains(occurrenceTag(generation, successorAt)))
    }

    @Test
    fun schedulerDiscoveryFailureDoesNotCancelOrReplacePossibleLiveWork() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val before = unfinishedCurrentWork().single()
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = TimeUnit.MINUTES.toMillis(1)
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            enqueueCalls.incrementAndGet()
            workManager.enqueueUniqueWork(name, policy, request)
        }
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = {
            error("scheduler discovery unavailable")
        }

        CleanupScheduleCoordinator.reconcile(context)

        val after = unfinishedCurrentWork()
        assertEquals(listOf(before.id), after.map { it.id })
        assertEquals(0, enqueueCalls.get())
    }

    @Test
    fun disableCancelsScheduleAndFencesStaleSuccessor() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )

        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = "stale-generation",
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = 1,
            )
        )
        CleanupScheduleCoordinator.configure(context, null)
        assertTrue(awaitUnfinishedCount(0))
        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = 1,
            )
        )
        assertTrue(unfinishedCurrentWork().isEmpty())
    }

    @Test
    fun disableRetiresPendingDebtBeforeLateEnqueueCompletion() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val second = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(1)
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ ->
            if (enqueueCalls.getAndIncrement() == 0) first else second
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )

        assertTrue(CleanupScheduleCoordinator.configure(context, null))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
        )
        assertEquals(1, enqueueCalls.get())

        // A late completion from the superseded operation cannot recreate
        // work or clear/mutate any newer authority.
        first.succeed()
        assertTrue(awaitUnfinishedCount(0))
        assertTrue(unfinishedCurrentWork().isEmpty())
        assertEquals(1, enqueueCalls.get())

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(CleanupScheduleCoordinator.configure(context, null))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
        )
        second.fail(IllegalStateException("late superseded failure"))
        assertTrue(awaitUnfinishedCount(0))
        assertTrue(unfinishedCurrentWork().isEmpty())
        assertEquals(2, enqueueCalls.get())
    }

    @Test
    fun supersededReplayCannotRecreateOldCadenceWork() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                first
            } else {
                workManager.enqueueUniqueWork(name, policy, request)
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val firstGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        val secondGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        assertTrue(firstGeneration != secondGeneration)

        // Complete the superseded operation after the new generation is
        // authoritative. Only the weekly replay may establish work.
        first.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        val current = unfinishedCurrentWork()
        assertEquals(1, current.size)
        assertTrue(current.single().tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY)))
        assertTrue(current.single().tags.contains(generationTag(secondGeneration)))
        assertTrue(current.single().tags.none { it.contains(firstGeneration) })
    }

    @Test
    fun successfulRunAppendsExactlyOneCalendarSuccessor() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val successor = awaitWork(timeoutMs = 30_000L) { infos ->
            cleanupRuns.get() >= 1 && infos.any { info ->
                info.tags.contains(generationTag(generation)) &&
                    info.tags.any { tag -> tag.startsWith("${CleanupScheduleCoordinator.TAG}_occurrence_") } &&
                    info.state == WorkInfo.State.ENQUEUED
            }
        }

        assertTrue(successor.any { it.tags.contains(cadenceTag(CleanupSchedulePolicy.DAILY)) })
        assertEquals(1, unfinishedCurrentWork().size)
        assertTrue(cleanupRuns.get() >= 1)
    }

    @Test
    fun consumedOccurrenceDoesNotRepeatWhenSuccessorPublicationIsUnavailable() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(1)
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)

        // Keep D1 durable but prevent its successor debt from being
        // published. The first worker still has to durably consume its
        // destructive effect before it returns.
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        val first = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val firstResult = awaitWorkById(first.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, firstResult.state)
        assertTrue(firstResult.outputData.getBoolean("cleanup_schedule_handoff_pending", false))
        assertEquals(1, cleanupRuns.get())
        assertEquals(
            "consumed",
            preferences.getString("cleanup_leftover_downloads_pending_effect_phase", null)
                ?: preferences.getString("cleanup_leftover_downloads_active_effect_phase", null),
        )

        // Re-enter the exact same WorkManager input after the effect has
        // completed but before D2 has been durably published. The real
        // worker must hand off/recover without running cleanup again.
        val second = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val secondResult = awaitWorkById(second.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, secondResult.state)
        assertTrue(secondResult.outputData.getBoolean("cleanup_schedule_handoff_pending", false))
        assertEquals(1, cleanupRuns.get())
    }

    @Test
    fun staleConsumedOccurrenceCannotRepeatAfterExactSuccessorPublication() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)

        val first = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val firstResult = awaitWorkById(first.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, firstResult.state)
        assertEquals(1, cleanupRuns.get())
        val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = occurrenceAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) ==
                    expectedSuccessorAt ||
                    preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) ==
                    expectedSuccessorAt
            }
        )

        // D1 is no longer the durable occurrence owner. A stale re-entry
        // with the same generation/cadence must be rejected by exact
        // occurrence ownership, even though D2 shares the generation.
        val second = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val secondResult = awaitWorkById(second.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, secondResult.state)
        assertTrue(secondResult.outputData.getBoolean("cleanup_schedule_stale", false))
        assertEquals(1, cleanupRuns.get())
    }

    @Test
    fun restartWithInProgressEffectJournalResumesBodyAndRecoversExactSuccessor() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)

        // Model process death after exact D1 admission but before its first
        // subeffect. The journal, rather than a coarse phase alone, is the
        // restart-reconstructible responsibility carrier.
        assertTrue(
            CleanupScheduleCoordinator.seedEffectJournalForTesting(
                context = context,
                journal = CleanupEffectJournal(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = occurrenceAt,
                    tempCleanupRequired = false,
                ),
            )
        )
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()

        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertEquals(1, cleanupRuns.get())

        val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = occurrenceAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) ==
                    expectedSuccessorAt ||
                    preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) ==
                    expectedSuccessorAt
            }
        )
    }

    @Test
    fun bareInProgressPhaseWithoutJournalFailsClosedAndDoesNotPublishSuccessor() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        val phaseEditor = preferences.edit().remove("cleanup_leftover_downloads_effect_journal")
        if (preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) == occurrenceAt) {
            phaseEditor.putString("cleanup_leftover_downloads_pending_effect_phase", "in_progress")
        }
        if (preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) == occurrenceAt) {
            phaseEditor.putString("cleanup_leftover_downloads_active_effect_phase", "in_progress")
        }
        assertTrue(phaseEditor.commit())
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()

        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.FAILED, terminal.state)
        assertEquals(0, cleanupRuns.get())
        assertTrue(
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) ==
                occurrenceAt ||
                preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) ==
                    occurrenceAt
        )
        assertTrue(
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) !=
                CleanupSchedulePolicy.nextOccurrence(
                    now = Calendar.getInstance().apply { timeInMillis = occurrenceAt },
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                ).timeInMillis
        )
    }

    @Test
    fun roomDeletionFailureResumesFrozenTargetsWithoutWideningToNewlyCancelledRow() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val firstId = database.downloadDao.insert(cleanupDownload("first"))
        createdDownloadIds += firstId
        val failureOnce = AtomicBoolean(true)
        DownloadRepository.cleanupAfterRoomDeletionForTesting = {
            if (failureOnce.compareAndSet(true, false)) {
                IllegalStateException("simulated post-Room cleanup failure")
            } else {
                null
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)

        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        assertTrue(awaitDownload(timeoutMs = 10_000L) {
            database.downloadDao.getNullableDownloadById(firstId) == null
        })

        val secondId = database.downloadDao.insert(cleanupDownload("second"))
        createdDownloadIds += secondId
        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(secondId)?.status,
        )
    }

    @Test
    fun roomCommitBeforeCacheFailureRetainsExactSuffixAfterRowDisappears() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val firstId = database.downloadDao.insert(cleanupDownload("cache-room-commit"))
        createdDownloadIds += firstId
        val first = requireNotNull(database.downloadDao.getNullableDownloadById(firstId))
        val cacheRoot = File(FileUtil.getCachePath(context))
        DownloadCacheOwnership.ensureMarker(cacheRoot, first)

        val roomFailureOnce = AtomicBoolean(true)
        DownloadRepository.cleanupAfterRoomDeletionForTesting = {
            if (roomFailureOnce.compareAndSet(true, false)) {
                IllegalStateException("simulated process death after Room commit")
            } else {
                null
            }
        }
        val cacheCalls = Collections.synchronizedList(mutableListOf<Long>())
        DownloadRepository.exactCacheDeletionForTesting = { target ->
            cacheCalls += target.id
            DownloadCacheOwnership.deleteIfOwnedOrAlreadyAbsent(cacheRoot, target)
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)

        assertTrue(awaitDownload(timeoutMs = 10_000L) {
            database.downloadDao.getNullableDownloadById(firstId) == null
        })
        // This row becomes eligible after D1's Room suffix committed. It is
        // not part of D1's frozen cache responsibility.
        val secondId = database.downloadDao.insert(cleanupDownload("cache-room-new"))
        createdDownloadIds += secondId
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()

        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertTrue(cacheCalls.contains(firstId))
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(secondId)?.status,
        )
    }

    @Test
    fun failedExactCacheDeletionDoesNotAdvanceJournalOrWidenTargets() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val firstId = database.downloadDao.insert(cleanupDownload("cache-false"))
        createdDownloadIds += firstId
        val first = requireNotNull(database.downloadDao.getNullableDownloadById(firstId))
        val cacheRoot = File(FileUtil.getCachePath(context))
        DownloadCacheOwnership.ensureMarker(cacheRoot, first)

        val cacheCalls = Collections.synchronizedList(mutableListOf<Long>())
        val failOnce = AtomicBoolean(true)
        DownloadRepository.exactCacheDeletionForTesting = { target ->
            cacheCalls += target.id
            if (failOnce.compareAndSet(true, false)) {
                false
            } else {
                DownloadCacheOwnership.deleteIfOwnedOrAlreadyAbsent(cacheRoot, target)
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        assertTrue(awaitDownload(timeoutMs = 10_000L) {
            database.downloadDao.getNullableDownloadById(firstId) == null
        })
        val secondId = database.downloadDao.insert(cleanupDownload("cache-false-new"))
        createdDownloadIds += secondId

        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertTrue(cacheCalls.count { it == firstId } >= 2)
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(secondId)?.status,
        )
    }

    @Test
    fun realCacheHelperPartialFailureRetainsExactCarrierAcrossRecovery() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val id = database.downloadDao.insert(cleanupDownload("cache-real-helper"))
        createdDownloadIds += id
        val target = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        val cacheRoot = File(FileUtil.getCachePath(context))
        val directory = File(cacheRoot, id.toString()).apply { mkdirs() }
        DownloadCacheOwnership.ensureMarker(cacheRoot, target)
        val first = directory.resolve("first.bin").apply { writeText("first") }
        val second = directory.resolve("second.bin").apply { writeText("second") }
        assertTrue(
            DownloadCacheOwnership.recordArtifacts(
                cacheRoot,
                target,
                listOf(first.absolutePath, second.absolutePath),
            )
        )

        val failSecondOnce = AtomicBoolean(true)
        DownloadCacheOwnership.fileDeletionForTesting = { file ->
            if (file.name == second.name && failSecondOnce.compareAndSet(true, false)) {
                // Return immediately so the worker releases ownershipLock
                // before the test observes the retry boundary and performs
                // the independent cache-root transition.
                false
            } else {
                file.delete()
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null),
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)

        assertTrue(awaitDownload(timeoutMs = 10_000L) {
            database.downloadDao.getNullableDownloadById(id) == null
        })
        val retrying = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount >= 1
        }
        assertTrue(retrying.runAttemptCount >= 1)
        assertFalse(first.exists())
        assertTrue(second.isFile)
        assertTrue(DownloadCacheOwnership.markerFile(cacheRoot, id).isFile)
        assertTrue(DownloadCacheOwnership.artifactManifestFile(cacheRoot, id).isFile)

        // Drop process-local replay state after the first attempt has entered
        // retry. Durable journal and exact filesystem carrier must be
        // sufficient for the retry that follows.
        CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)
        DownloadCacheOwnership.fileDeletionForTesting = null

        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertFalse(second.exists())
        assertFalse(DownloadCacheOwnership.markerFile(cacheRoot, id).exists())
        assertFalse(DownloadCacheOwnership.artifactManifestFile(cacheRoot, id).exists())
        val successor = awaitExactSuccessor(
            generation = generation,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
            predecessorOccurrenceAt = occurrenceAt,
        )
        assertEquals(WorkInfo.State.ENQUEUED, successor.state)
        val unfinished = unfinishedCurrentWork()
        assertEquals(
            "expected one logical successor: ${describeWorkInfos(unfinished)}",
            1,
            unfinished.size,
        )
        assertEquals(successor.id, unfinished.single().id)
    }

    @Test
    fun cleanupJournalReusesFrozenRootAfterCachePathRebindAndRestart() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val id = database.downloadDao.insert(cleanupDownload("cache-root-rebind"))
        createdDownloadIds += id
        val target = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        val rootOne = context.cacheDir.resolve("cleanup-root-one-${UUID.randomUUID()}").apply { mkdirs() }
        val rootTwo = context.cacheDir.resolve("cleanup-root-two-${UUID.randomUUID()}").apply { mkdirs() }
        val hadCachePath = legacyPreferences.contains("cache_path")
        val previousCachePath = legacyPreferences.getString("cache_path", null)
        val failSecondOnce = AtomicBoolean(true)
        try {
            assertTrue(legacyPreferences.edit().putString("cache_path", rootOne.absolutePath).commit())
            val directory = File(rootOne, id.toString()).apply { mkdirs() }
            DownloadCacheOwnership.prepareAttempt(rootOne, target, context)
            val first = directory.resolve("first.bin").apply { writeText("first") }
            val second = directory.resolve("second.bin").apply { writeText("second") }
            assertTrue(
                DownloadCacheOwnership.recordArtifacts(
                    rootOne,
                    target,
                    listOf(first.absolutePath, second.absolutePath),
                )
            )
            DownloadCacheOwnership.fileDeletionForTesting = { file ->
                if (file.name == second.name && failSecondOnce.compareAndSet(true, false)) {
                    // Return while the ownership lock is still held.  The
                    // test observes the resulting WorkManager retry before
                    // taking the same lock for the root transition.
                    false
                } else {
                    file.delete()
                }
            }

            assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
            val generation = requireNotNull(
                preferences.getString("cleanup_leftover_downloads_generation", null),
            )
            val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
            val occurrenceAt = currentScheduledOccurrenceAt()
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)

            assertTrue(awaitDownload(timeoutMs = 10_000L) {
                database.downloadDao.getNullableDownloadById(id) == null
            })
            val retrying = awaitWorkById(request.id) { info ->
                info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount >= 1
            }
            assertTrue(retrying.runAttemptCount >= 1)
            assertFalse(first.exists())
            assertTrue(second.exists())
            assertTrue(DownloadCacheOwnership.markerFile(rootOne, id).isFile)
            assertTrue(DownloadCacheOwnership.artifactManifestFile(rootOne, id).isFile)

            // Model the real settings transition: valid old-root carriers are
            // registered before the mutable cache_path preference changes.
            assertTrue(
                DownloadCacheOwnership.captureOwnedRootsForPathTransition(
                    context = context,
                    cacheRoot = rootOne,
                )
            )
            assertTrue(legacyPreferences.edit().putString("cache_path", rootTwo.absolutePath).commit())
            val rootTwoSentinel = rootTwo.resolve("new-temp.bin").apply { writeText("new-root") }
            CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)
            DownloadCacheOwnership.resetRootBindingProcessStateForTesting()
            DownloadCacheOwnership.fileDeletionForTesting = null

            val terminal = awaitWorkById(request.id) { info ->
                info.state == WorkInfo.State.SUCCEEDED ||
                    info.state == WorkInfo.State.FAILED ||
                    info.state == WorkInfo.State.CANCELLED
            }
            assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
            assertFalse(second.exists())
            assertFalse(DownloadCacheOwnership.markerFile(rootOne, id).exists())
            assertFalse(DownloadCacheOwnership.artifactManifestFile(rootOne, id).exists())
            assertTrue(rootTwoSentinel.isFile)
            assertFalse(File(rootTwo, id.toString()).exists())
            val successor = awaitExactSuccessor(
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                predecessorOccurrenceAt = occurrenceAt,
            )
            assertEquals(WorkInfo.State.ENQUEUED, successor.state)
            assertTrue(
                "expected successor debt to match WorkManager successor: ${durableSchedulingState()}",
                hasDurableScheduledOccurrence(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = expectedSuccessorOccurrenceAt(
                        predecessorOccurrenceAt = occurrenceAt,
                        cadence = CleanupSchedulePolicy.DAILY,
                        monthlyAnchorDay = anchorDay,
                    ),
                ),
            )
        } finally {
            DownloadCacheOwnership.fileDeletionForTesting = null
            if (hadCachePath) {
                legacyPreferences.edit().putString("cache_path", previousCachePath).commit()
            } else {
                legacyPreferences.edit().remove("cache_path").commit()
            }
            rootOne.deleteRecursively()
            rootTwo.deleteRecursively()
        }
    }

    @Test
    fun preJournalCacheRootDiscoveryUsesRegisteredOldRootNotCurrentPreference() = runBlocking {
        val id = database.downloadDao.insert(cleanupDownload("cache-root-prejournal"))
        createdDownloadIds += id
        val target = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        val rootOne = context.cacheDir.resolve("cleanup-prejournal-one-${UUID.randomUUID()}").apply { mkdirs() }
        val rootTwo = context.cacheDir.resolve("cleanup-prejournal-two-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // Model a carrier created before root binding was introduced;
            // the real settings-transition capture below must be what makes
            // this old root discoverable before cache_path changes.
            DownloadCacheOwnership.prepareAttempt(rootOne, target)
            val directory = File(rootOne, id.toString()).apply { mkdirs() }
            val owned = directory.resolve("owned.bin").apply { writeText("owned") }
            assertTrue(DownloadCacheOwnership.recordArtifacts(rootOne, target, listOf(owned.absolutePath)))
            assertTrue(
                DownloadCacheOwnership.captureOwnedRootsForPathTransition(
                    context = context,
                    cacheRoot = rootOne,
                )
            )
            assertTrue(legacyPreferences.edit().putString("cache_path", rootTwo.absolutePath).commit())

            val bindings = DownloadRepository(database).exactCacheCleanupBindings(listOf(target))
            assertEquals(1, bindings.size)
            assertEquals(rootOne.canonicalFile.absolutePath, bindings.single().rootPath)
            assertTrue(owned.isFile)
            assertFalse(File(rootTwo, id.toString()).exists())
        } finally {
            legacyPreferences.edit().remove("cache_path").commit()
            rootOne.deleteRecursively()
            rootTwo.deleteRecursively()
        }
    }

    @Test
    fun folderSettingsResetCapturesPreBindingRootBeforeDefaultingCachePath() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val id = database.downloadDao.insert(cleanupDownload("cache-root-reset"))
        createdDownloadIds += id
        val target = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        val rootOne = context.cacheDir.resolve("cleanup-reset-one-${UUID.randomUUID()}").apply { mkdirs() }
        val expectedDefaultRoot = (context.getExternalFilesDir(null) ?: context.cacheDir)
            .resolve("downloads")
            .canonicalFile
        val hadCachePath = legacyPreferences.contains("cache_path")
        val previousCachePath = legacyPreferences.getString("cache_path", null)
        try {
            assertTrue(legacyPreferences.edit().putString("cache_path", rootOne.absolutePath).commit())
            // This is an existing carrier from before durable root binding.
            DownloadCacheOwnership.prepareAttempt(rootOne, target)
            val directory = File(rootOne, id.toString()).apply { mkdirs() }
            val owned = directory.resolve("owned.bin").apply { writeText("owned") }
            assertTrue(DownloadCacheOwnership.recordArtifacts(rootOne, target, listOf(owned.absolutePath)))

            val scenario = ActivityScenario.launch(SettingsActivity::class.java)
            try {
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.frame_layout) as NavHostFragment
                    navHost.navController.navigate(R.id.folderSettingsFragment)
                    navHost.childFragmentManager.executePendingTransactions()
                }
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.frame_layout) as NavHostFragment
                    val fragment = navHost.childFragmentManager.primaryNavigationFragment
                        as FolderSettingsFragment
                    fragment.findPreference<androidx.preference.Preference>("reset_preferences")
                        ?.performClick()
                }
                awaitSettingsUiReady(
                    scenario,
                    destinationId = R.id.folderSettingsFragment,
                    dialogTextResId = R.string.continue_anyway,
                )
                onView(withText(R.string.continue_anyway)).perform(click())

                assertTrue(
                    awaitPreference(timeoutMs = 10_000L) {
                        val stored = legacyPreferences.getString("cache_path", null).orEmpty()
                        stored.isNotBlank() && runCatching {
                            File(stored).canonicalFile == expectedDefaultRoot
                        }.getOrDefault(false)
                    }
                )
                DownloadCacheOwnership.resetRootBindingProcessStateForTesting()

                val bindings = DownloadRepository(database).exactCacheCleanupBindings(listOf(target))
                assertEquals(1, bindings.size)
                assertEquals(rootOne.canonicalFile.absolutePath, bindings.single().rootPath)
                assertTrue(owned.isFile)
                assertFalse(File(expectedDefaultRoot, id.toString()).exists())

                assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
                val generation = requireNotNull(
                    preferences.getString("cleanup_leftover_downloads_generation", null)
                )
                val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
                val occurrenceAt = currentScheduledOccurrenceAt()
                workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
                val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
                assertEquals(
                    WorkInfo.State.SUCCEEDED,
                    awaitWorkById(request.id) { info ->
                        info.state == WorkInfo.State.SUCCEEDED ||
                            info.state == WorkInfo.State.FAILED ||
                            info.state == WorkInfo.State.CANCELLED
                    }.state,
                )
                assertFalse(owned.exists())
                assertFalse(DownloadCacheOwnership.markerFile(rootOne, id).exists())
                assertFalse(DownloadCacheOwnership.artifactManifestFile(rootOne, id).exists())
                assertFalse(File(expectedDefaultRoot, id.toString()).exists())
            } finally {
                scenario.close()
            }
        } finally {
            if (hadCachePath) {
                legacyPreferences.edit().putString("cache_path", previousCachePath).commit()
            } else {
                legacyPreferences.edit().remove("cache_path").commit()
            }
            rootOne.deleteRecursively()
        }
    }

    @Test
    fun folderSettingsResetPreservesR1ForLegacyJournalAfterRestart() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val id = database.downloadDao.insert(cleanupDownload("cache-root-reset-legacy"))
        createdDownloadIds += id
        val target = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        val rootOne = context.cacheDir.resolve("cleanup-reset-legacy-one-${UUID.randomUUID()}").apply { mkdirs() }
        val expectedDefaultRoot = (context.getExternalFilesDir(null) ?: context.cacheDir)
            .resolve("downloads")
            .canonicalFile
        val hadCachePath = legacyPreferences.contains("cache_path")
        val previousCachePath = legacyPreferences.getString("cache_path", null)
        try {
            assertTrue(legacyPreferences.edit().putString("cache_path", rootOne.absolutePath).commit())
            // The marker predates root-binding registration.  The real Folder
            // Settings reset must capture this old root before defaulting the
            // mutable cache_path preference.
            DownloadCacheOwnership.prepareAttempt(rootOne, target)
            val directory = File(rootOne, id.toString()).apply { mkdirs() }
            val owned = directory.resolve("owned.bin").apply { writeText("owned") }
            assertTrue(
                DownloadCacheOwnership.recordArtifacts(
                    rootOne,
                    target,
                    listOf(owned.absolutePath),
                )
            )

            val scenario = ActivityScenario.launch(SettingsActivity::class.java)
            try {
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.frame_layout) as NavHostFragment
                    navHost.navController.navigate(R.id.folderSettingsFragment)
                    navHost.childFragmentManager.executePendingTransactions()
                }
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.frame_layout) as NavHostFragment
                    val fragment = navHost.childFragmentManager.primaryNavigationFragment
                        as FolderSettingsFragment
                    fragment.findPreference<androidx.preference.Preference>("reset_preferences")
                        ?.performClick()
                }
                awaitSettingsUiReady(
                    scenario,
                    destinationId = R.id.folderSettingsFragment,
                    dialogTextResId = R.string.continue_anyway,
                )
                onView(withText(R.string.continue_anyway)).perform(click())
                assertTrue(
                    awaitPreference(timeoutMs = 10_000L) {
                        val stored = legacyPreferences.getString("cache_path", null).orEmpty()
                        stored.isNotBlank() && runCatching {
                            File(stored).canonicalFile == expectedDefaultRoot
                        }.getOrDefault(false)
                    }
                )
            } finally {
                scenario.close()
            }

            assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
            val generation = requireNotNull(
                preferences.getString("cleanup_leftover_downloads_generation", null),
            )
            val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
            val occurrenceAt = currentScheduledOccurrenceAt()
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)

            // This is the pre-root-binding journal shape: it records only the
            // target id.  After the reset and a simulated restart, production
            // legacy resolution must recover R1 from the captured locator.
            val legacyJournal = CleanupEffectJournal(
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                occurrenceAt = occurrenceAt,
                cancelledTargets = listOf(target),
                cancelledCacheCleanupRequiredIds = listOf(id),
                tempCleanupRequired = false,
            )
            assertTrue(
                CleanupScheduleCoordinator.seedEffectJournalForTesting(
                    context = context,
                    journal = legacyJournal,
                    phase = "eligible",
                )
            )
            CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)
            DownloadCacheOwnership.resetRootBindingProcessStateForTesting()

            val knownBindings = DownloadRepository(database).knownCacheCleanupBindings(target)
            assertEquals(1, knownBindings.size)
            assertEquals(rootOne.canonicalFile.absolutePath, knownBindings.single().rootPath)
            assertTrue(owned.isFile)
            assertFalse(File(expectedDefaultRoot, id.toString()).exists())

            val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
            val terminal = awaitWorkById(request.id) { info ->
                info.state == WorkInfo.State.SUCCEEDED ||
                    info.state == WorkInfo.State.FAILED ||
                    info.state == WorkInfo.State.CANCELLED
            }
            assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
            assertNull(database.downloadDao.getNullableDownloadById(id))
            assertFalse(owned.exists())
            assertFalse(DownloadCacheOwnership.markerFile(rootOne, id).exists())
            assertFalse(DownloadCacheOwnership.artifactManifestFile(rootOne, id).exists())
            assertFalse(File(expectedDefaultRoot, id.toString()).exists())
        } finally {
            if (hadCachePath) {
                legacyPreferences.edit().putString("cache_path", previousCachePath).commit()
            } else {
                legacyPreferences.edit().remove("cache_path").commit()
            }
            rootOne.deleteRecursively()
        }
    }

    @Test
    fun folderSettingsResetRetainsCachePathWhenRootCaptureFails() = runBlocking {
        val rootOne = context.cacheDir.resolve("cleanup-reset-failure-${UUID.randomUUID()}").apply { mkdirs() }
        val hadCachePath = legacyPreferences.contains("cache_path")
        val previousCachePath = legacyPreferences.getString("cache_path", null)
        try {
            assertTrue(legacyPreferences.edit().putString("cache_path", rootOne.absolutePath).commit())
            DownloadCacheOwnership.rootTransitionCaptureOverrideForTesting = { _, _ -> false }

            val scenario = ActivityScenario.launch(SettingsActivity::class.java)
            try {
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.frame_layout) as NavHostFragment
                    navHost.navController.navigate(R.id.folderSettingsFragment)
                    navHost.childFragmentManager.executePendingTransactions()
                }
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.frame_layout) as NavHostFragment
                    val fragment = navHost.childFragmentManager.primaryNavigationFragment
                        as FolderSettingsFragment
                    fragment.findPreference<androidx.preference.Preference>("reset_preferences")
                        ?.performClick()
                }
                awaitSettingsUiReady(
                    scenario,
                    destinationId = R.id.folderSettingsFragment,
                    dialogTextResId = R.string.continue_anyway,
                )
                onView(withText(R.string.continue_anyway)).perform(click())
                assertEquals(
                    rootOne.absolutePath,
                    legacyPreferences.getString("cache_path", null),
                )
            } finally {
                scenario.close()
            }
        } finally {
            DownloadCacheOwnership.rootTransitionCaptureOverrideForTesting = null
            if (hadCachePath) {
                legacyPreferences.edit().putString("cache_path", previousCachePath).commit()
            } else {
                legacyPreferences.edit().remove("cache_path").commit()
            }
            rootOne.deleteRecursively()
        }
    }

    @Test
    fun settingsMergeRestorePreservesPreBindingRootAndLegacyJournalAfterRestart() = runBlocking {
        assertSettingsRestorePreservesPreBindingRoot(
            resetData = false,
            includeImportedCachePath = true,
        )
    }

    @Test
    fun settingsResetRestorePreservesPreBindingRootAndLegacyJournalAfterRestart() = runBlocking {
        assertSettingsRestorePreservesPreBindingRoot(
            resetData = true,
            includeImportedCachePath = true,
        )
    }

    @Test
    fun settingsResetWithoutCachePathPreservesPreBindingRootAndLegacyJournal() = runBlocking {
        assertSettingsRestorePreservesPreBindingRoot(
            resetData = true,
            includeImportedCachePath = false,
        )
    }

    @Test
    fun settingsRestoreDoesNotRequireCacheRootCaptureForDestinationLocalPath() = runBlocking {
        val rootOne = context.cacheDir.resolve("settings-restore-capture-failure-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val originalPreferences = snapshotPreferences(legacyPreferences)
        try {
            assertTrue(legacyPreferences.edit().putString("cache_path", rootOne.absolutePath).commit())
            DownloadCacheOwnership.rootTransitionCaptureOverrideForTesting = { _, _ -> false }

            assertTrue(
                SettingsViewModel(context as android.app.Application).restoreData(
                    RestoreAppDataItem(
                        settings = listOf(BackupSettingsItem("settings_restore_merge", "no", "String")),
                    ),
                    context,
                )
            )
            assertEquals(rootOne.absolutePath, legacyPreferences.getString("cache_path", null))
            assertEquals("no", legacyPreferences.getString("settings_restore_merge", null))

            assertTrue(
                SettingsViewModel(context as android.app.Application).restoreData(
                    RestoreAppDataItem(
                        settings = listOf(BackupSettingsItem("settings_restore_reset", "no", "String")),
                    ),
                    context,
                    resetData = true,
                )
            )
            assertEquals(rootOne.absolutePath, legacyPreferences.getString("cache_path", null))
            assertEquals("no", legacyPreferences.getString("settings_restore_reset", null))
        } finally {
            DownloadCacheOwnership.rootTransitionCaptureOverrideForTesting = null
            restorePreferences(legacyPreferences, originalPreferences)
            rootOne.deleteRecursively()
        }
    }

    @Test
    fun refreshFailureResumesFrozenTargetsWithoutWideningToNewlyCancelledRow() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val firstId = database.downloadDao.insert(cleanupDownload("refresh-first"))
        createdDownloadIds += firstId
        val failureOnce = AtomicBoolean(true)
        LowQualityRedownloadLedger.refreshFailureForTesting = {
            if (failureOnce.compareAndSet(true, false)) {
                IllegalStateException("simulated cleanup refresh failure")
            } else {
                null
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)

        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        assertTrue(awaitDownload(timeoutMs = 10_000L) {
            database.downloadDao.getNullableDownloadById(firstId) == null
        })

        val secondId = database.downloadDao.insert(cleanupDownload("refresh-second"))
        createdDownloadIds += secondId
        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(secondId)?.status,
        )
    }

    @Test
    fun effectPhasePublicationFailureDoesNotRunCleanupBeforeRetry() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        val phaseCommitAttempts = AtomicInteger(0)
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = { editor ->
            if (phaseCommitAttempts.getAndIncrement() == 0) {
                false
            } else {
                editor.commit()
            }
        }

        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val retrying = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount >= 1
        }
        assertTrue(retrying.runAttemptCount >= 1)
        assertEquals(0, cleanupRuns.get())

        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertEquals(1, cleanupRuns.get())
    }

    @Test
    fun memoryVisibleEffectJournalFailureCannotGrantCleanupAuthority() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val occurrenceAt = currentScheduledOccurrenceAt()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)

        val phaseCommitAttempts = AtomicInteger(0)
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = true
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = { editor ->
            if (phaseCommitAttempts.getAndIncrement() == 0) {
                false
            } else {
                editor.commit()
            }
        }

        val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
        val retrying = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount >= 1
        }
        assertTrue(retrying.runAttemptCount >= 1)
        assertEquals(0, cleanupRuns.get())
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_effect_journal", null) != null
        )

        val terminal = awaitWorkById(request.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertEquals(1, cleanupRuns.get())
    }

    @Test
    fun retryDoesNotPublishSuccessorBeforeCleanupEventuallySucceeds() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val attempts = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            if (attempts.getAndIncrement() == 0) {
                throw IllegalStateException("deterministic cleanup retry")
            }
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        awaitWork(timeoutMs = 30_000L) { infos ->
            infos.any { it.runAttemptCount >= 1 && it.state == WorkInfo.State.ENQUEUED }
        }
        assertEquals(1, unfinishedCurrentWork().size)

        awaitWork(timeoutMs = 40_000L) { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0 } &&
                infos.any { it.state == WorkInfo.State.SUCCEEDED }
        }
        assertEquals(1, unfinishedCurrentWork().size)
        assertEquals(2, attempts.get())
    }

    @Test
    fun staleWorkerCannotResurrectOldCadenceAfterChange() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            if (invocations.getAndIncrement() == 0) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "old cleanup did not release" }
            }
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val reconfigure = async(Dispatchers.Default) {
            CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY)
        }
        delay(100L)
        assertFalse(reconfigure.isCompleted)
        release.countDown()
        assertTrue(reconfigure.await())

        val weekly = awaitWork(timeoutMs = 30_000L) { infos ->
            infos.any { info ->
                info.state == WorkInfo.State.ENQUEUED &&
                    info.tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY))
            }
        }
        assertTrue(weekly.any { it.tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY)) })
        assertTrue(
            unfinishedCurrentWork().all {
                it.tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY))
            }
        )
    }

    @Test
    fun staleWorkerCannotResurrectAfterDisable() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "cleanup did not release" }
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val disable = async(Dispatchers.Default) {
            CleanupScheduleCoordinator.configure(context, null)
        }
        delay(100L)
        assertFalse(disable.isCompleted)
        release.countDown()
        assertTrue(disable.await())

        awaitUnfinishedCount(0, timeoutMs = 30_000L)
        assertTrue(unfinishedCurrentWork().isEmpty())
    }

    @Test
    fun staleOccurrenceIsRejectedBeforeCleanupEffectAfterDisable() = runBlocking {
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val oldGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        assertTrue(CleanupScheduleCoordinator.configure(context, null))

        // WorkManager cancellation is asynchronous.  Submit an old-generation
        // request directly so the real worker must prove current authority at
        // its effect boundary rather than relying on cancellation timing.
        val staleRequest = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
            .setInputData(
                workDataOf(
                    CleanupScheduleCoordinator.INPUT_GENERATION to oldGeneration,
                    CleanupScheduleCoordinator.INPUT_CADENCE to CleanupSchedulePolicy.DAILY,
                )
            )
            .build()
        workManager.enqueue(staleRequest).result.get(20, TimeUnit.SECONDS)

        val terminal = awaitWorkById(staleRequest.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertTrue(terminal.outputData.getBoolean("cleanup_schedule_stale", false))
        assertEquals(0, cleanupRuns.get())
    }

    @Test
    fun disableBeforeDestructiveAdmissionFencesPausedOldWorker() = runBlocking {
        assertPausedWorkerCannotEnterAfterTransition(null)
    }

    @Test
    fun enabledSupersessionBeforeDestructiveAdmissionFencesPausedOldWorker() = runBlocking {
        assertPausedWorkerCannotEnterAfterTransition(CleanupSchedulePolicy.WEEKLY)
    }

    @Test
    fun settingsTransitionDoesNotBlockMainWhileCleanupEffectIsActive() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "cleanup did not release" }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val applied = Collections.synchronizedList(mutableListOf<String>())
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = CleanupSchedulePreferenceController(
                context = context,
                scope = controllerScope,
                applyPersistedCadence = applied::add,
            )
            val callbackReturned = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                controller.request(CleanupSchedulePolicy.WEEKLY)
                callbackReturned.countDown()
            }

            assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                preferences.getString("cleanup_leftover_downloads", null),
            )

            release.countDown()
            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    preferences.getString("cleanup_leftover_downloads", null) ==
                        CleanupSchedulePolicy.WEEKLY
                }
            )
            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    synchronized(applied) {
                        CleanupSchedulePolicy.WEEKLY in applied
                    }
                }
            )
        } finally {
            release.countDown()
            controllerScope.cancel()
        }
    }

    @Test
    fun settingsCommitFailureLeavesPreviousDurableCadenceVisible() = runBlocking {
        assertTrue(
            legacyPreferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .commit()
        )
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertEquals(
            CleanupSchedulePolicy.DAILY,
            preferences.getString("cleanup_leftover_downloads", null),
        )
        assertEquals(
            CleanupSchedulePolicy.DAILY,
            CleanupScheduleCoordinator.currentCadenceForSettings(context),
        )
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        val applied = Collections.synchronizedList(mutableListOf<String>())
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = CleanupSchedulePreferenceController(
                context = context,
                scope = controllerScope,
                applyPersistedCadence = applied::add,
            )
            controller.request(CleanupSchedulePolicy.WEEKLY)

            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    synchronized(applied) {
                        CleanupSchedulePolicy.DAILY in applied
                    }
                }
            )
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                preferences.getString("cleanup_leftover_downloads", null),
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun enabledAuthorityAndInitialDebtCommitBeforeEnqueueAttempt() = runBlocking {
        // Teardown clears the dedicated store so migration tests start from a
        // clean legacy state.  Establish that store outside this test's
        // measured authority transition; otherwise the first counted commit
        // is the one-time empty-store initialization rather than configure's
        // atomic authority publication.
        assertTrue(CleanupScheduleCoordinator.configure(context, null))
        val operation = ControlledOperation().also { controlledOperations += it }
        val authorityCommits = AtomicInteger(0)
        var enqueueSawMatchingDebt = false
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { editor ->
            authorityCommits.incrementAndGet()
            editor.commit()
        }
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, request ->
            val generation = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_GENERATION,
            )
            val cadence = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_CADENCE,
            )
            val anchorDay = request.workSpec.input.getInt(
                CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY,
                -1,
            )
            val occurrenceAt = request.workSpec.input.getLong(
                CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT,
                -1L,
            )
            enqueueSawMatchingDebt =
                generation == preferences.getString(
                    "cleanup_leftover_downloads_pending_generation",
                    null,
                ) &&
                    cadence == preferences.getString(
                        "cleanup_leftover_downloads_pending_cadence",
                        null,
                    ) &&
                    anchorDay == preferences.getInt(
                        "cleanup_leftover_downloads_pending_anchor_day",
                        -1,
                    ) &&
                    occurrenceAt == preferences.getLong(
                        "cleanup_leftover_downloads_pending_occurrence_at",
                        -1L,
                    )
            operation
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertEquals(1, authorityCommits.get())
        assertTrue(enqueueSawMatchingDebt)
    }

    @Test
    fun rapidSettingsRequestsLeaveLatestCadenceAuthoritative() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(
            legacyPreferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .commit()
        )
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = CleanupSchedulePreferenceController(
                context = context,
                scope = controllerScope,
                applyPersistedCadence = applied::add,
            )

            Handler(Looper.getMainLooper()).post {
                controller.request(CleanupSchedulePolicy.WEEKLY)
                controller.request("")
            }

            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    preferences.getString("cleanup_leftover_downloads", null).orEmpty().isEmpty()
                }
            )
            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    synchronized(applied) {
                        applied.lastOrNull().orEmpty().isEmpty()
                    }
                }
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun finalCleanupFailureRetainsExactOccurrenceForRecovery() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        // Keep the process-local replay owner out of the way until the
        // original D1 request has reached its terminal failure.  The
        // explicit reconcile below is the restart/recovery boundary under
        // test.
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting =
            TimeUnit.DAYS.toMillis(2)
        val attempts = AtomicInteger(0)
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = {
            admissionEntered.countDown()
            check(releaseAdmission.await(20, TimeUnit.SECONDS)) {
                "cleanup admission did not release"
            }
        }
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            attempts.incrementAndGet()
            throw IllegalStateException("deterministic final cleanup failure")
        }

        try {
            assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
            assertTrue(admissionEntered.await(10, TimeUnit.SECONDS))

            val generation = requireNotNull(
                preferences.getString("cleanup_leftover_downloads_generation", null),
            )
            val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
            val occurrenceAt = currentScheduledOccurrenceAt()
            val predecessorTag = occurrenceTag(generation, occurrenceAt)
            val expectedSuccessorAt = expectedSuccessorOccurrenceAt(
                predecessorOccurrenceAt = occurrenceAt,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
            )
            val successorTag = occurrenceTag(generation, expectedSuccessorAt)

            fun isUnfinished(info: WorkInfo): Boolean =
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED

            val original = awaitWork(timeoutMs = 10_000L) { current ->
                current.count { info ->
                    isUnfinished(info) && info.tags.contains(predecessorTag)
                } == 1
            }.single { info ->
                isUnfinished(info) && info.tags.contains(predecessorTag)
            }
            val originalId = original.id

            // The exact request is now identified while it is admitted, so
            // later assertions cannot accidentally observe a different
            // occurrence or a recovery request as the original D1.
            releaseAdmission.countDown()
            CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = null

            val retrying = awaitWorkById(originalId) { info ->
                info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount >= 1
            }
            assertTrue(retrying.tags.contains(predecessorTag))
            assertTrue(
                hasDurableScheduledOccurrence(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = occurrenceAt,
                ),
            )
            assertFalse(
                hasDurableScheduledOccurrence(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = expectedSuccessorAt,
                ),
            )
            val retryJournal = Gson().fromJson(
                requireNotNull(
                    preferences.getString("cleanup_leftover_downloads_effect_journal", null),
                ),
                CleanupEffectJournal::class.java,
            )
            assertTrue(retryJournal.matches(generation, CleanupSchedulePolicy.DAILY, anchorDay, occurrenceAt))
            assertTrue(retryJournal.isStructurallyValid())
            assertFalse(retryJournal.isComplete)
            assertTrue(
                listOf(
                    preferences.getString("cleanup_leftover_downloads_pending_effect_phase", null),
                    preferences.getString("cleanup_leftover_downloads_active_effect_phase", null),
                ).contains("in_progress"),
            )
            assertTrue(
                queryUniqueWorkInfos().none { info ->
                    isUnfinished(info) && info.tags.contains(successorTag)
                },
            )

            val terminal = awaitWorkById(originalId) { info ->
                info.state == WorkInfo.State.FAILED ||
                    info.state == WorkInfo.State.CANCELLED ||
                    info.state == WorkInfo.State.SUCCEEDED
            }
            assertEquals(WorkInfo.State.FAILED, terminal.state)
            assertEquals(CleanUpLeftoverDownloads.MAX_ATTEMPTS, terminal.runAttemptCount)
            assertEquals(CleanUpLeftoverDownloads.MAX_ATTEMPTS, attempts.get())
            assertTrue(terminal.outputData.getBoolean("cleanup_schedule_failure", false))
            assertTrue(terminal.outputData.getBoolean("cleanup_failure", false))
            assertTrue(terminal.outputData.getBoolean("cleanup_effect_recovery_required", false))

            val terminalJournal = Gson().fromJson(
                requireNotNull(
                    preferences.getString("cleanup_leftover_downloads_effect_journal", null),
                ),
                CleanupEffectJournal::class.java,
            )
            assertTrue(
                terminalJournal.matches(
                    generation,
                    CleanupSchedulePolicy.DAILY,
                    anchorDay,
                    occurrenceAt,
                ),
            )
            assertTrue(terminalJournal.isStructurallyValid())
            assertFalse(terminalJournal.isComplete)
            assertTrue(
                hasDurableScheduledOccurrence(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = occurrenceAt,
                ),
            )
            assertFalse(
                hasDurableScheduledOccurrence(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = expectedSuccessorAt,
                ),
            )
            assertTrue(
                queryUniqueWorkInfos().none { info ->
                    isUnfinished(info) && info.tags.contains(successorTag)
                },
            )

            // Model loss of the process-local replay owner, then let the
            // real reconciliation path requeue the exact unfinished D1.
            CleanupScheduleCoordinator.resetReplayOwnerForTesting()
            CleanupScheduleCoordinator.reconcile(context)
            val recovered = awaitWork(timeoutMs = 30_000L) { current ->
                val exactD1 = current.filter { info ->
                    isUnfinished(info) && info.tags.contains(predecessorTag)
                }
                exactD1.size == 1 && current.none { info ->
                    isUnfinished(info) && info.tags.contains(successorTag)
                }
            }
            val recoveredD1 = recovered.single { info ->
                isUnfinished(info) && info.tags.contains(predecessorTag)
            }
            assertTrue(recoveredD1.tags.contains(predecessorTag))
            assertTrue(
                hasDurableScheduledOccurrence(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = occurrenceAt,
                ),
            )
            assertFalse(
                hasDurableScheduledOccurrence(
                    generation = generation,
                    cadence = CleanupSchedulePolicy.DAILY,
                    monthlyAnchorDay = anchorDay,
                    occurrenceAt = expectedSuccessorAt,
                ),
            )
        } finally {
            releaseAdmission.countDown()
            CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = null
        }
    }

    @Test
    fun successorDebtReplaySurvivesWorkerRetryExhaustion() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 500L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting =
            WORK_MANAGER_MIN_BACKOFF_MILLIS
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
            // The initial occurrence must run promptly, but the replay path
            // rebuilds D2 with the initial-delay seam.  Hold D2 queued after
            // D1 has executed so this test can observe the handoff-pending
            // predecessor and the exact successor together.
            CleanupScheduleCoordinator.initialDelayOverrideForTesting =
                TimeUnit.DAYS.toMillis(2)
        }
        val successorEnqueueAttempts = AtomicInteger(0)
        val enqueueCalls = AtomicInteger(0)
        val initialRequestIds = Collections.synchronizedList(mutableListOf<UUID>())
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                // Keep the initial request on the real WorkManager path while
                // installing the successor failure seam before D1 can run.
                initialRequestIds += request.id
                workManager.enqueueUniqueWork(name, policy, request)
            } else if (
                successorEnqueueAttempts.getAndIncrement() <
                    CleanUpLeftoverDownloads.MAX_ATTEMPTS
            ) {
                throw IllegalStateException("successor enqueue failure")
            } else {
                workManager.enqueueUniqueWork(name, policy, request)
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val initialRequestId = synchronized(initialRequestIds) { initialRequestIds.single() }
        val initialResult = awaitWorkById(initialRequestId) { info ->
            info.state == WorkInfo.State.SUCCEEDED &&
                info.outputData.getBoolean("cleanup_schedule_handoff_pending", false)
        }
        val infos = awaitWork(timeoutMs = 60_000L) { current ->
            current.any { info ->
                info.state == WorkInfo.State.ENQUEUED &&
                    info.tags.contains(generationTag(generation)) &&
                    info.tags.any { tag ->
                        tag.startsWith("${CleanupScheduleCoordinator.TAG}_occurrence_")
                }
            }
        }
        assertTrue(initialResult.outputData.getBoolean("cleanup_schedule_handoff_pending", false))
        assertEquals(1, cleanupRuns.get())
        assertTrue(successorEnqueueAttempts.get() > CleanUpLeftoverDownloads.MAX_ATTEMPTS)
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun successfulCleanupDoesNotRepeatWhenSuccessorDiscoveryIsUnknown() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = {
            error("scheduler discovery unavailable")
        }

        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val terminal = awaitWork(timeoutMs = 30_000L) { infos ->
            infos.any { info ->
                info.state == WorkInfo.State.SUCCEEDED &&
                    info.tags.contains(generationTag(generation)) &&
                    info.outputData.getBoolean("cleanup_schedule_handoff_pending", false)
            }
        }
        assertTrue(terminal.any { info ->
            info.state == WorkInfo.State.SUCCEEDED &&
                info.outputData.getBoolean("cleanup_schedule_handoff_pending", false)
        })
        assertEquals(1, cleanupRuns.get())

        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = null
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) == -1L &&
                    preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) > 0L
            }
        )
        assertEquals(1, cleanupRuns.get())
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun downloadSettingsResetDisablesCleanupThroughProductionConsumer() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertEquals(1, unfinishedCurrentWork().size)

        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.frame_layout) as NavHostFragment
                navHost.navController.navigate(R.id.downloadSettingsFragment)
                navHost.childFragmentManager.executePendingTransactions()
            }
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.frame_layout) as NavHostFragment
                val fragment = navHost.childFragmentManager.primaryNavigationFragment
                    as DownloadSettingsFragment
                fragment.findPreference<androidx.preference.Preference>("reset_preferences")
                    ?.performClick()
            }
            awaitSettingsUiReady(
                scenario,
                destinationId = R.id.downloadSettingsFragment,
                dialogTextResId = R.string.continue_anyway,
            )
            onView(withText(R.string.continue_anyway)).perform(click())

            assertTrue(
                awaitPreference(timeoutMs = 10_000L) {
                    preferences.getString("cleanup_leftover_downloads", null).orEmpty().isEmpty()
                }
            )
            assertNull(preferences.getString("cleanup_leftover_downloads_pending_generation", null))
            assertNull(preferences.getString("cleanup_leftover_downloads_active_generation", null))
            assertTrue(awaitUnfinishedCount(0, timeoutMs = 10_000L))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun downloadSettingsResetWaitsForAdmittedCleanupWithoutBlockingUi() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupExited = CountDownLatch(1)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupStarted.countDown()
            try {
                check(releaseCleanup.await(WORKER_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "cleanup effect did not release"
                }
            } finally {
                cleanupExited.countDown()
            }
        }
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(cleanupStarted.await(WORKER_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.frame_layout) as NavHostFragment
                navHost.navController.navigate(R.id.downloadSettingsFragment)
                navHost.childFragmentManager.executePendingTransactions()
            }
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.frame_layout) as NavHostFragment
                val fragment = navHost.childFragmentManager.primaryNavigationFragment
                    as DownloadSettingsFragment
                fragment.findPreference<androidx.preference.Preference>("reset_preferences")
                    ?.performClick()
            }
            awaitSettingsUiReady(
                scenario,
                destinationId = R.id.downloadSettingsFragment,
                dialogTextResId = R.string.continue_anyway,
            )
            onView(withText(R.string.continue_anyway)).perform(click())

            val releaseSignaledBeforeAssertion =
                releaseCleanup.await(0, TimeUnit.MILLISECONDS)
            val cleanupExitedBeforeAssertion =
                cleanupExited.await(0, TimeUnit.MILLISECONDS)
            val dedicatedCadenceBeforeRelease =
                preferences.getString("cleanup_leftover_downloads", null)
            val coordinatorCadenceBeforeRelease =
                CleanupScheduleCoordinator.currentCadenceForSettings(context)
            check(!releaseSignaledBeforeAssertion) {
                "cleanup release was signaled before pre-release assertion; " +
                    "dedicatedCadence=$dedicatedCadenceBeforeRelease, " +
                    "coordinatorCadence=$coordinatorCadenceBeforeRelease"
            }
            check(!cleanupExitedBeforeAssertion) {
                "cleanup exited before pre-release assertion; " +
                    "dedicatedCadence=$dedicatedCadenceBeforeRelease, " +
                    "coordinatorCadence=$coordinatorCadenceBeforeRelease"
            }

            // The reset request is asynchronous.  Its initiating UI callback
            // returns while the admitted cleanup still owns the effect gate.
            assertEquals(CleanupSchedulePolicy.DAILY, dedicatedCadenceBeforeRelease)
            assertEquals(CleanupSchedulePolicy.DAILY, coordinatorCadenceBeforeRelease)
            releaseCleanup.countDown()
            assertTrue(
                "cleanup effect did not exit after release",
                cleanupExited.await(WORKER_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            )
            assertTrue(
                awaitPreference(timeoutMs = WORKER_WAIT_TIMEOUT_MILLIS) {
                    preferences.getString("cleanup_leftover_downloads", null).orEmpty().isEmpty()
                }
            )
            assertEquals("", CleanupScheduleCoordinator.currentCadenceForSettings(context))
            assertNull(preferences.getString("cleanup_leftover_downloads_pending_generation", null))
            assertNull(preferences.getString("cleanup_leftover_downloads_active_generation", null))
        } finally {
            releaseCleanup.countDown()
            scenario.close()
        }
    }

    @Test
    fun asynchronousEnqueueFailureLeavesDebtForStartupReconciliation() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                first
            } else {
                workManager.enqueueUniqueWork(name, policy, request)
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        first.fail(IllegalStateException("async enqueue failure"))
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertTrue(enqueueCalls.get() >= 2)
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun synchronousEnqueueFailureReplaysInProcessWithoutManualReconcile() = runBlocking {
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                throw IllegalStateException("synchronous enqueue failure")
            }
            workManager.enqueueUniqueWork(name, policy, request)
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertTrue(enqueueCalls.get() >= 2)
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun asynchronousEnqueueAcceptancePromotesMatchingDebt() = runBlocking {
        val operation = ControlledOperation().also { controlledOperations += it }
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ -> operation }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        operation.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null &&
                    preferences.getString("cleanup_leftover_downloads_active_generation", null)
                        ?.isNotBlank() == true
            }
        )
    }

    @Test
    fun staleAcceptanceCannotClearNewGenerationDebtAfterCadenceChange() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val second = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ ->
            if (enqueueCalls.getAndIncrement() == 0) first else second
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val firstGeneration = preferences.getString("cleanup_leftover_downloads_generation", null)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        val secondGeneration = preferences.getString("cleanup_leftover_downloads_generation", null)
        assertTrue(firstGeneration != secondGeneration)

        first.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == secondGeneration
            }
        )
        second.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
    }

    private suspend fun assertSettingsRestorePreservesPreBindingRoot(
        resetData: Boolean,
        includeImportedCachePath: Boolean,
    ) {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val id = database.downloadDao.insert(cleanupDownload("settings-restore-cache-root"))
        createdDownloadIds += id
        val target = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        val rootOne = context.cacheDir.resolve("settings-restore-root-one-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val rootTwo = context.cacheDir.resolve("settings-restore-root-two-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val originalPreferences = snapshotPreferences(legacyPreferences)
        try {
            assertTrue(legacyPreferences.edit().putString("cache_path", rootOne.absolutePath).commit())

            // This carrier intentionally predates RootBindingStore. Generic
            // settings restore must leave it unregistered because cache_path
            // is destination-local; discovery later must use the current
            // root's exact marker and manifest.
            DownloadCacheOwnership.prepareAttempt(rootOne, target)
            val owned = File(rootOne, id.toString()).resolve("owned.bin")
                .apply {
                    parentFile?.mkdirs()
                    writeText("owned")
                }
            assertTrue(DownloadCacheOwnership.recordArtifacts(rootOne, target, listOf(owned.absolutePath)))

            assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
            val generation = requireNotNull(
                preferences.getString("cleanup_leftover_downloads_generation", null),
            )
            val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
            val occurrenceAt = currentScheduledOccurrenceAt()
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            val legacyJournal = CleanupEffectJournal(
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                occurrenceAt = occurrenceAt,
                cancelledTargets = listOf(target),
                cancelledCacheCleanupRequiredIds = listOf(id),
                tempCleanupRequired = false,
            )
            assertTrue(
                CleanupScheduleCoordinator.seedEffectJournalForTesting(
                    context = context,
                    journal = legacyJournal,
                    phase = "eligible",
                )
            )

            val importedSettings = buildList {
                if (includeImportedCachePath) {
                    add(BackupSettingsItem("cache_path", rootTwo.absolutePath, "String"))
                }
                add(
                    BackupSettingsItem(
                        "cleanup_leftover_downloads",
                        CleanupSchedulePolicy.WEEKLY,
                        "String",
                    )
                )
                add(BackupSettingsItem("settings_restore_marker", "portable", "String"))
            }
            DownloadCacheOwnership.rootTransitionCaptureOverrideForTesting = { _, _ -> false }
            assertTrue(
                SettingsViewModel(context as android.app.Application).restoreData(
                    RestoreAppDataItem(settings = importedSettings),
                    context,
                    resetData = resetData,
                )
            )
            assertEquals(
                rootOne.canonicalFile.absolutePath,
                File(FileUtil.getCachePath(context)).canonicalFile.absolutePath,
            )
            assertEquals(
                rootOne.canonicalFile.absolutePath,
                File(requireNotNull(legacyPreferences.getString("cache_path", null)))
                    .canonicalFile.absolutePath,
            )
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                CleanupScheduleCoordinator.currentCadenceForSettings(context),
            )
            assertFalse(File(rootTwo, id.toString()).exists())

            // Any RootBindingStore entry would make this test pass for the
            // wrong reason. Generic restore must also succeed with the real
            // transition-capture seam forced to fail.
            DownloadCacheOwnership.clearRootBindingsForTesting(context)
            DownloadCacheOwnership.resetRootBindingProcessStateForTesting()
            val captured = DownloadRepository(database).exactCacheCleanupBindings(listOf(target))
            assertEquals(1, captured.size)
            assertEquals(rootOne.canonicalFile.absolutePath, captured.single().rootPath)

            // Drop process-local bindings and replay state while retaining
            // the exact durable journal/root carrier.
            CleanupScheduleCoordinator.simulateProcessRestartForTesting(context)
            DownloadCacheOwnership.resetRootBindingProcessStateForTesting()
            val recovered = DownloadRepository(database).knownCacheCleanupBindings(target)
            assertEquals(1, recovered.size)
            assertEquals(rootOne.canonicalFile.absolutePath, recovered.single().rootPath)

            val request = enqueueOccurrenceRequest(generation, anchorDay, occurrenceAt)
            val terminal = awaitWorkById(request.id) { info ->
                info.state == WorkInfo.State.SUCCEEDED ||
                    info.state == WorkInfo.State.FAILED ||
                    info.state == WorkInfo.State.CANCELLED
            }
            assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
            assertNull(database.downloadDao.getNullableDownloadById(id))
            assertFalse(owned.exists())
            assertFalse(DownloadCacheOwnership.markerFile(rootOne, id).exists())
            assertFalse(DownloadCacheOwnership.artifactManifestFile(rootOne, id).exists())
            assertFalse(File(rootTwo, id.toString()).exists())

            val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
                now = Calendar.getInstance().apply { timeInMillis = occurrenceAt },
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
            ).timeInMillis
            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) ==
                        expectedSuccessorAt ||
                        preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) ==
                        expectedSuccessorAt
                }
            )
            assertEquals("portable", legacyPreferences.getString("settings_restore_marker", null))
        } finally {
            DownloadCacheOwnership.rootTransitionCaptureOverrideForTesting = null
            restorePreferences(legacyPreferences, originalPreferences)
            rootOne.deleteRecursively()
            rootTwo.deleteRecursively()
        }
    }

    private fun snapshotPreferences(
        preferences: android.content.SharedPreferences,
    ): Map<String, Any?> = preferences.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
    }

    private fun restorePreferences(
        preferences: android.content.SharedPreferences,
        values: Map<String, Any?>,
    ) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(editor.commit())
    }

    private fun clearSchedulePreferences() {
        preferences.edit()
            .clear()
            .commit()
        legacyPreferences.edit()
            .remove("cleanup_leftover_downloads")
            .remove("cleanup_leftover_downloads_generation")
            .remove("cleanup_leftover_downloads_anchor_day")
            .remove("cleanup_leftover_downloads_pending_generation")
            .remove("cleanup_leftover_downloads_pending_cadence")
            .remove("cleanup_leftover_downloads_pending_anchor_day")
            .remove("cleanup_leftover_downloads_pending_occurrence_at")
            .remove("cleanup_leftover_downloads_pending_effect_phase")
            .remove("cleanup_leftover_downloads_active_generation")
            .remove("cleanup_leftover_downloads_active_cadence")
            .remove("cleanup_leftover_downloads_active_anchor_day")
            .remove("cleanup_leftover_downloads_active_occurrence_at")
            .remove("cleanup_leftover_downloads_active_effect_phase")
            .remove("cleanup_leftover_downloads_effect_journal")
            .commit()
    }

    /**
     * Bootstraps the dedicated critical store once, then leaves an enabled
     * schedule with no generation so tests exercise the bootstrap write rather
     * than accidentally failing at legacy-store migration first.
     */
    private suspend fun seedInitializedScheduleWithMissingGeneration() {
        val previousInitialDelay = CleanupScheduleCoordinator.initialDelayOverrideForTesting
        val previousReplayInitialDelay =
            CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting
        val previousReplayMaxDelay = CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting
        CleanupScheduleCoordinator.initialDelayOverrideForTesting =
            TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting =
            TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting =
            TimeUnit.DAYS.toMillis(2)
        try {
            assertTrue(
                legacyPreferences.edit()
                    .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                    .commit(),
            )
            CleanupScheduleCoordinator.reconcile(context)

            val seedGeneration = requireNotNull(
                preferences.getString("cleanup_leftover_downloads_generation", null),
            )
            assertTrue(
                awaitPreference(timeoutMs = WORKER_WAIT_TIMEOUT_MILLIS) {
                    preferences.getString(
                        "cleanup_leftover_downloads_generation",
                        null,
                    ) == seedGeneration &&
                        preferences.getString(
                            "cleanup_leftover_downloads_pending_generation",
                            null,
                        ) == null &&
                        preferences.getString(
                            "cleanup_leftover_downloads_active_generation",
                            null,
                        ) == seedGeneration
                },
            )
            awaitMainLooperIdle()
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                preferences.getString("cleanup_leftover_downloads", null),
            )
            assertEquals(
                seedGeneration,
                preferences.getString("cleanup_leftover_downloads_generation", null),
            )
            assertNull(
                preferences.getString("cleanup_leftover_downloads_pending_generation", null)
            )
            assertEquals(
                seedGeneration,
                preferences.getString("cleanup_leftover_downloads_active_generation", null),
            )

            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            awaitTaggedCleanupWorkIdle()
            workManager.pruneWork().result.get(20, TimeUnit.SECONDS)
            assertTrue(unfinishedCurrentWork().isEmpty())

            assertTrue(
                preferences.edit()
                    .remove("cleanup_leftover_downloads_generation")
                    .remove("cleanup_leftover_downloads_anchor_day")
                    .remove("cleanup_leftover_downloads_pending_generation")
                    .remove("cleanup_leftover_downloads_pending_cadence")
                    .remove("cleanup_leftover_downloads_pending_anchor_day")
                    .remove("cleanup_leftover_downloads_pending_occurrence_at")
                    .remove("cleanup_leftover_downloads_pending_effect_phase")
                    .remove("cleanup_leftover_downloads_active_generation")
                    .remove("cleanup_leftover_downloads_active_cadence")
                    .remove("cleanup_leftover_downloads_active_anchor_day")
                    .remove("cleanup_leftover_downloads_active_occurrence_at")
                    .remove("cleanup_leftover_downloads_active_effect_phase")
                    .remove("cleanup_leftover_downloads_effect_journal")
                    .commit(),
            )
            CleanupScheduleCoordinator.resetReplayOwnerForTesting()
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                preferences.getString("cleanup_leftover_downloads", null),
            )
            assertNull(preferences.getString("cleanup_leftover_downloads_generation", null))
            assertNull(
                preferences.getString("cleanup_leftover_downloads_pending_generation", null)
            )
            assertNull(
                preferences.getString("cleanup_leftover_downloads_active_generation", null)
            )
            assertTrue(unfinishedCurrentWork().isEmpty())
        } finally {
            CleanupScheduleCoordinator.initialDelayOverrideForTesting = previousInitialDelay
            CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting =
                previousReplayInitialDelay
            CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = previousReplayMaxDelay
        }
    }

    private fun clearTestSeams() {
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()
        CleanupScheduleCoordinator.workManagerForTesting = null
        CleanupScheduleCoordinator.nowProviderForTesting = null
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = null
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = null
        CleanupScheduleCoordinator.enqueueOverrideForTesting = null
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = null
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        CleanupScheduleCoordinator.effectPhaseCommitOverrideForTesting = null
        CleanupScheduleCoordinator.commitFailureAppliesMemoryForTesting = false
        CleanupScheduleCoordinator.resetDurabilityFenceForTesting()
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = null
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = null
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting = null
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = null
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = null
        DownloadRepository.cleanupAfterRoomDeletionForTesting = null
        DownloadRepository.exactCacheDeletionForTesting = null
        DownloadCacheOwnership.fileDeletionForTesting = null
        DownloadCacheOwnership.rootTransitionCaptureOverrideForTesting = null
        LowQualityRedownloadLedger.refreshFailureForTesting = null
    }

    private suspend fun assertPausedWorkerCannotEnterAfterTransition(
        replacementCadence: String?,
    ) = coroutineScope {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = {
            admitted.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "paused cleanup did not release" }
        }
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val oldGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val staleRequest = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
            .setInputData(
                workDataOf(
                    CleanupScheduleCoordinator.INPUT_GENERATION to oldGeneration,
                    CleanupScheduleCoordinator.INPUT_CADENCE to CleanupSchedulePolicy.DAILY,
                )
            )
            .build()
        workManager.enqueue(staleRequest).result.get(20, TimeUnit.SECONDS)
        assertTrue(admitted.await(10, TimeUnit.SECONDS))

        val transition = async(Dispatchers.Default) {
            CleanupScheduleCoordinator.configure(context, replacementCadence)
        }
        assertTrue(transition.await())
        if (replacementCadence == null) {
            assertEquals(
                "",
                preferences.getString("cleanup_leftover_downloads", null),
            )
        } else {
            assertEquals(
                replacementCadence,
                preferences.getString("cleanup_leftover_downloads", null),
            )
        }

        release.countDown()
        val terminal = awaitWorkById(staleRequest.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertTrue(terminal.outputData.getBoolean("cleanup_schedule_stale", false))
        assertEquals(0, cleanupRuns.get())
    }

    private fun unfinishedCurrentWork(): List<WorkInfo> = queryUniqueWorkInfos()
        .filter { it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED }

    private fun currentGenerationWork(generation: String): List<WorkInfo> = queryUniqueWorkInfos()
        .filter { it.tags.contains(generationTag(generation)) }

    private fun queryUniqueWorkInfos(): List<WorkInfo> = try {
        workManager.getWorkInfosForUniqueWork(CleanupScheduleCoordinator.WORK_NAME)
            .get(WORK_MANAGER_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        // A slow WorkManager query is a polling miss, not a test hang.  The
        // outer await timeout remains the useful failure boundary.
        emptyList()
    }

    private fun queryWorkInfoById(id: UUID): WorkInfo? = try {
        workManager.getWorkInfoById(id)
            .get(WORK_MANAGER_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    }

    private suspend fun disableCleanupAuthorityBeforeWorkDrain() {
        // Controlled enqueue operations and fault seams must be resolved before
        // the real disable transition, otherwise configure(null) can be
        // prevented from revoking the authority that is allowed to recreate
        // cleanup work.
        failOutstandingControlledOperations()
        clearTestSeams()
        assertTrue(CleanupScheduleCoordinator.configure(context, null))
        awaitMainLooperIdle()

        // Only after replay ownership and durable cleanup authority are
        // revoked is it safe to cancel and drain WorkManager. This preserves
        // diagnostics until the actor that could recreate work is stopped.
        cancelAllWorkAndAwaitIdle()
        awaitCleanupCancellationQuiescence()
    }

    private suspend fun awaitSettingsUiReady(
        scenario: ActivityScenario<SettingsActivity>,
        destinationId: Int? = null,
        dialogTextResId: Int? = null,
    ) {
        var lastActivityWindowFocus = false
        var lastAccessibilityWindows = ""
        var lastDialogNodeCount = 0
        val ready = withTimeoutOrNull(SETTINGS_UI_READY_TIMEOUT_MILLIS) {
            var ready = false
            while (!ready) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                var activityReady = false
                var currentDestination: Int? = null
                runCatching {
                    if (scenario.state == Lifecycle.State.RESUMED) {
                        scenario.onActivity { activity ->
                            val navHost = activity.supportFragmentManager
                                .findFragmentById(R.id.frame_layout) as? NavHostFragment
                            activityReady =
                                activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                                    activity.window.decorView.isShown &&
                                    (dialogTextResId != null ||
                                        activity.window.decorView.hasWindowFocus())
                            lastActivityWindowFocus = activity.window.decorView.hasWindowFocus()
                            currentDestination = navHost?.navController?.currentDestination?.id
                        }
                    }
                }

                val accessibilityWindows = accessibilityWindows()
                lastAccessibilityWindows = accessibilityWindows
                    .joinToString { window ->
                        "type=${window.type},active=${window.isActive},focused=${window.isFocused}," +
                            "package=${runCatching { window.root?.packageName?.toString() }
                                .getOrNull()}"
                    }
                val focusedWindow = accessibilityWindows.firstOrNull { window ->
                    window.isFocused && runCatching {
                        window.root?.packageName?.toString() == context.packageName
                    }.getOrDefault(false)
                }
                val destinationReady = destinationId == null || currentDestination == destinationId
                val dialogNodes = if (dialogTextResId == null) {
                    emptyList()
                } else {
                    focusedWindow?.root
                        ?.findAccessibilityNodeInfosByText(context.getString(dialogTextResId))
                        .orEmpty()
                }
                lastDialogNodeCount = dialogNodes.size
                val dialogReady = dialogTextResId == null || dialogNodes.any { node ->
                    node.isVisibleToUser
                }

                ready = activityReady && focusedWindow != null && destinationReady && dialogReady
                if (!ready) {
                    delay(WORK_POLL_INTERVAL_MILLIS)
                }
            }
            awaitMainLooperIdle()
            true
        } ?: false

        if (!ready) {
            val state = runCatching { scenario.state }.getOrNull()
            throw AssertionError(
                "Timed out waiting for focused SettingsActivity UI: " +
                    "state=$state, destination=$destinationId, dialogTextResId=$dialogTextResId, " +
                    "activityWindowFocus=$lastActivityWindowFocus, " +
                    "dialogNodeCount=$lastDialogNodeCount, " +
                    "accessibilityWindows=$lastAccessibilityWindows",
            )
        }
    }

    private fun accessibilityWindows(): List<AccessibilityWindowInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val serviceInfo = automation.serviceInfo
        if (serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            serviceInfo.flags = serviceInfo.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = serviceInfo
        }
        return automation.windows
    }

    private suspend fun awaitCleanupCancellationQuiescence() {
        // configure(null) deliberately exposes no synchronous cancellation
        // handle. WorkManager serializes operations on its task executor, so
        // this later bounded tag cancellation fences the cancellation queued
        // by configure(null) before the next test can create tagged cleanup
        // work.
        workManager.cancelAllWorkByTag(CleanupScheduleCoordinator.TAG)
            .result.get(20, TimeUnit.SECONDS)
        awaitTaggedCleanupWorkIdle()
        workManager.pruneWork().result.get(20, TimeUnit.SECONDS)
    }

    private suspend fun cancelAllWorkAndAwaitIdle() {
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        awaitTaggedCleanupWorkIdle()
        workManager.pruneWork().result.get(20, TimeUnit.SECONDS)
    }

    private suspend fun awaitTaggedCleanupWorkIdle() {
        var lastObserved = emptyList<WorkInfo>()
        val settled = withTimeoutOrNull(WORKER_WAIT_TIMEOUT_MILLIS) {
            while (true) {
                lastObserved = withContext(Dispatchers.IO) {
                    try {
                        workManager.getWorkInfosByTag(CleanupScheduleCoordinator.TAG)
                            .get(WORK_MANAGER_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        emptyList()
                    }
                }
                if (lastObserved.none { info ->
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED
                    }
                ) {
                    return@withTimeoutOrNull true
                }
                delay(WORK_POLL_INTERVAL_MILLIS)
            }
            error("unreachable")
        } ?: false
        if (!settled) {
            throw AssertionError(
                "Timed out waiting for canceled cleanup work; last observed=" +
                    lastObserved.joinToString { info ->
                        "${info.id}:${info.state}/attempt=${info.runAttemptCount}"
                },
            )
        }
    }

    private fun awaitMainLooperIdle() {
        val idle = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { idle.countDown() }
        check(idle.await(20, TimeUnit.SECONDS)) {
            "Timed out waiting for the Android main looper to drain"
        }
    }

    private fun failOutstandingControlledOperations() {
        controlledOperations.forEach { operation ->
            operation.failIfPending()
        }
    }

    private suspend fun awaitWork(
        timeoutMs: Long,
        predicate: (List<WorkInfo>) -> Boolean,
    ): List<WorkInfo> {
        var lastObserved = emptyList<WorkInfo>()
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                lastObserved = withContext(Dispatchers.IO) { queryUniqueWorkInfos() }
                if (predicate(lastObserved)) return@withTimeoutOrNull lastObserved
                delay(WORK_POLL_INTERVAL_MILLIS)
            }
            error("unreachable")
        } ?: throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for cleanup work; " +
                "last observed=" + lastObserved.joinToString { info ->
                    "${info.id}:${info.state}/attempt=${info.runAttemptCount}"
                },
        )
    }

    private suspend fun awaitWorkById(
        id: UUID,
        timeoutMs: Long = WORKER_WAIT_TIMEOUT_MILLIS,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo {
        var lastObserved: WorkInfo? = null
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                lastObserved = withContext(Dispatchers.IO) { queryWorkInfoById(id) }
                val info = lastObserved
                if (info != null && predicate(info)) return@withTimeoutOrNull info
                delay(WORK_POLL_INTERVAL_MILLIS)
            }
            error("unreachable")
        } ?: throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for WorkManager request $id; " +
                "last observed=" + (lastObserved?.let { info ->
                    "${info.state}/attempt=${info.runAttemptCount}"
                } ?: "<no WorkInfo row>"),
        )
    }

    private fun expectedSuccessorOccurrenceAt(
        predecessorOccurrenceAt: Long,
        cadence: String,
        monthlyAnchorDay: Int,
    ): Long = CleanupSchedulePolicy.nextOccurrence(
        now = Calendar.getInstance().apply { timeInMillis = predecessorOccurrenceAt },
        cadence = cadence,
        monthlyAnchorDay = monthlyAnchorDay,
    ).timeInMillis

    private fun hasDurableScheduledOccurrence(
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        occurrenceAt: Long,
    ): Boolean {
        fun matches(prefix: String): Boolean =
            preferences.getString("${prefix}_generation", null) == generation &&
                preferences.getString("${prefix}_cadence", null) == cadence &&
                preferences.getInt("${prefix}_anchor_day", -1) == monthlyAnchorDay &&
                preferences.getLong("${prefix}_occurrence_at", -1L) == occurrenceAt

        return matches("cleanup_leftover_downloads_pending") ||
            matches("cleanup_leftover_downloads_active")
    }

    private fun durableSchedulingState(): String =
        "pending=(" +
            preferences.getString("cleanup_leftover_downloads_pending_generation", null) + "," +
            preferences.getString("cleanup_leftover_downloads_pending_cadence", null) + "," +
            preferences.getInt("cleanup_leftover_downloads_pending_anchor_day", -1) + "," +
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) +
            "), active=(" +
            preferences.getString("cleanup_leftover_downloads_active_generation", null) + "," +
            preferences.getString("cleanup_leftover_downloads_active_cadence", null) + "," +
            preferences.getInt("cleanup_leftover_downloads_active_anchor_day", -1) + "," +
            preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) +
            ")"

    private fun describeWorkInfos(infos: List<WorkInfo>): String =
        infos.joinToString(prefix = "[", postfix = "]") { info ->
            "${info.id}:${info.state}/attempt=${info.runAttemptCount}/tags=${info.tags.sorted()}"
        }

    private suspend fun awaitExactSuccessor(
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        predecessorOccurrenceAt: Long,
        timeoutMs: Long = WORKER_WAIT_TIMEOUT_MILLIS,
    ): WorkInfo {
        val expectedSuccessorAt = expectedSuccessorOccurrenceAt(
            predecessorOccurrenceAt = predecessorOccurrenceAt,
            cadence = cadence,
            monthlyAnchorDay = monthlyAnchorDay,
        )
        val expectedTag = occurrenceTag(generation, expectedSuccessorAt)
        var lastObserved = emptyList<WorkInfo>()
        val found = withTimeoutOrNull(timeoutMs) {
            while (true) {
                lastObserved = withContext(Dispatchers.IO) { queryUniqueWorkInfos() }
                val exact = lastObserved.filter { it.tags.contains(expectedTag) }
                val live = exact.filter {
                    it.state != WorkInfo.State.CANCELLED &&
                        it.state != WorkInfo.State.FAILED
                }
                if (live.size > 1) {
                    throw AssertionError(
                        "duplicate live cleanup successors for $expectedTag; " +
                            "predecessor=$predecessorOccurrenceAt; " +
                            "durable=${durableSchedulingState()}; " +
                            "uniqueWork=${describeWorkInfos(lastObserved)}",
                    )
                }
                if (live.size == 1 && hasDurableScheduledOccurrence(
                        generation = generation,
                        cadence = cadence,
                        monthlyAnchorDay = monthlyAnchorDay,
                        occurrenceAt = expectedSuccessorAt,
                    )
                ) {
                    return@withTimeoutOrNull live.single()
                }
                delay(WORK_POLL_INTERVAL_MILLIS)
            }
            error("unreachable")
        }
        return found ?: throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for exact successor; " +
                "predecessor=$predecessorOccurrenceAt; expected=$expectedSuccessorAt; " +
                "generation=$generation; cadence=$cadence; anchor=$monthlyAnchorDay; " +
                "durable=${durableSchedulingState()}; " +
                "uniqueWork=${describeWorkInfos(lastObserved)}",
        )
    }

    private suspend fun awaitDownload(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean = withTimeout(timeoutMs) {
        while (!predicate()) delay(WORK_POLL_INTERVAL_MILLIS)
        true
    }

    private fun currentScheduledOccurrenceAt(): Long {
        val pending = preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L)
        return if (pending > 0L) {
            pending
        } else {
            preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L)
        }.also { occurrenceAt ->
            assertTrue("expected a durable scheduled occurrence", occurrenceAt > 0L)
        }
    }

    private suspend fun awaitUnfinishedCount(
        expected: Int,
        timeoutMs: Long = 10_000L,
    ): Boolean {
        awaitWork(timeoutMs) { unfinishedCurrentWork().size == expected }
        return true
    }

    private fun cleanupDownload(
        suffix: String,
        executionId: String = "cleanup-execution-$suffix-${UUID.randomUUID()}",
    ): DownloadItem = DownloadItem(
        id = 0L,
        url = "https://example.com/cleanup/$suffix/${UUID.randomUUID()}",
        title = "cleanup-$suffix",
        author = "cleanup-test",
        thumb = "",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(
            format_id = "18",
            container = "mp4",
            vcodec = "avc1",
            acodec = "mp4a",
        ),
        container = "mp4",
        downloadSections = "",
        allFormats = arrayListOf(),
        downloadPath = context.filesDir.resolve("cleanup-$suffix").absolutePath,
        website = "example.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "%(title)s",
        SaveThumb = false,
        status = DownloadRepository.Status.Cancelled.name,
        downloadStartTime = System.currentTimeMillis(),
        executionId = executionId,
        logID = null,
        operationId = "cleanup-test-$suffix-${UUID.randomUUID()}",
    )

    private fun markCurrentEffectConsumed(occurrenceAt: Long) {
        val editor = preferences.edit()
        if (preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L) == occurrenceAt) {
            editor.putString("cleanup_leftover_downloads_pending_effect_phase", "consumed")
        }
        if (preferences.getLong("cleanup_leftover_downloads_active_occurrence_at", -1L) == occurrenceAt) {
            editor.putString("cleanup_leftover_downloads_active_effect_phase", "consumed")
        }
        assertTrue(editor.commit())
    }

    private fun enqueueOccurrenceRequest(
        generation: String,
        monthlyAnchorDay: Int,
        occurrenceAt: Long,
    ): androidx.work.OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
            .setInputData(
                workDataOf(
                    CleanupScheduleCoordinator.INPUT_GENERATION to generation,
                    CleanupScheduleCoordinator.INPUT_CADENCE to CleanupSchedulePolicy.DAILY,
                    CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY to monthlyAnchorDay,
                    CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT to occurrenceAt,
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WORK_MANAGER_MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        workManager.enqueue(request).result.get(20, TimeUnit.SECONDS)
        return request
    }

    private suspend fun awaitPreference(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean = withTimeout(timeoutMs) {
        while (!predicate()) {
            delay(25L)
        }
        true
    }

    private fun cadenceTag(cadence: String): String =
        "${CleanupScheduleCoordinator.TAG}_cadence_$cadence"

    private fun generationTag(generation: String): String =
        "${CleanupScheduleCoordinator.TAG}_generation_$generation"

    private fun occurrenceTag(generation: String, occurrenceAt: Long): String =
        "${CleanupScheduleCoordinator.TAG}_occurrence_${generation}_$occurrenceAt"

    private class ControlledOperation : Operation {
        private val state = MutableLiveData<Operation.State>(Operation.IN_PROGRESS)
        private val result = SettableFuture.create<Operation.State.SUCCESS>()

        override fun getState(): LiveData<Operation.State> = state

        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = result

        fun succeed() {
            state.postValue(Operation.SUCCESS)
            result.set(Operation.SUCCESS)
        }

        fun fail(error: Throwable) {
            state.postValue(Operation.State.FAILURE(error))
            result.setException(error)
        }

        fun failIfPending() {
            if (!result.isDone) {
                fail(IllegalStateException("instrumentation test teardown"))
            }
        }
    }
}
