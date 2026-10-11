# Manual correctness review — db339e1 L6 cross-feature propagation — FINAL

date: 2026-10-12
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: FINAL
manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: cf79265fed7c782c81c16f4c8a22b17d3423645b
review_expected_parent: cf79265fed7c782c81c16f4c8a22b17d3423645b
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
implementation_newer_head: NONE_OBSERVED
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
production_source_changed: NO
private_handoff_changed: NO
persisted_prompt_changed: NO
independent_execution: NOT_EXECUTED

## 1. Independent verdict

NOT_CLEAN on pinned db339e1. Independent baseline L1-L6 performed and L6 Cross-feature semantic
propagation promoted DEEP_FAIL. This is a NEW SHA review, not a reuse of the c34b8aa5
completion/L1 verdict. Existing independent completion review of BUG-SCHEDULER-01 is separate
manual_review_run:NO. No earlier manual IN_PROGRESS run exists at this SHA. An attempt to
write an IN_PROGRESS start checkpoint was safety-blocked without publication, so this
self-contained FINAL records the initial basis, completed scope and result.

The exact implementation change from c34b8aa5 consists of 6 production lines in
WorkManagerHandoffRecovery.reconcileCarrier, plus 4 focused instrumentation test files.
No migration/schema/permission/runtime changes are present. Newer implementation not observed.
The source-confirmed scheduler accepted-terminal FAILED/CANCELLED loss is addressed without
broadening other kinds. The governing 8 active P2 roots and repository-wide count are unchanged:
no new semantic-root ID, alias, reopened root or closed canonical root is claimed.

## 2. Findings and cross-feature contracts

BUG-SCHEDULER-01 accepted-terminal residual — SOURCE_FIXED on db339e1; no new root.
WorkManagerHandoffCarrier START/END exact request acceptance -> ACCEPTED;
WorkInfo FAILED/CANCELLED now dispatches retryAfterFailure before generic deleteAccepted.
The retry path rereads current carrier, requires request/generation/retained-boundary authority,
advances old requestId to new requestId with DAO CAS, stages PENDING_ENQUEUE and resumes
WorkManager enqueue. DownloadWorker and CancelScheduledDownloadWorker validate exact request
at worker admission; successful semantic retirement remains exact. This preserves a failed
current daily boundary carrier across restart and refuses superseded stale E1.
Prior completion-review reports 152 PASS / 0 FAIL / 0 SKIP on exact published SHA,
not independent reviewer execution.

BUG-SCHEDULER-WINDOW-01 — old failure path NOT_REPRODUCED in pinned source;
canonical VERIFIED_CLOSED remains NOT_VERIFIED in this manual run.
ScheduledDownloadWindow.contains treats start/end minutes inclusively. nextEnd chooses the
end-minute's calendar day and adds one minute, handling 23:59 rollover. AlarmScheduler publishes
the resulting timestamp to exact AlarmManager END and to its durable fallback notBeforeAt;
ScheduleAlarmReceiver/CancelScheduleAlarmReceiver pass exact handoffId to enqueueAndAwait.
DownloadViewModel uses AlarmScheduler.isDuringTheScheduledTime, and DownloadWorker's scheduler
idle-stop check consumes the same predicate. The former contradiction (05:00:xx still allowed
while END already fired at 05:00:00) is not reproducible on this exact source.
No independent executable exact-SHA closure is claimed by this reviewer.

BUG-SCHEDULER-RESTORE-01 — old source defect NOT_REPRODUCED; closure NOT_VERIFIED.
BackupRestoreParser validates portable scheduler times/Boolean. Merge/Reset generate durable
scheduler RESTORE transition before preference commit; scheduler effect revokes prior owners,
publishes exact START then END with recorded phase progress; Reset owner and ordinary Merge
startup reconciliation retain separate compatible authority. No new Restore root proven.

BUG-SCHEDULER-04 — VERIFIED_OPEN existing P2.
Independent per-item future schedules are published as WorkRequests tagged "download" by
DownloadRepository.startDownloadWorkerInternal. Scheduler END consumer calls
WorkManager.cancelAllWorkByTag("download") before snapshotting active rows, so an
independent future delayed request is cancelled by daily-window shutdown. The per-running
execution/native cleanup uses stricter id/token fencing but does not scope WorkManager
tag cancellation. New root delta zero; not BUG-SCHEDULER-WINDOW-01.

BUG-FORMAT-BG-01 — VERIFIED_OPEN existing P2.
DownloadMultipleBottomSheetDialog.onContinueOnBackground passes selected IDs, but
DownloadViewModel.continueUpdatingFormatsOnBackground calls moveProcessingToSavedCategory()
without selection. DownloadDao.updateProcessingtoSavedStatus mutates all eligible
Processing rows, including unselected siblings, before background worker publication.

BUG-FORMAT-BG-02 — VERIFIED_OPEN existing P2.
Saved conversion durably commits before enqueueUniqueWork; WorkManager Operation.result
is not observed and no exact accepted/recovery carrier is established for the format batch.

BUG-FORMAT-BG-03 — VERIFIED_OPEN existing P2.
UpdateMultipleDownloadsFormatsWorker catches per-item format fetch/Result/Download mutations
with runCatching, increments progress, and returns Result.success even when an item failed;
terminal notification can claim updates for failed items.

BUG-FORMAT-BG-04 — VERIFIED_OPEN existing P2.
Worker fetches format data using pre-fetch Download row; after external work it rereads current
status/execution fields but republishes other mutable fields from the stale row through
dao.updateIfExecutionOwned or dao.updateWithoutUpsert. The current execution token is not
a complete configuration/result provenance proof.

BUG-FORMAT-BG-05 — VERIFIED_OPEN existing P2.
OneTimeWorkRequest receives tag "updateFormats" while progress notification passes
UpdateMultipleDownloadsFormatsWorker::class.java.name to NotificationUtil as workTag.
CancelWorkReceiver invokes cancelAllWorkByTag(workTag). The cancellation tag differs from
the WorkRequest tag; exact batch/request authority is not carried or acknowledged.

BUG-INCOGNITO-01 — VERIFIED_OPEN existing P2.
areAllProcessingIncognito returns (incognitoCount > 0) for all or selected Processing IDs;
DownloadMultipleBottomSheetDialog uses that Boolean to display toggled state.
When selection is mixed, ANY true is misrepresented as ALL true and a tap can disable
incognito for all selected rows. DownloadWorker independently gates logging/History with
each actual downloadItem.incognito, so misleading selection UI crosses into real History
publication on false members.

## 3. L1-L6 baseline recomputation

L1 BASELINE_FAIL: WorkManager scheduler accepted-terminal correction verified in source;
separate BUG-DATE-03 date-fetch enqueue does not await Operation.result; existing
BUG-HARDSUB-GENERATION-01 does not consume exact handoff generation.
L2 BASELINE_FAIL: HistoryFileDeletion content provider authority is lowercased in key;
Observe USER source identity uses raw URL instead of canonical unique key.
L3 BASELINE_FAIL: DownloadDao.claimDownloadForWorker allows Scheduled claim by id,
operationId and retryAttempt without expected prior due-time or schedule generation
(BUG-SCHEDULER-06). Format row reread does not bind full configuration authority.
L4 BASELINE_FAIL: DownloadRepository ordinary deleteScheduled/Errored/Queued/Paused
calls deleteKnownUserRemoval with expectedStatus=null and does not revalidate
status/generation in the delete transaction (BUG-DOWNLOAD-DELETE-SNAPSHOT-01).
L5 BASELINE_FAIL: format/cleanup/move workers call setForegroundAsync without
awaiting confirmed platform foreground establishment before effects
(WORKER-FOREGROUND-COMPLETION-01).
L6 DEEP_FAIL: BUG-SCHEDULER-04; five BUG-FORMAT-BG-01..05 roots;
BUG-INCOGNITO-01. Scheduler window/Restore prior source paths not reproduced but
closure remains unverified.

## 4. Triggered contracts and review retrospective

Module B WorkManager handoff/retry: SOURCE_FIXED for BUG-SCHEDULER-01; no
new root established. Module F old persisted carrier: no new schema, generation
representation or state marker; old ACCEPTED rows remain discoverable and are
routed by exact WorkInfo. Module H Restore and scheduler final effect:
OLD_FAILURE_NOT_REPRODUCED, CLOSURE_NOT_VERIFIED.
Scheduler time window: OLD_FAILURE_NOT_REPRODUCED, CLOSURE_NOT_VERIFIED.
Daily END vs independently scheduled requests: FAIL_EXISTING BUG-SCHEDULER-04.
Format producer/carry/consume/persist/cancel: FAIL_EXISTING five BG roots.
Incognito UI/DAO/History: FAIL_EXISTING BUG-INCOGNITO-01.
Destructive/claim/foreground: FAIL_EXISTING roots recorded above.
No additional semantic sibling root independently established.

The important review distinction is the boundary between inclusive minute
window correctness and destructive tag-wide cancellation. They have different
producers and final effects and cannot be collapsed into one root. The format
batch also demonstrates multiple independent failures along one ordinary UI
workflow: selection scope, durable enqueue, outcome truth, stale write and
cancellation. Each is retained under its existing root, counted exactly once.

## 5. Checklist evolution and checkpoint summary

checklist_change_required: NO
Governing v7 plus lens policy already require producer/carrier/consumer/final
effect, async acceptance, durable authority, exact scheduler boundary,
destructive targeting, platform completion and identity. No new governance needed.
primary_deep_lens: L6_CROSS_FEATURE_SEMANTIC_PROPAGATION
primary_deep_selection_reason: R1/R2 scheduler window and active multi-format/incognito
remaining_not_yet_deep: L1,L2,L3,L4,L5
next_not_yet_deep_lens: L3
next_lens_selection_reason: R2 scheduler due-time claim, stale format state and
cross-generation ownership are current OPEN authority obligations.
new_root_count_delta: 0
repository_open_closed_count_change: NONE
active_download_canonical_count_change: NONE
repository_wide_verdict: NOT_CLEAN
manual_review_run_status: FINAL
independent_execution: NOT_EXECUTED
