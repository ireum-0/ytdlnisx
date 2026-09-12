# BUG-HISTORY-01 current-basis revalidation — 2026-09-12

## Scope

- Independently CLEAN review basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Root: F17 / existing P2 `BUG-HISTORY-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Task 004 `BUG-CACHE-01` implementation diff was not inspected.

## Verdict

**OPEN / NOT_CLEAN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 33**.
- CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- F18 `BUG-KEYWORD-02` remains semantically dependent on F17's atomic HistoryUndoSnapshot contract.

## Exact current-source evidence

### Record deletion separates playlist-reference mutation from History-row mutation

`HistoryRepository.deleteRecords(ids)` enters `HistoryReferenceMutationCoordinator.withLock` and calls `deleteRecordsWithinReferenceMutation(ids)`.

That inner helper processes ID batches with two sequential DAO calls:

1. `playlistDao.deletePlaylistItemsByHistoryIds(batch)`;
2. `historyDao.deleteWithIds(batch)`.

There is no surrounding `database.withTransaction` in this repository primitive. The process-local reference-mutation mutex prevents participating in-process competitors from interleaving, but it does not provide Room atomicity or process-death rollback between the two durable writes.

A concrete reachable durability window is therefore:

```text
History H belongs to playlists P1/P2
-> deleteRecords(H) enters reference mutex
-> PlaylistItemCrossRef rows for H are deleted and commit
-> process dies / second DAO write fails
-> History H remains durable
-> playlist memberships are durably gone
```

History existence and its playlist relationship state have diverged.

### Ordinary Undo snapshots only History keyword assignments

The single-record non-file deletion path in `HistoryFragment` captures:

- the `HistoryItem` itself already held by the UI;
- `historyViewModel.getKeywordAssignmentSnapshot(item.id)`.

It then calls `deleteHistoryItems(..., deleteAssociatedFiles = false)`, whose ViewModel path resolves existing IDs and delegates to `repository.deleteRecords(existingIds)`.

The Snackbar Undo action calls:

`historyViewModel.restoreHistory(deletedItem, assignmentSnapshot)`.

`HistoryViewModel.restoreHistory(...)` delegates to `HistoryKeywordAssignmentRepository.restoreHistory(item, assignmentSnapshot)`, whose transaction restores the History row and restorable keyword-assignment rows. No playlist-membership snapshot is accepted or restored by this API.

Therefore even a fully successful ordinary delete followed immediately by user Undo restores the record but not its prior playlist memberships.

This is a deterministic semantic loss, independent of the process-death window above.

### Crossref schema provides no automatic relationship reconstruction

`PlaylistItemCrossRef` is a standalone two-column Room entity with primary key `(playlistId, historyItemId)` and no foreign-key/cascade relationship declared in the entity. `PlaylistDao` exposes explicit crossref insert/delete operations. Restoring a History row alone cannot reconstruct which playlists formerly referenced it.

### File-backed deletion converges to the same non-atomic DB relationship primitive

`HistoryViewModel.executePreparedHistoryFileDeletion(...)` holds `HistoryReferenceMutationCoordinator.withLock` while it performs the prepared file-deletion flow, revalidates surviving/current record snapshots, computes `removableRecordIds`, and finally calls:

`repository.deleteRecordsWithinReferenceMutation(removableRecordIds.toList())`.

Thus the file-backed path uses the same sequential `delete playlist refs -> delete History rows` DB primitive, without a Room transaction spanning those two durable relationship mutations.

The file-deletion path does have stronger filesystem/reference revalidation than ordinary record-only deletion; that is useful and must be preserved. It does not close F17's relationship atomicity. A DB failure after file effects or between crossref/History writes can still leave relationship state inconsistent with the record-level outcome.

## Root/count reconciliation

These are the exact existing F17 `BUG-HISTORY-01` semantics:

- playlist-reference deletion and History deletion are not one Room transaction;
- Undo does not own the playlist relationship state it claims to reverse.

They are one existing P2 root, not separate new blockers. Count delta remains zero.

## Stable future correction boundary

Introduce one canonical atomic History deletion/Undo snapshot contract, consistent with the Master Plan's `HistoryUndoSnapshot`, owning at least:

- the exact History row;
- keyword-assignment snapshot required for non-RULE/manual state;
- all current `PlaylistItemCrossRef` rows for that History ID.

Required semantics:

1. capture relationship state and delete the History row + playlist refs in one Room transaction;
2. if any DB mutation fails, neither History existence nor playlist membership may partially commit;
3. ordinary Undo must restore the relationship state it captured, not merely the History row and keywords;
4. bulk record deletion must use the same relationship primitive, with sibling isolation appropriate to the chosen transaction scope;
5. file-backed deletion must compose with the existing filesystem/reference authority and use the same atomic DB relationship mutation once filesystem deletion has authorized record removal;
6. transaction failure after filesystem effects must be represented truthfully; do not report a complete record deletion when the atomic DB relationship mutation did not commit;
7. preserve current file ownership/reference revalidation and unrelated playlist memberships.

Focused regressions should cover:

- one History row in multiple playlists -> delete -> Undo restores all memberships;
- injected failure between relationship deletion and History deletion proves full DB rollback;
- unrelated playlist membership remains untouched;
- bulk delete with mixed memberships;
- file-backed deletion followed by DB failure reports incomplete/failure semantics without partial relationship commit;
- no-file ordinary Undo restores History + assignments + playlist refs together.

No schema migration is inherently indicated by F17; existing tables can support an atomic Room transaction and typed snapshot.

## F18 consequence

F18 `BUG-KEYWORD-02` must not be canonically remediated ahead of F17 because F18 requires the atomic Undo snapshot boundary to decide which assignment state is snapshotted versus recomputed from current rules.

INDEPENDENT EXECUTION: NOT EXECUTED