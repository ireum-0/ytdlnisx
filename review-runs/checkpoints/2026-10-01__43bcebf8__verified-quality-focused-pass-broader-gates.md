# 43bcebf8 DownloadOutput moved-quality assertion — focused PASS, broader verification pending

checkpoint_kind: REVIEWER_FOCUSED_PASS_CLASSIFICATION_AND_VERIFICATION_CONTINUATION_AUTHORIZATION
review_parent_sha: b86ff283a59e93cee483066b322572d206115b60
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 43bcebf8d4f796ed6e6759ebe03b0e5925442444
reported_local_candidate_parent: 250b495941237f207537b244fca8c92f62de0e62
reported_local_candidate_tree: c187d28025799cb38869d251bed0795801d5936a
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Evidence authority

The 43bcebf8 child and focused execution remain implementation-agent evidence because the child is
protected local/unpublished and the exact local test-only diff is not GitHub-authoritative.

The implementation agent reported:
- exactly one forward test-only child on 250b4959;
- only DownloadOutputProductionWiringTest.kt changed;
- protected candidate worktree clean;
- production source unchanged;
- focused realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement: 1 PASS, 0 FAIL;
- zero skips and assumptions;
- verifier finalization PASS;
- the exact moved authoritative output was probed;
- the ambient file was excluded;
- the 360p-vs-720p rejection preserved History and old media;
- cleanup cleared execution owners.

## Reviewer classification

The previously classified TEST_HARNESS_MOVED_QUALITY_PROBE_PATH_ASSERTION_STALE subcase is
satisfied at the focused runtime boundary reported for 43bcebf8.

This does not close BUG-DOWNLOAD-01 and does not close an execution gate because:
- the candidate remains unpublished/local-only;
- the exact local test diff is not independently source-reviewed from GitHub;
- the complete DownloadOutputProductionWiringTest class has not executed on 43bcebf8;
- the exact-candidate detached diff proof has not executed;
- the complete exact-final-SHA 19-gate union has not executed on 43bcebf8;
- Complete-Wave Check has not executed;
- the separately required RESUME01 API24/25 production-path proof has not executed;
- independent reviewer execution has not occurred.

The active harness disposition therefore advances to:

FOCUSED_VERIFIED_QUALITY_PATH_PASS_BROADER_GATES_PENDING

Canonical P0/P1/P2 counts remain unchanged.
BUG-DOWNLOAD-01 remains OPEN P2.
No root is FIXED-CLOSED.
Repository-wide CLEAN remains unsupported.

## Verification-only continuation

Authorize verification only on exact local candidate
43bcebf8d4f796ed6e6759ebe03b0e5925442444.

No source/test/config edits and no new commit are authorized by this checkpoint.

Required sequence:

1. Fresh-check the protected worktree is clean and exactly at 43bcebf8 with parent 250b4959 and tree
   c187d28025799cb38869d251bed0795801d5936a.
2. Fresh device preflight on the already-authorized AVD/device. Do not wipe, recreate, reconfigure,
   or substitute the device.
3. Run the complete com.ireum.ytdl.database.DownloadOutputProductionWiringTest exactly once.
4. On any valid semantic failure, zero-test infrastructure failure, or incomplete verifier result:
   preserve evidence and STOP for reviewer classification. Do not retry unchanged.
5. Only if the complete class PASSes with nonzero execution and zero unexplained failures/errors,
   run the detached exact-SHA diff proof through tools/remediation/Invoke-Verification.ps1.
6. Only if that PASSes, restart the complete exact-final-SHA 19-gate union from partition 1 using
   the existing persisted canonical campaign definition. Do not reconstruct, reorder, omit, or
   invent gates.
7. Stop at the first valid union failure and preserve first-failure evidence.
8. Only if the complete 19-gate union PASSes, run Complete-Wave.ps1 in Check mode and bind its
   evidence to exact candidate 43bcebf8.
9. After Complete-Wave PASS, the RESUME01 API24/25 real notification/PendingIntent/ResumeActivity
   production-path proof remains mandatory. If an already-approved API24/25 execution target and
   exact persisted verifier definition are available in the protected campaign, execute that exact
   gate. Otherwise STOP at API24/25 NOT_EXECUTED without provisioning, recreating, substituting, or
   reconfiguring a device.
10. Do not publish in this verification-only task. Publication remains reviewer-gated after all
    required exact-candidate gates, including API24/25, pass and refs are freshly reconciled.

## Stop boundary

STOP and return exact evidence on:
- any full-class, diff, union, Complete-Wave, or API24/25 failure/incomplete result;
- any zero-test connected gate;
- any new/unclassified semantic root;
- any need for a source/test/config edit;
- any device provisioning/substitution/reconfiguration requirement;
- any protected-state ambiguity, writer race, divergence, rewrite, or governance mismatch.

INDEPENDENT EXECUTION: NOT EXECUTED
