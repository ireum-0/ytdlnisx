# BUG-DOWNLOAD-01 focused rerun — producer output remains unproven before History

checkpoint_kind: COMPLETED_IMPLEMENTATION_SEMANTIC_FAILURE_RECONCILIATION
review_parent_sha: c6935052f039b15fa6d2dbf1a8a8f75b5a98ebea
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: a27074d6ea8b56be01a9cfb3159a73c018d1b5d0
reported_local_candidate_parent: bd755355ce832db6cb14bcf246fe8c3d01e63ef5
reported_local_candidate_tree: 71ccd919c0400a65cd518d29183354a34b8b1aa6
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Completion classification

FOCUSED_SEMANTIC_FAILURE_CAUSE_NOT_YET_CLASSIFIED

The exact local child remained unchanged and the authorized AVD/device gate
passed. Android-test Kotlin compilation then passed. The focused class executed
valid tests and stopped at one semantic failure.

The prior ADB infrastructure blocker is therefore cleared for this run.

## Focused result

Reported exact class result:
- DownloadWorkerCleanupProductionWiringTest
- 31 executed
- 1 failure
- 0 errors
- 0 skipped
- no infrastructure marker

Failing method:
realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure

Timeout:
63,642 ms

Reported predicates:
- output paths: expected 1 / actual 1
- committed-History finalization hook: expected 1 / actual 0
- History row remains id=1 / title="old title" / downloadId=0
- Download row remains present:
  - id=1790605679007
  - status=Error
  - executionId=7e38d3ea-a1aa-4398-a112-1e6107a59eb7
- producer recovery:
  - same download/execution identity
  - generationId=15f804dc-4d64-40a5-8130-10d55f56c1b8
  - sequence=1
  - phase=OUTPUT_UNPROVEN
  - verifier match predicate=false
- exact execution owner=null
- process owner=null
- generic recovery target absent
- generic recovery disposition/phase=null
- WorkInfo=RUNNING / observed / unfinished

The verifier explicitly reports:
semanticCauseClassification=not_performed

Therefore this checkpoint does not classify the root as production residual,
fixture residual, cross-root/new-root signal, or tooling defect.

## Independent GitHub corroboration

The exact local candidate a27074d6 is unpublished and cannot be resolved on
GitHub, so its exact local production source is not independently reviewable.

The current remote implementation at 7d6a7b7c establishes a useful semantic
boundary:

- DownloadProducerRecovery.Phase includes OUTPUT_UNPROVEN.
- DownloadWorker transitions the current producer record to OUTPUT_UNPROVEN
  only when executeYtdlpPhase(...) returns an outcome that is not Completed.
- Only a Completed yt-dlp phase is then persisted as producer COMPLETE /
  NO_OUTPUT_COMPLETE and allowed to proceed to runSuccessfulDownload(...).
- runSuccessfulDownload performs output processing before History persistence.

Thus, on the remote production semantics, OUTPUT_UNPROVEN is evidence that the
attempt failed/cancelled before the normal successful-output-processing /
History-persistence path. It is not equivalent to a completed producer waiting
only for History finalization.

This does not prove the exact local candidate's first failure because the local
13-root production candidate remains unpublished.

## Immediate review conclusion

The previous single fixture correction is not proven sufficient.

Do not authorize another source/test correction from the timeout predicates
alone.

The next required action is a read-only trace of the existing exact local
candidate and preserved focused evidence to establish why executeYtdlpPhase did
not reach Completed despite the test hook reporting one output path.

Required questions:
1. exact first exception/cancellation and stack/log origin;
2. exact YtdlpPhaseOutcome type and issue stage/code at the transition to
   OUTPUT_UNPROVEN;
3. whether the test hook returned normally and what exact path/string it
   reported;
4. the exact active YtdlpOutputPlan for this test, including no-cache/direct
   staging mode and expected roots/ownership markers;
5. whether the fake output file lies inside the authoritative producer/output
   provenance root expected by that plan;
6. whether output provenance rejects the file as untrusted/unproven and at
   which statement;
7. whether the new external-cache destination changes producer semantics or
   only publication destination semantics;
8. whether any fixture precondition still prevents the intended committed-
   History finalization boundary;
9. whether the failure is a same-root production residual, a test/harness
   precondition failure, a cross-root/new-root signal, or remains insufficient
   evidence.

## Required classification from the trace

Return exactly one:
A. SAME_ROOT_PRODUCTION_RESIDUAL
B. TEST_OR_HARNESS_PRECONDITION_FAILURE
C. CROSS_ROOT_OR_NEW_ROOT_SIGNAL
D. INSUFFICIENT_EVIDENCE

For A/B/C, report the narrowest correction boundary but make no edits.
For D, identify the missing observation and narrowest diagnostic seam only.

## Forbidden actions for the trace

- no source edits
- no test edits
- no tooling/configuration edits
- no new commit
- no test rerun merely to seek green
- no union restart
- no Complete-Wave Check
- no publication
- no reset/rebase/amend/squash/history rewrite
- preserve protected work, stashes, prior evidence, and local.properties

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2.

No new finding is created by this checkpoint.
No finding is closed.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
