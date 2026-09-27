# BUG-HISTORY-04 clean-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 7dcbe664531203c16a8ffc7dba465b47654bb16e
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
live_completed_implementation_head: 31f55aca76efb1b956567fca5325b2575779a835
active_tooling_wave_inspected: NO

verdict: OPEN P2 / PARTIALLY FIXED / SAME-ROOT RESIDUAL CONFIRMED
new_finding_ids: 0
count_change: 0
canonical_p2: 17
primary_lens: L3 Concurrency & authority DEEP
independent_execution: NOT EXECUTED

## Closed subpart

The original title-only selector is no longer present. HistoryRepository.getDuplicateGroups() now groups only non-null HistoryDuplicateIdentity keys. HistoryDuplicateIdentity excludes title entirely, preserves DownloadType, recognizes stable official YouTube video identity, uses a strict canonical HTTP(S) identity for generic web sources, and fails closed for unsupported or ambiguous/local values.

Existing JVM and production-wiring tests cover same-title/different-source rejection, compatible YouTube source forms, type separation, ambiguous source rejection, and significant URL query/fragment distinctions.

## Same-root residual

HistoryViewModel.deleteDuplicates() still obtains duplicateGroups as a snapshot before the destructive boundary. It then calls mergeHistoryAssignments() separately for every removed row and only afterward calls deleteHistoryRecords() for the captured IDs.

The identity proof is therefore not revalidated at deletion time. A row can change url/type after grouping but before the final delete and is still deleted by ID. The public merge helper also commits each assignment merge in its own Room transaction, while deleteHistoryRecords() later opens a separate relationship-lock transaction for playlist/assignment/History removal.

A failure or process death after one or more merges but before delete can therefore expose a partially applied deduplication relationship state. Insert IGNORE makes re-merge bounded/idempotent for identical assignment keys, but that does not provide atomic completion or restore the missing destructive identity proof.

This is the existing BUG-HISTORY-04 root, not a new finding.

## Exact invariant

An automatic dedupe may delete candidate D in favor of retained R only if, inside the same final relationship-mutation transaction, both rows still exist and HistoryDuplicateIdentity.key(type,url) is non-null and equal for R and D. A stale precomputed group is never deletion authority.

Assignment merge and playlist/assignment/History mutation for every accepted duplicate must commit atomically with that identity proof. If identity is missing, changed, or ambiguous, preserve the row and all relationships.

## Narrow implementation boundary

Keep HistoryDuplicateIdentity as the identity policy. Move the destructive operation behind one repository/coordinator primitive that owns HistoryReferenceMutationCoordinator plus one Room transaction. Candidate discovery may occur inside that transaction or candidates may be passed in only as hints; current rows must be reread and regrouped/revalidated before any merge/delete.

Inside the transaction, choose the retained row deterministically by the existing time-then-id order, copy assignment rows using an in-transaction helper, materialize the retained keyword projection, delete the removed rows' playlist memberships and assignments, then delete only rows whose current identity still equals the retained current identity.

Do not add file deletion to this remediation. Do not broaden local/unprovable sources into destructive identity merely to increase dedupe coverage. Do not rely on title, display metadata, or pre-dialog snapshots.

## Acceptance matrix

- same title + different URLs: preserve both
- same official YouTube video identity + title variation + same type: eligible
- same identity + different DownloadType: preserve both
- cross-site same title: preserve both
- local/content/file-only ambiguous identity: preserve
- metadata race after candidate discovery: edit wins before transaction => candidate preserved; dedupe transaction wins first => later edit cannot resurrect/deform deleted identity
- keyword assignments: retained row receives removed-row assignments only for an identity still proven duplicate
- playlist relationships and History deletion: no partial state on injected exception between merge and delete
- retry after injected transaction failure: no broadened candidate set and no duplicate side effects
- process/re-entry semantics: rerun recomputes current identities; stale IDs alone never authorize deletion

The current relevant production blobs are identical on CLEAN basis and completed implementation 31f55aca. Active tools/remediation work was not inspected.