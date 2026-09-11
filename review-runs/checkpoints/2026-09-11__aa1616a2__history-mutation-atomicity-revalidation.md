# BUG-HISTORY-01 — History mutation/Undo atomicity revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna `BUG-OBSERVE-01` implementation wave is active.
- The active Luna implementation branch HEAD, commits, and diffs were not inspected, compared, reviewed, or relied upon for this decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-HISTORY-01` / F17

The fixed CLEAN basis still does not make History existence, playlist membership, keyword-assignment state, and file-backed record removal converge under one atomic mutation/Undo contract. The current Undo snapshot restores a History row plus keyword assignments but owns no playlist-reference snapshot, while record deletion removes playlist references separately from the History row. File-backed deletion also mutates external files before the corresponding Room record/reference deletion has durably committed.

This finding is about mutation/reference/Undo/atomicity **after a legitimate target set has already been selected**. It does not reopen the independently closed `BUG-HISTORY-DUPLICATE-IDENTITY-01` selection-identity root.

## Exact-source evidence

### 1. Record deletion removes playlist references separately from History rows

`app/src/main/java/com/ireum/ytdl/database/repository/HistoryRepository.kt` implements `deleteRecordsWithinReferenceMutation(ids)` by, for each ID batch:

1. `playlistDao.deletePlaylistItemsByHistoryIds(batch)`;
2. `historyDao.deleteWithIds(batch)`.

The surrounding `HistoryReferenceMutationCoordinator` is an application-level lock, not a Room transaction. `deleteRecords()` acquires that lock and then calls the same two-step primitive.

Therefore a database exception/process interruption between those DAO operations can leave playlist membership removed while the History row remains. Bulk/chunked deletion can additionally stop between batches with a partially applied set.

### 2. Current Undo snapshot does not own playlist membership

`HistoryViewModel` exposes only:

- `getKeywordAssignmentSnapshot(historyItemId)`; and
- `restoreHistory(item, assignmentSnapshot)`.

`HistoryKeywordAssignmentRepository.restoreHistory()` uses `db.withTransaction` to restore the History row and its keyword-assignment/materialized keyword state. Its restore contract accepts only:

- `HistoryItem`;
- `List<HistoryKeywordAssignment>`.

It neither captures nor restores playlist cross-reference rows.

Thus when the delete path has removed playlist membership, the current record Undo can restore the History row and keyword state without restoring the relationship state the deleted record previously had.

### 3. File-backed deletion mutates external state before Room record deletion

`HistoryViewModel.executePreparedHistoryFileDeletion()` executes the validated `HistoryFileDeletionEngine` first. After external deletion outcomes are known, it computes `removableRecordIds` and only then calls:

`repository.deleteRecordsWithinReferenceMutation(removableRecordIds.toList())`.

`HistoryFileDeletionEngine.execute()` performs the external file/document deletions and returns which History records may be removed. External filesystem/content-provider mutation cannot participate in the later Room atomic commit.

Consequently, after a file has been successfully deleted, a Room failure/process death before playlist-reference + History-row deletion can leave a durable History row/reference state pointing at media that is already gone. The current path has no durable intent/checkpoint that makes that cross-boundary operation crash-resumable or compensatable.

### 4. File-backed and record-only paths do not share one transactional relationship primitive

Record-only deletion eventually calls the repository's playlist-reference + History-row two-step delete. File-backed deletion performs external mutation first and then uses the same non-transactional DB primitive for removable records. Undo is separately implemented by the keyword-assignment repository and has no playlist-ref ownership.

These paths therefore do not satisfy the F17 requirement that capture/delete and restore share one authoritative `HistoryUndoSnapshot` relationship contract.

## Preserved positive behavior

The current code contains useful safeguards that this finding does not reject:

- History file deletion validates targets before deletion;
- retained-file references can exclude shared targets;
- record snapshots are revalidated around file deletion;
- failed/permission-denied/skipped external targets can prevent corresponding record removal;
- `HistoryReferenceMutationCoordinator` serializes relevant in-process reference mutations;
- duplicate-selection identity is separately fixed/closed and must remain preserved.

Those safeguards reduce wrong-target risk but do not provide Room atomicity or crash consistency across the relationship/Undo boundary.

## Root and alias reconciliation

This is the existing `BUG-HISTORY-01` / F17 root.

- Playlist-membership loss on Undo (historically tracked as `BUG-HISTORY-UNDO-PLAYLIST-01`) is evidence/subcase of F17, not a separate blocker count.
- External-file deletion versus later DB failure/process death is another F17 crash-consistency subcase, not a new root.
- `BUG-HISTORY-DUPLICATE-IDENTITY-01` remains CLOSED at `aa1616a2...`; it owns destructive target selection identity, whereas F17 begins after legitimate targets are known.

## Governing correction boundary

The Master Plan F17 contract remains applicable:

1. Introduce one `HistoryUndoSnapshot`-equivalent authority that owns at least:
   - History row;
   - keyword assignments;
   - playlist references.
2. Capture the complete relationship snapshot and perform History/reference deletion atomically in one Room transaction.
3. Restore the History row, restorable keyword state, and playlist memberships atomically in one Room transaction.
4. Route record-only, bulk, and file-backed DB record removal through the same transactional relationship primitive.
5. Preserve unrelated playlist membership and do not restore cross-references that were not part of the captured snapshot.
6. Preserve current file target/ownership/reference validation and bulk semantics.
7. Because external file deletion itself cannot join a Room transaction, explicitly preserve/establish crash-consistent semantics for the file-backed path. A successful external deletion followed by DB failure must not be silently represented as an ordinary untouched History record with no durable recovery/compensation state.
8. Preserve the non-file Undo UX while making what Undo claims internally consistent.
9. Add semantic regression coverage for at least:
   - one History item in multiple playlists, delete then Undo restores all captured memberships;
   - injected failure between reference/History mutation rolls back the Room relationship mutation;
   - bulk delete uses the same transactional primitive;
   - file deletion succeeds but DB mutation fails/process boundary is simulated -> no silent false-success convergence;
   - unrelated playlist membership is untouched.
10. Keep F18 / `BUG-KEYWORD-02` separate and dependency-gated. F18 requires the F17 atomic snapshot but owns RULE-assignment semantic recomputation, not relationship atomicity itself.

## Root/count reconciliation

- Existing canonical P2 root `BUG-HISTORY-01` is reconfirmed.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- Contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- F18 / `BUG-KEYWORD-02` remains a distinct open P2 root and dependency-gated on corrected F17 snapshot semantics.
- The active Luna `BUG-OBSERVE-01` implementation was not inspected or relied upon.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
