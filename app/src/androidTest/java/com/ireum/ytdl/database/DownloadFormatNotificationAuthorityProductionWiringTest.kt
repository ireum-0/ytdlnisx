package com.ireum.ytdl.database

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadFormatNotificationAuthorityProductionWiringTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private lateinit var viewModel: DownloadViewModel
    private var replayDiagnostic: ((String) -> Unit)? = null
    private var replayCompletionHandle: DisposableHandle? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        viewModel = DownloadViewModel(context, database, true)
    }

    @After
    fun tearDown() = runBlocking {
        captureReplayDiagnostic("before_teardown")
        try {
            viewModel.clearForTesting()
            captureReplayDiagnostic("after_viewmodel_clear")
            DownloadWorkerExecutionOwners.clearForTesting()
            DownloadWorkerProcessOwners.clearForTesting()
            database.close()
        } finally {
            captureReplayDiagnostic("teardown_exit")
            // Disposing an observation never cancels or joins its Job.
            runCatching { replayCompletionHandle?.dispose() }
            replayCompletionHandle = null
            replayDiagnostic = null
        }
    }

    @Test
    fun activeWorkerOwnedFormatCandidateIsRefusedWithoutChangingDurableIdentity() = runBlocking {
        val id = insert(
            status = DownloadRepository.Status.Active.name,
            executionId = "active-E1",
            operationId = "active-operation",
            retryAttempt = 4,
        )

        replayFormatNotification(id)

        assertUnchanged(id, DownloadRepository.Status.Active.name, "active-E1", "active-operation", 4)
    }

    @Test
    fun postProcessingWorkerOwnedFormatCandidateIsRefusedWithoutChangingDurableIdentity() = runBlocking {
        val id = insert(
            status = DownloadRepository.Status.PostProcessing.name,
            executionId = "post-E1",
            operationId = "post-operation",
            retryAttempt = 5,
        )

        replayFormatNotification(id)

        assertUnchanged(id, DownloadRepository.Status.PostProcessing.name, "post-E1", "post-operation", 5)
    }

    @Test
    fun newerQueuedFormatIntentIsRefusedWithoutChangingDurableIdentity() = runBlocking {
        val id = insert(
            status = DownloadRepository.Status.Queued.name,
            executionId = "",
            operationId = "new-queued-operation",
            retryAttempt = 2,
        )

        replayFormatNotification(id)

        assertUnchanged(id, DownloadRepository.Status.Queued.name, "", "new-queued-operation", 2)
    }

    @Test
    fun deletedFormatCandidateIsSkippedWithoutFailingTheNotification() = runBlocking {
        val id = insert(status = DownloadRepository.Status.Saved.name)
        database.downloadDao.delete(id)

        replayFormatNotification(id)

        assertNull(database.downloadDao.getNullableDownloadById(id))
    }

    @Test
    fun eligibleSavedFormatCandidateTransitionsThroughOwnedCas() = runBlocking {
        val id = insert(status = DownloadRepository.Status.Saved.name)

        replayFormatNotification(id)

        val current = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        assertEquals(DownloadRepository.Status.Processing.name, current.status)
        assertEquals("", current.executionId)
        assertEquals("", current.operationId)
        assertEquals(0, current.retryAttempt)
    }

    @Test
    fun mixedFormatNotificationProcessesEligibleSiblingAndPreservesStaleRows() = runBlocking {
        val activeId = insert(
            status = DownloadRepository.Status.Active.name,
            executionId = "mixed-active-E1",
            operationId = "mixed-active-operation",
            retryAttempt = 1,
        )
        val postId = insert(
            status = DownloadRepository.Status.PostProcessing.name,
            executionId = "mixed-post-E1",
            operationId = "mixed-post-operation",
            retryAttempt = 1,
        )
        val queuedId = insert(
            status = DownloadRepository.Status.Queued.name,
            operationId = "mixed-queued-operation",
            retryAttempt = 1,
        )
        val deletedId = insert(status = DownloadRepository.Status.Saved.name)
        database.downloadDao.delete(deletedId)
        val eligibleId = insert(status = DownloadRepository.Status.Saved.name)

        replayFormatNotification(activeId, postId, queuedId, deletedId, eligibleId)

        assertUnchanged(activeId, DownloadRepository.Status.Active.name, "mixed-active-E1", "mixed-active-operation", 1)
        assertUnchanged(postId, DownloadRepository.Status.PostProcessing.name, "mixed-post-E1", "mixed-post-operation", 1)
        assertUnchanged(queuedId, DownloadRepository.Status.Queued.name, "", "mixed-queued-operation", 1)
        assertNull(database.downloadDao.getNullableDownloadById(deletedId))
        assertEquals(
            DownloadRepository.Status.Processing.name,
            database.downloadDao.getNullableDownloadById(eligibleId)?.status,
        )
    }

    @Test
    fun errorHistoryReplacementRefusalRemainsRefusedInFormatNotificationBundle() = runBlocking {
        val id = insert(
            status = DownloadRepository.Status.Error.name,
            executionId = "error-E1",
            operationId = "error-operation",
            retryAttempt = 3,
            issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name,
            issueStage = DownloadIssueStage.HISTORY.name,
        )
        database.historyReplacementBarrierDao.insertIfAbsent(
            HistoryReplacementBarrier(
                downloadId = id,
                operationId = "barrier-operation",
                historyId = id + 1000L,
                expectedSourceUrl = "https://example.com/error-$id",
                expectedType = DownloadType.video.name,
                issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name,
                issueStage = DownloadIssueStage.HISTORY.name,
                createdAt = 1L,
            ),
        )

        replayFormatNotification(id)

        val current = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        assertEquals(DownloadRepository.Status.Error.name, current.status)
        assertEquals(
            DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name,
            current.lastIssueCode,
        )
        assertNotNull(database.historyReplacementBarrierDao.getByDownloadIdBlocking(id))
    }

    private suspend fun replayFormatNotification(vararg ids: Long) {
        val submittedAtNs = System.nanoTime()
        val outerJob = viewModel.turnDownloadItemsToProcessingDownloads(ids.toList(), deleteExisting = true)
        val outerCompletion = AtomicReference("NOT_VERIFIED")
        var waitStage = "before_timeout"
        var innerFirstObservedAtNs: Long? = null
        replayCompletionHandle = outerJob.invokeOnCompletion { cause ->
            // Observation only: never read payload, query Room, or change the Job outcome.
            try {
                outerCompletion.set("atNs=" + System.nanoTime() +
                    ",causeClass=" + (cause?.javaClass?.name ?: "NONE"))
            } catch (_: Throwable) {
                // A diagnostic failure must not escape a completion handler.
            }
        }
        replayDiagnostic = { boundary ->
            val innerJob = viewModel.processingItemsJob
            Log.i("FormatNotificationDiag",
                "boundary=$boundary waitStage=$waitStage submittedAtNs=$submittedAtNs " +
                    "outer=" + jobState(outerJob) + " outerCompletion=" + outerCompletion.get() +
                    " innerFirstObservedAtNs=$innerFirstObservedAtNs inner=" + jobState(innerJob) +
                    " actualPublicationAtNs=NOT_VERIFIED restoreDecision=NOT_VERIFIED " +
                    "mutexOwner=NOT_VERIFIED roomRows=NOT_VERIFIED " +
                    "fixtureIdentity=" + System.identityHashCode(database) +
                    " detachedUndoCompletion=NOT_VERIFIED")
        }
        try {
            withTimeout(10_000L) {
                waitStage = "poll"
                while (viewModel.processingItemsJob == null) delay(5L)
                // First observation is not the production publication timestamp.
                innerFirstObservedAtNs = System.nanoTime()
                waitStage = "join"
                viewModel.processingItemsJob?.join()
                waitStage = "complete"
            }
        } finally {
            // Runs before JUnit @After can cancel the Jobs or close the fixture.
            captureReplayDiagnostic("replay_exit")
        }
    }

    private fun jobState(job: Job?): String =
        job?.let { "active=${it.isActive},completed=${it.isCompleted},cancelled=${it.isCancelled}" }
            ?: "NULL_AT_SNAPSHOT"

    private fun captureReplayDiagnostic(boundary: String) {
        // Bounded metadata output is outside the original timeout/poll/join path.
        // Only diagnostic errors are contained; the original test failure propagates unchanged.
        runCatching { replayDiagnostic?.invoke(boundary) }
    }

    private suspend fun insert(
        status: String,
        executionId: String = "",
        operationId: String = "",
        retryAttempt: Int = 0,
        issueCode: String = "",
        issueStage: String = "",
    ): Long = database.downloadDao.insertRaw(
        DownloadItem(
            id = 0L,
            url = "https://example.com/format-${System.nanoTime()}",
            title = "Format candidate",
            author = "Author",
            thumb = "",
            duration = "1:00",
            type = DownloadType.video,
            format = Format(format_id = "1080p"),
            container = "mp4",
            downloadSections = "",
            allFormats = mutableListOf(),
            downloadPath = "/downloads",
            website = "example.com",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "%(title)s",
            SaveThumb = false,
            status = status,
            downloadStartTime = 0L,
            logID = null,
            operationId = operationId,
            retryAttempt = retryAttempt,
            executionId = executionId,
            lastIssueCode = issueCode,
            lastIssueStage = issueStage,
        ),
    )

    private fun assertUnchanged(
        id: Long,
        status: String,
        executionId: String,
        operationId: String,
        retryAttempt: Int,
    ) {
        val current = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        assertEquals(status, current.status)
        assertEquals(executionId, current.executionId)
        assertEquals(operationId, current.operationId)
        assertEquals(retryAttempt, current.retryAttempt)
    }
}
