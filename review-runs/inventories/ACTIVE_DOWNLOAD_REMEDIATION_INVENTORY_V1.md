# Active Download Remediation Inventory V1

DERIVED_INDEX=YES
CANONICAL_COUNT_AUTHORITY=YES

ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
ACTIVE_REMEDIATION_SCOPE_BOUNDARY=SCHEDULER_BACKGROUND_FORMAT_AND_INCOGNITO_DOWNLOAD_BLOCKERS
CANONICAL_COUNT_SEMANTICS=ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
implementation_verification_sha=db339e1e90392cfd994e2d42bca7ce88afb3be21

CANONICAL_P0=0
CANONICAL_P1=0
CANONICAL_P2=7
ROOT_STATUS_VOCABULARY=OPEN,CLOSED,AMBIGUOUS
ACTIVE_REMEDIATION_CONFIRMED_OPEN_ROOT_COUNT=7
ACTIVE_REMEDIATION_AMBIGUOUS_ROOT_COUNT=0
STATUS_NORMALIZATION_CHECKPOINT=review-runs/checkpoints/2026-10-12__db339e1__derived-root-status-vocabulary-reconciliation.md

The legacy `CANONICAL_P*` totals count unresolved blockers (OPEN + AMBIGUOUS); current active roots are all confirmed OPEN. This derived index includes the independently established V34 exact-X SQL trigger conflict as one additional root; prior six root statuses are unchanged.

LATEST_ACTIVE_SCOPE_EVIDENCE=review-runs/checkpoints/2026-10-16__57d8d8f__v34-g5-sqlite-trigger-primarykey-independent-final.md
LATEST_ACTIVE_SCOPE_VERIFIED_IMPL_SHA=57d8d8f1b4993d72177c12ac705f7f2137fa92a1

The first six root statuses above derive from pre-existing decisions; the seventh OPEN root is a new production finding independently reviewed at exact X.

## Canonical roots

1. BUG-FORMAT-BG-01 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
2. BUG-FORMAT-BG-02 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
3. BUG-FORMAT-BG-03 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
4. BUG-FORMAT-BG-04 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
5. BUG-FORMAT-BG-05 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
6. BUG-INCOGNITO-01 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md

7. BUG-FORMAT-AUTHORITY-TRIGGER-01 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-16__57d8d8f__v34-g5-sqlite-trigger-primarykey-independent-final.md
   Single production cause: Room generated UPDATE OR ABORT and existing-key trigger INSERT OR REPLACE conflict-policy inheritance abort normal Download/Result authority stamping; do not duplicate by test/writer path.

## CLOSED and removed from current unresolved inventory

- BUG-SCHEDULER-WINDOW-01 — CLOSED at db339e1 — independently reviewed source and V3 final exact-SHA window/END ownership execution evidence; preserves 2026-10-06 false-closure/reopen history.
- BUG-SCHEDULER-RESTORE-01 — CLOSED at db339e1 — independently reviewed validated Restore/settings transitions, external authority convergence and V3 exact-SHA production-wiring execution evidence.
- Canonical final decision: review-runs/checkpoints/2026-10-12__db339e1__scheduler-window01-restore01-independent-final-closures.md

## Current implementation priority

The earlier scheduler authority implementation db339e1 remains a historical reviewed basis. Existing 41-path candidate X 57d8d8f is now published after six actual old-schema migration PASSES; the V34 exact-X G5 gate failed on a new SQL-trigger primary-key root after 20 PASS, with 176 NOT_REACHED. This index does not promote X to CLEAN. The next possible source correction concerns the explicitly OPEN trigger root and its cross-writer/trigger/recovery compatibility, but an implementation prompt and launch need separate authorization.

Latest canonical scheduler root closures:
review-runs/checkpoints/2026-10-12__db339e1__scheduler-window01-restore01-independent-final-closures.md

## Scope guard

Repository-wide findings are not adopted into this inventory without a separate explicit scope decision.
The repository-wide derived finding total and its OPEN/CLOSED counts are separate accounting domains.

production_source_changed=NO
implementation_prompt_changed=NO
