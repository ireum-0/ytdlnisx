# BUG-COOKIE-03 clean-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 0553444563df45da7dcafb8999c7f0e5618716ca
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
live_completed_implementation_head: 21a04168286ae6562adbf219b8ea6a03984a2e25
tooling_completion_review: 0553444563df45da7dcafb8999c7f0e5618716ca
tooling_completion_finding: BUG-TOOLING-01 OPEN P2
tooling_diff_used_as_cookie_evidence: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L1 Durability & recovery DEEP
supporting_lenses:
- L3 Concurrency & authority
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Current source result

The core defect is unchanged from its original reviewed checkpoint.

`WebViewActivity.kt` and `CookieViewModel.kt` are byte-identical at the original BUG-COOKIE-03 checkpoint `ad1a8f026a7a05f3e1489775a74d8106dbfa510e` and the current CLEAN basis.

The Generate action still executes:

`cookiesViewModel.getCookiesFromDB(url).getOrNull()?.let { ... }`

and then unconditionally posts `RESULT_OK` and finishes. A missing WebView cookie DB, empty cookie database, open/query failure, or parsing failure is therefore converted to a skipped persistence block followed by success.

`CookieViewModel.insert()` is suspend/awaited, but `updateCookiesFile()` returns a newly launched `viewModelScope.launch(Dispatchers.IO)`. The caller cannot await its write, observe its exception, or prevent Activity destruction from cancelling the projection job.

Both retrying ActivityResult consumers still interpret `RESULT_OK` as credential readiness:
- `HomeFragment` sets `use_cookies=true` and immediately calls `startSearch()`;
- `DownloadBottomSheetDialog` sets `use_cookies=true` and immediately calls `initUpdateData()`.

The use of `RestoreMutationAdmission.applyOrdinaryPreferences()` around those preference writes changes restore admission, not cookie-readiness semantics.

The downstream extractor boundary also remains fail-open with respect to readiness. `YTDLPUtil.applyDefaultOptionsForFetchingData()` checks `use_cookies`, then calls `FileUtil.getCookieFile(context)`; that callback simply does nothing when `cookies.txt` is absent. The request then proceeds without `--cookies` even though persistent configuration claims cookie authentication is enabled.

## Exact invariant

A WebView cookie-acquisition attempt is successful only when one exact acquisition generation has:

1. successfully extracted the intended WebView cookie material;
2. durably inserted/updated the intended Room cookie record;
3. completely and successfully projected the enabled cookie set to the runtime cookie file;
4. returned a success handoff bound to that same acquisition request/generation.

Only that success handoff may authorize a caller to persist `use_cookies=true` and immediately retry the failed authenticated operation.

`use_cookies=true` is configuration intent, not proof that a usable runtime cookie projection exists. If runtime readiness is missing or cannot be proven, an authentication-aware request must not silently continue as though cookie authentication succeeded.

## Narrow implementation boundary

Keep WebView cookie extraction and the existing Room cookie model. Do not merge this root with BUG-COOKIE-01 revocation semantics or BUG-COOKIE-02 clipboard-export reporting.

Introduce one suspending, typed cookie projection core below the Activity-scoped ViewModel launch boundary. The core should serialize whole-file projection so two acquisitions or edits cannot write stale snapshots out of order, and return an explicit READY/FAILED result with an opaque projection/acquisition generation.

The acquisition path should use one awaitable operation that:
- consumes the exact requested URL/description plus extracted content;
- upserts the Room record;
- awaits the serialized runtime projection;
- verifies the projected runtime file is present/usable for the completed generation;
- returns a success token only after those steps complete.

If Room persistence succeeds but projection fails, keep the durable Room row as recoverable source state and report acquisition failure. Do not attempt a destructive rollback that could erase a prior valid row merely to simulate cross-store atomicity. A later explicit acquisition/projection retry may converge it.

Pass an opaque acquisition/request token into `WebViewActivity` and return it only with the successful projection result. `HomeFragment` and `DownloadBottomSheetDialog` must validate the returned token against the currently pending acquisition before setting `use_cookies=true` or retrying. A stale Activity result must not authorize a newer request.

The runtime consumer boundary must distinguish COOKIE_READY from COOKIE_UNAVAILABLE when `use_cookies=true`. A missing/unusable `cookies.txt` must surface as an authentication-readiness failure (or force an explicit successful reprojection) rather than silently omitting `--cookies`.

`PoTokenWebViewLoginActivity` uses the same detached projection helper and currently sets `use_cookies=true` immediately afterward. Its token-generation RESULT_OK may remain semantically independent if that is the intended contract, but the cookie-ready preference side effect must use the same awaited projection primitive rather than publishing readiness before the projection completes.

`CookiesFragment` may continue to launch WebView acquisition without consuming an Activity result, but WebView acquisition failure must remain visibly retryable instead of finishing as a false success.

## Forbidden shortcuts

- changing only the caller to delay `startSearch()` by time
- leaving `updateCookiesFile()` detached and polling for `cookies.txt`
- treating Room insert success alone as cookie readiness
- treating `use_cookies=true` alone as proof that the runtime file exists
- returning RESULT_OK after `getOrNull()` discarded an extraction failure
- swallowing projection write/cancellation failure and still enabling cookies
- using a URL string alone as the stale-result fence when multiple acquisitions of the same URL can exist
- weakening BUG-COOKIE-01/02 ownership boundaries by folding unrelated revocation/export behavior into this fix

## Acceptance matrix

- WebView reports no cookies: no Room mutation, no RESULT_OK, no `use_cookies=true`, no automatic retry
- WebView cookie SQLite file missing/unreadable/query failure: same fail-closed behavior
- Room insert/update failure: projection is not reported ready and caller does not retry
- projection write failure after successful Room upsert: Room row may remain, but Activity stays failed/retryable and caller does not enable cookies
- lifecycle cancellation after Room upsert but before projection completion: no success handoff survives
- projection is latched after Room upsert: prove RESULT_OK, `use_cookies=true`, `startSearch()`, and `initUpdateData()` cannot occur before release/completion
- two concurrent acquisitions A then B: B's successful generation cannot be overwritten or authorized by stale A completion; stale A ActivityResult is rejected
- repeated acquisition for the same URL: opaque request/generation identity, not URL equality alone, decides which result is current
- activity recreation/back/cancel preserves failure vs success and cannot manufacture a committed result
- process restart with `use_cookies=true` but missing/unusable runtime file: authenticated request fails closed or explicitly reprojections before execution; it does not silently omit `--cookies`
- complete success: returned handoff, Room row, runtime file, caller preference, and immediate retry all agree on ready state
- DownloadBottomSheet consumes the same success contract as Home
- PoToken cookie side-effect cannot set `use_cookies=true` before its cookie projection is authoritative
- production-path coverage exercises real ActivityResult callers and the generated cookie-file consumer, not only cookie parsing

No focused production regression was found in the bounded review that proves the WebView ActivityResult -> Room -> runtime-cookie-file -> immediate retry barrier. Existing source still exposes the original ordering gap.

The tooling wave at `21a04168286ae6562adbf219b8ea6a03984a2e25` is already covered by the later canonical tooling review at `0553444563df45da7dcafb8999c7f0e5618716ca`; its BUG-TOOLING-01 finding is separate from this cookie root.
