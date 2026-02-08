package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ireum.ytdl.database.models.YoutuberMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface YoutuberMetaDao {
    @Query("SELECT * FROM youtuber_meta")
    fun getAllFlow(): Flow<List<YoutuberMeta>>

    @Query("SELECT * FROM youtuber_meta")
    fun getAll(): List<YoutuberMeta>

    @Query("SELECT * FROM youtuber_meta WHERE author = :author LIMIT 1")
    suspend fun getByAuthor(author: String): YoutuberMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: YoutuberMeta)

    @Query("DELETE FROM youtuber_meta WHERE author = :author")
    suspend fun deleteByAuthor(author: String)

    @Query("DELETE FROM youtuber_meta")
    suspend fun clearAll()
}
