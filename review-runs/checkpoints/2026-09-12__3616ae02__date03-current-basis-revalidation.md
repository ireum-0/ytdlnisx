# BUG-DATE-03 — exact CLEAN-basis durable scheduler-handoff revalidation

Date: 2026-09-12

## Exact review state

- Independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Prior current-basis checkpoint: `d56771c10371be7df1625a5df34a71365db381a2` at `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- The separate exact verification wave remains against completed remote `checkpoint/pre-baseline-review@8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`; that candidate is not used as exploratory CLEAN-basis evidence here.

## Verdict

**NOT_CLEAN — existing P2 `BUG-DATE-03` remains OPEN / CONFIRMED at exact basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Relation to the Master Plan date findings

`BUG-DATE-03` is not a replacement for F15 `BUG-DATE-01` or F16 `BUG-DATE-02`.

- F15 governs typed extractor/date lookup outcomes and authoritative absence.
- F16 governs child distribution, retry/terminal semantics, parent state, WorkManager result, and notification agreement.
- `BUG-DATE-03` is the separate producer-to-scheduler durable handoff root: a nonterminal History date-fetch operation can exist durably without confirmed WorkManager carrier acceptance.

The applicable checklist obligations are therefore primarily asynchronous request/acceptance/completion semantics, ignored-result audit, recovery discovery, and multi-ledger/process-death closure.

## Intervening-range verification

Exact compare `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71 -> 3616ae02e56995e795cc52f3074d8c3d1cd2e330` is five commits ahead. The range changes keyword/cache/download/terminal code and related tests, but does not modify:

- `HistoryDateFetchManager.kt`;
- `HistoryDateFetchRepository.kt`;
- `HistoryDateFetch.kt`;
- `HistoryDateFetchEnqueuePolicyTest.kt`.

Those exact final-basis files were nevertheless re-read directly.

## Exact current production evidence

### 1. Durable operation creation still precedes scheduler acceptance

`HistoryDateFetchManager.startOrReconnect()` launches on its manager scope, then calls `repository.createOrReconnect()` before `enqueue(operation.operationId)`.

`HistoryDateFetchRepository.createOrReconnect()` uses one Room transaction to create the `HistoryDateFetchOperation` and exact `HistoryDateFetchItem` child snapshots. A newly created operation defaults to `RUNNING`; each child defaults to `PENDING`.

Therefore the application can durably publish an ordinary nonterminal operation before WorkManager has accepted any request for that `operationId`.

### 2. Enqueue remains fire-and-forget

`HistoryDateFetchManager.enqueue()` constructs the exact `HistoryDateFetchWorker` request and calls:

`workManager.enqueueUniqueWork(uniqueWorkName(operationId), ExistingWorkPolicy.KEEP, request)`.

The returned WorkManager `Operation` is not retained, awaited, observed, chained, or translated into any durable scheduler-acceptance/scheduling-debt state.

A normal method return from `enqueueUniqueWork(...)` therefore establishes request issuance only; it does not prove asynchronous acceptance or completion.

### 3. Durable model has no scheduler-acceptance/debt state

`HistoryDateFetchOperation` stores semantic operation state (`RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`), cancellation, counts, metrics, timestamps, and terminal reason. It has no field representing:

- scheduler request pending;
- enqueue accepted;
- enqueue rejected;
- durable scheduling debt/outbox ownership;
- retry generation for scheduler publication.

The child ledger similarly records semantic item states but no WorkManager-carrier acceptance.

Thus asynchronous enqueue rejection cannot be represented truthfully in the durable operation model; the operation simply remains ordinary `RUNNING` with `PENDING` children.

### 4. Startup reconciliation repeats the same unconfirmed handoff

`HistoryDateFetchManager.reconcile()` enumerates nonterminal operations that the notification policy says should restore at startup, republishes active notification state, and calls the same `enqueue(operationId)` method.

`ExistingWorkPolicy.KEEP` is useful for idempotence when exact unique work already exists, but it is not evidence that a prior or current enqueue request was accepted.

If scheduler publication repeatedly rejects asynchronously, reconciliation repeats the same fire-and-forget request without recording acceptance/failure. The durable row remains indistinguishable from a normally carried RUNNING operation.

### 5. Concrete failure chains remain reachable

Asynchronous rejection:

`createOrReconnect() Room transaction commits RUNNING + PENDING snapshots`
→ `enqueueUniqueWork(...) request is issued`
→ returned WorkManager `Operation` is discarded`
→ asynchronous enqueue failure/rejection occurs`
→ no worker carrier exists`
→ no durable scheduling-debt/failure state is written`
→ operation remains nonterminal and appears active`.

Process death:

`Room transaction commits exact operation/children`
→ process dies before scheduler acceptance is established`
→ restart discovers the nonterminal operation`
→ reconcile repeats the same unconfirmed enqueue path`
→ correctness still depends on a later enqueue succeeding, with no durable representation of repeated failure`.

This satisfies the blocker confirmation chain:

`user/startup request`
→ `durable RUNNING/PENDING Room state`
→ `unconfirmed asynchronous WorkManager handoff`
→ `carrierless operation can remain durably active without progress or truthful scheduler-failure state`.

## Existing test coverage does not close the root

`HistoryDateFetchEnqueuePolicyTest` still verifies only that `HistoryDateFetchEnqueuePolicy.workPolicy == ExistingWorkPolicy.KEEP`.

It does not exercise:

- asynchronous WorkManager enqueue rejection;
- the returned WorkManager `Operation`;
- process death after DB commit/before confirmed enqueue;
- repeated reconciliation failure;
- eventual retry success from durable scheduling debt;
- cancellation racing scheduler acceptance.

## Stable remediation boundary

The prior correction boundary remains valid:

1. make scheduler acceptance part of the History date-fetch handoff, or persist an explicit durable scheduling-debt/outbox state before depending on a worker carrier;
2. await/observe the returned WorkManager `Operation`, or establish an equivalent confirmed-carrier protocol;
3. represent enqueue failure truthfully and retain exact retry ownership rather than leaving ordinary RUNNING/PENDING state with no confirmed carrier;
4. preserve exact `operationId` and immutable child source snapshots across retry/recovery;
5. preserve `ExistingWorkPolicy.KEEP` or equivalent exact-carrier duplicate prevention after acceptance semantics are made explicit;
6. make startup reconciliation converge both carrier-present and carrier-missing/debt states, including repeated failure;
7. preserve cancellation authority if cancellation wins while scheduler publication is pending;
8. do not conflate this handoff root with F15 typed date lookup semantics or F16 child terminalization semantics.

Future closure coverage should deterministically exercise DB-commit→enqueue process death, asynchronous enqueue rejection, repeated rejection, retry success, duplicate manual starts/reconnect, and cancellation racing enqueue acceptance using production wiring rather than only the policy constant.

INDEPENDENT EXECUTION: NOT EXECUTED