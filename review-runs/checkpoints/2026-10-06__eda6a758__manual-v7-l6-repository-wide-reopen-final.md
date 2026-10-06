# Manual correctness review — eda6a758 — repository-wide L6 propagation / scheduler reopen

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 0d6647817580aea875c9ab57a873ff2568600009
review_parent_sha: 0d6647817580aea875c9ab57a873ff2568600009

implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
implementation_parent_sha: db29f63ce169176b4c8ade4cec01f66cc0307ec8
implementation_delta: FOUR_SCHEDULER_FILES
implementation_changed_files:
- app/src/main/java/com/ireum/ytdl/work/AlarmScheduler.kt
- app/src/main/java/com/ireum/ytdl/work/ScheduledDownloadWindow.kt
- app/src/test/java/com/ireum/ytdl/work/ScheduledDownloadWindowTest.kt
- app/src/androidTest/java/com/ireum/ytdl/work/ScheduledDownloadWindowProductionWiringTest.kt

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
plan_head_observed: 8491528b730abea17de22013bca4288e1549a39e
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
ledger_head_observed: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d

overall_verdict: NOT_CLEAN
canonical_p0: 0
canonical_p1: 1
canonical_p2: 13
canonical_open_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-SCHEDULER-WINDOW-01,BUG-SCHEDULER-RESTORE-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01,BUG-APP-UPDATE-01,BUG-APP-UPDATE-02,BUG-PLAYLIST-DELETE-01,BUG-COOKIE-RESTORE-01
new_finding_ids: BUG-SCHEDULER-RESTORE-01
reopened_finding_ids: BUG-SCHEDULER-WINDOW-01
independent_execution: NOT_EXECUTED

active_download_blockers: BUG-SCHEDULER-WINDOW-01,BUG-SCHEDULER-RESTORE-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01
deferred_non_download_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-APP-UPDATE-01,BUG-APP-UPDATE-02,BUG-PLAYLIST-DELETE-01,BUG-COOKIE-RESTORE-01
deferred_non_download_policy: RECORDED_NOT_REQUIRED_BEFORE_PO_TOKEN
po_token_resume_condition: ALL_ACTIVE_DOWNLOAD_BLOCKERS_CLOSED

superseded_scheduler_closure_checkpoint: review-runs/checkpoints/2026-10-06__eda6a758__bug-scheduler-window-01-exact-sha-closure-reviewed.md
supersession_reason: FRESH_MANUAL_REVIEW_PROVED_SAME_ROOT_END_MINUTE_CONTRACT_RESIDUAL
scheduler_prior_reported_execution: 45_JVM_PASS_24_ANDROID_PASS_0_FAILURES
scheduler_prior_execution_disposition: SUPPORTING_EVIDENCE_ONLY_TESTS_ENCODED_INCONSISTENT_END_MINUTE_CONTRACT

## Independent verdict

eda6a758 is NOT_CLEAN.

The published scheduler correction fixes the original post-midnight circular-day arithmetic defect and normalizes boundary Calendar seconds/milliseconds, but the prior exact-SHA closure overclaimed full closure.

Fresh manual review proves one same-root residual: the membership predicate includes the entire configured end minute while the externally published end boundary is the beginning of that same minute. The two authorities therefore disagree for almost the entire end minute.

Fresh triggered Module H review also establishes one distinct P2 root: portable backup/restore can write scheduler configuration through the generic preference path without scheduler-domain validation or matching external AlarmManager/WorkManager authority publication.

The six pre-existing non-scheduler download blockers remain open because the implementation delta changes only the scheduler four-file set. Deferred repository-wide roots likewise remain open.

## L1-L6 baseline

lens_coverage_current_sha:
- L1: BASELINE_FAIL
- L2: BASELINE_FAIL
- L3: BASELINE_FAIL
- L4: BASELINE_FAIL
- L5: BASELINE_FAIL
- L6: DEEP_FAIL

primary_deep_lens: L6 Cross-feature semantic propagation
primary_deep_selection_reason: R1/R3 — the material changed scheduler contract fans from persisted configuration through AlarmScheduler and durable handoff into queue admission, worker stop/start, ObserveSource scheduling, AlarmManager end publication and Restore effects; fresh review found both a same-root final-effect residual and a distinct restore propagation root.
remaining_not_yet_deep: L1,L2,L3,L4,L5
next_not_yet_deep_lens: L5 Platform contract closure
next_lens_selection_reason: R1/R2 — the reopened scheduler boundary directly crosses AlarmManager time semantics and the new restore root crosses portable configuration/platform handoff; existing APP-UPDATE roots also remain L5-owned.

L1 BASELINE_FAIL:
- BUG-PLAYER-01 still uses lifecycle-scoped unordered persistence with no application-scoped durable sequencing owner.
- BUG-FORMAT-BG-02 still mutates durable Download state before observed WorkManager acceptance and has no exact durable format-batch recovery owner.
- BUG-APP-UPDATE-02 still has no durable update-download completion owner.
- BUG-COOKIE-RESTORE-01 still allows ordinary cookie mutation outside Restore admission.
- BUG-SCHEDULER-RESTORE-01 persists restored scheduler preferences without a durable scheduler-effect transition owning matching external state.

L2 BASELINE_FAIL:
- CURRENT-PLAYER-TIMELINE-INDEX still dereferences callback-old index against the current player timeline.
- BUG-FORMAT-BG-01/04/05 retain widened or stale identity/cancellation authority.
- BUG-INCOGNITO-01 still collapses ALL semantics into ANY-count semantics.
- BUG-APP-UPDATE-02 still ignores DownloadManager request identity.
- BUG-SCHEDULER-RESTORE-01 permits restored preference identity to diverge from the still-live old scheduler carrier/alarm identity.

L3 BASELINE_FAIL:
- existing player/format/app-update/cookie concurrency roots remain.
- BUG-SCHEDULER-RESTORE-01 allows a restored scheduler preference image and a stale pre-restore external scheduler owner to coexist without one winning transfer protocol.

L4 BASELINE_FAIL:
- BUG-PLAYLIST-DELETE-01 remains a non-transactional destructive graph delete.
- BUG-COOKIE-RESTORE-01 remains competing destructive cookie ownership between authoritative Restore reset/import and ordinary mutation.

L5 BASELINE_FAIL:
- BUG-APP-UPDATE-01/02 remain open platform/representation roots.
- BUG-SCHEDULER-WINDOW-01 has an AlarmManager timestamp/predicate semantic mismatch at the end minute.
- BUG-SCHEDULER-RESTORE-01 admits generic restored strings without scheduler time-domain validation.

L6 DEEP_FAIL:
- scheduler semantics propagate to DownloadViewModel, DownloadWorker, ObserveSourceWorker, AlarmManager, WorkManager handoff recovery and Restore.
- existing background-format, incognito/History, player/History, app-update and cookie/Restore propagation failures remain.
- no additional root beyond BUG-SCHEDULER-WINDOW-01 residual and BUG-SCHEDULER-RESTORE-01 was established in this run.

## Trigger map

### Semantic-contract delta / consumer closure

trigger: YES
changed_boundary: ScheduledDownloadWindow.contains + nextStart/nextEnd consumed by AlarmScheduler
old_contract: ad-hoc hour arithmetic with overnight failure and asymmetric second normalization
new_intended_contract: one circular minute window with coherent exact external start/end authority

final_discovered_production_consumers:
- DownloadViewModel.queueDownloads scheduler admission
- DownloadWorker scheduler stop/admission
- ObserveSourceWorker membership requeue scheduler branch
- ObserveSourceWorker ordinary queue publication scheduler branch
- AlarmScheduler.scheduleWithinOrdinaryMutation exact AlarmManager start/end publication
- WorkManagerHandoffRecovery scheduler notBeforeAt fallback publication
- scheduler settings transition/replay
- backup/restore scheduler preference publication and post-restore reconciliation

consumer_closure_status: FAIL
authority_effect_closure_status: FAIL
reason:
- predicate and end AlarmManager final effect disagree within the configured end minute;
- Restore can change scheduler preferences without publishing/cancelling the corresponding external scheduler owner.

### Module A — platform capability / exact alarm admission

trigger: YES
status: PASS_NO_NEW_ROOT
evidence:
- canSchedule() keeps explicit API-level exact-alarm capability admission.
- no fresh permission/capability bypass was established.

### Module B — external scheduler handoff

trigger: YES
status: FAIL
evidence:
- scheduler start/end are durably carried through WorkManagerHandoffCarrier and exact request identity.
- end-boundary notBeforeAt inherits the inconsistent beginning-of-end-minute timestamp.
- restored scheduler state can leave old external carrier/alarm authority unmatched to the restored preference image.

### Module H — persisted executable configuration fan-out

trigger: YES
status: FAIL
evidence:
- schedule_start, schedule_end and use_scheduler are portable settings.
- TimePicker UI produces canonical HH:mm and uses SchedulerSettingsTransitionCoordinator.
- BackupRestoreParser validates generic String/Boolean type only for scheduler keys; no HH:mm shape/range rule exists.
- generic merge/reset restore writes these keys directly.
- post-commit Restore supersedes an old scheduler transition record but does not publish the restored scheduler enable/time effect.
- malformed or out-of-range restored strings may persist and later reach ScheduledDownloadWindow parsing/Calendar materialization.

### Other conditional modules

Module C: NOT_TRIGGERED by the scheduler delta beyond the already-reviewed portable settings path.
Module D: NOT_TRIGGERED.
Module E: NOT_TRIGGERED.
Module F: NOT_TRIGGERED by this delta; no new generation marker/sentinel was introduced.
Module G: NOT_TRIGGERED.
Module I: NOT_TRIGGERED as a new maintenance namespace root.

## Finding — reopened P2 BUG-SCHEDULER-WINDOW-01

status: REOPENED_OPEN_P2_SAME_ROOT_RESIDUAL
root: END_MINUTE_MEMBERSHIP_AND_EXTERNAL_END_PUBLICATION_DISAGREE

Current source:
- contains() compares only minute-of-day and includes endMinute.
- tests explicitly assert 05:00:59.999 is inside a 22:00..05:00 window.
- nextEnd() sets the configured end to HH:mm:00.000.
- at 05:00:43.987, a committed test explicitly expects nextEnd() == 05:00:00.000.

Concrete production contradiction:
1. configured window 22:00..05:00;
2. end AlarmManager boundary fires at 05:00:00 and its durable fallback uses the same timestamp;
3. at 05:00:30, isDuringTheScheduledTime() still returns true;
4. DownloadViewModel can therefore immediately start newly queued work after the end owner already fired;
5. DownloadWorker likewise treats that instant as inside the window;
6. no matching end boundary remains later in that same minute.

Scheduling during the end minute is also contradictory: nextEnd(now=05:00:43) returns a timestamp already in the past while contains(now) says the schedule is active.

This is the same scheduler-window semantic root, not a new count beyond reopening BUG-SCHEDULER-WINDOW-01.

Required correction boundary:
- preserve the already-established inclusive-minute/equal-boundary-one-minute product contract unless an explicit product decision changes it;
- publish the end effect at the first instant after the inclusive end minute, including 23:59 -> next-day 00:00;
- ensure nextEnd never returns a past instant while contains(now) is true;
- prove predicate, AlarmManager timestamp, durable handoff notBeforeAt and worker/queue final effects use one contract;
- cover start=end as exactly one minute.

Required focused acceptance:
- end minute at :00.000, :00.001, :59.999 and following minute;
- overnight, same-day, end=23:59 and start=end;
- scheduling invoked during the end minute;
- DownloadViewModel and DownloadWorker production-wiring behavior around the end boundary;
- AlarmManager publication and handoff timestamp equality.

## New P2 BUG-SCHEDULER-RESTORE-01

status: OPEN_P2
root: RESTORED_SCHEDULER_CONFIGURATION_BYPASSES_DOMAIN_VALIDATION_AND_EXTERNAL_AUTHORITY_PUBLICATION

Producer path:
portable backup settings
-> BackupRestoreParser.normalize/validateSettings
-> generic SharedPreferences restore
-> scheduler_start/scheduler_end/use_scheduler become durable preference image.

Current validation:
- schedule_start/end are portable.
- generic validation proves only type=String.
- no exact HH:mm range validation is applied to scheduler keys.
- updateScheduleBoundary's TimePicker-derived canonical input path is bypassed.

Current external-authority gap:
- merge/reset restore can publish a changed scheduler preference image.
- Restore post-commit supersedes a pre-existing SchedulerSettingsTransitionCoordinator record.
- it does not run a restore-authorized equivalent of the scheduler effect to cancel old alarms/handoffs or publish the restored enabled schedule.
- WorkManager scheduler request validation is exact to its carrier/request generation but does not prove that carrier matches the newly restored scheduler preference image.

Concrete outcomes:
- restore use_scheduler=false while an old scheduled start/end owner exists -> old external scheduler work may remain capable of firing after the restored preference says scheduling is disabled;
- restore different schedule_start/end -> old external times can remain in force until an unrelated future reschedule;
- restore malformed schedule string -> generic restore can commit it, and later scheduler parsing may throw or create out-of-domain Calendar behavior.

Required correction boundary:
- validate portable scheduler settings under a scheduler-owned exact HH:mm contract: two digits, colon, hour 00..23, minute 00..59, and Boolean use_scheduler;
- reject invalid scheduler configuration before it becomes executable authority;
- after restored preference state wins, use a restore-authorized scheduler effect that deterministically supersedes/cancels old alarm/handoff authority and publishes the restored schedule when enabled, or leaves no live scheduler owner when disabled;
- cover both merge and durable reset/recovery paths without recursively acquiring ordinary Restore admission;
- preserve exact request/generation identity and restart convergence.

Required focused acceptance:
- valid changed start/end while enabled;
- enabled -> disabled;
- disabled -> enabled;
- stale old AlarmManager/WorkManager carrier cannot fire after restore wins;
- process death around restored preference publication and external effect;
- malformed and out-of-range scheduler strings are rejected before ownership;
- exact restored AlarmManager timestamps and durable handoff timestamps agree.

## Existing findings continuity

CURRENT-PLAYER-TIMELINE-INDEX remains OPEN_P1:
onPositionDiscontinuity still uses oldPosition.mediaItemIndex with player.getMediaItemAt() on the current timeline.

BUG-PLAYER-01 remains OPEN_P2:
savePlaybackPositionForHistoryId still launches independent lifecycleScope IO writes; lock acquisition serializes execution but not logical submission order, and Activity destruction owns the scope.

BUG-FORMAT-BG-01 remains OPEN_P2:
continueUpdatingFormatsOnBackground still snapshots allProcessing, derives selected ids, then invokes a live broad Processing->Saved transition.

BUG-FORMAT-BG-02 remains OPEN_P2:
the same producer still ignores WorkManager enqueue Operation.result and retains no exact durable format-batch recovery carrier.

BUG-FORMAT-BG-03 remains OPEN_P2:
UpdateMultipleDownloadsFormatsWorker still discards foreground promotion completion, swallows per-item failures through ignored runCatching, advances progress and can return aggregate success.

BUG-FORMAT-BG-04 remains OPEN_P2:
the worker still fetches from a pre-fetch full Download snapshot, then copies newer status/execution authority into that stale payload and performs a full-row publication.

BUG-FORMAT-BG-05 remains OPEN_P2:
format work is tagged updateFormats, while the notification Cancel action sends the worker class name to CancelWorkReceiver.cancelAllWorkByTag; the initial foreground notification has no exact cancel capability.

BUG-INCOGNITO-01 remains OPEN_P2:
areAllProcessingIncognito still implements ANY via count>0 while the UI consumes it as ALL; DownloadWorker still persists History for each false row.

BUG-APP-UPDATE-01 remains OPEN_P2 deferred:
dotted version components are flattened before integer comparison.

BUG-APP-UPDATE-02 remains OPEN_P2 deferred:
the DownloadManager request id returned by enqueue is ignored; the receiver consumes any ACTION_DOWNLOAD_COMPLETE and has no durable restart owner.

BUG-PLAYLIST-DELETE-01 remains OPEN_P2 deferred:
playlist cross refs, playlist endpoint and group membership are deleted as separate durable statements rather than one Room transaction.

BUG-COOKIE-RESTORE-01 remains OPEN_P2 deferred:
ordinary CookieRepository/CookieProjectionCoordinator mutation does not participate in RestoreMutationAdmission while authoritative Restore resets/imports cookies.

## Terminal / cross-attempt / live-owner matrix

BUG-SCHEDULER-WINDOW-01:
- before end minute -> active and end owner pending: coherent.
- exactly end minute start -> predicate active while end owner fires: FAIL.
- later in end minute -> predicate active after end owner already fired: FAIL.
- following minute -> predicate inactive: coherent only after the inconsistent gap.
- schedule() during end minute -> can publish a past end timestamp while reporting active: FAIL.

BUG-SCHEDULER-RESTORE-01:
- no old owner + restored enabled -> new external owner is not guaranteed to be published: FAIL.
- old owner A + restored owner/config B -> A is not proven revoked/replaced by B: FAIL.
- restored disabled + old owner A -> preference says disabled but A can remain external authority: FAIL.
- process death after restored preferences but before matching scheduler effect -> no dedicated durable scheduler-effect owner proven: FAIL.
- malformed restored time -> durable executable config can be accepted without domain validation: FAIL.

## Verification evidence

Independent execution: NOT_EXECUTED.

Previously reported scheduler exact-final execution:
- 45 JVM PASS
- 24 Android PASS
- 0 failures
- 0 skips

That evidence does not close the fresh residual because the committed tests themselves encode both sides of the contradiction:
- membership includes 05:00:59.999;
- nextEnd during 05:00:43.987 is asserted as 05:00:00.000.

No current focused production-wiring test was found that asserts the end AlarmManager effect and queue/worker membership decision remain coherent throughout the end minute.

No current focused scheduler-restore test was found proving restored schedule settings validate exact time domain and atomically/recoverably replace external scheduler authority.

## Review retrospective

The prior scheduler closure correctly checked circular minute arithmetic, normalized Calendar fields and the unchanged consumer set, but stopped one edge too early.

It accepted helper-level facts:
- end minute is inclusive;
- published end timestamp is normalized to the beginning of that minute;

without composing them into the final-effect timeline. The existing test named currentBoundaryMinuteKeepsExistingSameDaySelection actually preserves the contradiction instead of proving closure.

The manual run also shows why Module H must follow persisted configuration through import/restore, not only through the normal UI writer. The TimePicker path is canonical, but portable restore bypasses that writer and therefore requires its own domain validation and external-effect convergence.

## Checklist evolution

checklist_change_required: NO

The findings are already directly covered by existing v7 rules:
- semantic-contract delta / consumer closure / authority-effect closure;
- asynchronous request/acceptance/completion;
- exact identity and live-owner review;
- Module B external scheduler handoff;
- Module H persisted executable configuration fan-out;
- final-checkpoint production-wiring evidence.

No new checklist rule is justified. The corrective lesson is application depth: pair predicate tests with the actual external boundary effect, and trace portable configuration through restore to executable authority.

## Final recount

canonical_p0: 0
canonical_p1: 1
canonical_p2: 13

canonical_open_roots:
- CURRENT-PLAYER-TIMELINE-INDEX
- BUG-PLAYER-01
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01
- BUG-APP-UPDATE-01
- BUG-APP-UPDATE-02
- BUG-PLAYLIST-DELETE-01
- BUG-COOKIE-RESTORE-01

active_download_blockers:
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

manual_review_status: FINAL
current_sha_deep_lenses: L6
remaining_not_yet_deep: L1,L2,L3,L4,L5
next_hint: L5_PLATFORM_CONTRACT_CLOSURE

next_governed_action:
- supersede the pending FMT-PRODUCER launch routing;
- correct BUG-SCHEDULER-WINDOW-01 residual and BUG-SCHEDULER-RESTORE-01 as one coherent scheduler-authority wave while keeping their closure verdicts distinct;
- require exact-final-SHA focused JVM + production-wiring/device evidence;
- only after both scheduler blockers close return to FMT-PRODUCER BG-01/BG-02.

INDEPENDENT_REVIEW_REQUIRED=YES
