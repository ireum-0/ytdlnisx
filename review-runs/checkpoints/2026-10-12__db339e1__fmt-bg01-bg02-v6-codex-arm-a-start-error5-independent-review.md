# Independent stopped-wave review — BG-01/BG-02 V6 Codex Arm A native launch error5

date: 2026-10-12
checkpoint_kind: IMPLEMENTATION_AGENT_DIAGNOSTIC_STOP_BOUNDARY_INDEPENDENT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: ca0dc30fe8ff22ffa84d296f09368e976520ff71
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
governing_v5_review_checkpoint: review-runs/checkpoints/2026-10-12__db339e1__fmt-bg01-bg02-v5-adb-native-process-start-infrastructure-stop-review.md
governing_v5_review_checkpoint_blob: 2859dca3c69477518137d4550a1a1b4e8e947b7f
v6_execution_prompt_path: ytdlnisx/prompts/2026-10-12_GPT61_SOL_FMT_BG01_BG02_V6_CODEX_AB_NO_PREFLIGHT_VS_PREFLIGHT_DIAGNOSTIC.md
v6_execution_prompt_blob: 0651c23839aac3a98cf83859ce6ae0acf5dfe57f
immutable_v6_report_path: ytdlnisx/reports/2026-10-12__FMT_BG01_BG02_AB_DIAG__fmt-bg01-bg02-v6-ab-20261012-983ab044__FULL_REPORT.md
immutable_v6_report_blob: 92da7fc20d9f565cca78c48cb241f255075f6c2d
private_v6_report_only_commit: f02639741b8cf721f1878f6a50632e861905d5d0
private_v6_report_archive_status: VERIFIED_BY_GITHUB_BLOB_AND_FORWARD_SINGLE_FILE_DELTA
original_private_handoff_blob: 52e4b9203028f181968dccb2c90cba137d6e25f7
implementation_remote_source_changed: NO
implementation_source_commit_push: NONE
local_draft_status: UNCOMMITTED_17_FILE_PROTECTED_CANDIDATE_AGENT_REPORTED_UNCHANGED
local_candidate_manifest_sha256: b8ef5fb31ebdc77141fc38e4ea0e90e86cd61d4eece5198ae95532be02171322
local_candidate_index_sha256: 177c240e43c04fdd1a88d6c649d8dc5967aeda6147dd2670b5a7aae4f5c9e37b
arm_a: START_ERROR_5
arm_b: NOT_REACHED_A_START_ERROR_5
requested_instrumentation_launch_attempts: 1
verified_android_methods_executed: 0
android_pass: 0
android_fail: 0
android_skip: 0
original_method: com.ireum.ytdl.work.FormatBatchHandoffProductionWiringTest#realProducerCapturesAAndBButLeavesLateCProcessing
original_method_status: NOT_EXECUTED_INFRASTRUCTURE_BLOCKED
native_controller_exit: 1
powershell_controller_pid: 3104
parent_process_pid: 21468
parent_process_name: codex.exe
adb_child_pid: NOT_RETURNED
adb_child_exit: NOT_RETURNED
root_cause_winerror5: NOT_VERIFIED
preflight_causation: NOT_VERIFIED
kollus_or_other_security_component_causation: NOT_VERIFIED
device_live_postfailure_state: NOT_VERIFIED
new_evidence_file_audit: 25_FILES_ZERO_MISMATCH_AGENT_REPORTED
historical_evidence_file_audit: 1285_ENTRIES_ZERO_MISMATCH_AGENT_REPORTED
current_bug_format_bg01_status: OPEN
current_bug_format_bg02_status: OPEN
canonical_root_delta: 0
active_remediation_confirmed_open_p2: 6
active_remediation_ambiguous_p2: 0
active_remediation_unresolved_p2: 6
repository_wide_verdict: NOT_CLEAN
independent_execution: NOT_EXECUTED

## 1. Independent GitHub authority and archive check

The reviewer fetched the live branches and exact files. The pinned implementation branch `ireum-0/ytdlnisx:checkpoint/pre-baseline-review` remains `db339e1e90392cfd994e2d42bca7ce88afb3be21`. Review `review/remediation` was `ca0dc30fe8ff22ffa84d296f09368e976520ff71` on this review's initial bounded refresh. Plan and ledger branches remain `8491528b730abea17de22013bca4288e1549a39e` and `50f43b4710a0865fd2a79d779186ce252cc9ff7f`, respectively. Applicable protocol blob remains `4107f1760d18f4a273ea25fb212b014701bc3bda` and V6 persisted prompt blob remains `0651c23839aac3a98cf83859ce6ae0acf5dfe57f`.

The private branch advanced normally by exactly one forward commit `7dcc66fe1a13b27552d41faa7ae800e1ea82ab84` -> `f02639741b8cf721f1878f6a50632e861905d5d0`, adding only the immutable V6 Full Report at the path above (205 added lines; no deletions). The report was read back from GitHub with exact Git blob `92da7fc20d9f565cca78c48cb241f255075f6c2d`. These are independently established remote facts. The Windows process packet, 25 new local evidence hashes, 1,285 historical hashes, APK local hashes, and device/fixture state are **implementation-agent-reported evidence**, not independently executed tests or reread local files by this reviewer.

## 2. V6 launch boundary, A/B stop rule and inference

The V6 diagnostic was limited to the named `FormatBatchHandoffProductionWiringTest#realProducerCapturesAAndBButLeavesLateCProcessing` Android instrumentation, using ordinary Codex `exec_command` -> Windows PowerShell -> `.NET ProcessStartInfo/Process.Start()` -> original SDK ADB. It intentionally omitted ordinary immediate ADB/device/Logcat preflight in Arm A while retaining the non-spawning safety/identity guard. Arm B after bounded read-only preflight was explicitly conditional upon **actual A PASS**.

The Full Report says Arm A's only attempted `Process.Start()` threw a `System.Management.Automation.MethodInvocationException` wrapping `System.ComponentModel.Win32Exception` NativeErrorCode5 `Access is denied` at `System.Diagnostics.Process.StartWithCreateProcess`. Controller PID3104, parent PID21468 / `codex.exe`, and PowerShell outer exit1 were recorded. ADB PID and native exit were NOT_RETURNED; raw instrumentation stdout/stderr were zero bytes. No verified Android runner execution/status, Kotlin assertion, Room state, completion or cleanup result exists. Absence of a returned child PID does not independently prove no transient native process existed; maintain that narrower observation.

A: `START_ERROR_5`, 1 attempted launch, zero Android verified executions. B: `NOT_REACHED_A_START_ERROR_5`, zero preflight commands, zero launch attempts. The agent stopped at the first failure, did not retry via alternate tool, did not run the other104 methods and did not modify source, installed packages, permissions or security settings as reported. This is a procedurally compliant stop, not a successful completed A/B comparison and not a test semantic FAIL.

**Causal classification:** an immediately preceding **V6 ordinary ADB preflight is demonstrably not necessary** for this particular error5 recurrence. This does not establish that other prior host/process/device events never influence launch, and does not establish a controlled negative causal effect of preflight: Arm B never ran. V5 logcat ADB child had launched successfully before the V5 instrumentation denial; user's later ordinary WindowsTerminal/PowerShell manual invocation completed the same method with `OK (1 test)` (user-reported). V6 now identifies the parent as `codex.exe`, but matching reported parent PID21468 across V5/V6 alone is not proof of historical parent process continuity. Medium integrity and zero restricting SID observations do not equate all process security/context attributes. Windows denial cause, policy attribution, Kollus, Codex sandbox and preflight causation all remain **NOT_VERIFIED**.

## 3. Source/root and exact-final-SHA treatment

This wave changed no production GitHub commit. The V5 independent checkpoint already established on this same live authoritative SHA that the current production path still publishes format background work from live Processing IDs and lacks the intended durable frozen captured batch authority, while legacy DAO status-wide update lacks exact captured-ID conditional authority. That prior exact-source causal finding remains applicable **because the source HEAD is unchanged**. This V6 infrastructure event provides neither contrary new source semantics nor execution of the local draft. Thus `BUG-FORMAT-BG-01=OPEN P2`, `BUG-FORMAT-BG-02=OPEN P2`; likewise BG-03, BG-04, BG-05, INCOGNITO-01 remain OPEN P2. Confirmed OPEN P2=6, AMBIGUOUS P2=0, unresolved P2=6. No new root and no repository CLEAN or known-good baseline claim.

A user-reported manual single-method PASS on the dirty local candidate is useful context for the environment discrepancy, but it is not Codex Arm A PASS, independent reviewer-run execution, the complete 105-method required gate, nor exact final committed SHA evidence. Section16.3 closure remains unmet; do not publish/close on an uncommitted draft, and do not relabel the production roots solely due to Windows error5.

## 4. Review retrospective and bounded future observation

The protocol-specified A/B experiment correctly short-circuited before B, conserving device integrity and preventing a green-seeking retry. The cost is that the requested preflight-after comparison cannot be measured. The failure packet is more discriminating than the prior summary because it pins parent process `codex.exe` PID21468 in V6, exact controller PID3104 and `.NET` native boundary. The report also correctly identifies that B never ran, no direct postfailure device checks occurred, and fixed-order A then B would have carried fixture/time confounders even if completed.

If separately authorized, favor a **read-only comparison of existing Codex and manual WindowsTerminal process/context records**: parent executable/signature and parent-child paths, actual token groups and integrity, job/restriction context, executable and argv identity, working directory, environment fingerprints, streams and process creation timestamps. Existing logs may be unavailable or not record denied creates. No privilege escalation, security exceptions, service/ACL changes, unchanged denied-command retry, APK reinstall or unauthorized production modification is justified by V6 alone. If a legitimately different host condition is independently established later, any future test needs its own narrow authorization and protection check; it must not silently treat V6 as a successful test gate.

## 5. Workflow and routing boundary

The private `NEXT_CHAT.md` capsule still advertises `PROMPT_EXECUTION_STATUS=PERSISTED_NOT_STARTED`, `V6_STATUS=PERSISTED_NOT_STARTED`, and a launch-ready V6 `EXACT_NEXT_ACTION` even though the immutable V6 Full Report proves it already executed and stopped. This is a **stale/invalid launch-ready claim**; the V6 prompt must not be relaunched. Protocol Section8.2 makes a review-only evidence update insufficient authorization to mutate the agent/prompt lifecycle or task routing in `NEXT_CHAT.md`. Do not update that capsule, persist a new agent prompt, change ownership, or emit its otherwise mechanical bootstrap. The status discrepancy requires separate authorized reconciliation before any future agent execution. The only current reviewer write is this append-only checkpoint; original V6 Full Report and historical V5 evidence remain immutable.

INDEPENDENT EXECUTION: NOT EXECUTED
