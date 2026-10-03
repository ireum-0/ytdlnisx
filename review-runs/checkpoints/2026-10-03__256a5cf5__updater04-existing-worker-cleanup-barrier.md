# BUG-UPDATER-04 existing worker cleanup barrier identified — test-only continuation authorized

checkpoint_kind: BUG_UPDATER04_EXISTING_WORKER_CLEANUP_BARRIER
review_parent_sha: 64329cb376a5c4fc085e24d9bce416453b63c40a
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Scope stop reviewed

The implementation agent stopped before edits because it could not prove that WorkManager cancellation or
recovery-job joining establishes completion of the real worker coroutine through final cleanup.

That stop was correct.

## Existing completion barrier found

No new production hook is required.

At implementation basis 256a5cf5, DownloadWorker.cleanupAttempt() ends by invoking:

DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting(downloadId, executionId)

This hook runs after the existing cleanupAttempt ownership/release decisions and is explicitly documented as
an exact attempt-cleanup observation point.

Existing production-wiring coverage in DownloadOutputProductionWiringTest already uses this same hook to:
- observe the exact downloadId/executionId cleanup;
- wait for either WorkInfo terminal state OR the exact cleanup observation;
- then assert post-attempt row/owner/native/recovery state.

Therefore this is an established test-only observation seam, not a new production semantic mechanism.

## Reviewer decision

EXISTING_COMPLETION_BARRIER=DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting
NEW_PRODUCTION_HOOK_AUTHORIZED=NO
PRODUCTION_SOURCE_EDIT_AUTHORIZED=NO

Authorize continuation of the previously approved test/precondition correction using the existing
afterAttemptCleanupForTesting hook as the exact worker-attempt cleanup barrier.

Before relying on it locally:
1. require the protected dirty DownloadWorker.kt still contains the same hook at the end of cleanupAttempt();
2. require no dirty BUG-UPDATER-04 production edit moved or weakened that hook relative to the cleanup
   completion boundary;
3. scope observations by exact downloadId and nonblank executionId;
4. wait finitely for the exact cleanup observation before closing Room/resetting hooks for tests that launch a
   real DownloadWorker;
5. after the observation, assert the expected row/owner/native/recovery state as appropriate to the test;
6. do not substitute WorkInfo cancellation acknowledgement, recovery-job join, or DB terminal status alone for
   the cleanup observation when worker completion matters.

## Test-only correction boundary

Continue to permit only the minimum androidTest correction needed to:
- establish startup readiness before release-count baselining;
- isolate release observation to the tested updater;
- surface native-admission failure before latch timeout masks it;
- use afterAttemptCleanupForTesting to prove exact real-worker cleanup completion;
- prevent teardown/db.close()/hook reset until exact worker cleanup is observed;
- eliminate confirmed cross-test worker/recovery contamination.

Preferred edit:
app/src/androidTest/java/com/ireum/ytdl/util/YtdlpRuntimeAuthorityProductionWiringTest.kt

No production/config/ABI packaging edit is authorized.

## Verification

The implementation agent may static-check and compile androidTest source.
It must not run the focused device class, commit, or publish.
Operator rerun still requires an ABI-compatible ARM64 Android target.

BUG-UPDATER-04 remains OPEN P2.
Runtime correctness remains NOT_VERIFIED.

INDEPENDENT_EXECUTION: NOT EXECUTED
