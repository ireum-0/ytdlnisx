# BUG-TERMINAL-05 — persisted Terminal intent can lose its WorkManager carrier

Date: 2026-09-12

## Exact review basis

- Independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Historical broader-registry root: P2 `BUG-TERMINAL-05`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active overnight candidate task while reviewed: task 009 `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`
- Moving task-009 diff inspected or relied on: **NO**

## Verdict

**CONFIRMED / PROMOTED P2 `BUG-TERMINAL-05` at exact CLEAN basis `9edd3e23...`.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 1 / P2 32**.

CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

Overall remains `NOT_CLEAN`.

## Current exact production chain

### 1. Durable Terminal intent commits before carrier establishment

The normal Terminal Run path awaits `TerminalViewModel.insert(...)`, which persists a `TerminalItem` in Room and returns its generated numeric task id. Only after that durable insert does the UI call `startTerminalDownloadWorker(...)`.

The row is therefore already durable before WorkManager acceptance is established.

### 2. Scheduling is detached and enqueue acceptance is discarded

At the exact reviewed source, `TerminalViewModel.startTerminalDownloadWorker(item)` immediately launches a second `viewModelScope.launch(Dispatchers.IO)`.

Inside that detached coroutine it builds a one-time `TerminalDownloadWorker`, then calls:

`WorkManager.getInstance(application).beginUniqueWork(item.id.toString(), KEEP, workRequest).enqueue()`

The returned WorkManager `Operation` is not awaited or otherwise observed.

Concrete loss windows therefore remain:

1. process/ViewModel death after the Room insert and before the detached coroutine establishes the WorkManager record;
2. asynchronous WorkManager enqueue failure reported only through the discarded `Operation`.

Neither case revokes or resolves the already-persisted Terminal row.

### 3. Orphan rows remain represented as active durable work

`TerminalDao` treats every row in `terminalDownloads` as active:

- `getActiveTerminalDownloads()` selects every row;
- `getActiveTerminalDownloadsFlow()` selects every row;
- `getActiveTerminalsCount()` counts every row.

There is no carrier-state column or durable handoff state distinguishing `intent persisted but enqueue unaccepted` from a genuinely executing/scheduled Terminal task.

Thus an orphan row can remain permanently visible/countable as active after its worker carrier was never established.

### 4. Existing startup recovery does not own ordinary Terminal enqueue handoff

Current startup recovery contains Terminal execution/publication recovery for already-admitted executions and exact publication remnants, plus the generic `WorkManagerHandoffRecovery` for its supported handoff carrier kinds.

The normal Terminal Run path does not create such a durable handoff carrier before scheduling. `TerminalViewModel.startTerminalDownloadWorker()` directly builds/enqueues the worker from the Room row and does not register ordinary Terminal dispatch with `WorkManagerHandoffRecovery` or an equivalent outbox.

Therefore process restart has no durable record pairing each runnable `terminalDownloads` row with the exact intended WorkRequest identity and no startup reconciliation that enumerates orphan Terminal rows and establishes exactly one missing carrier.

### 5. Existing Terminal execution recovery is downstream, not a closure

`TerminalExecutionRecovery` and `TerminalExecutionRegistry` govern native execution ownership after a Terminal execution has been admitted. They cannot recover a request that died before any worker reached the execution-admission boundary.

Likewise Terminal publication recovery owns already-produced/staged output state, not pre-execution enqueue acceptance.

The root therefore remains distinct from execution-generation, output-publication, and SAF destination authority defects.

## Concrete incorrect impact

A normal supported sequence can be:

`Terminal row durably inserted`
→ process dies / async enqueue fails before WorkManager acceptance
→ row survives in `terminalDownloads`
→ no Terminal worker exists for that request
→ normal startup does not reconstruct the missing ordinary Terminal carrier
→ UI continues to show/count the request as active indefinitely
→ user must manually cancel/delete and recreate the command.

This is a durable intent-to-carrier liveness failure, not merely a transient UI issue.

## Root reconciliation

Count once as existing broader-registry P2 `BUG-TERMINAL-05`.

Keep distinct from:

- `BUG-TERMINAL-HANDOFF-01` only if that canonical root is later proven to own a different already-admitted/retry decision boundary; if future reconciliation establishes identical durable producer/carrier semantics, merge rather than double-count. At this checkpoint the current canonical inventory does not contain `BUG-TERMINAL-05` under another confirmed alias in `NEXT_CHAT.md`.
- promoted `BUG-TERMINAL-03`, which owns SAF/provider output authority;
- historical/revalidated `BUG-TERMINAL-04`, which owns partial publication and is currently CLOSED at this CLEAN basis;
- ordinary Download queue enqueue-loss roots, which operate on a separate durable domain;
- Terminal execution/publication recovery, which begins after execution/output authority has already been established.

## Required correction boundary

A coherent repair must:

1. make the persisted Terminal request itself recoverably tied to an exact dispatch generation/request identity before or atomically with scheduling;
2. observe WorkManager enqueue acceptance/failure instead of discarding `Operation`;
3. avoid a detached `viewModelScope.launch` as the only bridge between a committed Room row and its execution carrier;
4. retain failed/unaccepted dispatch as durable retryable intent rather than silently presenting it as active-with-carrier;
5. reconcile persisted runnable Terminal intents at process startup and establish a missing carrier when no accepted/live carrier for the same generation exists;
6. prove exactly-one dispatch: startup reconciliation must not enqueue a duplicate when the intended WorkRequest is already ENQUEUED/RUNNING/BLOCKED;
7. preserve user cancellation/deletion semantics so a cancelled/revoked Terminal intent cannot be resurrected by delayed enqueue/recovery;
8. preserve current Terminal execution-generation and publication-recovery contracts after a worker is admitted.

Required deterministic coverage should include:

- Room insert commits -> process/delegate dies before enqueue call -> cold-start reconciliation schedules exactly one worker;
- enqueue `Operation` fails asynchronously -> durable intent remains recoverable and later converges;
- valid already-enqueued worker -> startup reconciliation creates no duplicate;
- cancellation/deletion wins before delayed recovery -> no resurrection;
- recovery restart repeats idempotently -> exactly one current carrier;
- execution-admitted control -> existing Terminal execution recovery remains authoritative and is not duplicated by pre-execution handoff recovery.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
