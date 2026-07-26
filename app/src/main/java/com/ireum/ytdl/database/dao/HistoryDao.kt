package com.ireum.ytdl.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.YoutuberInfo
import com.ireum.ytdl.util.storage.HistoryDeletionReferenceRecord
import com.ireum.ytdl.util.storage.HistoryDeletionCandidateRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY time DESC")
    fun getAll(): List<HistoryItem>

    @Query("SELECT COUNT(*) FROM history")
    fun getCount(): Int

    @Query("SELECT id FROM history")
    fun getAllIds(): List<Long>

    @Query("SELECT id, downloadPath FROM history")
    fun getDeletionReferenceRecords(): List<HistoryDeletionReferenceRecord>

    @Query("SELECT id, downloadPath FROM history WHERE id IN (:ids)")
    fun getDeletionReferenceRecordsByIds(ids: List<Long>): List<HistoryDeletionReferenceRecord>

    @Query("SELECT id, downloadPath, localTreeUri, localTreePath FROM history")
    fun getDeletionCandidateRecords(): List<HistoryDeletionCandidateRecord>

    @Query("SELECT id, downloadPath, localTreeUri, localTreePath FROM history WHERE id IN (:ids)")
    fun getDeletionCandidateRecordsByIds(ids: List<Long>): List<HistoryDeletionCandidateRecord>

    @Query("SELECT id FROM history WHERE id IN (:ids)")
    fun getExistingIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM history")
    fun observeAll(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history ORDER BY CASE WHEN lastWatched > 0 THEN lastWatched ELSE time END DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history WHERE id = :id")
    fun getItem(id: Long): HistoryItem

    @Query("SELECT * FROM history WHERE url = :url")
    fun getItem(url: String): HistoryItem?

    @Query("SELECT * FROM history WHERE downloadPath LIKE '%' || :path || '%' ESCAPE '\\' LIMIT 1")
    fun getItemByDownloadPath(path: String): HistoryItem?

    @Query("SELECT * FROM history WHERE downloadId = :downloadId LIMIT 1")
    fun getItemByDownloadId(downloadId: Long): HistoryItem?

    @Query("SELECT * FROM history WHERE localTreeUri = :treeUri AND localTreePath = :treePath LIMIT 1")
    fun getItemByLocalTree(treeUri: String, treePath: String): HistoryItem?

    @Query("SELECT * FROM history WHERE url = :url")
    fun getItemsByUrl(url: String): List<HistoryItem>

    @Query("SELECT * FROM history WHERE url IN (:urls)")
    fun getItemsByUrls(urls: List<String>): List<HistoryItem>

    @Query("SELECT * FROM history WHERE customThumb = '' AND (thumb LIKE 'http://%' OR thumb LIKE 'https://%') ORDER BY time DESC LIMIT :limit")
    fun getItemsWithRemoteThumb(limit: Int): List<HistoryItem>

    @Query("SELECT thumb FROM history WHERE id = :id")
    fun getThumb(id: Long): String

    @Query("SELECT * FROM history")
    fun getAllDownloaded(): List<HistoryItem>

    @Query("SELECT * FROM history WHERE url = :url")
    fun getByUrlAndFormat(url: String): HistoryItem?

    @get:Query("SELECT DISTINCT author FROM history WHERE author != '' ORDER BY author ASC")
    val authors: Flow<List<String>>

    @get:Query("SELECT DISTINCT website FROM history WHERE website != '' ORDER BY website ASC")
    val websites: Flow<List<String>>

    @Query("SELECT author, COUNT(id) as videoCount, " +
        "(SELECT COALESCE(NULLIF(customThumb, ''), thumb) FROM history WHERE author = h.author ORDER BY time DESC LIMIT 1) as thumbnail, " +
        "MAX(time) as lastTime, " +
        "IFNULL(SUM(filesize), 0) as totalSize, " +
        "MIN(time) as firstTime " +
        "FROM history h WHERE author != '' GROUP BY author ORDER BY author ASC")
    fun getAuthorsWithInfo(): Flow<List<YoutuberInfo>>

    @Query("SELECT * FROM history WHERE type = 'video' ORDER BY time DESC")
    fun getAllVideos(): List<HistoryItem>

    @Query("SELECT * FROM history WHERE type = 'video' AND hardSubScanRemoved = 0 AND hardSubDone = 0 ORDER BY time DESC")
    fun getHardSubScanCandidates(): List<HistoryItem>

    @Query(
        "SELECT * FROM history WHERE type = 'video' AND (" +
            "author = :author OR " +
            "author LIKE :author || ',%' OR author LIKE :author || ' /%' OR author LIKE :author || '/%' OR " +
            "author LIKE '%,' || :author || '%' OR author LIKE '%, ' || :author || '%' OR " +
            "author LIKE '%/' || :author || '%' OR author LIKE '%/ ' || :author || '%' " +
        ") ORDER BY time DESC"
    )
    fun getVideosByAuthor(author: String): List<HistoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRaw(item: HistoryItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAndGetIdRaw(item: HistoryItem): Long

    /**
     * Compatibility writes cannot change the materialized keyword projection.
     * Keyword changes must go through HistoryKeywordAssignmentRepository.
     */
    @Transaction
    fun update(item: HistoryItem) {
        val materialized = getItem(item.id).keywords
        updateRaw(item.copy(keywords = materialized))
    }

    @Update
    fun updateRaw(item: HistoryItem)

    @Query("UPDATE history SET playbackPositionMs = :positionMs WHERE id = :id")
    fun updatePlaybackPosition(id: Long, positionMs: Long)

    @Query("UPDATE history SET lastWatched = :time WHERE id = :id")
    fun updateLastWatched(id: Long, time: Long)

    @Query("UPDATE history SET keywords = :keywords WHERE id = :id")
    suspend fun updateKeywordsMaterialized(id: Long, keywords: String)

    @Query("UPDATE history SET hardSubScanRemoved = :removed, hardSubDone = :done WHERE id = :id")
    fun updateHardSubScanState(id: Long, removed: Boolean, done: Boolean)

    @Query("UPDATE history SET hardSubScanRemoved = :removed WHERE id IN (:ids) AND type = 'video' AND hardSubDone = 0")
    fun updateHardSubScanRemovedForIds(ids: List<Long>, removed: Boolean)

    @Query("UPDATE history SET hardSubScanRemoved = :done, hardSubDone = :done WHERE id IN (:ids) AND type = 'video'")
    fun updateHardSubDoneForIds(ids: List<Long>, done: Boolean): Int

    @Query("UPDATE history SET hardSubScanRemoved = 0, hardSubDone = 0 WHERE type = 'video' AND hardSubDone = 1")
    fun resetHardSubDoneForRescan()

    @Delete
    fun delete(item: HistoryItem)

    @Query("DELETE FROM history WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM history")
    fun nuke()

    @Query("SELECT * FROM history WHERE title LIKE :query OR author LIKE :query ORDER BY time DESC")
    fun getPaginatedSource(query: String): PagingSource<Int, HistoryItem>

    @RawQuery(observedEntities = [HistoryItem::class])
    fun getPaginatedSource(query: SupportSQLiteQuery): PagingSource<Int, HistoryItem>

    @Query("SELECT * FROM history WHERE (:query = '' OR title LIKE :query OR author LIKE :query) AND (:type = '' OR type = :type) AND (:author = '' OR author = :author OR author LIKE :author || ',%' OR author LIKE :author || ' /%' OR author LIKE :author || '/%' OR author LIKE '%,' || :author || '%' OR author LIKE '%, ' || :author || '%' OR author LIKE '%/' || :author || '%' OR author LIKE '%/ ' || :author || '%') AND (:website = '' OR website = :website) AND (:playlistId < 0 OR id IN (SELECT historyItemId FROM PlaylistItemCrossRef WHERE playlistId = :playlistId)) ORDER BY " +
        "CASE WHEN :sort = 'DATE' AND :order = 'ASC' THEN time END ASC," +
        "CASE WHEN :sort = 'DATE' AND :order = 'DESC' THEN time END DESC," +
        "CASE WHEN :sort = 'TITLE' AND :order = 'ASC' THEN title END ASC," +
        "CASE WHEN :sort = 'TITLE' AND :order = 'DESC' THEN title END DESC," +
        "CASE WHEN :sort = 'AUTHOR' AND :order = 'ASC' THEN author END ASC," +
        "CASE WHEN :sort = 'AUTHOR' AND :order = 'DESC' THEN author END DESC," +
        "CASE WHEN :sort = 'FILESIZE' AND :order = 'ASC' THEN filesize END ASC," +
        "CASE WHEN :sort = 'FILESIZE' AND :order = 'DESC' THEN filesize END DESC")
    fun getPaginatedSource(query: String, type: String, author: String, sort: String, order: String, website: String, playlistId: Long): PagingSource<Int, HistoryItem>

    @Query("SELECT id FROM history WHERE (:query = '' OR title LIKE :query OR author LIKE :query) AND (:type = '' OR type = :type) AND (:author = '' OR author = :author OR author LIKE :author || ',%' OR author LIKE :author || ' /%' OR author LIKE :author || '/%' OR author LIKE '%,' || :author || '%' OR author LIKE '%, ' || :author || '%' OR author LIKE '%/' || :author || '%' OR author LIKE '%/ ' || :author || '%') AND (:website = '' OR website = :website) AND (:playlistId < 0 OR id IN (SELECT historyItemId FROM PlaylistItemCrossRef WHERE playlistId = :playlistId)) ORDER BY " +
        "CASE WHEN :sort = 'DATE' AND :order = 'ASC' THEN time END ASC," +
        "CASE WHEN :sort = 'DATE' AND :order = 'DESC' THEN time END DESC," +
        "CASE WHEN :sort = 'TITLE' AND :order = 'ASC' THEN title END ASC," +
        "CASE WHEN :sort = 'TITLE' AND :order = 'DESC' THEN title END DESC," +
        "CASE WHEN :sort = 'AUTHOR' AND :order = 'ASC' THEN author END ASC," +
        "CASE WHEN :sort = 'AUTHOR' AND :order = 'DESC' THEN author END DESC," +
        "CASE WHEN :sort = 'FILESIZE' AND :order = 'ASC' THEN filesize END ASC," +
        "CASE WHEN :sort = 'FILESIZE' AND :order = 'DESC' THEN filesize END DESC")
    fun getFilteredIDs(query: String, type: String, author: String, sort: String, order: String, website: String, playlistId: Long): List<Long>

    @RawQuery
    fun getFilteredIDs(query: SupportSQLiteQuery): List<Long>

    @Query(
        "SELECT COUNT(*) FROM history WHERE " +
            "(:query = '' OR title LIKE :query OR author LIKE :query) AND " +
            "(:type = '' OR type = :type) AND " +
            "(:author = '' OR author = :author OR author LIKE :author || ',%' OR author LIKE :author || ' /%' OR author LIKE :author || '/%' OR author LIKE '%,' || :author || '%' OR author LIKE '%, ' || :author || '%' OR author LIKE '%/' || :author || '%' OR author LIKE '%/ ' || :author || '%') AND " +
            "(:website = '' OR website = :website) AND " +
            "(:playlistId < 0 OR id IN (SELECT historyItemId FROM PlaylistItemCrossRef WHERE playlistId = :playlistId))"
    )
    fun getFilteredCount(query: String, type: String, author: String, website: String, playlistId: Long): Int

    @RawQuery
    fun getFilteredCount(query: SupportSQLiteQuery): Int


    @Query("DELETE FROM history WHERE id IN (:ids)")
    fun deleteWithIds(ids: List<Long>)

    @Query("SELECT downloadPath FROM history WHERE id IN (:ids)")
    fun getDownloadPathsFromIDs(ids: List<Long>): List<String>

    @Query("SELECT * FROM history WHERE id IN (:ids)")
    fun getItemsFromIDs(ids: List<Long>): List<HistoryItem>

}
