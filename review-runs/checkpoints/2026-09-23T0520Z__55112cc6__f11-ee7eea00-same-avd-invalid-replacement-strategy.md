# F11 ee7eea00 same-AVD cold restart invalid — replacement runtime strategy required

Date: 2026-09-23

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind

No push occurred.

Prior canonical review:
`ce92baeee5f9b9a63d4b419eede22ec06f40673d`

## Same-AVD cold-start result

The exact prior AVD identity was reported as:

- AVD name: `Medium_Phone_API_36.1`
- AVD path: `C:\Users\dh2\.android\avd\Medium_Phone.avd`
- target: android-36.1
- emulator executable: `C:\Users\dh2\AppData\Local\Android\Sdk\emulator\emulator.exe`
- emulator version: 36.3.10.0
- prior runtime serial: `emulator-5554`

The user had manually powered off the prior emulator before the control.

The exact same AVD was launched once with:

`emulator.exe -avd Medium_Phone_API_36.1 -no-snapshot-load`

Reported result:
- no wipe requested;
- no snapshot restore requested;
- host launch process initially returned PID 26148;
- emulator process later disappeared;
- no emulator/qemu process remained;
- `emulator-5554` never reappeared;
- over ~129 seconds no adb reconnect occurred;
- `sys.boot_completed`, `system_server`, PackageManager, and ActivityManager were therefore not observable;
- no second launch occurred;
- no package mutation, APK installation, app launch, instrumentation, or semantic test occurred.

Result:

**SAME_AVD_COLD_RESTART_INVALID**

This classification is accepted.

## Interpretation

The retained AVD/runtime can no longer provide a valid Android execution environment.

Further attempts to:
- adb reboot the broken runtime;
- start the same AVD again;
- load a prior snapshot;
- wipe/recreate the same AVD in place

are NOT authorized by this checkpoint.

The same-AVD evidence path is exhausted.

This remains an infrastructure/runtime failure only.

It does not establish:
- an app startup failure;
- an ABI failure;
- an instrumentation failure;
- a production defect.

Canonical defect-count delta: 0.

## Evidence consequence

The original 0-test WorkManagerHandoff crash occurred on the prior AVD/runtime, but the original installed app/data state had already been lost before the ABI-control sequence began.

Therefore a future replacement runtime cannot reproduce that exact historical device state.

Any future runtime evidence must be labeled as a **new control environment**, not a restoration or replay of the original device state.

The most important comparability dimensions to preserve are expected to include:
- Android API/system image level;
- emulator architecture / ABI support;
- emulator implementation/version where feasible;
- device profile where feasible;
- app artifact identity;
- test APK identity when later authorized;
- runner/UTP configuration;
- install/data-state construction;
- exact command/lifecycle evidence.

## Required next task

Do NOT create a replacement emulator/device yet.

Perform a read-only replacement-runtime strategy review.

The strategy review must determine, using local SDK/AVD metadata and preserved UTP evidence:

1. whether an equivalent fresh AVD can be created from the same system image and device profile;
2. whether the exact Android 36.1 system image/package used by the prior AVD is still locally installed;
3. what AVD config dimensions can be copied/recreated without carrying corrupted runtime state;
4. whether a fresh AVD should use:
   - a newly created equivalent AVD;
   - a cloned config with fresh userdata;
   - an Android Studio-created AVD from the same image/profile;
   - another locally supportable approach;
5. which approach best preserves comparability without reusing corrupted userdata/snapshots;
6. whether x86_64 + arm64-v8a translation support can be reproduced;
7. whether emulator-version pinning is possible/necessary;
8. exact provenance that must be captured before any replacement runtime is used.

The review must select exactly one strategy or conclude that no sufficiently comparable replacement can be identified.

No runtime creation or launch is authorized during the strategy review.

## Current semantic state

Unchanged:
- source attribution remains `A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN`;
- original `WorkManagerHandoffProductionTest` remains `FAIL BEFORE EXECUTION / 0 tests`;
- execution gate remains NOT VERIFIED;
- direct non-semantic runner control remains accepted in principle but blocked on a valid ABI-matched app-only startup control;
- broad execution remains unauthorized;
- intended broad closure scope remains 17 classes / 316 identities subject to later authoritative exact-source verification.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
