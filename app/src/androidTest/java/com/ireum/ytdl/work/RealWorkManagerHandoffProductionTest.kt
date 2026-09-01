package com.ireum.ytdl.work

import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.receiver.CancelScheduleAlarmReceiver
import com.ireum.ytdl.receiver.ScheduleAlarmReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Exercises the real AndroidX WorkManager carrier and receiver handoff.
 * Deterministic Operation faults remain covered by WorkManagerHandoffProductionTest.
 */
@RunWith(AndroidJUnit4::class)
class RealWorkManagerHandoffProductionTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        cancelUniqueWork(HardSubScanWorker.UNIQUE_WORK_NAME)
        cancelUniqueWork(START_WORK_NAME)
        cancelUniqueWork(END_WORK_NAME)

        WorkManagerHandoffRecovery.clearForTesting()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workManagerForTesting = workManager
    }

    @After
    fun tearDown() = runBlocking {
        cancelUniqueWork(HardSubScanWorker.UNIQUE_WORK_NAME)
        cancelUniqueWork(START_WORK_NAME)
        cancelUniqueWork(END_WORK_NAME)
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun hardSubScanUsesRealWorkManagerAndConsumesExactCarrier() = runBlocking {
        val handoffId = WorkManagerHandoffRecovery.prepareHardSub(context)
        val carrier = checkNotNull(database.workManagerHandoffCarrierDao.get(handoffId))

        val outcome = WorkManagerHandoffRecovery.enqueueAndAwait(context, handoffId).await()

        assertTrue(outcome.accepted)
        val workInfo = awaitWorkInfo(carrier.requestId)
        assertEquals(UUID.fromString(carrier.requestId), workInfo.id)
        assertTrue(workInfo.tags.contains(HardSubScanWorker.TAG))
        assertNull(database.workManagerHandoffCarrierDao.get(handoffId))
    }

    @Test
    fun schedulerReceiversUseRealWorkManagerForIndependentStartAndEndCarriers() = runBlocking {
        val startId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            0L,
        )
        val startCarrier = checkNotNull(database.workManagerHandoffCarrierDao.get(startId))
        context.sendBroadcast(
            Intent(context, ScheduleAlarmReceiver::class.java)
                .putExtra(WorkManagerHandoffRecovery.EXTRA_HANDOFF_ID, startId)
        )

        val startWork = awaitWorkInfo(startCarrier.requestId)
        assertTrue(startWork.tags.contains("scheduledDownload"))
        awaitCarrierGone(startId)

        val endId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.END_BOUNDARY,
            0L,
        )
        val endCarrier = checkNotNull(database.workManagerHandoffCarrierDao.get(endId))
        context.sendBroadcast(
            Intent(context, CancelScheduleAlarmReceiver::class.java)
                .putExtra(WorkManagerHandoffRecovery.EXTRA_HANDOFF_ID, endId)
        )

        val endWork = awaitWorkInfo(endCarrier.requestId)
        assertTrue(endWork.tags.contains("cancelScheduledDownload"))
        awaitCarrierGone(endId)
    }

    @Test
    fun startupReconciliationReconstructsRealHardSubCarrierByExactRequestId() = runBlocking {
        val handoffId = WorkManagerHandoffRecovery.prepareHardSub(context)
        val carrier = checkNotNull(database.workManagerHandoffCarrierDao.get(handoffId))

        // Simulate process-local coordinator loss while retaining the Room carrier.
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workManagerForTesting = workManager
        WorkManagerHandoffRecovery.reconcile(context)

        val workInfo = awaitWorkInfo(carrier.requestId)
        assertEquals(UUID.fromString(carrier.requestId), workInfo.id)
        assertTrue(workInfo.tags.contains(HardSubScanWorker.TAG))
        awaitCarrierGone(handoffId)
    }

    private suspend fun awaitWorkInfo(requestId: String): WorkInfo {
        val id = UUID.fromString(requestId)
        var info: WorkInfo? = null
        withTimeout(10_000L) {
            while (info == null) {
                info = workManager.getWorkInfoById(id).get(5, TimeUnit.SECONDS)
                if (info == null) delay(25L)
            }
        }
        return checkNotNull(info)
    }

    private suspend fun awaitCarrierGone(handoffId: String) = withTimeout(10_000L) {
        while (database.workManagerHandoffCarrierDao.get(handoffId) != null) {
            delay(25L)
        }
    }

    private fun cancelUniqueWork(name: String) {
        workManager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
    }

    companion object {
        private const val START_WORK_NAME = "scheduled_download_start"
        private const val END_WORK_NAME = "scheduled_download_end"
    }
}
