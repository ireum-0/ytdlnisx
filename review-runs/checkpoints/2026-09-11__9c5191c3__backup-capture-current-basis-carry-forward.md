# BUG-BACKUP-04 — current CLEAN-basis carry-forward

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact checkpoint: `aefd324334105db0c008c2996e155d941b68ad4d` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F4 `BUG-BACKUP-04`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / existing P1 `BUG-BACKUP-04` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Carry-forward guard

The exact cumulative `aa1616a2... -> 9c5191c3...` range contains five implementation commits. Its changed production/test files are limited to the F3 source-authority domain:

- `ResultRepository.kt`
- `SourceSnapshot.kt`
- `NewPipeUtil.kt`
- `YTDLPUtil.kt`
- `AutomaticKeywordRuleSyncWorker.kt`
- `ObserveSourceWorker.kt`
- associated SourceSnapshot/Observe instrumentation and unit tests.

No backup capture, backup serialization, SettingsViewModel backup, custom-thumbnail backup, or backup Room capture file is changed in that range.

Therefore no intervening commit touches, rewires, persists, consumes, or otherwise semantically affects the established F4 selected-category capture authority root.

The prior exact-source evidence remains current:

- helper-local capture failures may still collapse to ordinary empty arrays;
- required custom-thumbnail read failures may still be silently omitted;
- semantically related Room state is still captured across independent reads without one consistent capture boundary;
- outer backup failure handling cannot see helper-local failures that were already erased.

No new semantic classification is introduced here; this checkpoint only records that the existing P1 OPEN disposition carries forward to the current CLEAN basis because the intervening implementation is unrelated to this domain.

## Root reconciliation

- Existing P1 `BUG-BACKUP-04` remains counted once.
- `BUG-BACKUP-05`, `BUG-BACKUP-07`, and restore-wide `BUG-BACKUP-03` remain distinct/dependent roots as previously recorded.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
