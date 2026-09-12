# BUG-BACKUP-06 — portable imported-ID authority current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Prior current-basis checkpoint: `2026-09-11__9c5191c3__backup-portable-id-current-basis-revalidation.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F6 / `BUG-BACKUP-06`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- F6 is a hard prerequisite of F7/F8/F9 and F11.
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-06` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

- Canonical blocker-count delta: `0`
- Canonical blocker count remains **P0 2 / P1 1 / P2 34**.
- CLEAN Review Basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range relation

The exact `9c5191c3... -> 9edd3e23...` changes do not modify the backup restore mapping implementation in `SettingsViewModel.kt`. Exact current source was re-read and the F6 consumers below remain present.

## Exact current production evidence

### 1. Restored Download Observe provenance can bind by destination numeric coincidence

When Observe Sources are included in the imported backup, `restoreData()` builds `restoredObserveSourceIdMap`, which is the correct explicit old→new mapping pattern.

However `remapRestoredDownload()` still handles a positive `oldSourceId` with `data.observeSources == null` by consulting the live destination database:

`observeSourcesRepository.getByIDOrNull(oldSourceId)?.id`

and caching that result.

No source URL, stable semantic key, configuration identity, or other immutable provenance is compared before the imported Download is assigned that destination row ID.

Concrete collision remains:

`backup Download.observeSourceId = N`
→ `backup omits Observe Sources`
→ `destination already contains unrelated Observe Source with local id N`
→ numeric lookup succeeds`
→ imported Download is persisted with `observeSourceId = N``
→ imported durable provenance now falsely attributes the Download to the unrelated destination source.

For `WaitingForMembership`, an absent mapping may force Error, but a numerically colliding live destination row is treated as a valid source and therefore bypasses that fail-closed branch.

### 2. Legacy Observe-source keyword assignment can preserve an unmapped backup-local numeric ID

For `HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE`, restore still resolves source identity as:

`restoredObserveSourceIdMap[assignment.sourceId] ?: assignment.sourceId`

If the referenced Observe Source was not imported/mapped, the backup-local numeric ID survives directly into the destination assignment.

An unrelated destination source with that same numeric ID can therefore appear to own the restored assignment. Even if no row currently has that number, the persisted backup-local ID remains an unproven destination identity that could collide later.

This is the same F6 portable-ID authority root, not a second blocker.

## Correction boundary

F6 remains implementation-ready as a prerequisite repair when selected:

- enumerate every imported DB-local numeric reference and every ID-bearing preference;
- when the referenced entity is imported, bind only through an explicit old→new destination map;
- when the entity is not imported, bind to existing destination state only after proving stable semantic identity under that entity's real identity contract;
- never accept equality of numeric primary keys across backup and destination as identity proof;
- when proof is unavailable, clear/reject/quarantine the reference according to the owning feature semantics rather than preserving stale numeric authority;
- keep transient/session-local numeric identities out of the portable backup contract;
- preserve valid semantic source merges and unrelated live destination state.

Required regressions remain: same-ID collision with source payload omitted, explicit mapped-source restore, missing referenced source, Merge with unrelated live same-ID source, WaitingForMembership Download provenance, legacy assignment mapping/unmapped collision, and transient preference exclusion.

## Dependency consequence

F6 is independently OPEN and continues to block downstream F7 `BUG-BACKUP-08`, F8 `BUG-BACKUP-05`, F9 `BUG-BACKUP-07`, and F11 `BUG-BACKUP-03` under the Master Plan dependency graph.

The next dependency-eligible exploratory boundary is F7 `BUG-BACKUP-08`, but any implementation of F7/F8/F9 must respect the unresolved F6 portable/transient ID policy rather than invent a conflicting schema.

The already-recorded Task 002 `BUG-KEYWORD-04` canonical replay remains the first implementation target unless an explicit workflow event changes that order.

INDEPENDENT EXECUTION: NOT EXECUTED
