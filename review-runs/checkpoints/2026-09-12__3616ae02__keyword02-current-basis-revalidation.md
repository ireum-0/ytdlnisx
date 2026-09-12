# BUG-KEYWORD-02 — History Undo derived-rule recomputation current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Implementation branch: `checkpoint/pre-baseline-review`.
- P2 `BUG-DUPLICATE-ADMISSION-01` implementation wave is active from exact start SHA `3616ae02...`; no post-start implementation commit or diff was inspected or relied on.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Canonical classification for this already-counted root is P2.

## Verdict

**NOT_CLEAN — existing P2 `BUG-KEYWORD-02` remains OPEN at exact CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Exact current production evidence

### 1. History Undo restores stale RULE assignment snapshots instead of deriving from current rule state

`HistoryKeywordAssignmentRepository.restoreHistory(item, assignmentSnapshot)` restores the deleted History row and then derives `existingRuleIds` only by asking whether each snapshotted RULE source ID still has a rule row.

It then admits a snapshotted RULE assignment whenever its `sourceId` is still in that set and reinserts those assignment rows. The restore boundary does **not** re-evaluate the restored History item against the current rule's playlist/source matching state, discovery eligibility, configured keywords, current revision, or any other current rule semantics.

Therefore rule existence is treated as sufficient authority for restoring old derived membership, which is weaker than the required current-rule derivation authority.

### 2. Current-rule mutation between delete and Undo can make the restored derived state wrong in both directions

A concrete stale-positive sequence remains possible:

1. History H matches RULE A and therefore has a RULE-derived keyword from A;
2. H is deleted and its assignment snapshot captures that RULE assignment;
3. A remains present but is edited/revised so H no longer qualifies for A;
4. Undo restores H;
5. `restoreHistory()` sees that A's ID still exists and reinserts the old RULE assignment anyway.

The inverse stale-negative also remains possible: a new or newly-applicable current rule B that was not represented in the deletion snapshot is not discovered merely by restoring that snapshot, so Undo can omit a derived assignment that current rule state would now require.

Manual/user-owned assignment restoration is not the problem; the root is using the deletion-time snapshot as authority for derived RULE state.

### 3. The production rule engine already owns current-rule evaluation, but Undo does not invoke that authority

`AutomaticKeywordRuleEngine` contains the current automatic-rule application/reconciliation machinery. Other production flows use rule-engine reconciliation for current source/video identity and rule state. The History Undo restore path instead directly reinstates assignment rows from the old snapshot and does not compose a current-rule recomputation for the restored History item.

### 4. Existing instrumentation does not close the delete → rule mutation → Undo sequence

`AutomaticKeywordRulePersistenceTest.deletionUndoRestoresAssignmentSourcesWithoutPromotingRuleKeywords` snapshots a History item's assignments, deletes the item, and restores it. It then removes the rule assignments *after* restore and verifies the manual keyword remains manual.

That test is useful for assignment-source preservation, but it does not mutate current rule semantics between deletion and restoration and therefore does not prove the required `BUG-KEYWORD-02` invariant.

Other nearby tests cover current URL-change reconciliation, manual-vs-rule ownership, rule deletion/edit behavior, and restore/merge assignment provenance; none of those turn deletion-time RULE snapshots into a valid authority for current Undo-time derived membership.

## Root reconciliation

This is the same existing `BUG-KEYWORD-02`; do not increment the blocker count again.

It remains distinct from:

- CLOSED F12 `BUG-KEYWORD-01`, which owns keyword data/provenance correctness in its separately reviewed scope;
- CLOSED P2 `BUG-KEYWORD-04`, which owns its separate keyword-rule correctness boundary;
- History deletion/playlist/atomicity roots such as F17 `BUG-HISTORY-01`.

The defect here is specifically **temporal authority at Undo**: manual/user state may be restored from the deletion snapshot, while derived RULE state must represent the current rule universe at restoration time.

## Stable correction boundary

- Restore manual/user-owned keyword assignments from the deletion snapshot as authoritative user state.
- Do not treat deletion-time RULE assignment rows as authoritative current derived state.
- During Undo, recompute automatic RULE assignments for the restored History item against current enabled/current rule state and current matching/discovery eligibility semantics.
- A still-existing but no-longer-matching/revised rule must not regain its stale snapshot assignment.
- A currently applicable rule that was not in the old snapshot must be able to contribute its current derived assignment.
- Keep History-row restore, manual-assignment restore, derived-rule reconciliation, and materialized keyword projection transactionally coherent so interruption cannot expose a mixed old/new assignment state.
- Preserve assignment-source provenance: current automatic results stay RULE-owned and restored user choices stay MANUAL/user-owned.
- Add deterministic production regression coverage for at least: H has MANUAL + RULE A → delete H → mutate A so it no longer matches and make RULE B currently applicable → Undo → MANUAL survives, stale A is absent, current B is present. Include rule deletion/disable/revision variants where production semantics distinguish them.

INDEPENDENT EXECUTION: NOT EXECUTED