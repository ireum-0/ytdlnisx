# BUG-OBSERVE-HANDOFF-01 — exact CLEAN-basis generation/revocation revalidation

Date: 2026-09-13

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Prior exact-basis checkpoint: `6e34be916cf621ddd3f80122fe89cdbd59010624` at `3616ae02...`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- The F4+F10 implementation wave is active after fixed completed SHA `f20833d6...`; no in-progress implementation diff was inspected or used as evidence.

## Verdict

**NOT_CLEAN — existing P0 `BUG-OBSERVE-HANDOFF-01` remains OPEN at exact canonical CLEAN basis `a12c5805...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 26**.
- CLEAN Review Basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range reconciliation

Exact compare `3616ae02... -> a12c5805...` is four commits ahead. The range changes duplicate-admission production/test surfaces and does modify `ObserveSourceWorker.kt`, but it does not add an immutable ordinary Observe configuration generation to `ObserveSourcesItem`, `ObserveSourcesRepository`, `ObserveSourcesViewModel`, or `ObserveSourcesDao`.

The worker change strengthens final Download duplicate admission. It does not establish current-generation authority for ordinary Observe execution.

## Exact current production evidence

### 1. Ordinary Observe requests still have no immutable configuration generation

At exact `a12c5805...`, `ObserveSourcesItem` is still keyed by auto-generated numeric Room `id` and has no ordinary immutable generation/revision field.

`ObserveSourcesRepository.observeTask()` cancels prior work and enqueues one-time `ObserveSourceWorker` using only the numeric source ID in input data. `ObserveSourceWorker.finishRunAndSchedule()` likewise publishes its recurring successor using only `INPUT_SOURCE_ID`.

The confirmed-retry path carries `INPUT_CONFIG_FINGERPRINT`, but that fingerprint is conditional on the special handoff fields and is not an ordinary recurring Observe generation.

### 2. Edit/STOP/delete can durably supersede a source while an older worker remains live

`ObserveSourcesViewModel.insertUpdate()` persists the edited item first and then calls `repository.observeTask(item)`, whose cancellation/replacement is asynchronous WorkManager control.

`stopObserving()` persists `STOPPED` first and then requests cancellation.

Delete requests cancellation and deletes the source row, but no immutable generation token is carried by every already-running ordinary worker.

Therefore a newer durable configuration/revocation can coexist with an older executing E1 worker.

### 3. Stale worker retains full-row source write authority

`ObserveSourceWorker` loads one `ObserveSourcesItem` and keeps that mutable object for the run.

`updateRunStatus()` and `finishRunAndSchedule()` call `repo.update(item)`. For ACTIVE sources the DAO path is full-row `@Update(onConflict = REPLACE)` by numeric primary key, without expected-generation/current-status CAS.

Concrete sequence remains reachable:

`E1 loads old source`
→ `E2 edit or STOP commits newer durable state`
→ `E1 survives cancellation`
→ `E1 updateRunStatus()/finishRunAndSchedule()`
→ stale retained row can republish old configuration/runtime state over E2/STOP.

### 4. Destructive History/file mutation remains unfenced by current Observe generation

The F3 `SourceSnapshot` authority fix remains preserved: only an authoritative source snapshot permits absence-driven cleanup, and History/file deletion still uses target/reference revalidation under `HistoryReferenceMutationCoordinator`.

But immediately before destructive execution, the worker does not reload or compare an ordinary Observe generation/status token.

A stale E1 can therefore hold authoritative membership for its old configuration, pass History target/reference revalidation, execute file deletion, and delete History rows even after E2 edit/STOP has durably revoked E1's source configuration authority.

The History reference lock proves record/file identity, not producer generation authority.

### 5. Positive Download publication remains a same-root consumer

The `3616ae02... -> a12c5805...` duplicate-admission work changes final insertion to `downloadRepo.insertNewWithDuplicateAdmission(...)` and adds post-insert claim protection.

That protects duplicate/admission identity. It does not prove the producer Observe generation is still current.

The worker still builds Download items from the retained E1 template, sets `observeSourceId = item.id`, and reaches durable admission without an ordinary current-generation check immediately before admission/publication.

Thus a superseded E1 can still publish new Download intent from an obsolete source/template after a newer edit or STOP has become durable.

This remains part of `BUG-OBSERVE-HANDOFF-01`; do not create a new root for the duplicate-admission consumer.

### 6. Recurring successor remains unfenced

`finishRunAndSchedule()` persists the retained item and enqueues `OBSERVE<sourceId>` with `ExistingWorkPolicy.REPLACE`, carrying only the numeric source ID.

No current-generation validation occurs immediately before the stale worker's source-row persistence or recurring successor publication.

A surviving old worker can therefore restore stale source state and republish recurring responsibility after a newer edit/STOP.

### 7. Special confirmed-retry fingerprint remains narrow

The special confirmed-retry path checks `WorkManagerHandoffRecovery.observeConfigFingerprint(item)` when handoff/fingerprint fields are present.

That is a valid narrow semantic fence for the confirmed notification decision, but ordinary Observe scheduling and recurring execution still lack the same immutable configuration generation.

## Root/count reconciliation

- Keep `BUG-OBSERVE-HANDOFF-01` counted once as P0.
- Historical `BUG-OBSERVE-04` remains an alias/subcase of this root.
- Keep distinct from CLOSED F3 / `BUG-OBSERVE-01` (source-membership completeness).
- Keep distinct from P2 `BUG-OBSERVE-02` (ordinary edit overwrites worker-owned runtime fields).
- Keep distinct from P2 `BUG-OBSERVE-03` (recurring scheduler acceptance/recovery debt).
- The duplicate-admission changes in `a12c5805...` do not close or split this root.
- Canonical count delta is `0`.

## Stable correction boundary

The existing implementation-ready boundary remains valid:

1. durable immutable ordinary Observe configuration generation/revision;
2. bind every ordinary request to its exact generation;
3. edit/reconfigure, STOP, and delete durably supersede/revoke older generations;
4. source-row worker persistence uses expected-generation/current-status fencing rather than stale full-row authority;
5. exact current-generation validation immediately before positive Download admission/publication, destructive History/file mutation, and recurring successor publication;
6. stale workers terminate/converge without restoring source state or publishing new work;
7. restart/process-death preserves or reconstructs the same generation fence;
8. preserve F3 SourceSnapshot completeness, History target/reference revalidation, membership-retry revocation, special confirmed-retry carrier semantics, and duplicate-admission closure.

INDEPENDENT EXECUTION: NOT EXECUTED