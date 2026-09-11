# BUG-BACKUP-06 — portable Observe-source ID current-basis revalidation

Date: 2026-09-11

## Exact basis
- CLEAN basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `review-runs/checkpoints/2026-09-11__aa1616a2__backup-portable-id-revalidation.md`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F6
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 is in progress; no implementation commit/diff newer than `5da8bc3354f6cbafd23d08dbc602530a983be6af` was inspected.

## Verdict
**NOT_CLEAN / existing P2 `BUG-BACKUP-06` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change relation
The `aa1616a2... -> 9c5191c3...` F3 changes are confined to source extraction / Observe / keyword authority paths and do not modify the backup restore mapping implementation in `SettingsViewModel`. The exact current restore path was nevertheless re-read before carrying the finding forward.

## Exact current source
`SettingsViewModel.restoreData()` still builds an explicit `restoredObserveSourceIdMap` when Observe Sources are included in the backup. That is the correct portable-reference pattern.

But when a restored Download has `observeSourceId = oldSourceId` and the backup omits `observeSources`, `remapRestoredDownload()` still falls back to destination numeric existence through `observeSourcesRepository.getByIDOrNull(oldSourceId)?.id` (cached after first lookup). Numeric row equality proves only that destination row N exists; it does not prove that backup-local source N and destination source N have the same semantic URL/configuration/identity.

The collision remains:

`backup Download.observeSourceId = N`
→ backup omits Observe Sources
→ unrelated destination Observe row also has local ID N
→ numeric lookup succeeds
→ imported Download is persisted as belonging to unrelated destination source N.

The legacy keyword-assignment restore path also still uses:

`restoredObserveSourceIdMap[assignment.sourceId] ?: assignment.sourceId`

for `LEGACY_OBSERVE_SOURCE`, allowing an unmapped backup-local numeric source ID to survive import and appear to refer to an unrelated same-number destination source.

These are consumers of the same F6 portability root, not separate findings.

## Correction boundary carried forward
- imported DB-local IDs are never portable identity by numeric equality alone;
- use explicit old→new mapping when the referenced entity is imported;
- otherwise require stable semantic identity proof before binding to an existing destination entity;
- clear/reject/quarantine an unsafe reference when no safe mapping exists;
- preserve valid semantic source merges and unrelated destination state;
- keep transient/session-local IDs outside the portable backup contract;
- regressions must cover same-ID collisions with source payload omitted, explicit mapped-source restore, missing source, Merge with unrelated live same-ID rows, waiting Download membership, legacy assignment remap, and transient preference exclusion.

## Root/dependency reconciliation
- `BUG-BACKUP-06` remains one canonical P2 root.
- `BUG-BACKUP-08`, `BUG-BACKUP-05`, and `BUG-BACKUP-07` remain downstream F6-dependent roots.
- `BUG-BACKUP-03` remains separate and restore-wide.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
