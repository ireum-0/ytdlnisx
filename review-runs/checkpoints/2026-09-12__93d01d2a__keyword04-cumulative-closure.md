# BUG-KEYWORD-04 cumulative canonical closure review — 2026-09-12

## Exact reviewed range

- Previous independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- First canonical implementation: `8e7f466c12bc4753c3e4bb048f7c02619f7d4c28`
- Review-fix commit: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Final remote implementation HEAD independently verified: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Exact ancestry: `93d01d2a...` is exactly two commits ahead of `9edd3e23...`, zero behind, with merge base exactly `9edd3e23...`; `93d01d2a...` parent is exactly `8e7f466c...`.
- Cumulative changed files: only `app/src/main/java/com/ireum/ytdl/database/repository/AutomaticKeywordRuleEngine.kt` and `app/src/androidTest/java/com/ireum/ytdl/database/AutomaticKeywordRulePersistenceTest.kt`.
- Prior NOT_CLEAN checkpoint: `53b1448df833a05472683a590a4041bd6686fea7` / `review-runs/checkpoints/2026-09-12__8e7f466c__keyword04-canonical-review.md`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**CLEAN / `BUG-KEYWORD-04` CLOSED.**

- Count delta: `P2 -1`.
- Canonical blocker count becomes **P0 2 / P1 1 / P2 33**.
- The contiguous independently CLEAN basis advances from `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71` to `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` for the reviewed cumulative implementation scope.

The review-fix closes the stale-negative History admission residual without regressing the stale-positive identity fence introduced by the first canonical commit.

## Cumulative production proof

### 1. Stale-negative omission is closed by one coherent Room ordering

Both `recordDiscovery()` and `applyFullSync()` now enter the per-video `db.withTransaction` before publishing assignment eligibility and before discovering matching History rows. Inside that same transaction they:

1. revalidate the current rule snapshot/revision/condition identity as applicable;
2. insert/promote the exact normalized `videoKey` match to assignment-eligible state;
3. call `applyRuleToLocalHistory(...)`;
4. query the current History table inside the transaction and filter it using `AutomaticKeywordNormalizer.videoKey()`;
5. apply the RULE assignment to those current matching rows.

The old whole-method/point-in-time History map is removed.

This gives the required two-way write ordering against `HistoryKeywordAssignmentRepository.insertHistory()`:

- **History insert wins first:** its Room transaction commits H before the discovery/full-sync transaction begins. The later per-video transaction publishes the eligible match and then reads current History, so it sees H and assigns the rule.
- **Discovery/full-sync wins first:** its transaction commits the eligible video match first. A later `insertHistory()` transaction calls `getEnabledRulesForVideoKey(videoKey)`, whose SQL requires `eligibleForAssignment = 1`, so it sees the committed rule match and assigns the rule during History insertion.

Because both sides use Room write transactions, there is no surviving external snapshot between eligibility publication and current-History discovery that can lose the insert-first case.

### 2. Stale-positive candidates remain fenced at the final mutation boundary

`applyRuleToLocalHistory()` computes `expectedVideoKey = AutomaticKeywordNormalizer.videoKey(videoUrl)` and selects current rows by the same normalized identity. For every selected History ID, `applyRuleToHistory(...)` reloads the current History row inside the final assignment transaction when `expectedVideoKey` is supplied. Missing rows are rejected; rows whose current normalized URL identity no longer equals the expected key are rejected before assignment mutation.

The same final transaction then reloads the current rule and requires:

- rule still exists;
- rule remains enabled;
- rule revision still equals the captured revision.

Only then are current rule keywords read and `HistoryKeywordAssignmentRepository.replaceSourceKeywords(...)` invoked.

This preserves the first commit's A->B/deletion protection while closing the opposite stale-negative side.

### 3. Canonical identity is not broadened

The correction continues to use `AutomaticKeywordNormalizer.videoKey()` for:

- incoming video filtering/deduplication where already applicable;
- video-match persistence keys;
- current History matching;
- final expected-current History revalidation;
- later History insertion lookup through `getEnabledRulesForVideoKey(videoKey)`.

No raw-URL equality or second identity model was introduced. Canonical-equivalent URL forms therefore remain within the same identity contract.

### 4. Baseline/source authority and direct History semantics are preserved

The cumulative two-commit change does not alter `recordBaseline()`, the public `applyToHistory()` contract, or `reconcileHistoryUrlChange()` behavior except that the private indexed-assignment path now supplies the optional expected identity fence.

The independently closed F12 `BUG-KEYWORD-01` source-authority contract remains intact in production callers:

- `ObserveSourceWorker` invokes automatic discovery only when its `SourceSnapshot` is authoritative; PARTIAL/FAILED source snapshots do not feed discovery as authoritative input.
- `AutomaticKeywordRuleSyncWorker` distinguishes FAILED/PARTIAL/AUTHORITATIVE snapshots, re-reads the current rule after fetch, requires current enabled + same revision, then selects `applyFullSync`, `recordBaseline`, or `recordDiscovery`.

This implementation does not reinterpret source completeness, baseline eligibility, rule revision, or direct History reconciliation semantics.

### 5. Process-death / transaction durability

For an assignment-eligible video, eligible-match publication and current-History discovery/assignment occur within the same outer Room transaction. A process death before commit rolls back that transaction; after commit the eligible match exists durably, so later `insertHistory()` can derive the RULE assignment from it. The correction therefore does not create a durable state where a committed eligible match is hidden behind a stale negative History snapshot.

## Test/evidence disposition

Implementation-agent evidence is preserved, not independently re-executed:

- exact remote start for review-fix `8e7f466c12bc4753c3e4bb048f7c02619f7d4c28`;
- review-fix commit/final remote HEAD `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`;
- `AutomaticKeywordRulePersistenceTest`: 34 executed, 32 passed, 2 documented pre-existing failures:
  - `historyInsertedAfterKnownDiscoveryReceivesRuleKeywords`;
  - `compatibilityHistoryUpdateCannotDivergeFromAssignments`;
- all five BUG-KEYWORD-04 stale-positive/deletion and stale-negative ordering tests reported PASS;
- full JVM suite: 610 passed, 0 failed;
- KSP PASS;
- debug Kotlin compile PASS;
- Android-test Kotlin compile PASS;
- `git diff --check` PASS;
- instrumentation: initial adb-access attempts failed before execution; final direct-access run executed all 34 tests on `emulator-5554 / Medium_Phone_API_36.1(AVD) - 16` and retained the same 32-pass / 2-documented-pre-existing-failure class result;
- Room migration: NOT REQUIRED.

The two documented failures are not silently reclassified by this closure. The reviewed root is the stale point-in-time History authority used when an otherwise assignment-eligible video match is published. The existing baseline/direct-compatibility failures remain outside this root's correction boundary and were explicitly preserved by the implementation instruction.

## Regression / semantic-contract closure

The cumulative diff changes only the automatic-keyword engine and focused persistence tests. The public engine result models and caller contracts remain unchanged. The material semantic delta is internal: assignment-eligible video publication now obtains current matching History within the same transactional ordering and selected candidates receive an expected-current normalized identity fence. Direct callers need no adaptation because no public success/failure or parameter contract changed.

No regression was found to preserved F12 `BUG-KEYWORD-01`, F13 `BUG-METADATA-02`, or F14 `BUG-METADATA-01` closures from this two-file cumulative range.

## Final disposition

`BUG-KEYWORD-04` is independently CLEAN for the full canonical range `9edd3e23... -> 93d01d2a...` and is CLOSED.

Canonical state after this checkpoint:

- P0: 2
- P1: 1
- P2: 33
- overall remains `NOT_CLEAN` because unrelated blockers remain open
- independently CLEAN basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`

INDEPENDENT EXECUTION: NOT EXECUTED