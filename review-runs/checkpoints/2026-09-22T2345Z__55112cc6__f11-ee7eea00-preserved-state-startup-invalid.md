# F11 ee7eea00 direct-startup diagnostic invalid — preserved installed state unavailable

Date: 2026-09-23

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported relation:
7 ahead / 0 behind

No push occurred.

Prior canonical review:
`6b22ded06e04ae1e2878484103b3ab32c6e3e546`

## Direct-startup diagnostic result

Reported device:
- emulator-5554;
- API 36;
- x86_64;
- online.

Required preserved package precondition failed:
- `com.ireum.ytdl` not installed;
- `pm path com.ireum.ytdl` produced no package path;
- package manager could not find `com.ireum.ytdl`;
- no launcher resolved;
- no app PID existed.

Therefore no force-stop and no launcher start were performed.

Result:

**DIRECT_STARTUP_DIAGNOSTIC_INVALID**

This is the correct stop disposition.

## Important state consequence

The diagnostic was specifically authorized to use the existing installed candidate and its preserved app/data state.

That preserved installed state no longer exists.

Reinstalling the APK cannot be described as restoring the prior diagnostic state:
- a new install creates a new installation/data state unless separately restored from an exact preserved data snapshot;
- no exact preserved app-data snapshot for the original 09:42 failure has been established;
- the original durable Restore / scheduler-transition / WorkManager state therefore remains unavailable.

Accordingly:

**PRESERVED-STATE DIRECT STARTUP CANNOT NOW BE COMPLETED FROM THE RECORDED DEVICE STATE.**

Do not retry the old direct-startup prompt after reinstall and call it the same diagnostic.

Canonical defect-count delta: 0.

## What a fresh install can and cannot prove

A separately authorized fresh-install startup control may still provide useful evidence on the exact local candidate.

If exact ee7eea00 source/build provenance is established, a clean/fresh install can answer:

- does the candidate deterministically fail during ordinary Application startup from a clean installation state?

It cannot answer:

- whether the original 09:42 crash was caused by the lost prior durable app state;
- whether a specific scheduler-transition/Restore/WorkManager debt present in the original installation caused the crash;
- whether the original instrumentation interaction alone caused the crash.

Therefore any such next diagnostic must be labeled:

**FRESH-INSTALL DIRECT STARTUP CONTROL**

not preserved-state recovery.

## Current startup disposition

Keep:

**A3 — STARTUP PATH INVOLVED BUT NO SPECIFIC ROOT PROVEN.**

No production source correction is authorized.

The original WorkManagerHandoffProductionTest remains:
- FAIL BEFORE EXECUTION;
- zero tests;
- not a semantic PASS/FAIL;
- unchanged-tree semantic rerun not yet authorized.

## Current broad closure scope

No change to prior disposition:
- historical method-level 307/16 manifest remains unrecoverable;
- current intended closure scope remains historical 16-class whole-class anchor plus scheduler-settings transition class;
- expected local candidate scope remains 17 classes / 316 identities subject to later authoritative exact-source verification;
- broad execution remains unauthorized.

## Safe next action

A separate fresh-install control may be authorized if desired.

Requirements:
1. exact local HEAD still ee7eea00;
2. no source/test/config changes;
3. fresh-build or existing APK provenance must be tied exactly to ee7eea00;
4. install app APK only; test APK is not needed;
5. record that installation creates a NEW clean app state;
6. resolve launcher from package metadata;
7. start launcher exactly once;
8. bounded capture only;
9. no instrumentation;
10. no semantic tests;
11. no retries;
12. do not interpret a clean-startup PASS as proof that the original failure was infrastructure.

Possible result classes:
- `FRESH_INSTALL_STARTUP_COMPLETES`;
- `FRESH_INSTALL_STARTUP_FAILS_OR_HANGS`;
- `FRESH_INSTALL_STARTUP_CONTROL_INVALID`.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
