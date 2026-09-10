# Independent Track A checkpoint — scheduler consumer generation follow-up

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

This checkpoint continues the fixed-basis review while a separate implementation wave is in progress. It does not inspect or infer from that in-progress implementation diff.

## BUG-SCHEDULE-01 — S8 confirmed: stale END consumer is not generation-fenced

`WorkManagerHandoffRecovery.buildRequest()` includes exact `handoffId` and `handoffRequestId` in the `CancelScheduledDownloadWorker` input data for `SCHEDULE_END`.

`CancelScheduledDownloadWorker.doWork()` does not read or validate either value. Its first destructive/global side effect is `WorkManager.cancelAllWorkByTag("download")`, followed by cancellation/requeue of currently running Download executions.

`prepareSchedulerBoundary()` replaces the outstanding END generation and `cancelScheduledHandoffs()` can delete/tombstone it, but a prior WorkManager request may already be RUNNING. Consumer-side code therefore has no final current-generation check before the broad cancellation effect.

Concrete stale-generation sequence:

1. END generation E1 is accepted and begins running.
2. scheduler configuration is changed/disabled or a newer END generation E2 supersedes E1;
3. producer-side carrier state now says E1 is stale;
4. E1 reaches `cancelAllWorkByTag("download")` without consulting its handoff/request identity;
5. current Download work can be cancelled under a boundary generation that is no longer authoritative.

Classification: same semantic root `BUG-SCHEDULE-01`; no P2 count increment.

Required invariant:

> Every schedule START/END consumer must revalidate the exact durable schedule generation at the final claim/destructive boundary. Producer-side REPLACE/tombstoning is not sufficient once an old worker has begun executing.

## BUG-SCHEDULE-01 — S9 confirmed: stale START consumer can still claim queue work

`WorkManagerHandoffRecovery.buildRequest()` also puts exact `handoffId` and `handoffRequestId` into the `DownloadWorker` request used for `SCHEDULE_START`.

At the fixed basis, `DownloadWorker` does not consume those input keys. It reaches the normal queue-admission path and invokes `claimDownloadThroughProductionAdmission()`.

That claim boundary correctly validates Download execution/native/history-replacement authority, but it does not validate scheduler handoff generation or the current scheduler preference/window.

Concrete stale-generation sequence:

1. START generation E1 is accepted and starts or is about to claim queue work;
2. schedule configuration changes, scheduling is disabled, or E2 replaces E1;
3. producer-side handoff state no longer authorizes E1;
4. E1 continues through normal Download admission because its input generation is ignored;
5. it can claim a Queued/Scheduled Download and publish a new Download execution token outside the now-authoritative START generation.

This is the START analogue of S8 and remains inside `BUG-SCHEDULE-01`; no count increment.

Acceptance must include both sides:

- START worker validates exact handoff/request generation immediately before each scheduler-authorized queue claim;
- END worker validates exact handoff/request generation immediately before broad WorkManager cancellation and before each execution cancellation/requeue boundary;
- cancellation/disable and replacement publish a durable revocation/current-generation state visible to already-running consumers;
- a stale worker exits without semantic side effects;
- current generation still makes progress after process death/retry.

## History date-fetch handoff sweep — no new blocker root

`HistoryDateFetchRepository.createOrReconnect()` creates a durable nonterminal operation and item ledger before `HistoryDateFetchManager.enqueue()` calls WorkManager. The enqueue call does not observe `Operation.result`, so a same-process asynchronous enqueue failure can temporarily leave a nonterminal operation without a running worker.

However this is materially different from the confirmed Terminal/LocalAdd/Observe missing-owner findings: `HistoryDateFetchManager.reconcile()` enumerates every nonterminal operation and re-enqueues it, and `App.onCreate()` invokes that reconciliation after runtime readiness. The durable operation is therefore startup-discoverable and has an explicit recovery owner.

Current classification: no new P2 handoff root on this evidence. The lack of live same-process enqueue-result convergence is a hardening concern unless a durable fixed point independent of restart is later established.

## HardSub generation candidate — not promoted in this checkpoint

`WorkManagerHandoffRecovery` gives HardSub Scan Now requests an exact generation/request identity, while `HardSubScanWorker` itself does not consume that identity. The worker does perform coroutine/isStopped checks around candidate processing, and further framework/production overlap evidence is required before claiming that a superseded generation can commit a distinct harmful semantic mutation.

Do not promote this candidate without establishing a concrete stale-generation producer -> mutation -> impact path that survives the worker's cancellation checks.

## Count

S8 and S9 are subcases of the already-counted `BUG-SCHEDULE-01`; this checkpoint does not add a canonical blocker root.

The current post-implementation working recount therefore remains `P0 2 / P1 3 / P2 23` pending review of future completed implementation SHAs.

INDEPENDENT EXECUTION: NOT EXECUTED
