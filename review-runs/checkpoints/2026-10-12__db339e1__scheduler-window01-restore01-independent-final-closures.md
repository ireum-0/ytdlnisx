# Independent final closure — BUG-SCHEDULER-WINDOW-01 and BUG-SCHEDULER-RESTORE-01

date: 2026-10-12
checkpoint_kind: CANONICAL_INDEPENDENT_FINDING_CLOSURE
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_parent_sha: 9b4ce39ff0d3df549159685a1061ae2930ab1644
review_base_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
reviewed_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
source_change_in_review: NO
protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip_reference_only: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
source_semantic_review_checkpoint: review-runs/checkpoints/2026-10-12__db339e1__reviewer-window01-restore01-independent-semantic-verification.md
earlier_evidence_sufficiency_checkpoint: review-runs/checkpoints/2026-10-12__db339e1__scheduler-window-restore-test-evidence-sufficiency-clarification.md
current_agent_report_reviewed: BUG_SCHEDULER_01_V3_PUBLICATION_AND_EXACT_SHA_VERIFICATION_REPORT.md
user_supplied_report_sha256: 6ee8e478ea8c82a52b743094affa0255d4e993d3afff259d4683754ecf137d2e
original_agent_report_provenance: C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-v3-alarm-window-20261011-86f87dee/BUG_SCHEDULER_01_V3_PUBLICATION_AND_EXACT_SHA_VERIFICATION_REPORT.md
reviewer_independent_execution: NOT_EXECUTED

## Canonical decision and scope

Independent reviewer result on published `db339e1e90392cfd994e2d42bca7ce88afb3be21`:
- `BUG-SCHEDULER-WINDOW-01`: **VERIFIED_CLOSED**, P2, formerly REOPENED_OPEN; finding's old failure path source-fixed, exact committed-SHA production-wiring execution evidence sufficient.
- `BUG-SCHEDULER-RESTORE-01`: **VERIFIED_CLOSED**, P2, formerly VERIFIED_OPEN; finding's old failure path source-fixed, exact committed-SHA Restore/Reset/WorkManager execution evidence sufficient.
- These are **two preexisting distinct semantic roots**, not aliases; count delta is **-2 OPEN P2, +2 CLOSED**. No new root and no implementation correction is being requested. All six other active download roots remain OPEN.
- `BUG-SCHEDULER-04` (tag-wide cancellation of independent work), `BUG-SCHEDULER-06` (due-time claim authority), and other separately confirmed scheduler findings remain distinct; these closures neither resolve nor suppress them.
- Active remediation `DOWNLOAD_ACTIVE_BLOCKER_CLOSURE`: P0=0, P1=0, **P2=6** (formerly 8). Repository semantic root totals unchanged at 148 discovered, **55 OPEN**, **93 CLOSED** (formerly 57/91). Historical audit provenance is immutable.
- Repository-wide verdict remains **NOT_CLEAN**; `CLEAN_REVIEW_BASIS=NONE_REPOSITORY_WIDE_CLEAN_REVOKED`. No new baseline, no Master Plan or ledger mutation.

## Why report evidence is closure-grade without duplicating execution

The reviewer directly read the user-supplied V3 final report, **not merely the earlier 152-total summary**. That report identifies exact clean committed and pushed source `db339e1`, its committed tree `5fbe5eb5c02a02f8bf1ebbd703bebc340d3d125e`, pre-push committed-SHA execution permitted by protocol §16.3, the actual app APK SHA-256 `6bc9dfbdc4b926c9c92193992fbeb06fa069196974b36254f736f0f3c3143ba0` and test APK SHA-256 `5a28571590340e2d4bf698a8b15f3f20dc2a02ee6daa27de92364744dc11dd1f`, matching installed debug package/runner on emulator-5554 API 36. It reports the app-UID `AlarmManager.canScheduleExactAlarms()` was actually true for the capability-dependent tests, and the original exact-alarm permission was restored once after the gate; package mode returned to `default`. The reviewer independently verified authoritative GitHub implementation HEAD equals the reported exact committed SHA and `c34b8aa..db339e1` is one normal compatible forward commit with five matching changed production/test paths; no fresh implementation change intervened.

The report explicitly distinguishes 17 prepublication preliminary executions from the **152 fresh unique exact-final-SHA methods** (57 JVM and 95 Android), all PASS, 0 FAIL, 0 SKIP, 0 TIMEOUT. Exact-final partitions: JVM 57, window/END 7, scheduler/Restore 36, handoff/mutation admission 29, ownership/restart/recovery 23. It asserts source-method identity matching, native Android exit 0, nonzero per-class/test filter execution, command preconditions, and governing coverage. This is implementation-agent execution evidence accepted under protocol §16.3, **not independent reviewer-run tests**. Original raw XML/logs were not separately re-executed or independently hash-verified by this reviewer; §§11/16.3 do not mandate a gratuitous reread of all raw XML when the supplied original completion report and source-level semantic trace are enough for this exact-root closure. The uploaded completion report is treated as reporting evidence, not absolute proof that any other unrelated root is correct.

## WINDOW-01: independent producer-to-effect closure

Original false-closure root from `2026-10-06__eda6a758__manual-v7-l6-repository-wide-reopen-final.md`: inclusive end-minute `contains()` remained true until HH:mm:59.999, but END AlarmManager/WorkManager handoff had already fired at HH:mm:00. Old passed tests stopped at helper Calendar arithmetic and missed the real in-minute Active owner effect.

On the **current exact source**, `ScheduledDownloadWindow.contains` retains inclusive minute membership, while `nextEnd` adds exactly one minute to end-day boundary, including 23:59 rollover and normalization of seconds/milliseconds. `AlarmScheduler.scheduleWithinOrdinaryMutation` passes the exact same computed END timestamp to the persistent `prepareSchedulerBoundaryWithinOrdinaryMutation` and `setAlarm` publication. Both actual receivers carry exact handoff identity to WorkManager. `DownloadWorker` uses that same active-window predicate, and `CancelScheduledDownloadWorker` validates current accepted request/boundary before END stop/requeue; actual release/convergence remains separately guarded by execution identity.

The submitted V3 report identifies actual executed `ScheduledDownloadWindowProductionWiringTest` instrumentation command, all **5 preliminary methods PASS** and **7 exact-SHA window/END owner methods PASS**, plus **16 JVM window** tests. Specifically `realQueueClaimsInInclusiveEndMinuteAndRealEndWorkerStopsAtFollowingMinute` executed with app-UID exact-alarm capability **true** and asserted real queue admission as Active inside inclusive end minute, actual following-minute END owner, stopped/requeued state, release of exact process/execution ownership and future START durable carrier. The reviewer directly inspected test source (not just its title) and verified these are real producer/consumer/final-effect assertions. These facts close the precise previously reopened failure, not an unrelated scheduler-root symptom.

WINDOW-01 disposition: **VERIFIED_CLOSED at db339e1**. No additional test or full raw-log transfer justified for this root.

## RESTORE-01: independent producer-to-effect closure

Original distinct root: portable scheduler settings could bypass HH:mm/type/domain validation, then persist an enabled/disabled/changed scheduler image without matching AlarmManager/WorkManager owner revocation/republication/restart convergence.

Exact source reviewed through `BackupRestoreParser.parse/fromTyped/validatePlan/validateSettings`, `SchedulerSettingsValidation.validatePortable` (strict portable HH:mm/time range/type/Boolean), `SettingsViewModel.restorePlan` and real Merge admission, `RestoreTransactionCoordinator` durable prepared journal/atomic preference effect/reconciliation phases, `SchedulerSettingsTransitionCoordinator.prepareRestoredPreferences` durable intent-before-target preference publication and progress flags, `AlarmScheduler.applyRestoredSchedulerEffectWithinMutation` old owner cancellation then separately replayable START/END publication, `WorkManagerHandoffRecovery` real exact request/cancel/acceptance and worker freshness checks, and `App.onCreate` recovery ordered Restore→scheduler transition→handoff. Interrupted or missing WorkManager effect retains a durable retry/replay owner; Reset omission of settings preserves earlier scheduler owner.

V3 exact-SHA report records **36/36 scheduler/Restore Android tests PASS**, plus **29/29 handoff/mutation-admission**, **23/23 owner/restart/recovery**, **6/6 JVM settings validation**. It expressly confirms previously interrupted source-bound Reset ran again on the exact final SHA with observed publication hook, expected competing-writer rejection, Reset `Completed`, final matching carrier; no stale V2 dirty/pre-SHA success was substituted. Independently reviewed production-wiring test source includes enabled/disabled Reset, enabled from prior disabled settings, partial Merge retaining other setting, malformed portable settings rejected before mutation, interrupted-effect restart replay, retained START until END publication, fallback enqueue failure followed by real WorkManager acceptance, stale receivers and failed/cancelled old E1 vs restored E2, and ordinary-vs-Restore competing writes. The reported full 152 test union includes those governing classes with zero skips/failures; no new production-semantic residual of the original RESTORE-01 root was established.

RESTORE-01 disposition: **VERIFIED_CLOSED at db339e1**. No additional test or full raw-log transfer justified for this root.

## Governance, independent-execution honesty and next step

Unlike agent verdicts, these **individual CLOSED decisions are made by the independent reviewer** after reading exact remote production/test composition and the supplied detailed V3 original report. `INDEPENDENT EXECUTION: NOT EXECUTED` means reviewer did not personally run Gradle/instrumentation; it does not erase the properly attributed existing agent execution evidence. No bug count is changed twice for subcases. Preserve historical reopened false-closure evidence in the inventory index and postmortem even after closing on later SHA.

Update current derived/canonical review inventory and repository finding index, then private handoff to exactly six remaining active P2 roots (FORMAT-BG-01..05 and INCOGNITO-01), keeping their original OPEN severity/status and leaving all governance source/Master Plan/ledger untouched. Next reviewer-owned action: independently inspect the next dependency-eligible FORMAT-BG-01 root against published `db339e1`, then author/persist a narrow repair prompt only when its source contract is independently stable. No implementation agent active and no prompt currently launch-ready.

independent_verdict: TWO_INDIVIDUAL_ROOT_CLOSURES_REVIEWED
new_roots: 0
same_root_residuals: 0_FOR_WINDOW01_AND_RESTORE01
canonical_active_p2_before: 8
canonical_active_p2_after: 6
repository_open_before: 57
repository_open_after: 55
repository_closed_before: 91
repository_closed_after: 93

INDEPENDENT EXECUTION: NOT EXECUTED
