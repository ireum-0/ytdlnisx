# BUG-OBSERVE-02 — Observe Source runtime-state edit current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Implementation branch: `checkpoint/pre-baseline-review`.
- Verification-only CACHE-02 wave remains active at exact implementation SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`; no post-basis implementation diff was used for this exploratory review.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Prior promotion checkpoint: `4c27b0588b632086085165882325e7961f675cd2` at basis `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-OBSERVE-02` remains OPEN at exact canonical CLEAN basis `93d01d2a...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 31**.
- CLEAN Review Basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Overall canonical state remains `NOT_CLEAN`.

## Exact current production evidence

### 1. Ordinary configuration Save still reconstructs runtime-owned columns

`ObserveSourcesBottomSheetDialog` receives the current `ObserveSourcesItem` for an edit but Save constructs a fresh `ObserveSourcesItem`.

The fresh row explicitly sets `runCount = 0`.

It conditionally preserves or clears processed-membership fields according to the documented controls, including `alreadyProcessedLinks`, `ignoredLinks`, `retryPromptedLinks`, and `observedLinks`.

However it does not pass through the current row's:

- `runHistory`;
- `runInProgress`;
- `currentRunStatus`.

`ObserveSourcesItem` defaults those omitted values to an empty run history, `false`, and an empty status string. Ordinary Save therefore constructs reset runtime state even when the user changed only configuration.

### 2. The persistence path still performs a full-row write

For an existing source, `ObserveSourcesViewModel.insertUpdate(item)` calls `repository.update(item)` and then reschedules the observation. It does not reread and transactionally merge current worker-owned runtime state.

For an ACTIVE source, `ObserveSourcesRepository.update(item)` forwards the object to `ObserveSourcesDao.update(item)`.

`ObserveSourcesDao.update(item)` remains a Room full-row `@Update(onConflict = REPLACE)` of `ObserveSourcesItem`; there is no configuration-only update or expected-runtime/revision predicate. The dialog's reset/default values therefore become durable state.

### 3. Runtime fields are correctness state, not presentation-only state

`ObserveSourceWorker.finishRunAndSchedule()` appends run history and increments `runCount` for a counted run. It stops the source when `endsAfterCount > 0 && runCount >= endsAfterCount` or when the end date is reached.

Concrete impact remains:

`endsAfterCount = 10`, durable `runCount = 8`
→ user edits only name/cadence/template
→ ordinary Save persists `runCount = 0`
→ the source can execute up to ten additional counted runs instead of the remaining two.

The same edit clears user-visible `runHistory`; an edit while a run is active can also persist `runInProgress = false` and `currentRunStatus = ""` even though those values are owned by live worker progress.

### 4. Explicit Start proves run-count reset is a separate lifecycle action

`ObserveSourcesFragment.onItemStart(...)` explicitly sets the source `status = ACTIVE` and `runCount = 0` before calling `insertUpdate(item)`.

The product therefore already has a dedicated lifecycle action that intentionally owns run-count reset. Ordinary configuration Save is not that action and has no equivalent semantic authority to reset execution progress.

## Root reconciliation

This remains the same existing P2 `BUG-OBSERVE-02`; do not increment the blocker count again.

It remains distinct from:

- P0 `BUG-OBSERVE-HANDOFF-01`, which owns stale-worker generation/revocation and durable WorkManager handoff/replacement authority;
- `BUG-OBSERVE-SOURCE-IDENTITY-01`, which owns semantic source uniqueness / atomic source publication;
- CLOSED F3 `BUG-OBSERVE-01`, which owns source-snapshot authority before destructive absence reconciliation;
- P2 `BUG-OBSERVE-03`, which owns loss of the WorkManager carrier after otherwise valid source scheduling intent.

A future implementation may share a revision/config-runtime ownership mechanism with `BUG-OBSERVE-HANDOFF-01`, but this root is independently reproducible because the edit writer itself destroys runtime-owned state even without a stale worker later winning.

## Stable correction boundary

- separate user-editable Observe Source configuration from worker-owned runtime/progress state;
- ordinary edit must preserve `runCount`, `runHistory`, `runInProgress`, and `currentRunStatus` through a transactional merge or configuration-only DAO mutation;
- `resetProcessedLinks` may reset only the documented processed/ignored/retry/observed membership state;
- explicit Start/reset remains the lifecycle action that owns run-count reset unless another dedicated reset transition is defined;
- rescheduling after edit must use the committed merged configuration without erasing prior runtime progress;
- edit-during-run ordering must preserve current runtime ownership while not allowing an older worker generation to overwrite newer configuration (the latter remains P0 handoff/root scope);
- deterministic production regressions should cover nonzero `runCount`, `endsAfterCount` near termination, run-history preservation, explicit processed-link reset, explicit Start reset, and edit during an active run.

INDEPENDENT EXECUTION: NOT EXECUTED