# BUG-DATE-03 — exact CLEAN-basis durable scheduler-handoff revalidation

Date: 2026-09-13

## Exact review state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior current-basis checkpoint: `3ee656aba057f788b319265ba5762237e697d4c5` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- The F10+F16+F17 implementation wave remains active; no in-progress implementation diff was inspected or used as evidence.

## Verdict

**NOT_CLEAN — existing P2 `BUG-DATE-03` remains OPEN / CONFIRMED at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Overall canonical state remains `NOT_CLEAN`.

## Relation to F15/F16

`BUG-DATE-03` remains distinct from the Master Plan date roots:

- F15 / `BUG-DATE-01` governs typed extractor lookup outcomes and authoritative absence and is independently CLOSED at 97342490.
- F16 / `BUG-DATE-02` governs typed child distribution, parent terminal state, WorkManager result, retry/exhaustion, and notification and is currently under active implementation.
- `BUG-DATE-03` is the producer-to-scheduler durable handoff root: a nonterminal History-date operation can be committed without confirmed WorkManager carrier acceptance.

## Intervening-range reconciliation

Exact compare `3616ae02... -> 90afaec1...` does not modify `HistoryDateFetchManager.kt` or `HistoryDateFetchRepository.kt`. Exact final-basis files were still re-read directly.

Later duplicate-admission/backup changes do not add scheduler acceptance, scheduling debt, or durable outbox semantics to the date-fetch operation.

## Exact current production evidence

### 1. Durable operation state still precedes scheduler acceptance

`HistoryDateFetchManager.startOrReconnect()` calls `repository.createOrReconnect()` first and only then calls `enqueue(operation.operationId)`.

`HistoryDateFetchRepository.createOrReconnect()` commits the operation and immutable child snapshots in one Room transaction. A new operation is ordinary nonterminal state with PENDING children before any scheduler carrier is confirmed.

### 2. Enqueue remains fire-and-forget

`HistoryDateFetchManager.enqueue()` calls:

`workManager.enqueueUniqueWork(uniqueWorkName(operationId), ExistingWorkPolicy.KEEP, request)`

The returned asynchronous WorkManager `Operation` is discarded. It is not awaited, observed, or translated into durable accepted/rejected/debt state.

Therefore a normal return from the method proves only request issuance, not scheduler acceptance.

### 3. Durable model still has no scheduler-debt authority

The repository records semantic operation/item state and progress, but the exact current composition has no durable field/carrier for:

- scheduler request pending;
- enqueue accepted;
- enqueue rejected;
- scheduling debt/outbox ownership;
- scheduler publication retry generation.

If asynchronous enqueue fails, the durable operation remains indistinguishable from an ordinary carried RUNNING/PENDING operation.

### 4. Startup reconciliation repeats the same unconfirmed handoff

`HistoryDateFetchManager.reconcile()` discovers nonterminal operations and calls the same `enqueue(operationId)` path.

`ExistingWorkPolicy.KEEP` prevents duplicate unique work when accepted work already exists. It does not prove that the current or previous enqueue request was accepted.

Repeated asynchronous rejection can therefore leave the operation durably active while every reconciliation attempt remains unobserved fire-and-forget.

## Concrete failure chain

`createOrReconnect() commits RUNNING/PENDING Room state`
→ `enqueueUniqueWork(...) request issued`
→ returned WorkManager Operation discarded
→ asynchronous rejection/failure
→ no worker carrier exists
→ no durable scheduling failure/debt state is written
→ operation remains nonterminal and appears active without progress.

Process death between DB commit and confirmed scheduling has the same root: restart rediscovers the operation but repeats the same unconfirmed publication protocol.

This remains a concrete producer -> durable state -> unconfirmed external handoff -> carrierless durable operation blocker chain.

## Root/count reconciliation

- Same existing P2 `BUG-DATE-03`; count delta `0`.
- Keep separate from CLOSED F15 and active F16.
- No later reviewed change closes or aliases this scheduler-handoff root.

## Stable remediation boundary

A future correction must make scheduler acceptance explicit or durably own scheduling debt before relying on a worker carrier:

1. observe/await the returned WorkManager Operation or use an equivalent confirmed-carrier protocol;
2. persist truthful enqueue rejection/debt and exact retry ownership;
3. preserve exact operationId and immutable child snapshots across retry/recovery;
4. retain KEEP or equivalent exact-carrier duplicate prevention after acceptance semantics are explicit;
5. make startup reconciliation converge carrier-present, carrier-missing, repeated-rejection, and eventual-success states;
6. preserve cancellation if cancellation wins while scheduler publication is pending;
7. do not conflate this with F15 lookup semantics or F16 child/parent terminal semantics.

Closure coverage should deterministically exercise DB-commit-before-enqueue process death, asynchronous rejection, repeated rejection, eventual retry success, duplicate reconnect/start, and cancellation racing enqueue acceptance through production wiring.

INDEPENDENT EXECUTION: NOT EXECUTED