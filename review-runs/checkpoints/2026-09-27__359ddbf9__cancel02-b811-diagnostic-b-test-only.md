# BUG-CANCEL-02 b811 diagnostic disposition — premature/stale observation

checkpoint_kind: IMPLEMENTATION_STOP_RULE_DIAGNOSTIC_REVIEW
review_parent_sha: `7aa35234824311b0106143266b08cf0cdaab5d11`

authoritative_remote_implementation_sha:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`

preserved_local_only_candidate_sha:
`b811724047f6ddfbd7c9c534f876e7f36e5f27d8`

local_candidate_parent:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`

## Diagnostic disposition

Primary reported classification:
`B. PREMATURE_OR_STALE_TEST_OBSERVATION`

This classification is accepted as the governing stop-rule diagnostic disposition for the next
test-only correction wave, with an evidence-confidence limitation:

- the exact local candidate is still not GitHub-authoritative source;
- the local patch itself has not been independently source-reviewed;
- the diagnostic report and preserved runtime timestamps are implementation-agent/runtime evidence;
- therefore this checkpoint does not close BUG-CANCEL-02 and does not independently prove the
  local production patch correct.

The reported first-failure sequence supports B rather than a new production correction:

1. the production-wiring test waits only for WorkManager `WorkInfo.state.isFinished`;
2. the request is reported CANCELLED;
3. worker stop/finalizer cleanup logs occur later;
4. `cleanup ... converged=true` is reported after the WorkManager cancellation signal;
5. the test failure log timestamp is later still, but the DAO read timestamp itself was not captured;
6. therefore the preserved evidence does not establish a persistent Terminal row after the exact
   app-owned cleanup/convergence owner completed.

The test did not capture exact execution-record, registry/lease, journal, carrier, or cleanup state
at the DAO read, so a stronger causal claim remains unsupported.

## Root and severity

BUG-CANCEL-02 remains OPEN under the existing canonical P2 root.

No new finding ID.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

The local row-convergence failure is not counted as a separate root.

## Authorized correction boundary

No production source change is authorized from this diagnostic.

A narrow test-only correction is authorized in
`TerminalExecutionProductionWiringTest.kt` to replace WorkManager-finished-as-quiescence with an
explicit bounded app-owned cleanup/convergence boundary before asserting the later durable row
state.

The correction must:
- remain test-only;
- preserve the original race ordering and cancellation semantics;
- preserve the pre-release proof that staging remains live/protected;
- wait on the semantic cleanup/convergence object, not elapsed time;
- use a finite bound;
- not weaken or remove the row-absence assertion;
- not manufacture row deletion or mutate production durable state from the test;
- not hide a production failure behind cleanup performed by test teardown;
- preserve the original first-failure evidence separately.

A bounded wait on the exact semantic observables is acceptable when it observes production outcome
rather than causing it. Prefer the narrowest available boundary proving that the STOPPED cleanup
owner completed and the Terminal row is durably absent. If the candidate exposes exact
registry/effect-lease observability without production edits, also prove that retirement occurs only
after effect quiescence.

## Verification after test-only correction

After the test tree materially changes, rerun is authorized.

Run the original prompt's exact gates in order:
1. focused connected `TerminalExecutionProductionWiringTest`;
2. broader connected Terminal partition;
3. conditional WorkManager handoff tests only if the local production candidate changed those
   semantics;
4. focused JVM tests;
5. compileDebugKotlin;
6. compileDebugAndroidTestKotlin;
7. git diff --check.

A valid semantic failure stops later gates. Do not rerun unchanged failures merely to seek green.

The final candidate must be committed forward from `b8117240...`; do not amend or rewrite it.
Push is authorized only after all exact-final-SHA gates pass and fresh remote/review checks remain
compatible.

Closure still requires normal fast-forward push and independent exact-source review of the pushed
production candidate.

INDEPENDENT EXECUTION: NOT EXECUTED
