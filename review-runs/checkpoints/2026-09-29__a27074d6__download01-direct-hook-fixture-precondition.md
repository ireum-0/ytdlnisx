# BUG-DOWNLOAD-01 OUTPUT_UNPROVEN trace — direct-mode hook fixture precondition

checkpoint_kind: COMPLETED_IMPLEMENTATION_RESULT_RECONCILIATION
review_parent_sha: 6731a3657ae5004814740d7f563d1c0876757679
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: a27074d6ea8b56be01a9cfb3159a73c018d1b5d0
reported_local_candidate_parent: bd755355ce832db6cb14bcf246fe8c3d01e63ef5
reported_local_candidate_tree: 71ccd919c0400a65cd518d29183354a34b8b1aa6
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Classification

B. TEST_OR_HARNESS_PRECONDITION_FAILURE

The exact focused failure is caused by the test's legacy synthetic-success hook
placing the fake output under rawTempDirectory while the direct no-cache
production output plan requires current-attempt artifacts under the exact
production ytdlpDirectory/direct staging root.

Production correctly fails closed at the direct ownership-manifest boundary.

## Exact reported trace

The implementation agent reports on exact local candidate a27074d6:
- no source/test/tooling/config changes in the trace;
- no rerun;
- exact HEAD / parent / tree matched the active handoff;
- parent..HEAD changes only
  DownloadWorkerCleanupProductionWiringTest.kt;
- git diff --check passes.

First failure after the synthetic output:
- IllegalStateException:
  "Could not persist current-attempt direct output ownership before completion";
- first failing production statement:
  check(DirectOutputStagingCleanup.recordExactArtifacts(...));
- local stack location:
  DownloadWorker.kt:5820, called from :2627.

Phase/result:
- ytdlpSuccessForTesting returned normally;
- fake output counter reached 1;
- YtdlpPhaseOutcome.Failed;
- producer transitions PREPARED -> RUNNING -> OUTPUT_UNPROVEN;
- no COMPLETE / NO_OUTPUT_COMPLETE state;
- final outcome log reports FINAL_FAILURE issues=[UNKNOWN];
- runtime issue stage remains PREFLIGHT because the synthetic hook bypasses
  normal yt-dlp progress callbacks.

Output-plan semantics:
- cache_downloads=false;
- writable unique external-cache final destination;
- direct no-cache path;
- direct staging parent derived from that destination;
- ytdlpDirectory/directStagingDirectory under
  <final-destination>/.ytdlnisx-output/<operation-token>;
- exact ownership marker required for that direct staging generation.

The legacy hook creates:
- File(rawTempDirectory, "replacement.m4a")

That file is outside the authoritative direct staging root.

DownloadOutputProvenance initially accepts the file through the clean-temp-root
current-attempt rule, but the later direct-mode ownership manifest filters to
the exact staging root, finds no exact artifacts, and rejects completion.

The external-cache fixture corrected destination writability but did not change
the legacy hook's rawTempDirectory behavior. Therefore it moved the test past
the prior destination failure but exposed the next fixture precondition.

## Independent GitHub corroboration

The current remote implementation at 7d6a7b7c contains:
- ytdlpSuccessForTesting(downloadId, rawTempDirectory);
- ytdlpSuccessWithOutputDirectoryForTesting(downloadId, rawTempDirectory,
  outputPlan.ytdlpDirectory);
- the production path prefers ytdlpSuccessWithOutputDirectoryForTesting when
  installed;
- after synthetic output is parsed, directNoCache mode requires
  DirectOutputStagingCleanup.recordExactArtifacts(...) to succeed before the
  phase can return Completed;
- otherwise the check throws and the phase returns Failed.

This independently corroborates the reported correction boundary.

## Narrow correction boundary

Authorize exactly one additional test-only child on top of
a27074d6ea8b56be01a9cfb3159a73c018d1b5d0.

Allowed file only:
app/src/androidTest/java/com/ireum/ytdl/database/DownloadWorkerCleanupProductionWiringTest.kt

Allowed method only:
realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure

Required correction:
- replace the legacy ytdlpSuccessForTesting seam for this test with
  ytdlpSuccessWithOutputDirectoryForTesting;
- create replacement.m4a inside the supplied production ytdlpDirectory;
- return the corresponding exact "[download] Destination: ..." output;
- preserve the existing unique writable external-cache final destination;
- preserve committed-History finalization fault injection;
- preserve all durable assertions and cleanup;
- do not bypass DirectOutputStagingCleanup, output provenance, History
  replacement, or finalization.

Forbidden:
- no production source changes;
- no tooling/config/schema/dependency changes;
- no unrelated tests;
- no weakening/removing fault injection or durable assertions;
- no amend/rebase/squash/reset/rewrite/force push;
- no protected stash/local.properties mutation.

## Verification

After the exact test-only child:
1. verify parent == a27074d6ea8b56be01a9cfb3159a73c018d1b5d0;
2. verify only the named file/method changed;
3. git diff --check;
4. fresh health preflight on the same authorized AVD;
5. compileDebugAndroidTestKotlin;
6. full DownloadWorkerCleanupProductionWiringTest;
7. require nonzero execution and zero failures/errors;
8. for the target method report:
   - output count;
   - finalization hook count;
   - History title/downloadId;
   - Download-row presence/absence;
   - exact execution/process owners;
   - generic recovery carrier;
   - producer recovery identity/phase if present;
   - WorkInfo state;
9. detached exact-SHA diff gate.

If focused verification fails, stop at first valid semantic failure and do not
make another edit in the same wave.

If focused verification passes, restart the full exact-final-SHA union from
partition 1. Stop at the first valid semantic/infrastructure failure.

Publication is not authorized until the restarted full union and Complete-Wave
Check pass and fresh-ref/CAS publication gates are satisfied.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2 pending verified focused pass, full union,
publication, and independent post-publication review.

No new production finding is created.
Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
