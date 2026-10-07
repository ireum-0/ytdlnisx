# Checkpoint-only production reconciliation — HardSub generation

checkpoint_kind: CHECKPOINT_ONLY_PRODUCTION_FINDING_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 18f1fee39e0ee298f7463d82622dd6cdc176c19a
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-HARDSUB-GENERATION-01 — VERIFIED_OPEN P2

This is a distinct historical checkpoint-only production root and is not one of the four registry
BUG-HARDSUB-01..04 roots.

Current exact eda6a758 source still reproduces both halves.

Generation authority:
- prepareHardSub() creates an exact durable HARD_SUB_SCAN handoff/request generation;
- HardSubScanWorker.doWork() does not read handoffId/requestId/generation input;
- it performs History scan-state writes and replacement publication without proving its generation is
  still current;
- a superseded already-running worker can therefore continue semantic effects after a newer Scan Now
  generation exists.

Atomic replacement reservation:
- for HistoryRedownloadMarker.regular(historyId), the worker separately executes
  countPendingByPlaylistMarker(marker);
- after observing zero it later creates a DownloadItem and calls downloadDao.insert(downloadItem);
- no unique database key or transactional marker reservation connects the zero-count observation to the
  insert;
- overlapping old/new generations can both observe zero and publish two replacement Download rows for
  the same History-redownload marker.

Disposition:
BUG-HARDSUB-GENERATION-01 = VERIFIED_OPEN P2.

Keep distinct from:
- BUG-HARDSUB-01 typed subtitle lookup completeness;
- BUG-HARDSUB-02 replacement hard-sub guarantee propagation;
- BUG-HARDSUB-03 destructive merge replacement;
- BUG-HARDSUB-04 negative-scan invalidation after configuration/source change.

Correction boundary:
1. retain exact current HardSub generation through semantic worker completion;
2. validate current generation before every History scan-state mutation and replacement publication;
3. superseded workers exit without semantic effects;
4. make History-redownload replacement reservation atomic by semantic marker identity;
5. preserve the same authority through restart/retry;
6. test overlapping E1/E2 and simultaneous zero-count publication deterministically.

Project inventory effect:
- add one distinct checkpoint-only production root;
- current state: OPEN P2;
- current download canonical inventory: unchanged.

No production source, implementation prompt, active implementation scope, Master Plan or ledger changed.
