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
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementAuthorization
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.database.repository.DownloadRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryReplacementBarrierPersistenceTest {
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
    fun sourceMismatchBarrierSurvivesPauseAndMutableRetryFields() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val before = db.historyDao.getItem(historyId)

        assertEquals(
            HistoryReplacementAuthorization.SourceMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = OTHER_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        val barrier = db.historyReplacementBarrierDao.getByDownloadId(item.id)
        assertNotNull(barrier)
        assertEquals(
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE).code.name,
            barrier!!.issueCode,
        )

        db.downloadDao.setStatus(item.id, DownloadRepository.Status.Paused.name)
        db.downloadDao.update(item.copy(type = DownloadType.audio, status = DownloadRepository.Status.Queued.name))

        val afterStaleWrite = db.downloadDao.getDownloadById(item.id)
        assertEquals(DownloadRepository.Status.Paused.name, afterStaleWrite.status)
        assertEquals(item.playlistURL, afterStaleWrite.playlistURL)
        // The authoritative mismatch is carried by the separate durable
        // barrier.  A Paused row is allowed to retain its user-visible state;
        // stale full-row writers must not erase the barrier or project a
        // misleading terminal Download issue into the row.
        assertEquals("", afterStaleWrite.lastIssueCode)
        assertEquals("", afterStaleWrite.lastIssueStage)
        assertEquals(barrier!!.issueCode, db.historyReplacementBarrierDao.getByDownloadId(item.id)?.issueCode)
        assertEquals(barrier!!.issueStage, db.historyReplacementBarrierDao.getByDownloadId(item.id)?.issueStage)

        assertEquals(
            HistoryReplacementAuthorization.SourceMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        assertEquals(before.downloadPath, db.historyDao.getItem(historyId).downloadPath)
        assertEquals(before.url, db.historyDao.getItem(historyId).url)
    }

    @Test
    fun typeMismatchBarrierCannotBeReauthorizedAfterTypeChange() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.audio)

        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.audio,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        db.downloadDao.update(item.copy(type = DownloadType.video))
        assertEquals(DownloadType.audio, db.downloadDao.getDownloadById(item.id).type)

        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        assertEquals(listOf("/tmp/previous.mp4"), db.historyDao.getItem(historyId).downloadPath)
    }

    @Test
    fun staleSecondWorkerClaimCannotRunAfterFirstWorkerRecordsMismatch() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val firstExecution = "worker-one"
        val secondExecution = "worker-two"

        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = item.operationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = firstExecution,
            )
        )
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = OTHER_URL,
            expectedType = DownloadType.video,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )

        assertEquals(
            0,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = item.operationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = secondExecution,
            )
        )
        assertTrue(
            db.historyReplacementBarrierDao.getByDownloadId(item.id)?.issueCode ==
                HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE).code.name
        )
    }

    @Test
    fun pausedRowCanCarryMismatchWithoutBeingForcedBackToError() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val executionId = "paused-mismatch-attempt"

        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = item.operationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = executionId,
            )
        )
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = OTHER_URL,
            expectedType = DownloadType.video,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )
        assertEquals(1, db.downloadDao.pauseIfExecutionOwned(item.id, executionId))

        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
        assertEquals(
            1,
            db.downloadDao.persistMismatchIssueForExecution(
                id = item.id,
                executionId = executionId,
                issueCode = issue.code.name,
                issueStage = issue.stage.name,
            )
        )
        val current = db.downloadDao.getDownloadById(item.id)
        assertEquals(DownloadRepository.Status.Paused.name, current.status)
        assertEquals(issue.code.name, current.lastIssueCode)
        assertEquals(issue.stage.name, current.lastIssueStage)
        assertEquals(0, db.downloadDao.reQueueDownloadItems(listOf(item.id)))
        assertEquals(DownloadRepository.Status.Paused.name, db.downloadDao.getDownloadById(item.id).status)
    }

    private fun repository() = HistoryKeywordAssignmentRepository(db)

    private suspend fun insertHistory(): Long = repository().insertHistory(
        HistoryItem(
            id = 0L,
            url = HISTORY_URL,
            title = "Previous",
            author = "Author",
            duration = "1:00",
            thumb = "",
            type = DownloadType.video,
            time = 1L,
            downloadPath = listOf("/tmp/previous.mp4"),
            website = "YouTube",
            format = Format(),
            downloadId = 0L,
        )
    )

    private suspend fun insertDownload(type: DownloadType): DownloadItem {
        val operationId = "history-replacement-test-${type.name}"
        val item = DownloadItem(
            id = 0L,
            url = HISTORY_URL,
            title = "Replacement",
            author = "Author",
            thumb = "",
            duration = "1:00",
            type = type,
            format = Format(format_id = "best"),
            container = "mp4",
            downloadSections = "",
            allFormats = arrayListOf(),
            downloadPath = "/tmp",
            website = "YouTube",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "%(title)s",
            SaveThumb = false,
            status = DownloadRepository.Status.Queued.name,
            downloadStartTime = 0L,
            logID = null,
            playlistURL = "history-redownload:1",
            operationId = operationId,
        )
        val id = db.downloadDao.insert(item)
        return item.copy(id = id)
    }

    companion object {
        private const val HISTORY_URL = "https://youtu.be/dQw4w9WgXcQ"
        private const val OTHER_URL = "https://youtu.be/9bZkp7q19f0"
    }
}
