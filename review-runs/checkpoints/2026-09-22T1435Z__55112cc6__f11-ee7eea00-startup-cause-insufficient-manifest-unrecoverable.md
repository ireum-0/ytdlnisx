# F11 ee7eea00 evidence stop — startup cause unavailable / historical broad manifest unrecoverable

Date: 2026-09-22

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported parent:
`87b05af9da2d51717594cebddcdaf32be93a4621`

Reported relation:
7 ahead / 0 behind

No push occurred.

Governing review before this classification:
`b76b2f2eefc13fbe733d60b5a02f4defe599829d`

## Preserved exact-SHA evidence

Reported PASS on exact local candidate ee7eea00:
- production Kotlin compile;
- androidTest Kotlin compile;
- focused BackupReset race 1/1;
- Cleanup acceptance 1/1;
- targeted Cleanup regressions 8/8;
- BackupReset 26/26;
- Cleanup 69/69;
- Scheduler transition 9/9;
- Scheduler external authority 12/12;
- Real WorkManager handoff 3/3.

First later stop:
- class: `com.ireum.ytdl.work.WorkManagerHandoffProductionTest`;
- result: FAIL BEFORE EXECUTION;
- executed: 0;
- instrumentation startup crash.

Remaining neighboring/frozen/broad gates were correctly NOT EXECUTED.

## Invocation integrity

Reported preserved Gradle command and UTP plan agree on:
- class filter `com.ireum.ytdl.work.WorkManagerHandoffProductionTest`;
- app/target package `com.ireum.ytdl`;
- test package `com.ireum.ytdl.test`;
- runner `androidx.test.runner.AndroidJUnitRunner`.

Disposition:
`INVOCATION_INTEGRITY = PASS`.

## Startup crash evidence

Preserved UTP result says `Process crashed` with zero tests.

The target app process reportedly starts but does not complete startup.

The preserved crash stack is ART signal-catcher/runtime abort machinery, not the original failure cause. No captured frame reaches:
- App.onCreate;
- Restore recovery;
- WorkManager initialization;
- CleanupScheduleCoordinator;
- CleanUpLeftoverDownloads;
- RestoreTransactionCoordinator;
- another candidate production frame.

APK installation is reported successful.

The durable app state at the crash time is not established.

## Classification

**D — INSUFFICIENT EVIDENCE.**

The zero-test result is not a semantic PASS or FAIL.

Current evidence cannot establish:
- external execution infrastructure failure;
- test-harness/invocation failure;
- candidate production startup failure.

Therefore the same WorkManagerHandoffProductionTest must NOT be rerun unchanged merely to seek a valid result.

Canonical defect-count delta: 0.

## Diagnostic evidence still worth recovering

Before any semantic rerun authorization, attempt non-source, non-semantic recovery of the original process-exit cause from Android-maintained historical diagnostics, if still available.

Useful nondestructive evidence includes:
- ActivityManager historical process exit info for `com.ireum.ytdl`;
- DropBox app_crash / system_app_crash / data_app_crash entries matching the exact timestamp;
- retained ANR/trace metadata matching the process/timestamp;
- crash-buffer entries not already copied;
- UTP device-controller / runner lifecycle diagnostics.

Do not clear data, reinstall, restart merely to generate new evidence, or rerun the failed class as part of this diagnostic step.

If these historical records identify a production startup stack, stop for source classification.
If they conclusively identify device/runner/system failure, an unchanged-tree continuation may then be separately authorized under §16.2.
If they remain insufficient, keep the gate blocked.

## Historical 307 / 16-class manifest

Reported local recovery result:

`HISTORICAL 307/16 MANIFEST: NOT RECOVERABLE FROM RETAINED EVIDENCE`

The strongest retained artifact is a Codex session transcript that preserves a 16-class filter list, but not the complete historical method/discovery manifest.

No retained successful batch XML/report union proves all original intended identities.

Therefore do not claim exact historical identity recovery.

## New current-equivalent manifest path

The governing broad checkpoint permits an exact current equivalent.

A later evidence-only step may construct a **NEW CURRENT-EQUIVALENT MANIFEST**, clearly distinct from the unrecoverable historical manifest, using:

1. the retained exact 16-class filter list as historical scope evidence;
2. the exact current candidate source at ee7eea00 for current test identities;
3. exact enumeration of every test identity in those whole selected classes;
4. explicit count/duplicate reconciliation;
5. confirmation that the seventh candidate did not add/remove test identities;
6. governing fifth-wave semantic surface and material-change coverage.

Do NOT force the reconstructed current count to 307.
Do NOT call the new manifest historical.
Do NOT execute broad tests until the new manifest is frozen and independently accepted.

## Current next action

No source/test edit.

Perform two evidence-only activities:

A. recover Android historical process-exit evidence for the zero-test startup crash without rerunning the failed class;

B. construct a new current-equivalent broad-F11 manifest from the retained 16-class filter scope plus exact current candidate identities, explicitly labeled as a new reconstruction.

After A:
- production startup cause => stop for independent source classification;
- conclusive infrastructure/harness cause => controlled unchanged-tree WorkManager handoff continuation may be separately authorized;
- still insufficient => execution gate remains blocked.

After B:
- record exact current classes/identities/count/duplicates;
- do not execute until independent acceptance.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
