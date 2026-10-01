# Manual correctness review 256a5cf5 L4 intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 3cb34f31c7f3431ef665c5cbee097aca2b52355b
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

overall_verdict: NOT_CLEAN
active_scope_p0: 0
active_scope_p1: 0
active_scope_p2: 1
active_scope_open_root: BUG-UPDATER-02

new_repository_findings:
- BUG-UPDATER-03: P2, PRE_EXISTING_BASELINE_DEFECT, active_scope=NO
- BUG-HISTORY-05: P2, PRE_EXISTING_BASELINE_DEFECT, active_scope=NO

BUG-HISTORY-04: FIXED_CLOSED
history04_reason: current duplicate candidates are reread and identity is revalidated in the same relationship lock and Room transaction.

BUG-HISTORY-05:
A valid duplicate cleanup can remove a playlist membership that exists only on the removed History row.
The current implementation copies keyword assignments to the retained row but does not copy the removed row's PlaylistItemCrossRef memberships before those memberships are removed.
The same behavior exists at baseline 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9, so attribution is pre-existing.
The current regression fixture places both rows in the same playlist and therefore does not cover asymmetric memberships.

trigger_map:
- Module C: FAIL, BUG-UPDATER-02/03
- Module F: FAIL, BUG-UPDATER-02
- Module H: FAIL, BUG-UPDATER-03
- Module E: FAIL, BUG-UPDATER-02 startup convergence
- destructive relationship preservation: FAIL, BUG-HISTORY-05

lens_coverage_current_sha:
- L1: BASELINE_FAIL
- L2: DEEP_FAIL
- L3: BASELINE
- L4: DEEP_FAIL
- L5: DEEP
- L6: DEEP_FAIL

primary_deep_lens: L4
primary_deep_selection_reason: R1/R2 direct destructive relationship-loss root
remaining_not_yet_deep: L1,L3
next_lens_hint: L1

independent_execution: NOT_EXECUTED
