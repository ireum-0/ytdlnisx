# Checkpoint-only lineage correction — BUG-PLAYLIST-TXN-01

Date: 2026-10-07

checkpoint_kind: REPOSITORY_CHECKPOINT_ONLY_LINEAGE_CORRECTION
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: a4e3ee521177c751242481b7207d240c5a2af036
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
supersedes_lineage_claim_in: review-runs/checkpoints/2026-10-07__eda6a758__checkpoint-only-playlist-txn-link-input-current.md
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8
repository_discovered_canonical_roots_total: NOT_YET_VERIFIED

## Correction

The immediately preceding checkpoint correctly revalidated the production failure but incorrectly classified BUG-PLAYLIST-TXN-01 as a distinct checkpoint-only semantic root.

Registry reverse-check proves the deletion portion is already owned by registry root BUG-PLAYLIST-01 — "Make playlist and playlist-group deletion atomic".

Exact semantic overlap:
- both identify PlaylistRepository.deletePlaylist() as independent playlist-item / playlist-row / playlist-group-member mutations without one Room transaction;
- both require one authoritative atomic relationship-graph mutation and failure/process-death coverage at the former split boundaries;
- both explicitly separate this ownership from BUG-HISTORY-01.

The older BUG-PLAYLIST-TXN-01 checkpoint also widened the same relation-transaction concern to multi-playlist add/remove selection and stale parent relation insertion. Those are same-root residual/subcase extensions of the playlist relationship atomicity/authority boundary, not evidence for a second project root.

Therefore:
BUG-PLAYLIST-TXN-01 -> BUG-PLAYLIST-01
relationship: SAME_ROOT_ALIAS_SUBCASE
distinct_checkpoint_only_root_increment: 0
current disposition of owning root at exact eda6a758: VERIFIED_OPEN P2
current download canonical membership: NO

The current-source proof and correction details in the prior checkpoint remain useful as BUG-PLAYLIST-01 residual evidence; only the distinct-root identity/count claim is corrected.

BUG-LINK-INPUT-01 is unaffected by this correction. Registry reverse-check against BUG-SHARE-01 shows a different root: BUG-SHARE-01 owns destructive clearing of unrelated Result-table state, while BUG-LINK-INPUT-01 owns typed external-input routing before durable Download admission. No registry root examined in this correction owns that route-classification failure, so BUG-LINK-INPUT-01 remains a distinct checkpoint-only VERIFIED_OPEN P2 root.

## Accounting correction

The prior checkpoint's "distinct checkpoint-only production roots classified: 2" must not be used as an additive root count.
Correct semantic-root increment from that checkpoint after this correction: 1, BUG-LINK-INPUT-01 only.

No historical checkpoint is modified. The repository-wide total remains NOT_YET_VERIFIED pending complete checkpoint-only discovery and reverse reconciliation.
