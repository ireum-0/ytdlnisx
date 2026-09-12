# BUG-MIGRATION-01 — current-basis independent revalidation

Date: 2026-09-12

## Exact reviewed state

- Independently CLEAN review basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Review mode: independent exploratory review while an unrelated BUG-KEYWORD-04 review-fix implementation wave is active
- Active implementation diff after `8e7f466c12bc4753c3e4bb048f7c02619f7d4c28` was not inspected or relied on
- Historical root: P2 `BUG-MIGRATION-01`
- Prior reviewed checkpoint recorded in canonical delta: `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- Governing plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN — existing P2 `BUG-MIGRATION-01` remains OPEN at exact basis `9edd3e23...`.**

- Canonical count delta: `0`
- Resulting canonical count remains **P0 2 / P1 1 / P2 34**.
- CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- This review narrows one historical subcase but does not close the root.

## Current production path

The real Folder-settings migration path is still:

`FolderSettingsFragment -> migrateDefaultVideoFolderInternal() -> moveFileToDestination() -> raw rename OR raw copy+source-delete OR SAF copy+source-delete -> HistoryDao.updateDownloadPathById()`.

`migrateDefaultVideoFolderInternal()` obtains the current video History rows and filters candidate paths that exist directly under the old default video directory. For each candidate it calls `moveFileToDestination()` before performing any durable History reference update.

For a raw destination, `moveFileToFileDirectory()` either:
- `renameTo(destinationFile)` and immediately returns the new absolute path; or
- copies to the destination, deletes the source, and returns the new absolute path.

For an SAF destination, `moveFileToContentTree()` creates the destination document, copies bytes, deletes the source, and returns the new document URI.

Only after one or more of those destructive external filesystem operations has succeeded does migration call:

`historyDao.updateDownloadPathById(item.id, updatedPaths)`.

There is no durable old-path -> new-path migration carrier persisted before source deletion in this production function. `movedPathMap` is only a process-local `linkedMapOf` used during the current invocation.

## Concrete crash/failure window

A reachable sequence remains:

1. History row H durably references old path A.
2. Migration chooses A and resolves destination B.
3. Raw rename succeeds, or copy to B succeeds and A is deleted, or SAF copy succeeds and A is deleted.
4. Before `updateDownloadPathById(H, ...B...)` commits, the process dies or that Room write throws.
5. Durable H still references A, but A no longer exists; B exists under a newly chosen raw filename or SAF document URI.
6. `movedPathMap` is lost with the process.
7. On a later user rerun, candidate construction checks `File(oldPath).exists()` and skips H because A is absent.
8. No exact durable mapping remains in this workflow from A/H to B, so rerun cannot deterministically repair H.

The multi-reference case exposes an additional durable window even if the first Room update succeeds. If H1 and H2 both reference the same old path A, this invocation uses `movedPathMap[A] = B` so H2 can be updated without moving the file again. If the process dies after H1 commits B but before H2 is processed/committed, H2 remains at A. On restart the process-local map is gone and A no longer exists, so H2 is skipped and remains stale.

The surrounding `HistoryReferenceMutationCoordinator.withLock` serializes cooperating in-process History reference mutations during the invocation, but it is a process-local mutex and is not a process-death recovery carrier. It does not bridge the filesystem-to-Room durability gap.

## Historical subcase narrowed by current source

The older finding also cited a stale full-row `HistoryDao.update(item.copy(downloadPath = ...))` that could overwrite unrelated concurrent History metadata. That exact subcase is no longer the current production evidence.

At `9edd3e23...`, migration calls the field-specific:

`UPDATE history SET downloadPath = :downloadPath WHERE id = :id`

via `updateDownloadPathById()`.

Therefore this review does **not** continue to claim that migration directly overwrites unrelated title/author/metadata fields through a stale full-row update.

However, the current field-specific update is still performed only after the old file has been irreversibly moved/deleted, and it is not an expected-old-path CAS. The durable publication/recovery root therefore remains open independently of the removed full-row-overwrite subcase.

## Governing invariant failure

The externally visible filesystem publication and the durable History reference are a multi-ledger semantic operation. Process death must be assumed between them. The current ordering allows the old authoritative file location to be destroyed before either:

- the new reference is durably committed; or
- a durable migration intent/mapping exists that restart can use to finish/rollback the operation.

Thus a successful external move can survive while the only application reference remains stale, and normal retry candidate construction excludes exactly that stale state because the old source no longer exists.

## Stable remediation boundary

A future correction must:

1. create an exact durable per-file/per-reference migration intent or equivalent recovery carrier before irreversible source removal;
2. bind that carrier to the History identity, expected old path, chosen destination identity/path/URI, and operation generation needed for safe replay;
3. ensure source deletion occurs only after the new History reference is durably published, or retain a compensating rollback protocol that can restore a valid old reference/file state;
4. reconcile process death for at least: old present/new absent, old present/new present, old absent/new present, and incomplete/failed destination copy;
5. support raw rename, raw copy fallback, and SAF destination publication under one coherent recovery contract;
6. handle multiple History rows referencing one source path so every intended reference is durably transferred or remains recoverable across process death;
7. use expected-current reference identity/CAS or equivalent current-row validation when publishing each reference, without reintroducing stale full-row metadata writes;
8. clean duplicate/staged destinations only when exact migration ownership is proven;
9. surface incomplete/failed migration truthfully rather than counting an external move as completed when its durable reference publication is unresolved.

## Future closure tests

Production-wiring coverage should fault-inject process death / persistence failure immediately after:

- successful raw rename;
- raw copy followed by source deletion;
- SAF copy followed by source deletion;
- first of two History references to the same old file being updated while the second remains old;
- destination collision resolution has chosen a non-original filename/URI but before that mapping is durable.

Restart/re-entry must recover an exact usable History reference without guessing by filename or filesystem enumeration. Tests should also preserve the current improvement that migration mutates only its owned reference field and must not regress into a stale full-row History update.

## Relation to current implementation wave

This checkpoint is independent of the active BUG-KEYWORD-04 review-fix wave and uses only fixed CLEAN basis `9edd3e23...`. It must not be interpreted as inspection or review of the in-progress implementation diff.

INDEPENDENT EXECUTION: NOT EXECUTED