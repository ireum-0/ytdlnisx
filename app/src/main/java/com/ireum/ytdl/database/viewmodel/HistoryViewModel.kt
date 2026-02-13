package com.ireum.ytdl.database.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.cachedIn
import androidx.paging.insertHeaderItem
import androidx.paging.map
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.DBManager.SORTING
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.UiModel
import com.ireum.ytdl.database.models.YoutuberInfo
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.HistoryRepository.HistorySortType
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.extractors.YoutubeApiUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.preference.PreferenceManager
import java.util.Collections
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: HistoryRepository
    val sortOrder = MutableStateFlow(SORTING.DESC)
    val sortType = MutableStateFlow(HistorySortType.DATE)
    val authorFilter = MutableStateFlow("")
    val keywordFilter = MutableStateFlow("")
    val websiteFilter = MutableStateFlow("")
    val playlistFilter = MutableStateFlow(-1L)
    val statusFilter = MutableStateFlow(HistoryStatus.ALL)
    val isYoutuberSelectionMode = MutableStateFlow(false)  // mode
    val isPlaylistSelectionMode = MutableStateFlow(false)
    val isKeywordSelectionMode = MutableStateFlow(false)
    val isRecentMode = MutableStateFlow(false)
    val youtuberGroupFilter = MutableStateFlow(-1L)
    val playlistGroupFilter = MutableStateFlow(-1L)
    val keywordGroupFilter = MutableStateFlow(-1L)
    private val queryFilter = MutableStateFlow("")
    private val titleQueryFilter = MutableStateFlow("")
    private val keywordQueryFilter = MutableStateFlow("")
    private val creatorQueryFilter = MutableStateFlow("")
    val searchFieldsFilter = MutableStateFlow(
        setOf(
            HistoryRepository.SearchField.TITLE,
            HistoryRepository.SearchField.KEYWORDS
        )
    )
    val includeChildCategoryVideosFilter = MutableStateFlow(false)
    val hiddenYoutubersFilter = MutableStateFlow(setOf<String>())
    val hiddenYoutuberGroupsFilter = MutableStateFlow(setOf<Long>())
    val showHiddenOnlyFilter = MutableStateFlow(false)
    val excludedChildKeywordsFilter = MutableStateFlow(setOf<String>())
    val visibleChildYoutuberGroupsFilter = MutableStateFlow(setOf<Long>())
    val visibleChildKeywordsFilter = MutableStateFlow(setOf<String>())
    private val refreshTrigger = MutableStateFlow(0L)
    private val typeFilter = MutableStateFlow("")
    val queryFilterFlow = queryFilter.asStateFlow()
    val titleQueryFilterFlow = titleQueryFilter.asStateFlow()
    val keywordQueryFilterFlow = keywordQueryFilter.asStateFlow()
    val creatorQueryFilterFlow = creatorQueryFilter.asStateFlow()
    val typeFilterFlow = typeFilter.asStateFlow()
    private var cachedIdsKey: HistoryFilters? = null
    private var cachedIds: List<Long>? = null
    private var loggedTreePermissions = false
    private val pendingYoutuberMeta = Collections.synchronizedSet(mutableSetOf<String>())
    private val youtuberMetaQueue = Collections.synchronizedSet(mutableSetOf<String>())
    private var youtuberMetaJob: Job? = null
    private var lastQuotaExceededAt = 0L

    enum class HistoryStatus {
        UNSET, DELETED, NOT_DELETED, MISSING_THUMBNAIL, CUSTOM_THUMBNAIL, ALL
    }

    var paginatedItems: Flow<PagingData<UiModel>>
    var websites: Flow<List<String>>
    var authors: Flow<List<String>>
    var keywords: Flow<List<String>>
    var youtuberInfos: Flow<List<YoutuberInfo>>
    var youtuberGroups: Flow<List<com.ireum.ytdl.database.models.YoutuberGroup>>
    var youtuberGroupMembers: Flow<List<com.ireum.ytdl.database.models.YoutuberGroupMember>>
    var youtuberGroupRelations: Flow<List<com.ireum.ytdl.database.models.YoutuberGroupRelation>>
    var playlistGroups: Flow<List<com.ireum.ytdl.database.models.PlaylistGroup>>
    var keywordGroups: Flow<List<com.ireum.ytdl.database.models.KeywordGroup>>
    var keywordGroupMembers: Flow<List<com.ireum.ytdl.database.models.KeywordGroupMember>>
    private val youtuberMetaFlow: Flow<List<com.ireum.ytdl.database.models.YoutuberMeta>>
    private val recentItems: Flow<List<HistoryItem>>
    var totalCount = MutableStateFlow(0)

    data class HistoryFilters(
        var type: String = "",
        var sortType: HistorySortType = HistorySortType.DATE,
        var sortOrder: SORTING = SORTING.DESC,
        var query: String = "",
        var titleQuery: String = "",
        var keywordQuery: String = "",
        var creatorQuery: String = "",
        var includeChildCategoryVideos: Boolean = false,
        var searchFields: Set<HistoryRepository.SearchField> = setOf(
            HistoryRepository.SearchField.TITLE,
            HistoryRepository.SearchField.KEYWORDS
        ),
        var status: HistoryStatus = HistoryStatus.ALL,
        var author: String = "",
        var keyword: String = "",
        var website: String = "",
        var playlistId: Long = -1L
    )
    data class HistoryListKey(
        val type: String,
        val sortType: HistorySortType,
        val sortOrder: SORTING,
        val query: String,
        val titleQuery: String,
        val keywordQuery: String,
        val creatorQuery: String,
        val searchFields: Set<HistoryRepository.SearchField>,
        val status: HistoryStatus,
        val author: String,
        val keyword: String,
        val website: String,
        val playlistId: Long
    )

    data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    data class Sextuple<A, B, C, D, E, F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F
    )
    data class Septuple<A, B, C, D, E, F, G>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F,
        val seventh: G
    )
    data class Octuple<A, B, C, D, E, F, G, H>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F,
        val seventh: G,
        val eighth: H
    )
    data class ModeState(
        val filters: HistoryFilters,
        val isYoutuberMode: Boolean,
        val isKeywordMode: Boolean,
        val isRecent: Boolean,
        val youtuberGroup: Long,
        val keywordGroup: Long,
        val hiddenYoutubers: Set<String>,
        val hiddenYoutuberGroups: Set<Long>,
        val showHiddenOnly: Boolean,
        val excludedChildKeywords: Set<String>,
        val visibleChildYoutuberGroups: Set<Long>,
        val visibleChildKeywords: Set<String>,
        val refreshToken: Long
    )

    init {
        val db = DBManager.getInstance(application)
        val dao = db.historyDao
        val playlistDao = db.playlistDao
        val keywordGroupDao = db.keywordGroupDao
        val groupDao = db.youtuberGroupDao
        val metaDao = db.youtuberMetaDao
        repository = HistoryRepository(dao, playlistDao)
        websites = repository.websites
        authors = repository.authors
        keywords = repository.keywords
        youtuberMetaFlow = metaDao.getAllFlow()
        youtuberInfos = combine(repository.getAuthorsWithInfo(), youtuberMetaFlow) { infos, metas ->
            val metaMap = metas.associateBy { it.author }
            infos.map { info ->
                val iconUrl = metaMap[info.author]?.iconUrl.orEmpty()
                if (iconUrl.isNotBlank()) {
                    info.copy(thumbnail = iconUrl)
                } else {
                    info
                }
            }
        }
        youtuberGroups = groupDao.getGroupsFlow()
        youtuberGroupMembers = groupDao.getAllMembersFlow()
        youtuberGroupRelations = groupDao.getAllRelationsFlow()
        playlistGroups = flowOf(emptyList())
        keywordGroups = keywordGroupDao.getGroupsFlow()
        keywordGroupMembers = keywordGroupDao.getAllMembersFlow()
        recentItems = repository.getRecent(20)
        viewModelScope.launch {
            combine(repository.getAuthorsWithInfo(), youtuberMetaFlow) { infos, metas ->
                val metaAuthors = metas.map { it.author }.toSet()
                infos.map { it.author }.filter { it.isNotBlank() && !metaAuthors.contains(it) }
            }.collect { missing ->
                if (missing.isEmpty()) return@collect
                fetchMissingYoutuberMeta(missing, metaDao)
            }
        }

        val filtersFlow: Flow<HistoryFilters> = combine(
            combine(sortOrder, sortType, authorFilter, statusFilter, websiteFilter) { s: SORTING, st: HistorySortType, a: String, status: HistoryStatus, w: String ->
                Quintuple(s, st, a, status, w)
            },
            combine(
                combine(queryFilter, typeFilter, playlistFilter, searchFieldsFilter) { q: String, t: String, p: Long, sf: Set<HistoryRepository.SearchField> ->
                    Quadruple(q, t, p, sf)
                },
                combine(keywordFilter, titleQueryFilter, keywordQueryFilter, creatorQueryFilter) { k: String, tq: String, kq: String, cq: String ->
                    Quadruple(k, tq, kq, cq)
                },
                includeChildCategoryVideosFilter
            ) { base, search, includeChild ->
                Octuple(base.first, base.second, base.third, base.fourth, search.first, search.second, search.third, search.fourth) to includeChild
            }
        ) { quint: Quintuple<SORTING, HistorySortType, String, HistoryStatus, String>, pair: Pair<Octuple<String, String, Long, Set<HistoryRepository.SearchField>, String, String, String, String>, Boolean> ->
            val oct = pair.first
            HistoryFilters(
                type = oct.second,
                sortType = quint.second,
                sortOrder = quint.first,
                query = oct.first,
                titleQuery = oct.sixth,
                keywordQuery = oct.seventh,
                creatorQuery = oct.eighth,
                includeChildCategoryVideos = pair.second,
                searchFields = oct.fourth,
                status = quint.fourth,
                author = quint.third,
                keyword = oct.fifth,
                website = quint.fifth,
                playlistId = oct.third
            )
        }

        val modeFlow = combine(
            combine(
                filtersFlow,
                isYoutuberSelectionMode,
                isKeywordSelectionMode,
                isRecentMode
            ) { filters, isSelectionMode, isKeywordMode, isRecent ->
                Quadruple(filters, isSelectionMode, isKeywordMode, isRecent)
            },
            youtuberGroupFilter,
            keywordGroupFilter,
            combine(
                combine(
                    hiddenYoutubersFilter,
                    hiddenYoutuberGroupsFilter,
                    showHiddenOnlyFilter,
                    excludedChildKeywordsFilter
                ) { hiddenY, hiddenGroups, showHiddenOnly, excludedChildren ->
                    Quadruple(hiddenY, hiddenGroups, showHiddenOnly, excludedChildren)
                },
                combine(visibleChildYoutuberGroupsFilter, visibleChildKeywordsFilter) { visibleYoutuberGroups, visibleKeywords ->
                    Pair(visibleYoutuberGroups, visibleKeywords)
                }
            ) { hidden, visible ->
                Sextuple(hidden.first, hidden.second, hidden.third, hidden.fourth, visible.first, visible.second)
            },
            refreshTrigger
        ) { base, youtuberGroup, keywordGroup, extra, refreshToken ->
            ModeState(
                filters = base.first,
                isYoutuberMode = base.second,
                isKeywordMode = base.third,
                isRecent = base.fourth,
                youtuberGroup = youtuberGroup,
                keywordGroup = keywordGroup,
                hiddenYoutubers = extra.first,
                hiddenYoutuberGroups = extra.second,
                showHiddenOnly = extra.third,
                excludedChildKeywords = extra.fourth,
                visibleChildYoutuberGroups = extra.fifth,
                visibleChildKeywords = extra.sixth,
                refreshToken = refreshToken
            )
        }

        paginatedItems = modeFlow.flatMapLatest { mode ->
            val filters = mode.filters
            val isSelectionMode = mode.isYoutuberMode
            val isKeywordMode = mode.isKeywordMode
            val isRecent = mode.isRecent
            val youtuberGroup = mode.youtuberGroup
            val keywordGroup = mode.keywordGroup
            val hiddenYoutubers = mode.hiddenYoutubers
            val hiddenYoutuberGroups = mode.hiddenYoutuberGroups
            val showHiddenOnly = mode.showHiddenOnly
            val excludedChildKeywords = mode.excludedChildKeywords
            val visibleChildYoutuberGroups = mode.visibleChildYoutuberGroups
            val visibleChildKeywords = mode.visibleChildKeywords
            Log.d(
                "HistoryPagingVM",
                "switch filters=${filters} youtuberMode=$isSelectionMode keywordMode=$isKeywordMode recent=$isRecent yGroup=$youtuberGroup kGroup=$keywordGroup"
            )
            if (isKeywordMode) {
                Log.d("HistoryPagingVM", "branch=keywordMode group=$keywordGroup")
                val filteredKeywordsFlow = flow {
                    val ids = withContext(Dispatchers.IO) {
                        repository.getFilteredIDs(
                            filters.query,
                            filters.type,
                            filters.author,
                            filters.keyword,
                            filters.titleQuery,
                            filters.keywordQuery,
                            filters.creatorQuery,
                            filters.sortType,
                            filters.sortOrder,
                            filters.status,
                            filters.website,
                            filters.playlistId,
                            filters.searchFields
                        )
                    }
                    val keywords = withContext(Dispatchers.IO) {
                        repository.getKeywordsWithInfoForHistoryIds(ids)
                    }
                    emit(keywords)
                }
                if (keywordGroup >= 0L) {
                    combine(filteredKeywordsFlow, keywordGroupMembers) { keywords, members ->
                        val memberSet = members.filter { it.groupId == keywordGroup }.map { it.keyword }.toSet()
                        val filtered = keywords.filter { memberSet.contains(it.keyword) }
                        val sorted = when (filters.sortType) {
                            HistorySortType.DATE -> {
                                if (filters.sortOrder == SORTING.DESC) {
                                    filtered.sortedBy { it.lastTime }
                                } else {
                                    filtered.sortedBy { it.firstTime }
                                }
                            }
                            HistorySortType.TITLE -> filtered.sortedBy { it.keyword.lowercase() }
                            HistorySortType.DURATION -> filtered.sortedBy { it.videoCount }
                            HistorySortType.AUTHOR -> filtered.sortedBy { it.keyword.lowercase() }
                        }.run {
                            if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                        }
                        PagingData.from(sorted.map { UiModel.KeywordInfoModel(it) as UiModel })
                    }
                } else {
                    combine(filteredKeywordsFlow, keywordGroups, keywordGroupMembers) { keywords, groups, members ->
                        val groupedKeywords = members.map { it.keyword }.toSet()
                        val keywordByName = keywords.associateBy { it.keyword }
                        val visibleKeywordNames = keywords
                            .filter { info ->
                                info.parentKeywords.none { parent -> keywordByName.containsKey(parent) }
                            }
                            .map { it.keyword }
                            .toSet()
                        val ungrouped = keywords.filter {
                            !groupedKeywords.contains(it.keyword) &&
                                (visibleKeywordNames.contains(it.keyword) || visibleChildKeywords.contains(it.keyword))
                        }
                        val sortedUngrouped = when (filters.sortType) {
                            HistorySortType.DATE -> {
                                if (filters.sortOrder == SORTING.DESC) {
                                    ungrouped.sortedBy { it.lastTime }
                                } else {
                                    ungrouped.sortedBy { it.firstTime }
                                }
                            }
                            HistorySortType.TITLE -> ungrouped.sortedBy { it.keyword.lowercase() }
                            HistorySortType.DURATION -> ungrouped.sortedBy { it.videoCount }
                            HistorySortType.AUTHOR -> ungrouped.sortedBy { it.keyword.lowercase() }
                        }.run {
                            if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                        }

                        val groupInfos = groups.map { group ->
                            val memberKeywords = members.filter { it.groupId == group.id }.map { it.keyword }.toSet()
                            val memberInfos = keywords.filter { memberKeywords.contains(it.keyword) }
                            val totalVideos = memberInfos.sumOf { it.videoCount }
                            val thumb = memberInfos.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
                            com.ireum.ytdl.database.models.KeywordGroupInfo(
                                id = group.id,
                                name = group.name,
                                memberCount = memberKeywords.size,
                                videoCount = totalVideos,
                                thumbnail = thumb
                            )
                        }.filter { it.memberCount > 0 && it.videoCount > 0 }.let { infos ->
                            when (filters.sortType) {
                                HistorySortType.DURATION -> infos.sortedBy { it.videoCount }
                                HistorySortType.TITLE -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.AUTHOR -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.DATE -> infos.sortedBy { it.name.lowercase() }
                            }.run {
                                if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                            }
                        }

                        val list = ArrayList<UiModel>()
                        list.addAll(groupInfos.map { UiModel.KeywordGroupModel(it) })
                        list.addAll(sortedUngrouped.map { UiModel.KeywordInfoModel(it) })
                        PagingData.from(list)
                    }
                }
            } else if (isRecent) {
                Log.d("HistoryPagingVM", "branch=recent")
                recentItems.map { items ->
                    val itemsMax = items.take(20)
                    fun recentTime(item: HistoryItem): Long {
                        return if (item.lastWatched > 0L) item.lastWatched else item.time
                    }
                    val sorted = itemsMax
                        .sortedByDescending { recentTime(it) }
                        .take(20)
                        .map { UiModel.HistoryItemModel(it) as UiModel }
                    PagingData.from(sorted)
                }
            } else if (isSelectionMode) {
                Log.d("HistoryPagingVM", "branch=youtuberSelection group=$youtuberGroup")
                val filteredYoutubersFlow = flow {
                    val ids = withContext(Dispatchers.IO) {
                        repository.getFilteredIDs(
                            filters.query,
                            filters.type,
                            filters.author,
                            filters.keyword,
                            filters.titleQuery,
                            filters.keywordQuery,
                            filters.creatorQuery,
                            filters.sortType,
                            filters.sortOrder,
                            filters.status,
                            filters.website,
                            filters.playlistId,
                            filters.searchFields
                        )
                    }
                    val youtubers = withContext(Dispatchers.IO) {
                        repository.getAuthorsWithInfoForHistoryIds(ids)
                    }
                    emit(youtubers)
                }
                fun isYoutuberVisible(author: String): Boolean {
                    val hidden = hiddenYoutubers.contains(author)
                    return if (showHiddenOnly) hidden else !hidden
                }
                fun isGroupVisible(groupId: Long): Boolean {
                    val hidden = hiddenYoutuberGroups.contains(groupId)
                    return if (showHiddenOnly) hidden else !hidden
                }
                if (youtuberGroup >= 0L) {
                    combine(
                        combine(filteredYoutubersFlow, youtuberGroupMembers, youtuberMetaFlow) { youtubers, members, metas ->
                            Triple(youtubers, members, metas)
                        },
                        combine(youtuberGroups, youtuberGroupRelations) { groups, relations ->
                            Pair(groups, relations)
                        }
                    ) { left, right ->
                        val youtubers = left.first
                        val members = left.second
                        val metas = left.third
                        val groups = right.first
                        val relations = right.second
                        val childrenByParent = relations.groupBy { it.parentGroupId }.mapValues { entry ->
                            entry.value.map { it.childGroupId }
                        }
                        val metaMap = metas.associateBy { it.author }
                        val enriched = youtubers.map { info ->
                            val iconUrl = metaMap[info.author]?.iconUrl.orEmpty()
                            if (iconUrl.isNotBlank()) info.copy(thumbnail = iconUrl) else info
                        }
                        fun descendantGroups(startGroupId: Long): Set<Long> {
                            val visited = linkedSetOf<Long>()
                            val stack = ArrayDeque<Long>()
                            stack.add(startGroupId)
                            while (stack.isNotEmpty()) {
                                val id = stack.removeFirst()
                                if (!visited.add(id)) continue
                                childrenByParent[id].orEmpty().forEach { stack.addLast(it) }
                            }
                            return visited
                        }
                        val childGroupIds = childrenByParent[youtuberGroup].orEmpty()
                            .filter { isGroupVisible(it) }
                            .toSet()
                        val directMemberSet = members.filter { it.groupId == youtuberGroup }.map { it.author }.toSet()
                        val filtered = enriched.filter { directMemberSet.contains(it.author) && isYoutuberVisible(it.author) }
                        val sorted = when (filters.sortType) {
                            HistorySortType.DATE -> {
                                if (filters.sortOrder == SORTING.DESC) filtered.sortedBy { it.lastTime } else filtered.sortedBy { it.firstTime }
                            }
                            HistorySortType.TITLE -> filtered.sortedBy { it.author.lowercase() }
                            HistorySortType.DURATION -> filtered.sortedBy { it.videoCount }
                            HistorySortType.AUTHOR -> filtered.sortedBy { it.author.lowercase() }
                        }.run { if (filters.sortOrder == SORTING.DESC) this.asReversed() else this }
                        val groupInfos = groups
                            .filter { childGroupIds.contains(it.id) }
                            .map { group ->
                                val memberAuthors = members
                                    .filter { descendantGroups(group.id).contains(it.groupId) }
                                    .map { it.author }
                                    .filter { isYoutuberVisible(it) }
                                    .toSet()
                                val memberInfos = enriched.filter { memberAuthors.contains(it.author) }
                                com.ireum.ytdl.database.models.YoutuberGroupInfo(
                                    id = group.id,
                                    name = group.name,
                                    memberCount = memberAuthors.size,
                                    videoCount = memberInfos.sumOf { it.videoCount },
                                    thumbnail = memberInfos.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
                                )
                            }
                            .filter { showHiddenOnly || (it.memberCount > 0 && it.videoCount > 0) }
                            .sortedBy { it.name.lowercase() }
                        val list = ArrayList<UiModel>()
                        list.addAll(groupInfos.map { UiModel.YoutuberGroupModel(it) })
                        list.addAll(sorted.map { UiModel.YoutuberInfoModel(it) })
                        PagingData.from(list)
                    }
                } else {
                    combine(filteredYoutubersFlow, youtuberGroups, youtuberGroupMembers, youtuberMetaFlow, youtuberGroupRelations) { youtubers, groups, members, metas, relations ->
                        val metaMap = metas.associateBy { it.author }
                        val enriched = youtubers.map { info ->
                            val iconUrl = metaMap[info.author]?.iconUrl.orEmpty()
                            if (iconUrl.isNotBlank()) info.copy(thumbnail = iconUrl) else info
                        }
                        val groupedAuthors = members.map { it.author }.toSet()
                        val ungrouped = enriched.filter { !groupedAuthors.contains(it.author) && isYoutuberVisible(it.author) }
                        val sortedUngrouped = when (filters.sortType) {
                            HistorySortType.DATE -> {
                                if (filters.sortOrder == SORTING.DESC) {
                                    ungrouped.sortedBy { it.lastTime }
                                } else {
                                    ungrouped.sortedBy { it.firstTime }
                                }
                            }
                            HistorySortType.TITLE -> ungrouped.sortedBy { it.author.lowercase() }
                            HistorySortType.DURATION -> ungrouped.sortedBy { it.videoCount }
                            HistorySortType.AUTHOR -> ungrouped.sortedBy { it.author.lowercase() }
                        }.run {
                            if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                        }
                        val childrenByParent = relations.groupBy { it.parentGroupId }.mapValues { entry ->
                            entry.value.map { it.childGroupId }
                        }
                        val childGroupIds = relations.map { it.childGroupId }.toSet()
                        fun descendantGroups(startGroupId: Long): Set<Long> {
                            val visited = linkedSetOf<Long>()
                            val stack = ArrayDeque<Long>()
                            stack.add(startGroupId)
                            while (stack.isNotEmpty()) {
                                val id = stack.removeFirst()
                                if (!visited.add(id)) continue
                                childrenByParent[id].orEmpty().forEach { stack.addLast(it) }
                            }
                            return visited
                        }

                        val rootGroups = groups.filter { !childGroupIds.contains(it.id) && isGroupVisible(it.id) }
                        val explicitlyVisibleChildGroups = groups.filter {
                            childGroupIds.contains(it.id) &&
                                visibleChildYoutuberGroups.contains(it.id) &&
                                isGroupVisible(it.id)
                        }
                        val visibleGroups = (rootGroups + explicitlyVisibleChildGroups).distinctBy { it.id }
                        val groupInfos = visibleGroups.map { group ->
                            val memberAuthors = members
                                .filter { descendantGroups(group.id).contains(it.groupId) }
                                .map { it.author }
                                .filter { isYoutuberVisible(it) }
                                .toSet()
                            val memberInfos = enriched.filter { memberAuthors.contains(it.author) }
                            val totalVideos = memberInfos.sumOf { it.videoCount }
                            val thumb = memberInfos.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
                            com.ireum.ytdl.database.models.YoutuberGroupInfo(
                                id = group.id,
                                name = group.name,
                                memberCount = memberAuthors.size,
                                videoCount = totalVideos,
                                thumbnail = thumb
                            )
                        }.filter { showHiddenOnly || (it.memberCount > 0 && it.videoCount > 0) }.let { infos ->
                            val sortedGroups = when (filters.sortType) {
                                HistorySortType.DURATION -> infos.sortedBy { it.videoCount }
                                HistorySortType.TITLE -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.AUTHOR -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.DATE -> infos.sortedBy { it.name.lowercase() }
                            }.run {
                                if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                            }
                            sortedGroups
                        }

                        val list = ArrayList<UiModel>()
                        list.addAll(groupInfos.map { UiModel.YoutuberGroupModel(it) })
                        list.addAll(sortedUngrouped.map { UiModel.YoutuberInfoModel(it) })
                        PagingData.from(list)
                    }
                }
            } else {
                Log.d("HistoryPagingVM", "branch=historyList")
                historyListFlowFor(filters, excludedChildKeywords)
            }
        }
            .onStart { Log.d("HistoryPagingVM", "paginatedItems collector start") }
            .onEach { data -> Log.d("HistoryPagingVM", "paginatedItems emit data=${System.identityHashCode(data)}") }
            .distinctUntilChanged()
            .cachedIn(viewModelScope)

    }

    fun setSorting(sort: HistorySortType) {
        if (sortType.value != sort) {
            sortOrder.value = SORTING.DESC
        } else {
            sortOrder.value = if (sortOrder.value == SORTING.DESC) {
                SORTING.ASC
            } else {
                SORTING.DESC
            }
        }
        sortType.value = sort
    }

    fun setAuthorFilter(filter: String) {
        if (authorFilter.value == filter) return
        Log.d("HistoryPagingVM", "setAuthorFilter='$filter'")
        authorFilter.value = filter
    }

    fun setKeywordFilter(filter: String) {
        if (keywordFilter.value == filter) return
        Log.d("HistoryPagingVM", "setKeywordFilter='$filter'")
        keywordFilter.value = filter
    }

    fun setHiddenYoutubersFilter(hidden: Set<String>) {
        if (hiddenYoutubersFilter.value == hidden) return
        hiddenYoutubersFilter.value = hidden
    }

    fun setHiddenYoutuberGroupsFilter(hidden: Set<Long>) {
        if (hiddenYoutuberGroupsFilter.value == hidden) return
        hiddenYoutuberGroupsFilter.value = hidden
    }

    fun setShowHiddenOnlyFilter(enabled: Boolean) {
        if (showHiddenOnlyFilter.value == enabled) return
        showHiddenOnlyFilter.value = enabled
    }

    fun setExcludedChildKeywordsFilter(hidden: Set<String>) {
        if (excludedChildKeywordsFilter.value == hidden) return
        excludedChildKeywordsFilter.value = hidden
        invalidateCachedIds(triggerRefresh = true)
    }

    fun setVisibleChildYoutuberGroupsFilter(visible: Set<Long>) {
        if (visibleChildYoutuberGroupsFilter.value == visible) return
        visibleChildYoutuberGroupsFilter.value = visible
    }

    fun setVisibleChildKeywordsFilter(visible: Set<String>) {
        if (visibleChildKeywordsFilter.value == visible) return
        visibleChildKeywordsFilter.value = visible
    }

    fun setYoutuberGroupFilter(groupId: Long) {
        if (youtuberGroupFilter.value == groupId) return
        Log.d("HistoryPagingVM", "setYoutuberGroupFilter=$groupId")
        youtuberGroupFilter.value = groupId
    }

    fun setPlaylistGroupFilter(groupId: Long) {
        if (playlistGroupFilter.value == -1L) return
        Log.d("HistoryPagingVM", "setPlaylistGroupFilter ignored (playlist feature disabled)")
        playlistGroupFilter.value = -1L
    }

    fun setKeywordGroupFilter(groupId: Long) {
        if (keywordGroupFilter.value == groupId) return
        Log.d("HistoryPagingVM", "setKeywordGroupFilter=$groupId")
        keywordGroupFilter.value = groupId
    }

    fun setWebsiteFilter(filter: String) {
        if (websiteFilter.value == filter) return
        Log.d("HistoryPagingVM", "setWebsiteFilter='$filter'")
        websiteFilter.value = filter
    }

    fun setPlaylistFilter(playlistId: Long) {
        if (playlistFilter.value == -1L) return
        Log.d("HistoryPagingVM", "setPlaylistFilter ignored (playlist feature disabled)")
        playlistFilter.value = -1L
    }

    fun toggleYoutuberSelectionMode() {
        Log.d("HistoryPagingVM", "toggleYoutuberSelectionMode=${!isYoutuberSelectionMode.value}")
        isYoutuberSelectionMode.value = !isYoutuberSelectionMode.value
    }

    fun setYoutuberSelectionMode(enabled: Boolean) {
        if (isYoutuberSelectionMode.value == enabled) return
        Log.d("HistoryPagingVM", "setYoutuberSelectionMode=$enabled")
        isYoutuberSelectionMode.value = enabled
    }

    fun togglePlaylistSelectionMode() {
        Log.d("HistoryPagingVM", "togglePlaylistSelectionMode ignored (playlist feature disabled)")
        isPlaylistSelectionMode.value = false
    }

    fun setPlaylistSelectionMode(enabled: Boolean) {
        if (!isPlaylistSelectionMode.value) return
        Log.d("HistoryPagingVM", "setPlaylistSelectionMode ignored (playlist feature disabled)")
        isPlaylistSelectionMode.value = false
    }

    fun toggleKeywordSelectionMode() {
        Log.d("HistoryPagingVM", "toggleKeywordSelectionMode=${!isKeywordSelectionMode.value}")
        isKeywordSelectionMode.value = !isKeywordSelectionMode.value
    }

    fun setKeywordSelectionMode(enabled: Boolean) {
        if (isKeywordSelectionMode.value == enabled) return
        Log.d("HistoryPagingVM", "setKeywordSelectionMode=$enabled")
        isKeywordSelectionMode.value = enabled
    }

    fun setRecentMode(enabled: Boolean) {
        if (isRecentMode.value == enabled) return
        Log.d("HistoryPagingVM", "setRecentMode=$enabled")
        isRecentMode.value = enabled
    }

    fun setQueryFilter(filter: String) {
        if (queryFilter.value == filter) return
        Log.d("HistoryPagingVM", "setQueryFilter='$filter'")
        queryFilter.value = filter
    }

    fun setTitleQueryFilter(filter: String) {
        if (titleQueryFilter.value == filter) return
        titleQueryFilter.value = filter
    }

    fun setKeywordQueryFilter(filter: String) {
        if (keywordQueryFilter.value == filter) return
        keywordQueryFilter.value = filter
    }

    fun setCreatorQueryFilter(filter: String) {
        if (creatorQueryFilter.value == filter) return
        creatorQueryFilter.value = filter
    }

    fun setSearchFieldsFilter(fields: Set<HistoryRepository.SearchField>) {
        val normalized = if (fields.isEmpty()) {
            setOf(
                HistoryRepository.SearchField.TITLE,
                HistoryRepository.SearchField.KEYWORDS
            )
        } else {
            fields
        }
        if (searchFieldsFilter.value == normalized) return
        searchFieldsFilter.value = normalized
    }

    fun setIncludeChildCategoryVideosFilter(enabled: Boolean) {
        if (includeChildCategoryVideosFilter.value == enabled) return
        includeChildCategoryVideosFilter.value = enabled
        invalidateCachedIds(triggerRefresh = true)
    }

    @Suppress("unused")
    fun setTypeFilter(filter: String) {
        if (typeFilter.value == filter) return
        typeFilter.value = filter
    }

    fun setStatusFilter(status: HistoryStatus) {
        if (statusFilter.value == status) return
        statusFilter.value = status
    }

    private fun historyListFlowFor(filters: HistoryFilters, excludedChildKeywords: Set<String>): Flow<PagingData<UiModel>> {
        viewModelScope.launch(Dispatchers.IO) {
            totalCount.value = repository.getFilteredCount(
                filters.query,
                filters.type,
                filters.author,
                filters.keyword,
                filters.titleQuery,
                filters.keywordQuery,
                filters.creatorQuery,
                filters.website,
                filters.playlistId,
                filters.searchFields
            )
        }

        val pager = Pager(
            config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 1),
            pagingSourceFactory = {
                repository.getPaginatedSource(
                    filters.query,
                    filters.type,
                    filters.author,
                    if (filters.includeChildCategoryVideos && filters.keyword.isNotBlank()) "" else filters.keyword,
                    filters.titleQuery,
                    filters.keywordQuery,
                    filters.creatorQuery,
                    filters.sortType,
                    filters.sortOrder,
                    filters.website,
                    filters.playlistId,
                    filters.searchFields
                )
            }
        )

        val baseFlow = pager.flow.map { pagingData: PagingData<HistoryItem> ->
            val filteredPagingData: PagingData<HistoryItem> = when (filters.status) {
                HistoryStatus.DELETED -> {
                    pagingData.filter { item: HistoryItem ->
                        item.downloadPath.any { path: String -> !FileUtil.exists(path) }
                    }
                }
                HistoryStatus.NOT_DELETED -> {
                    pagingData.filter { item: HistoryItem ->
                        item.downloadPath.any { path: String -> FileUtil.exists(path) }
                    }
                }
                HistoryStatus.MISSING_THUMBNAIL -> {
                    pagingData.filter { item: HistoryItem ->
                        val hasCustomThumb = item.customThumb.isNotBlank() && FileUtil.exists(item.customThumb)
                        val hasThumb = item.thumb.isNotBlank()
                        !hasCustomThumb && !hasThumb
                    }
                }
                HistoryStatus.CUSTOM_THUMBNAIL -> {
                    pagingData.filter { item: HistoryItem ->
                        item.customThumb.isNotBlank() && FileUtil.exists(item.customThumb)
                    }
                }
                else -> pagingData
            }

            filteredPagingData.map { historyItem: HistoryItem ->
                UiModel.HistoryItemModel(resolveLocalTreePath(historyItem)) as UiModel
            }
        }

        if (filters.author.isBlank() && filters.keyword.isBlank()) {
            return baseFlow
        }

        val relatedKeywordsFlow = flow {
            val relationIds = withContext(Dispatchers.IO) {
                repository.getFilteredIDs(
                    filters.query,
                    filters.type,
                    "",
                    "",
                    filters.titleQuery,
                    filters.keywordQuery,
                    filters.creatorQuery,
                    filters.sortType,
                    filters.sortOrder,
                    filters.status,
                    filters.website,
                    filters.playlistId,
                    filters.searchFields
                )
            }
            val allKeywords = withContext(Dispatchers.IO) {
                repository.getKeywordsWithInfoForHistoryIds(relationIds)
            }
            val selectedKeyword = filters.keyword.trim()
            val selectedKeywordInfo = allKeywords.firstOrNull { it.keyword.equals(selectedKeyword, ignoreCase = true) }
            val relatedHeaderKeywords: List<com.ireum.ytdl.database.models.KeywordInfo>
            val videoKeywordNamesLower: Set<String>
            val excludedVideoKeywordNamesLower: Set<String>
            when {
                filters.author.isNotBlank() -> {
                    val normalizedAuthor = normalizeCreator(filters.author)
                    val related = allKeywords.filter {
                        val creator = it.uniqueCreator ?: return@filter false
                        normalizeCreator(creator) == normalizedAuthor
                    }
                    val excludedRecursive = buildExcludedRecursiveForAuthor(
                        authorKeywords = related,
                        excludedChildKeywords = excludedChildKeywords,
                        includeChildCategoryVideos = filters.includeChildCategoryVideos
                    )
                    relatedHeaderKeywords = related
                    videoKeywordNamesLower = emptySet()
                    excludedVideoKeywordNamesLower = excludedRecursive
                        .map { it.lowercase(Locale.getDefault()) }
                        .toSet()
                }
                selectedKeywordInfo != null -> {
                    val byName = allKeywords.associateBy { it.keyword }
                    val excludedRecursive = buildExcludedRecursiveForKeyword(
                        selectedKeywordInfo = selectedKeywordInfo,
                        byName = byName,
                        excludedChildKeywords = excludedChildKeywords,
                        includeChildCategoryVideos = filters.includeChildCategoryVideos
                    )
                    val directChildren = selectedKeywordInfo.childKeywords.toSet()
                    relatedHeaderKeywords = allKeywords.filter { directChildren.contains(it.keyword) }

                    val videoNames = mutableSetOf(selectedKeywordInfo.keyword)
                    if (filters.includeChildCategoryVideos) {
                        val stack = ArrayDeque<String>()
                        stack.addAll(selectedKeywordInfo.childKeywords)
                        while (stack.isNotEmpty()) {
                            val name = stack.removeFirst()
                            if (!videoNames.add(name)) continue
                            byName[name]?.childKeywords.orEmpty().forEach { stack.addLast(it) }
                        }
                    }
                    videoKeywordNamesLower = videoNames.map { it.lowercase(Locale.getDefault()) }.toSet()
                    excludedVideoKeywordNamesLower = excludedRecursive
                        .map { it.lowercase(Locale.getDefault()) }
                        .toSet()
                }
                else -> {
                    relatedHeaderKeywords = emptyList()
                    videoKeywordNamesLower = emptySet()
                    excludedVideoKeywordNamesLower = emptySet()
                }
            }
            emit(Triple(relatedHeaderKeywords, videoKeywordNamesLower, excludedVideoKeywordNamesLower))
        }

        return combine(relatedKeywordsFlow, baseFlow) { related, pagingData ->
            val keywords = related.first
            val videoKeywords = related.second
            val excludedVideoKeywords = related.third
            var withHeaders = if (filters.keyword.isNotBlank() && videoKeywords.isNotEmpty()) {
                pagingData.filter { model ->
                    val item = (model as? UiModel.HistoryItemModel)?.historyItem ?: return@filter false
                    splitKeywords(item.keywords).any { videoKeywords.contains(it.lowercase(Locale.getDefault())) }
                }
            } else {
                pagingData
            }
            if (filters.author.isNotBlank() && excludedVideoKeywords.isNotEmpty()) {
                withHeaders = withHeaders.filter { model ->
                    val item = (model as? UiModel.HistoryItemModel)?.historyItem ?: return@filter false
                    splitKeywords(item.keywords).none { excludedVideoKeywords.contains(it.lowercase(Locale.getDefault())) }
                }
            }
            if (filters.keyword.isNotBlank() && excludedVideoKeywords.isNotEmpty()) {
                withHeaders = withHeaders.filter { model ->
                    val item = (model as? UiModel.HistoryItemModel)?.historyItem ?: return@filter false
                    splitKeywords(item.keywords).none { excludedVideoKeywords.contains(it.lowercase(Locale.getDefault())) }
                }
            }
            keywords
                .sortedByDescending { it.videoCount }
                .asReversed()
                .forEach { info ->
                    withHeaders = withHeaders.insertHeaderItem(item = UiModel.KeywordInfoModel(info))
                }
            withHeaders
        }
    }

    private fun normalizeCreator(value: String): String {
        return value.trim().trim('"').lowercase(Locale.getDefault())
    }

    private fun splitKeywords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun getIDsBetweenTwoItems(firstID: Long, secondID: Long): List<Long> {
        val ids = getFilteredIdsSnapshot()
        val firstIndex = ids.indexOf(firstID)
        val secondIndex = ids.indexOf(secondID)
        return if (firstIndex > secondIndex) {
            ids.filterIndexed { index, _ -> index < firstIndex && index > secondIndex }
        } else {
            ids.filterIndexed { index, _ -> index > firstIndex && index < secondIndex }
        }
    }

    fun getItemIDsNotPresentIn(not: List<Long>): List<Long> {
        val ids = getFilteredIdsSnapshot()
        if (not.isEmpty()) {
            return ids
        }
        val exclude = not.toHashSet()
        return ids.filter { !exclude.contains(it) }
    }

    private fun getFilteredIdsSnapshot(): List<Long> {
        val filters = HistoryFilters(
            typeFilter.value,
            sortType.value,
            sortOrder.value,
            queryFilter.value,
            titleQueryFilter.value,
            keywordQueryFilter.value,
            creatorQueryFilter.value,
            includeChildCategoryVideosFilter.value,
            searchFieldsFilter.value,
            statusFilter.value,
            authorFilter.value,
            keywordFilter.value,
            websiteFilter.value,
            playlistFilter.value
        )
        val cached = cachedIds
        if (cached != null && cachedIdsKey == filters) {
            return cached
        }
        val ids = repository.getFilteredIDs(
            filters.query,
            filters.type,
            filters.author,
            filters.keyword,
            filters.titleQuery,
            filters.keywordQuery,
            filters.creatorQuery,
            filters.sortType,
            filters.sortOrder,
            filters.status,
            filters.website,
            filters.playlistId,
            filters.searchFields
        )
        val finalIds = applyExcludedChildKeywordFilterToIds(
            ids = ids,
            filters = filters,
            excludedChildKeywords = excludedChildKeywordsFilter.value
        )
        cachedIdsKey = filters
        cachedIds = finalIds
        return finalIds
    }

    private fun applyExcludedChildKeywordFilterToIds(
        ids: List<Long>,
        filters: HistoryFilters,
        excludedChildKeywords: Set<String>
    ): List<Long> {
        if (ids.isEmpty()) return ids
        if (filters.author.isBlank() && filters.keyword.isBlank()) return ids

        val allKeywords = repository.getKeywordsWithInfoForHistoryIds(ids)
        val excludedLower: Set<String> = when {
            filters.keyword.isNotBlank() -> {
                val selectedKeyword = filters.keyword.trim()
                val selectedKeywordInfo = allKeywords.firstOrNull { it.keyword.equals(selectedKeyword, ignoreCase = true) }
                    ?: return ids
                val byName = allKeywords.associateBy { it.keyword }
                buildExcludedRecursiveForKeyword(
                    selectedKeywordInfo = selectedKeywordInfo,
                    byName = byName,
                    excludedChildKeywords = excludedChildKeywords,
                    includeChildCategoryVideos = filters.includeChildCategoryVideos
                ).map { it.lowercase(Locale.getDefault()) }.toSet()
            }
            filters.author.isNotBlank() -> {
                val normalizedAuthor = normalizeCreator(filters.author)
                val authorKeywords = allKeywords.filter {
                    val creator = it.uniqueCreator ?: return@filter false
                    normalizeCreator(creator) == normalizedAuthor
                }
                buildExcludedRecursiveForAuthor(
                    authorKeywords = authorKeywords,
                    excludedChildKeywords = excludedChildKeywords,
                    includeChildCategoryVideos = filters.includeChildCategoryVideos
                ).map { it.lowercase(Locale.getDefault()) }.toSet()
            }
            else -> emptySet()
        }

        if (excludedLower.isEmpty()) return ids
        val itemsById = repository.getItemsFromIDs(ids).associateBy { it.id }
        return ids.filter { id ->
            val item = itemsById[id] ?: return@filter false
            splitKeywords(item.keywords).none { excludedLower.contains(it.lowercase(Locale.getDefault())) }
        }
    }

    private fun buildExcludedRecursiveForAuthor(
        authorKeywords: List<com.ireum.ytdl.database.models.KeywordInfo>,
        excludedChildKeywords: Set<String>,
        includeChildCategoryVideos: Boolean
    ): Set<String> {
        if (authorKeywords.isEmpty()) return emptySet()
        val byName = authorKeywords.associateBy { it.keyword }
        val seeds = when {
            excludedChildKeywords.isNotEmpty() -> {
                authorKeywords
                    .filter { excludedChildKeywords.contains(it.keyword) }
                    .map { it.keyword }
            }
            !includeChildCategoryVideos -> {
                authorKeywords
                    .filter { info -> info.parentKeywords.none { byName.containsKey(it) } }
                    .map { it.keyword }
            }
            else -> emptyList()
        }
        return collectRecursiveKeywords(seeds, byName)
    }

    private fun buildExcludedRecursiveForKeyword(
        selectedKeywordInfo: com.ireum.ytdl.database.models.KeywordInfo,
        byName: Map<String, com.ireum.ytdl.database.models.KeywordInfo>,
        excludedChildKeywords: Set<String>,
        includeChildCategoryVideos: Boolean
    ): Set<String> {
        val seeds = when {
            excludedChildKeywords.isNotEmpty() -> {
                selectedKeywordInfo.childKeywords.filter { excludedChildKeywords.contains(it) }
            }
            !includeChildCategoryVideos -> {
                selectedKeywordInfo.childKeywords
            }
            else -> emptyList()
        }
        return collectRecursiveKeywords(seeds, byName)
    }

    private fun collectRecursiveKeywords(
        seeds: List<String>,
        byName: Map<String, com.ireum.ytdl.database.models.KeywordInfo>
    ): Set<String> {
        if (seeds.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        val stack = ArrayDeque<String>()
        seeds.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val keyword = stack.removeFirst()
            if (!out.add(keyword)) continue
            byName[keyword]?.childKeywords.orEmpty().forEach { stack.addLast(it) }
        }
        return out
    }

    fun getAll(): List<HistoryItem> {
        return repository.getAll()
    }

    suspend fun getKeywordInfoByNameForCurrentFilters(keyword: String): com.ireum.ytdl.database.models.KeywordInfo? {
        val infos = getKeywordInfosForCurrentFilters()
        return infos.firstOrNull { it.keyword.equals(keyword, ignoreCase = true) }
    }

    suspend fun getRootKeywordInfosByAuthorForCurrentFilters(author: String): List<com.ireum.ytdl.database.models.KeywordInfo> {
        val infos = getKeywordInfosForCurrentFilters()
        val normalizedAuthor = normalizeCreator(author)
        val authorKeywords = infos.filter {
            val creator = it.uniqueCreator ?: return@filter false
            normalizeCreator(creator) == normalizedAuthor
        }
        val byName = authorKeywords.associateBy { it.keyword }
        return authorKeywords
            .filter { info -> info.parentKeywords.none { byName.containsKey(it) } }
            .sortedByDescending { it.videoCount }
    }

    private suspend fun getKeywordInfosForCurrentFilters(): List<com.ireum.ytdl.database.models.KeywordInfo> {
        val ids = withContext(Dispatchers.IO) {
            repository.getFilteredIDs(
                queryFilter.value,
                typeFilter.value,
                "",
                "",
                titleQueryFilter.value,
                keywordQueryFilter.value,
                creatorQueryFilter.value,
                sortType.value,
                sortOrder.value,
                statusFilter.value,
                websiteFilter.value,
                playlistFilter.value,
                searchFieldsFilter.value
            )
        }
        return withContext(Dispatchers.IO) {
            repository.getKeywordsWithInfoForHistoryIds(ids)
        }
    }

    fun getByID(id: Long): HistoryItem {
        return resolveLocalTreePath(repository.getItem(id))
    }

    fun insert(item: HistoryItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(item)
        invalidateCachedIds(triggerRefresh = true)
    }

    fun delete(item: HistoryItem, deleteFile: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(item, deleteFile)
        invalidateCachedIds(triggerRefresh = true)
    }

    fun deleteAllWithIDs(ids: List<Long>, deleteFile: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAllWithIDs(ids, deleteFile)
        invalidateCachedIds(triggerRefresh = true)
    }

    fun deleteAllWithIDsCheckFiles(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAllWithIDsCheckFiles(ids)
        invalidateCachedIds(triggerRefresh = true)
    }

    fun getDownloadPathsFromIDs(ids: List<Long>): List<List<String>> {
        return repository.getDownloadPathsFromIDs(ids)
    }

    fun deleteAll(deleteFile: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAll(deleteFile)
        invalidateCachedIds(triggerRefresh = true)
    }

    fun deleteDuplicates() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteDuplicates()
        invalidateCachedIds(triggerRefresh = true)
    }

    fun update(item: HistoryItem) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("HistoryPagingVM", "update id=${item.id} author='${item.author}' title='${item.title}'")
        repository.update(item)
        invalidateCachedIds(triggerRefresh = true)
    }

    fun clearDeleted() = viewModelScope.launch(Dispatchers.IO) {
        repository.clearDeletedHistory()
        invalidateCachedIds(triggerRefresh = true)
    }

    fun removeKeywordsFromAllHistory(targetKeywords: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        repository.removeKeywordsFromAllHistory(targetKeywords)
        invalidateCachedIds(triggerRefresh = true)
    }

    private fun invalidateCachedIds(triggerRefresh: Boolean = false) {
        cachedIdsKey = null
        cachedIds = null
        if (triggerRefresh) {
            refreshTrigger.value = refreshTrigger.value + 1L
        }
    }

    private fun fetchMissingYoutuberMeta(
        authors: List<String>,
        metaDao: com.ireum.ytdl.database.dao.YoutuberMetaDao
    ) {
        val app = getApplication<Application>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val apiKey = prefs.getString("api_key", "").orEmpty()
        if (apiKey.isBlank()) return
        val language = resolveLanguage(prefs)
        val region = resolveRegion(prefs, language)
        val now = System.currentTimeMillis()
        if (now - lastQuotaExceededAt < 5 * 60 * 1000L) return
        authors.forEach { author ->
            if (author.isBlank()) return@forEach
            if (youtuberMetaQueue.size >= 200) return@forEach
            youtuberMetaQueue.add(author)
        }
        if (youtuberMetaJob?.isActive == true) return

        youtuberMetaJob = viewModelScope.launch(Dispatchers.IO) {
            val api = YoutubeApiUtil(app)
            var failures = 0
            while (isActive) {
                val author = synchronized(youtuberMetaQueue) {
                    youtuberMetaQueue.firstOrNull()?.also { youtuberMetaQueue.remove(it) }
                } ?: break
                if (pendingYoutuberMeta.contains(author)) {
                    continue
                }
                pendingYoutuberMeta.add(author)
                try {
                    val channel = api.searchChannelByName(author, language, region)
                    if (channel != null) {
                        val meta = com.ireum.ytdl.database.models.YoutuberMeta(
                            author = author,
                            channelUrl = channel.channelUrl,
                            iconUrl = channel.iconUrl
                        )
                        metaDao.upsert(meta)
                    }
                    if (api.wasQuotaExceeded()) {
                        lastQuotaExceededAt = System.currentTimeMillis()
                        break
                    }
                    failures = 0
                } catch (e: Exception) {
                    failures += 1
                    Log.d("HistoryViewModel", "youtuber meta fetch failed author=$author error=${e.message}")
                    if (failures >= 5) {
                        failures = 0
                        delay(1000)
                    }
                } finally {
                    pendingYoutuberMeta.remove(author)
                }
                delay(200)
            }
        }
    }

    private fun resolveLanguage(prefs: android.content.SharedPreferences): String {
        val pref = prefs.getString("app_language", "") ?: ""
        return if (pref.isBlank() || pref == "system") {
            Locale.getDefault().language.ifBlank { "en" }
        } else {
            pref
        }
    }

    private fun resolveRegion(prefs: android.content.SharedPreferences, language: String): String {
        val pref = prefs.getString("locale", "") ?: ""
        if (pref.isNotBlank()) return pref
        if (language == "ko") return "KR"
        return Locale.getDefault().country.ifBlank { "US" }
    }

    private fun resolveLocalTreePath(item: HistoryItem): HistoryItem {
        if (item.localTreeUri.isBlank() || item.localTreePath.isBlank()) return item
        if (item.downloadPath.any { FileUtil.exists(it) }) return item
        if (!loggedTreePermissions) {
            loggedTreePermissions = true
            val perms = getApplication<Application>().contentResolver.persistedUriPermissions
            Log.d(
                "LocalTreeRestore",
                "persistedUriPermissions=${perms.joinToString { p -> "${p.uri} r=${p.isReadPermission} w=${p.isWritePermission}" }}"
            )
        }
        Log.d(
            "LocalTreeRestore",
            "missing local path id=${item.id} title=${item.title} treeUri=${item.localTreeUri} treePath=${item.localTreePath} downloadPath=${item.downloadPath}"
        )
        val resolvedUri = FileUtil.resolveTreeDocumentUri(item.localTreeUri, item.localTreePath) ?: return item
        val resolvedPath = resolvedUri.toString()
        Log.d(
            "LocalTreeRestore",
            "resolved uri=$resolvedPath exists=${FileUtil.exists(resolvedPath)}"
        )
        if (!FileUtil.exists(resolvedPath)) return item
        val updated = item.copy(downloadPath = listOf(resolvedPath))
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(updated)
        }
        return updated
    }

}



