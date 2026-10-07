# Repository-wide semantic-root recount — final

Date: 2026-10-07

checkpoint_kind: REPOSITORY_SEMANTIC_ROOT_RECOUNT
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: 38eb3f1557557b975d4bd9884838e97b2198f2e8
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

REPOSITORY_DISCOVERED_CANONICAL_ROOTS_TOTAL=149
REPOSITORY_SEMANTIC_ROOTS_CURRENT_OPEN=57
REPOSITORY_SEMANTIC_ROOTS_CURRENT_CLOSED=92
REPOSITORY_CHECKPOINT_ONLY_FINDING_DISCOVERY=COMPLETE
REPOSITORY_CHECKPOINT_ONLY_UNRESOLVED_CANDIDATES=0

## Counting basis

Registry-derived candidate population:
- 136 candidate IDs;
- current audit result: 83 VERIFIED_CLOSED, 52 VERIFIED_OPEN, 1 SUPERSEDED_ALIAS;
- therefore registry-derived candidate IDs contribute 135 distinct semantic roots.

Checkpoint-only distinct production roots outside the 136-ID population after complete reverse reconciliation:
- 14 distinct roots;
- 5 VERIFIED_OPEN;
- 9 VERIFIED_CLOSED.

All checkpoint-only labels later proven to be aliases/subcases of registry roots are excluded from the
checkpoint-only distinct-root increment. Rejected candidates and tooling/governance-only findings are also
excluded.

Therefore:
- 135 registry-derived distinct semantic roots
- +14 distinct checkpoint-only production semantic roots
- =149 repository-wide discovered production correctness semantic roots.

Current disposition arithmetic:
- OPEN: 52 + 5 = 57
- CLOSED: 83 + 9 = 92
- 57 + 92 = 149

The single registry-derived alias is not counted as a root. Alias identifiers discovered only in checkpoint
history are not counted as roots.

## Checkpoint-only distinct roots counted in the +14

Current OPEN:
1. BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — VERIFIED_OPEN P2
2. BUG-HARDSUB-GENERATION-01 — VERIFIED_OPEN P2
3. HISTORY-CONTENT-AUTHORITY-ALIAS-01 — VERIFIED_OPEN P2
4. BUG-OBSERVE-SOURCE-IDENTITY-01 — VERIFIED_OPEN P2
5. BUG-LINK-INPUT-01 — VERIFIED_OPEN P2

Current CLOSED:
1. BUG-BACKUP-11 — VERIFIED_CLOSED
2. BUG-UPDATER-03 — VERIFIED_CLOSED
3. BUG-HISTORY-05 — VERIFIED_CLOSED
4. BUG-OBSERVE-HANDOFF-01 — VERIFIED_CLOSED
5. BUG-TERMINAL-11 — VERIFIED_CLOSED
6. BUG-UPDATER-04 — VERIFIED_CLOSED
7. BUG-SCHEDULE-01 — VERIFIED_CLOSED
8. BUG-DUPLICATE-ADMISSION-01 — VERIFIED_CLOSED
9. CLEANUP-MAIN-THREAD-BLOCK-01 — VERIFIED_CLOSED

## Cross-population/lineage exclusions

The following checkpoint-only identifiers do not add roots because current lineage reconciles them to an
already-counted semantic root:
- BUG-DOWNLOAD-HANDOFF-01 -> BUG-QUEUE-03
- BUG-GROUP-TXN-01 -> BUG-GROUP-01
- BUG-HISTORY-FILE-DELETE-TXN-01 -> BUG-HISTORY-01
- BUG-HISTORY-UNDO-PLAYLIST-01 -> BUG-HISTORY-01
- CLEANUP-STALE-DOWNLOAD-ROW-01 -> BUG-DOWNLOAD-DELETE-SNAPSHOT-01
- BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01 -> BUG-FORMAT-BG-03
- OBSERVE-SCHEDULER-ASYNC-BARRIER-01 -> BUG-OBSERVE-03
- BUG-LOCALADD-HANDOFF-01 -> BUG-LOCALADD-02
- BUG-TERMINAL-HANDOFF-01 -> BUG-TERMINAL-05
- BUG-KEYWORD-HANDOFF-01 -> BUG-KEYWORD-03
- AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 -> BUG-KEYWORD-03
- BUG-HISTORY-DUPLICATE-IDENTITY-01 -> BUG-HISTORY-04
- BUG-PLAYLIST-TXN-01 -> BUG-PLAYLIST-01
- BUG-CACHE-ROOT-01 -> BUG-CLEANUP-01 same-root subcase

Rejected production-root candidates excluded:
- BUG-LOWQUALITY-SAVED-01
- RESULT-URL-IDENTITY-ALIAS

Tooling/governance-only findings excluded from production correctness root total:
- BUG-TOOLING-01
- BUG-TOOLING-02

Historical filename token BUG-BACKUP-09 is non-distinct and remains routed to existing BUG-BACKUP-07
workflow reconciliation rather than a new production root.

## Scope separation

This repository-wide total is a derived semantic-root inventory count. It is not the active download
canonical count.

The fixed active download remediation scope remains:
ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
CANONICAL_P0=0
CANONICAL_P1=0
CANONICAL_P2=8

No repository-wide root is adopted into that active download scope by this recount.

## Next governed action

Checkpoint-only discovery and semantic-root counting are complete.

Next:
1. generate review-runs/inventories/REPOSITORY_FINDINGS_INDEX_V1.md as a derived, non-canonical-count
   authority over the 149 semantic roots and their aliases/subcases;
2. generate review-runs/inventories/ACTIVE_DOWNLOAD_REMEDIATION_INVENTORY_V1.md containing only the fixed
   eight active download roots;
3. perform the four-way final reconciliation:
   historical evidence -> repository index -> exact production -> download canonical -> private handoff.

production_source_changed: NO
implementation_prompt_changed: NO
private_handoff_changed_by_this_checkpoint: NO
