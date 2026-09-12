# BUG-PAUSE-03 — current-basis independent revalidation

Date: 2026-09-12

## Exact reviewed state

- Independently CLEAN review basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Review mode: independent exploratory review while an unrelated BUG-KEYWORD-04 review-fix implementation wave is active
- Active implementation diff after `8e7f466c12bc4753c3e4bb048f7c02619f7d4c28` was not inspected or relied on
- Historical root: P2 `BUG-PAUSE-03`
- Historical recorded checkpoint: `5b9a3da4906eefa4fc67f82d8bbbad63019f1f5b`
- Governing plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN — existing P2 `BUG-PAUSE-03` remains OPEN at exact basis `9edd3e23...`.**

- Canonical count delta: `0`
- Resulting canonical count remains **P0 2 / P1 1 / P2 34**.
- CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- This revalidation does not create a new root; it confirms the already-counted Pause-All target-set/broad transport-cancellation root.

## Current production evidence

`DownloadViewModel.pauseAllDownloads()` obtains its target set by reading the current Active/PostProcessing rows under `withDownloadWorkerExecutionLock`, then releases that short global lock and performs per-item USER_PAUSE persistence/semantic-stop/quiescence work. Only the rows in that point-in-time list receive the durable USER_PAUSE recovery/semantic-stop protocol.

After those exact-item operations, Pause-All still calls:

`WorkManager.getInstance(application).cancelAllWorkByTag("download")`

The final transport cancellation therefore targets every WorkManager request carrying the shared download tag rather than the exact Download/execution identities that won the Pause-All semantic target decision.

The worker production admission path remains independently live during this interval. `DownloadSchedulerAdmission.kt` performs queue observation/selection under a short global execution lock and later, under the per-Download lease plus the same short global lock, revalidates capacity/current candidate state and calls `claimDownloadForWorkerAndRead(...)` with a new execution token. The exact current DAO claim CAS changes a matching `Queued`/`Scheduled` row to `status='Active'` and publishes `executionId=:executionId`.

No Pause-All operation generation, durable pause-all barrier, admission epoch, or equivalent predicate is checked by the exact production admission path before this Active claim.

Therefore this ordering remains reachable:

1. Pause-All snapshots Active/PostProcessing Download A under the short global lock.
2. Pause-All releases the lock and starts A's durable USER_PAUSE/semantic-stop/quiescence sequence.
3. Queued/Scheduled Download B, which was not in the Pause-All snapshot, reaches normal production admission.
4. B acquires the short admission lock after the snapshot and successfully CAS-claims into `Active` with a new execution identity because no Pause-All admission fence exists.
5. B did not receive the Pause-All USER_PAUSE durable carrier/semantic-stop decision because it was absent from the target snapshot.
6. Pause-All reaches its final `cancelAllWorkByTag("download")` call.
7. That broad tag cancellation is not scoped to A and may stop B's WorkManager execution as well.
8. B has therefore been transport-cancelled despite never having been semantically selected/durably paused by this Pause-All operation.

This violates the checklist identity/granularity and final-mutation authority rules: an earlier exact target snapshot does not authorize a later broader tag-wide mutation after new live ownership can appear.

## Recovery relation

Current recovery distinguishes USER_PAUSE/USER_CANCEL from generic stopped-worker recovery. That distinction does not close this root: B was admitted after the Pause-All snapshot and therefore does not automatically acquire the durable USER_PAUSE disposition before the broad transport cancellation. Generic stopped-worker/recovery ownership cannot retroactively prove that B was an intended Pause-All target.

## Stable remediation boundary

A future correction must preserve exact semantic target authority across the final transport stop boundary. At minimum:

1. define the Pause-All operation's exact target/admission semantics rather than letting a stale snapshot silently broaden through a shared tag;
2. ensure a Download admitted after the target decision is either deliberately enrolled in the same durable USER_PAUSE operation before it becomes cancellable by that operation, or remains untouched by that operation;
3. prevent the final WorkManager/native transport stop from cancelling executions that lack the corresponding durable USER_PAUSE authority;
4. revalidate exact Download/execution ownership at the final cancellation/stop boundary;
5. preserve the existing USER_PAUSE semantic-stop/recovery contract for actual targets and preserve USER_CANCEL as a distinct disposition;
6. avoid weakening scheduler capacity, queue priority, low-quality replacement, native-process, or existing exact-execution ownership fences.

An implementation may choose an operation-wide admission barrier/generation, exact per-execution transport cancellation, or another equivalent protocol, but correctness requires closing both the snapshot-to-admission race and the final broad-cancellation authority expansion.

## Tests required for future closure

Production-wiring coverage should deterministically establish at least:

- A is in the Pause-All snapshot; B is queued outside it; B is admitted to an exact Active execution after the snapshot but before final transport cancellation; B must not be stopped unless it first acquires the same durable USER_PAUSE authority;
- a real snapshot target A still reaches durable Paused semantics and exact transport/native quiescence;
- an execution-generation replacement between snapshot and final stop is not cancelled using stale authority;
- USER_PAUSE recovery after process death continues to converge only the exact intended targets;
- unrelated sibling queue admission/worker ownership remains functional after Pause-All completes.

## Relation to current implementation wave

This checkpoint is independent of the active BUG-KEYWORD-04 review-fix wave and uses only the fixed CLEAN basis `9edd3e23...`. It must not be interpreted as inspection or review of the in-progress implementation diff.

INDEPENDENT EXECUTION: NOT EXECUTED