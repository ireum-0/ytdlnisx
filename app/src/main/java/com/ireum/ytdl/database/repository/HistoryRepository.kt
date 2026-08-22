package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.DBManager.SORTING
import com.ireum.ytdl.database.dao.HistoryDao
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.KeywordInfo
import com.ireum.ytdl.database.models.YoutuberInfo
import com.ireum.ytdl.util.WebsiteUtil
import com.ireum.ytdl.util.HistoryMediaPublishedDateCandidatePolicy
import com.ireum.ytdl.util.MediaPublishedDateOrder
import com.ireum.ytdl.util.MissingSourceDatePolicy
import com.ireum.ytdl.util.storage.HistoryDeletionReferenceRecord
import com.ireum.ytdl.util.storage.HistoryDeletionCandidateRecord
import com.ireum.ytdl.util.storage.HistoryReferenceMutationCoordinator
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
    val keywords = historyDao.observeAll().map { items ->
        items.flatMap { item ->
            splitKeywords(item.keywords)
        }.distinct().sortedBy { it.lowercase(Locale.getDefault()) }
    }

    val websites = historyDao.websites.map { WebsiteUtil.normalizeNames(it) }

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
                val itemThumb = item.customThumb.ifBlank { item.thumb }
                val fallbackThumb = item.thumb.takeIf { item.customThumb.isNotBlank() }
                val itemSize = if (item.filesize > 0) item.filesize else item.format.filesize
                authors.forEach { author ->
                    val acc = map.getOrPut(author) { YoutuberInfoAccumulator(author) }
                    acc.videoCount += 1
                    acc.totalSize += itemSize
                    if (item.time > acc.lastTime) {
                        acc.lastTime = item.time
                        if (itemThumb.isNotBlank()) {
                            acc.thumbnail = itemThumb
                            acc.fallbackThumbnail = fallbackThumb
                        }
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
        keyword: String = "",
        titleQuery: String = "",
        keywordQuery: String = "",
        creatorQuery: String = "",
        sortType: HistorySortType,
        order: SORTING,
        website: String,
        playlistId: Long,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS),
        status: Any? = null,
        missingSourceDatePolicy: MissingSourceDatePolicy = MissingSourceDatePolicy.USE_DOWNLOAD_DATE
    ) = historyDao.getPaginatedSource(
        buildFilterQuery(
            rawQuery = query,
            type = type,
            author = author,
            keyword = keyword,
            titleQuery = titleQuery,
            keywordQuery = keywordQuery,
            creatorQuery = creatorQuery,
            sortType = sortType,
            order = order,
            website = website,
            playlistId = playlistId,
            status = statusToFilterKey(status),
            searchFields = searchFields,
            missingSourceDatePolicy = missingSourceDatePolicy
        )
    )

    fun getFilteredIDs(
        query: String,
        type: String,
        author: String,
        keyword: String = "",
        titleQuery: String = "",
        keywordQuery: String = "",
        creatorQuery: String = "",
        sortType: HistorySortType,
        order: SORTING,
        status: Any,
        website: String,
        playlistId: Long,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS),
        missingSourceDatePolicy: MissingSourceDatePolicy = MissingSourceDatePolicy.USE_DOWNLOAD_DATE
    ): List<Long> {
        return historyDao.getFilteredIDs(
            buildFilterQuery(
                query,
                type,
                author,
                keyword,
                titleQuery,
                keywordQuery,
                creatorQuery,
                sortType,
                order,
                website,
                playlistId,
                status = statusToFilterKey(status),
                selectIds = true,
                searchFields = searchFields,
                missingSourceDatePolicy = missingSourceDatePolicy
            )
        )
    }

    fun getFilteredCount(
        query: String,
        type: String,
        author: String,
        keyword: String = "",
        titleQuery: String = "",
        keywordQuery: String = "",
        creatorQuery: String = "",
        website: String,
        playlistId: Long,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS),
        status: Any? = null
    ): Int {
        return historyDao.getFilteredCount(
            buildFilterQuery(
                query,
                type,
                author,
                keyword,
                titleQuery,
                keywordQuery,
                creatorQuery,
                HistorySortType.DATE,
                SORTING.DESC,
                website,
                playlistId,
                status = statusToFilterKey(status),
                countOnly = true,
                searchFields = searchFields
            )
        )
    }

    fun getAll(): List<HistoryItem> {
        return historyDao.getAll()
    }

    fun getCount(): Int = historyDao.getCount()

    fun getAllIds(): List<Long> = historyDao.getAllIds()

    fun getDeletionReferenceRecords(): List<HistoryDeletionReferenceRecord> {
        return historyDao.getDeletionReferenceRecords()
    }

    fun getDeletionReferenceRecordsByIds(ids: List<Long>): List<HistoryDeletionReferenceRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(ID_BATCH_SIZE).flatMap(historyDao::getDeletionReferenceRecordsByIds)
    }

    fun getDeletionCandidateRecords(): List<HistoryDeletionCandidateRecord> {
        return historyDao.getDeletionCandidateRecords()
    }

    fun getDeletionCandidateRecordsByIds(ids: List<Long>): List<HistoryDeletionCandidateRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(ID_BATCH_SIZE).flatMap(historyDao::getDeletionCandidateRecordsByIds)
    }

    fun getExistingIds(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(ID_BATCH_SIZE).flatMap(historyDao::getExistingIds)
    }

    fun getItem(id: Long): HistoryItem {
        return historyDao.getItem(id)
    }

    fun getItemsByUrl(url: String): List<HistoryItem> {
        return historyDao.getItemsByUrl(url)
    }

    fun getItemsWithRemoteThumb(limit: Int): List<HistoryItem> {
        return historyDao.getItemsWithRemoteThumb(limit)
    }

    fun getItemsMissingMediaPublishedAt(): List<HistoryItem> {
        return HistoryMediaPublishedDateCandidatePolicy.select(
            historyDao.getItemsWithMissingMediaPublishedAt()
        )
    }

    fun getVideoQualityScanCandidates(): List<HistoryItem> = historyDao.getAllVideos()

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
            val itemThumb = item.customThumb.ifBlank { item.thumb }
            val fallbackThumb = item.thumb.takeIf { item.customThumb.isNotBlank() }
            val itemSize = if (item.filesize > 0) item.filesize else item.format.filesize
            authors.forEach { author ->
                val acc = map.getOrPut(author) { YoutuberInfoAccumulator(author) }
                acc.videoCount += 1
                acc.totalSize += itemSize
                if (item.time > acc.lastTime) {
                    acc.lastTime = item.time
                    if (itemThumb.isNotBlank()) {
                        acc.thumbnail = itemThumb
                        acc.fallbackThumbnail = fallbackThumb
                    }
                }
                if (acc.firstTime == 0L || item.time < acc.firstTime) {
                    acc.firstTime = item.time
                }
            }
        }
        return map.values.map { it.toInfo() }.sortedBy { it.author.lowercase(Locale.getDefault()) }
    }

    fun getKeywordsWithInfoForHistoryIds(ids: List<Long>): List<KeywordInfo> {
        if (ids.isEmpty()) return emptyList()
        val map = linkedMapOf<String, KeywordInfoAccumulator>()
        getItemsFromIDs(ids).forEach { item ->
            val keywords = splitKeywords(item.keywords)
            if (keywords.isEmpty()) return@forEach
            val artistCreators = splitAuthors(item.artist)
            val creators = if (artistCreators.isNotEmpty()) {
                artistCreators
            } else {
                splitAuthors(item.author)
            }
            val itemThumb = item.customThumb.ifBlank { item.thumb }
            val fallbackThumb = item.thumb.takeIf { item.customThumb.isNotBlank() }
            keywords.forEach { keyword ->
                val acc = map.getOrPut(keyword) { KeywordInfoAccumulator(keyword) }
                acc.videoCount += 1
                acc.videoIds.add(item.id)
                creators.forEach { creator ->
                    val normalized = normalizeCreator(creator)
                    if (normalized.isBlank()) return@forEach
                    acc.creatorDisplayByNormalized.putIfAbsent(normalized, creator.trim().trim('"'))
                }
                if (item.time > acc.lastTime) {
                    acc.lastTime = item.time
                    if (itemThumb.isNotBlank()) {
                        acc.thumbnail = itemThumb
                        acc.fallbackThumbnail = fallbackThumb
                    }
                }
                if (acc.firstTime == 0L || item.time < acc.firstTime) {
                    acc.firstTime = item.time
                }
            }
        }
        val parentCandidatesByKeyword = mutableMapOf<String, MutableSet<String>>()
        val accumulators = map.values.toList()
        val accumulatorByKeyword = accumulators.associateBy { it.keyword }
        for (i in accumulators.indices) {
            val a = accumulators[i]
            val aIds = a.videoIds
            if (aIds.isEmpty()) continue
            for (j in accumulators.indices) {
                if (i == j) continue
                val b = accumulators[j]
                val bIds = b.videoIds
                if (aIds.size >= bIds.size || bIds.isEmpty()) continue
                if (bIds.containsAll(aIds)) {
                    parentCandidatesByKeyword.getOrPut(a.keyword) { linkedSetOf() }.add(b.keyword)
                }
            }
        }

        val directParentsByKeyword = mutableMapOf<String, List<String>>()
        parentCandidatesByKeyword.forEach { (childKeyword, parentCandidates) ->
            val childIds = accumulatorByKeyword[childKeyword]?.videoIds.orEmpty()
            val directParents = parentCandidates.filter { parent ->
                val parentIds = accumulatorByKeyword[parent]?.videoIds.orEmpty()
                parentCandidates.none { other ->
                    if (other == parent) return@none false
                    val otherIds = accumulatorByKeyword[other]?.videoIds.orEmpty()
                    otherIds.isNotEmpty() &&
                        otherIds.size <= parentIds.size &&
                        otherIds.containsAll(childIds) &&
                        parentIds.containsAll(otherIds)
                }
            }
            directParentsByKeyword[childKeyword] = directParents
        }

        val directChildrenByKeyword = mutableMapOf<String, MutableList<String>>()
        directParentsByKeyword.forEach { (childKeyword, directParents) ->
            directParents.forEach { parentKeyword ->
                directChildrenByKeyword.getOrPut(parentKeyword) { mutableListOf() }.add(childKeyword)
            }
        }

        return accumulators
            .map { it.toInfo(directParentsByKeyword[it.keyword].orEmpty(), directChildrenByKeyword[it.keyword].orEmpty()) }
            .sortedBy { it.keyword.lowercase(Locale.getDefault()) }
    }

    suspend fun deleteRecords(ids: List<Long>) {
        HistoryReferenceMutationCoordinator.withLock {
            deleteRecordsWithinReferenceMutation(ids)
        }
    }

    suspend fun deleteAllRecords() {
        HistoryReferenceMutationCoordinator.withLock {
            playlistDao.clearPlaylistItems()
            historyDao.nuke()
        }
    }

    suspend fun deleteRecordsWithinReferenceMutation(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(ID_BATCH_SIZE).forEach { batch ->
            playlistDao.deletePlaylistItemsByHistoryIds(batch)
            historyDao.deleteWithIds(batch)
        }
    }

    fun getDuplicateGroups(): List<List<HistoryItem>> =
        historyDao.getAllDownloaded()
            .filter { it.title.isNotBlank() }
            .groupBy { it.title.trim() }
            .values
            .filter { it.size > 1 }
            .map { group ->
                group.sortedWith(compareBy<HistoryItem> { it.time }.thenBy { it.id })
            }

    fun update(item: HistoryItem) {
        historyDao.update(item)
    }

    fun updateMediaPublishedAtIfMissing(
        id: Long,
        normalizedUrl: String,
        mediaPublishedAt: Long
    ): Boolean {
        return historyDao.updateMediaPublishedAtIfMissing(
            id = id,
            normalizedUrl = normalizedUrl,
            mediaPublishedAt = mediaPublishedAt
        ) > 0
    }

    fun updateHardSubScanRemoved(ids: List<Long>, removed: Boolean) {
        if (ids.isEmpty()) return
        ids.chunked(ID_BATCH_SIZE).forEach { batch ->
            historyDao.updateHardSubScanRemovedForIds(batch, removed)
        }
    }

    fun updateHardSubDone(ids: List<Long>, done: Boolean): Int {
        if (ids.isEmpty()) return 0
        return ids.chunked(ID_BATCH_SIZE).sumOf { batch ->
            historyDao.updateHardSubDoneForIds(batch, done)
        }
    }

    enum class HistorySortType {
        DATE, MEDIA_PUBLISHED_DATE, TITLE, AUTHOR, DURATION
    }

    private data class YoutuberInfoAccumulator(
        val author: String,
        var videoCount: Int = 0,
        var thumbnail: String? = null,
        var fallbackThumbnail: String? = null,
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
        ).also { it.fallbackThumbnail = fallbackThumbnail }
    }

    private data class KeywordInfoAccumulator(
        val keyword: String,
        var videoCount: Int = 0,
        var thumbnail: String? = null,
        var fallbackThumbnail: String? = null,
        var lastTime: Long = 0L,
        var firstTime: Long = 0L,
        val videoIds: MutableSet<Long> = linkedSetOf(),
        val creatorDisplayByNormalized: MutableMap<String, String> = linkedMapOf()
    ) {
        fun toInfo(parentKeywords: List<String>, childKeywords: List<String>): KeywordInfo {
            val uniqueCreator = if (creatorDisplayByNormalized.size == 1) {
                creatorDisplayByNormalized.values.firstOrNull()
            } else {
                null
            }
            return KeywordInfo(
                keyword = keyword,
                videoCount = videoCount,
                thumbnail = thumbnail,
                lastTime = lastTime,
                firstTime = firstTime,
                uniqueCreator = uniqueCreator,
                parentKeywords = parentKeywords
                    .distinctBy { it.lowercase(Locale.getDefault()) }
                    .sortedBy { it.lowercase(Locale.getDefault()) },
                childKeywords = childKeywords
                    .distinctBy { it.lowercase(Locale.getDefault()) }
                    .sortedBy { it.lowercase(Locale.getDefault()) },
                fallbackThumbnail = fallbackThumbnail
            )
        }
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

    private fun splitKeywords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
    }

    private fun normalizeCreator(value: String): String {
        return value.trim().trim('"').lowercase(Locale.getDefault())
    }

    private fun normalizeKeyword(value: String): String {
        return value.trim().lowercase(Locale.getDefault())
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
        keyword: String,
        titleQuery: String,
        keywordQuery: String,
        creatorQuery: String,
        sortType: HistorySortType,
        order: SORTING,
        website: String,
        playlistId: Long,
        status: String? = null,
        selectIds: Boolean = false,
        countOnly: Boolean = false,
        searchFields: Set<SearchField> = setOf(SearchField.TITLE, SearchField.KEYWORDS),
        missingSourceDatePolicy: MissingSourceDatePolicy = MissingSourceDatePolicy.USE_DOWNLOAD_DATE
    ): SupportSQLiteQuery {
        val where = StringBuilder()
        val args = ArrayList<Any>()

        fun appendClause(clause: String) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append(clause)
        }

        fun appendTokenClauses(tokens: List<SearchToken>, fields: Set<SearchField>) {
            tokens.forEach { searchToken ->
                val isExclude = searchToken.isExclude
                val token = searchToken.text
                if (token.isBlank()) return@forEach
                val escaped = escapeLike(token)
                val perTokenClauses = mutableListOf<String>()
                if (fields.contains(SearchField.TITLE)) {
                    perTokenClauses.add("title LIKE ? ESCAPE '\\'")
                    args.add("%$escaped%")
                }
                if (fields.contains(SearchField.KEYWORDS)) {
                    perTokenClauses.add("keywords LIKE ? ESCAPE '\\'")
                    args.add("%$escaped%")
                }
                if (fields.contains(SearchField.CREATOR)) {
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
        }

        val genericTokens = parseSearchTokens(rawQuery)
        val effectiveSearchFields = if (searchFields.isEmpty()) {
            setOf(SearchField.TITLE, SearchField.KEYWORDS)
        } else {
            searchFields
        }
        appendTokenClauses(genericTokens, effectiveSearchFields)
        appendTokenClauses(parseSearchTokens(titleQuery), setOf(SearchField.TITLE))
        appendTokenClauses(parseSearchTokens(keywordQuery), setOf(SearchField.KEYWORDS))
        appendTokenClauses(parseSearchTokens(creatorQuery), setOf(SearchField.CREATOR))

        if (type.isNotBlank()) {
            val types = type.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (types.size == 1) {
                appendClause("type = ?")
                args.add(types.first())
            } else if (types.isNotEmpty()) {
                appendClause("type IN (${types.joinToString(",") { "?" }})")
                args.addAll(types)
            }
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

        if (keyword.isNotBlank()) {
            val normalizedKeyword = keyword.trim().lowercase(Locale.getDefault()).replace(" ", "")
            if (normalizedKeyword.isNotBlank()) {
                appendClause("((',' || LOWER(REPLACE(keywords, ' ', '')) || ',') LIKE ?)")
                args.add("%,$normalizedKeyword,%")
            }
        }

        val selectedWebsites = WebsiteUtil.decodeFilter(website)
            .map(WebsiteUtil::comparisonKey)
            .distinct()
        if (selectedWebsites.isNotEmpty()) {
            if (selectedWebsites.size == 1) {
                appendClause("LOWER(TRIM(website)) = ?")
                args.add(selectedWebsites.first())
            } else {
                appendClause("LOWER(TRIM(website)) IN (${selectedWebsites.joinToString(",") { "?" }})")
                args.addAll(selectedWebsites)
            }
        }

        if (playlistId >= 0L) {
            appendClause("id IN (SELECT historyItemId FROM PlaylistItemCrossRef WHERE playlistId = ?)")
            args.add(playlistId)
        } else if (playlistId == -2L) {
            appendClause("id NOT IN (SELECT historyItemId FROM PlaylistItemCrossRef)")
        }

        when (status.orEmpty()) {
            // DB prefilter for statuses that can be decided without filesystem checks.
            "HARDSUB_DONE" -> {
                appendClause("type = 'video'")
                appendClause("hardSubDone = 1")
            }
            "HARDSUB_SCAN_TARGET" -> {
                appendClause("type = 'video'")
                appendClause("hardSubDone = 0")
                appendClause("hardSubScanRemoved = 0")
            }
            // Narrow candidate set before in-memory file-existence verification.
            "CUSTOM_THUMBNAIL" -> appendClause("customThumb != ''")
            "MISSING_THUMBNAIL" -> appendClause("thumb = '' AND customThumb = ''")
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
            if (sortType == HistorySortType.MEDIA_PUBLISHED_DATE) {
                sql.append(" ORDER BY ").append(
                    MediaPublishedDateOrder.sqlOrderBy(
                        policy = missingSourceDatePolicy,
                        descending = order == SORTING.DESC
                    )
                )
            } else {
                val sortColumn = when (sortType) {
                    HistorySortType.DATE -> "time"
                    HistorySortType.TITLE -> "title"
                    HistorySortType.AUTHOR -> "author"
                    HistorySortType.DURATION -> "durationSeconds"
                    HistorySortType.MEDIA_PUBLISHED_DATE -> error("Handled above")
                }
                sql.append(" ORDER BY ").append(sortColumn).append(" ").append(order.name)
            }
        }

        return SimpleSQLiteQuery(sql.toString(), args.toArray())
    }

    private fun statusToFilterKey(status: Any?): String? {
        return when (status) {
            null -> null
            is Enum<*> -> status.name
            else -> status.toString()
        }
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

