package com.ireum.ytdl.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.work.HistoryReplacementPersistenceResult
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import com.ireum.ytdl.work.YtdlpProcessIdentity
import com.ireum.ytdl.work.cleanupStoppedDownloadExecution
import com.ireum.ytdl.work.persistHistoryReplacementTerminalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadWorkerCleanupProductionWiringTest {
    private lateinit var db: DBManager

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun doublePersistenceFailureUsesTheProductionCleanupCarrierForEveryTypedRefusal() = runBlocking {
        listOf(
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE),
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE),
            HistoryReplacementDiagnostic.targetDeletedIssue(),
        ).forEach { issue ->
            val historyId = db.historyDao.insertAndGetIdRaw(history())
            val downloadId = db.downloadDao.insertRaw(
                download().copy(
                    playlistURL = "history-redownload:$historyId",
                    executionId = "E1",
                )
            )

            // Model the production worker's two failed authoritative writes:
            // the typed decision remains local when both attempts fail.
            val first = persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = { throw IOException("first terminal write failed") },
                transitionLinkedDownload = { error("ledger must not run") },
            )
            val second = persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = { throw IOException("recovery terminal write failed") },
                transitionLinkedDownload = { error("ledger must not run") },
            )
            assertTrue(first is HistoryReplacementPersistenceResult.Failed)
            assertTrue(second is HistoryReplacementPersistenceResult.Failed)

            // This is the production cleanup seam called by
            // DownloadWorker.cleanupStoppedWorker after the DB becomes
            // writable again.  It receives the worker-local exact issue and
            // cannot route it through ordinary requeue.
            val result = cleanupStoppedDownloadExecution(
                repository = DownloadRepository(db),
                downloadId = downloadId,
                executionId = "E1",
                authoritativeIssue = issue,
            )

            assertNotEquals(
                DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                result,
            )
            val current = db.downloadDao.getNullableDownloadById(downloadId)
            assertNotNull(current)
            assertEquals(DownloadRepository.Status.Error.name, current?.status)
            assertEquals(issue.code.name, current?.lastIssueCode)
            assertEquals(issue.stage.name, current?.lastIssueStage)
            val barrier = db.historyReplacementBarrierDao.getByDownloadId(downloadId)
            assertNotNull(barrier)
            assertEquals(issue.code.name, barrier?.issueCode)
            assertEquals(issue.stage.name, barrier?.issueStage)

            // Re-entry is idempotent and never turns the typed row into an
            // ordinary queued attempt.
            val reentry = cleanupStoppedDownloadExecution(
                repository = DownloadRepository(db),
                downloadId = downloadId,
                executionId = "E1",
                authoritativeIssue = issue,
            )
            assertNotEquals(
                DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                reentry,
            )
        }
    }

    @Test
    fun aTypedRecoveryFailureDoesNotPreventAnUnrelatedSiblingFromConverging() = runBlocking {
        val failingId = db.downloadDao.insertRaw(
            download().copy(executionId = "E1")
        )
        val ordinaryId = db.downloadDao.insertRaw(
            download().copy(executionId = "E2")
        )
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)

        var failed = false
        try {
            cleanupStoppedDownloadExecution(
                repository = DownloadRepository(db),
                downloadId = failingId,
                executionId = "E1",
                authoritativeIssue = issue,
            )
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)

        val siblingResult = cleanupStoppedDownloadExecution(
            repository = DownloadRepository(db),
            downloadId = ordinaryId,
            executionId = "E2",
        )
        assertEquals(
            DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
            siblingResult,
        )
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(ordinaryId)?.status,
        )
        assertFalse(
            db.downloadDao.getNullableDownloadById(failingId)?.status ==
                DownloadRepository.Status.Queued.name
        )
    }

    @Test
    fun startupRecoveryWaitsForNativeTerminationBeforeRequeue() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))
        val processId = YtdlpProcessIdentity.download(downloadId, "E1")
        val process = ControlledProcess()
        DownloadWorkerProcessOwners.claim(downloadId, "E1")
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))

        try {
            val recovery = async(Dispatchers.IO) {
                DownloadExecutionRecovery.reconcile(appContext, db)
            }
            process.destroyRequested.await()
            yield()
            assertFalse(recovery.isCompleted)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )

            process.acknowledgeTermination()
            recovery.await()
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, "E1")
        }
    }

    @Test
    fun startupRecoveryInspectsExactProcessRegistryAfterExecutionOwnerDisappears() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))
        val processId = YtdlpProcessIdentity.download(downloadId, "E1")
        val process = ControlledProcess()
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))
        // The durable journal says the prior cancellation barrier was
        // acknowledged, but an exact same-process native registry entry still
        // proves that the OS process has not quiesced.  No worker execution
        // owner is published in this scenario.
        assertTrue(DownloadExecutionRecovery.markNativeQuiescent(appContext, downloadId, "E1"))

        try {
            val recovery = async(Dispatchers.IO) {
                DownloadExecutionRecovery.reconcile(appContext, db)
            }
            process.destroyRequested.await()
            yield()
            assertFalse(recovery.isCompleted)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )

            process.acknowledgeTermination()
            recovery.await()
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
        }
    }

    @Test
    fun durableNativeRecoveryCarrierSurvivesTheProcessRegistryBeingGone() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "dead-E1"))
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))

        // Model a cold start after the app process and its in-memory process
        // registries disappeared.  The durable carrier still requires the
        // startup reconciler to establish that no exact process remains.
        DownloadExecutionRecovery.reconcile(appContext, db)

        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
    }

    private fun history() = HistoryItem(
        id = 0L,
        url = "https://example.com/video",
        title = "old title",
        author = "old author",
        artist = "old artist",
        duration = "01:00",
        durationSeconds = 60,
        thumb = "old-thumb",
        type = DownloadType.video,
        time = 1L,
        downloadPath = listOf("/old/file.mp4"),
        website = "example",
        format = Format(container = "mp4", filesize = 100),
        filesize = 100,
        downloadId = 0L,
    )

    private fun download() = DownloadItem(
        id = 0L,
        url = "https://example.com/video",
        title = "replacement",
        author = "author",
        thumb = "thumb",
        duration = "01:00",
        type = DownloadType.video,
        format = Format(container = "mp4"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "/tmp/file.mp4",
        website = "example",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = DownloadRepository.Status.Active.name,
        downloadStartTime = 0L,
        logID = null,
        playlistURL = "",
    )

    private class ControlledProcess : Process() {
        private val terminated = CountDownLatch(1)
        private var alive = true
        val destroyRequested = CompletableDeferred<Unit>()

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            terminated.await()
            return 143
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            terminated.await(timeout, unit)

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 143
        }

        override fun destroy() {
            destroyRequested.complete(Unit)
        }

        override fun destroyForcibly(): Process {
            acknowledgeTermination()
            return this
        }

        override fun isAlive(): Boolean = alive

        fun acknowledgeTermination() {
            alive = false
            terminated.countDown()
        }
    }
}
