# Observe scheduler root alias reconciliation — BUG-OBSERVE-03 / OBSERVE-SCHEDULER-ASYNC-BARRIER-01

Date: 2026-09-20

## Exact review state

- Fixed independently CLEAN basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Earlier canonical root: P2 `BUG-OBSERVE-03`.
- Later review label: P2 `OBSERVE-SCHEDULER-ASYNC-BARRIER-01`.
- Active F11 implementation remained frozen from inspection.

## Verdict

**CANONICAL ALIAS / COUNT ONCE.**

`OBSERVE-SCHEDULER-ASYNC-BARRIER-01` does not establish a semantically distinct blocker from existing P2 `BUG-OBSERVE-03` at `90afaec1...`.

Canonical interpretation:

- canonical root identity: `BUG-OBSERVE-03`;
- accepted descriptive alias: `OBSERVE-SCHEDULER-ASYNC-BARRIER-01`;
- state: P2 / OPEN;
- count delta from this reconciliation: `0`;
- do not count both labels separately.

## Evidence

### Earlier exact CLEAN-basis checkpoint

`review-runs/checkpoints/2026-09-14__90afaec1__observe03-current-basis-revalidation.md` proves `BUG-OBSERVE-03` OPEN at the same exact basis.

Its production sequence is:

- `ObserveSourceWorker.finishRunAndSchedule()` durably clears current run state;
- ordinary successor `enqueueUniqueWork(..., REPLACE, ...)` is issued;
- returned WorkManager `Operation` is not consumed;
- current worker returns `Result.success()`;
- no exact ordinary-successor handoff debt is durable;
- startup has no ordinary ACTIVE-source carrier reconstruction.

It also covers repository-driven create/edit scheduling through `ObserveSourcesRepository.observeTask()` and the same ignored enqueue-completion boundary.

### Later exact-basis checkpoint

`review-runs/checkpoints/2026-09-20T0607Z__90afaec1__observe-scheduler-async-barrier-exact-basis-revalidation.md` describes the same authoritative objects and same failure sequence:

- durable ACTIVE/not-in-progress source state;
- ignored `enqueueUniqueWork()` Operation;
- missing accepted successor;
- no exact retry/recovery debt;
- startup inventory that does not reconstruct ordinary ACTIVE USER Observe scheduling.

### No distinct authority object or independent final effect

The later label does not identify a different durable producer, different scheduler carrier, different restart owner, or different final user-visible failure.

Both labels own the same semantic responsibility:

`current valid Observe generation has durable ACTIVE recurrence intent but may lose its exact WorkManager carrier because enqueue acceptance is not durably owned/recovered`.

The create/edit subcases and successful-run successor subcase are manifestations of the same durable-intent -> asynchronous-carrier handoff root.

### Distinct neighboring roots remain distinct

This alias reconciliation does not merge:

- `BUG-OBSERVE-HANDOFF-01` — stale configuration-generation/revocation authority at final mutation/publication boundaries;
- `BUG-OBSERVE-02` — configuration edits overwriting worker-owned runtime fields;
- `BUG-OBSERVE-SOURCE-IDENTITY-01` — source semantic identity/concurrent row publication;
- automatic-keyword manual-sync scheduling handoff;
- Download queue carrier handoff.

Those have different authoritative objects or final effects.

## Stable canonical naming

Use `BUG-OBSERVE-03` as the canonical defect ID because it was already promoted and exact-basis revalidated before the later descriptive label appeared.

`OBSERVE-SCHEDULER-ASYNC-BARRIER-01` may remain in historical checkpoints as an alias for discoverability, but future counts, closure evidence, implementation authorization, and handoff state should treat them as one root.

## Verification

- Both exact-basis checkpoints compared directly.
- No `BUG-OBSERVE-03` closure implementation commit was found.
- Exact current source still satisfies the common failure sequence.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
