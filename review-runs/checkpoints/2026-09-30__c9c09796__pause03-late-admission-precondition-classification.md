# c9c09796 union stop — BUG-PAUSE-03 acceptance precondition blocked by recovery ordering

checkpoint_kind: COMPLETED_IMPLEMENTATION_RESULT_REVIEWER_CLASSIFICATION
review_parent_sha: 54f66cdfb62bb6ef1784cba461fc22ab015a547a
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: c9c09796d1cb08d04763710194665ad6119f86bb
reported_local_candidate_parent: 6b0825ff8c647f3b6f07bef6441d129095075c0b
reported_local_candidate_tree: 3225b80a59a9ad67a1696811d9fcffacaee7ce56
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
production_p2: 13
tooling_p2: 1
independent_execution: NOT_EXECUTED

## Reported completed work retained as implementation-agent evidence

The protected local forward chain is reported as:
- bce5a5a514715dc2dc9307a3eb84f8da646253eb
- 6b0825ff8c647f3b6f07bef6441d129095075c0b
- c9c09796d1cb08d04763710194665ad6119f86bb

Reported verification:
- tooling acceptance 40/40 PASS;
- exact compile and real Gradle launch-sidecar proof PASS;
- Cookie focused verification 7/7 PASS;
- Cookie exact compile and detached exact-SHA diff PASS;
- restarted full union reached 189 executed tests: 188 PASS / 1 FAIL;
- the failing gate was FindingAProductionWiringTest;
- nine later gates and Complete-Wave did not run;
- no publication occurred.

The local commits are not present on the authoritative implementation ref, so
their exact source mechanics remain implementation-agent evidence until
publication. This checkpoint does not close BUG-TOOLING-01 or BUG-COOKIE-03.

## Failing acceptance

Reported failing method:
pauseAllLeavesLateProductionAdmissionRunningOutsideItsTargetSnapshot

The method was intended to exercise the canonical BUG-PAUSE-03 acceptance:
- A is in the Pause-All snapshot;
- B is outside that snapshot;
- B is admitted after the snapshot and claims a fresh execution E2;
- Pause All must not stop B.

The observed failure did not establish that precondition. The reported late
worker started, but no durable proof showed that B reached a fresh Active/E2
claim before the assertion timed out.

Classification:
BUG-PAUSE-03 VERIFICATION PRECONDITION FAILURE /
RECOVERY-ORDERING HARNESS BLOCK.

This is not a new production finding and is not yet proof of a same-root
production residual.

## GitHub source-semantic basis

The canonical BUG-PAUSE-03 remediation-ready checkpoint requires the late
sibling to claim a fresh execution after the Pause-All target snapshot and
before the operation finishes. Its test requirement explicitly calls for the
real Room + Download admission + WorkManager cancellation boundary.

Authoritative implementation source at 7d6a7b7c shows that worker startup
admission performs DownloadExecutionRecovery.reconcile(...) before observing
the queued flow in observeQueuedDownloadsAfterRecovery(...).

DownloadExecutionRecovery.reconcile(...) discovers running/recovery candidates
and then acquires their per-Download execution side-effect leases before
candidate mutation.

Therefore a test that deliberately holds A's per-Download lease while starting
a late real DownloadWorker can block that worker in startup recovery before it
ever observes or claims B. That ordering is compatible with the production
recovery protocol and does not itself show that Pause All revoked B.

The existing production admission primitives are already explicit and
production-shared:
- admitQueuedDownloadsThroughProductionPath(...)
- claimDownloadThroughProductionAdmission(...)

The latter publishes a fresh execution token only after the real Room claim CAS
and DownloadWorkerExecutionOwners publication.

## What is and is not established

Established:
- the attempted test did not durably prove B reached Active/E2;
- worker startup recovery can precede queue observation and can wait on an
  unrelated recovery candidate's held per-Download lease;
- the canonical BUG-PAUSE-03 acceptance condition was therefore not reached by
  the failed run.

Not established:
- that Pause All stopped a successfully admitted B;
- that recovery/lease ordering is itself incorrect;
- that a new recovery/admission production root exists;
- that BUG-PAUSE-03 is fixed or closed on the unpublished candidate.

BUG-PAUSE-03 remains OPEN P2 pending a production-faithful focused proof.

## Reviewer-authorized narrow verification correction

Authorize exactly one forward verification child on
c9c09796d1cb08d04763710194665ad6119f86bb.

Purpose:
make the existing BUG-PAUSE-03 late-admission acceptance deterministic without
changing Pause-All, recovery, lease, claim, scheduler, or WorkManager semantics.

Allowed scope:
- app/src/androidTest/java/com/ireum/ytdl/database/FindingAProductionWiringTest.kt
- app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt only
  for one inert internal test observation hook immediately after the exact
  Pause-All Active/PostProcessing snapshot is materialized and before any
  snapshotted target acquires its per-Download side-effect lease.

The hook must:
- default to null/no-op;
- expose no production behavior change when unset;
- perform no Room, WorkManager, recovery, scheduler, or execution mutation;
- be reset in test setup/teardown;
- exist only to latch the already-existing production boundary.

Preferred focused interleaving:
1. create A as a genuinely live exact execution and establish its normal
   process-local owner state so startup recovery recognizes A as live;
2. start pauseAllDownloads();
3. latch immediately after Pause All has materialized its exact target snapshot,
   before target processing/lease acquisition;
4. only then make B eligible and start the real production WorkManager worker
   path for B;
5. require durable proof before releasing Pause All:
   - B row is Active;
   - B executionId is nonblank and differs from any prior generation;
   - DownloadWorkerExecutionOwners owns that exact B execution;
   - the WorkManager carrier used for the late worker has actually reached the
     production admission/claim path rather than merely being enqueued;
6. release Pause All and let the actual operation finish;
7. prove B remains outside this Pause-All operation:
   - same Active/E2 identity;
   - exact owner remains valid;
   - no USER_PAUSE recovery carrier belongs to B;
   - B's WorkManager carrier was not revoked by Pause All;
8. prove A alone converges through the operation's intended USER_PAUSE path.

Do not hold A's per-Download lease before the late worker has crossed its
recovery/admission boundary; that recreates the invalid precondition.

Do not bypass or stub:
- DownloadExecutionRecovery.reconcile;
- admitQueuedDownloadsThroughProductionPath;
- claimDownloadThroughProductionAdmission;
- the real WorkManager worker carrier;
- Pause All's real production path.

Do not weaken the late-running assertion or replace it with a queued/not-cancelled
assertion. The test must prove a genuinely claimed late execution.

## Verification after the narrow correction

Required:
- exact diff inspection and git diff --check;
- exact candidate compile gate;
- the modified late-admission method PASS;
- the complete FindingAProductionWiringTest class PASS with nonzero execution
  and zero unexplained failures/errors;
- preserve valid verifier evidence bound to exact SHA/tree.

If the focused class passes, restart the full exact-final-SHA union from
partition 1.

If the same method still fails after durable proof that B reached Active/E2,
STOP at first failure and return the exact B row/execution/owner/WorkInfo state.
At that point the failure can be classified against BUG-PAUSE-03 semantics.

If B still cannot reach Active/E2 because production recovery/admission blocks
it despite the post-snapshot latch and no held A lease, STOP without a second
edit and return durable state; that may establish a separately scoped
recovery/admission question.

No production semantic fix is authorized by this checkpoint.

## Canonical state

BUG-PAUSE-03 remains OPEN P2 / REMEDIATION VERIFICATION INCOMPLETE.
No new root is created.
All canonical production/tooling counts remain unchanged.
No repository-wide cleanliness claim is supported.
