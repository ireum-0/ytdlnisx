# Codebase Audit

> Status: Archived
> Snapshot date: 2026-05-03
> Revalidate all findings against the current source before treating them as current.

> Status: Archived
> Snapshot date: 2026-05-03
> Revalidate all findings against the current source before treating them as current.
## 1. Executive Summary

This audit reviewed the Android/Kotlin/Gradle codebase with emphasis on lifecycle, workers, Room consistency, file deletion/sharing, yt-dlp command construction, Media3 playback, exported components, data fetching, logging, and packaging risk.

No Gradle, test, build, device, or network commands were run. Secret-bearing files and excluded binary/native/build-output paths were not inspected.

Highest-risk items found:

- A user-configurable cache directory can be recursively deleted as if it were app-owned temp storage.
- A stored/restored `content://.../tree/...` history path can reach deletion code that may delete an entire SAF tree.
- yt-dlp template / extra command text is written to executable config files with only narrow blocking of `--config*` and `--ffmpeg-location`.
- Several exported internal components trust external extras and can queue downloads, requeue IDs, clear WebView cookies, or load arbitrary URLs.
- Download and terminal workers use detached coroutines for critical work, weakening cancellation, foreground ownership, and completion/error reporting.

## 2. Reviewed Scope

Reviewed:

- `app/src/main/java/com/ireum/ytdl/**`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/provider_paths.xml`
- selected Android XML resources that affect preferences, navigation, FileProvider, and notifications
- `build.gradle`, `app/build.gradle`, `settings.gradle`, `gradle.properties`
- Room DB manager, migrations, DAOs, repositories, view models, and current schema metadata

Not reviewed by instruction:

- `local.properties`, `keystore.properties`, `.env`, token/cookie/API-key files
- `app/src/main/assets/bin/`
- `app/src/main/jniLibs/`
- `.tmp_*`
- build outputs, APK/AAB files, native binaries

## 3. Review Method

Subagents were used. The review was split into these areas:

1. Android lifecycle / Activity / Service / BroadcastReceiver / notification
2. WorkManager / download queue / retry / cancel / foreground behavior
3. Room / DAO / migration / status transition / data consistency
4. FileUtil / cache / temp directory / delete/deleteRecursively / user output safety
5. yt-dlp / ffmpeg / aria2c / extraCommands / config file / command template security
6. Media3/ExoPlayer / playback / URI handling / PiP / subtitles
7. permissions / exported components / intent filters / FileProvider / storage access
8. data fetch / search / formats / streaming URL / batch URL handling
9. error handling / logging / user-visible failure states
10. build.gradle / Manifest / packaging / ABI / native library risk

Initial subagent creation hit a thread limit, so areas 7-10 were first reviewed locally and then re-run as separate subagents after completed agents were closed. Findings below are de-duplicated. Items with strong source evidence are listed as findings; items needing runtime, merged-manifest, device, or packaged-APK confirmation are in `Needs Verification`.

Main files/directories read:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/provider_paths.xml`
- `app/src/main/java/com/ireum/ytdl/App.kt`
- `app/src/main/java/com/ireum/ytdl/MainActivity.kt`
- `app/src/main/java/com/ireum/ytdl/VideoPlayerActivity.kt`
- `app/src/main/java/com/ireum/ytdl/PlaybackKeepAliveService.kt`
- `app/src/main/java/com/ireum/ytdl/receiver/**`
- `app/src/main/java/com/ireum/ytdl/work/**`
- `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt`
- `app/src/main/java/com/ireum/ytdl/util/NotificationUtil.kt`
- `app/src/main/java/com/ireum/ytdl/util/extractors/**`
- `app/src/main/java/com/ireum/ytdl/database/**`
- `app/src/main/java/com/ireum/ytdl/ui/downloadcard/**`
- `app/src/main/java/com/ireum/ytdl/ui/downloads/HistoryFragment.kt`
- `app/src/main/java/com/ireum/ytdl/ui/more/**`
- `build.gradle`, `app/build.gradle`, `settings.gradle`, `gradle.properties`

## 4. High Severity Findings

### H1. User-configurable cache path can be recursively deleted

- File/function: `CleanUpLeftoverDownloads.doWork`, `FolderSettingsFragment.clearCacheFolder`, `FileUtil.getCachePath`
- Actual risk: Data loss.
- Evidence flow: `FolderSettingsFragment.changePath()` stores arbitrary SAF-selected `cache_path`; `FileUtil.getCachePath()` returns `formatPath(preference)` directly when set; `FolderSettingsFragment` manual cache clear recursively deletes children of that path; `CleanUpLeftoverDownloads.doWork()` calls `File(FileUtil.getCachePath(context)).deleteRecursively()` when active download count is zero.
- Impact: If `cache_path` is set or restored to a broad folder such as Downloads, a shared app folder, or another user-selected directory, cleanup can remove unrelated user files.
- Minimal fix: Constrain cache to an app-owned subdirectory or marker-validated directory. Delete only known temp children such as numeric download IDs, `TERMINAL`, `tmp`, and `infojsons` after canonical path checks. Do not delete the configured root.
- Verification method: Set `cache_path` to a test folder containing unrelated files, run clear-cache and leftover-cleanup with no active downloads, and verify unrelated files remain.

### H2. Tree URI history paths can reach whole-directory deletion

- File/function: `FileUtil.deleteFile`, `FileUtil.deleteDocumentUri`, `HistoryRepository.delete*`
- Actual risk: Data loss.
- Evidence flow: Restore/import paths can populate `HistoryItem.downloadPath`; history deletion calls `FileUtil.deleteFilesWithZeroByteSiblings()`; `FileUtil.deleteFile()` routes any `content://` path to `deleteDocumentUri()`; `deleteDocumentUri()` tries both `DocumentFile.fromSingleUri(...).delete()` and `DocumentFile.fromTreeUri(...).delete()`.
- Impact: A crafted/restored history item with a SAF tree URI can cause a delete-with-file action to delete a whole granted directory if the provider permits it.
- Minimal fix: Reject tree URIs in file deletion paths. Only delete single-document/file URIs, and validate stored history paths are files rather than directories/tree roots.
- Verification method: Restore/add a history item whose `downloadPath` is a test SAF tree URI, trigger delete-with-file, and verify the app refuses and tree contents remain.

### H3. yt-dlp templates and extra commands execute with insufficient option policy

- File/function: `YTDLPUtil.buildYoutubeDLRequest`, `YTDLPUtil.addConfig`, `TerminalDownloadWorker.doWork`, `YoutubeDLCompat.sanitizeArguments`
- Actual risk: Security bypass / command capability exposure under the app UID.
- Evidence flow: Command template and extra command content is saved/imported as raw text, reaches `downloadItem.extraCommands` or command mode, is written into app-generated yt-dlp config files, then allowed by `YoutubeDLCompat.allowAppGeneratedConfigFile()`. `YoutubeDLCompat` blocks external `--config*` and `--ffmpeg-location`, but many runtime-sensitive yt-dlp features remain allowed.
- Impact: A malicious imported template or pasted command can enable yt-dlp behaviors such as subprocess execution, downloader overrides, path/output overrides, postprocessor args, or credential/header overrides under this app's permissions.
- Minimal fix: Add a shared yt-dlp option policy before writing/executing config text. For non-terminal templates and `extraCommands`, block execution/path/runtime-sensitive options unless explicitly allowed. Treat Terminal as an explicit unsafe/power-user mode with clear warnings and no silent auto-application.
- Verification method: Add sanitizer tests for blocked options such as `--exec`, downloader overrides, postprocessor args, batch/config/load-info files, cookies/header/proxy overrides, and output/path/cache overrides.

## 5. Medium Severity Findings

### M1. DownloadWorker starts download jobs outside WorkManager-owned coroutine scope

- File/function: `DownloadWorker.doWork`
- Actual risk: Core download cancel/stop regression.
- Evidence flow: `doWork()` collects queued items and starts each download with standalone `CoroutineScope(Dispatchers.IO).launch`; native work runs through `YoutubeDLCompat.execute(...)`. These jobs are not children of the worker coroutine, and there is no `onStopped()` cleanup for active process IDs.
- Impact: WorkManager cancellation, constraints, timeout, or OS stop can leave yt-dlp running outside foreground ownership, with stale notifications or `Active` DB rows.
- Minimal fix: Use structured concurrency inside `doWork` and add `onStopped()` to destroy active process IDs, cancel notifications, and normalize DB statuses.
- Verification method: Start a long download, cancel/stop WorkInfo by tag or unmet constraints, and confirm the process stops, notification clears, and DB status is not stuck `Active`.

### M2. Foreground-service setup failures are swallowed before long-running work

- File/function: `WorkManagerExtensions.setForegroundSafely`, `DownloadWorker.doWork`
- Actual risk: Background execution/foreground-service reliability failure.
- Evidence flow: `setForegroundSafely()` catches foreground-service exceptions and only logs. `DownloadWorker.doWork()` calls it and proceeds.
- Impact: On Android foreground-service restriction paths, downloads may run without a valid foreground service/notification and be killed or behave inconsistently.
- Minimal fix: Return a success/failure result from foreground setup and abort/retry before starting yt-dlp when foreground cannot be established. Await/handle `setForegroundAsync()` in other workers.
- Verification method: On API 31+/34+, queue from restricted/background state and verify downloads start only after foreground service is active.

### M3. Terminal downloads can report success before final file move finishes

- File/function: `TerminalDownloadWorker.doWork`
- Actual risk: User-visible false success and possible missing output.
- Evidence flow: After yt-dlp returns, non-`noCache` move runs in detached `CoroutineScope(Dispatchers.IO).launch`; the worker immediately updates logs, cancels the notification, deletes the terminal row, and returns `Result.success()`. Move exceptions only print/toast inside the detached coroutine.
- Impact: UI can report completion while output is still moving or failed to move; process death during the detached move can leave missing/partial final output with no retry/error state.
- Minimal fix: Await `FileUtil.moveFile(...)` in the worker scope and return failure if the move fails. Keep notification/row until final move result is known.
- Verification method: Run a terminal download with cache enabled to an unwritable/unavailable destination and confirm the worker fails and preserves an actionable error.

### M4. Worker metadata refresh can overwrite user cancel/pause status

- File/function: `DownloadWorker.doWork`
- Actual risk: Core cancel/pause state regression.
- Evidence flow: The first metadata refresh checks `dao.checkStatus(id) == Active` before full-row update. The later refresh after `YoutubeDLCompat.execute()` calls `resultRepo.updateDownloadItem(downloadItem)?.apply { dao.updateWithoutUpsert(this) }` without rechecking status; `downloadItem.status` was set to `Active` earlier.
- Impact: A cancel/pause racing with post-download metadata refresh can be overwritten, and the worker can continue with stale state.
- Minimal fix: Re-read status before the second update and update only metadata columns if the row is still `Active`.
- Verification method: Instrument a delayed metadata update, set row status to `Paused`/`Cancelled` before the second refresh, and assert status remains unchanged.

### M5. Scheduler alarm cancellation uses the wrong PendingIntent type

- File/function: `AlarmScheduler.cancel`
- Actual risk: Stale scheduled start/stop alarms.
- Evidence flow: Alarms are created with `PendingIntent.getBroadcast(...)`, but `cancel()` looks them up with `PendingIntent.getService(...)`. PendingIntent type is part of identity, so the lookup misses.
- Impact: Scheduled download start/stop receivers can fire after scheduling was cancelled or settings changed.
- Minimal fix: Use `PendingIntent.getBroadcast(...)` in `cancel()` with the same receivers, request codes, flags, and actions used during scheduling.
- Verification method: Schedule then cancel, inspect `dumpsys alarm` or assert in a focused test that no start/cancel receiver fires.

### M6. Exported ShareActivity trusts caller-controlled extras

- File/function: `ShareActivity.handleIntents`
- Actual risk: Security/UX bypass and crash path.
- Evidence flow: Manifest exports `ShareActivity` for `SEND` and many `BROWSABLE VIEW` filters. The activity reads external `TYPE` and `BACKGROUND`; invalid `TYPE` reaches `DownloadType.valueOf(...)`, and `BACKGROUND=true` bypasses the download card and queues work.
- Impact: Another app can crash the share entry or cause unsolicited background queueing/network/storage work.
- Minimal fix: Parse `TYPE` through an allowlist/fallback. Ignore `BACKGROUND`/quick-run extras on exported intents, or only honor them from non-exported internal components/aliases.
- Verification method: Send external VIEW/SEND intents with `TYPE=bad` and `BACKGROUND=true`; confirm no crash and no background queueing without confirmation.

### M7. Exported ResumeActivity can requeue arbitrary download IDs

- File/function: `ResumeActivity.handleIntents`
- Actual risk: Internal state change from external caller.
- Evidence flow: Manifest exports `ResumeActivity` with custom action `ytdlnisx.ResumeActivity`; `handleIntents()` reads `itemID`, cancels a notification, and calls `reQueueDownloadItems(listOf(id))`.
- Impact: Another local app can restart/cause downloads for known or guessed IDs.
- Minimal fix: Set `android:exported="false"` and remove the external intent filter, or require a signature permission/private token.
- Verification method: External `am start` with `itemID` should be denied after fix while notification resume still works.

### M8. Exported WebView activities trust external extras

- File/function: `WebViewActivity.onCreate`, `PoTokenWebViewLoginActivity.onCreate`
- Actual risk: Cookie/session disruption and arbitrary WebView content.
- Evidence flow: Both activities are exported. `WebViewActivity` force-reads `url` extras, clears global WebView cookies on first creation, enables JavaScript, and loads the supplied URL. `PoTokenWebViewLoginActivity` trusts `url`, `redirect_url`, and `no_auth`, writes supplied URL to preferences, clears cookies when `no_auth=true`, and loads the supplied URL.
- Impact: External apps can wipe app WebView cookies, disrupt YouTube auth/token generation state, or display arbitrary web content inside the app.
- Minimal fix: Set both activities `android:exported="false"`. If external launch is truly required, validate caller/action and restrict schemes/hosts.
- Verification method: External launches with `https://example.com` and `no_auth=true` should be rejected and should not alter cookies/preferences.

### M9. FileProvider scope is overly broad and view grants include write

- File/function: `provider_paths.xml`, `FileUtil.openFileIntent`, `FileUtil.shareFileIntent`, `NotificationUtil.createDownloadFinished`
- Actual risk: Overbroad URI grant surface.
- Evidence flow: Provider paths include `<root-path path="."/>` and `<external-path path="."/>`; `openFileIntent()` creates FileProvider URIs for raw paths and grants read plus write; share/notification paths grant access through the same broad provider.
- Impact: If an unintended path reaches open/share helpers, the selected recipient can receive URI access outside intended output directories. Write grants allow mutation where provider/path permissions allow it.
- Minimal fix: Remove `root-path`, narrow paths to explicit app/cache/download directories, and remove `FLAG_GRANT_WRITE_URI_PERMISSION` for view/share flows unless an edit workflow exists.
- Verification method: FileProvider URI generation should succeed only for intended output/cache files and fail for private/root paths outside the allowlist.

### M10. Shared batch URL stream is read fully without size cap

- File/function: `MainActivity.handleIntents`
- Actual risk: ANR/OOM denial of service.
- Evidence flow: Exported launcher aliases accept `ACTION_SEND application/txt`; `MainActivity` opens `EXTRA_STREAM`, reads every char into an unbounded `StringBuilder`, and passes the full string onward for URL parsing.
- Impact: A malicious or buggy sender can send a huge/blocking content URI and exhaust memory or block startup/navigation.
- Minimal fix: Stream line-by-line with maximum byte/line limits, enforce MIME/type expectations, and reject oversized inputs with a user-visible error.
- Verification method: Share a large text file via `ACTION_SEND`; app should remain responsive and reject oversized input.

### M11. Batch query result aggregation mutates shared MutableList concurrently

- File/function: `ResultViewModel.parseQueriesImpl`
- Actual risk: Batch search/data consistency failure.
- Evidence flow: A shared `mutableListOf<ResultItem?>()` receives `addAll(...)` from up to 10 concurrent `Dispatchers.IO` jobs under a semaphore.
- Impact: Multi-URL shared files or multi-query searches can produce missing results, nondeterministic ordering, or rare runtime failures.
- Minimal fix: Use `async`/`awaitAll` to collect per-query lists, or guard `res.addAll` with a `Mutex`.
- Verification method: Coroutine test with many fake queries returning known counts, repeated multiple times, should always match expected total.

### M12. Streaming URL expiry logic is inverted

- File/function: `ResultCardDetailsDialog.onCreateDialog`, `DownloadBottomSheetDialog.onViewCreated`
- Actual risk: Preview/cut playback failure from stale direct URLs.
- Evidence flow: Comments say to remove URLs older than one hour, but condition clears recent items (`creationTime > now - 3600000`) and leaves older items intact. Later preview paths reuse cached URLs.
- Impact: Expired direct streaming URLs can be reused while fresh URLs are unnecessarily refetched.
- Minimal fix: Invert the check to clear when `creationTime < now - 3600000`, ideally with a dedicated `urlsFetchedAt` timestamp.
- Verification method: Seed a `ResultItem` with old URLs and verify details/cut flows refetch before playback.

### M13. Streaming URL fetch failure returns a non-empty blank URL list

- File/function: `ResultRepository.getStreamingUrlAndChapters`, `ResultCardDetailsDialog`, `CutVideoBottomSheetDialog`
- Actual risk: Blank media source reaches ExoPlayer.
- Evidence flow: Repository converts failure to `Pair(listOf(""), null)`. Callers check only `data.first.isEmpty()`, then parse `urls[0]` and add it to Media3.
- Impact: Unsupported/failed extraction can produce a blank media item instead of controlled error UI.
- Minimal fix: Return `emptyList()` on failure or filter blank URLs before caller checks.
- Verification method: Force streaming URL extraction failure and assert no media item is added and an error state is shown.

### M14. Failed downloads can persist logs when logging/incognito says not to

- File/function: `DownloadWorker.doWork`
- Actual risk: Privacy expectation violation.
- Evidence flow: `logDownloads` is false when logging is disabled or item is incognito. On failure, the non-logging branch still inserts a `LogItem`; initial log content includes title, URL, command, and diagnostics.
- Impact: Private URLs, paths, command details, proxy/header options, or failure output can remain in app logs despite disabled logging/incognito.
- Minimal fix: Do not insert a `LogItem` in the non-logging branch. Store only sanitized short error text on the download item/notification if needed.
- Verification method: Disable logs, start an incognito failing download, and verify no new log row appears.

### M15. Sensitive request data is logged or exposed

- File/function: `YTDLPUtil.parseYTDLRequestString`, `DownloadWorker.doWork`, `TerminalDownloadWorker.doWork`, `NetworkUtil.genericRequest`, `NotificationUtil.createDownloadErrored`
- Actual risk: Secret/privacy exposure through logs and notifications.
- Evidence flow: `parseYTDLRequestString()` expands config contents into persisted logs; request construction can include cookies path, proxy, headers, extractor args, and tokens. `NetworkUtil` logs full URLs at error level while `YoutubeApiUtil` builds URLs containing `api_key`. Failed download notifications use title/URL/error text and `VISIBILITY_PUBLIC`.
- Impact: API keys, search queries, auth-adjacent arguments, private URLs, or raw errors can appear in logcat, app logs, exported logs, or lock-screen notifications.
- Minimal fix: Redact sensitive option values before persistence/display, remove full URL logging, redact query parameters such as `key` and `q`, and use `VISIBILITY_PRIVATE` plus generic text for incognito/private failures.
- Verification method: Configure API key/cookies/proxy/header, trigger fetch/download failure, inspect logcat, app logs, exports, and lock screen for redaction.

### M16. Release signing reuses the debug signing config

- File/function: `app/build.gradle`
- Actual risk: Release/debug signing boundary confusion.
- Evidence flow: Keystore properties are loaded into `signingConfigs { debug { ... } }`; both `release` and `debug` set `signingConfig signingConfigs.debug`.
- Impact: Release artifacts may be signed with the Android debug key when no keystore properties load, or real release credentials may be used for debug builds when present.
- Minimal fix: Create a separate `release` signing config, assign it only to `buildTypes.release`, and keep debug on the default debug signing config.
- Verification method: Run `./gradlew :app:signingReport` after fixing and confirm intended debug/release certificate separation.

### M17. Settings include missing Gradle modules

- File/function: `settings.gradle`
- Actual risk: Fresh checkout/CI build configuration failure.
- Evidence flow: `settings.gradle` includes `:common`, `:app`, `:library`, and `:ffmpeg`, but root file listing showed no `common/`, `library/`, or `ffmpeg/` directories.
- Impact: Gradle project resolution can fail before reaching `:app`.
- Minimal fix: Remove stale includes or map them to real module locations; keep only modules present in this repository.
- Verification method: Run `./gradlew :app:projects` or `./gradlew :app:compileDebugKotlin -x lint` after fixing.

### M18. Queue and duplicate consistency issues

- File/function: `DownloadViewModel.queueDownloads`, `detectAndMarkDuplicates`, `putAtTopOfQueue`, `putAtBottomOfQueue`, `putAtPosition`
- Actual risk: Queue/data consistency defects.
- Evidence flow: Duplicate prevention compares incoming queued items only against persisted active/queued/scheduled rows, not duplicates within the same batch. Config-mode duplicate detection computes `canonicalHistory` but selects only exact URL history. Queue reorder paths rewrite primary keys through fire-and-forget `CoroutineScope(Dispatchers.IO)` operations rather than a single transaction.
- Impact: Duplicate downloads can be queued; equivalent YouTube URLs can bypass history duplicate checks; interrupted/concurrent reorder can leave temporary IDs or inconsistent order.
- Minimal fix: Add in-batch duplicate sets, use canonical history for config duplicate checks, and move reorder operations into DAO `@Transaction` functions or introduce an explicit order column.
- Verification method: DAO/ViewModel tests for same-batch duplicates, equivalent YouTube URL duplicates, and interrupted/concurrent reorder preserving the ID set.

## 6. Low Severity Findings

### L1. Pause receiver can leave goAsync pending result unfinished

- File/function: `PauseDownloadNotificationReceiver.onReceive`
- Actual risk: Broadcast timeout / stale action handling.
- Evidence flow: `goAsync()` is called before validating `itemID`; `id == 0` exits without `finish()`. Exceptions before coroutine `finally` also lack an outer `.onFailure { result.finish() }`.
- Impact: Malformed/stale notification actions can hold the broadcast async token until system timeout.
- Minimal fix: Validate ID before `goAsync()` or always finish in outer failure/finally paths.
- Verification method: Send pause broadcast with missing/invalid `itemID` and confirm no broadcast timeout.

### L2. ShareActivity overlay view is not removed

- File/function: `ShareActivity.onCreate`
- Actual risk: Window leak on repeated share flows.
- Evidence flow: With overlay permission, the activity creates a `WindowManager` overlay view via `wm.addView(...)`, but `wm` and `myView` are local variables and no `onDestroy()` removal is present.
- Impact: Finishing/reopening share flow can leak an attached window or produce bad-token/window-leak logs.
- Minimal fix: Store the view and window manager in fields and remove the view in `onDestroy()`.
- Verification method: Repeatedly launch/finish share flow with overlay permission enabled and check logcat for leaked window errors.

### L3. Playback keep-alive service sticky restart lacks foreground rebuild

- File/function: `PlaybackKeepAliveService.onStartCommand`
- Actual risk: Playback background service reliability issue.
- Evidence flow: The service calls `startForeground()` only for `ACTION_START` but returns `START_STICKY` for null/unknown intents.
- Impact: Android can restart the service with a null intent and no rebuilt foreground notification, risking service kill or lifecycle errors.
- Minimal fix: Return `START_NOT_STICKY`, or rebuild a valid foreground notification on null restart from persisted state.
- Verification method: Start background playback, kill/restart service/process, and verify clean foreground-service behavior.

### L4. Media playback SAF path handling has edge-case failures

- File/function: `VideoPlayerActivity.handlePlaybackIntent`, `VideoPlayerActivity.buildDocumentUriForPath`
- Actual risk: Playback failure for SAF/raw-path edge cases.
- Evidence flow: `handlePlaybackIntent()` rejects raw paths with `File.exists()` before using the same SAF-aware resolver as playback. The SAF tree permission matching uses prefix checks without a path-boundary check, so a grant for `Movies` can match `Movies2`.
- Impact: Some valid SAF-backed media can be rejected, or wrong document URI attempts can occur for sibling paths.
- Minimal fix: Validate launch paths through the same URI resolver/read-open path used for playback, and require `relPath == treePath || relPath.startsWith("$treePath/")`.
- Verification method: Test playback with persisted tree grant for sibling directories and raw `/storage/...` history paths.

## 7. Needs Verification

- ABI/runtime payload coverage: `app/build.gradle` emits `x86`, `x86_64`, `armeabi-v7a`, `arm64-v8a`, plus universal APKs, while runtime code expects ABI-specific assets and native executable `.so` files. Inspect generated APKs and run native smoke tests per ABI.
- Native `.so` executable behavior: code executes files from `applicationInfo.nativeLibraryDir`; verify legacy extraction/executable bits on installed APK/AAB variants.
- Merged manifest receiver defaults: source manifest has notification/schedule receivers without explicit `android:exported`; verify merged manifest because no Gradle task was run.
- Notification channel timing: `App.kt` creates channels in an async coroutine; verify cold-start worker/playback notification paths on Android O+.
- Terminal worker foreground setup: `TerminalDownloadWorker` calls `setForegroundAsync()` without awaiting/catching; verify Android 12+/14 background-start behavior.
- Sidecar subtitles: player media item creation does not visibly attach `MediaItem.SubtitleConfiguration`; verify expected sidecar `.srt/.vtt` behavior.
- PiP aspect ratio: PiP always uses `Rational(16, 9)`; verify portrait/non-16:9 content.
- Regex DoS/crash from imported template `urlRegex`; imported regex is evaluated during data fetching and needs runtime tests.
- Data-fetch config injection severity: newline injection into watch-videos config is source-backed, but exact execution severity depends on yt-dlp option ordering.
- `YTDLPUtil.getStreamingUrlAndChapters` URL order: callers assume two URLs are `[audio, video]`; verify actual yt-dlp ordering.
- `YTDLPUtil.getFormatsForAll` progress mapping: `urlIdx` increments per callback line; verify warnings/blank lines do not misattribute progress.
- Room migrations: no registration gap was found for version 48, but migration tests were not run.
- `DownloadDao.checkAllQueuedItemsAreScheduledAfterNow()` SQL appears malformed but no active caller was found in audited paths.
- `CrashListener` replaces default uncaught exception handling; verify crash log persistence and process termination behavior.
- Debug po-token logging is guarded by `BuildConfig.DEBUG`; verify debug builds/support logs do not leak token values.

## 8. Must-Fix Items

1. Prevent recursive deletion of arbitrary configured cache paths.
2. Reject tree URIs in file deletion paths.
3. Add a real yt-dlp option policy for templates, extra commands, and non-terminal command injection points.
4. Fix DownloadWorker structured cancellation/stop ownership.
5. Await terminal cache moves before success.
6. Lock down exported internal activities and ignore untrusted external extras.
7. Narrow FileProvider paths and remove write grants from view/share.
8. Redact or suppress sensitive logs, URL logs, failure logs, and public lock-screen error content.
9. Fix stale/blank streaming URL handling.
10. Fix release/debug signing separation.

## 9. Suggested Fix Order

1. Data-loss fixes: cache recursive delete and tree URI deletion.
2. External component/security fixes: exported Share/Resume/WebView activities, FileProvider scope, yt-dlp option policy.
3. Worker correctness: DownloadWorker structured cancellation, foreground failure handling, terminal move await, metadata status race.
4. Privacy/logging: disabled/incognito failure logs, command redaction, API key URL logging, lock-screen visibility.
5. Core UX correctness: streaming URL expiry/failure handling, batch query concurrency, duplicate detection, queue reorder transactions.
6. Build/packaging: signing config separation, stale settings modules, ABI/native packaging verification.

## 10. Manual Device Test Checklist

- Set cache directory to a test folder containing unrelated files; run clear-cache and leftover cleanup; verify unrelated files remain.
- Restore/add a history item with a SAF tree URI; choose delete-with-file; verify directory contents remain.
- Start a long download; cancel/pause/stop WorkManager; verify native process, notification, and DB status stop consistently.
- Run a terminal download with cache enabled to a destination that becomes unavailable; verify worker reports failure and keeps actionable state.
- Send external intents to `ShareActivity` with invalid `TYPE` and `BACKGROUND=true`; verify no crash and no unconfirmed background queue.
- Send external intents to `ResumeActivity`, `WebViewActivity`, and `PoTokenWebViewLoginActivity`; verify they are not externally reachable after fixes.
- Open/share a normal downloaded file; verify read-only access works. Try a path outside allowed roots; verify FileProvider rejects it.
- Disable logging and run an incognito failing download; verify no log row and no public lock-screen URL/error exposure.
- Configure an API key and trigger YouTube API paths; verify logcat redacts/removes full URLs and key.
- Seed stale streaming URLs older than one hour; verify details/cut preview refetches them.
- Force streaming URL extraction failure; verify controlled error UI, not blank ExoPlayer media.
- Share a very large text file to the app; verify graceful rejection and no ANR/OOM.
- Run signing report after signing fix; verify debug/release fingerprints differ as intended.
- Inspect generated APKs per ABI and run a smoke download/ffmpeg/hard-sub/preflight test per supported ABI.

## 11. Areas Requiring Deeper Review

- Full merged-manifest review after Gradle configuration is fixed.
- Device-level WorkManager cancellation and Android 14 foreground-service behavior.
- Room migration tests from older schema versions to 48.
- Packaged APK/AAB ABI asset/native executable verification.
- End-to-end privacy review for logs, backups, crash logs, and support/export flows.
- Fuzzing/limits for shared text input, URL parsing, command template import, and regex fields.

## Must-Fix Summary

The items that must be fixed are: cache path recursive deletion, SAF tree URI deletion, unrestricted yt-dlp option execution, detached download/terminal worker ownership, exported internal activity trust, overbroad FileProvider grants, sensitive logging/notification exposure, stale/blank streaming URL handling, and debug/release signing separation.
