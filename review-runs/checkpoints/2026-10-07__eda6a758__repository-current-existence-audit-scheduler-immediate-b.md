# Repository current-existence audit — scheduler immediate dispositions B

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: f4ccdd5ae170593529452a35fc4e9cef2ff92019
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-SCHEDULER-02 — VERIFIED_CLOSED

Historical root:
a stale Scheduled-screen "Download now" selection could overwrite a row after a worker had already
claimed it Active/PostProcessing and erase its executionId.

Current exact source closes that specific path:
- ScheduledDownloadsFragment still calls
  DownloadViewModel.resetScheduleTimeForItemsAndStartDownload(selectedIds);
- the authoritative DAO mutation resetScheduleTimeForItems now includes
  status IN ('Queued','Scheduled');
- it cannot rewrite a current Active or PostProcessing row;
- therefore a worker claim that wins before the UI mutation revokes the stale UI authority: the
  reset-to-Queued write affects zero rows for that claimed item and cannot erase its live executionId.

Disposition:
BUG-SCHEDULER-02 = VERIFIED_CLOSED.

This closes the historical stale Scheduled-UI -> claimed worker overwrite root only. It does not close
the opposite ordering owned by BUG-SCHEDULER-06.

## BUG-SCHEDULER-06 — VERIFIED_OPEN P2

Historical root:
a worker observes a Scheduled row as due, the user successfully reschedules it to a future time, then the
stale worker claim succeeds because current due-time authority is not revalidated at the claim boundary.

Current exact source still reproduces the semantic gap:
- runnable observation queries require downloadStartTime <= observation currentTime;
- rescheduleQueuedOrScheduled can later keep the row Scheduled while replacing downloadStartTime with a
  new future value and clearing executionId;
- that reschedule preserves operationId/retryAttempt;
- claimDownloadForWorker accepts any current Queued/Scheduled row matching id + operationId +
  retryAttempt and the existing history/low-quality guards;
- the claim SQL contains no current downloadStartTime <= claim-time predicate, no expected prior
  downloadStartTime, and no schedule-generation token;
- claimDownloadForWorkerAndRead makes the CAS/materialization atomic, but it does not add the missing
  due-time authority check.

Therefore:
observe T_old due -> successful future reschedule T_new -> stale claim
remains a valid production interleaving.

Disposition:
BUG-SCHEDULER-06 = VERIFIED_OPEN P2.

Required direction:
make the latest persisted due-time/schedule generation part of the same transactional claim authority.
A successful future reschedule must revoke every stale earlier due observation.

## Immediate audit result

candidate_ids_audited_here: 2
verified_open_here: 1
verified_closed_here: 1

Lower-bound inventory progress:
- candidate IDs: 136
- audited: 65
- verified closed/currently not reproduced: 55
- verified open: 10
- not yet audited inside lower bound: 71

Verified-open non-canonical repository roots so far:
- BUG-PLAYER-01 — P2
- BUG-QUEUE-01 — P3
- BUG-QUEUE-05 — P2
- BUG-DATE-03 — P2
- BUG-QUEUE-02 — P2
- BUG-QUEUE-03 — P2
- BUG-SCHEDULER-01 recurrence remainder — P2
- BUG-SCHEDULER-03 — P2
- BUG-SCHEDULER-04 — P2
- BUG-SCHEDULER-06 — P2

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
