# Independent Track A checkpoint — History delete/Undo

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Scope: F17 `BUG-HISTORY-01`, F18 `BUG-KEYWORD-02`
- Implementation branch was intentionally not inspected beyond the fixed CLEAN basis while a separate implementation wave was in progress.
- Authoritative ledger not modified.

## P2-N / F17 — CONFIRMED

Production reachability is established in `HistoryFragment`: record deletion snapshots keyword assignments, calls `deleteHistoryItems(..., deleteAssociatedFiles = false)`, then Snackbar Undo calls `restoreHistory(deletedItem, assignmentSnapshot)`. Playlist membership is not captured or passed to restore.

`HistoryRepository.deleteRecordsWithinReferenceMutation()` deletes playlist cross-references and then History rows, but the coordinator lock is not a Room transaction. `HistoryViewModel.executePreparedHistoryFileDeletion()` executes `HistoryFileDeletionEngine` first and only afterward removes DB records. `HistoryFileDeletionEngine.execute()` invokes the deletion gateway before DB record mutation.

Concrete impacts:
- record-only Undo can restore the History row/keywords but permanently lose prior playlist membership;
- file-backed deletion can irreversibly delete media and then encounter DB mutation failure, leaving partial History/relationship state;
- a failure between playlist-crossref deletion and History deletion can leave relationship state partially mutated.

Invariant: History existence, playlist membership, keyword/manual relationship state, and claimed Undo outcome must be one atomic/recoverable semantic mutation. File deletion requires a staged/compensated or otherwise durable protocol that cannot report/leave a successful destructive side effect followed by unowned DB failure.

Acceptance:
- capture a HistoryUndoSnapshot containing History row + keyword assignments + playlist refs;
- record-only capture/delete and restore use Room transaction;
- bulk/file-backed record removal routes through the same relationship primitive;
- file-backed deletion has explicit DB-failure convergence/compensation semantics;
- multi-playlist Undo restores exact prior memberships and unrelated memberships remain untouched.

## P2-O / F18 — CONFIRMED

The same production Snackbar Undo reaches `HistoryKeywordAssignmentRepository.restoreHistory()`.

Current restore logic treats historical RULE assignments as restorable whenever the old numeric rule ID still exists. It does not require the rule to remain enabled, preserve the old revision, condition, or current keyword set. Therefore editing or disabling an existing rule between delete and Undo can resurrect stale RULE-derived keywords from the snapshot.

Invariant: manual/non-RULE state may come from the Undo snapshot; RULE-derived state must be recomputed from currently enabled rules and current rule keywords/conditions at restore time. Numeric rule ID alone is not semantic authority.

Acceptance:
- restore non-RULE snapshot state only;
- recompute RULE assignments from current enabled rules under the same atomic Undo contract;
- edited/disabled/deleted/recreated rules cannot resurrect stale snapshot semantics;
- unchanged current rules still materialize their current assignment set.

## Recount

Prior canonical open P2 after the preceding independent wave: B, C, J, K, L, M = 6.

Add P2-N/F17 and P2-O/F18 => canonical working count `P0 0 / P1 0 / P2 8`.

No independent tests/device/emulator execution performed in this Track A checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
