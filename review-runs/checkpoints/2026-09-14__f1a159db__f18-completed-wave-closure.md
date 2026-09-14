# F18 / BUG-KEYWORD-02 — completed-wave closure at f1a159db

## Scope

- implementation branch: `checkpoint/pre-baseline-review`
- completed review head: `f1a159db41f1281a31e1e06df486e4f67cdc3d89`
- implementation commit: `af5e9e80e53bef01e8d96b9ff5c5c56d522bad31`
- governing prerequisite: F17 / `BUG-HISTORY-01` atomic `HistoryUndoSnapshot`, previously CLOSED
- governing F18 invariant: manual/non-RULE state comes from the Undo snapshot; RULE-derived state comes from current rule authority.

## Exact-source review

The production record-Undo path still uses the F17 `HistoryUndoSnapshot` path: `HistoryFragment` invokes `historyViewModel.restoreHistory(undoSnapshot)`, and `HistoryViewModel.restoreHistory(snapshot)` delegates to `HistoryKeywordAssignmentRepository.restoreHistory(snapshot)`.

Inside `restoreHistory(snapshot)` the repository holds `HistoryReferenceMutationCoordinator` and one Room transaction while it:

1. rejects restoration if a replacement History row already occupies the captured ID;
2. inserts the captured History row with an empty materialized keyword projection;
3. restores only snapshot assignments whose source type is not `RULE`;
4. restores surviving playlist memberships;
5. derives the restored row's current video key;
6. queries current `automatic_keyword_rules` joined to current `automatic_keyword_rule_video_matches`, requiring both `r.enabled = 1` and `eligibleForAssignment = 1` for that video key;
7. for every currently eligible rule, reads the rule's current keyword rows and writes fresh RULE assignments;
8. materializes the final keyword union before the transaction commits.

No snapshot RULE row or snapshot numeric rule ID participates in the recomputation authority.

Current rule mutation semantics support the same conclusion: condition changes remove the old rule's assignments and video matches in the rule-save transaction; keyword edits replace the rule's current keyword rows; disabling removes the rule from `getEnabledRulesForVideoKey`; deleting a rule cascades its keyword/video-match rows. A deleted/recreated rule therefore contributes only through its current live rule/match/keyword state.

## F17 preservation

The same atomic snapshot restore boundary remains intact:

- non-RULE assignments are preserved from the snapshot;
- playlist memberships are restored only for surviving playlists;
- a replacement History row at the old ID is never overwritten;
- History/assignment/playlist/RULE recomputation is one Room transaction under the relationship mutation coordinator;
- existing regression coverage includes multi-playlist/non-RULE restoration, replacement-row rejection, delete failure rollback, restore failure rollback, edited RULE keywords, disabled/ineligible rule, deleted/recreated rule, and a newly eligible rule.

The older two-argument compatibility restore overload still restores surviving RULE IDs by numeric identity, but no current production Undo caller was identified on the exact completed path; the active History UI uses the typed `HistoryUndoSnapshot` overload. This is not counted as a current F18 blocker without a concrete production consumer.

## Disposition

- F18 / `BUG-KEYWORD-02`: **CLOSED_AT_F1A159DB / CLEAN for this finding scope**
- P2 count delta: `-1`
- canonical blocker total becomes **P0 2 / P1 0 / P2 20**
- F17 / `BUG-HISTORY-01`: preserved CLOSED through this reviewed path
- overall completed implementation SHA remains `NOT_CLEAN` because F10 is still OPEN and F20 has not yet been dispositioned
- contiguous independently CLEAN basis therefore remains `90afaec157607669ea32fa41877e7f0efcdcca86`

Next completed-wave review boundary: F20 / `BUG-LOCALADD-01` at exact SHA `f1a159db...`.

INDEPENDENT EXECUTION: NOT EXECUTED
