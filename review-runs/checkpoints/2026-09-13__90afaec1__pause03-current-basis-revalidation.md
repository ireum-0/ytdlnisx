# BUG-PAUSE-03 — exact CLEAN-basis independent revalidation

Date: 2026-09-13

## Exact reviewed state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior current-basis checkpoint: `8d5f1de178973bb6ca90c0c595c8a5b43a444121` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- The F10+F16+F17 implementation wave is active from completed SHA `973424909fd97de758b62f639967c8bae7c0bad7`; no in-progress implementation diff was inspected or used as evidence.

`BUG-PAUSE-03` is a later exploratory/current finding rather than an item in the frozen Master Plan registry. Its established root contract comes from the prior independent checkpoint and the governing v6 production-wiring/destructive-authority rules.

## Verdict

**NOT_CLEAN — existing P2 `BUG-PAUSE-03` remains OPEN at exact canonical CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range reconciliation

Exact compare `3616ae02... -> 90afaec1...` is sixteen commits ahead and includes duplicate-admission work plus backup remediation. `DownloadViewModel.kt` changed in that range, so the current Pause-All producer was re-read at exact `90afaec1...` rather than relying on diff absence.

The later work strengthens duplicate admission and other per-Download ownership boundaries. It does not introduce a Pause-All operation generation, admission epoch, durable operation-wide pause barrier, or an exact final WorkManager target set.

## Exact current production evidence

### 1. Pause-All still defines a point-in-time semantic target set

At exact `90afaec1...`, `DownloadViewModel.pauseAllDownloads()` snapshots only the current `Active` / `PostProcessing` Downloads under `withDownloadWorkerExecutionLock`.

For each row in that snapshot it then uses the exact Download/execution identity, persists `USER_PAUSE` recovery responsibility, converges the user-stop semantic state, and attempts exact native/worker quiescence.

This is strong authority for Downloads that were actually selected into the Pause-All snapshot.

### 2. The final WorkManager transport stop remains broader than that semantic target set

After the exact per-item semantic/quiescence work, Pause-All still calls:

`WorkManager.getInstance(application).cancelAllWorkByTag("download")`

That tag-wide transport operation addresses all current WorkManager requests carrying the shared `download` tag. It is not restricted to the exact Download/execution identities that received this Pause-All operation's durable `USER_PAUSE` decision.

### 3. Production admission can still create a new sibling execution after the snapshot

`DownloadSchedulerAdmission.admitQueuedDownloadsThroughProductionPath(...)` selects runnable candidates under the short global execution lock and then releases that selection lock before invoking the per-candidate claim callback.

`claimDownloadThroughProductionAdmission(...)` later acquires the candidate's per-Download side-effect lease and the short global execution lock, revalidates current scheduler capacity, process/native ownership, producer recovery, primary-success authority, low-quality cancellation, committed-History authority, and the exact queued snapshot CAS. It then allocates a fresh execution ID and publishes the process-local owner.

These are strong per-Download admission fences, but neither selection nor final claim checks a Pause-All generation/barrier or membership in a currently active Pause-All operation.

Therefore a Queued/Scheduled sibling B that was outside Pause-All's initial Active/PostProcessing snapshot can validly become a new Active execution after the snapshot and before the later tag-wide cancellation.

## Concrete reachable sequence

1. Pause-All snapshots Active/PostProcessing Download A under the short global execution lock.
2. It releases that lock and begins A's exact durable `USER_PAUSE` / semantic-stop / quiescence work.
3. Queued/Scheduled Download B was not part of that semantic target snapshot.
4. B reaches normal production admission after the snapshot.
5. B passes the exact current per-Download admission checks and receives a fresh execution identity.
6. B never received this Pause-All operation's durable `USER_PAUSE` authority.
7. Pause-All later calls `cancelAllWorkByTag("download")`.
8. The shared-tag transport cancellation can stop B's WorkManager execution despite B never being an authorized semantic target of the Pause-All operation.

The transport mutation therefore still has broader authority than the durable semantic target decision that is supposed to justify the stop.

## Recovery relation

Exact `USER_PAUSE` / `USER_CANCEL` recovery remains valuable for selected executions, and generic unexpected-stop recovery may eventually converge B after a broad cancellation. That does not retroactively make B an authorized Pause-All target. Recovery/liveness is not semantic authorization for the original transport stop.

## Root/count reconciliation

- This is the same existing P2 `BUG-PAUSE-03` target-set / broad-transport-cancellation root recorded at `8d5f1de...`.
- Later duplicate-admission and backup work does not close, split, or create an alias of this root.
- Canonical count delta remains `0`.

## Stable correction boundary

A future correction must preserve the exact Pause-All semantic target authority through the final transport-stop boundary.

Acceptable designs include an operation-wide durable admission barrier/generation, exact per-execution WorkManager cancellation, or an equivalent protocol, but the invariant is:

- a newly admitted sibling is either durably enrolled in the same Pause-All operation before that operation may stop it; or
- the sibling remains untouched by that Pause-All operation.

Preserve exact `USER_PAUSE` recovery, `USER_CANCEL` distinction, scheduler capacity/priority behavior, low-quality replacement fences, native-process ownership, producer/primary-success authority, cache-maintenance admission authority, and all independently closed duplicate-admission behavior.

Focused production-wiring coverage should force A into the Pause-All snapshot, keep B queued outside it, allow B to exact-claim after the snapshot but before the final transport stop, and prove B is not stopped unless it first acquires the same durable Pause-All semantic authority.

INDEPENDENT EXECUTION: NOT EXECUTED