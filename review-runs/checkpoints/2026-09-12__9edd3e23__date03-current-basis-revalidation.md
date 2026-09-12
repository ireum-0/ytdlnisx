# BUG-DATE-03 current-basis revalidation — 2026-09-12

## Scope
- CLEAN review basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Root: existing P2 `BUG-DATE-03`
- Disposition: `OPEN / NOT_CLEAN`
- Blocker delta: `0`
- Canonical blocker count remains `P0 2 / P1 1 / P2 34`
- In-progress implementation diff was not inspected.

## Exact current-source evidence
- `app/src/main/java/com/ireum/ytdl/work/HistoryDateFetchManager.kt`
  - blob `f1c38e95332871ec2e7b5b2c0c5439f672e1dc37`
  - `startOrReconnect()` durably calls `repository.createOrReconnect()` before `enqueue(operationId)`.
  - `reconcile()` enumerates nonterminal operations and calls the same `enqueue(operationId)`.
  - `enqueue()` calls `WorkManager.enqueueUniqueWork(...)` and discards the returned WorkManager `Operation`.
  - enqueue policy remains `ExistingWorkPolicy.KEEP`.
- `app/src/main/java/com/ireum/ytdl/database/repository/HistoryDateFetchRepository.kt`
  - blob `5baefae195472a8dd8611cd01f9ae4dd1663fe17`
  - `createOrReconnect()` commits the parent `HistoryDateFetchOperation` and exact child snapshots inside one Room transaction before scheduling.
  - no enqueue-acceptance state or durable scheduling debt is written by the manager if WorkManager enqueue fails asynchronously.
- `app/src/test/java/com/ireum/ytdl/work/HistoryDateFetchEnqueuePolicyTest.kt`
  - blob `e3033a7e7244b911223375c0421d2dd4c51c6d65`
  - verifies only `ExistingWorkPolicy.KEEP`; it does not test scheduler acceptance/failure or repair of a carrierless durable operation.

## Failure boundary
Room can commit a normal nonterminal RUNNING/PENDING operation before WorkManager has accepted any worker carrier. Since the returned WorkManager `Operation` is ignored, asynchronous enqueue rejection leaves a durable operation that looks active but has no confirmed worker. Startup reconciliation retries through the same fire-and-forget path, so repeated rejection remains unrepresented and can strand the operation. Process death after the Room commit and before enqueue has the same dependency on later reconciliation.

`KEEP` is useful for retry idempotence if an earlier enqueue actually succeeded, but it is not evidence that a carrier exists or that the current enqueue was accepted.

## Required correction boundary
1. Make scheduler acceptance part of the History date-fetch handoff or persist explicit durable scheduling debt/outbox state.
2. Await/observe the returned WorkManager `Operation`, or establish an equivalent confirmed-carrier protocol.
3. Represent enqueue failure truthfully and retry idempotently instead of leaving ordinary RUNNING/PENDING progress with no confirmed carrier.
4. Preserve exact `operationId` and child snapshot identity across retry.
5. Preserve `ExistingWorkPolicy.KEEP` or an equivalent exact-carrier duplicate-prevention rule.
6. Make startup reconciliation confirm/record enqueue acceptance or failure and converge after repeated failures.
7. Cover process death after DB commit/before enqueue, async enqueue rejection, repeated rejection, retry success, duplicate manual starts, and cancellation racing enqueue.

## Review disposition
`BUG-DATE-03` remains an existing P2 blocker. No blocker count or CLEAN review basis change is justified.

INDEPENDENT EXECUTION: NOT EXECUTED