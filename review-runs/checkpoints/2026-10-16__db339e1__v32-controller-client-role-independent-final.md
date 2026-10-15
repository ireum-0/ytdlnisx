# V32 independent review — client/server cardinality is not a duplicate-server defect

date: 2026-10-16 KST
checkpoint_kind: COMPLETED_V32_STOP_RULE_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_start_parent: 4ca7bff460e0a5fe23d258dd9e03e9f01f9e032f
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
applicable_protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
v32_full_report_commit: f9180ac1b8872e6db4235a84318ee7a3ecb4b2bb
v32_full_report_blob: 8f00b28056fe7f3a9e39a82608e71f8be92e909f
v32_prompt_blob: 9a3b9df276140928a4f25852104f43a03ff4ee48
v32_result: STOP_CONTROLLER_CLIENT_ROLE_NOT_VERIFIED_G0
protected_local_only_X: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
canonical_root_count_delta: 0
active_P2_unresolved: 6
clean_basis_advancement: NONE
g3_executed: 0_OF_6
g5_executed: 0_OF_197
independent_device_execution: NOT_EXECUTED

## Independent verdict

GitHub private branch advanced normally from 92bbd132f397c04cee05e5d1802f93c3cd862be3 to f9180ac1b8872e6db4235a84318ee7a3ecb4b2bb: exactly one append-only V32 Full Report. Its Git blob 8f00b28056fe7f3a9e39a82608e71f8be92e909f was fetched for independent reading. Current implementation HEAD remains db339e1e90392cfd994e2d42bca7ce88afb3be21, review prior tip 4ca7bff, and pinned protocol/plan/ledger unchanged. No remote production source update; local only X is preserved and remains unreviewed as published code.

V32's preserved primary and Toolhelp32 observations each reported two executable `adb.exe` PIDs: 6108 server listening 127.0.0.1:5037, and 21128 client connecting 127.0.0.1:56649 -> :5037, one emulator PID21196 and its one QEMU child PID4404. All observed executable paths, owner SID and session matched. The later single CIM query for client21128 and parent16308 returned zero records because both already exited; it does not determine their earlier invocation or prove lack of interference. No duplicate-server listener was observed. A raw-count assertion of exactly ONE adb.exe process conflates ADB service with transient ADB client and is an unnecessarily broad G0 admission predicate. The true service-authority predicate is ONE authorized server owning the configured 5037 endpoint, a coherent unique AVD/controller pair and absence of material competing device mutation/instrumentation at the actual effect boundary. The raw extra client remains an observed event; its historical command line is NOT_VERIFIED and must not be invented or retroactively called harmless. 

V32 stopped according to its as-written strict controller and unresolved-role gate without risk-bearing device effects, retaining the first failure evidence and sealed protected state. It is not a production semantic FAIL; no Android tests ran. V32 Full Report's full preservation checks and APK/fixture provenance are implementation-agent evidence, not reviewer-run Windows checks. Six active P2 roots remain open. The prior owner clean 8G guest evidence remains historical user observation; V32 never reread current device.

## Narrow correction to unblock real verification

Next authorized verification-wave G0 may read current host controller and process commandline **while a candidate is live**, classifying each adb.exe by endpoint role instead of requiring `adb.exe total=1`. Preserve original raw process/port/SID snapshots and first failure; do not modify/readjust older immutable V32 observations to manufacture a pass. Require exactly one authorized listening daemon on 5037 and exactly one intended emulator/QEMU for target Medium_Phone_API_36_1, relevant paths/owners, target serial/name/API/ABI/user0/boot, no competing instrumentation and no active unauthorized/interfering concurrent tool at the risk-bearing install/test boundary. A transient ADB CLI client may be present only if live role can be established, OR it has finished and a fresh coherent endpoint/guest/no-interference check at the current effect boundary passes; no invented retroactive command-line proof is required for a departed client absent evidence of conflict. If competing client persists and could mutate device state, unknown ownership, second daemon/listener, controller mismatch or evidence of interference, STOP with raw evidence. Bounded current-state recheck is a NEW admitted diagnostic with a materially narrower correct predicate, not a favorable-snapshot replay or unchanged failed semantic test. Do not kill/restart ADB/emulator, wipe AVD or change host permissions. 

After corrected admission, promptly follow the already authorized same-wave chain: existing X APK pair; six genuine historical 61/63/ORIGINAL64 Room->65 migrated production methods with 6/6 actual PASS; only then normal FF of existing unmodified local commit X onto exact compatible live db339e1; then same-run 19-class/197-method Android test inventory at exact X. All commits/history/governance/protected evidence/fixtures unchanged, no source/test/config edits. A real test failure remains its own stop or protocol-limited same-root continuation; report all statuses rather than asserting CLEAN. On any STOP publish immutable append-only private Full Report; if safe publication interface fails technically, distinguish unknown write from explicit platform policy refusal and never evade a policy denial through alternate commands. Owner-requested PowerShell command or .ps1 fallback for ordinary Git technical failure can be provided only after exact authorized file/HEAD/idempotency checks, never as bypass for refusal.

## Review retrospective

The session consumed a full wave at a controller **process family cardinality** check even though the port evidence already distinguished ONE listener from an additional client. Capturing client commandline in the FIRST simultaneous live snapshot (if material) and binding uniqueness to daemon/endpoint instead of filename count would have avoided treating ordinary short-lived CLI activity as duplicate-server proof. V30 count uncertainty and V31 speculative 16G reset budget were already retired. Further broad pre-reset feasibility gates must not be replayed. No premature semantic closure or root-count change.

INDEPENDENT VERDICT=NONPRODUCTION_CONTROLLER_ADMISSION_PRECONDITION_TOO_BROAD; HISTORICAL_CLIENT_COMMAND_NOT_VERIFIED; V32_STOP_WITHIN_AS_WRITTEN_GATE; G3_G4_G5_NOT_REACHED.
