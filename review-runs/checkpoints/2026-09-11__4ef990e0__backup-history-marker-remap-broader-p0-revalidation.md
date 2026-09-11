# Historical BUG-BACKUP-01 — History replacement marker remap broader P0 revalidation

Date: 2026-09-11

## Exact review basis

- Independently CLEAN implementation basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Governing Master Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Broader-registry hypothesis source: `review/remediation:TASKS.md`

This review used only the fixed exact source basis while Luna continued a separate F13 implementation wave. No moving implementation commit/diff newer than `4ef990e0...` was inspected or relied on.

## Historical hypothesis

Historical P0 `BUG-BACKUP-01` described backup-local History IDs embedded in restored `history-redownload:<id>` and `history-redownload:<id>:quality:<height>` markers surviving restore without remapping. A stale numeric marker could then target an unrelated destination History row whose auto-generated ID happened to equal the backup-local ID, permitting wrong-row replacement and previous-media deletion.

The required invariant is that replacement identity survives database remapping and is revalidated against current semantic source/type before destructive replacement.

## Exact-source revalidation

### Restore builds an explicit old->new History ID map

`SettingsViewModel.restoreData(...)` inserts restored History rows with new IDs through `HistoryKeywordAssignmentRepository.insertHistory(...)` and records each mapping in `importedHistoryIdMap[oldHistoryId] = newHistoryId`.

### Every restored Download category passes one marker-remap boundary

`remapRestoredDownload(...)` is used for restored queued, scheduled, cancelled, errored, and saved Download rows before `DownloadRepository.insertRestoredDownload(...)` persists them.

That function passes the row's `playlistURL` and `importedHistoryIdMap` to `HistoryRedownloadMarker.remap(...)`.

### Regular and quality markers are remapped structurally

`HistoryRedownloadMarker.remap(...)` first parses the serialized marker. A parsed marker must resolve through `importedHistoryIdMap`; otherwise it returns `RestoreRemapResult.Unmappable`. A successful mapping reconstructs `HistoryRedownloadMarker(restoredHistoryId, expectedMinimumHeight)` and re-encodes it, preserving regular-vs-quality semantics while replacing the backup-local numeric ID.

A parsed marker is therefore never allowed to retain its backup-local History ID merely because that numeric row exists on the destination device.

### Unmappable markers fail closed

For `RestoreRemapResult.Unmappable`, `SettingsViewModel.remapRestoredDownload(...)` clears the replacement marker and restores the Download in an Error/fail-closed state with a typed issue rather than preserving stale numeric replacement authority.

Existing persisted replacement-refusal state is likewise reconciled; a mapped target may reconstruct an exact `HistoryReplacementBarrier` only when required operation/source data is available. It does not turn an unmappable backup-local marker into live replacement authority.

### Final replacement still revalidates semantic identity

`HistoryKeywordAssignmentRepository` does not authorize replacement from History ID alone. The final transactional replacement authorization compares the expected source URL against the current destination History row through `HistoryReplacementSourceIdentity.matches(...)` and separately enforces the expected media type.

A replacement candidate whose mapped/current target no longer represents the expected source yields `SourceMismatch`, and a type mismatch yields `TypeMismatch`, before the privileged replacement mutation.

The replacement factory output is also checked against the same expected source identity/type before the current History row is replaced.

## Verdict

**CURRENT HISTORICAL P0 NOT REPRODUCED / DO NOT PROMOTE `BUG-BACKUP-01`.**

The concrete wrong-row restore path described by the broader registry is closed at exact `4ef990e0...`: backup-local History IDs are structurally remapped for every restored Download category, unmappable markers fail closed, and final replacement authority is revalidated against current semantic source identity and type rather than numeric ID alone.

- Severity/count delta: `0`
- Canonical blocker count remains **P0 2 / P1 2 / P2 28**.
- CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.
- Overall project remains `NOT_CLEAN`.

## Root reconciliation

- This historical P0 is not `BUG-BACKUP-03`; the current P0 concerns reset-restore fail-safety/partial destructive commit across many stores and side effects.
- It is not `BUG-BACKUP-06`; that P2 concerns Observe Source/backup-local identity remapping in its own domain.
- It is not `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`; that P2 concerns stale Download-row selection/generation authority.
- The current source/type authorization does not close unrelated open backup findings; it only disproves this historical numeric History-marker corruption path.

A future restore path that persists a parsed History replacement marker without exact remapping, or a replacement path that accepts target ID without current semantic source/type validation, would regress this historical P0.

INDEPENDENT EXECUTION: NOT EXECUTED