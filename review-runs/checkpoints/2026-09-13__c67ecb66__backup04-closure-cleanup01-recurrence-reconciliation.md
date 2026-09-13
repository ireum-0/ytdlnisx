# F4 closure and F10 recurrence reconciliation at c67ecb66

Date: 2026-09-13

## Exact reviewed state

- Implementation start for this wave: `f20833d6d74134ec52a33c6cdb4a862784d9c4bf`
- F4 commit: `90afaec157607669ea32fa41877e7f0efcdcca86`
- F10 commit / exact final remote HEAD: `c67ecb66e3df954a225354632945be101a9d740d`
- Exact chain verified: `f20833d6 -> 90afaec1 -> c67ecb66`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger ref-only: `899328bc91e4008e39a658387396a0106c8666ec`

## F4 / BUG-BACKUP-04

Verdict: **CLEAN / CLOSED** at exact final `c67ecb66...`; semantic fix commit is `90afaec1...`.

The prior same-second staging-identity residual is closed. `backupInternal()` retains the readable version/timestamp prefix but appends `UUID.randomUUID()` to every staging filename. Independent overlapping invocations therefore no longer select one mutable staging pathname. Exact-source publication remains `sourceFiles = listOf(saveFile)` and invocation-local `MoveFileResult` remains the publication authority.

The final cumulative F4 production-wiring class was reported as actually executed at exact final HEAD with `BackupSettingsProductionWiringTest: 8/8 PASS`, including the deterministic overlap regression. The previously required capture/write/move/concurrency matrix remained in that final class; related final reported evidence also included `BackupMoveResultProductionWiringTest: 1/1`, focused JVM 4/4, full JVM 623/623, Kotlin/KSP/APK verification and `git diff --check` PASS. These are implementation-agent external execution evidence, not independent reviewer execution.

Canonical P1 count changes **1 -> 0**.

F4's hard-prerequisite side for F8/F9 is now satisfied. F5/F6/F7 remain CLOSED.

## F10 / BUG-CLEANUP-01

Verdict: **OPEN / NOT_CLEAN**. This is the existing P2 root; count delta `0`.

Substantial source progress is accepted:

- one stable unique WorkManager name;
- durable cadence/generation/monthly-anchor authority;
- calendar daily/weekly/monthly progression and short-month anchor restoration;
- generation/cadence validation before successor publication;
- stale-generation fencing after reconfigure/disable;
- startup reconciliation;
- retry-before-success does not intentionally publish a duplicate successor;
- final reported production wiring `CleanupScheduleCoordinatorProductionWiringTest: 7/7 PASS` plus calendar JVM 6/6 PASS.

Two material recurrence residuals remain under the same durable-schedule invariant.

### Residual A — retry exhaustion terminates recurrence

`CleanUpLeftoverDownloads.doWork()` returns `Result.retry()` only while `runAttemptCount < MAX_ATTEMPTS - 1`. On the final cleanup failure it returns `Result.failure()` before `scheduleSuccessor()`.

Concrete chain:

`cadence enabled + generation G`
-> cleanup occurrence throws on every allowed attempt
-> final attempt returns `Result.failure()`
-> no successor publication occurs
-> current unique chain terminates
-> persisted cadence remains enabled
-> no in-process recurring reconciler runs
-> future cleanup recurrence is absent until a later application startup happens to call `reconcile()`.

This contradicts the F10 invariant that exactly one logical cleanup schedule preserves the selected calendar cadence. Current tests cover retry followed by eventual success, not retry exhaustion with recurrence preservation.

### Residual B — WorkManager enqueue request is treated as acceptance

`CleanupScheduleCoordinator.enqueueNextLocked()` calls `workManager.enqueueUniqueWork(...)` and discards the returned asynchronous Operation. It immediately returns the request. `scheduleSuccessor()` then returns `true`, and `CleanUpLeftoverDownloads.doWork()` returns `Result.success()` without observing whether WorkManager actually accepted/persisted the successor. `configure()` and `reconcile()` use the same enqueue boundary.

Concrete chain:

`cleanup succeeds under current generation G`
-> `scheduleSuccessor()` issues `enqueueUniqueWork(APPEND_OR_REPLACE, successor)`
-> returned asynchronous Operation is not awaited/observed
-> method reports success
-> current worker returns `Result.success()`
-> enqueue Operation later fails
-> no successor exists while durable cadence G remains enabled
-> if the process remains alive, startup reconciliation does not run
-> recurring cleanup silently stops.

This is the same F10 schedule-ownership root, not a new blocker. The governing v6 rule that an asynchronous request is not completion applies directly. Android WorkManager exposes completion through the returned Operation/future; source does not consume it here.

The production preference listener also ignores the Boolean returned by `CleanupScheduleCoordinator.configure()` and always returns `true`; any focused repair should make preference acceptance consistent with the coordinator's truthful configuration result or otherwise establish a durable pending/reconciliation responsibility before accepting the new setting.

Required review-fix boundary:

1. recurrence survives one occurrence exhausting its cleanup retry budget without pretending the cleanup itself succeeded;
2. successor publication has confirmed WorkManager enqueue acceptance or an explicit durable retry/reconciliation responsibility;
3. configuration/reconciliation enqueue failures cannot leave enabled cadence with no owner;
4. process death between durable cadence update and scheduler acceptance converges;
5. repeated recovery remains idempotent and generation-scoped;
6. stale generation cannot recreate old cadence;
7. preserve current calendar/monthly-anchor semantics and retry/no-duplicate behavior;
8. add deterministic production-boundary tests for exhausted cleanup retries and asynchronous enqueue failure/acceptance.

## Canonical count and CLEAN basis

Resulting blocker count: **P0 2 / P1 0 / P2 26**.

Overall project remains `NOT_CLEAN` because F10 and other canonical roots remain open.

The cumulative backup implementation prefix `a12c58055fff51b104f8b56fd53b534b8d7e5df4..90afaec157607669ea32fa41877e7f0efcdcca86` now has F4/F5/F6/F7 independently closed with required external execution evidence and no relevant regression found. Therefore the contiguous independently CLEAN review basis advances to the longest clean prefix:

`90afaec157607669ea32fa41877e7f0efcdcca86`

It does **not** advance to `c67ecb66...` because the F10 commit at that descendant remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED