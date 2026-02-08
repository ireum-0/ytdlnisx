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
import com.ireum.ytdl.database.repository.PlaylistRepository
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
    private val playlistRepository: PlaylistRepository
    val sortOrder = MutableStateFlow(SORTING.DESC)
    val sortType = MutableStateFlow(HistorySortType.DATE)
    val authorFilter = MutableStateFlow("")
    val websiteFilter = MutableStateFlow("")
    val playlistFilter = MutableStateFlow(-1L)
    val statusFilter = MutableStateFlow(HistoryStatus.ALL)
    val isYoutuberSelectionMode = MutableStateFlow(false)  // ?�튜�??�택 모드
    val isPlaylistSelectionMode = MutableStateFlow(false)
    val isRecentMode = MutableStateFlow(false)
    val youtuberGroupFilter = MutableStateFlow(-1L)
    val playlistGroupFilter = MutableStateFlow(-1L)
    private val queryFilter = MutableStateFlow("")
    val searchFieldsFilter = MutableStateFlow(
        setOf(
            HistoryRepository.SearchField.TITLE,
            HistoryRepository.SearchField.KEYWORDS
        )
    )
    private val typeFilter = MutableStateFlow("")
    val queryFilterFlow = queryFilter.asStateFlow()
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
    var youtuberInfos: Flow<List<YoutuberInfo>>
    var youtuberGroups: Flow<List<com.ireum.ytdl.database.models.YoutuberGroup>>
    var youtuberGroupMembers: Flow<List<com.ireum.ytdl.database.models.YoutuberGroupMember>>
    var playlistGroups: Flow<List<com.ireum.ytdl.database.models.PlaylistGroup>>
    var playlistGroupMembers: Flow<List<com.ireum.ytdl.database.models.PlaylistGroupMember>>
    private val youtuberMetaFlow: Flow<List<com.ireum.ytdl.database.models.YoutuberMeta>>
    var playlistInfos: Flow<List<com.ireum.ytdl.database.models.PlaylistInfo>>
    private val recentItems: Flow<List<HistoryItem>>
    private val recentPlaylists: Flow<List<com.ireum.ytdl.database.models.PlaylistInfo>>
    private val playlistsByAuthor: Flow<List<com.ireum.ytdl.database.models.PlaylistInfo>>
    var totalCount = MutableStateFlow(0)

    data class HistoryFilters(
        var type: String = "",
        var sortType: HistorySortType = HistorySortType.DATE,
        var sortOrder: SORTING = SORTING.DESC,
        var query: String = "",
        var searchFields: Set<HistoryRepository.SearchField> = setOf(
            HistoryRepository.SearchField.TITLE,
            HistoryRepository.SearchField.KEYWORDS
        ),
        var status: HistoryStatus = HistoryStatus.ALL,
        var author: String = "",
        var website: String = "",
        var playlistId: Long = -1L
    )
    data class HistoryListKey(
        val type: String,
        val sortType: HistorySortType,
        val sortOrder: SORTING,
        val query: String,
        val searchFields: Set<HistoryRepository.SearchField>,
        val status: HistoryStatus,
        val author: String,
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

    init {
        val db = DBManager.getInstance(application)
        val dao = db.historyDao
        val playlistDao = db.playlistDao
        val playlistGroupDao = db.playlistGroupDao
        val groupDao = db.youtuberGroupDao
        val metaDao = db.youtuberMetaDao
        repository = HistoryRepository(dao, playlistDao)
        playlistRepository = PlaylistRepository(playlistDao, playlistGroupDao)
        websites = repository.websites
        authors = repository.authors
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
        playlistGroups = playlistGroupDao.getGroupsFlow()
        playlistGroupMembers = playlistGroupDao.getAllMembersFlow()
        playlistInfos = playlistRepository.getPlaylistsWithInfo()
        recentItems = repository.getRecent(20)
        recentPlaylists = playlistRepository.getRecentPlaylistsWithInfo(20)
        playlistsByAuthor = authorFilter.flatMapLatest { author ->
            if (author.isBlank()) flowOf(emptyList())
            else playlistRepository.getPlaylistsWithInfoByAuthor(author)
        }

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
            combine(queryFilter, typeFilter, playlistFilter, searchFieldsFilter) { q: String, t: String, p: Long, sf: Set<HistoryRepository.SearchField> ->
                Quadruple(q, t, p, sf)
            }
        ) { quint: Quintuple<SORTING, HistorySortType, String, HistoryStatus, String>, quad: Quadruple<String, String, Long, Set<HistoryRepository.SearchField>> ->
            HistoryFilters(
                type = quad.second,
                sortType = quint.second,
                sortOrder = quint.first,
                query = quad.first,
                searchFields = quad.fourth,
                status = quint.fourth,
                author = quint.third,
                website = quint.fifth,
                playlistId = quad.third
            )
        }

        val modeFlow = combine(
            filtersFlow,
            isYoutuberSelectionMode,
            isPlaylistSelectionMode,
            isRecentMode,
            youtuberGroupFilter
        ) { filters, isSelectionMode, isPlaylistMode, isRecent, youtuberGroup ->
            Quintuple(filters, isSelectionMode, isPlaylistMode, isRecent, youtuberGroup)
        }

        paginatedItems = combine(modeFlow, playlistGroupFilter) { mode, playlistGroup ->
            Sextuple(mode.first, mode.second, mode.third, mode.fourth, mode.fifth, playlistGroup)
        }.flatMapLatest { (filters, isSelectionMode, isPlaylistMode, isRecent, youtuberGroup, playlistGroup) ->
            Log.d(
                "HistoryPagingVM",
                "switch filters=${filters} youtuberMode=$isSelectionMode playlistMode=$isPlaylistMode recent=$isRecent yGroup=$youtuberGroup pGroup=$playlistGroup"
            )
            if (isPlaylistMode) {
                Log.d("HistoryPagingVM", "branch=playlistMode group=$playlistGroup")
                val filteredPlaylistsFlow = flow {
                    val ids = withContext(Dispatchers.IO) {
                        repository.getFilteredIDs(
                            filters.query,
                            filters.type,
                            filters.author,
                            filters.sortType,
                            filters.sortOrder,
                            filters.status,
                            filters.website,
                            filters.playlistId,
                            filters.searchFields
                        )
                    }
                    val playlists = withContext(Dispatchers.IO) {
                        playlistRepository.getPlaylistsWithInfoForHistoryIds(ids)
                    }
                    emit(playlists)
                }
                if (playlistGroup >= 0L) {
                    combine(filteredPlaylistsFlow, playlistGroupMembers) { playlists, members ->
                        val memberIds = members.filter { it.groupId == playlistGroup }.map { it.playlistId }.toSet()
                        val filtered = playlists.filter { memberIds.contains(it.id) }
                        val sorted = when (filters.sortType) {
                            HistorySortType.DATE -> filtered.sortedBy { it.id }
                            HistorySortType.TITLE -> filtered.sortedBy { it.name.lowercase() }
                            HistorySortType.AUTHOR -> filtered.sortedBy { it.name.lowercase() }
                            HistorySortType.DURATION -> filtered.sortedBy { it.itemCount }
                        }.run {
                            if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                        }
                        PagingData.from(sorted.map { UiModel.PlaylistInfoModel(it) as UiModel })
                    }
                } else {
                    combine(filteredPlaylistsFlow, playlistGroups, playlistGroupMembers) { playlists, groups, members ->
                        val groupedIds = members.map { it.playlistId }.toSet()
                        val ungrouped = playlists.filter { !groupedIds.contains(it.id) }
                        val sortedUngrouped = when (filters.sortType) {
                            HistorySortType.DATE -> ungrouped.sortedBy { it.id }
                            HistorySortType.TITLE -> ungrouped.sortedBy { it.name.lowercase() }
                            HistorySortType.AUTHOR -> ungrouped.sortedBy { it.name.lowercase() }
                            HistorySortType.DURATION -> ungrouped.sortedBy { it.itemCount }
                        }.run {
                            if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                        }

                        val groupInfos = groups.map { group ->
                            val memberIds = members.filter { it.groupId == group.id }.map { it.playlistId }.toSet()
                            val memberInfos = playlists.filter { memberIds.contains(it.id) }
                            val totalItems = memberInfos.sumOf { it.itemCount }
                            val thumb = memberInfos.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
                            com.ireum.ytdl.database.models.PlaylistGroupInfo(
                                id = group.id,
                                name = group.name,
                                memberCount = memberIds.size,
                                itemCount = totalItems,
                                thumbnail = thumb
                            )
                        }.filter { it.memberCount > 0 && it.itemCount > 0 }.let { infos ->
                            val sortedGroups = when (filters.sortType) {
                                HistorySortType.DURATION -> infos.sortedBy { it.itemCount }
                                HistorySortType.TITLE -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.AUTHOR -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.DATE -> infos.sortedBy { it.name.lowercase() }
                            }.run {
                                if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                            }
                            sortedGroups
                        }

                        val list = ArrayList<UiModel>()
                        list.addAll(groupInfos.map { UiModel.PlaylistGroupModel(it) })
                        list.addAll(sortedUngrouped.map { UiModel.PlaylistInfoModel(it) })
                        PagingData.from(list)
                    }
                }
            } else if (isRecent) {
                Log.d("HistoryPagingVM", "branch=recent")
                combine(recentItems, recentPlaylists) { items, playlists ->
                    val itemsMax = items.take(20)
                    val playlistsMax = playlists.take(20)
                    fun recentTime(item: HistoryItem): Long {
                        return if (item.lastWatched > 0L) item.lastWatched else item.time
                    }
                    val historyByPlaylist = itemsMax.groupBy { item ->
                        playlistsMax.firstOrNull { pl -> item.downloadPath.any { path ->
                            path.contains("/${pl.name}/") || path.contains("\\${pl.name}\\")
                        } }?.id ?: -1L
                    }
                    val playlistLastTime = playlistsMax.associate { pl ->
                        val last = historyByPlaylist[pl.id]?.maxOfOrNull { recentTime(it) } ?: 0L
                        pl.id to last
                    }
                    val merged = ArrayList<UiModel>()
                    merged.addAll(itemsMax.map { UiModel.HistoryItemModel(it) })
                    merged.addAll(playlistsMax.map { UiModel.PlaylistInfoModel(it) })
                    val sorted = merged.sortedByDescending { model ->
                        when (model) {
                            is UiModel.HistoryItemModel -> recentTime(model.historyItem)
                            is UiModel.PlaylistInfoModel -> playlistLastTime[model.playlistInfo.id] ?: 0L
                            else -> 0L
                        }
                    }.take(20)
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
                if (youtuberGroup >= 0L) {
                    combine(filteredYoutubersFlow, youtuberGroupMembers, youtuberMetaFlow) { youtubers, members, metas ->
                        val metaMap = metas.associateBy { it.author }
                        val enriched = youtubers.map { info ->
                            val iconUrl = metaMap[info.author]?.iconUrl.orEmpty()
                            if (iconUrl.isNotBlank()) info.copy(thumbnail = iconUrl) else info
                        }
                        val memberSet = members.filter { it.groupId == youtuberGroup }.map { it.author }.toSet()
                        val filtered = enriched.filter { memberSet.contains(it.author) }
                        val sorted = when (filters.sortType) {
                            HistorySortType.DATE -> {
                                if (filters.sortOrder == SORTING.DESC) {
                                    filtered.sortedBy { it.lastTime }
                                } else {
                                    filtered.sortedBy { it.firstTime }
                                }
                            }
                            HistorySortType.TITLE -> filtered.sortedBy { it.author.lowercase() }
                            HistorySortType.DURATION -> filtered.sortedBy { it.videoCount }
                            HistorySortType.AUTHOR -> filtered.sortedBy { it.author.lowercase() }
                        }.run {
                            if (filters.sortOrder == SORTING.DESC) this.asReversed() else this
                        }
                        PagingData.from(sorted.map { UiModel.YoutuberInfoModel(it) as UiModel })
                    }
                } else {
                    combine(filteredYoutubersFlow, youtuberGroups, youtuberGroupMembers, youtuberMetaFlow) { youtubers, groups, members, metas ->
                        val metaMap = metas.associateBy { it.author }
                        val enriched = youtubers.map { info ->
                            val iconUrl = metaMap[info.author]?.iconUrl.orEmpty()
                            if (iconUrl.isNotBlank()) info.copy(thumbnail = iconUrl) else info
                        }
                        val groupedAuthors = members.map { it.author }.toSet()
                        val ungrouped = enriched.filter { !groupedAuthors.contains(it.author) }
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

                        val groupInfos = groups.map { group ->
                            val memberAuthors = members.filter { it.groupId == group.id }.map { it.author }.toSet()
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
                        }.filter { it.memberCount > 0 && it.videoCount > 0 }.let { infos ->
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
                historyListFlowFor(filters)
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

    fun setYoutuberGroupFilter(groupId: Long) {
        if (youtuberGroupFilter.value == groupId) return
        Log.d("HistoryPagingVM", "setYoutuberGroupFilter=$groupId")
        youtuberGroupFilter.value = groupId
    }

    fun setPlaylistGroupFilter(groupId: Long) {
        if (playlistGroupFilter.value == groupId) return
        Log.d("HistoryPagingVM", "setPlaylistGroupFilter=$groupId")
        playlistGroupFilter.value = groupId
    }

    fun setWebsiteFilter(filter: String) {
        if (websiteFilter.value == filter) return
        Log.d("HistoryPagingVM", "setWebsiteFilter='$filter'")
        websiteFilter.value = filter
    }

    fun setPlaylistFilter(playlistId: Long) {
        if (playlistFilter.value == playlistId) return
        Log.d("HistoryPagingVM", "setPlaylistFilter=$playlistId")
        playlistFilter.value = playlistId
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
        Log.d("HistoryPagingVM", "togglePlaylistSelectionMode=${!isPlaylistSelectionMode.value}")
        isPlaylistSelectionMode.value = !isPlaylistSelectionMode.value
    }

    fun setPlaylistSelectionMode(enabled: Boolean) {
        if (isPlaylistSelectionMode.value == enabled) return
        Log.d("HistoryPagingVM", "setPlaylistSelectionMode=$enabled")
        isPlaylistSelectionMode.value = enabled
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

    @Suppress("unused")
    fun setTypeFilter(filter: String) {
        if (typeFilter.value == filter) return
        typeFilter.value = filter
    }

    fun setStatusFilter(status: HistoryStatus) {
        if (statusFilter.value == status) return
        statusFilter.value = status
    }

    private fun historyListFlowFor(filters: HistoryFilters): Flow<PagingData<UiModel>> {
        viewModelScope.launch(Dispatchers.IO) {
            totalCount.value = repository.getFilteredCount(
                filters.query,
                filters.type,
                filters.author,
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

        val flow = if (filters.author.isNotBlank()) {
            combine(playlistsByAuthor, baseFlow) { playlists, pagingData ->
                var withHeaders = pagingData
                playlists.asReversed().forEach { pl ->
                    withHeaders = withHeaders.insertHeaderItem(item = UiModel.PlaylistInfoModel(pl))
                }
                withHeaders
            }
        } else {
            baseFlow
        }

        return flow
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
            searchFieldsFilter.value,
            statusFilter.value,
            authorFilter.value,
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
            filters.sortType,
            filters.sortOrder,
            filters.status,
            filters.website,
            filters.playlistId,
            filters.searchFields
        )
        cachedIdsKey = filters
        cachedIds = ids
        return ids
    }

    fun getAll(): List<HistoryItem> {
        return repository.getAll()
    }

    fun getByID(id: Long): HistoryItem {
        return resolveLocalTreePath(repository.getItem(id))
    }

    fun insert(item: HistoryItem) = viewModelScope.launch(Dispatchers.IO) {
        cachedIdsKey = null
        cachedIds = null
        repository.insert(item)
    }

    fun delete(item: HistoryItem, deleteFile: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        cachedIdsKey = null
        cachedIds = null
        repository.delete(item, deleteFile)
    }

    fun deleteAllWithIDs(ids: List<Long>, deleteFile: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        cachedIdsKey = null
        cachedIds = null
        repository.deleteAllWithIDs(ids, deleteFile)
    }

    fun deleteAllWithIDsCheckFiles(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        cachedIdsKey = null
        cachedIds = null
        repository.deleteAllWithIDsCheckFiles(ids)
    }

    fun getDownloadPathsFromIDs(ids: List<Long>): List<List<String>> {
        return repository.getDownloadPathsFromIDs(ids)
    }

    fun deleteAll(deleteFile: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        cachedIdsKey = null
        cachedIds = null
        repository.deleteAll(deleteFile)
    }

    fun deleteDuplicates() = viewModelScope.launch(Dispatchers.IO) {
        cachedIdsKey = null
        cachedIds = null
        repository.deleteDuplicates()
    }

    fun update(item: HistoryItem) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("HistoryPagingVM", "update id=${item.id} author='${item.author}' title='${item.title}'")
        cachedIdsKey = null
        cachedIds = null
        repository.update(item)
    }

    fun clearDeleted() = viewModelScope.launch(Dispatchers.IO) {
        cachedIdsKey = null
        cachedIds = null
        repository.clearDeletedHistory()
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

