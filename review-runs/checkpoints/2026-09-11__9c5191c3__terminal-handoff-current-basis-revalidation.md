# BUG-TERMINAL-HANDOFF-01 — current-basis carry-forward revalidation

Date: 2026-09-11

## Exact basis
- CLEAN basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `68b39e97491d0013bbbcc2553983db45ebfa9f8a` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 is in progress; no implementation commit/diff newer than `5da8bc3354f6cbafd23d08dbc602530a983be6af` was inspected.

## Verdict
**NOT_CLEAN / existing P2 `BUG-TERMINAL-HANDOFF-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change relation
The cumulative `aa1616a2... -> 9c5191c3...` F3 changes do not modify `TerminalFragment`, `TerminalViewModel`, Terminal initial handoff production code, or the pre-admission ownership boundary. The exact current producer was re-read before carrying the finding forward.

## Exact current source
The production UI still commits the durable Terminal row first and only then requests WorkManager ownership:

`TerminalFragment`
→ `terminalViewModel.insert(TerminalItem(...))`
→ receives durable `downloadID`
→ `terminalViewModel.startTerminalDownloadWorker(TerminalItem(downloadID, command))`.

At `9c5191c3...`, `TerminalViewModel.startTerminalDownloadWorker()` still constructs a one-time `TerminalDownloadWorker` request and calls:

`WorkManager.getInstance(application).beginUniqueWork(item.id.toString(), ExistingWorkPolicy.KEEP, workRequest).enqueue()`

The enqueue `Operation` is not awaited or observed, and no exact durable pre-enqueue handoff/request generation is persisted before producer responsibility ends.

The strong `TerminalExecutionRegistry` / `TerminalExecutionRecovery` generation witness remains created only after the worker starts and reaches execution admission. It therefore cannot cover process death or enqueue failure in the earlier Room-row -> WorkManager gap.

Concrete orphan sequence remains:

1. runnable Terminal row T commits;
2. process dies after commit but before accepted WorkManager ownership, or enqueue fails/rejects;
3. no worker reaches exact Terminal execution admission;
4. no execution witness exists for T;
5. startup execution/publication recovery cannot discover a witness that was never created;
6. T can remain durable/visible without a guaranteed owner to execute or terminalize it.

## Correction boundary carried forward
- establish durable Terminal handoff responsibility before the committed runnable row can be forgotten, or make startup reconcile every runnable Terminal row against exact WorkManager/request identity;
- persist exact request/generation identity and observe enqueue acceptance/failure;
- make process death before enqueue, between enqueue invocation and acceptance observation, and explicit enqueue failure deterministically recoverable;
- recovery/retry must be idempotent by exact Terminal generation and must feed into the existing execution-admission fence;
- cancellation/delete/reconfiguration must revoke stale pending handoff generations;
- production regressions should cover pre-enqueue death, unobserved/failed enqueue, restart repair, duplicate repair attempts, and accepted-handoff -> exact execution admission.

## Root reconciliation
This remains one canonical P2 root. `BUG-CACHE-ROOT-01` remains separate because it owns cache-root identity after/adjoining execution rather than the initial Room -> WorkManager ownership gap.

INDEPENDENT EXECUTION: NOT EXECUTED
