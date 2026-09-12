# BUG-BACKUP-02 — exact CLEAN-basis revalidation

Date: 2026-09-12 UTC

## Exact review state

- Fixed contiguous independently CLEAN basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Workflow state: `IMPLEMENTATION_DIFF_FROZEN_REVIEW_CONTINUES`.
- Active implementation target: F4 / `BUG-BACKUP-04`; no in-progress F4 diff was inspected or relied upon.
- Root: F5 / existing P2 `BUG-BACKUP-02`.
- Prior exact-basis checkpoint: `8bb305d943c6eee10fda062dfbd4ebe946492aa2` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-BACKUP-02` remains valid at exact CLEAN basis `a12c5805...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 29**.
- CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- F5 remains a hard prerequisite for F11 / P0 `BUG-BACKUP-03`.

## Intervening-range verification

The accepted range `3616ae02... -> a12c5805...` is the duplicate-admission remediation/test-harness range and does not modify `SettingsViewModel.kt` or the custom-thumbnail restore path. Exact final `a12c5805...` source was re-opened.

## Exact current production evidence

At exact `a12c5805...`, `SettingsViewModel.restoreData()` still invokes `restoreCustomThumbnails(data.customThumbnails)` before `importedHistoryIdMap` is created and before restored History rows receive newly allocated destination IDs.

`restoreCustomThumbnails()` still:

1. writes directly into the live app-owned `custom_thumbs` directory;
2. derives the live filename solely from backup-local History ID plus sanitized extension: `restored_${item.historyId}.$extension`;
3. uses ordinary `File.writeBytes(decoded)` on that pathname;
4. returns the pathname keyed by the backup-local History ID;
5. later allows a newly inserted destination History row to bind to that already-published path.

There is still no collision-resistant per-restore staging identity, exclusive/no-overwrite publication boundary, or destination-History-owned final pathname before the write.

Concrete production sequence remains:

`restore A: old History id N + thumbnail X`
→ live `restored_N.jpg` is written with X
→ destination History HA is inserted and points at that path
→ independent restore B also contains old History id N + thumbnail Y
→ the same live `restored_N.jpg` is overwritten with Y before B has a destination History identity
→ HA now observes Y although HA was not part of restore B.

This is the same F5 root: backup-local numeric identity is incorrectly used as durable live filesystem ownership identity across independent restores.

## Root reconciliation

F5 remains narrower than P0 F11 / `BUG-BACKUP-03`: F5 owns custom-thumbnail publication aliasing/overwrite before a new destination identity exists; F11 owns restore-wide transaction/recovery/compensation.

No new blocker is added; count delta is `0`.

## Stable correction boundary

A future F5 fix still needs collision-resistant per-restore staging, no-overwrite publication semantics, destination-owned final paths after destination History identity/mapping exists, cleanup of unbound staged files on failure, and isolation across repeated Merge/separate backups/extension variations.

No F5 implementation prompt is issued while F4 implementation is active.

INDEPENDENT EXECUTION: NOT EXECUTED