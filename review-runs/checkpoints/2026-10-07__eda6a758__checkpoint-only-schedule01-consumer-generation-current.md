# Checkpoint-only production reconciliation — scheduler consumer generation

checkpoint_kind: CHECKPOINT_ONLY_PRODUCTION_FINDING_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 97257f5420dfdaae77dddc146bb4ea83dc9beccb
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-SCHEDULE-01 — VERIFIED_CLOSED

This historical checkpoint-only root is distinct from the later historical
BUG-SCHEDULER-01 recurrence root.

Historical BUG-SCHEDULE-01 owned scheduler consumer generation fencing:
a superseded already-running START or END request could cross its first semantic effect because consumer
code ignored the exact durable handoff/request generation.

Current exact eda6a758 closes that boundary.

START consumer:
- DownloadWorker reads handoffId, handoffRequestId, generationId and boundary from input;
- before doWorkSerialized()/queue admission it calls
  WorkManagerHandoffRecovery.schedulerWorkRequestDisposition(..., kind=SCHEDULE_START,
  workRequestId=id.toString());
- STALE returns success without queue claims;
- PENDING retries;
- only CURRENT reaches Download admission.

END consumer:
- CancelScheduledDownloadWorker requires nonblank handoffId/requestId;
- before cancelAllWorkByTag("download") it calls the same schedulerWorkRequestDisposition for
  SCHEDULE_END with exact generation/boundary/workRequest identity;
- STALE exits and PENDING retries before the broad cancellation boundary;
- only CURRENT crosses into WorkManager cancellation and per-Download stop/requeue effects.

Both consumers retire only their exact scheduler work request after successful semantic completion.

Disposition:
BUG-SCHEDULE-01 = VERIFIED_CLOSED.

Relationship:
- not an alias of BUG-SCHEDULER-01 recurrence: recurrence asks whether the next daily boundary is
  durably re-armed after consumption;
- not an alias of BUG-SCHEDULER-WINDOW-01: window membership/end-effect time semantics are separate;
- the old S8/S9 stale-consumer generation mechanism itself is now closed.

Project inventory effect:
- add one distinct checkpoint-only production root;
- current state: CLOSED;
- current download canonical inventory unchanged.

No production source, implementation prompt, active implementation scope, Master Plan or ledger changed.
