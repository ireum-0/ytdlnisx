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
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.work.DownloadExecutionRecovery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FindingAProductionWiringTest {
    private lateinit var db: DBManager
    private lateinit var historyRepository: HistoryRepository

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
        historyRepository = HistoryRepository(db.historyDao, db.playlistDao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun staleArtistAndThumbnailWritesPreserveReplacementMetadata() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val stale = db.historyDao.getItem(historyId)
        db.historyDao.updateRaw(
            stale.copy(
                title = "replacement title",
                duration = "99:00",
                durationSeconds = 5940,
                thumb = "replacement-thumb",
                format = stale.format.copy(container = "mkv"),
                filesize = 99_000,
            )
        )

        assertTrue(historyRepository.updateArtist(historyId, "new artist"))
        assertFalse(historyRepository.updateThumb(historyId, stale.thumb, "local-thumb"))

        val current = db.historyDao.getItem(historyId)
        assertEquals("new artist", current.artist)
        assertEquals("replacement-thumb", current.thumb)
        assertEquals("replacement title", current.title)
        assertEquals("99:00", current.duration)
        assertEquals(5940, current.durationSeconds)
        assertEquals("mkv", current.format.container)
        assertEquals(99_000, current.filesize)
    }

    @Test
    fun staleKeywordIntentPreservesReplacementMetadata() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val stale = db.historyDao.getItem(historyId)
        db.historyDao.updateRaw(
            stale.copy(
                title = "replacement title",
                durationSeconds = 3600,
                format = stale.format.copy(container = "webm"),
                filesize = 360_000,
            )
        )

        HistoryKeywordAssignmentRepository(db)
            .updateManualFromMaterializedEditor(historyId, listOf("manual-keyword"))

        val current = db.historyDao.getItem(historyId)
        assertEquals("manual-keyword", current.keywords)
        assertEquals("replacement title", current.title)
        assertEquals(3600, current.durationSeconds)
        assertEquals("webm", current.format.container)
        assertEquals(360_000, current.filesize)
    }

    @Test
    fun fieldScopedWriterTreatsDeletedTargetAsNoOp() {
        assertFalse(historyRepository.updateArtist(404L, "ignored"))
        assertFalse(historyRepository.updateThumb(404L, "missing", "ignored"))
    }

    @Test
    fun committedHistoryReplacementIsDebtForTheRunningRequeuePrimitive() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val download = download()
        val downloadId = db.downloadDao.insertRaw(download)
        val committed = db.historyDao.getItem(historyId).copy(downloadId = downloadId)
        db.historyDao.updateRaw(committed)
        db.downloadDao.updateRaw(
            download.copy(
                id = downloadId,
                playlistURL = "history-redownload:$historyId",
                executionId = "E1",
                status = DownloadRepository.Status.Active.name,
            )
        )

        val result = DownloadRepository(db).requeueRunningDownload(downloadId, "E1")

        assertEquals(
            DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT,
            result,
        )
        assertEquals(DownloadRepository.Status.Active.name, db.downloadDao.getNullableDownloadById(downloadId)?.status)
        assertEquals("E1", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
    }

    @Test
    fun ordinaryExactTokenRunningRowStillRequeues() = runBlocking {
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))

        val result = DownloadRepository(db).requeueRunningDownload(downloadId, "E1")

        assertEquals(DownloadRepository.RunningDownloadRequeueResult.REQUEUED, result)
        assertEquals(DownloadRepository.Status.Queued.name, db.downloadDao.getNullableDownloadById(downloadId)?.status)
        assertEquals("", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
    }

    @Test
    fun applicationRecoveryRequeuesAbandonedOrdinaryRowWithoutDownloadWorker() = runBlocking {
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "dead-E1"))

        DownloadExecutionRecovery.reconcile(ApplicationProvider.getApplicationContext(), db)

        assertEquals(DownloadRepository.Status.Queued.name, db.downloadDao.getNullableDownloadById(downloadId)?.status)
    }

    @Test
    fun applicationRecoveryFinalizesCommittedDebtIdempotently() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = "dead-E1",
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))

        DownloadExecutionRecovery.reconcile(ApplicationProvider.getApplicationContext(), db)
        DownloadExecutionRecovery.reconcile(ApplicationProvider.getApplicationContext(), db)

        assertEquals(null, db.downloadDao.getNullableDownloadById(downloadId))
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
}
