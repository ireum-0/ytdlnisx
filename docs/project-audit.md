# Current Project Audit

**Audit date:** 2026-07-30
**Source of truth:** the checked-out implementation, resources, manifests,
Gradle configuration, tests, workflows, and documentation

This audit describes what is implemented. It does not promote archived plans or
unverified runtime assumptions to current functionality.

## Purpose

YTDLnisX is an Android media-downloader and local-library application. It
accepts shared or entered links and searches, obtains metadata and formats from
multiple extractors, schedules durable download work, manages the resulting
local media, and plays supported content with Media3. It also provides advanced
automation, terminal-style jobs, source observation, keyword organization,
presets, cookies, and YouTube compatibility controls.

## Architecture

The repository contains one Android module, `:app`, targeting Android API 36
with a minimum API of 24. Kotlin/JVM source targets Java 17. The primary UI is
XML/ViewBinding with Activities and Fragments; Compose is limited to selected
web screens.

The main layers are:

1. **Entry and presentation.** `MainActivity`, `ShareActivity`, feature
   Fragments, dialogs, adapters, and `VideoPlayerActivity` handle navigation,
   user input, editing, file actions, and playback.
2. **State and orchestration.** ViewModels, repositories, schedulers, and
   policy classes translate UI actions into database operations and durable
   work.
3. **Persistence.** Room schema version 53 stores results, downloads, history,
   logs, cookies, search records, terminal jobs, observe-source state, creator
   and keyword group data, automatic keyword rules, and supporting relations.
   SharedPreferences stores settings, presets, UI choices, and lightweight
   coordination state.
4. **Background execution.** WorkManager runs downloads, scheduled-work
   cancellation, terminal commands, local imports, hard-subtitle scans,
   observe-source checks, automatic keyword synchronization, PoToken
   generation, and related maintenance.
5. **Extraction and runtime.** yt-dlp/youtubedl-android is the principal
   extractor and downloader. NewPipe and the optional YouTube Data API provide
   metadata paths. aria2c, ffmpeg/ffprobe, Python/runtime payloads, QuickJS, and
   subtitle tooling support transfer and post-processing.
6. **Storage and sharing.** Raw paths, MediaStore, the Storage Access Framework,
   DocumentFile, content URIs, and FileProvider cover Android-version and
   provider differences.

The app does not use a dependency-injection framework. Components are
constructed explicitly, which makes ownership visible but leaves several
framework-heavy classes difficult to isolate in tests.

## Major execution and data flows

### Link or search to download

1. Text, a shared intent, or a quick-download entry point reaches the home or
   share flow.
2. `ResultRepository` classifies input, invokes the appropriate metadata
   provider, and persists result records.
3. Download dialogs build `DownloadItem` configuration, applying settings,
   templates, presets, formats, subtitles, and duplicate policy.
4. `DownloadViewModel` persists the queue item and schedules WorkManager.
5. `DownloadWorker` initializes required runtimes, performs transfer and
   post-processing, publishes foreground progress, handles cancellation/retry,
   writes history, and cleans temporary state.

### History and local library

Room history is observed by the History screen, which applies search, type,
status, creator, keyword, date, and ordering controls. Actions open, play,
share, edit, retry, locate, or delete content while distinguishing missing,
inaccessible, and app-owned files. Local import flows add user-selected content
through durable work. Source publication time is stored separately from
download time, with an explicit network backfill for older records.

### Playback

History or file actions create a playback context and queue. Media3 playback
restores position, manages subtitles and sidecars, supports queue navigation
and PiP, and persists progress. URI and lifecycle handling remain concentrated
in `VideoPlayerActivity`.

### Automation

Observe Sources schedules periodic checks and stores observed-link and retry
state. Automatic keyword rules evaluate creator/title/source metadata, maintain
coverage, materialize assignments, and schedule synchronization. Scheduled
downloads and terminal jobs use separate WorkManager paths.

## Implemented user-facing features

- URL input, search, Android share targets, quick-download aliases, and
  clipboard-oriented entry paths
- metadata discovery through yt-dlp, NewPipe, and optional YouTube API support
- video, audio, and command downloads with format, subtitle, thumbnail,
  naming, path, authentication, and advanced option controls
- queue, progress, pause/cancel/retry, scheduled downloads, and notifications
- duplicate detection and configurable handling
- local media import, history search/filter/sort/group, source-publication-date
  display/sort/backfill, bulk actions, and missing-file states
- open, share, locate, rename/edit metadata, and delete operations
- Media3 playback, saved position, queues, subtitle handling, and PiP
- download presets and configuration templates
- creator groups, keyword groups, automatic keyword rules, and assignment
  materialization
- Observe Sources monitoring with retry state
- terminal-style command planning, dry-run, execution, templates, and history
- cookies, WebView login, YouTube player-client selection, and PoToken controls
- runtime diagnostics, update controls, settings backup/restore, appearance,
  language, and launcher icon options

Detailed status and limitations are in [Implemented Features](features/README.md).

## Internal infrastructure and developer tooling

- Room migrations and exported schemas through version 53
- structured download outcomes, issue classification, safe retry decisions,
  argument policy, and diagnostic redaction
- centralized share-URI preparation and restrictions on sensitive/app-private
  files
- storage ownership and deletion policies
- focused JVM policy tests and Android migration/device tests
- pull-request compile/unit-test workflow and release build/signing workflow
- release and Telegram notification automation

## External dependencies and services

Major integrations include media sites reached through yt-dlp/NewPipe, optional
YouTube Data API access, Google suggestions, GitHub Releases, a
SponsorBlock-compatible service, WebView cookie/login state, and YouTube
PoToken generation. Room, WorkManager, Media3, AndroidX, Material Components,
Coil, Gson, OkHttp, NewPipe Extractor, and youtubedl-android are major library
dependencies. See [Integrations](integrations.md) for configuration and failure
behavior.

## Configuration and environment

Development requires an Android SDK, JDK 17-compatible toolchain, and the
Gradle wrapper. Local SDK paths and signing credentials are intentionally
outside version control. Release signing and notification secrets are supplied
through CI. Optional app features require user-provided API, cookie, token, or
storage configuration. See [Configuration](configuration.md).

## Incomplete or partial behavior

- Source publication dates cannot be synthesized when providers omit them;
  historical recovery is opt-in and network dependent.
- Aggregate creator/keyword views do not expose a canonical source-publication
  ordering.
- ABI support remains an evidence question rather than an enforced publishing
  policy.
- Storage and exact-folder behavior remains provider dependent.
- Structured issue classification intentionally covers only stable,
  high-confidence cases.
- Some large screens and workers have extracted policy helpers but are not
  fully decomposed into independently testable components.

## Quality, security, reliability, and maintainability findings

The implementation has meaningful safeguards for command arguments, log
redaction, sharing, app-owned deletion, retry classification, migrations, and
external URL validation. The highest residual risk is integration breadth:
external extractors and web flows, native payloads, Android storage, background
limits, and Media3 all require device-level evidence that JVM tests cannot
provide.

Large framework classes and the single-module design increase regression risk.
Full-library operations can become expensive at scale. Cross-store Room and
preference changes cannot be atomic. Exported share entry points and WebView
authentication remain security-sensitive. Terminal Room query/projection
warnings and Gradle deprecations are concrete maintenance debt.

## Test assessment

Current automated tests cover argument filtering, redaction, download outcomes
and stages, failure classification, retry and queue policies, storage ownership
and deletion, runtime probes, subtitles, converters, presets, automatic
keywords, media publication dates, and representative Room migration setup.

Coverage is insufficient for end-to-end downloads, native ABI execution,
WorkManager/foreground behavior, notifications, broad upgrade matrices,
document-provider differences, share-intent fuzzing, WebView authentication,
and Media3/PiP lifecycle behavior. These require device tests, fakes at
framework boundaries, or release smoke matrices.

## Documentation reconciliation

The previous `docs/codex` baseline identified several safety and quality tasks
as future work even though the current code implements them. The task registry
and project state have been updated to distinguish implemented, partial, and
deferred work. Archived audits remain available as dated evidence but are not
current defect lists. The current implementation uses Room schema 53 rather
than the older documented schema 50 baseline.

See [Known Limitations](known-limitations.md) and
[Future Work](future-work.md) for the resulting maintenance and product
recommendations.
