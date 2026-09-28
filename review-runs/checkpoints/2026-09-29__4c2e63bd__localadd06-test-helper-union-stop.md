# BUG-LOCALADD-06 union partition-2 stop — helper selects prior pending session again

checkpoint_kind: COMPLETED_IMPLEMENTATION_RESULT_RECONCILIATION
review_parent_sha: 7b1441410c6b8f8100955647edcb973b92bf9d4b
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 4c2e63bd26ca5cccc5467c240983e868e8f8f7c7
reported_local_candidate_parent: ff3a111a784fc50c43aed0aafc0f62776e7e0bd4
reported_local_candidate_tree: 615f65da193ec9c249939b06e9ce9b189751a282
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Completed local tooling correction evidence

The implementation agent reports one tooling-only child:
4c2e63bd26ca5cccc5467c240983e868e8f8f7c7

Parent:
ff3a111a784fc50c43aed0aafc0f62776e7e0bd4

Reported changed paths only:
- tools/remediation/Invoke-Verification.ps1
- tools/remediation/Test-ExecutionLifetimeProvenance.ps1
- tools/remediation/README.md

Reported verification:
- tooling acceptance: 20/20 PASS;
- Android test compile gate PASS;
- detached diff gate PASS;
- authorized AVD health PASS;
- DownloadWorkerCleanupProductionWiringTest: 31/31 PASS, zero failures/errors.

The exact candidate remains unpublished and is not resolvable from the current
remote implementation ref. Exact local source/diff mechanics remain
implementation-agent evidence until publication.

BUG-TOOLING-01 therefore has successful local correction/acceptance evidence but
remains OPEN P2 pending publication and independent post-publication review.

BUG-DOWNLOAD-01 has successful focused local execution evidence on the candidate
but remains OPEN P2 because the restarted full exact-final-SHA union is
incomplete and publication/review have not occurred.

## Restarted union

Partition 1:
DownloadWorkerCleanupProductionWiringTest
- 31 executed
- 31 passed
- zero failures/errors

Partition 2:
LocalAddWorkerProductionWiringTest
- 6 executed
- 1 assertion failure
- no infrastructure marker
- later partitions not run

Failing method:
openedOlderPendingSessionRemainsDiscoverableAfterNewWorkerPublishes

Reported expected IDs:
- 340242fe-c453-4476-ab03-740366166052

Reported observed IDs:
- 340242fe-c453-4476-ab03-740366166052
- 48ef93e7-3850-41f8-9850-2f6ebe946fb3

## Classification

B. TEST_OR_HARNESS_PRECONDITION_ASSERTION_FAILURE

No new production finding ID is created.

The current canonical BUG-LOCALADD-06 invariant requires every successfully
published unresolved Local Add session to remain independently discoverable by
exact session UUID until exact completion/abandon.

Its acceptance matrix explicitly requires:
"A publishes unresolved payload, then B publishes: both IDs enumerate/open
exactly once."

The reported observed set contains two distinct IDs, which is the direction
required by that production invariant rather than evidence of last-writer-wins
loss.

The implementation agent reports that the unpublished test helper
awaitNewPendingSession() excludes only IDs captured before the test. When used
for the second publish it can therefore select the first newly created session
again. setOf(firstId, secondId) then collapses the expected set to one ID even
though production enumeration returns both actual sessions.

The exact unpublished helper implementation cannot be independently read from
GitHub, so that helper mechanics remain agent evidence. However, the failure
shape plus the canonical acceptance contract support a narrow test-only
correction boundary and do not establish a production semantic residual.

Remote implementation 7d6 does not yet contain this new method/helper, so no
claim is made that the local helper mechanics were independently source-verified.

## Narrow correction boundary

Preserve exact local candidate
4c2e63bd26ca5cccc5467c240983e868e8f8f7c7
and all ancestors.

Authorize exactly one additional test-only child.

Allowed file only:
app/src/androidTest/java/com/ireum/ytdl/work/LocalAddWorkerProductionWiringTest.kt

Allowed scope:
- awaitNewPendingSession helper and/or only the failing
  openedOlderPendingSessionRemainsDiscoverableAfterNewWorkerPublishes method,
  as needed to make the second publication select a session ID that is new
  relative to all already-observed session IDs.

Required semantics:
- first publication yields exact firstId;
- second publication must explicitly exclude firstId in addition to any
  pre-test baseline IDs, or otherwise prove secondId != firstId;
- expected set must contain the two exact publication identities;
- production enumeration must still be asserted equal to both IDs;
- preserve the production requirement that the older session remains
  discoverable after the newer worker publishes;
- do not weaken to "contains at least one" or remove exact identity checks;
- preserve cleanup and session lifecycle assertions.

Forbidden:
- no production source changes;
- no tooling changes;
- no unrelated Android/JVM test changes;
- no assertion weakening that would permit singleton last-writer-wins behavior;
- no amend/rebase/squash/reset/history rewrite/force push.

## Verification after test-only child

1. verify parent is exactly
   4c2e63bd26ca5cccc5467c240983e868e8f8f7c7;
2. report exact new SHA/tree;
3. prove parent..HEAD changes only
   LocalAddWorkerProductionWiringTest.kt and only the helper/failing-test
   correction boundary;
4. git diff --check;
5. fresh authorized AVD health;
6. compileDebugAndroidTestKotlin;
7. run complete LocalAddWorkerProductionWiringTest;
8. require nonzero execution and zero failures/errors;
9. record exact two session IDs and final enumerated ID set for the failing
   regression method;
10. detached exact-SHA diff gate.

Focused failure:
- STOP at first valid semantic/infrastructure failure;
- preserve evidence;
- no second edit.

Focused PASS:
- restart the complete exact-final-SHA union from partition 1 because the
  committed Android-test tree changed;
- stop at first valid semantic/infrastructure failure.

Only after full restarted union PASS may Complete-Wave Check and publication
gates run.

## Canonical state

BUG-TOOLING-01 = OPEN P2 / LOCAL_CORRECTION_ACCEPTANCE_PASS_UNPUBLISHED.
BUG-DOWNLOAD-01 = OPEN P2 / FOCUSED_PASS_UNION_INCOMPLETE.
BUG-LOCALADD-06 = OPEN P2 / LOCAL_PRODUCTION_INVARIANT_OBSERVED_BUT_TEST_HELPER_BLOCKS_UNION.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.

No root is closed.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
