# BUG-BACKUP-06 — portable imported-ID authority current-basis revalidation

Date: 2026-09-12

## Exact review state

- Fixed independently CLEAN implementation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Current CACHE-02 verification-only target remains exact implementation SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`; this exploratory decision does not rely on any later implementation code.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F6 / `BUG-BACKUP-06`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- F6 is a hard prerequisite of downstream backup portability/relationship work and F11 / P0 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-06` remains OPEN at exact fixed CLEAN basis `93d01d2a...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 31**.
- Overall remediation state remains `NOT_CLEAN`.
- CLEAN Review Basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

## Intervening-range verification

The prior F6 current-basis checkpoint used exact basis `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

The exact range `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71..93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` changes only `AutomaticKeywordRuleEngine.kt` and its focused persistence test. It does not modify the backup restore identity mapping path in `SettingsViewModel.kt`.

The exact `93d01d2a...` production source was nevertheless re-read.

## Exact current production evidence

### 1. Restored Download Observe provenance still accepts destination numeric coincidence

`restoreData()` correctly creates `restoredObserveSourceIdMap` when Observe Sources are actually restored, using an explicit old→new mapping.

However `remapRestoredDownload()` still treats an imported Download with positive `oldSourceId` and omitted `data.observeSources` as follows:

- first look in `restoredObserveSourceIdMap`;
- if no mapping exists and Observe Sources were omitted, call `observeSourcesRepository.getByIDOrNull(oldSourceId)?.id` against the live destination database;
- cache that numeric result and use it as restored provenance.

No stable source URL, managed/source semantic key, configuration identity, or other portable identity is compared before assigning that destination row ID.

Concrete collision remains:

`backup Download.observeSourceId = N`
→ backup does not include Observe Sources
→ destination already has unrelated Observe Source with local primary key `N`
→ numeric lookup succeeds
→ imported Download persists `observeSourceId = N`
→ durable provenance is falsely attributed to the unrelated destination source.

For a `WaitingForMembership` Download, a missing mapping can force Error. A same-number collision bypasses that fail-closed branch because the unrelated destination row is accepted as if it were the imported source.

### 2. Legacy Observe-source keyword assignment still preserves an unmapped backup-local numeric ID

For `HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE`, restore still computes the destination source ID as:

`restoredObserveSourceIdMap[assignment.sourceId] ?: assignment.sourceId`

If the referenced Observe Source was not part of the imported/mapped source set, the backup-local numeric primary key is written directly into destination assignment state.

That can immediately collide with an unrelated destination source carrying the same local ID, or remain as unproven future collision authority.

These are two consumers of the same F6 portable numeric-reference authority defect and therefore remain one canonical root.

## Governing identity relation

Numeric primary keys from a backup database are location-local identifiers, not portable semantic identities.

For every imported DB-local reference:

- when the target entity is part of the import, binding must use an explicit old→new destination mapping;
- when the target entity is omitted, existing destination state may be reused only after stable semantic identity is proven under that entity's real identity contract;
- numeric equality alone cannot authorize the relationship;
- when proof is unavailable, the reference must be cleared, refused, quarantined, or otherwise fail closed according to the owning feature's semantics.

Transient/session-local numeric identities must not silently become part of the portable backup contract.

## Required correction boundary retained

F6 remains implementation-ready with the previously established scope:

- inventory imported DB-local numeric references and ID-bearing preferences;
- replace raw backup→destination numeric reuse with explicit mapped or semantically proven identity;
- preserve valid source merges where semantic identity really matches;
- clear/refuse/quarantine unproven references without mutating unrelated destination rows;
- preserve valid `WaitingForMembership` provenance only when its source identity is actually established;
- map or discard legacy Observe-source keyword assignment provenance rather than carrying backup-local IDs forward.

Focused scenarios remain: same-ID collision with source payload omitted; explicit mapped-source restore; missing referenced source; unrelated live same-ID source during Merge; WaitingForMembership provenance; legacy assignment mapped/unmapped collision; and transient preference exclusion.

## Dependency consequence

F6 remains OPEN and continues to block downstream F7/F8/F9 backup relationship/portability work from receiving a conflicting identity policy, and remains a hard prerequisite for F11 / P0 `BUG-BACKUP-03`.

The next exploratory target is F7 / `BUG-BACKUP-08`, with F6's unresolved portable-ID policy treated as an upstream constraint rather than silently assumed fixed. A CACHE-02 verification completion report preempts that exploration.

INDEPENDENT EXECUTION: NOT EXECUTED