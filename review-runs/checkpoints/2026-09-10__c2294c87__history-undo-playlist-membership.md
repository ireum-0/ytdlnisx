# Independent Track A checkpoint — History Undo playlist membership

Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
Verdict: `NOT_CLEAN`
Independent execution: NOT EXECUTED

This review remains pinned to the fixed contiguous independently-CLEAN basis and does not inspect in-progress implementation work.

## New `BUG-HISTORY-UNDO-PLAYLIST-01` — CONFIRMED P2

The production single-item History delete/Undo path is now confirmed.

`HistoryFragment` snapshots the `HistoryItem` and keyword assignments, calls `deleteHistoryItems(id, deleteAssociatedFiles=false)`, and exposes a Snackbar Undo action that calls `HistoryViewModel.restoreHistory(deletedItem, assignmentSnapshot)`.

The record-only delete path delegates to `HistoryRepository.deleteRecords()`. Under the History reference-mutation lock, that repository explicitly deletes playlist cross-references for the History ids before deleting the History rows.

The Undo restore path delegates to `HistoryKeywordAssignmentRepository.restoreHistory()`. That transaction restores the History row and authoritative keyword assignments, but it has no playlist-membership snapshot and performs no playlist cross-reference restoration. `HistoryDeletionRecord` is a file-target record and likewise carries no playlist relation state.

Concrete loss:
1. History H belongs to playlist P.
2. User deletes H without associated file deletion through the single-item UI.
3. `deleteRecords()` removes P↔H and H.
4. User taps Undo.
5. H and its keyword assignments return, but P↔H does not.

The UI reports an Undo while silently losing durable relationship data.

Acceptance:
- snapshot the exact playlist membership together with the History row and keyword assignments before destructive deletion;
- restore the row, surviving/restorable assignments, and only still-valid playlist memberships under the same History reference-mutation authority;
- do not recreate playlists that were independently deleted while the Undo was pending; validate current playlist identity/existence at restore time;
- cover no-playlist, one-playlist, multi-playlist, playlist-deleted-during-Undo, process/lifecycle recreation, and repeated Undo/idempotency behavior.

## Working independent recount

`P0 2 / P1 3 / P2 24`

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
Authoritative ledger unchanged.
