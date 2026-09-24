# Observe generation/ownership — same AVD relaunch produced no ADB transport

Date: 2026-09-24 +09:00

Implementation branch:
`checkpoint/pre-baseline-review`

Remote implementation HEAD:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Local committed candidate to preserve:
`aaf89b15224b814470c2efab548f4c3e40003de7`

Candidate parent:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Prior review checkpoint:
`6e2e652604ef51265f8861cdce238d92c0a61141`

## Verdict

**OBSERVE_GENERATION_EXACT_SHA_AVD_STARTUP_TRANSPORT_DIAGNOSTIC_REQUIRED**

No implementation push occurred.

Canonical defect delta: 0.

Canonical totals remain:
- P0 = 1
- P1 = 0
- P2 = 23
- Overall = NOT_CLEAN

CLEAN_REVIEW_BASIS remains:
`ee7eea001462b77e88a201ed2f26c2385048d421`

## Confirmed infrastructure behavior

The exact committed candidate remains unchanged and the required first
post-commit semantic gate still has not executed.

Observed recovery sequence:
1. existing emulator PID 8588 / QEMU PID 17676 were present;
2. `adb devices` exposed no device;
3. one bounded `adb kill-server` / `adb start-server` recovery was performed;
4. no transport appeared within the two-minute poll;
5. only the recorded stale emulator/QEMU PIDs were terminated;
6. the same AVD `Medium_Phone_API_36.1` was launched once with:
   `emulator.exe -avd Medium_Phone_API_36.1 -no-snapshot-load`;
7. no `-wipe-data`, no replacement device, and no alternate AVD was used;
8. the new emulator process (reported PID 3036) remained present for the full
   five-minute readiness window;
9. ADB still exposed no device, so boot/package-manager readiness could not be
   established.

The user also observed that the emulator did not appear visibly started.

This evidence is more specific than a transient ADB-server interruption:
the same AVD can create an emulator host process while failing to reach the
normal ADB-visible boot path.

No app/package/Gradle/UTP/instrumentation operation occurred in this attempt.
No semantic test result exists.

## Evidence

Reported evidence note:

`app/build/observe-generation-evidence/postcommit-avd-transport-unavailable-20260924.md`

Prior JVM crash/replay evidence remains preserved.

## Next diagnostic boundary

Do not rerun app verification yet.

Do not alter source/test/config files.
Do not amend/recommit/reset candidate `aaf89b15...`.
Do not wipe or recreate the AVD.

The next action is a bounded host-side emulator startup diagnosis.

First inspect the current failed/reachable emulator state without mutation:
- exact PID/process tree and command line;
- whether the process remains alive;
- whether it owns/listens on expected emulator console/ADB-related local ports;
- current `adb devices -l`;
- emulator/AVD launch or crash diagnostics already produced on disk;
- `emulator -accel-check`;
- available host memory at diagnosis time;
- AVD configuration facts needed to identify accelerator/graphics/system-image
  startup failures, without modifying the AVD.

Do not inventory unrelated host state.

If another launch is needed to obtain evidence:
- terminate only the exact failed emulator instance;
- relaunch the same `Medium_Phone_API_36.1`;
- retain `-no-snapshot-load`;
- add verbose diagnostic logging and capture stdout/stderr under ignored
  `app/build/observe-generation-evidence/`;
- no wipe;
- no second AVD;
- no app/package mutation;
- no source change.

Use the resulting evidence to classify the earliest concrete startup boundary,
for example:
- emulator host process exits/crashes;
- acceleration/hypervisor initialization failure;
- graphics/display backend initialization failure;
- AVD/system-image/config startup failure;
- console transport exists but guest/adbd never becomes available;
- ADB server/port registration conflict;
- another exact host-side cause established by logs.

Do not guess the cause from the mere absence of ADB.

If a narrow, non-destructive recovery is directly proven by the diagnostic
evidence, it may be applied in the same run and the same AVD may be relaunched
once under that evidence-based correction. Do not make speculative global
machine/JDK/SDK/AVD configuration changes.

Once the same AVD reaches normal ADB/boot/package-manager readiness:
- immediately resume the existing exact-SHA verification;
- first run
  `BackupSettingsProductionWiringTest#observeSourceBackupOmitsDestinationGenerationAndLegacyPayloadGetsLocalGeneration`;
- then continue the remaining final-SHA gates if it passes.

A recoverable emulator infrastructure fix does not require a new implementation
commit and does not invalidate candidate `aaf89b15...`.

## Preservation

Continue preserving:
- candidate `aaf89b15...`;
- protected primary workspace and HEAD;
- baseline;
- three protected stash objects;
- ignored/uncommitted `local.properties`;
- all existing Observe build evidence;
- JVM crash/replay logs;
- new emulator diagnostic logs.

INDEPENDENT EXECUTION: NOT EXECUTED
