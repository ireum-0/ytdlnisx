# b6b84106 DownloadOutput verified-quality diagnostic — missing low-quality ledger fixture classified

checkpoint_kind: REVIEWER_DIAGNOSTIC_CLASSIFICATION_AND_TEST_CORRECTION_AUTHORIZATION
review_parent_sha: e9c209c9dce07a008537a123bcdfcce7ff73f8bc
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: b6b84106a23d6698ec1569d9105910dec66d0a3d
reported_local_candidate_parent: b4df19003705083c75df25c6c48931dbda8cdb4e
reported_local_candidate_tree: 313555add3eb52212803511ec1c44f8525f6c0fc
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Evidence authority

The b6b84106 child and its focused runtime result remain implementation-agent evidence because the
child is protected local/unpublished and its exact diff is not available from GitHub.

The implementation agent reported:
- exactly one test-only child on b4df1900;
- only DownloadOutputProductionWiringTest.kt changed;
- focused execution: 1 executed, 0 PASS, 1 FAIL, zero skips/assumptions/errors;
- verifier finalization PASS;
- the production queue was empty and the target row absent at both observed queue windows;
- the target Download row remained Queued with blank executionId;
- WorkInfo progressed ENQUEUED -> RUNNING -> CANCELLED with terminal stopReason=1;
- yt-dlp success, video-quality probing, and exact attempt cleanup were not observed;
- unrelated captured admission fences were clear;
- scoped preferences were restored.

## Independent exact-source classification

Exact GitHub source at remote implementation 7d6a7b7c establishes the missing production
precondition.

DownloadDao.getQueuedScheduledDownloadsUntil() and its priority variant exclude a quality
replacement Download when:

- playlistURL matches history-redownload:%:quality:%; and
- no low_quality_redownload_items row exists for that Download id.

They also exclude a linked low-quality child when the linked item/operation is terminal, missing,
not RUNNING, or cancellation-requested.

The target method realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement constructs a
quality replacement marker but directly inserts the Download row with insertRaw(). It does not
create/link the low-quality operation/item carrier required by the production queue contract.

Production LowQualityRedownloadWorker does not persist quality replacement Downloads this way. It
creates/updates a LowQualityRedownloadItem and commits the Download + ledger linkage through
LowQualityRedownloadRepository.linkDownloadAtomically(). Existing low-quality production-wiring
tests already use this same linkage boundary.

Therefore the focused pre-claim failure is classified as:

TEST_HARNESS_QUALITY_REPLACEMENT_LEDGER_FIXTURE_MISSING

This is a test-harness residual in the existing BUG-DOWNLOAD-01 verification surface. It is not a
new production root and does not increase canonical counts.

The exact actor that converted the WorkRequest to CANCELLED need not be resolved before correcting
this fixture, because the target row is independently proven ineligible for the production queue
as currently constructed. The prior timeout versus current CANCELLED timing difference does not
restore the missing ledger authority and does not make the quality path valid.

## Authorized correction

Authorize exactly one forward TEST-ONLY correction child on exact local parent
b6b84106a23d6698ec1569d9105910dec66d0a3d.

Allowed source file only:
- app/src/androidTest/java/com/ireum/ytdl/database/DownloadOutputProductionWiringTest.kt

Production source changes remain forbidden.

For realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement:

1. Replace the manual quality-replacement Download persistence that bypasses the low-quality
   coordinator with a production-faithful low-quality ledger linkage.
2. Use LowQualityRedownloadRepository.linkDownloadAtomically() as the Download/ledger commit
   boundary rather than directly insertRaw() for the quality replacement child.
3. Establish a RUNNING low-quality operation and a nonterminal selected ledger item in the state
   required by linkDownloadAtomically().
4. Preserve source/type identity consistently between the History item, ledger item, and Download
   item. Do not bypass or disable HistoryReplacementSourceIdentity validation merely to make the
   fixture link.
5. Preserve the quality marker's 720p expected replacement contract and the authoritative staged
   360p synthetic output.
6. Preserve every semantic assertion:
   - ambient requested.mp4 is never accepted as authoritative output;
   - the staged output is the quality-probe input;
   - insufficient staged quality does not replace the old History media;
   - old media remains present;
   - ambient high-quality media remains present.
7. Keep the previously added bounded diagnostics if they remain inert and useful. They may be
   removed only if the exact corrected fixture reaches the intended semantic path and the removal
   does not weaken failure evidence.

Do not:
- modify production code or DAO predicates;
- insert a fake ledger row that could not represent the production linkage contract;
- bypass WorkManager;
- weaken source/type/operation identity checks;
- lengthen timeouts merely to seek green;
- run the full class, detached diff, union, Complete-Wave, or publication before reviewer
  classification.

## Verification

Run only:
realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement

Require:
- exact child SHA/parent/tree;
- one allowed test file only;
- git diff --check PASS;
- nonzero focused execution;
- evidence that the linked target is present in the production queue before claim;
- evidence that the real worker claims a nonblank executionId;
- evidence that yt-dlp synthetic success and video-quality probe are reached;
- preservation of the original ambient/staged/History assertions.

If the corrected production-faithful fixture reaches the quality path and the focused method
passes, STOP for reviewer classification. Do not continue to the full class in the same task.

If the method still fails after valid ledger linkage, preserve the first failure and report the
exact latest reached semantic boundary. Do not make a second correction.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2.
No new production root is created.
Canonical counts remain unchanged.
No root is FIXED-CLOSED.
Repository-wide CLEAN remains unsupported.
