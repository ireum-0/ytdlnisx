# Known Limitations and Technical Debt

This document describes limitations visible in the current implementation. It
does not treat archived audit findings or planned functionality as current
facts.

## Product and platform limitations

- Download and metadata behavior depends on external sites, yt-dlp, NewPipe,
  network conditions, cookies, optional YouTube API configuration, and bundled
  runtime compatibility. Upstream changes can break extraction without an app
  update.
- Android and OEM background limits can delay WorkManager jobs, scheduled
  downloads, observe-source checks, and automatic keyword synchronization.
- Exact file and folder operations vary by Android version and document
  provider. A path that exists may still be inaccessible through the available
  URI or storage permission.
- Source-media publication dates are only available when the extractor returns
  a trustworthy value. Existing history can be backfilled explicitly, but the
  operation is network-dependent and deliberately sequential.
- Quality verification depends on Android media metadata and the formats a live
  extractor exposes. Provider changes, unavailable/deleted sources, inaccessible
  storage, and stale or incomplete format lists can make an item ineligible.
  "Best" downloads without a durable numeric expectation cannot always be
  classified after the fact. Source verification is deferred until the user
  selects likely candidates, so the initial review list is intentionally
  provisional. Provider-specific failures can therefore still
  require field testing even though degraded normal downloads are now surfaced
  and verified replacements are fail-safe.
- Creator and keyword aggregate views do not have a single well-defined source
  publication date. They continue to use their aggregate-specific ordering
  semantics.
- Settings backup is application-managed because Android platform backup is
  disabled. Backup content can contain private history and configuration and
  must be handled as sensitive data.
- Localization breadth is substantial, but new or changed text still requires
  manual translation review across supported locales.

## Architecture and maintainability

- `HistoryFragment`, `VideoPlayerActivity`, `DownloadWorker`, and several
  download/storage utilities are large and combine multiple responsibilities.
  This raises change risk and makes isolated testing difficult.
- The app is a single Gradle module with explicit object construction and no
  dependency-injection boundary. This keeps setup simple but couples framework,
  storage, extractor, and UI concerns.
- Room and SharedPreferences both carry important state. Cross-store operations
  are not transactional and must tolerate process death between writes.
- Playback queue state has a dedicated model, but playback lifecycle,
  navigation, subtitles, PiP, and UI behavior remain concentrated in the player
  Activity.
- Some native and network calls are blocking. Coroutine cancellation is
  preserved at reviewed call boundaries, but an in-flight native operation may
  not stop immediately.

## Reliability and performance

- Large libraries can make full-history queries, regrouping, filtering, and
  explicit metadata backfills expensive. The UI uses background work in key
  paths, but the data volume is not bounded.
- A media file can be valid even when a later subtitle, thumbnail, move,
  history, or notification step fails. Callers must preserve partial-success
  information instead of treating every post-processing issue as a total
  download failure.
- Runtime health differs by ABI and device. Gradle output alone does not prove
  that yt-dlp, aria2c, ffmpeg, ffprobe, Python, QuickJS, and subtitle tooling all
  execute on every packaged ABI.
- External error messages are not stable APIs. Only high-confidence failures
  should be mapped to structured user actions.

## Security and privacy

- Exported share and launcher entry points intentionally accept external
  intents. Their input validation and URI handling remain security-sensitive.
- The app requests broad capabilities needed for downloads and scheduling,
  including network, media access, notifications, exact alarms, and foreground
  services. Effective behavior varies by OS version and user-granted access.
- Cookies, API keys, history, paths, terminal commands, and diagnostic output
  may be sensitive. Redaction is implemented in important logging and sharing
  paths, but new diagnostics must opt into the same policy.
- WebView-based login and PoToken flows depend on web content and cookie state;
  they should be treated as privileged integration surfaces.

## Test and build gaps

- JVM tests cover focused policies, parsers, queue state, redaction, storage
  decisions, retries, presets, keywords, and migration setup, but they do not
  cover the complete end-to-end feature surface.
- Device coverage is limited. Storage providers, Room upgrade paths, background
  execution, notifications, share intents, Media3/PiP, and native runtime
  execution need emulator or physical-device validation.
- There is no configured ktlint or Spotless formatting gate. Formatting
  consistency relies on IDE conventions and review.
- Gradle emits deprecation debt that should be resolved before future Gradle
  compatibility removes the affected behavior.
- Room compilation currently reports projection/query warnings around terminal
  records; these should be resolved or explicitly documented with a projection
  type.

See [Future Work](future-work.md) for prioritized responses to these limitations.
