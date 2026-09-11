# BUG-METADATA-02 — current-basis fresh metadata source-identity revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `3eb6c14b68bffb0e462be10ba8efa48bc77b6511` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P1 `BUG-KEYWORD-01`
- Moving implementation diff inspected or relied on: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F13
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

`ResultRepository.kt` changed materially in `aa1616a2... -> 9c5191c3...` for F3 SourceSnapshot/source-extraction semantics, so the previous F13 disposition is re-read against exact current source rather than carried forward automatically.

## Verdict

**NOT_CLEAN — existing P2 `BUG-METADATA-02` remains OPEN at `9c5191c3...`.**

- Canonical blocker-count delta: **0**
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

P1 `BUG-METADATA-01` remains dependency-blocked by this F13 root.

## 1. Requested-source identity policy still exists

Current `ResultRepository` still imports/uses `ExtractorSourceIdentity` and `ExtractorSourceIdentityPolicy`.

A stricter repository helper obtains metadata, derives/falls back to a `sourceIdentity`, and calls:

`ExtractorSourceIdentityPolicy.matchesRequestedSource(requestedSource, identity)`

before accepting the candidate.

Therefore the project has an explicit semantic policy for whether fresh metadata belongs to the requested extractor source.

## 2. FRESH_FIRST production enrichment still bypasses that policy

`ResultRepository.updateDownloadItem(downloadItem, lookupOrder)` still defaults to `FRESH_FIRST`.

The FRESH_FIRST branch at exact `9c5191c3...` still performs:

1. `getSingleMetadataFromSource(downloadItem.url)`;
2. direct `applyMetadata(downloadItem, info)`.

`getSingleMetadataFromSource()` resolves fresh/cache metadata through `MetadataEnrichmentResolver.resolveFreshFirst(...)`, but that path does not perform the requested-source `matchesRequestedSource(...)` acceptance check before returning the candidate to `updateDownloadItem()`.

Thus fresh candidate fields can still be applied to the Download row without proving semantic source identity against the requested Download URL.

## 3. CACHE_FIRST fresh fallback still bypasses the same policy

The CACHE_FIRST branch still calls `MetadataEnrichmentResolver.enrichCacheFirst(...)` with:

- cached loader: `getCachedInfoJsonResultOrThrow(downloadItem.url)`;
- fresh loader: `fetchSingleMetadataFromSource(downloadItem.url)`;
- direct metadata application callback.

The fresh fallback is not passed through `ExtractorSourceIdentityPolicy.matchesRequestedSource(...)` before merge/application.

Therefore a cache miss/incomplete cache can still admit mismatched or unproven fresh metadata.

## 4. F3 SourceSnapshot changes do not implicitly close F13

The recent `ResultRepository` changes introduce typed source-list completeness authority for Observe/keyword consumers. That contract is about list membership completeness (`AUTHORITATIVE / PARTIAL / FAILED`).

F13 is a different semantic predicate: whether one fetched metadata candidate belongs to the exact requested Download source identity.

No current production metadata-enrichment branch composes the stricter source-identity predicate into FRESH_FIRST or CACHE_FIRST fresh fallback.

The presence of the validated helper elsewhere confirms that this is a production-composition bypass, not absence of policy machinery.

## 5. Exact production consumers remain durable writers

`UpdateMultipleDownloadsDataWorker` at `9c5191c3...` still calls `resultRepo.updateDownloadItem(item)` with the default FRESH_FIRST order, then persists the returned metadata-enriched row through the Download DAO after preserving selected current execution/status fields.

`DownloadWorker.persistDownloadMetadata()` still calls `resultRepo.updateDownloadItem(..., CACHE_FIRST)` and then uses the result in the real Download execution path.

Therefore both unsafe lookup-order branches remain production-reachable and feed durable Download metadata persistence.

Concrete chain remains:

`Download source A needs metadata enrichment`
→ `fresh source fetch returns candidate B with mismatched/unproven ExtractorSourceIdentity`
→ FRESH_FIRST or CACHE_FIRST fresh fallback accepts B without matchesRequestedSource(A, B)`
→ `applyMetadata()` contributes B fields to A
→ production worker persists/uses the enriched Download row.

This violates F13: unrelated fresh metadata must contribute no field to a Download row.

## Root reconciliation

Keep this as existing canonical P2 `BUG-METADATA-02`, counted once.

It owns fresh metadata candidate source/provenance validation before merge/application.

Keep P1 `BUG-METADATA-01` distinct: F14 owns stale metadata-derived writes overwriting newer non-metadata Download state after a candidate is otherwise accepted. F13 must establish trusted candidate semantics before F14 can safely be corrected/closed.

## Correction boundary remains

A coherent fix still requires:

1. validate every fresh metadata candidate against `ExtractorSourceIdentityPolicy` before any field is merged/applied;
2. apply the same acceptance rule to FRESH_FIRST and CACHE_FIRST fresh fallback;
3. mismatched or unproven provenance contributes zero fields;
4. preserve accepted equivalent YouTube forms, stable identities, and approved redirect semantics already encoded by the policy;
5. preserve cache preference/fallback behavior and cancellation semantics;
6. add production-relevant tests for both lookup orders, including mismatched fresh, missing provenance, equivalent forms, approved redirects, and valid cache + unsafe fresh fallback.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
