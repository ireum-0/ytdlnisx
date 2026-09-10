# Independent Track A checkpoint — metadata source identity and stale-row overwrite

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Scope: F13 `BUG-METADATA-02`, F14 `BUG-METADATA-01`
- Implementation branch in-progress diff intentionally not inspected.
- Authoritative ledger not modified.

## F13 / BUG-METADATA-02 — CONFIRMED OPEN P2

Master Plan invariant: unrelated fresh metadata contributes no field to a Download row; every fresh candidate must pass the existing requested-source identity policy before merge/application.

Exact production basis:

1. `ResultRepository.getSingleMetadataFromUrl()` performs the required `ExtractorSourceIdentityPolicy.matchesRequestedSource(...)` validation.
2. The actual `updateDownloadItem()` enrichment path does not call it.
3. FRESH_FIRST calls `getSingleMetadataFromSource(downloadItem.url)` and immediately `applyMetadata(downloadItem, info)`.
4. CACHE_FIRST validates the cached info-json through strict URL matching, but its `loadFresh` fallback calls `fetchSingleMetadataFromSource(downloadItem.url)` and immediately applies that fresh candidate without the source-identity policy.
5. yt-dlp parsing records source identity on each ResultItem, so the proof material exists but is not consumed at the write boundary.

Concrete impact: a mismatched/ambiguous fresh extractor candidate can contribute title, author, playlist title, duration, website, thumbnail, or published date to an unrelated Download row. Missing/mismatched provenance is not rejected by the actual enrichment function.

Acceptance:
- validate every fresh candidate before merge/apply in both lookup orders;
- reuse `ExtractorSourceIdentityPolicy`, including canonical-equivalent YouTube forms and approved provider redirects;
- missing/mismatched provenance contributes zero fields;
- preserve strict cache validation, cache fallback, and cancellation semantics.

## F14 / BUG-METADATA-01 — CONFIRMED OPEN P1

Master Plan invariant: metadata enrichment may mutate metadata-owned columns only and can never overwrite status, path, queue/order, configuration, scheduling, retry state, or operation state.

Exact production basis:

- `ResultRepository.updateDownloadItem()` mutates and returns the supplied full `DownloadItem` object.
- `UpdateMultipleDownloadsDataWorker` loads an item, performs enrichment, then re-reads the current row and copies back only status, executionId, and issue fields before `updateIfExecutionOwned()` / `updateWithoutUpsert()` with the whole stale object.
- Concurrent changes to download path, format/configuration, queue/order, scheduling, retry/operation fields, or other non-metadata state between the original load and the full-row write can therefore be rolled back even though execution ownership still matches or is blank.
- `DownloadWorker.persistDownloadMetadata()` likewise enriches its worker-held `DownloadItem` snapshot rather than an immutable metadata patch, so the same full-row-authority design remains at the second affected writer.

Concrete impact: a metadata refresh can silently restore stale non-metadata state and thereby undo a concurrent user/system mutation.

Acceptance:
- F13 source validation first;
- return immutable metadata-only patch;
- DAO applies only metadata-owned columns;
- validate stable `(id, executionId/revision/source identity)` or equivalent session authority immediately before patch;
- deletion/source change cancels the patch;
- deterministic concurrent-mutation coverage for both writers.

## Recount

Previous working recount after source-authority/History review:
- P0: 2 — F3, F11
- P1: 2 — F4, F12
- P2: 8 — B, C, J, K, L, M, N/F17, O/F18

Add F13 P2 and F14 P1:
- `P0 2 / P1 3 / P2 9`

P2-B10 remains a subcase of P2-B and does not increase count.

Verdict: `NOT_CLEAN`.

INDEPENDENT EXECUTION: NOT EXECUTED
