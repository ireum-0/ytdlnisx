# F11 ee7eea00 ABI control precondition reconciled

Date: 2026-09-24 +09:00

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind; merge base `55112cc6d5234e44b785fe655007a7fb58ac0553`.

Prior canonical review:
`be4ed390b0f5277977116a73ad3cc665522638ff`

## Evidence received

The prior ABI-matched startup control stopped before package mutation at 2026-09-23 23:35:26 +09:00 because no adb device was attached and `emulator-5554` was absent.

The follow-up reconciliation report states that, at approximately 2026-09-24 00:20 +09:00:

- adb had no attached device;
- no emulator/QEMU process or emulator console listener was present;
- AVD metadata identified the expected existing AVD `Medium_Phone_API_36.1`;
- no competing emulator instance or attached serial was present;
- the same AVD was launched exactly once with the installed emulator using `-no-snapshot-load`;
- no wipe, snapshot load, replacement AVD, Android reboot, or adb-server restart occurred;
- the launched AVD attached as `emulator-5554`;
- full readiness was observed at 00:25:28.945 +09:00:
  - adb state `device`;
  - `sys.boot_completed=1`;
  - `system_server` PID 817;
  - package service found;
  - activity service found;
- no APK build, app/package mutation, app launch, test APK, instrumentation, or semantic test occurred.

The report also preserved the prior invalid evidence and new reconciliation evidence under ignored build directories.

## Disposition

Accept the runtime-precondition result as evidence:

`ABI_CONTROL_PRECONDITION_RECONCILED`

The exact same AVD is again ready for the ABI-matched ARM64 app-only startup control.

This does not establish any app, ARM64, instrumentation, UTP, or semantic result.

The previous invalid startup-control attempt did not consume the intended startup attempt because it stopped before uninstall/install/launch.

## Workflow consequence

Safe next action:

`ABI_MATCHED_ARM64_STARTUP_CONTROL_RETRY_ELIGIBLE`

Apply the current protocol's minimum-necessary gating rule: if the known same AVD is already running, use it; if it has simply stopped before package mutation, relaunch that exact AVD once with the established non-wiping/no-snapshot-load method, confirm the minimum readiness gate, and continue the same ARM64 startup-control task rather than creating another standalone reconciliation phase.

Do not create or use a replacement AVD. Do not wipe userdata or load a snapshot.

Current source attribution and execution-gate state remain unchanged.

Canonical defect count delta: 0.

INDEPENDENT EXECUTION: NOT EXECUTED
