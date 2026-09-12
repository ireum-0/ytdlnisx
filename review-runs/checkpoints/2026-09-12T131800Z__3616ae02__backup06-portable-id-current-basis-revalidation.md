# BUG-BACKUP-06 — portable imported-ID authority exact CLEAN-basis revalidation

Date: 2026-09-12

## Exact review state

- Fixed independently CLEAN implementation basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Active implementation/review-fix work after `e7355f5440d3a13cd8857f6cb5f15c242253cce2` remained frozen and was not inspected or relied on.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F6 / `BUG-BACKUP-06`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Prior F6 checkpoint: `0befe2a5c1638509ede6fd918b30043e2d44f0e3` at implementation basis `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- F6 remains a hard prerequisite for F7/F8/F9 identity-dependent backup work and for F11 / P0 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-06` remains OPEN / CONFIRMED at exact CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- Overall remediation state remains `NOT_CLEAN`.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.

## Intervening-range verification

The exact implementation range `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c..3616ae02e56995e795cc52f3074d8c3d1cd2e330` is three commits ahead / zero behind. Its changed files are cache/storage/worker-related and do not include `SettingsViewModel.kt` or the backup restore identity-remapping path.

The exact final `3616ae02...` production source was re-read directly.

## Exact production evidence

### 1. Restored Download Observe provenance still accepts destination numeric coincidence

`restoreData()` creates `restoredObserveSourceIdMap` when Observe Sources are actually restored. However `remapRestoredDownload()` still handles positive backup-local `oldSourceId` as follows when `data.observeSources == null`:

- use `restoredObserveSourceIdMap[oldSourceId]` if available;
- otherwise query the destination database with `observeSourcesRepository.getByIDOrNull(oldSourceId)?.id`;
- cache and persist that destination numeric ID as the restored Download's `observeSourceId`.

No stable Observe-source semantic identity is proven before accepting the destination row.

Concrete durable collision remains:

`backup Download.observeSourceId = N`
→ Observe Sources category omitted from the import
→ destination already has an unrelated Observe Source whose local primary key is `N`
→ numeric lookup succeeds
→ imported Download persists `observeSourceId = N`
→ durable provenance is falsely attributed to the unrelated destination source.

For a `WaitingForMembership` Download, a missing source mapping can fail closed to Error, but the same-number collision bypasses that branch because the unrelated destination row is treated as mapped provenance.

### 2. Legacy Observe-source keyword assignment still preserves unmapped backup-local numeric authority

For `HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE`, restore still computes:

`restoredObserveSourceIdMap[assignment.sourceId] ?: assignment.sourceId`

If no explicit old→new mapping exists, the backup-local numeric source ID is written directly into destination assignment state. It can therefore collide immediately with an unrelated destination source with the same local ID or persist unproven numeric authority for a later collision.

These are two consumers of the same F6 semantic root, not separate blockers.

## Governing identity relation

Database primary keys are database-local identifiers, not portable semantic identities. Imported references require an explicit old→new map or a proven stable semantic identity contract. Numeric equality alone cannot authorize destination relationships. Where identity cannot be proven, the owning feature must clear, refuse, quarantine, or otherwise fail closed rather than bind to unrelated destination state.

## Root/count reconciliation

This exact-basis review confirms the already-counted F6 root. It adds no blocker and changes no canonical count.

The broader restore-wide transaction/rollback/recovery problem remains owned separately by P0 `BUG-BACKUP-03`.

## Correction boundary retained

A correct F6 implementation must:

- inventory imported DB-local numeric references and ID-bearing preferences;
- replace raw backup→destination numeric reuse with explicit mapping or semantically proven identity;
- preserve legitimate source merges only where semantic identity is actually established;
- clear/refuse/quarantine unproven references without mutating unrelated destination rows;
- preserve `WaitingForMembership` provenance only when source identity is proven;
- map or discard legacy Observe-source keyword-assignment provenance instead of carrying backup-local IDs forward;
- define the portable/transient preference policy consumed by downstream F7/F8/F9 work.

Focused scenarios remain: same-ID collision with source payload omitted; explicit mapped-source restore; missing referenced source; unrelated same-ID source during Merge; WaitingForMembership provenance; legacy assignment mapped/unmapped collision; transient preference exclusion.

## Dependency consequence

F6 remains OPEN. Because F7/F8/F9 consume the F6 portable/transient-ID policy, they are not dependency-eligible implementation targets while F6 is unresolved. The next dependency-eligible backup prerequisite exploratory target is F10 / `BUG-CLEANUP-01` unless the active review-fix completion report arrives first and preempts exploration.

INDEPENDENT EXECUTION: NOT EXECUTED
