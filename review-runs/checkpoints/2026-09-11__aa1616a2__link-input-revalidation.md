# BUG-LINK-INPUT-01 — exact-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Newer implementation diff used as exploratory evidence: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, §9.6
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior root checkpoint: `review-runs/checkpoints/2026-09-10__c2294c87__link-share-input-admission.md`

## Verdict

**NOT_CLEAN — existing P2 `BUG-LINK-INPUT-01` is reconfirmed OPEN at `aa1616a2...`.**

The production Share direct-download path still bypasses the application's typed `WebUrlInput` routing/normalization contract before durable Download admission.

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 3 / P1 3 / P2 25**
- CLEAN Review Basis remains: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Exact external-input path

`ShareActivity.handleIntents()` accepts `ACTION_SEND` or `ACTION_VIEW` and obtains:

- `Intent.EXTRA_TEXT` for SEND;
- `intent.dataString` for VIEW.

Null/blank input is rejected, but a nonblank value is passed to:

`data.extractURL()`

and `String.extractURL()` delegates to `LinkUtil.extractFirstUrl()`.

## `extractFirstUrl()` still collapses semantic classes

At exact `aa1616a2...`, `LinkUtil.extractFirstUrl()`:

- regex-matches the first explicit `http://`, `https://`, or `ftp://` URL and trims common trailing punctuation;
- if no explicit URL match exists, returns `text.trim()` unchanged.

Therefore the Share input helper still does not return a typed distinction such as URL-found / search-text / unsupported scheme / malformed input.

Important consequences remain:

1. `example.com/path` has no explicit-scheme regex match and is returned unchanged;
2. arbitrary prose/search text with no URL is returned unchanged;
3. `ftp://...` is accepted by the Share extraction regex as a URL candidate.

## Existing typed contract remains stronger and is bypassed

`WebUrlInput.routeInput()` at the same basis distinguishes:

- supported HTTP/HTTPS or valid scheme-less web address -> `Extractor`;
- scheme-less arbitrary text -> `SearchQuery`;
- unsupported explicit scheme -> `UnsupportedExplicitScheme`.

`resolveExtractorInput()` normalizes a valid scheme-less web address to an HTTPS dispatch value before extractor use.

Thus, for the same input:

- `example.com/path` is semantically an extractor URL with dispatch value `https://example.com/path`;
- ordinary query text is a `SearchQuery`;
- FTP is not a supported extractor scheme.

The normal metadata/result yt-dlp path consumes this typed contract in `YTDLPUtil.getFromYTDLInternal()`: extractor input uses the normalized dispatch value, search text is routed through the configured search extractor, and unsupported explicit schemes return no result.

Share direct-download admission does not use `WebUrlInput.routeInput()` before persistence.

## Durable Download admission remains reachable

`ShareActivity` looks up cached Result rows by the untyped `inputQuery`. If there is not exactly one cached result it calls:

`downloadViewModel.createEmptyResultItem(inputQuery)`

For the direct/background branch (`download_card` disabled or the quick-run-background alias), it then calls:

`createDownloadItemFromResult(result = result, ...)`

followed by:

`queueDownloads(listOf(downloadItem))`.

The production `createDownloadItemFromResult()` constructs `DownloadItem` with `resultItem.url` as its URL. The Share fallback ResultItem therefore carries the untyped `inputQuery` directly into `DownloadItem.url`.

`queueDownloads()` persists new queued items through the production repository and then starts/schedules Download work. No `WebUrlInput` route is introduced at this durable admission boundary.

## Native producer boundary still consumes the raw durable source

The download-side yt-dlp request builder uses the persisted Download source directly for ordinary non-playlist input:

- blank URL -> empty request;
- otherwise -> `YoutubeDLRequest(downloadItem.url)`.

It does not re-run the result/search `WebUrlInput.routeInput()` contract before constructing the native download request.

The current production mismatch is therefore still:

`ACTION_SEND/ACTION_VIEW external text`
→ `extractFirstUrl()`
→ no typed route / raw fallback
→ fallback `ResultItem.url`
→ durable Queued `DownloadItem.url`
→ Download execution
→ `YoutubeDLRequest(downloadItem.url)`.

## Concrete semantic failures

### Scheme-less supported web address

`example.com/path` should enter the application's typed extractor contract and normalize to `https://example.com/path`. Direct Share instead durably admits the unnormalized spelling as the Download source.

### Search text / no URL

Ordinary text is `SearchQuery` under the normal input contract. A quick/direct-download surface may either resolve it through the configured search/result-selection contract or explicitly reject it, but it must not silently reinterpret the entire raw text as a direct producer source. Current Share does exactly that.

### Unsupported explicit scheme

`ftp://...` is matched by `LinkUtil` but is an unsupported explicit extractor scheme under `WebUrlInput`. Direct Share can therefore admit a source that the normal input contract would reject.

The finding does not depend on a specific yt-dlp/native error. The correctness break occurs when external input is durably admitted under a semantic class different from the application's own typed routing contract.

## Preserved non-blocking observations

The original root does not require changing deterministic first-explicit-URL behavior for prose containing a supported URL, nor does it require canonical media identity work from Master Plan §9.7. The defect is earlier: external input extraction/routing before durable Download admission.

## Root reconciliation

This remains existing P2 `BUG-LINK-INPUT-01`, counted once.

It is distinct from:

- `BUG-DOWNLOAD-HANDOFF-01`: Queued row -> WorkManager acceptance/recovery;
- `BUG-OBSERVE-SOURCE-IDENTITY-01`: Observe source semantic uniqueness and concurrent Observe publication;
- closed P2-K command/source-token duplicate normalization;
- closed B10/archive identity;
- canonical media/source identity after an input has already been semantically admitted.

## Required correction boundary

- Route every supported Share external text surface through one explicit typed extraction/routing result before durable Download mutation.
- Preserve a deliberate first-URL rule for prose/multiple URLs if desired, but represent no-URL/search-text, unsupported explicit scheme, malformed, blank, and accepted extractor input distinctly.
- Normalize accepted scheme-less web addresses through the `WebUrlInput` extractor contract before storing Download source identity.
- On a direct/quick-download surface, either resolve SearchQuery through the normal search/result-selection contract or reject/prompt explicitly; never persist arbitrary search text as a raw direct source.
- Reject unsupported/malformed inputs before a Queued Download row or worker ownership is published.
- Do not absorb downstream canonical media identity into this fix.

Required production regressions should cover ACTION_SEND/ACTION_VIEW as applicable for:

- explicit HTTP(S);
- URL in surrounding prose;
- deterministic multiple-URL selection;
- valid scheme-less web address -> normalized durable source;
- arbitrary search text -> search contract or explicit no-queue rejection;
- unsupported explicit scheme -> no durable Download / no worker handoff;
- blank/null input -> no durable Download;
- fresh onNewIntent input remains attributed to the new intent.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review. Source-level production wiring was reviewed at the exact fixed basis.

INDEPENDENT EXECUTION: NOT EXECUTED
