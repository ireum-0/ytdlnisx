# Manual correctness review — db339e1 L3 concurrency and authority — FINAL

date: 2026-10-12
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: FINAL
manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 262b8eac5c48995cdbfef95f7f8370ce0188568e
review_expected_parent: 262b8eac5c48995cdbfef95f7f8370ce0188568e
previous_manual_review: review-runs/checkpoints/2026-10-12__db339e1__manual-l6-cross-feature-final.md
previous_manual_review_status: FINAL
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
scope: SAME_IMPLEMENTATION_SHA_NEW_MANUAL_RUN_L1_L6_BASELINE_L3_DEEP
run_completed_scope: BASELINE_L1_L6,TRIGGER_MAP,TRIGGERED_MODULE_B,TRIGGERED_MODULE_F,TRIGGERED_MODULE_H,TRIGGERED_MODULE_I,TRIGGERED_PLATFORM_COMPLETION,L3_DEEP,FINAL_ROOT_RECOUNT
run_remaining_scope: NONE
independent_execution: NOT_EXECUTED
production_source_changed: NO
repository_wide_verdict: NOT_CLEAN
active_download_canonical_p0: 0
active_download_canonical_p1: 0
active_download_canonical_p2: 8
repository_discovered_roots: 148
repository_open_roots: 57
repository_closed_roots: 91
new_root_count_delta: 0
reopened_root_count_delta: 0
closed_root_count_delta: 0
accepted_or_waived_root_delta: 0

## 1. Independent verdict and pinned basis

NOT_CLEAN. This is a *second, distinct* manual review of exact production commit
`db339e1`, not a repetition of the preceding L6 FINAL verdict. The preceding
manual run is FINAL with review start parent `cf79265`; no manual IN_PROGRESS
run was present when this run began. The source change at this SHA principally
adds exact accepted-terminal FAILED/CANCELLED handoff retry in
`WorkManagerHandoffRecovery.reconcileCarrier`, with instrumentation test
changes. This review recomputes the L1-L6 baseline, follows the changed
handoff's callers/DAOs/worker admission/recovery, and promotes L3 DEEP due to
independently reachable stale scheduler claim authority.

The eight *active-download remediation* P2 roots stay canonically open in
the prior inventory; source non-reproduction of the earlier window/restore
paths is not executable closure evidence. The repository-wide 148/57/91
counts and separately recorded scheduler-04/scheduler-06 roots remain
unchanged. No new root is inferred from an existing subcase or alias.

## 2. Findings — production producer/carrier/consumer/final effect

### L3-1 — BUG-SCHEDULER-06 (existing P2, VERIFIED_OPEN; not a new root)

Producer: `DownloadViewModel.rescheduleExistingDownload` at lines 1812-1817
invokes `DownloadDao.rescheduleQueuedOrScheduled` after preliminary refusal
convergence, then starts scheduling. The DAO at lines 1125-1136 changes
`downloadStartTime`, `status` and `executionId`, but *does not* advance
`operationId` or `retryAttempt` or a scheduler generation.

Carrier: `DownloadRepository.startDownloadWorkerInternal` (4625-4697)
partitions current scheduled rows into immediate and future groups and
publishes WorkRequests including priority IDs. Separately,
`DownloadWorker.observeQueuedDownloadsAfterRecovery` (281-289)
constructs due-time-filtered queue snapshots with the current clock.
These are revocable observations: a user may reschedule the row after
selection, including across a suspension or worker scheduling race.

Consumer/final authority: `DownloadDao.claimDownloadForWorker` (449-476)
atomically transitions `Queued` *or* `Scheduled` to `Active` using
id, operationId and retryAttempt, but has neither
`downloadStartTime <= admittedNow` nor the selected schedule generation
or exact expected due-time. `claimDownloadForWorkerAndRead` (484-509)
correctly rolls back a failed materialization, but cannot revalidate an
omitted schedule condition. Thus a previously due candidate can claim an
otherwise legitimate same-operation row now rescheduled into the future.
A later WorkRequest/queue refresh does not retrospectively undo the
successful claim. This is the existing scheduler claim-authority root;
the ordinary WorkManager timing mechanism does not close the Room CAS race.

Required correction boundary: revalidate *current* due eligibility and
schedule/selection authority at the same transactional claim, ensuring
intentional reset-to-Queued semantics remain supported. Test the
old-observation -> later reschedule -> stale claim interleaving and the
valid same-generation due claim; cover priority and non-priority paths.

### L3-2 — BUG-SCHEDULER-04 (existing P2, VERIFIED_OPEN; distinct root)

Producer: `DownloadRepository.startDownloadWorkerInternal` (4652-4656,
4665-4690) publishes independently future-scheduled DownloadWork
requests tagged `download`.
Consumer: `CancelScheduledDownloadWorker.doWork` checks exact
SCHEDULE_END handoff generation/request, but within mutation admission
(lines 45-68) invokes
`WorkManager.cancelAllWorkByTag("download").result.get(...)`.
Final effect: *all* tagged DownloadWork requests are cancelled, not only
executions belonging to the daily-window owner. Exact per-execution
native cancellation and requeue guards further down the worker do not
repair the earlier global WorkManager cancellation. This is separate
from end-minute window membership semantics and from scheduler-06 claim
CAS. The cancellation Operation is awaited, so the issue is scope, not
ignored asynchronous completion. Preserve unrelated future requests.

### L3-3 — BUG-SCHEDULER-01 handoff residual (SOURCE_FIXED; no new root)

`WorkManagerHandoffRecovery.reconcileCarrier` (1446-1565) now routes
accepted SCHEDULE_START/SCHEDULE_END FAILED or CANCELLED WorkInfo to
`retryAfterFailure` before the generic deleteAccepted branch.
`retryAfterFailure` (2115-2290) rereads exact request identity, checks
current generation/retained boundary and applies `advanceRetry` CAS.
`WorkManagerHandoffCarrierDao.advanceRetry` requires
handoffId+oldRequestId and PENDING/ACCEPTED state; it creates a new
requestId/PENDING_ENQUEUE carrier. `scheduleRetry` reads the current
persistent carrier and retries through `performAttempt`.
`schedulerWorkRequestDisposition` (1093-1123) compares carrier kind,
handoff, requestId, optionally generation/boundary, actual WorkRequest
ID and outstanding boundary owner, and workers gate effects on CURRENT.
`retireSchedulerWorkRequest` deletes only exact accepted handoffId/
requestId. E1 cannot turn a new E2 carrier into its own request by an
unfenced accepted-terminal path. This establishes source-level closure
of the targeted retry gap, *not* a new independent runtime test PASS.

### L3-4 — Other open authority effects (existing, independently traced)

`DownloadViewModel.continueUpdatingFormatsOnBackground` (2942-2969)
moves every Processing row to Saved before enqueueing selected IDs,
and never awaits `Operation.result`. `UpdateMultipleDownloadsFormatsWorker`
(45-100) swallows per-item exceptions with `runCatching`, later copies
fresh status/execution fields into a stale full-row format snapshot
before `updateIfExecutionOwned`/`updateWithoutUpsert`, and returns
batch success/updated notification despite failures. The same worker
passes a class-name cancellation tag to notification instead of the
WorkRequest's `updateFormats` tag; it invokes `setForegroundAsync`
without awaiting establishment before database/network effects.
These remain separate pre-existing BG01..BG05 and
WORKER-FOREGROUND-COMPLETION-01 roots; do not count each repeated
manifestation as another semantic root.

`DownloadRepository.deleteScheduled/deleteErrored/deleteQueued/deletePaused`
(3231-3255) pass captured lists to `deleteKnownUserRemoval`, while
that helper (4244 onward) passes captured rows through unmodified
when expectedStatus is null, unlike the exact cleanup callers with
expectedStatus set. The snapshot is not authority at the actual
Room deletion; this is the pre-existing BUG-DOWNLOAD-DELETE-SNAPSHOT-01
destructive-ownership root, not a new scheduler subcase.

## 3. L1-L6 baseline, triggered contracts, and matrix

lens_coverage_current_sha:
- L1 DURABILITY_RECOVERY: BASELINE_FAIL — format enqueue after a
  durable Processing->Saved mutation ignores Operation.result; scheduler
  exact accepted FAILED/CANCELLED retry is improved but does not cure
  unrelated delivery debt.
- L2 IDENTITY_PROVENANCE: BASELINE_FAIL — content-document key
  construction still normalizes authority with
  `uri.authority.lowercase(Locale.US)` in
  `HistoryFileDeletion.kt` (175-180), a known namespace-identity
  defect; distinct identity contracts must not be conflated.
- L3 CONCURRENCY_AUTHORITY: DEEP_FAIL — scheduler-06 claim after a
  schedule change, scheduler-04 broad tag cancel and BG04 stale
  full-row publication all fail exact final-authority tests.
- L4 DESTRUCTIVE_OWNERSHIP: BASELINE_FAIL — captured unguarded
  `deleteKnownUserRemoval` lists may be stale; WorkManager tag
  cancellation also widens effect ownership.
- L5 PLATFORM_CONTRACT: BASELINE_FAIL — foreground completion is
  not awaited in format worker; no new manifest/capability decision.
- L6 CROSS_FEATURE_PROPAGATION: DEEP_FAIL (prior independent FINAL
  at this exact SHA) — five BG roots, incognito mismatch and
  scheduler cancellation remain open. This run independently
  revisited format producer/consumer/final-effect paths.

trigger_map:
- B WorkManager/scheduler request-acceptance-recovery: TRIGGERED;
  source-level accepted-terminal retry PASS for exact current carrier,
  distinct scheduler-04 and scheduler-06 authority FAIL.
- F old persisted carrier / restart: TRIGGERED by ACCEPTED pre-existing
  rows; DAO getOutstanding includes ACCEPTED, retry uses durable
  request and boundary CAS. Historical-format migration is
  NOT_APPLICABLE (no representation/schema delta).
  Independent process-death runtime PASS NOT_VERIFIED.
- H persistent scheduler preferences/Restore: TRIGGERED due executable
  scheduler fan-out; old scheduler-window and scheduler-restore
  source failures were NOT_REPRODUCED in prior exact-SHA L6 run;
  runtime final closure NOT_VERIFIED; no closure reclassification.
- I maintenance-vs-live-owner: TRIGGERED by daily END cancellation and
  future sibling request; FAIL_EXISTING BUG-SCHEDULER-04.
- A foreground/platform completion: TRIGGERED by background format
  effects; FAIL_EXISTING WORKER-FOREGROUND-COMPLETION-01.
- C provider namespace: existing identity violation remains baseline
  FAIL; no new representation-change adoption is claimed.
- D packaged resource and E referenced-artifact generation: no
  newly changed contract or relevant trigger proved in this delta.

Mandatory authority/cross-attempt matrix:
A no live owner, due row -> claim is valid only for current due state;
B live E2 after stale E1 schedule observation -> E1 must fail CAS
(CURRENTLY FAILS if opId and retryAttempt remain unchanged);
C old END carrier vs new boundary carrier -> exact handoff/request
disposition protects newer carrier;
D new future request after old daily window END -> tag-wide cancel
still incorrectly affects the independent owner;
E old format snapshot vs newly edited row -> stale full-row effect
still survives partial execution-token refresh;
F restore ACTIVE vs ordinary scheduler mutation -> admission protects
known restore transaction paths, but independent runtime closure
NOT_VERIFIED.

Fault matrix: (1) rejected claim materialization rolls back Room CAS;
(2) accepted scheduler WorkInfo FAILED/CANCELLED retains/retries exact
carrier in source; (3) stale due selection followed by rescheduling
has no terminal due-time CAS; (4) selected format mutation then
enqueue failure has no acknowledged recovery owner; (5) first
format-item fetch failure is swallowed and later siblings are
processed but false batch success survives; (6) tag-wide cancellation
is awaited yet mis-targeted. No new executable tests were run by the
independent reviewer.

## 4. Review retrospective and checklist evolution

This review used producer -> carrier -> consumer -> final effect,
not a commit-diff-only review. Source-level correctness of the
six-line handoff retry fix is narrower than correctness of the daily
window's final WorkManager effects, and narrower again than current
due-time claim CAS. The useful blind-spot lesson is that
`operationId+retryAttempt` can prove one queue operation while failing
to prove a separately mutable *schedule decision*. The missing
schedule dimension is demonstrated by the production
`rescheduleQueuedOrScheduled` writer, not inferred from a test name.

checklist_change_required: NO
reason: v7 already requires exact mutation-boundary revalidation,
positive live authority, cancellation scope, old-carrier restart and
cross-feature closure. The missed coverage was review depth on L3,
not missing governance. No source, checklist, plan, ledger, prompt,
or migration modifications were made.

## 5. Final checkpoint scope and next lens

manual_review_run_status: FINAL
primary_deep_lens: L3_CONCURRENCY_AND_AUTHORITY
primary_deep_selection_reason: R1 current scheduler claim-authority
and R2 WorkManager cancellation / format stale-state effects
deep_lenses_on_exact_db339e1: L3,L6
remaining_not_yet_deep: L1,L2,L4,L5
next_not_yet_deep_lens: L1_DURABILITY_AND_RECOVERY
next_lens_selection_reason: R2 durable format enqueue and
scheduled WorkManager/restart authority are unresolved
new_root_count_delta: 0
canonical_disposition_change: NONE
active_download_p2_count_change: NONE
independent_execution: NOT_EXECUTED
newer_implementation_head: NONE_OBSERVED_AT_PREWRITE
review_branch_final_readback: PENDING_VERIFICATION
