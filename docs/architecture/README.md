# System Architecture

## Shape of the application

YTDLnisX is a single-module Android application. Most screens use XML layouts,
Fragments, Activities, ViewBinding, RecyclerView, and Material components.
Compose is used for selected WebView-based screens. The application does not
use dependency injection; Android-aware classes obtain the Room singleton and
construct repositories explicitly.

```text
Android intents and UI
        |
        v
Activities / Fragments / dialogs / adapters
        |
        v
AndroidViewModels and feature coordinators
        |
        v
Repositories and policy/utility classes
        |
        +--------> Room DAOs and SharedPreferences
        |
        +--------> extractors, native tools, Android storage, WorkManager
```

## Application and presentation layer

- `App` initializes notification channels, yt-dlp/aria2, bundled subtitle and
  ffmpeg runtime material, default preferences, and automatic-keyword
  scheduling.
- `MainActivity` owns the primary navigation host and routes launcher, share,
  quick-download, and History-return intents.
- `HomeFragment` is the discovery and metadata entry point.
- Download-card dialogs and fragments translate metadata plus settings into
  `DownloadItem` records.
- Download queue fragments display Room-backed states and control WorkManager.
- `HistoryFragment` implements the paged local library and its keyword,
  creator, group, playlist, local-import, file, and metadata actions.
- `VideoPlayerActivity` owns playback UI, Media3 integration, PiP, background
  behavior, saved position, subtitles, and History-context queues.
- More/Settings/Terminal screens expose cookies, command templates, observed
  sources, logs, diagnostics, presets, updates, backup/restore, and advanced
  configuration.

Several presentation classes are very large and coordinate UI, persistence,
storage, and background work directly. This is current implementation, not a
recommended clean-architecture boundary.

## State and persistence

`DBManager` is the Room database singleton. Schema version 53 includes:

- transient extraction results;
- queued and completed download state;
- History/library entries and playback state;
- logs, cookies, search history, command templates, and terminal jobs;
- observed sources;
- playlists and grouping/cross-reference tables;
- keyword and creator/youtuber grouping and metadata;
- automatic keyword rules, matches, and materialized assignments.

Room DAOs are wrapped by repositories and usually exposed through
`AndroidViewModel` classes. History uses Paging 3 with raw queries whose filter
and order clauses are assembled by `HistoryRepository`. SharedPreferences hold
user configuration, small transient coordination records, and versioned
download presets. `BackupSettingsUtil` coordinates supported backup and restore
data.

Extractor input and identity are separate concerns. `WebUrlInput` preserves the
original source spelling while producing an explicit-scheme dispatch URL and a
comparison key. `ExtractorSourceIdentity` temporarily retains requested,
original, and canonical yt-dlp provenance only while parsing and validation are
in process; it is intentionally ignored by Room and Parcel. No downstream
consumer expects it after those boundaries. Persisted source strings remain
unchanged, and later matching rebuilds identity from the shared URL policy.

## Main execution and data flows

### Discovery to download

1. Home or a share intent provides a URL/query.
2. `ResultViewModel` calls `ResultRepository`.
3. The repository selects NewPipe, YouTube Data API, or yt-dlp and stores
   `ResultItem` metadata when needed.
4. Download UI plus `DownloadViewModel` applies direct choices, site command
   rules, defaults, and optionally a Quick Download preset.
5. A `DownloadItem` is inserted into Room and queued through WorkManager.
6. `DownloadWorker` builds a sanitized yt-dlp request, runs native tools,
   streams progress to DB/notifications, performs post-processing/moves, and
   writes History.
7. Structured outcome/issue data controls final state, diagnostics, and retry
   availability.

For video requests with a verifiable target, `VideoQualityPolicy` derives an
effective per-item height and forces staging. `DownloadWorker` probes the merged
media, may perform one quality-triggered public retry through the same request
builder, and records a degraded warning when a normal download must be kept.
Quality-replacement markers persist the expected height through Room and
WorkManager serialization. The final moved file is probed again before the
History row is replaced; `HistoryReplacementFilePolicy` prevents cleanup from
deleting an original on a failed replacement.

### Scheduling and observed sources

Scheduled records stay in Room until WorkManager or the optional exact-alarm
path makes them eligible. Observe Sources stores a source-specific
`DownloadItem` template; `ObserveSourceWorker` discovers new URLs and clones the
template for queueing. Retry metadata identifies one logical operation across
attempts.

### History and file operations

History filters are combined in `HistoryViewModel` and converted to a
`SupportSQLiteQuery` by `HistoryRepository`. Paging observes the History entity.
Keyword/creator/group screens derive aggregate cards and materialized
assignments from the same database. File actions resolve raw paths,
MediaStore/SAF URIs, and FileProvider URIs through storage utilities; deletion
uses reference and ownership checks before touching underlying files.

### Playback

History or local-library actions pass a media URI plus the active History
context to `VideoPlayerActivity`. The activity rebuilds and sorts the context
queue, then `PlaybackQueueState` tracks the displayed base list and current
position. Media3 handles playback while Room stores position/last-watched
updates. A foreground keep-alive service supports background playback; PiP and
media-button receivers provide system controls.

## Background and process boundaries

WorkManager owns download, Terminal, source-observation, metadata refresh,
local-import, hard-sub, cleanup/move, runtime-update, PoToken, and
automatic-keyword jobs. Download and Terminal cancellation also asks the
youtubedl-android runtime to destroy the native process by logical ID.

The app is still one Android process. Room, WorkManager, WebView, Media3, and
native executables have different cancellation and lifetime models, so process
death and partial success are explicitly important reliability cases.

## Storage and security boundaries

- User media may be represented by raw paths, `content://` URIs, MediaStore, or
  persisted SAF tree/document access.
- FileProvider is non-exported and limited to app-selected shared paths.
- Cookies, API keys, proxy credentials, PoTokens, URLs, command options, and
  local paths can be sensitive.
- Command arguments are filtered before execution, and persisted/displayed
  diagnostics pass through redaction.
- Android backup is disabled at the application level; the app provides its own
  explicit backup/restore feature.

## Architecture decisions

Durable decisions are recorded under [`decisions/`](decisions/).

- [ADR-0001: Download preset persistence and precedence](decisions/ADR-0001-download-preset-model.md)
