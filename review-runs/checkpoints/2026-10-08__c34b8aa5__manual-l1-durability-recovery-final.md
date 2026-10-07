# Manual correctness review — c34b8aa5 — L1 durability/recovery deep — final

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: FINAL
manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: f014a6f197f63d40b88efd5bfdd1cf87bece3190
review_parent_sha: 5578ff6a681ea1a216e84311a850f097eff030cd

pinned_implementation_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
run_basis_switched: NO
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

write_scope: REVIEW_REMediation_ONLY
private_handoff_write: NO
persisted_prompt_write: NO
production_source_write: NO

overall_verdict: NOT_CLEAN
primary_deep_lens: L1 Durability & recovery
primary_deep_lens_result: DEEP_FAIL
new_finding_ids: NONE
reopened_finding_ids: NONE
same_root_residuals_confirmed: BUG-SCHEDULER-01
repository_semantic_root_count_delta: 0
active_download_count_delta: 0
independent_execution: NOT_EXECUTED

## Independent verdict

c34b8aa5 is NOT_CLEAN.

Fresh source-semantic review independently confirms the accepted-terminal scheduler-carrier loss on exact
c34b8aa5. No new semantic root is created: it is a same-root residual of existing OPEN P2
BUG-SCHEDULER-01.

The two c34 scheduler corrections were also inspected end-to-end:
- BUG-SCHEDULER-WINDOW-01 old end-minute contradiction is not reproduced in current source.
- BUG-SCHEDULER-RESTORE-01 old unvalidated/unconverged Restore path is not reproduced in current source.

Neither is promoted to VERIFIED_CLOSED by this run because independent exact-SHA execution was not
performed and the reported implementation closure set was incomplete.

## Findings

### BUG-SCHEDULER-01 — SAME_ROOT_RESIDUAL CONFIRMED P2

Current exact chain:
scheduler START/END carrier
-> exact Operation.result acceptance
-> carrier state ACCEPTED
-> worker must retire exact carrier after semantic completion
-> WorkInfo becomes FAILED/CANCELLED first
-> reconcileCarrier has no scheduler START/END accepted-terminal retry branch
-> generic deleteAccepted removes the durable responsibility.

retryAfterFailure already provides exact old-request -> new-request CAS, current-generation fencing,
retained-boundary checks and bounded retry. The correction should route only current accepted scheduler
FAILED/CANCELLED carriers through that existing mechanism and refuse superseded generations.

Required controls:
START failed, END failed, current cancelled without supersession, settings supersession, Restore
supersession, successful exact retirement non-replay, and existing missing/unfinished controls.

### Existing L1 roots revalidated

BUG-DATE-03 remains OPEN P2: durable HistoryDateFetch operation/children precede enqueue, while both
ordinary start and startup reconcile discard enqueueUniqueWork Operation.result.

BUG-HARDSUB-GENERATION-01 remains OPEN P2: HardSubScanWorker still does not consume its durable
handoff/request generation as semantic authority; pending-marker check and Download insert remain separate.

### Changed stopped-execution ownership path

No new root established. Exact E1 retirement uses token-qualified map removal; a current E2 row permits
only E1 retirement, and unresolved native-process ownership remains a claim barrier.

## BASELINE L1-L6

- L1: DEEP_FAIL — BUG-SCHEDULER-01 residual, BUG-DATE-03, BUG-HARDSUB-GENERATION-01.
- L2: BASELINE_FAIL — HISTORY-CONTENT-AUTHORITY-ALIAS-01, BUG-OBSERVE-SOURCE-IDENTITY-01,
  BUG-LINK-INPUT-01 remain exact-source reproducible.
- L3: BASELINE_FAIL — BUG-SCHEDULER-06 claim authority still omits current due-time/prior
  downloadStartTime or schedule-generation identity.
- L4: BASELINE_FAIL — BUG-DOWNLOAD-DELETE-SNAPSHOT-01 remains in ordinary status sweeps because
  deleteKnownUserRemoval receives expectedStatus=null.
- L5: BASELINE_FAIL — WORKER-FOREGROUND-COMPLETION-01 remains in CleanUpLeftoverDownloads and
  MoveCacheFilesWorker through unawaited setForegroundAsync before correctness-relevant effects.
- L6: BASELINE_FAIL — BUG-SCHEDULER-04 remains: scheduler END still cancels all work tagged "download",
  including independent future delayed individual schedules; BUG-INCOGNITO-01 also retains ANY-vs-ALL
  mixed-selection semantics.

## Trigger disposition

scheduler handoff/recovery: FAIL_EXISTING_ROOT BUG-SCHEDULER-01.
scheduler window composition: OLD_FAILURE_NOT_REPRODUCED; closure NOT_VERIFIED.
scheduler Restore validation/convergence: OLD_FAILURE_NOT_REPRODUCED; closure NOT_VERIFIED.
stopped execution owner cleanup: PASS_NO_NEW_ROOT.
HistoryDateFetch carrier: FAIL_EXISTING_ROOT BUG-DATE-03.
HardSub generation: FAIL_EXISTING_ROOT BUG-HARDSUB-GENERATION-01.
foreground completion: FAIL_EXISTING_ROOT WORKER-FOREGROUND-COMPLETION-01.
daily-window vs individual delayed scheduling: FAIL_EXISTING_ROOT BUG-SCHEDULER-04.

## Review retrospective

The important distinction on c34b8aa5 is between source correction and verified closure. The new window
and Restore code removes the old semantic paths, but incomplete exact-SHA execution does not authorize
a final closure claim. The accepted-terminal carrier loss is independently source-reproducible and is
already inside BUG-SCHEDULER-01's persistent daily recurrence/restart contract, so counting a new root
would double-count one durability invariant.

## Checklist evolution

checklist_change_required: NO

The governing v7 checklist already covers durable recovery, async acceptance, exact generation identity,
process-death discoverability, destructive ownership, platform completion and final-effect composition.
The finding is a review-depth/application issue, not a governance gap.

## Checkpoint summary

manual_review_run_status: FINAL
pinned_sha_reviewed: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
primary_lens_promoted: L1_DURABILITY_AND_RECOVERY_DEEP_FAIL
new_root_ids: NONE
same_root_residuals: BUG-SCHEDULER-01
repository_count_change: NONE
active_download_count_change: NONE
remaining_not_yet_deep_on_pinned_sha: L2,L3,L4,L5,L6
next_lens_if_same_sha_review_resumes: L2_IDENTITY_AND_PROVENANCE
next_lens_selection_reason: R2/R5
private_handoff_changed: NO
persisted_prompt_changed: NO
production_source_changed: NO
INDEPENDENT EXECUTION: NOT EXECUTED
