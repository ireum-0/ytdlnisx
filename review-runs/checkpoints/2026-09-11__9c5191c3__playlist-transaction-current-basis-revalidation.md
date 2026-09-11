# BUG-PLAYLIST-TXN-01 — playlist transaction current-basis revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `2c00bf9e66c2c8d8a126697f94b56d88accd76b9` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 remains in progress; no implementation commit/diff newer than its frozen start was inspected or relied upon.

## Verdict

**NOT_CLEAN / existing P2 `BUG-PLAYLIST-TXN-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change relation

The fixed-basis `aa1616a2... -> 9c5191c3...` range is the F3 SourceSnapshot / Observe / automatic-keyword change set and does not modify playlist repository/viewmodel/cross-reference transaction boundaries. Exact current playlist source was re-read before carrying this finding forward.

## Exact current source

`PlaylistRepository.deletePlaylist(playlistId)@9c5191c3...` still performs three independent mutations in order:

1. `playlistDao.deletePlaylistItemsByPlaylistId(playlistId)`;
2. `playlistDao.deletePlaylist(playlistId)`;
3. `playlistGroupDao.deleteMembersByPlaylist(playlistId)`.

No enclosing Room transaction is established by `PlaylistRepository` or `PlaylistViewModel.deletePlaylist()`. A crash/failure between those operations can therefore persist a prefix of one confirmed deletion operation.

`PlaylistViewModel.applyPlaylistSelections()` still loops each `addPlaylistId` and calls `repository.insertPlaylistItems(...)`, then loops each `removePlaylistId` and calls `repository.removePlaylistItems(...)`. The UI-confirmed selection mutation remains a sequence of independently committed mutations rather than one all-or-nothing relation change.

`PlaylistItemCrossRef@9c5191c3...` still has only composite primary keys/indices and no Room foreign keys. The repository insert path constructs and inserts crossrefs without a mutation-boundary proof that both current playlist and History parents still exist. Thus stale/concurrent relation publication can still admit orphan membership.

## Correction boundary carried forward

1. One logical playlist deletion must be atomic across playlist-item crossrefs, playlist row, and playlist-group membership, or be a durable recoverable operation that converges all phases.
2. One confirmed multi-playlist add/remove selection must be atomic or equivalently recoverable as one semantic operation.
3. Relation insertion/mutation must require current existence/identity of both referenced parents at the mutation boundary; stale callers fail closed.
4. If foreign keys are introduced, review migration/cascade semantics explicitly; FK enforcement alone does not replace the all-or-nothing selection transaction.
5. Preserve intended idempotent duplicate-membership behavior.
6. Regression coverage should exercise failure/process-death at former commit boundaries and concurrent parent deletion versus stale relation insert.

## Root reconciliation

- `BUG-PLAYLIST-TXN-01` remains one canonical P2 root.
- No new root or severity change is established.
- F3 / `BUG-OBSERVE-01` remains CLOSED and unrelated to this relation-transaction boundary.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED