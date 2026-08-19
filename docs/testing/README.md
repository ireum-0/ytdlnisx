# Testing and Validation

## Automated checks

Run commands from the repository root. On Windows, use `gradlew.bat`; on
Unix-like systems, use `./gradlew`.

```powershell
.\gradlew.bat :app:compileDebugKotlin -x lint
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Use the compile task for a small Kotlin change, focused unit tests while
iterating, and the wider set before a release or broad change. The connected
test command requires a compatible running emulator or attached device. Release
builds require signing configuration and should be run only in an authorized
release context.

The project does not currently configure a ktlint or Spotless formatting task.
`git diff --check` is still useful for whitespace errors, but it is not a Kotlin
formatter.

## Current test coverage

JVM tests exercise focused logic including:

- command/argument policy and diagnostic redaction
- download outcome, issue classification, stage, retry, and queue decisions
- storage ownership and deletion decisions
- runtime probes and subtitle selection/conversion
- download presets
- automatic keyword rule normalization, matching, coverage, and scheduling
- media source-publication-date parsing, ordering, and fallback policy
- extractor-input normalization, requested/original/canonical source identity,
  and cached-source validation
- video-quality target, retry, replacement-file safety, marker, duplicate, and
  batch cancellation policies
- representative Room migration setup

Android tests cover selected migration and framework-dependent behavior. They
are not a substitute for the manual device matrix below.

## Important gaps

Automated coverage remains limited for:

- end-to-end extraction, download, native tool execution, and per-ABI health
- WorkManager foreground/cancellation/retry behavior under OS restrictions
- notification permissions and actions
- broad Room upgrade paths using real prior databases
- MediaStore, SAF, DocumentFile, and third-party provider differences
- exported share intents and hostile/malformed URIs
- Media3 playback, subtitles, queue navigation, PiP, and process recreation
- WebView login, cookies, and PoToken behavior

Tests that require live media services should use controlled accounts and
non-sensitive fixtures. Never put API keys, cookies, signing material, or
private media paths in test output or the repository.

For the 2026-08 physical-device quality investigation, an SM-A546E showed two
stored 1080p requests whose actual outputs were 640x360 H.264/AAC MP4 files.
The logs selected progressive format 18 after authenticated clients exposed no
usable higher streams. A nearby successful public fallback selected separate
video/audio formats and produced 1920x1080 H.264/Opus MKV. This evidence is the
basis for post-download probing and the bounded quality-triggered public retry;
private source URLs and authentication material are deliberately not recorded.
The final debug build was then exercised against one affected History item: the
first attempt again produced 360p, validation triggered the public retry,
formats 299+251 were merged, and the verified replacement was a 1920x1080/60
H.264/Opus Matroska file. History changed only after destination validation,
and the old MP4 was removed after the successful replacement.

## Manual validation

For affected features, test representative Android API levels and at least one
physical device when possible. Include:

- fresh install and upgrade from a supported older schema
- permission denied, revoked, and partially granted states
- app-owned path, MediaStore URI, SAF document, missing file, and inaccessible
  file
- successful download, cancellation, retryable failure, permanent failure, and
  post-processing partial success
- foreground/background transitions and process recreation
- playback with local/content URIs, subtitles, queue navigation, and PiP
- supported ABI runtime probes and a small download/post-processing smoke test

## CI and release checks

Pull requests compile the debug Kotlin source and run JVM unit tests. The main
release workflow additionally builds and signs release artifacts using CI
secrets. Workflow success does not prove device runtime, storage-provider, or
ABI compatibility.

Read [the release checklist](release-checklist.md) for release-candidate source,
upgrade, runtime, privacy, storage, and playback evidence. The more detailed
change-specific matrices in [`docs/codex/CHECKS.md`](../codex/CHECKS.md) remain
useful operational guidance.
