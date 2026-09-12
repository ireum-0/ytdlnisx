# BUG-MIGRATION-01 — exact CLEAN-basis independent revalidation

Date: 2026-09-12

## Exact reviewed state

- Independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Prior current-basis checkpoint: `06c11ecfe03b2f2ee896e61de9593eb093a8eac4` at `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Exact remote duplicate-admission verification for `8c5db3c7...` remains a separate active verification task; no post-`3616ae02...` state is used as evidence here.

## Verdict

**NOT_CLEAN — existing P2 `BUG-MIGRATION-01` remains OPEN at exact canonical CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

Exact compare `9edd3e23... -> 3616ae02...` is five commits ahead and includes cache-remediation changes to `FolderSettingsFragment.kt`, so exact final source was re-read directly. The added Folder-settings changes are cache-authority work; the video-folder migration still retains the same filesystem-before-History durability ordering and no durable migration carrier.

## Exact current production path

The production path remains:

`FolderSettingsFragment -> migrateDefaultVideoFolderInternal() -> moveFileToDestination() -> raw/SAF external move -> HistoryDao.updateDownloadPathById()`.

Inside `migrateDefaultVideoFolderInternal()`, `movedPathMap` is still a process-local `linkedMapOf<String,String>`. A path not already present in that map is passed to `moveFileToDestination(...)`; on success the returned destination is placed in the process-local map and only later, after the item's path list has been built, `historyDao.updateDownloadPathById(item.id, updatedPaths)` is called.

For raw copy fallback, current source copies bytes and then deletes `sourceFile` before returning the destination path. For SAF, current source writes the created destination document and then deletes `sourceFile` before returning the destination URI. The same helper also retains raw rename/move behavior. There is no durable old-path/new-path intent or per-reference recovery carrier persisted before the irreversible source removal.

## Concrete durable failure sequence

1. History row H durably references old path A.
2. Migration selects A and chooses destination B.
3. Raw rename/copy or SAF copy succeeds; in copy paths A is deleted before the helper returns success.
4. Process death or Room failure occurs before `updateDownloadPathById(H, ...B...)` commits.
5. Durable H still references A while B exists and A is absent.
6. `movedPathMap` disappears with the process.
7. On rerun, candidate construction depends on the old file/path still being present under the old default location, so this stale H can be skipped rather than deterministically mapped to B.
8. No exact durable H/A -> B ownership fact exists from which restart can safely finish or roll back the operation.

The same root applies when several History rows share A: one row can commit B while another remains at A, then process death loses the process-local mapping and leaves the remaining reference stranded.

## Current narrowing preserved

The historical stale full-row History overwrite subcase remains narrowed away. Current migration publishes only the `downloadPath` field through `updateDownloadPathById`; this checkpoint does not claim that title/author/other metadata are overwritten by migration.

That improvement does not close the multi-ledger durability root: the field-specific reference publication still occurs after the externally durable/destructive move and is not protected by a durable migration intent or restart reconciliation protocol.

`HistoryReferenceMutationCoordinator.withLock` remains useful in-process serialization but is process-local and therefore cannot bridge process death between filesystem publication/source deletion and Room reference publication.

## Root/count reconciliation

- Keep `BUG-MIGRATION-01` as the existing P2 root; count delta `0`.
- Cache-remediation changes in the intervening range neither close nor split this root.
- Do not revive the obsolete full-row metadata-overwrite subcase.

## Stable correction boundary

A future correction still requires an exact durable migration carrier or equivalent protocol established before irreversible source removal, bound to History/reference identity, expected old path, chosen destination identity, and an operation generation sufficient for restart-safe replay. Reference publication should use expected-current identity/CAS or equivalent validation. Recovery must cover raw rename, raw copy, SAF publication, multiple History references to one source, destination collision naming, and process death/failure at each durable boundary without guessing from filesystem enumeration.

INDEPENDENT EXECUTION: NOT EXECUTED