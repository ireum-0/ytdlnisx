# BUG-TERMINAL-HANDOFF-01 — Terminal initial WorkManager handoff revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-TERMINAL-HANDOFF-01`

The exact CLEAN basis still commits a runnable Terminal Room row before establishing an acknowledged/recoverable WorkManager request generation. If the process dies or enqueue fails before the worker reaches its durable execution-admission witness, the committed Terminal task has no deterministic recovery owner.

## Producer still commits the row before enqueue ownership

Exact production UI flow remains:

1. `TerminalFragment` inserts a durable `TerminalItem` through `terminalViewModel.insert(...)`;
2. only after the insert returns an ID does it call `startTerminalDownloadWorker(TerminalItem(downloadID, command))`.

Exact `TerminalViewModel.startTerminalDownloadWorker()` then builds a one-time `TerminalDownloadWorker` request and calls:

`WorkManager.getInstance(application).beginUniqueWork(item.id.toString(), ExistingWorkPolicy.KEEP, workRequest).enqueue()`

The enqueue `Operation` is not awaited or otherwise observed, and no exact pre-enqueue request/generation carrier is persisted.

A stable unique-work name is useful for duplicate suppression after WorkManager owns a request, but it is not proof that the enqueue was accepted.

## Exact Terminal execution witness is created only inside the worker

`TerminalDownloadWorker.doWorkInternal()` first verifies the Terminal row still exists, generates a fresh execution token/process identity, and only then calls `TerminalExecutionRegistry.admit(...)`.

That admission is the path that establishes `TerminalExecutionRecovery` ownership before native execution. This is strong once a worker actually reaches admission, but it occurs strictly after the unacknowledged Room-row -> WorkManager handoff.

Therefore a process death/enqueue failure before worker entry creates no Terminal execution witness.

## Startup recovery cannot discover the pre-admission orphan

`App.onCreate()` runs `TerminalExecutionRecovery.reconcile()` and `TerminalPublicationRecovery.reconcile()`.

`TerminalExecutionRecovery.reconcile()` discovers only files in the `terminal-execution-recovery` witness namespace and reconciles those exact records. It does not enumerate arbitrary active/runnable `TerminalItem` Room rows and compare them against WorkManager ownership.

If the original worker never reaches `TerminalExecutionRegistry.admit()`, there is no execution-witness file for this startup reconciler to discover.

`WorkManagerHandoffRecovery` also does not fill this gap at the reviewed basis. Its durable carrier producers cover HardSub, scheduler START/END, and Observe retry decisions; no Terminal handoff kind/producer is established before the UI forgets responsibility for the enqueue.

Concrete fixed point remains:

1. Terminal row T is committed;
2. process dies after commit but before accepted WorkManager ownership, or enqueue fails/rejects;
3. no Terminal worker reaches admission, so no `TerminalExecutionRecovery` witness exists;
4. startup execution/publication recovery sees no exact Terminal witness for T;
5. generic handoff recovery has no Terminal carrier;
6. T remains durable/visible with no owner guaranteed to execute or terminalize it.

## Later execution recovery does not compensate for missing initial handoff

The current worker/execution stack correctly contains substantial exact-generation protection after admission: row-existence revalidation, `TerminalExecutionRegistry.admit`, execution witness/native-generation binding, quiescence convergence, publication recovery, and terminal tombstones.

Those protections must be preserved. They cannot close the producer gap because none exists until the worker itself has started.

## Required correction boundary carried forward

1. Establish durable Terminal handoff responsibility before the committed runnable task can be forgotten by its producer, or make startup enumerate every runnable Terminal row and reconcile it against exact WorkManager/request identity.
2. Persist an exact request/generation identity and observe WorkManager `Operation.result`; producer responsibility may end only after accepted ownership or an explicit terminal/revoked result.
3. Process death between Room commit and enqueue, after enqueue invocation but before acceptance observation, and explicit enqueue failure must all converge deterministically after restart.
4. Handoff retry must be idempotent by exact Terminal semantic generation and must not create multiple native generations.
5. The new handoff layer must feed into, not bypass, the existing `TerminalExecutionRegistry` / `TerminalExecutionRecovery` admission/native-generation fence.
6. Cancellation/delete/reconfiguration must revoke stale pending handoff generations so an old recovered request cannot resurrect a retired Terminal task.
7. Add production-level coverage for pre-enqueue death, unobserved enqueue acceptance, explicit enqueue failure, restart repair, duplicate repair attempts, and transition from accepted handoff into exact execution admission.

## Root/count and basis reconciliation

- `BUG-TERMINAL-HANDOFF-01` was already counted as one P2 root.
- This exact-basis review reconfirms it; no new root is introduced.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- `BUG-CACHE-ROOT-01` remains separate: it owns mutable cache-root generation identity after/adjoining execution, while this root owns the initial Room -> WorkManager ownership handoff.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is unchanged.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
