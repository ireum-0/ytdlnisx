package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistInfo
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItem(playlistItem: PlaylistItemCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItems(playlistItems: List<PlaylistItemCrossRef>)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM PlaylistItemCrossRef WHERE playlistId = :playlistId AND historyItemId IN (:historyItemIds)")
    suspend fun deletePlaylistItems(playlistId: Long, historyItemIds: List<Long>)

    @Query("DELETE FROM PlaylistItemCrossRef WHERE historyItemId IN (:historyItemIds)")
    suspend fun deletePlaylistItemsByHistoryIds(historyItemIds: List<Long>)

    @Query("DELETE FROM PlaylistItemCrossRef WHERE playlistId = :playlistId")
    suspend fun deletePlaylistItemsByPlaylistId(playlistId: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM PlaylistItemCrossRef")
    suspend fun clearPlaylistItems()

    @Query(
        "SELECT playlistId FROM PlaylistItemCrossRef " +
            "WHERE historyItemId IN (:historyItemIds) " +
            "GROUP BY playlistId " +
            "HAVING COUNT(DISTINCT historyItemId) = :requiredCount"
    )
    suspend fun getCommonPlaylistIdsForHistoryItems(historyItemIds: List<Long>, requiredCount: Int): List<Long>

    @Transaction
    @Query("SELECT h.* FROM history h INNER JOIN PlaylistItemCrossRef p ON h.id = p.historyItemId WHERE p.playlistId = :playlistId")
    fun getPlaylistWithHistoryItems(playlistId: Long): Flow<List<HistoryItem>>

    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists")
    fun getAllPlaylistsSync(): List<Playlist>

    @Query("SELECT * FROM PlaylistItemCrossRef")
    fun getAllPlaylistItems(): List<PlaylistItemCrossRef>

    @Query("DELETE FROM playlists")
    fun clearPlaylists()

    @Query(
        "SELECT p.id, p.name, p.description, " +
            "COUNT(pc.historyItemId) as itemCount, " +
            "(SELECT h.thumb FROM history h " +
                "INNER JOIN PlaylistItemCrossRef pc2 ON h.id = pc2.historyItemId " +
                "WHERE pc2.playlistId = p.id ORDER BY h.time DESC LIMIT 1) as thumbnail " +
            "FROM playlists p LEFT JOIN PlaylistItemCrossRef pc ON p.id = pc.playlistId " +
            "GROUP BY p.id ORDER BY p.name ASC"
    )
    fun getPlaylistsWithInfo(): Flow<List<PlaylistInfo>>

    @Query(
        "SELECT p.id, p.name, p.description, " +
            "COUNT(pc.historyItemId) as itemCount, " +
            "(SELECT h.thumb FROM history h " +
                "INNER JOIN PlaylistItemCrossRef pc2 ON h.id = pc2.historyItemId " +
                "WHERE pc2.playlistId = p.id AND pc2.historyItemId IN (:historyItemIds) " +
                "ORDER BY h.time DESC LIMIT 1) as thumbnail " +
            "FROM playlists p " +
            "INNER JOIN PlaylistItemCrossRef pc ON p.id = pc.playlistId " +
            "WHERE pc.historyItemId IN (:historyItemIds) " +
            "GROUP BY p.id ORDER BY p.name ASC"
    )
    suspend fun getPlaylistsWithInfoForHistoryIds(historyItemIds: List<Long>): List<PlaylistInfo>

    @Query(
        "SELECT p.id, p.name, p.description, " +
            "COUNT(pc.historyItemId) as itemCount, " +
            "(SELECT h.thumb FROM history h " +
                "INNER JOIN PlaylistItemCrossRef pc2 ON h.id = pc2.historyItemId " +
                "WHERE pc2.playlistId = p.id ORDER BY h.time DESC LIMIT 1) as thumbnail " +
            "FROM playlists p " +
            "LEFT JOIN PlaylistItemCrossRef pc ON p.id = pc.playlistId " +
            "LEFT JOIN history h2 ON h2.id = pc.historyItemId " +
            "GROUP BY p.id " +
            "ORDER BY MAX(h2.time) DESC " +
            "LIMIT :limit"
    )
    fun getRecentPlaylistsWithInfo(limit: Int): Flow<List<PlaylistInfo>>

    @Query(
        "SELECT p.id, p.name, p.description, " +
            "COUNT(pc.historyItemId) as itemCount, " +
            "(SELECT h.thumb FROM history h " +
                "INNER JOIN PlaylistItemCrossRef pc2 ON h.id = pc2.historyItemId " +
                "WHERE pc2.playlistId = p.id ORDER BY h.time DESC LIMIT 1) as thumbnail " +
            "FROM playlists p " +
            "INNER JOIN PlaylistItemCrossRef pc ON p.id = pc.playlistId " +
            "INNER JOIN history h ON h.id = pc.historyItemId " +
            "GROUP BY p.id " +
            "HAVING COUNT(pc.historyItemId) > 0 AND " +
            "SUM(CASE WHEN (h.author = :author OR " +
                "h.author LIKE :author || ',%' OR h.author LIKE :author || ' /%' OR h.author LIKE :author || '/%' OR " +
                "h.author LIKE '%,' || :author || '%' OR h.author LIKE '%, ' || :author || '%' OR " +
                "h.author LIKE '%/' || :author || '%' OR h.author LIKE '%/ ' || :author || '%') " +
            "THEN 1 ELSE 0 END) = COUNT(pc.historyItemId) " +
            "ORDER BY p.name ASC"
    )
    fun getPlaylistsWithInfoByAuthor(author: String): Flow<List<PlaylistInfo>>
}

