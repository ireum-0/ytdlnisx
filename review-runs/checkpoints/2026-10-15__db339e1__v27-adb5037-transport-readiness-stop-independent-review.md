# V27 independent review: existing AVD missing ADB transport and port 5037 protocol faults

date: 2026-10-15 KST
checkpoint_kind: COMPLETED_V27_IMPLEMENTATION_STOP_RECONCILIATION
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_start_parent: 9ad8d8ec864e5ebb818a5e7a29e9a9d839e0483c
review_basis: EXACT_REMOTE_DB339E1_LOCAL_COMMITTED_X_UNPUBLISHED
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
applicable_protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
v27_full_report_commit: a6dec88f82a0145fcc14295d35f8552f9fe7de6b
v27_full_report_blob: 08cea0a42313e4a1d61b64a477f80c65bb72d2bb
v27_full_report_path: ytdlnisx/reports/2026-10-15__FMT_BG01_BG05_V27_EXISTING_AVD_EXACT_SHA__fmt-bg01-bg05-v27-20261015-f20f48ff__FULL_REPORT.md
prior_v27_review_checkpoint: review-runs/checkpoints/2026-10-15__db339e1__v26-g3-historical-avd-v27-entry.md
local_only_X: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
local_only_X_parent: db339e1e90392cfd994e2d42bca7ce88afb3be21
local_only_X_tree: f2adb2f34ee679517df672f8ecfea7fc889c7c11
canonical_root_count_delta: 0
active_P2_unresolved: 6
published_implementation_change: NONE
clean_basis_advancement: NONE
independent_source_review_of_X: NOT_VERIFIED
independent_execution: NOT_EXECUTED

## Independent verdict and historical comparison

Verified GitHub immutable Full Report at exact blob and fresh authoritative implementation HEAD db339e1. The prior local-only X is not available as published implementation source, so its 41 committed local paths and 123/123 JVM tests remain evidence reported by the agent, not a reviewer-executed or independently GitHub-reviewed source fix. The current real Android G3/G5 execution gate remains NOT_REACHED. V27 completed its material STOP at G1 as instructed; there was no publication, install, source edit, additional local commit or device test.

V27's original-SDK adb.exe devices -l initial command exited0, started ADB server PID6108 at TCP127.0.0.1:5037; source AVD Medium_Phone_API_36.1, emulator executable and x86_64/API36 config hashes matched prior identity. One owner-bounded emulator.exe -avd Medium_Phone_API_36.1 -no-snapshot-load launch at 2026-10-15T11:46:30.5117438Z returned PID964, with related qemu PID21456 later observed. Emulator exit was NOT observed, so there is no evidence of emulator exit0 or definite crash. The 480-second deadline yielded 69 adb devices -l poll exits0, no listed emulator-5554 or any OFFLINE entry. This is NO ADB TRANSPORT, not the V7 historical observed emulator-5554 OFFLINE state. The recorder itself exited0 successfully recording a STOP; it did not mean device readiness PASS.

Immune stop stdout snapshot shows emulator log opengl32sw load failure and software OpenGL fallback, then repeated 'adb protocol fault (couldn't read status length)' and 'Unable to connect to adb daemon on port: 5037'. The presence and ordering of these lines do not prove graphics failure caused port5037 protocol failure, nor that port ownership changed, a firewall/security product interfered, ADB daemon crashed, network loopback rejected, or a host/guest protocol mismatch. No Windows Win32 error5, nonzero ADB client exit, absolute DLL resolution evidence or emulator process exit was observed in this wave. Treat source/root cause as NOT_VERIFIED, separate from earlier V5/V6 Win32 error5 and V7 300-second OFFLINE timeout. V8 once reached boot_completed with a 66-second clean cold boot; historical success does not prove present health. Previous X package installation cannot be presumed from mere AVD name because V9 proved baseline APK pair after earlier candidate observations.

The agent reported exact original local X/clean index, original schema64 and generated65 checks, original 63->64 migration bytes, signed app/test APK pair hashes, protected worktrees, prior evidence, historical artifacts and no mutation to those sources. A normal AVD boot itself can update guest/AVD runtime state; that state is NOT asserted byte-identical. Original stdout/stderr may be live, while sealed stop snapshots and new immutable manifest were retained locally. No kill-server, emulator shutdown/reboot beyond the single launch, upgrade, install, wipe/reset/clear, security/ACL/sandbox alteration, process termination or second launch occurred. These are agent report preservation observations, not independently physically rerun by reviewer.

## Canonical findings and gate disposition

Six already-canonical P2 findings BG01-BG05 and INCOGNITO01 remain unresolved OPEN at the currently reviewed older published implementation, INCOGNITO01 outside format scope. This infrastructure event establishes no NEW production correctness root, no closure and no basis advancement. Do not elevate a suspected Windows graphics/ADB cause into a confirmed app production defect.

G0 reported protection/identity PASS for reuse from V26. G1 FAILED its readiness predicate and STOPPED before the actual device effect boundary. G2 live package/device DB/WAL/SHM/Restore proof NOT_REACHED, G3 6 authentic AndroidJUnit4 original61/63/64->65 migration tests NOT_REACHED (0 executed), G4 source push NOT_REACHED (remote db339e1), G5 19 governed Android classes/197 inventoried methods NOT_REACHED (0 executed). No device tests PASS; no exact-final-SHA closure, CLOSED, CLEAN, baseline or release authorization. A method inventory is not an execution log.

## Single bounded next external prerequisite (recommendation only, not execution authorization)

Do NOT blindly retry the same Cold Boot, restart ADB, run kill-server, recreate/wipe the AVD or disable security controls. The next useful safely limited action after a SEPARATELY AUTHORIZED host diagnostic is a read-only observation of CURRENT 127.0.0.1:5037 listener PID/executable/socket state, current emulator/QEMU PID and serial port/controller ownership (5554/5555), immutable emulator/ADB logs around protocol fault and any OS-observable exit/exception status; determine whether the ADB server is still alive/listening, whether emulator talks to the same 5037 server, and whether guest/QEMU reached early boot. Preserve live log originals and stopped-state snapshots. No causal claim without discriminating provenance. If read-only host facts prove a routine reversible recovery is safe and in-envelope, a separately authorized narrow task may attempt it ONCE with exact protected-state guard; if an unapproved process/security/device mutation is required, stop and identify owner authorization. Until current runtime compatibility, healthy exact X guest, DB/WAL/SHM/Restore and APK identity are proven, no app installation or Android migration methods, no normal source publication. Continue protected local X and exact-X APK preservation, not new source rewrites.

This completion-review checkpoint is REVIEW ONLY: it does not modify NEXT_CHAT.md prompt lifecycle, active implementation routing or executable agent permissions. The old V27 PERSISTED_NOT_STARTED capsule is superseded as factual execution history by the newer immutable report and this checkpoint. Do not interpret the stale old capsule as permission to relaunch V27; do not use reviewer-only fact reconciliation to create V28.

INDEPENDENT EXECUTION: NOT EXECUTED
