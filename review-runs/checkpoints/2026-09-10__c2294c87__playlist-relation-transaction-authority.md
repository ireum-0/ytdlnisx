# Independent Track A checkpoint — playlist relation transaction authority

Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
Verdict: `NOT_CLEAN`
Independent execution: NOT EXECUTED

This review remains pinned to the fixed contiguous independently-CLEAN basis and does not inspect in-progress implementation work.

## New `BUG-PLAYLIST-TXN-01` — CONFIRMED P2

`PlaylistRepository.deletePlaylist(playlistId)` implements one logical user operation as three independent DAO commits:

1. delete playlist↔History cross-references;
2. delete the playlist row;
3. delete playlist-group memberships for that playlist.

`PlaylistViewModel.deletePlaylist()` simply invokes that repository method on IO and does not provide an enclosing Room transaction or shared History/reference-mutation authority.

Concrete crash windows:
- process death after step 1 leaves the playlist alive while its entire History membership has been silently lost;
- process death after step 2 leaves the playlist absent while group-relation debt may remain.

The same semantic root appears in `PlaylistViewModel.applyPlaylistSelections()`: one confirmed UI operation may add multiple playlist memberships and remove multiple others via separate DAO/repository commits. A crash or exception can persist only a prefix of the requested selection change.

`PlaylistItemCrossRef` has a composite primary key but no foreign keys. The database therefore does not independently reject an orphan cross-reference if a stale/concurrent insert races playlist or History removal.

Do not infer definite numeric playlist-ID reuse from this finding; that was not required to establish the correctness failure.

Acceptance:
- execute one logical playlist deletion atomically across crossrefs, playlist row, and playlist-group membership, or use a durable multi-phase operation whose recovery owner converges every phase;
- execute a confirmed multi-playlist selection change under one transaction/revalidated relation-mutation authority;
- reject stale inserts unless both current playlist and current History identities exist at the mutation boundary;
- cover process death/failure after every prior partial-commit boundary and concurrent History/playlist deletion.

## Working independent recount

`P0 2 / P1 3 / P2 25`

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
Authoritative ledger unchanged.
