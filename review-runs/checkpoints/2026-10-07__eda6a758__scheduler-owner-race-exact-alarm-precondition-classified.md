# Scheduler owner-race focused queue failure — exact-alarm capability precondition classification

Date: 2026-10-07

record_kind: IMPLEMENTATION_STOP_REPORT_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: c8568837540bca85bed7a68af5a14d9055a94379
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
remote_implementation_changed: NO
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8
canonical_clean_status: NOT_CLEAN

## Stop report received

The verification continuation stopped at its required first-failure boundary.

Recovered/sealed verification:
- preserved JVM results recovered without rerun: 8 executed, 8 PASS, 0 FAIL, 0 skipped;
- Android owner-race regressions: 2 executed, 2 PASS, 0 FAIL, 0 skipped;
- focused scheduler gate: 5 executed, 4 PASS, 1 FAIL, 0 skipped.

Focused success evidence before the queue assertion:
- scheduler END succeeded;
- END handoff carrier disappeared;
- both exact E1 execution owners were absent.

The first failure was the post-END queue step at line 404. The queue result carried the exact-alarm
permission message and runtime capability observation canSchedule=false.

The diagnostic caller was reported as a fresh id=0 item with no persisted Room row and no recovery carrier.
That exact evidence rejects the previously authorized stale-input exception.

Preserved local candidate:
- HEAD eda6a7589af3a19a97eb38e869b47dabaf74388b
- committed tree ca8d9b8af59d03681663de9ac9588b6e6f5e3e4a
- parent db29f63ce169176b4c8ade4cec01f66cc0307ec8
- dirty tree 7795ce45446bbeb627e803021b79d71d3549b34d
- 19 unstaged paths
- empty index
- no new source edits after the stop
- no commit or publication

Evidence report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-owner-race-verify-20261007-EyjFn2/SCHEDULER_OWNER_RACE_VERIFICATION_STOP_REPORT.md

Reported cumulative sealed evidence: 22,104 records plus the separate storage audit.
Five protected parallel worktrees remained unchanged.

## Independent exact-source classification

This focused failure does NOT establish a residual execution-owner defect.

At exact remote source eda6a758:
- DownloadViewModel.queueDownloads reads use_scheduler;
- when scheduler is enabled and current time is outside the scheduled window, queue publication requires
  AlarmScheduler.canSchedule();
- if canSchedule() is false, queueDownloads invokes disableForImmediateQueueStart(), returns succeeded=false,
  and returns the enable_alarm_permission message instead of publishing the fresh item;
- AlarmScheduler.canSchedule() on API 31+ requires AlarmManager.canScheduleExactAlarms() == true;
- AndroidManifest.xml declares android.permission.SCHEDULE_EXACT_ALARM;
- DownloadSettingsFragment independently refuses enabling the scheduler on API 31+ when canSchedule() is false
  and launches Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM.

Therefore the observed queue refusal exactly matches the current production capability contract.

The fresh id=0/no-Row/no-recovery evidence additionally proves that the failure occurred before any
execution-owner/recovery refusal relevant to the active E1 race.

Classification:
FOCUSED_QUEUE_FAILURE=TEST_DEVICE_CAPABILITY_PRECONDITION_NOT_ESTABLISHED
OWNER_RACE_RESIDUAL_FROM_THIS_FAILURE=REJECTED
NEW_PRODUCTION_ROOT_FROM_THIS_FAILURE=NOT_ESTABLISHED
HARNESS_STALE_INPUT_CONTRACT=REJECTED_FOR_THIS_FAILURE

This classification does not close the local production correction because it is still unpushed and the
focused queue success path has not yet been executed under a capability-valid environment.

## Authorized continuation boundary

No new production source edit is authorized.

A single focused rerun is authorized only after a materially different, explicitly proven device
precondition:
- record API level/package/device identity;
- record the current exact-alarm special-access state;
- establish exact-alarm capability for the test package through a reversible device/environment operation
  consistent with the manifest-declared SCHEDULE_EXACT_ALARM contract;
- prove AlarmManager.canScheduleExactAlarms() / the app's canSchedule() is true before the queue diagnostic;
- preserve the prior device capability state for later restoration.

Do not weaken queueDownloads, bypass production canSchedule(), modify owner assertions, or convert the
permission refusal into success.

Do not modify the test merely to skip the capability branch when the focused test is intended to prove the
real post-END scheduler queue path. Prefer satisfying the real platform precondition.

If exact-alarm capability cannot be established safely and reversibly, STOP as an environment blocker.

After capability is proven, rerun only the focused scheduler partition once:
- owner/carrier assertions must remain green;
- post-END queue must succeed;
- any failure after capability=true must be captured exactly and STOPped for independent classification.

Only after that focused gate is fully green may the original broader prepublication gates continue.
JVM 8/8 and Android owner-race 2/2 need not be repeated before broader verification because their exact
sealed results were recovered from the preserved unchanged candidate; they remain subject to exact-final-SHA
closure after publication.

Publication remains forbidden until all required broader prepublication gates pass and exact source/scope
audit is green. Normal fast-forward only. Exact-published-SHA closure remains mandatory.

INDEPENDENT_REVIEW_REQUIRED=YES
