# Repository-wide semantic-root recount — cross-population alias correction

Date: 2026-10-07

checkpoint_kind: REPOSITORY_SEMANTIC_ROOT_RECOUNT_CORRECTION
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: d078c663d57466d6816e901157e933f8ca671378
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
supersedes_count_only_claim_in: review-runs/checkpoints/2026-10-07__eda6a758__repository-semantic-root-recount-final.md
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

REPOSITORY_DISCOVERED_CANONICAL_ROOTS_TOTAL=148
REPOSITORY_SEMANTIC_ROOTS_CURRENT_OPEN=57
REPOSITORY_SEMANTIC_ROOTS_CURRENT_CLOSED=91

## Correction reason

The preceding recount treated registry-derived BUG-OBSERVE-04 and checkpoint-only BUG-OBSERVE-HANDOFF-01 as two project roots. Historical lineage proves they are the same semantic root.

Authoritative lineage:
- 2026-09-12__36b43464__observe-stale-worker-alias-reconciliation.md: BUG-OBSERVE-04 is an alias/subcase of BUG-OBSERVE-HANDOFF-01.
- 2026-09-24T1545Z__ee7eea00__observe-handoff-p0-current-basis-revalidation.md: BUG-OBSERVE-04 remains an alias/subcase.
- 2026-10-07__eda6a758__checkpoint-only-production-reconciliation-immediate-a.md: BUG-OBSERVE-HANDOFF-01 is VERIFIED_CLOSED and BUG-OBSERVE-04 remains its historical alias/subcase.

## Corrected union arithmetic

Registry-derived/later-current IDs:
- 136 raw IDs
- BUG-FORMAT-01 -> BUG-FORMAT-BG-03 is one internal alias
- 135 semantic roots

Checkpoint-only distinct-by-ID production roots:
- 14

Cross-population overlap:
- BUG-OBSERVE-04 <-> BUG-OBSERVE-HANDOFF-01: subtract 1

135 + 14 - 1 = 148 repository-wide discovered production correctness semantic roots.

Current dispositions:
- OPEN = 52 + 5 = 57
- CLOSED = 83 + 9 - 1 = 91
- 57 + 91 = 148

## Scope separation

The active download scope remains exactly the eight roots under DOWNLOAD_ACTIVE_BLOCKER_CLOSURE.
CANONICAL_P0=0
CANONICAL_P1=0
CANONICAL_P2=8

No repository-wide root is adopted into the active download scope by this correction.

production_source_changed: NO
implementation_prompt_changed: NO
private_handoff_changed_by_this_checkpoint: NO
