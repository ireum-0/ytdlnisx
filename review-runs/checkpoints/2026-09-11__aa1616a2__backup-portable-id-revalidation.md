# BUG-BACKUP-06 — backup-local numeric ID authority revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA / contiguous CLEAN basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-BACKUP-06`

The restore path still permits backup-local numeric Observe-source IDs to acquire destination authority through numeric equality when an explicit old→new mapping is unavailable. That can bind imported Download/assignment state to an unrelated live Observe row in the destination database.

## Prior-root reconciliation

This is the same F6 root already retained by the older fixed-basis backup review: imported ID-bearing fields/preferences must use an explicit destination map or stable semantic identity proof; numeric equality alone is never portable authority. No new root is created here.

## Exact-source evidence

### 1. Explicit mapping exists only when Observe Sources are imported

`SettingsViewModel.restoreData(...)` builds `restoredObserveSourceIdMap` while processing `data.observeSources`. Each imported backup-local source ID is associated with the actual restored/live destination source ID selected through the Observe-source restore logic.

That explicit map is the correct portable-reference pattern when the referenced source payload is present.

### 2. Download restore falls back to destination numeric-ID existence

`remapRestoredDownload(item)` reads `item.observeSourceId` as `oldSourceId`.

When no explicit mapping exists and the backup does **not** include `observeSources`, the code queries:

`observeSourcesRepository.getByIDOrNull(oldSourceId)?.id`

and reuses that destination ID if a row with the same number exists.

The lookup proves only that the destination database happens to contain row N. It does not prove that backup-local Observe source N and destination Observe source N represent the same source URL/configuration/semantic membership.

The resulting value is copied into the restored Download's `observeSourceId` and is persisted by `downloadRepository.insertRestoredDownload(...)` for queued, scheduled, cancelled, errored, and saved payloads.

Concrete collision:

`backup Download.observeSourceId = 7`
→ backup omits Observe Sources
→ unrelated destination Observe row happens to have local ID 7
→ `getByIDOrNull(7)` succeeds
→ restored Download is persisted with `observeSourceId = 7`
→ imported Download is now attributed/bound to the unrelated destination Observe source.

This violates the F6 invariant that DB-local numeric references are not portable identity.

### 3. Legacy Observe keyword-assignment remap has another numeric fallback

For `HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE`, restore selects:

`restoredObserveSourceIdMap[assignment.sourceId] ?: assignment.sourceId`

If the explicit old→new source map has no entry, the backup-local numeric source ID itself survives import. A numerically equal but semantically unrelated destination row can therefore become the apparent source authority for the assignment.

This is another consumer of the same F6 root, not a separate blocker count.

### 4. The defect is distinct from Observe runtime generation authority

This backup portability defect concerns **cross-database identity during import**. It is distinct from P0 `BUG-OBSERVE-HANDOFF-01`, which concerns stale runtime generations/revocation inside a live installation, and from `BUG-OBSERVE-SOURCE-IDENTITY-01`, which owns current Observe semantic source identity where separately established.

## Governing correction boundary

The Master Plan F6 contract remains applicable:

- inventory every imported ID-bearing field and preference;
- use an explicit old→new destination map when the referenced entity is imported;
- otherwise require a stable semantic identity proof before binding to an existing destination entity;
- if no safe mapping exists, clear/reject/quarantine the reference according to the owning feature contract;
- never preserve a backup-local ID merely because the same numeric row exists in the destination database;
- preserve valid source-identity merges and unrelated destination state during Merge;
- exclude transient process/session-local ID state from the portable preference contract.

Focused regressions should include Observe-source numeric collisions with source payload omitted, explicit mapped-source restore, missing parent/source, Merge with unrelated live same-ID rows, waiting Download membership, assignment-source remapping, and transient preference exclusion.

## Root/count reconciliation

- Existing canonical P2 root `BUG-BACKUP-06` reconfirmed.
- Count delta: `0`.
- Canonical blocker count remains `P0 3 / P1 3 / P2 25`.
- `BUG-BACKUP-03` remains separately dependency-gated for restore-wide commit/recovery/atomicity.
- `BUG-BACKUP-08`, `BUG-BACKUP-05`, and `BUG-BACKUP-07` remain downstream F6-dependent roots and are not closed or recounted here.
- Contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED