# Independent reviewer verification — WINDOW-01 and RESTORE-01 on exact db339e1

date: 2026-10-12
checkpoint_kind: INDEPENDENT_FOCUSED_CORRECTNESS_VERIFICATION
checkpoint_status: FINAL_SOURCE_SEMANTIC_VERIFICATION_WITH_EXECUTION_EVIDENCE_GAP
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_expected_parent: 67b3d95f88ab0d3f30ae6b71a68cf43f1d38af69
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
private_handoff_at_start: 9a200ccbe93cc81fdd2714aa9184df1d976490f6
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
production_source_changed: NO
reviewer_independent_execution: NOT_EXECUTED
repository_wide_verdict: NOT_CLEAN
canonical_root_dispositions_changed: NONE
active_download_open_p2_count: 8

## Ownership correction and precise scope

This is REVIEWER-OWNED independent production-semantic verification, not a request for an implementation agent to decide correctness. The earlier preflight `2026-10-12__db339e1__scheduler-window-restore-closure-gate-preflight.md` and private persisted prompt `2026-10-12_GPT61_SOL_WINDOW01_RESTORE01_EXACT_SHA_CLOSURE_EVIDENCE.md` incorrectly asked an execution agent to classify closure readiness. They are historical evidence and must not be launched as reviewer-substitution. The agent may only collect and execute exact, already-authorized tests and report raw facts. This checkpoint contains the independent reviewer findings and still-missing *execution evidence*; the reviewer retains final OPEN/CLOSED authority.

The fix that introduced these semantics is in the exact three-commit compatible ancestry `eda6a758..db339e1`, not a speculative local draft. The most recent `c34b8aa..db339e1` adds only the targeted accepted-terminal scheduler retry and associated tests, not a new WINDOW/RESTORE implementation rewrite.

## BUG-SCHEDULER-WINDOW-01 independent source proof

- Producer: `ScheduledDownloadWindow.kt` `contains` checks integer local minutes inclusive between start and end (or circular overnight); milliseconds/seconds do not truncate membership. `nextEnd` selects the end minute's day, then `Calendar.add(MINUTE, 1)`; 23:59 rolls to next day. `nextBoundary` zeroes seconds and milliseconds. For end=05:00 and now=05:00:59.999, membership is true, END timestamp is 05:01:00; at 05:01:00 membership false. The old 05:00:00 END-vs-inclusive-predicate contradiction is absent on this exact source. Equal minute and overnight arithmetic are covered by actual test *source*, not a claimed runtime result.
- Effect publication: `AlarmScheduler.scheduleWithinOrdinaryMutation` computes one `nextEnd` calendar and passes the SAME `calendar.timeInMillis` to both `prepareSchedulerBoundaryWithinOrdinaryMutation` (durable notBeforeAt) and `setAlarm` (AlarmManager exact wake-up or recoverable WorkManager fallback). `scheduleAtForRestore` separately uses exact handed-at timestamp. `ScheduleAlarmReceiver` and `CancelScheduleAlarmReceiver` pass captured `EXTRA_HANDOFF_ID` into `enqueueAndAwait`.
- Consumer/end effect: `AlarmScheduler.isDuringTheScheduledTime` consumes the same predicate used by `DownloadWorker` scheduler idle-stop condition (worker lines 1335–1358); end worker `CancelScheduledDownloadWorker` validates exact handoffId/requestId/generation/boundary and worker request ID before cancelling/requeueing. The repository's `ScheduledDownloadWindowProductionWiringTest.realQueueClaimsInInclusiveEndMinuteAndRealEndWorkerStopsAtFollowingMinute` source exercises actual minute, owner, worker, queue and handoff effects, not just a pure helper. Code/test-source tracing PASS for old root.
- Separate failures: `CancelScheduledDownloadWorker` also calls tag-wide `WorkManager.cancelAllWorkByTag("download")`; this proven separate existing `BUG-SCHEDULER-04` can cancel an independent future request. `DownloadDao.claimDownloadForWorker` does not revalidate independently mutable due-time (separate `BUG-SCHEDULER-06`). Neither is counted as the original WINDOW-01 timestamp/predicate root, nor hidden for whole-repository CLEAN.

Source result: `BUG-SCHEDULER-WINDOW-01` OLD_FAILURE_NOT_REPRODUCED; SOURCE_SEMANTICS_PASS_FOR_DEFINED_ROOT; executable final closure remains NOT_VERIFIED without inspectable exact-SHA executed-test artifacts.

## BUG-SCHEDULER-RESTORE-01 independent source proof

- Validation entry: `BackupRestoreParser.parse`, `fromTyped`, and `validatePlan` run `normalize/validateSettings`; `SchedulerSettingsValidation.validatePortable` rejects non-exact HH:mm, invalid ranges, wrong types and noncanonical Boolean for scheduler keys, before `RestoreTransactionCoordinator.begin` can publish active Reset pointer or `SettingsViewModel.restoreMergeData` writes Merge preferences. `SettingsViewModel.restorePlan` revalidates before both Reset/Merge decisions.
- Ordinary Merge: `SettingsViewModel.restoreMergeData` uses `RestoreMutationAdmission.withOrdinaryMutation`; `SchedulerSettingsTransitionCoordinator.prepareRestoredPreferences` computes effective retained/overridden scheduler image, first durably writes the scheduler RESTORE transition, then appends target preferences and transition to the editor; `check(editor.commit())` controls return; `reconcilePendingWithinOrdinaryMutation` publishes the actual external effect under same admission. Failures leave pending transition for startup retry, not success. Settings-absent Merge retains prior scheduler ownership.
- Reset: `RestoreTransactionCoordinator.begin` validates prior to active pointer, stages plan + journal, owns durable phase drive PREPARED→QUIESCED→FILES_READY→APPLYING→DATA_COMMITTED→RECONCILING→COMPLETE. `publishPreferences` preserves destination-local keys, uses `prepareRestoredPreferences` and checked commit, and on false attempts compensation while journal remains owned; `reconcilePostCommit` explicitly supersedes old ordinary transition, then calls `reconcileForRestore` under restore mutation admission. Settings-absent Reset does not silently revoke scheduler authority.
- Effect and recovery: `SchedulerSettingsTransitionCoordinator` persists RESTORE transition with fixed startAt/endAt and progress flags; `AlarmScheduler.applyRestoredSchedulerEffectWithinMutation` cancels old START/END durable/external owners before replacement publication, persists `priorOwnersRevoked`, `startPublished`, `endPublished`, then marks COMPLETE. WorkManager fallback `acceptRestoredWindowFallbackWithinMutation` awaits `Operation.result` and exact accepted carrier identity; failed existing fallback is advanced via request CAS. `WorkManagerHandoffRecovery.schedulerWorkRequestDisposition` guards old workers by exact generation/request/current boundary.
- Startup: `App.onCreate` first awaits `RestoreTransactionCoordinator.recover`, then `SchedulerSettingsTransitionCoordinator.reconcile`, then `WorkManagerHandoffRecovery.reconcile`; a remaining Restore pointer prevents normal mutation, rather than treating partially restored settings as a completed external authority. Test *source* covers enabled/disabled Reset, partial Merge, invalid portable inputs, interruption after preference publication, after START before END, fallback failure then actual WorkManager acceptance, stale receivers and ordinary-vs-Restore races.
- Source-level proof is bounded to the original root: validated restored preference identity and durable matching AlarmManager/WorkManager authority. A new independently runtime-reproduced failure is NOT claimed.

Source result: `BUG-SCHEDULER-RESTORE-01` OLD_FAILURE_NOT_REPRODUCED; SOURCE_SEMANTICS_PASS_FOR_DEFINED_ROOT; executable final closure remains NOT_VERIFIED without inspectable exact-SHA executed-test artifacts.

## Execution evidence and decisive next event

A separate completed implementation report, `2026-10-12__db339e1__bug-scheduler-01-accepted-terminal-independent-closure.md`, records an agent-reported clean exact-published-db339e1 gate of 57 JVM + 95 Android = 152 PASS, 0 FAIL, 0 SKIP including full scheduler window and Restore/Reset classes. Its raw sealed report/evidence reside ONLY on the agent machine at `C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-v3-alarm-window-20261011-86f87dee/BUG_SCHEDULER_01_V3_PUBLICATION_AND_EXACT_SHA_VERIFICATION_REPORT.md`. The reviewer independently read exact GitHub source and test definitions but has not independently read those sealed raw execution logs or run the Android tests. Thus an aggregate report cannot establish executed-method coverage, app-UID exact-alarm capability, exit status and exact source/test/config parity by itself. Do not claim independent runtime PASS. Existing evidence must be extracted/inspected BEFORE considering any rerun; new test execution is authorized ONLY if the reviewer identifies a genuine material gap.

The next operational agent action may be **raw-evidence retrieval only**, with no authority to determine if these roots are correct, complete, PASS for closure, or CLOSED. Reviewer will parse the raw evidence and decide whether supplemental tests are needed. There is no authorization for production/test changes, history mutation, permission changes, installation, or new arbitrary suite runs in that evidence retrieval step. If the preserved reports cannot be accessed, return the precise missing artifact and tested SHA provenance gap; reviewer determines the next necessary focused test gate. Keep original OPEN P2 for both until then.

independent_verdict_window: SOURCE_OLD_FAILURE_NOT_REPRODUCED; EXECUTION_CLOSURE_NOT_VERIFIED
independent_verdict_restore: SOURCE_OLD_FAILURE_NOT_REPRODUCED; EXECUTION_CLOSURE_NOT_VERIFIED
new_roots: 0
root_count_delta: 0
next_owner: REVIEWER_AFTER_RAW_EVIDENCE_RETRIEVAL
next_action: INSPECT_SAVED_EXACT_SHA_RAW_TEST_EVIDENCE_THEN_INDEPENDENTLY_DECIDE_PER_ROOT_CLOSURE
INDEPENDENT EXECUTION: NOT EXECUTED
