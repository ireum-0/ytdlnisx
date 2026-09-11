# BUG-GROUP-TXN-01 — exact-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Newer implementation diff used as exploratory evidence: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior root checkpoint: `review-runs/checkpoints/2026-09-10__c2294c87__group-relations-scheduler-consumer-generation.md`

## Verdict

**NOT_CLEAN — existing P2 `BUG-GROUP-TXN-01` is reconfirmed OPEN at `aa1616a2...`.**

This is an already-counted group-relation transaction root. Exact current-basis source also exposes Playlist-group deletion as another consumer of the same root; it is not counted separately.

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 3 / P1 3 / P2 25**
- CLEAN Review Basis remains: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Exact production evidence

### Keyword-group delete

`HistoryFragment` handles one confirmed logical keyword-group deletion by executing, for each selected group id:

1. `keywordGroupDao.deleteMembersByGroup(id)`;
2. `keywordGroupDao.deleteGroup(id)`.

`KeywordGroupDao` exposes these as independent DAO writes and has no transaction wrapper joining them.

A process death/ordinary failure after step 1 but before step 2 therefore leaves the group row alive while all of its memberships have already been durably destroyed.

### Youtuber-group delete

For one confirmed logical Youtuber-group deletion, `HistoryFragment` executes:

1. `youtuberGroupDao.deleteMembersByGroup(id)`;
2. `youtuberGroupDao.deleteRelationsByGroup(id)`;
3. `youtuberGroupDao.deleteGroup(id)`.

Again the DAO supplies independent writes, not one logical transaction.

Failure after either destructive prefix can leave a still-live group with silently lost authors and/or parent/child relations.

### Playlist-group delete — same root, additional current-basis consumer

Exact `aa1616a2...` source performs Playlist-group deletion as:

1. `playlistGroupDao.deleteMembersByGroup(id)`;
2. `playlistGroupDao.deleteGroup(id)`.

This has the same durable authority defect as the established Keyword-group deletion. It is another consumer/subcase of `BUG-GROUP-TXN-01`, not a new root.

## Relationship rows do not supply referential repair

At the reviewed basis:

- `KeywordGroupMember` has composite primary key `(groupId, keyword)` and no foreign key to `keyword_groups`;
- `YoutuberGroupMember` has composite primary key `(groupId, author)` and no foreign key to `youtuber_groups`;
- `YoutuberGroupRelation` has composite primary key `(parentGroupId, childGroupId)` and no foreign keys enforcing live parent/child group endpoints;
- `PlaylistGroupMember` has composite primary key `(groupId, playlistId)` and no foreign key enforcing a live Playlist-group endpoint.

Thus Room/SQLite referential actions do not make these multi-write logical mutations atomic and do not automatically repair/reject orphaned endpoint relations.

## Logical relation replacement is also prefix-durable

The same root exists in logical edit operations, not just delete:

- Youtuber membership replacement can execute `deleteMembersForAuthorNotIn(author, selectedIds)` and then `insertMembers(...)` as separate writes.
- Youtuber parent-group replacement can execute `deleteRelationsForChildNotIn(childId, selectedParentIds)` and then `insertRelations(...)` as separate writes.

Failure between the destructive first write and additive second write persists only a prefix of the user's one logical edit, silently losing previously selected relationships.

Single add-only/remove-only mutations are not by themselves the problem; the blocker is the absence of one crash-atomic/revalidated authority boundary when one user operation semantically replaces/deletes a relation set through multiple durable writes.

## Concrete impact

A confirmed group deletion or relation replacement can be interrupted after its destructive prefix and before logical completion:

`user confirms one operation`
→ `first relationship DELETE commits`
→ `process death / exception`
→ `later write never occurs`
→ UI-visible group or edited entity survives
→ previously durable membership/parent-child relation data is silently lost.

This is durable relationship data loss, not merely a transient UI inconsistency.

## Root reconciliation

Keyword, Youtuber, and Playlist group deletion are one semantic root because they share the same missing logical transaction/authority boundary over group-owned relationships.

Youtuber membership/parent replacement is also same-root evidence: the same destructive-prefix/additive-suffix pattern performs one logical relation-set replacement without crash atomicity.

Do not count these consumers separately.

This root is distinct from `BUG-PLAYLIST-TXN-01`, which owns Playlist/History membership and Playlist-group relation operations under its already-established playlist-domain transaction root. The Playlist-*group deletion* observation here is retained as group-lifecycle evidence; remediation should reconcile overlapping playlist-domain APIs so the same operation is not fixed twice or counted twice.

## Required correction boundary

- Route every logical group delete through one Room transaction or an equivalent durable recoverable mutation protocol.
- A delete must atomically remove the group and all group-owned member/relation rows, or leave the pre-operation state intact.
- Relation-set replacement that performs delete-not-in + insert must execute under one logical transaction/revalidated boundary.
- Validate live parent/child/member endpoints at the mutation boundary where the relation contract requires them; do not rely on stale UI snapshots.
- Prefer DAO/repository transactional entry points so UI code cannot accidentally reconstruct the unsafe multi-write sequence.
- Add deterministic fault/rollback coverage for Keyword and Youtuber deletion and the current Playlist-group consumer, plus relation replacement where multiple durable writes form one user operation.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review. Source-level production wiring was reviewed at the exact fixed basis.

INDEPENDENT EXECUTION: NOT EXECUTED
