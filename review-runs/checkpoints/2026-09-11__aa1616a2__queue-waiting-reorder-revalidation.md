# BUG-QUEUE-01 — membership-waiting contextual reorder revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna `BUG-OBSERVE-01` implementation wave is active.
- The active Luna implementation branch HEAD, commits, and diffs were not inspected, compared, reviewed, or relied upon for this decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Broader registry state: `TASKS.md` classifies `BUG-QUEUE-01` as **P3 / Open**.

## Verdict

**NOT_CLEAN / EXISTING P3 ROOT RECONFIRMED**

Defect: `BUG-QUEUE-01` / F22

The exact CLEAN basis still permits contextual multi-selection to resolve both `Queued` and `WaitingForMembership` rows while Up/Down reorder silently applies only to the queued subset. The per-item and drag paths already suppress reorder for membership-waiting rows, so the remaining current defect is specifically contextual-selection authority and action visibility/revalidation.

This P3 root is intentionally outside the canonical P0/P1/P2 blocker count.

## Exact-source evidence

### 1. Contextual selection can resolve membership-waiting rows

`app/src/main/java/com/ireum/ytdl/ui/downloads/QueuedDownloadsFragment.kt` defines `getSelectedIDs()` so inverted/select-all style selection resolves IDs through:

- `DownloadRepository.Status.Queued`;
- `DownloadRepository.Status.WaitingForMembership`.

Direct checked IDs can likewise contain a waiting row because waiting cards remain selectable for other contextual operations.

The F22 invariant is therefore relevant to direct, inverted, select-all, and mixed selection states.

### 2. Up/Down does not reject or revalidate the resolved selection

The contextual `R.id.up` and `R.id.down` handlers:

1. call `getSelectedIDs()`;
2. clear checked items;
3. call `downloadViewModel.putAtTopOfQueue(selectedObjects)` or `putAtBottomOfQueue(selectedObjects)`.

They do not verify that every resolved selected row is currently `Queued`, do not hide/disable the action for a waiting-containing selection, and do not revalidate immediately before execution.

### 3. DAO reorder intentionally contains only `Queued` rows

`DownloadViewModel.putAtTopOfQueue()` / `putAtBottomOfQueue()` delegate to `DownloadDao.putAtTopOfTheQueue()` / `putAtBottomOfTheQueue()`.

Those transactional DAO helpers build `current` from `getQueuedDownloadsListIDs()`. The exact query for that list selects only rows with:

`status='Queued'`

The helpers then intersect the requested IDs with that queued-only list before rewriting order.

Therefore when the contextual selection contains both queued and membership-waiting rows, the action succeeds for only the queued subset and silently omits the waiting portion of the visible selection.

### 4. Per-item and drag behavior already contain the intended restriction

`QueuedDownloadAdapter` identifies `WaitingForMembership` rows and:

- hides their drag handle;
- refuses to start drag for them;
- hides per-item Move Top / Move Bottom menu actions.

`QueuedDownloadsFragment` drag movement additionally rejects moves when either source or target is a waiting row.

These are positive existing semantics. F22 does not require making waiting rows unselectable for delete/copy; it requires contextual reorder actions to obey the same eligibility contract.

## Governing correction boundary

The Master Plan F22 contract remains applicable:

1. Keep waiting rows selectable for valid contextual operations such as delete/copy.
2. Hide or disable Up/Down when the fully resolved selection includes any `WaitingForMembership` row.
3. Revalidate the complete resolved selection immediately before reorder execution so a row that changes to waiting after menu presentation cannot produce partial action.
4. Apply the same rule to direct selection, inverted selection, select-all, and select-between-derived selection.
5. Preserve queued-only reorder behavior and order semantics.
6. Do not modify membership retry ownership merely to solve this UI action contract.
7. Add focused tests for direct waiting, mixed queued+waiting, inverted, select-all, select-between, status change between selection and execution, and valid queued-only reorder.

## Registry/count reconciliation

- `TASKS.md` already records `BUG-QUEUE-01` as **P3 / Open**.
- This is a revalidation of that existing root, not a new finding.
- P0/P1/P2 blocker-count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- Contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- `BUG-QUEUE-03` / `BUG-DOWNLOAD-HANDOFF-01` remains a different scheduler-acceptance/recovery family and is not merged with this UI reorder defect.
- The separate active Luna `BUG-OBSERVE-01` implementation was not inspected or relied upon.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
