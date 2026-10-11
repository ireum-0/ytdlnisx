# Active Download Remediation Inventory V1

DERIVED_INDEX=YES
CANONICAL_COUNT_AUTHORITY=YES

ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
ACTIVE_REMEDIATION_SCOPE_BOUNDARY=SCHEDULER_BACKGROUND_FORMAT_AND_INCOGNITO_DOWNLOAD_BLOCKERS
CANONICAL_COUNT_SEMANTICS=ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
implementation_verification_sha=db339e1e90392cfd994e2d42bca7ce88afb3be21

CANONICAL_P0=0
CANONICAL_P1=0
CANONICAL_P2=6

## Canonical roots

1. BUG-FORMAT-BG-01 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
2. BUG-FORMAT-BG-02 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
3. BUG-FORMAT-BG-03 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
4. BUG-FORMAT-BG-04 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
5. BUG-FORMAT-BG-05 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
6. BUG-INCOGNITO-01 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md

## Verified closed and removed from current OPEN inventory

- BUG-SCHEDULER-WINDOW-01 — VERIFIED_CLOSED at db339e1 — independently reviewed source and V3 final exact-SHA window/END ownership execution evidence; preserves 2026-10-06 false-closure/reopen history.
- BUG-SCHEDULER-RESTORE-01 — VERIFIED_CLOSED at db339e1 — independently reviewed validated Restore/settings transitions, external authority convergence and V3 exact-SHA production-wiring execution evidence.
- Canonical final decision: review-runs/checkpoints/2026-10-12__db339e1__scheduler-window01-restore01-independent-final-closures.md

## Current implementation priority

The completed scheduler authority implementation is published at db339e1. No implementation agent is active.
Reviewer-owned next action is independent review of BUG-FORMAT-BG-01 at this exact SHA;
any later correction prompt requires separate source-semantic preflight and persistence.

Latest canonical scheduler root closures:
review-runs/checkpoints/2026-10-12__db339e1__scheduler-window01-restore01-independent-final-closures.md

## Scope guard

Repository-wide findings are not adopted into this inventory without a separate explicit scope decision.
The repository-wide derived finding total and its OPEN/CLOSED counts are separate accounting domains.

production_source_changed=NO
implementation_prompt_changed=NO
