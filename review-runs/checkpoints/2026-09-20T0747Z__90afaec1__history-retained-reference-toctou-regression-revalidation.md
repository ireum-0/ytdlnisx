# BUG-HISTORY-02 — exact CLEAN-basis regression revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Historical root under regression check: P0 `BUG-HISTORY-02` retained-reference TOCTOU.
- Prior exact disposition: CURRENT HISTORICAL P0 NOT REPRODUCED / DO NOT PROMOTE at `4ef990e00a354a71b33c4df8f215cc27337cdce9`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**HISTORICAL P0 STILL NOT REPRODUCED / DO NOT PROMOTE at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A retained-reference snapshot is not final destructive authority after concurrent History reference creation/change. Final destructive deletion and every production writer capable of creating/changing the protected media reference must participate in the same mutation-ordering domain.

## Exact regression review

### 1. Relevant intervening changes were identified

Exact compare `4ef990e0... -> 90afaec1...` is 23 commits ahead.

Among the previously reviewed retained-reference boundary, relevant source changes include `SettingsViewModel.kt` and `ObserveSourceWorker.kt`. The core coordinator-backed History repository and assignment repository remain present.

Therefore the prior rejection was not blindly inherited; the changed production consumers were re-opened at the exact final basis.

### 2. Observe Source destructive deletion still holds the coordinator through final authority

At `90afaec1...`, the sync-driven History/media deletion path enters:

`HistoryReferenceMutationCoordinator.withLock { ... }`

before it:

- re-reads selected rows' current `downloadPath` snapshots;
- re-reads retained History references excluding selected IDs;
- excludes currently retained targets;
- performs `HistoryFileDeletionEngine.execute(...)`;
- revalidates selected row snapshots after the external deletion;
- deletes only still-authorized History rows through `deleteRecordsWithinReferenceMutation(...)`.

The historical unlocked retained-reference window is therefore not reintroduced by the Observe changes.

### 3. Backup restore still acquires references through the coordinator-backed insertion boundary

`SettingsViewModel.restoreData(...)` restores History rows using:

`historyKeywordAssignments.insertHistory(...)`.

`HistoryKeywordAssignmentRepository.insertHistory(...)` is wrapped in `HistoryReferenceMutationCoordinator.withLock` and a Room transaction.

When reset is requested, `SettingsViewModel` uses `historyRepository.deleteAllRecords()`, which also holds `HistoryReferenceMutationCoordinator` while clearing playlist references and History rows.

Thus backup restore/reset does not publish a new protected `downloadPath` reference outside the same ordering domain.

### 4. Other core reference writers remain coordinated

Exact source at `90afaec1...` still shows:

- `HistoryRepository.deleteRecords()` and `deleteAllRecords()` under the coordinator;
- `HistoryRepository.updateDownloadPath(...)` under `withLockBlocking`;
- `HistoryKeywordAssignmentRepository.insertHistory(...)` under the coordinator;
- `HistoryKeywordAssignmentRepository.restoreHistory(...)` under the coordinator;
- `replaceHistoryPreservingAssignmentsAuthorized(...)` under the coordinator.

No new production reference writer was established in this regression pass that can publish a durable History media reference outside the ordering contract and race the reviewed deletion paths.

## Root reconciliation

- Keep historical `BUG-HISTORY-02` non-canonical/non-promoted at this basis.
- Count delta: `0`.
- Do not merge with `HISTORY-CONTENT-AUTHORITY-ALIAS-01`; that open P2 concerns loss of provider-authority identity inside the deletion key, not retained-reference timing.
- Do not merge with `BUG-HISTORY-01`; that root concerns playlist/History Undo atomicity.
- Reopen only if a concrete production writer is found that changes a protected History media reference outside `HistoryReferenceMutationCoordinator`, or if a destructive consumer drops the coordinator before final retained-reference authority is established.

## Verification

- Exact changed-file regression review: completed.
- Exact production delete/writer coordination trace: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
