# BUG-SCHEDULE-01 — current CLEAN-basis carry-forward revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `51c16bd7b03294df374f7f773b8dea5e97f3f9dd` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / existing P2 `BUG-SCHEDULE-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change overlap

The cumulative `aa1616a2... -> 9c5191c3...` F3 work modifies `ObserveSourceWorker`, so the previous schedule finding cannot be carried forward solely from file non-overlap. The exact current Observe consumer was therefore reopened.

The other established scheduler-authority files remain unchanged in that cumulative range:

- `AlarmScheduler.kt`
- `CancelScheduledDownloadWorker.kt`
- `DownloadWorker.kt`
- settings/platform capability callers

At `9c5191c3...`, the changed Observe worker still consumes the same scheduler authority:

- when queued observed/requeued items exist, it reads `use_scheduler`;
- outside the window, if `alarmScheduler.canSchedule()` is true it defers to `alarmScheduler.schedule()`;
- otherwise it starts the download path.

The F3 source-authority/lifecycle changes do not replace or correct the scheduler window/capability contract.

## Current source revalidation

### 1. Cross-midnight window remains incorrect

`AlarmScheduler.isDuringTheScheduledTime()` still keeps the current hour in `0..23` while adding 24 only to an ending hour that falls before the start.

For `22:00 -> 05:00`, the end becomes 29 but current `01:00` remains 1, so the predicate returns false inside the intended window.

The END boundary setup also still zeros `sTime` seconds a second time instead of normalizing `eTime`, leaving END seconds/milliseconds dependent on the current Calendar state.

### 2. API 24–30 capability semantics remain inconsistent

`AlarmScheduler.canSchedule()` still returns false below API 31. This conflates absence of the API-31 exact-alarm special-access gate with inability to schedule on earlier supported Android versions, while other settings/callers treat pre-31 scheduling as supported.

The same persisted scheduler setting can therefore still produce inconsistent business semantics by caller/platform band.

### 3. Stale END generation remains unfenced

`CancelScheduledDownloadWorker` still does not consume the scheduler `handoffId` / `handoffRequestId` transported by the durable handoff producer.

After only the process-local `isStopped` check it calls `cancelAllWorkByTag("download")`, then walks active/post-processing Downloads and can cancel native work and requeue execution generations.

There is no durable scheduler-generation revalidation immediately before the broad cancellation or per-execution cancellation/requeue side effects.

### 4. Stale START generation remains unfenced

Exact `DownloadWorker.kt@9c5191c3...` still has no `handoffId` consumer for scheduler START authority.

A previously accepted START worker can therefore survive scheduler disable/reconfiguration/replacement and continue toward queue observation/claim without proving its scheduler generation is still current.

### 5. Observe consumer remains exposed

The F3-reviewed `ObserveSourceWorker` still calls the same `AlarmScheduler.isDuringTheScheduledTime()` / `canSchedule()` predicates when deciding whether observed Downloads should start now or be deferred.

Thus the cross-midnight/platform-capability defects remain production authority for Observe-triggered queue liveness after the F3 lifecycle changes.

## Correction boundary

The prior correction boundary remains unchanged:

1. canonical same-day/cross-midnight interval semantics with exact boundary normalization;
2. consistent platform capability semantics for API 24–30 and 31+;
3. one contract shared across manual/Observe/worker callers;
4. durable current scheduler generation published on enable/reconfigure/disable;
5. START consumer revalidation immediately before scheduler-authorized queue claim;
6. END consumer revalidation before broad cancellation and again before correctness-relevant per-execution mutation;
7. stale scheduler consumers must exit without semantic side effects;
8. production tests for window edges/platform bands and START/END supersession races.

## Root reconciliation

- Window/capability and START/END generation subcases remain one existing P2 `BUG-SCHEDULE-01` root.
- No new root or severity change is established.
- `BUG-OBSERVE-01` remains CLOSED; its lifecycle/source-authority work does not close scheduler authority.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
