# Independent correctness review checkpoint — F17 / BUG-HISTORY-01 closure

- implementation range reviewed: `973424909fd97de758b62f639967c8bae7c0bad7..f6e7cf72e00c013ee9b770bf748cba7f38848256`
- exact implementation HEAD: `f6e7cf72e00c013ee9b770bf748cba7f38848256`
- finding: F17 / `BUG-HISTORY-01`
- disposition: `CLOSED_AT_F6E7CF72`
- severity/root relation: existing P2 root closed; count delta `-1`
- canonical blocker count after this closure: P0 `2` / P1 `0` / P2 `21`
- contiguous independently CLEAN basis: unchanged at `90afaec157607669ea32fa41877e7f0efcdcca86` because earlier/current blockers remain open

## Source-semantic review

The exact final source introduces `HistoryUndoSnapshot` owning the History row, keyword-assignment rows, and playlist memberships for one record. `HistoryKeywordAssignmentRepository.captureAndDeleteHistoryForUndo()` captures those three pieces and deletes playlist refs, assignment refs, and the History row while holding `HistoryReferenceMutationCoordinator` and one Room transaction. A failure during the transaction rolls the owned graph back rather than committing a partial deletion.

Snapshot restore uses the same reference-mutation ownership plus one Room transaction. It rejects an already-existing replacement row at the old History ID, inserts the History row, restores eligible non-stale relationships, materializes keywords, and commits as one graph mutation. If restore throws, the transaction leaves no partially restored History/assignment/playlist graph.

The user-facing record-only Undo path in `HistoryFragment` now calls `HistoryViewModel.deleteHistoryForUndo()` and restores the returned `HistoryUndoSnapshot`; it no longer reconstructs Undo from an independent assignment-only snapshot. The legacy row+assignment restore overload remains for compatibility/testing but no production caller was identified in the reviewed consumer search.

Bulk record-only deletion routes through `HistoryKeywordAssignmentRepository.deleteHistoryRecords()`, which uses the same reference-mutation lock and transaction boundary. File-backed deletion revalidates record/file-reference authority under `HistoryReferenceMutationCoordinator`, performs the already-existing filesystem-ownership deletion policy, then calls `HistoryRepository.deleteRecordsWithinReferenceMutation()`; production `HistoryRepository` consumers were migrated to provide `DBManager`, so playlist-reference deletion and History-row deletion execute in a Room transaction. The file operation itself remains externally non-transactional, but the F17 invariant is the database relationship graph: History existence and playlist membership cannot commit divergent database states.

No remaining direct production `HistoryDao.deleteWithIds`/`deleteById` bypass was identified outside the relationship-aware repository paths reviewed for this finding.

## F18 boundary preserved

F17 must not absorb F18 / `BUG-KEYWORD-02`. The current F17 restore filters RULE assignments whose rule IDs no longer exist, but it still restores snapshot RULE-derived assignments for surviving rule IDs. Recomputing RULE-derived assignments from current enabled rules/current keyword sets is the separate F18 correction. That remaining stale-rule semantic does not reopen F17's History/playlist relationship atomicity root.

Unavailable playlist memberships are filtered on restore rather than fabricating missing playlist identities, and replacement History rows at the captured ID are not overwritten.

## Execution evidence

The implementation completion report is tied to exact remote HEAD `f6e7cf72e00c013ee9b770bf748cba7f38848256`, whose five-commit chain was independently verified. It reports:

- `HistoryUndoPersistenceTest`: 6/6 on API 36 AVD;
- `HistoryFileDeletionTest`: 25/25 JVM;
- preservation instrumentation including History intent/duplicate/replacement-barrier and backup suites passed at the final SHA;
- full JVM suite: 626/626;
- debug Kotlin/Android-test Kotlin compilation and debug/android-test APK assembly passed.

The final test-only commit also adds transaction fault injection proving capture/delete failure preserves History + playlist membership + assignment rows and restore failure leaves no partial graph.

These are accepted as implementation-agent execution evidence supporting the exact reviewed SHA. They are not converted into reviewer-independent execution claims.

## Regression / scope result

Filesystem ownership safeguards and the previously reviewed History replacement barrier paths were preserved in the production composition inspected. No new P0/P1/P2 root was identified in the F17 change.

F17 closure unblocks F18 / `BUG-KEYWORD-02`. F10 remains independently NOT_CLEAN at the same final SHA under checkpoint `2827b8dc360b5b3e9253038d1cc8be81eb81f152`; therefore this closure does not advance the contiguous CLEAN basis.

INDEPENDENT EXECUTION: NOT EXECUTED
