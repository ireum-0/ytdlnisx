# BUG-CLEANUP-01 — recurring cleanup current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F10 / `BUG-CLEANUP-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Overnight candidate `e280bf758bac0d7e921b23694a64cad13ea02bf2` remains isolated and was independently NOT_CLEAN at checkpoint `1fc97d78e86583226eda8a89908e9d4068b1ae37`.
- F10 is a hard prerequisite of F11 `BUG-BACKUP-03`.
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P2 `BUG-CLEANUP-01` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

- Canonical blocker-count delta: `0`
- Canonical blocker count remains **P0 2 / P1 1 / P2 34**.
- CLEAN Review Basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Overall canonical state remains `NOT_CLEAN`.

## Exact current production evidence

### 1. Preference change schedules only one delayed one-time request

`DownloadSettingsFragment` handles `cleanup_leftover_downloads` preference changes by computing one `nextTime` for daily, weekly, or monthly and creating one `OneTimeWorkRequest<CleanUpLeftoverDownloads>` with that initial delay.

The request is enqueued with a unique-work name derived from `System.currentTimeMillis().toString()` rather than a stable logical schedule owner.

A timestamp-derived name means repeated cadence edits create different unique-work chains instead of replacing one stable logical cleanup schedule. Tag cancellation on disable can cancel tagged requests, but there is no generation/cadence ownership token proving which surviving request is current.

### 2. The worker never schedules a successor

`CleanUpLeftoverDownloads.doWork()` deletes cancelled/errored rows, refreshes low-quality ledgers, optionally deletes download-temp cache, and returns `Result.success()`.

It never reads the selected cadence, computes the next calendar occurrence, or enqueues a successor.

Therefore normal successful execution consumes the only carrier and the selected "daily"/"weekly"/"monthly" preference ceases to produce future cleanup runs until the user edits the preference again.

This directly reproduces the original F10 root: automatic cleanup is effectively one delayed request, not a durable recurring schedule.

### 3. No startup reconciliation or stale-cadence fencing exists

Current source has no centralized cleanup schedule coordinator or startup reconciliation path that ensures exactly one current carrier exists after process/app restart.

There is likewise no persisted generation/cadence token checked by the worker before scheduling or completing a logical occurrence.

A cadence change can leave an older timestamp-named request alongside a newer request, while a missing carrier is not repaired automatically on startup.

### 4. Retry/recurrence handoff is undefined

The current worker has no explicit transient-failure policy around repository/cache cleanup and no successor handoff at all. An exception can fail the one-time request, while success also terminates recurrence. There is therefore no retry-safe invariant such as "retry same occurrence, then exactly one successor on successful completion."

## Candidate relation

The isolated overnight candidate correctly introduced useful pieces that do not exist in canonical source: stable owner name, generation fencing, startup reconciliation, and local-calendar recurrence. It must not be replayed as-is because independent review found two residual blockers:

- a RUNNING worker uses same-name `ExistingWorkPolicy.REPLACE` for its successor, creating a self-replacement/cancellation path;
- cleanup exceptions can escape before successor creation with no explicit `Result.retry()` path, allowing recurrence to die.

A future canonical implementation should preserve the candidate's good calendar/generation/reconciliation design while correcting those handoff/retry semantics.

## Correction boundary

F10 remains implementation-ready when selected:

- one stable logical schedule owner;
- local-calendar daily/weekly/monthly next-occurrence policy, monthly as a calendar month rather than 30 days;
- cadence change replaces the authorized future schedule; disable cancels it;
- startup reconciles to exactly one current carrier;
- a valid RUNNING occurrence must hand off to exactly one successor without cancelling/replacing itself;
- transient cleanup failure must retry the same logical occurrence without creating a successor or duplicate chain;
- successor publication occurs only after successful logical completion and is idempotent across retry/re-entry;
- generation/cadence fencing prevents stale runs from resurrecting disabled/old schedules.

Required production-level coverage includes successful recurring handoff, retry then success, no duplicate successor, cadence change during active run, disable during active run, startup repair, and 28/29/30/31-day/year/DST/local-time behavior.

## F11 dependency consequence

F10 is independently OPEN at canonical current basis and remains a hard prerequisite blocking F11 `BUG-BACKUP-03`.

With this checkpoint, F4 through F10 now all have explicit current workflow dispositions relevant to F11:

- F4 OPEN current-basis;
- F5 OPEN current-basis;
- F6 OPEN current-basis;
- F7 OPEN current-basis;
- F8 OPEN current-basis;
- F9 OPEN current-basis;
- F10 OPEN current-basis, with an isolated NOT_CLEAN candidate providing partial reusable design evidence.

Therefore F11 must remain blocked; no Sol Extra High F11 planning or implementation prompt is warranted yet.

The separate Task 002 `BUG-KEYWORD-04` canonical replay remains the first implementation target unless an explicit workflow event changes that order.

INDEPENDENT EXECUTION: NOT EXECUTED
