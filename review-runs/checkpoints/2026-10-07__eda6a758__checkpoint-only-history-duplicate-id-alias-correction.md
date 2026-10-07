# Checkpoint-only lineage correction — BUG-HISTORY-DUPLICATE-IDENTITY-01

Date: 2026-10-07

checkpoint_kind: REPOSITORY_CHECKPOINT_ONLY_LINEAGE_CORRECTION
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: 6e13054c9c2fbaa360f141824310b01109b85129
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
supersedes_lineage_claim_in: review-runs/checkpoints/2026-10-07__eda6a758__checkpoint-only-duplicate-history-cleanup-current.md
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8
repository_discovered_canonical_roots_total: NOT_YET_VERIFIED

## Correction

BUG-HISTORY-DUPLICATE-IDENTITY-01 is not a distinct checkpoint-only semantic root.

Owning registry root:
BUG-HISTORY-04 — Do not identify destructive History duplicates by title alone

Relationship:
BUG-HISTORY-DUPLICATE-IDENTITY-01 -> BUG-HISTORY-04
classification: SAME_ROOT_ALIAS / LATER_DESCRIPTIVE_ID
distinct_checkpoint_only_root_increment: 0
current disposition: VERIFIED_CLOSED
current download canonical membership: NO

Exact semantic equality:
- BUG-HISTORY-04 owns History "Remove duplicates" choosing destructive equivalence by display title alone;
- BUG-HISTORY-DUPLICATE-IDENTITY-01 owns the same selection failure and its closure through typed destructive identity rather than title equality;
- both terminate at the same destructive duplicate grouping boundary before History deletion;
- this remains separate from BUG-HISTORY-01 relationship/Undo atomicity and from HISTORY-CONTENT-AUTHORITY-ALIAS-01 provider-authority key loss.

The existing closure evidence remains valid:
HistoryDuplicateIdentity now derives typed destructive identity, preserves source/provider distinctions, and fails closed for ambiguous/unprovable identity. The scheduler-only implementation delta to eda6a758 does not reopen that path.

## Accounting correction

The prior checkpoint's aggregate "distinct checkpoint-only production roots classified: 3" must not be additively interpreted as three new project roots.
Within that checkpoint:
- BUG-DUPLICATE-ADMISSION-01 remains distinct CLOSED;
- CLEANUP-MAIN-THREAD-BLOCK-01 remains distinct CLOSED;
- BUG-HISTORY-DUPLICATE-IDENTITY-01 is an alias of registry root BUG-HISTORY-04 and adds zero roots.

No historical evidence is modified. Repository total remains NOT_YET_VERIFIED pending completion of remaining checkpoint-only discovery/reconciliation.
