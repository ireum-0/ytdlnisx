# F11 ee7eea00 direct non-semantic runner startup/discovery completes

Date: 2026-09-24 +09:00

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind.

Prior canonical review checkpoint:
`b2fc6f246cc5ac5d285be76cd3d2fa15f41c912c`

## Execution-context note

The completion report used older protocol/handoff blobs:
- protocol `47a4ec6b71cfaefc19143779aa68dc9e30cdba66`;
- handoff `d9b213a3eb3b9630e040fb5bebb9eee6946cc236`.

Those predate the bounded no-push bootstrap revisions. The extra GitHub/history work is therefore not a requirement for future runs and does not invalidate the runtime result.

## Preserved ARM64 target state

Reported:
- exact AVD `Medium_Phone_API_36.1`;
- serial `emulator-5554`;
- no emulator restart;
- target app remained `com.ireum.ytdl` versionName 1.8.9 / versionCode 108090004;
- no app uninstall, reinstall, or data clear;
- accepted ARM64 clean-install state reused;
- pre-instrumentation app PID 5812;
- first-run permission prompt left untouched.

## AndroidTest provenance

Reported retained artifact:

`D:\AndroidStudioProjects\ytdlnisx-f11\app\build\outputs\apk\androidTest\debug\YTDLnisX-1.8.9-debug-androidTest.apk`

SHA-256:

`F4991114E2D0D9E9436101CEBC717995A12FB2E721E2AE26D525AD9B3A74DDAB`

Reported:
- test package `com.ireum.ytdl.test`;
- target package `com.ireum.ytdl`;
- runner `androidx.test.runner.AndroidJUnitRunner`;
- runner version 1.7.0;
- `-e log true` confirmed as log-only/non-semantic mode;
- no Orchestrator requirement identified.

## Test APK installation

Reported:
- exact test APK installed once;
- install exit 0 / Success;
- target app remained installed and unchanged;
- expected instrumentation component resolved.

## Direct non-semantic runner control

Command executed exactly once at 2026-09-24 01:49:19 +09:00:

`adb -s emulator-5554 shell am instrument -w -r -e class com.ireum.ytdl.work.WorkManagerHandoffProductionTest -e log true com.ireum.ytdl.test/androidx.test.runner.AndroidJUnitRunner`

Reported result:
- shell exit 0;
- requested class identified;
- 13 tests discovered;
- visible runner output included method names/discovery/status records;
- zero semantic test bodies executed;
- `newApplication com.ireum.ytdl.App`, runner onCreate/onStart observed;
- instrumentation finished normally;
- PID 7256 exited as USER REQUESTED / FORCE STOP with status 0, not as crash;
- no fatal exception, ANR, or tombstone;
- no app/runner PID remained afterward;
- permission prompt and visible app task remained.

A nonfatal log reported missing x86_64 ffmpeg payload asset. Because runner discovery completed and no fatal process failure followed, preserve it as evidence only; do not classify it here as the original zero-test crash root.

The displayed transcript was truncated and no separate complete raw stdout/stderr file was saved. No retry occurred. The available evidence is sufficient for the narrow classification because the runner returned normally, discovered 13 tests, and no semantic body executed, but the missing full transcript should remain an evidence-quality limitation for finer-grained future claims.

## Diagnostic disposition

Accept:

`RUNNER_STARTUP_COMPLETES_NO_TEST_BODY`

This establishes that, on the accepted ARM64 clean-install target state, direct test-APK + AndroidJUnitRunner startup/discovery can complete without executing semantic test bodies.

It does NOT establish:
- that the original UTP failure was infrastructure;
- that UTP itself was the root cause;
- a semantic PASS/FAIL for `WorkManagerHandoffProductionTest`;
- completion of its execution gate;
- F11 CLEAN.

Keep source attribution:

`A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN`

Original WorkManagerHandoffProductionTest remains:
- 0 tests in the original failing UTP run;
- execution gate NOT VERIFIED;
- not a semantic PASS or FAIL.

## Workflow consequence

The direct-runner boundary no longer reproduces the original zero-test crash.

The next useful discriminator is a UTP-specific non-semantic startup/discovery control that preserves zero semantic test-body execution while reintroducing the UTP/Gradle transport and lifecycle boundary.

Do not create a separate design-only phase merely to inspect the local invocation. The next execution task should:
1. preserve current target/test package state unless the exact UTP transport itself necessarily changes it;
2. inspect the exact local Gradle/UTP configuration just enough to determine a supported log-only class-filtered invocation;
3. if such an invocation is safely supported, execute it once in the same task;
4. if UTP cannot carry the non-semantic runner argument without ambiguity or semantic body risk, classify the control invalid/unsupported and stop without executing semantic tests.

Canonical defect count delta: 0.
CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED
