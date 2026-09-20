package com.ireum.ytdl.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreOutcome
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
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
    }

    @After
    fun tearDown(): Unit = runBlocking {
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
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

    private fun settingsPlan(vararg settings: BackupSettingsItem): RestorePlan =
        BackupRestoreParser.fromTyped(
            RestoreAppDataItem(settings = settings.toList()),
        )

    private companion object {
        const val START_WORK_NAME = "scheduled_download_start"
    }
}
