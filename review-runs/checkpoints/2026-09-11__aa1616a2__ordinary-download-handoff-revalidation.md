# BUG-DOWNLOAD-HANDOFF-01 — ordinary Download WorkManager handoff revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-DOWNLOAD-HANDOFF-01`

The exact CLEAN basis still allows an ordinary Download row to become durably runnable before any exact WorkManager request acceptance is durable or recoverable. A process death or enqueue failure in that gap can leave a `Queued`/otherwise runnable Download without a deterministic execution owner until some unrelated later producer happens to kick the queue.

## Ordinary queue trigger still treats enqueue invocation as handoff

Exact `DownloadRepository.startDownloadWorker()@aa1616a2...` builds a `DownloadWorker` request and directly calls `WorkManager.enqueueUniqueWork(...)`.

For ordinary immediate triggers, the current code intentionally gives each request a unique work name derived from the request UUID and uses `ExistingWorkPolicy.REPLACE`. For scheduled-batch triggers it likewise calls `enqueueUniqueWork(...)` with a scheduled unique-work name.

Neither branch observes the returned WorkManager `Operation.result`, persists the request UUID/generation as a durable pre-enqueue handoff carrier, or retains producer responsibility until exact acceptance is established.

The method therefore proves only that enqueue was invoked, not that WorkManager durably owns the runnable queue transition.

## Current generic handoff carrier does not cover ordinary Download queue ownership

Exact `WorkManagerHandoffCarrier@aa1616a2...` defines these durable one-shot handoff kinds only:

- `HARD_SUB_SCAN`;
- `SCHEDULE_START`;
- `SCHEDULE_END`;
- `OBSERVE_RETRY_DOWNLOAD`.

There is no ordinary Download-queue handoff kind/request carrier. `WorkManagerHandoffRecovery` therefore cannot reconstruct a lost ordinary `startDownloadWorker()` enqueue by semantic Download queue generation.

## Download execution recovery does not inventory arbitrary Queued rows as missing-worker debt

Exact `DownloadExecutionRecovery@aa1616a2...` is strong for already-established execution/finalization debt. Its startup discovery includes:

- Active/PostProcessing rows;
- execution recovery journals;
- committed History-replacement/finalization debt;
- producer/native-marker/primary-success recovery state.

Its exact current discovery path reads `getActiveAndPostProcessingDownloadsList()` rather than inventorying all ordinary `Queued` rows and proving each has an accepted WorkManager owner.

Thus an ordinary row that never reached execution admission has no execution ID/native generation/journal for this recovery owner to discover.

## App/MainActivity startup does not provide an unconditional queue kick

Exact `App.onCreate()@aa1616a2...` invokes `DownloadExecutionRecovery.reconcile()` and `WorkManagerHandoffRecovery.reconcile()`, but it does not unconditionally call `DownloadRepository.startDownloadWorker()` for queued rows.

Exact `MainActivity.onCreate()@aa1616a2...` likewise contains no unconditional queue-worker establishment. Existing queue kicks occur from specific producers/actions (manual queueing, Observe, HardSub, settings/schedule transitions, worker chaining), so relying on a later unrelated producer is not deterministic recovery ownership.

Concrete fixed point remains:

1. producer persists or reclassifies Download D as runnable/Queued;
2. producer calls `startDownloadWorker()`;
3. process dies before WorkManager has durably accepted the request, or enqueue publication fails;
4. D remains durably runnable but no exact ordinary queue handoff carrier exists;
5. startup execution recovery sees no active execution/finalization debt for D;
6. generic handoff recovery has no ordinary Download carrier;
7. no unconditional startup queue kick repairs D;
8. D can remain visibly queued until some unrelated action later happens to enqueue another DownloadWorker.

## Root reconciliation with BUG-QUEUE-03

Previously observed `BUG-QUEUE-03` scheduling/queue-handoff subcases remain evidence of this same root where the durable semantic queue transition is not paired with exact accepted WorkManager ownership. They are not a separate canonical blocker.

## Required correction boundary carried forward

1. Before producer responsibility ends, pair every durable ordinary runnable-queue transition with an exact request/generation carrier or equivalent durable recovery debt.
2. Observe WorkManager enqueue acceptance (`Operation.result`) rather than treating enqueue invocation as completion of the handoff.
3. Alternatively, startup must deterministically inventory every runnable Download row and establish/repair exact worker ownership without duplicating active generations.
4. Recovery must distinguish queue-handoff debt from already-established execution/finalization debt and remain idempotent under duplicate producers/startup attempts.
5. Requeue/resume/retry transitions require the same ownership rule as first queue admission; do not fix only the initial add path.
6. Scheduler-mediated START/END handoff remains governed by its existing durable carrier contract; ordinary direct WorkManager triggers must not silently depend on scheduler-specific recovery.
7. Add production-level coverage for first queued item, requeue/resume/retry, enqueue failure, death before acceptance observation, cold-start repair, and duplicate recovery attempts.

## Root/count and basis reconciliation

- `BUG-DOWNLOAD-HANDOFF-01` was already counted as one P2 root.
- `BUG-QUEUE-03` remains alias/subcase evidence of the same root.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is unchanged.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
