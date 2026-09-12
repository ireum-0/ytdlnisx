# BUG-CLEANUP-01 — exact CLEAN-basis revalidation

Date: 2026-09-12

## Exact review state

- Fixed contiguous independently CLEAN basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Root: F10 / existing P2 `BUG-CLEANUP-01`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Prior F10 exact-basis revalidation: `bed76f6c857391ccdb89727238baa5bba314715d` at `93d01d2a...`.
- Exact remote duplicate-admission verification is running separately against `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`; this F10 decision uses only the fixed CLEAN basis and does not depend on that later implementation state.

## Verdict

**OPEN / NOT_CLEAN — existing P2 `BUG-CLEANUP-01` remains confirmed at exact CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30** while `BUG-DUPLICATE-ADMISSION-01` is still pending exact-SHA execution closure.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- F10 remains a hard prerequisite for F11 / P0 `BUG-BACKUP-03`.

## Intervening-range verification

The exact range `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c..3616ae02e56995e795cc52f3074d8c3d1cd2e330` is the CACHE-01/CACHE-02 remediation range. It modifies cache/download/terminal authority files but does not modify:

- `app/src/main/java/com/ireum/ytdl/ui/more/settings/DownloadSettingsFragment.kt`;
- `app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt`;
- the application startup composition in a way that adds a cleanup schedule reconciler.

Fresh exact `3616ae02...` production source was re-read.

## Exact current production evidence

### 1. Preference change creates a one-shot carrier under a fresh logical name

`DownloadSettingsFragment` reads `cleanup_leftover_downloads` and computes an initial local-calendar delay:

- daily: `Calendar.DAY_OF_WEEK + 1`;
- weekly: `Calendar.DAY_OF_WEEK + 7`;
- monthly: `Calendar.MONTH + 1`;
- disabled: `cancelAllWorkByTag("cleanup_leftover_downloads")`.

For an enabled cadence it creates one `OneTimeWorkRequest<CleanUpLeftoverDownloads>` tagged `cleanup_leftover_downloads`, then calls:

`enqueueUniqueWork(System.currentTimeMillis().toString(), ExistingWorkPolicy.REPLACE, ...)`.

The unique-work name is a new timestamp for every preference mutation. `REPLACE` therefore does not establish one stable logical cleanup owner across edits. Multiple independently named future carriers can exist unless tag cancellation happens to remove them, and there is no generation/cadence identity carried by the work request.

The historical claim that the current initial monthly delay is a fixed 30 days is **not** current evidence: this source already uses `Calendar.MONTH + 1`. The defect is durable recurring schedule authority, not that initial calculation.

### 2. Successful worker execution terminates recurrence

`CleanUpLeftoverDownloads.doWork()`:

1. establishes foreground notification state;
2. deletes Cancelled downloads;
3. deletes Errored downloads;
4. deletes download-temp cache when active-download count is zero;
5. returns `Result.success()`.

It does not:

- read or validate the current cleanup cadence;
- consume a cadence/generation token;
- compute the next calendar occurrence;
- enqueue a successor;
- reconcile duplicate/stale carriers.

A successful run therefore consumes the only one-time carrier and recurrence stops until another preference mutation creates new work.

### 3. Startup has no cleanup-schedule reconciliation owner

`App.onCreate()` starts reconciliation for Download execution/finalization, Terminal execution/publication, WorkManager handoffs, low-quality redownload state, automatic-keyword observation coverage, and History date fetch. There is no cleanup schedule reconciliation owner that reconstructs one correct future occurrence from the persisted cleanup preference.

Thus process restart cannot repair a missing/consumed cleanup carrier into the intended recurring schedule.

### 4. Cadence edits/disable lack stale-run fencing

The request carries no stable cadence generation/token checked at execution time. Tag cancellation is an asynchronous request, not execution-time proof that an already admitted/running old occurrence no longer has authority. A stale old occurrence has no semantic fence preventing it from acting as if it still represents the selected schedule.

This remains one existing root: exactly one logical cleanup schedule does not durably preserve the selected **calendar** cadence across success, retry/re-entry, cadence change, disable, and restart.

## Stable correction boundary retained

F10 remains implementation-ready under the Master Plan:

1. one stable logical cleanup schedule owner;
2. calendar-aware daily/weekly/monthly next occurrence, with monthly advancing by one calendar month rather than a fixed duration;
3. persisted/current cadence identity or generation sufficient to reject stale runs;
4. cadence change replaces exactly one future occurrence;
5. disable prevents stale generations from acting or resurrecting recurrence;
6. transient failure retries the same logical occurrence without creating a successor;
7. successful logical completion schedules exactly one successor without self-replacement;
8. startup reconciles persisted cadence state to exactly one correct future carrier;
9. deterministic coverage for month-length/year/DST/time-zone transitions, repeated success, retry-then-success, cadence change/disable during a run, duplicate prevention, and restart repair.

No Room schema migration is inherently required by this root; implementation should choose the smallest durable carrier that proves these invariants.

## Dependency consequence

F10 remains OPEN and continues to block F11 / P0 `BUG-BACKUP-03`. Do not issue the F11 Extra High plan until all hard prerequisites, including F10, are closed.

INDEPENDENT EXECUTION: NOT EXECUTED
