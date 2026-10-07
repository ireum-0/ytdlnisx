# Scheduler END / DownloadWorker execution-owner race — confirmed same-root residual

Date: 2026-10-07

record_kind: STOP_RULE_PRODUCTION_RESIDUAL_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: c37d836ec54d323e9f2ea6250d75c1148e32ba12
protocol_blob: 7c42f1e3b1428216010bbcf46f175d9b2b4c2af2
remote_implementation_changed: NO
canonical_count_change: NONE
canonical_status_change: NONE
canonical_clean_status: NOT_CLEAN
new_root_classification: NO_SAME_ROOT_RESIDUAL

## Latest implementation-agent stop evidence

Reported governed result:
- test-only queue diagnostics were added;
- build PASS;
- AndroidTest compilation PASS;
- focused run: 5 executed, 4 PASS, 1 FAIL, 0 skipped;
- failure occurred BEFORE the intended post-END queueDownloads diagnostic;
- END completed;
- scheduler handoff carrier disappeared;
- unchanged execution-owner assertion failed because the exact Download/E1 execution owner remained registered;
- queue diagnostic was NOT_REACHED and its cause remains NOT_VERIFIED;
- remote HEAD remained eda6a7589af3a19a97eb38e869b47dabaf74388b;
- current local dirty tree: 8cfaa1ffe864a219dca03fdd18900fdeadfdcb34;
- 15 unstaged paths; empty index;
- 20,683 prior protected records preserved;
- 555 new records sealed and verified;
- no new production edit, commit, publication, or further test run.

Latest local durable report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-post-end-queue-20261007-ntH0oN/SCHEDULER_POST_END_QUEUE_DIAGNOSTIC_STOP_REPORT.md

The local report/diff remain non-remote evidence. The reviewer independently inspected exact remote
production source at eda6a758.

## Independent exact-source proof

Relevant exact remote production files:
- app/src/main/java/com/ireum/ytdl/work/CancelScheduledDownloadWorker.kt
- app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt
- app/src/main/java/com/ireum/ytdl/work/DownloadSchedulerAdmission.kt
- app/src/main/java/com/ireum/ytdl/work/DownloadWorkerOwnershipRecovery.kt

Exact source semantics:

1. A claimed Download execution publishes its process-local exact token via
   DownloadWorkerExecutionOwners.claim(downloadId, executionId).
   The owner registry is keyed by numeric Download ID and exact execution token.
   release(downloadId, executionId) uses exact-value removal, so releasing stale E1 cannot remove E2.

2. DownloadWorker.Attempt.cleanupAttempt:
   - observes the latest Room authority;
   - releases the exact execution owner when durable-stop recovery is pending;
   - otherwise releases it when the latest row is absent or no longer Active/PostProcessing;
   - if the latest row is still Active/PostProcessing for the same E1, it intentionally retains the
     execution owner;
   - after that decision it invokes afterAttemptCleanupForTesting(downloadId, executionId).

3. CancelScheduledDownloadWorker:
   - awaits WorkManager cancelAllWorkByTag("download").result;
   - independently scans Active/PostProcessing rows;
   - under the exact side-effect lease, requeues the current exact execution to Queued;
   - its scheduler handoff retirement is a separate lifecycle.

4. DownloadWorker.doWork cancellation handling invokes cleanupStoppedWorker() from cancellation catch
   and again from finally when isStopped.

5. cleanupStoppedWorker() snapshots worker-owned IDs even if the Room row has already become non-running.
   For each snapshot activeId it calls withCleanupOwnership(requireRunning=true).
   When END has already changed the same E1 row to Queued, withCleanupOwnership returns false because
   the row is no longer Active/PostProcessing.

6. In that false/non-running path, the ID is not added to releasedIds.
   The later exact owner-release loop releases DownloadWorkerExecutionOwners only when the ID is in
   releasedIds, recoveryEligibleIds, or recoveryPublicationFailedIds.

Therefore this race is possible:

A. E1 DownloadWorker cleanupAttempt reads E1 as Active/PostProcessing and keeps the E1 execution owner.
B. cleanupAttempt finishes its ownership decision.
C. scheduler END concurrently requeues the exact E1 Room row to Queued.
D. stopped-worker outer cleanup snapshots E1 bookkeeping, but its requireRunning ownership check now
   rejects the already-Queued row.
E. no released/recovery classification is recorded for that exact dead E1.
F. DownloadWorkerExecutionOwners.release(E1) is skipped, leaving a process-local E1 owner registered
   after the durable row is Queued and the scheduler END carrier is retired.

This explains the observed nondeterminism:
- if END requeues before cleanupAttempt's authority read, cleanupAttempt releases E1;
- if END requeues after cleanupAttempt retains E1 but before cleanupStoppedWorker's running-only check,
  the stale process-local owner can remain.

The latest 4/1 run directly matches the second ordering:
END completed + carrier gone + exact E1 execution owner still registered.

## Classification

PRODUCTION_SAME_ROOT_RESIDUAL=CONFIRMED

This is not a new canonical root. It is a scheduler cancellation / Download execution authority
convergence residual under the existing scheduler restore/authority remediation.

The earlier owner-release green result does not close this residual because the exact source permits
both orderings.

The intended queueDownloads diagnostic remains NOT_REACHED and is no longer the immediate next
diagnostic. Queue admission must not be used to work around a stale E1 owner.

## Minimal safe correction boundary

Authorize a narrow production correction in DownloadWorker stopped-worker cleanup so an exact
snapshot E1 owner is retired when the current row proves that E1 is no longer a running owner,
including the already-non-running same-execution case produced by scheduler END.

Required invariants:
- release only the exact snapshot execution token;
- never clear a newer E2 execution owner;
- never mutate/requeue a newer E2 row;
- a same-E1 Active/PostProcessing row must not be treated as dead without the existing cleanup/recovery proof;
- unresolved native process ownership remains separately protected by DownloadWorkerProcessOwners /
  native registry and recovery; do not falsely clear native authority;
- release must be idempotent across cancellation catch + finally double cleanup;
- no queue admission, scheduler preference, schema, migration, dependency, or ownership-model redesign;
- preserve durable recovery semantics on actual cleanup failure.

Preferred shape:
- classify the exact snapshot owner as releasable when the fresh row is null, belongs to a different
  execution, or the same exact execution is no longer Active/PostProcessing;
- feed that classification into the exact stale-owner release path;
- rely on exact-value remove so stale E1 release cannot erase E2;
- retain existing process-owner release guard when native registry still exists.

Do not simply clear the whole owner registry or use test teardown to hide the leak.

## Required deterministic regressions

At minimum:
1. deterministic E1 race: cleanupAttempt retains E1 while row is Active, then END requeues E1 to Queued
   before outer stopped-worker cleanup; exact E1 execution owner must be released;
2. E2 protection: E2 may claim after E1; stale E1 cleanup/release must not remove E2 owner or mutate E2 row;
3. same-E1 still Active/PostProcessing with unresolved cleanup must remain protected or recovery-owned,
   not blindly released;
4. native process registry present: execution-owner retirement must not falsely remove unresolved process owner;
5. cancellation catch + finally repeated cleanup is idempotent;
6. real scheduler END focused production-wiring scenario passes exact END/carrier/E1-owner assertions;
7. post-END fresh authoritative queue path reaches its diagnostic/queue step only after exact E1 owner
   retirement, without weakening CAS/recovery admission.

## STOP/report semantics

Protocol blob 7c42f1e3b1428216010bbcf46f175d9b2b4c2af2 now governs.

Every STOP means:
- stop further mutation/rerun/commit/push/cleanup beyond evidence preservation;
- preserve first failure;
- record exact failing test/assertion/line and expected/actual semantic state;
- mark later planned stages NOT_REACHED;
- verify protected candidate/index/refs/evidence;
- seal new evidence;
- ALWAYS return the required final report.

Silent STOP is forbidden.

## Next handoff

Persist one self-contained production-correction prompt for this exact same-root residual.
It must include the STOP/report semantics explicitly, preserve the 15-path local candidate, and chain
successful focused verification through prepublication, normal fast-forward publication, and exact-final-SHA
closure. If a materially different root or ownership architecture is required, STOP and report.

INDEPENDENT_REVIEW_REQUIRED=YES
