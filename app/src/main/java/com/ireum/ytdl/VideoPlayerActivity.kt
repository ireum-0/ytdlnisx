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
import android.graphics.drawable.Icon
import android.graphics.Color
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
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.ui.adapter.VideoQueueAdapter
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
import androidx.activity.result.contract.ActivityResultContracts
import java.io.FileOutputStream
import java.io.OutputStream
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
    private var repeatButton: ImageButton? = null
    private var shuffleButton: ImageButton? = null
    private var playerContainer: android.view.View? = null
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
    private var currentChapters: List<VideoChapter> = emptyList()

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

        val playerView = findViewById<PlayerView>(R.id.player_view)
        this.playerView = playerView
        val videoPath = intent.getStringExtra("video_path")
        launchHistoryId = intent.getLongExtra("history_id", -1L).takeIf { it > 0L }
        launchPlaybackPositionMs = intent.getLongExtra("playback_position_ms", 0L).takeIf { it >= 5_000L }

        if (videoPath.isNullOrBlank()) {
            Log.w("VideoPlayerActivity", "videoPath is null or blank")
            return
        }

        Log.d("VideoPlayerActivity", "videoPath=$videoPath")

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
        if (queueList != null) {
            queueAdapter = VideoQueueAdapter(this) { item ->
                val path = queuePlayablePathById[item.id]
                    ?: item.downloadPath.firstOrNull { FileUtil.exists(it) }
                    ?: item.downloadPath.firstOrNull()
                if (path != null) {
                    val index = queueIndexById[item.id] ?: queueItems.indexOfFirst { it.id == item.id }
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

        val uri = when {
            videoPath.startsWith("content://") -> Uri.parse(videoPath)
            videoPath.startsWith("file://") -> Uri.parse(videoPath)
            else -> {
                val file = File(videoPath)
                if (!file.exists() || !file.isFile) {
                    Toast.makeText(this, "Invalid video path: $videoPath", Toast.LENGTH_SHORT).show()
                    return
                }
                Uri.fromFile(file)
            }
        }

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            exoPlayer.setAudioAttributes(audioAttributes, true)
            exoPlayer.setHandleAudioBecomingNoisy(true)
        }
        setupMediaNotification()
        volumeNormalizationEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_VOLUME_NORMALIZATION, false)
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

        updateTitleFromPath(videoPath, preferredHistoryId = launchHistoryId)
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

        val playlistPaths = intent.getStringArrayListExtra("video_paths")

        if (!playlistPaths.isNullOrEmpty()) {
            loadQueueFromPaths(playlistPaths, videoPath)
        } else {
            loadQueueForContext(videoPath)
        }

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
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionPlaybackState()
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
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updateMediaSessionMetadata()
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateSubtitlesButtonState()
                refreshChaptersForCurrentItem()
            }
        })
        updateSubtitlesButtonState()
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
            queueItems = items
            rebuildQueueLookups(items)
            rebuildQueueIndexes(items)
            isShuffled = false
            updateShuffleButton()
            player?.shuffleModeEnabled = false

            playbackPositionsById.clear()
            playbackPositionsById.putAll(items.associate { it.id to it.playbackPositionMs })

            // 2) 큐(RecyclerView) 표시
            val startUri = uriFromPath(startPath).toString()
            val startId = queueIdByUri[startUri] ?: items.firstOrNull()?.id
            val startIndex = if (startId != null) items.indexOfFirst { it.id == startId }.let { if (it >= 0) it else 0 } else 0
            seekToSavedPlaybackPosition(startId)

            queueAdapter?.submitList(items) {
                val currentId = items.getOrNull(startIndex)?.id
                queueAdapter?.setCurrentItemId(currentId)
                scrollCurrentToTopIfAllowed(currentId)
            }

            // 3) 플레이어에는 "이미 위에서 setMediaItems 해놨으면" 다시 setMediaItems하지 않는 게 가장 깔끔
            //    그런데 지금은 onCreate에서 이미 exoPlayer.setMediaItems(mediaItems) 해버리니까,
            //    여기서는 player 세팅을 건드리지 않는 편이 "로딩 두 번"을 확실히 막음.

            // ✅ 즉: 여기서 player?.setMediaItems(...) / prepare()를 하지 말 것
            //    큐 표시만 담당하게 두면 중복 로딩이 사라짐
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
            type = DownloadType.video,
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

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) {
            isBackgroundPlayback = false
            setMediaNotificationEnabled(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }
    }

    override fun onPause() {
        savePlaybackPositionForCurrentItem()
        commitRecentWatchIfEligible()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        savePlaybackPositionForCurrentItem()
        commitRecentWatchIfEligible()
        if (wasInPip && !isInPictureInPictureMode && !isBackgroundPlayback && !isEditVideoInfoDialogVisible) {
            player?.pause()
            finish()
        } else if (isFinishing) {
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
        mediaSession?.release()
        mediaSession = null
        playerNotificationManager = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        player?.release()
        player = null
        activeInstance = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfSupported()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
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
            if (wasInPip && !isBackgroundPlayback && !isEditVideoInfoDialogVisible) {
                player?.pause()
                finish()
                return
            }
            if (!isBackgroundPlayback) {
                setMediaNotificationEnabled(false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationUi()
    }

    private fun applyOrientationUi() {
        val isLandscape = isLandscapeMode()
        queueHeader?.visibility = if (isLandscape) android.view.View.GONE else android.view.View.VISIBLE
        queueList?.visibility = if (isLandscape) android.view.View.GONE else android.view.View.VISIBLE

        val root = findViewById<ConstraintLayout>(R.id.video_player_root)
        val container = playerContainer ?: return
        val bar = timeBar ?: return
        val bottomControls = playerView?.findViewById<android.view.View>(R.id.player_bottom_area)
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
            options.add(getString(R.string.add_to_playlist))
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
                        7 -> addCurrentToPlaylist()
                        8 -> editCurrentVideoInfo()
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

    private fun enterPipIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playerView?.controllerAutoShow = false
            playerView?.hideController()
            val params = buildPipParams()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "PiP not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setActions(buildPipActions())
        }
        return builder.build()
    }

    private fun buildPipActions(): List<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        val isPlaying = player?.isPlaying == true
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
        return currentItem?.author.orEmpty()
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
        return currentItem?.thumb
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

    private fun addCurrentToPlaylist() {
        val item = currentHistoryItem()
        val historyId = item?.id ?: -1L
        if (historyId <= 0L) {
            Toast.makeText(this, getString(R.string.no_match_found), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val db = DBManager.getInstance(this@VideoPlayerActivity)
            val playlists = withContext(Dispatchers.IO) { db.playlistDao.getAllPlaylists().first() }
            val names = ArrayList<String>()
            names.add(getString(R.string.new_playlist))
            names.addAll(playlists.map { it.name })
            MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                .setTitle(getString(R.string.add_to_playlist))
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) {
                        showCreatePlaylistDialog(historyId)
                    } else {
                        val playlistId = playlists[which - 1].id
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlistId, historyId))
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@VideoPlayerActivity,
                                    getString(R.string.added_to_playlist),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                .show()
        }
    }

    private fun showCreatePlaylistDialog(historyId: Long) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.playlist_name)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_playlist))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = DBManager.getInstance(this@VideoPlayerActivity)
                    val playlistId = db.playlistDao.insertPlaylist(Playlist(name = name, description = null))
                    db.playlistDao.insertPlaylistItem(PlaylistItemCrossRef(playlistId, historyId))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@VideoPlayerActivity,
                            getString(R.string.added_to_playlist),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

    private fun currentPlaybackPendingIntent(): PendingIntent? {
        val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString() ?: return null
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("video_path", uri)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            NotificationUtil.PLAYBACK_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
                if (player?.isPlaying == true) {
                    playbackStateHandler.postDelayed(playbackStateUpdater!!, 1000L)
                }
            }
        }
        playbackStateHandler.removeCallbacks(playbackStateUpdater!!)
        playbackStateHandler.postDelayed(playbackStateUpdater!!, 1000L)
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
            setupMediaNotification()
            playerNotificationManager?.setPlayer(player)
            playerNotificationManager?.invalidate()
        } else {
            playerNotificationManager?.setPlayer(null)
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
            val url = currentThumbUrl()
            if (url.isNullOrBlank()) return null
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { Picasso.get().load(url).resize(512, 0).onlyScaleDown().get() }.getOrNull()
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
        setPictureInPictureParams(buildPipParams())
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
            ACTION_PIP_BACKGROUND -> {
                isBackgroundPlayback = true
                setMediaNotificationEnabled(true)
                moveTaskToBack(true)
            }
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
            requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
                updateTitleViews(item.title, item.author)
            } else {
                updateTitleViews(File(path).name, "")
            }
            playerNotificationManager?.invalidate()
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
    }

    private fun loadQueueForContext(videoPath: String) {
        if (queueList == null) return
        autoScrollQueueToCurrent = true
        val authorFilter = intent.getStringExtra("context_author").orEmpty()
        val playlistId = intent.getLongExtra("context_playlist_id", -1L)
        val playlistName = intent.getStringExtra("context_playlist_name").orEmpty()
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
        if (player?.mediaItemCount == 0) {
            val initialMediaId = launchHistoryId?.toString()
                ?: queueItems.firstOrNull { it.downloadPath.any { p -> uriFromPath(p) == initialUri } }
                    ?.id
                    ?.toString()
                ?: ""
            val initialItem = MediaItem.Builder()
                .setUri(initialUri)
                .setMediaId(initialMediaId)
                .build()
            val startPosition = launchPlaybackPositionMs ?: 0L
            player?.setMediaItem(initialItem, startPosition)
            player?.prepare()
            player?.playWhenReady = true
        }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                when {
                    playlistId > 0L -> db.playlistDao.getPlaylistWithHistoryItems(playlistId).first()
                    authorFilter.isNotBlank() -> db.historyDao.getVideosByAuthor(authorFilter)
                    else -> db.historyDao.getAllVideos()
                }.asSequence()
                    .filter { it.type == DownloadType.video }
                    .map { resolveLocalTreePath(db, it) }
                    .mapNotNull { item ->
                        val playablePath = item.downloadPath.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                        item to playablePath
                    }
                    .toList()
            }
            val fullItems = sortQueueItems(items.map { it.first }, sortType, sortOrder)
            baseQueueItems = fullItems
            if (fullItems.isEmpty()) return@launch
            queuePlayablePathById.clear()
            queueMediaUriById.clear()
            queueIdByUri.clear()
            items.forEach { (item, playablePath) ->
                val mediaUri = uriFromPath(playablePath)
                queuePlayablePathById[item.id] = playablePath
                queueMediaUriById[item.id] = mediaUri
                queueIdByUri[mediaUri.toString()] = item.id
            }
            val chunkSize = 50
            val currentId = queueIdByUri[initialUri.toString()] ?: fullItems.firstOrNull()?.id
            val currentIndex = if (currentId != null) fullItems.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else 0 } else 0
            queueItems = fullItems
            rebuildQueueIndexes(fullItems)
            isShuffled = false
            updateShuffleButton()
            player?.shuffleModeEnabled = false
            playbackPositionsById.clear()
            playbackPositionsById.putAll(fullItems.associate { it.id to it.playbackPositionMs })
            queueAdapter?.submitList(queueItems) {
                if (currentId != null) {
                    queueAdapter?.setCurrentItemId(currentId)
                } else {
                    queueAdapter?.setCurrentItemId(null)
                }
                scrollCurrentToTopIfAllowed(currentId)
            }
            // Build queue progressively for UI + player.
            lifecycleScope.launch(Dispatchers.Main) {
                val preloadedIds = mutableSetOf<Long>()

                // Prioritize neighbors around the current item for faster next/previous transition.
                val nextItem = fullItems.getOrNull(currentIndex + 1)
                if (nextItem != null) {
                    buildQueueMediaItem(nextItem)?.let { mediaItem ->
                        player?.addMediaItem(mediaItem)
                        preloadedIds.add(nextItem.id)
                    }
                }
                val prevItem = fullItems.getOrNull(currentIndex - 1)
                if (prevItem != null) {
                    buildQueueMediaItem(prevItem)?.let { mediaItem ->
                        player?.addMediaItem(0, mediaItem)
                        preloadedIds.add(prevItem.id)
                    }
                }

                // Append items after current to player in chunks.
                val afterItems = fullItems.subList((currentIndex + 1).coerceAtMost(fullItems.size), fullItems.size)
                afterItems.chunked(chunkSize).forEach { chunk ->
                    val mediaItems = chunk.mapNotNull { item ->
                        if (preloadedIds.contains(item.id)) return@mapNotNull null
                        buildQueueMediaItem(item)
                    }
                    if (mediaItems.isNotEmpty()) {
                        player?.addMediaItems(mediaItems)
                    }
                    kotlinx.coroutines.delay(16)
                }
                // Prepend items before current in reverse chunks to preserve order.
                val beforeItems = fullItems.subList(0, currentIndex)
                beforeItems.chunked(chunkSize).asReversed().forEach { chunk ->
                    val mediaItems = chunk.mapNotNull { item ->
                        if (preloadedIds.contains(item.id)) return@mapNotNull null
                        buildQueueMediaItem(item)
                    }
                    if (mediaItems.isNotEmpty()) {
                        player?.addMediaItems(0, mediaItems)
                    }
                    kotlinx.coroutines.delay(16)
                }
            }
        }
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

    private fun playSinglePath(path: String) {
        val historyId = queueItems.firstOrNull { it.downloadPath.contains(path) }?.id
        val mediaItem = MediaItem.Builder()
            .setUri(uriFromPath(path))
            .setMediaId(historyId?.toString() ?: "")
            .build()
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
        updateTitleFromPath(path)
    }

    private fun uriFromPath(path: String): Uri {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            return Uri.parse(path)
        }
        val docUri = buildDocumentUriForPath(path)
        return docUri ?: Uri.fromFile(File(path))
    }

    private fun buildDocumentUriForPath(path: String): Uri? {
        if (!path.startsWith("/storage/")) return null
        val relative = path.removePrefix("/storage/")
        val splitIndex = relative.indexOf('/')
        if (splitIndex <= 0 || splitIndex >= relative.length - 1) return null
        val volumeId = relative.substring(0, splitIndex)
        val relPath = relative.substring(splitIndex + 1)
        val docId = "$volumeId:$relPath"
        val permissions = contentResolver.persistedUriPermissions
        for (perm in permissions) {
            if (!perm.isReadPermission) continue
            val treeUri = perm.uri ?: continue
            if (!DocumentsContract.isTreeUri(treeUri)) continue
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            if (!treeDocId.startsWith("$volumeId:")) continue
            val treePath = treeDocId.substringAfter(':')
            if (treePath.isNotEmpty() && !relPath.startsWith(treePath)) continue
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }
        return null
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

        var gestureActionTriggered = false
        if (touchSlop == 0) {
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        }
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
                    touchStartY = event.y
                    touchStartX = event.x
                    initialVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    initialVolumePercent = if (maxVolume == 0) 0 else (initialVolume * 100 / maxVolume)
                    val currBrightness = window.attributes.screenBrightness
                    initialBrightness = if (currBrightness < 0f) 0.5f else currBrightness
                    adjusting = false
                    seeking = false
                    gestureActionTriggered = false
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
                    val width = playerView.width.coerceAtLeast(1)
                    val leftThird = width / 3f
                    val rightThird = width * 2f / 3f
                    val isLeftZone = touchStartX < leftThird
                    val isRightZone = touchStartX > rightThird
                    val isCenterZone = !isLeftZone && !isRightZone
                    if (!gestureActionTriggered && isCenterZone && kotlin.math.abs(deltaY) > 80f
                        && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)
                    ) {
                        gestureActionTriggered = true
                        if (deltaY > 0f) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            if (isLandscapeMode()) {
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                enterPipIfSupported()
                            }
                        }
                        return@setOnTouchListener true
                    }
                    if (!adjusting && !seeking && kotlin.math.abs(deltaX) > 40f && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
                        seeking = true
                    }
                    if (seeking) {
                        hideControlsForGesture(playerView)
                        val width = playerView.width.coerceAtLeast(1)
                        val percent = (deltaX / width).coerceIn(-1f, 1f)
                        val seekBy = (percent * 80_000L).toLong()
                        val target = (initialSeekPosition + seekBy).coerceAtLeast(0L)
                        player?.seekTo(target)
                        showSeekOverlay(target, seekBy)
                        return@setOnTouchListener true
                    }
                    if (!adjusting && kotlin.math.abs(deltaY) < 40f) {
                        return@setOnTouchListener true
                    }
                    if (isCenterZone) {
                        return@setOnTouchListener true
                    }
                    adjusting = true
                    val height = playerView.height.coerceAtLeast(1)
                    val percent = (deltaY / height).coerceIn(-1f, 1f) * 2f
                    hideControlsForGesture(playerView)
                    if (isLeftZone) {
                        val newBrightness = (initialBrightness + percent).coerceIn(0.0f, 1.0f)
                        val attrs = window.attributes
                        attrs.screenBrightness = newBrightness
                        window.attributes = attrs
                        val brightnessPct = (newBrightness * 100).toInt()
                        showBrightnessOverlay(brightnessPct)
                    } else if (isRightZone) {
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
                    if (!adjusting) {
                        playerView.performClick()
                    }
                    adjusting = false
                    seeking = false
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
        applyQueueOrder(nextList, currentItemId, currentPos, wasPlaying)
        isShuffled = true
        player?.shuffleModeEnabled = true
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
        applyQueueOrder(nextList, currentItemId, currentPos, wasPlaying)
        isShuffled = true
        player?.shuffleModeEnabled = true
        updateShuffleButton()
    }

    private fun disableShuffleQueue() {
        if (queueItems.isEmpty()) return
        val wasPlaying = player?.playWhenReady == true
        val currentPos = player?.currentPosition ?: 0L
        val currentItemId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        applyQueueOrder(baseQueueItems, currentItemId, currentPos, wasPlaying)
        isShuffled = false
        player?.shuffleModeEnabled = false
        updateShuffleButton()
    }

    private fun updateShuffleButton() {
        shuffleButton?.imageAlpha = if (isShuffled) 255 else 120
    }

    private fun applyQueueOrder(items: List<HistoryItem>, currentItemId: Long?, currentPos: Long, playWhenReady: Boolean) {
        queueItems = items
        rebuildQueueLookups(items)
        rebuildQueueIndexes(items)
        playbackPositionsById.clear()
        playbackPositionsById.putAll(items.associate { it.id to it.playbackPositionMs })
        val mediaItems = items.mapNotNull { item -> buildQueueMediaItem(item) }
        val idx = if (currentItemId != null) queueIndexById[currentItemId] ?: -1 else -1
        queueAdapter?.submitList(items) {
            if (idx >= 0 && idx < queueItems.size) {
                queueAdapter?.setCurrentItemId(queueItems[idx].id)
            } else {
                queueAdapter?.setCurrentItemId(null)
            }
        }
        if (mediaItems.isNotEmpty()) {
            player?.setMediaItems(mediaItems, if (idx >= 0) idx else 0, currentPos)
            player?.prepare()
            player?.playWhenReady = playWhenReady
        }
    }

    private fun initTimeBarScrubbing() {
        val bar = timeBar?.findViewById<DefaultTimeBar>(R.id.exo_progress) ?: return
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

    private fun getResumePositionForIndex(items: List<HistoryItem>, index: Int): Long {
        if (index < 0 || index >= items.size) return 0L
        val saved = items[index].playbackPositionMs
        return if (saved >= 5_000L) saved else 0L
    }

    private fun savePlaybackPositionForCurrentItem() {
        val historyId = resolveHistoryIdForMediaItem(player?.currentMediaItem)
        val position = player?.currentPosition ?: 0L
        savePlaybackPositionForHistoryIdWithDurationCheck(historyId, position, useDurationCheck = true)
    }

    private fun restorePlaybackPositionForCurrentItem() {
        val historyId = resolveHistoryIdForMediaItem(player?.currentMediaItem) ?: return
        seekToSavedPlaybackPosition(historyId)
    }

    private fun seekToSavedPlaybackPosition(historyId: Long?) {
        val resolvedHistoryId = historyId ?: return
        val saved = playbackPositionsById[resolvedHistoryId] ?: return
        if (saved < 5_000L) return
        val duration = player?.duration ?: C.TIME_UNSET
        if (duration > 0 && saved >= duration - 5_000L) return
        player?.seekTo(saved)
    }

    private fun savePlaybackPositionForHistoryIdWithDurationCheck(historyId: Long?, positionMs: Long, useDurationCheck: Boolean) {
        val resolvedHistoryId = historyId ?: return
        val duration = if (useDurationCheck) player?.duration ?: C.TIME_UNSET else C.TIME_UNSET
        val safePosition = if (duration > 0 && positionMs >= duration - 5_000L) 0L else positionMs
        savePlaybackPositionForHistoryId(resolvedHistoryId, safePosition)
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

    private fun buildQueueMediaItem(item: HistoryItem): MediaItem? {
        val mediaUri = queueMediaUriById[item.id] ?: return null
        return MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(item.id.toString())
            .build()
    }

    private fun savePlaybackPositionForHistoryId(historyId: Long, positionMs: Long) {
        playbackPositionsById[historyId] = positionMs
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
        private const val ACTION_PIP_PLAY_PAUSE = "ytdlnisx.action.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_BACKGROUND = "ytdlnisx.action.PIP_BACKGROUND"
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

}
