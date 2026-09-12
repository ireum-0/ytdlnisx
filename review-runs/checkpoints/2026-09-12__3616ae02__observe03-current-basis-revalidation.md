# BUG-OBSERVE-03 current-basis revalidation — 2026-09-12

## Scope

- Exact independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Root: existing P2 `BUG-OBSERVE-03`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior revalidation: `44050aebfd11a80e37423eda43ddf43ff977dc67` at basis `9edd3e23...`
- Exact implementation branch remains separately completed at `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`; this exploratory review uses only the independently CLEAN basis.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.

This is the already-counted ordinary Observe successor-handoff root, not a new blocker.

## Intervening-range check

`9edd3e23... -> 3616ae02...` contains five accepted commits. None modifies `ObserveSourceWorker.kt`, `ObserveSourcesRepository.kt`, `App.kt`, or the ordinary Observe recurrence scheduling surface. Exact final source was nevertheless reread at `3616ae02...`.

## Exact current-source evidence

### 1. Recurring worker completion still commits durable state before unconfirmed successor enqueue

`ObserveSourceWorker.finishRunAndSchedule(...)` still:

1. updates run history/count/status;
2. persists the source through `repo.update(item)`;
3. calculates recurrence delay and builds a `OneTimeWorkRequest<ObserveSourceWorker>`;
4. calls `WorkManager.getInstance(context).enqueueUniqueWork("OBSERVE$sourceID", ExistingWorkPolicy.REPLACE, request)`;
5. ignores the returned WorkManager `Operation` and returns `Result.success()`.

The durable source/run state can therefore represent an ongoing active recurrence while the next carrier has not been proven accepted.

`recoverFailedRun()` for ordinary, non-confirmed-retry execution can converge into this same `finishRunAndSchedule(...)` boundary.

### 2. Repository-driven ordinary scheduling is also fire-and-forget

`ObserveSourcesRepository.observeTask(...)` cancels current observation work, builds the next `ObserveSourceWorker`, and calls the same `enqueueUniqueWork("OBSERVE<id>", REPLACE, ...)` without awaiting or observing scheduler acceptance.

`ExistingWorkPolicy.REPLACE` describes replacement behavior after WorkManager receives the request; it is not evidence that the exact request was accepted.

### 3. Startup recovery still does not own ordinary recurrence debt

`App.onCreate()` starts several reconciliation owners, including `WorkManagerHandoffRecovery.reconcile(...)`, History date-fetch reconciliation, download execution recovery, low-quality recovery, and automatic-keyword observation coverage.

`WorkManagerHandoffRecovery` explicitly owns durable exact carriers for one-shot handoffs and the narrower `OBSERVE_RETRY_DOWNLOAD` path. Its Observe carrier records source/config/request identity and awaits `Operation.result`; startup reconciliation can inspect request identity and retry that special carrier.

No ordinary recurring `OBSERVE<sourceId>` successor debt/carrier is created by `finishRunAndSchedule(...)` or `ObserveSourcesRepository.observeTask(...)`, so this recovery subsystem cannot discover or repair a lost ordinary successor.

## Concrete failure boundary

A reachable sequence remains:

```text
ordinary Observe run completes semantic work
-> source/run state is durably updated as still-active recurrence
-> enqueueUniqueWork for successor is requested
-> WorkManager Operation later rejects/fails, or process dies before acceptance is established
-> current worker is complete/gone
-> no durable ordinary-successor scheduling debt exists
-> startup recovery has no exact ordinary recurrence carrier to discover
-> next observation run can be lost indefinitely
```

This violates checklist rules that an async enqueue request is not completion/ownership and that every surviving durable obligation must remain discoverable by a recovery owner after process death.

## Root relation

This remains separate from P0 `BUG-OBSERVE-HANDOFF-01`:

- P0 handoff root: stale ordinary workers lack immutable current configuration-generation authority at mutation/publication boundaries;
- P2 `BUG-OBSERVE-03`: active recurrence can lose the exact successor carrier because enqueue acceptance/debt is not durable.

A future correction must compose the two: any ordinary successor debt/carrier must be bound to the exact configuration generation so stale generations cannot publish successors.

The confirmed notification-retry path (`OBSERVE_RETRY_DOWNLOAD`) is useful neighboring infrastructure but is not authoritative for ordinary recurrence and must remain semantically distinct unless explicitly generalized.

## Stable correction boundary

1. Persist exact ordinary successor scheduling debt/outbox state before the current carrier may finish successfully, or otherwise establish a confirmed handoff with equivalent crash recovery.
2. Observe/await the exact WorkManager enqueue result rather than treating the request call as acceptance.
3. Bind successor debt and request identity to immutable source/configuration generation.
4. STOP/edit/delete must revoke stale-generation successor authority.
5. Startup reconciliation must distinguish an already accepted exact carrier from missing/rejected debt and repair only the latter.
6. Retry must be idempotent and must not create duplicate logical recurrence.
7. Repeated scheduler rejection must remain truthful durable debt/failure rather than silent active-without-carrier state.
8. Preserve recurrence delay semantics and the separate confirmed-retry behavior.

Focused regressions should cover asynchronous enqueue rejection, process death after source-state commit and before acceptance, restart repair, repeated rejection, successful retry, exact-carrier-already-present reconciliation, and STOP/edit/delete/stale-generation races.

INDEPENDENT EXECUTION: NOT EXECUTED