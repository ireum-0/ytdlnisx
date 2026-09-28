package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises duplicate discovery and mutation against real Room data. */
@RunWith(AndroidJUnit4::class)
class HistoryDuplicateIdentityProductionWiringTest {
    private lateinit var database: DBManager
    private lateinit var repository: HistoryRepository
    private lateinit var assignments: HistoryKeywordAssignmentRepository

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = HistoryRepository(database.historyDao, database.playlistDao)
        assignments = HistoryKeywordAssignmentRepository(database)
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

    @Test
    fun staleCandidateGroupPreservesEditedRowAndItsRelationships() = runBlocking {
        val (retainedId, duplicateId, playlistId) = seedDuplicatePair()
        val candidateGroups = candidateGroups()

        val duplicate = requireNotNull(database.historyDao.getNullableItem(duplicateId))
        assertEquals(
            1,
            database.historyDao.updateRaw(
                duplicate.copy(url = "https://www.youtube.com/watch?v=anotherVideo"),
            ),
        )

        assertEquals(0, assignments.deleteDuplicateHistoryGroups(candidateGroups))

        assertEquals(setOf(retainedId, duplicateId), database.historyDao.getAllDownloaded().map { it.id }.toSet())
        assertEquals(
            listOf("RetainedOnly"),
            database.automaticKeywordRuleDao.getAssignmentsRaw(retainedId).map { it.keyword },
        )
        assertEquals(
            listOf("DuplicateOnly"),
            database.automaticKeywordRuleDao.getAssignmentsRaw(duplicateId).map { it.keyword },
        )
        assertEquals("RetainedOnly", database.historyDao.getItem(retainedId).keywords)
        assertEquals("DuplicateOnly", database.historyDao.getItem(duplicateId).keywords)
        assertEquals(listOf(playlistId), database.playlistDao.getPlaylistItemsForHistory(retainedId).map { it.playlistId })
        assertEquals(listOf(playlistId), database.playlistDao.getPlaylistItemsForHistory(duplicateId).map { it.playlistId })
    }

    @Test
    fun staleCandidateGroupPreservesTypeChangedRowAndItsRelationships() = runBlocking {
        val (retainedId, duplicateId, playlistId) = seedDuplicatePair()
        val candidateGroups = candidateGroups()

        val duplicate = requireNotNull(database.historyDao.getNullableItem(duplicateId))
        assertEquals(1, database.historyDao.updateRaw(duplicate.copy(type = DownloadType.audio)))

        assertEquals(0, assignments.deleteDuplicateHistoryGroups(candidateGroups))

        assertEquals(setOf(retainedId, duplicateId), database.historyDao.getAllDownloaded().map { it.id }.toSet())
        assertEquals(
            listOf("RetainedOnly"),
            database.automaticKeywordRuleDao.getAssignmentsRaw(retainedId).map { it.keyword },
        )
        assertEquals(
            listOf("DuplicateOnly"),
            database.automaticKeywordRuleDao.getAssignmentsRaw(duplicateId).map { it.keyword },
        )
        assertEquals(listOf(playlistId), database.playlistDao.getPlaylistItemsForHistory(retainedId).map { it.playlistId })
        assertEquals(listOf(playlistId), database.playlistDao.getPlaylistItemsForHistory(duplicateId).map { it.playlistId })
    }

    @Test
    fun duplicateAssignmentsRelationshipsAndHistoryDeleteRollbackAsOneTransaction() = runBlocking {
        val (retainedId, duplicateId, playlistId) = seedDuplicatePair()
        val candidateGroups = candidateGroups()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_duplicate_history_delete
            BEFORE DELETE ON history WHEN OLD.id = $duplicateId
            BEGIN SELECT RAISE(ABORT, 'injected duplicate deletion failure'); END
            """.trimIndent(),
        )

        val failure = runCatching { assignments.deleteDuplicateHistoryGroups(candidateGroups) }.exceptionOrNull()

        assertTrue("expected the injected history-row delete failure", failure != null)
        assertEquals(setOf(retainedId, duplicateId), database.historyDao.getAllDownloaded().map { it.id }.toSet())
        assertEquals("RetainedOnly", database.historyDao.getItem(retainedId).keywords)
        assertEquals("DuplicateOnly", database.historyDao.getItem(duplicateId).keywords)
        assertEquals(
            listOf("RetainedOnly"),
            database.automaticKeywordRuleDao.getAssignmentsRaw(retainedId).map { it.keyword },
        )
        assertEquals(
            listOf("DuplicateOnly"),
            database.automaticKeywordRuleDao.getAssignmentsRaw(duplicateId).map { it.keyword },
        )
        assertEquals(listOf(playlistId), database.playlistDao.getPlaylistItemsForHistory(retainedId).map { it.playlistId })
        assertEquals(listOf(playlistId), database.playlistDao.getPlaylistItemsForHistory(duplicateId).map { it.playlistId })

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_duplicate_history_delete")
        val newcomerId = database.historyDao.insertAndGetIdRaw(
            history(
                id = 0,
                url = "https://youtu.be/dQw4w9WgXcQ?t=30",
                title = "Later same identity",
                time = 30,
            ),
        )
        assignments.replaceManualKeywords(newcomerId, listOf("NewcomerOnly"))
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlistId, newcomerId))

        assertEquals(1, assignments.deleteDuplicateHistoryGroups(candidateGroups))

        assertEquals(setOf(retainedId, newcomerId), database.historyDao.getAllDownloaded().map { it.id }.toSet())
        assertEquals(
            setOf("RetainedOnly", "DuplicateOnly"),
            database.automaticKeywordRuleDao.getAssignmentsRaw(retainedId).map { it.keyword }.toSet(),
        )
        val retainedKeywords = database.historyDao.getItem(retainedId).keywords
        assertTrue(retainedKeywords.contains("RetainedOnly"))
        assertTrue(retainedKeywords.contains("DuplicateOnly"))
        assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(duplicateId).isEmpty())
        assertTrue(database.playlistDao.getPlaylistItemsForHistory(duplicateId).isEmpty())
        assertEquals("NewcomerOnly", database.historyDao.getItem(newcomerId).keywords)
        assertEquals(listOf(playlistId), database.playlistDao.getPlaylistItemsForHistory(newcomerId).map { it.playlistId })

        assertEquals(
            0,
            database.historyDao.updateRaw(
                history(
                    id = duplicateId,
                    url = "https://youtu.be/a-late-stale-update",
                    title = "Late stale update",
                    time = 20,
                ),
            ),
        )
        assertNull(database.historyDao.getNullableItem(duplicateId))
    }

    private suspend fun seedDuplicatePair(): Triple<Long, Long, Long> {
        val retainedId = database.historyDao.insertAndGetIdRaw(
            history(
                id = 0,
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                title = "Original title",
                time = 10,
            ),
        )
        val duplicateId = database.historyDao.insertAndGetIdRaw(
            history(
                id = 0,
                url = "https://youtu.be/dQw4w9WgXcQ?t=5",
                title = "Different title",
                time = 20,
            ),
        )
        assignments.replaceManualKeywords(retainedId, listOf("RetainedOnly"))
        assignments.replaceManualKeywords(duplicateId, listOf("DuplicateOnly"))
        val playlistId = database.playlistDao.insertPlaylist(Playlist(name = "Dedupe", description = null))
        database.playlistDao.insertPlaylistItems(
            listOf(
                PlaylistItemCrossRef(playlistId, retainedId),
                PlaylistItemCrossRef(playlistId, duplicateId),
            ),
        )
        return Triple(retainedId, duplicateId, playlistId)
    }

    private fun candidateGroups(): List<List<Long>> = repository.getDuplicateGroups().map { group ->
        group.map { it.id }
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
