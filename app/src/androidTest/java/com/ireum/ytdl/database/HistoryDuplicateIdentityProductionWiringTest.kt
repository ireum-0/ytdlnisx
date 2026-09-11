package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.HistoryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises HistoryRepository.getDuplicateGroups() against real Room data. */
@RunWith(AndroidJUnit4::class)
class HistoryDuplicateIdentityProductionWiringTest {
    private lateinit var database: DBManager
    private lateinit var repository: HistoryRepository

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = HistoryRepository(database.historyDao, database.playlistDao)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun selectorDoesNotGroupSameTitleWithDifferentSources() {
        insert(history(1, "https://example.com/one", "Same title", 20))
        insert(history(2, "https://example.com/two", "Same title", 10))

        assertTrue(repository.getDuplicateGroups().isEmpty())
        assertEquals(2, database.historyDao.getAllDownloaded().size)
    }

    @Test
    fun selectorUsesStrongMediaIdentityAndPreservesTypeBoundary() {
        insert(history(1, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "First title", 20))
        insert(history(2, "https://youtu.be/dQw4w9WgXcQ?t=5", "Second title", 10))
        insert(
            history(
                3,
                "https://youtu.be/dQw4w9WgXcQ",
                "Audio rendition",
                5,
                type = DownloadType.audio,
            )
        )

        val groups = repository.getDuplicateGroups()
        assertEquals(1, groups.size)
        assertEquals(listOf(2L, 1L), groups.single().map { it.id })
        assertEquals(3, database.historyDao.getAllDownloaded().size)
    }

    @Test
    fun selectorRejectsUnknownAndAmbiguousSourcesWithoutDeletingRows() {
        insert(history(1, "", "Same title", 20))
        insert(history(2, "yt-dlp https://example.com/video", "Same title", 10))
        insert(history(3, "https://example.com/video?asset=one", "Same title", 5))
        insert(history(4, "https://example.com/video?asset=two", "Same title", 1))

        assertTrue(repository.getDuplicateGroups().isEmpty())
        assertEquals(setOf(1L, 2L, 3L, 4L), database.historyDao.getAllDownloaded().map { it.id }.toSet())
    }

    @Test
    fun selectorDoesNotGroupExtractorSignificantFragmentSources() {
        insert(history(1, "http://video.sina.com.cn/#250576776", "Same title", 20))
        insert(history(2, "http://video.sina.com.cn/#250576777", "Same title", 10))

        assertTrue(repository.getDuplicateGroups().isEmpty())
        assertEquals(setOf(1L, 2L), database.historyDao.getAllDownloaded().map { it.id }.toSet())
    }

    private fun insert(item: HistoryItem) {
        database.historyDao.insertAndGetIdRaw(item)
    }

    private fun history(
        id: Long,
        url: String,
        title: String,
        time: Long,
        type: DownloadType = DownloadType.video,
    ) = HistoryItem(
        id = id,
        url = url,
        title = title,
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = type,
        time = time,
        downloadPath = listOf("content://media/$id"),
        website = "Test",
        format = Format(),
        downloadId = id,
    )
}
