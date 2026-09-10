# Independent Track A checkpoint — Terminal + LocalAdd initial handoff authority

Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
Verdict: `NOT_CLEAN`
Independent execution: NOT EXECUTED

This review remains pinned to the fixed contiguous independently-CLEAN basis and does not inspect in-progress implementation work.

## New `BUG-TERMINAL-HANDOFF-01` — CONFIRMED P2

The production Terminal UI first inserts a durable `TerminalItem`, then separately calls `TerminalViewModel.startTerminalDownloadWorker()`. That method builds a one-time request and calls `beginUniqueWork(id, KEEP, request).enqueue()` without awaiting or otherwise observing the enqueue `Operation`.

The exact Terminal execution witness is created only later, inside worker admission through `TerminalExecutionRegistry` / `TerminalExecutionRecovery.begin()`.

Startup recovery does not scan arbitrary active Terminal Room rows. `TerminalExecutionRecovery.reconcile()` enumerates only durable execution witness files, and `WorkManagerHandoffRecovery` has no Terminal handoff kind/carrier.

Concrete fixed point:
1. Terminal row T is committed.
2. Process dies before WorkManager acceptance, or enqueue fails/rejects.
3. No worker enters admission, so no execution witness exists.
4. T remains durable/visible, but neither startup Terminal recovery nor generic WorkManager handoff recovery owns a request to re-enqueue it.

Acceptance:
- establish a durable pre-enqueue Terminal handoff generation before exposing the committed runnable row, or make startup reconcile every runnable Terminal row against exact WorkManager/request identity;
- observe enqueue `Operation.result` and keep/retry the exact semantic generation until accepted or terminally resolved;
- never let handoff retry bypass the later Terminal execution witness/native-generation admission fence;
- cover process death before enqueue, after enqueue call but before acceptance observation, explicit enqueue failure, and restart.

## New `BUG-LOCALADD-HANDOFF-01` — CONFIRMED P2

The production local-add path expands selected URIs, creates a session id, writes `LocalAddStorage.saveEntries(sessionId, entries)`, then builds a `LocalAddWorker` request and calls `WorkManager.enqueue(request)` without observing enqueue acceptance.

`LocalAddStorage` can load a session only when its id is already known. It has no outstanding-entry-session enumeration or request/acceptance state. App startup has no LocalAdd handoff reconciliation owner. History UI re-entry watches existing WorkManager work by tag and shows persisted progress/pending-result state, but does not reconstruct a worker for an entry session whose enqueue was lost.

Concrete fixed point:
1. local-add input session is persisted;
2. enqueue fails or process dies across the handoff;
3. the entries remain stored but no worker owns them;
4. reopening the History UI does not discover and re-enqueue the orphan input session.

Acceptance:
- use a durable local-add handoff row/session state that records exact request identity and enqueue acceptance, with startup/UI reconciliation;
- keep entry input until accepted worker ownership or an explicit user cancellation/terminal failure;
- make retry idempotent by exact local-add session identity;
- cover enqueue rejection, process death across the handoff, duplicate retry, and explicit cancel.

## Working independent recount

`P0 2 / P1 3 / P2 27`

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
Authoritative ledger unchanged.
