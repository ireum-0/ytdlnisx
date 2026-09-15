# Independent correctness review — cleanup stale-row authority

- frozen_implementation_sha: `a5160ab51dbe3c6f8d5f87853c4e4037469b9684`
- frozen_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- review_parent_sha: `1bddafc57cc15cc68c8f65b4c0ae61fb739b10c5`
- implementation_diff_after_start_inspected: `NO`

## New finding

### `CLEANUP-STALE-DOWNLOAD-ROW-01` — P2 / OPEN

Automatic leftover cleanup can delete a Download row after the user has legitimately revived the same row from the cleanup-eligible state.

Production chain on the frozen SHA:

1. `CleanUpLeftoverDownloads` invokes `DownloadRepository.deleteCancelled()` and `deleteErrored()` inside the cleanup destructive effect.
2. Each repository method first materializes the current Cancelled/Error rows and only then calls `deleteKnownUserRemoval(items)`.
3. Between that materialization and the later deletion transaction, production UI paths can legitimately revive the same Download identity. In particular, the Cancelled downloads redownload path reaches `DownloadViewModel.reQueueDownloadItemsAndWait()` / `DownloadDao.reQueueDownloadItems()`, which can transition a non-Active/non-PostProcessing row to `Queued`; pending-cancellation Undo can also restore the same Cancelled row to `Queued` or `WaitingForMembership` under its exact token/operation guards.
4. `deleteKnownUserRemoval(items)` does not re-read or require the current Download status before mutation. It derives only the old snapshot IDs, terminalizes linked low-quality children as `USER_REMOVED`, deletes History replacement barriers for those IDs, and calls `downloadDao.deleteAllWithIDs(ids)`.
5. `DownloadDao.deleteAllWithIDs(ids)` is an unconditional `DELETE FROM downloads WHERE id IN (:list)` with no current-status or exact-state predicate.
6. Therefore, if the retry/requeue or Undo commits first and the stale cleanup transaction commits second, cleanup deletes the newly revived live row. Its linked low-quality child can also be terminalized as user-removed even though the user just restored/requeued the Download.

Concrete user-visible impact: a successful redownload/requeue or exact pending-cancellation Undo can disappear because a previously captured cleanup snapshot remains destructive authority after the row's semantic state changed.

## Distinct-root classification

This is not `BUG-CLEANUP-02`.

The governing baseline registry defines `BUG-CLEANUP-02` as the `DOWNLOAD_TEMP` filesystem ownership race: cleanup snapshots zero Active/PostProcessing work, a Download then becomes Active and acquires its per-download temp directory, and `AppCacheManager.delete(DOWNLOAD_TEMP)` can delete that live directory. Its protected semantic object is live temporary filesystem ownership.

`CLEANUP-STALE-DOWNLOAD-ROW-01` instead concerns stale database-row authority: a Cancelled/Error snapshot is later used to delete the same ID after current durable state has changed back to runnable. The corrected cache path already has a separate cache-ownership lock/marker, but that does not serialize or revalidate the Room/linked-ledger deletion boundary.

No existing baseline or delta finding located in the governing ledger owns this cleanup-specific stale Cancelled/Error snapshot -> same-ID retry/Undo -> unconditional row deletion chain. It is therefore counted as one new P2 semantic root, not as another instance of the F10 recurrence root and not as a `BUG-CLEANUP-02` subcase.

## Required correction

- Make cleanup eligibility authoritative at the database mutation boundary, not only when the candidate list is read.
- A row selected as Cancelled/Error must be deleted only if the exact current durable state still satisfies the cleanup eligibility contract when the deletion transaction commits.
- Linked-child terminalization and History-replacement-barrier mutation must be conditioned on the same still-current deletion authority; a stale cleanup snapshot must not terminalize or strip state from a row that has been revived.
- Preserve legitimate same-ID retry/requeue and exact pending-cancellation Undo semantics.
- Reconcile races in both completion orders: cleanup-first may remove a genuinely still-eligible row; revive-first must cause stale cleanup to skip that row rather than delete the revived state.
- Add deterministic production-path races for at least Cancelled -> Queued requeue and pending-cancellation Undo -> Queued/WaitingForMembership, latching cleanup after candidate materialization and before the deletion transaction.
- Include Error -> retry/requeue where the production path preserves the same ID.

## Relationship to active F10 implementation

The currently active implementation wave remains authorized for F10 / `BUG-CLEANUP-01` occurrence-phase/re-entry semantics only. This newly confirmed root does **not** silently expand the in-progress implementation scope. It must be preserved as a separate open finding and reviewed independently after the current wave unless a later explicit authorization changes scope.

The previously recorded F10 partial-effect same-root addendum at `1bddafc57cc15cc68c8f65b4c0ae61fb739b10c5` remains unchanged.

## Counts / verdict

- prior canonical remediation counts: P0 2 / P1 0 / P2 21
- new distinct root: +1 P2
- updated counts: P0 2 / P1 0 / P2 22
- overall: `NOT_CLEAN`

INDEPENDENT EXECUTION: NOT EXECUTED