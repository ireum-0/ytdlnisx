# CLEANUP-STALE-DOWNLOAD-ROW-01 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Canonical later root: `CLEANUP-STALE-DOWNLOAD-ROW-01`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `CLEANUP-STALE-DOWNLOAD-ROW-01` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariants

Cleanup candidate discovery is not final destructive authority. At the mutation boundary, cleanup must still own the exact row/state/execution/recovery identity it intends to retire.

A terminal-looking Download status is not, by itself, proof that all exact execution/recovery responsibility is complete.

Checklist-v6 identity, live-owner, destructive-authority and cross-attempt rules therefore require exact current-state and recovery-owner revalidation at the final mutation boundary.

## Exact production evidence at 90afaec1

### 1. Cleanup materializes Cancelled/Error candidates before the destructive transaction

`CleanUpLeftoverDownloads.doWork()` calls:

- `downloadRepo.deleteCancelled()`;
- `downloadRepo.deleteErrored()`.

`DownloadRepository.deleteCancelled()` passes the previously materialized `getCancelledDownloads()` list to `deleteKnownUserRemoval()`.

`deleteErrored()` does the same for Error rows.

The DAO candidate queries select rows based on their status at candidate-read time.

### 2. Final deletion trusts stale IDs rather than current cleanup authority

`deleteKnownUserRemoval(items)` converts the old snapshots to a distinct ID list and later enters one Room transaction.

Inside that transaction it:

1. terminalizes linked low-quality children for those IDs;
2. deletes History replacement barriers for those IDs;
3. invokes `downloadDao.deleteAllWithIDs(ids)`.

`DownloadDao.deleteAllWithIDs()` is:

`DELETE FROM downloads WHERE id in (:list)`

with no current-status, execution-generation, recovery-carrier, or live-owner predicate.

Thus the final destructive boundary does not prove that the Download still has the state/authority represented by the original cleanup snapshot.

### 3. Same-ID revival is a real production path

`DownloadViewModel.reQueueDownloadItemsAndWait()` can requeue an eligible non-running Download under the per-Download side-effect lease and execution lock after checking current recovery/history barriers.

The final DAO operation can transition the same row ID back to `Queued`.

Pending-cancellation Undo likewise has a production RESTORE path which can restore the exact row to a resumable status such as `Queued` or `WaitingForMembership` after exact token/execution checks.

Therefore this interleaving is reachable:

Cancelled/Error candidate snapshot
→ user requeue/Undo wins and durably revives same ID
→ stale cleanup transaction runs second
→ linked child/barrier mutations and unconditional row delete use the old ID
→ revived Download disappears.

Cleanup-first may legitimately win when the row is still eligible; revive-first must instead make stale cleanup lose authority.

### 4. Status-only revalidation would remain insufficient

`DownloadExecutionRecovery` explicitly separates user-stop semantic state from later native quiescence.

A Cancelled row can still have an exact user-stop recovery carrier in or around `SEMANTIC_STOP_PENDING` / native-quiescence progression.

`DownloadExecutionRecovery.prepareUserStopBeforeNative()` requires the Download row and exact execution identity to converge semantic stop and then native quiescence.

Automatic cleanup does not:

- query `DownloadExecutionRecovery.hasPendingRecovery(...)`;
- acquire the exact per-Download execution side-effect lease used by stop/requeue convergence;
- prove native quiescence;
- condition linked-child/barrier/row/cache retirement on the same exact execution/recovery authority.

Thus even a row that is still literally `Cancelled` may be unsafe to delete if its exact user-stop recovery responsibility remains unresolved.

Deleting it can strand the carrier and remove the Room state needed for recovery to prove/converge the exact stop before native cleanup.

### 5. Cache retirement is downstream of the same stale authority

After the transaction, `deleteKnownUserRemoval()` invokes `deleteCache(items)` using the old candidate snapshots.

`DownloadCacheOwnership.deleteIfOwned()` provides cache-path ownership checks, but it does not retroactively establish that cleanup still owned the Download row/recovery semantics.

The row/linked-state authority defect is independently sufficient; cache retirement must compose with the corrected exact authority rather than be treated as proof of it.

## Concrete impacts

### Revive-first race

Cancelled/Error snapshot
→ user requeue or pending-cancellation Undo restores same ID
→ cleanup deletes same ID based on stale candidate
→ user-visible resumable work and linked state can disappear.

### User-stop recovery race

exact user Cancel commits semantic Cancelled state
→ exact execution recovery/native-quiescence debt remains
→ cleanup sees Cancelled and deletes row
→ recovery loses required exact row/execution evidence and can remain blocked while external/native responsibility is unresolved.

## Root reconciliation

- Keep `CLEANUP-STALE-DOWNLOAD-ROW-01` counted once as P2.
- Canonical count delta: `0`.
- Keep separate from F10 / `BUG-CLEANUP-01`: F10 governs cleanup occurrence/replay/successor scheduling semantics.
- Keep separate from `BUG-CLEANUP-02`: that root concerns live DOWNLOAD_TEMP maintenance ownership.
- Same-root subcases are stale revived-row deletion and unresolved exact user-stop recovery deletion.
- No new canonical root is created.

## Stable correction boundary

A future correction must make final cleanup authority coherent across all affected state:

1. revalidate at the final mutation boundary that the row still has the exact cleanup-eligible semantic state expected by the candidate;
2. prove no stronger same-ID requeue/Undo/reconfiguration has won;
3. prove no exact `DownloadExecutionRecovery` carrier still needs the row for semantic/native convergence;
4. prove no exact live execution/native owner makes retirement unsafe;
5. condition low-quality child terminalization, History replacement-barrier removal, Download row deletion and cache retirement on the same accepted authority;
6. preserve both completion orders: cleanup-first may retire a still-safe terminal row; revive-first must cause cleanup to skip;
7. add deterministic production-path races for Cancelled -> Queued requeue, pending-cancellation Undo -> Queued/WaitingForMembership, Error -> retry/requeue, and Cancelled + unresolved user-stop recovery -> later safe cleanup after convergence.

A status-only conditional DELETE is insufficient.

## Verification

- Exact-source production-path review: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
