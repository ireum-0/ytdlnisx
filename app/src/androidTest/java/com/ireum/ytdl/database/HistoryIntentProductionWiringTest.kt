package com.ireum.ytdl.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.viewmodel.HistoryViewModel
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

/**
 * Exercises the same scalar intent APIs used by HistoryFragment's bulk
 * artist/keyword actions against the application's Room database.
 */
@RunWith(AndroidJUnit4::class)
class HistoryIntentProductionWiringTest {
    private lateinit var application: Application
    private lateinit var db: DBManager
    private lateinit var historyViewModel: HistoryViewModel
    private val insertedHistoryIds = mutableListOf<Long>()

    @Before
    fun openDatabase() {
        application = ApplicationProvider.getApplicationContext()
        db = DBManager.getInstance(application)
        historyViewModel = HistoryViewModel(application)
    }

    @After
    fun removeFixtures() {
        insertedHistoryIds.forEach(db.historyDao::deleteById)
    }

    @Test
    fun staleKeywordSnapshotDoesNotRollbackConcurrentArtistOrMetadata() = runBlocking {
        val historyId = insertWithManualKeywords()
        val staleSnapshot = db.historyDao.getItem(historyId)
        assertEquals("A", staleSnapshot.artist)

        HistoryRepository(db.historyDao, db.playlistDao).updateArtist(historyId, "B")

        historyViewModel.updateKeywords(historyId, "K1, K2").join()

        val current = db.historyDao.getItem(historyId)
        assertEquals("B", current.artist)
        assertEquals("K1, K2", current.keywords)
        assertOriginalMetadata(current)
    }

    @Test
    fun staleArtistSnapshotDoesNotRollbackConcurrentKeywordAssignments() = runBlocking {
        val historyId = insertWithManualKeywords()
        val staleSnapshot = db.historyDao.getItem(historyId)
        assertEquals("K1", staleSnapshot.keywords)

        HistoryKeywordAssignmentRepository(db).replaceManualKeywords(historyId, listOf("K1", "K3"))

        historyViewModel.updateArtist(historyId, "B").join()

        val current = db.historyDao.getItem(historyId)
        assertEquals("B", current.artist)
        assertEquals("K1, K3", current.keywords)
        assertOriginalMetadata(current)
    }

    @Test
    fun replacementMetadataSurvivesStaleArtistIntent() = runBlocking {
        val historyId = insertWithManualKeywords()
        val staleSnapshot = db.historyDao.getItem(historyId)
        persistReplacementMetadata(staleSnapshot)

        historyViewModel.updateArtist(historyId, "user artist").join()

        val current = db.historyDao.getItem(historyId)
        assertEquals("user artist", current.artist)
        assertReplacementMetadata(current)
    }

    @Test
    fun replacementMetadataSurvivesStaleKeywordIntent() = runBlocking {
        val historyId = insertWithManualKeywords()
        val staleSnapshot = db.historyDao.getItem(historyId)
        persistReplacementMetadata(staleSnapshot)

        historyViewModel.updateKeywords(historyId, "K1, K2").join()

        val current = db.historyDao.getItem(historyId)
        assertEquals("K1, K2", current.keywords)
        assertReplacementMetadata(current)
    }

    @Test
    fun deletedTargetIsNoOpForBothExplicitIntents() = runBlocking {
        val historyId = insertWithManualKeywords()
        db.historyDao.deleteById(historyId)

        historyViewModel.updateArtist(historyId, "stale artist").join()
        historyViewModel.updateKeywords(historyId, "resurrected").join()

        assertNull(db.historyDao.getNullableItem(historyId))
        assertTrue(db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).isEmpty())
    }

    @Test
    fun thumbnailBackfillCasRejectsStaleRemoteThumbAfterReplacement() = runBlocking {
        val historyId = insertWithManualKeywords()
        val staleSnapshot = db.historyDao.getItem(historyId)
        persistReplacementMetadata(staleSnapshot)

        val repository = HistoryRepository(db.historyDao, db.playlistDao)
        assertFalse(repository.updateThumb(historyId, staleSnapshot.thumb, "local-thumb"))

        val current = db.historyDao.getItem(historyId)
        assertEquals("replacement-thumb", current.thumb)
        assertReplacementMetadata(current)
    }

    private suspend fun insertWithManualKeywords(): Long {
        val id = db.historyDao.insertAndGetIdRaw(history(keywords = "K1"))
        insertedHistoryIds += id
        HistoryKeywordAssignmentRepository(db).initializeManualAssignments(id, "K1")
        assertNotNull(db.historyDao.getNullableItem(id))
        return id
    }

    private fun persistReplacementMetadata(stale: HistoryItem) {
        db.historyDao.updateRaw(
            stale.copy(
                url = "https://example.com/replacement",
                title = "replacement title",
                author = "replacement author",
                duration = "99:00",
                durationSeconds = 5940,
                thumb = "replacement-thumb",
                website = "replacement-source",
                downloadPath = listOf("/replacement/file.webm"),
                format = stale.format.copy(container = "webm", filesize = 999_000),
                filesize = 999_000,
            )
        )
    }

    private fun assertOriginalMetadata(item: HistoryItem) {
        assertEquals("https://example.com/video", item.url)
        assertEquals("original title", item.title)
        assertEquals("original author", item.author)
        assertEquals("01:00", item.duration)
        assertEquals(60, item.durationSeconds)
        assertEquals("remote-thumb", item.thumb)
        assertEquals("original-source", item.website)
        assertEquals(listOf("/original/file.mp4"), item.downloadPath)
        assertEquals("mp4", item.format.container)
        assertEquals(100_000, item.filesize)
    }

    private fun assertReplacementMetadata(item: HistoryItem) {
        assertEquals("https://example.com/replacement", item.url)
        assertEquals("replacement title", item.title)
        assertEquals("replacement author", item.author)
        assertEquals("99:00", item.duration)
        assertEquals(5940, item.durationSeconds)
        assertEquals("replacement-thumb", item.thumb)
        assertEquals("replacement-source", item.website)
        assertEquals(listOf("/replacement/file.webm"), item.downloadPath)
        assertEquals("webm", item.format.container)
        assertEquals(999_000, item.filesize)
    }

    private fun history(keywords: String) = HistoryItem(
        id = 0L,
        url = "https://example.com/video",
        title = "original title",
        author = "original author",
        artist = "A",
        duration = "01:00",
        durationSeconds = 60,
        thumb = "remote-thumb",
        type = DownloadType.video,
        time = System.currentTimeMillis(),
        downloadPath = listOf("/original/file.mp4"),
        website = "original-source",
        format = Format(container = "mp4", filesize = 100_000),
        filesize = 100_000,
        downloadId = 0L,
        keywords = keywords,
    )
}
