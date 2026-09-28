# BUG-DOWNLOAD-01 SDK-scoped rerun — pre-finalization durable failure

checkpoint_kind: COMPLETED_IMPLEMENTATION_STOP_RULE_RECONCILIATION
review_parent_sha: ea6a38e018ee1575da23d20babb50f4f3652164e
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: bd755355ce832db6cb14bcf246fe8c3d01e63ef5
reported_local_candidate_tree: 0ea803361e0583b1f1643cb0cd6dc0d45d5c33c4
prior_handoff_tree_field_for_same_sha: 1cd1a950ec3ecfd88247ed0c52621838b4ddbe9b
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Remote and evidence status

Fresh GitHub state at reconciliation start:
- checkpoint/pre-baseline-review remains
  7d6a7b7c445d9e45297032fa0521a1fc1d732eb9;
- review/remediation was
  ea6a38e018ee1575da23d20babb50f4f3652164e;
- the local candidate is still unpublished and GitHub cannot resolve
  bd755355ce832db6cb14bcf246fe8c3d01e63ef5 as a commit.

The implementation-agent report is therefore a stop-rule/runtime-evidence report,
not a pushed completion. The local production source cannot be independently
reviewed from GitHub yet.

The newly reported tree for bd755355 is
0ea803361e0583b1f1643cb0cd6dc0d45d5c33c4. The prior handoff carried
1cd1a950ec3ecfd88247ed0c52621838b4ddbe9b for the same SHA. Because one commit
SHA cannot bind two trees, the earlier handoff field is treated as stale
metadata, not as source evidence. The next local trace must verify
git rev-parse HEAD and HEAD^{tree} before relying on the candidate.

## SDK/device recovery

The implementation agent reports:
- ANDROID_HOME and ANDROID_SDK_ROOT were scoped only to the verifier process;
- SDK path was C:\Users\dh2\AppData\Local\Android\Sdk;
- source local.properties was not used;
- the same authorized Medium_Phone_API_36.1 / emulator-5554 was cold-started
  with -no-snapshot-load and no wipe;
- ADB boot completion and PackageManager readiness passed;
- no infrastructure marker was observed.

This removes the prior zero-test SDK-environment blocker for this focused gate.

## Focused execution result

Exact reported candidate:
bd755355ce832db6cb14bcf246fe8c3d01e63ef5

Focused class:
DownloadWorkerCleanupProductionWiringTest

Reported execution:
- 31 tests executed;
- 1 failure;
- 0 errors;
- 0 skipped;
- failure:
  realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure;
- elapsed at timeout: 62,750 ms.

Reported predicates at timeout:
- output paths: 1 / 1, matched;
- committed-History finalization hook: expected 1, actual 0;
- replacement History: not converged; row id=1 still has title "old title",
  downloadId=0, with no read error;
- Download row: still present, id=1790598325626, status=Error,
  executionId=6e5b11d8-bdf5-4c18-9d71-c1c3691881eb, with no read error;
- DownloadProducerRecovery: matched the same execution,
  generationId=2d212c57-a081-495a-b90e-a6bec1401638, sequence=1,
  phase=COMPLETE, with no read error;
- exact execution owner: null, matched;
- process owner: null, matched;
- matching generic DownloadExecutionRecovery carrier: absent;
- WorkInfo: RUNNING and unfinished.

No union restart or publication occurred.

## Independent classification

This run does not support the prior Outcome-B test-only lifecycle hypothesis.

The decisive fact is not WorkInfo.RUNNING. The intended injected finalization
failure was never reached: the finalization hook remained at zero. In addition,
durable History replacement and Download-row deletion did not converge.

Therefore this is not a state where all durable promises are satisfied and only
worker lifecycle representation remains. The method stopped earlier than its
intended post-commit finalization-failure boundary.

The authoritative remote source at 7d6a7b7c calls
beforeCommittedHistoryFinalizationForTesting only after History persistence and
after committed-History finalization preparation. The same remote source also
contains a pre-commit History error path that preserves the Download row as
Error. The reported state is compatible with such a pre-finalization History
failure shape.

However, the exact local candidate contains unpublished production commits.
Because bd755355 is not available on GitHub, the reviewer cannot independently
prove which exact local statement or exception produced the Error row. The
runtime evidence therefore establishes a valid pre-finalization semantic stop,
but not yet the exact production correction.

No new finding ID is created at this checkpoint. BUG-DOWNLOAD-01 remains OPEN
P2. BUG-HISTORY-04 is not reclassified from this evidence alone.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.
Overall verdict remains NOT_CLEAN.

## Exact next action

Preserve bd755355 and all prior local commits/evidence exactly.

Perform one read-only local source/evidence trace. Do not edit production,
tests, tooling, configuration, or history. Do not rerun the class merely to seek
green.

First verify locally:
- HEAD == bd755355ce832db6cb14bcf246fe8c3d01e63ef5;
- HEAD^{tree} == 0ea803361e0583b1f1643cb0cd6dc0d45d5c33c4;
- if either differs, stop and report the exact local relation before any action.

Then use the already-preserved connected-test log and exact candidate source to
identify the first failure before the finalization hook. Record:
1. exact exception/error and stack/log origin;
2. Download lastIssueStage / lastIssueCode and any authoritative History
   replacement issue/refusal outcome;
3. whether replaceHistoryPreservingAssignmentsAuthorizedBlocking was entered
   and, if so, its exact outcome;
4. whether historyReplacementCommitted ever became true;
5. whether prepareCommittedHistoryFinalization was entered;
6. exact authoritative Download-row read outcome immediately before the
   History effect that failed;
7. why producer recovery reached COMPLETE while History remained old and the
   Download row became Error;
8. whether owner release and generic-carrier absence are correct for that exact
   failure, or prematurely discard exact E1 recovery responsibility;
9. whether a compatible successor can be admitted from the observed durable
   state;
10. whether cold restart/reconcile has a durable carrier that can converge the
    old History/Error row state without stale effects.

If the existing log is insufficient, report the missing observation and the
narrowest diagnostic seam required. Do not modify source under this trace.

Do not restart the full exact-final-SHA union and do not publish.

INDEPENDENT EXECUTION: NOT EXECUTED
