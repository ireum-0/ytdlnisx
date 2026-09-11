# BUG-OBSERVE-HANDOFF-01 — exact-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Newer implementation diff used as exploratory evidence: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior canonical severity checkpoint: `review-runs/checkpoints/2026-09-11__6763fb1b__observe-generation-severity-reconciliation.md`

`6763fb1b... -> aa1616a2...` changes only the History duplicate-identity domain (`HistoryRepository`, `HistoryDuplicateIdentity`, `WebUrlInput`, and corresponding tests); no Observe generation/scheduling/revocation production file changed. The P0 conclusion was nevertheless re-read against exact `aa1616a2...` source rather than inherited automatically.

## Verdict

**NOT_CLEAN — existing P0 `BUG-OBSERVE-HANDOFF-01` is reconfirmed OPEN at `aa1616a2...`.**

This is an already-counted semantic root. No count change occurs.

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 3 / P1 3 / P2 25**
- CLEAN Review Basis remains: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Exact production chain

### 1. New configuration / STOP becomes durable before cancellation is a barrier

`ObserveSourcesViewModel.insertUpdate()` for an existing source:

1. persists the edited `ObserveSourcesItem` through `repository.update(item)`;
2. then calls `repository.observeTask(item)`.

`stopObserving()`:

1. sets `item.status = STOPPED`;
2. persists the stopped row through `repository.update(item)`;
3. only then calls `repository.cancelObservationTaskByID(item.id)`.

`ObserveSourcesRepository.cancelObservationTaskByID()` issues:

- `cancelUniqueWork("OBSERVE$id")`;
- cancellation by observation/id tags.

It does not await the returned WorkManager operations or establish a durable generation/revocation witness consumed by an already-running ordinary worker.

`observeTask()` likewise calls cancellation and then `enqueueUniqueWork("OBSERVE$id", REPLACE, ...)`. Scheduler replacement is therefore requested after the new configuration is already durable, but no consumer-side mutation fence proves the old worker is gone before old authority can be used.

### 2. Ordinary recurring requests carry no immutable generation

The ordinary `observeTask()` WorkRequest input contains only the numeric source id.

`ObserveSourceWorker.runSourceWork()` reads optional `handoffId`, `handoffRequestId`, and `configFingerprint`, but configuration fingerprint validation is conditional on the special notification-confirmation handoff. An ordinary recurring worker has no immutable configuration revision/generation token.

The worker loads one mutable/full `ObserveSourcesItem` by `sourceID` and keeps using that retained object through the run.

### 3. Old worker can overwrite newer source state

`updateRunStatus()` mutates fields on the retained `item` and calls `repo.update(item)`.

`finishRunAndSchedule()` mutates the same retained item (`runCount`, run state/status/history, possibly STOPPED) and calls `repo.update(item)` again.

`ObserveSourcesRepository.update()` for ACTIVE rows delegates to `ObserveSourcesDao.update(item)`, and the DAO update remains a full-row Room `@Update(onConflict = REPLACE)` keyed only by the source numeric primary key. There is no expected revision/configuration/status predicate.

Therefore an old worker surviving edit/STOP cancellation can write a stale full-row snapshot back over the newer durable configuration/state.

### 4. Old worker can publish another ordinary successor

When not terminal, `finishRunAndSchedule()` constructs and enqueues another `ObserveSourceWorker` under the same `OBSERVE<sourceID>` namespace. Its input again contains only `sourceID`.

No current source-generation/configuration predicate is checked before successor publication.

Thus a revoked generation can recreate recurring work after a new configuration or STOP attempted to revoke it.

### 5. Revoked generation still reaches the destructive History/file mutation point

At exact `aa1616a2...`, an ordinary worker can enter the `syncWithSource` branch using its retained old source configuration/result.

It constructs History deletion records and enters `HistoryReferenceMutationCoordinator.withLock`. `HistoryFileDeletionEngine` revalidates History stored-target/reference snapshots and protects retained file references, but the path does not re-read the Observe source row or compare an immutable source generation immediately before `engine.execute(validation)`.

After file execution, it removes the corresponding History records through `historyRepo.deleteRecordsWithinReferenceMutation(...)`, again without proving the worker's Observe generation/configuration is current.

The concrete destructive chain therefore remains:

`new Observe edit or STOP durably commits`
→ `old ordinary worker survives asynchronous WorkManager cancellation/replacement`
→ `worker has no immutable configuration generation`
→ `old worker reaches syncWithSource absence logic`
→ `no final Observe-generation revalidation`
→ `HistoryFileDeletionEngine.execute()`
→ `History record removal`
→ user media can be deleted under revoked source authority.

This remains sufficient for P0 severity.

## Why special Observe-retry fencing does not close the ordinary root

Current source does have a `configFingerprint` check for the special confirmed Observe-retry handoff path when `handoffId` and the fingerprint are supplied. That is a different semantic carrier.

The ordinary recurring Observe request built by `observeTask()` and by `finishRunAndSchedule()` does not carry this fingerprint/handoff generation, so the special retry fence is not a general configuration-generation authority and does not close this root.

## Root reconciliation

Keep this as existing P0 `BUG-OBSERVE-HANDOFF-01`:

- it owns ordinary Observe configuration generation, supersession, STOP/delete revocation, final-mutation fencing, and successor publication authority;
- `BUG-OBSERVE-01` separately owns source extraction completeness/destructive absence authority;
- `BUG-OBSERVE-SOURCE-IDENTITY-01` separately owns semantic source-row uniqueness and concurrent duplicate-policy publication.

No new root is added and no severity change is made.

## Required correction boundary

A coherent repair still requires:

1. immutable durable configuration generation/revision for every ordinary Observe source generation;
2. every ordinary WorkRequest to carry the exact expected generation;
3. edit, STOP, and delete to durably supersede/revoke the old generation before old authority may mutate;
4. current-generation/CAS-style guards for source-row writes instead of stale full-row overwrite authority;
5. final generation revalidation immediately before correctness-relevant Download publication, destructive source-absence History/file mutation, and successor scheduling;
6. stale workers to converge without restoring/reopening a newer or stopped source;
7. process-death/startup behavior to retain or reconstruct the same generation fence.

The fix must preserve the distinct source-snapshot completeness contract and special confirmed-retry handoff semantics rather than conflating them into one token.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review. Source-level production wiring was reviewed at the exact fixed basis.

INDEPENDENT EXECUTION: NOT EXECUTED
