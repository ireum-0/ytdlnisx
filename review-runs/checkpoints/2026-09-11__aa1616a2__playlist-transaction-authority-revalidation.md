# BUG-PLAYLIST-TXN-01 — playlist relation transaction authority revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-PLAYLIST-TXN-01`

The exact CLEAN basis still implements one logical playlist relation mutation as multiple independently committed Room operations without one transaction/revalidated relation authority. Process death, exception, or concurrent deletion can therefore persist only a prefix of a confirmed user operation or admit orphan relation rows.

## Playlist deletion remains multi-commit

Exact `PlaylistRepository.deletePlaylist(playlistId)@aa1616a2...` executes, in order:

1. `playlistDao.deletePlaylistItemsByPlaylistId(playlistId)`;
2. `playlistDao.deletePlaylist(playlistId)`;
3. `playlistGroupDao.deleteMembersByPlaylist(playlistId)`.

`PlaylistViewModel.deletePlaylist()` merely invokes that repository method on `Dispatchers.IO`; it does not add an enclosing Room transaction.

The original crash windows therefore remain:

- death/failure after step 1 leaves the playlist row alive after silently losing all History membership;
- death/failure after step 2 leaves the playlist row absent while group-membership debt can remain.

## Confirmed multi-playlist selection mutation remains non-atomic

`PlaylistViewModel.applyPlaylistSelections()` still loops each `addPlaylistId` and calls `repository.insertPlaylistItems(...)`, then loops each `removePlaylistId` and calls `repository.removePlaylistItems(...)`.

Each underlying DAO call is its own mutation. A crash/exception can therefore persist only some adds, or all adds plus only some removals, even though the UI operation was confirmed as one selection change.

There is also no final relation-mutation revalidation tying the caller's earlier selection snapshot to current playlist/History existence.

## Cross-reference schema does not independently enforce parent existence

Exact `PlaylistItemCrossRef.kt@aa1616a2...` defines a composite primary key `(playlistId, historyItemId)` and indices, but no Room `ForeignKey` declarations.

Exact `PlaylistDao` inserts relation rows with `@Insert(onConflict = REPLACE)` and exposes no mutation-boundary query that proves both referenced playlist and History rows still exist before insertion.

Therefore a stale/concurrent insert can create an orphan relation after playlist/History deletion. Do not infer definite numeric ID reuse from this; orphan relation authority is sufficient to establish the current correctness gap.

## Verification gap

No focused current test was found that proves:

- playlist deletion is atomic across playlist-item crossrefs, playlist row, and group membership;
- multi-playlist add/remove selection is all-or-nothing;
- stale relation insertion is rejected after concurrent playlist/History deletion;
- process-death/failure at the prior partial-commit boundaries converges to one semantic outcome.

## Required correction boundary carried forward

1. Execute one logical playlist deletion atomically across playlist-item crossrefs, playlist row, and playlist-group membership, or use a durable multi-phase operation with a recovery owner that converges every phase.
2. Execute a confirmed multi-playlist selection change under one Room transaction or equivalent durable/recoverable semantic operation.
3. At relation insertion/mutation, require current existence/identity of both referenced playlist and History subject; stale callers must fail closed rather than create orphan membership.
4. If foreign keys are introduced, review migration and cascade semantics explicitly; foreign keys alone do not replace the all-or-nothing UI selection transaction.
5. Preserve existing idempotent duplicate membership behavior where semantically intended.
6. Add production-level tests for failure/process death between each former commit boundary and concurrent playlist/History deletion versus stale inserts.

## Root/count and basis reconciliation

- `BUG-PLAYLIST-TXN-01` was already counted as one P2 root.
- This exact-basis review reconfirms it; no new root is introduced.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is unchanged.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
