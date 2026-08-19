# Current Project State

Snapshot basis: `main@41d0acf89735c28a07f34cb565bd54e66cd9b6d0`, reviewed 2026-08-19.

## Repository profile

- Android/Kotlin/Gradle single-module application.
- Source version: `1.8.9`.
- minSdk 24; targetSdk/compileSdk 36.
- Room database version: **52** with exported `52.json` committed.
- WorkManager drives download and maintenance background work.
- Media3/ExoPlayer powers playback.
- yt-dlp/native runtime integration remains device/ABI sensitive.

## Implemented capabilities relevant to current engineering work

### Download reliability and diagnostics

Normal downloads use shared redaction before persisted/user-visible diagnostics, structured `DownloadOutcome`/`DownloadIssue` values, high-confidence issue classification, safe suggested actions, and bounded user-initiated retry metadata. Membership/access failures have dedicated handling rather than being collapsed into generic failures.

### CI and migration coverage

`.github/workflows/android-pr.yml` runs whitespace checks, debug Kotlin compilation, and debug unit tests for pull requests with read-only repository permission and no signing step. `.github/workflows/android.yml` separates verification, signing/release build, artifact upload, cleanup, and notification responsibilities.

Representative Android migration tests cover populated upgrade paths through Room 52, including retry metadata and automatic keyword rule/assignment state. These tests existing in source does not prove that connected instrumentation has been executed for the current commit.

### Automatic keyword rules

Playlist-based automatic keyword rules, source-aware keyword assignments, managed observation coverage, backup/restore support, and scheduled/manual sync are implemented. `history_keyword_assignments` is authoritative; `HistoryItem.keywords` is materialized compatibility state.

### Storage and History

History deletion has a dedicated validation/revalidation engine for raw paths, SAF, and MediaStore-like targets. File-location actions can copy file/URI values, copy a common parent, attempt to open the location, and fall back to safe parent text. App-owned cache categories can be scanned and cleared from folder settings while active-download UI gating is applied.

The planned explicit History file-state model is not implemented yet.

### Presets, runtime diagnostics, Terminal, playback

Download presets are stored as versioned/sanitized SharedPreferences JSON, can be created/renamed/applied/deleted, and can supply the Quick Download preset. Runtime diagnostics are user-triggered, bounded, cancellable, and redacted. Terminal preview and execution share a sanitized command plan. Playback queue data is owned by `PlaybackQueueState`, while lifecycle/PiP/subtitles/UI remain in `VideoPlayerActivity`.

## Known correctness findings

These findings were revalidated against the reviewed source snapshot and should be treated as active engineering work, not archived audit claims.

### [Blocker] Backup restore does not remap History replacement markers

`HardSubScanWorker` stores `history-redownload:<historyId>` in the queued `DownloadItem.playlistURL`. Backup serializes queued/scheduled download rows, while restore inserts History rows with newly generated IDs and builds `oldHistoryId -> newHistoryId`. `remapRestoredDownload()` remaps Observe Source IDs but leaves the History marker unchanged.

`DownloadWorker` later parses the marker as the History primary key, replaces that row, and deletes replaced media. After merge/reset restore, an old numeric ID can therefore refer to a different History row or to no restored target at all.

Required direction: remap every History replacement marker using `importedHistoryIdMap`; reject/neutralize an unmappable marker; and revalidate source identity before replacing a History row or deleting its previous media.

### [High] Automatic-keyword baseline can complete from an empty/incomplete fetch

`AutomaticKeywordRuleSyncWorker` receives only a `List<ResultItem>`. `AutomaticKeywordRuleEngine.recordBaseline()` treats an empty list as zero failures and completes the baseline. A later non-empty discovery can then classify all returned existing playlist members as newly eligible even when `apply to existing videos` was false.

Required direction: carry an authoritative fetch-completeness result and complete a baseline only from a trustworthy complete snapshot; add an empty/incomplete-then-nonempty regression test.

### [Medium] History Undo can restore stale derived RULE assignments

Record-only History deletion snapshots keyword assignments. Undo restores RULE rows when the numeric rule ID still exists. Editing a rule condition removes its old assignments/video matches but reuses the same rule ID with a new revision. If that edit occurs during the Undo window, the old assignment snapshot can be restored under the new rule meaning.

Required direction: restore user-owned/manual state, then recompute derived RULE assignments from current rule revision and current video-match state, or persist and validate the source revision in the snapshot.

### [Medium] Metadata refresh can overwrite concurrent download-row edits

`UpdateMultipleDownloadsDataWorker` reads a whole `DownloadItem`, performs a potentially slow metadata lookup, then re-reads only `status` before writing the old object back. Concurrent changes to scheduling/configuration/path or other row fields can be reverted.

Required direction: update only metadata columns owned by the worker, or perform a revision/compare-and-set update against the current row.

### [Medium] Hard-sub scan treats lookup failure as verified absence

`HardSubScanWorker` converts any subtitle metadata lookup exception to `emptyList()`. It then treats the empty list as “requested subtitle not present” and marks the History row removed from the scan. A transient network/extractor failure can therefore permanently skip a valid candidate until a separate reset occurs.

Required direction: distinguish lookup failure from successful empty metadata, leave failed candidates retryable, preserve coroutine cancellation, and bound retries.

## Verification and release confidence

The reviewed source contains many focused unit and instrumentation tests, but this documentation review did not execute Gradle or device tests. The PR workflow currently executes compile + JVM unit tests; it does not make Android instrumentation/migration execution a required PR check.

The `main` branch is not protected in repository settings at this snapshot, so workflows are present but not enforced as required merge checks.

## High-risk areas for future changes

- Room entities/DAOs/migrations/exported schemas and backup/restore ID mapping.
- WorkManager retries, cancellation, foreground execution, and process ownership.
- `DownloadWorker`, yt-dlp/ffmpeg/aria2c integration, cache movement, and partial-success persistence.
- automatic keyword baseline/discovery/revision semantics.
- History deletion and SAF/MediaStore permission boundaries.
- Media3 playback lifecycle, PiP, subtitles, queue transitions, and saved positions.
- diagnostics/redaction paths containing URLs, commands, cookies, tokens, or private paths.
