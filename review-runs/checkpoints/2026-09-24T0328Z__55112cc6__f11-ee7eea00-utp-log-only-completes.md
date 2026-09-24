# F11 ee7eea00 UTP/Gradle non-semantic startup/discovery completes

Date: 2026-09-24 +09:00

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind.

Prior canonical review checkpoint:
`e588ab776e9f4e4d93bbcab006f3c147de79a4a3`

## Governance note

The completion report recorded stale local private governance blobs:
- protocol `47a4ec6b71cfaefc19143779aa68dc9e30cdba66`;
- handoff `d9b213a3eb3b9630e040fb5bebb9eee6946cc236`.

The implementation/review tips used by the task were current and matched the authorized prompt:
- implementation `55112cc6d5234e44b785fe655007a7fb58ac0553`;
- review `e588ab776e9f4e4d93bbcab006f3c147de79a4a3`.

The stale private-file read is a bootstrap-quality defect, not a runtime-control invalidation. Protocol has since been tightened to require reading governance from one fresh bounded fetch of `ytdlnisx-review` rather than a stale local checkout.

## Preserved prior controls

Accepted and not repeated:
- `ABI_MATCHED_FRESH_STARTUP_COMPLETES`;
- `RUNNER_STARTUP_COMPLETES_NO_TEST_BODY`.

## UTP non-semantic command resolution

Reported exact local configuration:
- Gradle/UTP path: `:app:connectedDebugAndroidTest`;
- exact class filter: `com.ireum.ytdl.work.WorkManagerHandoffProductionTest`;
- runner argument: `log=true`;
- Android Gradle Plugin: 8.13.2;
- runner: `androidx.test.runner.AndroidJUnitRunner` 1.7.0;
- selected target app: exact ARM64-v8a artifact, not x86_64/universal;
- app SHA-256:
  `F168474EEAA55C16AEA80E23795673580A83895891F26C1487A22A531E97C0F8`;
- test APK SHA-256:
  `F4991114E2D0D9E9436101CEBC717995A12FB2E721E2AE26D525AD9B3A74DDAB`.

## One UTP non-semantic execution

Reported:
- exact AVD `Medium_Phone_API_36.1`;
- serial `emulator-5554`;
- one UTP/Gradle control only;
- exact class/filter + `log=true` reached AndroidJUnitRunner;
- 13 runner-visible identities discovered;
- UTP reported 13/13 completed, 0 skipped, 0 failed;
- these statuses are log-only discovery/status results, not semantic test-body passes;
- semantic test-body execution count = 0;
- Gradle task output ended `BUILD SUCCESSFUL`;
- target process PID 20879 observed;
- `App.onCreate` and AndroidJUnitRunner startup observed;
- instrumentation finished normally;
- no fatal exception, ANR, or tombstone in the captured relevant logcat.

UTP package lifecycle:
- installer configuration used `uninstall_after_test: true` for both APKs;
- UTP removed/reinstalled packages as part of its normal control;
- both packages were removed after the run;
- no manual data clearing or state seeding occurred.

Evidence-quality note:
- PowerShell wrapper did not produce a numeric child exit code because it remained attached to Gradle's idle daemon after the task had already completed;
- the task-level Gradle output itself recorded successful completion;
- no retry occurred;
- raw Gradle stdout and bounded lifecycle logcat were separately preserved.

## Diagnostic disposition

Accept:

`UTP_STARTUP_COMPLETES_NO_TEST_BODY`

This establishes that, under the exact ARM64 artifact boundary, the UTP/Gradle transport can:
- install/launch the intended target/test pair;
- start the target Application and AndroidJUnitRunner;
- select/discover `WorkManagerHandoffProductionTest`;
- complete the log-only discovery/status lifecycle;
- do so with zero semantic test-body execution and without reproducing the original startup crash.

Combined accepted controls now show:
1. ordinary clean ARM64 app startup completes;
2. direct AndroidJUnitRunner startup/discovery completes;
3. UTP/Gradle startup/discovery completes.

Therefore the earlier blanket prohibition on an unchanged-tree semantic rerun was tied to an unresolved startup boundary that is now sufficiently discriminated for current clean-state verification.

This does NOT establish:
- that the original zero-test failure was infrastructure;
- that the original failure was caused by UTP;
- the root cause of the historical crash;
- semantic PASS/FAIL for the 13 test methods;
- F11 CLEAN.

Keep startup attribution:

`A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN`

The original historical state is gone, so a new semantic run is a current-candidate clean-state execution gate, not a faithful replay of the lost 09:42 durable state.

## Next execution authority

Authorize a current-candidate semantic execution of:

`com.ireum.ytdl.work.WorkManagerHandoffProductionTest`

using the exact ee7eea00 ARM64/UTP boundary, once.

If that focused class validly passes 13/13, the startup blocker is no longer a reason to keep the already accepted current F11 broad closure scope blocked. The implementation agent may continue in the same task into the exact current closure manifest if it can resolve the previously accepted local 17-class / 316-identity manifest without ambiguity and can preserve exact frozen-27 baseline equivalence.

Stop on the first new valid semantic failure, manifest ambiguity, execution invalidity, or protected-state/repository-write conflict.

Canonical defect count delta: 0.
CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED
