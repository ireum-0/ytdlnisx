# F11 second-remediation re-review — producer/quiescence and scheduler-side-effect addendum

Date: 2026-09-21

Defect-ID: `BUG-BACKUP-03`
Reviewed-Implementation-SHA: `7efd3fe2579e421c545d1ed6287713dec02e9225`
Review-Base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
Reviewed-Ledger-SHA: `899328bc91e4008e39a658387396a0106c8666ec`

Verdict: NOT_CLEAN

Current blockers:
- [P0] `BUG-BACKUP-03`
- F11-R1 = STILL_OPEN
- F11-R2 = STILL_OPEN
- F11-R3 = CLOSED
- F11-R4 = CLOSED

Canonical blocker-count delta: `0`.

This addendum records two exact final-source consumer/authority gaps not covered by the preceding `fe41e2a2...`, `57e6e7c0...`, and `bc304d10...` records. They remain residuals of the same canonical F11 root.

## F11-R1 addendum — new LocalAdd producer can appear after the one-shot quiescence capture

Final `HistoryFragment` creates a LocalAdd session on an IO coroutine as:

`expandVideoUris`
→ allocate session/request
→ `LocalAddStorage.beginSession(...)`
→ `WorkManager.enqueueUniqueWork(...)`
→ await enqueue acceptance
→ `LocalAddStorage.markOwnerAccepted(...)`.

`beginSession()` and `markOwnerAccepted()` durably mutate the default SharedPreferences namespace directly. This production publication path does not participate in `RestoreMutationAdmission`.

F11 History quiescence performs:

`LocalAddStorage.loadLiveWorkOwners()`
→ persist the resulting session IDs into the Restore journal
→ `cancelAllWorkByTag(local_add_worker)`
→ poll tag quiescence.

Therefore the owner inventory is a one-shot snapshot rather than an admission boundary.

Reachable interleaving:

1. Restore publishes active ownership.
2. History quiescence captures the current LocalAdd owner list; new session S is not yet present.
3. A LocalAdd producer that was already executing publishes S with `beginSession()` after that capture.
4. S is then enqueued before or around F11's tag cancellation.
5. If cancellation catches S, its WorkManager owner is removed but S is absent from `journal.localAddSessionIds`, so post-commit LocalAdd reconciliation does not rebuild it.
6. If S is enqueued after the one-shot cancellation, its worker observes RestoreGate and retries; F11 has no second exact producer-admission barrier guaranteeing that a new owner cannot appear during the quiescence interval.

This is the same R1 invariant: F11 must not destroy or strand operational responsibility that can be published concurrently with its quiescence boundary.

Required correction:
- make exact LocalAdd live-responsibility publication participate in the same Restore ownership ordering before F11's destructive History quiescence;
- do not hold shared admission over URI expansion, UI work, native work, or long-running worker execution;
- ensure either the ordinary producer wins and its exact owner is visible to/quiesced by F11, or Restore wins and the producer cannot durably publish/enqueue until Restore completes;
- preserve user cancellation/retirement and F20 identity/session behavior.

Required deterministic proof:
- ordinary new LocalAdd publication wins before Restore publication: exact session/owner is captured and converges to one current owner;
- Restore wins before LocalAdd final publication: no LocalAdd owner/work is published into the active Restore graph;
- no session can be published between the final quiescence owner capture and the cancellation/quiescence boundary;
- no quiescence timeout is masked by a late LocalAdd retry owner.

## F11-R2 addendum — scheduler cancellation/external side effects remain outside final admission

`WorkManagerHandoffRecovery.cancelScheduledHandoffs()` now serializes its carrier-row deletions via ordinary mutation admission, which is an improvement.

However its external cancellation effects are outside that authority:

- it performs the START/END carrier deletion calls;
- then separately calls `cancelUniqueWork(scheduled_download_start)` and `cancelUniqueWork(scheduled_download_end)` outside the shared admission boundary;
- the WorkManager cancellation `Operation` is not awaited there.

`AlarmScheduler.cancel()` calls `cancelScheduledHandoffs()` and then independently calls `AlarmManager.cancel(...)` for the normal and one-shot PendingIntents. Those AlarmManager effects are also outside the shared admission boundary.

`DownloadSettingsFragment` has reachable ordinary scheduler mutations:
- `use_scheduler` performs only a point-in-time RestoreGate check before `scheduler.schedule()` / `scheduler.cancel()` and, on disable, enqueues an ordinary DownloadWorker afterward;
- schedule-start/end custom writers commit the preference through admission but call `scheduler.schedule()` only after that admission has been released.

Concrete authority race:

ordinary scheduler mutation begins while Restore is still ALLOWED
→ its early point-in-time check/admitted preference write succeeds
→ Restore publishes active ownership and begins/finishes restore-owned scheduler reconciliation
→ the old ordinary call continues into `AlarmScheduler.cancel()` or scheduling side effects
→ it can cancel a Restore-owned PendingIntent / accepted WorkManager fallback or publish stale ordinary scheduler/download work after Restore won the graph.

The six-method handoff admission suite covers ordinary carrier preparation (`HARD_SUB_SCAN`, scheduler carrier insert, Observe-retry carrier insert) versus Restore publication. It does not cover the final external cancellation/scheduling effects above.

Required correction:
- serialize/defer the complete ordinary scheduler authority effect, not only the carrier-row mutation;
- after Restore wins, an old ordinary call must not cancel Restore-owned alarms/WorkManager successors and must not publish stale ordinary Download work;
- await WorkManager cancellation/acceptance when that completion is part of the authority transfer;
- keep admission narrow: do not hold the shared mutex across wall-clock delays or arbitrary worker execution;
- preserve the independently CLOSED R3 accepted-fallback mechanism and CLOSED R4 restore work identity.

Required deterministic proof:
- ordinary cancel wins fully before Restore publication, including carrier + WorkManager/alarm cancellation effect;
- Restore wins before ordinary cancellation effect, and the ordinary call cannot cancel the restore-owned accepted successor or alarm;
- schedule-start/end change cannot publish scheduler side effects after Restore wins merely because the preference mutation was previously admitted;
- disabling scheduler cannot enqueue an ordinary Download owner after Restore wins.

## Terminal fault matrix
- R1 producer/quiescence crossing: GAP, as above.
- R2 scheduler external cancellation/publication crossing: GAP, as above.
- R3 exact-alarm-failure fallback itself: CLOSED by retained accepted carrier/WorkManager ownership.
- R4 restore work identity/replay: CLOSED.

## Cross-attempt / live-owner matrix
- LocalAdd new producer vs Restore: OPEN until one shared final-publication ordering is proven.
- scheduler ordinary cancel/schedule vs Restore: OPEN until external side effects share the authority ordering.
- restore alarm fallback replay: CLOSED.
- restore Download work replay: CLOSED.

## Triggered conditional modules
- External scheduler handoff/observation: GAP for ordinary cancel/schedule side effects.
- Maintenance/live-owner namespace: GAP for LocalAdd producer crossing.
- External representation/authority projection: existing LocalAdd runtime-preference gap remains governed by `57e6e7c0...`.

## Semantic-contract / consumer / authority-effect closure
- Trigger: YES.
- Newly reviewed producers/consumers: HistoryFragment LocalAdd producer, Restore quiescence LocalAdd capture/cancel, WorkManagerHandoffRecovery scheduler cancellation, AlarmScheduler cancellation/publication, DownloadSettings scheduler listeners.
- Final authority effects remain incomplete exactly at the two boundaries above.

## Recovery discovery closure
- LocalAdd startup discovery can recover explicit owner markers after process restart, but it does not make the active-transaction producer/quiescence race safe in the current process.
- scheduler startup reconciliation can recover retained carriers, but it cannot undo an ordinary cancellation that was allowed to destroy the accepted Restore-owned external owner after Restore won.

## Candidate rejections reviewed
- Do not reopen F11-R3 solely because R2 can externally cancel its owner: the R3 fallback mechanism itself remains source-closed; the remaining defect is R2 consumer/authority-effect closure.
- Do not revive every raw `local_add_entries_*` key: only exact live responsibility is authority.

## Verification gaps
- No independent Android runtime execution was performed.
- Existing implementation-agent tests do not exercise these two exact races.

## Verification evidence
- Exact final source reviewed at `7efd3fe2579e421c545d1ed6287713dec02e9225`.
- Governing predecessor review HEAD: `bc304d10ee90a166b92be801394bd6098d4d5d42`.
- Implementation-agent runtime evidence remains evidence only.

Ledger-May-Close: `NO`

INDEPENDENT EXECUTION: NOT EXECUTED
