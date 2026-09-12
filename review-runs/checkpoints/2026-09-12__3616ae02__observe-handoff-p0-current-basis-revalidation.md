# BUG-OBSERVE-HANDOFF-01 — exact CLEAN-basis generation/revocation revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Prior exact-basis checkpoint: `2026-09-12__9edd3e23__observe-handoff-p0-current-basis-revalidation.md`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` latest reviewed v6 state.
- Exact remote duplicate-admission verification for `8c5db3c7...` is a separate active verification task; no post-`3616ae02...` implementation state is used as evidence here.

## Verdict

**NOT_CLEAN — existing P0 `BUG-OBSERVE-HANDOFF-01` remains OPEN at exact canonical CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

Exact compare `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71 -> 3616ae02e56995e795cc52f3074d8c3d1cd2e330` is five commits ahead and modifies keyword/cache/Download/Terminal authority files, but does not modify `ObserveSourcesItem.kt`, `ObserveSourcesRepository.kt`, `ObserveSourcesViewModel.kt`, `ObserveSourcesDao.kt`, or `ObserveSourceWorker.kt`.

Exact `3616ae02...` production source was nevertheless re-read directly.

## Exact current production evidence

### 1. Ordinary Observe requests still have no immutable source generation

`ObserveSourcesItem` remains keyed by a mutable numeric Room primary key and contains no immutable ordinary configuration generation/revision field.

`ObserveSourcesRepository.observeTask()` cancels prior work and enqueues `ObserveSourceWorker` under `OBSERVE<sourceId>`, but ordinary request input carries only the numeric source ID. `ObserveSourceWorker.finishRunAndSchedule()` publishes its recurring successor the same way: only `INPUT_SOURCE_ID` is carried.

The special confirmed-retry path has `INPUT_CONFIG_FINGERPRINT`, but ordinary recurring Observe execution does not.

### 2. A newer durable edit/STOP/delete decision can coexist with an older live worker

For edit, `ObserveSourcesViewModel.insertUpdate()` first calls `repository.update(item)` and only then `repository.observeTask(item)`, which requests cancellation/replacement.

For STOP, the ViewModel mutates the item to `STOPPED`, durably calls `repository.update(item)`, then calls `cancelObservationTaskByID(item.id)`.

Delete requests WorkManager cancellation and deletes the source row, but there is still no generation token that every already-running ordinary worker must validate at later effect boundaries.

WorkManager cancellation/replacement therefore does not itself establish an immutable semantic generation fence for an already-running E1 worker.

### 3. Surviving E1 retains stale full-row source write authority

`ObserveSourceWorker` loads one `ObserveSourcesItem` and retains that mutable object through the run. `updateRunStatus()` and `finishRunAndSchedule()` call `repo.update(item)` on that retained object.

For an ACTIVE source, repository update reaches `ObserveSourcesDao.update(item)`, a full-row `@Update(onConflict = REPLACE)` keyed by the same numeric primary key. There is no expected-generation/status CAS predicate.

Concrete reachable sequence remains:

`E1 loads source`
→ `user edit E2 or STOP commits newer durable source state`
→ `E1 survives asynchronous cancellation long enough to continue`
→ `E1 calls updateRunStatus()/finishRunAndSchedule()`
→ `full-row update can republish E1's retained stale source state over E2/STOP`.

### 4. P0 destructive History/file effect remains unfenced by current Observe generation

F3 SourceSnapshot authority remains a distinct and valid completeness gate: absence-driven cleanup requires an authoritative source snapshot. History deletion also revalidates History target/reference snapshots under `HistoryReferenceMutationCoordinator`.

However, immediately before the destructive effect, the worker does not reload/compare an ordinary Observe configuration generation/status. At exact `3616ae02...`, a source-authoritative stale E1 can build deletion records, enter `HistoryReferenceMutationCoordinator.withLock`, run `HistoryFileDeletionEngine.execute(validation)`, revalidate History record target snapshots, and then call `historyRepo.deleteRecordsWithinReferenceMutation(...)` without proving that E1 is still the current Observe generation.

Concrete P0 chain remains:

`E1 ACTIVE + syncWithSource=true`
→ `E2 edit or STOP becomes durable`
→ `E1 survives cancellation`
→ `E1 obtains AUTHORITATIVE membership for its old configuration`
→ no current Observe-generation fence at destructive boundary`
→ filesystem deletion and History-row deletion execute under superseded/revoked Observe authority`.

History-reference revalidation protects file/record identity; it does not prove the Observe producer is still authorized.

### 5. Positive Download publication remains unfenced by current Observe generation

The same retained E1 source configuration drives Download creation/publication. At exact `3616ae02...`, a nonduplicate new item is durably inserted with `downloadRepo.insert(it)` and then can be handed to Download scheduling. There is no current Observe generation/status CAS immediately before this publication.

Thus E1 can publish positive Download intent from an obsolete source/template after an edit or STOP has already become durable. This is another consumer of the same generation/revocation authority root, not a new blocker.

### 6. Recurring successor can still resurrect stale responsibility

`finishRunAndSchedule()` first persists the retained source item, then creates a new one-time `ObserveSourceWorker` carrying only `INPUT_SOURCE_ID`, and enqueues it as `OBSERVE<sourceId>` with `ExistingWorkPolicy.REPLACE`.

There is no exact current-generation validation immediately before this successor publication. A stale E1 that outlives edit/STOP cancellation can therefore overwrite newer source state and republish recurring Observe responsibility.

### 7. Confirmed-retry fingerprint remains narrow and does not close ordinary authority

`WorkManagerHandoffRecovery` maintains an exact durable carrier and `configFingerprint` for the special confirmed-retry Download decision. `ObserveSourceWorker` checks that fingerprint only when the special handoff fields are present.

Ordinary `observeTask()` and recurring successors do not carry this fingerprint. The special one-shot handoff therefore remains useful narrow authority, but it is not an ordinary Observe configuration generation.

## Root/count reconciliation

- Keep `BUG-OBSERVE-HANDOFF-01` counted once as P0.
- Historical `BUG-OBSERVE-04` remains an alias/subcase of this root.
- Keep distinct from CLOSED F3 / `BUG-OBSERVE-01` (source-membership completeness), P2 `BUG-OBSERVE-02` (form writes overwriting worker-owned runtime fields), P2 `BUG-OBSERVE-03` (carrier acceptance/recovery liveness), and special confirmed-retry handoff semantics.
- No canonical count change.

## Stable correction boundary

The existing repair boundary remains valid and implementation-ready once the current exact-SHA verification wave releases the implementation branch:

1. durable immutable ordinary Observe configuration generation/revision;
2. every ordinary request bound to its exact generation;
3. edit/reconfigure, STOP, and delete durably supersede/revoke older generations;
4. source-row worker persistence fenced by expected generation/current status rather than stale full-row authority;
5. exact current-generation validation immediately before positive Download publication, destructive History/file mutation, and recurring successor publication;
6. stale workers terminate/converge without restoring source state or publishing new work;
7. restart/process-death preserves or reconstructs the same generation fence;
8. preserve F3 SourceSnapshot completeness, History target/reference revalidation, membership-retry revocation, special confirmed-retry carrier, and ordinary edit side effects.

`BUG-OBSERVE-HANDOFF-01` should be implemented before treating ordinary Observe cancellation/replacement as a safe semantic revocation boundary.

INDEPENDENT EXECUTION: NOT EXECUTED