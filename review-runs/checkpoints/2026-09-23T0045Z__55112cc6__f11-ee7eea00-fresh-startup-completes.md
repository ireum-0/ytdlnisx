# F11 ee7eea00 fresh-install startup completes — instrumentation interaction remains unresolved

Date: 2026-09-23

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind

No push occurred.

Prior canonical checkpoint:
`0141e9b878bd06fc5181834cc77cfd88f0d7df2e`

## Fresh-install control

Reported exact candidate build:
- task: `:app:assembleDebug --console=plain`;
- result: BUILD SUCCESSFUL;
- APK: `app/build/outputs/apk/debug/YTDLnisX-1.8.9-x86_64-debug.apk`;
- SHA-256: `1D63945DC5B1089E0C528F44FD126E46126D2021D8F7A5C3BF5D3207F2E74F65`;
- package: `com.ireum.ytdl`;
- versionName: `1.8.9`;
- versionCode: `108090003`.

Reported install:
- one app APK install;
- success / exit 0;
- no androidTest APK installed;
- explicitly NEW CLEAN INSTALLATION STATE.

Reported first launcher startup:
- launcher `com.ireum.ytdl/.Default`;
- ActivityManager command returned exit 0;
- ActivityManager wait status timed out after ~26.3 s;
- process PID 12339 remained alive;
- app task/window became visible;
- Android permission controller became top-resumed over the app for fresh-install runtime permission;
- no crash, fatal exception, ANR, or tombstone;
- nonfatal ffmpeg fallback / empty-result logs did not terminate the process.

Result:

**FRESH_INSTALL_STARTUP_COMPLETES**

This result is accepted as a valid diagnostic control.

## Interpretation

The exact local candidate can complete ordinary production Application startup from a fresh/clean installation state.

Therefore the original 09:42 zero-test instrumentation crash is NOT established as:
- a deterministic clean-install Application startup failure;
- a launcher-independent always-on production startup crash.

However this control does NOT establish that the original failure was infrastructure.

The original failure may still depend on:
- instrumentation/test-runner startup interaction;
- test APK/classpath/dex verification interaction;
- UTP package/process lifecycle;
- lost prior durable app state;
- timing/concurrency specific to the instrumentation startup;
- another runner/device interaction not exercised by ordinary launcher startup.

Keep current source attribution:

**A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN.**

No production source correction is authorized.

Canonical defect-count delta: 0.

## Original execution gate

`WorkManagerHandoffProductionTest` remains:
- FAIL BEFORE EXECUTION;
- zero tests;
- not a semantic PASS or semantic FAIL;
- execution gate NOT VERIFIED.

Do not rerun it unchanged merely to seek green.

## Instrumentation configuration facts independently visible on authoritative remote

At remote `55112cc6...`:
- target application uses `.App`;
- test runner is `androidx.test.runner.AndroidJUnitRunner`;
- no project androidTest manifest override was found in the reviewed tree;
- build.gradle shows AndroidJUnitRunner and ordinary AndroidJUnit4 dependencies;
- no Orchestrator configuration was identified in the reviewed build.gradle snippets;
- `WorkManagerHandoffProductionTest` uses `AndroidJUnit4` and initializes its in-memory DB / testing overrides in `@Before`, so the reported zero-test process death occurred before those test-method preconditions could become valid semantic evidence.

These remote facts are context only; exact local test APK/generated-manifest behavior must still be inspected locally before authorizing a runner control.

## Current broad closure scope

No change:
- historical method-level 307/16 manifest unrecoverable;
- retained 16-class whole-class anchor;
- current intended closure scope = anchor + scheduler-settings transition class;
- expected local scope = 17 classes / 316 identities subject to authoritative exact-source verification;
- broad execution remains unauthorized.

## Next boundary

Do NOT immediately run instrumentation.

First perform a read-only **INSTRUMENTATION STARTUP INTERACTION DESIGN** on exact local ee7eea00.

The design must compare:
1. the successful fresh launcher startup;
2. the original failing UTP/instrumentation startup;
3. exact local app/test APK build and generated instrumentation metadata;
4. package install/uninstall/replace behavior used by the original Gradle/UTP path;
5. runner arguments and class filter;
6. target/test process startup order;
7. permission/package-data handling;
8. whether UTP/test install changes or resets target app state;
9. whether a control can exercise instrumentation Application startup without executing the blocked semantic test.

The design must prefer the smallest diagnostic that can distinguish:
- runner/test-APK startup failure before test discovery;
- UTP-specific lifecycle failure;
- target Application startup interaction under instrumentation;
- ordinary semantic test failure.

No source/test/config edit.
No instrumentation in the design step.
No push.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
