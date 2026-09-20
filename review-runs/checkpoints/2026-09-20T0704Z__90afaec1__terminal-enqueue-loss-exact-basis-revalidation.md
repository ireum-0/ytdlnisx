# BUG-TERMINAL-05 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Existing canonical root: P2 `BUG-TERMINAL-05`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-TERMINAL-05` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A committed durable Terminal intent must either own an accepted execution carrier or leave an exact durable recovery owner that can establish one after enqueue failure/process death.

WorkManager enqueue request issuance is not acceptance, and process-local coroutine lifetime cannot be the sole bridge from durable Room intent to execution.

## Exact production evidence at 90afaec1

### 1. Terminal intent is persisted before dispatch

`TerminalFragment` normalizes the command and awaits:

`terminalViewModel.insert(TerminalItem(...))`

which durably inserts the Terminal row and returns its generated numeric ID.

Only after that Room insert completes does the fragment call:

`terminalViewModel.startTerminalDownloadWorker(TerminalItem(downloadID, command))`.

### 2. Dispatch is detached and enqueue completion is discarded

`TerminalViewModel.startTerminalDownloadWorker()` immediately starts a separate `viewModelScope.launch(Dispatchers.IO)`.

Inside that process-local coroutine it builds the worker and calls:

`beginUniqueWork(item.id.toString(), KEEP, workRequest).enqueue()`.

The returned WorkManager `Operation` is not awaited/observed.

Therefore both loss windows remain:

1. lifecycle/process death after durable Terminal row insert but before the detached coroutine reaches enqueue;
2. asynchronous enqueue failure after the request call while the persisted row remains.

### 3. Persisted rows are presented as active without carrier state

`TerminalDao` treats every `terminalDownloads` row as active:

- `getActiveTerminalDownloads()` selects all rows;
- `getActiveTerminalDownloadsFlow()` selects all rows;
- `getActiveTerminalsCount()` counts all rows.

There is no durable dispatch-state/request-identity field distinguishing an orphan persisted request from an accepted/running worker.

### 4. Startup recovery does not own ordinary Terminal dispatch

`App.onCreate()` runs Terminal execution/publication recovery and generic `WorkManagerHandoffRecovery`.

Those paths do not close this pre-execution handoff:

- Terminal execution recovery starts after execution admission/native authority exists;
- Terminal publication recovery starts after output/publication state exists;
- `WorkManagerHandoffRecovery` currently owns hard-sub, scheduler-boundary and Observe retry handoff kinds, not ordinary Terminal rows.

No startup path was found that enumerates every persisted runnable Terminal row, proves a corresponding nonterminal WorkRequest exists, and creates exactly one missing carrier.

### 5. Intervening range did not alter this path

Exact compare `9edd3e23... -> 90afaec1...` does not modify `TerminalViewModel.kt`, `TerminalFragment.kt`, `TerminalDao.kt`, `TerminalExecutionRecovery`, `WorkManagerHandoffRecovery`, or `App.kt` in a way that could close the root.

Exact final source therefore preserves the promoted production sequence unchanged.

## Concrete impact

Terminal row commits
→ process/lifecycle dies or enqueue Operation fails before acceptance
→ Room row survives
→ no worker carrier exists
→ startup has no ordinary Terminal dispatch reconciliation
→ UI continues to show/count the row as active
→ command remains durably stranded until manual intervention.

This is a durable intent-to-carrier liveness failure, not transient UI latency.

## Root reconciliation

- Keep `BUG-TERMINAL-05` counted once as P2.
- Count delta: `0`.
- Keep separate from Terminal execution-generation, publication, and SAF destination roots.
- Do not alias with ordinary Download queue handoff roots; they own a different durable domain.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. establish durable dispatch generation/request identity before or atomically with scheduling;
2. consume WorkManager enqueue completion/failure;
3. remove detached ViewModel coroutine lifetime as the sole handoff owner;
4. retain failed/unaccepted dispatch as durable retryable responsibility;
5. reconcile persisted runnable Terminal intents at startup;
6. prove exactly-one carrier on repeated restart/reconcile;
7. prevent cancelled/deleted Terminal intent from resurrection by delayed enqueue/recovery;
8. preserve existing downstream Terminal execution/publication recovery after worker admission.

## Verification

- Exact-source producer/carrier/restart trace: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
