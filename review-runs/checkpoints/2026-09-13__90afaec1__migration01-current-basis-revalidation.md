# BUG-MIGRATION-01 — exact CLEAN-basis independent revalidation

Date: 2026-09-13

## Exact reviewed state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior current-basis checkpoint: `8b7ac83bbf97a7fb44262e423b332bfc30d85b1a` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- The F10+F16+F17 implementation wave remains active; no in-progress implementation diff was inspected or used as evidence.

## Verdict

**NOT_CLEAN — existing P2 `BUG-MIGRATION-01` remains OPEN at exact canonical CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range reconciliation

Exact compare `3616ae02... -> 90afaec1...` is sixteen commits ahead. It does not modify `FolderSettingsFragment.kt`, so the migration producer is unchanged across that range; exact `90afaec1...` source was nevertheless re-read directly.

Later duplicate-admission/backup changes do not introduce a durable video-folder migration journal, intent, generation, or restart reconciliation protocol.

## Exact current production path

The production path remains:

`FolderSettingsFragment -> migrateDefaultVideoFolderInternal() -> moveFileToDestination() -> raw/SAF external mutation -> HistoryDao.updateDownloadPathById()`.

`migrateDefaultVideoFolderInternal()` still owns only a process-local `linkedMapOf<String,String>` named `movedPathMap` for old-path -> destination reuse during the current run.

For each History path, a previously unseen source is moved first. Only after the helper returns the destination does the process-local map record that mapping, and only after the item's new path list is assembled does the code call `historyDao.updateDownloadPathById(item.id, updatedPaths)`.

Raw copy fallback copies to the destination and deletes the source before returning the destination path. The SAF path creates/writes the destination document and likewise deletes the source before returning the destination URI. Raw rename/move has the same external-before-Room durability ordering.

There is still no durable old-path/new-path migration intent established before irreversible source removal.

## Concrete durable failure sequence

1. History row H durably references old path A.
2. Migration selects A and chooses destination B.
3. The external rename/copy/publication succeeds; copy/SAF paths remove A before returning success.
4. Process death or Room failure occurs before `updateDownloadPathById(H, B)` commits.
5. Durable H still references A while B exists and A may already be absent.
6. `movedPathMap` disappears with the process.
7. A restart has no exact durable H/A -> B ownership fact from which it can safely complete or roll back the migration.
8. Rerun candidate discovery can skip the stale H because its old source path is no longer present, leaving the durable reference stranded.

The same root remains for multiple History rows sharing A: one reference may commit B while another remains at A before process death erases the process-local mapping.

## Preserved narrowing

This checkpoint does not revive the historical stale full-row metadata overwrite subcase. The current migration publishes only `downloadPath` through `updateDownloadPathById`; unrelated History fields are not claimed to be overwritten here.

`HistoryReferenceMutationCoordinator.withLock` is useful in-process serialization, but process-local locking cannot bridge process death between external mutation and Room reference publication.

## Root/count reconciliation

- Same existing P2 `BUG-MIGRATION-01`; count delta `0`.
- No later reviewed change closes or splits this root.
- Do not count the obsolete full-row metadata-overwrite subcase separately.

## Stable correction boundary

A future correction still requires an exact durable migration carrier or equivalent restart-safe protocol established before irreversible source removal, bound to History/reference identity, expected old path, chosen destination identity, and an operation generation.

Reference publication must use expected-current identity/CAS or equivalent validation. Recovery must cover raw rename, raw copy, SAF publication, multiple History references to one source, destination collision naming, and process death/failure at each durable boundary without guessing from filesystem enumeration.

INDEPENDENT EXECUTION: NOT EXECUTED