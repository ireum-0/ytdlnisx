# BUG-SCHEDULER-05 clean-basis revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 620f9ddbc692dff9ec121731b44bc2c26956a341
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
live_completed_implementation_head: 31f55aca76efb1b956567fca5325b2575779a835
active_tooling_wave_inspected: NO

verdict: OPEN P2 / CONFIRMED
new_finding_ids: 0
count_change: 0
canonical_p2: 17
primary_lens: L5 Platform contract closure DEEP
independent_execution: NOT EXECUTED

Source result: the exact CLEAN basis still declares minSdk 24, while AlarmScheduler.canSchedule() returns false for every SDK below 31. DownloadSettingsFragment permits scheduler enablement on that same pre-31 band because it only blocks/requests exact-alarm access when SDK >= 31.

The ordinary DownloadViewModel queue path consumes that false as exact-alarm unavailability outside the configured window, invokes disableForImmediateQueueStart(), durably transitions use_scheduler to false, cancels scheduler authority, and reports the alarm-permission failure. ObserveSourceWorker also consumes the same false and starts requeued downloads immediately instead of honoring the enabled scheduler.

This is inconsistent with AlarmScheduler's own publication path: publishExactAlarm() uses AlarmManager.setExactAndAllowWhileIdle() whenever an AlarmManager service exists, without a pre-31 rejection branch. The false pre-31 capability result is therefore the same existing BUG-SCHEDULER-05 root and remains production-reachable.

No active tools/remediation implementation diff was inspected or used as evidence. No new root or blocker-count change.
