package com.ireum.ytdl.work

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreOutcome
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.RestorePhase
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.repository.DownloadRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production-wiring proof for Restore-owned exact-alarm fallback. */
@RunWith(AndroidJUnit4::class)
class F11RestoreAlarmFallbackProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager
    private lateinit var preferences: SharedPreferences
    private var originalAlarmPresent = false
    private var originalAlarm = false

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = DBManager.getInstance(context)
        workManager = WorkManager.getInstance(context)
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalAlarmPresent = preferences.contains("use_alarm_for_scheduling")
        originalAlarm = preferences.getBoolean("use_alarm_for_scheduling", false)
        preferences.edit().putBoolean("use_alarm_for_scheduling", true).commit()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        database.historyDao.nuke()
        database.downloadDao.deleteAll()
        WorkManagerHandoffRecovery.clearForTesting()
        AlarmScheduler.exactAlarmPublicationForTesting = null
    }

    @After
    fun tearDown() = runBlocking {
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        AlarmScheduler.exactAlarmPublicationForTesting = null
        WorkManagerHandoffRecovery.clearForTesting()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        database.downloadDao.deleteAll()
        database.historyDao.nuke()
        val editor = preferences.edit()
        if (originalAlarmPresent) editor.putBoolean("use_alarm_for_scheduling", originalAlarm)
        else editor.remove("use_alarm_for_scheduling")
        check(editor.commit())
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun exactAlarmFailureAcceptsDelayedOwnerAndReplayKeepsOneCurrentWorkRequest() = runBlocking {
        val firstFailure = AtomicBoolean(true)
        AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ ->
            if (firstFailure.compareAndSet(true, false)) {
                error("F11 one-shot exact-alarm failure")
            }
            error("exact alarm was republished instead of reusing accepted fallback")
        }
        var interrupt = true
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = {
            if (interrupt) {
                interrupt = false
                error("F11 crash after accepted alarm fallback")
            }
        }

        val startAt = System.currentTimeMillis() + 300_000L
        val pending = RestoreTransactionCoordinator.begin(
            context,
            scheduledPlan(startAt, 801L),
        )
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        val operationId = (pending as RestoreOutcome.CommittedReconciliationPending).operationId
        assertEquals(
            RestorePhase.RECONCILING.name,
            RestoreOperationStore.load(context)?.journal?.phase,
        )
        assertTrue(com.ireum.ytdl.database.RestoreGate.isRestoreInProgress(context))

        val beforeReplay = requireNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.SCHEDULE_START,
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.START_BOUNDARY,
            )
        )
        assertEquals(com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.ACCEPTED, beforeReplay.state)
        assertEquals("scheduled_download_start", beforeReplay.uniqueWorkName)
        val firstWork = awaitOneCurrentWork(beforeReplay.uniqueWorkName)
        assertEquals(beforeReplay.requestId, firstWork.id.toString())
        assertTrue(firstWork.state == WorkInfo.State.ENQUEUED || firstWork.state == WorkInfo.State.BLOCKED)

        // Recreate process-local handoff state. The durable accepted carrier
        // and WorkManager request are the only replay inputs.
        WorkManagerHandoffRecovery.clearForTesting()
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        val recovered = RestoreTransactionCoordinator.recover(context)
        assertTrue(recovered is RestoreOutcome.Completed)
        assertFalse(com.ireum.ytdl.database.RestoreGate.isRestoreInProgress(context))
        val afterReplay = requireNotNull(database.workManagerHandoffCarrierDao.get(beforeReplay.handoffId))
        assertEquals(beforeReplay.requestId, afterReplay.requestId)
        val current = currentUnfinished(afterReplay.uniqueWorkName)
        assertEquals(1, current.size)
        assertEquals(beforeReplay.requestId, current.single().id.toString())
        assertTrue(operationId.isNotBlank())

        // Once Restore ownership retires, ordinary handoff reconciliation can
        // retire the carrier while the accepted WorkManager owner remains.
        WorkManagerHandoffRecovery.reconcile(context)
        assertTrue(database.workManagerHandoffCarrierDao.get(beforeReplay.handoffId) == null)
        assertEquals(1, currentUnfinished("scheduled_download_start").size)
    }

    @Test
    fun fallbackEnqueueFailureLeavesReconciliationDebtUntilReplayAcceptsSuccessor() = runBlocking {
        val firstFailure = AtomicBoolean(true)
        AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ ->
            if (firstFailure.compareAndSet(true, false)) {
                error("F11 one-shot exact-alarm failure")
            }
            error("exact alarm was retried instead of durable fallback")
        }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ ->
            error("F11 fallback WorkManager enqueue unavailable")
        }

        val startAt = System.currentTimeMillis() + 300_000L
        val pending = RestoreTransactionCoordinator.begin(
            context,
            scheduledPlan(startAt, 802L),
        )
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        assertTrue(com.ireum.ytdl.database.RestoreGate.isRestoreInProgress(context))
        assertEquals(
            RestorePhase.RECONCILING.name,
            RestoreOperationStore.load(context)?.journal?.phase,
        )
        val debt = requireNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.SCHEDULE_START,
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.START_BOUNDARY,
            )
        )
        assertEquals(com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.PENDING_ENQUEUE, debt.state)
        assertTrue(debt.attempt > 0)
        assertTrue(currentUnfinished("scheduled_download_start").isEmpty())

        // The failed predecessor retry owner is process-local. Recreate the
        // process state, then let the same durable carrier accept WorkManager.
        WorkManagerHandoffRecovery.clearForTesting()
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        val recovered = RestoreTransactionCoordinator.recover(context)
        assertTrue(recovered is RestoreOutcome.Completed)
        assertFalse(com.ireum.ytdl.database.RestoreGate.isRestoreInProgress(context))
        val accepted = requireNotNull(database.workManagerHandoffCarrierDao.get(debt.handoffId))
        assertEquals(debt.requestId, accepted.requestId)
        assertEquals(com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.ACCEPTED, accepted.state)
        val work = awaitOneCurrentWork(accepted.uniqueWorkName)
        assertEquals(accepted.requestId, work.id.toString())
        assertEquals(1, currentUnfinished(accepted.uniqueWorkName).size)
    }

    private suspend fun awaitOneCurrentWork(name: String): WorkInfo {
        return withTimeout(20_000L) {
            while (true) {
                val current = currentUnfinished(name)
                if (current.size == 1) return@withTimeout current.single()
                delay(25L)
            }
            error("unreachable")
        }
    }

    private fun currentUnfinished(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name)
            .get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }

    private fun scheduledPlan(startAt: Long, index: Long): RestorePlan =
        BackupRestoreParser.fromTyped(
            RestoreAppDataItem(
                scheduled = listOf(
                    DownloadItem(
                        id = 0L,
                        url = "https://example.com/f11-scheduled-$index",
                        title = "F11 scheduled $index",
                        author = "F11",
                        thumb = "",
                        duration = "1:00",
                        type = com.ireum.ytdl.database.enums.DownloadType.video,
                        format = Format(container = "mp4"),
                        container = "mp4",
                        downloadSections = "",
                        allFormats = mutableListOf(),
                        downloadPath = context.filesDir.absolutePath,
                        website = "F11",
                        downloadSize = "",
                        playlistTitle = "",
                        audioPreferences = AudioPreferences(),
                        videoPreferences = com.ireum.ytdl.database.models.VideoPreferences(),
                        extraCommands = "",
                        customFileNameTemplate = "",
                        SaveThumb = false,
                        status = DownloadRepository.Status.Scheduled.name,
                        downloadStartTime = startAt,
                        logID = null,
                        operationId = "f11-scheduled-$index",
                    ),
                ),
            ),
        )
}