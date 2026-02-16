package com.ireum.ytdl.ui.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Patterns
import android.util.TypedValue
import android.graphics.Typeface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.SeekBar
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.paging.PagingData
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.VideoPlayerActivity
import com.ireum.ytdl.database.DBManager.SORTING
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.UiModel
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.database.viewmodel.HistoryViewModel
import com.ireum.ytdl.database.viewmodel.PlaylistViewModel
import com.ireum.ytdl.ui.adapter.HistoryPaginatedAdapter
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.Extensions.toStringDuration
import com.ireum.ytdl.util.Extensions.toDurationSeconds
import com.ireum.ytdl.util.Extensions.loadThumbnail
import com.ireum.ytdl.util.Extensions.enableFastScroll
import com.ireum.ytdl.util.NavbarUtil
import com.ireum.ytdl.util.UiUtil
import com.ireum.ytdl.util.extractors.YoutubeApiUtil
import com.ireum.ytdl.util.LocalAddCandidateDto
import com.ireum.ytdl.util.LocalAddMatchDto
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.lifecycle.asFlow
import androidx.work.workDataOf
import com.ireum.ytdl.util.LocalAddEntryDto
import com.ireum.ytdl.util.LocalAddStorage
import com.ireum.ytdl.work.LocalAddWorker
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.squareup.picasso.Picasso
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.coroutines.resume

class HistoryFragment : Fragment(), HistoryPaginatedAdapter.OnItemClickListener {
    private val playlistFilterUnassigned = -2L
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var playlistViewModel: PlaylistViewModel

    private lateinit var fragmentView: View
    private var mainActivity: MainActivity? = null
    private var fragmentContext: Context? = null
    private lateinit var layoutinflater: LayoutInflater
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var historyAdapter: HistoryPaginatedAdapter
    private lateinit var sortSheet: BottomSheetDialog
    private lateinit var noResults: RelativeLayout
    private lateinit var selectionChips: LinearLayout
    private lateinit var sharedPreferences: SharedPreferences
    private var authorList: List<String> = emptyList()
    private var keywordList: List<String> = emptyList()
    private var websiteList: List<String> = emptyList()
    private var playlistsCache: List<Playlist> = emptyList()
    private var youtuberGroupsCache: List<com.ireum.ytdl.database.models.YoutuberGroup> = emptyList()
    private var youtuberGroupRelationsCache: List<com.ireum.ytdl.database.models.YoutuberGroupRelation> = emptyList()
    private var playlistGroupsCache: List<com.ireum.ytdl.database.models.PlaylistGroup> = emptyList()
    private var keywordGroupsCache: List<com.ireum.ytdl.database.models.KeywordGroup> = emptyList()
    private var hiddenYoutubers: MutableSet<String> = linkedSetOf()
    private var hiddenYoutuberGroups: MutableSet<Long> = linkedSetOf()
    private var visibleChildYoutuberGroups: MutableSet<Long> = linkedSetOf()
    private var visibleChildYoutubers: MutableSet<String> = linkedSetOf()
    private var visibleChildKeywords: MutableSet<String> = linkedSetOf()
    private val prefHiddenYoutubersKey = "history_hidden_youtubers"
    private val prefHiddenYoutuberGroupsKey = "history_hidden_youtuber_groups"
    private val prefShowHiddenOnlyKey = "history_show_hidden_only"
    private val prefVisibleChildYoutuberGroupsKey = "history_visible_child_youtuber_groups"
    private val prefVisibleChildYoutubersKey = "history_visible_child_youtubers"
    private val prefVisibleChildKeywordsKey = "history_visible_child_keywords"
    private var totalCount = 0
    private var actionMode: ActionMode? = null
    private var youtuberActionMode: ActionMode? = null
    private var youtuberGroupActionMode: ActionMode? = null
    private var playlistActionMode: ActionMode? = null
    private var playlistGroupActionMode: ActionMode? = null
    private var keywordActionMode: ActionMode? = null
    private var keywordGroupActionMode: ActionMode? = null

    private lateinit var sortChip: Chip
    private lateinit var youtuberChip: Chip
    private lateinit var keywordChip: Chip
    private lateinit var playlistChip: Chip
    private lateinit var recentChip: Chip
    private lateinit var selectedYoutuberText: TextView
    private lateinit var selectedKeywordText: TextView
    private lateinit var selectedPlaylistText: TextView
    private var addLocalJob: Job? = null
    private var pendingThumbItem: HistoryItem? = null
    private var pendingThumbCallback: ((String) -> Unit)? = null
    private var pendingApplyReady: (() -> Unit)? = null
    private val localMatchCandidates: MutableList<LocalMatchCandidate> = mutableListOf()
    private var localMatchSelections: MutableList<LocalMatchSelection>? = null
    private var localMatchDeferred: CompletableDeferred<List<LocalMatchSelection>?>? = null
    private var localMatchDialog: androidx.appcompat.app.AlertDialog? = null
    private var localMatchAdapter: LocalMatchAdapter? = null
    private var localMatchSearchJob: Job? = null
    private var localMatchRefreshView: View? = null
    private var localMatchConfirmCallback: ((List<LocalMatchSelection>) -> Unit)? = null
    private val localMatchDeferredCandidates = mutableListOf<LocalMatchCandidate>()
    private var localMatchResultRepository: ResultRepository? = null
    private var localMatchDialogOpening = false
    private var localMatchRestartSearch: (() -> Unit)? = null
    private var localMatchAddFinished = false
    private var localMatchSkipUnset: ((List<LocalMatchSelection>) -> Unit)? = null
    private var pagingJob: Job? = null
    private var lastPagingData: PagingData<UiModel>? = null
    private var localAddSnackbar: Snackbar? = null
    private var localAddProgressJob: Job? = null
    private var localAddProgressTickerJob: Job? = null
    private var fastScrollEnabled = false
    private var lastScreenKey: ScreenKey? = null
    private var lastRecentMode: Boolean? = null
    private var pendingScrollToTop: Boolean = false
    private var forceTopOnNextPagesUpdate: Boolean = false
    private var firstYoutuberEntryPendingFix: Boolean = true
    private var firstRecentEntryPendingFix: Boolean = true
    private var lastYoutuberOriginGroupFilter: Long? = null
    private var lastKeywordOriginGroupFilter: Long? = null
    private var resetToAllOnResumeFromQueue: Boolean = false
    private var restoreScrollOnNextResume: Boolean = false
    private var isRestoringFromNavigationBack: Boolean = false
    private var suppressAutoScrollForNextScreenChange: Boolean = false
    private var pendingRestoreEntry: NavigationEntry? = null
    private val navigationBackStack = ArrayDeque<NavigationEntry>()
    private val savedScrollByState = mutableMapOf<NavigationState, ScrollSnapshot>()
    private var lastStableScrollSnapshot = ScrollSnapshot(0, 0)

    private data class ScreenKey(
        val sortType: HistoryRepository.HistorySortType,
        val sortOrder: DBManager.SORTING,
        val author: String,
        val website: String,
        val playlistId: Long,
        val status: HistoryViewModel.HistoryStatus,
        val isYoutuberMode: Boolean,
        val isPlaylistMode: Boolean,
        val isKeywordMode: Boolean,
        val isRecent: Boolean,
        val youtuberGroup: Long,
        val keywordGroup: Long,
        val playlistGroup: Long,
        val query: String,
        val titleQuery: String,
        val keywordQuery: String,
        val creatorQuery: String,
        val includeChildCategoryVideos: Boolean,
        val keyword: String,
        val searchFieldsKey: String,
        val type: String
    )

    private data class NavigationState(
        val sortType: HistoryRepository.HistorySortType,
        val sortOrder: DBManager.SORTING,
        val author: String,
        val website: String,
        val playlistId: Long,
        val status: HistoryViewModel.HistoryStatus,
        val isYoutuberMode: Boolean,
        val isPlaylistMode: Boolean,
        val isKeywordMode: Boolean,
        val isRecent: Boolean,
        val youtuberGroup: Long,
        val keywordGroup: Long,
        val playlistGroup: Long,
        val query: String,
        val titleQuery: String,
        val keywordQuery: String,
        val creatorQuery: String,
        val includeChildCategoryVideos: Boolean,
        val keyword: String,
        val searchFields: Set<HistoryRepository.SearchField>,
        val type: String
    )

    private data class ScrollSnapshot(
        val position: Int,
        val offset: Int
    )

    private data class NavigationEntry(
        val state: NavigationState,
        val scroll: ScrollSnapshot
    )

    private val addLocalVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != AppCompatActivity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(i).uri)
            }
        }
        if (uris.isEmpty()) return@registerForActivityResult
        val takeFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        uris.forEach { uri ->
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    takeFlags
                )
            }
        }
        if (addLocalJob?.isActive == true) {
            Toast.makeText(requireContext(), getString(R.string.local_video_already_adding), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        pendingApplyReady = {
            lifecycleScope.launch(Dispatchers.Main) {
                val pendingCount = localMatchCandidates.size + localMatchDeferredCandidates.size
                if (pendingCount == 0) {
                    Toast.makeText(requireContext(), getString(R.string.no_match_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (localMatchDialog == null) {
                    openLocalMatchDialog(localMatchResultRepository, awaitResult = false)
                } else {
                    localMatchAdapter?.notifyDataSetChanged()
                }
            }
        }
        showLocalAddSnackbar()
        addLocalJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val expandedUris = expandVideoUris(uris)
                val entries = expandedUris.map { LocalAddEntryDto(it.uri.toString(), it.treeUri?.toString()) }
                val sessionId = UUID.randomUUID().toString()
                LocalAddStorage.saveEntries(requireContext(), sessionId, entries)
                val request = OneTimeWorkRequestBuilder<LocalAddWorker>()
                    .setInputData(workDataOf(LocalAddWorker.KEY_SESSION_ID to sessionId))
                    .addTag(LocalAddWorker.TAG)
                    .build()
                WorkManager.getInstance(requireContext()).enqueue(request)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.local_video_adding), Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.local_video_cancelled), Toast.LENGTH_SHORT).show()
                }
            } finally {
                addLocalJob = null
                pendingApplyReady = null
            }
        }
    }

    private val pickCustomThumbLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val item = pendingThumbItem ?: return@registerForActivityResult
        val onComplete = pendingThumbCallback ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val savedPath = saveCustomThumbFromUri(item, uri)
            withContext(Dispatchers.Main) {
                if (savedPath.isNullOrBlank()) {
                    Toast.makeText(requireContext(), R.string.error_saving_thumbnail, Toast.LENGTH_SHORT).show()
                } else {
                    onComplete(savedPath)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        fragmentView = inflater.inflate(R.layout.fragment_history, container, false)
        mainActivity = activity as MainActivity?
        return fragmentView
    }

    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        fragmentContext = context
        layoutinflater = LayoutInflater.from(context)
        topAppBar = view.findViewById(R.id.history_toolbar)
        noResults = view.findViewById(R.id.no_results)
        selectionChips = view.findViewById(R.id.history_selection_chips)
        sortChip = view.findViewById(R.id.sortChip)
        youtuberChip = view.findViewById(R.id.youtuber_chip)
        keywordChip = view.findViewById(R.id.keyword_chip)
        playlistChip = view.findViewById(R.id.playlist_chip)
        recentChip = view.findViewById(R.id.recent_chip)
        selectedYoutuberText = view.findViewById(R.id.selected_youtuber_text)
        selectedYoutuberText.setOnLongClickListener {
            val selectedAuthor = historyViewModel.authorFilter.value
            if (selectedAuthor.isNotBlank()) {
                showYoutuberChildKeywordSelectionDialog(selectedAuthor)
                return@setOnLongClickListener true
            }
            val selectedGroupId = historyViewModel.youtuberGroupFilter.value
            if (selectedGroupId >= 0L) {
                showYoutuberChildGroupVisibilityDialog(selectedGroupId)
                return@setOnLongClickListener true
            }
            true
        }
        selectedKeywordText = view.findViewById(R.id.selected_keyword_text)
        selectedKeywordText.setOnLongClickListener {
            val selectedKeyword = historyViewModel.keywordFilter.value
            if (selectedKeyword.isBlank()) return@setOnLongClickListener false
            lifecycleScope.launch(Dispatchers.IO) {
                val keywordInfo = historyViewModel.getKeywordInfoByNameForCurrentFilters(selectedKeyword)
                withContext(Dispatchers.Main) {
                    if (keywordInfo == null) {
                        Toast.makeText(requireContext(), getString(R.string.no_results), Toast.LENGTH_SHORT).show()
                    } else {
                        showKeywordChildSelectionDialog(keywordInfo)
                    }
                }
            }
            true
        }
        selectedPlaylistText = view.findViewById(R.id.selected_playlist_text)

        val isInNavBar = NavbarUtil.getNavBarItems(requireActivity()).any { n -> n.itemId == R.id.historyFragment && n.isVisible }
        if (isInNavBar) {
            topAppBar.navigationIcon = null
        } else {
            mainActivity?.hideBottomNavigation()
        }
        topAppBar.setNavigationOnClickListener { mainActivity?.onBackPressedDispatcher?.onBackPressed() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (handleHistoryBack()) return@addCallback
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
        historyAdapter = HistoryPaginatedAdapter(this, requireActivity())
        recyclerView = view.findViewById(R.id.recyclerviewhistorys)

        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (preferences.getStringSet("swipe_gesture", requireContext().resources.getStringArray(R.array.swipe_gestures_values).toSet())!!.toList().contains("history")) {
            val itemTouchHelper = ItemTouchHelper(simpleCallback)
            itemTouchHelper.attachToRecyclerView(recyclerView)
        }

        recyclerView.layoutManager = GridLayoutManager(context, resources.getInteger(R.integer.grid_size))
        noResults.isVisible = false
        historyViewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        playlistViewModel = ViewModelProvider(this)[PlaylistViewModel::class.java]
        loadHiddenStateFromPrefs()
        historyViewModel.setHiddenYoutubersFilter(hiddenYoutubers)
        historyViewModel.setHiddenYoutuberGroupsFilter(hiddenYoutuberGroups)
        historyViewModel.setVisibleChildYoutuberGroupsFilter(visibleChildYoutuberGroups)
        historyViewModel.setVisibleChildYoutubersFilter(visibleChildYoutubers)
        historyViewModel.setVisibleChildKeywordsFilter(visibleChildKeywords)
        historyViewModel.setShowHiddenOnlyFilter(sharedPreferences.getBoolean(prefShowHiddenOnlyKey, false))
        playlistChip.visibility = View.GONE
        selectedPlaylistText.visibility = View.GONE
        historyViewModel.setPlaylistFilter(-1L)
        historyViewModel.setPlaylistGroupFilter(-1L)
        historyViewModel.setPlaylistSelectionMode(false)
        historyAdapter.stateRestorationPolicy =
            androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT
        recyclerView.adapter = historyAdapter
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                captureCurrentVisibleScrollSnapshot()?.let { snapshot ->
                    lastStableScrollSnapshot = snapshot
                }
            }
        })
        historyAdapter.addLoadStateListener { loadStates ->
            if (loadStates.refresh is androidx.paging.LoadState.NotLoading && historyAdapter.itemCount > 0) {
                if (tryApplyPendingRestore()) {
                    return@addLoadStateListener
                }
                if (pendingScrollToTop && !isRestoringFromNavigationBack && pendingRestoreEntry == null) {
                    recyclerView.post {
                        requestScrollToTop()
                        recyclerView.post { forceScrollToTop() }
                    }
                    pendingScrollToTop = false
                }
            }
        }
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        historyAdapter.addOnPagesUpdatedListener {
            if (forceTopOnNextPagesUpdate) {
                forceTopOnNextPagesUpdate = false
                recyclerView.post {
                    requestScrollToTop()
                    recyclerView.post { forceScrollToTop() }
                }
            }
            if (fastScrollEnabled) return@addOnPagesUpdatedListener
            recyclerView.post {
                if (fastScrollEnabled) return@post
                if (recyclerView.canScrollVertically(1) || recyclerView.canScrollVertically(-1)) {
                    recyclerView.enableFastScroll(paddingEndDp = 8)
                    fastScrollEnabled = true
                }
            }
        }

        pagingJob?.cancel()
        pagingJob = viewLifecycleOwner.lifecycleScope.launch {
            historyViewModel.paginatedItems.collectLatest { data ->
                val screenKey = ScreenKey(
                    sortType = historyViewModel.sortType.value,
                    sortOrder = historyViewModel.sortOrder.value,
                    author = historyViewModel.authorFilter.value,
                    website = historyViewModel.websiteFilter.value,
                    playlistId = historyViewModel.playlistFilter.value,
                    status = historyViewModel.statusFilter.value,
                    isYoutuberMode = historyViewModel.isYoutuberSelectionMode.value,
                    isPlaylistMode = historyViewModel.isPlaylistSelectionMode.value,
                    isKeywordMode = historyViewModel.isKeywordSelectionMode.value,
                    isRecent = historyViewModel.isRecentMode.value,
                    youtuberGroup = historyViewModel.youtuberGroupFilter.value,
                    keywordGroup = historyViewModel.keywordGroupFilter.value,
                    playlistGroup = historyViewModel.playlistGroupFilter.value,
                    query = historyViewModel.queryFilterFlow.value,
                    titleQuery = historyViewModel.titleQueryFilterFlow.value,
                    keywordQuery = historyViewModel.keywordQueryFilterFlow.value,
                    creatorQuery = historyViewModel.creatorQueryFilterFlow.value,
                    includeChildCategoryVideos = historyViewModel.includeChildCategoryVideosFilter.value,
                    keyword = historyViewModel.keywordFilter.value,
                    searchFieldsKey = historyViewModel.searchFieldsFilter.value
                        .map { it.name }
                        .sorted()
                        .joinToString(","),
                    type = historyViewModel.typeFilterFlow.value
                )
                val screenChanged = lastScreenKey != null && lastScreenKey != screenKey
                pendingScrollToTop = screenChanged &&
                    !isRestoringFromNavigationBack &&
                    !suppressAutoScrollForNextScreenChange
                lastScreenKey = screenKey
                lastPagingData = data
                historyAdapter.submitData(viewLifecycleOwner.lifecycle, data)
                if (suppressAutoScrollForNextScreenChange) {
                    suppressAutoScrollForNextScreenChange = false
                }
                if (pendingRestoreEntry != null) {
                    schedulePendingRestoreRetry()
                }
                if (!isRestoringFromNavigationBack && pendingScrollToTop) {
                    recyclerView.post { requestScrollToTop() }
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.authors.collectLatest {
                authorList = it
                if (it.isEmpty()) {
                    noResults.isVisible = true
                    selectionChips.isVisible = false
                    topAppBar.menu.children.firstOrNull { m -> m.itemId == R.id.filters }?.isVisible = false
                } else {
                    noResults.isVisible = false
                    selectionChips.isVisible = true
                    topAppBar.menu.children.firstOrNull { m -> m.itemId == R.id.filters }?.isVisible = true
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.keywords.collectLatest {
                keywordList = it
            }
        }

        lifecycleScope.launch {
            historyViewModel.websites.collectLatest { 
                websiteList = it
            }
        }

        lifecycleScope.launch {
            playlistViewModel.allPlaylists.collectLatest { playlists ->
                playlistsCache = playlists
                 updatePlaylistLabel(historyViewModel.playlistFilter.value)
            }
        }

        lifecycleScope.launch {
            historyViewModel.youtuberGroups.collectLatest { groups ->
                youtuberGroupsCache = groups
            }
        }

        lifecycleScope.launch {
            historyViewModel.youtuberGroupRelations.collectLatest { relations ->
                youtuberGroupRelationsCache = relations
            }
        }

        lifecycleScope.launch {
            historyViewModel.playlistGroups.collectLatest { groups ->
                playlistGroupsCache = groups
            }
        }

        lifecycleScope.launch {
            historyViewModel.keywordGroups.collectLatest { groups ->
                keywordGroupsCache = groups
            }
        }

        lifecycleScope.launch {
            historyViewModel.totalCount.collectLatest {
                totalCount = it
            }
        }

        lifecycleScope.launch {
            historyViewModel.statusFilter.collectLatest { status ->
                historyAdapter.setDisableGeneratedThumbnails(
                    status == HistoryViewModel.HistoryStatus.MISSING_THUMBNAIL
                )
            }
        }

        lifecycleScope.launch {
            historyViewModel.sortOrder.collectLatest {
                when (it) {
                    SORTING.ASC -> sortChip.chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_down)
                    SORTING.DESC -> sortChip.chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_up)
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.sortType.collectLatest {
                when (it) {
                    HistoryRepository.HistorySortType.AUTHOR -> sortChip.text = getString(R.string.author)
                    HistoryRepository.HistorySortType.DATE -> sortChip.text = getString(R.string.date_added)
                    HistoryRepository.HistorySortType.TITLE -> sortChip.text = getString(R.string.title)
                    HistoryRepository.HistorySortType.DURATION -> sortChip.text = getString(R.string.length)
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.authorFilter.collectLatest { filter ->
                if (filter.isEmpty()) {
                    selectedYoutuberText.visibility = View.GONE
                } else {
                    selectedYoutuberText.text = filter
                    selectedYoutuberText.visibility = View.VISIBLE
                }
                updateYoutuberChipCheckedState()
            }
        }

        lifecycleScope.launch {
            historyViewModel.youtuberGroupFilter.collectLatest { groupId ->
                if (groupId < 0L) {
                    if (historyViewModel.authorFilter.value.isEmpty()) {
                        selectedYoutuberText.visibility = View.GONE
                    }
                } else {
                    val groupName = youtuberGroupsCache.firstOrNull { it.id == groupId }?.name ?: groupId.toString()
                    selectedYoutuberText.text = getString(R.string.group_prefix, groupName)
                    selectedYoutuberText.visibility = View.VISIBLE
                }
                updateYoutuberChipCheckedState()
            }
        }

        lifecycleScope.launch {
            historyViewModel.playlistFilter.collectLatest { playlistId ->
                updatePlaylistLabel(playlistId)
                playlistChip.isChecked = playlistId != -1L || historyViewModel.playlistGroupFilter.value >= 0L
            }
        }

        lifecycleScope.launch {
            historyViewModel.keywordFilter.collectLatest { keyword ->
                if (keyword.isBlank()) {
                    if (historyViewModel.keywordGroupFilter.value < 0L) {
                        selectedKeywordText.visibility = View.GONE
                    }
                } else {
                    selectedKeywordText.text = keyword
                    selectedKeywordText.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.playlistGroupFilter.collectLatest { groupId ->
                if (groupId < 0L) {
                    if (historyViewModel.playlistFilter.value == -1L) {
                        selectedPlaylistText.visibility = View.GONE
                    }
                } else {
                    val groupName = playlistGroupsCache.firstOrNull { it.id == groupId }?.name ?: groupId.toString()
                    selectedPlaylistText.text = getString(R.string.group_prefix, groupName)
                    selectedPlaylistText.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.keywordGroupFilter.collectLatest { groupId ->
                if (groupId < 0L) {
                    if (historyViewModel.keywordFilter.value.isBlank()) {
                        selectedKeywordText.visibility = View.GONE
                    }
                } else {
                    val groupName = keywordGroupsCache.firstOrNull { it.id == groupId }?.name ?: groupId.toString()
                    selectedKeywordText.text = getString(R.string.group_prefix, groupName)
                    selectedKeywordText.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.isPlaylistSelectionMode.collectLatest { isSelectionMode ->
                playlistChip.isChecked = isSelectionMode ||
                    historyViewModel.playlistFilter.value != -1L ||
                    historyViewModel.playlistGroupFilter.value >= 0
                if (isSelectionMode && shouldAutoScrollToTop()) {
                    pendingScrollToTop = true
                    requestScrollToTop()
                }
                if (!isSelectionMode) {
                    historyAdapter.clearPlaylistSelection()
                    playlistActionMode?.finish()
                    historyAdapter.clearPlaylistGroupSelection()
                    playlistGroupActionMode?.finish()
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.isKeywordSelectionMode.collectLatest { isSelectionMode ->
                keywordChip.isChecked = isSelectionMode ||
                    historyViewModel.keywordFilter.value.isNotBlank() ||
                    historyViewModel.keywordGroupFilter.value >= 0
                if (isSelectionMode && shouldAutoScrollToTop()) {
                    pendingScrollToTop = true
                    requestScrollToTop()
                }
                if (!isSelectionMode) {
                    historyAdapter.clearKeywordSelection()
                    keywordActionMode?.finish()
                    historyAdapter.clearKeywordGroupSelection()
                    keywordGroupActionMode?.finish()
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.isYoutuberSelectionMode.collectLatest { isSelectionMode ->
                updateYoutuberChipCheckedState()
                if (isSelectionMode && shouldAutoScrollToTop()) {
                    forceTopOnNextPagesUpdate = true
                    pendingScrollToTop = true
                    requestScrollToTop()
                    if (firstYoutuberEntryPendingFix) {
                        firstYoutuberEntryPendingFix = false
                        recyclerView.postDelayed({
                            requestScrollToTop()
                            recyclerView.post { forceScrollToTop() }
                        }, 120L)
                    }
                }
                if (!isSelectionMode) {
                    historyAdapter.clearYoutuberSelection()
                    historyAdapter.clearYoutuberGroupSelection()
                    youtuberActionMode?.finish()
                    youtuberGroupActionMode?.finish()
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.isRecentMode.collectLatest { isRecentMode ->
                recentChip.isChecked = isRecentMode
                if (lastRecentMode != null && lastRecentMode != isRecentMode && shouldAutoScrollToTop()) {
                    pendingScrollToTop = true
                    requestScrollToTop()
                }
                if (lastRecentMode == true && !isRecentMode && shouldAutoScrollToTop()) {
                    pendingScrollToTop = true
                    requestScrollToTop()
                }
                if (isRecentMode && shouldAutoScrollToTop()) {
                    forceTopOnNextPagesUpdate = true
                    pendingScrollToTop = true
                    requestScrollToTop()
                    if (firstRecentEntryPendingFix) {
                        firstRecentEntryPendingFix = false
                        recyclerView.postDelayed({
                            requestScrollToTop()
                            recyclerView.post { forceScrollToTop() }
                        }, 120L)
                    }
                }
                lastRecentMode = isRecentMode
                if (isRecentMode) {
                    selectedPlaylistText.visibility = View.GONE
                    selectedKeywordText.visibility = View.GONE
                    fragmentView.findViewById<TextView>(R.id.selected_youtuber_text).visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                if (res.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val bundle = bundleOf(
                            Pair("duplicates", res)
                        )
                        findNavController().navigate(R.id.action_historyFragment_to_downloadsAlreadyExistDialog, bundle)
                    }
                    downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                }
            }
        }

        initMenu()
        initChips()
    }

    override fun onDestroyView() {
        localAddProgressJob?.cancel()
        localAddProgressJob = null
        localAddProgressTickerJob?.cancel()
        localAddProgressTickerJob = null
        localAddSnackbar?.dismiss()
        localAddSnackbar = null
        clearNavigationBackStack()
        pendingRestoreEntry = null
        isRestoringFromNavigationBack = false
        pagingJob?.cancel()
        pagingJob = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (resetToAllOnResumeFromQueue) {
            resetToAllOnResumeFromQueue = false
            resetToAllVideosState()
        }
        val snapshot = LocalAddStorage.getProgressSnapshot(requireContext())
        if (snapshot != null) {
            showLocalAddSnackbar()
        }
        maybeOpenPendingLocalAdd()
        if (restoreScrollOnNextResume) {
            restoreScrollOnNextResume = false
            val state = captureNavigationState()
            savedScrollByState[state]?.let { requestRestoreScroll(it) }
        }
    }

    private fun maybeOpenPendingLocalAdd() {
        if (localMatchDialog != null) return
        val sessionId = LocalAddStorage.consumeOpenSession(requireContext()) ?: return
        openPendingLocalAddSession(sessionId)
    }

    private fun openPendingLocalAddSession(sessionId: String) {
        val pending = LocalAddStorage.loadPending(requireContext(), sessionId)
        if (pending.isEmpty()) return
        val db = DBManager.getInstance(requireContext())
        val resultRepository = ResultRepository(db.resultDao, db.commandTemplateDao, requireContext())
        localMatchResultRepository = resultRepository
        showLocalAddSnackbar()
        val candidates = pending.map { dto ->
            val cleanTitle = normalizeLocalTitle(dto.title)
            LocalMatchCandidate(
                uri = Uri.parse(dto.uri),
                treeUri = dto.treeUri?.let { Uri.parse(it) },
                title = cleanTitle,
                ext = dto.ext,
                size = dto.size,
                durationSeconds = dto.durationSeconds,
                match = dto.match?.let { matchDto ->
                    val item = buildResultItem(matchDto)
                    YoutubeMatch(item, 1f, 0, exactTitleMatch = false)
                }
            )
        }
        localMatchCandidates.clear()
        localMatchCandidates.addAll(candidates)
        val selections = candidates.map { candidate ->
            val status = if (candidate.match == null) LocalMatchStatus.NONE else LocalMatchStatus.FOUND
            val choice = if (candidate.match == null) LocalMatchChoice.MANUAL else LocalMatchChoice.UNSET
            LocalMatchSelection(candidate, choice, status)
        }.toMutableList()
        localMatchSelections = selections
        localMatchAddFinished = true
        localMatchConfirmCallback = { decided ->
            processPendingSelections(decided, sessionId)
        }
        localMatchSkipUnset = localMatchSkipUnset@{ undecided ->
            if (undecided.isEmpty()) return@localMatchSkipUnset
            val remaining = pending.toMutableList()
            val undecidedSet = undecided.map { it.candidate.uri.toString() }.toSet()
            remaining.removeAll { undecidedSet.contains(it.uri) }
            LocalAddStorage.savePending(requireContext(), sessionId, remaining)
            if (remaining.isEmpty()) {
                LocalAddStorage.clearPending(requireContext(), sessionId)
            }
        }
        lifecycleScope.launch(Dispatchers.Main) {
            openLocalMatchDialog(resultRepository = resultRepository, awaitResult = false)
        }
    }

    private fun normalizeLocalTitle(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        val afterSlash = trimmed.substringAfterLast('/')
        val afterColon = afterSlash.substringAfterLast(':')
        return afterColon.ifBlank { afterSlash.ifBlank { trimmed } }
    }

    private fun showLocalAddSnackbar() {
        val existing = localAddSnackbar
        if (existing != null) {
            val snapshot = LocalAddStorage.getProgressSnapshot(requireContext())
            if (snapshot != null) {
                updateLocalAddSnackbarText(existing, snapshot.first, snapshot.second)
            } else {
                existing.setText(getString(R.string.local_video_adding))
            }
            existing.show()
            return
        }
        val snackbar = Snackbar.make(
            requireActivity().findViewById(android.R.id.content),
            getString(R.string.local_video_adding),
            Snackbar.LENGTH_INDEFINITE
        )
        val bottomNav = requireActivity().findViewById<BottomNavigationView?>(R.id.bottomNavigationView)
        if (bottomNav != null && bottomNav.isShown) {
            snackbar.anchorView = bottomNav
        }
        // Custom actions: Apply ready + Cancel
        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val actionLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val actionColor = MaterialColors.getColor(snackbar.view, com.google.android.material.R.attr.colorPrimary, Color.WHITE)
        val snackbarText = snackbarLayout.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        val textSizePx = snackbarText?.textSize
        val textPaddingTop = snackbarText?.paddingTop ?: dp(4)
        val textPaddingBottom = snackbarText?.paddingBottom ?: dp(4)
        val applyButton = TextView(requireContext()).apply {
            text = getString(R.string.apply_ready)
            setTextColor(actionColor)
            setTypeface(typeface, Typeface.BOLD)
            if (textSizePx != null) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            } else {
                textSize = 12f
            }
            setPadding(dp(8), textPaddingTop, dp(8), textPaddingBottom)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setOnClickListener {
                if (fragmentContext?.applicationContext == null) {
                    snackbar.dismiss()
                    return@setOnClickListener
                }
                if (pendingApplyReady != null) {
                    pendingApplyReady?.invoke()
                    return@setOnClickListener
                }
                if (!isAdded) {
                    snackbar.dismiss()
                    return@setOnClickListener
                }
                val sessionId = LocalAddStorage.consumeOpenSession(requireContext())
                if (!sessionId.isNullOrBlank()) {
                    openPendingLocalAddSession(sessionId)
                    return@setOnClickListener
                }
                if (localMatchCandidates.isNotEmpty() || localMatchDeferredCandidates.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        openLocalMatchDialog(localMatchResultRepository, awaitResult = false)
                    }
                    return@setOnClickListener
                }
                fragmentContext?.applicationContext?.let { ctx ->
                    Toast.makeText(
                        ctx,
                        getString(R.string.local_video_adding),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        val cancelButton = TextView(requireContext()).apply {
            text = getString(R.string.cancel)
            setTextColor(actionColor)
            setTypeface(typeface, Typeface.BOLD)
            if (textSizePx != null) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            } else {
                textSize = 12f
            }
            setPadding(dp(8), textPaddingTop, dp(8), textPaddingBottom)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setOnClickListener {
                val appContext = fragmentContext?.applicationContext
                if (appContext == null) {
                    snackbar.dismiss()
                    return@setOnClickListener
                }
                addLocalJob?.cancel()
                WorkManager.getInstance(appContext).cancelAllWorkByTag(LocalAddWorker.TAG)
                LocalAddStorage.clearProgressSnapshot(appContext)
                snackbar.dismiss()
                localMatchDialog?.dismiss()
                addLocalJob = null
                pendingApplyReady = null
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(6)
        }
        actionLayout.addView(applyButton)
        actionLayout.addView(cancelButton, params)
        val contentLayout = snackbarText?.parent as? LinearLayout
        if (contentLayout != null) {
            val textParams = snackbarText.layoutParams as? LinearLayout.LayoutParams
            if (textParams != null) {
                textParams.width = 0
                textParams.weight = 1f
                snackbarText.layoutParams = textParams
            }
            val actionParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            contentLayout.gravity = android.view.Gravity.CENTER_VERTICAL
            contentLayout.addView(actionLayout, actionParams)
        } else {
            val actionParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            }
            snackbarLayout.addView(actionLayout, actionParams)
        }
        localAddSnackbar = snackbar
        snackbar.show()

        if (localAddProgressJob == null) {
            localAddProgressJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    WorkManager.getInstance(requireContext())
                        .getWorkInfosByTagLiveData(LocalAddWorker.TAG)
                        .asFlow()
                        .collectLatest { infos ->
                            val active = infos.firstOrNull {
                                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                            }
                            val current = localAddSnackbar ?: return@collectLatest
                            val total = active?.progress?.getInt(LocalAddWorker.KEY_TOTAL, 0) ?: 0
                            val done = active?.progress?.getInt(LocalAddWorker.KEY_DONE, 0) ?: 0
                            if (total > 0) {
                                updateLocalAddSnackbarText(current, done, total)
                            } else {
                                val snapshot = LocalAddStorage.getProgressSnapshot(requireContext())
                                if (snapshot != null) {
                                    updateLocalAddSnackbarText(current, snapshot.first, snapshot.second)
                                } else {
                                    current.setText(getString(R.string.local_video_adding))
                                }
                            }
                        }
                }
            }
        }
        if (localAddProgressTickerJob == null) {
            localAddProgressTickerJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        val snapshot = LocalAddStorage.getProgressSnapshot(requireContext())
                        val current = localAddSnackbar
                        if (snapshot != null && current != null) {
                            updateLocalAddSnackbarText(current, snapshot.first, snapshot.second)
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
        }
    }

    private fun updateLocalAddSnackbarText(snackbar: Snackbar, done: Int, total: Int) {
        if (total > 0) {
            snackbar.setText(getString(R.string.local_video_adding) + " ($done/$total)")
        } else {
            snackbar.setText(getString(R.string.local_video_adding))
        }
    }

    private fun buildResultItem(match: LocalAddMatchDto): com.ireum.ytdl.database.models.ResultItem {
        return com.ireum.ytdl.database.models.ResultItem(
            id = 0,
            url = match.url,
            title = match.title,
            author = match.author,
            duration = match.duration,
            thumb = match.thumb,
            website = match.website,
            playlistTitle = "",
            formats = emptyList(),
            urls = "",
            chapters = null,
            playlistURL = "",
            playlistIndex = null
        )
    }

    private fun processPendingSelections(decided: List<LocalMatchSelection>, sessionId: String) {
        if (decided.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val resultRepository = ResultRepository(db.resultDao, db.commandTemplateDao, requireContext())
            val allItems = db.historyDao.getAll()
            val existingBaseNames = allItems
                .flatMap { it.downloadPath }
                .mapNotNull { extractBaseNameFromPath(it)?.lowercase(Locale.getDefault()) }
                .toMutableSet()
            val remaining = LocalAddStorage.loadPending(requireContext(), sessionId).toMutableList()
            decided.forEach { selection ->
                val candidate = selection.candidate
                when (selection.choice) {
                    LocalMatchChoice.USE_MATCH -> {
                        val manual = selection.manualMetadata
                        if (manual != null) {
                            val localUpdate = applyLocalFileUpdates(
                                originalUri = candidate.uri,
                                desiredTitle = manual.title,
                                desiredAuthor = manual.author,
                                allowRename = candidate.uri.scheme == "file" || candidate.treeUri != null
                            )
                            val updatedUriString = localUpdate.uri.toString()
                            val updatedTreeMeta = buildTreeMeta(candidate.treeUri, localUpdate.uri)
                            val useUrl = manual.sourceUrl.ifBlank { updatedUriString }
                            if (db.historyDao.getItem(useUrl) != null) {
                                remaining.removeAll { it.uri == candidate.uri.toString() }
                                return@forEach
                            }
                            val format = Format(
                                format_id = "local",
                                container = candidate.ext,
                                filesize = candidate.size,
                                format_note = "local"
                            )
                            val item = HistoryItem(
                                id = 0,
                                url = useUrl,
                                title = manual.title,
                                author = manual.author,
                                artist = manual.artist,
                                duration = manual.duration,
                                durationSeconds = manual.duration.toDurationSeconds(),
                                thumb = manual.thumb,
                                type = DownloadType.video,
                                time = System.currentTimeMillis() / 1000L,
                                downloadPath = listOf(updatedUriString),
                                website = manual.website,
                                format = format,
                                filesize = candidate.size,
                                downloadId = 0,
                                localTreeUri = updatedTreeMeta.first,
                                localTreePath = updatedTreeMeta.second
                            )
                            db.historyDao.insert(item)
                            remaining.removeAll { it.uri == candidate.uri.toString() }
                            return@forEach
                        }
                        val match = candidate.match ?: return@forEach
                        if (db.historyDao.getItem(match.item.url) != null) {
                            remaining.removeAll { it.uri == candidate.uri.toString() }
                            return@forEach
                        }
                        val format = Format(
                            format_id = "local",
                            container = candidate.ext,
                            filesize = candidate.size,
                            format_note = "local"
                        )
                        val item = HistoryItem(
                            id = 0,
                            url = match.item.url,
                            title = match.item.title.ifBlank { candidate.title },
                            author = match.item.author,
                            artist = "",
                            duration = if (match.item.duration.isNotBlank()) match.item.duration
                            else if (candidate.durationSeconds > 0) candidate.durationSeconds.toStringDuration(Locale.US) else "",
                            durationSeconds = if (match.item.duration.isNotBlank()) match.item.duration.toDurationSeconds() else candidate.durationSeconds.toLong(),
                            thumb = match.item.thumb,
                            type = DownloadType.video,
                            time = System.currentTimeMillis() / 1000L,
                            downloadPath = listOf(candidate.uri.toString()),
                            website = match.item.website,
                            format = format,
                            filesize = candidate.size,
                            downloadId = 0,
                            localTreeUri = candidate.treeUri?.toString().orEmpty(),
                            localTreePath = buildTreeMeta(candidate.treeUri, candidate.uri).second
                        )
                        db.historyDao.insert(item)
                    }
                    LocalMatchChoice.MANUAL -> {
                        val manual = selection.manualMetadata ?: withContext(Dispatchers.Main) {
                            promptManualMetadata(
                                defaultTitle = candidate.title,
                                durationSeconds = candidate.durationSeconds,
                                defaultAuthor = "",
                                defaultDuration = "",
                                resultRepository = resultRepository,
                                allowAutoFillOnOpen = false
                            )
                        }.metadata ?: return@forEach
                        val localUpdate = applyLocalFileUpdates(
                            originalUri = candidate.uri,
                            desiredTitle = manual.title,
                            desiredAuthor = manual.author,
                            allowRename = candidate.uri.scheme == "file" || candidate.treeUri != null
                        )
                        val updatedUriString = localUpdate.uri.toString()
                        val updatedTreeMeta = buildTreeMeta(candidate.treeUri, localUpdate.uri)
                        val useUrl = manual.sourceUrl.ifBlank { updatedUriString }
                        if (db.historyDao.getItem(useUrl) != null) {
                            remaining.removeAll { it.uri == candidate.uri.toString() }
                            return@forEach
                        }
                        val format = Format(
                            format_id = "local",
                            container = candidate.ext,
                            filesize = candidate.size,
                            format_note = "local"
                        )
                        val item = HistoryItem(
                            id = 0,
                            url = useUrl,
                            title = manual.title,
                            author = manual.author,
                            artist = manual.artist,
                            duration = manual.duration,
                            durationSeconds = manual.duration.toDurationSeconds(),
                            thumb = manual.thumb,
                            type = DownloadType.video,
                            time = System.currentTimeMillis() / 1000L,
                            downloadPath = listOf(updatedUriString),
                            website = manual.website,
                            format = format,
                            filesize = candidate.size,
                            downloadId = 0,
                            localTreeUri = updatedTreeMeta.first,
                            localTreePath = updatedTreeMeta.second
                        )
                        db.historyDao.insert(item)
                        val baseName = candidate.title.ifBlank { candidate.uri.lastPathSegment ?: "" }
                        val baseKey = baseName.lowercase(Locale.getDefault())
                        if (baseName.isNotBlank()) {
                            existingBaseNames.add(baseKey)
                        }
                    }
                    else -> Unit
                }
                remaining.removeAll { it.uri == candidate.uri.toString() }
            }
            if (remaining.isEmpty()) {
                LocalAddStorage.clearPending(requireContext(), sessionId)
            } else {
                LocalAddStorage.savePending(requireContext(), sessionId, remaining)
            }
        }
    }

    fun scrollToTop() {
        recyclerView.scrollToPosition(0)
        Handler(Looper.getMainLooper()).post {
            (topAppBar.parent as AppBarLayout).setExpanded(true, true)
        }
    }

    private fun requestScrollToTop() {
        if (!this::recyclerView.isInitialized) return
        cancelPendingScrollRestore()
        lastStableScrollSnapshot = ScrollSnapshot(0, 0)
        (topAppBar.parent as? AppBarLayout)?.setExpanded(true, false)
        recyclerView.stopScroll()
        recyclerView.post {
            forceScrollToTop()
            recyclerView.post { forceScrollToTop() }
        }
    }

    private fun cancelPendingScrollRestore() {
        pendingRestoreEntry = null
        isRestoringFromNavigationBack = false
        suppressAutoScrollForNextScreenChange = false
    }

    private fun shouldAutoScrollToTop(): Boolean {
        return !isRestoringFromNavigationBack && pendingRestoreEntry == null
    }

    private fun captureNavigationState(): NavigationState {
        return NavigationState(
            sortType = historyViewModel.sortType.value,
            sortOrder = historyViewModel.sortOrder.value,
            author = historyViewModel.authorFilter.value,
            website = historyViewModel.websiteFilter.value,
            playlistId = historyViewModel.playlistFilter.value,
            status = historyViewModel.statusFilter.value,
            isYoutuberMode = historyViewModel.isYoutuberSelectionMode.value,
            isPlaylistMode = historyViewModel.isPlaylistSelectionMode.value,
            isKeywordMode = historyViewModel.isKeywordSelectionMode.value,
            isRecent = historyViewModel.isRecentMode.value,
            youtuberGroup = historyViewModel.youtuberGroupFilter.value,
            keywordGroup = historyViewModel.keywordGroupFilter.value,
            playlistGroup = historyViewModel.playlistGroupFilter.value,
            query = historyViewModel.queryFilterFlow.value,
            titleQuery = historyViewModel.titleQueryFilterFlow.value,
            keywordQuery = historyViewModel.keywordQueryFilterFlow.value,
            creatorQuery = historyViewModel.creatorQueryFilterFlow.value,
            includeChildCategoryVideos = historyViewModel.includeChildCategoryVideosFilter.value,
            keyword = historyViewModel.keywordFilter.value,
            searchFields = historyViewModel.searchFieldsFilter.value,
            type = historyViewModel.typeFilterFlow.value
        )
    }

    private fun toScreenKey(state: NavigationState): ScreenKey {
        return ScreenKey(
            sortType = state.sortType,
            sortOrder = state.sortOrder,
            author = state.author,
            website = state.website,
            playlistId = state.playlistId,
            status = state.status,
            isYoutuberMode = state.isYoutuberMode,
            isPlaylistMode = state.isPlaylistMode,
            isKeywordMode = state.isKeywordMode,
            isRecent = state.isRecent,
            youtuberGroup = state.youtuberGroup,
            keywordGroup = state.keywordGroup,
            playlistGroup = state.playlistGroup,
            query = state.query,
            titleQuery = state.titleQuery,
            keywordQuery = state.keywordQuery,
            creatorQuery = state.creatorQuery,
            includeChildCategoryVideos = state.includeChildCategoryVideos,
            keyword = state.keyword,
            searchFieldsKey = state.searchFields.map { it.name }.sorted().joinToString(","),
            type = state.type
        )
    }

    private fun applyNavigationState(state: NavigationState) {
        historyViewModel.setYoutuberSelectionMode(state.isYoutuberMode)
        historyViewModel.setPlaylistSelectionMode(state.isPlaylistMode)
        historyViewModel.setKeywordSelectionMode(state.isKeywordMode)
        historyViewModel.setRecentMode(state.isRecent)

        historyViewModel.sortType.value = state.sortType
        historyViewModel.sortOrder.value = state.sortOrder
        historyViewModel.setYoutuberGroupFilter(state.youtuberGroup)
        historyViewModel.setKeywordGroupFilter(state.keywordGroup)
        historyViewModel.setPlaylistGroupFilter(state.playlistGroup)
        historyViewModel.setPlaylistFilter(state.playlistId)
        historyViewModel.setAuthorFilter(state.author)
        historyViewModel.setKeywordFilter(state.keyword)
        historyViewModel.setWebsiteFilter(state.website)
        historyViewModel.setStatusFilter(state.status)
        historyViewModel.setQueryFilter(state.query)
        historyViewModel.setTitleQueryFilter(state.titleQuery)
        historyViewModel.setKeywordQueryFilter(state.keywordQuery)
        historyViewModel.setCreatorQueryFilter(state.creatorQuery)
        historyViewModel.setIncludeChildCategoryVideosFilter(state.includeChildCategoryVideos)
        historyViewModel.setSearchFieldsFilter(state.searchFields)
        historyViewModel.setTypeFilter(state.type)
    }

    private fun captureScrollSnapshot(): ScrollSnapshot {
        val snapshot = captureCurrentVisibleScrollSnapshot()
        if (snapshot != null) {
            lastStableScrollSnapshot = snapshot
            return snapshot
        }
        return lastStableScrollSnapshot
    }

    private fun captureCurrentVisibleScrollSnapshot(): ScrollSnapshot? {
        if (!this::recyclerView.isInitialized) return null
        val firstChild = recyclerView.getChildAt(0) ?: return null
        val position = recyclerView.getChildAdapterPosition(firstChild)
        if (position == RecyclerView.NO_POSITION) return null
        return ScrollSnapshot(position, firstChild.top)
    }

    private fun requestRestoreScroll(scroll: ScrollSnapshot) {
        if (!this::recyclerView.isInitialized) return
        recyclerView.post {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            if (layoutManager != null) {
                val maxPosition = (historyAdapter.itemCount - 1).coerceAtLeast(0)
                val targetPosition = scroll.position.coerceIn(0, maxPosition)
                layoutManager.scrollToPositionWithOffset(targetPosition, scroll.offset)
                recyclerView.post {
                    layoutManager.scrollToPositionWithOffset(targetPosition, scroll.offset)
                }
                lastStableScrollSnapshot = ScrollSnapshot(targetPosition, scroll.offset)
            } else {
                recyclerView.scrollToPosition(scroll.position)
            }
        }
    }

    private fun schedulePendingRestoreRetry(remaining: Int = 8) {
        if (!this::recyclerView.isInitialized) return
        if (pendingRestoreEntry == null) return
        recyclerView.post {
            if (pendingRestoreEntry == null) return@post
            if (tryApplyPendingRestore()) return@post
            if (remaining <= 0) return@post
            recyclerView.postDelayed({
                schedulePendingRestoreRetry(remaining - 1)
            }, 60L)
        }
    }

    private fun tryApplyPendingRestore(force: Boolean = false): Boolean {
        val pending = pendingRestoreEntry ?: return false
        if (!force) {
            if (historyAdapter.itemCount <= 0) {
                return false
            }
            if (historyAdapter.itemCount <= pending.scroll.position) {
                return false
            }
            if (captureNavigationState() != pending.state) {
                return false
            }
            val targetScreenKey = toScreenKey(pending.state)
            if (lastScreenKey != targetScreenKey) {
                return false
            }
        }
        pendingRestoreEntry = null
        isRestoringFromNavigationBack = false
        requestRestoreScroll(pending.scroll)
        return true
    }

    private fun pushCurrentStateToNavigationStack() {
        if (!this::recyclerView.isInitialized) return
        val state = captureNavigationState()
        val scroll = captureScrollSnapshot()
        navigationBackStack.addLast(NavigationEntry(state, scroll))
    }

    private fun clearNavigationBackStack() {
        navigationBackStack.clear()
    }

    private fun totalYoutuberSelectionCount(): Int {
        return historyAdapter.getSelectedYoutubers().size + historyAdapter.getSelectedYoutuberGroups().size
    }

    private fun totalKeywordSelectionCount(): Int {
        return historyAdapter.getSelectedKeywords().size + historyAdapter.getSelectedKeywordGroups().size
    }

    private fun collectSelectedAuthorsIncludingGroups(onResult: (List<String>) -> Unit) {
        val selectedAuthors = historyAdapter.getSelectedYoutubers()
        val selectedGroupIds = historyAdapter.getSelectedYoutuberGroups()
        lifecycleScope.launch(Dispatchers.IO) {
            val merged = linkedSetOf<String>()
            merged.addAll(selectedAuthors)
            if (selectedGroupIds.isNotEmpty()) {
                val members = DBManager.getInstance(requireContext())
                    .youtuberGroupDao
                    .getAllMembers()
                    .filter { selectedGroupIds.contains(it.groupId) }
                    .map { it.author }
                merged.addAll(members)
            }
            withContext(Dispatchers.Main) {
                onResult(merged.toList())
            }
        }
    }

    private fun collectSelectedKeywordsIncludingGroups(onResult: (List<String>) -> Unit) {
        val selectedKeywords = historyAdapter.getSelectedKeywords()
        val selectedGroupIds = historyAdapter.getSelectedKeywordGroups()
        lifecycleScope.launch(Dispatchers.IO) {
            val merged = linkedSetOf<String>()
            merged.addAll(selectedKeywords)
            if (selectedGroupIds.isNotEmpty()) {
                val members = DBManager.getInstance(requireContext())
                    .keywordGroupDao
                    .getAllMembers()
                    .filter { selectedGroupIds.contains(it.groupId) }
                    .map { it.keyword }
                merged.addAll(members)
            }
            withContext(Dispatchers.Main) {
                onResult(merged.toList())
            }
        }
    }

    private fun resetToAllVideosState() {
        clearNavigationBackStack()
        historyViewModel.setAuthorFilter("")
        historyViewModel.setYoutuberGroupFilter(-1L)
        historyViewModel.setPlaylistFilter(-1L)
        historyViewModel.setPlaylistGroupFilter(-1L)
        historyViewModel.setRecentMode(false)
        historyViewModel.setQueryFilter("")
        historyViewModel.setTitleQueryFilter("")
        historyViewModel.setKeywordQueryFilter("")
        historyViewModel.setCreatorQueryFilter("")
        historyViewModel.setIncludeChildCategoryVideosFilter(false)
        historyViewModel.setTypeFilter("")
        historyViewModel.setWebsiteFilter("")
        historyViewModel.setStatusFilter(HistoryViewModel.HistoryStatus.ALL)

        if (historyViewModel.isYoutuberSelectionMode.value) {
            historyViewModel.toggleYoutuberSelectionMode()
        }
        if (historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
        }

        historyAdapter.refresh()
        pendingScrollToTop = true
        requestScrollToTop()
    }

    private fun forceScrollToTop() {
        val layoutManager = recyclerView.layoutManager
        if (layoutManager is GridLayoutManager) {
            layoutManager.scrollToPositionWithOffset(0, 0)
        } else {
            recyclerView.scrollToPosition(0)
        }
    }

    private fun configureTokenAutocomplete(
        inputView: AutoCompleteTextView,
        suggestionsProvider: () -> List<String>
    ) {
        val adapter = ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )
        var tokenRange: Pair<Int, Int>? = null
        inputView.threshold = 1
        inputView.setAdapter(adapter)
        fun updateSuggestions() {
            val text = inputView.text?.toString().orEmpty()
            val cursor = inputView.selectionStart.coerceAtLeast(0)
            val range = extractSearchTokenBounds(text, cursor)
            if (range == null) {
                tokenRange = null
                adapter.clear()
                inputView.dismissDropDown()
                return
            }
            val token = text.substring(range.first, cursor.coerceIn(range.first, range.second))
            val normalizedToken = token.removePrefix("-").trim()
            if (normalizedToken.isBlank()) {
                tokenRange = null
                adapter.clear()
                inputView.dismissDropDown()
                return
            }
            val suggestions = suggestionsProvider()
                .asSequence()
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.getDefault()) }
                .filter { it.contains(normalizedToken, ignoreCase = true) }
                .take(50)
                .toList()
            tokenRange = range
            adapter.clear()
            adapter.addAll(suggestions)
            adapter.notifyDataSetChanged()
            if (suggestions.isNotEmpty() && inputView.hasFocus()) {
                inputView.showDropDown()
            } else {
                inputView.dismissDropDown()
            }
        }
        inputView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                updateSuggestions()
            }
        })
        inputView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) updateSuggestions() else inputView.dismissDropDown()
        }
        inputView.setOnClickListener {
            updateSuggestions()
        }
        inputView.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position).orEmpty()
            if (selected.isBlank()) return@setOnItemClickListener
            val current = inputView.text?.toString().orEmpty()
            val range = tokenRange ?: return@setOnItemClickListener
            val start = range.first.coerceIn(0, current.length)
            val end = range.second.coerceIn(start, current.length)
            val prefix = if (start < current.length && current[start] == '-') "-" else ""
            val replacement = "$prefix$selected"
            val updated = buildString {
                append(current.substring(0, start))
                append(replacement)
                append(current.substring(end))
            }
            inputView.setText(updated)
            inputView.setSelection((start + replacement.length).coerceAtMost(updated.length))
        }
    }

    private fun extractSearchTokenBounds(input: String, cursor: Int): Pair<Int, Int>? {
        if (input.isBlank()) return null
        val safeCursor = cursor.coerceIn(0, input.length)
        var start = safeCursor
        while (start > 0 && !isSearchDelimiter(input[start - 1])) {
            start--
        }
        var end = safeCursor
        while (end < input.length && !isSearchDelimiter(input[end])) {
            end++
        }
        if (start >= end) return null
        return Pair(start, end)
    }

    private fun isSearchDelimiter(ch: Char): Boolean {
        return ch.isWhitespace() || ch == ','
    }

    private fun initMenu() {
        topAppBar.setOnClickListener { scrollToTop() }
        val showingDownloadQueue = NavbarUtil.getNavBarItems(requireContext()).any { n -> n.itemId == R.id.downloadQueueMainFragment && n.isVisible }
        topAppBar.menu.findItem(R.id.download_queue).isVisible = !showingDownloadQueue
        topAppBar.setOnMenuItemClickListener { m: MenuItem ->
            when (m.itemId) {
                R.id.search_history -> showSearchDialog()
                R.id.add_local_video -> {
                    val options = arrayOf(
                        getString(R.string.add_local_video),
                        getString(R.string.add_local_video_folder)
                    )
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.add_local_video))
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "video/*"
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                    }
                                    addLocalVideoLauncher.launch(intent)
                                }
                                1 -> {
                                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                        putExtra("android.content.extra.SHOW_ADVANCED", true)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                    }
                                    addLocalVideoLauncher.launch(intent)
                                }
                            }
                        }
                        .show()
                }
                R.id.remove_history -> {
                    if (authorList.isEmpty()) {
                        Toast.makeText(context, R.string.history_is_empty, Toast.LENGTH_SHORT).show()
                    } else {
                        val deleteFile = booleanArrayOf(false)
                        val deleteDialog = MaterialAlertDialogBuilder(fragmentContext!!)
                        deleteDialog.setTitle(getString(R.string.you_are_going_to_delete_multiple_items))
                        deleteDialog.setMultiChoiceItems(
                            arrayOf(getString(R.string.delete_files_too)),
                            booleanArrayOf(false)
                        ) { _: DialogInterface?, _: Int, b: Boolean -> deleteFile[0] = b }
                        deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                        deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                            historyViewModel.deleteAll(deleteFile[0])
                        }
                        deleteDialog.show()
                    }
                }
                R.id.download_queue -> {
                    resetToAllOnResumeFromQueue = true
                    findNavController().navigate(R.id.downloadQueueMainFragment)
                }
                R.id.remove_deleted_history -> {
                    if (authorList.isEmpty()) {
                        Toast.makeText(context, R.string.history_is_empty, Toast.LENGTH_SHORT).show()
                    } else {
                        val deleteDialog = MaterialAlertDialogBuilder(fragmentContext!!)
                        deleteDialog.setTitle(getString(R.string.confirm_delete_history))
                        deleteDialog.setMessage(getString(R.string.confirm_delete_history_desc))
                        deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                        deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int -> historyViewModel.clearDeleted() }
                        deleteDialog.show()
                    }
                }
                R.id.remove_duplicates -> {
                    if (authorList.isEmpty()) {
                        Toast.makeText(context, R.string.history_is_empty, Toast.LENGTH_SHORT).show()
                    } else {
                        val deleteDialog = MaterialAlertDialogBuilder(fragmentContext!!)
                        deleteDialog.setTitle(getString(R.string.confirm_delete_history))
                        deleteDialog.setMessage(getString(R.string.confirm_delete_history_desc))
                        deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                        deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int -> historyViewModel.deleteDuplicates() }
                        deleteDialog.show()
                    }
                }
                R.id.filters -> showFiltersDialog()
            }
            true
        }
    }

    private fun getDurationSeconds(context: Context, uri: Uri): Int {
        return runCatching {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration?.toIntOrNull()?.div(1000) ?: 0
            } finally {
                runCatching { retriever?.release() }
            }
        }.getOrElse { 0 }
    }

    private data class LocalUriEntry(
        val uri: Uri,
        val treeUri: Uri?
    )

    private suspend fun addLocalVideos(
        entries: List<LocalUriEntry>,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Pair<Int, Int> {
        val db = DBManager.getInstance(requireContext())
        val resultRepository = ResultRepository(db.resultDao, db.commandTemplateDao, requireContext())
        val allItems = db.historyDao.getAll()
        val missingCandidates = allItems
            .filter { it.type == DownloadType.video && it.downloadPath.all { path -> !FileUtil.exists(path) } }
            .toMutableList()
        val existingBaseNames = allItems
            .flatMap { it.downloadPath }
            .mapNotNull { extractBaseNameFromPath(it)?.lowercase(Locale.getDefault()) }
            .toMutableSet()
        var added = 0
        var skipped = 0
        var suppressManualPrompts = false
        var manualPromptActive = false
        val counterMutex = kotlinx.coroutines.sync.Mutex()
        val pendingSet = mutableSetOf<LocalMatchCandidate>()
        val processingJobs = mutableListOf<Job>()
        val total = entries.size
        var index = 0
        val pendingMatches = mutableListOf<LocalMatchCandidate>()
        localMatchCandidates.clear()
        localMatchDeferredCandidates.clear()
        localMatchSelections = null
        localMatchDeferred = null
        localMatchDialog = null
        localMatchAdapter = null
        localMatchSearchJob?.cancel()
        localMatchSearchJob = null
        localMatchResultRepository = resultRepository
        localMatchDialogOpening = false
        localMatchRestartSearch = null
        localMatchAddFinished = false
        localMatchSkipUnset = localMatchSkipUnset@{ selections ->
            val count = selections.count { it.choice == LocalMatchChoice.UNSET }
            if (count == 0) return@localMatchSkipUnset
            lifecycleScope.launch(Dispatchers.Main) {
                localMatchSelections?.removeAll(selections)
                localMatchCandidates.removeAll(selections.map { it.candidate })
                localMatchAdapter?.notifyDataSetChanged()
            }
            lifecycleScope.launch(Dispatchers.IO) {
                selections.forEach { selection ->
                    if (pendingSet.contains(selection.candidate)) {
                        pendingSet.remove(selection.candidate)
                    }
                }
                counterMutex.withLock { skipped += count }
            }
        }
        suspend fun applyManualMetadata(candidate: LocalMatchCandidate, manual: ManualMetadata) {
            val localUpdate = applyLocalFileUpdates(
                originalUri = candidate.uri,
                desiredTitle = manual.title,
                desiredAuthor = manual.author,
                allowRename = candidate.uri.scheme == "file" || candidate.treeUri != null
            )
            val updatedUriString = localUpdate.uri.toString()
            val updatedTreeMeta = buildTreeMeta(candidate.treeUri, localUpdate.uri)
            val useUrl = manual.sourceUrl.ifBlank { updatedUriString }
            val existingByUrl = db.historyDao.getItem(useUrl)
            if (existingByUrl != null) {
                counterMutex.withLock { skipped += 1 }
                return
            }
            val resolvedThumb = manual.thumb.ifBlank {
                val match = candidate.match
                if (match != null && useUrl == match.item.url) match.item.thumb else ""
            }
            val resolvedWebsite = manual.website.ifBlank {
                val match = candidate.match
                if (match != null && useUrl == match.item.url) match.item.website else ""
            }
            val format = Format(
                format_id = "local",
                container = candidate.ext,
                filesize = candidate.size,
                format_note = "local"
            )
            val item = HistoryItem(
                id = 0,
                url = useUrl,
                title = manual.title,
                author = manual.author,
                artist = manual.artist,
                duration = manual.duration,
                durationSeconds = manual.duration.toDurationSeconds(),
                thumb = resolvedThumb,
                type = DownloadType.video,
                time = System.currentTimeMillis() / 1000L,
                downloadPath = listOf(updatedUriString),
                website = resolvedWebsite,
                format = format,
                filesize = candidate.size,
                downloadId = 0,
                localTreeUri = updatedTreeMeta.first,
                localTreePath = updatedTreeMeta.second
            )
            db.historyDao.insert(item)
            val baseName = candidate.title.ifBlank { candidate.uri.lastPathSegment ?: "" }
            val baseKey = baseName.lowercase(Locale.getDefault())
            if (baseName.isNotBlank()) {
                existingBaseNames.add(baseKey)
            }
            counterMutex.withLock { added += 1 }
        }

        localMatchConfirmCallback = localMatchConfirmCallback@{ selections ->
            val toProcess = selections.filter { it.choice != LocalMatchChoice.UNSET }
            if (toProcess.isEmpty()) return@localMatchConfirmCallback
            lifecycleScope.launch(Dispatchers.Main) {
                localMatchSelections?.removeAll(toProcess)
                localMatchCandidates.removeAll(toProcess.map { it.candidate })
                localMatchAdapter?.notifyDataSetChanged()
                if (localMatchDeferredCandidates.isEmpty()) {
                    localMatchRefreshView?.visibility = View.GONE
                }
            }
            val job = lifecycleScope.launch(Dispatchers.IO) {
                toProcess.forEach { selection ->
                    val candidate = selection.candidate
                    when (selection.choice) {
                        LocalMatchChoice.USE_MATCH -> {
                            val manual = selection.manualMetadata
                            if (manual != null) {
                                applyManualMetadata(candidate, manual)
                                pendingSet.remove(candidate)
                                return@forEach
                            }
                            val match = candidate.match ?: run {
                                counterMutex.withLock { skipped += 1 }
                                pendingSet.remove(candidate)
                                return@forEach
                            }
                            val localUpdate = applyLocalFileUpdates(
                                originalUri = candidate.uri,
                                desiredTitle = match.item.title,
                                desiredAuthor = match.item.author,
                                allowRename = candidate.uri.scheme == "file" || candidate.treeUri != null
                            )
                            val updatedUriString = localUpdate.uri.toString()
                            val updatedTreeMeta = buildTreeMeta(candidate.treeUri, localUpdate.uri)
                            val baseName = candidate.title.ifBlank { candidate.uri.lastPathSegment ?: "" }
                            val baseKey = baseName.lowercase(Locale.getDefault())
                            val existingByUrl = db.historyDao.getItem(match.item.url)
                            if (existingByUrl != null) {
                                counterMutex.withLock { skipped += 1 }
                                pendingSet.remove(candidate)
                                return@forEach
                            }
                            val format = Format(
                                format_id = "local",
                                container = candidate.ext,
                                filesize = candidate.size,
                                format_note = "local"
                            )
                            val item = HistoryItem(
                                id = 0,
                                url = match.item.url,
                                title = match.item.title.ifBlank { candidate.title },
                                author = match.item.author,
                                artist = "",
                                duration = if (match.item.duration.isNotBlank()) match.item.duration
                                else if (candidate.durationSeconds > 0) candidate.durationSeconds.toStringDuration(Locale.US) else "",
                                durationSeconds = if (match.item.duration.isNotBlank()) match.item.duration.toDurationSeconds() else candidate.durationSeconds.toLong(),
                                thumb = match.item.thumb,
                                type = DownloadType.video,
                                time = System.currentTimeMillis() / 1000L,
                                downloadPath = listOf(updatedUriString),
                                website = match.item.website,
                                format = format,
                                filesize = candidate.size,
                                downloadId = 0,
                                localTreeUri = updatedTreeMeta.first,
                                localTreePath = updatedTreeMeta.second
                            )
                            db.historyDao.insert(item)
                            if (baseName.isNotBlank()) {
                                existingBaseNames.add(baseKey)
                            }
                            counterMutex.withLock { added += 1 }
                            pendingSet.remove(candidate)
                        }
                        LocalMatchChoice.MANUAL -> {
                            if (suppressManualPrompts) {
                                counterMutex.withLock { skipped += 1 }
                                pendingSet.remove(candidate)
                                return@forEach
                            }
                            val manual = selection.manualMetadata ?: run {
                                val manualResult = withContext(Dispatchers.Main) {
                                    val match = candidate.match
                                    promptManualMetadata(
                                        defaultTitle = match?.item?.title?.ifBlank { candidate.title } ?: candidate.title,
                                        durationSeconds = candidate.durationSeconds,
                                        defaultAuthor = match?.item?.author.orEmpty(),
                                        defaultDuration = match?.item?.duration.orEmpty(),
                                        resultRepository = resultRepository,
                                        allowAutoFillOnOpen = false
                                    )
                                }
                                val metadata = manualResult.metadata
                                if (metadata == null) {
                                    if (manualResult.cancelled) {
                                        suppressManualPrompts = true
                                    }
                                    counterMutex.withLock { skipped += 1 }
                                    pendingSet.remove(candidate)
                                    return@forEach
                                }
                                metadata
                            }
                            applyManualMetadata(candidate, manual)
                            pendingSet.remove(candidate)
                        }
                        LocalMatchChoice.UNSET -> {
                            counterMutex.withLock { skipped += 1 }
                            pendingSet.remove(candidate)
                        }
                    }
                }
            }
            processingJobs.add(job)
        }
        entries.forEach { entry ->
            val uri = entry.uri
            val treeUri = entry.treeUri
            index += 1
            onProgress(index, total)
            if (!currentCoroutineContext().isActive) return added to skipped
            val doc = documentFileForUri(uri)
            val uriString = uri.toString()
            val treeMeta = buildTreeMeta(treeUri, uri)
            if (treeMeta.first.isNotBlank() && treeMeta.second.isNotBlank()) {
                val existingByTree = db.historyDao.getItemByLocalTree(treeMeta.first, treeMeta.second)
                if (existingByTree != null) {
                    skipped += 1
                    return@forEach
                }
            }
            val existing = db.historyDao.getItemByDownloadPath(escapeLikeQuery(uriString))
            if (existing != null) {
                skipped += 1
                return@forEach
            }
            if (doc == null || doc.isDirectory) {
                skipped += 1
                return@forEach
            }
            val name = doc?.name ?: uri.lastPathSegment ?: getString(R.string.unknown)
            val title = name.substringBeforeLast('.')
            val baseName = title.ifBlank { name }
            val baseKey = baseName.lowercase(Locale.getDefault())
            if (baseName.isNotBlank() && existingBaseNames.contains(baseKey)) {
                skipped += 1
                return@forEach
            }
            val ext = name.substringAfterLast('.', "")
            val size = doc?.length() ?: 0L
            val durationSeconds = getDurationSeconds(requireContext(), uri)
            val reconnectCandidates = findReconnectCandidates(
                candidates = missingCandidates,
                title = title,
                size = size,
                durationSeconds = durationSeconds
            )
            if (reconnectCandidates.isNotEmpty()) {
                val selected = withContext(Dispatchers.Main) {
                    promptReconnectCandidate(
                        title = title,
                        size = size,
                        durationSeconds = durationSeconds,
                        candidates = reconnectCandidates
                    )
                }
                if (selected != null) {
                    val reconnected = reconnectHistoryItem(
                        db = db,
                        candidates = missingCandidates,
                        uri = uri,
                        treeUri = treeUri,
                        size = size,
                        selected = selected
                    )
                if (reconnected) {
                    if (baseName.isNotBlank()) {
                        existingBaseNames.add(baseKey)
                    }
                    added += 1
                    return@forEach
                }
                }
            }
            val (searchQuery, _) = extractTitleAndAuthorHint(title)
            val hasSearchResults = if (resultRepository != null && searchQuery.isNotBlank()) {
                runCatching {
                    resultRepository.search(searchQuery, resetResults = false, addToResults = false)
                }.getOrDefault(emptyList()).isNotEmpty()
            } else {
                false
            }
            if (!hasSearchResults) {
                if (suppressManualPrompts) {
                    skipped += 1
                    return@forEach
                }
                withContext(Dispatchers.Main) {
                    while (localMatchDialog != null || localMatchDialogOpening || manualPromptActive) {
                        kotlinx.coroutines.delay(200L)
                    }
                }
                manualPromptActive = true
                val manualResult = try {
                    withContext(Dispatchers.Main) {
                        promptManualMetadata(
                            defaultTitle = title,
                            durationSeconds = durationSeconds,
                            defaultAuthor = "",
                            defaultDuration = "",
                            resultRepository = resultRepository,
                            allowAutoFillOnOpen = false
                        )
                    }
                } finally {
                    manualPromptActive = false
                }
                val manual = manualResult.metadata
                if (manual == null) {
                    if (manualResult.cancelled) {
                        suppressManualPrompts = true
                    }
                    skipped += 1
                    return@forEach
                }
                val candidate = LocalMatchCandidate(
                    uri = uri,
                    treeUri = treeUri,
                    title = title,
                    ext = ext,
                    size = size,
                    durationSeconds = durationSeconds,
                    match = null
                )
                applyManualMetadata(candidate, manual)
                return@forEach
            }
            pendingMatches.add(
                LocalMatchCandidate(
                    uri = uri,
                    treeUri = treeUri,
                    title = title,
                    ext = ext,
                    size = size,
                    durationSeconds = durationSeconds,
                    match = null
                )
            )
            val candidate = pendingMatches.last()
            pendingSet.add(candidate)
            if (localMatchDialog != null) {
                localMatchCandidates.add(candidate)
                localMatchSelections?.let { selections ->
                    val selection = LocalMatchSelection(candidate, LocalMatchChoice.UNSET, LocalMatchStatus.LOADING)
                    selections.add(selection)
                    lifecycleScope.launch(Dispatchers.Main) {
                        localMatchAdapter?.notifyItemInserted(selections.size - 1)
                        localMatchRestartSearch?.invoke()
                    }
                }
            } else {
                localMatchCandidates.add(candidate)
                localMatchSelections?.let { selections ->
                    val selection = LocalMatchSelection(candidate, LocalMatchChoice.UNSET, LocalMatchStatus.LOADING)
                    selections.add(selection)
                    lifecycleScope.launch(Dispatchers.Main) {
                        localMatchAdapter?.notifyItemInserted(selections.size - 1)
                    }
                }
            }
        }

        localMatchAddFinished = true
        if (pendingMatches.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                while (localMatchDialog != null) {
                    kotlinx.coroutines.delay(200L)
                }
            }
            val selections = withContext(Dispatchers.Main) {
                openLocalMatchDialog(resultRepository, awaitResult = true)
            }
            if (selections != null) {
                localMatchConfirmCallback?.invoke(selections)
            } else {
                counterMutex.withLock {
                    skipped += pendingSet.size
                }
                pendingSet.clear()
            }
            processingJobs.joinAll()
        }
        localMatchDeferred = null
        return added to skipped
    }

    private data class LocalFileUpdateResult(
        val uri: Uri,
        val displayName: String,
        val renamed: Boolean,
        val metadataUpdated: Boolean
    )

    private data class LocalMatchCandidate(
        val uri: Uri,
        val treeUri: Uri?,
        val title: String,
        val ext: String,
        val size: Long,
        val durationSeconds: Int,
        var match: YoutubeMatch?
    )

    private enum class LocalMatchChoice { UNSET, USE_MATCH, MANUAL }

    private enum class LocalMatchStatus { LOADING, FOUND, NONE }

    private data class LocalMatchSelection(
        val candidate: LocalMatchCandidate,
        var choice: LocalMatchChoice,
        var status: LocalMatchStatus = LocalMatchStatus.LOADING,
        var manualMetadata: ManualMetadata? = null
    )

    private fun applyLocalFileUpdates(
        originalUri: Uri,
        desiredTitle: String,
        desiredAuthor: String,
        allowRename: Boolean
    ): LocalFileUpdateResult {
        val resolver = requireContext().contentResolver
        val doc = documentFileForUri(originalUri)
        val currentName = doc?.name ?: originalUri.lastPathSegment ?: ""
        val ext = currentName.substringAfterLast('.', "")
        val baseName = buildLocalFileBaseName(desiredTitle, desiredAuthor, currentName)
        val targetName = if (ext.isNotBlank()) "$baseName.$ext" else baseName
        var updatedUri = originalUri
        var renamed = false
        var metadataUpdated = false

        if (allowRename && targetName.isNotBlank() && targetName != currentName) {
            when (originalUri.scheme) {
                "file" -> {
                    val filePath = originalUri.path
                    if (!filePath.isNullOrBlank()) {
                        val file = File(filePath)
                        val targetFile = File(file.parentFile, targetName)
                        if (!targetFile.exists()) {
                            renamed = runCatching { file.renameTo(targetFile) }.getOrDefault(false)
                            if (renamed) {
                                updatedUri = Uri.fromFile(targetFile)
                            }
                        }
                    }
                }
                "content" -> {
                    val renameUri = runCatching {
                        DocumentsContract.renameDocument(resolver, originalUri, targetName)
                    }.getOrNull()
                    if (renameUri != null) {
                        updatedUri = renameUri
                        renamed = true
                    } else if (doc != null) {
                        renamed = runCatching { doc.renameTo(targetName) }.getOrDefault(false)
                    }
                }
            }
        }

        if (updatedUri.authority == MediaStore.AUTHORITY) {
            val values = ContentValues().apply {
                if (desiredTitle.isNotBlank()) {
                    put(MediaStore.MediaColumns.TITLE, desiredTitle)
                }
                if (desiredAuthor.isNotBlank()) {
                    put(MediaStore.Video.VideoColumns.ARTIST, desiredAuthor)
                }
                if (allowRename && targetName.isNotBlank()) {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                }
            }
            if (values.size() > 0) {
                val updatedRows = runCatching {
                    resolver.update(updatedUri, values, null, null)
                }.getOrDefault(0)
                metadataUpdated = updatedRows > 0
            }
        }

        if (updatedUri != originalUri && updatedUri.scheme == "content") {
            runCatching {
                resolver.takePersistableUriPermission(
                    updatedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }

        return LocalFileUpdateResult(
            uri = updatedUri,
            displayName = if (targetName.isNotBlank()) targetName else currentName,
            renamed = renamed,
            metadataUpdated = metadataUpdated
        )
    }

    private fun buildLocalFileBaseName(
        title: String,
        author: String,
        fallbackName: String
    ): String {
        val raw = when {
            author.isNotBlank() && title.isNotBlank() -> "$author - $title"
            title.isNotBlank() -> title
            author.isNotBlank() -> author
            else -> fallbackName.substringBeforeLast('.')
        }
        return sanitizeLocalFileName(raw).ifBlank {
            sanitizeLocalFileName(fallbackName.substringBeforeLast('.'))
        }
    }

    private fun sanitizeLocalFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
    }

    private data class ReconnectCandidate(
        val item: HistoryItem,
        val score: Float,
        val sizeOk: Boolean,
        val durationOk: Boolean,
        val titleScore: Float
    )

    private fun findReconnectCandidates(
        candidates: List<HistoryItem>,
        title: String,
        size: Long,
        durationSeconds: Int
    ): List<ReconnectCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val normalizedTitle = normalizeTitle(title)
        if (normalizedTitle.isBlank() || size <= 0L || durationSeconds <= 0) return emptyList()
        val results = candidates.mapNotNull { item ->
            val itemSize = if (item.filesize > 0) item.filesize else item.format.filesize
            val itemDuration = parseDurationSeconds(item.duration)
            val sizeDiff = if (size > 0 && itemSize > 0) kotlin.math.abs(itemSize - size) else Long.MAX_VALUE
            val sizeTolerance = if (size > 0) kotlin.math.max(1L, (size * 0.05).toLong()) else Long.MAX_VALUE
            val sizeOk = size > 0 && itemSize > 0 && sizeDiff <= sizeTolerance
            val durationDiff = if (durationSeconds > 0 && itemDuration > 0) kotlin.math.abs(itemDuration - durationSeconds) else Int.MAX_VALUE
            val durationOk = durationSeconds > 0 && itemDuration > 0 && durationDiff <= 10
            val titleScore = titleSimilarity(normalizedTitle, normalizeTitle(item.title.ifBlank { item.url }))
            val titleOk = titleScore >= 0.45f
            if (sizeOk && durationOk && titleOk) {
                val score = 5.5f + (titleScore * 2f)
                ReconnectCandidate(item, score, sizeOk, durationOk, titleScore)
            } else null
        }
        return results.sortedByDescending { it.score }.take(6)
    }

    private fun reconnectHistoryItem(
        db: DBManager,
        candidates: MutableList<HistoryItem>,
        uri: Uri,
        treeUri: Uri?,
        size: Long,
        selected: HistoryItem
    ): Boolean {
        val uriString = uri.toString()
        val treeMeta = buildTreeMeta(treeUri, uri)
        val updatedUrl = if (selected.url.startsWith("content://") || selected.url.startsWith("file://") || selected.url.isBlank()) {
            uriString
        } else {
            selected.url
        }
        val updatedFormat = if (size > 0 && selected.format.filesize == 0L) selected.format.copy(filesize = size) else selected.format
        val updatedItem = selected.copy(
            url = updatedUrl,
            downloadPath = listOf(uriString),
            filesize = if (size > 0) size else selected.filesize,
            format = updatedFormat,
            localTreeUri = treeMeta.first,
            localTreePath = treeMeta.second
        )
        db.historyDao.update(updatedItem)
        candidates.removeAll { it.id == selected.id }
        return true
    }

    private suspend fun promptReconnectCandidate(
        title: String,
        size: Long,
        durationSeconds: Int,
        candidates: List<ReconnectCandidate>
    ): HistoryItem? {
        if (candidates.isEmpty()) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val context = requireContext()
            val sizeText = if (size > 0) FileUtil.convertFileSize(size) else getString(R.string.unknown)
            val durationText = if (durationSeconds > 0) durationSeconds.toStringDuration(Locale.US) else getString(R.string.unknown)
            val padding = (resources.displayMetrics.density * 12).toInt()
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
            val messageView = TextView(context).apply {
                text = getString(R.string.local_video_reconnect_hint, title, sizeText, durationText)
            }
            val listView = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = ReconnectCandidateAdapter(candidates) { selected ->
                    if (cont.isActive) cont.resume(selected)
                }
            }
            container.addView(
                messageView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = padding }
            )
            container.addView(
                listView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.match_found))
                .setView(container)
                .setNegativeButton(getString(R.string.skip)) { _, _ ->
                    if (cont.isActive) cont.resume(null)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(null)
                }
                .create()
            (listView.adapter as? ReconnectCandidateAdapter)?.onDismiss = {
                if (dialog.isShowing) dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun buildTreeMeta(treeUri: Uri?, fileUri: Uri): Pair<String, String> {
        if (treeUri == null) return "" to ""
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
        if (treeId.isNullOrBlank() || docId.isNullOrBlank()) return "" to ""
        val relative = if (docId == treeId) "" else docId.removePrefix("$treeId/").removePrefix(treeId).trimStart('/')
        return treeUri.toString() to relative
    }

    private fun expandVideoUris(uris: List<Uri>): List<LocalUriEntry> {
        val result = ArrayList<LocalUriEntry>()
        uris.forEach { uri ->
            val doc = documentFileForUri(uri)
            if (doc != null && doc.isDirectory) {
                collectVideoUrisRecursive(doc, result, uri)
            } else {
                result.add(LocalUriEntry(uri, null))
            }
        }
        return result.distinctBy { entry ->
            localEntryIdentity(entry)
        }
    }

    private fun showSearchDialog() {
        val searchSheet = BottomSheetDialog(requireContext())
        searchSheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        searchSheet.setContentView(R.layout.history_search_sheet)

        val titleQueryInput = searchSheet.findViewById<AutoCompleteTextView>(R.id.titleQueryInput)
        val keywordQueryInput = searchSheet.findViewById<AutoCompleteTextView>(R.id.keywordQueryInput)
        val creatorQueryInput = searchSheet.findViewById<AutoCompleteTextView>(R.id.creatorQueryInput)

        titleQueryInput?.setText(historyViewModel.titleQueryFilterFlow.value)
        keywordQueryInput?.setText(historyViewModel.keywordQueryFilterFlow.value)
        creatorQueryInput?.setText(historyViewModel.creatorQueryFilterFlow.value)

        titleQueryInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                historyViewModel.setTitleQueryFilter(s?.toString().orEmpty())
            }
        })
        keywordQueryInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                historyViewModel.setKeywordQueryFilter(s?.toString().orEmpty())
            }
        })
        creatorQueryInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                historyViewModel.setCreatorQueryFilter(s?.toString().orEmpty())
            }
        })

        keywordQueryInput?.let { input ->
            configureTokenAutocomplete(input) { keywordList }
        }
        creatorQueryInput?.let { input ->
            configureTokenAutocomplete(input) { authorList }
        }

        val displayMetrics = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
        searchSheet.behavior.peekHeight = displayMetrics.heightPixels
        searchSheet.show()
    }

    private fun localEntryIdentity(entry: LocalUriEntry): String {
        val treeMeta = buildTreeMeta(entry.treeUri, entry.uri)
        if (treeMeta.first.isNotBlank() && treeMeta.second.isNotBlank()) {
            return "tree:${treeMeta.first}|${treeMeta.second}"
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(entry.uri) }.getOrNull()
        if (!documentId.isNullOrBlank()) {
            return "doc:$documentId"
        }
        return "uri:${entry.uri.normalizeScheme()}"
    }

    private fun collectVideoUrisRecursive(dir: DocumentFile, output: MutableList<LocalUriEntry>, treeUri: Uri) {
        dir.listFiles().forEach { child ->
            if (child.isDirectory) {
                collectVideoUrisRecursive(child, output, treeUri)
            } else if (child.isFile && isVideoDocument(child)) {
                output.add(LocalUriEntry(child.uri, treeUri))
            }
        }
    }

    private fun documentFileForUri(uri: Uri): DocumentFile? {
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(requireContext(), uri)
        } else {
            DocumentFile.fromSingleUri(requireContext(), uri)
        }
    }

    private fun extractBaseNameFromPath(path: String): String? {
        if (path.isBlank()) return null
        return when {
            path.startsWith("content://") || path.startsWith("file://") -> {
                val uri = Uri.parse(path)
                val doc = documentFileForUri(uri)
                val name = doc?.name ?: uri.lastPathSegment
                name?.substringBeforeLast('.')?.trim().takeIf { !it.isNullOrBlank() }
            }
            else -> File(path).nameWithoutExtension.trim().takeIf { it.isNotBlank() }
        }
    }

    private fun escapeLikeQuery(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    private fun isVideoDocument(doc: DocumentFile): Boolean {
        val mime = doc.type ?: return false
        return mime.startsWith("video/")
    }

    private data class YoutubeMatch(
        val item: com.ireum.ytdl.database.models.ResultItem,
        val titleSimilarity: Float,
        val durationDiffSeconds: Int,
        val exactTitleMatch: Boolean = false
    )

    private class ReconnectCandidateAdapter(
        private val items: List<ReconnectCandidate>,
        private val onSelect: (HistoryItem) -> Unit
    ) : RecyclerView.Adapter<ReconnectCandidateAdapter.ViewHolder>() {
        var onDismiss: (() -> Unit)? = null

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(android.R.id.text1)
            val meta: TextView = itemView.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position].item
            val title = item.title.ifBlank { holder.itemView.context.getString(R.string.unknown) }
            val author = item.author.ifBlank { "-" }
            val duration = if (item.duration.isNotBlank()) item.duration else "-"
            holder.title.text = title
            holder.meta.text = "$author - $duration"
            holder.itemView.setOnClickListener {
                onSelect(item)
                onDismiss?.invoke()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private suspend fun findYoutubeMatch(
        resultRepository: ResultRepository,
        title: String,
        durationSeconds: Int
    ): YoutubeMatch? {
        if (title.isBlank()) return null
        val (searchQuery, expectedAuthor) = extractTitleAndAuthorHint(title)
        val results = resultRepository.search(searchQuery, resetResults = false, addToResults = false)
        if (results.isEmpty()) return null
        val normalizedQuery = normalizeTitle(searchQuery)
        val first = results.first()
        val firstTitle = normalizeTitle(first.title)
        if (normalizedQuery.isNotBlank() && normalizedQuery == firstTitle) {
            val firstSeconds = parseDurationSeconds(first.duration)
            val firstDiff = if (durationSeconds > 0 && firstSeconds > 0) {
                abs(firstSeconds - durationSeconds)
            } else {
                Int.MAX_VALUE
            }
            return YoutubeMatch(first, 1f, firstDiff, exactTitleMatch = true)
        }
        val normalizedTitle = normalizedQuery
        if (normalizedTitle.length < 4) return null
        val candidates = results.mapNotNull { item ->
            val itemTitle = normalizeTitle(item.title)
            if (itemTitle.isBlank()) return@mapNotNull null
            val titleSim = titleSimilarity(normalizedTitle, itemTitle)
            if (expectedAuthor.isNotBlank()) {
                val authorSim = titleSimilarity(normalizeTitle(expectedAuthor), normalizeTitle(item.author))
                if (authorSim < 0.8f) return@mapNotNull null
            }
            val itemSeconds = parseDurationSeconds(item.duration)
            val durationDiff = if (durationSeconds > 0 && itemSeconds > 0) {
                abs(itemSeconds - durationSeconds)
            } else {
                Int.MAX_VALUE
            }
            val durationOk = durationSeconds > 0 && itemSeconds > 0 && durationDiff <= 5
            val titleOk = titleSim >= 0.85f
            if (durationSeconds > 0) {
                if (!durationOk || !titleOk) return@mapNotNull null
            } else {
                if (titleSim < 0.92f) return@mapNotNull null
            }
            YoutubeMatch(item, titleSim, durationDiff)
        }
        return candidates.maxByOrNull { match ->
            val durationPenalty = if (match.durationDiffSeconds == Int.MAX_VALUE) 0f else (match.durationDiffSeconds / 60f)
            match.titleSimilarity - durationPenalty
        }
    }

    private fun extractTitleAndAuthorHint(value: String): Pair<String, String> {
        val trimmed = value.trim()
        val parts = trimmed.split(" - ", limit = 2)
        if (parts.size == 2) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (left.isNotBlank() && right.isNotBlank() && !left.equals("y2mate.com", ignoreCase = true)) {
                return Pair(right, left)
            }
        }
        return Pair(trimmed, "")
    }

    private fun normalizeTitle(value: String): String {
        return value
            .lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun titleSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0f
        val dist = levenshteinDistance(a, b)
        return 1f - (dist.toFloat() / maxLen.toFloat())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val rows = a.length + 1
        val cols = b.length + 1
        val dp = IntArray(cols) { it }
        for (i in 1 until rows) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1 until cols) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + cost
                )
                prev = temp
            }
        }
        return dp[cols - 1]
    }

    private fun parseDurationSeconds(duration: String): Int {
        if (duration.isBlank()) return 0
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    private fun normalizeAuthors(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val parts = parseAuthorsWithQuotes(trimmed)
        return parts.joinToString(", ") { (token, quoted) ->
            if (quoted) "\"$token\"" else token
        }
    }

    private class DelimiterTokenizer : MultiAutoCompleteTextView.Tokenizer {
        private val delimiters = setOf(',', '/', '，', '／')

        override fun findTokenStart(text: CharSequence, cursor: Int): Int {
            var i = cursor
            while (i > 0 && !delimiters.contains(text[i - 1])) {
                i--
            }
            while (i < cursor && text[i] == ' ') {
                i++
            }
            return i
        }

        override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
            var i = cursor
            while (i < text.length) {
                if (delimiters.contains(text[i])) {
                    return i
                }
                i++
            }
            return text.length
        }

        override fun terminateToken(text: CharSequence): CharSequence {
            var i = text.length
            while (i > 0 && text[i - 1] == ' ') {
                i--
            }
            if (i > 0 && delimiters.contains(text[i - 1])) {
                return text
            }
            return "$text, "
        }
    }

    private data class ManualMetadata(
        val title: String,
        val author: String,
        val artist: String,
        val duration: String,
        val sourceUrl: String,
        val thumb: String,
        val website: String
    )

    private data class ManualMetadataResult(
        val metadata: ManualMetadata?,
        val cancelled: Boolean
    )

    private suspend fun promptManualMetadata(
        defaultTitle: String,
        durationSeconds: Int,
        defaultAuthor: String = "",
        defaultArtist: String = "",
        defaultDuration: String = "",
        defaultSourceUrl: String = "",
        resultRepository: ResultRepository? = null,
        allowAutoFillOnOpen: Boolean = true
    ): ManualMetadataResult {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val context = requireContext()

            fun showManualDialog(
                initialResult: com.ireum.ytdl.database.models.ResultItem?,
                allowAutoSearchOnShow: Boolean,
                allowAutoFill: Boolean
            ) {
                val padding = (resources.displayMetrics.density * 12).toInt()
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)
                }
                val titleInput = EditText(context).apply {
                    hint = getString(R.string.video_title)
                    setText(defaultTitle)
                }
                val authorInput = MultiAutoCompleteTextView(context).apply {
                    hint = getString(R.string.video_author)
                    if (defaultAuthor.isNotBlank()) setText(defaultAuthor)
                    threshold = 1
                    setTokenizer(DelimiterTokenizer())
                }
                val artistInput = MultiAutoCompleteTextView(context).apply {
                    hint = getString(R.string.artist)
                    if (defaultArtist.isNotBlank()) setText(defaultArtist)
                    threshold = 1
                    setTokenizer(DelimiterTokenizer())
                }
                val durationInput = EditText(context).apply {
                    hint = getString(R.string.video_duration)
                    val durationText = when {
                        defaultDuration.isNotBlank() -> defaultDuration
                        durationSeconds > 0 -> durationSeconds.toStringDuration(Locale.US)
                        else -> ""
                    }
                    if (durationText.isNotBlank()) setText(durationText)
                }
                val sourceUrlInput = EditText(context).apply {
                    hint = getString(R.string.video_source_url_optional)
                    if (defaultSourceUrl.isNotBlank()) setText(defaultSourceUrl)
                }
                val searchButton = android.widget.Button(context).apply {
                    text = getString(R.string.search_in_app)
                }
                if (resultRepository == null) {
                    searchButton.isEnabled = false
                    searchButton.alpha = 0.5f
                }
                container.addView(titleInput)
                val youtuberAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, authorList)
                authorInput.setAdapter(youtuberAdapter)
                artistInput.setAdapter(youtuberAdapter)
                container.addView(authorInput)
                container.addView(artistInput)
                container.addView(durationInput)
                container.addView(sourceUrlInput)
                container.addView(searchButton)

                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(getString(R.string.enter_video_info))
                    .setView(container)
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton(R.string.fetch_from_link, null)
                    .setNegativeButton(R.string.skip) { _, _ ->
                        cont.resume(ManualMetadataResult(null, cancelled = false))
                    }
                    .setOnCancelListener {
                        cont.resume(ManualMetadataResult(null, cancelled = true))
                    }
                    .create()
                dialog.setOnShowListener {
                    var isFetching = false
                    var lastFetchedUrl = ""
                    var suppressAutoFill = false
                    var selectedResult: com.ireum.ytdl.database.models.ResultItem? = null
                    var userEdited = false
                    var isApplyingAutoFill = false
                    val debounceHandler = Handler(Looper.getMainLooper())
                    var debounceRunnable: Runnable? = null
                    val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    val neutral = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)

                    fun normalizeInput(text: String): String {
                        return text
                            .replace(Regex("[\\p{Z}\\s\\u00A0\\u200B\\u200C\\u200D\\uFEFF]+"), " ")
                            .trim()
                    }

                    fun setTextSilently(editText: EditText, value: String) {
                        isApplyingAutoFill = true
                        editText.setText(value)
                        isApplyingAutoFill = false
                    }

                    val markEditedWatcher = object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            if (!isApplyingAutoFill) {
                                userEdited = true
                            }
                        }
                        override fun afterTextChanged(s: android.text.Editable?) = Unit
                    }
                    titleInput.addTextChangedListener(markEditedWatcher)
                    authorInput.addTextChangedListener(markEditedWatcher)
                    artistInput.addTextChangedListener(markEditedWatcher)
                    durationInput.addTextChangedListener(markEditedWatcher)
                    sourceUrlInput.addTextChangedListener(markEditedWatcher)

                    authorInput.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            authorInput.showDropDown()
                        }
                    }
                    authorInput.setOnClickListener {
                        authorInput.showDropDown()
                    }
                    artistInput.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            artistInput.showDropDown()
                        }
                    }
                    artistInput.setOnClickListener {
                        artistInput.showDropDown()
                    }

                    fun launchAutoFill(fromUserAction: Boolean) {
                        val url = sourceUrlInput.text.toString().trim()
                        if (isFetching || url.isBlank() || resultRepository == null) return
                        if (!Patterns.WEB_URL.matcher(url).matches()) return
                        if (!fromUserAction && url == lastFetchedUrl) return
                        if (!fromUserAction && userEdited) return
                        isFetching = true
                        positive.isEnabled = false
                        neutral.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            val info = fetchMetadataFromUrl(resultRepository, url)
                            withContext(Dispatchers.Main) {
                                if (info != null) {
                                    selectedResult = info
                                    lastFetchedUrl = info.url.ifBlank { url }
                                    if (fromUserAction) {
                                        setTextSilently(titleInput, info.title)
                                        setTextSilently(authorInput, info.author)
                                        setTextSilently(durationInput, info.duration)
                                        if (info.url.isNotBlank()) {
                                            setTextSilently(sourceUrlInput, info.url)
                                        }
                                    } else {
                                        if (titleInput.text.toString().trim().isBlank()) {
                                            setTextSilently(titleInput, info.title)
                                        }
                                        if (authorInput.text.toString().trim().isBlank() && info.author.isNotBlank()) {
                                            setTextSilently(authorInput, info.author)
                                        }
                                        if (durationInput.text.toString().trim().isBlank() && info.duration.isNotBlank()) {
                                            setTextSilently(durationInput, info.duration)
                                        }
                                        if (info.url.isNotBlank() && sourceUrlInput.text.toString().trim().isBlank()) {
                                            setTextSilently(sourceUrlInput, info.url)
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, R.string.no_match_found, Toast.LENGTH_SHORT).show()
                                }
                                isFetching = false
                                positive.isEnabled = true
                                neutral.isEnabled = true
                            }
                        }
                    }

                    val urlWatcher = object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            if (!allowAutoFill) return
                            if (suppressAutoFill) return
                            if (userEdited) return
                            debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                            debounceRunnable = Runnable { launchAutoFill(false) }
                            debounceHandler.postDelayed(debounceRunnable!!, 100L)
                        }
                        override fun afterTextChanged(s: android.text.Editable?) = Unit
                    }
                    sourceUrlInput.addTextChangedListener(urlWatcher)
                    dialog.setOnDismissListener {
                        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                        sourceUrlInput.removeTextChangedListener(urlWatcher)
                    }

                    neutral.setOnClickListener { launchAutoFill(true) }
                    searchButton.setOnClickListener {
                        val query = normalizeInput(titleInput.text.toString())
                        if (query.isBlank()) {
                            Toast.makeText(context, R.string.video_info_required, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val repo = resultRepository ?: return@setOnClickListener
                        searchButton.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            val results = runCatching {
                                repo.search(query, resetResults = false, addToResults = false)
                            }.getOrDefault(emptyList())
                            withContext(Dispatchers.Main) {
                                searchButton.isEnabled = true
                                if (results.isEmpty()) {
                                    Toast.makeText(context, R.string.no_match_found, Toast.LENGTH_SHORT).show()
                                    return@withContext
                                }
                                showSearchResultsDialog(
                                    context = context,
                                    query = query,
                                    results = results,
                                    onSelect = { selected ->
                                        val options = arrayOf(
                                            getString(R.string.fetch_title_only),
                                            getString(R.string.update_all_info)
                                        )
                                        MaterialAlertDialogBuilder(context)
                                            .setTitle(getString(R.string.apply_search_result))
                                            .setItems(options) { _, which ->
                                                suppressAutoFill = true
                                                debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                                                if (which == 0) {
                                                    setTextSilently(titleInput, "")
                                                    val link = selected.url
                                                    if (link.isBlank()) {
                                                        Toast.makeText(context, R.string.video_source_url_optional, Toast.LENGTH_SHORT).show()
                                                        suppressAutoFill = false
                                                        return@setItems
                                                    }
                                                    lifecycleScope.launch(Dispatchers.IO) {
                                                        val info = fetchMetadataFromUrl(resultRepository, link)
                                                        withContext(Dispatchers.Main) {
                                                            if (info == null) {
                                                                Toast.makeText(context, R.string.no_match_found, Toast.LENGTH_SHORT).show()
                                                                suppressAutoFill = false
                                                                return@withContext
                                                            }
                                                            setTextSilently(titleInput, info.title)
                                                            setTextSilently(authorInput, info.author)
                                                            setTextSilently(durationInput, info.duration)
                                                            setTextSilently(sourceUrlInput, info.url)
                                                            selectedResult = info
                                                            positive.isEnabled = true
                                                            neutral.isEnabled = true
                                                            debounceHandler.postDelayed({ suppressAutoFill = false }, 300L)
                                                        }
                                                    }
                                                    return@setItems
                                                }
                                                setTextSilently(titleInput, selected.title)
                                                setTextSilently(authorInput, selected.author)
                                                setTextSilently(durationInput, selected.duration)
                                                setTextSilently(sourceUrlInput, selected.url)
                                                selectedResult = selected
                                                positive.isEnabled = true
                                                neutral.isEnabled = true
                                                debounceHandler.postDelayed({ suppressAutoFill = false }, 300L)
                                            }
                                            .show()
                                    }
                                )
                            }
                        }
                    }
                    if (initialResult != null) {
                        val selected = initialResult
                        setTextSilently(titleInput, selected.title)
                        setTextSilently(authorInput, selected.author)
                        setTextSilently(durationInput, selected.duration)
                        setTextSilently(sourceUrlInput, selected.url)
                        selectedResult = selected
                        lastFetchedUrl = selected.url
                    } else if (allowAutoSearchOnShow && allowAutoFill && resultRepository != null && defaultTitle.isNotBlank()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val results = runCatching {
                                resultRepository.search(defaultTitle, resetResults = false, addToResults = false)
                            }.getOrDefault(emptyList())
                            if (results.isEmpty()) return@launch
                            withContext(Dispatchers.Main) {
                                if (userEdited) return@withContext
                                val selected = results.first()
                                setTextSilently(titleInput, selected.title)
                                setTextSilently(authorInput, selected.author)
                                setTextSilently(durationInput, selected.duration)
                                setTextSilently(sourceUrlInput, selected.url)
                                selectedResult = selected
                            }
                        }
                    }
                    positive.setOnClickListener {
                        val title = normalizeInput(titleInput.text.toString())
                        val author = normalizeAuthors(authorInput.text.toString())
                        val artist = normalizeAuthors(artistInput.text.toString())
                        val duration = normalizeInput(durationInput.text.toString())
                        val sourceUrl = normalizeInput(sourceUrlInput.text.toString())

                        if (title.isBlank() && sourceUrl.isNotBlank() && resultRepository != null) {
                            launchAutoFill(true)
                            return@setOnClickListener
                        }

                        if (title.isBlank()) {
                            Toast.makeText(context, R.string.video_info_required, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val result = selectedResult?.takeIf { it.url == sourceUrl }
                        cont.resume(
                            ManualMetadataResult(
                                metadata = ManualMetadata(
                                    title = title,
                                    author = author,
                                    artist = artist,
                                    duration = duration,
                                    sourceUrl = sourceUrl,
                                    thumb = result?.thumb.orEmpty(),
                                    website = result?.website.orEmpty()
                                ),
                                cancelled = false
                            )
                        )
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }

            if (!allowAutoFillOnOpen) {
                showManualDialog(initialResult = null, allowAutoSearchOnShow = false, allowAutoFill = false)
            } else if (resultRepository != null && defaultTitle.isNotBlank()) {
                var manualChosen = false
                val loadingView = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (resources.displayMetrics.density * 16).toInt()
                    setPadding(padding, padding, padding, padding)
                    addView(ProgressBar(context))
                    addView(TextView(context).apply {
                        text = getString(R.string.video_info_fetching)
                        setPadding(0, padding / 2, 0, 0)
                    })
                }
                val loadingDialog = MaterialAlertDialogBuilder(context)
                    .setTitle(getString(R.string.loading))
                    .setView(loadingView)
                    .setPositiveButton(R.string.enter_manually, null)
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        cont.resume(ManualMetadataResult(null, cancelled = true))
                    }
                    .setOnCancelListener {
                        cont.resume(ManualMetadataResult(null, cancelled = true))
                    }
                    .create()
                loadingDialog.setOnShowListener {
                    val manualButton = loadingDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    manualButton.setOnClickListener {
                        manualChosen = true
                        loadingDialog.dismiss()
                        showManualDialog(initialResult = null, allowAutoSearchOnShow = false, allowAutoFill = false)
                    }
                }
                loadingDialog.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val results = runCatching {
                        resultRepository.search(defaultTitle, resetResults = false, addToResults = false)
                    }.getOrDefault(emptyList())
                    withContext(Dispatchers.Main) {
                        if (!cont.isActive) return@withContext
                        if (manualChosen) return@withContext
                        loadingDialog.dismiss()
                        val initial = results.firstOrNull()
                        showManualDialog(initialResult = initial, allowAutoSearchOnShow = false, allowAutoFill = true)
                    }
                }
            } else {
                showManualDialog(initialResult = null, allowAutoSearchOnShow = false, allowAutoFill = true)
            }
        }
    }

    private suspend fun fetchMetadataFromUrl(
        resultRepository: ResultRepository,
        url: String
    ): com.ireum.ytdl.database.models.ResultItem? {
        if (url.isBlank()) return null
        return runCatching {
            resultRepository.getResultsFromSource(
                url,
                resetResults = false,
                addToResults = false,
                singleItem = true
            ).firstOrNull()
        }.getOrNull()
    }

    private fun showSearchResultsDialog(
        context: Context,
        query: String,
        results: List<com.ireum.ytdl.database.models.ResultItem>,
        onSelect: (com.ireum.ytdl.database.models.ResultItem) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.search_results_dialog, null)
        val list = view.findViewById<RecyclerView>(R.id.search_results_list)
        list.layoutManager = LinearLayoutManager(context)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("${getString(R.string.search_results)}: $query")
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        list.adapter = SearchResultsAdapter(context, results) { item ->
            onSelect(item)
            dialog.dismiss()
        }
    }

    private fun showEditHistoryItemDialog(item: HistoryItem) {
        val view = layoutinflater.inflate(R.layout.history_item_edit_dialog, null)
        val titleInput = view.findViewById<TextInputEditText>(R.id.edit_title)
        val authorInput = view.findViewById<MultiAutoCompleteTextView>(R.id.edit_author)
        val artistInput = view.findViewById<MultiAutoCompleteTextView>(R.id.edit_artist)
        val urlInput = view.findViewById<TextInputEditText>(R.id.edit_url)
        val durationInput = view.findViewById<TextInputEditText>(R.id.edit_duration)
        val keywordsInput = view.findViewById<MultiAutoCompleteTextView>(R.id.edit_keywords)
        val thumbPreview = view.findViewById<android.widget.ImageView>(R.id.edit_thumb_preview)
        val selectThumb = view.findViewById<android.widget.Button>(R.id.edit_select_thumb_gallery)
        val captureThumb = view.findViewById<android.widget.Button>(R.id.edit_capture_thumb)
        val removeThumb = view.findViewById<android.widget.Button>(R.id.edit_remove_thumb)
        val fetchSearch = view.findViewById<android.widget.Button>(R.id.edit_fetch_search)
        val fetchLink = view.findViewById<android.widget.Button>(R.id.edit_fetch_link)

        titleInput.setText(item.title)
        val youtuberAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authorList)
        authorInput.setAdapter(youtuberAdapter)
        artistInput.setAdapter(youtuberAdapter)
        authorInput.setTokenizer(DelimiterTokenizer())
        artistInput.setTokenizer(DelimiterTokenizer())
        authorInput.setText(item.author)
        artistInput.setText(item.artist)
        urlInput.setText(item.url)
        durationInput.setText(item.duration)
        keywordsInput.setText(item.keywords)
        keywordsInput.setTokenizer(DelimiterTokenizer())
        lifecycleScope.launch(Dispatchers.IO) {
            val keywordCandidates = historyViewModel.getAll()
                .flatMap { splitKeywordsLocal(it.keywords) }
                .distinctBy { it.lowercase(Locale.getDefault()) }
                .sortedBy { it.lowercase(Locale.getDefault()) }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                keywordsInput.setAdapter(
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        keywordCandidates
                    )
                )
            }
        }

        var editedThumb = item.thumb
        var editedWebsite = item.website
        var editedCustomThumb = item.customThumb

        fun updatePreview() {
            val preview = if (editedCustomThumb.isNotBlank() && FileUtil.exists(editedCustomThumb)) {
                editedCustomThumb
            } else {
                editedThumb
            }
            if (preview.isBlank()) {
                thumbPreview.setImageDrawable(null)
                return
            }
            val resolved = if (preview.startsWith("content://") || preview.startsWith("file://")) {
                preview
            } else {
                File(preview).toURI().toString()
            }
            Picasso.get()
                .invalidate(resolved)
            Picasso.get()
                .load(resolved)
                .resize(1280, 0)
                .onlyScaleDown()
                .into(thumbPreview)
        }

        updatePreview()
        removeThumb.isVisible = editedCustomThumb.isNotBlank()

        selectThumb.setOnClickListener {
            pendingThumbItem = item
            pendingThumbCallback = { path ->
                if (editedCustomThumb.isNotBlank() && editedCustomThumb != path) {
                    deleteCustomThumb(editedCustomThumb)
                }
                editedCustomThumb = path
                removeThumb.isVisible = true
                updatePreview()
            }
            pickCustomThumbLauncher.launch("image/*")
        }

        captureThumb.setOnClickListener {
            showCustomThumbPicker(item) { saved ->
                if (saved.isNullOrBlank()) {
                    Toast.makeText(requireContext(), R.string.error_saving_thumbnail, Toast.LENGTH_SHORT).show()
                } else {
                    if (editedCustomThumb.isNotBlank() && editedCustomThumb != saved) {
                        deleteCustomThumb(editedCustomThumb)
                    }
                    editedCustomThumb = saved
                    removeThumb.isVisible = true
                    updatePreview()
                }
            }
        }

        removeThumb.setOnClickListener {
            if (editedCustomThumb.isNotBlank()) {
                deleteCustomThumb(editedCustomThumb)
            }
            editedCustomThumb = ""
            removeThumb.isVisible = false
            updatePreview()
        }

        val db = DBManager.getInstance(requireContext())
        val resultRepository = ResultRepository(db.resultDao, db.commandTemplateDao, requireContext())

        fetchSearch.setOnClickListener {
            val query = titleInput.text?.toString()?.trim().orEmpty()
            if (query.isBlank()) {
                Toast.makeText(requireContext(), R.string.video_info_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchSearch.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                val results = runCatching {
                    resultRepository.search(query, resetResults = false, addToResults = false)
                }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) {
                    fetchSearch.isEnabled = true
                    if (results.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.no_match_found, Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    showSearchResultsDialog(
                        context = requireContext(),
                        query = query,
                        results = results,
                        onSelect = { selected ->
                            val options = arrayOf(
                                getString(R.string.fetch_title_only),
                                getString(R.string.update_all_info)
                            )
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.apply_search_result))
                                .setItems(options) { _, which ->
                                    if (which == 0) {
                                        titleInput.setText("")
                                        val link = selected.url
                                        if (link.isBlank()) {
                                            Toast.makeText(requireContext(), R.string.video_source_url_optional, Toast.LENGTH_SHORT).show()
                                            return@setItems
                                        }
                                        fetchLink.isEnabled = false
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val info = fetchMetadataFromUrl(resultRepository, link)
                                            withContext(Dispatchers.Main) {
                                                fetchLink.isEnabled = true
                                                if (info == null) {
                                                    Toast.makeText(requireContext(), R.string.no_match_found, Toast.LENGTH_SHORT).show()
                                                    return@withContext
                                                }
                                                titleInput.setText(info.title)
                                                authorInput.setText(info.author)
                                                urlInput.setText(info.url)
                                                durationInput.setText(info.duration)
                                                editedThumb = info.thumb
                                                editedWebsite = info.website
                                                updatePreview()
                                            }
                                        }
                                        return@setItems
                                    }
                                    titleInput.setText(selected.title)
                                    authorInput.setText(selected.author)
                                    urlInput.setText(selected.url)
                                    durationInput.setText(selected.duration)
                                    editedThumb = selected.thumb
                                    editedWebsite = selected.website
                                    updatePreview()
                                }
                                .show()
                        }
                    )
                }
            }
        }

        fetchLink.setOnClickListener {
            val url = urlInput.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                Toast.makeText(requireContext(), R.string.video_source_url_optional, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchLink.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                val info = fetchMetadataFromUrl(resultRepository, url)
                withContext(Dispatchers.Main) {
                    fetchLink.isEnabled = true
                    if (info == null) {
                        Toast.makeText(requireContext(), R.string.no_match_found, Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    titleInput.setText(info.title)
                    authorInput.setText(info.author)
                    urlInput.setText(info.url)
                    durationInput.setText(info.duration)
                    editedThumb = info.thumb
                    editedWebsite = info.website
                    updatePreview()
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_video_info))
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    Toast.makeText(requireContext(), R.string.video_info_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val author = normalizeAuthors(authorInput.text?.toString().orEmpty())
                val artist = normalizeAuthors(artistInput.text?.toString().orEmpty())
                val url = urlInput.text?.toString()?.trim().orEmpty()
                val duration = durationInput.text?.toString()?.trim().orEmpty()
                val keywords = keywordsInput.text?.toString()?.trim().orEmpty()
                val updated = item.copy(
                    title = title,
                    author = author,
                    artist = artist,
                    url = url,
                    duration = duration,
                    durationSeconds = duration.toDurationSeconds(),
                    keywords = keywords,
                    thumb = editedThumb,
                    customThumb = editedCustomThumb,
                    website = editedWebsite
                )
                val updateJob = historyViewModel.update(updated)
                lifecycleScope.launch {
                    updateJob.join()
                    historyAdapter.refresh()
                }
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            pendingThumbItem = null
            pendingThumbCallback = null
        }

        dialog.show()
    }

    private fun showAddToYoutuberGroupDialog(authors: List<String>) {
        if (authors.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val groups = db.youtuberGroupDao.getGroups()
            val relations = db.youtuberGroupDao.getAllRelations()
            val currentParentGroupId =
                if (historyViewModel.isYoutuberSelectionMode.value && historyViewModel.youtuberGroupFilter.value >= 0L) {
                    historyViewModel.youtuberGroupFilter.value
                } else {
                    null
                }
            withContext(Dispatchers.Main) {
                if (groups.isEmpty()) {
                    showCreateYoutuberGroupDialog(
                        onCreated = { groupId -> addAuthorsToGroup(groupId, authors) },
                        parentGroupId = currentParentGroupId
                    )
                    return@withContext
                }
                val groupRows = buildYoutuberGroupRows(groups, relations)
                val names = ArrayList<String>()
                names.add(getString(R.string.new_group))
                names.addAll(groupRows.map { it.second })
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.add_to_group))
                    .setItems(names.toTypedArray()) { _, which ->
                        if (which == 0) {
                            showCreateYoutuberGroupDialog(
                                onCreated = { groupId -> addAuthorsToGroup(groupId, authors) },
                                parentGroupId = currentParentGroupId
                            )
                        } else {
                            val group = groupRows[which - 1].first
                            addAuthorsToGroup(group.id, authors)
                        }
                    }
                    .show()
            }
        }
    }

    private fun buildYoutuberGroupRows(
        groups: List<com.ireum.ytdl.database.models.YoutuberGroup>,
        relations: List<com.ireum.ytdl.database.models.YoutuberGroupRelation>
    ): List<Pair<com.ireum.ytdl.database.models.YoutuberGroup, String>> {
        if (groups.isEmpty()) return emptyList()
        val groupById = groups.associateBy { it.id }
        val childrenByParent = relations.groupBy { it.parentGroupId }
            .mapValues { entry -> entry.value.map { it.childGroupId }.distinct() }
        val childIds = relations.map { it.childGroupId }.toSet()
        val roots = groups.filter { !childIds.contains(it.id) }.sortedBy { it.name.lowercase(Locale.getDefault()) }
        val out = mutableListOf<Pair<com.ireum.ytdl.database.models.YoutuberGroup, String>>()
        val visited = linkedSetOf<Long>()

        fun appendNode(groupId: Long, depth: Int) {
            val group = groupById[groupId] ?: return
            if (!visited.add(group.id)) return
            val label = if (depth > 0) "${"ㄴ".repeat(depth)} ${group.name}" else group.name
            out.add(group to label)
            childrenByParent[group.id].orEmpty()
                .sortedBy { id -> groupById[id]?.name?.lowercase(Locale.getDefault()).orEmpty() }
                .forEach { childId -> appendNode(childId, depth + 1) }
        }

        roots.forEach { appendNode(it.id, 0) }
        groups.filter { !visited.contains(it.id) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .forEach { appendNode(it.id, 0) }
        return out
    }

    private fun removeAuthorsFromCurrentYoutuberGroup(groupId: Long, authors: List<String>) {
        if (groupId < 0L || authors.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            db.youtuberGroupDao.deleteMembersByGroupAndAuthors(groupId, authors)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), getString(R.string.ok), Toast.LENGTH_SHORT).show()
                youtuberActionMode?.finish()
            }
        }
    }

    private fun showEditYoutuberGroupsDialog(author: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val groups = db.youtuberGroupDao.getGroups()
            if (groups.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.no_groups), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val groupIds = db.youtuberGroupDao.getGroupIdsForAuthor(author).toSet()
            withContext(Dispatchers.Main) {
                val checked = BooleanArray(groups.size) { index -> groupIds.contains(groups[index].id) }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.edit_group))
                    .setMultiChoiceItems(groups.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        val selectedIds = groups.filterIndexed { index, _ -> checked[index] }.map { it.id }
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (selectedIds.isEmpty()) {
                                db.youtuberGroupDao.deleteMembersForAuthor(author)
                            } else {
                                db.youtuberGroupDao.deleteMembersForAuthorNotIn(author, selectedIds)
                                val members = selectedIds.map { id -> com.ireum.ytdl.database.models.YoutuberGroupMember(id, author) }
                                db.youtuberGroupDao.insertMembers(members)
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun showEditYoutuberInfoDialog(author: String) {
        val db = DBManager.getInstance(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = db.youtuberMetaDao.getByAuthor(author)
            withContext(Dispatchers.Main) {
                val context = requireContext()
                val padding = (resources.displayMetrics.density * 12).toInt()
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)
                }
                val channelUrlInput = EditText(context).apply {
                    hint = getString(R.string.channel_url)
                    setText(meta?.channelUrl.orEmpty())
                }
                val iconUrlInput = EditText(context).apply {
                    hint = getString(R.string.channel_icon_url)
                    setText(meta?.iconUrl.orEmpty())
                }
                val fetchButton = android.widget.Button(context).apply {
                    text = getString(R.string.fetch_channel_info)
                }
                container.addView(channelUrlInput)
                container.addView(iconUrlInput)
                container.addView(fetchButton)

                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(getString(R.string.edit_youtuber_info))
                    .setView(container)
                    .setPositiveButton(R.string.ok, null)
                    .setNegativeButton(R.string.cancel, null)
                    .create()

                dialog.setOnShowListener {
                    val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    fetchButton.setOnClickListener {
                        fetchButton.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                            val language = resolveLanguage(prefs)
                            val region = resolveRegion(prefs, language)
                            val api = YoutubeApiUtil(context)
                            val channels = api.searchChannelsByName(author, language, region, 5)
                            withContext(Dispatchers.Main) {
                                fetchButton.isEnabled = true
                                if (channels.isEmpty()) {
                                    Toast.makeText(context, R.string.no_match_found, Toast.LENGTH_SHORT).show()
                                    return@withContext
                                }
                                val labels = channels.map { it.title.ifBlank { it.channelId } }.toTypedArray()
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(getString(R.string.select_channel))
                                    .setItems(labels) { _, which ->
                                        val selected = channels[which]
                                        channelUrlInput.setText(selected.channelUrl)
                                        iconUrlInput.setText(selected.iconUrl)
                                    }
                                    .show()
                            }
                        }
                    }
                    positive.setOnClickListener {
                        val channelUrl = channelUrlInput.text.toString().trim()
                        val iconUrl = iconUrlInput.text.toString().trim()
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (channelUrl.isBlank() && iconUrl.isBlank()) {
                                db.youtuberMetaDao.deleteByAuthor(author)
                            } else {
                                db.youtuberMetaDao.upsert(
                                    com.ireum.ytdl.database.models.YoutuberMeta(
                                        author = author,
                                        channelUrl = channelUrl,
                                        iconUrl = iconUrl
                                    )
                                )
                            }
                        }
                        youtuberActionMode?.finish()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
        }
    }

    private fun resolveLanguage(prefs: SharedPreferences): String {
        val pref = prefs.getString("app_language", "") ?: ""
        return if (pref.isBlank() || pref == "system") {
            Locale.getDefault().language.ifBlank { "en" }
        } else {
            pref
        }
    }

    private fun resolveRegion(prefs: SharedPreferences, language: String): String {
        val pref = prefs.getString("locale", "") ?: ""
        if (pref.isNotBlank()) return pref
        if (language == "ko") return "KR"
        return Locale.getDefault().country.ifBlank { "US" }
    }

    private fun showAddArtistDialog(selectedIds: List<Long>) {
        val candidates = authorList
        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_match_found, Toast.LENGTH_SHORT).show()
            return
        }
        val input = MultiAutoCompleteTextView(requireContext()).apply {
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, candidates))
            setTokenizer(DelimiterTokenizer())
            hint = getString(R.string.add_artist)
        }
        val container = TextInputLayout(requireContext()).apply {
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_artist))
            .setView(container)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val normalized = normalizeAuthors(input.text?.toString().orEmpty())
                val newArtists = splitAuthorsLocal(normalized)
                if (newArtists.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.video_info_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val items = selectedIds.mapNotNull { id ->
                        runCatching { historyViewModel.getByID(id) }.getOrNull()
                    }
                    if (items.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.no_match_found, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val updateJobs = mutableListOf<Job>()
                    items.forEach { item ->
                        val existing = splitAuthorsLocal(normalizeAuthors(item.artist))
                        val merged = (existing + newArtists).distinct()
                        val updated = item.copy(artist = merged.joinToString(", "))
                        updateJobs.add(historyViewModel.update(updated))
                    }
                    updateJobs.joinAll()
                    withContext(Dispatchers.Main) {
                        historyAdapter.refresh()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAddKeywordsDialog(selectedIds: List<Long>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val repository = HistoryRepository(db.historyDao, db.playlistDao)
            val allItems = historyViewModel.getAll()
            val keywordInfos = repository.getKeywordsWithInfoForHistoryIds(allItems.map { it.id })
            val keywordRows = buildKeywordRows(keywordInfos)
            val keywordCandidates = keywordRows.map { it.first }
            withContext(Dispatchers.Main) {
                val context = requireContext()
                val input = MultiAutoCompleteTextView(context).apply {
                    setAdapter(
                        ArrayAdapter(
                            context,
                            android.R.layout.simple_dropdown_item_1line,
                            keywordCandidates
                        )
                    )
                    setTokenizer(DelimiterTokenizer())
                    hint = getString(R.string.add_keywords)
                }
                val inputLayout = TextInputLayout(context).apply {
                    addView(input)
                }
                val labels = keywordRows.map { it.second }
                val checked = BooleanArray(labels.size)
                val listView = ListView(context).apply {
                    choiceMode = ListView.CHOICE_MODE_MULTIPLE
                    dividerHeight = 0
                    adapter = ArrayAdapter(
                        context,
                        android.R.layout.simple_list_item_multiple_choice,
                        labels
                    )
                    for (index in checked.indices) {
                        setItemChecked(index, checked[index])
                    }
                }
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val spacing = (resources.displayMetrics.density * 8).toInt()
                    setPadding(spacing, spacing, spacing, spacing)
                    addView(inputLayout)
                    addView(
                        listView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (resources.displayMetrics.heightPixels * 0.45f).toInt()
                        )
                    )
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.add_keywords))
                    .setView(container)
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        val selectedFromList = checked.indices
                            .filter { listView.isItemChecked(it) }
                            .map { keywordRows[it].first }
                        val typedKeywords = splitKeywordsLocal(input.text?.toString().orEmpty())
                        val newKeywords = (selectedFromList + typedKeywords)
                            .fold(mutableListOf<String>()) { acc, keyword ->
                                if (acc.none { it.equals(keyword, ignoreCase = true) }) acc.add(keyword)
                                acc
                            }
                        if (newKeywords.isEmpty()) {
                            Toast.makeText(requireContext(), R.string.video_info_required, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val items = selectedIds.mapNotNull { id ->
                                runCatching { historyViewModel.getByID(id) }.getOrNull()
                            }
                            if (items.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(requireContext(), R.string.no_match_found, Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                            val updateJobs = mutableListOf<Job>()
                            items.forEach { item ->
                                val existing = splitKeywordsLocal(item.keywords)
                                val seen = HashSet<String>()
                                val merged = mutableListOf<String>()
                                (existing + newKeywords).forEach { keyword ->
                                    val key = keyword.lowercase(Locale.getDefault())
                                    if (seen.add(key)) {
                                        merged.add(keyword)
                                    }
                                }
                                val updated = item.copy(keywords = merged.joinToString(", "))
                                updateJobs.add(historyViewModel.update(updated))
                            }
                            updateJobs.joinAll()
                            withContext(Dispatchers.Main) {
                                historyAdapter.refresh()
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun buildKeywordRows(
        infos: List<com.ireum.ytdl.database.models.KeywordInfo>
    ): List<Pair<String, String>> {
        if (infos.isEmpty()) return emptyList()
        val byKeyword = infos.associateBy { it.keyword }
        val childrenByParent = infos
            .flatMap { info -> info.parentKeywords.map { parent -> parent to info.keyword } }
            .groupBy({ it.first }, { it.second })
            .mapValues { entry ->
                entry.value.distinct().sortedBy { it.lowercase(Locale.getDefault()) }
            }
        val childSet = childrenByParent.values.flatten().toSet()
        val roots = infos
            .map { it.keyword }
            .filter { !childSet.contains(it) }
            .sortedBy { it.lowercase(Locale.getDefault()) }

        val out = mutableListOf<Pair<String, String>>()
        val visited = linkedSetOf<String>()
        fun appendKeyword(keyword: String, depth: Int) {
            if (!visited.add(keyword)) return
            if (byKeyword[keyword] == null) return
            val label = if (depth > 0) "${"ㄴ".repeat(depth)} $keyword" else keyword
            out.add(keyword to label)
            childrenByParent[keyword].orEmpty().forEach { child -> appendKeyword(child, depth + 1) }
        }
        roots.forEach { appendKeyword(it, 0) }
        infos.map { it.keyword }
            .filter { !visited.contains(it) }
            .sortedBy { it.lowercase(Locale.getDefault()) }
            .forEach { appendKeyword(it, 0) }
        return out
    }

    private fun splitAuthorsLocal(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        return parseAuthorsWithQuotes(trimmed)
            .map { it.first }
            .filter { it.isNotBlank() }
    }

    private fun splitKeywordsLocal(raw: String): List<String> {
        return raw
            .split(',', '\n')
            .map { it.trim() }
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
                ',', '/', '|', '&' -> {
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

    private suspend fun openLocalMatchDialog(
        resultRepository: ResultRepository?,
        awaitResult: Boolean
    ): List<LocalMatchSelection>? {
        if (!awaitResult && localMatchDialog != null) return null
        if (awaitResult) {
            localMatchDeferred?.let { return it.await() }
        }
        val deferred = if (awaitResult) CompletableDeferred<List<LocalMatchSelection>?>() else null
        localMatchDeferred = deferred
        val context = requireContext()
        if (localMatchDeferredCandidates.isNotEmpty()) {
            localMatchCandidates.addAll(localMatchDeferredCandidates)
            localMatchDeferredCandidates.clear()
        }
        if (localMatchCandidates.isEmpty()) {
            return null
        }
        val selections = localMatchSelections?.takeIf { it.size == localMatchCandidates.size }
            ?: localMatchCandidates.map { candidate ->
                LocalMatchSelection(candidate, LocalMatchChoice.UNSET, LocalMatchStatus.LOADING)
            }.toMutableList().also { localMatchSelections = it }
        val view = LayoutInflater.from(context).inflate(R.layout.local_match_list_dialog, null)
        val list = view.findViewById<RecyclerView>(R.id.local_match_list)
        val refreshView = view.findViewById<TextView>(R.id.local_match_refresh)
        list.layoutManager = LinearLayoutManager(context)
        val adapter = LocalMatchAdapter(selections) { selection, position ->
            lifecycleScope.launch(Dispatchers.Main) {
                val candidate = selection.candidate
                val currentManual = selection.manualMetadata
                val match = candidate.match
                val manualResult = promptManualMetadata(
                    defaultTitle = currentManual?.title
                        ?: match?.item?.title?.ifBlank { candidate.title }
                        ?: candidate.title,
                    durationSeconds = candidate.durationSeconds,
                    defaultAuthor = currentManual?.author ?: match?.item?.author.orEmpty(),
                    defaultDuration = currentManual?.duration ?: match?.item?.duration.orEmpty(),
                    resultRepository = resultRepository,
                    allowAutoFillOnOpen = false
                )
                val manual = manualResult.metadata ?: return@launch
                selection.manualMetadata = manual
                selection.choice = LocalMatchChoice.USE_MATCH
                val notifyPosition = if (position in selections.indices) position else selections.indexOf(selection)
                if (notifyPosition >= 0) {
                    localMatchAdapter?.notifyItemChanged(notifyPosition)
                } else {
                    localMatchAdapter?.notifyDataSetChanged()
                }
            }
        }
        localMatchAdapter = adapter
        list.adapter = adapter
        var searching = true

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.local_match_title))
            .setView(view)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                if (awaitResult) {
                    searching = false
                    localMatchSearchJob?.cancel()
                    deferred?.complete(selections)
                } else {
                    localMatchConfirmCallback?.invoke(selections.toList())
                }
            }
            .setOnCancelListener {
                if (awaitResult) {
                    searching = false
                    localMatchSearchJob?.cancel()
                    deferred?.complete(null)
                }
            }
            .create()

        localMatchDialog = dialog
        localMatchRefreshView = refreshView
        dialog.setOnShowListener {
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val decided = selections.filter { it.choice != LocalMatchChoice.UNSET }
                val undecided = selections.filter { it.choice == LocalMatchChoice.UNSET }
                if (localMatchAddFinished && undecided.isNotEmpty()) {
                    localMatchSkipUnset?.invoke(undecided)
                }
                if (awaitResult) {
                    searching = false
                    localMatchSearchJob?.cancel()
                    deferred?.complete(decided)
                } else {
                    if (decided.isNotEmpty()) {
                        localMatchConfirmCallback?.invoke(decided)
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
        pendingApplyReady = {
            lifecycleScope.launch(Dispatchers.Main) {
                if (localMatchDialog == null) {
                    if (localMatchCandidates.isEmpty() && localMatchDeferredCandidates.isEmpty()) {
                        return@launch
                    }
                    openLocalMatchDialog(localMatchResultRepository, awaitResult = false)
                } else {
                    adapter.notifyDataSetChanged()
                }
            }
        }
        dialog.setOnDismissListener {
            localMatchDialog = null
            localMatchRefreshView = null
            localMatchRestartSearch = null
        }

        if (resultRepository == null) {
            selections.forEach { selection ->
                if (selection.status == LocalMatchStatus.LOADING) {
                    selection.status = LocalMatchStatus.NONE
                    selection.choice = LocalMatchChoice.MANUAL
                }
            }
            adapter.notifyDataSetChanged()
            return deferred?.await()
        }

        fun startSearch() {
            localMatchSearchJob?.cancel()
            localMatchSearchJob = lifecycleScope.launch(Dispatchers.IO) {
                var index = 0
                while (searching && isActive) {
                    if (index >= selections.size) break
                    val selection = selections[index]
                    if (selection.status != LocalMatchStatus.LOADING) {
                        index += 1
                        continue
                    }
                    val candidate = selection.candidate
                    val match = withTimeoutOrNull(2000L) {
                        runCatching { findYoutubeMatch(resultRepository, candidate.title, candidate.durationSeconds) }.getOrNull()
                    }
                    if (!searching || !isActive) break
                    withContext(Dispatchers.Main) {
                        candidate.match = match
                        if (match == null) {
                            selection.status = LocalMatchStatus.NONE
                            selection.choice = LocalMatchChoice.MANUAL
                        } else {
                            selection.status = LocalMatchStatus.FOUND
                            if (match.exactTitleMatch && selection.choice != LocalMatchChoice.MANUAL && selection.manualMetadata == null) {
                                selection.choice = LocalMatchChoice.USE_MATCH
                                localMatchConfirmCallback?.invoke(listOf(selection))
                            }
                        }
                        adapter.notifyItemChanged(index)
                    }
                    index += 1
                }
            }
        }
        localMatchRestartSearch = { startSearch() }

        refreshView.visibility = if (localMatchDeferredCandidates.isNotEmpty()) View.VISIBLE else View.GONE
        refreshView.setOnClickListener {
            if (localMatchDeferredCandidates.isEmpty()) return@setOnClickListener
            val startIndex = selections.size
            localMatchCandidates.addAll(localMatchDeferredCandidates)
            localMatchDeferredCandidates.forEach { candidate ->
                selections.add(LocalMatchSelection(candidate, LocalMatchChoice.UNSET, LocalMatchStatus.LOADING))
            }
            localMatchDeferredCandidates.clear()
            refreshView.visibility = View.GONE
            adapter.notifyItemRangeInserted(startIndex, selections.size - startIndex)
            startSearch()
        }

        startSearch()

        return deferred?.await()
    }

    private class LocalMatchAdapter(
        private val items: List<LocalMatchSelection>,
        private val onEditClick: (LocalMatchSelection, Int) -> Unit
    ) : RecyclerView.Adapter<LocalMatchAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.local_match_item, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onEditClick)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.local_match_title)
            private val info: TextView = itemView.findViewById(R.id.local_match_info)
            private val result: TextView = itemView.findViewById(R.id.local_match_result)
            private val choices: android.widget.RadioGroup = itemView.findViewById(R.id.local_match_choice)
            private val unset: android.widget.RadioButton = itemView.findViewById(R.id.local_match_unset)
            private val keep: android.widget.RadioButton = itemView.findViewById(R.id.local_match_keep)
            private val editButton: android.widget.Button = itemView.findViewById(R.id.local_match_edit_button)

            fun bind(selection: LocalMatchSelection, onEditClick: (LocalMatchSelection, Int) -> Unit) {
                val candidate = selection.candidate
                title.text = candidate.title
                val durationText = if (candidate.durationSeconds > 0) candidate.durationSeconds.toStringDuration(Locale.US) else ""
                val sizeText = if (candidate.size > 0) FileUtil.convertFileSize(candidate.size) else ""
                info.text = listOf(durationText, sizeText).filter { it.isNotBlank() }.joinToString(" ")

                choices.setOnCheckedChangeListener(null)
                when (selection.status) {
                    LocalMatchStatus.LOADING -> {
                        result.text = itemView.context.getString(R.string.local_match_searching)
                        keep.isEnabled = false
                        if (selection.choice == LocalMatchChoice.USE_MATCH && selection.manualMetadata == null) {
                            selection.choice = LocalMatchChoice.UNSET
                        }
                    }
                    LocalMatchStatus.NONE -> {
                        val manual = selection.manualMetadata
                        result.text = if (manual != null) {
                            "${manual.title} - ${manual.author}"
                        } else {
                            itemView.context.getString(R.string.local_match_no_match)
                        }
                        keep.isEnabled = manual != null
                        if (manual == null && selection.choice == LocalMatchChoice.UNSET) {
                            selection.choice = LocalMatchChoice.MANUAL
                        }
                    }
                    LocalMatchStatus.FOUND -> {
                        val match = candidate.match
                        val manual = selection.manualMetadata
                        result.text = when {
                            manual != null -> "${manual.title} - ${manual.author}"
                            match != null -> "${match.item.title} - ${match.item.author}"
                            else -> itemView.context.getString(R.string.local_match_no_match)
                        }
                        keep.isEnabled = match != null || manual != null
                    }
                }
                when (selection.choice) {
                    LocalMatchChoice.UNSET -> unset.isChecked = true
                    LocalMatchChoice.USE_MATCH -> keep.isChecked = true
                    LocalMatchChoice.MANUAL -> choices.clearCheck()
                }
                editButton.setOnClickListener {
                    onEditClick(selection, bindingAdapterPosition)
                }

                choices.setOnCheckedChangeListener { _, checkedId ->
                    selection.choice = if (checkedId == R.id.local_match_keep && keep.isEnabled) {
                        LocalMatchChoice.USE_MATCH
                    } else if (checkedId == R.id.local_match_unset) {
                        LocalMatchChoice.UNSET
                    } else {
                        selection.choice
                    }
                }
            }
        }
    }

    private fun showCreateYoutuberGroupDialog(onCreated: (Long) -> Unit, parentGroupId: Long? = null) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.group_name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.new_group))
            .setView(input)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = DBManager.getInstance(requireContext())
                    val existing = db.youtuberGroupDao.getGroupByName(name)
                    val groupId = existing?.id ?: db.youtuberGroupDao.insertGroup(
                        com.ireum.ytdl.database.models.YoutuberGroup(name = name)
                    )
                    if (groupId > 0L && parentGroupId != null && parentGroupId > 0L && parentGroupId != groupId) {
                        db.youtuberGroupDao.insertRelations(
                            listOf(
                                com.ireum.ytdl.database.models.YoutuberGroupRelation(
                                    parentGroupId = parentGroupId,
                                    childGroupId = groupId
                                )
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (groupId > 0) {
                            onCreated(groupId)
                        } else if (existing != null) {
                            onCreated(existing.id)
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun addAuthorsToGroup(groupId: Long, authors: List<String>) {
        if (groupId <= 0L || authors.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val members = authors.map { com.ireum.ytdl.database.models.YoutuberGroupMember(groupId, it) }
            db.youtuberGroupDao.insertMembers(members)
            withContext(Dispatchers.Main) {
                youtuberActionMode?.finish()
            }
        }
    }

    private fun showCustomThumbPicker(
        item: HistoryItem,
        onSaved: (String?) -> Unit
    ) {
        val path = item.downloadPath.firstOrNull { FileUtil.exists(it) }
            ?: item.downloadPath.firstOrNull()
            ?: return onSaved(null)
        if (!canReadPath(path)) {
            (activity as? com.ireum.ytdl.ui.BaseActivity)?.askPermissions()
            Toast.makeText(requireContext(), R.string.request_permission_desc, Toast.LENGTH_SHORT).show()
            return onSaved(null)
        }
        val durationMs = getDurationMs(path)
        val maxSeconds = (durationMs / 1000L).coerceAtLeast(1L).toInt()
        val context = requireContext()
        val padding = (resources.displayMetrics.density * 12).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val preview = android.widget.ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 180).toInt()
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        val timeLabel = TextView(context).apply {
            setPadding(0, padding / 2, 0, 0)
        }
        val seekBar = SeekBar(context).apply {
            max = maxSeconds
            progress = 1
        }
        container.addView(preview)
        container.addView(timeLabel)
        container.addView(seekBar)

        var lastBitmap: Bitmap? = null
        var loadJob: Job? = null
        val debounceHandler = Handler(Looper.getMainLooper())
        var debounceRunnable: Runnable? = null

        fun updateLabel(sec: Int) {
            val clamped = sec.coerceIn(0, maxSeconds)
            timeLabel.text = getString(R.string.thumbnail_time_label, clamped)
        }

        fun loadFrame(sec: Int) {
            loadJob?.cancel()
            loadJob = lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = captureFrameBitmapAt(path, sec * 1000L)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        lastBitmap = bitmap
                        preview.setImageBitmap(bitmap)
                    }
                }
            }
        }

        updateLabel(1)
        loadFrame(1)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabel(progress)
                debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                debounceRunnable = Runnable { loadFrame(progress) }
                debounceHandler.postDelayed(debounceRunnable!!, 120L)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.pick_thumbnail_frame))
            .setView(container)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val bitmap = lastBitmap
                lifecycleScope.launch(Dispatchers.IO) {
                    val saved = if (bitmap != null) saveCustomThumbFromBitmap(item, bitmap) else null
                    withContext(Dispatchers.Main) {
                        onSaved(saved)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
            loadJob?.cancel()
        }
        dialog.show()
    }

    private fun getDurationMs(path: String): Long {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever()
            if (path.startsWith("content://") || path.startsWith("file://")) {
                retriever?.setDataSource(requireContext(), Uri.parse(path))
            } else {
                retriever?.setDataSource(path)
            }
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L).also {
            runCatching { retriever?.release() }
        }
    }

    private fun canReadPath(path: String): Boolean {
        return runCatching {
            if (path.startsWith("content://") || path.startsWith("file://")) {
                val uri = Uri.parse(path)
                val pfd: ParcelFileDescriptor? = requireContext().contentResolver.openFileDescriptor(uri, "r")
                pfd?.close()
                pfd != null
            } else {
                File(path).canRead()
            }
        }.getOrDefault(false)
    }

    private fun captureFrameBitmapAt(path: String, timeMs: Long): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever()
            if (path.startsWith("content://") || path.startsWith("file://")) {
                retriever?.setDataSource(requireContext(), Uri.parse(path))
            } else {
                retriever?.setDataSource(path)
            }
            val timeUs = (timeMs.coerceAtLeast(0L) * 1000L)
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever?.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    1280,
                    720
                )
            } else {
                retriever?.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            frame?.let { scaleDownBitmap(it, 1280) }
        }.getOrNull().also {
            runCatching { retriever?.release() }
        }
    }

    private fun scaleDownBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
        val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    private fun saveCustomThumbFromUri(item: HistoryItem, uri: Uri): String? {
        val stream = requireContext().contentResolver.openInputStream(uri) ?: return null
        val bitmap = stream.use { BitmapFactory.decodeStream(it) } ?: return null
        return saveCustomThumbFromBitmap(item, bitmap)
    }

    private fun saveCustomThumbFromBitmap(item: HistoryItem, bitmap: Bitmap): String? {
        val dir = resolveCustomThumbDirectory(item) ?: return null
        if (!dir.exists()) dir.mkdirs()
        val baseName = resolveCustomThumbBaseName(item)
        val file = File(dir, "${baseName}_custom_thumb.jpg")
        var out: OutputStream? = null
        return runCatching {
            out = FileOutputStream(file)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                null
            } else {
                file.absolutePath
            }
        }.getOrNull().also {
            runCatching { out?.close() }
        }
    }

    private fun resolveCustomThumbDirectory(item: HistoryItem): File? {
        val path = item.downloadPath.firstOrNull { FileUtil.exists(it) }
            ?: item.downloadPath.firstOrNull()
            ?: return null
        return when {
            path.startsWith("file://") -> {
                val filePath = Uri.parse(path).path ?: return null
                File(filePath).parentFile
            }
            path.startsWith("content://") -> {
                val fallback = requireContext().getExternalFilesDir(null) ?: requireContext().cacheDir
                File(fallback, "custom_thumbs")
            }
            else -> File(path).parentFile
        }
    }

    private fun resolveCustomThumbBaseName(item: HistoryItem): String {
        val path = item.downloadPath.firstOrNull { FileUtil.exists(it) }
            ?: item.downloadPath.firstOrNull()
            ?: return sanitizeLocalFileName(item.title.ifBlank { "video" })
        return when {
            path.startsWith("file://") -> {
                val filePath = Uri.parse(path).path ?: return sanitizeLocalFileName(item.title.ifBlank { "video" })
                File(filePath).nameWithoutExtension.ifBlank { sanitizeLocalFileName(item.title.ifBlank { "video" }) }
            }
            path.startsWith("content://") -> {
                val doc = documentFileForUri(Uri.parse(path))
                doc?.name?.substringBeforeLast('.')?.ifBlank { sanitizeLocalFileName(item.title.ifBlank { "video" }) }
                    ?: sanitizeLocalFileName(item.title.ifBlank { "video" })
            }
            else -> File(path).nameWithoutExtension.ifBlank { sanitizeLocalFileName(item.title.ifBlank { "video" }) }
        }
    }

    private fun deleteCustomThumb(path: String) {
        if (path.isBlank()) return
        runCatching { FileUtil.deleteFile(path) }
    }

    private class SearchResultsAdapter(
        private val context: Context,
        private val items: List<com.ireum.ytdl.database.models.ResultItem>,
        private val onSelect: (com.ireum.ytdl.database.models.ResultItem) -> Unit
    ) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {
        private val hideThumb = PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: android.widget.ImageView = view.findViewById(R.id.search_result_thumb)
            val title: TextView = view.findViewById(R.id.search_result_title)
            val meta: TextView = view.findViewById(R.id.search_result_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.search_result_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            val author = item.author.ifBlank { "-" }
            val duration = item.duration.ifBlank { "-" }
            holder.meta.text = "$author ? $duration"
            holder.thumb.loadThumbnail(hideThumb, item.thumb)
            holder.itemView.setOnClickListener {
                onSelect(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private suspend fun confirmYoutubeMatch(
        localTitle: String,
        durationSeconds: Int,
        match: YoutubeMatch
    ): MatchDecision {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val context = requireContext()
            val padding = (resources.displayMetrics.density * 12).toInt()
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
            val localInfo = TextView(context).apply {
                text = buildString {
                    append(getString(R.string.confirm_youtube_match_local))
                    append("\n")
                    append(localTitle)
                    if (durationSeconds > 0) {
                        append(" ? ")
                        append(durationSeconds.toStringDuration(Locale.US))
                    }
                }
            }
            val matchInfo = TextView(context).apply {
                val durationText = match.item.duration.ifBlank { "" }
                text = buildString {
                    append(getString(R.string.confirm_youtube_match_candidate))
                    append("\n")
                    append(match.item.title)
                    if (match.item.author.isNotBlank()) {
                        append("\n")
                        append(match.item.author)
                    }
                    if (durationText.isNotBlank()) {
                        append(" ? ")
                        append(durationText)
                    }
                }
            }
            container.addView(localInfo)
            container.addView(matchInfo)

            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.confirm_youtube_match_title))
                .setView(container)
                .setPositiveButton(R.string.use_match) { _, _ ->
                    cont.resume(MatchDecision.Use)
                }
                .setNeutralButton(R.string.edit_info) { _, _ ->
                    cont.resume(MatchDecision.Edit)
                }
                .setNegativeButton(R.string.skip) { _, _ ->
                    cont.resume(MatchDecision.Skip)
                }
                .setOnCancelListener {
                    cont.resume(MatchDecision.Skip)
                }
                .show()
        }
    }

    private enum class MatchDecision { Use, Edit, Skip }

    private fun loadHiddenStateFromPrefs() {
        hiddenYoutubers = sharedPreferences.getStringSet(prefHiddenYoutubersKey, emptySet())
            ?.toMutableSet()
            ?: linkedSetOf()
        hiddenYoutuberGroups = sharedPreferences.getStringSet(prefHiddenYoutuberGroupsKey, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toMutableSet()
            ?: linkedSetOf()
        visibleChildYoutuberGroups = sharedPreferences.getStringSet(prefVisibleChildYoutuberGroupsKey, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toMutableSet()
            ?: linkedSetOf()
        visibleChildYoutubers = sharedPreferences.getStringSet(prefVisibleChildYoutubersKey, emptySet())
            ?.toMutableSet()
            ?: linkedSetOf()
        visibleChildKeywords = sharedPreferences.getStringSet(prefVisibleChildKeywordsKey, emptySet())
            ?.toMutableSet()
            ?: linkedSetOf()
    }

    private fun persistHiddenStateToPrefs() {
        sharedPreferences.edit()
            .putStringSet(prefHiddenYoutubersKey, hiddenYoutubers.toSet())
            .putStringSet(prefHiddenYoutuberGroupsKey, hiddenYoutuberGroups.map { it.toString() }.toSet())
            .putStringSet(prefVisibleChildYoutuberGroupsKey, visibleChildYoutuberGroups.map { it.toString() }.toSet())
            .putStringSet(prefVisibleChildYoutubersKey, visibleChildYoutubers.toSet())
            .putStringSet(prefVisibleChildKeywordsKey, visibleChildKeywords.toSet())
            .apply()
    }

    private fun showFiltersDialog() {
        val filterSheet = BottomSheetDialog(requireContext())
        filterSheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        filterSheet.setContentView(R.layout.history_other_filters_sheet)

        val notDeleted = filterSheet.findViewById<TextView>(R.id.not_deleted)!!
        val deleted = filterSheet.findViewById<TextView>(R.id.deleted)!!
        val missingThumbnail = filterSheet.findViewById<TextView>(R.id.missing_thumbnail)!!
        val customThumbnailOnly = filterSheet.findViewById<TextView>(R.id.custom_thumbnail_only)!!
        val hardSubDoneOnly = filterSheet.findViewById<TextView>(R.id.hardsub_done_only)!!
        updateStatusIcons(notDeleted, deleted, missingThumbnail, customThumbnailOnly, hardSubDoneOnly, historyViewModel.statusFilter.value)

        notDeleted.setOnClickListener {
            val newStatus = cycleStatusOnNotDeleted(historyViewModel.statusFilter.value)
            historyViewModel.setStatusFilter(newStatus)
            updateStatusIcons(notDeleted, deleted, missingThumbnail, customThumbnailOnly, hardSubDoneOnly, newStatus)
        }
        deleted.setOnClickListener {
            val newStatus = cycleStatusOnDeleted(historyViewModel.statusFilter.value)
            historyViewModel.setStatusFilter(newStatus)
            updateStatusIcons(notDeleted, deleted, missingThumbnail, customThumbnailOnly, hardSubDoneOnly, newStatus)
        }
        missingThumbnail.setOnClickListener {
            val newStatus = cycleStatusOnMissingThumbnail(historyViewModel.statusFilter.value)
            historyViewModel.setStatusFilter(newStatus)
            updateStatusIcons(notDeleted, deleted, missingThumbnail, customThumbnailOnly, hardSubDoneOnly, newStatus)
        }
        customThumbnailOnly.setOnClickListener {
            val newStatus = cycleStatusOnCustomThumbnail(historyViewModel.statusFilter.value)
            historyViewModel.setStatusFilter(newStatus)
            updateStatusIcons(notDeleted, deleted, missingThumbnail, customThumbnailOnly, hardSubDoneOnly, newStatus)
        }
        hardSubDoneOnly.setOnClickListener {
            val newStatus = cycleStatusOnHardSubDone(historyViewModel.statusFilter.value)
            historyViewModel.setStatusFilter(newStatus)
            updateStatusIcons(notDeleted, deleted, missingThumbnail, customThumbnailOnly, hardSubDoneOnly, newStatus)
        }

        val includeChildCategoryVideosCheck = filterSheet.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.includeChildCategoryVideosCheck)
        val showHiddenOnlyCheck = filterSheet.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.showHiddenOnlyCheck)
        includeChildCategoryVideosCheck?.isChecked = historyViewModel.includeChildCategoryVideosFilter.value
        includeChildCategoryVideosCheck?.setOnCheckedChangeListener { _, isChecked ->
            historyViewModel.setIncludeChildCategoryVideosFilter(isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                val newExcluded = if (isChecked) {
                    emptySet()
                } else {
                    val selectedKeyword = historyViewModel.keywordFilter.value
                    val selectedAuthor = historyViewModel.authorFilter.value
                    when {
                        selectedKeyword.isNotBlank() -> {
                            historyViewModel.getKeywordInfoByNameForCurrentFilters(selectedKeyword)
                                ?.childKeywords
                                ?.toSet()
                                ?: emptySet()
                        }
                        selectedAuthor.isNotBlank() -> {
                            historyViewModel.getRootKeywordInfosByAuthorForCurrentFilters(selectedAuthor)
                                .map { it.keyword }
                                .toSet()
                        }
                        else -> emptySet()
                    }
                }
                withContext(Dispatchers.Main) {
                    historyViewModel.setExcludedChildKeywordsFilter(newExcluded)
                }
            }
        }
        showHiddenOnlyCheck?.isChecked = historyViewModel.showHiddenOnlyFilter.value
        showHiddenOnlyCheck?.setOnCheckedChangeListener { _, isChecked ->
            historyViewModel.setShowHiddenOnlyFilter(isChecked)
            sharedPreferences.edit().putBoolean(prefShowHiddenOnlyKey, isChecked).apply()
        }
        if (websiteList.size < 2) {
            filterSheet.findViewById<View>(R.id.websiteFilters)?.isVisible = false
        } else {
            val websiteGroup = filterSheet.findViewById<ChipGroup>(R.id.websitesChipGroup)
            val websiteFilter = historyViewModel.websiteFilter.value
            for (i in websiteList.indices) {
                val w = websiteList[i]
                val tmp = layoutinflater.inflate(R.layout.filter_chip, websiteGroup, false) as Chip
                tmp.text = w
                tmp.id = i
                tmp.setOnClickListener {
                    historyViewModel.setWebsiteFilter(if (tmp.isChecked) tmp.text as String else "")
                }
                if (w == websiteFilter) tmp.isChecked = true
                websiteGroup!!.addView(tmp)
            }
        }

        val displayMetrics = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
        filterSheet.behavior.peekHeight = displayMetrics.heightPixels
        filterSheet.show()
    }

    private fun updateStatusIcons(
        notDeleted: TextView,
        deleted: TextView,
        missingThumbnail: TextView,
        customThumbnailOnly: TextView,
        hardSubDoneOnly: TextView,
        status: HistoryViewModel.HistoryStatus
    ) {
        val checkIcon = R.drawable.ic_check
        val emptyIcon = R.drawable.empty
        when (status) {
            HistoryViewModel.HistoryStatus.ALL -> {
                notDeleted.setCompoundDrawablesRelativeWithIntrinsicBounds(checkIcon, 0, 0, 0)
                deleted.setCompoundDrawablesRelativeWithIntrinsicBounds(checkIcon, 0, 0, 0)
                missingThumbnail.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                customThumbnailOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                hardSubDoneOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
            }
            HistoryViewModel.HistoryStatus.DELETED -> {
                notDeleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                deleted.setCompoundDrawablesRelativeWithIntrinsicBounds(checkIcon, 0, 0, 0)
                missingThumbnail.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                customThumbnailOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                hardSubDoneOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
            }
            HistoryViewModel.HistoryStatus.NOT_DELETED -> {
                notDeleted.setCompoundDrawablesRelativeWithIntrinsicBounds(checkIcon, 0, 0, 0)
                deleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                missingThumbnail.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                customThumbnailOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                hardSubDoneOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
            }
            HistoryViewModel.HistoryStatus.MISSING_THUMBNAIL -> {
                notDeleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                deleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                missingThumbnail.setCompoundDrawablesRelativeWithIntrinsicBounds(checkIcon, 0, 0, 0)
                customThumbnailOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                hardSubDoneOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
            }
            HistoryViewModel.HistoryStatus.CUSTOM_THUMBNAIL -> {
                notDeleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                deleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                missingThumbnail.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                customThumbnailOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(checkIcon, 0, 0, 0)
                hardSubDoneOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
            }
            HistoryViewModel.HistoryStatus.HARDSUB_DONE -> {
                notDeleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                deleted.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                missingThumbnail.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                customThumbnailOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(emptyIcon, 0, 0, 0)
                hardSubDoneOnly.setCompoundDrawablesRelativeWithIntrinsicBounds(checkIcon, 0, 0, 0)
            }
            else -> {}
        }
    }

    private fun cycleStatusOnNotDeleted(status: HistoryViewModel.HistoryStatus): HistoryViewModel.HistoryStatus {
        return when (status) {
            HistoryViewModel.HistoryStatus.ALL -> HistoryViewModel.HistoryStatus.DELETED
            HistoryViewModel.HistoryStatus.NOT_DELETED -> HistoryViewModel.HistoryStatus.UNSET
            HistoryViewModel.HistoryStatus.DELETED -> HistoryViewModel.HistoryStatus.ALL
            else -> HistoryViewModel.HistoryStatus.NOT_DELETED
        }
    }

    private fun cycleStatusOnDeleted(status: HistoryViewModel.HistoryStatus): HistoryViewModel.HistoryStatus {
        return when (status) {
            HistoryViewModel.HistoryStatus.ALL -> HistoryViewModel.HistoryStatus.NOT_DELETED
            HistoryViewModel.HistoryStatus.NOT_DELETED -> HistoryViewModel.HistoryStatus.ALL
            HistoryViewModel.HistoryStatus.DELETED -> HistoryViewModel.HistoryStatus.UNSET
            else -> HistoryViewModel.HistoryStatus.DELETED
        }
    }

    private fun cycleStatusOnMissingThumbnail(status: HistoryViewModel.HistoryStatus): HistoryViewModel.HistoryStatus {
        return if (status == HistoryViewModel.HistoryStatus.MISSING_THUMBNAIL) {
            HistoryViewModel.HistoryStatus.ALL
        } else {
            HistoryViewModel.HistoryStatus.MISSING_THUMBNAIL
        }
    }

    private fun cycleStatusOnCustomThumbnail(status: HistoryViewModel.HistoryStatus): HistoryViewModel.HistoryStatus {
        return if (status == HistoryViewModel.HistoryStatus.CUSTOM_THUMBNAIL) {
            HistoryViewModel.HistoryStatus.ALL
        } else {
            HistoryViewModel.HistoryStatus.CUSTOM_THUMBNAIL
        }
    }

    private fun cycleStatusOnHardSubDone(status: HistoryViewModel.HistoryStatus): HistoryViewModel.HistoryStatus {
        return if (status == HistoryViewModel.HistoryStatus.HARDSUB_DONE) {
            HistoryViewModel.HistoryStatus.ALL
        } else {
            HistoryViewModel.HistoryStatus.HARDSUB_DONE
        }
    }

    private fun changeSortIcon(item: TextView, order: SORTING) {
        when (order) {
            SORTING.DESC -> item.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_up, 0, 0, 0)
            SORTING.ASC -> item.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_down, 0, 0, 0)
        }
    }

    private fun updatePlaylistLabel(playlistId: Long) {
        if (playlistId == playlistFilterUnassigned) {
            selectedPlaylistText.text = getString(R.string.not_in_any_playlist)
            selectedPlaylistText.visibility = View.VISIBLE
            return
        }
        if (playlistId < 0L) {
            if (historyViewModel.playlistGroupFilter.value < 0L) {
                selectedPlaylistText.visibility = View.GONE
            }
            return
        }
        val playlistName = playlistsCache.firstOrNull { it.id == playlistId }?.name ?: playlistId.toString()
        selectedPlaylistText.text = playlistName
        selectedPlaylistText.visibility = View.VISIBLE
    }

    private fun updateYoutuberChipCheckedState() {
        youtuberChip.isChecked = historyViewModel.isYoutuberSelectionMode.value ||
            historyViewModel.authorFilter.value.isNotEmpty() ||
            historyViewModel.youtuberGroupFilter.value >= 0L
    }

    private fun initChips() {
        sortChip.setOnClickListener { showSortDialog() }

        youtuberChip.setOnClickListener {
            lastYoutuberOriginGroupFilter = null
            clearNavigationBackStack()
            pendingScrollToTop = true
            historyViewModel.setRecentMode(false)
            if (historyViewModel.authorFilter.value.isNotEmpty()) {
                historyViewModel.setAuthorFilter("")
                historyViewModel.setYoutuberGroupFilter(-1L)
                if (!historyViewModel.isYoutuberSelectionMode.value) {
                    historyViewModel.toggleYoutuberSelectionMode()
                }
            } else {
                if (historyViewModel.youtuberGroupFilter.value >= 0L) {
                    historyViewModel.setYoutuberGroupFilter(-1L)
                    if (!historyViewModel.isYoutuberSelectionMode.value) {
                        historyViewModel.toggleYoutuberSelectionMode()
                    }
                } else {
                    historyViewModel.toggleYoutuberSelectionMode()
                }
            }
            if (historyViewModel.playlistFilter.value != -1L) {
                historyViewModel.setPlaylistFilter(-1L)
            }
            if (historyViewModel.playlistGroupFilter.value >= 0L) {
                historyViewModel.setPlaylistGroupFilter(-1L)
            }
            if (historyViewModel.isPlaylistSelectionMode.value) {
                historyViewModel.togglePlaylistSelectionMode()
            }
            if (historyViewModel.keywordFilter.value.isNotEmpty()) {
                historyViewModel.setKeywordFilter("")
            }
            if (historyViewModel.keywordGroupFilter.value >= 0L) {
                historyViewModel.setKeywordGroupFilter(-1L)
            }
            if (historyViewModel.isKeywordSelectionMode.value) {
                historyViewModel.toggleKeywordSelectionMode()
            }
            requestScrollToTop()
        }

        playlistChip.setOnClickListener {
            clearNavigationBackStack()
            pendingScrollToTop = true
            historyViewModel.setRecentMode(false)
            val isPlaylistMode = historyViewModel.isPlaylistSelectionMode.value
            val playlistFilter = historyViewModel.playlistFilter.value
            val playlistGroupFilter = historyViewModel.playlistGroupFilter.value
            val isPlaylistOverview = isPlaylistMode && playlistFilter == -1L && playlistGroupFilter < 0L
            val isUnassignedVideos = !isPlaylistMode && playlistFilter == playlistFilterUnassigned && playlistGroupFilter < 0L

            when {
                // 1) Playlist overview -> 2) Videos not in any playlist
                isPlaylistOverview -> {
                    historyViewModel.setPlaylistFilter(playlistFilterUnassigned)
                    historyViewModel.togglePlaylistSelectionMode()
                }
                // Any specific playlist/group selection also advances to unassigned videos
                playlistFilter >= 0L || playlistGroupFilter >= 0L -> {
                    historyViewModel.setPlaylistGroupFilter(-1L)
                    historyViewModel.setPlaylistFilter(playlistFilterUnassigned)
                    if (isPlaylistMode) {
                        historyViewModel.togglePlaylistSelectionMode()
                    }
                }
                // 2) Videos not in any playlist -> 3) All videos (off)
                isUnassignedVideos -> {
                    historyViewModel.setPlaylistFilter(-1L)
                }
                // 3) All videos (off) -> 1) Playlist overview
                else -> {
                    historyViewModel.setPlaylistGroupFilter(-1L)
                    historyViewModel.setPlaylistFilter(-1L)
                    if (!isPlaylistMode) {
                        historyViewModel.togglePlaylistSelectionMode()
                    }
                }
            }
            if (historyViewModel.authorFilter.value.isNotEmpty()) {
                historyViewModel.setAuthorFilter("")
            }
            if (historyViewModel.isYoutuberSelectionMode.value) {
                historyViewModel.toggleYoutuberSelectionMode()
            }
            if (historyViewModel.youtuberGroupFilter.value >= 0L) {
                historyViewModel.setYoutuberGroupFilter(-1L)
            }
            if (historyViewModel.keywordFilter.value.isNotEmpty()) {
                historyViewModel.setKeywordFilter("")
            }
            if (historyViewModel.keywordGroupFilter.value >= 0L) {
                historyViewModel.setKeywordGroupFilter(-1L)
            }
            if (historyViewModel.isKeywordSelectionMode.value) {
                historyViewModel.toggleKeywordSelectionMode()
            }
            requestScrollToTop()
        }

        keywordChip.setOnClickListener {
            lastKeywordOriginGroupFilter = null
            clearNavigationBackStack()
            pendingScrollToTop = true
            historyViewModel.setRecentMode(false)
            historyViewModel.setExcludedChildKeywordsFilter(emptySet())
            if (historyViewModel.keywordFilter.value.isNotEmpty()) {
                historyViewModel.setKeywordFilter("")
                historyViewModel.setKeywordGroupFilter(-1L)
                if (!historyViewModel.isKeywordSelectionMode.value) {
                    historyViewModel.toggleKeywordSelectionMode()
                }
            } else {
                if (historyViewModel.keywordGroupFilter.value >= 0L) {
                    historyViewModel.setKeywordGroupFilter(-1L)
                    if (!historyViewModel.isKeywordSelectionMode.value) {
                        historyViewModel.toggleKeywordSelectionMode()
                    }
                } else {
                    historyViewModel.toggleKeywordSelectionMode()
                }
            }
            if (historyViewModel.authorFilter.value.isNotEmpty()) {
                historyViewModel.setAuthorFilter("")
            }
            if (historyViewModel.youtuberGroupFilter.value >= 0L) {
                historyViewModel.setYoutuberGroupFilter(-1L)
            }
            if (historyViewModel.isYoutuberSelectionMode.value) {
                historyViewModel.toggleYoutuberSelectionMode()
            }
            if (historyViewModel.playlistFilter.value != -1L) {
                historyViewModel.setPlaylistFilter(-1L)
            }
            if (historyViewModel.playlistGroupFilter.value >= 0L) {
                historyViewModel.setPlaylistGroupFilter(-1L)
            }
            if (historyViewModel.isPlaylistSelectionMode.value) {
                historyViewModel.togglePlaylistSelectionMode()
            }
            requestScrollToTop()
        }

        recentChip.setOnCheckedChangeListener { _, isChecked ->
            clearNavigationBackStack()
            if (historyViewModel.isRecentMode.value == isChecked) return@setOnCheckedChangeListener
            pendingScrollToTop = true
            historyViewModel.setRecentMode(isChecked)
            if (isChecked) {
                historyViewModel.setAuthorFilter("")
                historyViewModel.setPlaylistFilter(-1L)
                historyViewModel.setPlaylistGroupFilter(-1L)
                historyViewModel.setYoutuberGroupFilter(-1L)
                historyViewModel.setKeywordGroupFilter(-1L)
                historyViewModel.setKeywordFilter("")
                if (historyViewModel.isYoutuberSelectionMode.value) {
                    historyViewModel.toggleYoutuberSelectionMode()
                }
                if (historyViewModel.isPlaylistSelectionMode.value) {
                    historyViewModel.togglePlaylistSelectionMode()
                }
                if (historyViewModel.isKeywordSelectionMode.value) {
                    historyViewModel.toggleKeywordSelectionMode()
                }
            }
            requestScrollToTop()
        }
    }


    private fun showSortDialog() {
        sortSheet = BottomSheetDialog(requireContext())
        sortSheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        sortSheet.setContentView(R.layout.history_sort_sheet)

        val date = sortSheet.findViewById<TextView>(R.id.date)
        val title = sortSheet.findViewById<TextView>(R.id.title)
        val duration = sortSheet.findViewById<TextView>(R.id.duration)

        val sortOptions = listOf(date!!, title!!, duration!!)
        sortOptions.forEach { it.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.empty, 0, 0, 0) }

        when (historyViewModel.sortType.value!!) {
            HistoryRepository.HistorySortType.DATE -> changeSortIcon(date, historyViewModel.sortOrder.value!!)
            HistoryRepository.HistorySortType.TITLE -> changeSortIcon(title, historyViewModel.sortOrder.value!!)
            HistoryRepository.HistorySortType.AUTHOR -> changeSortIcon(title, historyViewModel.sortOrder.value!!)
            HistoryRepository.HistorySortType.DURATION -> changeSortIcon(duration, historyViewModel.sortOrder.value!!)
        }

        date.setOnClickListener {
            sortOptions.forEach { it.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.empty, 0, 0, 0) }
            historyViewModel.setSorting(HistoryRepository.HistorySortType.DATE)
            changeSortIcon(date, historyViewModel.sortOrder.value!!)
        }
        title.setOnClickListener {
            sortOptions.forEach { it.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.empty, 0, 0, 0) }
            historyViewModel.setSorting(HistoryRepository.HistorySortType.TITLE)
            changeSortIcon(title, historyViewModel.sortOrder.value!!)
        }
        duration.setOnClickListener {
            sortOptions.forEach { it.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.empty, 0, 0, 0) }
            historyViewModel.setSorting(HistoryRepository.HistorySortType.DURATION)
            changeSortIcon(duration, historyViewModel.sortOrder.value!!)
        }

        val displayMetrics = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
        sortSheet.behavior.peekHeight = displayMetrics.heightPixels
        sortSheet.show()
    }

    override fun onCardClick(itemID: Long, filePresent: Boolean) {
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) {
                runCatching { historyViewModel.getByID(itemID) }.getOrNull()
            } ?: return@launch
            if (item.type.toString() == "video" && filePresent) {
                val path = item.downloadPath.firstOrNull { FileUtil.exists(it) }
                    ?: item.downloadPath.firstOrNull()
                if (path.isNullOrBlank()) return@launch
                val currentState = captureNavigationState()
                savedScrollByState[currentState] = captureScrollSnapshot()
                restoreScrollOnNextResume = true
                val intent = Intent(activity, VideoPlayerActivity::class.java)
                intent.putExtra("video_path", path)
                intent.putExtra("history_id", item.id)
                intent.putExtra("playback_position_ms", item.playbackPositionMs)
                intent.putExtra("context_sort_type", historyViewModel.sortType.value.name)
                intent.putExtra("context_sort_order", historyViewModel.sortOrder.value.name)
                intent.putExtra("context_query", historyViewModel.queryFilterFlow.value)
                intent.putExtra("context_title_query", historyViewModel.titleQueryFilterFlow.value)
                intent.putExtra("context_keyword_query", historyViewModel.keywordQueryFilterFlow.value)
                intent.putExtra("context_creator_query", historyViewModel.creatorQueryFilterFlow.value)
                intent.putExtra(
                    "context_search_fields",
                    historyViewModel.searchFieldsFilter.value.map { it.name }.sorted().joinToString(",")
                )
                intent.putExtra("context_type", historyViewModel.typeFilterFlow.value)
                intent.putExtra("context_website", historyViewModel.websiteFilter.value)
                intent.putExtra("context_include_child_category_videos", historyViewModel.includeChildCategoryVideosFilter.value)
                intent.putExtra(
                    "context_excluded_child_keywords",
                    historyViewModel.excludedChildKeywordsFilter.value.joinToString(",")
                )
                val authorFilter = historyViewModel.authorFilter.value
                if (authorFilter.isNotEmpty()) {
                    intent.putExtra("context_author", authorFilter)
                }
                val keywordFilter = historyViewModel.keywordFilter.value
                if (keywordFilter.isNotEmpty()) {
                    intent.putExtra("context_keyword", keywordFilter)
                }
                val playlistId = historyViewModel.playlistFilter.value
                if (playlistId >= 0L) {
                    intent.putExtra("context_playlist_id", playlistId)
                    val playlistName = playlistsCache.firstOrNull { it.id == playlistId }?.name
                    if (!playlistName.isNullOrBlank()) {
                        intent.putExtra("context_playlist_name", playlistName)
                    }
                }
                val youtuberGroupId = historyViewModel.youtuberGroupFilter.value
                if (youtuberGroupId >= 0L) {
                    intent.putExtra("context_youtuber_group_id", youtuberGroupId)
                }
                startActivity(intent)
            } else {
                UiUtil.showHistoryItemDetailsCard(item, requireActivity(), filePresent, sharedPreferences,
                    removeItem = { it, deleteFile -> historyViewModel.delete(it, deleteFile) },
                    redownloadItem = {
                        lifecycleScope.launch {
                            val downloadItem = downloadViewModel.createDownloadItemFromHistory(it)
                            downloadViewModel.queueDownloads(listOf(downloadItem), ignoreDuplicates = false)
                        }
                    },
                    redownloadShowDownloadCard = {
                        findNavController().navigate(
                            R.id.downloadBottomSheetDialog, bundleOf(
                                Pair("result", downloadViewModel.createResultItemFromHistory(it)),
                                Pair("type", it.type),
                                Pair("source_history_id", it.id),
                                Pair("ignore_duplicates", false)
                            )
                        )
                    }
                )
            }
        }
    }

    override fun onButtonClick(itemID: Long, filePresent: Boolean) {
        if (filePresent) {
            lifecycleScope.launch {
                val item = withContext(Dispatchers.IO) {
                    runCatching { historyViewModel.getByID(itemID) }.getOrNull()
                } ?: return@launch
                FileUtil.shareFileIntent(requireContext(), item.downloadPath)
            }
        }
    }

    override fun onCardSelect(isChecked: Boolean, position: Int) {
        lifecycleScope.launch {
            val selectedObjects = historyAdapter.getSelectedObjectsCount(totalCount)
            if (actionMode == null) actionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(contextualActionBar)
            actionMode?.apply {
                when {
                    selectedObjects == 0 -> this.finish()
                    else -> {
                        actionMode?.title = "$selectedObjects ${getString(R.string.selected)}"
                        this.menu.findItem(R.id.select_between).isVisible = false
                        if (selectedObjects == 2) {
                            val selectedIDs = contextualActionBar.getSelectedIDs().sortedBy { it }
                            val idsInMiddle = withContext(Dispatchers.IO) {
                                historyViewModel.getIDsBetweenTwoItems(selectedIDs.first(), selectedIDs.last())
                            }
                            this.menu.findItem(R.id.select_between).isVisible = idsInMiddle.isNotEmpty()
                        }
                    }
                }
            }

            when {
                isChecked && actionMode == null -> {
                    actionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(contextualActionBar)
                }
                isChecked -> actionMode!!.title = "$selectedObjects ${getString(R.string.selected)}"
                else -> {
                    actionMode?.title = "$selectedObjects ${getString(R.string.selected)}"
                    if (selectedObjects == 0) actionMode?.finish()
                }
            }
        }
    }

    private val contextualActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.history_menu_context, menu)
            mode.title = "${historyAdapter.getSelectedObjectsCount(totalCount)} ${getString(R.string.selected)}"
            (activity as MainActivity).disableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = false }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            menu?.findItem(R.id.edit_item)?.isVisible = historyAdapter.getSelectedObjectsCount(totalCount) == 1
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item!!.itemId) {
                R.id.select_between -> {
                    lifecycleScope.launch {
                        val selectedIDs = getSelectedIDs().sortedBy { it }
                        val idsInMiddle = withContext(Dispatchers.IO) {
                            historyViewModel.getIDsBetweenTwoItems(selectedIDs.first(), selectedIDs.last())
                        }.toMutableList()
                        idsInMiddle.addAll(selectedIDs)
                        if (idsInMiddle.isNotEmpty()) {
                            historyAdapter.checkMultipleItems(idsInMiddle)
                            actionMode?.title = "${idsInMiddle.count()} ${getString(R.string.selected)}"
                        }
                        mode?.menu?.findItem(R.id.select_between)?.isVisible = false
                    }
                    true
                }
                R.id.delete_results -> {
                    val deleteFile = booleanArrayOf(false)
                    val deleteDialog = MaterialAlertDialogBuilder(fragmentContext!!)
                    deleteDialog.setTitle(getString(R.string.you_are_going_to_delete_multiple_items))
                    deleteDialog.setMultiChoiceItems(arrayOf(getString(R.string.delete_files_too)), booleanArrayOf(false)) { _, _, b -> deleteFile[0] = b }
                    deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface, _ -> dialogInterface.cancel() }
                    deleteDialog.setPositiveButton(getString(R.string.ok)) { _, _ ->
                        lifecycleScope.launch {
                            val selectedObjects = getSelectedIDs()
                            historyAdapter.clearCheckedItems()
                            historyViewModel.deleteAllWithIDs(selectedObjects, deleteFile[0])
                            actionMode?.finish()
                        }
                    }
                    deleteDialog.show()
                    true
                }
                R.id.share -> {
                    lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        val paths = withContext(Dispatchers.IO) { historyViewModel.getDownloadPathsFromIDs(selectedObjects) }
                        FileUtil.shareFileIntent(requireContext(), paths.flatten())
                        historyAdapter.clearCheckedItems()
                        actionMode?.finish()
                    }
                    true
                }
                R.id.redownload -> {
                    lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        historyAdapter.clearCheckedItems()
                        actionMode?.finish()
                        if (selectedObjects.size == 1) {
                            val tmp = withContext(Dispatchers.IO) {
                                runCatching { historyViewModel.getByID(selectedObjects.first()) }.getOrNull()
                            }
                            if (tmp == null) {
                                Toast.makeText(context, getString(R.string.no_match_found), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            findNavController().navigate(
                                R.id.downloadBottomSheetDialog, bundleOf(
                                    Pair("result", downloadViewModel.createResultItemFromHistory(tmp)),
                                    Pair("type", tmp.type),
                                    Pair("source_history_id", tmp.id),
                                    Pair("ignore_duplicates", false)
                                )
                            )
                        } else {
                            val showDownloadCard = sharedPreferences.getBoolean("download_card", true)
                            downloadViewModel.turnHistoryItemsToProcessingDownloads(selectedObjects, downloadNow = !showDownloadCard)
                            actionMode?.finish()
                            if (showDownloadCard) {
                                val bundle = Bundle()
                                bundle.putLongArray("currentHistoryIDs", selectedObjects.toLongArray())
                                bundle.putBoolean("ignore_duplicates", false)
                                findNavController().navigate(R.id.downloadMultipleBottomSheetDialog2, bundle)
                            }
                        }
                    }
                    true
                }
                R.id.edit_item -> {
                    lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        if (selectedObjects.size != 1) {
                            Toast.makeText(context, getString(R.string.select_single_item), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val item = withContext(Dispatchers.IO) {
                            runCatching { historyViewModel.getByID(selectedObjects.first()) }.getOrNull()
                        }
                        if (item == null) {
                            Toast.makeText(context, getString(R.string.no_match_found), Toast.LENGTH_SHORT).show()
                            actionMode?.finish()
                            return@launch
                        }
                        showEditHistoryItemDialog(item)
                        actionMode?.finish()
                    }
                    true
                }
                R.id.add_artist -> {
                    lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        if (selectedObjects.isEmpty()) return@launch
                        showAddArtistDialog(selectedObjects)
                        actionMode?.finish()
                    }
                    true
                }
                R.id.add_keywords -> {
                    lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        if (selectedObjects.isEmpty()) return@launch
                        showAddKeywordsDialog(selectedObjects)
                        actionMode?.finish()
                    }
                    true
                }
                R.id.select_all -> {
                    historyAdapter.checkAll()
                    val selectedCount = historyAdapter.getSelectedObjectsCount(totalCount)
                    mode?.title = "(${selectedCount}) ${resources.getString(R.string.all_items_selected)}"
                    true
                }
                R.id.invert_selected -> {
                    historyAdapter.invertSelected()
                    val selectedCount = historyAdapter.getSelectedObjectsCount(totalCount)
                    actionMode?.title = "$selectedCount ${getString(R.string.selected)}"
                    if (selectedCount == 0) actionMode?.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            (activity as MainActivity).enableBottomNavigation()
            historyAdapter.clearCheckedItems()
            topAppBar.menu.forEach { it.isEnabled = true }
        }

        suspend fun getSelectedIDs(): List<Long> {
            return if (historyAdapter.inverted || historyAdapter.checkedItems.isEmpty()) {
                withContext(Dispatchers.IO) { historyViewModel.getItemIDsNotPresentIn(historyAdapter.checkedItems.toList()) }
            } else {
                historyAdapter.checkedItems.toList()
            }
        }
    }

    private val youtuberActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.youtuber_menu_context, menu)
            mode.title = "${historyAdapter.getSelectedYoutubers().size} ${getString(R.string.selected)}"
            (activity as MainActivity).disableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = false }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val count = historyAdapter.getSelectedYoutubers().size
            val mixedSelection = historyAdapter.getSelectedYoutuberGroups().isNotEmpty()
            val selected = historyAdapter.getSelectedYoutubers()
            menu?.findItem(R.id.edit_youtuber_info)?.isVisible = count == 1
            menu?.findItem(R.id.remove_from_current_youtuber_group)?.isVisible =
                historyViewModel.youtuberGroupFilter.value >= 0L && count > 0
            val allShownOnFirstList = selected.isNotEmpty() && selected.all { visibleChildYoutubers.contains(it) }
            menu?.findItem(R.id.show_youtubers_on_first_list)?.isVisible = selected.isNotEmpty() && !allShownOnFirstList
            menu?.findItem(R.id.hide_youtubers_from_first_list)?.isVisible = allShownOnFirstList
            if (mixedSelection) {
                menu?.findItem(R.id.edit_youtuber_info)?.isVisible = false
                menu?.findItem(R.id.show_youtubers_on_first_list)?.isVisible = false
                menu?.findItem(R.id.hide_youtubers_from_first_list)?.isVisible = false
            }
            val allHidden = selected.isNotEmpty() && selected.all { hiddenYoutubers.contains(it) }
            menu?.findItem(R.id.hide_youtubers)?.isVisible = !allHidden
            menu?.findItem(R.id.unhide_youtubers)?.isVisible = allHidden
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.add_to_youtuber_group -> {
                    collectSelectedAuthorsIncludingGroups { merged ->
                        if (merged.isNotEmpty()) {
                            showAddToYoutuberGroupDialog(merged)
                        }
                    }
                    true
                }
                R.id.edit_youtuber_info -> {
                    val selected = historyAdapter.getSelectedYoutubers()
                    if (selected.size == 1) {
                        showEditYoutuberInfoDialog(selected.first())
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.select_single_item), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.remove_from_current_youtuber_group -> {
                    val groupId = historyViewModel.youtuberGroupFilter.value
                    if (groupId >= 0L) {
                        collectSelectedAuthorsIncludingGroups { merged ->
                            if (merged.isNotEmpty()) {
                                removeAuthorsFromCurrentYoutuberGroup(groupId, merged)
                            }
                        }
                    }
                    true
                }
                R.id.show_youtubers_on_first_list -> {
                    val selected = historyAdapter.getSelectedYoutubers()
                    if (selected.isNotEmpty()) {
                        visibleChildYoutubers.addAll(selected)
                        persistHiddenStateToPrefs()
                        historyViewModel.setVisibleChildYoutubersFilter(visibleChildYoutubers.toSet())
                    }
                    youtuberActionMode?.finish()
                    true
                }
                R.id.hide_youtubers_from_first_list -> {
                    val selected = historyAdapter.getSelectedYoutubers()
                    if (selected.isNotEmpty()) {
                        visibleChildYoutubers.removeAll(selected.toSet())
                        persistHiddenStateToPrefs()
                        historyViewModel.setVisibleChildYoutubersFilter(visibleChildYoutubers.toSet())
                    }
                    youtuberActionMode?.finish()
                    true
                }
                R.id.hide_youtubers -> {
                    val selected = historyAdapter.getSelectedYoutubers()
                    if (selected.isNotEmpty()) {
                        hiddenYoutubers.addAll(selected)
                        persistHiddenStateToPrefs()
                        historyViewModel.setHiddenYoutubersFilter(hiddenYoutubers)
                    }
                    youtuberActionMode?.finish()
                    true
                }
                R.id.unhide_youtubers -> {
                    val selected = historyAdapter.getSelectedYoutubers()
                    if (selected.isNotEmpty()) {
                        hiddenYoutubers.removeAll(selected.toSet())
                        persistHiddenStateToPrefs()
                        historyViewModel.setHiddenYoutubersFilter(hiddenYoutubers)
                    }
                    youtuberActionMode?.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            youtuberActionMode = null
            historyAdapter.clearYoutuberSelection()
            historyAdapter.clearYoutuberGroupSelection()
            youtuberGroupActionMode = null
            (activity as MainActivity).enableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = true }
        }
    }

    private val youtuberGroupActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.youtuber_group_menu_context, menu)
            mode.title = "${historyAdapter.getSelectedYoutuberGroups().size} ${getString(R.string.selected)}"
            (activity as MainActivity).disableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = false }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val count = historyAdapter.getSelectedYoutuberGroups().size
            menu?.findItem(R.id.rename_youtuber_group)?.isVisible = count == 1
            menu?.findItem(R.id.edit_parent_youtuber_group)?.isVisible = count == 1
            val selected = historyAdapter.getSelectedYoutuberGroups()
            val allShownOnFirstList = selected.isNotEmpty() && selected.all { visibleChildYoutuberGroups.contains(it) }
            menu?.findItem(R.id.show_youtuber_groups_on_first_list)?.isVisible = selected.isNotEmpty() && !allShownOnFirstList
            menu?.findItem(R.id.hide_youtuber_groups_from_first_list)?.isVisible = allShownOnFirstList
            val allHidden = selected.isNotEmpty() && selected.all { hiddenYoutuberGroups.contains(it) }
            menu?.findItem(R.id.hide_youtuber_groups)?.isVisible = !allHidden
            menu?.findItem(R.id.unhide_youtuber_groups)?.isVisible = allHidden
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.rename_youtuber_group -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.size == 1) {
                        showRenameYoutuberGroupDialog(selected.first())
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.select_single_item), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.delete_youtuber_group -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.isNotEmpty()) {
                        showDeleteYoutuberGroupsDialog(selected)
                    }
                    true
                }
                R.id.add_youtuber_groups_to_group -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.isNotEmpty()) {
                        showAddYoutuberGroupsToParentDialog(selected)
                    }
                    true
                }
                R.id.edit_parent_youtuber_group -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.size == 1) {
                        showEditParentYoutuberGroupsDialog(selected.first())
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.select_single_item), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.show_youtuber_groups_on_first_list -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.isNotEmpty()) {
                        visibleChildYoutuberGroups.addAll(selected)
                        persistHiddenStateToPrefs()
                        historyViewModel.setVisibleChildYoutuberGroupsFilter(visibleChildYoutuberGroups.toSet())
                    }
                    youtuberGroupActionMode?.finish()
                    true
                }
                R.id.hide_youtuber_groups_from_first_list -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.isNotEmpty()) {
                        visibleChildYoutuberGroups.removeAll(selected.toSet())
                        persistHiddenStateToPrefs()
                        historyViewModel.setVisibleChildYoutuberGroupsFilter(visibleChildYoutuberGroups.toSet())
                    }
                    youtuberGroupActionMode?.finish()
                    true
                }
                R.id.hide_youtuber_groups -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.isNotEmpty()) {
                        hiddenYoutuberGroups.addAll(selected)
                        persistHiddenStateToPrefs()
                        historyViewModel.setHiddenYoutuberGroupsFilter(hiddenYoutuberGroups)
                    }
                    youtuberGroupActionMode?.finish()
                    true
                }
                R.id.unhide_youtuber_groups -> {
                    val selected = historyAdapter.getSelectedYoutuberGroups()
                    if (selected.isNotEmpty()) {
                        hiddenYoutuberGroups.removeAll(selected.toSet())
                        persistHiddenStateToPrefs()
                        historyViewModel.setHiddenYoutuberGroupsFilter(hiddenYoutuberGroups)
                    }
                    youtuberGroupActionMode?.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            youtuberGroupActionMode = null
            historyAdapter.clearYoutuberSelection()
            historyAdapter.clearYoutuberGroupSelection()
            youtuberActionMode = null
            (activity as MainActivity).enableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = true }
        }
    }

    private val playlistActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.playlist_menu_context, menu)
            mode.title = "${historyAdapter.getSelectedPlaylists().size} ${getString(R.string.selected)}"
            (activity as MainActivity).disableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = false }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val count = historyAdapter.getSelectedPlaylists().size
            menu?.findItem(R.id.rename_selected_playlist)?.isVisible = count == 1
            menu?.findItem(R.id.remove_from_current_playlist_group)?.isVisible =
                historyViewModel.playlistGroupFilter.value >= 0L && count > 0
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.add_to_playlist_group -> {
                    val selected = historyAdapter.getSelectedPlaylists()
                    if (selected.isNotEmpty()) {
                        showAddToPlaylistGroupDialog(selected)
                    }
                    true
                }
                R.id.rename_selected_playlist -> {
                    val selected = historyAdapter.getSelectedPlaylists()
                    if (selected.size == 1) {
                        showRenamePlaylistDialog(selected.first())
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.select_single_item), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.delete_selected_playlist -> {
                    val selected = historyAdapter.getSelectedPlaylists()
                    if (selected.isNotEmpty()) {
                        showDeletePlaylistsDialog(selected)
                    }
                    true
                }
                R.id.remove_from_current_playlist_group -> {
                    val selected = historyAdapter.getSelectedPlaylists()
                    val groupId = historyViewModel.playlistGroupFilter.value
                    if (groupId >= 0L && selected.isNotEmpty()) {
                        removePlaylistsFromCurrentPlaylistGroup(groupId, selected)
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            playlistActionMode = null
            historyAdapter.clearPlaylistSelection()
            (activity as MainActivity).enableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = true }
        }
    }

    private val playlistGroupActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.playlist_group_menu_context, menu)
            mode.title = "${historyAdapter.getSelectedPlaylistGroups().size} ${getString(R.string.selected)}"
            (activity as MainActivity).disableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = false }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val count = historyAdapter.getSelectedPlaylistGroups().size
            menu?.findItem(R.id.rename_playlist_group)?.isVisible = count == 1
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.rename_playlist_group -> {
                    val selected = historyAdapter.getSelectedPlaylistGroups()
                    if (selected.size == 1) {
                        showRenamePlaylistGroupDialog(selected.first())
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.select_single_item), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.delete_playlist_group -> {
                    val selected = historyAdapter.getSelectedPlaylistGroups()
                    if (selected.isNotEmpty()) {
                        showDeletePlaylistGroupsDialog(selected)
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            playlistGroupActionMode = null
            historyAdapter.clearPlaylistGroupSelection()
            (activity as MainActivity).enableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = true }
        }
    }

    private fun showRenamePlaylistDialog(playlistId: Long) {
        val currentName = playlistsCache.firstOrNull { it.id == playlistId }?.name ?: ""
        val editText = EditText(requireContext()).apply {
            setText(currentName)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rename_playlist))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    playlistViewModel.renamePlaylist(playlistId, newName)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private val simpleCallback: ItemTouchHelper.SimpleCallback =
        object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return when (viewHolder) {
                    is HistoryPaginatedAdapter.HistoryItemViewHolder -> super.getMovementFlags(recyclerView, viewHolder)
                    else -> makeMovementFlags(0, 0)
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val itemID = (viewHolder.itemView.tag as? Long)
                    ?: viewHolder.itemView.tag?.toString()?.toLongOrNull()
                if (itemID == null) {
                    if (position != RecyclerView.NO_POSITION) {
                        historyAdapter.notifyItemChanged(position)
                    } else {
                        historyAdapter.notifyDataSetChanged()
                    }
                    return
                }
                when (direction) {
                    ItemTouchHelper.LEFT -> {
                        lifecycleScope.launch {
                            val deletedItem = withContext(Dispatchers.IO) {
                                runCatching { historyViewModel.getByID(itemID) }.getOrNull()
                            }
                            if (position != RecyclerView.NO_POSITION) {
                                historyAdapter.notifyItemChanged(position)
                            } else {
                                historyAdapter.notifyDataSetChanged()
                            }
                            if (deletedItem == null) return@launch
                            UiUtil.showRemoveHistoryItemDialog(deletedItem, requireActivity(), delete = { item, deleteFile ->
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { historyViewModel.delete(item, deleteFile) }
                                    if (!deleteFile) {
                                        Snackbar.make(recyclerView, getString(R.string.you_are_going_to_delete) + ": " + deletedItem.title, Snackbar.LENGTH_INDEFINITE)
                                            .setAction(getString(R.string.undo)) { historyViewModel.insert(deletedItem) }
                                            .show()
                                    }
                                }
                            })
                        }
                    }
                    ItemTouchHelper.RIGHT -> {
                        lifecycleScope.launch {
                            val item = withContext(Dispatchers.IO) {
                                runCatching { historyViewModel.getByID(itemID) }.getOrNull()
                            }
                            if (position != RecyclerView.NO_POSITION) {
                                historyAdapter.notifyItemChanged(position)
                            } else {
                                historyAdapter.notifyDataSetChanged()
                            }
                            if (item == null) return@launch
                            findNavController().navigate(
                                R.id.downloadBottomSheetDialog, bundleOf(
                                    Pair("result", downloadViewModel.createResultItemFromHistory(item)),
                                    Pair("type", item.type),
                                    Pair("source_history_id", item.id),
                                    Pair("ignore_duplicates", false)
                                )
                            )
                        }
                    }
                }
            }

            override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                RecyclerViewSwipeDecorator.Builder(requireContext(), c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                    .addSwipeLeftBackgroundColor(Color.RED)
                    .addSwipeLeftActionIcon(R.drawable.baseline_delete_24)
                    .addSwipeRightBackgroundColor(MaterialColors.getColor(requireContext(), R.attr.colorOnSurfaceInverse, Color.TRANSPARENT))
                    .addSwipeRightActionIcon(R.drawable.ic_refresh)
                    .create()
                    .decorate()
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

    override fun onYoutuberSelected(youtuber: String) {
        lastYoutuberOriginGroupFilter = historyViewModel.youtuberGroupFilter.value
        pushCurrentStateToNavigationStack()
        historyViewModel.setAuthorFilter(youtuber)
        historyViewModel.setExcludedChildKeywordsFilter(emptySet())
        historyViewModel.setYoutuberGroupFilter(-1L)
        historyViewModel.toggleYoutuberSelectionMode()
        if (historyViewModel.playlistFilter.value != -1L) {
            historyViewModel.setPlaylistFilter(-1L)
        }
        if (historyViewModel.playlistGroupFilter.value >= 0L) {
            historyViewModel.setPlaylistGroupFilter(-1L)
        }
        if (historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
        }
        requestScrollToTop()
    }

    override fun onYoutuberLongClick(youtuberInfo: com.ireum.ytdl.database.models.YoutuberInfo) {
        showYoutuberChildKeywordSelectionDialog(youtuberInfo.author)
    }

    override fun onYoutuberSelectionChanged(selectedCount: Int) {
        val totalSelected = totalYoutuberSelectionCount()
        if (totalSelected > 0) {
            if (youtuberActionMode == null && youtuberGroupActionMode == null) {
                youtuberActionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(youtuberActionBar)
            }
            val activeMode = youtuberActionMode ?: youtuberGroupActionMode
            activeMode?.title = "$totalSelected ${getString(R.string.selected)}"
            activeMode?.invalidate()
        } else {
            youtuberActionMode?.finish()
            youtuberGroupActionMode?.finish()
        }
    }

    override fun onYoutuberGroupSelected(groupId: Long) {
        lastYoutuberOriginGroupFilter = null
        pushCurrentStateToNavigationStack()
        historyViewModel.setYoutuberGroupFilter(groupId)
        if (!historyViewModel.isYoutuberSelectionMode.value) {
            historyViewModel.toggleYoutuberSelectionMode()
        }
        if (historyViewModel.authorFilter.value.isNotEmpty()) {
            historyViewModel.setAuthorFilter("")
        }
        if (historyViewModel.playlistFilter.value != -1L) {
            historyViewModel.setPlaylistFilter(-1L)
        }
        if (historyViewModel.playlistGroupFilter.value >= 0L) {
            historyViewModel.setPlaylistGroupFilter(-1L)
        }
        if (historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
        }
        requestScrollToTop()
    }

    override fun onYoutuberGroupSelectionChanged(selectedCount: Int) {
        val totalSelected = totalYoutuberSelectionCount()
        if (totalSelected > 0) {
            if (youtuberActionMode == null && youtuberGroupActionMode == null) {
                youtuberGroupActionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(youtuberGroupActionBar)
            }
            val activeMode = youtuberGroupActionMode ?: youtuberActionMode
            activeMode?.title = "$totalSelected ${getString(R.string.selected)}"
            activeMode?.invalidate()
        } else {
            youtuberActionMode?.finish()
            youtuberGroupActionMode?.finish()
        }
    }

    override fun onPlaylistSelected(playlistId: Long) {
        pushCurrentStateToNavigationStack()
        historyViewModel.setPlaylistFilter(playlistId)
        if (historyViewModel.playlistGroupFilter.value >= 0L) {
            historyViewModel.setPlaylistGroupFilter(-1L)
        }
        if (historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
        }
        if (historyViewModel.authorFilter.value.isNotEmpty()) {
            historyViewModel.setAuthorFilter("")
        }
        if (historyViewModel.youtuberGroupFilter.value >= 0L) {
            historyViewModel.setYoutuberGroupFilter(-1L)
        }
        if (historyViewModel.isYoutuberSelectionMode.value) {
            historyViewModel.toggleYoutuberSelectionMode()
        }
        if (historyViewModel.keywordFilter.value.isNotEmpty()) {
            historyViewModel.setKeywordFilter("")
        }
        if (historyViewModel.keywordGroupFilter.value >= 0L) {
            historyViewModel.setKeywordGroupFilter(-1L)
        }
        if (historyViewModel.isKeywordSelectionMode.value) {
            historyViewModel.toggleKeywordSelectionMode()
        }
        requestScrollToTop()
    }

    override fun onPlaylistSelectionChanged(selectedCount: Int) {
        if (selectedCount > 0 && playlistActionMode == null) {
            playlistActionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(playlistActionBar)
        }
        playlistActionMode?.title = "$selectedCount ${getString(R.string.selected)}"
        playlistActionMode?.invalidate()
        if (selectedCount == 0) {
            playlistActionMode?.finish()
        }
    }

    override fun onPlaylistGroupSelected(groupId: Long) {
        pushCurrentStateToNavigationStack()
        historyViewModel.setPlaylistGroupFilter(groupId)
        if (!historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
        }
        if (historyViewModel.playlistFilter.value != -1L) {
            historyViewModel.setPlaylistFilter(-1L)
        }
        if (historyViewModel.authorFilter.value.isNotEmpty()) {
            historyViewModel.setAuthorFilter("")
        }
        if (historyViewModel.youtuberGroupFilter.value >= 0L) {
            historyViewModel.setYoutuberGroupFilter(-1L)
        }
        if (historyViewModel.isYoutuberSelectionMode.value) {
            historyViewModel.toggleYoutuberSelectionMode()
        }
        if (historyViewModel.keywordFilter.value.isNotEmpty()) {
            historyViewModel.setKeywordFilter("")
        }
        if (historyViewModel.keywordGroupFilter.value >= 0L) {
            historyViewModel.setKeywordGroupFilter(-1L)
        }
        if (historyViewModel.isKeywordSelectionMode.value) {
            historyViewModel.toggleKeywordSelectionMode()
        }
        requestScrollToTop()
    }

    override fun onPlaylistGroupSelectionChanged(selectedCount: Int) {
        if (selectedCount > 0 && playlistGroupActionMode == null) {
            playlistGroupActionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(playlistGroupActionBar)
        }
        playlistGroupActionMode?.title = "$selectedCount ${getString(R.string.selected)}"
        playlistGroupActionMode?.invalidate()
        if (selectedCount == 0) {
            playlistGroupActionMode?.finish()
        }
    }

    private val keywordActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.keyword_menu_context, menu)
            mode.title = "${historyAdapter.getSelectedKeywords().size} ${getString(R.string.selected)}"
            (activity as MainActivity).disableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = false }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val count = historyAdapter.getSelectedKeywords().size
            val selected = historyAdapter.getSelectedKeywords()
            val allShownOnFirstList = selected.isNotEmpty() && selected.all { visibleChildKeywords.contains(it) }
            menu?.findItem(R.id.show_keywords_on_first_list)?.isVisible = selected.isNotEmpty() && !allShownOnFirstList
            menu?.findItem(R.id.hide_keywords_from_first_list)?.isVisible = allShownOnFirstList
            menu?.findItem(R.id.remove_from_current_keyword_group)?.isVisible =
                historyViewModel.keywordGroupFilter.value >= 0L && count > 0
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.add_to_keyword_group -> {
                    collectSelectedKeywordsIncludingGroups { merged ->
                        if (merged.isNotEmpty()) {
                            showAddToKeywordGroupDialog(merged)
                        }
                    }
                    true
                }
                R.id.remove_from_current_keyword_group -> {
                    val groupId = historyViewModel.keywordGroupFilter.value
                    if (groupId >= 0L) {
                        collectSelectedKeywordsIncludingGroups { merged ->
                            if (merged.isNotEmpty()) {
                                removeKeywordsFromCurrentKeywordGroup(groupId, merged)
                            }
                        }
                    }
                    true
                }
                R.id.show_keywords_on_first_list -> {
                    val selected = historyAdapter.getSelectedKeywords()
                    if (selected.isNotEmpty()) {
                        visibleChildKeywords.addAll(selected)
                        persistHiddenStateToPrefs()
                        historyViewModel.setVisibleChildKeywordsFilter(visibleChildKeywords.toSet())
                    }
                    keywordActionMode?.finish()
                    true
                }
                R.id.hide_keywords_from_first_list -> {
                    val selected = historyAdapter.getSelectedKeywords()
                    if (selected.isNotEmpty()) {
                        visibleChildKeywords.removeAll(selected.toSet())
                        persistHiddenStateToPrefs()
                        historyViewModel.setVisibleChildKeywordsFilter(visibleChildKeywords.toSet())
                    }
                    keywordActionMode?.finish()
                    true
                }
                R.id.delete_keywords -> {
                    val selected = historyAdapter.getSelectedKeywords()
                    if (selected.isNotEmpty()) {
                        showDeleteKeywordsDialog(selected)
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            keywordActionMode = null
            historyAdapter.clearKeywordSelection()
            historyAdapter.clearKeywordGroupSelection()
            keywordGroupActionMode = null
            (activity as MainActivity).enableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = true }
        }
    }

    private val keywordGroupActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.keyword_group_menu_context, menu)
            mode.title = "${historyAdapter.getSelectedKeywordGroups().size} ${getString(R.string.selected)}"
            (activity as MainActivity).disableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = false }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val count = historyAdapter.getSelectedKeywordGroups().size
            menu?.findItem(R.id.rename_keyword_group)?.isVisible = count == 1
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.rename_keyword_group -> {
                    val selected = historyAdapter.getSelectedKeywordGroups()
                    if (selected.size == 1) {
                        showRenameKeywordGroupDialog(selected.first())
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.select_single_item), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.delete_keyword_group -> {
                    val selected = historyAdapter.getSelectedKeywordGroups()
                    if (selected.isNotEmpty()) {
                        showDeleteKeywordGroupsDialog(selected)
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            keywordGroupActionMode = null
            historyAdapter.clearKeywordSelection()
            historyAdapter.clearKeywordGroupSelection()
            keywordActionMode = null
            (activity as MainActivity).enableBottomNavigation()
            topAppBar.menu.forEach { it.isEnabled = true }
        }
    }

    override fun onKeywordSelected(keyword: String) {
        lastKeywordOriginGroupFilter = historyViewModel.keywordGroupFilter.value
        pushCurrentStateToNavigationStack()
        historyViewModel.setKeywordFilter(keyword)
        historyViewModel.setExcludedChildKeywordsFilter(emptySet())
        historyViewModel.setKeywordGroupFilter(-1L)
        if (historyViewModel.isKeywordSelectionMode.value) {
            historyViewModel.toggleKeywordSelectionMode()
        }
        if (historyViewModel.authorFilter.value.isNotEmpty()) {
            historyViewModel.setAuthorFilter("")
        }
        if (historyViewModel.youtuberGroupFilter.value >= 0L) {
            historyViewModel.setYoutuberGroupFilter(-1L)
        }
        if (historyViewModel.isYoutuberSelectionMode.value) {
            historyViewModel.toggleYoutuberSelectionMode()
        }
        if (historyViewModel.playlistFilter.value != -1L) {
            historyViewModel.setPlaylistFilter(-1L)
        }
        if (historyViewModel.playlistGroupFilter.value >= 0L) {
            historyViewModel.setPlaylistGroupFilter(-1L)
        }
        if (historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
        }
        requestScrollToTop()
    }

    override fun onKeywordLongClick(keywordInfo: com.ireum.ytdl.database.models.KeywordInfo) {
        showKeywordChildSelectionDialog(keywordInfo)
    }

    private fun showKeywordChildSelectionDialog(keywordInfo: com.ireum.ytdl.database.models.KeywordInfo) {
        val children = keywordInfo.childKeywords.distinct()
        if (children.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_results), Toast.LENGTH_SHORT).show()
            return
        }
        val excluded = historyViewModel.excludedChildKeywordsFilter.value
        val includeChildByDefault = historyViewModel.includeChildCategoryVideosFilter.value
        val checked = BooleanArray(children.size) { index ->
            if (!includeChildByDefault && excluded.isEmpty()) {
                false
            } else {
                !excluded.contains(children[index])
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(keywordInfo.keyword)
            .setMultiChoiceItems(children.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newExcluded = children
                    .filterIndexed { index, _ -> !checked[index] }
                    .toSet()
                historyViewModel.setExcludedChildKeywordsFilter(newExcluded)
            }
            .setNeutralButton("목록에 표시") { _, _ ->
                showKeywordChildVisibilityDialog(keywordInfo.keyword, children)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showKeywordChildVisibilityDialog(title: String, children: List<String>) {
        if (children.isEmpty()) return
        val checked = BooleanArray(children.size) { index ->
            visibleChildKeywords.contains(children[index])
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("$title - 목록 표시")
            .setMultiChoiceItems(children.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val selected = children
                    .filterIndexed { index, _ -> checked[index] }
                    .toSet()
                visibleChildKeywords.removeAll(children.toSet())
                visibleChildKeywords.addAll(selected)
                persistHiddenStateToPrefs()
                historyViewModel.setVisibleChildKeywordsFilter(visibleChildKeywords.toSet())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showYoutuberChildKeywordSelectionDialog(author: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val roots = historyViewModel.getRootKeywordInfosByAuthorForCurrentFilters(author)
            val rootKeywords = roots.map { it.keyword }.distinct()
            withContext(Dispatchers.Main) {
                if (rootKeywords.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.no_results), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val excluded = historyViewModel.excludedChildKeywordsFilter.value
                val includeChildByDefault = historyViewModel.includeChildCategoryVideosFilter.value
                val checked = BooleanArray(rootKeywords.size) { index ->
                    if (!includeChildByDefault && excluded.isEmpty()) {
                        false
                    } else {
                        !excluded.contains(rootKeywords[index])
                    }
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(author)
                    .setMultiChoiceItems(rootKeywords.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        val newExcluded = rootKeywords
                            .filterIndexed { index, _ -> !checked[index] }
                            .toSet()
                        historyViewModel.setExcludedChildKeywordsFilter(newExcluded)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun showYoutuberChildGroupVisibilityDialog(groupId: Long) {
        val groupName = youtuberGroupsCache.firstOrNull { it.id == groupId }?.name ?: groupId.toString()
        val childrenByParent = youtuberGroupRelationsCache
            .groupBy { it.parentGroupId }
            .mapValues { entry -> entry.value.map { it.childGroupId } }
        val descendants = mutableListOf<Pair<Long, Int>>()
        val queue = ArrayDeque<Pair<Long, Int>>()
        childrenByParent[groupId].orEmpty().forEach { childId -> queue.addLast(childId to 1) }
        val visited = linkedSetOf<Long>()
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            descendants.add(current to depth)
            childrenByParent[current].orEmpty().forEach { child -> queue.addLast(child to (depth + 1)) }
        }
        if (descendants.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_results), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = descendants.map { (id, depth) ->
            val name = youtuberGroupsCache.firstOrNull { it.id == id }?.name ?: id.toString()
            "${"ㄴ".repeat(depth)} $name"
        }
        val checked = BooleanArray(descendants.size) { index ->
            visibleChildYoutuberGroups.contains(descendants[index].first)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("$groupName - 목록 표시")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val descendantIds = descendants.map { it.first }.toSet()
                visibleChildYoutuberGroups.removeAll(descendantIds)
                descendants.forEachIndexed { index, pair ->
                    if (checked[index]) visibleChildYoutuberGroups.add(pair.first)
                }
                persistHiddenStateToPrefs()
                historyViewModel.setVisibleChildYoutuberGroupsFilter(visibleChildYoutuberGroups.toSet())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onKeywordSelectionChanged(selectedCount: Int) {
        val totalSelected = totalKeywordSelectionCount()
        if (totalSelected > 0) {
            if (keywordActionMode == null && keywordGroupActionMode == null) {
                keywordActionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(keywordActionBar)
            }
            val activeMode = keywordActionMode ?: keywordGroupActionMode
            activeMode?.title = "$totalSelected ${getString(R.string.selected)}"
            activeMode?.invalidate()
        } else {
            keywordActionMode?.finish()
            keywordGroupActionMode?.finish()
        }
    }

    override fun onKeywordGroupSelected(groupId: Long) {
        lastKeywordOriginGroupFilter = null
        pushCurrentStateToNavigationStack()
        historyViewModel.setKeywordGroupFilter(groupId)
        historyViewModel.setExcludedChildKeywordsFilter(emptySet())
        if (!historyViewModel.isKeywordSelectionMode.value) {
            historyViewModel.toggleKeywordSelectionMode()
        }
        if (historyViewModel.keywordFilter.value.isNotEmpty()) {
            historyViewModel.setKeywordFilter("")
        }
        if (historyViewModel.authorFilter.value.isNotEmpty()) {
            historyViewModel.setAuthorFilter("")
        }
        if (historyViewModel.youtuberGroupFilter.value >= 0L) {
            historyViewModel.setYoutuberGroupFilter(-1L)
        }
        if (historyViewModel.isYoutuberSelectionMode.value) {
            historyViewModel.toggleYoutuberSelectionMode()
        }
        if (historyViewModel.playlistFilter.value != -1L) {
            historyViewModel.setPlaylistFilter(-1L)
        }
        if (historyViewModel.playlistGroupFilter.value >= 0L) {
            historyViewModel.setPlaylistGroupFilter(-1L)
        }
        if (historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
        }
        requestScrollToTop()
    }

    override fun onKeywordGroupSelectionChanged(selectedCount: Int) {
        val totalSelected = totalKeywordSelectionCount()
        if (totalSelected > 0) {
            if (keywordActionMode == null && keywordGroupActionMode == null) {
                keywordGroupActionMode = (activity as AppCompatActivity?)!!.startSupportActionMode(keywordGroupActionBar)
            }
            val activeMode = keywordGroupActionMode ?: keywordActionMode
            activeMode?.title = "$totalSelected ${getString(R.string.selected)}"
            activeMode?.invalidate()
        } else {
            keywordActionMode?.finish()
            keywordGroupActionMode?.finish()
        }
    }

    override fun onPlaylistLongClick(playlistId: Long) {
        showPlaylistOptionsDialog(playlistId)
    }

    private fun showPlaylistOptionsDialog(playlistId: Long) {
        val options = arrayOf(
            getString(R.string.rename_playlist),
            getString(R.string.delete_playlist)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.playlist_options))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlistId)
                    1 -> showDeletePlaylistDialog(playlistId)
                }
            }
            .show()
    }

    private fun showDeletePlaylistDialog(playlistId: Long) {
        val playlistName = playlistsCache.firstOrNull { it.id == playlistId }?.name.orEmpty()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_playlist))
            .setMessage(getString(R.string.confirm_delete_playlist_desc, playlistName.ifBlank { getString(R.string.playlist) }))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                playlistViewModel.deletePlaylist(playlistId)
                if (historyViewModel.playlistFilter.value == playlistId) {
                    historyViewModel.setPlaylistFilter(-1L)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeletePlaylistsDialog(playlistIds: List<Long>) {
        if (playlistIds.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_playlist))
            .setMessage(getString(R.string.confirm_delete_playlists_desc, playlistIds.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                playlistIds.forEach { playlistViewModel.deletePlaylist(it) }
                if (playlistIds.contains(historyViewModel.playlistFilter.value)) {
                    historyViewModel.setPlaylistFilter(-1L)
                }
                playlistActionMode?.finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAddToPlaylistGroupDialog(playlistIds: List<Long>) {
        if (playlistIds.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val groups = db.playlistGroupDao.getGroups()
            withContext(Dispatchers.Main) {
                if (groups.isEmpty()) {
                    showCreatePlaylistGroupDialog { groupId ->
                        addPlaylistsToGroup(groupId, playlistIds)
                    }
                } else {
                    val names = mutableListOf<String>()
                    names.add(getString(R.string.new_group))
                    names.addAll(groups.map { it.name })
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.add_to_group))
                        .setItems(names.toTypedArray()) { _, which ->
                            if (which == 0) {
                                showCreatePlaylistGroupDialog { groupId ->
                                    addPlaylistsToGroup(groupId, playlistIds)
                                }
                            } else {
                                val group = groups[which - 1]
                                addPlaylistsToGroup(group.id, playlistIds)
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun removePlaylistsFromCurrentPlaylistGroup(groupId: Long, playlistIds: List<Long>) {
        if (groupId < 0L || playlistIds.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            db.playlistGroupDao.deleteMembersByGroupAndPlaylists(groupId, playlistIds)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), getString(R.string.ok), Toast.LENGTH_SHORT).show()
                playlistActionMode?.finish()
            }
        }
    }

    private fun showCreatePlaylistGroupDialog(onCreated: (Long) -> Unit) {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.group_name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.new_group))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = DBManager.getInstance(requireContext())
                    val existing = db.playlistGroupDao.getGroupByName(name)
                    val groupId = existing?.id ?: db.playlistGroupDao.insertGroup(
                        com.ireum.ytdl.database.models.PlaylistGroup(name = name)
                    )
                    if (groupId > 0) {
                        withContext(Dispatchers.Main) { onCreated(groupId) }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun addPlaylistsToGroup(groupId: Long, playlistIds: List<Long>) {
        if (groupId <= 0L || playlistIds.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val members = playlistIds.map { com.ireum.ytdl.database.models.PlaylistGroupMember(groupId, it) }
            db.playlistGroupDao.insertMembers(members)
            withContext(Dispatchers.Main) {
                playlistActionMode?.finish()
            }
        }
    }

    private fun showRenamePlaylistGroupDialog(groupId: Long) {
        val currentName = playlistGroupsCache.firstOrNull { it.id == groupId }?.name ?: ""
        val editText = EditText(requireContext()).apply {
            setText(currentName)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rename_group))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = DBManager.getInstance(requireContext())
                        db.playlistGroupDao.updateGroup(
                            com.ireum.ytdl.database.models.PlaylistGroup(id = groupId, name = newName)
                        )
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeletePlaylistGroupsDialog(groupIds: List<Long>) {
        if (groupIds.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_group))
            .setMessage(getString(R.string.confirm_delete_groups_desc, groupIds.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = DBManager.getInstance(requireContext())
                    groupIds.forEach { id ->
                        db.playlistGroupDao.deleteMembersByGroup(id)
                        db.playlistGroupDao.deleteGroup(id)
                    }
                }
                if (groupIds.contains(historyViewModel.playlistGroupFilter.value)) {
                    historyViewModel.setPlaylistGroupFilter(-1L)
                }
                playlistGroupActionMode?.finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditParentYoutuberGroupsDialog(groupId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val relations = db.youtuberGroupDao.getAllRelations()
            val childrenByParent = relations.groupBy { it.parentGroupId }.mapValues { entry ->
                entry.value.map { it.childGroupId }
            }
            val descendants = linkedSetOf<Long>()
            val stack = ArrayDeque<Long>()
            stack.add(groupId)
            while (stack.isNotEmpty()) {
                val current = stack.removeFirst()
                childrenByParent[current].orEmpty().forEach { childId ->
                    if (descendants.add(childId)) {
                        stack.addLast(childId)
                    }
                }
            }
            val groups = db.youtuberGroupDao.getGroups().filter { it.id != groupId && !descendants.contains(it.id) }
            if (groups.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.no_groups), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val parentIds = db.youtuberGroupDao.getParentIdsForChild(groupId).toSet()
            withContext(Dispatchers.Main) {
                val checked = BooleanArray(groups.size) { index -> parentIds.contains(groups[index].id) }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.edit_parent_groups))
                    .setMultiChoiceItems(groups.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        val selectedParentIds = groups.filterIndexed { index, _ -> checked[index] }.map { it.id }
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (selectedParentIds.isEmpty()) {
                                db.youtuberGroupDao.deleteRelationsForChild(groupId)
                            } else {
                                db.youtuberGroupDao.deleteRelationsForChildNotIn(groupId, selectedParentIds)
                                db.youtuberGroupDao.insertRelations(
                                    selectedParentIds.map { parentId ->
                                        com.ireum.ytdl.database.models.YoutuberGroupRelation(
                                            parentGroupId = parentId,
                                            childGroupId = groupId
                                        )
                                    }
                                )
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun showAddYoutuberGroupsToParentDialog(childGroupIds: List<Long>) {
        val targetChildren = childGroupIds.toSet()
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val relations = db.youtuberGroupDao.getAllRelations()
            val childrenByParent = relations.groupBy { it.parentGroupId }.mapValues { entry ->
                entry.value.map { it.childGroupId }
            }
            fun descendants(start: Long): Set<Long> {
                val out = linkedSetOf<Long>()
                val stack = ArrayDeque<Long>()
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val current = stack.removeFirst()
                    childrenByParent[current].orEmpty().forEach { child ->
                        if (out.add(child)) stack.addLast(child)
                    }
                }
                return out
            }
            val groups = db.youtuberGroupDao.getGroups().filter { candidate ->
                !targetChildren.contains(candidate.id) &&
                    targetChildren.none { childId -> descendants(childId).contains(candidate.id) }
            }
            withContext(Dispatchers.Main) {
                if (groups.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.no_groups), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val names = ArrayList<String>()
                names.add(getString(R.string.new_group))
                val groupRows = buildYoutuberGroupRows(groups, relations)
                names.addAll(groupRows.map { it.second })
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.add_to_group))
                    .setItems(names.toTypedArray()) { _, which ->
                        if (which == 0) {
                            showCreateYoutuberGroupDialog(
                                onCreated = { parentId ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val rels = targetChildren
                                            .filter { it != parentId }
                                            .map {
                                                com.ireum.ytdl.database.models.YoutuberGroupRelation(
                                                    parentGroupId = parentId,
                                                    childGroupId = it
                                                )
                                            }
                                        if (rels.isNotEmpty()) db.youtuberGroupDao.insertRelations(rels)
                                        withContext(Dispatchers.Main) {
                                            youtuberGroupActionMode?.finish()
                                        }
                                    }
                                },
                                parentGroupId = null
                            )
                        } else {
                            val parentId = groupRows[which - 1].first.id
                            lifecycleScope.launch(Dispatchers.IO) {
                                val rels = targetChildren
                                    .filter { it != parentId }
                                    .map {
                                        com.ireum.ytdl.database.models.YoutuberGroupRelation(
                                            parentGroupId = parentId,
                                            childGroupId = it
                                        )
                                    }
                                if (rels.isNotEmpty()) db.youtuberGroupDao.insertRelations(rels)
                                withContext(Dispatchers.Main) {
                                    youtuberGroupActionMode?.finish()
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun showAddToKeywordGroupDialog(keywords: List<String>) {
        if (keywords.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val groups = db.keywordGroupDao.getGroups()
            withContext(Dispatchers.Main) {
                if (groups.isEmpty()) {
                    showCreateKeywordGroupDialog { groupId ->
                        addKeywordsToGroup(groupId, keywords)
                    }
                } else {
                    val names = mutableListOf<String>()
                    names.add(getString(R.string.new_group))
                    names.addAll(groups.map { it.name })
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.add_to_group))
                        .setItems(names.toTypedArray()) { _, which ->
                            if (which == 0) {
                                showCreateKeywordGroupDialog { groupId ->
                                    addKeywordsToGroup(groupId, keywords)
                                }
                            } else {
                                val group = groups[which - 1]
                                addKeywordsToGroup(group.id, keywords)
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun removeKeywordsFromCurrentKeywordGroup(groupId: Long, keywords: List<String>) {
        if (groupId < 0L || keywords.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            db.keywordGroupDao.deleteMembersByGroupAndKeywords(groupId, keywords)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), getString(R.string.ok), Toast.LENGTH_SHORT).show()
                keywordActionMode?.finish()
            }
        }
    }

    private fun showCreateKeywordGroupDialog(onCreated: (Long) -> Unit) {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.group_name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.new_group))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = DBManager.getInstance(requireContext())
                    val existing = db.keywordGroupDao.getGroupByName(name)
                    val groupId = existing?.id ?: db.keywordGroupDao.insertGroup(
                        com.ireum.ytdl.database.models.KeywordGroup(name = name)
                    )
                    if (groupId > 0) {
                        withContext(Dispatchers.Main) { onCreated(groupId) }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun addKeywordsToGroup(groupId: Long, keywords: List<String>) {
        if (groupId <= 0L || keywords.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = DBManager.getInstance(requireContext())
            val members = keywords.map { com.ireum.ytdl.database.models.KeywordGroupMember(groupId, it) }
            db.keywordGroupDao.insertMembers(members)
            withContext(Dispatchers.Main) {
                keywordActionMode?.finish()
            }
        }
    }

    private fun showRenameKeywordGroupDialog(groupId: Long) {
        val currentName = keywordGroupsCache.firstOrNull { it.id == groupId }?.name ?: ""
        val editText = EditText(requireContext()).apply {
            setText(currentName)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rename_group))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = DBManager.getInstance(requireContext())
                        db.keywordGroupDao.updateGroup(
                            com.ireum.ytdl.database.models.KeywordGroup(id = groupId, name = newName)
                        )
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeleteKeywordGroupsDialog(groupIds: List<Long>) {
        if (groupIds.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_group))
            .setMessage(getString(R.string.confirm_delete_groups_desc, groupIds.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = DBManager.getInstance(requireContext())
                    groupIds.forEach { id ->
                        db.keywordGroupDao.deleteMembersByGroup(id)
                        db.keywordGroupDao.deleteGroup(id)
                    }
                }
                if (groupIds.contains(historyViewModel.keywordGroupFilter.value)) {
                    historyViewModel.setKeywordGroupFilter(-1L)
                }
                keywordGroupActionMode?.finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeleteKeywordsDialog(keywords: List<String>) {
        if (keywords.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_keywords))
            .setMessage(getString(R.string.confirm_delete_keywords_desc, keywords.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                historyViewModel.removeKeywordsFromAllHistory(keywords)
                if (keywords.any { it.equals(historyViewModel.keywordFilter.value, ignoreCase = true) }) {
                    historyViewModel.setKeywordFilter("")
                }
                visibleChildKeywords.removeAll(keywords.toSet())
                persistHiddenStateToPrefs()
                historyViewModel.setVisibleChildKeywordsFilter(visibleChildKeywords.toSet())
                keywordActionMode?.finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRenameYoutuberGroupDialog(groupId: Long) {
        val currentName = youtuberGroupsCache.firstOrNull { it.id == groupId }?.name ?: ""
        val editText = EditText(requireContext()).apply {
            setText(currentName)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rename_group))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = DBManager.getInstance(requireContext())
                        db.youtuberGroupDao.updateGroup(
                            com.ireum.ytdl.database.models.YoutuberGroup(id = groupId, name = newName)
                        )
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeleteYoutuberGroupsDialog(groupIds: List<Long>) {
        if (groupIds.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_group))
            .setMessage(getString(R.string.confirm_delete_groups_desc, groupIds.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = DBManager.getInstance(requireContext())
                    groupIds.forEach { id ->
                        db.youtuberGroupDao.deleteMembersByGroup(id)
                        db.youtuberGroupDao.deleteRelationsByGroup(id)
                        db.youtuberGroupDao.deleteGroup(id)
                    }
                }
                if (groupIds.contains(historyViewModel.youtuberGroupFilter.value)) {
                    historyViewModel.setYoutuberGroupFilter(-1L)
                }
                hiddenYoutuberGroups.removeAll(groupIds.toSet())
                visibleChildYoutuberGroups.removeAll(groupIds.toSet())
                persistHiddenStateToPrefs()
                historyViewModel.setHiddenYoutuberGroupsFilter(hiddenYoutuberGroups)
                historyViewModel.setVisibleChildYoutuberGroupsFilter(visibleChildYoutuberGroups.toSet())
                youtuberGroupActionMode?.finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun handleHistoryBack(): Boolean {
        when {
            actionMode != null -> {
                actionMode?.finish()
                return true
            }
            youtuberActionMode != null -> {
                youtuberActionMode?.finish()
                return true
            }
            youtuberGroupActionMode != null -> {
                youtuberGroupActionMode?.finish()
                return true
            }
            playlistActionMode != null -> {
                playlistActionMode?.finish()
                return true
            }
            playlistGroupActionMode != null -> {
                playlistGroupActionMode?.finish()
                return true
            }
            keywordActionMode != null -> {
                keywordActionMode?.finish()
                return true
            }
            keywordGroupActionMode != null -> {
                keywordGroupActionMode?.finish()
                return true
            }
        }

        if (navigationBackStack.isNotEmpty()) {
            val entry = navigationBackStack.removeLast()
            isRestoringFromNavigationBack = true
            suppressAutoScrollForNextScreenChange = true
            pendingScrollToTop = false
            pendingRestoreEntry = entry
            applyNavigationState(entry.state)
            schedulePendingRestoreRetry()
            return true
        }

        if (historyViewModel.authorFilter.value.isNotBlank()) {
            val originGroup = lastYoutuberOriginGroupFilter
            historyViewModel.setAuthorFilter("")
            if (originGroup != null) {
                historyViewModel.setYoutuberGroupFilter(originGroup)
                lastYoutuberOriginGroupFilter = null
            }
            if (!historyViewModel.isYoutuberSelectionMode.value) {
                historyViewModel.toggleYoutuberSelectionMode()
            }
            requestScrollToTop()
            return true
        }
        if (historyViewModel.youtuberGroupFilter.value >= 0L) {
            historyViewModel.setYoutuberGroupFilter(-1L)
            if (!historyViewModel.isYoutuberSelectionMode.value) {
                historyViewModel.toggleYoutuberSelectionMode()
            }
            requestScrollToTop()
            return true
        }
        if (historyViewModel.isYoutuberSelectionMode.value) {
            historyViewModel.toggleYoutuberSelectionMode()
            requestScrollToTop()
            return true
        }
        if (historyViewModel.playlistFilter.value != -1L) {
            historyViewModel.setPlaylistFilter(-1L)
            if (!historyViewModel.isPlaylistSelectionMode.value) {
                historyViewModel.togglePlaylistSelectionMode()
            }
            requestScrollToTop()
            return true
        }
        if (historyViewModel.playlistGroupFilter.value >= 0L) {
            historyViewModel.setPlaylistGroupFilter(-1L)
            if (!historyViewModel.isPlaylistSelectionMode.value) {
                historyViewModel.togglePlaylistSelectionMode()
            }
            requestScrollToTop()
            return true
        }
        if (historyViewModel.isPlaylistSelectionMode.value) {
            historyViewModel.togglePlaylistSelectionMode()
            requestScrollToTop()
            return true
        }
        if (historyViewModel.keywordFilter.value.isNotBlank()) {
            val originGroup = lastKeywordOriginGroupFilter
            historyViewModel.setKeywordFilter("")
            if (originGroup != null) {
                historyViewModel.setKeywordGroupFilter(originGroup)
                lastKeywordOriginGroupFilter = null
            }
            if (!historyViewModel.isKeywordSelectionMode.value) {
                historyViewModel.toggleKeywordSelectionMode()
            }
            requestScrollToTop()
            return true
        }
        if (historyViewModel.keywordGroupFilter.value >= 0L) {
            historyViewModel.setKeywordGroupFilter(-1L)
            if (!historyViewModel.isKeywordSelectionMode.value) {
                historyViewModel.toggleKeywordSelectionMode()
            }
            requestScrollToTop()
            return true
        }
        if (historyViewModel.isKeywordSelectionMode.value) {
            historyViewModel.toggleKeywordSelectionMode()
            requestScrollToTop()
            return true
        }
        if (historyViewModel.isRecentMode.value) {
            historyViewModel.setRecentMode(false)
            requestScrollToTop()
            return true
        }
        return false
    }
}
