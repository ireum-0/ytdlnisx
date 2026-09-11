# BUG-FORMAT-01 — bulk format refresh swallows per-item failure and can split Result/Download state

Date: 2026-09-12

## Exact review basis

- Independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Historical broader-registry root: P2 `BUG-FORMAT-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active overnight candidate task while reviewed: task 009 `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`
- Moving task-009 diff inspected or relied on: **NO**

## Verdict

**CONFIRMED / PROMOTED P2 `BUG-FORMAT-01` at exact CLEAN basis `9edd3e23...`.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 1 / P2 33**.

CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

Overall remains `NOT_CLEAN`.

## Current exact production chain

`UpdateMultipleDownloadsFormatsWorker.doWork()` iterates the requested Download ids. For an item with empty `allFormats`, the worker wraps the complete per-item refresh sequence in `runCatching`:

1. fetch formats via `resultRepo.getFormats(d.url)`;
2. choose the selected format;
3. mutate the cached Result object;
4. persist the Result row with `resDao.update(...)` when present;
5. reload the current Download state;
6. copy only selected live status/execution/issue fields into the stale working object;
7. persist the Download through `updateIfExecutionOwned(...)` or `updateWithoutUpsert(...)`.

The returned `Result<T>` from `runCatching` is ignored. There is no `.getOrThrow()`, failure branch, per-item failure carrier, or aggregate failure accounting.

The worker then increments `count` and updates progress regardless of whether the wrapped refresh succeeded or threw.

After all ids are traversed, the outer block returns `Result.success()`.

## Concrete failure semantics

### Extraction failure is silently converted to item completion

If `getFormats(...)` throws, `runCatching` captures it. The worker still increments completed count, continues the batch, and can return WorkManager success.

### Result/Download persistence can split

When a cached Result row exists, `resDao.update(this)` occurs before Download persistence. These are not one atomic transaction in this worker.

If Result persistence succeeds and the later Download write fails, the Result representation can contain refreshed formats while the Download row remains stale/missing those formats. The thrown failure is again swallowed by `runCatching`.

### User-visible terminal result remains success-labelled

Unless an exception escapes outside the inner `runCatching`, the outer catch never runs. Therefore mixed or all-per-item failures still converge to `Result.success()`.

The `finally` block shows the formats-updated notification whenever `ids` is still non-empty. Inner failures do not clear it. Thus the same failed ids can be advertised as updated.

## Why existing status/execution refresh does not close the root

The worker does reload current Download status/execution/issue fields before the final Download write, which reduces one class of stale full-row overwrite. That does not establish a typed refresh outcome and does not make the preceding Result/Download durable writes atomic.

An execution-owned DAO write returning no update or throwing also needs truthful per-item result handling; merely choosing an owner-guarded writer does not make an ignored failure a successful format refresh.

## Concrete incorrect impact

A supported batch can reach:

`Saved Download with formats missing`
→ background format refresh starts
→ extraction or later persistence fails for item A
→ failure captured and discarded
→ progress counts A as completed
→ worker processes remaining ids
→ worker returns `Result.success()`
→ success notification includes A
→ A remains stale, or Result and Download disagree.

The user therefore receives a success-labelled refresh despite failed or partially committed durable state.

## Root reconciliation

Count once as existing broader-registry P2 `BUG-FORMAT-01`.

Keep distinct from:
- `BUG-FORMAT-02`, which concerns a later stale format-update notification mutating a Download whose ownership/state advanced;
- metadata refresh roots, which use different repository contracts and fields;
- generic Download execution ownership, which does not itself define bulk format refresh outcome/atomicity.

## Required correction boundary

A coherent repair must:

1. preserve a typed per-item success/failure/cancellation outcome instead of discarding `runCatching` results;
2. explicitly rethrow `CancellationException` rather than converting cancellation into ordinary per-item completion;
3. make Result/Download format publication atomic where they represent one semantic refresh, or define compensating rollback/recovery that cannot success-label split state;
4. treat owner/CAS rejection as a non-successful item unless a fresh authoritative state proves the refresh is already satisfied;
5. aggregate mixed/all-failed batches truthfully into WorkManager result and notification semantics;
6. include only proven-success rows in any success-facing notification, with failed rows preserved/retryable or reported separately;
7. preserve current live Download status/execution authority and F14 metadata publication closure.

Required deterministic coverage should include:
- extractor failure before any write;
- Result-row persistence failure;
- Result succeeds then Download persistence fails/rejects;
- cancellation during extraction/persistence;
- mixed batch with success + failure;
- all-failed batch;
- all-success control;
- concurrent state/execution advancement control proving the refresh cannot overwrite newer execution authority.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
