# WORKER-FOREGROUND-COMPLETION-01 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Canonical existing root: `WORKER-FOREGROUND-COMPLETION-01`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `WORKER-FOREGROUND-COMPLETION-01` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new root.

## Governing contract

Checklist v6 requires asynchronous request/acceptance/completion to be treated as distinct correctness states. A future-returning foreground-establishment API is not complete merely because the request method returned.

Android's WorkManager long-running-worker guidance distinguishes:
- `ListenableWorker.setForegroundAsync()`, which returns a `ListenableFuture<Void>`; and
- suspending `CoroutineWorker.setForeground()`, which Kotlin CoroutineWorker callers can await.

Foreground establishment can itself fail under platform restrictions. A CoroutineWorker that requires foreground publication before destructive/durable work must therefore own that completion boundary rather than issue the future and continue.

## Exact production evidence at 90afaec1

### 1. CleanUpLeftoverDownloads

`CleanUpLeftoverDownloads` is a `CoroutineWorker`.

At the beginning of `doWork()` it calls `setForegroundAsync(...)` and discards the returned completion carrier.

It then immediately performs correctness-relevant destructive work:

- deletes Cancelled Downloads;
- deletes Error Downloads;
- may delete DOWNLOAD_TEMP cache state when no active Download is observed;
- returns `Result.success()`.

No foreground future completion/failure is consumed before those effects.

### 2. UpdateMultipleDownloadsFormatsWorker

`UpdateMultipleDownloadsFormatsWorker` is a `CoroutineWorker`.

It calls `setForegroundAsync(...)` without awaiting its returned future and then enters the bulk per-item format/data mutation path.

Thus foreground establishment failure can race or occur after durable per-item mutation has already begun.

This root is separate from `BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01`: that second root concerns ignored per-item mutation/error authority after the worker has started. This root concerns failure to own foreground-establishment completion before beginning the work.

### 3. MoveCacheFilesWorker

`MoveCacheFilesWorker` is a `CoroutineWorker`.

It calls `setForegroundAsync(...)` without consuming completion and immediately enters a cache-maintenance window that:

- collects cache artifacts;
- creates the external destination;
- moves exact artifacts.

The move/collision ownership logic may independently be sound, but it executes without first proving foreground establishment completed.

### 4. Safe controls prove the intended completion shape already exists in production

The same exact CLEAN basis contains corrected controls:

- `ObserveSourceWorker.updateRunStatus()` uses suspending `setForeground(...)`;
- `UpdateMultipleDownloadsDataWorker` uses `setForegroundSafely()` and returns `Result.retry()` when foreground establishment cannot be completed.

Therefore the vulnerable pattern is not required by project architecture or Kotlin CoroutineWorker limitations.

## Concrete impact

For each vulnerable worker:

foreground request issued
→ returned future remains unresolved
→ worker begins destructive/durable work
→ foreground publication later fails

can produce durable/filesystem effects without the worker having established the foreground execution contract it relies on.

The caller may also reach its normal terminal result without ever consuming the foreground-establishment failure.

This is a completion/authority gap, not merely notification timing.

## Root reconciliation

- Keep `WORKER-FOREGROUND-COMPLETION-01` as one P2 root.
- Canonical count delta: `0`.
- Keep separate from `BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01`.
- Keep separate from feature-specific destructive ownership roots inside cleanup or MoveCache.
- Safe foreground consumers are controls, not additional findings.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. make each vulnerable CoroutineWorker own foreground-establishment completion before correctness-relevant work begins;
2. prefer the suspending CoroutineWorker foreground API or an equivalent helper that awaits the underlying completion;
3. classify foreground-establishment failure into an explicit retry/failure result appropriate to the worker contract rather than silently continuing;
4. preserve cancellation semantics;
5. avoid duplicating durable/destructive work when foreground setup fails and WorkManager retries;
6. add production-wiring coverage where foreground completion fails before the worker's first durable/destructive effect, proving that effect did not occur;
7. preserve safe existing consumers and avoid a broad helper rewrite unless all consumers are inventoried under Checklist-v6 semantic-contract closure.

## Verification

- Exact-source production-path review: completed at `90afaec1...`.
- Exact upstream Android WorkManager foreground contract: reviewed from official Android Developers guidance.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
