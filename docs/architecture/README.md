# Architecture

This is a concise inventory of the current `main` implementation reviewed at `41d0acf` on 2026-08-19. It is descriptive, not a replacement for source-level review.

## Application shape

YTDLnisX is a single Android application module written primarily in Kotlin. UI fragments and activities call ViewModels, repositories, Room DAOs, WorkManager workers, storage utilities, and yt-dlp/native runtime wrappers directly; there is no dependency-injection boundary separating those layers.

## Persistence

`DBManager` is the Room database singleton. The current database version is **52**. Important persisted surfaces include:

- extraction results, downloads, History, logs, cookies, command templates, terminal jobs, and Observe Sources;
- retry metadata on download rows;
- automatic keyword rules, rule keywords, rule/video matches, and authoritative History keyword-assignment rows.

Exported schemas through `52.json` are committed. `HistoryItem.keywords` is a materialized compatibility projection; `history_keyword_assignments` is the authoritative source-aware representation.

SharedPreferences also stores important settings and feature state, including download presets. Cross-store operations are not transactional and must tolerate process death and backup/restore ID changes.

## Background work

WorkManager is used for downloads, Observe Sources, automatic keyword synchronization, hard-sub scanning, cleanup, and other maintenance work. Foreground execution, cancellation, retry, notifications, and persisted state are correctness-sensitive boundaries.

## Download pipeline

`DownloadWorker` owns the main transfer/post-processing flow. Current supporting policies include:

- redacted diagnostics via `SensitiveTextRedactor`;
- structured `DownloadOutcome` / `DownloadIssue` values;
- high-confidence failure classification;
- bounded user-initiated retry metadata and policy;
- membership/access handling;
- temporary-storage and History persistence cleanup.

Valid media creation and later post-processing failures are modeled separately so partial success can remain visible.

## Automatic keyword rules

Automatic playlist keyword rules are persisted in Room and synchronized by WorkManager. Rule assignments are source-aware and materialized back into History for compatibility. Baseline/discovery semantics and rule revisions are critical: an empty extractor result is not necessarily proof of a complete playlist snapshot, and derived RULE assignments must not be restored solely because a numeric rule ID still exists.

## Storage and History

Storage paths may be raw paths, `file://` values, MediaStore items, or SAF documents. `FileUtil` and `util/storage` provide location handling, safe History deletion, and app-owned cache management. History file deletion performs target validation and revalidation before deleting and must never treat a directory/tree root as a media target.

Backup/restore must remap every persistent cross-row reference. In particular, download markers that embed a History primary key are unsafe if restored without the History ID map; this is a current known blocker tracked in the task registry.

## Playback

Media3/ExoPlayer playback remains hosted by `VideoPlayerActivity`, while queue data ownership is extracted into `PlaybackQueueState`. PiP, background playback, URI resolution, subtitle handling, saved positions, and queue transitions remain high-risk integration surfaces.

## Presets and diagnostics

Download presets are versioned JSON stored in SharedPreferences and applied through a sanitized mapper. Runtime diagnostics are user-triggered and probe yt-dlp/Python, ffmpeg/ffprobe, aria2c, QuickJS, cookie presence, destinations, storage, notifications, and battery optimization with bounded/redacted output.

## Decisions

- [`ADR-0001-download-preset-model.md`](decisions/ADR-0001-download-preset-model.md): accepted persistence and precedence model for download presets.

Add a new ADR when a change introduces a durable architectural decision rather than merely changing implementation detail.
