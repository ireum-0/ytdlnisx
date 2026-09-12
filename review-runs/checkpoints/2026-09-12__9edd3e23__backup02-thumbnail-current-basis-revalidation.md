# BUG-BACKUP-02 — restored-thumbnail staging current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Prior current-basis checkpoint: `2026-09-11__9c5191c3__backup-thumbnail-current-basis-revalidation.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F5 / `BUG-BACKUP-02`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- F5 is a hard prerequisite of F11 `BUG-BACKUP-03`.
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-02` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

- Canonical blocker-count delta: `0`
- Canonical blocker count remains **P0 2 / P1 1 / P2 34**.
- CLEAN Review Basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

The exact cumulative range from prior reviewed basis `9c5191c3539734fa1c9f1b63501def89f47b216a` to `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71` contains F12/F13/F14 keyword/metadata corrections and does not modify the custom-thumbnail restore implementation in `SettingsViewModel.kt`.

The exact current source was nevertheless re-read rather than carried forward solely from diff absence.

## Exact current production evidence

`SettingsViewModel.restoreData()` still calls `restoreCustomThumbnails(data.customThumbnails)` before restored History rows receive fresh destination IDs and before `importedHistoryIdMap` exists.

`restoreCustomThumbnails()` still:

1. resolves the live app-owned `custom_thumbs` directory;
2. derives the output filename solely from backup-local `historyId` plus sanitized extension:
   `restored_${item.historyId}.$extension`;
3. writes with ordinary `File.writeBytes`, which overwrites an existing file of the same path;
4. returns that live path keyed by old History ID;
5. later lets History insertion bind a fresh destination row to that already-published live path.

There is no collision-resistant per-import staging namespace, exclusive/no-overwrite publication rule, or destination-History-owned final path allocated after new History identity exists.

Concrete sequence remains:

`Backup A old History id N + thumbnail X`
→ restore writes live `restored_N.jpg = X`
→ destination History HA points to that path`
→ repeated Merge / Backup B also has old id N + different thumbnail Y`
→ second restore writes live `restored_N.jpg = Y` before allocating HB`
→ HA's thumbnail content changes even though HA was not part of the second import.

That is persistent cross-import aliasing caused by backup-local numeric identity being used as live filesystem ownership identity.

## Failure cleanup relation

Decode/write failures are locally swallowed with `getOrNull()`/skip, and a later restore failure does not clean files written earlier by `restoreCustomThumbnails()`.

The broader absence of restore-wide rollback is owned by P0 `BUG-BACKUP-03`; F5 specifically owns the fact that staged/imported thumbnail content is not isolated from existing live content and can overwrite a prior successful import before destination identity is allocated.

## Correction boundary

F5 remains implementation-ready as a local prerequisite repair when selected:

- decode/write into a collision-resistant per-import staging namespace;
- never use backup-local History ID as the sole live filesystem ownership identity;
- use no-overwrite semantics for staging/publication;
- allocate destination History identity first or otherwise obtain an explicit old→new mapping before binding a live path;
- publish/rebind to a fresh destination-owned path that cannot alias an earlier import;
- clean staged/unbound files when decode/write/History insertion or a later enclosing restore stage fails;
- repeated Merge and independent backups that reuse the same old ID and extension must remain isolated;
- preserve legitimate extension/content variants;
- coordinate final cleanup/compensation with F11 without merging F5 into the restore-wide atomicity root.

Required focused scenarios remain: repeated Merge of the same backup, two backups reusing the same old History ID with different bytes, same ID with extension variation, write failure, History insertion failure, later restore failure, and Reset.

## F11 dependency consequence

F5 is independently OPEN and therefore is an additional current hard prerequisite blocking F11 `BUG-BACKUP-03` implementation.

After this checkpoint, the dependency-eligible exploratory sequence should continue with F6 `BUG-BACKUP-06` portable numeric-reference authority while the separate Task 002 `BUG-KEYWORD-04` canonical replay remains the already-recorded first implementation target.

INDEPENDENT EXECUTION: NOT EXECUTED
