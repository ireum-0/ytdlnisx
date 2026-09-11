# BUG-OBSERVE-HANDOFF-01 — current-basis generation/revocation revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `0a30b49a364f1c5a36ddd0b07d2afbf4a3733ef2` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P1 `BUG-KEYWORD-01`
- Moving implementation diff inspected or relied on: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

This revalidation is required because the independently CLEAN basis advanced from `aa1616a2...` to `9c5191c3...` through the F3 / `BUG-OBSERVE-01` implementation range, and `ObserveSourceWorker.kt` was materially changed in that range. The prior P0 disposition therefore cannot be carried forward solely by ancestry.

Exact comparison `aa1616a2... -> 9c5191c3...` is five commits ahead / zero behind. Among the established ordinary Observe generation/revocation production surfaces, `ObserveSourceWorker.kt` changed; `ObserveSourcesRepository`, `ObserveSourcesDao`, `ObserveSourcesViewModel`, and `ObserveSourcesItem` did not receive a generation-authority redesign in that range.

## Verdict

**NOT_CLEAN — existing P0 `BUG-OBSERVE-HANDOFF-01` remains OPEN at `9c5191c3...`.**

This is an already-counted root.

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 2 / P1 3 / P2 25**
- CLEAN Review Basis remains: `9c5191c3539734fa1c9f1b63501def89f47b216a`

The recent F3 correction closes source-membership completeness authority, but it does not establish ordinary Observe configuration-generation/revocation authority.

## Current exact production chain

### 1. Ordinary Observe state still has no immutable configuration generation

`ObserveSourcesItem` at `9c5191c3...` still contains the mutable source configuration/runtime fields and numeric primary key, but no immutable ordinary configuration generation/revision token.

`ObserveSourcesRepository.observeTask()` still builds the ordinary WorkRequest with only the source numeric id in input data. It first calls `cancelObservationTaskByID(id)` and then issues `enqueueUniqueWork("OBSERVE$id", REPLACE, ...)`, but neither cancellation nor replacement is awaited as a semantic completion barrier.

`ObserveSourceWorker.finishRunAndSchedule()` likewise creates its recurring successor with only `INPUT_SOURCE_ID`; it does not propagate an immutable expected configuration generation.

### 2. Edit / STOP still commits before asynchronous revocation completes

`ObserveSourcesViewModel.insertUpdate()` for an existing source:

1. cancels the retry-confirmation UI path;
2. persists the new source row through `repository.update(item)`;
3. calls `repository.observeTask(item)`, which requests cancellation/replacement afterward.

`stopObserving()`:

1. marks the row STOPPED;
2. persists that STOPPED state;
3. only then requests WorkManager cancellation.

Delete paths also request WorkManager cancellation without establishing a durable ordinary worker generation that a surviving worker must consume before later mutation.

Therefore the newer configuration / STOP / deletion decision can be durable while an older already-running ordinary worker still executes.

### 3. Surviving old worker can still overwrite newer durable source state

The worker loads one `ObserveSourcesItem` and retains/mutates it across the run.

`updateRunStatus()` changes fields on that retained item and calls `repo.update(item)`.

`finishRunAndSchedule()` changes run history, run count, run-in-progress/status, and potentially source status, then calls `repo.update(item)` again.

For ACTIVE rows `ObserveSourcesRepository.update()` calls `ObserveSourcesDao.update(item)`. The DAO remains a full-row Room `@Update(onConflict = REPLACE)` keyed by the numeric primary key. There is no expected generation/revision/status CAS predicate.

Thus an older surviving worker can write its stale full-row configuration/runtime snapshot over a newer edit or STOP state.

### 4. Recent F3 SourceSnapshot authority does not solve generation revocation

The F3 implementation correctly adds typed `AUTHORITATIVE / PARTIAL / FAILED` source membership semantics and ensures destructive source absence requires AUTHORITATIVE membership.

That solves *whether the extraction is complete enough to claim absence*.

It does not solve *whether this worker generation is still authorized to act for the source*.

A worker from generation E1 can have a genuinely AUTHORITATIVE snapshot for E1 while E2/new configuration or STOP has already durably superseded E1. Membership authority and configuration-generation authority are distinct predicates.

### 5. Revoked ordinary generation can still perform destructive History/file mutation

At `9c5191c3...`, when an old retained source has `syncWithSource=true` and its source snapshot is AUTHORITATIVE, the worker computes absent processed links and enters the History/file deletion path.

Inside `HistoryReferenceMutationCoordinator.withLock`, `HistoryFileDeletionEngine` revalidates History stored-target/reference snapshots. That is file/reference authority, not Observe generation authority.

Immediately before `HistoryFileDeletionEngine.execute(validation)` there is no re-read/CAS of the current Observe source generation/configuration. After filesystem execution, the worker removes the corresponding History rows through `historyRepo.deleteRecordsWithinReferenceMutation(...)`, again without proving the worker generation is still current.

Concrete P0 chain remains:

`E1 ordinary Observe worker running with syncWithSource=true`
→ `user edit/STOP commits E2 or STOP durably`
→ `WorkManager cancellation/replacement is asynchronous`
→ `E1 has no immutable ordinary generation token`
→ `E1 obtains/retains AUTHORITATIVE membership for its old configuration`
→ `no current-generation revalidation at destructive mutation boundary`
→ `HistoryFileDeletionEngine.execute()`
→ `History record removal`
→ user media/history can be destructively mutated under revoked Observe authority.

The stronger F3 membership gate makes this path narrower and more truthful, but does not revoke E1 when E2/STOP wins.

### 6. Revoked worker can still publish a recurring successor

`finishRunAndSchedule()` persists the retained item and then enqueues another ordinary `ObserveSourceWorker` under `OBSERVE<sourceID>` using only the numeric source id.

There is no exact current configuration-generation validation immediately before successor publication. A stale worker can therefore republish ordinary recurring work after a newer configuration or STOP attempted to supersede it.

### 7. Special confirmed-retry fingerprint remains a distinct carrier

`WorkManagerHandoffRecovery` has durable exact handling for `OBSERVE_RETRY_DOWNLOAD` and places `handoffId`, exact request id, and `configFingerprint` into that special Observe WorkRequest.

`ObserveSourceWorker` checks `configFingerprint` only when the special `handoffId` and fingerprint are present.

Ordinary `observeTask()` and `finishRunAndSchedule()` requests do not use that carrier and do not carry the fingerprint. The special notification/retry fence therefore does not close ordinary configuration generation, STOP/delete revocation, source-row stale overwrite, destructive mutation, or successor publication.

## Root reconciliation

Keep this as the existing canonical P0 `BUG-OBSERVE-HANDOFF-01`, counted once.

It owns:

- ordinary Observe configuration generation identity;
- edit/reconfigure supersession;
- STOP/delete revocation;
- stale source-row write prevention;
- current-generation validation at Download/destructive publication boundaries;
- recurring successor publication authority;
- process-death reconstruction of that generation fence.

Do not merge it with:

- CLOSED `BUG-OBSERVE-01`, which owns source extraction completeness and destructive-absence membership authority;
- P2 `BUG-OBSERVE-SOURCE-IDENTITY-01`, which owns semantic source-row uniqueness and concurrent duplicate-policy publication;
- special confirmed Observe retry handoff semantics, which already have a narrower exact carrier.

## Correction boundary remains

A coherent fix still requires:

1. an immutable durable configuration generation/revision for every ordinary Observe source generation;
2. every ordinary WorkRequest to carry the exact expected generation;
3. edit/reconfigure, STOP, and delete to durably supersede/revoke prior generation authority;
4. generation-aware/CAS source-row writes instead of stale full-row overwrite authority;
5. exact current-generation validation immediately before correctness-relevant Download publication, destructive History/file mutation, and recurring successor publication;
6. stale ordinary workers to converge without restoring/reopening a newer or stopped source;
7. process-death/startup behavior to preserve/reconstruct the same generation authority;
8. preservation of the independently CLOSED F3 SourceSnapshot authority contract and the distinct special confirmed-retry handoff carrier.

Required future regression scenarios include an already-running E1 overlapped by edit E2, STOP, and delete; supersession immediately before source-row write, positive Download publication, destructive History/file execution, and successor enqueue; plus process death/restart with old and current generations.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
