# Manual correctness review — eda6a758 — L1 durability/recovery deep — final

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: FINAL
manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: e5f61954c20a56515d0677e9bf018061d43dbe64
review_parent_sha: f112fecacf075fb9b0eddb49ddc9169cd178a8a0

pinned_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
newer_implementation_head_observed: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
newer_implementation_inspected: NO
newer_implementation_review_status: UNREVIEWED
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

Pinned implementation eda6a758 remains NOT_CLEAN.

This same-SHA pass independently recomputed the L1-L6 baseline and promoted L1 Durability & recovery
from BASELINE to DEEP. It established no new semantic root ID and did not reopen a previously closed root.

One previously unrecorded failure mode is confirmed as a same-root residual of existing OPEN P2
BUG-SCHEDULER-01: an accepted scheduler START/END WorkManager request that finishes FAILED/CANCELLED
before exact semantic retirement can lose its durable carrier during startup reconciliation. That is a
failure of the already-owned persistent daily scheduler/restart/re-arm contract, not a new root.

Existing OPEN L1 roots were also revalidated directly in exact pinned source:
- BUG-DATE-03 — durable date-fetch operation can outlive unobserved WorkManager enqueue failure;
- BUG-HARDSUB-GENERATION-01 — HardSub worker does not consume its durable handoff generation and
  replacement admission remains split check/insert;
- BUG-SCHEDULER-RESTORE-01 — restored scheduler final image and external scheduler owner can diverge.

The implementation branch advanced during this run to c34b8aa57e01803c9960e4ad873d1ed5b68e019c.
That newer implementation was not inspected and is not covered by this verdict.

## L1-L6 baseline recomputation

lens_coverage_pinned_sha:
- L1: DEEP_FAIL
- L2: BASELINE_FAIL
- L3: BASELINE_FAIL
- L4: BASELINE_FAIL
- L5: BASELINE_FAIL
- L6: BASELINE_FAIL

### L1 — Durability & recovery — DEEP_FAIL

Confirmed current production failures:
- BUG-DATE-03: HistoryDateFetchRepository durably creates/reconnects operation and child state before
  HistoryDateFetchManager.enqueue(); both ordinary and startup paths discard WorkManager Operation.result,
  so enqueue failure can leave a nonterminal operation without confirmed execution carrier.
- BUG-HARDSUB-GENERATION-01: prepareHardSub establishes exact durable handoff/request identity, but
  HardSubScanWorker does not consume handoffId/requestId/generation before History state or Download
  publication. countPendingByPlaylistMarker() and insert() remain separate.
- BUG-SCHEDULER-01 same-root residual: accepted scheduler START/END FAILED/CANCELLED requests are not
  retried by a scheduler-specific exact-carrier branch and can fall through generic carrier deletion.

Negative checks:
- Download execution recovery discovery retains opaque/unavailable namespaces fail-closed and includes
  running rows, journals, native markers/processes, producer records, orphan carrier evidence and pending
  primary-success authority.
- Cleanup occurrence state is exact generation/cadence/occurrence journaled and unfinished suffixes remain
  recoverable; exact scheduled cleanup status targets are revalidated transactionally.
- History destructive-file deletion revalidates selected record snapshots and retained references under
  shared HistoryReferenceMutationCoordinator ownership.
- Observe ordinary generation handoff and recurrence publication remain generation fenced in exact source.

### L2 — Identity & provenance — BASELINE_FAIL

Current exact production still contains independently established identity failures:
- HISTORY-CONTENT-AUTHORITY-ALIAS-01: HistoryFileDeletion target identity case-folds content-provider
  authority before combining it with provider-defined documentId.
- BUG-OBSERVE-SOURCE-IDENTITY-01: Observe USER source durable identity is numeric row id; admission checks
  raw URL equality separately from insert and lacks canonical semantic-source uniqueness.
- BUG-LINK-INPUT-01: ShareActivity routes data.extractURL() into Result/Download construction and background
  queue publication without first applying the typed WebUrlInput normalization/rejection contract.

No new L2 root was established by this L1 pass.

### L3 — Concurrency & authority — BASELINE_FAIL

Current existing authority race remains production-reachable:
- BUG-SCHEDULER-06: a due Scheduled row may be observed, then successfully rescheduled to a future
  downloadStartTime, while the stale worker claim still succeeds because claim authority does not include
  current due-time or prior schedule-generation identity.

The new BUG-SCHEDULER-01 residual also demonstrates an authority/liveness mismatch between accepted
scheduler request state and durable recurrence ownership, but is counted only under its existing root.

### L4 — Destructive ownership — BASELINE_FAIL

Current exact source preserves existing destructive-authority failures:
- BUG-DOWNLOAD-DELETE-SNAPSHOT-01: ordinary status sweeps such as deleteScheduled/deleteErrored/
  deleteQueued/deletePaused capture status-owned Download snapshots but call deleteKnownUserRemoval with
  expectedStatus=null; the final Room deletion can therefore act on a materially changed current row by id.
- HISTORY-CONTENT-AUTHORITY-ALIAS-01 can collapse exact provider namespaces before destructive target
  collapse/removal decisions.

Negative check:
- the dedicated scheduled cleanup worker is stronger than the ordinary status sweeps: it passes
  expectedStatus and revalidates id/status/operationId/executionId/downloadStartTime in the deletion
  transaction. This does not close BUG-DOWNLOAD-DELETE-SNAPSHOT-01 because the ordinary sweep APIs remain.

### L5 — Platform contract closure — BASELINE_FAIL

WORKER-FOREGROUND-COMPLETION-01 remains source-reproducible:
- CleanUpLeftoverDownloads calls setForegroundAsync() and proceeds into destructive-effect admission
  without owning completion;
- MoveCacheFilesWorker calls setForegroundAsync() and proceeds into filesystem mutation;
- UpdateMultipleDownloadsFormatsWorker calls setForegroundAsync() and proceeds into durable format/result
  mutation; its per-item semantic failures remain separately owned where applicable by BUG-FORMAT-BG-03.

Safe controls exist in the same codebase:
- ObserveSourceWorker uses suspending setForeground() before continuing its current-generation effect;
- HardSubScanWorker uses suspending setForeground() and explicitly classifies foreground setup failure.

No new platform root was created by this pass.

### L6 — Cross-feature semantic propagation — BASELINE_FAIL

Scheduler propagation still fails exact-source composition:
- ScheduledDownloadWindow.contains() includes the configured end minute through its full minute;
- nextEnd() publishes that configured minute at HH:mm:00.000 and can select a boundary already earlier
  within the current end minute;
- AlarmScheduler publishes the exact nextEnd timestamp into both AlarmManager and the durable scheduler
  handoff notBeforeAt;
- queue/worker membership can therefore continue to treat the end minute as active after the external
  end owner already fired.
This remains BUG-SCHEDULER-WINDOW-01, not a new root.

Restore propagation also remains open under BUG-SCHEDULER-RESTORE-01: the restored scheduler preference
image is not yet proven to validate and converge one exact external AlarmManager/WorkManager owner across
merge/reset/recovery.

## Triggered-module / contract disposition

1. WorkManager scheduler handoff/recovery
   - FAIL_EXISTING_ROOT
   - BUG-SCHEDULER-01 same-root accepted FAILED/CANCELLED carrier-loss residual confirmed.
   - No duplicate root created.

2. HistoryDateFetch durable operation -> WorkManager carrier
   - FAIL_EXISTING_ROOT
   - BUG-DATE-03 revalidated OPEN P2.

3. HardSub generation and replacement admission
   - FAIL_EXISTING_ROOT
   - BUG-HARDSUB-GENERATION-01 revalidated OPEN P2.

4. Restore/import persistence -> scheduler external authority
   - FAIL_EXISTING_ROOT
   - BUG-SCHEDULER-RESTORE-01 remains OPEN P2.
   - No additional Restore lifecycle root established.

5. History destructive filesystem/reference ownership
   - PASS_NO_NEW_ROOT for the inspected final-revalidation/relationship-lock boundary.
   - Existing HISTORY-CONTENT-AUTHORITY-ALIAS-01 remains OPEN on target identity, not a new race.

6. Scheduled cleanup exact-target/recovery path
   - PASS_NO_NEW_ROOT for its exact occurrence journal and expected-status deletion path.
   - Existing BUG-DOWNLOAD-DELETE-SNAPSHOT-01 remains OPEN in separate ordinary status-sweep APIs.

7. Observe generation/recurrence
   - PASS_NO_NEW_L1_ROOT.
   - Exact configurationGeneration is carried and checked at worker/final-effect boundaries;
     recurring successor is staged through durable handoff and convergence.
   - BUG-OBSERVE-SOURCE-IDENTITY-01 remains OPEN under L2 source identity.

8. Foreground-establishment completion
   - FAIL_EXISTING_ROOT.
   - WORKER-FOREGROUND-COMPLETION-01 remains OPEN/REOPENED P2.

## BUG-SCHEDULER-01 same-root residual detail

producer:
AlarmScheduler scheduled START/END boundary publication

durable carrier:
WorkManagerHandoffCarrier with exact handoffId/generationId/requestId/boundary/notBeforeAt

async acceptance:
enqueueAndAwait promotes the exact carrier to ACCEPTED

consumer:
DownloadWorker or CancelScheduledDownloadWorker

semantic retirement:
exact scheduler carrier is retired only after successful consumer semantic completion

failure:
accepted worker request reaches FAILED or CANCELLED before exact retirement

recovery:
WorkManagerHandoffRecovery.reconcileCarrier() has explicit accepted-failure retry ownership for several
other handoff kinds but not scheduler START/END; after the generic unfinished guard the scheduler carrier
can fall through accepted-carrier deletion

final effect:
the daily boundary owner disappears without completed START/END semantics, replacement, or next-boundary
re-arm; persistent scheduler behavior can stop until another unrelated mutation republishes authority

root boundary:
same root as BUG-SCHEDULER-01 because that root already owns persistent daily recurrence, restart/re-arm,
and settings-replacement semantics

minimal correction contract:
- retain/retry current accepted scheduler START/END FAILED/CANCELLED requests by exact carrier identity;
- refuse replay after superseding settings/Restore generation or exact successful retirement;
- preserve process-death discoverability until completion/replacement/supersession is durable.

closure rejection conditions:
- generic deletion of a current accepted failed/cancelled scheduler carrier;
- retry that does not prove current exact generation/boundary;
- retry after newer scheduler authority superseded the request;
- success path that leaves a replayable carrier after exact semantic retirement.

required deterministic controls:
- START accepted -> FAILED -> restart -> exact retry;
- END accepted -> FAILED -> restart -> exact retry;
- START/END accepted -> CANCELLED without supersession -> exact retry;
- settings supersession E1 -> E2 prevents E1 resurrection;
- Restore supersession E1 -> restore E2 prevents E1 resurrection;
- exact successful retirement is not replayed.

## Review retrospective

This pass found no new root because the newly exposed scheduler failure is not semantically independent:
the original BUG-SCHEDULER-01 contract already requires recurrent daily scheduling and restart/re-arm
survival. Treating accepted-request carrier loss as a new ID would double-count one final durability
invariant.

The strongest negative controls came from neighboring production paths rather than tests:
- exact scheduled cleanup revalidates frozen target ownership while ordinary status sweeps do not;
- Observe and HardSub show contrasting foreground establishment semantics;
- several WorkManager handoff kinds already preserve accepted failed/cancelled responsibility, making the
  scheduler fallthrough distinguishable rather than speculative.

## Checklist evolution

checklist_change_required: NO

The governing v7 checklist already requires:
- durable state-machine/process-death closure;
- async acceptance before durable state depends on platform work;
- exact generation/owner identity;
- recovery discoverability;
- final-effect composition;
- platform async-completion ownership.

The scheduler residual was found by applying those existing obligations more deeply. No governance text
change is required by this run.

## Checkpoint summary

manual_review_run_status: FINAL
pinned_sha_reviewed: eda6a7589af3a19a97eb38e869b47dabaf74388b
primary_lens_promoted: L1_DURABILITY_AND_RECOVERY_DEEP_FAIL
new_root_ids: NONE
same_root_residuals: BUG-SCHEDULER-01
repository_count_change: NONE
active_download_count_change: NONE
remaining_not_yet_deep_on_pinned_sha: L2,L3,L4
next_lens_if_same_sha_review_resumes: L2_IDENTITY_AND_PROVENANCE
next_lens_selection_reason: R2/R5 — multiple current open roots are identity/provenance failures and L2 is the next not-yet-DEEP lens after L1.
newer_implementation_head: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
newer_implementation_inspected: NO
private_handoff_changed: NO
persisted_prompt_changed: NO
production_source_changed: NO
INDEPENDENT EXECUTION: NOT EXECUTED
