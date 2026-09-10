# Independent Track A checkpoint — general Download queue handoff authority

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

This checkpoint reviews only the fixed independently CLEAN basis while a separate implementation wave is in progress.

## New P2 — `BUG-DOWNLOAD-HANDOFF-01`

### Invariant

> A durable runnable Download intent (`Queued` or due `Scheduled`) must have exactly one recoverable execution trigger. Committing the Room state and merely calling asynchronous WorkManager enqueue are not one atomic acceptance boundary. Every surviving runnable row must be discoverable by a startup/live reconciliation owner until a WorkManager request is positively accepted or the row is revoked/terminalized.

### Production producer ordering

`DownloadViewModel.queueDownloads()` builds the candidate set and `persistQueuedItems()` commits the resulting Download rows before calling `DownloadRepository.startDownloadWorker(...)`.

The same ordering appears in other production transitions that turn a durable row runnable and then trigger the worker, including requeue, resume, retry, schedule-reset, and reschedule paths.

### WorkManager acceptance gap

`DownloadRepository.startDownloadWorker(...)` builds one-time `DownloadWorker` requests and calls `WorkManager.enqueueUniqueWork(...)` directly.

For immediate work it uses a request-specific unique name and `REPLACE`; for non-alarm future schedule groups it enqueues a one-time request keyed by start time.

The returned `Operation` is not awaited or inspected. The method subsequently returns `Result.success(...)` to its caller. Therefore the app-side semantic producer treats the enqueue call as successful publication without a positive WorkManager acceptance result.

### Missing recovery owner

The fixed-basis startup graph does not reconcile arbitrary runnable Download rows:

- `DownloadExecutionRecovery.reconcile()` owns abandoned Active/PostProcessing and exact execution/finalization debt;
- `WorkManagerHandoffRecovery.reconcile()` owns only its explicit carrier kinds (hard-sub scan, scheduler START/END, Observe retry);
- Terminal, low-quality, automatic-keyword, and History date-fetch have their own startup owners;
- no `App.onCreate()` path enumerates ordinary `Queued` / due `Scheduled` Download rows and ensures an exact `DownloadWorker` request exists;
- `DownloadViewModel` initialization and `DownloadQueueMainFragment` view creation observe queue state for UI but do not perform queue-worker reconciliation.

`DownloadWorker` itself can observe/claim queued rows only after a worker instance already exists, so it cannot recover the missing carrier that would start it.

### Concrete fixed points

#### Q1 — immediate Queued row orphan

1. producer commits Download row `Q` as `Queued`;
2. `startDownloadWorker()` calls asynchronous `enqueueUniqueWork()`;
3. enqueue fails asynchronously or process death occurs before WorkManager acceptance is durable;
4. `Q` survives in Room;
5. no DownloadWorker request owns it;
6. process restart does not reconstruct a request from `Q`;
7. `Q` can remain indefinitely runnable-but-unowned until some unrelated later UI/action happens to call `startDownloadWorker()`.

This is a durable liveness/correctness failure, not merely a transient scheduling delay.

#### Q2 — non-alarm Scheduled group orphan

The same acceptance gap exists when `startDownloadWorker()` publishes future schedule-group `DownloadWorker` requests directly through WorkManager. A durable Scheduled row/group can survive while its one-time future carrier never became accepted, and startup has no row→request reconciliation owner.

Alarm-backed scheduling remains under the separate `BUG-SCHEDULE-01` root. Q2 is specifically the general Download handoff contract when `startDownloadWorker()` owns the WorkManager publication.

### Scope / non-duplication

This is distinct from:

- `BUG-SCHEDULE-01`: scheduler window/alarm/generation ownership;
- `BUG-OBSERVE-HANDOFF-01`: recurring Observe source intent and successor ownership;
- `BUG-KEYWORD-HANDOFF-01`: automatic-keyword sync intent;
- `BUG-TERMINAL-HANDOFF-01`: Terminal row→Terminal worker handoff;
- `BUG-LOCALADD-HANDOFF-01`: LocalAdd session/input→worker handoff.

The durable state and consumer are different: this root is the application's ordinary Download queue itself.

### Acceptance direction

A correction should provide one authoritative queue-trigger reconciliation protocol, for example:

- durable queue handoff/request identity coupled to the runnable transition, or an equivalently strong row-derived reconciliation model;
- observe `Operation.result` for the exact WorkManager request;
- startup reconciliation enumerates runnable Queued/due Scheduled rows and confirms/reconstructs their exact trigger;
- enqueue failure retains retry ownership instead of reporting success and forgetting the obligation;
- multiple queue producers converge on one protocol rather than each issuing unchecked WorkManager calls;
- stale triggers cannot claim rows after those rows were revoked, rescheduled, terminalized, or superseded;
- normal queue concurrency and exact Download execution/native fences remain intact.

## Count

Previous post-implementation working count: `P0 2 / P1 3 / P2 23`.

Add `BUG-DOWNLOAD-HANDOFF-01` as one distinct semantic root.

New working count: `P0 2 / P1 3 / P2 24`.

INDEPENDENT EXECUTION: NOT EXECUTED
