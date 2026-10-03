# BUG-UPDATER-04 direct-role recovery diagnostic instrumentation authorized

review_parent_sha: f747110ef11d594e97cae3467e75bf5252f24e43
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Accepted triage:
- exact failing assertion is the recoverDownloadExecution(918003L, "E1") == true assertion;
- recoverDownloadExecution returned false;
- failing test is unchanged from the pinned basis;
- immediate post-call process/marker state was not captured;
- cause remains NOT_VERIFIED;
- no BUG-UPDATER-04 production residual is proven.

Authorize one ANDROIDTEST-ONLY diagnostic correction in:
app/src/androidTest/java/com/ireum/ytdl/util/extractors/ytdlp/YtdlpNativeProcessBarrierStateMachineTest.kt

Required diagnostic scope for directNativeRoleMarkerUsesTheSameExactGenerationRecoveryCarrier only:
- preserve the same real tagged process, direct-role processId, marker state, generation token, and single recoverDownloadExecution call;
- before the call capture tagged PID visibility, process alive state, marker existence, marker contents, and existing read-only generation observation if available;
- immediately after the call and before the assertion/cleanup capture the same state;
- assign the recover return value to a local variable and include all captured state in the assertion failure message;
- do not add sleeps to make the test pass;
- do not call recover a second time;
- do not clear/delete/terminate state before diagnostics are captured;
- keep the original success assertions intact after the diagnostic capture.

PRODUCTION_SOURCE_EDIT_AUTHORIZED=NO
NEW_PRODUCTION_HOOK_AUTHORIZED=NO
OTHER_TEST_BODY_EDIT_AUTHORIZED=NO
DEVICE_TEST_AUTHORIZED=NO_AGENT_PREPARES_OPERATOR_SINGLE_METHOD_RERUN_ONLY
COMMIT_AUTHORIZED=NO
PUSH_AUTHORIZED=NO

Implementation-agent verification:
- compile complete debug AndroidTest Kotlin/source;
- git diff --check;
- preserve hashes and protected dirty inventory;
- no device test, commit, or publication.

After compile/static PASS, reviewer may authorize one operator rerun of only:
YtdlpNativeProcessBarrierStateMachineTest#directNativeRoleMarkerUsesTheSameExactGenerationRecoveryCarrier

BUG-UPDATER-04 remains OPEN P2.
