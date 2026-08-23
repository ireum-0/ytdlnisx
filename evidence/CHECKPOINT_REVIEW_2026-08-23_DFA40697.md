# Checkpoint Review Evidence — 2026-08-23 — `dfa40697`

## Frozen target

- Repository: `ireum-0/ytdlnisx`
- Production review branch: `checkpoint/pre-baseline-review`
- Pinned review SHA: `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- Pinned commit message: `docs: record scheduler carrier cancellation defect`
- Production-code delta from the previous ledger baseline SHA `1bd62b05abfbdd0f8217c57d7a43d05647ae3467`: none; the intervening checkpoint commit changes only `docs/codex/TASKS.md`.
- Review checklist: `ledger/remediation/REVIEW_CHECKLIST_V4_OPERATIONAL.md`, blob `96c5b1540836aea16a0e0fe4b88c6fa6791e1d81`.
- Review mode: static/source review with production-path tracing; no application source changes.

## Registry synchronization

The pinned checkpoint registry contains 74 active defects and includes `BUG-SCHEDULER-04`. The ledger baseline `TASKS.md` was still at 73 before this run. This review synchronizes `TASKS.md` to the pinned checkpoint's exact blob, then records post-split findings in `TASKS_DELTA.md`.

## Newly confirmed defect

### P2 — `BUG-SCHEDULER-05` — Treat pre-Android-12 exact-alarm capability as available

**Production path:**

`DownloadSettingsFragment` on API 24–30 permits `use_scheduler` and calls `AlarmScheduler.schedule()` -> a later queue action outside the window asks `AlarmScheduler.canSchedule()` -> the helper returns `false` for every `SDK_INT < 31` -> `DownloadViewModel` disables `use_scheduler`, reports the alarm-permission failure, and does not take the scheduler queue-persistence branch.

**Authority/proof:**

- App `minSdk` is 24, so API 24–30 is a supported runtime band.
- `AlarmScheduler.canSchedule()` returns false unconditionally below API 31.
- Settings only interprets exact-alarm special access as permission-relevant on API 31+ and otherwise allows scheduler activation.
- Android's `AlarmManager.canScheduleExactAlarms()` API was added at API 31; the special exact-alarm access model begins with Android 12 / API 31. Older supported versions must not be represented as incapable merely because this API-31 query does not exist.

**Deduplication:** distinct from `BUG-SCHEDULER-01` (daily recurrence/midnight), `BUG-SCHEDULER-03` (individual AlarmManager successor), and `BUG-SCHEDULER-04` (daily shutdown cancelling future WorkManager carriers). No existing active registry entry owns the pre-31 capability false-negative.

**Verification:** `SOURCE-LEVEL ONLY`.

## Revalidated historical Finding A evidence

The following previously frozen Finding A findings were rechecked against the pinned SHA and remain production-reachable. They are not counted as new defects from this run because `evidence/FINDING_A_REMAINING.md` already owns them.

### A1 — low-quality no-candidate cancellation winner race

`LowQualityRedownloadWorker.scan()` can perform its last `ensureRunning()` check and subsequently call `finishNoCandidates()` with no fresh cancellation check. `LowQualityRedownloadRepository.finishNoCandidates()` reads the operation and calls `LowQualityRedownloadDao.finishOperation()`, whose CAS requires only `state='RUNNING'` and does not reject `cancelRequested=1`. Therefore cancellation can commit after the last gate but before ordinary COMPLETED/FAILED terminalization.

### A3 — Download cancel mutates the Terminal ID domain

`CancelDownloadNotificationReceiver` validates the exact Download execution and durably cancels the Download, then still calls `terminalDao.delete(downloadId)`. `DownloadItem.id` and `TerminalItem.id` are independent auto-generated primary keys. A valid Download cancellation can therefore delete an unrelated Terminal row with the same numeric ID. `CancelTerminalNotificationReceiver` is separately domain-scoped, confirming that Terminal deletion has its own authority path.

### A5 — execution-lock / side-effect-lease AB/BA ordering

Canonical worker ownership cleanup and `withOwnedExecutionLease()` acquire the per-Download side-effect lease before the short global execution lock. `CancelScheduledDownloadWorker` holds the global execution lock around the batch and then waits for the per-Download lease. A worker can therefore hold a lease while waiting for the global lock as the scheduled-cancel worker holds the global lock while waiting for that same lease.

## Candidate-rejection notes

- `BUG-SCHEDULER-05` was **not** rejected as an API-availability hardening issue because the affected API 24–30 band is supported by `minSdk=24`, the queue path materially changes durable/user-requested behavior on the false result, and the platform contract proves the capability check's pre-31 meaning is wrong.
- The three revalidated Finding A paths were **not** added to `TASKS_DELTA.md` because they already have explicit historical ownership in `evidence/FINDING_A_REMAINING.md`; this run did not rediscover them as new findings.

## v4 matrix applicability

`BUG-SCHEDULER-05` is a pre-persistence capability/authority defect rather than a terminal Download-state defect, so the full Finding-A terminal fault matrix is not the primary proof surface. Its stateful re-entry consequence is deterministic: API 24–30 queueing outside the scheduler window reaches the false capability gate before scheduler persistence and disables the scheduler preference. No mutable retry field or later worker recovery can repair that rejected queue action automatically.

The revalidated A1/A3/A5 paths remain subject to their existing terminal/concurrency closure requirements and are not declared closed by this review.

## Verification evidence

- GitHub production-source review at pinned SHA: `SOURCE-LEVEL ONLY`
- Gradle focused tests: `NOT EXECUTED` in this review
- Instrumentation: `NOT EXECUTED` in this review
- Production wiring was traced statically for the new scheduler defect and the three revalidated historical paths.

## Verdict

`NOT_CLEAN`

Reason: the checkpoint already contains open correctness defects, this run confirmed one additional P2 defect, and required execution evidence for these paths was not produced in this review.
