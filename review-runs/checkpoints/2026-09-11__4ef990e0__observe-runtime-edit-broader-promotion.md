# BUG-OBSERVE-02 — preserve Observe Source runtime state across ordinary configuration edits

Date: 2026-09-11

## Exact review basis

- Independently CLEAN implementation basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Governing Master Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Broader-registry hypothesis source: `review/remediation:TASKS.md`

This review used only fixed exact source while Luna continued the separate F13 implementation wave. No moving implementation commit/diff newer than `4ef990e0...` was inspected or relied on.

## Verdict

**CONFIRMED / PROMOTED P2 `BUG-OBSERVE-02` at exact `4ef990e0...`.**

- Canonical count: **P0 2 / P1 2 / P2 28 -> P0 2 / P1 2 / P2 29**
- CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Overall project remains `NOT_CLEAN`

## Concrete production path

### 1. Ordinary edit reconstructs worker-owned runtime state

`ObserveSourcesBottomSheetDialog` receives the current `ObserveSourcesItem` for an edit, but Save constructs a fresh `ObserveSourcesItem` rather than expressing configuration-only field ownership.

The reconstructed row explicitly sets:

- `runCount = 0`

and does not pass through the current row's:

- `runHistory`
- `runInProgress`
- `currentRunStatus`

Those omitted constructor fields therefore take their defaults (`[]`, `false`, and `""`).

The dialog conditionally preserves/resets processed/ignored/retry/observed membership according to its own controls, but no ordinary-edit contract authorizes resetting execution count/history/status merely because another configuration field changed.

### 2. ViewModel does not merge current runtime ownership

For an existing source, `ObserveSourcesViewModel.insertUpdate(item)` directly calls `repository.update(item)` and then reschedules observation. It does not reload the current source and merge worker-owned runtime fields before persistence.

### 3. Repository/DAO perform full-row replacement

`ObserveSourcesRepository.update(item)` forwards the reconstructed object to `ObserveSourcesDao.update(item)` for an ACTIVE source.

`ObserveSourcesDao.update(item)` is a Room `@Update(onConflict = REPLACE)` of the full `ObserveSourcesItem` row. There is no partial configuration update, expected-runtime predicate, source revision, or merge that preserves worker-owned columns.

Therefore the dialog defaults become durable source state.

## User-visible semantic impact

`ObserveSourceWorker` increments `runCount` for counted runs and compares it against `endsAfterCount` to determine whether the source should stop. Resetting `runCount` on an unrelated edit changes that termination contract.

Example:

1. source is configured `endsAfterCount = 10`;
2. eight counted runs have completed, so durable `runCount = 8`;
3. user edits only name/cadence/template and presses Save;
4. Save persists reconstructed `runCount = 0`;
5. source may now execute up to ten additional counted runs instead of two.

The same edit also clears user-visible `runHistory`, and an edit during a run can overwrite `runInProgress/currentRunStatus` with idle defaults even though the running worker owns those fields.

## Explicit Start proves reset is a distinct lifecycle action

`ObserveSourcesFragment.onItemStart(...)` explicitly sets:

- `status = ACTIVE`
- `runCount = 0`

before calling `insertUpdate(item)`.

The product therefore already has a dedicated lifecycle action that intentionally owns run-count reset semantics. Ordinary configuration Save is not that action and must not implicitly perform the same reset.

## Required correction boundary

1. Separate editable Observe Source configuration from worker-owned runtime/progress state.
2. For ordinary edit, reload/merge current runtime state in one transaction or expose configuration-only DAO updates that cannot write `runCount`, `runHistory`, `runInProgress`, or `currentRunStatus`.
3. Preserve membership lists except for the explicit documented reset-processed-links operation.
4. Keep explicit Start/reset as the only ordinary UI transition that resets `runCount` unless another dedicated reset action is explicitly defined.
5. Define edit-during-run ordering so configuration commit cannot erase worker-owned runtime state, while a worker from an older configuration also cannot overwrite the newer configuration; the latter generation/revocation problem remains owned by `BUG-OBSERVE-HANDOFF-01`.
6. Add deterministic production-level regressions for edits at nonzero runCount, `endsAfterCount` near its terminal boundary, runHistory preservation, explicit membership reset, explicit Start reset, and edit during an active run.

## Root reconciliation

Count once as a new current P2 root.

- Distinct from P0 `BUG-OBSERVE-HANDOFF-01`: that root owns stale worker generation/revocation and durable WorkManager handoff/replacement authority. `BUG-OBSERVE-02` is the edit writer itself overwriting worker-owned runtime/progress columns even without requiring a stale worker to win later.
- Distinct from P2 `BUG-OBSERVE-SOURCE-IDENTITY-01`: that root owns semantic uniqueness/atomic publication of source rows.
- Distinct from CLOSED F3 `BUG-OBSERVE-01`: F3 owns source-snapshot authority before destructive absence reconciliation.
- The same future correction may choose a shared revision/config-runtime ownership mechanism with `BUG-OBSERVE-HANDOFF-01`, but semantic attribution/counting remains separate because either defect can exist without the other.

INDEPENDENT EXECUTION: NOT EXECUTED