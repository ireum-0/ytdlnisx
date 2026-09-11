# Independent correctness review — final checkpoint

Timestamp (UTC): 2026-09-11T04:29:00Z

## Frozen basis

- implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- plan/remediation SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review/remediation bootstrap SHA: `5afff125663209da720aedc3a82e5c8681cc5cbe`
- ledger/remediation SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- v6 checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

The implementation SHA remained frozen for the entire review round.

## Review completed

Current production source was reviewed directly rather than as a diff for the History mutation/Undo family:

`History deletion selection -> HistoryViewModel record-only/file-backed deletion -> HistoryFileDeletionEngine external mutation -> HistoryRepository playlist-reference + History-row deletion -> HistoryKeywordAssignmentRepository Undo restore`.

Verified current-source facts:

1. `HistoryRepository.deleteRecordsWithinReferenceMutation()` deletes playlist cross-references and History rows through two separate DAO calls per batch. `HistoryReferenceMutationCoordinator` is an application-level lock, not a Room transaction spanning both calls.
2. `HistoryViewModel.getKeywordAssignmentSnapshot()` captures only keyword assignments. `restoreHistory(item, assignmentSnapshot)` delegates to `HistoryKeywordAssignmentRepository.restoreHistory()`.
3. `HistoryKeywordAssignmentRepository.restoreHistory()` uses one Room transaction for the History row plus restorable keyword assignments/materialization, but its contract has no playlist-reference snapshot or restore operation.
4. `HistoryViewModel.executePreparedHistoryFileDeletion()` invokes `HistoryFileDeletionEngine.execute()` before calling the DB record/reference deletion primitive. External file/content-provider deletion therefore cannot participate in the later Room mutation; there is no durable crash-recovery carrier for the window where external deletion succeeds but DB mutation does not.
5. `PlaylistDao.deletePlaylistItemsByHistoryIds()` is a standalone DAO mutation; the repository does not wrap it together with `historyDao.deleteWithIds()` in `db.withTransaction`.

## Final blocker state

- P0: 3 canonical roots
- P1: 3 canonical roots
- P2: 25 canonical roots
- Independent verdict: `NOT_CLEAN`

## Findings/status changes

No new P0/P1/P2 canonical finding and no status transition were established in this round.

`BUG-HISTORY-01` / F17 is independently reconfirmed OPEN P2 at the frozen implementation SHA. Concrete current-SHA failure windows remain:

- playlist-reference delete succeeds -> History-row delete fails/process dies -> relationship state partially mutates;
- delete completes -> Undo restores History row + keyword assignments but not prior playlist memberships;
- external file deletion succeeds -> DB reference/row mutation fails/process dies -> durable History/reference state can remain while media is already gone.

These are existing F17 subcases and do not change canonical counts.

The previously closed `BUG-HISTORY-DUPLICATE-IDENTITY-01` remains separate: target-selection identity is not reopened by this post-selection atomicity finding, and no contrary current-source evidence was found in this round.

## Confirmed fixed invariants / non-regressions

- Production implementation SHA did not move during the round.
- Plan and ledger refs did not move during final recount.
- No current-source evidence was found to reopen the separately closed History duplicate-selection identity root.

## Open candidates / NOT_VERIFIED

- Independent JVM/instrumentation/emulator/device execution: `NOT_VERIFIED`.
- No external upstream semantic claim is required for the reconfirmed F17 issue; it is entirely repository-owned Room/application/filesystem behavior.
- No speculative candidate was promoted without a concrete current-SHA invariant violation.

## Remaining review scope

Continue rotating across canonical root families and authority/mutation boundaries in later rounds. If production advances, freeze the new implementation SHA at that next round and re-run affected consumer/effect graphs before changing any disposition.

## Exact semantic basis used

- repository production source at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`;
- v6 checklist blob `7b553328dfcd9941d783658f49ecb16c71b98c56`;
- Master Plan at `fada33a7eed86b1fa2c07065af66f14bf4d24714`, canonical SHA-256 `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`;
- no external upstream contract was necessary for this round's finding/status determination.

## Final ref recount before this checkpoint

- checkpoint/pre-baseline-review: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- plan/remediation: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- ledger/remediation: `899328bc91e4008e39a658387396a0106c8666ec`

INDEPENDENT EXECUTION: NOT EXECUTED
