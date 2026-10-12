# Derived findings status vocabulary normalization — no new correctness verdict

date: 2026-10-12
checkpoint_kind: DERIVED_INDEX_PRESENTATION_RECONCILIATION
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_parent_sha: e58898a43fd15b6f1949e20f04c75df1a033e6dc
implementation_basis_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
independent_execution: NOT_EXECUTED
production_source_changed: NO
finding_semantic_status_changed: NO
new_root_delta: 0
closed_root_delta: 0
reopened_root_delta: 0
canonical_count_delta: 0
next_task_or_implementation_prompt_changed: NO
private_handoff_changed: NO

## Scope and binding decisions

Only current presentation/status columns of the mutable derived finding indexes are normalized to the three root states OPEN, CLOSED and AMBIGUOUS. This is a **translation of already recorded canonical decisions**, NOT an independent new production semantic review, an invitation to reopen/close anything, or a manual trigger-`3` run. Historical checkpoints/ledger/prompt/governance and evidence/provenance columns remain immutable. Original verification/reopen/current-path-absent reasons stay grounded in their linked historical checkpoints; raw labels are not new current root states.

The active-download canonical index has six P2 OPEN roots: BUG-FORMAT-BG-01 through -05, and BUG-INCOGNITO-01. Scheduler WINDOW-01 and RESTORE-01 are CLOSED by the later authoritative independent final closure checkpoint `review-runs/checkpoints/2026-10-12__db339e1__scheduler-window01-restore01-independent-final-closures.md`, overriding older manual FINAL claims at the same implementation SHA. No evidence establishes a newly AMBIGUOUS root in the current indexes, so the vocabulary conversion labels none AMBIGUOUS. Do not infer from this conversion that every root was freshly re-reviewed.

Canonical derived repository index has exactly 148 semantic-root rows. Prior recorded states map by **current status only**:
- `VERIFIED_OPEN`, `VERIFIED_OPEN P1/P2/P3`, `REOPENED_OPEN P2` -> `OPEN` (severity stays in the separate Severity column; reopening history is preserved).
- `VERIFIED_CLOSED`, `VERIFIED_CLOSED_CURRENT_PATH_ABSENT`, `VERIFIED_CLOSED_CURRENT_RECONCILIATION` -> `CLOSED` (historical closure basis and current absence/reconciliation reasons remain in linked evidence).
- `AMBIGUOUS` is reserved for a future reviewer decision with explicit unresolved question, failed resolution attempt, reason it cannot be resolved now and next bounded action; no prior OPEN is automatically recast as AMBIGUOUS.

The repository-wide totals remain 148 discovered = **55 OPEN + 0 AMBIGUOUS + 93 CLOSED**. The old index footer's `57 OPEN / 91 CLOSED` was a stale snapshot predating the two scheduler closures, and its current-count proof must be corrected without rewriting original closure evidence. Active scope remains **P0=0/P1=0/P2=6**, with 6 confirmed OPEN and 0 AMBIGUOUS.

## Review restart recommendation (not an execution handoff)

For a future explicit manual `3` invocation: fresh-check the exact implementation, review, plan/ledger governance and private protocol refs; inspect the review branch for the latest canonical closure/reconciliation and a genuinely resumable `manual_review_run_status=IN_PROGRESS` on the pinned basis before creating any new run. Resume a valid IN_PROGRESS run without repinning; otherwise pin a new run according to the existing manual lifecycle. Existing FINAL manual checkpoints are historical coverage records, **not proof that newer canonical root closure is invalid**. Give unresolved AMBIGUOUS roots first attention; if no current AMBIGUOUS exists, continue required L1–L6 BASELINE / triggers and remaining not-yet-DEEP lenses for the exact pinned SHA. After review, append checkpoints and update derived current inventories/counts only as supported; do not change `NEXT_CHAT.md` routing or implementation prompt merely because a `3` review finished. An out-of-date `LATEST_MANUAL_REVIEW` pointer in the private handoff is not authority over the review branch.

This restart recommendation does not create a manual IN_PROGRESS run, authorize implementation, modify execution fields, or change the stable review protocol.
