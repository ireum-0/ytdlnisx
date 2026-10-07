# Canonical download-scope reconciliation — 2026-10-07

checkpoint_kind: CANONICAL_ACTIVE_SCOPE_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 75152687d5ccb39f23718dc22c789f4fa06df572
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934

user_scope_decision: CURRENT_CANONICAL_IS_DOWNLOAD_RELATED_UNTIL_EXPLICITLY_REVISED
ACTIVE_REMEDIATION_SCOPE_ID: DOWNLOAD_CORRECTNESS_REMEDIATION
ACTIVE_REMEDIATION_SCOPE_BOUNDARY: CURRENT_ACTIVE_DOWNLOAD_BLOCKERS_ONLY
CANONICAL_COUNT_SEMANTICS: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

## Reconciliation

The immediately preceding repository-wide manual reviews correctly established additional current
production findings, but their repository-wide open-root recount must not be reused as the canonical
active-remediation count.

The governing protocol defines CANONICAL_P0/P1/P2 as the open roots explicitly adopted into the
current active remediation scope, never repository-wide open totals or cumulative discovery totals.

The user has now explicitly fixed the current canonical scope to the download-related remediation
boundary until another explicit scope revision is made.

The existing handoff already carries an exact current list named ACTIVE_DOWNLOAD_BLOCKERS. On the
unchanged implementation basis eda6a758..., the latest scheduler/background-format/incognito review
evidence still establishes all eight as open P2 roots.

Therefore the canonical active inventory is:

- BUG-SCHEDULER-WINDOW-01 — OPEN P2 / reopened same-root residual
- BUG-SCHEDULER-RESTORE-01 — OPEN P2
- BUG-FORMAT-BG-01 — OPEN P2
- BUG-FORMAT-BG-02 — OPEN P2
- BUG-FORMAT-BG-03 — OPEN P2
- BUG-FORMAT-BG-04 — OPEN P2
- BUG-FORMAT-BG-05 — OPEN P2
- BUG-INCOGNITO-01 — OPEN P2

Result:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=8
- ACTIVE_REMEDIATION_OPEN_P0_COUNT=0
- ACTIVE_REMEDIATION_OPEN_P1_COUNT=0
- ACTIVE_REMEDIATION_OPEN_P2_COUNT=8

CANONICAL_OPEN_ROOTS:
BUG-SCHEDULER-WINDOW-01,
BUG-SCHEDULER-RESTORE-01,
BUG-FORMAT-BG-01,
BUG-FORMAT-BG-02,
BUG-FORMAT-BG-03,
BUG-FORMAT-BG-04,
BUG-FORMAT-BG-05,
BUG-INCOGNITO-01

## Repository-wide findings remain preserved but non-canonical

The following currently recorded repository-wide findings are not deleted, rejected, or declared
closed by this reconciliation:

- CURRENT-PLAYER-TIMELINE-INDEX
- BUG-PLAYER-01
- BUG-APP-UPDATE-01
- BUG-APP-UPDATE-02
- BUG-PLAYLIST-DELETE-01
- BUG-COOKIE-RESTORE-01
- WORKER-FOREGROUND-COMPLETION-01
- BUG-STORAGE-ALLFILES-01

They remain repository-wide findings subject to the historical/current finding reconciliation
campaign. They do not change CANONICAL_P* unless a later explicit scope/inventory decision adopts
them.

WORKER-FOREGROUND-COMPLETION-01 requires special duplicate-count handling: its
UpdateMultipleDownloadsFormatsWorker manifestation is already inside the correction/closure obligations
of canonical BUG-FORMAT-BG-03. The repository-wide cross-worker root must therefore not create a ninth
download canonical root from that same format-worker manifestation.

BUG-STORAGE-ALLFILES-01 affects a storage capability used by download configuration, but it was
discovered by repository-wide L5 review and was not part of the currently fixed ACTIVE_DOWNLOAD_BLOCKERS
inventory. This checkpoint does not silently adopt it. A later explicit scope decision is required if
the user chooses to add it to the download canonical campaign.

## Preserved active implementation

The implementation agent was observed active during this reconciliation.

This review-only metadata decision:
- does not inspect or rely on the in-progress implementation diff;
- does not alter the persisted active scheduler prompt;
- does not change ACTIVE_IMPLEMENTATION_SCOPE;
- does not stop, redirect, broaden, or reissue the active scheduler wave;
- preserves the current implementation priority on BUG-SCHEDULER-WINDOW-01 / BUG-SCHEDULER-RESTORE-01.

## Evidence continuity

Implementation basis remains eda6a758....

The eight-root open status is supported by:
- 2026-10-06 eda6a758 manual v7 L6 repository-wide reopen final;
- remaining-download-blockers correction boundaries;
- download open-roots historical-agent-error hardening;
- download open-roots ambiguity-closure addendum;
- 2026-10-07 download open-roots detailed spec review;
- later scheduler execution-owner same-root residual checkpoints.

The 2026-10-07 L5 repository-wide manual checkpoint remains valid evidence for repository-wide findings.
Only its P1/P2 recount is superseded for canonical active-remediation count interpretation by this
explicit scope reconciliation.

canonical_count_delta_from_handoff:
- P0: 0 -> 0
- P1: 1 -> 0
- P2: 15 -> 8

semantic_findings_deleted: NONE
repository_findings_closed: NONE
active_implementation_changed: NO
production_source_changed: NO
prompt_changed: NO
ledger_changed: NO
