# Current Remediation Status

This file is the current status overlay for the correctness-remediation ledger. It records status decisions already established by review/evidence without rewriting historical baseline, append-only finding records, or historical closure evidence.

## Authority

- Production branch: `checkpoint/pre-baseline-review`
- Current reviewed implementation checkpoint: `5b9a3da4906eefa4fc67f82d8bbbad63019f1f5b`
- Historical semantic-clean Finding-A checkpoint: `648d2c8044e9d67f8a7367c54e3185f28206b636`
- Accepted semantic-neutral build stabilization checkpoint: `30df7058cf5232daf315813f961c6a736a75fed5`
- Governing review checklist: `REVIEW_CHECKLIST_V5_OPERATIONAL.md`
- Historical Finding-A closure evidence: `evidence/FINDING_A_CLOSURE_2026-08-28_648D2C80.md`
- Current Finding-A independent verdict: **REOPENED / NOT CLEAN**
- Current confirmed remediation-regression P1 blockers: **0**
- Current confirmed remediation-regression P2 blockers: **1** (`BUG-CANCEL-04`)
- Waivers: **none**

The historical `648d2c80...` closure remains valid evidence for the state that was reviewed at that checkpoint. It is not the current workflow authority after later implementation changes and later confirmed remediation-regression findings.

## Current workflow phase

- Finding A implementation/remediation: **REOPENED / NOT CLEAN**.
- Finding B: **not started** and must not begin until Finding A is independently CLEAN again.
- `30df7058...` remains accepted as a semantic-neutral build/verification stabilization change.
- `5b9a3da4...` is the current reviewed implementation candidate. It materially improves the cancellation/pause quiescence propagation but is not adopted as a clean Finding-A semantic baseline.

## Current Finding-A remediation-regression status

The frozen A1-A12 historical closure remains preserved at `648d2c80...`; the current NOT CLEAN verdict is caused by later remediation-regression/incomplete-closure findings rather than by rewriting those historical records.

- `BUG-ADMISSION-01` — **CLOSED** at `648d2c8044e9d67f8a7367c54e3185f28206b636`.
- Later live-owner recovery P1 found during final second-opinion review — **CLOSED** at the same checkpoint; no duplicate canonical ID was created.
- `BUG-CANCEL-03` — remediation present at `5b9a3da4906eefa4fc67f82d8bbbad63019f1f5b`; independently assessed **SOURCE-LEVEL FIXED**, but fresh production instrumentation for this checkpoint is still not executed, so it is not used as an independently verified closure.
- `BUG-PAUSE-02` — remediation present at `5b9a3da4906eefa4fc67f82d8bbbad63019f1f5b`; independently assessed **SOURCE-LEVEL FIXED**, but fresh production instrumentation for this checkpoint is still not executed, so it is not used as an independently verified closure.
- `BUG-CANCEL-04` — **OPEN P2 / current-change blocker** at `5b9a3da4906eefa4fc67f82d8bbbad63019f1f5b`. The pre-write recovery carrier records exact execution/native recovery identity but not the user Cancel/Pause disposition. If the journal commit succeeds and the first semantic stop write fails, later recovery can reinterpret the still-Active/PostProcessing row as generic abandoned execution and requeue it. This is a remediation regression / incomplete closure introduced by the current cancellation/pause recovery protocol.

No additional distinct current-change P1/P2 was established by the independent source review of `5b9a3da4...` beyond `BUG-CANCEL-04`.

## Separately owned baseline defect discovered during review

- `BUG-PAUSE-03` — **OPEN P2**, classified by the append-only ledger as a **pre-existing baseline defect discovered post ledger split; not a remediation regression**. `pauseAllDownloads()` uses a fixed Active/PostProcessing snapshot and later performs broad `cancelAllWorkByTag("download")`; a Download execution admitted after the snapshot can therefore be transport-cancelled without having acquired durable Pause semantics.

`BUG-PAUSE-03` remains separately tracked baseline debt. It is not reattributed to the `5b9a3da4...` remediation by this overlay and is not counted as a current-change Finding-A remediation blocker.

## Review of `5b9a3da4...`

Source review independently confirms the intended post-commit quiescence propagation improvements:

- notification Cancel records durable recovery responsibility, commits exact cancellation, and exposes normal cancellation-side effects only after `quiesceAfterDurableStop()` succeeds;
- notification Pause records durable recovery responsibility, commits exact Paused state, withholds Resume publication when exact quiescence fails, and owns the asynchronous receiver lifecycle through `goAsync()` / exactly-once `finish()`;
- ViewModel Cancel/Pause paths route post-write native quiescence through the same recovery-aware boundary and preserve exact execution fencing;
- failure/exception paths retain durable recovery responsibility rather than treating helper failure as normal completion;
- stale execution carriers are prevented from overwriting a different execution identity in the single-entry recovery journal.

The remaining `BUG-CANCEL-04` problem is earlier in the protocol: the durable carrier is created before the first semantic stop write but is generic with respect to the requested stop disposition. Exact native identity alone therefore does not preserve the user's Cancel/Pause decision across first-write failure and process-death recovery.

## Verification state for `5b9a3da4...`

Implementation handoff reported:

- `git diff --check` — **PASS**
- `:app:compileDebugKotlin -x lint --rerun-tasks` — **PASS**
- `:app:testDebugUnitTest -x lint --rerun-tasks` — **PASS, 406 tests**
- `:app:compileDebugAndroidTestKotlin -x lint --rerun-tasks` — **PASS**
- standard `:app:connectedDebugAndroidTest -x lint --rerun-tasks` — **FAIL BEFORE EXECUTION** because no connected device was available
- newly added cancellation/pause production-wiring instrumentation — **ADDED NOT EXECUTED**
- focused Finding-A JVM selector — **NOT EXECUTED**
- fresh `FindingAProductionWiringTest` execution — **NOT EXECUTED**
- A11 race controls — **NOT EXECUTED**
- fresh `HistoryReplacementBarrierPersistenceTest` — **NOT EXECUTED**

The repository has no GitHub Actions workflow run associated with `5b9a3da4...`, so there is no independent CI execution evidence to substitute for the missing fresh instrumentation. Historical instrumentation evidence from `648d2c80...` remains historical and is not reused as fresh execution evidence for code added at `5b9a3da4...`.

Under checklist v5, the missing production-path execution would independently prevent a new CLEAN adoption for the materially changed receiver/recovery wiring even if no source blocker remained. `BUG-CANCEL-04` already makes the current checkpoint NOT CLEAN on source correctness grounds.

## Historical Finding-A closure status

At the historical semantic-clean checkpoint, the frozen A1-A12 scope was independently closed:

- A1 `LOWQUALITY-NO-CANDIDATE-CANCEL-RACE-01` — `SOURCE-LEVEL FIXED`
- A2 `WORKER-CLEANUP-SIBLING-FAULT-ISOLATION-01` — `SOURCE-LEVEL FIXED`
- A3 `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01` — `SOURCE-LEVEL FIXED`
- A4 `WORKER-OWNERSHIP-HISTORY-CLEANUP-LEASE-01` — `SOURCE-LEVEL FIXED`
- A5 `WORKER-EXECUTION-LOCK-LEASE-ORDER-DEADLOCK-01` — `SOURCE-LEVEL FIXED`
- A6 `LOWQUALITY-CANCELLATION-PHASE2-CONVERGENCE-DEBT-01` — `SOURCE-LEVEL FIXED`
- A7 `LOWQUALITY-TERMINAL-DEBT-RETRY-RACE-01` — `SOURCE-LEVEL FIXED`
- A8 `HISTORY-STALE-FULLROW-REFERENCE-WRITERS-01` — `SOURCE-LEVEL FIXED`
- A9 `HISTORY-POSTCOMMIT-FINALIZATION-DEBT-01` — `SOURCE-LEVEL FIXED`
- A10 `HISTORY-REFERENCE-LOCK-ROOM-ORDER-DEADLOCK-01` — `SOURCE-LEVEL FIXED`
- A11 `LOWQUALITY-COORDINATOR-FAILURE-CLAIM-RACE-01` — `SOURCE-LEVEL FIXED`
- A12 `HISTORY-POSTCOMMIT-LATE-STOP-RECLASSIFICATION-01` — `SOURCE-LEVEL FIXED`

The accepted historical execution evidence remains recorded in `evidence/FINDING_A_CLOSURE_2026-08-28_648D2C80.md`. This overlay does not rewrite that evidence; it only records that later changes reopened the current workflow.

## Registry interpretation

`TASKS.md` and `TASKS_DELTA.md` preserve historical review state and are not rewritten in place when later reviews change current status.

Interpret current status as:

1. read the baseline registry in `TASKS.md`;
2. read the append-only findings in `TASKS_DELTA.md`;
3. apply explicit later status overrides and current review state from this file and cited evidence.

The old `TASKS.md` overlay saying Finding A was NOT CLEAN at an earlier review head remains a historical audit snapshot. The later `648d2c80...` closure superseded that old snapshot for its checkpoint, and the post-closure findings recorded in `TASKS_DELTA.md` subsequently reopened the current workflow.

The historical `TASKS_DELTA.md` entry for `BUG-ADMISSION-01` is superseded to **CLOSED** by the established `648d2c80...` closure. `BUG-CANCEL-03` and `BUG-PAUSE-02` have source-level remediation at `5b9a3da4...` but are not counted as closed by this overlay because required fresh production execution is still absent. No waiver is created.

## Current registry counts

At the ledger state preceding this overlay:

- baseline active defects in `TASKS.md`: **74**
- append-only post-split entries in `TASKS_DELTA.md`: **49**
- entries closed by explicit later status override: **1** (`BUG-ADMISSION-01`)
- current active post-split entries after that override: **48**
- current effective active defects: **122**

These counts are metadata only. They do not reclassify `BUG-PAUSE-03` as a remediation regression or close `BUG-CANCEL-03` / `BUG-PAUSE-02` without the required fresh evidence.

## Next gate

Finding A cannot become CLEAN from `5b9a3da4...`.

The next implementation must remediate `BUG-CANCEL-04` while preserving the exact-quiescence, live-owner, cross-attempt, and sibling-isolation guarantees already added for `BUG-CANCEL-03` and `BUG-PAUSE-02`. It must then receive fresh production-path verification and a new independent full Finding-A review under checklist v5 before any Finding B work begins.
