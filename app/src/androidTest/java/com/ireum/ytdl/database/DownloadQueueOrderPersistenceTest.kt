package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.work.DownloadQueuePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadQueueOrderPersistenceTest {
    private lateinit var database: DBManager

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun reversingProcessingBatchPreservesItsQueueIntervalAndPositionSet() = runBlocking {
        database.downloadDao.insert(download(1, 100, DownloadRepository.Status.Queued))
        database.downloadDao.insert(download(2, 200, DownloadRepository.Status.Processing, 1))
        database.downloadDao.insert(download(3, 300, DownloadRepository.Status.Processing, 2))
        database.downloadDao.insert(download(4, 400, DownloadRepository.Status.Processing, 3))
        database.downloadDao.insert(download(5, 500, DownloadRepository.Status.Queued))

        val before = database.downloadDao.getProcessingDownloadsList()
        val processingIds = before.map(DownloadItem::id)
        val processingPositions = before.map(DownloadItem::orderPosition).toSet()

        database.downloadDao.reverseProcessingDownloads()

        val reversed = database.downloadDao.getProcessingDownloadsList()
        assertEquals(processingIds.reversed(), reversed.map(DownloadItem::id))
        assertEquals(processingIds.toSet(), reversed.map(DownloadItem::id).toSet())
        assertEquals(processingPositions, reversed.map(DownloadItem::orderPosition).toSet())
        assertEquals(listOf(3, 2, 1), reversed.map(DownloadItem::rowNumber))

        database.downloadDao.reQueueDownloadItems(processingIds)

        assertEquals(
            listOf(1L, 4L, 3L, 2L, 5L),
            database.downloadDao.getQueuedDownloadsList().map(DownloadItem::id)
        )
        val all = database.downloadDao.getAllDownloadsList()
        assertEquals(5, all.map(DownloadItem::orderPosition).toSet().size)
        assertTrue(all.map(DownloadItem::id).containsAll(processingIds))
    }

    @Test
    fun priorityObservationSurfacesItemBeyondOrdinaryPageForWorkerSelection() = runBlocking {
        (1L..12L).forEach { id ->
            database.downloadDao.insert(
                download(id, id * 100L, DownloadRepository.Status.Queued)
            )
        }

        val currentTime = System.currentTimeMillis()
        val ordinaryPage = database.downloadDao
            .getQueuedScheduledDownloadsUntil(currentTime)
            .first()
        val priorityIds = listOf(12L)
        val priorityPage = database.downloadDao
            .getQueuedScheduledDownloadsUntilWithPriority(currentTime, priorityIds)
            .first()

        assertEquals(10, ordinaryPage.size)
        assertFalse(ordinaryPage.any { it.id == 12L })
        assertEquals(12L, priorityPage.first().id)
        assertEquals(priorityPage.size, priorityPage.map(DownloadItem::id).distinct().size)

        val selected = DownloadQueuePolicy.selectCandidates(
            items = priorityPage,
            runningIds = emptySet(),
            prioritySnapshot = DownloadQueuePolicy.prioritySnapshot(
                priorityItemIds = priorityIds,
                queueRecords = database.downloadDao.getDownloadsByIdsSuspend(priorityIds),
                eligibleItemIds = priorityPage.mapTo(linkedSetOf(), DownloadItem::id),
                activeOrRunningIds = emptySet(),
                idOf = DownloadItem::id,
                statusOf = DownloadItem::status,
            ),
            availableSlots = 1,
            idOf = DownloadItem::id,
        )
        assertEquals(listOf(12L), selected.map(DownloadItem::id))
    }

    @Test
    fun queuedRangeExcludesEndpointsInEitherDirection() = runBlocking {
        database.downloadDao.insert(download(1, 100, DownloadRepository.Status.Queued))
        database.downloadDao.insert(download(2, 200, DownloadRepository.Status.Queued))
        database.downloadDao.insert(download(3, 300, DownloadRepository.Status.Queued))

        assertEquals(emptyList<Long>(), database.downloadDao.getQueuedIDsBetweenTwoItems(1, 2))
        assertEquals(listOf(2L), database.downloadDao.getQueuedIDsBetweenTwoItems(1, 3))
        assertEquals(listOf(2L), database.downloadDao.getQueuedIDsBetweenTwoItems(3, 1))
    }

    private fun download(
        id: Long,
        orderPosition: Long,
        status: DownloadRepository.Status,
        rowNumber: Int = 0,
    ) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        title = "Title $id",
        author = "Creator",
        thumb = "",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(format_id = "best"),
        container = "mp4",
        downloadSections = "",
        allFormats = arrayListOf(),
        downloadPath = "/downloads",
        website = "example.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "%(title)s",
        SaveThumb = false,
        status = status.name,
        downloadStartTime = 0,
        logID = null,
        orderPosition = orderPosition,
        rowNumber = rowNumber,
    )
}
