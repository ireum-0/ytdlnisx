# Independent Track A checkpoint — scheduler follow-up and keyword handoff

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Canonical working recount

- P0: 2
- P1: 3
- P2: 20

This checkpoint retains `BUG-SCHEDULE-01` as one P2 root, adds same-root scheduler subcases, and adds one new P2 root: `BUG-KEYWORD-HANDOFF-01`.

## BUG-SCHEDULE-01 — additional same-root evidence

### S3 — global scheduler recurrence is one-shot

`AlarmScheduler.schedule()` publishes one exact start alarm and one exact end alarm with `setExactAndAllowWhileIdle()`.

The exact production receivers (`ScheduleAlarmReceiver`, `CancelScheduleAlarmReceiver`) transfer those one-shot boundaries into WorkManager handoff carriers, and `CancelScheduledDownloadWorker` performs end-of-window cancellation/requeue. None of those successor paths schedules the next day's start/end pair.

The production callers that invoke `schedule()` are settings/manual queue/Observe paths; therefore, absent later user/Observe activity, the first start/end pair is consumed and the persisted `use_scheduler` preference can remain enabled while no next daily pair exists.

Acceptance addition: the durable scheduler generation must own exactly one future start/end successor pair and re-establish the next pair after a boundary completes, with startup reconciliation.

### S4 — explicit future `downloadStartTime` groups lose later AlarmManager successors

`DownloadRepository.startDownloadWorker()` groups future scheduled downloads by `downloadStartTime`. When `use_alarm_for_scheduling=true`, it calls:

`AlarmScheduler(context).scheduleAt(futureScheduleGroups.keys.min())`

Only the earliest future group gets an AlarmManager boundary. The ordinary DownloadWorker completion path does not generally call `startDownloadWorker(emptyList(), context)` to schedule the next group; the only exact call found is in the hard-sub post-processing slot-release path.

Concrete sequence: future groups T1 < T2 exist; T1 alarm fires and starts a worker; T2 remains a scheduled durable row but no successor alarm is published unless another external path later re-enters scheduling.

Acceptance addition: every durable future group must have a recoverable scheduling owner; after T1 acceptance/completion, responsibility for T2 must already exist or be durably transferred.

### S5 — automatic scheduler disable does not revoke existing alarms/handoffs

On supported API 24-30 devices, `AlarmScheduler.canSchedule()` returns false even though the settings path treats exact alarms as not requiring the API 31+ runtime permission gate. `DownloadViewModel` may therefore set `use_scheduler=false` when queuing outside the window, but this branch does not call `AlarmScheduler.cancel()`.

If the user previously enabled scheduling, already-published start/end alarms and durable handoff carriers may remain after the preference is forced false. This strengthens the S2 API-level inconsistency: revoking the preference does not necessarily revoke previously issued scheduling authority.

This is retained under `BUG-SCHEDULE-01`; no additional count.

## New P2 — BUG-KEYWORD-HANDOFF-01

### Invariant

A durable automatic-keyword sync intent (`QUEUED`, incomplete baseline, or `pendingApplyToExisting`) must have an exact accepted WorkManager owner or durable recovery debt. Startup must repair missing ownership. A user-requested apply-to-existing sync must not remain durably QUEUED with no worker.

### Production evidence at fixed basis

`AutomaticKeywordRuleRepository` mutates durable rule state before scheduling:

- `save()` can persist/advance the rule, then set `manualSyncStatus=QUEUED`, then call `AutomaticKeywordRuleScheduler.enqueue()`.
- `setEnabled()` persists `QUEUED` when sync is required, then enqueues.
- `syncNow()` calls `requestApplyExistingSync()`, which atomically increments revision and sets `pendingApplyToExisting=1`, `manualSyncStatus='QUEUED'`, then calls scheduler enqueue.

`AutomaticKeywordRuleScheduler.enqueue()` builds a one-time request under stable `AUTOMATIC_KEYWORD_RULE_SYNC_<ruleId>` and calls `enqueueUniqueWork(..., REPLACE, request)`, but it does not observe `Operation.result` and creates no durable handoff carrier.

Crash/enqueue-failure gap:

1. durable rule intent is committed;
2. process dies or WorkManager enqueue is not accepted before ownership is established;
3. app restarts with `QUEUED`/`pendingApplyToExisting` still durable but no sync work.

The startup path reconciles managed Observe coverage, not exact automatic-keyword sync WorkManager ownership.

Managed Observe discovery is not an equivalent recovery owner for explicit apply-to-existing:

- `AutomaticKeywordRuleEngine.recordDiscovery()` can complete an incomplete baseline, but it does not call `completeScheduledSyncIfCurrent()`;
- therefore it does not clear `pendingApplyToExisting` and does not terminalize the manual queued sync intent;
- only scheduled `applyFullSync()` / `recordBaseline()` call `completeScheduledSyncIfCurrent()`.

Concrete impact: a user `syncNow()` request can remain permanently `QUEUED`/`pendingApplyToExisting=1` after a crash/enqueue gap, with no deterministic startup re-enqueue.

### Acceptance

- Persist exact sync generation/request identity with the durable rule intent, or use an equivalent recoverable handoff carrier.
- Observe WorkManager enqueue acceptance; failed handoff remains durable retry debt.
- Startup reconciles enabled rules whose durable sync state requires an owner.
- Rule revision/reconfiguration supersedes old request generation; disable/delete revokes it.
- Process-death tests at DB-commit→enqueue, enqueue-accepted→acknowledgement, reconfiguration, disable/delete, and explicit apply-to-existing.

## Review Basis

No Review Basis advancement. It remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
