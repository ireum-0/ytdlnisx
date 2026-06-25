package com.ireum.ytdl

import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.graphics.Color
import android.graphics.Rect
import android.widget.ImageButton
import android.widget.Toast
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.Metadata
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.preference.PreferenceManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.database.viewmodel.HistoryViewModel
import com.ireum.ytdl.ui.adapter.VideoQueueAdapter
import com.ireum.ytdl.ui.downloads.HistoryFragment
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.session.MediaButtonReceiver
import android.media.audiofx.LoudnessEnhancer
import android.widget.AutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var speedLabel: AppCompatTextView? = null
    private var chaptersLabel: AppCompatTextView? = null
    private var titleView: AppCompatTextView? = null
    private var authorView: AppCompatTextView? = null
    private var pipButton: ImageButton? = null
    private var aspectButton: ImageButton? = null
    private var subtitlesButton: ImageButton? = null
    private var rotateButton: ImageButton? = null
    private var moreButton: ImageButton? = null
    private var lockButton: ImageButton? = null
    private var queueTitle: TextView? = null
    private var currentArtworkKey: String? = null
    private var seekOverlay: android.view.View? = null
    private var seekTime: TextView? = null
    private var seekDelta: TextView? = null
    private var valueOverlay: android.view.View? = null
    private var valueText: TextView? = null
    private var gestureOverlayBg: android.view.View? = null
    private var holdSpeedOverlay: TextView? = null
    private var leftBarOverlay: android.view.View? = null
    private var leftBarFill: android.view.View? = null
    private var leftBarContainer: android.view.View? = null
    private var rightBarOverlay: android.view.View? = null
    private var rightBarFill: android.view.View? = null
    private var rightBarContainer: android.view.View? = null
    private var timeBar: androidx.media3.ui.PlayerControlView? = null
    private var progressBar: DefaultTimeBar? = null
    private var repeatButton: ImageButton? = null
    private var shuffleButton: ImageButton? = null
    private var playerContainer: android.view.View? = null
    private var playerTopBar: View? = null
    private var playerBottomArea: View? = null
    private var lastControllerVisibility: Int = android.view.View.VISIBLE
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var overlayHideRunnable: Runnable? = null
    private val playbackStateHandler = Handler(Looper.getMainLooper())
    private var playbackStateUpdater: Runnable? = null
    private var controlsHiddenByGesture: Boolean = false
    private var audioManager: AudioManager? = null
    private var maxVolume: Int = 0
    private var initialVolume: Int = 0
    private var initialVolumePercent: Int = 0
    private var initialBrightness: Float = 0.5f
    private var touchStartY: Float = 0f
    private var touchStartX: Float = 0f
    private var adjusting: Boolean = false
    private var gestureDetector: GestureDetector? = null
    private var seeking: Boolean = false
    private var activeSwipeGesture: SwipeGestureType = SwipeGestureType.NONE
    private var triggeredCentralSwipeAction: Boolean = false
    private var consumedGestureInteraction: Boolean = false
    private var initialSeekPosition: Long = 0L
    private var holdSpeedRunnable: Runnable? = null
    private var holdSpeedActive: Boolean = false
    private var holdSpeedOriginal: Float = 1.0f
    private var touchSlop: Int = 0
    private var queueList: RecyclerView? = null
    private var queueAdapter: VideoQueueAdapter? = null
    private var queueItems: List<HistoryItem> = emptyList()
    private var baseQueueItems: List<HistoryItem> = emptyList()
    private val queuePlayablePathById: MutableMap<Long, String> = mutableMapOf()
    private val queueMediaUriById: MutableMap<Long, Uri> = mutableMapOf()
    private val queueIdByUri: MutableMap<String, Long> = mutableMapOf()
    private val queueIndexById: MutableMap<Long, Int> = mutableMapOf()
    private var autoScrollQueueToCurrent = true
    private var isShuffled: Boolean = false
    private var queueHeader: android.view.View? = null
    private val playbackPositionsById: MutableMap<Long, Long> = mutableMapOf()
    private var isBackgroundPlayback: Boolean = false
    private var pendingAutoPipOnLeave: Boolean = false
    private var wasInPip: Boolean = false
    private var controlsLocked: Boolean = false
    private var subtitleStyle: CaptionStyleCompat = CaptionStyleCompat.DEFAULT
    private var subtitleTextSizeFraction: Float = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
    private var subtitleApplyEmbeddedStyles: Boolean = true
    private var subtitleApplyEmbeddedFontSizes: Boolean = true
    private var mediaSession: MediaSessionCompat? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var customPlaybackSpeed: Float? = null
    private var holdPlaybackSpeed: Float = 2.0f
    private val recentWatchHandler = Handler(Looper.getMainLooper())
    private var recentWatchRunnable: Runnable? = null
    private var recentWatchStartMs: Long = 0L
    private var recentWatchHistoryId: Long? = null
    private var recentWatchUpdated: Boolean = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSessionId: Int = 0
    private var volumeNormalizationEnabled: Boolean = false
    private var pendingThumbItem: HistoryItem? = null
    private var pendingThumbCallback: ((String) -> Unit)? = null
    private var isEditVideoInfoDialogVisible: Boolean = false
    private var launchHistoryId: Long? = null
    private var launchPlaybackPositionMs: Long? = null
    private var skipNextPlaybackRestoreHistoryId: Long? = null
    private var playbackStartedAtMs: Long = 0L
    private var currentChapters: List<VideoChapter> = emptyList()
    private var suppressAutoPipForBackNavigation: Boolean = false
    private var backNavigationInProgress: Boolean = false
    private var lastKnownOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var orientationChangedDuringPlayback: Boolean = false
    private val downloadViewModel by lazy { ViewModelProvider(this)[DownloadViewModel::class.java] }
    private val promptedCompatibleRedownloadIds = mutableSetOf<Long>()

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
                    Toast.makeText(this@VideoPlayerActivity, R.string.error_saving_thumbnail, Toast.LENGTH_SHORT).show()
                } else {
                    onComplete(savedPath)
                }
            }
        }
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        lastKnownOrientation = resources.configuration.orientation
        configurePlaybackWindow()
        onBackPressedDispatcher.addCallback(this) {
            if (backNavigationInProgress) {
                return@addCallback
            }
            if (handleBackToHistoryIfNeeded()) {
                return@addCallback
            }
            backNavigationInProgress = true
            suppressAutoPipForBackNavigation = true
            pendingAutoPipOnLeave = false
            isBackgroundPlayback = false
            finish()
        }

        val playerView = findViewById<PlayerView>(R.id.player_view)
        this.playerView = playerView
        if (!intent.hasExtra("video_path")) {
            Log.w("VideoPlayerActivity", "videoPath is null or blank")
            return
        }

        speedLabel = playerView.findViewById(R.id.btn_speed)
        chaptersLabel = playerView.findViewById(R.id.btn_chapters)
        titleView = playerView.findViewById(R.id.player_title)
        authorView = playerView.findViewById(R.id.player_author)
        pipButton = playerView.findViewById(R.id.btn_pip)
        aspectButton = playerView.findViewById(R.id.btn_aspect)
        subtitlesButton = playerView.findViewById(R.id.btn_subtitles)
        rotateButton = playerView.findViewById(R.id.btn_rotate)
        moreButton = playerView.findViewById(R.id.btn_more)
        lockButton = playerView.findViewById(R.id.btn_lock)
        queueList = findViewById(R.id.video_queue_list)
        queueTitle = findViewById(R.id.queue_title)
        queueHeader = findViewById(R.id.queue_header)
        playerContainer = findViewById(R.id.player_container)
        repeatButton = findViewById(R.id.btn_repeat_mode)
        shuffleButton = findViewById(R.id.btn_shuffle_queue)
        playerTopBar = playerView.findViewById(R.id.player_top_bar)
        playerBottomArea = playerView.findViewById(R.id.player_bottom_area)
        if (queueList != null) {
            queueAdapter = VideoQueueAdapter(this) { item ->
                val path = queuePlayablePathById[item.id]
                    ?: item.downloadPath.firstOrNull { FileUtil.exists(it) }
                    ?: item.downloadPath.firstOrNull()
                if (path != null) {
                    val index = findPlayerMediaItemIndex(item.id)
                    if (index >= 0) {
                        autoScrollQueueToCurrent = true
                        queueAdapter?.setCurrentItemId(item.id)
                        player?.seekTo(index, 0L)
                        player?.playWhenReady = true
                        scrollCurrentToTopIfAllowed(item.id)
                    } else {
                        playSinglePath(path)
                    }
                }
            }
            queueList!!.layoutManager = LinearLayoutManager(this)
            queueList!!.adapter = queueAdapter
            queueList!!.itemAnimator = null
            queueAdapter?.setCurrentItemId(null)
            queueList!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        autoScrollQueueToCurrent = false
                    }
                }
            })
        }
        seekOverlay = findViewById(R.id.seek_overlay)
        seekTime = findViewById(R.id.seek_time)
        seekDelta = findViewById(R.id.seek_delta)
        valueOverlay = findViewById(R.id.value_overlay)
        valueText = findViewById(R.id.value_text)
        gestureOverlayBg = findViewById(R.id.gesture_overlay_bg)
        holdSpeedOverlay = findViewById(R.id.hold_speed_overlay)
        leftBarOverlay = findViewById(R.id.left_bar_overlay)
        leftBarFill = findViewById(R.id.left_bar_fill)
        leftBarContainer = findViewById(R.id.left_bar_container)
        rightBarOverlay = findViewById(R.id.right_bar_overlay)
        rightBarFill = findViewById(R.id.right_bar_fill)
        rightBarContainer = findViewById(R.id.right_bar_container)
        timeBar = findViewById(R.id.player_time_bar)
        progressBar = timeBar?.findViewById(R.id.exo_progress)

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            exoPlayer.setAudioAttributes(audioAttributes, true)
            exoPlayer.setHandleAudioBecomingNoisy(true)
            runCatching { exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK) }
        }
        setupMediaNotification()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        volumeNormalizationEnabled = prefs.getBoolean(PREF_VOLUME_NORMALIZATION, false)
        player?.repeatMode = prefs.getInt(PREF_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        ensureVolumeNormalization()

        timeBar?.player = player
        initTimeBarScrubbing()
        repeatButton?.setOnClickListener { toggleRepeatMode() }
        shuffleButton?.setOnClickListener {
            if (isShuffled) {
                reshuffleQueue()
            } else {
                enableShuffleQueue()
            }
        }
        shuffleButton?.setOnLongClickListener {
            if (isShuffled) {
                disableShuffleQueue()
                true
            } else {
                false
            }
        }
        updateRepeatButton()
        updateShuffleButton()
        initChaptersControl()
        initSpeedControl()
        initMoreMenu()
        initAspectControl(playerView)
        initSubtitlesControl(playerView)
        initRotateControl()
        initLockControl()
        initGestureControls(playerView)
        loadSubtitlePreferences()
        applySubtitleStyle()
        initPipControl()
        handlePlaybackIntent(intent, replaceCurrent = true)

        applyOrientationUi()
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            lastControllerVisibility = visibility
            if (isLandscapeMode()) {
                timeBar?.visibility = visibility
            }
        })
        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMediaSessionPlaybackState()
                updateMediaSessionMetadata()
                val currentUri = mediaItem?.localConfiguration?.uri
                if (currentUri != null) {
                    updateTitleFromPath(currentUri.toString())
                }
                refreshChaptersForCurrentItem()
                updateCurrentQueueSelection(
                    scrollToCurrent = true,
                    resetRecentWatchTimer = true,
                    forceScrollToCurrentTop = true
                )
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    restorePlaybackPositionForCurrentItem()
                }
                playerNotificationManager?.invalidate()
                updateSubtitlesButtonState()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                updateMediaSessionPlaybackState()
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    val oldMediaItem = player?.getMediaItemAt(oldPosition.mediaItemIndex)
                    val oldHistoryId = resolveHistoryIdForMediaItem(oldMediaItem)
                    if (oldHistoryId != null) {
                        val durationMs = getDurationMsForHistoryId(oldHistoryId)
                        val safePosition = if (durationMs > 0 && oldPosition.positionMs >= durationMs - 5_000L) 0L else oldPosition.positionMs
                        savePlaybackPositionForHistoryId(oldHistoryId, safePosition)
                    }
                    updateCurrentQueueSelection(
                        scrollToCurrent = true,
                        resetRecentWatchTimer = true,
                        forceScrollToCurrentTop = true
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionPlaybackState()
                updatePlaybackUiProgress()
                logPlaybackTiming("onPlaybackStateChanged state=$playbackState current=${player?.currentMediaItemIndex ?: -1}")
                if (playbackState == Player.STATE_READY) {
                    updateMediaSessionMetadata()
                    ensureVolumeNormalization()
                }
                if (playbackState == Player.STATE_ENDED) {
                    val currentHistoryId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
                    if (currentHistoryId != null) {
                        savePlaybackPositionForHistoryId(currentHistoryId, 0L)
                    }
                }
                if (isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    updatePipActions()
                }
                playerNotificationManager?.invalidate()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionPlaybackState()
                if (isPlaying && playbackStartedAtMs == 0L) {
                    playbackStartedAtMs = SystemClock.elapsedRealtime()
                }
                if (!isPlaying) {
                    playbackStartedAtMs = 0L
                }
                logPlaybackTiming("onIsPlayingChanged isPlaying=$isPlaying current=${player?.currentMediaItemIndex ?: -1}")
                if (isPlaying) {
                    startPlaybackStateUpdates()
                    updateCurrentQueueSelection(scrollToCurrent = false, resetRecentWatchTimer = false)
                    ensureRecentWatchTarget()
                    startRecentWatchTimer()
                } else {
                    stopPlaybackStateUpdates()
                    stopRecentWatchTimer()
                }
                if (isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    updatePipActions()
                }
                playerNotificationManager?.invalidate()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                logPlaybackTiming("onTimelineChanged reason=$reason windows=${timeline.windowCount}")
                updateMediaSessionMetadata()
                playerNotificationManager?.invalidate()
            }

            override fun onTracksChanged(tracks: Tracks) {
                logPlaybackTiming("onTracksChanged groups=${tracks.groups.size}")
                updateSubtitlesButtonState()
                refreshChaptersForCurrentItem()
            }

            override fun onRenderedFirstFrame() {
                logPlaybackTiming("onRenderedFirstFrame current=${player?.currentMediaItemIndex ?: -1}")
            }

            override fun onPlayerError(error: PlaybackException) {
                logPlaybackTiming("onPlayerError code=${error.errorCodeName} message=${error.message}")
                maybeOfferCompatibleRedownload(error)
            }
        })
        updateSubtitlesButtonState()
    }

    private fun maybeOfferCompatibleRedownload(error: PlaybackException) {
        if (!isLikelyUnsupportedVideoPlayback(error)) return

        val historyId = resolveHistoryIdForMediaItem(player?.currentMediaItem) ?: launchHistoryId ?: return
        if (!promptedCompatibleRedownloadIds.add(historyId)) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Unsupported video format")
            .setMessage("This video exceeds this device's playback support. Re-download a compatible version while keeping the same resolution when possible?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Re-download") { _, _ ->
                queueCompatibleRedownload(historyId)
            }
            .show()
    }

    private fun queueCompatibleRedownload(historyId: Long) {
        lifecycleScope.launch {
            val historyItem = withContext(Dispatchers.IO) {
                runCatching { DBManager.getInstance(this@VideoPlayerActivity).historyDao.getItem(historyId) }.getOrNull()
            }
            if (historyItem == null) {
                Toast.makeText(this@VideoPlayerActivity, "Unable to find the original download.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            runCatching {
                val downloadItem = downloadViewModel.createDownloadItemFromHistory(
                    historyItem = historyItem,
                    resolveSubtitleAvailability = false,
                    preferCompatibleVideo = true
                )
                val result = downloadViewModel.queueDownloads(listOf(downloadItem), ignoreDuplicates = true)
                if (!result.succeeded) {
                    throw IllegalStateException(result.message)
                }
            }.onSuccess {
                Toast.makeText(
                    this@VideoPlayerActivity,
                    "Queued a compatible re-download for this video.",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                Log.e("VideoPlayerActivity", "Failed to queue compatible redownload historyId=$historyId", error)
                val message = error.localizedMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: "Failed to queue a compatible re-download."
                Toast.makeText(
                    this@VideoPlayerActivity,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun isLikelyUnsupportedVideoPlayback(error: PlaybackException): Boolean {
        val details = buildString {
            append(error.message.orEmpty())
            var current: Throwable? = error
            repeat(6) {
                current = current?.cause
                if (current == null) return@repeat
                append('\n')
                append(current?.javaClass?.name.orEmpty())
                append(':')
                append(current?.message.orEmpty())
            }
        }.lowercase(Locale.US)

        val codecProblem = details.contains("decoder init failed") ||
            details.contains("no_exceeds_capabilities") ||
            details.contains("exceeds_capabilities") ||
            details.contains("mediacodecvideorenderer error")
        val videoProblem = details.contains("video/") ||
            details.contains("vp9") ||
            details.contains("av01") ||
            details.contains("h265") ||
            details.contains("hevc")
        return codecProblem && videoProblem
    }

    private fun configurePlaybackWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.video_player_root)) { _, insets ->
            applyWindowInsets()
            insets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun handlePlaybackIntent(playbackIntent: Intent, replaceCurrent: Boolean) {
        val videoPath = playbackIntent.getStringExtra("video_path")
        if (videoPath.isNullOrBlank()) return
        val isResumePlaybackUiIntent = playbackIntent.action == ACTION_RESUME_PLAYBACK_UI

        launchHistoryId = playbackIntent.getLongExtra("history_id", -1L).takeIf { it > 0L }
        val requestedPlaybackPositionMs =
            playbackIntent.getLongExtra("playback_position_ms", 0L).takeIf { it >= 5_000L }

        val uri = when {
            videoPath.startsWith("content://") -> Uri.parse(videoPath)
            videoPath.startsWith("file://") -> Uri.parse(videoPath)
            else -> {
                val file = File(videoPath)
                val documentUri = buildDocumentUriForPath(videoPath)
                if ((!file.exists() || !file.isFile) && documentUri == null) {
                    Toast.makeText(this, "Invalid video path: $videoPath", Toast.LENGTH_SHORT).show()
                    return
                }
                documentUri ?: Uri.fromFile(file)
            }
        }

        val playlistPaths = playbackIntent.getStringArrayListExtra("video_paths")
        val shouldBuildQueue = shouldBuildQueue(playbackIntent, playlistPaths)
        val requestedUriString = uri.toString()
        val currentUriString = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        val currentHistoryId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        val isSameAsCurrentPlayback =
            (player?.mediaItemCount ?: 0) > 0 && (
                (launchHistoryId != null && currentHistoryId != null && launchHistoryId == currentHistoryId) ||
                    (currentUriString != null && currentUriString == requestedUriString)
            )
        launchPlaybackPositionMs = if (isSameAsCurrentPlayback) {
            player?.currentPosition?.takeIf { it >= 5_000L }
        } else {
            requestedPlaybackPositionMs
        }
        logPlaybackPosition(
            "handlePlaybackIntent replaceCurrent=$replaceCurrent same=$isSameAsCurrentPlayback " +
                "requested=$requestedPlaybackPositionMs launch=$launchPlaybackPositionMs " +
                "current=${player?.currentPosition ?: -1L} historyId=$launchHistoryId uri=$requestedUriString"
        )

        if (replaceCurrent && isResumePlaybackUiIntent && (player?.mediaItemCount ?: 0) > 0) {
            updateTitleFromPath(currentUriString ?: requestedUriString, preferredHistoryId = currentHistoryId ?: launchHistoryId)
            updateCurrentQueueSelection(
                scrollToCurrent = true,
                resetRecentWatchTimer = false,
                forceScrollToCurrentTop = true
            )
            updatePlaybackUiProgress()
            refreshPipParams("handlePlaybackIntent_resumeUi")
            return
        }

        if (replaceCurrent) {
            savePlaybackPositionForCurrentItem()
            commitRecentWatchIfEligible()
            stopRecentWatchTimer()
            autoScrollQueueToCurrent = true
            isShuffled = false
            updateShuffleButton()
            queueAdapter?.setCurrentItemId(null)
            if (isSameAsCurrentPlayback) {
                updateTitleFromPath(requestedUriString, preferredHistoryId = launchHistoryId)
                updateCurrentQueueSelection(
                    scrollToCurrent = true,
                    resetRecentWatchTimer = false,
                    forceScrollToCurrentTop = true
                )
                updatePlaybackUiProgress()
                refreshPipParams("handlePlaybackIntent_samePlayback")
                return
            }
            if (!shouldBuildQueue) {
                playSinglePath(videoPath, launchHistoryId, launchPlaybackPositionMs ?: 0L)
            }
        }

        updateTitleFromPath(uri.toString(), preferredHistoryId = launchHistoryId)
        if (!playlistPaths.isNullOrEmpty()) {
            loadQueueFromPaths(playlistPaths, videoPath)
        } else if (shouldBuildQueue) {
            loadQueueForContext(videoPath)
        }
        refreshPipParams("handlePlaybackIntent")
    }

    private fun shouldBuildQueue(playbackIntent: Intent, playlistPaths: ArrayList<String>?): Boolean {
        if (!playlistPaths.isNullOrEmpty()) return true
        return playbackIntent.hasExtra("context_prefetched_history_ids") ||
            playbackIntent.hasExtra("context_author") ||
            playbackIntent.hasExtra("context_playlist_id") ||
            playbackIntent.hasExtra("context_keyword") ||
            playbackIntent.hasExtra("context_query") ||
            playbackIntent.hasExtra("context_status") ||
            playbackIntent.hasExtra("context_sort_type")
    }

    private fun loadQueueFromPaths(paths: List<String>, startPath: String) {
        if (queueList == null) return
        autoScrollQueueToCurrent = true

        // 큐 제목(원하면 더 예쁘게)
        queueTitle?.text = "재생 목록"

        lifecycleScope.launch {
            val db = DBManager.getInstance(this@VideoPlayerActivity)

            // 1) 큐 아이템 만들기: DB에 있으면 HistoryItem 그대로 쓰고, 없으면 임시 HistoryItem 생성
            val items: List<HistoryItem> = withContext(Dispatchers.IO) {
                paths.mapIndexed { index, p ->
                    val normalized = p
                    val found = db.historyDao.getItemByDownloadPath(normalized) // existing 함수 사용
                    found ?: makeTempHistoryItem(normalized, index)
                }
            }

            baseQueueItems = items
            isShuffled = false
            updateShuffleButton()
            val startUri = uriFromPath(startPath).toString()
            val startId = items.firstOrNull { item ->
                item.downloadPath.any { path -> uriFromPath(path).toString() == startUri }
            }?.id ?: items.firstOrNull()?.id
            applyQueueOrder(
                items = items,
                currentItemId = startId,
                currentPos = resolveQueueStartPositionFromItems(items, startId, launchPlaybackPositionMs),
                playWhenReady = true,
                forceScrollCurrentToTop = true
            )
        }
    }

    private fun makeTempHistoryItem(path: String, index: Int): HistoryItem {
        val name = try {
            if (path.startsWith("content://") || path.startsWith("file://")) path.substringAfterLast('/')
            else File(path).name
        } catch (_: Exception) {
            path.substringAfterLast('/')
        }
        val title = name.substringBeforeLast('.')

        return HistoryItem(
            id = -1L - index,               // 임시 고유 ID (DB PK랑 안 겹치게 음수)
            url = path,
            title = title,
            author = "",
            artist = "",
            duration = "",
            durationSeconds = 0L,
            thumb = "",
            type = if (isAudioPath(path)) DownloadType.audio else DownloadType.video,
            time = 0L,
            downloadPath = listOf(path),
            website = "",
            format = com.ireum.ytdl.database.models.Format(),
            filesize = 0L,
            downloadId = 0L,
            command = "",
            playbackPositionMs = 0L,
            localTreeUri = "",
            localTreePath = ""
        )
    }

    private fun HistoryItem.isPlayableInQueue(): Boolean {
        return type == DownloadType.video || type == DownloadType.audio
    }

    private fun isAudioPath(path: String): Boolean {
        val cleanPath = path.substringBefore('?').substringBefore('#')
        val ext = cleanPath.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in setOf("aac", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "weba", "wma")
    }

    override fun onResume() {
        super.onResume()
        suppressAutoPipForBackNavigation = false
        logHistoryReturn("onResume")
        pendingAutoPipOnLeave = false
        refreshPipParams("onResume")
        if (!isInPictureInPictureMode) {
            isBackgroundPlayback = false
            setMediaNotificationEnabled(false)
            setPlaybackForegroundMode(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logHistoryReturn("onNewIntent raw=${describeIntent(intent)}")
        val mergedIntent = Intent(intent)
        mergeNavigationContextIntoIntent(mergedIntent, this.intent)
        setIntent(mergedIntent)
        logHistoryReturn("onNewIntent merged=${describeIntent(mergedIntent)}")
        mediaSession?.let { MediaButtonReceiver.handleIntent(it, mergedIntent) }
        handlePlaybackIntent(mergedIntent, replaceCurrent = true)
    }

    override fun onPause() {
        savePlaybackPositionForCurrentItem()
        commitRecentWatchIfEligible()
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            pendingAutoPipOnLeave &&
            !suppressAutoPipForBackNavigation &&
            !isInPictureInPictureMode &&
            !isFinishing &&
            !isDestroyed
        ) {
            val entered = enterPipIfSupported()
            if (!entered) {
                window?.decorView?.post {
                    if (pendingAutoPipOnLeave &&
                        !suppressAutoPipForBackNavigation &&
                        !isInPictureInPictureMode &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        enterPipIfSupported()
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        logHistoryReturn("onStop background=$isBackgroundPlayback pip=$isInPictureInPictureMode")
        savePlaybackPositionForCurrentItem()
        commitRecentWatchIfEligible()
        if (!suppressAutoPipForBackNavigation &&
            pendingAutoPipOnLeave &&
            !isInPictureInPictureMode &&
            player?.isPlaying == true
        ) {
            isBackgroundPlayback = true
            setMediaNotificationEnabled(true)
            setPlaybackForegroundMode(true)
        }
        // onUserLeaveHint can be skipped on some launchers/gestures; keep background playback state in sync.
        if (!suppressAutoPipForBackNavigation &&
            !isInPictureInPictureMode &&
            !isFinishing &&
            !isDestroyed &&
            !isChangingConfigurations &&
            player?.isPlaying == true
        ) {
            isBackgroundPlayback = true
            setMediaNotificationEnabled(true)
            setPlaybackForegroundMode(true)
        }
        if (!suppressAutoPipForBackNavigation &&
            (isBackgroundPlayback || isInPictureInPictureMode) &&
            player?.isPlaying == true
        ) {
            setPlaybackForegroundMode(true)
            PlaybackKeepAliveService.start(
                context = this,
                title = currentPlaybackTitle(),
                content = currentPlaybackAuthor().ifBlank { currentPlaybackReason() },
                openIntent = currentPlaybackResumeIntent()
            )
        }
        if (isFinishing) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        savePlaybackPositionForCurrentItem()
        commitRecentWatchIfEligible()
        overlayHideRunnable?.let { overlayHandler.removeCallbacks(it) }
        stopRecentWatchTimer()
        stopPlaybackStateUpdates()
        setMediaNotificationEnabled(false)
        setPlaybackForegroundMode(false)
        PlaybackKeepAliveService.stop(this)
        mediaSession?.release()
        mediaSession = null
        playerNotificationManager = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        playerView?.player = null
        player?.release()
        player = null
        activeInstance = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (suppressAutoPipForBackNavigation) {
            logHistoryReturn("onUserLeaveHint suppressedForBackNavigation")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pendingAutoPipOnLeave = true
            isBackgroundPlayback = true
            setMediaNotificationEnabled(true)
            setPlaybackForegroundMode(true)
        }
        enterPipIfSupported()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            pendingAutoPipOnLeave = false
        }
        if (isInPictureInPictureMode) {
            wasInPip = true
        }
        playerView?.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerView?.controllerAutoShow = false
            playerView?.hideController()
        } else {
            playerView?.controllerAutoShow = true
        }
        moreButton?.isEnabled = !isInPictureInPictureMode
        pipButton?.isEnabled = !isInPictureInPictureMode
        timeBar?.visibility = if (isInPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE
        queueHeader?.visibility = if (isInPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE
        queueList?.visibility = if (isInPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE
        if (isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePipActions()
            setMediaNotificationEnabled(true)
        } else {
            if (!isBackgroundPlayback) {
                setMediaNotificationEnabled(false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (
            lastKnownOrientation != Configuration.ORIENTATION_UNDEFINED &&
            newConfig.orientation != Configuration.ORIENTATION_UNDEFINED &&
            newConfig.orientation != lastKnownOrientation
        ) {
            orientationChangedDuringPlayback = true
        }
        lastKnownOrientation = newConfig.orientation
        applyOrientationUi()
    }

    private fun applyOrientationUi() {
        val isLandscape = isLandscapeMode()
        queueHeader?.visibility = if (isLandscape) android.view.View.GONE else android.view.View.VISIBLE
        queueList?.visibility = if (isLandscape) android.view.View.GONE else android.view.View.VISIBLE

        val root = findViewById<ConstraintLayout>(R.id.video_player_root)
        val container = playerContainer ?: return
        val bar = timeBar ?: return
        val bottomControls = playerBottomArea
        val set = ConstraintSet()
        set.clone(root)
        if (isLandscape) {
            set.clear(R.id.player_time_bar, ConstraintSet.TOP)
            set.connect(R.id.player_time_bar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
            set.connect(R.id.player_time_bar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            set.connect(R.id.player_time_bar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            set.connect(R.id.player_container, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
            set.connect(R.id.player_container, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            set.connect(R.id.player_container, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            set.connect(R.id.player_container, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
        } else {
            set.clear(R.id.player_time_bar, ConstraintSet.BOTTOM)
            set.connect(R.id.player_time_bar, ConstraintSet.TOP, R.id.player_container, ConstraintSet.BOTTOM, 0)
            set.connect(R.id.player_time_bar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            set.connect(R.id.player_time_bar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            set.clear(R.id.player_container, ConstraintSet.BOTTOM)
            set.connect(R.id.player_container, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
            set.connect(R.id.player_container, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            set.connect(R.id.player_container, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
        }
        set.applyTo(root)

        val containerParams = container.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            containerParams.dimensionRatio = null
            containerParams.matchConstraintMaxHeight = 0
            containerParams.height = 0
            containerParams.width = 0
            timeBar?.setBackgroundColor(Color.TRANSPARENT)
            timeBar?.bringToFront()
            timeBar?.visibility = lastControllerVisibility
            if (bottomControls != null) {
                val params = bottomControls.layoutParams as android.view.ViewGroup.MarginLayoutParams
                params.bottomMargin = dpToPx(28f)
                bottomControls.layoutParams = params
            }
        } else {
            containerParams.dimensionRatio = "16:9"
            containerParams.matchConstraintMaxHeight = dpToPx(320f)
            containerParams.height = 0
            containerParams.width = 0
            timeBar?.setBackgroundColor(Color.BLACK)
            timeBar?.visibility = android.view.View.VISIBLE
            if (bottomControls != null) {
                val params = bottomControls.layoutParams as android.view.ViewGroup.MarginLayoutParams
                params.bottomMargin = 0
                bottomControls.layoutParams = params
            }
        }
        container.layoutParams = containerParams
        applyWindowInsets()
        updatePlaybackUiProgress()
        updateSystemBarsForOrientation()
    }

    private fun isLandscapeMode(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun initSpeedControl() {
        updateSpeedLabel(player?.playbackParameters?.speed ?: 1.0f)
        speedLabel?.setOnClickListener { showSpeedDialog() }
    }

    private fun initChaptersControl() {
        chaptersLabel?.setOnClickListener {
            showChaptersBottomSheet()
        }
        refreshChaptersForCurrentItem()
    }

    private fun refreshChaptersForCurrentItem() {
        val exo = player
        val mediaItem = exo?.currentMediaItem
        val mediaUri = mediaItem?.localConfiguration?.uri
        if (mediaUri == null) {
            updateChaptersUi(emptyList())
            return
        }
        val currentHistoryId = resolveHistoryIdForMediaItem(mediaItem)
        val embedded = extractEmbeddedChaptersFromPlayer(exo)
        lifecycleScope.launch {
            val chapters = if (embedded.isNotEmpty()) {
                embedded
            } else {
                withContext(Dispatchers.IO) {
                    loadChaptersForCurrentMedia(mediaUri, currentHistoryId)
                }
            }
            updateChaptersUi(chapters)
        }
    }

    private fun updateChaptersUi(chapters: List<VideoChapter>) {
        currentChapters = chapters
        chaptersLabel?.visibility = if (chapters.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showChaptersBottomSheet() {
        if (currentChapters.isEmpty()) return
        val dialog = BottomSheetDialog(this)
        val listView = ListView(this).apply {
            divider = null
            adapter = ArrayAdapter(
                this@VideoPlayerActivity,
                android.R.layout.simple_list_item_1,
                currentChapters.map { chapter ->
                    "${formatChapterTime(chapter.startMs)}  ${chapter.title.ifBlank { getString(R.string.chapters) }}"
                }
            )
            setOnItemClickListener { _, _, position, _ ->
                val chapter = currentChapters.getOrNull(position) ?: return@setOnItemClickListener
                val currentWindow = player?.currentMediaItemIndex ?: 0
                player?.seekTo(currentWindow, chapter.startMs)
                dialog.dismiss()
            }
        }
        dialog.setContentView(listView)
        dialog.show()
    }

    private fun loadChaptersForCurrentMedia(
        mediaUri: Uri,
        currentHistoryId: Long?
    ): List<VideoChapter> {
        val mediaPath = resolveBestMediaPathForChapters(mediaUri, currentHistoryId) ?: return emptyList()
        return loadChaptersFromSidecar(mediaPath)
    }

    private fun resolveBestMediaPathForChapters(mediaUri: Uri, currentHistoryId: Long?): String? {
        when {
            mediaUri.scheme == "file" -> return mediaUri.path
            mediaUri.scheme.isNullOrBlank() -> return mediaUri.toString()
        }
        if (currentHistoryId != null) {
            val mapped = queuePlayablePathById[currentHistoryId]
            if (!mapped.isNullOrBlank()) return mapped
        }
        return null
    }

    private fun extractEmbeddedChaptersFromPlayer(exo: Player?): List<VideoChapter> {
        if (exo == null) return emptyList()
        val result = linkedMapOf<Long, VideoChapter>()
        exo.currentTracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                val metadata = group.mediaTrackGroup.getFormat(i).metadata ?: continue
                extractChaptersFromMetadata(metadata).forEach { chapter ->
                    result[chapter.startMs] = chapter
                }
            }
        }
        return result.values.sortedBy { it.startMs }
    }

    private fun extractChaptersFromMetadata(metadata: Metadata): List<VideoChapter> {
        val chapters = mutableListOf<VideoChapter>()
        for (i in 0 until metadata.length()) {
            val entry = metadata[i]
            val clazz = entry.javaClass
            if (!clazz.simpleName.equals("ChapterFrame", ignoreCase = true)) continue
            val start = runCatching { clazz.getDeclaredField("startTimeMs").apply { isAccessible = true }.get(entry) as? Int }
                .getOrNull()?.toLong()
                ?: continue
            val end = runCatching { clazz.getDeclaredField("endTimeMs").apply { isAccessible = true }.get(entry) as? Int }
                .getOrNull()?.toLong()
            val title = extractTitleFromChapterEntry(entry)
            chapters.add(VideoChapter(title = title, startMs = start, endMs = end))
        }
        return chapters
    }

    private fun extractTitleFromChapterEntry(entry: Any): String {
        val clazz = entry.javaClass
        val chapterId = runCatching { clazz.getDeclaredField("chapterId").apply { isAccessible = true }.get(entry) as? String }
            .getOrNull()
            .orEmpty()
        val subFrames = runCatching {
            clazz.getDeclaredField("subFrames").apply { isAccessible = true }.get(entry) as? List<*>
        }.getOrNull().orEmpty()
        subFrames.forEach { frame ->
            frame ?: return@forEach
            val frameClass = frame.javaClass
            val frameId = runCatching { frameClass.getDeclaredField("id").apply { isAccessible = true }.get(frame) as? String }.getOrNull()
            if (frameId == "TIT2") {
                val values = runCatching {
                    frameClass.getDeclaredField("values").apply { isAccessible = true }.get(frame) as? List<*>
                }.getOrNull()
                val title = values?.firstOrNull()?.toString().orEmpty()
                if (title.isNotBlank()) return title
            }
        }
        return chapterId
    }

    private fun loadChaptersFromSidecar(mediaPath: String): List<VideoChapter> {
        val file = File(mediaPath)
        if (!file.exists()) return emptyList()
        val baseName = file.nameWithoutExtension
        val candidates = listOf(
            File("${file.absolutePath}.info.json"),
            File(file.parentFile, "$baseName.info.json")
        )
        val infoFile = candidates.firstOrNull { it.exists() && it.isFile } ?: return emptyList()
        val jsonText = runCatching { infoFile.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val chaptersArray = root.optJSONArray("chapters") ?: return emptyList()
        val chapters = mutableListOf<VideoChapter>()
        for (i in 0 until chaptersArray.length()) {
            val obj = chaptersArray.optJSONObject(i) ?: continue
            val startSec = obj.optDouble("start_time", -1.0)
            if (startSec < 0) continue
            val title = obj.optString("title", "")
            val endSec = obj.optDouble("end_time", -1.0)
            chapters.add(
                VideoChapter(
                    title = title,
                    startMs = (startSec * 1000L).toLong(),
                    endMs = if (endSec >= 0) (endSec * 1000L).toLong() else null
                )
            )
        }
        return chapters.sortedBy { it.startMs }
    }

    private fun formatChapterTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun loadSubtitlePreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        subtitleTextSizeFraction = prefs.getFloat(PREF_SUBTITLE_TEXT_SIZE, SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
        subtitleApplyEmbeddedStyles = prefs.getBoolean(PREF_SUBTITLE_EMBEDDED_STYLES, true)
        subtitleApplyEmbeddedFontSizes = prefs.getBoolean(PREF_SUBTITLE_EMBEDDED_FONT_SIZES, true)
        holdPlaybackSpeed = prefs.getFloat(PREF_HOLD_PLAYBACK_SPEED, 2.0f)
        val foreground = prefs.getInt(PREF_SUBTITLE_FOREGROUND, CaptionStyleCompat.DEFAULT.foregroundColor)
        val background = prefs.getInt(PREF_SUBTITLE_BACKGROUND, CaptionStyleCompat.DEFAULT.backgroundColor)
        val window = prefs.getInt(PREF_SUBTITLE_WINDOW, CaptionStyleCompat.DEFAULT.windowColor)
        val edgeType = prefs.getInt(PREF_SUBTITLE_EDGE_TYPE, CaptionStyleCompat.DEFAULT.edgeType)
        val edgeColor = prefs.getInt(PREF_SUBTITLE_EDGE_COLOR, CaptionStyleCompat.DEFAULT.edgeColor)
        subtitleStyle = CaptionStyleCompat(foreground, background, window, edgeType, edgeColor, null)
    }

    private fun persistSubtitlePreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putFloat(PREF_SUBTITLE_TEXT_SIZE, subtitleTextSizeFraction)
            .putBoolean(PREF_SUBTITLE_EMBEDDED_STYLES, subtitleApplyEmbeddedStyles)
            .putBoolean(PREF_SUBTITLE_EMBEDDED_FONT_SIZES, subtitleApplyEmbeddedFontSizes)
            .putFloat(PREF_HOLD_PLAYBACK_SPEED, holdPlaybackSpeed)
            .putInt(PREF_SUBTITLE_FOREGROUND, subtitleStyle.foregroundColor)
            .putInt(PREF_SUBTITLE_BACKGROUND, subtitleStyle.backgroundColor)
            .putInt(PREF_SUBTITLE_WINDOW, subtitleStyle.windowColor)
            .putInt(PREF_SUBTITLE_EDGE_TYPE, subtitleStyle.edgeType)
            .putInt(PREF_SUBTITLE_EDGE_COLOR, subtitleStyle.edgeColor)
            .apply()
    }

    private fun showSpeedDialog() {
        val current = player?.playbackParameters?.speed ?: 1.0f
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(16f), dpToPx(8f), dpToPx(16f), 0)
        }
        val valueText = android.widget.TextView(this).apply {
            text = String.format("%.2fx", current)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, dpToPx(8f))
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val presetSpeeds = floatArrayOf(
            prefs.getFloat(PREF_SPEED_PRESET_1, 0.75f),
            prefs.getFloat(PREF_SPEED_PRESET_2, 1.0f),
            prefs.getFloat(PREF_SPEED_PRESET_3, 1.25f),
            prefs.getFloat(PREF_SPEED_PRESET_4, 1.5f),
            prefs.getFloat(PREF_SPEED_PRESET_5, 2.0f)
        )
        val presetRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 0.25f
            valueTo = 3.0f
            stepSize = 0.05f
            value = current.coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, v, _ ->
                valueText.text = String.format("%.2fx", v)
            }
        }
        val setSpeed = { speed: Float ->
            val clamped = speed.coerceIn(slider.valueFrom, slider.valueTo)
            slider.value = clamped
            valueText.text = String.format("%.2fx", clamped)
        }
        val presetKeys = arrayOf(
            PREF_SPEED_PRESET_1,
            PREF_SPEED_PRESET_2,
            PREF_SPEED_PRESET_3,
            PREF_SPEED_PRESET_4,
            PREF_SPEED_PRESET_5
        )
        presetSpeeds.forEachIndexed { index, speed ->
            val chip = android.widget.TextView(this).apply {
                text = String.format("%.2fx", speed)
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dpToPx(8f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
                setBackgroundResource(R.drawable.player_speed_chip)
                setOnClickListener { setSpeed(speed) }
                setOnLongClickListener {
                    val currentSpeed = slider.value
                    prefs.edit().putFloat(presetKeys[index], currentSpeed).apply()
                    text = String.format("%.2fx", currentSpeed)
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        getString(R.string.speed_preset_saved, String.format("%.2fx", currentSpeed)),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dpToPx(6f)
            chip.layoutParams = params
            presetRow.addView(chip)
        }
        container.addView(valueText)
        container.addView(presetRow)
        container.addView(slider)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.playback_speed))
            .setView(container)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val speed = slider.value
                player?.setPlaybackSpeed(speed)
                updateSpeedLabel(speed)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCustomSpeedDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "1.00"
            setText(customPlaybackSpeed?.toString() ?: "")
            setSelection(text?.length ?: 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.custom_speed))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                val speed = value.toFloatOrNull()
                if (speed != null) {
                    val clamped = speed.coerceIn(0.25f, 3.0f)
                    customPlaybackSpeed = clamped
                    player?.setPlaybackSpeed(clamped)
                    updateSpeedLabel(clamped)
                } else {
                    Toast.makeText(this, getString(R.string.invalid_speed_value), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateSpeedLabel(speed: Float) {
        speedLabel?.text = String.format("%.2fx", speed)
    }

    private fun showHoldSpeedDialog() {
        val speeds = floatArrayOf(1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
        val labels = speeds.map { String.format("%.2fx", it) }.toTypedArray()
        val currentIndex = speeds.indexOfFirst { it == holdPlaybackSpeed }.let { if (it == -1) 2 else it }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hold_speed_title))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                holdPlaybackSpeed = speeds[which]
                persistSubtitlePreferences()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun initMoreMenu() {
        moreButton?.setOnClickListener {
            val options = mutableListOf<String>()
            options.add(getString(R.string.picture_in_picture))
            options.add(getString(R.string.playback_speed))
            options.add(getString(R.string.hold_speed_setting, String.format("%.2fx", holdPlaybackSpeed)))
            options.add(getString(R.string.subtitles_toggle))
            options.add(
                getString(
                    R.string.volume_normalization,
                    if (volumeNormalizationEnabled) getString(R.string.enabled) else getString(R.string.disabled)
                )
            )
            options.add(
                if (controlsLocked) getString(R.string.unlock_controls) else getString(R.string.lock_controls)
            )
            options.add(getString(R.string.video_info))
            options.add(getString(R.string.edit_video_info))
            MaterialAlertDialogBuilder(this)
                .setItems(options.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> enterPipIfSupported()
                        1 -> showSpeedDialog()
                        2 -> showHoldSpeedDialog()
                        3 -> showSubtitlesDialog()
                        4 -> toggleVolumeNormalization()
                        5 -> toggleControlsLock()
                        6 -> showVideoInfo()
                        7 -> editCurrentVideoInfo()
                    }
                }
                .show()
        }
    }

    private fun initPipControl() {
        pipButton?.setOnClickListener {
            enterPipIfSupported()
        }
    }

    private fun enterPipIfSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "PiP not supported", Toast.LENGTH_SHORT).show()
            return false
        }
        if (isFinishing || isDestroyed || isInPictureInPictureMode) {
            return false
        }
        return try {
            playerView?.controllerAutoShow = false
            playerView?.hideController()
            val params = buildPipParams(includeActions = false)
            val entered = enterPictureInPictureMode(params)
            if (!entered) {
                Log.w("VideoPip", "enterPipIfSupported primary params rejected, retrying with minimal params")
                val fallbackParams = PictureInPictureParams.Builder().build()
                enterPictureInPictureMode(fallbackParams)
            } else {
                true
            }
        } catch (_: IllegalStateException) {
            Log.w("VideoPip", "enterPipIfSupported failed: IllegalStateException")
            false
        }
    }

    private fun buildPipParams(includeActions: Boolean = true): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        resolvePipSourceRect()?.let { rect ->
            builder.setSourceRectHint(rect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }
        if (includeActions && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setActions(buildPipActions())
        }
        return builder.build()
    }

    private fun resolvePipSourceRect(): Rect? {
        val rect = Rect()
        val sourceView = playerContainer ?: playerView
        return if (sourceView?.getGlobalVisibleRect(rect) == true && !rect.isEmpty) rect else null
    }

    private fun buildPipActions(): List<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        val isPlaying = player?.isPlaying == true
        val hasNext = player?.hasNextMediaItem() == true
        val playPauseIcon = if (isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
        val playPauseTitle = if (isPlaying) getString(R.string.pause) else getString(R.string.play)
        actions.add(createPipAction(
            R.drawable.ic_headset,
            getString(R.string.pip_background),
            ACTION_PIP_BACKGROUND
        ))
        actions.add(createPipAction(
            playPauseIcon,
            playPauseTitle,
            ACTION_PIP_PLAY_PAUSE
        ))
        actions.add(
            createPipAction(
                R.drawable.ic_baseline_keyboard_arrow_right_24,
                getString(R.string.pip_next),
                ACTION_PIP_NEXT
            ).apply {
                isEnabled = hasNext
            }
        )
        return actions
    }

    private fun createPipAction(iconRes: Int, title: String, action: String): RemoteAction {
        val intent = Intent(this, com.ireum.ytdl.receiver.PipActionReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pendingIntent)
    }

    private fun currentPlaybackTitle(): String {
        val mediaId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        val queueTitle = mediaId?.let { id ->
            queueItems.firstOrNull { it.id == id }?.title?.trim().orEmpty()
        }.orEmpty()
        if (queueTitle.isNotEmpty()) return queueTitle
        val title = titleView?.text?.toString()?.trim().orEmpty()
        return if (title.isNotEmpty()) title else getString(R.string.app_name)
    }

    private fun currentPlaybackReason(): String {
        return when {
            isInPictureInPictureMode -> getString(R.string.playback_pip_notification)
            isBackgroundPlayback -> getString(R.string.playback_background_notification)
            else -> ""
        }
    }

    private fun currentPlaybackAuthor(): String {
        val mediaId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        val currentItem = if (mediaId != null) {
            queueItems.firstOrNull { it.id == mediaId }
        } else {
            val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (uri.isNullOrBlank()) {
                null
            } else {
                queueItems.firstOrNull { it.downloadPath.any { p -> p == uri || uri.endsWith(p) } }
            }
        }
        val queueAuthor = currentItem?.author.orEmpty()
        if (queueAuthor.isNotBlank()) return queueAuthor
        return authorView?.text?.toString()?.trim().orEmpty()
    }

    private fun currentThumbUrl(): String? {
        val mediaId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        val currentItem = if (mediaId != null) {
            queueItems.firstOrNull { it.id == mediaId }
        } else {
            val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (uri.isNullOrBlank()) {
                null
            } else {
                queueItems.firstOrNull { it.downloadPath.any { p -> p == uri || uri.endsWith(p) } }
            }
        }
        return currentItem?.let { resolvePreferredThumbSource(it) }
    }

    private fun resolvePreferredThumbSource(item: HistoryItem): String? {
        val preferred = if (item.customThumb.isNotBlank() && FileUtil.exists(item.customThumb)) {
            item.customThumb
        } else {
            item.thumb
        }
        if (preferred.isBlank()) return null
        return when {
            preferred.startsWith("content://") || preferred.startsWith("file://") -> preferred
            preferred.startsWith("http://") || preferred.startsWith("https://") -> preferred
            FileUtil.exists(preferred) -> File(preferred).toURI().toString()
            else -> preferred
        }
    }

    private fun isRemoteThumbSource(source: String): Boolean {
        return source.startsWith("http://") || source.startsWith("https://")
    }

    private suspend fun ensureLocalThumbForPlayback(item: HistoryItem, source: String): String {
        if (!isRemoteThumbSource(source) || item.id <= 0L) return source
        val bitmap = runCatching {
            Picasso.get().load(source).resize(512, 0).onlyScaleDown().get()
        }.getOrNull() ?: return source

        val outDir = File(filesDir, "thumb_cache")
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "history_${item.id}_thumb.jpg")
        val wrote = runCatching {
            outFile.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }
            true
        }.getOrDefault(false)
        if (!wrote) return source

        val updated = item.copy(thumb = outFile.absolutePath)
        runCatching {
            DBManager.getInstance(this@VideoPlayerActivity).historyDao.update(updated)
            queueItems = queueItems.map { if (it.id == updated.id) updated else it }
        }
        return outFile.toURI().toString()
    }

    private fun currentHistoryItem(): HistoryItem? {
        val mediaId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        if (mediaId != null) {
            return queueItems.firstOrNull { it.id == mediaId }
        }
        val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        if (uri.isNullOrBlank()) return null
        return queueItems.firstOrNull { it.downloadPath.any { p -> p == uri || uri.endsWith(p) } }
    }

    private fun editCurrentVideoInfo() {
        val item = currentHistoryItem()
        if (item == null || item.id <= 0L) {
            Toast.makeText(this, getString(R.string.no_match_found), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val db = DBManager.getInstance(this@VideoPlayerActivity)
            val authors = withContext(Dispatchers.IO) { db.historyDao.authors.first() }
            showEditHistoryItemDialog(item, authors)
        }
    }

    private fun showEditHistoryItemDialog(item: HistoryItem, authors: List<String>) {
        val view = layoutInflater.inflate(R.layout.history_item_edit_dialog, null)
        val titleInput = view.findViewById<TextInputEditText>(R.id.edit_title)
        val authorInput = view.findViewById<AutoCompleteTextView>(R.id.edit_author)
        val artistInput = view.findViewById<AutoCompleteTextView>(R.id.edit_artist)
        val urlInput = view.findViewById<TextInputEditText>(R.id.edit_url)
        val keywordsInput = view.findViewById<AutoCompleteTextView>(R.id.edit_keywords)
        val thumbPreview = view.findViewById<ImageView>(R.id.edit_thumb_preview)
        val selectThumb = view.findViewById<android.widget.Button>(R.id.edit_select_thumb_gallery)
        val captureThumb = view.findViewById<android.widget.Button>(R.id.edit_capture_thumb)
        val removeThumb = view.findViewById<android.widget.Button>(R.id.edit_remove_thumb)
        val fetchSearch = view.findViewById<android.widget.Button>(R.id.edit_fetch_search)
        val fetchLink = view.findViewById<android.widget.Button>(R.id.edit_fetch_link)

        fetchSearch.visibility = View.GONE
        fetchLink.visibility = View.GONE

        titleInput.setText(item.title)
        val youtuberAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, authors)
        authorInput.setAdapter(youtuberAdapter)
        artistInput.setAdapter(youtuberAdapter)
        authorInput.setText(item.author)
        artistInput.setText(item.artist)
        urlInput.setText(item.url)
        keywordsInput.setText(item.keywords)

        var editedCustomThumb = item.customThumb

        fun updatePreview() {
            val preview = if (editedCustomThumb.isNotBlank() && FileUtil.exists(editedCustomThumb)) {
                editedCustomThumb
            } else {
                item.thumb
            }
            if (preview.isBlank()) {
                thumbPreview.setImageDrawable(null)
                return
            }
            val resolved = if (preview.startsWith("content://") || preview.startsWith("file://")) {
                preview
            } else if (preview.startsWith("http://") || preview.startsWith("https://")) {
                preview
            } else {
                File(preview).toURI().toString()
            }
            Picasso.get().invalidate(resolved)
            Picasso.get().load(resolved).resize(1280, 0).onlyScaleDown().into(thumbPreview)
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
            captureCurrentFrameThumb(item) { saved ->
                if (saved.isNullOrBlank()) {
                    Toast.makeText(this, R.string.error_saving_thumbnail, Toast.LENGTH_SHORT).show()
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

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.edit_video_info))
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val title = titleInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    Toast.makeText(this, R.string.video_info_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val author = authorInput.text?.toString()?.trim().orEmpty()
                val artist = artistInput.text?.toString()?.trim().orEmpty()
                val url = urlInput.text?.toString()?.trim().orEmpty()
                val keywords = keywordsInput.text?.toString()?.trim().orEmpty()
                val updated = item.copy(
                    title = title,
                    author = author,
                    artist = artist,
                    url = url,
                    keywords = keywords,
                    customThumb = editedCustomThumb
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    DBManager.getInstance(this@VideoPlayerActivity).historyDao.update(updated)
                }
                updateTitleViews(updated.title, updated.author)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnDismissListener {
            isEditVideoInfoDialogVisible = false
            pendingThumbItem = null
            pendingThumbCallback = null
        }

        isEditVideoInfoDialogVisible = true
        dialog.show()
    }

    private fun showCustomThumbPicker(
        item: HistoryItem,
        onSaved: (String?) -> Unit
    ) {
        val path = item.downloadPath.firstOrNull { FileUtil.exists(it) }
            ?: item.downloadPath.firstOrNull()
            ?: return onSaved(null)
        if (!canReadPath(path)) {
            (this as? com.ireum.ytdl.ui.BaseActivity)?.askPermissions()
            Toast.makeText(this, R.string.request_permission_desc, Toast.LENGTH_SHORT).show()
            return onSaved(null)
        }
        val durationMs = getDurationMs(path)
        val maxSeconds = (durationMs / 1000L).coerceAtLeast(1L).toInt()
        val padding = (resources.displayMetrics.density * 12).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 180).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        val timeLabel = TextView(this).apply {
            setPadding(0, padding / 2, 0, 0)
        }
        val seekBar = SeekBar(this).apply {
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

        val dialog = MaterialAlertDialogBuilder(this)
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

    private fun captureCurrentFrameThumb(
        item: HistoryItem,
        onSaved: (String?) -> Unit
    ) {
        val path = item.downloadPath.firstOrNull { FileUtil.exists(it) }
            ?: item.downloadPath.firstOrNull()
            ?: return onSaved(null)
        if (!canReadPath(path)) {
            (this as? com.ireum.ytdl.ui.BaseActivity)?.askPermissions()
            Toast.makeText(this, R.string.request_permission_desc, Toast.LENGTH_SHORT).show()
            return onSaved(null)
        }
        val positionMs = player?.currentPosition ?: 0L
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = captureFrameBitmapAt(path, positionMs)
            val saved = if (bitmap != null) saveCustomThumbFromBitmap(item, bitmap) else null
            withContext(Dispatchers.Main) {
                onSaved(saved)
            }
        }
    }

    private fun getDurationMs(path: String): Long {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever()
            if (path.startsWith("content://") || path.startsWith("file://")) {
                retriever?.setDataSource(this, Uri.parse(path))
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
                val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
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
                retriever?.setDataSource(this, Uri.parse(path))
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
        val stream = contentResolver.openInputStream(uri) ?: return null
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
                val fallback = getExternalFilesDir(null) ?: cacheDir
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
                doc?.name?.substringBeforeLast('.')
                    ?.ifBlank { sanitizeLocalFileName(item.title.ifBlank { "video" }) }
                    ?: sanitizeLocalFileName(item.title.ifBlank { "video" })
            }
            else -> File(path).nameWithoutExtension.ifBlank { sanitizeLocalFileName(item.title.ifBlank { "video" }) }
        }
    }

    private fun sanitizeLocalFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
    }

    private fun documentFileForUri(uri: Uri): DocumentFile? {
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(this, uri)
        } else {
            DocumentFile.fromSingleUri(this, uri)
        }
    }

    private fun deleteCustomThumb(path: String) {
        if (path.isBlank()) return
        runCatching { FileUtil.deleteFile(path) }
    }

    private fun currentPlaybackResumeIntent(): Intent? {
        val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString() ?: return null
        return Intent(this, VideoPlayerActivity::class.java).apply {
            action = ACTION_RESUME_PLAYBACK_UI
            putExtra("video_path", uri)
            addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            resolveHistoryIdForMediaItem(player?.currentMediaItem)?.let { historyId ->
                putExtra("history_id", historyId)
            }
            putExtra("playback_position_ms", player?.currentPosition ?: 0L)
            intent.getStringArrayListExtra("video_paths")?.let { putStringArrayListExtra("video_paths", it) }
            listOf(
                "context_sort_type",
                "context_sort_order",
                "context_status",
                "context_query",
                "context_title_query",
                "context_keyword_query",
                "context_creator_query",
                "context_search_fields",
                "context_type",
                "context_website",
                "context_author",
                "context_keyword",
                "context_playlist_name",
                "context_excluded_child_keywords"
            ).forEach { key ->
                this@VideoPlayerActivity.intent.getStringExtra(key)?.let { value ->
                    putExtra(key, value)
                }
            }
            listOf(
                "context_include_child_category_videos"
            ).forEach { key ->
                if (this@VideoPlayerActivity.intent.hasExtra(key)) {
                    putExtra(key, this@VideoPlayerActivity.intent.getBooleanExtra(key, false))
                }
            }
            this@VideoPlayerActivity.intent.getStringExtra(EXTRA_RETURN_DESTINATION)?.let { destination ->
                putExtra(EXTRA_RETURN_DESTINATION, destination)
            }
            copyHistoryReturnExtrasTo(this)
            listOf(
                "context_playlist_id",
                "context_youtuber_group_id"
            ).forEach { key ->
                if (this@VideoPlayerActivity.intent.hasExtra(key)) {
                    putExtra(key, this@VideoPlayerActivity.intent.getLongExtra(key, -1L))
                }
            }
            this@VideoPlayerActivity.intent.getLongArrayExtra("context_prefetched_history_ids")?.let {
                putExtra("context_prefetched_history_ids", it)
            }
            if (this@VideoPlayerActivity.intent.hasExtra("context_prefetched_total_count")) {
                putExtra(
                    "context_prefetched_total_count",
                    this@VideoPlayerActivity.intent.getIntExtra("context_prefetched_total_count", -1)
                )
            }
        }
    }

    private fun currentPlaybackPendingIntent(): PendingIntent? {
        val intent = currentPlaybackResumeIntent() ?: return null
        return PendingIntent.getActivity(
            this,
            NotificationUtil.PLAYBACK_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun copyHistoryReturnExtrasTo(target: Intent) {
        intent.getBundleExtra(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT)?.let { snapshot ->
            target.putExtra(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT, Bundle(snapshot))
        }
        if (!intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)) return
        logHistoryReturn(
            "copyHistoryReturnExtras position=" +
                intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION, RecyclerView.NO_POSITION) +
                " offset=" +
                intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET, 0) +
                " itemId=" +
                intent.getLongExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID, -1L) +
                " itemTop=" +
                if (intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP)) {
                    intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP, 0)
                } else {
                    "null"
                }
        )
        target.putExtra(
            HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION,
            intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION, RecyclerView.NO_POSITION)
        )
        target.putExtra(
            HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET,
            intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET, 0)
        )
        val restoreItemId = intent.getLongExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID, -1L)
        if (restoreItemId > 0L) {
            target.putExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID, restoreItemId)
        }
        if (intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP)) {
            target.putExtra(
                HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP,
                intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP, 0)
            )
        }
    }

    private fun savePendingHistoryScrollRestore() {
        if (intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT)) {
            // The return intent carries the full snapshot; keep the legacy pending scroll as a fallback only.
            logHistoryReturn("savePendingHistoryScrollRestore hasScreenSnapshot")
        }
        if (!intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)) return
        val position = intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION, RecyclerView.NO_POSITION)
        val offset = intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET, 0)
        val itemId = intent.getLongExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID, -1L).takeIf { it > 0L }
        val itemTop = if (intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP)) {
            intent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP, 0)
        } else {
            null
        }
        logHistoryReturn(
            "savePendingHistoryScrollRestore position=$position offset=$offset itemId=$itemId itemTop=$itemTop"
        )
        HistoryFragment.savePendingScrollRestore(
            this,
            position,
            offset,
            itemId,
            itemTop
        )
    }

    private fun mergeNavigationContextIntoIntent(target: Intent, source: Intent?) {
        val safeSource = source ?: return
        if (!target.hasExtra(EXTRA_RETURN_DESTINATION)) {
            safeSource.getStringExtra(EXTRA_RETURN_DESTINATION)?.let { destination ->
                target.putExtra(EXTRA_RETURN_DESTINATION, destination)
            }
        }
        if (!target.hasExtra(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT)) {
            safeSource.getBundleExtra(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT)?.let { snapshot ->
                target.putExtra(HistoryFragment.EXTRA_RESTORE_SCREEN_SNAPSHOT, Bundle(snapshot))
            }
        }
        if (!target.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION) &&
            safeSource.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)
        ) {
            target.putExtra(
                HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION,
                safeSource.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION, RecyclerView.NO_POSITION)
            )
            target.putExtra(
                HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET,
                safeSource.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET, 0)
            )
            val restoreItemId = safeSource.getLongExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID, -1L)
                .takeIf { it > 0L }
            if (restoreItemId != null) {
                target.putExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_ID, restoreItemId)
            }
            if (safeSource.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP)) {
                target.putExtra(
                    HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP,
                    safeSource.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_ITEM_TOP, 0)
                )
            }
        }
    }

    private fun startMainForBackNavigation(includeHistoryRestore: Boolean) {
        val destination = intent.getStringExtra(EXTRA_RETURN_DESTINATION)
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = if (destination.isNullOrBlank()) Intent.ACTION_MAIN else Intent.ACTION_VIEW
            if (!destination.isNullOrBlank()) {
                putExtra("destination", destination)
            }
            if (includeHistoryRestore) {
                copyHistoryReturnExtrasTo(this)
            }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        suppressAutoPipForBackNavigation = true
        pendingAutoPipOnLeave = false
        isBackgroundPlayback = false
        logHistoryReturn("startMainForBackNavigation includeHistoryRestore=$includeHistoryRestore target=${describeIntent(mainIntent)}")
        startActivity(mainIntent)
    }

    private fun handleBackToHistoryIfNeeded(): Boolean {
        val hasHistoryRestore = intent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)
        val hasReturnDestination = !intent.getStringExtra(EXTRA_RETURN_DESTINATION).isNullOrBlank()
        val mustRecreateMainTask =
            isTaskRoot || intent.action == ACTION_RESUME_PLAYBACK_UI || orientationChangedDuringPlayback

        if (!mustRecreateMainTask && (hasHistoryRestore || hasReturnDestination)) {
            backNavigationInProgress = true
            if (hasHistoryRestore) {
                savePendingHistoryScrollRestore()
            }
            suppressAutoPipForBackNavigation = true
            pendingAutoPipOnLeave = false
            isBackgroundPlayback = false
            logHistoryReturn("handleBackToHistoryIfNeeded finishToExistingMain taskRoot=$isTaskRoot")
            finish()
            return true
        }

        if (hasHistoryRestore) {
            backNavigationInProgress = true
            savePendingHistoryScrollRestore()
            logHistoryReturn("handleBackToHistoryIfNeeded routeToMain taskRoot=$isTaskRoot")
            startMainForBackNavigation(includeHistoryRestore = true)
            finish()
            return true
        }
        if (hasReturnDestination) {
            backNavigationInProgress = true
            logHistoryReturn("handleBackToHistoryIfNeeded returnDestination routeToMain taskRoot=$isTaskRoot")
            startMainForBackNavigation(includeHistoryRestore = false)
            finish()
            return true
        }
        if (intent.action == ACTION_RESUME_PLAYBACK_UI) {
            backNavigationInProgress = true
            logHistoryReturn("handleBackToHistoryIfNeeded resumeUi routeToMain taskRoot=$isTaskRoot")
            startMainForBackNavigation(includeHistoryRestore = false)
            finish()
            return true
        }
        if (isTaskRoot) {
            backNavigationInProgress = true
            logHistoryReturn("handleBackToHistoryIfNeeded taskRoot=true noHistoryRestore")
            startMainForBackNavigation(includeHistoryRestore = false)
            finish()
            return true
        }
        logHistoryReturn("handleBackToHistoryIfNeeded taskRoot=false noHistoryRestore")
        return false
    }

    private fun logHistoryReturn(event: String) {
        if (!ENABLE_HISTORY_RETURN_LOGS) return
        Log.d(
            HISTORY_RETURN_TAG,
            "event=$event current=${player?.currentPosition ?: 0L} state=${player?.playbackState ?: -1} " +
                "taskRoot=$isTaskRoot taskId=$taskId isFinishing=$isFinishing action=${intent.action} " +
                "intent=${describeIntent(intent)}"
        )
    }

    private fun describeIntent(source: Intent?): String {
        val safeIntent = source ?: return "null"
        val extras = buildList {
            if (safeIntent.hasExtra(EXTRA_RETURN_DESTINATION)) {
                add("returnDestination=${safeIntent.getStringExtra(EXTRA_RETURN_DESTINATION)}")
            }
            if (safeIntent.hasExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)) {
                add(
                    "restore=" +
                        safeIntent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION, RecyclerView.NO_POSITION) +
                        "/" +
                        safeIntent.getIntExtra(HistoryFragment.EXTRA_RESTORE_SCROLL_OFFSET, 0)
                )
            }
            if (safeIntent.hasExtra("destination")) {
                add("destination=${safeIntent.getStringExtra("destination")}")
            }
            if (safeIntent.hasExtra("history_id")) {
                add("historyId=${safeIntent.getLongExtra("history_id", -1L)}")
            }
            if (safeIntent.hasExtra("video_path")) {
                add("videoPath=true")
            }
        }.joinToString(",")
        return "action=${safeIntent.action} flags=0x${safeIntent.flags.toString(16)} extras=[$extras]"
    }

    private fun setupMediaNotification() {
        if (playerNotificationManager != null) return
        mediaSession = MediaSessionCompat(this, "VideoPlayer").apply {
            isActive = true
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (player?.playbackState == Player.STATE_ENDED) {
                        player?.seekToDefaultPosition()
                    }
                    player?.play()
                }

                override fun onPause() {
                    player?.pause()
                }

                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos)
                }

                override fun onSkipToNext() {
                    player?.seekToNext()
                }

                override fun onSkipToPrevious() {
                    player?.seekToPrevious()
                }

                override fun onStop() {
                    handlePlaybackClose()
                }
            })
            setMediaButtonReceiver(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this@VideoPlayerActivity,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
        }
        updateMediaSessionPlaybackState()
        updateMediaSessionMetadata()
        playerNotificationManager = PlayerNotificationManager.Builder(
            this,
            NotificationUtil.PLAYBACK_NOTIFICATION_ID,
            NotificationUtil.PLAYBACK_CHANNEL_ID
        )
            .setMediaDescriptionAdapter(PlayerDescriptionAdapter())
            .setCustomActionReceiver(PlaybackActionReceiver())
            .setSmallIconResourceId(R.drawable.ic_headset)
            .build()
            .apply {
                setUseFastForwardAction(false)
                setUseRewindAction(false)
                setUseStopAction(false)
                setUseNextAction(true)
                setUsePreviousAction(true)
                val token = mediaSession?.sessionToken?.token
                if (token is android.media.session.MediaSession.Token) {
                    setMediaSessionToken(token)
                }
            }
    }

    private fun handlePlaybackClose() {
        savePlaybackPositionForCurrentItem()
        player?.pause()
        setMediaNotificationEnabled(false)
        setPlaybackForegroundMode(false)
        PlaybackKeepAliveService.stop(this)
        finish()
    }

    private inner class PlaybackActionReceiver : PlayerNotificationManager.CustomActionReceiver {
        override fun createCustomActions(
            context: android.content.Context,
            instanceId: Int
        ): Map<String, NotificationCompat.Action> {
            val intent = Intent(ACTION_PLAYBACK_CLOSE).setPackage(context.packageName).apply {
                putExtra(PlayerNotificationManager.EXTRA_INSTANCE_ID, instanceId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                instanceId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val action = NotificationCompat.Action(
                R.drawable.baseline_close_24,
                context.getString(R.string.pip_close),
                pendingIntent
            )
            return mapOf(ACTION_PLAYBACK_CLOSE to action)
        }

        override fun getCustomActions(player: Player): List<String> {
            return listOf(ACTION_PLAYBACK_CLOSE)
        }

        override fun onCustomAction(player: Player, action: String, intent: Intent) {
            if (action == ACTION_PLAYBACK_CLOSE) {
                handlePlaybackClose()
            }
        }
    }

    private fun updateMediaSessionPlaybackState() {
        val exo = player ?: return
        val session = mediaSession ?: return
        val state = when (exo.playbackState) {
            Player.STATE_READY -> if (exo.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            Player.STATE_BUFFERING -> if (exo.isPlaying) PlaybackStateCompat.STATE_BUFFERING else PlaybackStateCompat.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            else -> PlaybackStateCompat.STATE_NONE
        }
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    exo.currentPosition,
                    exo.playbackParameters.speed,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun toggleVolumeNormalization() {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
        if (!supported) {
            Toast.makeText(this, getString(R.string.volume_normalization_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        volumeNormalizationEnabled = !volumeNormalizationEnabled
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(PREF_VOLUME_NORMALIZATION, volumeNormalizationEnabled)
            .apply()
        ensureVolumeNormalization()
    }

    private fun ensureVolumeNormalization() {
        if (!volumeNormalizationEnabled) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            loudnessSessionId = 0
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return
        }
        val sessionId = player?.audioSessionId ?: 0
        if (sessionId == 0) return
        if (loudnessEnhancer != null && loudnessSessionId == sessionId) {
            loudnessEnhancer?.enabled = true
            return
        }
        loudnessEnhancer?.release()
        loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
            setTargetGain(LOUDNESS_TARGET_GAIN_MB)
            enabled = true
        }
        loudnessSessionId = sessionId
    }

    private fun startPlaybackStateUpdates() {
        if (playbackStateUpdater == null) {
            playbackStateUpdater = Runnable {
                updateMediaSessionPlaybackState()
                updatePlaybackUiProgress()
                if (player?.isPlaying == true) {
                    playbackStateHandler.postDelayed(playbackStateUpdater!!, 250L)
                }
            }
        }
        playbackStateHandler.removeCallbacks(playbackStateUpdater!!)
        playbackStateHandler.post(playbackStateUpdater!!)
    }

    private fun stopPlaybackStateUpdates() {
        playbackStateUpdater?.let { playbackStateHandler.removeCallbacks(it) }
    }

    private fun updateMediaSessionMetadata() {
        val exo = player ?: return
        val session = mediaSession ?: return
        val duration = exo.duration.coerceAtLeast(0L)
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentPlaybackTitle())
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentPlaybackAuthor())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()
        session.setMetadata(metadata)
    }

    private fun setMediaNotificationEnabled(enabled: Boolean) {
        if (enabled) {
            if (isBackgroundPlayback || isInPictureInPictureMode) {
                setPlaybackForegroundMode(true)
                PlaybackKeepAliveService.start(
                    context = this,
                    title = currentPlaybackTitle(),
                    content = currentPlaybackAuthor().ifBlank { currentPlaybackReason() },
                    openIntent = currentPlaybackResumeIntent()
                )
            }
            setupMediaNotification()
            playerNotificationManager?.setPlayer(player)
            updateMediaSessionMetadata()
            playerNotificationManager?.invalidate()
        } else {
            playerNotificationManager?.setPlayer(null)
            if (!isBackgroundPlayback && !isInPictureInPictureMode) {
                setPlaybackForegroundMode(false)
                PlaybackKeepAliveService.stop(this)
            }
        }
    }

    private inner class PlayerDescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return currentPlaybackTitle()
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            return currentPlaybackPendingIntent()
        }

        override fun getCurrentContentText(player: Player): CharSequence? {
            val author = currentPlaybackAuthor()
            val reason = currentPlaybackReason()
            return if (author.isNotBlank()) author else reason.ifBlank { null }
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ): Bitmap? {
            val item = currentHistoryItem()
            val source = item?.let { resolvePreferredThumbSource(it) } ?: currentThumbUrl()
            if (source.isNullOrBlank()) return null
            lifecycleScope.launch {
                val resolvedSource = withContext(Dispatchers.IO) {
                    if (item != null) ensureLocalThumbForPlayback(item, source) else source
                }
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { Picasso.get().load(resolvedSource).resize(512, 0).onlyScaleDown().get() }.getOrNull()
                }
                if (bmp != null) {
                    callback.onBitmap(bmp)
                }
            }
            return null
        }
    }

    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        refreshPipParams("updatePipActions")
    }

    private fun refreshPipParams(source: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            setPictureInPictureParams(buildPipParams())
        }.onFailure { err ->
            Log.w("VideoPip", "refreshPipParams[$source] failed: ${err.javaClass.simpleName}")
        }
    }

    private fun handlePipAction(action: String?) {
        when (action) {
            ACTION_PIP_PLAY_PAUSE -> {
                val exo = player ?: return
                if (exo.isPlaying) {
                    exo.pause()
                } else {
                    if (exo.playbackState == Player.STATE_ENDED) {
                        exo.seekToDefaultPosition()
                    }
                    exo.play()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    updatePipActions()
                }
            }
            ACTION_PIP_NEXT -> {
                val exo = player ?: return
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    updatePipActions()
                }
            }
            ACTION_PIP_BACKGROUND -> {
                isBackgroundPlayback = true
                setMediaNotificationEnabled(true)
                setPlaybackForegroundMode(true)
                moveTaskToBack(true)
            }
        }
    }

    private fun setPlaybackForegroundMode(enabled: Boolean) {
        runCatching { player?.setForegroundMode(enabled) }
    }

    private fun updateSystemBarsForOrientation() {
        val controller = WindowCompat.getInsetsController(window, window.decorView) ?: return
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscapeMode()) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.video_player_root)
        val insets = ViewCompat.getRootWindowInsets(root) ?: return
        val statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
        val topInset = maxOf(statusInsets.top, cutoutTop)
        val bottomInset = navInsets.bottom
        val container = playerContainer
        val containerParams = container?.layoutParams as? ConstraintLayout.LayoutParams

        if (isLandscapeMode()) {
            if (containerParams != null) {
                containerParams.topMargin = 0
                container.layoutParams = containerParams
            }
            playerTopBar?.setPadding(
                playerTopBar?.paddingLeft ?: 0,
                0,
                playerTopBar?.paddingRight ?: 0,
                playerTopBar?.paddingBottom ?: 0
            )
            playerBottomArea?.setPadding(
                playerBottomArea?.paddingLeft ?: 0,
                playerBottomArea?.paddingTop ?: 0,
                playerBottomArea?.paddingRight ?: 0,
                0
            )
        } else {
            if (containerParams != null) {
                containerParams.topMargin = topInset
                container.layoutParams = containerParams
            }
            playerTopBar?.setPadding(
                playerTopBar?.paddingLeft ?: 0,
                0,
                playerTopBar?.paddingRight ?: 0,
                playerTopBar?.paddingBottom ?: 0
            )
            playerBottomArea?.setPadding(
                playerBottomArea?.paddingLeft ?: 0,
                playerBottomArea?.paddingTop ?: 0,
                playerBottomArea?.paddingRight ?: 0,
                bottomInset
            )
            queueList?.setPadding(
                queueList?.paddingLeft ?: 0,
                queueList?.paddingTop ?: 0,
                queueList?.paddingRight ?: 0,
                maxOf(dpToPx(12f), bottomInset)
            )
        }
    }

    private fun initAspectControl(playerView: PlayerView) {
        aspectButton?.setOnClickListener {
            val next = when (playerView.resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            playerView.resizeMode = next
        }
    }

    private fun initSubtitlesControl(playerView: PlayerView) {
        subtitlesButton?.setOnClickListener {
            if (!hasSubtitles()) {
                Toast.makeText(this, getString(R.string.no_subtitles_available), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSubtitlesDialog()
        }
    }

    private fun initRotateControl() {
        rotateButton?.setOnClickListener {
            requestedOrientation = if (isLandscapeMode()) {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    private fun initLockControl() {
        lockButton?.setOnClickListener {
            toggleControlsLock()
        }
    }

    private fun toggleControlsLock() {
        controlsLocked = !controlsLocked
        val message = if (controlsLocked) {
            getString(R.string.controls_locked)
        } else {
            getString(R.string.controls_unlocked)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSubtitlesDialog() {
        val tracks = player?.currentTracks ?: return
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (textGroups.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_subtitles_available), Toast.LENGTH_SHORT).show()
            return
        }
        val items = ArrayList<String>()
        val selections = ArrayList<Pair<Tracks.Group, Int>>()
        items.add(getString(R.string.subtitles_off))
        for (group in textGroups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val label = when {
                    !format.label.isNullOrBlank() -> format.label
                    !format.language.isNullOrBlank() -> format.language
                    else -> null
                } ?: getString(R.string.subtitle_track_unknown, (selections.size + 1))
                items.add(label)
                selections.add(group to i)
            }
        }
        val disabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        var checkedIndex = 0
        if (!disabled) {
            for (index in selections.indices) {
                val (group, trackIndex) = selections[index]
                if (group.isTrackSelected(trackIndex)) {
                    checkedIndex = index + 1
                    break
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subtitles))
            .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                val exo = player ?: return@setSingleChoiceItems
                val paramsBuilder = exo.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    playerView?.subtitleView?.visibility = android.view.View.GONE
                } else {
                    val (group, trackIndex) = selections[which - 1]
                    paramsBuilder
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                    playerView?.subtitleView?.visibility = android.view.View.VISIBLE
                }
                exo.trackSelectionParameters = paramsBuilder.build()
                updateSubtitlesButtonState()
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.subtitle_settings)) { _, _ ->
                showSubtitleSettingsDialog()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun hasSubtitles(): Boolean {
        val tracks = player?.currentTracks ?: return false
        return tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported && it.length > 0 }
    }

    private fun updateSubtitlesButtonState() {
        val available = hasSubtitles()
        subtitlesButton?.isEnabled = available
        subtitlesButton?.imageAlpha = if (available) 255 else 90
    }

    private fun applySubtitleStyle() {
        val subtitleView = playerView?.subtitleView as? SubtitleView ?: return
        subtitleView.setStyle(subtitleStyle)
        subtitleView.setFractionalTextSize(subtitleTextSizeFraction)
        subtitleView.setApplyEmbeddedStyles(subtitleApplyEmbeddedStyles)
        subtitleView.setApplyEmbeddedFontSizes(subtitleApplyEmbeddedFontSizes)
    }

    private fun showSubtitleSettingsDialog() {
        val options = arrayOf(
            getString(R.string.subtitle_text_size),
            getString(R.string.subtitle_text_color),
            getString(R.string.subtitle_background),
            getString(R.string.subtitle_edge),
            if (subtitleApplyEmbeddedStyles) {
                getString(R.string.subtitle_embedded_styles_on)
            } else {
                getString(R.string.subtitle_embedded_styles_off)
            },
            if (subtitleApplyEmbeddedFontSizes) {
                getString(R.string.subtitle_embedded_font_sizes_on)
            } else {
                getString(R.string.subtitle_embedded_font_sizes_off)
            },
            getString(R.string.subtitle_reset)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subtitle_settings))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSubtitleTextSizeDialog()
                    1 -> showSubtitleTextColorDialog()
                    2 -> showSubtitleBackgroundDialog()
                    3 -> showSubtitleEdgeDialog()
                    4 -> {
                        subtitleApplyEmbeddedStyles = !subtitleApplyEmbeddedStyles
                        applySubtitleStyle()
                        persistSubtitlePreferences()
                        showSubtitleSettingsDialog()
                    }
                    5 -> {
                        subtitleApplyEmbeddedFontSizes = !subtitleApplyEmbeddedFontSizes
                        applySubtitleStyle()
                        persistSubtitlePreferences()
                        showSubtitleSettingsDialog()
                    }
                    6 -> resetSubtitleStyle()
                }
            }
            .show()
    }

    private fun showSubtitleTextSizeDialog() {
        val labels = arrayOf(
            getString(R.string.subtitle_size_small),
            getString(R.string.subtitle_size_medium),
            getString(R.string.subtitle_size_large)
        )
        val values = floatArrayOf(0.040f, 0.0533f, 0.070f)
        val currentIndex = values.indexOfFirst { kotlin.math.abs(it - subtitleTextSizeFraction) < 0.001f }
            .let { if (it >= 0) it else 1 }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subtitle_text_size))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                subtitleTextSizeFraction = values[which]
                applySubtitleStyle()
                persistSubtitlePreferences()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSubtitleTextColorDialog() {
        val labels = arrayOf(
            getString(R.string.subtitle_color_white),
            getString(R.string.subtitle_color_yellow),
            getString(R.string.subtitle_color_green),
            getString(R.string.subtitle_color_cyan)
        )
        val colors = intArrayOf(Color.WHITE, Color.YELLOW, Color.GREEN, Color.CYAN)
        val currentIndex = colors.indexOf(subtitleStyle.foregroundColor).let { if (it >= 0) it else 0 }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subtitle_text_color))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                updateSubtitleStyle(foregroundColor = colors[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSubtitleBackgroundDialog() {
        val labels = arrayOf(
            getString(R.string.subtitle_background_none),
            getString(R.string.subtitle_background_black),
            getString(R.string.subtitle_background_semi_black)
        )
        val colors = intArrayOf(Color.TRANSPARENT, Color.BLACK, 0xAA000000.toInt())
        val currentIndex = colors.indexOf(subtitleStyle.backgroundColor).let { if (it >= 0) it else 0 }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subtitle_background))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val color = colors[which]
                updateSubtitleStyle(backgroundColor = color, windowColor = Color.TRANSPARENT)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSubtitleEdgeDialog() {
        val labels = arrayOf(
            getString(R.string.subtitle_edge_none),
            getString(R.string.subtitle_edge_outline),
            getString(R.string.subtitle_edge_shadow)
        )
        val values = intArrayOf(
            CaptionStyleCompat.EDGE_TYPE_NONE,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        )
        val currentIndex = values.indexOf(subtitleStyle.edgeType).let { if (it >= 0) it else 0 }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subtitle_edge))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                updateSubtitleStyle(edgeType = values[which], edgeColor = Color.BLACK)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun resetSubtitleStyle() {
        subtitleStyle = CaptionStyleCompat.DEFAULT
        subtitleTextSizeFraction = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
        subtitleApplyEmbeddedStyles = true
        subtitleApplyEmbeddedFontSizes = true
        applySubtitleStyle()
        persistSubtitlePreferences()
    }

    private fun updateSubtitleStyle(
        foregroundColor: Int = subtitleStyle.foregroundColor,
        backgroundColor: Int = subtitleStyle.backgroundColor,
        windowColor: Int = subtitleStyle.windowColor,
        edgeType: Int = subtitleStyle.edgeType,
        edgeColor: Int = subtitleStyle.edgeColor
    ) {
        subtitleStyle = CaptionStyleCompat(
            foregroundColor,
            backgroundColor,
            windowColor,
            edgeType,
            edgeColor,
            subtitleStyle.typeface
        )
        applySubtitleStyle()
        persistSubtitlePreferences()
    }

    private fun showVideoInfo() {
        val title = currentPlaybackTitle()
        val author = currentPlaybackAuthor().ifBlank { getString(R.string.unknown) }
        val durationMs = player?.duration ?: C.TIME_UNSET
        val duration = if (durationMs > 0) formatTime(durationMs) else getString(R.string.unknown)
        val speed = player?.playbackParameters?.speed ?: 1.0f
        val subtitlesEnabled = hasSubtitles() &&
            (player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) != true)
        val format = player?.videoFormat
        val resolution = if (format != null && format.width > 0 && format.height > 0) {
            "${format.width}x${format.height}"
        } else {
            getString(R.string.unknown)
        }
        val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        val info = buildString {
            append(getString(R.string.title)).append(": ").append(title).append('\n')
            append(getString(R.string.author)).append(": ").append(author).append('\n')
            append(getString(R.string.length)).append(": ").append(duration).append('\n')
            append(getString(R.string.playback_speed)).append(": ").append(String.format("%.2fx", speed)).append('\n')
            append(getString(R.string.subtitles)).append(": ")
                .append(if (subtitlesEnabled) getString(R.string.enabled) else getString(R.string.disabled)).append('\n')
            append(getString(R.string.resolution)).append(": ").append(resolution).append('\n')
            append(getString(R.string.file_path)).append(": ").append(if (uri.isNotBlank()) uri else getString(R.string.unknown))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.video_info))
            .setMessage(info)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun updateTitleFromPath(path: String, preferredHistoryId: Long? = null) {
        val mediaId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        val candidateIds = listOfNotNull(mediaId, preferredHistoryId).distinct()
        val currentItem = candidateIds.asSequence()
            .mapNotNull { id -> queueItems.firstOrNull { it.id == id } }
            .firstOrNull()
            ?: queueItems.firstOrNull { it.downloadPath.any { p -> p == path || path.endsWith(p) } }
        if (currentItem != null) {
            updatePlayerArtwork(currentItem)
            updateTitleViews(currentItem.title, currentItem.author)
            playerNotificationManager?.invalidate()
            return
        }
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) {
                val historyDao = DBManager.getInstance(this@VideoPlayerActivity).historyDao
                candidateIds.asSequence()
                    .mapNotNull { id -> runCatching { historyDao.getItem(id) }.getOrNull() }
                    .firstOrNull()
                    ?: historyDao.getItemByDownloadPath(path)
            }
            if (item != null) {
                updatePlayerArtwork(item)
                updateTitleViews(item.title, item.author)
            } else {
                updatePlayerArtwork(null)
                updateTitleViews(File(path).name, "")
            }
            playerNotificationManager?.invalidate()
        }
    }

    private fun updatePlayerArtwork(item: HistoryItem?) {
        if (item?.type != DownloadType.audio) {
            currentArtworkKey = null
            playerView?.defaultArtwork = null
            return
        }

        val source = resolvePreferredThumbSource(item)
        if (source.isNullOrBlank()) {
            currentArtworkKey = null
            playerView?.defaultArtwork = null
            return
        }

        val key = "${item.id}|$source"
        if (currentArtworkKey == key) return
        currentArtworkKey = key
        lifecycleScope.launch {
            val resolvedSource = withContext(Dispatchers.IO) {
                ensureLocalThumbForPlayback(item, source)
            }
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    Picasso.get().load(resolvedSource).resize(1280, 720).centerCrop().get()
                }.getOrNull()
            }
            if (currentArtworkKey == key) {
                playerView?.defaultArtwork = bitmap?.let { BitmapDrawable(resources, it) }
            }
        }
    }

    private fun updateTitleViews(title: String, author: String) {
        titleView?.text = if (title.isNotBlank()) title else getString(R.string.app_name)
        if (author.isNotBlank()) {
            authorView?.text = author
            authorView?.visibility = android.view.View.VISIBLE
        } else {
            authorView?.text = ""
            authorView?.visibility = android.view.View.GONE
        }
        updateMediaSessionMetadata()
        playerNotificationManager?.invalidate()
    }

    private fun loadQueueForContext(videoPath: String) {
        if (queueList == null) return
        autoScrollQueueToCurrent = true
        logPlaybackTiming("loadQueueForContext start videoPath=$videoPath")
        val authorFilter = intent.getStringExtra("context_author").orEmpty()
        val playlistId = intent.getLongExtra("context_playlist_id", -1L)
        val playlistName = intent.getStringExtra("context_playlist_name").orEmpty()
        val keywordFilter = intent.getStringExtra("context_keyword").orEmpty()
        val queryFilter = intent.getStringExtra("context_query").orEmpty()
        val titleQueryFilter = intent.getStringExtra("context_title_query").orEmpty()
        val keywordQueryFilter = intent.getStringExtra("context_keyword_query").orEmpty()
        val creatorQueryFilter = intent.getStringExtra("context_creator_query").orEmpty()
        val typeFilter = intent.getStringExtra("context_type").orEmpty()
        val websiteFilter = intent.getStringExtra("context_website").orEmpty()
        val youtuberGroupId = intent.getLongExtra("context_youtuber_group_id", -1L)
        val statusFilter = runCatching {
            HistoryViewModel.HistoryStatus.valueOf(
                intent.getStringExtra("context_status") ?: HistoryViewModel.HistoryStatus.ALL.name
            )
        }.getOrDefault(HistoryViewModel.HistoryStatus.ALL)
        val includeChildCategoryVideos = intent.getBooleanExtra("context_include_child_category_videos", false)
        val excludedChildKeywords = parseCsvSet(intent.getStringExtra("context_excluded_child_keywords").orEmpty())
        val searchFields = parseSearchFields(intent.getStringExtra("context_search_fields").orEmpty())
        val prefetchedHistoryIds = intent.getLongArrayExtra("context_prefetched_history_ids")
            ?.asSequence()
            ?.filter { it > 0L }
            ?.toList()
            .orEmpty()
        val prefetchedTotalCount = intent.getIntExtra("context_prefetched_total_count", -1)
        val sortType = runCatching {
            HistoryRepository.HistorySortType.valueOf(
                intent.getStringExtra("context_sort_type") ?: HistoryRepository.HistorySortType.DATE.name
            )
        }.getOrDefault(HistoryRepository.HistorySortType.DATE)
        val sortOrder = runCatching {
            DBManager.SORTING.valueOf(
                intent.getStringExtra("context_sort_order") ?: DBManager.SORTING.DESC.name
            )
        }.getOrDefault(DBManager.SORTING.DESC)
        updateQueueTitle(authorFilter, playlistId, playlistName)
        val db = DBManager.getInstance(this)
        val initialUri = uriFromPath(videoPath)
        lifecycleScope.launch {
            if (prefetchedHistoryIds.isNotEmpty()) {
                val prefetchedItems = withContext(Dispatchers.IO) {
                    val historyRepo = HistoryRepository(db.historyDao, db.playlistDao)
                    val itemsById = historyRepo.getItemsFromIDs(prefetchedHistoryIds).associateBy { it.id }
                    prefetchedHistoryIds.asSequence()
                        .mapNotNull { id -> itemsById[id] }
                        .filter { it.isPlayableInQueue() }
                        .filter { passesStatusFilter(it, statusFilter) }
                        .map { resolveLocalTreePath(db, it) }
                        .mapNotNull { item ->
                            val playablePath = item.downloadPath.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                            item to playablePath
                        }
                        .toList()
                }
                if (prefetchedItems.isNotEmpty()) {
                    logPlaybackTiming("loadQueueForContext prefetched size=${prefetchedItems.size}")
                    val previewItems = prefetchedItems.map { it.first }
                    queuePlayablePathById.clear()
                    queueMediaUriById.clear()
                    queueIdByUri.clear()
                    prefetchedItems.forEach { (item, playablePath) ->
                        val mediaUri = uriFromPath(playablePath)
                        queuePlayablePathById[item.id] = playablePath
                        queueMediaUriById[item.id] = mediaUri
                        queueIdByUri[mediaUri.toString()] = item.id
                    }
                    val currentId = queueIdByUri[initialUri.toString()] ?: previewItems.firstOrNull()?.id
                    baseQueueItems = previewItems
                    isShuffled = false
                    updateShuffleButton()
                    applyQueueOrder(
                        items = previewItems,
                        currentItemId = currentId,
                        currentPos = resolveQueueStartPositionFromItems(previewItems, currentId, launchPlaybackPositionMs),
                        playWhenReady = true,
                        forceScrollCurrentToTop = true
                    )
                    if (prefetchedTotalCount > 0 && previewItems.size >= prefetchedTotalCount) {
                        return@launch
                    }
                }
            }
            val items = withContext(Dispatchers.IO) {
                val historyRepo = HistoryRepository(db.historyDao, db.playlistDao)
                val keywordForBaseQuery = if (includeChildCategoryVideos && keywordFilter.isNotBlank()) "" else keywordFilter
                val ids = historyRepo.getFilteredIDs(
                    query = queryFilter,
                    type = typeFilter,
                    author = authorFilter,
                    keyword = keywordForBaseQuery,
                    titleQuery = titleQueryFilter,
                    keywordQuery = keywordQueryFilter,
                    creatorQuery = creatorQueryFilter,
                    sortType = sortType,
                    order = sortOrder,
                    status = Unit,
                    website = websiteFilter,
                    playlistId = playlistId,
                    searchFields = searchFields
                )
                val relationIds = historyRepo.getFilteredIDs(
                    query = queryFilter,
                    type = typeFilter,
                    author = "",
                    keyword = "",
                    titleQuery = titleQueryFilter,
                    keywordQuery = keywordQueryFilter,
                    creatorQuery = creatorQueryFilter,
                    sortType = sortType,
                    order = sortOrder,
                    status = Unit,
                    website = websiteFilter,
                    playlistId = playlistId,
                    searchFields = searchFields
                )
                val groupFilteredIds = filterIdsByYoutuberGroup(
                    db = db,
                    historyRepo = historyRepo,
                    ids = ids,
                    youtuberGroupId = youtuberGroupId,
                    includeChildGroups = includeChildCategoryVideos
                )
                val groupFilteredRelationIds = filterIdsByYoutuberGroup(
                    db = db,
                    historyRepo = historyRepo,
                    ids = relationIds,
                    youtuberGroupId = youtuberGroupId,
                    includeChildGroups = includeChildCategoryVideos
                )
                val filteredIds = applyChildKeywordFiltersToIds(
                    historyRepo = historyRepo,
                    ids = groupFilteredIds,
                    relationIds = groupFilteredRelationIds,
                    authorFilter = authorFilter,
                    keywordFilter = keywordFilter,
                    includeChildCategoryVideos = includeChildCategoryVideos,
                    excludedChildKeywords = excludedChildKeywords
                )
                historyRepo.getItemsFromIDs(filteredIds).asSequence()
                    .filter { it.isPlayableInQueue() }
                    .filter { passesStatusFilter(it, statusFilter) }
                    .map { resolveLocalTreePath(db, it) }
                    .mapNotNull { item ->
                        val playablePath = item.downloadPath.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                        item to playablePath
                    }
                    .toList()
            }
            val fullItems = sortQueueItems(items.map { it.first }, sortType, sortOrder)
            logPlaybackTiming("loadQueueForContext full size=${fullItems.size}")
            baseQueueItems = fullItems
            if (fullItems.isEmpty()) {
                playSinglePath(videoPath, launchHistoryId, launchPlaybackPositionMs ?: 0L)
                return@launch
            }
            val preparedQueueData = withContext(Dispatchers.Default) {
                prepareQueueData(
                    items = fullItems,
                    preferredPlayablePaths = items.associate { (item, playablePath) -> item.id to playablePath },
                    previousPlayablePaths = queuePlayablePathById.toMap()
                )
            }
            val currentId = preparedQueueData.idByUri[initialUri.toString()] ?: fullItems.firstOrNull()?.id
            isShuffled = false
            updateShuffleButton()
            applyQueueOrder(
                items = fullItems,
                currentItemId = currentId,
                currentPos = resolveQueueStartPositionFromItems(fullItems, currentId, launchPlaybackPositionMs),
                preparedQueueData = preparedQueueData,
                playWhenReady = true,
                forceScrollCurrentToTop = true
            )
        }
    }

    private fun resolveQueueStartPosition(currentItemId: Long?, requestedPositionMs: Long?): Long {
        val currentHistoryId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        val liveCurrentPosition = player?.currentPosition ?: 0L
        if (currentItemId != null &&
            currentHistoryId == currentItemId &&
            liveCurrentPosition >= 5_000L &&
            requestedPositionMs != null &&
            liveCurrentPosition > requestedPositionMs
        ) {
            logPlaybackPosition(
                "resolveQueueStartPosition usingLiveCurrent currentId=$currentItemId requested=$requestedPositionMs resolved=$liveCurrentPosition"
            )
            return liveCurrentPosition
        }
        val savedPosition = if (currentItemId == null) {
            null
        } else {
            playbackPositionsById[currentItemId]
                ?: queueItems.firstOrNull { it.id == currentItemId }?.playbackPositionMs
        }
        val resolved = if (requestedPositionMs != null && requestedPositionMs > 0L) {
            maxOf(requestedPositionMs, savedPosition ?: 0L)
        } else if (currentItemId == null) {
            0L
        } else {
            savedPosition ?: 0L
        }
        logPlaybackPosition(
            "resolveQueueStartPosition currentId=$currentItemId requested=$requestedPositionMs " +
                "saved=$savedPosition resolved=$resolved current=${player?.currentPosition ?: -1L}"
        )
        return resolved
    }

    private fun resolveQueueStartPositionFromItems(
        items: List<HistoryItem>,
        currentItemId: Long?,
        requestedPositionMs: Long?
    ): Long {
        val cachedPosition = currentItemId?.let { getCachedPlaybackPosition(it) }?.takeIf { it > 0L }
        val itemSavedPosition = currentItemId
            ?.let { id -> items.firstOrNull { it.id == id }?.playbackPositionMs }
            ?.takeIf { it > 0L }
        val resolved = maxOf(
            requestedPositionMs ?: 0L,
            itemSavedPosition ?: 0L,
            cachedPosition ?: 0L
        )
        logPlaybackPosition(
            "resolveQueueStartPositionFromItems currentId=$currentItemId requested=$requestedPositionMs itemSaved=$itemSavedPosition cached=$cachedPosition resolved=$resolved"
        )
        return resolved
    }

    private fun sortQueueItems(
        items: List<HistoryItem>,
        sortType: HistoryRepository.HistorySortType,
        sortOrder: DBManager.SORTING
    ): List<HistoryItem> {
        val sorted = when (sortType) {
            HistoryRepository.HistorySortType.TITLE ->
                items.sortedBy { it.title.lowercase(java.util.Locale.getDefault()) }
            HistoryRepository.HistorySortType.AUTHOR ->
                items.sortedBy { it.author.lowercase(java.util.Locale.getDefault()) }
            HistoryRepository.HistorySortType.DURATION ->
                items.sortedBy { it.durationSeconds }
            HistoryRepository.HistorySortType.DATE ->
                items.sortedBy { it.time }
        }
        return if (sortOrder == DBManager.SORTING.DESC) sorted.asReversed() else sorted
    }

    private fun scrollCurrentToTopIfAllowed(currentId: Long?, force: Boolean = false) {
        if (!force && !autoScrollQueueToCurrent) return
        val recycler = queueList ?: return
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        val localIndex = if (currentId != null) queueIndexById[currentId] ?: -1 else -1
        if (localIndex < 0) return
        logPlaybackTiming("scrollCurrentToTopIfAllowed request currentId=$currentId index=$localIndex force=$force")
        recycler.post {
            recycler.stopScroll()
            val visibleCount = layoutManager.childCount
            if (visibleCount <= 0) {
                layoutManager.scrollToPositionWithOffset(localIndex, 0)
                recycler.post { layoutManager.scrollToPositionWithOffset(localIndex, 0) }
                return@post
            }
            val maxTopIndex = (queueItems.size - visibleCount).coerceAtLeast(0)
            if (localIndex > maxTopIndex) {
                layoutManager.scrollToPosition(queueItems.size - 1)
            } else {
                layoutManager.scrollToPositionWithOffset(localIndex, 0)
            }
            recycler.post { layoutManager.scrollToPositionWithOffset(localIndex, 0) }
        }
    }

    private fun updateCurrentQueueSelection(
        scrollToCurrent: Boolean,
        resetRecentWatchTimer: Boolean,
        forceScrollToCurrentTop: Boolean = false
    ) {
        val currentId = findCurrentQueueItemId()
        queueAdapter?.setCurrentItemId(currentId)
        if (resetRecentWatchTimer && currentId != null) {
            resetRecentWatch(currentId)
        }
        if (scrollToCurrent) {
            scrollCurrentToTopIfAllowed(currentId, force = forceScrollToCurrentTop)
        }
    }

    private fun findCurrentQueueItemId(): Long? {
        val currentMediaId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        if (currentMediaId != null && queueItems.any { it.id == currentMediaId }) {
            return currentMediaId
        }
        val currentUri = player?.currentMediaItem?.localConfiguration?.uri?.toString() ?: return null
        return queueIdByUri[currentUri] ?: queueItems.firstOrNull { item ->
            item.downloadPath.any { path -> uriFromPath(path).toString() == currentUri }
        }?.id
    }

    private fun updateQueueTitle(authorFilter: String, playlistId: Long, playlistName: String) {
        val title = when {
            playlistId > 0L && playlistName.isNotBlank() -> playlistName
            authorFilter.isNotBlank() -> authorFilter
            playlistId > 0L -> getString(R.string.queue_title_playlist)
            else -> getString(R.string.queue_title_all)
        }
        queueTitle?.text = title
    }

    private fun parseSearchFields(raw: String): Set<HistoryRepository.SearchField> {
        if (raw.isBlank()) {
            return setOf(
                HistoryRepository.SearchField.TITLE,
                HistoryRepository.SearchField.KEYWORDS
            )
        }
        val parsed = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { name ->
                runCatching { HistoryRepository.SearchField.valueOf(name) }.getOrNull()
            }
            .toSet()
        return if (parsed.isEmpty()) {
            setOf(
                HistoryRepository.SearchField.TITLE,
                HistoryRepository.SearchField.KEYWORDS
            )
        } else {
            parsed
        }
    }

    private fun parseCsvSet(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun passesStatusFilter(item: HistoryItem, status: HistoryViewModel.HistoryStatus): Boolean {
        return when (status) {
            HistoryViewModel.HistoryStatus.DELETED -> item.downloadPath.any { path -> !FileUtil.exists(path) }
            HistoryViewModel.HistoryStatus.NOT_DELETED -> item.downloadPath.any { path -> FileUtil.exists(path) }
            HistoryViewModel.HistoryStatus.MISSING_THUMBNAIL -> {
                val hasCustomThumb = item.customThumb.isNotBlank() && FileUtil.exists(item.customThumb)
                val hasThumb = item.thumb.isNotBlank()
                !hasCustomThumb && !hasThumb
            }
            HistoryViewModel.HistoryStatus.CUSTOM_THUMBNAIL -> {
                item.customThumb.isNotBlank() && FileUtil.exists(item.customThumb)
            }
            HistoryViewModel.HistoryStatus.HARDSUB_DONE -> item.hardSubDone
            else -> true
        }
    }

    private fun applyChildKeywordFiltersToIds(
        historyRepo: HistoryRepository,
        ids: List<Long>,
        relationIds: List<Long>,
        authorFilter: String,
        keywordFilter: String,
        includeChildCategoryVideos: Boolean,
        excludedChildKeywords: Set<String>
    ): List<Long> {
        if (ids.isEmpty()) return ids
        if (authorFilter.isBlank() && keywordFilter.isBlank()) return ids

        val allKeywords = historyRepo.getKeywordsWithInfoForHistoryIds(relationIds)
        if (allKeywords.isEmpty()) return ids

        val selectedKeywordInfo = if (keywordFilter.isBlank()) {
            null
        } else {
            allKeywords.firstOrNull { it.keyword.equals(keywordFilter.trim(), ignoreCase = true) }
        }
        val byName = allKeywords.associateBy { it.keyword }

        val videoKeywordNamesLower: Set<String> = when {
            selectedKeywordInfo != null && includeChildCategoryVideos -> {
                val names = mutableSetOf(selectedKeywordInfo.keyword)
                val stack = ArrayDeque<String>()
                stack.addAll(selectedKeywordInfo.childKeywords)
                while (stack.isNotEmpty()) {
                    val current = stack.removeFirst()
                    if (!names.add(current)) continue
                    byName[current]?.childKeywords.orEmpty().forEach { child -> stack.addLast(child) }
                }
                names.map { it.lowercase(java.util.Locale.getDefault()) }.toSet()
            }
            selectedKeywordInfo != null -> setOf(selectedKeywordInfo.keyword.lowercase(java.util.Locale.getDefault()))
            else -> emptySet()
        }

        val excludedLower: Set<String> = when {
            authorFilter.isNotBlank() -> {
                val normalizedAuthor = normalizeCreator(authorFilter)
                val authorKeywords = allKeywords.filter {
                    val creator = it.uniqueCreator ?: return@filter false
                    normalizeCreator(creator) == normalizedAuthor
                }
                buildExcludedRecursiveForAuthor(authorKeywords, excludedChildKeywords, includeChildCategoryVideos)
                    .map { it.lowercase(java.util.Locale.getDefault()) }
                    .toSet()
            }
            selectedKeywordInfo != null -> {
                buildExcludedRecursiveForKeyword(selectedKeywordInfo, byName, excludedChildKeywords, includeChildCategoryVideos)
                    .map { it.lowercase(java.util.Locale.getDefault()) }
                    .toSet()
            }
            else -> emptySet()
        }

        if (videoKeywordNamesLower.isEmpty() && excludedLower.isEmpty()) return ids
        val itemsById = historyRepo.getItemsFromIDs(ids).associateBy { it.id }
        return ids.filter { id ->
            val item = itemsById[id] ?: return@filter false
            val itemKeywordsLower = splitKeywordsForFilter(item.keywords)
                .map { it.lowercase(java.util.Locale.getDefault()) }
                .toSet()
            val matchesKeyword = if (keywordFilter.isNotBlank() && videoKeywordNamesLower.isNotEmpty()) {
                itemKeywordsLower.any { videoKeywordNamesLower.contains(it) }
            } else {
                true
            }
            if (!matchesKeyword) return@filter false
            if (excludedLower.isNotEmpty() && itemKeywordsLower.any { excludedLower.contains(it) }) {
                return@filter false
            }
            true
        }
    }

    private fun normalizeCreator(value: String): String {
        return value.trim().trim('"').lowercase(java.util.Locale.getDefault())
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
            .distinctBy { it.lowercase(java.util.Locale.getDefault()) }
    }

    private fun filterIdsByYoutuberGroup(
        db: DBManager,
        historyRepo: HistoryRepository,
        ids: List<Long>,
        youtuberGroupId: Long,
        includeChildGroups: Boolean
    ): List<Long> {
        if (youtuberGroupId < 0L || ids.isEmpty()) return ids

        val groupDao = db.youtuberGroupDao
        val members = groupDao.getAllMembers()
        if (members.isEmpty()) return emptyList()

        val targetGroupIds = if (includeChildGroups) {
            val childrenByParent = groupDao.getAllRelations()
                .groupBy { it.parentGroupId }
                .mapValues { entry -> entry.value.map { it.childGroupId } }
            val visited = linkedSetOf<Long>()
            val stack = ArrayDeque<Long>()
            stack.addLast(youtuberGroupId)
            while (stack.isNotEmpty()) {
                val groupId = stack.removeFirst()
                if (!visited.add(groupId)) continue
                childrenByParent[groupId].orEmpty().forEach { stack.addLast(it) }
            }
            visited
        } else {
            setOf(youtuberGroupId)
        }

        if (targetGroupIds.isEmpty()) return emptyList()
        val allowedAuthorsLower = members.asSequence()
            .filter { targetGroupIds.contains(it.groupId) }
            .map { normalizeCreator(it.author) }
            .toSet()
        if (allowedAuthorsLower.isEmpty()) return emptyList()

        val itemsById = historyRepo.getItemsFromIDs(ids).associateBy { it.id }
        return ids.filter { id ->
            val item = itemsById[id] ?: return@filter false
            extractItemCreators(item).any { creator ->
                allowedAuthorsLower.contains(normalizeCreator(creator))
            }
        }
    }

    private fun splitKeywordsForFilter(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
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
                authorKeywords.filter { excludedChildKeywords.contains(it.keyword) }.map { it.keyword }
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
            !includeChildCategoryVideos -> selectedKeywordInfo.childKeywords
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
            byName[keyword]?.childKeywords.orEmpty().forEach { child -> stack.addLast(child) }
        }
        return out
    }

    private fun playSinglePath(path: String, preferredHistoryId: Long? = null, startPositionMs: Long = 0L) {
        val historyId = preferredHistoryId ?: queueItems.firstOrNull { it.downloadPath.contains(path) }?.id
        val mediaItem = MediaItem.Builder()
            .setUri(uriFromPath(path))
            .setMediaId(historyId?.toString() ?: "")
            .build()
        logPlaybackPosition(
            "playSinglePath historyId=$historyId start=$startPositionMs current=${player?.currentPosition ?: -1L} path=$path"
        )
        if (historyId != null && startPositionMs > 0L) {
            skipNextPlaybackRestoreHistoryId = historyId
        }
        player?.setMediaItem(mediaItem, startPositionMs)
        player?.prepare()
        player?.playWhenReady = true
        updateTitleFromPath(path)
        updatePlaybackUiProgress()
    }

    private fun uriFromPath(path: String): Uri {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            return Uri.parse(path)
        }
        val docUri = buildDocumentUriForPath(path)
        return docUri ?: Uri.fromFile(File(path))
    }

    private fun buildDocumentUriForPath(path: String): Uri? {
        return FileUtil.buildDocumentUriForPath(path)
    }

    private fun resolveLocalTreePath(db: DBManager, item: HistoryItem): HistoryItem {
        if (item.localTreeUri.isBlank() || item.localTreePath.isBlank()) return item
        if (item.downloadPath.any { FileUtil.exists(it) }) return item
        val resolvedUri = FileUtil.resolveTreeDocumentUri(item.localTreeUri, item.localTreePath) ?: return item
        val resolvedPath = resolvedUri.toString()
        if (!FileUtil.exists(resolvedPath)) return item
        val updated = item.copy(downloadPath = listOf(resolvedPath))
        runCatching { db.historyDao.update(updated) }
        return updated
    }

    private fun showSeekOverlay(targetMs: Long, deltaMs: Long) {
        val total = formatTime(targetMs)
        val delta = formatDelta(deltaMs)
        seekTime?.text = total
        seekDelta?.text = "($delta)"
        showOnly(seekOverlay)
    }

    private fun showVolumeOverlay(volumePct: Int) {
        valueText?.text = volumePct.toString()
        setBarFill(rightBarContainer, rightBarFill, volumePct)
        showOnly(valueOverlay, rightBarOverlay)
    }

    private fun showBrightnessOverlay(brightnessPct: Int) {
        valueText?.text = brightnessPct.toString()
        setBarFill(leftBarContainer, leftBarFill, brightnessPct)
        showOnly(valueOverlay, leftBarOverlay)
    }

    private fun showHoldSpeedOverlay(speed: Float) {
        holdSpeedOverlay?.text = String.format("x%.1f", speed)
        holdSpeedOverlay?.visibility = android.view.View.VISIBLE
    }

    private fun hideHoldSpeedOverlay() {
        holdSpeedOverlay?.visibility = android.view.View.GONE
    }

    private fun hideGestureOverlaysImmediate() {
        overlayHideRunnable?.let { overlayHandler.removeCallbacks(it) }
        gestureOverlayBg?.visibility = android.view.View.GONE
        seekOverlay?.visibility = android.view.View.GONE
        valueOverlay?.visibility = android.view.View.GONE
        leftBarOverlay?.visibility = android.view.View.GONE
        rightBarOverlay?.visibility = android.view.View.GONE
    }

    private fun showOnly(vararg views: android.view.View?) {
        listOf(gestureOverlayBg, seekOverlay, valueOverlay, leftBarOverlay, rightBarOverlay)
            .forEach { it?.visibility = android.view.View.GONE }
        gestureOverlayBg?.visibility = android.view.View.VISIBLE
        views.forEach { it?.visibility = android.view.View.VISIBLE }
        overlayHideRunnable?.let { overlayHandler.removeCallbacks(it) }
        val hide = Runnable {
            gestureOverlayBg?.visibility = android.view.View.GONE
            views.forEach { it?.visibility = android.view.View.GONE }
        }
        overlayHideRunnable = hide
        overlayHandler.postDelayed(hide, 800)
    }

    private fun setBarFill(container: android.view.View?, fill: android.view.View?, percent: Int) {
        val safePercent = percent.coerceIn(0, 100)
        val height = container?.height ?: 0
        if (height == 0) {
            container?.post { setBarFill(container, fill, safePercent) }
            return
        }
        val fillHeight = (height * safePercent / 100f).toInt().coerceAtLeast(4)
        val params = fill?.layoutParams
        if (params != null) {
            params.height = fillHeight
            fill?.layoutParams = params
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}:${seconds.toString().padStart(2, '0')}"
    }

    private fun formatDelta(ms: Long): String {
        val sign = if (ms >= 0) "+" else "-"
        val abs = kotlin.math.abs(ms)
        return "$sign${formatTime(abs)}"
    }

    private fun resolveSwipeTouchZone(
        playerWidth: Float,
        playerHeight: Float,
        x: Float,
        y: Float
    ): SwipeTouchZone {
        val sideDeadZone = dpToPx(20f).toFloat()
        val topDeadZone = dpToPx(40f).toFloat()
        val bottomDeadZone = if (isLandscapeMode()) {
            dpToPx(80f).toFloat()
        } else {
            dpToPx(28f).toFloat()
        }
        if (playerWidth <= 0f || playerHeight <= 0f) return SwipeTouchZone.NONE
        if (x <= sideDeadZone || x >= playerWidth - sideDeadZone) return SwipeTouchZone.NONE
        if (y <= topDeadZone || y >= playerHeight - bottomDeadZone) return SwipeTouchZone.NONE

        val effectiveLeft = sideDeadZone
        val effectiveWidth = (playerWidth - sideDeadZone * 2f).coerceAtLeast(1f)
        val relativeX = ((x - effectiveLeft) / effectiveWidth).coerceIn(0f, 1f)

        return when {
            relativeX < 0.375f -> SwipeTouchZone.BRIGHTNESS
            relativeX > 0.625f -> SwipeTouchZone.VOLUME
            else -> SwipeTouchZone.SEEK
        }
    }

    private fun initGestureControls(playerView: PlayerView) {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val width = playerView.width.coerceAtLeast(1)
                    val isLeft = e.x < width / 2f
                    val curr = player?.currentPosition ?: 0L
                    val seekBy = 10_000L
                    val target = if (isLeft) (curr - seekBy).coerceAtLeast(0L) else (curr + seekBy)
                    player?.seekTo(target)
                    return true
                }
            })
        }

        if (touchSlop == 0) {
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        }
        val seekActivationThresholdPx = maxOf(dpToPx(40f).toFloat(), touchSlop * 1.4f)
        val verticalActivationThresholdPx = maxOf(dpToPx(36f).toFloat(), touchSlop * 1.35f)
        val systemGestureDominanceRatio = 1.15f
        var touchDownZone = SwipeTouchZone.NONE
        playerView.setOnTouchListener { _, event ->
            if (controlsLocked) {
                return@setOnTouchListener false
            }
            if (isInPictureInPictureMode) {
                return@setOnTouchListener true
            }
            gestureDetector?.onTouchEvent(event)
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val width = playerView.width.coerceAtLeast(1).toFloat()
                    val height = playerView.height.coerceAtLeast(1).toFloat()
                    touchDownZone = resolveSwipeTouchZone(width, height, event.x, event.y)
                    if (touchDownZone == SwipeTouchZone.NONE) {
                        holdSpeedRunnable?.let { overlayHandler.removeCallbacks(it) }
                        return@setOnTouchListener false
                    }
                    touchStartY = event.y
                    touchStartX = event.x
                    initialVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    initialVolumePercent = if (maxVolume == 0) 0 else (initialVolume * 100 / maxVolume)
                    val currBrightness = window.attributes.screenBrightness
                    initialBrightness = if (currBrightness < 0f) 0.5f else currBrightness
                    adjusting = false
                    seeking = false
                    activeSwipeGesture = SwipeGestureType.NONE
                    triggeredCentralSwipeAction = false
                    consumedGestureInteraction = false
                    initialSeekPosition = player?.currentPosition ?: 0L
                    holdSpeedActive = false
                    holdSpeedOriginal = player?.playbackParameters?.speed ?: 1.0f
                    holdSpeedRunnable?.let { overlayHandler.removeCallbacks(it) }
                    val holdRunnable = Runnable {
                        holdSpeedActive = true
                        player?.setPlaybackSpeed(holdPlaybackSpeed)
                        updateSpeedLabel(holdPlaybackSpeed)
                        showHoldSpeedOverlay(holdPlaybackSpeed)
                        hideControlsForGesture(playerView)
                    }
                    holdSpeedRunnable = holdRunnable
                    overlayHandler.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = touchStartY - event.y
                    val deltaX = event.x - touchStartX
                    if (!holdSpeedActive && (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)) {
                        holdSpeedRunnable?.let { overlayHandler.removeCallbacks(it) }
                    }
                    if (triggeredCentralSwipeAction) {
                        return@setOnTouchListener true
                    }
                    if (activeSwipeGesture == SwipeGestureType.NONE) {
                        if (touchDownZone == SwipeTouchZone.SEEK &&
                            kotlin.math.abs(deltaY) > verticalActivationThresholdPx &&
                            kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * systemGestureDominanceRatio
                        ) {
                            if (deltaY > 0f) {
                                consumedGestureInteraction = true
                                triggeredCentralSwipeAction = true
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                return@setOnTouchListener true
                            }
                            if (isLandscapeMode()) {
                                consumedGestureInteraction = true
                                triggeredCentralSwipeAction = true
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                                return@setOnTouchListener true
                            }
                            consumedGestureInteraction = true
                            triggeredCentralSwipeAction = true
                            enterPipIfSupported()
                            return@setOnTouchListener true
                        }
                        activeSwipeGesture = when (touchDownZone) {
                            SwipeTouchZone.SEEK -> {
                                if (kotlin.math.abs(deltaX) > seekActivationThresholdPx &&
                                    kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * systemGestureDominanceRatio
                                ) {
                                    SwipeGestureType.SEEK
                                } else {
                                    SwipeGestureType.NONE
                                }
                            }
                            SwipeTouchZone.BRIGHTNESS -> {
                                if (kotlin.math.abs(deltaY) > verticalActivationThresholdPx &&
                                    kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * systemGestureDominanceRatio
                                ) {
                                    SwipeGestureType.BRIGHTNESS
                                } else {
                                    SwipeGestureType.NONE
                                }
                            }
                            SwipeTouchZone.VOLUME -> {
                                if (kotlin.math.abs(deltaY) > verticalActivationThresholdPx &&
                                    kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * systemGestureDominanceRatio
                                ) {
                                    SwipeGestureType.VOLUME
                                } else {
                                    SwipeGestureType.NONE
                                }
                            }
                            SwipeTouchZone.NONE -> SwipeGestureType.NONE
                        }
                    }
                    if (activeSwipeGesture == SwipeGestureType.SEEK) {
                        consumedGestureInteraction = true
                        seeking = true
                        hideControlsForGesture(playerView)
                        val width = playerView.width.coerceAtLeast(1)
                        val percent = (deltaX / width).coerceIn(-1f, 1f)
                        val seekBy = (percent * 80_000L).toLong()
                        val target = (initialSeekPosition + seekBy).coerceAtLeast(0L)
                        player?.seekTo(target)
                        showSeekOverlay(target, seekBy)
                        return@setOnTouchListener true
                    }
                    if (activeSwipeGesture == SwipeGestureType.NONE) {
                        return@setOnTouchListener true
                    }
                    consumedGestureInteraction = true
                    adjusting = true
                    val height = playerView.height.coerceAtLeast(1)
                    val percent = (deltaY / height).coerceIn(-1f, 1f) * 2f
                    hideControlsForGesture(playerView)
                    if (activeSwipeGesture == SwipeGestureType.BRIGHTNESS) {
                        val newBrightness = (initialBrightness + percent).coerceIn(0.0f, 1.0f)
                        val attrs = window.attributes
                        attrs.screenBrightness = newBrightness
                        window.attributes = attrs
                        val brightnessPct = (newBrightness * 100).toInt()
                        showBrightnessOverlay(brightnessPct)
                    } else if (activeSwipeGesture == SwipeGestureType.VOLUME) {
                        val stepPx = height / 100f
                        val steps = (deltaY / stepPx).toInt()
                        val newPercent = (initialVolumePercent + steps).coerceIn(0, 100)
                        val newVolume = if (maxVolume == 0) 0 else ((newPercent * maxVolume + 50) / 100)
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                        showVolumeOverlay(newPercent)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    holdSpeedRunnable?.let { overlayHandler.removeCallbacks(it) }
                    if (holdSpeedActive) {
                        player?.setPlaybackSpeed(holdSpeedOriginal)
                        updateSpeedLabel(holdSpeedOriginal)
                    }
                    hideHoldSpeedOverlay()
                    hideGestureOverlaysImmediate()
                    if (!adjusting && !seeking && !consumedGestureInteraction) {
                        playerView.performClick()
                    }
                    adjusting = false
                    seeking = false
                    activeSwipeGesture = SwipeGestureType.NONE
                    triggeredCentralSwipeAction = false
                    consumedGestureInteraction = false
                    touchDownZone = SwipeTouchZone.NONE
                    showControlsAfterGesture(playerView)
                    true
                }
                else -> false
            }
        }
    }

    private fun hideControlsForGesture(playerView: PlayerView) {
        if (!controlsHiddenByGesture) {
            playerView.hideController()
            controlsHiddenByGesture = true
        }
    }

    private fun showControlsAfterGesture(playerView: PlayerView) {
        if (controlsHiddenByGesture) {
            playerView.showController()
            controlsHiddenByGesture = false
        }
    }
    private fun toggleRepeatMode() {
        val current = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        val next = when (current) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player?.repeatMode = next
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putInt(PREF_REPEAT_MODE, next)
            .apply()
        updateRepeatButton()
    }

    private fun updateRepeatButton() {
        when (player?.repeatMode ?: Player.REPEAT_MODE_OFF) {
            Player.REPEAT_MODE_ONE -> {
                repeatButton?.setImageResource(R.drawable.baseline_repeat_one_24)
                repeatButton?.imageAlpha = 255
            }
            Player.REPEAT_MODE_ALL -> {
                repeatButton?.setImageResource(R.drawable.baseline_loop_24)
                repeatButton?.imageAlpha = 255
            }
            else -> {
                repeatButton?.setImageResource(R.drawable.baseline_loop_24)
                repeatButton?.imageAlpha = 100
            }
        }
    }

    private fun enableShuffleQueue() {
        if (queueItems.isEmpty()) return
        val wasPlaying = player?.playWhenReady == true
        val currentPos = player?.currentPosition ?: 0L
        val currentItemId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        val currentItem = queueItems.firstOrNull { it.id == currentItemId }
        val remaining = queueItems.filter { it.id != currentItemId }.shuffled()
        val nextList = if (currentItem != null) listOf(currentItem) + remaining else remaining
        applyQueueOrder(nextList, currentItemId, currentPos, wasPlaying, forceScrollCurrentToTop = true)
        isShuffled = true
        updateShuffleButton()
    }

    private fun reshuffleQueue() {
        if (queueItems.isEmpty()) return
        val wasPlaying = player?.playWhenReady == true
        val currentPos = player?.currentPosition ?: 0L
        val currentItemId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        val currentItem = queueItems.firstOrNull { it.id == currentItemId }
        val remaining = queueItems.filter { it.id != currentItemId }.shuffled()
        val nextList = if (currentItem != null) listOf(currentItem) + remaining else remaining
        applyQueueOrder(nextList, currentItemId, currentPos, wasPlaying, forceScrollCurrentToTop = true)
        isShuffled = true
        updateShuffleButton()
    }

    private fun disableShuffleQueue() {
        if (queueItems.isEmpty()) return
        val wasPlaying = player?.playWhenReady == true
        val currentPos = player?.currentPosition ?: 0L
        val currentItemId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        applyQueueOrder(baseQueueItems, currentItemId, currentPos, wasPlaying)
        isShuffled = false
        updateShuffleButton()
    }

    private fun updateShuffleButton() {
        shuffleButton?.imageAlpha = if (isShuffled) 255 else 120
    }

    private fun applyQueueOrder(
        items: List<HistoryItem>,
        currentItemId: Long?,
        currentPos: Long,
        playWhenReady: Boolean,
        preparedQueueData: QueuePreparedData? = null,
        forceScrollCurrentToTop: Boolean = false
    ) {
        logPlaybackTiming("applyQueueOrder start size=${items.size} currentId=$currentItemId pos=$currentPos playWhenReady=$playWhenReady")
        queueItems = items
        val queueData = preparedQueueData ?: prepareQueueData(
            items = items,
            previousPlayablePaths = queuePlayablePathById.toMap()
        )
        applyQueuePreparedData(queueData)
        val mediaItems = queueData.mediaItems
        val idx = if (currentItemId != null) queueIndexById[currentItemId] ?: -1 else -1
        queueAdapter?.submitList(items) {
            logPlaybackTiming("applyQueueOrder submitList committed size=${items.size} currentId=$currentItemId")
            if (idx >= 0 && idx < queueItems.size) {
                val selectedId = queueItems[idx].id
                queueAdapter?.setCurrentItemId(selectedId)
                if (forceScrollCurrentToTop) {
                    scrollCurrentToTopIfAllowed(selectedId, force = true)
                }
            } else {
                queueAdapter?.setCurrentItemId(null)
            }
        }
        if (mediaItems.isNotEmpty()) {
            val syncedInPlace = syncExistingTimelineIfPossible(
                items = items,
                currentItemId = currentItemId,
                currentPos = currentPos,
                playWhenReady = playWhenReady
            )
            logPlaybackTiming("applyQueueOrder timeline synced=$syncedInPlace mediaItems=${mediaItems.size}")
            if (syncedInPlace && forceScrollCurrentToTop && currentItemId != null) {
                queueList?.post {
                    updateCurrentQueueSelection(
                        scrollToCurrent = true,
                        resetRecentWatchTimer = false,
                        forceScrollToCurrentTop = true
                    )
                }
            }
            if (!syncedInPlace) {
                player?.shuffleModeEnabled = false
                if (currentItemId != null && currentPos > 0L) {
                    skipNextPlaybackRestoreHistoryId = currentItemId
                }
                player?.setMediaItems(mediaItems, if (idx >= 0) idx else 0, currentPos)
                player?.prepare()
                player?.playWhenReady = playWhenReady
            }
            updatePlaybackUiProgress()
        }
    }

    private fun syncExistingTimelineIfPossible(
        items: List<HistoryItem>,
        currentItemId: Long?,
        currentPos: Long,
        playWhenReady: Boolean
    ): Boolean {
        val exo = player ?: return false
        if (exo.mediaItemCount <= 0 || items.isEmpty()) return false
        logPlaybackTiming("syncExistingTimelineIfPossible start targetSize=${items.size} currentId=$currentItemId currentPos=$currentPos")

        val targetIds = items.map { it.id }
        if (targetIds.distinct().size != targetIds.size) return false
        val mediaItemsById = items.associate { item ->
            item.id to (buildQueueMediaItem(item) ?: return false)
        }
        val timelineIds = mutableListOf<Long>()
        for (index in 0 until exo.mediaItemCount) {
            val mediaId = exo.getMediaItemAt(index).mediaId.toLongOrNull() ?: return false
            timelineIds.add(mediaId)
        }
        if (timelineIds.distinct().size != timelineIds.size) return false
        val existingOrderInTarget = targetIds.filter { it in timelineIds }
        val canExpandWithoutReordering = existingOrderInTarget == timelineIds
        if (canExpandWithoutReordering) {
            targetIds.forEachIndexed { targetIndex, desiredId ->
                if (!timelineIds.contains(desiredId)) {
                    val mediaItem = mediaItemsById[desiredId] ?: return false
                    exo.addMediaItem(targetIndex, mediaItem)
                    timelineIds.add(targetIndex, desiredId)
                }
            }
            exo.shuffleModeEnabled = false
            if (currentItemId != null) {
                val targetIndex = targetIds.indexOf(currentItemId)
                val currentResolvedId = resolveHistoryIdForMediaItem(exo.currentMediaItem)
                if (targetIndex >= 0 &&
                    (currentResolvedId != currentItemId || exo.currentMediaItemIndex != targetIndex)
                ) {
                    logPlaybackTiming(
                        "syncExistingTimelineIfPossible expanded seek currentResolvedId=$currentResolvedId targetId=$currentItemId targetIndex=$targetIndex currentPos=$currentPos"
                    )
                    exo.seekTo(targetIndex, currentPos)
                }
            }
            exo.playWhenReady = playWhenReady
            logPlaybackTiming("syncExistingTimelineIfPossible expanded inPlace finalSize=${exo.mediaItemCount}")
            return true
        }
        if (timelineIds.any { it !in targetIds }) {
            for (index in timelineIds.indices.reversed()) {
                if (timelineIds[index] !in targetIds) {
                    exo.removeMediaItem(index)
                    timelineIds.removeAt(index)
                }
            }
        }

        val mutableIds = timelineIds.toMutableList()
        targetIds.forEachIndexed { targetIndex, desiredId ->
            val fromIndex = mutableIds.indexOf(desiredId)
            if (fromIndex == -1) {
                val mediaItem = mediaItemsById[desiredId] ?: return false
                exo.addMediaItem(targetIndex, mediaItem)
                mutableIds.add(targetIndex, desiredId)
            } else if (fromIndex != targetIndex) {
                exo.moveMediaItem(fromIndex, targetIndex)
                mutableIds.removeAt(fromIndex)
                mutableIds.add(targetIndex, desiredId)
            }
        }
        while (mutableIds.size > targetIds.size) {
            val lastIndex = mutableIds.lastIndex
            exo.removeMediaItem(lastIndex)
            mutableIds.removeAt(lastIndex)
        }

        exo.shuffleModeEnabled = false
        if (currentItemId != null) {
            val currentIndex = targetIds.indexOf(currentItemId)
            if (currentIndex >= 0) {
                val currentResolvedId = resolveHistoryIdForMediaItem(exo.currentMediaItem)
                if (currentResolvedId != currentItemId || exo.currentMediaItemIndex != currentIndex) {
                    exo.seekTo(currentIndex, currentPos)
                }
            }
        }
        exo.playWhenReady = playWhenReady
        logPlaybackTiming("syncExistingTimelineIfPossible reordered finalSize=${exo.mediaItemCount}")
        return true
    }

    private fun logPlaybackTiming(event: String) {
        val sinceStart = if (playbackStartedAtMs > 0L) {
            SystemClock.elapsedRealtime() - playbackStartedAtMs
        } else {
            -1L
        }
        Log.d(
            PLAYBACK_TIMING_TAG,
            "t=${sinceStart}ms event=$event playing=${player?.isPlaying == true} state=${player?.playbackState ?: -1}"
        )
    }

    private fun logPlaybackPosition(event: String) {
        Log.d(
            PLAYBACK_POSITION_TAG,
            "event=$event mediaId=${player?.currentMediaItem?.mediaId ?: ""} current=${player?.currentPosition ?: -1L} state=${player?.playbackState ?: -1}"
        )
    }

    private fun initTimeBarScrubbing() {
        val bar = progressBar ?: return
        bar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                player?.seekTo(position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                player?.seekTo(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) {
                    player?.seekTo(position)
                }
            }
        })
    }

    private fun updatePlaybackUiProgress() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L
        val position = exo.currentPosition.coerceAtLeast(0L)
        val bufferedPosition = exo.bufferedPosition.coerceAtLeast(position)
        progressBar?.setDuration(duration)
        progressBar?.setPosition(position)
        progressBar?.setBufferedPosition(bufferedPosition)
    }

    private fun getResumePositionForIndex(items: List<HistoryItem>, index: Int): Long {
        if (index < 0 || index >= items.size) return 0L
        val saved = items[index].playbackPositionMs
        return if (saved >= 5_000L) saved else 0L
    }

    private fun savePlaybackPositionForCurrentItem() {
        val historyId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        val position = player?.currentPosition ?: 0L
        logPlaybackPosition("savePlaybackPositionForCurrentItem historyId=$historyId position=$position")
        savePlaybackPositionForHistoryIdWithDurationCheck(historyId, position, useDurationCheck = true)
    }

    private fun restorePlaybackPositionForCurrentItem() {
        val historyId = resolveHistoryIdForMediaItem(player?.currentMediaItem) ?: return
        if (skipNextPlaybackRestoreHistoryId == historyId) {
            logPlaybackPosition("restorePlaybackPositionForCurrentItem skipped historyId=$historyId reason=skipNext")
            skipNextPlaybackRestoreHistoryId = null
            return
        }
        logPlaybackPosition("restorePlaybackPositionForCurrentItem historyId=$historyId")
        seekToSavedPlaybackPosition(historyId)
    }

    private fun seekToSavedPlaybackPosition(historyId: Long?) {
        val resolvedHistoryId = historyId ?: return
        val saved = playbackPositionsById[resolvedHistoryId] ?: return
        if (saved < 5_000L) {
            logPlaybackPosition("seekToSavedPlaybackPosition skipped historyId=$resolvedHistoryId reason=tooSmall saved=$saved")
            return
        }
        val duration = player?.duration ?: C.TIME_UNSET
        if (duration > 0 && saved >= duration - 5_000L) {
            logPlaybackPosition("seekToSavedPlaybackPosition skipped historyId=$resolvedHistoryId reason=nearEnd saved=$saved duration=$duration")
            return
        }
        val currentPosition = player?.currentPosition ?: 0L
        if (currentPosition >= saved + 2_000L) {
            logPlaybackPosition("seekToSavedPlaybackPosition skipped historyId=$resolvedHistoryId reason=currentAhead saved=$saved current=$currentPosition")
            return
        }
        logPlaybackPosition("seekToSavedPlaybackPosition apply historyId=$resolvedHistoryId saved=$saved current=$currentPosition duration=$duration")
        player?.seekTo(saved)
    }

    private fun savePlaybackPositionForHistoryIdWithDurationCheck(historyId: Long?, positionMs: Long, useDurationCheck: Boolean) {
        val resolvedHistoryId = historyId ?: return
        val duration = if (useDurationCheck) player?.duration ?: C.TIME_UNSET else C.TIME_UNSET
        val safePosition = if (duration > 0 && positionMs >= duration - 5_000L) 0L else positionMs
        logPlaybackPosition(
            "savePlaybackPositionForHistoryIdWithDurationCheck historyId=$resolvedHistoryId input=$positionMs safe=$safePosition duration=$duration useDurationCheck=$useDurationCheck"
        )
        savePlaybackPositionForHistoryId(resolvedHistoryId, safePosition)
    }

    private fun cachePlaybackPosition(historyId: Long, positionMs: Long) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putLong("$PREF_PLAYBACK_POSITION_CACHE_PREFIX$historyId", positionMs)
            .apply()
    }

    private fun getCachedPlaybackPosition(historyId: Long): Long? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val key = "$PREF_PLAYBACK_POSITION_CACHE_PREFIX$historyId"
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    }

    private fun resolveHistoryIdForMediaItem(mediaItem: MediaItem?): Long? {
        val directId = mediaItem?.mediaId?.toLongOrNull()
        if (directId != null) return directId
        val currentUri = mediaItem?.localConfiguration?.uri?.toString() ?: return null
        return queueIdByUri[currentUri] ?: queueItems.firstOrNull { item ->
            item.downloadPath.any { path -> uriFromPath(path).toString() == currentUri }
        }?.id
    }

    private fun rebuildQueueLookups(items: List<HistoryItem>) {
        val previousPlayablePaths = queuePlayablePathById.toMap()
        queuePlayablePathById.clear()
        queueMediaUriById.clear()
        queueIdByUri.clear()
        items.forEach { item ->
            val playablePath = previousPlayablePaths[item.id]
                ?: item.downloadPath.firstOrNull { it.isNotBlank() }
                ?: item.downloadPath.firstOrNull()
            if (playablePath != null) {
                val mediaUri = uriFromPath(playablePath)
                queuePlayablePathById[item.id] = playablePath
                queueMediaUriById[item.id] = mediaUri
                queueIdByUri[mediaUri.toString()] = item.id
            }
        }
    }

    private fun rebuildQueueIndexes(items: List<HistoryItem>) {
        queueIndexById.clear()
        items.forEachIndexed { index, item ->
            queueIndexById[item.id] = index
        }
    }

    private fun findPlayerMediaItemIndex(historyId: Long): Int {
        val exo = player ?: return C.INDEX_UNSET
        for (index in 0 until exo.mediaItemCount) {
            if (resolveHistoryIdForMediaItem(exo.getMediaItemAt(index)) == historyId) {
                return index
            }
        }
        return C.INDEX_UNSET
    }

    private fun prepareQueueData(
        items: List<HistoryItem>,
        preferredPlayablePaths: Map<Long, String> = emptyMap(),
        previousPlayablePaths: Map<Long, String> = emptyMap()
    ): QueuePreparedData {
        val playablePathById = LinkedHashMap<Long, String>(items.size)
        val mediaUriById = LinkedHashMap<Long, Uri>(items.size)
        val idByUri = LinkedHashMap<String, Long>(items.size)
        val indexById = LinkedHashMap<Long, Int>(items.size)
        val playbackPositions = LinkedHashMap<Long, Long>(items.size)
        val mediaItems = ArrayList<MediaItem>(items.size)

        items.forEachIndexed { index, item ->
            indexById[item.id] = index
            playbackPositions[item.id] = item.playbackPositionMs

            val playablePath = preferredPlayablePaths[item.id]
                ?: previousPlayablePaths[item.id]
                ?: item.downloadPath.firstOrNull { it.isNotBlank() }
                ?: item.downloadPath.firstOrNull()
                ?: return@forEachIndexed

            val mediaUri = uriFromPath(playablePath)
            playablePathById[item.id] = playablePath
            mediaUriById[item.id] = mediaUri
            idByUri[mediaUri.toString()] = item.id
            mediaItems += MediaItem.Builder()
                .setUri(mediaUri)
                .setMediaId(item.id.toString())
                .build()
        }

        return QueuePreparedData(
            playablePathById = playablePathById,
            mediaUriById = mediaUriById,
            idByUri = idByUri,
            indexById = indexById,
            playbackPositionsById = playbackPositions,
            mediaItems = mediaItems
        )
    }

    private fun applyQueuePreparedData(queueData: QueuePreparedData) {
        queuePlayablePathById.clear()
        queuePlayablePathById.putAll(queueData.playablePathById)
        queueMediaUriById.clear()
        queueMediaUriById.putAll(queueData.mediaUriById)
        queueIdByUri.clear()
        queueIdByUri.putAll(queueData.idByUri)
        queueIndexById.clear()
        queueIndexById.putAll(queueData.indexById)
        playbackPositionsById.clear()
        playbackPositionsById.putAll(queueData.playbackPositionsById)
    }

    private fun buildQueueMediaItem(item: HistoryItem): MediaItem? {
        val mediaUri = queueMediaUriById[item.id] ?: return null
        return MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(item.id.toString())
            .build()
    }

    private fun savePlaybackPositionForHistoryId(historyId: Long, positionMs: Long) {
        playbackPositionsById[historyId] = positionMs
        cachePlaybackPosition(historyId, positionMs)
        lifecycleScope.launch(Dispatchers.IO) {
            DBManager.getInstance(this@VideoPlayerActivity).historyDao.updatePlaybackPosition(historyId, positionMs)
        }
    }

    private fun resetRecentWatch(historyId: Long) {
        if (recentWatchHistoryId != historyId) {
            commitRecentWatchIfEligible()
        }
        recentWatchHistoryId = historyId
        recentWatchStartMs = SystemClock.elapsedRealtime()
        recentWatchUpdated = false
    }

    private fun ensureRecentWatchTarget() {
        val currentId = resolveHistoryIdForMediaItem(player?.currentMediaItem) ?: return
        if (recentWatchHistoryId != currentId) {
            resetRecentWatch(currentId)
        }
    }

    private fun commitRecentWatchIfEligible() {
        val historyId = recentWatchHistoryId ?: return
        if (historyId <= 0L || recentWatchUpdated) return
        val elapsed = SystemClock.elapsedRealtime() - recentWatchStartMs
        if (elapsed < 10_000L) return
        recentWatchUpdated = true
        val now = System.currentTimeMillis() / 1000L
        lifecycleScope.launch(Dispatchers.IO) {
            DBManager.getInstance(this@VideoPlayerActivity).historyDao.updateLastWatched(historyId, now)
        }
    }

    private fun startRecentWatchTimer() {
        ensureRecentWatchTarget()
        if (recentWatchRunnable == null) {
            recentWatchRunnable = Runnable {
                commitRecentWatchIfEligible()
                if (player?.isPlaying == true) {
                    recentWatchHandler.postDelayed(recentWatchRunnable!!, 1_000L)
                }
            }
        }
        recentWatchHandler.removeCallbacks(recentWatchRunnable!!)
        recentWatchHandler.postDelayed(recentWatchRunnable!!, 1_000L)
    }

    private fun stopRecentWatchTimer() {
        commitRecentWatchIfEligible()
        recentWatchRunnable?.let { recentWatchHandler.removeCallbacks(it) }
    }

    private fun getDurationMsForHistoryId(historyId: Long): Long {
        val duration = queueItems.firstOrNull { it.id == historyId }?.duration ?: return 0L
        return parseDurationToMs(duration)
    }

    private fun parseDurationToMs(duration: String): Long {
        if (duration.isBlank()) return 0L
        val parts = duration.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            1 -> parts[0] * 1000L
            else -> 0L
        }
    }

    override fun onStart() {
        super.onStart()
        activeInstance = WeakReference(this)
    }

    companion object {
        const val EXTRA_RETURN_DESTINATION = "player_return_destination"
        private const val ENABLE_HISTORY_RETURN_LOGS = false
        private const val HISTORY_RETURN_TAG = "HistoryReturn"
        private const val PLAYBACK_TIMING_TAG = "PlaybackTiming"
        private const val PLAYBACK_POSITION_TAG = "PlaybackPosition"
        private const val ACTION_RESUME_PLAYBACK_UI = "ytdlnisx.action.RESUME_PLAYBACK_UI"
        private const val ACTION_PIP_PLAY_PAUSE = "ytdlnisx.action.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_BACKGROUND = "ytdlnisx.action.PIP_BACKGROUND"
        private const val ACTION_PIP_NEXT = "ytdlnisx.action.PIP_NEXT"
        private const val ACTION_PLAYBACK_CLOSE = "ytdlnisx.action.PLAYBACK_CLOSE"
        private const val PREF_SUBTITLE_TEXT_SIZE = "subtitle_text_size_fraction"
        private const val PREF_SUBTITLE_FOREGROUND = "subtitle_foreground_color"
        private const val PREF_SUBTITLE_BACKGROUND = "subtitle_background_color"
        private const val PREF_SUBTITLE_WINDOW = "subtitle_window_color"
        private const val PREF_SUBTITLE_EDGE_TYPE = "subtitle_edge_type"
        private const val PREF_SUBTITLE_EDGE_COLOR = "subtitle_edge_color"
        private const val PREF_SUBTITLE_EMBEDDED_STYLES = "subtitle_embedded_styles"
        private const val PREF_SUBTITLE_EMBEDDED_FONT_SIZES = "subtitle_embedded_font_sizes"
        private const val PREF_VOLUME_NORMALIZATION = "player_volume_normalization"
        private const val PREF_REPEAT_MODE = "player_repeat_mode"
        private const val PREF_PLAYBACK_POSITION_CACHE_PREFIX = "player_playback_position_"
        private const val LOUDNESS_TARGET_GAIN_MB = 500
        private const val PREF_HOLD_PLAYBACK_SPEED = "hold_playback_speed"
        private const val PREF_SPEED_PRESET_1 = "speed_preset_1"
        private const val PREF_SPEED_PRESET_2 = "speed_preset_2"
        private const val PREF_SPEED_PRESET_3 = "speed_preset_3"
        private const val PREF_SPEED_PRESET_4 = "speed_preset_4"
        private const val PREF_SPEED_PRESET_5 = "speed_preset_5"
        private var activeInstance: WeakReference<VideoPlayerActivity>? = null

        fun handlePipAction(action: String?) {
            activeInstance?.get()?.handlePipAction(action)
        }
    }

    private data class VideoChapter(
        val title: String,
        val startMs: Long,
        val endMs: Long?
    )

    private data class QueuePreparedData(
        val playablePathById: Map<Long, String>,
        val mediaUriById: Map<Long, Uri>,
        val idByUri: Map<String, Long>,
        val indexById: Map<Long, Int>,
        val playbackPositionsById: Map<Long, Long>,
        val mediaItems: List<MediaItem>
    )

    private enum class SwipeTouchZone {
        NONE,
        BRIGHTNESS,
        SEEK,
        VOLUME
    }

    private enum class SwipeGestureType {
        NONE,
        BRIGHTNESS,
        SEEK,
        VOLUME
    }

}
