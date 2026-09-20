# BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Canonical existing root: `BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.
- No new canonical root.

## Governing invariant

A multi-item worker must not publish aggregate success while material per-item failures or explicit authority refusals have been silently collapsed.

Checklist v6 sibling/remainder isolation and semantic-contract consumer closure require every material per-item outcome to remain represented through the final worker result/progress/publication.

A Boolean CAS/ownership result is part of the semantic contract; ignoring `false` is not equivalent to success.

## Exact production evidence at 90afaec1

### 1. Entire per-item mutation path is wrapped in ignored runCatching

`UpdateMultipleDownloadsFormatsWorker.doWork()` iterates the requested Download IDs.

For each item needing format enrichment it enters an unconsumed:

`runCatching { ... }`

The block performs correctness-relevant work including:

- remote/result format lookup;
- format selection;
- Result row update;
- current Download reread;
- preservation of current status/execution/issue fields;
- History replacement-barrier projection;
- Download row publication.

The resulting `Result<T>` from `runCatching` is not consumed.

Therefore an exception inside one item is silently converted into a discarded failure object.

### 2. Explicit execution-authority refusal is discarded

For an existing Download with nonblank execution identity, the worker calls:

`dao.updateIfExecutionOwned(d, current.executionId)`

The DAO returns `Boolean`.

It returns `false` when, among other cases:

- current execution identity no longer matches;
- target-deleted issue preservation would be violated;
- terminal convergence debt would be overwritten;
- current History replacement barrier does not match the proposed write.

The worker ignores this Boolean return value.

Thus an exact authority refusal is consumed as though the per-item update succeeded.

### 3. Progress and aggregate success still advance

After the ignored `runCatching` block, the worker unconditionally:

- increments `count`;
- publishes progress through `updateFormatUpdateNotification(...)`.

After the loop it returns:

`Result.success()`

unless an exception escapes outside the `runCatching`.

The `finally` block can also publish:

`showFormatsUpdatedNotification(ids + otherIdsInBundle)`

when the original ID list remains nonempty.

Therefore a batch containing one or more silently failed/refused items can still publish successful progress, overall WorkManager success, and a user-visible formats-updated completion notification.

### 4. Failure is not represented as exact residual responsibility

No per-item failure ledger/result set is preserved by this worker.

An ignored exception or `updateIfExecutionOwned()==false` therefore does not leave an exact retry/recovery owner for that item before aggregate success.

### 5. Existing dedicated regression was not located

Repository search at this exact basis found no dedicated production test for `UpdateMultipleDownloadsFormatsWorker` that forces:
- one item exception while siblings succeed; or
- `updateIfExecutionOwned()==false`;

and then proves aggregate outcome does not falsely report full success.

## Concrete impact

For a batch A/B/C:

A succeeds
→ B format lookup or persistence throws, or exact execution authority rejects the write
→ failure is discarded
→ count/progress advances for B
→ C may continue
→ worker returns `Result.success()`
→ completion notification reports the batch as updated.

The user and WorkManager therefore receive terminal aggregate success even though one or more requested durable updates did not occur.

The problem is not that sibling work continues; continuing independent siblings can be desirable. The defect is loss of the failed/refused item outcome and false aggregate success/publication.

## Root reconciliation

- Keep `BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01` as one P2 root.
- Canonical count delta: `0`.
- Keep separate from `WORKER-FOREGROUND-COMPLETION-01`: foreground completion is a pre-work async contract; this root is the per-item/aggregate semantic result contract.
- Do not merge with generic metadata batch behavior: `UpdateMultipleDownloadsDataWorker` has a different batch processor/result path.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. make every requested item produce an explicit outcome such as success, skipped/refused, retryable failure, or terminal failure;
2. consume the Boolean result of `updateIfExecutionOwned()` as an authority outcome, not an incidental return;
3. preserve successful siblings without erasing failed/refused siblings;
4. derive progress and final WorkManager result from actual material outcomes;
5. ensure the final completion notification does not claim full success when material items remain failed/refused/unprocessed;
6. define exact retry/residual responsibility for retryable items if the governing product/worker contract requires retry;
7. add deterministic mixed-batch production-wiring coverage, including a later-item exception and an execution-owner rejection after an earlier item has durably succeeded;
8. preserve cancellation rather than converting cancellation into ordinary per-item failure.

## Verification

- Exact-source production-path review: completed at `90afaec1...`.
- Dedicated regression-source inventory: no direct focused worker regression located.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
