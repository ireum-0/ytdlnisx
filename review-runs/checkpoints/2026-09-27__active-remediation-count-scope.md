# Active remediation count-scope reconciliation

checkpoint_kind: CANONICAL_ACTIVE_REMEDIATION_COUNT_RECONCILIATION
review_parent_sha: 90eb0510da22e9ba63e1d131b4eef9b83f1cd01c
finding_dispositions_changed: NO
independent_execution: NOT EXECUTED

## Count scope

CANONICAL_P0/P1/P2 count only unresolved canonical roots inside the currently active production correctness-remediation scope.

They do not count:
- every OPEN finding in the repository;
- unrelated future/backlog findings outside the active remediation inventory;
- tooling/harness/infrastructure blockers that gate remediation execution but are not production roots in the active remediation scope.

Current active remediation scope:
ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_CORRECTNESS_REMEDIATION
ACTIVE_REMEDIATION_SCOPE_SOURCE=latest canonical current-production-P2 inventory
CANONICAL_COUNT_SEMANTICS=ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

## Reconciled counts

ACTIVE_REMEDIATION_OPEN_P0_COUNT=0
ACTIVE_REMEDIATION_OPEN_P1_COUNT=0
ACTIVE_REMEDIATION_OPEN_P2_COUNT=13

Legacy CANONICAL_P0/P1/P2 values must equal those active-remediation counts:
CANONICAL_P0=0
CANONICAL_P1=0
CANONICAL_P2=13

## Active production P2 inventory

1. BUG-DOWNLOAD-01
2. BUG-LOCALADD-06
3. BUG-UPDATER-02
4. BUG-SCHEDULER-05
5. BUG-ABI-01
6. BUG-HISTORY-04
7. BUG-MIGRATION-01
8. BUG-RUNTIME-01
9. BUG-TERMINAL-06
10. BUG-COOKIE-03
11. BUG-BACKUP-11
12. BUG-PAUSE-03
13. BUG-RESUME-01

## Separate gating blockers

BUG-TOOLING-01 remains OPEN P2 but is a tooling blocker, not a production root in DOWNLOAD_CORRECTNESS_REMEDIATION.

Record it separately:
TOOLING_OPEN_P0_COUNT=0
TOOLING_OPEN_P1_COUNT=0
TOOLING_OPEN_P2_COUNT=1

Repository-wide OPEN findings in TASKS_DELTA are not implied by either count.

## Forward rule

- add/close/reclassify a root inside the active remediation scope => update CANONICAL_P* and ACTIVE_REMEDIATION_OPEN_P*_COUNT together;
- add/close/reclassify a tooling blocker => update TOOLING_OPEN_P*_COUNT only;
- a repository finding outside the active remediation scope changes neither count until an explicit canonical scope expansion adopts it;
- partial fix/remediation-ready without closure remains counted;
- aliases/duplicates count once.

This checkpoint supersedes 90eb0510da22e9ba63e1d131b4eef9b83f1cd01c for count scope and numeric count only. Finding dispositions are unchanged.

INDEPENDENT EXECUTION: NOT EXECUTED
