# Finding A Independent Review — 2026-08-29 — `29d48d71`

## Authority

- Production branch: `checkpoint/pre-baseline-review`
- Reviewed implementation checkpoint: `29d48d71d1df9744bd408f9a1c2113ccb0841571`
- Starting implementation parent: `5b9a3da4906eefa4fc67f82d8bbbad63019f1f5b`
- Ledger branch at review start: `ledger/remediation@d52582062a93bad4304758e1d63e509f23b97ab9`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- Historical semantic-clean checkpoint: `648d2c8044e9d67f8a7367c54e3185f28206b636`
- Accepted semantic-neutral build stabilization: `30df7058cf5232daf315813f961c6a736a75fed5`

The review fresh-compared the remote implementation and ledger refs before inspection. The implementation diff from `5b9a3da4...` is one additive commit touching fourteen remediation source/test files and no build/dependency configuration.

## Verdict

**Finding A — NOT CLEAN**

Current-change blockers established by this review:

- P1: **0**
- P2: **2 semantic blocker families**
  1. `BUG-CANCEL-04` remains **OPEN**: the new durable USER_CANCEL/USER_PAUSE identity is not yet consumed by the exact live E1 worker's normal side-effect/success authority after a first semantic-stop write failure.
  2. Historical Finding-A **A9/A12 post-commit History invariant is reopened at this checkpoint**: a speculative late USER_CANCEL/USER_PAUSE carrier can survive rejection by an already-authoritative committed History replacement and then block or outlive post-commit finalization. No duplicate `BUG-HISTORY-*` ID is created; the canonical owner is the already-reviewed A9/A12 post-commit/late-stop scope.

`BUG-PAUSE-03` remains a separate pre-existing baseline P2 and is not counted as a current-change Finding-A remediation blocker.

## What `29d48d71...` correctly improves

The new recovery journal now durably distinguishes:

- `GENERIC`
- `USER_CANCEL`
- `USER_PAUSE`

and phases:

- `SEMANTIC_STOP_PENDING`
- `NATIVE_QUIESCENCE_PENDING`
- `NATIVE_QUIESCENT`

The implementation also preserves useful precedence and compatibility rules: legacy missing-disposition journals remain generic; USER_CANCEL may supersede USER_PAUSE for the same execution; USER_PAUSE cannot downgrade USER_CANCEL; different execution IDs cannot overwrite an unresolved carrier; generic worker cleanup preserves an existing user-stop carrier; and ordinary generic cleanup now routes an existing user-stop carrier through operation-specific convergence before native termination/requeue.

This closes the previously demonstrated direct path:

```text
pre-write generic journal
-> first Cancel/Pause write fails
-> later generic recovery
-> ordinary REQUEUED
```

when recovery actually owns the abandoned execution. It does not close the full v6 consumer/effect graph.

## P2 blocker 1 — `BUG-CANCEL-04` remains open at the live-owner boundary

The first-write failure path is now:

```text
user Cancel/Pause D/E1
-> USER_STOP / SEMANTIC_STOP_PENDING journal commits
-> first Cancelled/Paused Room write throws or no-ops
-> D remains Active/PostProcessing E1
-> same-process recovery owner is scheduled
```

That durable semantic fact is correct. The remaining failure is its propagation to a still-live exact E1.

`DownloadExecutionRecovery.reconcile()` preserves an exact current `DownloadWorkerExecutionOwners` owner and returns before operation-specific user-stop convergence. That behavior is correct for generic recovery debt, but a durable user-stop carrier is an explicit revocation request and therefore requires a consumer on the live worker side.

The current worker does not provide that consumer:

- `DownloadWorker.shouldStopForUserRequest()` checks worker stop, lost execution ownership, low-quality cancellation, and current Paused/Cancelled row state, but not the durable USER_CANCEL/USER_PAUSE recovery disposition.
- `assertExecutionOwnedBeforeAttemptLocked()` checks low-quality cancellation and current exact row/owner state, but not the durable user-stop journal.
- `ensureExecutionOwnedBeforeAttempt()` checks the process-local cancellation registry and Paused/Cancelled row state, but the process-local registry is only published after the semantic Room stop succeeds; it is absent in this first-write-failure window.
- normal History/completion code can therefore continue while the row remains Active/E1 and the execution owner remains live.

A concrete authority-effect failure remains possible:

```text
live Active/E1 worker
-> USER_CANCEL journal wins the per-Download stop boundary
-> first Cancelled write fails
-> recovery sees exact live E1 and preserves it
-> worker does not observe USER_CANCEL journal
-> worker continues output/History/completion authority
-> ordinary completion can delete the Download row
-> user Cancel intent remains unresolved or becomes orphan recovery debt
```

The equivalent USER_PAUSE first-write failure can allow the live E1 worker to finish instead of honoring the durable Pause request.

This is still the same canonical `BUG-CANCEL-04` semantic-preservation defect, not a new ID: persistence of the disposition was added, but v6 consumer closure and authority-effect closure remain incomplete.

### Required closure for blocker 1

A durable USER_CANCEL/USER_PAUSE semantic-stop carrier must be an exact revocation barrier at every later live-worker side-effect/success boundary for the same execution, while preserving `BUG-CANCEL-01` ordering:

- observing `SEMANTIC_STOP_PENDING` may prevent new E1 side effects/success publication;
- it must **not** authorize native termination until the operation-specific Cancelled/Paused semantic state has durably converged;
- worker cleanup must converge the semantic stop first and only then quiesce native authority;
- an exact live E2/E3 must remain protected from stale E1 debt;
- focused tests must hold an exact live E1 owner across first-write failure and then attempt real worker side-effect/success boundaries, not merely call `reconcile()` after releasing/omitting the live owner.

## P2 blocker 2 — A9/A12 post-commit History precedence regressed

Finding A previously established that a committed History replacement is the durable primary result and that a late Pause/Cancel cannot rewrite or reinterpret it while finalization debt remains.

`29d48d71...` preserves the Room-side no-reclassification rule but creates recovery authority before that rule is evaluated:

```text
History replacement commits for D/E1
-> Download row still exists for post-commit finalization
-> late Cancel/Pause obtains the stop boundary
-> USER_CANCEL/USER_PAUSE + SEMANTIC_STOP_PENDING journal commits
-> DownloadRepository detects committed History and refuses Cancelled/Paused rewrite
-> caller treats semantic stop as uncommitted and retains the user-stop carrier
```

The operation-specific recovery helper has no `COMMITTED_HISTORY_FINALIZATION_DEBT` / superseded-stop outcome. `prepareUserStopBeforeNative()` retries Cancel/Pause against the same committed History row, receives the same refusal/no-op, and remains blocked.

Two bad terminal windows follow:

1. If the worker remains alive, its `historyReplacementCommitted` path intentionally ignores late stop and may finish/delete the Download, but the speculative USER_STOP journal is not operation-specifically cleared, leaving orphan recovery debt.
2. If the process/worker dies after the History commit but before finalization, recovery sees the USER_STOP carrier before ordinary committed-History finalization and repeatedly tries the losing stop operation instead of completing the already-authoritative History result.

This reopens the existing A9/A12 post-commit semantic family. It is not recorded as a new `BUG-HISTORY-*` item because the same original invariant already owns precedence and finalization after an authoritative History commit.

### Required closure for blocker 2

The stop protocol needs an explicit authority-precedence result, not a Boolean/no-op interpretation. At the exact mutation boundary it must distinguish at least:

- user stop successfully committed for the exact execution;
- already-satisfied same user stop;
- exact ownership/generation lost;
- stronger committed History result already won and stop is superseded/rejected;
- actual retryable persistence failure.

If committed History already won, the speculative user-stop carrier must not remain authoritative. Recovery must preserve/complete History finalization debt and must not later retry the rejected Cancel/Pause as though it had won.

The race where the user-stop journal is recorded first but the History transaction commits before the semantic stop write must resolve by the same authoritative precedence rule; simply prechecking before `recordPending()` is insufficient unless the same synchronization/transaction boundary proves no History commit can win afterward.

## Contract-delta review

The material semantic-contract delta in this patch is the recovery journal changing from generic native debt into operation-aware user-stop authority. v6 therefore requires every consumer of the new meaning to close.

Observed closure status:

- journal schema / legacy migration: **SOURCE-LEVEL CLOSED**
- same-execution Cancel > Pause precedence: **SOURCE-LEVEL CLOSED**
- different-execution overwrite rejection: **SOURCE-LEVEL CLOSED**
- abandoned/generic cleanup consuming USER_STOP before requeue: **SOURCE-LEVEL CLOSED**
- post-semantic-write exact native quiescence: **SOURCE-LEVEL CLOSED**
- Resume/requeue/retry barriers while ordinary pending recovery remains: **SOURCE-LEVEL CLOSED for reviewed paths**
- live exact E1 worker consuming pre-write USER_STOP: **OPEN**
- committed-History-vs-speculative-USER_STOP authority precedence: **OPEN**
- final mechanical production consumer recount: Luna reported a direct-caller inventory, but this independent review did not obtain a separate commit-scoped mechanical symbol index; because semantic blockers already remain, this is not used to infer CLEAN.

No new lock-order inversion was identified. Reviewed paths preserve the intended ordering:

```text
per-Download side-effect lease
-> short global execution lock
-> Room/CAS
```

and slow native termination remains outside the global execution lock.

## Verification evidence at `29d48d71...`

Implementation handoff reported fresh execution:

- `git diff --check` — **PASS**
- `:app:compileDebugKotlin -x lint --rerun-tasks` — **PASS**
- `:app:testDebugUnitTest -x lint --rerun-tasks` — **PASS**
- focused `FindingAProductionWiringTest` — **PASS, 66/66**
- `:app:compileDebugAndroidTestKotlin -x lint --rerun-tasks` — **PASS**
- `DownloadWorkerCleanupProductionWiringTest` — **PASS, 22/22**
- six A11 Room race controls — **PASS, 6/6**
- `HistoryReplacementBarrierPersistenceTest` — **PASS, 20/20**
- standard connected suite — **FAIL after execution**, 284 tests ran and six tests failed
- GitHub Actions/status for this SHA — **NOT EXECUTED / none present**

The focused executions materially improve the evidence over `5b9a3da4...`, but they do not cover the two source-level open cells above. In particular, the newly added first-write tests retain a USER_STOP carrier and later run reconciliation without proving the same exact live E1 worker is unable to continue normal success authority.

The handoff describes the six full connected-suite failures as existing AutomaticKeyword/LowQualityRedownload failures. Exact failing test names/logs were not included in the handoff. Because this commit also modifies LowQualityRedownload production/test files, this independent review does **not** classify all six failures as unrelated without that evidence. This verification ambiguity is additional to, and not the basis of, the NOT CLEAN source verdict.

## Status of previously reopened follow-ups

- `BUG-CANCEL-03` — post-semantic-write quiescence propagation remains source-level corrected in reviewed paths; focused production wiring is reported PASS at this checkpoint.
- `BUG-PAUSE-02` — Resume publication remains gated on committed Pause + exact quiescence in reviewed paths; focused production wiring is reported PASS at this checkpoint.
- `BUG-CANCEL-04` — **OPEN P2** for the live-worker consumer/effect gap described above.
- `BUG-PAUSE-03` — **OPEN P2 baseline defect**, unchanged and separately owned.

## Next gate

Finding B must not start.

The next implementation checkpoint must close both current-change P2 families above, preserve the already-corrected quiescence/live-generation/async behavior, add deterministic live-E1 first-write-failure tests plus committed-History/late-stop tests, resolve or independently classify the six full connected-suite failures, and then receive another fresh full Finding-A review under v6.
