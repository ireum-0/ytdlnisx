# F11 completion re-review — intermediate R3 disposition

Date: 2026-09-20

## Exact reviewed state

- Exact final remote implementation SHA: `61304eb6f11b10ac66057a1978d5b1f8f75019b0`.
- Original F11 base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`.
- Canonical F11 review: `8b5b064c63fc04de9b2d18346954ab5dfdec625e`.
- Governing Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.

## Disposition

### F11-R3 — STILL_OPEN / HIGH

The remediation correctly introduces an operation-bound `RestoreReconciliationAuthority` and carries it through:

`RestoreTransactionCoordinator`
→ `DownloadRepository.startDownloadWorkerForRestore`
→ `AlarmScheduler.scheduleAtForRestore`
→ `WorkManagerHandoffRecovery.prepareSchedulerBoundaryForRestore`.

This closes the original immediate self-block at `prepareSchedulerBoundary`.

However exact-alarm publication failure still has a same-root liveness gap.

## Exact final-source sequence

1. During RECONCILING, `prepareSchedulerBoundaryForRestore()` persists a deterministic scheduler carrier with:
   - deterministic handoff ID derived from restore operation + boundary;
   - `PENDING_ENQUEUE` state;
   - future `notBeforeAt`;
   - exact restore authority checked while the active Restore is still RECONCILING.

2. `AlarmScheduler.setAlarm()` calls `AlarmManager.setExactAndAllowWhileIdle(...)`.

3. If alarm publication throws, or no AlarmManager is available, it calls:

`WorkManagerHandoffRecovery.ensureConvergenceForRestore(context, handoffId, authority)`.

4. `ensureConvergenceForRestore()` is fire-and-forget on a process-local `convergenceScope`; the Restore coordinator does not await a durable accepted successor from it.

5. `performAttempt()` sees the future `notBeforeAt`. Instead of enqueuing a WorkManager request now with initial delay, it installs a process-local `scheduleRetry(..., authority)` and returns `RETRYING`.

6. `scheduleRetry()` delays in memory until the future boundary and then retries using the SAME Restore-specific authority.

7. `reconcilePostCommit()` is not waiting on that delayed retry. The Restore can advance:

`RECONCILING → COMPLETE → active-pointer retirement`.

8. When the delayed retry later wakes in the same process, `schedulerAuthorityAvailable(..., authority)` calls `requireCurrentReconciliationAuthority(...)`. The Restore owner is no longer active/RECONCILING, so that authority is invalid and the process-local retry cannot establish the WorkManager owner.

9. The exact alarm publication already failed. The durable carrier remains pending. `WorkManagerHandoffRecovery.reconcile()` can later recover such a carrier only through an ordinary no-Restore path, but that startup reconciliation is not triggered merely by Restore completion in the current process.

Result:

future scheduled responsibility can remain without an alarm or accepted WorkManager successor until a later process restart.

## Why the remediation test does not close this path

`BackupResetTransactionProductionWiringTest` globally sets `use_alarm_for_scheduling=false` in setup.

`acceptedRestoreSchedulingReplaysWithOneCurrentOwner()` therefore exercises the delayed **WorkManager** branch:

`scheduledDownload-restore-<operationId>-<startTime>`

with `ExistingWorkPolicy.KEEP`.

It does not execute:

`AlarmScheduler.scheduleAtForRestore`
→ exact-alarm publication failure
→ `ensureConvergenceForRestore`
→ future `notBeforeAt` delayed fallback
→ Restore COMPLETE.

Thus the reported 26/26 suite does not establish this R3 failure boundary.

## Required contract

An exact-alarm publication failure must leave a recovery mechanism that remains valid after the Restore transaction retires.

A safe correction may, for example:

- establish the delayed WorkManager request immediately (with WorkManager initial delay) while Restore authority is valid, and await enqueue acceptance; or
- durably transfer the pending scheduler carrier from restore-owned authority to ordinary post-Restore recovery before COMPLETE, then explicitly reconcile it before retiring Restore ownership;
- or provide an equivalent durable ownership transfer with finite acceptance proof.

Do not retain a delayed process-local coroutine whose sole authority expires when Restore completes.

Ordinary callers must remain fail-closed during active Restore.

## Canonical reconciliation

- This is the same canonical F11-R3 root, not a new root.
- Canonical blocker-count delta: `0`.
- F11 remains P0 / NOT_CLEAN.

## Evidence confidence

- Exact source sequence: independently reviewed at `61304eb6...`.
- Remediation test coverage gap: independently reviewed from exact final test source.
- Luna runtime results: evidence only; not independently executed here.

INDEPENDENT EXECUTION: NOT EXECUTED
