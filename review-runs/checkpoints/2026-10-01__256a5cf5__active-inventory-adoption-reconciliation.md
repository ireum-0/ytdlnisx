# Active inventory adoption reconciliation — 2026-10-01

checkpoint_kind: CANONICAL_ACTIVE_INVENTORY_RECONCILIATION
review_parent_sha: be3a24dd570f04a8dfc87da2989870746d9cdc95
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

governing_protocol_commit: 54086a4d27e7b224bf92582e74662200413283ac
governing_protocol_blob: 71630837d84456a86ca1c6b3709e7573dc2d13f3

## Why this reconciliation exists

The earlier active inventory was originally pinned as 13 production P2 roots and later closed at
256a5cf5. Subsequent independent manual reviews established two additional distinct P2 roots:

- BUG-UPDATER-03
- BUG-HISTORY-05

Those reviews described them as outside the current active inventory because they were discovered
after the earlier inventory snapshot. No separate functional-scope exclusion or governance/dependency
reason for deferring either root was recorded.

The clarified REVIEW_PROTOCOL section 10 now distinguishes:
- the named active remediation scope/domain;
- the canonical active inventory adopted inside that scope;
- the narrower active implementation root currently assigned to a prompt.

A same-scope production root may not remain outside the canonical inventory solely because it was
discovered later.

## Scope-membership proof

DOWNLOAD_CORRECTNESS_REMEDIATION already includes heterogeneous production roots across updater,
History, LocalAdd, backup/restore, scheduler, runtime, terminal/cache, cookie, pause and resume
correctness. The 256a5cf5 cumulative closure explicitly listed BUG-UPDATER-02 and BUG-HISTORY-04
inside this same scope.

Therefore:
- BUG-UPDATER-03 is inside the existing remediation scope because it is an updater/restore semantic
  contract defect adjacent to BUG-UPDATER-02.
- BUG-HISTORY-05 is inside the existing remediation scope because it is a History destructive
  relationship-preservation defect adjacent to BUG-HISTORY-04.

Neither root is an alias/subcase of the currently counted BUG-UPDATER-02 root:
- BUG-UPDATER-03 is a distinct malformed portable-source contract root.
- BUG-HISTORY-05 is a distinct valid-dedupe playlist-membership-loss root.

No durable deferral rationale exists for keeping either same-scope root outside the canonical active
inventory.

## Inventory dispositions

BUG-UPDATER-02:
- disposition: ADOPTED_INTO_ACTIVE_INVENTORY
- severity: P2
- status: OPEN
- current implementation root: YES

BUG-UPDATER-03:
- disposition: ADOPTED_INTO_ACTIVE_INVENTORY
- severity: P2
- status: OPEN
- attribution: PRE_EXISTING_BASELINE_DEFECT
- current implementation root: NO

BUG-HISTORY-05:
- disposition: ADOPTED_INTO_ACTIVE_INVENTORY
- severity: P2
- status: OPEN
- attribution: PRE_EXISTING_BASELINE_DEFECT
- current implementation root: NO

## Canonical count reconciliation

Before:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=1

After:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=3

ACTIVE_REMEDIATION_OPEN_P0_COUNT=0
ACTIVE_REMEDIATION_OPEN_P1_COUNT=0
ACTIVE_REMEDIATION_OPEN_P2_COUNT=3

active_inventory_open_roots:
- BUG-UPDATER-02
- BUG-UPDATER-03
- BUG-HISTORY-05

active_implementation_root:
- BUG-UPDATER-02

The currently persisted implementation prompt remains narrowly scoped to BUG-UPDATER-02. Adopting
the other two roots changes canonical inventory/count state, not the currently authorized source
edit scope.

## Superseded count statements

This checkpoint supersedes count-only statements that left BUG-UPDATER-03 and BUG-HISTORY-05
outside the active inventory without a concrete same-scope deferral reason.

It also supersedes the temporary broad-domain count reinterpretation checkpoint. The canonical
semantics remain ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY; the count is now 3 because all three confirmed
same-scope roots are explicitly adopted into that inventory.

No production source, test, configuration, prompt, ledger, or implementation branch was changed.
