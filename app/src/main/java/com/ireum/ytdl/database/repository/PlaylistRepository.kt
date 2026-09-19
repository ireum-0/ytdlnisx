package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.dao.PlaylistDao
import com.ireum.ytdl.database.dao.PlaylistGroupDao
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.models.PlaylistInfo
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val playlistGroupDao: PlaylistGroupDao,
    private val context: android.content.Context? = null,
) {
    private companion object {
        const val ID_BATCH_SIZE = 800
    }

    private fun ensureRestoreAdmission() {
        val app = context?.applicationContext
            ?: runCatching { com.ireum.ytdl.App.instance }.getOrNull()
            ?: return
        check(!com.ireum.ytdl.database.RestoreGate.isRestoreInProgress(app)) {
            "Restore transaction is active"
        }
    }

    fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists()
    }

    fun getPlaylistsWithInfo(): Flow<List<PlaylistInfo>> {
        return playlistDao.getPlaylistsWithInfo()
    }

    fun getRecentPlaylistsWithInfo(limit: Int): Flow<List<PlaylistInfo>> {
        return playlistDao.getRecentPlaylistsWithInfo(limit)
    }

    fun getPlaylistsWithInfoByAuthor(author: String): Flow<List<PlaylistInfo>> {
        return playlistDao.getPlaylistsWithInfoByAuthor(author)
    }

    suspend fun getPlaylistsWithInfoForHistoryIds(historyItemIds: List<Long>): List<PlaylistInfo> {
        if (historyItemIds.isEmpty()) return emptyList()
        val merged = linkedMapOf<Long, PlaylistInfo>()
        historyItemIds.chunked(ID_BATCH_SIZE).forEach { batch ->
            playlistDao.getPlaylistsWithInfoForHistoryIds(batch).forEach { info ->
                val previous = merged[info.id]
                if (previous == null) {
                    merged[info.id] = info
                } else {
                    merged[info.id] = previous.copy(
                        itemCount = previous.itemCount + info.itemCount,
                        thumbnail = previous.thumbnail ?: info.thumbnail
                    )
                }
            }
        }
        return merged.values.sortedBy { it.name.lowercase() }
    }

    suspend fun insertPlaylist(playlist: Playlist): Long {
        ensureRestoreAdmission()
        return playlistDao.insertPlaylist(playlist)
    }

    suspend fun insertPlaylistItem(playlistId: Long, historyItemId: Long) {
        ensureRestoreAdmission()
        val crossRef = PlaylistItemCrossRef(playlistId, historyItemId)
        playlistDao.insertPlaylistItem(crossRef)
    }

    suspend fun insertPlaylistItems(playlistId: Long, historyItemIds: List<Long>) {
        ensureRestoreAdmission()
        if (historyItemIds.isEmpty()) return
        val refs = historyItemIds.map { historyItemId ->
            PlaylistItemCrossRef(playlistId, historyItemId)
        }
        playlistDao.insertPlaylistItems(refs)
    }

    suspend fun renamePlaylist(playlistId: Long, name: String) {
        ensureRestoreAdmission()
        playlistDao.renamePlaylist(playlistId, name)
    }

    suspend fun removePlaylistItems(playlistId: Long, historyItemIds: List<Long>) {
        ensureRestoreAdmission()
        playlistDao.deletePlaylistItems(playlistId, historyItemIds)
    }

    suspend fun removePlaylistItemsByHistoryIds(historyItemIds: List<Long>) {
        ensureRestoreAdmission()
        playlistDao.deletePlaylistItemsByHistoryIds(historyItemIds)
    }

    suspend fun clearPlaylistItems() {
        ensureRestoreAdmission()
        playlistDao.clearPlaylistItems()
    }

    suspend fun getCommonPlaylistIds(historyItemIds: List<Long>): List<Long> {
        if (historyItemIds.isEmpty()) return emptyList()
        return playlistDao.getCommonPlaylistIdsForHistoryItems(historyItemIds, historyItemIds.size)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        ensureRestoreAdmission()
        playlistDao.deletePlaylistItemsByPlaylistId(playlistId)
        playlistDao.deletePlaylist(playlistId)
        playlistGroupDao.deleteMembersByPlaylist(playlistId)
    }
}

