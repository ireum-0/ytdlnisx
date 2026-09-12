# BUG-OBSERVE-HANDOFF-01 — current CLEAN-basis generation/revocation revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Remote implementation HEAD independently verified before review: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Prior current-basis generation checkpoint: `2026-09-11__9c5191c3__observe-generation-current-basis-revalidation.md`
- Historical alias reconciliation: `BUG-OBSERVE-04` remains a subcase of this root.
- Separate carrier-loss root: P2 `BUG-OBSERVE-03` remains distinct.
- Overnight candidate `BUG-OBSERVE-02` was read only as cross-root compatibility evidence; it remains NOT_CLEAN and is not integrated.
- No in-progress implementation diff was inspected or relied on.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN — existing P0 `BUG-OBSERVE-HANDOFF-01` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

This is an already-counted semantic root.

- Canonical blocker-count delta: `0`
- Canonical blocker count remains: **P0 2 / P1 1 / P2 34**
- CLEAN Review Basis remains: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Overall canonical state remains `NOT_CLEAN`.

## Exact current production evidence

### 1. Ordinary Observe rows still have no immutable configuration generation

`ObserveSourcesItem` contains mutable configuration/runtime state under a numeric Room primary key, but no immutable ordinary configuration generation/revision token.

`ObserveSourcesRepository.observeTask()` cancels existing work and builds the replacement ordinary `ObserveSourceWorker` request with only the source numeric id. `finishRunAndSchedule()` does the same for the recurring successor. The special confirmed-retry carrier has fingerprint fields, but ordinary recurring Observe requests do not.

Therefore an already-running ordinary worker cannot prove that the configuration generation it loaded is still the current one.

### 2. Durable edit/STOP/delete can win while an older worker remains alive

For an existing edit, `ObserveSourcesViewModel.insertUpdate()` persists the changed source row and only afterward requests cancellation/replacement through `observeTask()`.

For STOP, the ViewModel durably writes `STOPPED` first and then requests WorkManager cancellation. Delete similarly requests cancellation without establishing a generation token that every surviving worker must validate before later mutation.

WorkManager cancellation/replacement is not a synchronous semantic revocation barrier for an already-running worker. The durable new decision can therefore coexist with an older E1 worker still executing.

### 3. Surviving E1 still owns stale full-row source writes

`ObserveSourceWorker` loads an `ObserveSourcesItem`, retains and mutates it through the run, and calls `repo.update(item)` from `updateRunStatus()` and `finishRunAndSchedule()`.

For ACTIVE sources the repository reaches `ObserveSourcesDao.update(item)`, which is still full-row `@Update(onConflict = REPLACE)` keyed by the numeric primary key. There is no expected-generation/status CAS predicate.

Concrete reachable sequence remains:

`worker E1 loads source`
→ `user edit E2 or STOP commits newer durable row`
→ E1 survives asynchronous cancellation`
→ E1 mutates retained stale row`
→ full-row update republishes stale configuration/runtime state over the newer durable decision`.

### 4. Destructive History/file mutation is not fenced by current Observe generation

The F3 SourceSnapshot authority gate remains present and correctly requires an authoritative membership snapshot before absence-driven removal. History deletion also revalidates stored target/reference snapshots under `HistoryReferenceMutationCoordinator`.

Those are different authority predicates.

Immediately before `HistoryFileDeletionEngine.execute(validation)` and the corresponding History record deletion, the worker does not reload/compare a current ordinary Observe configuration generation. An E1 worker whose old source snapshot is internally authoritative can therefore execute destructive deletion after E2/STOP durably superseded E1.

Concrete P0 impact remains:

`E1 ACTIVE worker with syncWithSource=true`
→ `E2 edit or STOP commits`
→ `E1 survives cancellation`
→ `E1 obtains AUTHORITATIVE membership for its old configuration`
→ no current-generation fence at destructive boundary`
→ filesystem deletion and History-row removal can occur under revoked authority`.

### 5. Positive Download publication is also not fenced by current Observe generation

After filtering source results, the worker creates `DownloadItem`s from the retained source template and inserts/updates Queued rows. There is no current Observe generation/status revalidation immediately before those durable Download publications.

A stale E1 may therefore publish positive Download intent using an obsolete URL/cadence/template/configuration after E2/STOP has already become durable.

This is part of the same generation/revocation authority root, not a new count.

### 6. Recurring successor publication remains stale-generation-capable

`finishRunAndSchedule()` persists the retained source item, constructs a successor carrying only `INPUT_SOURCE_ID`, and enqueues it under `OBSERVE<sourceID>` with `REPLACE`.

There is no exact current-generation validation immediately before successor publication. A revoked E1 can therefore republish recurring responsibility after an edit/STOP attempted to supersede or revoke it.

### 7. Special confirmed-retry fingerprint does not close ordinary generation authority

The worker checks `INPUT_CONFIG_FINGERPRINT` only when the special handoff fields are present. Ordinary `observeTask()` and ordinary recurring successors do not carry that fingerprint.

The special confirmed-retry carrier remains a distinct narrow authority mechanism and is not evidence that ordinary Observe generation/revocation is closed.

## Root reconciliation

Keep `BUG-OBSERVE-HANDOFF-01` counted once as P0. Historical `BUG-OBSERVE-04` remains an alias/subcase.

Keep distinct from:

- CLOSED F3 / `BUG-OBSERVE-01`: source-membership completeness/destructive-absence authority;
- P2 `BUG-OBSERVE-02`: form/configuration writes overwriting worker-owned runtime fields;
- P2 `BUG-OBSERVE-03`: current valid generation losing its WorkManager carrier/acceptance/recovery responsibility;
- P2 Observe source-identity roots concerning semantic row uniqueness;
- special confirmed-retry handoff/fingerprint semantics.

The reviewed NOT_CLEAN task-003 candidate is useful architecture evidence for splitting configuration-owned and worker-owned columns, but it cannot be replayed as-is because it dropped existing edit rescheduling/retry-confirmation side effects. A P0 generation fix must not reproduce that regression.

## Stable correction boundary

The P0 repair boundary is now stable enough for implementation planning. A coherent fix must provide:

1. an immutable durable ordinary Observe configuration generation/revision;
2. every ordinary WorkRequest bound to the exact expected generation;
3. edit/reconfigure, STOP, and delete semantics that durably supersede/revoke older generations;
4. generation-aware/CAS worker persistence that cannot overwrite a newer edit/STOP/delete decision;
5. exact current-generation validation immediately before correctness-relevant positive Download publication, destructive History/file mutation, and recurring successor publication;
6. stale workers that terminate/converge without restoring source state or publishing new work;
7. process-death/startup semantics that preserve/reconstruct the same generation fence;
8. preservation of F3 SourceSnapshot authority, History target/reference revalidation, membership-retry revocation behavior, and the special confirmed-retry carrier;
9. preservation of ordinary edit side effects: obsolete retry-confirmation invalidation, replacement scheduling, and automatic-keyword coverage reconciliation;
10. compatibility with a later `BUG-OBSERVE-03` carrier-acceptance/recovery repair: generation correctness and carrier liveness may share machinery, but one must not be used as a substitute for the other.

Required deterministic scenarios include:

- running E1 overlapped by edit E2 immediately before a source-row write;
- running E1 overlapped by STOP and by delete;
- supersession immediately before positive Download publication;
- supersession immediately before destructive History/file execution;
- supersession immediately before recurring successor enqueue;
- process death/restart with stale and current generations;
- ordinary edit still replaces scheduling and invalidates obsolete confirmation state;
- stale worker cannot restore STOP/delete or resurrect a successor;
- existing F3 authoritative/partial membership behavior remains unchanged.

## Canonical implementation-order note

This exploratory revalidation does not silently replace the already-recorded canonical implementation target `TASK_002_BUG_KEYWORD_04_CANONICAL_REPLAY_OR_REIMPLEMENTATION`. If that implementation has not started, protocol §3.1 permits continued exploratory review from this same fixed CLEAN basis; this checkpoint establishes that the P0 Observe generation/revocation repair is independently stable when it is selected for implementation.

INDEPENDENT EXECUTION: NOT EXECUTED
