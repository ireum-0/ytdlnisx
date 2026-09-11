# BUG-SCHEDULE-01 — schedule authority and consumer-generation revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-SCHEDULE-01`

This is a revalidation of the already-counted scheduler root. Both previously established scheduler-authority axes remain present at the exact CLEAN basis: configured-window/platform-capability semantics are inconsistent, and already-running START/END consumers do not revalidate the exact durable scheduler generation at their final claim/destructive boundary.

## 1. Cross-midnight schedule-window authority remains incorrect

Exact source: `app/src/main/java/com/ireum/ytdl/work/AlarmScheduler.kt@aa1616a2...`.

`isDuringTheScheduledTime()` reads the current hour in ordinary `0..23` form. For a cross-midnight window it adds 24 only to `endingHour`; it does not project post-midnight current times into the same 24+ domain.

For example, with `22:00 -> 05:00`:

- start hour = 22;
- end hour becomes 29;
- current `01:00` remains hour 1;
- `1 in 22..29` is false.

Thus an instant inside the intended cross-midnight window is classified as outside it.

This predicate is production authority rather than a display-only helper. Exact current consumers include:

- `DownloadViewModel`, which uses the result before deciding whether to persist/execute queued work or defer it to scheduling;
- `ObserveSourceWorker`, which decides whether observed downloads should start immediately or be deferred;
- `DownloadWorker`, whose idle termination path uses the same scheduling-window predicate.

The same incorrect interval classification can therefore change queue liveness across manual, Observe, and worker paths.

The existing end-boundary construction hardening gap also remains: `AlarmScheduler.schedule()` zeros `sTime` seconds twice and never zeros `eTime`, so the END alarm can retain current seconds/milliseconds instead of the configured minute boundary.

## 2. API 24–30 capability semantics still contradict caller behavior

`AlarmScheduler.canSchedule()` returns `false` for every API level below 31.

But `DownloadSettingsFragment` explicitly gates the exact-alarm permission request only when `Build.VERSION.SDK_INT >= 31`; on API 24–30, enabling scheduling falls through to `scheduler.schedule()`.

`DownloadViewModel` consumes the same helper differently. When scheduling is enabled and the current instant is outside the configured window, a `false` `canSchedule()` result causes it to disable the `use_scheduler` preference and return an alarm-permission-style failure instead of preserving the supported pre-31 scheduling contract.

The same persisted user setting therefore has inconsistent business semantics across supported platform bands/callers. This triggers the governing checklist's platform-capability truth-table requirement and remains part of `BUG-SCHEDULE-01`.

## 3. S8 remains: stale END consumer is not generation-fenced

`WorkManagerHandoffRecovery.prepareSchedulerBoundary()` creates an exact durable END carrier with handoff/generation identity and a request UUID. `buildRequest()` passes both `handoffId` and `handoffRequestId` into `CancelScheduledDownloadWorker` input data.

At `aa1616a2...`, `CancelScheduledDownloadWorker.doWork()` does not read either value. After only an `isStopped` process-local check, its first broad semantic side effect is:

`WorkManager.getInstance(context).cancelAllWorkByTag("download")`

It then enumerates current Active/PostProcessing rows and can cancel native work and requeue exact executions.

Producer-side cancellation/replacement is not a semantic fence once an old worker is already running. A previously accepted END generation E1 can therefore become stale after schedule disable/reconfiguration/E2 replacement and still cross broad cancellation and per-execution cancellation/requeue boundaries without consulting the durable current-generation authority.

## 4. S9 remains: stale START consumer is not generation-fenced

`WorkManagerHandoffRecovery.buildRequest()` likewise includes exact scheduler handoff/request identity in the `DownloadWorker` request used for START.

Exact `DownloadWorker.kt@aa1616a2...` contains no `handoffId` consumption. The worker can proceed through normal queue observation/admission and ultimately claim eligible queued/scheduled work without proving that the scheduler START generation that launched it is still current.

Therefore a START generation E1 that was accepted or began running before a schedule disable/reconfiguration/E2 replacement can still cross the queue-claim boundary after its producer-side carrier has been superseded.

`ExistingWorkPolicy.REPLACE`, Alarm cancellation, or durable producer tombstoning alone are insufficient because cancellation is asynchronous and the governing checklist requires final mutation/claim revalidation for stale candidates.

## Required correction boundary carried forward

The existing acceptance remains valid:

1. Represent scheduling windows with one canonical interval contract that correctly handles same-day and cross-midnight windows and exact boundaries.
2. Use that same contract for manual queue, Observe queue, worker liveness, and settings behavior.
3. API 24–30 must not be interpreted as lacking exact-alarm capability merely because the API-31 runtime special-access gate does not exist there.
4. Normalize start/end boundary construction consistently, including seconds/milliseconds.
5. START consumers must validate the exact durable scheduler generation immediately before scheduler-authorized queue claims.
6. END consumers must validate the exact durable scheduler generation immediately before broad WorkManager cancellation and again before each correctness-relevant execution cancellation/requeue boundary where stale authority could otherwise survive.
7. Disable/reconfiguration/replacement must publish revocation/current-generation state that already-running consumers can observe.
8. A stale scheduler consumer exits without semantic side effects; the current generation retains liveness across process death/retry.
9. Tests should cover at least `22:00 -> 05:00` around both boundaries, same-day windows, API 24–30 versus 31+ capability behavior, manual/Observe/worker caller decisions, and START/END supersession races at final consumer boundaries.

## Root/count and basis reconciliation

- `BUG-SCHEDULE-01` was already counted as one P2 root.
- The window/capability findings and S8/S9 are subcases/evidence of that same root.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is not modified by this independent review.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
