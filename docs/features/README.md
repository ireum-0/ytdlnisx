# Currently Implemented Features

This catalog describes features present in the current source. “Implemented”
means a working code path and UI or internal consumer exist; it does not imply
complete device, extractor, or ABI coverage.

## User-facing features

### Discovery and metadata

Status: implemented, extractor-dependent.

- Accepts URLs, yt-dlp search expressions, and ordinary search queries from the
  Home screen.
- Accepts Android share intents, including a quick-download share entry point.
- Supports single videos, playlists, channels, and multi-result selection.
- Stores local search history and can query Google/YouTube suggestions.
- Can show YouTube recommendations or trending results when configured.
- Fetches metadata through NewPipe, yt-dlp, or the optional YouTube Data API.
- Stores upload/release dates when an extractor exposes them and can backfill
  missing dates for linked History items.

Main code: `HomeFragment`, `ResultViewModel`, `ResultRepository`,
`YoutubeApiUtil`, `NewPipeUtil`, and `YTDLPUtil`.

Limitations: metadata availability and semantics vary by extractor and website.
The source-date backfill is explicit, sequential, network-dependent, and leaves
undated items unchanged when no reliable date is returned.

### Download configuration

Status: implemented; advanced options are site- and runtime-dependent.

- Video, audio, and command downloads.
- Format, quality, codec, container, bitrate, and file-size preferences.
- Audio language, subtitle language, manual/automatic subtitles, embedding,
  sidecar subtitle writing, and subtitle-format selection.
- Thumbnail writing/embedding, metadata embedding, chapter handling, media
  cutting, SponsorBlock options, filename templates, and download archives.
- Cookies, SOCKS proxy, IPv4 forcing, rate limits, fragment concurrency,
  retries, aria2c, and custom command templates.
- Incognito downloads that avoid normal History/log persistence.
- Quick Download and reusable typed download presets. Presets intentionally do
  not store URLs, paths, cookies, commands, or other source-specific data.

Main code: download-card fragments/dialogs, `DownloadViewModel`,
`DownloadPreset`, `YTDLPUtil`, `YoutubeDLCompat`, and `YtdlpArgumentPolicy`.

Limitations: not every option combination is valid for every extractor,
container, or Android device. User-provided command text is sanitized, but
Terminal and advanced command features remain expert functionality.

### Queue, scheduling, observation, and retry

Status: implemented.

- Processing, queued, active, scheduled, saved, canceled, duplicate, and error
  states.
- Queue reordering, bounded concurrent downloads, pause/resume/cancel actions,
  and notification controls.
- Time-window scheduling through WorkManager, with an optional exact-alarm
  path.
- Observe Sources records that check supported sources and queue new entries.
- Structured download outcomes and issue classification for selected failure
  types.
- User-initiated bounded retry with stable operation IDs, attempt numbers, and
  retry strategies.
- Membership-access detection and a waiting/decision flow for supported cases.

Main code: `DownloadViewModel`, queue fragments/adapters, `DownloadWorker`,
`AlarmScheduler`, `ObserveSourceWorker`, `DownloadOutcome`,
`DownloadIssueClassifier`, and `DownloadRetryPolicy`.

Limitations: background execution remains subject to Android WorkManager,
foreground-service, alarm, battery, and OEM restrictions. Pause/cancel behavior
depends on cooperative termination of native tools.

### History and local library

Status: implemented, with a large and evolving feature surface.

- Paged History with title, keyword, and creator search.
- Filtering by type, status, author, website, keywords, and collection context.
- Sorting by download date, upload/release date, title, creator, or duration.
- Configurable placement of items without a source date.
- Multi-selection and bulk actions.
- Keyword, keyword-group, creator/youtuber, creator-group, and playlist data
  models and navigation.
- Automatic keyword rules with synchronization and materialized assignments.
- Local folder/file import, metadata matching, custom thumbnails, duplicate
  handling, and missing/inaccessible file state support.
- Open, share, copy-location, redownload, remove, and optional underlying-file
  deletion actions using raw paths, MediaStore, SAF, or FileProvider as
  available.
- A History menu action scans downloaded videos for a verifiable quality
  shortfall, presents likely candidates as an initially unselected review list,
  verifies only the sources the user explicitly selects, and queues bounded
  replacements. The local scan compares
  the actual video stream with the original numeric request; before queueing,
  live source metadata reduces the target to the current per-item maximum. It
  does not classify audio, local media, active duplicates, intentional
  low-quality choices, hard-sub replacements, or items without a trustworthy
  numeric target.

Quality replacement downloads are staged and media-validated before History or
the original file changes. A successful replacement preserves History identity,
keywords, user metadata, playback state, and source date, then removes stale
original paths. A failed or cancelled replacement leaves the original intact;
ordinary batch inspection failures do not stop later items. Incognito mode
disables the batch action because it cannot provide the required History
provenance.

Main code: `HistoryFragment`, `HistoryPaginatedAdapter`, `HistoryViewModel`,
`HistoryRepository`, History/keyword/group DAOs and repositories,
`LocalAddWorker`, `StoredLocation`, and `HistoryFileDeletion`.

Limitations: very large libraries can make full user-triggered scans or
metadata backfills expensive. Android providers do not expose identical file
capabilities, so some locations support only a fallback action. "Best" History
records without a recorded numeric expectation are not classified, avoiding an
unbounded extractor crawl and false quality claims.

### Playback

Status: implemented; device and URI behavior requires manual testing.

- Media3/ExoPlayer playback for local and supported remote media.
- HLS, DASH, and RTSP modules.
- History-context queues and an on-screen queue.
- Saved playback position, last-watched state, and resume behavior.
- Picture-in-picture, background playback, media controls, and a foreground
  keep-alive service.
- Sidecar subtitle discovery/selection and subtitle conversion support.
- Hard-sub scanning and post-processing paths.

Main code: `VideoPlayerActivity`, `PlaybackQueueState`,
`PlaybackKeepAliveService`, `VideoPlayerUtil`, subtitle utilities, and
`HardSubScanWorker`.

Limitations: raw paths, `content://` URIs, SAF permissions, PiP, background
limits, subtitle formats, and automatic queue transitions vary across Android
versions and devices.

### Settings, maintenance, and diagnostics

Status: implemented.

- Theme, accent, high-contrast mode, app icon, language, navigation, and card
  presentation options.
- Download, processing, folder, extractor, format, update, and advanced
  settings.
- App-data/settings backup and restore, including supported database and custom
  thumbnail data.
- Optional application update checks against GitHub Releases and manual/auto
  yt-dlp updates.
- Download logs with shared sensitive-text redaction.
- On-demand runtime diagnostics for yt-dlp, ffmpeg/ffprobe, aria2c, QuickJS,
  storage, notifications, battery optimization, and related prerequisites.
- App-owned cache scanning and cleanup with ownership checks.
- Cookie management through an in-app WebView.
- YouTube player-client and PoToken configuration/generation.
- Terminal downloads plus a dry-run preview generated from the same sanitized
  command plan used by execution.

Main code: settings fragments, `SettingsViewModel`, `BackupSettingsUtil`,
`UpdateUtil`, `RuntimeDiagnostics`, `AppCacheManager`, cookies/PoToken WebViews,
`TerminalCommandPlan`, and Terminal UI/workers.

## Internal infrastructure and developer tooling

### Persistence

Room schema version 53 stores results, downloads, History, logs, cookies,
command templates, search history, terminal jobs, observed sources, playlists,
keyword/creator grouping, automatic keyword rules, and supporting cross
references. DAOs feed repositories, ViewModels, Paging, and workers. Exported
schemas live under `app/schemas`.

### Background execution

WorkManager workers perform downloads, terminal execution, source observation,
metadata/format refresh, local import, hard-sub scanning, cache movement and
cleanup, yt-dlp updates, PoToken generation, and automatic-keyword sync.
AlarmManager is an optional scheduler trigger.

### Runtime and media toolchain

The app integrates youtubedl-android/yt-dlp and aria2c. It installs and probes
bundled ffmpeg/ffprobe and subtitle-conversion runtime material. Native/runtime
packaging is release-critical and intentionally not described as universally
supported without device evidence.

### Safety controls

- `YtdlpArgumentPolicy` and `YoutubeDLCompat` sanitize executable options.
- `SensitiveTextRedactor` and `AppPrivatePathRedactor` protect diagnostics.
- `FileProvider` exposes only scoped shared paths.
- Storage deletion and cache cleanup use ownership/reference policies.
- Most externally reachable Android components are narrow share/launcher
  entry points; worker-control receivers and services are non-exported.

### Build and CI

The project is a single `:app` Gradle module. GitHub Actions run debug Kotlin
compilation and JVM unit tests for pull requests and `main`, then produce signed
release APKs when release secrets are available. Release notifications are sent
to Telegram by a separate best-effort step.
