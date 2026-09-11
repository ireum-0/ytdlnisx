# BUG-GROUP-TXN-01 — group transaction current-basis revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `5a34db9f6f2035f68f7c3c3a0f18e6d04dc10e7f` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 remains in progress; no implementation commit/diff newer than its frozen start was inspected or relied upon.

## Verdict

**NOT_CLEAN / existing P2 `BUG-GROUP-TXN-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change relation

The fixed-basis `aa1616a2... -> 9c5191c3...` F3 range does not modify the History UI group-lifecycle/relation-replacement paths or their DAO transaction boundaries. Exact current production calls were re-read before carrying the finding forward.

## Exact current production evidence

`HistoryFragment.kt@9c5191c3...` still implements one confirmed Playlist-group deletion as two separate writes for each id:

1. `playlistGroupDao.deleteMembersByGroup(id)`;
2. `playlistGroupDao.deleteGroup(id)`.

Keyword-group deletion likewise remains:

1. `keywordGroupDao.deleteMembersByGroup(id)`;
2. `keywordGroupDao.deleteGroup(id)`.

Youtuber-group deletion remains three separate writes:

1. `youtuberGroupDao.deleteMembersByGroup(id)`;
2. `youtuberGroupDao.deleteRelationsByGroup(id)`;
3. `youtuberGroupDao.deleteGroup(id)`.

No enclosing Room transaction is established by these UI paths, so process death/exception after an earlier destructive write can leave the group row alive with memberships/relations already durably lost, or otherwise persist only a prefix of the confirmed logical operation.

The same root remains in relation-set replacement. For an author's selected Youtuber groups, current code can call `deleteMembersForAuthorNotIn(author, selectedIds)` and then `insertMembers(members)` as separate writes. Parent-group replacement similarly calls `deleteRelationsForChildNotIn(childId, selectedParentIds)` and then `insertRelations(...)`. Failure between destructive and additive phases silently persists an incomplete replacement.

These are consumers of one established missing logical transaction/revalidated-authority root, not separate blocker counts.

## Correction boundary carried forward

1. Route each logical group deletion through one Room transaction or equivalent durable recoverable mutation protocol.
2. A logical delete must atomically remove the group and all group-owned memberships/relations, or preserve the pre-operation state.
3. Relation-set replacement formed by delete-not-in plus insert must execute under one logical transaction/revalidated boundary.
4. Validate live parent/child/member endpoints at the mutation boundary where required; stale UI snapshots must fail closed.
5. Prefer DAO/repository transactional entry points so UI code cannot reconstruct unsafe multi-write sequences.
6. Preserve root reconciliation with `BUG-PLAYLIST-TXN-01`: Playlist-group lifecycle evidence belongs here, while Playlist/History membership transaction semantics remain the separate playlist-domain root; do not double-count overlapping APIs.
7. Add deterministic rollback/failure coverage for Keyword, Youtuber, Playlist-group deletion and multi-write relation replacement.

## Root reconciliation

- `BUG-GROUP-TXN-01` remains one canonical P2 root.
- No new root or severity change is established.
- `BUG-PLAYLIST-TXN-01` remains distinct but overlapping APIs must not be double-counted.
- F3 / `BUG-OBSERVE-01` remains CLOSED and unrelated to this group transaction boundary.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED