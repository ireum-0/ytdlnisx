package com.ireum.ytdl.work

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.receiver.CancelScheduleAlarmReceiver
import com.ireum.ytdl.receiver.ScheduleAlarmReceiver
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
import java.util.concurrent.CountDownLatch
import java.util.UUID

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
        val old = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))

        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = null
        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(BackupSettingsItem("restore_marker", "new", "String")),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        val restored = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertTrue(restored.id != old.id)
        assertEquals(SchedulerSettingsTransitionCoordinator.Kind.RESTORE, restored.checkedKind())
        assertEquals("COMPLETE", restored.phase)
    }

    @Test
    fun resetEnabledWindowReplacesOldOwnersWithMatchingFixedBoundaries() = runBlocking {
        val old = seedWindow(true)
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }

        val outcome = RestoreTransactionCoordinator.begin(context, restoredWindowPlan(true))

        assertTrue(outcome is RestoreOutcome.Completed)
        val restored = assertRestoredImage(true, "11:12", "13:14", old)
        assertEquals(listOf(restored.startAt, restored.endAt), published)
    }

    @Test
    fun resetDisabledWindowRevokesBothOldBoundaryOwners() = runBlocking {
        val old = seedWindow(true)
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }

        val outcome = RestoreTransactionCoordinator.begin(context, restoredWindowPlan(false))

        assertTrue(outcome is RestoreOutcome.Completed)
        assertRestoredImage(false, "11:12", "13:14", old)
        assertTrue(published.isEmpty())
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())
    }

    @Test
    fun resetEnabledWindowPublishesBothOwnersFromDisabledSettings() = runBlocking {
        val old = seedWindow(false)
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }

        val outcome = RestoreTransactionCoordinator.begin(context, restoredWindowPlan(true))

        assertTrue(outcome is RestoreOutcome.Completed)
        val restored = assertRestoredImage(true, "11:12", "13:14", old)
        assertEquals(listOf(restored.startAt, restored.endAt), published)
    }

    @Test
    fun realMergeRetainsMissingWindowSettingsAndPublishesItsEffectiveImage() = runBlocking {
        val old = seedWindow(true)
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }

        val outcome = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(settings = listOf(BackupSettingsItem("schedule_start", "11:12", "String"))),
            context,
            resetData = false,
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        val restored = assertRestoredImage(true, "11:12", "05:00", old)
        assertTrue(restored.restoreOperationId.isBlank())
        assertEquals(listOf(restored.startAt, restored.endAt), published)
    }

    @Test
    fun malformedPortableTimesAreRejectedBeforeAnyRuntimeAuthorityChanges() = runBlocking {
        val old = seedWindow(true)
        val viewModel = SettingsViewModel(context as Application)
        for (key in listOf("schedule_start", "schedule_end")) {
            for (value in listOf("0500", "xx:yy", "24:00", "23:60", "-1:00", "123:00", "5:00")) {
                val outcome = viewModel.restoreData(
                    RestoreAppDataItem(settings = listOf(BackupSettingsItem(key, value, "String"))),
                    context,
                    resetData = true,
                )
                assertTrue("$key=$value", outcome is RestoreOutcome.RejectedBeforeOwnership)
                assertNull(RestoreOperationStore.load(context))
                assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
                assertEquals("00:00", preferences.getString("schedule_start", null))
                assertEquals("05:00", preferences.getString("schedule_end", null))
                assertTrue(preferences.getBoolean("use_scheduler", false))
                old.forEach { assertNotNull(database.workManagerHandoffCarrierDao.get(it)) }
            }
        }
    }

    @Test
    fun resetRecoveryReplaysItsDurableImageAfterPreferencePublication() = runBlocking {
        val old = seedWindow(true)
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = {
            error("interrupt restored scheduler after durable preferences")
        }
        val pending = RestoreTransactionCoordinator.begin(context, restoredWindowPlan(true))
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        val decision = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals((pending as RestoreOutcome.CommittedReconciliationPending).operationId, decision.id)
        assertEquals(decision.id, decision.restoreOperationId)
        assertEquals("11:12", preferences.getString("schedule_start", null))
        assertEquals("13:14", preferences.getString("schedule_end", null))
        assertFalse(decision.priorOwnersRevoked)

        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = null
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }
        val recovered = RestoreTransactionCoordinator.recover(context)

        assertTrue(recovered is RestoreOutcome.Completed)
        val completed = assertRestoredImage(true, "11:12", "13:14", old)
        assertEquals(decision.id, completed.id)
        assertEquals(decision.startAt, completed.startAt)
        assertEquals(decision.endAt, completed.endAt)
        assertEquals(listOf(decision.startAt, decision.endAt), published)
    }

    @Test
    fun resetRecoveryRetainsAcceptedStartBeforePublishingMissingEnd() = runBlocking {
        val old = seedWindow(true)
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }
        AlarmScheduler.schedulerTransitionStepForTesting = { step ->
            if (step == AlarmScheduler.TRANSITION_STEP_AFTER_START_PUBLICATION) {
                error("interrupt Restore between exact start and end owners")
            }
        }
        val pending = RestoreTransactionCoordinator.begin(context, restoredWindowPlan(true))
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        val decision = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertTrue(decision.startPublished)
        assertFalse(decision.endPublished)
        val firstStart = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.SCHEDULE_START, WorkManagerHandoffRecovery.RESTORED_WINDOW_START,
        ))

        AlarmScheduler.schedulerTransitionStepForTesting = null
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        val completed = assertRestoredImage(true, "11:12", "13:14", old)
        val retainedStart = requireNotNull(database.workManagerHandoffCarrierDao.get(firstStart.handoffId))
        assertEquals(firstStart.requestId, retainedStart.requestId)
        assertEquals(firstStart.generationId, retainedStart.generationId)
        assertEquals(decision.id, completed.id)
        assertEquals(listOf(decision.startAt, decision.endAt), published)
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertEquals(listOf(decision.startAt, decision.endAt), published)
    }

    @Test
    fun restoredWindowFallbackFailureRetainsOwnerUntilRealWorkManagerAcceptance() = runBlocking {
        seedWindow(true)
        AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ -> error("exact alarm unavailable") }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ -> error("fallback enqueue unavailable") }
        val pending = RestoreTransactionCoordinator.begin(context, restoredWindowPlan(true))
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        val decision = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        val debt = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.SCHEDULE_START, WorkManagerHandoffRecovery.RESTORED_WINDOW_START,
        ))
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, debt.state)
        assertFalse(decision.startPublished)

        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        val completed = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals(decision.id, completed.id)
        assertEquals(decision.startAt, completed.startAt)
        assertEquals(decision.endAt, completed.endAt)
        for (boundary in listOf(WorkManagerHandoffRecovery.RESTORED_WINDOW_START,
            WorkManagerHandoffRecovery.RESTORED_WINDOW_END)) {
            val kind = if (boundary == WorkManagerHandoffRecovery.RESTORED_WINDOW_START)
                WorkManagerHandoffCarrier.SCHEDULE_START else WorkManagerHandoffCarrier.SCHEDULE_END
            val accepted = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(kind, boundary))
            assertEquals(WorkManagerHandoffCarrier.ACCEPTED, accepted.state)
            val work = requireNotNull(workManager.getWorkInfoById(UUID.fromString(accepted.requestId)).get(10, TimeUnit.SECONDS))
            assertEquals(WorkInfo.State.ENQUEUED, work.state)
            if (boundary == WorkManagerHandoffRecovery.RESTORED_WINDOW_START) {
                assertEquals(debt.handoffId, accepted.handoffId)
                assertEquals(debt.requestId, accepted.requestId)
            }
        }
    }

    @Test
    fun realStaleStartAndEndReceiversCannotActAfterRestoredOwnerWins(): Unit = runBlocking {
        val old = seedWindow(true)
        assertTrue(RestoreTransactionCoordinator.begin(context, restoredWindowPlan(true)) is RestoreOutcome.Completed)
        val current = database.workManagerHandoffCarrierDao.getOutstanding().map { it.handoffId to it.requestId }.toSet()
        val beforeWork = workManager.getWorkInfosByTag("download").get(10, TimeUnit.SECONDS).map { it.id to it.state }.toSet()
        for ((receiver, id) in listOf(ScheduleAlarmReceiver::class.java to old[0],
            CancelScheduleAlarmReceiver::class.java to old[1])) {
            val finished = CountDownLatch(1)
            context.sendOrderedBroadcast(
                Intent(context, receiver).putExtra(WorkManagerHandoffRecovery.EXTRA_HANDOFF_ID, id),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) { finished.countDown() }
                },
                null, 0, null, null,
            )
            assertTrue("stale receiver did not finish", finished.await(20, TimeUnit.SECONDS))
        }
        assertEquals(current, database.workManagerHandoffCarrierDao.getOutstanding().map { it.handoffId to it.requestId }.toSet())
        assertEquals(beforeWork, workManager.getWorkInfosByTag("download").get(10, TimeUnit.SECONDS).map { it.id to it.state }.toSet())
        assertRestoredImage(true, "11:12", "13:14", old)
    }

    @Test
    fun resetWithoutSettingsPreservesInterruptedEnableForOrdinaryRecovery() = runBlocking {
        seedWindow(false)
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = {
            error("interrupted enable before any external owner")
        }
        assertTrue(runCatching { AlarmScheduler(context).updateSchedulerEnabled(true) }.isFailure)
        val pending = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())

        val outcome = RestoreTransactionCoordinator.begin(
            context, BackupRestoreParser.fromTyped(RestoreAppDataItem()),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertEquals(pending, SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertTrue(preferences.getBoolean("use_scheduler", false))
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)
        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals(2, database.workManagerHandoffCarrierDao.getOutstanding().size)
        SchedulerSettingsTransitionCoordinator.reconcile(context)
        assertEquals(2, database.workManagerHandoffCarrierDao.getOutstanding().size)
    }

    @Test
    fun resetWithoutSettingsPreservesInterruptedDisableAndItsExactSuccessor() = runBlocking {
        val old = seedWindow(true)
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = {
            error("interrupted disable before old owner revocation")
        }
        assertTrue(runCatching { AlarmScheduler(context).updateSchedulerEnabled(false) }.isFailure)
        val pending = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        val uniqueName = "scheduler-disable-${pending.id}-0"

        val outcome = RestoreTransactionCoordinator.begin(
            context, BackupRestoreParser.fromTyped(RestoreAppDataItem()),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertEquals(pending, SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertFalse(preferences.getBoolean("use_scheduler", true))
        old.forEach { assertNotNull(database.workManagerHandoffCarrierDao.get(it)) }
        SchedulerSettingsTransitionCoordinator.beforeExternalEffectForTesting = null
        SchedulerSettingsTransitionCoordinator.reconcile(context)
        assertNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        old.forEach { assertNull(database.workManagerHandoffCarrierDao.get(it)) }
        val successor = workManager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
        assertEquals(1, successor.size)
        SchedulerSettingsTransitionCoordinator.reconcile(context)
        assertEquals(successor.single().id,
            workManager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS).single().id)
    }

    private suspend fun seedWindow(enabled: Boolean): List<String> {
        preferences.edit().putString("schedule_start", "00:00").putString("schedule_end", "05:00")
            .putBoolean("use_scheduler", enabled).commit()
        if (!enabled) return emptyList()
        AlarmScheduler(context).scheduleSuspending()
        return listOf(WorkManagerHandoffCarrier.SCHEDULE_START to WorkManagerHandoffCarrier.START_BOUNDARY,
            WorkManagerHandoffCarrier.SCHEDULE_END to WorkManagerHandoffCarrier.END_BOUNDARY).map { (kind, boundary) ->
            requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(kind, boundary)).handoffId
        }
    }

    private fun restoredWindowPlan(enabled: Boolean): RestorePlan = settingsPlan(
        BackupSettingsItem("schedule_start", "11:12", "String"),
        BackupSettingsItem("schedule_end", "13:14", "String"),
        BackupSettingsItem("use_scheduler", enabled.toString(), "Boolean"),
    )

    private suspend fun assertRestoredImage(
        enabled: Boolean, start: String, end: String, old: List<String>,
    ): SchedulerSettingsTransitionCoordinator.Transition {
        val transition = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals(SchedulerSettingsTransitionCoordinator.Kind.RESTORE, transition.checkedKind())
        assertEquals("COMPLETE", transition.phase)
        assertTrue(transition.priorOwnersRevoked)
        assertEquals(enabled, preferences.getBoolean("use_scheduler", !enabled))
        assertEquals(start, preferences.getString("schedule_start", null))
        assertEquals(end, preferences.getString("schedule_end", null))
        old.forEach { assertNull(database.workManagerHandoffCarrierDao.get(it)) }
        if (enabled) {
            for ((kind, boundary, at) in listOf(
                Triple(WorkManagerHandoffCarrier.SCHEDULE_START, WorkManagerHandoffRecovery.RESTORED_WINDOW_START, transition.startAt),
                Triple(WorkManagerHandoffCarrier.SCHEDULE_END, WorkManagerHandoffRecovery.RESTORED_WINDOW_END, transition.endAt),
            )) {
                val carrier = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(kind, boundary))
                assertEquals(at, carrier.notBeforeAt)
                assertEquals(carrier.handoffId, carrier.generationId)
                assertEquals(UUID.nameUUIDFromBytes("restored-window|${transition.id}|$boundary".toByteArray(Charsets.UTF_8)).toString(), carrier.handoffId)
            }
        }
        return transition
    }

    private fun settingsPlan(vararg settings: BackupSettingsItem): RestorePlan =
        BackupRestoreParser.fromTyped(
            RestoreAppDataItem(settings = settings.toList()),
        )
}
