# BUG-OBSERVE-02 — Observe Source runtime-state edit current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Implementation branch: `checkpoint/pre-baseline-review`.
- Active implementation wave: P2 `BUG-DUPLICATE-ADMISSION-01`, frozen start SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Post-start implementation commits/diffs inspected or relied on: **NO**.
- Prior exact-basis checkpoint: `review-runs/checkpoints/2026-09-12__93d01d2a__observe02-runtime-edit-current-basis-revalidation.md`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**OPEN — existing P2 `BUG-OBSERVE-02` remains independently reproducible at exact CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN` because unrelated counted roots remain open.

This exploratory finding is pre-existing relative to the just-closed cache implementation scope and therefore does not retract the independently established CLEAN status of `3616ae02...` for that cumulative implementation range.

## Intervening-range verification

The exact `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c -> 3616ae02e56995e795cc52f3074d8c3d1cd2e330` cumulative range contains the cache-authority implementation/review-fix files and does not modify:

- `ObserveSourcesBottomSheetDialog.kt`;
- `ObserveSourcesItem.kt`;
- `ObserveSourcesViewModel.kt`;
- `ObserveSourcesRepository.kt`;
- `ObserveSourcesDao.kt`.

Exact `3616ae02...` source was nevertheless re-read rather than carrying the earlier disposition forward solely from diff absence.

## Exact current production evidence

### 1. Ordinary configuration Save still reconstructs worker-owned runtime fields

When editing an existing source, `ObserveSourcesBottomSheetDialog` constructs a fresh `ObserveSourcesItem` on Save rather than applying configuration changes to a freshly loaded current row.

The constructed item explicitly sets:

`runCount = 0`

It conditionally preserves or clears the processed-link membership fields according to the explicit reset control, including `alreadyProcessedLinks`, `ignoredLinks`, `retryPromptedLinks`, and `observedLinks`.

However the constructor does not carry forward the current row's:

- `runHistory`;
- `runInProgress`;
- `currentRunStatus`.

`ObserveSourcesItem` supplies default values of empty history, `false`, and empty status for those omitted fields. An ordinary configuration Save therefore manufactures reset runtime state even when the user changed only name, cadence, template, or another configuration field.

### 2. Persistence still writes the reconstructed object as the durable row

For an existing source, `ObserveSourcesViewModel.insertUpdate(item)` calls `repository.update(item)` and then reschedules observation. It does not reload and merge current worker-owned runtime state before the write.

`ObserveSourcesRepository.update(item)` ultimately reaches the DAO update for an active item. `ObserveSourcesDao.update(item)` remains a full-row Room `@Update(onConflict = OnConflictStrategy.REPLACE)` with no configuration-only field mutation and no expected runtime/revision predicate.

Therefore the dialog's reset/default runtime values become durable database state.

### 3. Runtime state controls correctness, not only presentation

Observe execution increments `runCount` and uses it to enforce `endsAfterCount`; run history records completed/failed run progression, while `runInProgress` and `currentRunStatus` represent live execution state.

Concrete sequence remains:

`endsAfterCount = 10`, durable `runCount = 8`
→ user edits only configuration
→ Save persists `runCount = 0`
→ source can receive up to ten more counted runs instead of the remaining two.

The same Save can erase existing run history and can persist `runInProgress = false` / blank status while an execution is still active.

### 4. Explicit processed-link reset and lifecycle reset remain distinct authorities

The edit dialog already has an explicit processed-link reset control and preserves those ledgers when that control is not selected. That does not authorize resetting run count/history/status.

The product also has a dedicated Start/lifecycle transition that explicitly resets run count. Ordinary configuration editing is not that transition.

## Root reconciliation

This is the same already-counted P2 `BUG-OBSERVE-02`; no new root is added.

It remains distinct from:

- P0 `BUG-OBSERVE-HANDOFF-01`, which owns durable WorkManager handoff/replacement and stale execution-generation authority;
- `BUG-OBSERVE-SOURCE-IDENTITY-01`, which owns semantic source uniqueness / atomic source publication;
- CLOSED F3 `BUG-OBSERVE-01`, which owns authoritative source snapshots before destructive absence reconciliation;
- P2 `BUG-OBSERVE-03`, which owns scheduler carrier loss after otherwise valid scheduling intent.

Edit-during-run can intersect the handoff/generation root, but the present root is independently reproducible without a stale worker winning later: the edit writer itself persists reset runtime values.

## Stable correction boundary

A future correction should:

- separate user-editable Observe Source configuration from worker-owned runtime/progress state;
- make ordinary edit preserve current `runCount`, `runHistory`, `runInProgress`, and `currentRunStatus` through a transactional merge, expected-revision mutation, or configuration-only DAO update;
- let explicit processed-link reset affect only the documented processed/ignored/retry/observed membership state;
- retain explicit Start/reset as the lifecycle transition that owns run-count reset unless a separately defined reset action is introduced;
- reschedule from the committed merged configuration without erasing runtime progress;
- preserve stale-worker/generation safety without double-counting the separate handoff root.

Required deterministic production scenarios remain:

- edit name/cadence/template at nonzero `runCount`;
- `endsAfterCount` near its terminal boundary;
- non-empty `runHistory` preservation;
- explicit processed-link reset;
- explicit Start/reset behavior;
- edit while a run is active, proving the edit writer itself does not clear worker-owned runtime state.

No implementation prompt is issued while the separate `BUG-DUPLICATE-ADMISSION-01` implementation wave is active.

INDEPENDENT EXECUTION: NOT EXECUTED