# Independent stopped-wave review — V8 cold boot succeeded, installed baseline APK blocked candidate test

date: 2026-10-12
checkpoint_kind: IMPLEMENTATION_AGENT_DIAGNOSTIC_STOP_BOUNDARY_INDEPENDENT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: 2ba0fc3152deeac3cdf81e698ee0498044b03894
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
governing_v7_review_checkpoint: review-runs/checkpoints/2026-10-12__db339e1__fmt-bg01-bg02-v7-emulator-offline-readiness-timeout-independent-review.md
governing_v7_review_blob: 16efcb496dd205cb148112eb3cfd8b05f1280b72
execution_v8_prompt_path: ytdlnisx/prompts/2026-10-12_GPT61_SOL_FMT_BG01_BG02_V8_GRACEFUL_STOP_COLD_BOOT_SINGLE_TEST.md
execution_v8_prompt_blob: 0480502220f3f49e815d46424c9d1bb7b2e645a0
immutable_v8_report_path: ytdlnisx/reports/2026-10-12__FMT_BG01_BG02_V8_COLD_BOOT__fmt-bg01-bg02-v8-coldboot-20261012-898aedd6__FULL_REPORT.md
immutable_v8_report_blob: 03d663d288b1308442f701f5a6db3772a6ad1c95
private_report_only_commit: c8ca562c49c4f6ee6eba8ccbf3396cab98c94e6e
private_archive_status: VERIFIED_GITHUB_BLOB_ONE_FORWARD_REPORT_ONLY_COMMIT
original_private_handoff_blob: b97e1b487890ea7a8d3ef98e5c39bf050f54eee0
implementation_remote_source_changed: NO
source_commit_and_push: NONE
protected_local_candidate: UNCOMMITTED_17_FILE_DIRTY_DRAFT_AGENT_REPORTED_UNCHANGED
candidate_manifest_sha256: b8ef5fb31ebdc77141fc38e4ea0e90e86cd61d4eece5198ae95532be02171322
candidate_index_sha256: 177c240e43c04fdd1a88d6c649d8dc5967aeda6147dd2670b5a7aae4f5c9e37b
dedicated_avd: Medium_Phone_API_36.1
serial: emulator-5554
initial_device_state: offline
graceful_shutdown_attempts: 1
graceful_shutdown: CONFIRMED_AGENT_REPORTED
old_emulator_pid: 14684
old_qemu_pid: 9588
cold_boot_attempts: 1
cold_boot: CONFIRMED_AGENT_REPORTED
new_emulator_pid: 5000
new_qemu_pid: 20692
device_state_after_cold_boot: device
android_boot_completed: 1
android_api: 36
android_user: 0
read_only_adb_call_count: 27
read_only_adb_native_status: ALL_EXIT_0_AGENT_REPORTED
installed_candidate_app_identity_gate: FAIL_INSTALLED_BASELINE_INSTEAD_OF_CANDIDATE
installed_app_sha256: 6bc9dfbdc4b926c9c92193992fbeb06fa069196974b36254f736f0f3c3143ba0
protected_baseline_app_sha256: 6bc9dfbdc4b926c9c92193992fbeb06fa069196974b36254f736f0f3c3143ba0
protected_candidate_app_sha256: 0cf275025abafd65037aec1cd8adfa6a4f8ca61d95a55aefd042353d063bcda7
protected_candidate_test_sha256: 49573f444934366df232cd2d2b00c118b505283b9958e81890df3d52fcd4af25
current_installed_test_apk_identity: NOT_REACHED
test_runner_verification: NOT_REACHED
android_instrumentation_launch_attempts: 0
android_verified_executions: 0
android_pass: 0
android_fail: 0
android_skip: 0
target_method: com.ireum.ytdl.work.FormatBatchHandoffProductionWiringTest#realProducerCapturesAAndBButLeavesLateCProcessing
target_method_status: NOT_EXECUTED_IDENTITY_GATE
windows_native_instrumentation_error5: NOT_REACHED
installed_baseline_cause: NOT_VERIFIED
v7_offline_cause: NOT_VERIFIED
v6_winerror5_cause: NOT_VERIFIED
cold_boot_restored_readiness_causation: NOT_VERIFIED
new_evidence_entries_hashed: 200_ENTRIES_ZERO_MISMATCH_AGENT_REPORTED_FINAL
historical_evidence_entries_hashed: 1648_ENTRIES_ZERO_MISMATCH_AGENT_REPORTED
evidence_location_exception: 141_NEW_V8_FILES_APPENDED_IN_V7_DIRECTORY
historical_sealed_evidence_overwritten: NONE_AGENT_REPORTED
historical_evidence_directory_membership_unchanged: NO
evidence_output_isolation: VIOLATION_CONFIRMED_BY_AGENT_REPORT_NOT_PRODUCTION_ROOT
active_remediation_confirmed_open_p2: 6
active_remediation_ambiguous_p2: 0
active_remediation_unresolved_p2: 6
current_bug_format_bg01: OPEN
current_bug_format_bg02: OPEN
canonical_root_delta: 0
repository_wide_verdict: NOT_CLEAN
independent_execution: NOT_EXECUTED

## 1. GitHub provenance and archive verification

The reviewer independently fetched GitHub `ireum-0/private:ytdlnisx-review` at `c8ca562c49c4f6ee6eba8ccbf3396cab98c94e6e` and read back V8 Full Report Git blob `03d663d288b1308442f701f5a6db3772a6ad1c95`. The archive commit is exactly one ordinary forward step from `da621777829148c94862d4a9d87d950de9eb48a3`, adding only the immutable V8 Full Report (147 added lines, zero deletes). The implementation head remained `db339e1e90392cfd994e2d42bca7ce88afb3be21`; the review head before this checkpoint was `2ba0fc3152deeac3cdf81e698ee0498044b03894`; plan/ledger and protocol identities matched the pinned values above. The prompt is the persisted reviewed blob above. No production or NEXT_CHAT modification is evidenced by the archive.

The local emulator commands, native exits, APK hashes from the guest, 200 new evidence entries, 1648 historical hash checks, local candidate/status/index/worktree audits and the V7 directory additions are **agent-reported**, not independently rerun or filesystem-inspected by the GitHub-only reviewer. The GitHub report establishes provenance of that signed-off account of the run, not independent proof of every local byte.

## 2. First material stop and experiment validity

V7 ended with the dedicated emulator `emulator-5554 offline`. Subsequent user PowerShell observations showed the same old emulator PID14684 and QEMU PID9588 alive, but black screen and offline. V8 used read-only process/AVD identity, port ownership and emulator console identity observations, then sent **one** normal `adb -s emulator-5554 emu kill` request, receiving a successful close acknowledgement and observing both dedicated old processes exit within about4.7s. Preliminary date parsing and pipeline syntax errors occurred in observer scripts **before** any actual shutdown command and were recovered by read-only corrections. No force termination or other device was reported touched.

One documented `-no-snapshot-load` Cold Boot launched the same AVD with emulator PID5000/QEMU PID20692, and after about66 seconds the expected serial reached `device` with Android `sys.boot_completed=1`, API36 and user0. Twenty-seven read-only ADB invocations exited0 with no Win32 error5. Thus this run credibly crossed the previously blocked **device readiness** boundary. It did not cross the previously blocked **instrumentation Process.Start** boundary. The observation does not establish why V7 had been offline or that Windows error5 is fixed; permanent repair and causal mechanism stay NOT_VERIFIED.

At the installed application identity gate, the agent observed guest `sha256sum` for the installed app `6bc9dfbdc4b926c9c92193992fbeb06fa069196974b36254f736f0f3c3143ba0`: byte identity equal to the preserved **baseline app**, not the required protected **candidate app** `0cf275025abafd65037aec1cd8adfa6a4f8ca61d95a55aefd042353d063bcda7`. The prior V5 candidate installed-readback and V8 baseline installed-readback cannot both be projected onto one unchanged guest state; provenance of the intervening installed-byte change is NOT_VERIFIED. An old snapshot/disk identity and any other possible source of state drift remain unproven. The agent stopped without installing, clearing data, or triggering the method. This exact gate failure is appropriate: a test on the baseline app would not verify the uncommitted BG candidate.

After the failure the following were correctly NOT_REACHED: installed test APK identity, runner and guest instrumentation quiescence, named Android method, other104 methods and 105-method full gate. 0 native instrumentation launches, 0 runner methods, 0 PASS/FAIL/SKIP. This is an APK identity stop **not a test assertion failure** and cannot be called successful Android runtime verification.

## 3. Evidence preservation exception: appends into V7 directory

The agent reports that its V8 logging helper left a Windows-path assignment to the prior V7 evidence directory after updating only a forward-slash occurrence. It subsequently created **141 uniquely named V8 records inside the V7 evidence directory** rather than only the intended fresh V8 folder. The agent did not overwrite any original V7 evidence file; exclusive creation and hashes reportedly retained 1,648 historical sealed evidence entries and original V7 manifest bytes, including final manifest SHA256 `5ebb11f5f416b368b715e233b4f50f6cea4eb4fd06857a6b2ed6c8ea78f378e9`.

**Disposition:** this is a real evidence-location/run-isolation error; the V7 directory's membership is changed, so neither “V7 directory unchanged” nor an unqualified “all evidence unchanged” claim is correct. Original sealed historical file bytes are agent-reported unchanged; append-only relocation copies in the V8 `boot-output-snapshots/` directory and source V8 records are not substitutes for silent cleanup of the 141 newly added V7-directory entries. Preserve all versions and explicit origin/hash mapping; do not move/delete/rewrite any existing evidence. The 200 final new evidence entry hash matches and 1,648 historical matches remain attributed agent evidence; no new production correctness root follows from this diagnostics bookkeeping failure. It is a provenance/containment exception that must be considered in subsequent task preflight and independent evidence interpretation.

The original 17 local candidate source/test/schema files, index, six protected worktrees, baseline/candidate APK files and caches were independently reported byte-identical pre/post. The normal V8 AVD graceful stop/Cold Boot can alter mutable AVD userdata and runtime snapshots; their byte identity is NOT_VERIFIED and cannot be asserted unchanged. No source commits/push, rebuild/reinstall, forced kill, OS security or permission policy mutations were reported.

## 4. Canonical source/root status and bounded reviewer disposition

The exact implementation remote HEAD remains unchanged `db339e1e90392cfd994e2d42bca7ce88afb3be21`. Existing independently established production incorrect-effect chains on that same SHA still support `BUG-FORMAT-BG-01=OPEN P2` and `BUG-FORMAT-BG-02=OPEN P2`. The four other active production roots BG-03, BG-04, BG-05 and INCOGNITO-01 remain OPEN P2; confirmed OPEN P2=6, AMBIGUOUS P2=0, unresolved P2=6, canonical delta0. This V8 diagnostic did not generate a new implemented source correction or test on the dirty candidate and cannot close any root, advance source publication, or declare repository CLEAN.

Reviewer recommends, **without separately authorizing**, a narrow inventory/state provenance comparison between historical V5 emulator installed-candidate APK readback and current V8 baseline installed SHA; also verify the installed test APK identity and candidate APK/install authorization boundaries in any future task. Reinstallation changes device bytes and must be separately authorized after protective state/ownership and data-risk review. Do not automatically install, Cold Boot again, rerun instrumentation or change source; first preserve exact baseline/candidate identities and why previous guest evidence no longer matches current guest. New instrumentation is legitimate only after the expected installed package/runner state is restored and independently verified. Historical V6 native error5 and V7 offline root causes remain NOT_VERIFIED.

## 5. Workflow and launch boundary

Private `NEXT_CHAT.md` blob `b97e1b487890ea7a8d3ef98e5c39bf050f54eee0` still says `CURRENT_FMT_BG01_BG02_V8_STATUS=PERSISTED_NOT_STARTED` and `PROMPT_EXECUTION_STATUS=PERSISTED_NOT_STARTED`, while the immutable V8 Full Report proves V8 actually ran and stopped. The existing V8 launch-ready capsule is stale and **invalid for execution**. Under protocol Section8.2 this independent review-only request may append the reviewer checkpoint and reconcile canonical root evidence, but does not authorize changes to operational prompt/lifecycle fields, future install/device instructions or assignment. Do not emit a canonical bootstrap that would relaunch the already completed V8. An independently authorized reconciliation must precede any new agent work.

INDEPENDENT EXECUTION: NOT EXECUTED
