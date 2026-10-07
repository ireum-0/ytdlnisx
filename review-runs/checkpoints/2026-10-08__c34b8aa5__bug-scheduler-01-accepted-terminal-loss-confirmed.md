# BUG-SCHEDULER-01 — accepted terminal scheduler carrier loss confirmed on c34b8aa5

Date: 2026-10-08

record_kind: IMPLEMENTATION_COMPLETION_REVIEW_STOP_RECONCILIATION
record_status: FINAL
manual_review_run: NO

review_parent_sha: 4f586b94707094b692ce0ca8932efe330fee3c1b
reviewed_implementation_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
reviewed_range: eda6a7589af3a19a97eb38e869b47dabaf74388b..c34b8aa57e01803c9960e4ad873d1ed5b68e019c
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934

overall_verdict: NOT_CLEAN
new_finding_ids: NONE
reopened_finding_ids: NONE
same_root_residuals_confirmed: BUG-SCHEDULER-01
canonical_count_change: NONE
active_download_count_change: NONE

## Publication / history review

The implementation branch advanced by exactly two normal forward commits from eda6a758:

1. 9e3d082c0ceeeacf4c678e2cad2b9b0fb6b0de6e
   BUG-SCHEDULER-WINDOW-01: align inclusive END boundary and retire exact stopped E1
2. c34b8aa57e01803c9960e4ad873d1ed5b68e019c
   BUG-SCHEDULER-RESTORE-01: validate restored settings and converge exact scheduler authority

Ancestry is forward-only with merge base eda6a758. No history rewrite is observed.

The range is scheduler/Restore scoped and contains the expected production and regression changes. No
unrelated production expansion is established by this review.

## Reported verification

Implementation-agent stop report states:
- current published SHA c34b8aa57e01803c9960e4ad873d1ed5b68e019c;
- clean worktree;
- exact remote equality verified;
- prepublication verification: 49 PASS, 0 FAIL;
- exact-SHA closure: 98/138 PASS;
- completed 34/34 gate included;
- remaining 40 executions NOT_REACHED;
- no verifier remains running;
- protected worktrees/evidence preserved and sealed.

Sealed report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-f11-handoff-host-floor-20261007-AF1Z1b/F11_HANDOFF_HOST_FLOOR_GOVERNED_STOP_REPORT.md

These results are implementation-agent evidence. They do not establish full exact-SHA closure because 40
required executions were not reached.

## Forward manual-review evidence

The latest independent manual L1 run was pinned to eda6a758 and explicitly did not inspect c34b8aa5.
It identified a same-root residual of existing OPEN P2 BUG-SCHEDULER-01:

accepted scheduler START/END WorkManager request
-> WorkInfo becomes FAILED or CANCELLED before exact semantic retirement
-> startup reconciliation sees ACCEPTED carrier
-> no scheduler-specific accepted-terminal retry branch exists
-> generic accepted-carrier deletion can remove the durable daily scheduler owner.

That older checkpoint by itself is not authority for c34b8aa5. This checkpoint independently rechecks exact
current source.

## Exact c34b8aa5 current-source impact

Current WorkManagerHandoffRecovery.reconcileCarrier():

- reads the exact durable carrier and WorkInfo;
- for ACCEPTED carriers has explicit FAILED/CANCELLED recovery branches for:
  - OBSERVE_RETRY_DOWNLOAD;
  - OBSERVE_RECURRENCE terminal states;
  - AUTOMATIC_KEYWORD_SYNC;
  - TERMINAL_DISPATCH;
- retains scheduler START/END only while WorkInfo is absent or unfinished;
- has no ACCEPTED scheduler START/END FAILED/CANCELLED branch;
- therefore current ACCEPTED scheduler START/END with terminal FAILED/CANCELLED bypasses the unfinished
  retention guards and falls into generic deleteAccepted(handoffId, requestId).

The c34b8aa5 Restore-specific additions do not close this path. They add bounded restored-window fallback
acceptance/retry during Restore, but ordinary startup reconciliation still contains the accepted-terminal
scheduler carrier-loss fallthrough above.

Current retryAfterFailure() already provides the narrow reusable mechanism needed by the same root:
- current authority/generation checks;
- durable retained-carrier check for terminal-retained kinds;
- exact old requestId -> new requestId retry advance;
- PENDING_ENQUEUE transition;
- bounded retry scheduling;
- supersession outcome instead of reviving a stale generation.

Therefore:

CURRENT_CANDIDATE_IMPACT=CONFIRMED
BUG_SCHEDULER_01_SAME_ROOT_RESIDUAL=CONFIRMED_ON_C34B8AA5
NEW_ROOT=NO
PRODUCTION_CORRECTION_REQUIRED=YES
CORRECTION_SCOPE=NARROW_WORKMANAGER_HANDOFF_RECOVERY_ACCEPTED_SCHEDULER_TERMINAL_FAILURE_PATH

## Minimal correction contract

For exact current scheduler START/END carriers only:

- when carrier.state == ACCEPTED and its exact WorkInfo is FAILED or CANCELLED, do not delete the current
  durable carrier;
- route current exact authority through the existing retryAfterFailure/convergence mechanism;
- advance retry only if the same handoff/generation/boundary/request remains durable current authority;
- do not resurrect a superseded settings generation or Restore generation;
- preserve existing behavior for missing/unfinished WorkInfo;
- preserve exact successful semantic retirement;
- preserve Restore-owned restored-window fallback semantics;
- do not alter schema, scheduler window semantics, Restore settings validation, retry budget architecture,
  worker effect semantics, or unrelated handoff kinds.

Required deterministic controls:
1. START ACCEPTED -> FAILED -> startup reconcile -> exact durable retry survives;
2. END ACCEPTED -> FAILED -> startup reconcile -> exact durable retry survives;
3. current scheduler ACCEPTED -> CANCELLED without supersession -> exact durable retry survives;
4. settings supersession E1 -> E2 prevents failed/cancelled E1 resurrection;
5. Restore supersession E1 -> Restore E2 prevents failed/cancelled E1 resurrection;
6. successful exact retirement is not replayed;
7. existing missing/unfinished scheduler carrier controls remain green.

## Closure consequences

The already reported 98/138 exact-SHA results belong to c34b8aa5 and cannot serve as exact-final-SHA closure
after a new production correction is published.

Before a new publication:
- run focused BUG-SCHEDULER-01 recovery regressions;
- run materially affected WorkManager/scheduler/Restore recovery partitions;
- preserve prior evidence but do not use tests as a substitute for source-semantic audit.

After publication:
- verify exact remote equality;
- run the full governing exact-final-SHA closure set on the NEW exact published SHA, including all 138
  governed executions or the exact currently-discovered equivalent set if governance defines it dynamically;
- do not resume merely at the previously NOT_REACHED 40 because the production SHA changed.

No FIXED-CLOSED or repository-wide CLEAN claim is authorized by this checkpoint.

INDEPENDENT_REVIEW_REQUIRED=YES
