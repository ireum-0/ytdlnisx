# Task 002 / BUG-KEYWORD-04 — sequential independent candidate review

Date: 2026-09-12

## Exact reviewed state

- Current independently CLEAN canonical basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Candidate branch: `candidate/overnight-20260912-keyword04`
- Candidate result SHA: `4711fb1c56d1b779f8a8b472fcfe6a318e0ecbe4`
- Candidate required/base SHA: `36b43464b8106d90d672ba94718ccee58f38974f`
- Governing promotion checkpoint: `6fb5601b3e716aeb3b99957feda19a9519a4b9c7`
- Queue state: terminal `CANDIDATE_READY`; no source task is moving.

Exact ancestry was independently verified: candidate is exactly one additive commit ahead of `36b43464...`, zero behind, and changes only `AutomaticKeywordRuleEngine.kt` plus `AutomaticKeywordRulePersistenceTest.kt`. Candidate vs canonical `9edd3e23...` is diverged by one commit each with merge base exactly `36b43464...`; the candidate has not been integrated into canonical history.

The F14 canonical commit does not modify the automatic-keyword engine or its persistence test. Exact current canonical source at `9edd3e23...` still reproduces the original `BUG-KEYWORD-04` sequence: `buildHistoryIndex()` supplies a point-in-time History candidate keyed by normalized video identity, and both `recordDiscovery()` and `applyFullSync()` can later call `applyRuleToHistory()` using only the stale numeric History id after the History row has changed source identity.

## Verdict

**CANDIDATE CLEAN FOR ITS ROOT / READY FOR SEPARATE REPLAY OR REIMPLEMENTATION ON THE LATEST CLEAN BASIS.**

This is not a canonical closure and does not authorize automatic merge/cherry-pick/rebase/transplant.

- Canonical blocker-count delta now: `0`
- Canonical P2 `BUG-KEYWORD-04`: remains **OPEN** until an equivalent correction is integrated on the canonical implementation branch and independently reviewed there.
- Current canonical count remains **P0 2 / P1 1 / P2 34**.
- CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Independent semantic review

The candidate carries `expectedVideoKey = AutomaticKeywordNormalizer.videoKey(videoUrl)` from the stale index lookup to the final assignment function. Inside the same Room transaction that performs RULE assignment mutation, it reloads the History row by id and returns without mutation if the row no longer exists or its current normalized video identity differs from the expected key.

That closes the concrete root:

`stale index A -> History H changes A->B/deleted -> stale A candidate resumes -> final assignment transaction reloads H -> changed/deleted identity rejected -> no A RULE assignment/materialized keyword is recreated`.

The guard is reached from both `recordDiscovery()` and `applyFullSync()` through `applyRuleToLocalHistory()`. Existing rule existence/enabled/revision fencing remains inside the same final assignment transaction. Canonical-equivalent URL spellings remain governed by the shared `AutomaticKeywordNormalizer.videoKey()` contract rather than raw URL equality.

The candidate deliberately leaves direct `applyToHistory()` / `reconcileHistoryUrlChange()` calls with `expectedVideoKey = null`; that does not leave this root open because those paths do not consume the stale `buildHistoryIndex()` candidate that defines `BUG-KEYWORD-04`. They remain under their existing current-row/reconciliation semantics.

The deterministic production/repository tests added by the candidate cover full-sync A->B, discovery A->B, and deletion-after-candidate cases. Luna also reported existing unchanged/equivalent/revision semantics via the broader persistence suite. Two pre-existing unrelated instrumentation failures remained and were honestly reported; they are not evidence that this root remains open.

## Latest-basis compatibility

The only canonical change after candidate base is F14 metadata publication. It does not touch the automatic-keyword files, History identity normalization contract, or assignment transaction path. No semantic conflict with F14 was found.

Therefore the correction can be replayed/reimplemented as a separate canonical implementation commit starting from exact `9edd3e23...`, preserving history and without importing the old candidate branch wholesale.

## Required canonical integration boundary

A future canonical implementation should reproduce this candidate's semantics on `checkpoint/pre-baseline-review@9edd3e23...`:

1. carry expected normalized video identity from the indexed source candidate to the final RULE-assignment transaction;
2. reload History within that transaction;
3. reject missing or identity-changed rows before assignment mutation;
4. preserve rule revision/enabled fencing and canonical-equivalent identity semantics;
5. retain deterministic full-sync/discovery/deletion regressions;
6. do not broaden into F12 source authority, keyword WorkManager handoff, Undo, or unrelated History roots.

After canonical integration, independently review the exact new canonical range before decrementing P2 or advancing the CLEAN basis.

INDEPENDENT EXECUTION: NOT EXECUTED