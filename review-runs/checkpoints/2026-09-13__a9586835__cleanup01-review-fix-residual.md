# F10 / BUG-CLEANUP-01 review-fix re-review

## Scope

- Exact completed implementation HEAD: `a95868357ddd70ac990a79026daf8a571db1917b`
- Reviewed full F10 cumulative production path through preference acceptance, durable cadence/generation/pending debt, WorkManager request/acceptance, worker retry exhaustion, successor publication, and startup reconciliation.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F10
- Governing checklist: v6.

## Verdict

**OPEN / NOT_CLEAN / existing P2 root.**

Count delta: `0`.

After the independent F8 closure, canonical blocker count remains **P0 2 / P1 0 / P2 25**.

CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Confirmed progress

The exact final source fixes important prior subcases:

- cleanup retry exhaustion no longer necessarily kills recurrence: after the final cleanup-side failure, the worker attempts successor publication and preserves `cleanup_failure` evidence;
- successor publication awaits the WorkManager `Operation` result;
- generation-bound pending scheduling debt is committed before enqueue;
- matching accepted enqueue clears only matching debt;
- stale acceptance cannot clear a newer generation's debt;
- the settings preference listener now consumes the coordinator Boolean instead of unconditionally accepting the change.

## Remaining semantic residual

Initial/reconfiguration enqueue failure still has no automatic same-process replay owner.

Concrete path:

1. `configure()` durably commits the new cadence/generation.
2. `enqueueNextLocked()` durably commits pending scheduling debt.
3. `enqueueUniqueWork()` either throws synchronously or its returned `Operation` later fails.
4. `configure()` still returns true because durable debt exists.
5. `observeAcceptance()` clears debt only on success; its failure path schedules no retry/reconciliation.
6. No current in-process loop/job consumes the surviving debt.
7. `reconcile()` repairs it only when explicitly invoked, primarily at a later application startup.
8. If the process remains alive, the UI says the cadence is enabled while no accepted WorkManager occurrence exists indefinitely.

The pending preference carrier is durable and restart-discoverable, but it is not sufficient by itself to preserve the selected recurring cadence during the surviving process. This is the same existing F10 root, not a new blocker.

## Test-isolation defect in the submitted evidence

`CleanupScheduleCoordinatorProductionWiringTest.clearTestSeams()` does not reset `CleanupScheduleCoordinator.enqueueOverrideForTesting`.

Tests that install a `ControlledOperation` can therefore leak the enqueue seam into a later test. This explains a credible mechanism for the reported full-class run hanging after several methods even though methods passed individually.

This is not counted as a separate product blocker, but the exact F10 verification matrix is not stable until the seam is reset and the whole class passes in one invocation.

## Required next correction

- add a generation-bound automatic in-process replay owner for pending scheduling debt after synchronous/asynchronous enqueue failure, while retaining startup recovery after process death;
- avoid busy looping and preserve disable/reconfigure generation fencing;
- clear/invalidate obsolete pending debt consistently where appropriate;
- reset every F10 test seam in teardown;
- run the complete `CleanupScheduleCoordinatorProductionWiringTest` class successfully in one invocation, not only methods individually.

External implementation-agent tests remain evidence only.

INDEPENDENT EXECUTION: NOT EXECUTED