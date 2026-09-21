package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreOutcome
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-wiring proof for the remaining F11 scheduler external-authority
 * boundary. Carrier-row tests remain in the existing handoff suite; these
 * tests observe the actual WorkManager effect around Restore ownership.
 */
@RunWith(AndroidJUnit4::class)
class F11SchedulerExternalAuthorityProductionWiringTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var database: DBManager

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
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
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        AlarmScheduler.exactAlarmPublicationForTesting = null
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun ordinarySchedulerCancelAwaitsExternalCancellationBeforeRetiringCarrier() = runBlocking {
        val request = OneTimeWorkRequestBuilder<com.ireum.ytdl.work.DownloadWorker>()
            .setInitialDelay(1L, TimeUnit.HOURS)
            .addTag("download")
            .build()
        workManager.enqueueUniqueWork(
            START_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        ).result.get(20, TimeUnit.SECONDS)
        assertEquals(
            androidx.work.WorkInfo.State.ENQUEUED,
            requireNotNull(workManager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)).state,
        )
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            System.currentTimeMillis() + 60_000L,
        )

        AlarmScheduler(context).cancel()

        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            requireNotNull(workManager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)).state,
        )
        assertNull(database.workManagerHandoffCarrierDao.get(handoffId))
    }

    @Test
    fun restoreWinsBeforeOrdinarySchedulerCancelSideEffect() = runBlocking {
        val request = OneTimeWorkRequestBuilder<com.ireum.ytdl.work.DownloadWorker>()
            .setInitialDelay(1L, TimeUnit.HOURS)
            .addTag("download")
            .build()
        workManager.enqueueUniqueWork(
            START_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        ).result.get(20, TimeUnit.SECONDS)
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            System.currentTimeMillis() + 60_000L,
        )
        var cancelReturned = false
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            AlarmScheduler(context).cancel()
            cancelReturned = true
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertTrue(cancelReturned)
        assertEquals(
            androidx.work.WorkInfo.State.ENQUEUED,
            requireNotNull(workManager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)).state,
        )
        assertNotNull(database.workManagerHandoffCarrierDao.get(handoffId))
    }

    @Test
    fun restoreWinsBeforeOrdinarySchedulerCarrierPublication() = runBlocking {
        var failure: Throwable? = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            failure = runCatching {
                WorkManagerHandoffRecovery.prepareSchedulerBoundary(
                    context,
                    WorkManagerHandoffCarrier.START_BOUNDARY,
                    System.currentTimeMillis() + 60_000L,
                )
            }.exceptionOrNull()
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(
                BackupSettingsItem("start_destination", "Home", "String"),
            ),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertTrue(failure is IllegalStateException)
        assertNull(
            database.workManagerHandoffCarrierDao
                .getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.SCHEDULE_START,
                    WorkManagerHandoffCarrier.START_BOUNDARY,
                )
        )
    }

    @Test
    fun supersededSchedulerDownloadWorkerCannotBecomeLiveAfterRestore(): Unit = runBlocking {
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            System.currentTimeMillis() + 60_000L,
        )
        val carrier = requireNotNull(database.workManagerHandoffCarrierDao.get(handoffId))
        assertEquals(
            1,
            database.workManagerHandoffCarrierDao.markAccepted(
                handoffId,
                carrier.requestId,
                System.currentTimeMillis(),
            ),
        )
        assertEquals(
            1,
            database.workManagerHandoffCarrierDao.markSuperseded(
                handoffId,
                carrier.requestId,
                System.currentTimeMillis(),
            ),
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(UUID.fromString(carrier.requestId))
            .setInputData(
                Data.Builder()
                    .putString("handoffId", carrier.handoffId)
                    .putString("handoffRequestId", carrier.requestId)
                    .putString(WorkManagerHandoffRecovery.INPUT_GENERATION_ID, carrier.generationId)
                    .putString(WorkManagerHandoffRecovery.INPUT_BOUNDARY, carrier.boundary)
                    .build(),
            )
            .build()
        workManager.enqueue(request).result.get(20, TimeUnit.SECONDS)

        suspend fun awaitFinished(): androidx.work.WorkInfo {
            while (true) {
                val current = workManager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
                if (current != null && current.state.isFinished) return current
                kotlinx.coroutines.delay(25L)
            }
        }
        val info = withTimeout(20_000L) { awaitFinished() }
        assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, requireNotNull(info).state)
        assertEquals(WorkManagerHandoffCarrier.SUPERSEDED, database.workManagerHandoffCarrierDao.get(handoffId)?.state)
    }

    @Test
    fun scheduleStartPreferenceAndAlarmEffectShareOrdinaryAuthority(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString("schedule_start", "00:00").commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        val writer = async(Dispatchers.IO) {
            assertTrue(AlarmScheduler(context).updateScheduleBoundary("schedule_start", "01:11"))
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(context, settingsPlan(BackupSettingsItem("r2_start_marker", "restore", "String")))
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        assertEquals("01:11", preferences.getString("schedule_start", null))
        assertTrue(reset.await() is RestoreOutcome.Completed)
    }

    @Test
    fun scheduleEndRestoreWinsBeforePreferenceAndAlarmEffect(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString("schedule_end", "05:00").commit()
        var changed = true
        var observed: String? = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            changed = AlarmScheduler(context).updateScheduleBoundary("schedule_end", "02:22")
            observed = preferences.getString("schedule_end", null)
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(BackupSettingsItem("r2_end_marker", "restore", "String")),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertFalse(changed)
        assertEquals("05:00", observed)
    }

    @Test
    fun schedulerEnablePreferenceAndEffectsShareOrdinaryAuthority(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean("use_scheduler", false).commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        val writer = async(Dispatchers.IO) {
            assertTrue(AlarmScheduler(context).updateSchedulerEnabled(true))
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(context, settingsPlan(BackupSettingsItem("r2_enable_marker", "restore", "String")))
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        assertEquals(true, preferences.getBoolean("use_scheduler", false))
        assertTrue(reset.await() is RestoreOutcome.Completed)
    }

    @Test
    fun schedulerDisableRestoreWinsBeforePreferenceAndSuccessorEffect(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean("use_scheduler", true).commit()
        var changed = true
        var observed: Boolean? = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            changed = AlarmScheduler(context).updateSchedulerEnabled(false)
            observed = preferences.getBoolean("use_scheduler", false)
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(BackupSettingsItem("r2_disable_marker", "restore", "String")),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertFalse(changed)
        assertEquals(true, observed)
        assertTrue(
            workManager.getWorkInfosByTag("download").get(10, TimeUnit.SECONDS)
                .none { !it.state.isFinished },
        )
    }
    @Test
    fun scheduleEndPreferenceAndAlarmEffectShareOrdinaryAuthority(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString("schedule_end", "05:00").commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        val writer = async(Dispatchers.IO) {
            assertTrue(AlarmScheduler(context).updateScheduleBoundary("schedule_end", "02:22"))
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(context, settingsPlan(BackupSettingsItem("r2_end_ordinary_marker", "restore", "String")))
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        assertEquals("02:22", preferences.getString("schedule_end", null))
        assertTrue(reset.await() is RestoreOutcome.Completed)
    }

    @Test
    fun scheduleStartRestoreWinsBeforePreferenceAndAlarmEffect(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString("schedule_start", "00:00").commit()
        var changed = true
        var observed: String? = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            changed = AlarmScheduler(context).updateScheduleBoundary("schedule_start", "01:11")
            observed = preferences.getString("schedule_start", null)
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(BackupSettingsItem("r2_start_restore_marker", "restore", "String")),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertFalse(changed)
        assertEquals("00:00", observed)
    }

    @Test
    fun schedulerEnableRestoreWinsBeforePreferenceAndEffects(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean("use_scheduler", false).commit()
        var changed = true
        var observed: Boolean? = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            changed = AlarmScheduler(context).updateSchedulerEnabled(true)
            observed = preferences.getBoolean("use_scheduler", false)
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(BackupSettingsItem("r2_enable_restore_marker", "restore", "String")),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertFalse(changed)
        assertEquals(false, observed)
    }

    @Test
    fun schedulerDisablePreferenceAndSuccessorShareOrdinaryAuthority(): Unit = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean("use_scheduler", true).commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        val writer = async(Dispatchers.IO) {
            assertTrue(AlarmScheduler(context).updateSchedulerEnabled(false))
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(context, settingsPlan(BackupSettingsItem("r2_disable_ordinary_marker", "restore", "String")))
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        assertEquals(false, preferences.getBoolean("use_scheduler", true))
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertTrue(
            workManager.getWorkInfosByTag("download").get(10, TimeUnit.SECONDS)
                .none { !it.state.isFinished },
        )
    }
    private fun settingsPlan(vararg settings: BackupSettingsItem): RestorePlan =
        BackupRestoreParser.fromTyped(
            RestoreAppDataItem(settings = settings.toList()),
        )

    private companion object {
        const val START_WORK_NAME = "scheduled_download_start"
    }
}
