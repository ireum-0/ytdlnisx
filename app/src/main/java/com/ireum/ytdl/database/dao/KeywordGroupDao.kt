package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ireum.ytdl.database.models.KeywordGroup
import com.ireum.ytdl.database.models.KeywordGroupMember
import kotlinx.coroutines.flow.Flow

@Dao
interface KeywordGroupDao {
    @Query("SELECT * FROM keyword_groups ORDER BY name ASC")
    fun getGroupsFlow(): Flow<List<KeywordGroup>>

    @Query("SELECT * FROM keyword_groups ORDER BY name ASC")
    fun getGroups(): List<KeywordGroup>

    @Query("SELECT * FROM keyword_groups WHERE name = :name LIMIT 1")
    fun getGroupByName(name: String): KeywordGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroup(group: KeywordGroup): Long

    @Update
    fun updateGroup(group: KeywordGroup)

    @Query("DELETE FROM keyword_groups WHERE id = :groupId")
    fun deleteGroup(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMembers(members: List<KeywordGroupMember>)

    @Query("SELECT * FROM keyword_group_members")
    fun getAllMembersFlow(): Flow<List<KeywordGroupMember>>

    @Query("SELECT * FROM keyword_group_members")
    fun getAllMembers(): List<KeywordGroupMember>

    @Query("SELECT keyword FROM keyword_group_members WHERE groupId = :groupId")
    fun getMembersForGroupFlow(groupId: Long): Flow<List<String>>

    @Query("DELETE FROM keyword_group_members WHERE groupId = :groupId")
    fun deleteMembersByGroup(groupId: Long)

    @Query("DELETE FROM keyword_group_members WHERE groupId = :groupId AND keyword IN (:keywords)")
    fun deleteMembersByGroupAndKeywords(groupId: Long, keywords: List<String>)

    @Query("DELETE FROM keyword_group_members WHERE keyword = :keyword")
    fun deleteMembersByKeyword(keyword: String)

    @Query("DELETE FROM keyword_group_members WHERE keyword = :keyword AND groupId NOT IN (:groupIds)")
    fun deleteMembersForKeywordNotIn(keyword: String, groupIds: List<Long>)

    @Query("SELECT groupId FROM keyword_group_members WHERE keyword = :keyword")
    fun getGroupIdsForKeyword(keyword: String): List<Long>

    @Query("DELETE FROM keyword_group_members")
    fun clearMembers()

    @Query("DELETE FROM keyword_groups")
    fun clearGroups()
}

