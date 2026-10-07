# Canonical download-scope ID correction — 2026-10-07

checkpoint_kind: CANONICAL_ACTIVE_SCOPE_ID_CORRECTION
checkpoint_status: FINAL
review_parent_sha: 89dc1e3a5b712f6ed3a8847d4ee2c68f4e856dbc
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

supersedes_scope_id_only:
- review-runs/checkpoints/2026-10-07__eda6a758__canonical-download-scope-reconciliation.md

## Reason

Historical review evidence proves that the prior stable scope ID
DOWNLOAD_CORRECTNESS_REMEDIATION was intentionally broad and included production roots from updater,
History, LocalAdd, backup/restore, scheduler, runtime, Terminal/cache, cookie, pause and resume.

The user has explicitly fixed the CURRENT canonical scope to the narrower current download-blocker
campaign until another explicit revision.

Reusing the historical broad scope ID while excluding its prior same-scope roots would make the
scope/inventory semantics ambiguous and would conflict with the protocol rule that same-scope findings
require an explicit inventory disposition.

Therefore the current narrow campaign receives a distinct stable scope ID.

ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
ACTIVE_REMEDIATION_SCOPE_BOUNDARY=SCHEDULER_BACKGROUND_FORMAT_AND_INCOGNITO_DOWNLOAD_BLOCKERS
CANONICAL_COUNT_SEMANTICS=ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

Canonical inventory remains unchanged:
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

Counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=8

The historical DOWNLOAD_CORRECTNESS_REMEDIATION scope and all findings/checkpoints associated with it
remain preserved as history. This correction does not reopen, close, reject, adopt, or reattribute any
repository finding.

Current repository-wide finding reconciliation remains separate and cannot alter the above canonical
counts without an explicit future scope/inventory revision.

active_implementation_changed: NO
production_source_changed: NO
prompt_changed: NO
ledger_changed: NO
