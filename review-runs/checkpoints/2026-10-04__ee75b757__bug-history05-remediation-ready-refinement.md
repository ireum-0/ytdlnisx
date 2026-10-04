# BUG-HISTORY-05 remediation-ready refinement — existing DAO path and rollback seam

checkpoint_kind: ACTIVE_WAVE_FROZEN_BASIS_EXPLORATORY_FINAL
checkpoint_status: FINAL
manual_review_run: NO
implementation_agent_currently_working: YES
active_wave_scope: BUILD_ENVIRONMENT_LOCAL_PROPERTIES_STABILIZATION
active_wave_diff_inspected: NO

review_parent_sha: 394bc37ae1e016061e53aaa4fc7f549e31ffbf6f
clean_review_basis: ee75b75786b8b6182dfc31946b6325b20294e73b
implementation_sha_reviewed: ee75b75786b8b6182dfc31946b6325b20294e73b

verdict: BUG-HISTORY-05 OPEN P2 / CONFIRMED / REMEDIATION-READY_REFINED
canonical_count_change: 0
independent_execution: NOT_EXECUTED

## Refined implementation boundary

The existing production mutation point is
HistoryKeywordAssignmentRepository.deleteDuplicateHistoryGroups().

It already:
- enters HistoryReferenceMutationCoordinator.withLock;
- opens one Room transaction;
- rereads current candidate rows;
- recomputes exact duplicate identity;
- selects the retained row;
- rechecks retained/duplicate identity before destructive History deletion;
- transfers keyword assignments inside the same transaction.

The missing operation is only durable PlaylistItemCrossRef transfer.

PlaylistDao already exposes all required primitives:
- getPlaylistItemsForHistory(historyItemId);
- insertPlaylistItems(items);
- deletePlaylistItemsByHistoryIds(ids).

No new DAO query/helper is required for the narrow correction unless exact-source
conditions materially change before implementation.

## Exact transfer sequence

For each duplicate that passes the current identity check:

1. reread the duplicate's current playlist memberships inside the existing transaction;
2. construct equivalent PlaylistItemCrossRef rows targeting retained.id;
3. materialize those rows on retained.id using the existing insert path;
4. shared retained/duplicate memberships remain one logical cross-ref;
5. preserve retained-only and duplicate-only memberships;
6. then retire the duplicate's playlist refs and keyword assignments;
7. preserve the existing final duplicate-identity recheck immediately before History deletion;
8. delete the duplicate History row only if all prior relationship/assignment work succeeded.

Across multiple duplicates in one group, repeated transfer must converge to the full
playlist-membership union on retained.id.

All transfer, assignment materialization, relationship retirement, final identity
recheck, and History deletion stay in the same Room rollback boundary.

## Failure/rollback contract

A playlist transfer failure is a hard transactional failure. It must not be swallowed
and must not allow duplicate relationship retirement or History deletion to commit.

Existing production-wiring infrastructure already demonstrates a useful deterministic
failure pattern with SQLite triggers. Focused coverage can inject a BEFORE INSERT
failure on PlaylistItemCrossRef for the duplicate-only membership being retargeted to
retained.id.

On that failure, assert the original graph remains intact:
- retained History remains;
- duplicate History remains;
- retained assignments remain unchanged;
- duplicate assignments remain unchanged;
- retained playlist memberships remain unchanged;
- duplicate playlist memberships remain unchanged.

The existing injected History-delete failure test should also be strengthened so that,
after playlist-transfer logic is added, its rollback assertion proves any provisional
retained membership union was rolled back together with assignments and deletion.

## Focused acceptance matrix

Required relationship cases:
- retained only A, duplicate only B -> retained exactly A+B;
- retained A+C, duplicate B+C -> retained exactly A+B+C with one C;
- retained none, duplicate B -> retained B;
- retained A, duplicate none -> retained A;
- retained plus multiple duplicates carrying disjoint/overlapping memberships -> retained
  exact union across the whole current duplicate group;
- repeated dedupe after convergence is idempotent.

Authority/staleness cases:
- duplicate URL/source identity changes before mutation -> no transfer and no deletion;
- duplicate type changes before mutation -> no transfer and no deletion;
- existing HISTORY-04 final identity recheck remains unchanged.

Rollback cases:
- injected playlist-transfer failure -> whole graph unchanged;
- injected History-delete failure after transfer/assignment work -> whole graph unchanged;
- no partial retained union may survive a failed cleanup transaction.

## Test fixture refinement

The current HistoryDuplicateIdentityProductionWiringTest uses one shared playlist for both
retained and duplicate rows. That symmetric fixture cannot expose BUG-HISTORY-05.

Add asymmetric membership fixtures while keeping real Room rows and the production
deleteDuplicateHistoryGroups() entrypoint. Do not substitute an in-memory collection helper.

Prefer extending the existing test class and its SQLite-trigger failure technique instead
of introducing a parallel harness.

## Forbidden expansion

- no duplicate identity policy changes;
- no retained-row selection changes;
- no weakening HISTORY-04 stale-candidate protection;
- no PlaylistRepository refactor;
- no playlist row recreation;
- no relationship work outside the existing transaction/relationship lock;
- no new DAO API unless current primitives prove insufficient.

## Disposition

The 2026-10-02 BUG-HISTORY-05 remediation-ready checkpoint remains valid and is refined
by this checkpoint. The implementation can be limited to the existing repository method
plus focused production-wiring tests, with no expected schema or migration change.

Canonical counts remain P0=0, P1=0, P2=3.

INDEPENDENT EXECUTION: NOT EXECUTED
