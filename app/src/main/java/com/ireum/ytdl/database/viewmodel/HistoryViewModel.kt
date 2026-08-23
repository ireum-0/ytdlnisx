package com.ireum.ytdl.database.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
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
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.DBManager.SORTING
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import com.ireum.ytdl.database.models.UiModel
import com.ireum.ytdl.database.models.YoutuberInfo
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleEngine
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.database.repository.HistoryRepository.HistorySortType
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.EffectiveHistorySort
import com.ireum.ytdl.util.HistorySortPolicy
import com.ireum.ytdl.util.MediaPublishedDate
import com.ireum.ytdl.util.MediaPublishedDateSource
import com.ireum.ytdl.util.MissingSourceDatePolicy
import com.ireum.ytdl.util.extractors.YoutubeApiUtil
import com.ireum.ytdl.util.storage.AndroidHistoryFileDeletionGateway
import com.ireum.ytdl.util.storage.HistoryDeletionRecord
import com.ireum.ytdl.util.storage.HistoryDeletionCandidateRecord
import com.ireum.ytdl.util.storage.HistoryDeletionPolicy
import com.ireum.ytdl.util.storage.HistoryDeletionSummary
import com.ireum.ytdl.util.storage.HistoryDeletionValidation
import com.ireum.ytdl.util.storage.HistoryFileDeletionEngine
import com.ireum.ytdl.util.storage.HistoryReferenceMutationCoordinator
import com.ireum.ytdl.util.storage.HistoryFileDeletionGateway
import com.ireum.ytdl.util.storage.referencesSameFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.preference.PreferenceManager
import java.util.Collections
import java.util.Locale
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val repository: HistoryRepository
    private val database: DBManager
    private val keywordAssignments: HistoryKeywordAssignmentRepository
    private val automaticKeywordRuleEngine: AutomaticKeywordRuleEngine
    val sortOrder = MutableStateFlow(SORTING.DESC)
    val sortType = MutableStateFlow(HistorySortType.DATE)
    val missingSourceDatePolicy = MutableStateFlow(
        runCatching {
            MissingSourceDatePolicy.valueOf(
                preferences.getString(
                    PREF_MISSING_SOURCE_DATE_POLICY,
                    MissingSourceDatePolicy.USE_DOWNLOAD_DATE.name
                ).orEmpty()
            )
        }.getOrDefault(MissingSourceDatePolicy.USE_DOWNLOAD_DATE)
    )
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
    val visibleChildYoutubersFilter = MutableStateFlow(setOf<String>())
    val visibleChildKeywordsFilter = MutableStateFlow(setOf<String>())
    val effectiveSort = combine(
        sortType,
        sortOrder,
        isRecentMode,
        isYoutuberSelectionMode,
        isKeywordSelectionMode,
    ) { selectedType, selectedOrder, isRecent, isYoutuber, isKeyword ->
        HistorySortPolicy.resolve(
            selectedType = selectedType,
            selectedOrder = selectedOrder,
            isRecentMode = isRecent,
            isYoutuberMode = isYoutuber,
            isKeywordMode = isKeyword,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = EffectiveHistorySort(
            type = HistorySortType.DATE,
            order = SORTING.DESC,
            dateDisplayMode = com.ireum.ytdl.util.HistoryDateDisplayMode.DOWNLOAD_DATE,
            controlsVisible = true,
        ),
    )
    private val refreshTrigger = MutableStateFlow(0L)
    private val typeFilter = MutableStateFlow(DEFAULT_TYPE_FILTER)
    val queryFilterFlow = queryFilter.asStateFlow()
    val titleQueryFilterFlow = titleQueryFilter.asStateFlow()
    val keywordQueryFilterFlow = keywordQueryFilter.asStateFlow()
    val creatorQueryFilterFlow = creatorQueryFilter.asStateFlow()
    val typeFilterFlow = typeFilter.asStateFlow()
    private var cachedIdsKey: HistoryScope? = null
    private var cachedIds: List<Long>? = null
    private val pendingYoutuberMeta = Collections.synchronizedSet(mutableSetOf<String>())
    private val youtuberMetaQueue = Collections.synchronizedSet(mutableSetOf<String>())
    private var youtuberMetaJob: Job? = null
    private var lastQuotaExceededAt = 0L
    private val pendingThumbBackfill = Collections.synchronizedSet(mutableSetOf<Long>())
    private var thumbBackfillJob: Job? = null
    private var totalCountJob: Job? = null
    private var lastCountFilters: HistoryFilters? = null
    private var lastCountValue: Int? = null

    enum class HistoryStatus {
        UNSET, DELETED, NOT_DELETED, MISSING_THUMBNAIL, CUSTOM_THUMBNAIL, HARDSUB_DONE, HARDSUB_SCAN_TARGET, ALL
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
        var playlistId: Long = -1L,
        var isYoutuberMode: Boolean = false,
        var youtuberGroupId: Long = -1L,
        var hiddenYoutubers: Set<String> = emptySet(),
        var showHiddenOnly: Boolean = false,
        var missingSourceDatePolicy: MissingSourceDatePolicy = MissingSourceDatePolicy.USE_DOWNLOAD_DATE
    )

    data class MetadataUpdateResult(
        val item: HistoryItem,
        val protectedAutomaticKeywordCount: Int
    )
    private data class HistoryScope(
        val filters: HistoryFilters,
        val excludedChildKeywords: Set<String>,
        val youtuberGroupMembers: List<com.ireum.ytdl.database.models.YoutuberGroupMember> = emptyList(),
        val youtuberGroupRelations: List<com.ireum.ytdl.database.models.YoutuberGroupRelation> = emptyList()
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
        val playlistId: Long,
        val missingSourceDatePolicy: MissingSourceDatePolicy
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
        val visibleChildYoutubers: Set<String>,
        val visibleChildKeywords: Set<String>,
        val refreshToken: Long
    )

    init {
        val db = DBManager.getInstance(application)
        database = db
        keywordAssignments = HistoryKeywordAssignmentRepository(db)
        automaticKeywordRuleEngine = AutomaticKeywordRuleEngine(db)
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
                    info.copy(thumbnail = iconUrl).also {
                        it.fallbackThumbnail = info.fallbackThumbnail
                    }
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
            combine(
                combine(effectiveSort, missingSourceDatePolicy) { effective, missingPolicy ->
                    Triple(effective.order, effective.type, missingPolicy)
                },
                combine(authorFilter, statusFilter, websiteFilter) { author, status, website ->
                    Triple(author, status, website)
                }
            ) { sorting, filters ->
                Sextuple(
                    sorting.first,
                    sorting.second,
                    sorting.third,
                    filters.first,
                    filters.second,
                    filters.third
                )
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
        ) { sorting: Sextuple<SORTING, HistorySortType, MissingSourceDatePolicy, String, HistoryStatus, String>, pair: Pair<Octuple<String, String, Long, Set<HistoryRepository.SearchField>, String, String, String, String>, Boolean> ->
            val oct = pair.first
            HistoryFilters(
                type = oct.second,
                sortType = sorting.second,
                sortOrder = sorting.first,
                query = oct.first,
                titleQuery = oct.sixth,
                keywordQuery = oct.seventh,
                creatorQuery = oct.eighth,
                includeChildCategoryVideos = pair.second,
                searchFields = oct.fourth,
                status = sorting.fifth,
                author = sorting.fourth,
                keyword = oct.fifth,
                website = sorting.sixth,
                playlistId = oct.third,
                missingSourceDatePolicy = sorting.third
            )
        }.distinctUntilChanged()

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
                combine(visibleChildYoutuberGroupsFilter, visibleChildYoutubersFilter, visibleChildKeywordsFilter) { visibleYoutuberGroups, visibleYoutubers, visibleKeywords ->
                    Triple(visibleYoutuberGroups, visibleYoutubers, visibleKeywords)
                }
            ) { hidden, visible ->
                Septuple(hidden.first, hidden.second, hidden.third, hidden.fourth, visible.first, visible.second, visible.third)
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
                visibleChildYoutubers = extra.sixth,
                visibleChildKeywords = extra.seventh,
                refreshToken = refreshToken
            )
        }
            .distinctUntilChanged()
            // Many UI actions update multiple filters back-to-back; coalesce them into one recompute.
            .debounce(120)

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
            val visibleChildYoutubers = mode.visibleChildYoutubers
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
                            filters.searchFields,
                            filters.missingSourceDatePolicy
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
                            HistorySortType.DATE,
                            HistorySortType.MEDIA_PUBLISHED_DATE -> {
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
                        val groupedKeywords = members.asSequence().map { it.keyword }.toSet()
                        val memberKeywordsByGroup = members.groupBy { it.groupId }.mapValues { entry ->
                            entry.value.asSequence().map { it.keyword }.toSet()
                        }
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
                            HistorySortType.DATE,
                            HistorySortType.MEDIA_PUBLISHED_DATE -> {
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
                            val memberKeywords = memberKeywordsByGroup[group.id].orEmpty()
                            val memberInfos = memberKeywords.asSequence()
                                .mapNotNull { keywordByName[it] }
                                .toList()
                            val totalVideos = memberInfos.sumOf { it.videoCount }
                            val thumbnailInfo = memberInfos.firstOrNull { !it.thumbnail.isNullOrBlank() }
                            com.ireum.ytdl.database.models.KeywordGroupInfo(
                                id = group.id,
                                name = group.name,
                                memberCount = memberKeywords.size,
                                videoCount = totalVideos,
                                thumbnail = thumbnailInfo?.thumbnail,
                                fallbackThumbnail = thumbnailInfo?.fallbackThumbnail
                            )
                        }.filter { it.memberCount > 0 && it.videoCount > 0 }.let { infos ->
                            when (filters.sortType) {
                                HistorySortType.DURATION -> infos.sortedBy { it.videoCount }
                                HistorySortType.TITLE -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.AUTHOR -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.DATE,
                                HistorySortType.MEDIA_PUBLISHED_DATE -> infos.sortedBy { it.name.lowercase() }
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
                            filters.searchFields,
                            filters.missingSourceDatePolicy
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
                    if (filters.includeChildCategoryVideos) {
                        combine(youtuberGroupMembers, youtuberGroupRelations) { members, relations ->
                            val scope = historyScopeFor(
                                filters = filters.copy(
                                    isYoutuberMode = isSelectionMode,
                                    youtuberGroupId = youtuberGroup,
                                    hiddenYoutubers = hiddenYoutubers,
                                    showHiddenOnly = showHiddenOnly
                                ),
                                excludedChildKeywords = excludedChildKeywords,
                                youtuberGroupMembersSnapshot = members,
                                youtuberGroupRelationsSnapshot = relations
                            )
                            val ids = withContext(Dispatchers.IO) {
                                getSelectableHistoryIdsSnapshot(scope)
                            }
                            if (ids.isEmpty()) {
                                return@combine PagingData.from(emptyList<UiModel>())
                            }

                            val items = withContext(Dispatchers.IO) {
                                repository.getItemsFromIDs(ids)
                            }
                            val itemsById = items.associateBy { it.id }
                            val ordered = ids.mapNotNull { id ->
                                val item = itemsById[id] ?: return@mapNotNull null
                                UiModel.HistoryItemModel(sanitizeStoredDownloadPaths(item)) as UiModel
                            }
                            PagingData.from(ordered)
                        }
                    } else {
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
                        val membersByGroup = members.groupBy { it.groupId }.mapValues { entry ->
                            entry.value.asSequence().map { it.author }.toSet()
                        }
                        val descendantCache = HashMap<Long, Set<Long>>()
                        val metaMap = metas.associateBy { it.author }
                        val enriched = youtubers.map { info ->
                            val iconUrl = metaMap[info.author]?.iconUrl.orEmpty()
                            if (iconUrl.isNotBlank()) {
                                info.copy(thumbnail = iconUrl).also {
                                    it.fallbackThumbnail = info.fallbackThumbnail
                                }
                            } else {
                                info
                            }
                        }
                        fun descendantGroups(startGroupId: Long): Set<Long> {
                            descendantCache[startGroupId]?.let { cached -> return cached }
                            val visited = linkedSetOf<Long>()
                            val stack = ArrayDeque<Long>()
                            stack.add(startGroupId)
                            while (stack.isNotEmpty()) {
                                val id = stack.removeFirst()
                                if (!visited.add(id)) continue
                                childrenByParent[id].orEmpty().forEach { stack.addLast(it) }
                            }
                            return visited.also { descendantCache[startGroupId] = it }
                        }
                        val childGroupIds = childrenByParent[youtuberGroup].orEmpty()
                            .filter { isGroupVisible(it) }
                            .toSet()
                        val directMemberSet = membersByGroup[youtuberGroup].orEmpty()
                        val filtered = enriched.filter { directMemberSet.contains(it.author) && isYoutuberVisible(it.author) }
                        val sorted = when (filters.sortType) {
                            HistorySortType.DATE,
                            HistorySortType.MEDIA_PUBLISHED_DATE -> {
                                if (filters.sortOrder == SORTING.DESC) filtered.sortedBy { it.lastTime } else filtered.sortedBy { it.firstTime }
                            }
                            HistorySortType.TITLE -> filtered.sortedBy { it.author.lowercase() }
                            HistorySortType.DURATION -> filtered.sortedBy { it.videoCount }
                            HistorySortType.AUTHOR -> filtered.sortedBy { it.author.lowercase() }
                        }.run { if (filters.sortOrder == SORTING.DESC) this.asReversed() else this }
                        val groupInfos = groups
                            .filter { childGroupIds.contains(it.id) }
                            .map { group ->
                                val memberAuthors = descendantGroups(group.id)
                                    .asSequence()
                                    .flatMap { gId -> membersByGroup[gId].orEmpty().asSequence() }
                                    .filter { isYoutuberVisible(it) }
                                    .toSet()
                                val memberInfos = enriched.filter { memberAuthors.contains(it.author) }
                                val thumbnailInfo = memberInfos.firstOrNull { !it.thumbnail.isNullOrBlank() }
                                com.ireum.ytdl.database.models.YoutuberGroupInfo(
                                    id = group.id,
                                    name = group.name,
                                    memberCount = memberAuthors.size,
                                    videoCount = memberInfos.sumOf { it.videoCount },
                                    thumbnail = thumbnailInfo?.thumbnail,
                                    fallbackThumbnail = thumbnailInfo?.fallbackThumbnail
                                )
                            }
                            .filter { showHiddenOnly || (it.memberCount > 0 && it.videoCount > 0) }
                            .sortedBy { it.name.lowercase() }
                        val list = ArrayList<UiModel>()
                        list.addAll(groupInfos.map { UiModel.YoutuberGroupModel(it) })
                        list.addAll(sorted.map { UiModel.YoutuberInfoModel(it) })
                        PagingData.from(list)
                    }
                    }
                } else {
                    combine(filteredYoutubersFlow, youtuberGroups, youtuberGroupMembers, youtuberMetaFlow, youtuberGroupRelations) { youtubers, groups, members, metas, relations ->
                        val metaMap = metas.associateBy { it.author }
                        val membersByGroup = members.groupBy { it.groupId }.mapValues { entry ->
                            entry.value.asSequence().map { it.author }.toSet()
                        }
                        val enriched = youtubers.map { info ->
                            val iconUrl = metaMap[info.author]?.iconUrl.orEmpty()
                            if (iconUrl.isNotBlank()) {
                                info.copy(thumbnail = iconUrl).also {
                                    it.fallbackThumbnail = info.fallbackThumbnail
                                }
                            } else {
                                info
                            }
                        }
                        val groupedAuthors = members.map { it.author }.toSet()
                        val ungrouped = enriched.filter {
                            (!groupedAuthors.contains(it.author) || visibleChildYoutubers.contains(it.author)) &&
                                isYoutuberVisible(it.author)
                        }
                        val sortedUngrouped = when (filters.sortType) {
                            HistorySortType.DATE,
                            HistorySortType.MEDIA_PUBLISHED_DATE -> {
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
                        val descendantCache = HashMap<Long, Set<Long>>()
                        fun descendantGroups(startGroupId: Long): Set<Long> {
                            descendantCache[startGroupId]?.let { cached -> return cached }
                            val visited = linkedSetOf<Long>()
                            val stack = ArrayDeque<Long>()
                            stack.add(startGroupId)
                            while (stack.isNotEmpty()) {
                                val id = stack.removeFirst()
                                if (!visited.add(id)) continue
                                childrenByParent[id].orEmpty().forEach { stack.addLast(it) }
                            }
                            return visited.also { descendantCache[startGroupId] = it }
                        }

                        val rootGroups = groups.filter { !childGroupIds.contains(it.id) && isGroupVisible(it.id) }
                        val explicitlyVisibleChildGroups = groups.filter {
                            childGroupIds.contains(it.id) &&
                                visibleChildYoutuberGroups.contains(it.id) &&
                                isGroupVisible(it.id)
                        }
                        val visibleGroups = (rootGroups + explicitlyVisibleChildGroups).distinctBy { it.id }
                        val groupInfos = visibleGroups.map { group ->
                            val memberAuthors = descendantGroups(group.id)
                                .asSequence()
                                .flatMap { gId -> membersByGroup[gId].orEmpty().asSequence() }
                                .filter { isYoutuberVisible(it) }
                                .toSet()
                            val memberInfos = enriched.filter { memberAuthors.contains(it.author) }
                            val totalVideos = memberInfos.sumOf { it.videoCount }
                            val thumbnailInfo = memberInfos.firstOrNull { !it.thumbnail.isNullOrBlank() }
                            com.ireum.ytdl.database.models.YoutuberGroupInfo(
                                id = group.id,
                                name = group.name,
                                memberCount = memberAuthors.size,
                                videoCount = totalVideos,
                                thumbnail = thumbnailInfo?.thumbnail,
                                fallbackThumbnail = thumbnailInfo?.fallbackThumbnail
                            )
                        }.filter { showHiddenOnly || (it.memberCount > 0 && it.videoCount > 0) }.let { infos ->
                            val sortedGroups = when (filters.sortType) {
                                HistorySortType.DURATION -> infos.sortedBy { it.videoCount }
                                HistorySortType.TITLE -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.AUTHOR -> infos.sortedBy { it.name.lowercase() }
                                HistorySortType.DATE,
                                HistorySortType.MEDIA_PUBLISHED_DATE -> infos.sortedBy { it.name.lowercase() }
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
            .flowOn(Dispatchers.Default)
            .cachedIn(viewModelScope)

    }

    fun setSorting(sort: HistorySortType) {
        if (
            !HistorySortPolicy.isSelectable(
                type = sort,
                isRecentMode = isRecentMode.value,
                isYoutuberMode = isYoutuberSelectionMode.value,
                isKeywordMode = isKeywordSelectionMode.value,
            )
        ) return
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

    fun currentEffectiveSort(): EffectiveHistorySort {
        return HistorySortPolicy.resolve(
            selectedType = sortType.value,
            selectedOrder = sortOrder.value,
            isRecentMode = isRecentMode.value,
            isYoutuberMode = isYoutuberSelectionMode.value,
            isKeywordMode = isKeywordSelectionMode.value,
        )
    }

    fun setMissingSourceDatePolicy(policy: MissingSourceDatePolicy) {
        if (missingSourceDatePolicy.value == policy) return
        preferences.edit()
            .putString(PREF_MISSING_SOURCE_DATE_POLICY, policy.name)
            .apply()
        missingSourceDatePolicy.value = policy
        invalidateCachedIds(triggerRefresh = true)
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

    fun setVisibleChildYoutubersFilter(visible: Set<String>) {
        if (visibleChildYoutubersFilter.value == visible) return
        visibleChildYoutubersFilter.value = visible
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
        val normalized = normalizeTypeFilter(filter)
        if (typeFilter.value == normalized) return
        typeFilter.value = normalized
    }

    private fun normalizeTypeFilter(filter: String): String {
        val selected = filter.split(',')
            .map { it.trim() }
            .filter { it == DownloadType.audio.name || it == DownloadType.video.name }
            .distinct()
        return when {
            selected.isEmpty() -> DEFAULT_TYPE_FILTER
            selected.contains(DownloadType.audio.name) && selected.contains(DownloadType.video.name) -> DEFAULT_TYPE_FILTER
            else -> selected.first()
        }
    }

    fun setStatusFilter(status: HistoryStatus) {
        val metadataOnlyStatus = when (status) {
            HistoryStatus.DELETED,
            HistoryStatus.NOT_DELETED -> HistoryStatus.ALL
            else -> status
        }
        if (statusFilter.value == metadataOnlyStatus) return
        statusFilter.value = metadataOnlyStatus
    }

    private fun historyListFlowFor(filters: HistoryFilters, excludedChildKeywords: Set<String>): Flow<PagingData<UiModel>> {
        scheduleTotalCountUpdate(filters)

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
                    filters.searchFields,
                    filters.status,
                    filters.missingSourceDatePolicy
                )
            }
        )

        val baseFlow = pager.flow.map { pagingData: PagingData<HistoryItem> ->
            val filteredPagingData: PagingData<HistoryItem> = when (filters.status) {
                HistoryStatus.DELETED,
                HistoryStatus.NOT_DELETED -> pagingData
                HistoryStatus.MISSING_THUMBNAIL -> {
                    pagingData.filter { item: HistoryItem ->
                        withContext(Dispatchers.IO) {
                            val hasCustomThumb = item.customThumb.isNotBlank()
                            val hasThumb = item.thumb.isNotBlank()
                            !hasCustomThumb && !hasThumb
                        }
                    }
                }
                HistoryStatus.CUSTOM_THUMBNAIL -> {
                    pagingData.filter { item: HistoryItem ->
                        withContext(Dispatchers.IO) {
                            item.customThumb.isNotBlank()
                        }
                    }
                }
                HistoryStatus.HARDSUB_DONE -> {
                    pagingData
                }
                HistoryStatus.HARDSUB_SCAN_TARGET -> {
                    pagingData
                }
                else -> pagingData
            }

            filteredPagingData.map { historyItem: HistoryItem ->
                UiModel.HistoryItemModel(sanitizeStoredDownloadPaths(historyItem)) as UiModel
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
                    filters.searchFields,
                    filters.missingSourceDatePolicy
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
            var withHeaders = if (filters.keyword.isNotBlank()) {
                if (videoKeywords.isEmpty()) {
                    pagingData.filter { false }
                } else {
                    pagingData.filter { model ->
                        val item = (model as? UiModel.HistoryItemModel)?.historyItem ?: return@filter false
                        splitKeywords(item.keywords).any { videoKeywords.contains(it.lowercase(Locale.getDefault())) }
                    }
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

    private fun extractItemCreators(item: HistoryItem): Set<String> {
        val creators = linkedSetOf<String>()
        creators.addAll(splitCreators(item.author))
        creators.addAll(splitCreators(item.artist))
        return creators
    }

    private fun splitCreators(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
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
        val ids = getSelectableHistoryIdsSnapshot()
        val firstIndex = ids.indexOf(firstID)
        val secondIndex = ids.indexOf(secondID)
        return if (firstIndex > secondIndex) {
            ids.filterIndexed { index, _ -> index < firstIndex && index > secondIndex }
        } else {
            ids.filterIndexed { index, _ -> index > firstIndex && index < secondIndex }
        }
    }

    fun resolveSelectedHistoryIds(checkedIds: List<Long>, inverted: Boolean): List<Long> {
        val scopedIds = getSelectableHistoryIdsSnapshot()
        if (scopedIds.isEmpty()) return emptyList()
        if (inverted) {
            if (checkedIds.isEmpty()) return scopedIds
            val excluded = checkedIds.toHashSet()
            return scopedIds.filter { !excluded.contains(it) }
        }
        if (checkedIds.isEmpty()) return emptyList()
        val scoped = scopedIds.toHashSet()
        return checkedIds.filter { scoped.contains(it) }
    }

    fun getItemIDsNotPresentIn(not: List<Long>): List<Long> {
        return resolveSelectedHistoryIds(not, inverted = true)
    }

    private fun currentHistoryScope(): HistoryScope {
        val currentSort = currentEffectiveSort()
        return historyScopeFor(
            filters = HistoryFilters(
                typeFilter.value,
                currentSort.type,
                currentSort.order,
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
                playlistFilter.value,
                isYoutuberSelectionMode.value,
                youtuberGroupFilter.value,
                hiddenYoutubersFilter.value,
                showHiddenOnlyFilter.value,
                missingSourceDatePolicy.value
            ),
            excludedChildKeywords = excludedChildKeywordsFilter.value
        )
    }

    private fun historyScopeFor(
        filters: HistoryFilters,
        excludedChildKeywords: Set<String>,
        youtuberGroupMembersSnapshot: List<com.ireum.ytdl.database.models.YoutuberGroupMember>? = null,
        youtuberGroupRelationsSnapshot: List<com.ireum.ytdl.database.models.YoutuberGroupRelation>? = null
    ): HistoryScope {
        val needsYoutuberGroupScope =
            filters.isYoutuberMode && filters.youtuberGroupId >= 0L && filters.includeChildCategoryVideos
        val groupDao = if (needsYoutuberGroupScope) {
            DBManager.getInstance(getApplication()).youtuberGroupDao
        } else {
            null
        }
        return HistoryScope(
            filters = filters,
            excludedChildKeywords = excludedChildKeywords,
            youtuberGroupMembers = if (needsYoutuberGroupScope) {
                youtuberGroupMembersSnapshot ?: groupDao!!.getAllMembers()
            } else {
                emptyList()
            },
            youtuberGroupRelations = if (needsYoutuberGroupScope) {
                youtuberGroupRelationsSnapshot ?: groupDao!!.getAllRelations()
            } else {
                emptyList()
            }
        )
    }

    private fun getSelectableHistoryIdsSnapshot(scope: HistoryScope = currentHistoryScope()): List<Long> {
        // Keep history bulk actions tied to the same effective scope as the visible list.
        // Select-all and inverted selection store only exceptions in the adapter, so every
        // action must resolve IDs through this method instead of querying the raw history list.
        val filters = scope.filters
        val cached = cachedIds
        if (cached != null && cachedIdsKey == scope) {
            return cached
        }
        val ids = repository.getFilteredIDs(
            filters.query,
            filters.type,
            if (filters.isYoutuberMode && filters.youtuberGroupId >= 0L && filters.includeChildCategoryVideos) "" else filters.author,
            if (filters.includeChildCategoryVideos && filters.keyword.isNotBlank()) "" else filters.keyword,
            filters.titleQuery,
            filters.keywordQuery,
            filters.creatorQuery,
            filters.sortType,
            filters.sortOrder,
            filters.status,
            filters.website,
            filters.playlistId,
            filters.searchFields,
            filters.missingSourceDatePolicy
        )
        val statusFilteredIds = applyStatusFilterToIds(ids, filters.status)
        val childFilteredIds = applyChildKeywordFilterToIds(
            ids = statusFilteredIds,
            filters = filters,
            excludedChildKeywords = scope.excludedChildKeywords
        )
        val finalIds = applyCurrentYoutuberGroupFilterToIds(childFilteredIds, scope)
        cachedIdsKey = scope
        cachedIds = finalIds
        return finalIds
    }

    private fun applyStatusFilterToIds(ids: List<Long>, status: HistoryStatus): List<Long> {
        if (ids.isEmpty()) return ids
        if (status == HistoryStatus.ALL || status == HistoryStatus.UNSET) return ids
        val itemsById = repository.getItemsFromIDs(ids).associateBy { it.id }
        return ids.filter { id ->
            val item = itemsById[id] ?: return@filter false
            passesStatusFilter(item, status)
        }
    }

    private fun passesStatusFilter(item: HistoryItem, status: HistoryStatus): Boolean {
        return when (status) {
            HistoryStatus.DELETED,
            HistoryStatus.NOT_DELETED -> true
            HistoryStatus.MISSING_THUMBNAIL -> {
                val hasCustomThumb = item.customThumb.isNotBlank()
                val hasThumb = item.thumb.isNotBlank()
                !hasCustomThumb && !hasThumb
            }
            HistoryStatus.CUSTOM_THUMBNAIL -> item.customThumb.isNotBlank()
            HistoryStatus.HARDSUB_DONE -> item.hardSubDone
            HistoryStatus.HARDSUB_SCAN_TARGET ->
                item.type == com.ireum.ytdl.database.enums.DownloadType.video &&
                    !item.hardSubScanRemoved &&
                    !item.hardSubDone
            else -> true
        }
    }

    private fun applyChildKeywordFilterToIds(
        ids: List<Long>,
        filters: HistoryFilters,
        excludedChildKeywords: Set<String>
    ): List<Long> {
        if (ids.isEmpty()) return ids
        if (filters.author.isBlank() && filters.keyword.isBlank()) return ids

        val allKeywords = repository.getKeywordsWithInfoForHistoryIds(ids)
        var includedLower: Set<String> = emptySet()
        val excludedLower: Set<String> = when {
            filters.keyword.isNotBlank() -> {
                val selectedKeyword = filters.keyword.trim()
                val selectedKeywordInfo = allKeywords.firstOrNull { it.keyword.equals(selectedKeyword, ignoreCase = true) }
                    ?: return emptyList()
                val byName = allKeywords.associateBy { it.keyword }
                val excluded = buildExcludedRecursiveForKeyword(
                    selectedKeywordInfo = selectedKeywordInfo,
                    byName = byName,
                    excludedChildKeywords = excludedChildKeywords,
                    includeChildCategoryVideos = filters.includeChildCategoryVideos
                ).map { it.lowercase(Locale.getDefault()) }.toSet()
                if (filters.includeChildCategoryVideos) {
                    val included = linkedSetOf(selectedKeywordInfo.keyword)
                    val stack = ArrayDeque<String>()
                    stack.addAll(selectedKeywordInfo.childKeywords)
                    while (stack.isNotEmpty()) {
                        val keyword = stack.removeFirst()
                        if (!included.add(keyword)) continue
                        byName[keyword]?.childKeywords.orEmpty().forEach { stack.addLast(it) }
                    }
                    includedLower = included.map { it.lowercase(Locale.getDefault()) }.toSet()
                }
                excluded
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

        if (includedLower.isEmpty() && excludedLower.isEmpty()) return ids
        val itemsById = repository.getItemsFromIDs(ids).associateBy { it.id }
        return ids.filter { id ->
            val item = itemsById[id] ?: return@filter false
            val itemKeywords = splitKeywords(item.keywords).map { it.lowercase(Locale.getDefault()) }
            (includedLower.isEmpty() || itemKeywords.any { includedLower.contains(it) }) &&
                itemKeywords.none { excludedLower.contains(it) }
        }
    }

    private fun applyCurrentYoutuberGroupFilterToIds(ids: List<Long>, scope: HistoryScope): List<Long> {
        val filters = scope.filters
        if (ids.isEmpty()) return ids
        if (!filters.isYoutuberMode || filters.youtuberGroupId < 0L || !filters.includeChildCategoryVideos) return ids

        val childrenByParent = scope.youtuberGroupRelations.groupBy { it.parentGroupId }.mapValues { entry ->
            entry.value.map { it.childGroupId }
        }

        fun descendantGroups(startGroupId: Long): Set<Long> {
            val visited = linkedSetOf<Long>()
            val stack = ArrayDeque<Long>()
            stack.add(startGroupId)
            while (stack.isNotEmpty()) {
                val groupId = stack.removeFirst()
                if (!visited.add(groupId)) continue
                childrenByParent[groupId].orEmpty().forEach { stack.addLast(it) }
            }
            return visited
        }

        fun isYoutuberVisible(author: String): Boolean {
            val hidden = filters.hiddenYoutubers.contains(author)
            return if (filters.showHiddenOnly) hidden else !hidden
        }

        val targetGroupIds = descendantGroups(filters.youtuberGroupId)
        val allowedAuthorsLower = scope.youtuberGroupMembers
            .asSequence()
            .filter { targetGroupIds.contains(it.groupId) }
            .map { it.author }
            .filter { isYoutuberVisible(it) }
            .map { normalizeCreator(it) }
            .toSet()
        if (allowedAuthorsLower.isEmpty()) return emptyList()

        val itemsById = repository.getItemsFromIDs(ids).associateBy { it.id }
        return ids.filter { id ->
            val item = itemsById[id] ?: return@filter false
            extractItemCreators(item).any { allowedAuthorsLower.contains(normalizeCreator(it)) }
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

    suspend fun getItemsMissingMediaPublishedAt(): List<HistoryItem> = withContext(Dispatchers.IO) {
        repository.getItemsMissingMediaPublishedAt()
    }

    suspend fun getVideoQualityScanCandidates(): List<HistoryItem> = withContext(Dispatchers.IO) {
        repository.getVideoQualityScanCandidates()
    }

    suspend fun updateMediaPublishedAtIfMissing(
        id: Long,
        normalizedUrl: String,
        mediaPublishedAt: Long
    ): Boolean {
        if (
            id <= 0L ||
            normalizedUrl.isBlank() ||
            !MediaPublishedDate.isPresent(mediaPublishedAt)
        ) return false
        return withContext(Dispatchers.IO) {
            repository.updateMediaPublishedAtIfMissing(
                id = id,
                normalizedUrl = normalizedUrl,
                mediaPublishedAt = mediaPublishedAt
            )
        }
    }

    fun invalidateAfterMediaPublishedDateBackfill() {
        invalidateCachedIds(triggerRefresh = true)
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
        val currentSort = currentEffectiveSort()
        val ids = withContext(Dispatchers.IO) {
            repository.getFilteredIDs(
                queryFilter.value,
                typeFilter.value,
                "",
                "",
                titleQueryFilter.value,
                keywordQueryFilter.value,
                creatorQueryFilter.value,
                currentSort.type,
                currentSort.order,
                statusFilter.value,
                websiteFilter.value,
                playlistFilter.value,
                searchFieldsFilter.value,
                missingSourceDatePolicy.value
            )
        }
        return withContext(Dispatchers.IO) {
            repository.getKeywordsWithInfoForHistoryIds(ids)
        }
    }

    fun getByID(id: Long): HistoryItem {
        return sanitizeStoredDownloadPaths(repository.getItem(id))
    }

    fun getOperationPaths(item: HistoryItem): List<String> {
        return sharePathsForOperation(item)
    }

    fun getPlaybackPaths(item: HistoryItem): List<String> {
        val sanitizedItem = sanitizeStoredDownloadPaths(item)
        val resolvedTreeUri = resolvedTreeUri(sanitizedItem)?.toString()
        return (sanitizedItem.downloadPath + listOfNotNull(resolvedTreeUri))
            .filter(String::isNotBlank)
            .distinct()
    }

    suspend fun getKeywordAssignmentSnapshot(historyItemId: Long) = withContext(Dispatchers.IO) {
        keywordAssignments.snapshotAssignments(historyItemId)
    }

    fun restoreHistory(
        item: HistoryItem,
        assignmentSnapshot: List<HistoryKeywordAssignment>
    ) = viewModelScope.launch(Dispatchers.IO) {
        keywordAssignments.restoreHistory(item, assignmentSnapshot)
        invalidateCachedIds(triggerRefresh = true)
    }

    suspend fun deleteHistoryItems(ids: List<Long>, deleteAssociatedFiles: Boolean): HistoryDeletionSummary {
        return withContext(Dispatchers.IO) {
            val uniqueIds = ids.distinct()
            if (!deleteAssociatedFiles) {
                val existingIds = repository.getExistingIds(uniqueIds)
                val deletionRecords = existingIds.map { id -> HistoryDeletionRecord(id, emptyList()) }
                repository.deleteRecords(existingIds)
                invalidateCachedIds(triggerRefresh = true)
                return@withContext HistoryDeletionPolicy.recordOnly(deletionRecords)
            }
            executePreparedHistoryFileDeletion(prepareHistoryFileDeletion(uniqueIds))
        }
    }

    suspend fun prepareHistoryFileDeletion(ids: List<Long>): HistoryDeletionValidation {
        return withContext(Dispatchers.IO) {
            val items = repository.getDeletionCandidateRecordsByIds(ids.distinct())
            prepareHistoryFileDeletion(items)
        }
    }

    suspend fun prepareHistoryFileDeletionTargets(
        recordId: Long,
        storedTargets: List<String>,
        recordStoredTargetSnapshot: List<String>
    ): HistoryDeletionValidation {
        return withContext(Dispatchers.IO) {
            val item = repository.getItem(recordId)
            val requestedTargets = storedTargets.toSet()
            val recordTargets = if (item.downloadPath == recordStoredTargetSnapshot) {
                item.downloadPath.filter(requestedTargets::contains)
            } else {
                emptyList()
            }
            val engine = historyFileDeletionEngine()
            engine.excludeTargetsReferencedBy(
                validation = engine.validate(
                    listOf(
                        HistoryDeletionRecord(
                            id = item.id,
                            storedTargets = recordTargets,
                            recordStoredTargetSnapshot = recordStoredTargetSnapshot
                        )
                    )
                ),
                retainedStoredTargets = retainedStoredTargets(setOf(item.id))
            )
        }
    }

    suspend fun prepareAllHistoryFileDeletion(): HistoryDeletionValidation {
        return withContext(Dispatchers.IO) {
            prepareHistoryFileDeletion(repository.getDeletionCandidateRecords())
        }
    }

    suspend fun getAllHistoryCount(): Int = withContext(Dispatchers.IO) {
        repository.getCount()
    }

    suspend fun executePreparedHistoryFileDeletion(
        validation: HistoryDeletionValidation
    ): HistoryDeletionSummary {
        return withContext(Dispatchers.IO) {
            HistoryReferenceMutationCoordinator.withLock {
                fun currentStoredTargets(): Map<Long, List<String>> {
                    return repository.getDeletionReferenceRecordsByIds(
                        validation.records.map(HistoryDeletionRecord::id)
                    ).associate { item -> item.id to item.downloadPath }
                }

                val selectedIds = validation.records.mapTo(hashSetOf(), HistoryDeletionRecord::id)
                val engine = historyFileDeletionEngine()
                val currentValidation = engine.excludeTargetsReferencedBy(
                    validation = validation.revalidateRecordSnapshots(currentStoredTargets()),
                    retainedStoredTargets = retainedStoredTargets(selectedIds)
                )
                val result = engine.execute(currentValidation)
                val unchangedAfterDeletion = currentValidation
                    .revalidateRecordSnapshots(currentStoredTargets())
                    .recordTargetKeys
                    .keys
                val removableRecordIds = result.removableRecordIds.intersect(unchangedAfterDeletion)
                repository.deleteRecordsWithinReferenceMutation(removableRecordIds.toList())
                invalidateCachedIds(triggerRefresh = true)
                result.copy(
                    recordsRemoved = removableRecordIds.size,
                    removableRecordIds = removableRecordIds
                )
            }
        }
    }

    private fun historyFileDeletionEngine(): HistoryFileDeletionEngine {
        return HistoryFileDeletionEngine(AndroidHistoryFileDeletionGateway(getApplication()))
    }

    private fun prepareHistoryFileDeletion(
        items: List<HistoryDeletionCandidateRecord>
    ): HistoryDeletionValidation {
        val selectedIds = items.mapTo(hashSetOf(), HistoryDeletionCandidateRecord::id)
        val engine = historyFileDeletionEngine()
        return engine.excludeTargetsReferencedBy(
            validation = engine.validate(deletionRecords(items)),
            retainedStoredTargets = retainedStoredTargets(selectedIds)
        )
    }

    private fun deletionRecords(
        items: List<HistoryDeletionCandidateRecord>
    ): List<HistoryDeletionRecord> {
        val gateway = AndroidHistoryFileDeletionGateway(getApplication())
        return items.map { item ->
            val trustedDocumentTargets = trustedResolvedTreeTargets(
                downloadPaths = item.downloadPath,
                localTreeUri = item.localTreeUri,
                localTreePath = item.localTreePath,
                gateway = gateway
            )
            HistoryDeletionRecord(
                id = item.id,
                storedTargets = (item.downloadPath + trustedDocumentTargets).distinct(),
                recordStoredTargetSnapshot = item.downloadPath,
                trustedDocumentTargets = trustedDocumentTargets.toSet()
            )
        }
    }

    private fun retainedStoredTargets(excludedRecordIds: Set<Long>): Sequence<String> {
        return repository.getDeletionReferenceRecords()
            .asSequence()
            .filterNot { item -> item.id in excludedRecordIds }
            .flatMap { item -> item.downloadPath.asSequence() }
    }

    private fun trustedResolvedTreeTargets(
        downloadPaths: List<String>,
        localTreeUri: String,
        localTreePath: String,
        gateway: HistoryFileDeletionGateway
    ): List<String> {
        val resolvedTreeTarget = if (localTreeUri.isNotBlank() && localTreePath.isNotBlank()) {
            FileUtil.resolveTreeDocumentUri(localTreeUri, localTreePath)?.toString()
        } else {
            null
        }
        return listOfNotNull(resolvedTreeTarget).filter { treeTarget ->
            downloadPaths.any { storedTarget ->
                gateway.referencesSameFile(treeTarget, storedTarget)
            }
        }
    }

    fun getDownloadPathsFromIDs(ids: List<Long>): List<List<String>> {
        return repository.getItemsFromIDs(ids)
            .map(::sharePathsForOperation)
    }

    suspend fun deleteAllHistory(deleteAssociatedFiles: Boolean): HistoryDeletionSummary {
        if (deleteAssociatedFiles) {
            return executePreparedHistoryFileDeletion(prepareAllHistoryFileDeletion())
        }
        return withContext(Dispatchers.IO) {
            val snapshotIds = repository.getAllIds()
            repository.deleteRecords(snapshotIds)
            invalidateCachedIds(triggerRefresh = true)
            HistoryDeletionSummary(
                recordsRequested = snapshotIds.size,
                recordsRemoved = snapshotIds.size,
                removableRecordIds = emptySet(),
                outcomes = emptyList()
            )
        }
    }

    fun deleteDuplicates() = viewModelScope.launch(Dispatchers.IO) {
        val duplicateGroups = repository.getDuplicateGroups()
        duplicateGroups.forEach { group ->
            val retained = group.first()
            group.drop(1).forEach { duplicate ->
                keywordAssignments.mergeHistoryAssignments(duplicate.id, retained.id)
            }
        }
        repository.deleteRecords(duplicateGroups.flatMap { it.drop(1) }.map { it.id })
        invalidateCachedIds(triggerRefresh = true)
    }

    fun update(item: HistoryItem) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("HistoryPagingVM", "update id=${item.id} author='${item.author}' title='${item.title}'")
        val currentItem = runCatching { database.historyDao.getItem(item.id) }
            .getOrDefault(item)
        repository.update(item)
        if (currentItem.keywords != item.keywords) {
            keywordAssignments.updateManualFromMaterializedEditor(
                item.id,
                AutomaticKeywordNormalizer.parseKeywords(item.keywords)
            )
        }
        automaticKeywordRuleEngine.reconcileHistoryUrlChange(
            item.id,
            currentItem.url,
            item.url
        )
        invalidateCachedIds(triggerRefresh = true)
    }

    suspend fun updateWithKeywordNotice(item: HistoryItem): MetadataUpdateResult = withContext(Dispatchers.IO) {
        val result = HistoryReferenceMutationCoordinator.withLock {
            database.withTransaction {
                val currentItem = database.historyDao.getItem(item.id)
                val updatedItem = currentItem.copy(
                    url = item.url,
                    title = item.title,
                    author = item.author,
                    artist = item.artist,
                    duration = item.duration,
                    durationSeconds = item.durationSeconds,
                    thumb = item.thumb,
                    website = item.website,
                    customThumb = item.customThumb,
                    mediaPublishedAt = item.mediaPublishedAt
                        .takeIf(MediaPublishedDate::isPresent)
                        ?: currentItem.mediaPublishedAt.takeIf {
                            MediaPublishedDateSource.matches(currentItem.url, item.url)
                        }
                        ?: MediaPublishedDate.MISSING
                )
                // The reference coordinator is held before Room.  Updating
                // the raw row here avoids the reverse Room -> coordinator
                // acquisition performed by the compatibility wrapper.
                database.historyDao.updateRaw(updatedItem)
                val protectedCount = if (currentItem.keywords != item.keywords) {
                    keywordAssignments.updateManualFromMaterializedEditor(
                        item.id,
                        AutomaticKeywordNormalizer.parseKeywords(item.keywords)
                    )
                } else {
                    0
                }
                automaticKeywordRuleEngine.reconcileHistoryUrlChange(
                    item.id,
                    currentItem.url,
                    item.url
                )
                MetadataUpdateResult(
                    item = database.historyDao.getItem(item.id),
                    protectedAutomaticKeywordCount = protectedCount
                )
            }
        }
        invalidateCachedIds(triggerRefresh = true)
        result
    }

    fun removeKeywordsFromAllHistory(targetKeywords: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        val normalized = targetKeywords.map(AutomaticKeywordNormalizer::normalizeKeyword)
            .filter(String::isNotBlank)
            .toSet()
        database.historyDao.getAllIds().forEach {
            keywordAssignments.removeEditableKeywords(it, normalized)
        }
        invalidateCachedIds(triggerRefresh = true)
    }

    fun setHardSubScanRemoved(ids: List<Long>, removed: Boolean = true) = viewModelScope.launch(Dispatchers.IO) {
        repository.updateHardSubScanRemoved(ids, removed)
        invalidateCachedIds(triggerRefresh = true)
    }

    suspend fun setHardSubDone(ids: List<Long>, done: Boolean = true): Int = withContext(Dispatchers.IO) {
        val updatedCount = repository.updateHardSubDone(ids, done)
        invalidateCachedIds(triggerRefresh = true)
        updatedCount
    }

    private fun invalidateCachedIds(triggerRefresh: Boolean = false) {
        cachedIdsKey = null
        cachedIds = null
        lastCountFilters = null
        lastCountValue = null
        totalCountJob?.cancel()
        totalCountJob = null
        if (triggerRefresh) {
            refreshTrigger.value = refreshTrigger.value + 1L
        }
    }

    private fun scheduleTotalCountUpdate(filters: HistoryFilters) {
        val cachedFilters = lastCountFilters
        val cachedValue = lastCountValue
        if (cachedFilters == filters && cachedValue != null) {
            totalCount.value = cachedValue
            return
        }
        totalCountJob?.cancel()
        totalCountJob = viewModelScope.launch(Dispatchers.IO) {
            // Coalesce rapid filter toggles and avoid contention with initial paging query.
            delay(90)
            val count = repository.getFilteredCount(
                filters.query,
                filters.type,
                filters.author,
                filters.keyword,
                filters.titleQuery,
                filters.keywordQuery,
                filters.creatorQuery,
                filters.website,
                filters.playlistId,
                filters.searchFields,
                filters.status
            )
            if (!isActive) return@launch
            lastCountFilters = filters
            lastCountValue = count
            totalCount.value = count
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

    fun backfillRemoteThumbnails(limit: Int = 200) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!isNetworkAvailable()) return@launch
            val candidates = repository.getItemsWithRemoteThumb(limit)
            if (candidates.isEmpty()) return@launch
            synchronized(pendingThumbBackfill) {
                candidates.forEach { pendingThumbBackfill.add(it.id) }
            }
            if (thumbBackfillJob?.isActive == true) return@launch
            thumbBackfillJob = launchThumbnailBackfillJob()
        }
    }

    private fun launchThumbnailBackfillJob() = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val thumbDir = File(app.filesDir, "thumb_cache")
        if (!thumbDir.exists()) thumbDir.mkdirs()
        while (isActive) {
            if (!isNetworkAvailable()) break
            val id = synchronized(pendingThumbBackfill) {
                pendingThumbBackfill.firstOrNull()?.also { pendingThumbBackfill.remove(it) }
            } ?: break
            val item = runCatching { repository.getItem(id) }.getOrNull() ?: continue
            if (item.customThumb.isNotBlank() || item.thumb.isBlank() || !item.thumb.startsWith("http")) continue
            val localPath = downloadThumbToLocalPath(item.id, item.thumb, thumbDir) ?: continue
            repository.update(item.copy(thumb = localPath))
            delay(150)
        }
    }

    private fun downloadThumbToLocalPath(itemId: Long, thumbUrl: String, thumbDir: File): String? {
        val connection = runCatching {
            (URL(thumbUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
        }.getOrNull() ?: return null

        return runCatching {
            connection.connect()
            if (connection.responseCode !in 200..299) return@runCatching null
            val ext = when (connection.contentType?.lowercase(Locale.US).orEmpty()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/avif" -> "avif"
                "image/jpeg", "image/jpg" -> "jpg"
                else -> {
                    val fromUrl = thumbUrl.substringAfterLast('.', "").substringBefore('?').lowercase(Locale.US)
                    if (fromUrl in setOf("jpg", "jpeg", "png", "webp", "avif")) fromUrl else "jpg"
                }
            }
            val outFile = File(thumbDir, "history_${itemId}_thumb.$ext")
            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        }.getOrNull().also {
            connection.disconnect()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val app = getApplication<Application>()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun mediaPaths(item: HistoryItem): List<String> {
        return item.downloadPath
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { path -> isNonMediaSidecarPath(path) }
            .distinct()
            .toList()
    }

    private fun sanitizeStoredDownloadPaths(item: HistoryItem): HistoryItem {
        val filteredPaths = mediaPaths(item)
        if (filteredPaths.isEmpty() || filteredPaths == item.downloadPath) return item
        val updated = item.copy(downloadPath = filteredPaths)
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDownloadPath(updated.id, updated.downloadPath)
        }
        return updated
    }

    private fun sharePathsForOperation(item: HistoryItem): List<String> {
        val sanitizedItem = sanitizeStoredDownloadPaths(item)
        val resolvedTreePath = resolvedTreeUri(sanitizedItem)?.toString()
            ?: return sanitizedItem.downloadPath
        val gateway = AndroidHistoryFileDeletionGateway(getApplication())
        return sanitizedItem.downloadPath.map { storedPath ->
            if (gateway.referencesSameFile(storedPath, resolvedTreePath)) resolvedTreePath else storedPath
        }.distinct()
    }

    private fun resolvedTreeUri(item: HistoryItem): Uri? {
        if (item.localTreeUri.isBlank() || item.localTreePath.isBlank()) return null
        return FileUtil.resolveTreeDocumentUri(item.localTreeUri, item.localTreePath)
    }

    private fun isNonMediaSidecarPath(path: String): Boolean {
        val cleanPath = path.substringBefore('?')
        val name = cleanPath.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in setOf("srt", "vtt", "ass", "lrc", "srv3", "json3", "ttml", "description", "txt")
    }

    companion object {
        val DEFAULT_TYPE_FILTER = "${DownloadType.audio.name},${DownloadType.video.name}"
        private const val PREF_MISSING_SOURCE_DATE_POLICY = "history_missing_source_date_policy"
    }

}



