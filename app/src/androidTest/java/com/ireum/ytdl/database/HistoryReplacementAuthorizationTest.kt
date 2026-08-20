package com.ireum.ytdl.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementAuthorization
import com.ireum.ytdl.database.repository.HistoryReplacementOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryReplacementAuthorizationTest {
    private lateinit var db: DBManager

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DBManager::class.java
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun canonicalEquivalentSourceIsAuthorized() = runBlocking {
        val historyId = insertHistory()
        val authorization = repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = "https://www.youtube.com/watch?v=$VIDEO_ID",
            expectedType = DownloadType.video,
        )

        assertTrue(authorization is HistoryReplacementAuthorization.Authorized)
        assertEquals(historyId, (authorization as HistoryReplacementAuthorization.Authorized).target.id)
    }

    @Test
    fun mismatchedSourceIsRejected() = runBlocking {
        val historyId = insertHistory()

        assertEquals(
            HistoryReplacementAuthorization.SourceMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = "https://youtu.be/$OTHER_VIDEO_ID",
                expectedType = DownloadType.video,
            )
        )
    }

    @Test
    fun mismatchedReplacementLeavesExistingHistoryMediaUntouched() = runBlocking {
        val historyId = insertHistory()
        val before = db.historyDao.getItem(historyId)

        assertEquals(
            HistoryReplacementOutcome.SourceMismatch,
            repository().replaceHistoryPreservingAssignmentsAuthorized(
                historyId = historyId,
                expectedSourceUrl = "https://youtu.be/$OTHER_VIDEO_ID",
                expectedType = DownloadType.video,
            ) { current ->
                current.copy(downloadPath = listOf("/tmp/unrelated-replacement.mp4"))
            }
        )

        val after = db.historyDao.getItem(historyId)
        assertEquals(before.downloadPath, after.downloadPath)
        assertEquals(before.title, after.title)
    }

    @Test
    fun incompatibleReplacementTypeIsRejected() = runBlocking {
        val historyId = insertHistory()

        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.audio,
            )
        )
    }

    @Test
    fun deletedTargetIsRejectedWithoutCreatingAReplacement() = runBlocking {
        val historyId = insertHistory()
        db.historyDao.deleteById(historyId)

        assertEquals(
            HistoryReplacementOutcome.TargetMissing,
            repository().replaceHistoryPreservingAssignmentsAuthorized(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
            ) { current -> current.copy(title = "should-not-be-written") }
        )
        assertTrue(db.historyDao.getAll().isEmpty())
    }

    @Test
    fun targetChangedAfterWorkerLoadIsRejectedAndNotOverwritten() = runBlocking {
        val historyId = insertHistory()
        val workerSnapshot = db.historyDao.getItem(historyId)
        db.historyDao.update(workerSnapshot.copy(url = "https://youtu.be/$OTHER_VIDEO_ID"))

        assertEquals(
            HistoryReplacementOutcome.SourceMismatch,
            repository().replaceHistoryPreservingAssignmentsAuthorized(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
            ) { current -> current.copy(title = "stale-worker-write") }
        )
        assertEquals("https://youtu.be/$OTHER_VIDEO_ID", db.historyDao.getItem(historyId).url)
        assertEquals("Video", db.historyDao.getItem(historyId).title)
    }

    @Test
    fun authorizedReplacementUsesTheValidatedCurrentSnapshotForPreservationAndCleanup() = runBlocking {
        val historyId = insertHistory()
        val initial = db.historyDao.getItem(historyId)
        db.historyDao.update(initial.copy(playbackPositionMs = 456L))

        val outcome = repository().replaceHistoryPreservingAssignmentsAuthorized(
            historyId = historyId,
            expectedSourceUrl = "https://m.youtube.com/watch?v=$VIDEO_ID",
            expectedType = DownloadType.video,
        ) { current ->
            current.copy(
                title = "Replacement",
                downloadPath = listOf("/tmp/replacement.mp4"),
                playbackPositionMs = current.playbackPositionMs,
            )
        }

        val updated = outcome as HistoryReplacementOutcome.Updated
        assertEquals(456L, updated.previousTarget.playbackPositionMs)
        assertEquals(
            listOf("/tmp/video.mp4"),
            updated.previousTarget.downloadPath
        )
        val persisted = db.historyDao.getItem(historyId)
        assertEquals("Replacement", persisted.title)
        assertEquals(456L, persisted.playbackPositionMs)
    }

    private fun repository() = HistoryKeywordAssignmentRepository(db)

    private suspend fun insertHistory(): Long = repository().insertHistory(history())

    private fun history() = HistoryItem(
        id = 0L,
        url = HISTORY_URL,
        title = "Video",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = 1L,
        downloadPath = listOf("/tmp/video.mp4"),
        website = "YouTube",
        format = Format(),
        downloadId = 1L,
    )

    companion object {
        private const val VIDEO_ID = "dQw4w9WgXcQ"
        private const val OTHER_VIDEO_ID = "9bZkp7q19f0"
        private const val HISTORY_URL = "https://youtu.be/$VIDEO_ID"
    }
}
