# F11 ee7eea00 instrumentation-startup design accepted in principle — ABI confounder must be removed first

Date: 2026-09-23

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind

No push occurred.

Prior canonical review:
`e58edc1db3623359faf9cf81df582c3472744bc1`

## Design-task completion

The read-only instrumentation-startup interaction design completed without source/test/config edits, APK installation, instrumentation, semantic execution, commit, or push.

The report establishes:

- target package: `com.ireum.ytdl`;
- test package: `com.ireum.ytdl.test`;
- runner: `androidx.test.runner.AndroidJUnitRunner`;
- no custom test Application;
- no separate android:process for instrumentation;
- no project Orchestrator configuration identified;
- original failing UTP invocation filtered exactly to
  `com.ireum.ytdl.work.WorkManagerHandoffProductionTest`;
- original result remained zero executed tests / instrumentation process crash.

The locally resolved AndroidX runner is reported as 1.7.0.

The design report also establishes a supported non-semantic runner mode:

`-e log true`

with the intended property that runner/test discovery/class loading occurs while actual test method execution is bypassed.

This makes a direct log-only runner control a legitimate future discriminator in principle.

## Original UTP lifecycle facts

Reported retained evidence shows:
- UTP installed app APK first, then test APK;
- both install entries used empty install_options;
- both were marked uninstall_after_test=true;
- exact package-manager flags and exact app-data clearing behavior are not recoverable;
- target Application startup occurred under instrumentation;
- no distinct runner PID was preserved;
- UTP later uninstalled both packages.

The original semantic test body did not execute.

## Selected control design

The design agent selected:

**DIRECT_NON_SEMANTIC_RUNNER_STARTUP_CONTROL**

using direct `am instrument`, exact class filter, and `-e log true`, with zero semantic test bodies intended.

That design is accepted **in principle**.

## Independent runner-mode corroboration

The official AndroidJUnitRunner reference documents `-e log true` as log-only mode that loads/iterates selected test classes/methods while bypassing actual test execution.

Therefore a log-only direct runner control can be used as a startup/discovery diagnostic rather than a semantic rerun, provided the exact local runner artifact matches the reported version/configuration.

## Blocking ABI confounder

Do NOT execute the direct log-only runner control yet.

The original failing UTP attempt used:

`YTDLnisX-1.8.9-arm64-v8a-debug.apk`

The current fresh-install control instead used:

`YTDLnisX-1.8.9-x86_64-debug.apk`

on the same reported x86_64 emulator.

The project explicitly builds ABI-split APKs for:
- x86;
- x86_64;
- armeabi-v7a;
- arm64-v8a;
- universal.

The top-level build assigns distinct ABI version-code suffixes.

Therefore a successful direct runner control against the currently installed x86_64 app would leave at least these explanations unresolved:
- UTP-specific lifecycle/transport;
- original lost app state;
- timing;
- **arm64-v8a app artifact running on the x86_64 emulator**.

That success would not isolate instrumentation interaction cleanly enough.

The most informative next control is therefore not runner startup yet.

## Required next discriminator

First reproduce only the APP-ONLY startup boundary using the SAME ABI-specific app artifact class as the original failed UTP attempt:

**arm64-v8a app APK on the same emulator, no test APK, no instrumentation.**

This is a NEW clean installation control.

It does not restore the original lost durable state.

Required shape:
1. preserve current x86_64 fresh-startup evidence;
2. verify exact ee7eea00 arm64-v8a app APK provenance;
3. uninstall the current x86_64 target package once to avoid carrying its data into the ABI-matched control;
4. install the exact ee7eea00 arm64-v8a APP APK once;
5. install no test APK;
6. resolve the launcher from package metadata;
7. start the launcher exactly once;
8. bounded startup/process/crash capture;
9. no retry;
10. no instrumentation.

Possible results:

`ABI_MATCHED_FRESH_STARTUP_COMPLETES`
- arm64-v8a app can complete ordinary startup on the same emulator;
- ABI-only ordinary startup becomes less plausible as the original cause;
- next direct log-only runner control should use this same arm64 app state.

`ABI_MATCHED_FRESH_STARTUP_FAILS_OR_HANGS`
- original app artifact / ABI-on-device combination can reproduce startup failure without instrumentation;
- stop for independent source/ABI/runtime classification;
- do not proceed to runner control.

`ABI_MATCHED_FRESH_STARTUP_CONTROL_INVALID`
- exact artifact provenance/install/launcher/device boundary fails before a meaningful result;
- stop.

## Current source disposition

Keep:

**A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN.**

No production correction is authorized.

The original WorkManagerHandoffProductionTest remains:
- zero executed tests;
- not a semantic PASS/FAIL;
- execution gate NOT VERIFIED;
- unchanged-tree semantic rerun unauthorized.

## Current broad closure scope

No change:
- historical method-level 307/16 manifest unrecoverable;
- historical 16-class whole-class anchor retained;
- required scheduler-settings transition class added for current intended scope;
- expected local scope 17 classes / 316 identities subject to later authoritative exact-source verification;
- broad execution unauthorized.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
