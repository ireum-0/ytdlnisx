# F10 independent runtime-evidence review checkpoint

- checkpoint_type: F10-specific runtime evidence
- exact_implementation_sha: `85d5efabafb0dbb914506a25cfaa3282c901b301`
- implementation_base: `05e33771555aed85d3d271aa6a7f5545699e602b`
- implementation_chain: 8 commits ahead / 0 behind / straight ancestry
- changed production files in implementation wave: `0`
- changed test files in implementation wave: `1`
- frozen_master_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- v6_checklist_commit: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- v6_checklist_blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`.

Canonical root/count delta: `0`.

The full-class instrumentation run is valuable execution evidence but does **not** currently prove a production frozen-root recovery defect. The first reported failure occurs in a test whose orchestration does not establish the intended attempt boundary.

CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

F11 remains blocked.

## Exact implementation verification

Remote implementation HEAD was independently verified at:

`85d5efabafb0dbb914506a25cfaa3282c901b301`

with parent `bc112cd7aec85d0cb0c2c42a342edeb7acbff2aa`.

Compare from `05e33771555aed85d3d271aa6a7f5545699e602b` reports:

- ahead: 8
- behind: 0
- total commits: 8
- only changed path: `app/src/androidTest/java/com/ireum/ytdl/work/CleanupScheduleCoordinatorProductionWiringTest.kt`

No production file or dependency changed.

All eight reported commits are test-only F10 commits on the straight chain. Exact final-SHA combined status contexts: 0. Associated GitHub Actions workflow runs: 0.

## Runtime evidence received

Existing emulator:

- AVD: `Medium_Phone_API_36.1`
- serial: `emulator-5554`
- API: 36
- ABI: x86_64
- adb state: `device`
- boot completed: `1`

Build/diff preconditions reportedly passed.

Previously hanging test:

`bareInProgressPhaseWithoutJournalFailsClosedAndDoesNotPublishSuccessor`

was executed alone after harness changes and reportedly passed: 1 executed / 0 failures.

Full `CleanupScheduleCoordinatorProductionWiringTest` class:

- discovered: 69
- recorded before stop: 31
- first failure: `cleanupJournalReusesFrozenRootAfterCachePathRebindAndRestart`
- assertion: expected `SUCCEEDED`, observed `FAILED`
- worker was actually enqueued/executed and retried
- full gate therefore remains unsatisfied.

## Independent classification of the reported failure

The failing test is intended to prove:

1. R1 owns an exact cache marker/manifest with two files;
2. the first cleanup attempt deletes the first file and receives a retryable failure deleting the second;
3. before retry, the settings path transitions from R1 to R2 after old-root capture;
4. process-local state is reset/restarted;
5. retry resumes the frozen R1 journal, deletes only R1 responsibility, leaves R2 untouched, and completes.

The current test does **not** create that sequence safely.

`DownloadCacheOwnership.deleteIfOwnedResult(...)` executes under `synchronized(ownershipLock)` and invokes `fileDeletionForTesting` while still holding that lock.

The test's failure seam, for the second file, does:

- `secondDeletionEntered.countDown()`;
- waits on `releaseSecondDeletion.await(10, TimeUnit.SECONDS)`;
- only after release would it return `false`.

But the test thread, after observing `secondDeletionEntered`, calls:

`DownloadCacheOwnership.captureOwnedRootsForPathTransition(context, rootOne)`

**before** `releaseSecondDeletion.countDown()`.

`captureOwnedRootsForPathTransition(...)` also executes under the same `ownershipLock`.

Therefore the intended sequence is impossible:

- worker holds `ownershipLock` and waits for the test thread;
- test thread waits for `ownershipLock` before it can reach the latch release;
- after 10 seconds the worker-side `check(releaseSecondDeletion.await(...))` must time out/throw to release the lock.

The first attempt is therefore not proven to be the intended `ExactCleanupResult.RetryableFailure` produced by a clean `false` deletion result. It is contaminated by a test-only lock/latch timeout exception.

This ordering already existed at the prior `05e33771...` test source; the eight latest harness commits did not introduce a production regression.

Because the test does not faithfully model its claimed attempt boundary, terminal `FAILED` from this run cannot independently reopen F10 production source.

## Required correction

Keep the next wave Android-test-only.

Correct `cleanupJournalReusesFrozenRootAfterCachePathRebindAndRestart` so the first attempt reaches a real retry boundary without holding `ownershipLock` across coordination with the test thread.

Preferred semantic sequence:

1. make the second-file failure seam return `false` immediately on its first hit; do not wait while `ownershipLock` is held;
2. wait for the exact WorkRequest to become `ENQUEUED` with `runAttemptCount >= 1` (or another exact WorkManager signal proving the first attempt returned `Result.retry()`);
3. verify Room deletion committed, first exact file is gone, second exact file + marker/manifest remain;
4. only now, with the first worker attempt no longer executing the deletion critical section, call `captureOwnedRootsForPathTransition(R1)`;
5. change destination-local `cache_path` from R1 to R2;
6. simulate process restart/reset RootBindingStore process state;
7. allow retry/recovery to proceed with the failure seam disabled/consumed;
8. prove terminal success, second R1 file/marker/manifest convergence, R2 isolation, and correct successor state.

If needed to model process death before the in-process replay actor wins, use the existing replay-delay test seam narrowly rather than blocking inside the ownership lock. Do not change production timing or production code for this test orchestration.

After the corrected single test passes, rerun the full 69-test production-wiring class. Preserve the first genuinely semantic production failure if one appears.

## Preserved source disposition

Current production F10 semantics remain source-reviewed fixed, including:

- exact generation/cadence/occurrence ownership;
- durable effect journal and incomplete-suffix recovery;
- exact frozen `CacheBinding(downloadId, rootPath)` responsibility;
- RootBindingStore as discovery only;
- marker/manifest mutation authority;
- partial exact deletion retaining retry proof;
- R1 path-transition capture before R2 mutation;
- destination-local generic restore rules;
- D1-before-D2 and successor fencing.

No new P0/P1/P2 root is added from this test failure.

## Execution gate

Actual Android instrumentation did execute and found a failure, so implementation-side runtime evidence now exists. However, because the first failure is independently classified as an invalid test orchestration boundary, the required F10 production-wiring execution gate is still `NOT_VERIFIED` rather than a confirmed production FAIL or PASS.

A full corrected class run with nonzero tests and zero failures remains required before F10 canonical closure.

INDEPENDENT EXECUTION: NOT EXECUTED