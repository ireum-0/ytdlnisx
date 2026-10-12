# Independent stopped-wave review — BG-01/BG-02 V5 native ADB process-start denial

date: 2026-10-12
checkpoint_kind: IMPLEMENTATION_AGENT_STOP_BOUNDARY_INDEPENDENT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: 5ec97225d4d461897868447ed2eaf1f09d017845
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_v4_checkpoint: review-runs/checkpoints/2026-10-12__db339e1__fmt-bg01-bg02-v4-unprobed-command-stop-independent-review.md
governing_v4_checkpoint_blob: 70802ab1cbe09b0fe8d83c55ddfec56074e6b491
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
execution_v5_prompt_blob: 4854d6a7f0d88883939157cd33f060686805c19e
immutable_v5_report_path: ytdlnisx/reports/2026-10-12__FMT_BG01_BG02__fmt-bg01-bg02-v5-20261012-a76bdaf7__FULL_REPORT.md
immutable_v5_report_blob: 45a4c2406163959729c15054706d044ef765ce06
private_report_only_commit: 8c7dec17fc627035b9d00e3e596f8bfa06febd91
private_report_archive_status: VERIFIED
private_archive_parent: 7baeee02508372035438c82f1dab066756af3603
implementation_remote_source_changed: NO
source_commit_and_push: NONE
preserved_candidate_status: UNCOMMITTED_LOCAL_ONLY_17_FILES_AGENT_REPORTED_UNCHANGED
candidate_compiled_file_manifest_sha256: b8ef5fb31ebdc77141fc38e4ea0e90e86cd61d4eece5198ae95532be02171322
candidate_index_sha256: 177c240e43c04fdd1a88d6c649d8dc5967aeda6147dd2670b5a7aae4f5c9e37b
original_command_health_probe: PASS_AGENT_EXECUTED
apk_packaging_and_installation: PASS_AGENT_EXECUTED
first_instrumentation_method: NOT_EXECUTED_INFRASTRUCTURE_BLOCKED
android_executed_methods: 0
android_pass: 0
android_fail: 0
android_skip: 0
android_remaining_methods_not_reached: 104
android_required_methods_total: 105
native_controller_exit: 1
powershell_controller_pid: 23620
adb_child_pid: NOT_RETURNED
root_cause_winerror5: NOT_VERIFIED
kollus_causation: NOT_VERIFIED
new_evidence_file_audit: 248_FILES_ZERO_MISMATCH_AGENT_REPORTED
historical_evidence_file_audit: 1037_FILES_ZERO_MISMATCH_AGENT_REPORTED
current_bg01_status: OPEN
current_bg02_status: OPEN
canonical_root_delta: 0
active_remediation_confirmed_open_p2: 6
active_remediation_ambiguous_p2: 0
active_remediation_unresolved_p2: 6
repository_wide_verdict: NOT_CLEAN
independent_execution: NOT_EXECUTED

## 1. Independently verified remote authority

GitHub `checkpoint/pre-baseline-review` remains exactly `db339e1e90392cfd994e2d42bca7ce88afb3be21`. Private `ytdlnisx-review` advanced by one strictly forward commit `7baeee02508372035438c82f1dab066756af3603` -> `8c7dec17fc627035b9d00e3e596f8bfa06febd91`, adding exactly the immutable V5 Full Report, blob `45a4c2406163959729c15054706d044ef765ce06`. The report and current protocol/prompt identities were verified against GitHub. Implementation-agent local Windows, APK, and 1,285 evidence-file hash audits are reported evidence, not independently repeated tests or filesystem checks.

## 2. Exact error distinction, not an inferred Windows root cause

V4 was stopped before any current command probe. V5 performed exactly one ordinary original-tool PowerShell health probe and returned expected marker, exit 0, original account and actual controller PID17188. Consequently the older V3 outer-tool failure to create PowerShell is *not* a presently proven continuing global command-tool outage.

V5 continued in the same run. Both ordinary Gradle packaging invocations returned exit0; the first produced a wrong ABI by default, and the changed existing debug-only `-Pytdlnisx.testAbi=x86_64` argument produced target x86_64 application/test APKs. Candidate package hashes (agent-measured) `0cf275025abafd65037aec1cd8adfa6a4f8ca61d95a55aefd042353d063bcda7` and `49573f444934366df232cd2d2b00c118b505283b9958e81890df3d52fcd4af25` matched device-installed APK readbacks, as reported. This was a bounded invocation-argument correction, not a production or test source change. Normal protected local baseline APK files remained unmodified; however the *authorized installed emulator packages did change*, and later work must preserve or independently account for that now-current device state.

Before the target method, a read-only logcat call succeeded from the native controller (child PID23188, exit0). The first instrumentation command requested SDK adb via `ProcessStartInfo` and PowerShell/.NET `Process.StartWithCreateProcess`, original medium-integrity `dh2` token, ordinary device and unchanged SDK adb.exe identity, but its `Start()` raised Win32 NativeErrorCode5 (`Access is denied`). PowerShell controller PID23620 exited1, `adb` child PID/handle/native exit was NOT_RETURNED, and runner stdout/stderr had zero bytes. This establishes the **specific ADB instrumentation native child-launch boundary**, not an Android semantic failure, not an asserted negative about all possible internal transient processes, and not causality from Kollus. A successful logcat ADB child immediately prior makes `adb.exe` globally unable to launch an unsupported conclusion. It also makes any proof of a permanently restored command path premature.

The failed target was `FormatBatchHandoffProductionWiringTest#realProducerCapturesAAndBButLeavesLateCProcessing`, classified `NOT_EXECUTED_INFRASTRUCTURE_BLOCKED`. Exactly 104 later methods `NOT_REACHED`. Zero runner methods executed, so there are zero PASS, zero FAIL, zero SKIP, and no Room/carrier/WorkInfo/generation final-effect observation. The single infrastructure ERROR is *not* an Android assertion FAIL. No repeated identical attempt, alternate launcher, security-policy alteration or source commit/push followed. `ROOT_CAUSE_WINERROR5=NOT_VERIFIED` and `KOLLUS_CAUSATION=NOT_VERIFIED` remain mandatory.

## 3. Exact remote semantics and root disposition

Independent exact GitHub source on pinned `db339e1` still has `DownloadViewModel.continueUpdatingFormatsOnBackground` obtaining the live all-Processing IDs, selected or all, and publishing `WorkManager.enqueueUniqueWork` using a process-local/timestamp-style owner without the new atomic durable format-batch contract. `DownloadDao.updateProcessingtoSavedStatus` remains the old status-wide update; the existing related call chain does not substitute frozen captured exact-ID conditional authority. Thus `BUG-FORMAT-BG-01=OPEN P2` and `BUG-FORMAT-BG-02=OPEN P2` at this authoritative remote SHA, with no fresh remote patch and no root-count delta. Proposed local candidate's source descriptions, changed schema and Android-test source remain AGENT_REPORTED_LOCAL_ONLY and were not independently inspected/reproduced as exact source.

Other active roots BG-03, BG-04, BG-05 and INCOGNITO-01 remain OPEN P2. Independently CLOSED scheduler WINDOW-01 and RESTORE-01 on this exact SHA stay closed, but compatibility of unpublished candidate modifications with shared WorkManager/Restore/scheduler contracts is NOT_VERIFIED. All 105 mandatory Android methods still lack valid execution evidence, including 63/61 old-writer Room migration/replay, format-batch exact end-to-end effects and triggered shared effects. The six V3 JVM dirty-candidate PASS are historical helper evidence only. No remotely attributable final committed-SHA gate, no repo CLEAN advancement, no new production root inferred from the Windows infrastructure blocker.

## 4. Next feasible bounded action — review-only recommendation, not authorization

The actual failing interface is `.NET Process.StartWithCreateProcess` of SDK adb with `shell am instrument` arguments while the same controller previously successfully started a read-only adb logcat command. An appropriate **independent diagnosis** would compare the already captured **successful vs failed** process-creation parameters, same executable path/hash, working directory, token, environment, argument bytes, redirected streams and relevant contemporaneous OS policy/evidence if already accessible without changing system state. Investigate whether the original route has since materially changed before any new process-launch attempt. Do not infer Kollus from temporal proximity, weaken Windows security, add exclusions, elevate, switch tools or retry the identical denied instrumentation unchanged. Any future diagnostic/continuation requiring tests must be **separately authorized under current governance**, preserve the installed candidate device identities and all source/evidence artifacts, and gate a future valid test on substantiated host condition or a reviewed legitimate invocation correction. Do not publish unverified local source, waive 105 methods or declare an exact final-SHA closure.

## 5. Workflow and routing boundary

This user-reported result requests an independent infrastructure STOP review **before any later continuation authorization**. Review-only authority supports this checkpoint but does not authorize rewriting `NEXT_CHAT.md` to relaunch or replacing/persisting a new implementation prompt under Protocol Section8.2. The current private execution capsule still says V5 `PROMPT_EXECUTION_STATUS=PERSISTED_NOT_STARTED` despite the independently archived V5 Full Report; that lifecycle field is stale and makes the apparent V5 launch-ready state **substantively inconsistent**. It must not be used to relaunch the already executed V5 prompt. Preserve the obsolete state unchanged in this review-only pass; its owner/prompt routing requires separately authorized reconciliation prior to any future bootstrap. No bootstrap is emitted from an invalid launch-ready state.

INDEPENDENT EXECUTION: NOT EXECUTED
