package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreOutcome
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.util.BackupSettingsUtil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Production-boundary proof for the durable scheduler-settings transition.
 * The interruption seam is before the actual AlarmScheduler effect, so the
 * only recovery source for these tests is the persisted transition record.
 */
@RunWith(AndroidJUnit4::class)
class F11SchedulerSettingsTransitionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var workManager: WorkManager
    private lateinit var database: DBManager

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        SchedulerSettingsTransitionCoordinator.clearForTesting(context)
        preferences.edit()
            .remove("schedule_start")
            .remove("schedule_end")
            .remove("use_scheduler")
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ -> }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        SchedulerSettingsTransitionCoordinator.clearForTesting(context)
        AlarmScheduler.exactAlarmPublicationForTesting = null
        AlarmScheduler.schedulerTransitionStepForTesting = null
        AlarmScheduler.beforeDisableSuccessorEnqueueForTesting = null
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun scheduleStartPreferenceAndTransitionReplayAfterInterruptedEffect() = runBlocking {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = {
            error("simulated process death before scheduler publication")
        }

        assertTrue(
            runCatching {
                AlarmScheduler(context).updateScheduleBoundary("schedule_start", "01:11")
            }.isFailure,
        )
        val pending = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals("01:11", preferences.getString("schedule_start", null))
        assertEquals(
            SchedulerSettingsTransitionCoordinator.Kind.SCHEDULE_START,
            pending.checkedKind(),
        )

        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)

        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.SCHEDULE_START,
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.START_BOUNDARY,
            ),
        )
        assertNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.SCHEDULE_END,
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.END_BOUNDARY,
            ),
        )
    }

    @Test
    fun transitionOwnerIsDurableBeforeTargetPreferencePublication() = runBlocking {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        SchedulerSettingsTransitionCoordinator.beforePreferencePublicationForTesting = {
            error("simulated process death before target preference commit")
        }

        assertTrue(
            runCatching {
                AlarmScheduler(context).updateScheduleBoundary("schedule_start", "02:02")
            }.isFailure,
        )
        assertEquals("00:00", preferences.getString("schedule_start", null))
        assertEquals(
            SchedulerSettingsTransitionCoordinator.Kind.SCHEDULE_START,
            requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
                .checkedKind(),
        )

        SchedulerSettingsTransitionCoordinator.beforePreferencePublicationForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)

        assertEquals("02:02", preferences.getString("schedule_start", null))
        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
    }

    @Test
    fun enableTransitionIsRecoverableBeforeAnySchedulerCarrierExists() = runBlocking {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", false)
            .commit()
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = {
            error("simulated process death after enable decision")
        }

        assertTrue(runCatching { AlarmScheduler(context).updateSchedulerEnabled(true) }.isFailure)
        assertEquals(true, preferences.getBoolean("use_scheduler", false))
        assertNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())

        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)

        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals(2, database.workManagerHandoffCarrierDao.getOutstanding().size)
    }

    @Test
    fun scheduleTransitionReplaysAfterOldAuthorityCancellationBeforeStartPublication() = runBlocking {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        var interrupted = false
        AlarmScheduler.schedulerTransitionStepForTesting = { step ->
            if (step == AlarmScheduler.TRANSITION_STEP_AFTER_CANCELLATION && !interrupted) {
                interrupted = true
                error("simulated process death after old scheduler cancellation")
            }
        }

        assertTrue(
            runCatching {
                AlarmScheduler(context).updateScheduleBoundary("schedule_end", "03:03")
            }.isFailure,
        )
        assertNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))

        AlarmScheduler.schedulerTransitionStepForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)

        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals(2, database.workManagerHandoffCarrierDao.getOutstanding().size)
    }

    @Test
    fun scheduleTransitionReplaysAfterStartPublicationBeforeEndPublication() = runBlocking {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        var interrupted = false
        AlarmScheduler.schedulerTransitionStepForTesting = { step ->
            if (
                step == AlarmScheduler.TRANSITION_STEP_AFTER_START_PUBLICATION &&
                !interrupted
            ) {
                interrupted = true
                error("simulated process death before end publication")
            }
        }

        assertTrue(
            runCatching {
                AlarmScheduler(context).updateScheduleBoundary("schedule_start", "03:04")
            }.isFailure,
        )
        assertNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.SCHEDULE_START,
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.START_BOUNDARY,
            ),
        )

        AlarmScheduler.schedulerTransitionStepForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)

        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals(2, database.workManagerHandoffCarrierDao.getOutstanding().size)
    }

    @Test
    fun disableReplayKeepsOneStableSuccessorAfterEffectBeforeRetirementFailure() {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        AlarmScheduler(context).schedule()
        SchedulerSettingsTransitionCoordinator.beforeRetirementForTesting = {
            error("simulated process death before transition retirement")
        }

        assertTrue(runCatching { AlarmScheduler(context).updateSchedulerEnabled(false) }.isFailure)
        val pending = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        val uniqueName = "scheduler-disable-${pending.id}-0"

        SchedulerSettingsTransitionCoordinator.beforeRetirementForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)

        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
        assertEquals(1, infos.size)
        assertTrue(infos.single().id.toString().isNotBlank())
        assertEquals(false, preferences.getBoolean("use_scheduler", true))
    }

    @Test
    fun disableTransitionRemainsDurableWhenSuccessorEnqueueFails() {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        AlarmScheduler(context).schedule()
        AlarmScheduler.beforeDisableSuccessorEnqueueForTesting = {
            error("simulated process death before disable successor enqueue")
        }

        assertTrue(runCatching { AlarmScheduler(context).updateSchedulerEnabled(false) }.isFailure)
        val pending = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        val uniqueName = "scheduler-disable-${pending.id}-0"
        assertTrue(
            workManager.getWorkInfosForUniqueWork(uniqueName)
                .get(10, TimeUnit.SECONDS)
                .isEmpty(),
        )

        AlarmScheduler.beforeDisableSuccessorEnqueueForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)

        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
        assertEquals(1, infos.size)
        assertFalse(preferences.getBoolean("use_scheduler", true))
    }

    @Test
    fun schedulerTransitionRuntimeKeyIsNotPortable() {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = {
            error("leave durable transition debt")
        }
        runCatching { AlarmScheduler(context).updateScheduleBoundary("schedule_end", "03:33") }

        val backup = BackupSettingsUtil.backupSettings(preferences).getOrThrow()
        assertTrue(
            backup.none {
                it.asJsonObject.get("key").asString
                    .startsWith("scheduler_settings_transition_")
            },
        )
        assertFalse(BackupSettingsUtil.isPortablePreferenceKey("scheduler_settings_transition_v1"))
    }

    @Test
    fun restoreExplicitlySupersedesOldOrdinaryTransition() = runBlocking {
        preferences.edit()
            .putString("schedule_start", "00:00")
            .putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", true)
            .commit()
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = {
            error("leave old ordinary transition debt")
        }
        runCatching { AlarmScheduler(context).updateScheduleBoundary("schedule_start", "04:44") }
        assertNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))

        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = null
        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(BackupSettingsItem("restore_marker", "new", "String")),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
    }

    private fun settingsPlan(vararg settings: BackupSettingsItem): RestorePlan =
        BackupRestoreParser.fromTyped(
            RestoreAppDataItem(settings = settings.toList()),
        )
}
