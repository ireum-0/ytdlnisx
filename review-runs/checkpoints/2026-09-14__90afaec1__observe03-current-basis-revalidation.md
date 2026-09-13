# BUG-OBSERVE-03 — exact CLEAN-basis revalidation

Date: 2026-09-14

## Exact review state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior exact-basis checkpoint: `f53074cd681f4a9847195c779c62f049bcb42318` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Active implementation wave remains frozen from inspection; the implementation branch was independently confirmed still at `973424909fd97de758b62f639967c8bae7c0bad7`.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `BUG-OBSERVE-03` remains open at exact canonical CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

This is the already-counted ordinary Observe recurring-successor handoff root, not a new blocker.

## Intervening-range verification

Exact compare `3616ae02e56995e795cc52f3074d8c3d1cd2e330 -> 90afaec157607669ea32fa41877e7f0efcdcca86` is 16 commits ahead and **does modify `ObserveSourceWorker.kt`** (+66/-3), so the prior verdict was not carried forward by diff absence. Exact final source was re-read at `90afaec1...`.

The intervening Observe changes add the narrower confirmed-retry configuration fingerprint and duplicate-admission integration. They do not establish durable acceptance/recovery ownership for ordinary recurring `OBSERVE<sourceId>` successors.

## Exact current production evidence

### 1. Ordinary worker completion still commits durable source/run state before unconfirmed successor enqueue

At exact `90afaec1...`, `ObserveSourceWorker.finishRunAndSchedule(...)` still:

1. updates run history/count and clears in-progress status;
2. if recurrence continues, persists the source via `repo.update(item)`;
3. computes the next recurrence delay and builds a one-time `ObserveSourceWorker` request;
4. calls `WorkManager.getInstance(context).enqueueUniqueWork("OBSERVE$sourceID", ExistingWorkPolicy.REPLACE, request)`;
5. does not retain, await, observe, or translate the returned WorkManager `Operation` into durable scheduling debt;
6. immediately returns `Result.success()`.

The current worker can therefore complete successfully after durable source/run publication while acceptance of the exact next carrier remains unproven.

`recoverFailedRun(...)` for ordinary, non-confirmed-retry runs still converges through this same `finishRunAndSchedule(...)` boundary.

### 2. Repository-driven ordinary scheduling is still fire-and-forget

`ObserveSourcesRepository.observeTask(...)` still cancels existing observation work, builds the next ordinary worker, and calls:

`workManager.enqueueUniqueWork("OBSERVE${it.id}", ExistingWorkPolicy.REPLACE, workRequest.build())`

without observing or awaiting the WorkManager `Operation`.

`ExistingWorkPolicy.REPLACE` defines replacement semantics once WorkManager processes the request; it is not proof that the new request was accepted.

### 3. Startup recovery still has no ordinary recurring-successor debt to discover

`App.onCreate()` invokes `WorkManagerHandoffRecovery.reconcile(...)`, but the exact `WorkManagerHandoffRecovery` model remains a durable owner for one-shot handoffs plus the narrower confirmed Observe retry-download path.

Its Observe-specific carrier is created by `prepareObserveRetryDownload(...)`, which records source/config/request identity and observes enqueue acceptance. There is no corresponding carrier creation in ordinary `finishRunAndSchedule(...)` or `ObserveSourcesRepository.observeTask(...)`.

Therefore startup reconciliation cannot discover an ordinary recurring successor that was requested but never accepted: no exact durable ordinary-successor debt exists for it to own.

## Concrete failure sequence

A reachable sequence remains:

1. an ordinary Observe run completes its semantic work;
2. the source/run row is durably persisted as an active recurrence;
3. the next `OBSERVE<sourceId>` WorkManager request is issued;
4. the asynchronous enqueue later rejects/fails, or the process dies before acceptance is established;
5. the current worker has already returned success and disappears;
6. no durable ordinary-successor scheduling carrier/debt records the missing request;
7. startup recovery has no exact ordinary recurrence debt to discover;
8. the next observation can be lost indefinitely while durable source state still represents the observation as active.

This violates v6 asynchronous request/acceptance/completion and recovery-discovery invariants.

## Root relation to BUG-OBSERVE-HANDOFF-01

Keep this P2 root distinct from P0 `BUG-OBSERVE-HANDOFF-01`:

- P0 root: stale ordinary workers lack immutable current configuration-generation authority at final mutation/publication boundaries;
- P2 `BUG-OBSERVE-03`: an otherwise-current active recurrence can lose its exact successor carrier because ordinary enqueue acceptance/debt is not durable.

A future correction must compose them: ordinary successor debt/request identity must be bound to the exact immutable configuration generation so stale generations cannot publish or repair successors.

The confirmed retry-download fingerprint/carrier added in the intervening range is useful neighboring infrastructure but does not close ordinary recurrence.

## Stable correction boundary

A future correction still needs to:

1. persist exact ordinary recurring-successor scheduling debt/outbox state before the current carrier can truthfully complete, or establish an equivalent crash-safe confirmed handoff;
2. observe/await the exact enqueue acceptance result rather than treating request issuance as acceptance;
3. bind successor debt/request identity to immutable source/configuration generation;
4. ensure STOP/edit/delete revoke stale-generation successor and recovery authority;
5. make startup reconciliation distinguish an already accepted exact carrier from missing/rejected debt and repair only the latter;
6. make retry idempotent without duplicate logical recurrence;
7. keep repeated scheduler rejection as truthful durable debt/failure rather than silent active-without-carrier state;
8. preserve recurrence timing and the semantically separate confirmed retry-download path.

Focused closure coverage should force asynchronous enqueue rejection, process death after source-state commit but before acceptance, restart repair, repeated rejection, eventual success, exact-carrier-already-present reconciliation, and STOP/edit/delete/stale-generation races.

INDEPENDENT EXECUTION: NOT EXECUTED