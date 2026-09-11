# BUG-METADATA-01 / F14 — independent closure review

Date: 2026-09-12

## Exact reviewed range

- Prior independently CLEAN basis: `36b43464b8106d90d672ba94718ccee58f38974f`
- Completed implementation head: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Exact comparison: one additive commit ahead, zero behind
- Governing pre-implementation checkpoint: `cf3787933f046498aca3c949bc705b21ac78a2dd`
- Preserved F13 closure checkpoint: `e9419ed444a16c593440157f323a0eac72bdac5c`

## Verdict

**CLEAN / FIXED-CLOSED — P1 `BUG-METADATA-01` / F14 is independently closed at exact implementation SHA `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.**

Canonical count delta:
- P0: `0`
- P1: `-1`
- P2: `0`

Resulting canonical count: **P0 2 / P1 1 / P2 29**.

The contiguous independently CLEAN Review Basis advances from `36b43464b8106d90d672ba94718ccee58f38974f` to `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71` for this completed implementation scope.

## Independent exact-source review

### 1. Full-row publication authority was removed from both metadata writers

The implementation introduces an immutable `DownloadMetadataPatch` containing only the fields owned by the existing metadata-enrichment contract:

- title;
- author;
- playlist title;
- duration;
- website;
- thumbnail;
- media publication date.

`ResultRepository.getDownloadMetadataPatch(...)` works from a copy of the requested Download snapshot, derives only fields that changed under the existing `applyMetadata(...)` policy, and returns no patch when metadata enrichment produced no owned-field change. It does not carry queue, scheduling, retry, status, execution, operation, path, format, terminal, or replacement state.

### 2. Batch metadata publication is narrow and final-source guarded

`UpdateMultipleDownloadsDataWorker` now resolves the immutable patch and calls `DownloadDao.updateMetadataIfSourceMatches(...)` rather than republishing a complete `DownloadItem`.

The DAO performs one SQL `UPDATE` limited to metadata-owned columns and predicates the mutation on both the Download id and the expected source URL. Therefore:

- a concurrent non-metadata mutation survives;
- a deleted row is not recreated;
- a row whose durable source changed from A to B receives zero fields from the old A lookup.

The worker no longer copies selected current fields back into an otherwise stale full-row object, so the original F14 stale-snapshot overwrite mechanism is absent.

### 3. DownloadWorker preserves execution authority while narrowing metadata ownership

The production DownloadWorker path now delegates metadata publication to `persistDownloadMetadataNarrowly(...)`.

That helper resolves the same immutable patch through the existing CACHE_FIRST lookup contract and publishes through `updateMetadataIfSourceAndExecutionOwned(...)`. The final SQL predicate requires:

- exact Download id;
- the expected source URL;
- durable `status='Active'`;
- the exact expected `executionId`.

Thus F14 does not weaken the worker's existing execution fence while eliminating full-row metadata publication. A source change, row deletion, worker ownership loss, or terminal transition rejects the stale patch.

### 4. F13 candidate-source identity remains preserved

The implementation leaves the F13 fresh-metadata identity validation in the actual repository lookup path. `fetchSingleMetadataFromSource(...)` still rejects a fresh candidate that does not satisfy `ExtractorSourceIdentityPolicy.matchesRequestedSource(...)`, and the metadata patch is derived only after that protected lookup contract.

The new final publication source predicate is an additional durable-row guard; it does not replace or bypass F13 candidate validation.

The exact string predicate at final publication is conservative for a concurrent rewrite to an equivalent URL spelling: it can reject an otherwise equivalent-source metadata update, but it cannot authorize stale or unrelated metadata. That conservative non-publication does not recreate the F14 data-integrity defect and does not weaken F13 equivalent-source candidate handling.

### 5. Cancellation and partial publication

Patch resolution completes before the DAO mutation. Cancellation propagated by the repository path therefore occurs before publication; there is no staged sequence of metadata field writes that can partially commit.

The implementation-agent report records a deterministic cancellation regression in which cancellation leaves the metadata columns unchanged.

### 6. Deterministic production-wiring evidence

The added instrumentation uses held lookup completions rather than arbitrary sleeps and exercises the actual batch WorkManager path plus the DownloadWorker publication helper used by production. Reported evidence includes:

- batch unchanged-source concurrent non-metadata mutation preservation;
- batch A->B source change rejection;
- batch row-deletion rejection;
- DownloadWorker narrow publication under the same execution;
- DownloadWorker source-change and deletion rejection;
- cancellation with no partial metadata publication.

Agent-reported verification at exact task result SHA:

- focused instrumentation: 6/6 PASS on API 36 x86_64 emulator;
- full JVM: 610/610 PASS;
- KSP: PASS;
- debug Kotlin compile: PASS;
- androidTest Kotlin compile: PASS;
- `git diff --check`: PASS;
- Room migration: NOT REQUIRED.

These execution claims remain implementation-agent evidence only; the CLEAN verdict is based on independent exact-source review of the completed range and production wiring.

## Preserved contracts and regression scope

No concrete regression was found in the independently CLOSED F13 source-identity contract or in the existing Download execution/terminal/barrier predicates relevant to this publication path.

The completed F14 range does not modify unrelated cache, Observe, keyword, History replacement, backup, or handoff roots.

## Queue consequence

Overnight candidate task 002 is independently isolated from this canonical commit: its merge base with the canonical implementation is the old CLEAN basis `36b43464...`; it remains candidate-only and is not promoted by this closure. The currently RUNNING candidate task remains frozen from inspection.

INDEPENDENT EXECUTION: NOT EXECUTED