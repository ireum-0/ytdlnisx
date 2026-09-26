# BUG-CANCEL-02 ae27 diagnostic — production residual confirmed

checkpoint_kind: IMPLEMENTATION_STOP_RULE_DIAGNOSTIC_RESOLUTION
review_parent_sha: `d69cf8815704ed58a416fa3a07d6dc12738f1815`

authoritative_remote_implementation_sha:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`

preserved_local_chain:
`359ddbf9bf534009be095ad1bffea8ec45c899e4 -> b811724047f6ddfbd7c9c534f876e7f36e5f27d8 -> ae27b93339bf83fdaddb738b30f5fc8d51c3cd7e`

diagnostic_prompt:
`ytdlnisx/prompts/2026-09-27_BUG_CANCEL_02_AE27_TOKEN_RETIREMENT_DIAGNOSTIC.md`

## Review-tip reconciliation

The review branch advanced after the prior BUG-CANCEL-02 hold checkpoint by one unrelated append-only startup-observability diagnostic commit:
`d69cf8815704ed58a416fa3a07d6dc12738f1815`.

It is a forward child of `cc6a09f507322991df1429404b57a91a126bd4ab`, does not alter implementation source, BUG-CANCEL-02 evidence, canonical finding state, or this correction boundary, and is compatible with this checkpoint.

## Independent disposition

BUG-CANCEL-02 remains OPEN.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

No new finding ID is created.

The local-only b811/ae27 source is preserved evidence and is not GitHub-authoritative implementation state. The remote implementation branch remains at `359ddbf9...`.

The diagnostic report is accepted as implementation-agent/runtime evidence for the next correction boundary and is corroborated by the exact remote `359ddbf9...` production structure:
- `TerminalDownloadWorker.doWork()` currently executes stopped cleanup, dispatch-carrier resolution, and registry release as separate suspending calls in `finally`;
- the cleanup and dispatch-resolution helpers individually enter `NonCancellable`, but the entire finalizer sequence is not one continuous non-cancellable region;
- `TerminalExecutionRegistry.release()` is the process-local token-retirement owner.

This exact remote structure is consistent with the reported local-candidate residual: cancellation can resume between finalizer steps and prevent later token retirement even after durable STOPPED convergence and row deletion.

## Diagnostic classifications

### 1. Post-native cancellation target

Classification:
`PRODUCTION_RESIDUAL_SAME_ROOT`

Reported preserved sequence:
1. WorkManager cancellation and framework `CANCELLED`;
2. post-native test latch released;
3. worker stop path reports deferred convergence;
4. stopped-worker cleanup reports `converged=true`;
5. durable execution record reaches `TERMINAL_STOPPED / STOPPED`;
6. Terminal row is absent;
7. destination output is absent;
8. registry token remains active through the bounded observation window.

The still-active registry token is a valid BUG-CANCEL-02 residual. Durable row/STOPPED convergence is not sufficient to retire process-local effect/cache authority if the worker finalizer can be cancelled before the release owner runs.

The fixture's recovery-carrier expectation is separately over-constrained. On the reported `--simulate` path, the injected file exists before normal output validation has established the artifact manifest/publication-journal evidence that would justify a recovery carrier. Missing carrier on that fixture path is therefore not independent proof of failed cleanup.

### 2. cancellationAfterPublishedRowCommitCannotReopenTheTerminalResult

Classification:
`DISTINCT_LOCAL_CANDIDATE_DEFECT / TEST_FIXTURE_MISMATCH`

The reported `--simulate` command is planned as `NO_FILES_EXPECTED`, while the native-result hook injects a staging file. The worker correctly rejects that file as an unproven artifact and returns failure before the intended COMMITTING cancellation scenario reaches its row-deleted hook.

This is not evidence that production reopened a committed Terminal result.

The fixture must be corrected to use a plan that legitimately expects files and an output accepted by existing output-authority checks, while preserving the intended COMMITTING/cancellation ordering.

## Required correction boundary

One same-root continuation wave may carry both corrections because the production contract and both regression-contract mismatches are now sufficiently classified.

Production:
- ensure the worker finalization sequence that owns stopped cleanup, dispatch-carrier resolution, effect-lease/token retirement, and process-local authority release cannot be abandoned by coroutine cancellation after one earlier finalizer step succeeds;
- prefer the smallest composition change around the existing finalizer/owners;
- do not introduce a new durable phase, schema, journal, retry budget, dependency, or ownership abstraction unless fresh exact local source proves the existing primitives cannot express the invariant.

Tests:
- remove the recovery carrier from the decisive quiescence predicate on a fixture that cannot legitimately create one, or alter the fixture so the carrier is genuinely part of the tested contract; do not use a nonexistent carrier as a proxy for finalizer completion;
- preserve row absence, STOPPED durable outcome, inactive exact registry/effect owner, destination non-publication, and cache-ownership protection as applicable;
- correct `cancellationAfterPublishedRowCommitCannotReopenTheTerminalResult` so its command/plan expects files and its injected output satisfies existing authority validation before the COMMITTING cancellation seam is exercised.

## Same-root continuation authorization

The next implementation prompt should use the newly adopted same-root bounded-continuation protocol.

Within this BUG-CANCEL-02 root, after preserving first failure, the implementation agent may continue through:
- read-only diagnosis;
- narrow correction in the preauthorized finalizer/token-retirement production boundary;
- test fixture/quiescence-observer correction in the existing Terminal execution production-wiring test;
- compile/invocation fixes;
- bounded emulator/device recovery after zero-test infrastructure failure;
- exact rerun after a materially changed tree or materially recovered device.

Mandatory STOP remains for a new semantic root, changed contract, new unapproved durable architecture, schema/dependency/retry-budget/ownership expansion, unrelated feature scope, history/ref divergence, protected-state risk, or a repeated same failure requiring a materially different hypothesis.

## Next sequence

1. Persist one implementation-ready BUG-CANCEL-02 same-root continuation prompt.
2. Reuse the exact local chain if and only if its ancestry/worktree state still matches.
3. Complete exact final-SHA verification and normal fast-forward push only after all required gates pass.
4. Independently review the pushed exact source.
5. Only after this BUG-CANCEL-02 wave reaches a coherent completion boundary, execute the queued remediation-tooling wave, including the previously recorded emulator/Gradle reliability additions.
6. Independently review tooling.
7. Resume correctness remediation.

INDEPENDENT EXECUTION: NOT EXECUTED
