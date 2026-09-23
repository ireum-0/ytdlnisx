# F11 ee7eea00 ABI-matched ARM64 app-only startup completes

Date: 2026-09-24 +09:00

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind.

Prior canonical checkpoint:
`af1a6e58bc27986d33e9c7b055e5e66020bf06bc`

## Execution-context note

The completion report was produced against older protocol/handoff blobs:
- protocol blob `715172c3b198e5c3a2e970729192ceccc37787f6`;
- handoff blob `1d47f67b6ee0abf635ad05130317a75a261e0004`.

Those blobs predate the later protocol rule that successful preauthorized intermediate controls should chain directly rather than stop artificially.

The stale stop-only instruction explains why the report stopped after ARM64 startup success. It does not invalidate the ARM64 startup result itself.

## Same-AVD readiness

Reported:
- exact AVD `Medium_Phone_API_36.1`;
- serial `emulator-5554`;
- same already-running AVD reused;
- adb state `device`;
- `sys.boot_completed=1`;
- `system_server` PID 817;
- package service found;
- activity service found;
- no reboot or relaunch in this task.

## Exact ARM64 app provenance

Reported retained artifact:

`D:\AndroidStudioProjects\ytdlnisx-f11\app\build\outputs\apk\debug\YTDLnisX-1.8.9-arm64-v8a-debug.apk`

SHA-256:

`F168474EEAA55C16AEA80E23795673580A83895891F26C1487A22A531E97C0F8`

Metadata:
- package `com.ireum.ytdl`;
- versionName `1.8.9`;
- versionCode `108090004`;
- ABI `arm64-v8a`;
- signing certificate SHA-256 `403c3c085f6e9fd68e0a93549b657a13903fa50b051dcfed50aef43324aa8713`.

The report states local metadata/full hash were revalidated and no rebuild was needed.

## Clean-install control

Reported:
- `com.ireum.ytdl.test` absent before control;
- previous target app versionCode 108090003 uninstalled once;
- package absence verified;
- exact ARM64 app installed once without `-r`;
- install succeeded;
- resulting state explicitly labeled `NEW ABI-MATCHED CLEAN INSTALLATION STATE`.

## One app-only startup attempt

Reported launcher:

`com.ireum.ytdl/.Default`

Command executed exactly once at 2026-09-24 00:58:42.084 +09:00:

`adb -s emulator-5554 shell am start -W -n com.ireum.ytdl/.Default`

Reported result:
- exit 0;
- ActivityManager `Status: ok`;
- `LaunchState: COLD`;
- `Complete`;
- Android permission controller took over the first-run permission request;
- app PID 5812 remained present in all 12 observations over 60 seconds;
- app task/window remained visible;
- final app activity was paused behind top-resumed permission controller;
- no permission interaction was performed;
- no app-exit record, fatal exception, ANR, tombstone, or native linker failure was found.

The report also observed a `ResultDao_Impl.getFirstResult()` empty-result `IllegalStateException` via `ResultViewModel.checkTrending()`, but the app process remained alive and visible. This runtime log observation is preserved as evidence only and is not independently classified here as a new canonical blocker or startup failure.

## Diagnostic disposition

Accept:

`ABI_MATCHED_FRESH_STARTUP_COMPLETES`

This establishes only that the exact ee7eea00 ARM64 app can complete ordinary clean-install startup on the same recovered AVD without instrumentation.

It does not establish:
- completion of the first-run permission flow;
- post-permission behavior;
- UTP causality;
- semantic WorkManagerHandoffProductionTest execution;
- F11 CLEAN.

The prior ABI confounder that blocked direct runner startup/discovery control is now removed.

## Workflow consequence

Do NOT repeat the ARM64 uninstall/install/first-start control.

Preserve the current exact ARM64 clean-install app state.

Next authorized control:

`DIRECT_NON_SEMANTIC_RUNNER_STARTUP_CONTROL`

Use:
- exact ee7eea00 androidTest artifact;
- test package `com.ireum.ytdl.test`;
- runner `androidx.test.runner.AndroidJUnitRunner`;
- exact class filter `com.ireum.ytdl.work.WorkManagerHandoffProductionTest`;
- log-only mode `-e log true`;
- no semantic test body execution;
- one execution only;
- no retry.

If the known AVD has simply stopped, the current protocol permits one exact same-AVD non-wiping/no-snapshot-load launch and continuation of the same control after minimum readiness.

Canonical defect count delta: 0.
CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED
