# BUG-OBSERVE-03 — current-basis revalidation at 252adf8c

Date: 2026-09-25 +09:00

Exact independently reviewed implementation basis:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

Observe generation/ownership closure checkpoint:
009a03e5f15f80bfb707a9093ba96522910b8d84

Alias:
OBSERVE-SCHEDULER-ASYNC-BARRIER-01

Count this finding once.

## Verdict

OPEN / CONFIRMED / NOT_CLEAN — P2 BUG-OBSERVE-03 remains valid at exact
current basis 252adf8c0cb3762b4dd1335a7c24fd912be683e5.

Canonical defect delta: 0.

Totals remain after the just-completed Observe closure:
- P0 = 0
- P1 = 0
- P2 = 22

Overall remains NOT_CLEAN.

CLEAN_REVIEW_BASIS remains:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

## Exact current evidence

The generation/ownership wave correctly fences recurring successor publication
against edit/STOP/delete, but it does not make ordinary recurrence enqueue
acceptance durable.

ObserveSourceWorker.finishRunAndSchedule(...)
calls ObserveSourcesRepository.finishRunAndSchedule(...).

For a continuing source, repository finishRunAndSchedule:
1. publishes runtime under exact generation;
2. reloads the current source;
3. calls enqueueObservation(current, null);
4. reports committed=true when enqueueObservation returns a non-null Operation.

enqueueObservation(...) calls WorkManager.enqueueUniqueWork(...) and returns
the Operation directly.

The ordinary recurring successor path does not:
- await Operation.result before treating successor publication as accepted;
- persist an ordinary Observe recurrence carrier before releasing the producer;
- durably record an enqueue failure for restart recovery;
- reconstruct a failed ordinary recurrence publication after process death.

By contrast, observeTaskAndAwait(...) and restore scheduling paths explicitly
await the Operation result, and the specialized confirmed-retry path has a
durable WorkManagerHandoffCarrier owner.

## Failure model

Current generation G completes a run
-> runtime commit succeeds
-> enqueueUniqueWork returns an Operation object
-> finishRunAndSchedule reports committed
-> Operation later fails or process dies before acceptance is durably known
-> no ordinary recurring successor owner remains
-> the source is still ACTIVE but its recurrence can silently stop.

The generation fix prevents stale publication, but it does not close this
acceptance/recovery hole.

## Required correction invariants

A correction should provide a durable or otherwise provably restart-safe
acceptance model for ordinary Observe recurrence while preserving the new
generation fence.

At minimum:
1. successor publication must remain bound to exact source generation;
2. edit/STOP/delete must still supersede/revoke older recurrence authority;
3. enqueue acceptance must not be inferred merely from a non-null Operation;
4. an enqueue failure or process death before durable acceptance must leave a
   reconstructable owner/debt;
5. recovery must not resurrect a stale generation;
6. a retry/recovery attempt must not cancel or replace a newer-generation owner;
7. ordinary successful recurrence must remain exactly-once semantically across
   retries/restarts to the extent WorkManager unique-work semantics permit;
8. preserve Restore admission and existing confirmed-retry handoff semantics.

Do not merge this identity/count with the already-closed P0
BUG-OBSERVE-HANDOFF-01.

INDEPENDENT EXECUTION: NOT EXECUTED
