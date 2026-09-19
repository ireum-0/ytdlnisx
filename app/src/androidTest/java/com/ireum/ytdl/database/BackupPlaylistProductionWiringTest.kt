package com.ireum.ytdl.database

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistGroup
import com.ireum.ytdl.database.models.PlaylistGroupMember
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises playlist and playlist-group backup/restore through SettingsViewModel. */
@RunWith(AndroidJUnit4::class)
class BackupPlaylistProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private var publishedBackup: String? = null
    private var backupDestination: File? = null

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = DBManager.getInstance(context)
            clearState()
            backupDestination = File(
                requireNotNull(context.getExternalFilesDir(null)),
                "backup-playlist-test",
            ).apply { mkdirs() }
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove("cache_path")
                .putString("backup_path", backupDestination!!.absolutePath)
                .commit()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            publishedBackup?.let { File(it).delete() }
            backupDestination?.listFiles()?.forEach(File::delete)
            clearState()
        }
    }

    @Test
    fun playlistOnlySelectionIncludesHistoryAuthorityAndRoundTripsThroughProductionParser() = runBlocking {
        val historyId = database.historyDao.insertAndGetIdRaw(history(0L, "https://example.com/captured"))
        val playlistId = database.playlistDao.insertPlaylist(Playlist(name = "Captured", description = "graph"))
        val groupId = database.playlistGroupDao.insertGroup(PlaylistGroup(name = "Captured group"))
        database.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlistId, historyId))
        database.playlistGroupDao.insertMembers(listOf(PlaylistGroupMember(groupId, playlistId)))

        val result = SettingsViewModel(context as Application).backup(listOf("playlistData"))
        assertTrue(result.isSuccess)
        publishedBackup = result.getOrThrow()
        val json = JsonParser.parseString(File(publishedBackup!!).readText()).asJsonObject
        assertEquals(4, json["backup_format_version"].asInt)
        assertTrue(json.has("downloads"))
        assertEquals(1, json["playlists"].asJsonArray.size())
        assertEquals(1, json["playlist_item_cross_refs"].asJsonArray.size())
        assertEquals(1, json["playlist_groups"].asJsonArray.size())
        assertEquals(1, json["playlist_group_members"].asJsonArray.size())

        // Parse the serialized artifact through the same production helper
        // used by MainSettingsFragment before handing it to restoreData().
        val playlistPayload = BackupRestoreParser.parsePlaylistPayload(json, Gson())
        val imported = RestoreAppDataItem(
            downloads = json["downloads"].asJsonArray.map {
                Gson().fromJson(it, HistoryItem::class.java)
            },
            playlists = playlistPayload.playlists,
            playlistItemCrossRefs = playlistPayload.playlistItemCrossRefs,
            playlistGroups = playlistPayload.playlistGroups,
            playlistGroupMembers = playlistPayload.playlistGroupMembers,
        )
        clearState()
        assertTrue(SettingsViewModel(context as Application).restoreData(imported, context))
        val restoredHistory = database.historyDao.getAll().single()
        val restoredPlaylist = database.playlistDao.getAllPlaylistsSync().single()
        val restoredGroup = database.playlistGroupDao.getGroups().single()
        assertEquals(
            listOf(PlaylistItemCrossRef(restoredPlaylist.id, restoredHistory.id)),
            database.playlistDao.getAllPlaylistItems(),
        )
        assertEquals(
            listOf(PlaylistGroupMember(restoredGroup.id, restoredPlaylist.id)),
            database.playlistGroupDao.getAllMembers(),
        )
    }

    @Test
    fun restoreUsesExplicitPlaylistHistoryAndGroupMaps() = runBlocking {
        val oldHistoryId = 41L
        val oldPlaylistId = 11L
        val oldGroupId = 20L
        database.historyDao.insertRaw(history(oldHistoryId, "https://example.com/unrelated"))
        database.playlistDao.insertPlaylist(Playlist(oldPlaylistId, "unrelated destination", "collision"))
        database.playlistGroupDao.insertGroup(PlaylistGroup(oldGroupId, "unrelated destination group"))

        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                downloads = listOf(history(oldHistoryId, "https://example.com/imported")),
                playlists = listOf(
                    Playlist(oldPlaylistId, "Imported A", "one"),
                    Playlist(12L, "Imported A", "two"),
                ),
                playlistItemCrossRefs = listOf(
                    PlaylistItemCrossRef(oldPlaylistId, oldHistoryId),
                    PlaylistItemCrossRef(12L, oldHistoryId),
                    PlaylistItemCrossRef(999L, oldHistoryId),
                    PlaylistItemCrossRef(oldPlaylistId, 999L),
                ),
                playlistGroups = listOf(PlaylistGroup(oldGroupId, "Imported group")),
                playlistGroupMembers = listOf(
                    PlaylistGroupMember(oldGroupId, oldPlaylistId),
                    PlaylistGroupMember(oldGroupId, 12L),
                    PlaylistGroupMember(oldGroupId, 999L),
                    PlaylistGroupMember(999L, oldPlaylistId),
                ),
            ),
            context,
        )

        assertTrue(success)
        val importedHistory = database.historyDao.getAll().single { it.url.endsWith("/imported") }
        assertNotEquals(oldHistoryId, importedHistory.id)
        val importedPlaylists = database.playlistDao.getAllPlaylistsSync()
            .filter { it.name == "Imported A" }
        assertEquals(2, importedPlaylists.size)
        assertTrue(importedPlaylists.all { it.id != oldPlaylistId })
        assertEquals(
            setOf(importedHistory.id),
            database.playlistDao.getAllPlaylistItems()
                .filter { it.playlistId in importedPlaylists.map(Playlist::id) }
                .map(PlaylistItemCrossRef::historyItemId)
                .toSet(),
        )
        val importedGroup = database.playlistGroupDao.getGroupByName("Imported group")!!
        assertNotEquals(oldGroupId, importedGroup.id)
        assertEquals(
            importedPlaylists.map(Playlist::id).toSet(),
            database.playlistGroupDao.getAllMembers()
                .filter { it.groupId == importedGroup.id }
                .map(PlaylistGroupMember::playlistId)
                .toSet(),
        )
        assertTrue(database.playlistDao.getAllPlaylistItems().none { it.historyItemId == 999L })
        assertTrue(database.playlistGroupDao.getAllMembers().none { it.playlistId == 999L || it.groupId == 999L })
    }

    @Test
    fun sameNamePlaylistsRemainDistinctAndRepeatedMergeUsesFreshIds() = runBlocking {
        val payload = RestoreAppDataItem(
            playlists = listOf(
                Playlist(7L, "Same name", "first"),
                Playlist(8L, "Same name", "second"),
            ),
            playlistGroups = listOf(PlaylistGroup(3L, "Shared group")),
            playlistGroupMembers = listOf(
                PlaylistGroupMember(3L, 7L),
                PlaylistGroupMember(3L, 8L),
            ),
        )

        assertTrue(SettingsViewModel(context as Application).restoreData(payload, context))
        assertTrue(SettingsViewModel(context as Application).restoreData(payload, context))

        val playlists = database.playlistDao.getAllPlaylistsSync().filter { it.name == "Same name" }
        assertEquals(4, playlists.size)
        assertEquals(4, playlists.map(Playlist::id).distinct().size)
        assertEquals(1, database.playlistGroupDao.getGroups().count { it.name == "Shared group" })
        val group = database.playlistGroupDao.getGroupByName("Shared group")!!
        assertEquals(4, database.playlistGroupDao.getAllMembers().count { it.groupId == group.id })
    }

    @Test
    fun resetClearsOldRelationshipRowsBeforeImportingNewGraph() = runBlocking {
        val oldPlaylistId = database.playlistDao.insertPlaylist(Playlist(name = "Old", description = null))
        val oldGroupId = database.playlistGroupDao.insertGroup(PlaylistGroup(name = "Old group"))
        database.playlistGroupDao.insertMembers(listOf(PlaylistGroupMember(oldGroupId, oldPlaylistId)))

        assertTrue(
            SettingsViewModel(context as Application).restoreData(
                RestoreAppDataItem(
                    playlists = listOf(Playlist(90L, "New", null)),
                    playlistGroups = listOf(PlaylistGroup(91L, "New group")),
                    playlistGroupMembers = listOf(PlaylistGroupMember(91L, 90L)),
                ),
                context,
                resetData = true,
            ).isCompleted()
        )

        assertTrue(database.playlistDao.getAllPlaylistsSync().none { it.name == "Old" })
        assertTrue(database.playlistGroupDao.getGroups().none { it.name == "Old group" })
        val newPlaylist = database.playlistDao.getAllPlaylistsSync().single { it.name == "New" }
        val newGroup = database.playlistGroupDao.getGroupByName("New group")!!
        assertEquals(listOf(PlaylistGroupMember(newGroup.id, newPlaylist.id)), database.playlistGroupDao.getAllMembers())
    }

    private suspend fun clearState() {
        database.playlistDao.clearPlaylistItems()
        database.playlistGroupDao.clearMembers()
        database.playlistGroupDao.clearGroups()
        database.playlistDao.clearPlaylists()
        database.historyDao.nuke()
    }

    private fun history(id: Long, url: String) = HistoryItem(
        id = id,
        url = url,
        title = "History ${url.substringAfterLast('/')}\n",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = 1L,
        downloadPath = listOf(context.filesDir.absolutePath),
        website = "Other",
        format = Format(),
        downloadId = 0L,
    )
}
