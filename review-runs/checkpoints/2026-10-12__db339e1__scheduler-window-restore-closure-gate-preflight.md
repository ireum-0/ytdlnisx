# Scheduler WINDOW-01 + RESTORE-01 — exact db339e1 closure-evidence preflight

date: 2026-10-12
checkpoint_kind: FOCUSED_REVIEWER_CLOSURE_GATE_PREFLIGHT
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
status: FINAL_PREFLIGHT_ACTIONABLE_EXECUTION_EVIDENCE_UNVERIFIED
review_start_parent: f2a93c999338e8d24e139ede05134354904452ed
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
production_source_changed: NO
independent_execution: NOT_EXECUTED
verdict: TWO_EXISTING_ROOTS_REMAIN_OPEN_PENDING_EXACT_SHA_CLOSURE_EVIDENCE

## Exact evidence reconciliation and root separation

Previously completed manual L6 checkpoint `2026-10-12__db339e1__manual-l6-cross-feature-final.md` traced the whole scheduled predicate through `ScheduledDownloadWindow.contains/nextEnd`, `AlarmScheduler`, AlarmManager / durable notBeforeAt and receiver publication, `DownloadViewModel`, `DownloadWorker`, and END final effects. It found the former end-minute contradiction NOT_REPRODUCED on source db339e1: inclusive HH:mm:xx membership and next-minute END ownership agree. This does NOT itself verify exact-SHA execution of all required cases. Old reopened evidence from `2026-10-06__eda6a758__manual-v7-l6-repository-wide-reopen-final.md` explains why pure helper/predicate arithmetic was not enough.

The same L6 and subsequent L3 checkpoint `2026-10-12__db339e1__manual-l3-concurrency-authority-final.md` traced `BackupRestoreParser` portable scheduler validation, `RestoreTransactionCoordinator` / durable `SchedulerSettingsTransitionCoordinator` transition, ordinary-vs-Restore admission, revocation, exact START/END carrier publication, replay, and worker handoff. Old RESTORE-01 source failure NOT_REPRODUCED; runtime closure NOT_VERIFIED. Independent per-item WorkManager tag-wide cancellation (`BUG-SCHEDULER-04`) and scheduled claim due-time omission (`BUG-SCHEDULER-06`) remain separate pre-existing roots; do not silently count them as WINDOW-01 or RESTORE-01 and do not conceal them to claim repository CLEAN.

`2026-10-12__db339e1__bug-scheduler-01-accepted-terminal-independent-closure.md` records implementation-agent reported **152 PASS / 0 FAIL / 0 SKIP on exact clean published db339e1**: 57 JVM and 95 Android, including seven window/END-owner tests, Restore/Reset, E1->E2 handoff and accepted-terminal recovery. Its sealed local report is `C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-v3-alarm-window-20261011-86f87dee/BUG_SCHEDULER_01_V3_PUBLICATION_AND_EXACT_SHA_VERIFICATION_REPORT.md`. Reviewer has independently verified exact GitHub source and matching remote commit but has NOT read the sealed local execution logs or re-executed tests. The evidence cannot yet be declared complete for these two distinct roots without mapping actual tests/results/preconditions and clean-SHA identity to each root's entire final-effect closure contract. Do not manufacture a rerun requirement where the existing sealed result already suffices.

Production-bound existing test surfaces at db339e1 include:
- `app/src/test/java/com/ireum/ytdl/work/ScheduledDownloadWindowTest.kt` (full inclusive/end-minute rollover and normalized Calendar)
- `app/src/androidTest/java/com/ireum/ytdl/work/ScheduledDownloadWindowProductionWiringTest.kt` (real AlarmScheduler, durable boundary, real queue/END worker and exact owner release)
- `app/src/test/java/com/ireum/ytdl/work/SchedulerSettingsValidationTest.kt`
- `app/src/androidTest/java/com/ireum/ytdl/work/F11SchedulerSettingsTransitionProductionWiringTest.kt` (Reset/Merge enabled/disabled, invalid import, interrupted effects, replay/restart, stale receiver and terminal old carrier)
- `app/src/androidTest/java/com/ireum/ytdl/work/F11SchedulerExternalAuthorityProductionWiringTest.kt` (ordinary vs Restore races and cancellations)
- exact WorkManager handoff production tests when they own material end effects.

## Narrow required next action — evidence-first verification

Authorize a **verification-only** implementation-agent task, on the same exact db339e1 published SHA. First read/validate preserved original sealed V3 report, test XML/logs, source/test/config SHA identity, actual nonzero method executions, device/app-UID exact-alarm precondition, focused and broader gate coverage. Build a separate WINDOW-01 and RESTORE-01 closure matrix (old failure, live producer/carrier/consumer/final effect, normal/negative/crash/restart/race, actual evidence artifact, PASS/FAIL/NOT_VERIFIED). Reuse actual valid results, **no gratuitous complete 152-test rerun**. Execute only uncovered required focused and governing broader tests, with safe host/device/worktree/protected-state preflight and finite waits. Preserve existing evidence. If exact execution cannot occur, report exact blocker and NOT_REACHED gates, not PASS. Do not edit production/test files, mutate protected histories/trees, or classify either root CLOSED from agent side. Reviewer must independently inspect report and update canonical inventory/handoff only when two distinct closure gates prove complete. This checkpoint changes no canonical disposition or count.

next_action_owner: IMPLEMENTATION_AGENT_AFTER_PERSISTED_PROMPT_PREFLIGHT
verification_authorized: YES_SCOPED_TESTS_AND_EXISTING_EVIDENCE_AUDIT
implementation_correction_authorized: NO
source_commit_push_authorized: NO
reviewer_followup: VERIFY_AGENT_REPORT_AND_CLOSE_EACH_ROOT_IF_GATES_COMPLETE_ELSE_RECORD_EXACT_GAP
independent_execution: NOT_EXECUTED

INDEPENDENT EXECUTION: NOT EXECUTED
