# Current Remediation Status

This file is the current status overlay for the correctness-remediation ledger. It records status decisions already established by review/evidence without rewriting historical baseline, append-only finding records, or historical closure evidence.

## Authority

- Production branch: `checkpoint/pre-baseline-review`
- Current reviewed implementation checkpoint: `29d48d71d1df9744bd408f9a1c2113ccb0841571`
- Historical semantic-clean Finding-A checkpoint: `648d2c8044e9d67f8a7367c54e3185f28206b636`
- Accepted semantic-neutral build stabilization checkpoint: `30df7058cf5232daf315813f961c6a736a75fed5`
- Governing review checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- Historical Finding-A closure evidence: `evidence/FINDING_A_CLOSURE_2026-08-28_648D2C80.md`
- Current independent review evidence: `evidence/FINDING_A_REVIEW_2026-08-29_29D48D71.md`
- Current Finding-A independent verdict: **REOPENED / NOT CLEAN**
- Current confirmed remediation-regression P1 blockers: **0**
- Current confirmed remediation-regression P2 blocker families: **2**
  - `BUG-CANCEL-04` — still OPEN at the live-worker consumer/effect boundary.
  - A9/A12 post-commit History regression — existing canonical Finding-A post-commit/late-stop scope reopened; no duplicate `BUG-HISTORY-*` ID created.
- Waivers: **none**

The historical `648d2c80...` closure remains valid evidence for the state reviewed at that checkpoint. It is not the current workflow authority after later implementation changes and later confirmed remediation regressions.

## Current workflow phase

- Finding A implementation/remediation: **REOPENED / NOT CLEAN**.
- Finding B: **not started** and must not begin until Finding A is independently CLEAN again.
- `30df7058...` remains accepted as a semantic-neutral build/verification stabilization change.
- `29d48d71...` is the current reviewed implementation candidate. It materially improves operation-aware user-stop recovery but is not adopted as a clean Finding-A semantic baseline.

## Current Finding-A remediation-regression status

The frozen A1-A12 historical closure remains preserved at `648d2c80...`; the current NOT CLEAN verdict records regressions/incomplete closure at later implementation checkpoints rather than rewriting that historical decision.

- `BUG-ADMISSION-01` — **CLOSED** at `648d2c8044e9d67f8a7367c54e3185f28206b636`.
- Later live-owner recovery P1 found during final second-opinion review — **CLOSED** at the same checkpoint; no duplicate canonical ID was created.
- `BUG-CANCEL-03` — post-semantic-write exact quiescence propagation remains **SOURCE-LEVEL FIXED** in reviewed `29d48d71...` paths; implementation handoff reports fresh focused production wiring PASS.
- `BUG-PAUSE-02` — Pause/Resume publication remains **SOURCE-LEVEL FIXED** in reviewed `29d48d71...` paths; implementation handoff reports fresh focused production wiring PASS.
- `BUG-CANCEL-04` — **OPEN P2 / current-change blocker** at `29d48d71...`. The carrier now correctly stores USER_CANCEL/USER_PAUSE and semantic/native phases, and generic abandoned recovery no longer directly requeues such a carrier. However the durable `SEMANTIC_STOP_PENDING` fact is not consumed by a still-live exact E1 worker after the first Cancel/Pause Room write fails. Recovery preserves the live E1 owner, while worker side-effect/success checks do not inspect the operation-aware journal. The same E1 can therefore continue toward ordinary output/History/completion even though an exact user-stop revocation is durably pending.
- Historical A9 `HISTORY-POSTCOMMIT-FINALIZATION-DEBT-01` / A12 `HISTORY-POSTCOMMIT-LATE-STOP-RECLASSIFICATION-01` — **current P2 regression family reopened at `29d48d71...`**. A late Cancel/Pause can write a speculative USER_STOP carrier before `DownloadRepository` determines that an already-committed History replacement is the stronger primary result. The Room stop is correctly refused, but the user-stop carrier remains and operation-specific recovery repeatedly retries the losing stop rather than allowing committed-History finalization. If the live worker finishes first, the same carrier can instead survive as orphan recovery debt. This is recorded under the existing A9/A12 canonical scope rather than a duplicate `BUG-HISTORY-*` entry.

## Separately owned baseline defect

- `BUG-PAUSE-03` — **OPEN P2**, classified by the append-only ledger as a **pre-existing baseline defect discovered post ledger split; not a remediation regression**. `pauseAllDownloads()` uses a fixed Active/PostProcessing snapshot and later performs broad `cancelAllWorkByTag("download")`; a Download execution admitted after the snapshot can therefore be transport-cancelled without having acquired durable Pause semantics.

`BUG-PAUSE-03` remains separately tracked baseline debt and is not counted as a current-change Finding-A remediation blocker.

## Review of `29d48d71...`

Independent source review confirms several useful parts of the remediation:

- the recovery journal durably distinguishes `GENERIC`, `USER_CANCEL`, and `USER_PAUSE`;
- operation phases distinguish `SEMANTIC_STOP_PENDING`, `NATIVE_QUIESCENCE_PENDING`, and `NATIVE_QUIESCENT`;
- legacy missing-disposition journals remain generic;
- USER_CANCEL can supersede same-execution USER_PAUSE, while USER_PAUSE cannot downgrade USER_CANCEL;
- a different execution generation cannot overwrite an unresolved carrier;
- generic worker/recovery cleanup preserves an existing user-stop disposition and routes it through operation-specific semantic convergence before native cleanup/requeue;
- after Cancelled/Paused semantics have actually committed, exact native quiescence remains a required proof before normal cancellation cleanup or Resume publication;
- the asynchronous BroadcastReceiver body owns its own exceptions and exactly-once `PendingResult.finish()` handling remains present;
- reviewed lock ordering remains per-Download side-effect lease -> short global execution lock -> Room/CAS, with slow native termination outside the global lock.

The remaining source blockers are consumer/authority-precedence failures of the strengthened operation-aware recovery contract, not absence of the new journal fields themselves.

## Verification state for `29d48d71...`

Implementation handoff reported fresh execution:

- `git diff --check` — **PASS**
- `:app:compileDebugKotlin -x lint --rerun-tasks` — **PASS**
- `:app:testDebugUnitTest -x lint --rerun-tasks` — **PASS**
- focused `FindingAProductionWiringTest` — **PASS, 66/66**
- `:app:compileDebugAndroidTestKotlin -x lint --rerun-tasks` — **PASS**
- `DownloadWorkerCleanupProductionWiringTest` — **PASS, 22/22**
- six A11 Room race controls — **PASS, 6/6**
- `HistoryReplacementBarrierPersistenceTest` — **PASS, 20/20**
- standard connected suite — **FAIL after actual execution**, 284 tests run with six failures reported in AutomaticKeyword/LowQualityRedownload tests
- GitHub Actions / commit status for `29d48d71...` — **NOT EXECUTED / none present**

The focused instrumentation is materially stronger evidence than at `5b9a3da4...`, and the newly added tests are no longer `ADDED NOT EXECUTED`. They do not, however, exercise the two still-open semantic cells established by independent source review:

1. an exact live E1 owner continues to exist after USER_CANCEL/USER_PAUSE journal commit plus first semantic-write failure, and the real worker then attempts later side-effect/success publication;
2. committed History wins before a late Cancel/Pause semantic transition while the new speculative USER_STOP carrier exists, including process death before finalization.

The implementation handoff did not include the exact names/logs for the six full connected-suite failures. Because `29d48d71...` also changes LowQualityRedownload production/test files, this overlay does not independently classify all six failures as unrelated. That verification ambiguity is additional to, not the basis of, the current source-level NOT CLEAN verdict.

The repository has no GitHub Actions workflow run or commit-status context for `29d48d71...`.

## Historical Finding-A closure status

At the historical semantic-clean checkpoint, the frozen A1-A12 scope was independently closed:

- A1 `LOWQUALITY-NO-CANDIDATE-CANCEL-RACE-01` — historical closure preserved
- A2 `WORKER-CLEANUP-SIBLING-FAULT-ISOLATION-01` — historical closure preserved
- A3 `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01` — historical closure preserved
- A4 `WORKER-OWNERSHIP-HISTORY-CLEANUP-LEASE-01` — historical closure preserved
- A5 `WORKER-EXECUTION-LOCK-LEASE-ORDER-DEADLOCK-01` — historical closure preserved
- A6 `LOWQUALITY-CANCELLATION-PHASE2-CONVERGENCE-DEBT-01` — historical closure preserved
- A7 `LOWQUALITY-TERMINAL-DEBT-RETRY-RACE-01` — historical closure preserved
- A8 `HISTORY-STALE-FULLROW-REFERENCE-WRITERS-01` — historical closure preserved
- A9 `HISTORY-POSTCOMMIT-FINALIZATION-DEBT-01` — historical closure preserved at `648d2c80...`; **current regression reopened at `29d48d71...`**
- A10 `HISTORY-REFERENCE-LOCK-ROOM-ORDER-DEADLOCK-01` — historical closure preserved
- A11 `LOWQUALITY-COORDINATOR-FAILURE-CLAIM-RACE-01` — historical closure preserved
- A12 `HISTORY-POSTCOMMIT-LATE-STOP-RECLASSIFICATION-01` — historical closure preserved at `648d2c80...`; **current regression reopened at `29d48d71...`**

The accepted historical execution evidence remains recorded in `evidence/FINDING_A_CLOSURE_2026-08-28_648D2C80.md`. This overlay does not alter that evidence.

## Registry interpretation

`TASKS.md` and `TASKS_DELTA.md` preserve historical review state and are not rewritten in place when later reviews change current status.

Interpret current status as:

1. read the baseline registry in `TASKS.md`;
2. read append-only findings in `TASKS_DELTA.md`;
3. apply later status overrides and current review state from this file and cited evidence.

The historical `TASKS_DELTA.md` entry for `BUG-ADMISSION-01` is superseded to **CLOSED** by the established `648d2c80...` closure. `BUG-CANCEL-03` and `BUG-PAUSE-02` have current source remediation plus fresh focused execution reported at `29d48d71...`; they are not the present blockers. `BUG-CANCEL-04` remains open. A9/A12 are reopened as an existing Finding-A invariant regression, so no new append-only defect ID or registry count is created by this status update. No waiver is created.

## Current registry counts

The registry counts remain unchanged by this review because no duplicate append-only defect ID was created for the A9/A12 regression:

- baseline active defects in `TASKS.md`: **74**
- append-only post-split entries in `TASKS_DELTA.md`: **49**
- entries closed by explicit later status override: **1** (`BUG-ADMISSION-01`)
- current active post-split entries after that override: **48**
- current effective active defects: **122**

These counts are metadata only. They do not make the reopened A9/A12 current semantic regression disappear; that regression belongs to the already-existing Finding-A scope rather than a newly counted registry item.

## Next gate

Finding A cannot become CLEAN from `29d48d71...`.

The next implementation must:

1. finish `BUG-CANCEL-04` by propagating exact USER_CANCEL/USER_PAUSE `SEMANTIC_STOP_PENDING` authority to the still-live E1 worker's side-effect/success boundaries without violating the required semantic-write-before-native-termination ordering;
2. restore A9/A12 precedence so an already-authoritative committed History replacement supersedes/rejects speculative late user-stop recovery authority and finalization remains recoverable;
3. add deterministic production-path tests for both cells, including live owner and process-death windows;
4. preserve the exact-quiescence, live-generation, sibling-isolation, async receiver, and lock-order guarantees already established;
5. resolve or independently classify the six full connected-suite failures;
6. receive another fresh full Finding-A review under checklist v6 before any Finding B work begins.
