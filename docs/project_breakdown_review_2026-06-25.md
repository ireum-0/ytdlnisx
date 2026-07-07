# YTDLnisX Project Breakdown and Review

Date: 2026-06-25  
Repository: `D:\AndroidStudioProjects\ytdlnisx`  
Reviewer: Codex

## 1. Scope and method

This review treats the repository as an Android/Kotlin/Gradle project. I inspected project structure, Gradle configuration, Android manifest/component exposure, Kotlin package layout, WorkManager/download logic, Room database structure, file/storage helpers, extractor/runtime integration, playback code, resources, tests, docs, and CI metadata.

Deliberately not inspected: secret-bearing files such as `local.properties`, `keystore.properties`, `.env`, signing material, token/cookie values, generated build outputs, `.tmp_*` work areas, bundled executable/native binary contents under `app/src/main/assets/bin/`, and native library binary contents under `app/src/main/jniLibs/`.

No Gradle build, test suite, connected-device run, release build, or network-dependent command was run for this documentation-only review.

## 2. High-level project snapshot

- Product: YTDLnisX, an unofficial Android fork of YTDLnis for yt-dlp based media download, local library/history, playback, subtitles/hard-sub processing, command templates, cookies, observe sources, and terminal-style command downloads.
- Android module layout: single Gradle module, `:app`.
- Application ID / namespace: `com.ireum.ytdl`.
- Current version in `app/build.gradle`: `1.8.9` with `versionCode = major * 1000000 + minor * 10000 + patch * 100 + build`.
- SDK targets: `minSdk 24`, `compileSdk 36`, `targetSdk 36`.
- Language/runtime: Kotlin, AndroidX, Room/KSP, WorkManager, Media3, ViewBinding, some Compose dependencies, Java 17 bytecode.
- Approximate app source shape, excluding build output and binary internals:
  - `app/src/main/java`: 213 Kotlin files.
  - Kotlin lines by top package: `ui` ~30.5k, `util` ~12.2k, `database` ~10.2k, `work` ~5.3k, `receiver` ~0.7k.
  - Largest files: `HistoryFragment.kt` (~8.6k), `VideoPlayerActivity.kt` (~4.6k), `DownloadWorker.kt` (~3.3k), `UiUtil.kt` (~2.8k), `YTDLPUtil.kt` (~2.2k).
  - `res`: 411 files, dominated by layout/drawable/menu/value XML.
  - `assets`: 197 files / ~243 MB, mostly `bin` payloads plus `po_token.html`.
  - `jniLibs`: 82 files / ~80 MB, currently under `arm64-v8a` in the visible tree.
  - Room schemas: 75 JSON schema files across old and current package paths.
  - Tests: 2 local unit test files and 1 example instrumentation test file.

## 3. Build, Gradle, packaging, and release surface

### What exists

- Root `settings.gradle` includes only `:app`, which matches the current repository layout.
- Root plugins pin Android Gradle Plugin `8.13.2`, Kotlin `2.3.0`, Kotlin serialization/parcelize/compose plugins, and KSP `2.3.4`.
- `app/build.gradle` enables ViewBinding, BuildConfig, Compose, ABI splits, legacy JNI packaging, desugaring, Room schema export via KSP, release minify/shrink, and release signing from `keystore.properties` if present.
- ABI splits include `x86`, `x86_64`, `armeabi-v7a`, `arm64-v8a`, plus universal APK.
- CI (`.github/workflows/android.yml`) builds `./gradlew assemble --warning-mode all`, uploads per-ABI release APKs, and uses GitHub secrets to create signing inputs during CI.

### Review notes

- The build is native/binary-heavy. Runtime code installs ffmpeg payloads and uses JNI/shared-library paths, so every packaging change should be smoke-tested per ABI.
- `gradle.properties` has `android.suppressUnsupportedCompileSdk=35` while `compileSdk = 36`; this looks stale and should be removed or updated if still needed.
- Plugin versions are very new-looking and should be validated on a clean machine/CI because KSP and Kotlin versions normally need tight compatibility.
- `app/src/main/assets/bin/` and `app/src/main/jniLibs/` are a large part of the app surface even though binary internals were not inspected. Treat them as release-critical assets.

## 4. Android manifest, permissions, and entry points

### Main permissions

The app requests network, storage/media, notification, exact alarm, battery optimization, foreground service, data-sync foreground service, and media-playback foreground service permissions. These match the app's core jobs but make exported component and storage handling high impact.

### Components

Key manifest components include:

- `App` application class.
- `MainActivity` and launcher aliases for app icons.
- `ShareActivity` with many `SEND` / `VIEW` filters for YouTube, social, audio/video, and plain text sharing.
- `TransparentActivity`, `SettingsActivity`, and `TerminalActivity` with custom actions.
- `VideoPlayerActivity`, not exported, PiP-capable.
- Notification/action receivers for cancel, pause, observe retry decisions, schedule alarms, and PiP actions.
- `PlaybackKeepAliveService` as a media playback foreground service.
- WorkManager foreground service override with `foregroundServiceType="dataSync"`.
- `FileProvider` at `${applicationId}.fileprovider`.

### Review notes

- Several previously high-risk internal components are now non-exported (`ResumeActivity`, cookie WebView, Po-token login WebView, most app receivers). This is a good hardening direction.
- Exported surface still exists by design: launcher aliases, `ShareActivity`, `TransparentActivity`, `SettingsActivity`, `TerminalActivity`, terminal/quick-download aliases, and media button receiver. Keep exported components minimal and ensure all untrusted extras/actions are validated.
- `ShareActivity` now appears to derive background quick-run from alias metadata rather than caller-provided extras, and no longer parses a caller-provided `TYPE` enum directly. That reduces prior crash/queueing risk.
- `ShareActivity` still logs the full received intent (`Log.e("aa", intent.toString())`), which may include shared URLs. This should be removed or sanitized for privacy.

## 5. Application startup and bundled runtime tools

### What exists

- `App.onCreate()` initializes defaults, notification channels, yt-dlp, aria2c, ffmpeg payload files, and the bundled `yttml` subtitle converter asynchronously on `Dispatchers.IO`.
- `installBundledFfmpegPayload()` installs an ABI-specific `ffmpeg_payload.zip` into app no-backup storage, validates zip extraction paths by canonical target prefix, copies runtime `.so` dependencies, materializes shared-library placeholder files, and records a payload revision.
- `YoutubeDLCompat` adds app-selected `--ffmpeg-location`, injects quickjs runtime, manages process IDs, and blocks external config/ffmpeg/process-spawning options unless the config file is app-generated.

### Review notes

- Zip-slip protection exists for ffmpeg payload extraction.
- Runtime installation is guarded by a lock, but it runs asynchronously during startup; cold-start paths that need yt-dlp/ffmpeg immediately should continue calling `ensureRuntimeToolsInstalled()` defensively, as some code already does.
- Native payload revision is hardcoded (`arm64-wrapper-libffmpeg-0.18.1-r12`), so release process should explicitly bump/test it when payload contents change.
- Binary payload coverage must be checked in generated APKs for all configured ABI splits, especially because visible `jniLibs` only showed `arm64-v8a` while Gradle emits multiple ABIs.

## 6. UI, navigation, resources, and feature screens

### Major UI areas

- Root UI: `BaseActivity`, `HomeFragment`.
- Download card/configuration flows: `DownloadBottomSheetDialog`, `DownloadMultipleBottomSheetDialog`, `CutVideoBottomSheetDialog`, command templates, observe sources, format/subtitle selection dialogs.
- History/library: `HistoryFragment`, paginated adapters, local folder/library management, filters, keyword/youtuber/playlist grouping.
- More/settings: settings fragments, folder settings, advanced YouTube player client and po-token generation, update settings.
- Cookies and terminal: cookie import/export/WebView support and terminal command download UI.
- Playback: large `VideoPlayerActivity` with Media3, PiP, local history context, thumbnail handling, subtitles, and playback-state persistence.

### Review notes

- UI code is concentrated in a few very large classes/fragments. `HistoryFragment`, `VideoPlayerActivity`, `DownloadWorker`, and `UiUtil` are maintainability hotspots where regressions are hard to isolate.
- Layout resources are broad and include multiple landscape/tablet qualifiers, which is appropriate for player/library UX but increases manual test matrix.
- Compose is enabled and dependencies are present, but the visible codebase remains primarily XML/ViewBinding/Fragment-based.

## 7. Room database and data layer

### What exists

- `DBManager` Room database version: `50`.
- Entities include results, history, downloads, command templates, search history, template shortcuts, cookies, logs, terminal downloads, observe sources, playlists, playlist groups/members, keyword groups/members, youtuber groups/members/relations, and youtuber metadata.
- DAOs: 14 DAO files, with repositories and AndroidViewModels wrapping most data access.
- Migration list covers manual and auto migrations, including versions 13-14, 20-26, 29-50 and auto migrations 1-13, 14-20, 26-29.
- Schema exports exist under `app/schemas`, including legacy package paths and current `com.ireum.ytdl.database.DBManager`.

### Review notes

- Room is a high-risk area because DB version is high and the schema history is long. Keep migration tests as a priority before release.
- Some migration code still uses raw `execSQL` with interpolated data from existing rows. This is mostly local migration data, but parameterized statements or compiled statements would be safer and less fragile.
- Cursor handling is mixed: newer migration blocks close cursors, older migration blocks do not consistently close them. This is not likely a user-facing functional bug on short migrations, but it is technical debt.
- Current schema includes indexes for important history/download query paths. This helps the large history UI, but paging/filter query performance still needs device-sized data tests.

## 8. WorkManager, download queue, scheduling, and background execution

### What exists

Workers:

- `DownloadWorker`: central queued/scheduled download execution, yt-dlp requests, cache/temp handling, retry decisions, hard-sub burn-in, metadata/history/log updates, progress notifications.
- `TerminalDownloadWorker`: executes terminal command config through yt-dlp compatibility layer and moves outputs.
- `ObserveSourceWorker`: periodic source observation, duplicate/retry prompt tracking, scheduling next runs.
- `HardSubScanWorker`, `LocalAddWorker`, `MoveCacheFilesWorker`, multiple update/metadata workers, cleanup workers, and alarm scheduler helpers.

### Review notes

- `DownloadWorker` now uses serialized `downloadWorkerMutex`, `setForegroundSafely()` returns a Boolean and the worker retries if foreground setup fails, and eligible downloads are launched inside a `coroutineScope`. This is a stronger model than detached ad-hoc jobs.
- `TerminalDownloadWorker` now awaits the cache move in `withContext(Dispatchers.IO)` before success, which reduces false-success risk.
- `AlarmScheduler.cancel()` now uses `PendingIntent.getBroadcast(...)`, matching scheduled alarm creation.
- Still recommended: add explicit `onStopped()` cleanup for active process IDs/statuses in long-running workers, plus device tests for WorkManager cancellation, constraints, and Android 14 foreground-service behavior.
- `CleanUpLeftoverDownloads` uses `FileUtil.deleteCachePathIfAppOwned()`, which is safer than deleting an arbitrary configured cache path.

## 9. yt-dlp, extraction, networking, command templates, and cookies

### What exists

- `YTDLPUtil` builds yt-dlp requests, formats, streaming URLs/chapters, playlist/channel data, config files, and ffmpeg/runtime paths.
- `YoutubeDLCompat` wraps yt-dlp execution through Python/native runtime paths and sanitizes dangerous external options.
- NewPipe support exists through `NewPipeUtil`, `NewPipeDownloaderImpl`, and po-token generation/webview helpers.
- YouTube API support exists in `YoutubeApiUtil`, with fallback to NewPipe or yt-dlp paths depending settings/data source.
- Cookie support includes database persistence, WebView extraction, export/import, and generated cookies file updates.

### Review notes

- Option policy improved: config, ffmpeg-location, and process-spawning options such as `--exec`, `--external-downloader`, downloader args, and postprocessor args are blocked/sanitized unless app-generated where intended.
- Terminal mode still intentionally executes user-provided command text through an app-generated config file. Treat this as a power-user feature: keep warnings clear and keep it separated from silent/imported template flows.
- Network and extractor logging still includes user queries, titles, intent strings, and diagnostic paths in multiple places. A privacy-oriented logging pass is recommended before release.
- `ResultRepository.getStreamingUrlAndChapters()` now returns `emptyList()` on failure and filters blank URLs; expiry checks use `creationTime < now - oneHour`, which addresses older stale/blank streaming URL problems.

## 10. File, SAF, cache, sharing, and storage utilities

### What exists

- `FileUtil` handles file deletion, SAF URI mapping, MediaStore deletion, file moves, destination writability checks, cache/backup/default paths, config cleanup, open/share intents, and size formatting.
- `provider_paths.xml` currently allows:
  - `external-path` limited to `Download/YTDLnisx/`.
  - `external-files-path` for app external files.
  - `cache-path` for app cache.
- `deleteDocumentUri()` refuses tree URIs that are not document URIs.
- `deleteCachePathIfAppOwned()` refuses recursive cache deletion unless the resolved path is under app cache/external cache/external files.
- `openFileIntent()` and `shareFileIntent()` grant read-only URI permission.

### Review notes

- FileProvider scope has been narrowed compared with the previously documented broad root/external provider risk.
- SAF tree URI deletion is guarded, reducing accidental whole-directory deletion risk.
- Continue to avoid deleting configured directory roots. Prefer deleting known child folders/files after canonical checks.
- `FileUtil.deleteFile()` logs failed deletion paths. For privacy, consider redacting user-visible file paths in production builds.

## 11. Media playback, subtitles, PiP, and hard-sub processing

### What exists

- Playback is centered in `VideoPlayerActivity` using Media3 ExoPlayer/UI, PiP support, saved progress, thumbnails, chapter/metadata handling, and history context navigation.
- Hard-sub processing is integrated into `DownloadWorker` and helper utilities, with subtitle validation/conversion (`SubtitleFileValidator`, `SubtitleFormatConverter`, `SubtitleSelection`, `YoutubeTimedTextFallback`) and ffmpeg runtime selection/fallback logic.
- Unit tests currently focus on subtitle selection and conversion.

### Review notes

- This area is functionality-rich and high risk. The large activity/worker combination means playback, download, hard-sub, and file-move changes should be tested together.
- Sidecar subtitle behavior, PiP aspect ratio, SAF-backed media paths, and ffmpeg/hard-sub behavior should be verified on real devices, not just compile tests.
- Existing subtitle unit tests are useful but narrow. Add tests for subtitle language matching, malformed subtitle files, ffmpeg command construction, and playback path resolution where possible.

## 12. Notifications, foreground services, and user-visible state

### What exists

- `NotificationUtil` creates channels and numerous download/terminal/observe/playback notifications.
- Download and terminal workers run as foreground data-sync work.
- Playback keep-alive service runs as media playback foreground service.
- Receivers support pause/cancel/retry/PiP actions.

### Review notes

- Notification setup is a critical path because Android 13+ notification permission and Android 14 foreground service types are involved.
- Foreground setup is now handled more explicitly in `DownloadWorker`; other foreground paths should be checked for equivalent failure handling.
- Review lock-screen visibility and notification text for private/incognito downloads, URLs, command contents, and errors.

## 13. Documentation, release metadata, and repository hygiene

### What exists

- README describes the fork status, package name, install path, GPL v3 license, and major differentiators.
- `docs/` contains prior audit material, adoption planning, release notes, and verification notes.
- Fastlane metadata is present.
- `CHANGELOG.md` appears to contain mojibake/encoding damage in parts of the Korean text.
- Several generated/temp-looking `.tmp_*` files/directories exist at repository root. These were not inspected and should not be modified casually.

### Review notes

- Some prior audit docs are stale relative to the current source. For example, settings now include only `:app`, release signing config no longer obviously reuses debug signing in `app/build.gradle`, FileProvider is narrowed, tree URI deletion is guarded, terminal move is awaited, and streaming URL fallback is fixed.
- Keep audit docs versioned or mark them with status so future reviews do not treat fixed items as current defects.
- Consider cleaning or git-ignoring local `.tmp_*` and extracted binary work areas if they are not intended product inputs.

## 14. Tests and verification coverage

### Current tests

- `ExampleUnitTest.kt`: placeholder arithmetic test.
- `SubtitleSelectionTest.kt`: useful focused tests for Korean/living-chat subtitle selection, automatic caption exclusion, empty JSON3 validation, and JSON3 conversion to ASS/SRT.
- `ExampleInstrumentedTest.kt`: package-name smoke test.

### Coverage gaps

Recommended new tests/checks:

1. Room migration tests from representative old schemas to version 50.
2. DAO tests for queue ordering, duplicate detection, scheduled downloads, and history filters.
3. Unit tests for `YoutubeDLCompat` option sanitization and command-template policy.
4. FileUtil tests for tree URI refusal, app-owned cache deletion boundaries, provider path allowlist, and move failure behavior.
5. WorkManager/device tests for cancel/pause/stop, foreground failures, constraint changes, and notification cleanup.
6. Playback tests/manual scripts for SAF paths, local/raw paths, sidecar subtitles, PiP, and background playback.
7. ABI smoke tests for yt-dlp, aria2c, ffmpeg, ffprobe, yttml, hard-sub burn-in, and package payload installation.
8. Privacy tests to ensure URLs, cookies, API keys, headers, proxy values, and command text are not leaked into logs, exported logs, or public notifications.

## 15. Key strengths observed

- Single-module structure is easy to orient.
- Current code shows targeted hardening since earlier audits: narrowed FileProvider, non-exported internal WebViews/ResumeActivity, tree URI deletion guard, safer cache cleanup, WorkManager foreground failure handling, terminal move awaiting, and streaming URL failure cleanup.
- Room schema export is enabled and schema files are retained.
- App has feature depth: downloads, scheduling, observation, local library, playback, subtitles/hard-sub, cookies, command templates, and terminal mode.
- Runtime payload installation includes canonical zip extraction checks and dependency materialization.

## 16. Main risks and recommended priorities

### P1 - Release-blocking or near release-blocking checks

1. Run a clean Gradle compile/build check and fix any Kotlin/KSP/AGP compatibility problems. Suggested first command: `./gradlew :app:compileDebugKotlin -x lint`.
2. Run per-ABI APK smoke tests for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, and universal outputs, focused on yt-dlp, ffmpeg/ffprobe, aria2c, hard-sub, and subtitle converter availability.
3. Validate Room migrations to version 50 with exported schemas.
4. Device-test WorkManager cancellation/foreground behavior on Android 12-14.
5. Perform a privacy/logging pass to remove unsanitized URL/query/intent/path/token diagnostics from production paths.

### P2 - Important maintainability and correctness work

1. Break up `HistoryFragment`, `VideoPlayerActivity`, `DownloadWorker`, `UiUtil`, and `YTDLPUtil` into smaller testable collaborators over time.
2. Add explicit `onStopped()` cleanup for workers that own native processes.
3. Expand tests around command sanitization, FileUtil boundaries, queue consistency, and streaming/playback fallbacks.
4. Update stale docs and repair mojibake in `CHANGELOG.md`.
5. Reconcile `android.suppressUnsupportedCompileSdk=35` with `compileSdk=36`.

### P3 - Nice-to-have cleanup

1. Remove placeholder tests or replace them with meaningful smoke tests.
2. Remove stray debug tags such as `"aa"`, `"Aaa"`, and `"AAAAAAAAAAAAA"`.
3. Review whether generated `.tmp_*` files/directories should be outside the repository or ignored.

## 17. Suggested manual verification matrix

- Fresh install and upgrade install.
- First-run runtime payload initialization.
- Quick share from YouTube/browser/social apps.
- Batch text-file share within and over the 128 KiB cap.
- Normal video download, audio download, command download, scheduled download, and observe-source-triggered download.
- Pause/cancel/retry from notifications.
- Incognito/disabled logging failure path.
- Local file add and history delete-with-file using raw paths, SAF single-document URIs, and SAF tree grants.
- Playback from history, local folders, and SAF-backed paths.
- PiP enter/exit, background playback, and media button handling.
- Subtitle download, sidecar subtitle playback, hard-sub burn-in, and missing/invalid subtitle behavior.
- Cookie import/export and WebView-generated cookies.
- APK installation and runtime smoke on each ABI split.

## 18. Overall assessment

The project is a substantial Android media/download application with a large product surface in download execution, file handling, native runtime payloads, and playback. The current codebase shows evidence of recent security and reliability fixes, especially around provider scope, exported components, cache/tree deletion, terminal move completion, and streaming URL fallback. The biggest remaining risk is not one isolated file; it is the combination of a large native/runtime download pipeline, many Android background-execution constraints, sparse automated tests, and unsanitized diagnostic logging. Before release, prioritize build verification, ABI/device smoke tests, migration tests, WorkManager lifecycle tests, and privacy/logging cleanup.
