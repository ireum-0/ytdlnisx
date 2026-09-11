# BUG-KEYWORD-HANDOFF-01 — current-basis automatic-keyword sync handoff revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `077798ff624f81234560b6fe63411676ee3febb2` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P1 `BUG-KEYWORD-01`
- Moving implementation diff inspected or relied on: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

`AutomaticKeywordRuleSyncWorker.kt` changed in the F3 range to consume typed SourceSnapshot authority, so the handoff root is revalidated at the current CLEAN basis rather than inherited automatically. The scheduler/repository ownership protocol itself was not replaced in that range.

## Verdict

**NOT_CLEAN — existing P2 `BUG-KEYWORD-HANDOFF-01` remains OPEN at `9c5191c3...`.**

- Canonical blocker-count delta: **0**
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

Keep this root distinct from active P1 `BUG-KEYWORD-01`: F12 owns source-membership authority for baseline/discovery; this P2 owns durable DB intent -> WorkManager acceptance/recovery/supersession ownership.

## 1. Durable sync intent still precedes WorkManager ownership

Current `AutomaticKeywordRuleRepository` persists semantic sync intent before calling the scheduler.

Production examples:

### save()

When initial/baseline/apply-existing work is required, the repository first updates `manualSyncStatus` to `QUEUED` for the exact rule revision and only afterward calls `AutomaticKeywordRuleScheduler.enqueue(...)`.

### setEnabled()

Enabling a rule that needs sync persists the new enabled/revision state with `manualSyncStatus=QUEUED`, then calls scheduler enqueue.

### syncNow()

`syncNow()` first calls `requestApplyExistingSync(...)`. That durable DAO transition establishes the apply-existing intent/revision/status before scheduler enqueue is invoked.

Thus user-visible semantic intent can already be durable while no accepted WorkManager owner exists yet.

## 2. Scheduler still treats request issuance as if ownership were sufficient

`AutomaticKeywordRuleScheduler.enqueue()` builds a one-time `AutomaticKeywordRuleSyncWorker` request and calls:

`WorkManager.getInstance(context).enqueueUniqueWork(workName(ruleId), ExistingWorkPolicy.REPLACE, request)`.

The returned WorkManager `Operation` is ignored.

The scheduler still persists no:

- exact WorkRequest UUID as a durable ownership carrier;
- semantic generation/revision-to-request mapping;
- pending-enqueue/accepted state;
- durable retry/recovery debt for enqueue failure/process death.

A stable unique-work name serializes successful WorkManager ownership but is not proof that the DB-intent -> WorkManager handoff was accepted.

## 3. Process-death/enqueue-failure gap remains concrete

Concrete apply-existing sequence remains:

1. enabled rule receives `syncNow()`;
2. Room commits a newer revision with `pendingApplyToExisting=true`, `manualSyncStatus=QUEUED` and sync metadata;
3. process dies, or WorkManager enqueue/acceptance fails, before an accepted sync-worker owner is established;
4. app restarts with the durable semantic intent still present;
5. no exact persisted request/generation carrier identifies a missing owner;
6. no deterministic startup reconciler re-enqueues the explicit apply-existing sync because ownership is absent;
7. `pendingApplyToExisting` can remain durably pending without a worker capable of terminalizing `completeScheduledSyncIfCurrent()`.

The F3 typed SourceSnapshot correction changes what a running worker is allowed to do after extraction. It does not close this producer-side ownership gap before the worker starts.

## 4. Managed Observe coverage remains a different owner

`AutomaticKeywordObservationCoverage.reconcile()` inventories enabled rules and manages hidden `KEYWORD_DISCOVERY` Observe sources by condition key.

It can create/restart a managed discovery source, but it does not inspect or own:

- `manualSyncStatus=QUEUED` as explicit sync debt;
- `pendingApplyToExisting` as missing manual/full-sync ownership;
- an exact AutomaticKeywordRuleSyncWorker request id/generation;
- WorkManager acceptance/recovery state for the explicit sync.

Therefore discovery coverage after restart cannot be treated as recovery of the user-requested apply-existing handoff.

## 5. Typed F12 authority does not substitute for handoff ownership

At `9c5191c3...`, the running SyncWorker correctly treats PARTIAL/FAILED as non-authoritative for baseline/full-sync/discovery and only AUTHORITATIVE membership reaches those mutations.

That closes the source-membership semantic defect at source level, but only after a worker actually exists and runs.

The P2 failure occurs earlier:

`durable rule intent -> missing/unaccepted WorkManager owner`.

A correct consumer cannot complete an intent it never receives.

## 6. Cancellation/revocation is also still request-only

`AutomaticKeywordRuleScheduler.cancel()` still calls `cancelUniqueWork(workName(ruleId))` without awaiting completion or binding cancellation to a durable exact request/generation carrier.

Current worker revision checks are useful mutation fences once execution reaches them, but they do not by themselves establish producer-side enqueue/cancel completion ownership or restart discovery of missing explicit sync debt.

## Root reconciliation

Keep this as existing canonical P2 `BUG-KEYWORD-HANDOFF-01`, counted once.

It owns:

- durable automatic-keyword sync intent -> WorkManager accepted ownership;
- exact request/revision/generation handoff identity;
- enqueue failure/process-death recovery;
- restart discovery of missing explicit sync ownership;
- reconfiguration/disable/delete supersession and revocation of explicit sync owners.

Keep distinct from:

- active P1 `BUG-KEYWORD-01` / F12 source authority and production coverage;
- P2 `BUG-KEYWORD-02` History undo/rule-revision assignment restoration semantics;
- managed Observe discovery coverage, which is a separate discovery owner.

## Correction boundary remains

1. pair each durable explicit keyword-sync intent with an exact accepted WorkManager owner or a durable recoverable pending-handoff carrier;
2. observe WorkManager enqueue acceptance/failure rather than discarding `Operation.result`;
3. persist exact rule revision/generation/request identity before producer responsibility can be forgotten;
4. reconcile startup rules whose durable state requires explicit sync but whose exact worker owner is missing/stale;
5. make reconfiguration/disable/delete supersede/revoke old explicit generations deterministically;
6. preserve current worker-side F12 SourceSnapshot authority and revision checks;
7. keep managed discovery separate from explicit apply-existing completion;
8. add production tests for process death/enqueue failure, accepted acknowledgement, startup repair, revision supersession, disable/delete, and apply-existing completion.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
