# BUG-METADATA-02 / F13 — fresh metadata source-identity closure

Date: 2026-09-11

## Exact review basis

- Prior independently CLEAN basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Reported implementation completion: `36b43464b8106d90d672ba94718ccee58f38974f`
- Exact remote implementation HEAD independently verified: `36b43464b8106d90d672ba94718ccee58f38974f`
- Exact comparison `4ef990e0... -> 36b43464...`: **1 commit ahead / 0 behind**
- Exact parent of completion commit: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Changed production file: `app/src/main/java/com/ireum/ytdl/database/repository/ResultRepository.kt`
- Changed production-wiring test: `app/src/androidTest/java/com/ireum/ytdl/database/repository/ResultRepositoryMetadataIdentityProductionWiringTest.kt`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Moving implementation state inspected before completion: **NO**

## Verdict

**CLEAN — P2 `BUG-METADATA-02` / F13 is FIXED-CLOSED at `36b43464b8106d90d672ba94718ccee58f38974f`.**

The independently CLEAN basis advances to `36b43464b8106d90d672ba94718ccee58f38974f`.

Canonical blocker-count reconciliation includes the independently promoted broader P2 `BUG-OBSERVE-02` at checkpoint `4c27b0588b632086085165882325e7961f675cd2` before applying this closure:

- pre-closure reconciled count: **P0 2 / P1 2 / P2 29**
- `BUG-METADATA-02` closure delta: **P2 -1**
- post-closure canonical count: **P0 2 / P1 2 / P2 28**
- overall project: **NOT_CLEAN**

P1 `BUG-METADATA-01` / F14 remains OPEN and is now dependency-eligible. Its stale full-row publication root is distinct from this fresh-candidate source-identity root.

## F13 contract independently reviewed

The governing F13 invariant is that unrelated or unproven **fresh metadata** must contribute no field to a Download row. The correction must validate every fresh candidate before merge/application by reusing the existing source-identity policy, while preserving cache fallback, equivalent YouTube forms, approved redirects, and cancellation behavior.

### 1. Fresh candidate validation now occurs at the shared loader boundary

`ResultRepository.fetchSingleMetadataFromSource(inputQuery)` obtains the fresh candidate from the existing source extraction path and, before returning it to any enrichment/merge consumer, calls the shared source-identity boundary through `matchesRequestedMetadataSource(...)`.

`matchesRequestedMetadataSource(...)` constructs/uses `ExtractorSourceIdentity` and delegates to the existing `ExtractorSourceIdentityPolicy.matchesRequestedSource(...)`. No parallel URL-identity model was introduced.

If the candidate is mismatched or cannot establish requested-source identity, the fresh loader returns `null`. The rejected candidate therefore cannot donate title, author, thumbnail, duration, or any other metadata field to the caller.

### 2. FRESH_FIRST is closed

`updateDownloadItem(..., FRESH_FIRST)` obtains metadata through `getSingleMetadataFromSource(downloadItem.url)`.

That path now uses the guarded fresh loader. `getSingleMetadataFromUrl(...)` also performs a final requested-source check before returning a result.

Therefore an unrelated or unproven fresh candidate is rejected before `applyMetadata(...)` can mutate the in-memory Download item.

### 3. CACHE_FIRST fresh fallback is closed

`updateDownloadItem(..., CACHE_FIRST)` uses `MetadataEnrichmentResolver.enrichCacheFirst(...)` with:

- cached loader: `loadCachedMetadata(downloadItem.url)`;
- fresh loader: `fetchSingleMetadataFromSource(downloadItem.url)`;
- apply callback: `applyMetadata(downloadItem, info)`.

The fresh fallback passes through the same requested-source validation before the resolver can apply it. A mismatched fresh candidate becomes `null`; it cannot replace or supplement valid cache metadata.

This preserves the intended cache-first behavior rather than making fresh identity validation depend on lookup order.

### 4. Merge/fallback semantics do not reintroduce rejected fresh data

`MetadataEnrichmentResolver` receives only the return value of the guarded fresh loader. Once a fresh candidate is rejected to `null`, there is no object left for its fields to enter the cache/fresh merge or `applyMetadata(...)` path.

The resolver continues to permit usable cache fallback when fresh is absent or fails according to the existing policy. `CancellationException` remains explicitly rethrown rather than converted into cache fallback or partial success.

### 5. Existing source-equivalence policy is preserved

The unchanged shared `ExtractorSourceIdentityPolicy` remains the authority for accepted source equivalence. It retains:

- equivalent YouTube video URL forms via stable media identity;
- YouTube playlist/channel-specific identity handling;
- trusted canonical source matching;
- approved Vimeo stable-ID redirect handling;
- rejection of inconsistent requested-source provenance.

The F13 fix therefore tightens fresh-candidate admission without inventing a new or broader equivalence rule.

## Production consumer/effect closure

### `UpdateMultipleDownloadsDataWorker`

The real batch metadata worker constructs `ResultRepository` and calls `resultRepo.updateDownloadItem(item)` using the default FRESH_FIRST path before durable Download persistence. It has no separate fresh metadata extraction/application path that bypasses the repository guard.

The worker still has a broader stale-snapshot/full-row publication concern owned by P1 `BUG-METADATA-01` / F14. That distinct root does not invalidate the F13 candidate-trust closure.

### `DownloadWorker.persistDownloadMetadata()`

The real download-success metadata path calls `resultRepo.updateDownloadItem(downloadItem, lookupOrder = CACHE_FIRST)`. It therefore inherits the same guarded fresh fallback. No independent fresh metadata application bypass was found in the production path.

Its stale-row publication semantics likewise remain owned by F14, not F13.

## Production-wiring regression coverage

The added instrumentation test executes the real `ResultRepository` metadata-enrichment boundary with null-default test seams and covers the F13 authority/effect matrix:

1. FRESH_FIRST mismatched candidate contributes nothing;
2. FRESH_FIRST missing/unproven provenance contributes nothing;
3. equivalent YouTube source form is accepted;
4. approved Vimeo redirect identity is accepted;
5. CACHE_FIRST valid cache plus unsafe fresh preserves cache metadata and excludes unsafe fresh fields;
6. CACHE_FIRST cache miss plus mismatched fresh contributes nothing;
7. CACHE_FIRST cache miss plus valid fresh applies metadata;
8. cancellation propagates and does not silently consult/apply fallback metadata.

The test hooks default to `null` and are cleared by test code; production behavior is not redirected unless a test explicitly installs a seam.

## Implementation-agent execution evidence

The implementation agent reported at exact completion SHA `36b43464b8106d90d672ba94718ccee58f38974f`:

- focused JVM tests: **30/30 passed**;
- metadata identity instrumentation: **8/8 passed**;
- existing cache persistence instrumentation: **8/8 passed**;
- full JVM suite: **610/610 passed**;
- KSP: **PASS**;
- debug Kotlin compile: **PASS**;
- Android-test Kotlin compile: **PASS**;
- `git diff --check`: **PASS**;
- emulator: `emulator-5554`, API 36, x86_64;
- Room migration: **NOT REQUIRED**.

An initial unquoted instrumentation argument reportedly failed during Gradle task parsing before test execution; the corrected command then executed successfully. These are implementation-agent execution results and are not represented as independent assistant execution.

## Root reconciliation

Close only P2 `BUG-METADATA-02` / F13 here.

Do not merge it with P1 `BUG-METADATA-01` / F14. F14 owns stale `DownloadItem` snapshots and full-row metadata writers that can overwrite newer non-metadata durable state. F13 supplies the validated-candidate prerequisite that F14 now may rely on.

Preserve independently established closures and classifications absent concrete regression, including F3 `BUG-OBSERVE-01`, F12 `BUG-KEYWORD-01`, History duplicate identity, and P2-B / P2-K / B10.

No Master Plan or authoritative-ledger modification is made by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED