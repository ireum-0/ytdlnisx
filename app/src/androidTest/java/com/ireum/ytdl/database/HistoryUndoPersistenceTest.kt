package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class HistoryUndoPersistenceTest {
    private lateinit var database: DBManager
    private lateinit var assignments: HistoryKeywordAssignmentRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        assignments = HistoryKeywordAssignmentRepository(database)
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun undoRestoresOneHistoryWithAllPlaylistMembershipsAndAssignments() = runBlocking {
        insertHistory(1, "https://example.com/one", "manual")
        val firstPlaylist = database.playlistDao.insertPlaylist(Playlist(name = "One", description = null))
        val secondPlaylist = database.playlistDao.insertPlaylist(Playlist(name = "Two", description = null))
        database.playlistDao.insertPlaylistItems(
            listOf(
                PlaylistItemCrossRef(firstPlaylist, 1),
                PlaylistItemCrossRef(secondPlaylist, 1),
            )
        )
        assignments.initializeManualAssignments(1, "manual")

        val snapshot = assignments.captureAndDeleteHistoryForUndo(1)
        assertNotNull(snapshot)
        assertNull(database.historyDao.getNullableItem(1))
        assertTrue(database.playlistDao.getPlaylistItemsForHistory(1).isEmpty())
        assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(1).isEmpty())

        assertEquals(1L, assignments.restoreHistory(snapshot!!))
        assertEquals("manual", database.historyDao.getMaterializedKeywordsOrNull(1))
        assertEquals(
            setOf(firstPlaylist, secondPlaylist),
            database.playlistDao.getPlaylistItemsForHistory(1).map { it.playlistId }.toSet(),
        )
        assertEquals(
            setOf(HistoryKeywordAssignmentSources.MANUAL),
            database.automaticKeywordRuleDao.getAssignmentsRaw(1).map { it.sourceType }.toSet(),
        )
    }

    @Test
    fun committedRemovalLeavesNoDanglingPlaylistReferences() = runBlocking {
        insertHistory(1, "https://example.com/one")
        val playlist = database.playlistDao.insertPlaylist(Playlist(name = "One", description = null))
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlist, 1))

        assignments.deleteHistoryRecords(listOf(1))

        assertNull(database.historyDao.getNullableItem(1))
        assertTrue(database.playlistDao.getPlaylistItemsForHistory(1).isEmpty())
    }

    @Test
    fun bulkRemovalUsesTheSameAtomicRelationshipBoundary() = runBlocking {
        insertHistory(1, "https://example.com/one")
        insertHistory(2, "https://example.com/two")
        val playlist = database.playlistDao.insertPlaylist(Playlist(name = "All", description = null))
        database.playlistDao.insertPlaylistItems(
            listOf(PlaylistItemCrossRef(playlist, 1), PlaylistItemCrossRef(playlist, 2))
        )

        assignments.deleteHistoryRecords(listOf(1, 2))

        assertNull(database.historyDao.getNullableItem(1))
        assertNull(database.historyDao.getNullableItem(2))
        assertTrue(database.playlistDao.getPlaylistItemsForHistory(1).isEmpty())
        assertTrue(database.playlistDao.getPlaylistItemsForHistory(2).isEmpty())
    }

    @Test
    fun undoDoesNotOverwriteAReplacementHistoryRowAtTheOldId() = runBlocking {
        insertHistory(1, "https://example.com/original")
        val playlist = database.playlistDao.insertPlaylist(Playlist(name = "One", description = null))
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlist, 1))

        val snapshot = assignments.captureAndDeleteHistoryForUndo(1)!!
        insertHistory(1, "https://example.com/replacement")

        assertNull(assignments.restoreHistory(snapshot))
        assertEquals("https://example.com/replacement", database.historyDao.getItem(1).url)
        assertTrue(database.playlistDao.getPlaylistItemsForHistory(1).isEmpty())
    }

    @Test
    fun deletionFailureBeforeCommitLeavesOwnedRelationshipsIntact() = runBlocking {
        insertHistory(1, "https://example.com/blocked")
        val playlist = database.playlistDao.insertPlaylist(Playlist(name = "One", description = null))
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlist, 1))
        assignments.initializeManualAssignments(1, "manual")

        val triggerName = "history_undo_delete_failure"
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $triggerName BEFORE DELETE ON history " +
                "BEGIN SELECT RAISE(ABORT, 'forced History delete failure'); END"
        )
        try {
            var failure: Throwable? = null
            try {
                assignments.captureAndDeleteHistoryForUndo(1)
            } catch (error: Throwable) {
                failure = error
            }
            assertNotNull(failure)
            assertNotNull(database.historyDao.getNullableItem(1))
            assertEquals(1, database.playlistDao.getPlaylistItemsForHistory(1).size)
            assertEquals(1, database.automaticKeywordRuleDao.getAssignmentsRaw(1).size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        }
    }

    @Test
    fun undoRestoreFailureLeavesNoPartiallyRestoredGraph() = runBlocking {
        insertHistory(1, "https://example.com/restore-failure")
        val playlist = database.playlistDao.insertPlaylist(Playlist(name = "One", description = null))
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlist, 1))
        assignments.initializeManualAssignments(1, "manual")
        val snapshot = assignments.captureAndDeleteHistoryForUndo(1)!!

        val triggerName = "history_undo_restore_failure"
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $triggerName BEFORE INSERT ON history " +
                "BEGIN SELECT RAISE(ABORT, 'forced History restore failure'); END"
        )
        try {
            var failure: Throwable? = null
            try {
                assignments.restoreHistory(snapshot)
            } catch (error: Throwable) {
                failure = error
            }
            assertNotNull(failure)
            assertNull(database.historyDao.getNullableItem(1))
            assertTrue(database.playlistDao.getPlaylistItemsForHistory(1).isEmpty())
            assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(1).isEmpty())
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        }
    }

    private fun insertHistory(id: Long, url: String, keywords: String = "") {
        database.historyDao.insertRaw(
            HistoryItem(
                id = id,
                url = url,
                title = "Item $id",
                author = "Creator",
                duration = "00:01:00",
                thumb = "",
                type = DownloadType.video,
                time = 1000 + id,
                downloadPath = emptyList(),
                website = "example",
                format = Format(format_id = "best"),
                downloadId = 0,
                keywords = keywords,
            )
        )
    }
}
