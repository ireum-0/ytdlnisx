# Independent Track A checkpoint — Link/share input admission

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Review branch HEAD observed before this checkpoint: `8aa209148d2e3945e0dbbbb61caf9348af9cbb3c`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, §9.6 Link input/share handling
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## NEW P2 — `BUG-LINK-INPUT-01`

### Invariant

Every external text/share entry surface must resolve input through one explicit semantic input contract before durable Download admission. Supported web addresses must be normalized consistently, search text must follow the product's search route or be explicitly rejected on a direct-download surface, and unsupported/malformed input must not become a runnable raw Download source merely because URL extraction failed.

This is the Master Plan §9.6 boundary: null/blank/malformed payloads are normal rejected-input cases, supported surfaces use one extraction/normalization contract, and the fix must not widen arbitrary text into a URL-like direct source.

### Producer / external input surface

At the exact basis, `ShareActivity` handles `ACTION_SEND`/`ACTION_VIEW`. For `ACTION_SEND text/plain` it reads `Intent.EXTRA_TEXT`, rejects only null/blank payload, then computes:

`val inputQuery = data.extractURL()`

`String.extractURL()` delegates to `LinkUtil.extractFirstUrl(text)`.

Exact `LinkUtil` behavior:

- scheme-bearing `http://`, `https://`, or `ftp://` text is matched and the first match is returned after trailing-punctuation trimming;
- if no regex match exists, `extractFirstUrl()` returns `text.trim()` unchanged.

Therefore no-match is not a typed `no URL` result. It silently changes meaning from `URL extraction failed` to `the entire arbitrary text is the direct source candidate`.

### Existing semantic input contract that Share bypasses

`WebUrlInput` already defines the application's typed web/search routing contract:

- HTTP/HTTPS and valid scheme-less web addresses are `Extractor` inputs;
- a valid scheme-less address is normalized to an HTTPS `dispatchValue`;
- scheme-less arbitrary text is `SearchQuery`;
- unsupported explicit schemes are `UnsupportedExplicitScheme`.

The normal metadata/result path, `YTDLPUtil.getFromYTDLInternal()`, consumes that route:

- `Extractor` uses the normalized dispatch value;
- `SearchQuery` is converted to the configured search extractor (`ytsearch...`, `ytsearchmusic...`, etc.);
- unsupported explicit schemes return no result;
- HTTP(S) data-fetch input passes `validateDataFetchUrl()`, which rejects blank values, option-like `-` prefixes, whitespace/newlines, blocked yt-dlp path/config options, and non-HTTP(S) values.

The Share direct-download path does not use this contract before persistence.

### Durable carrier

In `ShareActivity`, when no unambiguous cached result exists, the fallback `inputQuery` becomes `createEmptyResultItem(inputQuery)`.

For the background/direct path (`download_card=false` or `quick_run_background=true`), that ResultItem is converted into a `DownloadItem`; `createDownloadItemFromResult()` copies `resultItem.url` into the Download row and creates it as `Queued`.

`queueDownloads()` then persists a new row through `DownloadRepository.update()` -> `DownloadDao.insert()` and starts/schedules Download work. There is no intervening `WebUrlInput` route/normalization gate at this durable mutation boundary.

This path is production-reachable:

- `AndroidManifest.xml` exports `ShareActivity` for `ACTION_SEND` with `text/plain`;
- `quickDownloadShareAlias` targets the same activity for `text/plain` and sets `quick_run_background=true`;
- `GeneralSettingsFragment` can enable/disable that alias through the user-visible quick-download-share preference.

### Consumer / execution boundary

`DownloadWorker` consumes the persisted Download and calls production `YTDLPUtil.buildYoutubeDLRequest(downloadItem=...)`.

For an ordinary item with no playlist authority, the builder does not re-run `WebUrlInput.routeInput()`. It directly constructs:

`YoutubeDLRequest(downloadItem.url)`

The resulting request proceeds to the worker's native yt-dlp execution path.

Thus the semantic mismatch crosses the full production chain:

`ACTION_SEND text/plain`
-> Share extraction fallback
-> raw `ResultItem.url`
-> durable Queued `DownloadItem.url`
-> Download worker
-> raw `YoutubeDLRequest(downloadItem.url)`
-> native producer execution.

### Concrete correctness impact

The app already distinguishes three semantic classes, but Share direct admission collapses them:

1. A scheme-less supported web address such as `example.com/path` should become normalized extractor input such as `https://example.com/path`; direct Share persists and executes the unnormalized spelling instead.
2. Ordinary text such as a search query is `SearchQuery` in the normal input contract and should use the configured search route, or be explicitly rejected if quick/direct download does not support search. Direct Share instead persists the text as the producer source argument.
3. An unsupported explicit source such as an `ftp://...` value can be extracted by `LinkUtil` even though `WebUrlInput` does not accept FTP as extractor input; text Share can therefore bypass the normal unsupported-scheme disposition.

This finding does not depend on asserting a particular yt-dlp error message or native-library outcome. The correctness failure is established earlier: an external input with one product semantic class is durably admitted and executed under a different direct-source class, contrary to the application's own routing contract and Plan §9.6.

### Reviewed non-findings in the same boundary

- Surrounding prose: the URL regex extracts the URL rather than including surrounding text.
- Trailing punctuation: `trimSharedUrl()` removes common trailing sentence punctuation while preserving balanced URL punctuation.
- Multiple URLs: `extractFirstUrl()` deterministically selects the first match; this is an explicit first-match helper contract and is acceptable under §9.6's requirement for an explicit product rule.
- `MainActivity.onNewIntent()`: the exact new intent is passed to `setIntent(intent)` and `handleIntents(intent)`; no stale prior-input reuse was found in this path.
- Null/blank `ShareActivity` payload: the activity exits rather than queuing a blank source.

These subcases do not add blocker count.

### Root reconciliation

`BUG-LINK-INPUT-01` is distinct from:

- `BUG-DOWNLOAD-HANDOFF-01`: durable runnable Download -> accepted WorkManager ownership;
- `BUG-OBSERVE-SOURCE-IDENTITY-01`: semantic uniqueness of Observe source rows and concurrent Observe publication;
- P2-K: role-aware command/source token identity;
- P2-B: producer generation/archive/publication authority;
- §9.7 canonical media/source identity after a source has already been semantically admitted.

This root owns the earlier external-input -> semantic-route -> durable Download-admission boundary.

### Acceptance direction

- Route Share text through one typed extraction/routing result before any durable Download mutation.
- Preserve deterministic first-URL behavior if that remains the product rule, but distinguish `URL found`, `search text/no URL`, `unsupported explicit scheme`, malformed, null, and blank cases.
- Normalize accepted scheme-less web addresses through `WebUrlInput` before they become durable Download source identity.
- A SearchQuery on a quick/direct-download surface must either be resolved through the configured search/result-selection contract or be explicitly rejected/prompted; it must not silently become a raw direct producer source.
- Reject unsupported/malformed direct-share inputs before inserting a Queued Download or publishing worker ownership.
- Add production-wiring coverage from `ACTION_SEND text/plain` through Share handling and durable admission for: plain HTTP(S), surrounding prose, multiple URLs, scheme-less web address, arbitrary search text, unsupported explicit scheme, null/blank input, and fresh `onNewIntent` input.
- For rejected direct-share inputs, prove no durable Download row and no Download-worker handoff are created. For accepted inputs, prove the exact normalized source carried into the durable row.

## Count bookkeeping

`canonical-count-reconciliation-3.md` corrected the pre-finding count to:

`P0 2 / P1 3 / P2 26`.

`BUG-LINK-INPUT-01` is one new distinct P2 root:

`P0 2 / P1 3 / P2 27`.

Overall verdict remains `NOT_CLEAN`.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
