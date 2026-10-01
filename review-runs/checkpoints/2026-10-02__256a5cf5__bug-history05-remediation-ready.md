# BUG-HISTORY-05 current-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 44a3f2e73f2764ac101117e1461d9333d62e1449
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 4
primary_lens: L4 Destructive ownership DEEP
supporting_lenses:
- L6 Cross-feature semantic propagation
- L3 Concurrency & authority
independent_execution: NOT EXECUTED

## Existing hardening to preserve

BUG-HISTORY-04's stale-candidate authority correction is materially present and
must remain intact.

Current duplicate cleanup:
- treats candidate IDs as hints;
- enters HistoryReferenceMutationCoordinator;
- performs one Room transaction;
- rereads current History rows;
- recomputes duplicate identity;
- retains the intended current row;
- rechecks exact duplicate identity immediately before destructive History deletion.

Current cleanup also transfers keyword assignments to the retained History row
inside the transaction.

The residual is not stale identity authority. It is loss of another durable
relationship class during a valid deletion.

## Current source result

For each valid duplicate D removed into retained R,
deleteDuplicateHistoryGroups() copies D's keyword assignments to R.

It does not read and retarget D's PlaylistItemCrossRef memberships to R.

The code then removes playlist items for D and deletes D.

Concrete valid-state example:
- R and D remain exact valid duplicates at final recheck;
- R belongs only to Playlist A;
- D belongs only to Playlist B;
- cleanup retains R and validly deletes D;
- B -> D is deleted;
- B -> R is never created.

The logical media survives, but the user's Playlist B membership disappears.

General History delete/undo code already treats playlist memberships as durable
relationship state by snapshotting/restoring them, so this is not a disposable
cache relation.

This root is distinct from BUG-HISTORY-04 because it reproduces when every
HISTORY-04 identity/revalidation condition succeeds.

## Exact invariant

When equivalent History rows are collapsed into one retained logical media row,
every durable relationship owned by a removed duplicate must either:

1. be intentionally transferred/merged onto the retained logical row according
   to the relationship's defined semantics; or
2. have an explicit product rule authorizing its removal.

For PlaylistItemCrossRef, no such removal rule exists in the reviewed production
contract. Therefore valid dedupe must preserve the union of playlist
memberships across the retained row and all duplicates being removed.

At final deletion:
- stale/nonduplicate rows remain untouched;
- retained row owns the complete intended membership union;
- removed duplicate owns no surviving cross-ref;
- the whole graph mutation remains transactional.

## Narrow implementation boundary

Keep the existing duplicate identity, retained-row selection,
HistoryReferenceMutationCoordinator and Room transaction.

Inside that same transaction, for every duplicate D that still passes current
identity revalidation:

- read D's current playlist memberships;
- map them to retained R;
- materialize the union on R;
- preserve R-only memberships;
- preserve D-only memberships;
- shared memberships remain one logical cross-ref;
- only after transfer, retire D's playlist refs and delete D.

Across multiple duplicates in the same group, retained R must end with the union
across the entire group.

Keep assignment transfer and playlist transfer inside the same rollback boundary
as History deletion.

Use existing PlaylistDao primitives when sufficient. Add only the minimum DAO
helper if current primitives cannot express the exact operation without losing
transactional clarity.

Preserve:
- current duplicate identity key;
- oldest/current retained selection policy;
- final identity recheck;
- keyword assignment materialization;
- stale-candidate protection;
- ordinary Playlist semantics outside dedupe.

## Forbidden shortcuts

- deleting duplicate playlist refs and assuming retained already has the same playlists
- copying only playlists common to all duplicate rows
- moving playlist relationships before current identity is revalidated
- moving relationships outside the existing Room transaction
- weakening BUG-HISTORY-04 current-row/final identity checks
- changing duplicate identity rules merely to avoid transfer
- deleting/recreating Playlist rows rather than transferring cross-refs
- swallowing relationship insert failure and still deleting History
- relying on REPLACE alone without explicitly constructing the intended union
- fixing the test fixture so both rows share the same playlist again
- broad PlaylistRepository refactoring unrelated to duplicate cleanup

## Acceptance matrix

- retained R belongs only to A; duplicate D belongs only to B; after cleanup R
  belongs exactly to A and B and D is gone
- R belongs to A/C; D belongs to B/C; after cleanup R belongs exactly once to
  A/B/C
- one retained row with multiple duplicates D1/D2 carrying different playlists;
  final retained row owns the union across all rows
- duplicate carries no playlists; retained memberships remain unchanged
- retained carries no playlists; duplicate-only memberships move to retained
- candidate D changes duplicate identity before final deletion; no relationship
  transfer or deletion for D commits
- candidate type/source identity changes under the existing stale-candidate
  fixture; its relationships remain on that row
- injected History deletion failure after relationship/assignment transfer begins
  rolls back retained memberships, assignments and duplicate deletion together
- injected playlist transfer failure prevents History deletion and leaves the
  original relationship graph intact
- keyword assignment union still behaves exactly as before
- repeated dedupe over already-converged state is idempotent
- production-wiring test exercises real Room transaction and PlaylistItemCrossRef
  rows, not only an in-memory set helper

## Test gap

Current HistoryDuplicateIdentityProductionWiringTest covers stale candidate
identity and transactional assignment/deletion behavior, but its relationship
fixture places retained and duplicate in the same playlist.

That fixture cannot detect loss of duplicate-only playlist membership.

No focused current-basis regression proves asymmetric membership union or
multiple-duplicate playlist union under the real duplicate cleanup transaction.

The root is confirmed and remediation-ready on the exact current basis.
