# Current Remediation Status

This file is the current status overlay for the correctness-remediation ledger. It records status decisions already established by review/evidence without rewriting historical baseline or append-only finding records.

## Authority

- Production branch: `checkpoint/pre-baseline-review`
- Semantic-clean Finding-A checkpoint: `648d2c8044e9d67f8a7367c54e3185f28206b636`
- Governing review checklist: `REVIEW_CHECKLIST_V5_OPERATIONAL.md`
- Finding-A closure evidence: `evidence/FINDING_A_CLOSURE_2026-08-28_648D2C80.md`
- Finding-A independent verdict: **CLOSED / CLEAN**
- Finding-A current-change P1/P2 blockers at closure: **0**
- Waivers: **none**

The semantic-clean checkpoint above remains the Finding-A authority until a later implementation checkpoint is independently reviewed and explicitly adopted as a new semantic baseline.

## Current workflow phase

- Finding A implementation/remediation: **CLOSED**.
- Build/verification performance stabilization: **authorized as a separate semantic-neutral activity**.
- Finding B: **not started by the Finding-A closure**.
- Any stabilization commit above `648d2c8044e9d67f8a7367c54e3185f28206b636` requires targeted independent review before it becomes a new implementation baseline.

## Finding A closure status

The frozen A1-A12 scope is closed at the semantic-clean checkpoint:

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

Remediation-regression status already established by review:

- `BUG-ADMISSION-01 — Keep a successful Download claim recoverable across post-claim publication failure` — **CLOSED** at `648d2c8044e9d67f8a7367c54e3185f28206b636`.
- The later live-owner recovery P1 found during final second-opinion review — **CLOSED** at the same checkpoint. It was reviewed as part of Finding-A closure and was not assigned a duplicate canonical defect ID.

## Accepted Finding-A execution evidence

At the semantic-clean checkpoint, the accepted evidence recorded by the closure review is:

- `git diff --check` — **PASS**
- `:app:compileDebugKotlin -x lint` — **PASS**
- `:app:testDebugUnitTest -x lint` — **PASS**
- focused Finding-A JVM set — **PASS, 27/27**
- `:app:compileDebugAndroidTestKotlin -x lint` — **PASS**
- direct Android instrumentation `FindingAProductionWiringTest` — **PASS, 34 tests**
- all six A11 production Room races — **PASS**
- direct Android instrumentation `HistoryReplacementBarrierPersistenceTest` — **PASS, 20 tests**

A separate uncached JVM rerun was `FAIL BEFORE EXECUTION` because AAPT2 could not start its daemon. The standard connected Android-test Gradle path was also `FAIL BEFORE EXECUTION` because of the pre-existing `app/build.gradle:89` `applicationVariants` configuration error. Those infrastructure failures did not replace the focused executions above and are now part of the separate build/verification stabilization scope.

## Registry interpretation

`TASKS.md` and `TASKS_DELTA.md` preserve historical review state. They are not rewritten in-place merely because a later review closes one previously recorded finding.

Current status must therefore be interpreted as:

1. read the baseline registry in `TASKS.md`;
2. read the append-only findings in `TASKS_DELTA.md`;
3. apply the explicit status overrides in this file and the cited closure/re-review evidence.

The historical `TASKS.md` correctness-remediation overlay that names review HEAD `66c1db3c7315ead73c585b9d18a229a36a275d22` and says Finding A is `NOT CLEAN` is superseded by the Finding-A closure evidence above. It remains preserved only as an earlier audit snapshot.

The historical `TASKS_DELTA.md` entry for `BUG-ADMISSION-01` records the defect when it was open. Its current status is superseded to **CLOSED** by the independently reviewed Finding-A closure at `648d2c8044e9d67f8a7367c54e3185f28206b636`.

No other baseline or post-split defect is closed, waived, reclassified, or reattributed by this status overlay.

## Current registry counts

The baseline registry contains **74** active baseline defects.

`TASKS_DELTA.md` contains **43** recorded post-split defect entries, all originally recorded as Open. One of those entries, `BUG-ADMISSION-01`, is now closed by the already-established Finding-A closure decision.

Therefore, for the ledger state represented by this overlay:

- baseline active defects: **74**
- post-split recorded defect entries: **43**
- post-split entries closed by explicit later status override: **1**
- current active post-split defects: **42**
- current effective active defects: **116**

These counts are metadata derived from the existing registries plus the established `BUG-ADMISSION-01` closure. They do not change the ownership or priority of any remaining defect.

## Separately owned defects

The Finding-A closure does not consume unrelated baseline/post-split defects. Existing records such as `BUG-DOWNLOAD-01`, `BUG-SCHEDULER-06`, and `BUG-KEYWORD-05` remain separately owned according to their existing ledger entries.

Likewise, closure of Finding A does not by itself close the broader baseline `BUG-BACKUP-01` record or begin Finding B. Any later closure or implementation decision for those scopes requires its own review/evidence.