# Independent correctness review — cleanup stale-row recovery-debt addendum

- frozen_implementation_sha: `a5160ab51dbe3c6f8d5f87853c4e4037469b9684`
- frozen_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- review_parent_sha: `eb2dc35fc97e75963b75d63ddb42a6811bf8336f`
- implementation_diff_after_start_inspected: `NO`

## Scope expansion — same root, count delta 0

`CLEANUP-STALE-DOWNLOAD-ROW-01` remains one P2 root, but its deletion-authority predicate must be stronger than a current-status recheck.

### Confirmed subcase: Cancelled row can still carry unresolved exact user-stop recovery

Production cancellation deliberately separates semantic Room state from native quiescence:

1. A user-cancel path publishes `DownloadExecutionRecovery` for the exact Download/execution with `USER_CANCEL / SEMANTIC_STOP_PENDING` before mutating native execution.
2. `DownloadRepository.convergeUserStopSemantic(...)` can then durably commit the Download row to `Cancelled` while the recovery carrier still owns the next phase.
3. Only after that semantic result does the cancel path continue toward `prepareUserStopBeforeNative()` / native quiescence and eventual carrier retirement.
4. Automatic leftover cleanup selects rows purely because they are currently `Cancelled`; `deleteKnownUserRemoval(items)` does not check `DownloadExecutionRecovery.hasPendingRecovery(...)`, recovery phase, exact live execution ownership, or native quiescence before deleting the row.
5. Cleanup also does not acquire the per-Download execution side-effect lease used by user-stop convergence, so it can interleave after the Cancelled Room commit but before the recovery carrier advances out of `SEMANTIC_STOP_PENDING`.
6. If cleanup deletes the Download row in that window, later `prepareUserStopBeforeNative()` cannot re-prove the exact semantic stop from Room. `convergeUserStopSemantic()` classifies a missing row as lost execution ownership; the recovery path retains responsibility and returns `BLOCKED` rather than treating absence as proof that Cancel committed.
7. Native quiescence is intentionally not attempted until semantic stop authority is proven. The cleanup deletion can therefore strand an exact user-stop recovery carrier, and an external/native execution may remain unresolved even though the user-visible row has already been removed.

This subcase does not require the row to have been revived to Queued. A row can still be literally `Cancelled` and yet be unsafe for cleanup because `Cancelled` is only the semantic stop decision, not proof that the exact execution's recovery/native obligations are complete.

### Cache side effect

After deleting the Room row, `deleteKnownUserRemoval()` invokes `deleteCache(items)`. That helper uses the old Download snapshot with `DownloadCacheOwnership.deleteIfOwned(...)`; it is not the `AppCacheManager` maintenance path protected by `CacheMaintenanceAuthority`. This reinforces that cleanup eligibility must be derived from exact execution/recovery authority before row/cache retirement, rather than assuming `Cancelled` means all execution ownership is gone.

The primary promoted impact remains the loss/stranding of the user-stop recovery authority. Cache mutation should be verified under the same race, but no broader cache-loss claim is required to establish this subcase.

## Required correction refinement

For `CLEANUP-STALE-DOWNLOAD-ROW-01`, deletion authority must prove all of the following at the mutation boundary:

- the Download still has the cleanup-eligible semantic status expected by the cleanup candidate;
- no stronger same-ID user action has revived/reconfigured the row;
- no exact `DownloadExecutionRecovery` carrier still requires the row for semantic-stop or native-quiescence convergence;
- no live execution/native authority still makes row/cache retirement unsafe;
- linked low-quality terminalization, History replacement barrier removal, Room deletion, and cache retirement use the same coherent authority decision.

A status-only `WHERE status='Cancelled'` or `WHERE status='Error'` fix is therefore insufficient.

Focused production-path coverage must include a user Cancel latched after the durable `Cancelled` semantic write but before advancement from `SEMANTIC_STOP_PENDING`; run automatic cleanup in that window and prove cleanup does not delete the row or its execution resources until the exact recovery protocol can converge. Then complete native quiescence and prove a later cleanup can remove the now-safe terminal row.

## Classification

- Existing root: `CLEANUP-STALE-DOWNLOAD-ROW-01`
- Severity/state: `P2 / OPEN`
- New-root count delta: `0`
- Canonical counts remain: P0 2 / P1 0 / P2 22
- This remains separate from `BUG-CLEANUP-02` live `DOWNLOAD_TEMP` maintenance ownership.
- This does not expand the currently active F10 / `BUG-CLEANUP-01` implementation authorization.

INDEPENDENT EXECUTION: NOT EXECUTED