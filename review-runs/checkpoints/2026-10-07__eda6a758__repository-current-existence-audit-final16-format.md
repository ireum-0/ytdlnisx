# Repository current-existence audit — final-16 format dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 2d4f98e78dbf8edc3bffbea8cef50b55d0cdb39f
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-FORMAT-01 — SUPERSEDED_ALIAS of BUG-FORMAT-BG-03

Historical BUG-FORMAT-01 and current canonical BUG-FORMAT-BG-03 own the same production semantic root:
background multi-Download format refresh discards per-item failure/cancellation truth, can split Result
and Download persistence, increments completion despite failure, returns batch success, and publishes the
updated-formats notification.

Exact eda6a758 still shows the same chain:
- UpdateMultipleDownloadsFormatsWorker wraps extraction/selection/Result write/Download write in
  runCatching and discards its Result;
- count advances regardless;
- Result.success() is returned after the loop;
- finally emits showFormatsUpdatedNotification for the requested IDs.

The later BG-03 correction contract is strictly more complete: typed extractor outcomes, cancellation,
Result/Download consistency, authoritative ALREADY_SATISFIED proof, mixed-batch aggregation, and truthful
notification/result semantics.

Disposition:
BUG-FORMAT-01 = SUPERSEDED_ALIAS(BUG-FORMAT-BG-03).

Counting:
- historical ID remains in the project history;
- do not count it as a second current open semantic root;
- the active download canonical root remains BUG-FORMAT-BG-03 only.

## BUG-FORMAT-03 — VERIFIED_OPEN P2

Exact eda6a758 still reproduces the selected-ID -> URL-wide destructive broadening:
- DownloadMultipleBottomSheetDialog receives unavailable callback for the current selected format set;
- onItemUnavailable(url) calls DownloadViewModel.removeUnavailableDownloadAndResultByURL(url);
- that helper calls DownloadRepository.deleteProcessingByUrl(url);
- DownloadDao executes DELETE FROM downloads WHERE status='Processing' AND url=:url.

Therefore selected Processing row A with URL U can authorize deletion of unselected Processing sibling B
with the same URL but different ID/configuration.

Disposition:
BUG-FORMAT-03 = VERIFIED_OPEN P2.

Correction boundary:
- preserve exact selected Download IDs through unavailable handling;
- destructive Download cleanup must require exact ID + expected current Processing state;
- URL-wide Result-cache invalidation, if desired, must remain separate from Download-row authority;
- same-URL unselected siblings must survive;
- partial failure/restart must retain exact selected identity rather than widen to URL.

## Immediate accounting

Lower-bound 136-ID population:
- audited IDs: 122
- VERIFIED_CLOSED/currently-not-reproduced: 76
- VERIFIED_OPEN: 45
- SUPERSEDED_ALIAS: 1
- remaining unaudited IDs: 14

Current narrow download canonical remains P0=0 / P1=0 / P2=8.
