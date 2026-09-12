# BUG-PAUSE-03 — exact CLEAN-basis independent revalidation

Date: 2026-09-12

## Exact reviewed state

- Independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Prior current-basis checkpoint: `cc8a91be114d4c87a0d151a6599d3bb28b08a8f2` at `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Exact remote duplicate-admission verification for `8c5db3c7...` remains a separate verification task; no post-`3616ae02...` implementation state is used as evidence here.

## Verdict

**NOT_CLEAN — existing P2 `BUG-PAUSE-03` remains OPEN at exact canonical CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

The range from the prior `9edd3e23...` basis to `3616ae02...` includes later keyword/cache remediation and modifies Download scheduler/worker authority code. Exact `3616ae02...` producer and admission source was therefore re-read rather than relying on diff absence.

The newer cache admission work strengthens per-Download execution/cache ownership but does not add a Pause-All operation generation, admission epoch, durable operation-wide pause barrier, or an exact final transport-cancellation target set.

## Exact current production evidence

### 1. Pause-All still establishes an exact point-in-time semantic target set

`DownloadViewModel.pauseAllDownloads()` still obtains the current Active/PostProcessing rows under `withDownloadWorkerExecutionLock`, releases that short global lock, and then performs per-item durable USER_PAUSE/semantic-stop/quiescence work only for rows in that snapshot.

The per-item path uses the exact Download/execution identity and current-row revalidation. This remains correct for the Downloads actually selected by Pause-All.

### 2. Final WorkManager stop still broadens authority beyond that target set

After the exact-item work, `pauseAllDownloads()` still executes:

`WorkManager.getInstance(application).cancelAllWorkByTag("download")`

The final transport operation therefore addresses every WorkManager request carrying the shared `download` tag, not just the exact Download/execution identities that received the durable USER_PAUSE semantic decision.

### 3. Exact current admission still allows a new sibling execution after the snapshot

At `3616ae02...`, `DownloadSchedulerAdmission.admitQueuedDownloadsThroughProductionPath(...)` performs candidate selection under the short global execution lock. `claimDownloadThroughProductionAdmission(...)` later acquires the candidate's per-Download lease and the short global lock, revalidates current ownership/capacity/native/recovery/low-quality/primary-success authority, then calls `claimDownloadForWorkerAndRead(...)` with a fresh execution token.

These are strong per-Download authority checks, but there is still no Pause-All operation generation/barrier checked before the claim. Therefore a Queued/Scheduled sibling B that was outside Pause-All's Active/PostProcessing snapshot can legitimately become a new Active execution before Pause-All reaches its broad tag cancellation.

The cache-maintenance admission window is likewise not a Pause-All semantic barrier; it protects cache/execution admission, not membership in a particular Pause-All operation.

## Concrete reachable sequence

1. Pause-All snapshots Active/PostProcessing Download A under the short global lock.
2. It releases that lock and begins A's exact durable USER_PAUSE/semantic-stop/quiescence sequence.
3. Queued/Scheduled Download B was not in that target snapshot.
4. B reaches normal production admission after the snapshot and passes the exact current per-Download claim checks.
5. B becomes `Active` with a new execution identity without ever receiving the Pause-All USER_PAUSE semantic decision.
6. Pause-All later calls `cancelAllWorkByTag("download")`.
7. The tag-wide transport cancellation can stop the WorkManager execution carrying B even though B was never an authorized semantic target of this Pause-All operation.

Thus the final transport mutation still has broader authority than the durable semantic target decision that is supposed to justify it.

## Recovery relation

The current recovery protocol correctly distinguishes USER_PAUSE/USER_CANCEL from generic stopped-worker recovery for exact selected executions. It does not retroactively make B an intended Pause-All target: B was admitted after the snapshot and lacks the corresponding durable USER_PAUSE carrier before the broad cancellation.

Generic stopped-worker recovery can converge an unexpectedly stopped B, but recovery/liveness does not prove semantic authorization for the original stop.

## Root/count reconciliation

- This is the same existing P2 `BUG-PAUSE-03` target-set/broad-transport-cancellation root.
- The strengthened cache/claim authority at `3616ae02...` does not close or split the root.
- No new blocker ID and no count change.

## Stable correction boundary

A future correction still needs to preserve exact semantic target authority through the final transport stop boundary. An implementation may use an operation-wide admission barrier/generation, exact per-execution WorkManager cancellation, or an equivalent protocol, but it must ensure that a newly admitted sibling is either durably enrolled in the same Pause-All operation before being stoppable by it or remains untouched by that operation.

Closure must also preserve exact USER_PAUSE recovery, USER_CANCEL distinction, scheduler capacity/priority behavior, low-quality replacement fences, native-process ownership, and the cache-maintenance admission authority added by later remediation.

Focused production-wiring coverage should force A in the Pause-All snapshot, B queued outside it, B exact-claims after the snapshot but before final transport stop, and prove B is not stopped unless it first acquires the same durable USER_PAUSE authority.

INDEPENDENT EXECUTION: NOT EXECUTED