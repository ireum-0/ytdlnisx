# Scheduler post-END queue failure — diagnostic boundary

Date: 2026-10-07

record_kind: STOP_RULE_DIAGNOSTIC_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: 528d3bc729b51845f09741ee7e11b2fa130f8be9
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
remote_implementation_changed: NO
canonical_count_change: NONE
canonical_status_change: NONE
canonical_clean_status: NOT_CLEAN
new_root_classification: NOT_VERIFIED

## Latest governed result

User/implementation report:
- build PASS;
- AndroidTest compilation PASS;
- focused gate: 5 executed, 4 PASS, 1 FAIL, 0 skipped;
- exact END completion passed;
- exact carrier disappearance passed;
- both owner-release assertions passed;
- failure occurred later at line 346 where post-END queueDownloads(...).succeeded was false;
- failure reason/message was not captured;
- no further correction or rerun occurred;
- local candidate HEAD remained eda6a7589af3a19a97eb38e869b47dabaf74388b;
- committed tree remained ca8d9b8af59d03681663de9ac9588b6e6f5e3e4a;
- parent remained db29f63ce169176b4c8ade4cec01f66cc0307ec8;
- dirty tree is now 6b48a7cd94ff1e3e8d3aa51d92871cf0f98e831b;
- 15 unstaged paths; index empty;
- 20,133 prior protected records preserved;
- 550 new evidence records sealed and verified;
- no production edit, commit, publication or active build/test process.

Latest local durable report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-exact-quiescence-20261007-5H3fKO/SCHEDULER_EXACT_QUIESCENCE_DIAGNOSTIC_STOP_REPORT.md

The local report and dirty candidate remain non-remote evidence.

## Owner-release disposition

The previously ambiguous owner-release assertion is no longer the failing boundary in the latest run.

Exact END completion, exact carrier disappearance, and both owner-release assertions passed under the
corrected exact-quiescence observation contract.

Therefore:
OWNER_RELEASE_SUBCASE=PROVEN_PASS_AT_LATEST_FOCUSED_GATE
OWNER_RELEASE_PRODUCTION_RESIDUAL_FROM_PRIOR_FAILURE=NOT_ESTABLISHED
PRIOR_OWNER_RELEASE_FAILURE_CLASSIFICATION=HARNESS_OBSERVABILITY_OR_QUIESCENCE_ERROR_SUPPORTED_BY_LATEST_RUN

This does not close the scheduler roots because a later post-END queue admission step now fails.

## Independent exact-remote queueDownloads semantics

At exact remote SHA eda6a758, DownloadViewModel.queueDownloads can return succeeded=false through
multiple semantically distinct paths, including:
- DownloadExecutionRecovery.hasPendingRecovery for an existing row;
- durable History replacement mismatch/barrier;
- retry metadata refusal;
- final duplicate/archive-unavailable admission;
- History redownload hydration failure;
- scheduler immediate-start permission failure;
- DownloadRepository.startDownloadWorker failure;
- existing-row snapshot/CAS transition failure in dao.updateForQueueIfSnapshot.

Critically, the existing-row snapshot/CAS transition path sets result.succeeded=false without assigning
a diagnostic message. Other paths often carry a message or separate duplicate/archive evidence.

For an existing row, queueDownloads snapshots the caller-supplied DownloadItem before mutating it, then
requires updateForQueueIfSnapshot to match the persisted row's status, executionId, operationId,
retryAttempt and issue-code/stage. Reusing a stale caller object after the END worker has changed the
Room row can therefore legitimately fail the CAS even when owner release itself is correct.

This is a discriminating hypothesis, NOT yet the causal conclusion for the local failure.

## Next diagnostic

No production correction is authorized yet.

Perform one material TEST/HARNESS diagnostic change before any rerun. Capture at the exact line-346
post-END queue attempt:
1. QueueDownloadsResult.succeeded;
2. QueueDownloadsResult.message exactly, including blank/nonblank;
3. duplicateDownloadIDs;
4. exact caller DownloadItem fields immediately before queueDownloads:
   id, status, executionId, operationId, retryAttempt, lastIssueCode, lastIssueStage, downloadStartTime;
5. a fresh Room row for the same id immediately before queueDownloads with the same fields;
6. whether DownloadExecutionRecovery.hasPendingRecovery is true for that id;
7. relevant scheduler preferences and isDuringTheScheduledTime/canSchedule state if that branch is reachable;
8. whether the test passes a reused pre-END object or a fresh post-END Room object;
9. exact DB row immediately after the failed queue call;
10. any WorkManager enqueue exception/result if queue publication reaches startDownloadWorker.

Do not modify production merely to add logging if the focused test can capture these observations through
existing public/internal test-accessible boundaries. A narrowly scoped test-only diagnostic helper is
allowed if needed.

One rerun is authorized only after this material diagnostic change.

Classification:
- caller object stale while fresh post-END Room row is queueable:
  HARNESS_STALE_INPUT_CONTRACT; correct test to queue the fresh authoritative row, preserve CAS semantics,
  then rerun once;
- pending recovery remains true after owner release:
  SAME_ROOT_RECOVERY_CONVERGENCE_CANDIDATE; STOP before production edit with exact recovery evidence;
- fresh input equals Room row but updateForQueueIfSnapshot still fails:
  SAME_ROOT_QUEUE_ADMISSION_CAS_CANDIDATE; STOP before production edit with exact fields/evidence;
- nonblank result.message identifies another explicit refusal:
  classify that exact refusal against the scenario; no guessing;
- WorkManager/scheduler publication fails after successful row transition:
  classify publication failure separately;
- still ambiguous after one diagnostic:
  INCONCLUSIVE_NOT_VERIFIED and STOP.

No new root count is authorized from the current evidence.

## Storage/protection

D: cleanup remains complete:
- reclaimed 4,838,006,784 bytes;
- no further cleanup in this wave.

Preserve exact scheduler dirty tree:
6b48a7cd94ff1e3e8d3aa51d92871cf0f98e831b
15 unstaged paths; empty index.

Preserve FMT, PO Token, updater03, history05 and all sealed evidence/reports.

## Next handoff

Persist one self-contained diagnostic-first prompt carrying the exact observations above.
Production source changes remain unauthorized until the diagnostic establishes a same-root production
boundary. If the result proves stale test input, a test-only correction may continue into the existing
scheduler verification/publication/exact-final-SHA closure chain.

INDEPENDENT_REVIEW_REQUIRED=YES
