# BUG-KEYWORD-01 — production-boundary review-fix completion review at 5da8bc33

Date: 2026-09-11

## Exact review basis and verified chain

- Previous independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior independent F12 revalidation checkpoint: `dc57238e5417b13a1b35917fcc4311dc53bfae50`
- Reported implementation commits:
  - `d6bd3a81fdec5585595a9fb78e6cf32bc99a886a`
  - `5da8bc3354f6cbafd23d08dbc602530a983be6af`
- Independently verified remote implementation HEAD: `5da8bc3354f6cbafd23d08dbc602530a983be6af`
- Verified contiguous relation: `9c5191c3... -> d6bd3a81... -> 5da8bc33...`, exactly two commits ahead, zero behind.
- Verified direct parents:
  - `d6bd3a81...` parent = `9c5191c3...`
  - `5da8bc33...` parent = `d6bd3a81...`
- Cumulative changed files: exactly three:
  - `app/src/main/java/com/ireum/ytdl/work/AutomaticKeywordRuleSyncWorker.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/AutomaticKeywordRuleSyncWorkerProductionWiringTest.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/ObserveSourceWorkerProductionWiringTest.kt`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F12 `BUG-KEYWORD-01`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**NOT_CLEAN / existing P1 `BUG-KEYWORD-01` remains OPEN — A–G production authority/effect coverage is now present and source semantics remain correct, but the required bounded-retry exhaustion / terminal-result production evidence is still incomplete.**

No new semantic root is counted.

- Count delta: **0**
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Source semantics and test seams

The implementation is test/seam-only in semantic effect.

`AutomaticKeywordRuleSyncWorker` now permits tests to inject:

- an in-memory `DBManager`;
- a typed `SourceSnapshot` at the real extraction boundary;
- a deterministic post-fetch rule-revision mutation.

All hooks are null by default. With hooks unset, ordinary production behavior remains the same as the independently reviewed F12 source semantics at `9c5191c3...`.

The real worker still owns:

- the initial enabled-rule snapshot;
- manual-sync RUNNING/terminal status updates;
- `SourceSnapshot` authority branching;
- PARTIAL/FAILED retry behavior;
- post-fetch current-rule reread and revision validation;
- selection of `applyFullSync`, `recordBaseline`, or `recordDiscovery`;
- engine/Room mutations and final WorkManager result.

No weakening of the independently closed F3 / `BUG-OBSERVE-01` typed source-authority contract was found.

## Production-boundary closure now established

### A. PARTIAL empty initial baseline

The new `AutomaticKeywordRuleSyncWorkerProductionWiringTest` drives the real worker through WorkManager against in-memory Room with a PARTIAL empty `SourceSnapshot` and proves:

- `baselineComplete` remains false;
- no video matches are created;
- no rule assignments are created;
- materialized History keywords remain unchanged;
- `manualSyncStatus` becomes PARTIAL;
- the worker enters WorkManager retry state.

### B. PARTIAL apply-existing

The real worker is exercised with `pendingApplyToExisting=true` and PARTIAL membership. The test proves:

- pending apply-existing intent remains true;
- no full-sync video match or assignment is committed;
- History keyword materialization remains unchanged;
- manual-sync status is PARTIAL and the worker enters retry state.

### C. FAILED snapshot

The real worker is exercised with a FAILED snapshot while baseline/match/assignment state already exists. The test proves:

- baseline state remains intact;
- existing video match remains intact;
- existing rule assignment and materialized keyword remain intact;
- manual-sync status becomes FAILED;
- the worker enters retry state.

### D. AUTHORITATIVE empty baseline

An authoritative empty snapshot through the real worker legitimately completes the empty baseline and records SUCCESS without inventing video matches or assignments.

### E. Historical incomplete-empty sequence

The production worker test executes:

1. PARTIAL empty;
2. AUTHORITATIVE membership containing two existing videos;
3. later AUTHORITATIVE membership containing those two plus one new video.

It proves the PARTIAL empty does not complete baseline, the authoritative initial membership establishes the old videos as ineligible baseline members, and only the later genuinely new item becomes eligible and receives the rule keyword.

This closes the original F12 incomplete-empty -> false future discovery scenario at the actual worker/Room boundary.

### F. Managed Observe discovery authority

`ObserveSourceWorkerProductionWiringTest` now drives the real Observe worker with a managed automatic-keyword rule and proves:

- PARTIAL source membership records PARTIAL discovery status but creates no keyword video match or assignment;
- AUTHORITATIVE membership records SUCCESS, creates the eligible match, and materializes the keyword.

This uses the existing real Observe worker/Room composition rather than a duplicate test-only algorithm.

### G. Post-fetch rule revision race

The narrow post-fetch hook deterministically changes the rule revision after source extraction but before the production worker rereads the rule. The real worker then exits without completing baseline, creating video matches, or assigning keywords for the stale revision.

The hook changes only test timing; the actual stale-revision decision is the production reread/check.

## Remaining blocker — H bounded retry exhaustion is not actually exercised

The issued review-fix required production evidence for bounded retry/manual-sync terminal behavior, including:

- retry exhaustion must not be confused with authoritative baseline completion;
- a terminal WorkManager result must not imply `baselineComplete` or consume `pendingApplyToExisting`;
- PARTIAL/FAILED terminal handling must not grant semantic authority that the source snapshot did not grant.

The final production wiring test does **not** execute this boundary to exhaustion.

Its `runWorker()` helper returns as soon as the rule reaches SUCCESS/PARTIAL/FAILED and WorkInfo is either ENQUEUED or finished. For PARTIAL and retryable FAILED paths, the first `Result.retry()` causes WorkManager to expose an ENQUEUED retry with `runAttemptCount >= 1`; the helper then cancels that WorkRequest immediately.

Therefore the current tests prove:

- the first retry is real WorkManager retry state;
- `runAttemptCount` actually advances;
- semantic state remains protected through that first retry boundary.

But they do **not** prove the production behavior after `MAX_ATTEMPTS` is exhausted, where the worker intentionally returns a terminal result while preserving non-authoritative PARTIAL/FAILED semantic state.

This matters under v6 core invariant 2 (`Stateful retry/re-entry must reconstruct the same semantic barrier; a one-attempt carrier is insufficient`) and core invariant 16 (`CLEAN requires semantic closure and required actual execution evidence`). It is also part of the explicit F12 review-fix boundary already issued before this wave.

No source-level defect is currently established in the exhaustion branch. The residual is a narrow required production-execution evidence gap.

## Reported instrumentation failures outside the residual

The implementation agent reported `AutomaticKeywordRulePersistenceTest` as 27/29 with two pre-existing failures in untouched code:

- `historyInsertedAfterKnownDiscoveryReceivesRuleKeywords`
- `compatibilityHistoryUpdateCannotDivergeFromAssignments`

The persistence test file is not part of this wave's changed files.

For F12 attribution:

- `historyInsertedAfterKnownDiscoveryReceivesRuleKeywords` creates a rule with baseline incomplete and directly calls `recordDiscovery`; under the corrected F12 contract, first authoritative membership while baseline is incomplete is baseline establishment, so immediate eligibility/assignment is not an F12 requirement. Its old expectation cannot be used to reopen the incomplete-empty authority root.
- `compatibilityHistoryUpdateCannotDivergeFromAssignments` exercises raw History compatibility/materialization behavior, not `SourceSnapshot` authority -> keyword baseline/discovery gating.

These failures therefore do not establish a new same-root F12 blocker in this review. They also are not treated as passing evidence.

## Root reconciliation / preserved boundaries

- Existing P1 `BUG-KEYWORD-01` remains counted once.
- P2 `BUG-KEYWORD-HANDOFF-01` remains separate and is not broadened or closed by this wave.
- P2 `BUG-KEYWORD-02` remains separate.
- `BUG-OBSERVE-01` remains CLOSED absent regression.
- History duplicate identity remains CLOSED absent regression.
- No new P0/P1/P2 root is added.

## Minimal next review-fix boundary

Do not redesign source authority or keyword semantics.

Add the smallest production WorkManager/Room regression that actually lets PARTIAL and retryable FAILED AutomaticKeywordRuleSyncWorker attempts reach `MAX_ATTEMPTS` exhaustion without test cancellation, then prove terminal WorkManager completion does not grant baseline/discovery/apply-existing authority.

At minimum prove for PARTIAL and FAILED exhaustion:

- the same request actually re-enters the production worker across retries;
- final `runAttemptCount` reaches the bounded retry threshold;
- the terminal WorkInfo state is observed rather than cancelling after the first ENQUEUED retry;
- PARTIAL exhaustion leaves `baselineComplete=false`, leaves `pendingApplyToExisting` intact when applicable, and creates no unauthorized matches/assignments;
- FAILED exhaustion likewise creates no new baseline/discovery/match/assignment authority and preserves prior semantic state;
- terminal WorkManager SUCCESS/FAILURE semantics are not reinterpreted as authoritative source success.

Use WorkManager testing facilities or the narrowest deterministic production-boundary mechanism possible. Do not replace the worker's retry algorithm with a parallel pure helper.

## Implementation-agent evidence

Reported for final SHA `5da8bc3354f6cbafd23d08dbc602530a983be6af` and treated as evidence, not independent execution:

- `AutomaticKeywordRuleSyncWorkerProductionWiringTest`: 6/6 passed;
- `ObserveSourceWorkerProductionWiringTest`: 8/8 passed;
- `AutomaticKeywordRulePersistenceTest`: 27/29 passed with the two reported pre-existing failures above;
- full JVM `:app:testDebugUnitTest`: 610/610 passed;
- KSP: PASS;
- debug Kotlin compile: PASS;
- Android-test Kotlin compile: PASS;
- `git diff --check`: PASS;
- focused instrumentation executed on emulator-5554 / API 36 / x86_64;
- Room migration: NOT REQUIRED.

These reported results do not substitute for independent execution.

INDEPENDENT EXECUTION: NOT EXECUTED
