# APIs, Runtimes, and Major Dependencies

## Media extraction and download

### yt-dlp / youtubedl-android

`io.github.junkfood02.youtubedl-android:library` is the primary generic
extractor/runtime integration. `YTDLPUtil` builds requests, parses JSON
metadata, expands supported configuration, and obtains cached info JSON.
`YoutubeDLCompat` and `YtdlpArgumentPolicy` sanitize options before execution.
The runtime can be updated from the app.

### aria2c

The youtubedl-android aria2c artifact is included and can be enabled for
downloads. Its usefulness depends on the URL, extractor, and chosen options.

### ffmpeg, ffprobe, and subtitle conversion

`App` installs and probes bundled runtime material used for media merging,
post-processing, diagnostics, subtitle conversion, and hard-sub flows. These
assets and native libraries are release-critical. ABI compatibility must be
proved on devices; it cannot be inferred from APK creation.

### NewPipe Extractor

NewPipe Extractor is an optional YouTube metadata/format source and includes a
WebView-backed PoToken generator path. The application falls back to yt-dlp
when the configured NewPipe path cannot return usable data.

## External network services

| Service | Purpose | Configuration / caveat |
|---|---|---|
| YouTube and other media sites | Metadata, streams, downloads | Requests may use cookies, player-client settings, PoTokens, and site-specific extractor behavior. |
| YouTube Data API v3 | Search, trending, and channel lookup | Optional user-supplied API key; quota/backoff handling is implemented. |
| Google suggestion endpoint | YouTube search suggestions | Used only when suggestions are enabled; queries leave the device. |
| GitHub Releases API | Application release/update checks | Points to `ireum-0/ytdlnisx`. |
| SponsorBlock-compatible endpoint | Sponsor segment removal | Optional and configurable; availability and trust depend on the selected endpoint. |
| Web pages opened for cookies | Cookie acquisition | JavaScript-enabled WebView; users choose the site and cookies can be persisted. |
| YouTube WebView endpoints | PoToken/player data generation | Advanced feature tied to YouTube behavior and subject to breakage. |

No telemetry or analytics SDK is configured in the current Gradle dependency
list. Normal media/extractor requests still disclose URLs and normal protocol
metadata to the selected site and runtime.

## Android platform integrations

- Room 2.8.4 for persistent data and migrations.
- WorkManager 2.11.0 for durable background/foreground work.
- Paging 3 for History.
- Media3 1.9.0 for ExoPlayer, HLS, DASH, RTSP, and playback UI.
- MediaStore, Storage Access Framework, DocumentFile/storage helpers, and
  FileProvider for media access.
- Notification channels, foreground services, PiP, media buttons, and
  AlarmManager.
- WebView/CookieManager and Accompanist WebView for cookies and PoToken flows.

## UI and utility dependencies

Material Components, AndroidX Navigation/Preference/Lifecycle/RecyclerView,
Picasso, RecyclerView SwipeDecorator, FastScroll, Markwon, EventBus, Gson,
Kotlin serialization, OkHttp, coroutines, and selected legacy support
libraries are used across the application.

## Delivery integrations

GitHub Actions compile and test pull requests and `main`. The main workflow can
create signed release APKs from repository secrets. Telegram notification
actions publish build/release notices; these notifications are operational
conveniences and do not determine build correctness.
