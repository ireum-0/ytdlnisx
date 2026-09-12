# BUG-HISTORY-01 current-basis revalidation — 2026-09-12

## Scope

- Exact independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Root: F17 / existing P2 `BUG-HISTORY-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F17
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior revalidation: `5003d0daa60e22e5e3c16cf225e21baa452802a2` at basis `93d01d2a...`
- Exact implementation branch remains separately completed at `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`; this exploratory review uses only the independently CLEAN basis.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- F18 `BUG-KEYWORD-02` remains hard-dependent on F17's atomic `HistoryUndoSnapshot` boundary.

This is the already-counted F17 root, not a new blocker.

## Intervening-range check

`93d01d2a... -> 3616ae02...` contains three accepted commits, but none modifies the F17 production surfaces: `HistoryRepository`, `HistoryViewModel`, `HistoryFragment`, `HistoryKeywordAssignmentRepository`, `PlaylistDao`, or `PlaylistItemCrossRef`.

The exact final source was nevertheless reread at `3616ae02...`.

## Exact current-source evidence

### 1. Record deletion still splits playlist-reference and History-row durability

`HistoryRepository.deleteRecords(ids)` enters `HistoryReferenceMutationCoordinator.withLock` and calls `deleteRecordsWithinReferenceMutation(ids)`.

The latter still processes each batch with two separate DAO calls:

1. `playlistDao.deletePlaylistItemsByHistoryIds(batch)`;
2. `historyDao.deleteWithIds(batch)`.

There is no `database.withTransaction` spanning those mutations. The process-local reference mutex prevents participating in-process interleaving but cannot provide Room rollback or process-death atomicity between the two durable writes.

Concrete reachable sequence:

```text
History H belongs to playlists P1/P2
-> record deletion starts
-> PlaylistItemCrossRef rows for H commit deleted
-> process death / History DAO failure
-> History H remains durable
-> playlist memberships are durably absent
```

History existence and relationship state therefore do not converge atomically.

### 2. Bulk delete is another consumer of the same non-atomic relationship contract

`HistoryRepository.deleteAllRecords()` still executes, under only the same process-local reference mutex:

1. `playlistDao.clearPlaylistItems()`;
2. `historyDao.nuke()`.

Again there is no surrounding Room transaction. A failure/process death between these calls can remove all playlist membership while retaining History rows. This is the same F17 root, not a second blocker.

### 3. Ordinary Undo still does not own playlist membership

The non-file single-record deletion path in `HistoryFragment` still snapshots only keyword assignments via `historyViewModel.getKeywordAssignmentSnapshot(item.id)`, calls `deleteHistoryItems(..., deleteAssociatedFiles = false)`, and wires Snackbar Undo to:

`historyViewModel.restoreHistory(deletedItem, assignmentSnapshot)`.

`HistoryViewModel.restoreHistory(...)` still delegates to `HistoryKeywordAssignmentRepository.restoreHistory(item, assignmentSnapshot)`.

That repository method uses a Room transaction for the History row plus keyword-assignment restoration, but its API accepts no playlist-reference snapshot and performs no playlist-reference restoration.

Therefore a fully successful delete followed by immediate Undo deterministically restores the History row/assignments while losing the prior playlist memberships.

### 4. Crossref schema cannot reconstruct lost membership implicitly

`PlaylistItemCrossRef` remains a standalone entity keyed by `(playlistId, historyItemId)` with no declared foreign keys. `PlaylistDao` exposes explicit insert/delete operations. Re-inserting a History row cannot infer which playlists formerly referenced it.

### 5. File-backed deletion still converges onto the same DB primitive

`HistoryViewModel.executePreparedHistoryFileDeletion(...)` keeps the stronger filesystem/reference validation path, computes the revalidated `removableRecordIds`, then calls:

`repository.deleteRecordsWithinReferenceMutation(removableRecordIds.toList())`.

That final DB relationship mutation is still the same non-transactional playlist-ref-delete -> History-delete sequence. Preserve the current file ownership/reference validation, but it does not close F17.

## Governing checklist application

The current implementation fails the applicable v6 boundaries:

- multi-ledger durability/process-death: playlist crossrefs and History existence are separate durable writes;
- destructive/durable identity relation: membership state is part of the relationship being deleted/restored but is not carried by Undo;
- restore/re-entry: Undo cannot reconstruct the semantic state it claims to reverse;
- sibling/bulk consistency: bulk deletion uses the same split-durability contract.

The concrete impact is durable relationship loss, not a helper-only concern.

## Stable correction boundary

The Master Plan's F17 correction remains appropriate:

- introduce one typed `HistoryUndoSnapshot` owning at least the exact History row, keyword-assignment snapshot, and all `PlaylistItemCrossRef` rows for that History ID;
- capture relationship state and delete History + playlist refs in one Room transaction;
- restore History + owned snapshot relationship state atomically;
- route ordinary, bulk, and file-backed record-removal through the same relationship primitive;
- preserve current file ownership/reference validation and unrelated memberships;
- injected DB failure must roll back all DB relationship mutation; after external file effects, result/failure reporting must remain truthful.

No schema migration is inherently required by this root.

## Dependency consequence

F18 `BUG-KEYWORD-02` remains blocked from canonical remediation until F17 supplies the atomic Undo snapshot boundary. F18 will decide which assignment state is snapshotted versus recomputed from current rules inside that boundary.

INDEPENDENT EXECUTION: NOT EXECUTED