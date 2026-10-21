# Active Download Remediation Inventory V1

DERIVED_INDEX=YES
CANONICAL_COUNT_AUTHORITY=YES

ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
ACTIVE_REMEDIATION_SCOPE_BOUNDARY=SCHEDULER_BACKGROUND_FORMAT_AND_INCOGNITO_DOWNLOAD_BLOCKERS
CANONICAL_COUNT_SEMANTICS=ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
implementation_verification_sha=141b5199592369ab953f52c55ebdb72ba300f1a4

CANONICAL_P0=0
CANONICAL_P1=0
CANONICAL_P2=7
ROOT_STATUS_VOCABULARY=OPEN,CLOSED,AMBIGUOUS
ACTIVE_REMEDIATION_CONFIRMED_OPEN_ROOT_COUNT=3
ACTIVE_REMEDIATION_AMBIGUOUS_ROOT_COUNT=4
STATUS_NORMALIZATION_CHECKPOINT=review-runs/checkpoints/2026-10-12__db339e1__derived-root-status-vocabulary-reconciliation.md

The legacy `CANONICAL_P*` totals count unresolved blockers (OPEN + AMBIGUOUS); current exact-141b519 active unresolved roots comprise three confirmed OPEN and four AMBIGUOUS (two former roots are now CLOSED). The V34 independently verified SQL trigger conflict and the V38 independently verified nullable chapter converter failure are distinct production roots, each counted once. Historical six-root classification is superseded by the 2026-10-21 pinned manual review; BG02 and BG03 are now CLOSED while BG04 and BG05 are AMBIGUOUS.

LATEST_ACTIVE_SCOPE_EVIDENCE=review-runs/checkpoints/2026-10-21__141b519__manual-active-root-disposition-in-progress.md
LATEST_ACTIVE_SCOPE_VERIFIED_IMPL_SHA=141b5199592369ab953f52c55ebdb72ba300f1a4

Historical note: the first six original root states, the seventh trigger OPEN at X, and eighth converter OPEN at X remain immutable findings for those earlier SHAs. At 141b519 source changed; current statuses and runtime evidence gates are reclassified below. The earlier uncommitted V38 candidate did not itself close any root.

## Canonical roots

1. BUG-FORMAT-BG-01 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
2. BUG-FORMAT-BG-02 — P2 — CLOSED
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
3. BUG-FORMAT-BG-03 — P2 — CLOSED
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
4. BUG-FORMAT-BG-04 — P2 — AMBIGUOUS
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
5. BUG-FORMAT-BG-05 — P2 — AMBIGUOUS
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
6. BUG-INCOGNITO-01 — P2 — OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md

7. BUG-FORMAT-AUTHORITY-TRIGGER-01 — P2 — AMBIGUOUS
   Latest evidence: review-runs/checkpoints/2026-10-16__57d8d8f__v34-g5-sqlite-trigger-primarykey-independent-final.md
   Single production cause: Room generated UPDATE OR ABORT and existing-key trigger INSERT OR REPLACE conflict-policy inheritance abort normal Download/Result authority stamping; do not duplicate by test/writer path.

8. BUG-RESULT-NULL-CHAPTER-CONVERTER-01 — P2 — AMBIGUOUS
   Latest evidence: review-runs/checkpoints/2026-10-17__57d8d8f__v39-converter-root-adoption-and-prompt-scope.md
   Separate result DAO conversion contract: nullable ResultItem.chapters is persisted as JSON null; an unconditional Gson fromJson(...).toMutableList() in Converters.kt:63 crashes on real ResultDao readback before a Handoff worker. Distinct from SQLite trigger uniqueness, confirmed at X, adopted by owner-requested next prompt.

9. BUG-FORMAT-SOURCELESS-COMMAND-MEMBER-01 — P2 — OPEN
    Latest evidence: review-runs/checkpoints/2026-10-21__141b519__manual-l3-command-member-in-progress.md
    A valid command download intentionally has blank URL, but FormatAuthority.proven requires nonblank URL; FormatBatchHandoff.members validates all selected/unselected members and worker creates a false unproven-payload issue. Independent from BG01 unselected status-wide publication.

## Excluded false-positive candidate (not a root)

- BUG-FORMAT-SESSION-FIRST-USE-RACE-01 — REJECTED at 141b519 after proof that one process-global RestoreMutationAdmission mutex encloses both Room session commit and process-cache publication. Immutable correction: review-runs/checkpoints/2026-10-21__141b519__manual-l1-session-root-rejection-in-progress.md. The earlier L3 OPEN hypothesis is superseded and not counted as a CLOSED production defect.

## CLOSED and removed from current unresolved inventory

- BUG-SCHEDULER-WINDOW-01 — CLOSED at db339e1 — independently reviewed source and V3 final exact-SHA window/END ownership execution evidence; preserves 2026-10-06 false-closure/reopen history.
- BUG-SCHEDULER-RESTORE-01 — CLOSED at db339e1 — independently reviewed validated Restore/settings transitions, external authority convergence and V3 exact-SHA production-wiring execution evidence.
- Canonical final decision: review-runs/checkpoints/2026-10-12__db339e1__scheduler-window01-restore01-independent-final-closures.md

## Current independently reviewed state — no implementation routing authorization

At 141b519, the nine discovered active-scope real roots are 3 OPEN, 4 AMBIGUOUS, 2 CLOSED; unresolved blocker total is 7 (OPEN + AMBIGUOUS), and CLEAN remains NO. BG02 and BG03 original wrong-effect contracts were closed with exact-source review and attributed V39 PASS; BG04, BG05, trigger authority, and converter remain AMBIGUOUS for individually named missing exact final-sha Android/Room or notification-action execution gates. No new task or prompt is authorized by this review. Historical candidate X 57d8d8f and V34 original trigger failure remain immutable evidence, not the current-source outcome.

Latest canonical scheduler root closures:
review-runs/checkpoints/2026-10-12__db339e1__scheduler-window01-restore01-independent-final-closures.md

## Scope guard

Repository-wide findings are not adopted into this inventory without a separate explicit scope decision.
The repository-wide derived finding total and its OPEN/CLOSED counts are separate accounting domains.

production_source_changed=NO
implementation_prompt_changed=NO

## 2026-10-21 pinned manual-review reconciliation

- Pinned implementation: `141b5199592369ab953f52c55ebdb72ba300f1a4`.
- Root status vocabulary: **OPEN** (current wrong effect proven), **CLOSED** (source and applicable executed evidence prove correction), **AMBIGUOUS** (neither proven; exact blocker specified in same-run review checkpoint).
- Active discovered: 9; OPEN: 3 (BG01, INCOGNITO01, SOURCELESS_COMMAND); AMBIGUOUS: 4 (BG04, BG05, FORMAT_AUTHORITY_TRIGGER01, RESULT_NULL_CHAPTER_CONVERTER01); CLOSED in-scope: 2 (BG02, BG03).
- Active P2 unresolved canonical: 7 = 3 OPEN + 4 AMBIGUOUS. Do NOT describe the historical CANONICAL_P2 combined unresolved total as seven proven-live source defects.
- All four AMBIGUOUS runtime blockers have individual exact reasons, already inspected evidence and one gated next feasible action in `review-runs/checkpoints/2026-10-21__141b519__manual-active-root-disposition-in-progress.md`. Specifically BG04 and FORMAT_AUTHORITY_TRIGGER01 lack named `FormatAuthorityTriggerProductionWiringTest` exact-SHA Room/SQLite trigger runs; BG05 lacks a delivered notification action→receiver→exact cancellation run; chapter converter lacks its seven named Room DAO old-schema/reopen methods on exact 141b519.
- This reconciliation is reviewer-only, not an agent prompt, code change, next-task authorization, or declaration of a test PASS. V39 exact-SHA reported PASS and V51/V52 incomplete/failed verification counts remain historical distinct evidence.
- L1 manual same-SHA review: candidate SESSION_FIRST_USE rejected after global mutex proof; real-root count nine and canonical P2 unresolved seven, pending FINAL. See checkpoint review-runs/checkpoints/2026-10-21__141b519__manual-l1-restored-carry-and-ambiguous-assessment-in-progress.md.

