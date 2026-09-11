# BUG-OBSERVE-03 — recover ACTIVE Observe Source schedules after enqueue loss

Date: 2026-09-12

## Exact review basis

- Independently CLEAN implementation basis: `36b43464b8106d90d672ba94718ccee58f38974f`
- Broader-registry source: `review/remediation:TASKS.md`, historical P2 `BUG-OBSERVE-03`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active implementation wave: overnight queue task 001, P1 `BUG-METADATA-01` / F14, starting from `36b43464...`
- Moving canonical/candidate implementation diff inspected or relied on: **NO**

## Verdict

**NOT_CLEAN — broader-registry P2 `BUG-OBSERVE-03` is independently reproduced at exact `36b43464...` and is promoted into the current canonical blocker inventory.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 2 / P2 29**.

CLEAN basis remains `36b43464b8106d90d672ba94718ccee58f38974f`.

Overall remains `NOT_CLEAN`.

## Concrete current-source chains

### A. New/edit source intent can outlive its WorkManager carrier

`ObserveSourcesViewModel.insertUpdate()` durably commits the Observe Source row before requesting its one-time WorkManager carrier.

For an existing row:
1. `repository.update(item)` commits the new ACTIVE source/configuration;
2. `repository.observeTask(item)` then cancels current unique/tagged work and attempts the replacement enqueue.

For a new row:
1. `repository.insert(item)` commits the source;
2. only after the generated id is returned does `repository.observeTask(item)` attempt the first WorkManager enqueue.

`ObserveSourcesRepository.observeTask()`:
- cancels existing work;
- builds `ObserveSourceWorker` with only source id;
- calls `workManager.enqueueUniqueWork("OBSERVE<id>", REPLACE, request)`;
- discards the returned `Operation` and persists no exact pending/accepted handoff carrier.

Therefore asynchronous enqueue failure, or process death after the ACTIVE row commit and before WorkManager acceptance is durable, can leave a valid durable ACTIVE source with no execution carrier. An edit can additionally cancel the previous valid carrier before replacement acceptance is established.

### B. Recurring successor has the same split-brain handoff

`ObserveSourceWorker.finishRunAndSchedule()`:
1. persists run history/count/progress and ACTIVE/STOPPED decision through `repo.update(item)`;
2. if the source remains active, builds a new one-time `ObserveSourceWorker`;
3. calls `enqueueUniqueWork("OBSERVE<sourceID>", REPLACE, ...)`;
4. ignores the returned `Operation`;
5. immediately returns `Result.success()`.

Thus the current worker can report terminal success after durable source-state commit even though the successor carrier was never accepted. Process death in the same durable gap produces the same orphaned ACTIVE intent.

### C. Startup recovery does not reconstruct ordinary USER Observe schedules

`App.onCreate()` runs several recovery owners, including `WorkManagerHandoffRecovery.reconcile()` and `AutomaticKeywordObservationCoverage.reconcile()`.

`WorkManagerHandoffRecovery` owns explicit durable carriers such as scheduled START/END, hard-sub, and special `OBSERVE_RETRY_DOWNLOAD`; it does not reconstruct ordinary recurring USER Observe schedules from ACTIVE `ObserveSourcesItem` rows.

`AutomaticKeywordObservationCoverage.reconcile()` enumerates Observe sources to manage hidden `KEYWORD_DISCOVERY` coverage. It may create/activate a managed discovery source, but it does not enumerate ordinary ACTIVE USER sources and prove each has a live/future `OBSERVE<id>` WorkManager carrier.

Current caller inventory for `ObserveSourcesRepository.observeTask()` is action-oriented (ViewModel create/edit, managed keyword coverage, restore) rather than a generic startup convergence loop for all durable ACTIVE USER sources.

Therefore an ACTIVE USER source whose ordinary carrier was lost has no guaranteed process-start owner that recreates it.

## Concrete impact

A user can have a source durably shown/configured as ACTIVE while it silently never executes again.

One valid sequence is:

`ACTIVE source row committed`
→ `old work cancelled or no old work exists`
→ `replacement enqueue Operation fails asynchronously / process dies before durable acceptance`
→ no exact pending carrier is retained
→ app/process restarts
→ startup reconcilers do not rebuild ordinary ACTIVE USER Observe schedule
→ source remains durably ACTIVE but future observation never occurs.

The same liveness loss can happen between one successful run and its successor.

## Root reconciliation

Count once as broader-registry `BUG-OBSERVE-03`.

Keep distinct from existing P0 `BUG-OBSERVE-HANDOFF-01`:
- P0 owns **ordinary Observe configuration generation/revocation authority**: stale E1 vs current E2/STOP/delete, stale source-row writes, destructive mutation under revoked authority, and whether a stale generation may publish a successor.
- `BUG-OBSERVE-03` owns **carrier acceptance/recovery for the current valid generation itself**. No stale generation is required. Even if there is exactly one current generation and no concurrent edit/STOP, its enqueue can fail or be lost after durable ACTIVE intent.

A future architecture may use one durable generation-aware handoff carrier to solve both roots, but semantic attribution remains distinct because either defect can exist without the other.

Keep distinct from:
- CLOSED F3 `BUG-OBSERVE-01`: source-snapshot completeness/destructive absence authority;
- P2 `BUG-OBSERVE-02`: configuration edit overwriting worker-owned runtime fields;
- P2 `BUG-OBSERVE-SOURCE-IDENTITY-01`: source semantic identity/concurrent row publication;
- P2 `BUG-KEYWORD-HANDOFF-01`: automatic-keyword sync handoff domain;
- P2 `BUG-DOWNLOAD-HANDOFF-01`: normal Download queue carrier domain.

## Required correction boundary

A coherent correction must:

1. make durable ACTIVE Observe intent and its WorkManager carrier converge under an explicit handoff protocol;
2. persist an exact pending/accepted carrier identity before relying on asynchronous enqueue completion, or otherwise prove durable WorkManager acceptance before considering the handoff complete;
3. observe `Operation.result` and retry/reconcile failed enqueue rather than discarding it;
4. cover create, edit/replacement, restore, managed source creation where applicable, and recurring successor publication;
5. on process start, discover ACTIVE ordinary Observe intent whose exact carrier is missing/failed and recreate or converge it safely;
6. integrate with the P0 generation/revocation contract so recovery never resurrects an obsolete generation or STOP/deleted source;
7. preserve the special confirmed-retry `WorkManagerHandoffRecovery` contract without conflating it with ordinary scheduling;
8. add deterministic production-wiring coverage for:
   - new ACTIVE source commit -> enqueue failure -> restart recovery;
   - edit cancels old carrier -> replacement enqueue failure -> current generation recovered;
   - successful run -> successor enqueue failure -> restart recovery;
   - process death between durable intent/carrier preparation and WorkManager acceptance;
   - STOP/delete after a pending failed enqueue -> startup must not resurrect it;
   - already accepted current carrier -> reconciliation does not duplicate semantic execution.

Do not broaden the eventual repair into unrelated Observe source-membership or keyword semantics.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
