# BUG-BACKUP-02 — restored-thumbnail staging collision revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna implementation wave is active.
- The active Luna implementation commits/diffs were not inspected, compared, reviewed, or relied upon.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-BACKUP-02`

At the fixed CLEAN basis, restore writes custom thumbnails into the live `custom_thumbs` directory before destination History IDs are allocated, using deterministic filenames derived from backup-local History IDs. Repeated Merge or different backups that reuse the same old History ID and extension can therefore overwrite a thumbnail already referenced by a prior successful import.

## Prior-root reconciliation

This is the same existing F5 root retained at older fixed basis `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d` in `review-runs/checkpoints/2026-09-10__c2294c87__track-a-backup-cleanup-date-recount.md`, which recorded repeated-Merge overwrite caused by backup-local History-ID thumbnail naming. Fresh exact-source review at `6763fb1b...` confirms the same production semantics remain.

## Exact-source evidence

### 1. Thumbnail materialization occurs before destination History insertion

`SettingsViewModel.restoreData(...)` starts by calling:

`restoreCustomThumbnails(data.customThumbnails)`

and only afterward iterates imported History rows and calls `insertHistory(historyItem.copy(id = 0L, ...))` to obtain each new destination History ID.

Therefore thumbnail paths are selected and files are written before the destination identifier that could safely namespace the imported row exists.

### 2. Restore writes directly into the live custom-thumbnail directory

`restoreCustomThumbnails(...)` uses:

- `baseDir = application.getExternalFilesDir(null) ?: application.filesDir`;
- `thumbDir = File(baseDir, "custom_thumbs")`.

This is not an isolated no-overwrite staging namespace. Restored files are materialized directly in the directory used for live custom-thumbnail references.

### 3. Filename authority is the backup-local History ID

For each backup thumbnail the exact filename is:

`restored_${item.historyId}.$extension`

where `item.historyId` is the backup-local History ID and the extension is sanitized.

The write path calls `outFile.writeBytes(decoded)` without an existence/no-overwrite guard. A later restore with the same old History ID and sanitized extension therefore targets the same file path.

### 4. Destination History ID is not used to rebind the file

After thumbnail restore, `restoreData()` inserts each History row with `id = 0L`, receives `newHistoryId`, and records `importedHistoryIdMap[oldHistoryId] = newHistoryId`.

However the custom thumbnail assigned to that new row is the already-materialized path from:

`restoredCustomThumbByOldHistoryId[oldHistoryId]`

There is no subsequent rename/copy/rebind to a fresh filename derived from the destination History ID or a collision-resistant import token.

### 5. Repeated Merge can mutate a prior successful import

Concrete production sequence:

1. Backup A contains old History ID `7` with custom thumbnail payload X and extension `jpg`.
2. First Merge writes live file `custom_thumbs/restored_7.jpg` and inserts a new destination History row, for example ID `101`, whose `customThumb` points to that file.
3. A repeated Merge of A, or a different Backup B whose backup-local History ID is also `7` with `jpg`, executes `restoreCustomThumbnails()` before its new History insertion.
4. It writes payload Y to the same `custom_thumbs/restored_7.jpg` path.
5. The already-live destination History row `101` still references that same path, so its thumbnail content changes even though it belongs to the earlier import.

Numeric destination History remapping therefore does not isolate restored thumbnail ownership.

### 6. Restore failure after the write does not compensate the staged/live file

`restoreData()` wraps the broader restore sequence in `runCatching` and ultimately returns `result.isSuccess`. No rollback/cleanup block removes or restores files already written by `restoreCustomThumbnails()` when a later preference/Room/relationship/download restore step fails.

Because the thumbnail write occurs first, a failed second Merge can overwrite the file referenced by a prior successful import and then return failure, leaving the older row's live thumbnail content changed.

The same ordering also means Reset begins materializing these files before its later History deletion/insertion steps, so restore failure can leave pre-commit filesystem mutation even when the database transition does not complete. The restore-wide atomicity/recovery implications remain owned by P0 `BUG-BACKUP-03`; the filename collision/early live staging mechanism here remains the distinct F5 `BUG-BACKUP-02` root.

## Governing correction boundary

The Master Plan F5 contract remains applicable:

- use collision-resistant, no-overwrite staging names that do not derive sole ownership from backup-local History IDs;
- do not make restored files live references before the destination History row is allocated and successfully bound;
- bind the staged thumbnail only through the explicit old→new History mapping / destination insertion result;
- clean staged artifacts on write/insertion/restore failure;
- repeated Merge and different backups reusing the same old numeric ID must not overwrite an existing imported thumbnail;
- preserve extension/content variants without accidental path aliasing;
- Reset must not expose pre-commit thumbnail mutation as a successful/live state.

Focused regressions should cover same old ID across backups, repeated Merge, same-ID same-extension different content, extension variants, thumbnail write failure, History insertion failure, later restore failure after thumbnail staging, and Reset.

## Root/count reconciliation

- Existing canonical P2 root `BUG-BACKUP-02` reconfirmed.
- Count delta: `0`.
- Canonical blocker count remains `P0 3 / P1 3 / P2 26`.
- P0 `BUG-BACKUP-03` remains distinct for restore-wide commit/recovery/atomicity; this checkpoint does not double-count the broader Reset transaction problem.
- The contiguous independently CLEAN Review Basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED