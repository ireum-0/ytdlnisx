# BUG-UPDATER-04 producer-recovery observation/drain correction authorized

checkpoint_kind: BUG_UPDATER04_PRODUCER_RECOVERY_TEST_CORRECTION_AUTHORIZATION
review_parent_sha: d87285541a20e0e165cbecb139e599fcee12f5e9
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Corrected ARM64 triage accepted

The read-only triage of the corrected ARM64 focused run is accepted.

Accepted findings:
- the first test failed at the assertion requiring producer-recovery admission blocking to be absent;
- exact DownloadWorker attempt cleanup had already been observed;
- the Download row was Error;
- execution/process owners were absent;
- native debt was cleared;
- exact attempt cleanup does not itself prove that the separate DownloadProducerRecovery state machine has
  converged;
- the other six tests never reached their bodies and were cascade-blocked by retained teardown isolation state;
- no BUG-UPDATER-04 production residual is conclusively established.

The exact producer record/phase at the failed assertion and whole-worker/recovery completion remain
NOT_VERIFIED from the prior run.

## Existing production/test-visible recovery seams

No new production observation hook is required.

Existing implementation basis provides:
- DownloadProducerRecovery.discover(context): durable producer record/phase observation;
- DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId): exact admission-blocking predicate;
- DownloadExecutionRecovery.reconcile(context, dbManager): the production reconciliation path that consumes
  non-live producer recovery debt and preserves COMPLETE producer finality;
- DownloadExecutionRecovery.isRecoveryJobActiveForTesting(downloadId): test visibility of deferred recovery
  ownership;
- DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting: exact attempt-cleanup observation.

DownloadExecutionRecovery.reconcile() explicitly processes DownloadProducerRecovery records. It retires
unpublished PREPARED/RUNNING/OUTPUT_UNPROVEN debt only after exact native quiescence, preserves COMPLETE
finality as non-blocking adoption authority, and schedules deferred per-Download recovery when convergence
cannot complete immediately.

## Reviewer decision

Authorize one bounded ANDROIDTEST-ONLY correction wave.

PRODUCTION_SOURCE_EDIT_AUTHORIZED=NO
NEW_PRODUCTION_HOOK_AUTHORIZED=NO
GRADLE_OR_ABI_CONFIG_EDIT_AUTHORIZED=NO

Primary allowed file:
app/src/androidTest/java/com/ireum/ytdl/util/YtdlpRuntimeAuthorityProductionWiringTest.kt

The correction must:

1. Preserve the existing exact afterAttemptCleanupForTesting barrier.
2. After exact cleanup observation for a real worker, inspect DownloadProducerRecovery.discover(context) and
   identify records for the exact downloadId/executionId.
3. Preserve the exact observed producer phase(s) in diagnostics before any recovery drain.
4. If exact producer state remains admission-blocking, invoke the existing production
   DownloadExecutionRecovery.reconcile(context, dbManager) path; do not manufacture retirement or call
   DownloadProducerRecovery.retire* directly from the test merely to green the assertion.
5. Use a finite bounded wait for the exact downloadId to reach a non-blocking producer state:
   - DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId) == false;
   - no exact live execution/process/native owner remains;
   - DownloadExecutionRecovery.isRecoveryJobActiveForTesting(downloadId) == false when deferred recovery was
     scheduled.
6. Treat COMPLETE producer finality as a valid non-blocking state; do not require every producer record to be
   absent or FINALIZED.
7. If convergence does not occur, fail with the exact producer record phase/generation/execution diagnostics.
   Do not clear/cancel recovery state to make the test pass.
8. Only after exact attempt cleanup plus producer non-blocking convergence may the harness clear shared
   isolation state, close Room, and allow the next test to start.
9. The six previously blocked tests must not be counted as independent semantic failures; rerun eligibility
   comes only after this harness correction compiles/static-verifies.

Do not use cancelAllRecoveryJobsForTesting/cancelRecoveryJobForTesting as proof of successful recovery drain.
Cancellation/join is cleanup of a test-created retry owner, not semantic convergence.

## Verification boundary

Implementation agent may:
- edit only the authorized androidTest file;
- inspect existing production APIs;
- compile the complete debug androidTest Kotlin source;
- run git diff --check;
- report exact hashes/diff.

Implementation agent must NOT:
- run device/instrumentation tests;
- edit production/config/dependencies;
- commit or publish.

After compile/static PASS, one operator rerun on the same ABI-compatible ARM64 target may be separately
authorized.

BUG-UPDATER-04 remains OPEN P2.
Runtime correctness remains NOT_VERIFIED.
Canonical counts remain 0/0/4.

INDEPENDENT_EXECUTION: NOT EXECUTED
