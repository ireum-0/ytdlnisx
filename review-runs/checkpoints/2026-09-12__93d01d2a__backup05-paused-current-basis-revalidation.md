# BUG-BACKUP-05 — paused Download backup/restore current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Implementation branch: `checkpoint/pre-baseline-review`
- Verification-only CACHE-02 wave remains active at exact implementation SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`; no post-basis implementation diff was used for this exploratory review.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F8 / `BUG-BACKUP-05`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Hard prerequisites F4 `BUG-BACKUP-04` and F6 `BUG-BACKUP-06` remain independently OPEN.
- F8 remains a hard prerequisite of F11 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-05` remains OPEN at exact canonical CLEAN basis `93d01d2a...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 31**.
- CLEAN Review Basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

The exact range `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71 -> 93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` is two commits ahead and changes only `AutomaticKeywordRuleEngine.kt` and `AutomaticKeywordRulePersistenceTest.kt`. It does not modify the F8 backup/restore schema or Download persistence paths. Exact `93d01d2a...` source was still re-read directly.

## Exact current production evidence

### 1. Paused Downloads remain durable application state

At exact `93d01d2a...`, `DownloadDao` still exposes explicit persistent `status='Paused'` queries including `getPausedDownloads()` and `getPausedDownloadsList()`, and `DownloadRepository` exposes `pausedDownloads`, `pausedDownloadsCount`, and includes `Paused` in its durable `Status` enum. This is not process-local/transient state.

### 2. Backup still has no Paused payload/category

`BackupSettingsUtil` has Download backup helpers for queued, scheduled, cancelled, errored, and saved rows, but no paused helper.

`SettingsViewModel.backup()` still defaults to `queued`, `scheduled`, `cancelled`, `errored`, and `saved`, and its category switch has no `paused` branch.

Therefore an all-category backup can omit durable Paused jobs entirely.

### 3. Restore model and pipeline still cannot represent Paused jobs

`RestoreAppDataItem` still contains queued, scheduled, cancelled, errored, and saved Download collections, with no paused collection.

`SettingsViewModel.restoreData()` has no `data.paused` branch and therefore no Reset/Merge path that can reconstruct portable Paused rows.

Consequently a backup cannot round-trip a Paused job with its persistent request/configuration/order/retry/operation state, and Reset cannot reconstruct the pre-backup paused set.

## Governing invariant and correction boundary

F8's plan invariant remains unmet: **all-category backups must preserve paused jobs and restore must never auto-start them.**

Once F4 typed capture and F6 portable-ID policy are established, F8 remains implementation-ready with the same constrained boundary:

- add Paused as an explicit portable Download payload/category under the selected self-describing backup capability contract;
- capture the complete persistent row fields required for later resume, subject to F6's portable/transient identity policy;
- restore as `Paused`, preserving legitimate stable queue/order/retry/operation metadata;
- never enqueue/start restored Paused rows as a restore side effect;
- Reset replaces selected paused state coherently; Merge imports without converting it to Queued/Active;
- repeated import follows the selected portable identity/collision policy and never treats backup-local numeric IDs as authority;
- coordinate any first real backup wire-format extension with F9 instead of independently bumping formats.

Required focused scenarios remain: paused-only backup, mixed-state all-category backup, Reset, Merge, repeated import, metadata/order preservation, F6 reference mapping, and explicit proof that no worker is enqueued for restored Paused rows.

## Dependency consequence

F8 remains OPEN and implementation-blocked by OPEN F4/F6. It remains a hard prerequisite blocking F11. The next exploratory boundary is F9 `BUG-BACKUP-07` current-basis revalidation.

INDEPENDENT EXECUTION: NOT EXECUTED