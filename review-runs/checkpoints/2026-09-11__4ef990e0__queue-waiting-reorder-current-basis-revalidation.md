# BUG-QUEUE-01 — membership-waiting contextual reorder current-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Prior exact-basis root checkpoint: `5864126eabdb74f5cc719d82ccf8cadd52bbd8e6` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P2 `BUG-METADATA-02` / F13, started from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F22
- Registry classification: existing **P3 / Open**, outside canonical P0/P1/P2 blocker count.

## Overlap guard

Exact compare `aa1616a2... -> 4ef990e0...` contains only F3/F12 source-authority and automatic-keyword production-wiring changes. Queue contextual-selection/reorder UI and DAO ordering files were untouched.

The prior F22 disposition therefore carries forward after narrow exact-source confirmation.

## Verdict

**NOT_CLEAN / existing P3 `BUG-QUEUE-01` remains OPEN at `4ef990e0...`.**

P0/P1/P2 count delta: **0**.

Canonical blocker count remains **P0 2 / P1 2 / P2 25**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Exact current-source confirmation

`QueuedDownloadsFragment.kt@4ef990e0...` still permits contextual selection to resolve both `Queued` and `WaitingForMembership` rows. Its `getSelectedIDs()` includes both statuses for inverted/select-all style selection, while direct checked IDs can also contain membership-waiting rows.

The contextual Up/Down handlers still:

1. resolve `getSelectedIDs()`;
2. clear checked items;
3. call `putAtTopOfQueue(selectedObjects)` or `putAtBottomOfQueue(selectedObjects)`;
4. finish the action mode.

They do not first prove that every resolved selected row is currently reorder-eligible `Queued`, and there is no immediate full-selection status revalidation at the execution boundary.

`DownloadDao@4ef990e0...` still implements `putAtTopOfTheQueue()` / `putAtBottomOfTheQueue()` by loading `getQueuedDownloadsListIDs()`, intersecting the requested IDs with that queued-only current list, and rewriting order for the resulting subset. The underlying queued-ID query selects `status='Queued'` and applies the existing runnable guards; `WaitingForMembership` is not part of that reorder authority set.

Therefore a mixed contextual selection still produces a silent partial action: queued rows move while membership-waiting rows from the same visible selection do not.

Per-item/drag restrictions for membership-waiting rows remain useful positive semantics and should be preserved. Waiting rows also remain legitimately selectable for non-reorder contextual actions such as delete/copy.

## Governing F22 invariant

A visible reorder action must apply to the entire selected set. The Master Plan requires Up/Down to be hidden/disabled when the resolved selection includes waiting rows and requires final revalidation to close status races.

Current source still violates that contextual-action contract.

## Root reconciliation

- This is the already-counted registry P3 `BUG-QUEUE-01`; no new root is introduced.
- It remains outside the P0/P1/P2 blocker count.
- Keep P2 `BUG-DOWNLOAD-HANDOFF-01` / historical queue handoff concerns separate; this root is contextual selection/reorder eligibility, not WorkManager acceptance/recovery.
- F12 `BUG-KEYWORD-01` remains CLOSED at `4ef990e0...`; no shared-domain regression was found.
- Active F13 implementation state is unchanged and was not inspected.

## Required correction boundary carried forward

1. Keep membership-waiting rows selectable for valid non-reorder actions.
2. Hide/disable Up/Down when the fully resolved selection includes any `WaitingForMembership` row.
3. Revalidate the complete resolved selection immediately before reorder execution.
4. Apply this to direct, inverted, select-all, and select-between-derived selections.
5. Preserve queued-only reorder semantics.
6. Add focused coverage for waiting-only, mixed, inverted, select-all, select-between, status-race, and queued-only cases.

INDEPENDENT EXECUTION: NOT EXECUTED