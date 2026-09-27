# Canonical current-open count reconciliation

checkpoint_kind: CANONICAL_OPEN_COUNT_RECONCILIATION
review_parent_sha: 547c05b63ab3febfd1d6b8a86b19d7c1df645516
finding_dispositions_changed: NO
independent_execution: NOT EXECUTED

Definition:
CANONICAL_P0/P1/P2 are counts of canonical roots that are currently unresolved at that severity. They are not cumulative discovery totals. OPEN, PARTIALLY FIXED, and REMEDIATION-READY roots still count until explicitly closed. Closed roots and aliases merged into an already-counted root do not count separately.

Reconciled counts:
- CURRENT_OPEN_P0_COUNT=0
- CURRENT_OPEN_P1_COUNT=0
- CURRENT_OPEN_P2_COUNT=14
- previous recorded P2 count=18
- correction=-4

Current open P2 inventory:
BUG-DOWNLOAD-01
BUG-LOCALADD-06
BUG-UPDATER-02
BUG-SCHEDULER-05
BUG-ABI-01
BUG-HISTORY-04
BUG-MIGRATION-01
BUG-RUNTIME-01
BUG-TERMINAL-06
BUG-COOKIE-03
BUG-BACKUP-11
BUG-PAUSE-03
BUG-RESUME-01
BUG-TOOLING-01

This checkpoint changes only canonical count semantics/value, not any finding disposition.

Forward rule:
- add one newly canonical unresolved root => +1 at its severity;
- explicitly close one counted root => -1 at its severity;
- merge an alias/duplicate into an already-counted root => no extra count;
- change severity => move one count from old severity to new severity;
- partial fix/remediation-ready without closure => count unchanged.

CANONICAL_COUNT_SEMANTICS=CURRENT_OPEN_ROOTS_ONLY

INDEPENDENT EXECUTION: NOT EXECUTED
