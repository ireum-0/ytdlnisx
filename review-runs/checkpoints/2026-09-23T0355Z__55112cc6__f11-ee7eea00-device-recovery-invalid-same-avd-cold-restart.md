# F11 ee7eea00 ABI device-service recovery invalid — same-AVD cold restart authorized

Date: 2026-09-23

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind

No push occurred.

Prior canonical review:
`fb639242b4e321a75ae0f7977115db84cad29efe`

## Recovery attempt result

The same-emulator service-recovery attempt did not restore a usable Android runtime.

Reported before recovery:
- device `emulator-5554`;
- API 36;
- model `sdk_gphone64_x86_64`;
- adb online;
- `sys.boot_completed=1`;
- package service absent;
- activity service absent;
- `system_server` PID 281;
- no servicemanager PID returned.

One authorized command was issued:

`adb -s emulator-5554 reboot`

Reported result:
- command emitted no output;
- host adb client did not return during the recovery window;
- over ~90 seconds / 17 readiness checks, adb remained online and `sys.boot_completed=1`;
- package service remained unavailable;
- activity service remained unavailable;
- `system_server` PID was absent;
- host adb client was interrupted after the bounded window;
- no second reboot was issued.

No app/package mutation occurred.
No APK build/install occurred.
No launcher startup occurred.
No instrumentation or semantic test occurred.

Result:

**ABI_DEVICE_SERVICE_RECOVERY_INVALID**

This classification is accepted.

## Interpretation

This is an emulator/runtime infrastructure failure.

It provides:
- no application startup result;
- no ABI-runtime result;
- no instrumentation result.

The current emulator process is not a trustworthy Android runtime merely because adb is online and `sys.boot_completed=1`.

Repeated `adb reboot` attempts are NOT authorized.

Canonical defect-count delta: 0.

## Same-device evidence consequence

The original 09:42 installed app/data state was already unavailable before this ABI control.

The current purpose of retaining the device was therefore limited to minimizing platform/runtime differences while testing the arm64-v8a artifact.

Because the current emulator process cannot restore `system_server` / PackageManager / ActivityManager through the one allowed adb reboot, preserving this exact process instance no longer adds useful app-state evidence.

However, before abandoning same-AVD comparability entirely, one narrower infrastructure recovery remains reasonable:

**one host-level cold restart of the SAME AVD, without wiping userdata and without loading a Quick Boot snapshot.**

This is not permission to create a different AVD.

## Authorized next infrastructure boundary

Before terminating the broken emulator process, resolve and record the exact AVD identity used by `emulator-5554` through nondestructive evidence where available, for example:
- `adb -s emulator-5554 emu avd name`;
- existing emulator/IDE process command line;
- AVD metadata;
- retained prior evidence.

Then, only if the exact AVD identity is defensible:

1. stop the broken emulator process once;
2. relaunch that exact SAME AVD once;
3. do not wipe data;
4. do not restore a saved snapshot;
5. use a cold/full boot path such as the local emulator's documented `-no-snapshot-load` equivalent if supported;
6. do not start a second parallel emulator instance;
7. wait boundedly for:
   - adb online;
   - `sys.boot_completed=1`;
   - `system_server` present;
   - package service available;
   - activity service available.

Do not assume an emulator CLI flag from memory; verify locally supported command/AVD launch shape before executing.

If same-AVD identity or a non-wiping cold-restart method cannot be established:
- stop;
- do not improvise a replacement AVD.

If the same AVD cold restart fails to restore services:
- classify `SAME_AVD_COLD_RESTART_INVALID`;
- do not repeat recovery;
- do not proceed to app control.

## ABI control after successful same-AVD recovery

Only if the same AVD returns with healthy core services may the arm64-v8a app-only control continue.

The existing intended control remains:
- exact ee7eea00 arm64-v8a app artifact;
- no androidTest APK;
- uninstall current target package once if present;
- verify package absence;
- install exact arm64 app once;
- restore/seed no old app data;
- label `NEW ABI-MATCHED CLEAN INSTALLATION STATE`;
- resolve real launcher;
- launch exactly once;
- bounded process/crash/native-ABI capture;
- no retry;
- no instrumentation.

Possible app-control results:
- `ABI_MATCHED_FRESH_STARTUP_COMPLETES`;
- `ABI_MATCHED_FRESH_STARTUP_FAILS_OR_HANGS`;
- `ABI_MATCHED_FRESH_STARTUP_CONTROL_INVALID`.

If same-AVD cold restart itself fails:
- `SAME_AVD_COLD_RESTART_INVALID`.

## Current semantic state

Unchanged:
- `A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN`;
- original `WorkManagerHandoffProductionTest` remains zero tests / execution gate NOT VERIFIED;
- direct non-semantic runner control remains accepted in principle but blocked until a valid ABI-matched app-only startup result exists;
- broad execution remains unauthorized;
- current intended broad closure scope remains 17 classes / 316 identities subject to later authoritative exact-source verification.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
