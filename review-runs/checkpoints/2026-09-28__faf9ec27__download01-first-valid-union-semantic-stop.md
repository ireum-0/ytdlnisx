# 13-root local candidate — first valid union semantic failure at BUG-DOWNLOAD-01

checkpoint_kind: POST_IMPLEMENTATION_UNION_SEMANTIC_STOP
review_parent_sha: 94c5ffc76c99d9217f706236bdab6e23485a94a2
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: faf9ec273cea90ce7479bc6f62e6fa79b6dc6fad
reported_local_candidate_tree: 0bfcf70fe2f169681b3bc09eb2cc014bc2afab10
reported_local_candidate_parent: 369de7a5219f4150c3c143ce20c03dd23dce2182
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Reported candidate shape

The implementation agent reports:
- the original 13 production-root commits remain unchanged;
- one tooling-only BUG-TOOLING-01 child commit was added on top;
- candidate SHA is faf9ec273cea90ce7479bc6f62e6fa79b6dc6fad;
- candidate tree is 0bfcf70fe2f169681b3bc09eb2cc014bc2afab10;
- the tooling child changes only:
  - tools/remediation/Complete-Wave.ps1
  - tools/remediation/Invoke-Verification.ps1
  - tools/remediation/README.md
  - tools/remediation/Test-ExecutionLifetimeProvenance.ps1.

These remain local-runtime claims until publication and independent review.

## Tooling residual verification result

The implementation agent reports the BUG-TOOLING-01 correction reached:
- tooling acceptance 16/16 PASS;
- PowerShell parsing PASS for all three changed scripts;
- exact-range git diff --check PASS;
- real detached YTDLnisX JVM bootstrap:
  6 executed / 0 failures / 0 errors / 0 skips;
- source-worktree local.properties not accessed;
- generated detached local.properties empty/ignored;
- SDK discovery from host environment.

This supports the intended correction contract but does not independently close
BUG-TOOLING-01 because the tooling child is still local-only and unpublished.

BUG-TOOLING-01 remains OPEN P2 pending publication and independent review.
BUG-TOOLING-02 remains FIXED-CLOSED.

## Emulator recovery result

The same previously authorized emulator/AVD was reported as:
- AVD: Medium_Phone_API_36.1
- serial: emulator-5554
- cold-started without wipe or configuration change;
- ADB boot completion PASS;
- independent PackageManager readiness PASS before connected tests.

The prior device-health blocker is therefore not the cause of the new union
failure.

## First valid exact-final-SHA union failure

The exact-final-SHA union began on the reported exact local candidate and reached
a valid connected test execution.

First partition:
DownloadWorkerCleanupProductionWiringTest

Reported execution:
- 30 tests executed;
- 5 failures;
- infrastructure/device health was not the blocker.

First failure:
realWorkerPostProcessingUnreadableCleanupRetainsExactOwner

Observed assertion:
- expected status: PostProcessing
- observed status: Queued

The implementation agent stopped immediately as required.

No later union partition, Complete-Wave Check, or publication ran.

## Finding classification

No new finding ID is created.

The first valid semantic failure is provisionally classified under
BUG-DOWNLOAD-01 because:
- it occurs in the production-wiring test surface specifically created for the
  authoritative Download-row read / cleanup / recovery contract;
- the failing scenario is PostProcessing plus unreadable cleanup authority;
- the canonical BUG-DOWNLOAD-01 invariant explicitly requires indeterminate
  authority reads to preserve exact E1 responsibility and forbids synthetic
  ownership loss;
- the acceptance matrix explicitly includes the PostProcessing/E1 unreadable
  case and double-read-failure cleanup responsibility.

This checkpoint does NOT yet conclude that the production implementation is
wrong rather than the new assertion/harness.

The observed Queued state must be traced through the exact local candidate to
determine whether:
A. production recovery re-queues or retires E1 responsibility too early,
   violating BUG-DOWNLOAD-01;
B. Queued is a valid converged recovery state that still preserves exact E1
   responsibility, making the assertion too representation-specific;
C. the harness/fault seam triggers a different boundary than intended;
D. multiple reported failures share one semantic cause.

Count one semantic root once.

## Required next review-fix analysis

Preserve the full reported local candidate and every prior failure record.

Before editing:
1. enumerate all 5 failing methods from the first connected partition;
2. capture each expected/observed semantic object, not only status strings;
3. trace the exact local candidate through:
   - authoritative read outcome;
   - worker exact execution owner;
   - process-local owner;
   - DownloadExecutionRecovery durable carrier;
   - row status/executionId transition;
   - scheduler/admission requeue;
   - cleanupAttempt final owner release/retain decision;
4. decide whether Queued still retains exact same-execution recovery
   responsibility or represents premature normal re-admission/ownership loss;
5. compare that result against every relevant BUG-DOWNLOAD-01 acceptance cell;
6. distinguish one same-root residual from test-only representation drift.

A test expectation may be changed only with exact production proof that the
canonical invariant remains satisfied under the observed state.

Do not change tests merely to obtain green.

## Authorized correction boundary

If the 5 failures establish a BUG-DOWNLOAD-01 production residual:
- add one narrow BUG-DOWNLOAD-01 correction child commit on top of
  faf9ec273cea90ce7479bc6f62e6fa79b6dc6fad;
- preserve all preceding 14 commits exactly;
- touch only production/test surfaces required by that same root;
- rerun the complete DownloadWorkerCleanupProductionWiringTest and focused JVM
  dependencies;
- require nonzero counts and zero unexplained failures.

If exact source proves production semantics are correct and the assertion is
stale/representation-specific:
- use a separately attributable BUG-DOWNLOAD-01 test-only correction;
- assert the semantic ownership/recovery object promised by the checkpoint,
  not merely a transient status label;
- prove the old assertion was invalid using the exact producer/carrier/consumer
  path before changing it.

If the failures establish a new semantic root or unauthorized cross-root
expansion, STOP and do not edit.

After the focused partition passes, restart the exact-final-SHA union from the
first partition against the NEW exact committed candidate.

Stop again on the first valid semantic failure.

## Canonical state

Remote implementation remains:
7d6a7b7c445d9e45297032fa0521a1fc1d732eb9

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.

All 13 production roots remain OPEN.
BUG-TOOLING-01 remains OPEN pending independent review.
BUG-TOOLING-02 remains FIXED-CLOSED.

INDEPENDENT EXECUTION: NOT EXECUTED
