# BUG-RUNTIME-01 clean-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: f528798d590c4f20efcc2ca20c5507068c6871e6
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
live_completed_implementation_head: 31f55aca76efb1b956567fca5325b2575779a835
active_tooling_wave_inspected: NO

verdict: OPEN P2 / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 17
primary_lens: L1 Durability & recovery DEEP
independent_execution: NOT EXECUTED

## Existing hardening to preserve

Runtime installation is serialized inside the process by runtimeInstallLock. The current payload fast-path checks the expected revision marker plus required FFmpeg and copied dependency files with nontrivial size. These checks are useful but do not make replacement failure-safe.

## Same-root residual

When the live payload fails the fast-path, installBundledFfmpegPayload() deletes the live payloadRoot first and extracts the replacement directly into that same path. Any extraction or filesystem failure can therefore destroy a previously valid runtime and expose an empty or partial live tree.

copyRequiredBundledRuntimeDependencies() logs and swallows required dependency failures. The outer installer can then write the expected revision marker and log success even when a required copied dependency is missing.

Installer failure is not returned to callers: the top-level runCatching logs the failure and ensureRuntimeToolsInstalled() returns normally.

YoutubeDLCompat.resolveValidFfmpegLocation() accepts the runtime when native libffmpeg.so/libffprobe.so are executable and the extracted usr/lib is merely a directory. It does not prove revision or required libraries. DownloadWorker likewise adds the extracted library directory whenever it exists and can return the native wrapper executable even after ensureRuntimeToolsInstalled() failed.

## Exact invariant

A live bundled FFmpeg runtime is authoritative only when one exact revision/generation has been fully staged, all required runtime dependencies are proven present/usable, and that verified generation has been atomically published. Failed or partial installation must never replace the last verified live generation or satisfy resolver availability checks.

## Narrow implementation boundary

Keep runtimeInstallLock as the in-process serialization layer. Install each replacement into an attempt-scoped staging directory outside the live payloadRoot, materialize required dependencies there, verify the expected revision and the complete required library contract there, then publish the verified generation atomically or through an explicit rollback-safe swap.

Make required dependency copy failures authoritative rather than log-only. Write the live revision/provenance marker only as part of successful publication, not before verification.

Define one shared runtime-validation primitive used by installer fast-path, YoutubeDLCompat, and DownloadWorker so partial directories cannot be interpreted differently by different consumers. Installer/result semantics must distinguish VERIFIED_CURRENT, VERIFIED_NEW, and FAILURE/UNAVAILABLE rather than returning Unit after logged failure.

Persist enough generation/staging ownership for restart recovery to discard or finish only owned staging attempts without deleting the last verified live runtime. Keep BUG-ABI-01 separate: this root assumes the packaged ABI artifacts exist.

## Forbidden shortcuts

- deleting live payloadRoot before replacement verification
- treating revision marker alone as proof of a complete runtime
- swallowing required dependency copy failure
- accepting usr/lib merely because the directory exists
- letting different runtime consumers implement weaker validity checks
- using retries as a substitute for preserving the previous verified generation

## Acceptance matrix

- upgrade from a valid older payload revision
- failure after staging begins and during ZIP extraction
- failure during each required dependency copy
- failure before revision/provenance publication
- failure during final publish/swap
- process death at the same boundaries
- resolver rejection when usr/lib exists but a required library is missing
- same-settings retry/manual requeue/reconfigure/notification retry use only a verified generation
- startup recovery removes/finishes only owned staging attempts
- real yt-dlp FFmpeg-required path and hard-sub resolution consume the same verified generation

No focused runtime replacement regression test was found in the reviewed tree. RuntimeDiagnostics is diagnostic code, not failure-path verification.

App.kt, YoutubeDLCompat.kt, and DownloadWorker.kt are byte-identical on CLEAN basis and completed implementation 31f55aca. Active tools/remediation work was not inspected.