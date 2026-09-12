# BUG-BACKUP-02 — restored-thumbnail staging exact CLEAN-basis revalidation

Date: 2026-09-12

## Exact review state

- Fixed independently CLEAN implementation basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Active implementation/review-fix work after `e7355f5440d3a13cd8857f6cb5f15c242253cce2` was treated as frozen and was not inspected or relied on for this exploratory decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F5 / `BUG-BACKUP-02`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Prior current-basis F5 checkpoint: `b5bc60a129459cdce2f59504e8ca140aeca757e5` at implementation basis `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- F5 remains a hard prerequisite of F11 / P0 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-02` remains OPEN / CONFIRMED at exact CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- Overall remediation state remains `NOT_CLEAN`.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.

## Intervening-range verification

The exact implementation range `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c..3616ae02e56995e795cc52f3074d8c3d1cd2e330` is three commits ahead / zero behind. Its changed files are cache/storage/worker-related and do **not** include `SettingsViewModel.kt` or the custom-thumbnail restore implementation.

The exact final `3616ae02...` production source was nevertheless re-read rather than inheriting the earlier verdict solely from diff absence.

## Exact production evidence

At exact `3616ae02...`, `SettingsViewModel.restoreData()` still calls `restoreCustomThumbnails(data.customThumbnails)` before `importedHistoryIdMap` is created and before restored History rows receive new destination IDs.

`restoreCustomThumbnails()` still:

1. resolves the live app-owned `custom_thumbs` directory;
2. derives the destination filename solely from backup-local numeric History ID plus sanitized extension: `restored_${item.historyId}.$extension`;
3. calls ordinary `File.writeBytes(decoded)` on that live pathname;
4. returns that live pathname keyed by the backup-local History ID;
5. later lets `insertHistory(...)` bind a newly allocated destination History row directly to that already-published pathname.

No collision-resistant per-restore staging identity, exclusive/no-overwrite publication boundary, or destination-History-owned final pathname exists before the filesystem write.

Concrete production-reachable sequence remains:

`Backup A old History id N + thumbnail X`
→ restore writes live `restored_N.jpg = X`
→ destination History HA is inserted and points at that pathname
→ a later independent Merge/backup also contains old History id N with thumbnail Y
→ second restore writes `restored_N.jpg = Y` before allocating the new destination History identity
→ HA now renders/references Y even though HA was not part of the later restore.

This is the same F5 semantic root: backup-local numeric identity is used as live filesystem ownership identity across independent restores.

## Root/count reconciliation

The broader absence of restore-wide transaction/rollback/recovery remains owned by P0 `BUG-BACKUP-03`. F5 is narrower: custom-thumbnail publication can alias and overwrite content belonging to an earlier successful import before a new destination History identity exists.

This revalidation therefore adds no blocker and changes no canonical count.

## Correction boundary retained

A correct F5 implementation must retain the established boundary:

- decode/write into a collision-resistant per-restore staging namespace;
- never use backup-local History ID as the sole live filesystem ownership identity;
- use no-overwrite/exclusive semantics for staging/final publication;
- allocate destination History identity first, or establish an explicit old→new mapping before binding a live final pathname;
- publish/rebind to a fresh destination-owned path that cannot alias an earlier import;
- clean staged/unbound files on decode/write, History insertion, or later enclosing restore failure;
- keep repeated Merge, separate backups reusing the same old ID, and extension variation isolated;
- coordinate eventual restore-wide compensation with F11 without merging this narrower F5 root into the P0 coordinator root.

## Dependency consequence

F5 remains an independently OPEN hard prerequisite for F11 / P0 `BUG-BACKUP-03`.

The next dependency-eligible backup prerequisite exploratory target is F6 / `BUG-BACKUP-06` portable numeric-reference authority, unless the active implementation completion report arrives first and preempts exploration.

INDEPENDENT EXECUTION: NOT EXECUTED
