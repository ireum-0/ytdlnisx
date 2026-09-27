# BUG-SCHEDULER-05 clean-basis remediation-ready refinement

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: cdd2571a48f7d8c6edabe7897aae177d466a9f9b
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L5 Platform contract closure DEEP
supporting_lenses:
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing behavior to preserve

The scheduler already centralizes exact-alarm publication in `AlarmScheduler`. Its `publishExactAlarm()` resolves the `AlarmManager` service and calls `setExactAndAllowWhileIdle()`; publication failure retains the durable handoff/fallback recovery path.

API 31+ settings flow already treats exact-alarm special access as a platform capability that may be unavailable.

The bug is the capability predicate on the supported pre-31 band, not alarm identity/recurrence/handoff semantics.

## Exact defect

`AlarmScheduler.canSchedule()` currently returns:

- API 31+: `alarmManager?.canScheduleExactAlarms() == true`
- API <31: `false`

The app supports API 24+. Settings permits scheduler enablement on API 24-30 because it only requests special exact-alarm access on API 31+.

Ordinary queueing and Observe Source later consume the false pre-31 result as "scheduler authority unavailable", disabling/bypassing scheduler behavior even though the actual publication path supports `setExactAndAllowWhileIdle()` on that platform band.

## Exact invariant

`canSchedule()` must answer whether this app can use its actual exact-alarm publication contract on the current platform.

For the supported platform range:
- API 24-30: capability is available when an `AlarmManager` service exists; there is no API-31 exact-alarm special-access query to satisfy.
- API 31+: capability requires an `AlarmManager` service and `canScheduleExactAlarms()==true`.

Every scheduler consumer must interpret this one predicate consistently. A false result may disable/fallback only where the platform contract is genuinely unavailable.

## Narrow implementation boundary

Change only the centralized capability helper first:

`alarmManager ?: return false`
`return if (SDK_INT >= 31) alarmManager.canScheduleExactAlarms() else true`

Then audit all production `canSchedule()` consumers to ensure none duplicates the old "pre-31 false" assumption.

Preserve:
- Settings permission request only on API 31+;
- `publishExactAlarm()` and its durable fallback/handoff behavior;
- scheduler transition coordinator semantics;
- recurrence/midnight logic owned by other scheduler findings;
- exact PendingIntent identity/cancellation behavior.

Do not special-case API 24-30 independently in each caller. The helper is the platform contract.

## Forbidden shortcuts

- returning true unconditionally, including when AlarmManager is unavailable
- requesting `SCHEDULE_EXACT_ALARM`/special access on API 24-30
- changing Settings alone while queue/Observe callers still consume false
- bypassing `canSchedule()` in one caller with an SDK check
- converting API31+ denied access into true
- broad changes to recurrence, schedule windows, or WorkManager handoff semantics

## Acceptance matrix

- API 24 with AlarmManager: canSchedule true
- API 30 with AlarmManager: canSchedule true
- API 24/30 without AlarmManager test double: false
- API 31+ with exact-alarm access: true
- API 31+ without exact-alarm access: false
- pre-31 Settings enable succeeds without permission flow
- pre-31 queue outside schedule window remains scheduler-owned and does not disable `use_scheduler`
- pre-31 Observe Source requeue honors scheduler rather than immediate-start fallback caused solely by capability false
- API31+ denied access retains current permission/fallback behavior
- `publishExactAlarm()` still uses exact publication/fallback and is not rewritten
- scheduler transition recovery remains unchanged
- production-path tests cover Settings + ordinary queue + Observe consumer on representative API bands

## Test gap

The existing source exposes a deterministic SDK branch and no focused API-band regression was identified in the CLEAN-basis revalidation.

This is the same BUG-SCHEDULER-05 root; no new finding ID or count change.
