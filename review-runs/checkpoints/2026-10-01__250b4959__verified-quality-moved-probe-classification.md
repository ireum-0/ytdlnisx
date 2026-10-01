# 250b4959 DownloadOutput verified-quality focused result — moved-path probe assertion classified

checkpoint_kind: REVIEWER_FOCUSED_RESULT_CLASSIFICATION_AND_TEST_CORRECTION_AUTHORIZATION
review_parent_sha: 4b7b36eac2b45ade82c172390296502e223cc244
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 250b495941237f207537b244fca8c92f62de0e62
reported_local_candidate_parent: b6b84106a23d6698ec1569d9105910dec66d0a3d
reported_local_candidate_tree: 65b4d1298dcbd30bcef1d99cda470790408b0113
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Evidence authority

The 250b4959 child and focused runtime result remain implementation-agent evidence because the
child is protected local/unpublished and its exact diff is not available from GitHub.

The implementation agent reported:
- exactly one test-only child on b6b84106;
- only DownloadOutputProductionWiringTest.kt changed;
- focused execution: 1 executed, 0 PASS, 1 FAIL, zero skips/errors/assumptions;
- verifier finalization PASS;
- the corrected fixture reached production queue admission;
- a nonblank executionId was published;
- synthetic yt-dlp success was reached;
- video-quality probing was reached;
- exact attempt cleanup was reached;
- the probe assertion failed because the actual probe input was moved requested (1).mp4 rather
  than the hook's original staged requested.mp4 path;
- History and the old/ambient files remained intact at the captured snapshot.

## Independent exact-source classification

Exact GitHub source at remote implementation 7d6a7b7c establishes the probe contract.

The synthetic ytdlpSuccessWithOutputDirectoryForTesting path returns directly from
executeYtdlpPhase() after recording output provenance. It does not execute the normal
executeYtdlpAttempts() quality-routing loop and therefore does not call
resolveCompletedYtdlpQuality()/probeStagedVideoQuality() for this injected success.

The later production output-processing path moves the authoritative no-cache source into the
configured destination before calling validateMovedQualityReplacement().

FileUtil.moveFile() uses uniqueDestinationFile() for direct filesystem publication. When
requested.mp4 already exists in the destination, the authoritative moved source is published as
requested (1).mp4 rather than overwriting the pre-existing ambient file.

validateMovedQualityReplacement() then probes finalPaths through probeVideoQuality(). The test
hook videoQualityProbeForTesting therefore receives the exact moved authoritative output path at
this boundary, not the original staging path.

Accordingly the focused failure is classified as:

TEST_HARNESS_MOVED_QUALITY_PROBE_PATH_ASSERTION_STALE

The observed requested (1).mp4 is consistent with the intended production collision-avoidance and
provenance contract. It is not evidence that the ambient requested.mp4 gained authority.

This is a same-root BUG-DOWNLOAD-01 verification-harness residual. It is not a new production root
and does not change canonical counts.

## Authorized correction

Authorize exactly one forward TEST-ONLY correction child on exact local parent
250b495941237f207537b244fca8c92f62de0e62.

Allowed source file only:
- app/src/androidTest/java/com/ireum/ytdl/database/DownloadOutputProductionWiringTest.kt

Production source changes remain forbidden.

For realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement:

1. Correct only the quality-probe path assertion so it reflects the actual production boundary:
   validateMovedQualityReplacement() probes the moved authoritative finalPaths.
2. Keep a strong provenance assertion:
   - the probe input must not equal ambientHighQuality.canonicalPath;
   - for this filesystem fixture with pre-existing requested.mp4, the authoritative moved media
     must be the collision-safe destination requested (1).mp4 (or an equivalently exact
     production-derived expected path if the test already captures the move result deterministically);
   - do not accept an arbitrary file in the destination and do not rescan/discover by recency.
3. Preserve the original staged-output witness so the test still proves the synthetic producer
   created the current attempt's requested.mp4 in operation-owned staging before publication.
4. Preserve the returned VideoMediaQuality READY 640x360 result so the 720p verified replacement
   must be rejected by validateMovedQualityReplacement().
5. Preserve all end-state assertions:
   - History remains on oldMedia;
   - oldMedia exists;
   - ambientHighQuality exists;
   - the rejected moved low-quality candidate is not allowed to become authoritative History media.
6. Preserve the production-faithful low-quality ledger linkage and scoped preference restoration
   from the prior correction.
7. Keep bounded diagnostic observations only if they remain inert and useful; do not weaken
   provenance or History assertions.

Do not:
- modify production code, FileUtil collision behavior, or DAO predicates;
- change the production probe boundary back to staging;
- relax the probe assertion to merely "some non-ambient path";
- remove the 360p rejection;
- lengthen timeouts merely to seek green;
- run broader verification before reviewer classification.

## Verification

Run only:
realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement

Require:
- exact child SHA/parent/tree;
- one allowed test file only;
- git diff --check PASS;
- nonzero focused execution;
- proof that the probe receives the collision-safe moved authoritative output and never ambientHighQuality;
- proof that quality validation rejects 360p against 720p;
- proof that History still references oldMedia;
- proof that oldMedia and ambientHighQuality remain present;
- proof that the rejected moved candidate does not survive as authoritative replacement media.

If the focused method passes with those semantics, STOP for reviewer classification. Do not run the
full class in the same task.

If it still fails, preserve the exact first failure and report the latest semantic boundary. Do
not make a second correction.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2.
No new production root is created.
Canonical counts remain unchanged.
No root is FIXED-CLOSED.
Repository-wide CLEAN remains unsupported.
