# Independent Track A checkpoint — HardSub generation + backup revalidation

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`

## NEW P2 — `BUG-HARDSUB-GENERATION-01`

### Production path

1. `ProcessingSettingsFragment` permits repeated `hard_sub_scan_now` clicks and calls `HardSubScanWorker.enqueueWithGeneration()` each time. The UI fences only late enqueue-result presentation with `latestHardSubHandoffId`; it does not disable a new scan while the prior worker is running.
2. `WorkManagerHandoffRecovery.prepareHardSub()` creates a fresh exact handoff/request generation using the fixed unique work name `hard_sub_scan_worker`; the WorkRequest input contains the exact `handoffId` and `requestId`.
3. `HardSubScanWorker` does not consume or validate that handoff/request generation before its durable scan mutations.
4. For each History candidate, the worker performs `countPendingByPlaylistMarker(marker)` and then separately calls `downloadDao.insert(downloadItem)`. This is not one atomic marker-reservation operation.
5. `DownloadItem` has no unique index on `playlistURL`/History-redownload marker, so two old/new scan consumers can both observe zero pending rows and then insert distinct Queued Download rows for the same regular History-redownload marker.
6. Download scheduler admission serializes live hard-sub execution but does not collapse duplicate queued markers. Its committed-History guard requires `history.downloadId == downloadItem.id`; after the first duplicate replacement commits, the second duplicate has a different Download id and therefore is not recognized as that committed Download. It can remain eligible for a second replacement attempt.

### Concrete impact

A superseded HardSub scan can publish durable work after a newer scan generation exists. A repeated Scan Now can therefore create multiple queued replacement Downloads for one History target and cause duplicate download/replacement work, including replacing a History item again after a prior hard-sub replacement has already succeeded.

### Required acceptance direction

- Consumer-side exact scan-generation validation must occur before candidate-state mutation and before publishing replacement work.
- The active generation must remain durably queryable for the worker lifetime; producer acceptance alone is insufficient if the carrier is deleted before consumer completion.
- Marker reservation / creation of the replacement Download must be atomic for the History-redownload semantic key (transactional reservation or an enforceable unique durable identity), so stale/current consumers cannot both publish the same target.
- Superseded workers must stop without mutating History hard-sub scan state or inserting replacement Downloads.

## Revalidated existing findings — remain OPEN

### F5 `BUG-BACKUP-02` — P2 retained

`restoreCustomThumbnails()` writes imported thumbnails to `restored_<backup-old-history-id>.<ext>`. Merge imports do not allocate a destination identity derived from the newly inserted History row. A later imported backup with the same backup-local History id can overwrite the file still referenced by a previously restored History row.

### F6 `BUG-BACKUP-06` — P2 retained

When restoring a Download with `observeSourceId > 0` while `observeSources` was not included in the backup, `remapRestoredDownload()` falls back to `observeSourcesRepository.getByIDOrNull(oldSourceId)?.id`. Backup-local numeric equality can therefore bind the restored Download to an unrelated current Observe source.

### F7 `BUG-BACKUP-08` — P2 retained

`BackupSettingsUtil.backupSettings()` stores `it.value!!::class.simpleName`, including portable SharedPreferences `Float`. Restore handles only `String`, `Boolean`, `Int`, or Set-like types and silently skips other type names. Fixed-basis production writes `terminal_zoom` as a Float in the default SharedPreferences, so the omission has a concrete persisted value.

### F8 `BUG-BACKUP-05` — P2 retained

Backup category code and UI values cover `queued`, `scheduled`, `cancelled`, `errored`, and `saved`; there is no `paused` category. `RestoreAppDataItem` also has no paused field. Persisted Paused Downloads cannot be represented by this backup/restore contract.

### F9 `BUG-BACKUP-07` — P2 retained

Playlist, History↔Playlist cross-reference, PlaylistGroup, and PlaylistGroupMember are persistent models, but the backup category list and `RestoreAppDataItem` provide no fields/categories for them. They are not carried indirectly by History serialization.

## Recount

Prior canonical working count after History Undo duplicate reconciliation: `P0 2 / P1 3 / P2 22`.

Add one distinct HardSub consumer-generation root: `P0 2 / P1 3 / P2 23`.

Review Basis remains fixed at `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
