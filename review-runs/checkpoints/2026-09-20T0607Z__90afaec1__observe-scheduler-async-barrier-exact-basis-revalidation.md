# OBSERVE-SCHEDULER-ASYNC-BARRIER-01 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Canonical existing root: `OBSERVE-SCHEDULER-ASYNC-BARRIER-01`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `OBSERVE-SCHEDULER-ASYNC-BARRIER-01` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A recurring durable source must not publish completion of the current run before the next-run scheduling handoff has either:
- been accepted durably; or
- left an exact recovery owner/debt that guarantees resubmission.

WorkManager request issuance is asynchronous. Returning from `enqueueUniqueWork()` is not the same semantic event as completion of its returned `Operation`.

Checklist v6 Module B and semantic consumer/effect closure require the completion carrier and restart owner to be traced explicitly.

## Exact production evidence at 90afaec1

### 1. ObserveSourceWorker closes durable run state before successor scheduling is proven

`ObserveSourceWorker.finishRunAndSchedule()`:

1. appends run history and advances run count as applicable;
2. sets:
   - `runInProgress = false`;
   - `currentRunStatus = ""`;
3. durably writes the ObserveSource through `repo.update(item)`;
4. constructs the next delayed `ObserveSourceWorker`;
5. calls `WorkManager.getInstance(context).enqueueUniqueWork(..., REPLACE, ...)`;
6. discards the returned `Operation`;
7. immediately returns `Result.success()`.

Thus the worker can publish terminal success for the current recurrence after durable source state says no run is in progress, while the successor enqueue is still unresolved.

### 2. Asynchronous enqueue failure leaves no exact successor owner

If the WorkManager `Operation` later fails:

- the current worker has already returned success;
- the source remains ACTIVE and not-in-progress;
- no exact scheduler handoff debt/carrier is created by this path;
- no retry result is returned for the current worker;
- no accepted successor WorkRequest is proven.

The semantic responsibility “run this ACTIVE source again at its next occurrence” can therefore be lost.

### 3. Repository scheduling has the same request-vs-completion gap

`ObserveSourcesRepository.observeTask()` first requests cancellation of prior unique/tagged work without awaiting those cancellation Operations.

It then issues `enqueueUniqueWork(..., REPLACE, ...)` and discards that enqueue `Operation` as well.

The repository therefore does not provide a completion/acceptance contract to its callers.

### 4. Startup inventory does not close the lost-successor case

At this exact basis, application startup reconciles several durable domains, including automatic-keyword observation coverage, but no generic inventory was found that scans all ACTIVE USER ObserveSources and proves they still own an accepted future WorkRequest.

`AutomaticKeywordObservationCoverage.reconcile()` only manages discovery sources required by automatic-keyword rules.

For an existing managed discovery source, it calls `observeTask()` only when the source is newly created or its status is not ACTIVE.

An already ACTIVE managed source whose WorkRequest is missing is therefore not repaired by that branch either.

Thus startup does not provide a complete fallback owner for the ignored recurring successor handoff.

## Concrete impact

Successful Observe run
→ source state durably becomes ACTIVE + not-in-progress
→ successor enqueue request is issued
→ WorkManager Operation later fails
→ current worker has already returned success
→ no accepted successor and no exact retry/recovery debt
→ recurring source can silently stop observing.

The same completion gap can affect a user-triggered/reconfiguration scheduling call through `ObserveSourcesRepository.observeTask()`.

## Root reconciliation

- Keep `OBSERVE-SCHEDULER-ASYNC-BARRIER-01` as one P2 root.
- Canonical count delta: `0`.
- Keep separate from `BUG-OBSERVE-HANDOFF-01`, whose exact durable carrier protects notification-confirmed Observe -> Download decisions.
- Keep separate from F11-R1, which concerns F11 broad quiescence cancelling otherwise valid scheduling responsibility.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. make Observe scheduling expose and consume a finite enqueue acceptance/completion boundary;
2. never return current-run success after successor scheduling failure unless an exact durable retry/recovery owner has already been established;
3. preserve exact source/generation identity across REPLACE/reconfiguration;
4. ensure cancellation/replacement ordering cannot let an old completion revoke a newer successor;
5. provide startup/recovery discovery for ACTIVE source state whose required WorkRequest is absent, or use an exact durable scheduler handoff carrier that makes startup discovery unnecessary;
6. include USER and managed KEYWORD_DISCOVERY source semantics where applicable;
7. add deterministic production-wiring tests for enqueue Operation failure, process recreation after durable run completion before accepted successor, and replacement/supersession ordering.

## Verification

- Exact-source production-path review: completed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
