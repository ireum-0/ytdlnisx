# BUG-METADATA-02 — fresh metadata source-identity revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna implementation wave is active.
- The active Luna implementation commits/diffs were not inspected, compared, reviewed, or relied upon.
- Concurrent review-branch records referring to newer implementation SHAs were not used as implementation evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-METADATA-02`

At the fixed CLEAN basis, the project already has a requested-source identity policy capable of rejecting unrelated fresh metadata, but the real Download metadata-enrichment composition bypasses that policy in both production lookup orders before applying fresh candidate fields.

## Prior-root reconciliation

This is the same root already recorded at older basis `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d` in:

- `review-runs/checkpoints/2026-09-10__c2294c87__track-a-metadata.md`;
- `review-runs/checkpoints/2026-09-10__c2294c87__handoff-metadata-source-authority-followup.md`.

Those records established that `getSingleMetadataFromUrl()` validates source identity while the production `updateDownloadItem()` path does not. Fresh exact-source review at `6763fb1b...` confirms that semantic bypass remains present. This checkpoint therefore does not create a new root or count.

## Exact-source evidence

### 1. A correct identity policy exists

`app/src/main/java/com/ireum/ytdl/util/ExtractorSourceIdentity.kt` and `ExtractorSourceIdentityPolicy` provide the source/provenance contract.

`app/src/main/java/com/ireum/ytdl/database/repository/ResultRepository.kt` has a validated helper path, `getSingleMetadataFromUrl()`, that accepts a fresh candidate only when:

`ExtractorSourceIdentityPolicy.matchesRequestedSource(inputQuery, fetched.sourceIdentity)`

is true.

Exact-basis `ExtractorSourceIdentityPolicyTest` confirms the intended policy handles exact URLs, equivalent YouTube forms, approved stable-identity/provider redirects, original-url provenance, meaningful query distinctions, playlist-vs-item distinctions, missing requested-source-only evidence, and unrelated-provider/source rejection.

Thus the required validation mechanism exists and has focused helper coverage.

### 2. FRESH_FIRST production enrichment bypasses it

`ResultRepository.updateDownloadItem(downloadItem, lookupOrder)` in `FRESH_FIRST` mode obtains metadata through `getSingleMetadataFromSource(res.url)` and passes the candidate directly to `applyMetadata(res, candidate)`.

`getSingleMetadataFromSource()` resolves fresh/cache availability and merge usability, but it does not require `matchesRequestedSource()` before returning the fresh candidate. Its fresh producer ultimately calls `fetchSingleMetadataFromSource()`, which is effectively:

`getResultsFromSource(inputQuery, resetResults = false, addToResults = false, singleItem = true).firstOrNull()`.

No source-identity predicate is applied before the candidate reaches `applyMetadata()`.

### 3. CACHE_FIRST remains affected through its fresh fallback

`CACHE_FIRST` uses `MetadataEnrichmentResolver.resolveCacheFirst(...)` with a cache loader and a fresh fallback. The cached-info path has stricter URL/provenance handling and is not being classified here as universally unsafe.

However, the fresh fallback uses `fetchSingleMetadataFromSource(res.url)` and the resolved candidate is again passed to `applyMetadata()` without `ExtractorSourceIdentityPolicy.matchesRequestedSource()`.

Therefore strict cache validation does not close the fresh-candidate bypass in CACHE_FIRST.

### 4. Candidate source provenance exists but is not consumed at the production merge boundary

`YTDLPUtil` parsing materializes `ExtractorSourceIdentity` fields on `ResultItem` where available, including original/canonical source, stable media identity, extractor/type, and playlist provenance.

The proof material needed to reject a mismatched fresh candidate therefore exists. The defect is that the production enrichment boundary does not consume it before merge/application.

### 5. `applyMetadata()` can copy unrelated candidate fields

`ResultRepository.applyMetadata(existing, fresh)` fills missing metadata fields on the target `DownloadItem`. It does not independently validate that `fresh.sourceIdentity` belongs to the requested Download source.

Consequently an unrelated/mismatched fresh candidate can contribute metadata such as title, author, playlist title, duration, website, thumbnail, and published date when the corresponding target field is eligible for enrichment.

### 6. Both lookup orders are wired into real production writers

`app/src/main/java/com/ireum/ytdl/work/UpdateMultipleDownloadsDataWorker.kt` calls `resultRepo.updateDownloadItem(item)` using the default `FRESH_FIRST` path and persists the resulting row.

`app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt`, in `persistDownloadMetadata(...)`, calls:

`resultRepo.updateDownloadItem(downloadItem, lookupOrder = CACHE_FIRST)`

before continuing successful Download handling and persists the resulting metadata state.

This proves the source-validation bypass is not helper-only dead code: both affected lookup modes have real production consumers.

Concrete chain:

`Download source A with missing metadata`
→ `fresh candidate B with mismatching/missing source provenance`
→ `updateDownloadItem()` production lookup path
→ no `matchesRequestedSource(A, B.sourceIdentity)`
→ `applyMetadata(A, B)`
→ production worker persistence
→ unrelated fresh metadata contributes fields to Download A.

This violates the F13 invariant that unrelated fresh metadata contributes no field to a Download row.

## Governing correction boundary

The Master Plan F13 contract remains applicable:

- validate every fresh candidate before merge/application;
- reuse the existing canonical-equivalent and approved redirect/source policy rather than inventing a second URL model;
- missing or mismatched fresh provenance contributes zero fields;
- preserve equivalent YouTube forms and approved redirects;
- preserve strict cache validation and cache fallback behavior;
- preserve cancellation semantics;
- cover both FRESH_FIRST and CACHE_FIRST fresh-fallback paths with production-relevant regressions.

This checkpoint does not require weakening or replacing the existing cache identity contract.

## Relationship to `BUG-METADATA-01`

`BUG-METADATA-02` owns **candidate identity/provenance validation before metadata merge**.

P1 `BUG-METADATA-01` remains a distinct downstream root for **stale full-row metadata writers overwriting non-metadata state after a candidate has been accepted**. F13 is a hard prerequisite for F14 correction semantics; this checkpoint neither merges nor closes F14.

## Root/count reconciliation

- Existing canonical P2 root `BUG-METADATA-02` reconfirmed.
- Count delta: `0`.
- Canonical blocker count remains `P0 3 / P1 3 / P2 26`.
- The contiguous independently CLEAN Review Basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED