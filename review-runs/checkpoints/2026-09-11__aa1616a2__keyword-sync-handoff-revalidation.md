# BUG-KEYWORD-HANDOFF-01 — automatic-keyword sync handoff revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-KEYWORD-HANDOFF-01`

The exact CLEAN basis still allows a durable automatic-keyword sync intent to be committed before any exact WorkManager ownership or durable enqueue-recovery debt exists. A crash or enqueue failure in that gap can leave `QUEUED` / incomplete-baseline / `pendingApplyToExisting` state without a deterministic worker owner, and startup does not reconstruct that explicit sync handoff.

## Durable intent precedes WorkManager ownership

Exact source: `AutomaticKeywordRuleRepository.kt@aa1616a2...`.

The repository establishes durable sync intent before scheduling in multiple production paths:

- `save()` persists the rule/revision and then writes `manualSyncStatus=QUEUED` before calling `AutomaticKeywordRuleScheduler.enqueue()` when an initial/baseline/apply-existing sync is required;
- `setEnabled()` can persist enabled state plus `manualSyncStatus=QUEUED` and only afterward call the scheduler;
- `syncNow()` first calls `requestApplyExistingSync()` and only after that succeeds invokes scheduler enqueue.

Exact DAO semantics for `requestApplyExistingSync()` are especially explicit:

- increment `revision`;
- set `pendingApplyToExisting = 1`;
- set `manualSyncStatus = 'QUEUED'`;
- persist the sync timestamp/error state.

The user-requested apply-to-existing semantic intent is therefore already durable before WorkManager ownership is attempted.

## Scheduler does not observe enqueue acceptance

Exact source: `AutomaticKeywordRuleScheduler.kt@aa1616a2...`.

`enqueue()` builds a one-time `AutomaticKeywordRuleSyncWorker` request and calls:

`WorkManager.getInstance(context).enqueueUniqueWork(workName(ruleId), ExistingWorkPolicy.REPLACE, request)`

The returned `Operation` is ignored. The scheduler persists no exact request UUID/generation carrier, no pending-enqueue state, and no retry debt that remains discoverable if the enqueue is rejected/fails or the process dies after the DB intent commit and before accepted WorkManager ownership is established.

A stable unique-work name prevents parallel ordinary owners under successful WorkManager operation, but it does not prove request acceptance and is not a durable handoff acknowledgement.

## Startup recovery does not own explicit/manual sync debt

`App` startup invokes `AutomaticKeywordObservationCoverage.reconcile()`, not an automatic-keyword manual-sync ownership reconciler.

`AutomaticKeywordObservationCoverage` owns hidden managed Observe sources for playlist discovery. It creates/restarts a managed source when discovery coverage is missing, but it does not inspect a rule's `manualSyncStatus=QUEUED`, `pendingApplyToExisting`, or an exact scheduled-sync request identity and does not re-enqueue `AutomaticKeywordRuleSyncWorker` because its owner is missing.

Therefore startup discovery coverage is not closure for the explicit sync handoff.

## Managed discovery cannot substitute for apply-existing completion

`AutomaticKeywordRuleEngine.recordDiscovery()` can record source observations and may complete an incomplete discovery baseline through `completeBaselineIfCurrent()`.

However explicit scheduled baseline/full-sync flows use `recordBaseline()` / `applyFullSync()`, and those call `completeScheduledSyncIfCurrent()` when their exact current rule snapshot completes. That DAO transition sets `baselineComplete=1` and, critically, clears `pendingApplyToExisting=0`.

`recordDiscovery()` does not call `completeScheduledSyncIfCurrent()` and therefore cannot terminalize the explicit apply-existing durable intent.

Concrete failure sequence remains:

1. an enabled rule receives `syncNow()`;
2. Room atomically increments revision and persists `pendingApplyToExisting=1`, `manualSyncStatus=QUEUED`;
3. process death or WorkManager enqueue failure occurs before accepted sync-worker ownership exists;
4. app restarts;
5. managed Observe discovery may continue, but no deterministic startup path reconstructs the missing exact manual/apply-existing worker;
6. the user-requested apply-existing intent can remain durably queued/pending without an owner capable of completing `completeScheduledSyncIfCurrent()`.

## Existing tests do not close the handoff gap

The current infrastructure JVM test proves the stable unique work name (`AUTOMATIC_KEYWORD_RULE_SYNC_<id>`) and managed-source policy properties. That does not exercise or prove:

- WorkManager `Operation.result` acceptance;
- process death between durable sync intent and enqueue acceptance;
- startup reconstruction of missing explicit sync ownership;
- exact generation/revision supersession;
- disable/delete revocation of an already-running/stale explicit sync owner.

## Required correction boundary carried forward

The existing acceptance remains applicable:

1. Durable automatic-keyword sync intent must be paired with an exact accepted WorkManager owner or durable recoverable handoff debt.
2. Observe enqueue acceptance/completion semantics rather than treating `enqueueUniqueWork()` invocation as acceptance.
3. Persist exact request/generation/revision identity or an equivalent carrier before the producer can forget responsibility.
4. Startup must reconcile enabled rules whose durable sync state requires a worker and idempotently repair missing/stale ownership.
5. Rule revision/reconfiguration must supersede old worker generations; disable/delete must revoke them.
6. Consumer execution must validate the current rule revision/generation at correctness-relevant mutation/terminal boundaries.
7. Managed Observe discovery remains a separate discovery owner and must not be treated as proof that an explicit apply-existing request completed.
8. Add production-level process-death/enqueue-failure coverage for DB-intent -> enqueue, enqueue accepted -> acknowledgement, restart repair, reconfiguration, disable/delete, and explicit apply-to-existing.

## Root/count and basis reconciliation

- `BUG-KEYWORD-HANDOFF-01` was already counted as one P2 root.
- This exact-basis review reconfirms that root; no new root is introduced.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- `BUG-KEYWORD-01` remains a distinct P1 semantic source/baseline correctness root; this handoff review does not close or double-count it.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is unchanged.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
