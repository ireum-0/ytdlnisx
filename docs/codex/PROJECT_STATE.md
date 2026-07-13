# Project State

## Repository profile

- Android application written primarily in Kotlin.
- Single Gradle module: `:app`.
- Main source root: `app/src/main/java`.
- Primary UI style: XML, ViewBinding, Fragments, and Activities.
- Room is used for persistent data.
- WorkManager is used for background and foreground download work.
- Media3/ExoPlayer is used for playback and PiP.
- yt-dlp, aria2c, Python/runtime payloads, QuickJS, ffmpeg, and ffprobe are release-critical.
- Storage code uses raw paths, MediaStore, SAF, DocumentFile, and FileProvider.

## Existing product capabilities

Do not recreate these as new features without checking the current implementation:

- URL and search input.
- Android share intents and quick-download entry points.
- Video, audio, and command downloads.
- Format and subtitle selection.
- Queued, active, scheduled, and observed downloads.
- yt-dlp, aria2c, ffmpeg, and runtime initialization.
- Cookies and YouTube PoToken support.
- Terminal-style command downloads and command templates.
- Download history and local-library management.
- Search, sorting, and filtering in History.
- File open, share, and delete actions.
- Saved playback position and resume playback.
- Media3 playback, PiP, and queue behavior.
- Subtitle selection, conversion, sidecar handling, and hard-sub processing.
- Observe Sources and retry-related source state.
- Settings backup and Room migrations.

## Existing safety and recovery work

Revalidate these before changing adjacent code:

- Dangerous yt-dlp options are filtered through an argument policy.
- Unit tests exist for important argument-policy cases.
- Terminal command and output redaction exists.
- FileProvider exposure is restricted compared with older versions.
- Share URI preparation is centralized.
- Large files are not blindly copied into the share cache.
- Known sensitive filenames and database/config files are blocked from sharing.
- DownloadWorker contains stopped-worker cleanup and requeue behavior.
- Migration smoke tests cover representative recent migration paths.
- Duplicate-related state exists and must be inspected before adding a second duplicate system.
- Observe Sources stores retry and observed-link state.

## High-risk hotspots

Treat changes in these areas as high risk:

- `HistoryFragment.kt`
- `VideoPlayerActivity.kt`
- `DownloadWorker.kt`
- `YTDLPUtil.kt`
- `UiUtil.kt`
- `FileUtil.kt`
- `NotificationUtil.kt`
- `DBManager.kt`
- `Migrations.kt`
- `AndroidManifest.xml`
- `app/build.gradle`
- `.github/workflows/*`

Large files are not, by themselves, permission for broad refactoring. Extract only the responsibility required by the selected task.

## Known planning constraints

### Tests

Automated coverage is limited relative to the feature surface. Do not claim broad regression safety from a compile-only check.

### Native and runtime support

Gradle creates multiple ABI outputs, but actual runtime support must be demonstrated. Do not claim that every generated ABI is fully supported until runtime probes and download smoke tests pass.

An explicit ABI support policy is still required:

- fully supported production ABI,
- best-effort ABI,
- emulator-only ABI,
- or unsupported ABI that should not be published.

### Partial success

A media file may be created successfully while a later step fails, such as:

- subtitle embedding,
- thumbnail handling,
- History insertion,
- final notification,
- final file move.

Do not reduce every post-download issue to a full download failure.

### Fork maintenance

YTDLnisX is a fork. Before implementing a large feature that may exist upstream:

1. Check current local code first.
2. Check upstream only when network access is allowed and the task benefits from it.
3. Do not block a local correctness or security fix on upstream analysis.
4. Keep fork-specific behavior isolated where practical.
5. Report likely merge-conflict areas for large changes.

## Unverified assumptions

The following require verification and must not be treated as facts:

- All ABI artifacts contain working yt-dlp, aria2c, ffmpeg, and subtitle runtimes.
- Every file manager supports opening an exact folder location.
- yt-dlp error strings are stable across versions and extractors.
- File size can always be predicted before download.
- Cookie expiration fields prove that a login session is valid.
- WorkManager constraints behave as an in-process pause mechanism.
- Existing duplicate handling covers every duplicate scenario.
- Existing resume-playback behavior covers every URI type.
