# BUG-OBSERVE-02 — exact CLEAN-basis revalidation

Date: 2026-09-14

## Exact review state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior exact-basis checkpoint: `ed5b50994b653e3c0b81efc08a3c877cec8d98d7` at `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Active implementation wave F10+F16+F17 remains frozen from review; implementation branch metadata was rechecked and still resolves to `973424909fd97de758b62f639967c8bae7c0bad7`. No in-progress implementation diff was inspected.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `BUG-OBSERVE-02` remains valid at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Intervening-range verification

Exact compare `a12c5805... -> 90afaec1...` is 12 commits ahead. The range is backup-focused and does not modify `ObserveSourcesBottomSheetDialog.kt`, `ObserveSourcesItem.kt`, `ObserveSourcesViewModel.kt`, `ObserveSourcesRepository.kt`, `ObserveSourcesDao.kt`, or `ObserveSourceWorker.kt`.

Exact final-basis production source was nevertheless re-read directly.

## Exact current production evidence

### 1. Ordinary configuration Save still constructs reset runtime state

When editing an existing user Observe Source, `ObserveSourcesBottomSheetDialog` constructs a new `ObserveSourcesItem` and explicitly passes `runCount = 0`.

The same constructor call has explicit preservation/reset policy for processed links, but it does not pass the existing row's worker-owned `runHistory`, `runInProgress`, or `currentRunStatus`.

`ObserveSourcesItem` defaults those omitted runtime fields to:

- `runHistory = mutableListOf()`;
- `runInProgress = false`;
- `currentRunStatus = ""`.

Therefore a configuration-only edit manufactures reset runtime state rather than preserving the current execution/progress state.

### 2. The reconstructed full row is still durably published

`ObserveSourcesViewModel.insertUpdate(item)` handles an existing source by calling `repository.update(item)` and then rescheduling it.

For an active source, `ObserveSourcesRepository.update(item)` delegates to `ObserveSourcesDao.update(item)`.

`ObserveSourcesDao.update(item)` remains a full-row Room `@Update(onConflict = OnConflictStrategy.REPLACE)` with no configuration-only field update and no expected-runtime/revision predicate.

The reset runtime fields created by the edit UI therefore become durable database state.

### 3. The overwritten fields are correctness-bearing worker state

At exact `90afaec1...`, `ObserveSourceWorker` still:

- sets `runInProgress` and `currentRunStatus` during execution;
- appends to `runHistory`;
- increments `runCount` on counted completion;
- stops recurrence when `endsAfterCount > 0 && runCount >= endsAfterCount`.

Concrete sequence remains:

`endsAfterCount = 10`, durable `runCount = 8`
-> user edits only configuration such as name/cadence/template
-> Save publishes reconstructed row with `runCount = 0`
-> worker now sees ten remaining counted runs rather than the intended two.

The same edit can erase run history and publish false idle/blank runtime status while a worker-owned runtime state existed.

## Root reconciliation

This is the same already-counted P2 `BUG-OBSERVE-02`, not a new root.

It remains distinct from:

- P0 `BUG-OBSERVE-HANDOFF-01`, which owns immutable configuration-generation and stale-worker mutation authority;
- P2 `BUG-OBSERVE-03`, which owns recurring successor scheduler acceptance/debt;
- CLOSED F3 `BUG-OBSERVE-01`, which owns authoritative source snapshots before destructive absence reconciliation.

The roots can intersect during edit-during-run races, but `BUG-OBSERVE-02` is independently reproducible because the ordinary edit writer itself persists reset worker-owned fields.

## Stable correction boundary

A future correction should separate user-editable configuration from worker-owned runtime/progress state. Ordinary edits must preserve at least `runCount`, `runHistory`, `runInProgress`, and `currentRunStatus`, while preserving explicit processed-link reset controls and any explicit lifecycle reset authority as separate intentional operations.

Final publication should also avoid allowing a stale edit snapshot to overwrite newer worker-owned runtime state; a configuration-only DAO update, expected-current revision/CAS, or equivalent transactional merge is required rather than reconstructing and replacing the full row.

INDEPENDENT EXECUTION: NOT EXECUTED