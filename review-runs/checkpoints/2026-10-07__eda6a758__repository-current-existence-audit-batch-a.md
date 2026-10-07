# Repository current-existence audit — batch A — previously remediated active roots

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 5b0c3c8fc6bb2394eee71e55cd4b9b7e3c43271c
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
known_good_baseline_marker: db29f63ce169176b4c8ade4cec01f66cc0307ec8
known_good_verified_source: adf2f347ce9e20ec9f9376cf94053694353c9961

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## Current-delta proof

db29f63..eda6a758 is one forward implementation commit changing only:
- app/src/main/java/com/ireum/ytdl/work/AlarmScheduler.kt
- app/src/main/java/com/ireum/ytdl/work/ScheduledDownloadWindow.kt
- app/src/test/java/com/ireum/ytdl/work/ScheduledDownloadWindowTest.kt
- app/src/androidTest/java/com/ireum/ytdl/work/ScheduledDownloadWindowProductionWiringTest.kt

Therefore previously verified non-scheduler closures at the known-good tree can be carried to eda6a758
when no later review has reopened the root.

## VERIFIED_CLOSED on current SHA

The following roots were explicitly FIXED-CLOSED in the 2026-10-01 cumulative closure and preserved by
the exact adf2f347 final-heavy/KGB review. Their production domains are unchanged by db29f63..eda6a758:

- BUG-DOWNLOAD-01
- BUG-LOCALADD-06
- BUG-UPDATER-02
- BUG-ABI-01
- BUG-HISTORY-04
- BUG-MIGRATION-01
- BUG-RUNTIME-01
- BUG-TERMINAL-06
- BUG-COOKIE-03
- BUG-BACKUP-11
- BUG-PAUSE-03
- BUG-RESUME-01

Current disposition for each: VERIFIED_CLOSED.

Additional final-remediation roots explicitly closed before the KGB and untouched by the current delta:
- BUG-UPDATER-03 — VERIFIED_CLOSED
- BUG-HISTORY-05 — VERIFIED_CLOSED

## BUG-SCHEDULER-05 current direct revalidation

Historical root:
BUG-SCHEDULER-05 — pre-Android-12 exact-alarm capability falsely unavailable.

The current source was reopened directly because AlarmScheduler.kt is one of the four files changed after
the KGB.

Current exact eda6a758 source:
- AlarmScheduler.canSchedule delegates to ExactAlarmCapabilityPolicy.canSchedule;
- the policy requires AlarmManager availability;
- for apiLevel < 31 it returns available without calling canScheduleExactAlarms();
- for API 31+ it requires the platform capability callback.

This preserves the historical correction boundary. The current scheduler-window residual concerns time
membership/end-effect authority and does not revert the pre-31 capability policy.

Current disposition:
- BUG-SCHEDULER-05 — VERIFIED_CLOSED.

## Batch result

roots_audited: 15
verified_closed: 15
verified_open: 0
reopened: 0
not_verified: 0

This batch changes no repository-total count because the full 136-ID semantic lineage is still
IN_PROGRESS. It changes no current download canonical count.

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
