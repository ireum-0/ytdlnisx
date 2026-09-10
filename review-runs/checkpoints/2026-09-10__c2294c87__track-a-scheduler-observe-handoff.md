# Independent Track A checkpoint — scheduler and Observe handoff

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Canonical working recount

After the previously sealed backup/cleanup/date pass (`abd9ea409974f23bc4eaab77aac2bc9c6d94163d`):

- P0: 2
- P1: 3
- P2: 19

This checkpoint adds two new P2 roots. It does not change P0/P1 counts.

## New P2 — BUG-SCHEDULE-01

### Invariant

The configured download scheduling window is one semantic authority across supported Android API levels and all queue producers/consumers. Cross-midnight windows must classify the same instant consistently. Platform permission capability must not change scheduling semantics on API levels where no runtime exact-alarm permission gate is required.

### Evidence at fixed basis

`AlarmScheduler.isDuringTheScheduledTime()`:

- reads current hour as `0..23`;
- when end hour is before start hour, adds 24 only to the end hour;
- does not map post-midnight current hours into the same 24+ domain.

Example: configured `22:00 -> 05:00`, current `01:00` becomes `1 in 22..29 == false` even though the instant is inside the intended window.

Production consumers:

- `DownloadViewModel` persists queued items and schedules the next alarm when this method says the app is outside the window.
- `ObserveSourceWorker` uses the same predicate to decide whether queued items start now or are deferred.
- `DownloadWorker` may cancel an otherwise idle worker when the predicate says it is outside the window.

Therefore a queue action at 01:00 in a 22:00-05:00 window can be deferred to the next 22:00; Observe and worker liveness use the same false classification.

Second subcase in the same scheduling-authority root:

- app `minSdk` is 24;
- settings UI explicitly treats API <31 as not requiring the `canScheduleExactAlarms()` permission request and calls `scheduler.schedule()` when enabling scheduling;
- `AlarmScheduler.canSchedule()` nevertheless returns `false` for every API <31;
- later `DownloadViewModel` consumes this `false` outside the window by disabling `use_scheduler` and returning an alarm-permission failure;
- `ObserveSourceWorker` consumes the same `false` differently: it falls through and starts downloads immediately instead of respecting the configured window.

Thus the same persisted scheduler preference produces contradictory execution behavior on supported API 24-30 devices.

Additional same-root hardening: `schedule()` zeroes seconds on `sTime` twice and never zeroes `eTime`, so the end boundary may inherit current seconds/milliseconds.

### Acceptance

- Represent a configured window as a canonical interval that correctly handles same-day and cross-midnight cases, including exact boundaries.
- One shared helper decides whether an instant is in-window and computes next start/end.
- For API <31, capability semantics must not report permission denial merely because `canScheduleExactAlarms()` is unavailable.
- Manual queue, Observe queue, worker idle termination, and settings use the same contract.
- Tests: 22:00-05:00 at 21:59/22:00/23:59/00:00/01:00/05:00/05:01; same-day windows; API 24-30 vs 31+ capability behavior; caller-level queue decisions.

## New P2 — BUG-OBSERVE-HANDOFF-01

### Invariant

An ACTIVE Observe source must have durable successor ownership: either an accepted exact WorkManager request or durable recovery debt that startup reconciliation can deterministically re-enqueue. DB state must not claim ACTIVE recurrence while no successor exists.

### Evidence at fixed basis

Initial/update path:

- `ObserveSourcesViewModel.insertUpdate()` first inserts/updates the durable Observe source.
- Only after the DB mutation does it call `ObserveSourcesRepository.observeTask()`.
- `observeTask()` calls `enqueueUniqueWork("OBSERVE$id", REPLACE, request)` and ignores `Operation.result`; it writes no durable handoff carrier.

Recurring worker path:

- `ObserveSourceWorker.finishRunAndSchedule()` first updates the durable source/run state.
- It then builds and directly enqueues the next `OBSERVE$id` one-time request.
- The current worker returns success after this call, but enqueue acceptance is not durably proven.

Recovery path:

- `App.onCreate()` reconciles dedicated WorkManager handoff carriers, download/terminal recovery, automatic-keyword coverage, low-quality recovery, and History-date fetch.
- It does not reconcile all ACTIVE user Observe rows against exact WorkManager ownership.
- `AutomaticKeywordObservationCoverage.reconcile()` handles managed keyword sources, but an existing managed source that is already ACTIVE is not re-enqueued merely because work is missing.

Therefore process death between DB commit and enqueue, or enqueue rejection/failure not observed by the caller, can leave a durable ACTIVE source with no future execution. Initial insertion is the simplest fixed point because no prior request exists to survive the gap.

### Acceptance

- Make Observe scheduling a durable handoff: DB state plus exact request identity/semantic generation, or an equivalent recoverable carrier.
- Observe WorkManager `Operation.result`; failed enqueue remains durable debt.
- Startup reconciles every ACTIVE source against exact successor ownership and repairs missing/stale requests idempotently.
- Reconfiguration supersedes old generation; STOPPED/delete revokes it.
- Recurring worker finalization must not report successful recurrence until successor responsibility is durably transferred.
- Tests: process death before enqueue, enqueue failure, process death after WorkManager acceptance but before local acknowledgement, source reconfiguration, STOPPED/delete race, active user source startup repair, active managed source startup repair.

## Review Basis

No Review Basis advancement. It remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
