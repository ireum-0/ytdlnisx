# Checkpoint-only historical ID reconciliation — aliases and rejected candidate A

checkpoint_kind: CHECKPOINT_ONLY_FINDING_ID_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: c38ee1d016fee156cbe1b2613b35223492aa76d9
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-DOWNLOAD-HANDOFF-01 — SUPERSEDED_ALIAS of BUG-QUEUE-03

The historical canonical-count reconciliation defines BUG-DOWNLOAD-HANDOFF-01 by:
- runnable Download row commits before WorkManager acceptance;
- DownloadRepository.startDownloadWorker() does not observe ordinary enqueue Operation;
- startup does not reconstruct arbitrary ordinary Queued/due Scheduled work whose carrier was lost.

That is the exact semantic root currently audited as BUG-QUEUE-03.

Current disposition:
BUG-DOWNLOAD-HANDOFF-01 = SUPERSEDED_ALIAS(BUG-QUEUE-03).

Do not count a second project root.

## BUG-GROUP-TXN-01 — SUPERSEDED_ALIAS of BUG-GROUP-01

The historical root is explicitly:
HistoryFragment deletes one Keyword/Youtuber group through multiple independent DAO writes
(members/relations before group row) without one transaction/recovery phase.

That is the exact current root audited as BUG-GROUP-01.

Current disposition:
BUG-GROUP-TXN-01 = SUPERSEDED_ALIAS(BUG-GROUP-01).

Do not count a second project root.

## BUG-HISTORY-FILE-DELETE-TXN-01 — SUPERSEDED_ALIAS of BUG-HISTORY-01

The historical canonical-count reconciliation already states this explicitly:
external file deletion before History DB/relationship mutation is evidence under F17 / BUG-HISTORY-01,
not a distinct semantic root.

Current disposition:
BUG-HISTORY-FILE-DELETE-TXN-01 = SUPERSEDED_ALIAS(BUG-HISTORY-01).

## BUG-HISTORY-UNDO-PLAYLIST-01 — SUPERSEDED_ALIAS of BUG-HISTORY-01

The historical reconciliation explicitly records that record-only Undo restores History + keyword state
without prior playlist memberships, but this is a playlist-membership subcase of F17 / BUG-HISTORY-01.

Current disposition:
BUG-HISTORY-UNDO-PLAYLIST-01 = SUPERSEDED_ALIAS(BUG-HISTORY-01).

## BUG-LOWQUALITY-SAVED-01 — FALSE_POSITIVE_REJECTED

The canonical count chain explicitly records:
low-quality-saved-false-positive-correction.md retracts BUG-LOWQUALITY-SAVED-01.

This identifier therefore remains historical candidate evidence only. It is not a confirmed canonical
production root and must not contribute to project discovered-root totals.

Current disposition:
BUG-LOWQUALITY-SAVED-01 = FALSE_POSITIVE_REJECTED.

## Counting effect

Distinct production roots added by this checkpoint: 0.
Alias identifiers reconciled: 4.
Rejected candidates reconciled: 1.

This checkpoint changes no current download canonical count and does not alter any already-audited root
disposition.
