# BUG-SCHEDULER-01 V2 stop — exact candidate Reset passes; window blocked by denied exact-alarm capability

Date: 2026-10-11
record_kind: COMPLETED_IMPLEMENTATION_STOP_REPORT_RECONCILIATION
record_status: FINAL
manual_review_run: NO
review_parent_sha: 8c14e04873134d575752925df76aa4279e4f121a
reviewed_remote_implementation_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
overall_verdict: NOT_CLEAN
existing_root: BUG-SCHEDULER-01 OPEN P2 SAME_ROOT
new_root_count_delta: 0
active_download_count_delta: 0
implementation_publication: NOT_PUBLISHED
source_semantic_closure: NOT_VERIFIED
INDEPENDENT EXECUTION: NOT EXECUTED

## Implementation-agent stop report and evidence boundary

The user supplied the V2 exact-candidate stop report, with referenced sealed local file:
`C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-v2-closure-20261011-93927246/BUG_SCHEDULER_01_V2_SOURCE_BOUND_RESET_AND_WINDOW_STOP_REPORT.md`.

According to this explicit completion/stop report, the source review supports a narrow scheduler accepted-terminal FAILED/CANCELLED retry through the existing exact-request retry CAS; settings/Restore supersession and successful semantic retirement retain their expected fences. This is reported *local unpublished source review*, not an independent reading of that five-file dirty diff or a published source fix.

A source-bound corrected `F11HandoffCarrierMutationAdmissionProductionWiringTest#schedulerResetWinsBeforeOrdinaryCarrierMutation` executed once, 1 PASS / 0 FAIL / 0 SKIP, exact runner `OK (1 test)`, process exit code 0. The agent reports the logs show authoritative Reset publication, expected competing-writer rejection, `RestoreOutcome.Completed`, and final carrier assertions. Therefore the original Reset *test harness* residual is verified closed **within the reported local-candidate evidence**. Unlike the preceding installed-APK pass without binding evidence, the new stop report explicitly asserts source-bound execution; this review has not independently re-read the sealed log/APK hashing.

The `ScheduledDownloadWindowProductionWiringTest` gate did not start: 0 tests / HARNESS_BLOCKED. The application's own UID-bound `AlarmManager.canScheduleExactAlarms()` reported `false` on the target environment. This is an unmet platform/device capability precondition, not a demonstrated scheduler production assertion failure. No permission change, test rerun, rebuild, install or source/index/APK mutation took place.

The agent reports `git diff --check` PASS and evidence manifest verification of 119 new + 113 historical files = 232 checked, 0 mismatches. Protected state remained:
- committed HEAD c34b8aa57e01803c9960e4ad873d1ed5b68e019c;
- uncommitted tracked dirty tree `5fbe5eb5c02a02f8bf1ebbd703bebc340d3d125e`;
- five modified tracked paths and empty index;
- five protected worktrees; four unknown-origin cache items; both APKs.

Publication, commit creation, full exact-final-committed-SHA closure remain NOT_REACHED. Historic `WinError 5` causation remains NOT_VERIFIED. This review does not treat 1/1 Reset, earlier 12/12, or local source semantics as final remote closure.

## Independent exact-source and platform classification

At remote c34b8aa57e01803c9960e4ad873d1ed5b68e019c, `app/src/main/java/com/ireum/ytdl/work/AlarmScheduler.kt` `canSchedule()` on API >=31 depends on actual `AlarmManager.canScheduleExactAlarms()` from the application context; its manifest declares `android.permission.SCHEDULE_EXACT_ALARM`. `ScheduledDownloadWindowProductionWiringTest.kt` exercises real schedule/window/queue authority. This matches the previously independently classified `2026-10-07__eda6a758__scheduler-owner-race-exact-alarm-precondition-classified.md` platform precondition. A returned false does not establish an app source bug or authorize bypassing this gate. A test skipped before method invocation is zero execution, never semantic PASS or FAIL.

## Separate authorization for exact platform precondition

The reviewer authorizes a narrow follow-up **conditional, reversible device-only exact-alarm special-access preparation**, then actual focused window verification, followed by preauthorized downstream exact-SHA closure only if all gates pass. Never directly alter source, APK, Git, host security or broad Android device settings merely to make canSchedule true. Precise package/UID/emulator, existing permission/appop mode, ability to restore, and installed test package source binding are hard preflight gates. Use the documented Android special-app-access setting or its exact scoped Android appop only if supported and restorable with a recorded old mode; no unrestricted ADB root, permission whitelist, elevated host privileges, forced process kill, install/uninstall, clear data, snapshot erase, or app/OS-wide policy modification. Proof requires the *application UID* API to return true, not just a shell appops output or package-name assumption.

Because revoking exact-alarm permission can stop the target app and cancel alarms, restoration itself may have non-reversible scheduler side effects; record those effects and STOP rather than asserting perfect reversibility. Restore only when an exact, safe recorded prior permission mode can be returned without destroying protected test/evidence state; if not, STOP and report state, requesting explicit next authorization. Avoid automatic permission toggle cycles and repeated assertion runs. Keep any device-only permission manipulation bounded to the authorized test package and selected existing emulator, preserving existing data and other apps. Do not change a user's production handset.

If the app-UID capability can be established safely, execute the exact previously blocked window focused gate once against the same bound candidate using existing trusted test runner, with complete JUnit+process exit and source/test tree identity. Preserve first failure and stop on an unexpected semantic outcome. If gate passes and the remaining required environment, source and exact committed test preconditions hold, continue adjacent authorized correction/verification and ordinary fast-forward publication/full exact-final-SHA gate as in V2 without artificial segmentation. Never treat historical partial 98/138 or new Reset PASS as full closure on new SHA. Failure or unreadable protected state is a STOP.

## Reconciled next action

NEXT_ACTION_OWNER=IMPLEMENTATION_AGENT
EXACT_NEXT_ACTION=AUTHORIZE_SCOPED_EXACT_ALARM_CAPABILITY_PRECONDITION_ON_TEST_DEVICE_THEN_WINDOW_FOCUSED_VERIFICATION_AND_CHAINED_CLOSURE_WHEN_SAFE
CHAINED_CLOSURE_POLICY=REQUIRED
CHAINED_CLOSURE_BREAK_REASON=DENIED_EXACT_ALARM_CAPABILITY_UNTIL_PROVEN_SAFE_DEVICE_SCOPED_PRECONDITION
BUG-SCHEDULER-01 remains OPEN P2; no new canonical root or CLEAN claim.
