# 9aa6d972 DownloadOutput verified-quality timeout — test-only bounded diagnostic authorized

checkpoint_kind: REVIEWER_DIAGNOSTIC_AUTHORIZATION
review_parent_sha: ad5807a773b7286d03b56888e7cb3c2b6980c944
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 9aa6d972eafd885046cc6b2643f7f3c053c2527e
reported_local_candidate_parent: c5df12ad37dc54aacdd6ba13262db23ac6f56beb
reported_local_candidate_tree: bad5a70d1887d18d90c4c484a39292cccad03123
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Evidence exhausted

The first valid full DownloadOutputProductionWiringTest execution after device recovery ran
21 tests, with one failure:

- realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement
- java.lang.IllegalStateException: Timed out waiting for real DownloadWorker
- request id 2ac329bb-d40c-4c26-b2c7-d5276519b607

The 9aa6d972 helper waits for either:
1. WorkInfo terminal state; or
2. the matching exact afterAttemptCleanupForTesting observation.

Neither condition was observed within the existing bounded wait, so
captureAndAssertPostAttempt() was never reached for the failing attempt.

Per-test logcat artifacts exist by filename but contain no payload for both the
negative and positive verified-quality methods. Existing runtime artifacts therefore do not
show whether the failing attempt reached:
- synthetic yt-dlp success;
- staged video quality probing;
- quality rejection handling;
- resetYtdlpOutputDirectory;
- failure terminal persistence;
- or exact attempt cleanup.

No unchanged rerun is authorized merely to seek a green result.

## Source-semantic narrowing

The failing method uses the test helper's default URL:
https://example.com/<downloadId>

It is therefore not a YouTube media route.

The method constructs a verified quality-replacement marker targeting 720p and supplies only a
360p authoritative staged output. The production DownloadQualityFallbackPolicy for a
non-YouTube verified replacement maps this mismatch to REJECT_REPLACEMENT, not to public/auth
retry or selection-probe routing.

The worker then handles CompletedYtdlpQualityOutcome.Reject by resetting the current output
directory and throwing YtdlpQualityRejectedException. The observed >60s pre-cleanup lifetime is
therefore not explained by the intended bounded YouTube quality retry policy.

This narrows the unresolved location to the real worker path at or before the quality-rejection
transition, but does not yet prove a production defect or identify a safe production correction.

## Authorized diagnostic child

Authorize exactly one forward test-only diagnostic child on exact parent
9aa6d972eafd885046cc6b2643f7f3c053c2527e.

Allowed source file:
- app/src/androidTest/java/com/ireum/ytdl/database/DownloadOutputProductionWiringTest.kt

Production source changes are NOT authorized.

The diagnostic child may use only existing test seams and read-only production state to record,
for the failing method only:

- whether ytdlpSuccessWithOutputDirectoryForTesting was entered/exited;
- whether videoQualityProbeForTesting was entered/exited;
- the exact probe input paths;
- whether afterAttemptCleanupForTesting fired and its executionId;
- WorkInfo state at timeout;
- Download row status/executionId/operationId at timeout;
- DownloadWorkerExecutionOwners owner;
- DownloadWorkerProcessOwners owner;
- generic DownloadExecutionRecovery pending/disposition/phase;
- DownloadProducerRecovery discovery/records/blocking state;
- native-process registry and marker debt when an executionId is known;
- History replacement row/path;
- oldMedia, ambientHighQuality and stagedLowQuality existence/path;
- any existing issue code/stage fields already on the Download row.

Requirements:
- do not change production timing, authority, retry, cleanup, ownership, recovery, Room,
  WorkManager, native, notification or file-publication semantics;
- do not lengthen the existing timeout to convert the failure into a pass;
- do not replace the real WorkManager DownloadWorker;
- do not weaken or delete the method's semantic assertions;
- diagnostic state may be included in the timeout exception and/or deterministic test log;
- keep the diagnostic bounded to this one failing method and the shared helper only as necessary;
- preserve all prior evidence.

## Verification

Run only the exact failing method first using the established exact-candidate focused-method
verification mechanism already used for the prior g15 method-level run.

Require:
- exact new child SHA/parent/tree recorded;
- nonzero execution;
- preserved original semantic method;
- captured diagnostic facts sufficient to locate the latest reached boundary.

Do NOT run the full class, detached diff, or 19-gate union until reviewer classification of the
focused diagnostic result.

## Decision after focused diagnostic

- If ytdlp success and quality probe both complete, but the attempt stalls during rejection/reset
  or later exact authority/cleanup work, classify the concrete mechanism against BUG-DOWNLOAD-01
  and the existing ownership/recovery invariants before authorizing any production correction.
- If quality probe is never entered, trace only the bounded producer/output-provenance path leading
  to the probe.
- If the diagnostic proves a test-only stale assumption/seam defect with production semantics
  intact, derive one narrow test correction without weakening coverage.
- If the mechanism remains ambiguous or maps to a distinct root, stop without production change.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2.
No new root is created.
Canonical counts remain unchanged.
No root is FIXED-CLOSED.
Repository-wide CLEAN remains unsupported.
