# BUG-KEYWORD-01 — automatic-keyword source-authority closure at 4ef990e0

Date: 2026-09-11

## Exact review basis and verified chain

- Previous independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior F12 completion checkpoint: `e5561b82845feed7d297e0cffada08cad4073800`
- Reported review-fix start SHA: `5da8bc3354f6cbafd23d08dbc602530a983be6af`
- Reported and independently verified final remote implementation HEAD: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Verified direct parent: `4ef990e0...` parent = `5da8bc33...`
- Verified cumulative additive chain:
  - `9c5191c3539734fa1c9f1b63501def89f47b216a`
  - `d6bd3a81fdec5585595a9fb78e6cf32bc99a886a`
  - `5da8bc3354f6cbafd23d08dbc602530a983be6af`
  - `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Cumulative compare: final is exactly 3 commits ahead / 0 behind CLEAN basis.
- Cumulative changed files remain limited to:
  - `app/src/main/java/com/ireum/ytdl/work/AutomaticKeywordRuleSyncWorker.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/AutomaticKeywordRuleSyncWorkerProductionWiringTest.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/ObserveSourceWorkerProductionWiringTest.kt`
- Final review-fix commit `4ef990e0...` is test-only and changes only `AutomaticKeywordRuleSyncWorkerProductionWiringTest.kt`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F12 `BUG-KEYWORD-01`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**CLEAN / P1 `BUG-KEYWORD-01` FIXED-CLOSED at `4ef990e00a354a71b33c4df8f215cc27337cdce9`.**

The F12 invariant is now closed through exact production source plus production-boundary WorkManager/Room execution evidence:

> Baseline completion and scheduled discovery require source authority independently of item count; PARTIAL or FAILED extraction, including retry exhaustion, never acquires the semantic authority of an AUTHORITATIVE snapshot.

## Final source-semantic review

`AutomaticKeywordRuleSyncWorker` still obtains a typed `SourceSnapshot` and branches on authority before any baseline/apply-existing/discovery engine mutation:

- `FAILED` throws into typed failure/status handling and may retry while bounded attempts remain;
- `PARTIAL` writes PARTIAL status, retries while bounded attempts remain, and terminally returns WorkManager success only after exhaustion without entering the keyword engine;
- only `AUTHORITATIVE` proceeds to current-rule reread/revision validation and then `applyFullSync`, `recordBaseline`, or `recordDiscovery`.

The current-rule reread still requires the rule to exist, remain enabled, and retain the fetched revision before semantic mutation.

The production test seams introduced by the earlier review-fix remain null by default:

- DB substitution falls back to ordinary `DBManager.getInstance(applicationContext)`;
- typed snapshot injection falls back to ordinary `ResultRepository.getSourceSnapshotFromSource(...)`;
- the post-fetch hook is null in production.

No production authority algorithm was duplicated or replaced.

## Full F12 production boundary closure

The cumulative production wiring now establishes all required scenarios.

### A. PARTIAL empty initial baseline

The real `AutomaticKeywordRuleSyncWorker` through WorkManager + in-memory Room leaves `baselineComplete=false`, creates no matches/assignments, leaves History keyword materialization unchanged, and records PARTIAL.

### B. PARTIAL pending apply-existing

A PARTIAL snapshot does not consume `pendingApplyToExisting`, perform full sync, create assignments, or mutate History keyword materialization.

### C. FAILED snapshot

FAILED preserves prior baseline/match/assignment/History keyword state and does not create new semantic authority.

### D. AUTHORITATIVE empty

An authoritative empty snapshot may legitimately complete an empty baseline. Item count is therefore not confused with source authority.

### E. Original false-future-discovery sequence

The real worker executes:

1. PARTIAL empty;
2. AUTHORITATIVE initial full membership;
3. later AUTHORITATIVE membership with one genuinely new item.

The incomplete empty does not complete baseline; initial authoritative members are stored as baseline/ineligible; only the later genuinely new item becomes eligible/applied.

This closes the original F12 root cause.

### F. Managed Observe discovery consumer

The exact final `ObserveSourceWorker` derives `sourceIsAuthoritative` from `SourceSnapshot` authority and calls `AutomaticKeywordRuleEngine.recordDiscovery(...)` only when the source is authoritative. PARTIAL managed discovery records PARTIAL status without creating keyword match/assignment state; AUTHORITATIVE discovery may do so.

Thus the F3 authority contract is propagated through this production consumer/effect graph.

### G. Stale rule revision after fetch

The deterministic post-fetch test changes the rule revision before the production reread. The real worker refuses stale baseline/match/assignment mutation because current rule revision no longer matches the fetched rule snapshot.

### H. Bounded retry exhaustion / terminal WorkManager result

The final review-fix adds two production WorkManager/Room regressions that enqueue one real `OneTimeWorkRequest<AutomaticKeywordRuleSyncWorker>` and do not cancel it after the first retry.

The helper:

- creates one request once;
- configures linear retry backoff;
- increments an attempt counter only when the real worker reaches typed source extraction;
- waits on the same request ID until terminal WorkInfo;
- never substitutes a parallel pure retry algorithm.

PARTIAL exhaustion asserts:

- exactly 3 production worker entries;
- terminal `runAttemptCount == 3`;
- terminal WorkInfo `SUCCEEDED`;
- semantic status remains PARTIAL;
- `baselineComplete=false`;
- `pendingApplyToExisting=true`;
- no video matches or assignments;
- existing History keyword materialization unchanged.

Retryable FAILED exhaustion asserts:

- exactly 3 production worker entries;
- terminal `runAttemptCount == 3`;
- terminal WorkInfo `SUCCEEDED`;
- semantic status remains FAILED;
- prior baseline, video-match, assignment, and History keyword state is preserved.

Therefore WorkManager terminal success is explicitly proven not to grant source authority.

## Persistence-test failure reconciliation

The implementation agent reported `AutomaticKeywordRulePersistenceTest` as 27/29, with unchanged failures:

- `historyInsertedAfterKnownDiscoveryReceivesRuleKeywords`
- `compatibilityHistoryUpdateCannotDivergeFromAssignments`

These tests/file were untouched by the cumulative F12 implementation range.

The prior independent checkpoint `e5561b82845feed7d297e0cffada08cad4073800` already reconciled them:

- `historyInsertedAfterKnownDiscoveryReceivesRuleKeywords` directly drives `recordDiscovery` with an incomplete baseline and expects immediate eligibility. Under corrected F12 semantics, first authoritative membership while baseline is incomplete establishes baseline and does not make existing members newly eligible; that old expectation is not a valid reason to reopen F12.
- `compatibilityHistoryUpdateCannotDivergeFromAssignments` concerns raw History compatibility/materialization behavior, not `SourceSnapshot` authority -> baseline/discovery gating.

No new evidence in `4ef990e0...` changes that root classification. The failures are not counted as F12 residuals and are not treated as passing evidence.

## Preserved boundaries and regressions

- F3 / `BUG-OBSERVE-01` remains CLOSED; its `AUTHORITATIVE / PARTIAL / FAILED` contract is preserved.
- yt-dlp source authority is not upgraded merely to make keyword baseline/discovery progress.
- P2 `BUG-KEYWORD-HANDOFF-01` remains a distinct OPEN root.
- P2 `BUG-KEYWORD-02` remains distinct and dependency-gated by `BUG-HISTORY-01`.
- Rule revision checks remain intact.
- Apply-existing behavior remains authority-gated.
- Prior matches/assignments remain preserved on PARTIAL/FAILED source outcomes.
- Bounded retry behavior remains production-owned.
- No Room schema change was introduced by this F12 wave.
- No Master Plan or authoritative-ledger modification is made.

## Implementation-agent execution evidence

Reported for final SHA `4ef990e00a354a71b33c4df8f215cc27337cdce9` and treated as execution evidence, not independent execution:

- `AutomaticKeywordRuleSyncWorkerProductionWiringTest`: 8/8 passed;
- `ObserveSourceWorkerProductionWiringTest`: 8/8 passed;
- `AutomaticKeywordRulePersistenceTest`: 27/29 passed, with the two reconciled unchanged failures above;
- full JVM `:app:testDebugUnitTest --rerun-tasks`: 610/610 passed;
- KSP: PASS;
- debug Kotlin compile: PASS;
- Android-test Kotlin compile: PASS;
- `git diff --check`: PASS;
- emulator-5554 / Medium_Phone_API_36.1 / API 36 / x86_64 instrumentation executed;
- Room migration: NOT REQUIRED.

No GitHub commit status contexts or pull-request workflow runs were present for this exact SHA; this does not replace or negate the reported direct emulator execution evidence.

## Canonical state consequence

- `BUG-KEYWORD-01` closes: P1 count **3 -> 2**.
- P0 remains **2**.
- P2 remains **25**.
- Resulting canonical blocker count: **P0 2 / P1 2 / P2 25**.
- Overall project remains `NOT_CLEAN` because unrelated canonical blockers remain open.
- The independently CLEAN contiguous Review Basis advances from `9c5191c3539734fa1c9f1b63501def89f47b216a` to `4ef990e00a354a71b33c4df8f215cc27337cdce9` for the reviewed cumulative F12 implementation scope.

INDEPENDENT EXECUTION: NOT EXECUTED