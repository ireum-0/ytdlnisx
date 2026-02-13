package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ireum.ytdl.database.models.YoutuberGroup
import com.ireum.ytdl.database.models.YoutuberGroupMember
import com.ireum.ytdl.database.models.YoutuberGroupRelation
import kotlinx.coroutines.flow.Flow

@Dao
interface YoutuberGroupDao {

    @Query("SELECT * FROM youtuber_groups ORDER BY name ASC")
    fun getGroupsFlow(): Flow<List<YoutuberGroup>>

    @Query("SELECT * FROM youtuber_groups ORDER BY name ASC")
    fun getGroups(): List<YoutuberGroup>

    @Query("SELECT * FROM youtuber_groups WHERE name = :name LIMIT 1")
    fun getGroupByName(name: String): YoutuberGroup?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertGroup(group: YoutuberGroup): Long

    @Update
    fun updateGroup(group: YoutuberGroup)

    @Query("DELETE FROM youtuber_groups WHERE id = :groupId")
    fun deleteGroup(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMembers(members: List<YoutuberGroupMember>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRelations(relations: List<YoutuberGroupRelation>)

    @Query("DELETE FROM youtuber_group_members WHERE groupId = :groupId")
    fun deleteMembersByGroup(groupId: Long)

    @Query("DELETE FROM youtuber_group_relations WHERE parentGroupId = :groupId OR childGroupId = :groupId")
    fun deleteRelationsByGroup(groupId: Long)

    @Query("DELETE FROM youtuber_group_members WHERE groupId = :groupId AND author IN (:authors)")
    fun deleteMembersByGroupAndAuthors(groupId: Long, authors: List<String>)

    @Query("DELETE FROM youtuber_group_members WHERE author = :author")
    fun deleteMembersForAuthor(author: String)

    @Query("DELETE FROM youtuber_group_members WHERE author = :author AND groupId NOT IN (:groupIds)")
    fun deleteMembersForAuthorNotIn(author: String, groupIds: List<Long>)

    @Query("SELECT groupId FROM youtuber_group_members WHERE author = :author")
    fun getGroupIdsForAuthor(author: String): List<Long>

    @Query("SELECT * FROM youtuber_group_members")
    fun getAllMembersFlow(): Flow<List<YoutuberGroupMember>>

    @Query("SELECT * FROM youtuber_group_members")
    fun getAllMembers(): List<YoutuberGroupMember>

    @Query("SELECT * FROM youtuber_group_relations")
    fun getAllRelationsFlow(): Flow<List<YoutuberGroupRelation>>

    @Query("SELECT * FROM youtuber_group_relations")
    fun getAllRelations(): List<YoutuberGroupRelation>

    @Query("SELECT parentGroupId FROM youtuber_group_relations WHERE childGroupId = :childGroupId")
    fun getParentIdsForChild(childGroupId: Long): List<Long>

    @Query("DELETE FROM youtuber_group_relations WHERE childGroupId = :childGroupId")
    fun deleteRelationsForChild(childGroupId: Long)

    @Query("DELETE FROM youtuber_group_relations WHERE childGroupId = :childGroupId AND parentGroupId NOT IN (:parentGroupIds)")
    fun deleteRelationsForChildNotIn(childGroupId: Long, parentGroupIds: List<Long>)

    @Query("SELECT author FROM youtuber_group_members WHERE groupId = :groupId")
    fun getMembersForGroupFlow(groupId: Long): Flow<List<String>>

    @Query("DELETE FROM youtuber_group_members")
    fun clearMembers()

    @Query("DELETE FROM youtuber_group_relations")
    fun clearRelations()

    @Query("DELETE FROM youtuber_groups")
    fun clearGroups()
}
