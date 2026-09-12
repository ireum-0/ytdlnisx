# BUG-CLEANUP-01 — exact CLEAN-basis revalidation

Date: 2026-09-12 UTC

## Exact review state

- Fixed contiguous independently CLEAN basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Workflow state: `IMPLEMENTATION_DIFF_FROZEN_REVIEW_CONTINUES`.
- Active implementation target: F4 / `BUG-BACKUP-04`; no in-progress F4 diff was inspected or relied upon.
- Root: F10 / existing P2 `BUG-CLEANUP-01`.
- Prior exact-basis checkpoint: `f852aa4bd4dd3afe591e524322c99c1480dfe172` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-CLEANUP-01` remains valid at exact CLEAN basis `a12c5805...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 29**.
- CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- F10 remains a hard prerequisite for F11 / P0 `BUG-BACKUP-03`.

## Intervening-range verification

The accepted range `3616ae02... -> a12c5805...` is the duplicate-admission remediation/test-harness range. It does not modify `DownloadSettingsFragment.kt`, `CleanUpLeftoverDownloads.kt`, or `App.kt`. Exact final source was nevertheless re-opened.

## Exact current production evidence

### 1. Preference mutation still publishes a one-shot carrier under a fresh name

`DownloadSettingsFragment` reads the `cleanup_leftover_downloads` preference and computes an initial calendar delay:

- daily: calendar + 1 day;
- weekly: calendar + 7 days;
- monthly: calendar + 1 month;
- disabled: cancel all work tagged `cleanup_leftover_downloads`.

For an enabled cadence it creates a `OneTimeWorkRequest<CleanUpLeftoverDownloads>` tagged `cleanup_leftover_downloads`, then calls:

`enqueueUniqueWork(System.currentTimeMillis().toString(), ExistingWorkPolicy.REPLACE, ...)`.

Because every edit uses a new timestamp as the unique-work name, `REPLACE` does not identify one stable logical cleanup schedule. The request carries no durable cadence generation/token.

The current initial monthly calculation uses `Calendar.MONTH + 1`; the present root is not a fixed-30-day initial-delay bug.

### 2. Successful worker execution still terminates recurrence

`CleanUpLeftoverDownloads.doWork()` deletes Cancelled/Errored downloads, conditionally deletes download-temp cache when no active downloads exist, and returns `Result.success()`.

It does not read/validate the selected cadence, compute the next occurrence, enqueue one successor, or reconcile stale/duplicate carriers.

A successful execution therefore consumes the only one-time carrier. Recurrence stops until another preference change publishes another request.

### 3. Startup still has no cleanup schedule reconciliation owner

Exact `App.onCreate()` initializes multiple execution/publication/handoff/reconciliation owners, including Download execution, Terminal execution/publication, WorkManager handoffs, low-quality redownload, automatic-keyword observation coverage, and History date fetch.

There is still no startup owner that reads the persisted cleanup cadence and repairs it to exactly one correct future cleanup occurrence.

### 4. Cadence change/disable still lacks generation fencing

Tag cancellation is an asynchronous request, and the cleanup request carries no immutable cadence generation checked by the worker. A stale already-admitted/running occurrence therefore has no semantic execution-time fence proving that its schedule generation is still current.

## Root reconciliation

This is the same existing F10 / P2 root: exactly one logical cleanup schedule does not durably preserve the selected calendar cadence across successful execution, retry/re-entry, cadence change, disable, and process restart.

No new blocker is added; count delta is `0`.

## Stable correction boundary

The previously established implementation boundary remains valid:

1. one stable logical cleanup schedule owner;
2. calendar-aware daily/weekly/monthly next occurrence;
3. persisted/current cadence identity or generation;
4. cadence change replaces exactly one future occurrence;
5. disable prevents stale generations from acting/resurrecting recurrence;
6. retry remains the same logical occurrence without duplicate successor creation;
7. successful completion schedules exactly one successor;
8. startup reconciles persisted cadence to exactly one correct future carrier;
9. deterministic coverage for month length/year/DST/time-zone transitions, repeat success, retry, change/disable, duplicate prevention, and restart repair.

Do not issue F11/P0 planning while F10 and the other prerequisites remain open.

INDEPENDENT EXECUTION: NOT EXECUTED