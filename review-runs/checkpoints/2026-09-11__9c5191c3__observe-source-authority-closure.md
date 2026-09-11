# BUG-OBSERVE-01 — final production-boundary closure review

Date: 2026-09-11

## Exact review basis and chain

- Previous contiguous independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Initial F3 implementation: `fffb6b131a7b9cb35cf8a218b42863df9e332430`
- Lifecycle review-fix: `ebc3a4f770a584a02f6ea2738dff9dd43e1fb267`
- Production-boundary test/seam commit: `b45eaa6618e050c26fefa8e51ca43feaf6c529e7`
- Test lifecycle-fixture correction: `29bbd9018f45d95e9fad3b9d7e74db4ccf49ba5f`
- Final test-expectation correction / reviewed final SHA: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Independently verified implementation branch remote HEAD: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Verified direct chain: `ebc3a4f7 -> b45eaa66 -> 29bbd901 -> 9c5191c3`
- Verified cumulative relation from prior CLEAN basis: `aa1616a2... -> 9c5191c3...`, five commits ahead, zero behind.
- `ebc3a4f7... -> 9c5191c3...` changes exactly two files: `ObserveSourceWorker.kt` and the new production-boundary instrumentation test.
- `b45eaa66... -> 9c5191c3...` changes only `ObserveSourceWorkerProductionWiringTest.kt`; no production semantics changed in the two final commits.

Governing references:

- Master Plan `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F3 `BUG-OBSERVE-01`
- Master Plan whole-text SHA-256 `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- v6 checklist `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- prior independent checkpoint `d436352d265dae885295f78065b38b943c239f4e`

## Reconciliation with the concurrent b45eaa66 checkpoint

`review/remediation` already contained historical checkpoint `3c56f2328567c52ef69f50f8c7d6a79bfdf4e6bc`, frozen at `b45eaa66...`.

That checkpoint found the source semantics correct and the production-boundary test source materially improved, but kept `BUG-OBSERVE-01` open because actual instrumentation execution at its frozen SHA was not verified. It explicitly recorded that the implementation branch had advanced to `9c5191c3...` and that the newer implementation must be reviewed in a later round rather than mixed into the b45 review.

This final checkpoint is that later round. It does not rewrite the historical b45 decision. It reviews the exact final SHA and incorporates the final completion report's actual emulator execution evidence as implementation-agent evidence, while preserving `INDEPENDENT EXECUTION: NOT EXECUTED` for this reviewer.

## Verdict

**CLEAN / P0 `BUG-OBSERVE-01` FIXED-CLOSED at `9c5191c3539734fa1c9f1b63501def89f47b216a`.**

The source-level F3 authority contract previously found correct at `ebc3a4f7...` remains correct, and the previously missing production worker/durable-state regression evidence now exists at the actual `ObserveSourceWorker` / WorkManager / Room boundary.

No same-root semantic residual was found in the cumulative `aa1616a2... -> 9c5191c3...` review.

## Full F3 semantic closure

### Membership authority

The final `SourceSnapshot` contract remains:

- `AUTHORITATIVE`
- `PARTIAL`
- `FAILED`

Only `AUTHORITATIVE` permits destructive absence reconciliation. `PARTIAL` and `FAILED` cannot authorize deletion by omission. Authoritative empty remains a valid complete-membership observation.

### Producer authority

The previously independently reviewed producer contract remains unchanged after `ebc3a4f7...`:

- NewPipe conversion drops, incomplete continuation/pagination, and extraction failure preserve `PARTIAL`/`FAILED` authority rather than pretending membership is complete.
- genuinely completed NewPipe membership, including a genuinely completed empty source, may be `AUTHORITATIVE`.
- current yt-dlp typed source extraction remains conservatively `PARTIAL` because its production request uses `--ignore-errors` and tolerant child-line parsing; the lifecycle fix does not obtain progress by falsely upgrading it to authoritative.
- `ResultRepository` preserves typed source authority through NewPipe/yt-dlp selection and fallback; a primary `PARTIAL` result is not silently upgraded by fallback.

### Lifecycle authority is distinct from membership authority

The final contract remains:

- `AUTHORITATIVE -> INITIAL_BASELINE_ELIGIBLE`
- `PARTIAL -> FORWARD_PROGRESS`
- `FAILED -> NONE`

Production `ObserveSourceWorker` consumes those meanings separately:

- destructive source absence is still authority-gated;
- PARTIAL does not establish a complete first-run get-only-new baseline;
- PARTIAL can flow through positive-item processing and advance safe recurring run lifecycle;
- FAILED does not advance run count;
- retry-confirmation waiting remains deliberately non-counting;
- `endsAfterCount` is evaluated from the correctly advanced persisted count.

This closes the earlier permanent `runCount == 0` / first-run-ignore trap without reopening destructive absence.

## Production-boundary test seam review

`b45eaa66...` adds four narrow test seams to the real worker:

1. in-memory `DBManager` substitution;
2. typed `SourceSnapshot` injection at the production extraction boundary;
3. downstream `startDownloadWorker` effect capture;
4. retry-confirmation capability override.

All hooks default to null and production behavior falls back to the pre-existing real objects/effects.

The downstream queue seam is placed after the worker's production filtering/admission and after `downloadRepo.insert/update` persists queued Download rows. Therefore the new tests exercise durable positive-item publication logic rather than merely a helper-generated candidate list.

The worker itself still performs the correctness-relevant composition:

- source-row load;
- production filtering/canonicalization;
- complete-baseline decision;
- PARTIAL/FAILED/AUTHORITATIVE authority decisions;
- sync-with-source absence gating;
- History deletion path;
- Download row insert/update;
- `alreadyProcessedLinks` / `ignoredLinks` persistence;
- `runCount` update;
- `endsAfterCount` stop transition;
- successor scheduling / terminal worker result.

The two commits after `b45eaa66...` modify only the instrumentation test fixture/assertion and do not alter production semantics.

## Required production scenarios A–G

Final `ObserveSourceWorkerProductionWiringTest` drives an actual `ObserveSourceWorker` through WorkManager with an in-memory Room database.

### A. PARTIAL first run

Covered by `partialFirstRunUsesProductionWorkerWithoutBaselineOrAbsenceAndAdvancesRun`:

- `getOnlyNewUploads=true`, `runCount=0`;
- PARTIAL snapshot advances persisted run count to 1;
- complete ignore baseline is not established;
- a positive item reaches production queue publication;
- omitted History remains present under `syncWithSource`.

### B. Later PARTIAL positive item

Covered by `laterPartialPositiveItemReachesProductionQueueWithoutAbsenceDeletion`:

- later positive item reaches queue publication;
- run count advances;
- omitted prior History remains protected from destructive absence.

### C. `endsAfterCount`

Covered by `partialRunAdvancesAndStopsAtEndsAfterCountThroughProductionWorker`:

- usable PARTIAL run advances persisted `runCount`;
- threshold transition persists `STOPPED`;
- positive item is still published.

### D. FAILED snapshot

Covered by `failedSnapshotDoesNotAdvanceOrPublishPositiveWorkThroughProductionWorker`:

- existing run count is unchanged;
- no positive queue work is published;
- pre-existing processed membership is preserved;
- History is not deleted by absence.

The final `9c5191c3...` assertion correctly checks preservation of the existing processed link rather than incorrectly expecting it to disappear.

### E. AUTHORITATIVE first run

Covered by `authoritativeFirstRunEstablishesCompleteBaselineAndAdvancesThroughProductionWorker`:

- complete first-run baseline is persisted;
- run count advances;
- baseline items are not queued as new downloads.

### F. AUTHORITATIVE empty

Covered by `authoritativeEmptySourceRemovesMissingHistoryThroughProductionWorker`:

- authoritative empty membership reaches the real sync-with-source destructive path;
- legitimately absent History is removed;
- no positive queue work is emitted.

### G. Retry-confirmation waiting

Covered by `retryConfirmationWaitingKeepsRunCountNonCountingAtProductionBoundary`:

- retry-confirmation capability is injected at the real production decision gate;
- waiting state remains non-counting;
- no Download work is spuriously published.

These scenarios close the exact evidence gap recorded by `d436352d...`.

## Execution evidence

The implementation agent reported for final SHA `9c5191c3...`:

- focused `SourceSnapshotTest`: 12/12 passed;
- full JVM suite: 610 tests, 0 failures, 0 errors, 0 skipped;
- production instrumentation: 7/7 passed;
- instrumentation device: `emulator-5554`, `Medium_Phone_API_36.1`, API 36, x86_64;
- KSP: PASS;
- debug Kotlin compile: PASS;
- Android-test Kotlin compile: PASS;
- `git diff --check`: PASS;
- Room migration: NOT REQUIRED.

The implementation report also states that earlier instrumentation attempts exposed a fixture signature problem and then an incorrect assertion; both were corrected in additive test-only commits before the final successful 7/7 run.

These claims are accepted as implementation-agent execution evidence, not as independent execution by this reviewer. The v6 requirement for actual execution evidence is therefore represented, while reviewer execution status remains unchanged below.

## Preserved root boundaries

- `BUG-KEYWORD-01` remains a distinct P1 root; F3 closure merely satisfies its hard prerequisite. It is not closed here.
- `BUG-OBSERVE-HANDOFF-01` remains a distinct open P0 generation/supersession/revocation root and was not broadened into F3.
- `BUG-OBSERVE-SOURCE-IDENTITY-01` remains a distinct P2 semantic source-owner identity root.
- the independently closed History duplicate-identity correction remains preserved; the cumulative F3 range does not modify its production files.
- P2-B, P2-K, and B10 remain CLOSED/PRESERVED absent concrete regression.
- no Master Plan or authoritative-ledger modification is authorized or made by this checkpoint.

## Canonical count and basis consequence

`BUG-OBSERVE-01` closes exactly one existing P0 root.

- P0: **3 -> 2**
- P1: **3**
- P2: **25**
- Overall project state remains `NOT_CLEAN` because other canonical blockers remain open.

The cumulative implementation range for this remediation wave is independently CLEAN for its reviewed scope. Therefore the contiguous independently CLEAN Review Basis advances:

`aa1616a2c7710b878c44949a5f74ad02c6706d8d`
→ `9c5191c3539734fa1c9f1b63501def89f47b216a`.

F3 closure makes P1 `BUG-KEYWORD-01` dependency-eligible. It must now be independently revalidated against exact current source before an implementation prompt is issued.

INDEPENDENT EXECUTION: NOT EXECUTED
