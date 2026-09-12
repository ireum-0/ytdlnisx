# BUG-BACKUP-05 — paused Download backup/restore current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F8 / `BUG-BACKUP-05`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Hard prerequisites F4 `BUG-BACKUP-04` and F6 `BUG-BACKUP-06` are both independently OPEN at the current basis.
- F8 is a hard prerequisite of F11 `BUG-BACKUP-03`.
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-05` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

- Canonical blocker-count delta: `0`
- Canonical blocker count remains **P0 2 / P1 1 / P2 34**.
- CLEAN Review Basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Overall canonical state remains `NOT_CLEAN`.

## Exact current production evidence

### 1. Paused Downloads are durable production state

`DownloadDao` has explicit `status='Paused'` queries including `getPausedDownloads()` and `getPausedDownloadsList()`, and the repository/UI use Paused as a persistent Download state. This is not transient process-only state.

### 2. Backup has no Paused payload/category

`BackupSettingsUtil` provides backup helpers for History/downloads and the queued, scheduled, cancelled, errored, and saved Download categories. There is no paused-download backup helper or paused category serialization path.

`SettingsViewModel.backup()` likewise has no `paused` category branch.

Therefore an all-data backup can omit durable Paused jobs entirely.

### 3. Restore model and restore pipeline cannot represent Paused jobs

`RestoreAppDataItem` contains queued, scheduled, cancelled, errored, and saved Download collections, but no paused collection.

`SettingsViewModel.restoreData()` has no paused reset/delete/insert branch.

Consequently a backup cannot round-trip a Paused job with its configuration/order/retry/operation metadata, and Reset cannot reconstruct the pre-backup paused set.

This is the direct F8 root; no speculative fault window is needed.

## Required correction boundary

Once F4 typed capture and F6 portable-ID policy are established, F8 must:

- add Paused as an explicit portable Download payload/category under the chosen self-describing backup capability contract;
- capture the complete persistent row fields needed to resume later without changing the semantic request, subject to F6 portable/transient-field policy;
- restore rows as `Paused`, preserving stable queue/order/retry/operation metadata that is legitimately portable;
- never enqueue/start a restored Paused row as part of restore completion or generic post-restore worker startup;
- Reset must replace the selected paused state coherently; Merge must import without converting to Queued/Active;
- repeated import must follow the selected portable identity/collision policy and must not use backup-local numeric IDs as authority;
- coordinate wire-format/version capability with F9 rather than independently bumping a format for each payload extension.

Required focused scenarios: paused-only backup, all-category mixed states, Reset, Merge, repeated import, order/config/retry/operation metadata preservation, portable Observe/history references under F6, and explicit proof that no worker is enqueued for restored Paused rows.

## Dependency consequence

F8 remains OPEN and is blocked for implementation by OPEN F4 and F6. It also remains a hard prerequisite blocking F11.

The next dependency-eligible exploratory boundary is F9 `BUG-BACKUP-07` playlist/group backup/restore. F9's hard prerequisites are likewise F4 typed capture and F6 ID maps, so it may be revalidated now but must not be implemented against an unresolved F6 identity policy.

The separate Task 002 `BUG-KEYWORD-04` canonical replay remains the first implementation target unless an explicit workflow event changes that order.

INDEPENDENT EXECUTION: NOT EXECUTED
