package com.ireum.ytdl.work

import android.app.Application
import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.ireum.ytdl.R
import org.json.JSONArray
import org.json.JSONObject
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.DownloadClaimTestHooks
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScheduledDownloadWindowProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        SchedulerSettingsTransitionCoordinator.clearForTesting(context)
        preferences.edit().remove("schedule_start").remove("schedule_end")
            .remove("use_scheduler").commit()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ -> }
        DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = null
    }

    @After
    fun tearDown(): Unit = runBlocking {
        AlarmScheduler.exactAlarmPublicationForTesting = null
        AlarmScheduler.schedulerTransitionStepForTesting = null
        DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = null
        SchedulerSettingsTransitionCoordinator.clearForTesting(context)
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        preferences.edit().remove("schedule_start").remove("schedule_end")
            .remove("use_scheduler").commit()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun realSchedulerReadsPersistedOvernightWindowAcrossMidnight() {
        preferences.edit().putString("schedule_start", "22:00")
            .putString("schedule_end", "05:00").commit()
        var now = clock(21, 59)
        val scheduler = AlarmScheduler(context) { now.clone() as Calendar }
        assertFalse(scheduler.isDuringTheScheduledTime())
        for ((hour, minute) in listOf(22 to 0, 23 to 59, 0 to 0, 2 to 17, 5 to 0)) {
            now = clock(hour, minute, 59, 999)
            assertTrue("$hour:$minute", scheduler.isDuringTheScheduledTime())
        }
        now = clock(5, 1)
        assertFalse(scheduler.isDuringTheScheduledTime())
    }

    @Test
    fun realSchedulerUsesDefaultsAndRereadsEqualBoundaryConfiguration() {
        var now = clock(0, 0)
        val scheduler = AlarmScheduler(context) { now.clone() as Calendar }
        assertTrue(scheduler.isDuringTheScheduledTime())
        now = clock(5, 0, 59, 999)
        assertTrue(scheduler.isDuringTheScheduledTime())
        now = clock(5, 1)
        assertFalse(scheduler.isDuringTheScheduledTime())
        preferences.edit().putString("schedule_start", "09:15")
            .putString("schedule_end", "09:15").commit()
        now = clock(9, 14)
        assertFalse(scheduler.isDuringTheScheduledTime())
        now = clock(9, 15, 59, 999)
        assertTrue(scheduler.isDuringTheScheduledTime())
        now = clock(9, 16)
        assertFalse(scheduler.isDuringTheScheduledTime())
    }

    @Test
    fun realSchedulePublishesNormalizedStartAndEndWithMatchingDurableCarriers() = runBlocking {
        preferences.edit().putString("schedule_start", "22:00")
            .putString("schedule_end", "05:00").putBoolean("use_scheduler", true).commit()
        val now = clock(23, 59, 43, 987)
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }

        AlarmScheduler(context) { now.clone() as Calendar }.scheduleSuspending()

        val start = clock(22, 0).apply { add(Calendar.DATE, 1) }.timeInMillis
        val end = clock(5, 1).apply { add(Calendar.DATE, 1) }.timeInMillis
        assertEquals(listOf(start, end), published)
        val startCarrier = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.SCHEDULE_START, WorkManagerHandoffCarrier.START_BOUNDARY,
        ))
        val endCarrier = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.SCHEDULE_END, WorkManagerHandoffCarrier.END_BOUNDARY,
        ))
        assertEquals(start, startCarrier.notBeforeAt)
        assertEquals(end, endCarrier.notBeforeAt)
        assertEquals(0L, start % 60_000L)
        assertEquals(0L, end % 60_000L)
        assertEquals(clock(23, 59, 43, 987).timeInMillis, now.timeInMillis)
    }

    @Test
    fun schedulingInEndMinuteKeepsTheMatchingEndOwnerInTheFuture() = runBlocking {
        for ((start, end) in listOf("22:00" to "05:00", "09:15" to "09:15",
            "00:00" to "23:59", "23:59" to "00:00")) {
            preferences.edit().putString("schedule_start", start)
                .putString("schedule_end", end).putBoolean("use_scheduler", true).commit()
            val parts = end.split(":").map(String::toInt)
            for ((second, millis) in listOf(0 to 0, 0 to 1, 59 to 999)) {
                var now = clock(parts[0], parts[1], second, millis)
                val scheduler = AlarmScheduler(context) { now.clone() as Calendar }
                val published = mutableListOf<Long>()
                AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }
                assertTrue(scheduler.isDuringTheScheduledTime())
                scheduler.scheduleSuspending()
                val carrier = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.SCHEDULE_END, WorkManagerHandoffCarrier.END_BOUNDARY,
                ))
                val expected = clock(parts[0], parts[1]).apply { add(Calendar.MINUTE, 1) }
                assertEquals(expected.timeInMillis, published.last())
                assertEquals(expected.timeInMillis, carrier.notBeforeAt)
                assertTrue(carrier.notBeforeAt > now.timeInMillis)
                now = expected
                if (start != "00:00" || end != "23:59") assertFalse(scheduler.isDuringTheScheduledTime())
            }
        }
    }

    @Test
    fun realQueueClaimsInInclusiveEndMinuteAndRealEndWorkerStopsAtFollowingMinute() = runBlocking(Dispatchers.IO) {
        val realDatabase = DBManager.getInstance(context)
        val claimed = AtomicReference<DownloadItem?>()
        val exactEnd = AtomicReference<WorkManagerHandoffCarrier?>()
        val blockedAtStopGate = AtomicBoolean(false)
        val runningRequestIds = AtomicReference<List<UUID>>(emptyList())
        val scenarioId = UUID.randomUUID().toString()
        val atStopGate = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val attemptCleaned = CountDownLatch(1)
        val url = "https://example.com/scheduler-end-minute-${UUID.randomUUID()}"
        val oldMeteredPresent = preferences.contains("metered_networks")
        val oldMetered = preferences.getBoolean("metered_networks", true)
        val oldLimitPresent = preferences.contains("concurrent_downloads")
        val oldLimit = preferences.getInt("concurrent_downloads", 1)
        val viewModel = withContext(Dispatchers.Main) {
            DownloadViewModel(context as Application, realDatabase, true)
        }
        suspend fun recordBoundary(stage: String) {
            val expected = exactEnd.get()
            val execution = claimed.get()
            val currentCarrier = expected?.let { realDatabase.workManagerHandoffCarrierDao.get(it.handoffId) }
            val outstandingEnd = realDatabase.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.SCHEDULE_END, WorkManagerHandoffCarrier.END_BOUNDARY,
            )
            val row = execution?.let { realDatabase.downloadDao.getNullableDownloadById(it.id) }
            val endWork = expected?.let {
                workManager.getWorkInfoById(UUID.fromString(it.requestId)).get(10, TimeUnit.SECONDS)
            }
            val restore = RestoreOperationStore.load(context)
            val superseded = expected != null && outstandingEnd != null &&
                (outstandingEnd.handoffId != expected.handoffId ||
                    outstandingEnd.generationId != expected.generationId ||
                    outstandingEnd.requestId != expected.requestId)
            val prefix = "scenario=$scenarioId stage=$stage"
            Log.i("SchedulerOwnerDiagnostic", "$prefix expectedEnd=$expected")
            Log.i("SchedulerOwnerDiagnostic", "$prefix exactCarrier=$currentCarrier outstandingEnd=$outstandingEnd superseded=$superseded")
            Log.i("SchedulerOwnerDiagnostic", "$prefix endWorkId=" + endWork?.id +
                " endWorkState=" + endWork?.state + " finished=" + endWork?.state?.isFinished)
            Log.i("SchedulerOwnerDiagnostic", "$prefix downloadId=" + execution?.id +
                " expectedExecution=" + execution?.executionId + " rowStatus=" + row?.status +
                " rowExecution=" + row?.executionId +
                " executionOwner=" + execution?.let { DownloadWorkerExecutionOwners.ownerOf(it.id) } +
                " processOwner=" + execution?.let { DownloadWorkerProcessOwners.ownerOf(it.id) })
            Log.i("SchedulerOwnerDiagnostic", "$prefix restoreOperation=" + restore?.journal?.operationId +
                " restorePhase=" + restore?.journal?.phase + " restoreDeferred=" + RestoreGate.isRestoreInProgress(context) +
                " downloadWork=" + runningRequestIds.get().map { id ->
                    id to workManager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)?.state
                })
        }
        try {
            WorkManagerHandoffRecovery.databaseForTesting = realDatabase
            DownloadWorkerEffectTestHooks.dbManagerForTesting = realDatabase
            AlarmScheduler(context).cancel()
            realDatabase.downloadDao.deleteAll()
            preferences.edit().putBoolean("metered_networks", true).putInt("concurrent_downloads", 1).commit()
            DownloadClaimTestHooks.afterExecutionOwnerPublicationForTesting = { item ->
                if (item.url == url) {
                    val first = claimed.compareAndSet(null, item.copy())
                    Log.i("SchedulerOwnerDiagnostic", "scenario=$scenarioId stage=claim downloadId=" +
                        item.id + " executionId=" + item.executionId + " first=$first")
                }
            }
            DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = { id, executionId ->
                val execution = claimed.get()
                if (execution != null && id == execution.id && executionId == execution.executionId) {
                    Log.i("SchedulerOwnerDiagnostic", "scenario=$scenarioId stage=attempt_cleanup_observed downloadId=$id executionId=$executionId" +
                        " executionOwner=" + DownloadWorkerExecutionOwners.ownerOf(id) +
                        " processOwner=" + DownloadWorkerProcessOwners.ownerOf(id))
                    attemptCleaned.countDown()
                }
            }
            DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = { id, boundary ->
                if (id == claimed.get()?.id && boundary == "stop_gate" &&
                    blockedAtStopGate.compareAndSet(false, true)) {
                    atStopGate.countDown()
                    check(releaseWorker.await(120, TimeUnit.SECONDS)) { "scheduler timeline worker not released" }
                }
            }
            // Use the real device clock and an entire fresh minute. No clock
            // or admission helper replaces either production consumer.
            delay(60_000L - System.currentTimeMillis() % 60_000L + 500L)
            val now = Calendar.getInstance()
            val minute = String.format(Locale.ROOT, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            preferences.edit().putString("schedule_start", minute).putString("schedule_end", minute)
                .putBoolean("use_scheduler", true).commit()
            AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ -> error("exercise durable real WorkManager fallback") }
            AlarmScheduler(context).scheduleSuspending()
            val endOwner = requireNotNull(realDatabase.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.SCHEDULE_END, WorkManagerHandoffCarrier.END_BOUNDARY,
            ))
            exactEnd.set(endOwner)
            recordBoundary("end_published")
            val followingMinute = (now.clone() as Calendar).apply {
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, 1)
            }.timeInMillis
            assertEquals(followingMinute, endOwner.notBeforeAt)
            // Ordinary fallback intentionally retains its durable carrier
            // until notBeforeAt, then enqueues the real END worker.
            assertEquals(WorkManagerHandoffRecovery.OutcomeKind.RETRYING,
                WorkManagerHandoffRecovery.enqueueAndAwait(context, endOwner.handoffId).await().kind)
            assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE,
                realDatabase.workManagerHandoffCarrierDao.get(endOwner.handoffId)?.state)

            val queued = timelineDownload(url)
            val queuedResult = viewModel.queueDownloads(listOf(queued), ignoreDuplicates = true)
            assertTrue(queuedResult.message, queuedResult.succeeded)
            assertTrue("real DownloadWorker did not reach its production stop gate",
                withContext(Dispatchers.IO) { atStopGate.await(20, TimeUnit.SECONDS) })
            val execution = requireNotNull(claimed.get())
            val live = requireNotNull(realDatabase.downloadDao.getNullableDownloadById(execution.id))
            assertEquals(DownloadRepository.Status.Active.name, live.status)
            assertTrue(live.executionId.isNotBlank())
            assertEquals(execution.executionId, live.executionId)
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(live.id, live.executionId))
            assertTrue(AlarmScheduler(context).isDuringTheScheduledTime())
            assertTrue(System.currentTimeMillis() < endOwner.notBeforeAt)
            val running = withContext(Dispatchers.IO) {
                workManager.getWorkInfosByTag("download").get(10, TimeUnit.SECONDS)
                    .filter { it.state == WorkInfo.State.RUNNING }
            }
            assertTrue("fresh execution has no actual running WorkManager carrier", running.isNotEmpty())
            runningRequestIds.set(running.map { it.id })
            recordBoundary("active_claim_before_end")
            assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE,
                realDatabase.workManagerHandoffCarrierDao.get(endOwner.handoffId)?.state)
            assertNull(withContext(Dispatchers.IO) {
                workManager.getWorkInfoById(UUID.fromString(endOwner.requestId)).get(10, TimeUnit.SECONDS)
            })

            withTimeout(90_000L) {
                while (withContext(Dispatchers.IO) {
                    running.any { workManager.getWorkInfoById(it.id).get(10, TimeUnit.SECONDS)?.state != WorkInfo.State.CANCELLED }
                }) delay(50L)
            }
            assertTrue(System.currentTimeMillis() >= followingMinute)
            assertFalse(AlarmScheduler(context).isDuringTheScheduledTime())
            releaseWorker.countDown()
            withTimeout(30_000L) {
                while (workManager.getWorkInfoById(UUID.fromString(endOwner.requestId))
                        .get(10, TimeUnit.SECONDS)?.state?.isFinished != true) delay(25L)
            }
            recordBoundary("exact_end_finished")
            assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(UUID.fromString(endOwner.requestId))
                .get(10, TimeUnit.SECONDS)?.state)
            withTimeout(30_000L) { WorkManagerHandoffRecovery.reconcile(context) }
            recordBoundary("reconcile_completed")
            withTimeout(10_000L) {
                while (realDatabase.workManagerHandoffCarrierDao.get(endOwner.handoffId) != null) delay(25L)
            }
            recordBoundary("exact_carrier_absent")
            assertNull(realDatabase.workManagerHandoffCarrierDao.get(endOwner.handoffId))
            val stopped = requireNotNull(realDatabase.downloadDao.getNullableDownloadById(live.id))
            assertEquals(DownloadRepository.Status.Queued.name, stopped.status)
            assertEquals("", stopped.executionId)
            // Scheduler retirement uses exact WorkInfo completion plus real
            // reconciliation. Keep Download attempt ownership a separate assertion.
            assertTrue("exact cancelled attempt did not finish production cleanup",
                attemptCleaned.await(30, TimeUnit.SECONDS))
            recordBoundary("before_download_owner_assertions")
            assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(live.id, live.executionId))
            assertFalse(DownloadWorkerProcessOwners.isOwnedBy(live.id, live.executionId))

            // The same real queue entrypoint outside the inclusive minute
            // persists responsibility and publishes tomorrow's window.
            val afterEnd = timelineDownload("$url-after-end")
            val afterEndCallerBefore = afterEnd.copy()
            fun queueItemEvidence(item: DownloadItem?): JSONObject = if (item == null) {
                JSONObject().put("present", false)
            } else {
                JSONObject().put("present", true).put("id", item.id)
                    .put("status", item.status).put("executionId", item.executionId)
                    .put("operationId", item.operationId).put("retryAttempt", item.retryAttempt)
                    .put("lastIssueCode", item.lastIssueCode).put("lastIssueStage", item.lastIssueStage)
                    .put("downloadStartTime", item.downloadStartTime)
            }
            suspend fun recordQueueAdmission(stage: String, caller: DownloadItem) {
                val row = realDatabase.downloadDao.getNullableDownloadById(caller.id)
                val originalIdRow = realDatabase.downloadDao.getNullableDownloadById(afterEndCallerBefore.id)
                val scheduler = AlarmScheduler(context)
                val pending = DownloadExecutionRecovery.hasPendingRecovery(context, caller.id)
                Log.i("SchedulerQueueDiagnostic", JSONObject().put("scenario", scenarioId)
                    .put("stage", stage).put("callerOrigin", "FRESH_NEW_CREATED_AFTER_END_NOT_REUSED")
                    .put("caller", queueItemEvidence(caller)).put("roomRow", queueItemEvidence(row))
                    .put("originalCallerId", afterEndCallerBefore.id)
                    .put("originalIdRoomRow", queueItemEvidence(originalIdRow)).toString())
                Log.i("SchedulerQueueDiagnostic", JSONObject().put("scenario", scenarioId)
                    .put("stage", stage).put("hasPendingRecovery", pending)
                    .put("pendingDownloadIds", JSONArray(DownloadExecutionRecovery.pendingDownloadIds(context).toList()))
                    .put("recoveryDisposition", DownloadExecutionRecovery.pendingDispositionForExecution(context, caller.id)?.name ?: JSONObject.NULL)
                    .put("recoveryPhase", DownloadExecutionRecovery.pendingPhaseForTesting(context, caller.id)?.name ?: JSONObject.NULL)
                    .put("executionOwner", DownloadWorkerExecutionOwners.ownerOf(caller.id) ?: JSONObject.NULL)
                    .put("processOwner", DownloadWorkerProcessOwners.ownerOf(caller.id) ?: JSONObject.NULL).toString())
                Log.i("SchedulerQueueDiagnostic", JSONObject().put("scenario", scenarioId)
                    .put("stage", stage).put("use_scheduler", preferences.getBoolean("use_scheduler", false))
                    .put("schedule_start", preferences.getString("schedule_start", null) ?: JSONObject.NULL)
                    .put("schedule_end", preferences.getString("schedule_end", null) ?: JSONObject.NULL)
                    .put("isDuringTheScheduledTime", scheduler.isDuringTheScheduledTime())
                    .put("canSchedule", scheduler.canSchedule()).put("observedAt", System.currentTimeMillis()).toString())
                val downloadWork = workManager.getWorkInfosByTag("download").get(10, TimeUnit.SECONDS)
                Log.i("SchedulerQueueDiagnostic", JSONObject().put("scenario", scenarioId)
                    .put("stage", stage).put("downloadWork", JSONArray(downloadWork.map {
                        JSONObject().put("id", it.id.toString()).put("state", it.state.name)
                            .put("runAttemptCount", it.runAttemptCount).put("tags", JSONArray(it.tags.toList()))
                    })).toString())
            }
            recordQueueAdmission("BEFORE_QUEUE", afterEndCallerBefore)
            val afterEndResult = try {
                viewModel.queueDownloads(listOf(afterEnd), ignoreDuplicates = true)
            } catch (error: Throwable) {
                Log.e("SchedulerQueueDiagnostic", "scenario=$scenarioId stage=QUEUE_EXCEPTION", error)
                recordQueueAdmission("AFTER_QUEUE_EXCEPTION", afterEnd)
                throw error
            }
            Log.i("SchedulerQueueDiagnostic", JSONObject().put("scenario", scenarioId)
                .put("stage", "QUEUE_RESULT").put("succeeded", afterEndResult.succeeded)
                .put("message", afterEndResult.message).put("messageBlank", afterEndResult.message.isBlank())
                .put("messageLength", afterEndResult.message.length)
                .put("matchesAlarmPermissionRefusal", afterEndResult.message == context.getString(R.string.enable_alarm_permission))
                .put("duplicateDownloadIDs", JSONArray(afterEndResult.duplicateDownloadIDs)).toString())
            recordQueueAdmission("AFTER_QUEUE", afterEnd)
            assertTrue(afterEndResult.message, afterEndResult.succeeded)
            val waiting = requireNotNull(realDatabase.downloadDao.getNullableDownloadById(afterEnd.id))
            assertEquals(DownloadRepository.Status.Queued.name, waiting.status)
            assertEquals("", waiting.executionId)
            assertTrue(requireNotNull(realDatabase.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.SCHEDULE_START, WorkManagerHandoffCarrier.START_BOUNDARY,
            )).notBeforeAt > System.currentTimeMillis())
        } finally {
            withContext(NonCancellable) {
                runCatching { withTimeout(15_000L) { recordBoundary("before_teardown") } }
                    .onFailure { Log.e("SchedulerOwnerDiagnostic", "scenario=$scenarioId diagnostic capture failed", it) }
            }
            releaseWorker.countDown()
            DownloadClaimTestHooks.afterExecutionOwnerPublicationForTesting = null
            DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = null
            DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = null
            withContext(Dispatchers.IO) { workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS) }
            withContext(Dispatchers.Main) { viewModel.clearForTesting() }
            DownloadWorkerEffectTestHooks.dbManagerForTesting = null
            AlarmScheduler(context).cancel()
            WorkManagerHandoffRecovery.databaseForTesting = database
            realDatabase.downloadDao.deleteAll()
            val editor = preferences.edit()
            if (oldMeteredPresent) editor.putBoolean("metered_networks", oldMetered) else editor.remove("metered_networks")
            if (oldLimitPresent) editor.putInt("concurrent_downloads", oldLimit) else editor.remove("concurrent_downloads")
            check(editor.commit())
        }
    }

    private fun timelineDownload(url: String): DownloadItem = DownloadItem(
        id = 0L, url = url, title = "scheduler timeline", author = "test", thumb = "", duration = "01:00",
        type = DownloadType.video, format = Format(container = "mp4"), container = "mp4", downloadSections = "",
        allFormats = mutableListOf(), downloadPath = context.filesDir.absolutePath, website = "example",
        downloadSize = "", playlistTitle = "", audioPreferences = AudioPreferences(), videoPreferences = VideoPreferences(),
        extraCommands = "", customFileNameTemplate = "", SaveThumb = false,
        status = DownloadRepository.Status.Queued.name, downloadStartTime = 0L, logID = null,
    )

    private fun clock(hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 6, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }
}
