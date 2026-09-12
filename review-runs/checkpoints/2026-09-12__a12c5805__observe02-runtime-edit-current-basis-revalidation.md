# BUG-OBSERVE-02 — exact CLEAN-basis revalidation

Date: 2026-09-12 UTC

## Exact review state

- Fixed contiguous independently CLEAN basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Workflow state: `IMPLEMENTATION_DIFF_FROZEN_REVIEW_CONTINUES`.
- Active implementation target: F4 / `BUG-BACKUP-04`, started by explicit user signal. No in-progress implementation diff was inspected or relied upon.
- Finding: existing P2 `BUG-OBSERVE-02`.
- Prior exact-basis checkpoint: `7004275a42c2da3d6b26f043af974ea60eb0c1ca` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-OBSERVE-02` remains valid at exact CLEAN basis `a12c5805...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 29**.
- CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.

## Intervening-range verification

The accepted range `3616ae02... -> a12c5805...` is the duplicate-admission remediation/test-harness range. It does not modify `ObserveSourcesBottomSheetDialog.kt`, `ObserveSourcesItem.kt`, `ObserveSourcesViewModel.kt`, `ObserveSourcesRepository.kt`, or `ObserveSourcesDao.kt`. `ObserveSourceWorker.kt` changed only in duplicate-admission publication/continuation wiring; the run-count/end-condition semantics relevant to this root were re-opened at exact final source and remain unchanged.

## Exact current production evidence

### 1. Ordinary configuration Save still manufactures reset runtime state

When editing an existing Observe Source, `ObserveSourcesBottomSheetDialog` constructs a new `ObserveSourcesItem` and explicitly writes `runCount = 0`.

The Save path has explicit logic for processed-link reset/preservation, but does not carry forward the existing row's worker-owned `runHistory`, `runInProgress`, or `currentRunStatus`.

`ObserveSourcesItem` defaults those omitted fields to empty history, `false`, and blank status. Therefore a configuration-only Save still constructs reset runtime state.

### 2. Persistence still publishes the reconstructed full row

`ObserveSourcesViewModel.insertUpdate(item)` calls `repository.update(item)` for an existing source before rescheduling.

`ObserveSourcesRepository.update(item)` calls the DAO update for an active source, and `ObserveSourcesDao.update(item)` remains a full-row Room `@Update(onConflict = OnConflictStrategy.REPLACE)` with no configuration-only update or expected-runtime/revision predicate.

The reset/default runtime values from the edit writer therefore become durable database state.

### 3. These fields own execution correctness

At exact `a12c5805...`, `ObserveSourceWorker.finishRunAndSchedule()` still increments `item.runCount` for counted runs and treats `(endsAfterCount > 0 && runCount >= endsAfterCount)` as a terminal condition. The worker also writes `runInProgress/currentRunStatus` and appends `runHistory`.

Concrete sequence remains:

`endsAfterCount = 10`, durable `runCount = 8`
→ user edits only name/cadence/template
→ Save persists `runCount = 0`
→ source can receive up to ten additional counted runs rather than the intended remaining two.

The same ordinary Save can erase run history and replace live run status with default inactive/blank values.

### 4. Explicit lifecycle/reset authorities remain distinct

The product has explicit processed-link reset controls and explicit source lifecycle/start behavior that may own documented resets. Their existence does not authorize ordinary configuration edit to reset worker-owned run progress.

## Root reconciliation

This remains the same already-counted P2 `BUG-OBSERVE-02`; count delta is `0`.

It remains distinct from:

- P0 `BUG-OBSERVE-HANDOFF-01`, which owns stale configuration-generation / execution authority;
- P2 `BUG-OBSERVE-03`, which owns recurring successor handoff/acceptance debt;
- CLOSED F3 `BUG-OBSERVE-01`, which owns authoritative source snapshots before destructive absence reconciliation.

Edit-during-run can intersect those roots, but `BUG-OBSERVE-02` is independently reproducible because the edit writer itself persists reset runtime fields.

## Stable correction boundary

A future correction still needs to separate user-editable configuration from worker-owned runtime/progress state, preserve `runCount`, `runHistory`, `runInProgress`, and `currentRunStatus` across ordinary edits, preserve explicit processed-link reset semantics, and retain explicit lifecycle reset as its own authority.

No implementation prompt is issued while F4 `BUG-BACKUP-04` implementation is active.

INDEPENDENT EXECUTION: NOT EXECUTED