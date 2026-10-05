package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
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
        val (retainedId, duplicateId, playlistId) = seedDuplicatePair(sharedPlaylist = false)
        val duplicatePlaylistId = database.playlistDao.getPlaylistItemsForHistory(duplicateId).single().playlistId
        val candidateGroups = candidateGroups()
        val before = snapshotGraph()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_duplicate_history_delete
            BEFORE DELETE ON history WHEN OLD.id = $duplicateId
            BEGIN
                SELECT CASE WHEN NOT EXISTS (
                    SELECT 1 FROM PlaylistItemCrossRef
                    WHERE playlistId = $duplicatePlaylistId AND historyItemId = $retainedId
                ) OR NOT EXISTS (
                    SELECT 1 FROM history_keyword_assignments
                    WHERE historyItemId = $retainedId AND keyword = 'DuplicateOnly'
                ) THEN RAISE(ABORT, 'provisional relationship union missing') END;
                SELECT RAISE(ABORT, 'injected duplicate deletion failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching { assignments.deleteDuplicateHistoryGroups(candidateGroups) }.exceptionOrNull()

        assertTrue("expected the injected history-row delete failure", failure != null)
        assertTrue(failureMessages(failure).contains("injected duplicate deletion failure"))
        assertEquals(before, snapshotGraph())
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
        assertEquals(listOf(duplicatePlaylistId), database.playlistDao.getPlaylistItemsForHistory(duplicateId).map { it.playlistId })

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
        assertEquals(setOf(playlistId, duplicatePlaylistId), playlistIds(retainedId))
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

    @Test
    fun asymmetricPlaylistMembershipsTransferAndCleanupIsIdempotent() = runBlocking {
        val (retainedId, duplicateId, retainedPlaylistId) = seedDuplicatePair(sharedPlaylist = false)
        val duplicatePlaylistId = playlistIds(duplicateId).single()
        val playlists = database.playlistDao.getAllPlaylistsSync().sortedBy { it.id }
        val candidates = candidateGroups()

        assertEquals(1, assignments.deleteDuplicateHistoryGroups(candidates))

        assertCollapsed(retainedId, listOf(duplicateId), setOf(retainedPlaylistId, duplicatePlaylistId),
            setOf("RetainedOnly", "DuplicateOnly"))
        assertEquals(playlists, database.playlistDao.getAllPlaylistsSync().sortedBy { it.id })
        val converged = snapshotGraph()
        assertEquals(0, assignments.deleteDuplicateHistoryGroups(candidates))
        assertEquals(0, assignments.deleteDuplicateHistoryGroups(candidateGroups()))
        assertEquals(converged, snapshotGraph())
    }

    @Test
    fun sharedAndUniquePlaylistMembershipsMaterializeExactlyOnce() = runBlocking {
        val (retainedId, duplicateId, sharedPlaylistId) = seedDuplicatePair()
        val retainedOnly = addPlaylist("Retained playlist", retainedId)
        val duplicateOnly = addPlaylist("Duplicate playlist", duplicateId)
        val playlists = database.playlistDao.getAllPlaylistsSync().sortedBy { it.id }

        assertEquals(1, assignments.deleteDuplicateHistoryGroups(candidateGroups()))

        assertCollapsed(retainedId, listOf(duplicateId), setOf(retainedOnly, duplicateOnly, sharedPlaylistId),
            setOf("RetainedOnly", "DuplicateOnly"))
        assertEquals(playlists, database.playlistDao.getAllPlaylistsSync().sortedBy { it.id })
    }

    @Test
    fun retainedWithoutPlaylistReceivesDuplicateMembership() = runBlocking {
        val (retainedId, duplicateId, _) = seedDuplicatePair(sharedPlaylist = false)
        val duplicatePlaylistId = playlistIds(duplicateId).single()
        database.playlistDao.deletePlaylistItemsByHistoryIds(listOf(retainedId))
        assertTrue(playlistIds(retainedId).isEmpty())

        assertEquals(1, assignments.deleteDuplicateHistoryGroups(candidateGroups()))

        assertCollapsed(retainedId, listOf(duplicateId), setOf(duplicatePlaylistId),
            setOf("RetainedOnly", "DuplicateOnly"))
    }

    @Test
    fun duplicateWithoutPlaylistPreservesRetainedMembership() = runBlocking {
        val (retainedId, duplicateId, retainedPlaylistId) = seedDuplicatePair(sharedPlaylist = false)
        database.playlistDao.deletePlaylistItemsByHistoryIds(listOf(duplicateId))
        assertTrue(playlistIds(duplicateId).isEmpty())

        assertEquals(1, assignments.deleteDuplicateHistoryGroups(candidateGroups()))

        assertCollapsed(retainedId, listOf(duplicateId), setOf(retainedPlaylistId),
            setOf("RetainedOnly", "DuplicateOnly"))
    }

    @Test
    fun multipleDuplicatesPreserveFullPlaylistAndKeywordUnion() = runBlocking {
        val (retainedId, duplicateId, sharedPlaylistId) = seedDuplicatePair()
        val retainedOnly = addPlaylist("Retained playlist", retainedId)
        val duplicateOnly = addPlaylist("Duplicate playlist", duplicateId)
        val secondDuplicateId = addDuplicate("SecondDuplicateOnly", 30)
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(sharedPlaylistId, secondDuplicateId))
        val secondOnly = addPlaylist("Second duplicate playlist", secondDuplicateId)
        val playlists = database.playlistDao.getAllPlaylistsSync().sortedBy { it.id }

        assertEquals(2, assignments.deleteDuplicateHistoryGroups(candidateGroups()))

        assertCollapsed(retainedId, listOf(duplicateId, secondDuplicateId),
            setOf(sharedPlaylistId, retainedOnly, duplicateOnly, secondOnly),
            setOf("RetainedOnly", "DuplicateOnly", "SecondDuplicateOnly"))
        assertEquals(playlists, database.playlistDao.getAllPlaylistsSync().sortedBy { it.id })
        val converged = snapshotGraph()
        assertEquals(0, assignments.deleteDuplicateHistoryGroups(candidateGroups()))
        assertEquals(converged, snapshotGraph())
    }

    @Test
    fun staleSourceCandidateDoesNotTransferDuplicateOnlyPlaylist() = runBlocking {
        val (_, duplicateId, _) = seedDuplicatePair(sharedPlaylist = false)
        val candidates = candidateGroups()
        val duplicate = requireNotNull(database.historyDao.getNullableItem(duplicateId))
        assertEquals(1, database.historyDao.updateRaw(duplicate.copy(url = "https://youtu.be/anotherVideo")))
        val edited = snapshotGraph()

        assertEquals(0, assignments.deleteDuplicateHistoryGroups(candidates))
        assertEquals(edited, snapshotGraph())
    }

    @Test
    fun staleTypeCandidateDoesNotTransferDuplicateOnlyPlaylist() = runBlocking {
        val (_, duplicateId, _) = seedDuplicatePair(sharedPlaylist = false)
        val candidates = candidateGroups()
        val duplicate = requireNotNull(database.historyDao.getNullableItem(duplicateId))
        assertEquals(1, database.historyDao.updateRaw(duplicate.copy(type = DownloadType.audio)))
        val edited = snapshotGraph()

        assertEquals(0, assignments.deleteDuplicateHistoryGroups(candidates))
        assertEquals(edited, snapshotGraph())
    }

    @Test
    fun playlistTransferInsertFailureRollsBackEarlierDuplicateAndEntireGraph() = runBlocking {
        val (retainedId, duplicateId, _) = seedDuplicatePair(sharedPlaylist = false)
        val firstTransferredPlaylistId = playlistIds(duplicateId).single()
        val secondDuplicateId = addDuplicate("SecondDuplicateOnly", 30)
        val failingPlaylistId = addPlaylist("Failing duplicate playlist", secondDuplicateId)
        val candidates = candidateGroups()
        val before = snapshotGraph()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_duplicate_playlist_transfer
            BEFORE INSERT ON PlaylistItemCrossRef
            WHEN NEW.playlistId = $failingPlaylistId AND NEW.historyItemId = $retainedId
            BEGIN
                SELECT CASE WHEN EXISTS (SELECT 1 FROM history WHERE id = $duplicateId)
                    OR NOT EXISTS (
                        SELECT 1 FROM PlaylistItemCrossRef
                        WHERE playlistId = $firstTransferredPlaylistId AND historyItemId = $retainedId
                    ) OR NOT EXISTS (
                        SELECT 1 FROM history_keyword_assignments
                        WHERE historyItemId = $retainedId AND keyword = 'DuplicateOnly'
                    ) THEN RAISE(ABORT, 'earlier duplicate did not provisionally converge') END;
                SELECT RAISE(ABORT, 'injected playlist transfer failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching { assignments.deleteDuplicateHistoryGroups(candidates) }.exceptionOrNull()

        assertTrue(failureMessages(failure).contains("injected playlist transfer failure"))
        assertEquals(before, snapshotGraph())
    }

    @Test
    fun inTransactionIdentityChangeAfterPlaylistTransferRollsBackAtFinalRecheck() = runBlocking {
        val (retainedId, duplicateId, _) = seedDuplicatePair(sharedPlaylist = false)
        val duplicatePlaylistId = playlistIds(duplicateId).single()
        val candidates = candidateGroups()
        val before = snapshotGraph()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER change_duplicate_identity_after_transfer
            AFTER INSERT ON PlaylistItemCrossRef
            WHEN NEW.playlistId = $duplicatePlaylistId AND NEW.historyItemId = $retainedId
            BEGIN UPDATE history SET url = 'https://youtu.be/anotherVideo' WHERE id = $duplicateId; END
            """.trimIndent(),
        )

        val failure = runCatching { assignments.deleteDuplicateHistoryGroups(candidates) }.exceptionOrNull()

        assertTrue(failureMessages(failure).contains("History duplicate identity changed during cleanup"))
        assertEquals(before, snapshotGraph())
    }

    private data class GraphSnapshot(
        val history: List<HistoryItem>,
        val assignments: List<HistoryKeywordAssignment>,
        val memberships: List<PlaylistItemCrossRef>,
        val playlists: List<Playlist>,
    )

    private suspend fun snapshotGraph() = GraphSnapshot(
        database.historyDao.getAllDownloaded().sortedBy { it.id },
        database.automaticKeywordRuleDao.getAllAssignmentsRaw(),
        database.playlistDao.getAllPlaylistItems().sortedWith(compareBy<PlaylistItemCrossRef> { it.playlistId }.thenBy { it.historyItemId }),
        database.playlistDao.getAllPlaylistsSync().sortedBy { it.id },
    )

    private suspend fun playlistIds(historyId: Long): Set<Long> =
        database.playlistDao.getPlaylistItemsForHistory(historyId).map { it.playlistId }.toSet()

    private suspend fun addPlaylist(name: String, historyId: Long): Long {
        val id = database.playlistDao.insertPlaylist(Playlist(name = name, description = null))
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(id, historyId))
        return id
    }

    private suspend fun addDuplicate(keyword: String, time: Long): Long {
        val id = database.historyDao.insertAndGetIdRaw(history(0, "https://youtu.be/dQw4w9WgXcQ?t=30", "Another title", time))
        assignments.replaceManualKeywords(id, listOf(keyword))
        return id
    }

    private suspend fun assertCollapsed(retainedId: Long, removedIds: List<Long>, expectedPlaylists: Set<Long>, expectedKeywords: Set<String>) {
        assertEquals(setOf(retainedId), database.historyDao.getAllDownloaded().map { it.id }.toSet())
        assertEquals(expectedPlaylists, playlistIds(retainedId))
        val refs = database.playlistDao.getPlaylistItemsForHistory(retainedId)
        assertEquals(expectedPlaylists.size, refs.size)
        assertEquals(expectedKeywords, database.automaticKeywordRuleDao.getAssignmentsRaw(retainedId).map { it.keyword }.toSet())
        val materialized = database.historyDao.getItem(retainedId).keywords
        expectedKeywords.forEach { assertTrue(materialized.contains(it)) }
        removedIds.forEach { id ->
            assertNull(database.historyDao.getNullableItem(id))
            assertTrue(database.playlistDao.getPlaylistItemsForHistory(id).isEmpty())
            assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(id).isEmpty())
        }
    }

    private fun failureMessages(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.joinToString(" ") { it.message.orEmpty() }

    private suspend fun seedDuplicatePair(sharedPlaylist: Boolean = true): Triple<Long, Long, Long> {
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
        val duplicatePlaylistId = if (sharedPlaylist) playlistId else
            database.playlistDao.insertPlaylist(Playlist(name = "Duplicate-only", description = null))
        database.playlistDao.insertPlaylistItems(
            listOf(
                PlaylistItemCrossRef(playlistId, retainedId),
                PlaylistItemCrossRef(duplicatePlaylistId, duplicateId),
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
