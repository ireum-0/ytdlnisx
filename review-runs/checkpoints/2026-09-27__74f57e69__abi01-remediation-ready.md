# BUG-ABI-01 clean-basis remediation-ready refinement

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 55bfb817666ca4afad76a967115065ca49c4a550
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L5 Platform contract closure DEEP
supporting_lenses:
- L1 Durability & recovery
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing behavior to preserve

The app-owned FFmpeg runtime is already treated as the intended execution authority. `YoutubeDLCompat.resolveValidFfmpegLocation()` is conservative: it returns a location only when both native FFmpeg/ffprobe executables and the extracted payload library directory are present.

BUG-RUNTIME-01 separately owns atomic staging/verification of a packaged runtime generation on an ABI where the required package actually exists. That root must remain separate.

## Exact defect

Release APK configuration explicitly publishes `x86`, `x86_64`, `armeabi-v7a`, and `arm64-v8a`, plus a universal APK.

The checked-in app-owned FFmpeg/ffprobe executables and bundled payload are only complete for arm64-v8a. Non-arm64 release outputs therefore advertise support that cannot satisfy the production FFmpeg runtime contract.

The hard-sub resolver also contains a second fail-open edge: after filtering for a usable `libffmpeg.so`, it falls back to `executableCandidates.first()` even when that path is not usable. The resulting `FfmpegRuntime` can name a nonexistent/non-executable file.

## Exact invariant

Every ABI that the release build publishes as supported must contain and verify the complete runtime required by every production feature advertised on that ABI.

Equivalently:

published ABI
-> packaged FFmpeg + ffprobe executables
-> packaged required payload libraries
-> successful runtime validation
-> only then FFmpeg-required execution.

An unavailable runtime is a typed unavailable state. It is never represented by a guessed executable path, and FFmpeg-required/hard-sub work cannot report semantic success after that state.

## Narrow implementation boundary

Given the current checked-in artifacts, the smallest safe release correction is to make the supported production ABI set match reality:

- restrict release ABI outputs to `arm64-v8a`;
- do not publish a "universal" APK that implies/runtime-exposes unsupported ABIs. If a universal artifact remains for a distribution reason, its packaged ABI set must still contain only supported runtime ABIs and its release naming/metadata must not imply the missing architectures.

If the project instead chooses to retain another ABI, closure requires adding the complete executable + payload runtime for that ABI and verifying it through the same production runtime validator. Do not keep the current split merely because unrelated native dependencies happen to build for that ABI.

Independently harden runtime consumption:
- make `DownloadWorker.resolveFfmpegRuntime()` return a nullable/typed unavailable result when no usable executable exists; remove `?: executableCandidates.first()`;
- have hard-sub/merge/probe callers fail explicitly before publication when FFmpeg is required and runtime is unavailable;
- use one shared runtime-validation result for yt-dlp FFmpeg location and hard-sub rather than two inconsistent notions of availability where practical.

For ordinary yt-dlp operations that provably do not require FFmpeg, runtime absence may remain non-fatal. Do not silently downgrade a requested hard-sub or known FFmpeg-required post-processing operation.

Keep BUG-RUNTIME-01 responsible for replacement atomicity and dependency verification of an otherwise packaged supported ABI.

## Forbidden shortcuts

- leaving non-arm64 release splits enabled and documenting them as "best effort"
- relying on startup log output when the packaged runtime is absent
- keeping the hard-sub fallback to a nonexistent `libffmpeg.so`
- treating yt-dlp omission of `--ffmpeg-location` as proof that an FFmpeg-required operation is safe
- disabling hard-sub silently and publishing unburned media as success
- bundling only an ffmpeg executable without ffprobe/required payload libraries
- counting emulator/debug support as proof of release ABI support
- merging this availability root into BUG-RUNTIME-01 staging/upgrade atomicity

## Acceptance matrix

- assembled arm64-v8a release contains executable ffmpeg + ffprobe and all required payload libraries
- no published non-arm64 split exists unless that exact artifact passes the same runtime verification
- release universal artifact, if any, contains only supported ABI/runtime combinations
- fresh install on every published ABI passes shared runtime validation
- missing/corrupt executable returns explicit unavailable, never a guessed path
- missing payload library returns explicit unavailable
- hard-sub request with unavailable runtime fails before final publication and cannot report burned success
- split-stream/FFmpeg-required merge with unavailable runtime fails rather than silently publishing an invalid semantic result
- genuinely FFmpeg-independent download remains functional when the runtime is not needed
- same-settings retry/manual requeue/reconfigure/notification retry cannot convert a permanently absent packaged runtime into transient success
- restart does not change unavailable into available without a valid packaged/install generation
- BUG-RUNTIME-01 staged upgrade tests still apply on supported arm64 runtime
- artifact-level verification inspects each generated release APK/AAB ABI payload and a production DownloadWorker test exercises FFmpeg-required merge + hard-sub on every published ABI

## Test gap

Source/JVM checks alone cannot close this root. Closure requires inspecting assembled release artifacts and executing the real runtime path for each published ABI.

At the CLEAN basis the build still publishes four ABI splits plus universal while the complete app-owned FFmpeg runtime is arm64-only, and hard-sub still has an unusable fallback path.

This is the same BUG-ABI-01 root; no new finding ID or count change.
