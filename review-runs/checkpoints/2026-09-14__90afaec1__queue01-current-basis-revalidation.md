# F22 / BUG-QUEUE-01 — exact CLEAN-basis revalidation

Date: 2026-09-14

## Exact review state

- Exact independently CLEAN basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F22 `BUG-QUEUE-01`.
- Master Plan severity/mode: P3 / `DIRECT_LUNA_IMPLEMENTATION`.
- Active implementation wave remains F10 review fix + F18 + F20 from exact start `f6e7cf72e00c013ee9b770bf748cba7f38848256`; no post-start implementation diff was inspected or used.

## Verdict

**OPEN / CONFIRMED P3 — existing `BUG-QUEUE-01` remains valid at exact CLEAN basis `90afaec1...`.**

- P0/P1/P2 canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 21**.
- Overall remains `NOT_CLEAN` because higher-severity open blockers remain.
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Governing invariant

A visible reorder action must apply to the entire selected set. Waiting-for-membership rows may remain selectable for actions such as delete/copy, but a queue Up/Down action must not appear to accept a mixed selection and then silently reorder only the Queued subset.

## Exact current production evidence

### 1. WaitingForMembership rows are part of the selectable queue surface

`QueuedDownloadsFragment` observes and counts both `Queued` and `WaitingForMembership` rows.

`QueuedDownloadAdapter` deliberately prevents direct drag reordering and hides per-row move-top/move-bottom actions for `WaitingForMembership`, but it does **not** prohibit contextual selection of those rows. Long-click and multi-selection treat waiting rows like other rows.

This is valid for non-reorder actions and therefore does not itself violate the invariant.

### 2. Contextual Up/Down remains visible without selection-status gating

The contextual action mode inflates the queue selection menu in `onCreateActionMode()` and `onPrepareActionMode()` returns false without status-dependent visibility/enablement logic.

The `up` and `down` handlers resolve the selected IDs, clear selection, and call `downloadViewModel.putAtTopOfQueue(selectedObjects)` / `putAtBottomOfQueue(selectedObjects)` without first proving every selected row is currently `Queued`.

### 3. Selection expansion can explicitly include WaitingForMembership rows

`getSelectedIDs()` returns direct checked IDs unchanged for ordinary direct selection.

For inverted/select-all selection it queries IDs with both statuses:

- `Queued`
- `WaitingForMembership`

`select_between` obtains Queued middle IDs but then adds the currently selected endpoint IDs, so a waiting endpoint can remain in the selected set.

Thus direct, inverted, select-all, and select-between selection shapes can all resolve to a set containing at least one waiting row.

### 4. DAO reorder authority silently filters to Queued only

`DownloadViewModel.putAtTopOfQueue()` and `putAtBottomOfQueue()` directly delegate to `DownloadDao.putAtTopOfTheQueue()` / `putAtBottomOfTheQueue()`.

Those transactional DAO methods build their mutable ordering from `getQueuedDownloadsListIDs()`, whose query selects only rows with `status='Queued'`.

They then intersect the caller's selected IDs with that Queued-only list before rewriting order.

Therefore a mixed selected set such as `{Queued A, Waiting B}` is accepted by the UI action, but only A participates in the durable reorder. B is silently ignored.

### 5. Async status race remains

For direct selection, checked IDs are not revalidated against current status immediately before execution. A row selected while Queued may become `WaitingForMembership` before the user presses Up/Down.

The same DAO filtering then silently drops that now-waiting row rather than rejecting/disableing the reorder action as a whole.

## Concrete impact

A user can select multiple visible rows and invoke Up or Down while one or more selected rows are `WaitingForMembership`. The application presents the action as applying to the selected set, but the DAO changes only the Queued subset. The visible semantic action is therefore partially applied without disclosure.

This is exactly the existing F22 root, not a new blocker.

## Stable correction boundary

The Master Plan correction remains sufficient:

- hide or disable Up/Down whenever the resolved selected set contains any `WaitingForMembership` row;
- revalidate immediately before executing Up/Down so a status transition after menu preparation cannot create partial application;
- apply the same rule to direct, inverted, select-all, and select-between selections;
- preserve waiting-row selection for delete/copy and other valid contextual actions;
- preserve existing queued-only drag/per-row move behavior.

Focused tests should cover direct waiting selection, mixed selection, inverted/select-all/select-between selection, an async Queued -> WaitingForMembership race before execution, and queued-only reorder success.

## Review disposition

`BUG-QUEUE-01` remains an existing P3 hardening/correctness item. It does not change the current P0/P1/P2 blocker total.

INDEPENDENT EXECUTION: NOT EXECUTED