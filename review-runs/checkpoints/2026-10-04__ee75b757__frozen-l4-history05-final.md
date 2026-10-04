# Frozen-basis L4 review — ee75b757 — FINAL

checkpoint_kind: ACTIVE_WAVE_FROZEN_BASIS_EXPLORATORY_FINAL
checkpoint_status: FINAL
manual_review_run: NO
implementation_agent_currently_working: YES
active_wave_scope: BUILD_ENVIRONMENT_LOCAL_PROPERTIES_STABILIZATION
active_wave_diff_inspected: NO
clean_review_basis: ee75b75786b8b6182dfc31946b6325b20294e73b
review_parent_sha: 9ac59c4d401e62171d3f3cdcc9121da38e6a0ee2
protocol_blob: 5c9d15602b08b1202571c48da37142cb8abebd0f

review_lens: L4 Destructive ownership
review_depth: DEEP
review_result: FAIL
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
new_finding_ids: NONE
count_change: 0
independent_execution: NOT_EXECUTED

## BUG-HISTORY-05

deleteDuplicateHistoryGroups() currently:
- rereads candidates under HistoryReferenceMutationCoordinator + one Room transaction;
- recomputes current duplicate identity;
- transfers duplicate keyword assignments to retained;
- deletes duplicate PlaylistItemCrossRef rows;
- rechecks identity immediately before deleting duplicate History.

It never reads duplicate playlist memberships and never inserts their union on
retained.

Counterexample:
retained R belongs only to Playlist A; valid duplicate D belongs only to
Playlist B. Cleanup keeps R and deletes D, but B->D is removed and B->R is
never created.

HistoryUndoSnapshot explicitly stores playlistMemberships and undo tests restore
them, proving playlist membership is durable History relationship state.

PlaylistItemCrossRef primary key is (playlistId, historyItemId), so the narrow
correction can map duplicate memberships to retained.id and insert the union
inside the existing transaction before duplicate relationship retirement.
Overlapping memberships remain idempotent.

Current HistoryDuplicateIdentityProductionWiringTest masks the defect because
seedDuplicatePair() puts retained and duplicate in the same playlist. Existing
rollback tests therefore do not prove asymmetric membership transfer.

Required correction preserves BUG-HISTORY-04:
- candidate IDs remain hints;
- current rows/identity are reread in the transaction;
- retained selection is unchanged;
- final identity recheck remains immediately before destructive deletion.

Required success/rollback cases:
- retained A + duplicate B -> retained A+B;
- retained A/C + duplicate B/C -> retained A/B/C;
- multiple duplicates -> full union;
- stale identity -> no transfer/delete;
- transfer or History-delete failure -> entire graph mutation rolls back.

## Other L4 re-proof

No reopened root was established in sampled closed destructive domains.

History file deletion revalidates the target before actual file/document delete.
History folder migration holds the relationship coordinator, updates path only if
unchanged, rereads references and source size/mtime before source deletion.
Terminal cache cleanup remains fail-closed for UNKNOWN/opaque ownership.
Bundled FFmpeg replacement retains durable journal/backup/exact-generation
rollback semantics before retiring prior runtime state.

This checkpoint supplies frozen-basis DEEP L4 evidence only. It does not create
or resume a manual-3 run and does not alter prior manual lens accounting.

INDEPENDENT EXECUTION: NOT EXECUTED
