# BUG-TERMINAL-05 — exact current-basis revalidation

Date: 2026-09-25 +09:00

Exact independently CLEAN implementation basis reviewed:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

Governing Master Plan:
fada33a7eed86b1fa2c07065af66f14bf4d24714

Governing checklist:
REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7

Canonical existing root:
P2 BUG-TERMINAL-05

Prior exact-basis revalidation:
review-runs/checkpoints/2026-09-20T0704Z__90afaec1__terminal-enqueue-loss-exact-basis-revalidation.md

## Verdict

OPEN / CONFIRMED / NOT_CLEAN.

The root remains present at exact CLEAN basis
8d4624e810c4a7d51ed625a0dc06302b0f8e1819.

Canonical blocker-count delta:
0

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

CLEAN_REVIEW_BASIS remains:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

## Current exact-source evidence

### Durable Terminal intent still commits before dispatch

TerminalFragment still:
1. normalizes the command;
2. awaits terminalViewModel.insert(TerminalItem(...));
3. receives the generated durable numeric id;
4. only then calls startTerminalDownloadWorker(...).

A committed Terminal row therefore still exists before WorkManager dispatch
responsibility is durably established.

### Dispatch is still process-local and acceptance is unobserved

TerminalViewModel.startTerminalDownloadWorker(...) still starts a detached
viewModelScope.launch(Dispatchers.IO).

Inside that coroutine it:
- builds TerminalDownloadWorker;
- calls beginUniqueWork(id.toString(), KEEP, request).enqueue().

The returned WorkManager Operation is not observed.

Therefore both original loss windows remain:
- lifecycle/process death after the Room insert but before the detached
  coroutine reaches enqueue;
- asynchronous enqueue failure after request issuance.

### Generic WorkManager handoff changes did not close Terminal dispatch

The current generic handoff carrier now includes:
- HARD_SUB_SCAN;
- schedule boundaries;
- Observe retry;
- Observe recurrence;
- AUTOMATIC_KEYWORD_SYNC.

There is still no Terminal dispatch kind.

App startup invokes WorkManagerHandoffRecovery.reconcile(), but no ordinary
Terminal carrier exists for it to recover.

The automatic-keyword extension therefore does not incidentally repair this
root.

### Terminal rows still expose no dispatch ownership

TerminalDao continues to treat every row in terminalDownloads as active:
- getActiveTerminalDownloads();
- getActiveTerminalDownloadsFlow();
- getActiveTerminalsCount().

There is no dispatch state/request identity in TerminalItem and no separate
durable carrier linking a row to an accepted exact WorkRequest.

### Downstream worker recovery remains separate

TerminalDownloadWorker now has substantial execution/publication recovery and
correctly refuses to execute when the Terminal row has already converged.

Those protections begin after a WorkRequest reaches worker admission.

They do not recover a durable Terminal row for which no WorkRequest was ever
accepted.

## Concrete current failure

1. Terminal row T commits.
2. Process/lifecycle dies before detached enqueue runs, or enqueue Operation
   later fails.
3. T remains in terminalDownloads.
4. No accepted WorkRequest is proven.
5. No exact durable dispatch owner remains.
6. startup Terminal execution/publication recovery has nothing to own because
   worker admission never occurred.
7. UI continues to present/count T as active.
8. T is durably stranded.

## Stable correction boundary

A correction should:

1. stage exact durable Terminal dispatch responsibility together with the
   durable Terminal intent, or otherwise before process-local publication
   responsibility can be lost;
2. observe Operation.result before dispatch is considered accepted;
3. retry/recover exact pending responsibility after enqueue failure/process
   restart;
4. bind the request to exact Terminal row identity and command semantics;
5. make worker entry require the current exact dispatch owner so a stale old
   request cannot execute a deleted/replaced intent;
6. retain accepted-owner identity across transient missing WorkInfo;
7. make cancel/delete durably supersede dispatch responsibility before or with
   asynchronous WorkManager cancellation;
8. ensure delayed acceptance of a superseded request cannot resurrect the
   Terminal row or command;
9. preserve existing downstream Terminal execution/publication recovery after
   admission;
10. reconcile startup exactly once without duplicate semantic successors;
11. add deterministic production-wiring coverage for:
   - durable row/carrier before Operation acceptance;
   - async enqueue failure;
   - process restart recovery;
   - accepted owner + transient missing WorkInfo;
   - cancel/delete supersession;
   - delayed stale acceptance after cancellation;
   - stale/unbound worker request refusal;
   - exactly-one current request after repeated reconcile.

Prefer reusing the existing generic work_manager_handoff_carriers table with a
new Terminal dispatch kind if it can represent the full contract without a
schema change.

## Verification

Exact-source current-basis revalidation completed at
8d4624e810c4a7d51ed625a0dc06302b0f8e1819.

Independent execution was not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
