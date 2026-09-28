# BUG-DOWNLOAD-01 pre-finalization trace — test fixture precondition failure

checkpoint_kind: COMPLETED_IMPLEMENTATION_STOP_RULE_RECONCILIATION
review_parent_sha: e981003cce966c5b4e43347cfdbf8f28d4d07f18
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: bd755355ce832db6cb14bcf246fe8c3d01e63ef5
reported_local_candidate_tree: 0ea803361e0583b1f1643cb0cd6dc0d45d5c33c4
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Classification

B. TEST_OR_HARNESS_PRECONDITION_FAILURE

The focused run did not reach the promised committed-History finalization
boundary. The failure was caused by the test destination fixture becoming
unwritable to the production move path before History persistence.

This is not a conclusion based on WorkInfo.RUNNING.

## Candidate identity

The implementation agent reports and locally verified:
- HEAD = bd755355ce832db6cb14bcf246fe8c3d01e63ef5
- HEAD^{tree} = 0ea803361e0583b1f1643cb0cd6dc0d45d5c33c4

This resolves the prior stale handoff tree metadata for this SHA.

The candidate remains local-only and unpublished. Exact local source mechanics
remain implementation-agent evidence where not independently available on
GitHub.

## Causal trace

The implementation agent reports:
- the failing test sets cache_downloads=false;
- the fixture uses context.cacheDir.absolutePath as downloadPath;
- the fake yt-dlp output is a three-byte replacement.m4a;
- preserved per-test Logcat records:
  No writable destination for /data/data/com.ireum.ytdl/cache ... perms=<none>;
- the first move exception originates at FileUtil.kt:643;
- after retry also fails, DownloadWorker.kt:3301 throws:
  Move failed without an authoritative output path;
- the worker classifies the failure as DESTINATION_NOT_WRITABLE at MOVE;
- execution proceeds through yt-dlp output production before this move failure.

The implementation agent further traced:
- runSuccessfulDownload performs output processing before History persistence;
- the move failure prevents runHistoryPersistence from executing;
- replaceHistoryPreservingAssignmentsAuthorizedBlocking was not entered;
- there was no History replacement outcome/refusal object;
- historyReplacementCommitted remained false;
- prepareCommittedHistoryFinalization was not entered;
- beforeCommittedHistoryFinalizationForTesting was not entered;
- therefore there was no authoritative Download-row read immediately before a
  History effect in this failed run.

Producer recovery phase COMPLETE means the producer output completed; it does
not imply a History commit. The reported specialized DownloadProducerRecovery
record remains the durable E1 carrier for the completed producer output.

The reported owner release / no generic post-commit carrier is therefore
consistent with a pre-commit move failure on the supplied evidence; there was
no committed-History finalization debt.

## Independent GitHub corroboration

The current remote test at 7d6a7b7c:
- sets cache_downloads=false;
- sets downloadPath=context.cacheDir.absolutePath;
- installs beforeCommittedHistoryFinalizationForTesting only after that setup.

The current remote DownloadWorker:
- fails output processing with
  "Move failed without an authoritative output path"
  when no authoritative final path exists;
- performs this move/output work before History persistence/finalization;
- calls beforeCommittedHistoryFinalizationForTesting only after committed
  History finalization preparation.

This source ordering corroborates the classification that an unwritable
destination can prevent the test from reaching its intended finalization
boundary.

The exact local candidate is unpublished, so this checkpoint does not claim an
independent line-by-line review of its entire local production diff.

## Narrow correction boundary

One test-only correction is authorized for:
app/src/androidTest/java/com/ireum/ytdl/database/DownloadWorkerCleanupProductionWiringTest.kt

Only the failing method's destination/cache fixture may be changed so that:
- the production move path has a genuinely writable destination;
- the final output path is authoritative;
- the existing committed-History finalization fault injection remains intact;
- the test still requires the History replacement to commit before the injected
  finalization failure;
- no production source, tooling, schema, dependency, or unrelated test behavior
  changes.

Do not weaken the fault injection or remove the durable assertions.

## Required focused verification

After the single test-only child:
1. verify candidate parent is exactly bd755355ce832db6cb14bcf246fe8c3d01e63ef5;
2. verify only DownloadWorkerCleanupProductionWiringTest.kt changed;
3. run the full DownloadWorkerCleanupProductionWiringTest;
4. require nonzero execution and zero failures/errors;
5. preserve exact test count;
6. require the failing method to prove the finalization hook is reached exactly
   once and History replacement is authoritative;
7. require Android-test Kotlin compile;
8. require detached exact-SHA diff gate;
9. use only the same authorized AVD with fresh boot/PackageManager readiness.

If the focused class passes, restart the full exact-final-SHA union from the
first partition on the new exact candidate and stop at the first valid semantic
or infrastructure failure.

No publication is authorized until the full union and Complete-Wave Check pass
under the governing publication protocol.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2 pending focused verification, full-union
verification, publication, and independent post-publication review.

No new finding ID is created.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
