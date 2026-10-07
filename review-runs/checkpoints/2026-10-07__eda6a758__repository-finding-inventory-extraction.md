# Repository finding inventory extraction — 2026-10-07

checkpoint_kind: REPOSITORY_FINDING_LINEAGE_INVENTORY
checkpoint_status: IN_PROGRESS
review_parent_sha: 51a778f54c93523ed8633a2fe514ba0a2858925b
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_preserved: YES
canonical_scope_checkpoint: review-runs/checkpoints/2026-10-07__eda6a758__canonical-download-scope-reconciliation.md
canonical_download_counts: P0=0,P1=0,P2=8
canonical_count_change: NONE

## Raw historical sources

TASKS.md:
- baseline registry entries: 74
- role: historical baseline finding registry
- current disposition is not inferred from its historical State fields.

TASKS_DELTA.md:
- post-split entries: 49
- role: append-only historical finding registry
- current disposition is not inferred from its historical State fields.

Cross-file ID overlap:
- BUG-OBSERVE-04 appears in both TASKS.md and TASKS_DELTA.md.

Therefore:
- raw baseline + delta records: 123
- unique historical IDs from TASKS + TASKS_DELTA: 122

## Current-review IDs absent from the two historical registries

The latest current-source review state contains fourteen IDs not present as registry headings in
TASKS.md or TASKS_DELTA.md:

- CURRENT-PLAYER-TIMELINE-INDEX
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-APP-UPDATE-01
- BUG-APP-UPDATE-02
- BUG-PLAYLIST-DELETE-01
- BUG-COOKIE-RESTORE-01
- WORKER-FOREGROUND-COMPLETION-01
- BUG-STORAGE-ALLFILES-01

Two current roots are already historical-registry IDs:
- BUG-PLAYER-01
- BUG-INCOGNITO-01

Initial lineage candidate-ID population:
- 122 unique TASKS/TASKS_DELTA IDs
- plus 14 later IDs absent from those registries
- total candidate IDs requiring semantic lineage reconciliation: 136

This is NOT a repository canonical-root count.
This is NOT a repository OPEN count.
This is NOT a canonical download count.

Before any project-total count is published, the 136 candidate IDs must be classified for:
- distinct semantic root;
- same-root residual;
- alias/subcase;
- rejected false positive;
- superseded identifier;
- current existence/disposition.

## Current-source audit policy

For every candidate root:
1. do not inherit historical Open/Closed state without evidence;
2. verify against exact implementation eda6a758... or prove a preserved closure through exact closure
   evidence plus relevant-path ancestry/no-change;
3. classify current disposition as VERIFIED_OPEN, VERIFIED_CLOSED, REOPENED, SUPERSEDED_ALIAS,
   FALSE_POSITIVE_REJECTED, or NOT_VERIFIED;
4. when OPEN/REOPENED, record current producer/carrier/consumer/recovery/final-effect contract and
   correction boundary;
5. when REOPENED, distinguish FALSE_CLOSURE / TRUE_REGRESSION / PARTIAL_CLOSURE / NOT_VERIFIED cause
   and record why the former closure was accepted and what evidence invalidates it now.

## Scope guard

Repository finding reconciliation is independent of the fixed current canonical download campaign.
No repository-wide classification in this campaign changes CANONICAL_P* without a later explicit
download-scope inventory decision.

production_source_changed: NO
private_prompt_changed: NO
ledger_changed: NO
