# AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 — exact current-basis revalidation

Date: 2026-09-25 +09:00

Exact independently CLEAN implementation basis reviewed:
3b475625db7bed29c199f6decd39717cb0efbde2

Governing Master Plan:
fada33a7eed86b1fa2c07065af66f14bf4d24714

Governing checklist:
REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7

Canonical existing root:
P2 AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01

Prior exact-basis revalidation:
review-runs/checkpoints/2026-09-20T0620Z__90afaec1__automatic-keyword-scheduler-handoff-exact-basis-revalidation.md

## Verdict

OPEN / CONFIRMED / NOT_CLEAN.

The root remains present at exact CLEAN basis
3b475625db7bed29c199f6decd39717cb0efbde2.

Canonical blocker-count delta:
0

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 21

CLEAN_REVIEW_BASIS remains:
3b475625db7bed29c199f6decd39717cb0efbde2

## Current exact-source evidence

### Scheduler now exposes Operation, but ordinary callers still do not establish acceptance

AutomaticKeywordRuleScheduler.enqueue(...) now returns Operation?.

That is an improvement from the older exact basis, but ordinary callers do not
turn the returned asynchronous Operation into an accepted durable handoff.

AutomaticKeywordRuleRepository.save(...):
- commits the rule revision / keywords / assignments;
- when sync is needed, writes manualSyncStatus=QUEUED for the exact revision;
- calls AutomaticKeywordRuleScheduler.enqueue(...);
- does not await Operation.result;
- does not create an exact durable scheduler-recovery owner.

AutomaticKeywordRuleRepository.setEnabled(...):
- increments the rule revision;
- when synchronization is needed, persists manualSyncStatus=QUEUED;
- calls the scheduler;
- does not await Operation.result;
- does not create durable enqueue debt.

AutomaticKeywordRuleRepository.syncNow(...):
- DAO requestApplyExistingSync atomically increments revision,
  sets pendingApplyToExisting=1 and manualSyncStatus=QUEUED;
- calls the scheduler;
- tests only whether enqueue(...) returned null;
- returns true without awaiting Operation.result;
- has no durable retry/recovery owner if the asynchronous Operation later
  fails.

Therefore a durable QUEUED/pending rule revision can still outlive failed
WorkManager publication.

### Restore-specific path is improved and is not the remaining ordinary root

RestoreTransactionCoordinator now calls
AutomaticKeywordRuleScheduler.enqueueForRestore(...) and explicitly waits on
operation.result before completing Restore reconciliation.

That closes the old unchecked-enqueue gap for this specific Restore-owned
publication boundary.

It does not provide ordinary save/enable/syncNow recovery after the Restore
authority is gone.

### Startup still does not recover missing automatic-keyword sync publication

App startup invokes AutomaticKeywordObservationCoverage.reconcile(), which owns
ObserveSource discovery coverage.

WorkManagerHandoffRecovery.reconcile() runs for existing generic handoff
carriers, but no AUTOMATIC_KEYWORD sync carrier kind is currently created.

No startup path was found that scans enabled rules in durable QUEUED or pending
manual-sync state and proves or recreates an exact accepted
AUTOMATIC_KEYWORD_RULE_SYNC_<id> WorkRequest.

A process death after durable QUEUED publication but before successful enqueue
therefore still has no exact recovery owner.

### WorkRequest identity is not bound to the originating rule revision

AutomaticKeywordRuleScheduler request input contains:
- ruleId;
- mode.

It does not carry the originating rule revision.

AutomaticKeywordRuleSyncWorker loads the current rule by numeric ruleId when it
starts. It then uses that freshly loaded revision for RUNNING/final CAS writes.

Thus the worker-side revision checks prevent a worker from overwriting a
revision that changes after the worker's own load, but they do not prove that
the WorkRequest itself belongs to the revision that created it.

A stale/late request from an older scheduler publication can therefore load a
newer revision and act as though it were scheduled for that newer revision
unless WorkManager cancellation happened to eliminate it first.

The scheduler mode is placed in input but current worker source does not use
INPUT_MODE to establish execution authority; semantic mode is effectively
re-derived from current rule state.

This is part of the same scheduler-handoff ownership root rather than a new
canonical finding.

### Cancellation is not a durable supersession boundary

AutomaticKeywordRuleScheduler.cancel(...) issues cancelUniqueWork(...) without
an exact durable tombstone/generation owner.

Repository disable/delete therefore rely on asynchronous WorkManager
cancellation plus rule-row state, but there is no scheduler carrier that makes
old request identity durably non-authoritative and independently recoverable.

The rule.enabled/current revision checks reduce effect authority, but they do
not repair lost publication for the still-active current revision.

## Concrete remaining impact

Ordinary save / enable / syncNow:
1. durable current rule revision is committed as QUEUED and/or
   pendingApplyToExisting;
2. scheduler enqueue invocation returns an asynchronous Operation;
3. the repository returns without observing Operation.result;
4. Operation can later fail or the process can die;
5. no accepted WorkRequest is proven;
6. no exact durable retry owner remains;
7. startup has no general owner that converges the missing work;
8. the rule can remain durably pending without progress.

Separately, an old request lacks originating revision identity and can race a
newer rule revision until WorkManager cancellation/replacement wins.

## Stable correction boundary

A correction should keep this as one P2 root and should:

1. bind scheduler responsibility to exact ruleId + rule revision + sync mode;
2. create durable handoff/recovery debt before ordinary publication authority
   can be lost;
3. require Operation.result success before publication is considered accepted;
4. retry/recover exact durable pending responsibility after enqueue failure or
   process restart;
5. retain enough durable accepted-owner identity to avoid duplicate semantic
   successors when WorkInfo is transiently unavailable;
6. fence worker execution to the exact originating revision and current
   handoff/request owner;
7. make disable/delete/new revision durably supersede older exact owners before
   asynchronous cancellation;
8. preserve newer revision/work when retiring a stale older request;
9. use the same acceptance model for Restore, while preserving Restore
   reconciliation authority;
10. reconcile save, setEnabled, syncNow, retry/recovery, cancel/delete, Restore,
    and worker terminal ownership under one semantic contract;
11. add deterministic production-wiring coverage for asynchronous
    Operation failure, process restart, stale old-revision completion, and
    current-owner preservation.

The existing generic work_manager_handoff_carriers table appears capable of
representing this responsibility without a schema change by using:
- kind = a new automatic-keyword sync kind;
- sourceId = ruleId;
- sourceConfigurationGeneration = exact rule revision;
- decision = sync mode;
- boundary = ruleId string;
- generationId/handoffId/requestId = exact durable handoff identities.

That reuse is a preferred narrow design, not a mandate. If implementation
finds that an explicit schema field is required for correctness, migration and
schema evidence become mandatory.

## Verification

Exact-source revalidation completed at
3b475625db7bed29c199f6decd39717cb0efbde2.

Independent execution was not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
