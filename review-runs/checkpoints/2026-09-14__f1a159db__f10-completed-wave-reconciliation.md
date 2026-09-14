# F10 / BUG-CLEANUP-01 — completed-wave reconciliation at f1a159db

## Scope

- implementation branch: `checkpoint/pre-baseline-review`
- previous completed implementation head: `f6e7cf72e00c013ee9b770bf748cba7f38848256`
- completed review head: `f1a159db41f1281a31e1e06df486e4f67cdc3d89`
- F10 implementation commits in this completed range:
  - `7f23fbb2ea530c267cfff9d3e5d64332107c0d78` — converge cleanup authority on persistence failure
  - `b9341032c80fba0ce612adffae85b54c00cfbdeb` — test-only import correction
- governing finding: F10 / `BUG-CLEANUP-01`, P2

## What the review fix correctly changes

`CleanupScheduleCoordinator.configure()` now retires the superseded generation's pending scheduling-debt tuple in the same `SharedPreferences.Editor.commit()` that installs the new cadence/generation/anchor authority. This closes the previously reviewed second-persistence-write failure where new authority could commit and a later debt-retirement commit could fail before stale WorkManager cleanup was cancelled/replaced.

The worker also now reads its generation/cadence and calls `CleanupScheduleCoordinator.isCurrentOccurrence()` before entering cleanup effects. A request that is already stale when that check executes returns success with `cleanup_schedule_stale=true` instead of performing cleanup.

## Residual — existing F10 root remains OPEN

The authority check is still point-in-time rather than an authority lease/critical section spanning the destructive effect boundary.

Concrete exact-source sequence at `f1a159db...`:

1. `CleanUpLeftoverDownloads.doWork()` calls `isCurrentOccurrence(generation, cadence)`.
2. `isCurrentOccurrence()` acquires the coordinator process lock only while reading the current generation/cadence from preferences, then releases it and returns `true`.
3. Before the worker executes `DownloadRepository.deleteCancelled()`, `deleteErrored()`, or temporary-download-cache deletion, another thread can enter `CleanupScheduleCoordinator.configure()` under the same coordinator lock and durably commit a new generation/cadence, including disable.
4. WorkManager cancellation remains asynchronous.
5. The old worker has already passed its only pre-effect authority check and continues into destructive cleanup without revalidation or a lock/lease that prevents authority supersession through those effects.

Therefore a worker that was current at the instant of the check can become stale before the destructive effect and still delete cancelled/errored Download rows or temp download cache after disable/supersession has become durable authority.

This is not a new blocker. It is the same stale/destructive-authority semantic root already owned by F10 / `BUG-CLEANUP-01`.

## Disposition

- F10 / `BUG-CLEANUP-01`: **OPEN / NOT_CLEAN**
- severity/root count: existing P2, count delta `0`
- canonical blocker total remains **P0 2 / P1 0 / P2 21**
- contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 / `BUG-BACKUP-03` remains blocked on actual F10 closure before its required Sol Extra High planning step.

The next review boundary in this completed wave is F18 / `BUG-KEYWORD-02` at the same exact final SHA.

INDEPENDENT EXECUTION: NOT EXECUTED
