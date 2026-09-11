# BUG-METADATA-01 / F14 — stale full-row metadata publication revalidation

Date: 2026-09-11

## Exact review basis

- Independently CLEAN basis: `36b43464b8106d90d672ba94718ccee58f38974f`
- F13 / `BUG-METADATA-02` closure checkpoint: `e9419ed444a16c593440157f323a0eac72bdac5c`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

F14 had a hard prerequisite on the F13 validated fresh-metadata candidate contract. F13 is now independently CLEAN, so this existing P1 root is dependency-eligible and is revalidated against the new exact basis before issuing implementation work.

## Verdict

**NOT_CLEAN — existing P1 `BUG-METADATA-01` / F14 remains OPEN and is now implementation-eligible.**

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 2 / P1 2 / P2 28**
- CLEAN basis remains: `36b43464b8106d90d672ba94718ccee58f38974f`

The F13 change corrected candidate trust; it did not change either durable metadata writer or the Download DAO full-row publication primitives. The F14 stale-snapshot overwrite root therefore remains.

## Exact production root

### 1. Background batch writer still enriches a stale mutable Download snapshot

`UpdateMultipleDownloadsDataWorker` loads a `DownloadItem` by id and passes that mutable snapshot to `ResultRepository.updateDownloadItem(item)`.

Metadata extraction may suspend for a substantial interval. After enrichment returns, the worker re-reads the current row, but it copies back only a small subset of currently-owned fields before publication:

- `status`;
- `executionId`;
- `lastIssueCode`;
- `lastIssueStage`;
- replacement-barrier issue fields when present.

All other fields on `updatedItem` still originate from the earlier pre-fetch snapshot. The worker then calls either:

- `dao.updateIfExecutionOwned(updatedItem, currentItem.executionId)`, or
- `dao.updateWithoutUpsert(updatedItem)`.

Therefore a concurrent durable mutation to another non-metadata field can be overwritten by the stale full-row object even though status/execution/issue fields are refreshed.

### 2. DAO publication primitives are still full-row writes

`DownloadDao.updateWithoutUpsert(...)` ultimately delegates to a Room `@Update` full-row writer after its existing terminal/barrier guards.

`DownloadDao.updateIfExecutionOwned(...)` is explicitly documented as a full-row write while the exact worker owns the attempt. Its execution/terminal-convergence guards prevent several ownership violations, but they do not convert metadata enrichment into a metadata-only patch and do not prove that every non-metadata field on the stale object is still current.

Execution ownership is therefore necessary but insufficient for F14: the same execution may legitimately receive concurrent durable configuration/path/queue/retry/scheduling changes that metadata enrichment does not own.

### 3. DownloadWorker metadata persistence has the same publication class

`DownloadWorker.persistDownloadMetadata(...)` uses the now-safe F13 `ResultRepository.updateDownloadItem(..., CACHE_FIRST)` lookup contract, but it still begins from a mutable `DownloadItem` snapshot and later publishes the enriched object through Download-row writer semantics.

F13 now proves that fresh metadata belongs to the requested source. It does not prove that the pre-fetch Download snapshot remains current when the metadata is published.

### 4. Current-source authority must also survive the fetch

A source URL or other source-defining field can change, or the row can be deleted, while metadata lookup is in flight. F13 validates a fresh candidate against the **requested source used for that lookup**. F14 must additionally prove at publication time that the Download row still represents that same source authority.

Otherwise correctly validated metadata for source A can be applied after the durable row has moved to source B.

## Stable correction boundary

The smallest coherent F14 correction is to stop treating metadata enrichment as authority to republish a complete `DownloadItem` row.

Required result:

1. represent the enrichment result as an immutable metadata-only patch or equivalent narrow carrier;
2. apply only fields owned by metadata enrichment through a DAO metadata-only update rather than a full-row replacement;
3. guard the final patch with the requested source/current-source identity needed to reject a stale A->B publication;
4. if the Download row was deleted or its source authority changed during lookup, apply no stale metadata;
5. preserve F13 candidate validation, existing cache/fresh lookup policy, equivalent-source handling, approved redirects, and cancellation propagation;
6. preserve existing execution/terminal/barrier authority rather than weakening those guards;
7. do not use arbitrary sleeps as concurrency proof.

The exact metadata-owned field set must be derived from the existing `ResultRepository.applyMetadata(...)` contract; do not broaden the patch into unrelated Download configuration, queue, scheduling, retry, operation, execution, path, or terminal fields.

## Required regression/effect coverage

At minimum, exercise both real production writer classes with deterministic overlap where practical:

1. **Batch worker / unchanged source:** pause lookup after the old row is loaded, mutate one or more non-metadata durable fields, resume lookup, and prove only metadata-owned fields change.
2. **Batch worker / source changed:** change the Download source while lookup is paused; old-source metadata must not commit.
3. **Batch worker / row deleted:** delete the row while lookup is paused; no stale resurrection or patch may occur.
4. **DownloadWorker / unchanged source:** overlap CACHE_FIRST/fresh enrichment with a non-metadata durable mutation and prove it survives.
5. **DownloadWorker / source changed or deleted:** stale metadata must not commit.
6. **Normal unchanged-source path:** valid metadata still commits.
7. **F13 preservation:** mismatched or unproven fresh metadata still contributes zero fields.
8. **Cancellation:** cancellation propagates and does not publish a partial metadata patch.

Helper/DAO tests alone are insufficient if they do not prove the two production worker paths use the narrow publication boundary.

## Root reconciliation

Keep P1 `BUG-METADATA-01` distinct from the now-CLOSED P2 `BUG-METADATA-02`.

- F13 owns whether a fresh candidate belongs to the requested source before merge/application.
- F14 owns whether an enriched result may overwrite newer durable Download state after a long lookup and whether requested-source authority is still current at publication.

Do not broaden this implementation into unrelated Download handoff, deletion-snapshot, cache-root, or History-replacement roots.

No Master Plan or authoritative-ledger modification is made by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED