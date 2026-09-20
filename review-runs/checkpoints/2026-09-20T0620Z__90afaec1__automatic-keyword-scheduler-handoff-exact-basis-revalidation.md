# AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Canonical existing root: `AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A durable nonterminal state that requires external WorkManager execution must not be published without either:
- an accepted scheduler handoff; or
- an exact durable recovery owner capable of rediscovering and resubmitting the responsibility.

WorkManager `enqueueUniqueWork()` returns an asynchronous `Operation`; invocation is not completion.

## Exact production evidence at 90afaec1

### 1. Scheduler discards the WorkManager completion carrier

`AutomaticKeywordRuleScheduler.enqueue()` builds an `AutomaticKeywordRuleSyncWorker` request and calls:

`WorkManager.getInstance(context).enqueueUniqueWork(workName(ruleId), REPLACE, request)`

The method returns `Unit` and does not return, await, or otherwise consume the WorkManager `Operation`.

`cancel()` similarly issues cancellation without exposing completion, but the blocker-relevant path here is durable nonterminal sync state followed by unchecked enqueue.

### 2. save() can durably publish QUEUED before unchecked enqueue

`AutomaticKeywordRuleRepository.save()` commits the rule/revision/keywords/assignment changes.

When initial/apply-existing synchronization is needed, it then writes:

`manualSyncStatus = QUEUED`

for the exact rule revision and invokes the scheduler.

If the enqueue Operation later fails, the durable rule already advertises queued sync responsibility but no accepted WorkRequest is proven.

### 3. setEnabled() has the same gap

When enabling a rule that requires synchronization, `setEnabled()` persists a new revision with `manualSyncStatus = QUEUED` and then calls the same unchecked scheduler.

Again the durable state can outlive a failed scheduling handoff.

### 4. syncNow() makes the gap especially explicit

`syncNow()` invokes DAO `requestApplyExistingSync()`, whose SQL atomically:

- increments `revision`;
- sets `pendingApplyToExisting = 1`;
- sets `manualSyncStatus = 'QUEUED'`.

After a successful affected-row result, `syncNow()` calls the unchecked scheduler and returns `true`.

Thus the public semantic result can report that the manual sync request was accepted at the application layer even though WorkManager acceptance remains unresolved and may later fail.

### 5. Worker execution is the transition out of QUEUED

`AutomaticKeywordRuleSyncWorker` starts by loading the rule and then calls `updateManualSyncStatusIfRevision(... RUNNING ...)`.

Therefore a missing WorkRequest leaves the durable rule in QUEUED/pending state; no independent execution path advances it.

### 6. Startup/recovery inventory does not own missing manual-sync work

At this exact basis, `App.onCreate()` runs `AutomaticKeywordObservationCoverage.reconcile()`, but that component owns ObserveSource discovery coverage, not the automatic-keyword rule manual-sync WorkRequest.

No startup production inventory was found that scans rules with QUEUED/pending manual sync state and re-establishes a missing `AUTOMATIC_KEYWORD_RULE_SYNC_<id>` WorkRequest.

Restore code may enqueue restored rules during that specific restore operation, but it uses the same unchecked scheduler and is not a general restart recovery owner for later scheduler loss.

## Concrete impact

save / enable / syncNow
→ durable exact rule revision becomes QUEUED and/or pendingApplyToExisting
→ WorkManager enqueue request is issued
→ Operation later fails
→ caller has already returned
→ no worker reaches RUNNING
→ no exact scheduler debt/recovery owner resubmits
→ rule can remain durably pending without progress.

## Root reconciliation

- Keep `AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01` as one P2 root.
- Canonical count delta: `0`.
- Keep separate from `OBSERVE-SCHEDULER-ASYNC-BARRIER-01`: this root's durable authority is the automatic-keyword rule revision/manual-sync state, not ObserveSource recurrence state.
- Keep separate from F11-R1/R3: those concern Reset-specific cancellation/reconciliation.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. expose and consume WorkManager enqueue acceptance for automatic-keyword sync;
2. preserve exact rule/revision/mode identity across the handoff;
3. if durable QUEUED/pending state is committed before enqueue, establish exact recovery debt before the caller can lose responsibility;
4. add startup/recovery discovery that can converge queued/pending rules lacking an accepted request, or use an exact durable handoff carrier that makes such discovery explicit;
5. preserve REPLACE/revision supersession so an old scheduler completion cannot authorize a newer rule revision;
6. reconcile save, setEnabled, syncNow, restore, retry and cancel consumers under one semantic-contract closure;
7. add deterministic production-wiring coverage for enqueue Operation failure after durable QUEUED state, restart with QUEUED/pending state and no work, and stale old-revision completion after a newer rule revision.

## Verification

- Exact-source production-path review: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
