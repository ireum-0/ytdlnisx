# b4df1900 DownloadOutput verified-quality diagnostic — pre-claim cancellation classified

checkpoint_kind: REVIEWER_DIAGNOSTIC_CLASSIFICATION_AND_CONTINUATION_AUTHORIZATION
review_parent_sha: bf9ac3b70337253ee805eaaed36e28c17497d60c
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: b4df19003705083c75df25c6c48931dbda8cdb4e
reported_local_candidate_parent: 9aa6d972eafd885046cc6b2643f7f3c053c2527e
reported_local_candidate_tree: d6d45d6d0e4971748c94a98b7bb7fa60ce894d70
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Evidence authority

The b4df1900 child and its focused runtime result are implementation-agent evidence from the
protected local worktree. The child is not published on GitHub, so its exact diff is not treated
as independently verified source.

The implementation agent reported:
- exactly one forward child on 9aa6d972;
- only DownloadOutputProductionWiringTest.kt changed;
- focused execution: 1 executed, 0 PASS, 1 FAIL, zero skips/assumptions/errors;
- verifier finalization PASS;
- WorkInfo reached CANCELLED;
- the Download row remained Queued with blank executionId;
- yt-dlp success hook was not reached;
- video-quality probing was not reached;
- exact attempt cleanup observation was not reached.

These facts do not reproduce the prior >60s pre-cleanup timeout and do not exercise the verified
quality-rejection path.

## Independent source-semantic classification

Exact GitHub source at remote implementation 7d6a7b7c shows that DownloadWorker may cancel its
own WorkRequest with WorkManager.cancelWorkById(this@DownloadWorker.id) after queue admission when
no execution has been admitted and one of the worker stop conditions holds.

The same exact source shows that candidate claim can be rejected before execution publication by
existing recovery, producer-finality, primary-success, process/native ownership, capacity, or
cancellation fences.

The target production-wiring test creates a Queued row with blank executionId and exercises the
real WorkManager DownloadWorker. Its fixture scopes cache_downloads but does not independently pin
the unrelated global use_scheduler or concurrent_downloads preferences.

Therefore the focused b4df1900 result is classified as:

TEST_HARNESS_PRECLAIM_ADMISSION_PRECONDITION_NOT_ESTABLISHED

The observed CANCELLED state is compatible with a worker self-cancel after a failed/no-op
admission, but the exact cancellation branch and exact pre-claim blocker remain NOT_VERIFIED.

This result does not establish a BUG-DOWNLOAD-01 production residual, does not establish a new
production root, and does not close or weaken the original verified-quality contract.

The original full-class timeout root mapping remains NOT_VERIFIED.

## Authorized continuation

Authorize exactly one forward TEST-ONLY continuation child on exact local parent
b4df19003705083c75df25c6c48931dbda8cdb4e.

Allowed source file only:
- app/src/androidTest/java/com/ireum/ytdl/database/DownloadOutputProductionWiringTest.kt

Production source changes remain forbidden.

The continuation may:

1. establish deterministic unrelated test preconditions for the target method by scoping and
   restoring:
   - use_scheduler=false;
   - concurrent_downloads=1;
2. preserve cache_downloads=false and every existing verified-quality semantic assertion;
3. capture pre-enqueue and terminal WorkInfo state needed to classify claim admission, including
   state transitions, runAttemptCount, and stopReason when the current WorkManager API exposes it;
4. capture the target row before enqueue and at terminal/timeout, including status, executionId,
   operationId, issue code/stage, and whether the exact target appears in the production queue
   query used by DownloadWorker;
5. capture the exact pre-claim fences that can reject publication of a new execution, including:
   - DownloadExecutionRecovery pending/responsibility state;
   - DownloadProducerRecovery admission-blocking state;
   - committed primary-success authority for this Download;
   - process-owner claimability;
   - registered/native-process or marker debt for this Download;
   - low-quality cancellation state;
6. retain the existing ytdlp-success, video-quality-probe, cleanup, History, and file-state
   observations from the current diagnostic child.

Do not:
- change production code;
- lengthen the existing semantic timeout merely to seek green;
- bypass WorkManager or call DownloadWorker internals directly;
- weaken the ambient-vs-authoritative staged-output assertions;
- delete first-failure evidence;
- run the full class, detached diff, 19-gate union, Complete-Wave, or publication.

## Required focused outcome

Run only:
realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement

Require nonzero execution.

If the worker reaches the yt-dlp/quality path, preserve the exact latest reached boundary and stop
after the focused result for reviewer classification.

If it again terminates before claim, report the exact queue/admission fences and WorkInfo
transition/stop reason and stop.

If deterministic fixture preconditions expose a single stale test-only assumption with production
semantics intact, do not make a second correction in the same task.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2.
No new production root is created.
Canonical counts remain unchanged.
No root is FIXED-CLOSED.
Repository-wide CLEAN remains unsupported.
