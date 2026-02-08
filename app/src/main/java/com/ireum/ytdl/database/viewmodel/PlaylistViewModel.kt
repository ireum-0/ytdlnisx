package com.ireum.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: PlaylistRepository

    val allPlaylists: Flow<List<Playlist>>

    init {
        val db = DBManager.getInstance(application)
        repository = PlaylistRepository(db.playlistDao, db.playlistGroupDao)
        allPlaylists = repository.getAllPlaylists()
    }

    fun insertPlaylist(playlist: Playlist, callback: (Long) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val id = repository.insertPlaylist(playlist)
        callback(id)
    }

    fun insertPlaylistItem(playlistId: Long, historyItemId: Long) = viewModelScope.launch(Dispatchers.IO) {
        repository.insertPlaylistItem(playlistId, historyItemId)
    }

    fun renamePlaylist(playlistId: Long, name: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.renamePlaylist(playlistId, name)
    }

    fun removePlaylistItems(playlistId: Long, historyItemIds: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        repository.removePlaylistItems(playlistId, historyItemIds)
    }

    suspend fun getCommonPlaylistIds(historyItemIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        repository.getCommonPlaylistIds(historyItemIds)
    }

    fun applyPlaylistSelections(
        historyItemIds: List<Long>,
        addPlaylistIds: List<Long>,
        removePlaylistIds: List<Long>,
        onDone: (() -> Unit)? = null
    ) = viewModelScope.launch(Dispatchers.IO) {
        addPlaylistIds.forEach { playlistId ->
            repository.insertPlaylistItems(playlistId, historyItemIds)
        }
        removePlaylistIds.forEach { playlistId ->
            repository.removePlaylistItems(playlistId, historyItemIds)
        }
        if (onDone != null) {
            withContext(Dispatchers.Main) { onDone.invoke() }
        }
    }

    fun deletePlaylist(playlistId: Long) = viewModelScope.launch(Dispatchers.IO) {
        repository.deletePlaylist(playlistId)
    }
}

