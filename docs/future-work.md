# Recommended Future Work

This register separates new product ideas from required fixes, maintenance, and
technical debt. Priorities are relative to the current implementation:
**immediate** protects correctness or release confidence, **near-term**
improves the next development cycles, and **long-term** is strategic.

## Required bug fixes

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Resolve terminal Room projection warnings | Room reports that terminal queries omit a model property. Removing ambiguity prevents silent default values and future Room compiler failures. | `database/dao/TerminalDao.kt`, terminal models and callers | Use an explicit projection DTO or make the selected columns and model contract match; add DAO tests. | Immediate | Low |

## Maintenance and technical debt

### Testing and quality

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Execute and extend the Room upgrade matrix | Schema exports and JVM setup do not prove upgrades on Android. Device-backed migration tests reduce data-loss risk, including 52→53. | `DBManager.kt`, `Migrations.kt`, `schemas/`, `MigrationSmokeTest` | Test representative old databases, constraints, defaults, and retained data on an emulator/device. | Immediate | Medium |
| Add regression tests for source-date propagation | The new value crosses extractors, queue models, workers, history edits, duplicate checks, and sorting. Focused tests protect against silent clearing or cross-URL metadata. | result repository, download/history models, History UI, workers | Fake providers; verify cancellation, URL identity, zero-value retention, duplicate normalization, and missing-date policies. | Immediate | Medium |
| Build device integration suites | Framework behavior dominates storage, WorkManager, notifications, sharing, and playback risk. Automated device coverage reduces manual release effort. | workers, storage utilities, manifest entry points, player | Use emulator APIs spanning scoped-storage changes; inject fakes where native/network behavior is not deterministic. | Near-term | High |
| Add a formatting gate | No ktlint or Spotless task currently enforces Kotlin formatting. A deterministic check reduces review noise. | Gradle build and Kotlin sources | Select one formatter, pin its version, baseline deliberately, and avoid mass reformatting in feature commits. | Near-term | Medium |

### Architecture and refactoring

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Decompose History orchestration | History combines queries, selection, grouping, metadata refresh, file actions, and navigation. Smaller controllers improve testability and change isolation. | `HistoryFragment.kt`, history adapters/repositories | Extract one responsibility at a time; preserve selection and lifecycle behavior; benchmark large libraries. | Near-term | High |
| Separate download phases from worker plumbing | Transfer, post-processing, persistence, notifications, and cleanup are difficult to test independently. Explicit phase boundaries improve partial-success handling. | `DownloadWorker.kt`, download policies/utilities | Keep WorkManager foreground and cancellation semantics at the boundary; model artifacts and warnings explicitly. | Near-term | High |
| Isolate player lifecycle and queue ownership | Playback behavior remains concentrated in one Activity. A clear session/controller boundary reduces PiP and URI regressions. | `VideoPlayerActivity.kt`, `PlaybackQueueState.kt` | Preserve Media3 lifecycle, saved position, subtitles, queue mutation, and media-session integration. | Near-term | High |
| Introduce testable gateways at external boundaries | Direct framework/native/provider access limits deterministic tests. Interfaces allow focused failure and cancellation testing. | extractors, runtime utilities, storage and sharing helpers | Avoid abstraction for its own sake; start where tests need controlled time, errors, URIs, or process outcomes. | Near-term | Medium |
| Modernize Gradle configuration | Deprecation warnings threaten future Gradle compatibility. Early cleanup lowers upgrade risk. | root and app Gradle files | Change syntax separately from dependency upgrades and verify every build variant. | Near-term | Medium |

### Security and privacy

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Add hostile-intent and URI tests | Exported share entry points process untrusted input. Fuzzed and malformed cases improve confidence in path, scheme, and permission checks. | manifest, `ShareActivity`, URI/share utilities | Cover oversized text, unsupported schemes, traversal-like names, revoked grants, and sensitive-file denial. | Immediate | Medium |
| Formalize diagnostic data classification | New logs or support exports can accidentally include cookies, paths, commands, or URLs. A documented policy makes redaction reusable. | redaction, runtime diagnostics, logs, backup/share paths | Define allowed fields, centralized sanitizers, retention, and tests; keep support export opt-in. | Near-term | Medium |
| Review WebView authentication boundaries | Login and PoToken screens operate on privileged cookies and external web content. Periodic review reduces script/navigation exposure. | PoToken/login WebViews and cookie utilities | Restrict navigation, JS bridges, file access, debugging, and cookie persistence; test provider changes. | Near-term | Medium |

### Performance and reliability

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Make large metadata backfills resumable | A sequential UI-launched backfill is safe but can be slow or interrupted. Checkpointed work improves completion for large libraries. | History metadata refresh, repository, WorkManager | Preserve source URL validation, rate limits, cancellation, retries, progress, and user control; avoid uncontrolled parallel requests. | Near-term | Medium |
| Benchmark and page large history views | Full-library transforms may cause latency and memory growth. Paging or indexed queries improve scalability. | history DAO/repository, History UI, group views | Measure first; preserve multi-select, grouping, missing-date policy, and deterministic ordering. | Near-term | High |
| Define and test an ABI support policy | Generated APKs may not have equivalent native runtime behavior. A policy prevents publishing artifacts without evidence. | ABI Gradle config, assets/native libraries, runtime diagnostics | Maintain per-ABI smoke tests for yt-dlp, aria2c, ffmpeg/ffprobe, Python, QuickJS, and subtitle tooling. | Immediate | High |
| Strengthen process cancellation tests | Blocking native operations may outlive coroutine cancellation. Tests and process ownership reduce orphan work and stale files. | yt-dlp/runtime process wrappers, workers, cleanup | Verify signals, timeouts, child processes, WorkManager stop, retry, and artifact ownership. | Near-term | High |

### Observability and operations

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Add an opt-in redacted diagnostic bundle | Runtime probes exist, but support triage still requires assembling context. A safe bundle improves issue resolution. | runtime diagnostics, logs, settings/export UI | Exclude cookies, tokens, full URLs, commands, and private paths; preview contents and require explicit user action. | Near-term | Medium |
| Track release smoke evidence | ABI, upgrade, storage, and playback claims need repeatable evidence. A release record makes gaps visible. | release workflow and checklist | Store versions and pass/fail metadata, not user data; keep manual device evidence linked to a release. | Near-term | Low |

### Developer experience and documentation

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Keep generated architecture inventory current | The broad feature surface makes manual documentation drift likely. A lightweight inventory catches stale schemas, workers, and exported components. | docs, manifest, Room database, WorkManager classes | Generate only stable facts and review prose manually; do not expose local/signing configuration. | Near-term | Medium |
| Automate Markdown link checks | Documentation was reorganized and now has a central index. Link validation prevents navigation decay. | `docs/` and CI | Support relative anchors and intentional external links; run without rewriting files. | Near-term | Low |
| Document manual provider/device matrices | Contributors need reproducible storage and playback scenarios. A matrix improves bug reports and release consistency. | testing docs | Cover API level, OEM/provider, URI type, media type, notification permission, and background restriction. | Near-term | Low |

## New product and user-experience ideas

| Recommendation | Need and expected value | Relevant code | Technical considerations | Priority | Complexity |
|---|---|---|---|---|---|
| Show publication-date provenance and refresh state | Users cannot always tell whether a date came from yt-dlp, NewPipe, YouTube API, fallback, or download time. Provenance makes sorting and edits understandable. | metadata models, extractors, History details | Requires a schema change and migration; distinguish unknown from fallback and preserve source identity. | Near-term | Medium |
| Add background backfill controls | Large libraries benefit from pause/resume, network constraints, and progress outside the History screen. | History UI and WorkManager | Build on resumable backfill maintenance; include rate limits, cancellation, and battery/network choices. | Near-term | Medium |
| Improve aggregate date semantics | Creator and keyword groups cannot currently sort by a single media publication time. User-selected newest/oldest/member-summary semantics could make aggregate views more useful. | creator/keyword DAOs and group UIs | Define semantics before implementation; avoid misleading fallback dates and expensive unindexed aggregation. | Long-term | Medium |
| Add accessible first-run capability guidance | Storage, notifications, exact alarms, battery restrictions, and cookies are complex. Contextual guidance can reduce setup failures. | onboarding/settings/permission flows | Ask only when a feature needs the capability; explain privacy and OS-specific fallbacks. | Near-term | Medium |
| Add advanced history filters only from measured demand | The current History surface is already broad. Targeted filters can help power users without further overloading the screen. | History query/UI | Gather usage and issue evidence; prefer composable filters and saved views over one-off controls. | Long-term | High |
| Expand preset portability deliberately | Presets are local SharedPreferences state. Explicit export/import or synchronization could help multi-device users. | preset model, backup/restore | Version the format, validate imported paths/options, redact credentials, and define conflict precedence. | Long-term | Medium |

Ideas in this final section are not implemented and should not be described as
current features. Maintenance tables above should generally be addressed before
large product expansion.
