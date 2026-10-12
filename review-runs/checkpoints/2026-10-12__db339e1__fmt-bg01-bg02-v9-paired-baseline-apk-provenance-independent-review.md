# Independent V9 review — paired baseline APK provenance, candidate verification still blocked

date: 2026-10-12
checkpoint_kind: IMPLEMENTATION_AGENT_DIAGNOSTIC_COMPLETION_INDEPENDENT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: 38dba4d311e4696fdb411ca63f9c0837305d094b
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
governing_v8_review_checkpoint: review-runs/checkpoints/2026-10-12__db339e1__fmt-bg01-bg02-v8-baseline-installed-apk-identity-stop-independent-review.md
governing_v8_review_checkpoint_blob: 3e9f9cd46d26855467ea17d143dabe728aa58fc0
execution_v9_prompt_path: ytdlnisx/prompts/2026-10-12_GPT61_SOL_FMT_BG01_BG02_V9_INSTALLED_APK_PROVENANCE_READONLY_DIAGNOSTIC.md
execution_v9_prompt_blob: c58ad6f933c6afe26efccfb6c451b85e8a157772
immutable_v9_report_path: ytdlnisx/reports/2026-10-12__FMT_BG01_BG02_V9_APK_PROVENANCE__fmt-bg01-bg02-v9-provenance-20261012-a2f313e7__FULL_REPORT.md
immutable_v9_report_blob: 9427b63625a137dbc20056c7ebe60e5bd17ac577
private_report_only_commit: 1f8ad7aecec456d9ee656ca73889fd122748d917
private_archive_status: GITHUB_READBACK_VERIFIED_SINGLE_NORMAL_FORWARD_ADDED_REPORT
private_handoff_blob_at_execution: f889e43e555d0a9fe50ec8f23676937e359fc72f
implementation_remote_source_changed: NO
source_commit_or_push: NONE
dirty_candidate_files: 17_AGENT_REPORTED_UNCHANGED
candidate_source_manifest_sha256: b8ef5fb31ebdc77141fc38e4ea0e90e86cd61d4eece5198ae95532be02171322
candidate_index_sha256: 177c240e43c04fdd1a88d6c649d8dc5967aeda6147dd2670b5a7aae4f5c9e37b
dedicated_avd: Medium_Phone_API_36.1
serial: emulator-5554
same_post_v8_emulator_process_pid: 5000_AGENT_REPORTED
same_post_v8_qemu_process_pid: 20692_AGENT_REPORTED
device_state: device_AGENT_REPORTED
android_api: 36_AGENT_REPORTED
android_user: 0_AGENT_REPORTED
read_only_adb_queries: 11_OF_12_BUDGET_ALL_NATIVE_EXIT_0_AGENT_REPORTED
windows_native_error5_observed_this_run: NO_READONLY_QUERIES_ONLY
actual_installed_app_sha256: 6bc9dfbdc4b926c9c92193992fbeb06fa069196974b36254f736f0f3c3143ba0
actual_installed_test_sha256: 5a28571590340e2d4bf698a8b15f3f20dc2a02ee6daa27de92364744dc11dd1f
protected_baseline_app_sha256: 6bc9dfbdc4b926c9c92193992fbeb06fa069196974b36254f736f0f3c3143ba0
protected_baseline_test_sha256: 5a28571590340e2d4bf698a8b15f3f20dc2a02ee6daa27de92364744dc11dd1f
protected_candidate_app_sha256: 0cf275025abafd65037aec1cd8adfa6a4f8ca61d95a55aefd042353d063bcda7
protected_candidate_test_sha256: 49573f444934366df232cd2d2b00c118b505283b9958e81890df3d52fcd4af25
runner_expected: com.ireum.ytdl.debug.test/androidx.test.runner.AndroidJUnitRunner
runner_present: YES_AGENT_REPORTED
v5_candidate_installed_and_hash_readback: VERIFIED_AS_AGENT_RECORDED_HISTORICAL_OBSERVATION
v9_baseline_package_last_update_before_v5: GUEST_METADATA_AGENT_REPORTED_TIMEZONE_UNNORMALIZED
installed_apk_state_transition_cause: NOT_VERIFIED
v6_winerror5_cause: NOT_VERIFIED
v7_offline_cause: NOT_VERIFIED
candidate_runtime_verification: BLOCKED_INSTALLED_BASELINE_PAIR
instrumentation_attempts: 0
android_executed_methods: 0
android_pass: 0
android_fail: 0
android_skip: 0
android_105_method_gate: NOT_AUTHORIZED_NOT_EXECUTED
historical_manifest_entries_verified_agent: 1848_NOT_DISTINCT_FILES_ZERO_MISMATCH
v8_legacy_directory_additions_verified_agent: 141_ZERO_MISMATCH
v9_new_evidence_entries_final_agent: 84_USER_REPORTED_ZERO_MISMATCH
v9_new_evidence_entries_pre_report: 78_IN_GITHUB_REPORT_ZERO_MISMATCH_AGENT_REPORTED
v9_evidence_isolation: NEW_EXCLUSIVE_CANONICAL_PATH_AGENT_REPORTED
source_semantic_review_of_uncommitted_17_FILES: NOT_PERFORMED_BY_THIS_DIAGNOSTIC
canonical_root_delta: 0
current_bug_format_bg01: OPEN
current_bug_format_bg02: OPEN
active_remediation_confirmed_open_p2: 6
active_remediation_ambiguous_p2: 0
active_remediation_unresolved_p2: 6
repository_wide_verdict: NOT_CLEAN
independent_execution: NOT_EXECUTED

## 1. Authoritative provenance and archive

Independent GitHub reviewer fetched `ireum-0/private:ytdlnisx-review` at `1f8ad7aecec456d9ee656ca73889fd122748d917`, V9 report blob `9427b63625a137dbc20056c7ebe60e5bd17ac577`, the persisted V9 prompt `c58ad6f933c6afe26efccfb6c451b85e8a157772`, protocol `4107f1760d18f4a273ea25fb212b014701bc3bda`, V5 and V8 report blobs, and V8 governing FINAL reviewer checkpoint blob. Private archive is exactly one forward commit from `760cc47a494fb3655ab5cceba62a473379543bd9`, adding only the V9 immutable Full Report (170 added lines, zero deleted). Remote implementation head unchanged `db339e1e90392cfd994e2d42bca7ce88afb3be21`; review tip before this checkpoint `38dba4d311e4696fdb411ca63f9c0837305d094b`; plan and ledger tips and applicable protocol unchanged.

All guest process, installed APK, package metadata, local source/index/worktree hashes, evidence files, host native exits and other machine-state claims below come from the immutable agent Full Report (and user-provided final count), **not independent host execution or independent rehashing** by the GitHub reviewer.

## 2. Scope completion and independent identity reasoning

The V9 prompt explicitly prohibited installation, AVD restart, source change, Gradle build or Android instrumentation. Agent recorded one preparation-time observer PowerShell Substring exception before any ADB or output helper creation; a new corrected read-only helper then performed eleven bounded ADB queries (within 12 permitted), all native exit0, with no separate launcher/retries. No material V9 device/test/protected-state failure is established. Exact live emulator/serial/AVD/API/user/runner package identity was reported consistent with V8; no new Android methods were run.

V9 read `base.apk` bytes for `com.ireum.ytdl.debug` and `com.ireum.ytdl.debug.test`. Both observed SHA-256 values match the protected **baseline** APK pair exactly. Both differ from their respective preserved **BG candidate** APKs. One expected `com.ireum.ytdl.debug.test/androidx.test.runner.AndroidJUnitRunner` entry targets the app. That package combination is internally consistent as a baseline installation; it is not intrinsically a broken or anomalous device configuration. **The discrepancy is relative to the specific requested test target**, which must exercise the uncommitted BG candidate production and Android-test code. Runner presence under baseline bytes does not prove candidate test readiness or validate candidate runtime behavior.

V5 separately reported successful `adb install -r -t` of the candidate pair and exact installed candidate SHA readback; V8 first observed baseline application SHA after one cold boot; V9 again observed the paired baseline APKs. These are time-specific observations, not a single unchanged guest-state proof. Different installed code paths and V9 package lastUpdateTime metadata pre-dating the V5 installation, while firstInstallTime matches, provide evidence of a guest package-state difference; timestamps were explicitly reported without independent timezone normalization. They do **not** independently identify when/why the state changed. No complete discriminating install/snapshot/guest-data history exists in the inspected V5/V8/V9 reports. V8 `-no-snapshot-load` proves only a no-snapshot-load request at V8 launch, not that it restored/reverted particular guest bytes.

Therefore `INSTALLED_BASELINE_PROVENANCE_CAUSE=NOT_VERIFIED`. V6 instrumentation `Process.Start` Win32 code5 was **not retried** in V9; 11 successful ordinary ADB process launches do not establish its cause/resolution. V7 offline cause also remains `NOT_VERIFIED`. Candidate runtime blocked, `0` instrumentation attempts and `0` executed Android methods; no source-semantic or correctness closure is implied.

## 3. Protected evidence and isolation

Agent reported the original 17 dirty candidate files and source manifest unchanged, staging-empty physical index unchanged, six historical protected worktrees, additional V2 worktree, 21 registered worktrees, four cached literal-%SystemDrive% files, local candidate/baseline APKs and original executable hashes preserved. Historic audit covered **1,848 manifest entries** (1,310 +338 +200), not necessarily 1,848 distinct files, with zero mismatches. All **141** prior V8 additions mistakenly placed under V7 evidence root reportedly remained unchanged. V9 created a fresh exclusive canonical evidence folder, validated no containment/alias with V2–V8 roots, reviewed helper assignment before invocation and recorded no new writes into older evidence locations. Thus the V8 evidence-directory membership exception persists, but V9 did not repeat it as agent reported; no historical bytes or directory membership cleanup authorized.

V9 report explicitly records 78 new entries verified at the **pre-report** sealing stage; the user-provided V9 outcome states 84 new final entries verified, zero mismatches. This final count is not independently recomputed here and the archived text does not list the complete post-archive manifest entry count. Both should retain their stage-specific attribution rather than be silently equated. Mutable emulator disk/snapshot bytes were not comprehensively hashed or declared unchanged. Historical user-reported hash verification is evidence about protected local files, not independent GitHub verification of each file.

## 4. Candidate update recommendation — not authorization

Current app/test APKs are a consistent **baseline** pair; no repair of the baseline as such is required. To run the BG candidate instrumentation, both candidate application and candidate test APK byte identities need verified, but overwriting installed packages is a **new device mutation** and outside V9 authorization. The V5 installation evidence (matching signature/version and previous additive schema63-to-64 migration safety review) is historical and does not prove today's app data, WAL state, database schema, migration behavior or guest disk provenance. A same-version `adb install -r -t` may preserve app data but does not by itself prove it safe in the current state; neither a reinstall nor destructive downgrade/wipe is approved by this checkpoint.

Recommended for a separately authorized plan: first establish current device ownership, installed app/test signed artifacts, versions, process quiescence, on-device persistent DB/schema and installed state risks by non-destructive gates; after explicit user authorization, consider a bounded **data-preserving update of both existing candidate APKs** with exact current-installed hash re-readback, and only then a separately bounded named instrumentation test. Do not auto-trigger installation/test; fail closed if current data/schema can't be safely established. If candidate-source semantics or provenance mismatches appear, return for independent review before any device mutation. Candidate source includes 9 tracked modifications and 8 new paths; this V9 diagnostic did not inspect or validate the semantics/necessity of each local uncommitted change. No rebuild indicated by V9 observation alone, but no proof of source-review completion either.

## 5. Canonical root and workflow constraints

Implementation published SHA remains unchanged; established BG01/BG02 production roots remain OPEN P2. Other four active roots BG03/BG04/BG05/INCOGNITO01 remain OPEN P2; active confirmed unresolved6 P2, ambiguous0, canonical root delta0; repository NOT_CLEAN. This observer result is not an independently executed Android test or closure gate.

Private `NEXT_CHAT.md` remains at blob `f889e43e555d0a9fe50ec8f23676937e359fc72f` and still marks V9 `PERSISTED_NOT_STARTED` and `PROMPT_EXECUTION_STATUS=PERSISTED_NOT_STARTED`, despite the immutable V9 execution report. The recorded launch-ready state is **stale, not executable**. This independent review-only checkpoint does not authorize modifying NEXT_ACTION_OWNER, prompt execution status, work flags, future device/install/test scopes or fresh prompt assignment under protocol Section8.2. Do not issue canonical bootstrap for already executed V9. Future V10/new action needs independent explicit authorization and fresh proper handoff reconciliation; V9 may not be rerun as an alternative. The private report-only commit did not alter NEXT_CHAT, and the reviewer has not attempted a lifecycle write.

INDEPENDENT EXECUTION: NOT EXECUTED
