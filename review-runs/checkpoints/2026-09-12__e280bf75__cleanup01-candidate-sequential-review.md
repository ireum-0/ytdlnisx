# Task 008 / BUG-CLEANUP-01 candidate sequential independent review

Date: 2026-09-12

## Review basis and candidate isolation

- Canonical independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Overnight queue base: `36b43464b8106d90d672ba94718ccee58f38974f`
- Candidate: `e280bf758bac0d7e921b23694a64cad13ea02bf2`
- Candidate is exactly one commit ahead of queue base.
- Compare against canonical CLEAN basis is diverged by one commit on each side with merge base exactly `36b43464b8106d90d672ba94718ccee58f38974f`; candidate remains isolated and not integrated.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F10 `BUG-CLEANUP-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN for `BUG-CLEANUP-01`. Do not replay/integrate this candidate as-is.**

Count delta: **0**. The findings below are residual scope of the already-counted P2 `BUG-CLEANUP-01`, not new roots.

Canonical blocker count remains **P0 2 / P1 1 / P2 34**.

Canonical CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## What the candidate fixes correctly

The candidate addresses several important parts of F10 correctly:

- replaces timestamp-derived unique-work names with one stable logical owner name;
- centralizes preference-change, startup reconciliation, and successor scheduling in `CleanupScheduleCoordinator`;
- persists a generation token and fences stale workers against current cadence/generation;
- disable cancels cleanup-tagged future work;
- startup reconciliation can recreate a missing current carrier;
- daily/weekly recurrence uses local calendar increments;
- monthly recurrence preserves an anchor day across short months (e.g. Jan 31 -> Feb 28/29 -> Mar 31) rather than fixed 30-day arithmetic;
- focused JVM policy tests exercise daily/weekly and short/leap-month behavior;
- Android wiring tests were added for cadence replacement, stale-generation fencing, disable, and startup reconciliation.

These pieces should be preserved.

The queue report explicitly records an instrumentation caveat for task 008; unavailable/not-executed instrumentation is not converted into PASS by this review.

## Residual blocker A: successor scheduling REPLACEs the currently running unique worker

`CleanUpLeftoverDownloads.doWork()` performs cleanup and, before returning `Result.success()`, calls `CleanupScheduleCoordinator.scheduleSuccessor(...)`.

`scheduleSuccessor()` calls `enqueueNextLocked(...)`, which always executes:

```kotlin
workManager.enqueueUniqueWork(
    WORK_NAME,
    ExistingWorkPolicy.REPLACE,
    request,
)
```

The currently executing cleanup request is itself the unfinished unique work identified by `WORK_NAME`.

Android WorkManager's `ExistingWorkPolicy.REPLACE` contract is explicit: when unfinished work with the same unique name exists, it is cancelled/deleted and the new work is inserted. WorkManager's unique-work documentation likewise states that `REPLACE` cancels pending work bearing the same unique name.

Therefore the normal success path asks WorkManager to replace/cancel the very worker that is still executing `doWork()`. Cancellation is best-effort, so the exact visible timing can vary, but the design does not provide the required clean "successful run -> one successor" ownership transition. It can mark/stop the current run as cancelled while it is trying to return success and makes the recurrence semantics dependent on cancellation timing.

The added Android wiring tests call `scheduleSuccessor()` against enqueued work, but do not execute the real RUNNING worker self-successor path.

## Residual blocker B: a transient cleanup failure can permanently terminate recurrence

The preferred F10 contract explicitly requires retry-safe recurrence: retry must not duplicate a successor, and a recurring cleanup schedule must remain durable.

`CleanUpLeftoverDownloads.doWork()` currently performs repository/cache cleanup without a surrounding retry/terminal policy. `scheduleSuccessor()` is reached only after all cleanup operations succeed. If `deleteCancelled()`, `deleteErrored()`, ledger refresh, active-count lookup, or cache deletion throws, `doWork()` exits before successor creation and does not return `Result.retry()`.

The one-time carrier can therefore fail with no successor, permanently ending the logical recurring schedule until a later app-start reconciliation happens to repair it. Even then, recurrence depends on a future process/app start rather than the durable schedule itself.

The candidate has no deterministic test for:

- transient worker failure -> WorkManager retry of the same logical occurrence;
- no successor while retry is pending;
- later successful retry -> exactly one successor.

This leaves a core F10 invariant unproven and, by control flow, unmet.

## Minimal correction boundary

Preserve the calendar/generation/reconciliation model, but separate the two enqueue semantics:

1. preference change/startup repair may replace the logical chain when authority changes or the carrier is missing;
2. a currently running valid worker must attach/create its successor without cancelling itself (for example through a chain/append design whose exact WorkManager semantics are verified for the project version, or another explicit handoff that does not `REPLACE` the running request);
3. cleanup execution needs an explicit transient failure policy that returns `Result.retry()` where appropriate;
4. successor creation occurs only after the logical occurrence has completed successfully and must be idempotent if the worker is retried/re-entered;
5. generation/cadence fencing must remain authoritative so a stale run cannot resurrect an old schedule after change/disable.

No schema migration is indicated by these residuals.

## Required focused regression coverage

Add deterministic production-level coverage for at least:

- a RUNNING cleanup worker schedules its successor without the running worker becoming cancelled/replaced;
- successful run leaves exactly one delayed successor;
- transient cleanup failure returns retry and leaves no duplicate successor;
- retry then success leaves exactly one successor;
- cadence change while an old run is active fences the old successor and leaves exactly one new-generation schedule;
- disable while an old run is active prevents resurrection;
- startup after an interrupted handoff reconciles to exactly one current schedule;
- existing 28/29/30/31-day, year-boundary, local-calendar/DST behavior remains covered.

## Candidate disposition

`e280bf758bac0d7e921b23694a64cad13ea02bf2` is **NOT_CLEAN** and must not be replayed as-is. Its stable owner name, generation fencing, startup reconciliation, and calendar-month policy are reusable, but worker-to-successor ownership and retry semantics must be corrected before `BUG-CLEANUP-01` is closed.

INDEPENDENT EXECUTION: NOT EXECUTED
