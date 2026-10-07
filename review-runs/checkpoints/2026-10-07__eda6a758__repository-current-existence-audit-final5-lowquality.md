# Repository current-existence audit — final-5 low-quality dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: eadef1dc85890cb50c696999e33da66f12ceae31
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-LOWQUALITY-01 — VERIFIED_OPEN P2, narrowed residual

Historical root:
Download failure could commit Download=Error and then lose/swallow the linked low-quality child/parent
FAILED transition.

Current source has materially improved the root:
- the Download Error write persists exact lastIssueCode/lastIssueStage first;
- linked transition receives that exact reason;
- linked-transition failure calls LowQualityRedownloadLedger.scheduleConvergence(downloadId);
- LowQualityRedownloadRepository.reconcileDownload() derives a nonterminal linked child from the durable
  Download status/lastIssueCode, preserving the exact reason rather than generic failure;
- App startup runs LowQualityRedownloadManager.reconcile(), providing restart convergence.

However the semantic split remains reachable:
- HistoryReplacementTerminalRecovery.persistHistoryReplacementTerminalState() intentionally keeps the
  Download/ledger writes non-atomic;
- if transitionLinkedDownload() throws, onLinkedTransitionFailure is best-effort;
- if scheduling that live convergence also throws, its failure is explicitly swallowed;
- the helper still returns HistoryReplacementPersistenceResult.Persisted;
- therefore the worker may treat the terminal persistence as successful while durable Download=Error is
  visible and the owning low-quality child/operation is still nonterminal until some later reconciliation.

The old exact-reason-loss subcase is closed, but immediate terminal-state coherence is not.

Disposition:
BUG-LOWQUALITY-01 = VERIFIED_OPEN P2.

Narrow correction:
the Download Error commit must either include the linked child/parent terminal transition or durably
publish exact mandatory convergence responsibility whose persistence/acceptance failure prevents the
terminal write from being represented as fully persisted.

## BUG-LOWQUALITY-02 — VERIFIED_OPEN P2, dependent split-state residual

The normal historical path is partially fenced now:
- terminal low-quality child/operation state is detected by hasTerminalHistoryReplacementLedger();
- processDownloadItemToProcessing() refuses ordinary RECONFIGURED conversion when that terminal ledger is
  present.

But BUG-LOWQUALITY-01 still permits a concrete current state where:
- Download is durably Error with exact issue;
- linked low-quality child/operation remains nonterminal because its transition failed;
- the live convergence owner has not yet repaired it or its scheduling failed.

In that state hasTerminalHistoryReplacementLedger() does not reject a coherent nonterminal quality
replacement. The multi-item Errored flow still:
- calls turnDownloadItemsToProcessingDownloads(selectedObjects) with deleteExisting=false;
- processDownloadItemToProcessing() clears item.id to 0 and inserts a new Processing clone;
- DownloadMultipleBottomSheetDialog later calls deleteAllWithID(currentDownloadIDs) on the old Error IDs;
- generic old-row removal can terminalize the linked old child as user-removed;
- the new Processing clone has a different Download ID and no atomic child rebind.

Thus the cross-ID ownership loss remains reachable while low-quality terminal convergence debt exists.

Disposition:
BUG-LOWQUALITY-02 = VERIFIED_OPEN P2.

Narrow correction:
a Download carrying unresolved linked-ledger convergence debt must not enter ID-changing reconfiguration;
or the child ownership must be atomically rebound to the replacement before old-row retirement.

## BUG-LOWQUALITY-03 — VERIFIED_CLOSED

Historical root:
a low-quality child and parent could already be terminal FAILED, then a supported SAME_SETTINGS retry
could requeue the same Download ID while leaving those terminal ledger facts unchanged; later success
deleted the Download but skipped the terminal child.

Current source closes that exact contradiction at both admission and completion:
- retryFailedDownload() calls prepareRetryMetadata(SAME_SETTINGS);
- prepareRetryMetadata includes hasTerminalHistoryReplacementLedger(item) in
  historyReplacementMismatch;
- terminal low-quality child/operation therefore blocks the retry instead of allowing Error->Queued;
- queueDownloads()/reconfigured processing use the same terminal-ledger refusal for quality replacements;
- DownloadRepository.completeAndDeleteInternal() additionally refuses successful quality-replacement
  completion unless the linked child and operation are current nonterminal owners (or an already
  committed exact success).

Therefore the historical terminal-ledger + newer successful retry state is no longer producible through
the supported current retry path.

Disposition:
BUG-LOWQUALITY-03 = VERIFIED_CLOSED.

This does not close BUG-LOWQUALITY-01/02: a nonterminal split state caused by failed ledger convergence is
a different current condition and remains separately owned above.

## Immediate accounting

Lower-bound 136-ID population:
- audited IDs: 134
- VERIFIED_CLOSED/currently-not-reproduced: 81
- VERIFIED_OPEN: 52
- SUPERSEDED_ALIAS: 1
- remaining unaudited IDs: 2

Remaining:
- BUG-TERMINAL-08
- BUG-TERMINATE-01

Current narrow download canonical remains P0=0 / P1=0 / P2=8.
