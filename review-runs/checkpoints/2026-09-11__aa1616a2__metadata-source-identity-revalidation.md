# BUG-METADATA-02 — exact-basis fresh metadata source-identity revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Newer implementation diff used as exploratory evidence: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F13
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior exact review: `review-runs/checkpoints/2026-09-11__6763fb1b__metadata-source-identity-revalidation.md`

`6763fb1b... -> aa1616a2...` changed only History duplicate-identity code/tests and did not modify `ResultRepository`, metadata identity policy, or the production metadata writers. The root was nevertheless re-read at exact `aa1616a2...`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-METADATA-02` is reconfirmed OPEN at `aa1616a2...`.**

- Count delta: **0**
- Canonical count remains **P0 3 / P1 3 / P2 25**
- CLEAN basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Exact-source evidence

### The strict requested-source identity policy exists

`ResultRepository` imports and uses `ExtractorSourceIdentityPolicy`. Its validated metadata helper rejects a fetched candidate when:

`ExtractorSourceIdentityPolicy.matchesRequestedSource(requestedSource, sourceIdentity)`

is false.

The project therefore already has the semantic policy required to distinguish equivalent/approved provenance from unrelated fresh metadata.

### FRESH_FIRST production enrichment still bypasses the policy

`ResultRepository.updateDownloadItem(downloadItem, lookupOrder)` defaults to `FRESH_FIRST`.

That branch still performs:

1. `getSingleMetadataFromSource(downloadItem.url)`;
2. direct `applyMetadata(downloadItem, info)`.

No `matchesRequestedSource(downloadItem.url, info.sourceIdentity)` check occurs at this merge boundary.

Thus a fresh candidate accepted by the general source fetch helper can contribute fields before proving that it belongs to the requested Download source.

### CACHE_FIRST fresh fallback still bypasses the same policy

The `CACHE_FIRST` branch calls `MetadataEnrichmentResolver.enrichCacheFirst(...)` with:

- a cache loader using the existing cached-info contract;
- a fresh loader using `fetchSingleMetadataFromSource(downloadItem.url)`.

The fresh fallback candidate is still not passed through the requested-source identity predicate before merge/application.

The stricter cache path therefore does not make the fresh fallback safe.

### Validated helper does not protect these production paths

The same repository contains a path that explicitly derives/falls back to `ExtractorSourceIdentity` and calls `matchesRequestedSource()` before accepting fetched metadata. That proves the bypass is not lack of available provenance machinery; it is a composition gap in the production enrichment paths.

### Production consumers remain real

The previous exact-source review established both lookup orders are consumed by real writers:

- `UpdateMultipleDownloadsDataWorker` uses the default FRESH_FIRST path;
- `DownloadWorker.persistDownloadMetadata()` uses CACHE_FIRST.

No file in those production paths changed in `6763fb1b... -> aa1616a2...`, so the exact-aa bypass remains wired into durable Download metadata persistence.

Concrete chain remains:

`Download source A with enrichable/missing metadata`
→ `fresh candidate B with mismatched or unproven source identity`
→ production `updateDownloadItem()` FRESH_FIRST or CACHE_FIRST fresh fallback
→ no requested-source identity validation
→ `applyMetadata()`
→ production writer persists enriched Download row
→ unrelated candidate contributes fields to A.

This violates F13: unrelated fresh metadata contributes no field to a Download row.

## Root reconciliation

This remains existing P2 `BUG-METADATA-02`, counted once.

P1 `BUG-METADATA-01` remains distinct and dependency-gated: it owns stale/full-row metadata writers overwriting non-metadata state after a candidate is accepted. F13 must establish validated metadata candidate semantics before F14 is corrected.

## Required correction boundary

- validate every fresh candidate against the existing `ExtractorSourceIdentityPolicy` before any metadata merge/application;
- apply the same rule to FRESH_FIRST and CACHE_FIRST fresh fallback;
- mismatched or missing/unproven provenance must contribute zero fields;
- preserve accepted equivalent YouTube forms and approved redirects/stable identities already encoded by the policy;
- preserve strict cache behavior, fallback semantics, and cancellation;
- add production-relevant coverage for both lookup orders, including valid cache + mismatched fresh, missing provenance, equivalent sources, and approved redirects.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review.

INDEPENDENT EXECUTION: NOT EXECUTED
