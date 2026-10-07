package com.ireum.ytdl.work

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.DownloadClaimTestHooks
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StoppedDownloadExecutionOwnerProductionWiringTest {
    @Test
    fun actualDurableNativeRegistryRetainsProcessOwnerWhenQueuedExecutionRetires() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YtdlpNativeProcessBarrier.configure(context)
        val id = System.currentTimeMillis()
        val token = UUID.randomUUID().toString()
        DownloadWorkerExecutionOwners.claim(id, token)
        assertTrue(DownloadWorkerProcessOwners.claim(id, token))
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(id, token),
            state = "RUNNING",
            generationToken = UUID.randomUUID().toString(),
        )
        val original = marker.readBytes()
        try {
            assertTrue(DownloadWorker.hasNativeProcessRegistryEntry(id, token))
            withDownloadWorkerExecutionLock {
                assertTrue(retireStoppedDownloadExecutionOwner(
                    id, token, AbandonedDownloadExecution(id, "", "Queued"), false,
                    DownloadWorker::hasNativeProcessRegistryEntry,
                ))
            }
            assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(id, token))
            assertTrue(DownloadWorkerProcessOwners.isOwnedBy(id, token))
            assertTrue(DownloadWorker.hasNativeProcessRegistryEntry(id, token))
            assertTrue(original.contentEquals(marker.readBytes()))
        } finally {
            marker.delete()
            DownloadWorkerExecutionOwners.release(id, token)
            DownloadWorkerProcessOwners.release(id, token)
        }
    }

    @Test
    fun childRetainsActiveE1ThenRealEndRequeuesBeforeOuterCleanup() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = DBManager.getInstance(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = preferences.all.filterKeys { it in setOf(
            "schedule_start", "schedule_end", "use_scheduler", "metered_networks", "concurrent_downloads",
        ) }
        val workManager = WorkManager.getInstance(context)
        val claimed = AtomicReference<DownloadItem?>()
        val stoppedGate = AtomicBoolean(false)
        val atStopGate = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val retainedByChild = CountDownLatch(1)
        val releaseOuter = CountDownLatch(1)
        val scenario = UUID.randomUUID().toString()
        val url = "https://example.com/exact-owner-race-$scenario"
        val viewModel = withContext(Dispatchers.Main) {
            DownloadViewModel(context as Application, database, true)
        }
        fun record(stage: String) {
            val e1 = claimed.get()
            val row = e1?.let { database.downloadDao.getNullableDownloadById(it.id) }
            Log.i("StoppedOwnerRaceDiagnostic", "scenario=$scenario stage=$stage id=" + e1?.id +
                " expectedE1=" + e1?.executionId + " rowStatus=" + row?.status +
                " rowExecution=" + row?.executionId +
                " executionOwner=" + e1?.let { DownloadWorkerExecutionOwners.ownerOf(it.id) } +
                " processOwner=" + e1?.let { DownloadWorkerProcessOwners.ownerOf(it.id) })
        }
        try {
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            WorkManagerHandoffRecovery.databaseForTesting = database
            DownloadWorkerEffectTestHooks.dbManagerForTesting = database
            AlarmScheduler(context).cancel()
            database.downloadDao.deleteAll()
            assertTrue(preferences.edit().putBoolean("metered_networks", true)
                .putInt("concurrent_downloads", 1).commit())
            DownloadClaimTestHooks.afterExecutionOwnerPublicationForTesting = { item ->
                if (item.url == url) claimed.compareAndSet(null, item.copy())
            }
            DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = { id, boundary ->
                if (id == claimed.get()?.id && boundary == "stop_gate" &&
                    stoppedGate.compareAndSet(false, true)) {
                    atStopGate.countDown()
                    check(releaseChild.await(120, TimeUnit.SECONDS)) { "child stop gate not released" }
                }
            }
            DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = { id, token ->
                val e1 = claimed.get()
                if (e1 != null && id == e1.id && token == e1.executionId) {
                    // This is the actual child cleanup boundary. Outer cleanup
                    // cannot run until the real END worker has requeued E1.
                    record("CHILD_CLEANUP_RETAINED_ACTIVE")
                    val row = requireNotNull(database.downloadDao.getNullableDownloadById(id))
                    check(row.status == DownloadRepository.Status.Active.name && row.executionId == token)
                    check(DownloadWorkerExecutionOwners.isOwnedBy(id, token))
                    retainedByChild.countDown()
                    check(releaseOuter.await(120, TimeUnit.SECONDS)) { "outer cleanup not released" }
                }
            }
            delay(60_000L - System.currentTimeMillis() % 60_000L + 500L)
            val now = Calendar.getInstance()
            val minute = String.format(Locale.ROOT, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            assertTrue(preferences.edit().putString("schedule_start", minute).putString("schedule_end", minute)
                .putBoolean("use_scheduler", true).commit())
            AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ -> error("real END fallback") }
            AlarmScheduler(context).scheduleSuspending()
            val end = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.SCHEDULE_END, WorkManagerHandoffCarrier.END_BOUNDARY,
            ))
            val queued = DownloadItem(
                id = 0L, url = url, title = "exact owner race", author = "test", thumb = "", duration = "01:00",
                type = DownloadType.video, format = Format(container = "mp4"), container = "mp4", downloadSections = "",
                allFormats = mutableListOf(), downloadPath = context.filesDir.absolutePath, website = "example",
                downloadSize = "", playlistTitle = "", audioPreferences = AudioPreferences(), videoPreferences = VideoPreferences(),
                extraCommands = "", customFileNameTemplate = "", SaveThumb = false,
                status = DownloadRepository.Status.Queued.name, downloadStartTime = 0L, logID = null,
            )
            val queuedResult = viewModel.queueDownloads(listOf(queued), ignoreDuplicates = true)
            assertTrue(queuedResult.message, queuedResult.succeeded)
            assertTrue(atStopGate.await(20, TimeUnit.SECONDS))
            val e1 = requireNotNull(claimed.get())
            assertTrue(e1.executionId.isNotBlank())
            assertTrue(System.currentTimeMillis() < end.notBeforeAt)
            val running = workManager.getWorkInfosByTag("download").get(10, TimeUnit.SECONDS)
                .filter { it.state == WorkInfo.State.RUNNING }
            assertTrue(running.isNotEmpty())
            // Cancel the real carrier early solely to force child-before-END.
            // END still executes its actual cancellation/native/requeue path.
            running.forEach { workManager.cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS) }
            releaseChild.countDown()
            assertTrue("child did not retain Active E1", retainedByChild.await(20, TimeUnit.SECONDS))
            record("BEFORE_REAL_END")
            withTimeout(90_000L) {
                while (workManager.getWorkInfoById(UUID.fromString(end.requestId))
                        .get(10, TimeUnit.SECONDS)?.state?.isFinished != true) delay(25L)
            }
            assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(UUID.fromString(end.requestId))
                .get(10, TimeUnit.SECONDS)?.state)
            withTimeout(30_000L) { WorkManagerHandoffRecovery.reconcile(context) }
            val afterEnd = requireNotNull(database.downloadDao.getNullableDownloadById(e1.id))
            assertEquals(DownloadRepository.Status.Queued.name, afterEnd.status)
            assertEquals("", afterEnd.executionId)
            assertEquals(null, database.workManagerHandoffCarrierDao.get(end.handoffId))
            assertTrue("outer cleanup escaped its latch", DownloadWorkerExecutionOwners.isOwnedBy(e1.id, e1.executionId))
            record("END_QUEUED_BEFORE_OUTER_CLEANUP")
            releaseOuter.countDown()
            withTimeout(30_000L) {
                while (DownloadWorkerExecutionOwners.isOwnedBy(e1.id, e1.executionId)) delay(25L)
            }
            record("OUTER_CLEANUP_RETIRED_E1")
            assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(e1.id, e1.executionId))
            assertFalse(DownloadWorkerProcessOwners.isOwnedBy(e1.id, e1.executionId))
            assertEquals(afterEnd, database.downloadDao.getNullableDownloadById(e1.id))
        } finally {
            withContext(NonCancellable) {
                record("BEFORE_TEARDOWN")
                releaseChild.countDown()
                releaseOuter.countDown()
                DownloadClaimTestHooks.afterExecutionOwnerPublicationForTesting = null
                DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = null
                DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = null
                workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
                withContext(Dispatchers.Main) { viewModel.clearForTesting() }
                AlarmScheduler(context).cancel()
                DownloadWorkerEffectTestHooks.dbManagerForTesting = null
                WorkManagerHandoffRecovery.clearForTesting()
                database.downloadDao.deleteAll()
                val editor = preferences.edit()
                for (key in listOf("schedule_start", "schedule_end", "use_scheduler", "metered_networks", "concurrent_downloads")) {
                    when (val value = previous[key]) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        else -> editor.remove(key)
                    }
                }
                check(editor.commit())
                AlarmScheduler.exactAlarmPublicationForTesting = null
            }
        }
    }
}
