# Independent stopped-wave review — BG-01/BG-02 exact-alarm capability precondition

date: 2026-10-12
checkpoint_kind: IMPLEMENTATION_AGENT_STOP_BOUNDARY_INDEPENDENT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: d2bdf269205d1e7288f22e04182abda42951f630
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
execution_prompt_blob: ba81868f5fef4c077fb065ecacb67e8c72b32f55
production_source_changed: NO
independent_execution: NOT_EXECUTED
canonical_root_delta: 0
current_bg01_status: OPEN
current_bg02_status: OPEN
active_download_p2_count: 6
repository_wide_verdict: NOT_CLEAN

## Source and report basis
User-provided agent STOP synopsis (not the complete local original Full Report) says exact application-UID `AlarmManager.canScheduleExactAlarms()` returned false; no device permission mutation is authorized; zero source edits/builds/tests/implementation commits/pushes; protected states unchanged; 44 new and 876 historical evidence files verified with zero reported mismatch. The local Full Report was reported at `C:/Users/dh2/AppData/Local/Temp/ytdlnisx-fmt-bg01-bg02-20261012-8f24b4cd/FMT_BG01_BG02_FINAL_FULL_REPORT.md`. These file-count/UID details are AGENT_REPORTED, not independently read raw evidence. `PRIVATE_REPORT_ARCHIVE=BLOCKED` because the separate private report-only write was rejected by automatic approval, with explicit report-only approval pending. Do NOT bypass the rejected write, infer publication, or conflate archival with semantic test results.

Independent GitHub source inspection on exact `db339e1e90392cfd994e2d42bca7ce88afb3be21`: `DownloadViewModel.continueUpdatingFormatsOnBackground` + `DownloadDao.updateProcessingtoSavedStatus` still cause BG-01 status-wide mutation; `enqueueUniqueWork` still lacks BG-02 durable batch owner. `AlarmScheduler.kt` `setAlarm` (lines 380-394) catches exact alarm publication failure and requests `WorkManagerHandoffRecovery.ensureConvergence`; `setAlarmForRestore` similarly has the durable Restore fallback. `ScheduledDownloadWindowProductionWiringTest.realQueueClaimsInInclusiveEndMinuteAndRealEndWorkerStopsAtFollowingMinute` (lines 183+) explicitly installs a test seam that forces a real WorkManager fallback; it also observes `scheduler.canSchedule()` and a queue after the end minute, so the exact required capability of its whole scenario must be determined from actual source and runtime, not guessed.

## Independent gating decision
An app-UID `canScheduleExactAlarms()==false` is NOT a global precondition for implementing BG-01 captured bundle conditional Room transitions or BG-02 durable format-batch WorkManager handoff. In V2, permission/capability requirements belong to triggered scheduler regression tests rather than unconditional source-change eligibility. Stopping before any code/build solely on this condition prematurely broadens a conditional runtime gate. Do NOT attempt AppOps grant, permission prompt bypass, alternate user/account/emulator install, alarm substitution or weaker fake pass.

The safe continuation is a new narrow **same-root** implementation prompt preserving V2 semantic contract and all protected state, and making per-test app-UID capability checks just-in-time. BG-focused source work, compile/JVM/Room and any available permission-independent instrumentation can proceed in a new isolated clean worktree at `db339e1e90392cfd994e2d42bca7ce88afb3be21`. Inventory conditional scheduler triggers from actual changed shared code before test execution. For any genuinely mandatory regression requiring `canScheduleExactAlarms()==true`, if the verified existing test device returns false and no separately authorized already-capable test environment is available, mark that exact test NOT_REACHED/BLOCKED, preserve its predicate and pending authority, and STOP before claiming complete verification or publishing an inadequately verified production commit. Do not silently skip a required gate or change application/system permission. Material evidence may show a specific test needs no exact-alarm permission and can exercise the existing real fallback normally: decide test by test.

After a runtime capability blocker, preserve work/evidence; a new exact capability authorization requires an explicit user grant, not a silent prompt permission expansion. Keep BG-01, BG-02 OPEN until a later independently verified exact source and execution gate. Never interpret an implementation report's historical test counts as independently executed.

Report-only archive rejection is separate: existing original local Full Report stays immutable and `PRIVATE_REPORT_ARCHIVE=BLOCKED`. Reviewer may assess the user-provided report as evidence per Protocol Section 6.2, but the agent's rejected private write must not be repeated to evade automatic approval. A future new run's separately authorized new report-only publication may be attempted once under its own approval and immutable path, with a blocked result honestly preserved.

## Prompt correction boundary
Use a new immutable V3 prompt; do not edit/delete V2. Keep production scope limited to BG-01/BG-02, preserve closed WINDOW-01/RESTORE-01 and all deferred roots; keep same-wave chained final exact-SHA test obligation and mandatory Full Report on STOP. V3 shall not declare tests waived, infer device precondition true, or grant device permission.

review_decision: GLOBAL_EXACT_ALARM_PRECONDITION_NOT_APPLICABLE_TO_BG01_BG02_SOURCE_WORK; TEST_SPECIFIC_CAPABILITY_MAY_REMAIN_BLOCKED
next_action: REVIEWER_PERSIST_V3_NARROW_CONTINUATION_THEN_VERIFY_LAUNCH_READY
INDEPENDENT EXECUTION: NOT EXECUTED
