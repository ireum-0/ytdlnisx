# BUG-BACKUP-02 — restored-thumbnail staging current-basis revalidation

Date: 2026-09-12

## Exact review state

- Fixed independently CLEAN implementation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Current verification-only cache target remains exact implementation SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`; no newer implementation diff was inspected or relied on for this exploratory decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F5 / `BUG-BACKUP-02`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- F5 remains a hard prerequisite of F11 / P0 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-02` remains OPEN at exact fixed CLEAN basis `93d01d2a...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 31**.
- Overall remediation state remains `NOT_CLEAN`.
- CLEAN Review Basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

## Intervening-range verification

The prior current-basis F5 checkpoint reviewed exact basis `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

The exact implementation range `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71..93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` is two commits ahead / zero behind and changes only:

- `app/src/main/java/com/ireum/ytdl/database/repository/AutomaticKeywordRuleEngine.kt`
- `app/src/androidTest/java/com/ireum/ytdl/database/AutomaticKeywordRulePersistenceTest.kt`

It does not modify the custom-thumbnail restore implementation. The exact final `93d01d2a...` source was nevertheless re-read rather than inheriting the old verdict solely from diff absence.

## Exact current production evidence

At exact `93d01d2a...`, `SettingsViewModel.restoreData()` still calls:

`restoreCustomThumbnails(data.customThumbnails)`

before `importedHistoryIdMap` is created and before restored History rows receive new destination IDs.

`restoreCustomThumbnails()` still:

1. resolves the live app-owned `custom_thumbs` directory;
2. derives the destination filename solely from the backup-local numeric History ID and sanitized extension:
   `restored_${item.historyId}.$extension`;
3. calls ordinary `File.writeBytes(decoded)` on that live path, which replaces existing content at the same pathname;
4. returns the live path keyed by the old backup History ID;
5. later allows a newly inserted History row to bind to that already-published path.

No collision-resistant per-restore staging identity, exclusive/no-overwrite publication boundary, or destination-History-owned final pathname exists before the filesystem write.

Concrete production-reachable sequence therefore remains:

`Backup A old History id N + thumbnail X`
→ restore publishes `restored_N.jpg = X`
→ destination History HA is inserted and points to that pathname
→ a later independent Merge/backup also contains old History id N with thumbnail Y
→ second restore writes `restored_N.jpg = Y` before allocating its new History identity
→ HA now references Y even though HA was not part of the later restore.

This is the same F5 semantic root: backup-local numeric identity is being used as live filesystem ownership identity across independent restores.

## Failure and sibling relation

Decode/write failures are still locally converted to skip via `runCatching(...).getOrNull()`. Earlier successfully written thumbnail files are not rollback-owned by a destination History identity at the time they are published.

The broader absence of restore-wide rollback/atomicity remains owned by P0 `BUG-BACKUP-03`. F5 is narrower: a custom-thumbnail payload can alias and overwrite content belonging to an earlier successful restore before a new destination History identity exists.

No new blocker root is added by this revalidation.

## Correction boundary retained

A correct F5 implementation must preserve the previously established boundary:

- decode/write into a collision-resistant per-restore staging namespace;
- never use backup-local History ID as the sole live filesystem ownership identity;
- use no-overwrite/exclusive semantics for staging and final publication;
- allocate destination History identity first, or otherwise establish an explicit old→new mapping before binding a live final pathname;
- publish/rebind to a fresh destination-owned path that cannot alias an earlier import;
- clean staged/unbound files when decode/write, History insertion, or a later enclosing restore stage fails;
- keep repeated Merge, separate backups reusing the same old ID, and extension variation isolated;
- coordinate eventual restore-wide compensation with F11 without merging F5 into the P0 atomic-restore root.

Focused scenarios remain: repeated Merge of one backup; two backups reusing one old History ID with different bytes; extension variation; write failure; History insertion failure; later restore failure; and Reset restore.

## Dependency consequence

F5 remains an independently OPEN hard prerequisite for F11 / P0 `BUG-BACKUP-03`.

The next dependency-eligible backup prerequisite exploratory target is F6 / `BUG-BACKUP-06` portable numeric-reference authority, unless the CACHE-02 verification completion report arrives first and preempts exploration.

INDEPENDENT EXECUTION: NOT EXECUTED