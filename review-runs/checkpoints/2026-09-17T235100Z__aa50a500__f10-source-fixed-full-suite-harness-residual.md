# F10 frozen DOWNLOAD_TEMP root correction: source fixed, full-suite harness residual

## Reviewed implementation

- Repository: `ireum-0/ytdlnisx`
- Branch: `checkpoint/pre-baseline-review`
- Previous completed implementation HEAD: `85d5efabafb0dbb914506a25cfaa3282c901b301`
- Reviewed final remote HEAD: `aa50a500a47263f704914f0960543d6e92ff3aff`
- Remote ancestry: straight, 2 commits ahead / 0 behind from `85d5efab...`
- Commits:
  - `f869315196b222f6919832181e1f98c29a7ccdfc` — `test: move frozen-root recovery to retry boundary`
  - `aa50a500a47263f704914f0960543d6e92ff3aff` — `fix: preserve exact cache snapshot root across rebind`
- Both commits carry `Defect-ID: BUG-CLEANUP-01`.
- Changed files:
  - `app/src/main/java/com/ireum/ytdl/util/storage/AppCacheManager.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/CleanupScheduleCoordinatorProductionWiringTest.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/util/storage/CacheMaintenanceProductionWiringTest.kt`

## Independent source review

The confirmed residual at `85d5efab...` was that a journaled `DOWNLOAD_TEMP` `AppCacheExactSnapshot` froze R1 in `snapshot.rootPath`, but `deleteExact()` re-resolved the mutable current `cache_path` and therefore refused to consume R1 after R1 -> R2.

At `aa50a500...`:

1. `deleteExact(snapshot)` now resolves through `targetForExactSnapshot(snapshot)`.
2. For `DOWNLOAD_TEMP`, the persisted root is canonicalized again and accepted only when:
   - canonicalization succeeds;
   - the canonical path is absolute;
   - the re-resolved canonical path is exactly the persisted canonical `snapshot.rootPath`;
   - `AppOwnedPathPolicy.isWithin(root, applicationOwnedRoots())` proves the frozen root remains within the application-owned storage boundary.
3. The frozen R1 target reconstructs `TERMINAL` and `Logs` exclusions relative to R1 rather than consulting mutable R2.
4. Other categories retain the previous current-root equality fence; the generalized frozen-root behavior is limited to `DOWNLOAD_TEMP`.
5. Per-file deletion still preserves:
   - exact relative-path identity;
   - canonical containment under the frozen root;
   - category exclusions;
   - live-owner protection;
   - exact size + `lastModified` identity;
   - no rediscovery of newly-created files.
6. A changed/replaced exact path remains preserved and makes the deletion incomplete rather than deleting by pathname alone.
7. No `AppCacheExactSnapshot` schema/persistence format change was introduced.

The new direct production-wiring regression covers:
- R1 snapshot capture;
- R1 -> R2 rebind;
- exact R1 deletion;
- distinguishable R2 isolation;
- replaced R1 identity preservation;
- frozen R1 `TERMINAL` / `Logs` exclusion preservation.

The known production composition remains:
- `CleanUpLeftoverDownloads.prepareCleanupEffectJournal()` captures the exact `DOWNLOAD_TEMP` snapshot;
- `CleanUpLeftoverDownloads.executeCleanupEffect()` consumes it through `AppCacheManager.deleteExact(snapshot)`;
- per-Download frozen `CacheBinding(downloadId, rootPath)` cleanup remains a separate path and is not weakened by this change.

### Source verdict

The frozen-R1/current-R2 production residual is fixed at exact remote `aa50a500a47263f704914f0960543d6e92ff3aff`.

No new canonical source blocker is established by this implementation diff.

## Implementation-agent verification evidence

Reported by the implementation agent against the final committed implementation:

- `git diff --check`: PASS
- committed-range diff check: PASS
- `:app:compileDebugKotlin -x lint`: PASS after restoring the isolated worktree's missing local `local.properties`
- `:app:compileDebugAndroidTestKotlin`: PASS
- focused frozen-root production-wiring test: PASS, 1/1
  - WorkManager observed RETRY -> SUCCESS
- direct `AppCacheManager` frozen-root regression: PASS, 1/1
- full `CleanupScheduleCoordinatorProductionWiringTest`: 69 executed, 8 failures, 0 skipped

These are implementation-agent execution claims, not independent reviewer execution.

## First full-class failure classification

First reported full-class failure:

`cleanupJournalReusesFrozenRootAfterCachePathRebindAndRestart`

Reported runtime facts:
- the worker reached `SUCCEEDED`;
- R1 cleanup assertions passed;
- R2 isolation assertions passed;
- failure occurred only at the final expected-count assertion;
- suite logs showed a successor request being scheduled and later cancelled;
- the same focused test passes independently.

Exact-source review identifies two harness defects that make this failure non-production evidence.

### A. The final assertion does not actually identify the successor

The test:

1. configures a DAILY schedule and records the current `occurrenceAt`;
2. calls `workManager.cancelAllWork()`, cancelling the configured unique-work request;
3. manually enqueues the worker under test with `enqueueOccurrenceRequest(...)`, which is not the coordinator's unique-work request and carries no coordinator occurrence tags;
4. after that worker succeeds, asserts that `getWorkInfosForUniqueWork(WORK_NAME)` contains one item tagged with:

`occurrenceTag(generation, occurrenceAt)`

But production `CleanupScheduleCoordinator.scheduleSuccessor(...)` computes the **next** cadence occurrence from the completed `occurrenceAt` and enqueues/tags that successor with `successorDebt.occurrenceAt`, not the predecessor's `occurrenceAt`.

Therefore the final assertion is history-sensitive: it counts whether the earlier cancelled predecessor remains visible in WorkManager unique-work history. It does not prove successor convergence.

The same history-sensitive pattern already existed at `85d5efab...`; the new production fix merely allows execution to progress far enough to expose it.

The closely related `realCacheHelperPartialFailureRetainsExactCarrierAcrossRecovery` test contains the same predecessor-tag/history assertion and belongs to the same harness correction boundary.

### B. Suite lifecycle can carry an asynchronous cancellation into a later test

Both `@Before` and `@After` call:

`CleanupScheduleCoordinator.configure(context, null)`

Production `configure(null)` commits disabled authority, calls:

`workManager.cancelAllWorkByTag(TAG)`

and returns without awaiting that WorkManager `Operation`.

The test lifecycle then waits for main-looper idle, but main-looper idle is not proof that the WorkManager cancellation operation has completed.

Under isolated execution this cancellation normally finishes before the test creates its successor. Under full-suite load, an earlier lifecycle cancellation can remain in flight long enough to cancel a newly-created tagged successor. This is consistent with the reported suite log: successor scheduled, then cancelled.

The existing `cancelAllWorkAndAwaitIdle()` helper is called before lifecycle `configure(null)`, not after it, so it does not fence the new asynchronous cancellation issued by that final disable call.

### Harness verdict

The first full-class failure is classified as an **instrumentation harness residual**, not a new F10 production-semantic residual.

The remaining seven full-class failures are not independently classified from this completion report because the required first-failure stop preserved only the first detailed failure. They must not be assumed to be either production failures or harmless cascades.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- canonical finding count delta: `0`
- canonical totals remain: P0=2, P1=0, P2=24
- Overall remains `NOT_CLEAN`
- contiguous CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked until the F10 execution gate closes.

## Required next correction boundary

Run one **test-only F10 instrumentation-harness correction wave** from exact remote `aa50a500a47263f704914f0960543d6e92ff3aff`.

Do not change production code unless corrected harness execution establishes a concrete production-semantic failure.

The narrow correction should:

1. make test lifecycle disable/cancellation quiescent before a test body or next test can schedule tagged work;
2. replace predecessor-history assertions with exact successor assertions:
   - compute the expected successor occurrence from the predecessor occurrence/cadence/anchor;
   - await the exact successor tag/debt/request rather than relying on retention of a cancelled predecessor;
   - apply the same correction to materially identical helper-path assertions;
3. preserve first-failure diagnostics before teardown;
4. run the focused frozen-root test first;
5. if focused passes, run the full `CleanupScheduleCoordinatorProductionWiringTest` class once;
6. if the full class exposes another first failure, preserve its exact diagnostics and stop before unrelated changes;
7. commit/push the test-only correction normally with `Defect-ID: BUG-CLEANUP-01` and return the exact remote SHA/evidence.

The old isolated verification worktree's uncommitted retry-boundary correction has now been reproduced and published in `f8693151...`; its special protocol protection may be explicitly released. Do not delete or modify it as part of this review write.

INDEPENDENT EXECUTION: NOT EXECUTED
