# BUG-UPDATER-04 UpdateUtil broader-fixture startup-readiness correction authorized

checkpoint_kind: BUG_UPDATER04_UPDATEUTIL_TEST_READINESS_CORRECTION_AUTHORIZATION
review_parent_sha: 76a79f57ae8bd466b442330ae7f8251ff2ab05ab
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Read-only triage accepted

Accepted triage:
- primary classification: TEST_HARNESS_OR_PRECONDITION_DEFECT;
- the UpdateUtil broader fixture lacks ordering against App startup/native-library readiness;
- the four failures occur during post-updater --version validation;
- required native libraries are present in the tested APK;
- failed-install filesystem state remains NOT_VERIFIED because UTP uninstalled the package after the run;
- broader result remains preserved as 1 PASS / 4 FAIL;
- no BUG-UPDATER-04 production residual is conclusively proven.

## Source-ordering evidence on pinned basis

App.onCreate() starts runtime readiness asynchronously.
That readiness path orders:
- scheduler transition recovery;
- defaults/notification setup;
- initLibraries();
- YoutubeDL.getInstance().init(this);
- Aria2c.getInstance().init(this).

UpdateUtilProductionWiringTest.setUp() currently obtains ApplicationProvider context and immediately begins
fixture preference/reset work without an explicit wait proving that App runtime readiness completed.

The broader fixture therefore has no established happens-before edge from App native-library initialization
to the UpdateUtil test's post-updater version validation.

## Reviewer decision

Authorize one narrow ANDROIDTEST-ONLY readiness/precondition correction.

Primary allowed file:
app/src/androidTest/java/com/ireum/ytdl/util/UpdateUtilProductionWiringTest.kt

PRODUCTION_SOURCE_EDIT_AUTHORIZED=NO
NEW_PRODUCTION_HOOK_AUTHORIZED=NO
GRADLE_OR_PACKAGING_EDIT_AUTHORIZED=NO
DEVICE_TEST_AUTHORIZED=NO_AGENT_PREPARES_OPERATOR_RERUN_ONLY

### Required correction contract

1. Establish a finite, deterministic precondition in the fixture proving the app's yt-dlp/Python runtime
   initialization is complete before any test that can reach post-updater --version validation.
2. Prefer an already-existing observable initialization/readiness condition from production/dependency state.
3. Do not create a new production hook merely for this test.
4. Do not replace real production post-update validation with a stub/bypass.
5. Do not make updaterForTesting skip runtime-authority or post-update validation semantics that production
   still requires.
6. If no stable existing readiness observation can be proven from current production/dependency APIs,
   STOP and report that exact limitation rather than inventing a readiness heuristic.
7. Use finite timeout/failure diagnostics. A sleep-only delay is not an acceptable correctness boundary.
8. Keep all five existing UpdateUtilProductionWiringTest semantic bodies/assertions unchanged unless a direct
   fixture-only refactor is required to apply the readiness precondition.
9. Preserve the existing 1 PASS / 4 FAIL evidence and the focused 7/7 PASS evidence.

### Verification boundary

Implementation agent may:
- inspect local dependency/API definitions read-only;
- edit only the authorized androidTest fixture;
- compile the complete debug Android-test Kotlin source;
- run git diff --check;
- record exact hashes/inventory.

Implementation agent must NOT:
- run device/instrumentation tests;
- edit production/config/dependencies/packaging;
- commit or publish.

After compile/static PASS, reviewer may authorize exactly one rerun of UpdateUtilProductionWiringTest on the
same ARM64 target.

BUG-UPDATER-04 remains OPEN P2.
Canonical counts remain 0/0/4.
Runtime correctness remains NOT_VERIFIED beyond the already accepted focused 7/7 PASS.

INDEPENDENT_EXECUTION: NOT EXECUTED
