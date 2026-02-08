package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.DBManager.SORTING
import com.ireum.ytdl.database.dao.HistoryDao
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.YoutuberInfo
import com.ireum.ytdl.util.FileUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.util.Locale

class HistoryRepository(private val historyDao: HistoryDao, private val playlistDao: com.ireum.ytdl.database.dao.PlaylistDao) {
    private companion object {
        // Keep Room IN-clause bindings safely below SQLite variable limits.
        const val ID_BATCH_SIZE = 800
    }

    val authors = historyDao.observeAll().map { items ->
        items.flatMap { item ->
            val authors = splitAuthors(item.author)
            val artists = splitAuthors(item.artist)
            (authors + artists).distinct()
        }.distinct().sorted()
    }

    val websites = historyDao.websites

    enum class SearchField {
        TITLE,
        KEYWORDS,
        CREATOR
    }

    fun getRecent(limit: Int) = historyDao.getRecent(limit)

    fun getAuthorsWithInfo(): Flow<List<YoutuberInfo>> {
        return historyDao.observeAll().map { items ->
            val map = linkedMapOf<String, YoutuberInfoAccumulator>()
            items.forEach { item ->
                val authors = (splitAuthors(item.author) + splitAuthors(item.artist)).distinct()
                if (authors.isEmpty()) return@forEach
                val itemThumb = item.customThumb
                    .takeIf { it.isNotBlank() && FileUtil.exists(it) }
                    ?: item.thumb
                val itemSize = if (item.filesize > 0) item.filesize else item.format.filesize
                authors.forEach { author ->
                    val acc = map.getOrPut(author) { YoutuberInfoAccumulator(author) }
                    acc.videoCount += 1
                    acc.totalSize += itemSize
                    if (item.time > acc.lastTime) {
                        acc.lastTime = item.time
                        acc.thumbnail = itemThumb.ifBlank { acc.thumbnail }
                    }
                    if (acc.firstTime == 0L || item.time < acc.firstTime) {
                        acc.firstTime = item.time
                    }
                }
            }
            map.values.map { it.toInfo() }.sortedBy { it.author.lowercase(Locale.getDefault()) }
        }
    }

    fun getPaginatedSource(
        query: String,
        type: String,
        author: String,
        sortType: HistorySortType,
        order: SORTING,
        website: String,
        playlistId: Long,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS)
    ) = historyDao.getPaginatedSource(
        buildFilterQuery(query, type, author, sortType, order, website, playlistId, searchFields = searchFields)
    )

    fun getFilteredIDs(
        query: String,
        type: String,
        author: String,
        sortType: HistorySortType,
        order: SORTING,
        status: Any,
        website: String,
        playlistId: Long,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS)
    ): List<Long> {
        return historyDao.getFilteredIDs(
            buildFilterQuery(
                query,
                type,
                author,
                sortType,
                order,
                website,
                playlistId,
                selectIds = true,
                searchFields = searchFields
            )
        )
    }

    fun getFilteredCount(
        query: String,
        type: String,
        author: String,
        website: String,
        playlistId: Long,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS)
    ): Int {
        return historyDao.getFilteredCount(
            buildFilterQuery(
                query,
                type,
                author,
                HistorySortType.DATE,
                SORTING.DESC,
                website,
                playlistId,
                countOnly = true,
                searchFields = searchFields
            )
        )
    }

    fun getAll(): List<HistoryItem> {
        return historyDao.getAll()
    }

    fun getItem(id: Long): HistoryItem {
        return historyDao.getItem(id)
    }

    fun getItemsByUrl(url: String): List<HistoryItem> {
        return historyDao.getItemsByUrl(url)
    }

    fun getDownloadPathsFromIDs(ids: List<Long>): List<List<String>> {
        return historyDao.getItemsFromIDs(ids).map { it.downloadPath }
    }

    fun getItemsFromIDs(ids: List<Long>): List<HistoryItem> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(ID_BATCH_SIZE).flatMap { batch ->
            historyDao.getItemsFromIDs(batch)
        }
    }

    fun getAuthorsWithInfoForHistoryIds(ids: List<Long>): List<YoutuberInfo> {
        if (ids.isEmpty()) return emptyList()
        val map = linkedMapOf<String, YoutuberInfoAccumulator>()
        getItemsFromIDs(ids).forEach { item ->
            val authors = (splitAuthors(item.author) + splitAuthors(item.artist)).distinct()
            if (authors.isEmpty()) return@forEach
            val itemThumb = item.customThumb
                .takeIf { it.isNotBlank() && FileUtil.exists(it) }
                ?: item.thumb
            val itemSize = if (item.filesize > 0) item.filesize else item.format.filesize
            authors.forEach { author ->
                val acc = map.getOrPut(author) { YoutuberInfoAccumulator(author) }
                acc.videoCount += 1
                acc.totalSize += itemSize
                if (item.time > acc.lastTime) {
                    acc.lastTime = item.time
                    acc.thumbnail = itemThumb.ifBlank { acc.thumbnail }
                }
                if (acc.firstTime == 0L || item.time < acc.firstTime) {
                    acc.firstTime = item.time
                }
            }
        }
        return map.values.map { it.toInfo() }.sortedBy { it.author.lowercase(Locale.getDefault()) }
    }


    fun insert(item: HistoryItem) {
        historyDao.insert(item)
    }

    fun insertAndGetId(item: HistoryItem): Long {
        return historyDao.insertAndGetId(item)
    }

    suspend fun delete(item: HistoryItem, deleteFile: Boolean) {
        if (deleteFile) {
            item.downloadPath.forEach { FileUtil.deleteFile(it) }
        }
        playlistDao.deletePlaylistItemsByHistoryIds(listOf(item.id))
        historyDao.delete(item)
    }

    suspend fun deleteAllWithIDs(ids: List<Long>, deleteFile: Boolean) {
        if (ids.isEmpty()) return
        if (deleteFile) {
            ids.chunked(ID_BATCH_SIZE).forEach { batch ->
                val items = historyDao.getItemsFromIDs(batch)
                items.forEach { item ->
                    item.downloadPath.forEach { FileUtil.deleteFile(it) }
                }
            }
        }
        ids.chunked(ID_BATCH_SIZE).forEach { batch ->
            playlistDao.deletePlaylistItemsByHistoryIds(batch)
            historyDao.deleteWithIds(batch)
        }
    }

    suspend fun deleteAllWithIDsCheckFiles(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(ID_BATCH_SIZE).forEach { batch ->
            val items = historyDao.getItemsFromIDs(batch)
            items.forEach { item ->
                val filesPresent = item.downloadPath.all { FileUtil.exists(it) }
                if (filesPresent) {
                    item.downloadPath.forEach { FileUtil.deleteFile(it) }
                }
            }
            playlistDao.deletePlaylistItemsByHistoryIds(batch)
            historyDao.deleteWithIds(batch)
        }
    }

    suspend fun deleteAll(deleteFile: Boolean) {
        if (deleteFile) {
            val items = historyDao.getAll()
            items.forEach { item ->
                item.downloadPath.forEach { FileUtil.deleteFile(it) }
            }
        }
        playlistDao.clearPlaylistItems()
        historyDao.nuke()
    }

    suspend fun deleteDuplicates() {
        val items = historyDao.getAllDownloaded()
        val duplicates = items
            .filter { it.title.isNotBlank() }
            .groupBy { it.title.trim() }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                // Keep the earliest-added item and remove later duplicates.
                group.sortedWith(compareBy<HistoryItem> { it.time }.thenBy { it.id }).drop(1)
            }
        if (duplicates.isEmpty()) return
        deleteAllWithIDs(duplicates.map { it.id }, deleteFile = false)
    }

    fun update(item: HistoryItem) {
        historyDao.update(item)
    }

    suspend fun clearDeletedHistory() {
        val items = historyDao.getAll()
        items.forEach { item ->
            if (item.downloadPath.all { !FileUtil.exists(it) }) {
                playlistDao.deletePlaylistItemsByHistoryIds(listOf(item.id))
                historyDao.delete(item)
            }
        }
    }

    enum class HistorySortType {
        DATE, TITLE, AUTHOR, DURATION
    }

    private data class YoutuberInfoAccumulator(
        val author: String,
        var videoCount: Int = 0,
        var thumbnail: String? = null,
        var lastTime: Long = 0L,
        var totalSize: Long = 0L,
        var firstTime: Long = 0L
    ) {
        fun toInfo(): YoutuberInfo = YoutuberInfo(
            author = author,
            videoCount = videoCount,
            thumbnail = thumbnail,
            lastTime = lastTime,
            totalSize = totalSize,
            firstTime = firstTime
        )
    }

    private data class SearchToken(
        val text: String,
        val isExclude: Boolean
    )

    private fun splitAuthors(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        return parseAuthorsWithQuotes(trimmed)
            .map { it.first }
            .filter { it.isNotBlank() }
    }

    private fun parseAuthorsWithQuotes(raw: String): List<Pair<String, Boolean>> {
        if (raw.isBlank()) return emptyList()
        val parts = mutableListOf<Pair<String, Boolean>>()
        val current = StringBuilder()
        var inQuotes = false
        var currentQuoted = false
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when (ch) {
                '"' -> {
                    inQuotes = !inQuotes
                    currentQuoted = currentQuoted || inQuotes
                }
                ',', '/', '，', '／' -> {
                    if (inQuotes) {
                        current.append(ch)
                    } else {
                        val token = current.toString().trim().trim('"')
                        if (token.isNotBlank()) {
                            parts.add(token to currentQuoted)
                        }
                        current.setLength(0)
                        currentQuoted = false
                    }
                }
                else -> current.append(ch)
            }
            i += 1
        }
        val last = current.toString().trim().trim('"')
        if (last.isNotBlank()) {
            parts.add(last to currentQuoted)
        }
        return parts
    }

    private fun buildFilterQuery(
        rawQuery: String,
        type: String,
        author: String,
        sortType: HistorySortType,
        order: SORTING,
        website: String,
        playlistId: Long,
        selectIds: Boolean = false,
        countOnly: Boolean = false,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS)
    ): SupportSQLiteQuery {
        val where = StringBuilder()
        val args = ArrayList<Any>()

        fun appendClause(clause: String) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append(clause)
        }

        val tokens = parseSearchTokens(rawQuery)

        val effectiveSearchFields = if (searchFields.isEmpty()) {
            setOf(SearchField.TITLE, SearchField.KEYWORDS)
        } else {
            searchFields
        }

        tokens.forEach { searchToken ->
            val isExclude = searchToken.isExclude
            val token = searchToken.text
            if (token.isBlank()) return@forEach
            val escaped = escapeLike(token)
            val perTokenClauses = mutableListOf<String>()
            if (effectiveSearchFields.contains(SearchField.TITLE)) {
                perTokenClauses.add("title LIKE ? ESCAPE '\\'")
                args.add("%$escaped%")
            }
            if (effectiveSearchFields.contains(SearchField.KEYWORDS)) {
                perTokenClauses.add("keywords LIKE ? ESCAPE '\\'")
                args.add("%$escaped%")
            }
            if (effectiveSearchFields.contains(SearchField.CREATOR)) {
                perTokenClauses.add("(author LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\')")
                args.add("%$escaped%")
                args.add("%$escaped%")
            }
            if (perTokenClauses.isNotEmpty()) {
                val joined = perTokenClauses.joinToString(" OR ")
                if (isExclude) {
                    appendClause("NOT ($joined)")
                } else {
                    appendClause("($joined)")
                }
            }
        }

        if (type.isNotBlank()) {
            appendClause("type = ?")
            args.add(type)
        }

        if (author.isNotBlank()) {
            val normalizedAuthor = author.trim().trim('"')
            val escaped = escapeLike(normalizedAuthor)
            val quotedAuthor = "\"$normalizedAuthor\""
            val escapedQuoted = escapeLike(quotedAuthor)
            appendClause(
                "(" +
                    "(author = ? OR " +
                    "author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR " +
                    "author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\')" +
                " OR " +
                    "(author = ? OR " +
                    "author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR " +
                    "author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\')" +
                " OR " +
                    "(artist = ? OR " +
                    "artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR " +
                    "artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\')" +
                " OR " +
                    "(artist = ? OR " +
                    "artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR " +
                    "artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\')" +
                ")"
            )
            args.add(normalizedAuthor)
            args.add("${escaped},%")
            args.add("${escaped} /%")
            args.add("${escaped}/%")
            args.add("%,$escaped%")
            args.add("%, $escaped%")
            args.add("%/$escaped%")
            args.add("%/ $escaped%")
            args.add(quotedAuthor)
            args.add("${escapedQuoted},%")
            args.add("${escapedQuoted} /%")
            args.add("${escapedQuoted}/%")
            args.add("%,$escapedQuoted%")
            args.add("%, $escapedQuoted%")
            args.add("%/$escapedQuoted%")
            args.add("%/ $escapedQuoted%")
            args.add(normalizedAuthor)
            args.add("${escaped},%")
            args.add("${escaped} /%")
            args.add("${escaped}/%")
            args.add("%,$escaped%")
            args.add("%, $escaped%")
            args.add("%/$escaped%")
            args.add("%/ $escaped%")
            args.add(quotedAuthor)
            args.add("${escapedQuoted},%")
            args.add("${escapedQuoted} /%")
            args.add("${escapedQuoted}/%")
            args.add("%,$escapedQuoted%")
            args.add("%, $escapedQuoted%")
            args.add("%/$escapedQuoted%")
            args.add("%/ $escapedQuoted%")
        }

        if (website.isNotBlank()) {
            appendClause("website = ?")
            args.add(website)
        }

        if (playlistId >= 0L) {
            appendClause("id IN (SELECT historyItemId FROM PlaylistItemCrossRef WHERE playlistId = ?)")
            args.add(playlistId)
        } else if (playlistId == -2L) {
            appendClause("id NOT IN (SELECT historyItemId FROM PlaylistItemCrossRef)")
        }

        val select = when {
            countOnly -> "SELECT COUNT(*)"
            selectIds -> "SELECT id"
            else -> "SELECT *"
        }

        val sql = StringBuilder("$select FROM history")
        if (where.isNotEmpty()) {
            sql.append(" WHERE ").append(where)
        }
        if (!countOnly) {
            val sortColumn = when (sortType) {
                HistorySortType.DATE -> "time"
                HistorySortType.TITLE -> "title"
                HistorySortType.AUTHOR -> "author"
                HistorySortType.DURATION -> "durationSeconds"
            }
            sql.append(" ORDER BY ").append(sortColumn).append(" ").append(order.name)
        }

        return SimpleSQLiteQuery(sql.toString(), args.toArray())
    }

    private fun parseSearchTokens(rawQuery: String): List<SearchToken> {
        val result = mutableListOf<SearchToken>()
        var i = 0

        fun isDelimiter(ch: Char): Boolean = ch.isWhitespace() || ch == ','

        while (i < rawQuery.length) {
            while (i < rawQuery.length && isDelimiter(rawQuery[i])) i++
            if (i >= rawQuery.length) break

            var isExclude = false
            if (rawQuery[i] == '-' && i + 1 < rawQuery.length && !isDelimiter(rawQuery[i + 1])) {
                isExclude = true
                i++
            }

            if (i < rawQuery.length && rawQuery[i] == '"') {
                i++
                val start = i
                while (i < rawQuery.length && rawQuery[i] != '"') i++
                val token = rawQuery.substring(start, i).trim()
                if (token.isNotBlank()) {
                    result.add(SearchToken(token, isExclude))
                }
                if (i < rawQuery.length && rawQuery[i] == '"') i++
            } else {
                val start = i
                while (i < rawQuery.length && !isDelimiter(rawQuery[i])) i++
                val token = rawQuery.substring(start, i).trim()
                if (token.isNotBlank()) {
                    result.add(SearchToken(token, isExclude))
                }
            }
        }

        return result
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}

