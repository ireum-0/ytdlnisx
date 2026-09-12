# BUG-KEYWORD-04 — canonical implementation independent review

Date: 2026-09-12

## Exact reviewed range

- Previous independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Canonical implementation candidate: `8e7f466c12bc4753c3e4bb048f7c02619f7d4c28`
- Exact ancestry: `8e7f466c...` is exactly one commit ahead of `9edd3e23...`, zero behind, with parent/merge-base exactly `9edd3e23...`.
- Changed production file: `app/src/main/java/com/ireum/ytdl/database/repository/AutomaticKeywordRuleEngine.kt`
- Changed test file: `app/src/androidTest/java/com/ireum/ytdl/database/AutomaticKeywordRulePersistenceTest.kt`
- Governing candidate-review checkpoint: `review-runs/checkpoints/2026-09-12__4711fb1c__keyword04-candidate-sequential-review.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / `BUG-KEYWORD-04` remains OPEN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 34**.
- CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- `8e7f466c...` must not become the independently CLEAN basis.

The canonical commit correctly adds a final positive-candidate identity fence, but it leaves—and in `recordDiscovery()` widens—the opposite stale-index failure: a currently matching History row can be absent from the point-in-time index and therefore receive no RULE assignment even though the discovery/full-sync transaction makes that video eligible.

## What is correct in the candidate

The implementation correctly carries `expectedVideoKey = AutomaticKeywordNormalizer.videoKey(videoUrl)` from an indexed History candidate to `applyRuleToHistory(...)`. Inside the final Room transaction it reloads the History row, rejects deletion, rejects a changed normalized identity, rechecks rule existence/enabled/revision, and then mutates assignment rows plus the materialized `HistoryItem.keywords` projection.

That closes the stale-positive sequence for a candidate that was actually present in the index:

`index contains H under A -> H changes A->B or is deleted -> stale H/A candidate reaches final transaction -> current H is reloaded -> mismatch/missing is rejected -> no stale A RULE assignment is written`.

Both `recordDiscovery()` and `applyFullSync()` reach the guard through `applyRuleToLocalHistory()`. Direct `applyToHistory()` / `reconcileHistoryUrlChange()` continue to use their existing current-row semantics with `expectedVideoKey = null`. Canonical-equivalent URL identity remains governed by shared `AutomaticKeywordNormalizer.videoKey()` rather than raw URL equality. Existing rule revision/enabled fencing remains present.

The current canonical replay is also stronger than the old overnight candidate's discovery race seam: it explicitly evaluates the lazy index before the test hook so the A->B test is no longer trivially satisfied by rebuilding the index after the mutation.

## Residual blocker — stale-negative History omission at the assignment authority boundary

`recordDiscovery()` now executes, for each video, approximately:

1. `val indexedHistory = historyByVideoKey` — this forces the lazy whole-History index to be captured before the video transaction;
2. optional deterministic test hook;
3. `db.withTransaction { ... insert/promote video match ... applyRuleToLocalHistory(..., indexedHistory) }`.

`applyRuleToLocalHistory()` can only revalidate IDs already present in `indexedHistory`. The new `expectedVideoKey` guard rejects stale positive candidates, but it cannot discover a matching History row that was absent when the snapshot was built.

Concrete reachable single-video `recordDiscovery()` sequence at `8e7f466c...`:

1. rule R is enabled and baseline-complete (so new discovery is assignment-eligible);
2. discovery for video A captures `indexedHistory` while no History row for A exists;
3. before discovery enters its Room transaction, another production path calls `HistoryKeywordAssignmentRepository.insertHistory()` for A;
4. that insert transaction writes History H, then calls `getEnabledRulesForVideoKey(A)`; the discovery video-match has not been inserted/promoted yet, so the query returns no eligible R and H receives no RULE assignment;
5. the History insert commits;
6. discovery transaction inserts/promotes R's video match for A;
7. discovery applies R only to the stale pre-insert `indexedHistory`, which does not contain H;
8. transaction commits with the video match eligible but H still missing R's assignment/materialized keyword.

The two production transactions serialize their writes, but the point-in-time History snapshot is outside that serialization. No final re-query fills the negative gap. The row can remain semantically divergent until a later independent reconciliation/sync happens; immediate correctness is not guaranteed.

This is not hypothetical helper behavior. `HistoryKeywordAssignmentRepository.insertHistory()` applies automatic rules only through `AutomaticKeywordRuleDao.getEnabledRulesForVideoKey(videoKey)`, whose SQL requires an existing `automatic_keyword_rule_video_matches` row with `eligibleForAssignment = 1`. Therefore an insert that wins before the discovery transaction cannot self-repair this sequence.

### Relation to the previous CLEAN basis

At `9edd3e23...`, `recordDiscovery()` declared `historyByVideoKey` lazy and first consumed it inside the eligible video's `db.withTransaction`. For the first/single eligible video, that preserved a useful two-way ordering:

- if History insertion committed first, the transaction-local lazy index could see the new row;
- if discovery committed first, a later History insertion could see the committed eligible video match and assign R during `insertHistory()`.

`8e7f466c...` moves the index observation before that transaction for the first/single video, widening the stale-negative race into this common case.

`applyFullSync()` already builds its History index outside its per-video transaction at the previous basis, so the same stale-negative omission class is also still present there. The canonical replay therefore does not fully close stale History-index authority; it fixes stale positive IDs but not missing current matches.

## Root/count reconciliation

This residual is kept under the existing P2 `BUG-KEYWORD-04` root rather than creating a new defect ID. It is the opposite direction of the same point-in-time History-index authority problem at final automatic RULE assignment:

- stale positive candidate -> wrong assignment risk (now guarded);
- stale negative snapshot -> required current assignment omitted (still open, and widened in discovery by this commit).

No blocker-count increment is warranted.

The previously preserved F12 `BUG-KEYWORD-01` source-authority closure is not reopened: this residual assumes an otherwise valid/eligible discovery/full-sync result and concerns History membership/identity authority at the final assignment boundary, not source snapshot completeness.

F14 metadata publication is untouched by this commit. No shared-code regression to F14 was found.

## Verification evidence disposition

Implementation-agent report is preserved as evidence:

- exact remote start `9edd3e23...` and exact remote final HEAD `8e7f466c...`;
- new stale-positive tests: 3/3 PASS;
- full `AutomaticKeywordRulePersistenceTest`: 30 PASS / 2 documented pre-existing failures (`historyInsertedAfterKnownDiscoveryReceivesRuleKeywords`, `compatibilityHistoryUpdateCannotDivergeFromAssignments`);
- full JVM: 610 PASS / 0 FAIL;
- KSP: PASS;
- debug Kotlin compile: PASS;
- Android-test Kotlin compile: PASS;
- `git diff --check`: PASS;
- instrumentation: initial sandbox adb launch failed before execution, then direct-access rerun on `Medium_Phone_API_36.1(AVD) - 16` executed all 32 tests; the class outcome remains 30 pass / 2 documented pre-existing failures;
- Room migration: NOT REQUIRED.

These tests exercise stale-positive A->B/deletion fencing but do not exercise the stale-negative sequence above: History insertion after index capture but before eligible video-match publication.

## Minimal review-fix boundary

Preserve the current final positive identity guard, but restore an atomic ordering between video-match eligibility publication and discovery of currently matching History rows.

A safe correction must ensure that for each assignment-eligible video, the transaction that makes the video match eligible also obtains the authoritative current set of matching History rows (or an equivalent exact mechanism) before applying assignments. The ordering must satisfy both sides:

- History insert wins first -> sync transaction sees and assigns that History row;
- sync transaction wins first -> later `insertHistory()` sees the committed eligible video match and assigns the rule.

Do not use one whole-method point-in-time History map as negative authority. If normalized video identity cannot be queried directly in SQL, a current History scan/filter inside the same Room transaction is semantically acceptable even if a more efficient exact index is later designed; correctness precedes optimization.

Retain:

- final current-History `expectedVideoKey` revalidation for every selected candidate;
- rule existence/enabled/revision fencing;
- canonical-equivalent URL semantics through `AutomaticKeywordNormalizer.videoKey()`;
- baseline eligibility semantics;
- direct `applyToHistory()` / `reconcileHistoryUrlChange()` behavior;
- F12 source-authority scope separation.

Add deterministic production/repository regressions for both `recordDiscovery()` and `applyFullSync()` where a matching History row is inserted after the old point-in-time index seam but before the transaction publishes/uses the video match, and prove exactly one correct RULE assignment/materialized keyword results. Keep the existing A->B and deletion races green.

No schema migration is indicated by this residual.

INDEPENDENT EXECUTION: NOT EXECUTED