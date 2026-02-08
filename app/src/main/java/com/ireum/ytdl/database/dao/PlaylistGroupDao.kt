package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ireum.ytdl.database.models.PlaylistGroup
import com.ireum.ytdl.database.models.PlaylistGroupMember
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistGroupDao {
    @Query("SELECT * FROM playlist_groups ORDER BY name ASC")
    fun getGroupsFlow(): Flow<List<PlaylistGroup>>

    @Query("SELECT * FROM playlist_groups ORDER BY name ASC")
    fun getGroups(): List<PlaylistGroup>

    @Query("SELECT * FROM playlist_groups WHERE name = :name LIMIT 1")
    fun getGroupByName(name: String): PlaylistGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroup(group: PlaylistGroup): Long

    @Update
    fun updateGroup(group: PlaylistGroup)

    @Query("DELETE FROM playlist_groups WHERE id = :groupId")
    fun deleteGroup(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMembers(members: List<PlaylistGroupMember>)

    @Query("SELECT * FROM playlist_group_members")
    fun getAllMembersFlow(): Flow<List<PlaylistGroupMember>>

    @Query("SELECT playlistId FROM playlist_group_members WHERE groupId = :groupId")
    fun getMembersForGroupFlow(groupId: Long): Flow<List<Long>>

    @Query("DELETE FROM playlist_group_members WHERE groupId = :groupId")
    fun deleteMembersByGroup(groupId: Long)

    @Query("DELETE FROM playlist_group_members WHERE groupId = :groupId AND playlistId IN (:playlistIds)")
    fun deleteMembersByGroupAndPlaylists(groupId: Long, playlistIds: List<Long>)

    @Query("DELETE FROM playlist_group_members WHERE playlistId = :playlistId")
    fun deleteMembersByPlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_group_members WHERE playlistId = :playlistId AND groupId NOT IN (:groupIds)")
    fun deleteMembersForPlaylistNotIn(playlistId: Long, groupIds: List<Long>)

    @Query("SELECT groupId FROM playlist_group_members WHERE playlistId = :playlistId")
    fun getGroupIdsForPlaylist(playlistId: Long): List<Long>
}
