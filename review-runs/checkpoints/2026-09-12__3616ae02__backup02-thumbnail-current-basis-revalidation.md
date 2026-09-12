# BUG-BACKUP-02 — restored-thumbnail staging current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Implementation branch: `checkpoint/pre-baseline-review`
- Implementation wave for `BUG-DUPLICATE-ADMISSION-01` is active from this exact basis; no post-start implementation commit was inspected, compared, or relied on.
- Prior F5 checkpoint: `review-runs/checkpoints/2026-09-12__9edd3e23__backup02-thumbnail-current-basis-revalidation.md` at review commit `c1e0660ecd870f3041d74f85f6592e5ac9c2feb6`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F5 / `BUG-BACKUP-02`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- F5 is a hard prerequisite of F11 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-02` remains OPEN at exact canonical CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

The exact cumulative relation from the prior F5 reviewed basis `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71` to current CLEAN basis `3616ae02e56995e795cc52f3074d8c3d1cd2e330` is a linear ahead-by-5 range with merge base exactly `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

The changed-file set in that range does not include `SettingsViewModel.kt` or a custom-thumbnail restore test. It is limited to automatic-keyword persistence/engine changes plus cache/download/terminal storage authority changes and their tests. The exact current production source at `3616ae02...` was nevertheless re-read directly rather than carrying forward the prior verdict from diff absence.

## Exact current production evidence

`SettingsViewModel.restoreData()` still calls `restoreCustomThumbnails(data.customThumbnails)` before destination History rows receive fresh IDs and before `importedHistoryIdMap` is populated.

At exact `3616ae02...`, `restoreCustomThumbnails()` still:

1. resolves the live app-owned `custom_thumbs` directory directly;
2. decodes each backup thumbnail and sanitizes its extension;
3. derives the live output path as `restored_${item.historyId}.$extension`, using backup-local History numeric identity as the sole filename identity;
4. writes bytes with ordinary `File.writeBytes`, which can replace an existing file at that path;
5. returns the resulting live path keyed by the old backup History ID.

Later, `restoreData()` inserts each History row with `id = 0L` and binds `customThumb` from `restoredCustomThumbByOldHistoryId[oldHistoryId]` before recording the fresh destination ID in `importedHistoryIdMap`.

There is still no collision-resistant per-import thumbnail staging namespace, no exclusive/no-overwrite publication boundary, and no destination-History-owned live path allocated from the fresh destination identity before publication.

Therefore the persistent cross-import alias remains possible:

`Backup A old History id N + thumbnail X`
→ restore publishes live `restored_N.jpg = X`
→ destination History HA points to that path
→ repeated Merge or independent Backup B also carries old id N + thumbnail Y
→ second restore writes `restored_N.jpg = Y`
→ HA now observes Y even though HA was not part of the second import.

The root remains the use of backup-local numeric identity as live filesystem ownership identity before destination identity exists.

## Failure-cleanup relation

Decode/write failures are still locally skipped, while thumbnail files successfully written before a later restore-stage failure are not owned by a per-import staging/compensation boundary in this path.

The broader restore-wide rollback/atomicity problem remains owned by P0 `BUG-BACKUP-03`. F5 remains narrower: custom-thumbnail content is published into shared live namespace before destination History ownership is established, allowing an import to overwrite content already referenced by a previous successful import.

## Correction boundary

F5 remains implementation-ready as a local prerequisite repair when selected:

- decode/write into a collision-resistant per-import staging namespace;
- never use backup-local History ID as the sole live filesystem ownership identity;
- use no-overwrite semantics for staging/publication;
- allocate destination History identity first, or otherwise establish an explicit old→new destination mapping before binding a live path;
- publish/rebind to a fresh destination-owned path that cannot alias a prior import;
- clean staged/unbound files when decode/write/History insertion or a later enclosing restore stage fails;
- preserve legitimate extension/content variants;
- cover repeated Merge, two backups reusing the same old ID with different bytes, extension variation, write failure, History insertion failure, later restore failure, and Reset;
- coordinate final compensation with F11 without merging F5 into the restore-wide atomicity root.

## Dependency consequence

F5 remains independently OPEN and therefore remains a hard prerequisite blocking F11 `BUG-BACKUP-03` implementation.

While the separate duplicate-admission implementation wave remains active, independent exploration should continue from exact fixed basis `3616ae02...` and must not inspect or rely on post-start implementation commits. A duplicate-admission completion report preempts that exploration.

INDEPENDENT EXECUTION: NOT EXECUTED