# BUG-LINK-INPUT-01 — current-basis Share/input admission revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `9e6cbc775459111da26e21ca7b8f8519a9aadbc8` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation work is outside this fixed basis; no moving implementation diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, §9.6
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

`YTDLPUtil.kt` changed materially in `aa1616a2... -> 9c5191c3...` for F3 typed source extraction and now visibly uses `WebUrlInput.routeInput()` for result/source-list fetching. This review therefore checks whether that stronger routing contract also reached the external Share -> durable Download -> native producer path.

## Verdict

**NOT_CLEAN — existing P2 `BUG-LINK-INPUT-01` remains OPEN at `9c5191c3...`.**

The F3 changes strengthened metadata/source-list extraction input routing but did not move typed `WebUrlInput` admission in front of the Share direct-download durable mutation.

- Canonical blocker-count delta: **0**
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## 1. External Share input still enters through an untyped extraction helper

Current `ShareActivity.handleIntents()` accepts ACTION_SEND / ACTION_VIEW and selects:

- `Intent.EXTRA_TEXT` for SEND;
- `intent.dataString` for VIEW.

After rejecting null/blank input it still executes:

`val inputQuery = data.extractURL()`.

`Extensions.String.extractURL()` still delegates directly to:

`LinkUtil.extractFirstUrl(this)`.

No `WebUrlInput.routeInput()` result is created or consumed at this external-input admission boundary.

## 2. `LinkUtil.extractFirstUrl()` still collapses different semantic input classes

At exact `9c5191c3...`, the regex still accepts the first explicit:

- `http://...`;
- `https://...`;
- `ftp://...`.

If the regex finds no explicit URL, the helper returns the entire trimmed input text unchanged.

Therefore the Share input path still cannot distinguish:

- accepted extractor URL;
- valid scheme-less web address;
- search/query text;
- unsupported explicit scheme;
- malformed nonblank text.

Concrete current examples remain:

- `example.com/path` -> returned unchanged;
- ordinary prose/search query -> returned unchanged;
- `ftp://host/path` -> accepted as the extracted URL candidate.

## 3. The application's typed contract remains stronger

Current `WebUrlInput.routeInput()` still distinguishes:

- supported HTTP/HTTPS or valid scheme-less web address -> `Extractor`;
- no explicit scheme but not a supported web address -> `SearchQuery`;
- explicit unsupported scheme -> `UnsupportedExplicitScheme`.

`resolveExtractorInput()` normalizes a valid scheme-less web address to an HTTPS dispatch value.

FTP participates only in comparison-key parsing; it is not in the supported extractor scheme set (`http`, `https`).

Thus the same input has a different semantic classification under the application-wide typed routing contract than under the Share helper.

## 4. F3 YTDLPUtil routing does not repair Share durable admission

The current F3-modified `YTDLPUtil.getFromYTDLInternal()` now routes its `query` through `WebUrlInput.routeInput()`:

- Extractor -> normalized dispatch value;
- SearchQuery -> configured search behavior;
- unsupported explicit scheme -> no result.

That is the correct stronger contract for metadata/result/source-list fetching.

However Share direct/background download admission does not call this result-fetch path before creating the Download row. The durable source is constructed earlier from the untyped Share fallback.

Therefore adding typed routing to source-list/metadata fetch did not close the direct-download external-input boundary.

## 5. Untyped Share value still becomes durable Download source identity

`ShareActivity` queries cached Results by the untyped `inputQuery`. If there is not exactly one cached result it calls:

`downloadViewModel.createEmptyResultItem(inputQuery)`.

`createEmptyResultItem(url)` stores that value directly as `ResultItem.url`.

For the direct/background path, `createDownloadItemFromResult(...)` constructs `DownloadItem` with:

`resultItem.url`

as the Download URL.

`queueDownloads()` then transitions/persists queued items through the production repository before starting Download work. It does not insert a `WebUrlInput` routing/normalization step before durable publication.

The exact chain therefore remains:

`ACTION_SEND / ACTION_VIEW external text`
→ `extractURL()` / `LinkUtil.extractFirstUrl()`
→ untyped `inputQuery`
→ fallback `ResultItem.url`
→ `DownloadItem.url`
→ durable Queued Download row.

## 6. Native Download producer still consumes the raw durable source directly

Current download-side `YTDLPUtil.buildYoutubeDLRequest(downloadItem, ...)` still contains the ordinary non-playlist branch:

- blank `downloadItem.url` -> empty request;
- otherwise -> `YoutubeDLRequest(downloadItem.url)`.

It does not re-run `WebUrlInput.routeInput()` on the already-persisted Download source before constructing the native download request.

Therefore the complete production mismatch remains:

`external Share text`
→ weaker untyped Share classification
→ durable Download source
→ native producer consumes that raw source directly.

## Concrete current semantic failures

### Scheme-less supported web address

`example.com/path` is a valid typed extractor input whose dispatch value should be normalized to `https://example.com/path`. Share direct admission persists the unnormalized value instead.

### Search text / no URL

Ordinary text is a `SearchQuery` under `WebUrlInput`, but Share direct/background admission can persist the whole text as if it were a direct producer source. A quick-download surface must either route it through an explicit search/result selection contract or reject/prompt before queue publication.

### Unsupported explicit scheme

`ftp://...` is selected by `LinkUtil.extractFirstUrl()` but is `UnsupportedExplicitScheme` for extractor dispatch. Share can therefore persist/execute a source that the typed contract rejects.

The finding does not depend on yt-dlp subsequently succeeding or failing. The correctness break is the durable semantic misclassification before the native producer boundary.

## Root reconciliation

Keep this as existing canonical P2 `BUG-LINK-INPUT-01`, counted once.

It owns external Share/text extraction and typed routing before durable Download admission.

Keep distinct from:

- P2 `BUG-DOWNLOAD-HANDOFF-01`, which begins after a valid runnable Download row exists and owns WorkManager acceptance/recovery;
- P2 `BUG-OBSERVE-SOURCE-IDENTITY-01`, which owns Observe source semantic uniqueness and concurrent Observe publication;
- downstream canonical media/source identity once an input has already been validly admitted;
- closed command duplicate-token/archive identity roots.

## Correction boundary remains

1. route every supported Share external text surface through one explicit typed extraction/routing result before durable Download mutation;
2. preserve deterministic first-URL extraction for prose if desired, but represent URL-found, search text/no URL, unsupported scheme, malformed, and blank distinctly;
3. normalize accepted scheme-less web addresses using the same `WebUrlInput` extractor contract before storing Download source identity;
4. for SearchQuery on a quick/direct surface, either resolve through the normal search/result-selection contract or explicitly refuse/prompt; do not persist raw search text as a direct Download source;
5. reject unsupported/malformed inputs before a Queued row or worker handoff exists;
6. preserve F3 metadata/source-list routing while avoiding a duplicate/parallel input-classification implementation.

Required production regressions remain ACTION_SEND/ACTION_VIEW coverage for explicit HTTP(S), URL in prose, deterministic multiple-URL selection, scheme-less web normalization, search text behavior, unsupported explicit scheme refusal, null/blank refusal, and `onNewIntent` attribution.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
