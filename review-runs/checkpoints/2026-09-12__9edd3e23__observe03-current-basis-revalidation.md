# BUG-OBSERVE-03 current-basis revalidation — 2026-09-12

## Scope
- CLEAN review basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Root: existing P2 `BUG-OBSERVE-03`
- Disposition: `OPEN / NOT_CLEAN`
- Blocker delta: `0`
- Canonical blocker count remains `P0 2 / P1 1 / P2 34`
- The active BUG-KEYWORD-04 review-fix implementation diff, including observed in-progress HEAD `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`, was not inspected.

## Exact current-source evidence
- `app/src/main/java/com/ireum/ytdl/work/ObserveSourceWorker.kt`
  - blob `b4796455f8eab4da87bbedc5cb94fbef059c34ec`
  - ordinary recurring completion persists the source/run completion state through `repo.update(item)` before publishing the next worker carrier.
  - it then builds the successor `OneTimeWorkRequest` and calls `WorkManager.getInstance(context).enqueueUniqueWork("OBSERVE$sourceID", ExistingWorkPolicy.REPLACE, request)`.
  - the returned WorkManager `Operation` is not awaited/observed, and the current worker returns `Result.success()`.
  - recovery through `recoverFailedRun()` can converge into the same `finishRunAndSchedule()` publication boundary.
- `app/src/main/java/com/ireum/ytdl/database/repository/ObserveSourcesRepository.kt`
  - blob `d4998a9566867ecf611bcd314699e0cc2f90d9f8`
  - ordinary `observeTask()` cancels current Observe work and fire-and-forget enqueues the replacement through the same `OBSERVE<sourceId>` unique-work namespace with `REPLACE`.
  - this path also does not establish confirmed scheduler acceptance before treating the scheduling call as complete.
- `app/src/main/java/com/ireum/ytdl/App.kt`
  - exact CLEAN-basis startup inspection found recovery/reconciliation for several other durable work flows, including download handoff and History date fetch, but no general startup reconciler was found that owns ordinary recurring `OBSERVE<sourceId>` carrier debt.
- `app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt`
  - the durable/confirmed Observe-related handoff present here is the narrower confirmed-retry flow (`OBSERVE_RETRY_DOWNLOAD`), which persists a request identity, observes WorkManager acceptance, and has reconciliation semantics.
  - that special path does not own ordinary recurring `OBSERVE<sourceId>` successor publication and therefore does not close this root.

No inspected focused test established the missing invariant that ordinary recurring Observe durable ACTIVE intent always has an accepted successor carrier, or that a rejected/lost ordinary successor is deterministically repaired after restart. This is not a claim that no Observe tests exist elsewhere; it is the disposition of the inspected evidence for this root.

## Failure boundary
An ordinary recurring Observe run can finish its durable source/run update first, then attempt successor publication through `enqueueUniqueWork(...)` without confirming the returned WorkManager operation. If WorkManager rejects/fails the enqueue asynchronously, or the process dies after the DB update and before successful carrier publication, the current worker can be gone while durable source state still represents an ongoing recurrence and no successor carrier is confirmed.

Because the ordinary recurring path has no identified durable scheduling-debt/outbox state and no general startup reconciliation for `OBSERVE<sourceId>`, the next run can be lost indefinitely. `ExistingWorkPolicy.REPLACE` controls replacement semantics when WorkManager receives the request; it is not proof that WorkManager accepted a successor.

This is separate from P0 `BUG-OBSERVE-HANDOFF-01`: that root concerns stale ordinary workers lacking current immutable configuration-generation authority at mutation/publication boundaries. `BUG-OBSERVE-03` concerns accepted/durable ownership of the ordinary successor carrier itself. The eventual correction must compose with the P0 generation fence rather than merge the findings conceptually.

## Required correction boundary
1. Make ordinary recurring Observe successor publication a confirmed handoff, or persist explicit durable successor scheduling debt/outbox state before the current carrier can finish successfully.
2. Await/observe the returned WorkManager `Operation`, or establish an equivalent proof that the exact successor carrier was accepted.
3. Bind any durable successor debt/carrier identity to the exact source/configuration generation so stale generations cannot schedule successors.
4. Preserve recurrence delay semantics while making retries idempotent and preventing duplicate logical successor carriers.
5. Add startup reconciliation that distinguishes an existing accepted exact carrier from missing/rejected carrier state and repairs the latter deterministically.
6. Represent repeated scheduler rejection truthfully rather than leaving ordinary ACTIVE recurrence with no carrier and no durable retry obligation.
7. Preserve the separate confirmed-retry (`OBSERVE_RETRY_DOWNLOAD`) protocol and do not accidentally make it authoritative for ordinary recurrence.
8. Cover asynchronous enqueue rejection, process death after durable run completion but before enqueue acceptance, repeated rejection, restart repair, retry success, STOP/edit/delete racing successor publication, and stale-generation attempts to publish a successor.

## Review disposition
`BUG-OBSERVE-03` remains an existing P2 blocker. No blocker count or CLEAN review basis change is justified.

INDEPENDENT EXECUTION: NOT EXECUTED