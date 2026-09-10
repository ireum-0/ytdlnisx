# Independent Track A checkpoint — scheduler S6 and stale Download deletion authority

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Canonical working recount

- P0: 2
- P1: 3
- P2: 21

This checkpoint adds one new P2 root, `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`. Scheduler S6 is additional evidence under the already-counted `BUG-SCHEDULE-01` root.

## BUG-SCHEDULE-01 — S6 semantic namespace collision

The settings schema exposes `use_alarm_for_scheduling` and `use_scheduler` as independent switches; both may be enabled.

However global daily-window start and explicit future `downloadStartTime` AlarmManager scheduling share the same durable start-boundary semantic namespace:

- `AlarmScheduler.schedule()` prepares a `START_BOUNDARY` handoff for the configured daily start.
- `AlarmScheduler.scheduleAt(at)` also prepares `START_BOUNDARY` for a specific future Download group.
- WorkManager handoff recovery uses the same logical start work identity for that boundary class.
- durable replacement of an outstanding START boundary therefore supersedes the other meaning.

The AlarmManager PendingIntents use different request codes for global start vs explicit future start, so an old platform alarm may still fire after its durable carrier has been superseded. When that happens, exact handoff recovery sees the old carrier as superseded and no longer authorizes its intended action.

Concrete conflicts:

1. daily window start is prepared; a later explicit `scheduleAt(T)` supersedes the daily start carrier;
2. explicit T is prepared; a later `schedule()` call supersedes T;
3. both platform alarms can still exist, but only the latest shared START carrier retains semantic authority.

Acceptance addition: global recurring-window generation and explicit per-download-time generation require separate durable namespaces/keys. Reconciliation must independently preserve/revoke each semantic schedule.

No extra P2 count; this remains `BUG-SCHEDULE-01`.

## New P2 — BUG-DOWNLOAD-DELETE-SNAPSHOT-01

### Invariant

A stale Download status snapshot or numeric ID must not authorize deletion of a newer current Download state/generation. Any automatic/status-scoped deletion must revalidate the current row atomically at the destructive boundary, including status and semantic execution/retry ownership.

### Production evidence at fixed basis

`CleanUpLeftoverDownloads.doWork()` performs:

1. `downloadRepo.deleteCancelled()`;
2. `downloadRepo.deleteErrored()`;
3. optional app DOWNLOAD_TEMP cleanup if active count is zero.

`DownloadRepository.deleteErrored()` first evaluates `getErroredDownloads()` and passes that list to `deleteKnownUserRemoval(items)`.

`deleteKnownUserRemoval(items)`:

- derives only `ids = items.map(DownloadItem::id)` from the earlier snapshot;
- enters a Room transaction;
- terminalizes linked children and deletes History-replacement barriers for those IDs;
- calls `downloadDao.deleteAllWithIDs(ids)` unconditionally;
- does not re-read or compare current `status`, `executionId`, `operationId`, retry attempt/strategy, or row revision before deletion.

After the transaction it calls `deleteCache(items)`. Cache deletion is partially protected by `DownloadCacheOwnership.deleteIfOwned()` using exact operation/execution marker identity, but that does not repair the unconditional DB row deletion.

### Concrete race

1. cleanup reads Download D while D is `Error`;
2. before cleanup's delete transaction, the production retry path reads that Error row and successfully CAS-transitions it toward Processing/Queued using the expected Error snapshot;
3. cleanup then enters `deleteKnownUserRemoval(oldErrorSnapshot)` and deletes current D by numeric ID without verifying that D is still the same Error generation;
4. the retry's durable row and linked state can be removed even though Error status no longer authorizes automatic cleanup.

The shared helper is also used by status/batch deletion callers, so the correction should be at the destructive primitive rather than only in `CleanUpLeftoverDownloads`.

### Impact

A newer retry/current Download generation can disappear because an older status snapshot retained destructive authority. Linked low-quality/history-replacement state can also be terminalized/removed using the same stale ID set. The exact cache ownership check limits arbitrary cache deletion but does not protect Room authority.

### Acceptance

- status-scoped cleanup captures a typed deletion precondition, not bare IDs;
- inside the same transaction that deletes/terminalizes linked state, re-read current row and require expected status plus semantic generation fields (`executionId`, `operationId`, retry attempt/revision as appropriate);
- a changed row is skipped, not deleted;
- linked ledger/barrier mutations use only rows whose deletion precondition succeeded;
- automatic cleanup and UI/batch callers cannot turn an old selection/status snapshot into authority over a newer running/queued retry;
- regression tests interleave Error snapshot -> retry CAS -> cleanup delete boundary, plus stale queued/scheduled/batch selections.

## Non-findings retained in this pass

- Hard-sub Scan Now uses the dedicated durable WorkManager handoff recovery path and observes exact enqueue ownership.
- Low-quality re-download manager observes WorkManager `Operation.result` and schedules durable enqueue convergence on failure.
- History-date cancellation has durable `cancelRequested`; startup re-enqueues RUNNING operations, and the worker converges cancellation through `finishCancellation()`.
- Metadata background refresh timestamp work names are not separately counted here; no equivalent durable pending-owner state was found, and its confirmed correctness risks remain F13/F14.

## Review Basis

No Review Basis advancement. It remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
