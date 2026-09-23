# F11 ee7eea00 ABI-matched startup control invalid — emulator core services unavailable

Date: 2026-09-23

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind

No push occurred.

Prior canonical review:
`08eb280d82f3f3ecfd459ecd55bc4a6ee5779010`

## Attempt result

The ABI-matched arm64 app-only startup control did not reach package mutation or app startup.

Reported device:
- emulator-5554;
- online;
- API 36;
- x86_64;
- ABI list x86_64,arm64-v8a;
- `sys.boot_completed=1`.

However the device's core Android services required by the control were unavailable:
- package queries returned `Can't find service: package`;
- activity query returned `Can't find service: activity`.

Therefore:
- current target-package presence could not be established;
- no uninstall occurred;
- no arm64 APK install occurred;
- no launcher resolution occurred;
- no ActivityManager launch occurred;
- no instrumentation or semantic test occurred.

Result:

**ABI_MATCHED_FRESH_STARTUP_CONTROL_INVALID**

This classification is accepted.

## Classification

This is an external device/runtime infrastructure precondition failure, not an application or ABI result.

`sys.boot_completed=1` alone is insufficient readiness evidence when PackageManager and ActivityManager services are unavailable.

The attempt produced no semantic app result and no ABI-runtime result.

Canonical defect-count delta: 0.

## Rerun policy

The protocol permits a rerun when an external infrastructure/tool interruption prevented the requested stage from producing a valid result, provided the invalid attempt is preserved.

That condition applies here.

A retry of the ABI-matched app-only control may therefore be authorized only after bounded recovery of the SAME emulator.

Do not substitute a different emulator/device.

## Allowed infrastructure recovery boundary

Before any app/package mutation:

1. preserve the invalid-attempt evidence;
2. verify adb connectivity;
3. inspect `service list` / `service check package` / `service check activity` or equivalent read-only service-manager evidence;
4. inspect `system_server` presence and relevant boot/service readiness;
5. if package/activity services are still unavailable, authorize at most ONE normal reboot of the same emulator via adb;
6. after reboot, wait boundedly for:
   - adb online;
   - `sys.boot_completed=1`;
   - package service available;
   - activity service available;
7. if both services do not become available within the bounded recovery window, stop with infrastructure invalid;
8. do not wipe data, cold-boot a replacement, restore a snapshot, recreate the AVD, or switch devices.

A normal reboot of the same emulator is infrastructure repair, not a replacement device.

## Retry boundary after readiness recovery

Only after the same emulator proves package/activity service readiness may the previously authorized ABI-matched fresh-install control be retried once.

Because the prior invalid attempt performed no target package mutation, this is not green-seeking after a valid app result.

The retry must still:
- preserve x86_64 control evidence;
- establish exact ee7eea00 arm64-v8a app APK provenance;
- uninstall target app once if present;
- verify package absence;
- install exact arm64 app APK once;
- install no test APK;
- restore/seed no app data;
- resolve launcher;
- perform one launcher startup;
- capture bounded startup/process/native-ABI evidence;
- never run instrumentation.

Possible app-control results remain:
- `ABI_MATCHED_FRESH_STARTUP_COMPLETES`;
- `ABI_MATCHED_FRESH_STARTUP_FAILS_OR_HANGS`;
- `ABI_MATCHED_FRESH_STARTUP_CONTROL_INVALID`.

If emulator service recovery itself fails, use:

`ABI_DEVICE_SERVICE_RECOVERY_INVALID`

and do not attempt package mutation.

## Current semantic state

Keep:
- source attribution `A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN`;
- original WorkManagerHandoffProductionTest = zero tests / execution gate NOT VERIFIED;
- direct non-semantic runner control accepted in principle but still blocked on valid ABI-matched app-only startup;
- current broad execution unauthorized.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
