# BUG-UPDATER-04 direct-role single-method rerun blocked by device absence

review_parent_sha: d638e3e0bc2de6168aea7f00caf73985cd3aa87d
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Operator attempt:
- intended method: YtdlpNativeProcessBarrierStateMachineTest#directNativeRoleMarkerUsesTheSameExactGenerationRecoveryCarrier
- connectedDebugAndroidTest failed before any test execution
- DeviceException: No connected devices!
- BUILD FAILED in 14s
- semantic tests executed: 0

Classification:
INFRASTRUCTURE_FAILURE_NO_DEVICE
SEMANTIC_RESULT=NOT_EXECUTED
BUG_UPDATER04_PRODUCTION_RESIDUAL=NOT_VERIFIED

Do not treat this as a test failure and do not retry unchanged while no device is connected.

Authorized recovery:
- restore an ABI-compatible ARM64 target connection;
- verify adb reports the target before Gradle invocation;
- do not change source/test/config;
- once target presence is materially restored, rerun the same single method exactly once;
- stop after PASS/FAIL/zero-test/infrastructure result;
- preserve full diagnostic failure message if semantic FAIL occurs.

No production edit, commit, push, or publication is authorized.

BUG-UPDATER-04 remains OPEN P2.
