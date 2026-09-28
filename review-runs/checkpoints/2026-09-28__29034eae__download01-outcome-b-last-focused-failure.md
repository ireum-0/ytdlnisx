# BUG-DOWNLOAD-01 focused Outcome B residual after tooling path correction

checkpoint_kind: FOCUSED_SEMANTIC_STOP
review_parent_sha: 261633f8ce72c6c9fab72c654c5d2f6c966cf91a
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 29034eaeac30fc68b5e45c9f8f182dc06ec5131e
reported_local_candidate_tree: 1cd1a950ec3ecfd88247ed0c52621838b4ddbe9b
reported_local_candidate_parent: 77a3a177eb775d0a17fe5286aa5fd33c7cd51220
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Tooling path correction result

The implementation agent reports one tooling-only child:
29034eaeac30fc68b5e45c9f8f182dc06ec5131e

Changed paths:
- tools/remediation/Invoke-Verification.ps1
- tools/remediation/Test-ExecutionLifetimeProvenance.ps1
- tools/remediation/README.md

Reported tooling acceptance:
17/17 PASS, including the new long connected gate ID bounded-token cell.

The corrected focused verifier then reached real connected execution on the
exact local candidate, so the prior evidence-path blocker is no longer the
active execution blocker.

BUG-TOOLING-01 remains OPEN P2 pending publication and independent review, but
its current local correction is reported verification-passing.

## Focused BUG-DOWNLOAD-01 result

Exact local candidate:
29034eaeac30fc68b5e45c9f8f182dc06ec5131e

Focused class:
DownloadWorkerCleanupProductionWiringTest

Reported execution:
- 31 tests executed
- 1 failure
- 0 errors
- 0 skipped
- device health PASS
- no infrastructure signal

Failing method:
realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure

Reported failure:
- timeout waiting for the real worker's durable producer condition
- last WorkInfo state: RUNNING
- test source line reported: 1679

The implementation agent stopped at this focused semantic/lifecycle failure as
required.

No restarted exact-final-SHA union, Complete-Wave Check, or publication ran.

## Root classification

No new finding ID is created.

The failure remains provisionally within BUG-DOWNLOAD-01 Outcome B analysis.

It does not presently establish BUG-HISTORY-04:
- BUG-HISTORY-04 governs duplicate-History destructive identity proof and atomic
  relationship mutation;
- the reported scenario starts after a History commit and fails while observing
  the worker/recovery lifecycle;
- no evidence presently shows stale duplicate identity, dedupe deletion, or
  relationship-transaction corruption.

The focused test-only classification is still NOT_VERIFIED because one corrected
method remains failing.

## Required exact-source analysis

Before editing, inspect the exact local candidate and trace only:
realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure

Determine:
1. what durable History record exists after the injected finalization failure;
2. exact Download row existence/status/executionId after retry;
3. exact DownloadProducerRecovery identity and phase;
4. exact generic DownloadExecutionRecovery carrier state;
5. whether the real worker is intentionally long-lived after the durable outcome;
6. what condition the test currently waits for at/around line 1679;
7. whether that condition is a semantic requirement or merely a lifecycle/
   representation condition;
8. whether a compatible successor could be incorrectly admitted while the old
   E1 authority is still active;
9. whether any stale E1 can still mutate terminal/history/filesystem effects;
10. whether cold restart/reconcile still converges from the observed durable
    state.

If source proves History authority and exact recovery/finality semantics are
already correct while WorkInfo remains RUNNING by design, the test must assert
the durable promised outcome rather than worker termination.

If source proves the worker/recovery state can remain RUNNING while violating
exact authority or allowing stale effects, this is a production same-root
residual and must not be papered over by test changes.

## Authorized correction boundary

Preserve every existing local commit exactly.

Preferred next correction:
- one test-only BUG-DOWNLOAD-01 child commit;
- change only
  app/src/androidTest/java/com/ireum/ytdl/database/DownloadWorkerCleanupProductionWiringTest.kt;
- only if exact production proof shows the current timeout/wait condition is
  representation-specific.

If production source must change, STOP before editing and report exact proof.
Do not silently widen authority.

Do not alter tooling again merely because this focused semantic test failed.

## Focused acceptance

After the one narrow correction:
- rerun the full DownloadWorkerCleanupProductionWiringTest;
- require nonzero execution and zero failures/errors;
- preserve exact count;
- require Android-test Kotlin compile;
- require detached exact-SHA diff gate;
- preserve healthy same-AVD preflight.

Only then may BUG-DOWNLOAD-01 Outcome B advance from NOT_VERIFIED to focused
verified evidence.

It still remains OPEN until post-publication independent review.

## Full union

Only after focused verification passes:
- determine the new exact candidate SHA/tree;
- restart the full exact-final-SHA union from the first partition;
- stop at the first valid semantic failure;
- no publication until every required union gate and Complete-Wave Check pass.

## Canonical state

Remote implementation:
7d6a7b7c445d9e45297032fa0521a1fc1d732eb9

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.

BUG-DOWNLOAD-01 remains OPEN P2 / OUTCOME_B_NOT_VERIFIED_ONE_FOCUSED_FAILURE.
BUG-TOOLING-01 remains OPEN P2 pending independent review.
BUG-TOOLING-02 remains FIXED-CLOSED.

INDEPENDENT EXECUTION: NOT EXECUTED
