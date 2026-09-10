# Independent correctness checkpoint — ordinary Download WorkManager handoff

- Review basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diffs inspected: NO
- Verdict: NOT_CLEAN
- Canonical working count after this checkpoint: `P0 2 / P1 3 / P2 25`
- Independent execution: NOT EXECUTED

## NEW P2 — BUG-DOWNLOAD-HANDOFF-01

### Semantic root
An ordinary Download can become durably runnable (`Queued` / rescheduled runnable state) before any exact WorkManager acceptance is durable, and no startup owner inventories arbitrary queued rows to repair a lost enqueue.

### Exact production path
- `DownloadViewModel` persists/requeues Download rows and then calls `DownloadRepository.startDownloadWorker(...)`.
- `DownloadRepository.startDownloadWorker()` builds a `DownloadWorker` request and calls `WorkManager.enqueueUniqueWork(...)` directly. It does not await/observe `Operation.result`, persist request identity, or retain a pre-enqueue handoff carrier. The function returns `Result.success(...)` after issuing enqueue.
- `App.onCreate()` invokes `DownloadExecutionRecovery.reconcile()` and `WorkManagerHandoffRecovery.reconcile()`, but ordinary Download queue triggers are not represented by a `WorkManagerHandoffCarrier`.
- `DownloadExecutionRecovery` discovers Active/PostProcessing rows, explicit recovery journals, committed History-replacement debt, and native marker debt; it does not inventory ordinary `Queued` rows as missing-worker obligations.
- `MainActivity` startup likewise does not unconditionally enqueue a generic queue `DownloadWorker`.

### Concrete fixed point
1. User/producer commits a runnable Download row.
2. Process dies or WorkManager enqueue publication fails before the request is accepted.
3. Durable row remains Queued/runnable.
4. On cold start, execution recovery sees no active execution/recovery debt and handoff recovery has no carrier for this ordinary queue request.
5. No exact owner re-enqueues the row; it can remain visibly queued until some unrelated later action happens to trigger another DownloadWorker.

This is a durable intent → missing execution-owner handoff failure, not merely delayed UI refresh.

### Acceptance
- Persist an exact pre-enqueue queue handoff/request identity before treating the queue transition as handed off, and observe WorkManager `Operation.result`; or
- make startup reconciliation inventory all runnable Download rows and deterministically establish/repair the correct queue worker ownership.
- Recovery must distinguish ordinary runnable queue work from execution/finalization debt and remain idempotent across duplicate startup/producer attempts.
- Add process-death and enqueue-failure coverage for first queued item, requeue/resume/retry, and cold-start repair.

## Count bookkeeping
Prior canonical working count: `P0 2 / P1 3 / P2 24`.
`BUG-DOWNLOAD-HANDOFF-01` adds one independent P2 root.
Current canonical working count: `P0 2 / P1 3 / P2 25`.
